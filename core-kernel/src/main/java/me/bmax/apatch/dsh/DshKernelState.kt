package me.bmax.apatch.dsh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * `DshRuntime` 拆成多个职责类之后，它们共用的那一小块状态。
 *
 * ## 为什么要单独抽出来
 *
 * 拆分前 `DshRuntime` 是一个 3487 行的 object，148 个函数里有 83 处读 `appContext`、
 * 51 处写 `_state`。如果每个职责类各自持有一份，就会出现「装到一半的进度只有
 * 安装器知道、首页看不到」这类问题 —— 状态必须**只有一份**。
 *
 * 这个类就是那一份。各职责类通过它读写，不再各自 `lateinit var appContext`。
 *
 * ## 为什么不是 interface + 多实现
 *
 * 这里没有多实现的需求，只有"一处写、多处读"。做成普通 object 更直白，
 * 也避免为每个字段写一遍委托。
 *
 * ## `ready` 的意义
 *
 * `appContext` 在 [APatchApp.onCreate] 之前是未绑定的。所有读接口都要能在
 * 未绑定时给默认值而不是抛异常 —— 首页第一次 composition 就跑在这个窗口里
 * （真机实测：早先版本在这里撞 UninitializedPropertyAccessException）。
 */
object DshKernelState {

    @Volatile
    private var _appContext: Context? = null

    /** 宿主 Context。未绑定时抛 —— 只在确定已绑定处用。 */
    val appContext: Context
        get() = _appContext ?: error("DshKernelState.appContext 未绑定：APatchApp.onCreate 里要先调 bind()")

    /** Context 是否已绑定。未绑定时读接口给默认值。 */
    val ready: Boolean get() = _appContext != null

    /** 未绑定返回 null，调用方自己决定怎么处理。 */
    fun appContextOrNull(): Context? = _appContext

    fun bind(context: Context) {
        if (_appContext == null) _appContext = context.applicationContext
    }

    /** 运行状态。**全仓库唯一一份**。 */
    val stateFlow = MutableStateFlow(DshState())

    val state: StateFlow<DshState> = stateFlow.asStateFlow()

    val current: DshState get() = stateFlow.value

    fun setState(update: (DshState) -> DshState) {
        stateFlow.update(update)
        // 广播给扩展。
        //
        // 放在这里而不是各调用点：`setState` 是**唯一**的状态写入口（全仓库 51 处写状态
        // 都走它），在这里发就不会漏。散在各处发的话，新增一个状态转换点就少一次广播，
        // 而漏掉的表现是"扩展偶尔收不到" —— 最难查的那种。
        //
        // core-kernel 不能依赖 :app 的 HostEvents（依赖方向相反），所以这里用回调注入：
        // 宿主在 Application.onCreate 里把自己那份实现塞进来。
        stateListener?.invoke(stateFlow.value)
    }

    /**
     * 状态变化的旁路监听（宿主用它给扩展广播事件）。
     *
     * 为什么是可变回调而不是构造参数：core-kernel 是被 :app 依赖的底层模块，
     * 它没法反向引用 :app 里的 HostEvents。用回调把依赖倒过来。
     */
    @Volatile
    private var stateListener: ((DshState) -> Unit)? = null

    fun setStateListener(listener: ((DshState) -> Unit)?) {
        stateListener = listener
    }

    /**
     * 宿主 prefs（`config`）。
     *
     * 名字必须与 [APatchApp.SP_NAME] 一致 —— 早先 KernelEnv 里硬编码过一个
     * 别的名字，结果读的是另一份空文件，迁移记录写了下次启动看不见，每版重跑。
     * 这里统一走 DshEnv.PREF（与 DshRuntime.prefs() 同一个常量）。
     */
    /**
     * 当前 WebUI 端口。
     *
     * 放这里而不是 DshRuntime：:ext-backup / :ext-display 都要拼
     * `http://127.0.0.1:<port>/...` 去访问容器内的 dsh 服务，而 DshRuntime 在 :app
     * 它们够不着。端口存在 prefs 里、只依赖 appContext，本来就不该待在 :app。
     *
     * 越界值（0 / 负数 / >65535）回落到默认端口 —— prefs 是用户可改的文本，
     * 存进一个非法值时应该能用默认值继续跑，而不是让整个 WebUI 打不开。
     */
    fun port(): Int {
        if (!ready) return DshEnv.DEFAULT_PORT
        return prefs().getInt(DshEnv.KEY_PORT, DshEnv.DEFAULT_PORT).let {
            if (it in 1..65535) it else DshEnv.DEFAULT_PORT
        }
    }

    fun prefs(): android.content.SharedPreferences =
        appContext.getSharedPreferences(DshEnv.PREF, android.content.Context.MODE_PRIVATE)

    /**
     * 引导/重启/重装共用的串行锁：三者都会动 rootfs 与服务进程。
     *
     * 放共享状态里而不是各职责类各持一份 —— 两份锁等于没有锁，
     * 两条路径仍会同时动 rootfs。
     */
    val bootMutex = kotlinx.coroutines.sync.Mutex()

    /** 运行时更新检查的锁（避免自动检查与手动检查同时打 GitHub API）。 */
    val updateCheckMutex = kotlinx.coroutines.sync.Mutex()

    /** 长期存活的 IO 作用域（引导、安装、重启都在上面跑）。 */
    val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    // ── 服务进程句柄 ──

    @Volatile
    var serverProcess: Process? = null

    @Volatile
    var startedAt: Long = 0L

    // ── 硬链接探测结果（一次探测，全程复用）──

    @Volatile
    var hardlinkOk: Boolean? = null

    @Volatile
    var hardlinkDetail: String = ""
}
