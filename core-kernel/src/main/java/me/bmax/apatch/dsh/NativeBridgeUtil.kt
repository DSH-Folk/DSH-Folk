package me.bmax.apatch.dsh

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.bmax.apatch.res.R as CoreR
import me.bmax.apatch.util.appString
import org.json.JSONObject

/**
 * 原生桥的公共工具：前台判定、主线程调度、文案与错误体。
 *
 * ## 为什么从 DshNativeBridge 抽出来
 *
 * 这几项被桥的**每个**能力实现用到（通知、剪贴板、相机、媒体、特权命令……），
 * 但它们与「哪项能力」无关。留在 2637 行的 DshNativeBridge 里，能力实现无法
 * 独立成文件 —— 每个都会拖着这一整块。
 *
 * 抽成独立对象后，能力实现可以只 import 这个工具类，DshNativeBridge 只保留
 * 分发与路由。
 *
 * ## 可见性全是 public
 *
 * 因为使用方跨模块（:ext-device-caps / :ext-display / :app）。Kotlin 的 internal
 * 是模块级私有，跨模块不可见 —— 这是本仓库拆模块时反复踩的坑。
 */
object NativeBridgeUtil {

    /** 主线程操作的等待上限：桥接线程不能被 UI 卡死。 */
    const val MAIN_WAIT_MS = 3000L

    /** 文本参数的长度上限。 */
    const val MAX_TEXT_LEN = 4096

    /**
     * 「一次性授权」的有效期：用户点「允许一次」后，这项能力在这段时间内免再确认。
     *
     * 取 3 分钟：够 agent 完成一次连贯操作（点几下、读一屏），又不会长到让
     * 「允许一次」变成事实上的常开。
     */
    const val ONCE_TTL_MS = 180_000L

    /**
     * 进程是否有可见 Activity。
     *
     * 用 `getMyMemoryState` 而不是 ProcessLifecycleOwner：前者可以在任意线程读，而
     * 桥接请求跑在自己的连接线程上。前台服务给的是 IMPORTANCE_FOREGROUND_SERVICE(125)，
     * 只有真的有 Activity 在前面才是 IMPORTANCE_FOREGROUND(100) —— 正好是需要的区分。
     */
    fun isForeground(ctx: Context): Boolean = runCatching {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /**
     * 在主线程跑一段并等它结束（有超时，不让桥接线程被 UI 卡死）。
     *
     * @return true 表示 block 在超时内真的跑完了；false 表示主线程被卡住、[MAIN_WAIT_MS] 内没跑完
     *   —— 调用方**必须**按失败处理，否则会出现「剪贴板假装是空的」「toast 假装发了」这类
     *   桥契约明令禁止的静默假成功。
     */
    fun onMain(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return true
        }
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        return runCatching { latch.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    /** 文本参数：空串归为「没给」，并截断到 [MAX_TEXT_LEN]。 */
    fun text(v: String?): String? {
        if (v.isNullOrEmpty()) return null
        return if (v.length > MAX_TEXT_LEN) v.substring(0, MAX_TEXT_LEN) else v
    }

    fun okJson(): String = JSONObject().put("ok", true).toString()

    /**
     * 取一条本地化文案。
     *
     * 这些串会经 `dsh-native` 的 stderr 出现在**用户**眼前（agent 也读它，但 agent 认的是
     * [err] 里的 `reason`，不是这段人话），所以要跟随应用语言。走 `appString` 而不是
     * `ctx.getString`：这里的 Context 是 Application 的，而应用内语言在 API 33 以下
     * 只作用于 Activity。
     *
     * 取不到就退回资源名：桥不能因为一次资源查找失败而回 500，那会把「参数写错了」
     * 变成「宿主坏了」。
     */
    fun str(ctx: Context, resId: Int, vararg args: Any): String = runCatching {
        ctx.appString(resId, *args)
    }.getOrElse { ctx.resources.getResourceEntryName(resId) ?: "error" }

    /** 错误体带机器可读的 [reason]：agent 需要据此决定是重试还是提示用户。 */
    fun err(msg: String, reason: String): String = JSONObject()
        .put("ok", false)
        .put("error", msg)
        .put("reason", reason)
        .toString()

    /** 未知端点的统一回包。 */
    fun methodNotAllowed(ctx: Context, method: String, path: String): Pair<Int, String> =
        404 to err(
            str(ctx, CoreR.string.dsh_native_err_unknown_endpoint, method, path),
            "unknown_endpoint",
        )
}