package com.verdenroz.corvid.core.testing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.rememberResourceEnvironment

/**
 * The environment a composition resolves strings with. Outside one, the desktop JVM asks AWT for the
 * screen density, which a headless test runner does not have, so tests take it from a composition.
 */
@OptIn(ExperimentalTestApi::class)
fun composedResourceEnvironment(): ResourceEnvironment {
    var environment: ResourceEnvironment? = null
    runComposeUiTest { setContent { environment = rememberResourceEnvironment() } }
    return checkNotNull(environment)
}
