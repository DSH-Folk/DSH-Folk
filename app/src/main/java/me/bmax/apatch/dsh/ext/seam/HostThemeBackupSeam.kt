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
        runCatching { ThemeIO.measureThemeZip(ctx, meta.toHost()) }.getOrDefault(-1L)

    override fun exportTheme(
        ctx: Context,
        target: Uri,
        meta: ThemeBackupSeam.ThemeMetadata,
    ): Boolean = runCatching { ThemeManager.exportTheme(ctx, target, meta.toHost()) }.getOrDefault(false)

    override fun importTheme(ctx: Context, source: Uri): Boolean =
        runCatching { ThemeManager.importTheme(ctx, source) }.getOrDefault(false)

    private fun ThemeBackupSeam.ThemeMetadata.toHost() = ThemeManager.ThemeMetadata(
        name = name,
        type = type,
        version = version,
        author = author,
        description = description,
    )
}
