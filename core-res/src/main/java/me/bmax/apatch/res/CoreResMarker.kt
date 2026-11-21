package me.bmax.apatch.res

/**
 * 本模块只提供资源，没有逻辑。
 *
 * 保留一个真实 Kotlin 文件：AGP 对"没有任何源文件"的 library 模块不产出变体，
 * 依赖它的模块会在配置阶段失败（报错说找不到变体，而不是"模块为空"，很难懂）。
 */
internal object CoreResMarker {
    const val MODULE = "core-res"
}
