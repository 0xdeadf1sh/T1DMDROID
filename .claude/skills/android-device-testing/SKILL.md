---
name: android-device-testing
description: >-
  Build, deploy, screenshot, and verify the T1DMDROID app on the physical phone using the `android`
  CLI (with adb for on-device inspection). Use whenever you need to run / install / launch the app on
  device, capture a screenshot, inspect the UI layout, or confirm a change actually works on-device
  (foreground-service liveness, BLE scan mode, CGM readings landing in the DB). Covers the build
  command, the variant→package map, APK output paths, the launcher-alias activity ambiguity, and the
  dumpsys / DB verification patterns. Prefer this over hand-rolled adb for the build→deploy→screenshot
  loop.
---

# Testing T1DMDROID on-device with the `android` CLI

There is a first-class `android` command-line tool on PATH. **Reach for it** to locate build
artifacts, deploy to the phone, take screenshots, and inspect the UI — it is faster and less
error-prone than hand-assembling `adb` invocations. Run `android help` (do not truncate it) for the
full command list, and `android <cmd> -h` for any subcommand. For general, non-project usage also see
the built-in `android-cli` skill (`android skills list` / `android skills find android`).

Use plain `adb` only where the `android` tool has no equivalent: `dumpsys`, `input keyevent`, and
pulling the app database during verification.

## The loop: build → deploy → observe

1. **Build the APK.** The Rust core needs `cargo-ndk` on PATH and AGP needs the pinned JDK (the
   system JDK is too new for AGP), so the invocation is non-obvious:

   ```bash
   PATH="$HOME/.cargo/bin:$PATH" env -u JAVA_HOME ./gradlew :app:assemblePersonalDebug
   ```

   Skipping the `PATH=` prefix silently repackages a stale `.so`; omitting `env -u JAVA_HOME` picks up
   the too-new system JDK. Default to the **personalDebug** variant — that is the daily build.

2. **Find the built APK** without guessing paths:

   ```bash
   android describe --project_dir .          # lists every module, variant, and its APK output path
   ```

   Grep the output for `Task: :app` to read the exact APK locations. For reference, the two debug
   variants land at:

   - personalDebug → `app/build/outputs/apk/personal/debug/app-personal-debug.apk` → package **`com.t1dm.app`**
   - publicDebug   → `app/build/outputs/apk/public/debug/app-public-debug.apk`      → package **`com.t1dm.app.pub`**

3. **Deploy + launch** (installs and starts the activity):

   ```bash
   android run --apks app/build/outputs/apk/personal/debug/app-personal-debug.apk \
     --device <serial> --activity com.t1dm.app.LauncherTron
   ```

   - `--device` is the serial from `adb devices`; optional when exactly one device is attached.
   - `--activity` is **required here**: the app declares one `MainActivity` plus five per-theme
     `<activity-alias>` LAUNCHER entries, so `android run` reports *"Multiple candidates for type
     ACTIVITY"* and refuses to guess. Pass **`com.t1dm.app.LauncherTron`** (the default-enabled alias,
     which mirrors the real home-screen launch) or `com.t1dm.app.MainActivity`.
   - `android run` reinstalls each call, so it is the iterate-loop primitive — rebuild, `android run`,
     re-observe.

4. **Observe.** Screenshot and UI inspection are `android`, not raw `adb`:

   ```bash
   android screen capture -o shot.png        # PNG of the current screen; -a annotates UI bounding boxes
   android layout -p                          # pretty-printed layout tree (add --device for the serial)
   android screen resolve                      # visually target UI elements
   ```

   Read the PNG back with the Read tool. Two on-device-testing rules worth keeping: **never verify a
   transient visual defect from a still** — use `adb shell screenrecord`, pull it, and scan frames —
   and **sweep all three themes** for any colour-role bug (the default Tron palette hides several).

## Verifying behaviour, not just pixels

The app's real work happens in the always-on foreground service, so verification usually means
inspecting service + DB state, which is `adb` territory:

- **Foreground-service liveness + BLE scan mode:**

  ```bash
  adb shell dumpsys activity services com.t1dm.app | grep -iE "CgmScanService|isForeground"
  adb shell dumpsys bluetooth_manager | grep -iE "ScanMode|t1dm"   # current vs historical scan config
  ```

  `isForeground=true` means the monitor is up; the newest `Scan Config: [ ScanMode=… ]` for
  `com.t1dm.app` is the mode actually in effect.

- **Readings + the service heartbeat** (pull the WAL and shm too — the DB uses the BundledSQLite
  driver and the reader replays the WAL on open):

  ```bash
  adb exec-out run-as com.t1dm.app cat databases/t1dm.db     > t1dm.db
  adb exec-out run-as com.t1dm.app cat databases/t1dm.db-wal > t1dm.db-wal
  adb exec-out run-as com.t1dm.app cat databases/t1dm.db-shm > t1dm.db-shm
  # then: python3 -c "import sqlite3; ..."  (system `sqlite3` CLI lacks features; use python)
  ```

  `kv.last_alive_ts` advances every 60 s **iff the process lives**; the newest `cgm_reading.tsMs`
  advances **iff the scan is delivering**. Heartbeat-advances-but-readings-stall ⇒ a scan problem,
  not a process kill — the discriminator for background-collection bugs.

- **Background / screen-off tests:** `adb shell input keyevent KEYCODE_HOME` then `… KEYCODE_SLEEP`
  reproduces "exit the app and lock the phone"; confirm the screen is dark with
  `dumpsys power | grep mWakefulness`. Pulling the DB does not wake the screen, so you can sample
  across the dark window. **Reading the real CGM is a sensor test — announce it to the user first**:
  a passive read only works while the official AiDEX app (on the user's other phone) isn't holding the
  sensor in a connection, so they need to free it.

## Install gotchas on this device (HyperOS / K90 Max)

The ones that bite deployment on this Xiaomi/HyperOS device:

- A **fresh-package** install (first-ever `com.t1dm.app.pub`) returns `INSTALL_FAILED_USER_RESTRICTED`
  and needs a physical on-screen tap (or Developer options → *Install via USB* + disable MIUI install
  confirmation). An already-installed package (personalDebug `com.t1dm.app`) reinstalls fine.
- `POST_NOTIFICATIONS` still prompts after install; `pm grant com.t1dm.app
  android.permission.POST_NOTIFICATIONS` before a scripted launch, or the dialog stalls it.
- After `am force-stop`, an intent that starts the FGS trips
  `ForegroundServiceStartNotAllowedException` — relaunch `MainActivity`/`LauncherTron` first.
- The non-exported `CgmScanService` cannot be poked directly over adb (HyperOS refuses "Requires
  permission not exported"); the debug build ships an **exported** `CgmDebugReceiver` that forwards
  intents to the running FGS via an app-internal `startForegroundService`. Broadcast to it explicitly,
  e.g. `adb shell am broadcast -n com.t1dm.app/com.t1dm.app.service.CgmDebugReceiver -a
  com.t1dm.app.INJECT_READING --ei bg 45 --ei ageMin 0 --ei trend -20`.
