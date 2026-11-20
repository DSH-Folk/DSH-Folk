package me.bmax.apatch.backup

/**
 * 模块占位。
 *
 * 保留一个真实的 Kotlin 文件而不是空目录：AGP 对"没有任何源文件"的 library 模块
 * 不会产出变体，`:app` 依赖它时会在配置阶段就失败（报错说找不到变体，
 * 而不是"模块为空"——那个错很难懂）。
 *
 * 本模块的实际内容在后续批次迁入，迁完后删掉这个文件。
 */
internal object BackupModuleMarker {
    const val MODULE = "ext-backup"
}
