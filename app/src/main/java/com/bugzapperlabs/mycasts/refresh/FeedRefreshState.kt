package com.bugzapperlabs.mycasts.refresh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide "a feed refresh is currently running" signal (issue #152), shared between the manual
 * pull-to-refresh path ([com.bugzapperlabs.mycasts.feedlist.FeedListViewModel]) and the scheduled
 * background worker ([FeedRefreshWorker]) -- unread counts are displayed frozen while this is
 * true, so a scheduled background refresh needs to participate too, not just a manual one, or
 * counts still visibly flicker whenever the background worker happens to fire.
 *
 * A reference count rather than a plain boolean, so an overlapping manual refresh and background
 * refresh don't let one's completion prematurely flip this back to "not refreshing" while the
 * other is still writing to the DB.
 */
@Singleton
class FeedRefreshState @Inject constructor() {
    private val activeCount = MutableStateFlow(0)
    val isRefreshing = activeCount.map { it > 0 }

    /**
     * A synchronous, always-current read (issue #76 follow-up), for callers that can't trust a
     * `combine()`'s cached [isRefreshing] branch to already reflect the latest value -- combine()
     * collects each of its upstream flows via its own independently-scheduled child coroutine, so
     * under load one branch (e.g. the unread-count flow) can be processed before another (this
     * one) has caught up, letting a DB write that lands mid-refresh briefly appear "not refreshing"
     * to a collector deciding whether to freeze on it. [activeCount] itself is a plain
     * [MutableStateFlow], so `.value` is always immediately current with no such lag.
     */
    fun isCurrentlyRefreshing(): Boolean = activeCount.value > 0

    suspend fun <T> track(block: suspend () -> T): T {
        activeCount.update { it + 1 }
        try {
            return block()
        } finally {
            activeCount.update { it - 1 }
        }
    }
}
