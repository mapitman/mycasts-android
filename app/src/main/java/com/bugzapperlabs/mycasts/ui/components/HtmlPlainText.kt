package com.bugzapperlabs.mycasts.ui.components

import org.jsoup.Jsoup

/** Strips HTML tags down to plain text (e.g. RSS/Atom `description`/`content:encoded` bodies) for
 *  display in a plain [androidx.compose.material3.Text] rather than rendering markup. */
fun htmlToPlainText(html: String): String = if (html.isBlank()) "" else Jsoup.parse(html).text()
