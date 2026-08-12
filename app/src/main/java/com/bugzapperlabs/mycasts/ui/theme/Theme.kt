package com.bugzapperlabs.mycasts.ui.theme

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
    primary = MyCastsGreenDark,
    onPrimary = OnMyCastsGreenDark,
    primaryContainer = MyCastsGreenContainerDark,
    onPrimaryContainer = OnMyCastsGreenContainerDark,
    secondary = MyCastsSecondaryDark,
    onSecondary = OnMyCastsSecondaryDark,
    secondaryContainer = MyCastsSecondaryContainerDark,
    onSecondaryContainer = OnMyCastsSecondaryContainerDark,
    tertiary = MyCastsOrangeDark,
    onTertiary = OnMyCastsOrangeDark,
    tertiaryContainer = MyCastsOrangeContainerDark,
    onTertiaryContainer = OnMyCastsOrangeContainerDark,
)

private val LightColorScheme = lightColorScheme(
    primary = MyCastsGreenLight,
    onPrimary = OnMyCastsGreenLight,
    primaryContainer = MyCastsGreenContainerLight,
    onPrimaryContainer = OnMyCastsGreenContainerLight,
    secondary = MyCastsSecondaryLight,
    onSecondary = OnMyCastsSecondaryLight,
    secondaryContainer = MyCastsSecondaryContainerLight,
    onSecondaryContainer = OnMyCastsSecondaryContainerLight,
    tertiary = MyCastsOrangeLight,
    onTertiary = OnMyCastsOrangeLight,
    tertiaryContainer = MyCastsOrangeContainerLight,
    onTertiaryContainer = OnMyCastsOrangeContainerLight,
)

/**
 * @param dynamicColor Opt-in to Android 12+ wallpaper-derived color, defaulting to off here so
 * callers that don't care (previews, tests) get the fixed MyCasts brand scheme. The app's own
 * entry point (`MainActivity`) explicitly passes the user's "Use device theme colors" setting
 * (issue #95, on by default there) rather than relying on this default.
 */
@Composable
fun MyCastsTheme(
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
