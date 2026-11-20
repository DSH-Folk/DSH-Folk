package me.bmax.apatch.ui.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import me.bmax.apatch.R
import me.bmax.apatch.dsh.ext.ExtensionUiRegistry

/**
 * 把扩展注册的设置项渲染进宿主设置页。
 *
 * ## 为什么是"追加"而不是"改造 93 条"
 *
 * 宿主有 93 条设置项分布在 8 个分类页里，每条都带着自己的状态、权限检查、确认弹窗
 * （生物识别要弹系统框、DPI 要重启应用、更新通道要二次确认…）。把它们全改成数据驱动
 * 意味着把那些交互搬进一个通用渲染器 —— 那会让渲染器长成一个 if-else 怪物，
 * 而且每加一条新设置都要回来改它。
 *
 * 反过来做：宿主自带条目**原样保留**，扩展条目**追加在分类末尾**。
 * 代价是扩展条目不能插在宿主条目中间 —— 但那个需求本来就不存在：
 * 扩展和宿主的设置项在语义上没有从属关系，按"谁的谁"分组反而更清楚。
 *
 * ## 调用点
 *
 * 每个分类页在它自己的 SplicedColumnGroup 之后调一次，传自己的分类 id。
 * 页面不需要知道有哪些扩展项，扩展也不需要知道页面长什么样。
 */
@Composable
fun ExtensionSettingItems(
    /** 宿主分类 id，见 [ExtensionUiRegistry.SettingsCategory]。 */
    category: String,
    flat: Boolean = false,
    highlightKey: String? = null,
) {
    // 依赖注册表快照：扩展是异步加载的，注册可能发生在首次组合之后。
    // 不依赖的话，装了扩展要杀进程重进才看得到它加的设置项。
    val items = remember(ExtensionUiRegistry.settingItems().size) {
        ExtensionUiRegistry.settingItems().filter { it.category == category }
    }
    if (items.isEmpty()) return

    SplicedColumnGroup(
        flat = flat,
        highlightKey = highlightKey,
        title = stringResource(R.string.dsh_ext_settings_group),
    ) {
        for (item in items) {
            item(key = item.key) {
                ToggleSettingCard(
                    flat = flat,
                    icon = Icons.Filled.Extension,
                    title = item.title,
                    description = item.summary,
                    // 扩展设置项目前只有"点击"语义，没有开关状态 ——
                    // 状态归扩展自己管（它可以把当前值写进 summary）。
                    // 画成开关却不让改，比画成可点条目更让人困惑。
                    checked = false,
                    onClick = item.onClick,
                    onCheckedChange = { item.onClick?.invoke() },
                )
            }
        }
    }
}
