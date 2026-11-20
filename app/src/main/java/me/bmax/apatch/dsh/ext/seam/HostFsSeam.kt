package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import java.io.File
import me.bmax.apatch.dsh.DshEnv

/**
 * [FsSeam] 的宿主实现。
 *
 * ## 路径穿越必须在**这一层**挡掉
 *
 * 扩展拿到的是宿主真实的文件系统。`containerToHost(ctx, "/root/../../databases/x.db")`
 * 这种输入如果直接 `File(rootfs, path)`，`..` 会被解析掉、指到容器外面去。
 * 宿主的 [DshEnv.containerToHost] 已经做了规范化 + 前缀校验，这里必须复用它，
 * 不能自己拼路径。
 */
internal class HostFsSeam(
    private val ctx: Context,
    override val extensionDir: File,
) : FsSeam {

    override val rootfsDir: File? =
        DshEnv.rootfs(ctx).takeIf { it.isDirectory }

    override fun containerToHost(containerPath: String): File? =
        DshEnv.containerToHost(ctx, containerPath)

    override fun hostToContainer(hostPath: File): String? {
        val root = rootfsDir ?: return null
        // 用 canonical 路径比较：不规范化的话 "/a/../rootfs/x" 这种会绕过前缀检查。
        val rootCanon = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val fileCanon = runCatching { hostPath.canonicalFile }.getOrNull() ?: return null
        val rootPrefix = rootCanon.path + File.separator
        if (!fileCanon.path.startsWith(rootPrefix)) return null
        return "/" + fileCanon.path.removePrefix(rootPrefix)
    }

    override fun readContainerText(containerPath: String): String? {
        val f = containerToHost(containerPath) ?: return null
        if (!f.isFile) return null
        return runCatching { f.readText() }.getOrNull()
    }

    override fun writeContainerText(containerPath: String, content: String): Boolean {
        val f = containerToHost(containerPath) ?: return false
        return runCatching {
            f.parentFile?.mkdirs()
            f.writeText(content)
            true
        }.getOrDefault(false)
    }

    override fun listContainer(containerPath: String): List<String> {
        val f = containerToHost(containerPath) ?: return emptyList()
        if (!f.isDirectory) return emptyList()
        return runCatching { f.list()?.sorted() ?: emptyList() }.getOrDefault(emptyList())
    }
}
