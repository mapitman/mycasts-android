package com.bugzapperlabs.myfeeds.data.opml

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedItem
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpmlExporterTest {
    private lateinit var db: AppDatabase
    private lateinit var exporter: OpmlExporter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        exporter = OpmlExporter(db.feedDao(), db.feedItemDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun export_emptyDatabase_producesEmptyBody() = runTest {
        val opml = exporter.export()

        val parsed = OpmlParser.parse(ByteArrayInputStream(opml.toByteArray()))
        assertEquals(emptyList<OpmlFolder>(), parsed.folders)
    }

    @Test
    fun export_roundTripsNonPodcastFeedsThroughParser() = runTest {
        db.feedDao().insert(Feed(title = "Ars Technica", feedUrl = "https://arstechnica.com/feed"))
        db.feedDao().insert(Feed(title = "Original Title", userTitle = "My Feed", feedUrl = "https://example.com/feed"))

        val opml = exporter.export()
        val parsed = OpmlParser.parse(ByteArrayInputStream(opml.toByteArray()))

        assertEquals(1, parsed.folders.size)
        val folder = parsed.folders.single()
        assertEquals("Feeds", folder.name)
        assertEquals(
            setOf(
                OpmlFeed("Ars Technica", "https://arstechnica.com/feed"),
                OpmlFeed("My Feed", "https://example.com/feed"),
            ),
            folder.feeds.toSet(),
        )
    }

    @Test
    fun export_splitsPodcastAndOtherFeedsIntoSeparateFolders() = runTest {
        val podcastFeedId = db.feedDao().insert(Feed(title = "Windows Weekly", feedUrl = "https://feeds.twit.tv/ww.xml"))
        db.feedDao().insert(Feed(title = "BBC News", feedUrl = "https://feeds.bbci.co.uk/news/rss.xml"))
        db.feedItemDao().insert(
            FeedItem(
                id = "item-1",
                feedId = podcastFeedId,
                title = "Episode 1",
                enclosureUrl = "https://example.com/ep1.mp3",
                enclosureType = "audio/mpeg",
            ),
        )

        val opml = exporter.export()
        val parsed = OpmlParser.parse(ByteArrayInputStream(opml.toByteArray()))

        assertEquals(setOf("Podcasts", "Feeds"), parsed.folders.map { it.name }.toSet())
        assertEquals(listOf("Windows Weekly"), parsed.folders.single { it.name == "Podcasts" }.feeds.map { it.title })
        assertEquals(listOf("BBC News"), parsed.folders.single { it.name == "Feeds" }.feeds.map { it.title })
    }

    @Test
    fun export_escapesXmlSpecialCharacters() = runTest {
        db.feedDao().insert(Feed(title = "A \"quoted\" <feed>", feedUrl = "https://example.com/feed?a=1&b=2"))

        val opml = exporter.export()
        val parsed = OpmlParser.parse(ByteArrayInputStream(opml.toByteArray()))

        assertEquals("A \"quoted\" <feed>", parsed.folders.single().feeds.single().title)
        assertEquals("https://example.com/feed?a=1&b=2", parsed.folders.single().feeds.single().xmlUrl)
    }
}
