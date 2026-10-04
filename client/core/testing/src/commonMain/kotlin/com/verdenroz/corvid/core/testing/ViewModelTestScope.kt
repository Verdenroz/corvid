package com.verdenroz.corvid.core.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class ViewModelTestScope {
    internal val viewModels = mutableListOf<ViewModel>()

    /** Registers [viewModel] so its scope is cancelled and joined when the test ends. */
    fun <T : ViewModel> track(viewModel: T): T = viewModel.also { viewModels += it }
}

/**
 * viewModelScope resolves to Dispatchers.Main, structurally unrelated to
 * runTest's own scheduler, so Main and the test body share one dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun runViewModelTest(body: suspend ViewModelTestScope.() -> Unit): TestResult {
    val dispatcher = UnconfinedTestDispatcher()
    Dispatchers.setMain(dispatcher)
    val scope = ViewModelTestScope()
    return runTest(dispatcher) {
        try {
            scope.body()
        } finally {
            scope.viewModels.forEach { it.viewModelScope.coroutineContext.job.cancel() }
            scope.viewModels.forEach { it.viewModelScope.coroutineContext.job.join() }
            Dispatchers.resetMain()
        }
    }
}
