package com.verdenroz.corvid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.UpdateRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.jmap.JmapError
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.UpdateReport
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.settings_about_unfinished
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What this device asked the server to do, while the server is still doing it. */
enum class UpdateActivity { Checking, Updating }

sealed interface AboutState {
    data object Loading : AboutState

    /** The server answers 404: it is older than the update endpoint. */
    data object Unsupported : AboutState

    data class Failed(val message: UiText) : AboutState

    /**
     * [failure] is the last request that did not go through; [report] stays what the server last said.
     * [abandoned] means polling hit its ceiling while the server still said busy.
     */
    data class Ready(
        val report: UpdateReport,
        val activity: UpdateActivity? = null,
        val failure: UiText? = null,
        val abandoned: Boolean = false,
    ) : AboutState {
        val working: Boolean get() = activity != null || (report.busy && !abandoned)
    }
}

class AboutViewModel(
    private val repository: UpdateRepository,
    private val ceiling: Duration = CEILING,
) : ViewModel() {
    private val _state = MutableStateFlow<AboutState>(AboutState.Loading)
    val state: StateFlow<AboutState> = _state

    private var polling: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    fun check() = request(UpdateActivity.Checking) { repository.check() }

    fun apply() = request(UpdateActivity.Updating) { repository.apply() }

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
        _state.value = ready.copy(activity = activity, failure = null, abandoned = false)
        poll(send, since = ready.report)
    }

    /** The server turns busy a moment after accepting a request, so a not-busy report still carrying [since]'s `checkedAt` is the old one. */
    private fun poll(
        send: suspend () -> Unit = {},
        since: UpdateReport? = null,
    ) {
        polling?.cancel()
        polling =
            viewModelScope.launch {
                try {
                    send()
                    settle(since)
                } catch (e: JmapException) {
                    update { it.copy(activity = null, failure = e.userMessage()) }
                }
            }
    }

    private suspend fun settle(since: UpdateReport?) {
        var waited = Duration.ZERO
        var polls = 0
        while (waited < ceiling) {
            val wait = pollDelay(polls++)
            delay(wait)
            waited += wait
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
            val settled = !report.busy && (since == null || report.checkedAt != since.checkedAt)
            update { it.copy(report = report, activity = it.activity.takeUnless { settled }) }
            if (settled) return
        }
        update { it.copy(activity = null, abandoned = true, failure = uiText(Res.string.settings_about_unfinished)) }
    }

    private fun update(change: (AboutState.Ready) -> AboutState.Ready) {
        _state.value.let { if (it is AboutState.Ready) _state.value = change(it) }
    }
}

private val POLL_BACKOFF = listOf(3.seconds, 5.seconds, 10.seconds, 15.seconds)
private val CEILING = 20.minutes

/** The wait before the [poll]th status read, lengthening while the server stays busy. */
internal fun pollDelay(poll: Int): Duration = POLL_BACKOFF[minOf(poll, POLL_BACKOFF.lastIndex)]
