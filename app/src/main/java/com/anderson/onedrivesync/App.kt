package com.anderson.onedrivesync

import android.app.Application
import com.anderson.onedrivesync.core.AppContainer

class App : Application() {
    lateinit var container: AppContainer
        private set

    val appContainer: AppContainer get() = container

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
