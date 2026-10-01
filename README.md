# T1DMDROID

A personal Android app for Type 1 Diabetes that reads up to four continuous glucose monitors at once — Microtech/Ottai **AiDEX X / LinX**, **Anytime CT5** and **Libre 3** — each over its own held, paired Bluetooth-LE GATT session, and runs **on-device** glucose forecasting. One sensor at a time is authoritative and alone feeds the forecast, statistics, alarms and outbound destinations; the others are recorded and can be shown on the BG panel. It is advisory-only — it never actuates insulin delivery. It runs on Android 12 and later, on arm64 and x86_64, and is sideloaded rather than published to any app store.

Designed by a T1DM patient, informed by lived experience.

> [!CAUTION]
> **Personal project, research use only.** T1DMDROID solves one patient's niche problem and is published for reference, not for anyone else to install or depend on. It is not a medical device, not clinically validated, and unsupported. Its forecasts and calculators may be wrong and **must not** be used for medical, diagnostic, or dosing decisions, nor to replace a real CGM, its official app, or professional care. Provided "as is", without warranty; the authors accept no liability.


## Table of contents

- [What it is](#what-it-is)
- [Features](#features)
- [Architecture](#architecture)
- [Module map](#module-map)
- [Building](#building)
- [Running on Xiaomi HyperOS / MIUI](#running-on-xiaomi-hyperos--miui)
- [Background controls on other phones](#background-controls-on-other-phones)
- [Target device](#target-device)
- [Related projects](#related-projects)
- [License](#license)


## What it is

The app pairs with the sensor, holds the GATT session open, and reads live values and the sensor's stored history over it. Each reading is stamped at the sensor's own sample time, snapped to a 5-minute grid. An AiDEX that reports no start time is activated on connect, which starts its warm-up; a Libre 3 is activated, or moved to this phone, by one NFC tap.

A small transformer runs over that feed entirely on the device. It predicts any withheld stretch of glucose rather than only the next two hours, so one artifact fills a gap the sensor left as well as it forecasts, and a low-rank adapter can personalise it from the wearer's own matured forecasts while the exported weights stay frozen. Around it sit meal and insulin logs, advisory statistics, and a deterministic, model-free alarm path for out-of-range and loss-of-signal. Optional integrations add a one-way Nightscout bridge and an encrypted BLE link to watch peripherals.

The inference and watch protocols are documented under [`docs/`](docs): [`INFERENCE.md`](docs/INFERENCE.md) and [`WATCH_BLE.md`](docs/WATCH_BLE.md).


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

Every bout opens its own review: the route and the glucose trace over the bout, padded to 30 minutes when shorter. A slider moves a cursor along trace and route and reads out the local time and the measured BG; an empty slot shows no value. Tracks are never uploaded; map tiles are fetched for the surrounding area and cached on the device.

A bout can also be replayed at a chosen instant, past or future, which lays its disposal curve into the exercise channel. A replay is a log like any other: it appears in Logs, its curve is drawn on the glucose panel beside the carbohydrate and insulin curves, and it can be moved in time or deleted, which takes its grams back out of the series. The model takes carbohydrate and insulin only, so no exercise curve reaches the forecast.

### Backup and Restore

One gzipped, line-delimited JSON file holds the glucose readings, the wide sensor series, meals, doses, basal schedules, custom foods, saved meals, insulin types, exercise bouts and their tracks, replayed bouts, promoted gap-fills with their median and 90 % band, the CGM source list, LoRA adapters, band corrections, deletion tombstones, the graph's freehand drawings, and the exported settings. It leaves out raw sub-grid samples, unpromoted gap-fills, stored forecasts, the outbox, telemetry, and the settings that are not exported: the Nightscout link, the backup schedule, body mass, the sensor-name switch and the fail-open override. Automatic backups run on a chosen cadence into a folder outside app storage, so they survive an uninstall, with a configurable number of older archives retained.

Restore merges: a record already present is kept, so importing the same file twice changes nothing and an older archive can never roll back newer data. The Nightscout secret is never written to a backup — it lives in the Android Keystore rather than in the database.


## Architecture

- **UI:** Jetpack Compose, organized as a multi-module Gradle build so the CGM-source and model-backend seams stay pluggable.
- **Rust core (`t1dm-core`, via JNI/NDK):** the correctness-critical, hot numerics — AiDEX frame decode and its CRCs, session crypto, the model pre/post pipeline (causal Savitzky-Golay smoothing, normalize/denormalize, the Kovatchev risk transform, quantile assembly), glycemic statistics, and the watch AES-128-GCM. Kotlin keeps the UI, BLE plumbing, storage, and orchestration. `cargo test -p t1dm-core` tests the core bit-for-bit against golden vectors.
- **On-device inference:** [ExecuTorch](https://pytorch.org/executorch/). One exported model on one backend, behind a seam that keeps it replaceable: the CPU XNNPACK fp32 delegate, which the stock runtime registers. It is the only path a dose is scored on; a model whose artifact will not load there falls back to a fixed-output stub and the dose calculator refuses.
- **Storage & orchestration:** Room on the bundled SQLite driver; an always-on foreground service plus WorkManager run the sensor sessions, the 5-minute grid, inference, the Nightscout bridge, and the alarm path off the main thread.


## Module map

| Module | Responsibility |
|---|---|
| `:app` | Composition root, the always-on foreground service, notifications, widgets, navigation |
| `:cgm` | Connected sessions for AiDEX X, CT5 and Libre 3, sensor discovery, and the CGM-source registry |
| `:inference` | The forecasting cycle: context build, backend dispatch, decode, degeneracy gating |
| `:sensors` | Step counter, GPS track recording, and other phone sensors |
| `:calc` | Advisory bolus/basal calculators, dose rails and the dose advisor |
| `:alerts` | The deterministic, model-free alarm engine (out-of-range, loss-of-signal, device temperature) |
| `:sync` | The durable outbox behind the one-way Nightscout bridge |
| `:watch` | Encrypted BLE link to watch peripherals (T1DMKDE on the desktop, T1DMAUTO on a car head unit) |
| `:data` | Room database, repositories, statistics, curve reconstruction, the backup archive codec |
| `:core:common`, `:core:model`, `:core:design`, `:core:native` | Shared dispatchers, domain types, theming, and the Rust-core JNI bindings |
| `:ui:graph` | The custom Compose blood-glucose graph |
| `:ui:game`, `:feature:game` | Drive and Golf, the cosmetic minigames drawn on the glucose trace over a rapier2d solver in `t1dm-core` |
| `:feature:*` | Screen features — dashboard, stats, models, meals, insulin, exercise, security, cgm, settings, logs, backup |


## Building

- Android SDK **36** and the NDK, JDK **21**.
- A Rust toolchain with the `aarch64-linux-android` and `x86_64-linux-android` targets and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) installed **and on `PATH`**, or the build fails. With no NDK found, the native build is skipped and the APK may package a stale `.so` from an earlier build.

The app ships **arm64-v8a** and **x86_64**, `minSdk 31`, `targetSdk 36`. The ABI list is `t1dm.abis` in `gradle.properties`; the Rust cross-build and every `abiFilters` read it. Release builds run lint with `NewApi` fatal across every module, so a call above API 31 without a version check fails the build.

```sh
./gradlew :app:assemblePersonalRelease
```

`crates/libre3-core`'s table-backed tests need `LIBRE3_TABLES_DIR` set to a copy of the tables the app reads from `libre3/tables` in its external files dir on the phone. Without it `cargo test` fails; `LIBRE3_TABLES_SKIP=1` runs the table-free tests only.

Two product flavors: `personal` (the daily build) and `public` (installs under a `.pub` application id). Release builds are R8-minified and resource-shrunk; without a `keystore.properties` they fall back to the debug signing key, so a fresh checkout still produces an installable APK.


## Running on Xiaomi HyperOS / MIUI

HyperOS manages background apps far more aggressively than stock Android, and an always-on CGM reader holding a Bluetooth session is exactly the kind of app it curtails. The setup below is required for reliable operation, and a system update or reboot can silently reset parts of it.

### Battery and autostart

In **Settings → Apps**, for T1DMDROID:

- **Autostart** on — this also lets it start on boot.
- Battery mode **No restrictions**; the default "Battery saver" level throttles background work.
- **Pause app activity if unused** off.
- The standard Android **battery-optimization exemption** granted as well.

Also set the **system Bluetooth app** to **Unrestricted** (Settings → Apps → show system apps → Bluetooth → battery usage) — easy to miss, and the sensor sessions depend on it. **Lock the app in Recents** (drag its card down until it shows a padlock) so "clear all" and the memory cleaner cannot evict it. **Performance** power mode helps as well.

### Background collection while the screen is off

Readings arrive over the held GATT session, which a locked screen does not suspend. On Android 14+, and especially on HyperOS, the system does suspend a background app's unbatched Bluetooth-LE scan, and scans here only find sensors: none carries a reading. A paired AiDEX is redialled at its stored address without one. The CT5 and Libre 3 discovery scans run **batched** while the phone is locked: the controller buffers advertisements in hardware and HyperOS flushes them on roughly a **five-minute timer**.

So after a dropped link on a locked phone, finding a CT5 or Libre 3 again can take up to about **five minutes**, and readings and any alarms wait on it. Each reading keeps the sensor's own sample time, and the gap is back-filled from the sensor's stored history once the session is back.

### Glucose on the lock screen

The app posts a persistent, silent notification with the current glucose value and trend. HyperOS hides **silent** notifications from the lock screen by default and removes the corresponding control from Settings, so the notification appears in the shade but not on the lock screen until that control is re-enabled once:

```sh
adb shell settings put secure lock_screen_show_silent_notifications 1
```

An "Activity Launcher"-type app reaches the same control: `com.android.settings.Settings$ConfigureNotificationSettingsActivity` → **Notifications on lock screen** → **Show conversations, default and silent**.

The setting is **device-wide**, persists across reboots, and is cleared by a factory reset — no app can set it on the user's behalf.


## Background controls on other phones

**Settings → Background** reads what the OS currently allows the app and opens the system page for each: battery optimization, exact alarms (Android 12 only; from 13 the app holds `USE_EXACT_ALARM`), notifications, and full-screen alerts (Android 14+). On Xiaomi, Samsung, OnePlus/OPPO/realme, vivo and Huawei/Honor phones it also opens the maker's own autostart or background-limit page, whose state no app can read. A maker page that has moved falls back to the app's details page.

The app asks once, on first start, for the battery-optimization exemption. An exempt app may also start its foreground service from the background, which the CGM watchdog relies on to restart a killed service on Android 12+.

Below Android 13 no Bluetooth handle can carry a random address type, so a CT5 is always found by scan rather than redialled at its stored address.


## Target device

The app is tested on one phone: a **Redmi K90 Max** (MediaTek Dimensity 9500 / MT6993) running **Android 16 / HyperOS**, arm64-v8a. Other phones and Android versions are untested on hardware. Inference runs on the CPU; the SoC's APU is not used, since the MediaTek NeuroPilot runtime ships through Play feature delivery and a sideloaded build cannot fetch it.


## Related projects

- **[T1DMSIM](https://github.com/0xdeadf1sh/T1DMSIM)** — the behavioral simulator whose synthetic traces pretrain the model this app runs.
- **[T1DMAI](https://github.com/0xdeadf1sh/T1DMAI)** — the training and ExecuTorch export pipeline that produces the artifact and descriptor loaded here.


## License

MIT — see [LICENSE](LICENSE).
