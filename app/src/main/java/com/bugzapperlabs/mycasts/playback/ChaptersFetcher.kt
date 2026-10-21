package com.bugzapperlabs.mycasts.playback

import com.bugzapperlabs.mycasts.data.feed.FeedFetcher
import com.bugzapperlabs.mycasts.data.feed.MAX_JSON_BYTES
import com.bugzapperlabs.mycasts.data.feed.stringLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * Fetches and parses a Podcasting 2.0 external chapters JSON file (issue #95), pointed to by
 * [com.bugzapperlabs.mycasts.data.local.FeedItem.chaptersUrl]. Never throws -- a failed fetch or malformed
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
            // issue #300: capped; an oversized or interrupted body just means no chapters.
            try {
                ChaptersParser.parse(it.body?.stringLimited(MAX_JSON_BYTES).orEmpty())
            } catch (_: IOException) {
                emptyList()
            }
        }
    }
}
