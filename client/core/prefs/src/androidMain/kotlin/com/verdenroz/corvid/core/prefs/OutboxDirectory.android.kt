package com.verdenroz.corvid.core.prefs

import org.koin.android.ext.koin.androidContext
import java.io.File

actual val platformOutboxDirectoryModule = outboxDirectoryModule { File(androidContext().filesDir, "outbox") }
