package com.bugzapperlabs.myfeeds.data.feed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * Ported from FeedManager.UpdateFeed: GET with a UA header, redirects followed (OkHttp does this
 * by default), detect HTML vs XML by scanning for a doctype/html tag, and on HTML re-fetch the
 * feed URL discovered via [HtmlFeedDiscovery] -- matching the original's recursive
 * fetch-discover-refetch flow (bounded here to avoid infinite discovery loops).
 */
class FeedFetcher @Inject constructor(private val httpClient: OkHttpClient) {
    suspend fun fetchFeed(url: String, discoveryDepth: Int = 0): FeedFetchResult =
        withContext(Dispatchers.IO) {
            if (discoveryDepth > MAX_DISCOVERY_DEPTH) {
                return@withContext FeedFetchResult.Failure("Too many HTML discovery redirects")
            }

            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: IOException) {
                return@withContext FeedFetchResult.Failure(e.message ?: "Network error")
            }

            response.use {
                if (!it.isSuccessful) {
                    return@withContext FeedFetchResult.Failure("HTTP ${it.code}")
                }

                val content = it.body?.string().orEmpty()
                if (looksLikeHtml(content)) {
                    val discoveredUrl = HtmlFeedDiscovery.findFeedUrl(content, url)
                        ?: return@withContext FeedFetchResult.Failure("No feed link found at $url")
                    return@withContext fetchFeed(discoveredUrl, discoveryDepth + 1)
                }

                val feed = FeedParser.parse(content)
                    ?: return@withContext FeedFetchResult.Failure("Could not parse feed at $url")
                FeedFetchResult.Success(feed, resolvedUrl = url)
            }
        }

    private fun looksLikeHtml(content: String): Boolean =
        content.contains("<!doctype html", ignoreCase = true) || content.contains("<html", ignoreCase = true)

    companion object {
        private const val MAX_DISCOVERY_DEPTH = 1
        const val USER_AGENT = "MyFeeds-Android/1.0 (+https://github.com/mapitman/myfeeds-android)"
    }
}
