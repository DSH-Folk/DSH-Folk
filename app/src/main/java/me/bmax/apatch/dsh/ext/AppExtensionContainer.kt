package me.bmax.apatch.dsh.ext

import android.content.Context
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshRuntimeFiles
import java.io.File

/**
 * [ExtensionContainer] 的宿主实现。
 *
 * ## 归属记录为什么必须落盘
 *
 * 内存里记一份就够运行时用，但**卸载发生在另一次进程生命周期**：用户装完扩展、
 * 用几天、然后卸载 —— 那时进程早重启过。如果只在内存里记，卸载时根本不知道
 * 该摘掉哪些命令，`dsh-browser` 就会留在容器里。
 *
 * 所以每个命令旁边留一个归属标记文件：`/usr/local/bin/.<name>.owner`，内容是扩展 id。
 * 卸载时扫这一批标记，把属于该扩展的命令连同标记一起删掉。
 *
 * ## 为什么标记文件而不是一份总清单
 *
 * 总清单要在并发安装时加锁、还要处理"清单写了但文件没写成"的不一致。
 * 每个命令自带标记则天然幂等：文件在、标记在；文件没了，标记也就没用。
 * 扫描成本是读几个小文件，只在卸载时发生，不在热路径上。
 */
internal class AppExtensionContainer(
    private val appContext: Context,
    private val extensionId: String,
) : ExtensionContainer {

    /** 命令名会被拼进路径，必须拦住 `../`、`/`、空串这些。 */
    private fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 64 &&
            name[0].isLetterOrDigit() &&
            name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

    private fun binDir(): File = File(DshEnv.rootfs(appContext), "usr/local/bin")

    private fun ownerMarker(name: String): File = File(binDir(), "." + name + ".owner")

    override fun installCommand(name: String, script: String): Boolean = runCatching {
        if (!isValidName(name)) {
            ExtLog.log("[" + extensionId + "] 拒绝安装命令 " + name + "：名字不合法")
            return false
        }
        // 容器还没装好时不写：rootfs 不存在，mkdirs 会造出一个空目录，
        // 而运行时装好之后那个目录不会被清掉 —— 留下一个永远用不上的壳。
        if (!DshEnv.isRuntimeInstalled(appContext)) {
            ExtLog.info("[" + extensionId + "] 运行时未安装，暂不安装命令 " + name)
            return false
        }
        val bin = binDir()
        if (!bin.isDirectory && !bin.mkdirs()) return false
        DshRuntimeFiles.writeScript(File(bin, name), script)
        // 标记必须在脚本写成功之后：反过来的话，脚本没写成却留下标记，
        // 卸载时会删掉一个不存在的文件（无害），但列表里会多一个幽灵命令（有害）。
        ownerMarker(name).writeText(extensionId, Charsets.UTF_8)
        ExtLog.info("[" + extensionId + "] 已安装命令 " + name)
        true
    }.getOrElse {
        ExtLog.log("[" + extensionId + "] 安装命令 " + name + " 失败：" + it.message)
        false
    }

    override fun removeCommand(name: String): Boolean = runCatching {
        if (!isValidName(name)) return false
        val bin = binDir()
        val f = File(bin, name)
        val removed = f.delete()
        ownerMarker(name).delete()
        if (removed) ExtLog.info("[" + extensionId + "] 已移除命令 " + name)
        removed
    }.getOrDefault(false)

    override fun installedCommands(): List<String> = runCatching {
        val bin = binDir()
        if (!bin.isDirectory) return emptyList()
        (bin.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".owner") }
            .filter { runCatching { it.readText(Charsets.UTF_8).trim() }.getOrNull() == extensionId }
            .map { it.name.removePrefix(".").removeSuffix(".owner") }
            .filter { it.isNotEmpty() }
            .sorted()
    }.getOrDefault(emptyList())

    companion object {
        /**
         * 摘掉某个扩展装过的全部命令。
         *
         * 由 [ExtensionEngine] 在停用/卸载时调用。用扩展 id 扫标记，
         * 所以即使进程重启过、内存里没有任何记录，也照样收得干净。
         */
        fun removeAllOf(appContext: Context, extensionId: String) {
            runCatching {
                val bin = File(DshEnv.rootfs(appContext), "usr/local/bin")
                if (!bin.isDirectory) return
                val markers = (bin.listFiles() ?: emptyArray()).filter {
                    it.isFile && it.name.startsWith(".") && it.name.endsWith(".owner") &&
                        runCatching { it.readText(Charsets.UTF_8).trim() }.getOrNull() == extensionId
                }
                for (m in markers) {
                    val name = m.name.removePrefix(".").removeSuffix(".owner")
                    if (name.isNotEmpty()) File(bin, name).delete()
                    m.delete()
                }
                if (markers.isNotEmpty()) {
                    ExtLog.info("已摘除 " + extensionId + " 的 " + markers.size + " 个容器命令")
                }
            }.onFailure {
                ExtLog.log("摘除 " + extensionId + " 的容器命令失败：" + it.message)
            }
        }
    }
}
