package me.bmax.apatch.dsh.ext.seam
/**
 * 宿主界面外壳：主题、路由跳转、打开 WebUI。
 *
 * 扩展不该自己起 Activity —— 那会出现两个任务栈、返回键行为诡异。
 * 走这里，跳转由宿主的导航栈管。
 */
interface UiSeam {

    /** 当前是否深色模式。 */
    fun darkTheme(): Boolean

    /** 当前主题主色（ARGB）。 */
    fun accentColor(): Long

    /** 按 route 跳转（宿主导航图里的路由，含扩展注册的页面）。 */
    fun navigate(route: String): Boolean

    /** 打开宿主内置 WebUI。 */
    fun openWebUi()

    /** 弹一条宿主风格的提示。 */
    fun toast(message: String)

    /** 当前界面语言（BCP 47，如 `zh-CN`）。 */
    fun language(): String

    // ── 全局悬浮窗（盖在别的应用上面） ──────────────────────────────────────

    /**
     * 是否已获得「显示在其他应用上层」权限。
     *
     * 调用 [showGlobalOverlay] 之前必须先查它：没权限时系统会静默不显示窗口
     * （不抛异常、不报错），调用方会以为成功了。
     */
    fun hasOverlayPermission(): Boolean

    /**
     * 跳到系统的悬浮窗权限设置页。
     *
     * 为什么需要它：这个权限**不能**用运行时权限弹窗申请，只能跳设置页让用户手动开。
     * 不提供这个入口的话，扩展只能干巴巴提示"请去设置里开"，而用户根本找不到在哪。
     */
    fun openOverlaySettings()

    /**
     * 挂一个全局悬浮窗。返回是否成功（没权限、内容为空、同 id 已存在都返回 false）。
     *
     * ## 与 [ExtensionUiRegistry.Slot.WEBUI_OVERLAY] 的区别
     *
     * 槽位是**往宿主页面里插**，只在本应用可见；这个是**独立的系统窗口**，
     * 能盖在任何应用上面。前者不需要权限，后者需要。
     *
     * ## 生命周期由宿主负责
     *
     * 扩展不需要自己起 Service、不需要管窗口何时摘除：
     * 扩展被停用/卸载、或安全模式熔断时，宿主会自动摘掉它挂的所有窗口
     * （见 `ExtensionEngine.unload`）。这样就不会出现"扩展没了、窗口还在"的僵尸。
     *
     * @param ownerId 归属者 id，通常是扩展 id。卸载时按它批量摘除。
     * @param content Compose 内容。给它一个 Modifier，扩展负责画满它。
     */
    fun showGlobalOverlay(ownerId: String, content: @androidx.compose.runtime.Composable (androidx.compose.ui.Modifier) -> Unit): Boolean

    /** 摘掉某个 owner 的全局悬浮窗。没挂过也安全（幂等）。 */
    fun hideGlobalOverlay(ownerId: String)

    /** 摘掉某个 owner 的全部悬浮窗（卸载时宿主调用）。 */
    fun hideAllOverlaysOf(ownerId: String)
}
