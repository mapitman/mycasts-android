package com.bugzapperlabs.mycasts.sync

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
 * Manifest-registered (issue #276) so the system can wake the phone app to apply a position
 * report from the watch even when it isn't running. The phone never applies an incoming queue
 * snapshot -- it's the source of that sync, not a consumer of it -- so this only handles
 * [POSITION_PATH_PREFIX] events, unlike `:wear`'s `WearSyncListenerService`.
 */
@AndroidEntryPoint
class PhoneSyncListenerService : WearableListenerService() {

    @Inject
    lateinit var positionSyncApplier: PositionSyncApplier

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val positionUpdates = mutableListOf<PositionUpdate>()
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            if (path.startsWith(POSITION_PATH_PREFIX)) {
                positionUpdates += DataMapItem.fromDataItem(event.dataItem).dataMap.toPositionUpdate()
            }
        }
        dataEvents.release()

        serviceScope.launch {
            positionUpdates.forEach { positionSyncApplier.apply(it) }
        }
    }
}
