package com.verdenroz.corvid.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.auth.AuthFailure
import com.verdenroz.corvid.core.auth.CodeOutcome
import com.verdenroz.corvid.core.auth.Passkey
import com.verdenroz.corvid.core.auth.PasskeyListing
import com.verdenroz.corvid.core.auth.PasskeyRepository
import com.verdenroz.corvid.core.auth.PasskeyRole
import com.verdenroz.corvid.core.auth.RemoveOutcome
import com.verdenroz.corvid.core.data.catchingSignIn
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.domain.EnrollPasskey
import com.verdenroz.corvid.core.domain.MasterSignIn
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.passkeys_failed_last
import com.verdenroz.corvid.feature.settings.resources.passkeys_failed_not_accepted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface PasskeyList {
    data object Loading : PasskeyList

    data class Listed(val passkeys: List<Passkey>) : PasskeyList

    data object Off : PasskeyList

    data class Failed(val message: UiText) : PasskeyList
}

sealed interface PasskeyStep {
    data object Idle : PasskeyStep

    data object SigningIn : PasskeyStep

    data object Working : PasskeyStep

    /** A code to type on another device, shown once. */
    data class Code(val role: PasskeyRole, val code: String) : PasskeyStep

    data class Added(val role: PasskeyRole) : PasskeyStep

    data object Removed : PasskeyStep

    data class Failed(val message: UiText) : PasskeyStep
}

data class PasskeysState(
    val list: PasskeyList = PasskeyList.Loading,
    val step: PasskeyStep = PasskeyStep.Idle,
    val removing: String? = null,
) {
    val busy: Boolean get() = step is PasskeyStep.SigningIn || step is PasskeyStep.Working
}

/**
 * The passkeys this server holds. Adding or removing one is a master act: each costs a fresh master
 * passkey, whose token goes to that one request and is not stored.
 */
class PasskeysViewModel(
    private val repository: PasskeyRepository,
    private val masterSignIn: MasterSignIn,
    private val enrollPasskey: EnrollPasskey,
    private val server: ServerAddress,
) : ViewModel() {
    private val _state = MutableStateFlow(PasskeysState())
    val state: StateFlow<PasskeysState> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(list = PasskeyList.Loading)
        viewModelScope.launch {
            val list =
                try {
                    when (val listing = repository.list()) {
                        is PasskeyListing.Listed -> PasskeyList.Listed(listing.passkeys)
                        PasskeyListing.NotServed -> PasskeyList.Off
                    }
                } catch (e: JmapException) {
                    PasskeyList.Failed(e.userMessage())
                }
            _state.value = _state.value.copy(list = list)
        }
    }

    /** Makes a code for [role] to type on another device. */
    fun makeCode(role: PasskeyRole) = withMaster { token -> withCode(role, token) { code -> PasskeyStep.Code(role, code) } }

    /** Makes a passkey of [role] on this device, through a code the master makes for it. */
    fun addHere(role: PasskeyRole) =
        withMaster { token ->
            withCode(role, token) { code ->
                enrollPasskey(server, code)
                PasskeyStep.Added(role)
            }
        }

    private suspend fun withCode(
        role: PasskeyRole,
        token: String,
        minted: suspend (String) -> PasskeyStep,
    ): PasskeyStep =
        when (val outcome = repository.mintCode(role, token)) {
            is CodeOutcome.Minted -> minted(outcome.code)
            CodeOutcome.NotAccepted -> PasskeyStep.Failed(uiText(Res.string.passkeys_failed_not_accepted))
            CodeOutcome.NotServed -> PasskeyStep.Failed(AuthFailure.Off.userMessage())
        }

    fun askRemove(id: String) {
        if (!_state.value.busy) _state.value = _state.value.copy(removing = id, step = PasskeyStep.Idle)
    }

    fun keep() {
        _state.value = _state.value.copy(removing = null)
    }

    fun remove(id: String) {
        _state.value = _state.value.copy(removing = null)
        withMaster { token ->
            when (repository.remove(id, token)) {
                RemoveOutcome.Removed, RemoveOutcome.Gone -> PasskeyStep.Removed
                RemoveOutcome.Last -> PasskeyStep.Failed(uiText(Res.string.passkeys_failed_last))
                RemoveOutcome.NotAccepted -> PasskeyStep.Failed(uiText(Res.string.passkeys_failed_not_accepted))
            }
        }
    }

    private fun withMaster(act: suspend (String) -> PasskeyStep) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(step = PasskeyStep.SigningIn)
        viewModelScope.launch {
            val step =
                catchingSignIn(PasskeyStep::Failed) {
                    masterSignIn.withAdminToken { token ->
                        _state.value = _state.value.copy(step = PasskeyStep.Working)
                        act(token)
                    }
                }
            _state.value = _state.value.copy(step = step)
            if (step is PasskeyStep.Added || step == PasskeyStep.Removed) refresh()
        }
    }
}
