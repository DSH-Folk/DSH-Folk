package me.bmax.apatch.dsh

import android.content.Context
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.update
import me.bmax.apatch.res.R as CoreR
import me.bmax.apatch.util.appString

/**
 * 日志与本地化字符串：`DshRuntime` 拆出来的第一块职责。
 *
 * ## 为什么先拆这一块
 *
 * 它耦合最低：只依赖 [DshKernelState]（Context 与状态）与 [DshEnv]/[LogStore]
 * 这些已下沉 `:core-kernel` 的底层。拆出来之后 `DshRuntime` 的 148 个函数
 * 少了 15 个，而调用点一个都不用改 —— `DshRuntime` 保留同名转发。
 *
 * ## 硬链接探测为什么在这里
 *
 * [supported] 的结果只服务 [hardlinkLogLine]（启动日志里的那一行）。
 * 拆到别处会让"谁负责写这行日志"和"谁负责探测"分家，反而更难追。
 *
 * 探测结果存在 [DshKernelState]，因为其他职责类（引导流程）也要读它。
 */
object DshLogging {

    private const val TAG = "DSH-Folk"

    /** 短名，避免每行都写 [DshKernelState]。 */
    private val K get() = DshKernelState

    /**
     * rootfs 所在文件系统是否支持真实硬链接。
     *
     * 支持时 proot 不加 `--link2symlink`：该扩展把 `link()` 目标改写成指向临时中间文件的
     * 符号链接，而 dsh 的 write 工具正是用 `link(临时文件, 目标)` 发布后立刻删临时目录 ——
     * 于是新建文件 100% 变悬空链接（write 报成功但读不出来）。
     *
     * 探测结果缓存在 [DshKernelState]：一次探测，全程复用（同步块保证并发只探一次）。
     */
    fun supported(): Boolean {
        K.hardlinkOk?.let { return it }
        synchronized(this) {
            K.hardlinkOk?.let { return it }
            val dir = DshEnv.rootfs(K.appContext).takeIf { it.isDirectory } ?: K.appContext.filesDir
            val src = File(dir, ".dshfolk-linkprobe")
            val dst = File(dir, ".dshfolk-linkprobe.hl")
            var ok = false
            var detail = ""
            try {
                dir.mkdirs()
                src.delete(); dst.delete()
                Files.write(src.toPath(), byteArrayOf('o'.code.toByte(), 'k'.code.toByte()))
                Files.createLink(dst.toPath(), src.toPath())
                ok = dst.isFile && dst.length() == 2L
                if (!ok) detail = str(CoreR.string.dsh_log_hardlink_unreadable)
            } catch (e: Throwable) {
                ok = false
                detail = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { src.delete() }
                runCatching { dst.delete() }
            }
            android.util.Log.i(TAG, "硬链接支持=$ok${if (detail.isEmpty()) "" else "（$detail）"}")
            K.hardlinkOk = ok
            K.hardlinkDetail = detail
            return ok
        }
    }

    /** 硬链接探测失败的原因；成功时为空。供启动日志记录用。 */
    fun detail(): String = K.hardlinkDetail

    fun tailLog(lines: Int = 200): String =
        if (K.appContextOrNull() != null) LogStore.named(DshEnv.serverLog(K.appContext)).tail(lines) else ""

    /** **上一次运行**的日志（见 [DshEnv.serverLogPrev]）：重启之后再采集也能回溯到之前的错误。 */
    fun tailPrevLog(lines: Int = 2000): String =
        if (K.appContextOrNull() != null) LogStore.named(DshEnv.serverLogPrev(K.appContext)).tail(lines) else ""

    /** 应用自己**全部**日志文件（当前那份 + 轮转出去的上一份 + 以后新加的）。 */
    fun appLogFiles(): List<java.io.File> =
        if (K.appContextOrNull() != null) DshEnv.logFiles(K.appContext) else emptyList()

    /**
     * 全部日志文件**合并排序后按时间窗口裁切**（见 [DshLogWindow]）。
     *
     * 与 [tailLog] 的区别是"按时间"而不是"按行数"、且跨文件：bugreport 的窗口选 10 分钟时，
     * 只有这一次运行的最后 10 分钟该进归档 —— 上一份里窗口内的那几行也要带上。
     */
    internal fun windowedLog(minutes: Int): DshLogWindow.Result = DshLogWindow.read(appLogFiles(), minutes)

    /**
     * 清空本次运行的日志 —— 但**先把上一份轮转成 [DshEnv.serverLogPrev]**。
     *
     * 这个函数在**每次起服务时**都会被调到，所以「重启之后再采集 bugreport」看到的永远只有
     * 本次运行的内容：真机上导入会话报错发生在 12:xx，14:24 的报告里 dsh.log 只有 1 KB
     * （就是那次启动之后的新内容），只能靠猜。留一份上一次运行的日志，问题才有可能回溯。
     */
    fun clearLog() {
        if (K.appContextOrNull() == null) return
        val log = DshEnv.serverLog(K.appContext)
        if (log.isFile && log.length() > 0L) {
            runCatching { log.copyTo(DshEnv.serverLogPrev(K.appContext), overwrite = true) }
        }
        LogStore.named(log).clear()
    }

    /**
     * 清空应用自己的**全部**日志文件（深度清理那条路用它）。
     *
     * 与 [clearLog] 的关系：clearLog 是**起服务时**用的，它把当前那份轮转进
     * [DshEnv.serverLogPrev] 再清空当前那份 —— 那是为了 bugreport 能回溯上一次运行，代价是
     * 磁盘上仍然留着全部内容（上一份），**不回收空间**。深度清理要的是真的清掉，所以这里：
     *
     * 1. 当前那份先走 [clearLog]（复用它同一条安全通道：flush 积压 → 关句柄 → 截断）；
     * 2. 轮转出去的那一份，以及 [DshEnv.logFiles] 里以后新增的任何 `*.log`，逐个走
     *    [LogStore.NamedLog.clear]。
     *
     * **绝不直接删文件**：这些文件被 [LogStore] 持有写句柄，`File.delete()` 之后后续输出会写进
     * 已被删除的 inode（内存里还在、盘上没了，而且新行永远不再落盘）。`NamedLog.clear` 先
     * flush、再关句柄、然后原地截断并把行数/体积基线归零，之后 append 自然重开 —— 正在被写的
     * 日志因此是"清空后继续写"，而不是"删掉再续写"。
     */
    fun clearAppLogs() {
        if (K.appContextOrNull() == null) return
        clearLog()
        for (f in appLogFiles()) LogStore.named(f).clear()
    }

    fun appendLog(line: String) {
        if (K.appContextOrNull() != null) {
            LogStore.named(DshEnv.serverLog(K.appContext)).append(line)
        } else {
            // Context 还没绑定时**不能什么都不做**：启动最早那一段（扩展加载、安装恢复）恰恰是
            // 故障最多、最需要日志的地方，静默丢弃会让「装上了但没生效」变成无从查起
            // （beta.238 的 shell 扩展不生效、dsh.log 里一条 [ext] 都没有，就是这么来的）。
            // 退到 logcat，至少 bugreport 的 logcat.txt 里还找得到。
            android.util.Log.i("DSH-Folk", line)
        }
    }

    /**
     * 硬链接状态的日志行，**带上探测失败的原因**。
     *
     * 只写结论是不够的：App 私有目录本该支持硬链接，报「不支持」是反常的，
     * 而 bugreport 里的 logcat 只覆盖最近几分钟、抓不到启动时那条 Log.i。
     * 把原因写进 dsh.log 才能在下一份 bugreport 里直接看到 —— 真机上就是靠这条
     * 才拿到 `AccessDeniedException`（系统不允许在 App 私有目录建硬链接）。
     *
     * 这里**不再**声称 pnpm 的导入方式：那是 [pnpmImportMethodLine] 的事，它读
     * 真实配置。v1.3 在这行里写「pnpm 已切为 copy 导入」，而那一版的配置压根没生效。
     */
    private fun hardlinkLogLine(): String {
        val ok = supported()
        val detail = detail()
        return buildString {
            append(str(if (ok) CoreR.string.dsh_log_hardlink_yes else CoreR.string.dsh_log_hardlink_no))
            if (detail.isNotEmpty()) append("（$detail）")
            append(" · ")
            append(
                when {
                    // proroot 无条件加 --link2symlink，探测结果在它下面不代表 guest 的实际能力。
                    // 用 effectiveRuntimeId：x86_64 上「选了 proroot」但跑的是 proot，
                    // 说成「proroot 无条件启用 l2s」与同一份日志第一行自相矛盾。
                    DshRuntime.effectiveRuntimeId() == "proroot" -> str(CoreR.string.dsh_log_hardlink_l2s_forced)
                    !ok -> str(CoreR.string.dsh_log_hardlink_l2s_on)
                    else -> str(CoreR.string.dsh_log_hardlink_l2s_off)
                }
            )
        }
    }

    fun fail(message: String) {
        appendLog("! $message")
        K.stateFlow.update { it.copy(phase = DshPhase.ERROR, message = message, speedBytesPerSec = 0L) }
    }

    /**
     * 取本地化字符串。
     *
     * 用 [me.bmax.apatch.util.appString] 而不是 `appContext.getString`：应用内语言在
     * API 33 以下只改 Activity 的 Configuration，Application 的 Context 仍然解析成
     * **系统语言**。少了这一层，界面切成英文之后首页状态和启动日志依旧是中文
     * （风味语言那几套皮更是一行都不会出现，它们永远不是系统语言）。
     */
    fun str(resId: Int, vararg args: Any): String =
        if (K.appContextOrNull() != null) K.appContext.appString(resId, *args) else ""

    /**
     * 写一行「进展」日志（`>` 前缀）。
     *
     * 与 [appendLog] 分开的意义：调用方只提供资源 id 与参数，不再拼中文字面量 ——
     * 日志同时进首页日志卡和 bugreport 的 dsh.log，两处都该跟随应用语言。
     */
    fun logInfo(resId: Int, vararg args: Any) = appendLog("> " + str(resId, *args))

    /** 写一行「异常」日志（`!` 前缀）。 */
    fun logWarn(resId: Int, vararg args: Any) = appendLog("! " + str(resId, *args))

    internal fun joinForLog(items: Collection<String>): String = DshRuntimeEnv.joinForLog(items)

    /**
     * 下载源的本地化名字。
     *
     * 走 [DshSource.labelRes] —— 与设置页、更新对话框同一份映射。日志此前用的是
     * DshSource 里一份硬编码中文的 displayName()，现在那个函数已经不存在了。
     */
    fun sourceName(source: String): String = str(DshSource.labelRes(source))

    /**
     * 让一段文本能安全地放进 shell 的单引号里。
     *
     * 这段文本现在来自资源，翻译里出现 `'` 完全正常（英文 "don't"、法语 "l'app"），
     * 而一个裸单引号会提前闭合字符串、把后面的内容变成 shell 代码。`'\''` 是 POSIX 里
     * 唯一可靠的写法：闭合、插入转义的引号、再打开。
     */
    private fun shellSingleQuoted(s: String): String = s.replace("'", "'\\''")
}
