package com.verdenroz.corvid.core.prefs

import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.io.File

/** The per-user directory the outbox keeps its sealed messages in. */
class OutboxDirectory(val path: File)

/** Supplies the [OutboxDirectory] for this platform. */
expect val platformOutboxDirectoryModule: Module

internal fun outboxDirectoryModule(directory: Scope.() -> File): Module =
    module {
        single { OutboxDirectory(directory()) }
    }
