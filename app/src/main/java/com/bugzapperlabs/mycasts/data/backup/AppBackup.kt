package com.bugzapperlabs.mycasts.data.backup

import com.bugzapperlabs.mycasts.data.local.AutoQueuePosition
import com.bugzapperlabs.mycasts.data.local.Feed
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.local.QueueEntry
import com.bugzapperlabs.mycasts.data.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * A full snapshot of local app state (issue #157): every feed/item/queue entry plus settings,
 * not just the subscribed-feed list OPML already covers -- read/played state, queue order,
 * per-feed settings, and app settings all round-trip through this too. Downloaded episode files
 * themselves aren't included (they're large and re-fetchable); only the DB rows referencing them
 * are, so a restored device shows the same "downloaded" episodes but has to redownload the files.
 */
data class AppBackup(
    val feeds: List<Feed>,
    val feedItems: List<FeedItem>,
    val queueEntries: List<QueueEntry>,
    val settings: AppSettings,
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("backupVersion", BACKUP_VERSION)
        root.put("feeds", JSONArray(feeds.map { it.toJson() }))
        root.put("feedItems", JSONArray(feedItems.map { it.toJson() }))
        root.put("queueEntries", JSONArray(queueEntries.map { it.toJson() }))
        root.put("settings", settings.toJson())
        return root.toString(2)
    }

    companion object {
        private const val BACKUP_VERSION = 1

        fun fromJson(json: String): AppBackup {
            val root = JSONObject(json)
            return AppBackup(
                feeds = root.getJSONArray("feeds").objects().map { it.toFeed() },
                feedItems = root.getJSONArray("feedItems").objects().map { it.toFeedItem() },
                queueEntries = root.getJSONArray("queueEntries").objects().map { it.toQueueEntry() },
                settings = root.getJSONObject("settings").toAppSettings(),
            )
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

private fun JSONObject.putOpt(name: String, value: Any?) {
    if (value != null) put(name, value)
}

private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) getLong(name) else null
private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name) && !isNull(name)) getInt(name) else null
private fun JSONObject.optDoubleOrNull(name: String): Double? = if (has(name) && !isNull(name)) getDouble(name) else null
private fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null

private fun Feed.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    putOpt("title", title)
    putOpt("userTitle", userTitle)
    putOpt("description", description)
    putOpt("feedUrl", feedUrl)
    putOpt("siteUrl", siteUrl)
    putOpt("imageUrl", imageUrl)
    putOpt("displayMode", displayMode)
    putOpt("itemsToKeep", itemsToKeep)
    putOpt("lastGet", lastGet)
    putOpt("sortOrder", sortOrder)
    put("autoDownloadEnabled", autoDownloadEnabled)
    put("autoQueueEnabled", autoQueueEnabled)
    putOpt("autoQueueMaxCount", autoQueueMaxCount)
    put("playbackSpeed", playbackSpeed.toDouble())
    put("autoQueuePosition", autoQueuePosition.name)
    put("volumeBoostMillibels", volumeBoostMillibels)
    put("startSkipSeconds", startSkipSeconds)
    putOpt("maxDownloadsToKeep", maxDownloadsToKeep)
}

private fun JSONObject.toFeed(): Feed = Feed(
    id = getLong("id"),
    title = optStringOrNull("title"),
    userTitle = optStringOrNull("userTitle"),
    description = optStringOrNull("description"),
    feedUrl = optStringOrNull("feedUrl"),
    siteUrl = optStringOrNull("siteUrl"),
    imageUrl = optStringOrNull("imageUrl"),
    displayMode = optIntOrNull("displayMode"),
    itemsToKeep = optIntOrNull("itemsToKeep"),
    lastGet = optLongOrNull("lastGet"),
    sortOrder = optIntOrNull("sortOrder"),
    autoDownloadEnabled = optBoolean("autoDownloadEnabled"),
    autoQueueEnabled = optBoolean("autoQueueEnabled"),
    autoQueueMaxCount = optIntOrNull("autoQueueMaxCount"),
    playbackSpeed = optDouble("playbackSpeed", 1.0).toFloat(),
    autoQueuePosition = optStringOrNull("autoQueuePosition")?.let { AutoQueuePosition.valueOf(it) }
        ?: AutoQueuePosition.BOTTOM,
    volumeBoostMillibels = optInt("volumeBoostMillibels"),
    startSkipSeconds = optInt("startSkipSeconds"),
    maxDownloadsToKeep = optIntOrNull("maxDownloadsToKeep"),
)

private fun FeedItem.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("feedId", feedId)
    putOpt("title", title)
    putOpt("description", description)
    putOpt("url", url)
    putOpt("imageUrl", imageUrl)
    putOpt("itemGuid", itemGuid)
    putOpt("publishDate", publishDate)
    put("isRead", isRead)
    putOpt("enclosureUrl", enclosureUrl)
    putOpt("enclosureType", enclosureType)
    putOpt("enclosureLength", enclosureLength)
    putOpt("enclosurePosition", enclosurePosition)
    putOpt("enclosureDurationMs", enclosureDurationMs)
    putOpt("downloadedBytes", downloadedBytes)
    putOpt("downloadedFilePath", downloadedFilePath)
    putOpt("chaptersUrl", chaptersUrl)
    put("autoDownloaded", autoDownloaded)
}

private fun JSONObject.toFeedItem(): FeedItem = FeedItem(
    id = getString("id"),
    feedId = getLong("feedId"),
    title = optStringOrNull("title"),
    description = optStringOrNull("description"),
    url = optStringOrNull("url"),
    imageUrl = optStringOrNull("imageUrl"),
    itemGuid = optStringOrNull("itemGuid"),
    publishDate = optLongOrNull("publishDate"),
    isRead = optBoolean("isRead"),
    enclosureUrl = optStringOrNull("enclosureUrl"),
    enclosureType = optStringOrNull("enclosureType"),
    enclosureLength = optLongOrNull("enclosureLength"),
    enclosurePosition = optDoubleOrNull("enclosurePosition"),
    enclosureDurationMs = optLongOrNull("enclosureDurationMs"),
    downloadedBytes = optLongOrNull("downloadedBytes"),
    downloadedFilePath = optStringOrNull("downloadedFilePath"),
    chaptersUrl = optStringOrNull("chaptersUrl"),
    autoDownloaded = optBoolean("autoDownloaded"),
)

private fun QueueEntry.toJson(): JSONObject = JSONObject().apply {
    put("itemId", itemId)
    put("position", position)
    put("addedAt", addedAt)
    put("autoQueued", autoQueued)
}

private fun JSONObject.toQueueEntry(): QueueEntry = QueueEntry(
    itemId = getString("itemId"),
    position = getInt("position"),
    addedAt = getLong("addedAt"),
    autoQueued = optBoolean("autoQueued"),
)

private fun AppSettings.toJson(): JSONObject = JSONObject().apply {
    put("updateIntervalMinutes", updateIntervalMinutes)
    put("fontSize", fontSize.toDouble())
    put("enableImageDisplay", enableImageDisplay)
    put("maxItemsPerFeed", maxItemsPerFeed)
    put("feedRefreshConcurrency", feedRefreshConcurrency)
    put("defaultToAllItemsView", defaultToAllItemsView)
    put("allowPodcastDownloadOnBattery", allowPodcastDownloadOnBattery)
    // issue #221: JSON keys kept as their pre-rename "Cellular" names (not renamed to match the
    // Kotlin properties) so a backup exported before this change still restores correctly.
    put("allowPodcastDownloadOnCellular", allowPodcastDownloadOnMobileData)
    put("allowPodcastStreaming", allowPodcastStreamingOnMobileData)
    put("autoDeleteFinishedDownloads", autoDeleteFinishedDownloads)
    put("notifyOnNewItems", notifyOnNewItems)
    putOpt("lastImportUrl", lastImportUrl)
    putOpt("lastFeedUpdateEpochMillis", lastFeedUpdateEpochMillis)
    putOpt("lastPlayingFeedId", lastPlayingFeedId)
    putOpt("lastPlayingItemId", lastPlayingItemId)
    put("batteryOptimizationPromptShown", batteryOptimizationPromptShown)
    put("notificationPermissionPromptShown", notificationPermissionPromptShown)
    put("addDefaultFeedsPromptShown", addDefaultFeedsPromptShown)
    putOpt("podcastIndexApiKey", podcastIndexApiKey)
    putOpt("podcastIndexApiSecret", podcastIndexApiSecret)
    put("autoDownloadNewFeedsByDefault", autoDownloadNewFeedsByDefault)
    putOpt("autoDownloadNewFeedsMaxCount", autoDownloadNewFeedsMaxCount)
    put("useDeviceThemeColors", useDeviceThemeColors)
}

private fun JSONObject.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        updateIntervalMinutes = optLongOrNull("updateIntervalMinutes") ?: defaults.updateIntervalMinutes,
        fontSize = optDoubleOrNull("fontSize")?.toFloat() ?: defaults.fontSize,
        enableImageDisplay = if (has("enableImageDisplay")) getBoolean("enableImageDisplay") else defaults.enableImageDisplay,
        maxItemsPerFeed = optIntOrNull("maxItemsPerFeed") ?: defaults.maxItemsPerFeed,
        feedRefreshConcurrency = optIntOrNull("feedRefreshConcurrency") ?: defaults.feedRefreshConcurrency,
        defaultToAllItemsView = if (has("defaultToAllItemsView")) getBoolean("defaultToAllItemsView") else defaults.defaultToAllItemsView,
        allowPodcastDownloadOnBattery = if (has("allowPodcastDownloadOnBattery")) {
            getBoolean("allowPodcastDownloadOnBattery")
        } else {
            defaults.allowPodcastDownloadOnBattery
        },
        allowPodcastDownloadOnMobileData = if (has("allowPodcastDownloadOnCellular")) {
            getBoolean("allowPodcastDownloadOnCellular")
        } else {
            defaults.allowPodcastDownloadOnMobileData
        },
        allowPodcastStreamingOnMobileData = if (has("allowPodcastStreaming")) {
            getBoolean("allowPodcastStreaming")
        } else {
            defaults.allowPodcastStreamingOnMobileData
        },
        autoDeleteFinishedDownloads = if (has("autoDeleteFinishedDownloads")) {
            getBoolean("autoDeleteFinishedDownloads")
        } else {
            defaults.autoDeleteFinishedDownloads
        },
        notifyOnNewItems = if (has("notifyOnNewItems")) getBoolean("notifyOnNewItems") else defaults.notifyOnNewItems,
        lastImportUrl = optStringOrNull("lastImportUrl"),
        lastFeedUpdateEpochMillis = optLongOrNull("lastFeedUpdateEpochMillis"),
        lastPlayingFeedId = optLongOrNull("lastPlayingFeedId"),
        lastPlayingItemId = optStringOrNull("lastPlayingItemId"),
        batteryOptimizationPromptShown = if (has("batteryOptimizationPromptShown")) {
            getBoolean("batteryOptimizationPromptShown")
        } else {
            defaults.batteryOptimizationPromptShown
        },
        notificationPermissionPromptShown = if (has("notificationPermissionPromptShown")) {
            getBoolean("notificationPermissionPromptShown")
        } else {
            defaults.notificationPermissionPromptShown
        },
        addDefaultFeedsPromptShown = if (has("addDefaultFeedsPromptShown")) {
            getBoolean("addDefaultFeedsPromptShown")
        } else {
            defaults.addDefaultFeedsPromptShown
        },
        podcastIndexApiKey = optStringOrNull("podcastIndexApiKey"),
        podcastIndexApiSecret = optStringOrNull("podcastIndexApiSecret"),
        autoDownloadNewFeedsByDefault = if (has("autoDownloadNewFeedsByDefault")) {
            getBoolean("autoDownloadNewFeedsByDefault")
        } else {
            defaults.autoDownloadNewFeedsByDefault
        },
        autoDownloadNewFeedsMaxCount = optIntOrNull("autoDownloadNewFeedsMaxCount"),
        useDeviceThemeColors = if (has("useDeviceThemeColors")) getBoolean("useDeviceThemeColors") else defaults.useDeviceThemeColors,
    )
}
