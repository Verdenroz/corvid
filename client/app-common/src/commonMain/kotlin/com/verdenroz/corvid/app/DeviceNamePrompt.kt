package com.verdenroz.corvid.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.device_name_continue
import com.verdenroz.corvid.appcommon.resources.device_name_hint
import com.verdenroz.corvid.appcommon.resources.device_name_title
import com.verdenroz.corvid.core.keyring.DEVICE_NAME_PATTERN
import org.jetbrains.compose.resources.stringResource

/** Asked once per session, the first time a signed-in device has no key yet (decision 10:
 *  an existing file is never adopted, so this name always creates a fresh one). */
@Composable
fun DeviceNamePrompt(onSubmit: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val valid = DEVICE_NAME_PATTERN.matches(name)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(Res.string.device_name_title))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(Res.string.device_name_hint)) },
        )
        Button(onClick = { onSubmit(name) }, enabled = valid) {
            Text(stringResource(Res.string.device_name_continue))
        }
    }
}
