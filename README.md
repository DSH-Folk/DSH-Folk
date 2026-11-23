<div align="center">

# DSH-Folk

[简体中文](./README.md) | [English](./README.en.md)

**在 Android 上运行 DeepSeek Harness 的启动器**

[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg?logo=gnu)](./LICENSE)
[![Build](https://github.com/DSH-Folk/DSH-Folk/actions/workflows/build.yml/badge.svg)](https://github.com/DSH-Folk/DSH-Folk/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/DSH-Folk/DSH-Folk?logo=github)](https://github.com/DSH-Folk/DSH-Folk/releases/latest)

</div>

DSH-Folk 将 [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh)（基于 Node.js 的编码 Agent CLI）封装为开箱即用的 Android 应用：首次启动按设备架构（`arm64-v8a` / `x86_64`）下载 Linux 容器运行时，通过 proot / proroot 启动 `dsh web`，并提供原生 WebUI、终端、插件商店、自动化工作流与扩展支持。

无需 root 或 Termux 即可使用；若设备已配置 root、Shizuku 或无线 ADB，可按需启用对应的特权能力。

## 目录

- [预览](#预览)
- [功能一览](#功能一览)
- [环境要求](#环境要求)
- [安装](#安装)
- [运行时管理](#运行时管理)
- [文档索引](#文档索引)
- [项目结构](#项目结构)
- [致谢](#致谢) · [许可证](#许可证) · [友情链接](#友情链接) · [交流](#交流)

## 预览

<div align="center">

| 主页 | 终端 |
| :---: | :---: |
| <img src="docs/screenshots/home.jpg" width="260" alt="主页：启动 / 停止、运行方式、权限通道与启动日志"> | <img src="docs/screenshots/terminal.jpg" width="260" alt="终端：容器内的真 PTY，带 ESC / TAB / CTRL / 方向键扩展键"> |
| **插件** | **设置** |
| <img src="docs/screenshots/plugins.jpg" width="260" alt="插件：已安装列表，显示下载量、star 与可更新状态"> | <img src="docs/screenshots/settings.jpg" width="260" alt="设置：常规 / 外观 / 行为 / 功能 / 安全 / 备份 / 插件 / 多媒体"> |

</div>

## 功能一览

| 页面 | 说明 |
| --- | --- |
| **主页** | 一键启动 / 停止 / 重启 DSH，查看运行状态、WebUI 地址、运行模式、权限通道与启动日志 |
| **终端** | 基于 Termux `terminal-view` 的容器 PTY 终端，带常用扩展键 |
| **插件 / 扩展** | 管理容器内 DSH 插件（内置 4000+ 条插件商店、周下载量与 star 统计、本地 `.tgz` 安装与加载自检）以及宿主 `.dshx` 应用扩展 |
| **工作流** | 可视化节点编排自动化工作流，支持按针脚类型筛选、条件分支、审批与原生能力调用 |
| **设置** | 常规 / 外观 / 行为 / 功能 / 安全 / 备份 / 插件 / 多媒体，主题体系兼容 FolkPatch（`theme.json` / `.fpt`） |

- **多语言与主题**：应用语言在 **设置 → 常规 → 语言** 独立切换（不依赖系统语言）；主题商店与 `.fpt` 导出/导入位于 **设置 → 外观**。
- **虚拟屏**：在 **设置 → 功能 → 权限管理 → 屏幕与外部界面 → 虚拟屏** 开启后，Agent 可在独立虚拟显示屏上运行并操作目标应用，不影响当前主屏使用，并支持实时画面预览与手动接管（需 root / Shizuku / 无线 ADB 之一）。
- **配置备份**：直接复用 DSH 桌面端的 `dsh-config-manager` 导出格式，手机与桌面端备份互通；凭据默认不导出，支持可选 AES-256-GCM 加密。

## 环境要求

- Android 8.0 (API 26) 及以上
- **arm64-v8a** 或 **x86_64** 设备（不支持 32 位）
- 首次启动需联网下载运行时（压缩包约 150 MB，解压后约 600 MB，建议预留 2 GB 以上空间）

首次下载运行时后会自动预装四个常用插件：`dsh-web-mobile`（移动端适配）、`dshmarket`（WebUI 插件市场）、`dsh-config-manager`（配置备份依赖）、`dsh-file-upload`（附件上传与语音输入）。预装失败不影响主服务启动，后续可在插件商店手动安装。

内置 WebUI 额外提供几项原生脚本增强（位于 **设置 → 功能 → 用户脚本**）：旧版 WebView JS API 兼容垫片、系统栏安全区内边距适配、触屏回车换行与上传错误诊断。

root / Shizuku / 无线 ADB 均为可选项且默认关闭，可在 **设置 → 安全 → 权限通道** 按需开启。

## 安装

前往 [Releases](https://github.com/DSH-Folk/DSH-Folk/releases/latest) 下载对应架构的 APK（附 `.sha256` 校验文件）：

- `DSH-Folk-<版本>-arm64-v8a.apk`：绝大多数手机与平板
- `DSH-Folk-<版本>-x86_64.apk`：Android 模拟器、Android-x86、ChromeOS

开发构建可在 [Actions](https://github.com/DSH-Folk/DSH-Folk/actions/workflows/build.yml) 的成功记录中下载工件。

应用更新通道可在 **设置 → 常规 → 更新通道** 切换（正式版 / Beta / Alpha）；容器运行时更新通道在运行时版本菜单中独立设置。应用内更新支持多镜像测速、断点续传与 `.sha256` 完整性校验。

## 运行时管理

在 **设置 → 功能 → 运行时** 卡片中操作：

- **点一下卡片**：立即检查更新，确认后下载替换（自动保留会话、插件与配置数据）。
- **长按卡片**：打开版本菜单，可切换完整版 / 精简版、正式 / 测试通道、重装当前版本、切换历史版本或导入本地 `.tar.gz` 运行时包。列表按两个滑块筛选；当前已装那一版若被筛掉，会固定在最上面，点它是重装。

> 启动日志中的 `http://127.0.0.1:3080/?token=…` 包含本机访问令牌，分享日志前请先去除。

## 文档索引

- [扩展开发指南](docs/extensions/README.md) — `.dshx` 应用扩展清单、UI 槽位、10 大强类型 Seam、19 个 Hook、启动管线与安全模型
- [架构与运行原理](docs/architecture.md) — 8 模块 Gradle 拓扑、Seam 依赖倒置、proot/proroot、rootfs 与 ELF 闭包检查
- [宿主桥接能力（dsh-fs / dsh-native）](docs/host-bridges.md) — 回环文件桥、原生能力清单、特权命令、无障碍与自助提权
- [配置备份与加密](docs/backup.md) — 备份格式、`DCA1` 加密容器、快照回退与 WebDAV 云备份
- [开机自启](docs/autostart.md) — 开机广播、无障碍保活与开机脚本方案说明
- [日志采集与脱敏](docs/logs.md) — 诊断包组成、敏感信息脱敏与时间窗口裁剪
- [构建与发布说明](docs/dev-notes.md) — 应用与运行时 CI 工作流、通道机制与静态门禁
- [自动化工作流开发记录](docs/workflow-pitfalls.md) — 跨进程契约、审批机制与 Compose 画布手势踩坑记录（**CC BY 4.0**）
- [功耗、省电与无障碍保活调研](docs/research-power-and-a11y.md) — proroot / proot 实测对比与保活策略调研
- [附件上传落盘排查记录](docs/attachment-upload-pitfalls.md) — `--link2symlink` 与原子发布兼容性分析（**CC BY 4.0**）

## 项目结构

项目按单向依赖拆分为 8 个 Gradle 模块（由 `tools/check-module-boundary.js` 在 CI 校验）：

```text
app/                装配壳：AndroidManifest、Activity / Service / Provider、主界面与设置页
ext-api/            公共扩展契约（第三方 .dshx 直接依赖）：10 个强类型 Seam、UI/Hook/SPI 注册表与启动管线
core-kernel/        不可变宿主底座：容器环境、日志、安全熔断、权限策略、Shizuku AIDL 与工具层
core-res/           跨模块共享字符串资源（单一真源）
ext-workflow/       内置扩展：自动化工作流引擎、校验、审批与节点编辑器
ext-display/        内置扩展：虚拟屏会话管理、Binder 协议与悬浮镜像窗
ext-userscripts/    内置扩展：WebUI 注入脚本管道、用户脚本管理与 GreasyFork 市场
ext-backup/         内置扩展：配置备份、AES-256-GCM 加密归档与云同步
displayserver/      独立虚拟屏服务端进程（app_process）
runtime-builder/    容器 rootfs 构建脚本与 ELF 传递依赖检查
tools/              CI 静态门禁与跨模块边界检查脚本
docs/               分主题技术文档
```

内部 Kotlin 包名保持 `me.bmax.apatch`（`applicationId` 为 `top.funcun.dshfolk`），以保持对 FolkPatch 主题与历史配置文件的向后兼容。

## 致谢

- [FolkPatch](https://github.com/LyraVoid/FolkPatch) — 本项目的 UI 基础（GPL-3.0）
- [APatch](https://github.com/bmax121/APatch) — FolkPatch 的上游项目
- [点击助手 (TouchTool)](https://github.com/mr-bogey/TouchTool) — 自动化工作流编辑器的 184 项动作目录、15 类分组、按针脚类型筛选面板及节点交互设计参考（GPL-3.0）
- [DSHA](https://github.com/DSH-APP/DSHA) — 无线 ADB 配对与容器运行方案参考
- [DSHM](https://github.com/RochelimitDawn/DSHM) — 运行时在线交付与镜像测速方案参考
- [dsh-market](https://github.com/dsh-market/dsh-market) — 插件商店目录与统计接口（MIT）
- [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) — 容器内运行的核心 Agent 程序
- [proot](https://github.com/proot-me/proot) / [proroot](https://github.com/coderredlab/proroot) / [Termux](https://github.com/termux/termux-app) — 容器执行与 PTY 终端组件
- [Shizuku](https://github.com/RikkaApps/Shizuku) — 免 root 特权通道
- [KernelSU](https://github.com/tiann/KernelSU) / [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) — 界面设计参考
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) — 虚拟显示创建与输入注入原理参考（Apache-2.0）
- [Operit](https://github.com/AAswordman/Operit) — `displayserver/` 移植自其 `tools/shower`，悬浮窗交互参考其 `VirtualDisplayOverlay`（LGPL-3.0）

> `displayserver/` 移植自 [Operit](https://github.com/AAswordman/Operit)（LGPL-3.0，源自 Apache-2.0 的 [scrcpy](https://github.com/Genymobile/scrcpy)），包名调整为 `me.bmax.apatch.display` 并移除无关界面，按 GPLv3 要求随本仓库完整开源。

## 许可证

[GNU General Public License v3.0](./LICENSE)。本项目基于 GPL-3.0 的 FolkPatch 开发，分发与二次修改须继续遵循 GPLv3 并提供完整源码。

## 友情链接

LINUX DO 开源社区 | [linux.do](https://linux.do)

## 交流

- QQ 群：[1109060326](https://qm.qq.com/q/t7HDoR5ACk)
- Issues：https://github.com/DSH-Folk/DSH-Folk/issues
