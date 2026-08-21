package com.bugzapperlabs.mycasts.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.data.backup.AppBackupRepository
import com.bugzapperlabs.mycasts.data.opml.ImportProgress
import com.bugzapperlabs.mycasts.data.opml.OpmlExporter
import com.bugzapperlabs.mycasts.data.opml.OpmlImportCoordinator
import com.bugzapperlabs.mycasts.data.opml.OpmlParser
import com.bugzapperlabs.mycasts.data.settings.AppSettings
import com.bugzapperlabs.mycasts.data.settings.SettingsDataStore
import com.bugzapperlabs.mycasts.refresh.FeedRefreshScheduling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val opmlImportCoordinator: OpmlImportCoordinator,
    private val opmlExporter: OpmlExporter,
    private val appBackupRepository: AppBackupRepository,
    private val feedRefreshScheduler: FeedRefreshScheduling,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        settingsDataStore.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Live completed/total counts while "Add default feeds" runs (issue #105). */
    val addDefaultFeedsProgress: StateFlow<ImportProgress?> = opmlImportCoordinator.progress

    // addDefaultFeeds() runs on OpmlImportCoordinator's app-lifetime scope (issue #105) so
    // navigating away from Settings mid-import no longer cancels it, the same reason
    // AddFeedViewModel's own OPML import already went through the coordinator (issue #271) --
    // merged with this ViewModel's own local message rather than exposing the coordinator's result
    // directly, since this screen must also report a local OPML-parse failure below. WhileSubscribed
    // (matching [settings] above) rather than a plain always-collecting coroutine, so nothing is
    // left running once nobody's observing it.
    private val _localMessage = MutableStateFlow<String?>(null)

    /** One-shot "Add default feeds" result for a Snackbar; cleared via [consumeAddDefaultFeedsMessage]. */
    val addDefaultFeedsMessage: StateFlow<String?> =
        merge(_localMessage, opmlImportCoordinator.result).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun consumeAddDefaultFeedsMessage() {
        _localMessage.value = null
        opmlImportCoordinator.consumeResult()
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

    fun setDefaultToAllItemsView(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setDefaultToAllItemsView(value) }
    }

    fun setMaxItemsPerFeed(count: Int) {
        viewModelScope.launch { settingsDataStore.setMaxItemsPerFeed(count) }
    }

    fun setFeedRefreshConcurrency(count: Int) {
        viewModelScope.launch { settingsDataStore.setFeedRefreshConcurrency(count) }
    }

    fun setFontSize(scale: Float) {
        viewModelScope.launch { settingsDataStore.setFontSize(scale) }
    }

    fun setAllowPodcastDownloadOnBattery(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setAllowPodcastDownloadOnBattery(value) }
    }

    fun setAllowPodcastDownloadOnMobileData(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setAllowPodcastDownloadOnMobileData(value) }
    }

    fun setAlwaysAllowPodcastStreamingOnMobileData(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setAlwaysAllowPodcastStreamingOnMobileData(value) }
    }

    fun setAutoDeleteFinishedDownloads(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setAutoDeleteFinishedDownloads(value) }
    }

    fun setUseDeviceThemeColors(value: Boolean) {
        viewModelScope.launch { settingsDataStore.setUseDeviceThemeColors(value) }
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

    fun addDefaultFeeds() {
        viewModelScope.launch {
            val document = try {
                context.assets.open("default_feeds.opml").use { OpmlParser.parse(it) }
            } catch (_: Exception) {
                null
            }
            if (document == null) {
                _localMessage.value = context.getString(R.string.add_feed_invalid_opml)
            } else {
                opmlImportCoordinator.startImport(document)
            }
        }
    }

    fun resetSettings() {
        viewModelScope.launch { settingsDataStore.reset() }
    }

    /** Raw OPML XML text for the caller to copy to the clipboard (issue #92). */
    suspend fun exportOpmlText(): String = opmlExporter.export()

    /** Writes the OPML export to a cache file for the caller to share via [android.content.Intent.ACTION_SEND]. */
    suspend fun exportOpmlToFile(): File {
        val opml = opmlExporter.export()
        val file = File(context.cacheDir, "mycasts-export.opml")
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

    private val _backupMessage = MutableStateFlow<String?>(null)

    /** One-shot export/import result for a Snackbar (issue #157); cleared via [consumeBackupMessage]. */
    val backupMessage: StateFlow<String?> = _backupMessage

    fun consumeBackupMessage() {
        _backupMessage.value = null
    }

    /**
     * Writes a full local-state backup (issue #157) directly to a user-chosen destination, e.g.
     * from [androidx.activity.result.contract.ActivityResultContracts.CreateDocument] -- mirrors
     * [writeOpmlTo], no storage permission needed since the system picker itself grants access to
     * the chosen [uri].
     */
    fun writeBackupTo(uri: Uri) {
        viewModelScope.launch {
            val json = appBackupRepository.export()
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            _backupMessage.value = context.getString(R.string.settings_backup_exported)
        }
    }

    /**
     * Restores a full local-state backup (issue #157) from a user-chosen file, e.g. from
     * [androidx.activity.result.contract.ActivityResultContracts.OpenDocument] -- replaces every
     * feed/item/queue entry and every setting wholesale, so the caller should confirm with the
     * user before calling this.
     */
    fun restoreBackupFrom(uri: Uri) {
        viewModelScope.launch {
            val json = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            } catch (_: Exception) {
                null
            }
            if (json == null) {
                _backupMessage.value = context.getString(R.string.settings_backup_restore_failed)
                return@launch
            }
            try {
                appBackupRepository.import(json)
                _backupMessage.value = context.getString(R.string.settings_backup_restored)
            } catch (_: JSONException) {
                _backupMessage.value = context.getString(R.string.settings_backup_restore_failed)
            }
        }
    }
}
