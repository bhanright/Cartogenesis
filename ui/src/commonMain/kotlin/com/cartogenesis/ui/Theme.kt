package com.cartogenesis.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cartogenesis.ui.generated.resources.Res
import com.cartogenesis.ui.generated.resources.plex_sans_medium
import com.cartogenesis.ui.generated.resources.plex_sans_regular
import com.cartogenesis.ui.generated.resources.spectral_regular
import com.cartogenesis.ui.generated.resources.spectral_semibold
import org.jetbrains.compose.resources.Font

/**
 * The chrome, in the atlas's own vocabulary.
 *
 * The application used to run inside a bare `MaterialTheme`, which meant stock Material 3: purple
 * tonal buttons, lavender chips, one sans face at one weight. It looked like a demonstration of
 * Material rather than a thing for drawing maps, and it disagreed with everything inside the map
 * frame — an aged chart in a sepia palette, surrounded by a colour scheme from a phone.
 *
 * So the surround is drawn from the same materials as the map. In daylight the ground is Vellum's
 * own paper and its own inks, taken literally from [com.cartogenesis.cartography.MapStyle.VELLUM]
 * so that chrome and map cannot drift apart: the panel is the page, the text is the ink the
 * coastlines are drawn in, and the one accent is the oxide brown the borders are drawn in. After
 * dark it is the author's website — near-black ink, bone text, brass for anything live and oxblood
 * for anything that has gone wrong.
 *
 * Everything here is set **once**, as a Material `ColorScheme`, `Typography` and `Shapes`, so that
 * every control in the application picks it up without being dressed by hand where it is used. Two
 * details do most of the work:
 *
 *  - `surfaceTint` is set to the surface colour itself in both schemes. Material tints a raised
 *    surface toward the primary colour in proportion to its elevation, which is where the lilac
 *    cards came from; making the tint equal to the surface turns every tonal fill in the
 *    application into flat paper, and leaves the hairline borders to do the separating.
 *  - the containers (`secondaryContainer` and friends, which is what a selected chip and a filled
 *    button reach for) are washes of the paper rather than saturated blocks, so a selection reads
 *    as a stain on the page rather than a highlighter.
 */
@Composable
fun CartogenesisTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (dark) DarkAtlas else LightAtlas,
        typography = cartogenesisTypography(),
        shapes = AtlasShapes,
        content = content
    )
}

// The two palettes, named once. Light is Vellum's; dark is the website's CSS variables verbatim.

/** Vellum's `paper`, lightened a shade for a whole window's worth of it. */
private val PaperGround = Color(0xFFEFE4C8)
private val PaperRaised = Color(0xFFF6EEDB)
private val PaperSunk = Color(0xFFE4D8B9)

/** Darker than Vellum's `coastline`, which is a line weight rather than a text colour. */
private val Ink = Color(0xFF2B2117)

/** Vellum's `river`: the muted ink, for anything secondary. */
private val InkFaded = Color(0xFF6E5B3C)

/** Vellum's `coastline`. */
private val Slate = Color(0xFF5B4A2F)

/** Vellum's `border`: the one accent. */
private val Sepia = Color(0xFF6B3F2A)

/** Vellum's first land stop, which is what a stained page looks like where something is selected. */
private val SepiaWash = Color(0xFFE2D2A9)

private val Rule = Color(0xFFBFAD86)
private val RuleFaint = Color(0xFFD7C8A5)

/** Nautical's `border`: an oxide red that belongs on the same page. */
private val Oxide = Color(0xFF8A3B2E)

// bfunk.online
private val InkDark = Color(0xFF15110F)
private val InkRaised = Color(0xFF1C1714)
private val InkSunk = Color(0xFF241D18)
private val Hairline = Color(0xFF3A2F28)
private val Bone = Color(0xFFE8DFD0)
private val BoneDim = Color(0xFF9C9187)
private val Parchment = Color(0xFFF2E7CF)
private val Brass = Color(0xFFC9A227)
private val BrassDim = Color(0xFF8D7326)
private val Oxblood = Color(0xFF5D0000)
private val OxbloodLit = Color(0xFF7E1414)

/**
 * Daylight: Vellum's paper and Vellum's inks.
 *
 * The numbers are the style's own, lifted from `MapStyle.VELLUM` — `paper` for the ground,
 * `landRamp`'s first stop for a selected wash, `river` for muted text, `coastline` for the ink and
 * `border` for the accent — with only the ground lightened a shade, because a whole window of
 * `0xF0E3C2` is a stronger yellow than a map's worth of it.
 */
private val LightAtlas: ColorScheme = lightColorScheme(
    primary = Sepia,
    onPrimary = PaperRaised,
    primaryContainer = SepiaWash,
    onPrimaryContainer = Ink,
    inversePrimary = Brass,
    secondary = Slate,
    onSecondary = PaperRaised,
    secondaryContainer = SepiaWash,
    onSecondaryContainer = Ink,
    tertiary = InkFaded,
    onTertiary = PaperRaised,
    tertiaryContainer = SepiaWash,
    onTertiaryContainer = Ink,
    background = PaperGround,
    onBackground = Ink,
    surface = PaperRaised,
    onSurface = Ink,
    surfaceVariant = PaperSunk,
    onSurfaceVariant = InkFaded,
    // Equal to the surface, so Material's tonal elevation cannot tint anything. See the class
    // note: this is what removes the lilac cards without touching a single call site.
    surfaceTint = PaperRaised,
    inverseSurface = Ink,
    inverseOnSurface = PaperRaised,
    error = Oxide,
    onError = PaperRaised,
    errorContainer = Color(0xFFE8CFC0),
    onErrorContainer = Color(0xFF3A1810),
    outline = Rule,
    outlineVariant = RuleFaint,
    scrim = Color(0xFF17120B),
    surfaceBright = Color(0xFFF9F3E3),
    surfaceDim = Color(0xFFE3D7B8),
    surfaceContainerLowest = Color(0xFFFBF6E9),
    surfaceContainerLow = Color(0xFFF7F0DE),
    surfaceContainer = PaperRaised,
    surfaceContainerHigh = Color(0xFFEDE2C7),
    surfaceContainerHighest = PaperSunk
)

/**
 * After dark: bfunk.online.
 *
 * The author's site palette, exactly — ink, hairline, bone, bone-dim, parchment, brass, brass-dim,
 * oxblood, oxblood-lit — mapped onto the Material roles rather than reinvented. Brass is the only
 * bright thing on the page and so is the only accent; oxblood carries anything that failed.
 */
private val DarkAtlas: ColorScheme = darkColorScheme(
    primary = Brass,
    onPrimary = InkDark,
    primaryContainer = Color(0xFF2A2114),
    onPrimaryContainer = Brass,
    inversePrimary = Sepia,
    secondary = BrassDim,
    onSecondary = InkDark,
    secondaryContainer = Color(0xFF2A2114),
    onSecondaryContainer = Parchment,
    tertiary = BoneDim,
    onTertiary = InkDark,
    tertiaryContainer = Color(0xFF241E1A),
    onTertiaryContainer = Bone,
    background = InkDark,
    onBackground = Bone,
    surface = InkRaised,
    onSurface = Bone,
    surfaceVariant = InkSunk,
    onSurfaceVariant = BoneDim,
    surfaceTint = InkRaised,
    inverseSurface = Parchment,
    inverseOnSurface = InkDark,
    error = OxbloodLit,
    onError = Parchment,
    errorContainer = Oxblood,
    onErrorContainer = Parchment,
    outline = Hairline,
    outlineVariant = Color(0xFF2B231D),
    scrim = Color(0xFF0A0807),
    surfaceBright = Color(0xFF2A231E),
    surfaceDim = InkDark,
    surfaceContainerLowest = Color(0xFF100D0B),
    surfaceContainerLow = Color(0xFF181310),
    surfaceContainer = InkRaised,
    surfaceContainerHigh = Color(0xFF221B16),
    surfaceContainerHighest = Color(0xFF29211B)
)

/**
 * The handful of colours that sit *over the map* rather than beside it.
 *
 * The progress banner, the zoom readout and a placed label are drawn on top of a rendered chart,
 * whose own paper and backdrop come from its [com.cartogenesis.cartography.MapStyle] and have
 * nothing to do with whether the surrounding window is light or dark. They therefore cannot take
 * the scheme's colours — a paper-coloured banner over Vellum would vanish — so they are fixed, and
 * fixed *here*, rather than being spelled out as literals wherever they are drawn.
 */
internal object OverMap {
    /** A wash of the dark ink, for anything laid over the chart. */
    val Veil = Color(0xE014100E)
    val Ink = Color(0xFF15110F)
    val Parchment = Color(0xFFF2E7CF)
    val ParchmentDim = Color(0xCCF2E7CF)
}

/**
 * Corners a draughtsman would accept: nearly square.
 *
 * Material's default is a 12dp pill on almost everything, which is what makes an unstyled Compose
 * application recognisable at a glance. A ruled page has right angles, so these are the smallest
 * radii that still read as deliberate rather than as an accident of rendering.
 */
private val AtlasShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(3.dp),
    large = RoundedCornerShape(4.dp),
    extraLarge = RoundedCornerShape(6.dp)
)

/**
 * Two faces, and the rule for which goes where.
 *
 * Spectral, a serif cut for screens, carries everything that names something — the application, a
 * section, a realm. IBM Plex Sans, which is narrow and legible small, carries everything that
 * *measures* something — a slider's value, a resolution, a status line. That split is the oldest
 * convention in cartography: the title and the legend headings are set, the annotations are
 * lettered.
 *
 * Both are bundled as Compose resources rather than named as system families, so the browser build
 * renders in the same faces as the desktop instead of falling back to whatever the page's default
 * happens to be. They are open licensed (SIL OFL 1.1; the licences are in `ui/licences`).
 *
 * Composed once here and remembered: `Font` loads its bytes through the resource reader, and
 * building the families inside each `TextStyle` would ask for them again on every recomposition.
 */
@Composable
private fun cartogenesisTypography(): Typography {
    val display = FontFamily(
        Font(Res.font.spectral_regular, FontWeight.Normal),
        Font(Res.font.spectral_semibold, FontWeight.SemiBold)
    )
    val sans = FontFamily(
        Font(Res.font.plex_sans_regular, FontWeight.Normal),
        Font(Res.font.plex_sans_medium, FontWeight.Medium)
    )
    return remember(display, sans) { typography(display, sans) }
}

private fun typography(display: FontFamily, sans: FontFamily) = Typography(
    displayLarge = TextStyle(
        fontFamily = display, fontWeight = FontWeight.Normal,
        fontSize = 46.sp, lineHeight = 54.sp, letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = display, fontWeight = FontWeight.Normal,
        fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = display, fontWeight = FontWeight.Normal,
        fontSize = 29.sp, lineHeight = 36.sp, letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = display, fontWeight = FontWeight.Normal,
        fontSize = 27.sp, lineHeight = 34.sp, letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = display, fontWeight = FontWeight.Normal,
        fontSize = 23.sp, lineHeight = 30.sp, letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = display, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 27.sp, letterSpacing = 0.2.sp
    ),
    // The application's own name, and the heading of any pane.
    titleLarge = TextStyle(
        fontFamily = display, fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = 0.3.sp
    ),
    titleMedium = TextStyle(
        fontFamily = display, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = 0.4.sp
    ),
    // Section headings — World, Features, Style, Export. Small caps would be truer still, but the
    // face has no small-cap set, and faking them by uppercasing would change the words.
    titleSmall = TextStyle(
        fontFamily = display, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.8.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.1.sp
    ),
    labelLarge = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp
    ),
    labelSmall = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Normal,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp
    )
)
