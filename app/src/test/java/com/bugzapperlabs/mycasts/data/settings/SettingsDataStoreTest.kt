package com.bugzapperlabs.mycasts.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsDataStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
    }

    @Test
    fun defaults_matchOriginalSettingsViewModelDefaults() = runTest {
        val settings = settingsDataStore.settings.first()

        assertEquals(AppSettings(), settings)
        assertEquals(30L, settings.updateIntervalMinutes)
        assertEquals(FontSize.NORMAL, settings.fontSize)
        assertTrue(settings.enableImageDisplay)
        assertEquals(20, settings.maxItemsPerFeed)
        assertFalse(settings.defaultToAllItemsView)
        assertFalse(settings.allowPodcastDownloadOnBattery)
        assertFalse(settings.allowPodcastDownloadOnCellular)
        assertTrue(settings.allowPodcastStreaming)
        assertFalse(settings.autoDeleteFinishedDownloads)
        assertNull(settings.lastImportUrl)
    }

    @Test
    fun writingEachSetting_roundTripsThroughSettingsFlow() = runTest {
        settingsDataStore.setUpdateIntervalMinutes(60)
        settingsDataStore.setFontSize(FontSize.SMALL)
        settingsDataStore.setEnableImageDisplay(false)
        settingsDataStore.setMaxItemsPerFeed(50)
        settingsDataStore.setDefaultToAllItemsView(true)
        settingsDataStore.setAllowPodcastDownloadOnBattery(true)
        settingsDataStore.setAllowPodcastDownloadOnCellular(true)
        settingsDataStore.setAllowPodcastStreaming(false)
        settingsDataStore.setAutoDeleteFinishedDownloads(true)
        settingsDataStore.setLastImportUrl("https://example.com/feeds.opml")
        settingsDataStore.setLastFeedUpdateEpochMillis(123456789L)

        val settings = settingsDataStore.settings.first()

        assertEquals(60L, settings.updateIntervalMinutes)
        assertEquals(FontSize.SMALL, settings.fontSize)
        assertFalse(settings.enableImageDisplay)
        assertEquals(50, settings.maxItemsPerFeed)
        assertTrue(settings.defaultToAllItemsView)
        assertTrue(settings.allowPodcastDownloadOnBattery)
        assertTrue(settings.allowPodcastDownloadOnCellular)
        assertFalse(settings.allowPodcastStreaming)
        assertTrue(settings.autoDeleteFinishedDownloads)
        assertEquals("https://example.com/feeds.opml", settings.lastImportUrl)
        assertEquals(123456789L, settings.lastFeedUpdateEpochMillis)
    }

    @Test
    fun setLastImportUrl_null_removesKey() = runTest {
        settingsDataStore.setLastImportUrl("https://example.com/feeds.opml")

        settingsDataStore.setLastImportUrl(null)

        assertNull(settingsDataStore.settings.first().lastImportUrl)
    }

    @Test
    fun setAddDefaultFeedsPromptShown_roundTrips() = runTest {
        assertFalse(settingsDataStore.settings.first().addDefaultFeedsPromptShown)

        settingsDataStore.setAddDefaultFeedsPromptShown(true)

        assertTrue(settingsDataStore.settings.first().addDefaultFeedsPromptShown)
    }

    @Test
    fun reset_clearsBackToDefaults() = runTest {
        settingsDataStore.setMaxItemsPerFeed(999)
        settingsDataStore.setAllowPodcastStreaming(false)

        settingsDataStore.reset()

        assertEquals(AppSettings(), settingsDataStore.settings.first())
    }
}
