package com.verdenroz.corvid.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.verdenroz.corvid.app.shell.AppUpdateBannerBar
import com.verdenroz.corvid.core.data.AppInstaller
import com.verdenroz.corvid.core.data.AppReleaseRepository
import org.koin.compose.koinInject
import org.koin.core.scope.Scope
import java.io.File

/** Looks for a newer app build for as long as [scope] lives, and again each time the app returns to the foreground. */
@Composable
internal fun rememberAppUpdates(
    scope: Scope,
    installer: AppInstaller = koinInject(),
    backgroundDrop: BackgroundDropController = koinInject(),
): AppUpdates {
    val updates =
        remember(scope) {
            AppUpdates(scope.get<AppReleaseRepository>(), installer, File(System.getProperty("java.io.tmpdir"), "corvid-update"))
        }
    LaunchedEffect(updates) { updates.run(backgroundDrop.foregrounded) }
    return updates
}

@Composable
internal fun AppUpdatesBanner(updates: AppUpdates) {
    val banner by updates.banner.collectAsState(null)
    banner?.let { AppUpdateBannerBar(it, onUpdate = updates::update, onDismiss = updates::dismiss) }
}
