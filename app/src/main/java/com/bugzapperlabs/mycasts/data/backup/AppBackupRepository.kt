package com.bugzapperlabs.mycasts.data.backup

import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Full local-state backup/restore (issue #157) -- unlike OPML export/import, which only carries
 * the subscribed-feed list, this round-trips read/played state, queue order, per-feed settings,
 * and app settings too.
 */
class AppBackupRepository @Inject constructor(
    private val feedRepository: FeedRepository,
    private val queueRepository: QueueRepository,
    private val settingsDataStore: SettingsDataStore,
) {
    suspend fun export(): String {
        val backup = AppBackup(
            feeds = feedRepository.getAllFeeds(),
            feedItems = feedRepository.getAllItems(),
            queueEntries = queueRepository.getAllEntries(),
            settings = settingsDataStore.settings.first(),
        )
        return backup.toJson()
    }

    /** Replaces every feed/item/queue entry and every setting wholesale (issue #157) -- feeds
     *  first (with their original ids preserved), so the backup's own items/queue entries
     *  (inserted after, referencing those same ids) resolve correctly. */
    suspend fun import(json: String) {
        val backup = AppBackup.fromJson(json)
        feedRepository.replaceAllFeeds(backup.feeds)
        feedRepository.insertItems(backup.feedItems)
        queueRepository.replaceAllEntries(backup.queueEntries)
        settingsDataStore.restore(backup.settings)
    }
}
