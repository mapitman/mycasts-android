package com.bugzapperlabs.myfeeds.data.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class FeedDateParserTest {
    @Test
    fun parse_rfc822WithGmt() {
        assertEquals(Instant.parse("2013-06-03T11:05:30Z"), FeedDateParser.parse("Mon, 03 Jun 2013 11:05:30 GMT"))
    }

    @Test
    fun parse_rfc822WithNumericOffset() {
        assertEquals(Instant.parse("2013-06-03T11:05:30Z"), FeedDateParser.parse("Mon, 03 Jun 2013 07:05:30 -0400"))
    }

    @Test
    fun parse_iso8601WithZ() {
        assertEquals(Instant.parse("2013-06-03T11:05:30Z"), FeedDateParser.parse("2013-06-03T11:05:30Z"))
    }

    @Test
    fun parse_blankOrNull_returnsNull() {
        assertNull(FeedDateParser.parse(null))
        assertNull(FeedDateParser.parse(""))
        assertNull(FeedDateParser.parse("   "))
    }

    @Test
    fun parse_garbage_returnsNullInsteadOfThrowing() {
        assertNull(FeedDateParser.parse("not a date at all"))
    }

    @Test
    fun parse_namedUsTimezoneAbbreviations() {
        assertEquals(
            Instant.parse("2026-07-09T01:25:18Z"),
            FeedDateParser.parse("Wed, 08 Jul 2026 18:25:18 PDT"),
        )
        assertEquals(
            Instant.parse("2013-06-03T15:05:30Z"),
            FeedDateParser.parse("Mon, 03 Jun 2013 11:05:30 EDT"),
        )
    }
}
