package com.verdenroz.corvid.core.designsystem.component

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme

/**
 * Material's field for its focus and accessibility; the hairline, radius and type are Corvid's.
 * [onSubmit] makes Enter and the keyboard's action key do what the form's primary button does.
 */
@Composable
fun CorvidTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onSubmit: (() -> Unit)? = null,
) {
    val colors = CorvidTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = if (onSubmit == null) keyboardOptions else keyboardOptions.copy(imeAction = ImeAction.Go),
        keyboardActions = onSubmit?.let { KeyboardActions(onGo = { it() }) } ?: KeyboardActions.Default,
        shape = CorvidShape.medium,
        textStyle = CorvidTheme.type.name.copy(color = colors.ink),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.outline,
                focusedLabelColor = colors.accent,
                unfocusedLabelColor = colors.inkMuted,
                cursorColor = colors.accent,
            ),
        modifier = modifier,
    )
}
