package com.bugzapperlabs.myfeeds.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bugzapperlabs.myfeeds.MainActivity
import com.bugzapperlabs.myfeeds.R
import kotlinx.coroutines.flow.first

data class UnreadFeed(val feedId: Long, val title: String, val unreadCount: Int)

val FeedIdParam = ActionParameters.Key<Long>("feedId")

/**
 * Replaces the WinPhone app's live/secondary tiles (issue #24): per-feed and total unread counts,
 * tapping a feed deep-links into its article list via [MainActivity]'s `feedId` intent extra.
 * Rendered from a one-shot snapshot rather than a continuously observed Flow -- the widget is
 * refreshed explicitly (on app launch and after each scheduled feed refresh), which keeps the
 * update model simple and matches how infrequently a home-screen surface actually needs to repaint.
 */
class UnreadWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = UnreadWidgetEntryPoint.from(context)
        val feeds = entryPoint.feedDao().observeAll().first()
        val unreadCounts = entryPoint.feedItemDao().observeUnreadCountsByFeed().first()
            .associate { it.feedId to it.count }
        val totalUnread = unreadCounts.values.sum()

        val unreadFeeds = feeds
            .mapNotNull { feed ->
                val count = unreadCounts[feed.id] ?: 0
                if (count == 0) return@mapNotNull null
                UnreadFeed(feedId = feed.id, title = feed.userTitle ?: feed.title.orEmpty(), unreadCount = count)
            }
            .sortedByDescending { it.unreadCount }

        val appName = context.getString(R.string.app_name)
        val unreadText = context.getString(R.string.feed_list_total_unread, totalUnread)
        val allCaughtUpText = context.getString(R.string.widget_all_caught_up)

        provideContent {
            GlanceTheme {
                WidgetContent(appName, unreadText, allCaughtUpText, unreadFeeds)
            }
        }
    }
}

@Composable
private fun WidgetContent(appName: String, unreadText: String, allCaughtUpText: String, feeds: List<UnreadFeed>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF1B1B1B))
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            text = appName,
            style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 16.sp),
        )
        Text(
            text = unreadText,
            style = TextStyle(color = ColorProvider(Color(0xFF8BC34A)), fontSize = 13.sp),
        )
        if (feeds.isEmpty()) {
            Text(
                text = allCaughtUpText,
                style = TextStyle(color = ColorProvider(Color.LightGray), fontSize = 13.sp),
                modifier = GlanceModifier.padding(top = 8.dp),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp)) {
                items(feeds, itemId = { it.feedId }) { feed -> FeedRow(feed) }
            }
        }
    }
}

@Composable
private fun FeedRow(feed: UnreadFeed) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(
                actionStartActivity<MainActivity>(parameters = actionParametersOf(FeedIdParam to feed.feedId)),
            ),
    ) {
        Text(
            text = feed.title,
            style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp),
            modifier = GlanceModifier.defaultWeight(),
            maxLines = 1,
        )
        Text(
            text = feed.unreadCount.toString(),
            style = TextStyle(color = ColorProvider(Color(0xFF8BC34A)), fontSize = 13.sp),
        )
    }
}
