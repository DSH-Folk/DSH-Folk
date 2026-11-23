# DSH-Folk 扩展开发指南

> 面向第三方扩展开发者，本文所有接口签名与行为均以当前 `:ext-api` 源码为准。

## 目录

1. [什么是扩展](#1-什么是扩展)
2. [最小可运行扩展](#2-最小可运行扩展)
3. [manifest.json 字段](#3-manifestjson-字段)
4. [API 能力清单](#4-api-能力清单)
5. [生命周期与异常隔离](#5-生命周期与异常隔离)
6. [编译与发布](#6-编译与发布)
7. [混淆与 R8 规则](#7-混淆与-r8-规则)
8. [安全模型与熔断](#8-安全模型与熔断)
9. [调试与常见问题](#9-调试与常见问题)
10. [内置扩展模块与已知边界](#10-内置扩展模块与已知边界)

---

## 1. 什么是扩展

DSH-Folk 支持三类扩展形态，运行环境与能力边界各不相同：

| 类型 | 运行位置 | 约束 | 主要用途 |
|---|---|---|---|
| DSH 插件 | 容器内 Node.js | 容器沙箱 | 扩展 DSH Agent 工具、模型与会话逻辑 |
| 用户脚本 | 内置 WebView | 浏览器沙箱 | 修改 WebUI 页面 DOM 与交互 |
| **应用扩展 (`.dshx`)** | **App 宿主进程** | **与宿主同权** | 定制界面/外壳、调用 10 大强类型 Seam、拦截宿主流程、替换 SPI、向容器注入命令 |

应用扩展是加载进宿主进程的 DEX（父 `ClassLoader` 为宿主 `classLoader`），与宿主共享进程权限。

---

## 2. 最小可运行扩展

### 目录结构

```text
<filesDir>/extensions/<id>/
    manifest.json      # 必填
    classes.dex        # 可选（纯声明式扩展可省略）
    assets/            # 可选，扩展私有资源
```

`<filesDir>` 对应 `/data/user/0/top.funcun.dshfolk/files`，`<id>` 需匹配 `^[A-Za-z0-9._-]{1,64}$` 且不能为 `.` 或 `..`。

### Hello World

`manifest.json`：

```json
{
  "id": "hello-world",
  "name": "Hello World",
  "version": "1.0.0",
  "entryClass": "com.example.hello.HelloExtension",
  "description": "在扩展页顶部添加一行提示文字",
  "declaredCaps": ["ui.slot"],
  "minAppVersion": "2.2.1"
}
```

`HelloExtension.kt`：

```kotlin
package com.example.hello

import androidx.compose.material3.Text
import me.bmax.apatch.dsh.ext.DshExtension
import me.bmax.apatch.dsh.ext.DshExtensionHost
import me.bmax.apatch.dsh.ext.ExtensionUiRegistry

class HelloExtension : DshExtension {
    override fun onLoad(host: DshExtensionHost) {
        host.log("hello 扩展已加载")
        ExtensionUiRegistry.registerSlot(
            extensionId = host.id,
            slot = ExtensionUiRegistry.Slot.EXTENSIONS_PAGE_TOP,
            op = ExtensionUiRegistry.SlotOp.INSERT_AFTER,
        ) { modifier ->
            Text("来自 hello-world 扩展", modifier = modifier)
        }
    }

    override fun onUnload() {
        // 引擎会自动撤销本扩展注册的槽位、Hook、SPI 与启动管线；
        // 如自行持有后台线程或文件句柄，请在此处释放。
    }
}
```

将 `manifest.json` 与 `classes.dex` 放入 `<filesDir>/extensions/hello-world/`（或打包为 `.dshx` 安装），在「插件 → 扩展」中启用并**重启 App** 生效。

---

## 3. manifest.json 字段

| 字段 | 必填 | 说明 | 校验规则 |
|---|---|---|---|
| `id` | 是 | 扩展唯一标识（即目录名） | `^[A-Za-z0-9._-]{1,64}$`，不可为 `.`/`..` |
| `name` | 是 | 显示名称 | 非空 |
| `version` | 是 | 版本号 | 非空（用于市场更新比对） |
| `entryClass` | 否 | 实现 `DshExtension` 的全限定类名 | 为空表示纯声明式扩展 |
| `description` | 否 | 简要说明 | — |
| `declaredCaps` | 否 | 声明的能力标签数组 | 安装/启用确认弹窗中逐条展示给用户 |
| `minAppVersion` | 否 | 最低宿主版本要求 | 宿主版本不足时跳过加载，市场也禁止安装 |

---

## 4. API 能力清单

全部公共契约位于 `:ext-api` 模块（`me.bmax.apatch.dsh.ext`），仅依赖 Jetpack Compose 与 Kotlin Coroutines。

### 4.1 入口与宿主句柄

```kotlin
interface DshExtension {
    fun onLoad(host: DshExtensionHost)
    fun onUnload() {}
    /** 容器就绪时回调（需保证幂等，重装运行时会再次触发） */
    fun onContainerReady(host: DshExtensionHost) {}
}

interface DshExtensionHost {
    val id: String
    val appContext: Context
    val fs: ExtensionFs
    fun log(message: String)
    val host: ExtensionHostSettings     // 白名单宿主开关（BROWSER_MODE / WEBVIEW_DEBUG）
    val container: ExtensionContainer   // 向容器 /usr/local/bin 安装与回收命令
    val seam: ExtensionSeams            // 推荐：强类型宿主能力 Seam 门面（Release R8 安全）
    @Deprecated("优先使用 host.seam")
    val platform: ExtensionPlatform     // 旧版反射兜底门面（Release 混淆包不保证内部方法名稳定）
}
```

### 4.2 强类型 Seam（`host.seam.*` 与 `SeamRegistry`）

`:ext-api` 提供 **10 个强类型 Seam 接口**（整包受 R8 `-keep` 保护，第三方 `.dshx` 在 Release 包中可直接调用，也可通过 `ExtensionServiceRegistry` 替换）：

| 接口 | 访问方式 | 主要能力 |
|---|---|---|
| `RuntimeSeam` | `host.seam.runtime` | 读取/订阅运行时状态、拉起容器、同步或流式执行容器 Bash 命令 |
| `ShellSeam` | `host.seam.shell` | 调用宿主 Root / Shizuku / ADB 特权命令通道 |
| `FsSeam` | `host.seam.fs` | 容器与宿主路径双向转换、读写与列举容器内文件 |
| `DeviceSeam` | `host.seam.device` | 无障碍动作、TTS 语音播报、相机授权、亮屏与电池状态查询 |
| `UiSeam` | `host.seam.ui` | 读取主题深浅色/强调色/语言、路由导航、打开 WebUI、弹 Toast |
| `BackupSeam` | `host.seam.backup` | 检查应用更新、读取当前版本（无 UI 导出/导入暂为占位实现） |
| `EventSeam` | `host.seam.event` | 订阅与发布 5 类系统事件（`RUNTIME_STATE`、`CONTAINER_READY` 等） |
| `CapabilitySeam` | `host.seam.capability` | 查询已声明能力、可用性与宿主版本号 |
| `NativeBridgeSeam` | `SeamRegistry.nativeBridge()` | 调用宿主 `/native/*` 桥接路由、前台判断与暂存目录工具 |
| `ThemeBackupSeam` | `SeamRegistry.themeBackup()` | 测量、导出与导入 `.fpt` 主题归档 |

### 4.3 容器命令与宿主开关

- `host.container.installCommand(name, script)` / `removeCommand(name)` / `installedCommands()`：往容器 `/usr/local/bin/<name>` 安装命令脚本（建议在 `onContainerReady` 中调用）；扩展停用或卸载时宿主会自动清理归属命令。
- `host.host.get(switch)` / `set(switch, on)`：读写白名单开关（`HostSwitch.BROWSER_MODE`、`HostSwitch.WEBVIEW_DEBUG`），需在 `declaredCaps` 中声明对应能力。

### 4.4 界面扩展（`ExtensionUiRegistry`）

```kotlin
ExtensionUiRegistry.registerConfigPanel(extensionId, title, content) // 推荐：独占配置面板
ExtensionUiRegistry.registerPage(extensionId, page)                  // 注册独立导航页面
ExtensionUiRegistry.registerSetting(extensionId, item)               // 向 8 大设置分类注入条目
ExtensionUiRegistry.registerSlot(extensionId, slot, op, content)     // 4 个内置槽位插入/替换/移除/包裹
ExtensionUiRegistry.overrideScreen(extensionId, screenId, content)   // 整屏接管（last-wins）
ExtensionUiRegistry.registerShell(extensionId, content)              // 接管主导航外壳（last-wins）
ExtensionUiRegistry.interceptPalette(extensionId) { scheme -> ... }  // 修改全局 Material 3 色板
```

- **配置面板约定**：每个 `extensionId` 独占一块面板，宿主负责外层滚动与高度上限，内容请直接使用 `Column`（不要再套 `LazyColumn` 或 `verticalScroll`）。
- **内置槽位**：`EXTENSIONS_PAGE_TOP`、`HOME_CARD`、`SETTINGS_FUNCTION`、`WEBUI_OVERLAY`。
- **外壳接管**：`ShellContent` 的第二个参数为宿主原有页面树，外壳只负责导航编排，原有终端、插件、设置页均可继续复用。

### 4.5 拦截器（19 个 Hook 点）与启动管线（5 阶段）

- **`ExtensionHookRegistry`**：提供 `before`、`after`、`around`（返回 `false` 拦截默认逻辑），覆盖 19 个点位：`RUNTIME_BEFORE_START`、`RUNTIME_BUILD_ARGV`、`RUNTIME_APPLY_ENV`、`RUNTIME_PROBE_READY`、`BRIDGE_BEFORE_DISPATCH`、`BRIDGE_AUDIT`、`STORAGE_CHECK_PATH`、`NATIVE_BEFORE_INVOKE`、`NATIVE_AFTER_INVOKE`、`SHELL_BEFORE_EXEC`、`SHELL_AFTER_EXEC`、`A11Y_DUMP_TREE`、`DISPLAY_START_SESSION`、`WEBUI_CONFIGURE`、`USERSCRIPTS_BUILD`、`PLUGIN_UPDATE`、`WORKFLOW_EXECUTE_NODE`、`PROMPT_RENDER`、`DIAGNOSTICS_REDACT`。
- **`ExtensionBootPipeline`**：支持按优先级挂载 5 个启动阶段：`APP_CREATE`、`SPLASH`（可写 `ctx["hold"] = true` 延长启动页）、`AUTH`（可写 `ctx["blocked"] = true` 拦截启动）、`INITIAL_ROUTE`（可写 `ctx["route"]` 指定首屏）、`AUTOSTART`（可写 `ctx["skip"] = true` 跳过自启）。单阶段总预算 200ms，超时自动跳过剩余处理器。

### 4.6 SPI 子系统替换（`ExtensionServiceRegistry`）

```kotlin
ExtensionServiceRegistry.register(extensionId, RuntimeSeam::class.java, customImpl)
```

同类型遵循后注册覆盖先注册；安全模式熔断时自动回退至宿主默认实现。

### 4.7 文件系统与日志

- `ExtensionFs` 提供 `dataDir` / `filesDir` / `rootfsDir` / `cacheDir` / `ownDir`、`prefs(name)` 及常用文件读写方法；禁止修改熔断标志文件（`isProtected == true`）。
- `ExtLog.info(msg)` 仅输出到 logcat（tag `DSH-Ext`）；`ExtLog.log(msg)` 同时写入 `dsh.log`，仅用于真正的故障上报。

---

## 5. 生命周期与异常隔离

- `onLoad(host)` 抛出异常会被捕获并自动回滚该扩展已注册的全部 SPI / Hook / UI / 启动管线，不影响宿主及其他扩展。
- Hook 回调抛异常仅记录日志并跳过本次调用；`around` 异常按放行处理。
- 处于安全模式熔断时，所有注册中心自动短路，返回默认实现。

---

## 6. 编译与发布

第三方扩展通过 Gradle composite build 依赖 `:ext-api` 编译为独立 `.dex`：

```kotlin
// settings.gradle.kts
includeBuild("../DSH-Folk") {
    dependencySubstitution {
        substitute(module("me.bmax.apatch:ext-api")).using(project(":ext-api"))
    }
}
```

打包步骤：

```bash
./gradlew :extensions:my-extension:assembleRelease
$ANDROID_HOME/build-tools/<ver>/d8 --min-api 26 --lib <android.jar> --output out/ classes.jar
zip my-extension-1.0.0.dshx manifest.json classes.dex
```

上架官方市场仓库 [dsh-folk-plugins](https://github.com/DSH-Folk/dsh-folk-plugins) 时，在 `index.json` 中填写 `downloadUrl`、`sha256` 与 `sizeBytes`。

---

## 7. 混淆与 R8 规则

宿主 release 构建开启 `isMinifyEnabled` 与 `-repackageclasses`，`app/proguard-rules.pro` 已保留 `:ext-api`（`me.bmax.apatch.dsh.ext.**`）、`kotlin.**`、`kotlinx.coroutines.**`、`androidx.compose.**`、`androidx.lifecycle.**` 与 `androidx.activity.**`。

由于 debug 构建不混淆，发布扩展前**务必使用 release 或 alpha/beta 签名包实测**。

---

## 8. 安全模型与熔断

应用扩展与宿主同权，宿主提供三道自救防线：

1. **逃生熔断文件**：在以下任一路径创建 `disable_extensions`（或 `.disable_extensions` / `disable_extensions.txt`），冷启动即跳过全部第三方扩展：
   - `<filesDir>/extensions/.disable_extensions`
   - `Android/data/top.funcun.dshfolk/files/disable_extensions`
   - `/sdcard/Download/DSH-Folk/.disable_extensions`
2. **连续崩溃 3 次自动熔断**：自动写入熔断标志文件。
3. **独立崩溃恢复页**：提供「禁用全部扩展并重启」按钮，且该页面不加载任何扩展 UI。

---

## 9. 调试与常见问题

查看日志：`adb logcat -s DSH-Ext`。

| 现象 | 常见原因 | 排查方式 |
|---|---|---|
| 扩展页显示「清单损坏」 | `manifest.json` 缺失或字段不合法 | 检查 `id` 字符集及 `name`/`version` 非空 |
| 日志提示 `入口类没有实现 DshExtension` | 扩展把 `:ext-api` 打包进了 `classes.dex` | 改为仅编译期依赖宿主 `:ext-api` |
| 日志提示 `要求宿主 >= X` | `minAppVersion` 高于当前宿主 | 升级宿主或调整 `minAppVersion` |
| 仅 Release 包报找不到方法 | 使用了已弃用的 `host.platform` 反射调用内部类 | 迁移至 `host.seam.*` 强类型接口 |
| 扩展启用后界面未变化 | 处于安全模式或被后加载扩展覆盖 | 查看 `DSH-Ext` 日志中的熔断与外壳决策记录 |

---

## 10. 内置扩展模块与已知边界

- **内置扩展模块**：`:ext-workflow`、`:ext-display`、`:ext-userscripts`、`:ext-backup` 随 APK 编译打包并默认启用，与第三方 `.dshx` 共享同一套 `:ext-api` Seam 契约。
- **已知边界**：
  - 暂不支持热加载（安装/启停需重启生效），暂未引入第三方数字签名校验（依赖市场 `sha256` 校验与信任确认）。
  - 受 Android 静态清单限制，`.dshx` 无法动态新增 `Activity` / `Service` 或新的系统权限。
  - `BackupSeam.exportConfig` / `importConfig` 目前为占位返回，配置备份暂走宿主界面流程。
