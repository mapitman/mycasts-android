package com.bugzapperlabs.mycasts.wear.di

import android.content.Context
import com.bugzapperlabs.mycasts.sync.WearSyncClient
import com.bugzapperlabs.mycasts.wear.sync.PlayServicesWearSyncClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WearDataClientModule {
    @Provides
    @Singleton
    fun provideDataClient(@ApplicationContext context: Context): DataClient = Wearable.getDataClient(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WearSyncBindingModule {
    @Binds
    abstract fun bindWearSyncClient(impl: PlayServicesWearSyncClient): WearSyncClient
}
