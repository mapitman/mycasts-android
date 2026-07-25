package com.bugzapperlabs.myfeeds.reader

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/**
 * Strips scripts/styles/forms and any other non-content markup from article HTML before it's
 * loaded into the reader's WebView, per the plan's "render sanitized HTML (Jsoup...)" note.
 */
object HtmlSanitizer {
    private val SAFELIST: Safelist = Safelist.relaxed()
        .addTags("hr")
        .addAttributes("img", "src", "alt")
        .addAttributes("a", "href", "target")

    fun sanitize(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return Jsoup.clean(html, SAFELIST)
    }
}
