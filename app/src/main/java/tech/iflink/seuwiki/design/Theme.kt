package tech.iflink.seuwiki.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat

val LocalSeuColors = staticCompositionLocalOf { LightSeuColors }

/** Shorthand: `SeuTheme.colors.accent`. */
object SeuTheme {
    val colors: SeuColorScheme
        @Composable get() = LocalSeuColors.current
}

/**
 * App theme.
 *
 * Material 3 supplies the scaffolding (Scaffold, Text defaults, ripple) while
 * [SeuColorScheme] carries the iOS semantic roles the screens actually read —
 * so a card surface is `secondarySystemGroupedBackground` on both platforms
 * rather than whatever M3's `surfaceContainer` happens to resolve to.
 */
@Composable
fun SEUWikiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val seuColors = if (darkTheme) DarkSeuColors else LightSeuColors

    val m3 = if (darkTheme) {
        darkColorScheme(
            primary = seuColors.accent,
            onPrimary = if (darkTheme) Color(0xFF00382B) else Color.White,
            background = seuColors.groupedBackground,
            onBackground = seuColors.label,
            surface = seuColors.secondaryGroupedBackground,
            onSurface = seuColors.label,
            surfaceVariant = seuColors.tertiaryFill,
            onSurfaceVariant = seuColors.secondaryLabel,
            outline = seuColors.separator,
            error = seuColors.red,
        )
    } else {
        lightColorScheme(
            primary = seuColors.accent,
            onPrimary = Color.White,
            background = seuColors.groupedBackground,
            onBackground = seuColors.label,
            surface = seuColors.secondaryGroupedBackground,
            onSurface = seuColors.label,
            surfaceVariant = seuColors.tertiaryFill,
            onSurfaceVariant = seuColors.secondaryLabel,
            outline = seuColors.separator,
            error = seuColors.red,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalSeuColors provides seuColors) {
        MaterialTheme(
            colorScheme = m3,
            typography = SeuTypography,
            shapes = SeuShapes,
            content = content,
        )
    }
}
