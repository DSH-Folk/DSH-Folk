@file:Suppress("UnstableApiUsage")

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 公共资源模块：只放**跨模块共用**的资源。
 *
 * ## 为什么必须单独成一个模块
 *
 * `gradle.properties` 里 `android.nonTransitiveRClass=true` —— 每个模块只能看到**自己的** R。
 * 而 `DshRuntime`（78 处）、`DshSource`（9 处）、`ContainerRuntime`（7 处）要下沉到
 * `:core-kernel`，Batch D 的 6 个 `:ext-*` 也都要用 `R.string.dsh_*`。
 * 资源留在 `:app` 里，这些模块一个都拿不到。
 *
 * ## 单一真源
 *
 * `dsh_strings.xml` 整体搬过来（en + zh-rCN 两份），**不复制**。
 * 复制出来的第二份必然与第一份逐渐分叉，而多语言不一致是最难发现的回归
 * （只在特定语言下露出来）。
 *
 * ## 只迁 dsh_strings.xml
 *
 * `strings.xml` 留在 `:app`：它里面的 607 条只有 `:app` 自己的界面在用
 * （唯一跨模块的 `app_name` 也只有 HarnessService 与 manifest 用，都在 `:app`）。
 * 把不需要的也搬过来，等于凭空扩大公共面。
 */
plugins {
    alias(libs.plugins.agp.lib)
}

val androidCompileSdkVersion: Int by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra

android {
    // 必须与 :app 的 namespace 不同：AGP 校验唯一性，相同则 manifest 合并失败
    // （报 "Namespace 'me.bmax.apatch' is used in multiple modules"）。
    namespace = "me.bmax.apatch.res"
    compileSdk = androidCompileSdkVersion

    defaultConfig {
        minSdk = androidMinSdkVersion
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}
