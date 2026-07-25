package com.bugzapperlabs.myfeeds.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ported from MyFeeds.Data/FeedDataContext.cs Feed table. `isUpdating` from the original was
 * runtime-only (no [Column] attribute) and is intentionally not persisted here.
 */
@Entity(
    tableName = "feeds",
    indices = [Index("userTitle"), Index("title")],
)
data class Feed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String? = null,
    val userTitle: String? = null,
    val description: String? = null,
    val feedUrl: String? = null,
    val siteUrl: String? = null,
    val imageUrl: String? = null,
    val displayMode: Int? = null,
    val itemsToKeep: Int? = null,
    val lastGet: Long? = null,
    val sortOrder: Int? = null,
    /** New in this port (issue #23) -- the original MyFeeds only supported manual downloads. */
    val autoDownloadEnabled: Boolean = false,
    /** New episodes auto-add to the Next Up queue (issue #68) when this feed refreshes. */
    val autoQueueEnabled: Boolean = false,
    /** Only enforced when [autoQueueEnabled]; null means unlimited (keep all auto-queued episodes). */
    val autoQueueMaxCount: Int? = null,
    /** Playback speed applied when starting an episode of this feed (issue #70). */
    val playbackSpeed: Float = 1.0f,
    /** Where new episodes land in the Next Up queue when [autoQueueEnabled] (issue #166). Defaults
     *  to [AutoQueuePosition.BOTTOM] to preserve pre-existing auto-queue behavior for existing feeds. */
    val autoQueuePosition: AutoQueuePosition = AutoQueuePosition.BOTTOM,
    /** Volume boost applied when playing an episode of this feed (issue #199), as an
     *  [android.media.audiofx.LoudnessEnhancer] target gain in millibels; 0 means no boost. */
    val volumeBoostMillibels: Int = 0,
    /** Seconds to skip from the start when an episode of this feed begins playing fresh, i.e. has
     *  no saved resume position (issue #200); 0 means no skip. */
    val startSkipSeconds: Int = 0,
    /** Only enforced against auto-downloaded episodes ([FeedItem.autoDownloaded]) of this feed
     *  (issue #250); null means unlimited. Manually-downloaded episodes are never auto-deleted by
     *  this cap, and a queued or currently-playing episode is exempt from eviction even if it's
     *  the oldest auto-download, mirroring [com.bugzapperlabs.myfeeds.data.repository.FeedRepository.trimToItemsToKeep]'s
     *  queue exemption. */
    val maxDownloadsToKeep: Int? = null,
)

/** Where auto-queued episodes are inserted in the Next Up queue (issue #166). Room stores enums
 *  natively as their [name] string, so no [androidx.room.TypeConverter] is needed. */
enum class AutoQueuePosition {
    TOP,
    BOTTOM,
}
