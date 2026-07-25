package com.bugzapperlabs.myfeeds.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds background-download support (issue #23): per-feed auto-download flag and per-item progress/path. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE feeds ADD COLUMN autoDownloadEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE feed_items ADD COLUMN downloadedBytes INTEGER")
        db.execSQL("ALTER TABLE feed_items ADD COLUMN downloadedFilePath TEXT")
    }
}

/** Stores parsed `itunes:duration` per episode (issue #75), so the reader can show a saved resume
 *  position proportionally without needing to buffer the episode first. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE feed_items ADD COLUMN enclosureDurationMs INTEGER")
    }
}

/** Adds the "Next Up" playback queue (issue #67): an ordered, cross-feed list of episode ids. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS queue_entries (" +
                "itemId TEXT NOT NULL PRIMARY KEY, " +
                "position INTEGER NOT NULL, " +
                "addedAt INTEGER NOT NULL, " +
                "FOREIGN KEY(itemId) REFERENCES feed_items(id) ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_queue_entries_position ON queue_entries(position)")
    }
}

/** Adds per-feed auto-queue settings (issue #68): opt-in auto-add of new episodes to the Next Up
 *  queue, with an optional cap on how many of that feed's episodes stay queued at once. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE feeds ADD COLUMN autoQueueEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE feeds ADD COLUMN autoQueueMaxCount INTEGER")
    }
}

/** Adds per-feed playback speed (issue #70), applied when starting an episode of that feed. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE feeds ADD COLUMN playbackSpeed REAL NOT NULL DEFAULT 1.0")
    }
}

/** Removes categories (issue #118): the feed list collapses to two fixed lists (Podcasts, Feeds)
 *  derived from item enclosure type, so the categories table and feeds.categoryId go away. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE feeds_new (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "title TEXT, " +
                "userTitle TEXT, " +
                "description TEXT, " +
                "feedUrl TEXT, " +
                "siteUrl TEXT, " +
                "imageUrl TEXT, " +
                "displayMode INTEGER, " +
                "itemsToKeep INTEGER, " +
                "lastGet INTEGER, " +
                "sortOrder INTEGER, " +
                "autoDownloadEnabled INTEGER NOT NULL DEFAULT 0, " +
                "autoQueueEnabled INTEGER NOT NULL DEFAULT 0, " +
                "autoQueueMaxCount INTEGER, " +
                "playbackSpeed REAL NOT NULL DEFAULT 1.0)",
        )
        db.execSQL(
            "INSERT INTO feeds_new (id, title, userTitle, description, feedUrl, siteUrl, imageUrl, " +
                "displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled, autoQueueEnabled, " +
                "autoQueueMaxCount, playbackSpeed) " +
                "SELECT id, title, userTitle, description, feedUrl, siteUrl, imageUrl, displayMode, " +
                "itemsToKeep, lastGet, sortOrder, autoDownloadEnabled, autoQueueEnabled, " +
                "autoQueueMaxCount, playbackSpeed FROM feeds",
        )
        db.execSQL("DROP TABLE feeds")
        db.execSQL("ALTER TABLE feeds_new RENAME TO feeds")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_feeds_userTitle ON feeds(userTitle)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_feeds_title ON feeds(title)")
        db.execSQL("DROP TABLE categories")
    }
}

/**
 * Distinguishes auto-queued from manually-queued entries (issue #125/#127): a feed's
 * `autoQueueMaxCount` eviction should only ever remove episodes it auto-queued itself, not ones
 * the user deliberately added to Next Up. Existing rows default to `0` (manual) -- the safe
 * direction, since it's eviction that's opt-in via this flag, not protection.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE queue_entries ADD COLUMN autoQueued INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds podcast chapters support (issue #95): the URL of a per-episode Podcasting 2.0 external
 *  JSON chapters file, if the feed provides one. Chapters themselves are fetched lazily at
 *  playback time, not persisted. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE feed_items ADD COLUMN chaptersUrl TEXT")
    }
}

/** Adds per-feed auto-queue position (issue #166): whether new episodes auto-added to Next Up
 *  land at the top or bottom of the queue. Existing rows default to 'BOTTOM' to preserve the
 *  pre-existing append-to-end behavior. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE feeds ADD COLUMN autoQueuePosition TEXT NOT NULL DEFAULT 'BOTTOM'")
    }
}

/** Adds per-feed volume boost (issue #199), applied via a LoudnessEnhancer target gain in
 *  millibels when playing an episode of that feed. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE feeds ADD COLUMN volumeBoostMillibels INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds per-feed start-skip (issue #200): seconds to skip from the start when an episode of that
 *  feed begins playing fresh (no saved resume position). */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE feeds ADD COLUMN startSkipSeconds INTEGER NOT NULL DEFAULT 0")
    }
}

/** Adds per-feed max-downloads-to-keep (issue #250): caps how many auto-downloaded episodes of a
 *  feed are kept before the oldest are auto-deleted, plus the flag distinguishing an
 *  auto-triggered download from a manual one so the cap never evicts a manual download. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE feeds ADD COLUMN maxDownloadsToKeep INTEGER")
        db.execSQL("ALTER TABLE feed_items ADD COLUMN autoDownloaded INTEGER NOT NULL DEFAULT 0")
    }
}
