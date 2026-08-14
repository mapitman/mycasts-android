package com.bugzapperlabs.mycasts.data.backup

import com.bugzapperlabs.mycasts.data.local.AutoQueuePosition
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.QueueEntry
import com.bugzapperlabs.mycasts.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet.
 *  Robolectric itself (not just Room/DataStore) is needed here since org.json's Android stub
 *  throws on every call in a plain JVM unit test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppBackupTest {
    @Test
    fun toJson_fromJson_roundTripsEveryFieldOfEachEntity() {
        val feed = Feed(
            id = 42,
            title = "A Podcast",
            userTitle = "My Podcast",
            description = "A description",
            feedUrl = "https://example.com/feed.xml",
            siteUrl = "https://example.com",
            imageUrl = "https://example.com/art.png",
            displayMode = 1,
            itemsToKeep = 30,
            lastGet = 1000L,
            sortOrder = 2,
            autoDownloadEnabled = true,
            autoQueueEnabled = true,
            autoQueueMaxCount = 5,
            playbackSpeed = 1.5f,
            autoQueuePosition = AutoQueuePosition.TOP,
            volumeBoostMillibels = 600,
            startSkipSeconds = 15,
            maxDownloadsToKeep = 3,
        )
        val item = FeedItem(
            id = "item-1",
            feedId = 42,
            title = "Episode 1",
            description = "Episode description",
            url = "https://example.com/ep1",
            imageUrl = "https://example.com/ep1.png",
            itemGuid = "guid-1",
            publishDate = 2000L,
            isRead = true,
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureType = "audio/mpeg",
            enclosureLength = 12345L,
            enclosurePosition = 30.5,
            enclosureDurationMs = 60000L,
            downloadedBytes = 500L,
            downloadedFilePath = "/data/ep1.mp3",
            chaptersUrl = "https://example.com/ep1-chapters.json",
            autoDownloaded = true,
        )
        val queueEntry = QueueEntry(itemId = "item-1", position = 0, addedAt = 3000L, autoQueued = true)
        val settings = AppSettings(
            updateIntervalMinutes = 45,
            fontSize = 1.25f,
            enableImageDisplay = false,
            maxItemsPerFeed = 50,
            feedRefreshConcurrency = 4,
            defaultToAllItemsView = true,
            allowPodcastDownloadOnBattery = true,
            allowPodcastDownloadOnCellular = true,
            allowPodcastStreaming = false,
            autoDeleteFinishedDownloads = true,
            notifyOnNewItems = true,
            lastImportUrl = "https://example.com/import.opml",
            lastFeedUpdateEpochMillis = 4000L,
            lastPlayingFeedId = 42L,
            lastPlayingItemId = "item-1",
            batteryOptimizationPromptShown = true,
            notificationPermissionPromptShown = true,
            addDefaultFeedsPromptShown = true,
            podcastIndexApiKey = "key",
            podcastIndexApiSecret = "secret",
            autoDownloadNewFeedsByDefault = true,
            autoDownloadNewFeedsMaxCount = null,
            useDeviceThemeColors = false,
        )
        val backup = AppBackup(feeds = listOf(feed), feedItems = listOf(item), queueEntries = listOf(queueEntry), settings = settings)

        val restored = AppBackup.fromJson(backup.toJson())

        assertEquals(listOf(feed), restored.feeds)
        assertEquals(listOf(item), restored.feedItems)
        assertEquals(listOf(queueEntry), restored.queueEntries)
        assertEquals(settings, restored.settings)
    }

    @Test
    fun fromJson_missingOptionalSettingsField_fallsBackToDefault() {
        // A backup written by an older app version, before some AppSettings field existed, has
        // no key for it at all -- restoring it shouldn't crash, it should just use that field's
        // regular default (issue #157).
        val backup = AppBackup(feeds = emptyList(), feedItems = emptyList(), queueEntries = emptyList(), settings = AppSettings())
        val json = org.json.JSONObject(backup.toJson())
        json.getJSONObject("settings").remove("feedRefreshConcurrency")

        val restored = AppBackup.fromJson(json.toString())

        assertEquals(AppSettings().feedRefreshConcurrency, restored.settings.feedRefreshConcurrency)
    }
}
