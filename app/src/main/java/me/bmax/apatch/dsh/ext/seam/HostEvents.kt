package me.bmax.apatch.dsh.ext.seam

import me.bmax.apatch.dsh.ext.seam.Events

/**
 * 宿主向扩展广播事件。
 *
 * ## 为什么需要这个薄封装
 *
 * [HostEventSeam] 是 internal 的，而发事件的地方分散在 `DshRuntime`（core-kernel 之外）、
 * `ExtensionEngine`、`ThemeManager` 等处。让每处都去摸 seam 注册表既啰嗦，又容易忘掉
 * "熔断时不该发"这条规则（`HostEventSeam.emit` 内部已经判了，但调用方不知道）。
 *
 * 这里集中成一个入口：**发事件失败永远不影响主流程**。事件是"顺便通知"，不是
 * 业务的一部分 —— 一个订阅者写坏不该让容器起不来。
 *
 * ## 历史：这些事件曾经全部是死代码
 *
 * `EventSeam.Events` 里定义的 5 个常量，在补上本文件之前**一次都没有被 emit 过**。
 * 表现是扩展按文档订阅了 `RUNTIME_STATE`，回调永远不触发，而且没有任何报错 ——
 * 最难查的那种"静默失效"。桌宠扩展第一版就是这么踩空的（改用 `RuntimeSeam.observe`
 * 才拿到状态）。
 *
 * 所以本文件存在的意义不只是"少写几行"：它让"事件确实有人发"这件事在代码里可见。
 */
object HostEvents {

    /** 运行时状态变化。payload: installed / running / installing / progress / message */
    fun runtimeState(
        installed: Boolean,
        running: Boolean,
        installing: Boolean,
        progress: Float,
        message: String,
    ) = fire(Events.RUNTIME_STATE) {
        mapOf(
            "installed" to installed,
            "running" to running,
            "installing" to installing,
            "progress" to progress,
            "message" to message,
        )
    }

    /** 容器就绪（rootfs 可用）。payload: rootfsDir */
    fun containerReady(rootfsDir: String) = fire(Events.CONTAINER_READY) {
        mapOf("rootfsDir" to rootfsDir)
    }

    /** 扩展被启用。payload: id */
    fun extensionEnabled(id: String) = fire(Events.EXTENSION_ENABLED) {
        mapOf("id" to id)
    }

    /** 扩展被停用。payload: id */
    fun extensionDisabled(id: String) = fire(Events.EXTENSION_DISABLED) {
        mapOf("id" to id)
    }

    /** 主题变化。payload: dark / accent */
    fun themeChanged(dark: Boolean, accent: String) = fire(Events.THEME_CHANGED) {
        mapOf("dark" to dark, "accent" to accent)
    }

    /**
     * 统一的发送入口。
     *
     * 取不到 seam 提供者时静默跳过：那意味着扩展子系统还没初始化（App 刚启动、
     * 或处于安全模式），此时没有任何订阅者，发出去也没人收。
     */
    private inline fun fire(event: String, payload: () -> Map<String, Any?>) {
        runCatching {
            SeamRegistry.event().emit(event, payload())
        }
    }
}
