package com.verdenroz.corvid.app

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.verdenroz.corvid.appcommon.resources.Res
import com.verdenroz.corvid.appcommon.resources.unreachable_server
import com.verdenroz.corvid.core.data.outbox.OutboxSealer
import com.verdenroz.corvid.core.designsystem.CorvidTheme
import com.verdenroz.corvid.core.model.Draft
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.prefs.OutboxDirectory
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.core.ui.LockedPane
import com.verdenroz.corvid.core.ui.UnreachablePane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val DEVICE_KEY = ByteArray(33) { (it * 7 + 1).toByte() }

private class OfflineRig(withKey: Boolean = true) {
    val directory: File = createTempDirectory().toFile()
    val preferences = DevicePreferences(InMemoryPreferencesStore(), CoroutineScope(Dispatchers.Unconfined), DeviceSettings())
    val outbox = OfflineOutbox(preferences, OutboxDirectory(directory))

    init {
        if (withKey) runBlocking { preferences.setOutboxKey(OutboxSealer.keyFor(DEVICE_KEY).encoded()) }
    }

    fun allBytes() = directory.walkTopDown().filter { it.isFile }.joinToString("\n") { String(it.readBytes(), Charsets.ISO_8859_1) }
}

private val LOCKED = AppLockState.Locked

@OptIn(ExperimentalTestApi::class)
class OfflineGateTest {
    private fun ComposeUiTest.show(
        rig: OfflineRig,
        state: AppLockState = LOCKED,
        canWrite: Boolean = true,
    ) = setContent {
        CorvidTheme(darkTheme = false) {
            OfflineGate(state, "mail.example.test", canWrite, rig.outbox, {}) { waiting, onWrite ->
                when (state) {
                    is AppLockState.Unreachable -> UnreachablePane(state.reason, {}, {}, waiting = waiting, onWrite = onWrite)
                    else -> LockedPane("mail.example.test", "phone", null, onUnlock = {}, waiting = waiting, onWrite = onWrite)
                }
            }
        }
    }

    @Test
    fun `writing a message is offered once a sealing key is stored`() =
        runComposeUiTest {
            show(OfflineRig())

            onNodeWithText("Write a message").assertExists()
        }

    @Test
    fun `writing a message is not offered without a sealing key`() =
        runComposeUiTest {
            show(OfflineRig(withKey = false), canWrite = false)

            onNodeWithText("Write a message").assertDoesNotExist()
        }

    @Test
    fun `writing a message is offered from the unreachable pane too`() =
        runComposeUiTest {
            show(OfflineRig(), AppLockState.Unreachable(uiText(Res.string.unreachable_server)))

            onNodeWithText("Write a message").assertExists()
        }

    @Test
    fun `a message written while locked is sealed on disk and counted on the lock pane`() =
        runComposeUiTest {
            val rig = OfflineRig()
            show(rig)

            onNodeWithText("Write a message").performClick()
            onNode(hasSetTextAction() and hasText("To")).performTextInput("zed-recipient@example.test")
            onNode(hasSetTextAction() and hasText("Subject")).performTextInput("quiet-subject")
            onNode(hasSetTextAction() and hasText("Message")).performTextInput("plain-body-text")
            onNodeWithText("Send").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Done").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Done").performClick()
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("1 message waiting").fetchSemanticsNodes().isNotEmpty() }

            val bytes = rig.allBytes()
            listOf("zed-recipient", "quiet-subject", "plain-body-text").forEach { assertFalse(it in bytes, it) }
            assertTrue(rig.directory.listFiles().orEmpty().any { it.name.endsWith(".msg") })
        }

    @Test
    fun `the lock pane shows how many wait and no message text`() =
        runComposeUiTest {
            val rig = OfflineRig()
            runBlocking {
                rig.outbox.write(Draft(subject = "quiet-subject", body = "plain-body-text"), "mail.example.test")
                rig.outbox.write(Draft(subject = "quiet-subject", body = "plain-body-text"), "mail.example.test")
            }
            show(rig)

            waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("2 messages waiting").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("quiet-subject", substring = true).assertDoesNotExist()
        }

    @Test
    fun `the writer is dropped with its text once the gate unlocks`() =
        runComposeUiTest {
            val rig = OfflineRig()
            var state: AppLockState by mutableStateOf(LOCKED)
            setContent {
                CorvidTheme(darkTheme = false) {
                    OfflineGate(state, "mail.example.test", true, rig.outbox, {}) { _, onWrite ->
                        if (state == LOCKED) {
                            LockedPane(
                                "mail.example.test",
                                "phone",
                                null,
                                onUnlock = {},
                                onWrite = onWrite,
                            )
                        } else {
                            Text("mailbox")
                        }
                    }
                }
            }

            onNodeWithText("Write a message").performClick()
            onNode(hasSetTextAction() and hasText("Message")).performTextInput("plain-body-text")
            state = AppLockState.Connecting(needsDeviceName = false)
            waitForIdle()
            state = LOCKED
            waitForIdle()
            onNode(hasSetTextAction() and hasText("plain-body-text")).assertExists()
            state = AppLockState.Unlocked(AccountHosts("mail.example.test"))
            waitForIdle()
            state = LOCKED
            waitForIdle()

            onNodeWithText("Write a message").performClick()
            onNode(hasSetTextAction() and hasText("plain-body-text")).assertDoesNotExist()
        }
}
