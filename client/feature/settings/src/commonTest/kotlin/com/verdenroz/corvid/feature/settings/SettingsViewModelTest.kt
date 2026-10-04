package com.verdenroz.corvid.feature.settings

import com.verdenroz.corvid.core.prefs.AutoLock
import com.verdenroz.corvid.core.prefs.DevicePreferences
import com.verdenroz.corvid.core.prefs.DeviceSettings
import com.verdenroz.corvid.core.prefs.ThemeChoice
import com.verdenroz.corvid.core.testing.InMemoryPreferencesStore
import com.verdenroz.corvid.core.testing.ViewModelTestScope
import com.verdenroz.corvid.core.testing.runViewModelTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class Calls {
    var locks = 0
    var signOuts = 0
}

private fun ViewModelTestScope.viewModel(calls: Calls = Calls()): SettingsViewModel {
    val preferences = DevicePreferences(InMemoryPreferencesStore(), CoroutineScope(Dispatchers.Main), DeviceSettings())
    return track(SettingsViewModel(preferences, onLock = { calls.locks++ }, onSignOut = { calls.signOuts++ }))
}

class SettingsViewModelTest {
    @Test
    fun `choosing a theme and an auto-lock shows them in the state`() =
        runViewModelTest {
            val viewModel = viewModel()

            viewModel.selectTheme(ThemeChoice.Dark)
            viewModel.selectAutoLock(AutoLock.FifteenMinutes)

            assertEquals(DeviceSettings(ThemeChoice.Dark, AutoLock.FifteenMinutes), viewModel.state.value.settings)
        }

    @Test
    fun `lock now calls the lock action once`() =
        runViewModelTest {
            val calls = Calls()
            viewModel(calls).lockNow()

            assertEquals(1, calls.locks)
        }

    @Test
    fun `sign out asks first and only signs out once confirmed`() =
        runViewModelTest {
            val calls = Calls()
            val viewModel = viewModel(calls)

            viewModel.askSignOut()
            assertTrue(viewModel.state.value.confirmingSignOut)
            assertEquals(0, calls.signOuts)

            viewModel.confirmSignOut()
            assertEquals(1, calls.signOuts)
        }

    @Test
    fun `cancelling the sign-out question keeps the device signed in`() =
        runViewModelTest {
            val calls = Calls()
            val viewModel = viewModel(calls)

            viewModel.askSignOut()
            viewModel.keepSignedIn()

            assertFalse(viewModel.state.value.confirmingSignOut)
            assertEquals(0, calls.signOuts)
        }
}
