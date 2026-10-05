package tech.iflink.seuwiki.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Type scale ported 1:1 from the SwiftUI text styles used in the iOS app.
 *
 * SF Pro Text is not redistributable, so this renders in the platform default
 * (Roboto). Sizes, weights and line heights match SF Pro exactly, and tracking
 * is tightened to close the visual gap — SF Pro runs slightly tighter than
 * Roboto at every size in this scale.
 */
private val Default = FontFamily.Default

private val TightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun sf(
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    lineHeight: Int = (size * 1.22f).toInt(),
    tracking: Float = 0f,
) = TextStyle(
    fontFamily = Default,
    fontSize = size.sp,
    fontWeight = weight,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = TightLineHeight,
)

/** The named styles the SwiftUI code reaches for, exposed as one object. */
object SeuType {
    /** `.system(size: 34, weight: .bold, design: .rounded)` — reminder countdown. */
    val CountdownLarge = sf(34, FontWeight.Bold, 41, -0.5f)

    /** `.system(size: 22, weight: .bold, design: .rounded)` — next course time. */
    val CountdownMedium = sf(22, FontWeight.Bold, 28, -0.3f)

    val LargeTitle = sf(34, FontWeight.Bold, 41, -0.4f)
    val Title2 = sf(22, FontWeight.Bold, 28, -0.3f)
    val Title3 = sf(20, FontWeight.Bold, 25, -0.2f)
    val Headline = sf(17, FontWeight.SemiBold, 22, -0.4f)

    /** `.headline.weight(.bold)` — the tool and topic card titles. */
    val HeadlineBold = sf(17, FontWeight.Bold, 22, -0.4f)
    val Subheadline = sf(15, FontWeight.Normal, 20, -0.2f)
    val SubheadlineMedium = sf(15, FontWeight.Medium, 20, -0.2f)
    val SubheadlineSemibold = sf(15, FontWeight.SemiBold, 20, -0.2f)
    val Body = sf(17, FontWeight.Normal, 22, -0.4f)
    val Callout = sf(16, FontWeight.Normal, 21, -0.3f)
    val Footnote = sf(13, FontWeight.Normal, 18, -0.1f)
    val Caption = sf(12, FontWeight.Normal, 16, 0f)
    val CaptionMedium = sf(12, FontWeight.Medium, 16, 0f)

    /** `.caption2` — the smallest step, used by the topic cards' post counts. */
    val Caption2 = sf(11, FontWeight.Normal, 14, 0f)
    val Caption2Medium = sf(11, FontWeight.Medium, 14, 0f)
}

val SeuTypography = Typography(
    bodyLarge = SeuType.Body,
    bodyMedium = SeuType.Callout,
    bodySmall = SeuType.Footnote,
    titleLarge = SeuType.LargeTitle,
    titleMedium = SeuType.Title3,
    titleSmall = SeuType.Headline,
    labelLarge = SeuType.SubheadlineMedium,
    labelMedium = SeuType.Caption,
    labelSmall = SeuType.Caption,
)
