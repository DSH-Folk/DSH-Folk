package me.bmax.apatch.ui.screen.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshDeepClean
import me.bmax.apatch.ui.component.ExpressiveCard
import me.bmax.apatch.util.ui.showToast

/**
 * 「深度清理」页：功能页那张「清理资源和缓存」卡片**长按**进来的。
 *
 * 与点进去的浅清理分开，是因为删的东西不是一个量级：浅清理只动应用自己的主题/媒体/缓存，
 * 而这里会动**运行时（rootfs）里的临时文件、apt/npm 缓存、日志**，以及**共享存储里的
 * 大文件与大文件夹**。所以这一页有三条硬性纪律：
 *
 * 1. **先警告**：页头常驻一段说明（删除直接生效、不进回收站，请自己确认里面没有要留的东西），
 *    确认弹窗里再说一次。
 * 2. **按分区展示**：运行时 / 应用内部存储 / 共享存储三块，每块用 `StatFs` 报出所在分区的
 *    总量与可用量 —— 用户看的不该只是"这几项一共多大"，还得知道删了能腾出哪个分区。
 * 3. **失败如实反馈**：删除别的项成功、这一项失败（被占用、没权限）时逐条列出来，不合并成
 *    一句"清理完成"。
 *
 * 清单与阈值在 [DshDeepClean] 里，那里也写清了**哪些路径绝不能进清单**。
 */
@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DshDeepCleanScreen(navigator: DestinationsNavigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var sections by remember { mutableStateOf<List<DshDeepClean.Section>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    // 「扫过」与「扫出来是空的」是两件事：scan() 恒返回三块 Section，所以不能拿
    // sections.isNotEmpty() 反推（那是在耦合 scan 的实现细节）。没有这个标志，进页那一瞬间
    // 的空列表会被渲染成「没有可清理的项目」——而用户根本还没扫。
    //
    // 刻意不上 rememberSaveable：重建（旋转 / 进程回收）后 Section 活不下来，留着
    // scanned=true 配一个空列表，恰好又变回上面那句误导文案。跟 sections 同生命周期最安全：
    // 一起回落到「未扫描」，与「进页不自动扫」的语义一致。
    var scanned by remember { mutableStateOf(false) }
    // 勾选集合只存**顶层选择**：勾父目录时把已选的子项剔掉（父覆盖子，统计不重复）
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirming by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }
    // 删除进度：条目可能是十万级文件的大目录，一条删好几秒，没进度的话底栏按钮按下去像死了。
    // deleting==null 表示没在删；非 null 时是 (已完成, 总数, 当前路径)。
    var deleting by remember { mutableStateOf<Triple<Int, Int, String>?>(null) }

    /**
     * 扫描只由**用户**触发：顶栏刷新、未扫描时的「开始扫描」、删完后的重扫。
     *
     * 进页不自动跑。以前这里是 `LaunchedEffect(scanKey)`，首次组合必跑 —— 而扫描要递归
     * 统计共享存储里每个目录的体积（见 DshDeepClean 的 KDoc），进页就等于每次点进来先卡
     * 十几秒，用户只是想把这一页看一眼也躲不掉。
     *
     * 没有沿用「scanKey 初值设成哨兵、在 effect 里跳过 0」那种写法：那让「不自动扫」变成一条
     * 靠魔法数维持的约定，将来谁改了初值或 `scanKey++` 就静默退回进页即扫，而文本门禁看不见
     * 这种语义回归。这里干脆删掉 scanKey，让「扫描只能由用户触发」是结构上的事实。
     *
     * 扫描必须在 IO 上：rootfs 是十万级文件。
     */
    fun startScan() {
        if (scanning) return
        // 同步置位：按钮的 enabled 依赖它，异步置位会在两次点击之间留一个窗口
        scanning = true
        selected = emptySet()
        scope.launch {
            sections = withContext(Dispatchers.IO) { DshDeepClean.scan(context) }
            scanning = false
            scanned = true
        }
    }

    val allTargets = sections.flatMap { it.targets }

    /** 这一行显示为勾选 = 它自己被选中，或它的某个祖先被选中（父目录覆盖子项）。 */
    fun checked(path: String): Boolean =
        selected.any { it == path || DshDeepClean.isUnder(path, it) }

    fun toggle(path: String, on: Boolean) {
        val next = selected.toMutableSet()
        if (on) {
            // 勾父目录：把已经勾上的子项剔掉 —— 否则同一份数据被算两次
            next.removeAll { it != path && DshDeepClean.isUnder(it, path) }
            next.add(path)
        } else {
            next.remove(path)
            // 取消父目录：它的子项也跟着取消（它们本来是被父覆盖着显示勾选的）
            next.removeAll { DshDeepClean.isUnder(it, path) }
        }
        selected = next
    }

    val chosen = allTargets.filter { checked(it.path) }
    val chosenBytes = chosen.sumOf { it.bytes }

    fun clean() {
        val paths = chosen.map { it.path }
        if (paths.isEmpty()) return
        scope.launch {
            deleting = Triple(0, paths.size, "")
            val result = withContext(Dispatchers.IO) {
                DshDeepClean.delete(context, paths) { done, total, current ->
                    // 回调在 IO 线程上：进度状态是 Compose State，切回主线程再写
                    scope.launch(Dispatchers.Main) { deleting = Triple(done, total, current) }
                }
            }
            deleting = null
            failed = result.failed
            showToast(context, context.getString(R.string.dsh_clean_done, result.deleted))
            // 删完重扫：留下的与没删掉的要按真实情况重新列
            startScan()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dsh_clean_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { startScan() }, enabled = !scanning) {
                        Icon(
                            Icons.Outlined.Refresh,
                            // 还没扫过时不能叫「重新扫描」：这页以前进页即扫，那个标签一直是对的，
                            // 现在第一次进来读屏软件会听到一句与事实不符的说明。
                            contentDescription = stringResource(
                                if (scanned) R.string.dsh_clean_rescan else R.string.dsh_clean_start_scan,
                            ),
                        )
                    }
                },
            )
        },
        bottomBar = {
            // 底栏常驻：选中项与合计在任何滚动位置都看得见
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                R.string.dsh_clean_selected,
                                chosen.size,
                                Formatter.formatFileSize(context, chosenBytes),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { confirming = true }, enabled = chosen.isNotEmpty()) {
                        Text(stringResource(R.string.dsh_clean_action))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 页头常驻警告：删除直接生效，没有回收站
            item(key = "warning") {
                ExpressiveCard(flat = true) {
                    Text(
                        text = stringResource(R.string.dsh_clean_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }

            // 未扫描态：进页不自动扫，所以这里必须是一个明确的入口，不能是「没有可清理的项目」
            // —— 后者会让用户以为真的没东西可清。
            if (!scanned && !scanning) {
                item(key = "not_scanned") {
                    ExpressiveCard(flat = true) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.dsh_clean_not_scanned),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { startScan() }) {
                                Text(stringResource(R.string.dsh_clean_start_scan))
                            }
                        }
                    }
                }
            }

            if (scanning) {
                item(key = "scanning") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.dsh_clean_scanning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            for (section in sections) {
                item(key = "section:" + section.titleRes) {
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(
                            text = stringResource(section.titleRes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(
                                R.string.dsh_clean_partition,
                                section.partitionPath,
                                Formatter.formatFileSize(context, section.freeBytes),
                                Formatter.formatFileSize(context, section.totalBytes),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                section.noteRes?.let { note ->
                    item(key = "note:" + section.titleRes) {
                        Text(
                            text = stringResource(note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                items(section.targets, key = { it.path }) { target ->
                    CleanTargetRow(
                        kind = stringResource(target.kindRes),
                        path = target.path,
                        sizeText = Formatter.formatFileSize(context, target.bytes),
                        checked = checked(target.path),
                        onToggle = { on -> toggle(target.path, on) },
                    )
                }
            }

            // 只有真扫过之后才可能说「没有可清理的项目」
            if (scanned && !scanning && allTargets.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.dsh_clean_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.dsh_clean_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dsh_clean_confirm_text,
                        Formatter.formatFileSize(context, chosenBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    clean()
                }) { Text(stringResource(R.string.dsh_clean_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // 删除进行时的进度对话框：不可取消（删一半中断会留下半删的目录，比删完更糟），
    // 删完由 clean() 把 deleting 置空自动消失。
    deleting?.let { (done, total, current) ->
        AlertDialog(
            onDismissRequest = { /* 删除中不允许关 */ },
            title = { Text(stringResource(R.string.dsh_clean_deleting_title)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.dsh_clean_deleting_count, done, total),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (current.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = current,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {},
        )
    }

    if (failed.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { failed = emptyList() },
            title = { Text(stringResource(R.string.dsh_clean_failed_title, failed.size)) },
            text = {
                // 限高 + 自己滚，不把对话框顶出屏幕；里面的 Text 不单独限高
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = stringResource(R.string.dsh_clean_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (p in failed) {
                        Text(
                            text = p,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { failed = emptyList() }) {
                    Text(stringResource(R.string.close))
                }
            },
        )
    }
}

/**
 * 一行清理目标：类别 + 路径 + 体积，整行可勾。
 *
 * 勾选按仓库既有写法：`Modifier.toggleable(value, role = Role.Checkbox, …)` 拿手势与语义，
 * `Checkbox(checked, onCheckedChange = null)` 只做显示 —— 两处都能点会出现"点文字不勾、
 * 点方块才勾"这种不一致。
 */
@Composable
private fun CleanTargetRow(
    kind: String,
    path: String,
    sizeText: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    ExpressiveCard(flat = true) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(kind, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = sizeText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
