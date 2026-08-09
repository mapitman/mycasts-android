package com.bugzapperlabs.mycasts.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.bugzapperlabs.mycasts.data.directory.FeedDirectory
import com.bugzapperlabs.mycasts.data.directory.OfflinePodcastSearch
import com.bugzapperlabs.mycasts.data.directory.OnlinePodcastSearch
import com.bugzapperlabs.mycasts.data.directory.PodcastIndexSearchProvider
import com.bugzapperlabs.mycasts.data.directory.PodcastSearchProvider
import com.bugzapperlabs.mycasts.data.directory.PodcastSearchService

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
