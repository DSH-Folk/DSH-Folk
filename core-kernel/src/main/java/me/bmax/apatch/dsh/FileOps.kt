package me.bmax.apatch.dsh

import java.io.File

/**
 * 文件搬移/复制的底层原语。
 *
 * 原本在 `DshRuntimeInstaller.kt` 里。抽出来是因为 `DshEnv` 要用 `renameOrCopy`，
 * 而 `DshRuntimeInstaller` 依赖整个安装流程（下载、解包、进度上报），下沉不动。
 *
 * 两个函数各自的 KDoc 里写了两条硬约束的原因：
 *  - 跨挂载点 `renameTo` 会**静默失败**，必须回退到复制
 *  - 符号链接必须**原样复制**而不是跟随（rootfs 里有悬空链接）
 */

/**
 * 搬目录：先 rename，失败（跨挂载点、厂商 ROM 的怪行为）再复制 + 删除。
 *
 * 为什么是 internal：恢复路径（recoverInterruptedRuntimeInstall、DshEnv.recoverPreserved）
 * 也要用它 —— 那两处此前是裸 renameTo，跨边界失败时**静默返回 false**，用户数据就这么丢了。
 */
fun renameOrCopy(src: File, dst: File, note: (String) -> Unit): Boolean {
    if (src.renameTo(dst)) return true
    note("renameTo 失败，回退复制：${src.name} → ${dst.name}")
    return runCatching {
        if (dst.exists()) dst.deleteRecursively()
        src.copyRecursiveTo(dst)
        src.deleteRecursively()
        true
    }.getOrElse {
        note("复制回退也失败：${it.javaClass.simpleName}: ${it.message}")
        false
    }
}

/**
 * 递归复制（保留符号链接与可执行位）。
 *
 * **必须原样复制符号链接，而不是跟随它** —— rootfs 里到处是链接（Ubuntu 的 alternatives、
 * systemd 的 wants 目录之类），其中不少是**悬空链接**（目标在本容器里不存在，
 * 例如 motd-news 相关的 timer）。跟随它们会得到 `FileNotFoundException: ... ENOENT`，
 * 于是整个回退复制失败 —— Issue #12 的日志正是这样：
 *
 * ```
 * ! [安装] 复制回退也失败：FileNotFoundException: .../motd-news.timer: ENOENT
 * ```
 *
 * 所以这里先 `readSymbolicLink()` 判断：能读出目标就是链接，用 `Os.symlink` 原样重建，
 * 目标是否存在与本次复制无关。解压器（[TarGzipExtractor]）也是这么重建链接的。
 */
fun File.copyRecursiveTo(dst: File) {
    // 符号链接优先判：File.isDirectory/isFile 都会**跟随**链接，必须先问链接本身
    val linkTarget = runCatching { java.nio.file.Files.readSymbolicLink(toPath()) }.getOrNull()
    if (linkTarget != null) {
        dst.parentFile?.mkdirs()
        runCatching {
            dst.delete()
            android.system.Os.symlink(linkTarget.toString(), dst.absolutePath)
        }.onFailure {
            // 链接建不出来（少见：文件系统不支持）就跳过这一条，不因一个链接废掉整次安装
            android.util.Log.w("DSH-Folk", "复制符号链接失败 ${dst.name}: ${it.message}")
        }
        return
    }
    if (isDirectory) {
        dst.mkdirs()
        listFiles()?.forEach { child -> child.copyRecursiveTo(File(dst, child.name)) }
    } else {
        dst.parentFile?.mkdirs()
        inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
        // 可执行位必须显式带上：copyTo 只搬字节，权限是 inode 属性
        if (canExecute()) runCatching { dst.setExecutable(true, false) }
    }
}
