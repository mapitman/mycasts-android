package com.bugzapperlabs.mycasts.data.repository

import com.bugzapperlabs.mycasts.data.local.FeedItem

/**
 * Seam between [QueueRepository] and the download subsystem, which lives outside `:core` (it
 * pulls in WorkManager and isn't needed by a streaming-only Wear OS build). `app` binds this to
 * its real `EnclosureDownloadRepository`; a wear module can bind a no-op instead.
 */
interface QueueDownloadTrigger {
    suspend fun ensureDownloaded(item: FeedItem)
}
