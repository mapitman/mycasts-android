package com.bugzapperlabs.mycasts.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString

/**
 * Article reader body text (issue #16), styled from the typography scale ported from
 * the old ArticleFontSize setting (see Type.kt).
 */
@Composable
fun ReaderText(text: String, fontScale: Float = 1f, modifier: Modifier = Modifier) {
    ReaderText(AnnotatedString(text), fontScale, modifier)
}

/** Overload for show notes parsed via [AnnotatedString.Companion.fromHtml] (issue #8), so basic
 *  HTML formatting/links survive without a WebView. */
@Composable
fun ReaderText(text: AnnotatedString, fontScale: Float = 1f, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyLarge.let {
        if (fontScale == 1f) it else it.copy(fontSize = it.fontSize * fontScale)
    }
    Text(
        text = text,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}
