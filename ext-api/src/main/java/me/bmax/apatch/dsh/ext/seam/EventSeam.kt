/**
 * 宿主事件：扩展订阅、宿主广播。
 *
 * 事件名用字符串常量而不是枚举 —— 扩展是独立编译的，枚举加一个值就要重编所有扩展。
 * 常量集中在 [Events] 里，宿主与扩展共用同一份字面量。
 */
interface EventSeam {

    /** 订阅一个事件。返回取消订阅的函数。 */
    fun subscribe(event: String, handler: (Map<String, Any?>) -> Unit): () -> Unit

    /** 广播一个事件给其它扩展与宿主。 */
    fun emit(event: String, payload: Map<String, Any?> = emptyMap())
}

/** 宿主事件名。加新事件时**只加不改**（改名等于让老扩展静默失效）。 */
object Events {
    /** 运行时状态变化。payload: installed / running / progress */
    const val RUNTIME_STATE = "runtime.state"

    /** 容器就绪（rootfs 可用）。payload: rootfsDir */
    const val CONTAINER_READY = "container.ready"

    /** 扩展被启用。payload: id */
    const val EXTENSION_ENABLED = "extension.enabled"

    /** 扩展被停用。payload: id */
    const val EXTENSION_DISABLED = "extension.disabled"

    /** 主题变化。payload: dark / accent */
    const val THEME_CHANGED = "theme.changed"
}
