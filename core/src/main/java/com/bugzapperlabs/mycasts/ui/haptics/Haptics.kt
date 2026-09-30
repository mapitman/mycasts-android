package com.bugzapperlabs.mycasts.ui.haptics

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/** App-level haptic events (issue #292); [Haptics] maps each to a platform effect. Shared by `:app` and `:wear` (issue #294). */
enum class HapticEvent {
    /** A swipe or pull crossed the point where release commits its action. */
    GestureThreshold,
    /** A drag gesture began (for example, lifting a queue row). */
    GestureStart,
    /** A drag gesture finished (for example, dropping a queue row). */
    GestureEnd,
    /** A long press entered selection mode or started a drag. */
    LongPress,
    ToggleOn,
    ToggleOff,
    /** One discrete step of a slider, or a dragged row crossing into a new slot. */
    Tick,
    /** A light tick on a transport control press (play, pause, skip). */
    Click,
    Confirm,
    Reject,
}

/**
 * Returns the [HapticFeedbackConstants] value for [event] on [sdkInt].
 *
 * issue #292: the Compose `HapticFeedbackType` in the current BOM only exposes `LongPress` and
 * `TextHandleMove`, so this calls `View.performHapticFeedback` directly. The toggle, segment
 * tick, and gesture-threshold constants need API 34; API 31 to 33 get the fallbacks below.
 */
fun hapticConstantFor(event: HapticEvent, sdkInt: Int): Int {
    val api34 = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    return when (event) {
        HapticEvent.GestureThreshold ->
            if (api34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.GESTURE_START
        HapticEvent.GestureStart -> HapticFeedbackConstants.GESTURE_START
        HapticEvent.GestureEnd -> HapticFeedbackConstants.GESTURE_END
        HapticEvent.LongPress -> HapticFeedbackConstants.LONG_PRESS
        HapticEvent.ToggleOn ->
            if (api34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONFIRM
        HapticEvent.ToggleOff ->
            if (api34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.REJECT
        HapticEvent.Tick ->
            if (api34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        HapticEvent.Click -> HapticFeedbackConstants.CONTEXT_CLICK
        HapticEvent.Confirm -> HapticFeedbackConstants.CONFIRM
        HapticEvent.Reject -> HapticFeedbackConstants.REJECT
    }
}

/**
 * Shared haptics entry point so the same interaction feels the same everywhere (issue #292).
 * `performHapticFeedback` honors the system "Touch feedback" setting, so there is no app-level
 * toggle. Call only from user-initiated events, never passive ones such as a finished refresh.
 */
class Haptics(private val view: View, private val sdkInt: Int = Build.VERSION.SDK_INT) {
    fun perform(event: HapticEvent) {
        view.performHapticFeedback(hapticConstantFor(event, sdkInt))
    }

    fun toggle(on: Boolean) = perform(if (on) HapticEvent.ToggleOn else HapticEvent.ToggleOff)
}
