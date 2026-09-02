package com.bugzapperlabs.mycasts.sync

import android.util.Log
import com.bugzapperlabs.mycasts.data.local.QueuedEpisode
import com.bugzapperlabs.mycasts.data.repository.QueueRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "QueueSyncPublisher"

/** Coalesces bursts of Next Up edits into a single push (issue #276) -- reordering, or a feed's
 *  auto-queue adding several episodes at once, would otherwise send one queue snapshot per
 *  individual [QueueRepository] mutation. */
private const val QUEUE_PUSH_DEBOUNCE_MS = 500L

/**
 * Keeps a paired Wear OS watch's queue current by pushing [QueueRepository.observeQueue] to
 * [WearSyncClient] (issue #276).
 *
 * [start] is called from [com.bugzapperlabs.mycasts.MainActivity.onCreate], not
 * `Application.onCreate()` -- the latter also runs for every Robolectric-hosted unit test (see
 * `MainActivity.onCreate`'s own `feedRefreshScheduler.schedule` comment for the same reasoning),
 * where this would otherwise start a background coroutine hitting a real database file and real
 * Play Services on every unrelated test. [Singleton]-scoped so [started] survives across
 * `MainActivity` being recreated (e.g. on a configuration change) without starting a second,
 * duplicate collector, and so this class owns its own process-lifetime [CoroutineScope] rather
 * than depending on an Activity-scoped one that would be cancelled when the Activity is destroyed.
 */
@OptIn(FlowPreview::class)
@Singleton
class QueueSyncPublisher @Inject constructor(
    private val queueRepository: QueueRepository,
    private val wearSyncClient: WearSyncClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    fun start() {
        if (started.compareAndSet(false, true)) {
            scope.launch { collectAndPush(queueRepository.observeQueue()) }
        }
    }

    /** Split out from [start] so the debounce/coalescing behavior can be tested against a fully
     *  virtual-time-controlled [Flow] (issue #276) -- [QueueRepository.observeQueue]'s underlying
     *  Room query re-execution happens on a real (non-test) dispatcher, which makes asserting
     *  exact debounce timing against it in a `runTest` unreliable. */
    internal suspend fun collectAndPush(queue: Flow<List<QueuedEpisode>>) {
        queue.debounce(QUEUE_PUSH_DEBOUNCE_MS)
            .collect { snapshot ->
                // A failed push (no paired watch, Play Services unavailable, momentarily
                // unreachable) must not end the collection -- the next queue change should still
                // get a chance to sync, rather than this process never syncing again.
                runCatching { wearSyncClient.putQueueSnapshot(snapshot.toSyncQueueItems()) }
                    .onFailure { Log.w(TAG, "Failed to push queue snapshot to watch", it) }
            }
    }
}
