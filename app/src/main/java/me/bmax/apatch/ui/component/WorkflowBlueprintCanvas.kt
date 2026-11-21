package me.bmax.apatch.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import me.bmax.apatch.dsh.WorkflowActionCatalog
import me.bmax.apatch.dsh.WorkflowNode
import me.bmax.apatch.dsh.WorkflowDefinition
import me.bmax.apatch.dsh.WorkflowEdgeKind
import me.bmax.apatch.dsh.WorkflowNodeType
import me.bmax.apatch.dsh.WorkflowPinKind
import me.bmax.apatch.dsh.WorkflowPinType
import me.bmax.apatch.dsh.WorkflowSchema
import me.bmax.apatch.dsh.WorkflowStepState
import kotlin.math.abs
import kotlin.math.max
import me.bmax.apatch.dsh.workflow.WorkflowActionCatalog
import me.bmax.apatch.dsh.workflow.WorkflowDefinition
import me.bmax.apatch.dsh.workflow.WorkflowEdgeKind
import me.bmax.apatch.dsh.workflow.WorkflowNode
import me.bmax.apatch.dsh.workflow.WorkflowNodeType
import me.bmax.apatch.dsh.workflow.WorkflowPinKind
import me.bmax.apatch.dsh.workflow.WorkflowPinSpec
import me.bmax.apatch.dsh.workflow.WorkflowPinType
import me.bmax.apatch.dsh.workflow.WorkflowSchema
import me.bmax.apatch.dsh.workflow.WorkflowStepState

private const val CARD_W_DP = 220f

/** 视口手势块的稳定 key：只用于让 pointerInput 不随重组重启。 */
private const val UNIT_VIEWPORT_GESTURE_KEY = "workflow-canvas-viewport"

/**
 * 蓝图式画布：点阵 + 贝塞尔连线 + 节点卡。
 *
 * 手势分工（对齐点击助手：View 各自 onTouchEvent，容器只管空白处平移）：
 *  - **卡片自己**接点/拖/拉线（见 [WorkflowNodeCard]）—— 不需要手算命中，也就不会再出现
 *    「只有屏幕左上角能点到」这种坐标系比错的 bug；
 *  - 画布只接「按在空白处」的拖动 → 平移；空白处点一下 → [onBlankTap]（取消选中）；
 *  - 拉线的落点判定只在**窗口坐标**里比（卡片报上来的窗口矩形 vs 手指窗口坐标），
 *    连线也画在不随平移的覆盖层上，所以线永远跟手。
 */
@Composable
internal fun WorkflowBlueprintCanvas(
    definition: WorkflowDefinition,
    positions: Map<String, Offset>,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
    stepStates: Map<String, WorkflowStepState> = emptyMap(),
    selectedNodeId: String? = null,
    /**
     * 已授予的能力 id 集合。卡片据此把自己标成"不可用"。
     *
     * 传集合而不是每张卡一个布尔：同一张卡的能力要求是从动作目录推出来的，
     * 而"哪些已授予"是全局事实 —— 把两者在画布入口合并一次，卡片就只管显示。
     */
    grantedCaps: Set<String> = emptySet(),
    /** 卡上直接改参数：传 null 时卡片参数只读（预览、详情页）。 */
    onNodeParamChange: ((nodeId: String, key: String, value: String) -> Unit)? = null,
    /** 卡上「展开」按钮：在 NONE → HALF → FULL 之间循环。 */
    onNodeToggleExpand: ((nodeId: String) -> Unit)? = null,
    /** 卡上「锁」按钮：冻结这张卡（不能拖/不能选/不能删）。 */
    onNodeToggleLock: ((nodeId: String) -> Unit)? = null,
    onNodeClick: ((String) -> Unit)? = null,
    /** 拖动中：参数是手指此刻的**绝对窗口坐标**（不是位移）。对齐点击助手在父容器里用绝对坐标算整格位移。 */
    onNodeDrag: ((id: String, windowX: Float, windowY: Float) -> Unit)? = null,
    /** 开始拖动：按下时的绝对窗口坐标，作为整格位移的基准。 */
    onNodeDragStart: ((id: String, windowX: Float, windowY: Float) -> Unit)? = null,
    onNodeDragEnd: ((id: String) -> Unit)? = null,
    /** 卡上「复制」按钮（对齐参考实现 ActionCard.initCopy）。 */
    onNodeCopy: ((id: String) -> Unit)? = null,
    /** 卡上「删除」按钮（第一下武装、第二下真删，见 deleteArmedNodeId）。 */
    onNodeDelete: ((id: String) -> Unit)? = null,
    /** 卡上铅笔（打开这一步的详细配置）。 */
    onNodeEdit: ((id: String) -> Unit)? = null,
    /** 哪张卡的删除按钮正处于「已武装」（第一下点过）状态。 */
    deleteArmedNodeId: String? = null,
    /**
     * 拉线松手：toId 非空 = 连到那张卡；为空 = 落在空白（调用方弹节点菜单）。
     * pointer 是松手时的**窗口坐标** —— 菜单里选了节点类型后，新节点要落在松手的位置。
     */
    /**
     * 拉线松手。
     *
     * [fromPin] 是**拉出来的那个针脚的种类名**（[WorkflowPinKind] 的 name）：从「成立」口拉出去
     * 和从「不成立」口拉出去，落盘的 whenExpr 不一样。之前这里只有 fromId，于是 condition 的
     * 两条分支画出来全变成默认边 —— 判断节点在真实执行时永远只走其中一条，而用户看到的图
     * 上有两条线。
     */
    onWireDrop: ((
        fromId: String,
        fromPin: String,
        toId: String?,
        pointerWindow: Offset,
        /** 松手压中的**具体数据入口针脚 id**（`p_x` / `cmd` / `title` …）；压在卡片空白处时为 null。 */
        targetPin: String?,
    ) -> Unit)? = null,
    onBlankTap: (() -> Unit)? = null,
    /**
     * 视口（缩放 + 平移）。由**宿主**持有而不是画布内部：落点、吸附、新节点坐标都要用同一套
     * 逆变换（见 [WorkflowViewport]），两处各存一份就一定会算出两个不同的落点。
     */
    viewport: WorkflowViewport = WorkflowViewport(),
    onViewportChange: ((WorkflowViewport) -> Unit)? = null,
) {
    val dotColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    // TouchTool 的 ExecutePinSlotView / PinInfo.EXECUTE_INFO 使用 colorPrimaryVariant，
    // 连线取针脚颜色；onSurfaceVariant 在深色主题下近黑，用户反馈「变黑了」。
    // Compose 主题里用 primary，与控制流针脚高亮保持一致，不用灰色中性色。
    val edgeColor = MaterialTheme.colorScheme.primary
    val trueColor = MaterialTheme.colorScheme.tertiary
    val falseColor = MaterialTheme.colorScheme.error
    val wireColor = MaterialTheme.colorScheme.primary
    // 拉线压在卡片上但接不上时用的颜色。在**组合期**取值：Canvas 的 draw lambda 不是
    // @Composable，在里面读 MaterialTheme 编译不过（同一个坑上一轮已经踩过一次）。
    val wireRejectColor = MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    // 数据针脚类型 → 颜色，在组合期算一次给绘制层查表（见 drawBlueprintEdges 的 pinColors）。
    // 只有 5 种类型，重建这点映射可忽略；好处是绘制期不必读主题。
    val pinColors = WorkflowPinType.entries.associate { it.id to workflowPinColor(it) }

    val pan = viewport.pan
    val scale = viewport.scale
    val viewportCb = rememberUpdatedState(onViewportChange)
    // 手势块里的 `viewport` **必须是这个**，不能直接读参数。
    //
    // pointerInput 的 key 是常量，块只会组合一次；它捕获的是**第一次组合时**的 viewport。
    // 直接读参数的话，每一帧都拿初始 scale 去算 zoomAround，于是缩放永远停在第一帧算出来的
    // 那个比例上、来回闪（真机表现就是「卡在 89% 闪烁」）。rememberUpdatedState 让块里
    // 读到的是最新值。
    val viewportState = rememberUpdatedState(viewport)
    // 画布在窗口里的位置：连线端点要把窗口坐标换算回画布局部坐标。
    var origin by remember { mutableStateOf(Offset.Zero) }
    // 卡片窗口矩形（卡片自己报上来）：拉线落点只在这里比，坐标口径唯一。
    // 卡片矩形同理：存 coords、用时现取（理由见 pinCoords 那段）。
    var boundsCoords by remember {
        mutableStateOf<Map<String, LayoutCoordinates>>(emptyMap())
    }
    /** 某张卡**此刻**的世界矩形（理由同 [pinWorldRect]：变换由调用方传入）。 */
    fun nodeWorldRect(id: String, panNow: Offset, scaleNow: Float): Rect? {
        val c = boundsCoords[id] ?: return null
        if (!c.isAttached) return null
        return toWorldRect(c.unclippedBoundsInWindow(), origin, panNow, scaleNow)
    }
    /** 全部卡片此刻的世界矩形（落点判定用）。 */
    fun nodeWorldRects(panNow: Offset, scaleNow: Float): Map<String, Rect> =
        boundsCoords.mapNotNull { (id, c) ->
            if (c.isAttached) id to toWorldRect(c.unclippedBoundsInWindow(), origin, panNow, scaleNow) else null
        }.toMap()
    // 画布的空白点击与拉线回调也要最新值（手势块的 key 不会因回调变化而重启）。
    val blankTapCb = rememberUpdatedState(onBlankTap)
    val wireDropCb = rememberUpdatedState(onWireDrop)
    var wireFrom by remember { mutableStateOf<String?>(null) }
    // 拉线起点那个针脚的种类（"EXEC_TRUE" / "EXEC_FALSE" / …），决定落盘时带哪个 when。
    var wireFromPin by remember { mutableStateOf("") }
    var wireAnchor by remember { mutableStateOf(Offset.Zero) }
    var wirePointer by remember { mutableStateOf(Offset.Zero) }
    // 拖拽中，指针下的**可接针脚**（key 形如 "<nodeId>:<pinKey>"）。null = 当前没有可接目标。
    //
    // 参考实现用 dragOnPin 做同一件事，并在命中变化时震一下 —— 那是"这里能接"的唯一即时
    // 反馈。没有它，用户只能靠松手之后线断没断来猜，试错成本很高。
    var wireHoverPin by remember { mutableStateOf<String?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // 针脚窗口矩形，两套键同时登记（见卡片的 onPinBounds / onPinIdBounds）：
    //  - "<节点id>:<引脚种类名>"（EXEC_IN / DATA_OUT …）—— 控制流边与按种类的调用点用；
    //  - "<节点id>:<引脚id>"（p_x / cmd / result / var）—— 数据边与数据落点用。
    // 一张卡上常有好几个同种类的数据针脚，只按种类建索引会互相覆盖，
    // 于是"线从哪颗点出去"和"落在哪个参数上"都只能靠猜。
    // ── 存 LayoutCoordinates，**不存换算后的矩形** ──
    //
    // 这里踩过一个很隐蔽的坑：原先在 onGloballyPositioned 里当场把窗口矩形逆变换成
    // 「世界坐标」再存起来。看着合理，实则错在**存的是换算结果**：
    //
    //   世界 = (窗口 - origin - pan) / scale
    //
    // 而平移画布只改 graphicsLayer、**不会重新布局** → onGloballyPositioned 不再触发 →
    // 存下来的那个世界坐标是用**旧 pan** 算的。平移之后绘制仍用这个旧值，线头就固定
    // 落后平移量那么多。卡片一旦移出可视区，Compose 更不会为它重跑布局，于是
    // "只超出边界就偏、在视口内还好"—— 用户报的正是这个形态。
    //
    // 正确做法：存 coords 本身，**用的时候现取**。boundsInWindow() 是查询式 API，
    // 每次调用都会套用**当前**的 graphicsLayer 变换，所以平移/缩放后拿到的永远是对的。
    var pinCoords by remember {
        mutableStateOf<Map<String, LayoutCoordinates>>(emptyMap())
    }
    // 只在**对象真的换了**时才写回：onGloballyPositioned 每次布局都触发，而写状态又会
    // 触发重组 → 布局，无条件赋值就是一个反馈循环（真机表现：卡片发抖）。
    // 用 === 比身份而不是比值：同一个 coords 对象在平移期间不会变，天然切断循环。
    fun putPinCoords(key: String, c: LayoutCoordinates) {
        if (pinCoords[key] === c) return
        pinCoords = pinCoords + (key to c)
    }

    /**
     * 某颗针脚**此刻**的世界矩形。取不到（未附加/未测量）返回 null。
     *
     * pan/scale 必须**由调用方传进来**（而不是在这里读外层的状态）：
     * 这个函数会在 Canvas 的 draw lambda 里被调用，那是**绘制阶段**，
     * 读状态不会登记成快照依赖 —— 平移时既不会触发重绘，拿到的也可能是过期值。
     * 传参则保证「绘制用的变换」与「换算用的变换」是同一份。
     */
    fun pinWorldRect(key: String, panNow: Offset, scaleNow: Float): Rect? {
        val c = pinCoords[key] ?: return null
        if (!c.isAttached) return null
        return toWorldRect(c.unclippedBoundsInWindow(), origin, panNow, scaleNow)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
            .onGloballyPositioned { origin = it.positionInWindow() }
            .then(
                if (readOnly) {
                    // 只读（预览）：只有点空白取消选中，没有平移。
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { blankTapCb.value?.invoke() }
                    }
                } else {
                    Modifier
                        .pointerInput(UNIT_VIEWPORT_GESTURE_KEY) {
                            // 空白处（卡片没有消费事件时才会到这里）拖 = 平移、双指 = 缩放。
                            // 与点击助手一致：范围 0.2–2.0，超出即夹住，不报错也不回弹。
                            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                // 围绕手势中心缩放，并叠上这帧的平移 —— 否则手指按住的那个点会跑。
                                viewportCb.value?.invoke(
                                    viewportState.value.zoomAround(centroid, zoomChange, panChange),
                                )
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { blankTapCb.value?.invoke() }
                        }
                },
            ),
    ) {
        // ── 背景点阵：画在**视口层**（不受内容层变换影响）──
        //
        // 点阵必须铺满整个可见区并跟着 pan 滚动，才是"无限画布"的观感。
        // 它放在内容层里只有一屏大小，内容一平移就露出空白 ——
        // 用户会以为画布到头了、移不过去（实际坐标没限制，只是没画点）。
        Canvas(Modifier.fillMaxSize()) { drawBlueprintGrid(dotColor, pan, scale) }
        // 内容层：平移只在这里应用一次。
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 变换顺序 = 先按左上角缩放、再平移，于是「屏幕 = 世界 × scale + pan」。
                    // 逆变换只有一处（[WorkflowViewport.toWorldDp]），落点/吸附都由它算。
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            // 点阵已移到视口层（见下面 pan 层），这里不再画。
            // 选中/拖动的卡画一圈虚线框。
            //
            // 为什么要有它：拖动时卡片跟着手指走，但它**是不是被选中了**没有任何视觉反馈 ——
            // 松手之后才发现"我动的是另一张卡"是这类编辑器里最常见的误操作。虚线框用的是
            // 与卡片自身尺寸无关的固定 padding，所以缩放时框也跟着缩放（它画在内容层里）。
            val framed = selectedNodeId
            if (framed != null) {
                // 框必须用**卡片实测的窗口矩形**换算，不能用写死的高度 ——
                // 卡片带参数行、会变高，写死的框会只包住上半张卡。
                //
                // ## 两个坑叠在一起，各自都要修
                //
                // 1) 矩形不能在组合期算好再被 draw lambda 捕获。
                //    拖动卡片只改 `Modifier.offset`，不重新布局、不动 boundsCoords 这个 State，
                //    组合根本不会重跑 —— 捕获的 rect 永远是按下时的旧值。用户报的
                //    「虚线框不跟卡片」就是这个：框停在原地，卡走了。
                //    所以 rect 必须在 draw lambda **内部**现取。
                //
                // 2) 但 draw lambda 里读 State 不登记快照依赖，于是「卡片高度变了」
                //    （展开参数、换状态）只更新 boundsCoords 而不重绘，框会停在旧高度。
                //    用 key(coords) 把 Canvas 包起来：coords 换了就重建节点，显式重新组合一次。
                //
                // 矩形本身来自 unclippedBoundsInWindow —— 不能用 boundsInWindow()，
                // 那个会把矩形夹到画布裁剪区上，卡片一出视野框就贴边。
                key(boundsCoords[framed]) {
                    Canvas(Modifier.fillMaxSize()) {
                        val rect = nodeWorldRect(framed, pan, scale) ?: return@Canvas
                        val pad = 8.dp.toPx()
                        drawRect(
                            color = wireColor,
                            topLeft = Offset(rect.left - pad, rect.top - pad),
                            size = androidx.compose.ui.geometry.Size(
                                rect.width + pad * 2,
                                rect.height + pad * 2,
                            ),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(10.dp.toPx(), 7.dp.toPx()),
                                ),
                            ),
                        )
                    }
                }
            }

            // 每张卡里"已接线的参数键"：NONE 档据此决定显示哪些行。
            // 由数据边（toPin / fromPin 的 to 端）算出，卡片自己不认识边表。
            val dataLinkedKeys: Map<String, Set<String>> = definition.edges
                .filter { it.kind == WorkflowEdgeKind.DATA }
                .groupBy { it.to }
                .mapValues { (_, es) -> es.map { it.toPin }.toSet() }
            // 每张卡里"已接线的针脚 id"（**含出口**）：卡片据此把针脚填成实色。
            //
            // 参考实现的 PinView.refreshPin 调 slotView.setLinked(pin.isLinked())，
            // isLinked() = !links.isEmpty() —— 一头一连上就填实色，出/入都算。
            // 我们之前只有数据入口的"已接线"信息（dataLinkedKeys，且只给参数行用），
            // 出口与控制流针脚永远画成空心，于是"看起来什么都没连上"。
            //
            // 必须同时收 from 与 to 两侧：一条边的两端各有一颗针脚，都要变实。
            val linkedPinIds: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
                for (e in definition.edges) {
                    if (e.kind == WorkflowEdgeKind.DATA) {
                        getOrPut(e.to) { mutableSetOf() }.add(e.toPin)
                        getOrPut(e.from) { mutableSetOf() }.add(e.fromPin)
                    } else {
                        // 控制流边没有 toPin/fromPin，要**按卡片的真实针脚 id** 记。
                        //
                        // 上一版写的是 WorkflowPinKind.EXEC_IN.name（"EXEC_IN"），而
                        // WorkflowSchema 造控制流针脚时 id 是 "in" / "out"
                        // （见 defaultPinsFor：WorkflowPinSpec("in", …) / ("out", …)）。
                        // 两个字符串永远对不上，于是 linked 恒为 false —— 控制流针脚
                        // 一直是空心，用户截图里那条线连着的五边形没有填充。
                        //
                        // 用 pinsOf 取真实 id 而不是硬编码 "in"/"out"：万一以后改 id，
                        // 这里跟着走，不会再次静默失配。
                        getOrPut(e.to) { mutableSetOf() }.addAll(
                            WorkflowSchema.pinsOf(definition, e.to)
                                .filter { it.kind == WorkflowPinKind.EXEC_IN }
                                .map { it.id },
                        )
                        getOrPut(e.from) { mutableSetOf() }.addAll(
                            WorkflowSchema.pinsOf(definition, e.from)
                                .filter { it.kind == WorkflowPinKind.EXEC_OUT }
                                .map { it.id },
                        )
                    }
                }
            }
            // ── 连线层：画在**卡片之下** ──
            //
            // 参考实现就是这样：CardLayoutView 的 dispatchDraw 先画背景网格、再画连线，
            // 卡片是子 View，最后才由系统叠上去 —— 所以线是被卡片**压着**的。
            //
            // 这在参考实现里不成问题，靠两点补偿：
            //   1. 针脚槽位紧贴卡片边缘（pin_top.xml 的 pinSlotBox 是第一层），
            //      线一离开针脚就立刻进入卡片下方，看不出被截断；
            //   2. 卡片有 1px 的高亮描边（colorPrimaryVariant），
            //      两张卡叠在一起时边缘清晰，能看出是两张。
            //
            // 我之前把连线挪到卡片之上来"修"遮挡，方向错了：
            // 那样线会横穿卡片表面，与参考实现的观感完全不同。现在改回下方。
            Canvas(Modifier.fillMaxSize()) {
                drawBlueprintEdges(
                    definition = definition,
                    positions = positions,
                    pinWorldRect = { key -> pinWorldRect(key, pan, scale) },
                    origin = origin,
                    pan = pan,
                    scale = scale,
                    normal = edgeColor,
                    trueColor = trueColor,
                    falseColor = falseColor,
                    dataColor = edgeColor,
                    pinColors = pinColors,
                    selectedNodeId = selectedNodeId,
                )
            }

            for (node in definition.nodes) {
                val pos = positions[node.id] ?: continue
                WorkflowNodeCard(
                    node = node,
                    status = stepStates[node.id],
                    // 能力与授予情况由宿主算好传下来：画布每帧重绘，不能在这里查权限。
                    requiredCaps = requiredCapsOf(node),
                    granted = requiredCapsOf(node).all { it in grantedCaps },
                    selected = selectedNodeId == node.id,
                    modifier = Modifier.offset(x = pos.x.dp, y = pos.y.dp),
                    onTap = { if (!node.locked) onNodeClick?.invoke(node.id) },
                    // 锁定的卡不接拖动（锁定 = 不能拖动，见 WorkflowNode.locked）。
                    // 绝对窗口坐标直接透传：整格位移由宿主按起点算，画布不插值、不累加。
                    onDrag = if (node.locked) null else ({ wx: Float, wy: Float -> onNodeDrag?.invoke(node.id, wx, wy) }),
                    onDragStart = if (node.locked) null else ({ wx: Float, wy: Float -> onNodeDragStart?.invoke(node.id, wx, wy) }),
                    onDragEnd = if (node.locked) null else ({ onNodeDragEnd?.invoke(node.id) }),
                    // 卡上直接改参数：只在编辑态开放（预览/详情传 null，输入框自动 disabled）。
                    onParamChange = onNodeParamChange?.let { cb -> { key, value -> cb(node.id, key, value) } },
                    onToggleExpand = onNodeToggleExpand?.let { { it(node.id) } },
                    onToggleLock = onNodeToggleLock?.let { { it(node.id) } },
                    onCopy = onNodeCopy?.let { cb -> { cb(node.id) } },
                    onDelete = onNodeDelete?.let { cb -> { cb(node.id) } },
                    onEdit = onNodeEdit?.let { cb -> { cb(node.id) } },
                    deleteArmed = deleteArmedNodeId == node.id,
                    // 入口节点不能删：删掉它整张图没有起点，定义立刻不合法（校验器也会报）。
                    canDelete = node.type != WorkflowNodeType.TRIGGER,
                    linkedParamKeys = dataLinkedKeys[node.id].orEmpty(),
                    linkedPinIds = linkedPinIds[node.id].orEmpty(),
                    // 拖线悬停在哪颗针脚上（key 是 "<nodeId>:<pinId>"，这里只取属于本卡的那部分）。
                    hoveredPinId = wireHoverPin
                        ?.takeIf { it.substringBefore(':') == node.id }
                        ?.substringAfter(':'),
                    // 卡片与针脚一律**只上报 LayoutCoordinates**，世界矩形在用的那一刻现算。
                    //
                    // 曾经在这里当场逆变换成世界坐标再存 —— 那正是"卡片移出边界后线就偏"的
                    // 根因：平移只改 graphicsLayer、不重新布局，onGloballyPositioned 不再触发，
                    // 存下的是**旧 pan 算出来的**世界坐标；卡片越出视口后 Compose 更不会为它
                    // 重跑布局，缓存值永远停在旧 pan 上。存 coords 则每次查询都会套用当前变换。
                    // 与 putPinCoords 同一套纪律：只在 coords **换对象**时写回。
                    //
                    // 上一版这里是无条件赋值（`boundsCoords = boundsCoords + (...)`），于是形成
                    // 「布局 → 回调 → 改状态 → 重组 → 布局」的反馈循环。真机表现就是用户报的
                    // 「虚线框不跟卡片了」：框读到的 rect 在循环里反复失效/重建，跟不上拖动。
                    // 用 === 比身份：同一个 coords 对象在拖动/平移期间不变，天然切断循环。
                    onBounds = { c ->
                        if (boundsCoords[node.id] !== c) boundsCoords = boundsCoords + (node.id to c)
                    },
                    onPinBounds = { pinKind, c -> putPinCoords("${node.id}:$pinKind", c) },
                    // 同时按引脚 id 登记：数据边与落点判定要精确到"哪一颗"。
                    onPinIdBounds = { pinId, pinKind, c ->
                        putPinCoords("${node.id}:$pinId", c)
                        putPinCoords("${node.id}:$pinKind", c)
                    },
                    onPinDrag = { pinKind, anchor, pointer ->
                        wireFrom = node.id
                        wireFromPin = pinKind
                        wireAnchor = anchor
                        wirePointer = pointer
                        // ── 拖拽中实时判「这里能不能接」──
                        //
                        // 参考实现：dragOnPin 记录落点处可接的针脚，命中变化时
                        // performHapticFeedback(LONG_PRESS)。震动是"就落这儿"的唯一即时反馈，
                        // 而且只在**变化时**震一次 —— 每一帧都震的话手会麻。
                        //
                        // 判定用 WorkflowPinSpec.canLinkFrom（参考实现 Pin.linkAble 的等价物）：
                        // 控制流只接控制流、数据只看类型互通，规则仍然只有那一处。
                        // pointer 是窗口坐标，针脚矩形是世界坐标 —— 先换算，否则拖拽中
                        // 的"可接针脚"判定会随平移整体偏移。
                        val hit = hitPinAt(toWorldPoint(pointer, origin, pan, scale), node.id, definition, pinCoords.keys) { key -> pinWorldRect(key, pan, scale) }
                        val targetSpec = hit?.let { h ->
                            WorkflowSchema.pinsOf(definition, h.nodeId).firstOrNull { it.id == h.pinId }
                        }
                        val sourceKind = WorkflowPinKind.entries.firstOrNull { it.name == pinKind }
                        val sourceSpec = WorkflowSchema.pinsOf(definition, node.id)
                            .firstOrNull { it.kind == sourceKind }
                        val linkable = targetSpec != null && sourceSpec != null &&
                            targetSpec.canLinkFrom(sourceSpec)
                        val next = if (linkable) hit?.let { h -> "${'$'}{h.nodeId}:${'$'}{h.pinId}" } else null
                        if (next != wireHoverPin) {
                            wireHoverPin = next
                            if (next != null) {
                                haptic.performHapticFeedback(
                                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                )
                            }
                        }
                    },
                    onPinDrop = { pointer ->
                        val from = wireFrom
                        val fromPin = wireFromPin
                        wireFrom = null
                        wireFromPin = ""
                        wireHoverPin = null
                        if (from != null) {
                            // 落点先找**具体针脚**：松手压在某颗数据入口上，就把线接到那个参数。
                            //
                            // 上一版只比卡片矩形，于是所有数据线一律接到下游的第一个参数 ——
                            // 用户明明落在第二个参数上，线却跑到第一个去了。
                            // 只认**数据入口**针脚：索引里还登记着引脚种类名与出口针脚，
                            // 不筛的话，松手压到自己那颗出口点、或者压在种类名那种粗索引上，
                            // 都会被当成"连到下游某个参数"。
                            // 命中判定与拖拽中的高亮**共用同一个函数**：两处各写一遍的话，
                            // 会出现「高亮说能接、松手却接不上」——那比没有高亮更让人困惑。
                            val pointerWorld = toWorldPoint(pointer, origin, pan, scale)
                            val hit = hitPinAt(pointerWorld, from, definition, pinCoords.keys) { key -> pinWorldRect(key, pan, scale) }
                            val targetPin = hit?.pinId
                            val target = hit?.nodeId
                                ?: nodeWorldRects(pan, scale).entries.firstOrNull { (id, rect) -> id != from && rect.contains(pointerWorld) }?.key
                            wireDropCb.value?.invoke(from, fromPin, target, pointer, targetPin)
                        }
                    },
                )
            }
        }

        // 拉线覆盖层：**不随平移**，两端都是「窗口坐标 - 画布原点」，所以始终贴着手指。
        if (wireFrom != null) {
            Canvas(Modifier.fillMaxSize()) {
                val a = wireAnchor - origin
                val b = wirePointer - origin
                // 拉线中的预览线也画成折线：松手之后会变成折线，预览若是曲线，
                // 松手那一下的"变形"会让人以为连错了对象。
                // 走廊取 LINK_CORRIDOR_DP（12dp），**不是**画布网格步长（24dp）。
                // 见 WorkflowLinkPath 的说明：把网格步长当走廊会让绕行半径翻倍、把卡片包住。
                // 预览线也要按源针脚方向选走法，否则松手那一下线的形状会突变。
                val sourceVertical = wireFromPin.startsWith("EXEC") || wireFromPin.isBlank()
                val path = orthogonalLinkPath(
                    a, b,
                    corridor = LINK_CORRIDOR_DP.dp.toPx(),
                    vertical = sourceVertical,
                )
                // 预览线的颜色说明此刻"松手会怎样"：
                //  - 命中可接针脚：primary（与落点高亮同色，读作"就落这儿"）；
                //  - 压在卡片上但接不上：error 的低饱和版（明确但不用告警红，那不是错误操作）；
                //  - 悬在空白：保持 wireColor（松手会弹节点面板，见 showActionSelector 的对应行为）。
                // 卡片矩形是**世界坐标**，wirePointer 是**窗口坐标** —— 必须先换算。
                // 之前直接拿窗口坐标去 contains 世界矩形：画布一平移/缩放，两个坐标系就错开，
                // "压在卡片上但接不上"的高亮会在错误的卡片上亮起（或该亮时不亮）。
                val pointerWorld = toWorldPoint(wirePointer, origin, pan, scale)
                val hoveringCardButNotLinkable = wireHoverPin == null && nodeWorldRects(pan, scale).entries.any { (id, rect) ->
                    id != wireFrom && rect.contains(pointerWorld)
                }
                val preview = when {
                    wireHoverPin != null -> wireColor
                    hoveringCardButNotLinkable -> wireRejectColor
                    else -> wireColor
                }
                drawPath(path, preview, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                drawCircle(preview, radius = 5.dp.toPx(), center = b)
            }
        }
    }
}

/**
 * 窗口坐标的矩形 → **世界坐标**（画布内容坐标，与卡片落点同一套）。
 *
 * 必须传入**测量当时**的 pan/scale，不能用调用时的当前值：
 * 平移只改 graphicsLayer、不重新布局，所以"测量的那一刻"与"绘制的此刻"可能不同。
 */
/**
 * 窗口坐标的点 → 世界坐标。命中判定用（指针是窗口坐标，针脚/卡片矩形是世界坐标）。
 */
private fun toWorldPoint(point: Offset, origin: Offset, pan: Offset, scale: Float): Offset {
    val s = if (scale > 0f) scale else 1f
    return Offset((point.x - origin.x - pan.x) / s, (point.y - origin.y - pan.y) / s)
}

/**
 * 某块布局**未被裁剪**的窗口矩形。
 *
 * ## 为什么不能用 boundsInWindow()
 *
 * `boundsInWindow()` 内部走的是 `findRootCoordinates().localBoundingBoxOf(this, clipBounds = true)`
 * —— **clipBounds 是 true**。也就是说，当这个节点超出任何祖先的裁剪区时，返回的矩形会被
 * **夹到裁剪边界上**，而不是它的真实位置。
 *
 * 画布根节点有 `.clip(RoundedCornerShape(12.dp))`，所以卡片一旦被拖出可视区：
 *  - 它的 boundsInWindow 就变成"贴着画布边缘的一条"；
 *  - 用它算出来的世界坐标自然也是错的 —— 线头被拽向边界，而不是跟着卡片。
 *
 * 这正是"卡片在视野内还好、一超出边界就偏"的根因。之前几轮一直在查 pan/scale 的换算，
 * 方向错了：换算公式是对的，**输入**被裁剪过了。
 *
 * ## 修法
 *
 * 官方 API 本来就有这个开关：`boundsInWindow(clipBounds = false)`。
 * 文档原话：「If true, any clipping that occurs between this layout and the window
 * will affect the returned bounds, and can even result in an empty rectangle if clipped
 * regions do not overlap.」—— 默认值就是 true。
 *
 * 用官方参数而不是自己拿 localToWindow 算两个角：后者在旋转/缩放时得到的不是外接矩形，
 * 而官方实现会正确处理。
 */
private fun LayoutCoordinates.unclippedBoundsInWindow(): Rect =
    boundsInWindow(clipBounds = false)

private fun toWorldRect(rect: Rect, origin: Offset, pan: Offset, scale: Float): Rect {
    val s = if (scale > 0f) scale else 1f
    return Rect(
        left = (rect.left - origin.x - pan.x) / s,
        top = (rect.top - origin.y - pan.y) / s,
        right = (rect.right - origin.x - pan.x) / s,
        bottom = (rect.bottom - origin.y - pan.y) / s,
    )
}

/**
 * 松手/拖拽位置压在**哪一颗数据入口针脚**上。命中返回节点 id 与针脚 id，否则 null。
 *
 * 只认**数据入口**针脚：索引里还登记着引脚种类名与出口针脚，不筛的话，
 * 压到自己那颗出口点、或者压在种类名那种粗索引上，都会被当成"连到下游某个参数"。
 *
 * 抽成独立函数是因为它有两个调用点 —— 拖拽中的高亮与松手时的落点。
 * 两处各写一遍必然漂移，而漂移的表现是「高亮说能接、松手接不上」，极难查。
 */
private fun hitPinAt(
    pointer: Offset,
    fromNodeId: String,
    definition: WorkflowDefinition,
    pinKeys: Collection<String>,
    rectOf: (String) -> Rect?,
): PinHit? {
    for (key in pinKeys) {
        val rect = rectOf(key) ?: continue
        if (!rect.contains(pointer)) continue
        val owner = key.substringBefore(':')
        if (owner == fromNodeId) continue
        val pinId = key.substringAfter(':')
        val ownerNode = definition.nodes.firstOrNull { it.id == owner } ?: continue
        if (pinId !in WorkflowSchema.dataInputKeysOf(ownerNode)) continue
        return PinHit(owner, pinId)
    }
    return null
}

/** 一次针脚命中的结果（画布内部用）。 */
private data class PinHit(val nodeId: String, val pinId: String)

/**
 * 数据边的 fromPin → 起点节点的**引脚 id**（画布内部索引键的后半段）。
 *
 * 落盘的 fromPin 有三种写法，都源自编辑页的 `dataSourceFor`：
 *  - `var:<名字>`  → extract 产出的是变量，出口针脚 id 就是 `var`；
 *  - `step:<id>`   → 本步整体输出，出口针脚 id 是 `result`（每个节点都有这条出口）；
 *  - 其余（裸针脚名）→ 原样用（兼容手写/旧文件）。
 *
 * 上一版是对 `:` 切一刀取后半段，于是 `step:n3_123` 被当成一个叫 `n3_123` 的针脚 ——
 * 永远找不到，线因此退回"卡片右上角"的估算法，看起来像连错了。
 */
private fun dataPinIdOf(
    definition: WorkflowDefinition,
    fromNodeId: String,
    fromPin: String,
): String = when {
    fromPin.startsWith("var:") -> "var"
    fromPin.startsWith("step:") -> "result"
    fromPin.contains(':') -> fromPin.substringAfter(':')
    fromPin.isNotBlank() -> fromPin
    // 什么都没写：按节点类型给最合适的出口（extract 给变量，其余给结果）。
    definition.node(fromNodeId)?.type == WorkflowNodeType.EXTRACT -> "var"
    else -> "result"
}

/**
 * 这张卡需要的系统能力 id。
 *
 * 用 [WorkflowActionCatalog] 而不是读 config：能力是**动作定义的一部分**，
 * 写进 config 就等于让用户能改自己的能力声明，那等于没有声明。
 */
private fun requiredCapsOf(node: WorkflowNode): List<String> {
    val spec = WorkflowActionCatalog.byId(node.config("bridge")) ?: return emptyList()
    val cap = spec.cap ?: return emptyList()
    return listOf(cap.id)
}

/**
 * 画布背景网格。**对齐参考实现的 drawBackground**（CardLayoutView.java:880）。
 *
 * 参考实现的三条关键做法，这里逐条照做：
 *  1. 画的是**方格线**，不是点阵；
 *  2. 网格 = 12dp（`CardLayoutView.GRID_DP_SIZE`），与吸附网格**同一个常量**；
 *  3. 每 10 格加粗一档，另有坐标轴的两种强调（垂直/水平主轴最粗）。
 *
 * 之前用 24dp 点阵，与吸附的 12dp 网格不是同一套 —— 视觉上看不出吸附落在哪，
 * 而参考实现的网格线本身就告诉用户"卡片会落在这些线的交点上"。
 *
 * 取模铺满的做法也照抄：`offset = start % gridSize`，平移多少都不会画穿屏幕边界，
 * 因此画布在观感上是无限的（参考实现同样没有平移范围限制）。
 */
private fun DrawScope.drawBlueprintGrid(color: Color, pan: Offset, scale: Float) {
    val safeScale = if (scale > 0f) scale else 1f
    // 网格步长跟着缩放走：与卡片、连线保持同一缩放。
    val step = WorkflowGeometry.GRID_DP.dp.toPx() * safeScale
    if (step <= 0f) return
    // 起点 = pan 对步长取模（参考实现：canvas.translate(offsetX, offsetY)）。
    var x0 = pan.x % step
    var y0 = pan.y % step
    val bigStep = step * 10f
    // 竖直网格线
    var x = x0
    while (x < size.width) {
        // 主轴（x0 那一根）在参考实现里用 5px 强调；每 10 格用 2，其余 0.5。
        val w = when {
            x == x0 -> 2.4f
            (x - x0) % bigStep == 0f -> 1.1f
            else -> 0.6f
        }
        drawLine(color = color, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = w)
        x += step
    }
    // 水平网格线
    var y = y0
    while (y < size.height) {
        val w = when {
            y == y0 -> 2.4f
            (y - y0) % bigStep == 0f -> 1.1f
            else -> 0.6f
        }
        drawLine(color = color, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = w)
        y += step
    }
}

/**
 * 三次贝塞尔连线：真分支 tertiary、假分支 error 虚线、默认流 outline 实线、
 * **数据边 primary 细虚线**。
 */
private fun DrawScope.drawBlueprintEdges(
    definition: WorkflowDefinition,
    positions: Map<String, Offset>,
    pinWorldRect: (String) -> Rect?,
    origin: Offset,
    pan: Offset,
    scale: Float,
    normal: Color,
    trueColor: Color,
    falseColor: Color,
    dataColor: Color,
    /**
     * 数据针脚类型 → 颜色。**在组合期算好**，绘制期只查表。
     *
     * 键是 [WorkflowPinType.id]（text/number/bool/choice/any）。
     * 不能在绘制期调 workflowPinColor —— 它读 MaterialTheme，是 @Composable。
     */
    pinColors: Map<String, Color> = emptyMap(),
    /** 当前选中的节点：与它相连的线加粗置顶（参考实现把选中卡的线单独再画一遍）。 */
    selectedNodeId: String? = null,
) {
    val density = this.density
    // 绘制顺序 = 层级。与选中卡相连的边排到最后画，于是它压在别的线上面 ——
    // 参考实现就是这么做的（先画非选中卡的线，再把选中卡的线用更粗的笔重画一遍）。
    // 不这样做的话，加粗的线会被后画的普通线盖掉一半，"高亮"就只剩下一段段断口。
    val orderedEdges = definition.edges.sortedBy { e ->
        if (selectedNodeId != null && (e.from == selectedNodeId || e.to == selectedNodeId)) 1 else 0
    }
    for (edge in orderedEdges) {
        val from = positions[edge.from] ?: continue
        val to = positions[edge.to] ?: continue
        val isData = edge.kind == WorkflowEdgeKind.DATA
        // 线头的针脚键：控制流用引脚种类名，数据边用引脚 id（见 [dataPinIdOf]）。
        // 两者共用 "<节点id>:<引脚键>" 这一套索引，卡片在 onPinBounds / onPinIdBounds 里都登记。
        val pinKey = if (isData) {
            dataPinIdOf(definition, edge.from, edge.fromPin)
        } else {
            when (edge.whenExpr) {
                "true" -> WorkflowPinKind.EXEC_TRUE.name
                "false" -> WorkflowPinKind.EXEC_FALSE.name
                else -> WorkflowPinKind.EXEC_OUT.name
            }
        }
        // 针脚报的是**窗口**矩形，而这一层画在已被 graphicsLayer 缩放+平移过的内容层里 ——
        // 所以要先经逆变换回到世界坐标，否则一旦缩放，线头就会离针脚越来越远。
        val safeScale = if (scale > 0f) scale else 1f
        // pinWorldRect 每次调用都现取窗口矩形（不裁剪）再用**当前** pan/scale 换算，
        // 所以拿到的一定是此刻的世界坐标。不能再逆变换一次 —— 那会把偏移算两遍。
        // 保留 worldX/worldY 两个名字只为可读性：这条绘制链路上的坐标一律是世界坐标。
        fun worldX(x: Float) = x
        fun worldY(y: Float) = y
        val pinned = pinWorldRect("${edge.from}:$pinKey")
        // 起点锚点：**按针脚方向分开取**，不是一律取某一条边。
        //
        // 参考实现的 `PinView.getSlotPosInLayout`：
        //   垂直针脚（控制流，顶进底出）：x 取水平居中，出口再加一个整高 → 底边中点；
        //   水平针脚（数据，左进右出）：y 取垂直居中，出口再加一个整宽 → 右边中点。
        //
        // 上一版对控制流取的是 `pinned.right`（右边中点）。控制流针脚在卡片里是**垂直**方向的，
        // 它的底尖在底边中点上 —— 取右边中点，线就从五边形右侧弹出去、贴着卡右缘走，
        // 与针脚尖错开整整半个针脚宽。用户说的"连线没对齐"就是这一处。
        val startVertical = !isData
        val startX = if (pinned != null) {
            worldX(if (startVertical) pinned.center.x else pinned.right)
        } else {
            (from.x * density) + (if (isData) 0f else CARD_W_DP * density)
        }
        val startY = if (pinned != null) {
            // 出口才落在底边；入口（EXEC_IN 用作源的情况）落在顶边。
            worldY(if (startVertical) pinned.bottom else pinned.center.y)
        } else {
            (from.y * density) + WorkflowNodeCardAnchorY * density
        }
        // 终点：数据边落在目标**参数那颗点**上，控制流边落在卡片的执行入口上。
        // 取不到真针脚才退回卡片锚点的估算法（旧文件 / 未知键仍画得出线）。
        //
        // 与起点同一套规则、方向相反：控制流入口在**顶边中点**，数据入口在**左边中点**。
        // 目标是接收方，所以控制流取 `top`、数据取 `left`（不是 center）——
        // 线要停在针脚的外沿，压到针脚身上会把它盖住。
        val toPinned = pinWorldRect("${edge.to}:${if (isData) edge.toPin else WorkflowPinKind.EXEC_IN.name}")
        val endX = if (toPinned != null) {
            worldX(if (isData) toPinned.left else toPinned.center.x)
        } else {
            to.x * density
        }
        val endY = if (toPinned != null) {
            worldY(if (isData) toPinned.center.y else toPinned.top)
        } else {
            (to.y * density) + WorkflowNodeCardAnchorY * density
        }
        // 折线（不是贝塞尔）：见 WorkflowLinkPath 的说明。
        //
        // `vertical` 必须跟着**针脚方向**走（参考实现是 calculateLinkPath(..., pin.isVertical())）：
        // 控制流针脚是垂直的（顶进底出），走"上下为主"的那套走法；
        // 数据针脚是水平的（右出左进），必须走"左右为主"的那套。
        //
        // 这里一直用默认值 true，于是数据线被当成垂直连接来排布：从右边中点出发后先往下拐，
        // 再横过去接左边中点 —— 画出来就是贴着一圈绕的大弯，还常与卡片重叠。
        // 用户截图里那条横穿卡片的数据线就是这个。
        val path = orthogonalLinkPath(
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            corridor = LINK_CORRIDOR_DP.dp.toPx(),
            vertical = !isData,
        )
        // ── 颜色：取**这条边插在哪个针脚上**，而不是取边的种类 ──
        //
        // 参考实现的 dispatchDraw 里，连线色就是源针脚自己的颜色；两端针脚同类时纯色，
        // 不同类才做渐变。这样"线的颜色"与"它插的那个针脚"是同一条信息 —— 一根黄线一眼就
        // 知道它属于那个黄色针脚。
        //
        // 上一版按"是不是数据边 / whenExpr"上色，于是所有控制流线都是同一个灰、所有数据线
        // 都是同一个 primary，已连的线看不出连到哪 —— 用户说的"已连接的线没有高亮"就是这个。
        // 颜色在**组合期**就算好了（见调用点的 pinColors），这里只查表。
        //
        // 为什么不能在这里读主题：drawBlueprintEdges 是 DrawScope 扩展、不是 @Composable，
        // 而 workflowPinColor 读 MaterialTheme.colorScheme（@Composable 属性）。
        // 在绘制阶段读主题既编译不过，也确实不对 —— 主题变了应当触发重组、重新算色，
        // 而不是等下一帧绘制时才悄悄变。
        val color = if (isData) {
            val toSpec = WorkflowSchema.pinsOf(definition, edge.to).firstOrNull { it.id == edge.toPin }
            pinColors[(toSpec?.type ?: WorkflowPinType.ANY).id] ?: dataColor
        } else {
            // 控制流没有"数据类型"，三类出口各自的语义色沿用画布原有的一套：
            // 成立=tertiary、不成立=error（它同时画虚线）、默认流=outline。
            // 这三色是**同一颗针脚**的颜色在画布上的延伸 —— 卡片上它们都是 outline，
            // 但画布必须区分真假，否则一条分支线看不出走的是哪一边。
            when (edge.whenExpr) {
                "true" -> trueColor
                "false" -> falseColor
                else -> normal
            }
        }
        // 选中卡片的连线加粗并置顶：参考实现把"选中卡片相关的线"用更粗的笔单独再画一遍。
        // 置顶靠第二趟绘制实现（见下方 selected 分支），这里只决定线宽。
        val touchesSelection = selectedNodeId != null &&
            (edge.from == selectedNodeId || edge.to == selectedNodeId)
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                // 与选中卡片相连的线加粗一档：它是「这条边属于我正看着的那张卡」的提示。
                width = if (touchesSelection) {
                    if (isData) 3.dp.toPx() else 4.dp.toPx()
                } else {
                    if (isData) 1.8.dp.toPx() else if (edge.whenExpr.isBlank()) 2.5.dp.toPx() else 2.dp.toPx()
                },
                cap = StrokeCap.Round,
                pathEffect = if (isData || edge.whenExpr == "false") {
                    PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
                } else {
                    null
                },
            ),
        )
    }
}