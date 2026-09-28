package com.silf.app

import android.app.Application
import com.silf.app.di.AppContainer

class SilfApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Container is initialized lazily now.
    }
}
