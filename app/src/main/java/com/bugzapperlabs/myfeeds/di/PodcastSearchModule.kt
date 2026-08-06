package com.bugzapperlabs.myfeeds.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.bugzapperlabs.myfeeds.data.directory.FeedDirectory
import com.bugzapperlabs.myfeeds.data.directory.OfflinePodcastSearch
import com.bugzapperlabs.myfeeds.data.directory.OnlinePodcastSearch
import com.bugzapperlabs.myfeeds.data.directory.PodcastIndexSearchProvider
import com.bugzapperlabs.myfeeds.data.directory.PodcastSearchProvider
import com.bugzapperlabs.myfeeds.data.directory.PodcastSearchService

@Module
@InstallIn(SingletonComponent::class)
abstract class PodcastSearchModule {
    @Binds
    @OnlinePodcastSearch
    abstract fun bindOnlinePodcastSearchProvider(impl: PodcastIndexSearchProvider): PodcastSearchProvider

    @Binds
    @OfflinePodcastSearch
    abstract fun bindOfflinePodcastSearchProvider(impl: FeedDirectory): PodcastSearchProvider

    @Binds
    abstract fun bindPodcastSearchProvider(impl: PodcastSearchService): PodcastSearchProvider
}
