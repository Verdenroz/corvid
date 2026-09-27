package com.verdenroz.corvid.core.jmap

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One JMAP method call or response. The wire shape is a 3-element JSON
 * array `[name, argumentsOrResult, callId]`, not an object, so it needs
 * [InvocationSerializer] rather than the derived one.
 */
@Serializable(with = InvocationSerializer::class)
data class Invocation(
    val name: String,
    val arguments: JsonObject,
    val callId: String,
)

object InvocationSerializer : KSerializer<Invocation> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Invocation")

    override fun serialize(
        encoder: Encoder,
        value: Invocation,
    ) {
        val json = encoder as? JsonEncoder ?: error("Invocation can only be serialized to JSON")
        json.encodeJsonElement(
            buildJsonArray {
                add(JsonPrimitive(value.name))
                add(value.arguments)
                add(JsonPrimitive(value.callId))
            },
        )
    }

    override fun deserialize(decoder: Decoder): Invocation {
        val json = decoder as? JsonDecoder ?: error("Invocation can only be deserialized from JSON")
        val array = json.decodeJsonElement().jsonArray
        return Invocation(
            name = array[0].jsonPrimitive.content,
            arguments = array[1].jsonObject,
            callId = array[2].jsonPrimitive.content,
        )
    }
}
