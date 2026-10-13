package com.verdenroz.corvid.feature.settings

import com.verdenroz.corvid.core.auth.AuthFailure
import com.verdenroz.corvid.core.auth.PasskeyException
import com.verdenroz.corvid.core.auth.PasskeyRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.domain.ApproveWithKey
import com.verdenroz.corvid.core.domain.EnrollPasskey
import com.verdenroz.corvid.core.model.ServerAddress
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.FakePasskey
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.passkeys_failed_last
import com.verdenroz.corvid.feature.settings.resources.passkeys_failed_not_accepted
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

private val SERVER = ServerAddress("https://mail.example.test")

private class Rig(
    val fake: FakeJmapServer,
    val viewModel: PasskeysViewModel,
    val enrolled: MutableList<Pair<ServerAddress, String>>,
    val signIns: () -> Int,
)

private suspend fun ViewModelTestScope.rig(
    token: String = "master-token",
    signIn: suspend () -> String = { token },
    enroll: suspend (String) -> Boolean = { false },
): Rig {
    val fake = FakeJmapServer()
    fake.passkeys.passkeys += FakePasskey("a")
    fake.passkeys.passkeys += FakePasskey("b", backupEligible = true)
    fake.passkeys.passkeys += FakePasskey("m", approves = true)
    var signIns = 0
    val enrolled = mutableListOf<Pair<ServerAddress, String>>()
    val viewModel =
        track(
            PasskeysViewModel(
                PasskeyRepository(fake.client()),
                object : ApproveWithKey {
                    override suspend fun <T> withAdminToken(use: suspend (String) -> T): T {
                        signIns++
                        return use(signIn())
                    }
                },
                EnrollPasskey { server, code ->
                    enrolled += server to code
                    enroll(code)
                },
                SERVER,
            ),
        )
    return Rig(fake, viewModel, enrolled) { signIns }
}

private suspend fun PasskeysViewModel.loaded(): PasskeyList = state.first { it.list != PasskeyList.Loading }.list

private suspend fun PasskeysViewModel.settled(): PasskeysState = state.first { !it.busy && it.step != PasskeyStep.Idle }

class PasskeysViewModelTest {
    @Test
    fun `the passkeys the server holds are listed`() =
        runViewModelTest {
            val r = rig()

            val list = assertIs<PasskeyList.Listed>(r.viewModel.loaded())

            assertEquals(listOf("a", "b", "m"), list.passkeys.map { it.id })
        }

    @Test
    fun `a server with passkeys switched off says so`() =
        runViewModelTest {
            val r = rig()
            r.fake.passkeys.off = true

            r.viewModel.refresh()

            assertEquals(PasskeyList.Off, r.viewModel.state.first { it.list == PasskeyList.Off }.list)
        }

    @Test
    fun `a code for another device costs one approver passkey and is shown once`() =
        runViewModelTest {
            val r = rig()

            r.viewModel.makeCode(true)
            val done = r.viewModel.settled()

            assertEquals(PasskeyStep.Code(true, "ABCD-EFGH-IJKL-MNOP", Instant.parse("2030-01-02T00:00:00Z")), done.step)
            assertEquals(1, r.signIns())
            assertEquals(listOf(true), r.fake.passkeys.minted)
            assertEquals(listOf<String?>("master-token"), r.fake.passkeys.masters)
        }

    @Test
    fun `a dismissed approver prompt makes nothing`() =
        runViewModelTest {
            val r = rig(signIn = { throw PasskeyException.Cancelled })

            r.viewModel.makeCode(false)
            val done = r.viewModel.settled()

            assertEquals(PasskeyStep.Failed(PasskeyException.Cancelled.userMessage()), done.step)
            assertEquals(emptyList(), r.fake.passkeys.minted)
        }

    @Test
    fun `an admin token the server does not accept is reported`() =
        runViewModelTest {
            val r = rig(token = "stale")

            r.viewModel.makeCode(false)

            assertEquals(PasskeyStep.Failed(uiText(Res.string.passkeys_failed_not_accepted)), r.viewModel.settled().step)
        }

    @Test
    fun `adding a passkey here makes a code and enrolls it on this device at this server`() =
        runViewModelTest {
            val r = rig()

            r.viewModel.addHere(false)
            val done = r.viewModel.settled()

            assertEquals(PasskeyStep.Added(false), done.step)
            assertEquals(listOf(SERVER to "ABCD-EFGH-IJKL-MNOP"), r.enrolled)
            assertEquals(listOf(false), r.fake.passkeys.minted)
            assertEquals(1, r.signIns())
        }

    @Test
    fun `a dismissed enrollment prompt is reported and the code is simply left unused`() =
        runViewModelTest {
            val r = rig(enroll = { throw PasskeyException.Cancelled })

            r.viewModel.addHere(false)

            assertEquals(PasskeyStep.Failed(PasskeyException.Cancelled.userMessage()), r.viewModel.settled().step)
        }

    @Test
    fun `a passkey the server already holds is reported`() =
        runViewModelTest {
            val r = rig(enroll = { throw AuthFailure.AlreadyEnrolled })

            r.viewModel.addHere(false)

            assertEquals(PasskeyStep.Failed(AuthFailure.AlreadyEnrolled.userMessage()), r.viewModel.settled().step)
        }

    @Test
    fun `removing asks first and keeping it asks nothing of the master`() =
        runViewModelTest {
            val r = rig()
            r.viewModel.loaded()

            r.viewModel.askRemove("a")
            assertEquals("a", r.viewModel.state.value.removing)
            r.viewModel.keep()

            assertEquals(null, r.viewModel.state.value.removing)
            assertEquals(0, r.signIns())
        }

    @Test
    fun `a confirmed removal costs one master passkey and drops the row`() =
        runViewModelTest {
            val r = rig()
            r.viewModel.loaded()

            r.viewModel.askRemove("a")
            r.viewModel.remove("a")
            val done = r.viewModel.settled()

            assertEquals(PasskeyStep.Removed, done.step)
            assertEquals(1, r.signIns())
            assertEquals(listOf("b", "m"), r.fake.passkeys.passkeys.map { it.id })
            assertEquals(null, done.removing)
        }

    @Test
    fun `the last passkey of a role stays and the reason is given`() =
        runViewModelTest {
            val r = rig()

            r.viewModel.remove("m")

            assertEquals(PasskeyStep.Failed(uiText(Res.string.passkeys_failed_last)), r.viewModel.settled().step)
            assertEquals(3, r.fake.passkeys.passkeys.size)
        }
}
