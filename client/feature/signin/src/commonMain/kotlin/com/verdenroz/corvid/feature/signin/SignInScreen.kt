package com.verdenroz.corvid.feature.signin

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidTextField
import com.verdenroz.corvid.core.designsystem.component.PillButton
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.domain.Discovery
import com.verdenroz.corvid.core.model.hostOf
import com.verdenroz.corvid.core.prefs.RememberedAccount
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.StatePane
import com.verdenroz.corvid.feature.signin.resources.Res
import com.verdenroz.corvid.feature.signin.resources.signin_advanced
import com.verdenroz.corvid.feature.signin.resources.signin_another
import com.verdenroz.corvid.feature.signin.resources.signin_change
import com.verdenroz.corvid.feature.signin.resources.signin_confirm
import com.verdenroz.corvid.feature.signin.resources.signin_continue
import com.verdenroz.corvid.feature.signin.resources.signin_detail
import com.verdenroz.corvid.feature.signin.resources.signin_discovering
import com.verdenroz.corvid.feature.signin.resources.signin_email
import com.verdenroz.corvid.feature.signin.resources.signin_in_progress
import com.verdenroz.corvid.feature.signin.resources.signin_issuer
import com.verdenroz.corvid.feature.signin.resources.signin_remembered
import com.verdenroz.corvid.feature.signin.resources.signin_remembered_detail
import com.verdenroz.corvid.feature.signin.resources.signin_server
import com.verdenroz.corvid.feature.signin.resources.signin_submit
import com.verdenroz.corvid.feature.signin.resources.signin_title
import com.verdenroz.corvid.feature.signin.resources.signin_use_email
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SignInScreen(viewModel: SignInViewModel = koinViewModel()) {
    var another by rememberSaveable { mutableStateOf(false) }
    val remembered by viewModel.remembered.collectAsState()
    val account = remembered.takeUnless { another }
    if (account != null) {
        RememberedSignIn(viewModel, account) {
            another = true
            viewModel.reset()
        }
    } else {
        AccountEntry(viewModel)
    }
}

@Composable
private fun RememberedSignIn(
    viewModel: SignInViewModel,
    account: RememberedAccount,
    onAnother: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val signingIn = state is SignInUiState.SigningIn
    StatePane(stringResource(Res.string.signin_title), detail = stringResource(Res.string.signin_remembered_detail)) {
        Text(
            stringResource(Res.string.signin_remembered, account.email ?: hostOf(account.server)),
            style = CorvidTheme.type.name,
            color = CorvidTheme.colors.ink,
        )
        (state as? SignInUiState.Failed)?.let { Problem(it.message.asString()) }
        PrimaryButton(
            stringResource(if (signingIn) Res.string.signin_in_progress else Res.string.signin_submit),
            onClick = { viewModel.signInAs(account) },
            enabled = !signingIn,
        )
        PillButton(stringResource(Res.string.signin_another), icon = null, onClick = onAnother)
    }
}

@Composable
private fun AccountEntry(viewModel: SignInViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var issuer by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.state.collectAsState()
    val signingIn = state is SignInUiState.SigningIn

    LaunchedEffect(state) {
        when (val current = state) {
            is SignInUiState.Discovered -> {
                server = current.discovery.server.baseUrl
                issuer = current.discovery.issuer
            }
            is SignInUiState.DiscoveryFailed -> advanced = true
            else -> Unit
        }
    }

    StatePane(stringResource(Res.string.signin_title), detail = stringResource(Res.string.signin_detail)) {
        val discovery = state.discovery
        when {
            advanced -> {
                val url = KeyboardOptions(keyboardType = KeyboardType.Uri)
                CorvidTextField(server, { server = it }, stringResource(Res.string.signin_server), Modifier.fillMaxWidth(), url)
                CorvidTextField(issuer, { issuer = it }, stringResource(Res.string.signin_issuer), Modifier.fillMaxWidth(), url)
            }
            discovery != null -> Confirmation(discovery)
            else -> {
                val keyboard = KeyboardOptions(keyboardType = KeyboardType.Email)
                CorvidTextField(email, { email = it }, stringResource(Res.string.signin_email), Modifier.fillMaxWidth(), keyboard)
            }
        }
        (state as? SignInUiState.DiscoveryFailed)?.let { Problem(it.message.asString()) }
        (state as? SignInUiState.Failed)?.let { Problem(it.message.asString()) }
        if (advanced || discovery != null) {
            PrimaryButton(
                stringResource(if (signingIn) Res.string.signin_in_progress else Res.string.signin_submit),
                onClick = { viewModel.signIn(server, issuer) },
                enabled = !signingIn,
            )
        } else {
            val discovering = state is SignInUiState.Discovering
            PrimaryButton(
                stringResource(if (discovering) Res.string.signin_discovering else Res.string.signin_continue),
                onClick = { viewModel.discover(email) },
                enabled = !discovering,
            )
        }
        if (advanced) {
            PillButton(stringResource(Res.string.signin_use_email), icon = null, onClick = {
                advanced = false
                viewModel.reset()
            })
        } else {
            if (discovery != null) PillButton(stringResource(Res.string.signin_change), icon = null, onClick = viewModel::reset)
            PillButton(stringResource(Res.string.signin_advanced), icon = null, onClick = { advanced = true })
        }
    }
}

@Composable
private fun Confirmation(discovery: Discovery) {
    Text(
        stringResource(Res.string.signin_confirm, discovery.serverHost, discovery.issuerHost),
        style = CorvidTheme.type.name,
        color = CorvidTheme.colors.ink,
    )
}

@Composable
private fun Problem(message: String) {
    Text(message, style = CorvidTheme.type.meta, color = CorvidTheme.colors.danger)
}
