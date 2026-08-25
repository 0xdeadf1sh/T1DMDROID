package com.t1dm.app

import kotlinx.coroutines.launch
import timber.log.Timber

/** Runs every start, with no done-flag: a config import can re-inject a retired id. The launcher half
 *  is synchronous on the main thread — binder traffic, not the disk IO StrictMode watches. Re-pointing
 *  the alias stays in [MainActivity.onStop]; toggling one while foregrounded can get us evicted. */
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
