package me.bmax.apatch.dsh

/**
 * 标记「只给本仓库其它 Gradle 子模块用，不是第三方 API」。
 *
 * ## 为什么需要这个标注
 *
 * Kotlin 的 `internal` 是**模块**级（一个 Gradle 子模块 = 一个编译单元）。
 * 文件从 `:app` 下沉到 `:core-kernel` 后，原本模块内的 `internal` 成员对 `:app`
 * 就不可见了（`Cannot access 'fun parseStamp': it is internal`）。
 *
 * 放宽成 `public` 会让人以为这是对外契约。加这个标注表明：它只是跨子模块可见，
 * **不是**给第三方扩展用的 API。
 *
 * ## 为什么不担心扩大第三方 API 面
 *
 * 第三方 `.dshx` 扩展只依赖 `:ext-api`，并且 `proguard-rules.pro` 只 keep
 * `me.bmax.apatch.dsh.ext.**`。`:core-kernel` 里的东西既不在扩展的编译类路径上，
 * 也不会被 R8 保留 —— 第三方根本够不着。
 *
 * 编译期不强制（没有 lint 规则拦），靠 code review 与这个 KDoc 维持约定。
 */
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
annotation class InternalKernelApi
