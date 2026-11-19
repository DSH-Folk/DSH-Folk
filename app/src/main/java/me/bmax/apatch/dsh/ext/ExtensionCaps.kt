package me.bmax.apatch.dsh.ext

import android.content.Context

/**
 * 「有没有**已启用**的扩展声明了某个宿主能力」。
 *
 * ## 为什么能力要由扩展推导，而不是做成用户开关
 *
 * 用户想要的从来不是"一个开关"，而是"让某个扩展能操作浏览器"。把能力做成宿主开关会
 * 产生一个半生效状态：开关开着但没有扩展用、或者装了扩展但开关没开 —— 两种都让用户
 * 困惑"到底为什么不能用"。
 *
 * 以「已启用的扩展声明了它」为判据，语义就是用户真正做的那个动作：
 *  - 装扩展并启用 = 用户明确同意（启用前的信任确认里逐条列着它要的能力）
 *  - 禁用或卸载扩展 = 通道自动关闭，不需要记得再去关一个开关
 *
 * ## 为什么要单独一个对象
 *
 * 判据涉及读盘（每个扩展解析一次 manifest），而调用点（WebView 创建、桥配置写入）
 * 都不该关心这件事怎么算。收在一处也保证「哪些能力可以这样推导」有明确清单 ——
 * 不是任意能力都能，见 [WEBVIEW_DEBUG_CAP]。
 */
object ExtensionCaps {

    /**
     * 扩展声明「我要用 WebView 调试通道」的能力名。
     *
     * 与 [ExtensionHostImpl.CAP_HOST_WEBVIEW_DEBUG] 是同一个字符串 —— 那边是写入口，
     * 这边是读入口。两处必须一致，否则"扩展写了但宿主读不到"（或反过来）。
     */
    const val WEBVIEW_DEBUG_CAP = "host:webview-debug"

    /** 是否有已启用的扩展声明了 WebView 调试通道。 */
    fun hasWebviewDebug(ctx: Context): Boolean = hasCap(ctx, WEBVIEW_DEBUG_CAP)

    /**
     * 是否有已启用的扩展声明了 [cap]。
     *
     * **读盘失败一律当作"没有"**：这是安全相关的判定，宁可少开一个通道，
     * 也不要在读不到扩展列表时默认放行。
     */
    fun hasCap(ctx: Context, cap: String): Boolean = runCatching {
        val enabled = ExtensionRegistry.enabledIds(ctx)
        if (enabled.isEmpty()) return false
        ExtensionRegistry.listInstalled(ctx)
            .asSequence()
            .filter { it.enabled }
            .any { cap in it.manifest.declaredCaps }
    }.getOrDefault(false)
}
