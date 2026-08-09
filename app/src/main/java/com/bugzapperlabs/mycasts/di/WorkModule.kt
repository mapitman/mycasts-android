package com.bugzapperlabs.mycasts.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.bugzapperlabs.mycasts.download.DownloadManager
import com.bugzapperlabs.mycasts.download.DownloadScheduling
import com.bugzapperlabs.mycasts.refresh.FeedRefreshScheduler
import com.bugzapperlabs.mycasts.refresh.FeedRefreshScheduling
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkBindingModule {
    @Binds
    abstract fun bindFeedRefreshScheduling(impl: FeedRefreshScheduler): FeedRefreshScheduling

    @Binds
    abstract fun bindDownloadScheduling(impl: DownloadManager): DownloadScheduling
}
