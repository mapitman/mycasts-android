package com.bugzapperlabs.mycasts.data.feed

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-feed mutual exclusion for [FeedUpdateEngine.persist] (issue #70). Without this, two
 * concurrent refreshes of the *same* feed -- most commonly a manual pull-to-refresh landing while
 * the scheduled [com.bugzapperlabs.mycasts.refresh.FeedRefreshWorker] is also refreshing, since it
 * runs on the interval from Settings -- each independently check `findByItemGuid` before either
 * has committed its inserts, so both insert their own fresh-UUID copy of the same episode, and
 * each one's own trim-to-`itemsToKeep` races against the other's writes too. A true [Singleton] so
 * the same lock table is shared across every caller (both ViewModels, the worker, and OPML
 * import's [FeedUpdateEngine.persistFetchedFeed]), regardless of how many separate
 * [FeedUpdateEngine] instances Hilt happens to create (it isn't itself scoped as a singleton).
 */
@Singleton
class FeedRefreshLocks @Inject constructor() {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withLock(feedId: Long, block: suspend () -> T): T =
        locks.getOrPut(feedId) { Mutex() }.withLock { block() }
}
