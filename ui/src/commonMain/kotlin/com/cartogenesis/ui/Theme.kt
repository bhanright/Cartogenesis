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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.staticCompositionLocalOf
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
    choice: ThemeChoice = ThemeChoice.SYSTEM,
    scale: Float = 1f,
    /**
     * Whether this is being driven by a fingertip, which is a property of the theme and not of any
     * control: F5 makes the sliders and the switches bigger by changing one set of numbers here
     * rather than by editing the controls, exactly as F1 recoloured them.
     */
    coarsePointer: Boolean = false,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val scaled = remember(density, scale) {
        // Only the density moves, not the font scale: a font scale is the reader's own accessibility
        // setting and multiplying it here would apply this twice to text and once to everything
        // else, so the interface would come apart rather than grow.
        Density(density.density * scale, density.fontScale)
    }
    val targets = if (coarsePointer) TouchTargets.TOUCH else TouchTargets.POINTER
    CompositionLocalProvider(
        LocalDensity provides scaled,
        LocalTouchTargets provides targets,
        LocalChromeDetail provides choice.detail()
    ) {
        MaterialTheme(
            colorScheme = choice.scheme(dark),
            typography = cartogenesisTypography(choice),
            shapes = AtlasShapes,
            content = content
        )
    }
}

/**
 * Which chrome the window is dressed in.
 *
 * [SYSTEM] is the default and is what F1 shipped: the desktop's or the browser's own light/dark
 * setting, followed live. The four named ones are a deliberate refusal of the usual "light, dark,
 * auto" triple — the application already owns ten palettes, one per map style, and the two
 * chromes F1 wrote are literally two of them (Vellum's paper, and the author's site). So the extra
 * choices are lifted from the styles rather than invented: choosing Nautical dresses the window in
 * the admiralty chart's buff and oxide, Midnight in its slate and brass-gold, Mars in basalt and
 * rust. A reader who works in one style can put the whole window in it.
 *
 * The map is *not* restyled by this, and that separation is deliberate: which style a map is drawn
 * in is a property of the map (and of the exported file), while this is a property of the room the
 * map is being looked at in.
 */
enum class ThemeChoice(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    NAUTICAL("Nautical"),
    MIDNIGHT("Midnight"),
    MARS("Mars"),
    HIGH_CONTRAST("High contrast"),
    COLORBLIND("Colorblind"),
    ALLIED("Allied"),
    HALLOWED("Hallowed"),
    BAROQUE("Baroque");

    /** [systemDark] is consulted only by [SYSTEM]; every other choice is an answer already. */
    internal fun scheme(systemDark: Boolean): ColorScheme = when (this) {
        SYSTEM -> if (systemDark) DarkAtlas else LightAtlas
        LIGHT -> LightAtlas
        DARK -> DarkAtlas
        NAUTICAL -> NauticalChrome
        MIDNIGHT -> MidnightChrome
        MARS -> MarsChrome
        HIGH_CONTRAST -> HighContrastChrome
        COLORBLIND -> ColorblindChrome
        ALLIED -> AlliedChrome
        HALLOWED -> HallowedChrome
        BAROQUE -> BaroqueChrome
    }

    /**
     * Whether this chrome is a dark one, for anything that has to know before the scheme exists.
     *
     * [SYSTEM] has no answer of its own, so it returns null and the caller falls back to the host.
     */
    internal fun isDark(): Boolean? = when (this) {
        SYSTEM -> null
        LIGHT, NAUTICAL, ALLIED, HALLOWED, BAROQUE -> false
        DARK, MIDNIGHT, MARS, HIGH_CONTRAST, COLORBLIND -> true
    }

    /**
     * Everything about a chrome that is not a colour or a type size.
     *
     * See [ChromeDetail]. Five of the eleven answer with something other than the default, and the
     * six that came before F6 all answer with the default itself — which is what keeps their
     * screenshots pixel-identical.
     */
    internal fun detail(): ChromeDetail = when (this) {
        SYSTEM, LIGHT, DARK, NAUTICAL, MIDNIGHT, MARS -> ChromeDetail.PLAIN
        HIGH_CONTRAST -> HighContrastDetail
        COLORBLIND -> ColorblindDetail
        ALLIED -> AlliedDetail
        HALLOWED -> HallowedDetail
        BAROQUE -> BaroqueDetail
    }
}

/**
 * What a section rule looks like under a heading.
 *
 * The panel's headings are ruled off by [com.cartogenesis.ui.SectionRule], which asks the chrome
 * which of these it wants rather than being told at each of the six call sites. That indirection is
 * the whole point: Baroque's double hairline with a diamond at each end and Hallowed's doubled gold
 * are properties of a *theme*, and a theme that had to be applied by hand wherever a heading
 * happens to be drawn is not a theme.
 */
internal enum class SectionRuleStyle {
    /** One hairline at the weight a pen would draw it. What every chrome before F6 has. */
    PLAIN,

    /** Two hairlines a gap apart, in the chrome's [ChromeDetail.ruleAccent]. */
    DOUBLED,

    /** The same two, with a small lozenge centred on each end — the rule the site draws in CSS. */
    DOUBLED_WITH_DIAMONDS
}

/**
 * The part of a chrome that is not in its [ColorScheme] or its [Typography].
 *
 * F6 asks for five things that a colour scheme cannot say: a rule drawn twice in gold, a rule with
 * a diamond at each end, headings set as capitals, a state told by a shape as well as by a hue, and
 * an accent that is a *mark* rather than a word. Each of those would otherwise have to be written
 * where the control is used, which is exactly what `Controls.kt` exists to prevent — so they are
 * declared here instead, handed down through [LocalChromeDetail], and read once by the composable
 * whose job the thing is.
 *
 * Every field defaults to what the six chromes before F6 already did, so those are untouched.
 */
internal class ChromeDetail(
    val sectionRule: SectionRuleStyle = SectionRuleStyle.PLAIN,
    /** The ink an ornamental rule is drawn in. Null means the scheme's own `outlineVariant`. */
    val ruleAccent: Color? = null,
    /** Headings set as capitals, which is as near to small capitals as an OFL text face gets. */
    val smallCapsHeadings: Boolean = false,
    /**
     * The accent where it is a mark rather than a word — a border, a slider's fill, a switch's
     * bead. Null means the scheme's `primary`, which is what every chrome but High contrast wants.
     *
     * High contrast is the exception because WCAG asks two different questions of the two roles:
     * 7:1 of anything that is read (1.4.6) and 3:1 of anything that is merely seen (1.4.11). The
     * saturated blue that chrome is specified with reaches 4.67:1 against black, which is a good
     * mark and an unreadable word, so the mark keeps it and the word takes the same hue lifted.
     */
    val markAccent: Color? = null,
    /**
     * Whether a state that is otherwise carried by hue also gets a shape: the armed button
     * underlined, a chosen chip ruled twice as heavily, a disabled one struck through.
     */
    val shapeCues: Boolean = false,
    /** The weight of a rule or a border. 2 dp is High contrast's answer to a hairline. */
    val stroke: Dp = 1.dp,
    /** The two strips over the map. Null means [OverMap.Strip], which is deliberately translucent. */
    val overMapStrip: Color? = null,
    /** Whether the cartouche is boxed, the way a map margin boxes its title block. */
    val boxedCartouche: Boolean = false
) {
    /** The accent where it is a border, a fill or a bead rather than a word. */
    fun mark(scheme: ColorScheme): Color = markAccent ?: scheme.primary

    /** The ink a section rule is drawn in. */
    fun rule(scheme: ColorScheme): Color = ruleAccent ?: scheme.outlineVariant

    /** The ground of the two strips laid over the chart. */
    fun strip(): Color = overMapStrip ?: OverMap.Strip

    companion object {
        val PLAIN = ChromeDetail()
    }
}

internal val LocalChromeDetail = staticCompositionLocalOf { ChromeDetail.PLAIN }

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

// ---- The three chromes lifted from map styles, added by F4. ----
//
// Each is built the same way the two above are: the style's `paper` is the ground, its `coastline`
// is the ink, its `border` is the accent, and a stop from its own ramp is the wash a selection
// takes. Nothing is invented, so a chrome and the style it came from cannot drift apart, and the
// rule that made the first two work — `surfaceTint` equal to the surface, so no tonal fill
// survives — is kept in all three.

// NAUTICAL: buff paper, the chart's near-black blue for ink, oxide red for the accent.
private val ChartPaper = Color(0xFFF4EAD2)
private val ChartRaised = Color(0xFFFAF3E2)
private val ChartSunk = Color(0xFFE9DCBE)
private val ChartInk = Color(0xFF1B2C3A)
private val ChartInkFaded = Color(0xFF4C6172)
private val ChartOxide = Color(0xFF8A3B2E)
private val ChartWash = Color(0xFFD6E9F0)
private val ChartRule = Color(0xFFB8A886)

private val NauticalChrome: ColorScheme = lightColorScheme(
    primary = ChartOxide,
    onPrimary = ChartRaised,
    primaryContainer = ChartWash,
    onPrimaryContainer = ChartInk,
    inversePrimary = Color(0xFF6E9DB5),
    secondary = Color(0xFF3E6E8C),
    onSecondary = ChartRaised,
    secondaryContainer = ChartWash,
    onSecondaryContainer = ChartInk,
    tertiary = ChartInkFaded,
    onTertiary = ChartRaised,
    tertiaryContainer = Color(0xFFEDF6F9),
    onTertiaryContainer = ChartInk,
    background = ChartPaper,
    onBackground = ChartInk,
    surface = ChartRaised,
    onSurface = ChartInk,
    surfaceVariant = ChartSunk,
    onSurfaceVariant = ChartInkFaded,
    surfaceTint = ChartRaised,
    inverseSurface = ChartInk,
    inverseOnSurface = ChartRaised,
    error = Color(0xFF9E2B20),
    onError = ChartRaised,
    errorContainer = Color(0xFFEED6CF),
    onErrorContainer = Color(0xFF3A120C),
    outline = ChartRule,
    outlineVariant = Color(0xFFD3C4A4),
    scrim = Color(0xFF10202C),
    surfaceBright = Color(0xFFFDF8EC),
    surfaceDim = Color(0xFFE6D9BC),
    surfaceContainerLowest = Color(0xFFFEFAF1),
    surfaceContainerLow = Color(0xFFFBF5E6),
    surfaceContainer = ChartRaised,
    surfaceContainerHigh = Color(0xFFF0E7D0),
    surfaceContainerHighest = ChartSunk
)

// MIDNIGHT: the moonlit map's own indigo and slate, with its brass-gold border as the accent and
// its deliberately bright river blue kept for anything secondary — the one thing that style
// refuses to dim is the water, and the chrome keeps that promise.
private val NightGround = Color(0xFF10151F)
private val NightRaised = Color(0xFF161D2A)
private val NightSunk = Color(0xFF1E2635)
private val NightRule = Color(0xFF313C4E)
private val NightText = Color(0xFFC8D2DE)
private val NightTextDim = Color(0xFF8A94A2)
private val NightGold = Color(0xFFD8A05A)
private val NightWater = Color(0xFF7FC6E8)

private val MidnightChrome: ColorScheme = darkColorScheme(
    primary = NightGold,
    onPrimary = NightGround,
    primaryContainer = Color(0xFF2C2519),
    onPrimaryContainer = NightGold,
    inversePrimary = Color(0xFF8A5A20),
    secondary = NightWater,
    onSecondary = NightGround,
    secondaryContainer = Color(0xFF1C2B38),
    onSecondaryContainer = NightWater,
    tertiary = NightTextDim,
    onTertiary = NightGround,
    tertiaryContainer = NightSunk,
    onTertiaryContainer = NightText,
    background = NightGround,
    onBackground = NightText,
    surface = NightRaised,
    onSurface = NightText,
    surfaceVariant = NightSunk,
    onSurfaceVariant = NightTextDim,
    surfaceTint = NightRaised,
    inverseSurface = Color(0xFFAEB7C4),
    inverseOnSurface = NightGround,
    error = Color(0xFFD0705E),
    onError = NightGround,
    errorContainer = Color(0xFF4A1C15),
    onErrorContainer = Color(0xFFF0D5CF),
    outline = NightRule,
    outlineVariant = Color(0xFF262F3D),
    scrim = Color(0xFF070B12),
    surfaceBright = Color(0xFF27303F),
    surfaceDim = NightGround,
    surfaceContainerLowest = Color(0xFF0C1017),
    surfaceContainerLow = Color(0xFF131924),
    surfaceContainer = NightRaised,
    surfaceContainerHigh = Color(0xFF1C2431),
    surfaceContainerHighest = Color(0xFF222B3A)
)

// MARS: basalt ground, rust and ochre accents, dust-pale text. The same faces, as the spec asks —
// nothing about the typography changes with the chrome, only the colours.
private val BasaltGround = Color(0xFF15100D)
private val BasaltRaised = Color(0xFF1D1613)
private val BasaltSunk = Color(0xFF261D18)
private val BasaltRule = Color(0xFF453228)
private val Dust = Color(0xFFE8D8C0)
private val DustDim = Color(0xFFA8917A)
private val Rust = Color(0xFFC2683A)
private val Ochre = Color(0xFFD8A45A)

private val MarsChrome: ColorScheme = darkColorScheme(
    primary = Rust,
    onPrimary = BasaltGround,
    primaryContainer = Color(0xFF32211A),
    onPrimaryContainer = Ochre,
    inversePrimary = Color(0xFF7E3A20),
    secondary = Ochre,
    onSecondary = BasaltGround,
    secondaryContainer = Color(0xFF32211A),
    onSecondaryContainer = Dust,
    tertiary = DustDim,
    onTertiary = BasaltGround,
    tertiaryContainer = BasaltSunk,
    onTertiaryContainer = Dust,
    background = BasaltGround,
    onBackground = Dust,
    surface = BasaltRaised,
    onSurface = Dust,
    surfaceVariant = BasaltSunk,
    onSurfaceVariant = DustDim,
    surfaceTint = BasaltRaised,
    inverseSurface = Dust,
    inverseOnSurface = BasaltGround,
    error = Color(0xFFD4573A),
    onError = BasaltGround,
    errorContainer = Color(0xFF4A1A0E),
    onErrorContainer = Color(0xFFF2D9CC),
    outline = BasaltRule,
    outlineVariant = Color(0xFF31241D),
    scrim = Color(0xFF0A0705),
    surfaceBright = Color(0xFF2E231C),
    surfaceDim = BasaltGround,
    surfaceContainerLowest = Color(0xFF0F0B09),
    surfaceContainerLow = Color(0xFF181210),
    surfaceContainer = BasaltRaised,
    surfaceContainerHigh = Color(0xFF241B16),
    surfaceContainerHighest = Color(0xFF2B211A)
)

// ---- F6's five. ----
//
// Unlike F4's three, these are not lifted from map styles: they are asked for by name — high
// contrast, colour-blind, allied, hallowed, baroque — and each is a room rather than a chart. What
// they share with the eight before them is the mechanism, exactly: a complete Material scheme with
// `surfaceTint` equal to the surface so no tonal fill survives, containers that are washes rather
// than blocks, and not one colour written at a call site.
//
// Two of the five also carry something a scheme cannot express — a rule drawn twice, a state told
// by a shape, capitals, an italic, a size — and every one of those goes through [ChromeDetail] or
// through [typographyFor], never through the composable that happens to draw the thing.

// HIGH CONTRAST: black ground, white text, 2 dp rules, one blue.
//
// The blue is the interesting decision. #1A6EFF is the accent the spec names and it measures
// 4.67:1 against pure black — comfortably past WCAG 1.4.11's 3:1 for a control's own boundary, and
// well short of 1.4.6's 7:1 for anything that has to be read. A chrome whose entire promise is
// contrast cannot print words in it. So the saturated blue is the *mark* — the slider's fill, the
// bead in the switch, the 2 dp border round the armed button and the chosen chip — and the same
// hue lifted to #6FA8FF (8.83:1 on black) is the accent wherever it is a word. See
// [ChromeDetail.markAccent].
private val HcBlack = Color(0xFF000000)
private val HcWhite = Color(0xFFFFFFFF)

/** The accent as a mark. The hex F6 names. */
private val HcBlueMark = Color(0xFF1A6EFF)

/** The same hue as a word: 8.83:1 on black, where the mark manages 4.67:1. */
private val HcBlueText = Color(0xFF6FA8FF)

/** A red that is still a red at 9.2:1 on black. */
private val HcAlarm = Color(0xFFFF8A75)

/**
 * A popover needs an edge, and this chrome has forbidden itself translucency and shadow.
 *
 * One step off black rather than black, so a menu or a dialog is a plane in front of the window
 * instead of a hole in it. White on it is 15.3:1, which is still past AAA with room to spare.
 */
private val HcRaised = Color(0xFF1A1A1A)

private val HighContrastChrome: ColorScheme = darkColorScheme(
    primary = HcBlueText,
    onPrimary = HcBlack,
    primaryContainer = HcBlack,
    onPrimaryContainer = HcBlueText,
    inversePrimary = HcBlueMark,
    secondary = HcWhite,
    onSecondary = HcBlack,
    secondaryContainer = HcBlack,
    onSecondaryContainer = HcWhite,
    tertiary = HcWhite,
    onTertiary = HcBlack,
    tertiaryContainer = HcBlack,
    onTertiaryContainer = HcWhite,
    background = HcBlack,
    onBackground = HcWhite,
    surface = HcBlack,
    onSurface = HcWhite,
    surfaceVariant = HcBlack,
    // Nothing is muted in this chrome: a dimmed grey is the first thing a high-contrast setting
    // exists to abolish, so the secondary text colour is the primary one.
    onSurfaceVariant = HcWhite,
    surfaceTint = HcBlack,
    inverseSurface = HcWhite,
    inverseOnSurface = HcBlack,
    error = HcAlarm,
    onError = HcBlack,
    errorContainer = HcBlack,
    onErrorContainer = HcAlarm,
    outline = HcWhite,
    outlineVariant = HcWhite,
    scrim = HcBlack,
    surfaceBright = HcRaised,
    surfaceDim = HcBlack,
    surfaceContainerLowest = HcBlack,
    surfaceContainerLow = HcBlack,
    surfaceContainer = HcBlack,
    surfaceContainerHigh = HcRaised,
    surfaceContainerHighest = HcRaised
)

private val HighContrastDetail = ChromeDetail(
    markAccent = HcBlueMark,
    stroke = 2.dp,
    // "No translucency anywhere": the two strips over the map are 76% opaque by default, which is
    // the one place in the application where text is read through something.
    overMapStrip = Color(0xFF000000)
)

// COLORBLIND: warm greys, and nothing that means anything by hue alone.
//
// Dark rather than light, and that follows from the two colours the spec names. Okabe and Ito's
// orange (#E69F00) and sky blue (#56B4E9) were chosen for marks on a white page — as *text* on one
// they measure 2.1:1 and 2.2:1, which is not a chrome anyone can use. On a warm near-black they
// are 7.3:1 and 7.2:1, both past AAA, and the hue pair survives every simulation. So the greys go
// dark and the two accents keep their exact values.
//
// The rest of the promise is not a colour at all: see [ChromeDetail.shapeCues], which is what puts
// an underline under the armed button, doubles the rule round the chosen chip and strikes the
// label of a disabled one, so that no state in the application is told by hue alone.
private val CbGround = Color(0xFF211F1D)
private val CbRaised = Color(0xFF2A2724)
private val CbSunk = Color(0xFF35312D)
private val CbRule = Color(0xFF565049)
private val CbText = Color(0xFFF2EEE8)
private val CbTextDim = Color(0xFFC0B9B0)

/** Okabe–Ito orange: the current item. */
private val CbOrange = Color(0xFFE69F00)

/** Okabe–Ito sky blue: focus. */
private val CbSky = Color(0xFF56B4E9)

/**
 * Okabe–Ito bluish green, lifted for the dark ground: anything that has gone right.
 *
 * #009E73 as published measures 4.34:1 on this surface, which is a mark rather than a word; the
 * same hue at higher luminance is 6.37:1 and keeps every separation the set was chosen for.
 */
private val CbGreen = Color(0xFF2FBF95)

/**
 * Okabe–Ito reddish purple, for anything that has gone wrong.
 *
 * Not their vermillion, and that is the whole reason this palette is worth measuring: #D55E00
 * beside #E69F00 is 12.5 apart to normal vision and **6.0** under deuteranopia, because both
 * collapse toward the same yellow. Okabe and Ito say as much — the eight are safe pairwise, not
 * every pair equally. The reddish purple keeps 13.3 from the orange under every simulation.
 */
private val CbVermillion = Color(0xFFCC79A7)

private val ColorblindChrome: ColorScheme = darkColorScheme(
    primary = CbOrange,
    onPrimary = Color(0xFF1A1815),
    primaryContainer = Color(0xFF3A2E14),
    onPrimaryContainer = CbOrange,
    inversePrimary = Color(0xFF8A6100),
    secondary = CbSky,
    onSecondary = Color(0xFF1A1815),
    secondaryContainer = Color(0xFF16303C),
    onSecondaryContainer = CbSky,
    tertiary = CbGreen,
    onTertiary = Color(0xFF1A1815),
    tertiaryContainer = Color(0xFF10322A),
    onTertiaryContainer = CbGreen,
    background = CbGround,
    onBackground = CbText,
    surface = CbRaised,
    onSurface = CbText,
    surfaceVariant = CbSunk,
    onSurfaceVariant = CbTextDim,
    surfaceTint = CbRaised,
    inverseSurface = CbText,
    inverseOnSurface = CbGround,
    error = CbVermillion,
    onError = Color(0xFF1A1815),
    errorContainer = Color(0xFF35202B),
    onErrorContainer = CbVermillion,
    outline = CbRule,
    outlineVariant = Color(0xFF433D37),
    scrim = Color(0xFF100E0D),
    surfaceBright = Color(0xFF3C3833),
    surfaceDim = CbGround,
    surfaceContainerLowest = Color(0xFF171614),
    surfaceContainerLow = Color(0xFF1F1D1B),
    surfaceContainer = CbRaised,
    surfaceContainerHigh = Color(0xFF322E2A),
    surfaceContainerHighest = Color(0xFF3A3631)
)

private val ColorblindDetail = ChromeDetail(shapeCues = true)

// ALLIED: a 1940s Army Map Service sheet.
//
// Buff paper, olive drab, ivory, grid-numeral red and navy ink, exactly the five the spec names —
// but which Material role each takes is decided by what an AMS sheet actually is. The sheet is
// *printed on* the buff; the olive drab is the overprint and the margin the sheet is mounted in.
// Making the panel a block of #4B5320 with ivory type would leave nowhere for the red to go: the
// grid numerals measure 1.24:1 against olive drab, which is not an accent but an absence. So the
// buff is the paper the panel is printed on, the navy is the ink it is ruled and lettered in, the
// red is the grid numeral at 4.07:1 on that paper, and the olive drab is the ground the whole
// sheet lies on, the filled state and the inverse surface.
//
// The display face is set as capitals on the section headings — see [ChromeDetail.smallCapsHeadings]
// and Allied's own tracking in [typographyFor] — and the cartouche is boxed, the way the title
// block of a map margin is.
private val AmsBuff = Color(0xFFD9CBA3)
private val AmsBuffRaised = Color(0xFFE6DBBB)
private val AmsBuffSunk = Color(0xFFCDBE93)
private val AmsOlive = Color(0xFF4B5320)
private val AmsIvory = Color(0xFFF2ECD9)
private val AmsRed = Color(0xFFB22222)
private val AmsNavy = Color(0xFF1E2438)
private val AmsNavyFaded = Color(0xFF3A4258)

private val AlliedChrome: ColorScheme = lightColorScheme(
    primary = AmsRed,
    onPrimary = AmsIvory,
    primaryContainer = Color(0xFFE6D6AC),
    onPrimaryContainer = AmsNavy,
    inversePrimary = Color(0xFFE28A80),
    secondary = AmsNavy,
    onSecondary = AmsBuff,
    // The overprint, and the one place the olive drab is a block rather than a margin: a chosen
    // chip, a switch that is on, anything the sheet has stamped. Ivory on it is 6.95:1.
    secondaryContainer = AmsOlive,
    onSecondaryContainer = AmsIvory,
    tertiary = AmsOlive,
    onTertiary = AmsIvory,
    tertiaryContainer = Color(0xFFDED2AC),
    onTertiaryContainer = Color(0xFF2C3212),
    background = AmsOlive,
    onBackground = AmsIvory,
    surface = AmsBuff,
    onSurface = AmsNavy,
    surfaceVariant = AmsBuffSunk,
    onSurfaceVariant = AmsNavyFaded,
    surfaceTint = AmsBuff,
    inverseSurface = AmsOlive,
    inverseOnSurface = AmsIvory,
    error = Color(0xFF7A1010),
    onError = AmsIvory,
    errorContainer = Color(0xFFE2C6A8),
    onErrorContainer = Color(0xFF3A0A0A),
    outline = AmsNavyFaded,
    outlineVariant = Color(0xFF8A8F86),
    scrim = Color(0xFF171A0C),
    surfaceBright = Color(0xFFEFE6CB),
    surfaceDim = Color(0xFFC4B48D),
    surfaceContainerLowest = Color(0xFFEFE6CB),
    surfaceContainerLow = AmsBuffRaised,
    surfaceContainer = AmsBuff,
    surfaceContainerHigh = Color(0xFFCFC09A),
    surfaceContainerHighest = Color(0xFFC4B48D)
)

private val AlliedDetail = ChromeDetail(smallCapsHeadings = true, boxedCartouche = true)

// HALLOWED: an illuminated manuscript.
//
// Lapis is the ground the page lies on, vellum is the page, ink is what is written on it, and gold
// is the leaf. The leaf itself — #D4AF37, the spec's warmed #C9A227 — is a *rule*, not a word: on
// vellum it measures 1.73:1, which is what gold leaf on vellum has always measured and is why an
// illuminator used it for borders and initials rather than for text. So [ChromeDetail.ruleAccent]
// carries it, the section rules are doubled in it, and the accent that has to be read is the same
// pigment in shadow (#8A6A12, 4.1:1 on vellum). Crimson is the danger colour the spec asks for.
private val LapisGround = Color(0xFF1B2A5B)
private val Vellum = Color(0xFFF1E9D2)
private val VellumRaised = Color(0xFFF7F1E0)
private val VellumSunk = Color(0xFFE4D9BC)
private val ManuscriptInk = Color(0xFF23201A)
private val ManuscriptInkFaded = Color(0xFF4A4335)

/** Gold leaf. Never a word; see the note above. */
private val GoldLeaf = Color(0xFFD4AF37)

/** The same pigment in shadow, for the accent where it has to be read. */
private val GoldShadow = Color(0xFF8A6A12)
private val Crimson = Color(0xFF8A1C1C)

private val HallowedChrome: ColorScheme = lightColorScheme(
    primary = GoldShadow,
    onPrimary = Vellum,
    primaryContainer = Color(0xFFEFE1B6),
    onPrimaryContainer = Color(0xFF4A3708),
    inversePrimary = GoldLeaf,
    secondary = Color(0xFF26386F),
    onSecondary = Vellum,
    // The lapis, where it is a block rather than the ground the page lies on: a chosen chip, a
    // switch that is on. Vellum on it is 11.33:1, which is what an initial letter measures.
    secondaryContainer = LapisGround,
    onSecondaryContainer = Vellum,
    tertiary = ManuscriptInkFaded,
    onTertiary = Vellum,
    tertiaryContainer = VellumSunk,
    onTertiaryContainer = ManuscriptInk,
    background = LapisGround,
    onBackground = Vellum,
    surface = Vellum,
    onSurface = ManuscriptInk,
    surfaceVariant = VellumSunk,
    onSurfaceVariant = ManuscriptInkFaded,
    surfaceTint = Vellum,
    inverseSurface = LapisGround,
    inverseOnSurface = Vellum,
    error = Crimson,
    onError = Vellum,
    errorContainer = Color(0xFFEBD3C6),
    onErrorContainer = Color(0xFF3A0C0C),
    outline = Color(0xFFB9A87C),
    outlineVariant = Color(0xFFDCCFA9),
    scrim = Color(0xFF0C1330),
    surfaceBright = Color(0xFFFBF6E9),
    surfaceDim = Color(0xFFDFD3B2),
    surfaceContainerLowest = Color(0xFFFBF6E9),
    surfaceContainerLow = VellumRaised,
    surfaceContainer = Vellum,
    surfaceContainerHigh = Color(0xFFE9E0C6),
    surfaceContainerHighest = VellumSunk
)

private val HallowedDetail = ChromeDetail(
    sectionRule = SectionRuleStyle.DOUBLED,
    ruleAccent = GoldLeaf
)

// BAROQUE: a gilt-and-walnut room.
//
// Walnut is the panelling the room is lined in, cream marble is the surface of everything set
// against it, oxblood velvet is the accent — lit, #7E1414, because unlit oxblood at 5D0000 is a
// colour you can only see in a well-lit room — and the rules are gilt. The display face is set in
// italic for the headings (see [typographyFor]: the family has no italic cut, so this is a
// synthesised slant and says so), and the section rules are the site's own: two hairlines with a
// small lozenge centred on each end.
private val Walnut = Color(0xFF3B2415)
private val Marble = Color(0xFFEFE6D8)
private val MarbleRaised = Color(0xFFF7F1E8)
private val MarbleSunk = Color(0xFFE2D6C4)
private val WalnutInk = Color(0xFF2A1B10)
private val WalnutInkFaded = Color(0xFF5A4635)
private val Oxblood2 = Color(0xFF5D0000)
private val OxbloodLit2 = Color(0xFF7E1414)
private val Gilt = Color(0xFFB08D3A)

private val BaroqueChrome: ColorScheme = lightColorScheme(
    primary = OxbloodLit2,
    onPrimary = Marble,
    primaryContainer = Color(0xFFE9D8C6),
    onPrimaryContainer = Color(0xFF3A0808),
    inversePrimary = Color(0xFFE0A79E),
    secondary = Oxblood2,
    onSecondary = Marble,
    // The walnut, where it is a block rather than the panelling behind everything: a chosen chip,
    // a switch that is on. Marble on it is 11.72:1.
    secondaryContainer = Walnut,
    onSecondaryContainer = Marble,
    tertiary = Color(0xFF6E5320),
    onTertiary = Marble,
    tertiaryContainer = Color(0xFFEDE0C6),
    onTertiaryContainer = Color(0xFF3A2A08),
    background = Walnut,
    onBackground = Marble,
    surface = Marble,
    onSurface = WalnutInk,
    surfaceVariant = MarbleSunk,
    onSurfaceVariant = WalnutInkFaded,
    surfaceTint = Marble,
    inverseSurface = Walnut,
    inverseOnSurface = Marble,
    error = Color(0xFF8C2E1E),
    onError = Marble,
    errorContainer = Color(0xFFEBD2C6),
    onErrorContainer = Color(0xFF3A1006),
    outline = Color(0xFFB9A288),
    outlineVariant = Color(0xFFD6C6AE),
    scrim = Color(0xFF1A0F08),
    surfaceBright = Color(0xFFFBF6EE),
    surfaceDim = Color(0xFFDDD0BE),
    surfaceContainerLowest = Color(0xFFFBF6EE),
    surfaceContainerLow = MarbleRaised,
    surfaceContainer = Marble,
    surfaceContainerHigh = Color(0xFFE8DCCB),
    surfaceContainerHighest = MarbleSunk
)

private val BaroqueDetail = ChromeDetail(
    sectionRule = SectionRuleStyle.DOUBLED_WITH_DIAMONDS,
    ruleAccent = Gilt
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

    /**
     * Thinner than [Veil], for the two strips that sit on the map all the time.
     *
     * The progress banner is a moment and can afford to be nearly opaque; the toolbar and the
     * legend are always there, and at the banner's weight they would read as two black bars with a
     * map between them rather than as annotations on a chart.
     */
    val Strip = Color(0xC214100E)
    val Ink = Color(0xFF15110F)
    val Parchment = Color(0xFFF2E7CF)
    val ParchmentDim = Color(0xCCF2E7CF)

    /** An unchosen name in the style strip: present, legible, plainly not the current one. */
    val ParchmentFaint = Color(0x8CF2E7CF)

    /** The hairlines that divide the strip into cells, and rule it off from the map. */
    val Rule = Color(0x3DF2E7CF)
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
private fun cartogenesisTypography(choice: ThemeChoice): Typography {
    val display = FontFamily(
        Font(Res.font.spectral_regular, FontWeight.Normal),
        Font(Res.font.spectral_semibold, FontWeight.SemiBold)
    )
    val sans = FontFamily(
        Font(Res.font.plex_sans_regular, FontWeight.Normal),
        Font(Res.font.plex_sans_medium, FontWeight.Medium)
    )
    return remember(display, sans, choice) { typographyFor(choice, display, sans) }
}

/**
 * The same two faces, set the way this chrome sets them.
 *
 * Three of F6's five ask for a change of *type* rather than of colour, and none of them is a thing
 * a call site should be doing: High contrast wants everything one step larger, Allied wants the
 * display face tracked out for capitals, Baroque wants the headings in italic. So each is a
 * transformation of the one [Typography] rather than a second one written out, which is also what
 * guarantees the other eight chromes are untouched — they take the identity transformation.
 */
private fun typographyFor(
    choice: ThemeChoice,
    display: FontFamily,
    sans: FontFamily
): Typography {
    val base = typography(display, sans)
    return when (choice) {
        // One step larger, everywhere. 1.15 is a Material type step; the interface scale in
        // Settings multiplies the density instead, so the two compose rather than fight.
        ThemeChoice.HIGH_CONTRAST -> base.map { it.enlarged(1.15f) }

        // Capitals need air between them or they set as a wall; 1.2sp on the display styles is
        // what turns Spectral's caps into something that reads as small capitals rather than as
        // shouting. The words themselves are uppercased by the heading composable, from
        // [ChromeDetail.smallCapsHeadings] — this is only the letterfit.
        ThemeChoice.ALLIED -> base.mapDisplay {
            it.copy(letterSpacing = (it.letterSpacing.value + 1.2f).sp)
        }

        // Spectral is bundled in roman only, so this is a synthesised slant rather than a drawn
        // italic — which is what a gilt cartouche's lettering was too, more often than not.
        ThemeChoice.BAROQUE -> base.mapDisplay {
            it.copy(fontStyle = FontStyle.Italic, fontSynthesis = FontSynthesis.Style)
        }

        else -> base
    }
}

private fun TextStyle.enlarged(factor: Float): TextStyle = copy(
    fontSize = fontSize * factor,
    lineHeight = lineHeight * factor
)

/** Every style in the set, transformed. */
private fun Typography.map(f: (TextStyle) -> TextStyle) = Typography(
    displayLarge = f(displayLarge), displayMedium = f(displayMedium), displaySmall = f(displaySmall),
    headlineLarge = f(headlineLarge), headlineMedium = f(headlineMedium),
    headlineSmall = f(headlineSmall),
    titleLarge = f(titleLarge), titleMedium = f(titleMedium), titleSmall = f(titleSmall),
    bodyLarge = f(bodyLarge), bodyMedium = f(bodyMedium), bodySmall = f(bodySmall),
    labelLarge = f(labelLarge), labelMedium = f(labelMedium), labelSmall = f(labelSmall)
)

/**
 * The nine styles set in the display face, transformed; the six sans ones left alone.
 *
 * The split is the one F1 drew: the serif names things, the sans measures them. An italic or a
 * tracked-out capital belongs to the naming half — a slider's value set in tracked italics would
 * be a value nobody could read at a glance.
 */
private fun Typography.mapDisplay(f: (TextStyle) -> TextStyle) = copy(
    displayLarge = f(displayLarge), displayMedium = f(displayMedium), displaySmall = f(displaySmall),
    headlineLarge = f(headlineLarge), headlineMedium = f(headlineMedium),
    headlineSmall = f(headlineSmall),
    titleLarge = f(titleLarge), titleMedium = f(titleMedium), titleSmall = f(titleSmall)
)

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
