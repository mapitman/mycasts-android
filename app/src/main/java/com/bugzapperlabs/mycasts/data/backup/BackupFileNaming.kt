package com.bugzapperlabs.mycasts.data.backup

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Suggested filename for a backup export (issue #198): the base name plus today's date in
 * `YYYY-MM-DD` form, so successive exports default to distinct names in the system "Save As"
 * picker instead of the fixed `mycasts-backup.json` every export previously suggested -- which
 * could otherwise be silently overwritten, depending on the destination storage provider's own
 * same-name handling. [date] defaults to today but is a parameter so callers (and tests) don't
 * need to fake the system clock to pin a specific date.
 */
object BackupFileNaming {
    private const val BASE_NAME = "mycasts-backup"

    fun suggestedFileName(date: LocalDate = LocalDate.now()): String =
        "$BASE_NAME-${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}.json"
}
