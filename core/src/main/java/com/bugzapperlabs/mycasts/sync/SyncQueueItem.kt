package com.bugzapperlabs.mycasts.sync

/**
 * One Next Up entry as pushed to/from a paired Wear OS watch (issue #276) -- a flattened,
 * transport-friendly projection of [com.bugzapperlabs.mycasts.data.local.QueuedEpisode], not the
 * Room entity itself (the watch has its own separate database, populated only by applying these).
 */
data class SyncQueueItem(
    val itemId: String,
    val feedId: Long,
    val title: String?,
    val feedTitle: String?,
    val enclosureUrl: String?,
    val artworkUrl: String?,
    val durationMs: Long?,
    val positionMs: Long?,
    /** Queue order, lowest first -- explicit rather than relying on list order surviving
     *  serialization round-trips. */
    val orderIndex: Int,
)
