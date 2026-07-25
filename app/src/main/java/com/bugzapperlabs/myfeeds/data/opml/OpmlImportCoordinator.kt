package com.bugzapperlabs.myfeeds.data.opml

import android.content.Context
import com.bugzapperlabs.myfeeds.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs [OpmlImporter.import] on its own app-lifetime scope (issue #271) rather than the Add Feed
 * screen's [com.bugzapperlabs.myfeeds.addfeed.AddFeedViewModel] scope. A multi-feed OPML import
 * validates every candidate feed by fetching it (issue #231), which can take long enough that
 * blocking back navigation until it finishes -- the first fix attempted here -- was an
 * unacceptable wait; running it on a scope that outlives the screen means backing out no longer
 * silently truncates the batch partway through.
 */
@Singleton
class OpmlImportCoordinator @Inject constructor(
    private val opmlImporter: OpmlImporter,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _result = MutableStateFlow<String?>(null)

    /** One-shot completed-import message for a Snackbar; cleared via [consumeResult]. */
    val result: StateFlow<String?> = _result

    fun consumeResult() {
        _result.value = null
    }

    fun startImport(document: OpmlDocument) {
        scope.launch {
            val importResult = opmlImporter.import(document)
            _result.value = when {
                importResult.importedCount > 0 ->
                    context.getString(R.string.add_feed_imported_count, importResult.importedCount)
                document.feeds.isEmpty() -> context.getString(R.string.add_feed_no_feeds_found_in_opml)
                importResult.invalidCount > 0 -> context.getString(R.string.add_feed_some_feeds_could_not_be_imported)
                else -> context.getString(R.string.add_feed_all_feeds_already_subscribed)
            }
        }
    }
}
