package me.bmax.apatch.dsh

import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import me.bmax.apatch.dsh.ext.ExtensionHookRegistry
import me.bmax.apatch.util.appString
import me.bmax.apatch.util.PermissionUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.bmax.apatch.res.R as CoreR
import me.bmax.apatch.dsh.workflow.WorkflowGrants
import me.bmax.apatch.dsh.DshNotificationListener
import me.bmax.apatch.dsh.devicecaps.NativeCapRouter

/**
 * 原生能力桥：容器内的 agent 通过 `/native/` 下的全部端点 让**宿主 App 代它**做只有 Android 侧
 * 才能做的事 —— 发通知、振动、弹 toast、读写剪贴板、拉起分享/打开链接、读设备信息。
 *
 * 端点挂在 [DshFsBridge] 的同一个回环 socket 上，共用它的回环守卫与 token 校验，
 * 所以这里只负责「授权之后允不允许、能不能做成」。
 *
 * ## 为什么必须默认关闭、并且分项
 *
 * 容器里跑的不只是 dsh 本体，还有用户自己从插件商店装的第三方插件。它们共享同一个
 * token（都读 `/root/.dsh/fs-bridge.json`），因此「能调这个接口」等价于「容器内任何代码
 * 都能调」。发通知、读剪贴板、拉起分享面板都是能被滥用的能力，只能由用户显式逐项打开。
 *
 * ## 前台限制不假装成功
 *
 * Android 10 起后台读剪贴板恒返回 null、后台启动 Activity 会被静默丢弃。这两类请求在
 * 后台时直接回 409 + `reason:"not_foreground"`，而不是回一个空串或者假的 ok ——
 * agent 需要知道「现在做不到，让用户把应用切到前台」，不是以为剪贴板真的是空的。
 */
object DshNativeBridge {
    /**
     * [Cap] 已迁到 :core-kernel 的 NativeCap.kt。
     *
     * 保留别名，全仓 200+ 处 `DshNativeBridge.Cap.XXX` 调用点零改动。
     * Kotlin 的 typealias 是编译期替换，与直接写真实类型完全等价，不影响 R8。
     */
    typealias Cap = me.bmax.apatch.dsh.Cap

    private const val TAG = "DshNativeBridge"

    /** agent 通知的独立渠道。和前台服务的 dsh_harness 分开：那条是 LOW，压根不会提醒。 */
    private const val CHANNEL_ID = "dsh_agent"
    private const val FULL_SCREEN_CHANNEL_ID = "dsh_agent_urgent"

    /** agent 通知 id 的基址。1001 是 [HarnessService] 的前台通知，绝不能被覆盖。 */
    private const val NOTIFICATION_ID_BASE = 2000
    private const val MAX_NOTIFICATION_SLOT = 999

    internal const val MAX_TEXT_LEN = 4096
    private const val MAX_VIBRATE_MS = 3000L

    /** 主线程操作的等待上限：桥接线程不能被 UI 卡死。 */
    private const val MAIN_WAIT_MS = 3000L

    @Volatile private var channelReady = false

    /**
     * [Access] 已迁到 :core-kernel 的 NativeAccess.kt。
     *
     * 保留别名，全仓 `DshNativeBridge.Access.XXX` 调用点零改动。
     */
    typealias Access = me.bmax.apatch.dsh.Access



    /**
     * 这项能力需要的**运行时**权限（可以直接 `requestPermissions` 的那种）。
     *
     * 空数组表示不需要任何运行时权限 —— 有两类：真的不需要（toast、振动、网络状态），
     * 以及需要一项**特殊**权限（[specialPermissionOf]）。后者申请不到，只能跳系统页。
     */
    fun runtimePermissions(cap: Cap): Array<String> = when (cap) {
        Cap.NOTIFY ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyArray()
            }
        Cap.FULL_SCREEN_NOTIFY ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)
            } else emptyArray()
        Cap.MEDIA -> PermissionUtils.mediaPermissions()
        Cap.MIC -> arrayOf(android.Manifest.permission.RECORD_AUDIO)
        Cap.CAMERA -> arrayOf(android.Manifest.permission.CAMERA)
        Cap.CALENDAR -> PermissionUtils.calendarPermissions()
        Cap.CONTACTS -> arrayOf(android.Manifest.permission.READ_CONTACTS)
        Cap.LOCATION -> PermissionUtils.locationPermissions()
        Cap.PHONE -> arrayOf(android.Manifest.permission.READ_PHONE_STATE)
        // 绝大多数传感器不需要权限；这两个只影响心率与计步两项，
        // 缺了它们这项能力**依然可用**（见 availability）
        Cap.SENSORS -> PermissionUtils.sensorPermissions()
        Cap.SMS -> arrayOf(
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS,
        )
        else -> emptyArray()
    }

    /**
     * 这项能力需要的**特殊**权限（`requestPermissions` 拿不到、必须跳系统页的那种）。
     *
     * 与 [runtimePermissions] 互斥：一项能力要么走运行时申请，要么走系统页，
     * 不会两者都要。null 表示不需要特殊权限。
     */
    fun specialPermissionOf(cap: Cap): Special? = when (cap) {
        Cap.A11Y -> Special.A11Y_SERVICE
        Cap.SETTINGS -> Special.WRITE_SETTINGS
        Cap.VOLUME -> Special.NOTIFICATION_POLICY
        Cap.INSTALL -> Special.REQUEST_INSTALL
        Cap.USAGE -> Special.USAGE_STATS
        Cap.FULL_SCREEN_NOTIFY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Special.FULL_SCREEN_INTENT
        } else null
        else -> null
    }

    /**
     * 申请不到的特殊权限。
     *
     * 每一项都对应一个专门的系统设置页 Action —— 通用的「应用信息」页对用户多两跳，
     * 而且 WRITE_SETTINGS 那个开关在应用信息页里根本找不到（它在「特殊应用权限」下面）。
     */
    enum class Special(val action: String, val perAppUri: Boolean) {
        /** 修改系统设置：亮度、休眠时间、自动旋转。 */
        WRITE_SETTINGS(Settings.ACTION_MANAGE_WRITE_SETTINGS, true),

        /**
         * 勿扰访问权。
         *
         * 这个页面**不接受** package: uri —— 传了也只会被忽略，只能列出所有应用让用户
         * 自己找到本应用。所以 [perAppUri] 是 false。
         */
        NOTIFICATION_POLICY(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, false),

        /** 安装未知应用。 */
        REQUEST_INSTALL(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, true),

        /** 全屏通知权限；Android 14+ 支持按应用跳转。 */
        FULL_SCREEN_INTENT(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, true),

        /** 通知读取访问权；系统只提供应用列表页。 */
        NOTIFICATION_ACCESS(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, false),

        /** 应用使用统计访问权；系统只提供应用列表页。 */
        USAGE_STATS(Settings.ACTION_USAGE_ACCESS_SETTINGS, false),

        /**
         * 无障碍服务。
         *
         * 它不是一个「权限」，而是一个由系统托管、用户单独打开的服务 —— 但对本应用而言
         * 判定方式与特殊权限完全一样（就是「用户有没有在系统页里打开这一项」），所以并进
         * 同一张表，于是「跳设置页 + 回前台复查」那套机制可以原样复用。
         * [perAppUri] 为 false：无障碍设置页不接受 package 参数。
         */
        A11Y_SERVICE(Settings.ACTION_ACCESSIBILITY_SETTINGS, false),
    }

    /** 这项特殊权限现在是否已经授予。 */
    fun specialGranted(ctx: Context, special: Special): Boolean = when (special) {
        Special.WRITE_SETTINGS -> PermissionUtils.canWriteSystemSettings(ctx)
        Special.NOTIFICATION_POLICY -> PermissionUtils.hasNotificationPolicyAccess(ctx)
        Special.REQUEST_INSTALL -> PermissionUtils.canRequestPackageInstalls(ctx)
        Special.FULL_SCREEN_INTENT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        } else true
        Special.NOTIFICATION_ACCESS -> DshNotificationListener.connected()
        Special.USAGE_STATS -> PermissionUtils.hasUsageStatsPermission(ctx)
        Special.A11Y_SERVICE -> DshA11y.connected()
    }

    fun runtimePermissions(ctx: Context, cap: Cap, access: Access): Array<String> = when (cap) {
        Cap.NOTIFY -> if (accessNeedsWrite(access) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)
        } else emptyArray()
        Cap.SMS -> buildList {
            if (accessNeedsRead(access)) add(android.Manifest.permission.READ_SMS)
            if (accessNeedsWrite(access)) add(android.Manifest.permission.SEND_SMS)
        }.toTypedArray()
        else -> runtimePermissions(cap)
    }

    fun specialPermissionOf(cap: Cap, access: Access): Special? =
        if (cap == Cap.NOTIFY && (accessNeedsRead(access) || access == Access.CONTROL)) Special.NOTIFICATION_ACCESS
        else specialPermissionOf(cap)

    private fun accessNeedsRead(access: Access): Boolean =
        access == Access.READ || access == Access.READ_WRITE || access == Access.CONTROL

    private fun accessNeedsWrite(access: Access): Boolean =
        access == Access.WRITE || access == Access.READ_WRITE || access == Access.CONTROL

    /**
     * 这项能力的权限是否已经齐了（运行时的与特殊的一并判断）。
     *
     * 界面用它决定要不要在那一项下面显示「去授权」。返回 true **不代表**能力可用 ——
     * 设备可能压根没有麦克风或摄像头，那是 [availability] 的事。
     *
     * 位置这一项只要求拿到任一档：只给了「大致位置」也算齐，精度差异由界面单独一行说明，
     * 而不是逼用户去追求精确位置。传感器同理 —— 缺 BODY_SENSORS 只是少两个传感器。
     */
    fun permissionSatisfied(ctx: Context, cap: Cap): Boolean =
        permissionSatisfied(ctx, cap, access(ctx, cap))

    /**
     * 同上，但按指定的档位判断。
     *
     * 需要它是因为「仅本次」：那一刻持久档位可能还是 off，而这次调用要用的档位是用户刚点的
     * 那一档 —— 按持久档位算会得出「什么都不缺」，于是该弹的 Android 权限提示不会弹。
     */
    fun permissionSatisfied(ctx: Context, cap: Cap, access: Access): Boolean {
        specialPermissionOf(cap, access)?.let { if (!specialGranted(ctx, it)) return false }
        val needed = runtimePermissions(ctx, cap, access)
        if (needed.isEmpty()) return true
        return when (cap) {
            // 三类媒体给一类就够（用户可能只想给照片）
            Cap.MEDIA -> PermissionUtils.hasAnyMediaPermission(ctx)
            // 大致位置也算给了
            Cap.LOCATION -> PermissionUtils.hasLocationPermission(ctx)
            else -> needed.all {
                ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /** 权限齐了的能力集合。界面一次算好，避免每一行各查一遍。 */
    fun capsWithPermission(ctx: Context): Set<Cap> =
        Cap.entries.filter { permissionSatisfied(ctx, it) }.toSet()

    // ────────────────────────── 开关 ──────────────────────────

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(DshEnv.PREF, Context.MODE_PRIVATE)

    /** 总开关。默认关。 */
    fun enabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(DshEnv.KEY_NATIVE_BRIDGE, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit { putBoolean(DshEnv.KEY_NATIVE_BRIDGE, on) }
    }

    private const val ACCESS_PREFIX = "native_bridge_access_"

    /**
     * 「仅本次」授权的寿命。
     *
     * 用户点下按钮时脑子里想的是「这件事」，agent 重试那次调用是几秒内的事；三分钟足够
     * 覆盖一次慢的工具往返，又不至于让一次点击在很久以后仍然有效。
     */
    const val ONCE_TTL_MS = 180_000L

    /**
     * 提权申请里附带命令的长度上限。
     *
     * 请求参数走查询串（桥只在 query 里取参数），而读请求头有 16 KB 的上限；留足其余参数与
     * 编码膨胀的余量，2000 个字符足够放下一条（哪怕是多行的）shell 命令。
     */
    private const val MAX_COMMAND_CHARS = 2000

    /** 记录文件的大小上限：超过就把当前文件转成 `.previous` 再重开一份。 */
    private const val AUDIT_FILE_MAX_BYTES = 1_048_576L

    /**
     * 一条记录里保存的响应体长度上限。
     *
     * 按列表类结果（联系人 / 通知 / 媒体）的典型体量估的：它们动辄几十上百 KB，一条不截
     * 就能把 [AUDIT_FILE_MAX_BYTES] 挤掉大半，用户翻记录时只看到最近一次调用。
     */
    private const val AUDIT_RESULT_MAX_CHARS = 2000

    /** 只有同时存在安全可用的读、写操作时才显示第三档。 */
    fun supportsWrite(cap: Cap): Boolean = when (cap) {
        Cap.NOTIFY, Cap.FULL_SCREEN_NOTIFY, Cap.TOAST, Cap.VIBRATE, Cap.TORCH, Cap.CLIPBOARD, Cap.INTENT,
        Cap.MIC, Cap.CAMERA, Cap.TTS, Cap.CALENDAR, Cap.VOLUME, Cap.SETTINGS,
        Cap.SMS, Cap.SHELL, Cap.SAF, Cap.A11Y, Cap.DISPLAY -> true
        else -> false
    }

    // 特权命令是**唯一**既读又写却没有 Android 权限的能力：它的门禁全在通道上
    // （[PrivilegedShell.denyReason]）与「限制模式 + 危险操作清单」上（[PrivPolicy.needsConfirm]）。

    fun supportsRead(cap: Cap): Boolean = when (cap) {
        Cap.TOAST, Cap.VIBRATE, Cap.TORCH, Cap.FULL_SCREEN_NOTIFY, Cap.INTENT, Cap.MIC, Cap.CAMERA -> false
        else -> true
    }

    fun accessOptions(cap: Cap): List<Access> = when {
        cap == Cap.NOTIFY -> listOf(Access.OFF, Access.WRITE, Access.READ, Access.READ_WRITE, Access.CONTROL)
        cap == Cap.SMS -> listOf(Access.OFF, Access.WRITE, Access.READ, Access.READ_WRITE)
        cap == Cap.FULL_SCREEN_NOTIFY -> listOf(Access.OFF, Access.WRITE)
        !supportsRead(cap) -> listOf(Access.OFF, Access.WRITE)
        supportsWrite(cap) -> listOf(Access.OFF, Access.READ, Access.READ_WRITE)
        else -> listOf(Access.OFF, Access.READ)
    }

    fun access(ctx: Context, cap: Cap): Access {
        val saved = prefs(ctx).getString(ACCESS_PREFIX + cap.id, null)
        return Access.entries.firstOrNull { it.id == saved }
            ?: if (cap in enabledCaps(ctx)) {
                if (supportsWrite(cap)) Access.READ_WRITE else Access.READ
            } else {
                Access.OFF
            }
    }

    /**
     * 「仅本次」的一次性授权。
     *
     * 用户在提权弹窗里选「仅本次」时写在这里：**不上盘**（进程重启即消失），而且只有
     * [ONCE_TTL_MS] 这么久的寿命 —— 用户当场同意的是「你正要做的这件事」，不是
     * 「往后随便用」。落到磁盘上就变成了一次点击永久提权，那正是这个选项要避免的。
     */
    private class Once(val access: Access, val expiresAtMs: Long)

    private val onceStore = ConcurrentHashMap<Cap, Once>()

    /** 未过期的一次性授权级别；顺手清掉过期的。 */
    fun onceAccess(cap: Cap): Access? {
        val once = onceStore[cap] ?: return null
        if (once.expiresAtMs <= System.currentTimeMillis()) {
            onceStore.remove(cap, once)
            return null
        }
        return once.access
    }

    /** 全部未过期的一次性授权，供 /native/capabilities 与提示词事实使用。 */
    fun onceGrants(): Map<Cap, Access> =
        Cap.entries.mapNotNull { cap -> onceAccess(cap)?.let { cap to it } }.toMap()

    /** 用户在弹窗里选了「仅本次」。 */
    fun grantOnce(ctx: Context, cap: Cap, access: Access) {
        val normalized = if (access in accessOptions(cap)) access else accessOptions(cap).last()
        onceStore[cap] = Once(normalized, System.currentTimeMillis() + ONCE_TTL_MS)
        // 立刻改事实：提示词得说清「只有一次」，否则 agent 会拿它当普通授权连着调
        runCatching { DshHostPrompt.writeFacts(ctx.applicationContext) }
    }

    /**
     * 计入一次性授权后的实际级别。
     *
     * 取两者中更高的一档（按 [accessOptions] 的档位顺序比较），所以「仅本次」只能加不能减。
     */
    private fun effectiveAccess(ctx: Context, cap: Cap): Access {
        val persisted = access(ctx, cap)
        val once = onceAccess(cap) ?: return persisted
        val options = accessOptions(cap)
        return if (options.indexOf(once) > options.indexOf(persisted)) once else persisted
    }

    /**
     * 这次调用如果真的用了「仅本次」配额就把它收回。
     *
     * 判据是「持久级别本来通不过这次调用的闸门」—— 一次读操作不该顺手烧掉写配额，已经持久
     * 授权的项也不会因为一次调用丢掉一次性授权。判据必须复刻 [handle] 里那三道闸门（control /
     * write / read）：只看读写的版本会漏掉 `notify/system` 的 CONTROL 档，那一档的一次性授权
     * 就能被反复使用。
     */
    private fun spendOnce(ctx: Context, cap: Cap, method: String, path: String, params: Map<String, String>) {
        val once = onceStore[cap] ?: return
        if (once.expiresAtMs <= System.currentTimeMillis()) {
            onceStore.remove(cap, once)
            return
        }
        val persisted = access(ctx, cap)
        val authorizedBefore = when {
            path == "/native/notify/system" -> persisted == Access.CONTROL
            isWriteRequest(method, path, params) -> accessNeedsWrite(persisted)
            else -> accessNeedsRead(persisted)
        }
        if (authorizedBefore) return
        if (onceStore.remove(cap, once)) {
            runCatching { DshHostPrompt.writeFacts(ctx.applicationContext) }
        }
    }

    fun setAccess(ctx: Context, cap: Cap, access: Access) {
        val normalized = if (access in accessOptions(cap)) access else accessOptions(cap).last()
        prefs(ctx).edit { putString(ACCESS_PREFIX + cap.id, normalized.id) }
        // 同步旧集合，兼容同一 prefs 的旧代码/降级安装。
        setCapEnabled(ctx, cap, normalized != Access.OFF)
    }

    fun accessMap(ctx: Context): Map<Cap, Access> = Cap.entries.associateWith { access(ctx, it) }

    fun capEnabled(ctx: Context, cap: Cap): Boolean = access(ctx, cap) != Access.OFF

    /**
     * 这项能力这次调用能不能过闸门：设置里勾着，或者用户刚给过一次性的「仅本次」。
     *
     * 必须与 [capEnabled] 分开：[capEnabled] 回答的是「设置里开着吗」，界面与提示词事实都用它；
     * 这里回答的是「这次调用放行吗」。合成一个的话，「仅本次」会在它最该起作用的场景 —— 能力
     * 本来就没勾 —— 被 `cap_disabled` 挡死，用户点了同意却什么也没发生。
     */
    private fun capCallable(ctx: Context, cap: Cap): Boolean =
        capEnabled(ctx, cap) || onceAccess(cap) != null

    private fun canWrite(ctx: Context, cap: Cap): Boolean = accessNeedsWrite(effectiveAccess(ctx, cap))

    private fun canRead(ctx: Context, cap: Cap): Boolean = accessNeedsRead(effectiveAccess(ctx, cap))

    /** 已启用的能力集合。默认空 —— 开了总开关也还要逐项勾。 */
    fun enabledCaps(ctx: Context): Set<Cap> {
        val raw = prefs(ctx).getString(DshEnv.KEY_NATIVE_CAPS, "") ?: ""
        if (raw.isEmpty()) return emptySet()
        val ids = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return Cap.entries.filter { it.id in ids }.toSet()
    }

    fun setCapEnabled(ctx: Context, cap: Cap, on: Boolean) {
        val next = enabledCaps(ctx).toMutableSet()
        if (on) next.add(cap) else next.remove(cap)
        prefs(ctx).edit {
            putString(DshEnv.KEY_NATIVE_CAPS, next.joinToString(",") { it.id })
        }
    }

    /**
     * 这项能力当前是否真的能用（权限、系统能力层面），以及不能用的原因 id。
     *
     * 与「用户开没开」分开：通知项开着但系统通知权限没给时，要能说出是后者。
     */
    private fun availability(ctx: Context, cap: Cap): Pair<Boolean, String> = when (cap) {
        // 「能用」是宽松判据（有任一子操作的权限即可）：发通知要 POST，读/清系统通知要通知监听
        // 访问权 —— 两者独立。只查 POST 会把「只开了监听、用 read 档读通知」误判成不可用。
        // 每次调用具体需要哪一项，由 osGap 按档位精确把关（在此之前）。
        Cap.NOTIFY ->
            if (PermissionUtils.hasNotificationPermission(ctx) || DshNotificationListener.connected()) true to ""
            else false to "no_notification_permission"
        Cap.FULL_SCREEN_NOTIFY ->
            if (!PermissionUtils.hasNotificationPermission(ctx)) false to "no_notification_permission"
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !specialGranted(ctx, Special.FULL_SCREEN_INTENT)) false to "no_full_screen_permission"
            else true to ""
        Cap.VIBRATE ->
            if (vibrator(ctx)?.hasVibrator() == true) true to "" else false to "no_vibrator"
        // 有些设备（平板/模拟器）没有闪光灯：那是设备属性，不是瞬时错误，别让 agent 反复重试
        Cap.TORCH ->
            if (hasTorch(ctx)) true to "" else false to "no_torch"
        // 只要有一类媒体可读就算可用：用户可能只给了照片。具体缺哪一类由
        // /native/media/list 的 granted 字段说明，不在这里一刀切成不可用。
        Cap.MEDIA ->
            if (PermissionUtils.hasAnyMediaPermission(ctx)) true to ""
            else false to "no_media_permission"
        Cap.MIC ->
            if (!PermissionUtils.hasMicrophonePermission(ctx)) false to "no_audio_permission"
            else if (!hasMicrophone(ctx)) false to "no_microphone"
            else true to ""
        Cap.CAMERA ->
            if (!PermissionUtils.hasCameraPermission(ctx)) false to "no_camera_permission"
            else if (!DshCamera.hasCamera(ctx)) false to "no_camera"
            else true to ""
        // 设备上没装 TTS 引擎时这一项做不了任何事。海外精简 ROM 和一些定制系统真的
        // 不带引擎，那时该让 agent 知道「去装一个」而不是反复撞 500。
        Cap.TTS ->
            if (DshTts.hasEngine(ctx)) true to "" else false to "no_tts_engine"
        // 读能力只要读权限：写日历缺权限时由 /native/calendar/create 自己回 403，
        // 不能因为不能写就把「看日程」也判成不可用
        Cap.CALENDAR ->
            if (PermissionUtils.hasCalendarReadPermission(ctx)) true to ""
            else false to "no_calendar_permission"
        Cap.CONTACTS ->
            if (PermissionUtils.hasContactsPermission(ctx)) true to ""
            else false to "no_contacts_permission"
        Cap.LOCATION ->
            if (PermissionUtils.hasLocationPermission(ctx)) true to ""
            else false to "no_location_permission"
        Cap.PHONE ->
            if (!PermissionUtils.hasPhoneStatePermission(ctx)) false to "no_phone_permission"
            else if (!hasTelephony(ctx)) false to "no_telephony"
            else true to ""
        // 传感器这一项**不**因缺权限而不可用：加速度、光、气压等都不需要权限，
        // BODY_SENSORS / ACTIVITY_RECOGNITION 只影响心率与计步两条，
        // 由 /native/sensors/list 的 needPermission 字段说明
        Cap.SENSORS -> true to ""
        // 这三项走特殊权限：申请不到，只能跳系统页
        Cap.SETTINGS ->
            if (PermissionUtils.canWriteSystemSettings(ctx)) true to ""
            else false to "no_write_settings"
        // 音量读取从不需要授权，只有「勿扰开着时改音量」「设静音/振动」才要策略访问权
        // —— 所以这一项恒可用，权限不足时由具体的写操作回 403
        Cap.VOLUME -> true to ""
        Cap.INSTALL -> true to ""
        Cap.USAGE ->
            if (PermissionUtils.hasUsageStatsPermission(ctx)) true to ""
            else false to "no_usage_permission"
        // 「能用」是宽松判据：只读短信要 READ_SMS，只发短信要 SEND_SMS —— 任一在即可用。
        // 只查 READ_SMS 会把「只发送」这个明确提供的档位永久判成不可用（发送其实只需 SEND_SMS）。
        // 每次调用具体需要读还是写，由 osGap 按档位精确把关。
        Cap.SMS ->
            if (PermissionUtils.hasSmsReadPermission(ctx) || PermissionUtils.hasSmsSendPermission(ctx)) true to ""
            else false to "no_sms_permission"
        // 特权命令能不能用，取决于用户有没有选一条通道、以及那条通道是不是已经就绪。
        // 未就绪（root 还没验过、Shizuku 还没授权、ADB 还没配对）要回**精确原因**：
        // 一律回 no_channel 会让 agent 说「用户没开特权」，而实际上用户刚刚开过，
        // 只是还差一步 —— 那句话会把用户引到一个已经打开了的设置页上。
        Cap.SHELL, Cap.DISPLAY -> PrivilegedShell.reach(ctx)?.let { reach ->
            when {
                reach.usable -> true to ""
                else -> false to (reach.reason ?: "no_channel")
            }
        } ?: (false to "no_channel")
        // 无障碍服务没开时这一项做不了任何事：agent 该提示用户去打开那个开关
        Cap.A11Y ->
            if (DshA11y.connected()) true to "" else false to "no_a11y_service"
        // 没有任何已登记的 SAF 来源时这一项做不了事：agent 该提示用户在「文件访问范围」里
        // 用系统选择器选一棵挂不上的树（会被登记成来源）。这不是瞬时错误，别反复重试。
        Cap.SAF ->
            if (DshSafSources.sources(ctx).isNotEmpty()) true to "" else false to "no_saf_source"
        else -> true to ""
    }

    private fun hasMicrophone(ctx: Context): Boolean = runCatching {
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }.getOrDefault(true)

    /** 平板与模拟器常常没有电话功能，那时 TelephonyManager 的字段全是空的。 */
    private fun hasTelephony(ctx: Context): Boolean = runCatching {
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }.getOrDefault(false)

    /** 设备有没有可当电筒的闪光灯。能力可用性判断用。 */
    private fun hasTorch(ctx: Context): Boolean = runCatching {
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }.getOrDefault(false)

    // ────────────────────────── 分发 ──────────────────────────

    /**
     * 处理一条 `/native/...` 请求。
     *
     * @return `状态码 to JSON 体`；null 表示响应已自行写出（本桥不会出现这种情况）。
     */
    fun handle(
        ctx: Context,
        method: String,
        path: String,
        params: Map<String, String>,
    ): Pair<Int, String> {
        // capabilities 必须在总开关关闭时也能查：否则容器侧只能靠 403 猜是「没开」还是「没这功能」
        if (path == "/native/capabilities") {
            return if (method == "GET") capabilitiesJson(ctx) else methodNotAllowed(ctx, method, path)
        }
        if (path == "/native/elevate") {
            return if (method == "POST") requestElevation(ctx, params) else methodNotAllowed(ctx, method, path)
        }
        // 工作流运行期间的调用**不受这套门禁制约**：用户在工作流开跑前对整份流程点过一次头
        // （弹窗逐项列了它要用的能力），此后每一步再被档位 / 限制模式 / 危险清单拦一次，只会把
        // 「一次批准」变成几十次形式化点击 —— 那种设计最后的产物是人闭眼点「允许」。
        //
        // 真正兜底的是 **Android 自己的权限**：运行时权限、特殊访问（无障碍/用量/全屏通知…）
        // 一样都没被这里绕过，缺了照样失败；而且工作流开跑前有一个专门的检查弹窗先把它们
        // 确认好（见 [WorkflowPermissions]）。危险命令另有一道条件：批准时必须**单独勾选**过。
        val grantCap = capOf(path)
        val grantDanger = grantCap == Cap.SHELL &&
            PrivPolicy.shellRisk(ctx, params["cmd"].orEmpty()) == PrivRisk.DANGEROUS
        // 两个分支返回的类型必须一致：写成 if (danger) allowsDangerous(...) else allows(...) 会让
        // 推断结果变成 Any?（Boolean? 与 Grant? 的公共父类型），后面取 .runId 就编译不过。
        val workflowGrant: WorkflowGrants.Grant? = if (grantDanger) {
            if (grantCap != null && WorkflowGrants.allowsDangerous(grantCap.id)) {
                WorkflowGrants.allows(grantCap.id)
            } else {
                null
            }
        } else {
            grantCap?.let { WorkflowGrants.allows(it.id) }
        }
        val workflowCall = workflowGrant != null
        if (!workflowCall && !enabled(ctx)) {
            return 403 to err(str(ctx, CoreR.string.dsh_native_err_disabled), "disabled")
        }

        val reason = text(params["reason"])
            ?: return 400 to err(str(ctx, CoreR.string.dsh_native_err_reason_required), "reason_required")
        // 这次调用是否经过用户当场同意（免确认路径留 auto）。只用于审计 —— 事后要能回答
        // 「这条 root 命令是用户点过头的，还是宽松档直接跑的」。
        var privDecision = "auto"
        val cap = capOf(path)
            ?: return 404 to err(
                str(ctx, CoreR.string.dsh_native_err_unknown_endpoint, method, path),
                "unknown_endpoint",
            )
        // 档位不够时**不**直接 403，而是阻塞着问用户。用户同意就把这次调用就地执行掉并返回真实
        // 结果 —— agent 不需要「申请 → 再调一次」，也就不会看到「申请成功了但调用还是失败」。
        val need = if (workflowCall) null else insufficient(ctx, cap, method, path, params)
        // 档位够不代表就能直接跑：还要过「限制模式 + 危险操作清单」这一关（[PrivPolicy]）。
        //
        // 判据只有两个来源，**对所有能力一视同仁**：
        //  ① 命中危险操作清单 → 永远问（清单条目可删，删掉即沉默）；
        //  ② 限制模式开着、且这个能力在能力清单里 → 每次都问。
        // 旧模型这里是 `when (cap)` 只给 shell/虚拟屏/无障碍算 risk、其余返回 null ——
        // 于是短信、相机、麦克风、定位在能力启用之后再没被问过。那道"闸"本来就只覆盖三个
        // 能力；现在把 CAMERA 之类放进能力清单才是真的新增了一道闸。
        val danger = cap == Cap.SHELL &&
            PrivPolicy.shellRisk(ctx, params["cmd"].orEmpty()) == PrivRisk.DANGEROUS
        val confirm = !workflowCall && PrivPolicy.needsConfirm(
            restrictMode = PrivPolicy.restrictMode(ctx),
            capRestricted = PrivPolicy.isRestricted(ctx, cap),
            danger = danger,
        )
        // risk 只用于审计与返回值（`docRisk` 等）：它不再决定问不问，但必须仍与清单一致，
        // 否则"审计说 dangerous、实际没问"就成了新的谜。
        val risk = if (cap == Cap.SHELL) {
            PrivPolicy.shellRisk(ctx, params["cmd"].orEmpty())
        } else {
            PrivRisk.WRITE
        }
        if (workflowCall) {
            // 审计里要能回答「这条命令是哪个工作流的哪一次运行放行的」——这就是那条线索。
            privDecision = "workflow:" + (workflowGrant?.runId ?: "")
        }
        if ((need != null || confirm) && !workflowCall) {
            // 弹窗只有在前台才看得见；不在前台就别把这条连接挂在这里等一个永远不会出现的弹窗
            if (!isForeground(ctx)) {
                val result = 409 to err(
                    str(ctx, CoreR.string.dsh_native_err_elevate_foreground),
                    "not_foreground",
                )
                audit(ctx, method, path, params, cap, reason, result)
                return result
            }
            // 弹窗上显示的就是这条命令本身（与审计同款重建），用户看到的即是将要执行的
            val command = auditCommand(method, path, params, reason, maskSensitive = false)
            // 两种弹窗：LEVEL 是「档位不够，问要不要放开」；CALL 是「档位够，但按严格程度
            // 这次仍要你点头」。分开是因为按钮不同 —— LEVEL 可能有「允许（长期）」，
            // CALL 只有「允许本次」。
            val kind = if (need != null) DshElevationRequests.Kind.LEVEL else DshElevationRequests.Kind.CALL
            val reach = if (cap == Cap.SHELL) PrivilegedShell.reach(ctx) else null
            val request = DshElevationRequests.submit(
                cap = cap,
                access = need ?: effectiveAccess(ctx, cap),
                reason = reason,
                command = command,
                invocation = null,
                kind = kind,
                channel = reach?.channel?.let { channelLabel(ctx, it) },
                uid = reach?.uid,
            )
            if (request == null) {
                val result = 409 to err(
                    str(ctx, CoreR.string.dsh_native_err_elevate_busy),
                    "request_pending",
                )
                audit(ctx, method, path, params, cap, reason, result)
                return result
            }
            runCatching { DshHostPrompt.writeFacts(ctx.applicationContext) }
            privDecision = if (kind == DshElevationRequests.Kind.CALL) "confirmed" else "level_granted"
            when (DshElevationRequests.awaitDecision(request.id)) {   // 阻塞在这里等用户
                DshElevationRequests.Decision.ALLOWED,
                DshElevationRequests.Decision.ONCE,
                -> Unit

                DshElevationRequests.Decision.DENIED -> {
                    privDecision = "denied"
                    val result = 403 to err(
                        str(ctx, CoreR.string.dsh_native_err_denied, capName(ctx, cap)),
                        "denied_by_user",
                    )
                    // 被拒这条也要记「当时是什么判据在问」：restrictMode 关着却问了，
                    // 说明问的原因是危险清单命中；开着则是清单里的能力。reason 只说了"被拒"。
                    audit(ctx, method, path, params, cap, reason, result, privAuditExtra(ctx, cap, privDecision))
                    return result
                }

                DshElevationRequests.Decision.EXPIRED -> {
                    privDecision = "expired"
                    val result = 403 to err(
                        str(ctx, CoreR.string.dsh_native_err_request_expired, capName(ctx, cap)),
                        "request_expired",
                    )
                    audit(ctx, method, path, params, cap, reason, result, privAuditExtra(ctx, cap, privDecision))
                    return result
                }
            }
        }
        // 免确认直接跑掉的时候，"为什么不用问"要能分辨：限制模式关着，还是这个能力不在清单里
        // —— 事后复盘时这两者的含义完全不同（前者是全局默认，后者是用户把它移出了清单）。
        if (!workflowCall && need == null && !confirm) {
            privDecision = if (PrivPolicy.restrictMode(ctx)) "cap_not_listed" else "restrict_off"
        }
        // 兜底：走到这里档位仍不够（例如申请期间用户在别处把开关关掉了）。维持原来的 403，
        // 而不是假装放行。
        if (!workflowCall && !capCallable(ctx, cap)) {
            val result = 403 to err(
                str(ctx, CoreR.string.dsh_native_err_cap_disabled, capName(ctx, cap)),
                "cap_disabled",
            )
            audit(ctx, method, path, params, cap, reason, result)
            return result
        }
        if (!workflowCall && path == "/native/notify/system" && effectiveAccess(ctx, cap) != Access.CONTROL) {
            val result = 403 to err(str(ctx, CoreR.string.dsh_native_err_control_disabled, capName(ctx, cap)), "control_disabled")
            audit(ctx, method, path, params, cap, reason, result)
            return result
        }
        if (!workflowCall && isWriteRequest(method, path, params) && !canWrite(ctx, cap)) {
            val result = 403 to err(
                str(ctx, CoreR.string.dsh_native_err_write_disabled, capName(ctx, cap)),
                "write_disabled",
            )
            audit(ctx, method, path, params, cap, reason, result)
            return result
        }
        if (!workflowCall && !isWriteRequest(method, path, params) && !canRead(ctx, cap)) {
            val result = 403 to err(
                str(ctx, CoreR.string.dsh_native_err_read_disabled, capName(ctx, cap)),
                "read_disabled",
            )
            audit(ctx, method, path, params, cap, reason, result)
            return result
        }
        // Android 自己的权限没给：App 层允许了也执行不了。这一段单独弹（可能要把用户送去系统
        // 设置页，回来再复查），用户确认后把「Android 层没授权」作为结果返回。
        val gap = osGap(ctx, cap, need ?: effectiveAccess(ctx, cap))
        if (gap != null && !awaitOsGap(ctx, cap, need ?: effectiveAccess(ctx, cap), gap)) {
            val result = 409 to err(
                str(ctx, CoreR.string.dsh_native_err_no_android_permission, gap.labels.joinToString(", ")),
                "no_android_permission",
            )
            audit(ctx, method, path, params, cap, reason, result)
            return result
        }
        val (ok, why) = availability(ctx, cap)
        if (!ok) {
            val result = 409 to err(
                str(ctx, CoreR.string.dsh_native_err_cap_unavailable, capName(ctx, cap)),
                why,
            )
            audit(ctx, method, path, params, cap, reason, result)
            return result
        }
        // 到这里这次调用真的会被执行，「仅本次」配额到此为止 —— 一次授权换一次调用。
        // 放在设备可用性检查**之后**：用户点了「仅本次」，接着在 Android 自己的权限框上
        // 点了拒绝（或者干脆没给），这次调用什么也做不成；那种情况烧掉配额等于让他为同一件
        // 事回答两次。配额本身有三分钟寿命，所以留着也不会变成长期授权。
        spendOnce(ctx, cap, method, path, params)

        // 扩展拦截点（调用前）：权限判定已全部通过，**即将真的执行**这一下。
        //
        // around 返回 false = 扩展拦下这次调用（例如它要替用户加一道自己的策略）。
        // 拦下时返回 403 且**照常写审计** —— 审计里要能看出「是扩展拦的」而不是「宿主拒绝」，
        // 否则事后查日志会误判成权限配置问题。
        if (ExtensionHookRegistry.has(ExtensionHookRegistry.Point.NATIVE_BEFORE_INVOKE)) {
            val payload = mutableMapOf<String, Any?>(
                "cap" to cap?.id,
                "method" to method,
                "path" to path,
                "params" to params,
                "reason" to reason,
            )
            if (!ExtensionHookRegistry.runAround(ExtensionHookRegistry.Point.NATIVE_BEFORE_INVOKE, payload)) {
                val result = 403 to err("调用被扩展拦下", "extension_blocked")
                audit(ctx, method, path, params, cap, reason, result, privAuditExtra(ctx, cap, "extension_blocked"))
                return result
            }
        }

        val result = runCatching {
            when {
            method == "POST" && path == "/native/shell" -> shellExec(ctx, params)
            method == "GET" && path == "/native/a11y/tree" -> a11yExec(ctx, "tree", params)
            method == "POST" && path == "/native/a11y/tap" -> a11yExec(ctx, "tap", params)
            method == "POST" && path == "/native/a11y/click" -> a11yExec(ctx, "click", params)
            method == "POST" && path == "/native/a11y/swipe" -> a11yExec(ctx, "swipe", params)
            method == "POST" && path == "/native/a11y/text" -> a11yExec(ctx, "text", params)
            method == "POST" && path == "/native/a11y/global" -> a11yExec(ctx, "global", params)
            method == "GET" && path == "/native/a11y/screenshot" -> a11yExec(ctx, "screenshot", params)
            // 虚拟屏（截屏 / 注入输入 / 在虚拟屏上启动 App）。
            //
            // 逐条列出而不是用 `path.startsWith("/native/display/")` 一把兜住：本项目的
            // check-native-caps 门禁是「capOf 里声明的每条端点都必须在分派表里显式出现」，
            // 前缀兜底会让它看不见这些路由，于是新加端点时漏接线也无人拦。a11y 那一族
            // 同样是逐条列的，风格一致。
            method == "GET" && path == "/native/display/status" -> display(ctx, method, path, params)
            method == "POST" && path == "/native/display/session" -> display(ctx, method, path, params)
            method == "POST" && path == "/native/display/screenshot" -> display(ctx, method, path, params)
            method == "POST" && path == "/native/display/tap" -> display(ctx, method, path, params)
            method == "POST" && path == "/native/display/swipe" -> display(ctx, method, path, params)
            method == "POST" && path == "/native/display/key" -> display(ctx, method, path, params)
            method == "POST" && path == "/native/display/launch" -> display(ctx, method, path, params)
            method == "POST" && path == "/native/display/stop" -> display(ctx, method, path, params)
            method == "POST" && path == "/native/notify" -> notify(ctx, params)
            method == "DELETE" && path == "/native/notify" -> cancelNotify(ctx, params)
            method == "GET" && path == "/native/notify/list" -> notificationList(ctx, params)
            method == "DELETE" && path == "/native/notify/system" -> cancelSystemNotification(ctx, params)
            method == "POST" && path == "/native/notify/full-screen" -> fullScreenNotify(ctx, params)
            method == "POST" && path == "/native/toast" -> toast(ctx, params)
            method == "POST" && path == "/native/vibrate" -> vibrate(ctx, params)
            method == "POST" && path == "/native/torch" -> DshSystemCtl.torchSet(ctx, params)
            method == "POST" && path == "/native/clipboard" -> clipboardSet(ctx, params)
            method == "GET" && path == "/native/clipboard" -> clipboardGet(ctx)
            method == "POST" && path == "/native/share" -> share(ctx, params)
            method == "POST" && path == "/native/open" -> open(ctx, params)
            method == "POST" && path == "/native/dial" -> dial(ctx, params)
            method == "GET" && path == "/native/device" -> device(ctx)
            method == "GET" && path == "/native/media/list" -> mediaList(ctx, params)
            method == "GET" && path == "/native/media/read" -> mediaRead(ctx, params)
            method == "POST" && path == "/native/mic/record" -> micRecord(ctx, params)
            method == "POST" && path == "/native/mic/start" -> micStart(ctx, params)
            method == "POST" && path == "/native/mic/stop" -> micStop(ctx, params)
            method == "POST" && path == "/native/camera/photo" -> DshCamera.photo(ctx, params)
            method == "POST" && path == "/native/tts/speak" -> DshTts.speak(ctx, params)
            method == "POST" && path == "/native/tts/file" -> DshTts.toFile(ctx, params)
            method == "GET" && path == "/native/tts/voices" -> DshTts.voices(ctx)
            method == "GET" && path == "/native/calendar/list" ->
                DshPersonalData.calendarList(ctx, params)
            method == "POST" && path == "/native/calendar/create" ->
                DshPersonalData.calendarCreate(ctx, params)
            method == "GET" && path == "/native/contacts/list" ->
                DshPersonalData.contactsList(ctx, params)
            method == "GET" && path == "/native/location" ->
                DshPersonalData.location(ctx, params)
            method == "GET" && path == "/native/phone/info" -> DshDeviceSense.phoneInfo(ctx)
            method == "GET" && path == "/native/sensors/list" ->
                DshDeviceSense.sensorList(ctx)
            method == "GET" && path == "/native/sensors/read" ->
                DshDeviceSense.sensorRead(ctx, params)
            method == "GET" && path == "/native/network" -> DshDeviceSense.networkInfo(ctx)
            method == "GET" && path == "/native/volume" -> DshSystemCtl.volumeGet(ctx)
            method == "POST" && path == "/native/volume" ->
                DshSystemCtl.volumeSet(ctx, params)
            method == "POST" && path == "/native/ringer" ->
                DshSystemCtl.ringerSet(ctx, params)
            method == "GET" && path == "/native/settings" -> DshSystemCtl.settingsGet(ctx)
            method == "POST" && path == "/native/settings/brightness" ->
                DshSystemCtl.brightnessSet(ctx, params)
            method == "POST" && path == "/native/settings/timeout" ->
                DshSystemCtl.timeoutSet(ctx, params)
            method == "POST" && path == "/native/settings/rotation" ->
                DshSystemCtl.rotationSet(ctx, params)
            method == "GET" && path == "/native/install" -> DshSystemCtl.installStatus(ctx)
            method == "GET" && path == "/native/usage/list" ->
                DshPersonalData.usageList(ctx, params)
            method == "GET" && path == "/native/sms/list" ->
                DshPersonalData.smsList(ctx, params)
            method == "POST" && path == "/native/sms/send" -> smsSend(ctx, params)
            else -> methodNotAllowed(ctx, method, path)
            }
        }.getOrElse { e ->
            // 端点处理器抛异常（未 runCatching 的那几个）：回一个带原因的 500，并且**照常审计**，
            // 否则这条调用在记录里凭空消失。
            Log.w(TAG, "native endpoint threw $method $path: ${e.message}")
            500 to err(str(ctx, CoreR.string.dsh_native_err_internal, e.message ?: ""), "internal")
        }
        // 扩展拦截点（调用后）：结果已经拿到，允许扩展**改写响应**。
        //
        // 用途：给敏感内容打码、补充字段、把某个端点的返回统一成扩展自己的格式。
        // 与 `SHELL_AFTER_EXEC` 的区别是层次不同 —— 这里管的是**桥端点**的响应，
        // 那里管的是容器内 bash 命令的输出。
        //
        // 审计放在改写**之后**：日志里记的应该是模型实际看到的那份内容，
        // 否则事后对账会对不上。
        val finalResult = if (ExtensionHookRegistry.has(ExtensionHookRegistry.Point.NATIVE_AFTER_INVOKE)) {
            val payload = mutableMapOf<String, Any?>(
                "cap" to cap?.id,
                "path" to path,
                "code" to result.first,
                "body" to result.second,
            )
            ExtensionHookRegistry.runBefore(ExtensionHookRegistry.Point.NATIVE_AFTER_INVOKE, payload)
            val code = (payload["code"] as? Int) ?: result.first
            val body = (payload["body"] as? String) ?: result.second
            code to body
        } else {
            result
        }
        audit(ctx, method, path, params, cap, reason, finalResult, privAuditExtra(ctx, cap, privDecision))
        return finalResult
    }

    /**
     * 执行一条特权命令。
     *
     * 走到这里时档位与用户同意都已经过了（见 [handle] 的闸门），这一层只负责：通道允许不允许
     * （[PrivilegedShell.denyReason]）、单飞、真的跑、把结果按协议返回。
     *
     * 命令本身**不**在 JSON 里回显 —— 它已经在审计里了，而回显等于让 agent 以为自己看到的是
     * 「宿主批准的命令」，实际上批准的是它自己写的那串。
     */
    private fun shellExec(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val command = text(params["cmd"])
            ?: return 400 to err(str(ctx, CoreR.string.dsh_native_err_shell_no_command), "no_command")
        val asRoot = params["su"] == "1"
        val timeout = params["timeout"]?.toLongOrNull() ?: PrivilegedShell.DEFAULT_TIMEOUT_MS
        val risk = PrivilegedShell.riskOf(command)
        // 这里**不**自己写审计：[handle] 拿到返回值之后统一记一条，自己再记一条会变成
        // 同一次调用出现两行（而且下面那些早期返回也都被记过两遍）
        PrivilegedShell.denyReason(ctx, risk, asRoot)?.let { deny ->
            return 403 to err(str(ctx, CoreR.string.dsh_native_err_priv_denied, privReasonText(ctx, deny)), deny)
        }
        if (!PrivilegedShell.tryEnter()) {
            return 429 to err(str(ctx, CoreR.string.dsh_native_err_priv_busy), "busy")
        }
        val outcome = try {
            PrivilegedShell.exec(ctx, command, asRoot, timeout)
        } finally {
            PrivilegedShell.exit()
        }
        val reach = PrivilegedShell.reach(ctx)
        val body = JSONObject()
            .put("ok", (run { val n = outcome.note; n }) == null && outcome.exit == 0)
            .put("exit", outcome.exit)
            .put("stdout", outcome.stdout)
            .put("stderr", outcome.stderr)
            .put("timedOut", outcome.timedOut)
            .put("channel", reach?.channel?.name?.lowercase().orEmpty())
            .put("uid", reach?.uid ?: -1)
            .put("asRoot", asRoot)
            .put("risk", riskId(risk))
        // 跑不成（通道没了 / 超时）与「跑了但退出码非零」是两件事：前者 agent 应当改策略，
        // 后者它应当去读 stderr。用状态码把它们分开。
        outcome.note?.let { body.put("reason", it) }
        // note 是跨模块属性，不能 smart cast，先取局部值。
        val outcomeNote = outcome.note
        return if (outcomeNote == null) {
            200 to body.toString()
        } else {
            val status = if (outcome.timedOut) 504 else 409
            body.put("error", str(ctx, CoreR.string.dsh_native_err_shell_failed, privReasonText(ctx, outcomeNote)))
            status to body.toString()
        }
    }

    /**
     * 执行一次无障碍读/写。
     *
     * 与 [shellExec] 同样是「闸门都过了才走到这里」：档位（关/读/读写）、严格程度下的确认、
     * 服务是否开着，都已经在前面判定过。这一层只负责调用 [DshA11y] 并把结果按协议返回。
     */
    /**
     * 虚拟屏那一族的转发口。
     *
     * 与 `a11yExec` 同样的角色：把「子系统的工具面」挡在这个文件之外，好让
     * 这个文件只管能力档、严格程度、审计与分派。真正的实现与它的取舍见 [DshDisplay]。
     */
    private fun display(
        ctx: Context,
        method: String,
        path: String,
        params: Map<String, String>,
    ): Pair<Int, String> = DshDisplay.handle(ctx, method, path, params)

    private fun a11yExec(ctx: Context, action: String, params: Map<String, String>): Pair<Int, String> {
        val body = try {
            when (action) {
                "tree" -> {
                    val raw = DshA11y.snapshot(
                        maxDepth = params["depth"]?.toIntOrNull() ?: DshA11y.MAX_DEPTH,
                        maxNodes = params["max"]?.toIntOrNull() ?: DshA11y.MAX_NODES,
                    )
                    // 扩展拦截点：无障碍树是**最贵的一份返回**（动辄上万字，全进模型上下文），
                    // 而它的信息密度很低。给扩展一次裁剪/精简的机会，是这块最值得做的优化点。
                    //
                    // 扩展读 "tree"（原始 JSON），可以写回精简版。写回的内容就是模型看到的。
                    //
                    // 注意 snapshot 返回的是 **JSONObject**（不是字符串）：写回也必须是 JSONObject，
                    // 因为下游 `a11yExec` 的返回值会被直接序列化进响应体，换成字符串会二次转义。
                    if (ExtensionHookRegistry.has(ExtensionHookRegistry.Point.A11Y_DUMP_TREE)) {
                        val payload = mutableMapOf<String, Any?>("tree" to raw, "depth" to params["depth"])
                        ExtensionHookRegistry.runBefore(ExtensionHookRegistry.Point.A11Y_DUMP_TREE, payload)
                        (payload["tree"] as? JSONObject) ?: raw
                    } else {
                        raw
                    }
                }
                "tap" -> {
                    val x = params["x"]?.toFloatOrNull()
                    val y = params["y"]?.toFloatOrNull()
                    if (x == null || y == null) {
                        return 400 to err(str(ctx, CoreR.string.dsh_native_err_a11y_bad_args), "bad_args")
                    }
                    DshA11y.tap(x, y, params["ms"]?.toLongOrNull() ?: 60L)
                }
                "click" -> {
                    val target = text(params["target"])
                        ?: return 400 to err(str(ctx, CoreR.string.dsh_native_err_a11y_bad_args), "bad_args")
                    DshA11y.click(target, text(params["class"]), params["index"]?.toIntOrNull() ?: 0)
                }
                "swipe" -> {
                    val nums = listOf("x1", "y1", "x2", "y2").map { params[it]?.toFloatOrNull() }
                    if (nums.any { it == null }) {
                        return 400 to err(str(ctx, CoreR.string.dsh_native_err_a11y_bad_args), "bad_args")
                    }
                    DshA11y.swipe(nums[0]!!, nums[1]!!, nums[2]!!, nums[3]!!, params["ms"]?.toLongOrNull() ?: 300L)
                }
                "text" -> {
                    val value = params["text"]
                        ?: return 400 to err(str(ctx, CoreR.string.dsh_native_err_a11y_bad_args), "bad_args")
                    DshA11y.setText(
                        value,
                        text(params["target"]),
                        text(params["class"]),
                        params["index"]?.toIntOrNull() ?: 0,
                    )
                }
                "global" -> {
                    val act = text(params["action"])
                        ?: return 400 to err(str(ctx, CoreR.string.dsh_native_err_a11y_bad_args), "bad_args")
                    DshA11y.global(act)
                }
                "screenshot" -> DshA11y.screenshot(ctx)
                else -> JSONObject().put("ok", false).put("reason", "unknown_action")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "无障碍调用失败: " + e.message)
            JSONObject().put("ok", false).put("reason", "a11y_failed")
        }
        body.put("action", action)
        return if (body.optBoolean("ok")) 200 to body.toString() else 409 to body.toString()
    }

    private fun riskId(risk: PrivRisk): String = when (risk) {
        PrivRisk.READONLY -> "readonly"
        PrivRisk.WRITE -> "write"
        PrivRisk.DANGEROUS -> "dangerous"
    }

    /** 把通道侧的拒绝理由翻成给用户/agent 看的一句话（reason 码本身另给）。 */
    private fun privReasonText(ctx: Context, reason: String): String = str(
        ctx,
        when (reason) {
            "no_channel" -> CoreR.string.dsh_native_priv_reason_no_channel
            "root_unavailable" -> CoreR.string.dsh_native_priv_reason_root_unavailable
            "adb_root_disabled" -> CoreR.string.dsh_native_priv_reason_adb_root
            "adb_write_disabled" -> CoreR.string.dsh_native_priv_reason_adb_write
            "runtime_missing" -> CoreR.string.dsh_native_priv_reason_runtime
            "root_lost" -> CoreR.string.dsh_native_priv_reason_root_lost
            "channel_lost" -> CoreR.string.dsh_native_priv_reason_channel_lost
            "timeout" -> CoreR.string.dsh_native_priv_reason_timeout
            // 「选了通道但还没就绪」三种：文案必须说清下一步做什么，
            // 否则用户看到的是一句像是故障的提示
            PrivilegedShell.REASON_ROOT_UNVERIFIED -> CoreR.string.dsh_native_priv_reason_root_unverified
            PrivilegedShell.REASON_SHIZUKU_UNAUTHORIZED -> CoreR.string.dsh_native_priv_reason_shizuku_unauthorized
            PrivilegedShell.REASON_ADB_UNPAIRED -> CoreR.string.dsh_native_priv_reason_adb_unpaired
            else -> CoreR.string.dsh_native_priv_reason_unknown
        },
    )

    /**
     * 能力的**本地化**名字，用在给用户看的报错里。
     *
     * 不直接用 [Cap.id]：那是协议 id（`clipboard`），报错里该出现的是「剪贴板」。
     * 名字表放 UI 层（`nativeCapTitleRes`）会让 dsh 包依赖 ui 包，所以这里单独映射。
     */
    private fun capName(ctx: Context, cap: Cap): String = str(
        ctx,
        when (cap) {
            Cap.NOTIFY -> CoreR.string.dsh_native_cap_notify
            Cap.FULL_SCREEN_NOTIFY -> CoreR.string.dsh_native_cap_full_screen_notify
            Cap.TOAST -> CoreR.string.dsh_native_cap_toast
            Cap.VIBRATE -> CoreR.string.dsh_native_cap_vibrate
            Cap.TORCH -> CoreR.string.dsh_native_cap_torch
            Cap.CLIPBOARD -> CoreR.string.dsh_native_cap_clipboard
            Cap.INTENT -> CoreR.string.dsh_native_cap_intent
            Cap.DEVICE -> CoreR.string.dsh_native_cap_device
            Cap.MEDIA -> CoreR.string.dsh_native_cap_media
            Cap.MIC -> CoreR.string.dsh_native_cap_mic
            Cap.CAMERA -> CoreR.string.dsh_native_cap_camera
            Cap.TTS -> CoreR.string.dsh_native_cap_tts
            Cap.CALENDAR -> CoreR.string.dsh_native_cap_calendar
            Cap.CONTACTS -> CoreR.string.dsh_native_cap_contacts
            Cap.LOCATION -> CoreR.string.dsh_native_cap_location
            Cap.PHONE -> CoreR.string.dsh_native_cap_phone
            Cap.SENSORS -> CoreR.string.dsh_native_cap_sensors
            Cap.NETWORK -> CoreR.string.dsh_native_cap_network
            Cap.VOLUME -> CoreR.string.dsh_native_cap_volume
            Cap.SETTINGS -> CoreR.string.dsh_native_cap_settings
            Cap.INSTALL -> CoreR.string.dsh_native_cap_install
            Cap.USAGE -> CoreR.string.dsh_native_cap_usage
            Cap.SAF -> CoreR.string.dsh_native_cap_saf
            Cap.SMS -> CoreR.string.dsh_native_cap_sms
            Cap.SHELL -> CoreR.string.dsh_native_cap_shell
            Cap.A11Y -> CoreR.string.dsh_native_cap_a11y
            Cap.DISPLAY -> CoreR.string.dsh_native_cap_display
        },
    )

    /**
     * 这次请求算不算「写」。
     *
     * 多数端点看路径就够，但 `/native/shell` 必须看**命令本身**：`dumpsys window` 是读，
     * `settings put` 是写。按路径一刀切成写，会让「读」档位连 `getprop` 都用不了 ——
     * 而那一档存在的意义正是「只允许看」。
     */
    private fun isWriteRequest(method: String, path: String, params: Map<String, String>): Boolean =
        when {
            // 只读的那一个动作单列：读屏不改变任何东西，不该被写档位挡住
            path == "/native/a11y/tree" -> false
            path.startsWith("/native/a11y/") -> true
            // 同理：虚拟屏里「看」与「动手」的差别正是这一档存在的意义。截图与查询是读；
            // 建会话、点击、滑动、按键、启动 App、停止服务端都是写。
            path == "/native/display/status" || path == "/native/display/screenshot" -> false
            path.startsWith("/native/display/") -> true
            path == "/native/shell" ->
                PrivilegedShell.riskOf(params["cmd"].orEmpty()) != PrivRisk.READONLY
            else -> isWriteRequest(method, path)
        }

    private fun isWriteRequest(method: String, path: String): Boolean = when {
        path == "/native/notify" || path == "/native/notify/system" || path == "/native/notify/full-screen" -> true
        path == "/native/sms/send" -> true
        path == "/native/toast" || path == "/native/vibrate" -> true
        path == "/native/share" || path == "/native/open" -> true
        path == "/native/mic/record" || path == "/native/camera/photo" -> true
        path == "/native/mic/start" || path == "/native/mic/stop" -> true
        path.startsWith("/native/tts/") && path != "/native/tts/voices" -> true
        path == "/native/clipboard" && method == "POST" -> true
        path == "/native/calendar/create" -> true
        path == "/native/shell" -> true
        path.startsWith("/native/a11y/") && path != "/native/a11y/tree" -> true
        path == "/native/volume" && method == "POST" -> true
        path == "/native/ringer" -> true
        path.startsWith("/native/settings/") && method == "POST" -> true
        else -> false
    }

    private fun capOf(path: String): Cap? = when (path) {
        "/native/notify", "/native/notify/list", "/native/notify/system" -> Cap.NOTIFY
        "/native/notify/full-screen" -> Cap.FULL_SCREEN_NOTIFY
        "/native/toast" -> Cap.TOAST
        "/native/vibrate" -> Cap.VIBRATE
        "/native/torch" -> Cap.TORCH
        "/native/clipboard" -> Cap.CLIPBOARD
        "/native/share", "/native/open", "/native/dial" -> Cap.INTENT
        "/native/device" -> Cap.DEVICE
        "/native/media/list", "/native/media/read" -> Cap.MEDIA
        "/native/mic/record", "/native/mic/start", "/native/mic/stop" -> Cap.MIC
        "/native/camera/photo" -> Cap.CAMERA
        "/native/tts/speak", "/native/tts/file", "/native/tts/voices" -> Cap.TTS
        "/native/calendar/list", "/native/calendar/create" -> Cap.CALENDAR
        "/native/contacts/list" -> Cap.CONTACTS
        "/native/location" -> Cap.LOCATION
        "/native/phone/info" -> Cap.PHONE
        "/native/sensors/list", "/native/sensors/read" -> Cap.SENSORS
        "/native/network" -> Cap.NETWORK
        "/native/volume", "/native/ringer" -> Cap.VOLUME
        "/native/settings",
        "/native/settings/brightness",
        "/native/settings/timeout",
        "/native/settings/rotation" -> Cap.SETTINGS
        "/native/install" -> Cap.INSTALL
        "/native/usage/list" -> Cap.USAGE
        "/native/sms/list", "/native/sms/send" -> Cap.SMS
        "/native/shell" -> Cap.SHELL
        "/native/a11y/tree",
        "/native/a11y/tap",
        "/native/a11y/click",
        "/native/a11y/swipe",
        "/native/a11y/text",
        "/native/a11y/global",
        "/native/a11y/screenshot" -> Cap.A11Y
        "/native/display/status",
        "/native/display/session",
        "/native/display/screenshot",
        "/native/display/tap",
        "/native/display/swipe",
        "/native/display/key",
        "/native/display/launch",
        "/native/display/stop" -> Cap.DISPLAY
        // SAF 桥的端点挂在 [DshFsBridge] 的 `/saf/…` socket 上、不经本 handle 分发，但它们
        // 的权限判定落在 Cap.SAF —— 列在这里让「路径 → 能力」只有一处权威，也让
        // check-native-caps 的「每个 Cap 都有端点」成立。真正的 gate 见 [safGate]。
        "/saf/sources", "/saf/list", "/saf/stat", "/saf/read",
        "/saf/write", "/saf/mkdir", "/saf/delete", "/saf/move", "/saf/copy" -> Cap.SAF
        else -> null
    }

    /**
     * SAF 桥（`/saf/…`，挂在 [DshFsBridge] 的 socket 上）的准入判定。
     *
     * 端点虽然在 DshFsBridge 的 socket 上，但「允不允许」这件事的权威仍在这里：读 App 侧
     * prefs 的 [Cap.SAF] 档位，**不信任容器**传来的任何东西。返回 null = 放行；非空 = 直接
     * 回这个错误（状态码 + JSON）。
     *
     * - 总开关关 → 403 disabled；
     * - [Cap.SAF] 档位 OFF → 403 cap_disabled；
     * - 写端点（write/mkdir/delete/move/copy）要读写档，不足 → 403 write_disabled；
     * - 读端点（sources/list/stat/read）要读档，不足 → 403 read_disabled。
     *
     * 读 `effectiveAccess`（含「仅本次」提升）与原生能力同构 —— 虽然 SAF 没有自己的提权弹窗，
     * 用的是持久档位，但走同一个入口保证口径一致。
     */
    fun safGate(ctx: Context, method: String, path: String): Pair<Int, String>? {
        if (!enabled(ctx)) return 403 to err(str(ctx, CoreR.string.dsh_native_err_disabled), "disabled")
        val cap = Cap.SAF
        if (!capEnabled(ctx, cap)) {
            return 403 to err(str(ctx, CoreR.string.dsh_native_err_cap_disabled, capName(ctx, cap)), "cap_disabled")
        }
        return if (safWriteRequest(path)) {
            if (canWrite(ctx, cap)) null
            else 403 to err(str(ctx, CoreR.string.dsh_native_err_write_disabled, capName(ctx, cap)), "write_disabled")
        } else {
            if (canRead(ctx, cap)) null
            else 403 to err(str(ctx, CoreR.string.dsh_native_err_read_disabled, capName(ctx, cap)), "read_disabled")
        }
    }

    /**
     * SAF 端点是不是「写」。读：sources / list / stat / read；其余（write / mkdir / delete /
     * move / copy）都是写。与 [isWriteRequest] 对 `/native/…` 的分法同构 —— 读的归读档、
     * 动的归读写档。
     */
    private fun safWriteRequest(path: String): Boolean = when (path) {
        "/saf/sources", "/saf/list", "/saf/stat", "/saf/read" -> false
        else -> true
    }

    /**
     * 给 SAF 桥记一条审计（与 `/native/…` 共用 `native-capability.jsonl`）。
     *
     * DshFsBridge 执行完 `/saf/…` 之后回调这里，让 SAF 操作与原生能力调用进同一份记录 ——
     * 否则「经 SAF 代办动了别的应用私有目录」这件事在记录里凭空消失。
     */
    fun safAudit(
        ctx: Context,
        method: String,
        path: String,
        params: Map<String, String>,
        response: Pair<Int, String>,
    ) {
        audit(ctx, method, path, params, Cap.SAF, params["reason"].orEmpty(), response)
    }

    private fun capabilitiesJson(ctx: Context): Pair<Int, String> {
        val on = enabled(ctx)
        val caps = JSONObject()
        // 这一趟顺手清掉的过期配额要反映到提示词事实里，否则提示词会一直宣称「有一份仅本次
        // 授权」直到下一次状态变化 —— 而 agent 查 caps 恰恰就是在确认这件事。
        val onceBefore = onceStore.size
        val once = onceGrants()
        if (once.size != onceBefore) runCatching { DshHostPrompt.writeFacts(ctx.applicationContext) }
        for (cap in Cap.entries) {
            val (available, why) = availability(ctx, cap)
            val entry = JSONObject()
                .put("enabled", on && capEnabled(ctx, cap))
                .put("access", if (on) access(ctx, cap).id else Access.OFF.id)
                .put("supportsWrite", supportsWrite(cap))
                .put("accessOptions", org.json.JSONArray(accessOptions(cap).map { it.id }))
                .put("available", available)
                .put("reason", why)
            // 「仅本次」单独一个字段而不是并进 access：它是**一次**调用，不是当前级别。
            // 并进去 agent 会以为这项已经开了，然后连着调第二次撞 403。
            once[cap]?.let { entry.put("once", it.id) }
            caps.put(cap.id, entry)
        }
        val pending = DshElevationRequests.pending.value
        val last = DshElevationRequests.last.value
        return 200 to JSONObject()
            .put("ok", true)
            .put("bridgeEnabled", on)
            .put("foreground", isForeground(ctx))
            // 特殊权限单独一段：它们申请不到，agent 该做的是提示用户去系统页开，
            // 而不是反复撞 403
            .put("special", DshSystemCtl.specialPermissions(ctx))
            // 提权申请的状态：agent 靠这三个字段判断「该等、该重试、还是该换个办法」，
            // 不用靠猜 403 到底是「用户拒绝」还是「还没看见」。
            .put(
                "pending",
                pending?.let {
                    JSONObject()
                        .put("cap", it.cap.id)
                        .put("access", it.access.id)
                        .put("reason", it.reason)
                        .put("msLeft", DshElevationRequests.remainingMs(it))
                        .put("command", it.command ?: JSONObject.NULL)
                } ?: JSONObject.NULL,
            )
            .put(
                "lastElevation",
                last?.let {
                    JSONObject()
                        .put("cap", it.cap.id)
                        .put("access", it.access.id)
                        .put("decision", it.decision.id)
                        .put("agoMs", (System.currentTimeMillis() - it.atMs).coerceAtLeast(0L))
                } ?: JSONObject.NULL,
            )
            // 特权通道：**没有通道就是 null**，不是 `{"channel":"none"}` —— 提示词只在
            // 这一段非空时才告诉 agent 「你能提权」，而「设备上根本没有提权途径」这件事
            // 说与不说都是让 agent 去骚扰用户。
            .put("elevation", elevationJson(ctx))
            // 只读清单只对 shell 有意义，且只有启用时才给 —— 关着的能力不必描述细节
            .put("shellReadonly", if (accessMap(ctx)[Cap.SHELL] != Access.OFF) JSONArray(PrivilegedShell.readonlyCommands()) else JSONArray())
            .put("caps", caps)
            .toString()
    }

    /**
     * 当前特权通道的事实：走哪条、能拿到什么身份、严格程度如何。
     *
     * 四个字段都是 agent 决定「要不要走特权这条路」必需的信息：uid 决定它能读什么，
     * canRoot 决定 `--su` 有没有意义，restrictMode 决定这次调用会不会还要用户点头
     * （开着时清单里的能力每次都会，所以别把十件事拆成十条命令）。
     */
    internal fun elevationJson(ctx: Context): Any {
        val reach = PrivilegedShell.reach(ctx) ?: return JSONObject.NULL
        return JSONObject()
            .put("channel", PrivilegedShell.channelId(reach.channel))
            .put("uid", reach.uid)
            .put("canRoot", reach.canRoot)
            // ready=false 时不能说「你有特权」：用户选了通道但还没点过刷新权限 / 还没授权，
            // 这时候 agent 该做的是提醒用户，而不是发一条注定失败的调用
            .put("ready", reach.ready)
            .put("reason", reach.reason ?: JSONObject.NULL)
            // 首选不可用而落到了别的通道：不说的话，agent 会以为「用户选的就是这条」
            .put("fellBackFrom", reach.selected?.let { PrivilegedShell.channelId(it) } ?: JSONObject.NULL)
            // 现在只有一个开关：限制模式。旧字段叫 strictness，语义已经不存在（见 PrivPolicy）
            .put("restrictMode", PrivPolicy.restrictMode(ctx))
    }

    // ────────────────────────── 能力实现 ──────────────────────────
    //
    // 已抽到 [NativeCapRouter]（:ext-device-caps）。保留同名转发，调用点零改动。
    // handle() 仍在本对象里，只做「解析 + 按 path 分派」，实现全在路由对象。

    internal fun neededAccess(cap: Cap, method: String, path: String, params: Map<String, String>) : Access = NativeCapRouter.neededAccess(cap, method, path, params)
    internal fun levelCovers(have: Access, need: Access) : Boolean = NativeCapRouter.levelCovers(have, need)
    internal fun insufficient(ctx: Context, cap: Cap, method: String, path: String, params: Map<String, String>) : Access? = NativeCapRouter.insufficient(ctx, cap, method, path, params)
    internal fun osGap(ctx: Context, cap: Cap, access: Access) : OsGap? = NativeCapRouter.osGap(ctx, cap, access)
    internal fun awaitOsGap(ctx: Context, cap: Cap, access: Access, gap: OsGap) : Boolean = NativeCapRouter.awaitOsGap(ctx, cap, access, gap)
    internal fun specialLabel(ctx: Context, special: Special) : String = NativeCapRouter.specialLabel(ctx, special)
    internal fun permissionLabel(ctx: Context, permission: String) : String = NativeCapRouter.permissionLabel(ctx, permission)
    internal fun bounded(raw: String?) : String? = NativeCapRouter.bounded(raw)
    internal fun requestElevation(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.requestElevation(ctx, params)
    internal fun auditResult(body: String): String = if (body.length <= AUDIT_RESULT_MAX_CHARS) : String = NativeCapRouter.auditResult(body)
    internal fun privAuditExtra(ctx: Context, cap: Cap, decision: String) : JSONObject = NativeCapRouter.privAuditExtra(ctx, cap, decision)
    internal fun channelLabel(ctx: Context, channel: PermissionManager.Channel) : String = NativeCapRouter.channelLabel(ctx, channel)
    internal fun shellArg(value: String) : String = NativeCapRouter.shellArg(value)
    internal fun cancelSystemNotification(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.cancelSystemNotification(ctx, params)
    internal fun fullScreenNotify(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.fullScreenNotify(ctx, params)
    internal fun notificationList(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.notificationList(ctx, params)
    internal fun smsSend(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.smsSend(ctx, params)
    internal fun notify(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.notify(ctx, params)
    internal fun cancelNotify(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.cancelNotify(ctx, params)
    internal fun ensureChannel(ctx: Context) = NativeCapRouter.ensureChannel(ctx)
    internal fun toast(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.toast(ctx, params)
    internal fun vibrate(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.vibrate(ctx, params)
    internal fun vibrator(ctx: Context) : Vibrator? = NativeCapRouter.vibrator(ctx)
    internal fun clipboardSet(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.clipboardSet(ctx, params)
    internal fun clipboardGet(ctx: Context) : Pair<Int, String> = NativeCapRouter.clipboardGet(ctx)
    internal fun clipboard(ctx: Context) : ClipboardManager? = NativeCapRouter.clipboard(ctx)
    internal fun share(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.share(ctx, params)
    internal fun open(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.open(ctx, params)
    internal fun dial(ctx: Context, params: Map<String, String>) : Pair<Int, String> = NativeCapRouter.dial(ctx, params)
    internal fun startChooser(ctx: Context, intent: Intent) : Pair<Int, String> = NativeCapRouter.startChooser(ctx, intent)
    internal fun backgroundActivityDenied(ctx: Context) : Pair<Int, String> = NativeCapRouter.backgroundActivityDenied(ctx)
    internal fun device(ctx: Context) : Pair<Int, String> = NativeCapRouter.device(ctx)

    // ────────────────────────── 媒体与麦克风 ──────────────────────────

    /**
     * 为什么媒体与录音的结果**落到容器里**，而不是流式回给调用方。
     *
     * 这两个端点和其它端点的形状不同：它们的产物是二进制，而 [handle] 的返回是
     * `状态码 to JSON`。要流式回传就得把 socket 一路传进来，与 [DshFsBridge] 的
     * 响应生命周期纠缠在一起（那条路上已经踩过一次：写了头就再也改不回 JSON 错误）。
     *
     * 换个思路：容器的 rootfs 本来就是本 App 的私有目录，写它**不需要任何存储权限**。
     * 于是把字节落到 `rootfs/tmp/...`，把**容器内**路径回给调用方，agent 直接用普通
     * 文件工具读 —— 少一条二进制通道，也少一次 base64 膨胀。
     */
    private const val STAGE_DIR_NAME = "dsh-native"

    /** 暂存区里保留的文件数上限：超出就删最旧的，别让 rootfs 被无声吃满。 */
    private const val STAGE_KEEP = 32

    internal const val DEFAULT_MEDIA_LIMIT = 50
    internal const val MAX_MEDIA_LIMIT = 500

    /** 单次取媒体的字节上限：容器里读它还要再占一份，64MB 已经很宽松。 */
    private const val MAX_MEDIA_BYTES = 64L * 1024 * 1024

    private const val DEFAULT_RECORD_MS = 5_000L

    /**
     * 录音时长上限。
     *
     * 30 秒不是协议限制（`soTimeout` 只管阻塞读，写响应前handler 慢多久都不会被它掐断），
     * 而是三件事的交集：请求占着一条连接线程直到录完；用户必须一直把应用留在前台，
     * 否则后半段全是静音；`agent` 想要更长的录音应该分多段。真需要更长再谈。
     */
    private const val MAX_RECORD_MS = 30_000L

    /**
     * 录音互斥。
     *
     * `MediaRecorder` 抢的是全局音频输入：第二个请求会在 `start()` 抛
     * `IllegalStateException`，而两个请求都已经建好文件 —— 报错的那个留下一个 0 字节
     * 残骸，成功的那个时长莫名变短。用一个标志直接回 409，比事后清理干净。
     */
    private val recording = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 会话式录音（`mic/start` → `mic/stop`）的状态；同一时刻最多一个。
     *
     * 为什么要与 [recording] 分开存：那条标志只回答「有没有人在录」，而 stop 需要拿到
     * **哪一个**录音器（以及它写到哪个文件），才能优雅地 `stop()` 把 MP4 的 moov 写下去。
     */
    private class MicSession(
        val id: String,
        val out: File,
        var recorder: MediaRecorder?,
        val startedAt: Long,
    )

    /** 当前会话；**读写都在 [micLock] 里**（HTTP 线程与看门狗线程并发）。 */
    private var micSession: MicSession? = null

    /** 上一次会话的结果（id → 响应体），留给「stop 来晚一步」的客户端；下一次 start 清掉。 */
    private var micLast: Pair<String, JSONObject>? = null

    private val micLock = Any()

    internal fun stageDir(ctx: Context): File =
        File(DshEnv.tmpDir(ctx), STAGE_DIR_NAME).apply { mkdirs() }

    /** 暂存目录对应的**容器内**路径（rootfs/tmp → /tmp）。 */
    internal fun stageGuestPath(name: String): String = "/tmp/$STAGE_DIR_NAME/$name"

    /** 只保留最近 [STAGE_KEEP] 个文件。 */
    internal fun trimStage(dir: File) {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
                ?: return
            val excess = files.size - STAGE_KEEP
            if (excess > 0) files.take(excess).forEach { it.delete() }
        }
    }

    /** 文件名里只留安全字符：这个名字会拼进容器路径。 */
    internal fun safeName(raw: String, fallbackExt: String): String {
        val cleaned = raw.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("").trim('.', '_')
        val name = cleaned.ifEmpty { "media" }
        return if (name.contains('.')) name.take(96) else "${name.take(90)}.$fallbackExt"
    }

    private fun mediaTypeOf(raw: String?): PermissionUtils.MediaType? =
        PermissionUtils.MediaType.entries.firstOrNull { it.id == (raw ?: "image") }

    private fun mediaCollection(type: PermissionUtils.MediaType) = when (type) {
        PermissionUtils.MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        PermissionUtils.MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        PermissionUtils.MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

    /**
     * 列媒体库。
     *
     * 走 MediaStore 而不是直接遍历 `/sdcard/DCIM`：后者需要「所有文件访问」，而
     * `READ_MEDIA_*` 只授权前者。返回里带 `granted` —— 用户可能只给了照片，
     * agent 得能看出「音频不是没有，是没授权」。
     */
    private fun mediaList(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val type = mediaTypeOf(params["type"])
            ?: return 400 to err(str(ctx, CoreR.string.dsh_native_err_bad_media_type), "bad_type")
        if (!PermissionUtils.hasMediaPermission(ctx, type)) {
            return 403 to err(
                str(ctx, CoreR.string.dsh_native_err_media_type_denied, type.id),
                "no_media_permission",
            )
        }
        val limit = (params["limit"]?.toIntOrNull() ?: DEFAULT_MEDIA_LIMIT)
            .coerceIn(1, MAX_MEDIA_LIMIT)
        val nameLike = text(params["q"])

        val cols = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cols += MediaStore.MediaColumns.RELATIVE_PATH
        }

        val where = if (nameLike != null) "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" else null
        val args = if (nameLike != null) arrayOf("%$nameLike%") else null
        val order = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        val entries = JSONArray()
        val queryResult = runCatching {
            ctx.contentResolver.query(
                mediaCollection(type), cols.toTypedArray(), where, args, order,
            )?.use { c ->
                val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                val relIdx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext() && entries.length() < limit) {
                    val o = JSONObject()
                        .put("id", if (idIdx >= 0) c.getLong(idIdx) else -1L)
                        .put("name", if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "")
                        .put("size", if (sizeIdx >= 0) c.getLong(sizeIdx) else -1L)
                        // MediaStore 的 DATE_MODIFIED 是**秒**，不是毫秒
                        .put("mtime", if (dateIdx >= 0) c.getLong(dateIdx) * 1000L else 0L)
                        .put("mime", if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else "")
                    if (relIdx >= 0) o.put("dir", c.getString(relIdx) ?: "")
                    entries.put(o)
                }
            }
        }
        queryResult.onFailure { e ->
            Log.w(TAG, "media 查询失败: ${e.message}")
            return 500 to err(
                str(ctx, CoreR.string.dsh_native_err_media_query, e.message ?: ""),
                "query_failed",
            )
        }

        val granted = JSONArray()
        for (t in PermissionUtils.grantedMediaTypes(ctx)) granted.put(t.id)
        return 200 to JSONObject()
            .put("ok", true)
            .put("type", type.id)
            .put("granted", granted)
            .put("entries", entries)
            .toString()
    }

    /**
     * 取一条媒体的字节，落到容器暂存区。
     *
     * 用 `openInputStream(content://…)` 而不是拼 `_DATA` 路径：分区存储下后者可能
     * 根本读不到，而 ContentResolver 走的正是 `READ_MEDIA_*` 授权的那条路。
     */
    private fun mediaRead(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val type = mediaTypeOf(params["type"])
            ?: return 400 to err(str(ctx, CoreR.string.dsh_native_err_bad_media_type), "bad_type")
        if (!PermissionUtils.hasMediaPermission(ctx, type)) {
            return 403 to err(
                str(ctx, CoreR.string.dsh_native_err_media_type_denied, type.id),
                "no_media_permission",
            )
        }
        val id = params["id"]?.toLongOrNull()
            ?: return 400 to err(str(ctx, CoreR.string.dsh_native_err_missing_id), "missing_id")
        val uri = ContentUris.withAppendedId(mediaCollection(type), id)

        var displayName = "media"
        var declaredSize = -1L
        runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    displayName = c.getString(0) ?: displayName
                    declaredSize = c.getLong(1)
                }
            }
        }
        // SIZE 已经超限就不必开流了；SIZE 缺失（-1）时靠下面边写边数兜底
        if (declaredSize > MAX_MEDIA_BYTES) {
            return 400 to err(
                str(ctx, CoreR.string.dsh_native_err_media_too_big, MAX_MEDIA_BYTES / (1024 * 1024)),
                "too_large",
            )
        }

        val fallbackExt = when (type) {
            PermissionUtils.MediaType.IMAGE -> "jpg"
            PermissionUtils.MediaType.VIDEO -> "mp4"
            PermissionUtils.MediaType.AUDIO -> "m4a"
        }
        val dir = stageDir(ctx)
        val out = File(dir, "${id}_${safeName(displayName, fallbackExt)}")
        var copied = 0L
        // MediaStore 的 SIZE 可能缺失或不准（查询失败时是 -1），所以边写边数；
        // 超限与读失败要分开报，不然「文件太大」会显示成「宿主读不了」。
        var overflow = false
        val ok = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(out).use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_MEDIA_BYTES) {
                            overflow = true
                            return@runCatching false
                        }
                        o.write(buf, 0, n)
                    }
                }
                true
            } ?: false
        }.getOrElse { e ->
            Log.w(TAG, "media 读取失败: ${e.message}")
            false
        }
        if (!ok) {
            out.delete()
            return if (overflow) {
                400 to err(
                    str(ctx, CoreR.string.dsh_native_err_media_too_big, MAX_MEDIA_BYTES / (1024 * 1024)),
                    "too_large",
                )
            } else {
                500 to err(str(ctx, CoreR.string.dsh_native_err_media_read), "read_failed")
            }
        }
        trimStage(dir)
        return 200 to JSONObject()
            .put("ok", true)
            .put("path", stageGuestPath(out.name))
            .put("bytes", copied)
            .put("name", displayName)
            .toString()
    }

    /**
     * 录一段音，落到容器暂存区。
     *
     * 前台限制不是我们加的：Android 9 起没有前台界面/前台服务类型的进程录音**只会拿到
     * 静音**，不报错。我们的前台服务是 specialUse 类型，它不授予麦克风 —— 所以后台请求
     * 直接回 409，而不是交一段几百 KB 的静音出去。
     */
    private fun micRecord(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!isForeground(ctx)) return backgroundMicDenied(ctx)
        if (!recording.compareAndSet(false, true)) {
            return 409 to err(str(ctx, CoreR.string.dsh_native_err_mic_busy), "already_recording")
        }
        try {
            return doRecord(ctx, params)
        } finally {
            recording.set(false)
        }
    }

    private fun doRecord(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val ms = (params["ms"]?.toLongOrNull() ?: DEFAULT_RECORD_MS).coerceIn(500L, MAX_RECORD_MS)
        val out = File(stageDir(ctx), "rec_${System.currentTimeMillis()}.m4a")

        val started = startMic(ctx, out)
        val recorder = started.recorder ?: return micFailed(ctx, started)

        runCatching { Thread.sleep(ms) }
        // 收尾（stop → 落盘 → 校验 → 后台则丢弃）与 /native/mic/stop 共用
        return finishMic(ctx, recorder, out, ms, id = null)
    }

    /** 录音器启动结果：失败时带上阶段（建对象 / start）与原始异常。 */
    private class MicStart(val recorder: MediaRecorder?, val code: String, val cause: Throwable?)

    /**
     * 建好并启动录音器；失败时清掉半成品文件。
     *
     * 两个阶段的文案不同（`recorder_failed` 带上原始 message、`record_failed` 不带），
     * 所以失败要分阶段返回 —— 合成一句「就是不行」会让「构造器就炸了」与「麦克风被别的
     * 应用占着、start() 抛」看起来一模一样。
     */
    private fun startMic(ctx: Context, out: File): MicStart {
        @Suppress("DEPRECATION")
        val recorder = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx)
            else MediaRecorder()
        }.getOrElse { e ->
            Log.w(TAG, "录音器创建失败: ${e.message}")
            return MicStart(null, "recorder_failed", e)
        }

        val okStart = runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(64_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(out.absolutePath)
            recorder.prepare()
            recorder.start()
            true
        }.getOrElse { e ->
            Log.w(TAG, "录音启动失败: ${e.message}")
            runCatching { recorder.release() }
            out.delete()
            false
        }
        return if (okStart) MicStart(recorder, "", null) else MicStart(null, "record_failed", null)
    }

    /** 录音启动失败 → 状态码 + JSON（`mic/record` 与 `mic/start` 共用同一组文案）。 */
    private fun micFailed(ctx: Context, started: MicStart): Pair<Int, String> =
        if (started.code == "recorder_failed") {
            500 to err(
                str(ctx, CoreR.string.dsh_native_err_mic_init, started.cause?.message ?: ""),
                started.code,
            )
        } else {
            500 to err(str(ctx, CoreR.string.dsh_native_err_mic_start), "record_failed")
        }

    /**
     * 录音的收尾：stop → 落盘 → 校验 →（中途切到后台则丢弃）。
     *
     * `mic/record` 与 `mic/stop` 走**同一条**尾巴，只有 [ms] 的来源不同：前者按请求的
     * 时长、后者按 start 到现在的实测值。
     */
    private fun finishMic(
        ctx: Context,
        recorder: MediaRecorder?,
        out: File,
        ms: Long,
        id: String?,
    ): Pair<Int, String> {
        // stop() 在「一帧都没录到」时会抛，此时产物是个坏文件，必须删掉再报错
        val stopped = runCatching { recorder?.stop(); true }.getOrElse { e ->
            Log.w(TAG, "录音停止失败: ${e.message}")
            false
        }
        runCatching { recorder?.release() }
        if (!stopped || !out.isFile || out.length() == 0L) {
            out.delete()
            return 500 to err(str(ctx, CoreR.string.dsh_native_err_mic_empty), "record_empty")
        }
        // 录完再确认一次前台：中途切走的那段是静音，交出去等于交半份假数据
        if (!isForeground(ctx)) {
            out.delete()
            return backgroundMicDenied(ctx)
        }
        trimStage(stageDir(ctx))
        val body = JSONObject()
            .put("ok", true)
            .put("path", stageGuestPath(out.name))
            .put("bytes", out.length())
            .put("ms", ms)
        if (id != null) body.put("id", id)
        return 200 to body.toString()
    }

    /** 会话 id：够随机（两个并发客户端不会撞），也够短（要打进日志与命令行）。 */
    private fun nextMicId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    /**
     * 会话式录音的第一半：开始录、**立刻**返回会话 id。
     *
     * 与 [micRecord] 的区别是「时长谁定」：`mic/record` 按请求的 ms 录满再答，请求要
     * 占着一条连接直到录完；这里由调用方在需要的时候 `mic/stop`。按键说话这类交互既要
     * **零切断**（不必按时长切段，段间就没有那截静默），也要**即时停止**（stop 立刻收尾，
     * `MediaRecorder.stop()` 会把 MP4 的 moov 写下去 —— 这正是「提前终止会得到坏文件」
     * 的反面）。
     *
     * 看门狗：[MAX_RECORD_MS] 到了会自己 stop 并把文件留在暂存区，所以客户端崩了也不会
     * 把录音器一直开着（结果留给来晚一步的 [micStop]）。
     */
    private fun micStart(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        if (!isForeground(ctx)) return backgroundMicDenied(ctx)
        if (!recording.compareAndSet(false, true)) {
            return 409 to err(str(ctx, CoreR.string.dsh_native_err_mic_busy), "already_recording")
        }
        val out = File(stageDir(ctx), "rec_${System.currentTimeMillis()}.m4a")
        val started = startMic(ctx, out)
        val recorder = started.recorder
        if (recorder == null) {
            recording.set(false)
            return micFailed(ctx, started)
        }
        val session = MicSession(nextMicId(), out, recorder, System.currentTimeMillis())
        synchronized(micLock) {
            micSession = session
            micLast = null
        }
        val watchdog = Thread {
            runCatching { Thread.sleep(MAX_RECORD_MS) }
            synchronized(micLock) {
                val current = micSession
                if (current != null && current.id == session.id) {
                    Log.i(TAG, "录音会话超时，自动停止: ${session.id}")
                    stopMicSessionLocked(ctx, current)
                }
            }
        }
        watchdog.isDaemon = true
        watchdog.name = "dsh-mic-watchdog"
        watchdog.start()
        return 200 to JSONObject()
            .put("ok", true)
            .put("id", session.id)
            .put("path", stageGuestPath(out.name))
            .put("maxMs", MAX_RECORD_MS)
            .toString()
    }

    /**
     * 会话式录音的第二半：优雅停止并交出文件。
     *
     * 来晚一步（看门狗已按 [MAX_RECORD_MS] 收尾）时把**那一次的结果**再给一遍，而不是
     * 报错：录到的东西不该因为客户端慢了一拍就丢掉。结果只保留到下一次 start。
     */
    private fun micStop(ctx: Context, params: Map<String, String>): Pair<Int, String> {
        val id = text(params["id"])
            ?: return 400 to err(str(ctx, CoreR.string.dsh_native_err_missing_param, "id"), "missing_id")
        synchronized(micLock) {
            val session = micSession
            if (session != null) {
                return if (session.id == id) stopMicSessionLocked(ctx, session)
                else 409 to err(str(ctx, CoreR.string.dsh_native_err_mic_no_session), "bad_session")
            }
            val late = micLast?.takeIf { it.first == id }
            return if (late != null) 200 to late.second.toString()
            else 409 to err(str(ctx, CoreR.string.dsh_native_err_mic_no_session), "no_session")
        }
    }

    /** 停一次会话并落盘。调用方必须持有 [micLock]（HTTP 线程与看门狗都会进来）。 */
    private fun stopMicSessionLocked(ctx: Context, session: MicSession): Pair<Int, String> {
        micSession = null
        recording.set(false)
        val result = finishMic(
            ctx,
            session.recorder,
            session.out,
            System.currentTimeMillis() - session.startedAt,
            session.id,
        )
        session.recorder = null
        if (result.first == 200) {
            runCatching { micLast = session.id to JSONObject(result.second) }
        }
        return result
    }

    private fun backgroundMicDenied(ctx: Context): Pair<Int, String> =
        409 to err(str(ctx, CoreR.string.dsh_native_err_mic_background), "not_foreground")

    // ────────────────────────── 工具 ──────────────────────────

    /**
     * 进程是否有可见 Activity。
     *
     * 用 `getMyMemoryState` 而不是 ProcessLifecycleOwner：前者可以在任意线程读，而
     * 桥接请求跑在自己的连接线程上。前台服务给的是 IMPORTANCE_FOREGROUND_SERVICE(125)，
     * 只有真的有 Activity 在前面才是 IMPORTANCE_FOREGROUND(100) —— 正好是需要的区分。
     */
    internal fun isForeground(ctx: Context): Boolean = runCatching {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /**
     * 在主线程跑一段并等它结束（有超时，不让桥接线程被 UI 卡死）。
     *
     * @return true 表示 block 在超时内真的跑完了；false 表示主线程被卡住、[MAIN_WAIT_MS] 内没跑完
     *   —— 调用方**必须**按失败处理，否则会出现「剪贴板假装是空的」「toast 假装发了」这类
     *   本类契约明令禁止的静默假成功。
     */
    internal fun onMain(block: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return true
        }
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        return runCatching { latch.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    /** 文本参数：空串归为「没给」，并截断到 [MAX_TEXT_LEN]。 */
    internal fun text(v: String?): String? {
        if (v.isNullOrEmpty()) return null
        return if (v.length > MAX_TEXT_LEN) v.substring(0, MAX_TEXT_LEN) else v
    }

    internal fun okJson(): String = JSONObject().put("ok", true).toString()

    /**
     * 取一条本地化文案。
     *
     * 这些串会经 `dsh-native` 的 stderr 出现在**用户**眼前（agent 也读它，但 agent 认的是
     * [err] 里的 `reason`，不是这段人话），所以要跟随应用语言。走 `appString` 而不是
     * `ctx.getString`：这里的 Context 是 Application 的，而应用内语言在 API 33 以下
     * 只作用于 Activity。
     *
     * 取不到就退回资源名：桥不能因为一次资源查找失败而回 500，那会把「参数写错了」
     * 变成「宿主坏了」。
     */
    internal fun str(ctx: Context, resId: Int, vararg args: Any): String = runCatching {
        ctx.appString(resId, *args)
    }.getOrElse { ctx.resources.getResourceEntryName(resId) ?: "error" }

    /** 错误体带机器可读的 [reason]：agent 需要据此决定是重试还是提示用户。 */
    internal fun err(msg: String, reason: String): String = JSONObject()
        .put("ok", false)
        .put("error", msg)
        .put("reason", reason)
        .toString()

    private fun methodNotAllowed(ctx: Context, method: String, path: String): Pair<Int, String> =
        404 to err(
            str(ctx, CoreR.string.dsh_native_err_unknown_endpoint, method, path),
            "unknown_endpoint",
        )
}
