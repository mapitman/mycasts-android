package com.bugzapperlabs.myfeeds.playback

import org.json.JSONObject

/** A single podcast chapter (issue #95), from a Podcasting 2.0 external JSON chapters file. */
data class Chapter(
    val startTimeMs: Long,
    val title: String?,
)

/**
 * Parses the Podcasting 2.0 chapters JSON format (https://github.com/Podcastindex-org/podcast-namespace/blob/main/chapters/jsonChapters.md):
 * `{"chapters": [{"startTime": <seconds>, "title": "...", "toc": true|false}, ...]}`. `startTime`
 * is in seconds (can be fractional), converted here to milliseconds to match [Chapter.startTimeMs]
 * and player position units elsewhere in the app.
 *
 * Chapters with `"toc": false` (e.g. value-for-value boost/tip markers some hosts inject) are
 * excluded -- they're not meant to appear in a visible table of contents/navigation, per spec.
 */
object ChaptersParser {
    fun parse(json: String): List<Chapter> = try {
        val root = JSONObject(json)
        val chapters = root.optJSONArray("chapters") ?: return emptyList()
        buildList {
            for (i in 0 until chapters.length()) {
                val chapter = chapters.optJSONObject(i) ?: continue
                if (!chapter.optBoolean("toc", true)) continue
                val startTimeSeconds = if (chapter.has("startTime")) chapter.optDouble("startTime") else continue
                if (startTimeSeconds.isNaN()) continue
                add(Chapter(startTimeMs = (startTimeSeconds * 1000).toLong(), title = chapter.optString("title").ifEmpty { null }))
            }
        }.sortedBy { it.startTimeMs }
    } catch (_: Exception) {
        emptyList()
    }
}
