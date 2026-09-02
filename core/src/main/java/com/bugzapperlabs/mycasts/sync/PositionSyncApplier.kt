package com.bugzapperlabs.mycasts.sync

import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies an incoming [PositionUpdate] to the local database, last-write-wins by [PositionUpdate.updatedAt]
 * (issue #276) -- identical logic on both phone and watch (each applying updates received *from*
 * the other side), so this lives in `:core` rather than being duplicated per app. [Singleton]-
 * scoped so [lastAppliedAt] tracks one process's whole session, not just one listener callback.
 */
@Singleton
class PositionSyncApplier @Inject constructor(
    private val feedRepository: FeedRepository,
) {
    private val lastAppliedAt = ConcurrentHashMap<String, Long>()

    /** Drops [update] if a newer (or equally-timed -- already applied) update for the same item
     *  has already landed, so a stale/reordered delivery can never regress a more recent position. */
    suspend fun apply(update: PositionUpdate) {
        val previous = lastAppliedAt[update.itemId]
        if (previous != null && previous >= update.updatedAt) return
        lastAppliedAt[update.itemId] = update.updatedAt
        feedRepository.setEnclosurePosition(update.itemId, update.positionMs / 1000.0)
    }
}
