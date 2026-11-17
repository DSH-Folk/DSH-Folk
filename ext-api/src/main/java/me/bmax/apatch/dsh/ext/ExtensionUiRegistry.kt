package me.bmax.apatch.dsh.ext

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 一段槽位内容：给宿主一个 Modifier，画什么由扩展决定。 */
typealias SlotContent = @Composable (Modifier) -> Unit

/**
 * 宿主运行时状态的「扁平快照」。
 *
 * 为什么不直接给宿主自己的 DshState：那是 :app 的内部类型，暴露出去等于把整个 app 变成公开
 * API（以后谁都改不动）。外壳真正需要的只有这几项，做成稳定的数据类。
 */
data class RuntimeSummary(
    /** 运行时版本，例如 0.2.1-rc.2-ubuntunoble-r6。 */
    val version: String,
    /** 当前容器运行时 id，例如 proot / proroot / chroot。 */
    val mode: String,
    /** 服务是否在跑。 */
    val running: Boolean,
    /** 监听端口（未启动时为 0）。 */
    val port: Int,
)

/**
 * 交给「外壳扩展」的宿主导航句柄。
 *
 * 外壳接管了整屏之后，**必须**还能把用户送回宿主原有的页面 —— 否则替换外壳就等于把 App 关进
 * 一个空房间。所以宿主把这些动作包成接口交给外壳，而不是让外壳自己去摸 navController。
 */
interface ShellHost {
    /** 四个主页面 id，见 [ExtensionShellTabs]。 */
    fun navigate(tabId: String)

    /** 当前所在的主页面 id（外壳用来高亮）。 */
    fun currentTabId(): String

    /**
     * 当前所在主页面的**可观察**版本。
     *
     * 为什么光有 [currentTabId] 不够：用户按系统返回键、或被别的入口切页时，外壳收不到通知，
     * 侧栏高亮会停在上一次调 navigate 的那一页 —— 看起来像点错了。外壳应收集这个流。
     */
    fun currentTabStates(): StateFlow<String>

    /**
     * 切回宿主默认外壳（= 停用当前这个外壳扩展）。
     *
     * 必须有这个入口：外壳接管了整个导航，一旦它自己画坏了，用户就没有任何「回去」的路 ——
     * 只能去扩展页找到它并关掉，而那需要先能看见扩展页，也正是被接管的部分。
     */
    fun resetToDefaultShell()

    /** 跳到任意目的地 route（设置子页等），外壳一般用不到。 */
    fun navigateRoute(route: String)

    /** 打开容器里的 WebUI（dsh 本体）。 */
    fun openWebUi()

    /** 运行时状态快照，供外壳显示「当前版本/运行方式」。 */
    fun runtimeSummary(): RuntimeSummary

    /** 运行时可观察状态（外壳想实时显示时收集它）。 */
    fun runtimeStates(): StateFlow<RuntimeSummary>
}

/** 主页面 id 常量（与宿主底栏四页一一对应）。 */
object ExtensionShellTabs {
    const val HOME = "home"
    const val TERMINAL = "terminal"
    const val PLUGINS = "plugins"
    const val SETTINGS = "settings"

    /** 全部主页面，顺序即宿主底栏顺序。 */
    val ALL = listOf(HOME, TERMINAL, PLUGINS, SETTINGS)
}

/**
 * 外壳内容：拿到宿主句柄与「原有页面树」的渲染入口。
 *
 * 第二个参数是宿主把同一棵导航树交出来的方式 —— 外壳负责画自己的顶栏/抽屉/底栏，
 * 但页面本体仍然是宿主原来的那套（插件页、设置页…一个都不能少）。
 */
typealias ShellContent = @Composable (ShellHost, @Composable (Modifier) -> Unit) -> Unit

/**
 * 界面接管总线。
 *
 * 能力（从保守到激进）：
 * 0. **配置面板**：扩展给宿主一块**自己的**配置界面，入口在它自己的卡片上（[registerConfigPanel]）；
 * 1. **具名槽位**：宿主在固定位置留插槽，扩展往里插内容（INSERT_BEFORE / REPLACE / …）；
 * 2. **整屏重绘**：接管某个界面的整屏；
 * 3. **外壳接管**：接管 App 的导航外壳（顶栏/抽屉/底栏），页面树仍由宿主提供；
 * 4. **主题色板拦截**：拿到宿主的 ColorScheme 再返回改过的一份。
 *
 * 0 是后来补的，补的理由值得写下来：在此之前，扩展想给用户一个调参数的地方，只有两条路 ——
 * 往宿主固定槽位塞控件，或者整屏接管。前者是**共享**位置，多个扩展往同一处堆，越长越乱，
 * REPLACE 更是硬冲突；后者天然独占，两个扩展同时干就必然有一个静默失效。配置面板按
 * extensionId 独占一格，**结构上不存在争抢**，于是也不需要任何优先级仲裁。
 *
 * 熔断后一律返回空 / 原样 —— 界面必须回到宿主原生样子，否则用户连求救按钮都点不到。
 */
object ExtensionUiRegistry {

    enum class SlotOp { INSERT_BEFORE, INSERT_AFTER, REPLACE, REMOVE, WRAP }

    /** 一条已注册的槽位内容。 */
    data class SlotEntry(val extensionId: String, val op: SlotOp, val content: SlotContent)

    /** 一次整屏接管（**带 extensionId**：否则卸载时撤不掉，整屏会一直被它占着）。 */
    data class ScreenOverride(val extensionId: String, val content: SlotContent)

    /** 一次外壳接管。 */
    data class ShellOverride(val extensionId: String, val content: ShellContent)

    /**
     * 一条扩展自带的配置界面：宿主给标准容器，扩展只画内容。
     *
     * 为什么不复用 [SlotContent] 的槽位机制：槽位是**共享**位置，多个扩展往同一处塞就必然
     * 要仲裁（谁先谁后、REPLACE 谁赢）。面板按 extensionId 独占，一个扩展最多一个，
     * 「冲突」这个概念在这里不成立 —— 不需要仲裁的隔离才是真的解决了冲突。
     */
    data class ConfigPanel(
        val extensionId: String,
        /** 面板标题；空 = 宿主用扩展名（多数扩展不关心标题怎么写）。 */
        val title: String,
        val content: SlotContent,
    )

    /** 扩展**独占**了哪个界面面（见 [ownedSurfaces]）。 */
    enum class SurfaceKind { SHELL, SCREEN }

    /**
     * 一处独占的界面面。
     *
     * 只收「独占」的两类（外壳 / 整屏）：槽位与色板是叠加式的，不存在「谁占了」这回事。
     */
    data class SurfaceClaim(val kind: SurfaceKind, val id: String)

    private val slots = ConcurrentHashMap<String, CopyOnWriteArrayList<SlotEntry>>()
    private val overrides = ConcurrentHashMap<String, CopyOnWriteArrayList<ScreenOverride>>()
    private val shells = CopyOnWriteArrayList<ShellOverride>()
    private val palette = CopyOnWriteArrayList<Pair<String, (ColorScheme) -> ColorScheme>>()
    private val panels = ConcurrentHashMap<String, ConfigPanel>()

    /**
     * 宿主内置**整屏**（可被 overrideScreen 接管）。与槽位不同：槽位是往页面里插一段，
     * 整屏是**替换掉整个页面** —— 扩展自己负责那一屏的全部内容。
     */
    object Screen {
        /** dsh 的 WebUI 页（DshWebUiActivity）。 */
        const val WEBUI = "screen.webui"
    }

    /** 宿主内置槽位名（新增一个就是加一行常量，扩展侧用字符串引用）。 */
    object Slot {
        const val EXTENSIONS_PAGE_TOP = "extensions.page.top"
        const val HOME_CARD = "home.card"
        const val SETTINGS_FUNCTION = "settings.function"
        const val WEBUI_OVERLAY = "webui.overlay"
    }

    fun registerSlot(extensionId: String, slot: String, op: SlotOp, content: SlotContent) {
        if (ExtensionGate.isLatched()) return
        slots.getOrPut(slot) { CopyOnWriteArrayList() }.add(SlotEntry(extensionId, op, content))
    }

    /**
     * 接管整屏（screenId 由宿主定义）。
     *
     * ## 语义：后注册的赢（**破坏性变更**，改自 first-wins）
     *
     * 原来这里是「第一个注册的永久生效」，而 [registerShell] 一直是「最后一个生效」——
     * 同一套 API 两种语义，扩展作者无法预期，而且是**最难排查的那种失效**：先加载的那个
     * 悄悄赢着，后加载的代码明明跑了（onLoad 没报错、注册也没被拒）却什么都看不见。
     *
     * 现在统一成 last-wins，理由不是「和外壳一样好看」，而是它有一条可解释的规则：
     * 谁的 onLoad 更晚，谁就是用户最近启用的那个，界面归它。被顶掉的会留下一条日志，
     * 写明「谁顶掉了谁」—— 排查时能直接看到输家是谁，而不是只看到「被顶掉了」。
     *
     * 已有扩展若依赖「先注册者赢」，实际表现会变；统一语义比维持一个偶然行为重要。
     */
    fun overrideScreen(extensionId: String, screenId: String, content: SlotContent) {
        if (ExtensionGate.isLatched()) {
            ExtLog.log("[ext:" + extensionId + "] overrideScreen 被熔断拦截，未注册（" + screenId + "）")
            return
        }
        val list = overrides.getOrPut(screenId) { CopyOnWriteArrayList() }
        // 同一扩展重复注册：先撤掉自己那条，否则会叠加成一串（与 registerShell 同一纪律）
        list.removeIf { it.extensionId == extensionId }
        list.lastOrNull()?.let { loser ->
            ExtLog.info(
                "[" + extensionId + "] 接管整屏 " + screenId +
                    "，顶掉了 [" + loser.extensionId + "]",
            )
        }
        list.add(ScreenOverride(extensionId, content))
    }

    /**
     * 接管 App 的导航外壳。
     *
     * 多条注册时取**最后注册**的那条（后启用的扩展赢），只有一个外壳在生效 —— 两个外壳叠在
     * 一起既画不出来也没法解释。
     *
     * 同一扩展重复注册是**覆盖**而不是叠加：原来这里是裸 add，同一个扩展在 onLoad 里调两次
     * 就会在 shells 里留两条一模一样的记录，日志里的「当前共 N 个」跟着失真（N 说的是记录数，
     * 不是真正在争外壳的扩展数），卸载时倒是会被 unregisterAll 一起清掉 —— 于是这个 bug
     * 只在「排查时看日志」这一种场景下露出来，最难发现。
     */
    fun registerShell(extensionId: String, content: ShellContent) {
        if (ExtensionGate.isLatched()) {
            // 熔断时静默返回会让「扩展加载成功但界面没换」变得完全无法解释，所以留一行
            ExtLog.log("[ext:" + extensionId + "] registerShell 被熔断拦截，未注册")
            return
        }
        shells.removeIf { it.extensionId == extensionId }
        shells.lastOrNull()?.let { loser ->
            ExtLog.info("[" + extensionId + "] 接管外壳，顶掉了 [" + loser.extensionId + "]")
        }
        shells.add(ShellOverride(extensionId, content))
        ExtLog.info("[" + extensionId + "] 已注册外壳（当前共 " + shells.size + " 个）")
    }

    /**
     * 注册本扩展的配置面板（**独占**，一个扩展最多一块）。
     *
     * 这是扩展给用户调参数的标准入口：宿主负责卡片上的入口、标题栏、空态，以及**滚动**，
     * 扩展只提供内容本身。
     *
     * ## 内容不要自己再套一层滚动
     *
     * 宿主的外框已经给了高度上限并做了 verticalScroll。内容里再放 LazyColumn / verticalScroll
     * 就是同方向嵌套 —— 内层会拿到无限高度，直接抛
     * "Vertically scrollable component was measured with an infinity maximum height"，
     * 而那行报错出现在扩展自己的调用栈里，看起来像宿主崩了。配置项多就用普通 Column 铺开，
     * 滚动由宿主负责。
     *
     * 重复注册按 id 覆盖（重新加载一个扩展时不该留下旧面板）。
     */
    fun registerConfigPanel(extensionId: String, title: String = "", content: SlotContent) {
        if (ExtensionGate.isLatched()) {
            ExtLog.log("[ext:" + extensionId + "] registerConfigPanel 被熔断拦截，未注册")
            return
        }
        panels[extensionId] = ConfigPanel(extensionId, title, content)
        ExtLog.info("[" + extensionId + "] 已注册配置面板")
    }

    /** 取某个扩展的配置面板（没有则 null = 卡片上显示空态）。熔断返回 null。 */
    fun configPanel(extensionId: String): ConfigPanel? =
        if (ExtensionGate.isLatched()) null else panels[extensionId]

    /**
     * 这个扩展当前**独占**了哪些界面面。
     *
     * 存在的唯一理由是「让冲突可见」：外壳与整屏都是 last-wins，输的那一方完全没有运行时
     * 信号，用户只会看到「我装了两个，只有一个生效」。把这句摆进扩展自己的配置面板，
     * 用户和扩展作者都能一眼看到它此刻在占什么，而不必去翻 logcat。
     */
    fun ownedSurfaces(extensionId: String): List<SurfaceClaim> {
        if (ExtensionGate.isLatched()) return emptyList()
        val out = ArrayList<SurfaceClaim>()
        if (shells.lastOrNull()?.extensionId == extensionId) {
            out.add(SurfaceClaim(SurfaceKind.SHELL, ""))
        }
        for ((screenId, list) in overrides) {
            if (list.lastOrNull()?.extensionId == extensionId) {
                out.add(SurfaceClaim(SurfaceKind.SCREEN, screenId))
            }
        }
        return out
    }

    /** 拦截主题色板。 */
    fun interceptPalette(extensionId: String, transform: (ColorScheme) -> ColorScheme) {
        if (ExtensionGate.isLatched()) return
        palette.add(extensionId to transform)
    }

    /** 取某个槽位的全部内容（按注册顺序）。熔断返回空。 */
    fun slotEntries(slot: String): List<SlotEntry> =
        if (ExtensionGate.isLatched()) emptyList() else (slots[slot]?.toList() ?: emptyList())

    /**
     * 该界面是否被接管（返回**最后**注册的接管者，与 [overrideScreen] 的 last-wins 一致）。
     *
     * 判据必须与写入端用同一个方向：读写两端各取一头（原来写端 first-wins、这里 firstOrNull
     * 恰好一致，但那只是巧合）就会变成「注册了 A 却渲染了 B」这种没法复现的错乱。
     */
    fun screenOverride(screenId: String): ScreenOverride? =
        if (ExtensionGate.isLatched()) null else overrides[screenId]?.lastOrNull()

    /** 当前生效的外壳（没有则 null = 用宿主默认外壳）。 */
    fun shellOverride(): ShellOverride? =
        if (ExtensionGate.isLatched()) null else shells.lastOrNull()

    /** 依次过一遍色板拦截器。 */
    fun applyPalette(base: ColorScheme): ColorScheme {
        if (ExtensionGate.isLatched()) return base
        var out = base
        for ((id, transform) in palette) {
            out = runCatching { transform(out) }.getOrElse {
                ExtLog.log("[ext:" + id + "] 色板拦截抛异常（已跳过）")
                out
            }
        }
        return out
    }

    /** 撤掉某个扩展注册的全部界面内容（槽位 / 整屏 / 外壳 / 色板 / 配置面板）。 */
    fun unregisterAll(extensionId: String) {
        for (list in slots.values) list.removeIf { it.extensionId == extensionId }
        for (list in overrides.values) list.removeIf { it.extensionId == extensionId }
        shells.removeIf { it.extensionId == extensionId }
        palette.removeIf { it.first == extensionId }
        // 面板必须一起撤：它的 content 是扩展 dex 里的 lambda，扩展卸载之后这个 lambda
        // 还指着已卸载的类加载器 —— 留着就是一颗定时炸弹（用户再点开配置就 NoClassDefFoundError）。
        panels.remove(extensionId)
    }

    fun clearAll() {
        slots.clear(); overrides.clear(); shells.clear(); palette.clear(); panels.clear()
    }
}
