package me.bmax.apatch.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import me.bmax.apatch.R
import me.bmax.apatch.util.UpdateChannel

/**
 * 更新通道选择面板。
 *
 * ## 三档的风险不是同一量级，所以提示也不是同一个强度
 *
 * - [UpdateChannel.STABLE] 无需确认：它就是默认行为，回退到安全档不该有摩擦。
 * - [UpdateChannel.BETA] 一次普通确认：确实会有意外行为，但"装回来"是可行的
 *   （同一个签名、能覆盖安装），代价可逆。
 * - [UpdateChannel.ALPHA] **红色高危 + 手输确认文案**：它可能打不开、可能丢数据，
 *   而且一旦打不开，用户连退回设置页改回来的机会都没有。这种情况下"点一下确认"
 *   太轻了 —— 手指滑一下就过去。要求手打一个词，是为了把"我知道我在做什么"
 *   变成一个有成本的动作。
 *
 * 手输要求只针对 Alpha，不是因为 Beta 无害，而是**摩擦力要跟不可逆程度匹配**：
 * 给每一档都上输入确认，用户会习惯性地打完，反而失去了警示作用。
 */
@Composable
fun UpdateChannelPicker(
    current: UpdateChannel,
    onDismiss: () -> Unit,
    onPick: (UpdateChannel) -> Unit,
) {
    // 待确认的目标通道。选中 Alpha 时不立刻生效，先展开警示 + 输入框。
    var pending by remember { mutableStateOf<UpdateChannel?>(null) }

    val confirmWord = stringResource(R.string.update_channel_alpha_confirm_word)
    var typed by remember { mutableStateOf("") }

    // 选一档：Stable/Beta 走一次确认，Alpha 走输入确认。
    // Beta 的确认也用同一个 pending 机制，避免出现"有的档有确认页、有的没有"两套流程。
    fun choose(channel: UpdateChannel) {
        if (channel == current) {
            onDismiss()
            return
        }
        pending = channel
        typed = ""
    }

    val target = pending
    if (target == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.settings_update_channel)) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    for (channel in UpdateChannel.entries.reversed()) {
                        ChannelRow(
                            channel = channel,
                            selected = channel == current,
                            onClick = { choose(channel) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_channel_cancel)) }
            },
        )
        return
    }

    // ── 确认页 ──
    val isAlpha = target == UpdateChannel.ALPHA
    val canConfirm = !isAlpha || typed.trim().equals(confirmWord, ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (isAlpha) {
            { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        title = {
            Text(
                text = stringResource(target.labelRes),
                // Alpha 的标题用错误色：这是"高危"最省字数的一处表达
                color = if (isAlpha) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (isAlpha) R.string.update_channel_alpha_warning
                        else R.string.update_channel_beta_warning
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isAlpha) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isAlpha) {
                    Spacer(Modifier.height(16.dp))
                    // 提示里带上要打的词本身：让用户不用猜大小写，也避免"提示说输入确认
                    // 但没说输入什么"这种最糟的组合。
                    Text(
                        text = stringResource(R.string.update_channel_alpha_confirm_hint, confirmWord),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(target) },
                enabled = canConfirm,
            ) {
                Text(
                    text = stringResource(R.string.update_channel_switch),
                    color = if (canConfirm) {
                        if (isAlpha) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_channel_cancel)) }
        },
    )
}

@Composable
private fun ChannelRow(
    channel: UpdateChannel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中态用 RadioButton 而不是 Checkbox：三选一，语义上就该是单选。
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(
                text = stringResource(channel.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (channel == UpdateChannel.ALPHA) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(channel.descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
