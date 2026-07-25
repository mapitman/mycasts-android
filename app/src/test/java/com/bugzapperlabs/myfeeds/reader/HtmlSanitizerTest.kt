package com.bugzapperlabs.myfeeds.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlSanitizerTest {
    @Test
    fun sanitize_stripsScriptTags() {
        val result = HtmlSanitizer.sanitize("<p>Hello</p><script>alert('x')</script>")

        assertTrue(result.contains("Hello"))
        assertFalse(result.contains("script"))
        assertFalse(result.contains("alert"))
    }

    @Test
    fun sanitize_keepsBasicFormattingAndImages() {
        val result = HtmlSanitizer.sanitize("<p><b>Bold</b> text with <img src=\"https://example.com/x.jpg\"></p>")

        assertTrue(result.contains("<b>Bold</b>"))
        assertTrue(result.contains("img"))
        assertTrue(result.contains("https://example.com/x.jpg"))
    }

    @Test
    fun sanitize_keepsLinks() {
        val result = HtmlSanitizer.sanitize("<a href=\"https://example.com\">link</a>")

        assertTrue(result.contains("href=\"https://example.com\""))
    }

    @Test
    fun sanitize_blankOrNull_returnsEmptyString() {
        assertEquals("", HtmlSanitizer.sanitize(null))
        assertEquals("", HtmlSanitizer.sanitize(""))
        assertEquals("", HtmlSanitizer.sanitize("   "))
    }
}
