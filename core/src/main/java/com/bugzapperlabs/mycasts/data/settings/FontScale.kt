package com.bugzapperlabs.mycasts.data.settings

/**
 * Multiplier applied to a base text/font size to realize the in-app font-size setting (issue
 * #27), replacing the old three-step SMALL/NORMAL/LARGE enum with a slider-driven scale (issue
 * #125) so it can move in finer increments than the old fixed 0.85/1.0/1.15. The Settings slider
 * still snaps to discrete [FONT_SCALE_STEP] increments rather than dragging fully continuously,
 * chosen so the 100%/125%/200% marks land exactly on a stop instead of requiring a pixel-precise
 * drag to hit them.
 */
const val FONT_SCALE_MIN = 0.7f
const val FONT_SCALE_MAX = 2.0f
const val FONT_SCALE_STEP = 0.05f
const val FONT_SCALE_DEFAULT = 1.0f
