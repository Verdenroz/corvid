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
        Text("No key on the server for \"$deviceName\" yet.")
        Text("It may be this device's first sign-in, or its access was revoked.")
        Button(onClick = onConfirm) {
            Text("Enroll \"$deviceName\"")
        }
    }
}
