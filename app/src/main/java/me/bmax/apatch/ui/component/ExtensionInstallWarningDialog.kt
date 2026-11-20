package me.bmax.apatch.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.bmax.apatch.R

/**
 * 安装第三方扩展前的**风险确认**。
 *
 * ## 为什么必须有这个弹窗
 *
 * 扩展与宿主**同进程、同权限**：它能读通知、调摄像头、跑 root 命令、改无障碍服务、
 * 往容器里装任意脚本。这不是"可能"，是 API 设计上就允许的 —— 用户要的正是
 * 「现有功能都可由扩展实现」。
 *
 * 所以安装这一步必须是**明确的知情同意**，而不是一个「安装」按钮直接执行。
 * 弹窗要讲清三件事：能做什么、来源是否可信、出问题怎么收回。
 *
 * ## 三个入口共用
 *
 * 文件、链接、市场三条安装路径都会经过这里。分开写三份迟早会漂移 ——
 * 而"某一入口没有警告"是这类设计最严重的事故。
 */
@Composable
fun ExtensionInstallWarningDialog(
    /** 扩展名（已知时显示；从链接安装时可能还不知道，传 null）。 */
    extensionName: String?,
    /** 该扩展声明的能力，逐条列出。空列表时只显示通用警告。 */
    declaredCaps: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dsh_ext_warn_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.dsh_ext_warn_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (declaredCaps.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.dsh_ext_warn_caps_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    for (cap in declaredCaps) {
                        Text(
                            "· " + cap,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!extensionName.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.dsh_ext_warn_target, extensionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.dsh_ext_warn_revoke),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.dsh_ext_warn_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_channel_cancel))
            }
        },
    )
}
