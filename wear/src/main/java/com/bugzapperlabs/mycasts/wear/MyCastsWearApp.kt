package com.bugzapperlabs.mycasts.wear

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * The watch app's Hilt entry point, separate from `:app`'s `MyCastsApp` since they're independent
 * processes on independent devices (issue #276) -- notification channels and WorkManager wiring
 * are added here once the watch actually needs them (downloads/foreground workers are phase 2+).
 */
@HiltAndroidApp
class MyCastsWearApp : Application()
