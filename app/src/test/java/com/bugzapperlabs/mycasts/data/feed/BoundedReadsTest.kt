package com.bugzapperlabs.mycasts.data.feed

import com.bugzapperlabs.mycasts.data.opml.OpmlParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** issue #300: untrusted input is size-capped, and entity declarations are refused. */
class BoundedReadsTest {
    private val billionLaughs = """<?xml version="1.0"?>
        <!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;">]>
        <rss version="2.0"><channel><title>&lol2;</title></channel></rss>"""

    @Test
    fun stringLimited_underLimit_returnsBody() {
        val body = "hello".toResponseBody("text/plain".toMediaType())

        assertEquals("hello", body.stringLimited(10))
    }

    @Test
    fun stringLimited_overLimit_throws() {
        val body = "hello world".toResponseBody("text/plain".toMediaType())

        assertThrows(InputTooLargeException::class.java) { body.stringLimited(5) }
    }

    @Test
    fun readBytesLimited_overLimitWithoutKnownLength_throws() {
        val stream = object : java.io.InputStream() {
            override fun read(): Int = 'x'.code
        }

        assertThrows(IOException::class.java) { stream.readBytesLimited(1024) }
    }

    @Test
    fun feedParser_entityDeclaration_isRejected() {
        assertNull(FeedParser.parse(billionLaughs))
    }

    @Test
    fun feedParser_plainDoctype_stillParses() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE rss PUBLIC "-//Netscape Communications//DTD RSS 0.91//EN" "http://my.netscape.com/publish/formats/rss-0.91.dtd">
            <rss version="2.0"><channel><title>Old Feed</title></channel></rss>"""

        assertNotNull(FeedParser.parse(xml))
    }

    @Test
    fun opmlParser_entityDeclaration_throws() {
        assertThrows(IOException::class.java) { OpmlParser.parse(billionLaughs.byteInputStream()) }
    }
}
