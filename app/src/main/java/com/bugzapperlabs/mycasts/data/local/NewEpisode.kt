package com.bugzapperlabs.mycasts.data.local

import androidx.room.Embedded

/** An episode found since the app was last opened (issue #161), joined with its parent feed's
 *  display title and artwork for the "New episodes" screen. */
data class NewEpisode(
    @Embedded val item: FeedItem,
    val feedTitle: String?,
    val feedImageUrl: String?,
)
