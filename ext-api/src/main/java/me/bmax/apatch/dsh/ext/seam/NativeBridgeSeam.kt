package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import java.io.File

/**
 * 原生桥的极薄门面。
 *
 * ## 为什么需要它
 *
 * `DshNativeBridge` 是一个 2637 行的单 object，内部高度互锁（能力实现调用开关判定，
 * 开关判定调用权限查询，权限查询又用 Special 枚举）。按「分区」硬切会切出循环依赖，
 * 在没有本地 JDK 的环境里代价极高（试过，连续 4 轮 CI 失败）。
 *
 * 而真正卡住 `:ext-workflow` / `:ext-display` 收口的，不是「桥的内部结构」，
 * 而是它们需要**调用**桥的几个方法却够不着（桥在 :app）。
 *
 * 所以换个做法：不拆桥，只在桥前面放一层薄接口。桥原样留在 :app（它内部怎么组织
 * 是它自己的事），扩展通过本接口调用它。这样：
 * - `:ext-*` 拿到单向依赖，不用反向依赖 :app；
 * - 桥的内部结构零改动，不再烧 CI；
 * - 接口只有 7 个方法，是真正「用到什么暴露什么」，不是把整个桥搬出来。
 *
 * ## 为什么不直接暴露 DshNativeBridge
 *
 * `:ext-api` 不能依赖 `:app`（架构硬约束），而 `DshNativeBridge` 在 `:app` 里。
 * 接口必须定义在 `:ext-api`，由宿主侧实现并注册进 [SeamRegistry]。
 *
 * ## 加新方法时的纪律
 *
 * 只加**扩展真实调用到**的方法。本接口一旦变胖，就等于把桥的耦合搬到了扩展侧，
 * 那时再想解耦会比现在难得多。
 */interface NativeBridgeSeam {

    /**
     * 分派一次原生能力请求。
     *
     * 这是桥的**唯一入口**：扩展按 path 提请求，桥内部做权限档校验、审计、分发。
     * 扩展不需要知道桥怎么实现，也不该知道 —— 那是宿主的安全边界。
     *
     * @return HTTP 状态码 + 响应体（JSON 字符串）。扩展应把状态码原样传回上游，
     *   不要自己改写：403 与 500 对 agent 的意义完全不同（前者「用户没授权」，
     *   后者「宿主坏了」）。
     */
    fun handle(ctx: Context, method: String, path: String, params: Map<String, String>): Pair<Int, String>

    /**
     * 进程是否有可见 Activity。
     *
     * 剪贴板、toast、分享、拨号这些能力都要求前台：后台偷偷弹 toast 或读剪贴板，
     * 用户看不见也拦不住，那是恶意软件的行为。扩展自己也可能要判（比如决定是否
     * 先提示用户切前台），所以暴露出来。
     */
    fun isForeground(ctx: Context): Boolean

    /** 取一条本地化文案（跟随应用内语言）。取不到时退回资源名，不抛异常。 */
    fun str(ctx: Context, resId: Int, vararg args: Any): String

    /** 构造错误体：带机器可读的 `reason`，agent 据此决定重试还是提示用户。 */
    fun err(msg: String, reason: String): String

    /**
     * 原生暂存目录（rootfs 之外的共享区）。
     *
     * 媒体抓取与虚拟屏截图都往这里落文件，再经 [stageGuestPath] 换成容器内路径
     * 交给 CLI 读。两个能力共用同一个目录是有意的：它们在语义上都是「宿主帮容器
     * 暂存一份大文件」，分开建目录只会让清理逻辑变复杂。
     */
    fun stageDir(ctx: Context): File

    /** 暂存文件名 → 容器内可见路径。 */
    fun stageGuestPath(name: String): String

    /**
     * 清掉暂存目录里的旧文件。
     *
     * 不清理的后果不是磁盘涨（每次都会覆盖同名文件），而是**陈旧文件被误读**：
     * 上一次的截图还在，这次抓取失败时上游可能拿到旧图而以为成功了。
     */
    fun trimStage(dir: File)
}
