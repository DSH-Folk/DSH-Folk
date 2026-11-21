package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import android.net.Uri
import me.bmax.apatch.ui.theme.ThemeIO
import me.bmax.apatch.ui.theme.ThemeManager

/**
 * [ThemeBackupSeam] 的宿主实现：转给 [ThemeManager] / [ThemeIO]。
 *
 * 薄转发。它存在的唯一理由是让备份模块不必依赖整个 `ui.theme`。
 */
internal object HostThemeBackupSeam : ThemeBackupSeam {
    override fun measureThemeZipBytes(ctx: Context, meta: ThemeBackupSeam.ThemeMetadata): Long =
        // 三个 Theme 方法都是 suspend（内部切 IO、可能要压缩几十 MB）。
        // seam 接口是同步的：调用方（备份管线）本来就是阻塞式的一步步走，
        // 给它 suspend 会逼整条链改成协程。runBlocking 桥一下，语义是「等它算完」。
        runCatching { kotlinx.coroutines.runBlocking { ThemeIO.measureThemeZip(ctx, meta.toHost()) } }
            .getOrDefault(-1L)

    override fun exportTheme(
        ctx: Context,
        target: Uri,
        meta: ThemeBackupSeam.ThemeMetadata,
    ): Boolean = runCatching {
        kotlinx.coroutines.runBlocking { ThemeManager.exportTheme(ctx, target, meta.toHost()) }
    }.getOrDefault(false)

    override fun importTheme(ctx: Context, source: Uri): Boolean =
        runCatching { kotlinx.coroutines.runBlocking { ThemeManager.importTheme(ctx, source) } }
            .getOrDefault(false)

    private fun ThemeBackupSeam.ThemeMetadata.toHost() = ThemeManager.ThemeMetadata(
        name = name,
        type = type,
        version = version,
        author = author,
        description = description,
    )
}
