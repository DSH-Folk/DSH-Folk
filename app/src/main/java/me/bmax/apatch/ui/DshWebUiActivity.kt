package me.bmax.apatch.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.Rational
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import me.bmax.apatch.R
import me.bmax.apatch.dsh.A11yOwn
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.DshRuntime
import me.bmax.apatch.dsh.ext.ExtensionHookRegistry
import me.bmax.apatch.dsh.ext.ExtensionUiRegistry
import me.bmax.apatch.ui.component.ElevationRequestDialogHost
import me.bmax.apatch.ui.component.WorkflowDialogHosts
import me.bmax.apatch.ui.theme.APatchTheme
import me.bmax.apatch.dsh.DshWebCompat
import me.bmax.apatch.util.LauncherIconUtils
import me.bmax.apatch.util.ui.showToast
import me.bmax.apatch.res.R as CoreR
import me.bmax.apatch.dsh.userscripts.Userscripts
import me.bmax.apatch.dsh.userscripts.WebScripts
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.key

/**
 * WebUI 打开方式。
 *
 * 用 Activity 而不是 composedestinations 的页面：首页六套布局共用的
 * `DshHomeUiState.openWeb()` 是普通方法，拿不到 navigator；做成 Activity 后
 * 只要 startActivity，不用把 navigator 穿过六套布局。FolkPatch 原来的
 * WebUIActivity 也是这个形制。
 */
object DshWebUi {
    const val MODE_IN_APP = "in"
    const val MODE_BROWSER = "browser"
    const val MODE_ASK = "ask"

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
            .getString(DshEnv.KEY_WEBUI_MODE, MODE_IN_APP) ?: MODE_IN_APP

    fun setMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
            .edit().putString(DshEnv.KEY_WEBUI_MODE, mode).apply()
    }

    /** 在应用内打开。 */
    fun openInApp(ctx: Context, url: String) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, DshWebUiActivity::class.java)
                    .putExtra(DshWebUiActivity.EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * 交给系统浏览器；没有可用浏览器时提示。
     *
     * 返回是否真的打开了：调用方（WebUI 主界面）用它决定要不要撤掉「这次离开不要缩小窗」
     * 那个旗 —— 打不开还留着旗，下一次用户按 home 就不会自动进小窗了。
     */
    fun openExternal(ctx: Context, url: String): Boolean {
        val ok = runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (!ok) showToast(ctx, ctx.getString(CoreR.string.dsh_no_browser))
        return ok
    }
}

/** 悬浮球直径。 */
private val BALL_SIZE = 44.dp

/** 球体与屏幕边缘的内缩。 */
private val BALL_INSET = 8.dp

/**
 * 球面上那个软件图标的绘制尺寸。
 *
 * 比 [BALL_SIZE] 大：`ic_launcher_foreground` 那张前景位图四周有一圈透明留白 —— 实测画布
 * 432px，有内容的只有居中的 262px，约六成。按球直径直接画的话真正的图案会缩成一小块，
 * 所以放大到 0.606 × 48 ≈ 29dp 让图案撑满一些。
 *
 * 上限由圆形裁剪定：图案是个圆角方块（圆角半径 51/432），离画布中心最远的一点在
 * 0.38 × 尺寸处；取 48dp 时是 18.2dp，仍在 IconButton / Surface 那道圆形裁剪（半径 ≥ 20dp）
 * 之内，所以切掉的只是透明留白，不会啃掉图案。
 */
private val BALL_ICON_SIZE = 48.dp

/** 展开后每个环绕按钮的直径。 */
private val MENU_BUTTON_SIZE = 42.dp

/** 环绕按钮圆心到球心的距离。 */
private val MENU_RADIUS = 64.dp

/** 环绕展开的总张角（度）：180 = 贴着屏幕那一侧的一个半圆。 */
private const val MENU_SPREAD_DEG = 180f

/**
 * 环绕按钮往外顶出的高度：球心到最上/最下那颗按钮外沿的距离。
 *
 * 球的纵向可停区间要按它缩进，否则球贴到屏幕上/下边缘时，展开的按钮要么顶出屏幕，
 * 要么被夹到同一行叠在一起。水平方向不用管：球本来就贴边，按钮只朝屏幕里侧铺。
 */
private val MENU_REACH = MENU_RADIUS * sin(Math.toRadians(MENU_SPREAD_DEG / 2.0)).toFloat() +
    MENU_BUTTON_SIZE / 2 - BALL_SIZE / 2

/**
 * 首次在旧内核上打开 WebUI 时的说明框。
 *
 * 明确告诉用户「要往页面里注入一小段 JS」以及不注入的后果，两个按钮都会把选择固化下来，
 * 之后不再打扰。划掉不存任何选择，下次再问。
 */
@Composable
private fun DshCompatShimDialog(
    kernel: DshWebCompat.Kernel,
    onDismiss: () -> Unit,
    onDisable: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.dsh_webui_compat_title)) },
        text = {
            Text(
                text = stringResource(
                    CoreR.string.dsh_webui_compat_body,
                    kernel.display.ifEmpty { "?" },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreR.string.dsh_webui_compat_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDisable) {
                Text(stringResource(CoreR.string.dsh_webui_compat_disable))
            }
        },
    )
}

/**
 * 内置 WebUI 容器。
 *
 * 只加载本机 `http://127.0.0.1:<port>`：明文由 network_security_config 允许，
 * 无需额外权限。不开 allowFileAccess —— 这个 WebView 除了本地回环没有别的用途，
 * 放开文件访问只会给页面多一条读 app 私有目录的路。
 *
 * **没有顶栏**：dsh 的 web 界面自己就是一个完整应用，再压一条 64dp 的 TopAppBar
 * （叠加状态栏内缩后更高）纯粹是在挤内容。返回/刷新/外部打开改由一颗贴边的悬浮球
 * 提供，位置可拖、松手吸附到左或右壁并记住。
 *
 * 注意 dsh 自己的 web 界面**有登录页**，所以这里同样需要登录，这是 dsh 的行为。
 *
 * ## 为什么必须自己接文件选择与下载
 *
 * WebView 不是浏览器，它**默认什么都不做**：
 * - `<input type="file">` 被点击时，WebView 调 `WebChromeClient.onShowFileChooser`，
 *   基类返回 false，于是没有任何反应 —— 页面侧连 `change` 事件都收不到。这就是
 *   「插件提供的文件上传按钮点了没反应，浏览器里就好」的全部原因，跟插件无关。
 * - 下载同理：`<a download>` / `Content-Disposition: attachment` 触发的是
 *   `WebView.setDownloadListener`，不设就直接丢弃。dsh 自己的会话日志导出
 *   （dsh-session-log-export）用的正是 `anchor.download = …; anchor.click()`。
 * - `blob:` URL 更特殊：它连 DownloadListener 都不会走（那是浏览器进程内的对象，
 *   没有网络请求），所以额外注入一小段 JS 把 blob 读成 base64 交回原生。
 *
 * ## 旧 WebView 内核要补 JS API
 *
 * WebView 是可独立升级的组件，系统版本高**不代表**内核新：有真机报过 Android 15
 * 上装着 Chromium 110 的 WebView。dsh 前端用到 `AbortSignal.any`（Chrome 116）与
 * `Promise.withResolvers`（Chrome 119），在这种设备上打开工作区就是
 * `AbortSignal.any is not a function`。兼容垫片在文档开始前补齐这些 API，见
 * [WebScripts.BUILTINS] 与 `assets/webui-scripts/compat.js`。
 *
 * ## 系统栏是沉浸的（网页画到小白条与状态栏后面）
 *
 * WebView 铺满整窗，系统栏后面是**网页自己的背景**；页面本体由内边距脚本（
 * `assets/webui-scripts/inset.js`，注入时带上四个方向的原生像素值）加的 `#root`
 * 内边距让开这两片区域。targetSdk 35 起系统强制 edge-to-edge，Android 侧给
 * WebView 留内边距的老做法只会得到两条主题底色带（手势条上下各一条，正是用户报的
 * 「底部留白」）。键盘例外：那一段由 `windowInsetsPadding(imeAnimationTarget)` 一步让开，见 onCreate 里的注释。
 */
class DshWebUiActivity : AppCompatActivity() {

    /**
     * 标签集合。
     *
     * 多标签 = 多 WebView：标签的浏览状态（前进后退栈、滚动位置、表单内容、JS 上下文）
     * 全在 WebView 实例里，没有 API 能抽出来再还回去。详见 [DshWebTab] 的类注释。
     */
    private var tabStore: DshWebTabStore? = null

    /**
     * **当前活动标签**的 WebView。
     *
     * 保留这个名字是为了让原有的 27 处 `webView?.xxx()` 调用一行都不用改 ——
     * 它们本来就该作用于"用户此刻正在看的那个页面"。切换标签时这个值跟着变。
     */
    private var webView: WebView?
        get() = tabStore?.active?.webView
        set(value) {
            tabStore?.active?.webView = value
        }

    /**
     * 桥当前是否挂着（回环页挂着、外站摘掉）。初值 true：WebView 创建时就挂上了。
     *
     * **委托到当前标签**：桥是挂在具体 WebView 实例上的，多标签下用一个 Activity 级
     * 布尔会串台（详见 [DshWebTab.bridgesAttached] 的注释）。
     */
    private var bridgesAttached: Boolean
        get() = tabStore?.active?.bridgesAttached ?: true
        set(value) { tabStore?.active?.bridgesAttached = value }
    private var canGoBack = false

    /**
     * WebView 当前加载的是**哪个 token** 的地址。
     *
     * 用来判断运行时重启后要不要重载：见 setContent 里那个 LaunchedEffect。
     * 用「记下来的 token」而不是比较 URL 字符串 —— URL 会被 WebView 规范化，比字符串容易误判、反复重载。
     */
    private var loadedWebToken: String? = null

    /**
     * 这一批注入脚本是否已按 document-start 装上。
     *
     * 只有**一个**布尔量：内置（见 [WebScripts.BUILTINS]）与用户导入的脚本走的是同一条管道、
     * 同一个注入点，失败原因也只有一个（内核不支持 DOCUMENT_START_SCRIPT，或注册抛异常）——
     * 以前五段各记一个，是因为它们各有各的开关；开关现在在 [WebScripts] 的注册表里，
     * 这里只需要知道「注册这一步成没成」。
     */
    /** 委托到当前标签：脚本注册绑在具体 WebView 上，不能跨标签共用。 */
    private var scriptsInstalled: Boolean
        get() = tabStore?.active?.scriptsInstalled ?: false
        set(value) { tabStore?.active?.scriptsInstalled = value }

    /**
     * 最近一次算出的系统栏内边距（CSS 像素 = dp）。
     *
     * 存成字段而不是只在组合期用局部量：`onPageStarted` 的补注入发生在**别的时刻**
     * （每次导航、刷新），拿组合期捕获的旧值会让页面在转屏后按旧尺寸避让。
     */
    private var cssInsetTop = 0

    /**
     * 浏览器导航栏此刻是否**占着布局空间**。
     *
     * 与 Compose 里那个 `browserBarVisible` 是同一件事的镜像：这个类需要在
     * [syncInsetsFromLayout] 里读它，而那里不在组合作用域内、拿不到 Composable 状态。
     * 由界面在状态变化时写回。
     */
    @Volatile
    private var browserBarShown = false

    /** 上一次布局回调里 View 的 top，用来去重（同一次布局会被回调多次）。 */
    private var lastLayoutTop = Int.MIN_VALUE

    /** 系统状态栏高度（px）。由 Compose 侧写入，[syncInsetsFromLayout] 读取。 */
    @Volatile
    private var statusBarTopPx = 0

    /** 当前 density。同上，给 [syncInsetsFromLayout] 做 px→dp 换算。 */
    @Volatile
    private var statusBarDensity = 1f
    private var cssInsetRight = 0
    private var cssInsetBottom = 0
    private var cssInsetLeft = 0

    /** 待回填给 `<input type="file">` 的回调；同一时刻只可能有一个选择器。 */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooser: ActivityResultLauncher<Intent>

    /**
     * 网页要麦克风、而系统还没授予时，Chromium 那一次 [PermissionRequest] 挂在这里。
     *
     * 必须挂住：回调要等**系统授权框**的结果（见 `onCreate` 里注册的 `micPermission`），
     * Chromium 会一直等我们答 grant/deny。丢掉它就是默认拒绝 —— 页面里 `getUserMedia`
     * 恒定抛 `NotAllowedError`，而系统设置里明明是允许的（用户报的正是这个）。
     */
    private var pendingAudioRequest: PermissionRequest? = null
    private lateinit var micPermission: ActivityResultLauncher<String>

    /** 网页要的这批资源里，**现在**真正授权得了的那些（麦克风 / 摄像头）。 */
    private fun grantableMedia(req: PermissionRequest): Array<String> =
        req.resources.filter { res ->
            when (res) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                // DRM / MIDI 之类维持 WebView 的默认处理：我们不替用户点头
                else -> false
            }
        }.toTypedArray()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 显式声明 edge-to-edge，不依赖系统隐式强制。
        //
        // Android 15+ 对 targetSdk 35 的应用会强制 edge-to-edge，主题里不写也是这个效果 ——
        // 但"隐式强制"在退出画中画、多窗口切换这类窗口重建时机并不可靠：系统会按普通窗口
        // 重算一次 fitsSystemWindows，把内容往下推。自己声明一次，等于给系统一个明确状态，
        // 后面 [restoreEdgeToEdge] 的再次声明才是"恢复"而不是"第一次设置"。
        runCatching { WindowCompat.setDecorFitsSystemWindows(window, false) }

        val url = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }
            ?: DshRuntime.webUrl()

        // 标签集合在 onCreate 建好：之后所有 WebView 的创建/切换/销毁都以它为准。
        // 放在这里而不是 Compose 里 remember —— WebView 是 Activity 级资源，
        // 用 remember 会在配置变化时被重建，那些 WebView 就变成没人认领的孤儿。
        tabStore = DshWebTabStore(url)

        // 必须在 onCreate 里注册（Activity 还没 STARTED），不能等到点击时才注册
        fileChooser = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = fileChooserCallback
            fileChooserCallback = null
            // 取消也必须回调（传 null），否则 WebView 认为选择器还开着，
            // 那个 <input> 之后再点就永远没反应了
            cb?.onReceiveValue(parseChooserResult(result.resultCode, result.data))
        }

        // 网页要麦克风、系统还没授予时弹的那一次授权框。同样必须在 onCreate 里注册
        // （Activity 还没 STARTED），否则回调收不到、那次 PermissionRequest 会被永久挂住。
        //
        // 为什么必须答：Chromium 等我们调 grant/deny，而我们不答的默认就是**拒绝** ——
        // 页面里 `getUserMedia` 于是恒定抛 `NotAllowedError`，提示「麦克风权限未开启，
        // 请在浏览器和系统设置中允许访问」，而系统设置里明明是允许的。用户报的正是这个。
        micPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val req = pendingAudioRequest
            pendingAudioRequest = null
            if (req == null) return@registerForActivityResult
            // 授权框允许了、且这次请求本来就要麦克风，就把麦克风（含一并要到的摄像头）
            // 交给 Chromium；否则明确拒绝 —— 拒绝也要答，否则页面一直等。
            val resources = if (granted) grantableMedia(req) else emptyArray()
            if (resources.isNotEmpty()) {
                Log.i(TAG, "web media permission granted: " + resources.joinToString(","))
                req.grant(resources)
            } else {
                req.deny()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 页面内还能后退就先退页面，否则才退出 Activity
                if (canGoBack) {
                    webView?.goBack()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        // 首帧就要按主开关决定画不画那个按钮（onResume 还会再读一次，设置改完回来即生效）
        pipMainEnabled.value = DshEnv.webuiPipMain(this)

        setContent {
            // allowCustomBackground = false：WebUI 是别人的页面，
            // 背后垫一张自定义壁纸只会让内容看不清
            APatchTheme(allowCustomBackground = false) {
                // 整屏接管：扩展可以替换掉整个 WebUI 页。
                // 放在最外层判断 —— 接管方要为自己的整屏负责（包括它想渲染什么、怎么退出），
                // 宿主这边只求一件事：没有注册时行为与原来一字不差。
                //
                // 注意：接管方拿到的是空画布，宿主的 WebView 不会同时存在。想「在页面上浮一层」
                // 请用 WEBUI_OVERLAY 槽位（那条路径下页面照常存在）。
                // 必须在最外层做 if/else 而不是「渲染完再 return」：APatchTheme 的 content
                // 是普通 @Composable lambda（非 inline），return@APatchTheme 编译不过。
                val screenOverride = remember { ExtensionUiRegistry.screenOverride(ExtensionUiRegistry.Screen.WEBUI) }
                if (screenOverride != null) {
                    Box(Modifier.fillMaxSize()) {
                        screenOverride.content(Modifier.fillMaxSize())
                    }
                } else {
                var progress by remember { mutableIntStateOf(0) }

                // 提权申请弹窗：用户此刻正看着 WebUI，只挂在 MainActivity 上的话
                // 这份申请他永远看不到（60 秒后静默超时算拒绝）
                ElevationRequestDialogHost()
            // 工作流审批（黄=建/红=跑）与运行前的权限检查：两处都要挂 —— WebUI 是独立 Activity，
            // 不挂过去，用户在 WebUI 里让 agent 建工作流就等不到弹窗（桥那边会一直阻塞到超时）。
            WorkflowDialogHosts()

                // 旧内核上**自动**注入兼容垫片，然后只说明一次。
                //
                // 为什么不再是「先问」：缺 Iterator 这类全局时整个 WebUI 会渲染成
                // "Failed to load plugins"，用户连设置页都进不去，问了也答不上来
                // （1.9.2 真机实测）。所以先保证能用，再把「已启用兼容模式 / 可关闭」
                // 明确告诉用户 —— off 仍然是用户的决定权，落盘后永不注入。
                val kernel = remember { DshWebCompat.kernel(this@DshWebUiActivity) }
                var showCompatNotice by remember {
                    mutableStateOf(DshWebCompat.shouldNotice(this@DshWebUiActivity, kernel))
                }
                if (showCompatNotice) {
                    DshCompatShimDialog(
                        kernel = kernel,
                        onDismiss = {
                            DshWebCompat.markNoticed(this@DshWebUiActivity)
                            showCompatNotice = false
                        },
                        onDisable = {
                            DshWebCompat.setMode(this@DshWebUiActivity, DshWebCompat.MODE_OFF)
                            DshWebCompat.markNoticed(this@DshWebUiActivity)
                            showCompatNotice = false
                            // 关掉之后要重新加载一次，垫片才真的不在这份文档里
                            webView?.reload()
                        },
                    )
                }

                // 系统栏尺寸交给页面自己避让（见 assets/webui-scripts/inset.js）。
                //
                // 为什么不让 Android 侧给 WebView 加内边距：那样系统栏后面只能垫一层
                // 主题底色，网页看着像被裁掉了一截（手势条上下各一条色带）。改成 WebView
                // 铺满整窗后，状态栏与小白条后面就是网页自己的背景 —— 页面本体用
                // `#root` 的 padding 让开这两个区域，可交互内容一样不会被盖住。
                val density = LocalDensity.current
                // 左右的 inset 与书写方向有关（RTL 下 start/end 会翻），所以四个取值里
                // 横向那两个要连 layoutDirection 一起传
                val layoutDirection = LocalLayoutDirection.current
                val insetTopPx = WindowInsets.statusBars.getTop(density)
                val insetBottomPx = WindowInsets.navigationBars.getBottom(density)
                // 横屏时三键导航会在侧边、挖孔也在侧边，两边取更大的那个
                val insetLeftPx = maxOf(
                    WindowInsets.navigationBars.getLeft(density, layoutDirection),
                    WindowInsets.displayCutout.getLeft(density, layoutDirection),
                )
                val insetRightPx = maxOf(
                    WindowInsets.navigationBars.getRight(density, layoutDirection),
                    WindowInsets.displayCutout.getRight(density, layoutDirection),
                )
                // CSS 像素就是 dp，WebView 的视口按 dp 计
                fun toCss(px: Int): Int = (px / density.density).roundToInt()
                val cssLeft = toCss(insetLeftPx)
                val cssRight = toCss(insetRightPx)
                // 键盘弹起时 WebView 会被抬到键盘上方（键盘本身盖住了手势条），再让页面留一条
                // 就给键盘上方多垫一层空白。判据用 imeAnimationTarget（键盘的**目标**高度，动画一
                // 开始就到位）而非 ime（逐帧插值），与下面 windowInsetsPadding 的抬升保持同步。
                val cssBottom = if (WindowInsets.imeAnimationTarget.getBottom(density) > 0) 0 else toCss(insetBottomPx)
                SideEffect {
                    cssInsetRight = cssRight
                    cssInsetBottom = cssBottom
                    cssInsetLeft = cssLeft
                }
                // 顶部**不在这里算**：它要扣掉窗口被系统下推的量，而那个量只有
                // View.getLocationOnScreen 测得到（见 [syncInsetsFromLayout]）。
                // 这里只把系统状态栏高度交给那个函数，并触发一次同步。
                SideEffect {
                    statusBarTopPx = insetTopPx
                    statusBarDensity = density.density
                }
                // 尺寸变了（转屏、折叠、键盘、状态栏显隐）就同步给页面。
                // 顶部高度在 [syncInsetsFromLayout] 里按屏幕坐标重算，这里传的是
                // 它算好的结果（首次组合时 WebView 还没建，factory 会写进 document-start 脚本）。
                LaunchedEffect(insetTopPx, cssRight, cssBottom, cssLeft) {
                    syncInsetsFromLayout()
                }

                // 浏览器导航栏的显隐状态：由悬浮球**长按**切换（Via 式）。
                // 只有开关打开时才可能显示 —— 关着时外链根本进不来，这条栏没有意义。
                //
                // 声明在 Box 之外、Column 之前：顶栏和悬浮球都要读它。
                var browserBarVisible by remember { mutableStateOf(false) }
                // 页面信息弹窗（点地址栏打开）
                var showPageInfo by remember { mutableStateOf(false) }
                // 标签总览弹窗
                var showTabSwitcher by remember { mutableStateOf(false) }
                // 当前页标题。存在**标签**里而不是这里：切标签后标题要跟着换，
                // 用局部状态的话切回来标题就没了（WebView.title 不是可观察的）。
                // 直接读标签的 mutableStateOf，读取本身会建立订阅。
                val pageTitle: String = tabStore?.active?.title.orEmpty()

                // 把「顶栏是否占位」同步给 syncInsetsFromLayout，并触发一次重算：
                // 顶栏一出现，cssTop 要从「状态栏高度」变成 0，否则页面顶部会多一条空白。
                val barShown = DshEnv.browserMode(this@DshWebUiActivity) && browserBarVisible
                SideEffect {
                    if (browserBarShown != barShown) {
                        browserBarShown = barShown
                        syncInsetsFromLayout()
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    // 顶栏与 WebView 用 Column 上下排布，而不是叠在同一个 Box 里。
                    //
                    // 叠放的后果（真机反馈）：唤出导航栏时它盖在网页上，半透明底把内容糊住，
                    // 被盖住的那一条还收不到触摸 —— 用户既看不清也点不到。浏览器的正常行为
                    // 是**把页面挤下去**，让页面按新高度重排。
                    //
                    // 为什么 Column 在 Box **里面**而不是替换 Box：这个 Box 里还住着进度条、
                    // 悬浮球、画中画引导等一堆靠 BoxScope 定位（`.align(...)`）的东西，
                    // 换成 Column 会让它们全部失去对齐能力。
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        // 顶栏：只有浏览器模式且用户唤出时才占位。
                        if (DshEnv.browserMode(this@DshWebUiActivity)) {
                            WebUiBrowserBar(
                                visible = browserBarVisible,
                                // 读**标签**的可观察 url，不是 webView?.url。
                                // WebView.url 不是 Compose 状态：页内跳转或切标签时它变了
                                // 也不会触发重组，地址栏就会一直停在旧值。
                                currentUrl = tabStore?.active?.url ?: url,
                                canGoBack = webView?.canGoBack() == true,
                                canGoForward = webView?.canGoForward() == true,
                                onBack = { webView?.goBack() },
                                onForward = { webView?.goForward() },
                                onReload = { webView?.reload() },
                                onGoHome = {
                                    webView?.loadUrl(DshRuntime.webUrl())
                                    browserBarVisible = false
                                },
                                onExitBrowserMode = {
                                    DshEnv.setBrowserMode(this@DshWebUiActivity, false)
                                    browserBarVisible = false
                                    webView?.loadUrl(DshRuntime.webUrl())
                                },
                                onShowPageInfo = { showPageInfo = true },
                                // 新建标签：真的开一个新 WebView（独立的历史与页面状态）。
                                onNewTab = {
                                    val t = tabStore?.newTab(DshRuntime.webUrl())
                                    if (t == null) {
                                        showToast(
                                            this@DshWebUiActivity,
                                            getString(
                                                CoreR.string.dsh_browser_tab_limit,
                                                DshWebTab.MAX_TABS,
                                            ),
                                        )
                                    }
                                },
                                onShowTabs = { showTabSwitcher = true },
                                tabCount = tabStore?.tabs?.size ?: 1,
                            )
                        }
                        // 切标签时用 key 强制 AndroidView 重建。
                        //
                        // AndroidView 的 factory 只在**首次**组合时调用一次，之后一直复用
                        // 同一个 View。多标签必须让它换实例 —— 每个标签有自己的 WebView。
                        // 用 activeId 作 key：切换标签 → key 变 → 旧 AndroidView 从组合里移除、
                        // 新的一棵重建，factory 拿到（或创建）那个标签的 WebView。
                        //
                        // 被移除的那个 WebView 不会丢：它还在 tabStore 的标签对象里，
                        // 切回去时 factory 直接把它交还，页面状态（滚动、表单、历史）都还在。
                        val activeTabId = tabStore?.activeId ?: 0L
                        // 切到这个标签后立刻重算一次内边距。
                        //
                        // 为什么必需：cssTop 取决于「顶栏此刻是否占位」，而那个状态是全局的。
                        // 在标签 A 展开过顶栏、切到标签 B 时，B 的页面是按它**上次**的内边距
                        // 渲染的 —— 不重算就会看到顶部多一条空白或内容被状态栏压住。
                        // 旧标签那边由 factory 里的 addOnLayoutChangeListener 负责，但那只在
                        // 布局真的变化时触发，切标签不一定引起布局变化。
                        LaunchedEffect(activeTabId) {
                            syncInsetsFromLayout()
                        }
                        key(activeTabId) {
                        AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            // 只有键盘要让开：左右上下的系统栏由页面用内边距避让。
                            // 用 imeAnimationTarget 而非 imePadding：后者跟着输入法弹出动画**逐帧**
                            // 改 WebView 高度，WebView 117 每帧重排又慢又卡，输入框「慢半拍才跟上」。
                            // 目标高度让 WebView 只重排一次、一步抬到键盘上方（这是壳侧能做到的最优；
                            // 逐帧跟随需 WebView M139 的 visual-viewport IME 支持，本机内核给不了）。
                            .windowInsetsPadding(WindowInsets.imeAnimationTarget),
                        factory = { ctx ->
                            // 这个标签已经有 WebView 就直接交还（切回来的场景），
                            // 没有才新建。切走时 AndroidView 只是不再渲染它，
                            // 实例仍在标签对象里，所以历史与页面状态都保留。
                            //
                            // **交还前必须先解绑**：旧的 ViewFactoryHolder 可能还没完成
                            // detach，此时直接让新的 holder addView 会抛
                            // IllegalStateException: The specified child already has a parent。
                            // 这是切标签最典型的崩溃点，且只在特定时序下复现（快速连点、
                            // 切标签时页面还在加载），不先做这一步就是埋雷。
                            run {
                                val cached = tabStore?.active?.webView
                                if (cached != null) {
                                    (cached.parent as? ViewGroup)?.removeView(cached)
                                }
                                cached
                            } ?: run {
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // 本地回环页面用不到文件访问，关掉少一条攻击面
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false

                                // ── WebView 远程调试（CDP）──
                                //
                                // 打开后 WebView 在本地监听抽象 unix socket，Chrome DevTools 协议走它。
                                // 容器里的 dsh 通过 adb forward 或直连抽象 socket 就能读页面 / 执行 JS / 截屏。
                                //
                                // **默认关**：这是一条完整的浏览器控制通道，拿到它就能读写页面里的一切
                                // （含登录态与正在编辑的内容）。只在用户主动开时才开。
                                //
                                // 必须在这里（WebView 实例创建时）设：setWebContentsDebuggingEnabled 是**进程级**
                                // 静态开关，但对「设置之后创建」的 WebView 才生效，已经建好的那个不会补上。
                                if (DshEnv.webviewDebug(this@DshWebUiActivity)) {
                                    android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                                    // 记下 socket 名，供容器里的 dsh-browser 找到这个 WebView。
                                    // 名字规则由 Chromium 固定：webview_devtools_remote_<pid>，pid 是**本进程**的。
                                    // 它是抽象命名空间 socket（没有文件系统路径），容器里通过桥配置拿到名字后，
                                    // 用 adb forward 或直连都能访问。
                                    DshEnv.setWebviewDebugSocket(
                                        this@DshWebUiActivity,
                                        "webview_devtools_remote_" + android.os.Process.myPid(),
                                    )
                                }
                                // blob: 下载的桥。只在 loadUrl 的回环地址上注入
                                // （onPageStarted 里按 origin 校验），别的来源拿不到它
                                addJavascriptInterface(BlobBridge(), BLOB_BRIDGE)
                                // 用户脚本的 GM_notification 落到原生 Toast
                                addJavascriptInterface(UserscriptBridge(), USERSCRIPT_BRIDGE)
                                // 扩展拦截点：WebView 建好、宿主设置全部就位之后，给扩展一次调整机会。
                                //
                                // 为什么放在桥都挂完之后：扩展若想把某个桥换成自己的实现，需要先看到
                                // 宿主已经挂了什么（payload 里的 "view" 就是它）。放在最末尾也让
                                // 「宿主设置」与「扩展设置」的先后关系确定 —— 扩展永远能覆盖宿主。
                                //
                                // 注意：这里**不能**让扩展拿到 ctx 或 Activity 去做越界的事，
                                // 但 API 层给的就是 WebView 本身，边界由扩展自己负责（它已经在应用进程内）。
                                if (ExtensionHookRegistry.has(ExtensionHookRegistry.Point.WEBUI_CONFIGURE)) {
                                    val payload = mutableMapOf<String, Any?>("view" to this)
                                    ExtensionHookRegistry.runBefore(ExtensionHookRegistry.Point.WEBUI_CONFIGURE, payload)
                                }
                                webViewClient = object : WebViewClient() {
                                    /**
                                     * 只让回环页面留在这个 WebView 里，其余交给系统浏览器。
                                     *
                                     * 不只是体验问题：[BlobBridge] 是通过
                                     * `addJavascriptInterface` 挂上的，一旦 WebView 被导航到
                                     * 外部站点，那个站点就能直接调它往磁盘写文件。把外链踢出去
                                     * 是让这个桥永远只面向本机 dsh 的前提。
                                     */
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val target = request?.url ?: return false
                                        val scheme = target.scheme?.lowercase()
                                        if (scheme != "http" && scheme != "https") {
                                            // mailto: / intent: 之类交给系统，别在 WebView 里报错
                                            suppressAutoPip = true
                                            val opened = runCatching {
                                                startActivity(
                                                    Intent(Intent.ACTION_VIEW, target)
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                )
                                            }.isSuccess
                                            // 没打开成功就立刻撤旗：留着它会让下一次「离开应用」也不缩小窗
                                            if (!opened) suppressAutoPip = false
                                            return opened
                                        }
                                        // 回环页面本来就是我们的，留在 WebView 里
                                        if (isLoopback(target.toString())) {
                                            // 从外站返回 dsh：把桥挂回去（进外站时卸掉了，见 setBridgesFor）
                                            if (DshEnv.browserMode(this@DshWebUiActivity)) {
                                                setBridgesFor(view, loopback = true)
                                            }
                                            return false
                                        }
                                        // 浏览器模式：外链留在应用内。
                                        // **关键安全动作**：进外站前先把 JavascriptInterface 桥摘掉 ——
                                        // addJavascriptInterface 是挂在 WebView 实例上的、对所有页面可见，
                                        // 不摘的话任意第三方站点都能调它往磁盘写文件。
                                        // 用户脚本另有一条按 origin 分档的注入通道（见 installScripts），
                                        // 外站只注入用户脚本、不注入内置脚本（内置脚本绑的是 dsh 自己的 DOM）。
                                        if (DshEnv.browserMode(this@DshWebUiActivity)) {
                                            setBridgesFor(view, loopback = false)
                                            return false
                                        }
                                        suppressAutoPip = true
                                        if (!DshWebUi.openExternal(this@DshWebUiActivity, target.toString())) {
                                            // 同上：没有浏览器时不能把旗留着
                                            suppressAutoPip = false
                                        }
                                        return true
                                    }

                                    override fun doUpdateVisitedHistory(
                                        view: WebView?,
                                        u: String?,
                                        isReload: Boolean,
                                    ) {
                                        canGoBack = view?.canGoBack() == true
                                        super.doUpdateVisitedHistory(view, u, isReload)
                                    }

                                    override fun onPageFinished(view: WebView?, u: String?) {
                                        progress = 100
                                        // 标题写回**这个标签**：页面信息与标签总览都要显示它。
                                        // WebView.title 不是可观察的，只能在这里抓一次。
                                        // 按 view 找标签而不是写"当前标签"—— 页面加载完成时
                                        // 用户可能已经切走了，写到活动标签上会张冠李戴。
                                        val owner = tabStore?.tabs?.firstOrNull { it.webView === view }
                                        owner?.title = view?.title.orEmpty()
                                        owner?.url = view?.url.orEmpty()
                                        owner?.loading = false
                                        super.onPageFinished(view, u)
                                    }

                                    override fun onPageStarted(
                                        view: WebView?,
                                        u: String?,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        progress = 1
                                        // 按 view 反查标签：后台标签的页面也会走到这里，
                                        // 一律写 active 会把用户正在看的那个标签改错。
                                        val owner = tabStore?.ownerOf(view)

                                        // ── 桥的 origin 隔离（安全关键）──
                                        //
                                        // 光在 shouldOverrideUrlLoading 里管不够：它只在「页面主动发起
                                        // 一次导航」时被调用。以下三条路径**绕过它**，把桥带进外站：
                                        //   - goBack() / goForward()（用户按返回键退回外站）
                                        //   - 服务端 302 重定向
                                        //   - 页面内 location 赋值、meta refresh
                                        // 真机可复现：DSH → 外站（摘桥）→ 回 DSH（挂桥）→ 按返回键
                                        // → 外站页面上 window.DshFolkDownload 又回来了，那个站点
                                        // 就能往磁盘写文件。
                                        //
                                        // onPageStarted 对**每一条**主文档导航都会触发，是唯一能覆盖
                                        // 全部路径的时机。放在这里不是"再管一次"，而是真正的兜底。
                                        if (DshEnv.browserMode(this@DshWebUiActivity) && u != null) {
                                            setBridgesFor(view, isLoopback(u))
                                        }

                                        // document-start 装不上时的回落：这里注入虽然已经晚于
                                        // 文档开头，但仍早于绝大多数模块求值，能救回一部分场景。
                                        // 每段脚本自己的 document-start 语义就退化成 onPageStarted。
                                        //
                                        // 只判「注册那一步成没成」：该不该注入已经在
                                        // [WebScripts.injections] 里按各自的开关筛过了，
                                        // 这里再问一遍开关就会出现「关掉兼容模式 → 注册返回 false
                                        // → 回落里又把它注进去」这种自相矛盾的路径。
                                        //
                                        // 读 owner 而不是 active：脚本注册绑在具体 WebView 上。
                                        if (owner?.scriptsInstalled != true && isLoopback(u)) {
                                            injectScriptsNow(view, u)
                                        }
                                        // 标签的地址与加载态跟着页面走：标签总览要显示实时值。
                                        owner?.url = u.orEmpty()
                                        owner?.loading = true
                                        super.onPageStarted(view, u, favicon)
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        // 只报主文档失败：子资源失败（favicon 之类）不该打扰用户
                                        if (request?.isForMainFrame == true) {
                                            showToast(
                                                this@DshWebUiActivity,
                                                getString(CoreR.string.dsh_webui_load_failed),
                                            )
                                        }
                                        super.onReceivedError(view, request, error)
                                    }
                                }
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, p: Int) {
                                        progress = p
                                    }

                                    /**
                                     * 把页面里的 JS 报错带进 App 日志。
                                     *
                                     * 这条是补课：上游客户端 bundle 抛
                                     * "Failed to load plugins … Iterator is not defined"
                                     * 时，页面自己画了个错误页，而 logcat 与上报里**一个字都没有**
                                     * —— 排查时只能靠用户截图。现在页面报错会落到
                                     * [me.bmax.apatch.dsh.LogStore]，随 bugreport 一起带出来。
                                     */
                                    override fun onConsoleMessage(
                                        msg: android.webkit.ConsoleMessage?,
                                    ): Boolean {
                                        val m = msg ?: return false
                                        // 上传诊断（assets/webui-scripts/file-upload.js 打的固定前缀）
                                        // 必须在**任何级别**都落日志：那条信息本身就是「为什么失败」，
                                        // 而它不一定是 error 级别（有的浏览器把 console.error 降级）。
                                        val uploadDiag = m.message().startsWith("[DSH-Folk upload]")
                                        if (uploadDiag || m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                            val line =
                                                (if (uploadDiag) "upload: " else "page error: ") +
                                                    m.message() + " @" + m.sourceId() + ":" + m.lineNumber()
                                            Log.w(TAG, line)
                                            // 也落进 dsh 日志（随 bugreport 带出来）。logcat 只覆盖最近
                                            // 几分钟、还要看采集时机，而页面报错往往发生在启动那一刻：
                                            // 真机上「Failed to load plugins」那次就是既没进 logcat 也没进
                                            // 上报，只能靠用户截图。
                                            me.bmax.apatch.dsh.DshRuntime.appendLog("[page] " + line)
                                        }
                                        return false
                                    }

                                    /**
                                     * 网页要麦克风/摄像头时的授权回调。
                                     *
                                     * **不重写它，`getUserMedia` 就是恒定失败**：AOSP 的默认实现
                                     * 是 `request.deny()`，而对话页的语音输入正是在 `getUserMedia`
                                     * 抛 `NotAllowedError` 时显示「麦克风权限未开启，请在浏览器和
                                     * 系统设置中允许访问」—— 系统里允许了也没用，因为 WebView 这
                                     * 一层从没被点过头。
                                     *
                                     * 顺序：已经授予的直接 grant（快速路径）；网页要麦克风而系统
                                     * 还没给，就弹系统授权框并**挂住**这次请求，等结果回来再答
                                     * （Chromium 会一直等 grant/deny，见 [micPermission]）。
                                     */
                                    override fun onPermissionRequest(
                                        request: PermissionRequest?,
                                    ) {
                                        val req = request ?: return
                                        val granted = grantableMedia(req)
                                        if (granted.isNotEmpty()) {
                                            Log.i(
                                                TAG,
                                                "web media permission granted: " +
                                                    granted.joinToString(","),
                                            )
                                            req.grant(granted)
                                            return
                                        }
                                        val wantsAudio = req.resources.contains(
                                            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                                        )
                                        if (wantsAudio) {
                                            // 上一个还没答就放掉，否则那个页面会被永久卡住
                                            // （同 onShowFileChooser 的写法）
                                            pendingAudioRequest?.deny()
                                            pendingAudioRequest = req
                                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                            return
                                        }
                                        // 其余资源维持 WebView 的默认处理（拒绝）
                                        super.onPermissionRequest(req)
                                    }

                                    /**
                                     * `<input type="file">` 的落点。返回 false 会让页面
                                     * 彻底收不到文件 —— 这正是之前上传按钮没反应的原因。
                                     */
                                    override fun onShowFileChooser(
                                        view: WebView?,
                                        callback: ValueCallback<Array<Uri>>?,
                                        params: android.webkit.WebChromeClient.FileChooserParams?,
                                    ): Boolean {
                                        // 上一个选择器还没结束就先放掉它，否则那个 input 会被永久卡住
                                        fileChooserCallback?.onReceiveValue(null)
                                        fileChooserCallback = callback
                                        val intent = runCatching {
                                            params?.createIntent()
                                        }.getOrNull() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = "*/*"
                                        }
                                        return try {
                                            // 选择器要盖在最上面：这一次别自动缩成小窗
                                            suppressAutoPip = true
                                            fileChooser.launch(intent)
                                            true
                                        } catch (e: ActivityNotFoundException) {
                                            Log.w(TAG, "no file picker activity", e)
                                            // 选择器没起来就没有「离开」这回事：撤旗，否则卡到下次 onResume
                                            suppressAutoPip = false
                                            fileChooserCallback = null
                                            callback?.onReceiveValue(null)
                                            showToast(
                                                this@DshWebUiActivity,
                                                getString(CoreR.string.dsh_webui_no_file_picker),
                                            )
                                            false
                                        }
                                    }
                                }
                                // http(s) 下载（Content-Disposition / <a download> 指向真实 URL）
                                setDownloadListener { dl, userAgent, disposition, mime, _ ->
                                    startHttpDownload(dl, userAgent, disposition, mime)
                                }
                                // 必须在 loadUrl 之前装：addDocumentStartJavaScript 只对
                                // 「调用返回之后才开始加载」的 frame 生效。内置那几段与用户
                                // 导入的脚本走的是同一条管道（每段一次注册，互不牵连），
                                // 顺序与开关都在 [WebScripts] 里。
                                scriptsInstalled = installScripts(
                                    this,
                                    tabStore?.active?.initialUrl ?: url,
                                )
                                webView = this
                                // 补一次恢复：本页 WebView 是在 Compose factory 里创建的，而 onStart 早于
                                // 第一次组合 —— 若上一轮 onStop 暂停了全局定时器之后本页被系统回收重建，
                                // onStart 那次 resumeTimers() 会打在 null 上，新实例就停在「已暂停」状态。
                                // resumeWebView() 幂等，在新实例上调用也无害。
                                resumeWebView()
                                // 布局一变就按屏幕坐标重算顶部内边距。
                                //
                                // 这是防双倍顶栏的主力：系统改窗口布局（退出 PiP、多窗口、
                                // 转屏）都会走到这里，而那一刻 getLocationOnScreen 量到的
                                // 才是"窗口被推下去多少"。用 View 的监听而不是 Compose 的
                                // onGloballyPositioned —— 后者给的是相对窗口的坐标，窗口整体
                                // 下推时它仍是 0，扣减会完全失效（第一版就是这么错的）。
                                addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
                                    if (top != lastLayoutTop) {
                                        lastLayoutTop = top
                                        syncInsetsFromLayout()
                                    }
                                }
                                // 用这个标签自己的初始地址，不是 Activity 那个 url：
                                // 新建标签时两者相同（都指向 DSH 首页），但恢复已有标签时
                                // 必须用它当初的地址，否则每个标签都会回到首页。
                                loadUrl(tabStore?.active?.initialUrl ?: url)
                                loadedWebToken = DshRuntime.state.value.webToken
                            }
                            } // run（新建 WebView 的分支）
                        },
                    )
                    } // key(activeTabId)
                    } // Column（顶栏 + WebView）到此结束

                    // 扩展叠加层：同一 Box 内、WebView 之上。这是 WEBUI_OVERLAY 槽位的第一处真实渲染点。
                    ExtensionWebUiOverlay()

                    // 运行时重启（proroot/chroot 回退、手动重启服务、崩溃自愈）会**换一个 token**，
                    // 而 WebView 只在创建时 loadUrl 过一次 —— 之后页面上的接口调用仍带着旧 token，
                    // 用户看到的就是一条 {"ok":false,"error":"token 不匹配","reason":"bad_token"}
                    //（issue #12 真机反馈：「切换运行时之后打开网页提示 token 不匹配」）。
                    // 这里盯着 state 里的 token：变了就带**新地址**重载一次。
                    //
                    // 只取 token 这一项（map + distinctUntilChanged）而不是整份 state：启动期间
                    // progress/message 变一次就重组一次整个页面，会让启动更卡。
                    // 重载会回到首页 —— 比停在 bad_token 的报错页好，且只在**真的**换了 token 时发生。
                    val webToken by remember {
                        DshRuntime.state.map { it.webToken }.distinctUntilChanged()
                    }.collectAsState(initial = DshRuntime.state.value.webToken)
                    LaunchedEffect(webToken) {
                        if (webToken.isNullOrBlank()) return@LaunchedEffect
                        // 第一次组合时 WebView 还没建好 / 还没加载：那一份由 factory 自己记
                        val previous = loadedWebToken ?: return@LaunchedEffect
                        if (previous == webToken) return@LaunchedEffect
                        val wv = webView ?: return@LaunchedEffect
                        loadedWebToken = webToken
                        wv.loadUrl(DshRuntime.webUrl())
                    }

                    // 加载进度：窗口顶端一条细线，不占布局高度
                    if (progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter),
                        )
                    }

                    // 悬浮球在画中画里**也留着**：那是小窗里唯一的出口 —— 点画中画按钮 =
                    // 切回全屏（见 [enterPip]）。以前这里按 inPip 把它藏起来，用户在小窗里
                    // 就再也点不到这个按钮，只剩系统那几个手势可用（用户报的正是这个）。
                    WebUiFloatingBall(
                        // 长按切换导航栏。短按保持原来的环绕菜单不变。
                        // 长按**总要给反馈**：以前只在浏览器模式开启时才切换导航栏，
                        // 关着的时候长按就是静默无反应 —— 用户完全看不出「为什么按了没动静」
                        // （真机报的正是这个）。现在关着时提示去设置里打开。
                        onToggleBrowserBar = {
                            if (DshEnv.browserMode(this@DshWebUiActivity)) {
                                browserBarVisible = !browserBarVisible
                            } else {
                                showToast(
                                    this@DshWebUiActivity,
                                    getString(CoreR.string.dsh_webui_browser_mode_off_hint),
                                )
                            }
                        },
                        // 「退出」不再单给一个 ✕：下面这个返回键在页面退无可退时本来就会
                        // 退出 Activity（见 addCallback 那段），✕ 是同一个逻辑的第二个入口。
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        onReload = { webView?.reload() },
                        // 交给外部浏览器时**现取**当前地址，而不是用本页进来时那个 [url]：
                        // dsh 每次重启都会生成新 token（旧地址的 token 随之失效），而本页
                        // 可以一直开着 —— 用进来时那份就等于把一个过期 token 递给浏览器。
                        onOpenExternal = {
                            DshWebUi.openExternal(this@DshWebUiActivity, DshRuntime.webUrl())
                        },
                        // 主开关关掉时不画这个按钮（自动进入那条路已经在 DshEnv.webuiPipAuto 里挡掉）
                        showPip = pipMainEnabled.value,
                        // 进不去（设备不支持 / 应用级开关被关）就先给引导，不硬撞。
                        // 已经在画中画里时 enterPip 走的是「切回全屏」，不会走到这里弹引导。
                        onEnterPip = { if (!enterPip()) showPipGuide.value = true },
                    )

                    // 导航栏已移到上面的 Column 里（不再叠在 WebView 之上）。

                    // 页面信息：点地址栏打开。
                    //
                    // 为什么值得一个弹窗：地址栏只显示截断的 URL，用户看不出这一页
                    // 到底是不是本机的、有没有走加密。这三件事（完整地址、标题、
                    // 安全状态）是「我现在在看什么」的全部答案。
                    if (showPageInfo) {
                        val ctx = this@DshWebUiActivity
                        val fullUrl = webView?.url ?: url
                        val secure = fullUrl.startsWith("https://")
                        val loopback = fullUrl.startsWith("http://127.0.0.1") ||
                            fullUrl.startsWith("http://localhost") ||
                            fullUrl.startsWith("http://[::1]")
                        AlertDialog(
                            onDismissRequest = { showPageInfo = false },
                            title = { Text(stringResource(CoreR.string.dsh_browser_info_title)) },
                            text = {
                                Column {
                                    Text(
                                        stringResource(CoreR.string.dsh_browser_info_url),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    Text(
                                        fullUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    if (pageTitle.isNotBlank()) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            stringResource(CoreR.string.dsh_browser_info_title_label),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        Text(pageTitle, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        stringResource(CoreR.string.dsh_browser_info_security),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    Text(
                                        when {
                                            loopback -> stringResource(CoreR.string.dsh_browser_info_local)
                                            secure -> stringResource(CoreR.string.dsh_browser_info_secure)
                                            else -> stringResource(CoreR.string.dsh_browser_info_insecure)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("url", fullUrl))
                                    showToast(ctx, ctx.getString(CoreR.string.dsh_browser_info_copied))
                                }) {
                                    Text(stringResource(CoreR.string.dsh_browser_info_copy))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPageInfo = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            },
                        )
                    }

                    // 标签总览：真实的多标签列表。
                    //
                    // 每一项显示标题 + 地址，点它切过去；右边的 ✕ 关掉那一个。
                    // 当前活动的那一项用高亮底色标出来 —— 否则用户切完回到列表
                    // 分不清自己现在在哪一页。
                    if (showTabSwitcher) {
                        val store = tabStore
                        val list = store?.tabs ?: emptyList()
                        AlertDialog(
                            onDismissRequest = { showTabSwitcher = false },
                            title = {
                                Text(
                                    stringResource(CoreR.string.dsh_browser_tabs) +
                                        " (" + list.size + "/" + DshWebTab.MAX_TABS + ")",
                                )
                            },
                            text = {
                                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                                    if (list.isEmpty()) {
                                        Text(stringResource(CoreR.string.dsh_browser_tabs_empty))
                                    }
                                    for (tab in list) {
                                        val isActive = tab.id == store?.activeId
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isActive) MaterialTheme.colorScheme.secondaryContainer
                                                    else MaterialTheme.colorScheme.surface,
                                                )
                                                .clickable {
                                                    store?.select(tab.id)
                                                    showTabSwitcher = false
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    tab.title.ifBlank {
                                                        stringResource(CoreR.string.dsh_browser_tab_untitled)
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    tab.url,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    // 关掉最后一个标签 = 退出浏览模式并回首页，
                                                    // 而不是留一个没有标签的空壳界面。
                                                    val stillHas = store?.close(tab.id) ?: false
                                                    if (!stillHas) {
                                                        showTabSwitcher = false
                                                        DshEnv.setBrowserMode(
                                                            this@DshWebUiActivity,
                                                            false,
                                                        )
                                                        DshWebUi.openInApp(
                                                            this@DshWebUiActivity,
                                                            DshRuntime.webUrl(),
                                                        )
                                                        finish()
                                                    }
                                                },
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Close,
                                                    stringResource(CoreR.string.dsh_browser_tab_close),
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val t = store?.newTab(DshRuntime.webUrl())
                                    if (t != null) showTabSwitcher = false
                                    else {
                                        showToast(
                                            this@DshWebUiActivity,
                                            getString(
                                                CoreR.string.dsh_browser_tab_limit,
                                                DshWebTab.MAX_TABS,
                                            ),
                                        )
                                    }
                                }) {
                                    Text(stringResource(CoreR.string.dsh_browser_new_tab))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTabSwitcher = false }) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            },
                        )
                    }

                    if (showPipGuide.value) {
                        PipGuideDialog(
                            switchOff = pipSupported() && !pipAllowed(),
                            onDismiss = { showPipGuide.value = false },
                            onOpenSettings = {
                                showPipGuide.value = false
                                openAppDetails()
                            },
                        )
                    }
                }
                }
            }
        }
    }

    /** 是否正在画中画。由 [onPictureInPictureModeChanged] 维护（个别 ROM 不回调，见 [enterPip]）。 */
    private val inPip = mutableStateOf(false)

    /**
     * 画中画**主开关**的当前值。
     *
     * onResume 每次重读：用户在设置里关掉之后回到本页，悬浮菜单里的按钮立刻消失，
     * 不必重启 Activity。
     */
    private val pipMainEnabled = mutableStateOf(true)

    /** 「开不了画中画」的引导弹窗是否可见。 */
    private val showPipGuide = mutableStateOf(false)

    /**
     * 下一次 [onUserLeaveHint] 不要自动进小窗。
     *
     * 31 以下没有「自动进入画中画」那个开关，只能靠 onUserLeaveHint 补；而**我们自己**去调
     * 外部页面（文件选择器、浏览器）时它也可能来一次 —— 那时候缩成小窗是错的（选择器会开在
     * 一个小窗上）。所以这两处出手前先立个旗，回到前台（[onResume]）再撤掉。
     */
    private var suppressAutoPip = false

    /**
     * 画中画按钮的动作：**不在**小窗里就进小窗，**在**小窗里就切回全屏。返回 false = 真的进不去，
     * 调用方去弹引导。
     *
     * 两种「进不去」要分开说：设备没有这个能力（[pipSupported]）与系统把本应用的画中画关了
     * （[pipAllowed]，应用信息页里的那个开关）。引导文案据此二选一，所以这里也分开判。
     *
     * 已经是画中画时**绝不能**返回 false：那条路是「切回全屏」，把它说成「进不了画中画」
     * 就会弹一个文不对题的引导（用户报的「提示被占用」就是这里返回 false 造成的）。
     * [inPip] 由 [onPictureInPictureModeChanged] 维护，个别 ROM 不回调它，所以用系统状态
     * `isInPictureInPictureMode` 兜住。
     */
    private fun enterPip(): Boolean {
        // 以系统给的答复为准，不拿 AppOps 预判：个别 ROM 把它报成 MODE_IGNORED 却实际允许，
        // 预判会把本来能进的用户直接挡进引导里。[pipSupported] / [pipAllowed] 只用来决定
        // 引导怎么说（是不支持，还是本应用的开关被关了）。
        val inPipNow = isInPictureInPictureMode
        if (inPipNow || inPip.value) {
            inPip.value = false
            val exited = runCatching { false }.getOrElse {
                Log.w(TAG, "exitPictureInPictureMode 抛异常", it)
                false
            }
            if (!exited && inPipNow) {
                // 极少数 ROM 上 exit 不展开回全屏：显式把本 Activity 拉到前台。
                // REORDER_TO_FRONT 复用已有实例（不新建、不重建），先立旗免得这次「离开」
                // 又触发 onUserLeaveHint 把我们送回小窗；onResume 会把旗撤掉。
                suppressAutoPip = true
                runCatching {
                    startActivity(
                        Intent(this, DshWebUiActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            }
            return true
        }
        return runCatching { enterPictureInPictureMode(pipParams(autoEnter = true)) }.getOrDefault(false)
    }

    /**
     * 画中画参数。
     *
     * 31+ 带 [PictureInPictureParams.Builder.setAutoEnterEnabled]：设过一次之后，用户按 home /
     * 手势返回桌面时系统自己把小窗留在应用外（官方文档把这一项挂在「swipe up to home」那条
     * 手势上）—— 不再依赖用户先点一次悬浮菜单里的按钮。离开本应用去别的 App 那一半它管不到，
     * 由 [onUserLeaveHint] + [enterPipOnLeave] 负责；31 以下这个字段不存在，也是这条路兜底。
     * 12+ 的无缝缩放：小窗与大窗之间过渡不闪一下。
     */
    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }

    /**
     * [onUserLeaveHint] 那条路专用的小窗入口：**不带** auto-enter 请求。
     *
     * 平台规则（AOSP `ActivityTaskManagerService#enterPictureInPictureMode`，android16-release）：
     * 客户端在 Activity 已经 PAUSING 时调进来、而参数里 `autoEnterEnabled` 为 true，平台**直接
     * 拒绝**并 return false，原话是「If the app is using auto-enter, and it explicitly requests
     * entering PiP while pausing, return false immediately since auto-enter should take in place
     * instead.」；`onUserLeaveHint` 恰好在这个 PAUSING 窗口里执行，所以那里带 auto-enter 的调用
     * 必然失败 —— 等于自己把兜底废掉：系统自动进入没管的那些场合（切到别的 App、从通知进别的 App）
     * 就一条路都不剩了。
     *
     * 所以这里按 12 以前的「客户端发起」入口进（`autoEnter = false`，平台在 PAUSING 时接受这条，
     * 见同一函数里 legacy-entry 那段注释：*a client-request that was actually a server-request
     * (via pause(userLeaving=true))*）。滑回桌面那条路不受影响：那里继续由 [onResume] 写下的粘性
     * 参数交给系统自动进（设过一次就一直有效）。
     *
     * 30 及以下这个参数根本不参与构建（[pipParams] 只在 31+ 才写 setAutoEnterEnabled），
     * 所以传 false 与传 true 在那些版本上产出的参数完全相同，行为不变。
     */
    private fun enterPipOnLeave(): Boolean =
        runCatching { enterPictureInPictureMode(pipParams(autoEnter = false)) }.getOrDefault(false)

    private fun pipSupported(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * 应用级画中画开关（AppOps 的 picture_in_picture 项）。
     *
     * MODE_DEFAULT 是「没被单独关掉」，按允许算：只有显式 MODE_IGNORED 才算被关。
     * 29 起用 unsafeCheckOpNoThrow（不打权限日志），26-28 只能走已废弃的 checkOpNoThrow。
     */
    private fun pipAllowed(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return true
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
    }

    /** 引导里的「去设置」：应用信息页，用户在里面能找到「画中画」那一项。 */
    private fun openAppDetails() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
            )
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            restoreEdgeToEdge()
            // 立刻同步一次：让页面尽快回到正确状态，用户不会先看到双倍顶栏再跳回来。
            window.decorView.post { syncInsetsFromLayout() }
            // 再补一次（350ms）：PiP 放大动画通常 200–400ms，动画结束的瞬间系统会
            // 再刷一次窗口布局 —— 那一下会把我们刚设的 decorFitsSystemWindows 冲掉。
            // 只做一次 post（16ms）必然早于动画结束，这正是第一版没生效的原因。
            window.decorView.postDelayed({
                if (!isInPictureInPictureMode) {
                    restoreEdgeToEdge()
                    syncInsetsFromLayout()
                }
            }, PIP_RESTORE_DELAY_MS)
        }
    }

    /**
     * 按 WebView 的**屏幕坐标**重算顶部内边距并下发给页面。
     *
     * ## 为什么单独一个函数，而不是在 Compose 里算
     *
     * 触发时机有三个，且都不受 Compose 控制：
     *  1. 布局变化（WebView 的 OnLayoutChangeListener）；
     *  2. 退出画中画（窗口被系统重排）；
     *  3. PiP 放大动画结束后（约 350ms，系统可能在那时又刷一次布局）。
     *
     * 等 Compose 重组再算，可能已经晚了一帧，用户看到的是"先双倍、再跳回来"。
     * 这里命令式地量、命令式地下发，是唯一能跟上的做法。
     *
     * ## 为什么必须用屏幕坐标（getLocationOnScreen），不能用窗口坐标
     *
     * 第一版用的是 Compose 的 `positionInWindow().y`，**扣减完全没生效** —— 真机复现
     * 确认。原因是坐标系选错了：退出画中画后，系统是把**整个 Window**（连同内容帧）
     * 推到状态栏下方的，而 WebView 在**窗口内部**依然贴顶，所以
     * `positionInWindow().y` 仍然返回 0。减 0 等于没减。
     *
     * 只有 `getLocationOnScreen` 量得到「WebView 顶边离物理屏幕最上沿多远」——
     * 窗口被下推多少，它就是多少。扣掉它，CSS 就只补系统**没**避让的那部分，
     * 两者相加恰好等于状态栏高度。
     */
    private fun syncInsetsFromLayout() {
        val wv = webView ?: return
        // 还没量过状态栏：首次布局回调可能早于 Compose 的 SideEffect 写入这两个值。
        // 那时按 0 算会把 cssTop 也算成 0 —— 页面顶栏直接贴到状态栏后面。
        // 直接返回，等 Compose 那边写好再触发（它的 LaunchedEffect 会调一次）。
        if (statusBarDensity <= 0f || statusBarTopPx <= 0) return
        val loc = IntArray(2)
        runCatching { wv.getLocationOnScreen(loc) }.onFailure { return }
        val y = loc[1]
        // 原生已避让的量与状态栏高度取差，负数归零：两个值来自不同分发时机，
        // 中间态可能出现 y > statusBarTopPx，负数 padding 会让页面塌掉。
        val effectiveTopPx = (statusBarTopPx - y).coerceAtLeast(0)
        // 顶栏展开时页面不再需要自己避让状态栏：那一块已经被不透明的工具栏占住了。
        // 不归零的话，工具栏下方会多出一条与状态栏等高、什么都没有的空白。
        val cssTop = if (browserBarShown) 0 else (effectiveTopPx / statusBarDensity).roundToInt()
        cssInsetTop = cssTop
        // 诊断日志：真机再出异常时，这一行能直接区分「原生没下推」(y=0)、
        // 「下推了但没扣」(y>0 且 cssTop 没变)、「扣了但页面没收到」。
        Log.i(
            TAG,
            "inset sync: statusTop=$statusBarTopPx screenY=$y cssTop=$cssTop " +
                "inPip=" + isInPictureInPictureMode,
        )
        runCatching {
            wv.evaluateJavascript(
                insetUpdateScript(cssTop, cssInsetRight, cssInsetBottom, cssInsetLeft),
                null,
            )
        }
    }

    /**
     * 退出画中画后重新把窗口拉回 edge-to-edge。
     *
     * ## 为什么需要这一步
     *
     * 本页的"沉浸式"不是自己设的 —— 主题（Theme.AppCompat.DayNight.NoActionBar）在
     * Android 15+ 上由系统强制 edge-to-edge，我们没有（也不该）自己写死
     * setDecorFitsSystemWindows(false)。
     *
     * 但**退出 PiP 是一次窗口重建**：Activity 换了 Configuration，不少 ROM 会在这个
     * 时刻把 decorView 的 fitsSystemWindows 重新按"普通窗口"算一遍 —— 于是整个
     * Compose 内容被往下推了一个状态栏的高度。而 inset.js 那边**照样**按
     * WindowInsets.statusBars 给 #root 加 padding-top，结果就是原生推一次 + CSS 推一次
     * = 顶栏双倍留白（真机复现：右侧抽屉的半透明遮罩在状态栏下方被齐平切掉）。
     *
     * 这里显式再设一次并强制重算 insets，让原生侧回到贴顶状态。
     * [requestApplyInsets] 必须走 post：onPictureInPictureModeChanged 里窗口还没完成
     * 这次布局，立刻请求会被系统合并进上一次分发里，等于没发。
     */
    private fun restoreEdgeToEdge() {
        runCatching {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.decorView.post {
                ViewCompat.requestApplyInsets(window.decorView)
                // 重算 insets 之后窗口位置可能刚变，立刻把新的屏幕坐标同步给页面。
                syncInsetsFromLayout()
            }
        }.onFailure { Log.w(TAG, "恢复 edge-to-edge 失败", it) }
    }

    /**
     * 用户离开时的兜底：**所有版本**都在这里再进一次小窗。
     *
     * 31+ 虽然写了 `setAutoEnterEnabled`，但那是系统说了算，而且官方文档只把自动进入挂在
     * 「手势返回桌面（swipe up to home）」这一种离开上；用户报的「切到别的 App 不进小窗」
     * 正是它管不到的那一半 —— 那一半的官方入口就是这里（文档：*Google Maps switches to PiP
     * mode if the user presses the home or recents button ... catch this case by overriding
     * onUserLeaveHint()*）。注意这里必须走 [enterPipOnLeave]：平台在 PAUSING 期间会拒绝带
     * auto-enter 的客户端请求，带 auto-enter 进等于什么都没做。
     *
     * **静默**：这里弹引导会在用户正要走的时候糊一脸；进不去就算了，什么都不做，失败写日志。
     * [suppressAutoPip]（我们自己调外部页面时立的旗）在这里**一次性消费**，用完立刻清掉 ——
     * 那个旗卡在 true 上正好造成「怎么都不自动进」。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val allow = !suppressAutoPip
        suppressAutoPip = false
        if (!allow || isInPictureInPictureMode || !DshEnv.webuiPipAuto(this)) return
        // 31+ 上「自动进入」是个**粘性参数**：系统据此认定「离开时由系统自己进小窗」，于是在
        // Activity 正在 pausing 时，我们的显式请求会被**直接拒掉** —— AOSP
        // `ActivityTaskManagerService#enterPictureInPictureMode` 原话：
        //   "If the app is using auto-enter, and it explicitly requests entering PiP while
        //    pausing, return false immediately since auto-enter should take in place instead."
        // 而系统那次自动进入只覆盖「滑回桌面 / 划走」，切到别的 App 它管不到 —— 于是用户报的
        // 「滑回桌面能进、切到别的 App 不进」：两条路一条被拒、一条不管。
        //
        // 参照 PiliPlus（android/app/src/main/kotlin/com/example/piliplus/MainActivity.kt）：它
        // 压根不写 setAutoEnterEnabled，只在 onUserLeaveHint 里自己发起进小窗。这里保留自动
        // 进入（滑回桌面那条路更稳），但**在显式请求之前先把粘性参数关掉**：两次离开从此都走
        // 「客户端发起」，不再互相顶掉。onResume 会把粘性参数按开关写回去。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pipSupported()) {
            try {
                setPictureInPictureParams(pipParams(autoEnter = false))
            } catch (e: Exception) {
                Log.w(TAG, "onUserLeaveHint: 关闭 auto-enter 失败", e)
            }
        }
        if (!enterPipOnLeave()) Log.w(TAG, "onUserLeaveHint: enterPictureInPictureMode 返回 false")
    }

    /** 选择结果 → WebView 要的 Uri 数组。取消或无数据一律 null。 */
    private fun parseChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        data.clipData?.let { clip ->
            // 多选走 clipData
            val list = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            if (list.isNotEmpty()) return list.toTypedArray()
        }
        return data.data?.let { arrayOf(it) }
    }

    /**
     * 交给系统 DownloadManager 落到公共 Download/DSH-Folk。
     *
     * 用 DownloadManager 而不是自己拉流：它有通知栏进度、断点、失败重试，
     * 而且写公共目录不需要存储权限（自带 MediaStore 登记）。
     * 回环地址没有 Cookie 也无妨，dsh 的鉴权走的是同源会话；带上 Cookie 只是兜底。
     */
    private fun startHttpDownload(
        downloadUrl: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        // DownloadManager 只认 http/https；blob:/data: 由 JS 那条路处理
        if (!downloadUrl.startsWith("http://") && !downloadUrl.startsWith("https://")) {
            Log.i(TAG, "download scheme not handled here: ${downloadUrl.take(24)}")
            return
        }
        val name = runCatching {
            URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType)
        }.getOrNull() ?: "download"
        val ok = runCatching {
            val req = DownloadManager.Request(Uri.parse(downloadUrl))
                .setMimeType(mimeType)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "$PUBLIC_SUBDIR/$name",
                )
            if (!userAgent.isNullOrEmpty()) req.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(downloadUrl)?.takeIf { it.isNotEmpty() }
                ?.let { req.addRequestHeader("Cookie", it) }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            true
        }.getOrElse {
            Log.e(TAG, "enqueue download failed", it)
            false
        }
        showToast(
            this,
            if (ok) getString(CoreR.string.dsh_webui_downloading, name)
            else getString(CoreR.string.dsh_webui_download_failed),
        )
    }

    /**
     * blob:/data: 下载的原生落点：JS 把内容读成 base64 递过来，这里写文件。
     *
     * 只从回环页面注入（blob 下载那段脚本由注入管道按回环 origin 规则装，见
     * [WebScripts.BUILTINS]）。
     * 即便如此也不信任入参：文件名只取 basename 并过滤路径分隔符，写入目录写死。
     */
    /**
     * 用户脚本 `GM_notification` 的原生落点：一个 Toast。
     *
     * 与 [BlobBridge] 同一套信任前提 —— 只在回环页面上挂（外部链接会被踢去系统浏览器），
     * 所以拿到调用的只可能是我们自己的页面。JS 线程调用，转回 UI 线程；长度截断，
     * 免得一个脚本刷屏。调用方可以什么都不传（脚本常写 GM_notification("done")）。
     */
    private inner class UserscriptBridge {
        @JavascriptInterface
        fun notify(title: String?, text: String?) {
            val body = listOfNotNull(title?.trim()?.takeIf { it.isNotEmpty() }, text?.trim()?.takeIf { it.isNotEmpty() })
                .joinToString(": ")
                .take(200)
                .ifEmpty { return }
            runOnUiThread { showToast(this@DshWebUiActivity, body) }
        }
    }

    private inner class BlobBridge {
        @JavascriptInterface
        fun save(base64: String, fileName: String) {
            val safe = fileName.substringAfterLast('/').substringAfterLast('\\')
                .filter { it.isLetterOrDigit() || it in "._- ()[]" }
                .take(120)
                .ifEmpty { "download" }
            val ok = runCatching {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    PUBLIC_SUBDIR,
                )
                // 公共 Download 写不进去（分区存储、无「所有文件」权限）时退到应用外部目录，
                // 那里始终可写，用户仍能通过「打开目录」拿到文件
                val target = if (dir.isDirectory || dir.mkdirs()) {
                    File(dir, safe)
                } else {
                    val fb = File(
                        getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir,
                        PUBLIC_SUBDIR,
                    )
                    fb.mkdirs()
                    File(fb, safe)
                }
                target.writeBytes(bytes)
                // 让文件在系统「下载」/文件管理器里可见（公共目录才需要）
                runCatching {
                    android.media.MediaScannerConnection.scanFile(
                        this@DshWebUiActivity, arrayOf(target.absolutePath), null, null,
                    )
                }
                target.absolutePath
            }.getOrElse {
                Log.e(TAG, "blob save failed", it)
                null
            }
            runOnUiThread {
                showToast(
                    this@DshWebUiActivity,
                    if (ok != null) getString(CoreR.string.dsh_webui_downloaded, safe)
                    else getString(CoreR.string.dsh_webui_download_failed),
                )
            }
        }
    }

    /** 注入的 JS 只处理 WebView 天生不管的 blob:/data:，http(s) 仍走 DownloadListener。 */
    private fun isLoopback(u: String?): Boolean {
        val host = runCatching { Uri.parse(u ?: return false).host }.getOrNull() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    /**
     * 在**文档开始前**把所有该注入的脚本装上，返回「注册这一步成没成」。
     *
     * 该注入哪几段由 [WebScripts.injections] 决定：内置（compat / inset / composer /
     * a11y-labels / blob-download，各自的开关在注册表里）+ 用户导入的脚本（逐条开关
     * + @match）。这里只做两件原生的事：
     *
     * - 用 [loopbackOriginRules] 把范围钉死在回环地址（别的站点不该被我们动）；
     * - **一段一次** [WebViewCompat.addDocumentStartJavaScript]：WebView 分别编译，
     *   于是一段语法错只毁它自己（拼成一大段的话，一处语法错会让整段静默不执行）。
     *
     * 必须在 loadUrl 之前调用：addDocumentStartJavaScript 只对「调用返回之后才开始加载」
     * 的 frame 生效。
     */
    private fun installScripts(view: WebView, url: String): Boolean {
        // 按 origin 分档（浏览器模式下才可能走到非回环分支）：
        //  - 回环（dsh 自己）→ 内置脚本 + 用户脚本，origin 规则钉在回环；
        //  - 外部站点 → **只注入用户脚本**。内置脚本（compat / inset / composer / a11y-labels /
        //    blob-download）每一项都绑在 dsh 自己的 DOM 结构与后端约定上，跑在别人的页面上
        //    只会报错、甚至改坏人家页面；它们本来也不该有「在任意站点生效」的能力。
        val browserMode = DshEnv.browserMode(this)
        val loopback = isLoopback(url)
        val scripts = if (browserMode && !loopback) {
            // Userscripts.injections 本身只按每条的 @match/@include 过滤，不限定回环 ——
            // 所以外部站点直接复用它即可，不需要另造一个「任意 origin」变体。
            Userscripts.injections(this, url)
        } else {
            WebScripts.injections(this, url, currentInsets())
        }
        if (scripts.isEmpty()) return true // 没什么要注入的：不用在 onPageStarted 里反复补
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        if (!supported) {
            Log.i(TAG, "document-start script unsupported, scripts fall back to onPageStarted")
            return false
        }
        // 外站要的是「这一页」的规则，不是回环那三条 —— 否则脚本在外部网页上静默不注入。
        val rules = if (browserMode && !loopback) {
            originRuleFor(url)
        } else {
            loopbackOriginRules(url)
        }
        return runCatching {
            scripts.forEach { WebViewCompat.addDocumentStartJavaScript(view, it, rules) }
            Log.i(TAG, "scripts injected at document-start: " + scripts.size)
            true
        }.getOrElse {
            Log.w(TAG, "addDocumentStartJavaScript failed for scripts", it)
            false
        }
    }

    /** document-start 装不上时的回落：逐段 evaluate（时机退化成 onPageStarted）。 */
    private fun injectScriptsNow(view: WebView?, url: String?) {
        // 显式判空：智能转换只对「null 检查后的不可变参数」生效，不依赖 stdlib 的契约注解
        if (view == null || url == null || url.isBlank()) return
        WebScripts.injections(this, url, currentInsets()).forEach {
            view.evaluateJavascript(it, null)
        }
    }

    /** 当前系统栏内边距（CSS 像素）。组合期算好存在字段里，注入时现取。 */
    private fun currentInsets() = WebScripts.Insets(
        top = cssInsetTop,
        right = cssInsetRight,
        bottom = cssInsetBottom,
        left = cssInsetLeft,
    )

    override fun onResume() {
        super.onResume()
        // 「别看本应用」：all 档时把整页（含 WebView 的虚拟子树）从无障碍树里隐掉。
        // 每页重进都重设一次，用户在设置里改了档位不必重启 App。
        A11yOwn.applyToWindow(window)
        // 画中画「自动进入」：31+ 靠这个参数生效 —— 设一次之后，用户按 home / 划走时系统自己
        // 缩成小窗（应用外也看得见）。每页重进都重设一次，用户改了设置也不必重启。
        //
        // 失败不再静默吞掉：这个参数写不进去 = 按 home 什么都不会发生，而日志里一个字都没有。
        // 主/副两个开关都由 DshEnv.webuiPipAuto 合成（主开关关掉一律 false）。
        pipMainEnabled.value = DshEnv.webuiPipMain(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && pipSupported()) {
            // 关掉时必须也调一次（autoEnter=false）—— 这个参数是有粘性的，不覆盖就还是上次那个值。
            try {
                setPictureInPictureParams(pipParams(autoEnter = DshEnv.webuiPipAuto(this)))
            } catch (e: Exception) {
                Log.w(TAG, "setPictureInPictureParams(autoEnter) 失败", e)
            }
        }
        // 回到前台：上一轮那两次"主动离开"的豁免用完就撤
        suppressAutoPip = false
    }

    /**
     * 离开前台就降载：WebView 不是普通 View，进了后台照样跑 rAF、CSS 动画与页面自己的轮询定时器。
     * DSH 的 WebUI 是一整个聊天前端（虚拟列表 + 流式渲染），后台空转时 CPU 与 WebView 渲染进程常驻不降，
     * 这是「多会话一起跑就发热耗电」里属于本应回收的那一部分。
     *
     * 守卫是「画中画 **且** 屏幕亮着」，而不是只看画中画：熄屏时 isInPictureInPictureMode 仍然是
     * true（PiP 窗口只是没被关掉），只按它判就成了「屏幕黑着、画面谁也看不见」时定时器照跑 ——
     * 正好是这一版要省掉的那部分耗电。
     *
     * 关于 pauseTimers() 的全局语义：它作用于**进程内所有 WebView**，而本应用不止一个 WebView ——
     * 除了这里这个，还有脚本市场那条应用内浏览页（DshWebViewScreen，由 ScriptMarketScreen 导航
     * 进入）。同时可见时它们都处于 started、不会走到 onStop，所以这个判断在现实中成立；但若将来
     * 出现「一个可见、另一个被 stop」的组合（多窗口/画中画叠用），这里必须改成引用计数 —— 现在的
     * 写法会连另一个页面一起停。
     */
    override fun onStop() {
        if (!watchingInPip()) {
            runCatching {
                // **逐个**标签都暂停，不能只暂停活动那个。
                //
                // 多标签下每个 WebView 都是独立的渲染实例，各自跑自己的 rAF 与定时器。
                // 只暂停活动标签，后台标签的页面照样在刷 —— 开着三个标签切到后台，
                // 就是三份 CPU 与网络轮询一起空转。
                //
                // pauseTimers() 是进程级开关，调一次即可；onPause() 必须逐个调。
                tabStore?.tabs?.forEach { it.webView?.onPause() }
                webView?.pauseTimers()
            }.onFailure { Log.w(TAG, "暂停 WebView 定时器失败（后台可能继续空转）", it) }
        }
        super.onStop()
    }

    /**
     * 与 onStop 严格配对，且**无条件**恢复：resumeTimers() 是全局的、重复调用无害，
     * 但漏调会让页面回来以后所有定时器都不再触发（消息不刷新、按钮无响应）。
     */
    override fun onStart() {
        super.onStart()
        resumeWebView()
    }

    /**
     * 恢复前台。**无条件**调用，理由见 onStart。
     *
     * 失败不再静默：吞掉异常的话，「回来以后页面不刷新」在现场将完全无从查起。
     */
    private fun resumeWebView() {
        runCatching {
            webView?.resumeTimers()
            webView?.onResume()
        }.onFailure { Log.w(TAG, "恢复 WebView 定时器失败（页面可能不再刷新）", it) }
    }

    /** 画中画里页面还在眼前（停掉等于冻结画面）；但熄屏时它并不在眼前。 */
    private fun watchingInPip(): Boolean =
        isInPictureInPictureMode &&
            (getSystemService(PowerManager::class.java)?.isInteractive ?: true)

    override fun onDestroy() {
        // 必须逐个销毁：只把引用置空不会释放渲染进程，标签开得多时会连着 Activity 一起泄漏。
        runCatching { tabStore?.releaseAll() }
        tabStore = null
        // 页面走了但选择器回调还挂着时也要放掉，否则 WebView 内部一直等
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        // 麦克风那次请求同理：还没答就答「拒绝」，别把一个 WebView 对象留给下一次加载
        pendingAudioRequest?.deny()
        pendingAudioRequest = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "dsh_webui_url"
        private const val TAG = "DshWebUi"

        /**
         * 退出画中画后补刷 edge-to-edge 的延迟（ms）。
         *
         * PiP 放大回全屏的动画通常 200–400ms。系统在**动画结束那一刻**会再刷一次
         * 窗口布局，把 decorFitsSystemWindows 按普通窗口重算 —— 只做一次
         * `post { }`（约 16ms 后）必然早于它，等于白做。350ms 落在常见动画时长之后。
         */
        private const val PIP_RESTORE_DELAY_MS = 350L

        /** 下载落地的公共子目录（用户找得到）。 */
        private const val PUBLIC_SUBDIR = "DSH-Folk"

        /** JS 侧看到的桥名。 */
        private const val BLOB_BRIDGE = "DshFolkDownload"

        /** 用户脚本的 GM_notification 桥名（注入体的 JS 里按这个名字找）。 */
        private const val USERSCRIPT_BRIDGE = "DshFolkNotify"

        /**
         * document-start 脚本允许的 origin 规则。
         *
         * 格式 `SCHEME "://" HOSTNAME_PATTERN [":" PORT]`，**端口不写就默认 80/443**，
         * 所以三个回环写法都要带上真实端口，否则规则匹配不到、脚本静默不注入。
         * IPv6 字面量要方括号。
         */
        internal fun loopbackOriginRules(url: String): Set<String> {
            val parsed = runCatching { Uri.parse(url) }.getOrNull()
            val port = parsed?.port?.takeIf { it in 1..65535 } ?: DshRuntime.port()
            return setOf(
                "http://127.0.0.1:$port",
                "http://localhost:$port",
                "http://[::1]:$port",
            )
        }



        /** 尺寸变化时通知页面；脚本还没装上时是空操作（那时由 onPageStarted 补注入）。 */
        internal fun insetUpdateScript(top: Int, right: Int, bottom: Int, left: Int): String =
            "window.__dshFolkInsets&&window.__dshFolkInsets($top,$right,$bottom,$left)"



    }
    /**
     * 按当前页面的 origin 挂上或摘掉 JavascriptInterface 桥。
     *
     * ## 为什么必须动态管理
     * `addJavascriptInterface` 是**挂在 WebView 实例上**的，一旦挂上，之后加载的所有页面
     * 都能通过 `window.<名字>` 调到它 —— 它没有 origin 隔离。浏览器模式让 WebView 有机会
     * 加载任意第三方站点，所以在进入非回环页面之前必须把它们摘掉，否则那个站点就能直接
     * 调 `DshFolkDownload` 往磁盘写文件。
     *
     * 摘掉用 `removeJavascriptInterface`（API 11+），名字与挂上时一致。
     *
     * ## 为什么回到 dsh 时要挂回
     * 进外站时摘掉是必须的，但摘掉之后如果不挂回，用户从外站导航回 dsh 首页时下载桥就没了 ——
     * 「进过一次外站，dsh 的 blob 下载从此失效」是很难查的 bug。所以两个方向都要处理。
     */
    private fun setBridgesFor(view: WebView?, loopback: Boolean) {
        val v = view ?: return
        // 状态记在**这个 view 所属的标签**上，不是「当前活动标签」。
        //
        // 后台标签的页面自己会跳转（重定向、定时刷新、页面内 location 赋值），那些
        // 回调里 view 不是活动标签。写 active 会把用户正在看的那个标签的记账改错 ——
        // 于是它下次进外站时「状态没变」被跳过，桥留在外站页面上。
        val owner = tabStore?.ownerOf(v) ?: return
        // 同向重复调用直接跳过（shouldOverrideUrlLoading 会被频繁触发）。
        if (owner.bridgesAttached == loopback) return
        runCatching {
            if (loopback) {
                v.addJavascriptInterface(BlobBridge(), BLOB_BRIDGE)
                v.addJavascriptInterface(UserscriptBridge(), USERSCRIPT_BRIDGE)
            } else {
                v.removeJavascriptInterface(BLOB_BRIDGE)
                v.removeJavascriptInterface(USERSCRIPT_BRIDGE)
            }
            owner.bridgesAttached = loopback
            Log.i(TAG, "bridges " + (if (loopback) "attached" else "removed") + " for origin switch")
        }.onFailure {
            // 摘桥失败比挂桥失败严重得多：留着一个可被第三方站点调用的写文件接口。
            // 所以失败时按「已摘掉」记账并打警告 —— 下次进回环会重新挂上。
            Log.w(TAG, "bridge toggle failed", it)
            owner.bridgesAttached = false
        }
    }


    /**
     * 外部站点的 document-start 注入规则：**这一页自己的 origin**。
     *
     * 与 [loopbackOriginRules] 的区别：后者把范围钉死在本机 dsh（内置脚本只该在那里跑）；
     * 这里要的是具体某个站点的 scheme://host[:port]。
     *
     * 端口规则与 WebView 的 origin 规则一致：不写端口就默认 80/443，所以
     * 只有当 URL 显式带端口时才拼上，否则会把 http://example.com:8080 这类页面挡在外面。
     */
    private fun originRuleFor(url: String): Set<String> {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return emptySet()
        val scheme = parsed.scheme?.lowercase() ?: return emptySet()
        val host = parsed.host ?: return emptySet()
        val port = parsed.port.takeIf { it in 1..65535 }
        // IPv6 字面量要方括号（WebView origin 规则的格式要求）
        val hostPart = if (host.contains(':')) "[$host]" else host
        return setOf(if (port != null) "$scheme://$hostPart:$port" else "$scheme://$hostPart")
    }


}

/**
 * 贴边的半透明悬浮球：球面就是应用自己的软件图标，点开后返回 / 刷新 / 画中画 /
 * 外部打开 / 关闭这几颗圆钮**绕着球心**铺成半圈（不再竖排成一列）。
 *
 * 位置持久化成「哪一侧 + 纵向比例」而不是绝对像素：换了屏幕方向或分屏尺寸后，
 * 绝对坐标会把球留在屏幕外，比例不会。
 *
 * 圆弧朝屏幕里侧扇开（右贴边朝左、左贴边朝右），球的可停区间也跟着上下各缩进
 * `MENU_REACH`，这样贴到边缘再展开也不会把按钮顶出屏幕。
 *
 * [showPip] 为 false（画中画主开关被关掉）时**不画**画中画那一个按钮 —— 关掉开关的人
 * 不该还看到一个点了没用的入口。
 */
@Composable
private fun WebUiFloatingBall(
    onBack: () -> Unit,
    onReload: () -> Unit,
    onOpenExternal: () -> Unit,
    onEnterPip: () -> Unit,
    showPip: Boolean = true,
    /** 长按球：显示 / 隐藏浏览器导航栏（浏览器模式下的 Via 式交互）。 */
    onToggleBrowserBar: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
    }
    val density = LocalDensity.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()

    var onRight by remember {
        mutableStateOf(prefs.getString(DshEnv.KEY_WEBUI_BALL_SIDE, "right") != "left")
    }
    // 纵向位置按可用高度的比例存；0.5 = 竖直居中
    var yRatio by remember {
        mutableFloatStateOf(prefs.getFloat(DshEnv.KEY_WEBUI_BALL_Y, 0.45f).coerceIn(0f, 1f))
    }
    var expanded by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    // 拖动期间用未吸附的实时 x（dp），松手后回到贴边值
    var dragX by remember { mutableStateOf<Dp?>(null) }

    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxW = maxWidth
            val maxH = maxHeight
            val top = safe.calculateTopPadding()
            val bottom = safe.calculateBottomPadding()
            // 球心可落的纵向区间：不压状态栏、不压手势条，并且给展开的环绕按钮留出上下半径
            // （MENU_REACH），这样球贴到边缘时展开的按钮也不会被顶出屏幕。
            val yMin = top + BALL_INSET + MENU_REACH
            val yMax = (maxH - bottom - BALL_INSET - BALL_SIZE - MENU_REACH).coerceAtLeast(yMin)
            val restX = if (onRight) maxW - BALL_SIZE - BALL_INSET else BALL_INSET

            val x by animateDpAsState(
                targetValue = dragX ?: restX,
                animationSpec = spring(),
                label = "ballX",
            )
            val y = yMin + (yMax - yMin) * yRatio

            // 展开动画：progress 0 = 按钮全收在球心，1 = 各自铺到圆弧上的落点。
            // 拖动中目标回到 0（先收起来再拖），松手再展开；球自身的位置由上面那个
            // animateDpAsState 负责，两者互不干扰。
            //
            // 用「不过冲」的弹簧：落点已经被 slot() 贴到安全区边缘，一旦过冲就会顶出屏幕，
            // 所以这里宁可稳一点，也不要那点多出来的回弹。
            val menuProgress by animateFloatAsState(
                targetValue = if (expanded && !dragging) 1f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "ballMenu",
            )

            // 展开后按钮不再竖排成一列，而是绕着球心铺成一段圆弧：贴右边缘就朝左边扇开，贴
            // 左边缘就朝右边扇开，一律朝屏幕里侧，不会顶到屏幕外。
            val cx = x + BALL_SIZE / 2
            val cy = y + BALL_SIZE / 2
            val menuCount = if (showPip) 4 else 3
            // 顶端那颗永远排在第一个：右侧时从左上方往左下方扫，左侧时从右上方往右下方扫。
            val startDeg = if (onRight) 180f + MENU_SPREAD_DEG / 2f else -MENU_SPREAD_DEG / 2f
            val endDeg = if (onRight) 180f - MENU_SPREAD_DEG / 2f else MENU_SPREAD_DEG / 2f
            val minX = BALL_INSET
            val maxX = (maxW - BALL_INSET - MENU_BUTTON_SIZE).coerceAtLeast(minX)
            val minY = top + BALL_INSET
            val maxY = (maxH - bottom - BALL_INSET - MENU_BUTTON_SIZE).coerceAtLeast(minY)

            fun slot(index: Int): Pair<Dp, Dp> {
                val f = if (menuCount <= 1) 0.5f else index.toFloat() / (menuCount - 1)
                val rad = Math.toRadians((startDeg + (endDeg - startDeg) * f).toDouble())
                val bx = cx + MENU_RADIUS * cos(rad).toFloat() - MENU_BUTTON_SIZE / 2
                val by = cy + MENU_RADIUS * sin(rad).toFloat() - MENU_BUTTON_SIZE / 2
                return bx.coerceIn(minX, maxX) to by.coerceIn(minY, maxY)
            }

            // 起点：按钮正好盖在球心上；终点：slot() 算出来的圆弧落点。
            val fromX = cx - MENU_BUTTON_SIZE / 2
            val fromY = cy - MENU_BUTTON_SIZE / 2
            val backSlot = slot(0)
            val reloadSlot = slot(1)
            val pipSlot = slot(2)
            val externalSlot = if (showPip) slot(3) else slot(2)

            BallMenuButton(
                progress = menuProgress,
                fromX = fromX,
                fromY = fromY,
                toX = backSlot.first,
                toY = backSlot.second,
                onClick = { expanded = false; onBack() },
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(CoreR.string.dsh_webui_back),
                )
            }
            BallMenuButton(
                progress = menuProgress,
                fromX = fromX,
                fromY = fromY,
                toX = reloadSlot.first,
                toY = reloadSlot.second,
                onClick = { expanded = false; onReload() },
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = stringResource(CoreR.string.dsh_webui_reload),
                )
            }
            // 主开关关掉时这一条不出现：不是灰掉，是根本不画（点了没用的入口
            // 只会让人以为功能坏了）。其余按钮照旧。
            if (showPip) {
                BallMenuButton(
                    progress = menuProgress,
                    fromX = fromX,
                    fromY = fromY,
                    toX = pipSlot.first,
                    toY = pipSlot.second,
                    onClick = { expanded = false; onEnterPip() },
                ) {
                    Icon(
                        Icons.Outlined.PictureInPictureAlt,
                        contentDescription = stringResource(CoreR.string.dsh_pip_button),
                    )
                }
            }
            BallMenuButton(
                progress = menuProgress,
                fromX = fromX,
                fromY = fromY,
                toX = externalSlot.first,
                toY = externalSlot.second,
                onClick = { expanded = false; onOpenExternal() },
            ) {
                Icon(
                    Icons.Outlined.OpenInBrowser,
                    contentDescription = stringResource(CoreR.string.dsh_webui_open_external),
                )
            }

            Surface(
                shape = CircleShape,
                // 球面就是软件图标本身：不再垫一块 secondaryContainer 圆底，否则图标自带的
                // 浅色圆角底会叠在色块上，既不是「软件图标」也不半透明。
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .offset(x = x, y = y)
                    .size(BALL_SIZE)
                    // 手势统一在**一个** pointerInput 里处理。
                    //
                    // 为什么不能分成两个 pointerInput：Compose 的指针事件沿 modifier 链逐个派发，
                    // 后面的 detectDragGestures 一旦进入 awaitPointerEventScope 就把事件拿走了，
                    // 前面的 detectTapGestures 再也等不到 up —— 表现就是「短按与长按**双双失效**」
                    // （真机报的正是这个）。
                    //
                    // 现在把拖动放在前面（离 Surface 更近）：手指没位移时 detectDragGestures 的
                    // onDragStart 不会触发，事件不被消费，后面的 tap 才拿得到完整的按下-抬起。
                    .pointerInput(maxW, maxH, yMin, yMax) {
                        detectDragGestures(
                            onDragStart = {
                                dragging = true
                                dragX = restX
                            },
                            onDragEnd = {
                                dragging = false
                                // 松手按左右中线吸附，并把结果记下来
                                val centre = (dragX ?: restX) + BALL_SIZE / 2
                                onRight = centre > maxW / 2
                                dragX = null
                                prefs.edit()
                                    .putString(
                                        DshEnv.KEY_WEBUI_BALL_SIDE,
                                        if (onRight) "right" else "left",
                                    )
                                    .putFloat(DshEnv.KEY_WEBUI_BALL_Y, yRatio)
                                    .apply()
                            },
                            onDragCancel = {
                                dragging = false
                                dragX = null
                            },
                        ) { _, delta: Offset ->
                            with(density) {
                                dragX = ((dragX ?: restX) + delta.x.toDp())
                                    .coerceIn(0.dp, (maxW - BALL_SIZE).coerceAtLeast(0.dp))
                                val span = (yMax - yMin).coerceAtLeast(1.dp)
                                yRatio = (yRatio + (delta.y.toDp() / span)).coerceIn(0f, 1f)
                            }
                        }
                    }
                    // 短按 / 长按放在拖动之后：拖动没开始时它不消费事件，tap 才能拿到完整手势。
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { onToggleBrowserBar() },
                            onTap = { expanded = !expanded },
                        )
                    },
            ) {
                // 用 Box 而不是 IconButton：IconButton 自己带一颗 clickable，会把点击吃掉，
                // 上层的 detectTapGestures 就收不到 —— 短按/长按都会失效。
                // 这里只需要「画一个图标」，点击语义全部由上层的 pointerInput 负责。
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(LauncherIconUtils.currentIconForeground()),
                        contentDescription = stringResource(CoreR.string.dsh_webui_ball),
                        // requiredSize 而不是 size：IconButton/Surface 会把最小约束往下传，
                        // size 会被夹回球径，画出来就偏小。位图四周那圈透明留白会被
                        // IconButton 的圆形裁剪吃掉，只留下中间本来就有内容的圆角方形。
                        modifier = Modifier
                            .requiredSize(BALL_ICON_SIZE)
                            .alpha(if (dragging || expanded) 0.92f else 0.6f),
                    )
                }
            }
        }
    }
}

/**
 * 环绕在悬浮球外圈的一个圆按钮。
 *
 * 位置的起、止点都由调用方按圆弧算好后传进来（而不是自己排布），这样按钮的落点只跟球心
 * 有关，换边、拖动都不会算错。[progress] 是展开动画的进度：0 = 收在球心且看不见，1 = 到位。
 * 位移、透明度、缩放都由它插值出来，所以展开/收起是「从球里长出去」而不是原地淡入。
 */
@Composable
private fun BallMenuButton(
    progress: Float,
    fromX: Dp,
    fromY: Dp,
    toX: Dp,
    toY: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    // 进度为 0 时干脆不画：否则这一圈透明按钮全叠在球心上，会把球的点击抢走。
    if (progress <= 0.01f) return
    val scale = 0.4f + 0.6f * progress
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .offset(
                x = lerp(fromX, toX, progress),
                y = lerp(fromY, toY, progress),
            )
            .size(MENU_BUTTON_SIZE)
            .graphicsLayer {
                alpha = progress
                scaleX = scale
                scaleY = scale
            },
    ) {
        IconButton(onClick = onClick) { content() }
    }
}

/**
 * 「开不了画中画」的引导。
 *
 * 分两种：设备没有画中画能力（只能说明情况），与系统把本应用的画中画关掉了（给一条去设置的
 * 路 —— 那个开关在应用信息页里，没有可直接打开的公开入口，所以不猜 ROM 的跳转）。
 */
@Composable
private fun PipGuideDialog(
    switchOff: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.dsh_pip_guide_title)) },
        text = {
            Text(
                stringResource(
                    if (switchOff) CoreR.string.dsh_pip_guide_text_supported
                    else CoreR.string.dsh_pip_guide_text_unsupported
                )
            )
        },
        confirmButton = {
            if (switchOff) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(CoreR.string.dsh_pip_guide_settings))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            }
        },
        dismissButton = if (switchOff) {
            { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
        } else {
            null
        },
    )
}

/**
 * WebUI 之上的扩展叠加层（`WEBUI_OVERLAY` 槽位）。
 *
 * 为什么放在 WebUI 这一页而不是主界面：悬浮按钮、快捷面板这类「浮在 dsh 页面上」的东西
 * 需要和页面在同一坐标系里 —— 挂在别的 Activity 上就得自己算两个窗口的相对位置。
 *
 * 必要性：`slotEntries` 在此之前**没有任何渲染点**，扩展即使注册了 WEBUI_OVERLAY 也不会显示。
 *
 * 安全说明：叠加层只画扩展自己的内容，**不接管 WebView 的导航**。外链仍然走
 * [DshWebUi.openExternal]，因为 BlobBridge 是 addJavascriptInterface 挂上去的 —— 一旦让
 * 外部站点留在 WebView 里，那个站点就能直接往磁盘写文件。
 */
@Composable
private fun ExtensionWebUiOverlay() {
    val entries = ExtensionUiRegistry.slotEntries(ExtensionUiRegistry.Slot.WEBUI_OVERLAY)
    if (entries.isEmpty()) return
    Box(Modifier.fillMaxSize()) {
        for (e in entries) {
            // 叠加层自己不铺满：给扩展内容一个「想多大就多大」的容器，
            // 铺满会导致它吃掉整页的点击。
            Box(Modifier.wrapContentSize()) {
                e.content(Modifier)
            }
        }
    }
}

