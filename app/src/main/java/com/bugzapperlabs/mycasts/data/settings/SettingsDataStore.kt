package com.bugzapperlabs.mycasts.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SettingsDataStore @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            updateIntervalMinutes = prefs[Keys.UPDATE_INTERVAL_MINUTES] ?: AppSettings().updateIntervalMinutes,
            fontSize = prefs[Keys.FONT_SIZE_SCALE] ?: AppSettings().fontSize,
            enableImageDisplay = prefs[Keys.ENABLE_IMAGE_DISPLAY] ?: AppSettings().enableImageDisplay,
            maxItemsPerFeed = prefs[Keys.MAX_ARTICLES] ?: AppSettings().maxItemsPerFeed,
            feedRefreshConcurrency = prefs[Keys.FEED_REFRESH_CONCURRENCY] ?: AppSettings().feedRefreshConcurrency,
            defaultToAllItemsView = prefs[Keys.DEFAULT_TO_ALL_ARTICLE_VIEW]
                ?: AppSettings().defaultToAllItemsView,
            allowPodcastDownloadOnBattery = prefs[Keys.ALLOW_PODCAST_DOWNLOAD_ON_BATTERY]
                ?: AppSettings().allowPodcastDownloadOnBattery,
            allowPodcastDownloadOnCellular = prefs[Keys.ALLOW_PODCAST_DOWNLOAD_ON_CELLULAR]
                ?: AppSettings().allowPodcastDownloadOnCellular,
            allowPodcastStreaming = prefs[Keys.ALLOW_PODCAST_STREAMING] ?: AppSettings().allowPodcastStreaming,
            autoDeleteFinishedDownloads = prefs[Keys.AUTO_DELETE_FINISHED_DOWNLOADS]
                ?: AppSettings().autoDeleteFinishedDownloads,
            notifyOnNewItems = prefs[Keys.NOTIFY_ON_NEW_ITEMS] ?: AppSettings().notifyOnNewItems,
            lastImportUrl = prefs[Keys.LAST_IMPORT_URL],
            lastFeedUpdateEpochMillis = prefs[Keys.LAST_FEED_UPDATE_EPOCH_MILLIS],
            lastPlayingFeedId = prefs[Keys.LAST_PLAYING_FEED_ID],
            lastPlayingItemId = prefs[Keys.LAST_PLAYING_ITEM_ID],
            batteryOptimizationPromptShown = prefs[Keys.BATTERY_OPTIMIZATION_PROMPT_SHOWN]
                ?: AppSettings().batteryOptimizationPromptShown,
            notificationPermissionPromptShown = prefs[Keys.NOTIFICATION_PERMISSION_PROMPT_SHOWN]
                ?: AppSettings().notificationPermissionPromptShown,
            addDefaultFeedsPromptShown = prefs[Keys.ADD_DEFAULT_FEEDS_PROMPT_SHOWN]
                ?: AppSettings().addDefaultFeedsPromptShown,
            podcastIndexApiKey = prefs[Keys.PODCAST_INDEX_API_KEY],
            podcastIndexApiSecret = prefs[Keys.PODCAST_INDEX_API_SECRET],
            autoDownloadNewFeedsByDefault = prefs[Keys.AUTO_DOWNLOAD_NEW_FEEDS_BY_DEFAULT]
                ?: AppSettings().autoDownloadNewFeedsByDefault,
            autoDownloadNewFeedsMaxCount = when (val stored = prefs[Keys.AUTO_DOWNLOAD_NEW_FEEDS_MAX_COUNT]) {
                null -> AppSettings().autoDownloadNewFeedsMaxCount
                UNLIMITED_MAX_DOWNLOADS_SENTINEL -> null
                else -> stored
            },
            useDeviceThemeColors = prefs[Keys.USE_DEVICE_THEME_COLORS] ?: AppSettings().useDeviceThemeColors,
            pendingNewEpisodeIds = prefs[Keys.PENDING_NEW_EPISODE_IDS] ?: AppSettings().pendingNewEpisodeIds,
            newEpisodeIdsToShow = prefs[Keys.NEW_EPISODE_IDS_TO_SHOW] ?: AppSettings().newEpisodeIdsToShow,
        )
    }

    suspend fun setUpdateIntervalMinutes(minutes: Long) {
        dataStore.edit { it[Keys.UPDATE_INTERVAL_MINUTES] = minutes }
    }

    suspend fun setFontSize(scale: Float) {
        dataStore.edit { it[Keys.FONT_SIZE_SCALE] = scale }
    }

    suspend fun setEnableImageDisplay(enabled: Boolean) {
        dataStore.edit { it[Keys.ENABLE_IMAGE_DISPLAY] = enabled }
    }

    suspend fun setMaxItemsPerFeed(count: Int) {
        dataStore.edit { it[Keys.MAX_ARTICLES] = count }
    }

    suspend fun setFeedRefreshConcurrency(count: Int) {
        dataStore.edit { it[Keys.FEED_REFRESH_CONCURRENCY] = count }
    }

    suspend fun setDefaultToAllItemsView(value: Boolean) {
        dataStore.edit { it[Keys.DEFAULT_TO_ALL_ARTICLE_VIEW] = value }
    }

    suspend fun setAllowPodcastDownloadOnBattery(value: Boolean) {
        dataStore.edit { it[Keys.ALLOW_PODCAST_DOWNLOAD_ON_BATTERY] = value }
    }

    suspend fun setAllowPodcastDownloadOnCellular(value: Boolean) {
        dataStore.edit { it[Keys.ALLOW_PODCAST_DOWNLOAD_ON_CELLULAR] = value }
    }

    suspend fun setAllowPodcastStreaming(value: Boolean) {
        dataStore.edit { it[Keys.ALLOW_PODCAST_STREAMING] = value }
    }

    suspend fun setAutoDeleteFinishedDownloads(value: Boolean) {
        dataStore.edit { it[Keys.AUTO_DELETE_FINISHED_DOWNLOADS] = value }
    }

    suspend fun setNotifyOnNewItems(value: Boolean) {
        dataStore.edit { it[Keys.NOTIFY_ON_NEW_ITEMS] = value }
    }

    suspend fun setLastImportUrl(url: String?) {
        dataStore.edit {
            if (url == null) it.remove(Keys.LAST_IMPORT_URL) else it[Keys.LAST_IMPORT_URL] = url
        }
    }

    suspend fun setLastFeedUpdateEpochMillis(epochMillis: Long) {
        dataStore.edit { it[Keys.LAST_FEED_UPDATE_EPOCH_MILLIS] = epochMillis }
    }

    /** Tracks the episode loaded into the player so it can be restored on relaunch (issue #108). */
    suspend fun setLastPlayingItem(feedId: Long?, itemId: String?) {
        dataStore.edit {
            if (feedId == null || itemId == null) {
                it.remove(Keys.LAST_PLAYING_FEED_ID)
                it.remove(Keys.LAST_PLAYING_ITEM_ID)
            } else {
                it[Keys.LAST_PLAYING_FEED_ID] = feedId
                it[Keys.LAST_PLAYING_ITEM_ID] = itemId
            }
        }
    }

    /** Mirrors the original SettingsViewModel.Reset(): clears all settings back to defaults. */
    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    /** Overwrites every setting with [settings] wholesale (issue #157), for a full backup
     *  restore -- clears first rather than merging, so a setting absent from an older backup
     *  (a field added since) falls back to [AppSettings]'s own default instead of leaving
     *  whatever this device already had lying around underneath it. */
    suspend fun restore(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs.clear()
            prefs[Keys.UPDATE_INTERVAL_MINUTES] = settings.updateIntervalMinutes
            prefs[Keys.FONT_SIZE_SCALE] = settings.fontSize
            prefs[Keys.ENABLE_IMAGE_DISPLAY] = settings.enableImageDisplay
            prefs[Keys.MAX_ARTICLES] = settings.maxItemsPerFeed
            prefs[Keys.FEED_REFRESH_CONCURRENCY] = settings.feedRefreshConcurrency
            prefs[Keys.DEFAULT_TO_ALL_ARTICLE_VIEW] = settings.defaultToAllItemsView
            prefs[Keys.ALLOW_PODCAST_DOWNLOAD_ON_BATTERY] = settings.allowPodcastDownloadOnBattery
            prefs[Keys.ALLOW_PODCAST_DOWNLOAD_ON_CELLULAR] = settings.allowPodcastDownloadOnCellular
            prefs[Keys.ALLOW_PODCAST_STREAMING] = settings.allowPodcastStreaming
            prefs[Keys.AUTO_DELETE_FINISHED_DOWNLOADS] = settings.autoDeleteFinishedDownloads
            prefs[Keys.NOTIFY_ON_NEW_ITEMS] = settings.notifyOnNewItems
            settings.lastImportUrl?.let { prefs[Keys.LAST_IMPORT_URL] = it }
            settings.lastFeedUpdateEpochMillis?.let { prefs[Keys.LAST_FEED_UPDATE_EPOCH_MILLIS] = it }
            settings.lastPlayingFeedId?.let { prefs[Keys.LAST_PLAYING_FEED_ID] = it }
            settings.lastPlayingItemId?.let { prefs[Keys.LAST_PLAYING_ITEM_ID] = it }
            prefs[Keys.BATTERY_OPTIMIZATION_PROMPT_SHOWN] = settings.batteryOptimizationPromptShown
            prefs[Keys.NOTIFICATION_PERMISSION_PROMPT_SHOWN] = settings.notificationPermissionPromptShown
            prefs[Keys.ADD_DEFAULT_FEEDS_PROMPT_SHOWN] = settings.addDefaultFeedsPromptShown
            settings.podcastIndexApiKey?.let { prefs[Keys.PODCAST_INDEX_API_KEY] = it }
            settings.podcastIndexApiSecret?.let { prefs[Keys.PODCAST_INDEX_API_SECRET] = it }
            prefs[Keys.AUTO_DOWNLOAD_NEW_FEEDS_BY_DEFAULT] = settings.autoDownloadNewFeedsByDefault
            prefs[Keys.AUTO_DOWNLOAD_NEW_FEEDS_MAX_COUNT] =
                settings.autoDownloadNewFeedsMaxCount ?: UNLIMITED_MAX_DOWNLOADS_SENTINEL
            prefs[Keys.USE_DEVICE_THEME_COLORS] = settings.useDeviceThemeColors
        }
    }

    suspend fun setBatteryOptimizationPromptShown(shown: Boolean) {
        dataStore.edit { it[Keys.BATTERY_OPTIMIZATION_PROMPT_SHOWN] = shown }
    }

    suspend fun setNotificationPermissionPromptShown(shown: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATION_PERMISSION_PROMPT_SHOWN] = shown }
    }

    suspend fun setAddDefaultFeedsPromptShown(shown: Boolean) {
        dataStore.edit { it[Keys.ADD_DEFAULT_FEEDS_PROMPT_SHOWN] = shown }
    }

    suspend fun setPodcastIndexApiKey(key: String?) {
        dataStore.edit {
            if (key.isNullOrBlank()) it.remove(Keys.PODCAST_INDEX_API_KEY) else it[Keys.PODCAST_INDEX_API_KEY] = key
        }
    }

    suspend fun setPodcastIndexApiSecret(secret: String?) {
        dataStore.edit {
            if (secret.isNullOrBlank()) it.remove(Keys.PODCAST_INDEX_API_SECRET) else it[Keys.PODCAST_INDEX_API_SECRET] = secret
        }
    }

    suspend fun setAutoDownloadNewFeedsByDefault(value: Boolean) {
        dataStore.edit { it[Keys.AUTO_DOWNLOAD_NEW_FEEDS_BY_DEFAULT] = value }
    }

    suspend fun setAutoDownloadNewFeedsMaxCount(value: Int?) {
        dataStore.edit { it[Keys.AUTO_DOWNLOAD_NEW_FEEDS_MAX_COUNT] = value ?: UNLIMITED_MAX_DOWNLOADS_SENTINEL }
    }

    suspend fun setUseDeviceThemeColors(value: Boolean) {
        dataStore.edit { it[Keys.USE_DEVICE_THEME_COLORS] = value }
    }

    /** Adds [ids] to the pool of episodes found since the app was last opened (issue #161),
     *  called by [com.bugzapperlabs.mycasts.refresh.FeedRefreshWorker] after every scheduled
     *  refresh that turns up new episodes -- a plain union rather than a replace, so several
     *  refreshes in a row without the app being opened keep accumulating instead of the count
     *  resetting to just the most recent run. */
    suspend fun addPendingNewEpisodeIds(ids: Collection<String>) {
        dataStore.edit {
            val current = it[Keys.PENDING_NEW_EPISODE_IDS] ?: emptySet()
            it[Keys.PENDING_NEW_EPISODE_IDS] = current + ids
        }
    }

    /** Marks the app as opened in the foreground (issue #161): freezes whatever's currently
     *  pending into [AppSettings.newEpisodeIdsToShow] for the "New episodes" screen to display,
     *  then clears the pending pool so the next scheduled refresh starts a fresh count. Called
     *  from [com.bugzapperlabs.mycasts.MyCastsApp]'s `ProcessLifecycleOwner` observer, which only
     *  fires on a genuine foreground transition -- not when a background refresh runs with no
     *  Activity ever shown. */
    suspend fun markAppOpened() {
        dataStore.edit {
            val pending = it[Keys.PENDING_NEW_EPISODE_IDS] ?: emptySet()
            it[Keys.NEW_EPISODE_IDS_TO_SHOW] = pending
            it[Keys.PENDING_NEW_EPISODE_IDS] = emptySet()
        }
    }

    // The string literals below are the on-disk DataStore key names, not the Kotlin API (issue
    // #11 renamed AppSettings/SettingsDataStore's article-flavored fields/functions to
    // episode/item wording) -- changing a literal here would silently reset that existing user's
    // saved value to default on upgrade, since DataStore looks up by the literal, not the
    // Kotlin identifier pointing at it.
    private object Keys {
        val UPDATE_INTERVAL_MINUTES = longPreferencesKey("update_interval_minutes")
        // issue #119: a fresh key, not a reuse of the three it replaced (list_font_size/
        // feed_list_font_size/article_font_size) -- those stay as unread dead keys on an existing
        // install rather than being repurposed, since this is a new (merged) setting, not a rename
        // of one of the old three.
        // issue #125: another fresh key (float, not the old int ordinal) rather than reusing
        // "font_size" -- DataStore's Preferences proto tags each entry with its type, so reading
        // an existing int-typed value back out as a float throws at runtime instead of migrating.
        val FONT_SIZE_SCALE = floatPreferencesKey("font_size_scale")
        val ENABLE_IMAGE_DISPLAY = booleanPreferencesKey("enable_image_display")
        val MAX_ARTICLES = intPreferencesKey("max_articles")
        val FEED_REFRESH_CONCURRENCY = intPreferencesKey("feed_refresh_concurrency")
        val DEFAULT_TO_ALL_ARTICLE_VIEW = booleanPreferencesKey("default_to_all_article_view")
        val ALLOW_PODCAST_DOWNLOAD_ON_BATTERY = booleanPreferencesKey("allow_podcast_download_on_battery")
        val ALLOW_PODCAST_DOWNLOAD_ON_CELLULAR = booleanPreferencesKey("allow_podcast_download_on_cellular")
        val ALLOW_PODCAST_STREAMING = booleanPreferencesKey("allow_podcast_streaming")
        val AUTO_DELETE_FINISHED_DOWNLOADS = booleanPreferencesKey("auto_delete_finished_downloads")
        val NOTIFY_ON_NEW_ITEMS = booleanPreferencesKey("notify_on_new_items")
        val LAST_IMPORT_URL = stringPreferencesKey("last_import_url")
        val LAST_FEED_UPDATE_EPOCH_MILLIS = longPreferencesKey("last_feed_update_epoch_millis")
        val LAST_PLAYING_FEED_ID = longPreferencesKey("last_playing_feed_id")
        val LAST_PLAYING_ITEM_ID = stringPreferencesKey("last_playing_item_id")
        val BATTERY_OPTIMIZATION_PROMPT_SHOWN = booleanPreferencesKey("battery_optimization_prompt_shown")
        val NOTIFICATION_PERMISSION_PROMPT_SHOWN = booleanPreferencesKey("notification_permission_prompt_shown")
        val ADD_DEFAULT_FEEDS_PROMPT_SHOWN = booleanPreferencesKey("add_default_feeds_prompt_shown")
        val PODCAST_INDEX_API_KEY = stringPreferencesKey("podcast_index_api_key")
        val PODCAST_INDEX_API_SECRET = stringPreferencesKey("podcast_index_api_secret")
        val AUTO_DOWNLOAD_NEW_FEEDS_BY_DEFAULT = booleanPreferencesKey("auto_download_new_feeds_by_default")
        val AUTO_DOWNLOAD_NEW_FEEDS_MAX_COUNT = intPreferencesKey("auto_download_new_feeds_max_count")
        val USE_DEVICE_THEME_COLORS = booleanPreferencesKey("use_device_theme_colors")
        val PENDING_NEW_EPISODE_IDS = stringSetPreferencesKey("pending_new_episode_ids")
        val NEW_EPISODE_IDS_TO_SHOW = stringSetPreferencesKey("new_episode_ids_to_show")
    }
}
