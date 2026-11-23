# Architecture & Runtime

[← Back to README](../README.en.md)

## Execution Pipeline

```text
DSH-Folk (Android app)                      ← ABI splits: arm64-v8a / x86_64
  └─ proot / proroot                        ← executable .so inside APK nativeLibraryDir
       └─ Ubuntu 24.04 rootfs               ← downloaded on first launch per ABI
            ├─ python3                       ← preinstalled for wireless ADB pairing
            ├─ git                           ← preinstalled for git-based plugins
            └─ Node.js 24 + @deepseek-ai/dsh
                 └─ dsh web --port 3080      ← loopback only by default
                      └─ Built-in WebUI / mobile browser
```

### Android Platform Constraints

- **Executable binaries**: Android's `app_data_file` is mounted `noexec`, so `proot` / `proroot` are packaged as `.so` files in `nativeLibraryDir`.
- **ABI support**: [proroot](https://github.com/coderredlab/proroot) ships `arm64-v8a` only; `x86_64` devices use `proot`.
- **Hardlinks & pnpm**: On devices where `link(2)` fails in app private storage, `--link2symlink` is enabled and `packageImportMethod: copy` is written to `pnpm-workspace.yaml` so Node's `require.resolve` can resolve plugin client bundles cleanly.
- **Bundled pnpm & ELF checks**: The rootfs bundles `pnpm@10.34.5` and `git`. Because rootfs packages are unpacked cross-architecture via `dpkg-deb -x`, `runtime-builder/check-elf-closure.js` verifies `DT_NEEDED` shared-library closure during CI builds.
- **Loopback & data preservation**: `dsh web` and config backup bind to `127.0.0.1` by default. Runtime upgrades preserve `root/.dsh`, `root/.local`, and `.l2s` atomically.

## Gradle Module Architecture

The codebase is organized into **8 Gradle modules** with one-way dependencies enforced in CI by `tools/check-module-boundary.js` and `tools/check-cross-module-symbols.js`:

```text
:app                 Assembly shell (AndroidManifest, Activities/Services, Compose UI)
  ↓
:ext-*               Built-in compile-time extensions (bundled in APK, enabled by default)
  ├─ :ext-workflow        Workflow engine, validator, approvals, and editor
  ├─ :ext-display         Virtual display session, Binder protocol, and mirror overlay
  ├─ :ext-userscripts     WebUI script pipeline, userscript manager, and GreasyFork market
  └─ :ext-backup          Config backup, AES-256-GCM archive crypto, and cloud sync
  ↓
:core-kernel         Immutable host kernel (container env, logging, safety guard, privilege policy, Shizuku AIDL)
  ↓
:core-res            Shared string resources (single source of truth)
:ext-api             Public extension contract (compiled against by third-party .dshx)
```

No horizontal dependencies (`:ext-a → :ext-b`) and no reverse dependencies (`* → :app`) are permitted. Cross-module calls go through typed Seam interfaces in `:ext-api`.

## Seam Dependency Inversion

Both built-in modules and third-party `.dshx` extensions access host capabilities through **10 typed Seam interfaces** in `:ext-api`:

`RuntimeSeam`, `ShellSeam`, `FsSeam`, `DeviceSeam`, `UiSeam`, `BackupSeam`, `EventSeam`, `CapabilitySeam`, `NativeBridgeSeam`, and `ThemeBackupSeam`.

`APatchApp.onCreate` installs default host implementations via `SeamRegistry.install(ctx, HostSeamProvider(ctx))`. Under safe-mode latching or early startup, `SeamRegistry` falls back cleanly without crashing. `DshNativeBridge` remains intact inside `:app` behind the 7-method `NativeBridgeSeam` facade to avoid breaking its tightly coupled internal dispatch logic.
