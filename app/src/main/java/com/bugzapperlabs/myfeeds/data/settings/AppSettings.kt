package com.bugzapperlabs.myfeeds.data.settings

/**
 * Ported from SettingsViewModel.cs. Dropped fields with no Android equivalent in this plan:
 * Instapaper username/password (Instapaper integration dropped, see port plan), and
 * SupportedOrientation/LockPortraitMode (WP-specific page orientation lock).
 */
data class AppSettings(
    val updateIntervalMinutes: Long = 30,
    val listFontSize: FontSize = FontSize.NORMAL,
    val feedListFontSize: FontSize = FontSize.LARGE,
    val articleFontSize: FontSize = FontSize.NORMAL,
    val enableImageDisplay: Boolean = true,
    val maxArticles: Int = 20,
    /** How many feeds FeedUpdateEngine refreshes at once (issue #177), trading refresh speed
     *  against network/server load. Mirrors FeedUpdateEngine's prior fixed cap as the default. */
    val feedRefreshConcurrency: Int = 2,
    val defaultToAllArticleView: Boolean = false,
    val allowPodcastDownloadOnBattery: Boolean = false,
    val allowPodcastDownloadOnCellular: Boolean = false,
    val allowPodcastStreaming: Boolean = true,
    /** Deletes a downloaded episode's file once it's fully played (issue #71). */
    val autoDeleteFinishedDownloads: Boolean = false,
    val notifyOnNewItems: Boolean = false,
    val lastImportUrl: String? = null,
    val lastFeedUpdateEpochMillis: Long? = null,
    /** The episode last loaded into the player, restored on app relaunch (issue #108). */
    val lastPlayingFeedId: Long? = null,
    val lastPlayingItemId: String? = null,
)
