package com.silf.app

import android.app.Application
import com.silf.app.di.AppContainer
import com.silf.app.di.DefaultAppContainer

class SilfApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer()
    }
}
