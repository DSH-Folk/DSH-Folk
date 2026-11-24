package me.bmax.apatch.dsh

/**
 * 内核需要的、只能由宿主提供的东西。
 *
 * ## 两种注入
 *
 * **常量**（`install`）：`:core-kernel` 不能引用 `:app` 的 `BuildConfig`
 * （那是 :app 生成的）或 `APApplication`（Application 子类，带 UI/崩溃处理）。
 * 但内核确实需要版本号（更新检查）、APATCH_FOLDER（拼 PATH）、以及**同一份** prefs。
 *
 * **回调**（`setLogSink` / `setContainerExec`）：用来断环。
 *
 * ## 断的是什么环
 *
 * `DshEnv` 要写日志，而日志落地需要 `DshEnv.serverLog(ctx)`：
 *
 * ```
 * DshEnv → DshRuntime.appendLog → DshEnv.serverLog → DshEnv
 * ```
 *
 * 这个环把 `DshEnv` / `LogStore` / `ContainerRuntime` / `DshRuntimeEnv` /
 * `DshRuntimeConstants` / `DshRuntimeModels` / `AdbBridge` 一整批底层文件的
 * 依赖闭包撑到 57 个文件 —— 谁也没法单独下沉到 :core-kernel。
 *
 * 把「写一行日志」「在容器里跑条命令」降级成回调，环就断了：
 * 底层只认 `(String) -> Unit` / `(String, Long) -> String`，不关心最终实现。
 */
object KernelEnv {
    /** 宿主版本名（BuildConfig.VERSION_NAME），更新检查要比对远端 tag。 */
    @Volatile
    var appVersionName: String = ""
        private set

    /** 宿主版本号（BuildConfig.VERSION_CODE），启动迁移记录用。 */
    @Volatile
    var appVersionCode: Int = 0
        private set

    /** 是否 debug 构建（BuildConfig.DEBUG），决定 libsu 是否开 verbose 日志。 */
    @Volatile
    var isDebugBuild: Boolean = false
        private set

    /**
     * 宿主 applicationId（BuildConfig.APPLICATION_ID）。
     *
     * DshFileAccess 用它拼 `${applicationId}.documents` —— 那个 FileProvider authority
     * 必须与实际 manifest 一致，写死字符串在 debug/alpha 后缀包里会失配。
     */
    @Volatile
    var applicationId: String = ""
        private set

    /** APatch 数据目录（APApplication.APATCH_FOLDER），拼 PATH / BUSYBOX 用。 */
    @Volatile
    var apatchFolder: String = ""
        private set

    /**
     * 宿主 prefs 实例（APApplication.sharedPreferences）。
     *
     * 内核不能引用 `APApplication`，但两边必须用**同一份**文件 —— 否则启动迁移
     * 的记录写了下次启动看不见，每版都重跑一遍迁移。
     */
    @Volatile
    var hostPrefs: android.content.SharedPreferences? = null
        private set

    /**
     * 宿主 prefs 的文件名（APApplication.SP_NAME）。
     *
     * 兜底路径要用它自己开一份。**名字必须与宿主完全一致** —— 写错就是一个
     * 看起来正常、实际读另一份空文件的分支。
     */
    @Volatile
    var hostPrefsName: String = ""
        private set

    /** 是否已注入常量。 */
    val installed: Boolean get() = appVersionName.isNotEmpty() && apatchFolder.isNotEmpty()

    fun install(
        appVersionName: String,
        isDebugBuild: Boolean,
        apatchFolder: String,
        appVersionCode: Int = 0,
        hostPrefs: android.content.SharedPreferences? = null,
        hostPrefsName: String = "",
        applicationId: String = "",
    ) {
        this.applicationId = applicationId
        this.appVersionName = appVersionName
        this.isDebugBuild = isDebugBuild
        this.apatchFolder = apatchFolder
        this.appVersionCode = appVersionCode
        this.hostPrefs = hostPrefs
        this.hostPrefsName = hostPrefsName
    }

    // ── 断环用的回调 ──

    @Volatile
    private var logSink: ((String) -> Unit)? = null

    /**
     * 挂日志出口。由 `DshRuntime.init` 调用。
     *
     * 未注入时 `log` 退化为 logcat —— 启动最早那一段（扩展加载、安装恢复）
     * 恰恰是日志最要紧的地方，不能因为还没注入就什么都不写。
     */
    fun setLogSink(sink: (String) -> Unit) { logSink = sink }

    /** 写一行日志。 */
    fun log(line: String) {
        val sink = logSink
        if (sink != null) sink(line) else android.util.Log.i("DSH-Kernel", line)
    }

    @Volatile
    private var execInContainer: ((String, Long) -> String)? = null

    /**
     * 挂容器执行器。由 `DshRuntime.init` 调用。
     *
     * 断的是 `AdbBridge → DshRuntime` 这条边：AdbBridge 只是在容器里跑 adb
     * 命令，不该依赖整个运行时单例。
     */
    fun setContainerExec(exec: (bashCommand: String, timeoutMs: Long) -> String) {
        execInContainer = exec
    }

    /**
     * 宿主状态快照，供 `DshHostPrompt.writeFacts` 组装 host-facts.json。
     *
     * ## 为什么要注入
     *
     * 那几项事实（原生能力开关、提权时限、无障碍连接状态、"别看本应用"档位）
     * 都存在 `:app` 侧：`DshNativeBridge` 依赖 UI 层与 `:app` 的 R，
     * `A11yOwn` / `DshA11y` / `DshElevationRequests` 同理。内核够不着。
     *
     * 但写事实文件这件事**必须**在内核里（`DshFileAccess` 变更时就要重写，
     * 而 `DshFileAccess` 已下沉）。所以让宿主提供快照，内核只负责序列化。
     *
     * 未注入时返回空快照 —— 事实文件会缺这几项，但不会崩。
     */
    data class HostFacts(
        val nativeEnabled: Boolean = false,
        val nativeCaps: Map<String, String> = emptyMap(),
        val onceGrants: Map<String, String> = emptyMap(),
        val elevateTtlMs: Long = 0L,
        val onceTtlMs: Long = 0L,
        val a11yConnected: Boolean = false,
        val a11yOwnMode: String = "",
        val effectiveRuntimeId: String = "",
        val restrictMode: Boolean = false,
        val restrictedCaps: List<String> = emptyList(),
        /** 提权通道 JSON（PrivilegedShell.reach 的序列化）。null = 没有提权途径。 */
        val elevationJson: String? = null,
        /** root 通道是否已生效（= 用户启用了特权**且**首选是 root）。给特权相关 UI 用。 */
        val rootChannelActive: Boolean = false,
        /**
         * 设备上 su 是否真的能拿到 uid 0 —— 与「用户有没有启用特权通道」无关。
         *
         * ## 为什么要跟 [rootChannelActive] 分开
         *
         * [rootChannelActive] 是「特权通道」的概念：用户要在权限通道页启用它、并选 root，
         * 才算生效。但 **chroot 容器不需要那条通道** —— 它只需要「能不能拿到 uid 0」这个
         * 设备事实：它的启动脚本自己调 su（见 ContainerRuntime.Chroot.spawnArgv），
         * 完全不经过 PrivilegedShell 的通道判定。
         *
         * 两者混用会得到一个荒唐的结果：su 明明可用，chroot 却因为「用户没在权限通道页
         * 启用 root」而不可用 —— 而用户启用那条通道是为了让 agent 用特权命令，
         * 跟 chroot 容器能不能起没有关系。真机反馈要的就是这个隔离。
         */
        val rootUsable: Boolean = false,
        /** root shell 的真实路径（PrivilegedShell.resolveSuPath）。 */
        val suPath: String? = null,
    )

    @Volatile
    private var hostFactsProvider: ((android.content.Context) -> HostFacts)? = null

    fun setHostFactsProvider(p: (android.content.Context) -> HostFacts) { hostFactsProvider = p }

    fun hostFacts(ctx: android.content.Context): HostFacts =
        hostFactsProvider?.invoke(ctx) ?: HostFacts()

    /**
     * 在容器里跑命令并拿输出。
     *
     * 未注入时返回空串 —— 调用方按「没输出」处理，与命令失败的表现一致。
     * 不抛异常：这条路径在启动早期会被调用，抛出去会让调用方整个挂掉。
     */
    fun exec(bashCommand: String, timeoutMs: Long = 60_000L): String {
        val e = execInContainer ?: return ""
        return e(bashCommand, timeoutMs)
    }
}
