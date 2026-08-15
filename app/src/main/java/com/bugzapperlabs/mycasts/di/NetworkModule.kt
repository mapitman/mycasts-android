package com.bugzapperlabs.mycasts.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** [EnclosureDownloadWorker][com.bugzapperlabs.mycasts.download.EnclosureDownloadWorker]'s client
 *  (issue #188 follow-up) -- see [NetworkModule.provideDownloadOkHttpClient]'s doc for why this
 *  can't just be the general-purpose client every other network caller shares. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * A separate client, not the shared [provideOkHttpClient] singleton, so its concurrency cap
     * only ever throttles downloads -- never a feed refresh or search request racing to share the
     * same connection budget.
     *
     * Bulk-downloading a large Next Up queue (issue #188) or a big multi-episode selection can
     * enqueue dozens of [com.bugzapperlabs.mycasts.download.EnclosureDownloadWorker] jobs at once;
     * each one's request otherwise fires immediately and independently, opening that many
     * simultaneous connections spread across as many distinct podcast-host CDNs -- observed in
     * practice to make most of them fail (and hit their own [EnclosureDownloadWorker] retry) within
     * about a second of starting. Routing every download through one shared [Dispatcher] with a
     * modest [Dispatcher.maxRequests] bounds how many of those connections are ever actually open
     * at once, queuing the rest until a slot frees up -- the same "cap the burst" fix already
     * applied to auto-download for the same reason (see AutoQueueAndDownloadEnforcer's
     * MAX_ITEMS_PER_REFRESH_WHEN_UNLIMITED), just at the transport layer instead of the item count,
     * since here the burst can span many hosts at once rather than one feed's own backlog.
     */
    @Provides
    @Singleton
    @DownloadHttpClient
    fun provideDownloadOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = MAX_CONCURRENT_DOWNLOADS; maxRequestsPerHost = MAX_CONCURRENT_DOWNLOADS })
        .build()

    private const val MAX_CONCURRENT_DOWNLOADS = 3
}
