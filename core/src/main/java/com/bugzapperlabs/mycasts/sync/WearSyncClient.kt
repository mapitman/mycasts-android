package com.bugzapperlabs.mycasts.sync

import kotlinx.coroutines.flow.Flow

/**
 * Seam over the Wear OS Data Layer API (issue #276), so sync-triggering logic (debounced queue
 * pushes, position-tick timestamp comparison) can be unit tested against [FakeWearSyncClient]
 * instead of the real `DataClient`, which needs Play Services and isn't practical to run under
 * Robolectric. The real implementation (`PlayServicesWearSyncClient`) lives in `:app`/`:wear`,
 * each wrapping its own `Wearable.getDataClient(context)`.
 *
 * [putQueueSnapshot] and [putPosition] both write through the Data Layer's persisted "last known
 * value" semantics: a write survives the counterpart device being briefly unreachable, and is
 * delivered once it reconnects, rather than being lost like a one-shot message would be.
 */
interface WearSyncClient {
    /** Replaces the full Next Up snapshot visible to the paired device. */
    suspend fun putQueueSnapshot(queue: List<SyncQueueItem>)

    /** Reports [itemId]'s playback position as of [updatedAt] (epoch millis) to the paired
     *  device. Last-write-wins: a receiver drops any [PositionUpdate] older than the newest one
     *  it's already applied for that item. */
    suspend fun putPosition(itemId: String, positionMs: Long, updatedAt: Long)

    /** Queue snapshots received from the paired device. */
    fun observeQueueSnapshots(): Flow<List<SyncQueueItem>>

    /** Position updates received from the paired device. */
    fun observePositionUpdates(): Flow<PositionUpdate>
}
