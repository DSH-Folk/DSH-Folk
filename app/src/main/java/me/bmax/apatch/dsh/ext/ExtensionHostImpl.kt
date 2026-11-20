package me.bmax.apatch.dsh.ext

import android.content.Context
import me.bmax.apatch.dsh.DshEnv

/**
 * [DshExtensionHost] 的宿主实现。
 *
 * 比接口多两样东西：[internalPlatform]（强类型门面，仅一等公民扩展）与
 * 直接暴露宿主内部类型，放进公共 API 等于把整个 app 钉成 API。
 * 所以：市场里的扩展只用接口；将来若要做「一等公民扩展」，再由宿主侧把它们编进 APK 并拿这个句柄。
 */
internal class ExtensionHostImpl(
    override val id: String,
    override val appContext: Context,
    /** 该扩展在 manifest 里声明的能力。改宿主开关前要拿它核对。 */
    private val declaredCaps: List<String> = emptyList(),
) : DshExtensionHost {

    override val fs: ExtensionFs by lazy { AppExtensionFs(appContext, id) }

    /**
     * 全部宿主能力（反射式，见 [AppExtensionPlatform]）。
     *
     * 已标记 `@Deprecated`：反射依赖宿主实现类的方法名，而 release 构建开了
     * `-repackageclasses`，`me.bmax.apatch.dsh.*` 下的方法名会被混淆。
     * 保留它只为兼容已发布的老扩展；新代码用 [seam]。
     */
    @Deprecated(
        message = "反射在 release 混淆包里不可靠，请改用 seam",
        replaceWith = ReplaceWith("seam"),
    )
    override val platform: ExtensionPlatform by lazy {
        // 传 seam 的取用器：高频门面方法会优先走强类型 seam（R8 安全），
        // 其余仍走反射。传 lambda 而不是实例，避免 platform / seam 互相 lazy 初始化时打结。
        AppExtensionPlatform(appContext, id) { seam }
    }

    /**
     * 强类型宿主能力（编译期绑定，R8 安全）。
     *
     * 与 [platform] 的区别见 [ExtensionSeams] 的注释 —— 一句话：
     * 这个走接口调用，混淆不影响；那个走反射，只在 debug 包可靠。
     */
    override val seam: ExtensionSeams by lazy {
        me.bmax.apatch.dsh.ext.seam.HostSeams(
            appContext,
            // 用 ExtensionLayout 作为唯一来源，不自己拼 "extensions/<id>" ——
            // 目录布局一旦调整（比如加版本子目录），两处拼路径就会分叉，
            // 表现是"扩展写进去的文件自己读不到"。
            ExtensionLayout.dirOf(appContext, id),
        )
    }

    /** 往容器里装命令。归属记录落盘，卸载时由 [AppExtensionContainer.removeAllOf] 收回。 */
    override val container: ExtensionContainer by lazy {
        AppExtensionContainer(appContext, id)
    }

    /**
     * 强类型的宿主门面，**只有编进 APK 的一等公民扩展**拿得到（第三方编译时看不到）。
     *
     * 与 [platform]（反射式、给所有扩展）的区别：这个是编译期类型安全的，但要求扩展与
     * 宿主一起编译。市场里的扩展走 [platform]。
     */
    val internalPlatform: DshPlatform by lazy { DshPlatform(appContext, id) }

    override fun log(message: String) {
        ExtLog.log("[" + id + "] " + message)
    }

    /**
     * 宿主开关的门面。
     *
     * 写之前**必须**核对该扩展在 manifest 里声明过对应能力：
     * 声明是用户在启用前看到的告知，绕过它等于偷偷改用户没同意的事。
     */
    override val host: ExtensionHostSettings = object : ExtensionHostSettings {
        override fun get(switch: HostSwitch): Boolean = when (switch) {
            HostSwitch.BROWSER_MODE -> DshEnv.browserMode(appContext)
            HostSwitch.WEBVIEW_DEBUG -> DshEnv.webviewDebug(appContext)
        }

        override fun set(switch: HostSwitch, on: Boolean): Boolean {
            val cap = when (switch) {
                HostSwitch.BROWSER_MODE -> CAP_HOST_BROWSER_MODE
                HostSwitch.WEBVIEW_DEBUG -> CAP_HOST_WEBVIEW_DEBUG
            }
            if (cap !in declaredCaps) {
                ExtLog.log("[" + id + "] 拒绝修改 " + switch.id + "：manifest 未声明 " + cap)
                return false
            }
            when (switch) {
                HostSwitch.BROWSER_MODE -> DshEnv.setBrowserMode(appContext, on)
                // 调试通道**不可写**：它由「已启用的扩展声明了 host:webview-debug」推导。
                // 允许扩展自己写它就等于自举 —— 一个扩展能单方面把自己需要的通道打开，
                // 而用户在启用前看到的告知就失去了约束力。这里只读不写。
                HostSwitch.WEBVIEW_DEBUG -> Unit
            }
            ExtLog.log("[" + id + "] 设置 " + switch.id + " = " + on)
            return true
        }
    }

    companion object {
        /**
         * 扩展声明「我要改宿主浏览器模式」用的能力名。
         *
         * 它出现在启用前的信任确认里，所以措辞要能让人看懂后果：
         * 开浏览器模式 = 应用内会加载第三方网页。
         */
        const val CAP_HOST_BROWSER_MODE = "host:browser-mode"

        /** 扩展声明「我要开 WebView 调试通道」。开了等于把整个浏览器的控制权交给容器。 */
        const val CAP_HOST_WEBVIEW_DEBUG = "host:webview-debug"
    }
}
