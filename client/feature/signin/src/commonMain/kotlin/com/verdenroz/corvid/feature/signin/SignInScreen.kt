package com.verdenroz.corvid.feature.signin

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.designsystem.component.CorvidTextField
import com.verdenroz.corvid.core.designsystem.component.PrimaryButton
import com.verdenroz.corvid.core.text.asString
import com.verdenroz.corvid.core.ui.StatePane
import com.verdenroz.corvid.feature.signin.resources.Res
import com.verdenroz.corvid.feature.signin.resources.signin_detail
import com.verdenroz.corvid.feature.signin.resources.signin_in_progress
import com.verdenroz.corvid.feature.signin.resources.signin_issuer
import com.verdenroz.corvid.feature.signin.resources.signin_server
import com.verdenroz.corvid.feature.signin.resources.signin_submit
import com.verdenroz.corvid.feature.signin.resources.signin_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SignInScreen(viewModel: SignInViewModel = koinViewModel()) {
    var server by rememberSaveable { mutableStateOf("") }
    var issuer by rememberSaveable { mutableStateOf("") }
    val state by viewModel.state.collectAsState()
    val signingIn = state is SignInUiState.SigningIn
    val url = KeyboardOptions(keyboardType = KeyboardType.Uri)

    StatePane(stringResource(Res.string.signin_title), detail = stringResource(Res.string.signin_detail)) {
        CorvidTextField(server, { server = it }, stringResource(Res.string.signin_server), Modifier.fillMaxWidth(), url)
        CorvidTextField(issuer, { issuer = it }, stringResource(Res.string.signin_issuer), Modifier.fillMaxWidth(), url)
        (state as? SignInUiState.Failed)?.let { failed ->
            Text(failed.message.asString(), style = CorvidTheme.type.meta, color = CorvidTheme.colors.danger)
        }
        PrimaryButton(
            stringResource(if (signingIn) Res.string.signin_in_progress else Res.string.signin_submit),
            onClick = { viewModel.signIn(server, issuer) },
            enabled = !signingIn,
        )
    }
}
