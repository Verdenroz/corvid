package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.jmap.JmapException
import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.text.resources.Res
import com.verdenroz.corvid.core.text.resources.server_unreachable
import com.verdenroz.corvid.core.text.uiText

fun JmapException.userMessage(): UiText = uiText(Res.string.server_unreachable)
