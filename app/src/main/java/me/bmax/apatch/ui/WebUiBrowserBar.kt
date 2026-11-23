package me.bmax.apatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.bmax.apatch.res.R as CoreR
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.background

/**
 * 浏览器模式下的 Via 式导航栏。
 *
 * **显隐由调用方控制**（[visible]）：切换动作绑在现有的悬浮球**长按**上，所以这个组件
 * 自己不再画按钮 —— 再画一颗会出现两个入口互相打架（用户分不清哪颗算「那颗球」）。
 *
 * 内容：后退 / 前进 / 当前地址（只读展示）/ **回 DSH 首页** / 刷新 / 退出浏览器模式。
 *
 * ## 首页按钮为什么必须存在
 * 浏览器模式一旦打开，用户可能漂到任意站点。没有「回 dsh 首页」就等于把人锁在外部网页上，
 * 得靠系统返回键一层层退 —— 而站点自己也可能吃掉返回键。
 *
 * ## 地址为什么只读
 * 这里是「看清自己在哪」，不是地址输入框。真要做地址输入得配一整套输入法、补全与校验，
 * 那是另一个功能；现在把只读地址摆在这是为了让「站内导航」这件事可解释。
 */
@Composable
internal fun WebUiBrowserBar(
    visible: Boolean,
    currentUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onGoHome: () -> Unit,
    onExitBrowserMode: () -> Unit,
    /** 点信息图标：给用户看当前页的完整信息（URL、标题、是否 https）。 */
    onShowPageInfo: () -> Unit,
    /** 地址栏输入后回车：导航到这个地址。 */
    onNavigate: (String) -> Unit,
    /** 点「新建标签页」：开一个空标签（回到 DSH 首页）。 */
    onNewTab: () -> Unit,
    /** 点「标签总览」：列出所有标签并允许切换 / 关闭。 */
    onShowTabs: () -> Unit,
    tabCount: Int = 1,
    modifier: Modifier = Modifier,
) {
    // 输入中的地址。与 currentUrl 分开：用户正在改的时候，页面跳转不该把他
    // 正在敲的字覆盖掉；只有「没在编辑」时才跟随 currentUrl 同步。
    var editing by remember { mutableStateOf(currentUrl) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(currentUrl, focused) {
        if (!focused) editing = currentUrl
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        Surface(
            // 不透明：这条栏现在是**占位**的（把网页挤下去），不是浮在网页上的。
            // 留半透明会让下方网页透出来，看起来像"盖住了一半"—— 而这正是改造前的问题。
            //
            // 状态栏避让：Column 从窗口最顶开始（edge-to-edge），不加这一层的话
            // 工具栏会被状态栏压住。只给这条栏让，WebView 那边由 inset.js 负责。
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBack, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "后退")
                }
                IconButton(onClick = onForward, enabled = canGoForward) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "前进")
                }
                // 地址栏：一个真的输入框。
                //
                // ## 为什么不再用「只读文字 + 整条可点」
                //
                // 第一版是只读的，点一下弹「页面信息」。用户明确要的是**能改地址**
                // —— 只读框看着像输入框却打不了字，比纯文字更让人困惑。
                // 现在：点进去就能编辑、回车导航；信息图标是**独立**的按钮，两者不再
                // 抢同一块点击区域。
                //
                // ## 为什么用 BasicTextField 而不是 OutlinedTextField
                //
                // 这条栏高 48dp 左右，OutlinedTextField 自带 label 浮动位、边框、
                // 内边距，压进来会把整条栏撑高。BasicTextField 只提供输入能力，
                // 外观完全由这里控制 —— 和地址栏「就是一行字」的观感一致。
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onShowPageInfo,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(CoreR.string.dsh_browser_page_info),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    BasicTextField(
                        value = editing,
                        onValueChange = { editing = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 6.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        // 点进来时全选：想换地址的人不会想先手动清空。
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val target = normalizeUrl(editing)
                                if (target.isNotEmpty()) {
                                    onNavigate(target)
                                    focusManager.clearFocus()
                                }
                            },
                        ),
                    )
                }
                // 标签总览：显示当前标签数，点了列出全部。
                //
                // 用一个带数字角标的方框图标而不是纯图标：用户要能一眼看出
                // "现在开了几个"。只有 1 个标签时也照常显示 —— 按钮位置固定，
                // 不会因为标签数变化而左右跳。
                IconButton(onClick = onShowTabs) {
                    BadgedBox(
                        badge = {
                            if (tabCount > 1) {
                                Badge { Text(tabCount.toString()) }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Tab, stringResource(CoreR.string.dsh_browser_tabs))
                    }
                }
                IconButton(onClick = onNewTab) {
                    Icon(Icons.Filled.Add, stringResource(CoreR.string.dsh_browser_new_tab))
                }
                IconButton(onClick = onGoHome) {
                    Icon(Icons.Filled.Home, stringResource(CoreR.string.dsh_browser_home))
                }
                IconButton(onClick = onReload) {
                    Icon(Icons.Outlined.Refresh, "刷新")
                }
                IconButton(onClick = onExitBrowserMode) {
                    Icon(Icons.Outlined.Close, "退出浏览器模式")
                }
            }
        }
    }
}

/**
 * 把地址栏里敲的内容补成一个能导航的 URL。
 *
 * 规则按「用户敲这个是想去哪」来定，不是按 URL 语法：
 *  - 已经是 scheme://... → 原样用；
 *  - 只敲了主机名/端口（`127.0.0.1:3080`、`example.com`）→ 补 http://；
 *  - 别的（比如 `dsh 设置`）→ 当成搜索词，交给系统浏览器处理太突兀，
 *    这里直接拼到当前 dsh 的搜索页；dsh 没有搜索页，所以退回主机名猜测。
 *
 * 空串返回空串，调用方据此不做任何事。
 */
internal fun normalizeUrl(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return ""
    // 有 scheme 就用（http / https / file / about 都认）
    if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) return t
    if (t.startsWith("about:") || t.startsWith("data:")) return t
    // 形如 host[:port][/path]：补 http://
    // 判据是「第一段里有 . 或 : 或就是 localhost」—— 太宽松会把普通词也当域名
    val host = t.substringBefore('/')
    val looksLikeHost = host.contains('.') || host.contains(':') ||
        host == "localhost" || host.startsWith("127.") || host.startsWith("[")
    return if (looksLikeHost) "http://$t" else "http://$t"
}
