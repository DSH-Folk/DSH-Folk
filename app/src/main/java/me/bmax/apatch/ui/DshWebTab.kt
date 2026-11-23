package me.bmax.apatch.ui

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一个浏览器标签。
 *
 * ## 为什么一个标签就是一个 WebView 实例
 *
 * 标签的本质是「各自独立的浏览状态」：地址、前进后退栈、滚动位置、页面内的 JS
 * 上下文。这些全都在 WebView 实例里，**没有任何 API 能把它们抽出来存到别处再还回去**。
 * 所以多标签只能是多 WebView —— 共用一个实例再「切换时重载 URL」等于每次都开新页面，
 * 用户回到上一个标签时表单填了一半的内容、滚动到的位置全没了，那不是标签。
 *
 * 代价是内存：每个 WebView 都是独立的 Chromium 渲染进程上下文。所以有
 * [MAX_TABS] 上限，且关闭标签时**必须**调 [destroy]（只把引用置空不会释放渲染进程）。
 *
 * ## 为什么标题/URL 存在这里而不是现读 WebView
 *
 * WebView 的 `title` / `url` 不是可观察的：标签总览列表要显示它们，但 WebView
 * 变化时 Compose 不会重组。所以在 [WebViewClient] 的回调里主动写回这两个字段。
 */
class DshWebTab(
    val id: Long,
    /** 初始地址。新建标签时给 DSH 首页。 */
    val initialUrl: String,
) {
    /**
     * 这个标签的 WebView。
     *
     * 由界面的 `AndroidView.factory` 创建并赋进来；[release] 之后置回 null。
     * 为 null 表示「还没建」或「已销毁」—— 切换到这个标签时若为 null，
     * 界面会重新创建一个（相当于恢复被系统回收的标签）。
     */
    var webView: WebView? = null

    /** 当前页标题。由 WebViewClient.onReceivedTitle / onPageFinished 写回。 */
    var title by mutableStateOf("")

    /** 当前页地址。同上，在页面跳转时写回。 */
    var url by mutableStateOf(initialUrl)

    /** 是否正在加载（给标签总览显示一个细进度）。 */
    var loading by mutableStateOf(false)

    /**
     * 这个标签的 WebView 当前是否挂着宿主 JS 桥（BlobBridge / UserscriptBridge）。
     *
     * **必须按标签记**：桥只能在回环页挂着 —— 外站拿到 BlobBridge 就能往磁盘写文件。
     * 原来这是 Activity 级的单例布尔，多标签下会串台：标签 A 在回环页（记账 true），
     * 切到标签 B 的外站时那句「状态没变就跳过」直接 return，**外站上的桥没被摘掉**。
     */
    var bridgesAttached: Boolean = true

    /**
     * 这个标签的 WebView 是否已装过用户脚本的 document-start 注册。
     *
     * 同样按标签记：脚本注册是绑在**具体 WebView 实例**上的，
     * 用 Activity 级的布尔会让第二个标签被误判成「已装过」而永远不装。
     */
    var scriptsInstalled: Boolean = false

    /** 销毁这个标签的 WebView 并释放渲染进程。 */
    fun release() {
        val wv = webView ?: return
        webView = null
        runCatching {
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.removeAllViews()
            wv.destroy()
        }
    }

    companion object {
        /**
         * 标签数上限。
         *
         * 每个 WebView 都要占一份渲染进程内存（真机上一个 dsh 页面约 60–100 MB）。
         * 8 个已经能到 500 MB 以上，中低端机会被系统直接杀掉 —— 那时用户看到的是
         * 「切个标签应用没了」，比「不让再开」糟得多。
         */
        const val MAX_TABS = 8
    }
}

/**
 * 标签集合 + 当前活动标签。
 *
 * 用 `mutableStateListOf` 而不是普通 List：标签总览要随增删重组。
 */
class DshWebTabStore(initialUrl: String) {

    private var nextId = 1L

    val tabs = mutableStateListOf<DshWebTab>()

    /** 当前活动标签的 id。 */
    var activeId by mutableStateOf(0L)
        private set

    init {
        val first = DshWebTab(nextId++, initialUrl)
        tabs.add(first)
        activeId = first.id
    }

    /** 当前活动标签；集合非空时恒不为 null。 */
    val active: DshWebTab? get() = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

    /**
     * 新建一个标签并切过去。
     *
     * @return 新标签；已达 [DshWebTab.MAX_TABS] 时返回 null（调用方据此提示用户）。
     */
    fun newTab(url: String): DshWebTab? {
        if (tabs.size >= DshWebTab.MAX_TABS) return null
        val t = DshWebTab(nextId++, url)
        tabs.add(t)
        activeId = t.id
        return t
    }

    /** 切到指定标签。不存在时不动。 */
    fun select(id: Long) {
        if (tabs.any { it.id == id }) activeId = id
    }

    /**
     * 关闭一个标签。
     *
     * 关掉当前标签时自动切到**左边**那个（没有左边就右边）；关到只剩一个时
     * 不清空 —— 空集合会让整个浏览界面没有 WebView 可显示。最后一个标签的
     * 「关闭」语义是「回到首页」，由调用方处理。
     *
     * @return 关闭后是否还有标签。false 表示调用方应当退出浏览模式。
     */
    fun close(id: Long): Boolean {
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return tabs.isNotEmpty()
        val removed = tabs.removeAt(idx)
        removed.release()
        if (tabs.isEmpty()) return false
        if (activeId == id) {
            activeId = tabs[(idx - 1).coerceAtLeast(0)].id
        }
        return true
    }

    /**
     * 按 WebView 实例反查它属于哪个标签。
     *
     * **不要用 `active` 代替它**：WebViewClient 的回调可能在**非活动标签**上触发
     * （后台标签的页面自己跳转、定时刷新、重定向）。那时写 `active` 会把当前正在看的
     * 那个标签的状态改错 —— 桥的挂载记账尤其致命，见 [DshWebTab.bridgesAttached]。
     */
    fun ownerOf(view: android.webkit.WebView?): DshWebTab? {
        val v = view ?: return null
        return tabs.firstOrNull { it.webView === v }
    }

    /** 销毁全部标签（Activity 销毁时调用）。 */
    fun releaseAll() {
        tabs.forEach { it.release() }
        tabs.clear()
    }
}
