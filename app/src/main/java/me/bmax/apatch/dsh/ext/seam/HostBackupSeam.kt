package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import java.io.File
import me.bmax.apatch.BuildConfig
import me.bmax.apatch.dsh.DshConfigBackup

/**
 * [BackupSeam] 的宿主实现。
 *
 * ## 为什么导出/导入是同步的
 *
 * 宿主的备份走容器内回环 HTTP（`dsh-config-manager`），本来就是阻塞调用。
 * 把它包成 suspend 会让人以为它很轻 —— 实际它会卡住调用线程几百毫秒到几秒。
 * 保持同步签名，逼调用方自己切到 IO 线程（这是诚实：慢就是慢）。
 */
internal class HostBackupSeam(private val ctx: Context) : BackupSeam {

    override fun exportConfig(password: String): File? = runCatching {
        // 宿主没有"只导出、给我文件"的公开入口 —— 它的导出流程带 UI 进度。
        // 这里如实返回 null，而不是伪造一个文件或偷偷起一个 Activity。
        null
    }.getOrNull()

    override fun importConfig(archive: File, password: String): Boolean = runCatching {
        false
    }.getOrDefault(false)

    override fun checkUpdate() {
        // 更新检查在宿主里是带 UI 的流程（弹窗、下载、安装）。
        // seam 只暴露"触发"这一层；真正的交互仍归宿主。
        runCatching {
            me.bmax.apatch.util.UpdateChecker.openUpdateUrl(ctx)
        }
    }

    override fun currentVersion(): String = BuildConfig.VERSION_NAME
}
