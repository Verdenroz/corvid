package com.verdenroz.corvid.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.key_cancel
import com.verdenroz.corvid.appcommon.resources.key_pin_continue
import com.verdenroz.corvid.appcommon.resources.key_pin_label
import com.verdenroz.corvid.appcommon.resources.key_pin_title
import com.verdenroz.corvid.appcommon.resources.key_pin_wrong
import com.verdenroz.corvid.appcommon.resources.key_touch_detail
import com.verdenroz.corvid.appcommon.resources.key_touch_title
import com.verdenroz.corvid.core.auth.KeyPromptState
import com.verdenroz.corvid.core.auth.SecurityKeyPrompt
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import org.jetbrains.compose.resources.stringResource

/** Asks for the security key's PIN, then tells the person to touch it, for as long as a ceremony needs either. */
@Composable
fun SecurityKeyDialog(prompt: SecurityKeyPrompt) {
    val state by prompt.state.collectAsState()
    when (val current = state) {
        KeyPromptState.Idle -> Unit
        is KeyPromptState.Pin -> PinDialog(current.wrong, prompt)
        KeyPromptState.Touch -> TouchDialog(prompt)
    }
}

@Composable
private fun PinDialog(
    wrong: Boolean,
    prompt: SecurityKeyPrompt,
) {
    var pin by remember { mutableStateOf("") }
    val submit = { if (pin.isNotEmpty()) prompt.submitPin(pin) }
    AlertDialog(
        onDismissRequest = prompt::cancel,
        title = { Text(stringResource(Res.string.key_pin_title)) },
        text = {
            Column {
                if (wrong) Text(stringResource(Res.string.key_pin_wrong), color = CorvidTheme.colors.danger)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(stringResource(Res.string.key_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text(stringResource(Res.string.key_pin_continue)) } },
        dismissButton = { TextButton(onClick = prompt::cancel) { Text(stringResource(Res.string.key_cancel)) } },
    )
}

@Composable
private fun TouchDialog(prompt: SecurityKeyPrompt) {
    AlertDialog(
        onDismissRequest = prompt::cancel,
        title = { Text(stringResource(Res.string.key_touch_title)) },
        text = { Text(stringResource(Res.string.key_touch_detail)) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = prompt::cancel) { Text(stringResource(Res.string.key_cancel)) } },
    )
}
