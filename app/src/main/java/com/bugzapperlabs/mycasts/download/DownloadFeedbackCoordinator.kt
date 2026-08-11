package com.bugzapperlabs.mycasts.download

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val DOWNLOAD_START_TIMEOUT_MS = 8_000L

/**
 * Tracks a single-episode download from tap to confirmed start (issue #84), on an app-lifetime
 * scope rather than whichever screen's ViewModel triggered it -- the episode details screen can
 * be navigated away from well before a download actually starts making progress (WorkManager
 * scheduling delay, network/battery constraints not yet met, etc.), and until now the download
 * button gave no feedback at all in that gap, reading as "the button did nothing" -- the actual
 * bug report this fixes, not a case of downloads silently failing outright.
 *
 * [pendingItemIds] drives an immediate spinner on the download button the instant it's tapped,
 * before there's any real progress ([FeedItem.downloadedBytes]) to react to. If real progress
 * hasn't appeared within [DOWNLOAD_START_TIMEOUT_MS], [result] surfaces a one-shot message,
 * wherever the user is by then -- consumed via [consumeResult].
 */
@Singleton
class DownloadFeedbackCoordinator @Inject constructor(
    private val downloadRepository: EnclosureDownloadRepository,
    private val feedRepository: FeedRepository,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Overridable so tests can use a short timeout instead of waiting out the real one. */
    @VisibleForTesting
    internal var downloadStartTimeoutMs: Long = DOWNLOAD_START_TIMEOUT_MS

    private val _pendingItemIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingItemIds: StateFlow<Set<String>> = _pendingItemIds

    private val _result = MutableStateFlow<String?>(null)

    /** One-shot "download didn't start in time" message for a Snackbar; cleared via [consumeResult]. */
    val result: StateFlow<String?> = _result

    fun consumeResult() {
        _result.value = null
    }

    fun startDownload(item: FeedItem) {
        _pendingItemIds.update { it + item.id }
        scope.launch {
            downloadRepository.startDownload(item)
            // Real progress or outright completion both count as "started" -- a fast/small
            // enclosure can finish downloading before this timeout would otherwise fire.
            val started = withTimeoutOrNull(downloadStartTimeoutMs) {
                feedRepository.observeDownloadedItems().first { downloaded -> downloaded.any { it.item.id == item.id } }
                true
            } ?: false
            _pendingItemIds.update { it - item.id }
            if (!started) {
                _result.value = context.getString(R.string.download_feedback_not_started, item.title.orEmpty())
            }
        }
    }

    /**
     * Test-only teardown: cancels [scope] and waits until every coroutine launched into it has
     * actually finished, so nothing is left mid-flight against the test's own database once it
     * closes (mirrors [com.bugzapperlabs.mycasts.playback.PlaybackController.awaitShutdownForTest]).
     */
    @VisibleForTesting
    internal suspend fun cancelForTest() {
        scope.coroutineContext.job.cancelAndJoin()
    }
}
