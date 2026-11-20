package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import android.content.Intent
import android.provider.Settings
import me.bmax.apatch.dsh.DshA11y
import me.bmax.apatch.dsh.DshCamera
import me.bmax.apatch.dsh.DshTts

/**
 * [DeviceSeam] 的宿主实现。
 *
 * ## 全部方法都不抛
 *
 * 这些能力都要用户授权。没授权是常态 —— 扩展要能问"能不能用"而不是撞异常。
 */
internal class HostDeviceSeam(private val ctx: Context) : DeviceSeam {

    override fun a11yEnabled(): Boolean = runCatching { DshA11y.connected() }.getOrDefault(false)

    override fun openA11ySettings() {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 执行一次无障碍动作。
     *
     * `action` 的取值与宿主无障碍桥一致：`click` / `tap` / `swipe` / `global`。
     * 目前只支持无参的 `global` 类动作；带坐标的动作需要扩展自己用 `DshA11y` 的完整 API，
     * 那是后续 seam 扩展的事 —— 现在不假装支持。
     */
    override fun a11yPerform(action: String): Boolean = runCatching {
        if (!DshA11y.connected()) return false
        DshA11y.global(action)
        true
    }.getOrDefault(false)

    override fun cameraGranted(): Boolean = runCatching { DshCamera.hasCamera(ctx) }.getOrDefault(false)

    override fun ttsAvailable(): Boolean = runCatching {
        // 宿主没有单独的"引擎就绪"查询。不能靠调 speak 探测 —— 那会真的发声。
        // 只确认系统有这个类；真要用时 speak() 会返回 409/500 让扩展知道失败了。
        Class.forName("android.speech.tts.TextToSpeech") != null
    }.getOrDefault(false)

    override fun speak(text: String): Boolean = runCatching {
        // 返回码是 HTTP 风格：200 才是成功（400 缺参数 / 409 正在朗读 / 5xx 引擎失败）。
        val (code, _) = DshTts.speak(ctx, mapOf("text" to text))
        code == 200
    }.getOrDefault(false)

    override fun screenOn(): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isInteractive
    }.getOrDefault(false)

    override fun batteryPercent(): Int = runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    override fun charging(): Boolean = runCatching {
        val i = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = i?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }.getOrDefault(false)
}
