package com.bugzapperlabs.myfeeds.download

import java.security.MessageDigest

/**
 * Ported rule from MyFeeds.Data DbFeedStore.GetEnclosureLocalName: the local filename is derived
 * from a hash of the enclosure URL (not the item id), so re-downloading the same URL from a
 * different feed item -- or after a feed re-parse assigns a new item id -- reuses the same file
 * instead of downloading it twice. SHA-1+truncate-to-Guid in the original; SHA-256 hex here since
 * there's no GUID-shaped-string constraint on Android.
 */
object EnclosureFileNaming {
    fun fileNameFor(enclosureUrl: String, enclosureType: String?): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(enclosureUrl.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        val extension = extensionFor(enclosureUrl, enclosureType)
        return if (extension != null) "$hex.$extension" else hex
    }

    private fun extensionFor(url: String, mimeType: String?): String? {
        val urlExtension = url.substringAfterLast('/', "").substringAfterLast('.', "").takeIf {
            it.isNotBlank() && it.length <= 5 && it.all(Char::isLetterOrDigit)
        }
        if (urlExtension != null) return urlExtension

        return when (mimeType?.substringBefore(';')?.trim()) {
            "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            "video/mp4" -> "mp4"
            else -> null
        }
    }
}
