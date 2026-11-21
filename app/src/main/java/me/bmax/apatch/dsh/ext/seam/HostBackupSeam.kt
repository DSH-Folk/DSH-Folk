package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import java.io.File
import kotlinx.coroutines.runBlocking
import me.bmax.apatch.dsh.DshConfigBackup
import me.bmax.apatch.dsh.KernelEnv
import me.bmax.apatch.dsh.backup.BackupScope
import me.bmax.apatch.dsh.backup.ExportPlan

/**
 * [BackupSeam] 的宿主实现。
 *
 * ## 为什么导出/导入是同步的
 *
 * 宿主的备份走容器内回环 HTTP（`dsh-config-manager`），本来就是阻塞调用。
 * 把它包成 suspend 会让人以为它很轻 —— 实际它会卡住调用线程几百毫秒到几秒。
 * 保持同步签名，逼调用方自己切到 IO 线程（这是诚实：慢就是慢）。
 *
 * ## 为什么用 runBlocking 而不是返回 null/false
 *
 * 早期版本这里如实返回 null（"宿主没有只导出、给我文件的公开入口"）。
 * 那个判断当时是对的 —— 导出流程确实带 UI 进度。但 `DshConfigBackup.exportArchive`
 * 后来抽成了**纯逻辑入口**（`DshCloudAppData` 已经在用它），不再绑 UI。
 * 于是这里可以给真实现：扩展调用 `exportConfig` 能真的拿到一个加密归档文件。
 *
 * `runBlocking` 在这里是可接受的：seam 的调用方本来就是扩展的后台线程，
 * 而签名已经是同步的（见上一段）。用 `runBlocking` 只是把 suspend 函数桥接过来，
 * 不引入额外线程切换。
 */
internal class HostBackupSeam(private val ctx: Context) : BackupSeam {

    /**
     * 导出 DSH 配置为加密归档。
     *
     * 范围固定 [BackupScope.DSH_ONLY]：扩展要的是「DSH 侧配置」，不是整个 App 数据
     * （后者包含 App 自己的 prefs、主题包，那是宿主内部的事，不该由扩展经 seam 取走）。
     * 会话不带（`sessionLimit = 0`）：会话体积能到几百 MB，扩展不该因为一次备份调用
     * 就把用户的全部对话记录拖出来。
     *
     * 密码为空时**不加密** —— 但 seam 的契约是"加密归档"，所以空密码直接拒绝，
     * 而不是悄悄产出一个明文包（那会让调用方以为它拿到了加密文件）。
     */
    override fun exportConfig(password: String): File? {
        if (password.isEmpty()) return null
        val plan = ExportPlan(
            scope = BackupScope.DSH_ONLY,
            sessionLimit = 0,
            password = password,
            includesTheme = false,
        )
        val result = runCatching {
            runBlocking { DshConfigBackup.exportArchive(ctx, plan) }
        }.getOrNull()
        return if (result?.ok == true) result.file else null
    }

    /**
     * 从加密归档恢复。
     *
     * 用 `merge` 策略 + `rollbackOnError = true`：扩展触发的恢复不该是破坏性的 ——
     * 合并保留用户现有配置，出错则整体回滚。用户要"覆盖式恢复"时走设置页，
     * 那条路有完整的预检与冲突确认。
     */
    override fun importConfig(archive: File, password: String): Boolean {
        if (!archive.isFile) return false
        return runCatching {
            runBlocking {
                DshConfigBackup.import(
                    ctx = ctx,
                    zip = archive,
                    strategy = "merge",
                    rollbackOnError = true,
                    password = password,
                ).ok
            }
        }.getOrDefault(false)
    }

    override fun checkUpdate() {
        // 更新检查在宿主里是带 UI 的流程（弹窗、下载、安装）。
        // seam 只暴露"触发"这一层；真正的交互仍归宿主。
        runCatching {
            me.bmax.apatch.util.UpdateChecker.openUpdateUrl(ctx)
        }
    }

    override fun currentVersion(): String = KernelEnv.appVersionName
}