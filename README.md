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

The AiDEX X broadcasts its current reading roughly once per minute and the app listens passively — no pairing, no bond, no GATT connection. Activation, calibration and warmup stay with the sensor's official app on a separate phone. Each reading is stamped with the phone's receive time snapped to a 5-minute grid.

A small transformer runs over that feed entirely on the device. It predicts any withheld stretch of glucose rather than only the next two hours, so one artifact fills a gap the sensor left as well as it forecasts, and a low-rank adapter can personalise it from the wearer's own matured forecasts while the exported weights stay frozen. Around it sit meal and insulin logs, advisory statistics, and a deterministic, model-free alarm path for out-of-range and loss-of-signal. Optional integrations add a one-way Nightscout bridge and an encrypted BLE watch accessory.

The Bluetooth, inference, and watch protocols are documented under [`docs/`](docs): [`CGM.md`](docs/CGM.md), [`INFERENCE.md`](docs/INFERENCE.md), and [`WATCH_BLE.md`](docs/WATCH_BLE.md).


## Features

<table>
<tr>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/0f52cf8b-5122-4d68-ba36-b0f84b7090b7" width="320" controls></video><br><b>Blood glucose graph</b></td>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/e7d7a001-f89f-475b-9446-823c71ec5ae3" width="320" controls></video><br><b>Multiple sensors</b></td>
</tr>
<tr>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/7704a16f-3539-4345-9c17-5330ad572e46" width="320" controls></video><br><b>Paint</b></td>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/970528b8-9494-4861-94ce-05db97fa3843" width="320" controls></video><br><b>Drive</b></td>
</tr>
<tr>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/22db9310-0cea-4283-b328-7d66f2bb77be" width="320" controls></video><br><b>Autoregressive rolling</b></td>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/21c5ab35-24fc-42be-9720-2de01283ca97" width="320" controls></video><br><b>Hindsight</b></td>
</tr>
<tr>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/434dfd60-c53a-4bca-b50d-9a8e5aae45bd" width="320" controls></video><br><b>Infilling</b></td>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/49221852-40a6-4bec-bdfd-8ddd313d9d3a" width="320" controls></video><br><b>Circadian clock</b></td>
</tr>
<tr>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/d3374eb4-f7f3-42b5-bf1e-99e328fbefc6" width="320" controls></video><br><b>Model evaluation</b></td>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/2b77341c-9f8c-46f8-893f-9fdfaab3709d" width="320" controls></video><br><b>LoRA adapters</b></td>
</tr>
<tr>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/a6dcc3eb-f52b-4bf2-8803-b3e13068edf6" width="320" controls></video><br><b>Log carbohydrates</b></td>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/296c5f4e-feac-44bd-b342-f3dafa270075" width="320" controls></video><br><b>Log insulin</b></td>
</tr>
<tr>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/6533d16f-3953-4410-b155-2cffecfde8f4" width="320" controls></video><br><b>Statistics</b></td>
<td align="center" width="50%"><video src="https://github.com/user-attachments/assets/ef631f0b-0cc8-4353-8d8a-c27599a725d2" width="320" controls></video><br><b>Settings</b></td>
</tr>
</table>

### Exercise

Bouts run from a manual start to a manual stop, following GPS during walks and runs and drawing the route on OpenStreetMap tiles. Distance and pace come from accepted fixes; energy expenditure from the ACSM walking and running equations at zero grade, shown only once a body mass has been entered. Each bout's per-five-minute magnitude folds into the same wide sensor series that carries glucose, heart rate, steps, sleep and mood.

Every bout opens its own review: the route, the glucose trace from 30 minutes before to 2 hours after, and a slider sweeping the forecast issued at any instant in that window across the trace that followed it. Where no forecast was issued, the slider shows nothing rather than the nearest one. Tracks are never uploaded; map tiles are fetched for the surrounding area and cached on the device.

A bout can also be replayed at a chosen instant, past or future, which lays its disposal curve into the exercise channel and lets the forecast answer it. A replay is a log like any other: it appears in Logs, its curve is drawn on the glucose panel beside the carbohydrate and insulin curves, and it can be moved in time or deleted, which takes its grams back out of the series.

### Backup and Restore

One gzipped, line-delimited JSON file holds the whole local record — every glucose reading, the wide sensor series, meals, doses, basal schedules, custom foods, saved meals, insulin types, exercise bouts and their tracks, replayed bouts, the graph's freehand drawings, and every setting. Automatic backups run on a chosen cadence into a folder outside app storage, so they survive an uninstall, with a configurable number of older archives retained.

Restore merges: a record already present is kept, so importing the same file twice changes nothing and an older archive can never roll back newer data. The Nightscout secret is never written to a backup — it lives in the Android Keystore rather than in the database.


## Architecture

- **UI:** Jetpack Compose, organized as a multi-module Gradle build so the CGM-source and model-backend seams stay pluggable.
- **Rust core (`t1dm-core`, via JNI/NDK):** the correctness-critical, hot numerics — AiDEX frame decode and its CRCs, session crypto, the model pre/post pipeline (causal Savitzky-Golay smoothing, normalize/denormalize, the Kovatchev risk transform, quantile assembly), and the watch AES-128-GCM. Kotlin keeps the UI, BLE plumbing, storage, and orchestration. The core is tested bit-for-bit against golden vectors in CI.
- **On-device inference:** [ExecuTorch](https://pytorch.org/executorch/). One exported model on one backend, behind a seam that keeps it replaceable: the CPU XNNPACK fp32 delegate, which the stock runtime registers. It is the only path a dose is scored on; a model whose artifact will not load there falls back to a fixed-output stub and the dose calculator refuses.
- **Storage & orchestration:** Room on the bundled SQLite driver; an always-on foreground service plus WorkManager run the passive scan, the 5-minute grid, inference, the Nightscout bridge, and the alarm path off the main thread.


## Module map

| Module | Responsibility |
|---|---|
| `:app` | Composition root, the always-on foreground service, notifications, widgets, navigation |
| `:cgm` | Passive AiDEX X advertisement scan, recognition, and the CGM-source registry |
| `:inference` | The forecasting cycle: context build, backend dispatch, decode, degeneracy gating |
| `:sensors` | Step counter, GPS track recording, and other phone sensors |
| `:calc` | Advisory bolus/basal and statistics calculators |
| `:alerts` | The deterministic, model-free alarm engine (out-of-range, loss-of-signal, device temperature) |
| `:sync` | The durable outbox behind the one-way Nightscout bridge |
| `:watch` | Encrypted BLE link to the optional ESP32-C3 watch |
| `:data` | Room database, repositories, curve reconstruction, the backup archive codec |
| `:core:common`, `:core:model`, `:core:design`, `:core:native` | Shared dispatchers, domain types, theming, and the Rust-core JNI bindings |
| `:ui:graph` | The custom Compose blood-glucose graph |
| `:feature:*` | Screen features — dashboard, stats, models, meals, insulin, security, settings, logs, backup |


## Building

- Android SDK **36** and the NDK, JDK **21**.
- A Rust toolchain with the `aarch64-linux-android` target and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) installed **and on `PATH`** — the native build silently skips if `cargo-ndk` is missing.

The app targets **arm64-v8a only**, `minSdk 34`, `targetSdk 36`.

```sh
./gradlew :app:assemblePersonalRelease
```

Two product flavors: `personal` (the daily build) and `public` (installs under a `.pub` application id). Release builds are R8-minified and resource-shrunk; without a `keystore.properties` they fall back to the debug signing key, so a fresh checkout still produces an installable APK.


## Running on Xiaomi HyperOS / MIUI

HyperOS manages background apps far more aggressively than stock Android, and an always-on passive CGM reader is exactly the kind of app it curtails. The setup below is required for reliable operation, and a system update or reboot can silently reset parts of it.

### Battery and autostart

In **Settings → Apps**, for T1DMDROID:

- **Autostart** on — this also lets it start on boot.
- Battery mode **No restrictions**; the default "Battery saver" level throttles background work.
- **Pause app activity if unused** off.
- The standard Android **battery-optimization exemption** granted as well.

Also set the **system Bluetooth app** to **Unrestricted** (Settings → Apps → show system apps → Bluetooth → battery usage) — easy to miss, and the scan depends on it. **Lock the app in Recents** (drag its card down until it shows a padlock) so "clear all" and the memory cleaner cannot evict it. **Performance** power mode helps as well.

### Background collection while the screen is off

On Android 14+, and especially on HyperOS, the system suspends a background app's Bluetooth-LE scan when the screen turns off. To keep collecting, the app uses **offloaded batch scanning**: the Bluetooth controller buffers the sensor's advertisements in hardware regardless of screen state, and HyperOS flushes those batches on roughly a **five-minute timer**.

So while the phone is locked, new readings — and therefore any alarms — can lag by up to about **five minutes**. This is an OS-imposed floor for a passive-advertisement sensor, and does not apply while the screen is on. Each batched reading is timestamped at its true capture instant, so no 5-minute grid slot is lost.

### Glucose on the lock screen

The app posts a persistent, silent notification with the current glucose value and trend. HyperOS hides **silent** notifications from the lock screen by default and removes the corresponding control from Settings, so the notification appears in the shade but not on the lock screen until that control is re-enabled once:

```sh
adb shell settings put secure lock_screen_show_silent_notifications 1
```

An "Activity Launcher"-type app reaches the same control: `com.android.settings.Settings$ConfigureNotificationSettingsActivity` → **Notifications on lock screen** → **Show conversations, default and silent**.

The setting is **device-wide**, persists across reboots, and is cleared by a factory reset — no app can set it on the user's behalf.


## Target device

The build targets a single phone: a **Redmi K90 Max** (MediaTek Dimensity 9500 / MT6993) running **Android 16 / HyperOS**, arm64-v8a. Inference runs on the CPU; the SoC's APU is not used, since the MediaTek NeuroPilot runtime ships through Play feature delivery and a sideloaded build cannot fetch it. Other devices are untested and unsupported.


## Related projects

- **[T1DMSIM](https://github.com/0xdeadf1sh/T1DMSIM)** — the behavioral simulator whose synthetic traces pretrain the model this app runs.
- **[T1DMAI](https://github.com/0xdeadf1sh/T1DMAI)** — the training and ExecuTorch export pipeline that produces the artifact and descriptor loaded here.


## License

MIT — see [LICENSE](LICENSE).
