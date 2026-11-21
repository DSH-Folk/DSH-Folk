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
    // namespace **不能**和 :app 相同。
    //
    // AGP 会做唯一性校验：两个模块用同一个 namespace 时 manifest 合并直接失败
    // （"Namespace 'me.bmax.apatch' is used in multiple modules"）。
    //
    // namespace 只决定 R 类与 BuildConfig 的**生成包名**，不影响源文件的实际包路径 ——
    // 迁进来的代码仍在 me.bmax.apatch.dsh.* 下，照常编译。
    // 代价：本模块内不能用 me.bmax.apatch.R（那是 :app 的 R）。真需要字符串时
    // 通过 seam 或注入的 Context 拿，不要在底座里引用宿主资源。
    namespace = "me.bmax.apatch.kernel"
    compileSdk = androidCompileSdkVersion

    defaultConfig {
        minSdk = androidMinSdkVersion
    }

    buildFeatures {
        compose = true
        // IDshShellService.aidl 从 :app 迁过来了（Checkpoint 1）。
        // AIDL 生成本来就该在底层：Shizuku 用户服务是全仓库共用的 IPC 契约，
        // 不是 UI 层的东西。不开这个开关，DshShizukuShell 在 :core-kernel 里
        // 找不到生成的 IDshShellService 接口。
        aidl = true
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
    implementation(project(":core-res"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    // 运行时/容器/日志这套用到的 AndroidX 组件
    implementation(libs.androidx.appcompat)
    // DshWebCompat 要用 WebViewCompat 查内核特性（:ext-userscripts 依赖它）。
    implementation(libs.androidx.webkit)
    // OverlayLifecycleOwner 要用 LifecycleRegistry（:ext-display 的投屏浮层靠它）。
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    // OverlayLifecycleOwner 实现 SavedStateRegistryOwner（投屏浮层的 Compose 树需要）。
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.com.github.topjohnwu.libsu.core)
    implementation(libs.com.github.topjohnwu.libsu.service)
    // Shizuku：PermissionManager 判权限时要用 rikka.shizuku.Shizuku
    implementation(libs.dev.rikka.shizuku.api)
    implementation(libs.dev.rikka.shizuku.provider)

    // 单测：Batch B 起每个模块都要有真正的测试（熔断、迁移幂等、路径穿越、加密往返、
    // 工作流环检测、能力路由）。整个仓库此前零单测，这是从 0 开始的那一步。
    testImplementation(libs.junit)
}
