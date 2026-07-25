package com.bugzapperlabs.myfeeds.data.feed

import java.time.Instant

data class ParsedEnclosure(
    val url: String,
    val type: String = "",
    val length: Long = 0,
)

data class ParsedFeedItem(
    val title: String,
    val url: String,
    val description: String,
    val publishDate: Instant?,
    val itemGuid: String,
    val enclosure: ParsedEnclosure?,
    /** From `itunes:duration` (RSS podcast feeds only) -- lets the reader show a resume position
     *  proportionally (issue #75) before the episode has ever actually been buffered/played. */
    val durationMs: Long? = null,
    /** From the Podcasting 2.0 `<podcast:chapters url="..."/>` element (issue #95) -- points to an
     *  external JSON chapters file, fetched lazily at playback time rather than at parse time. */
    val chaptersUrl: String? = null,
)

data class ParsedFeed(
    val title: String,
    val siteUrl: String,
    val description: String,
    val imageUrl: String?,
    val items: List<ParsedFeedItem>,
)
