package com.bugzapperlabs.mycasts.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeWearSyncClientTest {
    @Test
    fun putQueueSnapshot_recordsWhatWasSent() = runTest {
        val client = FakeWearSyncClient()
        val queue = listOf(
            SyncQueueItem(
                itemId = "ep-1", feedId = 1L, title = "Ep 1", feedTitle = "Feed",
                enclosureUrl = "https://example.com/1.mp3", artworkUrl = null,
                durationMs = null, positionMs = null, orderIndex = 0,
            ),
        )

        client.putQueueSnapshot(queue)

        assertEquals(listOf(queue), client.sentQueueSnapshots)
    }

    @Test
    fun putPosition_recordsWhatWasSent() = runTest {
        val client = FakeWearSyncClient()

        client.putPosition("ep-1", positionMs = 5_000L, updatedAt = 1_000L)

        assertEquals(listOf(PositionUpdate("ep-1", 5_000L, 1_000L)), client.sentPositions)
    }

    @Test
    fun deliverQueueSnapshot_isObservable() = runTest {
        val client = FakeWearSyncClient()
        val queue = listOf(
            SyncQueueItem(
                itemId = "ep-1", feedId = 1L, title = "Ep 1", feedTitle = "Feed",
                enclosureUrl = null, artworkUrl = null, durationMs = null, positionMs = null, orderIndex = 0,
            ),
        )

        client.deliverQueueSnapshot(queue)

        assertEquals(queue, client.observeQueueSnapshots().first())
    }

    @Test
    fun deliverPositionUpdate_isObservable() = runTest {
        val client = FakeWearSyncClient()
        val update = PositionUpdate("ep-1", positionMs = 3_000L, updatedAt = 2_000L)
        val received = mutableListOf<PositionUpdate>()

        // A SharedFlow (unlike the queue snapshot StateFlow) only delivers to an already-active
        // collector, so subscribe before delivering rather than reading .first() after the fact.
        val collectJob = launch { client.observePositionUpdates().toList(received) }
        runCurrent()
        client.deliverPositionUpdate(update)
        runCurrent()
        collectJob.cancel()

        assertEquals(listOf(update), received)
    }
}
