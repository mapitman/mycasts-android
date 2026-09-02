package com.bugzapperlabs.mycasts.wear.sync

import com.bugzapperlabs.mycasts.sync.POSITION_PATH_PREFIX
import com.bugzapperlabs.mycasts.sync.PositionSyncApplier
import com.bugzapperlabs.mycasts.sync.PositionUpdate
import com.bugzapperlabs.mycasts.sync.QUEUE_PATH
import com.bugzapperlabs.mycasts.sync.SyncQueueItem
import com.bugzapperlabs.mycasts.sync.toPositionUpdate
import com.bugzapperlabs.mycasts.sync.toSyncQueueItems
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manifest-registered (issue #276), so the system can wake this app to apply a queue/position
 * sync even when it isn't already running -- unlike [com.bugzapperlabs.mycasts.sync.PlayServicesWearSyncClient]'s
 * in-process listeners, which only fire while something is actively collecting those flows.
 */
@AndroidEntryPoint
class WearSyncListenerService : WearableListenerService() {

    @Inject
    lateinit var queueSyncApplier: WearQueueSyncApplier

    @Inject
    lateinit var positionSyncApplier: PositionSyncApplier

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // A DataEvent (and the buffer itself) becomes invalid once the buffer is released, so
        // every payload needed is decoded into a plain, buffer-independent model object first.
        val queueSnapshots = mutableListOf<List<SyncQueueItem>>()
        val positionUpdates = mutableListOf<PositionUpdate>()
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
            when {
                path == QUEUE_PATH -> queueSnapshots += dataMap.toSyncQueueItems()
                path.startsWith(POSITION_PATH_PREFIX) -> positionUpdates += dataMap.toPositionUpdate()
            }
        }
        dataEvents.release()

        serviceScope.launch {
            queueSnapshots.forEach { queueSyncApplier.apply(it) }
            positionUpdates.forEach { positionSyncApplier.apply(it) }
        }
    }
}
