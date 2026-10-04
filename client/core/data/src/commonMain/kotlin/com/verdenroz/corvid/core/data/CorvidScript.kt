package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.FilterAction
import com.verdenroz.corvid.core.model.FilterRule

/** One part of the `corvid` script: a metadata line that carries its source of truth, and the Sieve built from it. */
internal interface ScriptSection {
    val requires: List<String>
    val metadata: String
    val sieve: String
}

/** Everything Corvid keeps in its one Sieve script. A new feature adds a section here. */
internal data class CorvidScript(val rules: List<FilterRule> = emptyList()) {
    val needsTrash: Boolean get() = rules.any { it.enabled && FilterAction.MoveToTrash in it.actions }

    fun render(trash: FilterAction.MoveToFolder?): String {
        val sections = listOf<ScriptSection>(RulesSection(rules, trash))
        val requires = sections.flatMap { it.requires }.distinct()
        return buildList {
            sections.forEach { add(it.metadata) }
            add("require [${requires.joinToString(", ") { quote(it) }}];")
            sections.map { it.sieve }.filter { it.isNotEmpty() }.forEach { add(it) }
        }.joinToString("\n", postfix = "\n")
    }
}

internal fun parseCorvidScript(text: String): CorvidScript = CorvidScript(rules = parseRules(text) ?: throw FiltersFormatException())
