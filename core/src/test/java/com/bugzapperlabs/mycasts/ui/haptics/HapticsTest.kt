package com.bugzapperlabs.mycasts.ui.haptics

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Test

// issue #292: the API 31-33 fallback table from the issue.
class HapticsTest {
    private val api33 = 33
    private val api34 = 34

    @Test
    fun gestureThreshold_usesThresholdActivateOnApi34AndGestureStartBefore() {
        assertEquals(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE, hapticConstantFor(HapticEvent.GestureThreshold, api34))
        assertEquals(HapticFeedbackConstants.GESTURE_START, hapticConstantFor(HapticEvent.GestureThreshold, api33))
    }

    @Test
    fun toggles_fallBackToConfirmAndReject() {
        assertEquals(HapticFeedbackConstants.TOGGLE_ON, hapticConstantFor(HapticEvent.ToggleOn, api34))
        assertEquals(HapticFeedbackConstants.TOGGLE_OFF, hapticConstantFor(HapticEvent.ToggleOff, api34))
        assertEquals(HapticFeedbackConstants.CONFIRM, hapticConstantFor(HapticEvent.ToggleOn, api33))
        assertEquals(HapticFeedbackConstants.REJECT, hapticConstantFor(HapticEvent.ToggleOff, api33))
    }

    @Test
    fun tick_fallsBackToClockTick() {
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, hapticConstantFor(HapticEvent.Tick, api34))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticConstantFor(HapticEvent.Tick, api33))
    }

    @Test
    fun longPress_isSameOnAllApis() {
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticConstantFor(HapticEvent.LongPress, api33))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticConstantFor(HapticEvent.LongPress, api34))
    }
}
