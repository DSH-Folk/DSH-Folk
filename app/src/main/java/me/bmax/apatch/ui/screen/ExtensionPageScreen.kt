package me.bmax.apatch.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateBottomPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import me.bmax.apatch.dsh.ext.ExtensionUiRegistry
import me.bmax.apatch.res.R as CoreR

/**
 * 承载**扩展注册的整页**的通用路由。
 *
 * ## 为什么需要这样一个"空壳页"
 *
 * 宿主的导航图由 compose-destinations 的 KSP 在**编译期**生成（`NavGraphs.root` 是生成
 * 出来的 object），`DestinationsNavHost` 只接受一个固定的 NavGraphSpec，没有运行时
 * 追加路由的入口。所以扩展没法把自己的目的地注解塞进宿主图里 —— 那些注解在
 * 编译期就定型了，而扩展是之后才装进来的。
 *
 * 解法：宿主**预留一个**路由，参数是扩展页面 id。用户导航到这里，宿主再从
 * [ExtensionUiRegistry] 取出对应的 Composable 渲染。
 *
 * 对用户：这就是一个正常页面（有顶栏、有返回键、进返回栈）。
 * 对扩展：不用碰任何导航代码，只注册 id + 标题 + 内容。
 *
 * ## 页面不存在时
 *
 * 显示一行说明而不是崩溃或空白：扩展可能刚被卸载、或注册失败。
 * 空白页会让用户以为"点坏了"，而崩溃更糟。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionPageScreen(
    navigator: DestinationsNavigator,
    /**
     * 要渲染哪个扩展页面（[ExtensionUiRegistry.Page.id]）。
     *
     * 给默认值不是"可以不给"：compose-destinations 要求导航参数有默认值才能生成
     * Destination 类型（参数顺序上 navigator 必须在前，与宿主其它页面一致）。
     * 空串落地时显示"页面不存在"，那是正确行为。
     */
    pageId: String = "",
) {
    // 依赖注册表快照：扩展是异步加载的，进页面时可能刚好注册完。
    val page = ExtensionUiRegistry.page(pageId)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = page?.title ?: stringResource(CoreR.string.dsh_ext_page_missing_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.navigateUp() }) {
                        // 与宿主其它页面一致：返回键的 contentDescription 留空，
                        // 由无障碍树里的按钮语义自己描述。
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        // 内容延伸到透明的小白条（手势导航条）下方，而不是被它顶开一条实色带。
        // 底部安全区由页面自己补回（列表 contentPadding / 固定底栏的 navigationBarsPadding），
        // 否则最后一项会被手势条压住。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner_raw ->
        // contentWindowInsets 归零后 Scaffold 不再让开手势条，把底部安全区加回 padding。
        val inner = inner_raw.let {
            it.copy(bottom = it.calculateBottomPadding() + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        }
        Box(Modifier.fillMaxSize().padding(inner)) {
            if (page != null) {
                page.content(Modifier.fillMaxSize())
            } else {
                Text(
                    text = stringResource(CoreR.string.dsh_ext_page_missing_body, pageId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}
