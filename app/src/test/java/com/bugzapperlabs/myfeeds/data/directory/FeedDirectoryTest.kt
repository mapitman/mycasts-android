package com.bugzapperlabs.myfeeds.data.directory

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedDirectoryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val feedDirectory: PodcastSearchProvider = FeedDirectory(context)

    @Test
    fun search_blankQuery_returnsNoResults() = runTest {
        assertTrue(feedDirectory.search("").isEmpty())
        assertTrue(feedDirectory.search("   ").isEmpty())
    }

    @Test
    fun search_knownTerm_returnsNonEmptyResults() = runTest {
        val results = feedDirectory.search("android")

        assertTrue(results.isNotEmpty())
        results.forEach { entry ->
            val matches = entry.title.contains("android", ignoreCase = true) ||
                entry.description?.contains("android", ignoreCase = true) == true ||
                entry.subtitle.contains("android", ignoreCase = true)
            assertTrue("expected a match for '${entry.title}'", matches)
        }
    }

    @Test
    fun search_isCaseInsensitive() = runTest {
        val lower = feedDirectory.search("tech")
        val upper = feedDirectory.search("TECH")
        val mixed = feedDirectory.search("TeCh")

        assertEquals(lower.map { it.feedUrl }, upper.map { it.feedUrl })
        assertEquals(lower.map { it.feedUrl }, mixed.map { it.feedUrl })
    }

    @Test
    fun search_titleMatchesRankAboveDescriptionOnlyMatches() = runTest {
        val results = feedDirectory.search("android")
        val firstDescriptionOnlyIndex = results.indexOfFirst { entry ->
            !entry.title.contains("android", ignoreCase = true)
        }
        val lastTitleMatchIndex = results.indexOfLast { entry ->
            entry.title.contains("android", ignoreCase = true)
        }

        if (firstDescriptionOnlyIndex != -1 && lastTitleMatchIndex != -1) {
            assertTrue(lastTitleMatchIndex < firstDescriptionOnlyIndex)
        }
    }

    @Test
    fun search_respectsLimit() = runTest {
        val results = feedDirectory.search("a", limit = 5)

        assertTrue(results.size <= 5)
    }

    @Test
    fun search_unmatchedQuery_returnsEmpty() = runTest {
        val results = feedDirectory.search("zzzznonexistentqueryxyz123")

        assertTrue(results.isEmpty())
    }
}
