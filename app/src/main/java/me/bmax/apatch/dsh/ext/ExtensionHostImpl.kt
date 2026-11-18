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
        }

        override fun set(switch: HostSwitch, on: Boolean): Boolean {
            val cap = when (switch) {
                HostSwitch.BROWSER_MODE -> CAP_HOST_BROWSER_MODE
            }
            if (cap !in declaredCaps) {
                ExtLog.log("[" + id + "] 拒绝修改 " + switch.id + "：manifest 未声明 " + cap)
                return false
            }
            when (switch) {
                HostSwitch.BROWSER_MODE -> DshEnv.setBrowserMode(appContext, on)
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
    }
}
