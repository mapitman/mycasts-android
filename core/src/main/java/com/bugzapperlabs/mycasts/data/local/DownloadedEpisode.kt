package com.bugzapperlabs.mycasts.data.local

import androidx.room.Embedded

/** A downloaded (or downloading) episode joined with its parent feed's display title and artwork. */
data class DownloadedEpisode(
    @Embedded val item: FeedItem,
    val feedTitle: String?,
    val feedImageUrl: String?,
)
