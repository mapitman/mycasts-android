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

/** Sentinel used only for [AppSettings.autoDownloadNewFeedsMaxCount]'s on-disk DataStore
 *  representation (issue #98) -- [Preferences] has no native "key present but null" state the way
 *  a nullable Room column does, so "Unlimited" (the Kotlin `null`) has to round-trip through a
 *  literal value distinguishable from every real choice in the 1/3/5/10 chip row, and from an
 *  absent key (which means "never configured", falling back to the real default of 5). */
const val UNLIMITED_MAX_DOWNLOADS_SENTINEL = 0

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
    /** Default [com.bugzapperlabs.mycasts.data.local.Feed.autoDownloadEnabled] applied to a feed
     *  the first time it's subscribed (issue #98), mirroring how autoQueueEnabled already defaults
     *  on for new podcast feeds. Existing feeds and any later per-feed change in Feed Properties
     *  are unaffected either way. */
    val autoDownloadNewFeedsByDefault: Boolean = false,
    /** Default [com.bugzapperlabs.mycasts.data.local.Feed.maxDownloadsToKeep] applied alongside
     *  [autoDownloadNewFeedsByDefault] (issue #98) -- same 1/3/5/10/unlimited(null) options as the
     *  per-feed chips in Feed Properties. */
    val autoDownloadNewFeedsMaxCount: Int? = 5,
    /** Follows the device's Material You theme color when available (issue #95), instead of the
     *  fixed MyCasts brand colors. Defaults to on; a user who prefers the brand colors can turn it
     *  back off. Has no effect below Android 12 (see [com.bugzapperlabs.mycasts.ui.theme.MyCastsTheme]). */
    val useDeviceThemeColors: Boolean = true,
)
