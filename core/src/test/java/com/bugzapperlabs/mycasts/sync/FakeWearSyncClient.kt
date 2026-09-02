package com.bugzapperlabs.mycasts.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [WearSyncClient] for tests (issue #276) -- no Play Services involved. [putQueueSnapshot]
 * and [putPosition] record what was sent (via [sentQueueSnapshots]/[sentPositions]) and separately
 * feed [observeQueueSnapshots]/[observePositionUpdates], so a test can drive both "what did we send
 * out" and "what did we receive" without a real two-device round trip.
 */
class FakeWearSyncClient : WearSyncClient {
    val sentQueueSnapshots = mutableListOf<List<SyncQueueItem>>()
    val sentPositions = mutableListOf<PositionUpdate>()

    private val incomingQueueSnapshots = MutableStateFlow<List<SyncQueueItem>>(emptyList())
    private val incomingPositionUpdates = MutableSharedFlow<PositionUpdate>(extraBufferCapacity = 16)

    override suspend fun putQueueSnapshot(queue: List<SyncQueueItem>) {
        sentQueueSnapshots += queue
    }

    override suspend fun putPosition(itemId: String, positionMs: Long, updatedAt: Long) {
        sentPositions += PositionUpdate(itemId, positionMs, updatedAt)
    }

    override fun observeQueueSnapshots() = incomingQueueSnapshots.asStateFlow()

    override fun observePositionUpdates() = incomingPositionUpdates.asSharedFlow()

    /** Simulates the paired device pushing a new queue snapshot. */
    suspend fun deliverQueueSnapshot(queue: List<SyncQueueItem>) {
        incomingQueueSnapshots.emit(queue)
    }

    /** Simulates the paired device pushing a position update. */
    suspend fun deliverPositionUpdate(update: PositionUpdate) {
        incomingPositionUpdates.emit(update)
    }
}
