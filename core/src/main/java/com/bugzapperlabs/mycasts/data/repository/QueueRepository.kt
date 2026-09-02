package com.bugzapperlabs.mycasts.data.repository

import com.bugzapperlabs.mycasts.data.local.QueueDao
import com.bugzapperlabs.mycasts.data.local.QueueEntry
import com.bugzapperlabs.mycasts.data.local.QueuedEpisode
import com.bugzapperlabs.mycasts.data.local.isPodcastEpisode
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The "Next Up" playback queue (issue #67), over [QueueDao]. */
class QueueRepository @Inject constructor(
    private val queueDao: QueueDao,
    private val feedRepository: FeedRepository,
    private val downloadRepository: QueueDownloadTrigger,
    private val settingsDataStore: SettingsDataStore,
) {
    fun observeQueue(): Flow<List<QueuedEpisode>> = queueDao.observeQueue()

    /** Queued item IDs only (issue #52), for screens that just need to know membership, e.g. to
     *  show a "this episode is already queued" indicator without observing the full queue. */
    fun observeQueuedItemIds(): Flow<Set<String>> = queueDao.observeQueuedItemIds().map { it.toSet() }

    suspend fun isQueued(itemId: String): Boolean = queueDao.findItemId(itemId) != null

    /**
     * No-op if already queued -- an episode can only be queued once. Returns whether it was added.
     * [autoQueued] should only be true for the feed-refresh auto-queue path (issue #68); manual
     * adds must stay exempt from that feed's [enforceFeedCap] eviction (issue #125/#127).
     *
     * Also starts the episode downloading (issue #219) -- adding to Next Up, whether by the user
     * or by auto-queue, is now the sole trigger for auto-downloading; there's no separate per-feed
     * or global auto-download toggle any more.
     */
    suspend fun addToEnd(itemId: String, autoQueued: Boolean = false): Boolean {
        if (queueDao.findItemId(itemId) != null) return false
        queueDao.insert(
            QueueEntry(itemId, position = queueDao.maxPosition() + 1, addedAt = System.currentTimeMillis(), autoQueued = autoQueued),
        )
        triggerDownload(itemId)
        return true
    }

    /**
     * No-op if already queued -- an episode can only be queued once. [autoQueued] mirrors
     * [addToEnd]'s parameter of the same name (issue #166: auto-queue can now insert at either end
     * of the queue depending on the feed's `autoQueuePosition`), so this end also needs to be able
     * to mark entries as auto-queued for [enforceFeedCap] eviction to see them.
     *
     * "Front" means directly after the currently-playing episode, not the absolute front of the
     * queue (issue #271) -- [moveToFront] pins that episode at the true front position so it shows
     * up in Next Up itself, and inserting an even-lower position here would silently bump it out of
     * the "now playing" slot. [SettingsDataStore.lastPlayingItemId] is how the currently-playing
     * episode is identified: it's set/cleared by [com.bugzapperlabs.mycasts.playback.PlaybackController]
     * around the same "now playing" transitions [moveToFront] itself is called for, and survives
     * process death, so it reflects "now playing" here reliably even across a fresh process. Falls
     * back to the true front when nothing is currently playing, same as before.
     *
     * Also starts the episode downloading (issue #219), same as [addToEnd].
     */
    suspend fun addToFront(itemId: String, autoQueued: Boolean = false) {
        if (queueDao.findItemId(itemId) != null) return
        val currentItemId = settingsDataStore.settings.first().lastPlayingItemId
            ?.takeIf { it != itemId && queueDao.findItemId(it) != null }
        queueDao.insert(
            QueueEntry(itemId, position = queueDao.minPosition() - 1, addedAt = System.currentTimeMillis(), autoQueued = autoQueued),
        )
        if (currentItemId != null) {
            val ordered = queueDao.orderedItemIds().toMutableList()
            ordered.remove(itemId)
            ordered.add(ordered.indexOf(currentItemId) + 1, itemId)
            reorder(ordered)
        }
        triggerDownload(itemId)
    }

    /**
     * Starts auto-downloading [itemId] as a side effect of it being added to Next Up (issue #219),
     * gated by [com.bugzapperlabs.mycasts.data.settings.AppSettings.downloadOnAddToNextUp] (issue
     * #219 follow-up: added so this can be turned off, falling back to episodes only ever
     * downloading via an explicit single-episode download tap) -- not called from [moveToFront],
     * since that just marks an already-queued (or not) episode as "now playing" rather than the
     * user/auto-queue actually adding it to Next Up. See [QueueDownloadTrigger.ensureDownloaded]
     * for the actual already-downloaded/mid-download guard.
     */
    private suspend fun triggerDownload(itemId: String) {
        if (!settingsDataStore.settings.first().downloadOnAddToNextUp) return
        val item = feedRepository.getItem(itemId) ?: return
        downloadRepository.ensureDownloaded(item)
    }

    /**
     * Ensures [itemId] is the queue's front entry (issue #196): moves it there if already queued,
     * inserts it there (never as auto-queued) otherwise. This is how [com.bugzapperlabs.mycasts.playback.PlaybackController]
     * marks an episode "now playing" -- the currently-playing episode is a real queue entry like
     * any other, always at the front, rather than a special case hidden from the queue entirely,
     * so it shows up in Next Up itself (clearly marked) instead of only via the mini/expanded player.
     */
    suspend fun moveToFront(itemId: String) {
        val position = queueDao.minPosition() - 1
        if (queueDao.findItemId(itemId) != null) {
            queueDao.setPosition(itemId, position)
        } else {
            queueDao.insert(QueueEntry(itemId, position = position, addedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Moves [itemId] to the back of the queue if it's currently queued; a no-op otherwise (issue
     * #196). Used to get a just-failed episode out of the front slot so [PlaybackService] doesn't
     * immediately trip over it again advancing to whatever's next, without discarding it outright
     * the way a finished episode is -- it never actually played, so it's still worth surfacing in
     * Next Up for the user to retry.
     */
    suspend fun moveToEnd(itemId: String) {
        if (queueDao.findItemId(itemId) == null) return
        queueDao.setPosition(itemId, queueDao.maxPosition() + 1)
    }

    /** Returns the entry that was removed (for issue #284's undo), or null if it wasn't queued. */
    suspend fun remove(itemId: String): QueueEntry? {
        val entry = queueDao.getEntry(itemId) ?: return null
        queueDao.remove(itemId)
        return entry
    }

    /** Re-inserts an entry previously returned by [remove], undoing it -- a no-op (via the DAO's
     *  IGNORE conflict strategy) if the episode has since been queued again some other way. */
    suspend fun restore(entry: QueueEntry) = queueDao.insert(entry)

    /** Renumbers positions to match [orderedItemIds] exactly, for drag-to-reorder in the queue screen. */
    suspend fun reorder(orderedItemIds: List<String>) {
        orderedItemIds.forEachIndexed { index, itemId -> queueDao.setPosition(itemId, index) }
    }

    /** The item at the front of the queue, without removing it (issue #196) -- the currently-playing
     *  episode is meant to stay queued (as the front entry) for as long as it's playing, so
     *  advancing to it shouldn't also dequeue it the way the old pop-and-remove semantics did. */
    suspend fun peekFront(): String? = queueDao.firstItemId()

    /**
     * Evicts this feed's oldest *auto-queued* episodes (earliest added, not earliest published)
     * down to [maxCount] (issue #68) -- manually-queued entries are never evicted by this, so a
     * feed auto-queuing new episodes can't silently wipe out ones the user deliberately queued
     * (issue #125/#127). Only removes from the queue; does not touch the episode, its download, or
     * read state.
     */
    suspend fun enforceFeedCap(feedId: Long, maxCount: Int) {
        val ordered = queueDao.orderedAutoQueuedItemIdsForFeed(feedId)
        val excess = ordered.size - maxCount
        if (excess <= 0) return
        ordered.take(excess).forEach { queueDao.remove(it) }
    }

    /** A one-shot snapshot of the whole queue, position order (issue #157), for a full backup
     *  export. */
    suspend fun getAllEntries(): List<QueueEntry> = queueDao.getAll()

    /** Replaces the whole queue with [entries] wholesale (issue #157), for a full backup restore
     *  -- the entries themselves are inserted separately by [com.bugzapperlabs.mycasts.data.repository.FeedRepository.replaceAllFeeds]'s
     *  cascade-delete of the previous feeds/items/queue, so this only needs to insert the backup's
     *  own queue rows back afterward. */
    suspend fun replaceAllEntries(entries: List<QueueEntry>) = queueDao.insertAll(entries)

    /** Applying a synced Next Up snapshot on a Wear OS watch (issue #276): unlike
     *  [replaceAllEntries] (restoring into an already-empty DB), a repeat sync needs stale
     *  entries -- removed from the queue on the phone since the last sync -- actually cleared
     *  first, not just left behind alongside the new ones. */
    suspend fun replaceQueueFromSync(entries: List<QueueEntry>) {
        queueDao.clear()
        queueDao.insertAll(entries)
    }
}
