package me.bmax.apatch.dsh.ext

import android.content.Context
import java.io.File

/**
 * 宿主级扩展的入口契约。
 *
 * 与另外两种「扩展」的区别：
 *  - DSH 插件：跑在容器里的 Node.js，受容器约束；
 *  - 用户脚本：注入内置 WebView，受浏览器沙箱约束；
 *  - **扩展（本文件）**：加载进 App 进程，与宿主同权。
 *
 * ## 宿主对混淆的约定（改 proguard 规则前必读）
 * 扩展是**外部编译产物**，它的字节码里写死了这些名字：
 *  - `me.bmax.apatch.dsh.ext.**`（本文件与三个注册中心）
 *  - `kotlin.**`（stdlib，例如 Intrinsics）
 *  - `androidx.compose.**` 与 `kotlinx.coroutines.flow.**`（画界面、收状态流）
 * 所以宿主 release 构建**必须**按 proguard-rules.pro 里的 keep 规则保留它们；
 * 少一条的表现都很隐蔽：不是编译错误，而是「扩展加载失败」或「onLoad 里 Intrinsics 解析失败」，
 * 且只在 release 包上出现（debug 不混淆）。这条坑真机踩过两次。
 *
 * 这个接口放在 :ext-api 模块里，所以第三方仓库可以编译出自己的实现；
 * 宿主用 DexClassLoader 加载它（父加载器是宿主 classLoader，因此运行期与宿主同权）。
 */
interface DshExtension {
    /** 宿主加载完这个扩展后回调。抛异常会被捕获并跳过该扩展，不影响其它扩展与宿主启动。 */
    fun onLoad(host: DshExtensionHost)

    /** 停用 / 移除 / 安全模式熔断时回调（可选）。同样不能抛出去影响调用方。 */
    fun onUnload() {}

    /**
     * 容器就绪时回调（可选）：运行时已装好、`/usr/local/bin` 可写。
     *
     * ## 为什么需要单独一个回调
     *
     * [onLoad] 在 App 启动时就跑，那时运行时**可能还没装**（首次启动、或用户刚清空数据）。
     * 往不存在的 rootfs 里装命令只会失败，而扩展没有办法知道"什么时候该重试"。
     *
     * 宿主在两种时机调它：
     *  1. 运行时安装完成之后（用户刚装完，立刻能用扩展提供的命令）；
     *  2. 启动时运行时已经就绪的情况下，紧跟 [onLoad] 之后。
     *
     * 实现要**幂等**：它可能被调用多次（重装运行时、切换通道都会触发）。
     */
    fun onContainerReady(host: DshExtensionHost) {}
}

/**
 * 交给扩展的宿主句柄。
 *
 * 做成**接口**而不是数据类：宿主侧要往里加东西（比如只有 App 内扩展才拿得到的强类型门面），
 * 第三方只需要按接口编程；接口里只放「任何扩展都用得上、且不牵出宿主内部类型」的能力。
 */
interface DshExtensionHost {
    /** 扩展 id（目录名，来自 manifest）。 */
    val id: String

    /** 宿主 application context（不是 Activity）。 */
    val appContext: Context

    /** 目录读写（见 [ExtensionFs]）。 */
    val fs: ExtensionFs

    /** 写进宿主日志（前缀 [ext:扩展id]），便于报障时定位是哪个扩展干的。 */
    fun log(message: String)

    /**
     * 宿主开关（见 [HostSwitch]）。
     *
     * ## 为什么扩展需要改宿主设置
     *
     * 有些能力**只能**在宿主层面开：浏览器模式决定 WebView 是否留在应用内加载外部站点，
     * 那是宿主的加载策略，扩展改不了自己运行的环境。而"给 DSH 开放调试接口"这类扩展
     * 恰恰依赖它 —— 不开浏览器模式，扩展就永远等不到那些页面。
     *
     * ## 这不是无限制的写权限
     *
     * 只有 [HostSwitch] 里列出的开关可以被改，而且每个都要在 manifest 的
     * `declaredCaps` 里声明（用户在启用前会看到它）。扩展**不能**借这个接口改任意 prefs。
     */
    val host: ExtensionHostSettings

    /**
     * 往容器里装命令（见 [ExtensionContainer]）。
     *
     * 有了它，「容器里能用的命令」才算真正可以由扩展提供 —— 否则扩展只能开开关，
     * 命令必须由宿主写死。
     */
    val container: ExtensionContainer

    /**
     * 全部宿主能力（见 [ExtensionPlatform]）。
     *
     * 有了它，「软件现有功能都可由扩展实现」才成立：扩展能调到宿主今天有的任何
     * 子系统，而不是只能等宿主把某个开关加到白名单里。
     */
    val platform: ExtensionPlatform
}

/**
 * 扩展能改的宿主开关。**白名单**：加一项就要在宿主侧实现一项。
 *
 * 每一项都对应一个真实的宿主设置，且都是「扩展确实需要、但没有别的办法影响」的：
 *  - [BROWSER_MODE]：WebView 是否在应用内加载外部站点。浏览器调试类扩展必需。
 */
enum class HostSwitch(val id: String) {
    BROWSER_MODE("browser_mode"),

    /**
     * WebView 远程调试通道（CDP）。
     *
     * 开了它，容器里的 dsh 才能通过 Chrome DevTools 协议控制应用内的 WebView ——
     * 读 DOM、执行 JS、截屏、拦截请求。这是「给 DSH 开放浏览器 API」的实现基础。
     *
     * 依赖 [BROWSER_MODE]：不开浏览器模式时 WebView 只加载回环的 dsh 页面，
     * 容器自己就是那个页面的服务端，不需要调试通道。
     */
    WEBVIEW_DEBUG("webview_debug"),
    ;

    companion object {
        fun fromId(id: String): HostSwitch? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 往容器里装命令的能力。
 *
 * ## 为什么这必须是宿主能力，而不是扩展自己写文件
 *
 * 容器的根文件系统在宿主私有目录下（rootfs），扩展**拿不到**那个路径 ——
 * 也不该拿到：能往 rootfs 里写文件，就等于能在容器里执行任意代码，
 * 那是比"调一个开关"高一个数量级的权限。
 *
 * 所以这里给的是一个**受限的安装入口**：扩展交出脚本内容，宿主负责落盘、
 * 加执行位、并登记归属。扩展拿不到 rootfs 的写权限，只能装自己要装的那几个命令。
 *
 * ## 卸载即清理
 *
 * 宿主按 [DshExtensionHost.id] 记着"这个命令是谁装的"。扩展停用或卸载时，
 * 宿主把它的命令一并摘掉 —— 否则用户会看到"我卸载了扩展，命令还在"。
 * 这正是这一版之前 `dsh-browser` 的问题。
 */
interface ExtensionContainer {

    /**
     * 装一个命令到容器 `/usr/local/bin/<name>`。
     *
     * @param name 命令名。只能是 `[a-z0-9][a-z0-9._-]*`（会被拼进路径，必须校验）。
     * @param script 脚本正文。宿主会写文件并加执行位，不解释内容。
     * @return true = 已装好；false = 名字不合法或写入失败（不抛异常，扩展应据此降级）。
     *
     * ## 为什么允许扩展装任意脚本
     *
     * 扩展本来就在 App 进程内、与宿主同权，它能做的事远多于往容器里放个脚本。
     * 这里设限不是为了防扩展，而是为了让**用户**能看见"哪个扩展装了什么"
     * （扩展详情页会列出来），并且在卸载时能干净地收回去。
     */
    fun installCommand(name: String, script: String): Boolean

    /** 摘掉一个命令（扩展自己主动清理时用；卸载时宿主会自动做）。 */
    fun removeCommand(name: String): Boolean

    /** 当前这个扩展装过哪些命令（给扩展自己核对用）。 */
    fun installedCommands(): List<String>
}

/** 宿主开关的读写门面。实现由宿主提供。 */
interface ExtensionHostSettings {
    /** 读一个开关的当前值。 */
    fun get(switch: HostSwitch): Boolean

    /**
     * 写一个开关。
     *
     * @return true = 已写入；false = 该扩展没有在 manifest 里声明这个能力，被拒绝。
     *   拒绝时**不抛异常**：扩展应当据此降级，而不是崩掉整个宿主。
     */
    fun set(switch: HostSwitch, on: Boolean): Boolean
}

/**
 * 宿主能力的**全量**访问入口（反射式）。
 *
 * ## 为什么不是强类型门面
 *
 * 宿主有 33 个能力单例（runtime / shell / a11y / camera / workflow / …），其中大部分是
 * `internal object`。把它们逐个搬进 :ext-api 有两个硬障碍：
 *
 * 1. **模块方向**：:ext-api 不能依赖 :app（那会成环），而这些单例都在 :app 里。
 * 2. **API 承诺**：把 `internal` 类型写进公共接口，等于把它们钉成永久契约 ——
 *    以后任何一次重构都是破坏性变更。
 *
 * 所以这里给的是**按名字调用**的入口：扩展写 `platform.call("shell", "exec", ...)`，
 * 宿主在自己的进程内把名字解析成真实的单例与方法。扩展因此能调到宿主今天有的任何能力，
 * 宿主新增能力时扩展**不需要**等 :ext-api 发版。
 *
 * ## 代价（必须让扩展作者知道）
 *
 * 没有编译期类型检查：方法名拼错、参数类型不对，都要到运行期才报错。
 * 这是「覆盖全部宿主能力」与「稳定 API 契约」之间必须选一个的地方 ——
 * 按当前需求选了前者。详见 [PlatformResult]。
 */
interface ExtensionPlatform {

    /**
     * 列出全部可用的宿主能力名（即 DshPlatform 上的属性名）。
     *
     * 扩展可以据此做能力探测：宿主版本不同，可用的能力也不同。
     */
    fun capabilities(): List<String>

    /**
     * 读一个宿主能力对象（返回它在宿主进程里的实例）。
     *
     * 拿到之后怎么调？看 [invoke]。
     *
     * @param capability [capabilities] 里的名字，例如 "shell"、"a11y"、"runtime"。
     * @return 能力对象；名字不存在时返回 null（不抛异常）。
     */
    fun capability(capability: String): Any?

    /**
     * 在一个宿主能力上调方法。
     *
     * @param capability 能力名，例如 "shell"。
     * @param method 方法名，例如 "exec"。重载按参数**个数与类型**匹配。
     * @param args 实参。基本类型、String、List/Map 可以直接传；
     *   需要宿主类型的参数请用 [capability] 拿到实例后自己调。
     * @return 调用结果。方法返回 void 时是 Unit；失败时见 [PlatformResult.error]。
     */
    fun invoke(capability: String, method: String, vararg args: Any?): PlatformResult

    /**
     * 读一个宿主能力上的属性（Kotlin 的 val / var，对应 getter）。
     *
     * 例如 `property("runtime", "state")` 拿到 StateFlow。
     */
    fun property(capability: String, name: String): PlatformResult
}

/**
 * [ExtensionPlatform.invoke] / [property] 的结果。
 *
 * 为什么不用「返回值 + 抛异常」：扩展与宿主同进程，一个没接住的异常会带走整个 App。
 * 所以所有失败都折进这里，让扩展自己决定是降级还是上报。
 */
class PlatformResult(
    /** 成功时的返回值；失败时为 null。方法返回 void 时是 Unit。 */
    val value: Any?,
    /** 失败原因（人类可读）；成功时为 null。 */
    val error: String?,
) {
    val ok: Boolean get() = error == null

    companion object {
        fun success(value: Any?): PlatformResult = PlatformResult(value, null)
        fun failure(reason: String): PlatformResult = PlatformResult(null, reason)
    }
}

/**
 * 扩展在磁盘上的形态：filesDir/extensions/扩展id/。
 *
 * 目录里：manifest.json（必填）、classes.dex（可选）、assets/（可选）。
 * 不做 zip：安装时解压到这个目录即可，也方便用户在文件管理器里核对。
 */
object ExtensionLayout {
    /** 扩展根目录（宿主私有）。 */
    fun root(ctx: Context): File = File(ctx.filesDir, "extensions")

    /** 某个扩展的目录。 */
    fun dirOf(ctx: Context, id: String): File = File(root(ctx), id)

    /** 扩展的 dex（可能不存在 —— 纯声明式扩展不需要代码）。 */
    fun dexOf(ctx: Context, id: String): File = File(dirOf(ctx, id), "classes.dex")

    /** 扩展的 manifest。 */
    fun manifestOf(ctx: Context, id: String): File = File(dirOf(ctx, id), "manifest.json")

    /** 扩展自己的资源目录。 */
    fun assetsOf(ctx: Context, id: String): File = File(dirOf(ctx, id), "assets")

    /** 校验 id 是否可安全用作目录名（防 .. / 分隔符穿越）。 */
    fun isValidId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 64 &&
            id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' } &&
            id != "." && id != ".."
}

/**
 * 扩展侧的日志出口。
 *
 * 为什么是一个可注入的 sink 而不是直接写宿主日志：:ext-api 不依赖宿主，而日志最终要落到
 * 宿主的 dsh.log 里（报障时最有用）。宿主在启动时把 sink 指向自己的 LogStore。
 */
object ExtLog {
    /** 宿主导入的实现：写进 dsh.log（**只给真正的故障用**）。 */
    @Volatile
    var sink: (String) -> Unit = {}

    /**
     * 宿主导入的第二条出口：只进 logcat，不写 dsh.log。
     *
     * 为什么不全都写 dsh.log：dsh.log 是用户在「设置 → 日志」里看的启动日志，
     * 塞满「准备加载 xx」「外壳决策」这种例行诊断会把真故障淹没掉。而这些信息在
     * 排查问题时又必须有 —— 所以走 logcat，bugreport 里照样拿得到。
     */
    @Volatile
    var infoSink: (String) -> Unit = {}

    /** 例行诊断：只进 logcat。 */
    fun info(message: String) {
        runCatching { infoSink(message) }
    }

    /** 故障：进 dsh.log（也进 logcat）。 */
    fun log(message: String) {
        runCatching { sink(message) }
    }
}

/**
 * 扩展系统的运行门（由宿主设置，扩展 API 只读）。
 *
 * 存在的理由：注册中心与启动管线都要在**安全模式熔断之后立刻短路**，但「是否熔断」是宿主的
 * 概念（要读文件系统、要读 prefs），:ext-api 里不能实现。所以宿主把结论塞进来，这里只读。
 */
object ExtensionGate {
    /** 熔断位：为真时注册中心一律不生效、既有注册一律按「不存在」处理。 */
    @Volatile
    var latched: Boolean = false

    fun isLatched(): Boolean = latched
}
