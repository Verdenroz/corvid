package com.verdenroz.corvid.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Spelled as the server writes it. */
@Serializable
enum class UpdateResult {
    @SerialName("current")
    Current,

    @SerialName("updated")
    Updated,

    @SerialName("rolledBack")
    RolledBack,

    @SerialName("failed")
    Failed,

    @SerialName("skipped")
    Skipped,
}

/** What the server says about its own release: [running] now, [latest] when a check found one, and the last outcome. */
data class UpdateReport(
    val running: String,
    val latest: String?,
    val checkedAt: Instant?,
    val result: UpdateResult,
    val detail: String?,
    val auto: Boolean,
    val busy: Boolean,
) {
    val updateAvailable: Boolean get() = latest != null && latest != running
}
