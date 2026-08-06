package com.bugzapperlabs.myfeeds.data.directory

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PodcastSearchServiceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var settingsDataStore: SettingsDataStore

    private val onlineResult = PodcastSearchResult("Online Result", "https://example.com/online.xml", "Author", null)
    private val offlineResult = PodcastSearchResult("Offline Result", "https://example.com/offline.xml", "Category", null)

    private class FakeSearchProvider(
        private val results: List<PodcastSearchResult> = emptyList(),
        private val failure: Throwable? = null,
    ) : PodcastSearchProvider {
        var callCount = 0
            private set

        override suspend fun search(query: String, limit: Int): List<PodcastSearchResult> {
            callCount++
            failure?.let { throw it }
            return results
        }
    }

    @Before
    fun setUp() {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
    }

    @Test
    fun search_noCredentialsConfigured_usesOfflineOnly() = runTest {
        val online = FakeSearchProvider(listOf(onlineResult))
        val offline = FakeSearchProvider(listOf(offlineResult))
        val service = PodcastSearchService(online, offline, settingsDataStore)

        val results = service.search("linux")

        assertEquals(listOf(offlineResult), results)
        assertEquals(0, online.callCount)
        assertEquals(1, offline.callCount)
    }

    @Test
    fun search_credentialsConfigured_usesOnline() = runTest {
        settingsDataStore.setPodcastIndexApiKey("key")
        settingsDataStore.setPodcastIndexApiSecret("secret")
        val online = FakeSearchProvider(listOf(onlineResult))
        val offline = FakeSearchProvider(listOf(offlineResult))
        val service = PodcastSearchService(online, offline, settingsDataStore)

        val results = service.search("linux")

        assertEquals(listOf(onlineResult), results)
        assertEquals(1, online.callCount)
        assertEquals(0, offline.callCount)
    }

    @Test
    fun search_onlyApiKeySet_stillUsesOffline() = runTest {
        settingsDataStore.setPodcastIndexApiKey("key")
        val online = FakeSearchProvider(listOf(onlineResult))
        val offline = FakeSearchProvider(listOf(offlineResult))
        val service = PodcastSearchService(online, offline, settingsDataStore)

        val results = service.search("linux")

        assertEquals(listOf(offlineResult), results)
        assertEquals(0, online.callCount)
    }

    @Test
    fun search_onlineFails_fallsBackToOffline() = runTest {
        settingsDataStore.setPodcastIndexApiKey("key")
        settingsDataStore.setPodcastIndexApiSecret("secret")
        val online = FakeSearchProvider(failure = IOException("network error"))
        val offline = FakeSearchProvider(listOf(offlineResult))
        val service = PodcastSearchService(online, offline, settingsDataStore)

        val results = service.search("linux")

        assertEquals(listOf(offlineResult), results)
        assertEquals(1, online.callCount)
        assertEquals(1, offline.callCount)
    }

    @Test(expected = CancellationException::class)
    fun search_onlineCancelled_propagatesRatherThanFallingBack() = runTest {
        settingsDataStore.setPodcastIndexApiKey("key")
        settingsDataStore.setPodcastIndexApiSecret("secret")
        val online = FakeSearchProvider(failure = CancellationException("cancelled"))
        val offline = FakeSearchProvider(listOf(offlineResult))
        val service = PodcastSearchService(online, offline, settingsDataStore)

        service.search("linux")
    }
}
