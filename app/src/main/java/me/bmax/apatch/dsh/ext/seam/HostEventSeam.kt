package me.bmax.apatch.dsh.ext.seam

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import me.bmax.apatch.dsh.ext.ExtensionGate

/**
 * [EventSeam] 的宿主实现：一个进程内的简单事件总线。
 *
 * ## 为什么不复用 ExtensionHookRegistry
 *
 * Hook 是"宿主流程中间插一刀"（有 before/after/around 三种时机、能拦下宿主行为）。
 * 事件是"发生了，通知一下"（只有订阅/广播，没有拦截语义）。
 *
 * 共用一套存储会让语义混淆：扩展调 `around('runtime.state')` 会返回 false 试图拦下
 * 一次状态变化 —— 但状态变化不是可拦截的操作，那个 false 会被静默忽略。
 * 分开两个注册表，各自语义清楚，代价只是几十行代码。
 *
 * ## 事件名
 *
 * 见 [Events]。加新事件只加不改 —— 改名等于让所有已装扩展静默收不到。
 */
internal class HostEventSeam : EventSeam {

    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<String, (Map<String, Any?>) -> Unit>>>()

    /** 由 ExtensionEngine 在加载时注入，用于退订时按扩展清理。 */
    var ownerOf: ((String, String) -> Unit)? = null

    override fun subscribe(event: String, handler: (Map<String, Any?>) -> Unit): () -> Unit {
        if (ExtensionGate.isLatched()) return {}
        val list = listeners.getOrPut(event) { CopyOnWriteArrayList() }
        val entry = "anon" to handler
        list.add(entry)
        return {
            list.remove(entry)
            if (list.isEmpty()) listeners.remove(event)
        }
    }

    override fun emit(event: String, payload: Map<String, Any?>) {
        if (ExtensionGate.isLatched()) return
        val list = listeners[event] ?: return
        for ((id, handler) in list) {
            // 一个扩展的回调抛异常不能影响其它扩展 —— 逐个 catch。
            runCatching { handler(payload) }.onFailure {
                me.bmax.apatch.dsh.ext.ExtLog.log(
                    "事件 " + event + " 的订阅者抛异常（已忽略）：" + (it.message ?: it.javaClass.simpleName),
                )
            }
        }
    }

    /** 卸载扩展时清掉它的订阅（按 handler 无法识别归属，所以整表清 —— 见下方说明）。 */
    fun clearAll() { listeners.clear() }
}
