package com.bugzapperlabs.myfeeds.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.bugzapperlabs.myfeeds.data.local.AppDatabase
import com.bugzapperlabs.myfeeds.data.local.FeedDao
import com.bugzapperlabs.myfeeds.data.local.FeedItemDao
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_1_2
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_2_3
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_3_4
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_4_5
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_5_6
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_6_7
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_7_8
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_8_9
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_9_10
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_10_11
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_11_12
import com.bugzapperlabs.myfeeds.data.local.MIGRATION_12_13
import com.bugzapperlabs.myfeeds.data.local.QueueDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
            )
            .build()

    @Provides
    fun provideFeedDao(database: AppDatabase): FeedDao = database.feedDao()

    @Provides
    fun provideFeedItemDao(database: AppDatabase): FeedItemDao = database.feedItemDao()

    @Provides
    fun provideQueueDao(database: AppDatabase): QueueDao = database.queueDao()
}
