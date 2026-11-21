package me.bmax.apatch.dsh.ext.seam

/**
 * 容器运行时：进程状态、命令执行、日志。
 *
 * ## 为什么这个 seam 最重要
 *
 * 它是**唯一**能让扩展真正"驱动容器"的入口。以前扩展通过 [AppExtensionPlatform] 反射
 * `DshRuntime` 拿到，而 `DshRuntime` 在 `me.bmax.apatch.dsh` 包里**没有 R8 keep** ——
 * release 包里方法名被混淆成 `a`/`b`，反射按名字找不到。
 *
 * 走这个接口是**编译期绑定**：R8 看得见调用关系，混淆不影响。
 *
 * ## 为什么状态用回调而不是 StateFlow
 *
 * `:ext-api` 不能依赖 `:app`，宿主的 `StateFlow<DshState>` 是 app 内部类型。
 * 想在 ext-api 里暴露 StateFlow 就得把 DshState 搬进 ext-api（那是宿主内部模型，
 * 搬过去等于永久冻结成公共 API），或者引入 `stateIn`（需要 CoroutineScope，
 * 而 scope 的生命周期该由调用方管，不该由 seam 持有）。
 *
 * 回调形式没有这些问题：扩展自己决定要不要转成 Flow（`callbackFlow` 一行就够）。
 */
interface RuntimeSeam {

    /** 当前状态快照。 */
    fun state(): RuntimeState

    /**
     * 观察状态变化。返回取消订阅的函数。
     *
     * 回调在**主线程**上调用（宿主的状态流在主线程更新）。扩展要做重活请自己切线程。
     */
    fun observe(handler: (RuntimeState) -> Unit): () -> Unit

    /** 执行一条命令，返回进程（调用方负责读流与 waitFor）。 */
    fun exec(bashCommand: String): Process

    /** 执行并收集**标准输出**。超时抛异常。 */
    fun execCapture(bashCommand: String, timeoutMs: Long = 60_000L): String

    /**
     * 流式执行：每读到一行就回调一次。
     *
     * 行按 `\n` 或 `\r` 切分 —— pnpm / apt 的进度行以 `\r` 结尾，
     * 只按 `\n` 切会攒到最后才吐（宿主那边踩过这个坑）。
     *
     * @return 完整输出（已拼好的全部行）
     */
    fun execStreaming(
        bashCommand: String,
        timeoutMs: Long = 60_000L,
        onLine: (String) -> Unit,
    ): String

    /** 写一行到扩展日志（会出现在宿主的日志界面里）。 */
    /**
     * 把运行时与当前 Context 绑定（首次使用前必须调一次）。
     *
     * 工作流引擎要自己起容器进程，而起进程前运行时必须已 attach ——
     * 否则 exec 会因拿不到 rootfs 路径而失败。宿主在 Application.onCreate
     * 里已经 attach 过，扩展再调一次是幂等的。
     */
    fun attach(context: android.content.Context)

    /**
     * 等运行时就绪（带超时）。
     *
     * @return true 表示在超时内真的跑起来了；false 表示超时或失败 ——
     *   调用方**必须**按失败处理。工作流里「等 DSH 起来再跑下一步」靠它，
     *   把 false 当成功会让后续节点在一个没起来的运行时上执行。
     */
    fun awaitRunning(timeoutMs: Long): Boolean

    /**
     * 启动运行时服务（不等待就绪）。
     *
     * 工作流的 autostart 节点用它：先起服务，再用 [awaitRunning] 等它真的可用。
     * 分成两步是因为「起」是异步的 —— 起完立刻判 running 会永远为假。
     */
    fun start(context: android.content.Context): Boolean

    /**
     * 短暂持有服务不被系统回收。
     *
     * 工作流跑长命令期间用：Android 可能在内存紧张时杀掉后台服务，
     * 而工作流正等着它的输出。hold 让它在这段时间内保持前台优先级。
     */
    fun hold(context: android.content.Context)

    fun log(message: String)
}

/** 运行时状态的最小投影。宿主自己的 `DshState` 字段更多，这里只暴露扩展真正需要的。 */
data class RuntimeState(
    /** 运行时文件是否已安装。 */
    val installed: Boolean,
    /** 是否正在运行。 */
    val running: Boolean,
    /** 是否正在下载/解压。 */
    val installing: Boolean,
    /** 安装进度 0..1。 */
    val progress: Float = 0f,
    /** 人可读的状态说明（可能是本地化文案）。 */
    val message: String = "",
)
