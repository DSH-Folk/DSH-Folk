# Final Report: Three Research Items

> Date: 2026-11-18 · Covers: Figure 2, items 1, 2, 3
> Items 1–2 depend on hardware and upstream design; item 3 is already implemented.

---

## 1. `proroot` vs `proot` power consumption

### Bottom line

**The user-supplied measurement report gets two facts wrong**, though its direction
("syscall-heavy workloads are expensive") is right.

### Corrections (from this repo's code, not speculation)

| Report says | Code says |
|---|---|
| "proroot is a self-built ptrace translation layer" | **Wrong. proroot is LD_PRELOAD + binary patching, zero ptrace.** See `ContainerRuntime.kt:16` |
| "the PRoot kind of proot" | proot *is* the ptrace emulation. See `ContainerRuntime.kt:236` |
| "can't switch, no toggle in settings" | **Wrong. You can.** `DshEnv.KEY_RUNTIME`, `DshRuntime.setRuntimeId()`, UI at `FunctionSettingsScreen.kt:458` |

So the "80× syscall amplification" figure is **misattributed**: proroot does not intercept
syscalls at all, so that magnitude cannot come from it. The likely explanation:

1. What actually ran was **proot** — on x86_64 the five proroot `.so` files do not exist and
   `runtime()` silently falls back to proot. The report itself mentions
   `container_runtime: proot`, which confirms it measured proot.
2. 80× is a **plausible** magnitude for ptrace (two context switches per syscall plus
   translation work).

### The real options — there are three, not two

| Runtime | Mechanism | Requires | Cost |
|---|---|---|---|
| `proot` | ptrace emulation | any ABI, bundled | **highest** (trap per syscall) |
| `proroot` | LD_PRELOAD path translation | **arm64-v8a only** | low (zero ptrace) |
| `chroot` | real-root chroot | root channel | lowest; also lets bwrap work |

Default is **proroot** ("no ptrace, noticeably cheaper than proot"); some kernels get stuck
on seccomp/ptrace, which `noteProrootFailure()` catches — one failure switches back to proot
(`PROROOT_FAIL_LIMIT = 1`).

### What still needs hardware testing

- Real power delta of proroot vs proot **on arm64** (the report likely measured proot, i.e.
  never measured proroot).
- Power under chroot (needs root; bwrap availability varies by kernel).
- Long-running idle vs busy container.

**Suggested method**: same arm64 device, same rootfs, same script, all three runtimes, take
mAh deltas from `dumpsys batterystats` — CPU time and power are not always linear.

---

## 2. DSHA power-saving mode

### DSHA's design (from its own docs)

> "Continuous running is the default. Power saving releases the **Web lock** after one minute
> of continuous known-idle with the screen off, no LAN access and no native tasks — it does
> **not** stop Web. The built-in adapter reads dsh's agent/inbox/background-job/cron state;
> when the interface is missing, the state is unknown, or the state file is stale, it **keeps
> alive**. Native tasks hold their own lock, so terminal, bridge requests, plugins and backups
> do not lose keep-alive when the Web idle lock is released. Locks have timeouts and are
> renewed by active tasks/services."

Decomposed, it is four layers:

1. **Inputs**: screen off, no LAN, no native tasks, known-idle ≥ 1 min.
2. **Action**: release the **Web WakeLock** (not kill the process, not stop Web).
3. **Conservative fallback**: unknown/missing/stale state → **keep alive** (rather burn a
   little more power than kill a running task).
4. **Independent locks**: native tasks hold their own, unaffected by the Web idle lock.

The key insight is **"power saving" ≠ "stopping"**: it only lets go of a wakelock so the
system can sleep on its own schedule. Process and Web both stay; nothing to restart.

### Where this repo stands

| Capability | Status |
|---|---|
| Foreground service | **Yes** (`HarnessService`, `FOREGROUND_SERVICE_SPECIAL_USE`) |
| WakeLock | **No** (no `PARTIAL_WAKE_LOCK` anywhere) |
| Power-save decision | **No** |
| Idle-state reading | **No** |

Strictly speaking this repo has **no concept of "power saving"** yet — it is "always resident
via foreground service". That is not a bug: without a wakelock there is nothing to release,
and the foreground service already keeps the process from being reclaimed easily.

### If we were to build it

**Do not copy DSHA's four layers.** DSHA's design solves "the Web WakeLock keeps burning power
after screen-off". This repo **has no such wakelock**; copying it would mean first introducing
a wakelock and then writing logic to release it — a net increase in power use. The dominant
cost here is the container (proot/proroot syscall overhead), not a wakelock.

**Minimal proposal** (if desired):

1. Add a "pause non-essential polling when screen is off" toggle (no wakelock).
2. Only two inputs: screen off + no running workflow.
3. Action only lowers UI/state refresh rate; **never stops the container**.
4. Unknown state → keep current behaviour (same conservative stance as DSHA).

**Priority: low.** Without measurements showing meaningful drain after screen-off, this is
premature optimisation — and every added condition is another chance to kill a running task.

---

## 3. Accessibility keep-alive

### Verdict: **already implemented**, with its limits documented

Not a research item here — it ships. Core is `DshA11yRecovery` (304 lines) plus two services.

### What already works

**Borrowing the a11y framework as a launch path** (`DshAutostartService`):

- A deliberately do-nothing accessibility service whose only value is `onServiceConnected()`:
  the system binds enabled a11y services after boot **and re-binds them after the process is
  killed**.
- The only launch path that needs neither root nor a vendor "autostart" allowlist (the
  `BootCompletedReceiver` broadcast never arrives on many ROMs).
- Kept minimal on purpose: `accessibilityEventTypes` only `typeWindowStateChanged`
  (subscribing to everything means the system pushes every UI change — wasted power);
  **no** `canRetrieveWindowContent` (that bit is what "can read your screen" means, and we
  only need "gets bound"); event callbacks are empty.

**Automatic recovery** (`DshA11yRecovery`):

- Observes `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` and writes our entries back.
- **Add-only**: other apps' entries are preserved verbatim; never reordered or cleared — one
  "tidy-up" would disable the user's screen reader.
- **Write then read back**: a write is not proof of effect (some ROMs reject or revert it).
- **Rate limited**: at most `MAX_ATTEMPTS_PER_WINDOW` per window. If the system keeps
  reverting, stop and report rather than fight over the same switch.
- Whether to restore is decided from **in-app intent**, not the system setting — when the
  system shows it off we cannot tell whether the user meant it.

**Prerequisite**: writing `Settings.Secure` needs `WRITE_SECURE_SETTINGS`, which has no
runtime prompt (ADB/root only). Without it we **write nothing and do not pretend success**.

### Limits (documented in code)

Best-effort. A normal app cannot run a resident monitor. Covered moments:

1. secure-settings change while our process is alive (observer)
2. app returning to foreground (`repairQuietly`)
3. service disconnect callback (`onUnbind`)

**While our process is dead, nobody restores it.** Note that a dead process ≠ a disabled
service: an enabled a11y service gets re-bound by the system, which re-runs
`onServiceConnected` — that is the "container comes back" mechanism.

### Three possible improvements

1. **`START_STICKY` + foreground service**: `HarnessService` is already a foreground service;
   check it uses `START_STICKY` (system rebuilds after kill). More direct than a11y.
2. **Periodic self-check via `JobScheduler` / `WorkManager`**: costs wakeups, needs balancing.
3. **Ask the user to disable battery optimisation**: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
   Note Google Play restricts this permission to specific app categories; GitHub distribution
   avoids that constraint.

### Not recommended

- **`typeAllMask`**: pushes every UI change to us — wasted power and violates "borrow minimally".
- **`canRetrieveWindowContent`**: makes the system consent text say "can read screen content";
  bad optics, and unnecessary.
- **Unbounded retries writing secure settings**: creates a switch-fighting loop.

---

## Summary

| Item | Status | Note |
|---|---|---|
| 1. proroot vs proot power | **needs hardware** | Two factual errors corrected; method given |
| 2. DSHA power saving | **done** | Mechanism decomposed; low priority, rationale given |
| 3. A11y keep-alive | **implemented** | Recovery ships; three improvements listed |
