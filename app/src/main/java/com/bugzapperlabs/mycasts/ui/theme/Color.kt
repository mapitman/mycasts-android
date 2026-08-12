package com.bugzapperlabs.mycasts.ui.theme

import androidx.compose.ui.graphics.Color

// Brand green sampled from the WinPhone app's in-UI accent (unread counts, feed
// descriptions) — see Marketing/1 - English Feed List.png. The app icon itself is
// orange (#FF7D00); we carry that over as the tertiary accent for podcast/enclosure UI.
val MyCastsGreenLight = Color(0xFF2E7D32)
val OnMyCastsGreenLight = Color(0xFFFFFFFF)
val MyCastsGreenContainerLight = Color(0xFFA5D6A7)
val OnMyCastsGreenContainerLight = Color(0xFF002106)

val MyCastsGreenDark = Color(0xFF8BD98F)
val OnMyCastsGreenDark = Color(0xFF00390B)
val MyCastsGreenContainerDark = Color(0xFF1B5E20)
val OnMyCastsGreenContainerDark = Color(0xFFA5D6A7)

val MyCastsOrangeLight = Color(0xFF8B5000)
val OnMyCastsOrangeLight = Color(0xFFFFFFFF)
val MyCastsOrangeContainerLight = Color(0xFFFFDCC1)
val OnMyCastsOrangeContainerLight = Color(0xFF2C1600)

val MyCastsOrangeDark = Color(0xFFFFB77C)
val OnMyCastsOrangeDark = Color(0xFF4A2800)
val MyCastsOrangeContainerDark = Color(0xFF693C00)
val OnMyCastsOrangeContainerDark = Color(0xFFFFDCC1)

// Muted sage-green secondary (issue #95) -- a desaturated companion to the brand green primary,
// giving the fixed/fallback color scheme (used whenever device theme colors are off or
// unavailable) a third distinct accent role instead of leaving `secondary` at Material 3's
// generic baseline.
val MyCastsSecondaryLight = Color(0xFF54634F)
val OnMyCastsSecondaryLight = Color(0xFFFFFFFF)
val MyCastsSecondaryContainerLight = Color(0xFFD7E8CF)
val OnMyCastsSecondaryContainerLight = Color(0xFF121F0F)

val MyCastsSecondaryDark = Color(0xFFBBCCB3)
val OnMyCastsSecondaryDark = Color(0xFF263422)
val MyCastsSecondaryContainerDark = Color(0xFF3C4B37)
val OnMyCastsSecondaryContainerDark = Color(0xFFD7E8CF)
