package com.verdenroz.corvid.desktop

import com.verdenroz.corvid.core.data.AppInstaller
import com.verdenroz.corvid.core.data.InstallResult
import com.verdenroz.corvid.core.model.AppPlatform
import java.io.File

class DesktopAppInstaller : AppInstaller {
    override val platform = if (System.getProperty("os.name").startsWith("Linux")) AppPlatform.Linux else null

    override suspend fun install(file: File) = InstallResult.NotSupported
}
