package com.bugzapperlabs.myfeeds.data.local

import androidx.room.Embedded

/** A downloaded (or downloading) episode joined with its parent feed's display title. */
data class DownloadedEpisode(
    @Embedded val item: FeedItem,
    val feedTitle: String?,
)
