package com.verdenroz.corvid.core.model

import kotlin.time.Instant

/** The server's model-written summary of a thread. [text] derives from the mail it summarizes, so it is untrusted. */
data class ThreadSummary(val text: String, val generatedAt: Instant)
