package com.bugzapperlabs.myfeeds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll

/**
 * [ViewModelStore] wrapper for ViewModel tests that swap `Dispatchers.Main` for a test
 * dispatcher (issues #54/#60).
 *
 * ViewModels in these tests are constructed directly (not via a ViewModelProvider), so nothing
 * would otherwise call `ViewModel.clear()` to cancel their viewModelScope between tests --
 * that's what routing them through a real store solves. But `clear()` alone isn't enough:
 * cancellation is asynchronous, and a child coroutine currently suspended in Room/DataStore work
 * on a real background thread only observes it when that work completes -- at which point its
 * resumption dispatches back onto `Dispatchers.Main`. kotlinx-coroutines statics are shared
 * across every Robolectric test class in the JVM (coroutines aren't sandbox-instrumented), so
 * such a straggler can touch the installed TestMainDispatcher exactly while a later test -- in
 * this class or any other -- is calling `Dispatchers.setMain`/`resetMain`. That corrupts it
 * ("Dispatchers.Main is used concurrently with setting it"), which in turn can kill a
 * ViewModel's `stateIn` sharing coroutine and leave the victim test's `first { predicate }`
 * wait hanging until `runTest`'s timeout: the CI hangs of issue #54, the wandering
 * assertion/`UncompletedCoroutinesError` flakes of #60, and the `TestMainDispatcher`
 * IllegalStateExceptions seen in CI were all faces of this one leak.
 *
 * [clearAndJoin] therefore also *joins* every stored ViewModel's scope job, so teardown doesn't
 * return -- and `Dispatchers.resetMain()` doesn't run -- until nothing is left in flight.
 */
class TrackedViewModelStore {
    private val store = ViewModelStore()
    private val jobs = mutableListOf<Job>()

    fun put(key: String, viewModel: ViewModel) {
        jobs += viewModel.viewModelScope.coroutineContext.job
        store.put(key, viewModel)
    }

    /** Call from inside `runTest` on the same scheduler `Dispatchers.setMain` was given, so the
     *  scheduler keeps getting pumped while this waits out in-flight coroutines. */
    suspend fun clearAndJoin() {
        store.clear()
        jobs.joinAll()
        jobs.clear()
    }
}
