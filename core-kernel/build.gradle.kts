@file:Suppress("UnstableApiUsage")

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 不可变宿主底座：容器引擎、环境路径、安全熔断、权限守卫、日志、迁移。
 *
 * ## 插件声明只有 agp.lib
 *
 * AGP 9 起 android 插件已内置 Kotlin 支持，再应用 `org.jetbrains.kotlin.android`
 * 会被直接判错。这条与 :ext-api 一致（那边是踩过 CI 才对的）。
 *
 * ## 依赖方向（单向无环）
 *
 * 只依赖 :ext-api 与 :core-kernel。**禁止**横向依赖其它 :ext-* 模块 ——
 * 需要别的模块的能力时，走 :ext-api 的 Seam 接口 + ExtensionServiceRegistry.resolve()。
 */
plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin.compose.compiler)
}

val androidCompileSdkVersion: Int by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra

android {
    // namespace 必须是 me.bmax.apatch —— 迁进来的代码在 me.bmax.apatch.dsh.* /
    // me.bmax.apatch.util.* 包下，namespace 只影响 R 类与 BuildConfig 的生成包名。
    // 写成 me.bmax.apatch.kernel 的话这些源文件不在 namespace 下，AGP 仍能编译，
    // 但 R 类会生成到 kernel 包、而代码里 import 的是 me.bmax.apatch.R —— 找不到。
    namespace = "me.bmax.apatch"
    compileSdk = androidCompileSdkVersion

    defaultConfig {
        minSdk = androidMinSdkVersion
    }

    buildFeatures {
        compose = true
    }
}

// 与 :app 完全一致的 JDK/Kotlin 目标：产物进同一个 APK，目标版本不一致会在
// dex 合并时报「class file has wrong version」。
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

dependencies {
    implementation(project(":ext-api"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    // 运行时/容器/日志这套用到的 AndroidX 组件
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.com.github.topjohnwu.libsu.core)
    implementation(libs.com.github.topjohnwu.libsu.service)

    // 单测：Batch B 起每个模块都要有真正的测试（熔断、迁移幂等、路径穿越、加密往返、
    // 工作流环检测、能力路由）。整个仓库此前零单测，这是从 0 开始的那一步。
    testImplementation(libs.junit)
}
