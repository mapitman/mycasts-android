package com.bugzapperlabs.myfeeds.playback

import com.bugzapperlabs.myfeeds.data.feed.FeedFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * Fetches and parses a Podcasting 2.0 external chapters JSON file (issue #95), pointed to by
 * [com.bugzapperlabs.myfeeds.data.local.FeedItem.chaptersUrl]. Never throws -- a failed fetch or malformed
 * response just means no chapters for this episode, not a playback error.
 */
class ChaptersFetcher @Inject constructor(private val httpClient: OkHttpClient) {
    suspend fun fetch(url: String): List<Chapter> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", FeedFetcher.USER_AGENT).build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (_: IOException) {
            return@withContext emptyList()
        }
        response.use {
            if (!it.isSuccessful) return@withContext emptyList()
            ChaptersParser.parse(it.body?.string().orEmpty())
        }
    }
}
