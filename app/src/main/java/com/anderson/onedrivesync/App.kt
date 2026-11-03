package com.anderson.onedrivesync

import android.app.Application
import com.anderson.onedrivesync.core.AppContainer
import com.anderson.onedrivesync.notify.Notifier
import com.anderson.onedrivesync.work.Scheduler

class App : Application() {
    lateinit var container: AppContainer
        private set

    val appContainer: AppContainer get() = container

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
        Scheduler.ensurePeriodic(this)
        Scheduler.ensureUpdateCheck(this)
    }
}
