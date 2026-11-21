# 它是怎么跑起来的

[← 返回 README](../README.md)

## 它是怎么跑起来的

```
DSH-Folk (Android app)                      ← 按 ABI 拆包：arm64-v8a / x86_64
  └─ proot / proroot                        ← 打包在 APK 里的可执行 .so
       └─ Ubuntu 24.04 rootfs               ← 首次启动时在线下载（arm64 或 x86_64）
            ├─ python3                       ← 无线 ADB 配对用，已预装
            ├─ git                            ← git 源插件用，已预装
            └─ Node.js 24 + @deepseek-ai/dsh
                 └─ dsh web --port 3080      ← 默认只监听 127.0.0.1
                      └─ 手机浏览器 / 应用内打开
```

几个不得不这么做的地方：

- Android 的 `app_data_file` 带 **noexec**，只有 `nativeLibraryDir` 里的 `.so` 可执行，所以 proot / proroot 以 `.so` 形式打包进 APK。
- proroot 只有 arm64 版本（[上游](https://github.com/coderredlab/proroot) 只发布 arm64-v8a），所以 x86_64 设备上运行方式固定为 proot，
  设置里那一项会禁选并说明原因。
- 部分设备的私有目录禁止 `link(2)`（真机实测报 `AccessDeniedException`），应用会先探测硬链接是否可用，不可用时给 proot 加 `--link2symlink`；
  proroot 则无条件启用它。而 pnpm 正是用 `link()` 从内容存储装包 —— 链接一旦被改写成符号链接，
  Node 的 `require.resolve` 做 realpath 就会解析进内容存储的扁平哈希目录，插件声明的 `./lib/client.cjs` 再也拼不出来
  （表现是装完插件 `dsh web` 报 `MissingClientBundleError`）。所以这种环境下会给 profile 的 `pnpm-workspace.yaml`
  写上 `packageImportMethod: copy`，让 pnpm 复制真实文件。代价是内容存储的去重失效，容器体积会大一些。
- `dsh plugin` 只负责调 pnpm，PATH 上没有 pnpm 就直接 exit 127。运行时因此固定带自包含的
  `pnpm@10.34.5`（不跟 `latest`：pnpm 12 的 npm 包改成了依赖 postinstall 下载原生二进制的启动器，
  与异架构构建必须使用的 `--ignore-scripts` 冲突），按 `package.json.bin` 重建 `/usr/local/bin`
  下的链接，并在打包前用 `node bin/pnpm.cjs --version` 自检 —— 这一层只由运行时负责，App 不再
  在设备上给 rootfs 打补丁（那只会把「运行时是坏的」藏起来）。修复版以 r3 重新发布在**两个**通道上，
  所以存量用户（含仍停在 0.1.2-r2 的 stable 用户）能直接更新运行时脱困，不必重装。
- 插件目录里超过一半的条目是 `github:owner/name` 安装规格，pnpm 解析它要 `git ls-remote`，所以 git 也预装进了 rootfs。
  注意 rootfs 是用 `dpkg-deb -x` 纯解包装出来的（不跑 maintainer script —— 它们要在目标架构上执行），
  **dpkg 的依赖关系没人替我们解** —— 包列表写漏一个传递依赖，构建期一切正常，到设备上 exec 那一刻才报
  `cannot find libxxx.so.N`。所以构建末尾有一步 `check-elf-closure.js`：从 git-core / perl 扩展 / python3
  出发递归解析 ELF 的 `DT_NEEDED`，任何 SONAME 找不到提供者就让构建失败（并断言入口是目标架构）。
- `dsh web` 默认只绑定回环地址；配置备份走的也是同一个回环 HTTP 接口。局域网访问是设置里一个默认关闭的开关。
- **1.9.0 把 dsh 0.1.5 推上正式通道**（`runtime-latest` = `0.1.5-rc.1-ubuntunoble-r4`，当年那版老命名；2026-11 起改成 `ubuntu-noble`，见 dev-notes；要求 App ≥ 1.9.0）。
  测试通道此前已经在同一份 rootfs 上跑了一整轮 beta，但**测试人数不足**：升级 dsh 会改写会话与插件数据，
  而 App 侧保留的那几条路径（`root/.dsh`、`root/.local`、`.l2s`）只保得住文件、保不住上游格式变化。
  所以更新运行时前请先备份 —— 更新说明弹窗的第一句就是这句警示，用错误色显示。


---

## 模块化：谁在哪一层

代码不是一坨 `:app`，而是按**依赖方向单向**切的 10 个 Gradle 模块。这张图是硬约束，
不是建议 —— `tools/check-module-boundary.js` 会在 CI 上检查，越界直接红。

```
:app                 装配壳（Application / Activity / Compose 界面）
  ↓
:ext-*               编译期内建扩展（随 APK 打包，默认全开）
  ├─ ext-workflow        工作流引擎（DSL 解析、节点执行、审批）
  ├─ ext-display         虚拟屏 / 显示镜像（Binder 协议 + 视频 Sink）
  ├─ ext-userscripts     用户脚本 / 脚本市场
  ├─ ext-backup          配置备份（归档格式、加密、云同步）
  ├─ ext-device-caps     设备能力（媒体、麦克风）
  └─ ext-theme-media     主题 / 背景 / 字体 / 音效（占位，待迁入）
  ↓
:core-kernel         不可变宿主底座（运行时、权限策略、环境路径、扩展加载器）
  ↓
:core-res            共享字符串资源（nonTransitiveRClass 下唯一能被所有模块看到的 R）
:ext-api             公共扩展契约（第三方 .dshx 直接依赖）
```

**禁止**横向依赖（`:ext-a → :ext-b`）与反向依赖（任何模块 → `:app`）。
需要别的模块的能力时走 `:ext-api` 的 Seam 接口。

### 当前规模

| 模块 | 文件 | 行数 |
|---|---:|---:|
| `:core-kernel` | 38 | 7847 |
| `:ext-api` | 21 | 2153 |
| `:ext-workflow` | 11 | 4529 |
| `:ext-userscripts` | 4 | 1052 |
| `:ext-display` | 5 | 2731 |
| `:ext-backup` | 7 | 4799 |
| `:ext-device-caps` | 1 | 14 |
| `:ext-theme-media` | 1 | 14 |
| `:app` | 255 | 88931 |

### Seam：依赖倒置的那一层

扩展要调宿主的能力（跑容器命令、读权限状态、发通知），但 `:ext-*` 不能依赖 `:app`
（`:app` 依赖所有扩展，反向依赖就成环了）。

解法是把「要什么」定义在 `:ext-api`，「给什么」由宿主实现：

```
:ext-workflow ──依赖──> :ext-api/RuntimeSeam  <──实现── :app/HostRuntimeSeam
```

每个 Seam 都是**极薄**的接口 —— 只暴露扩展真实调用的方法，不是把宿主的实现搬出来。
目前有 9 个：`RuntimeSeam`、`ShellSeam`、`DeviceSeam`、`UiSeam`、`CapabilitySeam`、
`EventSeam`、`BackupSeam`、`NativeBridgeSeam`、`ThemeBackupSeam`。

`SeamRegistry` 是取用入口，定义在 `:ext-api`。宿主在 `APatchApp.onCreate` 里
`install(ctx, HostSeamProvider(ctx))` 注册实现。没装时返回「全部能力不可用」的兜底 ——
让扩展降级，而不是让 App 崩（熔断与早期初始化都会走到这里）。

#### 为什么不硬拆 `DshNativeBridge`

`DshNativeBridge` 是 1900 行的单 object，内部高度互锁：能力实现调用开关判定，
开关判定调用权限查询，权限查询又用 `Special` 枚举。按「分区」硬切会切出循环依赖。

试过，在没有本地 JDK 的环境里连续 4 轮 CI 失败，代价极高而收益只是「文件短了点」。
现在的做法是：**不拆它**，在它前面放一层 `NativeBridgeSeam`（7 个方法），
`:ext-workflow` / `:ext-display` 通过接口调它。桥的内部结构是它自己的事。

这条经验值得记住：**「让依赖方向对」和「让文件变短」是两个目标，前者才是有价值的那个。**
