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
            label = { Text("Server") },
        )
        OutlinedTextField(
            value = issuer,
            onValueChange = { issuer = it },
            label = { Text("Identity provider") },
        )
        Button(
            onClick = { viewModel.signIn(server, issuer) },
            enabled = state !is SignInUiState.SigningIn,
        ) {
            Text(if (state is SignInUiState.SigningIn) "Signing in..." else "Sign in")
        }
        (state as? SignInUiState.Failed)?.let { failed ->
            Text(failed.message, color = MaterialTheme.colorScheme.error)
        }
    }
}
