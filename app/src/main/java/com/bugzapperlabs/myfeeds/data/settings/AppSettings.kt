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
    /** Whether the one-time battery-optimization exemption nudge (issue #273) has already been
     *  shown -- shown at most once regardless of the user's choice, since it's a system dialog
     *  they can always revisit from Settings if they change their mind. */
    val batteryOptimizationPromptShown: Boolean = false,
    /** Free API credentials for live podcast search via podcastindex.org (issue #93), registered
     *  by the user themselves -- there's no ToS-compliant way to bundle a single shared key in an
     *  open-source app. Search silently falls back to the offline directory when either is unset,
     *  see [com.bugzapperlabs.myfeeds.data.directory.PodcastSearchService]. */
    val podcastIndexApiKey: String? = null,
    val podcastIndexApiSecret: String? = null,
)
