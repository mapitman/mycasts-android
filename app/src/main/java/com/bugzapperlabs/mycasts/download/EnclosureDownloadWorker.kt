package com.bugzapperlabs.mycasts.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.bugzapperlabs.mycasts.MyCastsApp
import com.bugzapperlabs.mycasts.R
import com.bugzapperlabs.mycasts.data.local.FeedItem
import com.bugzapperlabs.mycasts.data.repository.FeedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Downloads a single episode's enclosure to app-internal storage (issue #23), resuming a partial
 * file via an HTTP Range request when the server supports it (falls back to a full re-download
 * otherwise, mirroring ordinary HTTP semantics rather than the original's OS-managed
 * BackgroundTransferRequest, which has no direct Android equivalent). Progress is persisted to
 * [com.bugzapperlabs.mycasts.data.local.FeedItem.downloadedBytes] periodically rather than every chunk, to
 * avoid hammering the DB; [com.bugzapperlabs.mycasts.data.local.FeedItem.downloadedFilePath] is only set
 * once the file is fully written, so a killed-mid-download item is never mistaken for complete.
 *
 * Runs as a foreground worker (issue #15) with a silent progress notification -- expected/ongoing
 * activity, not something worth alerting over, but visible so the user knows a download is
 * actually happening rather than silently stalled. WorkManager dismisses the notification
 * automatically once [doWork] returns, so no explicit cleanup is needed on success/failure.
 */
@HiltWorker
class EnclosureDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val feedRepository: FeedRepository,
    private val httpClient: OkHttpClient,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return@withContext Result.failure()
        val item = feedRepository.getItem(itemId) ?: return@withContext Result.failure()
        val url = item.enclosureUrl ?: return@withContext Result.failure()

        setForeground(createForegroundInfo(item, downloadedBytes = 0L, totalBytes = null))

        val downloadDir = File(applicationContext.filesDir, DOWNLOAD_DIR).apply { mkdirs() }
        val file = File(downloadDir, EnclosureFileNaming.fileNameFor(url, item.enclosureType))
        val existingBytes = if (file.exists()) file.length() else 0L

        val request = Request.Builder().url(url).apply {
            if (existingBytes > 0) header("Range", "bytes=$existingBytes-")
        }.build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.retry()
                val body = response.body ?: return@withContext Result.retry()
                val resumed = response.code == 206
                val contentLength = body.contentLength()
                val totalBytes = contentLength.takeIf { it > 0 }
                    ?.let { if (resumed) existingBytes + it else it }

                FileOutputStream(file, resumed).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloadedBytes = if (resumed) existingBytes else 0L
                        var lastPersistedBytes = downloadedBytes
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (isStopped) return@withContext Result.retry()
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            if (downloadedBytes - lastPersistedBytes >= PROGRESS_PERSIST_INTERVAL_BYTES) {
                                feedRepository.setDownloadedBytes(itemId, downloadedBytes)
                                lastPersistedBytes = downloadedBytes
                                setForeground(createForegroundInfo(item, downloadedBytes, totalBytes))
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            return@withContext Result.retry()
        }

        feedRepository.setDownloadedFilePath(itemId, file.absolutePath)
        Result.success()
    }

    private fun createForegroundInfo(item: FeedItem, downloadedBytes: Long, totalBytes: Long?): ForegroundInfo {
        val downloadedMb = downloadedBytes / BYTES_PER_MB
        val totalMb = totalBytes?.let { it / BYTES_PER_MB }
        val text = if (totalMb != null) {
            applicationContext.getString(R.string.notification_download_progress_with_total, downloadedMb, totalMb)
        } else {
            applicationContext.getString(R.string.notification_download_progress_no_total, downloadedMb)
        }
        val notification = NotificationCompat.Builder(applicationContext, MyCastsApp.DOWNLOADS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item.title?.ifBlank { null } ?: applicationContext.getString(R.string.notification_download_title_fallback))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (totalBytes != null && totalBytes > 0) {
                    setProgress(PROGRESS_MAX, ((downloadedBytes * PROGRESS_MAX) / totalBytes).toInt().coerceIn(0, PROGRESS_MAX), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()
        return ForegroundInfo(item.id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_ITEM_ID = "itemId"
        const val DOWNLOAD_DIR = "podcast_downloads"
        private const val BUFFER_SIZE = 8 * 1024
        private const val PROGRESS_PERSIST_INTERVAL_BYTES = 256 * 1024L
        private const val BYTES_PER_MB = 1024f * 1024f
        private const val PROGRESS_MAX = 100
    }
}
