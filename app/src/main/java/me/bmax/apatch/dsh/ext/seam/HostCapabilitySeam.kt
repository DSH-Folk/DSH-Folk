package me.bmax.apatch.dsh.ext.seam

import me.bmax.apatch.BuildConfig

/**
 * [CapabilitySeam] 的宿主实现：能力自省。
 *
 * ## 为什么需要
 *
 * 扩展是**独立编译**的。装了新版宿主之后它可能能用上更多能力；
 * 反过来，把扩展装到老宿主上时它不该假设某个能力一定在。
 * 硬编码假设的结果是"在老宿主上崩"或"在新宿主上白不用"。
 */
internal class HostCapabilitySeam : CapabilitySeam {

    override fun declared(): List<String> = CAPABILITIES

    override fun available(name: String): Boolean = CAPABILITIES.contains(name)

    override fun hostVersion(): String = BuildConfig.VERSION_NAME

    private companion object {
        /**
         * 宿主声明的能力清单。
         *
         * 与 seam 接口一一对应 —— **加 seam 就要加这里**，否则扩展问"有没有"会得到否，
         * 然后它自己降级，而宿主明明支持。
         */
        val CAPABILITIES = listOf(
            "host:runtime",
            "host:shell",
            "host:fs",
            "host:device",
            "host:ui",
            "host:backup",
            "host:event",
            "host:capability",
            // 扩展体系自身的两项（不是 seam，但扩展常问）
            "host:webview-debug",
            "host:browser-mode",
        )
    }
}
