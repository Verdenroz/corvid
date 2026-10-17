package com.verdenroz.corvid.core.ui.suggestion

import com.verdenroz.corvid.core.text.UiText
import com.verdenroz.corvid.core.ui.resources.Res
import com.verdenroz.corvid.core.ui.resources.suggestion_over_days
import com.verdenroz.corvid.core.ui.resources.suggestion_over_weeks

private const val DAYS_IN_WEEK = 7
private const val WEEKS_FROM = 14

/** How long a suggestion's messages span, in days below two weeks and in weeks from there; null when the server did not say. */
fun spanClause(spanDays: Int): UiText? =
    when {
        spanDays <= 0 -> null
        spanDays < WEEKS_FROM -> UiText.Plural(Res.plurals.suggestion_over_days, spanDays)
        else -> UiText.Plural(Res.plurals.suggestion_over_weeks, (spanDays + DAYS_IN_WEEK / 2) / DAYS_IN_WEEK)
    }
