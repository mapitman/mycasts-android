package com.bugzapperlabs.mycasts.sync

import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * [WearSyncClient] over the real Wear OS Data Layer API (issue #276). `putDataItem`'s "last known
 * value" persistence is what lets a snapshot survive the paired device being briefly unreachable
 * -- see [WearSyncClient]'s own doc for why `DataClient` rather than `MessageClient` was chosen.
 *
 * This is the in-process, live-observation half of sync (for a ViewModel to collect while the app
 * is foregrounded); [QUEUE_PATH]/[POSITION_PATH_PREFIX] events are *also* delivered to each app's
 * manifest-registered `WearableListenerService`, which is what actually keeps the database current
 * even when nothing is collecting these flows -- see that service's own doc.
 */
class PlayServicesWearSyncClient @Inject constructor(
    private val dataClient: DataClient,
) : WearSyncClient {

    override suspend fun putQueueSnapshot(queue: List<SyncQueueItem>) {
        val request = PutDataMapRequest.create(QUEUE_PATH).apply {
            dataMap.putDataMapArrayList("items", ArrayList(queue.map { it.toDataMap() }))
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }

    override suspend fun putPosition(itemId: String, positionMs: Long, updatedAt: Long) {
        val request = PutDataMapRequest.create(POSITION_PATH_PREFIX + itemId).apply {
            dataMap.putString("itemId", itemId)
            dataMap.putLong("positionMs", positionMs)
            dataMap.putLong("updatedAt", updatedAt)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }

    override fun observeQueueSnapshots(): Flow<List<SyncQueueItem>> = callbackFlow {
        val existing = dataClient.dataItems.await()
        existing.firstOrNull { it.uri.path == QUEUE_PATH }
            ?.let { trySend(DataMapItem.fromDataItem(it).dataMap.toSyncQueueItems()) }
        existing.release()

        val listener = DataClient.OnDataChangedListener { events ->
            for (event in events) {
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == QUEUE_PATH) {
                    trySend(DataMapItem.fromDataItem(event.dataItem).dataMap.toSyncQueueItems())
                }
            }
        }
        dataClient.addListener(listener)
        awaitClose { dataClient.removeListener(listener) }
    }

    override fun observePositionUpdates(): Flow<PositionUpdate> = callbackFlow {
        val listener = DataClient.OnDataChangedListener { events ->
            for (event in events) {
                val path = event.dataItem.uri.path
                if (event.type == DataEvent.TYPE_CHANGED && path != null && path.startsWith(POSITION_PATH_PREFIX)) {
                    trySend(DataMapItem.fromDataItem(event.dataItem).dataMap.toPositionUpdate())
                }
            }
        }
        dataClient.addListener(listener)
        awaitClose { dataClient.removeListener(listener) }
    }
}
