package com.verdenroz.corvid.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.enroll_confirm
import com.verdenroz.corvid.appcommon.resources.enroll_detail
import com.verdenroz.corvid.appcommon.resources.enroll_title
import org.jetbrains.compose.resources.stringResource

/**
 * No key exists yet for [deviceName]: could be its first sign-in, or an
 * operator revoke. Either way, enrolling means this device gets new access,
 * so it needs its own tap rather than following straight from naming the
 * device (decision 10: never automatic).
 */
@Composable
fun EnrollmentPrompt(
    deviceName: String,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(Res.string.enroll_title, deviceName))
        Text(stringResource(Res.string.enroll_detail))
        Button(onClick = onConfirm) {
            Text(stringResource(Res.string.enroll_confirm, deviceName))
        }
    }
}
