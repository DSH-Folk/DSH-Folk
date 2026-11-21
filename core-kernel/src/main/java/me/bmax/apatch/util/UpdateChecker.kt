package me.bmax.apatch.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import me.bmax.apatch.dsh.KernelEnv
import me.bmax.apatch.dsh.compareVersions
import me.bmax.apatch.dsh.DshSource
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用自身的更新检查。
 *
 * FolkPatch 原来查的是它自己的服务端（folk.mysqil.com/api/version，返回裸 versionCode）。
 * DSH-Folk 没有服务端，改为直接读 GitHub 的 latest release：
 *  - tag 形如 `v1.6` / `1.6`，与 `versionName` 比较；
 *  - 版本号比较走 [compareVersions]（含预发布标识）；
 *  - 匿名 GitHub API 限流 60/h，所以沿用 FolkApiClient 的 TTL 缓存。
 *
 * 必须先验 tag 长得像版本号：同一个仓库里还有 `runtime-latest` 这个滚动 tag（容器
 * 运行时的发布位），仓库里没有正式版本发布时 `releases/latest` 返回的就是它 ——
 * 而 compareVersions("runtime-latest", "1.6") > 0（首段不是数字，按字典序比），
 * 于是每次启动都弹「有新版本」。
 *
 * **结果必须区分「没有更新」与「查不到」**：原来 [checkUpdate] 一律返回 Boolean，
 * 任何失败（限流 403、断网、DNS 失败）都落成 false，界面报「您已是最新版本」——
 * 用户看到的是一句肯定的错误结论，而不是「检查失败」。匿名配额只有 60/h，而插件
 * 商店那边也在打 GitHub（star / 目录），配额烧完这条路就静默失效了。
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val LATEST_PATH = "/repos/DSH-Folk/DSH-Folk/releases/latest"
    /**
     * 列表路径。
     *
     * 取 30 条而不是 10：开了测试版通道之后，每次 Actions 构建都会多一个 prerelease，
     * 十条很快就全是测试版 —— 那时**关着**测试版的用户在这一页里找不到任何正式版。
     * （`releases/latest` 本身会跳过 prerelease，所以正式版通道主要靠它；这一条是它
     * 拿不到时的退路，退路也必须管用。）
     */
    private const val LIST_PATH = "/repos/DSH-Folk/DSH-Folk/releases?per_page=30"
    private const val API_BASE = "https://api.github.com"
    private const val RELEASES_URL = "https://github.com/DSH-Folk/DSH-Folk/releases"

    /**
     * 直连之外的备用入口。
     *
     * 已实测 gh-proxy 能代理 api.github.com（返回同样的 JSON），而它不共享 GitHub
     * 对本机 IP 的匿名配额 —— 直连被限流时正好接得上。
     */
    private val API_MIRRORS = listOf(
        "https://v6.gh-proxy.org/https://api.github.com",
        "https://gh-proxy.com/https://api.github.com",
        "https://ghproxy.net/https://api.github.com",
        "https://github.moeyy.xyz/https://api.github.com",
    )

    /**
     * 「接受测试版更新」的旧 prefs 键。
     *
     * **已弃用**，只作迁移来源保留（见 [UpdateChannel.read]）。新代码不要读它 ——
     * 通道现在由 [UpdateChannel] 表达，布尔表达不了三态。留这个常量是为了让还没
     * 迁移完的调用方继续能编译，而不是鼓励继续用。
     */
    @Deprecated("用 UpdateChannel.read(context) 取通道")
    const val KEY_ACCEPT_BETA = UpdateChannel.LEGACY_KEY_ACCEPT_BETA

    /** 版本 tag：可选 v 前缀 + 至少两段数字 + 可选预发布后缀。 */
    private val VERSION_TAG = Regex("""^[vV]?\d+(\.\d+)+([-+].*)?$""")

    /** 强制过渡版本的正文标记（独占一行）。见 [isMandatoryBridge]。 */
    private const val BRIDGE_MARKER = "[mandatory-bridge]"

    /** 检查结果。[failure] 只在真的查不到时非空，不能与「已是最新」混为一谈。 */
    data class Status(
        val hasUpdate: Boolean,
        val latestTag: String = "",
        /** APK 资产的下载地址；空表示这次没找到可直装的包，UI 应退回浏览器。 */
        val apkUrl: String = "",
        val apkName: String = "",
        val apkSize: Long = 0,
        /** 期望的 sha256（来自同名 .sha256 资产）；空表示无法校验。 */
        val sha256: String = "",
        /** release 正文，给对话框显示更新内容。 */
        val notes: String = "",
        /**
         * 这次找到的是不是测试版。
         *
         * 必须让界面知道：测试版和正式版的更新提示长得一样，而用户按下「更新」之后装上的
         * 东西风险完全不同。不标出来就等于偷偷把人推上测试通道。
         */
        val isPrerelease: Boolean = false,
        /**
         * 这一版是不是「强制过渡版本」。
         *
         * 见 [isMandatoryBridge]：某些版本带了底层数据迁移，跳过它直接装更高版本会让
         * 本地数据停在旧结构上（新代码读不懂）。这时 UI 必须禁用「跳过此版本」之类的
         * 入口，用户只能先升到它。
         */
        val isMandatoryBridge: Boolean = false,
        val failure: String? = null,
    ) {
        /** 能不能走应用内更新：要有包，也要有校验值 —— 不校验就装是不可接受的。 */
        val canInstallInApp: Boolean get() = apkUrl.isNotEmpty() && sha256.isNotEmpty()
    }

    /**
     * 用户主动检查前取消**应用更新**遗留的网络请求，再从头发起一次。
     *
     * ## 为什么不能无条件 cancelInFlightRequests()
     *
     * `cancelInFlightRequests()` 取消的是 `FolkApiClient` 里**全部**在途请求，不区分是谁发的。
     * 而冷启动时 `MainActivity` 会并行跑两条自动检查：应用更新 + **运行时更新**
     * （`DshRuntime.checkRuntimeUpdate()` 走同一个 `fetchApiJson`）。
     *
     * 于是「用户在冷启动后两秒内点了手动检查应用更新」会发生：
     *  1. 手动检查取消掉在途请求 → **运行时自动检查被连带取消**；
     *  2. 运行时那条 `runCatching{}.getOrNull()` 拿到 null，静默当作"没查到"；
     *  3. 用户永远看不到那个本来存在的运行时更新提示，而且没有任何错误可见。
     *
     * 这不会报错、不会崩，只会让"运行时更新提示时有时无" —— 最难查的那一类。
     *
     * 现在只取消**应用更新**这条链自己的请求（见 [FolkApiClient.cancelTagged]），
     * 运行时那条不受影响。用户点"检查应用更新"不该影响运行时更新。
     */
    suspend fun checkManually(channel: UpdateChannel = UpdateChannel.STABLE): Status {
        FolkApiClient.cancelTagged(TAG_APP_UPDATE)
        return check(channel)
    }

    /** 应用更新这条链在 [FolkApiClient] 里登记请求用的标签。 */
    const val TAG_APP_UPDATE = "app-update"

    /**
     * @param channel 更新通道。默认 [UpdateChannel.STABLE] —— 与历史行为一致
     *   （旧布尔键缺省 false 就是只收正式版）。取值见 [UpdateChannel.read]。
     */
    suspend fun check(channel: UpdateChannel = UpdateChannel.STABLE): Status = withContext(Dispatchers.IO) {
        var lastError: String? = null

        // 顺序取决于通道，这不是小事：`releases/latest` **定义上**跳过 prerelease，
        // 所以通道不是 Stable 时先问它，会拿到正式版、判定「已是最新」然后直接返回，
        // 列表根本没机会被看一眼 —— 开关看起来毫无作用。
        val paths = if (channel == UpdateChannel.STABLE) {
            listOf(LATEST_PATH, LIST_PATH)
        } else {
            listOf(LIST_PATH, LATEST_PATH)
        }

        for (path in paths) {
            val fetched = fetchFirstRelease(path, channel)
            if (fetched == null) {
                lastError = "all update sources failed for $path"
                continue
            }
            val (base, release) = fetched
            val tag = release.optString("tag_name").trim()
            Log.d(TAG, "remote=$tag local=${KernelEnv.appVersionName} via $base")
            val newer = compareVersions(tag, KernelEnv.appVersionName) > 0
            if (!newer) return@withContext Status(hasUpdate = false, latestTag = tag)

            val asset = pickApkAsset(release)
            return@withContext Status(
                hasUpdate = true,
                latestTag = tag,
                apkUrl = asset?.url.orEmpty(),
                apkName = asset?.name.orEmpty(),
                apkSize = asset?.size ?: 0L,
                sha256 = asset?.let { fetchSha256(it.shaUrl) }.orEmpty(),
                notes = release.optString("body").trim(),
                isPrerelease = isBeta(release, tag),
                isMandatoryBridge = isMandatoryBridge(release, tag),
            )
        }

        // 走到这里 = 每条路都没拿到可用的版本 tag
        Status(hasUpdate = false, failure = lastError ?: "no release found")
    }

    /**
     * 竞速拉取一个 GitHub API 路径，返回第一个成功的响应体（都不行时 null）。
     *
     * 给运行时版本列表用：它要点名列出所有 release，而列表路径在 `check()` 里已被
     * 解析成「最新的那一个」，拿不到全量。入口集合与 [fetchFirstRelease] 完全一致 ——
     * 直连被匿名限流时 gh-proxy 那几条镜像接得上。
     */
    suspend fun fetchApiJson(path: String): String? = supervisorScope {
        val pending = (listOf(API_BASE) + API_MIRRORS).map { base ->
            async {
                FolkApiClient.fetchJson(
                    base + path,
                    ttlMs = 10 * 60 * 1000L,
                    maxRetries = 1,
                    forceRefresh = true,
                    tag = TAG_APP_UPDATE,
                ).getOrNull()
            }
        }.toMutableList()

        while (pending.isNotEmpty()) {
            val completed = select<Pair<kotlinx.coroutines.Deferred<String?>, String?>> {
                pending.forEach { candidate -> candidate.onAwait { candidate to it } }
            }
            pending.remove(completed.first)
            val body = completed.second
            if (!body.isNullOrBlank()) {
                pending.forEach { it.cancel() }
                return@supervisorScope body
            }
        }
        null
    }

    private suspend fun fetchFirstRelease(path: String, channel: UpdateChannel): Pair<String, JSONObject>? =
        supervisorScope {
            val pending = (listOf(API_BASE) + API_MIRRORS).map { base ->
                base to async {
                    FolkApiClient.fetchJson(
                        base + path,
                        ttlMs = 30 * 60 * 1000L,
                        maxRetries = 1,
                        forceRefresh = true,
                        tag = TAG_APP_UPDATE,
                    ).getOrNull()?.let { body ->
                        runCatching { newestVersionRelease(body, channel) }.getOrNull()
                    }
                }
            }.toMutableList()

            while (pending.isNotEmpty()) {
                val completed = select<Pair<Pair<String, kotlinx.coroutines.Deferred<JSONObject?>>, JSONObject?>> {
                    pending.forEach { candidate ->
                        candidate.second.onAwait { candidate to it }
                    }
                }
                pending.remove(completed.first)
                val release = completed.second
                if (release != null && release.optString("tag_name").isNotBlank()) {
                    pending.forEach { it.second.cancel() }
                    return@supervisorScope completed.first.first to release
                }
            }
            null
        }

    private data class ApkAsset(
        val name: String,
        val url: String,
        val size: Long,
        /** 同名 + `.sha256` 的资产地址；空 = 这个 release 没带校验文件。 */
        val shaUrl: String,
    )

    /**
     * 从 release 的 assets 里挑**本机架构**的 APK，并配对它的 `.sha256`。
     *
     * 1.7.6 起按 ABI 拆包，一个 release 里同时有 `…-arm64-v8a.apk` 与 `…-x86_64.apk`。
     * 原来「取第一个 .apk」会把 arm64 包发给 x86_64 用户（装上也起不了容器），所以
     * 这里按 [DshSource.runtimeArch] 精确匹配文件名里的 ABI。
     *
     * 三种情形：
     * - 有本机 ABI 的包 → 用它；
     * - 整个 release 里**没有任何**带 ABI 后缀的包 → 那是 1.7.5 及更早的单包 release，
     *   回退到「第一个 .apk」，与旧行为一致；
     * - 只有别的架构的包 → 返回 null，让 UI 退回浏览器下载。**绝不**退而求其次发一个
     *   装不上/跑不起来的包。
     *
     * sha256 仍严格按「同名 + .sha256」配对，不去猜别的命名：配错了校验值等于没校验，
     * 而校验失败会阻止安装 —— 宁可退回浏览器下载。
     */
    private fun pickApkAsset(release: JSONObject): ApkAsset? {
        val assets = release.optJSONArray("assets") ?: return null
        val byName = HashMap<String, JSONObject>()
        val apks = ArrayList<JSONObject>()
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            byName[name] = a
            if (name.endsWith(".apk", ignoreCase = true)) apks.add(a)
        }
        if (apks.isEmpty()) return null

        val arch = DshSource.runtimeArch()
        val abiTagged = apks.filter { hasAbiTag(it.optString("name")) }
        val a = when {
            // 本机架构的包
            abiTagged.any { it.optString("name").contains(arch, ignoreCase = true) } ->
                abiTagged.first { it.optString("name").contains(arch, ignoreCase = true) }
            // 旧 release（没有任何 ABI 后缀）：单包，沿用旧行为
            abiTagged.isEmpty() -> apks.first()
            // 只有别的架构 → 不发错包
            else -> return null
        }

        val name = a.optString("name")
        return ApkAsset(
            name = name,
            url = a.optString("browser_download_url"),
            size = a.optLong("size", 0L),
            shaUrl = byName["$name.sha256"]?.optString("browser_download_url").orEmpty(),
        )
    }

    /** 文件名里是否带我们已知的 ABI 标记（用来区分拆包 release 与旧的单包 release）。 */
    private fun hasAbiTag(name: String): Boolean =
        KNOWN_ABIS.any { name.contains(it, ignoreCase = true) }

    /** 拆包会出现在资产名里的 ABI。只列本项目实际发布的两个。 */
    private val KNOWN_ABIS = listOf("arm64-v8a", "x86_64")

    /**
     * 拉 `.sha256` 文件并取出十六进制摘要。
     *
     * 内容形如 `<hex>  <filename>`（sha256sum 的输出），所以取第一段。
     * 不是 64 位十六进制就返回空 —— 宁可让 UI 退回浏览器，也不拿一个可疑的
     * 期望值去比对（那等于没校验）。
     *
     * 直连 `github.com/releases/download/…` 会 302 到
     * `release-assets.githubusercontent.com`（Azure blob），国内直连经常超时；
     * 而 APK 下载那边（[AppUpdater]）走的是 gh-proxy 镜像。校验值必须跟着走
     * 同一批镜像，否则「包下得来、校验值拿不到」照样被 [Status.canInstallInApp]
     * 判成不能应用内更新。每个候选只试一次（串行、命中即返回），控制总超时。
     */
    private suspend fun fetchSha256(url: String): String {
        if (url.isEmpty()) return ""
        // 校验值必须跟 APK 走同一批候选：原来只写死 v6 + axisnow 两条（新增线路永远排不进去，
        // 线路一挂这里就先失败）。竞速关掉时只取原址，与「未勾选＝直连」一致。
        val candidates = if (AppUpdater.raceEnabled()) {
            DshSource.proxyCandidates(url)
        } else {
            listOf(url)
        }
        for (c in candidates) {
            val body = FolkApiClient.fetchJson(
                c,
                ttlMs = 30 * 60 * 1000L,
                maxRetries = 0,
                tag = TAG_APP_UPDATE,
            ).getOrNull().orEmpty()
            val hex = body.trim().substringBefore(' ').trim()
            if (hex.length == 64 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                return hex.lowercase()
            }
        }
        return ""
    }

    /**
     * 从 latest 对象或 releases 数组里挑出**当前通道可见**的最新版本。
     *
     * 数组按发布时间倒序，但不能只取第一个：`runtime-latest` 也在同一个列表里。
     *
     * ## 通道过滤是「按档位」而不是「按后缀精确匹配」
     *
     * 早期的写法是「不接受测试版 → 把带 `-` 后缀的全排除」。加了 Alpha 之后不能这么做了：
     * 若按后缀精确匹配，Alpha 通道用户会**看不到正式版**（后缀是 alpha，正式版没有后缀，
     * 匹配不上）。正确语义是「通道是一个上限，档位不高于上限的都要」—— 由
     * [UpdateChannel.allows] 回答。Stable 通道的上限是 Stable，于是 beta/alpha 全被挡掉，
     * 与旧行为完全一致。
     *
     * ## 两道判断都要（对 Beta 及以下）
     *
     * 判断「这算不算 beta」时，`prerelease` 标记与 tag 后缀**都要看**：
     *  - `prerelease` —— 正常发布流程会设它；
     *  - tag 里的后缀（`1.8.1-beta.3` 的 `-beta.3`）—— 万一发布时忘了勾那个复选框，
     *    仍然不会把测试版推给正式通道的用户。漏一道的代价是所有人都被推上测试版。
     */
    private fun newestVersionRelease(body: String, channel: UpdateChannel): JSONObject? {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            var best: JSONObject? = null
            var bestTag = ""
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("draft")) continue
                val tag = o.optString("tag_name").trim()
                if (!VERSION_TAG.matches(tag)) continue
                if (!channel.allows(channelOf(o, tag))) continue
                if (bestTag.isEmpty() || compareVersions(tag, bestTag) > 0) {
                    best = o
                    bestTag = tag
                }
            }
            return pickWithMandatoryBridge(arr, best, channel)
        }
        val o = JSONObject(trimmed)
        val tag = o.optString("tag_name").trim()
        if (!VERSION_TAG.matches(tag)) return null
        if (!channel.allows(channelOf(o, tag))) return null
        return o
    }

    /**
     * 这个 release 属于哪一档通道。
     *
     * 判定顺序是「从最激进往下退」，因为后缀可能同时像 alpha 又像 beta 的写法不存在，
     * 但**未标记**的 pre-release 必须有个归属：`prerelease=true` 但后缀认不出来的，
     * 按 Beta 处理（保守：不至于让 Alpha 用户看不到它，也不会推给 Stable 用户）。
     */
    private fun channelOf(release: JSONObject, tag: String): UpdateChannel {
        val pre = tag.substringAfter('-', "").lowercase()
        if (pre.isEmpty()) {
            // 没有后缀 = 正式版。但若 prerelease 标记为 true 而后缀为空，说明发布流程
            // 没按约定打 tag：宁可当成 beta，也不要让 Stable 用户收到一个未标记的预发布。
            return if (release.optBoolean("prerelease")) UpdateChannel.BETA else UpdateChannel.STABLE
        }
        if (pre.startsWith("alpha")) return UpdateChannel.ALPHA
        // beta 及一切认不出来的后缀都归 Beta。
        return UpdateChannel.BETA
    }

    /**
     * 强制过渡版本：在候选里**优先**挑它。
     *
     * ## 为什么需要这个东西
     *
     * 有些版本带了底层数据迁移（本地存储结构变了）。用户若从很旧的版本直接跳到最新的，
     * 中间那次迁移就被跳过了 —— 新代码读到的还是旧结构的数据，症状是"数据看起来还在
     * 但功能全乱"。强制过渡版本就是那条"必经之路"：必须先装上它，把数据搬过去，
     * 之后才能继续往上升。
     *
     * ## 怎么标记
     *
     * 约定写在 **release 正文**里，不用 tag 后缀：tag 要参与 semver 比较，往里塞标记
     * 会让版本号本身变得难解析，而且 tag 一旦发出就不能改。正文标记可以在发布后修正。
     *
     * 形如（大小写不敏感，独占一行）：
     * ```
     * [mandatory-bridge]
     * ```
     *
     * ## 为什么是"挑它"而不是"只允许它"
     *
     * 若本地版本已经 >= 该过渡版本，说明迁移已经做过了，此时它就是普通版本、不该再拦。
     * 判定放在这里只负责"把候选换成它"，"要不要强制"由 [Status.isMandatoryBridge]
     * 交给 UI —— 检查逻辑不该替 UI 决定按钮能不能点。
     */
    private fun pickWithMandatoryBridge(
        arr: JSONArray,
        best: JSONObject?,
        channel: UpdateChannel,
    ): JSONObject? {
        if (best == null) return null
        val bestTag = best.optString("tag_name").trim()
        val local = KernelEnv.appVersionName
        // 候选里所有"比本地新、且属于强制过渡"的版本，取**最低**的那个：
        // 用户必须先过最早的那道关。更高的过渡版本等他过了这道关之后自然会成为新的候选。
        var bridge: JSONObject? = null
        var bridgeTag = ""
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("draft")) continue
            val tag = o.optString("tag_name").trim()
            if (!VERSION_TAG.matches(tag)) continue
            if (!channel.allows(channelOf(o, tag))) continue
            if (!isMandatoryBridge(o, tag)) continue
            // 已经不比本地新了 → 迁移早就做过，不再是"必经"
            if (compareVersions(tag, local) <= 0) continue
            if (bridgeTag.isEmpty() || compareVersions(tag, bridgeTag) < 0) {
                bridge = o
                bridgeTag = tag
            }
        }
        // 没有待过的关，或者最新版本身就是那道关 → 直接用 best。
        if (bridge == null) return best
        // 若 best 比 bridge 更高，说明中间跳过了这道关：改推 bridge。
        return if (compareVersions(bridgeTag, bestTag) < 0) bridge else best
    }

    /**
     * 这个 release 是否声明为强制过渡版本。
     *
     * 只认**独占一行**的标记，避免正文里随口提到这个词就误判 —— 比如更新说明里写
     * "这版不是 mandatory-bridge"，子串匹配会把它当成真的标记。
     */
    private fun isMandatoryBridge(release: JSONObject, tag: String): Boolean {
        val body = release.optString("body")
        if (body.isBlank()) return false
        return body.lineSequence().any { it.trim().equals(BRIDGE_MARKER, ignoreCase = true) }
    }

    /** 这个 release 是不是测试版：`prerelease` 标记，或 tag 带预发布后缀。 */
    private fun isBeta(release: JSONObject, tag: String): Boolean =
        release.optBoolean("prerelease") || tag.substringAfter('-', "").isNotEmpty()

    fun openUpdateUrl(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open releases page", e)
        }
    }
}
