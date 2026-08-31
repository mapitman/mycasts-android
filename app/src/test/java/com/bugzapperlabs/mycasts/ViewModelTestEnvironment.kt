package com.bugzapperlabs.mycasts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.RoomDatabase
import com.bugzapperlabs.mycasts.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.File

/**
 * Shared fixture for ViewModel unit tests (issue #258), replacing the old TrackedViewModelStore.
 *
 * The flakiness tracked in issues #54/#60/#76/#77/#215/#258 came from mixing virtual coroutine
 * time with real background threads: Room's query/invalidation-tracker executor and
 * PreferenceDataStoreFactory's default IO scope both ran on real threads that
 * `runTest`/`advanceUntilIdle()` had no way to flush or wait on, and neither was ever torn down,
 * so a straggler from one test class could go on to corrupt the shared `Dispatchers.Main` static
 * that a *later* Robolectric test class installs (kotlinx-coroutines statics aren't
 * Robolectric-sandboxed per class, unlike Android framework classes).
 *
 * This fixture puts every one of those boundaries on the *same* [TestCoroutineScheduler] that
 * backs [mainDispatcher], so `advanceUntilIdle()` and a suspending `first { }` both
 * deterministically flush Room and DataStore work instead of racing it, and gives DataStore's
 * scope an explicit lifetime that [tearDown] joins and cancels alongside ViewModel scopes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTestEnvironment {
    val scheduler = TestCoroutineScheduler()

    /** Install via `Dispatchers.setMain(mainDispatcher)`; run test bodies with `runTest(mainDispatcher)`. */
    val mainDispatcher = UnconfinedTestDispatcher(scheduler)

    /** Backs Room's query/transaction executors and DataStore's scope -- queued onto the same
     *  scheduler as [mainDispatcher], so both are pumped together by one `runTest`. */
    private val backgroundDispatcher = StandardTestDispatcher(scheduler)
    private val dataStoreScope = CoroutineScope(backgroundDispatcher + Job())

    private val store = ViewModelStore()
    private val viewModelJobs = mutableListOf<Job>()

    fun put(key: String, viewModel: ViewModel) {
        viewModelJobs += viewModel.viewModelScope.coroutineContext.job
        store.put(key, viewModel)
    }

    /** In-memory Room database with executors routed onto [scheduler], so Room's background
     *  query/invalidation-tracker work is deterministically flushed rather than racing real
     *  threads (issue #258). `allowMainThreadQueries()` is still required: it permits *direct*
     *  synchronous DAO calls issued from the calling coroutine -- unrelated to which executor
     *  backs Room's own async work, and unavoidable here since [mainDispatcher] is unconfined and
     *  Robolectric's test thread is itself the main-looper thread Room is checking for. */
    fun database(context: Context): AppDatabase =
        configureExecutors(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java))
            .allowMainThreadQueries()
            .build()

    private fun configureExecutors(
        builder: RoomDatabase.Builder<AppDatabase>,
    ): RoomDatabase.Builder<AppDatabase> {
        val executor = backgroundDispatcher.asExecutor()
        return builder.setQueryExecutor(executor).setTransactionExecutor(executor)
    }

    /** Preferences DataStore whose actor runs on [dataStoreScope] (this fixture's scheduler-backed
     *  scope) instead of the real `Dispatchers.IO` scope `PreferenceDataStoreFactory.create`
     *  otherwise defaults to (issue #258) -- and which [tearDown] explicitly cancels, so it can't
     *  outlive `TemporaryFolder` deleting its backing file. */
    fun preferences(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { file })

    /** Call from inside `runTest(mainDispatcher)` so the scheduler keeps getting pumped while this
     *  waits out in-flight ViewModel and DataStore coroutines (issues #54/#60/#258), before the
     *  caller's `Dispatchers.resetMain()` runs.
     *
     *  There's no supported way to also assert here that nothing *else* is still holding
     *  `Dispatchers.Main` -- `kotlinx.coroutines.test`'s `TestMainDispatcher` wraps every
     *  installed dispatcher and exposes no public accessor for the current delegate, so identity
     *  can't be checked from here. `kotlinx-coroutines-test` 1.9.0 already detects a genuinely
     *  concurrent `setMain`/read internally (its `NonConcurrentlyModifiable` wrapper, the source
     *  of the "Dispatchers.Main is used concurrently with setting it" error this file used to
     *  reference) -- joining every coroutine this class could have leaked, as above, is what
     *  keeps that detector from ever having something to catch. */
    suspend fun tearDown() {
        store.clear()
        viewModelJobs.joinAll()
        viewModelJobs.clear()

        dataStoreScope.cancel()
        dataStoreScope.coroutineContext.job.children.toList().joinAll()
    }
}
