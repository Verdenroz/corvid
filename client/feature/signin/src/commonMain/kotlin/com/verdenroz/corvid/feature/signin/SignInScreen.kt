package com.verdenroz.corvid.feature.signin

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidTextField
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.designsystem.component.QuietLink
import com.verdenroz.corvid.core.domain.Discovery
import com.verdenroz.corvid.core.model.hostOf
import com.verdenroz.corvid.core.prefs.RememberedAccount
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.StatePane
import com.verdenroz.corvid.feature.signin.resources.Res
import com.verdenroz.corvid.feature.signin.resources.signin_advanced
import com.verdenroz.corvid.feature.signin.resources.signin_another
import com.verdenroz.corvid.feature.signin.resources.signin_as
import com.verdenroz.corvid.feature.signin.resources.signin_change
import com.verdenroz.corvid.feature.signin.resources.signin_confirm
import com.verdenroz.corvid.feature.signin.resources.signin_continue
import com.verdenroz.corvid.feature.signin.resources.signin_detail
import com.verdenroz.corvid.feature.signin.resources.signin_discovering
import com.verdenroz.corvid.feature.signin.resources.signin_email
import com.verdenroz.corvid.feature.signin.resources.signin_finishing
import com.verdenroz.corvid.feature.signin.resources.signin_in_progress
import com.verdenroz.corvid.feature.signin.resources.signin_remembered
import com.verdenroz.corvid.feature.signin.resources.signin_remembered_detail
import com.verdenroz.corvid.feature.signin.resources.signin_server
import com.verdenroz.corvid.feature.signin.resources.signin_server_detail
import com.verdenroz.corvid.feature.signin.resources.signin_set_up
import com.verdenroz.corvid.feature.signin.resources.signin_set_up_back
import com.verdenroz.corvid.feature.signin.resources.signin_set_up_code
import com.verdenroz.corvid.feature.signin.resources.signin_set_up_detail
import com.verdenroz.corvid.feature.signin.resources.signin_set_up_progress
import com.verdenroz.corvid.feature.signin.resources.signin_set_up_submit
import com.verdenroz.corvid.feature.signin.resources.signin_set_up_title
import com.verdenroz.corvid.feature.signin.resources.signin_submit
import com.verdenroz.corvid.feature.signin.resources.signin_title
import com.verdenroz.corvid.feature.signin.resources.signin_use_email
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SignInScreen(viewModel: SignInViewModel = koinViewModel()) {
    var another by rememberSaveable { mutableStateOf(false) }
    var settingUp by rememberSaveable(stateSaver = AccountSaver) { mutableStateOf<RememberedAccount?>(null) }
    val remembered by viewModel.remembered.collectAsState()
    val account = remembered.takeUnless { another }
    val toggle = {
        another = !another
        viewModel.reset()
    }
    val setUp = { target: RememberedAccount ->
        viewModel.reset()
        settingUp = target
    }
    val target = settingUp
    when {
        target != null ->
            SetUp(viewModel, target) {
                viewModel.reset()
                settingUp = null
            }
        account != null -> RememberedSignIn(viewModel, account, toggle, setUp)
        else -> AccountEntry(viewModel, remembered, toggle, setUp)
    }
}

@Composable
private fun RememberedSignIn(
    viewModel: SignInViewModel,
    account: RememberedAccount,
    onAnother: () -> Unit,
    onSetUp: (RememberedAccount) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val signingIn = state is SignInUiState.SigningIn
    val discovering = state is SignInUiState.Discovering
    val failed = state as? SignInUiState.Failed
    StatePane(stringResource(Res.string.signin_title), detail = stringResource(Res.string.signin_remembered_detail)) {
        Text(
            stringResource(Res.string.signin_remembered, account.label),
            style = CorvidTheme.type.name,
            color = CorvidTheme.colors.ink,
        )
        failed?.let { Problem(it.message.asString()) }
        if (failed?.hint == FailureHint.SetUp) {
            PrimaryButton(stringResource(Res.string.signin_set_up), onClick = { onSetUp(account) })
        } else {
            PrimaryButton(
                stringResource(if (signingIn) Res.string.signin_in_progress else Res.string.signin_submit),
                onClick = { viewModel.setUp(account, "") },
                enabled = !signingIn,
            )
            QuietLink(stringResource(Res.string.signin_set_up), onClick = { onSetUp(account) })
        }
        QuietLink(stringResource(Res.string.signin_another), onClick = onAnother)
    }
}

@Composable
private fun SetUp(
    viewModel: SignInViewModel,
    account: RememberedAccount,
    onBack: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val state by viewModel.state.collectAsState()
    val working = state is SignInUiState.SigningIn || state is SignInUiState.FinishingSetup
    val failed = state as? SignInUiState.Failed

    LaunchedEffect(failed) {
        if (failed?.hint == FailureHint.NewCode) code = ""
    }

    val canSetUp = !working && code.isNotBlank()
    StatePane(stringResource(Res.string.signin_set_up_title), detail = stringResource(Res.string.signin_set_up_detail)) {
        CorvidTextField(code, { code = it }, stringResource(Res.string.signin_set_up_code), Modifier.fillMaxWidth(), onSubmit = {
            if (canSetUp) viewModel.setUp(account, code)
        })
        failed?.let { Problem(it.message.asString()) }
        PrimaryButton(
            stringResource(setUpLabel(state)),
            onClick = { viewModel.setUp(account, code) },
            enabled = canSetUp,
        )
        QuietLink(stringResource(Res.string.signin_set_up_back), onClick = onBack)
    }
}

private fun setUpLabel(state: SignInUiState) =
    when (state) {
        is SignInUiState.SigningIn -> Res.string.signin_set_up_progress
        SignInUiState.FinishingSetup -> Res.string.signin_finishing
        else -> Res.string.signin_set_up_submit
    }

@Composable
private fun AccountEntry(
    viewModel: SignInViewModel,
    remembered: RememberedAccount?,
    onRemembered: () -> Unit,
    onSetUp: (RememberedAccount) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.state.collectAsState()
    val signingIn = state is SignInUiState.SigningIn
    val discovering = state is SignInUiState.Discovering

    LaunchedEffect(state) {
        when (val current = state) {
            is SignInUiState.Discovered -> server = current.discovery.server.baseUrl
            is SignInUiState.DiscoveryFailed -> advanced = true
            else -> Unit
        }
    }

    val detail = if (advanced) Res.string.signin_server_detail else Res.string.signin_detail
    StatePane(stringResource(Res.string.signin_title), detail = stringResource(detail)) {
        val discovery = state.discovery
        when {
            advanced -> {
                val url = KeyboardOptions(keyboardType = KeyboardType.Uri)
                CorvidTextField(
                    server,
                    { server = it },
                    stringResource(
                        Res.string.signin_server,
                    ),
                    Modifier.fillMaxWidth(),
                    url,
                    onSubmit = {
                        if (!signingIn) viewModel.signIn(server)
                    },
                )
            }
            discovery != null -> Confirmation(discovery)
            else -> {
                val keyboard = KeyboardOptions(keyboardType = KeyboardType.Email)
                CorvidTextField(
                    email,
                    { email = it },
                    stringResource(
                        Res.string.signin_email,
                    ),
                    Modifier.fillMaxWidth(),
                    keyboard,
                    onSubmit = {
                        if (!discovering) viewModel.discover(email)
                    },
                )
            }
        }
        (state as? SignInUiState.DiscoveryFailed)?.let { Problem(it.message.asString()) }
        (state as? SignInUiState.Failed)?.let { Problem(it.message.asString()) }
        if (advanced || discovery != null) {
            PrimaryButton(
                stringResource(if (signingIn) Res.string.signin_in_progress else Res.string.signin_submit),
                onClick = { if (advanced || discovery == null) viewModel.signIn(server) else viewModel.confirm(discovery) },
                enabled = !signingIn,
            )
            val account = discovery?.let { RememberedAccount(it.server.baseUrl, it.email) } ?: RememberedAccount(server.trimEnd('/'))
            QuietLink(stringResource(Res.string.signin_set_up), onClick = { onSetUp(account) })
        } else {
            PrimaryButton(
                stringResource(if (discovering) Res.string.signin_discovering else Res.string.signin_continue),
                onClick = { viewModel.discover(email) },
                enabled = !discovering,
            )
        }
        if (advanced) {
            QuietLink(stringResource(Res.string.signin_use_email), onClick = {
                advanced = false
                viewModel.reset()
            })
        } else {
            if (discovery != null) {
                QuietLink(stringResource(Res.string.signin_change), onClick = viewModel::reset)
            } else {
                QuietLink(stringResource(Res.string.signin_advanced), onClick = { advanced = true })
            }
        }
        remembered?.let { QuietLink(stringResource(Res.string.signin_as, it.label), onClick = onRemembered) }
    }
}

private val RememberedAccount.label: String get() = email ?: hostOf(server)

private val AccountSaver: Saver<RememberedAccount?, List<String>> =
    Saver(
        save = { account -> account?.let { listOf(it.server, it.email.orEmpty()) } },
        restore = { (server, email) -> RememberedAccount(server, email.ifEmpty { null }) },
    )

@Composable
private fun Confirmation(discovery: Discovery) {
    Text(
        stringResource(Res.string.signin_confirm, discovery.serverHost),
        style = CorvidTheme.type.name,
        color = CorvidTheme.colors.ink,
    )
}

@Composable
private fun Problem(message: String) {
    Text(message, style = CorvidTheme.type.meta, color = CorvidTheme.colors.danger)
}
