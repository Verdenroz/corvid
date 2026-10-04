package com.verdenroz.corvid.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.verdenroz.corvid.core.designsystem.resources.Res
import com.verdenroz.corvid.core.designsystem.resources.bricolage_grotesque_bold
import com.verdenroz.corvid.core.designsystem.resources.bricolage_grotesque_medium
import com.verdenroz.corvid.core.designsystem.resources.hanken_grotesk_bold
import com.verdenroz.corvid.core.designsystem.resources.hanken_grotesk_medium
import com.verdenroz.corvid.core.designsystem.resources.hanken_grotesk_regular
import com.verdenroz.corvid.core.designsystem.resources.hanken_grotesk_semibold
import org.jetbrains.compose.resources.Font

/**
 * Bricolage Grotesque for the wordmark, day headers, card names and the reader's subject;
 * Hanken Grotesk for everything else.
 */
@Immutable
data class CorvidType(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val dayHeader: TextStyle,
    val name: TextStyle,
    val body: TextStyle,
    val meta: TextStyle,
    val metaStrong: TextStyle,
    val chip: TextStyle,
    val action: TextStyle,
    val label: TextStyle,
)

@Composable
internal fun corvidType(isDark: Boolean): CorvidType {
    val display =
        FontFamily(
            Font(Res.font.bricolage_grotesque_medium, FontWeight.Medium),
            Font(Res.font.bricolage_grotesque_bold, FontWeight.Bold),
        )
    val text =
        FontFamily(
            Font(Res.font.hanken_grotesk_regular, FontWeight.Normal),
            Font(Res.font.hanken_grotesk_medium, FontWeight.Medium),
            Font(Res.font.hanken_grotesk_semibold, FontWeight.SemiBold),
            Font(Res.font.hanken_grotesk_bold, FontWeight.Bold),
        )
    return CorvidType(
        display =
            TextStyle(
                fontFamily = display,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                letterSpacing = (-0.7).sp,
            ),
        headline =
            TextStyle(
                fontFamily = display,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                letterSpacing = (-0.5).sp,
            ),
        title =
            TextStyle(
                fontFamily = display,
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp,
                lineHeight = 26.sp,
                letterSpacing = (-0.4).sp,
            ),
        dayHeader =
            TextStyle(
                fontFamily = display,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                letterSpacing = (-0.3).sp,
            ),
        name = TextStyle(fontFamily = text, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp),
        // Light text on ink reads thinner, so the dark theme opens the leading.
        body =
            TextStyle(
                fontFamily = text,
                fontSize = 15.5.sp,
                lineHeight = if (isDark) 26.sp else 25.sp,
                letterSpacing = if (isDark) 0.1.sp else 0.sp,
            ),
        meta = TextStyle(fontFamily = text, fontSize = 12.5.sp, lineHeight = 17.sp),
        metaStrong = TextStyle(fontFamily = text, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, lineHeight = 17.sp),
        chip = TextStyle(fontFamily = text, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, lineHeight = 15.sp),
        action = TextStyle(fontFamily = text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
        label =
            TextStyle(
                fontFamily = text,
                fontWeight = FontWeight.Bold,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.8.sp,
            ),
    )
}

internal fun CorvidType.toMaterial(): Typography =
    Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = headline,
        headlineLarge = headline,
        headlineMedium = headline,
        headlineSmall = title,
        titleLarge = title,
        titleMedium = dayHeader,
        titleSmall = name,
        bodyLarge = body,
        bodyMedium = name.copy(fontWeight = FontWeight.Normal),
        bodySmall = meta,
        labelLarge = action,
        labelMedium = metaStrong,
        labelSmall = label,
    )
