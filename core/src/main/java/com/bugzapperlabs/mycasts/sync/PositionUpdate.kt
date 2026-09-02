package com.bugzapperlabs.mycasts.sync

/**
 * A playback-position report exchanged between phone and watch (issue #276), in either direction.
 * [updatedAt] (wall-clock epoch millis) is the whole conflict-resolution mechanism -- whichever
 * side last wrote wins; see [WearSyncClient.putPosition].
 */
data class PositionUpdate(
    val itemId: String,
    val positionMs: Long,
    val updatedAt: Long,
)
