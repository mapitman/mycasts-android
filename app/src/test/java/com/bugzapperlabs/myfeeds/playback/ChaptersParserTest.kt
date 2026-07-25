package com.bugzapperlabs.myfeeds.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Config pins Robolectric to API 35 -- Robolectric 4.14 doesn't support compileSdk 36 yet.
 *  Robolectric (rather than a plain JVM test) is required here: org.json.JSONObject on the
 *  Android SDK stub jar throws on real use outside an Android runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChaptersParserTest {
    @Test
    fun parse_mapsStartTimeSecondsToMillisAndTitle() {
        val json = """
            {"chapters": [
              {"startTime": 0, "title": "Intro"},
              {"startTime": 90.5, "title": "Main Topic"}
            ]}
        """.trimIndent()

        val chapters = ChaptersParser.parse(json)

        assertEquals(listOf(Chapter(0L, "Intro"), Chapter(90_500L, "Main Topic")), chapters)
    }

    @Test
    fun parse_excludesChaptersWithTocFalse() {
        // issue #95: value-for-value boost/tip markers some hosts inject aren't meant to appear
        // in a visible table of contents/navigation, per the Podcasting 2.0 spec.
        val json = """
            {"chapters": [
              {"startTime": 0, "title": "Intro"},
              {"startTime": 5, "title": "1000 sats from listener", "toc": false},
              {"startTime": 120, "title": "Main Topic"}
            ]}
        """.trimIndent()

        val chapters = ChaptersParser.parse(json)

        assertEquals(listOf("Intro", "Main Topic"), chapters.map { it.title })
    }

    @Test
    fun parse_sortsByStartTime() {
        val json = """{"chapters": [{"startTime": 100}, {"startTime": 0}, {"startTime": 50}]}"""

        val chapters = ChaptersParser.parse(json)

        assertEquals(listOf(0L, 50_000L, 100_000L), chapters.map { it.startTimeMs })
    }

    @Test
    fun parse_missingChaptersKey_returnsEmpty() {
        assertTrue(ChaptersParser.parse("""{"version": "1.2.0"}""").isEmpty())
    }

    @Test
    fun parse_malformedJson_returnsEmptyRatherThanThrowing() {
        assertTrue(ChaptersParser.parse("not json").isEmpty())
    }

    @Test
    fun parse_chapterMissingStartTime_isSkipped() {
        val json = """{"chapters": [{"title": "No start time"}, {"startTime": 10, "title": "Has one"}]}"""

        val chapters = ChaptersParser.parse(json)

        assertEquals(listOf("Has one"), chapters.map { it.title })
    }
}
