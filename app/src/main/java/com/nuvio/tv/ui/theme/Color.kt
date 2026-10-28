package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

internal fun semanticContrastRatio(foreground: Color, background: Color): Float {
    val base = background.compositeOver(NuvioPrimitives.neutral950)
    val foregroundLuminance = foreground.compositeOver(base).luminance()
    val backgroundLuminance = base.luminance()
    return (maxOf(foregroundLuminance, backgroundLuminance) + 0.05f) /
        (minOf(foregroundLuminance, backgroundLuminance) + 0.05f)
}

internal fun semanticForegroundFor(background: Color): Color {
    val whiteContrast = semanticContrastRatio(NuvioPrimitives.white, background)
    val darkContrast = semanticContrastRatio(NuvioPrimitives.neutral925, background)
    return when {
        darkContrast >= whiteContrast && darkContrast >= 4.5f -> NuvioPrimitives.neutral925
        whiteContrast >= 4.5f -> NuvioPrimitives.white
        else -> NuvioPrimitives.black
    }
}

internal fun semanticForegroundFor(background: Color, preferred: Color): Color =
    if (semanticContrastRatio(preferred, background) >= 4.5f) preferred else semanticForegroundFor(background)

internal fun semanticFocusColor(candidate: Color, background: Color): Color {
    val opaque = candidate.copy(alpha = 1f)
    if (semanticContrastRatio(opaque, background) >= 3f) return opaque
    val target = semanticForegroundFor(background)
    for (step in 1..12) {
        val adjusted = lerp(opaque, target, step / 12f)
        if (semanticContrastRatio(adjusted, background) >= 3f) return adjusted
    }
    return target
}

@Immutable
data class NuvioSurfaceColors(
    val background: Color,
    val raised: Color,
    val card: Color,
    val default: Color,
    val variant: Color,
    val panel: Color,
    val overlay: Color,
    val field: Color,
    val menu: Color,
    val modal: Color,
    val playerOverlay: Color,
    val divider: Color
)

@Immutable
data class NuvioTextColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val disabled: Color,
    val inverse: Color,
    val onAccent: Color,
    val onOverlay: Color,
    val metadata: Color
)

@Immutable
data class NuvioFocusColors(
    val ring: Color,
    val background: Color,
    val content: Color,
    val accent: Color,
    val scrim: Color
)

@Immutable
data class NuvioSelectionColors(
    val background: Color,
    val foreground: Color,
    val border: Color,
    val mutedBackground: Color,
    val mutedForeground: Color
)

@Immutable
data class NuvioMediaColors(
    val heroScrim: Color,
    val imageScrim: Color,
    val posterFallback: Color,
    val videoControlsScrim: Color,
    val glassPanelTop: Color,
    val glassPanelMiddle: Color,
    val glassPanelBottom: Color,
    val glow: Color
)

@Immutable
data class NuvioStatusColors(
    val rating: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
    val info: Color,
    val watched: Color,
    val unwatched: Color,
    val cached: Color,
    val torrent: Color,
    val premium: Color
)

@Immutable
data class NuvioDisabledColors(
    val container: Color,
    val content: Color,
    val border: Color,
    val overlay: Color
)

@Immutable
data class NuvioSourceColors(
    val trakt: Color,
    val tmdb: Color,
    val imdb: Color,
    val mdblist: Color
)

@Immutable
data class NuvioContrastPair(
    val foreground: Color,
    val background: Color
)

class NuvioColorScheme(
    palette: ThemeColorPalette,
    amoledMode: Boolean = false,
    amoledSurfacesMode: Boolean = false
) {
    private val pureBlack = NuvioPrimitives.black
    private val pureBlackSurfaces = amoledMode && amoledSurfacesMode

    val Background = if (amoledMode) pureBlack else palette.background
    val BackgroundElevated = if (pureBlackSurfaces) pureBlack else palette.backgroundElevated
    val BackgroundCard = if (pureBlackSurfaces) pureBlack else palette.backgroundCard
    val Surface = if (pureBlackSurfaces) pureBlack else palette.surface
    val SurfaceVariant = if (pureBlackSurfaces) pureBlack else palette.surfaceVariant
    val Panel = if (pureBlackSurfaces) pureBlack else palette.panel
    val Overlay = palette.overlay
    val Field = if (pureBlackSurfaces) pureBlack else palette.field
    val Menu = if (pureBlackSurfaces) pureBlack else palette.menu
    val Modal = if (pureBlackSurfaces) pureBlack else palette.modal
    val PlayerOverlay = palette.playerOverlay
    val Divider = NuvioPrimitives.neutral750

    val Primary = NuvioPrimitives.neutral200
    val PrimaryVariant = NuvioPrimitives.neutral400
    val OnPrimary = semanticForegroundFor(Primary)
    val Secondary = palette.secondary
    val SecondaryVariant = palette.secondaryVariant
    val OnSecondary = semanticForegroundFor(Secondary, palette.onSecondary)
    val OnSecondaryVariant = semanticForegroundFor(SecondaryVariant, palette.onSecondaryVariant)

    val TextPrimary = NuvioPrimitives.neutral100
    val TextSecondary = NuvioPrimitives.neutral400
    val TextTertiary = NuvioPrimitives.neutral600
    val TextDisabled = NuvioPrimitives.neutral700
    val TextInverse = NuvioPrimitives.neutral925

    val FocusRing = semanticFocusColor(palette.focusRing, BackgroundCard)
    val FocusBackground = palette.focusBackground
    val FocusContent = semanticForegroundFor(FocusBackground)
    val FocusScrim = NuvioPrimitives.black.copy(alpha = 0.32f)

    val Rating = NuvioPrimitives.rating
    val Error = NuvioPrimitives.error
    val Warning = NuvioPrimitives.warning
    val Success = NuvioPrimitives.success
    val Info = NuvioPrimitives.info
    val Watched = NuvioPrimitives.success
    val Unwatched = NuvioPrimitives.neutral600
    val Cached = NuvioPrimitives.blue300
    val Torrent = NuvioPrimitives.torrent
    val Premium = NuvioPrimitives.premium

    val Border = NuvioPrimitives.neutral750
    val BorderFocused = FocusRing
    val BorderMuted = NuvioPrimitives.neutral750.copy(alpha = 0.58f)

    val Scrim = NuvioPrimitives.black.copy(alpha = 0.62f)
    val ImageScrim = NuvioPrimitives.black.copy(alpha = 0.58f)
    val VideoControlsScrim = NuvioPrimitives.black.copy(alpha = 0.72f)
    val PosterFallback = BackgroundCard

    val DisabledContainer = SurfaceVariant.copy(alpha = 0.42f)
    val DisabledContent = TextDisabled
    val DisabledBorder = Border.copy(alpha = 0.48f)
    val DisabledOverlay = NuvioPrimitives.black.copy(alpha = 0.42f)

    val surfaces = NuvioSurfaceColors(
        background = Background,
        raised = BackgroundElevated,
        card = BackgroundCard,
        default = Surface,
        variant = SurfaceVariant,
        panel = Panel,
        overlay = Overlay,
        field = Field,
        menu = Menu,
        modal = Modal,
        playerOverlay = PlayerOverlay,
        divider = Divider
    )

    val text = NuvioTextColors(
        primary = TextPrimary,
        secondary = TextSecondary,
        tertiary = TextTertiary,
        disabled = TextDisabled,
        inverse = TextInverse,
        onAccent = OnSecondary,
        onOverlay = NuvioPrimitives.white,
        metadata = TextSecondary
    )

    val focus = NuvioFocusColors(
        ring = FocusRing,
        background = FocusBackground,
        content = FocusContent,
        accent = Secondary,
        scrim = FocusScrim
    )

    val selection = NuvioSelectionColors(
        background = Secondary,
        foreground = OnSecondary,
        border = SecondaryVariant,
        mutedBackground = FocusBackground,
        mutedForeground = TextPrimary
    )

    val media = NuvioMediaColors(
        heroScrim = Scrim,
        imageScrim = ImageScrim,
        posterFallback = PosterFallback,
        videoControlsScrim = VideoControlsScrim,
        glassPanelTop = Panel.copy(alpha = 0.84f),
        glassPanelMiddle = BackgroundElevated.copy(alpha = 0.80f),
        glassPanelBottom = Background.copy(alpha = 0.78f),
        glow = FocusRing.copy(alpha = 0.32f)
    )

    val status = NuvioStatusColors(
        rating = Rating,
        error = Error,
        warning = Warning,
        success = Success,
        info = Info,
        watched = Watched,
        unwatched = Unwatched,
        cached = Cached,
        torrent = Torrent,
        premium = Premium
    )

    val disabled = NuvioDisabledColors(
        container = DisabledContainer,
        content = DisabledContent,
        border = DisabledBorder,
        overlay = DisabledOverlay
    )

    val source = NuvioSourceColors(
        trakt = NuvioPrimitives.trakt,
        tmdb = NuvioPrimitives.tmdb,
        imdb = NuvioPrimitives.imdb,
        mdblist = NuvioPrimitives.mdblist
    )

    val contrastPairs = listOf(
        NuvioContrastPair(TextPrimary, Background),
        NuvioContrastPair(TextPrimary, BackgroundCard),
        NuvioContrastPair(TextSecondary, Background),
        NuvioContrastPair(OnSecondary, Secondary),
        NuvioContrastPair(FocusContent, FocusBackground),
        NuvioContrastPair(NuvioPrimitives.white, PlayerOverlay)
    )
}

object NuvioColors {
    val Background: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.Background

    val BackgroundElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.BackgroundElevated

    val BackgroundCard: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.BackgroundCard

    val Surface: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.Surface

    val SurfaceVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.SurfaceVariant

    val Primary = NuvioPrimitives.neutral200
    val PrimaryVariant = NuvioPrimitives.neutral400
    val OnPrimary = semanticForegroundFor(Primary)
    val TextPrimary = NuvioPrimitives.neutral100
    val TextSecondary = NuvioPrimitives.neutral400
    val TextTertiary = NuvioPrimitives.neutral600
    val TextDisabled = NuvioPrimitives.neutral700
    val Rating = NuvioPrimitives.rating
    val Error = NuvioPrimitives.error
    val Success = NuvioPrimitives.success
    val Warning = NuvioPrimitives.warning
    val Info = NuvioPrimitives.info
    val Border = NuvioPrimitives.neutral750

    val Secondary: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.Secondary

    val SecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.SecondaryVariant

    val OnSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.OnSecondary

    val OnSecondaryVariant: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.OnSecondaryVariant

    val FocusRing: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.FocusRing

    val FocusBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.FocusBackground

    val BorderFocused: Color
        @Composable
        @ReadOnlyComposable
        get() = NuvioTheme.colors.BorderFocused
}
