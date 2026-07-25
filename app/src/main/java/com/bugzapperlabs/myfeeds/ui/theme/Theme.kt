package com.bugzapperlabs.myfeeds.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = MyFeedsGreenDark,
    onPrimary = OnMyFeedsGreenDark,
    primaryContainer = MyFeedsGreenContainerDark,
    onPrimaryContainer = OnMyFeedsGreenContainerDark,
    tertiary = MyFeedsOrangeDark,
    onTertiary = OnMyFeedsOrangeDark,
    tertiaryContainer = MyFeedsOrangeContainerDark,
    onTertiaryContainer = OnMyFeedsOrangeContainerDark,
)

private val LightColorScheme = lightColorScheme(
    primary = MyFeedsGreenLight,
    onPrimary = OnMyFeedsGreenLight,
    primaryContainer = MyFeedsGreenContainerLight,
    onPrimaryContainer = OnMyFeedsGreenContainerLight,
    tertiary = MyFeedsOrangeLight,
    onTertiary = OnMyFeedsOrangeLight,
    tertiaryContainer = MyFeedsOrangeContainerLight,
    onTertiaryContainer = OnMyFeedsOrangeContainerLight,
)

/**
 * @param dynamicColor Opt-in to Android 12+ wallpaper-derived color. Off by default so the
 * ported MyFeeds brand (green accent, orange tertiary) always shows.
 */
@Composable
fun MyFeedsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
