# T1DMDROID

An Android app for Type 1 Diabetes. It reads up to four CGMs at once — **AiDEX X / LinX**, **Anytime CT5** and **Libre 3** — over Bluetooth LE, and forecasts glucose on the device. It is advisory-only — it never actuates insulin delivery. Android 12+, arm64 and x86_64, sideloaded.

Designed by a T1DM patient, informed by lived experience.

> [!CAUTION]
> **Personal project, research use only.** T1DMDROID solves one patient's niche problem and is published for reference, not for anyone else to install or depend on. It is not a medical device, not clinically validated, and unsupported. Its forecasts and calculators may be wrong and **must not** be used for medical, diagnostic, or dosing decisions, nor to replace a real CGM, its official app, or professional care. Provided "as is", without warranty; the authors accept no liability.


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

- **Blood glucose graph** — readings, the forecast and its band, and carbohydrate and insulin curves on one graph.
- **Multiple sensors** — up to four at once; one drives the forecast, alarms and statistics, the rest are recorded.
- **Paint** — freehand drawing on the graph.
- **Drive** — cosmetic minigames played on the glucose trace.
- **Autoregressive rolling** — rolls the forecast past its horizon, for display only.
- **Hindsight** — scrubs through past forecasts against what happened.
- **Infilling** — the model fills gaps the sensor left.
- **Circadian clock** — the model's guess at the time of day, from glucose alone.
- **Model evaluation** — backtest, accuracy, calibration, and Clarke and DTS zones.
- **LoRA adapters** — tune the model on the wearer's own forecasts; the base weights stay frozen.
- **Log carbohydrates** — a meal as a drawn appearance curve.
- **Log insulin** — a dose as a drawn insulin-action curve.
- **Statistics** — time in range, GMI, CV, LBGI/HBGI, ADRR and MAGE.
- **Settings** — targets, alarms, dose rails, display and backups.
- **Alarms** — model-free out-of-range and loss-of-signal alarms.
- **Nightscout** — one-way upload.
- **Watch link** — encrypted BLE push to T1DMKDE (desktop) and T1DMAUTO (car head unit).
- **Backup** — one gzipped JSON file; restore merges and never rolls back newer data.


## Building

Android SDK 36 and the NDK, JDK 21, and Rust with the `aarch64-linux-android` and `x86_64-linux-android` targets and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) on `PATH`.

```sh
./gradlew :app:assemblePersonalRelease
```


## Running on Xiaomi HyperOS / MIUI

- **Settings → Apps → T1DMDROID:** Autostart on, battery **No restrictions**, **Pause app activity if unused** off.
- **System Bluetooth app:** battery **Unrestricted**.
- **Recents:** lock the app's card.
- **Lock-screen glucose:** `adb shell settings put secure lock_screen_show_silent_notifications 1`


## Related projects

- **[T1DMSIM](https://github.com/0xdeadf1sh/T1DMSIM)** — the simulator whose synthetic traces pretrain the model.
- **[T1DMAI](https://github.com/0xdeadf1sh/T1DMAI)** — training and ExecuTorch export of the model.


## License

MIT — see [LICENSE](LICENSE).
