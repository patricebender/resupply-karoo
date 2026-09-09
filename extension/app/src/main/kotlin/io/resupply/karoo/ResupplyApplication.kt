package io.resupply.karoo

import android.app.Application
import timber.log.Timber

class ResupplyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
