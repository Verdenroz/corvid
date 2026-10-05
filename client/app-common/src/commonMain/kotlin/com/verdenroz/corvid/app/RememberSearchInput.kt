package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.verdenroz.corvid.app.shell.SearchInput
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private val SEARCH_DEBOUNCE = 350.milliseconds

/** The typed text, searched a moment after the last keystroke and cleared once no search is open. */
@Composable
internal fun rememberSearchInput(navigator: Navigator): SearchInput {
    val searching = navigator.searching
    var typed by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(searching) { if (!searching) typed = "" }
    LaunchedEffect(typed) {
        delay(SEARCH_DEBOUNCE)
        navigator.search(typed)
    }
    val focus = remember { FocusRequester() }
    return SearchInput(typed, { typed = it }, { navigator.search(typed) }, focus)
}
