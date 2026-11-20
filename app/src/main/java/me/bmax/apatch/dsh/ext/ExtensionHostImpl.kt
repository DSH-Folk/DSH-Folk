package me.bmax.apatch.dsh.ext

import android.content.Context
import me.bmax.apatch.dsh.DshEnv

/**
 * [DshExtensionHost] 的宿主实现。
 *
 * 比接口多一样东西：[platform]（36 项宿主能力门面）。它**故意不放进 :ext-api** —— 那些门面
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

    /** 往容器里装命令。归属记录落盘，卸载时由 [AppExtensionContainer.removeAllOf] 收回。 */
    override val container: ExtensionContainer by lazy {
        AppExtensionContainer(appContext, id)
    }

    /** 只有 App 内扩展拿得到；第三方扩展编译时看不到这个属性。 */
    val platform: DshPlatform by lazy { DshPlatform(appContext, id) }

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
