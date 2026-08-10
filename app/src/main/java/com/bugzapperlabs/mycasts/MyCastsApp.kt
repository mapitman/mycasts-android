package com.bugzapperlabs.mycasts

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyCastsApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(NotificationManager::class.java)

        val newItemsChannel = NotificationChannel(
            NEW_ITEMS_CHANNEL_ID,
            getString(R.string.notification_new_items_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.notification_new_items_channel_description) }
        notificationManager.createNotificationChannel(newItemsChannel)

        // IMPORTANCE_LOW (issue #15): a silent, no-heads-up progress notification while an
        // episode downloads -- expected/ongoing activity, not something worth alerting over.
        val downloadsChannel = NotificationChannel(
            DOWNLOADS_CHANNEL_ID,
            getString(R.string.notification_downloads_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_downloads_channel_description) }
        notificationManager.createNotificationChannel(downloadsChannel)
    }

    companion object {
        const val NEW_ITEMS_CHANNEL_ID = "new_items"
        const val DOWNLOADS_CHANNEL_ID = "downloads"
    }
}
