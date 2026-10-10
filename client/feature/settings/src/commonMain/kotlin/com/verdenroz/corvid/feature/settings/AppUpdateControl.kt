package com.verdenroz.corvid.feature.settings

import com.verdenroz.corvid.core.data.AppUpdateState
import kotlinx.coroutines.flow.StateFlow

/** What Settings shows of the app's own update, and how it asks for one. */
class AppUpdateControl(
    val state: StateFlow<AppUpdateState>,
    val onUpdate: () -> Unit,
)
