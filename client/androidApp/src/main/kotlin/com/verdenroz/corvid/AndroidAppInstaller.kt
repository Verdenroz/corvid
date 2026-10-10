package com.verdenroz.corvid

import com.verdenroz.corvid.core.data.AppInstaller
import com.verdenroz.corvid.core.data.InstallResult
import com.verdenroz.corvid.core.model.AppPlatform
import java.io.File

class AndroidAppInstaller : AppInstaller {
    override val platform = AppPlatform.Android

    override suspend fun install(file: File) = InstallResult.NotSupported
}
