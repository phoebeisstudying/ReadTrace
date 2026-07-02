package com.dmer.neoreaderrecords

import android.app.Application

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
