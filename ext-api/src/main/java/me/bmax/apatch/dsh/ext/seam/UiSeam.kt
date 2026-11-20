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
}
