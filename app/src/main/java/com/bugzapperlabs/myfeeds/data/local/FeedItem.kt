package com.bugzapperlabs.myfeeds.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ported from MyFeeds.Data/FeedDataContext.cs FeedItem table. `id` is a client-generated UUID
 * (matches the original's `UniqueIdentifier` PK); dedup on ingest is by `itemGuid` (fallback
 * chain guid -> id -> link, applied in the feed update engine, not here).
 *
 * `enclosureRequestId` (a WP BackgroundTransferService request id) has no Android equivalent and
 * isn't persisted -- WorkManager tracks its own requests, looked up by this row's [id] instead.
 * `downloadedBytes`/`downloadedFilePath` (issue #23) replace the original's runtime-only
 * `EnclosureBytesDownloaded`/`IsEnclosureDownloading`: they need to be persisted here since
 * Android downloads happen in a background worker, not a live in-memory transfer the UI already
 * holds a reference to.
 */
@Entity(
    tableName = "feed_items",
    foreignKeys = [
        ForeignKey(
            entity = Feed::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("publishDate"), Index("feedId")],
)
data class FeedItem(
    @PrimaryKey val id: String,
    val feedId: Long,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val imageUrl: String? = null,
    val itemGuid: String? = null,
    val publishDate: Long? = null,
    val isRead: Boolean = false,
    val enclosureUrl: String? = null,
    val enclosureType: String? = null,
    val enclosureLength: Long? = null,
    val enclosurePosition: Double? = null,
    /** From RSS `itunes:duration` (issue #75), where the feed provides it -- lets the reader show
     *  the saved resume position proportionally before the episode has ever been buffered/played. */
    val enclosureDurationMs: Long? = null,
    val downloadedBytes: Long? = null,
    val downloadedFilePath: String? = null,
    /** From the Podcasting 2.0 `<podcast:chapters>` element (issue #95); chapters themselves are
     *  fetched from this URL lazily at playback time, not persisted. */
    val chaptersUrl: String? = null,
    /** Whether this episode's download was started by [Feed.autoDownloadEnabled] rather than a
     *  manual tap (issue #250) -- only auto-downloaded episodes are candidates for
     *  [Feed.maxDownloadsToKeep] eviction. */
    val autoDownloaded: Boolean = false,
)

/**
 * Whether this item is a playable podcast episode, as opposed to a plain article whose feed
 * happens to set `<enclosure>` for something else (commonly a featured image -- e.g. Windows
 * Central and Sky News both publish `type="image/jpeg"` enclosures on ordinary articles). Podcast
 * RSS/Atom enclosures set a MIME type starting with `audio/`; that's the actual signal, not
 * enclosure presence alone.
 */
val FeedItem.isPodcastEpisode: Boolean
    get() = enclosureUrl != null && enclosureType?.startsWith("audio/", ignoreCase = true) == true
