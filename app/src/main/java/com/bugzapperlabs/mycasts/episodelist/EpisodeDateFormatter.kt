package com.bugzapperlabs.mycasts.episodelist

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object EpisodeDateFormatter {
    private val FORMATTER = DateTimeFormatter.ofPattern("M/d/yyyy h:mm a")

    fun format(epochMillis: Long?): String {
        if (epochMillis == null) return ""
        return FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }
}
