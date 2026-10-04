package com.verdenroz.corvid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.UpdateRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.UpdateReport
import com.verdenroz.corvid.core.text.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What this device asked the server to do, while the server is still doing it. */
enum class UpdateActivity { Checking, Updating }

sealed interface AboutState {
    data object Loading : AboutState

    /** The server answers 404: it is older than the update endpoint. */
    data object Unsupported : AboutState

    data class Failed(val message: UiText) : AboutState

    /** [failure] is the last request that did not go through; [report] stays what the server last said. */
    data class Ready(
        val report: UpdateReport,
        val activity: UpdateActivity? = null,
        val failure: UiText? = null,
    ) : AboutState {
        val working: Boolean get() = activity != null || report.busy
    }
}

class AboutViewModel(
    private val repository: UpdateRepository,
    private val pollEvery: Duration = POLL_EVERY,
) : ViewModel() {
    private val _state = MutableStateFlow<AboutState>(AboutState.Loading)
    val state: StateFlow<AboutState> = _state

    private var polling: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    fun check() = request(UpdateActivity.Checking) { repository.check() }

    fun apply() = request(UpdateActivity.Updating) { repository.apply() }

    /** Ends polling; the screen calls it on leaving, since the view model outlives it. */
    fun stop() {
        polling?.cancel()
    }

    private suspend fun load() {
        try {
            val report = repository.status()
            _state.value = if (report == null) AboutState.Unsupported else AboutState.Ready(report)
            if (report?.busy == true) poll()
        } catch (e: JmapException) {
            _state.value = AboutState.Failed(e.userMessage())
        }
    }

    private fun request(
        activity: UpdateActivity,
        send: suspend () -> Unit,
    ) {
        val ready = _state.value as? AboutState.Ready ?: return
        if (ready.working) return
        _state.value = ready.copy(activity = activity, failure = null)
        poll(send)
    }

    private fun poll(send: suspend () -> Unit = {}) {
        polling?.cancel()
        polling =
            viewModelScope.launch {
                try {
                    send()
                    settle()
                } catch (e: JmapException) {
                    update { it.copy(activity = null, failure = e.userMessage()) }
                }
            }
    }

    private suspend fun settle() {
        while (true) {
            delay(pollEvery)
            val report =
                try {
                    repository.status()
                } catch (e: JmapException) {
                    // The server restarts itself to finish an update, so these two are the expected gap.
                    if (e.error is JmapError.Unreachable || e.error is JmapError.Server) continue else throw e
                }
            if (report == null) {
                _state.value = AboutState.Unsupported
                return
            }
            update { it.copy(report = report, activity = it.activity.takeIf { report.busy }) }
            if (!report.busy) return
        }
    }

    private fun update(change: (AboutState.Ready) -> AboutState.Ready) {
        _state.value.let { if (it is AboutState.Ready) _state.value = change(it) }
    }
}

private val POLL_EVERY = 3.seconds
