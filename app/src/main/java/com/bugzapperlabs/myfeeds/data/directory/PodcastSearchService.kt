package com.bugzapperlabs.myfeeds.data.directory

import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks which [PodcastSearchProvider] handles a search (issue #93, strategy pattern): live
 * podcastindex.org search ([PodcastIndexSearchProvider]) when the user has configured an API
 * key/secret in Settings, otherwise the bundled offline directory ([FeedDirectory]) -- also the
 * fallback if the live search itself fails (network error, bad credentials, etc.), so a search
 * never just goes empty due to a transient online failure. This is the only [PodcastSearchProvider]
 * callers (e.g. `AddFeedViewModel`) should depend on; the two concrete providers stay
 * interchangeable behind it. Depends on the shared interface (via [OnlinePodcastSearch]/
 * [OfflinePodcastSearch] qualifiers), not the concrete classes, so this stays substitutable in
 * tests without needing a real HTTP client or Android assets.
 */
@Singleton
class PodcastSearchService @Inject constructor(
    @OnlinePodcastSearch private val onlineProvider: PodcastSearchProvider,
    @OfflinePodcastSearch private val offlineProvider: PodcastSearchProvider,
    private val settingsDataStore: SettingsDataStore,
) : PodcastSearchProvider {

    override suspend fun search(query: String, limit: Int): List<PodcastSearchResult> {
        val settings = settingsDataStore.settings.first()
        val hasCredentials = !settings.podcastIndexApiKey.isNullOrBlank() && !settings.podcastIndexApiSecret.isNullOrBlank()
        if (!hasCredentials) return offlineProvider.search(query, limit)

        return try {
            onlineProvider.search(query, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            offlineProvider.search(query, limit)
        }
    }
}
