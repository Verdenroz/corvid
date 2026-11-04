package com.verdenroz.corvid.core.prefs

actual val platformOutboxDirectoryModule = outboxDirectoryModule { userConfigDir().resolve("corvid").resolve("outbox") }
