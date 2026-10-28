package com.nuvio.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.nuvio.tv.domain.model.CustomThemeColors

fun CustomThemeColors.toColorPalette(): ThemeColorPalette {
    val gradient = colors.map { Color(it or 0xFF000000.toInt()) }
    val accent = gradient[1]
    val brushColors = if (isSolid) listOf(accent) else gradient
    fun surface(base: Color, tint: Float) = lerp(base, accent, tint)

    return ThemeColorPalette(
        secondary = accent,
        secondaryVariant = gradient[2],
        onSecondary = semanticForegroundFor(accent),
        onSecondaryVariant = semanticForegroundFor(gradient[2]),
        accentGradient = brushColors,
        focusRing = gradient.maxBy { it.luminance() },
        focusRingGradient = brushColors,
        focusBackground = surface(NuvioPrimitives.neutral850, 0.16f),
        background = surface(NuvioPrimitives.neutral950, 0.02f),
        backgroundElevated = surface(NuvioPrimitives.neutral900, 0.035f),
        backgroundCard = surface(NuvioPrimitives.neutral825, 0.04f),
        surface = surface(NuvioPrimitives.neutral875, 0.035f),
        surfaceVariant = surface(NuvioPrimitives.neutral800, 0.04f),
        panel = surface(NuvioPrimitives.neutral900, 0.035f),
        field = surface(NuvioPrimitives.neutral850, 0.04f),
        menu = surface(NuvioPrimitives.neutral875, 0.035f),
        modal = surface(NuvioPrimitives.neutral900, 0.035f)
    )
}
