# T1DMDROID

A personal Android app for Type 1 Diabetes that passively reads a Microtech/Ottai **AiDEX X / LinX** continuous glucose monitor over Bluetooth-LE advertisements and runs **on-device** glucose forecasting. It is advisory-only — it never actuates insulin delivery — and is built for a single arm64 device rather than for general distribution: it is sideloaded, not published to any app store.

Designed by a T1DM patient, informed by lived experience.

> [!CAUTION]
> **Personal project, research use only.** T1DMDROID solves one patient's niche problem — one sensor, one phone — and is published for reference, not for anyone else to install or depend on. It is not a medical device, not clinically validated, and unsupported. Its forecasts and calculators may be wrong and **must not** be used for medical, diagnostic, or dosing decisions, nor to replace a real CGM, its official app, or professional care. Provided "as is", without warranty; the authors accept no liability.


## Table of contents

- [What it is](#what-it-is)
- [Features](#features)
- [Architecture](#architecture)
- [Module map](#module-map)
- [Building](#building)
- [Running on Xiaomi HyperOS / MIUI](#running-on-xiaomi-hyperos--miui)
- [Target device](#target-device)
- [Related projects](#related-projects)
- [License](#license)


## What it is

T1DMDROID reads glucose the way a beacon scanner reads a beacon: the AiDEX X broadcasts its current reading roughly once per minute, and the app listens for those advertisements passively — no pairing, no bond, no GATT connection. Sensor activation, calibration, and warmup remain the job of the sensor's official app on a separate phone; T1DMDROID is a pure, indefinite passive reader. Each reading is stamped with the phone's receive time snapped to a 5-minute grid.

On top of that live feed it runs a small transformer forecasting model entirely on the device, surfaces the current value with a trend and a glanceable forecast, logs meals/insulin/notes, computes advisory statistics, and drives a deterministic, model-free alarm path for out-of-range and loss-of-signal conditions. Optional integrations add a self-hosted sync server and an encrypted BLE watch accessory.

The Bluetooth, inference, and watch protocols are documented under [`docs/`](docs): [`CGM.md`](docs/CGM.md), [`INFERENCE.md`](docs/INFERENCE.md), [`WATCH_BLE.md`](docs/WATCH_BLE.md), and [`T1DMSERVER_API.md`](docs/T1DMSERVER_API.md).


## Features

- **Passive CGM feed** — advertisements read without pairing, snapped to a 5-minute grid with gaps reconstructed.
- **On-device forecasting** — a small transformer under ExecuTorch, CPU reference plus NPU shadow, gated so a degenerate forecast is withheld rather than shown.
- **Model-free alarms** — deterministic out-of-range, loss-of-signal, weak-signal, and device-temperature tiers, independent of the model.
- **Glucose panel** — a custom Compose chart with threshold bands, selectable smoothing, a freehand annotation layer, and a drive-mode minigame over the trace.
- **Logging** — meals, insulin, and journal notes, each confirmed in a dialog and undoable from its receipt; saved meals, custom foods, editable curves.
- **Advisory calculators** — bolus and correction suggestions, insulin- and carbs-on-board, time in range, GMI, AGP, Kovatchev risk.
- **Glanceable surfaces** — home-screen widget, live notification, and predictive alerts ahead of a projected crossing.
- **Settings** — a search index over every knob, three themes, configurable haptics, portable configuration backup.
- **Optional peripherals** — a self-hosted sync server and an encrypted BLE watch.


## Architecture

- **UI:** Jetpack Compose, organized as a multi-module Gradle build so the CGM-source and model-backend seams stay pluggable.
- **Rust core (`t1dm-core`, via JNI/NDK):** owns the correctness-critical, hot numerics — AiDEX frame decode and its CRCs, session crypto, the model pre/post pipeline (causal Savitzky-Golay smoothing, normalize/denormalize, the Kovatchev risk transform, quantile assembly), and the watch AES-128-GCM. Kotlin keeps the UI, BLE plumbing, storage, and orchestration. The core is tested bit-for-bit against golden vectors in CI.
- **On-device inference:** [ExecuTorch](https://pytorch.org/executorch/). One exported artifact runs on two backends — CPU (XNNPACK, fp32) as the reference authority, and the NPU (MediaTek NeuroPilot / Neuron, fp16) as a measured shadow — behind a clean backend seam.
- **Storage & orchestration:** Room on the bundled SQLite driver; an always-on foreground service plus WorkManager run the passive scan, the 5-minute grid, inference, sync, and the alarm path off the main thread.

Heavy compute never runs on the main thread; the UI observes results reactively.


## Module map

| Module | Responsibility |
|---|---|
| `:app` | Composition root, the always-on foreground service, notifications, widgets, navigation |
| `:cgm` | Passive AiDEX X advertisement scan, recognition, and the CGM-source registry |
| `:inference` | The forecasting cycle: context build, backend dispatch, decode, degeneracy gating |
| `:sensors` | Step counter and other phone sensors |
| `:calc` | Advisory bolus/basal and statistics calculators |
| `:alerts` | The deterministic, model-free alarm engine (out-of-range, loss-of-signal, device temperature) |
| `:sync` | Durable-outbox sync with the self-hosted server |
| `:watch` | Encrypted BLE link to the optional ESP32-C3 watch |
| `:data` | Room database, repositories, curve reconstruction |
| `:core:common`, `:core:model`, `:core:design`, `:core:native` | Shared dispatchers, domain types, theming, and the Rust-core JNI bindings |
| `:ui:graph` | The custom Compose blood-glucose graph |
| `:feature:*` | Screen features — dashboard, stats, models, hardware, network, meals, insulin, security, settings, journal |


## Building

Requirements:

- Android SDK **36** and the NDK, JDK **21**.
- A Rust toolchain with the `aarch64-linux-android` target and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) installed **and on `PATH`** (the native build silently skips if `cargo-ndk` is missing).

The app targets **arm64-v8a only**, `minSdk 34`, `targetSdk 36`. Build the optimized personal release APK with:

```sh
./gradlew :app:assemblePersonalRelease
```

There are two product flavors: `personal` (the daily build) and `public` (installs under a `.pub` application id). Release builds are R8-minified and resource-shrunk; if no `keystore.properties` is present they fall back to the debug signing key so a fresh checkout still produces an installable APK.


## Running on Xiaomi HyperOS / MIUI

HyperOS (and MIUI generally) manage background apps far more aggressively than stock Android, and an always-on passive CGM reader is exactly the kind of app they curtail. The setup below is required for reliable operation. Note that HyperOS may silently reset some of these toggles after a system update or reboot, so they are worth re-checking periodically.

### Battery and autostart

In **Settings → Apps**, for T1DMDROID:

- Enable **Autostart** / "Background autostart" (also lets it start on boot).
- Set the battery mode to **No restrictions** — the default "Battery saver" level throttles background work.
- Turn **off** "Pause app activity if unused".
- Grant the standard Android **battery-optimization exemption** ("Ignore battery optimizations") as well.

Also exempt the **system Bluetooth app** (Settings → Apps → show system apps → Bluetooth → battery usage → **Unrestricted**) — easy to miss, and the scan depends on it. Finally, **lock the app in Recents** (drag its card down until it shows a padlock) so "clear all" and the memory cleaner don't evict it. Setting the phone to **Performance** power mode helps as well.

### Background collection while the screen is off

On Android 14+, and especially on HyperOS, the system suspends a background app's Bluetooth-LE scan when the screen turns off — a plain real-time scan stops delivering the moment the phone locks. To keep collecting, the app uses **offloaded batch scanning**: the Bluetooth controller buffers the sensor's advertisements in hardware regardless of screen state, and HyperOS flushes those batches on roughly a **five-minute timer**.

The practical consequence: while the phone is locked, new glucose readings — and therefore any alarms — can lag by up to about **five minutes**. This is an OS-imposed floor for a passive-advertisement sensor (a device you can *connect* to over GATT is not affected), and it does not apply while the screen is on. Each batched reading is timestamped at its true capture instant, so no 5-minute grid slot is lost.

### Glucose on the lock screen

The app posts a persistent, silent notification with the current glucose value and trend. HyperOS hides **silent** notifications from the lock screen by default and removes the corresponding control from Settings, so the notification will appear in the shade but not on the lock screen until that control is re-enabled once:

```sh
adb shell settings put secure lock_screen_show_silent_notifications 1
```

Alternatively, use an "Activity Launcher"-type app to open
`com.android.settings.Settings$ConfigureNotificationSettingsActivity` → **Notifications on lock screen** → **Show conversations, default and silent**.

This setting is **device-wide** (it affects every app's silent notifications), persists across reboots, and is cleared by a factory reset — there is no way for an app to set it on your behalf.


## Target device

The build targets a single phone: a **Redmi K90 Max** (MediaTek Dimensity 9500 / MT6993, APU 990 NPU), running **Android 16 / HyperOS**, arm64-v8a. The NPU path uses the MediaTek NeuroPilot / Neuron stack. Other devices are untested and unsupported.


## Related projects

- **[T1DMSIM](https://github.com/0xdeadf1sh/T1DMSIM)** — the behavioral simulator whose synthetic traces pretrain the model this app runs.
- **[T1DMAI](https://github.com/0xdeadf1sh/T1DMAI)** — the training and ExecuTorch export pipeline that produces the artifact and descriptor loaded here.
- **[T1DMSERVER](https://github.com/0xdeadf1sh/T1DMSERVER)** — the optional sync backend this app pushes to, phone-authoritative (protocol in [`docs/T1DMSERVER_API.md`](docs/T1DMSERVER_API.md)).


## License

GPL-3.0.
