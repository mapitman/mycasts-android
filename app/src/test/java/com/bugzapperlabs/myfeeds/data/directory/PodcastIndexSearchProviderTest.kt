package com.bugzapperlabs.myfeeds.data.directory

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PodcastIndexSearchProviderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var settingsDataStore: SettingsDataStore
    private lateinit var provider: PodcastIndexSearchProvider

    @Before
    fun setUp() = runTest {
        server = MockWebServer()
        server.start()
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.newFolder(), "test.preferences_pb") },
        )
        settingsDataStore = SettingsDataStore(dataStore)
        settingsDataStore.setPodcastIndexApiKey("test-key")
        settingsDataStore.setPodcastIndexApiSecret("test-secret")
        provider = PodcastIndexSearchProvider(OkHttpClient(), settingsDataStore).apply {
            baseUrl = server.url("/api/1.0/")
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun search_blankQuery_returnsEmptyWithoutRequesting() = runTest {
        val results = provider.search("")

        assertTrue(results.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun search_noCredentialsConfigured_throws() = runTest {
        settingsDataStore.setPodcastIndexApiKey(null)
        settingsDataStore.setPodcastIndexApiSecret(null)

        try {
            provider.search("linux")
            fail("expected SearchFailedException")
        } catch (e: PodcastIndexSearchProvider.SearchFailedException) {
            // expected
        }
    }

    @Test
    fun search_sendsCorrectAuthHeaders() = runTest {
        var recorded: RecordedRequest? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                recorded = request
                return MockResponse().setResponseCode(200).setBody("""{"status":"true","feeds":[],"count":0}""")
            }
        }

        provider.search("linux")

        val request = requireNotNull(recorded)
        assertEquals("test-key", request.getHeader("X-Auth-Key"))
        val authDate = requireNotNull(request.getHeader("X-Auth-Date"))
        val expectedHash = sha1Hex("test-key" + "test-secret" + authDate)
        assertEquals(expectedHash, request.getHeader("Authorization"))
        assertTrue(request.path?.startsWith("/api/1.0/search/byterm?") == true)
        assertTrue(request.path?.contains("q=linux") == true)
    }

    @Test
    fun search_parsesFeedResultsFromResponse() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "status": "true",
                  "feeds": [
                    {
                      "id": 1,
                      "title": "Linux Out Loud",
                      "url": "https://example.com/feed.xml",
                      "author": "The Hosts",
                      "description": "A podcast about Linux."
                    },
                    {
                      "id": 2,
                      "title": "No Author Feed",
                      "url": "https://example.com/other.xml",
                      "ownerName": "Owner Name",
                      "description": ""
                    }
                  ],
                  "count": 2
                }
                """.trimIndent(),
            ),
        )

        val results = provider.search("linux")

        assertEquals(2, results.size)
        assertEquals(
            PodcastSearchResult(
                title = "Linux Out Loud",
                feedUrl = "https://example.com/feed.xml",
                subtitle = "The Hosts",
                description = "A podcast about Linux.",
            ),
            results[0],
        )
        assertEquals("Owner Name", results[1].subtitle)
        assertNull(results[1].description)
    }

    @Test
    fun search_httpFailure_throws() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            provider.search("linux")
            fail("expected SearchFailedException")
        } catch (e: PodcastIndexSearchProvider.SearchFailedException) {
            // expected
        }
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
