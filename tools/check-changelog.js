#!/usr/bin/env node
// 校验「更新说明」与「测试版通道」这两件事的内部一致性。
//
// 它们各自都有一个安静失效的方式：
//
//  1. 更新说明的版本号写在三个地方（build.gradle.kts 的基准、util/Changelog.kt 的
//     VERSION、以及那份条目文案）。发版时改了版本却忘了改文案，用户看到的是「1.8.1
//     更新了什么」配上 1.8.0 的内容 —— 一句自信的假话。运行时有兜底（版本不符就不弹），
//     但那意味着**新版本的用户什么都看不到**，而没人会发现。
//
//  2. 测试版通道依赖三处配合：工作流发的是 prerelease、tag 长得像版本号、App 侧按
//     prerelease 标记与 tag 后缀排除。任一处漏掉的后果都是「开关看起来没用」或者更糟 ——
//     「关着开关的人也被推上测试版」。
//
// 这些全都没有编译期信号，也不会让任何测试变红。
const fs = require("fs");
const path = require("path");

const ROOT = path.resolve(__dirname, "..");
const read = (p) => require("./_paths.js").read(p);

let fail = 0;

/**
 * 取一个顶层函数的**函数体**。
 *
 * 不用「从函数名往后取 N 个字符」：文件里下一个函数的内容会滑进这个窗口，让「这个
 * 函数里必须出现 X」的断言在 X 被删掉之后照样通过（第一版就是这样，反向验证抓到了）。
 * 这里按花括号深度找真正的结束位置。
 */
function fnBody(src, header) {
  const at = src.indexOf(header);
  if (at < 0) return null;
  const open = src.indexOf("{", at);
  // 表达式体的函数（`fun f() = a && b`）没有 `{`，或者它的 `{` 属于后面某个函数 ——
  // 两种情况都退到 memberBody 的「切到下一个同级声明」策略。
  const nextDecl = nextMemberAt(src, at);
  if (open < 0 || (nextDecl > 0 && open > nextDecl)) return memberBody(src, at);
  let depth = 0;
  for (let i = open; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}") {
      depth--;
      if (depth === 0) return src.slice(at, i + 1);
    }
  }
  return null;
}

/** 下一个同级成员声明的位置（-1 表示没有）。 */
function nextMemberAt(src, from) {
  const re = /\n(?:\s{0,4})(?:@|fun |val |var |const |private |internal |object |class )/g;
  re.lastIndex = from + 1;
  const m = re.exec(src);
  return m ? m.index : -1;
}

/** 从 [from] 切到下一个同级成员声明，用于表达式体的函数。 */
function memberBody(src, from) {
  const end = nextMemberAt(src, from);
  return src.slice(from, end < 0 ? src.length : end);
}
function ok(cond, msg) {
  console.log((cond ? "  ✓ " : "  ✗ ") + msg);
  if (!cond) fail++;
}

/** 剥 Kotlin 注释：`must not appear` 类断言必须扫剥过的文本。 */
function code(src) {
  return src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}

const gradle = read("build.gradle.kts");
const changelogKt = code(read("app/src/main/java/me/bmax/apatch/util/Changelog.kt"));
const stringsEn = read("app/src/main/res/values/strings.xml");
const stringsZh = read("app/src/main/res/values-zh-rCN/strings.xml");
const checker = code(read("app/src/main/java/me/bmax/apatch/util/UpdateChecker.kt"));
const folkClient = code(read("app/src/main/java/me/bmax/apatch/util/FolkApiClient.kt"));
// 运行时安装/更新已迁到 DshRuntimeInstall.kt（Checkpoint 1 拆解）。
// 共享状态（含两把锁）在 DshKernelState.kt。
const runtime = code(read("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt") + "\n" + read("app/src/main/java/me/bmax/apatch/dsh/DshRuntimeInstall.kt") + "\n" + read("app/src/main/java/me/bmax/apatch/dsh/DshKernelState.kt"));
const mainActivity = code(read("app/src/main/java/me/bmax/apatch/ui/MainActivity.kt"));
const settings = code(read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt"));
/** 剥 YAML 注释：注释里解释某个 flag 为什么重要，不能算作那个 flag 存在。 */
function yml(src) {
  return src
    .split("\n")
    .filter((l) => !/^\s*#/.test(l))
    .join("\n");
}

const betaYml = yml(read(".github/workflows/beta.yml"));
const home = code(read("app/src/main/java/me/bmax/apatch/ui/screen/Home.kt"));
const dialog = code(read("app/src/main/java/me/bmax/apatch/ui/component/WelcomeGuide.kt"));

// ── 1. 版本号三处一致 ──
console.log("── 版本号 ──");
const baseName = gradle.match(/fun baseVersionName\(\): String = "([^"]+)"/);
const baseCode = gradle.match(/fun baseVersionCode\(\): Int = (\d+)/);
ok(baseName !== null, "build.gradle.kts 有 baseVersionName()" + (baseName ? ` = ${baseName[1]}` : ""));
ok(baseCode !== null, "build.gradle.kts 有 baseVersionCode()" + (baseCode ? ` = ${baseCode[1]}` : ""));

// `.kts` 的脚本体被编译成一个类的主体，那里**不允许** const val —— 写了就是
// 「Const 'val' is only allowed on top level…」，而这只有 Kotlin 编译器会说，
// 也就是要等 CI 十几分钟。基准版本一律用函数：另一个候选（普通 val）有更安静的坑，
// 见那段 KDoc。
ok(!/^\s*(private )?const val/m.test(gradle),
  "build.gradle.kts 里没有 const val（脚本体不允许，只有 Kotlin 编译器会告诉你）");

// 基准版本必须是**没有副作用、没有初始化顺序**的函数：`managerVersionCode by
// extra(getVersionCode())` 在脚本很靠前的位置就执行，一个声明在后面的 val 此刻还是
// 默认值，构建会静默拿到错误的版本号。
ok(/fun baseVersionName\(\)/.test(gradle) && /fun baseVersionCode\(\)/.test(gradle),
  "基准版本是函数而不是属性（脚本里的 val 有初始化顺序，会静默取到 0）");

const clVersion = changelogKt.match(/VERSION = "([^"]+)"/);
ok(clVersion !== null, "Changelog.VERSION 存在" + (clVersion ? ` = ${clVersion[1]}` : ""));
if (baseName && clVersion) {
  ok(baseName[1] === clVersion[1],
    `Changelog.VERSION 与 baseVersionName() 一致（${clVersion[1]} vs ${baseName[1]}）`);
}

// versionCode 必须与版本名对应：1.8.0 → 10800。beta.yml 里那段推导用的是同一规则，
// 两边脱钩会让测试版的 versionCode 落在正式版的错误一侧。
if (baseName && baseCode) {
  const parts = baseName[1].split(".").map((x) => parseInt(x, 10));
  // 四段式（补丁版）= 前三位算出的号 + 第四段：1.8.2.1 → 10803、1.9.2.1 → 10903、1.9.2.2 → 10904。
  // 必须**大于**它修补的那个正式版，否则 App 内更新检查认不出、系统也可能拒绝覆盖
  // （相同号允许覆盖，但「比它大」才是我们想要的语义）。
  const floor = parts.length === 3
    ? parts[0] * 10000 + parts[1] * 100 + parts[2]
    : parts.length === 4
      ? parts[0] * 10000 + parts[1] * 100 + parts[2] + parts[3]
      : null;
  // 公式值是**下限**：正式版可以显式抬高 versionCode，以便盖过此前测试版线里已用掉的号
  // （测试版 1.9.2.34-beta.101 的 vc 已是 10936，公式给正式版 1.9.5 只有 10905 会被系统当降级、
  // 装了 beta 的用户无法覆盖更新）。所以只要求 ≥ 公式下限、且不小于本项目已发布过的最高号。
  // 注意：手动抬高后，后续走 beta.yml 公式推导的测试版号可能低于它，需要人工确认单调递增
  // （beta.yml 会拿 baseVersionCode() 当下限取 max，所以这个数也是测试版号的输入）。
  //
  // **每发一个正式版就把它抬到那版的 baseVersionCode()** —— 它记的是已经发出去的最高号，
  // 不是"当前版本号"。抬晚了（或忘了抬）就会出现「新版本的号低于用户已装的号」：系统按降级
  // 拒绝安装、App 的 compareVersions 也不会提示，整个更新通道静默失效。2.0.0 → 20000。
  const PUBLISHED_FLOOR = 30003; // 已发布过的最高 baseVersionCode（正式版 3.0.3；抬到 30003 前是 3.0.2 的 30002）
  ok(floor !== null && Number(baseCode[1]) >= floor && Number(baseCode[1]) >= PUBLISHED_FLOOR,
    `baseVersionCode() 不低于公式下限且盖过已发布最高号（${baseName[1]} → 下限 ${floor} / 已发 ${PUBLISHED_FLOOR}，实际 ${baseCode[1]}）`);
}

// CI 覆盖必须存在：没有它，测试版工作流传的 -P 会被静默忽略，
// 发出去的每个 beta 都自称正式版号，而 App 判成「不更新」
ok(/dshVersionOverride\("dshVersionName"\)/.test(gradle), "版本名可被 -PdshVersionName 覆盖");
ok(/dshVersionOverride\("dshVersionCode"\)/.test(gradle), "版本号可被 -PdshVersionCode 覆盖");
ok(/providers\.gradleProperty/.test(gradle),
  "覆盖走 providers.gradleProperty（findProperty 会让配置缓存失效）");

// ── 2. 更新说明的内容 ──
console.log("\n── 更新说明 ──");
for (const [label, xml] of [["values", stringsEn], ["values-zh-rCN", stringsZh]]) {
  const arr = xml.match(/<string-array name="changelog_items">([\s\S]*?)<\/string-array>/);
  ok(arr !== null, `${label} 里有 changelog_items`);
  if (arr) {
    const items = [...arr[1].matchAll(/<item>([\s\S]*?)<\/item>/g)].map((m) => m[1].trim());
    ok(items.length > 0, `${label} 的更新条目非空（${items.length} 条）`);
    ok(items.every((x) => x.length > 0), `${label} 没有空条目`);
  }
  ok(/name="changelog_title"/.test(xml), `${label} 里有 changelog_title`);
  ok(/name="changelog_got_it"/.test(xml), `${label} 里有 changelog_got_it`);
}
// 两个语言的条目数必须一样：少一条不会报错，只是那一条对某个语言的用户消失了
{
  const n = (xml) => {
    const a = xml.match(/<string-array name="changelog_items">([\s\S]*?)<\/string-array>/);
    return a ? [...a[1].matchAll(/<item>/g)].length : -1;
  };
  ok(n(stringsEn) === n(stringsZh),
    `两个语言的更新条目数一致（${n(stringsEn)} / ${n(stringsZh)}）`);
}

// ── 3. 显示时机 ──
console.log("\n── 显示时机 ──");
{
  const body = fnBody(changelogKt, "fun shouldShow(") || "";
  ok(body.length > 0, "Changelog.shouldShow 存在");
  // 查的是**条件本身**，不是形参名：形参删不掉，条件才是会被删的那个
  ok(/welcomeShown &&/.test(body),
    "shouldShow 把「首启引导已看过」作为前提（新装的人不该看到「本次更新」）");
  ok(/VERSION == currentCoreVersion\(\) &&/.test(body),
    "版本不符时不显示（宁可不说，也不能把旧内容配新版本号）");
  ok(/shownFor != VERSION/.test(body), "同一版本只弹一次");
}
ok(/substringBefore\('-'\)/.test(changelogKt),
  "比较用主版本号（测试版是 1.8.1-beta.7，同批共用一份说明）");
ok(/showWelcomeGuide/.test(home) && /else if \(showChangelog\)/.test(home),
  "首启引导与更新说明互斥（两个对话框叠在一起会互相盖住按钮）");
ok(new RegExp("putString\\(Changelog\\.KEY_SHOWN_FOR").test(home),
  "关掉之后记下已弹过的版本");
// 引导那条路径也要记：不然刚装完的人关掉引导，下一秒又看到「本次更新」
{
  const welcomeBlock = home.slice(
    home.indexOf("if (showWelcomeGuide) {"),
    home.indexOf("} else if (showChangelog) {")
  );
  ok(/KEY_SHOWN_FOR/.test(welcomeBlock),
    "看完首启引导也记成「更新说明已弹过」（否则关掉引导立刻又弹一个）");
}

// ── 3b. 10.8 的生日彩蛋 ──
//
// 这个彩蛋的判据**一年只跑得到一次**：写错了（记成布尔 → 一辈子只弹一次；或按 UTC 判 →
// 某些时区差一天）要等到明年 10.8 才有人发现。所以这里把月份、日期、年份比较、prefs 键、
// 以及它在互斥分支里的位置全部钉住。
console.log("\n── 生日彩蛋（10.8） ──");
{
  const egg = read("app/src/main/java/me/bmax/apatch/util/BirthdayEgg.kt");
  const home2 = read("app/src/main/java/me/bmax/apatch/ui/screen/Home.kt");
  const dshEn = read("app/src/main/res/values/dsh_strings.xml");
  const dshZh = read("app/src/main/res/values-zh-rCN/dsh_strings.xml");
  ok(/const val MONTH = 10/.test(egg) && /const val DAY = 8/.test(egg),
    "生日就写在 BirthdayEgg 里：10 月 8 日（不散到界面里）");
  ok(/today\.monthValue == MONTH && today\.dayOfMonth == DAY/.test(egg),
    "isBirthday 判的正是这两位");
  ok(/fun shouldShow\(ctx: Context, today: LocalDate = LocalDate\.now\(\)\)/.test(egg),
    "shouldShow 的「今天」可以注入（否则这条判据根本没法验）");
  ok(/isBirthday\(today\) && prefs\(\)\.getInt\(KEY_SHOWN_YEAR, 0\) != today\.year/.test(egg),
    "该弹 = 今天是 10.8 **且今年还没弹过**");
  ok(/putInt\(KEY_SHOWN_YEAR, today\.year\)/.test(egg),
    "弹过记的是年份（记成布尔 → 这辈子只弹一次）");
  ok(/KEY_SHOWN_YEAR = "birthday_egg_shown_year"/.test(egg) &&
    (egg.match(/birthday_egg_shown_year/g) || []).length === 1,
    "prefs 键只有一处字面量（第二处写歪了就是同一个彩蛋弹两次或永不弹）");
  ok(/import java\.time\.LocalDate/.test(egg) && /LocalDate\.now\(\)/.test(egg),
    "用设备本地日期（按 UTC 判会在某些时区差一天）");
  const minSdk = gradle.match(/androidMinSdkVersion by extra\((\d+)\)/);
  ok(minSdk !== null && Number(minSdk[1]) >= 26,
    `java.time 要 API 26（minSdk 现在 ${minSdk ? minSdk[1] : "?"}）—— 低于它会在老机器上 NoClassDefFoundError`);
  // 互斥分支的顺序：彩蛋最不重要，排最后
  const birthdayAt = home2.indexOf("else if (showBirthday)");
  const policyAt = home2.indexOf("else if (showPolicyNotice)");
  const changeAt2 = home2.indexOf("else if (showChangelog)");
  ok(birthdayAt > 0 && policyAt > 0 && changeAt2 > 0 && policyAt > changeAt2 && birthdayAt > policyAt,
    "彩蛋排在整串互斥分支的**最后**（引导 → 更新说明 → 策略变更 → 彩蛋）");
  {
    const block = home2.slice(birthdayAt, home2.indexOf("ProvideDshHomeState"));
    // 两条关闭路径都走同一个 dismiss：标记与关闭只有一处，少接一处就会同一天反复弹
    ok(/val dismissBirthday: \(\) -> Unit = \{[\s\S]{0,120}BirthdayEgg\.markShown\(\)/.test(home2) &&
      (block.match(/dismissBirthday\(\)/g) || []).length >= 2,
      "点按钮和点外面都走同一个 dismiss（且那个 dismiss 里真的记了「已弹过」）");
  }
  ok(/mutableStateOf\(BirthdayEgg\.shouldShow\(homeContext\)\)/.test(home2),
    "进首页判一次就够（日期在一次 composition 里不会变）");
  for (const [label, xml] of [["values", dshEn], ["values-zh-rCN", dshZh]]) {
    for (const k of ["dsh_birthday_title", "dsh_birthday_text", "dsh_birthday_ok"]) {
      ok(xml.includes(`name="${k}"`), `${label} 有 ${k}`);
    }
  }
  // 彩蛋不进更新说明 —— 提前写出来就不叫彩蛋了
  ok(!/生日|birthday/i.test(stringsEn) && !/生日|birthday/i.test(stringsZh),
    "changelog_items 里不提彩蛋（更新说明是给人看的清单，彩蛋是惊喜）");
  const rd = read("docs/dev-notes.md");
  const rdEn = read("docs/dev-notes.en.md");
  ok(/BirthdayEgg/.test(rd) && /10 月 8 日/.test(rd), "dev-notes 记了彩蛋与其判据（中文）");
  ok(/BirthdayEgg/.test(rdEn) && /October 8/.test(rdEn), "dev-notes.en 也记了（英文）");
}

// ── 4. 复用的是同一个壳 ──
console.log("\n── 对话框复用 ──");
ok(/fun PagedInfoDialog\(/.test(dialog), "有公用的 PagedInfoDialog");
for (const name of ["WelcomeGuideDialog", "ChangelogDialog"]) {
  const body = fnBody(dialog, `fun ${name}(`);
  ok(body !== null, `${name} 存在`);
  if (body) {
    ok(/PagedInfoDialog\(/.test(body), `${name} 走的是 PagedInfoDialog（不是另做一套壳）`);
  }
}
// 首启引导必须**不可**随手关掉：它是一道门，关掉就再也不出现
{
  const body = fnBody(dialog, "fun WelcomeGuideDialog(") || "";
  ok(/dismissible = false/.test(body), "首启引导不可点外面/返回键关掉");
}

// ── 5. 测试版通道 ──
console.log("\n── 测试版通道 ──");
// 通道从「一个布尔」升级成「三态枚举」之后，这一节钉的东西也跟着换了。
// 旧断言钉的是 acceptBeta 这个布尔（check(acceptBeta = false)、if (!acceptBeta && isBeta(...))），
// 那些写法在加了 Alpha 之后**表达不了三态**，所以断言必须改成钉新语义 ——
// 否则门禁会把旧实现当成规范，谁改谁红。
const channel = code(read("app/src/main/java/me/bmax/apatch/util/UpdateChannel.kt"));
ok(/enum class UpdateChannel/.test(channel), "有 UpdateChannel 三态枚举");
for (const id of ["stable", "beta", "alpha"]) {
  ok(channel.includes('"' + id + '"'), `通道 ${id} 有落盘 id`);
}
ok(/fun allows\(other: UpdateChannel\): Boolean = other\.ordinal <= this\.ordinal/.test(channel),
  "通道是「上限」语义：选 Alpha 时同时收 Beta/Stable（按后缀精确匹配会让 Alpha 用户看不到正式版）");
// 旧布尔键必须继续被读：直接换新键会让存量测试版用户静默掉回正式版
ok(/LEGACY_KEY_ACCEPT_BETA/.test(channel) && /getBoolean\(LEGACY_KEY_ACCEPT_BETA, false\)/.test(channel),
  "旧布尔键仍作迁移来源（否则存量测试版用户会静默掉回正式版）");
ok(/if \(stored != null\) return fromId\(stored\)/.test(channel),
  "迁移只在新键不存在时发生一次（之后用户的选择是唯一事实）");
// 通道读写必须与设置页同一份 prefs：写一份读另一份 = 界面显示开了、检查读到关
// sharedPreferences 是 **伴生对象** 成员，必须走 APApplication.sharedPreferences。
// 曾经写成 app.sharedPreferences（实例访问）—— 本地门禁看不出来，CI 才报 Unresolved reference。
ok(/APApplication\.sharedPreferences/.test(channel),
  "通道读写复用 APApplication.sharedPreferences（伴生成员，不是实例成员；与设置页同源）");

ok(/suspend fun check\(channel: UpdateChannel = UpdateChannel\.STABLE\)/.test(checker),
  "check() 的通道默认 STABLE（与历史行为一致：旧布尔缺省 false 就是只收正式版）");
ok(/if \(channel == UpdateChannel\.STABLE\) \{[\s\S]{0,120}LIST_PATH, LATEST_PATH/.test(checker),
  "非 Stable 通道先查列表：releases/latest 定义上跳过 prerelease，先问它会让通道失效");
ok(/private fun isBeta\(/.test(checker), "有 isBeta 判据");
{
  // 取到**下一个 4 空格缩进的声明**为止，而不是固定 400 字符、也不是找 "\n    }" ——
  // isBeta 是表达式函数体（"= release.optBoolean(...) || ..."），根本没有右花括号，
  // 找花括号会一路吃到下一个函数里去，让断言在无关改动下假绿。
  const at = checker.indexOf("private fun isBeta(");
  const rest = at >= 0 ? checker.slice(at + 1) : "";
  const nextDecl = rest.search(/\n    (private |fun |val |internal |@)/);
  const body = at >= 0 ? checker.slice(at, nextDecl >= 0 ? at + 1 + nextDecl : checker.length) : "";
  ok(/optBoolean\("prerelease"\)/.test(body) && /substringAfter\('-',/.test(body),
    "isBeta 两道判断都在（prerelease 标记 + tag 的预发布后缀）");
}
// 过滤必须走 channelOf + allows，且列表与 latest 两条路径都做
ok(/if \(!channel\.allows\(channelOf\(o, tag\)\)\) continue/.test(checker),
  "列表路径按通道过滤");
ok(/if \(!channel\.allows\(channelOf\(o, tag\)\)\) return null/.test(checker),
  "latest 路径同样按通道过滤（只过滤一条 = 另一条把不该给的版本推给所有人）");
// 强制过渡版本：漏了这条，用户会跳过数据迁移直接升到最新，数据停在旧结构上
ok(/private fun isMandatoryBridge\(/.test(checker), "有强制过渡版本判据");
ok(/BRIDGE_MARKER/.test(checker) && /equals\(BRIDGE_MARKER, ignoreCase = true\)/.test(checker),
  "过渡标记按整行精确匹配（子串匹配会让正文里随口提到该词的发布被误判）");
ok(/private fun pickWithMandatoryBridge\(/.test(checker),
  "候选里优先挑过渡版本（否则直接跳到最新版 = 跳过数据迁移）");
ok(/val isMandatoryBridge: Boolean = false/.test(checker),
  "Status 带 isMandatoryBridge，UI 才能禁掉「跳过此版本」");
{
  // ── 自动检查与手动检查不能互相打断 ──
  //
  // 冷启动时应用更新与运行时更新是**并行**的两条自动检查，而
  // cancelInFlightRequests() 取消的是 FolkApiClient 里**全部**在途请求。
  // 用户在两秒内点「手动检查应用更新」，原来会把运行时那条一起取消 ——
  // 它拿到 null 后静默当作"没查到"，运行时更新提示时有时无且不报任何错。
  ok(/fun cancelTagged\(tag: String\)/.test(folkClient),
    "FolkApiClient 支持按标签取消（不分标签就会误杀另一条检查）");
  ok(/cancelTagged\(TAG_APP_UPDATE\)/.test(checker),
    "手动检查只取消应用更新这条链，不动运行时那条");
  // 只查"文件里有这两个词"不够：注释里提到 updateCheckMutex 就会假绿。
  // 钉住**声明**与**包裹**两处，且必须是 checkRuntimeUpdate 这个函数。
  // Mutex 已移到 DshKernelState（拆解后多个职责类共用同一把锁）。
  ok(/val updateCheckMutex = kotlinx\.coroutines\.sync\.Mutex\(\)/.test(runtime),
    "运行时更新检查有 Mutex 声明（自动与手动并发时不重复请求、不互相覆盖结果）");
  // ── 运行时更新提示：自动与手动不能各弹一次 ──
  //
  // 自动检查（MainActivity 冷启动 2.5s 后）与手动检查（设置页卡片）是两条独立路径，
  // 各自弹各自的确认框。用户冷启动后进设置点一下「检查更新」，同一个更新会弹两次窗。
  // 修法是让两边共用一份「已经提示过这个版本」的事实。
  ok(/fun claimRuntimePrompt\(version: String\): Boolean/.test(runtime),
    "DshRuntime 有提示去重闸门（否则自动+手动各弹一次）");
  // 改成局部变量再传：result.version 现在是跨模块 public 属性，
  // Kotlin 不给它做 smart cast，所以先取到 newVersion。
  ok(/claimRuntimePrompt\(newVersion\)/.test(mainActivity) &&
    /val newVersion = result\?\.version/.test(mainActivity),
    "自动检查登记提示权");
  // 跨模块属性不做 smart cast，改成局部变量 promptVersion
  ok(/claimRuntimePrompt\(promptVersion\)/.test(settings) &&
    /val promptVersion = result\?\.version/.test(settings),
    "设置页手动检查也登记提示权（共用同一闸门）");
  ok(/fun clearPromptedRuntimeVersion\(\)/.test(runtime),
    "提示关掉时放开闸门（否则用户手动再查一次看不到结果）");
{
  // ── GitHub 令牌：加密存储 + 只发给 api.github.com ──
  const token = code(read("app/src/main/java/me/bmax/apatch/util/GithubTokenStore.kt"));
  ok(/AES\/GCM\/NoPadding/.test(token),
    "令牌用 AES-GCM 加密（GCM 自带完整性校验，密文被改过会抛而不是解出垃圾）");
  ok(/AndroidKeyStore/.test(token),
    "密钥在 AndroidKeyStore（密钥不出安全硬件，拿到 prefs 文件也解不开）");
  ok(/setUserAuthenticationRequired\(false\)/.test(token),
    "不要求用户认证（更新检查可能在后台跑，要求解锁会静默失败）");
  ok(/fun masked\(context: Context\): String\?/.test(token),
    "有打码展示（设置页要能确认配的是哪个，但不能把完整令牌画在屏幕上）");
  // 关键安全边界：令牌只能发给 api.github.com，绝不能跟着代理发出去
  // 用**原始文本**而不是 code()：code() 会剥掉 "//" 之后的内容，而这里要检查的
  // 恰好是含 "https://" 的那一行 —— 用 code() 会让断言永远为假（假红）。
  const apiRaw = read("app/src/main/java/me/bmax/apatch/util/FolkApiClient.kt");
  ok(apiRaw.includes('url.startsWith("https://api.github.com/")'),
    "令牌只对 api.github.com 发送（跟着 gh-proxy 发出去等于把凭据交给中间人）");
  ok(apiRaw.includes('Authorization') && apiRaw.includes('Bearer '), "带 Bearer 头");
  const gen = code(read("app/src/main/java/me/bmax/apatch/ui/screen/settings/GeneralSettings.kt"));
  // ── 搜索索引的文案必须与卡片上的文案一致 ──
  //
  // 踩过一次：无障碍那张卡的标题是「无障碍服务保持开启」，而索引里用了它**内层开关**
  // 的文案「自动恢复」。用户照着卡片上的字去搜「无障碍」，什么也搜不到 ——
  // 索引里的字和界面上的字不是同一批。
  const regRaw = read("app/src/main/java/me/bmax/apatch/ui/screen/settings/SettingsRegistry.kt");
  const a11yEntry = regRaw.match(/SettingEntry\("function_a11y_recovery",\s*(?:Core)?R\.string\.(\w+),\s*(?:Core)?R\.string\.(\w+)/);
  ok(a11yEntry !== null, "无障碍恢复在搜索索引里（否则搜「无障碍」找不到）");
  if (a11yEntry) {
    ok(a11yEntry[1] === "dsh_a11y_fix_title",
      "索引用卡片标题 dsh_a11y_fix_title（=「无障碍服务保持开启」），不是内层开关的「自动恢复」");
    ok(a11yEntry[2] === "dsh_a11y_fix_summary", "索引用卡片摘要，不是子选项的说明");
  }
  ok(/GithubTokenDialog/.test(gen), "设置页有令牌输入弹窗");
  ok(/settings_github_token_readonly_hint/.test(gen),
    "明确建议只读令牌（我们只读 Releases/元数据，写权限是纯风险）");
}
{
  // ── 安装与启动分开 ──
  ok(/fun updateRuntime\(preserveData: Boolean = true, autoStart: Boolean = true\)/.test(runtime),
    "updateRuntime 可指定装完是否自动启动");
  // 放宽为 internal（withLock 的 lambda 里要能调）。
  ok(/(?:private |internal )fun markInstalledIdle\(\)/.test(runtime),
    "装完不启动要落回「已安装未运行」（否则首页一直转圈）");
  const home = code(read("app/src/main/java/me/bmax/apatch/ui/screen/HomeDsh.kt"));
  ok(/if \(installed\) onStart else onInstall/.test(home),
    "未运行时主按钮按「已装/未装」分派（以前恒为「启动」，点下去却先下 150MB）");
  ok(/dsh_install_confirm_body/.test(home), "安装前有下载告知");
  ok(/dsh_installed_done_body/.test(home), "装完问要不要启动");
  ok(/dsh_restart_confirm_body/.test(home), "重启前提醒保存");
}
{
  // ── 容器后端：无权限时弹窗而不是灰按钮 ──
  const fs2 = code(read("app/src/main/java/me/bmax/apatch/ui/screen/settings/FunctionSettings.kt"));
  ok(/deniedBackend = "chroot"/.test(fs2) && /deniedBackend = "proroot"/.test(fs2),
    "无权限的后端点下去弹说明（灰按钮只表达「不行」，不表达「为什么」）");
  ok(/dsh_mode_chroot_denied_body/.test(fs2), "chroot 无权限有说明");
  ok(/dsh_mode_denied_ok/.test(fs2), "弹窗有确认按钮");
}
{
  // ── 扩展：从链接 / 从文件安装 ──
  const mkt = code(read("app/src/main/java/me/bmax/apatch/dsh/ext/ExtensionMarket.kt"));
  ok(/suspend fun installFromUrl\(/.test(mkt), "扩展支持从链接安装");
  ok(/suspend fun installFromFile\(/.test(mkt), "扩展支持从文件安装");
  // 只查"文件里有 MAX_ZIP_BYTES"不够：注释里提到就会假绿。钉住**声明**与**使用**两处。
  ok(/private const val MAX_ZIP_BYTES = \d+L \* 1024 \* 1024/.test(mkt),
    "有大小上限的常量声明（防止一个链接把存储写满）");
  ok(/if \(total > MAX_ZIP_BYTES\)/.test(mkt),
    "上限真的在读取循环里生效（只声明不比较等于没有）");
  ok(/findManifestDir/.test(mkt), "容忍多套一层目录的 zip");
  const plug = code(read("app/src/main/java/me/bmax/apatch/ui/screen/DshPluginScreen.kt"));
  ok(/showExtLinkInstall/.test(plug) && /pickExtZip/.test(plug),
    "扩展页有链接与文件两个入口");
}
  // 锁走 DshKernelState（`K.updateCheckMutex`）。
  ok(/suspend fun checkRuntimeUpdate\(\): RuntimeCheckResult = (?:K\.)?updateCheckMutex\.withLock/.test(runtime),
    "checkRuntimeUpdate 真的被 withLock 包住（只声明不用等于没锁）");
}
{
  // ── 桌面名称四选一 ──
  //
  // android:label 是**安装时**从资源解析的，运行期没有 API 能改，
  // 所以换桌面名字只能靠切换启用哪个 activity-alias。
  // 每加一个名字要同时加：两个别名（主/备用图标）+ 一个字符串资源。
  const icon = code(read("app/src/main/java/me/bmax/apatch/util/LauncherIconUtils.kt"));
  ok(/val NAMES = listOf\("DSH-Folk", "DSH", "DeepDroid", "DSHD"\)/.test(icon),
    "桌面名称四个可选值在唯一来源里（UI 不再抄一遍）");
  for (const n of ["DeepDroid", "DSHD"]) {
    ok(icon.includes('"' + n + '"'), `桌面名称含 ${n}`);
  }
  const manifest = read("app/src/main/AndroidManifest.xml");
  for (const a of ["MainActivityAliasDeepDroid","MainActivityAliasAltDeepDroid","MainActivityAliasDshd","MainActivityAliasAltDshd"]) {
    ok(manifest.includes(a), `manifest 有别名 ${a}`);
  }
  ok(/app_name_deepdroid/.test(manifest) && /app_name_dshd/.test(manifest),
    "两个新名字各有独立 label 资源（复用同一个 label 就等于两个名字一样）");
}
{
  // ── 扩展商店是整页，不是半窗 ──
  const market = code(read("app/src/main/java/me/bmax/apatch/ui/component/ExtensionMarketSheet.kt"));
  ok(/Scaffold\(/.test(market) && /TopAppBar\(/.test(market),
    "扩展商店用 Scaffold + TopAppBar（与插件商店同一套容器）");
  // 列表那个 520dp 上限是为「弹层高度是 wrap」加的；整页之后它变成没必要的限制。
  ok(!/heightIn\(max = 520\.dp\)/.test(market),
    "列表不再有 520dp 上限（整页高度有界，不需要它）");
}
{
  // 强制过渡版本必须有**实际约束**，不能只是数据类上有个字段。
  // 只加字段不加约束 = 用户照旧可以关掉对话框、跳过迁移，那个字段就成了装饰。
  const ud = code(read("app/src/main/java/me/bmax/apatch/ui/component/UpdateDialog.kt"));
  ok(/val mandatoryBridge = status\?\.isMandatoryBridge == true/.test(ud),
    "对话框读 isMandatoryBridge");
  ok(/onDismissRequest = \{ if \(!busy && !mandatoryBridge\) onDismiss\(\) \}/.test(ud),
    "强制过渡时挡掉返回键/外部点击（否则点一下外面就跳过了）");
  ok(/dismissOnBackPress = !busy && !mandatoryBridge/.test(ud),
    "强制过渡时挡掉返回键");
  ok(/if \(!mandatoryBridge\) \{[\s\S]{0,160}TextButton\(onClick = onDismiss/.test(ud),
    "强制过渡时「关闭」整个不渲染（灰着的按钮会被反复点，不如不给）");
  // 光出现字符串不够：把它绑到别的 Text 上、或者渲染成空串，字符串依然"出现过"。
  // 必须钉住「bridgeNotice 被渲染进 Text 的 text 参数」这条绑定。
  ok(/val bridgeNotice = if \(mandatoryBridge\) \{[\s\S]{0,120}update_mandatory_bridge_notice/.test(ud),
    "对话框读强制过渡说明文案");
  ok(/text = bridgeNotice,/.test(ud),
    "说明文案真的被渲染出来（只说「必须更新」像强迫，说清数据迁移才是知情选择）");
}
ok(/val isPrerelease: Boolean = false/.test(checker),
  "Status 带 isPrerelease，界面才能把测试版标出来");
{
  const ud = code(read("app/src/main/java/me/bmax/apatch/ui/component/UpdateDialog.kt"));
  ok(/isPrerelease/.test(ud), "更新对话框读 isPrerelease");
  ok(/update_beta_badge/.test(ud) && /update_beta_warning/.test(ud),
    "对话框上有测试版标记与提醒（两种提示长得一样，风险却不同）");
}
// 列表条数：每个 beta 一个 tag，10 条很快全是测试版
{
  const per = checker.match(/releases\?per_page=(\d+)/);
  ok(per !== null && Number(per[1]) >= 30,
    `列表取够条数（当前 ${per ? per[1] : "?"}，每个 beta 占一条，太少会让正式版通道找不到正式版）`);
}

// ── 6. beta 工作流 ──
console.log("\n── beta 工作流 ──");
ok(/--prerelease/.test(betaYml), "发的是 prerelease（这是 App 侧过滤的依据）");
ok(/assembleRelease/.test(betaYml),
  "用 release 变体：debug 变体是独立包名 + debug 签名，装上去不是升级而是多一个图标");
ok(/-PdshVersionName=/.test(betaYml) && /-PdshVersionCode=/.test(betaYml),
  "把版本传给 Gradle");
// 版本名带 -<channel>.N：compareVersions 靠它排先后，且正式版 > 预发布版。
// 通道由 workflow 输入决定（默认 alpha），所以这里认 ${CHANNEL} 变量。
// 序号按核心版本计数（发正式版归零），见 beta.yml 的 Derive version。
ok(/\$\{CHANNEL\}\.\$\{SEQ\}/.test(betaYml),
  "版本名带 -<channel>.N（compareAppVersions 靠序号排先后，且正式版 > 预发布版）");
ok(/KEYSTORE_BASE64/.test(betaYml) && /::error::missing release signing secrets/.test(betaYml),
  "缺签名材料就硬失败（用 debug key 签出的包用户装不上，报错只说「应用未安装」）");
ok(/CN=Android Debug/.test(betaYml), "签名自检：debug key 必须被拦住");
{
  const out = betaYml.match(/OUT="([^"]*)"/);
  ok(out !== null && out[1].includes("${abi}"),
    "产物名带 ABI（pickApkAsset 按文件名挑架构，没有 ABI 会被当成旧的单包 release）" +
      (out ? ` → ${out[1]}` : ""));
}
ok(/sha256sum/.test(betaYml),
  "带 .sha256（canInstallInApp 要求校验值，没有它只能退回浏览器）");
// tag 必须过得了 App 侧的正则
{
  const re = /^[vV]?\d+(\.\d+)+([-+].*)?$/;
  const sample = "v1.8.1-beta.7";
  ok(/tag=v\$NAME/.test(betaYml) && re.test(sample),
    `tag 形如 ${sample}，能过 UpdateChecker 的 VERSION_TAG（滚动 tag 会被直接忽略）`);
}

// ── 7. 工作流注入：自由文本输入只能走 env ──
//
// 这一条是**真炸过**的：`notes` 直接写进 run: 之后，GitHub 在跑脚本之前就把它展开进脚本
// 文本，于是说明里的反引号被当命令替换执行（日志里能看到 "git+ssh://…: No such file or
// directory" 与 "dsh: command not found"），展开后的 body 还超过 GitHub 的 125000 字符
// 上限，`gh release create` 回 422 —— 包已经构建、签名、验完，却发不出去。
//
// 规则：`workflow_dispatch` 里 type 缺省（GitHub 默认就是 string）或 `type: string` 的输入
// 是**自由文本**，只允许出现在 env: 映射里，绝不允许出现在 run: 正文里。
function workflowInputs(src) {
  const lines = src.split("\n");
  const at = lines.findIndex((l) => /^\s*workflow_dispatch:\s*$/.test(l));
  if (at < 0) return [];
  const inAt = lines.findIndex((l, i) => i > at && /^\s*inputs:\s*$/.test(l));
  if (inAt < 0) return [];
  const base = lines[inAt].match(/^(\s*)/)[1].length;
  const out = [];
  for (let i = inAt + 1; i < lines.length; i++) {
    const l = lines[i];
    if (l.trim() === "") continue;
    const ind = l.match(/^(\s*)/)[1].length;
    if (ind <= base) break;
    const m = l.match(/^\s*([A-Za-z_][\w-]*):\s*$/);
    if (!m || ind !== base + 2) continue;
    let block = "";
    for (let j = i + 1; j < lines.length; j++) {
      const l2 = lines[j];
      if (l2.trim() === "") continue;
      if (l2.match(/^(\s*)/)[1].length <= ind) break;
      block += l2 + "\n";
    }
    const ty = block.match(/^\s*type:\s*(\w+)\s*$/m);
    out.push({ name: m[1], type: ty ? ty[1] : "string" });
  }
  return out;
}

/** `run:` 块的正文行号（`run: |` 缩进之下、缩进回到同级之前的那些行）。 */
function runBodyLines(src) {
  const lines = src.split("\n");
  const inBody = new Set();
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^(\s*)run:\s*[|>]-?\s*$/);
    if (!m) continue;
    const indent = m[1].length;
    for (let j = i + 1; j < lines.length; j++) {
      const l = lines[j];
      if (l.trim() === "") continue;
      if (l.match(/^(\s*)/)[1].length <= indent) break;
      inBody.add(j);
    }
  }
  return inBody;
}

console.log("\n── 工作流注入（自由文本输入必须走 env） ──");
{
  const WF = [".github/workflows/beta.yml", ".github/workflows/runtime.yml", ".github/workflows/build.yml"];
  /** 当前哪些输入是自由文本：写出来是为了「新增一个 string 输入」时逼人回来审这条规则。 */
  const EXPECT = {
    ".github/workflows/beta.yml": ["notes", "target_version"],
    ".github/workflows/runtime.yml": ["dsh_version", "min_app_version", "node_version", "release_tag"],
    ".github/workflows/build.yml": [],
  };
  for (const wf of WF) {
    const raw = read(wf);
    const free = workflowInputs(raw).filter((i) => i.type === "string").map((i) => i.name).sort();
    ok(free.join(",") === EXPECT[wf].join(","),
      `${wf} 的自由文本输入清单没变（${free.join(", ") || "无"}）—— 变了就得重看下面两条`);
    const lines = raw.split("\n");
    const inBody = runBodyLines(raw);
    const inRun = [...inBody].map((i) => lines[i]).join("\n");
    for (const n of EXPECT[wf]) {
      ok(!new RegExp("\\$\\{\\{\\s*inputs\\." + n + "\\s*\\}\\}").test(inRun),
        `${wf}: inputs.${n} 不出现在 run: 正文里（展开进脚本 = 允许注入命令）`);
      // 交给 YAML 层（env:/with: 的映射值）就行：那里是原样传递，不过 shell。
      // 不要求"值恰好是 ${{ inputs.x }}"——runtime.yml 的 release_tag 是带默认值的表达式。
      const handed = lines.some((l, i) =>
        !inBody.has(i) &&
        new RegExp("^\\s*[A-Za-z_][\\w.-]*:.*\\$\\{\\{\\s*inputs\\." + n + "\\b").test(l));
      ok(handed, `${wf}: inputs.${n} 通过 YAML 映射（env:/with:）传进去，而不是拼进脚本`);
    }
  }
}

// ── 8. README ──
//
// 这三条是最容易「文档说 A、代码做 B」的地方：测试版用哪个变体、artifact 为什么不能用、
// 更新说明为什么是本地资源。读者按 README 去改代码时，错的文档比没有文档更贵。
console.log("\n── README ──");
{
  const rd = read("docs/dev-notes.md");
  // 开关已从「接收测试版更新」改名为「更新通道」（三态之后布尔表达不了）。
  // 断言必须跟着改，否则门禁会把旧名字当成规范 —— 谁按新结构改文档谁红。
  ok(/更新通道/.test(rd), "dev-notes 写了那个开关的位置（已改名「更新通道」）");
  ok(/Alpha/.test(rd) && /Beta/.test(rd) && /正式版/.test(rd),
    "dev-notes 写清了三档通道（改名后不能只剩「测试版」一个概念）");
  ok(/release 变体/.test(rd), "README 说明测试版为什么不用 debug 包");
  ok(/401/.test(rd), "README 记下 artifact 下载要认证这个事实（否则下一个人会再试一次）");
  ok(/PagedInfoDialog/.test(rd), "README 说明两个对话框共用一个壳");
  ok(/changelog_items/.test(rd), "README 指出更新说明的内容在哪");
}

console.log(fail === 0 ? "\n全部通过" : `\n${fail} 项失败`);
process.exit(fail === 0 ? 0 : 1);
