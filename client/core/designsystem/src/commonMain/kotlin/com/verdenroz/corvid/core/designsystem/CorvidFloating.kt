package com.verdenroz.corvid.core.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Where Write, the action notice and the wide compose panel float over a screen, so each keeps clear of the others
 * and a list scrolls its last row clear of them all. Write floats only when narrow; wide, it heads the sidebar.
 */
object CorvidFloating {
    val writeEnd = 24.dp
    val writeBottom = 22.dp
    val writeHeight = 40.dp

    val panelEnd = 24.dp
    val panelWidth = 540.dp
    val panelHeight = 470.dp

    val noticeStart = 16.dp
    private val noticeHeight = 48.dp
    private val noticeAboveWrite = 18.dp
    private val listGap = 24.dp
    private val writeTop = writeBottom + writeHeight

    fun noticeBottom(wide: Boolean): Dp = if (wide) writeBottom else writeTop + noticeAboveWrite

    fun noticeEnd(panelShown: Boolean): Dp = if (panelShown) panelWidth + panelEnd + noticeStart else noticeStart

    /** A list's bottom padding: above Write, or above the notice when it floats over Write. */
    fun listClearance(noticeOverWrite: Boolean): Dp =
        (if (noticeOverWrite) noticeBottom(wide = false) + noticeHeight else writeTop) + listGap
}
