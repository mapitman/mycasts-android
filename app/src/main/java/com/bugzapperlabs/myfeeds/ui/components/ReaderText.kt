package com.bugzapperlabs.myfeeds.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Article reader body text (issue #16), styled from the typography scale ported from
 * the old ArticleFontSize setting (see Type.kt).
 */
@Composable
fun ReaderText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}
