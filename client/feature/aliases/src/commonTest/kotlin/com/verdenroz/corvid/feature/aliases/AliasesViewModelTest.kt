package com.verdenroz.corvid.feature.aliases

import com.verdenroz.corvid.core.auth.PasskeyException
import com.verdenroz.corvid.core.data.AliasRepository
import com.verdenroz.corvid.core.data.userMessage
import com.verdenroz.corvid.core.domain.ApproveWithKey
import com.verdenroz.corvid.core.testing.FakeAlias
import com.verdenroz.corvid.core.testing.FakeJmapServer
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.client
import com.verdenroz.corvid.core.testing.runViewModelTest
import com.verdenroz.corvid.core.text.uiText
import com.verdenroz.corvid.feature.aliases.resources.Res
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_busy
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_cap
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_enabled_cap
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_gone
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_not_accepted
import com.verdenroz.corvid.feature.aliases.resources.aliases_failed_session
import com.verdenroz.corvid.feature.aliases.resources.aliases_off
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class Rig(
    val fake: FakeJmapServer,
    val viewModel: AliasesViewModel,
    val signIns: () -> Int,
)

private suspend fun ViewModelTestScope.rig(
    token: String = "master-token",
    signIn: suspend () -> String = { token },
    setup: FakeJmapServer.() -> Unit = {},
): Rig {
    val fake = FakeJmapServer()
    fake.aliases.aliases += FakeAlias("shop.abcd2345")
    fake.aliases.aliases += FakeAlias("news.wxyz6789", enabled = false)
    fake.setup()
    var signIns = 0
    val viewModel =
        track(
            AliasesViewModel(
                AliasRepository(fake.client()),
                object : ApproveWithKey {
                    override suspend fun <T> withAdminToken(use: suspend (String) -> T): T {
                        signIns++
                        return use(signIn())
                    }
                },
            ),
        )
    return Rig(fake, viewModel) { signIns }
}

private suspend fun AliasesViewModel.loaded(): AliasList = state.first { it.list != AliasList.Loading }.list

private suspend fun AliasesViewModel.settled(): AliasesState = state.first { !it.busy && it.step != AliasStep.Idle }

private suspend fun AliasesViewModel.names(): List<String> = assertIs<AliasList.Listed>(loaded()).aliases.map { it.name }

class AliasesViewModelTest {
    @Test
    fun `the aliases the server minted are listed in its order`() =
        runViewModelTest {
            val r = rig()

            assertEquals(listOf("shop.abcd2345", "news.wxyz6789"), r.viewModel.names())
        }

    @Test
    fun `an alias a deploy wiped stays listed as missing`() =
        runViewModelTest {
            val r = rig { aliases.aliases += FakeAlias("gone.abcd2345", enabled = false, missing = true) }

            val list = assertIs<AliasList.Listed>(r.viewModel.loaded())

            assertEquals(listOf(false, false, true), list.aliases.map { it.missing })
            assertEquals(false, list.aliases.last().enabled)
        }

    @Test
    fun `a server with aliases off says so`() =
        runViewModelTest {
            val r = rig { aliases.off = true }

            assertEquals(AliasList.Off, r.viewModel.loaded())
        }

    @Test
    fun `a server without the alias routes reads as aliases off`() =
        runViewModelTest {
            val r = rig { aliases.unrouted = true }

            assertEquals(AliasList.Off, r.viewModel.loaded())
        }

    @Test
    fun `a busy mail server fails the list with a reason to try again`() =
        runViewModelTest {
            val r = rig { aliases.busy = true }

            assertEquals(AliasList.Failed(uiText(Res.string.aliases_failed_busy)), r.viewModel.loaded())
        }

    @Test
    fun `the label keeps only lowercase letters, digits and dashes, up to twenty`() =
        runViewModelTest {
            val r = rig()

            r.viewModel.setLabel("My Shop_2!")
            assertEquals("myshop2", r.viewModel.state.value.label)

            r.viewModel.setLabel("a".repeat(25) + "-")
            assertEquals("a".repeat(20), r.viewModel.state.value.label)
        }

    @Test
    fun `a mint from a label costs no approver and lists the new alias`() =
        runViewModelTest {
            val r = rig()
            r.viewModel.loaded()

            r.viewModel.setLabel("bank")
            r.viewModel.mint()
            val done = r.viewModel.settled()

            assertEquals("bank.abcd2345@corvid.test", assertIs<AliasStep.Minted>(done.step).alias.address)
            assertEquals("", done.label)
            assertEquals(listOf("bank"), r.fake.aliases.labels)
            assertEquals(0, r.signIns())
            assertEquals(listOf("shop.abcd2345", "news.wxyz6789", "bank.abcd2345"), r.viewModel.names())
        }

    @Test
    fun `an empty label mints nothing`() =
        runViewModelTest {
            val r = rig()

            r.viewModel.mint()

            assertEquals(AliasStep.Idle, r.viewModel.state.value.step)
            assertEquals(emptyList(), r.fake.aliases.labels)
        }

    @Test
    fun `a mint past the server's cap keeps the label and gives the limit`() =
        runViewModelTest {
            val r = rig { aliases.atCap = true }

            r.viewModel.setLabel("bank")
            r.viewModel.mint()
            val done = r.viewModel.settled()

            assertEquals(AliasStep.Failed(uiText(Res.string.aliases_failed_cap)), done.step)
            assertEquals("bank", done.label)
        }

    @Test
    fun `a mint on a server with aliases off says so`() =
        runViewModelTest {
            val r = rig()
            r.viewModel.loaded()
            r.fake.aliases.off = true

            r.viewModel.setLabel("bank")
            r.viewModel.mint()

            assertEquals(AliasStep.Failed(uiText(Res.string.aliases_off)), r.viewModel.settled().step)
        }

    @Test
    fun `a mint while the mail server is busy asks for another try`() =
        runViewModelTest {
            val r = rig()
            r.viewModel.loaded()
            r.fake.aliases.busy = true

            r.viewModel.setLabel("bank")
            r.viewModel.mint()

            assertEquals(AliasStep.Failed(uiText(Res.string.aliases_failed_busy)), r.viewModel.settled().step)
        }

    @Test
    fun `a list the sentinel refuses the session for is reported as the session`() =
        runViewModelTest {
            val r = rig { aliases.dailyRefused = true }

            assertEquals(AliasList.Failed(uiText(Res.string.aliases_failed_session)), r.viewModel.loaded())
        }

    @Test
    fun `a mint the sentinel refuses the session for is reported as the session`() =
        runViewModelTest {
            val r = rig()
            r.viewModel.loaded()
            r.fake.aliases.dailyRefused = true

            r.viewModel.setLabel("bank")
            r.viewModel.mint()

            assertEquals(AliasStep.Failed(uiText(Res.string.aliases_failed_session)), r.viewModel.settled().step)
        }

    @Test
    fun `disabling asks first and keeping it asks nothing of the approver`() =
        runViewModelTest {
            val r = rig()
            r.viewModel.loaded()

            r.viewModel.askDisable("shop.abcd2345")
            assertEquals("shop.abcd2345", r.viewModel.state.value.disabling)
            r.viewModel.keep()

            assertEquals(null, r.viewModel.state.value.disabling)
            assertEquals(0, r.signIns())
        }

    @Test
    fun `a confirmed disable costs one approver passkey and sends its token`() =
        runViewModelTest {
            val r = rig()
            r.viewModel.loaded()

            r.viewModel.askDisable("shop.abcd2345")
            r.viewModel.disable("shop.abcd2345")
            val done = r.viewModel.settled()

            assertEquals(false, assertIs<AliasStep.Switched>(done.step).alias.enabled)
            assertEquals(null, done.disabling)
            assertEquals(1, r.signIns())
            assertEquals(listOf<String?>("master-token"), r.fake.aliases.masters)
            assertEquals(listOf(false, false), r.fake.aliases.aliases.map { it.enabled })
        }

    @Test
    fun `a disabled alias is enabled again behind the approver`() =
        runViewModelTest {
            val r = rig()
            r.viewModel.loaded()

            r.viewModel.enable("news.wxyz6789")
            val done = r.viewModel.settled()

            assertEquals(true, assertIs<AliasStep.Switched>(done.step).alias.enabled)
            assertEquals(1, r.signIns())
            assertEquals(listOf(true, true), r.fake.aliases.aliases.map { it.enabled })
        }

    @Test
    fun `enabling past the server's cap gives the limit and changes nothing`() =
        runViewModelTest {
            val r = rig { aliases.atCap = true }

            r.viewModel.enable("news.wxyz6789")

            assertEquals(AliasStep.Failed(uiText(Res.string.aliases_failed_enabled_cap)), r.viewModel.settled().step)
            assertEquals(listOf(true, false), r.fake.aliases.aliases.map { it.enabled })
        }

    @Test
    fun `an admin token the server does not accept is reported and changes nothing`() =
        runViewModelTest {
            val r = rig(token = "stale")

            r.viewModel.disable("shop.abcd2345")

            assertEquals(AliasStep.Failed(uiText(Res.string.aliases_failed_not_accepted)), r.viewModel.settled().step)
            assertEquals(listOf(true, false), r.fake.aliases.aliases.map { it.enabled })
        }

    @Test
    fun `a dismissed approver prompt switches nothing`() =
        runViewModelTest {
            val r = rig(signIn = { throw PasskeyException.Cancelled })

            r.viewModel.disable("shop.abcd2345")

            assertEquals(AliasStep.Failed(PasskeyException.Cancelled.userMessage()), r.viewModel.settled().step)
            assertEquals(emptyList(), r.fake.aliases.masters)
        }

    @Test
    fun `an alias the server no longer holds is gone and the list is read again`() =
        runViewModelTest {
            val r = rig()
            r.viewModel.loaded()
            r.fake.aliases.aliases.removeAt(0)

            r.viewModel.disable("shop.abcd2345")

            assertEquals(AliasStep.Failed(uiText(Res.string.aliases_failed_gone)), r.viewModel.settled().step)
            assertEquals(listOf("news.wxyz6789"), r.viewModel.names())
        }
}
