package com.bugzapperlabs.myfeeds.feedriver

import com.bugzapperlabs.myfeeds.data.local.FeedItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun episode(id: String) = FeedItem(
    id = id,
    feedId = 1,
    title = id,
    itemGuid = id,
    enclosureUrl = "https://example.com/$id.mp3",
    enclosureType = "audio/mpeg",
)

private fun article(id: String) = FeedItem(id = id, feedId = 1, title = id, itemGuid = id)

/**
 * Plain data-class tests, no Robolectric/coroutines -- see
 * [FeedRiverViewModelAddSelectedToQueueTest]'s doc for why [FeedRiverViewModel.uiState] itself
 * isn't exercised directly in this test suite (issue #286).
 */
class FeedRiverUiStateTest {
    @Test
    fun canAddSelectedToQueue_falseWhenSelectionIsEmpty() {
        val state = FeedRiverUiState(articles = listOf(episode("ep-1")), selectedIds = emptySet())

        assertFalse(state.canAddSelectedToQueue)
    }

    @Test
    fun canAddSelectedToQueue_falseWhenSelectionMixesArticlesAndEpisodes() {
        val state = FeedRiverUiState(
            articles = listOf(article("article-1"), episode("ep-1")),
            selectedIds = setOf("article-1", "ep-1"),
        )

        assertFalse(state.canAddSelectedToQueue)
    }

    @Test
    fun canAddSelectedToQueue_trueWhenEverySelectedItemIsAnEpisode() {
        val state = FeedRiverUiState(
            articles = listOf(episode("ep-1"), episode("ep-2"), article("article-1")),
            selectedIds = setOf("ep-1", "ep-2"),
        )

        assertTrue(state.canAddSelectedToQueue)
    }
}
