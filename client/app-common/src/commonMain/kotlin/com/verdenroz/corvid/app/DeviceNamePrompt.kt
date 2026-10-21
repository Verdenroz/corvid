package com.verdenroz.corvid.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.device_name_continue
import com.verdenroz.corvid.appcommon.resources.device_name_detail
import com.verdenroz.corvid.appcommon.resources.device_name_hint
import com.verdenroz.corvid.appcommon.resources.device_name_title
import com.verdenroz.corvid.core.designsystem.component.CorvidTextField
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.keyring.DEVICE_NAME_PATTERN
import com.verdenroz.corvid.core.ui.StatePane
import org.jetbrains.compose.resources.stringResource

/** Asked once per session, the first time a signed-in device has no key yet. An existing key
 *  file is never adopted, so this name always creates a fresh one. */
@Composable
fun DeviceNamePrompt(onSubmit: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }

    val valid = DEVICE_NAME_PATTERN.matches(name)
    StatePane(stringResource(Res.string.device_name_title), detail = stringResource(Res.string.device_name_detail)) {
        CorvidTextField(name, { name = it }, stringResource(Res.string.device_name_hint), Modifier.fillMaxWidth(), onSubmit = {
            if (valid) onSubmit(name)
        })
        PrimaryButton(stringResource(Res.string.device_name_continue), onClick = { onSubmit(name) }, enabled = valid)
    }
}
