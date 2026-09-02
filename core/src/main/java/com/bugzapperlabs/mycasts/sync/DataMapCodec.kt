package com.bugzapperlabs.mycasts.sync

import com.google.android.gms.wearable.DataMap

/**
 * `DataMap` <-> [SyncQueueItem]/[PositionUpdate] wire encoding (issue #276), shared by
 * [PlayServicesWearSyncClient] (in-process `DataClient.addListener`, for live UI observation) and
 * each app's manifest-registered `WearableListenerService` (woken by the system even when the
 * app process isn't running) -- both need to parse the same `DataEvent` payloads, so the encoding
 * lives in one public place rather than being duplicated per listener.
 */
const val QUEUE_PATH = "/queue"
const val POSITION_PATH_PREFIX = "/position/"
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

fun SyncQueueItem.toDataMap(): DataMap = DataMap().apply {
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

fun DataMap.toSyncQueueItem(): SyncQueueItem = SyncQueueItem(
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

fun DataMap.toSyncQueueItems(): List<SyncQueueItem> =
    getDataMapArrayList(KEY_ITEMS)?.map { it.toSyncQueueItem() }.orEmpty()

fun DataMap.toPositionUpdate(): PositionUpdate = PositionUpdate(
    itemId = getString(KEY_ITEM_ID).orEmpty(),
    positionMs = getLong(KEY_POSITION_MS),
    updatedAt = getLong(KEY_UPDATED_AT),
)
