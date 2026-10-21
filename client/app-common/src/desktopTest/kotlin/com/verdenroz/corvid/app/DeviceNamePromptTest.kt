package com.verdenroz.corvid.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DeviceNamePromptTest {
    @Test
    fun `enter in the name field submits a valid name`() =
        runComposeUiTest {
            val submitted = mutableListOf<String>()
            setContent { CorvidTheme(darkTheme = false) { DeviceNamePrompt(onSubmit = { submitted += it }) } }
            val field = onNode(hasSetTextAction())

            field.requestFocus()
            field.performTextInput("laptop-1")
            field.performKeyInput { pressKey(Key.Enter) }

            assertEquals(listOf("laptop-1"), submitted)
        }

    @Test
    fun `enter in the name field holds back a name the button would refuse`() =
        runComposeUiTest {
            val submitted = mutableListOf<String>()
            setContent { CorvidTheme(darkTheme = false) { DeviceNamePrompt(onSubmit = { submitted += it }) } }
            val field = onNode(hasSetTextAction())

            field.requestFocus()
            field.performTextInput("Laptop One")
            field.performKeyInput { pressKey(Key.Enter) }

            assertEquals(emptyList(), submitted)
        }
}
