package me.bmax.apatch.ui.component

import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.ext.ExtensionMarket
import me.bmax.apatch.dsh.ext.MarketEntry

/**
 * 扩展市场弹层。
 *
 * 为什么做成弹层而不是一个独立目的地：这一页的上下文是「我装了哪些扩展」，
 * 市场的用途只有一个 —— 往里再装一个。跳到另一屏再跳回来会把「刚装完的那个在哪」丢掉，
 * 而弹层收起后列表本来就会重读。
 *
 * 安装成功的判定**不看网络返回**，只看 ExtensionRegistry 里有没有它：
 * 网络层说成功但写盘失败是真实存在的情况（目录权限、空间），那时用户看到的必须是真相。
 *
 * ## 视觉为什么长这样（与插件商店对齐时定下的几条）
 *
 * 卡片照 [DshPluginStoreScreen] 的商店瓦片（18dp / surfaceContainer / 内边距 14dp），
 * **不是** 20dp / secondaryContainer@0.2 —— 后者在本仓已经是「已安装项」的语法
 * （见扩展页的 ExtensionItem、插件页的 DshPluginItem）。市场条目是「可安装项」，
 * 套用已安装的语法会让用户以为它已经装上了。这是可用性问题，不只是不齐。
 *
 * 但容器是弹层不是 Screen，所以有几处**故意不照抄**：
 *  - 不用 Scaffold / SearchAppBar：弹层里再套一层顶栏会多出一条阴影与一次 inset 计算；
 *  - 不用 ScrollableEmptyState：它是为「整屏页面的悬浮底栏被自动隐藏后能下拉唤回」造的，
 *    内部 fillMaxSize() + verticalScroll；弹层高度是 wrap 的，套进去会把弹层撑满屏；
 *  - 不加 HomeBottomSpacer：那个组件读 LocalBottomBarVisible，弹层里没有底栏，
 *    加了就是在弹层底部凭空多出 16~80dp 空白。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionMarketSheet(
    onDismiss: () -> Unit,
    /** 安装成功后回调（由调用方重读列表）。 */
    onInstalled: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<MarketEntry>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    // 安装结果分成两条：以前失败原因也塞进同一条 message 用主色显示，
    // 蓝色的一行「校验失败：文件可能被篡改」看起来像成功提示。
    var message by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf("") }
    // 按 id 记「正在装哪一条」：这个状态必须活在这一层而不是行里 ——
    // 从详情弹层发起安装时列表行可能已经滚出视口，状态挂在 item 上的话滚回来转圈就丢了。
    var installingId by remember { mutableStateOf<String?>(null) }
    // 已装版本同样是弹层级状态：装完要立刻刷新，否则那一行会一直显示「安装」。
    var installed by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 详情只存 id，实体每次从 entries 取（与插件商店同一条纪律：存快照的话
    // 装完之后详情页按钮会停在打开那一刻的状态）。
    var detailId by remember { mutableStateOf<String?>(null) }
    val detail = detailId?.let { id -> entries.firstOrNull { it.id == id } }

    /** 重读已装版本。installedVersions 会读文件系统，按它的约定必须在 IO 线程调用。 */
    suspend fun refreshInstalled() {
        installed = withContext(Dispatchers.IO) {
            ExtensionMarket.installedVersions(context.applicationContext)
        }
    }

    /**
     * 拉目录。失败**不清空已有列表**：刷新失败时用户手上那份目录仍然是有用的，
     * 清掉只会让他觉得「刚才还能看到的东西没了」。
     */
    fun reload() {
        loading = true
        error = ""
        scope.launch {
            ExtensionMarket.fetchIndex().fold(
                onSuccess = { entries = it; error = "" },
                onFailure = { error = it.message ?: it.javaClass.simpleName },
            )
            loading = false
        }
    }

    /**
     * 待确认的安装。市场条目的能力清单已经在手（[MarketEntry.declaredCaps]），
     * 不需要额外预览请求，所以这里只是"先弹窗、确认后再执行"。
     */
    var pendingInstall by remember { mutableStateOf<MarketEntry?>(null) }

    fun install(entry: MarketEntry) {
        if (installingId != null) return
        // 先弹风险确认：扩展与宿主同进程同权限，这一步不能省。
        pendingInstall = entry
    }

    fun doInstall(entry: MarketEntry) {
        if (installingId != null) return
        installingId = entry.id
        message = ""
        failure = ""
        scope.launch {
            ExtensionMarket.install(context, entry).fold(
                onSuccess = { mf ->
                    message = context.getString(R.string.dsh_ext_market_done, mf.name)
                    onInstalled()
                    // 装完立刻重读已装表：那一行要从「安装」翻成 ✓，不能等下次开弹层
                    refreshInstalled()
                },
                onFailure = { e ->
                    failure = e.message ?: e.javaClass.simpleName
                },
            )
            installingId = null
        }
    }

    LaunchedEffect(Unit) {
        refreshInstalled()
        reload()
    }

    // 详情弹层：单击卡片进这里。动作仍留在列表行上（与扩展页「进弹层是为了看清楚，
    // 不是为了把常用动作藏起来」同一取舍），但详情里也给一颗主按钮 —— 那是商店的做法，
    // 而且这里正是用户核对完 id / 校验值之后最想按的地方。
    detail?.let { entry ->
        MarketDetailSheet(
            entry = entry,
            installedVersion = installed[entry.id],
            installing = installingId == entry.id,
            busy = installingId != null && installingId != entry.id,
            onDismiss = { detailId = null },
            onInstall = {
                detailId = null
                install(entry)
            },
        )
    }

    // ── 整页，不是弹层 ──
    //
    // 原来是 ModalBottomSheet（半窗）。用户的原话是「而不是像现在的半窗」——
    // 而「对齐插件商店」这件事在半窗里根本做不到：插件商店是 Scaffold 整页，
    // 有顶栏、有全屏列表、有自己的返回语义；塞在弹层里就只能是「像商店的一块」。
    //
    // 现在用同一套 Scaffold + TopAppBar，返回箭头与插件商店一致，
    // 列表也拿到了整屏高度 —— 之前那个 heightIn(max = 520.dp) 是为了让弹层不被内容顶爆，
    // 整页之后它就成了没必要的限制，已去掉。
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_ext_market)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { reload() }, enabled = !loading) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.dsh_ext_market_refresh),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            // 计数行：与商店的「共 N 个插件」同一处位置。市场没有分类与搜索，
            // 所以不做「显示 N / 共 M」——那个数字只在「搜的是全量」时才有意义。
            if (!loading && entries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.dsh_ext_market_count, entries.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (message.isNotEmpty()) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (failure.isNotEmpty()) {
                Text(
                    text = failure,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // 列表已经有内容时，刷新失败只加一行红字，不把列表换成错误页（见 reload）。
            if (error.isNotEmpty() && entries.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.dsh_ext_market_failed, error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                // 加载中必须有字：只画一个转圈，首屏请求慢或被网络挡住时看起来就是
                // 「底下全空」—— 脚本市场那边已经为这个踩过一次（见 ScriptMarketScreen）。
                loading && entries.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(
                            text = stringResource(R.string.dsh_ext_market_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 一条都没拿到：错误与「目录合法但还没发布」是两回事，不能混成一句。
                entries.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (error.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.dsh_ext_market_failed, error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            // 失败必须可重试：弹层没有顶栏，以前只能关掉重开一遍。
                            TextButton(onClick = { reload() }) {
                                Text(stringResource(R.string.retry))
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.dsh_ext_market_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.dsh_ext_market_empty_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                else -> {
                    // 整页之后列表直接吃满剩余高度（weight(1f)）：这是 Scaffold 里的
                    // Column，高度有界，不需要再给 LazyColumn 一个 max ——
                    // 那个 520dp 是为「弹层高度是 wrap 的」加的，现在反而是个没必要的限制。
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            MarketRow(
                                entry = entry,
                                installedVersion = installed[entry.id],
                                installing = installingId == entry.id,
                                // 一次只装一个（并发下载只会两边都超时），所以别条在装时
                                // 这颗按钮要显示成不可用 —— 否则点了 install() 直接 return，
                                // 用户看到的是「按了没反应」。
                                busy = installingId != null && installingId != entry.id,
                                onOpenDetail = { detailId = entry.id },
                                onInstall = { install(entry) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

        // 安装风险确认。确认后才真正执行 doInstall。
        // 取消时什么都不发生 —— 条目还在列表里，用户随时可以再点。
        pendingInstall?.let { entry ->
            ExtensionInstallWarningDialog(
                extensionName = entry.name,
                declaredCaps = entry.declaredCaps,
                onConfirm = {
                    pendingInstall = null
                    doInstall(entry)
                },
                onDismiss = { pendingInstall = null },
            )
        }
}

/**
 * 市场里的一行：名称 + 作者 + 版本/能力标签 + 说明 + 安装按钮。
 *
 * 整卡可点进详情（与商店瓦片一致）：点了没反应会让用户看不出这是个可交互对象，
 * 而 id / 校验值 / 最低宿主版本这些「装之前该能核对」的信息也只能在详情里看全。
 */
@Composable
private fun MarketRow(
    entry: MarketEntry,
    installedVersion: String?,
    installing: Boolean,
    /** 别的条目正在装：这颗按钮不可用（见调用点）。 */
    busy: Boolean,
    onOpenDetail: () -> Unit,
    onInstall: () -> Unit,
) {
    // 已装的版本与目录里的不同才算「可更新」：只按 id 判断会把「装了旧版」也显示成已安装，
    // 用户就再也不会去点它。
    val upToDate = installedVersion != null && installedVersion == entry.version
    Card(
        onClick = onOpenDetail,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.author.isNotEmpty()) {
                        Text(
                            text = entry.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 图标按钮而不是文字按钮：「可更新」这类文案会把卡片顶宽，
                // 而 40dp 的图标与商店瓦片右上是同一颗、同一个位置。
                FilledTonalIconButton(
                    onClick = onInstall,
                    enabled = !installing && !upToDate && !busy,
                    modifier = Modifier.size(40.dp),
                ) {
                    when {
                        installing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        upToDate -> Icon(
                            Icons.Outlined.Check,
                            contentDescription = stringResource(R.string.dsh_ext_market_installed),
                        )
                        else -> Icon(
                            if (installedVersion == null) Icons.Outlined.Download else Icons.Outlined.SystemUpdate,
                            contentDescription = stringResource(
                                if (installedVersion == null) {
                                    R.string.dsh_ext_market_install
                                } else {
                                    R.string.dsh_ext_market_update
                                },
                            ),
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 版本与能力是目录里**真的有的**字段。商店那行还有下载量/星标，
                // 但 MarketEntry 没有这两个字段 —— 不伪造、不补 0，宁可比商店少两个标签。
                if (entry.version.isNotEmpty()) {
                    ModuleLabel(
                        text = "v${entry.version}",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.declaredCaps.isNotEmpty()) {
                    ModuleLabel(
                        text = stringResource(
                            R.string.dsh_ext_caps,
                            entry.declaredCaps.take(3).joinToString(", "),
                        ),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (entry.description.isNotEmpty()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 市场条目详情。
 *
 * 排版照 [DshPluginDetailSheet]：24/24/32 的内边距、spacedBy(10)、headlineSmall 标题、
 * 能力用一块 12dp 圆角的 Surface、字段用「左标签右值」的 DetailRow、主按钮在底部。
 *
 * 为什么值得为它开一个弹层：市场条目是从网络装进来的东西，装之前用户能核对的只有
 * id（装进哪个目录）、sha256（装的是不是目录里那一份）、minApi（这台宿主够不够新）。
 * 这些以前在界面上**完全看不到**，属于功能性缺失而不只是视觉不齐。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarketDetailSheet(
    entry: MarketEntry,
    installedVersion: String?,
    installing: Boolean,
    /** 别的条目正在装：主按钮不可用（见调用点）。 */
    busy: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val upToDate = installedVersion != null && installedVersion == entry.version

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (entry.author.isNotEmpty()) {
                Text(
                    text = entry.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (entry.version.isNotEmpty()) {
                    ModuleLabel(
                        text = "v${entry.version}",
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (upToDate) {
                    ModuleLabel(
                        text = stringResource(R.string.dsh_ext_market_installed),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else if (installedVersion != null) {
                    ModuleLabel(
                        text = stringResource(R.string.dsh_ext_market_update),
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            if (entry.description.isNotEmpty()) {
                Text(text = entry.description, style = MaterialTheme.typography.bodyMedium)
            }
            // 声明能力整块摆出来，与扩展页详情弹层同一套写法：能力名可能很长，
            // 逗号串成一句会看不清到底声明了几条。
            if (entry.declaredCaps.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.dsh_ext_detail_caps_all),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        for (cap in entry.declaredCaps) {
                            Text(
                                text = "· " + cap,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            MarketDetailRow(stringResource(R.string.dsh_ext_detail_id), entry.id)
            if (installedVersion != null) {
                MarketDetailRow(stringResource(R.string.dsh_ext_market_field_installed), installedVersion)
            }
            MarketDetailRow(
                stringResource(R.string.dsh_ext_market_field_minapi),
                if (entry.minApi <= 1) {
                    stringResource(R.string.dsh_ext_market_field_minapi_any)
                } else {
                    entry.minApi.toString()
                },
            )
            MarketDetailRow(stringResource(R.string.dsh_ext_market_field_url), entry.downloadUrl)
            // 校验值也摆出来：目录与安装包都来自网络，这是用户唯一能自己核对的证据。
            MarketDetailRow(stringResource(R.string.dsh_ext_market_field_sha), entry.sha256)

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    installing -> CircularProgressIndicator(Modifier.size(20.dp))
                    upToDate -> Unit
                    else -> Button(onClick = onInstall, enabled = !busy) {
                        Icon(
                            if (installedVersion == null) Icons.Outlined.Download else Icons.Outlined.SystemUpdate,
                            null,
                            Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                if (installedVersion == null) {
                                    R.string.dsh_ext_market_install
                                } else {
                                    R.string.dsh_ext_market_update
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 详情里的一行「标签 + 值」。与插件详情弹层同一版式（左 96dp 标签，值占余下宽度）。
 *
 * 名字带 Market 前缀：`ui.component` 包里 DshPluginDetail.kt 已经有一个同签名的
 * file-private `DetailRow`，两者互不可见但同名会让人误以为是同一个组件。 */
@Composable
private fun MarketDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}
