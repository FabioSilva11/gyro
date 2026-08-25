package com.gyrobridge.app

import android.app.Application
import com.gyrobridge.app.core.AppGraph

class GyroBridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
    }
}
