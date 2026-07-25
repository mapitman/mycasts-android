package com.bugzapperlabs.myfeeds.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The "Next Up" playback queue (issue #67): an ordered, cross-feed list of episodes independent
 * of any single feed's article list. `itemId` is the primary key -- an episode can only be queued
 * once, re-queuing is a no-op (see [com.bugzapperlabs.myfeeds.data.repository.QueueRepository]).
 */
@Entity(
    tableName = "queue_entries",
    foreignKeys = [
        ForeignKey(
            entity = FeedItem::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("position")],
)
data class QueueEntry(
    @PrimaryKey val itemId: String,
    val position: Int,
    val addedAt: Long,
    // Only auto-queued entries (issue #68) are subject to a feed's autoQueueMaxCount eviction --
    // a manually-queued episode shouldn't vanish just because its feed happened to auto-queue new
    // ones afterward (issue #125/#127).
    val autoQueued: Boolean = false,
)
