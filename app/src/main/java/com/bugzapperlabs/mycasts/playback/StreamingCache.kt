package com.bugzapperlabs.mycasts.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** Disk budget for [StreamingCache]'s [SimpleCache] (issue #230) -- a fixed cap rather than a
 *  user-facing setting, evicted least-recently-used first once exceeded. Separate from downloaded
 *  episodes' own storage (`filesDir`, see `EnclosureDownloadWorker`), which has no such cap of its
 *  own beyond each feed's `Feed.maxDownloadsToKeep`. */
private const val STREAMING_CACHE_MAX_BYTES = 200L * 1024 * 1024

/**
 * Lazily-built, process-wide singleton [SimpleCache] for streamed (undownloaded) episode
 * playback (issue #230), so a later seek-backward or replay reads already-fetched bytes from
 * disk instead of re-streaming them over the network.
 *
 * A plain lazily-initialized holder rather than a Hilt-injected `@Singleton` -- [SimpleCache] is
 * annotated `@UnstableApi`, and exposing it as a Hilt `@Inject` field on [PlaybackService]
 * triggers `UnsafeOptInUsageError` from Hilt's generated member-injector code, which has no way
 * to carry the opt-in through. Keeping the type entirely inside code this app itself writes (and
 * so can annotate) avoids that, at the cost of the usual double-checked-locking boilerplate Hilt
 * would otherwise handle.
 */
@UnstableApi
object StreamingCache {
    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache =
        cache ?: synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, "media3_stream_cache"),
                LeastRecentlyUsedCacheEvictor(STREAMING_CACHE_MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
}
