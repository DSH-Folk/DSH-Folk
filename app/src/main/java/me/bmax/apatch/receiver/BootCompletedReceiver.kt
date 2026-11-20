package me.bmax.apatch.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.bmax.apatch.dsh.DshAutostart

/**
 * 开机自启的**广播**路径：收 `BOOT_COMPLETED` 后把 Harness 拉起来。
 *
 * 三条自启路径之一（另两条见 [DshAutostart]）。判断逻辑全部下沉到
 * [DshAutostart.trigger]：它会检查当前选的是不是**这一条**，所以用户切到脚本或无障碍
 * 模式之后，这个 receiver 仍然会收到广播、但不会再启动任何东西。
 *
 * FolkPatch 原来在这里补跑 `apd manager-boot-completed`（内核补丁的 post-fs-data 兜底）。
 * DSH-Folk 没有 apd，那段一并去掉了。
 *
 * 注意 `ACTION_LOCKED_BOOT_COMPLETED` **没有**注册：那条广播在用户解锁前就送到，此时
 * 应用的 CE 存储（`filesDir`、SharedPreferences）还没挂载，读到的会是空配置。
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // ── 启动管线 AUTOSTART 段 ──
        //
        // 在真正触发自启**之前**跑。扩展可以往 ctx 里写 "skip" = true 阻止本次自启
        // （例如它要先把前置条件准备好，或用户设了某时段不自启）。
        //
        // 注意这里跑在**广播接收器**里、不是 Activity：进程可能只是被广播临时拉起，
        // 内存与时间预算都很紧。管线本身有 200ms 总预算，超时的 handler 直接跳过。
        val autoCtx = mutableMapOf<String, Any?>()
        me.bmax.apatch.dsh.ext.ExtensionBootPipeline.run(
            me.bmax.apatch.dsh.ext.ExtensionBootPipeline.Stage.AUTOSTART,
            autoCtx,
        )
        if (autoCtx["skip"] == true) {
            android.util.Log.i("BootCompletedReceiver", "扩展跳过了本次自启")
            return
        }

        DshAutostart.trigger(context.applicationContext, DshAutostart.Mode.RECEIVER)
    }
}
