# 三项调研的最终报告

> 日期：2026-11-18 ｜ 对应需求：图 2 第 1、2、3 项
> 前两项（功耗对比、省电模式）依赖真机与上游设计，第三项（无障碍保活）已有实现。

---

## 1. `proroot` vs `proot` 功耗对比

### 结论先行

**用户提供的实测报告结论需要修正两处事实**，但其中"系统调用密集场景开销大"的方向是对的。

### 事实修正（依据：本仓库代码，不是推测）

| 报告的说法 | 代码里的实际情况 |
|---|---|
| 「proroot 是自研 ptrace 翻译层」 | **错。proroot 是 LD_PRELOAD + 二进制补丁，零 ptrace。** 见 `ContainerRuntime.kt:16`「[Proroot] coderredlab/proroot，LD_PRELOAD + 二进制补丁，零 ptrace 开销」 |
| 「proot 那种 PRoot」 | proot 才是 ptrace 模拟。见 `ContainerRuntime.kt:236`「proot 是 ptrace 模拟（不要 root、兼容性最好），proroot 是 LD_PRELOAD 路径翻译」 |
| 「设置里切不了，没有开关」 | **错。能切。** `DshEnv.KEY_RUNTIME = "container_runtime"`，`DshRuntime.setRuntimeId(id)` 可写，UI 入口在 `FunctionSettingsScreen.kt:458`（设置 → 功能 → 运行方式） |

所以报告里「80 倍 syscall 放大」的**归因错了**：那个量级如果真实存在，不可能来自 proroot ——
proroot 根本不拦系统调用。更可能的解释是：

1. 实测时**实际跑的是 proot**（x86_64 上 proroot 的五个 .so 不存在，`runtime()` 会静默回落
   proot）。报告里提到 `container_runtime: proot`，正好印证这一点 —— 它测的就是 proot。
2. ptrace 的 80× 放大对 proot 是**合理量级**（每次 syscall 两次上下文切换 + 翻译）。

### 真实的可选路径

三个运行时，不是两个：

| 运行时 | 机制 | 可用条件 | 开销 |
|---|---|---|---|
| `proot` | ptrace 模拟 | 全架构，APK 内置 | **最高**（每次 syscall 陷入） |
| `proroot` | LD_PRELOAD 路径翻译 | **仅 arm64-v8a**（上游只出 arm64 .so） | 低（零 ptrace） |
| `chroot` | 真 root chroot | 需要 root 通道 | 最低，且能让 bwrap 有机会工作 |

**默认是 proroot**，理由是「不走 ptrace，开销明显低于 proot」；部分内核上会卡在
seccomp/ptrace 起不来，由 `noteProrootFailure()` 兜底 —— 失败一次即自动切回 proot
（`PROROOT_FAIL_LIMIT = 1`）。

### 仍需要真机验证的部分

以下无法在本仓库内得出，需要实测：

- arm64 设备上 proroot vs proot 的**真实功耗差**（报告测的可能是 proot，等于没测到 proroot）。
- 切到 chroot 后的功耗（需要 root，且 bwrap 可用性因内核而异）。
- 长跑场景（容器空闲 vs 有任务）的差异。

**建议的实测方法**：同一台 arm64 设备、同一 rootfs、同一脚本，三个运行时各跑一轮，
用 `dumpsys batterystats` 取 mAh 增量，而不是只看 CPU 时间 —— 功耗与 CPU 时间不总是线性。

---

## 2. DSHA 省电工作模式机制

### DSHA 的设计（上游，读自其文档）

> 「持续运行是默认模式。省电模式在**熄屏、无局域网访问、无原生任务且运行状态连续已知空闲
> 一分钟后释放 Web 锁，不主动停止 Web**。内置适配读取 dsh 的 Agent、收件箱、后台作业和
> 定时任务状态；缺少接口、未知状态或状态文件过期时**保持保活**。原生任务独立持锁，
> 终端、桥接请求、插件和备份等命令不会随 Web 空闲锁释放而失去保活。锁有超时并由活跃
> 任务/服务续期。」

拆开看，它其实是四层：

1. **判定输入**：熄屏（`PowerManager.isInteractive == false`）、无局域网访问、无原生任务、
   运行状态连续已知空闲 ≥ 1 分钟。
2. **动作**：释放 **Web WakeLock**（不是杀进程、不是停 Web）。
3. **保守兜底**：状态未知 / 接口缺失 / 状态文件过期 → **保持保活**（宁可多耗电，不可误杀）。
4. **独立锁**：原生任务（终端、桥接、插件、备份）持自己的锁，不受 Web 空闲锁影响。

关键洞察是**"省电"不等于"停止"**：它只是松开一个 wakelock，让系统可以按自己的策略休眠。
进程和 Web 都还在，用户回来时不需要重新启动。

### 本仓库的现状

| 能力 | 状态 |
|---|---|
| 前台服务 | **有**（`HarnessService`，`FOREGROUND_SERVICE_SPECIAL_USE`） |
| WakeLock | **没有**（全仓库无 `PARTIAL_WAKE_LOCK`） |
| 省电模式判定 | **没有** |
| 空闲状态读取 | **没有** |

所以严格说，本仓库**还没有"省电模式"这个概念** —— 目前是"一直以前台服务常驻"。
这不是 bug：没有 wakelock 就谈不上"释放 wakelock"，前台服务本身已经让系统不会轻易回收。

### 如果要做，怎么做（结论）

**不建议照搬 DSHA 的四层判定**，理由是：

- DSHA 那套是为了解决"Web WakeLock 在熄屏后仍在烧电"。本仓库**没有这个 wakelock**，
  照搬等于先引入一个 wakelock 再写一套逻辑去释放它 —— 净效果是更耗电。
- 本仓库的耗电大头在容器（proot/proroot 的 syscall 开销），不在 wakelock。

**建议的最小方案**（若要加）：

1. 加一个"熄屏后暂停非必要轮询"的开关（不碰 wakelock）。
2. 判定输入只要两条：熄屏 + 无运行中的工作流。
3. 动作只是降低 UI/状态刷新频率，**不停止容器**。
4. 状态未知时保持现状（与 DSHA 同一保守取向）。

**优先级建议：低。** 在没有实测数据证明"熄屏后仍有明显耗电"之前，加省电模式属于
提前优化，而且每加一条判定就多一个"误杀正在跑的任务"的机会。

---

## 3. 无障碍服务保活

### 结论：**已有实现**，且能力边界已写清

本仓库不是"待调研"，是"已落地"。核心在 `DshA11yRecovery`（304 行）与两个服务。

### 已经做到的

**借无障碍框架做启动路径**（`DshAutostartService`）：

- 一个**故意什么都不做**的无障碍服务，唯一价值是 `onServiceConnected()` ——
  系统开机后会主动 bind 已启用的无障碍服务，**进程被杀后还会重新 bind**。
- 这是唯一一条既不需要 root、又不受国产 ROM「自启动管理」白名单约束的启动路径。
  （对比：`BootCompletedReceiver` 那条广播在很多 ROM 上根本不会送达。）
- 刻意做到最小：`accessibilityEventTypes` 只设 `typeWindowStateChanged`（订阅全等于让系统
  把每次界面变化都推过来，白烧电）；**没有** `canRetrieveWindowContent`（那一位才是
  "能读你屏幕内容"的来源，我们只需要"被 bind"这个事实）；事件回调全空。

**自动恢复**（`DshA11yRecovery`）：

- 监听 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 变更，发现服务被关就写回去。
- **只增不减**：别人的条目原样保留，只补自己缺的组件，绝不重排/清空 ——
  一次"顺手整理"就会把用户的读屏软件关掉。
- **写后读回**：写入不等于生效（有的 ROM 会拒收或立刻改回），读回确认才报成功。
- **限次**：窗口期内最多试 `MAX_ATTEMPTS_PER_WINDOW` 次。系统反复改回去时停手并如实报告，
  而不是两个进程互相抢同一个开关。
- 判定"要不要恢复"只看**应用内部的期望设置**，不看系统设置 —— 系统关掉时无法区分
  是不是用户本意，看系统设置会变成"抢开关"。
- **有一个独立总闸**（`a11y_auto_recovery`，默认开）。期望设置的两个来源不等于
  "用户同意被自动改回去" —— 那是两件事。总闸关掉后 `wanted()` 恒返回"都不需要"，
  并且**真正 unregisterContentObserver**（不是"还在盯着但回调里早退"），再次打开会重挂。
  加它之前的真实问题是：这个行为**完全不可见**，用户只会看到"我明明在系统设置里关了，
  它自己又开了"，既不知道存在这个机制，也无从拒绝。

**权限前提**：写 `Settings.Secure` 需要 `WRITE_SECURE_SETTINGS`，没有运行时授权框，
只能用 ADB / root 一次性授予。没授予时**不执行任何写入，也不假装成功**。

### 能力边界（必须对用户说清，代码注释已写明）

这是 **best-effort**。普通应用做不到常驻监控。能覆盖的时机只有：

1. 应用进程活着时收到 secure settings 变更（observer）
2. 应用回到前台（`repairQuietly`）
3. 服务断开回调（`onUnbind`）

**进程被系统杀掉期间，谁也不会替我们恢复。** 但注意：进程被杀 ≠ 服务被关 ——
无障碍服务处于"已启用"状态时，系统会重新 bind 它，`onServiceConnected` 会再跑一次，
这正是"容器掉了还能回来"的机制。

### 三个可以提高的方向（如果要做）

1. **`START_STICKY` + 前台服务组合**：`HarnessService` 已经是前台服务，
   检查它是否 `START_STICKY`（被杀后系统重建）。这是比无障碍更直接的一条。
2. **`JobScheduler` / `WorkManager` 定期自检**：周期性检查服务是否还在，不在就恢复。
   代价是唤醒本身耗电，需要平衡。
3. **引导用户关闭电池优化**：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。
   注意这条在 Google Play 有政策限制（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 仅允许
   特定类别应用），上架前需要评估。本应用若只走 GitHub 分发则不受此限。

### 不建议做的

- **`typeAllMask` 订阅**：让系统把每次界面变化都推过来，白烧电，且违反"最小借用"原则。
- **`canRetrieveWindowContent`**：会让系统授权文案变成"能读取屏幕内容"，用户观感差，
  而我们不需要它。
- **无限制重试写 secure settings**：会与系统/其他应用形成抢开关循环。

---

## 汇总

| 需求 | 状态 | 说明 |
|---|---|---|
| 1. proroot vs proot 功耗对比 | **需真机** | 报告有两处事实错误已修正；测量方法已给出 |
| 2. DSHA 省电模式调研 | **已完成** | 机制已拆解；建议优先级低，理由已说明 |
| 3. 无障碍保活调研 | **已实现** | 恢复机制已落地；三个提高方向已列出 |
