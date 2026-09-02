package com.bugzapperlabs.mycasts.wear.di

import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.QueueDownloadTrigger
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** The watch never downloads episodes in phase 1 (issue #276) -- it streams directly from
 *  `enclosureUrl` over its own connection, so [QueueRepository]'s download trigger is a no-op
 *  here (the phone's [com.bugzapperlabs.mycasts.download.EnclosureDownloadRepository] binding is
 *  the real one; downloading to the watch itself is issue #277). */
class NoOpQueueDownloadTrigger @Inject constructor() : QueueDownloadTrigger {
    override suspend fun ensureDownloaded(item: FeedItem) = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WearQueueModule {
    @Binds
    abstract fun bindQueueDownloadTrigger(impl: NoOpQueueDownloadTrigger): QueueDownloadTrigger
}
