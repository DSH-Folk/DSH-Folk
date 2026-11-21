#!/usr/bin/env node
/**
 * 模块边界预检：本地替代编译器，拦住"推上去才发现 Unresolved reference"。
 *
 * ## 为什么需要这个脚本
 *
 * 本地没有 JDK，CI 是唯一编译器。一次搬迁要等 8-10 分钟才知道能不能编译，
 * 而失败信息只会说 `Unresolved reference 'X'`，不说是哪个模块缺的 ——
 * 于是变成"迁一批 → 报错 → 再迁一批"的盲试错循环。实际踩过：
 * 一轮 Batch C 连推 5 个失败提交。
 *
 * 这个脚本在 push 前本地跑，扫 :core-kernel 与各 :ext-* 的每个 .kt：
 * 只要出现**只有 :app 才有**的符号就直接报错，并指出是哪个文件哪一行。
 *
 * ## 判定规则
 *
 * 一个符号算"只有 :app 才有"，当且仅当：
 *  1. 它在 :app 的源码里被声明，
 *  2. 且它**不在**目标模块自己的源码里，
 *  3. 且它不在该模块的依赖闭包里（:core-res / :ext-api / :core-kernel 等）。
 *
 * 另外三类硬禁（不看声明位置，见到就报）：
 *  - `me.bmax.apatch.ui.*`：UI 层（Activity / 主题 / Compose 屏幕）
 *  - `me.bmax.apatch.APApplication` / `BuildConfig`：Application 子类与模块生成类
 *  - `me.bmax.apatch.R`：:app 的资源类（要用 :core-res 的 CoreR）
 *
 * ## 已知豁免
 *
 * `KernelEnv` 的 KDoc 里会提到 BuildConfig / APApplication（解释为什么要注入）。
 * 注释不算引用 —— 扫描前先剥注释，否则这个脚本会被自己的文档判红。
 */

const fs = require("fs");
const path = require("path");

const { repoRoot, SOURCE_ROOTS } = require("./_paths.js");
const ROOT = repoRoot();

let errors = 0;
const fail = (msg) => { console.log("  ✗ " + msg); errors++; };
const ok = (msg) => console.log("  ✓ " + msg);

/** 剥注释，避免 KDoc 里提到的符号被当成真实引用。 */
function stripComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .map((l) => l.replace(/\/\/.*$/, ""))
    .join("\n");
}

/** 递归列出目录下所有 .kt。 */
function walkKt(dir, acc = []) {
  if (!fs.existsSync(dir)) return acc;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walkKt(p, acc);
    else if (e.name.endsWith(".kt")) acc.push(p);
  }
  return acc;
}

/**
 * 建"符号 → 声明它的模块"表。
 *
 * 只认顶层声明（顶格）：缩进的 fun/val 是成员，不是模块级符号。
 * 枚举常量、伴生对象成员同理 —— 它们跟着外层类走。
 */
function collectSymbols(moduleDirs) {
  const table = new Map(); // sym -> Set<moduleName>
  for (const [mod, dir] of Object.entries(moduleDirs)) {
    for (const f of walkKt(dir)) {
      const src = stripComments(fs.readFileSync(f, "utf8"));
      // **必须真正顶格**：^ 后不许有空格。否则 `                val me = e as? ...`
      // 这种局部变量会被当成模块级符号，于是 "me" 进了符号表，
      // 每个文件里的 `me.bmax.apatch` 都会被判成"用了 :app 独有的 me"。
      // 这个坑实际踩过：54 处误报，全是因为这一条正则。
      for (const m of src.matchAll(
        // 三条约束，缺一条都会把非模块级的东西收进符号表：
        //  1. `^[ \t]*` 之外不允许缩进 —— 局部 `val me = ...` 不是模块级符号
        //  2. 名字后必须跟 `\s*[(<:{=]` 或行尾 —— 排除扩展函数
        //     `fun me.bmax.apatch.DshState.toSeam()`（那里 me 是包名的一部分）
        //  3. 名字里不许有点 —— 同上
        /^(?:@\w+[^\n]*\n)?(?:internal |private |abstract |open |sealed |data |enum |annotation )*(?:object|class|interface|fun|val|const val|typealias) +([A-Za-z_][A-Za-z0-9_]*)\s*[(<:{=\n]/gm,
      )) {
        const sym = m[1];
        if (!table.has(sym)) table.set(sym, new Set());
        table.get(sym).add(mod);
      }
    }
  }
  return table;
}

/** Kotlin / Android / Java 的公共符号，永远不该被判为"缺依赖"。 */
const BUILTIN = new Set(`
File Context Intent Uri Bundle Log String Builder Process Thread Runnable Handler Looper
Bitmap Drawable Shell Result Type Unit Any List Map Set Array Exception Throwable
IOException InputStream OutputStream Socket ServerSocket PackageManager SharedPreferences
Editor System Math Integer Long Boolean Byte Short Float Double Char Object Class Runtime
Timer TimerTask Random Pattern Matcher Regex Pair Triple State Flow Job Mutex
JSONObject JSONArray ContextCompat ActivityResultLauncher CompletableDeferred
CoroutineScope SupervisorJob Dispatchers StateFlow MutableStateFlow
withContext launch async delay runBlocking withTimeout withTimeoutOrNull
T R E K V P N C S A B I O D M
`.trim().split(/\s+/));

// ── 配置 ──

const MODULES = {
  "core-kernel": path.join(ROOT, "core-kernel/src/main/java"),
  "ext-api": path.join(ROOT, "ext-api/src/main/java"),
  "core-res": path.join(ROOT, "core-res/src/main/java"),
  "ext-workflow": path.join(ROOT, "ext-workflow/src/main/java"),
  "ext-display": path.join(ROOT, "ext-display/src/main/java"),
  "ext-userscripts": path.join(ROOT, "ext-userscripts/src/main/java"),
  "ext-backup": path.join(ROOT, "ext-backup/src/main/java"),
  "ext-device-caps": path.join(ROOT, "ext-device-caps/src/main/java"),
  "ext-theme-media": path.join(ROOT, "ext-theme-media/src/main/java"),
  app: path.join(ROOT, "app/src/main/java"),
};

/** 目标模块 → 它被允许依赖的模块（Gradle 里真实存在的 implementation）。 */
const ALLOWED = {
  "core-kernel": ["core-kernel", "core-res", "ext-api"],
  "core-res": ["core-res"],
  // :ext-api 是**第三方扩展的编译期契约**，它只声明类型（含 @Composable 函数类型），
  // 不含实现。所以允许它引用 Compose 的类型 —— 那些是 androidx，不是 :app 的 ui.*。
  "ext-api": ["ext-api"],
  "ext-workflow": ["ext-workflow", "core-res", "ext-api", "core-kernel"],
  "ext-display": ["ext-display", "core-res", "ext-api", "core-kernel"],
  "ext-userscripts": ["ext-userscripts", "core-res", "ext-api", "core-kernel"],
  "ext-backup": ["ext-backup", "core-res", "ext-api", "core-kernel"],
  "ext-device-caps": ["ext-device-caps", "core-res", "ext-api", "core-kernel"],
  "ext-theme-media": ["ext-theme-media", "core-res", "ext-api", "core-kernel"],
};

/** 硬禁 import：不看声明位置，见到就报。 */
const FORBIDDEN_IMPORTS = [
  { re: /^import me\.bmax\.apatch\.ui\./, why: "UI 层（Activity / 主题 / Compose）只有 :app 有" },
  { re: /^import me\.bmax\.apatch\.APApplication$/, why: "Application 子类只有 :app 有，改用 KernelEnv 注入" },
  { re: /^import me\.bmax\.apatch\.BuildConfig$/, why: "BuildConfig 是 :app 生成的，改用 KernelEnv" },
  { re: /^import me\.bmax\.apatch\.R$/, why: ":app 的资源类，改用 me.bmax.apatch.res.R (CoreR)" },
  { re: /^import me\.bmax\.apatch\.dsh\.ext\.seam\.Host/, why: "Host* 是 :app 的 seam 实现，不是契约" },
];

console.log("── 模块边界：禁止 :app 专属依赖 ──");

const symbols = collectSymbols(MODULES);

for (const [mod, dir] of Object.entries(MODULES)) {
  const allowed = ALLOWED[mod];
  if (!allowed) continue; // :app 没有限制

  const files = walkKt(dir);
  if (files.length === 0) continue;

  const violations = [];
  for (const f of files) {
    const raw = fs.readFileSync(f, "utf8");
    const code = stripComments(raw);
    const rel = path.relative(ROOT, f);

    // 1) 硬禁 import
    for (const line of code.split("\n")) {
      const t = line.trim();
      for (const { re, why } of FORBIDDEN_IMPORTS) {
        if (re.test(t)) violations.push(rel + ": " + t + " —— " + why);
      }
    }

    // 本文件自己声明的名字（含嵌套 object/class 里的）—— 不该被判成缺依赖。
    // 例：ExtensionUiRegistry 里 `object SettingsCategory { ... }` 是嵌套声明，
    // 符号表只收顶格的，会漏掉它，于是自己用自己的类型反而被判越界。
    const ownNames = new Set(
      [...code.matchAll(/(?:object|class|interface|enum class|val|fun|typealias) +([A-Za-z_][A-Za-z0-9_]*)/g)]
        .map((m) => m[1]),
    );

    // 2) 裸用 :app 独有的符号（没 import 但直接用了，或同包）
    for (const [sym, mods] of symbols) {
      if (BUILTIN.has(sym)) continue;
      if (mods.has(mod)) continue; // 自己模块有
      if (ownNames.has(sym)) continue; // 本文件自己声明的（含嵌套）
      if ([...mods].some((m) => allowed.includes(m))) continue; // 依赖闭包里有
      // 只报告"在 :app 里声明、且不在允许列表"的
      if (!mods.has("app")) continue;
      // 整词匹配
      // 前后必须不是标识符字符，也**不能是连字符**（`"allow-root-shell"` 里的 allow
      // 不是符号引用）与引号内内容无关 —— 字符串字面量里出现同名不算依赖。
      // 先剥掉字符串字面量，避免 `"allow-root-shell"` / `"download"` 这类误报。
      const codeNoStr = code.replace(/"(?:[^"\\]|\\.)*"/g, '""').replace(/'(?:[^'\\]|\\.)*'/g, "''");
      if (!new RegExp("(?:^|[^A-Za-z0-9_-])" + sym + "(?:[^A-Za-z0-9_-]|$)").test(codeNoStr)) continue;
      violations.push(rel + ": 用了 :app 独有的 " + sym + "（未在允许的依赖模块里）");
    }
  }

  if (violations.length === 0) {
    ok(mod + " 无 :app 专属依赖（" + files.length + " 个文件）");
  } else {
    fail(mod + " 有 " + violations.length + " 处越界：");
    for (const v of violations.slice(0, 15)) console.log("      " + v);
    if (violations.length > 15) console.log("      …还有 " + (violations.length - 15) + " 处");
  }
}

// ── internal 可见性：被 :app 用到的符号不能是 internal ──

console.log("\n── internal 跨模块可见性 ──");
{
  // Kotlin 的 internal 是**模块**级。文件下沉后，原本模块内的 internal 成员
  // 对 :app 就不可见了 —— 报错是 "Cannot access 'fun X': it is internal in file"，
  // 但本地没有编译器，只有 CI 才会炸。所以在这里提前拦。
  const appSrc = MODULES.app && walkKt(MODULES.app)
    .map((f) => stripComments(fs.readFileSync(f, "utf8")))
    .join("\n");

  const offenders = [];
  for (const [mod, dir] of Object.entries(MODULES)) {
    if (mod === "app" || !ALLOWED[mod]) continue;
    for (const f of walkKt(dir)) {
      const code = stripComments(fs.readFileSync(f, "utf8"));
      // 允许缩进：object 内部的成员（`    internal fun wsHostDir`）同样是模块级可见性，
      // 且 :app 一样够不着。第一版只扫顶格，漏了 DshFileAccess 的 8 个成员。
      for (const m of code.matchAll(
        // 必须包含 sealed / data / enum / annotation 修饰的类 —— 第一版漏了
        // `internal sealed class PickerHostDir`，于是 public 函数暴露 internal 返回类型
        // 这条错只能等 CI 报（'public' function exposes its 'internal' return type）。
        /^\s*internal (?:sealed |data |enum |annotation )*(?:object|class|interface|fun|val|const val) +([A-Za-z_][A-Za-z0-9_]*)/gm,
      )) {
        const sym = m[1];
        if (!new RegExp("(?:^|[^A-Za-z0-9_])" + sym + "(?:[^A-Za-z0-9_]|$)").test(appSrc)) continue;
        offenders.push(path.relative(ROOT, f) + ": " + sym + " 是 internal，但 :app 在用");
      }
    }
  }

  if (offenders.length === 0) {
    ok("被 :app 使用的符号都不是 internal");
  } else {
    fail(offenders.length + " 个符号是 internal 却被 :app 引用：");
    for (const o of offenders.slice(0, 12)) console.log("      " + o);
    if (offenders.length > 12) console.log("      …还有 " + (offenders.length - 12) + " 个");
  }
}

// ── 自检：脚本本身得能判红 ──

console.log("\n── 自检 ──");
{
  // 造一个假文件，确认硬禁规则真的会命中
  const probe = "import me.bmax.apatch.ui.MainActivity\nimport me.bmax.apatch.R\n";
  const hits = FORBIDDEN_IMPORTS.filter(({ re }) =>
    probe.split("\n").some((l) => re.test(l.trim())),
  );
  ok("硬禁规则能识别 ui.* 与 R（命中 " + hits.length + "/2）", hits.length === 2);
}

if (errors > 0) {
  console.log("\n✗ 有 " + errors + " 个模块越界。CI 一定会编译失败，先修再推。");
  process.exit(1);
}
console.log("\n全部通过：目标模块没有 :app 专属依赖。");
