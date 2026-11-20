@file:Suppress("UnstableApiUsage")

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * 内置扩展：虚拟屏管理、DisplayServer 桥接、镜像悬浮窗与预览页。
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
    namespace = "me.bmax.apatch.display"
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
    implementation(project(":core-kernel"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
}
