package me.bmax.apatch.dsh.ext.seam

/**
 * 主题包导入导出的最小契约。
 *
 * ## 为什么需要它
 *
 * [DshConfigBackup]（备份管线）要能打包/还原应用主题，而主题实现在 `ui.theme`
 * 里 —— 那是一片 7454 行、33 个文件、重度依赖 Compose 的代码，且与 UI 层
 * 紧密耦合。让备份模块直接依赖它，等于把整个 UI 层拖进备份模块。
 *
 * 而备份真正需要的只有三件事：量一下主题包多大、导出成 zip、从 zip 导入。
 * 这三件事在这里收成一个接口，实现留在 `:app`。
 *
 * ## 为什么 exportTheme 收 Uri 而不是 File
 *
 * 原实现（[ThemeManager.exportTheme]）就是要 Uri：它内部走 SAF 风格的写入，
 * 接受任意 content:// 或 file://。收窄成 File 会逼实现层多做一次转换，
 * 而那层转换没有任何价值。
 */
interface ThemeBackupSeam {

    /**
     * 主题包元信息。
     *
     * 单独暴露而不是让调用方自己拼：元信息会写进主题包内的 theme.json，
     * 而「量出来的大小」与「真导出的大小」必须来自**同一份**元信息，
     * 否则两者永远差一点（备份的「是否含主题」开关就按这个大小决定默认值）。
     */
    data class ThemeMetadata(
        val name: String,
        val type: String,
        val version: String,
        val author: String,
        val description: String,
    )

    /** 当前主题打成包有多大（字节）。失败返回 -1 —— 调用方据此决定默认不勾。 */
    fun measureThemeZipBytes(ctx: android.content.Context, meta: ThemeMetadata): Long

    /** 导出主题到指定 Uri。@return 是否成功（失败不抛，调用方要能继续导出其它部分）。 */
    fun exportTheme(ctx: android.content.Context, target: android.net.Uri, meta: ThemeMetadata): Boolean

    /** 从 Uri 导入主题。@return 是否成功。 */
    fun importTheme(ctx: android.content.Context, source: android.net.Uri): Boolean
}
