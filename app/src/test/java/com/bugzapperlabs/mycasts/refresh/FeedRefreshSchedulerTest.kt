package com.bugzapperlabs.mycasts.refresh

import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * Deliberately doesn't touch a real [androidx.work.WorkManager] -- see [FeedRefreshScheduling]'s
 * doc: that has previously deadlocked Robolectric-hosted tests. [FeedRefreshScheduler.policyFor]
 * is extracted purely so this policy-selection logic (issue #164) can be tested directly instead.
 */
class FeedRefreshSchedulerTest {
    @Test
    fun policyFor_noExistingWork_usesUpdate() {
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, FeedRefreshScheduler.policyFor(null))
    }

    @Test
    fun policyFor_enqueuedWork_usesUpdate() {
        val existing = workInfo(WorkInfo.State.ENQUEUED)

        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, FeedRefreshScheduler.policyFor(existing))
    }

    @Test
    fun policyFor_runningWork_usesUpdate() {
        val existing = workInfo(WorkInfo.State.RUNNING)

        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, FeedRefreshScheduler.policyFor(existing))
    }

    @Test
    fun policyFor_failedWork_usesReplace() {
        // issue #164: UPDATE only rewrites the definition, never revives a job already stuck in a
        // terminal state -- REPLACE is required to actually bring it back.
        val existing = workInfo(WorkInfo.State.FAILED)

        assertEquals(ExistingPeriodicWorkPolicy.REPLACE, FeedRefreshScheduler.policyFor(existing))
    }

    @Test
    fun policyFor_cancelledWork_usesReplace() {
        val existing = workInfo(WorkInfo.State.CANCELLED)

        assertEquals(ExistingPeriodicWorkPolicy.REPLACE, FeedRefreshScheduler.policyFor(existing))
    }

    private fun workInfo(state: WorkInfo.State) = WorkInfo(UUID.randomUUID(), state, emptySet(), Data.EMPTY, Data.EMPTY, 0)
}
