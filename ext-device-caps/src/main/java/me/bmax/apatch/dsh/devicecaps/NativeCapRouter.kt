package me.bmax.apatch.dsh.devicecaps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.Locale
import me.bmax.apatch.dsh.Access
import me.bmax.apatch.dsh.Cap
import me.bmax.apatch.dsh.NativeBridgeUtil
import me.bmax.apatch.dsh.OsGap
import me.bmax.apatch.dsh.PermissionManager
import me.bmax.apatch.dsh.PrivRisk
import me.bmax.apatch.dsh.Special
import me.bmax.apatch.util.appString
import me.bmax.apatch.res.R as CoreR
import org.json.JSONArray
import org.json.JSONObject
import me.bmax.apatch.dsh.DshNotificationListener
import me.bmax.apatch.dsh.appLaunchIntent
import me.bmax.apatch.dsh.DshElevationRequests
import me.bmax.apatch.dsh.DshHostPrompt
import me.bmax.apatch.dsh.PrivPolicy
import me.bmax.apatch.dsh.PrivilegedShell
import me.bmax.apatch.util.PermissionUtils
import me.bmax.apatch.dsh.showToast
import me.bmax.apatch.dsh.DshEnv

/**
 * 各能力的具体实现：通知、toast、振动、剪贴板、分享、拨号、设备信息，
 * 以及前置的权限档校验与审计。
 *
 * ## 为什么从 DshNativeBridge 抽出来
 *
 * 桥的 handle() 只该做两件事：解析请求、按 path 分派。实际的「怎么发通知」
 * 「怎么读剪贴板」与分派无关，却占了 700 多行 —— 抽出来后桥从 2637 行降到 1948 行，
 * 而每个能力可以独立演进、独立测试。
 *
 * ## 权限档校验也在这里
 *
 * neededAccess / levelCovers / insufficient / osGap 是所有能力共用的前置判定。
 * 放这里而不是留在桥里，是因为它们与「能力实现」是同一件事的两面：
 * 一个回答「能不能做」，一个回答「怎么做」。
 */
object NativeCapRouter {

    class Once(val access: Access, val expiresAtMs: Long)


    val onceStore = ConcurrentHashMap<Cap, Once>()


    const val ACCESS_PREFIX = "native_bridge_access_"


    fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    const val NOTIFICATION_ID_BASE = 2000
    const val MAX_NOTIFICATION_SLOT = 999
    const val CHANNEL_ID = "dsh_agent"
    const val FULL_SCREEN_CHANNEL_ID = "dsh_agent_urgent"
    const val AUDIT_FILE_MAX_BYTES = 1_048_576L
    const val AUDIT_RESULT_MAX_CHARS = 2000
    const val MAX_COMMAND_CHARS = 2000
    const val ONCE_TTL_MS = 180_000L


    /**
     * 这次调用至少需要哪一档。
     *
     * 取**够用的最低档**：多要一档就等于多问用户一次，而用户对「读通知」和「全权控制通知」的
     * 感受完全不同。档位从 [accessOptions] 里挑，所以只会给出这台设备上合法的那几档。
     */
    fun neededAccess(cap: Cap, method: String, path: String, params: Map<String, String>): Access {
        val options = accessOptions(cap)
        val fits: (Access) -> Boolean = when {
            path == "/native/notify/system" -> { a -> a == Access.CONTROL }
            isWriteRequest(method, path, params) -> { a -> accessNeedsWrite(a) }
            else -> { a -> accessNeedsRead(a) }
        }
        return options.firstOrNull { it != Access.OFF && fits(it) } ?: options.last()
    }

    /**
     * [have] 这一档能不能覆盖 [need] 那一档要做的动作。
     *
     * **不能**用 [accessOptions] 里的下标比大小：那张表是按「侵入性」排的，不是按能力包含关系 ——
     * 通知那一项是 OFF < write < read < read_write < control，而 read 并不包含 write。按下标比会
     * 得出「已经有 read 了，write 就不用问」的结论，然后被后面的写闸门 403 掉，用户连被问一句的
     * 机会都没有。这里比的是动作：控制权要 control，写要 write 系，读要 read 系。
     */
    fun levelCovers(have: Access, need: Access): Boolean = when {
        need == Access.CONTROL -> have == Access.CONTROL
        accessNeedsWrite(need) -> accessNeedsWrite(have)
        else -> accessNeedsRead(have)
    }

    /** 档位够不够：够了返回 null，不够返回需要的那一档。 */
    fun insufficient(ctx: Context, cap: Cap, method: String, path: String, params: Map<String, String>): Access? {
        val need = neededAccess(cap, method, path, params)
        return if (levelCovers(effectiveAccess(ctx, cap), need)) null else need
    }

    /** Android 层缺的东西：给人看的名字 + 需要跳系统页时的动作。 */
    private data class OsGap(val labels: List<String>, val settingsAction: String?)

    /**
     * Android 层还缺哪些权限。
     *
     * 判据用 [permissionSatisfied]（它知道「媒体给一类就够」「大致位置也算」这些例外），所以
     * 不会因为可选权限而反复弹窗；返回 null 表示不缺。
     */
    fun osGap(ctx: Context, cap: Cap, access: Access): OsGap? {
        if (permissionSatisfied(ctx, cap, access)) return null
        val labels = mutableListOf<String>()
        val special = specialPermissionOf(cap, access)
        if (special != null && !specialGranted(ctx, special)) labels += specialLabel(ctx, special)
        for (permission in runtimePermissions(ctx, cap, access)) {
            val granted = ContextCompat.checkSelfPermission(ctx, permission) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) labels += permissionLabel(ctx, permission)
        }
        if (labels.isEmpty()) return null
        return OsGap(labels.distinct(), special?.action)
    }

    /**
     * 弹第二段并阻塞等结果；返回 true 表示复查通过、可以执行了。
     *
     * 不在前台时直接返回 false：这一段需要用户看着屏幕操作（可能还要去系统设置页）。
     */
    fun awaitOsGap(ctx: Context, cap: Cap, access: Access, gap: OsGap): Boolean {
        if (!NativeBridgeUtil.isForeground(ctx)) return false
        val request = DshElevationRequests.askOs(cap, access, gap.labels, gap.settingsAction)
            ?: return false
        return DshElevationRequests.awaitOs(request.id) == DshElevationRequests.OsOutcome.GRANTED
    }

    /** 特殊权限的短名。 */
    fun specialLabel(ctx: Context, special: Special): String = str(
        ctx,
        when (special) {
            Special.WRITE_SETTINGS -> CoreR.string.dsh_native_perm_label_write_settings
            Special.NOTIFICATION_POLICY -> CoreR.string.dsh_native_perm_label_dnd
            Special.REQUEST_INSTALL -> CoreR.string.dsh_native_perm_label_install
            Special.FULL_SCREEN_INTENT -> CoreR.string.dsh_native_perm_label_full_screen
            Special.NOTIFICATION_ACCESS -> CoreR.string.dsh_native_perm_label_notification_access
            Special.USAGE_STATS -> CoreR.string.dsh_native_perm_label_usage
            Special.A11Y_SERVICE -> CoreR.string.dsh_native_perm_label_a11y
        },
    )

    /** 运行时权限的短名；不认识的就用权限名的最后一段，别把整串包名塞给用户。 */
    fun permissionLabel(ctx: Context, permission: String): String {
        val known = when (permission) {
            android.Manifest.permission.POST_NOTIFICATIONS -> CoreR.string.dsh_native_perm_label_notifications
            android.Manifest.permission.CAMERA -> CoreR.string.dsh_native_perm_label_camera
            android.Manifest.permission.RECORD_AUDIO -> CoreR.string.dsh_native_perm_label_microphone
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
            -> CoreR.string.dsh_native_perm_label_contacts
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
            -> CoreR.string.dsh_native_perm_label_calendar
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            -> CoreR.string.dsh_native_perm_label_location
            android.Manifest.permission.READ_PHONE_STATE -> CoreR.string.dsh_native_perm_label_phone
            android.Manifest.permission.BODY_SENSORS -> CoreR.string.dsh_native_perm_label_sensors
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.RECEIVE_SMS,
            -> CoreR.string.dsh_native_perm_label_sms
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            -> CoreR.string.dsh_native_perm_label_media
            else -> null
        }
        return known?.let { NativeBridgeUtil.str(ctx, it) } ?: permission.substringAfterLast('.')
    }

    /** 取一个可选的长文本参数：去空白、限长、空串按「没给」处理。 */
    fun bounded(raw: String?): String? =
        NativeBridgeUtil.text(raw)?.trim()?.take(MAX_COMMAND_CHARS)?.takeIf { it.isNotBlank() }

    fun requestElevation(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!enabled(ctx)) return 403 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_disabled), "disabled")
        if (!NativeBridgeUtil.isForeground(ctx)) {
            return 409 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_elevate_foreground), "not_foreground")
        }
        val cap = Cap.entries.firstOrNull { it.id == params["cap"] }
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_bad_cap), "bad_cap")
        val requested = Access.entries.firstOrNull { it.id == params["access"] }
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_bad_access), "bad_access")
        if (requested !in accessOptions(cap) || requested == Access.OFF) {
            return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_bad_access), "bad_access")
        }
        val current = access(ctx, cap)
        if (levelCovers(current, requested)) {
            return 200 to JSONObject()
                .put("ok", true)
                .put("status", "already_granted")
                .put("access", current.id)
                .put("note", "This level is already granted in settings; make the call directly.")
                .toString()
        }
        // 已经有一个还没用掉的「仅本次」配额能覆盖这次申请：说明用户刚同意过同一件事，
        // 别再弹一次窗问他 —— 让他重跑那条命令即可。
        val onceNow = onceAccess(cap)
        if (onceNow != null && levelCovers(onceNow, requested)) {
            return 200 to JSONObject()
                .put("ok", true)
                .put("status", "already_granted_once")
                .put("access", onceNow.id)
                .put("once", true)
                .put("note", "The user already allowed this once; no new dialog was opened. Make that one call now.")
                .toString()
        }
        val reason = NativeBridgeUtil.text(params["reason"])
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_reason_required), "reason_required")
        // agent 可以附上「获准后要跑的那条命令」，弹窗原文照显给用户看。命令过长就截断而不是
        // 报错：看到一截命令，用户的判断力也远好过看到一个 400 后什么也没有。
        val command = bounded(params["command"])
        val invocation = bounded(params["invocation"])
        val request = DshElevationRequests.submit(cap, requested, reason, command, invocation)
            ?: return 409 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_elevate_busy), "request_pending")
        // 事实文件跟着变：提示词知道「已经有一份申请在等用户」，agent 就不会再提一份
        runCatching { DshHostPrompt.writeFacts(ctx.applicationContext) }
        // 阻塞等用户答复。以前这里立刻回 202，让 agent 自己过一会儿再查 pending —— 那等于把
        // 一次问答拆成三次往返，中间还要 agent 记得回来问。现在这就是一次普通的阻塞调用。
        val decision = DshElevationRequests.awaitDecision(request.id)
        val base = JSONObject()
            .put("ok", decision == DshElevationRequests.Decision.ALLOWED ||
                decision == DshElevationRequests.Decision.ONCE)
            .put("status", decision.id)
            .put("cap", cap.id)
            .put("access", requested.id)
            .put("expiresInMs", 0)
        val response = when (decision) {
            DshElevationRequests.Decision.ALLOWED -> 200 to base
                .put("note", "The user allowed it. The level is saved; call the capability now.")
                .toString()

            // 用户也可能按「仅本次」：档位不落盘，但这一次（到 ONCE_TTL_MS 为止）必须放行 ——
            // 少了这个分支 Kotlin 的 when 不穷尽，直接编译不过（本地无 JDK，只有 CI 会报）。
            DshElevationRequests.Decision.ONCE -> 200 to base
                .put("expiresInMs", NativeBridgeUtil.ONCE_TTL_MS)
                .put(
                    "note",
                    "The user allowed this call only. The level was not saved, so expect to be " +
                        "asked again next time; call the capability now.",
                )
                .toString()

            DshElevationRequests.Decision.DENIED -> 403 to NativeBridgeUtil.err(
                NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_denied, capName(ctx, cap)),
                "denied_by_user",
            )

            DshElevationRequests.Decision.EXPIRED -> 403 to NativeBridgeUtil.err(
                NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_request_expired, capName(ctx, cap)),
                "request_expired",
            )
        }
        // 记的是**真正返回的那一份**。以前这里按申请结论另算一个状态码（拒绝 403、
        // 超时 408），而实际响应两者都是 403 —— 记录与响应体对不上，翻记录时看不出
        // 「拒绝」和「超时」的区别。现在两者都由响应体里的 reason 区分。
        audit(ctx, "POST", "/native/elevate", params, cap, reason, response)
        return response
    }

    /**
     * 写一条能力调用记录。
     *
     * [response] 是整个返回值（状态码 + 响应体），不是单独的状态码：记录里只留一个数字
     * 时，「这次调用到底返回了什么」只能回容器日志里翻，而那条日志早被后来的输出冲掉了。
     * 状态码仍单独存一份，界面按它上色，不必去解析响应体。
     */
    fun audit(
        ctx: Context,
        method: String,
        path: String,
        params: Map<String, String>,
        cap: Cap,
        reason: String,
        response: Pair<Int, String>,
        extra: JSONObject? = null,
    ) {
        runCatching {
            val dir = File(ctx.filesDir, "audit").apply { mkdirs() }
            val file = File(dir, "native-capability.jsonl")
            if (file.exists() && file.length() > AUDIT_FILE_MAX_BYTES) {
                File(dir, "native-capability.previous.jsonl").also { if (it.exists()) it.delete() }
                file.renameTo(File(dir, "native-capability.previous.jsonl"))
            }
            val entry = JSONObject()
                .put("time", Instant.now().toString())
                .put("method", method)
                .put("path", path)
                .put("command", auditCommand(method, path, params, reason, maskSensitive = true))
                .put("fullCommand", auditCommand(method, path, params, reason, maskSensitive = false))
                .put("capability", cap.id)
                .put("access", access(ctx, cap).id)
                // 实际生效的档位：含「仅本次」提升。持久档是 off、靠一次性授权放行的调用，
                // 只看 access 会记成 off，看不出「谁批准了这次」——审计的意义正在于此。
                .put("effectiveAccess", effectiveAccess(ctx, cap).id)
                .put("reason", reason)
                .put("status", response.first)
                .put("result", auditResult(response.second))
            if (extra != null) for (key in extra.keys()) entry.put(key, extra.get(key))
            FileWriter(file, true).use { it.append(entry.toString()).append('\n') }
        }.onFailure { Log.w(TAG, "记录能力调用失败: ${it.message}") }
    }

    /**
     * 响应体截断。列表类结果（联系人、通知、媒体）可以有几百 KB，而记录文件只留
     * [AUDIT_FILE_MAX_BYTES] —— 不截断的话一条调用就能把整份历史挤掉。
     */
    fun auditResult(body: String): String = if (body.length <= AUDIT_RESULT_MAX_CHARS) {
        body
    } else {
        body.take(AUDIT_RESULT_MAX_CHARS) + "\n…(已截断，完整长度 ${body.length})"
    }

    /**
     * 审计附加字段：这次**为什么**问、或者为什么没问。
     *
     * [restrictMode] 与 [decision] 对**每个**能力都写：新模型下「这条为什么没被拦」正是要复盘
     * 的问题（开关关着是全局默认，不在清单里是用户把它移出了清单，两者含义完全不同）。
     * 旧写法只给 shell 记，于是真机报告里 153 条 a11y 调用一条都答不出是问过还是静默放行。
     *
     * [channel] / [uid] 仍然只给 shell：它们描述的是**那条提权通道的身份**
     * （「凌晨三点那条 pm uninstall 是谁批的、以什么身份跑的」只能靠它们回答），
     * 对相机、无障碍这些不经过提权通道的能力没有意义。
     */
    fun privAuditExtra(ctx: Context, cap: Cap, decision: String): JSONObject {
        val entry = JSONObject()
            .put("restrictMode", PrivPolicy.restrictMode(ctx))
            .put("decision", decision)
        if (cap == Cap.SHELL) {
            val reach = PrivilegedShell.reach(ctx)
            entry
                .put("channel", reach?.channel?.name?.lowercase().orEmpty())
                .put("uid", reach?.uid ?: -1)
        }
        return entry
    }

    fun channelLabel(ctx: Context, channel: PermissionManager.Channel): String = str(
        ctx,
        when (channel) {
            PermissionManager.Channel.ROOT -> CoreR.string.dsh_perm_root
            PermissionManager.Channel.SHIZUKU -> CoreR.string.dsh_perm_shizuku
            PermissionManager.Channel.ADB -> CoreR.string.dsh_perm_adb
            PermissionManager.Channel.NONE -> CoreR.string.dsh_perm_none
        },
    )

    fun auditCommand(
        method: String,
        path: String,
        params: Map<String, String>,
        reason: String,
        maskSensitive: Boolean,
    ): String {
        fun masked(value: String): String = value.map { char ->
            if (char.isWhitespace()) char else '•'
        }.joinToString("")
        fun value(key: String, sensitive: Boolean = false): String = params[key].orEmpty().let {
            shellArg(if (sensitive && maskSensitive) masked(it) else it)
        }
        fun option(key: String, sensitive: Boolean = false): String? =
            params[key]?.let { "--$key ${value(key, sensitive)}" }

        val base = when (path) {
            "/native/elevate" -> "elevate ${value("cap")} ${value("access")}"
            "/native/notify" -> if (method == "DELETE") "notify-cancel" else listOfNotNull(
                "notify", value("title", true), params["body"]?.let { value("body", true) }
            ).joinToString(" ")
            "/native/notify/list" -> "notify-list"
            "/native/notify/system" -> listOfNotNull("notify-dismiss", params["key"]?.let { value("key", true) }).joinToString(" ")
            "/native/notify/full-screen" -> listOfNotNull(
                "notify-full-screen", value("title", true), params["body"]?.let { value("body", true) }
            ).joinToString(" ")
            "/native/toast" -> "toast ${value("text", true)}"
            "/native/vibrate" -> "vibrate"
            "/native/clipboard" -> if (method == "GET") "clip get" else "clip set ${value("text", true)}"
            "/native/share" -> "share ${value("text", true)}"
            "/native/open" -> "open ${value("url", true)}"
            "/native/device" -> "device"
            "/native/media/list" -> "media list"
            "/native/media/read" -> "media get ${value("id")}"
            "/native/mic/record" -> "mic record"
            "/native/mic/start" -> "mic start"
            "/native/mic/stop" -> "mic stop"
            "/native/camera/photo" -> "camera photo"
            "/native/tts/speak" -> "tts say ${value("text", true)}"
            "/native/tts/file" -> "tts file ${value("text", true)}"
            "/native/tts/voices" -> "tts voices"
            "/native/calendar/list" -> "calendar list"
            "/native/calendar/create" -> "calendar add ${value("title", true)}"
            "/native/contacts/list" -> "contacts list"
            "/native/location" -> "location"
            "/native/phone/info" -> "phone"
            "/native/sensors/list" -> "sensors list"
            "/native/sensors/read" -> "sensors read ${shellArg(params["id"] ?: params["sensor"].orEmpty())}"
            "/native/network" -> "network"
            "/native/volume" -> if (method == "GET") "volume" else "volume set ${value("percent")}"
            "/native/ringer" -> "ringer ${value("mode")}"
            "/native/settings" -> "settings"
            "/native/settings/brightness" -> "settings brightness ${value("percent")}"
            "/native/settings/timeout" -> "settings timeout ${value("ms")}"
            "/native/settings/rotation" -> "settings rotation ${value("on")}"
            "/native/install" -> "install"
            "/native/usage/list" -> "usage list"
            "/native/sms/list" -> "sms list"
            "/native/sms/send" -> "sms send ${value("to", true)} ${value("body", true)}"
            // 特权命令**不打码**：用户当初点的就是这个命令，记录里把它藏掉等于让「权限调用
            // 记录」失去唯一的复盘价值（与 /native/elevate 的附带命令同理）
            "/native/shell" ->
                (if (params["su"] == "1") "shell --su " else "shell ") + value("cmd")
            // 无障碍动作同样不打码：用户要复核的是「它点了哪里/输入了什么」，
            // 把目标文本藏起来等于让审计失去意义（与 /native/shell 同理）
            "/native/a11y/tree" -> "a11y tree"
            "/native/a11y/tap" -> "a11y tap ${value("x")} ${value("y")}"
            "/native/a11y/click" -> "a11y click ${value("target")} ${value("class")} ${value("index")}"
            "/native/a11y/swipe" ->
                "a11y swipe ${value("x1")} ${value("y1")} ${value("x2")} ${value("y2")} ${value("ms")}"
            "/native/a11y/text" ->
                "a11y text ${value("text")} ${value("target")} ${value("class")} ${value("index")}"
            "/native/a11y/global" -> "a11y global ${value("action")}"
            else -> "$method $path"
        }
        val options = when (path) {
            // 附带命令不打码：它是 agent 自己写的、也正是要用户复核的那串东西；打码等于把
            // 「权限调用记录」里最该看的一栏藏掉（会被打码的是通知正文、联系人这类用户隐私）
            "/native/elevate" -> listOf(option("command"))
            "/native/notify" -> listOf(option("id"), option("ongoing"))
            "/native/notify/list", "/native/sms/list" -> listOf(option("limit"))
            "/native/notify/system" -> listOf(option("all"))
            "/native/vibrate" -> listOf(option("ms"), option("amplitude"))
            "/native/clipboard" -> listOf(option("label", true))
            "/native/share" -> listOf(option("title", true))
            "/native/media/list" -> listOf(option("type"), option("q", true), option("limit"))
            "/native/media/read" -> listOf(option("type"))
            "/native/mic/record" -> listOf(option("ms"))
            "/native/mic/stop" -> listOf(option("id"))
            "/native/mic/start" -> listOf()
            "/native/camera/photo" -> listOf(option("facing"), option("max"))
            "/native/shell" -> listOf(option("timeout"))
            "/native/a11y/tree" -> listOf(option("depth"), option("max"))
            "/native/a11y/tap" -> listOf()
            "/native/a11y/click" -> listOf(option("class"), option("index"))
            "/native/a11y/swipe" -> listOf(option("ms"))
            "/native/a11y/text" -> listOf(option("target"), option("class"), option("index"))
            "/native/a11y/global" -> listOf()
            "/native/tts/speak", "/native/tts/file" -> listOf(option("lang"), option("rate"), option("pitch"))
            "/native/calendar/list" -> listOf(option("days"), option("limit"))
            "/native/calendar/create" -> listOf(
                option("start"), option("end"), option("minutes"),
                option("location", true), option("description", true),
            )
            "/native/contacts/list" -> listOf(option("q", true), option("limit"))
            "/native/location" -> listOf(option("maxAge"), option("wait"))
            "/native/volume" -> listOf(option("stream"))
            "/native/settings/brightness" -> listOf(option("auto"))
            "/native/usage/list" -> listOf(option("days"), option("limit"))
            else -> emptyList()
        }.filterNotNull()
        return (listOf("dsh-native", base.trim()) + options + listOf("--reason ${shellArg(reason)}")).joinToString(" ")
    }

    fun shellArg(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9._:/+-]+"))) value
        else "'${value.replace("'", "'\\''")}'"

    fun cancelSystemNotification(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!DshNotificationListener.connected()) {
            return 409 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_notification_access), "no_notification_access")
        }
        val key = NativeBridgeUtil.text(params["key"])
        val ok = if (key == null && params["all"] == "1") DshNotificationListener.cancelAll()
        else key?.let(DshNotificationListener::cancel) ?: false
        return if (ok) 200 to JSONObject().put("ok", true).toString()
        else 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_notification_cancel), "cancel_failed")
    }

    fun fullScreenNotify(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val title = NativeBridgeUtil.text(params["title"])
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_missing_param, "title"), "missing_title")
        val body = NativeBridgeUtil.text(params["body"]) ?: ""
        val open = PendingIntent.getActivity(
            ctx,
            NOTIFICATION_ID_BASE + MAX_NOTIFICATION_SLOT + 1,
            // 不 import MainActivity：dsh 包要下沉 :core-kernel，反向依赖 ui 会让整块搬不动
            appLaunchIntent(ctx),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: return 500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_no_service, "NotificationManager"), "no_service")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(FULL_SCREEN_CHANNEL_ID, NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_cap_full_screen_notify), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val notification = NotificationCompat.Builder(ctx, FULL_SCREEN_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID_BASE + MAX_NOTIFICATION_SLOT + 1, notification)
        return 200 to JSONObject().put("ok", true).toString()
    }

    fun notificationList(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!DshNotificationListener.connected()) {
            return 409 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_notification_access), "no_notification_access")
        }
        val limit = (params["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        return 200 to JSONObject()
            .put("ok", true)
            .put("notifications", DshNotificationListener.snapshot(limit))
            .toString()
    }

    fun smsSend(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!PermissionUtils.hasSmsSendPermission(ctx)) {
            return 403 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_sms_send_denied), "no_sms_send_permission")
        }
        val to = NativeBridgeUtil.text(params["to"])
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_missing_param, "to"), "missing_to")
        val body = NativeBridgeUtil.text(params["body"])
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_missing_param, "body"), "missing_body")
        return runCatching {
            val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION") SmsManager.getDefault()
            } ?: error("SmsManager unavailable")
            val parts = manager.divideMessage(body)
            if (parts.size > 1) manager.sendMultipartTextMessage(to, null, parts, null, null)
            else manager.sendTextMessage(to, null, body, null, null)
            200 to JSONObject().put("ok", true).put("to", to).put("parts", parts.size).toString()
        }.getOrElse { e ->
            Log.w(TAG, "发送短信失败: ${e.message}")
            500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_sms_send, e.message ?: ""), "send_failed")
        }
    }

    fun notify(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val title = NativeBridgeUtil.text(params["title"]) ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_missing_param, "title"), "missing_title")
        val body = NativeBridgeUtil.text(params["body"]) ?: ""
        val slot = (params["id"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_NOTIFICATION_SLOT)
        val ongoing = params["ongoing"] == "1"
        val id = NOTIFICATION_ID_BASE + slot

        ensureChannel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
            ?: return 500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_no_service, "NotificationManager"), "no_service")
        return runCatching {
            nm.notify(id, buildNotification(ctx, title, body, ongoing))
            200 to JSONObject().put("ok", true).put("id", slot).toString()
        }.getOrElse { e ->
            Log.w(TAG, "notify 失败: ${e.message}")
            500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_notify_failed, e.message ?: ""), "notify_failed")
        }
    }

    fun cancelNotify(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val slot = (params["id"]?.toIntOrNull() ?: 0).coerceIn(0, MAX_NOTIFICATION_SLOT)
        runCatching {
            ctx.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_ID_BASE + slot)
        }
        return 200 to JSONObject().put("ok", true).put("id", slot).toString()
    }

    fun buildNotification(
        ctx: Context,
        title: String,
        body: String,
        ongoing: Boolean,
    ): Notification {
        val open = PendingIntent.getActivity(
            ctx,
            NOTIFICATION_ID_BASE,
            appLaunchIntent(ctx),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    fun ensureChannel(ctx: Context) {
        if (channelReady) return
        runCatching {
            val ch = NotificationChannel(
                CHANNEL_ID,
                ctx.appString(CoreR.string.dsh_native_notif_channel_name),
                // 与前台服务那条 IMPORTANCE_LOW 分开：agent 主动发的通知本来就是要人看见的
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = ctx.appString(CoreR.string.dsh_native_notif_channel_desc)
            }
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
            channelReady = true
        }
    }

    fun toast(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val msg = NativeBridgeUtil.text(params["text"])
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_missing_param, "text"), "missing_text")
        // Toast 必须有 Looper；桥接线程没有
        val done = onMain { me.bmax.apatch.dsh.showToast(ctx, msg) }
        return if (done) 200 to NativeBridgeUtil.okJson()
        else 500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_no_service, "UI"), "timeout")
    }

    fun vibrate(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val ms = (params["ms"]?.toLongOrNull() ?: 30L).coerceIn(1L, MAX_VIBRATE_MS)
        val amplitude = (params["amplitude"]?.toIntOrNull() ?: -1).let {
            if (it < 0) VibrationEffect.DEFAULT_AMPLITUDE else it.coerceIn(1, 255)
        }
        val v = vibrator(ctx)
            ?: return 500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_no_service, "Vibrator"), "no_service")
        return runCatching {
            v.vibrate(VibrationEffect.createOneShot(ms, amplitude))
            200 to JSONObject().put("ok", true).put("ms", ms).toString()
        }.getOrElse { e ->
            Log.w(TAG, "vibrate 失败: ${e.message}")
            500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_vibrate_failed, e.message ?: ""), "vibrate_failed")
        }
    }

    /**
     * 不复用 [me.bmax.apatch.util.VibrationManager.vibrate]：那条被 UI 的
     * 「触感反馈」开关挡着、且时长固定 30ms，是给按钮点击用的。
     */
    fun vibrator(ctx: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }.getOrNull()

    fun clipboardSet(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val value = NativeBridgeUtil.text(params["text"])
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_missing_param, "text"), "missing_text")
        val label = NativeBridgeUtil.text(params["label"]) ?: "DSH"
        var failure: String? = null
        val done = onMain {
            runCatching {
                clipboard(ctx)?.setPrimaryClip(ClipData.newPlainText(label, value))
                    ?: run { failure = "no_service" }
            }.onFailure { e -> failure = e.message ?: "set_failed" }
        }
        // 主线程被卡住没跑完：按失败处理，别回一个「写成功了」的假 200
        if (!done && failure == null) failure = "timeout"
        val f = failure
        return if (f == null) 200 to NativeBridgeUtil.okJson()
        else 500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_clipboard_write, f), "clipboard_failed")
    }

    fun clipboardGet(ctx: Context): Pair<Int, String> {
        // Android 10 起后台读剪贴板恒为 null，别把它当成「剪贴板是空的」
        if (!NativeBridgeUtil.isForeground(ctx)) {
            return 409 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_clipboard_background), "not_foreground")
        }
        var value: String? = null
        var present = false
        val done = onMain {
            runCatching {
                val clip = clipboard(ctx)?.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    present = true
                    value = clip.getItemAt(0).coerceToText(ctx).toString()
                }
            }.onFailure { e -> Log.w(TAG, "读剪贴板失败: ${e.message}") }
        }
        // 主线程卡住没读到：不能回 empty:true（那正是被禁止的「假装剪贴板是空的」）
        if (!done) return 500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_no_service, "UI"), "timeout")
        return 200 to JSONObject()
            .put("ok", true)
            .put("empty", !present)
            .put("text", value ?: "")
            .toString()
    }

    fun clipboard(ctx: Context): ClipboardManager? =
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    fun share(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val value = NativeBridgeUtil.text(params["text"])
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_missing_param, "text"), "missing_text")
        val title = NativeBridgeUtil.text(params["title"])
        if (!NativeBridgeUtil.isForeground(ctx)) return backgroundActivityDenied(ctx)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
            if (title != null) putExtra(Intent.EXTRA_SUBJECT, title)
        }
        return startChooser(ctx, Intent.createChooser(send, title))
    }

    fun open(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val url = NativeBridgeUtil.text(params["url"])
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_missing_param, "url"), "missing_url")
        // 只放 http/https：file:// 要经 FileProvider 换 content URI，而 intent:// 之类
        // 能被用来拉起任意组件，不是这个接口该提供的能力
        val lower = url.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_scheme), "unsupported_scheme")
        }
        if (!NativeBridgeUtil.isForeground(ctx)) return backgroundActivityDenied(ctx)
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_bad_url), "bad_url")
        return startChooser(ctx, Intent(Intent.ACTION_VIEW, uri))
    }

    /**
     * 把号码填进系统拨号盘（[Intent.ACTION_DIAL]），**不拨出**——是否按下通话键由用户决定。
     *
     * 因此不需要 CALL_PHONE 权限，与 share/open 同属 INTENT 能力、同样要求前台（弹出的是系统
     * 拨号界面，没有前台就没人看得见）。只接受拨号盘认得的字符，避免把任意 tel: 负载塞进去。
     */
    fun dial(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val number = NativeBridgeUtil.text(params["number"])
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_missing_param, "number"), "missing_number")
        if (!number.matches(Regex("[0-9+()#*,\\-. ]{1,40}"))) {
            return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_bad_number), "bad_number")
        }
        if (!NativeBridgeUtil.isForeground(ctx)) return backgroundActivityDenied(ctx)
        val uri = android.net.Uri.fromParts("tel", number, null)
        return startChooser(ctx, Intent(Intent.ACTION_DIAL, uri))
    }

    fun startChooser(ctx: Context, intent: Intent): Pair<Int, String> =        runCatching {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            200 to NativeBridgeUtil.okJson()
        }.getOrElse { e ->
            Log.w(TAG, "startActivity 失败: ${e.message}")
            500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_start_failed, e.message ?: ""), "start_failed")
        }

    fun backgroundActivityDenied(ctx: Context): Pair<Int, String> =
        409 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_activity_background), "not_foreground")

    fun device(ctx: Context): Pair<Int, String> {
        val json = JSONObject()
            .put("ok", true)
            .put("brand", Build.BRAND)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("release", Build.VERSION.RELEASE)
            .put("abis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("locale", Locale.getDefault().toLanguageTag())
            .put("foreground", NativeBridgeUtil.isForeground(ctx))
        runCatching {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm != null) {
                json.put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                json.put("charging", bm.isCharging)
            }
        }
        runCatching {
            val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (status > 0) json.put("batteryTempC", status / 10.0)
        }
        return 200 to json.toString()
    }

}
