package com.verdenroz.corvid.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Starts a write that runs to the end even when the view model is cleared first, as a screen's is the moment its
 * back-stack entry is popped, which can be right after the tap that started the write.
 */
@OptIn(DelicateCoroutinesApi::class)
fun ViewModel.launchToEnd(block: suspend CoroutineScope.() -> Unit): Job =
    viewModelScope.launch(start = CoroutineStart.ATOMIC) { withContext(NonCancellable, block) }
