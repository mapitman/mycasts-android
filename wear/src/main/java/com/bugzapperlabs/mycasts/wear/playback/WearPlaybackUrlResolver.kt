package com.bugzapperlabs.mycasts.wear.playback

import com.bugzapperlabs.mycasts.data.local.FeedItem

/**
 * Always resolves to [FeedItem.enclosureUrl] (issue #276) -- the watch streams directly over its
 * own connection in phase 1, unlike `:app`'s [com.bugzapperlabs.mycasts.playback.PlaybackUrlResolver],
 * which prefers a downloaded local file. Deliberately never looks at [FeedItem.downloadedFilePath]:
 * that field only carries meaning once phase 2 (issue #277) adds sending downloaded episodes to
 * the watch, and this resolver is the regression guard that phase 2 work will need to
 * deliberately change, not accidentally inherit.
 */
object WearPlaybackUrlResolver {
    fun resolve(item: FeedItem): String? = item.enclosureUrl
}
