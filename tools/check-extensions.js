#!/usr/bin/env node
// 门禁：扩展系统（App Extensions）的**安全铁律**与失败隔离不许被改坏。
//
// 这套东西跑在 App 进程内、与宿主同权（root 通道 / 无障碍 / 全盘文件），所以它的价值不在
// 「功能多」，而在「出问题时用户还有路可走」。三条不变量：
//   1. 启动第一件事检测熔断标志文件；命中就一个扩展都不加载；
//   2. 崩溃页永远不接扩展 UI，且带「禁用全部扩展并重启」；
//   3. 任何一个扩展抛异常/加载失败，只能影响它自己（宿主与其它扩展照常）。
// 另外还有一条技术硬约束：Android 14+ 动态加载的 dex 必须只读，否则 SecurityException。
const fs = require('fs');
const path = require('path');

const errors = [];
const root = path.resolve(__dirname, '..');
const read = (p) => require("./_paths.js").read(p);
const exists = (p) => require("./_paths.js").exists(p);
// 跨模块查找（迁移期间文件可能已搬进 :core-kernel）
const findable = (p) => require("./_paths.js").findable(p);
const must = (c, m) => { if (!c) errors.push(m); };

// 扩展系统分成两个模块，门禁也得分开看：
//   :ext-api —— 契约（第三方扩展编译时依赖它，所以这里不许出现任何宿主类型）
//   :app     —— 宿主实现（熔断、加载、编排、36 项门面）
// 扩展系统的源码逻辑前缀。
//
// 不再写死 `app/src/main/java/...`：Batch C 起 ExtensionEngine / ExtensionLoader /
// ExtensionRegistry / ExtensionSafetyGuard 迁进 :core-kernel，写死的话这些断言会
// 直接读不到文件。传逻辑名给 read()/exists()，由 tools/_paths.js 跨模块解析 ——
// 迁移期间两处位置都认。
const EXT = 'dsh/ext/';
const API = 'ext-api/src/main/java/me/bmax/apatch/dsh/ext/';

const apiFiles = [
  'ExtensionApi.kt', 'ExtensionManifest.kt', 'ExtensionFs.kt',
  'ExtensionServiceRegistry.kt', 'ExtensionHookRegistry.kt', 'ExtensionUiRegistry.kt',
];
const appFiles = [
  'ExtensionSafetyGuard.kt', 'ExtensionRegistry.kt', 'ExtensionLoader.kt',
  'ExtensionEngine.kt', 'DshPlatform.kt',
  'AppExtensionFs.kt', 'ExtensionHostImpl.kt',
];
for (const f of apiFiles) must(exists(API + f), '缺契约文件 ext-api/' + f);
for (const f of appFiles) must(findable(EXT + f), '缺宿主实现 ' + f);

const guard = read(EXT + 'ExtensionSafetyGuard.kt');
const loader = read(EXT + 'ExtensionLoader.kt');
const registry = read(EXT + 'ExtensionRegistry.kt');
const manifest = read(API + 'ExtensionManifest.kt');
const engine = read(EXT + 'ExtensionEngine.kt');
const extFs = read(API + 'ExtensionFs.kt');
const svc = read(API + 'ExtensionServiceRegistry.kt');
const hook = read(API + 'ExtensionHookRegistry.kt');
const uiReg = read(API + 'ExtensionUiRegistry.kt');
const apiSrc = apiFiles.map((f) => read(API + f)).join(String.fromCharCode(10));

// ---- 0. 契约模块不得依赖宿主（这条是模块拆分存在的全部意义）----
must(!/import me\.bmax\.apatch\.(dsh|ui|util)\./.test(apiSrc),
  'ext-api 里出现了宿主类型 import —— 契约模块一旦依赖 :app，第三方就再也编译不了');
// 只判**代码**：注释里提到 DshRuntime 是合理的（要解释「为什么不能依赖它」），
// 把注释一起判会逼着后来人删掉最有价值的那段说明。
const apiCode = apiSrc
  .split(String.fromCharCode(10))
  .map((l) => l.replace(/\/\/.*$/, ''))
  .join(String.fromCharCode(10))
  .replace(/\/\*[\s\S]*?\*\//g, '');
must(!/DshRuntime|DshEnv|DshState|DshPlatform/.test(apiCode),
  'ext-api 的**代码**里出现了宿主实现类型（DshRuntime/DshEnv/DshState/DshPlatform）');
must(!/androidx\.compose\.material3\.ColorScheme/.test(apiSrc.replace(/ColorScheme/g, 'ColorScheme')) || true,
  '占位断言（ColorScheme 是 Compose 公共类型，允许出现）');

// ---- 1. 熔断文件：三条路径都得认，且三种文件名写法都认 ----
must(/val KILL_SWITCH_NAMES = listOf\([\s\S]{0,200}"\.disable_extensions"[\s\S]{0,200}"disable_extensions"[\s\S]{0,200}"disable_extensions\.txt"/.test(guard),
  'KILL_SWITCH_NAMES 要同时认带点 / 不带点 / .txt 三种写法（不同文件管理器的习惯不一样）');
must(/fun killSwitchCandidates\(ctx: Context\): List<File>/.test(guard), '缺 killSwitchCandidates()');
must(/ExtensionLayout\.root\(ctx\)/.test(guard), '没有把私有扩展目录纳入熔断路径');
must(/getExternalFilesDir\(null\)/.test(guard),
  '没有纳入 getExternalFilesDir()：那是「插电脑用 MTP 就能建」的唯一免权限路径');
must(/DIRECTORY_DOWNLOADS/.test(guard), '没有纳入公共下载目录 /sdcard/Download/DSH-Folk');
must(/fun checkAndEnforce\(ctx: Context\)/.test(guard), '缺 checkAndEnforce()：启动时没有任何地方检测熔断');
must(/fun isSafeMode\(ctx: Context\?\): Boolean/.test(guard), '缺 isSafeMode()');
must(/fun latch\(ctx: Context, why: String, writeMarker: Boolean\)/.test(guard), '缺 latch()');
must(/fun isProtectedPath\(ctx: Context, target: File\): Boolean/.test(guard), '缺 isProtectedPath()');
// canonicalPath：否则扩展用符号链接指到标志文件再删，逃生通道就失效
must(/canonicalPath/.test(guard), 'isProtectedPath 必须用 canonicalPath 判定（只比字符串会被符号链接绕过）');

// ---- 2. 崩溃阈值与自动熔断 ----
must(/const val CRASH_THRESHOLD = \d+/.test(guard), '缺崩溃阈值常量');
must(/fun noteCrash\(ctx: Context, detail: String\): Boolean/.test(guard), '缺 noteCrash()：连续崩溃无法自动熔断');
must(/writeMarker = true/.test(guard), '自动熔断没有落标志文件：下次冷启动又会崩在同一个扩展上');
must(/fun stackTouchesExtension\(trace: String\?\): Boolean/.test(guard), '缺 stackTouchesExtension()：分不清是谁崩的');

// ---- 3. Android 14+ 只读 dex（硬约束）+ 内存加载兜底 ----
// 真机(beta.239, Android 16/SDK36)踩到：dex 完好、类定义也在 class_defs 里，但
// DexClassLoader 报 Didn't find class —— ART 把 dex 元素静默丢掉了（只读/odex/校验失败都这样，
// 都不抛异常）。所以只读要用 Os.chmod 这种真能生效的方式，并且必须有内存加载兜底。
must(/Os\.chmod\(dex\.absolutePath, 0x124/.test(loader),
  'ExtensionLoader 没有用 Os.chmod 0400 设只读：File.setReadOnly 可能返回 false 且被静默忽略');
must(/InMemoryDexClassLoader/.test(loader),
  '缺内存加载兜底：文件路径一旦被 ART 丢掉 dex 元素，扩展就永远加载不上且看不出原因');
const readOnlyIdx = loader.indexOf('Os.chmod(dex.absolutePath');
const dexLoaderIdx = loader.indexOf('DexClassLoader(');
must(readOnlyIdx > 0 && dexLoaderIdx > readOnlyIdx,
  '只读必须在 DexClassLoader 之前设置（顺序反了等于没设）');
must(/ctx\.classLoader,/.test(loader),
  'DexClassLoader 的父加载器必须是宿主 classLoader，否则扩展拿不到宿主任何类');
must(/ctx\.classLoader,/.test(loader),
  'DexClassLoader 的父加载器必须是宿主 classLoader，否则扩展拿不到宿主任何类');

// ---- 4. 失败隔离：安全模式短路 + 异常不冒泡 ----
must(/if \(ExtensionGate\.isLatched\(\)\) return fallback\(\)/.test(svc),
  'SPI 在熔断态下没有退回默认实现');
must(/if \(ExtensionGate\.isLatched\(\)\) return true/.test(hook),
  'Hook 的 around 在熔断态下没有直接放行');
// 注册中心在 :ext-api 里，读的是可注入的 ExtensionGate（它不认识 Context / 熔断文件）。
// 宿主侧 ExtensionSafetyGuard 必须把熔断位同步过去，否则「熔断后扩展仍在生效」。
must(/if \(ExtensionGate\.isLatched\(\)\) emptyList\(\)/.test(uiReg),
  'UI 槽位在熔断态下没有返回空（熔断后界面必须回到原生样子）');
must(/private fun setLatched\(value: Boolean\) \{[\s\S]{0,200}ExtensionGate\.latched = value/.test(guard),
  '宿主熔断位没有同步到 ExtensionGate：注册中心读不到熔断，扩展会继续生效');
must(/fun installLogSink\(\)/.test(guard), '缺 installLogSink()：扩展日志进不了 dsh.log，报障时无从定位');
must(/runCatching \{ handler\.run\(payload\) \}/.test(hook), 'Hook 调用没有 runCatching：扩展异常会冒泡进宿主主流程');
must(/runCatching \{[\s\S]{0,80}?ext\.onLoad\(host\)/.test(loader),
  'onLoad 没有 runCatching：一个坏扩展能让宿主起不来');
must(/unregisterAll\(mf\.id\)/.test(loader),
  'onLoad 失败后没有撤掉已注册的 SPI/Hook/UI —— 会留下指向半初始化扩展的悬空回调');

// ---- 5. 编排器 ----
must(/fun bootstrap\(ctx: Context\)/.test(engine), '缺 ExtensionEngine.bootstrap()');
must(/if \(ExtensionSafetyGuard\.isSafeMode\(ctx\)\)/.test(engine), 'bootstrap 没有先看安全模式');
// 判据放宽到 600 字符：安全模式分支里会先打一行「原因=…」的诊断日志再 return，
// 卡 200 字符会把这条正经活判成违规（写死距离的断言本身也很脆）。
must(/ExtensionSafetyGuard\.isSafeMode\(ctx\)\) \{[\s\S]{0,600}?return/.test(engine),
  '安全模式下 bootstrap 没有提前返回（熔断了还在加载扩展）');
must(/fun unloadAll\(ctx: Context\)/.test(engine) && /fun disableAllAndLatch\(ctx: Context, why: String\)/.test(engine),
  '缺 unloadAll / disableAllAndLatch（崩溃页与安全模式都要用）');
for (const reg of ['ExtensionServiceRegistry.unregisterAll', 'ExtensionHookRegistry.unregisterAll',
  'ExtensionUiRegistry.unregisterAll', 'ExtensionBootPipeline.unregisterAll']) {
  must(new RegExp(reg.replace(/\./g, '\\.')).test(engine),
    'unload 没有撤掉 ' + reg + '（会留下悬空回调）');
}

// ---- 6. 目录 API 不得碰熔断标志 ----
// 拦截逻辑在宿主实现里（接口自己没法知道熔断标志长什么样）
const appFsImpl = read(EXT + 'AppExtensionFs.kt');
must(/ExtensionSafetyGuard\.isProtectedPath\(ctx, file\)/.test(appFsImpl),
  'AppExtensionFs 没有拦截保护路径：扩展可以把「禁用全部扩展」的标志删掉');
must(/override fun write\(|override fun delete\(|override fun symlink\(/.test(appFsImpl),
  'AppExtensionFs 没有实现写/删/链接（ExtensionFs 契约不完整）');
must(/fun symlink\(/.test(extFs), 'ExtensionFs 缺 symlink（容器相关扩展的基本需求）');

// ---- 7. manifest 校验 ----
must(/fun isValidId\(id: String\): Boolean/.test(read(API + 'ExtensionApi.kt')),
  '缺 isValidId()：id 会被直接拼进目录名，必须防 ../ 穿越');
must(/if \(!ExtensionLayout\.isValidId\(id\)\) return null/.test(manifest),
  'manifest 解析没有校验 id 合法性');


// ---- 8. 宿主接线 ----
const app = read('app/src/main/java/me/bmax/apatch/APatchApp.kt');
must(/ExtensionSafetyGuard\.checkAndEnforce\(this\)/.test(app), 'APatchApp 没有在启动时检测熔断');
const checkIdx = app.indexOf('ExtensionSafetyGuard.checkAndEnforce(this)');
const bootIdx = app.indexOf('ExtensionEngine.bootstrap(this)');
must(checkIdx > 0 && bootIdx > checkIdx, '必须「先检测熔断、再加载扩展」，顺序反了等于没有熔断');
must(/getDefaultUncaughtExceptionHandler\(\)/.test(app), '没有校验/复原根崩溃处理器（扩展可以劫持崩溃流）');
must(/ExtensionSafetyGuard\.noteCrash\(/.test(app), '崩溃时没有计入扩展崩溃（连续崩溃不会自动熔断）');


// ---- 8d. 外壳替换：接管导航外壳，但页面树必须原样还在 ----
const main = read("app/src/main/java/me/bmax/apatch/ui/MainActivity.kt");
must(/ExtensionUiRegistry\.shellOverride\(\)/.test(main), "MainActivity 没有读外壳注册：扩展装了壳也不会生效");
must(/activeShell\.content\(/.test(main), "MainActivity 没有渲染外壳内容");
// 页面树必须被**传给**外壳，而不是被外壳替掉：否则外壳一旦画得不全，插件页/设置页就消失了
must(/navHostSlot\(inner\.then\(baseContentModifier\)\)/.test(main),
  "外壳分支没有把原有导航树传进去：用户会被关在只剩外壳的空房间里");
// 至少三处用同一个 slot（外壳 / 侧栏 / 底栏）—— 复制粘贴出第二份就会三处各长各的
{
  const uses = (main.match(/navHostSlot\(/g) || []).length;
  must(uses >= 3, "导航树没有被三处共用（外壳/侧栏/底栏），只找到 " + uses + " 处");
}
// else 分支必须保持原路径：外壳为空时行为不能变
{
  const shellIdx = main.indexOf("if (activeShell != null)");
  const railIdx = main.indexOf("else if (useNavigationRail)");
  must(shellIdx > 0 && railIdx > shellIdx,
    "外壳判空分支被改过：没有扩展时必须原样走侧栏/底栏那条老路径");
}
const shellHostImpl = read("app/src/main/java/me/bmax/apatch/ui/ExtensionShellHost.kt");
must(/override fun resetToDefaultShell\(\)/.test(shellHostImpl),
  "ShellHost 没有实现 resetToDefaultShell：外壳画坏时用户没有回去的路");
must(/override fun currentTabStates\(\)/.test(shellHostImpl),
  "ShellHost 没有实现可观察的当前页：返回键切页时外壳高亮会不同步");
// 导航参数必须与底栏同一套，否则同一台机器两种返回栈行为
must(/popUpTo\(navController\.graph\.startDestinationId\) \{ saveState = true \}/.test(shellHostImpl) &&
  /launchSingleTop = true/.test(shellHostImpl) && /restoreState = true/.test(shellHostImpl),
  "ShellHost 的 navigate 没有用与底栏一致的 popUpTo/launchSingleTop/restoreState");


// ---- 8e. minAppVersion：扩展依赖新宿主 API 时，旧宿主必须拒绝加载 ----
must(/minAppVersion/.test(loader) && /DshRuntime\.appSatisfies\(required\)/.test(loader),
  "ExtensionLoader 没有校验 minAppVersion：旧宿主加载新扩展会在运行期 NoSuchMethodError");



// ---- 8g. release 混淆必须保留扩展 API（这是 beta.236~240 全都加载不上的根因）----
// 扩展是独立编译的 DEX，引用写死了 Lme/bmax/apatch/dsh/ext/... 这类名字。release 开了
// isMinifyEnabled + -repackageclasses，R8 一改名，ART 链接扩展类时就找不到父类型，
// 表现成「Didn't find class <入口类>」，而扩展包本身完全正常。'
{
  const pg = read("app/proguard-rules.pro");
  const gradleSrc = read("app/build.gradle.kts");
  must(/-keep class me\.bmax\.apatch\.dsh\.ext\.\*\* \{ \*; \}/.test(pg),
    "proguard 没有 -keep 扩展 API 整包：release 混淆会把 DshExtension 改名，扩展必然加载失败");
  must(/-keep interface me\.bmax\.apatch\.dsh\.ext\.\*\*/.test(pg),
    "proguard 没有 keep 扩展 API 的接口（DshExtensionHost / ExtensionFs / ShellHost）");
  // release 确实开了混淆 —— 如果哪天关了，上面两条 keep 就只是冗余，这条断言会提醒复查
  must(/isMinifyEnabled = true/.test(gradleSrc),
    "release 的 isMinifyEnabled 变了：请复查扩展 keep 规则是否仍然必要");
  // 语言运行时同样必须保留原名：真机踩过 —— 类找到了、onLoad 也调用了，但一进去就
  // Failed resolution of: Lkotlin/jvm/internal/Intrinsics;（-repackageclasses 把它挪了包）
  must(/-keep class kotlin\.\*\* \{ \*; \}/.test(pg),
    "proguard 没有整包保留 kotlin-stdlib：扩展一进 onLoad 就会 Intrinsics 解析失败");
  // 必须是 -keep（连成员一起），不能是 -keepnames：Compose 编译器生成代码调的是 internal
  // 成员，只保类名会出 "No static method composableLambdaInstance(...)"（beta.242 踩过）
  must(/-keep class androidx\.compose\.runtime\.\*\* \{ \*; \}/.test(pg) &&
    /-keep class androidx\.compose\.ui\.\*\* \{ \*; \}/.test(pg),
    "proguard 没有整包保留 Compose（含成员）：扩展的 @Composable 会在运行期链接失败");
  must(!/-keepnames class androidx\.compose/.test(pg),
    "Compose 只用了 -keepnames（只保类名不保成员），扩展必炸");
  must(/-keep class kotlinx\.coroutines\.flow\.\*\*/.test(pg),
    "proguard 没有保留 kotlinx.coroutines.flow：扩展收集 StateFlow 会失败");
}

// 这条不是洁癖：beta.238 的 shell 扩展不生效，而 dsh.log 里一条 [ext] 都没有，
// 根因是 bootstrap 排在 DshRuntime.init 之前，appendLog 在没有 Context 时静默丢弃。
const dshRuntimeSrc = read("app/src/main/java/me/bmax/apatch/dsh/DshRuntime.kt");
must(/android\.util\.Log\.i\(\"DSH-Folk\", line\)/.test(dshRuntimeSrc),
  "appendLog 在 Context 未绑定时会静默丢弃：启动期日志（扩展加载）会凭空消失");
{
  const initIdx = app.indexOf("DshRuntime.init(this)");
  const bootIdx2 = app.indexOf("ExtensionEngine.bootstrap(this)");
  must(initIdx > 0 && bootIdx2 > initIdx,
    "ExtensionEngine.bootstrap 必须排在 DshRuntime.init 之后，否则它自己写的日志落不了盘");
}
// 例行诊断必须有（否则「装了没生效」无从查起），但**不许进 dsh.log**：
// 那是用户在「设置 → 日志」里看的启动日志，塞满例行行会把真故障淹没。
must(engine.includes("ExtLog.info(") && engine.includes("已安装 "),
  "bootstrap 没有打印已安装/启用概况（走 ExtLog.info，只进 logcat）");
{
  const sinkBody = guard.slice(guard.indexOf("fun installLogSink"));
  must(!sinkBody.slice(0, sinkBody.indexOf("\n    }")).includes("DshRuntime.appendLog"),
    "扩展日志被写进了 dsh.log：dsh.log 只该有 dsh 运行时自身的启动日志");
}
must(!/DshRuntime\.appendLog\(\s*"[^"]*\[ext/.test(engine + loader),
  "扩展相关日志仍然在写 dsh.log（应统一走 ExtLog，只进 logcat）");
must(loader.includes("自检"), "加载失败时没有自检：无法区分「dex 整体被拒」与「只有这个类找不到」");
must(!engine.includes('[ext] bootstrap 开始'),
  "bootstrap 的例行概况写进了 dsh.log，会淹没真故障");
must(/安全模式，跳过全部第三方扩展；原因=/.test(engine), "安全模式拦截没有打印原因（用户只会看到「没生效」）");
must(/准备加载：dex=/.test(loader), "加载失败时无法从日志判断 dex 是否存在/多大");
must(/没有实现 DshExtension，实际类型=/.test(loader), "接口类型不匹配时会静默返回 null（典型：扩展自带了一份 :ext-api）");
must(/已注册外壳/.test(uiReg), "注册外壳没有日志，「注册没成功」与「注册了没渲染」无法区分");
{
  const mainSrc2 = read("app/src/main/java/me/bmax/apatch/ui/MainActivity.kt");
  must(/外壳决策=/.test(mainSrc2), "MainActivity 没有记录外壳决策，无法定位「注册了但没生效」");
}

const crash = read('app/src/main/java/me/bmax/apatch/ui/CrashHandleActivity.kt');
must(/ExtensionEngine\.disableAllAndLatch\(/.test(crash), '崩溃页没有「禁用全部扩展」的救援入口');
must(!/ExtensionUiRegistry/.test(crash), '崩溃页不许接扩展 UI（会被扩展接管，用户就点不到救援按钮了）');

const screen = read('app/src/main/java/me/bmax/apatch/ui/screen/DshPluginScreen.kt');

// ---- 8a2. 配置面板：扩展自己那块界面，以及「独占面」语义不许再分叉 ----
//
// 这三条都是**变异可变红**的判据（改回旧实现断言必须红），不是钉注释/形参名的假保护：
//  1. configPanel 熔断态返回 null —— 熔断之后界面必须回到宿主原生样子，面板也不例外；
//  2. unregisterAll 清 panels —— 面板的 content 是扩展 dex 里的 lambda，扩展卸载后
//     它还指着已卸载的类加载器，留着就是 NoClassDefFoundError；
//  3. overrideScreen 与 shellOverride 同为 last-wins —— 这一条防的是「以后又分叉」：
//     原来整屏是 first-wins、外壳是 last-wins，同一套 API 两种语义，先加载的悄悄赢、
//     后加载的完全静默，是最难排查的那种失效。
{
  // 断言必须看**代码**，不能看注释：把实现注释掉之后，正则照样能在注释里匹配到
  // 「panels.remove(extensionId)」这类字样，于是门禁保持绿色而保护其实已经没了
  // （变异验证实测到过这一条，M2）。所以这几条判据先剥注释再匹配。
  const codeOnly = (s) =>
    s.split(String.fromCharCode(10)).map((l) => l.replace(/\/\/.*$/, '')).join(String.fromCharCode(10))
      .replace(/\/\*[\s\S]*?\*\//g, '');
  const panelBody = uiReg.slice(uiReg.indexOf("fun configPanel("));
  must(panelBody.length > 0, '缺 ExtensionUiRegistry.configPanel()');
  // 取到函数体结束（表达式体只有一行，取到下一个成员声明前即可）
  const panelDecl = codeOnly(panelBody.slice(0, panelBody.indexOf("\n    }") + 6));
  must(/if \(ExtensionGate\.isLatched\(\)\) null/.test(panelDecl),
    'configPanel 在熔断态下没有返回 null（熔断后界面必须回到原生样子）');
  must(/panels\[extensionId\]/.test(panelDecl),
    'configPanel 没有从 panels 取内容（形同虚设）');

  const unreg = uiReg.slice(uiReg.indexOf("fun unregisterAll("));
  const unregBody = codeOnly(unreg.slice(0, unreg.indexOf("\n    }") + 6));
  must(/panels\.remove\(extensionId\)/.test(unregBody),
    'unregisterAll 没有清掉配置面板：扩展卸载后面板仍持有一个指向已卸载 dex 的闭包');

  // 写入端与读取端必须同一个方向：写端 last-wins + 读端 lastOrNull
  const overrideFn = uiReg.slice(uiReg.indexOf("fun overrideScreen("));
  const overrideBody = codeOnly(overrideFn.slice(0, overrideFn.indexOf("\n    }") + 6));
  must(/\.add\(ScreenOverride\(/.test(overrideBody) &&
    !/firstOrNull|add\(0,/.test(overrideBody),
    'overrideScreen 不再是追加语义（改回 first-wins 会让「后启用的扩展」静默失效）');
  const screenGet = uiReg.slice(uiReg.indexOf("fun screenOverride("));
  const screenGetBody = codeOnly(screenGet.slice(0, screenGet.indexOf("\n    }") + 6));
  must(/overrides\[screenId\]\?\.lastOrNull\(\)/.test(screenGetBody),
    'screenOverride 没有取最后注册的那条：与 overrideScreen 的 last-wins 分叉了');
  const shellGet = uiReg.slice(uiReg.indexOf("fun shellOverride("));
  const shellGetBody = codeOnly(shellGet.slice(0, shellGet.indexOf("\n    }") + 6));
  must(/shells\.lastOrNull\(\)/.test(shellGetBody),
    'shellOverride 没有取最后注册的那条（整屏与外壳的赢家规则必须一致）');
  // 顶掉别人时必须留下「谁顶掉了谁」，只有「被顶掉了」等于没说是谁干的
  must(/顶掉了 \[|顶掉了\[/.test(uiReg),
    '接管冲突没有记录「谁顶掉了谁」：排查时看不出输家是谁');
}

// ---- 8b. 扩展市场：下载来的东西比本机的更不可信，三道防线一道都不能少 ----
const market = read(EXT + "ExtensionMarket.kt");
// 扩展可能依赖只有新宿主才有的 API（例如 ShellHost.resetToDefaultShell）：旧宿主必须拒装/拒载，
// 否则运行期是 NoSuchMethodError，而它出现在扩展的调用栈里，看起来像宿主崩了。
must(/DshRuntime\.appSatisfies\(required\)/.test(market),
  "市场安装没有校验 minAppVersion：用户要等重启后才发现扩展根本没加载");
must(/const val DEFAULT_INDEX_URL = "https:\/\/raw\.githubusercontent\.com\/DSH-Folk\/dsh-folk-plugins\/main\/index\.json"/.test(market),
  "市场目录默认地址必须指向 dsh-folk-plugins 仓库的 index.json");
must(/MessageDigest\.getInstance\("SHA-256"\)/.test(market),
  "市场安装没有校验 sha256：下载下来的 dex 会被直接安装");
must(/canonical/.test(market),
  "解压没有做 zip slip 校验（canonical 前缀判断）：安装包可以往扩展目录外写文件");
// 强制 https：明文 HTTP 下目录与 sha256 可以一起被换掉，校验等于没有
// 用 URL.protocol 判断而不是字符串前缀：前缀判断挡不住 https:/evil（少一个斜杠）这类写法
must(/protocol != "https"/.test(market), "市场地址没有强制 https（应解析成 URL 后判 protocol）");
// 清单 id 必须与条目 id 一致，否则一个条目能覆盖另一个已装扩展的目录
must(/manifest\.id/.test(market) && /!=\s*entry\.id|!=\s*entry\.id/.test(market),
  "安装没有校验「安装包 manifest.id == 市场条目 id」：一个条目可以覆盖另一个扩展");
must(/deleteRecursively/.test(market), "市场安装没有清理临时目录（缓存会一直涨）");

// ---- 8c. 扩展页：与插件页/脚本页对齐后该有的东西 ----
must(/extensionFilter/.test(screen), "扩展页没有自己的过滤词（与插件页共用一个词会串页）");
must(/R\.string\.dsh_ext_empty_filtered/.test(screen),
  "扩展页空态没有区分「一个都没装」与「搜索没命中」：会让搜过东西的人以为扩展丢了");
must(/ExtensionDetailSheet\(/.test(screen), "扩展页没有详情弹层（点卡片没反应）");
must(/dsh_ext_badge_broken/.test(screen) && /dsh_ext_badge_nocode/.test(screen),
  "扩展页缺「清单损坏 / 无代码」徽章");
must(/ExtensionRegistry\.listInstalled\(/.test(screen), '扩展页没有列真实扩展（还是占位文案）');
must(/ExtensionSafetyGuard\.isSafeMode\(/.test(screen), '扩展页没有安全模式横幅');
must(/ExtensionEngine\.unload\(/.test(screen), '关闭扩展时没有卸载（会留下仍在跑的扩展）');
// 打开开关 = 授予完整权限：必须有信任确认，否则一次误触就等于授权
must(/dsh_ext_trust_title/.test(screen) && /dsh_ext_trust_accept/.test(screen),
  '启用扩展前没有「以完整权限运行」的信任确认');
must(/dsh_ext_trust_body/.test(screen), '信任确认没有列出扩展声明的能力/说明');

// ---- 9. 文案两种语言都要有 ----
const zh = read('app/src/main/res/values-zh-rCN/dsh_strings.xml');
const en = read('app/src/main/res/values/dsh_strings.xml');
for (const key of ['dsh_ext_title', 'dsh_ext_empty', 'dsh_ext_safe_mode', 'dsh_ext_crash_disable_all']) {
  must(new RegExp('name="' + key + '"').test(zh), '中文缺字符串 ' + key);
  must(new RegExp('name="' + key + '"').test(en), '英文缺字符串 ' + key);
}

console.log("\n── Batch B：强类型 seam ──");
{
// 8 个 seam 接口必须都在 :ext-api（第三方扩展要能编译期依赖它们）
const SEAMS = ["RuntimeSeam", "ShellSeam", "FsSeam", "DeviceSeam", "UiSeam", "BackupSeam", "EventSeam", "CapabilitySeam"];
for (const name of SEAMS) {
  must(exists(`ext-api/src/main/java/me/bmax/apatch/dsh/ext/seam/${name}.kt`),
    `seam 接口 ${name} 在 :ext-api 里（第三方可编译期依赖）`);
}

// 每个 seam 都要有宿主实现，否则扩展拿到的是空
const HOST_IMPLS = {
  RuntimeSeam: "HostRuntimeSeam", ShellSeam: "HostShellSeam", FsSeam: "HostFsSeam",
  DeviceSeam: "HostDeviceSeam", UiSeam: "HostUiSeam", BackupSeam: "HostBackupSeam",
  EventSeam: "HostEventSeam", CapabilitySeam: "HostCapabilitySeam",
};
for (const [seam, impl] of Object.entries(HOST_IMPLS)) {
  const src = read(`app/src/main/java/me/bmax/apatch/dsh/ext/seam/${impl}.kt`);
  must(new RegExp(`class ${impl}\\b[^{]*:\\s*${seam}`).test(src),
    `${impl} 实现了 ${seam}`);
}

// host 上要能拿到 seam —— 扩展的唯一入口
const api = read("ext-api/src/main/java/me/bmax/apatch/dsh/ext/ExtensionApi.kt");
must(/val seam: ExtensionSeams/.test(api), "host 上挂得到 seam");
must(/interface ExtensionSeams/.test(api), "有 ExtensionSeams 聚合接口");

// 反射门面必须标 @Deprecated —— 它是 R8 地雷，不能让人以为可以放心用
const plat = read("app/src/main/java/me/bmax/apatch/dsh/ext/AppExtensionPlatform.kt");
must(/@Deprecated\(/.test(api.split("val platform: ExtensionPlatform")[0].split("interface DshExtensionHost")[1] || ""),
  "ExtensionPlatform 在接口上标了 @Deprecated（反射在 release 混淆包里不可靠）");

// 高频入口先走 seam
must(/seamRoute\(method, args\)\?\.let \{ return it \}/.test(plat),
  "AppExtensionPlatform.call() 先走 seam 再回退反射");

// 启动管线 5 个阶段全部接通 —— 少一个就是"扩展注册了但永远不触发"
const STAGES = {
  APP_CREATE: "ExtensionEngine.kt",
  SPLASH: "MainActivity.kt",
  AUTH: "MainActivity.kt",
  INITIAL_ROUTE: "MainActivity.kt",
  AUTOSTART: "BootCompletedReceiver.kt",
};
for (const [stage, file] of Object.entries(STAGES)) {
  const rel = file === "ExtensionEngine.kt"
    ? "app/src/main/java/me/bmax/apatch/dsh/ext/" + file
    : file === "BootCompletedReceiver.kt"
      ? "app/src/main/java/me/bmax/apatch/receiver/" + file
      : "app/src/main/java/me/bmax/apatch/ui/" + file;
  // 用完整限定名而不是只匹配 "Stage.XXX"：只测阶段名的话，
  // 把 AUTOSTART 改成 APP_CREATE 这种"接错段"的错仍会绿。
  must(read(rel).includes("ExtensionBootPipeline.Stage." + stage),
    `启动管线 ${stage} 段有真实调用点（${file}）`);
}

// 宿主默认实现要注册进总线 —— 不注册的话扩展的"整块替换"对宿主自己不生效
const engine = read("app/src/main/java/me/bmax/apatch/dsh/ext/ExtensionEngine.kt");
must(engine.includes("SeamRegistry.install("), "bootstrap 里装了宿主 seam 默认实现");
must(engine.indexOf("SeamRegistry.install") < engine.indexOf("ExtensionLoader.boot"),
  "seam 装在扩展加载**之前**（否则扩展 onLoad 时总线还是空的）");

// 绝不为了迁就反射而宽泛 keep
const proguard = read("app/proguard-rules.pro");
must(!/-keep\s+class\s+me\.bmax\.apatch\.dsh\.\*\*\s*\{\s*\*;\s*\}/.test(proguard),
  "没有为迁就反射去 -keep 整个 me.bmax.apatch.dsh.**（那会让 R8 失去意义）");

// 每个 seam 文件都必须是**可编译的 Kotlin 文件**：有 package、需要的 import 齐全。
//
// 为什么单列这条：kt-lint 只查括号配平与词法，查不出"缺 package" ——
// 缺 package 的文件在 kt-lint 眼里完全正常，只在 CI 编译时才炸
// （报 Unresolved reference，且错误指向调用方而不是文件本身，很难定位）。
// 这条坑我踩过一次：8 个 seam 里 7 个缺 package，本地全绿，CI 全红。
for (const name of SEAMS) {
  const src = read(`ext-api/src/main/java/me/bmax/apatch/dsh/ext/seam/${name}.kt`);
  must(src.startsWith("package me.bmax.apatch.dsh.ext.seam"),
    `${name}.kt 有 package 声明（缺了只在编译时炸，kt-lint 查不出来）`);
  // 用到 File 就必须 import（Android 模块没有默认 import java.io.*）
  const body = src.split("\n").filter((l) => !l.startsWith("import ") && !l.startsWith("package")).join("\n");
  if (/\bFile\b/.test(body)) {
    must(src.includes("import java.io.File"), `${name}.kt 用了 File 就要 import java.io.File`);
  }
}

// CI 的触发路径必须覆盖**所有** Gradle 模块。
//
// 漏掉的表现极其隐蔽：推上去一条 run 都没有，改动静默不被编译，而 GitHub 上
// 看起来一切正常（没有红色）。Batch C 把文件迁进 :core-kernel 时就是这么漏的 ——
// 迁移提交 push 完，CI 列表里连一条 run 都没多。
{
  const settings = read("settings.gradle.kts");
  const modules = [...settings.matchAll(/^include\(":(.+?)"\)/gm)].map((m) => m[1]);
  must(modules.length > 0, "settings.gradle.kts 里能解析出模块清单");
  const wf = read(".github/workflows/build.yml");
  const pathsLine = (wf.split("\n").find((l) => l.includes("paths: [") && l.includes("app/**")) || "");
  must(pathsLine.length > 0, "build.yml 有 paths 过滤行");
  for (const m of modules) {
    // :app 对应 app/**；其余模块名就是目录名
    must(pathsLine.includes("'" + m + "/**'"),
      `build.yml 的触发路径覆盖 :${m}（漏了的话改它不会触发 CI）`);
  }
}

// 测试必须真的会被执行 —— 否则写再多测试也只是"编译不过的注释"。
//
// 真实教训：`ExtensionBootPipelineTest` 放在 :ext-api，而被测类还在 :app，
// 于是 `./gradlew :ext-api:testDebugUnitTest` 一跑就 Unresolved reference。
// 但 CI 只跑 `assembleDebug`/`assembleRelease` —— 它们**不编译 src/test** ——
// 所以这个错一路绿灯到了发布。
for (const wf of [".github/workflows/build.yml", ".github/workflows/beta.yml"]) {
  const y = read(wf);
  must(/gradlew test\b/.test(y), `${wf} 会跑单元测试（只构建不测 = 测试形同虚设）`);
  must(!/gradlew :\w[\w-]*:testDebugUnitTest/.test(y),
    `${wf} 用聚合的 \`test\` 而不是写死模块名（新加模块不会被静默跳过）`);
}

// 被测类必须在测试所在的模块里 —— 这是那个 bug 的直接判据。
must(exists("ext-api/src/main/java/me/bmax/apatch/dsh/ext/ExtensionBootPipeline.kt"),
  "ExtensionBootPipeline 在 :ext-api（外部扩展要能注册启动阶段）");
must(!exists("app/src/main/java/me/bmax/apatch/dsh/ext/ExtensionBootPipeline.kt"),
  "app 里不再有 ExtensionBootPipeline 副本（两份会各走各的）");

// 管线不能依赖宿主内部守卫：:ext-api 看不到 ExtensionSafetyGuard
{
  const bp = read("ext-api/src/main/java/me/bmax/apatch/dsh/ext/ExtensionBootPipeline.kt");
  must(!/ExtensionSafetyGuard/.test(bp),
    "ExtensionBootPipeline 用 ExtensionGate 而不是宿主内部的 ExtensionSafetyGuard");
  must(/ExtensionGate\.isLatched\(\)/.test(bp), "熔断判断走 ExtensionGate");
  // prefs 文件名不该出现在公共契约里
  must(!/getSharedPreferences/.test(bp),
    "ExtensionBootPipeline 不碰 prefs（落盘实现由宿主注入）");
}

// SeamRegistry 的熔断安全性：fallback 绝不能抛
{
  const sr = read("app/src/main/java/me/bmax/apatch/dsh/ext/seam/SeamRegistry.kt");
  // 只看代码行：注释里提到旧实现是正常的（那是解释为什么改）
  const srCode = sr.split("\n").filter((l) => !l.trim().startsWith("*") && !l.trim().startsWith("//")).join("\n");
  must(!/\berror\(/.test(srCode),
    "SeamRegistry 的 fallback 不抛异常（熔断时 resolve 直接走 fallback，抛了就崩）");
  must(!/ExtensionServiceRegistry\.register\(/.test(sr),
    "SeamRegistry 不把宿主实现注册进扩展覆写表（clearAll 会连它一起清掉）");
  must(/ExtensionServiceRegistry\.resolve\(/.test(sr), "取值仍走覆写表（扩展可整块替换）");
}

// 单测：Batch B 的验收要求
must(exists("ext-api/src/test/java/me/bmax/apatch/dsh/ext/ExtensionServiceRegistryTest.kt"),
  "有 ExtensionServiceRegistry 的单元测试");
must(exists("ext-api/src/test/java/me/bmax/apatch/dsh/ext/ExtensionBootPipelineTest.kt"),
  "有 ExtensionBootPipeline 的单元测试");
const regTest = read("ext-api/src/test/java/me/bmax/apatch/dsh/ext/ExtensionServiceRegistryTest.kt");
must(regTest.includes("ExtensionGate.latched = true"), "注册中心测试覆盖了熔断短路");
must(regTest.includes("unregisterAll"), "注册中心测试覆盖了卸载清理");
const pipeTest = read("ext-api/src/test/java/me/bmax/apatch/dsh/ext/ExtensionBootPipelineTest.kt");
must(/throw IllegalStateException/.test(pipeTest), "启动管线测试覆盖了异常隔离");
must(/priority = 30/.test(pipeTest), "启动管线测试覆盖了优先级顺序");
}

if (errors.length) {


console.error('check-extensions FAILED:');
  for (const e of errors) console.error('  ✗ ' + e);
  process.exit(1);
}
console.log('check-extensions: 通过');
