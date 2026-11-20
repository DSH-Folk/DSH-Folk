package me.bmax.apatch.dsh.ext.seam

import android.content.Context
import me.bmax.apatch.dsh.AdbBridge
import me.bmax.apatch.dsh.PrivilegedShell

/**
 * [ShellSeam] 的宿主实现：设备上的提权 shell 与 ADB。
 *
 * ## 每个方法都不抛异常
 *
 * 没有 root 是**常态**，不是错误。返回空串 / false 让扩展自己决定是提示用户还是换条路。
 * 抛异常会逼每个扩展写 try/catch，而那些 catch 最后都会变成 `catch (_: Throwable) {}`，
 * 等于把真实错误也吞掉。
 */
internal class HostShellSeam(private val ctx: Context) : ShellSeam {

    override fun hasRoot(): Boolean = runCatching {
        PrivilegedShell.exec(ctx, "id", asRoot = true, timeoutMs = 5_000L).exit == 0
    }.getOrDefault(false)

    override fun root(command: String, timeoutMs: Long): String = runCatching {
        PrivilegedShell.exec(ctx, command, asRoot = true, timeoutMs = timeoutMs).stdout
    }.getOrDefault("")

    override fun hasShizuku(): Boolean = runCatching {
        PrivilegedShell.exec(ctx, "id", asRoot = false, timeoutMs = 5_000L).exit == 0
    }.getOrDefault(false)

    override fun shizuku(command: String, timeoutMs: Long): String = runCatching {
        PrivilegedShell.exec(ctx, command, asRoot = false, timeoutMs = timeoutMs).stdout
    }.getOrDefault("")

    /**
     * ADB 通道是否可用。
     *
     * 注意这里问的是 `injected()`（ADB 通道是否已注入），**不是**"有没有连上设备" ——
     * 宿主的 ADB 是走容器里的 adb 二进制连本机，注入成功就能用。
     */
    override fun adbConnected(): Boolean = runCatching { AdbBridge.injected() }.getOrDefault(false)

    override fun adb(command: String, timeoutMs: Long): String =
        runCatching { AdbBridge.shell(command) }.getOrDefault("")
}
