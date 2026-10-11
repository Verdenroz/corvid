package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal data class Place(val depth: Int, val home: Route)

/**
 * Opening a screen or switching tab leaves the list behind; going back does not, so the notice from an
 * action taken in the reader still shows on the list it returns to.
 */
internal fun leftScreen(
    before: Place,
    after: Place,
): Boolean = after.depth > before.depth || after.home != before.home

/** Ticks and the last action's notice belong to the screen they were made on. */
@Composable
internal fun DropOnLeave(
    navigator: Navigator,
    ledgers: Ledgers,
) {
    val place = Place(navigator.depth, navigator[0])
    var last by remember { mutableStateOf(place) }
    LaunchedEffect(place) {
        if (leftScreen(last, place)) {
            ledgers.clearSelections()
            ledgers.actions.dismiss()
        }
        last = place
    }
}
