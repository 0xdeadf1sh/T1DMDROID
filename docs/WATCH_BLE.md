# Watch link

The specification is [`SPEC/watch.md`](https://github.com/0xdeadf1sh/T1DMCOMMON/blob/main/SPEC/watch.md)
in T1DMCOMMON, checked out beside this repository at `../T1DMCOMMON/SPEC/watch.md`. This stub
carries only what is local to T1DMDROID.

| Part | Where |
| --- | --- |
| Session crypto, record and frame codecs | `crates/t1dm-watch`; its uniffi face is `crates/t1dm-core/src/watch.rs` |
| Golden vectors | `crates/t1dm-watch/testdata/` |
| Links, pairing, push schedule | `watch/`: `WatchHub`, `WatchLink`, `ble/AndroidWatchCentral` |
| Record sources, key storage | `app/src/main/kotlin/com/t1dm/app/watch/` |
| Pairing UI | Security panel |

Debug builds forward these actions to the service for bring-up without the UI:
`adb shell am broadcast -n com.t1dm.app/.service.CgmDebugReceiver -a <action>`.

| Action | Extra |
| --- | --- |
| `com.t1dm.app.WATCH_PAIR` | — |
| `com.t1dm.app.WATCH_CONFIRM` | `--es id <device_id>` for that device's rotation; none for a new pairing |
| `com.t1dm.app.WATCH_ROTATE` | `--es id <device_id>`, optional with one pairing |
| `com.t1dm.app.WATCH_UNPAIR` | `--es id <device_id>`, optional with one pairing |
| `com.t1dm.app.WATCH_PUSH` | — |
