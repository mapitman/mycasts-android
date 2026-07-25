package com.bugzapperlabs.myfeeds.data.feed

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Ported from DateTimeUtils.ParseDate: tries a lenient parse first, then a couple of common
 * feed date variants. The original never throws on failure -- it returns null -- and does a
 * lot of RFC822 military-timezone-letter normalization we don't reproduce here since those are
 * vanishingly rare in real-world feeds. North American zone abbreviations (PST/PDT/etc.) are
 * common though (e.g. the TWiT network's feeds), so those are normalized to numeric offsets
 * before parsing; anything else that doesn't match a standard format is treated as unparseable
 * (item still ingested, just without a publish date).
 */
object FeedDateParser {
    private val FORMATTERS = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ISO_ZONED_DATE_TIME,
        DateTimeFormatter.ISO_INSTANT,
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz"),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz"),
    )

    private val NAMED_ZONE_OFFSETS = mapOf(
        "EST" to "-0500", "EDT" to "-0400",
        "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600",
        "PST" to "-0800", "PDT" to "-0700",
    )
    private val TRAILING_NAMED_ZONE = Regex(""" ([A-Z]{3})$""")

    fun parse(raw: String?): Instant? {
        val value = raw?.trim()
        if (value.isNullOrEmpty()) return null

        parseWithFormatters(value)?.let { return it }
        normalizeNamedZone(value)?.let { normalized -> parseWithFormatters(normalized)?.let { return it } }

        return try {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC)
        } catch (_: Exception) {
            try {
                LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay().toInstant(ZoneOffset.UTC)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseWithFormatters(value: String): Instant? {
        for (formatter in FORMATTERS) {
            try {
                return OffsetDateTime.parse(value, formatter).toInstant()
            } catch (_: Exception) {
                // try the next formatter
            }
        }
        return null
    }

    /** e.g. "Wed, 08 Jul 2026 18:25:18 PDT" -> "Wed, 08 Jul 2026 18:25:18 -0700". */
    private fun normalizeNamedZone(value: String): String? {
        val match = TRAILING_NAMED_ZONE.find(value) ?: return null
        val offset = NAMED_ZONE_OFFSETS[match.groupValues[1]] ?: return null
        return value.substring(0, match.range.first) + " " + offset
    }
}
