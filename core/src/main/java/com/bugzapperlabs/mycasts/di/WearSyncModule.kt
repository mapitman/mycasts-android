package com.bugzapperlabs.mycasts.di

import android.content.Context
import com.bugzapperlabs.mycasts.sync.PlayServicesWearSyncClient
import com.bugzapperlabs.mycasts.sync.WearSyncClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Shared by `:app` and `:wear` (issue #276) -- `@InstallIn(SingletonComponent::class)` applies
 * per hosting Android application, so defining this once in `:core` wires a [WearSyncClient]
 * into both apps' Hilt components instead of needing an identical module in each.
 */
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
