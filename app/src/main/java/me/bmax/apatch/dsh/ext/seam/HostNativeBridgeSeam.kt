package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import java.io.File
import me.bmax.apatch.dsh.DshNativeBridge

/**
 * [NativeBridgeSeam] 的宿主实现：把调用直接转给 [DshNativeBridge]。
 *
 * 这一层没有任何逻辑，就是转发 —— 那是有意的。Seam 的价值在于「方向」：
 * `:ext-*` 依赖 `:ext-api` 的接口，而 `:app` 实现它，依赖方向变成
 * 扩展 → 接口 ← 宿主，不再是扩展 → 宿主。
 *
 * 如果在这里加逻辑（缓存、降级、格式转换），就等于在扩展与桥之间又插了一层
 * 需要维护的东西，而它的存在理由只是「让依赖方向对」。保持它薄。
 */
internal class HostNativeBridgeSeam(private val ctx: Context) : NativeBridgeSeam {
    override fun handle(
        ctx: Context,
        method: String,
        path: String,
        params: Map<String, String>,
    ): Pair<Int, String> = DshNativeBridge.handle(ctx, method, path, params)

    override fun isForeground(ctx: Context): Boolean = DshNativeBridge.isForeground(ctx)

    override fun str(ctx: Context, resId: Int, vararg args: Any): String =
        DshNativeBridge.str(ctx, resId, *args)

    override fun err(msg: String, reason: String): String = DshNativeBridge.err(msg, reason)

    override fun stageDir(ctx: Context): File = DshNativeBridge.stageDir(ctx)

    override fun stageGuestPath(name: String): String = DshNativeBridge.stageGuestPath(name)

    override fun trimStage(dir: File) = DshNativeBridge.trimStage(dir)
}
