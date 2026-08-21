package com.bugzapperlabs.mycasts.data.settings

/** Sentinel for [AppSettings.maxItemsPerFeed] / [com.bugzapperlabs.mycasts.data.local.Feed.itemsToKeep]
 *  meaning "keep every item, never trim" (issue #302) -- outside the sliders' normal 5..100 range,
 *  so it can't collide with a real user-chosen count. */
const val UNLIMITED_ITEMS_TO_KEEP = 0

/** The max-items sliders (Settings, Feed Properties) put "unlimited" past the numeric range,
 *  at the right/max end where a bigger number is expected to live, rather than at the left/min
 *  end where [UNLIMITED_ITEMS_TO_KEEP]'s own value of 0 would otherwise place it (issue #302). */
const val MAX_ARTICLES_SLIDER_UNLIMITED_POSITION = 105f

/** Maps a max-items slider's raw thumb position back to the value to store -- the top position
 *  ([MAX_ARTICLES_SLIDER_UNLIMITED_POSITION]) means unlimited, everything else is a literal count. */
fun itemsToKeepFromSliderPosition(position: Float): Int =
    if (position >= MAX_ARTICLES_SLIDER_UNLIMITED_POSITION) UNLIMITED_ITEMS_TO_KEEP else position.toInt()

/**
 * Ported from SettingsViewModel.cs. Dropped fields with no Android equivalent in this plan:
 * Instapaper username/password (Instapaper integration dropped, see port plan), and
 * SupportedOrientation/LockPortraitMode (WP-specific page orientation lock).
 */
data class AppSettings(
    val updateIntervalMinutes: Long = 30,
    /** Text size across episode details, episode lists, and the podcast list (issue #119) -- a
     *  single setting rather than three independent ones per screen, which mostly just meant
     *  setting each to the same value anyway. A continuous scale factor (issue #125) rather than
     *  a fixed small/normal/large step, see [FONT_SCALE_MIN]/[FONT_SCALE_MAX]. */
    val fontSize: Float = FONT_SCALE_DEFAULT,
    val enableImageDisplay: Boolean = true,
    val maxItemsPerFeed: Int = 20,
    /** How many feeds FeedUpdateEngine refreshes at once (issue #177), trading refresh speed
     *  against network/server load. Mirrors FeedUpdateEngine's prior fixed cap as the default. */
    val feedRefreshConcurrency: Int = 2,
    val defaultToAllItemsView: Boolean = false,
    val allowPodcastDownloadOnBattery: Boolean = false,
    val allowPodcastDownloadOnMobileData: Boolean = false,
    /** Whether streaming is allowed over mobile data (issue #123) -- Wi-Fi streaming is always
     *  allowed regardless of this setting; it only gates the mobile-data case. Same field/default
     *  as the blanket "allow streaming" toggle this replaced, so an existing choice to disable
     *  streaming (data-conscious) narrows to "mobile data only" instead of resetting to the
     *  default. */
    val allowPodcastStreamingOnMobileData: Boolean = true,
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
    /** Whether the one-time proactive POST_NOTIFICATIONS request (issue #43) has already been
     *  shown at app launch -- shown at most once regardless of the user's choice, since Android
     *  only surfaces the system permission dialog once per install (a second call to
     *  requestPermission() after a denial returns denied without showing UI). The Settings
     *  screen's "Notify on new items" toggle can still re-request it as a secondary path if the
     *  user changes their mind after granting it from system Settings. */
    val notificationPermissionPromptShown: Boolean = false,
    /** Whether the one-time first-launch "add starter feeds?" prompt (issue #108) has already been
     *  shown -- shown at most once regardless of the user's choice, and never re-shown just because
     *  the feed list is empty again later (e.g. after Settings' "Remove all feeds"). */
    val addDefaultFeedsPromptShown: Boolean = false,
    /** Free API credentials for live podcast search via podcastindex.org (issue #93), registered
     *  by the user themselves -- there's no ToS-compliant way to bundle a single shared key in an
     *  open-source app. Search silently falls back to the offline directory when either is unset,
     *  see [com.bugzapperlabs.mycasts.data.directory.PodcastSearchService]. */
    val podcastIndexApiKey: String? = null,
    val podcastIndexApiSecret: String? = null,
    /** Follows the device's Material You theme color when available (issue #95), instead of the
     *  fixed MyCasts brand colors. Defaults to on; a user who prefers the brand colors can turn it
     *  back off. Has no effect below Android 12 (see [com.bugzapperlabs.mycasts.ui.theme.MyCastsTheme]). */
    val useDeviceThemeColors: Boolean = true,
    /** Episode ids the background refresh worker has found since the app was last opened in the
     *  foreground (issue #161) -- accumulates across multiple scheduled refreshes so the "new
     *  episodes" notification reflects everything missed since the user last opened the app, not
     *  just the most recent refresh. Flushed into [newEpisodeIdsToShow] and cleared the next time
     *  the app is actually foregrounded, see [SettingsDataStore.markAppOpened]. */
    val pendingNewEpisodeIds: Set<String> = emptySet(),
    /** Snapshot of [pendingNewEpisodeIds] captured at the moment the app was last opened (issue
     *  #161) -- drives which feeds the podcast list highlights as having a new episode. Frozen at
     *  open time rather than read live, so it doesn't change under the user's feet as a new batch
     *  starts accumulating in [pendingNewEpisodeIds] behind it. */
    val newEpisodeIdsToShow: Set<String> = emptySet(),
)
