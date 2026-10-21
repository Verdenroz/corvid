package com.verdenroz.corvid.feature.aliases

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.verdenroz.corvid.core.data.Alias
import com.verdenroz.corvid.core.data.AliasListing
import com.verdenroz.corvid.core.data.AliasRefusal
import com.verdenroz.corvid.core.data.AliasRepository
import com.verdenroz.corvid.core.data.AtCap
import com.verdenroz.corvid.core.data.MintOutcome
import com.verdenroz.corvid.core.data.SwitchOutcome
import com.verdenroz.corvid.core.data.catchingSignIn
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.domain.ApproveWithKey
import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.aliases.resources.Res
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_busy
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_cap
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_gone
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_label
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_not_accepted
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_server
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_session
import com.verdenroz.corvid.feature.aliases.resources.aliases_off
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val LABEL_MAX = 20
private val LABEL_CHARS = ('a'..'z') + ('0'..'9') + '-'

sealed interface AliasList {
    data object Loading : AliasList

    /** Every alias the server minted, a [Alias.missing] one included. */
    data class Listed(val aliases: List<Alias>) : AliasList

    data object Off : AliasList

    data class Failed(val message: UiText) : AliasList
}

sealed interface AliasStep {
    data object Idle : AliasStep

    data object Minting : AliasStep

    data object SigningIn : AliasStep

    data object Working : AliasStep

    data class Minted(val alias: Alias) : AliasStep

    /** A disable or enable went through; [alias] is the row as the server now holds it. */
    data class Switched(val alias: Alias) : AliasStep

    data class Failed(val message: UiText) : AliasStep
}

data class AliasesState(
    val list: AliasList = AliasList.Loading,
    val step: AliasStep = AliasStep.Idle,
    val label: String = "",
    val disabling: String? = null,
) {
    val busy: Boolean get() = step is AliasStep.Minting || step is AliasStep.SigningIn || step is AliasStep.Working
}

/**
 * The aliases this server minted. Listing and minting ride the session; disabling or enabling one
 * costs a fresh approver passkey, whose token goes to that one request and is not stored.
 */
class AliasesViewModel(
    private val repository: AliasRepository,
    private val approveWithKey: ApproveWithKey,
) : ViewModel() {
    private val _state = MutableStateFlow(AliasesState())
    val state: StateFlow<AliasesState> = _state

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(list = AliasList.Loading)
        viewModelScope.launch {
            val list =
                try {
                    when (val listing = repository.list()) {
                        is AliasListing.Listed -> AliasList.Listed(listing.aliases)
                        AliasRefusal.Off -> AliasList.Off
                        is AliasRefusal -> AliasList.Failed(listing.message(switching = false))
                    }
                } catch (e: JmapException) {
                    AliasList.Failed(e.userMessage())
                }
            _state.value = _state.value.copy(list = list)
        }
    }

    /** Keeps only what a label may hold, lowercased, up to the server's length. */
    fun setLabel(text: String) {
        _state.value = _state.value.copy(label = text.lowercase().filter { it in LABEL_CHARS }.take(LABEL_MAX))
    }

    fun mint() {
        val label = _state.value.label
        if (_state.value.busy || label.isEmpty()) return
        _state.value = _state.value.copy(step = AliasStep.Minting)
        viewModelScope.launch {
            val step =
                try {
                    when (val outcome = repository.mint(label)) {
                        is MintOutcome.Minted -> AliasStep.Minted(outcome.alias)
                        MintOutcome.BadLabel -> AliasStep.Failed(uiText(Res.string.aliases_failed_label))
                        AtCap -> AliasStep.Failed(uiText(Res.string.aliases_failed_cap))
                        is AliasRefusal -> AliasStep.Failed(outcome.message(switching = false))
                    }
                } catch (e: JmapException) {
                    AliasStep.Failed(e.userMessage())
                }
            _state.value = _state.value.copy(step = step, label = if (step is AliasStep.Minted) "" else label)
            if (step is AliasStep.Minted) refresh()
        }
    }

    fun askDisable(name: String) {
        if (!_state.value.busy) _state.value = _state.value.copy(disabling = name, step = AliasStep.Idle)
    }

    fun keep() {
        _state.value = _state.value.copy(disabling = null)
    }

    fun disable(name: String) {
        _state.value = _state.value.copy(disabling = null)
        withApprover { token -> repository.disable(name, token).toStep() }
    }

    fun enable(name: String) {
        withApprover { token -> repository.enable(name, token).toStep() }
    }

    private fun withApprover(act: suspend (String) -> AliasStep) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(step = AliasStep.SigningIn)
        viewModelScope.launch {
            val step =
                catchingSignIn(AliasStep::Failed) {
                    approveWithKey.withAdminToken { token ->
                        _state.value = _state.value.copy(step = AliasStep.Working)
                        act(token)
                    }
                }
            _state.value = _state.value.copy(step = step)
            if (step is AliasStep.Switched || step == GONE) refresh()
        }
    }
}

private val GONE = AliasStep.Failed(uiText(Res.string.aliases_failed_gone))

private fun SwitchOutcome.toStep(): AliasStep =
    when (this) {
        is SwitchOutcome.Switched -> AliasStep.Switched(alias)
        SwitchOutcome.Gone -> GONE
        AtCap -> AliasStep.Failed(uiText(Res.string.aliases_failed_cap))
        is AliasRefusal -> AliasStep.Failed(message(switching = true))
    }

/** On a switch a refused credential is the approver's token; anywhere else it is the session. */
private fun AliasRefusal.message(switching: Boolean): UiText =
    when (this) {
        AliasRefusal.Off -> uiText(Res.string.aliases_off)
        AliasRefusal.Busy -> uiText(Res.string.aliases_failed_busy)
        AliasRefusal.Failed -> uiText(Res.string.aliases_failed_server)
        AliasRefusal.NotAccepted ->
            uiText(if (switching) Res.string.aliases_failed_not_accepted else Res.string.aliases_failed_session)
    }
