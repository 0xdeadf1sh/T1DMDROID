package com.t1dm.app

import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The startup repair for themes retired from a shipped build (the Windows XP and Teto Kasane palettes
 * were removed with their launcher aliases and icon geometry). Two pieces of state name a theme by id
 * and outlive the upgrade that deleted it, so neither can be left to the UI to notice:
 *
 *  - the persisted `ui.theme` kv row — benign to render, since every id→palette resolver falls back to
 *    Tron, but it leaves Settings → Display with no theme chip selected and gets carried forward by the
 *    config export;
 *  - the per-component enabled state PackageManager keeps for the launcher `<activity-alias>`es, which
 *    outranks the manifest and can strip the app of its home-screen entry outright — see
 *    [LauncherIconManager.ensureLauncherEntry] for why that is the sharp edge.
 *
 * Both halves are idempotent, so this simply runs on every process start rather than carrying a
 * "migration done" flag: the launcher probe is a binder round-trip and the kv probe a single indexed
 * read, and a config import can re-inject a retired id long after any one-shot flag would have been set.
 *
 * The launcher half runs SYNCHRONOUSLY on the main thread — a stranded home-screen icon must not wait on
 * a coroutine that a cold-start kill could pre-empt, and PackageManager calls are binder traffic, not the
 * disk IO [T1dmApplication]'s StrictMode policy watches for. The kv half touches Room, so it goes to the
 * app scope. Neither half re-points the alias to the corrected theme: that swap stays where
 * [MainActivity.onStop] already performs it, since toggling an alias while the task is foregrounded is
 * what HyperOS's recents can evict us for. The corrected id converges there on the way out.
 */
object RetiredThemeMigration {

    fun run(app: T1dmApplication) {
        LauncherIconManager.ensureLauncherEntry(app)
        val container = app.container
        container.appScope.launch {
            container.settingsStore.coerceRetiredThemeId()?.let {
                Timber.i("persisted theme id no longer resolves — coerced to %s", it)
            }
        }
    }
}
