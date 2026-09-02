package com.bugzapperlabs.mycasts.sync

import com.bugzapperlabs.mycasts.data.local.QueuedEpisode
import kotlin.math.roundToLong

/** [QueuedEpisode] (Room-shaped, phone-local) -> [SyncQueueItem] (transport-shaped) (issue #276). */
fun List<QueuedEpisode>.toSyncQueueItems(): List<SyncQueueItem> = mapIndexed { index, episode ->
    SyncQueueItem(
        itemId = episode.item.id,
        feedId = episode.item.feedId,
        title = episode.item.title,
        feedTitle = episode.feedTitle,
        enclosureUrl = episode.item.enclosureUrl,
        artworkUrl = episode.item.imageUrl ?: episode.feedImageUrl,
        durationMs = episode.item.enclosureDurationMs,
        positionMs = episode.item.enclosurePosition?.let { seconds -> (seconds * 1000).roundToLong() },
        orderIndex = index,
    )
}
