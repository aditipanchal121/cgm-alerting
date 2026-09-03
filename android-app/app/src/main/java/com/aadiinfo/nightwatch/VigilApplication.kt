package com.aadiinfo.nightwatch

import android.app.Application
import com.aadiinfo.nightwatch.di.AppContainer
import com.aadiinfo.nightwatch.notifications.NotificationHelper

class VigilApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer()
        NotificationHelper.createChannels(this)
    }
}
