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
import com.cartogenesis.ui.generated.resources.plex_mono_bold
import com.cartogenesis.ui.generated.resources.plex_mono_regular
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
 * So the surround is drawn from the same materials as the map: the panel is a page, the text is
 * the ink a coastline is drawn in, and the one accent is the oxide brown a border is drawn in. The
 * inks are literally a map style's — the oxide accent, the muted river brown of secondary text and
 * the coastline's own are [com.cartogenesis.cartography.MapStyle.VELLUM]'s, so chrome and map cannot
 * drift apart on what a *line* is coloured. The **grounds** are not a style's and no longer try to
 * be (F29, 2026-09-14): a whole window of Vellum's yellow paper pulled against a Natural map's
 * greens and a cobalt sea, so daylight is a modern atlas plate's off-white and after dark a neutral
 * charcoal, with bone text, brass for anything live and oxblood for anything that has gone wrong.
 * The author's website follows the dark scheme rather than the other way round — `site/index.html`
 * mirrors it value for value, and `SitePaletteContrastTest` fails if the two drift.
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
 * setting, followed live. The fourteen named ones are a deliberate refusal of the usual "light,
 * dark, auto" triple — the application already owns eleven palettes, one per map style, and takes
 * its accents and its rules from them even where the ground is its own (see [CartogenesisTheme]).
 * F4's three are lifted from the styles rather than invented: choosing Nautical dresses the window
 * in the admiralty chart's buff and oxide, Midnight in its slate and brass-gold, Mars in basalt and
 * rust, so a reader who works in one style can put the whole window in it. F6's five, F7's four,
 * F24's one and F28's one are asked for by name — high contrast, colour-blind, Allied, Hallowed,
 * Baroque, Matrix, Hessian, Roman, Hitchcock, Lemon Blueberry, Blacklight — and each is a room
 * rather than a chart. Seventeen is more than a flat list can carry, so [group] puts them on three
 * shelves; nothing about a name or a stored value moves.
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
    BAROQUE("Baroque"),
    MATRIX("Matrix"),
    HESSIAN("Hessian"),
    ROMAN("Roman"),
    HITCHCOCK("Hitchcock"),
    LEMON_BLUEBERRY("Lemon Blueberry"),
    BLACKLIGHT("Blacklight");

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
        MATRIX -> MatrixChrome
        HESSIAN -> HessianChrome
        ROMAN -> RomanChrome
        HITCHCOCK -> HitchcockChrome
        LEMON_BLUEBERRY -> LemonBlueberryChrome
        BLACKLIGHT -> BlacklightChrome
    }

    /**
     * Whether this chrome is a dark one, for anything that has to know before the scheme exists.
     *
     * [SYSTEM] has no answer of its own, so it returns null and the caller falls back to the host.
     */
    internal fun isDark(): Boolean? = when (this) {
        SYSTEM -> null
        LIGHT, NAUTICAL, ALLIED, HALLOWED, BAROQUE, HESSIAN, ROMAN -> false
        DARK, MIDNIGHT, MARS, HIGH_CONTRAST, COLORBLIND, MATRIX, HITCHCOCK,
        LEMON_BLUEBERRY, BLACKLIGHT -> true
    }

    /**
     * Which of the three shelves this chrome sits on in the picker and in the View menu.
     *
     * Seventeen names in one flat run is a list nobody reads to the end of, and the three groups
     * are not arbitrary: [ThemeGroup.STANDARD] is what the application shipped with and what a
     * reader who wants no opinion should take, [ThemeGroup.ACCESSIBLE] is the two whose promise is
     * a measured threshold rather than a look, and [ThemeGroup.STYLED] is the twelve that are a
     * room to work in. No name and no stored value moves: this is a heading over a list, nothing
     * more.
     */
    internal fun group(): ThemeGroup = when (this) {
        SYSTEM, LIGHT, DARK -> ThemeGroup.STANDARD
        HIGH_CONTRAST, COLORBLIND -> ThemeGroup.ACCESSIBLE
        NAUTICAL, MIDNIGHT, MARS, ALLIED, HALLOWED, BAROQUE,
        MATRIX, HESSIAN, ROMAN, HITCHCOCK, LEMON_BLUEBERRY, BLACKLIGHT -> ThemeGroup.STYLED
    }

    /**
     * Everything about a chrome that is not a colour or a type size.
     *
     * See [ChromeDetail]. Eleven of the seventeen answer with something other than the default, and
     * the six that came before F6 all answer with the default itself — which is what keeps their
     * screenshots pixel-identical.
     */
    internal fun detail(): ChromeDetail = when (this) {
        SYSTEM, LIGHT, DARK, NAUTICAL, MIDNIGHT, MARS -> ChromeDetail.PLAIN
        HIGH_CONTRAST -> HighContrastDetail
        COLORBLIND -> ColorblindDetail
        ALLIED -> AlliedDetail
        HALLOWED -> HallowedDetail
        BAROQUE -> BaroqueDetail
        MATRIX -> MatrixDetail
        HESSIAN -> HessianDetail
        ROMAN -> RomanDetail
        HITCHCOCK -> HitchcockDetail
        LEMON_BLUEBERRY -> LemonBlueberryDetail
        BLACKLIGHT -> BlacklightDetail
    }
}

/**
 * The three shelves the seventeen chromes are offered on.
 *
 * A grouping, not a setting: nothing here is stored, nothing here is a name a reader has already
 * chosen, and [ThemeChoice.entries] is still the whole list in its own order for anything that
 * wants it. See [ThemeChoice.group].
 */
internal enum class ThemeGroup(val label: String) {
    STANDARD("Standard"),
    ACCESSIBLE("Accessible"),
    STYLED("Styled")
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
    DOUBLED_WITH_DIAMONDS,

    /** Hessian's running stitch: 4 dp of thread, 3 dp of cloth, all the way across. */
    STITCHED,

    /** Roman's Greek key: a meander in a 6 dp repeating unit, drawn as one stroked path. */
    MEANDER,

    /**
     * Hitchcock's bar, cut into three and the pieces slipped past one another.
     *
     * The Psycho titles are a name sliced into bands that never quite line up; this is the same
     * gesture at the width of a section heading.
     */
    CUT_BAR
}

/**
 * How a chrome dresses the title block at the foot of the map.
 *
 * The cartouche is drawn over the chart in [OverMap]'s ink rather than in the scheme's, so a chrome
 * cannot recolour it — but it can say what shape it is, and four of them do.
 */
internal enum class CartoucheStyle {
    /** Words on the strip, nothing round them. Every chrome before F6. */
    PLAIN,

    /** Ruled off from the sheet, the way a map margin boxes its title block. Allied. */
    BOXED,

    /** A sewn label: the same box, its border a running stitch. Hessian. */
    STITCHED,

    /** Boxed twice, a hair apart, the way an inscription is framed. Roman. */
    DOUBLE_RULE,

    /** A small spiral wound in beside the world's name. Hitchcock, and it is Vertigo's. */
    SPIRAL
}

/**
 * Anything drawn *into* a panel behind its content.
 *
 * One chrome asks for this and it is the whole of its subject: Hessian is a cloth, and a cloth that
 * was only a colour would be a paint chip.
 */
internal enum class PanelTexture {
    NONE,

    /** Two families of hairlines at ±45°, 6 dp apart, at 8% — a plain weave. */
    CROSSHATCH
}

/**
 * Where a chrome sets its headings as capitals.
 *
 * Two answers rather than one because F6 and F7 mean different things by it. Allied wanted the
 * *panel's* six section headings lettered like a map margin, and that is all it ever touched; the
 * four F7 chromes are typographic through and through — a terminal prompt, a stencil, an
 * inscription, a title card — and set every heading in the application, the settings dialog's rows
 * and the menus' own headings included. Keeping the two apart is what leaves Allied byte-identical
 * while F7 reaches further.
 */
internal enum class HeadingCase {
    /** As written. Every chrome but Allied and F7's four. */
    SENTENCE,

    /** Capitals in the panel's section headings, and nowhere else. Allied. */
    PANEL_CAPITALS,

    /** Capitals wherever a heading is set. F7's four. */
    CAPITALS
}

/**
 * The part of a chrome that is not in its [ColorScheme] or its [Typography].
 *
 * F6 asked for five things a colour scheme cannot say: a rule drawn twice in gold, a rule with a
 * diamond at each end, headings set as capitals, a state told by a shape as well as by a hue, and
 * an accent that is a *mark* rather than a word. F7 added seven more: a prompt and an interpunct
 * before a heading, a label for a button that is a block rather than a stain, a shape for the
 * cartouche, a texture behind a panel and the ink to draw it in, and a ground for the window
 * itself. Each of those would otherwise have to be written where the control is used, which is
 * exactly what `Controls.kt` exists to prevent — so they are declared here instead, handed down
 * through [LocalChromeDetail], and read once by the composable whose job the thing is.
 *
 * Every field defaults to what the six chromes before F6 already did, so those are untouched — and
 * `ChromeContrastTest` and `ChromeGalleryTest` between them assert that the eleven chromes before
 * F7 are unchanged in every Material role and pixel-identical where they are drawn.
 */
internal class ChromeDetail(
    val sectionRule: SectionRuleStyle = SectionRuleStyle.PLAIN,
    /** The ink an ornamental rule is drawn in. Null means the scheme's own `outlineVariant`. */
    val ruleAccent: Color? = null,
    /** Headings set as capitals, which is as near to small capitals as an OFL text face gets. */
    val headings: HeadingCase = HeadingCase.SENTENCE,
    /**
     * Set before a heading, verbatim. Matrix's `>`, and nothing else has ever wanted one.
     *
     * A prompt rather than a bullet: on a terminal the caret is what says the next word is a thing
     * you may act on, and a section heading in this application is exactly that.
     */
    val headingPrefix: String? = null,
    /**
     * Whether the words of a multi-word heading are separated by an interpunct rather than a space.
     *
     * Roman's, and it is not decoration: a Latin inscription has no word spaces, it has points, and
     * `WORKING·RESOLUTION` is the difference between capitals and lettering.
     */
    val headingInterpunct: Boolean = false,
    /**
     * The accent where it is a mark rather than a word — a border, a slider's fill, a switch's
     * bead. Null means the scheme's `primary`, which is what every chrome but High contrast wants.
     *
     * High contrast is the exception because WCAG asks two different questions of the two roles:
     * 7:1 of anything that is read (1.4.6) and 3:1 of anything that is merely seen (1.4.11). The
     * saturated blue that chrome is specified with reaches 4.67:1 against black, which is a good
     * mark and an unreadable word, so the mark keeps it and the word takes the same hue lifted.
     * Hitchcock's vermilion is the same story and is measured in `ChromeContrastTest`.
     */
    val markAccent: Color? = null,
    /**
     * The label of the armed button, where the chrome has made that button a solid block.
     *
     * Null, and the label is the scheme's `primary` on a wash of `primaryContainer` — a stain and a
     * darker rule, which is what F1 decided even the loudest control on a page of ink should be.
     * Three chromes disagree, and for the same reason each time: a phosphor terminal, a stencil and
     * a Saul Bass title card all invert rather than stain, so the container is the accent at full
     * strength and the label is the ground.
     */
    val buttonLabel: Color? = null,
    /**
     * Whether a state that is otherwise carried by hue also gets a shape: the armed button
     * underlined, a chosen chip ruled twice as heavily, a disabled one struck through.
     */
    val shapeCues: Boolean = false,
    /** The weight of a rule or a border. 2 dp is High contrast's answer to a hairline. */
    val stroke: Dp = 1.dp,
    /** The two strips over the map. Null means [OverMap.Strip], which is deliberately translucent. */
    val overMapStrip: Color? = null,
    /**
     * The ground the whole window is painted in, behind the panels and the gutters.
     *
     * Null, and it is the scheme's `surface` — which is what the window has taken since F1, and
     * which is why every light chrome before F7 is a page with panels of the same paper on it,
     * told apart by a ruled edge. That is right for a chart and wrong for four subjects whose whole
     * premise is a *panel set against a ground*: burlap with linen labels sewn to it, marble panels
     * on a Pompeian wall, a terminal's windows on a black screen, a Bass card's blocks on charcoal.
     * Material's `background` role is the colour each of those wants and the frame does not read it,
     * so the chrome says so here rather than the frame changing its mind for all seventeen.
     */
    val windowGround: Color? = null,
    /** What shape the cartouche is, the way a map margin decides about its title block. */
    val cartouche: CartoucheStyle = CartoucheStyle.PLAIN,
    /** Anything drawn into a panel behind its content. Hessian's weave, and nothing else. */
    val panelTexture: PanelTexture = PanelTexture.NONE,
    /** The ink [panelTexture] is drawn in, and at what strength. Ignored where there is no texture. */
    val textureInk: Color = Color.Transparent
) {
    /** The accent where it is a border, a fill or a bead rather than a word. */
    fun mark(scheme: ColorScheme): Color = markAccent ?: scheme.primary

    /** The label of the armed button. */
    fun label(scheme: ColorScheme): Color = buttonLabel ?: scheme.primary

    /** The ink a section rule is drawn in. */
    fun rule(scheme: ColorScheme): Color = ruleAccent ?: scheme.outlineVariant

    /** The ground of the two strips laid over the chart. */
    fun strip(): Color = overMapStrip ?: OverMap.Strip

    /** The ground the window itself is painted in. */
    fun ground(scheme: ColorScheme): Color = windowGround ?: scheme.surface

    /**
     * A heading, lettered the way this chrome letters one.
     *
     * [panel] is true only at the panel's own six section headings, which is the whole reach of
     * [HeadingCase.PANEL_CAPITALS]. The words themselves are never rewritten — a heading is
     * uppercased, pointed and prompted, and that is all.
     */
    fun heading(title: String, panel: Boolean = false): String {
        val capitals = headings == HeadingCase.CAPITALS ||
            (panel && headings == HeadingCase.PANEL_CAPITALS)
        var text = title
        if (headingInterpunct) {
            text = text.split(' ').filter { it.isNotEmpty() }.joinToString("·")
        }
        if (capitals) text = text.uppercase()
        return if (headingPrefix == null) text else headingPrefix + text
    }

    companion object {
        val PLAIN = ChromeDetail()
    }
}

internal val LocalChromeDetail = staticCompositionLocalOf { ChromeDetail.PLAIN }

// The two palettes, named once. Both are inked in a map style's colours and grounded in their own:
// see [CartogenesisTheme] for why, and the two ladders below for how the tiers are placed.

/**
 * The three papers of a modern atlas plate: the window, a panel raised off it, a panel sunk into it.
 *
 * Off-white and barely warm, rather than the yellow of
 * [com.cartogenesis.cartography.MapStyle.VELLUM] that these were until F29. A whole window of
 * Vellum's `paper` is a far stronger yellow than a map's worth of it, and it fought every map that
 * was not Vellum — a Natural world's greens and a cobalt sea most of all. The inks that are read on
 * these are still Vellum's, and did not move.
 *
 * The rest of the light scheme's papers lie on the two segments these three define — see
 * [LightAtlas], where each is named with the fraction of the way along it stands.
 */
private val PaperGround = Color(0xFFF4F1EA)
private val PaperRaised = Color(0xFFFAF8F3)
private val PaperSunk = Color(0xFFE9E4D8)

/** Darker than Vellum's `coastline`, which is a line weight rather than a text colour. */
private val Ink = Color(0xFF2B2117)

/** Vellum's `river`: the muted ink, for anything secondary. */
private val InkFaded = Color(0xFF6E5B3C)

/** Vellum's `coastline`. */
private val Slate = Color(0xFF5B4A2F)

/** Vellum's `border`: the one accent. */
private val Sepia = Color(0xFF6B3F2A)

/**
 * What a stained page looks like where something is selected: [Sepia] laid on [PaperGround] at an
 * eighth.
 *
 * A stain of the accent rather than a block of it, which is the rule the containers all follow (see
 * the class note). It was Vellum's first land stop until F29 took Vellum's paper out of the scheme,
 * and a land stop is a colour a *map* owns, so the wash is now stated as the arithmetic rather than
 * borrowed: an eighth is the fraction that puts it as far from the paper as the land stop stood
 * from Vellum's — 17, 22 and 24 levels of red, green and blue against that stop's 13, 18 and 31.
 */
private val SepiaWash = Color(0xFFE3DBD2)

private val Rule = Color(0xFFBFAD86)
private val RuleFaint = Color(0xFFD7C8A5)

/** Nautical's `border`: an oxide red that belongs on the same page. */
private val Oxide = Color(0xFF8A3B2E)

/**
 * The dark scheme's four charcoals: the window, a raised panel, a sunk panel, and the rule between.
 *
 * One tone at four depths, and the tone is stated once here because every other dark surface is
 * built from it — red, green and blue in the proportion **1 : 1.116 : 1.268**, which is what these
 * four share to the last level. A neutral charcoal rather than the brown-black these were until F29
 * (William, 2026-09-14): the warm ground made every map in the frame look yellow-lit, and made the
 * cobalt of a Natural sea look like a mistake.
 *
 * The proportion is scaled by a **step**, which is simply the red channel, and the rest of the
 * scheme is the same tone at its own step. [DarkAtlas] names each one's. The steps are the ones the
 * warm palette had, each three lower — the amount [InkDark] came down — so every gap on the ladder
 * is the gap it was; [Hairline] is the one exception, a step tighter than the arithmetic would put
 * it, because that is the value that measured best against the panels it separates.
 */
private val InkDark = Color(0xFF121417)
private val InkRaised = Color(0xFF191C20)
private val InkSunk = Color(0xFF21252A)
private val Hairline = Color(0xFF363C44)

// The inks, the accents and the alarm, which F29 did not touch: brand continuity is these and the
// typography, not the grounds they are read on.
private val Bone = Color(0xFFE8DFD0)
private val BoneDim = Color(0xFF9C9187)
private val Parchment = Color(0xFFF2E7CF)
private val Brass = Color(0xFFC9A227)
private val BrassDim = Color(0xFF8D7326)
private val Oxblood = Color(0xFF5D0000)
private val OxbloodLit = Color(0xFF7E1414)

/** [Brass] laid on [InkDark] at an eighth: what an armed button and a chosen chip are filled with. */
private val BrassStain = Color(0xFF292619)

/** [Bone] laid on [InkDark] at a sixteenth: the quietest of the three filled blocks. */
private val BoneStain = Color(0xFF1F2123)

/**
 * Daylight: an atlas plate's off-white, inked in Vellum's own inks.
 *
 * The inks are the style's own, lifted from `MapStyle.VELLUM` — `river` for muted text, `coastline`
 * for the ink, `border` for the accent — and did not move in F29. The papers did: see [PaperGround].
 *
 * Every paper that is not one of the three named ones lies on a segment between two of them, at the
 * fraction of the way along it stood on the old papers, measured as its distance from white in the
 * red channel. Toward [PaperRaised] from [PaperGround]: `surfaceContainerLow` at 8/7,
 * `surfaceBright` at 10/7, `surfaceContainerLowest` at 12/7 — all three past the raised panel,
 * which is what makes them lighter than it. Toward [PaperSunk]: `surfaceContainerHigh` at 2/11 and
 * `surfaceDim` at 12/11, the one just off the window's own paper and the other just past the sunk
 * one.
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
    // The five papers the KDoc places on the two segments, in the order they lighten.
    surfaceDim = Color(0xFFE8E3D6),
    surfaceContainerHigh = Color(0xFFF2EFE7),
    surfaceContainerLow = Color(0xFFFBF9F4),
    surfaceBright = Color(0xFFFDFBF7),
    surfaceContainerLowest = Color(0xFFFEFDF9),
    surfaceContainer = PaperRaised,
    surfaceContainerHighest = PaperSunk
)

/**
 * After dark: a neutral charcoal, lit by brass.
 *
 * Bone, bone-dim, parchment, brass, brass-dim, oxblood and oxblood-lit are the author's website's,
 * exactly, mapped onto the Material roles rather than reinvented; brass is the only bright thing in
 * the room and so is the only accent, and oxblood carries anything that failed. The grounds were
 * the site's too until F29 replaced them with the charcoals of [InkDark], and the site now follows
 * this file rather than leading it.
 *
 * Every surface here is that one tone at its own step — the red channel, green and blue at 1.116
 * and 1.268 of it. In the order they lighten: `scrim` 7, `surfaceContainerLowest` 13, the window 18,
 * `surfaceContainerLow` 21, a panel 25, `surfaceContainerHigh` 31, a sunk panel 33,
 * `surfaceContainerHighest` 38, `surfaceBright` 39, `outlineVariant` 40, the rule 54.
 *
 * The three containers are the exception, and are stains rather than steps, which is the rule the
 * class note states: `primaryContainer` and `secondaryContainer` are [Brass] laid on the window at
 * an eighth, `tertiaryContainer` is [Bone] on it at a sixteenth. Both reproduce the containers the
 * warm palette had, over the warm window, to within two levels a channel.
 */
private val DarkAtlas: ColorScheme = darkColorScheme(
    primary = Brass,
    onPrimary = InkDark,
    primaryContainer = BrassStain,
    onPrimaryContainer = Brass,
    inversePrimary = Sepia,
    secondary = BrassDim,
    onSecondary = InkDark,
    secondaryContainer = BrassStain,
    onSecondaryContainer = Parchment,
    tertiary = BoneDim,
    onTertiary = InkDark,
    tertiaryContainer = BoneStain,
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
    outlineVariant = Color(0xFF282D33),
    scrim = Color(0xFF070809),
    surfaceBright = Color(0xFF272C31),
    surfaceDim = InkDark,
    surfaceContainerLowest = Color(0xFF0D0F10),
    surfaceContainerLow = Color(0xFF15171B),
    surfaceContainer = InkRaised,
    surfaceContainerHigh = Color(0xFF1F2327),
    surfaceContainerHighest = Color(0xFF262A30)
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
// The display face is set as capitals on the section headings — see [HeadingCase.PANEL_CAPITALS]
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

private val AlliedDetail = ChromeDetail(
    headings = HeadingCase.PANEL_CAPITALS,
    cartouche = CartoucheStyle.BOXED
)

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

// ---- F7's four. ----
//
// Four names and nothing else: Matrix, Hessian, Roman, Hitchcock. Like F6's five these are rooms
// rather than charts, and they are built the same way — a complete Material scheme with
// `surfaceTint` equal to the surface, every ornament declared in [ChromeDetail] and read once by
// the composable whose job it is, and not one colour written at a call site. What they add to the
// machinery is what four subjects that are *typography* rather than palette needed: a third bundled
// face, a prompt and a point before a heading, a texture behind a panel, a label colour for a
// button that is a block rather than a stain, and three more ways to rule off a section.
//
// Every text pair in all four is measured at WCAG AA in `ChromeContrastTest`, and two of the four
// needed the same decision F6 made for High contrast — the colour a spec names is a good mark and a
// thin word — so the mark keeps the named value and the word takes the same hue moved.

// MATRIX: a phosphor terminal.
//
// The 1999 film's screens are a CRT in a dark room: a black that is very slightly green because the
// tube is never quite off, one bright phosphor for everything that matters, a dimmer draw of the
// same phosphor for everything that does not, and amber only when something is wrong. Two decisions
// are worth stating.
//
// The first is that there is no elevation. Material lifts a menu or a dialog by lightening it, and
// on this palette that walks the dimmer green straight past AA — #1F8F49 measures 4.62:1 on the
// panel and only 4.32:1 on the lightened container Material would want for an open menu. A terminal
// has no elevation to express: an overlay is a *hole*, darker than the screen and bordered in
// green. So the containers go down rather than up (4.85:1 at the darkest), which is both what the
// subject looks like and what keeps the spec's own two greens exactly as written.
//
// The second is that the filled states invert. F1's rule is that even the loudest control is a
// stain and a darker rule; a terminal's is that a selected thing is the ground and the screen is
// the ink. So `primaryContainer` and `secondaryContainer` are the phosphor at full strength and
// [ChromeDetail.buttonLabel] paints the label in the black — 13.46:1, the strongest pair in any
// chrome here.
private val TerminalBlack = Color(0xFF030704)
private val TerminalPanel = Color(0xFF071209)

/** The phosphor. Everything that is read, and every filled state's ground. */
private val Phosphor = Color(0xFF3DF07A)

/** The same tube, further from the gun: secondary text, at 4.62:1 on the panel. */
private val PhosphorDim = Color(0xFF1F8F49)

/** The one warning colour a monochrome terminal ever had. */
private val TerminalAmber = Color(0xFFFFB000)

/**
 * The rules: the phosphor at 40%, which is what the spec asks for and what a scan line looks like.
 *
 * Left as an alpha rather than flattened to a hex on purpose — it is drawn over three different
 * near-blacks and should be the same 40% draw on each. Composited over the panel it is #1D6B36,
 * 2.92:1, which is a rule and not a word.
 */
private val PhosphorRule = Color(0x663DF07A)

private val MatrixChrome: ColorScheme = darkColorScheme(
    primary = Phosphor,
    onPrimary = TerminalBlack,
    primaryContainer = Phosphor,
    onPrimaryContainer = TerminalBlack,
    inversePrimary = PhosphorDim,
    secondary = PhosphorDim,
    onSecondary = TerminalBlack,
    secondaryContainer = Phosphor,
    onSecondaryContainer = TerminalBlack,
    tertiary = PhosphorDim,
    onTertiary = TerminalBlack,
    tertiaryContainer = Color(0xFF030A05),
    onTertiaryContainer = Phosphor,
    background = TerminalBlack,
    onBackground = Phosphor,
    surface = TerminalPanel,
    onSurface = Phosphor,
    surfaceVariant = Color(0xFF050E07),
    onSurfaceVariant = PhosphorDim,
    surfaceTint = TerminalPanel,
    inverseSurface = Phosphor,
    inverseOnSurface = TerminalBlack,
    error = TerminalAmber,
    onError = TerminalBlack,
    errorContainer = Color(0xFF2A1C00),
    onErrorContainer = TerminalAmber,
    outline = PhosphorDim,
    outlineVariant = PhosphorRule,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF0B1B0E),
    surfaceDim = TerminalBlack,
    // Downward, not upward: see the note above. An open menu is a darker rectangle with a green
    // edge, which is what an overlay on a terminal is and what holds the dim green at AA.
    surfaceContainerLowest = Color(0xFF010301),
    surfaceContainerLow = Color(0xFF020603),
    surfaceContainer = TerminalPanel,
    surfaceContainerHigh = Color(0xFF050E07),
    surfaceContainerHighest = Color(0xFF030A05)
)

private val MatrixDetail = ChromeDetail(
    headings = HeadingCase.CAPITALS,
    headingPrefix = "> ",
    buttonLabel = TerminalBlack,
    // The screen behind the windows: the black the panels are one shade of green away from, so a
    // panel reads as a lit region of the tube rather than as the whole tube.
    windowGround = TerminalBlack,
    // 85% black. The default strip is a wash of warm ink at 76%, which over a chart reads as a
    // brown smear; a terminal's overlay is black and nearly opaque.
    overMapStrip = Color(0xD9000000)
)

// HESSIAN: the cloth.
//
// Burlap, unbleached linen, dark brown thread, twine, and the stencil red a sack is stamped with.
// The subject is a *weave*, so the chrome carries a texture as well as a palette: two families of
// hairlines at ±45°, 6 dp apart, at 8%, drawn behind everything a panel holds — see
// [PanelTexture.CROSSHATCH]. Every rule in the application becomes a running stitch and the
// cartouche becomes a sewn label.
//
// The weave is measured in rather than assumed harmless, and it is not: composited at 8% it darkens
// each ground by about 4%, which for dark text on light cloth *lowers* contrast. The spec's burlap
// #B3956A leaves the brown at 4.34:1 with the weave in — under AA — so the ground is lifted one
// shade to #BC9E73, exactly as F1 lifted Vellum's paper for a whole window's worth of it. That is
// 4.85:1 woven and 5.42:1 plain, and the text colour the spec names is untouched.
private val Burlap = Color(0xFFBC9E73)
private val Linen = Color(0xFFEDE3CC)
private val LinenSunk = Color(0xFFE0D3B6)

/** The thread everything is written in, and the ink the weave is drawn with at 8%. */
private val ClothInk = Color(0xFF3A2A1B)
private val ClothInkFaded = Color(0xFF5A4632)

/** Twine: the accent as a mark — a border, a rail, a bead, a stitch. 4.81:1 on linen. */
private val Twine = Color(0xFF7A5C3A)

/** The same fibre in shadow, for the accent where it has to be read. 5.22:1 on woven linen. */
private val TwineDark = Color(0xFF6A4E2C)

/** Stencil red: what a sack is stamped with, and what this chrome arms and alarms in. */
private val StencilRed = Color(0xFF8B3A2F)

private val HessianChrome: ColorScheme = lightColorScheme(
    primary = TwineDark,
    onPrimary = Linen,
    // The armed button is the stencil, not a stain: a red block with linen lettering, 6.00:1.
    primaryContainer = StencilRed,
    onPrimaryContainer = Linen,
    inversePrimary = Color(0xFFD9B98C),
    secondary = ClothInkFaded,
    onSecondary = Linen,
    // The twine, where it is a block rather than a thread: a chosen chip, a switch that is on.
    secondaryContainer = Twine,
    onSecondaryContainer = Linen,
    tertiary = ClothInkFaded,
    onTertiary = Linen,
    tertiaryContainer = Color(0xFFDCCFB2),
    onTertiaryContainer = Color(0xFF33251A),
    background = Burlap,
    onBackground = ClothInk,
    surface = Linen,
    onSurface = ClothInk,
    surfaceVariant = LinenSunk,
    onSurfaceVariant = ClothInkFaded,
    surfaceTint = Linen,
    inverseSurface = ClothInk,
    inverseOnSurface = Linen,
    error = StencilRed,
    onError = Linen,
    errorContainer = Color(0xFFE7CDBE),
    onErrorContainer = Color(0xFF3A130C),
    outline = Color(0xFF9C8462),
    outlineVariant = Color(0xFFCFBF9C),
    scrim = Color(0xFF241A10),
    surfaceBright = Color(0xFFF6EEDC),
    surfaceDim = Color(0xFFD3C5A6),
    surfaceContainerLowest = Color(0xFFF6EEDC),
    surfaceContainerLow = Color(0xFFF1E8D2),
    surfaceContainer = Linen,
    surfaceContainerHigh = Color(0xFFE4D8BD),
    surfaceContainerHighest = Color(0xFFDCCEB0)
)

private val HessianDetail = ChromeDetail(
    sectionRule = SectionRuleStyle.STITCHED,
    ruleAccent = Twine,
    headings = HeadingCase.CAPITALS,
    // The armed button is a stencilled block, so its lettering is the cloth: linen on stencil red
    // at 6.00:1, where the twine the accent is otherwise would have been 1.00:1 on it — the two
    // happen to sit at the same luminance, which is exactly the kind of thing a guard is for.
    buttonLabel = Linen,
    cartouche = CartoucheStyle.STITCHED,
    // The cloth itself, with the linen labels sewn onto it. Woven like everything else here.
    windowGround = Burlap,
    panelTexture = PanelTexture.CROSSHATCH,
    textureInk = ClothInk.copy(alpha = 0.08f)
)

// ROMAN: an imperial wall.
//
// Pompeian red is the ground the whole room is painted in, marble is every surface set against it,
// and the lettering is the near-black of a chiselled inscription. Two things make it Roman rather
// than merely red: the headings are pointed rather than spaced — `WORKING·RESOLUTION`, because a
// Latin inscription has no word spaces — and every section is ruled off with a Greek key.
//
// The bronze is the same decision Hallowed made about gold leaf. #9C7A3C on marble measures 3.33:1,
// which is what bronze on stone has always measured and is why it was used for fittings rather than
// for text; so the bronze is the *rule* — the meander, the borders, the beads — and the accent that
// has to be read is the same metal darkened to #7A5C24, 5.17:1.
private val Pompeian = Color(0xFF7A1F1F)
private val RomanMarble = Color(0xFFF1EAD9)
private val RomanMarbleSunk = Color(0xFFE3DAC4)
private val Inscription = Color(0xFF1F1B18)
private val InscriptionFaded = Color(0xFF4A423A)

/** Bronze. The meander and the fittings; never a word. */
private val Bronze = Color(0xFF9C7A3C)

/** The same metal darkened, for the accent where it has to be read. */
private val BronzeDark = Color(0xFF7A5C24)

/** A lit Pompeian, for anything that has gone wrong: 7.12:1 on marble. */
private val PompeianLit = Color(0xFF8A2B2B)

private val RomanChrome: ColorScheme = lightColorScheme(
    primary = BronzeDark,
    onPrimary = RomanMarble,
    // The red, where it is a block: the armed button, with white lettering at 10.28:1.
    primaryContainer = Pompeian,
    onPrimaryContainer = Color(0xFFFFFFFF),
    inversePrimary = Bronze,
    secondary = PompeianLit,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Pompeian,
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = InscriptionFaded,
    onTertiary = RomanMarble,
    tertiaryContainer = Color(0xFFE7DEC8),
    onTertiaryContainer = Color(0xFF33291C),
    background = Pompeian,
    onBackground = Color(0xFFFFFFFF),
    surface = RomanMarble,
    onSurface = Inscription,
    surfaceVariant = RomanMarbleSunk,
    onSurfaceVariant = InscriptionFaded,
    surfaceTint = RomanMarble,
    inverseSurface = Pompeian,
    inverseOnSurface = Color(0xFFFFFFFF),
    error = PompeianLit,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFE9CFC6),
    onErrorContainer = Color(0xFF3A0E0E),
    outline = Color(0xFFB0A183),
    outlineVariant = Color(0xFFD5C9AD),
    scrim = Color(0xFF2A0A0A),
    surfaceBright = Color(0xFFFAF5EA),
    surfaceDim = Color(0xFFD8CDB4),
    surfaceContainerLowest = Color(0xFFFAF5EA),
    surfaceContainerLow = Color(0xFFF5EEDF),
    surfaceContainer = RomanMarble,
    surfaceContainerHigh = Color(0xFFE9E0CB),
    surfaceContainerHighest = Color(0xFFDFD5BE)
)

private val RomanDetail = ChromeDetail(
    sectionRule = SectionRuleStyle.MEANDER,
    ruleAccent = Bronze,
    headings = HeadingCase.CAPITALS,
    headingInterpunct = true,
    markAccent = Bronze,
    buttonLabel = Color(0xFFFFFFFF),
    // The wall the marble is set into.
    windowGround = Pompeian,
    cartouche = CartoucheStyle.DOUBLE_RULE
)

// HITCHCOCK: a Saul Bass title card.
//
// Charcoal, flat black, off-white, and exactly one hot colour. Bass's cards are cut rather than
// drawn — Psycho's name arrives in three bands that never line up — so the section rule is a bar
// sliced in three and slipped, and the cartouche carries Vertigo's spiral wound in beside the
// world's name.
//
// The vermilion is the third instance of F6's two-form accent and the spec predicted it: #E8491D is
// "about 5.5:1" on black by reputation and measures **4.38:1** on the flat-black panel and 4.70:1
// on the charcoal — over AA on the ground, under it on the panel, which is the worse of the two and
// the one that decides. So the true Vertigo vermilion is the *mark* and the block — the armed
// button is a vermilion slab with black lettering at 5.40:1 — and the word takes the same hue
// lifted to #FF7A55, 6.63:1.
private val Charcoal = Color(0xFF151515)
private val FlatBlack = Color(0xFF1C1C1C)
private val BassWhite = Color(0xFFF2EFE8)
private val BassGrey = Color(0xFFB9B3A8)

/** Vertigo vermilion, as named. The block, the rail, the bead, the border. */
private val Vermilion = Color(0xFFE8491D)

/** The same hue lifted, for the accent where it is a word. */
private val VermilionLit = Color(0xFFFF7A55)

/** Bass's other colour. 7.41:1 on the panel. */
private val Mustard = Color(0xFFD9A21B)

private val HitchcockChrome: ColorScheme = darkColorScheme(
    primary = VermilionLit,
    onPrimary = Color(0xFF000000),
    primaryContainer = Vermilion,
    onPrimaryContainer = Color(0xFF000000),
    inversePrimary = Color(0xFF8A2408),
    secondary = Mustard,
    onSecondary = Color(0xFF000000),
    secondaryContainer = Mustard,
    onSecondaryContainer = Color(0xFF000000),
    tertiary = BassGrey,
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF2A2A2A),
    onTertiaryContainer = BassWhite,
    background = Charcoal,
    onBackground = BassWhite,
    surface = FlatBlack,
    onSurface = BassWhite,
    surfaceVariant = Color(0xFF242424),
    onSurfaceVariant = BassGrey,
    surfaceTint = FlatBlack,
    inverseSurface = BassWhite,
    inverseOnSurface = Charcoal,
    // One hot colour on a title card, and it does both jobs: emphasis and alarm. The lifted tint
    // where it is a word, exactly as the accent.
    error = VermilionLit,
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF3A1208),
    onErrorContainer = Color(0xFFFF9E86),
    outline = Color(0xFF6E6A64),
    outlineVariant = Color(0xFF3A3A3A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF303030),
    surfaceDim = Charcoal,
    surfaceContainerLowest = Color(0xFF0E0E0E),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = FlatBlack,
    surfaceContainerHigh = Color(0xFF232323),
    surfaceContainerHighest = Color(0xFF2A2A2A)
)

private val HitchcockDetail = ChromeDetail(
    sectionRule = SectionRuleStyle.CUT_BAR,
    ruleAccent = Vermilion,
    headings = HeadingCase.CAPITALS,
    markAccent = Vermilion,
    buttonLabel = Color(0xFF000000),
    // Charcoal behind the flat-black blocks: a card is printed, not a screen.
    windowGround = Charcoal,
    cartouche = CartoucheStyle.SPIRAL
)

// ---- F24's one. ----

// LEMON BLUEBERRY: the two colours the name says, and the question of which way round they go.
//
// William asked for the pair and left the rest open, so the arrangement was decided by measuring
// rather than by taste. Both were built to the same rules — the same two families of colour, the
// F1 armed button (a wash of the ground stained toward the accent, with the accent for a label,
// which is what every chrome that does not invert does) — and every text pair in each was measured
// against WCAG AA:
//
//   lemon ground, blueberry ink   worst pair 7.38:1 (the secondary as a word), Stop 7.80:1
//   blueberry ground, lemon ink   worst pair 7.76:1 (the alarm as a word),     Stop 10.19:1
//
// Blueberry-as-ground wins both, and not by luck. On a lemon ground the accent has to be dark
// enough to be *read* against a near-white yellow, which pushes it down into the same range as the
// ink — so the accent stops looking like an accent, and the armed button's wash and its label are
// two dark-on-light tones a little way apart. On a blueberry ground the accent is the lemon itself,
// bright against a deep violet, and the button's stain is deeper still, which opens the pair by
// two and a half points. So the fruit is the room and the peel is the writing.
//
// The rest of the palette is derived from the pair rather than chosen beside it. The raised and
// sunk surfaces are the ground lifted toward the bloom on a blueberry's skin — that dusty violet is
// already in the fruit, so a well and a card stay the same colour as the room. The alarm is the one
// tone that is neither: blueberry pigment is an anthocyanin, which is a pH indicator, and squeezing
// a lemon into blueberry juice turns it pink. Both halves of the name are in it, and it is the one
// colour in the chrome that cannot be mistaken for either.

/** The window: the fruit at its darkest, where no light gets through the skin. */
private val BlueberryGround = Color(0xFF151033)

/** A panel raised off it — the same violet, a little further into the flesh. */
private val BlueberryPanel = Color(0xFF1E1845)

/** A sunk panel: a well cut into the fruit, lit by the bloom rather than by a light. */
private val BlueberrySunk = Color(0xFF282052)

/** The stain under an armed control. F1's wash, in the only colour this room has to stain with. */
private val BlueberryWash = Color(0xFF2C2456)

/** The hairline. 3.15:1 on a panel, past WCAG 1.4.11's 3:1 for a control's own boundary. */
private val BlueberryRule = Color(0xFF6F63A6)

/** The fainter rule, for a division that is a hint rather than an edge. */
private val BlueberryRuleFaint = Color(0xFF352C68)

/** The bloom on the skin: a dusty violet, and the chrome's secondary. */
private val BlueberryBloom = Color(0xFFC4B4F0)

/** The text: lemon flesh, pale and warm, 14.20:1 on a panel. */
private val LemonInk = Color(0xFFF7EFC0)

/** The same flesh dimmed for anything secondary. 9.47:1 on a panel. */
private val LemonInkDim = Color(0xFFD0C58C)

/** The peel: the lemon at full strength, and the one accent. 11.97:1 on a panel. */
private val LemonZest = Color(0xFFF0DC7A)

/** The peel in shadow, for the inverse accent Material asks for and nothing here draws large. */
private val LemonZestShaded = Color(0xFF7A6714)

/**
 * The alarm, and it is made of the pair: lemon juice turns blueberry pigment pink.
 *
 * Anthocyanin is a pH indicator, so acid takes it from violet to red — which is why this is the one
 * tone in the chrome that reads as neither of the two colours and cannot be confused with the
 * accent. 7.76:1 on a panel.
 */
private val BerryJuice = Color(0xFFFF8FB8)

/** The same, deep enough to hold pale lettering: an error's own block. */
private val BerryJuiceDeep = Color(0xFF4A1030)

private val LemonBlueberryChrome: ColorScheme = darkColorScheme(
    primary = LemonZest,
    onPrimary = BlueberryGround,
    primaryContainer = BlueberryWash,
    onPrimaryContainer = LemonZest,
    inversePrimary = LemonZestShaded,
    secondary = BlueberryBloom,
    onSecondary = BlueberryGround,
    secondaryContainer = BlueberryWash,
    onSecondaryContainer = LemonInk,
    tertiary = LemonInkDim,
    onTertiary = BlueberryGround,
    tertiaryContainer = BlueberrySunk,
    onTertiaryContainer = LemonInk,
    background = BlueberryGround,
    onBackground = LemonInk,
    surface = BlueberryPanel,
    onSurface = LemonInk,
    surfaceVariant = BlueberrySunk,
    onSurfaceVariant = LemonInkDim,
    surfaceTint = BlueberryPanel,
    inverseSurface = LemonInk,
    inverseOnSurface = BlueberryGround,
    error = BerryJuice,
    onError = Color(0xFF3A0A20),
    errorContainer = BerryJuiceDeep,
    onErrorContainer = Color(0xFFFFC4D8),
    outline = BlueberryRule,
    outlineVariant = BlueberryRuleFaint,
    scrim = Color(0xFF0A0720),
    surfaceBright = Color(0xFF332A66),
    surfaceDim = BlueberryGround,
    surfaceContainerLowest = Color(0xFF100C28),
    surfaceContainerLow = Color(0xFF191333),
    surfaceContainer = BlueberryPanel,
    surfaceContainerHigh = Color(0xFF241D4E),
    surfaceContainerHighest = Color(0xFF2B2359)
)

private val LemonBlueberryDetail = ChromeDetail(
    // The darkest of the fruit behind the panels, so the panels read as things set on a ground
    // rather than as a single flat violet with hairlines ruled across it — the same move Hessian,
    // Roman and Hitchcock make, and the only piece of ornament this chrome takes.
    windowGround = BlueberryGround
)

// ---- F28's one. ----

// BLACKLIGHT: #E6FF42 and #520C94, and the same question F24 asked of its two.
//
// William gave the pair and nothing else, so which of them is the room was decided by measuring.
// Both arrangements were built to one set of rules — the named colour is the panel a word is read
// on, the window and the sunk well are that same colour a step either side of it, the ink is the
// *other* colour taken as far from the panel as the panel's own room allows, the accent is the
// other colour at full strength, and the armed button is F1's stain: the panel taken about ten
// L* toward the ground, never an inverted block. Every text pair in each was then measured
// against WCAG AA, and the armed Stop button with them:
//
//   violet room, lime writing   worst pair 5.57:1 (the alarm as a word), Stop 13.97:1
//   lime room, violet ink       worst pair 4.63:1 (the alarm as a word), Stop  7.73:1
//
// Both clear AA, so the Stop button decided it, and it decided by a factor of nearly two. The
// reason is the lime: #E6FF42 has a relative luminance of 0.887, paler than most papers, so as a
// *ground* it forces everything that has to be seen against it down into the dark end together —
// the alarm can only be a rust at 4.63:1, the secondary a plum at 4.69:1, the hairline barely
// clears 1.4.11 at 3.08:1, and a stain under a button cannot travel far before the button stops
// being a stain and becomes a block. As *writing* the same lime is the brightest thing in the room
// and the wash beneath it the darkest, which is where 13.97:1 comes from. So the lamp is the room
// and the highlighter is the writing, which is also what the name describes: a blacklight is a
// violet tube, and what glows under it is not violet.
//
// The rest of the palette is derived from the pair rather than chosen beside it. Every violet here
// is #520C94's own hue — 312.7 degrees in CIE L*a*b*, and no tone in the scheme is more than 0.4
// of a degree off its hue — moved only in lightness and chroma, and every lime is the
// highlighter's 110.5 degrees the same way, so the chrome has two hues in it and no third. The
// alarm is the one exception and it is derived too: a blacklight is an excitation, not a colour,
// and the lime is one dye's answer to it. A highlighter set holds more than one dye, and the
// orange one under the same lamp emits further down the spectrum — which is why the alarm is
// neither of the two colours and cannot be read as a hotter lime or a warmer violet.

/** The room past the lamp's reach: William's violet at half its lightness, L* 24.3 down to 10.6. */
private val VioletGround = Color(0xFF2B064F)

/** The panel, and it is William's violet exactly. Every word in the chrome is read on this. */
private val VioletPanel = Color(0xFF520C94)

/** A sunk panel: the same violet one step nearer the tube, where the lamp's own light pools. */
private val VioletSunk = Color(0xFF601BA4)

/**
 * The stain under an armed control: the window ground with a trace of the highlighter worked in.
 *
 * F1's wash rather than a block, and ten L* below the panel because a stain darkens what it soaks
 * into — which is the whole of why this arrangement's Stop button measures 13.97:1 and the
 * other's 7.73:1.
 */
private val VioletWash = Color(0xFF32134F)

/** The hairline. 3.31:1 on a panel, past WCAG 1.4.11's 3:1 for a control's own boundary. */
private val VioletRule = Color(0xFFA577CA)

/** The fainter rule, for a division that is a hint rather than an edge. */
private val VioletRuleFaint = Color(0xFF52287A)

/** The haze a tube throws around itself, and the chrome's secondary. 5.66:1 on a panel. */
private val VioletHaze = Color(0xFFD0A6F1)

/** The text: the highlighter thinned almost to white, still green. 10.18:1 on a panel. */
private val LimeInk = Color(0xFFF3F5C9)

/** The same ink thinned less, for anything secondary. 7.66:1 on a panel, 6.43:1 in a well. */
private val LimeInkDim = Color(0xFFD2DA80)

/** The highlighter at full strength, and it is William's lime exactly. 10.19:1 on a panel. */
private val LimeGlow = Color(0xFFE6FF42)

/** The same pigment with the lamp off, for the inverse accent Material asks for. */
private val LimeGlowShaded = Color(0xFF6E7D0F)

/**
 * The alarm, and it is what the same lamp fires in a different dye.
 *
 * A blacklight is an excitation rather than a colour: the ultraviolet goes in and the pigment
 * decides what comes out, which is why one highlighter glows lime and the orange one beside it in
 * the box glows orange. So the third colour in this room is arrived at the same way the second one
 * was, and it is the only tone here that is neither hue — 39.3 CIEDE2000 from the lime, 71.0 from
 * the panel it is read on and 30.0 from the ink beside it. 5.57:1 on a panel.
 */
private val FlareOrange = Color(0xFFFF9E4D)

/** The flare banked down to an ember: an error's own block, deep enough to hold pale lettering. */
private val FlareOrangeDeep = Color(0xFF4A1E05)

private val BlacklightChrome: ColorScheme = darkColorScheme(
    primary = LimeGlow,
    onPrimary = VioletGround,
    primaryContainer = VioletWash,
    onPrimaryContainer = LimeGlow,
    inversePrimary = LimeGlowShaded,
    secondary = VioletHaze,
    onSecondary = VioletGround,
    secondaryContainer = VioletWash,
    onSecondaryContainer = LimeInk,
    tertiary = LimeInkDim,
    onTertiary = VioletGround,
    tertiaryContainer = VioletSunk,
    onTertiaryContainer = LimeInk,
    background = VioletGround,
    onBackground = LimeInk,
    surface = VioletPanel,
    onSurface = LimeInk,
    surfaceVariant = VioletSunk,
    onSurfaceVariant = LimeInkDim,
    surfaceTint = VioletPanel,
    inverseSurface = LimeInk,
    inverseOnSurface = VioletGround,
    error = FlareOrange,
    // The room showing through the flare, which is both the better number (8.25:1 against 8.01:1
    // for a burnt brown) and the only dark tone this chrome owns.
    onError = VioletGround,
    errorContainer = FlareOrangeDeep,
    onErrorContainer = Color(0xFFFFD3A6),
    outline = VioletRule,
    outlineVariant = VioletRuleFaint,
    scrim = Color(0xFF1D0233),
    surfaceBright = Color(0xFF732DB7),
    surfaceDim = VioletGround,
    surfaceContainerLowest = Color(0xFF250546),
    surfaceContainerLow = Color(0xFF420C77),
    surfaceContainer = VioletPanel,
    surfaceContainerHigh = Color(0xFF58139B),
    surfaceContainerHighest = Color(0xFF6520A8)
)

private val BlacklightDetail = ChromeDetail(
    // The room behind the panels, so a panel reads as a lit surface in a dark room rather than as
    // one flat violet with hairlines ruled across it. The same move Lemon Blueberry makes, and the
    // only piece of ornament this chrome takes either.
    windowGround = VioletGround
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
 * Two faces, and the rule for which goes where — and, for one chrome, a third.
 *
 * Spectral, a serif cut for screens, carries everything that names something — the application, a
 * section, a realm. IBM Plex Sans, which is narrow and legible small, carries everything that
 * *measures* something — a slider's value, a resolution, a status line. That split is the oldest
 * convention in cartography: the title and the legend headings are set, the annotations are
 * lettered.
 *
 * F7 adds IBM Plex Mono, from the same IBM Plex release as the sans (v6.4.0, the face at version
 * 2.004), and exactly one chrome sets its type in it: Matrix, whose subject is a terminal, where
 * there is no such split because every glyph sits on the same grid.
 *
 * All three are bundled as Compose resources rather than named as system families, so the browser
 * build renders in the same faces as the desktop instead of falling back to whatever the page's
 * default happens to be. They are open licensed (SIL OFL 1.1; the licences are in `ui/licences` and
 * the generated `Notices.kt` names all three).
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
    // The third face, and it is built only by the chrome that sets its type in it. `Font` reads the
    // resource where it is called, so building this family unconditionally would have a browser
    // fetch 314 KB of a face sixteen of the seventeen chromes never draw a glyph of.
    val mono = if (choice == ThemeChoice.MATRIX) {
        FontFamily(
            Font(Res.font.plex_mono_regular, FontWeight.Normal),
            Font(Res.font.plex_mono_bold, FontWeight.Bold)
        )
    } else {
        null
    }
    return remember(display, sans, mono, choice) { typographyFor(choice, display, sans, mono) }
}

/**
 * The same faces, set the way this chrome sets them.
 *
 * Nine of the seventeen chromes ask for a change of *type* rather than of colour, and none of them
 * is a thing a call site should be doing: High contrast wants everything one step larger, Allied,
 * Hessian and Roman want the display face tracked out for capitals, Baroque wants the headings in
 * italic, Hitchcock wants them heavy and tight, Lemon Blueberry and Blacklight want a hair more air
 * between the letters of a heading, and Matrix wants a different face entirely. So each is a
 * transformation of the one [Typography] rather than a second one written out, which is also what
 * guarantees the other eight chromes are untouched — they take the identity transformation.
 */
private fun typographyFor(
    choice: ThemeChoice,
    display: FontFamily,
    sans: FontFamily,
    mono: FontFamily? = null
): Typography {
    val base = typography(display, sans)
    return when (choice) {
        // One face for both roles, because a terminal has one. The split F1 drew — a serif for what
        // names, a sans for what measures — is a printer's convention, and a screen that renders
        // every glyph on the same grid has no use for it. Everything else about the scale stays:
        // only the family and the letterfit move, so the panel does not change shape when the
        // chrome does. Mono is wide, so the tracking the serif needed comes back out.
        ThemeChoice.MATRIX -> mono?.let { face ->
            base.map {
                it.copy(
                    fontFamily = face,
                    letterSpacing = (it.letterSpacing.value - 0.2f).coerceAtLeast(0f).sp
                )
            }
        } ?: base

        // The same letterfit Allied uses, and for the same reason: a stencil is capitals with air
        // between them. The words are uppercased by [ChromeDetail.heading].
        ThemeChoice.HESSIAN, ThemeChoice.ROMAN -> base.mapDisplay {
            it.copy(letterSpacing = (it.letterSpacing.value + 1.2f).sp)
        }

        // A Bass card is heavy and tight — the letters touch, and the eye reads the shape of the
        // word before it reads the word. Spectral is bundled in regular and semibold, so Bold is
        // the semibold with the renderer's own emboldening on top, which is what a title card's
        // lettering was too.
        ThemeChoice.HITCHCOCK -> base.mapDisplay {
            it.copy(
                fontWeight = FontWeight.Bold,
                fontSynthesis = FontSynthesis.Weight,
                letterSpacing = (it.letterSpacing.value - 0.6f).sp
            )
        }

        // One step larger, everywhere. 1.15 is a Material type step; the interface scale in
        // Settings multiplies the density instead, so the two compose rather than fight.
        ThemeChoice.HIGH_CONTRAST -> base.map { it.enlarged(1.15f) }

        // Capitals need air between them or they set as a wall; 1.2sp on the display styles is
        // what turns Spectral's caps into something that reads as small capitals rather than as
        // shouting. The words themselves are uppercased by the heading composable, from
        // [ChromeDetail.heading] — this is only the letterfit.
        ThemeChoice.ALLIED -> base.mapDisplay {
            it.copy(letterSpacing = (it.letterSpacing.value + 1.2f).sp)
        }

        // Spectral is bundled in roman only, so this is a synthesised slant rather than a drawn
        // italic — which is what a gilt cartouche's lettering was too, more often than not.
        ThemeChoice.BAROQUE -> base.mapDisplay {
            it.copy(fontStyle = FontStyle.Italic, fontSynthesis = FontSynthesis.Style)
        }

        // The optical correction every light-on-dark setting needs, and no other change: a pale
        // glyph on a deep ground spreads into its own counters — irradiation, which is why a
        // reversed-out serif always looks a weight heavier than the same face printed black on
        // white — and Spectral's headings close up under it. 0.3sp is the smallest step that opens
        // them again without the heading reading as tracked-out capitals, which is a different
        // chrome's idea. The sans is left alone: it carries figures, and a tracked figure is a
        // figure read one digit at a time. Blacklight takes the same step for the same reason and
        // wants it more, since its ink is a saturated lime rather than a cream: irradiation is
        // strongest where the glyph is brightest and the ground darkest, and those are the two
        // extremes this chrome is built out of.
        ThemeChoice.LEMON_BLUEBERRY, ThemeChoice.BLACKLIGHT -> base.mapDisplay {
            it.copy(letterSpacing = (it.letterSpacing.value + 0.3f).sp)
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
