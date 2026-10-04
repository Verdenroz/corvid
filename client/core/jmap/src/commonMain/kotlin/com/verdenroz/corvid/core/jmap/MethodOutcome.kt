package com.verdenroz.corvid.core.jmap

import kotlinx.serialization.json.JsonObject

/** One call's result inside a batch. A [Failure] here never fails the rest of the batch. */
sealed interface MethodOutcome {
    /** [implicit] holds any further responses the server sent under the same callId. */
    data class Success(val result: JsonObject, val implicit: List<Invocation> = emptyList()) : MethodOutcome

    data class Failure(val error: JmapError.Method) : MethodOutcome
}
