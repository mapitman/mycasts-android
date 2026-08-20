package com.bugzapperlabs.mycasts.data.backup

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFileNamingTest {
    @Test
    fun suggestedFileName_includesDateInIsoFormat() {
        val name = BackupFileNaming.suggestedFileName(LocalDate.of(2026, 8, 20))

        assertEquals("mycasts-backup-2026-08-20.json", name)
    }

    @Test
    fun suggestedFileName_padsSingleDigitMonthAndDay() {
        val name = BackupFileNaming.suggestedFileName(LocalDate.of(2026, 1, 5))

        assertEquals("mycasts-backup-2026-01-05.json", name)
    }
}
