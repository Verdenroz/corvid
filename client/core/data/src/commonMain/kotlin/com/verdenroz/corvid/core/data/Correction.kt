package com.verdenroz.corvid.core.data

import com.verdenroz.corvid.core.model.CorvidKeywords
import com.verdenroz.corvid.core.model.Label

/**
 * The user's answer to a model guess. The labeler trains only on the user's own keywords, so an
 * answer is written as one, and the guess is removed. `corvid-llm-done` stays, so the model is
 * not asked about this message again.
 */
sealed interface Correction {
    val guess: Label.Guess

    data class Right(override val guess: Label.Guess) : Correction

    data class Wrong(override val guess: Label.Guess) : Correction

    /** [keyword] is already the user's own, from [CorvidKeywords.userKeyword]. */
    data class Change(override val guess: Label.Guess, val keyword: String) : Correction
}

/** Keywords to set (true) and to remove (false) for [this] answer. */
fun Correction.keywordPatch(): Map<String, Boolean> {
    val removeGuess = CorvidKeywords.MODEL_LABEL + guess.name to false
    return when (this) {
        is Correction.Right -> mapOf(guess.name to true, removeGuess)
        is Correction.Wrong -> mapOf(removeGuess)
        is Correction.Change -> mapOf(keyword to true, removeGuess)
    }
}
