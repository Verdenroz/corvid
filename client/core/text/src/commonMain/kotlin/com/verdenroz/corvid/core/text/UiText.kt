package com.verdenroz.corvid.core.text

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * User-facing text that any layer can produce, resolved late by the UI or a [TextResolver]. Client copy is
 * always a [Resource] or [Plural]; [FromServer] is reserved for text that arrived from outside,
 * such as a server's error description or a sender's name. An argument that is itself a
 * [UiText] is resolved before formatting.
 */
sealed interface UiText {
    data class Resource(val resource: StringResource, val args: List<Any> = emptyList()) : UiText

    data class Plural(val resource: PluralStringResource, val count: Int, val args: List<Any> = listOf(count)) : UiText

    data class FromServer(val value: String) : UiText
}

fun uiText(
    resource: StringResource,
    vararg args: Any,
): UiText = UiText.Resource(resource, args.toList())

fun uiPlural(
    resource: PluralStringResource,
    count: Int,
): UiText = UiText.Plural(resource, count)

@Composable
fun UiText.asString(): String =
    when (this) {
        is UiText.Resource -> stringResource(resource, *args.map { it.composed() }.toTypedArray())
        is UiText.Plural -> pluralStringResource(resource, count, *args.map { it.composed() }.toTypedArray())
        is UiText.FromServer -> value
    }

/** Resolves outside composition, for text that leaves the UI, such as a reply's attribution line. */
fun interface TextResolver {
    suspend fun resolve(text: UiText): String
}

val SystemTextResolver = TextResolver { it.resolve() }

suspend fun UiText.resolve(environment: ResourceEnvironment = getSystemResourceEnvironment()): String =
    when (this) {
        is UiText.Resource -> getString(environment, resource, *args.map { it.resolved(environment) }.toTypedArray())
        is UiText.Plural -> getPluralString(environment, resource, count, *args.map { it.resolved(environment) }.toTypedArray())
        is UiText.FromServer -> value
    }

@Composable
private fun Any.composed(): Any = if (this is UiText) asString() else this

private suspend fun Any.resolved(environment: ResourceEnvironment): Any = if (this is UiText) resolve(environment) else this
