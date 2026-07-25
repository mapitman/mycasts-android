package com.bugzapperlabs.myfeeds.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.myfeeds.data.local.Feed
import com.bugzapperlabs.myfeeds.data.local.FeedDao
import com.bugzapperlabs.myfeeds.data.opml.OpmlParser
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Loads the bundled default_feeds.opml (refreshed from the WinPhone app's stale DefaultFeeds.xml
 * -- see MyFeeds/DefaultFeeds.xml) into Feed on first run. The asset still nests feeds under
 * folder outlines for readability, but [OpmlParser] flattens those (issue #118 -- categories no
 * longer exist), so every feed lands in the same unordered list.
 */
class DefaultFeedsSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val feedDao: FeedDao,
    private val settingsDataStore: SettingsDataStore,
) {
    suspend fun seedIfFirstRun() {
        if (!settingsDataStore.settings.first().isFirstRun) return

        val opml = context.assets.open(DEFAULT_FEEDS_ASSET).use { OpmlParser.parse(it) }
        opml.feeds.forEachIndexed { feedIndex, feed ->
            feedDao.insert(Feed(title = feed.title, feedUrl = feed.xmlUrl, sortOrder = feedIndex))
        }

        settingsDataStore.setFirstRunComplete()
    }

    companion object {
        private const val DEFAULT_FEEDS_ASSET = "default_feeds.opml"
    }
}
