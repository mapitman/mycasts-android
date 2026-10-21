package com.bugzapperlabs.mycasts.data.feed

import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream

// Size caps for untrusted input (issue #300). Far above any real feed/OPML/chapters file, but
// small enough that a hostile or broken server can't make the app buffer unbounded data.
const val MAX_FEED_BYTES = 10L * 1024 * 1024
const val MAX_OPML_BYTES = 5L * 1024 * 1024
const val MAX_JSON_BYTES = 2L * 1024 * 1024

/** An entity declaration is the building block of entity-expansion attacks ("billion laughs"). A
 *  plain DOCTYPE (still used by some older RSS feeds) is harmless without one. */
internal fun String.declaresXmlEntities(): Boolean = contains("<!ENTITY", ignoreCase = true)

/** Resolves every external entity or DTD to nothing, so parsing never fetches a URL named by the
 *  document (issue #300). */
object NoExternalEntitiesResolver : org.xml.sax.EntityResolver {
    override fun resolveEntity(publicId: String?, systemId: String?): org.xml.sax.InputSource =
        org.xml.sax.InputSource(java.io.StringReader(""))
}

class InputTooLargeException(limit: Long) : IOException("Input is larger than $limit bytes")

/** Reads at most [maxBytes] of this body; throws [InputTooLargeException] if there is more. */
fun ResponseBody.stringLimited(maxBytes: Long): String {
    if (contentLength() > maxBytes) throw InputTooLargeException(maxBytes)
    return byteStream().readBytesLimited(maxBytes).toString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
}

/** Reads at most [maxBytes]; throws [InputTooLargeException] if there is more. */
fun InputStream.readBytesLimited(maxBytes: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) throw InputTooLargeException(maxBytes)
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
