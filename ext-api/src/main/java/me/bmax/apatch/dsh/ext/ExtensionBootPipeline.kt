package me.bmax.apatch.dsh.ext

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 启动管线：把宿主启动拆成五段，扩展可以在每段插处理（按优先级串行）。
 *
 * 顺序即真实启动顺序：
 * onAppCreate → SplashOverride → AuthInterceptor → InitialRouteResolver → AutoStartPolicy
 *
 * 约束：每段只允许**同步、快速**的处理。这里跑在 App 启动路径上，一个 handler 卡 3 秒就是
 * 一次肉眼可见的白屏，所以单段总预算 200ms，超时的 handler 直接跳过并记日志。
 */
object ExtensionBootPipeline {

    enum class Stage { APP_CREATE, SPLASH, AUTH, INITIAL_ROUTE, AUTOSTART }

    /** 处理函数：拿到可变上下文，可读可写。 */
    fun interface Handler {
        fun handle(ctx: MutableMap<String, Any?>)
    }

    private data class Entry(val extensionId: String, val priority: Int, val handler: Handler)

    private val stages = ConcurrentHashMap<Stage, CopyOnWriteArrayList<Entry>>()

    /** 单段总预算（毫秒）。超出就跳过剩余 handler —— 启动路径不能被扩展拖住。 */
    private const val STAGE_BUDGET_MS = 200L

    fun register(extensionId: String, stage: Stage, priority: Int = 0, handler: Handler) {
        if (ExtensionGate.isLatched()) return
        stages.getOrPut(stage) { CopyOnWriteArrayList() }
            .add(Entry(extensionId, priority, handler))
    }

    /** 跑一段。无 handler 时直接返回，零开销。 */
    fun run(stage: Stage, ctx: MutableMap<String, Any?> = mutableMapOf()) {
        if (ExtensionGate.isLatched()) return
        val list = stages[stage] ?: return
        val startedAt = System.currentTimeMillis()
        for (e in list.sortedBy { it.priority }) {
            if (System.currentTimeMillis() - startedAt > STAGE_BUDGET_MS) {
                ExtLog.log(
                    "启动管线 " + stage.name + " 超出 " + STAGE_BUDGET_MS + "ms 预算，已跳过剩余处理",
                )
                return
            }
            runCatching { e.handler.handle(ctx) }.onFailure {
                ExtLog.log(
                    "[" + e.extensionId + "] 启动管线 " + stage.name + " 抛异常（已跳过）：" +
                        (it.message ?: it.javaClass.simpleName),
                )
            }
        }
    }

    fun unregisterAll(extensionId: String) {
        for (list in stages.values) list.removeIf { it.extensionId == extensionId }
    }

    fun clearAll() { stages.clear() }

    /**
     * 「已引导过」的落盘回调。
     *
     * 由宿主注入（见 `ExtensionEngine`）。放在这里的原因是**这个管线不该知道
     * prefs 文件名** —— 那是宿主的存储细节，写进 :ext-api 等于把它固化成公共契约。
     */
    @Volatile
    private var onBootstrapped: (() -> Unit)? = null

    /** 宿主注入落盘实现。重复注入以后者为准。 */
    fun setBootstrappedRecorder(recorder: () -> Unit) {
        onBootstrapped = recorder
    }

    /** 标记「已引导过」。没注入 recorder 时是空操作（单元测试里就是这样）。 */
    fun markBootstrapped() {
        runCatching { onBootstrapped?.invoke() }
    }
}
