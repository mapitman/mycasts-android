package com.bugzapperlabs.mycasts.data.backup

import com.bugzapperlabs.mycasts.data.local.BackupDao
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.download.EnclosureDownloadWorker
import kotlinx.coroutines.flow.first
import java.io.File
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
    private val backupDao: BackupDao,
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

    /** Replaces every feed/item/queue entry and every setting wholesale (issue #157). The backup
     *  is fully parsed and validated before any data changes, and the database part runs as one
     *  transaction (issue #298), so a bad file or a mid-restore failure leaves the previous data
     *  intact. Throws [org.json.JSONException] for an unreadable backup. */
    suspend fun import(json: String) {
        val backup = AppBackup.fromJson(json)
        backupDao.replaceAll(
            feeds = backup.feeds,
            items = backup.feedItems.map { it.withValidatedDownload() },
            queueEntries = backup.queueEntries,
        )
        // issue #299: backups don't contain the PodcastIndex credentials, so keep this device's own
        // rather than letting the restore's settings wipe them.
        val current = settingsDataStore.settings.first()
        settingsDataStore.restore(
            backup.settings.copy(
                podcastIndexApiKey = current.podcastIndexApiKey,
                podcastIndexApiSecret = current.podcastIndexApiSecret,
            ),
        )
    }

    /** A backup doesn't contain the downloaded files themselves, and its recorded path comes from
     *  an untrusted file (issue #298): keep the download record only when it points at an existing
     *  file inside this app's own downloads directory, otherwise clear it so the episode shows as
     *  not downloaded instead of pointing at a missing (or arbitrary) path. */
    private fun FeedItem.withValidatedDownload(): FeedItem {
        val path = downloadedFilePath ?: return if (downloadedBytes == null) this else copy(downloadedBytes = null)
        val file = try {
            File(path).canonicalFile
        } catch (_: java.io.IOException) {
            null
        }
        val valid = file != null && file.isFile && file.parentFile?.name == EnclosureDownloadWorker.DOWNLOAD_DIR
        return if (valid) this else copy(downloadedFilePath = null, downloadedBytes = null)
    }
}
