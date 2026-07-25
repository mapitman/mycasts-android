package com.bugzapperlabs.myfeeds.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Schema migration coverage (Room exports schema JSON to app/schemas via room.schemaLocation). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun version1Schema_opensSuccessfully() {
        helper.createDatabase(TEST_DB, 1).close()
    }

    @Test
    fun version7Schema_opensSuccessfully() {
        helper.createDatabase(TEST_DB, 7).close()
    }

    @Test
    fun version8Schema_opensSuccessfully() {
        helper.createDatabase(TEST_DB, 8).close()
    }

    @Test
    fun version9Schema_opensSuccessfully() {
        helper.createDatabase(TEST_DB, 9).close()
    }

    @Test
    fun version10Schema_opensSuccessfully() {
        helper.createDatabase(TEST_DB, 10).close()
    }

    @Test
    fun version11Schema_opensSuccessfully() {
        helper.createDatabase(TEST_DB, 11).close()
    }

    @Test
    fun version12Schema_opensSuccessfully() {
        helper.createDatabase(TEST_DB, 12).close()
    }

    @Test
    fun version13Schema_opensSuccessfully() {
        helper.createDatabase(TEST_DB, 13).close()
    }

    @Test
    fun migrate1To2_addsDownloadColumnsWithoutDataLoss() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL("INSERT INTO categories (id, name, sortOrder) VALUES (1, 'Tech', NULL)")
            execSQL(
                "INSERT INTO feeds (id, categoryId, title, userTitle, description, feedUrl, siteUrl, " +
                    "imageUrl, displayMode, itemsToKeep, lastGet, sortOrder) " +
                    "VALUES (1, 1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        migrated.query("SELECT autoDownloadEnabled FROM feeds WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate2To3_addsEnclosureDurationColumnWithoutDataLoss() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL("INSERT INTO categories (id, name, sortOrder) VALUES (1, 'Tech', NULL)")
            execSQL(
                "INSERT INTO feeds (id, categoryId, title, userTitle, description, feedUrl, siteUrl, " +
                    "imageUrl, displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled) " +
                    "VALUES (1, 1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0)",
            )
            execSQL(
                "INSERT INTO feed_items (id, feedId, title, isRead) VALUES ('item-1', 1, 'An Item', 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        migrated.query("SELECT enclosureDurationMs FROM feed_items WHERE id = 'item-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        migrated.close()
    }

    @Test
    fun migrate3To4_createsQueueEntriesTable() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL("INSERT INTO categories (id, name, sortOrder) VALUES (1, 'Tech', NULL)")
            execSQL(
                "INSERT INTO feeds (id, categoryId, title, userTitle, description, feedUrl, siteUrl, " +
                    "imageUrl, displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled) " +
                    "VALUES (1, 1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0)",
            )
            execSQL(
                "INSERT INTO feed_items (id, feedId, title, isRead) VALUES ('item-1', 1, 'An Item', 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        migrated.execSQL("INSERT INTO queue_entries (itemId, position, addedAt) VALUES ('item-1', 0, 1000)")
        migrated.query("SELECT position FROM queue_entries WHERE itemId = 'item-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate4To5_addsAutoQueueColumnsWithoutDataLoss() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL("INSERT INTO categories (id, name, sortOrder) VALUES (1, 'Tech', NULL)")
            execSQL(
                "INSERT INTO feeds (id, categoryId, title, userTitle, description, feedUrl, siteUrl, " +
                    "imageUrl, displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled) " +
                    "VALUES (1, 1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        migrated.query("SELECT autoQueueEnabled, autoQueueMaxCount FROM feeds WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.close()
    }

    @Test
    fun migrate5To6_addsPlaybackSpeedColumnWithoutDataLoss() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL("INSERT INTO categories (id, name, sortOrder) VALUES (1, 'Tech', NULL)")
            execSQL(
                "INSERT INTO feeds (id, categoryId, title, userTitle, description, feedUrl, siteUrl, " +
                    "imageUrl, displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled, " +
                    "autoQueueEnabled, autoQueueMaxCount) " +
                    "VALUES (1, 1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        migrated.query("SELECT playbackSpeed FROM feeds WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1.0, cursor.getDouble(0), 0.0001)
        }
        migrated.close()
    }

    @Test
    fun migrate6To7_dropsCategoriesTableAndColumnWithoutDataLoss() {
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL("INSERT INTO categories (id, name, sortOrder) VALUES (1, 'Tech', NULL)")
            execSQL(
                "INSERT INTO feeds (id, categoryId, title, userTitle, description, feedUrl, siteUrl, " +
                    "imageUrl, displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled, " +
                    "autoQueueEnabled, autoQueueMaxCount, playbackSpeed) " +
                    "VALUES (1, 1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, 1.0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        migrated.query("SELECT title FROM feeds WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("A Feed", cursor.getString(0))
        }
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'categories'").use { cursor ->
            assertTrue(!cursor.moveToFirst())
        }
        migrated.close()
    }

    @Test
    fun migrate7To8_addsAutoQueuedColumnDefaultingToManual() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                "INSERT INTO feeds (id, title, userTitle, description, feedUrl, siteUrl, imageUrl, " +
                    "displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled, autoQueueEnabled, " +
                    "autoQueueMaxCount, playbackSpeed) " +
                    "VALUES (1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, 1.0)",
            )
            execSQL(
                "INSERT INTO feed_items (id, feedId, title, isRead) VALUES ('item-1', 1, 'An Item', 0)",
            )
            execSQL("INSERT INTO queue_entries (itemId, position, addedAt) VALUES ('item-1', 0, 1000)")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        migrated.query("SELECT autoQueued FROM queue_entries WHERE itemId = 'item-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate8To9_addsChaptersUrlColumnWithoutDataLoss() {
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL(
                "INSERT INTO feeds (id, title, userTitle, description, feedUrl, siteUrl, imageUrl, " +
                    "displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled, autoQueueEnabled, " +
                    "autoQueueMaxCount, playbackSpeed) " +
                    "VALUES (1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, 1.0)",
            )
            execSQL(
                "INSERT INTO feed_items (id, feedId, title, isRead) VALUES ('item-1', 1, 'An Item', 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)

        migrated.query("SELECT chaptersUrl FROM feed_items WHERE id = 'item-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        migrated.close()
    }

    @Test
    fun migrate9To10_addsAutoQueuePositionColumnDefaultingToBottom() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL(
                "INSERT INTO feeds (id, title, userTitle, description, feedUrl, siteUrl, imageUrl, " +
                    "displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled, autoQueueEnabled, " +
                    "autoQueueMaxCount, playbackSpeed) " +
                    "VALUES (1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, 1.0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)

        migrated.query("SELECT autoQueuePosition FROM feeds WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("BOTTOM", cursor.getString(0))
        }
        migrated.close()
    }

    @Test
    fun migrate10To11_addsVolumeBoostColumnDefaultingToZero() {
        helper.createDatabase(TEST_DB, 10).apply {
            execSQL(
                "INSERT INTO feeds (id, title, userTitle, description, feedUrl, siteUrl, imageUrl, " +
                    "displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled, autoQueueEnabled, " +
                    "autoQueueMaxCount, playbackSpeed, autoQueuePosition) " +
                    "VALUES (1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, 1.0, 'BOTTOM')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11)

        migrated.query("SELECT volumeBoostMillibels FROM feeds WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate11To12_addsStartSkipSecondsColumnDefaultingToZero() {
        helper.createDatabase(TEST_DB, 11).apply {
            execSQL(
                "INSERT INTO feeds (id, title, userTitle, description, feedUrl, siteUrl, imageUrl, " +
                    "displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled, autoQueueEnabled, " +
                    "autoQueueMaxCount, playbackSpeed, autoQueuePosition, volumeBoostMillibels) " +
                    "VALUES (1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, 1.0, 'BOTTOM', 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 12, true, MIGRATION_11_12)

        migrated.query("SELECT startSkipSeconds FROM feeds WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun migrate12To13_addsMaxDownloadsAndAutoDownloadedColumnsWithoutDataLoss() {
        helper.createDatabase(TEST_DB, 12).apply {
            execSQL(
                "INSERT INTO feeds (id, title, userTitle, description, feedUrl, siteUrl, imageUrl, " +
                    "displayMode, itemsToKeep, lastGet, sortOrder, autoDownloadEnabled, autoQueueEnabled, " +
                    "autoQueueMaxCount, playbackSpeed, autoQueuePosition, volumeBoostMillibels, startSkipSeconds) " +
                    "VALUES (1, 'A Feed', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, 1.0, 'BOTTOM', 0, 0)",
            )
            execSQL(
                "INSERT INTO feed_items (id, feedId, title, isRead) VALUES ('item-1', 1, 'An Item', 0)",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 13, true, MIGRATION_12_13)

        migrated.query("SELECT maxDownloadsToKeep FROM feeds WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        migrated.query("SELECT autoDownloaded FROM feed_items WHERE id = 'item-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
