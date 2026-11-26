package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import me.bmax.apatch.dsh.ext.ExtensionServiceRegistry

/**
 * 宿主默认 seam 实现的持有者与取用入口。
 *
 * ## 宿主默认实现**不注册**进扩展覆写表
 *
 * 第一版把宿主实现用 `register("host", ...)` 塞进了 [ExtensionServiceRegistry]。
 * 那是错的，有两个真实故障：
 *
 * 1. **安全模式熔断后直接崩溃**。`resolve()` 在熔断时短路返回 `fallback()`，
 *    而当时的 fallback 是 `error("SeamRegistry.install() 还没调用")` ——
 *    熔断之后任何一次能力调用都会抛异常。熔断是"扩展把启动路径写坏了，用户唯一
 *    还走得通的逃生通道"，结果逃生通道本身崩了。
 *
 * 2. **`clearAll()` 之后宿主实现没了，但 `installed` 还是 true**。
 *    卸载全部扩展时会调 `clearAll()`，它把 `"host"` 那条也一并清掉；
 *    而 `installed` 标志让 `install()` 直接 return —— 于是宿主永远拿不到默认实现。
 *
 * ## 现在的做法
 *
 * 宿主默认实例由本对象**自己持有**，只作为 `resolve()` 的 fallback 传入：
 *
 * - 没扩展覆写 → 拿到宿主默认（正常情况）
 * - 有扩展覆写 → 拿到扩展那份（"整块替换"仍然成立）
 * - 熔断 / clearAll() → fallback 就是宿主默认，**永远可用**
 *
 * 这样三条路径都不会崩，而且语义更清楚：覆写表里只有"扩展"，
 * 宿主是基线而不是"一个叫 host 的扩展"。
 */
object SeamRegistry {

    @Volatile
    private var provider: SeamProvider? = null

    /**
     * 初始化宿主默认实现。可重复调用（幂等）。
     *
     * 不再往 [ExtensionServiceRegistry] 写任何东西 —— 宿主不是扩展，
     * 不该占覆写表里的位置。
     */
    @Synchronized
    fun install(ctx: Context, p: SeamProvider) {
        if (provider != null) return
        fallbackContext = ctx.applicationContext
        provider = p
    }

    /** 宿主默认实现是否已就绪（测试与诊断用）。 */
    fun isInstalled(): Boolean = provider != null

    // ── 取用：先问覆写表，没有就用宿主默认 ──
    //
    // 每个都写成 `resolve(type) { default }`：熔断时 resolve 会短路返回 default，
    // 这正是我们要的行为（熔断后不能还有扩展在跑，但宿主自己得能跑）。

    fun runtime(): RuntimeSeam = resolve(RuntimeSeam::class.java) { requireProvider().runtime }

    fun shell(): ShellSeam = resolve(ShellSeam::class.java) {
        requireProvider().shell ?: UnavailableShell
    }

    fun device(): DeviceSeam = resolve(DeviceSeam::class.java) {
        requireProvider().device ?: UnavailableDevice
    }

    fun ui(): UiSeam = resolve(UiSeam::class.java) {
        requireProvider().ui ?: UnavailableUi
    }

    fun nativeBridge(): NativeBridgeSeam = resolve(NativeBridgeSeam::class.java) {
        requireProvider().nativeBridge
            ?: throw IllegalStateException("NativeBridgeSeam 需要 Context：请先 SeamRegistry.install(ctx)")
    }

    fun themeBackup(): ThemeBackupSeam = resolve(ThemeBackupSeam::class.java) {
        requireProvider().themeBackup ?: UnavailableThemeBackup
    }

    fun backup(): BackupSeam = resolve(BackupSeam::class.java) {
        requireProvider().backup ?: UnavailableBackup
    }

    fun event(): EventSeam = resolve(EventSeam::class.java) { requireProvider().event }

    fun capability(): CapabilitySeam = resolve(CapabilitySeam::class.java) { requireProvider().capability }

    private fun <T : Any> resolve(type: Class<T>, fallback: () -> T): T =
        ExtensionServiceRegistry.resolve(type, fallback)

    /**
     * `install()` 之前的兜底。
     *
     * 返回一个**能用的**默认实现而不是抛异常：`install()` 在
     * `ExtensionEngine.bootstrap` 里调用，而 `onCreate` 早期、单元测试、
     * 以及某些后台进程都可能在没有 install 的情况下碰到能力调用。
     * 抛异常的代价是整个 App 崩，而"给一个无状态默认实现"最坏只是少了个 ctx。
     *
     * 注意 宿主实现本来就是无状态的，
     * 所以这里只需要 ctx —— 而没 install 时也没有 ctx，只能给进程级的兜底。
     */
    /**
     * 取宿主实现。
     *
     * install 没跑时**不抛异常**，返回「全部能力不可用」的兜底 —— 熔断与早期初始化
     * 都可能走到这里，抛异常的代价是整个 App 崩，而"能力不可用"最坏只是扩展降级。
     * 逃生通道不能自己崩。
     */
    private fun requireProvider(): SeamProvider =
        provider ?: UnavailableProvider.also { provider = it }

    /** 由宿主在 `install()` 之外设置的兜底 context（见 [setFallbackContext]）。 */
    @Volatile
    private var fallbackContext: Context? = null

    /** 宿主早期（`APApplication.onCreate`）调用，给 [requireProvider] 一个兜底。 */
    fun setFallbackContext(ctx: Context) {
        fallbackContext = ctx.applicationContext
    }

    private fun currentApplicationContext(): Context? = fallbackContext

    // ── 未初始化时的"不可用"实现 ──
    //
    // 每个方法都返回"失败"而不是抛异常：扩展拿到它们时最多是功能不可用，
    // 不会因为宿主早期状态把自己崩掉。

    /**
     * 兜底实现：所有能力都"不可用"。
     *
     * 只在 install 没跑时出现（正常启动路径不会）。每个方法返回失败而不是抛异常 ——
     * 扩展拿到它最多是功能不可用，不会因为宿主早期状态把自己崩掉。
     */
    private object UnavailableProvider : SeamProvider {
        override val runtime: RuntimeSeam = UnavailableRuntime
        override val event: EventSeam = UnavailableEvent
        override val capability: CapabilitySeam = UnavailableCapability
        override val shell: ShellSeam = UnavailableShell
        override val device: DeviceSeam = UnavailableDevice
        override val ui: UiSeam = UnavailableUi
        override val backup: BackupSeam = UnavailableBackup
        override val nativeBridge: NativeBridgeSeam = UnavailableNativeBridge
        override val themeBackup: ThemeBackupSeam = UnavailableThemeBackup
    }

    private object UnavailableRuntime : RuntimeSeam {
        override fun state() = RuntimeState(installed = false, running = false, installing = false)
        override fun observe(handler: (RuntimeState) -> Unit): () -> Unit = { }
        override fun exec(bashCommand: String): Process = throw IllegalStateException("runtime 未就绪")
        override fun execCapture(bashCommand: String, timeoutMs: Long) = ""
        override fun execStreaming(bashCommand: String, timeoutMs: Long, onLine: (String) -> Unit) = ""
        override fun attach(context: Context) { }
        override fun awaitRunning(timeoutMs: Long) = false
        override fun start(context: Context) = false
        override fun hold(context: Context) { }
        override fun log(message: String) { }
        // 没有运行时就没有日志：给空列表与一个什么都不做的取消函数。
        // 这里**不能**抛 —— 扩展在壳里画启动屏时第一帧就会调它，抛异常等于整页崩掉。
        override fun recentLogs(limit: Int): List<String> = emptyList()
        override fun observeLogs(onLine: (String) -> Unit): () -> Unit = { }
    }

    private object UnavailableEvent : EventSeam {
        override fun subscribe(event: String, handler: (Map<String, Any?>) -> Unit): () -> Unit = { }
        override fun emit(event: String, payload: Map<String, Any?>) { }
    }

    private object UnavailableCapability : CapabilitySeam {
        override fun declared(): List<String> = emptyList()
        override fun available(name: String) = false
        override fun hostVersion() = "unknown"
    }

    private object UnavailableNativeBridge : NativeBridgeSeam {
        override fun handle(ctx: Context, method: String, path: String, params: Map<String, String>) =
            503 to """{"error":"native bridge 未就绪"}"""
        override fun isForeground(ctx: Context) = false
        override fun str(ctx: Context, resId: Int, vararg args: Any) = ""
        override fun err(msg: String, reason: String) = """{"error":"$msg","reason":"$reason"}"""
        override fun stageDir(ctx: Context) = java.io.File("/dev/null")
        override fun stageGuestPath(name: String) = ""
        override fun trimStage(dir: java.io.File) { }
    }

    private object UnavailableShell : ShellSeam {
        override fun hasRoot() = false
        override fun root(command: String, timeoutMs: Long) = ""
        override fun hasShizuku() = false
        override fun shizuku(command: String, timeoutMs: Long) = ""
        override fun adbConnected() = false
        override fun adb(command: String, timeoutMs: Long) = ""
    }

    private object UnavailableDevice : DeviceSeam {
        override fun a11yEnabled() = false
        override fun openA11ySettings() {}
        override fun a11yPerform(action: String) = false
        override fun cameraGranted() = false
        override fun ttsAvailable() = false
        override fun speak(text: String) = false
        override fun screenOn() = false
        override fun batteryPercent() = -1
        override fun charging() = false
    }

    private object UnavailableUi : UiSeam {
        override fun darkTheme() = false
        override fun accentColor() = 0L
        override fun navigate(route: String) = false
        override fun openWebUi() {}
        override fun toast(message: String) {}
        override fun language() = "en"
        // 没有 UI 宿主就没有悬浮窗能力：一律报"没权限"、show 返回 false。
        // 这里**不能**抛 —— 扩展在 onLoad 里查权限是常见写法，抛异常等于扩展加载失败。
        override fun hasOverlayPermission() = false
        override fun openOverlaySettings() {}
        override fun showGlobalOverlay(
            ownerId: String,
            content: androidx.compose.runtime.Composable (androidx.compose.ui.Modifier) -> Unit,
        ) = false
        override fun hideGlobalOverlay(ownerId: String) {}
        override fun hideAllOverlaysOf(ownerId: String) {}
    }

    private object UnavailableThemeBackup : ThemeBackupSeam {
        override fun measureThemeZipBytes(ctx: Context, meta: ThemeBackupSeam.ThemeMetadata) = -1L
        override fun exportTheme(ctx: Context, target: android.net.Uri, meta: ThemeBackupSeam.ThemeMetadata) = false
        override fun importTheme(ctx: Context, source: android.net.Uri) = false
    }

    private object UnavailableBackup : BackupSeam {
        override fun exportConfig(password: String): java.io.File? = null
        override fun importConfig(archive: java.io.File, password: String) = false
        override fun checkUpdate() {}
        override fun currentVersion() = "unknown"
    }
}
