package com.bugzapperlabs.myfeeds.articlelist

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ArticleDateFormatterTest {
    @Test
    fun format_epochMillis_formatsInSystemDefaultZone() {
        val zoned = ZonedDateTime.of(2013, 6, 3, 11, 5, 30, 0, ZoneId.systemDefault())

        val result = ArticleDateFormatter.format(zoned.toInstant().toEpochMilli())

        assertEquals("6/3/2013 11:05 AM", result)
    }

    @Test
    fun format_null_returnsEmptyString() {
        assertEquals("", ArticleDateFormatter.format(null))
    }
}
