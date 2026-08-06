package com.bugzapperlabs.myfeeds.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.myfeeds.R
import com.bugzapperlabs.myfeeds.data.opml.OpmlExporter
import com.bugzapperlabs.myfeeds.data.opml.OpmlImporter
import com.bugzapperlabs.myfeeds.data.opml.OpmlParser
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
import com.bugzapperlabs.myfeeds.data.settings.AppSettings
import com.bugzapperlabs.myfeeds.data.settings.FontSize
import com.bugzapperlabs.myfeeds.data.settings.SettingsDataStore
import com.bugzapperlabs.myfeeds.refresh.FeedRefreshScheduling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val feedRepository: FeedRepository,
    private val opmlImporter: OpmlImporter,
    private val opmlExporter: OpmlExporter,
    private val feedRefreshScheduler: FeedRefreshScheduling,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        settingsDataStore.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** One-shot "Add default feeds" result for a Snackbar; cleared via [consumeAddDefaultFeedsMessage]. */
    private val _addDefaultFeedsMessage = MutableStateFlow<String?>(null)
    val addDefaultFeedsMessage: StateFlow<String?> = _addDefaultFeedsMessage

    fun consumeAddDefaultFeedsMessage() {
        _addDefaultFeedsMessage.value = null
    }

    fun setUpdateIntervalMinutes(minutes: Long) {
        viewModelScope.launch {
            settingsDataStore.setUpdateIntervalMinutes(minutes)
            feedRefreshScheduler.schedule(minutes)
        }
    }

    fun setEnableImageDisplay(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setEnableImageDisplay(enabled) }
    }

    fun setDefaultToAllArticleView(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setDefaultToAllArticleView(value) }
    }

    fun setMaxArticles(count: Int) {
        viewModelScope.launch { settingsDataStore.setMaxArticles(count) }
    }

    fun setFeedRefreshConcurrency(count: Int) {
        viewModelScope.launch { settingsDataStore.setFeedRefreshConcurrency(count) }
    }

    fun setArticleFontSize(size: FontSize) {
        viewModelScope.launch { settingsDataStore.setArticleFontSize(size) }
    }

    fun setListFontSize(size: FontSize) {
        viewModelScope.launch { settingsDataStore.setListFontSize(size) }
    }

    fun setFeedListFontSize(size: FontSize) {
        viewModelScope.launch { settingsDataStore.setFeedListFontSize(size) }
    }

    fun setAllowPodcastDownloadOnBattery(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setAllowPodcastDownloadOnBattery(value) }
    }

    fun setAllowPodcastDownloadOnCellular(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setAllowPodcastDownloadOnCellular(value) }
    }

    fun setAllowPodcastStreaming(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setAllowPodcastStreaming(value) }
    }

    fun setAutoDeleteFinishedDownloads(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setAutoDeleteFinishedDownloads(value) }
    }

    fun setNotifyOnNewItems(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setNotifyOnNewItems(value) }
    }

    fun setPodcastIndexApiKey(key: String) {
        viewModelScope.launch { settingsDataStore.setPodcastIndexApiKey(key) }
    }

    fun setPodcastIndexApiSecret(secret: String) {
        viewModelScope.launch { settingsDataStore.setPodcastIndexApiSecret(secret) }
    }

    fun clearPodcasts() {
        viewModelScope.launch { feedRepository.clearAllEnclosurePositions() }
    }

    fun addDefaultFeeds() {
        viewModelScope.launch {
            val document = try {
                context.assets.open("default_feeds.opml").use { OpmlParser.parse(it) }
            } catch (_: Exception) {
                null
            }
            _addDefaultFeedsMessage.value = if (document == null) {
                context.getString(R.string.add_feed_invalid_opml)
            } else {
                val result = opmlImporter.import(document)
                when {
                    result.importedCount > 0 -> context.getString(R.string.add_feed_imported_count, result.importedCount)
                    document.feeds.isEmpty() -> context.getString(R.string.add_feed_no_feeds_found_in_opml)
                    result.invalidCount > 0 -> context.getString(R.string.add_feed_some_feeds_could_not_be_imported)
                    else -> context.getString(R.string.add_feed_all_feeds_already_subscribed)
                }
            }
        }
    }

    fun removeAllFeeds() {
        viewModelScope.launch { feedRepository.removeAllFeeds() }
    }

    fun resetSettings() {
        viewModelScope.launch { settingsDataStore.reset() }
    }

    /** Writes the OPML export to a cache file for the caller to share via [android.content.Intent.ACTION_SEND]. */
    suspend fun exportOpmlToFile(): File {
        val opml = opmlExporter.export()
        val file = File(context.cacheDir, "myfeeds-export.opml")
        file.writeText(opml)
        return file
    }

    /**
     * Writes the OPML export directly to a user-chosen destination (issue #151), e.g. from
     * [androidx.activity.result.contract.ActivityResultContracts.CreateDocument] -- no storage
     * permission needed since the system picker itself grants access to the chosen [uri].
     */
    suspend fun writeOpmlTo(uri: Uri) {
        val opml = opmlExporter.export()
        context.contentResolver.openOutputStream(uri)?.use { it.write(opml.toByteArray()) }
    }
}
