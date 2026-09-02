package com.bugzapperlabs.mycasts.wear.sync

import com.bugzapperlabs.mycasts.sync.PositionUpdate
import com.bugzapperlabs.mycasts.sync.SyncQueueItem
import com.bugzapperlabs.mycasts.sync.WearSyncClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

private const val QUEUE_PATH = "/queue"
private const val POSITION_PATH_PREFIX = "/position/"
private const val KEY_ITEMS = "items"
private const val KEY_ITEM_ID = "itemId"
private const val KEY_FEED_ID = "feedId"
private const val KEY_TITLE = "title"
private const val KEY_FEED_TITLE = "feedTitle"
private const val KEY_ENCLOSURE_URL = "enclosureUrl"
private const val KEY_ARTWORK_URL = "artworkUrl"
private const val KEY_DURATION_MS = "durationMs"
private const val KEY_POSITION_MS = "positionMs"
private const val KEY_ORDER_INDEX = "orderIndex"
private const val KEY_UPDATED_AT = "updatedAt"

/**
 * [WearSyncClient] over the real Wear OS Data Layer API (issue #276). `putDataItem`'s "last known
 * value" persistence is what lets a snapshot survive the paired device being briefly unreachable
 * -- see [WearSyncClient]'s own doc for why `DataClient` rather than `MessageClient` was chosen.
 */
class PlayServicesWearSyncClient @Inject constructor(
    private val dataClient: DataClient,
) : WearSyncClient {

    override suspend fun putQueueSnapshot(queue: List<SyncQueueItem>) {
        val request = PutDataMapRequest.create(QUEUE_PATH).apply {
            dataMap.putDataMapArrayList(KEY_ITEMS, ArrayList(queue.map { it.toDataMap() }))
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }

    override suspend fun putPosition(itemId: String, positionMs: Long, updatedAt: Long) {
        val request = PutDataMapRequest.create(POSITION_PATH_PREFIX + itemId).apply {
            dataMap.putString(KEY_ITEM_ID, itemId)
            dataMap.putLong(KEY_POSITION_MS, positionMs)
            dataMap.putLong(KEY_UPDATED_AT, updatedAt)
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

private fun SyncQueueItem.toDataMap(): DataMap = DataMap().apply {
    putString(KEY_ITEM_ID, itemId)
    putLong(KEY_FEED_ID, feedId)
    title?.let { putString(KEY_TITLE, it) }
    feedTitle?.let { putString(KEY_FEED_TITLE, it) }
    enclosureUrl?.let { putString(KEY_ENCLOSURE_URL, it) }
    artworkUrl?.let { putString(KEY_ARTWORK_URL, it) }
    durationMs?.let { putLong(KEY_DURATION_MS, it) }
    positionMs?.let { putLong(KEY_POSITION_MS, it) }
    putInt(KEY_ORDER_INDEX, orderIndex)
}

private fun DataMap.toSyncQueueItem(): SyncQueueItem = SyncQueueItem(
    itemId = getString(KEY_ITEM_ID).orEmpty(),
    feedId = getLong(KEY_FEED_ID),
    title = getString(KEY_TITLE),
    feedTitle = getString(KEY_FEED_TITLE),
    enclosureUrl = getString(KEY_ENCLOSURE_URL),
    artworkUrl = getString(KEY_ARTWORK_URL),
    durationMs = if (containsKey(KEY_DURATION_MS)) getLong(KEY_DURATION_MS) else null,
    positionMs = if (containsKey(KEY_POSITION_MS)) getLong(KEY_POSITION_MS) else null,
    orderIndex = getInt(KEY_ORDER_INDEX),
)

private fun DataMap.toSyncQueueItems(): List<SyncQueueItem> =
    getDataMapArrayList(KEY_ITEMS)?.map { it.toSyncQueueItem() }.orEmpty()

private fun DataMap.toPositionUpdate(): PositionUpdate = PositionUpdate(
    itemId = getString(KEY_ITEM_ID).orEmpty(),
    positionMs = getLong(KEY_POSITION_MS),
    updatedAt = getLong(KEY_UPDATED_AT),
)
