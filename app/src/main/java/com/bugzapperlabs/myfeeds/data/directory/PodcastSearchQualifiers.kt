package com.bugzapperlabs.myfeeds.data.directory

import javax.inject.Qualifier

/** Disambiguates the two Hilt bindings of [PodcastSearchProvider] (issue #93) -- [PodcastSearchService]
 *  depends on the interface for both so it stays swappable/fakeable, rather than the concrete
 *  [PodcastIndexSearchProvider]/[FeedDirectory] classes. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OnlinePodcastSearch

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OfflinePodcastSearch
