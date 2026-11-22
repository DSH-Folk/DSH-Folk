package me.bmax.apatch.ui.component

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.dsh.DshA11yRecovery
import me.bmax.apatch.res.R as CoreR
import me.bmax.apatch.dsh.PrivilegedShell

/**
 * 「无障碍服务被系统关掉后自动恢复」的设置卡。
 *
 * 为什么需要它：WRITE_SECURE_SETTINGS **没有运行时授权框**，只能由用户从 ADB / 终端授予。
 * 所以这张卡的全部职责是把两件事说清楚 ——
 *
 * 1. 现在有没有这条权限（没有就给出**可以直接复制**的 pm grant 命令，不在应用内偷偷跑）；
 * 2. 恢复本身是 best-effort（进程不在时无人可恢复），不能让用户以为装了它就万无一失。
 *
 * 是否真的动手不在这里判断：由 [DshA11yRecovery.wanted] 按应用内那两个开关算
 * （自启动模式是否仍是无障碍、原生桥 A11Y 档位是否不是关）。这里只负责呈现与触发。
 */
@Composable
internal fun A11yRecoveryCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(DshA11yRecovery.secureSettingsGranted(context)) }
    // 自动恢复总闸。默认开（存量用户已在享受这个行为，默认关掉等于悄悄取消一项保障）。
    var autoRecovery by remember { mutableStateOf(DshA11yRecovery.autoRecoveryEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    val command = remember(context) { DshA11yRecovery.adbGrantCommand(context) }
    // 当前特权通道。非 null 且 usable 时才给「一键授予」—— 没有通道时那个按钮
    // 点了只会失败，摆出来等于骗用户。
    var reach by remember { mutableStateOf<PrivilegedShell.Reach?>(null) }

    // 用户刚从 ADB 授完权回来：每次回到这一页重读，而不是等进程重启。
    LifecycleResumeEffect(Unit) {
        granted = DshA11yRecovery.secureSettingsGranted(context)
        // 通道状态也重探：用户可能刚去配好 Shizuku/ADB 再回来，那时一键按钮该出现。
        // 探测本身不弹 su 授权框（reach 内部用 allowRootPrompt=false）。
        reach = runCatching { PrivilegedShell.reach(context.applicationContext) }.getOrNull()
        onPauseOrDispose { }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(CoreR.string.dsh_a11y_fix_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(CoreR.string.dsh_a11y_fix_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            // ── 自动恢复总闸 ──
            //
            // 放在权限状态**之前**：用户先要知道"这个功能要不要开"，再关心"开了要什么权限"。
            // 顺序反了会让人以为权限是必须的，而实际上关掉总闸就完全不需要它。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(CoreR.string.dsh_a11y_fix_auto_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(CoreR.string.dsh_a11y_fix_auto_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = autoRecovery,
                    onCheckedChange = { on ->
                        autoRecovery = on
                        // setAutoRecoveryEnabled 内部会 install/unregister：
                        // 关掉时真正解绑监听（不是"还在盯着但早退"），打开时重新挂上。
                        DshA11yRecovery.setAutoRecoveryEnabled(context, on)
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (granted) {
                    stringResource(CoreR.string.dsh_a11y_fix_granted)
                } else {
                    // 同上：先在组合作用域外算好，再取文案。
                    val ch = reach?.takeIf { it.usable }?.channel
                    if (ch != null) {
                        stringResource(CoreR.string.dsh_a11y_fix_grant_now_summary, ch.name.lowercase())
                    } else {
                        stringResource(CoreR.string.dsh_a11y_fix_need_permission)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.tertiary,
            )
            if (!granted && reach?.takeIf { it.usable } == null) {
                Spacer(Modifier.height(6.dp))
                // 命令原样摆出来：用户要复制去 ADB 执行，藏进弹窗就不叫"可复制"了。
                // 有可用通道时不显示 —— 那时一键按钮就能做完，命令只是噪音。
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!granted) {
                    // 有可用特权通道时优先给「一键授予」：用户很可能已经配好 Shizuku/root，
                    // 让他为一条 pm grant 去插数据线开电脑是把已有能力白白浪费掉。
                    // takeIf 的 lambda 不是 @Composable 上下文，里面不能调 stringResource。
                    // 先算好布尔值，再在组合作用域里取文案。
                    val hasUsableChannel = reach?.usable == true
                    if (hasUsableChannel) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val (ok, why) = withContext(Dispatchers.IO) {
                                        DshA11yRecovery.grant(context.applicationContext)
                                    }
                                    busy = false
                                    if (ok) {
                                        granted = true
                                        Toast.makeText(
                                            context,
                                            CoreR.string.dsh_a11y_fix_grant_ok,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(CoreR.string.dsh_a11y_fix_grant_failed, why),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(CoreR.string.dsh_a11y_fix_grant_now))
                        }
                    }
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("pm grant", command))
                        Toast.makeText(context, CoreR.string.dsh_a11y_fix_copied, Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(CoreR.string.dsh_a11y_fix_copy))
                    }
                    TextButton(onClick = {
                        granted = DshA11yRecovery.secureSettingsGranted(context)
                        reach = runCatching { PrivilegedShell.reach(context.applicationContext) }.getOrNull()
                    }) {
                        Text(stringResource(CoreR.string.dsh_a11y_fix_check))
                    }
                } else {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                // 写 secure settings 是 binder 调用，仍旧不该占主线程。
                                val r = withContext(Dispatchers.IO) {
                                    DshA11yRecovery.repair(context.applicationContext, manual = true)
                                }
                                busy = false
                                Toast.makeText(context, r.messageRes, Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Text(stringResource(CoreR.string.dsh_a11y_fix_repair))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(CoreR.string.dsh_a11y_fix_best_effort),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
