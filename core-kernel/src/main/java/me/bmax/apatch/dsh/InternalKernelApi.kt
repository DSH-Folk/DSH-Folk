package me.bmax.apatch.dsh

/**
 * 标记「只给本仓库其它 Gradle 子模块用，不是第三方 API」。
 *
 * Kotlin 的 `internal` 是**模块**级（一个 Gradle 子模块 = 一个编译单元）。
 * 文件从 :app 下沉到 :core-kernel 后，原本模块内的 `internal` 成员对 :app
 * 就不可见了（`Cannot access 'fun X': it is internal`）。
 *
 * 放宽成 `public` 会让人以为这是对外契约，所以加这个标注说明：
 * 它只是跨子模块可见，**不是**给第三方扩展用的 API。
 *
 * 不担心扩大第三方 API 面：第三方 .dshx 只依赖 :ext-api，且 proguard 只 keep
 * `me.bmax.apatch.dsh.ext.**` —— :core-kernel 里的东西第三方够不着。
 */
@Retention(AnnotationRetention.SOURCE)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
annotation class InternalKernelApi
