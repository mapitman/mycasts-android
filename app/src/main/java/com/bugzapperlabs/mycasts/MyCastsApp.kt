package com.bugzapperlabs.mycasts

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MyCastsApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(NotificationManager::class.java)

        val newItemsChannel = NotificationChannel(
            NEW_ITEMS_CHANNEL_ID,
            getString(R.string.notification_new_episodes_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.notification_new_episodes_channel_description) }
        notificationManager.createNotificationChannel(newItemsChannel)

        // IMPORTANCE_LOW (issue #15): a silent, no-heads-up progress notification while an
        // episode downloads -- expected/ongoing activity, not something worth alerting over.
        val downloadsChannel = NotificationChannel(
            DOWNLOADS_CHANNEL_ID,
            getString(R.string.notification_downloads_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_downloads_channel_description) }
        notificationManager.createNotificationChannel(downloadsChannel)

        // IMPORTANCE_LOW (issue #16): same reasoning as the downloads channel above -- a silent
        // progress notification while feeds refresh in the background, separate from the
        // IMPORTANCE_DEFAULT "new items" channel, which is the actual alert-worthy summary.
        val feedRefreshChannel = NotificationChannel(
            FEED_REFRESH_CHANNEL_ID,
            getString(R.string.notification_feed_refresh_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_feed_refresh_channel_description) }
        notificationManager.createNotificationChannel(feedRefreshChannel)

        // issue #161: ProcessLifecycleOwner.onStart fires only on a genuine foreground
        // transition -- some Activity actually becoming visible -- unlike MainActivity's own
        // onCreate/onStart, which also fire on configuration changes, and unlike a scheduled
        // FeedRefreshWorker run, which never shows an Activity at all. That's exactly the "running
        // in the background doesn't count as open" distinction this feature needs.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                appScope.launch { settingsDataStore.markAppOpened() }
                // Once opened, the podcast list itself now shows which feeds have new episodes
                // (issue #161) -- a still-visible "N new episodes" notification from before this
                // open would otherwise sit there showing a stale count that no longer matches what
                // markAppOpened just reset.
                NotificationManagerCompat.from(this@MyCastsApp).cancel(NEW_ITEMS_NOTIFICATION_ID)
            }
        })
    }

    companion object {
        const val NEW_ITEMS_CHANNEL_ID = "new_items"
        const val DOWNLOADS_CHANNEL_ID = "downloads"
        const val FEED_REFRESH_CHANNEL_ID = "feed_refresh"
        const val NEW_ITEMS_NOTIFICATION_ID = 1
    }
}
