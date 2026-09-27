package com.verdenroz.corvid.core.jmap

import kotlinx.serialization.json.JsonObject

/** One call's result inside a batch. A [Failure] here never fails the rest of the batch. */
sealed interface MethodOutcome {
    data class Success(val result: JsonObject) : MethodOutcome

    data class Failure(val error: JmapError.Method) : MethodOutcome
}
