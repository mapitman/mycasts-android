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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
            // Races real progress/completion against an outright permanent failure (issue #209)
            // -- a fast/small enclosure can finish downloading before this timeout would otherwise
            // fire, and a low-storage failure in particular resolves almost immediately, well
            // before the timeout, so there's no reason to make the user wait it out just to see a
            // generic "didn't start" instead of the specific reason.
            val outcome = withTimeoutOrNull(downloadStartTimeoutMs) {
                merge(
                    feedRepository.observeDownloadedItems()
                        .filter { downloaded -> downloaded.any { it.item.id == item.id } }
                        .map { DownloadOutcome.Started },
                    downloadRepository.observeFailureReason(item.id)
                        .filterNotNull()
                        .map { DownloadOutcome.Failed(it) },
                ).first()
            }
            _pendingItemIds.update { it - item.id }
            _result.value = when (outcome) {
                is DownloadOutcome.Failed -> failureMessage(item, outcome.reason)
                DownloadOutcome.Started -> null
                null -> context.getString(R.string.download_feedback_not_started, item.title.orEmpty())
            }
        }
    }

    private fun failureMessage(item: FeedItem, reason: String): String = when (reason) {
        EnclosureDownloadWorker.FAILURE_REASON_LOW_STORAGE ->
            context.getString(R.string.download_feedback_failed_low_storage, item.title.orEmpty())
        else -> context.getString(R.string.download_feedback_not_started, item.title.orEmpty())
    }

    private sealed interface DownloadOutcome {
        data object Started : DownloadOutcome
        data class Failed(val reason: String) : DownloadOutcome
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
