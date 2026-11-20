@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "DSH-Folk"
include(":app")
// 扩展 API 契约模块：见 ext-api/build.gradle.kts 顶部的说明（第三方扩展要能依赖它）
include(":ext-api")

// ── 架构重构（Batch A）：目标拓扑 ──
//
// :ext-api       公共扩展契约（第三方 .dshx 直接依赖）
// :core-kernel   不可变宿主底座
// :ext-*         编译期内建扩展（随 APK 打包、默认全开）
// :app           极薄装配壳
//
// 依赖方向严格单向：:ext-* → :core-kernel → :ext-api，:app → 全部。
// 禁止横向（:ext-a → :ext-b）与反向（任何模块 → :app）。
include(":core-kernel")
include(":ext-workflow")
include(":ext-display")
include(":ext-userscripts")
include(":ext-backup")
include(":ext-device-caps")
include(":ext-theme-media")
