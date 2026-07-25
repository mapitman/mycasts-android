package com.bugzapperlabs.myfeeds.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EnclosureFileNamingTest {
    @Test
    fun fileNameFor_isStableForSameUrl() {
        val url = "https://example.com/episode-1.mp3"

        val first = EnclosureFileNaming.fileNameFor(url, "audio/mpeg")
        val second = EnclosureFileNaming.fileNameFor(url, "audio/mpeg")

        assertEquals(first, second)
    }

    @Test
    fun fileNameFor_differsForDifferentUrls() {
        val a = EnclosureFileNaming.fileNameFor("https://example.com/1.mp3", "audio/mpeg")
        val b = EnclosureFileNaming.fileNameFor("https://example.com/2.mp3", "audio/mpeg")

        assertNotEquals(a, b)
    }

    @Test
    fun fileNameFor_usesExtensionFromUrlWhenPresent() {
        val name = EnclosureFileNaming.fileNameFor("https://example.com/episode.mp3", "audio/mpeg")

        assertEquals(true, name.endsWith(".mp3"))
    }

    @Test
    fun fileNameFor_fallsBackToMimeTypeExtensionWhenUrlHasNone() {
        val name = EnclosureFileNaming.fileNameFor("https://example.com/episode", "audio/mpeg")

        assertEquals(true, name.endsWith(".mp3"))
    }

    @Test
    fun fileNameFor_noExtensionAvailable_omitsExtension() {
        val name = EnclosureFileNaming.fileNameFor("https://example.com/episode", null)

        assertEquals(false, name.contains("."))
    }
}
