package me.bmax.apatch.dsh

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * 可写的「下载」目录。
 *
 * 优先公共 Download（用户能用文件管理器直接看到），但 Android 10 起的分区存储让
 * 那里默认写不进去 —— 而 getExternalStoragePublicDirectory **不会**抛异常，
 * 只是后续 mkdirs/写文件静默失败。所以这里真去建一次目录探测，失败就退回
 * 应用专属外部目录（Android/data/<pkg>/files/Download，无需任何权限）。
 */
fun getSafeDownloadsDir(context: Context): File {
    val fallback = {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Download")
    }
    return try {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val allFiles = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        if (allFiles && (public.isDirectory || public.mkdirs())) public else fallback()
    } catch (_: SecurityException) {
        fallback()
    }
}
