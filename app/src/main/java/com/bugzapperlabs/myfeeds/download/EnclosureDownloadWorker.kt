package com.bugzapperlabs.myfeeds.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.bugzapperlabs.myfeeds.data.repository.FeedRepository
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
 * [com.bugzapperlabs.myfeeds.data.local.FeedItem.downloadedBytes] periodically rather than every chunk, to
 * avoid hammering the DB; [com.bugzapperlabs.myfeeds.data.local.FeedItem.downloadedFilePath] is only set
 * once the file is fully written, so a killed-mid-download item is never mistaken for complete.
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

                FileOutputStream(file, resumed).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var totalBytes = if (resumed) existingBytes else 0L
                        var lastPersistedBytes = totalBytes
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (isStopped) return@withContext Result.retry()
                            output.write(buffer, 0, read)
                            totalBytes += read
                            if (totalBytes - lastPersistedBytes >= PROGRESS_PERSIST_INTERVAL_BYTES) {
                                feedRepository.setDownloadedBytes(itemId, totalBytes)
                                lastPersistedBytes = totalBytes
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

    companion object {
        const val KEY_ITEM_ID = "itemId"
        const val DOWNLOAD_DIR = "podcast_downloads"
        private const val BUFFER_SIZE = 8 * 1024
        private const val PROGRESS_PERSIST_INTERVAL_BYTES = 256 * 1024L
    }
}
