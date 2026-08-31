package com.t1dm.app

import kotlinx.coroutines.launch
import timber.log.Timber

/** Runs every start, with no done-flag: a config import can re-inject a retired id. */
object RetiredThemeMigration {

    fun run(app: T1dmApplication) {
        val container = app.container
        container.appScope.launch {
            container.settingsStore.coerceRetiredThemeId()?.let {
                Timber.i("persisted theme id no longer resolves — coerced to %s", it)
            }
        }
    }
}
