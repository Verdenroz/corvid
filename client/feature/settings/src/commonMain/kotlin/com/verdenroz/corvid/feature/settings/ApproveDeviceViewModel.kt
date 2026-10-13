package com.verdenroz.corvid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.catchingSignIn
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.domain.ApproveWithKey
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.keyring.ApproveOutcome
import com.verdenroz.corvid.core.keyring.DeviceApprovalRepository
import com.verdenroz.corvid.core.keyring.PendingOutcome
import com.verdenroz.corvid.core.keyring.PendingRequest
import com.verdenroz.corvid.core.keyring.parseApprovalLink
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.approve_failed_gone
import com.verdenroz.corvid.feature.settings.resources.approve_failed_not_accepted
import com.verdenroz.corvid.feature.settings.resources.approve_failed_refused
import com.verdenroz.corvid.feature.settings.resources.approve_failed_upstream
import com.verdenroz.corvid.feature.settings.resources.approve_not_a_link
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val CODE_LENGTH = 6
private val CODE = Regex("[0-9]{$CODE_LENGTH}")
private const val SHORT_ID_LENGTH = 8

sealed interface ApproveStep {
    data object Editing : ApproveStep

    data object SigningIn : ApproveStep

    data object Sending : ApproveStep

    data class Approved(val shortId: String) : ApproveStep

    data class Failed(val message: UiText) : ApproveStep
}

sealed interface PendingList {
    data object Loading : PendingList

    data class Listed(val requests: List<PendingRequest>) : PendingList

    data object Off : PendingList

    data class Failed(val message: UiText) : PendingList
}

data class ApproveDeviceState(
    val id: String = "",
    val code: String = "",
    val step: ApproveStep = ApproveStep.Editing,
    val pending: PendingList = PendingList.Loading,
    val typingId: Boolean = false,
) {
    val busy: Boolean get() = step is ApproveStep.SigningIn || step is ApproveStep.Sending
    val canApprove: Boolean get() = id.isNotBlank() && CODE.matches(code) && !busy
}

/**
 * Approves another device's request. The admin token a sign-in returns is passed to one request and
 * not stored, so each approval costs a fresh passkey.
 */
class ApproveDeviceViewModel(
    private val repository: DeviceApprovalRepository,
    private val approveWithKey: ApproveWithKey,
) : ViewModel() {
    private val _state = MutableStateFlow(ApproveDeviceState())
    val state: StateFlow<ApproveDeviceState> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(pending = PendingList.Loading)
        viewModelScope.launch {
            val pending =
                try {
                    when (val outcome = repository.pending()) {
                        is PendingOutcome.Listed -> PendingList.Listed(outcome.requests)
                        PendingOutcome.NotServed -> PendingList.Off
                    }
                } catch (e: JmapException) {
                    PendingList.Failed(e.userMessage())
                }
            _state.value = _state.value.copy(pending = pending)
        }
    }

    fun pick(request: PendingRequest) = setId(request.id)

    fun typeIdInstead() = edit { it.copy(typingId = true) }

    fun setId(id: String) = edit { it.copy(id = id.trim()) }

    fun setCode(code: String) = edit { it.copy(code = code.filter(Char::isDigit).take(CODE_LENGTH)) }

    fun scanned(text: String) {
        val link = parseApprovalLink(text)
        _state.value =
            if (link == null) {
                _state.value.copy(step = ApproveStep.Failed(uiText(Res.string.approve_not_a_link)))
            } else {
                _state.value.copy(id = link.id, code = link.code, step = ApproveStep.Editing, typingId = true)
            }
    }

    fun approve() {
        val current = _state.value
        if (!current.canApprove) return
        _state.value = current.copy(step = ApproveStep.SigningIn)
        viewModelScope.launch {
            val step = send(current.id, current.code)
            _state.value = _state.value.copy(step = step)
            if (step is ApproveStep.Approved) refresh()
        }
    }

    private suspend fun send(
        id: String,
        code: String,
    ): ApproveStep {
        return catchingSignIn(ApproveStep::Failed) {
            approveWithKey.withAdminToken { token ->
                _state.value = _state.value.copy(step = ApproveStep.Sending)
                when (repository.approve(id, code, token)) {
                    ApproveOutcome.Approved -> ApproveStep.Approved(id.take(SHORT_ID_LENGTH))
                    ApproveOutcome.NotAccepted -> ApproveStep.Failed(uiText(Res.string.approve_failed_not_accepted))
                    ApproveOutcome.Refused -> ApproveStep.Failed(uiText(Res.string.approve_failed_refused))
                    ApproveOutcome.Gone -> ApproveStep.Failed(uiText(Res.string.approve_failed_gone))
                    ApproveOutcome.UpstreamFailed -> ApproveStep.Failed(uiText(Res.string.approve_failed_upstream))
                }
            }
        }
    }

    private fun edit(change: (ApproveDeviceState) -> ApproveDeviceState) {
        if (!_state.value.busy) _state.value = change(_state.value).copy(step = ApproveStep.Editing)
    }
}
