package com.verdenroz.corvid.feature.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class RecipientInputTest {
    private val actions = RecipientActions(onEdit = { _, _, _ -> }, onMove = {}, onAccept = {}, onDismiss = { false })

    @Test
    fun `a recipient field is named by its label, so Bcc reads apart from To`() =
        runComposeUiTest {
            setContent {
                CorvidTheme(
                    darkTheme = false,
                ) { RecipientInput("Bcc", RecipientField.Bcc, "ana@x.test", suggestions = null, actions = actions) }
            }

            onNode(hasSetTextAction() and hasText("Bcc")).assertExists()
            onNode(hasSetTextAction() and hasText("ana@x.test")).assertExists()
        }
}
