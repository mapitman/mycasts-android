package com.bugzapperlabs.mycasts.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlPlainTextTest {
    @Test
    fun htmlToPlainText_stripsTags() {
        // issue #167: some feeds embed raw HTML in their description/subtitle.
        assertEquals("Line one Line two", htmlToPlainText("Line one<br>Line two"))
        assertEquals("emphasis", htmlToPlainText("<em>emphasis</em>"))
    }

    @Test
    fun htmlToPlainText_plainTextUnaffected() {
        assertEquals("Just plain text", htmlToPlainText("Just plain text"))
    }

    @Test
    fun htmlToPlainText_blank_returnsEmpty() {
        assertEquals("", htmlToPlainText(""))
        assertEquals("", htmlToPlainText("   "))
    }
}
