package me.bmax.apatch.dsh.ext.seam
/**
 * 设备能力：无障碍、相机、TTS、设备感知、硬件监控、个人数据。
 *
 * 这些都需要运行时权限或系统服务授权，**每个方法都要能优雅失败**：
 * 扩展不该因为用户没给某个权限就崩掉整个宿主。
 */
interface DeviceSeam {

    /** 无障碍服务是否已开启。 */
    fun a11yEnabled(): Boolean

    /** 打开系统无障碍设置页（引导用户手动开启）。 */
    fun openA11ySettings()

    /** 执行一次无障碍点击/滑动动作。未开启时返回 false。 */
    fun a11yPerform(action: String): Boolean

    /** 是否有相机权限。 */
    fun cameraGranted(): Boolean

    /** TTS 是否可用。 */
    fun ttsAvailable(): Boolean

    /** 朗读一段文本。 */
    fun speak(text: String): Boolean

    /** 当前屏幕是否亮着 / 是否锁屏 / 电量等基础感知。 */
    fun screenOn(): Boolean

    /** 电池电量百分比；读不到返回 -1。 */
    fun batteryPercent(): Int

    /** 设备是否在充电。 */
    fun charging(): Boolean
}
