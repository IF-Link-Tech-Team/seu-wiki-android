package tech.iflink.seuwiki.design

import androidx.compose.ui.graphics.Color

/**
 * Deterministic colour assignment for forum topics, authors and handbook nodes.
 *
 * The iOS build hashes the key (a topic slug, an author name, a section id) into
 * a fixed palette so the same key always resolves to the same colour across
 * screens and relaunches — no state needed. This is the same scheme.
 */
private fun bucketFor(key: String, buckets: Int): Int = key.sumOf { it.code } % buckets

/**
 * Fully saturated palette used for topic cards and handbook node icons — the
 * Apple Podcasts category page treatment the iOS build borrows.
 *
 * These are fixed sRGB values in SwiftUI (constructed with `Color(red:green:blue:)`,
 * not semantic colors), so they are fixed here too and do not vary by theme.
 */
private val SolidTints = listOf(
    Color(0xFF9EA838), // rgb(0.62, 0.66, 0.22)
    Color(0xFFDB3D52), // rgb(0.86, 0.24, 0.32)
    Color(0xFFED7D29), // rgb(0.93, 0.49, 0.16)
    Color(0xFFE04C78), // rgb(0.88, 0.30, 0.47)
    Color(0xFF5CAD42), // rgb(0.36, 0.68, 0.26)
    Color(0xFF2E9E94), // rgb(0.18, 0.62, 0.58)
    Color(0xFF3D85E3), // rgb(0.24, 0.52, 0.89)
    Color(0xFF805CD4), // rgb(0.50, 0.36, 0.83)
    Color(0xFFDB479E), // rgb(0.86, 0.28, 0.62)
    Color(0xFFF09E24), // rgb(0.94, 0.62, 0.14)
)

/** Saturated topic colour, stable for a given [key]. */
fun forumSolidTint(key: String): Color = SolidTints[bucketFor(key, SolidTints.size)]

/**
 * Resolves a `ToolItem.tintKey` to its colour.
 *
 * The Tools grid deliberately uses the *light* palette regardless of theme, so a
 * card reads the same in dark mode as it does on iOS.
 */
fun toolTintOf(key: String, colors: SeuColorScheme): Color = when (key) {
    "blue" -> IosPalette.Blue
    "green" -> IosPalette.Green
    "orange" -> IosPalette.Orange
    "purple" -> IosPalette.Purple
    "pink" -> IosPalette.Pink
    "teal" -> IosPalette.Teal
    "mint" -> IosPalette.Mint
    "indigo" -> IosPalette.Indigo
    "red" -> IosPalette.Red
    "yellow" -> IosPalette.Yellow
    "cyan" -> IosPalette.Cyan
    "brown" -> IosPalette.Brown
    else -> IosPalette.Gray
}
