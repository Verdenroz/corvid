package com.verdenroz.corvid.feature.settings

import com.verdenroz.corvid.core.domain.MasterSignIn
import com.verdenroz.corvid.core.keyring.DeviceApprovalRepository
import com.verdenroz.corvid.core.keyring.DeviceKeyLookup
import com.verdenroz.corvid.core.keyring.DeviceKeyRepository
import com.verdenroz.corvid.core.keyring.DeviceRequest
import com.verdenroz.corvid.core.keyring.OpenOutcome
import com.verdenroz.corvid.core.keyring.PendingRequest
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.settings.resources.Res
import com.verdenroz.corvid.feature.settings.resources.approve_failed_gone
import com.verdenroz.corvid.feature.settings.resources.approve_failed_not_accepted
import com.verdenroz.corvid.feature.settings.resources.approve_failed_refused
import com.verdenroz.corvid.feature.settings.resources.approve_failed_signin
import com.verdenroz.corvid.feature.settings.resources.approve_failed_upstream
import com.verdenroz.corvid.feature.settings.resources.approve_not_a_link
import kotlinx.coroutines.flow.first
import org.publicvalue.multiplatform.oidc.OpenIdConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class Setup(
    val fake: FakeJmapServer,
    val repository: DeviceApprovalRepository,
    val viewModel: ApproveDeviceViewModel,
    val request: DeviceRequest,
    val signIns: () -> Int,
)

private suspend fun ViewModelTestScope.setup(
    token: String = "master-token",
    signIn: suspend () -> String = { token },
): Setup {
    val fake = FakeJmapServer()
    val repository = DeviceApprovalRepository(fake.client())
    val request = assertIs<OpenOutcome.Opened>(repository.open("laptop-1")).request
    var signIns = 0
    val viewModel =
        track(
            ApproveDeviceViewModel(
                repository,
                object : MasterSignIn {
                    override suspend fun <T> withAdminToken(use: suspend (String) -> T): T {
                        signIns++
                        return use(signIn())
                    }
                },
            ),
        )
    return Setup(fake, repository, viewModel, request) { signIns }
}

private suspend fun ApproveDeviceViewModel.settled(): ApproveDeviceState = state.first { !it.busy && it.step != ApproveStep.Editing }

private suspend fun ApproveDeviceViewModel.loaded(): PendingList = state.first { it.pending != PendingList.Loading }.pending

private fun ApproveDeviceViewModel.fill(request: DeviceRequest) {
    setId(request.id)
    setCode(request.code)
}

class ApproveDeviceViewModelTest {
    @Test
    fun `an approval signs in as the admin, sends the token once and the device can then read its key`() =
        runViewModelTest {
            val s = setup()
            s.viewModel.fill(s.request)

            s.viewModel.approve()
            val done = s.viewModel.settled()

            assertEquals(ApproveStep.Approved(s.request.id.take(8)), done.step)
            assertEquals(1, s.signIns())
            assertEquals(listOf("master-token"), s.fake.devices.approvals.map { it.master })
            assertIs<DeviceKeyLookup.Found>(DeviceKeyRepository(s.fake.client()).fetch("laptop-1"))
        }

    @Test
    fun `approving needs an id and a six digit code`() =
        runViewModelTest {
            val s = setup()

            assertFalse(s.viewModel.state.value.canApprove)
            s.viewModel.setId(s.request.id)
            s.viewModel.setCode("12345")
            assertFalse(s.viewModel.state.value.canApprove)
            s.viewModel.setCode("123456")
            assertTrue(s.viewModel.state.value.canApprove)
            s.viewModel.approve()
        }

    @Test
    fun `a code typed with other characters keeps only its digits`() =
        runViewModelTest {
            val s = setup()

            s.viewModel.setCode("04 29-17x9")

            assertEquals("042917", s.viewModel.state.value.code)
        }

    @Test
    fun `a scanned link fills the id and the code`() =
        runViewModelTest {
            val s = setup()

            s.viewModel.scanned("corvid-approve:abc123?code=042917")

            val state = s.viewModel.state.value
            assertEquals("abc123", state.id)
            assertEquals("042917", state.code)
            assertTrue(state.typingId)
        }

    @Test
    fun `a scan that is not an approval link says so and fills nothing`() =
        runViewModelTest {
            val s = setup()

            s.viewModel.scanned("https://example.com")

            assertEquals(ApproveStep.Failed(uiText(Res.string.approve_not_a_link)), s.viewModel.state.value.step)
            assertEquals("", s.viewModel.state.value.id)
        }

    @Test
    fun `a cancelled passkey sign-in sends nothing to the server`() =
        runViewModelTest {
            val s = setup(signIn = { throw OpenIdConnectException.AuthenticationCancelled("cancelled") })
            s.viewModel.fill(s.request)

            s.viewModel.approve()

            assertEquals(ApproveStep.Failed(uiText(Res.string.approve_failed_signin)), s.viewModel.settled().step)
            assertEquals(emptyList(), s.fake.devices.approvals)
        }

    @Test
    fun `a token the server does not accept reads as not accepted`() =
        runViewModelTest {
            val s = setup(token = "stale")
            s.viewModel.fill(s.request)

            s.viewModel.approve()

            assertEquals(ApproveStep.Failed(uiText(Res.string.approve_failed_not_accepted)), s.viewModel.settled().step)
        }

    @Test
    fun `a wrong code reads as refused`() =
        runViewModelTest {
            val s = setup()
            s.viewModel.setId(s.request.id)
            s.viewModel.setCode(if (s.request.code == "000000") "000001" else "000000")

            s.viewModel.approve()

            assertEquals(ApproveStep.Failed(uiText(Res.string.approve_failed_refused)), s.viewModel.settled().step)
        }

    @Test
    fun `an expired request reads as gone`() =
        runViewModelTest {
            val s = setup()
            s.fake.devices.expire(s.request.id)
            s.viewModel.fill(s.request)

            s.viewModel.approve()

            assertEquals(ApproveStep.Failed(uiText(Res.string.approve_failed_gone)), s.viewModel.settled().step)
        }

    @Test
    fun `a server that cannot make the key reads as upstream failure`() =
        runViewModelTest {
            val s = setup()
            s.fake.devices.upstreamFails = true
            s.viewModel.fill(s.request)

            s.viewModel.approve()

            assertEquals(ApproveStep.Failed(uiText(Res.string.approve_failed_upstream)), s.viewModel.settled().step)
        }

    @Test
    fun `editing a field clears the last outcome`() =
        runViewModelTest {
            val s = setup(token = "stale")
            s.viewModel.fill(s.request)
            s.viewModel.approve()
            s.viewModel.settled()

            s.viewModel.setCode("111111")

            assertEquals(ApproveStep.Editing, s.viewModel.state.value.step)
        }

    @Test
    fun `the open requests are listed on open`() =
        runViewModelTest {
            val s = setup()

            val listed = assertIs<PendingList.Listed>(s.viewModel.loaded())
            assertEquals(listOf(s.request.id), listed.requests.map { it.id })
            assertEquals(listOf("laptop-1"), listed.requests.map { it.name })
        }

    @Test
    fun `picking a request sets the id and approving sends it with the typed code`() =
        runViewModelTest {
            val s = setup()
            s.viewModel.loaded()
            val other = assertIs<OpenOutcome.Opened>(s.repository.open("tablet")).request
            s.viewModel.refresh()
            val listed = assertIs<PendingList.Listed>(s.viewModel.loaded())
            assertEquals(listOf(other.id, s.request.id), listed.requests.map { it.id })

            s.viewModel.pick(listed.requests.first { it.name == "tablet" })
            s.viewModel.setCode(other.code)
            s.viewModel.approve()
            s.viewModel.settled()

            assertEquals(listOf(other.id), s.fake.devices.approvals.map { it.id })
            assertEquals(listOf(other.code), s.fake.devices.approvals.map { it.code })
        }

    @Test
    fun `an approved request leaves the list`() =
        runViewModelTest {
            val s = setup()
            s.viewModel.fill(s.request)

            s.viewModel.loaded()
            s.viewModel.approve()
            s.viewModel.settled()

            s.viewModel.state.first { it.pending == PendingList.Listed(emptyList()) }
        }

    @Test
    fun `no waiting device lists as empty`() =
        runViewModelTest {
            val s = setup()
            s.viewModel.loaded()
            s.fake.devices.expire(s.request.id)

            s.viewModel.refresh()

            assertEquals(PendingList.Listed(emptyList<PendingRequest>()), s.viewModel.loaded())
        }

    @Test
    fun `a server with device approval off lists as off`() =
        runViewModelTest {
            val s = setup()
            s.viewModel.loaded()
            s.fake.devices.off = true

            s.viewModel.refresh()

            assertEquals(PendingList.Off, s.viewModel.loaded())
        }
}
