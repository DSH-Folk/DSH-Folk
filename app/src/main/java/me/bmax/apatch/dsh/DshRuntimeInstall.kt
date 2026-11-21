package me.bmax.apatch.dsh

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.bmax.apatch.dsh.ext.ExtensionEngine
import me.bmax.apatch.util.UpdateChecker
import me.bmax.apatch.res.R as CoreR

/**
 * 运行时安装、切换、更新检查，以及文件桥的配置写入。
 *
 * ## 为什么这些在一起
 *
 * 它们的共同点是「**动 rootfs 或动运行时元数据**」：安装/重装/切换版本/导入
 * tar 包/查更新，以及文件桥 token 与配置文件的落盘。这些操作必须互斥
 * （[DshKernelState.bootMutex] / [DshKernelState.updateCheckMutex]），
 * 拆到两个类里会让"谁该持哪把锁"变成容易搞错的事。
 *
 * ## 为什么从 DshRuntime 拆出来
 *
 * 706 行、30 个函数，是 `DshRuntime` 里最大的一块。它只依赖共享状态
 * 和少数几个引导函数（[DshRuntime.startAndAwaitForInstall] 等），
 * 拆出来之后 `DshRuntime` 从 2995 行降到约 2290 行。
 */
object DshRuntimeInstall {

    /** 短名，避免每行都写 [DshKernelState]。 */
    private val K get() = DshKernelState

    /**
     * 自身引用。
     *
     * `withLock { }` 的 lambda 接收者是 `Mutex`，把外层 object 的 `this` 遮住了 ——
     * 于是 lambda 里直接写 `clearSeedLedger()` 会报 "receiver type mismatch"。
     * `DshRuntimeInstall.clearSeedLedger()` 也不行（同样受遮蔽影响）。
     * 用一个普通属性拿到自身引用最可靠。
     */
    private val self get() = this


    /** 取（或首先生成）文件桥 token。 */
    internal fun ensureFsToken(): String {
        val p = K.prefs()
        p.getString(DshEnv.KEY_FS_TOKEN, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val t = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        p.edit().putString(DshEnv.KEY_FS_TOKEN, t).apply()
        return t
    }

    /**
     * 把端口 + token（以及 WebView 调试 socket 名）写进容器内配置文件。
     *
     * `dsh-fs` / `dsh-saf` / `dsh-native` / `dsh-browser` 都读这一份。
     *
     * 调试 socket 名只在开着时才写：没开的时候容器里根本连不上，
     * 写一个空名字只会让 `dsh-browser` 报"找不到"，不如不写、让它报"没启用"。
     */
    internal fun writeFsBridgeConfig(port: Int, token: String) {
        runCatching {
            DshEnv.fsBridgeConfig(K.appContext).parentFile?.mkdirs()
            val socket = DshEnv.webviewDebugSocket(K.appContext)
            val extra = if (socket.isNotEmpty()) {
                ",\"webviewSocket\":\"" + socket + "\""
            } else {
                ""
            }
            DshEnv.fsBridgeConfig(K.appContext).writeText(
                "{\"port\":$port,\"token\":\"$token\"" + extra + "}",
                StandardCharsets.UTF_8,
            )
        }
    }

    /**
     * 运行时里有没有 pnpm。
     *
     * **只判定，不修 rootfs。** pnpm 是运行时的组成部分：runtime-builder 固定
     * `pnpm@10.34.5`、按 `package.json.bin` 重建 `usr/local/bin` 下的链接，并在打包前
     * 用 `node bin/pnpm.cjs --version` 自检。App 侧再打补丁只会把「运行时该负责的事」
     * 藏起来 —— 真机日志里表现就是每次进容器都刷一行「已修复此运行时的 pnpm」，
     * 而用户根本不知道自己的运行时其实是坏的。
     *
     * 判据只看 `usr/local/bin/pnpm` 在不在：执行位与入口正确性由运行时构建期自检保证，
     * 容器内 `dsh plugin` 前还有一次 `command -v pnpm` 兜底（见 DshPluginRepo）。
     */
    internal fun pnpmInstalled(): Boolean =
        File(DshEnv.rootfs(K.appContext), "usr/local/bin/pnpm").exists()

    /**
     * 把 `dsh-fs` / `dsh-native` CLI 写进容器（rootfs 就在 App 私有目录，直接落盘，
     * 不必 execRootfs heredoc）。
     *
     * **每次启动都会调用**（见 [startServer] 里的调用点），所以必须按内容比对再写。
     *
     * 以前它只在引导路径（bootstrap / reinstall）调用，那留下了一个很安静的缺口：用户升级
     * App 但沿用同一套 rootfs 时，容器里的 CLI 永远是上一次引导时写下的那份。加一个新子命令
     * 后主机提示词会立刻开始向 agent 宣传它，而容器里根本没有 —— agent 拿到的是
     * "command not found"，翻遍设置页也找不到原因。按内容比对再写，成本只是读两个小文件。
     *
     * 两个脚本都读同一份 fs-bridge.json，所以不需要各自的配置或 ROOTFS_REV 变更。
     * （`dsh-saf` 是第三个，同样读那一份、同一 token、同一 port，只是打到 `/saf/…`。）
     */
    internal fun ensureFsBridgeCli() {
        if (!DshEnv.isRuntimeInstalled(K.appContext)) return
        val bin = File(DshEnv.rootfs(K.appContext), "usr/local/bin")
        for ((name, script) in listOf(
            "dsh-fs" to DshRuntime.FS_BRIDGE_CLI_SCRIPT,
            "dsh-native" to DshRuntime.NATIVE_CLI_SCRIPT,
            "dsh-saf" to DshRuntime.SAF_BRIDGE_CLI_SCRIPT,
            "dsh-workflow" to DshRuntime.WORKFLOW_CLI_SCRIPT,
        )) {
            runCatching {
                DshRuntimeFiles.writeScript(File(bin, name), script)
            }.onFailure { android.util.Log.w(DshRuntime.TAG, "写 $name 失败: ${it.message}") }
        }
        // 权限桥疑难解答：agent 报错时用 `dsh-native troubleshoot` 读它。和上面的脚本一样
        // 按内容比对再写 —— 换应用语言后这份清单必须跟着变，否则 agent 拿到的是上一门语言的步骤。
        runCatching {
            DshRuntimeFiles.writeIfChanged(
                File(DshEnv.dshHome(K.appContext), DshRuntime.TROUBLESHOOT_DOC_NAME),
                DshRuntime.nativeTroubleshootDocForInstall(),
            )
        }.onFailure { android.util.Log.w(DshRuntime.TAG, "写 ${DshRuntime.TROUBLESHOOT_DOC_NAME} 失败: ${it.message}") }
        // 工作流 DSL 说明：容器里的 `dsh-workflow` 与工具插件的提示词段都读它，写在这儿是为了
        // 「实现改了、文档跟着改」——否则 agent 会照着一份过时说明写定义，然后在校验器那里撞墙。
        // 样例定义：agent 抄一份改，比从零写更不容易漏字段；设置页也拿它当新建模板。
        runCatching {
            val dir = File(DshEnv.dshHome(K.appContext), DshRuntime.WORKFLOW_SAMPLES_DIR)
            dir.mkdirs()
            for ((sampleName, sampleText) in DshRuntime.WORKFLOW_SAMPLES) {
                DshRuntimeFiles.writeIfChanged(File(dir, sampleName + ".json"), sampleText)
            }
        }.onFailure { android.util.Log.w(DshRuntime.TAG, "写工作流样例失败: ${it.message}") }
        // 容器就绪：通知扩展来装自己的命令。
        //
        // 放在这里而不是 install 的末尾，是因为 ensureFsBridgeCli 是"运行时可用"的
        // 统一汇合点（安装、重装、切通道、启动都会走到）。扩展的命令因此不会出现
        // "装完运行时还得重启一次应用才生效"的窗口。
        runCatching {
            ExtensionEngine.notifyContainerReady(K.appContext)
        }.onFailure { android.util.Log.w(DshRuntime.TAG, "通知扩展容器就绪失败: ${it.message}") }
        runCatching {
            DshRuntimeFiles.writeIfChanged(
                File(DshEnv.dshHome(K.appContext), DshRuntime.WORKFLOW_DSL_DOC_NAME),
                DshRuntime.WORKFLOW_DSL_DOC,
            )
        }.onFailure { android.util.Log.w(DshRuntime.TAG, "写 $DshRuntime.WORKFLOW_DSL_DOC_NAME 失败: ${it.message}") }
    }

    /**
     * 把容器侧的 CLI / 工作流指南 / 样例定义按当前版本重写一遍。
     *
     * 为什么需要单独一个入口：这些文件平时只在**容器启动**时对一遍（[ensureFsBridgeCli]），
     * 而用户完全可能「升了 App、容器一直没重启」—— 那时的容器里没有 dsh-workflow、没有指南、
     * 没有样例，agent 就会卡在「不知道该怎么用、格式是什么」上（实测就是这样）。设置页打开
     * 或打开工作流开关时调一次，把这段窗口补上；工具插件要重启 DSH 才生效，这一条如实写在
     * 设置页上。
     */
    fun refreshContainerCli() {
        runCatching { ensureFsBridgeCli() }
            .onFailure { android.util.Log.w(DshRuntime.TAG, "刷新容器侧 CLI 失败: ${it.message}") }
        // 插件与 cordis 挂载项也要一起补：agent 看到工作流的**唯一**通道就是这两个插件
        // （工具描述 + 提示词段）。只补 CLI 与指南的话，升级后没重启过容器的设备上，agent
        // 依然什么都不知道 —— 这正是实测里「折腾半天不知道怎么用」的成因。
        runCatching { DshHostPrompt.ensureInstalled(K.appContext) }
            .onFailure { android.util.Log.w(DshRuntime.TAG, "刷新容器侧插件失败: ${it.message}") }
    }

    /** 启动回环桥（选空闲端口 + 写配置 + 监听）。文件端点与原生端点共用它。 */
    internal fun startFsBridge() {
        runCatching {
            val fsPort = DshPortConfig.findFreePort(DshFsBridge.PORT_BASE)
            val fsToken = ensureFsToken()
            writeFsBridgeConfig(fsPort, fsToken)
            DshFsBridge.start(fsPort, fsToken, K.appContext)
            DshLogging.logInfo(CoreR.string.dsh_log_bridge_up)
        }.onFailure { DshLogging.logWarn(CoreR.string.dsh_log_bridge_failed, it.message ?: it.javaClass.simpleName) }
    }

    /** 强制重启 web 服务。 */
    fun restart() {
        K.scope.launch {
            K.bootMutex.withLock {
                DshRuntime.stopServer()
                delay(500)
                if (DshRuntime.checkPortConflictForInstall()) return@withLock
                DshRuntime.startAndAwaitForInstall()
            }
        }
    }

    /**
     * 在**服务已停止**的状态下跑一段活，然后无论成败都把服务起回来。
     *
     * 为什么要停（准确一点说：**更稳，而不是唯一可行**）：工作区注册表是 storage-json ——
     * 启动时读一次盘进内存，此后内存是权威，平时不重读盘；而任意一次 workspace 域写
     * （建/删工作区、调顺序、归档、挂接会话）会把**整份内存状态**原子覆盖回盘。
     *
     * 由此可以推出：运行中改文件是**可行的**，只要改完到重启之间没有发生过任何 workspace
     * 域写 —— 重启后照样会被加载（真机上验证过）。风险不是写坏文件，而是**静默丢失**：
     * 中间若有那么一次 workspace 写，旧内存状态会把改动整份盖掉，等于白改。
     * 停止服务只是把这个风险归零。而这一步之后用户往往还会继续在 GUI 里动工作区
     * （导入完还要重启才生效），所以宁可多停几秒也不要「改完看着成功、重启后一切照旧」。
     *
     * 与 [restart] 同样走 K.bootMutex：两条路径并发会把容器搅成两份 dsh 在抢端口。
     * [block] 抛异常也必须恢复服务：用户点的是一次「导入」，不该因为归组失败就得到
     * 一个停着的服务。
     */
    suspend fun <T> withServiceStopped(block: suspend () -> T): T? = K.bootMutex.withLock {
        DshRuntime.stopServer()
        try {
            block()
        } finally {
            // 端口被占说明容器里已经有一份在跑：保持现状，别在别人的端口上再起一份
            if (!DshRuntime.checkPortConflictForInstall()) DshRuntime.startAndAwaitForInstall()
        }
    }

    /** 重新下载并安装运行时。 */
    /**
     * 「更新」：按**通道候选**取最新的一份（测试通道 = 正式 + 测试里更新者）。
     *
     * 与 [reinstallRuntime] 分开是必须的：两者以前共用同一条路（UI 上「更新」确认框调的就是
     * 重装那个回调），于是「测试通道检测到正式版更新」在按下更新后仍然去下当前通道那份 ——
     * 真机日志里就是这样：检测到新版，下载 URL 还是 runtime-beta-latest。
     */
    /**
     * 装完但不启动：把状态落回「已安装、未运行」。
     *
     * 不能什么都不做：`downloadAndInstall` 结束时 phase 停在 DOWNLOADING/EXTRACTING 之类的
     * 中间态上，首页会一直转圈，而实际上安装早就完成了。
     * 也不能复用 DshRuntime.stopServer() —— 那会写「已停止」，而这里从来没有启动过。
     */
    internal fun markInstalledIdle() {
        // 只改 phase 与 message：webUrl 是**计算属性**（由 port + token 拼出来），
        // 不是构造参数，写不了；pid / webToken 也不该在这里清 ——
        // 装完不启动时它们本来就是空的。
        K.setState { it.copy(
            phase = DshPhase.NOT_READY,
            progress = 0f,
            speedBytesPerSec = 0L,
            message = "",
        ) }
    }

    fun updateRuntime(preserveData: Boolean = true, autoStart: Boolean = true) {
        K.scope.launch {
            K.bootMutex.withLock {
                DshRuntime.stopServer()
                self.clearSeedLedger()
                downloadAndInstall(preserveData, newest = true)
                if (K.current.phase != DshPhase.ERROR) {
                    self.setupResolvConf()
                    DshRuntime.seedPluginsForInstall()
                    self.ensureFsBridgeCli()
                    if (DshRuntime.checkPortConflictForInstall()) return@withLock
                    // 装完**不一定要**立刻拉起来：首页那条「安装」路径要把「装完」与
                    // 「启动」拆成两步，由用户在弹窗里自己决定。不拆的话，用户点「安装」
                    // 之后容器直接跑起来，而他想先看一眼装了什么的机会就没有了。
                    if (autoStart) DshRuntime.startAndAwaitForInstall() else self.markInstalledIdle()
                }
            }
        }
    }

    fun reinstallRuntime(preserveData: Boolean = true, autoStart: Boolean = true) {
        K.scope.launch {
            K.bootMutex.withLock {
                DshRuntime.stopServer()
                // 重装等于换了一套全新 rootfs，容器里的插件确实没了，该重新预装
                self.clearSeedLedger()
                // 重装 = 装回已装那一份，不跟着通道走（否则「重装」会变成静默升级）
                downloadAndInstall(preserveData, newest = false)
                if (K.current.phase != DshPhase.ERROR) {
                    self.setupResolvConf()
                    DshRuntime.seedPluginsForInstall()
                    self.ensureFsBridgeCli()
                    if (DshRuntime.checkPortConflictForInstall()) return@withLock
                    if (autoStart) DshRuntime.startAndAwaitForInstall() else self.markInstalledIdle()
                }
            }
        }
    }

    /**
     * 安装版本列表里选中的那一份运行时（升级 / 降级 / 换通道都走这里）。
     *
     * 与 [reinstallRuntime] 的唯一区别是 metadata 从哪来：这里用用户选中的那个
     * release，而不是当前通道的最新版 —— 否则「切到 0.1.1-rc.2」会变成「又装了一遍
     * 最新版」。其余流程完全一致：先停服务，装完重新预装插件并拉起服务。
     */
    fun switchRuntimeVersion(entry: RuntimeVersion, preserveData: Boolean = true) {
        K.scope.launch {
            K.bootMutex.withLock {
                DshLogging.clearLog()
                DshLogging.logInfo(CoreR.string.dsh_log_switch_runtime, entry.version, entry.tag)
                DshRuntime.stopServer()
                self.clearSeedLedger()
                K.stateFlow.update {
                    it.copy(
                        phase = DshPhase.DOWNLOADING,
                        progress = 0f,
                        speedBytesPerSec = 0L,
                        message = DshLogging.str(CoreR.string.dsh_msg_downloading, entry.version),
                    )
                }
                installMeta(entry.toMeta(), preserveData)
                if (K.current.phase != DshPhase.ERROR) {
                    self.setupResolvConf()
                    DshRuntime.seedPluginsForInstall()
                    self.ensureFsBridgeCli()
                    if (DshRuntime.checkPortConflictForInstall()) return@withLock
                    DshRuntime.startAndAwaitForInstall()
                }
            }
        }
    }

    /**
     * 换 rootfs = 容器里的预装插件没了：清掉预装记账，让下一轮 [seedPlugins] 重新来一遍。
     */
    internal fun clearSeedLedger() {
        K.prefs().edit()
            .remove(DshEnv.KEY_SEEDED_PLUGINS)
            .remove(@Suppress("DEPRECATION") DshEnv.KEY_SEED_PLUGINS_DONE)
            .apply()
    }

    /**
     * App 启动后是否自动检查运行时更新（设置里「自动检查更新」那个独立开关）。
     *
     * 默认**开**：不看版本的存量用户只有靠启动提示才知道自己卡在一份坏的 rootfs 上。
     */
    fun autoCheckEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
            .getBoolean(DshEnv.KEY_RUNTIME_AUTO_CHECK, true)

    fun setAutoCheckEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DshEnv.KEY_RUNTIME_AUTO_CHECK, on)
            .apply()
    }

    fun importRuntime(tarball: File, preserveData: Boolean = true) {
        K.scope.launch {
            K.bootMutex.withLock {
                DshRuntime.stopServer()
                DshLogging.clearLog()
                K.stateFlow.update { it.copy(phase = DshPhase.EXTRACTING, message = DshLogging.str(CoreR.string.dsh_msg_installing)) }
                val ok = withContext(Dispatchers.IO) { installRuntimeArchive(tarball, preserveData) }
                tarball.delete()
                if (!ok) {
                    DshLogging.fail(DshLogging.str(CoreR.string.dsh_err_extract_failed))
                    return@withLock
                }
                K.prefs().edit()
                    .remove(DshEnv.KEY_SEEDED_PLUGINS)
                    .remove(@Suppress("DEPRECATION") DshEnv.KEY_SEED_PLUGINS_DONE)
                    .putString(DshEnv.KEY_RUNTIME_VERSION, "imported")
                    .apply()
                K.stateFlow.update {
                    it.copy(
                        phase = DshPhase.NOT_READY,
                        installed = true,
                        runtimeVersion = "imported",
                        progress = 1f,
                        message = DshLogging.str(CoreR.string.dsh_msg_runtime_ready),
                    )
                }
                DshRuntime.refreshRootfsSize()
                self.setupResolvConf()
                DshRuntime.seedPluginsForInstall()
                self.ensureFsBridgeCli()
                if (!DshRuntime.checkPortConflictForInstall()) DshRuntime.startAndAwaitForInstall()
            }
        }
    }

    /**
     * 运行时通道当前生效的下载源。
     *
     * 竞速**关掉**（总开关或「运行时」分开关）时不再测速：用户若在「下载渠道」里明确指定过某个源，
     * 就按他指定的走（那是手选、不是竞速）；只有 `auto`（= 交给竞速决定）才退化为直连 GitHub。
     */
    fun runtimeSource(ctx: Context): String {
        val setting = DshSource.setting(ctx)
        if (!DshRuntime.raceEnabled(DshPortConfig.RACE_RUNTIME)) {
            return if (setting == DshSource.SOURCE_AUTO) DshSource.SOURCE_GITHUB else setting
        }
        return DshSource.resolve(ctx)
    }

    /** 当前该拉的 metadata.json 地址（自定义源仍走它自己的 URL）。 */
    internal fun runtimeMetaUrl(): String {
        val src = runtimeSource(K.appContext)
        if (src == DshSource.SOURCE_CUSTOM) return DshSource.effectiveMetaUrl(K.appContext)
        return DshSource.proxyPrefix(src) + DshSource.metaUrl()
    }

    /**
     * 拉一份 metadata.json 并解析。失败（断网 / 404 / JSON 坏了）返回 null。
     *
     * 取文本走 [fetchJsonText]（直连优先，其次各代理前缀）：版本列表里每个 release 的
     * metadata 都是固定的 GitHub 下载地址，用不上测速排序那一套。
     */
    internal fun fetchMetaFrom(url: String): DshMeta? {
        val text = fetchJsonText(url) ?: return null
        return runCatching {
            val json = JSONObject(text)
            DshMeta(
                version = json.optString("version", "unknown"),
                url = json.getString("url"),
                sha256 = json.optString("sha256", ""),
                sizeBytes = json.optLong("sizeBytes", 0L),
                mirrors = json.optJSONArray("mirrors")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                    ?: emptyList(),
                arch = json.optString("arch", ""),
                dsh = json.optString("dsh", ""),
                nodeVersion = json.optString("nodeVersion", ""),
                builtAt = json.optString("builtAt", ""),
                minAppVersion = json.optString("minAppVersion", ""),
                // 构建脚本一直就写着这一项（runtime-builder/build-rootfs.sh 的 "flavor"），
                // 只是以前不解析 —— 版本列表要按「精简/完整」筛选就得靠它：历史 release 的
                // tag 里没有 flavor，光看 tag 分不出来。
                flavor = json.optString("flavor", ""),
                // 2026-11 起构建脚本直接写这两项。老 metadata 没有，读出来是空/0，
                // 由调用方回退到从版本串里切（RuntimeVersion.parts）。
                ubuntuSuite = json.optString("ubuntuSuite", ""),
                rootfsRev = json.optInt("rootfsRev", 0),
            )
        }.getOrNull()
    }

    /** 某个 release tag 在**本机架构**下的 metadata。 */
    internal fun fetchMetaOfTag(tag: String, suffix: String): DshMeta? =
        fetchMetaFrom(DshSource.releaseBase(tag) + "metadata$suffix.json")

    /**
     * 拉一段文本：直连不行就套代理前缀再试。
     *
     * 这几个 metadata 只有几十 KB，代理前缀是白名单里的两个 gh-proxy 域名 ——
     * 与下载 rootfs 用的是同一批入口，国内网络下直连 GitHub 经常直接超时。
     */
    internal fun fetchJsonText(url: String): String? {
        val candidates = listOf(
            url,
            DshSource.proxyPrefix(DshSource.SOURCE_GHPROXY_CF) + url,
            DshSource.proxyPrefix(DshSource.SOURCE_GHPROXY_AXISNOW) + url,
        ).distinct()
        for (candidate in candidates) {
            val text = runCatching {
                val conn = URL(candidate).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "DSH-Folk")
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    return@runCatching null
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /**
     * 仓库里的 runtime release（tag + 发布时间），按发布时间倒序。
     *
     * 用 API 而不是通道地址：只有 API 能列出历史版本。**必须翻页** —— 一页 100 条，
     * 仓库里已经有 270 多个 release（App 自己的也占位），四个滚动 tag 早就掉到第 3 页去了。
     * 只拉第一页时版本列表里既没有正式版也没有精简版，滑到精简那一档是空列表。
     *
     * 拿不到就返回空表，由 [listRuntimeVersions] 退回通道兜底。
     */
    internal suspend fun fetchRuntimeReleases(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (page in 1..MAX_RELEASE_PAGES) {
            val body = UpdateChecker.fetchApiJson(
                "/repos/DSH-Folk/DSH-Folk/releases?per_page=$RELEASES_PER_PAGE&page=$page",
            ) ?: break
            var got = 0
            runCatching {
                val arr = JSONArray(body)
                got = arr.length()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optBoolean("draft")) continue
                    val tag = o.optString("tag_name").trim()
                    // 仓库里还有 App 自己的 release（v1.8.4…）：只认运行时那批 tag
                    if (!tag.startsWith("runtime")) continue
                    out.add(tag to o.optString("published_at").trim())
                }
            }.onFailure { DshLogging.logWarn(CoreR.string.dsh_log_version_list_failed) }
            // 这一页没给满 = 到头了；拉空了也停，别让网络异常变成 6 次重试
            if (got < RELEASES_PER_PAGE) break
        }
        return out.sortedByDescending { it.second }
    }

    /**
     * 有没有更新的运行时可用。
     *
     * 判据是版本串**不相等**，不是语义比较：`0.1.1-rc.2-ubuntu-noble-r2` 里混了
     * dsh 版本、ubuntu 代号和 rootfs 修订号，semver 比较对它没有意义；而任何一段
     * 变了都值得重装。r2 就是这么来的 —— 内容修了但 dsh 版本没动。
     *
     * 「不相等」走 [RuntimeVersion.sameVersion] 而不是 `==`：命名格式改过一版
     * （`ubuntunoble` → `ubuntu-noble`），逐字比会把这一个分隔符算成新运行时，
     * 给全体存量用户推一次 150MB 重装。
     *
     * @return 远端有不同版本时带 [RuntimeCheckResult.version]；若那份新运行时还要求
     * 更高的 App 版本，[RuntimeCheckResult.minAppVersion] 一并带上（UI 据此改提示
     * 「先更新应用」而不是给「更新运行时」按钮）。查不到 / 已最新 = 空结果。
     */
    /**
     * 运行时更新检查的互斥锁。
     *
     * 自动检查（冷启动 2.5 秒后）与手动检查（设置页点卡片）**可能同时发生**：
     * 用户冷启动后立刻进设置点一下，两条 `checkRuntimeUpdate()` 就并行了。
     *
     * 不加锁的后果不只是多发一轮请求（虽然那也已经浪费了匿名 API 的 60/h 配额），
     * 更麻烦的是两条都可能返回结果，界面收到两次响应 —— 一个提示框弹两次，
     * 或者先到的"有更新"被后到的"没更新"覆盖掉。
     *
     * 用 [Mutex] 而不是"标志位 + 直接返回"：后到的那次**等**前一次做完再返回它的结果，
     * 而不是拿到一个假的"没更新"。用户点检查就是想看到答案，给他一个假的否定比让他多等一会儿糟。
     */

    suspend fun checkRuntimeUpdate(): RuntimeCheckResult = K.updateCheckMutex.withLock {
        withContext(Dispatchers.IO) {
        if (!DshEnv.isRuntimeInstalled(K.appContext)) return@withContext RuntimeCheckResult()
        val meta = fetchNewestMeta() ?: return@withContext RuntimeCheckResult(failure = true)
        val local = K.prefs().getString(DshEnv.KEY_RUNTIME_VERSION, null).orEmpty()
        // 本地版本未知（早期版本装的，没记过）时不谎报有更新：重装要重下 150MB，
        // 不能靠猜就让用户付这个代价；也没法说「已是最新」——那同样是猜的
        if (local.isEmpty()) return@withContext RuntimeCheckResult(failure = true)
        if (RuntimeVersion.sameVersion(meta.version, local)) RuntimeCheckResult()
        else RuntimeCheckResult(
            version = meta.version,
            minAppVersion = if (DshRuntime.appSatisfies(meta.minAppVersion)) "" else meta.minAppVersion,
        )
        }
    }

    /**
     * 列出仓库里**所有**可用的运行时版本（长按「更新」时用）。
     *
     * 光靠通道 tag 拿不到历史版本 —— 滚动通道永远只有那四个位置
     * （`runtime-latest` / `runtime-slim-latest` / `runtime-beta-latest` /
     * `runtime-slim-beta-latest`），它们的内容会被就地覆盖，所以「回到上一版」
     * 只能靠带版本号的历史 release（`runtime-0.1.1-rc.2` 这种）。
     *
     * 数据来源是 Releases API（列 tag + 发布时间）加上每个 release 的 metadata
     * （版本、dsh、node、体积、sha256、最低 App 版本都在里面）。API 走
     * [UpdateChecker.fetchApiJson]，复用那里已经验证过的一批入口：直连被限流时
     * gh-proxy 能代理 api.github.com。
     *
     * 四个滚动 tag **无条件**再质疑一遍：API 里它们按发布时间排在很后面（甚至翻页
     * 之外），而它们才是四个通道当下在用的内容，漏掉就是「列表里没有当前版本」。
     *
     * 排序：先按通道分组（[RuntimeVersion.channelRank]），组内按 [compareRuntimeVersion]
     * 语义降序 —— 不是 `publishedAt`，也不是字符串字典序：那两样都会把
     * `0.2.2-rc.1` 排到 `0.2.1-rc.5` 后面（同一批补发的 release 时间戳只差几十秒，
     * 而字典序下 `rc.9` 比 `rc.10` 大）。
     */
    suspend fun listRuntimeVersions(): List<RuntimeVersion> = withContext(Dispatchers.IO) {
        val want = DshSource.runtimeArch()
        val suffix = if (want == "arm64-v8a") "" else "-x86_64"
        // version 去重：同一个版本串可能同时挂在滚动 tag 与历史 tag 上，保留通道权重高的那份
        val found = LinkedHashMap<String, RuntimeVersion>()
        fun keep(entry: RuntimeVersion) {
            val prev = found[entry.version]
            if (prev == null ||
                RuntimeVersion.channelRank(entry.channel) < RuntimeVersion.channelRank(prev.channel)
            ) {
                found[entry.version] = entry
            }
        }
        fun entryOf(tag: String, published: String, meta: DshMeta) = RuntimeVersion(
            version = meta.version,
            tag = tag,
            channel = RuntimeVersion.channelOf(tag),
            dsh = meta.dsh,
            nodeVersion = meta.nodeVersion,
            builtAt = meta.builtAt,
            minAppVersion = meta.minAppVersion,
            sha256 = meta.sha256,
            sizeBytes = meta.sizeBytes,
            arch = meta.arch,
            url = meta.url,
            mirrors = meta.mirrors,
            publishedAt = published,
            flavor = meta.flavor,
        )

        val releases = fetchRuntimeReleases()
        if (releases.isNotEmpty()) {
            DshLogging.logInfo(CoreR.string.dsh_log_version_list_api, releases.size)
            // 并发拉各 release 的 metadata：串行的话二十几个版本在慢网络上要等半分钟
            val metas = coroutineScope {
                releases.map { (tag, published) ->
                    async { fetchMetaOfTag(tag, suffix)?.let { Triple(tag, published, it) } }
                }.mapNotNull { it.await() }
            }
            for ((tag, published, meta) in metas) {
                if (meta.arch.isNotEmpty() && meta.arch != want) continue
                self.keep(self.entryOf(tag, published, meta))
            }
        }

        // 四个滚动 tag 无条件补一遍。API 全挂 / 限流烧完时这就是**唯一**内容，
        // 所以它不是优化，是保底：精简通道的那两行只能从这里来。
        if (found.isEmpty()) DshLogging.logWarn(CoreR.string.dsh_log_version_list_fallback)
        val rolling = coroutineScope {
            RuntimeVersion.ROLLING_TAGS.map { tag ->
                async { fetchMetaOfTag(tag, suffix)?.let { tag to it } }
            }.mapNotNull { it.await() }
        }
        for ((tag, meta) in rolling) {
            if (meta.arch.isNotEmpty() && meta.arch != want) continue
            keep(entryOf(tag, "", meta))
        }

        found.values.sortedWith(
            compareBy<RuntimeVersion> { RuntimeVersion.channelRank(it.channel) }
                .thenComparator { x, y -> RuntimeVersion.compareRuntimeVersion(y.version, x.version) }
                .thenByDescending { it.publishedAt }
        )
    }

    /** 写容器 DNS（国内解析优先，谷歌/CF 兜底）。 */
    /**
     * 写入容器的 DNS 与 hosts。
     *
     * Android 不暴露 /etc/resolv.conf（DNS 走 netd 的 binder 接口），容器里的 glibc
     * 只会读文件，所以必须自己写一份，否则容器内所有域名解析都失败 —— 表现是
     * npm/pnpm/apt 全部 EAI_AGAIN。base rootfs 里的 resolv.conf 与 hosts 都是 0 字节。
     *
     * localhost 也得手动补：dsh web 绑 127.0.0.1，容器内插件回连 http://localhost:3080
     * 时没有这一行就解析不出来。
     */
    // 实现搬到同包 DshRuntimeEnv（只接 ctx 的纯落地件）；这里保留同名私有壳，调用点零改动。
    internal fun setupResolvConf() = DshRuntimeEnv.setupResolvConf(K.appContext)

    /** 缺失或空文件才写（不覆盖用户自己改过的内容）。 */
    internal fun ensureContainerDns() = DshRuntimeEnv.ensureDns(K.appContext)

    /** 落地件的日志桥：按接口把日志口子交出去，辅助文件不需要认识整个单例。 */
    private val logBridge = object : DshRuntimeLog {
        fun info(resId: Int, vararg args: Any) = DshLogging.logInfo(resId, *args)
        fun warn(resId: Int, vararg args: Any) = DshLogging.logWarn(resId, *args)
    }

    // 实现在 DshRuntimeEnv（只接 ctx + 依赖闭包）。调用点零改动。
    internal fun ensureContainerGroups() = DshRuntimeEnv.ensureContainerGroups(K.appContext, logBridge)

    /** 公开入口保持原签名（DshPluginRepo 会调）。 */
    fun allowProfileBuilds(packages: List<String>, onLine: (String) -> Unit = {}): List<String> =
        DshRuntimeEnv.allowProfileBuilds(K.appContext, packages, onLine)

    /**
     * 让容器内的 pnpm 用「复制」而不是「硬链接」从内容存储装包。
     *
     * 为什么必须这么做：pnpm 用 `link()` 把 CAS 里的文件装进 node_modules，而
     * proot/proroot 的 `--link2symlink` 会把它改写成符号链接（见 [linkBecomesSymlink]）。
     * Node 的 `require.resolve` 默认 realpath，于是
     * `node_modules/<pkg>/package.json` 解析成 `<store>/files/<xx>/<hash>`，
     * dsh 再按 `join(dirname(pkgPath), "./lib/client.cjs")` 拼客户端包路径，
     * 得到 `<store>/files/<xx>/lib/client.cjs` —— CAS 里只有扁平的哈希文件，
     * 没有 lib/ 目录，必然 ENOENT。真机表现是任何带 `exports["./client"]` 的插件
     * 装完就让 `dsh web` 起不来（MissingClientBundleError）。
     *
     * 选 copy 而不是设法让硬链接可用：真机探测报的是
     * `AccessDeniedException`（系统不允许在 App 私有目录建硬链接），不在 App 能改的
     * 范围内。copy 绕开整个链接语义，代价是 CAS 去重失效、rootfs 变大。
     *
     * **写 pnpm-workspace.yaml 而不是 .npmrc**：pnpm 11 起这类 pnpm 专有设置已从
     * `.npmrc` 迁到 `pnpm-workspace.yaml`。实测 `pnpm config get package-import-method`
     * 对 .npmrc 里的同名项返回 undefined，而 workspace 里的 `packageImportMethod`
     * 返回 copy（.npmrc 本身没失效 —— 同文件里的 registry= 照样生效）。v1.3 写
     * .npmrc 那一版因此完全没生效。
     *
     * **只在文件已存在时追加**：dsh 的 `initProfile` 见到文件存在就不写自己的模板
     * （dsh-app-boot 里 `if (!existsSync(workspacePath))`），抢先创建会把
     * `nodeLinker: hoisted` 与 `autoInstallPeers: false` 弄丢。profile 尚未初始化时
     * 靠安装命令上的 `--package-import-method copy` 兜住那一次。
     *
     * 用户显式写过这一项时**不覆盖** —— 显式配置优先于我们的推断。
     */
    internal fun ensureProfilePnpmSettings() {
        runCatching { removeLegacyNpmrcImportLine() }
        if (!linkBecomesSymlink()) return
        runCatching {
            val ws = File(DshEnv.rootfs(K.appContext), "$PROFILE_GUEST_REL/pnpm-workspace.yaml")
            // 不存在就不建：见 KDoc，抢在 dsh initProfile 之前会弄丢它的模板
            if (!ws.isFile) return@runCatching
            val old = ws.readText(StandardCharsets.UTF_8)
            if (old.lineSequence().any { it.trimStart().startsWith(PNPM_IMPORT_KEY) }) return@runCatching
            val sep = if (old.isEmpty() || old.endsWith("\n")) "" else "\n"
            // 追加顶层键而不是解析重写整个 YAML：这文件里还有 minimumReleaseAgeExclude、
            // allowBuilds 等结构化内容，字符串追加最不容易弄坏别人的配置
            ws.writeText(old + sep + PNPM_IMPORT_LINE + "\n", StandardCharsets.UTF_8)
            android.util.Log.i(DshRuntime.TAG, "已写入 profile pnpm 设置: $PNPM_IMPORT_LINE")
        }
    }

    /**
     * 清掉 v1.3 写进 `/root/.npmrc` 的那行 —— 它已知无效（pnpm 11 不读），
     * 留着只会在排查时误导。只删精确匹配的那一行，用户自己写的其它行一律不碰；
     * 文件因此变空就把文件删掉。
     */
    internal fun removeLegacyNpmrcImportLine() {
        val rc = File(DshEnv.rootfs(K.appContext), "root/.npmrc")
        if (!rc.isFile) return
        val old = rc.readText(StandardCharsets.UTF_8)
        if (!old.contains(NPMRC_LEGACY_LINE)) return
        val kept = old.lineSequence().filter { it.trim() != NPMRC_LEGACY_LINE }.toList()
        if (kept.all { it.isBlank() }) {
            rc.delete()
        } else {
            rc.writeText(kept.joinToString("\n").trimEnd() + "\n", StandardCharsets.UTF_8)
        }
        android.util.Log.i(DshRuntime.TAG, "已清理无效的 npmrc 行: $NPMRC_LEGACY_LINE")
    }

    /**
     * pnpm 实际读到的导入方式，供启动日志用。
     *
     * 读文件而不是回答「我们写过没有」：v1.3 的教训正是日志宣称了一件没生效的事。
     */
    internal fun pnpmImportMethodLine(): String {
        val ws = File(DshEnv.rootfs(K.appContext), "$PROFILE_GUEST_REL/pnpm-workspace.yaml")
        if (!ws.isFile) return DshLogging.str(CoreR.string.dsh_log_pnpm_unconfigured)
        val line = runCatching {
            ws.readLines().firstOrNull { it.trimStart().startsWith(PNPM_IMPORT_KEY) }
        }.getOrNull()
            ?: return DshLogging.str(CoreR.string.dsh_log_pnpm_default)
        val value = line.substringAfter(':', "").trim().ifEmpty { "?" }
        return DshLogging.str(CoreR.string.dsh_log_pnpm_from_profile, value)
    }

    // ── 给 DshRuntime 内部调用的包装 ──
    // 这些函数原本是 DshRuntime 的 private 成员，迁到这里后 DshRuntime 内部
    // 还有调用点，所以暴露 internal 包装（参数只多一个 caller 位置占位）。

    internal fun setupResolvConfFor(caller: Any) = setupResolvConf()

    internal fun ensureContainerDnsFor(caller: Any) = ensureContainerDns()

    internal fun ensureContainerGroupsFor(caller: Any) = ensureContainerGroups()

    internal fun ensureProfilePnpmSettingsFor(caller: Any) = ensureProfilePnpmSettings()

    internal fun removeLegacyNpmrcImportLineFor(caller: Any) = removeLegacyNpmrcImportLine()

    internal fun pnpmImportMethodLineFor(caller: Any): String = pnpmImportMethodLine()

    internal fun pnpmInstalledFor(caller: Any): Boolean = pnpmInstalled()

    internal fun ensureFsBridgeCliFor(caller: Any) = ensureFsBridgeCli()

    internal fun startFsBridgeFor(caller: Any) = startFsBridge()
}
