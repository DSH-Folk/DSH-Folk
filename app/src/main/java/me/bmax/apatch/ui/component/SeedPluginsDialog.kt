package me.bmax.apatch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.bmax.apatch.core.res.CoreR
import me.bmax.apatch.dsh.DshRuntime

/**
 * 首次准备预装插件时的勾选确认框。
 *
 * ## 为什么要有这一步
 *
 * 预装清单里的包会改 WebUI、注册插件商店、接管配置备份 —— 都是**用户没要求**就装进
 * 他容器里的东西。此前的做法是直接装，用户只能事后去商店卸载；更糟的是卸载之后下次
 * 冷启动的"补装"逻辑还会把它装回来（补装判据是"在清单里但没装上"，卸载正好满足它）。
 *
 * 所以：装之前问一次，取消勾选的记进 [DshRuntime.setSeedSkipped]，后续补装**尊重**
 * 这个选择，不再强行装回。
 *
 * ## 为什么默认全选
 *
 * 这些包是功能可用性的前提（没有 dsh-web-mobile 就没有手机版 WebUI，没有
 * dsh-config-manager 设置里的备份页直接不可用）。默认不选等于把 App 弄成半残，
 * 用户还得自己找回来。默认全选 + 允许取消，是"开箱可用"与"尊重选择"的折中。
 */
@Composable
internal fun SeedPluginsDialog(
    onConfirm: (keep: Set<String>) -> Unit,
    onSkipAll: () -> Unit,
) {
    val all = remember { DshRuntime.seedPluginList }
    // 默认全选
    val checked = remember { mutableStateMapOf<String, Boolean>().apply { all.forEach { put(it, true) } } }

    AlertDialog(
        onDismissRequest = { /* 必须做出选择：直接关掉等于没说，按全选处理更符合预期 */ },
        title = { Text(stringResource(CoreR.string.dsh_seed_ask_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(CoreR.string.dsh_seed_ask_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                for (pkg in all) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked[pkg] == true,
                            onCheckedChange = { on -> checked[pkg] = on },
                        )
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pkg, style = MaterialTheme.typography.bodyMedium)
                            val desc = seedPluginDescription(pkg)
                            if (desc != null) {
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(all.filter { checked[it] == true }.toSet()) }) {
                Text(stringResource(CoreR.string.dsh_seed_ask_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkipAll) {
                Text(stringResource(CoreR.string.dsh_seed_ask_skip_all))
            }
        },
    )
}

/**
 * 预装包的一句话说明。
 *
 * 直接写在这里而不是读商店元数据：这一步发生在**容器还没起来**的时候，拉不到任何
 * 远端数据；而清单本身是编译期固定的，说明跟着它一起写死最省事也最可靠。
 */
private fun seedPluginDescription(pkg: String): String? = when (pkg) {
    "dsh-web-mobile" -> "手机版 WebUI 布局"
    "dshmarket" -> "插件商店（浏览与安装社区插件）"
    "dsh-config-manager" -> "配置备份与恢复（设置里的导出/导入依赖它）"
    "dsh-folk-cloud" -> "云同步（WebDAV 备份）"
    else -> null
}
