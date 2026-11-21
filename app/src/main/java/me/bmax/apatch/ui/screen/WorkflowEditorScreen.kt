package me.bmax.apatch.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.ui.component.WorkflowDefinitionEditor
import me.bmax.apatch.res.R as CoreR
import me.bmax.apatch.dsh.workflow.WorkflowStore
import me.bmax.apatch.dsh.workflow.WorkflowSchema

/**
 * 工作流编辑页 —— **次级页面**（导航进出的那种）。
 *
 * 以前编辑是覆盖层：设置页里点「编辑」只是把一个 Surface 盖在当前页上，返回栈里没有它，用户
 * 按返回时不知道会回到哪里；审批那条路又要在弹窗里再套一层弹窗。现在它是导航图里的一个目的地：
 *
 *  - 设置页：新建（先填名字）/ 编辑 → `navigate(WorkflowEditorScreenDestination(...))`
 *  - 保存 → 写盘 + `popBackStack()`；取消（含内容里的「放弃改动」确认）→ `popBackStack()`
 *  - 审批那条（AI 发起、可能在任何前台页面弹出）仍走 `WorkflowEditorDialog` 的全屏弹窗，
 *    内容与这一页**完全同一份**（[WorkflowDefinitionEditor]），不会各写一套。
 *
 * ## 两条与审批弹窗**不同**的规则（都只在这一页生效）
 *
 * 1. **自动保存**：普通编辑每改一次就排队落盘（见 `WorkflowDefinitionEditor` 的 autosave 分支）。
 *    AI 审批那一份是临时草稿 —— 未批准的改动绝不能提前覆盖正式流程定义。
 * 2. **名称唯一**：新建要先填名字（重名/空名都不许建），改名也走同一判据。
 *    审批编辑不改名（它改的是"要不要照这份跑"，不是"这叫什么"）。
 */
@Destination<RootGraph>
@Composable
fun WorkflowEditorScreen(
    navigator: DestinationsNavigator,
    workflowId: String = "",
    /** 新建：由列表页先用 [WorkflowNameDialog] 收名字，创建成功后才带着 id 进来。 */
    newName: String = "",
) {
    val ctx = LocalContext.current
    val initial = remember(workflowId) {
        when {
            workflowId.isNotEmpty() -> WorkflowStore.list(ctx).firstOrNull { it.id == workflowId }
            else -> null
        }
    }
    // 定义读不到（被删了 / 文件坏了 / 新建时没带 id）：不留一屏空白，退回列表。
    if (initial == null) {
        LaunchedEffect(Unit) { navigator.popBackStack() }
        return
    }
    WorkflowDefinitionEditor(
        initial = initial,
        title = stringResource(CoreR.string.dsh_workflow_editor_title),
        // 自动保存：界面每次改动都会走这里，返回的是落盘后的定义（含新 revision）。
        // 冲突/失败返回 null，编辑器会显示状态并保留草稿，不会假装已保存。
        //
        // 前置 revision **必须用 edited.revision**，不能用外层的 initial.revision。
        //
        // 这里踩过一个让「保存功能失效」的坑：编辑器内部维护 savedRevision 并在每次落盘后
        // 更新它，调用 saver 时把当前值塞进 edited.revision 一起传下来
        // （见 WorkflowEditorSurface 的 persistNow：saver(current.copy(revision = savedRevision))）。
        // 而这里原先写死 initial.revision —— 那是**进入页面时**的值，是个常量。
        // 第一次保存：磁盘 revision == initial.revision，成功，磁盘变成 +1。
        // 第二次保存起：磁盘 revision 已 +1，而 expected 仍是 initial.revision → 永远拒绝。
        // 用户看到的就是「第一次改动存住了，之后怎么改都存不住」，也就是「保存功能失效」。
        autosave = { edited -> WorkflowStore.saveIfRevision(ctx, edited, edited.revision) },
        onSave = { edited ->
            // 手动保存 = 立刻落盘并退出。同样用 edited.revision（理由见上）。
            WorkflowStore.saveIfRevision(ctx, edited, edited.revision)
            navigator.popBackStack()
        },
        onCancel = { navigator.popBackStack() },
        newName = newName,
    )
}

/**
 * 新建流程前收名字的弹窗。
 *
 * 为什么先收名字再进编辑页：编辑页里改名字要多一次进出（先建一个无名的，进去改，再返回看列表），
 * 而列表页那一行**只会显示名字** —— 名字缺失时用户面对的是「未命名」，选不中自己刚建的那个。
 *
 * 判据与 [WorkflowStore.nameTaken] 同一处：trim + 忽略大小写，排除自己。
 * 建成功（真的写盘了）才导航 —— 只创建内存对象然后导航，会在编辑页开局就读不到定义。
 */
@Composable
internal fun WorkflowNameDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    fun validate(raw: String): String? {
        val name = raw.trim()
        return when {
            name.isEmpty() -> ctx.getString(CoreR.string.dsh_workflow_name_empty)
            name.length > WorkflowSchema.MAX_NAME_CHARS ->
                ctx.getString(CoreR.string.dsh_workflow_name_too_long, WorkflowSchema.MAX_NAME_CHARS)
            WorkflowStore.nameTaken(ctx, name) -> ctx.getString(CoreR.string.dsh_workflow_name_taken)
            else -> null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(CoreR.string.dsh_workflow_name_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = 0
                },
                label = { Text(stringResource(CoreR.string.dsh_workflow_name_hint)) },
                isError = error != 0,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != 0) {
                Text(
                    text = ctx.getString(error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val problem = validate(text)
                    if (problem != null) {
                        error = when {
                            text.trim().isEmpty() -> CoreR.string.dsh_workflow_name_empty
                            text.trim().length > WorkflowSchema.MAX_NAME_CHARS ->
                                CoreR.string.dsh_workflow_name_too_long
                            else -> CoreR.string.dsh_workflow_name_taken
                        }
                        return@TextButton
                    }
                    val name = text.trim()
                    busy = true
                    scope.launch {
                        // 先把文件**建出来**再导航：编辑页开头是按 id 读盘的，
                        // 只造一个内存对象、不写盘，进去就立刻"读不到 → 自动退回"。
                        val created = withContext(Dispatchers.IO) {
                            val def = starter(name)
                            WorkflowStore.saveIfRevision(ctx, def, 0)?.id
                        }
                        busy = false
                        if (created != null) onCreate(created) else error = CoreR.string.dsh_workflow_name_create_failed
                    }
                },
            ) { Text(stringResource(CoreR.string.dsh_workflow_name_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
