package me.bmax.apatch.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.WorkflowEditorScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshNativeBridge
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.WorkflowApprovals
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowEngine
import me.bmax.apatch.dsh.WorkflowPermissions
import me.bmax.apatch.dsh.WorkflowRun
import me.bmax.apatch.dsh.WorkflowRunState
import me.bmax.apatch.dsh.WorkflowStore
import me.bmax.apatch.dsh.WorkflowValidator
import me.bmax.apatch.ui.component.WorkflowDefinitionEditor
import me.bmax.apatch.ui.component.WorkflowDetailDialog
import me.bmax.apatch.ui.component.WorkflowGraphPreview
import org.json.JSONObject
import me.bmax.apatch.res.R as CoreR
import me.bmax.apatch.dsh.workflow.WorkflowApprovals
import me.bmax.apatch.dsh.workflow.WorkflowStore
import me.bmax.apatch.dsh.workflow.WorkflowValidator
import me.bmax.apatch.dsh.workflow.WorkflowDefinition
import me.bmax.apatch.dsh.workflow.WorkflowRun
import me.bmax.apatch.dsh.workflow.WorkflowRunState
import me.bmax.apatch.dsh.workflow.isStaleFormat
import me.bmax.apatch.dsh.workflow.toJson

/**
 * 设置 → 功能 → 自动化工作流。
 *
 * 这一页回答用户三个问题：**开没开**、**AI 到底能不能调它**、**刚才那次跑成什么样了**。
 * 第三个尤其重要 —— 工作流可以在后台跑很久，用户需要一个不依赖 agent 的窗口自己看。
 *
 * 这里点「运行」时不弹审批框：按按钮的人就是批准的人，而且这一页已经把流程图、能力清单、
 * 危险命令警告都摆在同一屏了。容器里的 AI 走的是另一条路（黄/红弹窗 + 编辑 + diff），
 * 因为它不是站在屏幕前的人。两条路的**权限检查**是一致的（都要先过 [WorkflowPermissions]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun WorkflowScreen(navigator: DestinationsNavigator) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences(DshEnv.PREF, android.content.Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(DshEnv.KEY_WORKFLOW_ENABLED, false)) }
    var listKey by remember { mutableIntStateOf(0) }
    // 多入口的流程：点「运行」时先弹这个选从哪张开始卡片跑。
    // 存的是整份定义（弹窗要读 triggers），关闭时置 null。
    var entryPickerDef by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var detailRun by remember { mutableStateOf<WorkflowRun?>(null) }
    // 点流程主体 → 详情弹窗（名称/描述/可缩放预览/编辑/删除），不在列表行里就地展开。
    var detailDef by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var dangerousAck by remember { mutableStateOf(false) }
    // 运行/取消之后列表与详情要跟着变；引擎的 tick 是所有状态推进的汇合点。
    val tick by WorkflowEngine.tick.collectAsState()

    // 打开这一页也做一次对账：桥平时只在有请求时才跑 reconcileInterrupted，而用户完全可能
    // 一直没让 AI 碰过工作流 —— 那一页上就会永久挂着一个 running（其实是进程被杀掉的）运行。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            WorkflowStore.reconcileInterrupted(ctx.applicationContext)
            // 升级 App 后容器可能一直没重启：这里的 dsh-workflow / 指南 / 样例就还是旧的（甚至没有）。
            DshRuntime.refreshContainerCli()
        }
        listKey++
    }
    // 用户自己建/改流程：与审批时的编辑页是**同一个** [WorkflowDefinitionEditor]。
    // 新建要先在弹窗里收名字（重名/空名都不许建），建成功后才导航进编辑页。
    var nameDialog by remember { mutableStateOf(false) }

    val workflows = remember(listKey, tick) { WorkflowStore.list(ctx) }
    val runs = remember(listKey, tick) { WorkflowStore.listRuns(ctx, limit = 12) }
    // 自检：agent 到底有没有拿到「工具 + 指南」。缺哪一项、下一步做什么，都摆在这一屏上 ——
    // 否则用户只能看到「AI 不会用」，而没有任何可查的证据。
    val checks = remember(listKey) { containerChecks(ctx) }
    var restartOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(CoreR.string.dsh_workflow_title)) },
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(CoreR.string.dsh_workflow_switch), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(CoreR.string.dsh_workflow_switch_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        prefs.edit().putBoolean(DshEnv.KEY_WORKFLOW_ENABLED, on).apply()
                        if (on) scope.launch { withContext(Dispatchers.IO) { DshRuntime.refreshContainerCli() } }
                        listKey++
                    },
                )
            }
            if (!enabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(CoreR.string.dsh_workflow_disabled_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            val tool = readToolStatus(ctx)
            Text(
                stringResource(CoreR.string.dsh_workflow_tools_status, tool.state),
                style = MaterialTheme.typography.bodySmall,
            )
            if (tool.state != "ok") {
                Text(
                    stringResource(CoreR.string.dsh_workflow_tools_restart),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(CoreR.string.dsh_workflow_selfcheck), style = MaterialTheme.typography.labelLarge)
            for (c in checks) {
                Text(
                    (if (c.ok) "\u2713 " else if (c.warn) "\u26a0 " else "\u2717 ") + stringResource(c.labelRes) +
                        (if (c.detail.isBlank()) "" else "：" + c.detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (c.ok) MaterialTheme.colorScheme.onSurfaceVariant
                    else if (c.warn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
            }
            if (checks.any { !it.ok && !it.warn }) {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { DshRuntime.refreshContainerCli() }
                        listKey++
                    }
                }) { Text(stringResource(CoreR.string.dsh_workflow_fix_assets)) }
            }
            TextButton(onClick = { restartOpen = true }, enabled = enabled) {
                Text(stringResource(CoreR.string.dsh_workflow_restart_dsh))
            }
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(CoreR.string.dsh_workflow_list_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { nameDialog = true }, enabled = enabled) {
                    Text(stringResource(CoreR.string.dsh_workflow_new))
                }
            }
            if (workflows.isEmpty()) {
                Text(
                    stringResource(CoreR.string.dsh_workflow_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            for (def in workflows) {
                Spacer(Modifier.height(8.dp))
                // 校验结果就地算：定义不合法时**运行按钮点不动**，并把同一批错误显示给用户
                // （与 AI 收到的那份错误同源，省得「AI 说能跑、这里跑不了」）。
                val errors = remember(def) {
                    WorkflowValidator.validate(def, DshNativeBridge.Cap.entries.map { it.id }.toSet()).errors
                }
                WorkflowRow(
                    def = def,
                    lastState = runs.firstOrNull { it.workflowId == def.id }?.state,
                    enabled = enabled,
                    valid = errors.isEmpty(),
                    errors = errors,
                    onOpenDetail = { detailDef = def },
                    onRun = {
                        // 多入口时先让用户选从哪张开始卡片跑。
                        //
                        // 一个流程可以有多张「开始卡片」（对齐点击助手），而 engine 的默认
                        // 行为是取第一张。多入口时静默取第一张 = 「点了运行，跑的却是另一个入口」，
                        // 用户完全看不出来 —— 所以这里必须问一次。
                        val entries = def.triggers
                        if (entries.size > 1) {
                            entryPickerDef = def
                        } else {
                            scope.launch {
                                if (withContext(Dispatchers.IO) { awaitPermissions(ctx, def) }) {
                                    withContext(Dispatchers.IO) {
                                        WorkflowEngine.start(
                                            ctx.applicationContext,
                                            def,
                                            emptyList(),
                                            // 页面上的勾选就是「含危险命令也放行」的凭据；没勾就 false。
                                            allowDangerous = dangerousAck && def.declaresDangerous,
                                            entryId = entries.firstOrNull()?.id,
                                        )
                                    }
                                    listKey++
                                }
                            }
                        }
                    },
                )
            }

            // 入口选择弹窗：多张「开始卡片」时问一次从哪张开始跑。
            entryPickerDef?.let { pd ->
                EntryPickerDialog(
                    def = pd,
                    onDismiss = { entryPickerDef = null },
                    onPick = { entryId ->
                        entryPickerDef = null
                        scope.launch {
                            if (withContext(Dispatchers.IO) { awaitPermissions(ctx, pd) }) {
                                withContext(Dispatchers.IO) {
                                    WorkflowEngine.start(
                                        ctx.applicationContext,
                                        pd,
                                        emptyList(),
                                        allowDangerous = dangerousAck && pd.declaresDangerous,
                                        entryId = entryId,
                                    )
                                }
                                listKey++
                            }
                        }
                    },
                )
            }

            // 详情弹窗：名称 / 描述 / 可缩放预览 / 编辑 / 删除。
            detailDef?.let { dd ->
                WorkflowDetailDialog(
                    def = dd,
                    lastStateText = runs.firstOrNull { it.workflowId == dd.id }?.state?.id,
                    onDismiss = { detailDef = null },
                    onEdit = {
                        detailDef = null
                        navigator.navigate(WorkflowEditorScreenDestination(workflowId = dd.id)) {
                            launchSingleTop = true
                        }
                    },
                    onDelete = {
                        detailDef = null
                        WorkflowStore.delete(ctx, dd.id)
                        listKey++
                    },
                    onMetaChange = { newName, newDesc ->
                        val trimmed = newName.trim()
                        // 空名不落盘：流程在列表里只显示名字，空名等于让它从列表上消失。
                        if (trimmed.isNotEmpty() && !WorkflowStore.nameTaken(ctx, trimmed, dd.id)) {
                            WorkflowStore.saveIfRevision(
                                ctx,
                                dd.copy(name = trimmed, description = newDesc),
                                dd.revision,
                            )
                        }
                    },
                )
            }

            if (runs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(CoreR.string.dsh_workflow_runs_title), style = MaterialTheme.typography.titleSmall)
                for (run in runs) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        tonalElevation = 1.dp,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(run.workflowName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(
                                        CoreR.string.dsh_workflow_run_summary,
                                        run.state.id,
                                        run.steps.size,
                                        run.runId,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (run.running) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (run.cancelReason.isNotBlank()) {
                                    Text(
                                        run.cancelReason,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (run.running) {
                                TextButton(onClick = {
                                    WorkflowEngine.cancel(ctx, run.runId, "canceled by user")
                                    listKey++
                                }) { Text(stringResource(CoreR.string.dsh_workflow_action_cancel)) }
                            } else {
                                // 详情走弹窗：里面给**当时那一版定义**与逐步结果 —— 用户要看的正是
                                // 「它当时到底跑了什么、结果是什么」，而不是列表里挤出来的几行。
                                TextButton(onClick = { detailRun = run }) {
                                    Text(stringResource(CoreR.string.dsh_workflow_detail))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    detailRun?.let { run ->
        RunDetailDialog(run = run, onDismiss = { detailRun = null })
    }

    if (restartOpen) {
        AlertDialog(
            onDismissRequest = { restartOpen = false },
            title = { Text(stringResource(CoreR.string.dsh_workflow_restart_title)) },
            text = {
                Text(
                    stringResource(CoreR.string.dsh_workflow_restart_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restartOpen = false
                    scope.launch {
                        withContext(Dispatchers.IO) { DshRuntime.refreshContainerCli() }
                        DshRuntime.restart()
                        listKey++
                    }
                }) { Text(stringResource(CoreR.string.dsh_workflow_restart_now)) }
            },
            dismissButton = {
                TextButton(onClick = { restartOpen = false }) {
                    Text(stringResource(CoreR.string.dsh_workflow_action_cancel))
                }
            },
        )
    }

    // 新建流程：先填名字，创建成功才进编辑页（见 [WorkflowNameDialog]）。
    if (nameDialog) {
        WorkflowNameDialog(
            onDismiss = { nameDialog = false },
            onCreate = { id ->
                nameDialog = false
                listKey++
                navigator.navigate(WorkflowEditorScreenDestination(workflowId = id)) {
                    launchSingleTop = true
                }
            },
        )
    }

    // 编辑与新建都走次级页面（WorkflowEditorScreen），不再在本页盖一层。

}

/**
 * 用户自建流程的起点：**空定义**（一个节点、一条连线都没有）。
 *
 * ## 为什么不再预置「入口 + 一条 shell 命令」
 *
 * 对齐点击助手：新建出来的画布就是一张白纸，用户从选择面板里自己摆第一张卡。预置两个节点
 * 的代价是把「我们想让你这么做」写进了每一次新建 ——
 *  - 用户想做的第一件事多半不是跑 shell，得先删掉那张卡；
 *  - 预置的两张卡还是**已经连好**的，想换个入口还得先把边拆掉；
 *  - 预置的 config 是第二份默认值，字段表一改，旧流程文件里就躺着一份旧写法（历史上
 *    出过「预设卡片缺 autostartDsh」那种不一致）。
 *
 * ## 空模板必须配的那一半：开始卡片要能加得进来
 *
 * 校验器要求「有节点就要有开始卡片」，所以空画布下用户的第一张卡必须是入口。
 * 选择面板的第一组因此是「触发器」里的开始卡片（见 EDITOR_NODE_CATALOG 的第一项）；
 * 少了它，用户加什么都会得到一份跑不起来的流程 —— 这两件事必须同批改。
 *
 * ## 代价说在明处
 *
 * 空定义过不了校验：新建后还没加卡片的流程，在列表里会标「不合法」、运行按钮点不动。
 * 这是真话而不是缺陷 —— 它确实跑不起来；等用户加上第一张卡就自动恢复。
 */
internal fun starter(name: String): WorkflowDefinition =
    WorkflowDefinition(
        id = WorkflowStore.newId(),
        name = name,
        description = "",
        declaredCaps = emptyList(),
        // 空模板：节点与连线都由用户在画布上自己加（第一张通常是开始卡片）。
        nodes = emptyList(),
        edges = emptyList(),
    )

/**
 * 列表里的一行流程。
 *
 * 与上一版最大的不同：**点流程主体是打开详情弹窗**，不再在行里就地展开。
 * 就地展开的问题是：展开的那块既装流程图又装权限又装按钮，行高忽大忽小，
 * 用户滚列表时上下文一直在跳；而"看详情"和"改流程"本来就是两件事。
 *
 * 行内只保留一个「运行」——它是这一页唯一的高频操作，其余全部收进详情弹窗里。
 */
@Composable
private fun WorkflowRow(
    def: WorkflowDefinition,
    lastState: WorkflowRunState?,
    enabled: Boolean,
    valid: Boolean,
    errors: List<String>,
    onOpenDetail: () -> Unit,
    onRun: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickableRow(onOpenDetail),
            ) {
                Text(def.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(CoreR.string.dsh_workflow_row_summary, def.nodes.size, def.edges.size) +
                        (lastState?.let { " · " + it.id } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onRun, enabled = enabled && valid) { Text(stringResource(CoreR.string.dsh_workflow_action_run)) }
        }
        // 旧格式单独给一条明确的横幅：这不是"参数没填对"，而是整份定义作废了。
        // 两者的补救办法完全不同（前者改参数，后者只能重建），混在一起说会误导。
        if (def.isStaleFormat()) {
            Text(
                stringResource(CoreR.string.dsh_wf_stale_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                stringResource(CoreR.string.dsh_wf_stale_body),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 不合法时把原因摆在行下面：运行按钮是灰的，不说为什么等同于坏的。
        if (!valid) {
            Text(
                stringResource(CoreR.string.dsh_workflow_invalid_run) + " " + errors.size,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 运行前把权限检查弹出来并等结论（弹窗在 [me.bmax.apatch.ui.component.WorkflowDialogHosts]）。 */
private suspend fun awaitPermissions(
    ctx: android.content.Context,
    def: WorkflowDefinition,
): Boolean {
    val request = WorkflowPermissions.Gate.submit(
        workflowName = def.name,
        caps = def.declaredCaps,
        reason = def.name,
    ) ?: return false
    return WorkflowPermissions.Gate.awaitDecision(request.id) == WorkflowPermissions.Gate.Decision.START
}

/**
 * 容器里的工具注册状态（插件自己写的）。
 *
 * 如实显示：`ok` = 四个工具都注册上了；`degraded`/`unavailable` = agent 只能用 `dsh-workflow`
 * CLI。用户看到这句才知道「agent 说没有 workflow_run 这个工具」是不是正常现象。
 */
/**
 * 一次运行的详情弹窗：**当时那一版定义**（流程图 + JSON）、逐步结果、用户改动 diff、日志尾部。
 *
 * 为什么是弹窗而不是就地把列表撑开：定义 + 结果 + 日志三块放在列表里会把一页拉得很长，而用户
 * 看详情时想的是「这一次到底跑了什么」，独立出来才读得清。
 */
@Composable
private fun RunDetailDialog(run: WorkflowRun, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val logs = remember(run.runId) { WorkflowStore.readLog(ctx, run.runId, fromLine = 1, maxLines = 200).first }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.dsh_workflow_detail_title, run.workflowName)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(CoreR.string.dsh_workflow_run_summary, run.state.id, run.steps.size, run.runId),
                    style = MaterialTheme.typography.labelMedium,
                )
                val def = run.definition
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(CoreR.string.dsh_workflow_detail_definition),
                    style = MaterialTheme.typography.labelLarge,
                )
                if (def != null) {
                    // 预览可缩放（真机反馈「预览图不能缩放」）：外层双向滚动 + graphicsLayer 缩放，
                    // 并按缩放后的尺寸占位 —— 只加 scale 不占位的话，放大后右半边会被裁掉。
                    var zoom by remember(run.runId) { mutableStateOf(1f) }
                    var content by remember(run.runId) { mutableStateOf(IntSize.Zero) }
                    val density = LocalDensity.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { zoom = (zoom - 0.25f).coerceAtLeast(0.75f) }) { Text("-") }
                        Text(
                            stringResource(CoreR.string.dsh_workflow_zoom, (zoom * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        TextButton(onClick = { zoom = (zoom + 0.25f).coerceAtMost(2.5f) }) { Text("+") }
                        TextButton(onClick = { zoom = 1f }) {
                            Text(stringResource(CoreR.string.dsh_workflow_zoom_reset))
                        }
                    }
                    Box(
                        Modifier
                            .height(320.dp)
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Box(
                            Modifier
                                .requiredSize(
                                    with(density) { (content.width * zoom).toDp() },
                                    with(density) { (content.height * zoom).toDp() },
                                )
                                .graphicsLayer(
                                    scaleX = zoom,
                                    scaleY = zoom,
                                    transformOrigin = TransformOrigin(0f, 0f),
                                ),
                        ) {
                            Box(Modifier.onSizeChanged { content = it }) { WorkflowGraphPreview(def) }
                        }
                    }
                    // 定义 JSON 默认收起：展开的长 JSON 会把流程图和结果挤在一起（真机截图就是糊的）
                    var showJson by remember(run.runId) { mutableStateOf(false) }
                    TextButton(onClick = { showJson = !showJson }) {
                        Text(
                            stringResource(
                                if (showJson) CoreR.string.dsh_workflow_detail_json_hide
                                else CoreR.string.dsh_workflow_detail_json_show,
                            ),
                        )
                    }
                    if (showJson) {
                        Text(
                            def.toJson().toString(2),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        stringResource(CoreR.string.dsh_workflow_detail_no_definition),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(CoreR.string.dsh_workflow_detail_result),
                    style = MaterialTheme.typography.labelLarge,
                )
                for (st in run.steps) {
                    Text(
                        st.name + " · " + st.state.id +
                            (st.exitCode?.let { " exit=" + it } ?: "") +
                            " · " + st.durationMs + "ms" +
                            (if (st.reason.isBlank()) "" else " · " + st.reason),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (st.outputTail.isNotBlank()) {
                        Text(
                            st.outputTail.takeLast(600),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (run.diff.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(CoreR.string.dsh_workflow_run_diff),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        run.diff.joinToString("\n") { it.humanize() },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (logs.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(CoreR.string.dsh_workflow_detail_logs),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        logs.takeLast(120).joinToString("\n"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.dsh_workflow_action_close)) }
        },
    )
}

/** 自检的一项。三态而不是两态：`warn` 表示「不是坏的，只是这一条现在不成立」—— 例如工具插件
 * 没注册上（agent 还能走 CLI）或者状态文件还没写出来。把 warn 画成红叉，用户就会去修一个
 * 其实能用的东西（实测里那个假红点就是这么来的）。
 */
private data class ContainerCheck(
    val labelRes: Int,
    val ok: Boolean,
    val detail: String = "",
    val warn: Boolean = false,
)

/**
 * 容器侧自检：agent 要能看到工作流，必需的东西到底在不在。
 *
 * 这几项**都是文件系统事实**（不是猜），所以用户报「AI 不会用」时，看一眼就知道缺哪一环：
 * 命令/指南/样例是即时可用的；插件与挂载项决定 agent 是否知道它存在，而插件要 DSH 重启才加载。
 */
private fun containerChecks(ctx: android.content.Context): List<ContainerCheck> {
    val rootfs = DshEnv.rootfs(ctx)
    val home = DshEnv.dshHome(ctx)
    val cli = File(rootfs, "usr/local/bin/dsh-workflow")
    val guide = File(home, "workflow-dsl.md")
    val samples = File(home, "workflow-samples")
    val plugin = File(home, "plugins/dsh-folk-workflow.mjs")
    val patch = File(home, "cordis.patch.yml")
    val sampleCount = samples.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0
    val patchHasEntry = runCatching { patch.isFile && patch.readText().contains("dsh-folk-workflow") }
        .getOrDefault(false)
    val tool = readToolStatus(ctx)
    return listOf(
        ContainerCheck(CoreR.string.dsh_workflow_selfcheck_cli, cli.isFile && cli.length() > 0L),
        ContainerCheck(CoreR.string.dsh_workflow_selfcheck_guide, guide.isFile && guide.length() > 0L, "${guide.length()} B"),
        ContainerCheck(CoreR.string.dsh_workflow_selfcheck_samples, sampleCount > 0, "$sampleCount"),
        ContainerCheck(CoreR.string.dsh_workflow_selfcheck_plugin, plugin.isFile && plugin.length() > 0L, "${plugin.length()} B"),
        ContainerCheck(CoreR.string.dsh_workflow_selfcheck_patch, patchHasEntry),
        // 工具注册：只有「红」的条件是**两条路都不通**（没有 CLI 也没有工具）。只差工具那一条
        // 时算黄：agent 照样能用 dsh-workflow 干活，用户没必要为此去修什么。
        ContainerCheck(
            CoreR.string.dsh_workflow_selfcheck_tools,
            ok = tool.state == "ok",
            detail = if (tool.reason.isBlank()) tool.state else tool.state + " — " + tool.reason.take(120),
            warn = tool.state != "ok" && cli.isFile && cli.length() > 0L,
        ),
    )
}

/** 插件写回来的诊断（工具注册状态 + 原因）。 */
private data class ToolStatus(val state: String, val reason: String)

private fun readToolStatus(ctx: android.content.Context): ToolStatus {
    val f = File(DshEnv.dshHome(ctx), "workflow-tool-status.json")
    if (!f.isFile) return ToolStatus("unknown", "")
    return runCatching {
        val o = JSONObject(f.readText())
        ToolStatus(
            state = o.optString("toolsState", o.optString("state", "unknown")),
            reason = o.optString("toolsReason", o.optString("reason", "")),
        )
    }.getOrDefault(ToolStatus("unknown", ""))
}

/** 让整行可点（行里没有别的可点元素时用它，触摸范围就是整行）。 */
private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/**
 * 「从哪个入口开始」弹窗。
 *
 * 一个流程可以有多张「开始卡片」（对齐点击助手），而 [WorkflowEngine.start] 的默认
 * 行为是取第一张。多入口时静默取第一张的后果是「点了运行，跑的却是另一个入口」——
 * 用户从界面上完全看不出来，只会觉得流程行为不对。
 *
 * 所以多入口时**必须**问一次。只有一个入口时不弹（问了也是废话）。
 */
@Composable
private fun EntryPickerDialog(
    def: WorkflowDefinition,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.dsh_workflow_pick_entry_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(CoreR.string.dsh_workflow_pick_entry_hint, def.triggers.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                // 不滚动：入口数量是人手加的，几十个已经是异常情况；
                // 真多到滚不动时用户该做的是拆流程，而不是在这个弹窗里找。
                for (t in def.triggers) {
                    // 不依赖 workflowNodeLabel（那是 WorkflowNodeCard 里的 private）：
                    // 这里的标题只是「这是哪个入口」，有名字就用名字，没名字就用节点 id ——
                    // 入口卡片通常都有名字，兜底只要不空就行。
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(t.id) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = t.name.ifBlank { t.id },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (t.description.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = t.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_channel_cancel))
            }
        },
    )
}
