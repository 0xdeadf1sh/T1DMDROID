package com.t1dm.app

import android.app.Application
import timber.log.Timber

class T1dmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}
