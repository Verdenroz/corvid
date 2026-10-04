package com.verdenroz.corvid.core.designsystem.component

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.core.designsystem.CorvidShape
import com.verdenroz.corvid.core.designsystem.CorvidTheme

/** Material's field for its focus and accessibility; the hairline, radius and type are Corvid's. */
@Composable
fun CorvidTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = CorvidTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        shape = CorvidShape.medium,
        textStyle = CorvidTheme.type.name.copy(color = colors.ink),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.line,
                focusedLabelColor = colors.accent,
                unfocusedLabelColor = colors.inkMuted,
                cursorColor = colors.accent,
            ),
        modifier = modifier,
    )
}
