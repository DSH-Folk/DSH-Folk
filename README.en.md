<div align="center">

# DSH-Folk

[简体中文](./README.md) | [English](./README.en.md)

**Android launcher for DeepSeek Harness**

[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg?logo=gnu)](./LICENSE)
[![Build](https://github.com/DSH-Folk/DSH-Folk/actions/workflows/build.yml/badge.svg)](https://github.com/DSH-Folk/DSH-Folk/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/DSH-Folk/DSH-Folk?logo=github)](https://github.com/DSH-Folk/DSH-Folk/releases/latest)

</div>

DSH-Folk packages [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) (a Node.js coding agent CLI) as a standalone Android app. On first launch, it downloads a Linux container runtime matching the device ABI (`arm64-v8a` or `x86_64`), starts `dsh web` inside proot / proroot, and provides an integrated WebUI, PTY terminal, plugin store, workflow editor, and extension system.

No root or Termux is required. If root, Shizuku, or wireless ADB is available, optional privileged features can be enabled in Settings.

## Contents

- [Preview](#preview)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Runtime Management](#runtime-management)
- [Documentation](#documentation)
- [Project Structure](#project-structure)
- [Acknowledgments](#acknowledgments) · [License](#license) · [Links](#links) · [Community](#community)

## Preview

<div align="center">

| Home | Terminal |
| :---: | :---: |
| <img src="docs/screenshots/home.jpg" width="260" alt="Home: start/stop, runtime mode, privilege channel, and startup logs"> | <img src="docs/screenshots/terminal.jpg" width="260" alt="Terminal: container PTY with extra keys"> |
| **Plugins** | **Settings** |
| <img src="docs/screenshots/plugins.jpg" width="260" alt="Plugins: installed list with downloads, stars, and updates"> | <img src="docs/screenshots/settings.jpg" width="260" alt="Settings: general, appearance, behavior, functions, security, backup, plugins, and multimedia"> |

</div>

## Features

| Section | Description |
| --- | --- |
| **Home** | Start, stop, or restart DSH, inspect runtime stage, WebUI URL, privilege channel, and startup logs |
| **Terminal** | Full container PTY powered by Termux `terminal-view` with extra keys |
| **Plugins & Extensions** | Manage container DSH plugins (4,000+ store entries, npm/GitHub stats, local `.tgz` install with load check) and host `.dshx` extensions |
| **Workflows** | Visual node-based automation editor with pin-type filtering, branching, approvals, and native capability actions |
| **Settings** | General, Appearance, Behavior, Functions, Security, Backup, Plugins, and Multimedia; theme format compatible with FolkPatch (`theme.json` / `.fpt`) |

- **Language & Themes**: Independent in-app language switcher under **Settings → General → Language**; theme store and `.fpt` import/export under **Settings → Appearance**.
- **Virtual Display**: Run and automate apps on an isolated virtual display (**Settings → Functions → Permissions → Virtual Display**) with live preview and touch takeover (requires root, Shizuku, or wireless ADB).
- **Config Backup**: Compatible with desktop `dsh-config-manager` archives; credentials are excluded by default and optional AES-256-GCM encryption is supported.

## Requirements

- Android 8.0 (API 26) or newer
- **arm64-v8a** or **x86_64** device (32-bit is not supported)
- Internet connection on first launch (~150 MB download, ~600 MB extracted; 2 GB+ free storage recommended)

## Installation

Download the APK matching your device ABI from [Releases](https://github.com/DSH-Folk/DSH-Folk/releases/latest) (each accompanied by a `.sha256` checksum):

- `DSH-Folk-<version>-arm64-v8a.apk` — phones and tablets
- `DSH-Folk-<version>-x86_64.apk` — Android emulators, Android-x86, ChromeOS

Development builds are available from [Actions](https://github.com/DSH-Folk/DSH-Folk/actions/workflows/build.yml). Update channels (Stable / Beta / Alpha) can be configured under **Settings → General → Update Channel**.

## Runtime Management

Use the **Settings → Functions → Runtime** card:

- **Tap**: Check for runtime updates and upgrade while preserving sessions, plugins, and config.
- **Tap the card**: Check for updates right now, then download and replace on confirmation (sessions, plugins and config are preserved).
- **Long-press the card**: Open the version menu to switch between full vs slim, and stable vs beta channels, reinstall, downgrade, or import a local `.tar.gz` runtime archive. The list is filtered by both sliders; if the currently installed version is filtered out it is pinned on top, and tapping it reinstalls.
- **Toggle**: Enable or disable automatic update checks on launch (enabled by default).

## Documentation

- [Extension Development Guide (ZH)](docs/extensions/README.md) — `.dshx` manifest, UI slots, 10 typed Seams, 19 hooks, boot pipeline, and safety model
- [Architecture & Runtime](docs/architecture.en.md) — 8-module Gradle layout, Seam dependency inversion, proot/proroot, rootfs, and ELF closure checks
- [Host Bridges (dsh-fs / dsh-native)](docs/host-bridges.en.md) — loopback bridges, native capabilities, privileged shell, and accessibility
- [Backup & Encryption](docs/backup.en.md) — export/import format, `DCA1` container, snapshot rollback, and WebDAV cloud sync
- [Autostart](docs/autostart.en.md) — boot receiver, accessibility service, and boot script options
- [Logs & Redaction](docs/logs.en.md) — bugreport contents, redaction, and time-window trimming
- [Build & Release Notes](docs/dev-notes.en.md) — CI workflows, release channels, and static gates
- [Workflow Pitfalls](docs/workflow-pitfalls.en.md) — cross-process contracts, approvals, and Compose canvas notes (**CC BY 4.0**)
- [Power & Accessibility Research](docs/research-power-and-a11y.en.md) — proroot vs proot measurements and keep-alive strategies
- [Attachment Upload Pitfalls](docs/attachment-upload-pitfalls.en.md) — `--link2symlink` and atomic publish diagnostics (**CC BY 4.0**)

## Project Structure

The project is split into 8 Gradle modules with strict one-way dependencies (enforced by `tools/check-module-boundary.js`):

```text
app/                Assembly shell: AndroidManifest, Activities/Services/Providers, UI & Settings
ext-api/            Public extension contract (dependency for .dshx): 10 typed Seams, UI/Hook/SPI registries, BootPipeline
core-kernel/        Immutable host kernel: container env, logging, safety guard, privilege policy, Shizuku AIDL
core-res/           Shared string resources (single source of truth)
ext-workflow/       Built-in extension: workflow engine, validator, approvals, and node editor
ext-display/        Built-in extension: virtual display manager, Binder protocol, and mirror overlay
ext-userscripts/    Built-in extension: WebUI script pipeline, userscript manager, and GreasyFork market
ext-backup/         Built-in extension: config backup, AES-256-GCM archive crypto, and cloud sync
displayserver/      Standalone virtual display server process (app_process)
runtime-builder/    Container rootfs builder scripts and ELF dependency closure checker
tools/              CI static gates and cross-module boundary checkers
docs/               Technical documentation
```

The internal Kotlin package stays `me.bmax.apatch` (`applicationId` is `top.funcun.dshfolk`) to preserve backward compatibility with FolkPatch themes and existing user data.

## Acknowledgments

- [FolkPatch](https://github.com/LyraVoid/FolkPatch) — UI foundation (GPL-3.0)
- [APatch](https://github.com/bmax121/APatch) — upstream of FolkPatch
- [TouchTool (点击助手)](https://github.com/mr-bogey/TouchTool) — reference for the workflow editor's 184-action catalog, 15 categories, pin-type filtering dialog, and node interaction design (GPL-3.0)
- [DSHA](https://github.com/DSH-APP/DSHA) — wireless ADB pairing and container execution reference
- [DSHM](https://github.com/RochelimitDawn/DSHM) — online runtime delivery and mirror benchmarking reference
- [dsh-market](https://github.com/dsh-market/dsh-market) — plugin store catalog and statistics APIs (MIT)
- [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) — core agent runtime
- [proot](https://github.com/proot-me/proot) / [proroot](https://github.com/coderredlab/proroot) / [Termux](https://github.com/termux/termux-app) — container execution and PTY terminal
- [Shizuku](https://github.com/RikkaApps/Shizuku) — root-free privileged channel
- [KernelSU](https://github.com/tiann/KernelSU) / [SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra) — UI design references
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) — virtual display creation and input injection reference (Apache-2.0)
- [Operit](https://github.com/AAswordman/Operit) — `displayserver/` ported from `tools/shower`; floating overlay interaction inspired by `VirtualDisplayOverlay` (LGPL-3.0)

## License

[GNU General Public License v3.0](./LICENSE). Derived from GPL-3.0 FolkPatch; any redistribution or modification must remain under GPLv3 with complete source code.

## Links

LINUX DO Open Source Community | [linux.do](https://linux.do)

## Community

- QQ Group: [1109060326](https://qm.qq.com/q/t7HDoR5ACk)
- Issues: https://github.com/DSH-Folk/DSH-Folk/issues
