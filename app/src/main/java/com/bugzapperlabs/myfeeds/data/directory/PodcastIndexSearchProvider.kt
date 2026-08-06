package com.bugzapperlabs.myfeeds.data.directory

import com.bugzapperlabs.myfeeds.data.feed.FeedFetcher
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live podcast search against podcastindex.org's `/search/byterm` endpoint (issue #93) -- one of
 * two [PodcastSearchProvider] implementations, see [PodcastSearchService] for how the active one
 * is chosen. Docs: https://podcastindex-org.github.io/docs-api/. Deliberately hand-rolled with
 * just [OkHttpClient] and [org.json] (both already dependencies) rather than a new HTTP/JSON
 * library, per issue #93 -- only the one search endpoint is implemented, not the full API.
 *
 * Every request needs a free API key+secret (registered by the user themselves at
 * podcastindex.org and entered in Settings, see [SettingsDataStore]) -- there's no ToS-compliant
 * way to bundle a single shared key in an open-source app. Auth is a signed-request scheme, not
 * a static header: `Authorization` is `sha1(apiKey + apiSecret + unixTime)` (lowercase hex),
 * alongside the raw `X-Auth-Key` and the `X-Auth-Date` (the same unix time, as a string) the
 * server recomputes the hash against; the timestamp must be within roughly a 5-minute window of
 * the server's clock.
 */
@Singleton
class PodcastIndexSearchProvider @Inject constructor(
    private val httpClient: OkHttpClient,
    private val settingsDataStore: SettingsDataStore,
) : PodcastSearchProvider {

    /** Overridable only for tests (issue #93) -- direct construction bypasses Hilt entirely, so
     *  pointing this at a MockWebServer doesn't touch the DI graph in any way. */
    internal var baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl()

    /** Thrown for any failure (missing credentials, network error, non-2xx, malformed response)
     *  so [PodcastSearchService] can catch it and fall back to the offline directory uniformly. */
    class SearchFailedException(message: String, cause: Throwable? = null) : IOException(message, cause)

    override suspend fun search(query: String, limit: Int): List<PodcastSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        val settings = settingsDataStore.settings.first()
        val apiKey = settings.podcastIndexApiKey?.trim().orEmpty()
        val apiSecret = settings.podcastIndexApiSecret?.trim().orEmpty()
        if (apiKey.isEmpty() || apiSecret.isEmpty()) {
            throw SearchFailedException("PodcastIndex API key/secret not configured")
        }

        val unixTime = (System.currentTimeMillis() / 1000).toString()
        val authorization = sha1Hex(apiKey + apiSecret + unixTime)

        val url = baseUrl.newBuilder()
            .addPathSegments("search/byterm")
            .addQueryParameter("q", trimmed)
            .addQueryParameter("max", limit.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", FeedFetcher.USER_AGENT)
            .header("X-Auth-Date", unixTime)
            .header("X-Auth-Key", apiKey)
            .header("Authorization", authorization)
            .build()

        val body = try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw SearchFailedException("HTTP ${response.code}")
                response.body?.string() ?: throw SearchFailedException("Empty response body")
            }
        } catch (e: IOException) {
            throw SearchFailedException(e.message ?: "Network error", e)
        }

        parseResults(body)
    }

    private fun parseResults(body: String): List<PodcastSearchResult> {
        val feeds: JSONArray = try {
            JSONObject(body).optJSONArray("feeds") ?: JSONArray()
        } catch (e: org.json.JSONException) {
            throw SearchFailedException("Could not parse PodcastIndex response", e)
        }

        return buildList {
            for (i in 0 until feeds.length()) {
                val feed = feeds.optJSONObject(i) ?: continue
                val title = feed.optString("title").takeIf { it.isNotBlank() } ?: continue
                val feedUrl = feed.optString("url").takeIf { it.isNotBlank() } ?: continue
                val author = feed.optString("author").takeIf { it.isNotBlank() }
                    ?: feed.optString("ownerName").takeIf { it.isNotBlank() }
                    ?: ""
                val description = feed.optString("description").takeIf { it.isNotBlank() }
                add(PodcastSearchResult(title = title, feedUrl = feedUrl, subtitle = author, description = description))
            }
        }
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.podcastindex.org/api/1.0/"
    }
}
