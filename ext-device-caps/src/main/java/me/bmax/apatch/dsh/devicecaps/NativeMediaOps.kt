package me.bmax.apatch.dsh.devicecaps

import android.content.Context
import android.content.ContentUris
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import me.bmax.apatch.dsh.DshEnv
import me.bmax.apatch.dsh.NativeBridgeUtil
import me.bmax.apatch.util.PermissionUtils
import me.bmax.apatch.util.appString
import me.bmax.apatch.res.R as CoreR
import org.json.JSONArray
import org.json.JSONObject

/**
 * 媒体库读写与麦克风录音。
 *
 * ## 为什么从 DshNativeBridge 抽出来
 *
 * 这两项都是「拿系统资源、落文件、回 JSON」，与分发逻辑无关，却占了桥的近五分之一。
 * 抽出来后桥只留路由，能力实现可以独立演进。
 *
 * ## 暂存目录是共享契约
 *
 * [stageDir] / [stageGuestPath] / [trimStage] 不只用在这里 —— 虚拟屏（:ext-display）
 * 也往同一个目录写帧。所以它们是本对象的公开成员，桥保留同名转发。
 */
object NativeMediaOps {

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

    const val DEFAULT_MEDIA_LIMIT = 50
    const val MAX_MEDIA_LIMIT = 500

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

    fun stageDir(ctx: Context): File =
        File(DshEnv.tmpDir(ctx), STAGE_DIR_NAME).apply { mkdirs() }

    /** 暂存目录对应的**容器内**路径（rootfs/tmp → /tmp）。 */
    fun stageGuestPath(name: String): String = "/tmp/$STAGE_DIR_NAME/$name"

    /** 只保留最近 [STAGE_KEEP] 个文件。 */
    fun trimStage(dir: File) {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }
                ?: return
            val excess = files.size - STAGE_KEEP
            if (excess > 0) files.take(excess).forEach { it.delete() }
        }
    }

    /** 文件名里只留安全字符：这个名字会拼进容器路径。 */
    fun safeName(raw: String, fallbackExt: String): String {
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
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_bad_media_type), "bad_type")
        if (!PermissionUtils.hasMediaPermission(ctx, type)) {
            return 403 to NativeBridgeUtil.err(
                NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_media_type_denied, type.id),
                "no_media_permission",
            )
        }
        val limit = (params["limit"]?.toIntOrNull() ?: DEFAULT_MEDIA_LIMIT)
            .coerceIn(1, MAX_MEDIA_LIMIT)
        val nameLike = NativeBridgeUtil.text(params["q"])

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
            return 500 to NativeBridgeUtil.err(
                NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_media_query, e.message ?: ""),
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
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_bad_media_type), "bad_type")
        if (!PermissionUtils.hasMediaPermission(ctx, type)) {
            return 403 to NativeBridgeUtil.err(
                NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_media_type_denied, type.id),
                "no_media_permission",
            )
        }
        val id = params["id"]?.toLongOrNull()
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_missing_id), "missing_id")
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
            return 400 to NativeBridgeUtil.err(
                NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_media_too_big, MAX_MEDIA_BYTES / (1024 * 1024)),
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
                400 to NativeBridgeUtil.err(
                    NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_media_too_big, MAX_MEDIA_BYTES / (1024 * 1024)),
                    "too_large",
                )
            } else {
                500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_media_read), "read_failed")
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
        if (!NativeBridgeUtil.isForeground(ctx)) return backgroundMicDenied(ctx)
        if (!recording.compareAndSet(false, true)) {
            return 409 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_mic_busy), "already_recording")
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
            500 to NativeBridgeUtil.err(
                NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_mic_init, started.cause?.message ?: ""),
                started.code,
            )
        } else {
            500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_mic_start), "record_failed")
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
            return 500 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_mic_empty), "record_empty")
        }
        // 录完再确认一次前台：中途切走的那段是静音，交出去等于交半份假数据
        if (!NativeBridgeUtil.isForeground(ctx)) {
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
        if (!NativeBridgeUtil.isForeground(ctx)) return backgroundMicDenied(ctx)
        if (!recording.compareAndSet(false, true)) {
            return 409 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_mic_busy), "already_recording")
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
        val id = NativeBridgeUtil.text(params["id"])
            ?: return 400 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_missing_param, "id"), "missing_id")
        synchronized(micLock) {
            val session = micSession
            if (session != null) {
                return if (session.id == id) stopMicSessionLocked(ctx, session)
                else 409 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_mic_no_session), "bad_session")
            }
            val late = micLast?.takeIf { it.first == id }
            return if (late != null) 200 to late.second.toString()
            else 409 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_mic_no_session), "no_session")
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
        409 to NativeBridgeUtil.err(NativeBridgeUtil.str(ctx, CoreR.string.dsh_native_err_mic_background), "not_foreground")

}
