#!/usr/bin/env node
/**
 * 搬迁预检：找出「跨模块引用但没 import」和「引用了 :app 专属符号」两类问题。
 *
 * ## 为什么需要这个脚本
 *
 * 拆 `DshRuntime` 时连续失败 20 次 CI，根因是「改文件 → push → 等 CI 报错」的循环：
 * 每次 CI 只报前 10 条错误，改完再等 8 分钟，20 轮就是两个多小时。
 * 而这个仓库本地没有 JDK/Kotlin 编译器，`kt-lint.js` 只查括号与词法，查不出
 * 「符号没 import」「符号在别的模块」这类问题 —— 那正是 CI 唯一能查的。
 *
 * 这个脚本用**纯文本推断**把这类错误提前到本地：
 * 1. 扫目标模块每个 .kt 文件里用到的顶层符号；
 * 2. 减去本文件定义的、同包定义的、标准库的；
 * 3. 剩下的去全仓找「谁定义了这个符号」；
 * 4. 若定义在别的模块且本文件没 import → 报错（CI 必定 Unresolved reference）；
 * 5. 若定义在 :app 且目标模块不是 :app → 报错（架构禁止）。
 *
 * 局限（必须说清楚，否则会误信）：
 * - 不做真正的类型检查，扩展函数、泛型、lambda 参数推断都看不出来；
 * - 对 `Type.member` 形式只看 `Type`，不查成员是否存在；
 * - 因此它报的错**一定**是错（CI 会失败），但它没报的不代表没问题。
 * 定位是「减少 CI 轮次」，不是「替代 CI」。
 */
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, "..");
/** 被扫描（会报错）的模块。 */
const MODULES = ["core-kernel", "core-res", "ext-api", "ext-workflow", "ext-display",
  "ext-userscripts", "ext-backup", "ext-device-caps", "ext-theme-media"];

/**
 * 只建索引、不被扫描的模块。
 *
 * :app 必须在这里 —— 否则「:ext-* 用了 :app 的 APApplication」这类越界
 * 会因为索引里查不到 APApplication 而被当成「平台类」跳过。这是变异测试抓到的漏洞。
 */
const INDEX_ONLY = ["app"];

/** 剥掉注释与字符串字面量：否则 KDoc 里提到的类名会被当成真实引用。 */
function strip(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .map((l) => l.replace(/\/\/.*$/, ""))
    .join("\n")
    .replace(/"""(?:[^"\\]|\\.)*"""/g, '""')
    .replace(/"(?:[^"\\]|\\.)*"/g, '""')
    .replace(/'(?:[^'\\]|\\.)*'/g, "''");
}

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith(".kt")) out.push(p);
  }
  return out;
}

/**
 * 收集一个文件里定义的所有符号名（含嵌套声明）。
 *
 * 允许缩进：\`object ExtensionUiRegistry { object SettingsCategory { ... } }\` 里的
 * SettingsCategory 在本文件内可直接用，不会产生跨模块引用。
 * 早期版本要求行首无缩进，导致 :ext-api 内部引用自己的嵌套 object 被误报成
 * 「用了 :app 的 SettingsCategory」—— 同名符号在两个模块都存在时尤其容易误判。
 */
function defsOf(src) {
  const names = new Set();
  const re = /^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |abstract |open |sealed |data |enum |annotation |value |inline |suspend |const |override )*(?:object|class|interface|fun|val|var) +([A-Za-z_][A-Za-z0-9_]*)/gm;
  let m;
  while ((m = re.exec(src)) !== null) names.add(m[1]);
  return names;
}

/** 收集一个文件 import 进来的名字（含 as 别名）。 */
function importsOf(src) {
  const names = new Set();
  const re = /^import\s+([\w.]+)(?:\s+as\s+(\w+))?$/gm;
  let m;
  while ((m = re.exec(src)) !== null) {
    names.add(m[2] || m[1].split(".").pop());
  }
  return names;
}

const pkgOf = (src) => (src.match(/^package\s+([\w.]+)/m) || [, ""])[1];

// ── 建索引：符号名 → [{module, file, pkg}] ──
const index = new Map();
const moduleFiles = new Map();
for (const mod of [...MODULES, ...INDEX_ONLY]) {
  const files = walk(path.join(ROOT, mod, "src", "main", "java"));
  moduleFiles.set(mod, files);
  for (const f of files) {
    const src = strip(fs.readFileSync(f, "utf8"));
    for (const name of defsOf(src)) {
      // 只索引「大写开头且不是全大写」的名字：类型、object、伴生常量。
      //
      // 排除两类：
      // - 全大写（TAG、MAX_BYTES）：几乎每个文件都有 private const val TAG，
      //   索引进来只会制造「TAG 定义在 :app」这类假警报；
      // - 小写开头（apApp、newId）：顶层 val/fun 到处同名，同理。
      //
      // 注意用「有大写也有小写」而不是「第二个字符是小写」：后者会漏掉
      // APApplication、IDshShellService 这类连续大写的驼峰名。
      if (!/^[A-Z]/.test(name) || name === name.toUpperCase()) continue;
      if (!index.has(name)) index.set(name, []);
      index.get(name).push({ module: mod, file: f, pkg: pkgOf(src) });
    }
  }
}

// Kotlin 内建 / 标准库 / Android 常见符号：不做解析，直接跳过。
const BUILTIN = new Set(("Any Array ArrayList Boolean Byte Char Class Comparable Double Enum " +
  "Exception Float Function Int Iterable Iterator List Long Map MutableList MutableMap " +
  "Number Object Pair Result Set Short String Throwable Triple Unit " +
  "also apply arrayOf asSequence associateBy buildList buildMap buildString by check " +
  "checkNotNull coerceAtMost coerceIn compareTo contains count delay drop dropWhile " +
  "emptyArray emptyList emptyMap emptySet error filter filterNot find first firstOrNull " +
  "flatMap fold forEach getOrElse getOrNull groupBy ifEmpty indexOf isEmpty isNotEmpty " +
  "joinToString last lastOrNull lazy let listOf lock map mapIndexed mapNotNull mapOf maxOf " +
  "minOf mutableListOf mutableMapOf mutableSetOf none onEach orEmpty partition plus put " +
  "readLine reduce remove repeat require requireNotNull reversed run runCatching setOf " +
  "single sorted sortedBy sortedWith split sumOf synchronized take takeIf takeWhile toInt " +
  "toList toLong toMap toMutableList toSet toTypedArray trim trimEnd trimStart tryOrNull " +
  "use with zip println print toString hashCode equals copyOf fillInStackTrace " +
  "Context Intent Bundle File Log Environment Uri Activity Application View " +
  "Composable Modifier remember LaunchedEffect MutableState mutableStateOf " +
  "CoroutineScope Dispatchers Job Mutex SupervisorJob withContext withLock launch async " +
  "StateFlow MutableStateFlow Flow collect collectAsState SharedPreferences Editor " +
  "AtomicBoolean AtomicInteger Volatile Synchronized Thread Runnable Handler Looper " +
  "JSONObject JSONArray JSONException Regex Matcher Pattern StringBuilder " +
  "InputStream OutputStream BufferedReader BufferedWriter FileInputStream FileOutputStream " +
  "ByteArray CharArray Process ProcessBuilder TimeUnit System Runtime " +
  "getString getInt getLong getBoolean getFloat edit apply commit putString putInt " +
  "putLong putBoolean remove clear contains keySet all keys values entries").split(/\s+/));

/**
 * 常见注解名：Kotlin/JVM 标准库的注解在很多模块里都有同名类（比如 :app 的
 * DshDeepClean.Target），按名字查索引必然误报。这些名字直接跳过。
 *
 * 只放**注解**：注解本身不参与运行时逻辑，误用也不会造成架构问题；
 * 放行普通类型的名字会掩盖真实的越界引用。
 */
const ANNOTATIONS = new Set([
  "Target", "Retention", "Repeatable", "MustBeDocumented", "Deprecated",
  "Suppress", "JvmStatic", "JvmField", "JvmOverloads", "JvmName", "JvmMultifileClass",
  "Synchronized", "Volatile", "Transient", "Strictfp", "Throws", "JvmSuppressWildcards",
  "OptIn", "RequiresApi", "Keep", "NonNull", "Nullable", "VisibleForTesting",
]);

let errors = 0;
const report = [];

for (const mod of MODULES) {
  for (const f of moduleFiles.get(mod)) {
    const raw = fs.readFileSync(f, "utf8");
    const src = strip(raw);
    const ownPkg = pkgOf(src);
    const defs = defsOf(src);
    const imps = importsOf(src);
    // 同包内其它文件的定义（Kotlin 同包免 import）
    const samePkg = new Set();
    for (const g of moduleFiles.get(mod)) {
      if (g === f) continue;
      const gsrc = strip(fs.readFileSync(g, "utf8"));
      if (pkgOf(gsrc) !== ownPkg) continue;
      for (const n of defsOf(gsrc)) samePkg.add(n);
    }
    // 扫「首字母大写」的疑似类型引用（类型一定是大写开头）
    const used = new Set();
    const re = /(?<![\w.])([A-Z][A-Za-z0-9_]*)(?=[\s(.:<>,)?\[])/g;
    let m;
    while ((m = re.exec(src)) !== null) used.add(m[1]);
    for (const name of used) {
      if (defs.has(name) || samePkg.has(name) || imps.has(name) || BUILTIN.has(name)) continue;
      if (ANNOTATIONS.has(name)) continue;
      const hits = index.get(name);
      if (!hits) continue; // 可能是平台类，跳过
      // 若同名符号在本模块自己也有定义，说明引用的是本模块那份（Kotlin 就近解析），
      // 不必跨模块 import。这条挡掉「:app 有个 DshDeepClean.Target，而
      // :core-kernel 用的是 kotlin.annotation.Target」这类同名遮蔽误报。
      if (hits.some((h) => h.module === mod)) continue;
      const rel = path.relative(ROOT, f);
      const fromApp = hits.some((h) => h.module === "app");
      const sameModule = hits.some((h) => h.module === mod);
      if (fromApp && mod !== "app") {
        errors++;
        report.push(`  ${rel}: ${name} 定义在 :app（${path.relative(ROOT, hits.find((h) => h.module === "app").file)}），本模块不能直接用`);
      } else if (!sameModule) {
        // Kotlin 的包是跨模块的：只要 package 声明相同，即使定义在另一个模块也免 import。
        // 这一点极易误判 —— 搬迁时把文件移进同包的新模块，是不需要加 import 的。
        if (hits.some((h) => h.pkg === ownPkg)) continue;
        errors++;
        const h = hits[0];
        report.push(`  ${rel}: ${name} 定义在 :${h.module}（${h.pkg}），缺 import`);
      }
    }
  }
}

console.log("── 搬迁预检：跨模块符号 ──");
if (errors === 0) {
  console.log(`  ✓ ${MODULES.length} 个模块没有「缺 import」或「误用 :app 符号」`);
} else {
  console.log(`  ✗ ${errors} 处问题：`);
  for (const line of report.slice(0, 40)) console.log(line);
  if (report.length > 40) console.log(`  …还有 ${report.length - 40} 处`);
}

process.exit(errors === 0 ? 0 : 1);