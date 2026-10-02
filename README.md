# T1DMDROID

An Android testbed for biohackers and researchers who run and test machine-learning models on blood glucose. It reads up to four CGMs at once (**AiDEX X / LinX**, **Anytime CT5**, **Libre 3**) over Bluetooth LE and runs your ExecuTorch models on their live output, on the phone. Android 12+, arm64 and x86_64, sideloaded.

> [!CAUTION]
> **Research use only.** It is not a medical device and not clinically validated. Its forecasts and calculators may be wrong and **must not** be used for medical or dosing decisions, nor to replace a real CGM, its official app, or professional care. No warranty, no liability.


## Video Gallery

<table>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/0f52cf8b-5122-4d68-ba36-b0f84b7090b7" width="320" controls></video><br>Readings, the forecast band, and carbohydrate and insulin curves on one graph.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/e7d7a001-f89f-475b-9446-823c71ec5ae3" width="320" controls></video><br>Up to four CGMs read at once and recorded side by side.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/7704a16f-3539-4345-9c17-5330ad572e46" width="320" controls></video><br>Freehand drawing on the graph.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/970528b8-9494-4861-94ce-05db97fa3843" width="320" controls></video><br>Minigames played on the glucose trace.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/22db9310-0cea-4283-b328-7d66f2bb77be" width="320" controls></video><br>Extends the forecast past the model's horizon by feeding it back in.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/21c5ab35-24fc-42be-9720-2de01283ca97" width="320" controls></video><br>Scrub through past forecasts against the readings that followed.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/434dfd60-c53a-4bca-b50d-9a8e5aae45bd" width="320" controls></video><br>The model fills gaps in the sensor record.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/49221852-40a6-4bec-bdfd-8ddd313d9d3a" width="320" controls></video><br>The model's estimate of the hour of day, against the local clock.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/d3374eb4-f7f3-42b5-bf1e-99e328fbefc6" width="320" controls></video><br>Backtests, accuracy, calibration, and Clarke and DTS error grids.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/2b77341c-9f8c-46f8-893f-9fdfaab3709d" width="320" controls></video><br>Train LoRA adapters on the phone for forecasting, infilling or backcasting.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/a6dcc3eb-f52b-4bf2-8803-b3e13068edf6" width="320" controls></video><br>Meals, with a carbohydrate appearance curve you can draw.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/296c5f4e-feac-44bd-b342-f3dafa270075" width="320" controls></video><br>Insulin doses, with an action curve you can draw.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/6533d16f-3953-4410-b155-2cffecfde8f4" width="320" controls></video><br>Time in range, GMI, CV, LBGI/HBGI, ADRR and MAGE.</td></tr>
<tr><td align="center"><video src="https://github.com/user-attachments/assets/ef631f0b-0cc8-4353-8d8a-c27599a725d2" width="320" controls></video><br>Targets, alarms, dose rails, display and backups.</td></tr>
</table>


## More

- **Alarms:** out-of-range and loss-of-signal.
- **Nightscout:** uploads readings.
- **Watch link:** pushes readings and forecasts over encrypted BLE to T1DMKDE (desktop) and T1DMAUTO (car head unit).
- **Backup:** export and restore as one gzipped JSON file.


## Your own model

Export an ExecuTorch `.pte` for the XNNPACK backend and its descriptor, as [T1DMAI](https://github.com/0xdeadf1sh/T1DMAI) does, and push both:

```sh
adb push my.xnnpack.pte my.descriptor.json /sdcard/Android/data/com.t1dm.app.pub/files/models/
```

Each descriptor in that folder loads as one model. Its `artifact` key names the `.pte`; without it, `<id>.xnnpack.pte`.


## Building

Android SDK 36 and the NDK, JDK 21, and Rust with the `aarch64-linux-android` and `x86_64-linux-android` targets and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk) on `PATH`.

```sh
./gradlew :app:assemblePublicRelease
```


## Running on Xiaomi HyperOS / MIUI

- **Settings → Apps → T1DMDROID:** Autostart on, battery **No restrictions**, **Pause app activity if unused** off.
- **System Bluetooth app:** battery **Unrestricted**.
- **Recents:** lock the app's card.
- **Lock-screen glucose:** `adb shell settings put secure lock_screen_show_silent_notifications 1`


## Related projects

- **[T1DMSIM](https://github.com/0xdeadf1sh/T1DMSIM):** the simulator whose synthetic traces pretrain the model.
- **[T1DMAI](https://github.com/0xdeadf1sh/T1DMAI):** trains the model and exports it to ExecuTorch.


## License

MIT. See [LICENSE](LICENSE).
