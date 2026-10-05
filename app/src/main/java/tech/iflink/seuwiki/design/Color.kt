package tech.iflink.seuwiki.design

import androidx.compose.ui.graphics.Color

/**
 * Brand colors.
 *
 * [AccentLight] / [AccentDark] are read straight out of the iOS project's
 * `Assets.xcassets/AccentColor.colorset` so both platforms share one brand value.
 */
object SeuColors {
    val AccentLight = Color(0xFF0E5A46)
    val AccentDark = Color(0xFF34D6AB)
}

/**
 * iOS system palette.
 *
 * The forum, feed and handbook screens tint by semantic color, so these are
 * pinned to Apple's documented sRGB values rather than to Material Theme's
 * tonally-mapped equivalents — otherwise the same screen would read greener /
 * bluer on Android than it does on iOS.
 */
object IosPalette {
    // Light-mode values.
    val Red = Color(0xFFFF3B30)
    val Orange = Color(0xFFFF9500)
    val Yellow = Color(0xFFFFCC00)
    val Green = Color(0xFF34C759)
    val Mint = Color(0xFF00C7BE)
    val Teal = Color(0xFF30B0C7)
    val Cyan = Color(0xFF32ADE6)
    val Blue = Color(0xFF007AFF)
    val Indigo = Color(0xFF5856D6)
    val Purple = Color(0xFFAF52DE)
    val Pink = Color(0xFFFF2D55)
    val Brown = Color(0xFFA2845E)
    val Gray = Color(0xFF8E8E93)

    // Dark-mode values.
    val RedDark = Color(0xFFFF453A)
    val OrangeDark = Color(0xFFFF9F0A)
    val YellowDark = Color(0xFFFFD60A)
    val GreenDark = Color(0xFF30D158)
    val MintDark = Color(0xFF66D4CF)
    val TealDark = Color(0xFF40C8E0)
    val CyanDark = Color(0xFF64D2FF)
    val BlueDark = Color(0xFF0A84FF)
    val IndigoDark = Color(0xFF5E5CE6)
    val PurpleDark = Color(0xFFBF5AF2)
    val PinkDark = Color(0xFFFF375F)
    val BrownDark = Color(0xFFAC8E68)
    val GrayDark = Color(0xFF98989D)
}

/**
 * Semantic surface + text roles, mirroring the `Color(.systemGroupedBackground)`,
 * `Color(.secondarySystemGroupedBackground)` and `Color(.tertiarySystemFill)`
 * lookups used throughout the SwiftUI code.
 */
data class SeuColorScheme(
    val accent: Color,
    /**
     * 压在 [accent] 底色上的文字色。
     *
     * 这一项的存在是因为 `#34D6AB`（深色模式的品牌亮绿）压白字对比度只有
     * **1.83:1**，远低于 WCAG AA 要求的 4.5:1 —— 属于实打实的不可读。
     * 深色下改成 `#00382B`（品牌深绿），对亮绿的对比度是 **7.08:1**。
     *
     * 亮色模式的 accent 是 `#0E5A46` 深绿，本来就压白字够用，所以这里仍是白色。
     * 凡是「accent 作底 + 文字叠加」的地方（console 胶囊选中态、chip、
     * 「关注」按钮、彩色卡片）都必须用这个 token，不要直接写 Color.White。
     */
    val onAccentInverted: Color,
    val groupedBackground: Color,
    val secondaryGroupedBackground: Color,
    val tertiaryFill: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val red: Color,
    val orange: Color,
    val green: Color,
    val blue: Color,
    val purple: Color,
    val pink: Color,
    val teal: Color,
    val mint: Color,
    val indigo: Color,
    val isDark: Boolean,
)

val LightSeuColors = SeuColorScheme(
    accent = SeuColors.AccentLight,
    onAccentInverted = Color.White,
    groupedBackground = Color(0xFFF2F2F7),
    secondaryGroupedBackground = Color(0xFFFFFFFF),
    tertiaryFill = Color(0x1E767680),
    label = Color(0xFF000000),
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0xFFC6C6C8),
    red = IosPalette.Red,
    orange = IosPalette.Orange,
    green = IosPalette.Green,
    blue = IosPalette.Blue,
    purple = IosPalette.Purple,
    pink = IosPalette.Pink,
    teal = IosPalette.Teal,
    mint = IosPalette.Mint,
    indigo = IosPalette.Indigo,
    isDark = false,
)

/**
 * Linear blend of two colors — the counterpart of SwiftUI's `Color.mix(with:by:)`.
 *
 * The Tools grid leans on this to give every saturated card a subtle top-to-bottom
 * gradient (`tint` mixed 8% toward white at the top, 10% toward black at the
 * bottom) rather than a flat fill.
 */
fun Color.mixWith(other: Color, by: Float): Color {
    val t = by.coerceIn(0f, 1f)
    return Color(
        red = red * (1 - t) + other.red * t,
        green = green * (1 - t) + other.green * t,
        blue = blue * (1 - t) + other.blue * t,
        alpha = alpha * (1 - t) + other.alpha * t,
    )
}

val DarkSeuColors = SeuColorScheme(
    accent = SeuColors.AccentDark,
    onAccentInverted = Color(0xFF00382B),
    groupedBackground = Color(0xFF000000),
    secondaryGroupedBackground = Color(0xFF1C1C1E),
    tertiaryFill = Color(0x3D767680),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0xFF38383A),
    red = IosPalette.RedDark,
    orange = IosPalette.OrangeDark,
    green = IosPalette.GreenDark,
    blue = IosPalette.BlueDark,
    purple = IosPalette.PurpleDark,
    pink = IosPalette.PinkDark,
    teal = IosPalette.TealDark,
    mint = IosPalette.MintDark,
    indigo = IosPalette.IndigoDark,
    isDark = true,
)
