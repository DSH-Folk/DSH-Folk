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
internal object SeamRegistry {

    @Volatile
    private var defaults: Defaults? = null

    /**
     * 宿主默认实现。
     *
     * [FsSeam] 不在这里：它带 `extensionDir`，每个扩展一份，由 [HostSeams] 直接 new。
     */
    private class Defaults(val ctx: Context?) {
        val runtime: RuntimeSeam = HostRuntimeSeam()
        val event: EventSeam = HostEventSeam()
        val capability: CapabilitySeam = HostCapabilitySeam()
        // 这四个要 Context。ctx 为 null（未初始化）时给 null，取用时返回"不可用"实现。
        val shell: ShellSeam? = ctx?.let { HostShellSeam(it) }
        val device: DeviceSeam? = ctx?.let { HostDeviceSeam(it) }
        val ui: UiSeam? = ctx?.let { HostUiSeam(it) }
        val backup: BackupSeam? = ctx?.let { HostBackupSeam(it) }
    }

    /**
     * 初始化宿主默认实现。可重复调用（幂等）。
     *
     * 不再往 [ExtensionServiceRegistry] 写任何东西 —— 宿主不是扩展，
     * 不该占覆写表里的位置。
     */
    @Synchronized
    fun install(ctx: Context) {
        if (defaults != null) return
        defaults = Defaults(ctx.applicationContext)
    }

    /** 宿主默认实现是否已就绪（测试与诊断用）。 */
    fun isInstalled(): Boolean = defaults != null

    // ── 取用：先问覆写表，没有就用宿主默认 ──
    //
    // 每个都写成 `resolve(type) { default }`：熔断时 resolve 会短路返回 default，
    // 这正是我们要的行为（熔断后不能还有扩展在跑，但宿主自己得能跑）。

    fun runtime(): RuntimeSeam = resolve(RuntimeSeam::class.java) { requireDefaults().runtime }

    fun shell(): ShellSeam = resolve(ShellSeam::class.java) {
        requireDefaults().shell ?: UnavailableShell
    }

    fun device(): DeviceSeam = resolve(DeviceSeam::class.java) {
        requireDefaults().device ?: UnavailableDevice
    }

    fun ui(): UiSeam = resolve(UiSeam::class.java) {
        requireDefaults().ui ?: UnavailableUi
    }

    fun backup(): BackupSeam = resolve(BackupSeam::class.java) {
        requireDefaults().backup ?: UnavailableBackup
    }

    fun event(): EventSeam = resolve(EventSeam::class.java) { requireDefaults().event }

    fun capability(): CapabilitySeam = resolve(CapabilitySeam::class.java) { requireDefaults().capability }

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
     * 注意 [HostRuntimeSeam] / [HostEventSeam] / [HostCapabilitySeam] 本来就是无状态的，
     * 所以这里只需要 ctx —— 而没 install 时也没有 ctx，只能给进程级的兜底。
     */
    @Synchronized
    private fun requireDefaults(): Defaults {
        defaults?.let { return it }
        // 极端情况（install 没跑）：用兜底 context 补一个。
        //
        // 拿不到 context 时**不抛异常** —— 返回一个"全部能力都不可用"的 Defaults。
        // 为什么：熔断/早期初始化都可能走到这里，抛异常的代价是整个 App 崩，
        // 而"能力不可用"最坏只是扩展降级。逃生通道不能自己崩。
        //
        // 注：@Synchronized 只能标函数/访问器，不能标表达式 —— 第一版标在
        // `if` 语句上，编译直接报 "not applicable to target 'expression'"。
        return Defaults(currentApplicationContext()).also { defaults = it }
    }

    /** 由宿主在 `install()` 之外设置的兜底 context（见 [setFallbackContext]）。 */
    @Volatile
    private var fallbackContext: Context? = null

    /** 宿主早期（`APApplication.onCreate`）调用，给 [requireDefaults] 一个兜底。 */
    fun setFallbackContext(ctx: Context) {
        fallbackContext = ctx.applicationContext
    }

    private fun currentApplicationContext(): Context? = fallbackContext

    // ── 未初始化时的"不可用"实现 ──
    //
    // 每个方法都返回"失败"而不是抛异常：扩展拿到它们时最多是功能不可用，
    // 不会因为宿主早期状态把自己崩掉。

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
    }

    private object UnavailableBackup : BackupSeam {
        override fun exportConfig(password: String) = null
        override fun importConfig(archive: java.io.File, password: String) = false
        override fun checkUpdate() {}
        override fun currentVersion() = "unknown"
    }
}
