package com.verdenroz.corvid.core.testing

import com.verdenroz.corvid.core.text.TextResolver
import com.verdenroz.corvid.core.text.UiText

/**
 * Resolves text to its resource key and arguments, `reply_attribution[Fri 1 Mar, alice]`, so a
 * common test can check which copy was chosen without loading resources or a locale.
 */
val KeyedTextResolver = TextResolver { it.keyed() }

private fun UiText.keyed(): String =
    when (this) {
        is UiText.Resource -> "${resource.key}${args.keyedArgs()}"
        is UiText.Plural -> "${resource.key}${args.keyedArgs()}"
        is UiText.FromServer -> value
    }

private fun List<Any>.keyedArgs(): String =
    if (isEmpty()) {
        ""
    } else {
        joinToString(", ", "[", "]") {
            if (it is UiText) it.keyed() else it.toString()
        }
    }
