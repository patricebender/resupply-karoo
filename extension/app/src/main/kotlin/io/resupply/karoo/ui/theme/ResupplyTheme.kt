package io.resupply.karoo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.resupply.karoo.data.ThemeMode

// Brand-faithful Material 3 palette, built from the Resupply logo (ic_resupply /
// ResupplyLoadingLogo): a dark teal-green tile, cream letterform, and route-orange accent. The
// whole app runs through this so the chrome reads as the brand instead of Material's purple
// default. Only the scheme roles the UI actually consumes are dialled in with intent (primary,
// onPrimary, surface/background, onSurface, onSurfaceVariant, surfaceVariant, outlineVariant,
// error/onError); the rest are filled coherently so nothing can fall back to purple.

// --- Shared brand hues -------------------------------------------------------------------------
private val RouteOrange = Color(0xFFE8873B) // the logo's route trace — the hero accent (dark)
private val DeepOrange = Color(0xFFC96A1E)  // a deeper orange that holds contrast on cream (light)
private val TileDark = Color(0xFF0F1A18)    // the logo squircle — dark surface/background
private val TileLift = Color(0xFF16221F)    // a hair lighter than the tile for raised surfaces
private val Cream = Color(0xFFF2EDE3)        // the logo "R" — primary text on dark
private val CreamPaper = Color(0xFFF7F4EC)   // a warm off-white paper — light surface/background
private val Ink = Color(0xFF14211E)          // near-black teal-green — primary text on light
private val Contour = Color(0xFF2A3A36)      // the logo's faint contour lines — dark dividers

val ResupplyDarkColors = darkColorScheme(
    primary = RouteOrange,
    onPrimary = Ink,
    primaryContainer = Color(0xFF3A2410),
    onPrimaryContainer = Color(0xFFFFD9B8),
    secondary = Color(0xFF5FC7E3),       // logo dot cyan — a cool counter-accent
    onSecondary = Ink,
    background = TileDark,
    onBackground = Cream,
    surface = TileDark,
    onSurface = Cream,
    surfaceVariant = TileLift,
    onSurfaceVariant = Color(0xFFA8B3AE), // dimmed cream-grey for secondary text
    outline = Color(0xFF3C4C48),
    outlineVariant = Contour,
    error = Color(0xFFEF5350),           // brighter than ClosedRed so it reads on the dark tile
    onError = Ink,
)

val ResupplyLightColors = lightColorScheme(
    primary = DeepOrange,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF3A1D00),
    secondary = Color(0xFF00728A),       // deeper cyan for contrast on paper
    onSecondary = Color(0xFFFFFFFF),
    background = CreamPaper,
    onBackground = Ink,
    surface = CreamPaper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEE8DB),
    onSurfaceVariant = Color(0xFF5A6560),
    outline = Color(0xFFB6AF9E),
    outlineVariant = Color(0xFFDBD3C4),
    error = Color(0xFFB00020),
    onError = Color(0xFFFFFFFF),
)

/** True when [mode] resolves to the dark theme (SYSTEM defers to the Karoo's day/night mode). */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The app's single theme boundary. Resolves [mode] to a light/dark brand [androidx.compose
 * .material3.ColorScheme] and keeps the ambient typography (no type changes here). Wrap the
 * whole app in exactly one of these.
 */
@Composable
fun ResupplyTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (mode.isDark()) ResupplyDarkColors else ResupplyLightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
