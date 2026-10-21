package com.bugzapperlabs.mycasts.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** issue #303: one `/position` item per device, with the legacy per-episode paths still accepted. */
class PositionPathTest {
    @Test
    fun isPositionPath_acceptsTheCurrentPath() {
        assertTrue(isPositionPath(POSITION_PATH))
    }

    @Test
    fun isPositionPath_acceptsALegacyPerEpisodePath() {
        assertTrue(isPositionPath("/position/episode-123"))
    }

    @Test
    fun isPositionPath_rejectsOtherPaths() {
        assertFalse(isPositionPath(QUEUE_PATH))
        assertFalse(isPositionPath("/positionless"))
        assertFalse(isPositionPath(null))
    }
}
