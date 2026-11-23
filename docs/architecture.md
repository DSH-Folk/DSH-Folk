# 架构与运行原理

[← 返回 README](../README.md)

## 运行链路

```text
DSH-Folk (Android app)                      ← 按 ABI 拆包：arm64-v8a / x86_64
  └─ proot / proroot                        ← 打包在 APK 里的可执行 .so
       └─ Ubuntu 24.04 rootfs               ← 首次启动按架构下载
            ├─ python3                       ← 无线 ADB 配对用，已预装
            ├─ git                           ← git 源插件用，已预装
            └─ Node.js 24 + @deepseek-ai/dsh
                 └─ dsh web --port 3080      ← 默认仅监听 127.0.0.1
                      └─ 内置 WebUI / 手机浏览器
```

### Android 平台的关键适配

- **可执行二进制位置**：Android 的 `app_data_file` 带 `noexec`，仅 `nativeLibraryDir` 下的 `.so` 允许执行，因此 proot / proroot 以 `.so` 形式随 APK 分发。
- **架构限制**：[proroot](https://github.com/coderredlab/proroot) 仅提供 `arm64-v8a`，`x86_64` 设备固定使用 proot。
- **硬链接与 pnpm 兼容**：部分设备私有目录禁止 `link(2)`。应用启动时会探测硬链接支持情况，不可用时自动为 proot 开启 `--link2symlink`（proroot 默认开启），并在 profile 的 `pnpm-workspace.yaml` 中写入 `packageImportMethod: copy`，避免符号链接导致 Node `require.resolve` 找不到插件客户端包。
- **自包含 pnpm 与 git**：rootfs 固定预装自包含的 `pnpm@10.34.5` 与 `git`（支持 `github:owner/name` 插件源）。由于 rootfs 通过 `dpkg-deb -x` 异架构解包构建，构建末尾由 `runtime-builder/check-elf-closure.js` 递归校验 ELF 的 `DT_NEEDED`，防止缺失传递动态库。
- **网络与数据保护**：`dsh web` 与配置备份默认仅绑定 `127.0.0.1`，局域网访问默认关闭。替换运行时会自动暂存并恢复 `root/.dsh`、`root/.local` 与 `.l2s`，升级前仍建议先备份配置。

## Gradle 模块化分层

仓库按单向依赖拆分为 **8 个 Gradle 模块**，由 CI 中的 `tools/check-module-boundary.js` 与 `tools/check-cross-module-symbols.js` 强制校验：

```text
:app                 装配壳（AndroidManifest / Activity / Service / Compose 界面）
  ↓
:ext-*               编译期内建扩展模块（随 APK 打包，默认启用）
  ├─ :ext-workflow        工作流引擎、校验、审批与节点编辑器
  ├─ :ext-display         虚拟屏会话、Binder 协议与悬浮镜像窗
  ├─ :ext-userscripts     WebUI 注入管道、用户脚本与 GreasyFork 市场
  └─ :ext-backup          配置备份、AES-256-GCM 加密与云同步
  ↓
:core-kernel         不可变宿主底座（容器环境、日志、安全熔断、权限策略、Shizuku AIDL）
  ↓
:core-res            跨模块共享字符串资源（单一真源）
:ext-api             公共扩展契约（第三方 .dshx 直接依赖）
```

依赖规则：禁止 `:ext-*` 模块之间横向依赖，禁止任何子模块反向依赖 `:app`。跨模块能力统一通过 `:ext-api` 中的 Seam 接口交互。

### 模块规模概览

| 模块 | 源文件数 | 代码行数 |
|---|---:|---:|
| `:core-kernel` | 38 | 7,847 |
| `:ext-api` | 21 | 2,153 |
| `:ext-workflow` | 11 | 4,529 |
| `:ext-userscripts` | 4 | 1,052 |
| `:ext-display` | 5 | 2,731 |
| `:ext-backup` | 7 | 4,799 |
| `:core-res` | 1 | 11 |
| `:app` | 255 | 88,931 |

## Seam 依赖倒置机制

内建模块与第三方 `.dshx` 扩展都通过 `:ext-api` 暴露的 **10 个强类型 Seam 接口**访问宿主能力，避免反向依赖 `:app`：

```text
:ext-workflow / .dshx ──依赖──> :ext-api/RuntimeSeam <──实现── :app/HostRuntimeSeam
```

全部 10 个 Seam 接口：`RuntimeSeam`、`ShellSeam`、`FsSeam`、`DeviceSeam`、`UiSeam`、`BackupSeam`、`EventSeam`、`CapabilitySeam`、`NativeBridgeSeam`、`ThemeBackupSeam`。

宿主在 `APatchApp.onCreate` 中调用 `SeamRegistry.install(ctx, HostSeamProvider(ctx))` 注入默认实现；在安全模式熔断或早期初始化尚未就绪时，`SeamRegistry` 返回安全的降级兜底实现。

### 关于 `DshNativeBridge` 的边界决策

`DshNativeBridge`（约 2,600 行）内部由能力分发、开关判定与权限档位紧密互锁，强行按文件切分极易引入循环依赖。当前策略是保留其内部整体留在 `:app`，在其前方架设轻量的 `NativeBridgeSeam`（7 个方法），让 `:ext-workflow` 与 `:ext-display` 仅依赖接口而不耦合具体实现。
