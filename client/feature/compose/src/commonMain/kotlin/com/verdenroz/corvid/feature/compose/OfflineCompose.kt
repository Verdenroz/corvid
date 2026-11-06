package com.verdenroz.corvid.feature.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.data.outbox.WriteOutcome
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.QuietLink
import com.verdenroz.corvid.core.model.ComposeIntent
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.model.parseAddresses
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.core.ui.DiscardQuestion
import com.verdenroz.corvid.core.ui.StatusLine
import com.verdenroz.corvid.feature.compose.resources.Res
import com.verdenroz.corvid.feature.compose.resources.compose_add_recipient
import com.verdenroz.corvid.feature.compose.resources.compose_discard_question
import com.verdenroz.corvid.feature.compose.resources.compose_not_sent
import com.verdenroz.corvid.feature.compose.resources.compose_offline_done
import com.verdenroz.corvid.feature.compose.resources.compose_offline_empty
import com.verdenroz.corvid.feature.compose.resources.compose_offline_no_key
import com.verdenroz.corvid.feature.compose.resources.compose_offline_queued
import com.verdenroz.corvid.feature.compose.resources.compose_outbox_held
import com.verdenroz.corvid.feature.compose.resources.compose_outbox_unsaved
import com.verdenroz.corvid.feature.compose.resources.compose_subject
import com.verdenroz.corvid.feature.compose.resources.compose_to
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * A new message written while the device key is not held. It is plain Compose state, never saved with the
 * screen, so what is typed reaches disk only sealed, when it is queued.
 */
class OfflineComposeState {
    var to by mutableStateOf("")
    var subject by mutableStateOf("")
    var body by mutableStateOf("")
    var error by mutableStateOf<UiText?>(null)
        private set
    var sending by mutableStateOf(false)
        private set
    var queued by mutableStateOf(false)
        private set

    val hasText: Boolean get() = to.isNotBlank() || subject.isNotBlank() || body.isNotBlank()

    fun clear() {
        to = ""
        subject = ""
        body = ""
        error = null
        queued = false
    }

    suspend fun send(write: suspend (Draft) -> WriteOutcome) {
        val draft = Draft(to = parseAddresses(to), subject = subject, body = body)
        error =
            when {
                draft.to.isEmpty() -> uiText(Res.string.compose_add_recipient)
                subject.isBlank() && body.isBlank() -> uiText(Res.string.compose_offline_empty)
                else -> null
            }
        if (error != null) return
        sending = true
        try {
            when (write(draft)) {
                is WriteOutcome.Queued -> {
                    clear()
                    queued = true
                }
                WriteOutcome.HeldElsewhere -> refuse(Res.string.compose_outbox_held)
                WriteOutcome.NoKey -> error = uiText(Res.string.compose_offline_no_key)
            }
        } catch (_: IOException) {
            refuse(Res.string.compose_outbox_unsaved)
        } catch (_: GeneralSecurityException) {
            refuse(Res.string.compose_outbox_unsaved)
        } finally {
            sending = false
        }
    }

    private fun refuse(reason: StringResource) {
        error = uiText(Res.string.compose_not_sent, uiText(reason))
    }
}

/** [onSend] seals and queues the draft; [onClose] leaves the screen, after a discard question when text is typed. */
@Composable
fun OfflineComposeScreen(
    state: OfflineComposeState,
    onSend: suspend (Draft) -> WriteOutcome,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val finish = {
        state.clear()
        onClose()
    }
    var confirmingDiscard by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(CorvidTheme.colors.surface)) {
        Bar(
            ComposeIntent.New,
            sending = state.sending || state.queued,
            onClose = {
                if (state.hasText) confirmingDiscard = true else finish()
            },
            onAttach = null,
            onSend = { scope.launch { state.send(onSend) } },
        )
        if (state.queued) {
            Queued(finish)
            return@Column
        }
        if (confirmingDiscard) {
            DiscardQuestion(
                stringResource(Res.string.compose_discard_question),
                onConfirm = finish,
                onCancel = { confirmingDiscard = false },
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            state.error?.let {
                StatusLine(
                    it.asString(),
                    failed = true,
                    style = CorvidTheme.type.meta,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Field(stringResource(Res.string.compose_to), state.to) { state.to = it }
            Field(stringResource(Res.string.compose_subject), state.subject) { state.subject = it }
            Body(state.body) { state.body = it }
        }
    }
}

@Composable
private fun Queued(onDone: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp)) {
            Text(stringResource(Res.string.compose_offline_queued), style = CorvidTheme.type.body, color = CorvidTheme.colors.inkMuted)
            QuietLink(stringResource(Res.string.compose_offline_done), onDone)
        }
    }
}
