package com.bugzapperlabs.myfeeds.data.settings

/**
 * Ordinal matches the raw int values the WinPhone app stored for ListFontSize/FeedListFontSize
 * (0/1/2), and the "30pt"/"35pt"/"40pt" strings it stored for ArticleFontSize.
 */
enum class FontSize {
    SMALL,
    NORMAL,
    LARGE,
}

/**
 * Multiplier applied to a base text/font size to realize the in-app font-size setting
 * (issue #27): NORMAL is unscaled, SMALL/LARGE nudge the size down/up proportionally.
 */
val FontSize.scaleFactor: Float
    get() = when (this) {
        FontSize.SMALL -> 0.85f
        FontSize.NORMAL -> 1.0f
        FontSize.LARGE -> 1.15f
    }
