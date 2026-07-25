package com.bugzapperlabs.myfeeds.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.bugzapperlabs.myfeeds.data.local.FeedDao
import com.bugzapperlabs.myfeeds.data.local.FeedItemDao

/**
 * [androidx.glance.appwidget.GlanceAppWidget] isn't an Android component Hilt can inject directly
 * (no `@AndroidEntryPoint` support), so its data dependencies are pulled out of the app-wide Hilt
 * graph via this entry point instead.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UnreadWidgetEntryPoint {
    fun feedDao(): FeedDao
    fun feedItemDao(): FeedItemDao

    companion object {
        fun from(context: android.content.Context): UnreadWidgetEntryPoint =
            EntryPointAccessors.fromApplication(context, UnreadWidgetEntryPoint::class.java)
    }
}
