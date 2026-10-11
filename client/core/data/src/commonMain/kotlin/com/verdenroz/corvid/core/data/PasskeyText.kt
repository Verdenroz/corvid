package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.auth.AuthFailure
import com.verdenroz.corvid.core.auth.PasskeyException
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.passkey_already_enrolled
import com.verdenroz.corvid.core.text.resources.passkey_cancelled
import com.verdenroz.corvid.core.text.resources.passkey_code_bad
import com.verdenroz.corvid.core.text.resources.passkey_none
import com.verdenroz.corvid.core.text.resources.passkey_not_associated
import com.verdenroz.corvid.core.text.resources.passkey_not_enrolled
import com.verdenroz.corvid.core.text.resources.passkey_off
import com.verdenroz.corvid.core.text.resources.passkey_refused
import com.verdenroz.corvid.core.text.resources.passkey_server_refused
import com.verdenroz.corvid.core.text.resources.passkey_unsupported
import com.verdenroz.corvid.core.text.uiText

fun PasskeyException.userMessage(): UiText =
    when (this) {
        PasskeyException.Cancelled -> uiText(Res.string.passkey_cancelled)
        PasskeyException.NoCredential -> uiText(Res.string.passkey_none)
        PasskeyException.NotAssociated -> uiText(Res.string.passkey_not_associated)
        PasskeyException.Unsupported -> uiText(Res.string.passkey_unsupported)
        is PasskeyException.Refused -> uiText(Res.string.passkey_refused, UiText.FromServer(message.orEmpty()))
    }

fun AuthFailure.userMessage(): UiText =
    when (this) {
        AuthFailure.BadCode -> uiText(Res.string.passkey_code_bad)
        AuthFailure.Refused -> uiText(Res.string.passkey_server_refused)
        AuthFailure.NotEnrolled -> uiText(Res.string.passkey_not_enrolled)
        AuthFailure.Off -> uiText(Res.string.passkey_off)
        AuthFailure.AlreadyEnrolled -> uiText(Res.string.passkey_already_enrolled)
    }
