package me.bmax.apatch.dsh

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.bmax.apatch.dsh.ext.ExtensionHookRegistry
import me.bmax.apatch.util.LocaleCtx
import me.bmax.apatch.util.UpdateChecker
import me.bmax.apatch.util.appString
import org.json.JSONArray
import org.json.JSONObject
import me.bmax.apatch.res.R as CoreR
import me.bmax.apatch.dsh.backup.DshBackupArchive

/** 运行时阶段。 */
object DshRuntime : DshRuntimeHost {
    internal const val TAG = "DSH-Folk"



    /**
     * 从 dsh 打印的 `dsh web: http://…?token=…` 里提取认证 token。
     *
     * 字符集必须是 **base64url**：上游 dsh-client-connection 的 `processLaunchToken` 用的是
     * `encodeBase64Url(randomBytes(32))`，43 个字符里 `-` 与 `_` 都可能出现。这条原来写的是
     * `\?token=([A-Za-z0-9+/=-]+)`（少了 `_`），用 20000 个真 token 量过：**46.8% 会被从
     * `_` 处截断**（URL 带着一个错的 token 去撞认证墙），另有 **1.6% 因首字符就是 `_` 而整条
     * 匹配不上**，URL 里连 token 参数都没有 —— 正是「外部浏览器打开时链接没有 token」。
     *
     * 收尾不靠字符类：局域网开着时 dsh 会在同一行再打一个 ` (LAN: …)`，所以按分隔符截断。
     */
    private val DSH_WEB_TOKEN_RE = Regex("[?&]token=([A-Za-z0-9_%+/.=~-]+)")

    /** ELF `e_machine`：183 = AArch64，62 = x86-64（见 [rootfsArchMismatch]）。 */



    /** web profile 在 rootfs 内的相对路径（guest 侧是 /root/.dsh/profiles/web）。 */
    internal const val PROFILE_GUEST_REL = DshRuntimeEnv.PROFILE_GUEST_REL

    /** 权限桥疑难解答在容器里的文件名（与 CLI 里那个路径必须一致）。 */
    // ── 容器侧脚本与文档 ──────────────────────────────────────────────────────
    // 内容在同包 [DshRuntimeScripts]（约一千二百行 raw string）。这里只留引用：调用点零改动，
    // 要改脚本、DSL 说明或样例，去那个文件。
    internal val FS_BRIDGE_CLI_SCRIPT: String get() = DshRuntimeScripts.FS_BRIDGE_CLI_SCRIPT
    internal val SAF_BRIDGE_CLI_SCRIPT: String get() = DshRuntimeScripts.SAF_BRIDGE_CLI_SCRIPT
    internal val NATIVE_CLI_SCRIPT: String get() = DshRuntimeScripts.NATIVE_CLI_SCRIPT
    internal val WORKFLOW_CLI_SCRIPT: String get() = DshRuntimeScripts.WORKFLOW_CLI_SCRIPT
    internal val WORKFLOW_DSL_DOC: String get() = DshRuntimeScripts.WORKFLOW_DSL_DOC
    internal val WORKFLOW_SAMPLES: Map<String, String> get() = DshRuntimeScripts.WORKFLOW_SAMPLES
    internal val WORKFLOW_SAMPLES_DIR: String get() = DshRuntimeScripts.WORKFLOW_SAMPLES_DIR
    internal val WORKFLOW_DSL_DOC_NAME: String get() = DshRuntimeScripts.WORKFLOW_DSL_DOC_NAME
    internal val TROUBLESHOOT_DOC_NAME: String get() = DshRuntimeScripts.TROUBLESHOOT_DOC_NAME
    internal fun nativeTroubleshootDoc(): String = DshRuntimeScripts.nativeTroubleshootDoc(appContext)



    /**
     * 预装插件的「最低要求版本」名单（见 [applySeedVersionUpgrade]）。
     *
     * DSH 启动时，凡当前已装版本**低于**这里要求的预装包，就用 `github:` 规格重装一次以拉到
     * 满足要求的新版本；已达标的不动。要求版本必须与插件仓 `package.json` 的版本对齐：插件发了
     * 需要 App 一并到位的改动，就抬插件版本、再把这里的要求版本同步上来。
     */
    // 0.5.0：云备份面板加了「包含应用主题」开关（按主题包大小自动默认），需要 App 侧的
    // /cloud/appdata/theme 端点与导出的 includeTheme 参数配合；老插件不会传该参数，
    // App 侧按 true 兜底，所以只是「拿不到新功能」，不会出错。
    //
    // dsh-config-manager 0.1.64（用户 2026-09-25 指定）：会话跨机恢复与凭据回填那一批修复
    // （上游 issue #45）。对我们尤其关键的三条：
    //   ① sessions 进了分区注册表（applyOrder 14），导入执行阶段会**真的写会话文件**并做归位/
    //      登记 —— 所以 App 侧必须把会话计划项从交给它的计划里剔除，由 App 独占会话
    //      （见 DshConfigBackup：以前插件不执行 sessions，我们独占是为了绕开静默丢弃；现在
    //      是**有意独占**，不剔除就会两边都写）；
    //   ② /plan 与 /analyze 认 decryptPassword，不传就会把「只存在于 secrets.enc、未被
    //      credentialsStatus 声明」的凭据从计划里漏掉（真机反馈「导入密钥没生效」）；
    //   ③ 会用 manifest 的 sourceHome 自动生成跨机基础路径重定基规则，App 侧据此同时给
    //      /plan 的 pathMappings 传映射（见 DshBackupArchive）。
    //
    // 种子最低版本 = 预装升级流程的「门槛」，低于它就在启动时被拉到这个版本。
    //
    // 纪律：**只能抬到同时兼容 0.1.x 与 0.2.x 的版本**。因为 App 同时挂着 stable(0.1.7)
    // 与 beta(0.2.0) 两个运行时，抬到一个只认 0.2.0 的版本，会把 stable 用户升级到
    // 反被 dsh 兼容闸门整包跳过的包 —— 那就从「云备份不可用」变成「插件市场不可用」。
    //
    // dshmarket 1.66.8：peerDeps 是 `^0.1.0-rc.7 || ^0.1.1-rc.2 || ^0.1.2-alpha.2 || ^0.2.0-rc.1`
    //   —— 0.1.x 与 0.2.x 都在范围内，是双兼容的，所以从 1.65.1 抬到它（0.2.0 上 1.65.1 会被
    //   跳过；真机已由用户手动更新验证过 1.66.8 在 0.2.0 正常）。
    // dsh-web-mobile 3.0.3：**上游最新就是它**，且范围明确 `<0.2.0`，没有双兼容版本可抬，
    //   故保持不动。实测它在 0.2.0 上并未被跳过（它不是 profile bundle，不吃那道闸门）且 UI 正常。
    // dsh-config-manager 0.1.64：范围本来就是 `>=0.1.0-rc.6 <0.3.0-0`，天然双兼容。


    /** 取某个预装包的安装 spec（默认即包名）。 */
    fun seedSpec(pkg: String): String = SEED_SPECS[pkg] ?: pkg

    /** 取某个预装包的 tgz 兜底直链（没有则 null）。 */
    fun seedFallbackTgz(pkg: String): String? = SEED_FALLBACK_TGZ[pkg]


    /** 退役判定用的 dsh 版本下界：从这个版本起上游自带 `file-upload`。 */
    private const val RETIRE_MIN_DSH_VERSION = "0.1.5"



    /**
     * 当前 App 的 semver 核心段（去掉 `-beta.N` 这类预发布后缀）。
     *
     * 最低版本比较只看核心段：`1.8.3-beta.20` 与 `1.8.3` 在功能上同代，按完整
     * semver 比会把所有测试版用户误拦在 `minAppVersion = 1.8.3` 之外。
     */
    private fun appCoreVersion(): String = me.bmax.apatch.BuildConfig.VERSION_NAME.substringBefore('-')

    /**
     * App 是否满足运行时的最低版本要求；空要求恒为满足（旧 metadata）。
     *
     * 两种门槛分开处理，因为它们的意图不同：
     *
     * - 要求**不带**预发布后缀（`1.8.4`）时只比核心段：`1.8.4-beta.32` 与 `1.8.4` 功能同代，
     *   门槛是按「这一代 App 有没有那些适配」写的，按完整 semver 比会把所有测试版用户挡在
     *   门外 —— 包括运行时测试通道自己（它的要求正是 `1.8.4`）。
     * - 要求**带**预发布后缀（`1.8.4-beta.32`）时按完整版本比。这种写法是在说「至少到这个
     *   测试版」，而只比核心段会让它永远成立：正式版 App 的预发布段被丢掉后比较恒为「满足」，
     *   于是这条要求等于没写。
     */
    override fun appSatisfies(minAppVersion: String): Boolean {
        if (minAppVersion.isBlank()) return true
        val exact = minAppVersion.substringBefore('+').contains('-')
        val installed = if (exact) me.bmax.apatch.BuildConfig.VERSION_NAME else appCoreVersion()
        return compareVersions(installed, minAppVersion) >= 0
    }



    /** 预装插件集合（供插件列表/商店渲染「预装」标签）。 */
    fun isSeedPlugin(pkg: String): Boolean = pkg in SEED_PLUGINS

    /** raw string 里没法直接写美元加大括号（Kotlin 会当模板求值），用这个常量拼。 */
    private const val D = "$"


    private val scope get() = DshKernelState.scope
    // ─────────────────── 更新提示的全局闸门 ───────────────────
    //
    // 自动检查（冷启动 2.5s 后，MainActivity）与手动检查（设置页卡片）是**两条独立路径**，
    // 各自弹各自的确认框。用户在冷启动后进设置点一下「检查更新」，两条都会命中，
    // 于是**同一个更新弹两次窗** —— 用户看到的是"点了两次一样的东西"。
    //
    // 修法不是让其中一条别弹（两条都是用户可见的意图），而是让它们**共用一份"已经提示过"**
    // 的事实：谁先弹谁登记版本号，后到的发现同一个版本已经提示过就不再弹。
    //
    // 只按版本号去重、不按时间：用户关掉提示后再次手动检查，仍应看到结果（那是他主动要的），
    // 所以去重只在**提示还挂着**时生效，见 clearPromptedRuntimeVersion()。
    private val _promptedRuntimeVersion = MutableStateFlow<String?>(null)

    /** 当前正被提示的运行时版本；null = 没有提示在挂。 */
    val promptedRuntimeVersion: StateFlow<String?> = _promptedRuntimeVersion.asStateFlow()

    /**
     * 登记「这个版本已经弹过提示」。
     *
     * @return true = 本次抢到了提示权（调用方该弹）；false = 同一个版本已经在提示中（不该再弹）。
     */
    fun claimRuntimePrompt(version: String): Boolean {
        if (version.isEmpty()) return false
        if (_promptedRuntimeVersion.value == version) return false
        _promptedRuntimeVersion.value = version
        return true
    }

    /** 提示关掉时调用：放开闸门，下次手动检查仍能看到结果。 */
    fun clearPromptedRuntimeVersion() {
        _promptedRuntimeVersion.value = null
    }

    private val _state get() = DshKernelState.stateFlow
    val state: StateFlow<DshState> = DshKernelState.state

    // ────────── DshRuntimeHost：安装簇等辅助文件只认这个接口 ──────────
    // 接口由本 object 直接实现：冲突的 10 个私有成员已放宽为 internal（外部调用为 0），
    // 6 个公开成员加 override（名字与可见性不变，7 处外部调用点照旧）。
    /** [DshRuntimeHost] 的只读入口（与 [state] 同一个流）。 */
    override val stateFlow: StateFlow<DshState> get() = _state

    /** [DshRuntimeHost] 的写入口（与内部 `_state.update` 同义）。 */
    /** [DshRuntimeHost] 的同步读入口。 */
    override val current: DshState get() = _state.value

    override fun setState(update: (DshState) -> DshState) { _state.update(update) }

    override val elfMachineAarch64: Int get() = ELF_MACHINE_AARCH64
    override val elfMachineX86_64: Int get() = ELF_MACHINE_X86_64


    /** 宿主 Context（安装簇的扩展函数也要用；原先 private，外部无调用）。 */
    internal val appContext: Context get() = DshKernelState.appContext
    private var serverProcess: Process?
        get() = DshKernelState.serverProcess
        set(v) { DshKernelState.serverProcess = v }
    private var startedAt: Long
        get() = DshKernelState.startedAt
        set(v) { DshKernelState.startedAt = v }

    private var hardlinkOk: Boolean?
        get() = DshKernelState.hardlinkOk
        set(v) { DshKernelState.hardlinkOk = v }

    /**
     * 硬链接探测失败的原因（异常类名 + message），成功时为空。
     *
     * 必须留下来：原来只走 Log.i，而 logcat 在 bugreport 里只有最近几分钟，
     * App 早已启动完，那一行永远抓不到 —— 于是「为什么这台设备不支持硬链接」
     * 每次都只能靠猜。现在它会随 [startServer] 一起写进 dsh.log。
     */
    private var hardlinkDetail: String
        get() = DshKernelState.hardlinkDetail
        set(v) { DshKernelState.hardlinkDetail = v }

    /**
     * 只绑定 Context，不做任何 IO。**必须在任何 Composable 读取本对象之前调用**
     * （APApplication.onCreate 里）。
     *
     * 原来只有 attach() 一个入口，而它是在首页的 LaunchedEffect 里调的 —— 那时
     * 首次 composition 已经跑完了，composition 期间读 runtimeId() / port() 会撞上
     * 未初始化的 lateinit 直接崩掉（真机实测：进首页几秒后
     * UninitializedPropertyAccessException）。
     */
    fun init(context: Context) {
        if (!DshKernelState.ready) {
            // 状态统一交给 DshKernelState（拆解后多个职责类共用同一份）。
            DshKernelState.bind(context)
            // 把「写日志」「在容器里跑命令」两个出口挂到 KernelEnv。
            //
            // 这一步是断环的关键：DshEnv / ContainerRuntime / AdbBridge 走
            // KernelEnv 的回调，而不是反过来引用本单例。环一断，它们才能下沉到
            // :core-kernel（之前依赖闭包 57 个文件，谁都动不了）。
            KernelEnv.setLogSink(::appendLog)
            KernelEnv.setContainerExec { bashCommand, timeoutMs ->
                execRootfsForOutput(bashCommand, timeoutMs)
            }
            // 上一次运行时替换如果被强杀打断，先恢复可启动的 rootfs，再认领暂存数据。
            recoverInterruptedRuntimeInstall()
            // 进程重启后旧日志不该残留：清一次，让启动日志按「本次运行」呈现。
            // startServer() 里还会再清一次，这里主要覆盖「只开 App 不启动服务」的情况。
            clearLog()
        }
    }

    /** Context 是否已绑定。未绑定时所有读接口给默认值而不是抛异常。 */
    private val ready: Boolean get() = DshKernelState.ready

    /**
     * 已绑定的 application context（给同在 dsh 包里的兄弟对象用，如 [DshPluginRepo] 要拿它读
     * 竞速通道的测速缓存）。未绑定返回 null —— 调用方自己决定怎么处理，不要在这里抛。
     */
    fun appContextOrNull(): Context? = DshKernelState.appContextOrNull()

    fun attach(context: Context) {
        init(context)
        val installed = DshEnv.isRuntimeInstalled(appContext)
        val cachedSize = prefs().getLong(DshEnv.KEY_ROOTFS_SIZE, 0L)
        // 已装运行时的最低 App 版本要求是**持久化**的：App 升级/降级后（prefs 保留）
        // 或离线时都要能判断「这份运行时还能不能用」，不能依赖现查 metadata。
        val minApp = prefs().getString(DshEnv.KEY_RUNTIME_MIN_APP, null).orEmpty()
        _state.update {
            it.copy(
                installed = installed,
                port = port(),
                runtimeVersion = prefs().getString(DshEnv.KEY_RUNTIME_VERSION, null),
                appUpdateRequired = installed && !appSatisfies(minApp),
                requiredAppVersion = minApp.ifEmpty { null },
                rootfsSizeBytes = if (installed) cachedSize else 0L,
                phase = if (installed) it.phase else DshPhase.NOT_READY,
            )
        }
        // 装好了但还没量过（升级上来的旧安装）：后台补一次，别让界面一直显示「—」
        if (installed && cachedSize <= 0L) refreshRootfsSize()
    }

    /**
     * 后台重算容器体积并落盘缓存。
     *
     * 只允许从这里进入 [dirSize]：它要遍历十万级文件，在组合期同步调用会让
     * 每次导航回首页都卡两秒以上（真机 MIUIScout 实测 duration=2505ms）。
     */
    override fun refreshRootfsSize() {
        if (!ready) return
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { dirSize(DshEnv.rootfs(appContext)) }
            prefs().edit().putLong(DshEnv.KEY_ROOTFS_SIZE, bytes).apply()
            _state.update { it.copy(rootfsSizeBytes = bytes) }
        }
    }

    override fun prefs() = appContext.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /** 见 [DshKernelState.port]（下沉到 :core-kernel，:ext-* 也要用）。 */
    fun port(): Int = DshKernelState.port()

    /** 当前 WebUI 地址（带认证 token，若已从 dsh 输出里捕获到）。 */
    fun webUrl(): String = _state.value.webUrl

    fun setPort(p: Int) {
        if (!ready || p !in 1..65535) return
        prefs().edit().putInt(DshEnv.KEY_PORT, p).apply()
        _state.update { it.copy(port = p) }
    }

    // 竞速通道 id 常量随 [DshPortConfig] 一起搬走了，这里保留同名转发
    // （DshPluginRepo / 设置页都按 DshRuntime.RACE_* 引用）。
    const val RACE_PLUGINS = DshPortConfig.RACE_PLUGINS
    const val RACE_APP_UPDATE = DshPortConfig.RACE_APP_UPDATE
    const val RACE_RUNTIME = DshPortConfig.RACE_RUNTIME

    // ────────────────────────── 端口占用检测 ──────────────────────────
    // ────────────────────────── 局域网访问 ──────────────────────────
    // ────────────────────────── 竞速通道（镜像/测速） ──────────────────────────
    // ────────────────────────── 应用启动行为 ──────────────────────────
    //
    // 以上四块已迁到 [DshPortConfig]（Checkpoint 1 的职责拆解）。这里只留同名转发。

    fun isPortInUse(port: Int): Boolean = DshPortConfig.isPortInUse(port)

    fun findFreePort(from: Int = port() + 1): Int = DshPortConfig.findFreePort(from)

    fun resolvePortConflict(action: PortConflictAction, newPort: Int? = null) =
        DshPortConfig.resolvePortConflict(action, newPort)

    fun lanEnabled(): Boolean = DshPortConfig.lanEnabled()

    fun setLanEnabled(enabled: Boolean) = DshPortConfig.setLanEnabled(enabled)

    fun raceMasterEnabled(): Boolean = DshPortConfig.raceMasterEnabled()

    fun setRaceMasterEnabled(enabled: Boolean) = DshPortConfig.setRaceMasterEnabled(enabled)

    override fun raceEnabled(channel: String): Boolean = DshPortConfig.raceEnabled(channel)

    fun setRaceEnabled(channel: String, enabled: Boolean) = DshPortConfig.setRaceEnabled(channel, enabled)

    fun autoStartOnLaunch(): Boolean = DshPortConfig.autoStartOnLaunch()

    fun setAutoStartOnLaunch(enabled: Boolean) = DshPortConfig.setAutoStartOnLaunch(enabled)

    fun autoOpenWebUi(): Boolean = DshPortConfig.autoOpenWebUi()

    fun setAutoOpenWebUi(enabled: Boolean) = DshPortConfig.setAutoOpenWebUi(enabled)

    fun lanIp(): String? = DshPortConfig.lanIp()

    // ────────────────────────── 容器运行时选择 ──────────────────────────

    /**
     * 当前选择的运行时 id。
     *
     * 默认 proroot：它不走 ptrace，容器内进程开销明显低于 proot。代价是在部分
     * 内核上会卡在 seccomp/ptrace 上起不来 —— 这条路由 [noteProrootFailure]
     * 兜底，失败一次就自动切回 proot，所以默认选快的那个是合理的。
     *
     * 注意这是**用户的选择**，不一定是实际在跑的那个：proroot 的五个 .so 只有
     * arm64-v8a 有，x86_64 设备上这里返回 proroot 而 [runtime] 给的是 proot。
     * 凡是描述或依赖「实际行为」的地方用 [effectiveRuntimeId]。
     */
    fun runtimeId(): String =
        if (!ready) "proroot" else prefs().getString(DshEnv.KEY_RUNTIME, "proroot") ?: "proroot"

    /**
     * 实际会用来起容器的运行时 id。
     *
     * 与 [runtimeId] 的差别只在「选了 proroot 但它在本机不可用」这一种情况，而这一种
     * 在 x86_64 设备上是**常态**（proroot 上游只出 arm64）。分不清两者会同时产生
     * 三个可观察的错误，报障包里都出现过：
     *  - 启动日志第 1 行说 proot（走 runtime()），第 3 行说「proroot 无条件启用 l2s」；
     *  - basic.txt 记 `ContainerRuntime: proroot`，与同一个包里的 dsh.log 自相矛盾；
     *  - [linkBecomesSymlink] 误判为 true，pnpm 被无谓地降级成 `--package-import-method
     *    copy` —— 明明 proot 在硬链接可用时根本不加 `--link2symlink`。
     */
    fun effectiveRuntimeId(): String = if (!ready) "proroot" else runtime().id()

    fun setRuntimeId(id: String) {
        if (!ready) return
        prefs().edit().putString(DshEnv.KEY_RUNTIME, id).putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
    }

    /** 解析出实际可用的运行时；选中的不可用时静默回退 proot。 */
    override fun runtime(): ContainerRuntime {
        val proot = ContainerRuntime.Proot(appContext, nativeLib("libproot.so"))
        // chroot 需要 root 通道；不可用就静默回退 proot（与 proroot 同一套策略）。
        if (runtimeId() == "chroot") {
            val chroot = ContainerRuntime.Chroot(appContext)
            if (chroot.available()) return chroot
        }
        if (runtimeId() != "proroot") return proot
        val proroot = ContainerRuntime.Proroot(appContext, ContainerRuntime.Proroot.defaultDir(appContext))
        return if (proroot.available()) proroot else proot
    }

    /**
     * 刚刚发生过 proroot → proot 的自动回退，等着用 proot 重试一次。
     *
     * 存在的理由：[PROROOT_FAIL_LIMIT] 是 1，失败即回退。如果只写一行
     * 「已切回 proot，请重新启动」，用户首次开应用就会撞上一次失败并被
     * 要求手动重试——而此时我们已经知道该用 proot 了。
     */
    @Volatile
    private var prorootFellBack = false

    /**
     * 记一次 proroot 启动失败；达上限强制切回 proot。返回是否触发了回退。
     *
     * 插件层面的失败不算：见 [PLUGIN_FAILURE_MARKS]。
     */
    fun noteProrootFailure(why: String): Boolean {
        // chroot 与 proroot 共用这套「失败即回退」：两者都是实验性启动方式，
        // 一旦起不来就必须自动回到 proot，否则用户会卡在「每次都失败」。
        val id = runtimeId()
        if (id != "proroot" && id != "chroot") return false
        // 插件树加载失败与运行时无关：以前这条判据排在 chroot 分支**之后**，于是插件报错
        // 也会把 chroot 判死并回退 proot（评审 P0-3 附带项）。判据必须先跑。
        if (lastLogSuggestsPluginFailure()) {
            logWarn(CoreR.string.dsh_log_plugin_stage_failure)
            return false
        }
        if (id == "chroot") {
            prefs().edit().putString(DshEnv.KEY_RUNTIME, "proot").apply()
            logWarn(CoreR.string.dsh_log_proroot_failed, "chroot: " + why)
            prorootFellBack = true
            return true
        }
        val n = prefs().getInt(DshEnv.KEY_PROROOT_FAIL, 0) + 1
        return if (n >= PROROOT_FAIL_LIMIT) {
            prefs().edit().putString(DshEnv.KEY_RUNTIME, "proot").putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
            // 两条独立的完整句子，而不是把「连续 N 次」拼进一句 —— 片段拼接在
            // 别的语言里语序就散了（这也是为什么没有 dsh_log_proroot_failed_prefix）
            if (n > 1) logWarn(CoreR.string.dsh_log_proroot_failed_times, n, why)
            else logWarn(CoreR.string.dsh_log_proroot_failed, why)
            prorootFellBack = true
            true
        } else {
            prefs().edit().putInt(DshEnv.KEY_PROROOT_FAIL, n).apply()
            false
        }
    }

    /**
     * 容器启动前把 rootfs 里的容器数据修回 App uid。
     *
     * 为什么必须有：chroot 是**真 root**（见 [ContainerRuntime.Chroot]），它写出的
     * /root/.dsh、/tmp、/usr/local 归 root；而 proot/proroot 里的进程是 App uid，连读都读不了
     * —— 真机表现就是 proot 启动直接报
     * `EACCES ... /root/.dsh/profiles/web/cordis.patch.yml`（issue #12）。
     * proot 自己修不了（它不是 root），所以只能借一条 root 通道在**起容器之前**做。
     *
     * chroot 的启动脚本里也有同样一段（进入前 + 退出时各一次）。这里这份是给
     * 「用户从 chroot 切回 proot/proroot」兜底 —— 那条路根本不会执行 chroot 的脚本，
     * 而恰恰是那种情况下会撞上 EACCES。
     *
     * 没有 root 通道时直接跳过；失败不阻断启动。
     */
    private fun repairRootfsOwnership() {
        val root = DshEnv.rootfs(appContext)
        if (!root.isDirectory) return
        // 与 chroot 能否启动同一判据：root 通道就绪
        if (!ContainerRuntime.Chroot(appContext).available()) return
        if (!PrivilegedShell.tryEnter()) {
            appendLog("[own] 跳过 rootfs 属主修复：特权命令通道忙")
            return
        }
        try {
            val uid = appContext.applicationInfo.uid
            val q = { s: String -> ContainerRuntime.Chroot.shellQuote(s) }
            val script = "if ! command -v find >/dev/null 2>&1 || " +
                // \$ 是 Kotlin 普通字符串里的字面量 $：这里要的是 shell 的命令替换
                "[ -n \"\$(find ${q(File(root, "root").path)} ! -user $uid 2>/dev/null | head -n 1)\" ]; then " +
                "chown -R $uid:$uid ${q(File(root, "root").path)} ${q(File(root, "tmp").path)} " +
                "${q(File(root, "usr/local").path)} 2>/dev/null; fi"
            val out = PrivilegedShell.exec(appContext, script, asRoot = true, timeoutMs = 20_000L)
            if (out.exit == 0) {
                appendLog("[own] rootfs 属主已核对（非 App uid 的条目已修回）")
            } else {
                appendLog("[own] rootfs 属主修复未成功：exit=" + out.exit + " " + out.stderr.take(120))
            }
        } finally {
            PrivilegedShell.exit()
        }
    }

    /**
     * 启动日志尾部是否指向插件树加载失败。
     *
     * 保守匹配：宁可漏判（退回旧的误降级行为）也不误判（把真正的 proroot 故障
     * 当成插件问题、让用户一直卡在坏运行时上）。
     */
    private fun lastLogSuggestsPluginFailure(): Boolean {
        if (!ready) return false
        val tail = runCatching {
            LogStore.named(DshEnv.serverLog(appContext)).tail(200)
        }.getOrDefault("")
        if (tail.isEmpty()) return false
        return PLUGIN_FAILURE_MARKS.any { tail.contains(it, ignoreCase = true) }
    }

    /** 插件树加载失败的可修复提示；无此迹象时返回 null。 */
    fun pluginTreeFailureHint(): String? =
        if (lastLogSuggestsPluginFailure()) str(CoreR.string.dsh_err_plugin_tree_failed) else null

    private fun nativeLib(name: String): File = File(DshEnv.nativeLibDir(appContext), name)

    /**
     * rootfs 所在文件系统是否支持真实硬链接。
     *
     * 实现已迁到 [DshLogging.supported]（探测结果缓存在 [DshKernelState]）。
     */
    fun hardlinkSupported(): Boolean = DshLogging.supported()

    /** 硬链接探测失败的原因；成功时为空。 */
    fun hardlinkDetail(): String = DshLogging.detail()

    /**
     * guest 里的 `link()` 是否会被改写成符号链接。
     *
     * 不能只看 [hardlinkSupported]：proroot **无条件**加 `--link2symlink`
     * （见 ContainerRuntime.Proroot.baseArgv），所以哪怕宿主文件系统支持真硬链接，
     * 容器内拿到的仍然是符号链接。pnpm 正是用 `link()` 从内容存储装包，一旦被改写，
     * `require.resolve` 的 realpath 就会跳进 CAS、把包目录结构解析没了。
     */
    fun linkBecomesSymlink(): Boolean = !hardlinkSupported() || effectiveRuntimeId() == "proroot"

    /** 复制 proot 的 NEEDED 依赖到可写 lib 目录。 */
    internal fun ensureRuntimeFiles() {
        val libDir = File(appContext.filesDir, "lib").apply { mkdirs() }
        DshEnv.tmpDir(appContext).mkdirs()
        // 每条 exec 路径都要保证 DNS 在：冷启动直接进插件页 / 终端页时不会走 bootstrap，
        // 少了这一步容器里 pnpm、apt 全是 EAI_AGAIN。已存在则原样保留（用户可能改过）。
        ensureContainerDns()
        ensureProfilePnpmSettings()
        ensureContainerGroups()
        // **无条件**备好 proot 的 NEEDED 依赖，不按「当前选中的运行时」跳过。
        //
        // 这里曾经是 `if (runtime().id() != "proroot") return`（注释还写着「proroot 不需要」），
        // 在选了 proroot 的设备上直接不复制。那是**回归**：`prootBaseArgv()` 现在是全局兜底
        // （PTY 在 chroot 下回退、spawnArgv 返回 null 时回退、运行时不可用时回退），于是 proroot
        // 模式下也会真的去 exec `libproot.so` —— 而它 NEEDED `libtalloc.so.2`（soname；jniLibs
        // 里只有 `libtalloc.so`）。真机表现（OnePlus PLQ110 / Android 17 / beta.212）：
        //   CANNOT LINK EXECUTABLE ".../lib/libproot.so": library "libtalloc.so.2" not found
        // 复制很便宜（31KB；目标已存在且长度相同就直接返回），没有任何理由省这一步。
        //
        // jniLibs 里叫 libtalloc.so / libandroidshmem.so，proot 按 SONAME 找。
        copyExec(nativeLib("libtalloc.so"), File(libDir, "libtalloc.so.2"))
        copyExec(nativeLib("libandroidshmem.so"), File(libDir, "libandroid-shmem.so"))
    }

    private fun copyExec(src: File, dst: File) {
        if (!src.isFile) return
        if (dst.isFile && dst.length() == src.length()) return
        runCatching {
            src.inputStream().use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
            dst.setExecutable(true, false)
        }
    }

    // ────────────────────────── 进容器执行 ──────────────────────────
    //
    // 实现已迁到 [DshExec]（Checkpoint 1 的职责拆解）。这里只留同名转发。

    fun execRootfs(bashCommand: String): Process = DshExec.execRootfs(bashCommand)

    fun execRootfsInteractive(): Process = DshExec.execRootfsInteractive()

    fun ptyArgv(vararg guestCmd: String): Array<String> = DshExec.ptyArgv(*guestCmd)

    fun ptyEnv(): Array<String> = DshExec.ptyEnv()

    fun execRootfsForOutput(bashCommand: String, timeoutMs: Long = 60_000L): String =
        DshExec.execRootfsForOutput(bashCommand, timeoutMs)

    fun execRootfsStreaming(
        bashCommand: String,
        timeoutMs: Long = 60_000L,
        onLine: (String) -> Unit,
    ): String = DshExec.execRootfsStreaming(bashCommand, timeoutMs, onLine)

    /** [DshExec] 要拼 libproot.so 的路径；保持 private 语义但跨对象可见。 */
    internal fun nativeLibFor(name: String): File = nativeLib(name)
    // ────────────────────────── 引导（下载 + 解压 + 启动）──────────────────────────

    /** 引导/重启/重装共用的串行锁：三者都会动 rootfs 与服务进程。 */
    private val bootMutex get() = DshKernelState.bootMutex

    /**
     * 一键引导：未装则下载安装，然后拉起 web 服务。
     *
     * [startServer] 的守卫现在只看进程，不再兼当「防重复下载」；
     * 而 HarnessService 在 DOWNLOADING / EXTRACTING 阶段依然会放行 bootstrap，
     * 连点两次启动就会开两条 135MB 下载。用互斥锁 + 阶段判定拦住。
     */
    /** 强行启动：跳过端口探测，直接走引导（[DshPortConfig] 的 FORCE 分支要用）。 */
    internal fun bootstrapForced() {
        scope.launch { bootMutex.withLock { startAndAwait() } }
    }

    override fun bootstrap() {
        scope.launch {
            if (busy()) return@launch
            bootMutex.withLock {
                if (!DshEnv.isRuntimeInstalled(appContext)) {
                    downloadAndInstall()
                    if (_state.value.phase == DshPhase.ERROR) return@withLock
                } else if (rootfsArchMismatch()) {
                    // 已装的 rootfs 架构不对，重下一份正确的。
                    // 1.7.6 在带 arm 转译层的 x86_64 设备上会装成 arm64 rootfs（见
                    // DshSource.runtimeArch 的注释），那份 rootfs 一执行就 SIGILL，
                    // 而 isRuntimeInstalled 只看文件在不在、会一直认为「已安装」——
                    // 不在这里自愈，用户就只能手动重装运行时才能脱困。
                    logWarn(CoreR.string.dsh_log_arch_mismatch_redownload)
                    downloadAndInstall()
                    if (_state.value.phase == DshPhase.ERROR) return@withLock
                }
                setupResolvConf()
                seedPlugins()
                ensureFsBridgeCli()
                if (checkPortConflict()) return@withLock
                startAndAwait()
            }
        }
    }

    /**
     * 已解压的 rootfs 是不是别的架构。
     *
     * 判据取 rootfs 里 `usr/local/bin/node` 的 ELF `e_machine` —— 它就是 proot 起来后
     * 第一个真正要 exec 的目标。读不到（文件缺失、异常）返回 false：宁可放行也不要因为
     * 一次读失败就把用户的 150MB 运行时删了重下。
     */
    private fun rootfsArchMismatch(): Boolean = runCatching {
        val node = File(DshEnv.rootfs(appContext), "usr/local/bin/node")
        val machine = elfMachine(node) ?: return@runCatching false
        val want = when (DshSource.runtimeArch()) {
            "arm64-v8a" -> ELF_MACHINE_AARCH64
            else -> ELF_MACHINE_X86_64
        }
        if (machine == want) return@runCatching false
        logWarn(CoreR.string.dsh_log_node_machine, machine, want)
        true
    }.getOrDefault(false)

    /**
     * 预装内置插件（首启，以及从旧版本升级后补装新增的那几个）。
     *
     * 放在服务启动**之前**：首启本来就要下 150MB + 解压，再加一轮 pnpm 是等比例的；
     * 而服务只启动一次、插件已经生效，不会出现「就绪了又要重启」的突兀体验。
     *
     * **按包名逐个记账**而不是记一个「已完成」布尔量：1.6 把清单从 2 个加到 3 个，
     * 如果沿用布尔量，1.5 老用户的标记已经是 true，新增的 dsh-config-manager 就
     * 永远轮不到装 —— 而它恰好是配置备份功能的依赖。记名字才能让老用户补上增量。
     *
     * 装过就不再重试（无论成功失败）：失败不该在每次冷启动重来，用户可以去商店手动装。
     * 但已经真装上的会先被跳过 —— 用 [DshPluginRepo.bundles] 对一遍，
     * 手动装过的同样算数，不会重复跑一次 pnpm。
     *
     * 这里**不做**安装后验证：插件是我们自己挑的、已人工验证过，在首启路径上再跑
     * 一次 `dsh web --port 0` 只会把首启拖长几分钟。
     */
    internal suspend fun seedPlugins() {
        if (!DshEnv.isRuntimeInstalled(appContext)) return

        // 预装/补装全靠 `dsh plugin` → pnpm。运行时没带 pnpm 时（旧 rootfs）只报一次
        // 并停止：四个预装包逐个 exit 127 只是刷屏，用户得不到任何可行动的信息。
        // 恢复路径就是更新运行时 —— 不含 pnpm 的旧 rootfs 已从两个通道重新发布。
        if (!pnpmInstalled()) {
            logWarn(CoreR.string.dsh_log_missing_pnpm)
            return
        }

        // profile 的 bundles 里留着「解析不到的包」会让 dsh 直接拒绝启动
        // （见 DshPluginRepo.pruneUnresolvableBundles 的说明）。启动前先清掉，
        // 别让用户先看一轮「服务进程已退出」再自动切回 proot。
        val prunedStale = runCatching {
            DshPluginRepo.pruneUnresolvableBundles(managedSeedPackages())
        }.getOrElse { emptySet() }
        if (prunedStale.isNotEmpty()) {
            logInfo(CoreR.string.dsh_log_bundle_pruned, joinForLog(prunedStale))
        }

        val p = prefs()
        val attempted = p.getString(DshEnv.KEY_SEEDED_PLUGINS, null)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet()
            // 首次迁移：1.5 及更早只有布尔量，true 就意味着当时那两个已经试过了
            ?: if (@Suppress("DEPRECATION") p.getBoolean(DshEnv.KEY_SEED_PLUGINS_DONE, false)) {
                mutableSetOf("dsh-web-mobile", "dshmarket")
            } else {
                mutableSetOf()
            }

        // 已经装上的（含用户手动装的）。放在补修之前取：补修要靠它判断「记过账但没生效」。
        // 判据是「声明在 profile 的 bundles 里**且** node_modules 里真的有包」——
        // 只看声明会把「换运行时后 profile 重建、包却没了」的坏安装当成好的。
        val bundleState = runCatching { DshPluginRepo.bundleState() }
            .getOrElse { DshPluginRepo.BundleState(emptyList(), emptySet()) }
        val installed = bundleState.declared.filter { it in bundleState.present }.toSet()

        // ── 「上游已内置」必须与「试过了」分开记账 ──
        //
        // 判据是**实际读到的 entry id**：同一条 id 只要由**别的**包（通常是上游核心包，
        // dsh 0.1.5 的 dsh-web-app 就自带 file-upload）声明了，这个预装包就不能再装 ——
        // 装了会让整棵插件树报 duplicate loader entry id 而根本起不来。
        //
        // 关键是**不能写进 attempted**。写进去等于告诉补修逻辑「我们试过它、但它不在
        // bundles 里」，于是被摘账重装，形成：跳过 → 重装 → 启动失败 → 自动卸载 →
        // 下次启动又重装 的死循环（1.9.0 真机实测）。所以这里单独一份 shadowed，
        // 并且三处都要认它：补修、换运行时重试、以及本轮要装哪些。
        // 退役包：上游已自带同名能力，一律不装；已装的在下面摘掉。
        // 判据是**运行时版本**（dsh ≥ RETIRE_MIN_DSH_VERSION 才有那份内置能力），
        // 不再去读上游的 entry id —— 那条路 yaml 解析失败时会静默返回空表，
        // 于是「该退役的没退役」，用户看到预装包还在（真机复现）。
        val runtimeNow = p.getString(DshEnv.KEY_RUNTIME_VERSION, "").orEmpty()
        val retiredActive = runtimeSupportsRetiredSeed(runtimeNow)
        val mappedShadowed = if (retiredActive) RETIRED_SEED_PLUGINS.keys.toSet() else emptySet()
        // 落盘的补充项：只由「启动失败 → 自动卸载」那条路径才能知道、映射表没覆盖的冲突。
        // 不落盘的话每次冷启动都会重装一次再失败一次。换运行时即作废（内置与否是运行时的属性）。
        val recordedShadowed = if (p.getString(DshEnv.KEY_SEED_SHADOWED_RUNTIME, null) == runtimeNow) {
            p.getString(DshEnv.KEY_SEED_SHADOWED, null)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                .orEmpty()
        } else {
            emptySet()
        }
        val shadowed = mappedShadowed + recordedShadowed
        // 刚被清掉的声明如果本来就属于「上游已内置」那一类，落盘记住它：
        // 实时判定已经会跳过它，这里只是让判定结果跨启动稳定。
        rememberShadowed(prunedStale.filter { it in mappedShadowed })

        // 已经装着的冲突包**在启动之前**就摘掉：否则这次启动照样先以 duplicate entry id
        // 失败一次，再走「失败后自愈」重试 —— 用户白等一轮，日志里多一段 node 堆栈。
        // 用 declared 而不是 installed：包已从 node_modules 消失、声明却还留着时，
        // 也要走一次卸载把声明摘干净（dsh 解析不到那条声明就拒绝启动）。
        val shadowedInstalled = shadowed.filter { it in installed || it in bundleState.declared }
        for (pkg in shadowedInstalled) {
            logWarn(CoreR.string.dsh_log_seed_shadow_uninstall, pkg, RETIRED_SEED_PLUGINS[pkg] ?: pkg)
            runCatching {
                DshPluginRepo.uninstall(pkg, onLine = { line -> appendLog(line) })
            }.onFailure { appendLog(it.message ?: it.javaClass.simpleName) }
        }
        val shadowedSkipped = shadowed - shadowedInstalled.toSet()
        if (shadowedSkipped.isNotEmpty()) {
            logInfo(CoreR.string.dsh_log_seed_skip_builtin, joinForLog(shadowedSkipped))
        }
        // 历史遗留：这些包以前被记成「试过了」。摘掉它们 —— 万一以后换回不含该能力的
        // 运行时，实时判定不再命中，它们就该重新预装。
        if (attempted.removeAll(shadowed.toSet())) persistSeeded(attempted)

        // 修好根因后补修历史失败（见 KEY_SEED_REPAIR_REV）
        applySeedRepair(p, attempted, installed, shadowed)
        // 换运行时（或本键还不存在）时再给一次机会：见 KEY_SEED_RUNTIME 的说明
        applySeedEnvRetry(p, attempted, installed, shadowed)

        // 用户主动取消勾选的包**永不自动装回**：那是明确的意愿表达，不是"还没轮到"。
        // 单独一份清单（不是混进 attempted），因为两者的语义不同：
        // attempted = 试过了；skipped = 用户不要。混在一起会让"用户后来想装了"没有任何入口。
        val skipped = seedSkippedPackages()
        val todo = SEED_PLUGINS.filter { it !in attempted && it !in shadowed && it !in skipped }
        val missing = todo.filter { it !in installed }
        if (todo.isNotEmpty() && missing.isEmpty()) {
            persistSeeded(attempted + todo)
        } else if (missing.isNotEmpty()) {
            _state.update {
                it.copy(phase = DshPhase.EXTRACTING, progress = 0f, message = str(CoreR.string.dsh_plugin_seeding))
            }
            for (pkg in missing) {
                logInfo(CoreR.string.dsh_log_seeding, pkg)
                val code = installSeedPkgWithApproval(pkg)
                if (code == 0) {
                    logInfo(CoreR.string.dsh_log_seed_done, pkg)
                } else {
                    logWarn(CoreR.string.dsh_log_seed_failed, pkg)
                }
            }
            persistSeeded(attempted + todo)
            // 预装会大幅改变 node_modules 体积，顺手重算一次缓存
            refreshRootfsSize()
            _state.update { it.copy(phase = DshPhase.NOT_READY, progress = 1f) }
        }

        // 按版本号门控刷新预装插件：只有当前已装版本**低于** SEED_MIN_VERSIONS 要求时才重装
        // （github: 拉最新 main）。用户要求「只有需要的时候才更新，对比版本号、低于才更新」。
        applySeedVersionUpgrade(shadowed)

        // 装后兜底（每次预装路径都跑，包括 todo 为空时 —— 运行时升级把上游内置能力
        // 带进来后，历史预装包也可能变成冲突源）：按实际读到的 entry id 找重复，
        // 卸载其中的预装包，保住插件树能启动。核心包与用户自装包绝不自动动。
        runCatching {
            val after = DshPluginRepo.pluginEntries(includeCore = true)
            val byId = after.entries
                .flatMap { (pkg, ids) -> ids.map { id -> id to pkg } }
                .groupBy({ it.first }, { it.second })
            for ((id, pkgs) in byId) {
                if (pkgs.size < 2) continue
                val conflicts = pkgs.distinct().filter { it in SEED_PLUGINS }
                if (conflicts.isEmpty()) continue
                for (pkg in conflicts) {
                    logWarn(CoreR.string.dsh_log_dup_entry_removed, pkg, id)
                    DshPluginRepo.uninstall(pkg, onLine = { line -> appendLog(line) })
                }
            }
        }
    }

    /** 从 dsh plugin 的输出里取真实退出码；没有标记行（超时/容器没起来）返回 null。 */
    private fun exitCodeOf(out: String): Int? = out.lineSequence()
        .lastOrNull { it.startsWith(DshPluginRepo.EXIT_MARKER) }
        ?.removePrefix(DshPluginRepo.EXIT_MARKER)?.trim()?.toIntOrNull()

    /**
     * 记下「上游已内置、别再预装」的包（跟随运行时版本，换运行时即作废）。
     *
     * 只有启动失败后的兜底修复才用得上它 —— 映射表能判定的冲突在启动前就算出来了，
     * 不需要落盘。这条路径的存在是为了让**没进映射表**的冲突也不至于变成每轮启动一次的
     * 「卸载 → 重装 → 再失败」。
     */
    private fun rememberShadowed(names: Collection<String>) {
        if (names.isEmpty()) return
        val p = prefs()
        val runtime = p.getString(DshEnv.KEY_RUNTIME_VERSION, "").orEmpty()
        val known = if (p.getString(DshEnv.KEY_SEED_SHADOWED_RUNTIME, null) == runtime) {
            p.getString(DshEnv.KEY_SEED_SHADOWED, null)
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                .orEmpty()
        } else {
            emptySet()
        }
        p.edit()
            .putString(DshEnv.KEY_SEED_SHADOWED, (known + names).distinct().joinToString(","))
            .putString(DshEnv.KEY_SEED_SHADOWED_RUNTIME, runtime)
            .apply()
    }

    /** 记下「已尝试预装」的包名集合。 */
    private fun persistSeeded(names: Collection<String>) {
        prefs().edit()
            .putString(DshEnv.KEY_SEEDED_PLUGINS, names.distinct().joinToString(","))
            .apply()
    }

    /**
     * 装一个预装包，并自动处理 pnpm「拦下构建脚本、等人点头」的情况（预装路径没人可问）。
     *
     * pnpm 拦下依赖的构建脚本时 **不是**「装不上」，而是以退出码 1 结束、dsh 不 reconcile
     * bundles，插件躺在 node_modules 里却进不了 profile（界面上就是「预装了但未生效」，
     * 1.7.6 的 dsh-file-upload 因传递依赖 sharp/tesseract.js 带 install 脚本正是如此）。
     * 交互式 `pnpm approve-builds` 在容器里跑不了，所以这里自动放行**本次预装自己拉进来的**
     * 那几个包的构建脚本并重试（范围仅限 pnpm 点名的，不是全局开关）。返回最终退出码。
     */
    private suspend fun installSeedPkgWithApproval(pkg: String): Int {
        // 版本：按当前运行时的 engines.dsh 挑一个能装的最高版本，而不是无脑 latest。
        //
        // 无脑 latest 的坑：插件新版会抬高 engines 要求（`engines.dsh >= 0.3.0`），而用户
        // 容器里还是 0.2.2 —— 装上后整棵插件树加载失败，本来能用的旧版本反而被弄坏。
        // 探测失败（网络/运行时版本未知）时 bestVersionFor 返回空串，这里退回 latest，
        // 行为与改造前一致。
        val dshVer = prefs().getString(DshEnv.KEY_RUNTIME_VERSION, null).orEmpty()
        val picked = runCatching { DshPluginRepo.bestVersionFor(pkg, dshVer) }
            .getOrElse { "" }
        val version = picked.ifBlank {
            if (picked.isBlank() && dshVer.isNotBlank()) {
                logInfo(CoreR.string.dsh_log_seed_version_fallback, pkg, dshVer)
            }
            DshPluginRepo.VERSION_LATEST
        }
        if (picked.isNotBlank()) logInfo(CoreR.string.dsh_log_seed_version_picked, pkg, picked, dshVer)

        // 显式版本：预装包门禁（[applySeedVersionUpgrade]）就是靠这次重装把版本顶上去的，
        // 而 pnpm 对已声明过的 registry 依赖，`add <裸包名>` 是空操作 → 门禁会静默失效。
        // git 规格（dsh-folk-cloud）由 DshPluginRepo.install 忽略版本、按裸规格重新解析最新提交。
        var out = runCatching {
            DshPluginRepo.install(
                seedSpec(pkg),
                version,
                onLine = { line -> appendLog(line) },
                fallbackTgz = seedFallbackTgz(pkg),
            )
        }.getOrElse { str(CoreR.string.dsh_log_seed_exception, it.message ?: it.javaClass.simpleName) }
        var code = exitCodeOf(out)
        if (code != 0) {
            val pending = DshPluginRepo.pendingBuildApproval(out)
            if (pending.isNotEmpty()) {
                logInfo(CoreR.string.dsh_log_seed_builds_blocked, joinForLog(pending))
                out = runCatching {
                    DshPluginRepo.install(
                        seedSpec(pkg),
                        version,
                        onLine = { line -> appendLog(line) },
                        allowBuilds = pending,
                        fallbackTgz = seedFallbackTgz(pkg),
                    )
                }.getOrElse { str(CoreR.string.dsh_log_seed_retry_exception, it.message ?: it.javaClass.simpleName) }
                code = exitCodeOf(out)
            }
        }
        // 只补一句可行动的话，不在这里修 rootfs：pnpm 由运行时自带。旧运行时那个无 shebang
        // 的 pnpm shim 会让 dsh 的 spawnSync 直接 ENOENT，报的就是这一行 —— 出路是更新运行时。
        if (code != 0 && out.contains(DshPluginRepo.NO_PNPM)) logWarn(CoreR.string.dsh_log_missing_pnpm)
        // exitCodeOf 解析不出退出码时返回 null —— 按失败处理（-1），调用方 `code == 0` 才是成功
        return code ?: -1
    }

    /**
     * 按版本号门控刷新预装插件：把**当前已装**版本低于 [SEED_MIN_VERSIONS] 要求的预装插件用
     * `github:` 规格重装一次（重新解析 main 最新提交），跟上必需的修复。
     *
     * 与「每次 App 升级都重装」相比，这里只在**真的过时**时才动手（用户要求：搞个名单，对比
     * 插件版本号、低于才更新）。判据是容器里 `node_modules/<pkg>/package.json` 的实际版本，
     * 所以插件仓每次发有意义的改动都要抬 package.json 版本，App 这边再把要求版本填进名单。
     *
     * 只碰当前已装、且不在 shadowed（上游已内置、不该装）里的包：用户卸掉的不会被复活，缺失
     * 首装仍由 missing 循环负责。重装失败不影响已装旧版本（install 失败不卸载），只落一行日志；
     * 仍低于要求时下次启动会再试（这正是「需要时才更新」的期望行为）。
     */
    /**
     * 某个预装插件的新版本，能否装进**当前这个运行时**。
     *
     * 预装插件的 peer 依赖是冲着 dsh 主包版本写的。插件升到新版时，它可能已经要求一个
     * 比手上运行时更新的 dsh —— 这时代替用户"升级"，结果是插件树直接起不来，
     * 连原来能用的旧版本也没了。宁可停在旧版本，等 App 把运行时一起换掉。
     *
     * 目前只有 dsh-web-mobile 有这条硬要求（3.3.0 起 peer 是 dsh-client-* >=0.2.2-rc.1）。
     * 其它包（dshmarket / dsh-config-manager）的 peer 是宽区间，任意较新运行时都满足，
     * 所以不设门槛 —— 门槛写多了会变成"永远不升级"的另一种 bug。
     *
     * @param runtimeVersion 容器里已装的运行时版本（metadata 的 dsh 字段，如 "0.2.2-rc.1"）。
     *   空 = 还没装或读不到，按"不满足"处理：此时装什么上去都没法保证能起来。
     */
    private fun seedUpgradeAllowed(pkg: String, runtimeVersion: String): Boolean {
        if (pkg != "dsh-web-mobile") return true
        val need = "0.2.2-rc.1"
        if (runtimeVersion.isBlank()) return false
        return compareVersions(runtimeVersion, need) >= 0
    }

    private suspend fun applySeedVersionUpgrade(shadowed: Set<String>) {
        // 有些预装插件的**新版本**反过来要求更新的运行时（dsh-web-mobile 3.3.0 起要求
        // dsh-client-* >= 0.2.2-rc.1）。运行时是随 release 一起发的，插件装进旧运行时后
        // 会因 peer 不满足而起不来，反而把本来能用的旧版本弄坏。
        // 所以：运行时版本够不上这个插件的要求时，**跳过升级**、保留旧版本继续用。
        // 判据是容器里记录的 runtimeVersion（metadata 的 dsh 字段），不是 App 版本。
        // 与 state 里那个 runtimeVersion 同源（都是 prefs 的 KEY_RUNTIME_VERSION）：
        // 这里是 suspend 上下文、拿不到 _state 的最新值，但 prefs 写入是在装完运行时
        // 之后才发生的，两者在这一点上等价。
        val rtVer = prefs().getString(DshEnv.KEY_RUNTIME_VERSION, null).orEmpty()
        val wanted = SEED_MIN_VERSIONS.filterKeys { pkg ->
            pkg !in shadowed && seedUpgradeAllowed(pkg, rtVer)
        }
        if (wanted.isEmpty()) return
        val byPkg = runCatching { DshPluginRepo.listInstalled() }.getOrNull()
            ?.associateBy { it.pkg } ?: return
        // 已装、有可比版本、且低于要求 → 需要重装。没装的不在这里复活（缺失首装走 missing 循环）。
        val outdated = wanted.filter { (pkg, min) ->
            val cur = byPkg[pkg]?.installedVersion.orEmpty()
            cur.isNotEmpty() && compareVersions(cur, min) < 0
        }
        if (outdated.isEmpty()) return
        _state.update {
            it.copy(phase = DshPhase.EXTRACTING, progress = 0f, message = str(CoreR.string.dsh_plugin_seeding))
        }
        for ((pkg, min) in outdated) {
            logInfo(CoreR.string.dsh_log_seed_version_upgrade, pkg, byPkg[pkg]?.installedVersion.orEmpty(), min)
            val code = installSeedPkgWithApproval(pkg)
            if (code == 0) logInfo(CoreR.string.dsh_log_seed_done, pkg) else logWarn(CoreR.string.dsh_log_seed_failed, pkg)
        }
        refreshRootfsSize()
        _state.update { it.copy(phase = DshPhase.NOT_READY, progress = 1f) }
    }

    /**
     * 补修历史预装失败：把**记过账但实际没进 bundles**的包从账本里摘掉，让它们再试一次。
     *
     * 为什么需要：[persistSeeded] 无论成败都记账（失败不该每次冷启动重试），于是修好
     * 根因也救不回已经失败的那次。1.7.6 的 dsh-file-upload 正卡在这里 —— pnpm 拦下
     * 传递依赖的构建脚本导致它没进 bundles，而包名已被记账，之后永远不再尝试。
     *
     * 只摘「不在 bundles 里」的：已生效的包不会被重跑。轮次号只前进一次，所以补修
     * 最多发生一轮，不会变成每次启动都重试失败项。
     */
    private fun applySeedRepair(
        p: android.content.SharedPreferences,
        attempted: MutableSet<String>,
        installed: Set<String>,
        shadowed: Set<String>,
    ) {
        if (p.getInt(DshEnv.KEY_SEED_REPAIR_REV, 0) >= SEED_REPAIR_REV) return
        // shadowed 的包是「上游已内置、不该装」，不是「试过但没生效」：摘它的账只会让它
        // 被重装一次再撞 duplicate entry id（1.9.0 的死循环就是这么来的）。
        val retry = attempted.filter { it in SEED_PLUGINS && it !in installed && it !in shadowed }
        if (retry.isNotEmpty()) {
            attempted.removeAll(retry.toSet())
            persistSeeded(attempted)
            logInfo(CoreR.string.dsh_log_seed_repair, joinForLog(retry))
        }
        p.edit().putInt(DshEnv.KEY_SEED_REPAIR_REV, SEED_REPAIR_REV).apply()
    }

    /**
     * 换运行时后重新给预装一次机会：把「记过账但没生效」的预装包从账本里摘掉。
     *
     * [applySeedRepair] 靠人工抬轮次号，只能救一次；用完后再遇到同一类问题
     * —— 预装失败 → 账本照样记账 → 之后永远不再尝试 —— 就是死胡同：
     * 用户的预装插件一直缺着，界面上不会有任何提示，日志里连一行都不会有
     * （账本提前返回，预装流程整个被跳过）。这里改成跟着**运行时版本**自动复位：
     * 换运行时等于换了环境，旧失败的原因（网络、pnpm 拦构建脚本、dsh 版本不兼容、
     * profile 被重建）多半已经不成立。
     *
     * 配额 [SEED_MAX_PASSES] 防止「根因没修好 → 每次开机都重跑一遍 pnpm」：
     * 只有真的重试过才计数，用尽后要等运行时版本变化才重新获得机会。
     */
    private fun applySeedEnvRetry(
        p: android.content.SharedPreferences,
        attempted: MutableSet<String>,
        installed: Set<String>,
        shadowed: Set<String>,
    ) {
        val runtimeNow = p.getString(DshEnv.KEY_RUNTIME_VERSION, "").orEmpty()
        val lastRuntime = p.getString(DshEnv.KEY_SEED_RUNTIME, null)
        val passes = p.getInt(DshEnv.KEY_SEED_PASSES, 0)
        // 版本没变且配额已用尽：不再重试（否则每次冷启动都要多等一轮 pnpm）
        if (lastRuntime == runtimeNow && passes >= SEED_MAX_PASSES) return
        // 同 applySeedRepair：上游已内置的不算「没生效」，不重试
        val retry = attempted.filter { it in SEED_PLUGINS && it !in installed && it !in shadowed }
        if (retry.isEmpty()) {
            // 没有要补的：只记下「这个版本已经检查过」，不消耗配额
            if (lastRuntime != runtimeNow) {
                p.edit().putString(DshEnv.KEY_SEED_RUNTIME, runtimeNow).apply()
            }
            return
        }
        attempted.removeAll(retry.toSet())
        persistSeeded(attempted)
        logInfo(CoreR.string.dsh_log_seed_retry_missing, joinForLog(retry))
        p.edit()
            .putString(DshEnv.KEY_SEED_RUNTIME, runtimeNow)
            .putInt(DshEnv.KEY_SEED_PASSES, if (lastRuntime == runtimeNow) passes + 1 else 1)
            .apply()
    }

    /**
     * 启动并等待就绪；插件树冲突可自动修复时先卸载冲突包再重试一次，然后才是
     * proroot/chroot → proot 回退（只重试一次：proot 也起不来就是真错了，再试无意义）。
     */
    internal suspend fun startAndAwait() {
        prorootFellBack = false
        startServer()
        awaitReady()
        // 上游内置的 entry id 与预装三方包重名（0.1.5 的 file-upload）会让整棵插件树
        // 起不来。自动卸载冲突的预装包后重试一次 —— 不修的话用户只能看到一行
        // duplicate loader entry id 的 node 堆栈，完全不知道下一步该干什么。
        // 两个自愈各查各的：入口 id 撞车与「声明残留」是两种不同的坏状态，
        // 同时存在时要一次都修掉，否则用户要重启两轮。
        var repaired = false
        if (repairDuplicateLoaderEntry()) repaired = true
        if (repairUnresolvableBundles()) repaired = true
        if (_state.value.phase == DshPhase.ERROR && repaired) {
            logInfo(CoreR.string.dsh_log_dup_entry_retry)
            stopServer()
            delay(500)
            startServer()
            awaitReady()
        }
        if (!prorootFellBack) return
        prorootFellBack = false
        logInfo(CoreR.string.dsh_log_retry_with_proot)
        stopServer()
        delay(500)
        startServer()
        awaitReady()
    }

    /**
     * 预装清单的只读视图，给 UI 的勾选对话框用。
     *
     * **不能叫 `SEED_PLUGINS`**：那会在 object 内部遮蔽同名的顶层常量，让本文件里
     * 十几处 `SEED_PLUGINS` 静默改为解析到这个属性 —— 功能上还成立（getter 指向顶层），
     * 但多一层间接、且一旦有人把 getter 写错就是无限递归。换个名字，两件事分开。
     */
    val seedPluginList: List<String> get() = me.bmax.apatch.dsh.SEED_PLUGINS

    /** 用户在确认框里取消勾选的包名。 */
    fun seedSkippedPackages(): Set<String> =
        prefs().getString(DshEnv.KEY_SEED_PLUGINS_SKIPPED, null)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

    /**
     * 记录用户跳过了哪些预装包（`null` = 还没问过）。
     *
     * 只写一次：`asked` 标记保证勾选框不会每次冷启动都弹一遍 —— 用户点过取消之后
     * 再反复问，比不问更烦。后续想装可以在插件商店手动装。
     */
    fun setSeedSkipped(pkgs: Set<String>) {
        prefs().edit()
            .putString(DshEnv.KEY_SEED_PLUGINS_SKIPPED, pkgs.joinToString(","))
            .putBoolean(DshEnv.KEY_SEED_PLUGINS_ASKED, true)
            .apply()
    }

    /** 是否还没问过用户要预装哪些（首装/老用户升级都算"没问过"）。 */
    fun seedAskPending(): Boolean = !prefs().getBoolean(DshEnv.KEY_SEED_PLUGINS_ASKED, false)

    /** 预装流程管得着的包：在装的 + 退役的（退役包也要能卸、能清声明）。 */
    private fun managedSeedPackages(): Set<String> =
        (SEED_PLUGINS + RETIRED_SEED_PLUGINS.keys).toSet()

    /**
     * 这个运行时是否已经自带退役包提供的能力。
     *
     * 运行时版本形如 `0.1.5-rc.1-ubuntunoble-r4`，前缀就是它捆绑的 dsh 版本。
     * 解析不出来一律当「不支持」：宁可让退役包多留一会儿，也不要在老运行时上
     * 把用户还能用的能力卸掉。
     */
    private fun runtimeSupportsRetiredSeed(runtimeVersion: String): Boolean {
        val core = runtimeVersion.trim().removePrefix("v").substringBefore('-')
        if (core.isEmpty() || core.substringBefore('.').toIntOrNull() == null) return false
        return compareVersions(core, RETIRE_MIN_DSH_VERSION) >= 0
    }

    /**
     * 尝试自愈「profile 声明了 bundles，但包已经不在」导致的服务起不来。
     *
     * 这种残留 `dsh plugin` 自己修不了（reconcile 要求这个包**当时**还在 dependencies 里），
     * 所以由 App 摘掉声明 —— 与 duplicate entry id 一样，用户从日志里看不出下一步该干什么，
     * 只会看到「服务进程已退出」。只动预装清单里的包；如果是用户自装的插件缺了包，
     * 就只说清是哪个、让他去插件页重装，绝不擅自改用户的东西。
     */
    private suspend fun repairUnresolvableBundles(): Boolean {
        val tail = runCatching {
            LogStore.named(DshEnv.serverLog(appContext)).tail(400)
        }.getOrDefault("")
        val names = PROFILE_BUNDLE_RE.findAll(tail)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (names.isEmpty()) return false
        val seedNames = names.filter { it in SEED_PLUGINS }.toSet()
        if (seedNames.isEmpty()) {
            logWarn(CoreR.string.dsh_log_bundle_unresolvable_user, joinForLog(names))
            return false
        }
        val pruned = runCatching { DshPluginRepo.pruneUnresolvableBundles(seedNames) }
            .getOrElse { emptySet() }
        if (pruned.isEmpty()) return false
        logWarn(CoreR.string.dsh_log_bundle_repair, joinForLog(pruned))
        return true
    }

    /**
     * 尝试自愈「上游内置的 entry id 与预装三方包重名」导致的插件树加载失败。
     *
     * 判据是启动日志里的 `duplicate loader entry id: X`。只卸载**预装清单里**声明了
     * 该 id 的包（核心 `@deepseek-ai/` 作用域的包与用户自装的包绝不自动动 —— 冲突时保留
     * 上游内置，因为旧 App 没有它对应的适配逻辑）。返回是否执行了卸载，调用方据此
     * 决定是否重试一次启动。已修复过的不重复修（同一轮启动只重试一次）。
     */
    private suspend fun repairDuplicateLoaderEntry(): Boolean {
        val tail = runCatching {
            LogStore.named(DshEnv.serverLog(appContext)).tail(400)
        }.getOrDefault("")
        val id = DUP_ENTRY_RE.find(tail)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: return false
        val entries = runCatching { DshPluginRepo.pluginEntries() }.getOrElse { emptyMap() }
        val managed = managedSeedPackages()
        val culprits = entries.entries
            .filter { (_, ids) -> id in ids }
            .map { it.key }
            .filter { it in managed }
            // 探测不到 entry id 时的兜底：这条 id 属于某个退役包，就直接认它 ——
            // 上游已内置该能力，卸掉退役包是唯一正确的处置。
            .ifEmpty { RETIRED_SEED_PLUGINS.filter { (_, retiredId) -> retiredId == id }.keys.toList() }
        if (culprits.isEmpty()) return false
        for (pkg in culprits) {
            logWarn(CoreR.string.dsh_log_dup_entry_repair, pkg, id)
            DshPluginRepo.uninstall(pkg, onLine = { line -> appendLog(line) })
        }
        // 必须落盘：映射表（[RETIRED_SEED_PLUGINS]）没覆盖的冲突只有这里才知道，不记下来的话
        // 下次冷启动会把它们当「缺的预装包」重新装上，再失败一次 —— 也就是刚才那个循环。
        rememberShadowed(culprits)
        return true
    }

    /**
     * 诊断模式：只在用户显式开启时才跑那些**耗时**的排查工具。
     *
     * ## 为什么要有这个开关
     *
     * 有些函数是排查某个具体故障时加的（会话目录深扫要起一个 node 进程遍历整棵树、
     * Agent Teams 诊断要往容器里打一段代码）。问题查清之后它们的结论已经固化，
     * 但代码留着有用 —— 下次同类问题再出现时不必重写。
     *
     * 折中是：默认不跑（不拖慢每次启动），需要时在设置里打开。
     *
     * ## 为什么不用 BuildConfig.DEBUG
     *
     * 出问题的是**用户的发布版**，而那台设备上装不了 debug 包（签名不同、要卸载重装，
     * 而卸载会丢容器数据）。所以必须让发布版也能开。
     */
    private fun diagnosticMode(): Boolean =
        runCatching { prefs().getBoolean(DshEnv.KEY_DIAGNOSTIC_MODE, false) }.getOrDefault(false)

    /** 下载/解压/准备/启动中：不接受新的引导请求。 */
    private fun busy(): Boolean = _state.value.phase.let {
        // PREPARING 必须算进来：准备阶段是同步跑补丁，这段时间再进来一次引导
        // 会在锁上排队，用户看到的是"点了没反应 → 再点 → 更慢"。
        it == DshPhase.DOWNLOADING || it == DshPhase.EXTRACTING ||
            it == DshPhase.PREPARING || it == DshPhase.STARTING
    }

    /**
     * 启动前探测端口占用。
     *
     * 本服务自己的进程在跑（`serverProcess?.isAlive`）不算冲突 —— 那是正常监听，
     * `startServer` 自会识别并跳过。只在「我们没在跑、端口却连得上」时置 [DshState.portConflict]
     * 并中止本轮启动，等 [resolvePortConflict] 决定。
     *
     * @return true 表示已置冲突标记、应当中止本轮启动
     */
    internal fun checkPortConflict(): Boolean {
        if (serverProcess?.isAlive == true) return false
        if (!isPortInUse(port())) return false
        _state.update { it.copy(portConflict = true) }
        logWarn(CoreR.string.dsh_log_port_busy, port())
        return true
    }

    // ────────────────────────── 文件桥 + 运行时安装/更新 ──────────────────────────
    //
    // 实现已迁到 [DshRuntimeInstall]（Checkpoint 1 的职责拆解）。这里只留同名转发。

    fun refreshContainerCli() = DshRuntimeInstall.refreshContainerCli()

    fun restart() = DshRuntimeInstall.restart()

    fun updateRuntime(preserveData: Boolean = true, autoStart: Boolean = true) =
        DshRuntimeInstall.updateRuntime(preserveData, autoStart)

    fun reinstallRuntime(preserveData: Boolean = true, autoStart: Boolean = true) =
        DshRuntimeInstall.reinstallRuntime(preserveData, autoStart)

    fun switchRuntimeVersion(entry: RuntimeVersion, preserveData: Boolean = true) =
        DshRuntimeInstall.switchRuntimeVersion(entry, preserveData)

    fun autoCheckEnabled(ctx: Context): Boolean = DshRuntimeInstall.autoCheckEnabled(ctx)

    fun setAutoCheckEnabled(ctx: Context, on: Boolean) = DshRuntimeInstall.setAutoCheckEnabled(ctx, on)

    fun importRuntime(tarball: File, preserveData: Boolean = true) =
        DshRuntimeInstall.importRuntime(tarball, preserveData)

    override fun runtimeSource(ctx: Context): String = DshRuntimeInstall.runtimeSource(ctx)

    internal fun runtimeMetaUrl(): String = DshRuntimeInstall.runtimeMetaUrl()

    internal fun fetchMetaFrom(url: String): DshMeta? = DshRuntimeInstall.fetchMetaFrom(url)

    internal fun fetchMetaOfTag(tag: String, suffix: String): DshMeta? =
        DshRuntimeInstall.fetchMetaOfTag(tag, suffix)

    suspend fun checkRuntimeUpdate(): RuntimeCheckResult = DshRuntimeInstall.checkRuntimeUpdate()

    suspend fun listRuntimeVersions(): List<RuntimeVersion> = DshRuntimeInstall.listRuntimeVersions()

    fun allowProfileBuilds(packages: List<String>, onLine: (String) -> Unit = {}): List<String> =
        DshRuntimeInstall.allowProfileBuilds(packages, onLine)

    // 下面这些原本是本 object 的 private 函数，现在被 DshRuntimeInstall 调用，放宽为 internal。

    internal suspend fun startAndAwaitForInstall() = startAndAwait()

    internal fun checkPortConflictForInstall(): Boolean = checkPortConflict()

    internal suspend fun seedPluginsForInstall() = seedPlugins()

    internal fun nativeTroubleshootDocForInstall(): String = nativeTroubleshootDoc()

    internal fun pnpmImportMethodLineForInstall(): String = DshRuntimeInstall.pnpmImportMethodLine()

    // 引导流程内部还有调用点（setupResolvConf 等），转回新实现。
    private fun setupResolvConf() = DshRuntimeInstall.setupResolvConf()

    private fun ensureContainerDns() = DshRuntimeInstall.ensureContainerDns()

    private fun ensureContainerGroups() = DshRuntimeInstall.ensureContainerGroups()

    private fun ensureProfilePnpmSettings() = DshRuntimeInstall.ensureProfilePnpmSettings()

    private fun removeLegacyNpmrcImportLine() = DshRuntimeInstall.removeLegacyNpmrcImportLine()

    private fun pnpmImportMethodLine(): String = DshRuntimeInstall.pnpmImportMethodLine()

    private fun pnpmInstalled(): Boolean = DshRuntimeInstall.pnpmInstalled()

    private fun ensureFsBridgeCli() = DshRuntimeInstall.ensureFsBridgeCli()

    private fun startFsBridge() = DshRuntimeInstall.startFsBridge()

    // ────────────────────────── web 服务 ──────────────────────────

    /**
     * 等到 `dsh web` 真的在跑（或超时）。
     *
     * 自动化工作流的 `trigger` 节点用它：DSH 没起来时先拉起再继续，起不来就走 `false` 分支
     * （用户要的「未启动 DSH 时先阻塞启动 DSH，也可条件判断进入其它流程」）。
     *
     * 判据只看 phase == RUNNING：那是 [startServer] 与端口探测共同判定的结果，比「进程还活着」
     * 更接近事实 —— 进程活着但端口连不上的时候，工作流后面的 `dsh` 命令必然失败。
     */
    suspend fun awaitRunning(timeoutMs: Long): Boolean {
        if (!ready) return false
        if (_state.value.phase == DshPhase.RUNNING) return true
        return withTimeoutOrNull(timeoutMs) {
            state.first { it.phase == DshPhase.RUNNING }
            true
        } ?: false
    }

    /**
     * 启动 `dsh web`。
     *
     * `--expose-internals` 必带：dsh 的 profile-boot 会无条件创建 cordis-plugin-hmr，
     * 那个插件第一行就检查 loader.internal，缺这个标志会把整个启动带崩；
     * 而 NODE_OPTIONS 传不了它（node 明确拒绝），只能作为命令行参数。
     *
     * 默认端口 3080 不显式传 --port，避免 commander 的 'argument missing'。
     * 服务默认只绑 127.0.0.1；开启「局域网访问」后追加 --host 0.0.0.0。
     * 鉴权由 dsh 自己的登录页负责。
     */
    override fun startServer() {
        // 守卫只看真实进程，不看 phase。看 phase 会把上一步（例如安装完成）
        // 自己写下的 STARTING 误当成「已有人在启动」。
        if (serverProcess?.isAlive == true) return
        if (!DshEnv.isRuntimeInstalled(appContext)) {
            _state.update { it.copy(phase = DshPhase.NOT_READY, message = str(CoreR.string.dsh_msg_not_installed)) }
            return
        }
        // 最低 App 版本闸门：运行时要求比当前 App 更高的版本时，直接拦下并指路去
        // 更新应用，而不是 exec 一个注定起不来的 dsh —— 0.1.5 起上游内置的 entry id
        // 会与旧 App 预装的三方插件冲突，而旧 App 既没有预装跳过、也没有自愈逻辑。
        val minApp = _state.value.requiredAppVersion
        if (_state.value.appUpdateRequired && minApp != null) {
            val detail = str(CoreR.string.dsh_runtime_min_app_required, minApp)
            appendLog("! $detail")
            _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
            return
        }
        // 立刻把状态切到「准备中」：下面这段准备工作（补丁、CLI 刷新、属主修复）
        // 是同步跑的，真机要 1–5 秒。以前全部跑完才第一次设 phase，用户点完按钮
        // 界面毫无反应，看起来像卡死。
        _state.update {
            it.copy(phase = DshPhase.PREPARING, progress = 0f, message = str(CoreR.string.dsh_phase_preparing))
        }

        DshEnv.dshHome(appContext).mkdirs()
        DshEnv.tmpDir(appContext).mkdirs()
        DshEnv.serverLog(appContext).parentFile?.mkdirs()
        // 每次起服务都过一遍：既刷新宿主事实（用户可能在别处改了能力开关或系统通知
        // 权限），也自愈「patch 行在、插件文件没了」——那种状态下 dsh 会因为一个
        // entry 加载失败而整棵树起不来，而 restart() 不走 bootstrap，否则没人修。
        DshHostPrompt.ensureInstalled(appContext)
        clearLog()
        // dsh 每次启动都会生成新 token，不能沿用上一个进程的认证地址。
        _state.update { it.copy(webToken = null) }

        if (lanEnabled()) patchLanHost()
        // 存储补丁直接打 v3（rename 发布）。这里曾经先调 restoreAttachmentStore() 把
        // 补丁还原成上游原始实现、再打 v3 —— 那是 beta.220 的 A/B 对照实验（判断
        // 「md 失败 / 压缩包成功」是补丁引入还是上游本身如此），结论早已出来并固定走 v3，
        // 还原那一步纯属每次启动白读一遍所有相关文件。已删除。
        patchAttachmentStoreV3()
        // 同类问题在会话持久化上也存在：子会话文件是悬空链接 → Agent Teams 的 spawn_teammate 失败。
        // 先清理历史悬空项并体检，再把该处的 link 发布换成 rename 发布。
        // 会话目录深扫（node 进程遍历整棵 sessions 树）与 Agent Teams 诊断补丁
        // 都是**排查期**的工具：前者每次启动要起一个 node 进程扫盘，后者是往容器里
        // 打一段诊断代码。它们的结论早已固化（持久化补丁已稳定、teams 诊断已在位），
        // 留在热路径上只是每次启动多花几百毫秒。改成诊断模式才跑。
        if (diagnosticMode()) {
            sessionStoreDeepDiag()
        }
        patchSessionPersistence()
        if (diagnosticMode()) {
            patchAgentTeamDiag()
        }
        // 同一类问题还影响「新建文件」的原子写（dsh-fs-local 的 createIfAbsent 用 link）。
        patchFsLocalWrites()
        // 让外部浏览器（App 用 Intent 拉起的 Chrome）也能一次登进 dsh web：把会话 cookie 的
        // SameSite=Strict 放宽为 Lax（见 [patchBrowserCookieSameSite]）。每次启动前幂等重打。
        patchBrowserCookieSameSite()
        // git 的 CA 配置写在 /root/.gitconfig，更新运行时会把它清掉（PEM 在 /root/.dsh 下还在），
        // 之后 dsh 自身 reconcile / 启动自愈跑 `dsh plugin` 去 https 克隆 github: 插件就会撞
        // 「无 CA」（CAfile: none）。启动前无条件重设，见 DshPluginRepo.ensureGitCaAtStartup。
        DshPluginRepo.ensureGitCaAtStartup()
        // chroot 以真 root 写过容器数据的话，切回 proot/proroot 会直接 EACCES（issue #12）。
        // 放在真正起容器之前，且与运行时无关 —— 只有 root 能 chown。
        repairRootfsOwnership()

        val port = port()
        val lan = lanEnabled()
        val opts = buildString {
            if (port != DshEnv.DEFAULT_PORT) append(" --port $port")
            if (lan) append(" --host 0.0.0.0")
            // 不让 dsh 自己拉系统浏览器（我们用 Intent / WebView 打开 WebUI）。**这才是真开关**：
            // dsh web 用 npm 的 `open` 包（Linux 上走 xdg-open），根本不读 BROWSER 环境变量，
            // 所以下面那句 `export BROWSER=true` 从来没拦住过它 —— 只是容器里没有 xdg-open，
            // 尝试后静默失败，看着像被拦住了。`--no-open` 从 0.1.7 起就有（已核对 0.1.7-rc.2 与
            // 0.2.0-rc.2 的 dsh-web-app 选项表逐字一致），两个运行时都能无条件带。
            append(" --no-open")
        }
        val cmd = buildString {
            append("export DSH_HOME=/root/.dsh && ")
            // 兼容性保留：拦住可能读 BROWSER 的更老工具链（对 dsh web 本身无效，见上）。
            append("export BROWSER=true && ")
            append("mkdir -p ${DshEnv.WORKSPACE_GUEST} 2>/dev/null; ")
            append("cd ${DshEnv.WORKSPACE_GUEST} && ")
            append("if command -v dsh >/dev/null 2>&1 && test -f \"\$(command -v dsh)\"; then ")
            // dsh 是 wrapper，先 readlink 出真正的 bin.js 再交给 node（才能带 --expose-internals）
            append("DSH_REAL=\$(readlink -f \"\$(command -v dsh)\" 2>/dev/null || command -v dsh); ")
            append("exec node --expose-internals \"\$DSH_REAL\" web" + opts + " 2>&1; ")
            // 这行在容器里由 shell 输出，会被 forwardOutput 原样转进日志。文案取自
            // 资源（跟随应用语言），单引号要转义成 shell 能接受的形式。
            append("else echo '[DSH-Folk] " + shellSingleQuoted(str(CoreR.string.dsh_log_dsh_missing_in_container)) + "'; exit 1; fi")
        }

        logInfo(CoreR.string.dsh_log_runtime_mode, runtime().displayName())
        // 设备信息（裁剪版）：只留厂商 + 型号 + 系统版本。完整字段在报障包的 basic.txt 里，
        // 但那份得解压、翻文件才看得到；而这份日志是随手复制/导出转发的（设置 → 常规 →
        // 发送日志，或首页那张启动日志卡），开头就得能答出「这是哪台设备」。
        logInfo(
            CoreR.string.dsh_log_device,
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
        )
        // 架构写进日志：x86_64 + arm 转译设备上「装了哪个包 / 用了哪份 rootfs」是首要排障线索
        logInfo(
            CoreR.string.dsh_log_arch,
            DshSource.runtimeArch(),
            android.os.Build.SUPPORTED_ABIS.joinToString("/"),
        )
        logInfo(CoreR.string.dsh_log_hardlink, DshLogging.hardlinkLogLine())
        logInfo(CoreR.string.dsh_log_pnpm_import, pnpmImportMethodLine())
        logInfo(CoreR.string.dsh_log_starting_web, port)
        if (lan) {
            val ip = lanIp()
            if (ip != null) {
                logInfo(CoreR.string.dsh_log_lan_on, ip, port)
            } else {
                logInfo(CoreR.string.dsh_log_lan_on_no_ip)
            }
        } else {
            logInfo(CoreR.string.dsh_log_lan_off)
        }

        // 扩展拦截点：**启动容器进程之前的最后一道口子**。
        //
        // 放在这里而不是函数开头：此时补丁、CLI 刷新、音量/局域网判定都已经做完，
        // 扩展看到的是「即将真的被执行的那条命令」。around 返回 false 可以让扩展拒绝启动
        // （例如某扩展判定这台设备上的已知问题会触发崩溃）。
        // cmd 可被改写：扩展能在这里调整启动参数而不必去碰 RUNTIME_BUILD_ARGV
        // （后者改的是容器 argv，这里是容器内 bash 命令，层次不同）。
        var startCmd = cmd
        if (ExtensionHookRegistry.has(ExtensionHookRegistry.Point.RUNTIME_BEFORE_START)) {
            val payload = mutableMapOf<String, Any?>("cmd" to cmd, "port" to port())
            if (!ExtensionHookRegistry.runAround(ExtensionHookRegistry.Point.RUNTIME_BEFORE_START, payload)) {
                val detail = "启动被扩展拦下"
                appendLog("! $detail")
                _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
                return
            }
            ExtensionHookRegistry.runBefore(ExtensionHookRegistry.Point.RUNTIME_BEFORE_START, payload)
            (payload["cmd"] as? String)?.takeIf { it.isNotBlank() }?.let { startCmd = it }
        }

        serverProcess = try {
            execRootfs(startCmd)
        } catch (e: Exception) {
            // 不能把上一轮已死的进程留在字段里：[awaitReady] 会把它读成
            // 「进程已退出」，盖掉真正的启动失败原因。
            serverProcess = null
            val detail = str(CoreR.string.dsh_err_start_failed, e.message ?: e.javaClass.simpleName)
            appendLog("! $detail")
            // 不再限定 proroot：noteProrootFailure 自己认得 chroot（切回 proot 并返回 true），
            // 而调用点那句 `runtimeId() == "proroot"` 会让 chroot 的回退分支永远不可达
            // —— issue #12 里 chroot 起不来时用户就卡在「每次都失败」。
            if (noteProrootFailure(detail)) {
                logInfo(CoreR.string.dsh_log_switched_to_proot)
            }
            _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
            return
        }
        forwardOutput(serverProcess)
        // 每次启动都对一遍 CLI 内容：升级 App 而沿用旧 rootfs 时，容器里的 dsh-fs / dsh-native
        // 必须跟着更新，否则新加的子命令只存在于提示词里（见 ensureFsBridgeCli 的注释）。
        ensureFsBridgeCli()
        startFsBridge()
        // 附件存储体检：上传失败最常见的两种原因是「磁盘满」与「DSH_HOME 属主不对」，
        // 而这两种在运行日志里此前一个字都没有。放在后台跑，不拖慢启动。
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { attachmentDiag() }
        startedAt = System.currentTimeMillis()
        _state.update { it.copy(phase = DshPhase.STARTING, port = port, message = str(CoreR.string.dsh_msg_starting)) }
    }

    /** 逐行转发容器输出到日志（node 重定向到文件是块缓冲，不逐行读日志面板会空白）。 */
    /**
     * 附件存储体检：可用空间 / DSH_HOME 属主 / 能否真的写进去。
     *
     * 上传失败在 dsh 网页端只有一句「上传失败」，而最常见的原因就是磁盘满与属主不对，
     * 却要用户开终端敲命令才看得到。这里每次启动顺手记三行（[attach] 前缀），
     * 任何一份 bugreport 都能直接回答「是不是空间/权限」。
     */
    private fun attachmentDiag() {
        runCatching {
            val out = execRootfsForOutput(
                "df -h /root 2>/dev/null | tail -n 1; " +
                    "ls -ld /root/.dsh /root/.dsh/attachments 2>&1; " +
                    // 直接探两个会在 Android 上被拒的系统调用：硬链接、目录 fsync。
                    // 上传失败时这段结论能立刻区分「是 link 还是目录 fsync」（补丁覆盖不了的就靠它）。
                    "node -e 'const fs=require(\"fs\");const d=\"/root/.dsh/attachments/v1/tmp\";" +
                    "fs.mkdirSync(d,{recursive:true,mode:448});const a=d+\"/p1\";fs.writeFileSync(a,\"x\");" +
                    "try{fs.linkSync(a,d+\"/p2\");const st=fs.lstatSync(d+\"/p2\");console.log(\"LINK_OK symlink=\"+st.isSymbolicLink());" +
                    "fs.unlinkSync(a);" +
                    "try{fs.chmodSync(d+\"/p2\",256);console.log(\"CHMOD_AFTER_UNLINK_OK\")}catch(e){console.log(\"CHMOD_AFTER_UNLINK_FAIL \"+e.code)}" +
                    "fs.unlinkSync(d+\"/p2\")}catch(e){console.log(\"LINK_FAIL \"+e.code)}" +
                    "try{fs.unlinkSync(a)}catch(e){}" +
                    "try{const fd=fs.openSync(d,\"r\");fs.fsyncSync(fd);fs.closeSync(fd);console.log(\"FSYNC_DIR_OK\")}catch(e){console.log(\"FSYNC_DIR_FAIL \"+e.code)}' 2>&1; " +
                    // 不用 shell 变量：Kotlin 字符串里的 $ 要转义，写错就变成模板插值（真机 compile 才报）。
                    "if dd if=/dev/zero of=/root/.dsh/attachments/.dshfolk-wtest bs=1k count=8 2>/dev/null; " +
                    "then echo WRITE_OK; rm -f /root/.dsh/attachments/.dshfolk-wtest; else echo WRITE_FAIL; fi",
                20_000L,
            )
            out.lineSequence().filter { it.isNotBlank() }.forEach { appendLog("[attach] " + it.trim()) }
        }
    }

    private fun forwardOutput(proc: Process?) {
        if (proc == null) return
        val log = LogStore.named(DshEnv.serverLog(appContext))
        scope.launch {
            val reader = proc.inputStream.bufferedReader()
            try {
                for (line in reader.lineSequence()) {
                    log.append(line)
                    // dsh 打印带 token 的 URL 后，把 token 捞出来写进 state，
                    // 这样 webUrl 就能带 ?token=...，WebView 才不会撞认证墙。
                    if (_state.value.webToken == null) {
                        val m = DSH_WEB_TOKEN_RE.find(line)
                        if (m != null) {
                            _state.update { s -> s.copy(webToken = m.groupValues[1]) }
                        }
                    }
                }
            } catch (_: Exception) {
                // 进程被销毁时读流中断，属预期
            } finally {
                log.flushForExit()
                runCatching { reader.close() }
            }
        }
    }



    /**
     * 会话存储体检：扫 `$DSH_HOME/sessions` 下的会话文件，识别并清理**悬空符号链接**。
     *
     * 由来（Agent Teams spawn 失败）：子会话目录里有 `session.v4.jsonl.zstd`，`readdir` 列得到，
     * 但 `open` 报 ENOENT —— 这正是 proroot `--link2symlink` 留下的悬空链接：
     * 发布时 link 建的是「锚点 + 符号链接组」，锚点（staging 名）被删，目标名就失效。
     * 悬空项本身没有任何数据（目标已不存在），删掉是安全的；下次该会话会被重新创建。
     *
     * 只删**确实打不开**的链接，正常文件与正常链接一律不动。
     */
    private fun sessionStoreDiag() {
        runCatching {
            val out = execRootfsForOutput("node -e 'const fs=require(\"fs\"),p=require(\"path\");const root=\"/root/.dsh/sessions\";let names=[];try{names=fs.readdirSync(root)}catch(e){console.log(\"sessions-dir-missing\");process.exit(0)}let dirs=0,files=0,sym=0,dang=0;for(const d of names){const dp=p.join(root,d);let st;try{st=fs.lstatSync(dp)}catch(e){continue}if(!st.isDirectory())continue;dirs++;let inner=[];try{inner=fs.readdirSync(dp)}catch(e){continue}for(const f of inner){if(f.indexOf(\"session.\")!==0||f.indexOf(\".jsonl\")<0)continue;const fp=p.join(dp,f);let ls;try{ls=fs.lstatSync(fp)}catch(e){continue}if(!ls.isSymbolicLink()){files++;continue}sym++;let ok=true;try{fs.statSync(fp)}catch(e){ok=false}if(!ok){dang++;try{fs.unlinkSync(fp)}catch(e){}}}}console.log(\"dirs=\"+dirs+\" files=\"+files+\" symlinks=\"+sym+\" danglingRemoved=\"+dang)'", 30_000L)
            out.lineSequence().filter { it.isNotBlank() }.forEach { appendLog("[session] " + it.trim()) }
        }
    }


    /**
     * 会话存储**深层**体检（sessions/<cwd>/<sessionId>/file 是两层）。
     *
     * 上一版只扫了一层，把 cwd 目录当成会话目录数，因此报出 `files=0`（实际文件在第二层）。
     * 这里递归两层，统计：会话目录数、正常日志文件、session.lock、符号链接、以及**确实打不开**的悬空链接
     * （悬空 = 目标已不存在；无数据，删除安全）。日志前缀 `[session]`。
     */
    private fun sessionStoreDeepDiag() {
        runCatching {
            val out = execRootfsForOutput("node -e 'const fs=require(\"fs\"),p=require(\"path\");const root=\"/root/.dsh/sessions\";let names=[];try{names=fs.readdirSync(root)}catch(e){console.log(\"sessions-dir-missing\");process.exit(0)}let dirs=0,files=0,locks=0,sym=0,dang=0;function scan(dp,depth){let inner=[];try{inner=fs.readdirSync(dp)}catch(e){return}for(const f of inner){const fp=p.join(dp,f);let ls;try{ls=fs.lstatSync(fp)}catch(e){continue}if(ls.isDirectory()){if(depth<2)scan(fp,depth+1);continue}if(f===\"session.lock\"){locks++;continue}if(f.indexOf(\"session.\")!==0||f.indexOf(\".jsonl\")<0)continue;if(!ls.isSymbolicLink()){files++;continue}sym++;let ok=true;try{fs.statSync(fp)}catch(e){ok=false}if(!ok){dang++;try{fs.unlinkSync(fp)}catch(e){}}}}for(const d of names){const dp=p.join(root,d);let st;try{st=fs.lstatSync(dp)}catch(e){continue}if(st.isDirectory()){dirs++;scan(dp,1)}}console.log(\"dirs=\"+dirs+\" files=\"+files+\" locks=\"+locks+\" symlinks=\"+sym+\" danglingRemoved=\"+dang)'", 40_000L)
            out.lineSequence().filter { it.isNotBlank() }.forEach { appendLog("[session] " + it.trim()) }
        }
    }

    /**
     * Agent Teams 的失败诊断：把 `spawn_teammate` 的**完整错误链**打进日志。
     *
     * 现象：工具只回一句 `teammate creation and durable failure recording both failed`，
     * 而真正的原因（子会话打开失败 / 锁失败 / 目录项问题）藏在 AggregateError 的成员里，
     * 既不在 dsh.log，也不在工具返回值里 —— 于是只能猜。
     *
     * 这里在抛出 AggregateError 之前，把两个成员各自打印：`code` + `message` + `stack` 前三行。
     * 前缀 `[DSH-Folk team]`，只加日志，不改行为。
     */
    private fun patchAgentTeamDiag() {
        val target = File(
            DshEnv.rootfs(appContext),
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-experimental-agent-team/lib/index.js",
        )
        if (!target.isFile) {
            appendLog("[team] 跳过诊断补丁：找不到 dsh-experimental-agent-team/lib/index.js")
            return
        }
        runCatching {
            val src = target.readText(StandardCharsets.UTF_8)
            if (src.contains("[DSH-Folk team]")) {
                appendLog("[team] 诊断补丁已在位")
                return
            }
            val hits = src.split("\t\t\t} catch (recordError) {\n\t\t\t\tthrow new AggregateError([error, recordError], \"teammate creation and durable failure recording both failed\");\n\t\t\t}").size - 1
            val out = src.replace("\t\t\t} catch (recordError) {\n\t\t\t\tthrow new AggregateError([error, recordError], \"teammate creation and durable failure recording both failed\");\n\t\t\t}", "\t\t\t} catch (recordError) {\n\t\t\t\tconsole.error(\"[DSH-Folk team] spawn failed: \" + [error, recordError].map((e) => String((e && e.code) || \"\") + \" \" + String((e && e.message) || e) + \" | \" + String((e && e.stack) || \"\").split(\"\\n\").slice(0, 3).join(\" / \")).join(\" ;; \"));\n\t\t\t\tthrow new AggregateError([error, recordError], \"teammate creation and durable failure recording both failed\");\n\t\t\t}")
            appendLog("[team] 诊断补丁：anchor=" + hits)
            if (hits != 1) {
                appendLog("[team] 诊断补丁锚点不唯一，未写回")
                return
            }
            val tmp = File(target.parentFile, target.name + ".dshfolk.tmp")
            tmp.writeText(out, StandardCharsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            appendLog("[team] 诊断补丁已应用（失败时打印完整错误链）")
        }.onFailure { appendLog("[team] 诊断补丁失败：" + (it.message ?: it.javaClass.simpleName)) }
    }


    /**
     * `dsh-fs-local` 的原子写补丁：`writeFileAtomic` 的 createIfAbsent 分支用
     * `linkFile(tempPath, absolutePath)` 发布新文件（硬链接），Android 上 link 不可用
     * （SELinux 拒 app_data_file 的 link，或 l2s 造成悬空链接），凡是「新建文件」的写入
     * 都会失败 —— 表现为各种写配置/写状态的操作报 ENOENT/EACCES。
     *
     * 改用 `copyFile(temp, dest, 1)`：1 = COPYFILE_EXCL，**目标存在就失败**，
     * 与原来 link 的「排他创建」语义一致（不是静默覆盖）。
     */
    private fun patchFsLocalWrites() {
        val target = File(
            DshEnv.rootfs(appContext),
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
        )
        if (!target.isFile) {
            appendLog("[fs] 跳过写入补丁：找不到 dsh-fs-local/lib/index.js")
            return
        }
        runCatching {
            val src = target.readText(StandardCharsets.UTF_8)
            if (src.contains("COPYFILE_EXCL 语义")) {
                appendLog("[fs] 写入补丁已在位")
                return
            }
            var out = src.replace(
                "import { chmod, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from \"node:fs/promises\";",
                "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from \"node:fs/promises\";",
            )
            val importOk = out != src
            val hits = out.split("\t\t\tawait linkFile(tempPath, absolutePath);").size - 1
            out = out.replace(
                "\t\t\tawait linkFile(tempPath, absolutePath);",
                "\t\t\t// DSH-Folk: Android 上 link() 不可用；copyFile(...,1) 保持排他创建（COPYFILE_EXCL 语义）\n\t\t\tawait copyFile(tempPath, absolutePath, 1);",
            )
            appendLog("[fs] 写入补丁：import=" + importOk + " createIfAbsent=" + hits)
            if (!importOk || hits != 1) {
                appendLog("[fs] 写入补丁不完整，未写回（运行时版本可能变了）")
                return
            }
            val tmp = File(target.parentFile, target.name + ".dshfolk.tmp")
            tmp.writeText(out, StandardCharsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            appendLog("[fs] 写入补丁已应用（createIfAbsent: link → copyFile EXCL）")
        }.onFailure { appendLog("[fs] 写入补丁失败：" + (it.message ?: it.javaClass.simpleName)) }
    }

    /**
     * 会话持久化补丁：把「link 发布」换成 **rename 发布**。
     *
     * `dsh-session-persistence-jsonl` 的 `publishCurrentExclusive` 用 `link(staged, currentPath)`
     * 把迁移产物发布成正式会话文件；在 proroot 的 l2s 下这是「锚点 + 符号链接组」，
     * 锚点被清理后目标变悬空，于是「readdir 有、open ENOENT」，Agent Teams 的
     * `spawn_teammate` 正好卡在这一步（`readPersistedSession` 读不到子会话）。
     *
     * 改法：同目录 `rename`（原子、只留一个真名字）；目标若已存在且是**悬空链接**，
     * 先删掉再 rename；是正常文件则沿用原语义「视为冲突」（返回 false，交给调用方校验）。
     */
    private fun patchSessionPersistence() {
        val target = File(
            DshEnv.rootfs(appContext),
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        )
        if (!target.isFile) {
            appendLog("[session] 跳过持久化补丁：找不到 dsh-session-persistence-jsonl/lib/index.js")
            return
        }
        runCatching {
            val src = target.readText(StandardCharsets.UTF_8)
            // 用「效果」判断而不是 marker：老版本可能只打过 migration 那半，
            // 而 create(materialize) 那半是后加的；单看 marker 会永远跳过新 hunk（beta.226 踩过）。
            if (src.contains("await rename(tmp, finalPath);")) {
                appendLog("[session] 持久化补丁已在位（create + migration）")
                return
            }
            var out = src.replace("import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";", "import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate } from \"node:fs/promises\";")
            val importOk = out != src
            val before = out.split("\ttry {\n\t\tawait internals.fs.link(staged, currentPath);\n\t} catch (error) {\n\t\t/* v8 ignore else -- a non-collision filesystem error propagates unchanged. */\n\t\tif (isEEXIST(error)) return false;\n\t\t/* v8 ignore next -- the filesystem error is already complete. */\n\t\tthrow error;\n\t}").size - 1
            out = out.replace("\ttry {\n\t\tawait internals.fs.link(staged, currentPath);\n\t} catch (error) {\n\t\t/* v8 ignore else -- a non-collision filesystem error propagates unchanged. */\n\t\tif (isEEXIST(error)) return false;\n\t\t/* v8 ignore next -- the filesystem error is already complete. */\n\t\tthrow error;\n\t}", "\t// DSH-Folk: Android/proot 下 link() 是「锚点 + 符号链接组」，删锚点后目标变悬空\n\t//（表现为 readdir 列得出、open 报 ENOENT）。改用同目录 rename 发布，并先清掉悬空目标。\n\ttry {\n\t\tconst existing = await internals.fs.lstat(currentPath);\n\t\tif (!existing.isSymbolicLink()) return false;\n\t\tawait internals.fs.rm(currentPath);\n\t} catch (error) {\n\t\tif (!(error !== void 0 && error.code === \"ENOENT\")) throw error;\n\t}\n\tawait rename(staged, currentPath);")
            val beforeMaterialize = out.split("\t\t\tawait link(tmp, finalPath);").size - 1
            out = out.replace("\t\t\tawait link(tmp, finalPath);", "\t\t\tawait rename(tmp, finalPath);")
            appendLog(
                "[session] 持久化补丁：import=" + importOk + " link-publish=" + before +
                    " materialize=" + beforeMaterialize,
            )
            val publishOk = before == 1 || out.contains("await rename(staged, currentPath);")
            val materializeOk = beforeMaterialize == 1 || out.contains("await rename(tmp, finalPath);")
            val importOkNow = importOk || out.contains("realpath, rename, rm")
            if (!importOkNow || !publishOk || !materializeOk) {
                appendLog("[session] 持久化补丁不完整，未写回（运行时版本可能变了）")
                return
            }
            val tmp = File(target.parentFile, target.name + ".dshfolk.tmp")
            tmp.writeText(out, StandardCharsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            appendLog("[session] 持久化补丁已应用（两处 link 发布均改为 rename：create + migration）")
        }.onFailure { appendLog("[session] 持久化补丁失败：" + (it.message ?: it.javaClass.simpleName)) }
    }

    /**
     * 存储补丁 v3：把「硬链接发布」换成 **rename 发布**。
     *
     * 真机证据（beta.221 = 上游原始实现，Redmi/Android16/proroot）：
     *  - 启动体检复现了根因：`LINK_OK symlink=false` 紧接着 `CHMOD_AFTER_UNLINK_FAIL ENOENT`
     *    —— link 看似成功，但删掉源名（`unlink(staged.path)`）之后，目标名也随之消失；
     *  - 于是上游固定流程 link → unlink(staging) → chmod(target) 必然 ENOENT，
     *    整条链最后只剩一句 ATTACHMENT_WRITE_FAILED；
     *  - 这解释了「以前传过的文件能传、新文件不能」：目标已存在时 link 走 EEXIST 分支、
     *    不会新建那个会被 unlink 带走的名字，所以只有**旧的、已缓存的对象**能通过。
     *
     * 为什么 proroot 下会这样：它带 `--link2symlink`，把硬链接模拟成「锚点 + 符号链接组」，
     * 锚点（staging 名）被删掉，组里的名字就跟着失效 —— 不是真正的独立目录项。
     *
     * 所以不再用 link：同一目录内 `rename` 是原子的、只保留一个名字，不需要硬链接；
     * 别名（files/<sha>/<name>）改用 `copyFile`（源对象始终在）。四处替换都做计数校验，
     * 任何一处不是恰好 1 次就**不写回**。
     */
    private fun patchAttachmentStoreV3() {
        val target = File(
            DshEnv.rootfs(appContext),
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js",
        )
        if (!target.isFile) {
            appendLog("[attach] 跳过存储补丁：找不到 dsh-attachment-local/lib/index.js")
            return
        }
        runCatching {
            val src = target.readText(StandardCharsets.UTF_8)
            if (src.contains("DSH-Folk: rename 已消费")) {
                appendLog("[attach] 存储补丁 v3 已在位")
                return
            }
            var out = src.replace("import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";", "import { chmod, copyFile, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";")
            val importOk = out != src
            fun count(s: String) = out.split(s).size - 1
            val c1 = count("await link(staged.path, target);")
            out = out.replace("await link(staged.path, target);", "await rename(staged.path, target);")
            val c2 = count("await unlink(staged.path);")
            out = out.replace(
                "await unlink(staged.path);",
                "/* DSH-Folk: rename 已消费临时名，不能再 unlink（否则目标会随之消失） */",
            )
            val c3 = count("await link(source, target);")
            // 别名目标可能是历史失败留下的**悬空链接**（l2s 的锚点被删），copyFile 会穿过它再失败；
            // 先删掉那个名字（不存在就忽略），再复制真实字节。
            out = out.replace(
                "await link(source, target);",
                "try { await unlink(target); } catch (e) { /* DSH-Folk: 历史悬空别名，忽略 */ }\n\t\tawait copyFile(source, target);",
            )
            appendLog(
                "[attach] 存储补丁 v3：import=" + importOk + " link→rename=" + c1 +
                    " drop-unlink=" + c2 + " alias→copy=" + c3,
            )
            if (!importOk || c1 != 1 || c2 != 1 || c3 != 1) {
                appendLog("[attach] 存储补丁 v3 不完整，未写回（运行时版本可能变了）")
                return
            }
            val tmp = File(target.parentFile, target.name + ".dshfolk.tmp")
            tmp.writeText(out, StandardCharsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            appendLog("[attach] 存储补丁 v3 已应用（link+unlink 改为 rename；别名改为 copy）")
        }.onFailure { appendLog("[attach] 存储补丁 v3 失败：" + (it.message ?: it.javaClass.simpleName)) }
    }

    /**
     * 给容器里的 `dsh-attachment-local` 打一次 Android 兼容补丁（v2，历史实验，当前未调用）。
     *
     * 真机实测（issue 反馈，Redmi / Android 16 / proroot）上传附件失败，响应体是
     * `{code:"session/attachment-invalid", reason:"ATTACHMENT_WRITE_FAILED"}` —— 落盘那一步挂了。
     * 上游的发布路径是「写临时文件 → **hard link** 到内容寻址的目标 → 逐级 fsync 目录」：
     *
     *  - 硬链接：本机探测就是 `AccessDeniedException`（App 私有目录建不了硬链接，见启动日志第一行）；
     *  - 目录 fsync：部分 Android 内核/文件系统对目录句柄 `sync()` 返回 EINVAL/EPERM。
     *
     * 第一版补丁（beta.219）只做了「link 失败回退 copy + 目录 fsync 容错」，**没有修好**：
     * 那次真机探测显示容器里 `LINK_OK` / `FSYNC_DIR_OK`，说明 link 并不报错 —— 但它可能是
     * proroot 的 `--link2symlink` 悄悄建了一个**符号链接**；紧接着 `unlink(临时文件)` 就让
     * 那个「目标」变成悬空链接，后面 `chmod`/`digest` 一碰就失败（同样是一句 ATTACHMENT_WRITE_FAILED）。
     *
     * 所以第二版不再猜哪一步：把发布整段**简化成直接复制字节**（跳过硬链接、EEXIST 去重、
     * 目录 fsync 那一整套），并在每个失败点把底层 errno 打进日志
     * （`[DSH-Folk attach] publish/alias/stage failed: <code> <message>`）——补丁再失效时能一眼看出。
     *
     * 与 [patchLanHost] 同款：每次启动幂等重打，运行时重装后自动回补；文件形状变了就跳过并记日志。
     */
    private fun patchAttachmentStore() {
        val target = File(
            DshEnv.rootfs(appContext),
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js",
        )
        if (!target.isFile) {
            appendLog("[attach] 跳过存储补丁：找不到 dsh-attachment-local/lib/index.js")
            return
        }
        runCatching {
            val src = target.readText(StandardCharsets.UTF_8)
            // 标记随第一处 hunk 一起写进去，重打时据此判幂等
            if (src.contains("DSH-Folk: Android")) return
            var out = src
            fun chunk(before: String) = before != out

            val b0 = out
            out = out.replace(
                "import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";",
                "import { chmod, copyFile, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";",
            )
            // 上一版（beta.219）可能已经打过 v1 补丁：那时 import 里已有 copyFile，这里要认它，
            // 否则升级后 hunk 匹配不上 → 整包补丁不写回 → 修复永远到不了已经打过 v1 的设备。
            val impOk = chunk(b0) || out.contains("import { chmod, copyFile, link,")

            // 落盘策略：既然这台的 link/fsync 单测都 OK、真实链路却失败，就**不再猜具体哪一步**，
            // 直接把发布简化成「复制字节」——跳过硬链接、EEXIST 去重、目录 fsync 那一整套。
            val b1 = out
            out = out.replace(
                "async function publishStagedObject(root, target, staged) {",
                "async function publishStagedObject(root, target, staged) {\n" +
                    "\t// DSH-Folk: Android 上不折腾硬链接/目录 fsync，直接复制字节。\n" +
                    "\ttry {\n" +
                    "\t\tawait ensureDurableDirectory(dirname(target), staged.boundary);\n" +
                    "\t\tawait copyFile(staged.path, target);\n" +
                    "\t\tawait removeTemporary(staged.path);\n" +
                    "\t\treturn;\n" +
                    "\t} catch (error) {\n" +
                    "\t\tconsole.error(\"[DSH-Folk attach] publish failed: \" + String(error && error.code) + \" \" + String(error && error.message));\n" +
                    "\t\tawait removeTemporary(staged.path);\n" +
                    "\t\tthrow new AttachmentError(\"Unable to persist attachment.\", \"ATTACHMENT_WRITE_FAILED\", { cause: error });\n" +
                    "\t}",
            )
            val pubOk = chunk(b1)

            val b2 = out
            out = out.replace(
                "async function publishImmutableAlias(root, source, target, sha256) {",
                "async function publishImmutableAlias(root, source, target, sha256) {\n" +
                    "\ttry {\n" +
                    "\t\tawait ensureDurableDirectory(dirname(target), await ensureDurableHome(dirname(dirname(resolve(root)))));\n" +
                    "\t\tawait copyFile(source, target);\n" +
                    "\t\treturn;\n" +
                    "\t} catch (error) {\n" +
                    "\t\tconsole.error(\"[DSH-Folk attach] alias failed: \" + String(error && error.code) + \" \" + String(error && error.message));\n" +
                    "\t\tthrow new AttachmentError(\"Unable to persist attachment.\", \"ATTACHMENT_WRITE_FAILED\", { cause: error });\n" +
                    "\t}",
            )
            val aliasOk = chunk(b2)

            // 写临时文件那一步失败时，也要把底层 errno 打出来（RemoteError 只带一个 reason，cause 会被丢掉）
            val b3 = out
            out = out.replace(
                "\t\tif (error instanceof AttachmentError || signal?.aborted === true) throw error;\n" +
                    "\t\tthrow new AttachmentError(\"Unable to persist attachment.\", \"ATTACHMENT_WRITE_FAILED\", { cause: error });",
                "\t\tif (error instanceof AttachmentError || signal?.aborted === true) throw error;\n" +
                    "\t\tconsole.error(\"[DSH-Folk attach] stage failed: \" + String(error && error.code) + \" \" + String(error && error.message));\n" +
                    "\t\tthrow new AttachmentError(\"Unable to persist attachment.\", \"ATTACHMENT_WRITE_FAILED\", { cause: error });",
            )
            val stageOk = chunk(b3)

            // 目录 fsync 失败别再上抛（ensureDurableDirectory 内部还会走到它）
            val sdStart = out.indexOf("async function syncDirectory(path) {")
            val sdEnd = if (sdStart >= 0) out.indexOf("\n}\n", sdStart) else -1
            if (sdStart >= 0 && sdEnd > sdStart) {
                val seg = out.substring(sdStart, sdEnd)
                out = out.substring(0, sdStart) + seg.replace("await handle.sync();", "await handle.sync().catch(() => {});") +
                    out.substring(sdEnd)
            }
            val fsyncOk = chunk(b3)

            appendLog(
                "[attach] 存储补丁：import=" + impOk + " publish=" + pubOk + " alias=" + aliasOk +
                    " stage=" + stageOk + " fsync=" + fsyncOk,
            )
            if (!impOk || !pubOk || !aliasOk || !stageOk) {
                appendLog("[attach] 存储补丁不完整，未写回（运行时版本可能变了，等下一版适配）")
                return
            }
            val tmp = File(target.parentFile, target.name + ".dshfolk.tmp")
            tmp.writeText(out, StandardCharsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            appendLog("[attach] 存储补丁已应用（落盘简化为直接复制 + 失败带 errno）")
        }.onFailure { appendLog("[attach] 存储补丁失败：" + (it.message ?: it.javaClass.simpleName)) }
    }

    /**
     * 从代码层强行开启 `--host 0.0.0.0`：注释掉 dsh-web-app 的安全检查。
     *
     * 当前 dsh-web-app 会硬编码拒绝 `0.0.0.0`，App 打开「局域网访问」后因此以
     * usage error 退出。每次启动前幂等 patch 掉那一行，让 dsh 接受该地址。
     */
    private fun patchLanHost() {
        val target = File(
            DshEnv.rootfs(appContext),
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-web-app/lib/startup.js",
        )
        if (!target.isFile) return
        val src = target.readText(StandardCharsets.UTF_8)
        val marker = "/* patched by DSH-Folk: lan force-enable */"
        if (src.contains(marker)) return
        val patched = src.replace(
            oldValue = """if (options.host === "0.0.0.0") program.error("error: --host 0.0.0.0 is intentionally not supported yet for safety: it would expose remote code execution to the network; use 127.0.0.1 instead");""",
            newValue = marker,
        )
        if (patched != src) {
            target.writeText(patched, StandardCharsets.UTF_8)
            logInfo(CoreR.string.dsh_log_lan_patched)
        }
    }

    /**
     * 让外部浏览器也能一次登进 dsh web：把会话 cookie 的 `SameSite=Strict` 放宽为 `Lax`。
     *
     * dsh 的浏览器会话认证（dsh-client-connection 的 `authorizeIndex`）在 `?token=` 校验通过后，
     * 用一个 303 重定向到 `/` 并下发会话 cookie，属性是 `HttpOnly; SameSite=Strict`。而 App 用
     * `Intent(ACTION_VIEW)` 拉起外部 Chrome，属于「外部/非第一方发起的顶层导航」——Chrome 对
     * `SameSite=Strict` 的 cookie 在这种导航（以及随后的 303 跳转、刷新按钮）上一律**不带**，
     * 于是服务端收不到 cookie → 回 401「authentication required; reopen the URL…」。用户手动在
     * 地址栏回车是第一方导航，`Strict` 才会带上，所以「手打能进、Intent/刷新不行」。
     *
     * 放宽成 `Lax`：顶层 GET 导航都会带 cookie（正好覆盖上面三种失败场景），跨站子请求/POST
     * 仍被挡住 —— 对一个只绑 `127.0.0.1` 的本地服务是安全且恰当的取舍。只改这一个属性，不碰
     * token/cookie 的签名逻辑，桌面端与内置 WebUI 都不受影响。
     *
     * 与 [patchLanHost] 同一路子：每次启动前幂等改 rootfs 里那份被服务端加载的源码；升级运行时
     * 会带来未打补丁的新副本，所以不能只打一次。
     */
    private fun patchBrowserCookieSameSite() {
        var patched = 0
        for (t in clientConnectionIndexFiles()) {
            val src = runCatching { t.readText(StandardCharsets.UTF_8) }.getOrNull() ?: continue
            if (!src.contains("HttpOnly; SameSite=Strict")) continue
            val out = src.replace("HttpOnly; SameSite=Strict", "HttpOnly; SameSite=Lax")
            if (out != src && runCatching { t.writeText(out, StandardCharsets.UTF_8) }.isSuccess) patched++
        }
        if (patched > 0) logInfo(CoreR.string.dsh_log_cookie_samesite_patched, patched)
    }

    /**
     * rootfs 里所有 `@deepseek-ai/dsh-client-connection/lib/index.js`（认证逻辑所在）：
     * dsh 全局安装目录下顶层 hoist 的一份，外加 `@deepseek-ai` 下各包各自 nested 的一份。
     * 有界枚举，不深走整棵 node_modules。
     */
    private fun clientConnectionIndexFiles(): List<File> {
        val out = ArrayList<File>()
        val scope = File(
            DshEnv.rootfs(appContext),
            "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai",
        )
        File(scope, "dsh-client-connection/lib/index.js").let { if (it.isFile) out.add(it) }
        scope.listFiles()?.forEach { pkg ->
            File(pkg, "node_modules/@deepseek-ai/dsh-client-connection/lib/index.js")
                .let { if (it.isFile) out.add(it) }
        }
        return out
    }

    /**
     * 轮询直到服务真能响应 HTTP 或超时。
     *
     * 只探 TCP 端口是不够的：端口被别的进程占着（上一次没退干净、用户自己在容器里
     * 跑了东西）也会 connect 成功，于是首页显示「已就绪」，点开却是别人的页面或直接
     * 连不上。这里在端口通之后再要一次 HTTP 响应 —— 任何状态码都算（dsh 未登录时
     * 返回登录页，403 也证明是它在服务）。
     */
    private suspend fun awaitReady() {
        // 没有进程就没什么可等。不能依赖下面那句
        // `serverProcess?.isAlive == false` —— serverProcess 为 null 时它是 false
        // （null == false），于是「压根没启动」会一声不响地等到超时。
        // [startServer] 已经报错的情况下直接返回，否则这里的超时/无进程
        // 文案会盖掉那个更具体的原因。
        if (_state.value.phase == DshPhase.ERROR) return
        if (serverProcess == null) {
            val detail = str(CoreR.string.dsh_err_no_process)
            appendLog("! $detail")
            _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
            return
        }
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        // 等待期间**定期把进程的存活与输出写进日志**。
        // 为什么必须有：真机实测 proroot 卡住时，日志从 `[attach] WRITE_OK` 直接跳到 90 秒后的
        // 「启动超时」——中间一片空白，既看不出进程还在不在，也看不到它最后说了什么。
        // 于是「proroot 为什么起不来」这个问题只能靠猜。每隔十几秒打一行心跳，把这条链路补上。
        var lastBeat = 0L
        while (System.currentTimeMillis() < deadline) {
            val nowBeat = System.currentTimeMillis()
            if (nowBeat - lastBeat >= READY_HEARTBEAT_MS) {
                lastBeat = nowBeat
                val p = serverProcess
                val waitedSec = (nowBeat - (deadline - READY_TIMEOUT_MS)) / 1000
                // 不打 pid：Process.pid() 在部分 android.jar 里没有定义（CI 直接报 Unresolved reference），
                // 而这条心跳要回答的是「进程还活着吗、端口通了吗」，pid 对这两个问题没有帮助。
                appendLog(
                    "[wait] 运行中=" + (p?.isAlive == true) +
                        " · 端口=" + port() + " 已监听=" + isPortInUse(port()) +
                        " · 已等=" + waitedSec + "s"
                )
                // 第二次心跳（30s）之后还无所获，就主动去容器里看进程树。
                // 为什么需要：proroot 卡住时日志里只有「等待」，看不出它到底走到哪一步 ——
                // 是 loader 还没起来、还是 node 起来了但插件树卡住。这两者的修法完全不同。
                // 只在 30s 后探一次，避免给每次正常启动都加一次容器调用。
                if (waitedSec >= 30 && waitedSec < 45) {
                    val tree = runCatching {
                        execRootfsForOutput(
                            "ps -eo pid,comm 2>/dev/null | grep -E 'node|proroot|proot|dsh' | head -20",
                            10_000L,
                        )
                    }.getOrNull().orEmpty().trim()
                    appendLog("[wait] 容器内相关进程：" + (tree.ifEmpty { "(无)" }))

                    // 探针：容器内**是否已经拉起 node**，以及 l2s 目录规模。
                    //
                    // 为什么需要：真机日志显示「运行中=true、90 秒端口未监听、容器内只有 libproroot.so」——
                    // 进程活着但 node 从没出来。若 l2s 目录在膨胀，就说明它卡在 --link2symlink 的
                    // 「移动真身 + 建链接」上（本机硬链接不可用，每次 link() 都要搬文件，node 加载
                    // 上千模块时开销被放大）。这些数字是唯一能验证这条假设的证据。
                    //
                    // 路径用 DshEnv 取而不是硬编码包名：装到别的包名（debug 变体）时硬编码会指向错误位置。
                    val l2sPath = DshEnv.l2sDir(appContext).absolutePath
                    val probe = runCatching {
                        execRootfsForOutput(
                            "echo NODE=\$(pgrep -c node 2>/dev/null || echo 0); " +
                                "echo L2S=\$(ls -1 ' + l2sPath + ' 2>/dev/null | wc -l)",
                            10_000L,
                        )
                    }.getOrNull().orEmpty().trim()
                    appendLog("[wait] 计数：" + probe.ifEmpty { "(探测失败)" })

                    // 决定性探针：**单独**用当前运行时跑一条最简命令。
                    //
                    // 为什么要这一步：真机上 proroot 是「进程活着、90 秒端口不监听、node 从未出现」，
                    // 但这有两种完全不同的可能，修法也完全不同：
                    //   (a) proroot 自己起不来（连 echo 都跑不动）→ 换/降级运行时；
                    //   (b) proroot 能跑，只是 dsh 加载慢到超过 90 秒 → 抬超时或预热。
                    // 跑一条 echo 就能一次性分开这两者，比继续猜快得多。
                    val smoke = runCatching {
                        execRootfsForOutput("echo SMOKE_OK", 20_000L)
                    }
                    appendLog(
                        "[wait] 冒烟（当前运行时跑 echo）：" +
                            (smoke.getOrNull()?.trim()?.ifEmpty { "(空输出)" }
                                ?: ("失败：" + (smoke.exceptionOrNull()?.message ?: "未知"))) +
                            " · 耗时取不到（看这一行有没有出现即可）",
                    )
                }
            }
            // 扩展拦截点：**判定「就绪」之前的最后一道口子**。
            //
            // around 返回 false = 扩展认为还不算就绪（例如它发现某个它关心的插件还没加载完），
            // 于是这一轮跳过就绪判定、继续等下一轮心跳 —— 超时仍由 READY_TIMEOUT_MS 管，
            // 不会因为扩展一直否决而永远等下去。
            //
            // 为什么值得给这个口子：真机上「端口通了但 dsh 插件树还没加载完」是一类真实状态，
            // 宿主的判据只有端口 + HTTP，看不出这些；扩展比宿主更清楚自己关心什么。
            val readyOk = if (ExtensionHookRegistry.has(ExtensionHookRegistry.Point.RUNTIME_PROBE_READY)) {
                // 起始时刻由 deadline 反推（与上面 waitedSec 的算法一致，别再造一个变量）
                val payload = mutableMapOf<String, Any?>(
                    "port" to port(),
                    "waitedMs" to (nowBeat - (deadline - READY_TIMEOUT_MS)),
                )
                ExtensionHookRegistry.runAround(ExtensionHookRegistry.Point.RUNTIME_PROBE_READY, payload)
            } else {
                true
            }
            if (readyOk && isPortInUse(port()) && httpResponds(port())) {
                awaitWebToken()
                _state.update {
                    it.copy(
                        phase = DshPhase.RUNNING,
                        progress = 1f,
                        message = str(CoreR.string.dsh_msg_service_ready, it.webUrl),
                    )
                }
                logInfo(CoreR.string.dsh_log_ready, port())
                prefs().edit().putInt(DshEnv.KEY_PROROOT_FAIL, 0).apply()
                return
            }
            if (serverProcess?.isAlive == false) {
                val base = str(CoreR.string.dsh_err_process_exited)
                appendLog("! $base")
                if (noteProrootFailure(base)) {
                    logInfo(CoreR.string.dsh_log_switched_to_proot)
                }
                // 插件树失败时给出可执行的下一步，而不是只说「进程已退出」
                val detail = pluginTreeFailureHint() ?: base
                _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
                return
            }
            delay(1_000)
        }
        // 超时同样算 proroot 一次失败：进程没退但服务始终起不来（proroot 在部分内核上
        // 卡在 seccomp/ptrace 上就是这种表现），只记「进程退出」那一路会让用户永远
        // 卡在坏运行时上，自动回退 proot 的兜底形同不存在。
        val detail = str(CoreR.string.dsh_err_start_timeout)
        appendLog("! $detail")
        if (noteProrootFailure(detail)) {
            logInfo(CoreR.string.dsh_log_switched_to_proot)
        }
        _state.update { it.copy(phase = DshPhase.ERROR, message = detail) }
    }

    /**
     * 等 dsh 打印出带 token 的地址（见 [TOKEN_WAIT_MS]）。
     *
     * 不能把「端口响应」当成「可以打开」：web 服务器**激活即监听**，而 token 那行是插件树
     * 加载完才打印的，两者之间的 `webUrl` 是不带 token 的裸地址 —— 用它开 WebUI（内置页或
     * 外部浏览器）都只会撞认证墙。等到了再宣布就绪，所有打开路径拿到的就是带 token 的地址。
     *
     * 超时不是错误：这份运行时若哪天不再打印 token（[DSH_WEB_TOKEN_RE] 没命中），也得让
     * 服务照常可用，所以按无 token 继续，只留一行日志说明 —— 否则表现成「启动卡住」，
     * 比认证墙更难查。
     */
    private suspend fun awaitWebToken() {
        if (_state.value.webToken != null) return
        val deadline = System.currentTimeMillis() + TOKEN_WAIT_MS
        var announced = false
        while (_state.value.webToken == null &&
            System.currentTimeMillis() < deadline &&
            serverProcess?.isAlive != false
        ) {
            if (!announced) {
                logInfo(CoreR.string.dsh_log_token_waiting)
                announced = true
            }
            delay(TOKEN_POLL_MS)
        }
        val token = _state.value.webToken
        if (token == null) {
            appendLog("! " + str(CoreR.string.dsh_log_token_timeout, TOKEN_WAIT_MS / 1000))
        } else {
            // 只记长度，不记 token 本身：长度够用来判断有没有被截断（base64url(32 字节) = 43）
            logInfo(CoreR.string.dsh_log_token_captured, token.length)
        }
    }

    /**
     * 回环 HTTP 是否有响应。
     *
     * 任何 HTTP 状态码都算就绪 —— dsh 未登录时返回登录页（200）或 403，都说明服务在跑。
     * 只有连不上 / 不是 HTTP / 超时才算没起来。
     */
    private fun httpResponds(port: Int): Boolean = runCatching {
        val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 1_500
            conn.readTimeout = 2_500
            conn.instanceFollowRedirects = false
            conn.responseCode > 0
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrDefault(false)

    /** 停止 web 服务：先杀容器内的 dsh web，再销毁 proot 进程。 */
    fun stopServer() {
        runCatching {
            // proot 不隔离 PID，容器内 /proc 看到宿主全部进程 —— 只杀 bin.js web / dsh web，
            // 绝不裸 pkill -f node（会误杀 agent 或用户自己的 node 进程）。
            //
            // 必须排除自己：这条清理命令的 cmdline 里就含 'bin.js web' 字面量，pgrep -f 会
            // 把执行它的 bash、以及 $() 命令替换 fork 出的子 shell 一起匹配上（子 shell 与
            // 父进程共享 cmdline，所以比对 PID 挡不住它）。做法是在命令里埋一个哨兵字符串，
            // 再按 /proc/<pid>/cmdline 把带哨兵的进程全部跳过 —— 否则 kill -KILL 会先把这条
            // 清理命令自己杀掉，真正的 dsh web 反而留着。
            //
            // **为什么先「查一遍」再决定要不要等**（真机实测：这段让每次点启动多等 2 秒以上）：
            // 绝大多数启动根本没有残留进程（上次已干净退出），而原写法 `_kill -TERM; sleep 1;
            // _kill -KILL` 无论有没有目标都**无条件睡满 1 秒**，之前还要扫两遍宿主全部进程
            // （proot 模式下 /proc 里是整机进程，不是容器里的）。改成先用一次 pgrep 收集候选：
            // 空就直接退出，有才走 TERM→等待→KILL。语义不变，只是不再为「没东西可杀」付固定开销。
            execRootfs(
                ": DSHFOLK_STOP_SENTINEL; " +
                "_pids() { pgrep -f 'bin.js web' 2>/dev/null; pgrep -f 'dsh web' 2>/dev/null; }; " +
                "_alive() { for _p in \$(_pids); do " +
                "grep -qa DSHFOLK_STOP_SENTINEL \"/proc/\$_p/cmdline\" 2>/dev/null && continue; " +
                "echo \"\$_p\"; done; }; " +
                "_targets=\$( _alive ); " +
                "[ -z \"\$_targets\" ] && exit 0; " +
                "kill -TERM \$_targets 2>/dev/null; sleep 1; " +
                "_left=\$( _alive ); " +
                "[ -n \"\$_left\" ] && kill -KILL \$_left 2>/dev/null; exit 0"
            ).waitFor(8, TimeUnit.SECONDS)
        }
        runCatching { serverProcess?.destroyForcibly() }
        serverProcess = null
        startedAt = 0L
        DshFsBridge.stop()
        _state.update { it.copy(phase = DshPhase.NOT_READY, pid = null, message = str(CoreR.string.dsh_msg_stopped)) }
    }

    /**
     * 终止正在跑的插件安装/更新（进度窗口的「停止更新」）。
     *
     * 只杀 pnpm 与 `dsh plugin add|remove` 这一类命令行，**绝不裸 pkill node**：proot/chroot
     * 都不隔离 PID，容器里看得到宿主进程，裸杀会把 agent 或用户自己的 node 一起带走。
     *
     * 哨兵字符串的用法与 [stopServer] 同源：这条清理命令自己的 cmdline 里就含 `pnpm`、
     * `plugin add` 这些字面量，pgrep -f 会把它自己也匹配上（连同 $() 命令替换 fork 出的
     * 子 shell），必须先按 /proc/<pid>/cmdline 跳过 —— 否则 kill -KILL 会先把自己杀掉，
     * 真正在跑的安装反而留着。
     */
    /**
     * 用户点了「停止」。
     *
     * 除了杀容器里正在跑的 pnpm/dsh plugin，还要置一个标志：安装链是**多步重试**的
     * （多条 registry / 多条 github 线路 / tgz 兜底），杀掉当前那一步之后，循环会接着试
     * 下一个源 —— 真机反馈的「停止更新后还会尝试更新」就是这么来的。
     * 重试链每一步之前问一次 [pluginStopRequested]，用户喊停就真的不再往下试。
     */
    @Volatile
    private var pluginStopFlag = false

    fun requestPluginStop() {
        pluginStopFlag = true
        killPluginOperations()
    }

    /** 本次插件操作是否已被要求停止（重试链用它提前收手）。 */
    fun pluginStopRequested(): Boolean = pluginStopFlag

    /** 每次插件操作开始时清掉：上一次的停止不能把这一次也拦掉。 */
    fun clearPluginStop() {
        pluginStopFlag = false
    }

    fun killPluginOperations() {
        runCatching {
            execRootfs(
                ": DSHFOLK_PLUGIN_STOP_SENTINEL; " +
                "_kill() { for _p in " +
                // 真实 argv 是 `node … plugin --profile web add|remove …`（见 DshPluginRepo.dshPlugin），
                // 没有连续的 "plugin add" 子串 —— 早先那两条模式是**死模式**，一条都匹配不到。
                "\$(pgrep -f 'pnpm' 2>/dev/null; pgrep -f 'plugin --profile' 2>/dev/null); do " +
                "grep -qa DSHFOLK_PLUGIN_STOP_SENTINEL \"/proc/\$_p/cmdline\" 2>/dev/null && continue; " +
                "kill \"\$1\" \"\$_p\" 2>/dev/null; done; }; " +
                "_kill -TERM; sleep 1; _kill -KILL"
            ).waitFor(8, TimeUnit.SECONDS)
        }
    }

    fun uptimeMillis(): Long = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    // ────────────────────────── 日志 ──────────────────────────
    //
    // 实现已迁到 [DshLogging]（Checkpoint 1 的职责拆解）。这里只留同名转发，
    // 调用点一个都不用改 —— 全仓 130 处 DshRuntime.appendLog / str / logInfo 照旧。

    fun tailLog(lines: Int = 200): String = DshLogging.tailLog(lines)

    fun tailPrevLog(lines: Int = 2000): String = DshLogging.tailPrevLog(lines)

    fun appLogFiles(): List<java.io.File> = DshLogging.appLogFiles()

    internal fun windowedLog(minutes: Int): DshLogWindow.Result = DshLogging.windowedLog(minutes)

    override fun clearLog() = DshLogging.clearLog()

    fun clearAppLogs() = DshLogging.clearAppLogs()

    fun appendLog(line: String) = DshLogging.appendLog(line)

    /** 增量日志订阅（见 [DshLogging.observeLogs]）。给极简壳扩展做实时滚动日志。 */
    fun observeLogs(onLine: (String) -> Unit): () -> Unit = DshLogging.observeLogs(onLine)

    override fun fail(message: String) = DshLogging.fail(message)

    override fun str(resId: Int, vararg args: Any): String = DshLogging.str(resId, *args)

    override fun logInfo(resId: Int, vararg args: Any) = DshLogging.logInfo(resId, *args)

    override fun logWarn(resId: Int, vararg args: Any) = DshLogging.logWarn(resId, *args)

    internal fun joinForLog(items: Collection<String>): String = DshLogging.joinForLog(items)

    override fun sourceName(source: String): String = DshLogging.sourceName(source)

    private fun shellSingleQuoted(s: String): String = s.replace("'", "'\\''")

    // ────────────────────────── 工具 ──────────────────────────

    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0 -> "…"
        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / 1024.0 / 1024.0)
        bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }

    override fun verifySha256(file: File, expected: String): Boolean {
        // metadata 里没给 sha256 时不拦（早期 runtime 发布没有这一项）
        if (expected.isBlank()) return true
        return DshDownloader.sha256(file).equals(expected, ignoreCase = true)
    }

    override fun availableSpace(dir: File): Long = runCatching {
        dir.mkdirs()
        StatFs(dir.absolutePath).availableBytes
    }.getOrDefault(-1L)

    /**
     * 运行时占用统计（设置页存储信息用）。
     *
     * **别在组合期调用**：它递归遍历整个 rootfs。首页要显示体积请读
     * `state.rootfsSizeBytes`（缓存值），需要刷新走 [refreshRootfsSize]。
     */
    fun rootfsSizeBytes(): Long = if (!ready) 0L else dirSize(DshEnv.rootfs(appContext))

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }
}
