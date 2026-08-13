package com.bugzapperlabs.mycasts.data.feed

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

/** Whether any of this feed's items look like playable podcast episodes -- same audio-MIME-type
 *  signal as [com.bugzapperlabs.mycasts.data.local.isPodcastEpisode], checked before subscribing
 *  rather than after (issue #122): a feed with no audio enclosures at all is a plain article/news
 *  feed, which the app no longer accepts as a new subscription. */
val ParsedFeed.hasPodcastEpisode: Boolean
    get() = items.any { it.enclosure?.type?.startsWith("audio/", ignoreCase = true) == true }
