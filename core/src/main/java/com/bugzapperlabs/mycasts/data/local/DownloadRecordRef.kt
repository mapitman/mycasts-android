package com.bugzapperlabs.mycasts.data.local

/** Just the download columns of a [FeedItem] (issue #316), so a reconciliation pass does not load
 *  every row's description. */
data class DownloadRecordRef(val id: String, val downloadedFilePath: String?, val downloadedBytes: Long?)
