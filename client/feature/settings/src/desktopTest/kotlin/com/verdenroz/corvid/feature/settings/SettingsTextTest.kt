package com.verdenroz.corvid.feature.settings

import com.verdenroz.corvid.core.testing.composedResourceEnvironment
import com.verdenroz.corvid.core.text.resolve
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.settings_auto_lock_option
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTextTest {
    @Test
    fun `an auto-lock choice reads as its minutes`() =
        runTest {
            assertEquals("15 min", uiText(Res.string.settings_auto_lock_option, 15).resolve(composedResourceEnvironment()))
        }
}
