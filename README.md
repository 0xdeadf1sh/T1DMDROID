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

On top of that live feed it runs a small transformer forecasting model entirely on the device, surfaces the current value with a trend and a glanceable forecast, logs meals and insulin, computes advisory statistics, and drives a deterministic, model-free alarm path for out-of-range and loss-of-signal conditions. Optional integrations add a self-hosted sync server and an encrypted BLE watch accessory.

The Bluetooth, inference, and watch protocols are documented under [`docs/`](docs): [`CGM.md`](docs/CGM.md), [`INFERENCE.md`](docs/INFERENCE.md), [`WATCH_BLE.md`](docs/WATCH_BLE.md), and [`T1DMSERVER_API.md`](docs/T1DMSERVER_API.md).


## Features

### Blood Glucose Graph

<img width="400" height="auto" alt="photo_2026-07-25_18-53-18" src="https://github.com/user-attachments/assets/c2ba5931-586a-41c9-adcd-f05e913a3899" />

### Autoregressive Rolling

https://github.com/user-attachments/assets/e808ddd5-ca75-431e-9789-bdbe94ceeabf

### Paint Inside The BG Graph

https://github.com/user-attachments/assets/334f76ef-a84a-4fc8-a1f8-9040f8f336c3

### Circadian Rhythm & Remaining Time

https://github.com/user-attachments/assets/e185384e-b671-4c99-8f8a-807c9d805a21

### ADA Professional Publications Feed

https://github.com/user-attachments/assets/965587fb-25f4-41ec-a54d-8c45f806bb47

### Advanced Statistics

https://github.com/user-attachments/assets/7742f1ac-be4e-490f-9903-e3bced23e9ea

### Real-Time Model Inference

https://github.com/user-attachments/assets/a8c8d0d1-829c-4df6-b147-50ef2424a656

### Logging Meals

https://github.com/user-attachments/assets/7173971f-fe94-4b10-ab7e-935e4f1adfe1

### Logging Insulin

https://github.com/user-attachments/assets/5a84df1a-5db9-49cd-94a9-a4a4c17546d5

### Advanced Searchable Settings Panel

https://github.com/user-attachments/assets/13ade4ea-f50f-4c91-bf23-62ef8dae1c96

### Theming

https://github.com/user-attachments/assets/319401e4-09ff-4e8f-964f-0eb8a0808fca

### Backup and Restore

The Backup panel writes the whole local record — every glucose reading, the wide sensor series, logged meals and doses, basal schedules, custom foods, saved meals, insulin types, the graph's freehand drawings, and every setting — to one gzipped, line-delimited JSON file. Automatic backups run on a chosen cadence into a folder outside app storage, so they survive an uninstall, with a configurable number of older archives retained.

Restoring merges: a record already present on the device is kept, so importing the same file twice changes nothing and an older archive can never roll back newer data. The server token is never written to a backup — it lives in the Android Keystore rather than in the database.


## Architecture

- **UI:** Jetpack Compose, organized as a multi-module Gradle build so the CGM-source and model-backend seams stay pluggable.
- **Rust core (`t1dm-core`, via JNI/NDK):** owns the correctness-critical, hot numerics — AiDEX frame decode and its CRCs, session crypto, the model pre/post pipeline (causal Savitzky-Golay smoothing, normalize/denormalize, the Kovatchev risk transform, quantile assembly), and the watch AES-128-GCM. Kotlin keeps the UI, BLE plumbing, storage, and orchestration. The core is tested bit-for-bit against golden vectors in CI.
- **On-device inference:** [ExecuTorch](https://pytorch.org/executorch/). One exported model runs on two backends — CPU (XNNPACK, fp32) as the reference authority, and the GPU (Vulkan compute delegate, fp16) as a measured shadow whose agreement with the CPU path is measured before it may inform anything — behind a clean backend seam. The Vulkan delegate comes from a custom ExecuTorch build vendored under `third_party/`; the stock runtime registers XNNPACK only.
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
| `:data` | Room database, repositories, curve reconstruction, the backup archive codec |
| `:core:common`, `:core:model`, `:core:design`, `:core:native` | Shared dispatchers, domain types, theming, and the Rust-core JNI bindings |
| `:ui:graph` | The custom Compose blood-glucose graph |
| `:feature:*` | Screen features — dashboard, stats, models, hardware, network, meals, insulin, security, settings, logs, backup |


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

The build targets a single phone: a **Redmi K90 Max** (MediaTek Dimensity 9500 / MT6993), running **Android 16 / HyperOS**, arm64-v8a. Accelerated inference runs on the GPU through Vulkan; the SoC's APU is not used, since the MediaTek NeuroPilot runtime ships through Play feature delivery and a sideloaded build cannot fetch it. Other devices are untested and unsupported.


## Related projects

- **[T1DMSIM](https://github.com/0xdeadf1sh/T1DMSIM)** — the behavioral simulator whose synthetic traces pretrain the model this app runs.
- **[T1DMAI](https://github.com/0xdeadf1sh/T1DMAI)** — the training and ExecuTorch export pipeline that produces the artifact and descriptor loaded here.
- **[T1DMSERVER](https://github.com/0xdeadf1sh/T1DMSERVER)** — the optional sync backend this app pushes to, phone-authoritative (protocol in [`docs/T1DMSERVER_API.md`](docs/T1DMSERVER_API.md)).


## License

GPL-3.0.
