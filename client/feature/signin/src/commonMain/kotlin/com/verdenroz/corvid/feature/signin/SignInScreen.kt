package com.verdenroz.corvid.feature.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.feature.signin.resources.Res
import com.verdenroz.corvid.feature.signin.resources.signin_in_progress
import com.verdenroz.corvid.feature.signin.resources.signin_issuer
import com.verdenroz.corvid.feature.signin.resources.signin_server
import com.verdenroz.corvid.feature.signin.resources.signin_submit
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SignInScreen(viewModel: SignInViewModel = koinViewModel()) {
    var server by rememberSaveable { mutableStateOf("") }
    var issuer by rememberSaveable { mutableStateOf("") }
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text(stringResource(Res.string.signin_server)) },
        )
        OutlinedTextField(
            value = issuer,
            onValueChange = { issuer = it },
            label = { Text(stringResource(Res.string.signin_issuer)) },
        )
        Button(
            onClick = { viewModel.signIn(server, issuer) },
            enabled = state !is SignInUiState.SigningIn,
        ) {
            Text(stringResource(if (state is SignInUiState.SigningIn) Res.string.signin_in_progress else Res.string.signin_submit))
        }
        (state as? SignInUiState.Failed)?.let { failed ->
            Text(failed.message.asString(), color = MaterialTheme.colorScheme.error)
        }
    }
}
