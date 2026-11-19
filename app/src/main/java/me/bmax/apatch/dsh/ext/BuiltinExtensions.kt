package me.bmax.apatch.dsh.ext

import android.content.Context
import java.io.File

/**
 * 随 APK 分发的内置扩展。
 *
 * ## 为什么需要这个机制
 *
 * 有些能力只有扩展能表达（比如「声明我要用 WebView 调试通道」），而扩展市场的索引
 * 是**空的** —— 用户按提示去商店里找，什么也找不到。让用户自己打包一个 zip 再装进来，
 * 对一个"本来该开箱能用"的功能来说门槛太高。
 *
 * 所以内置扩展随 APK 走：首次启动时写到扩展目录、**默认启用**，用户能在扩展页看到它、
 * 也能像普通扩展一样禁用或卸载。不是隐藏在代码里的特例 —— 那样用户既看不见也关不掉。
 *
 * ## 与市场扩展的区别
 *
 * 只有「谁把它放进来」这一点：内置的由这里写入，市场装的由 ExtensionMarket 写入。
 * 落盘位置、manifest 格式、启用状态、卸载路径全部相同 —— 用户视角没有两种扩展。
 */
object BuiltinExtensions {

    /**
     * 浏览器桥扩展的 id。
     *
     * 它声明 `host:webview-debug`，于是**启用它就等于打开 WebView 调试通道**。
     * 这是"能力由扩展声明驱动"那条设计的落点：不需要宿主开关。
     */
    const val BROWSER_BRIDGE_ID = "dsh-browser-bridge"

    /**
     * 内置扩展清单：id → (manifest 文本, 说明)。
     *
     * manifest 用文本而不是数据类：它与市场里的扩展包**逐字相同**，
     * 写成两套结构迟早会漂移（市场的 parse 认的字段和这里造的不是同一批）。
     */
    private data class Builtin(val manifestJson: String)

    private val ALL: Map<String, Builtin> = mapOf(
        BROWSER_BRIDGE_ID to Builtin(
            """
            {
              "id": "dsh-browser-bridge",
              "name": "浏览器桥",
              "version": "1.0.0",
              "description": "让容器里的 dsh 通过 DevTools 协议操作应用内的浏览器：读页面、执行 JS、截屏。启用本扩展即开启 WebView 调试通道。",
              "declaredCaps": ["host:webview-debug", "host:browser-mode"],
              "minAppVersion": "2.2.0"
            }
            """.trimIndent(),
        ),
    )

    /**
     * 首次启动时把内置扩展写到磁盘。
     *
     * ## 几条必须遵守的规则
     *
     * 1. **目录已存在就完全不动**：用户可能改过 manifest、或正打算卸载它。
     *    每次启动都覆写会让"我禁用了它，重启又回来了"变成真的。
     * 2. **首次写入时默认启用**：它随 APK 来、声明的能力在信任确认里可见，
     *    用户的"装上这个应用"就是同意。但用户之后可以关掉，见第 1 条。
     * 3. 失败只记日志：这是启动路径，不能因为写不了扩展目录就打不开应用。
     */
    fun ensureInstalled(ctx: Context) {
        val app = ctx.applicationContext
        for ((id, builtin) in ALL) {
            runCatching {
                val dir = ExtensionLayout.dirOf(app, id)
                // 已经装过（无论启用与否）就一律不动。
                if (dir.isDirectory) return@runCatching
                if (!dir.mkdirs()) return@runCatching
                File(dir, "manifest.json").writeText(builtin.manifestJson, Charsets.UTF_8)
                ExtensionRegistry.setEnabled(app, id, true)
                ExtLog.log("已安装内置扩展：" + id)
            }.onFailure {
                ExtLog.log("安装内置扩展 " + id + " 失败：" + it.message)
            }
        }
    }
}
