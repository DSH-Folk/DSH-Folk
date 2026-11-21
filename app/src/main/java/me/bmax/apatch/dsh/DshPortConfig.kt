package me.bmax.apatch.dsh

import kotlinx.coroutines.flow.update
import me.bmax.apatch.res.R as CoreR

/**
 * 端口、局域网、竞速通道、启动行为这几项"用户偏好"。
 *
 * ## 为什么它们在一起
 *
 * 全都是 **prefs 的读写 + 一次状态同步**：改端口要更新 `state.port`，
 * 改局域网要重启服务才生效，改竞速通道要清测速缓存。拆开写会让"改了之后
 * 忘了同步状态"变成一类反复出现的 bug。
 *
 * ## 为什么抽出来
 *
 * 这 156 行对 `DshRuntime` 其余部分几乎零依赖：只要 [DshKernelState] 的
 * Context/prefs 和 [DshRuntime.port]。Checkpoint 1 拆解的第二块。
 */
object DshPortConfig {

    /** 短名，避免每行都写 [DshKernelState]。 */
    private val K get() = DshKernelState

    // ────────────────────────── 端口占用检测 ──────────────────────────

    /** 127.0.0.1:port 是否已被某个进程监听（TCP connect 探测）。 */
    fun isPortInUse(port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 800)
            true
        }
    }.getOrDefault(false)

    /**
     * 从 [from] 起向后扫第一个空闲端口。
     *
     * 供「端口被占用 → 换一个」用；扫到 65535 还没有就回退默认端口（仍可能占用，
     * 但比拿一个越界端口强）。
     */
    fun findFreePort(from: Int = DshRuntime.port() + 1): Int {
        var p = from.coerceIn(1, 65535)
        while (p <= 65535) {
            if (!isPortInUse(p)) return p
            p++
        }
        return DshEnv.DEFAULT_PORT
    }

    /**
     * 用户对「端口被占用」作出的决定。
     *
     * [PortConflictAction.AUTO]：换一个空闲端口；[PortConflictAction.MANUAL]：用
     * [newPort]（越界或仍被占用则保留冲突标记，交由 UI 提示）；[PortConflictAction.FORCE]：
     * 不改端口、强行继续启动。
     *
     * `setPort` 只是同步 prefs 写 + state 更新，改完端口再调 [bootstrap] 重跑；
     * FORCE 则跳过端口探测、直接 [startAndAwait]。
     */
    fun resolvePortConflict(action: PortConflictAction, newPort: Int? = null) {
        if (!K.ready) return
        when (action) {
            PortConflictAction.AUTO -> {
                DshRuntime.setPort(findFreePort())
                K.stateFlow.update { it.copy(portConflict = false) }
                DshRuntime.bootstrap()
            }
            PortConflictAction.MANUAL -> {
                val p = newPort ?: return
                if (p !in 1..65535 || isPortInUse(p)) return // 保留 portConflict，UI 继续提示
                DshRuntime.setPort(p)
                K.stateFlow.update { it.copy(portConflict = false) }
                DshRuntime.bootstrap()
            }
            PortConflictAction.FORCE -> {
                // 强行启动：跳过端口探测直接拉起（用户明知端口被占仍要用）
                K.stateFlow.update { it.copy(portConflict = false) }
                scope.launch {
                    bootMutex.withLock { startAndAwait() }
                }
            }
        }
    }

    // ────────────────────────── 局域网访问 ──────────────────────────

    /** 局域网访问开关（默认关）。 */
    fun lanEnabled(): Boolean =
        ready && K.prefs().getBoolean(DshEnv.KEY_LAN, false)

    fun setLanEnabled(enabled: Boolean) {
        if (!K.ready) return
        K.prefs().edit().putBoolean(DshEnv.KEY_LAN, enabled).apply()
    }

    // ────────────────────────── 竞速通道（镜像/测速） ──────────────────────────

    /** 竞速通道的三个通道 id（见 [raceEnabled]）。 */
    const val RACE_PLUGINS = "plugins"
    const val RACE_APP_UPDATE = "app_update"
    const val RACE_RUNTIME = "runtime"

    /** 竞速通道**总开关**（默认开）。关掉时 [raceEnabled] 一律返回 false。 */
    fun raceMasterEnabled(): Boolean =
        !ready || K.prefs().getBoolean(DshEnv.KEY_RACE_MASTER, true)

    fun setRaceMasterEnabled(enabled: Boolean) {
        if (!K.ready) return
        K.prefs().edit().putBoolean(DshEnv.KEY_RACE_MASTER, enabled).apply()
    }

    /**
     * 某个通道是否启用竞速（总开关 + 该通道分开关，两者都开才生效）。
     *
     * 分开关默认开：老用户此前的行为就是「能加速就加速」，升级后不该悄悄变慢。
     */
    fun raceEnabled(channel: String): Boolean {
        if (!raceMasterEnabled()) return false
        val key = when (channel) {
            RACE_PLUGINS -> DshEnv.KEY_RACE_PLUGINS
            RACE_APP_UPDATE -> DshEnv.KEY_RACE_APP_UPDATE
            RACE_RUNTIME -> DshEnv.KEY_RACE_RUNTIME
            else -> return false
        }
        return !ready || K.prefs().getBoolean(key, true)
    }

    fun setRaceEnabled(channel: String, enabled: Boolean) {
        if (!K.ready) return
        val key = when (channel) {
            RACE_PLUGINS -> DshEnv.KEY_RACE_PLUGINS
            RACE_APP_UPDATE -> DshEnv.KEY_RACE_APP_UPDATE
            RACE_RUNTIME -> DshEnv.KEY_RACE_RUNTIME
            else -> return
        }
        K.prefs().edit().putBoolean(key, enabled).apply()
    }

    // ────────────────────────── 应用启动行为 ──────────────────────────

    /**
     * 打开 App 时是否自动启动服务（默认关）。
     *
     * 与开机自启（[DshAutostart]）分开：那是设备开机，这是用户点开应用。两者可以各自
     * 独立成立 —— 有人只在手动打开时必须自动跑起来，不想让它在后台常驻到开机。
     */
    fun autoStartOnLaunch(): Boolean =
        ready && K.prefs().getBoolean(DshEnv.KEY_AUTO_START_ON_LAUNCH, false)

    fun setAutoStartOnLaunch(enabled: Boolean) {
        if (!K.ready) return
        K.prefs().edit().putBoolean(DshEnv.KEY_AUTO_START_ON_LAUNCH, enabled).apply()
    }

    /** 服务就绪后是否自动打开 DSH 页面（默认关）。 */
    fun autoOpenWebUi(): Boolean =
        ready && K.prefs().getBoolean(DshEnv.KEY_AUTO_OPEN_WEBUI, false)

    fun setAutoOpenWebUi(enabled: Boolean) {
        if (!K.ready) return
        K.prefs().edit().putBoolean(DshEnv.KEY_AUTO_OPEN_WEBUI, enabled).apply()
    }

    /** 本机局域网 IPv4（site-local），取不到返回 null。 */
    fun lanIp(): String? = runCatching {
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (ifaces.hasMoreElements()) {
            val nif = ifaces.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is java.net.Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                    return@runCatching a.hostAddress
                }
            }
        }
        null
    }.getOrNull()
}
