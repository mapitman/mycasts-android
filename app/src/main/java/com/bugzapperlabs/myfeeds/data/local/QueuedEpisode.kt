package com.bugzapperlabs.myfeeds.data.local

import androidx.room.Embedded

/** A queued episode joined with its parent feed's display title and image, in queue order. */
data class QueuedEpisode(
    @Embedded val item: FeedItem,
    val feedTitle: String?,
    val feedImageUrl: String?,
)
