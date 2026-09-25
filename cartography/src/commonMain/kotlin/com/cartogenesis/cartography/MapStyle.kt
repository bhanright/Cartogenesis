package com.cartogenesis.cartography

import com.cartogenesis.worldgen.pipeline.Biome
import kotlinx.serialization.Serializable

/**
 * How a finished map is drawn: the paper it is on, the water, the ink, and how hard the light
 * rakes across the relief.
 *
 * A style changes appearance and nothing else. The world underneath is identical whichever is
 * chosen, and the diagnostic views — elevation, rainfall, plates, the flow layers — ignore styles
 * entirely, because their colours mean something and a pretty ramp would make them lie.
 *
 * The four levers that do most of the work are worth naming, since they are what separates a
 * cartographer's map from a satellite photograph:
 *
 *  - **[biomeWash]** — how much of the vegetation colour is allowed through. At 0.45 the map reads
 *    as terrain seen from above; at 0 it reads as something drawn, where height alone carries the
 *    shape.
 *  - **[biomeMuting]** — how far each biome colour is dragged toward the paper before it is used.
 *    This is what stops an old chart looking like a modern one with a filter over it: aged inks
 *    are not saturated greens dimmed, they are earths.
 *  - **[climateTint]** — how far the height ramp itself follows the climate, rather than only being
 *    washed with it: whether a desert is sand at every height or green ground with a sandy wash
 *    over it. See [ClimateTint].
 *  - **[reliefStrength]** — how much the hillshade is exaggerated. Ink drawings lean on it hard,
 *    because without colour there is nothing else to carry the mountains.
 */
@Serializable
enum class MapStyle(
    val label: String,
    val detail: String,
    /** Shallow last: the ramp runs from abyss to shore. */
    internal val oceanRamp: IntArray,
    /** Coast first, snow line last. */
    internal val landRamp: IntArray,
    internal val paper: Int,
    internal val biomeWash: Float,
    internal val biomeMuting: Float,
    internal val river: Int,
    internal val lake: Int,
    internal val lakeDeep: Int,
    internal val coastline: Int,
    internal val coastlineStrength: Float,
    internal val border: Int,
    internal val wilderness: Int,
    internal val reliefStrength: Float,
    /**
     * How far the land ramp is allowed to follow the climate rather than the height alone.
     *
     * Imhof's modulated hypsometric series, in one number: at 1 a desert is sand at every height, a
     * frozen coast is pale and a wood is dark ground, and at 0 the ramp is the ramp. See
     * [ClimateTint], which computes the three things a cell's climate has to say, and [ground],
     * which spends this on them. Two styles hold it at 0 and each has a reason of its own — a pen
     * has no tint to modulate, and the colour-blind ramp is a promise measured in CIEDE2000 that
     * nothing may move.
     */
    internal val climateTint: Float,
    /**
     * How black this style rules its depth contours, or 0 for a sea with none.
     *
     * Drawn in [coastline], the style's own ink, at this share of it. See [Isobaths]. Zero for the
     * engraving, whose sea is a vignette and has no room for a second family of lines, and for the
     * colour-blind style, whose flat slate sea is a decision rather than an omission.
     */
    internal val isobathInk: Float,
    /**
     * How far the landmark markers are dragged toward the paper.
     *
     * They are the one thing on the map that is not terrain, and left alone they are a modern
     * colour key sitting on top of an aged chart. Muting them is what makes them look inked on by
     * the same hand. Dark styles keep theirs bright, since blending toward that paper would bury
     * them.
     */
    internal val glyphMuting: Float,
    /**
     * Draw the land as ink on blank paper rather than as filled colour.
     *
     * A different way of drawing rather than a different palette, and the flag that turns the whole
     * engraving on: hachures instead of hillshade, a vignette instead of an empty sea, ruled water
     * instead of a lake fill, stipple on the ice and a dotted border. Nothing is tinted by height
     * at all — the paper shows through everywhere, and the shape is carried by where the strokes
     * fall and how heavily. See [Engraving], which draws all of it, and [EngravingPlan], which
     * holds the size of the pen's marks — in pixels, the same at every resolution, so a larger
     * plate carries more strokes rather than bigger ones.
     *
     * It stops short of the thing it is imitating. A hand-drawn map draws each range as a little
     * picture of a mountain, repeated and shaded by eye; this hachures by slope, so the texture is
     * right and the pictograms are not there.
     */
    internal val lineArt: Boolean,
    /**
     * How readily a hachure stroke thickens and blackens as the ground steepens. Only when [lineArt].
     *
     * Lehmann's constant: the steepness at which a stroke reaches its full weight is one over this,
     * measured from [EngravingPlan.SLOPE_FLOOR]. Low leaves everything but a cliff face grey and
     * thin; high fills whole ranges solid and loses the shape the strokes were drawn to carry.
     */
    internal val inkGain: Float,
    /** Drawn behind the map, and used by a front end for the surround. */
    val backdrop: Int,
    /**
     * This style's own set of realm colours, or null to take the shared ones.
     *
     * Null in every style but [CLEAR], and that is the whole of the mechanism: the political and
     * peoples views ordinarily draw from [MapPalette.nation] and [MapPalette.culture] — hue wheels
     * with a fixed step — and a style that declares a set here replaces them with it, cycling when
     * there are more realms than colours and hatching each further turn of the cycle (see
     * [hatched]). Declaring a set also hands those two views this style's *own* water and land,
     * rather than the shared ramps they otherwise share with the diagnostic views, because a set
     * chosen to be told apart is worth nothing under a sea that competes with it.
     *
     * Still palette-only: a realm colour has always reached the graphics card as a lookup table
     * (see [RasterRecipe]), so all that changes on the device is what is in the table and which
     * two ramps the political branch reads.
     */
    internal val realmRamp: IntArray? = null
) {
    ATLAS(
        label = "Atlas",
        detail = "Elevation and climate",
        oceanRamp = intArrayOf(
            0xFF0B2239.toInt(), 0xFF11395B.toInt(), 0xFF1B5479.toInt(),
            0xFF2B7398.toInt(), 0xFF57A5C4.toInt()
        ),
        landRamp = intArrayOf(
            0xFF9DBE7A.toInt(), 0xFF8AAE63.toInt(), 0xFFB9C070.toInt(), 0xFFC8B072.toInt(),
            0xFFA98A63.toInt(), 0xFF8A6F58.toInt(), 0xFF7C6656.toInt(), 0xFFEDEDE8.toInt()
        ),
        paper = 0xFFF2E4C6.toInt(),
        biomeWash = 0.45f,
        biomeMuting = 0f,
        river = 0xFF3C7EA8.toInt(),
        lake = 0xFF4E92B4.toInt(),
        lakeDeep = 0xFF2F6B8C.toInt(),
        coastline = 0xFF3E4A52.toInt(),
        coastlineStrength = 0.55f,
        border = 0xFF2A2118.toInt(),
        wilderness = 0xFF6E6A5E.toInt(),
        reliefStrength = 1f,
        // The full effect. A modern atlas is the thing Imhof was describing when he wrote that a
        // hypsometric series has to follow the vegetation, and this style is the one that claims to
        // be one.
        climateTint = 1f,
        isobathInk = 0.18f,
        glyphMuting = 0f,
        lineArt = false,
        inkGain = 0f,
        backdrop = 0xFF14171A.toInt()
    ),

    /**
     * Aged parchment. The sea is pale rather than dark, as it is on old charts where the ink was
     * expensive and the land was the point, and every colour is dragged toward the paper so the
     * whole thing looks stained rather than printed.
     */
    VELLUM(
        label = "Vellum",
        detail = "Aged parchment and sepia ink",
        // Deeper and greyer than the land it meets, though still on the same paper. The first
        // version kept the sea within a shade or two of the coast, which is honest to an old chart
        // but made telling water from land a small act of concentration on every glance.
        oceanRamp = intArrayOf(
            0xFF8E8367.toInt(), 0xFF9C9175.toInt(), 0xFFB0A488.toInt(),
            0xFFC6BA9C.toInt(), 0xFFDBD0B4.toInt()
        ),
        landRamp = intArrayOf(
            0xFFE8DBB6.toInt(), 0xFFE2D2A9.toInt(), 0xFFD9C79A.toInt(), 0xFFCDB88B.toInt(),
            0xFFBFA87B.toInt(), 0xFFAE966C.toInt(), 0xFF9C855F.toInt(), 0xFFEDE4D0.toInt()
        ),
        paper = 0xFFF0E3C2.toInt(),
        biomeWash = 0.22f,
        biomeMuting = 0.55f,
        river = 0xFF6E5B3C.toInt(),
        lake = 0xFFBFB08A.toInt(),
        lakeDeep = 0xFFA6976F.toInt(),
        coastline = 0xFF5B4A2F.toInt(),
        coastlineStrength = 0.7f,
        border = 0xFF6B3F2A.toInt(),
        wilderness = 0xFFBCAE8C.toInt(),
        reliefStrength = 0.75f,
        // Half. An old chart knew perfectly well where the deserts were and drew them as sand, but
        // its whole palette is already earths, so there is less for the climate to move.
        climateTint = 0.45f,
        isobathInk = 0.12f,
        glyphMuting = 0.55f,
        lineArt = false,
        inkGain = 0f,
        backdrop = 0xFF2A2318.toInt()
    ),

    /**
     * Ink and wash, after East Asian landscape painting. Almost no colour: the paper is nearly
     * white, the mountains are grey ink laid on harder where the ground is steeper, and the water
     * is a single flat tone. The relief is pushed hard because with the colour gone it is the only
     * thing left describing the shape.
     */
    INK_WASH(
        label = "Ink wash",
        detail = "Sumi-e: grey ink on pale paper",
        oceanRamp = intArrayOf(
            0xFF8C9AA3.toInt(), 0xFF9AA7AF.toInt(), 0xFFAAB6BC.toInt(),
            0xFFBAC4C9.toInt(), 0xFFCBD3D6.toInt()
        ),
        landRamp = intArrayOf(
            0xFFF4F2EA.toInt(), 0xFFE9E6DC.toInt(), 0xFFDBD7CB.toInt(), 0xFFC7C2B5.toInt(),
            0xFFAAA498.toInt(), 0xFF8B857A.toInt(), 0xFF6B665D.toInt(), 0xFF4A463F.toInt()
        ),
        paper = 0xFFF7F5EE.toInt(),
        biomeWash = 0.10f,
        biomeMuting = 0.80f,
        river = 0xFF44505A.toInt(),
        lake = 0xFF9FADB5.toInt(),
        lakeDeep = 0xFF7F8D96.toInt(),
        coastline = 0xFF2B2F33.toInt(),
        coastlineStrength = 0.8f,
        border = 0xFF7A2E28.toInt(),
        wilderness = 0xFFC9C4B8.toInt(),
        reliefStrength = 1.8f,
        // Almost nothing to modulate: with the colour this nearly gone, what is left of a climate
        // is a shade of grey either way.
        climateTint = 0.15f,
        isobathInk = 0.10f,
        glyphMuting = 0.70f,
        lineArt = false,
        inkGain = 0f,
        backdrop = 0xFF1D1F21.toInt()
    ),

    /**
     * An admiralty chart. The water carries the information here rather than the land: pale bands
     * stepping out from the shore, with the interior left almost blank buff, the way a chart tells
     * a sailor what they need and nothing else.
     */
    NAUTICAL(
        label = "Nautical",
        detail = "Admiralty chart, depth-banded water",
        oceanRamp = intArrayOf(
            0xFF6E9DB5.toInt(), 0xFF8FB8CB.toInt(), 0xFFB4D2DF.toInt(),
            0xFFD6E9F0.toInt(), 0xFFEDF6F9.toInt()
        ),
        landRamp = intArrayOf(
            0xFFEFE2C4.toInt(), 0xFFEADCBA.toInt(), 0xFFE3D3AC.toInt(), 0xFFDBC99E.toInt(),
            0xFFD1BD8F.toInt(), 0xFFC4AE7F.toInt(), 0xFFB59D70.toInt(), 0xFFF2ECDE.toInt()
        ),
        paper = 0xFFF4EAD2.toInt(),
        biomeWash = 0.14f,
        biomeMuting = 0.62f,
        river = 0xFF3E6E8C.toInt(),
        lake = 0xFFAFD2E0.toInt(),
        lakeDeep = 0xFF87B4C8.toInt(),
        coastline = 0xFF23384A.toInt(),
        coastlineStrength = 0.85f,
        border = 0xFF8A3B2E.toInt(),
        wilderness = 0xFFD8CBA9.toInt(),
        reliefStrength = 0.5f,
        // The land is deliberately near-blank buff and the interest is all in the water, so the
        // climate gets a third of a say ashore and the contours are the strongest on the list. A
        // chart is the one document here that is *about* the depth.
        climateTint = 0.30f,
        isobathInk = 0.30f,
        glyphMuting = 0.40f,
        lineArt = false,
        inkGain = 0f,
        backdrop = 0xFF16232C.toInt()
    ),

    /**
     * The same world after dark. Deep indigo water, slate land, and rivers left bright so they
     * still read — the one thing that must not disappear when everything else is dimmed.
     */
    MIDNIGHT(
        label = "Midnight",
        detail = "Moonlit, for dark rooms",
        oceanRamp = intArrayOf(
            0xFF070B18.toInt(), 0xFF0C1428.toInt(), 0xFF14203C.toInt(),
            0xFF1D2E52.toInt(), 0xFF2C4470.toInt()
        ),
        landRamp = intArrayOf(
            0xFF243040.toInt(), 0xFF2B3849.toInt(), 0xFF344254.toInt(), 0xFF3E4C5E.toInt(),
            0xFF4A5768.toInt(), 0xFF5A6675.toInt(), 0xFF6E7887.toInt(), 0xFFAEB7C4.toInt()
        ),
        paper = 0xFF1A2130.toInt(),
        biomeWash = 0.18f,
        biomeMuting = 0.70f,
        river = 0xFF7FC6E8.toInt(),
        lake = 0xFF3E7396.toInt(),
        lakeDeep = 0xFF27516E.toInt(),
        coastline = 0xFF9FB4C6.toInt(),
        coastlineStrength = 0.35f,
        border = 0xFFD8A05A.toInt(),
        wilderness = 0xFF39424F.toInt(),
        reliefStrength = 1.3f,
        // Moonlight drains the colour out of everything, so only a quarter of the climate survives
        // — and what "paler" means here is bluer, since the paper this style prints on is dark.
        climateTint = 0.25f,
        isobathInk = 0.14f,
        glyphMuting = 0f,
        lineArt = false,
        inkGain = 0f,
        backdrop = 0xFF080B12.toInt()
    ),

    /**
     * The pull-down physical map from a schoolroom wall. Saturated hypsometric tints stepping
     * green to yellow to orange to brown, a flat pale sea, and none of the restraint of a
     * cartographer's chart — these were printed to be legible from the back of a classroom.
     */
    SCHOOLROOM(
        label = "Schoolroom",
        detail = "Elevation",
        oceanRamp = intArrayOf(
            0xFF6FB6CE.toInt(), 0xFF7FC0D5.toInt(), 0xFF92CDDE.toInt(),
            0xFFA8D9E6.toInt(), 0xFFBFE5EE.toInt()
        ),
        landRamp = intArrayOf(
            0xFF4E9B4A.toInt(), 0xFF77B356.toInt(), 0xFFAFC85E.toInt(), 0xFFE0CE66.toInt(),
            0xFFE8A94A.toInt(), 0xFFD97B34.toInt(), 0xFFB4552A.toInt(), 0xFFF0E6DC.toInt()
        ),
        paper = 0xFFF3EEE2.toInt(),
        biomeWash = 0.12f,
        biomeMuting = 0.35f,
        river = 0xFF2F6FA0.toInt(),
        lake = 0xFF7FC0D5.toInt(),
        lakeDeep = 0xFF4E9AB8.toInt(),
        coastline = 0xFF2E3B44.toInt(),
        coastlineStrength = 0.6f,
        border = 0xFFB03A3A.toInt(),
        wilderness = 0xFFBFB9A8.toInt(),
        reliefStrength = 0.45f,
        // The full effect, as on Atlas, and for a blunter reason: the pull-down map printed the
        // Sahara yellow and the Congo dark green, and that is most of what it was for. Anything
        // less leaves this ramp's lowland green under a coastal desert, because the green band of
        // a classroom map runs a third of the way up the sheet before it turns to sand.
        climateTint = 1f,
        isobathInk = 0.10f,
        glyphMuting = 0.2f,
        lineArt = false,
        inkGain = 0f,
        backdrop = 0xFF20262B.toInt()
    ),

    /**
     * The modern illustrated fantasy map: deep teal water, warm cream land, and forest laid on
     * heavily in dark green. The vegetation wash runs high here, because on these maps the woods
     * are a *place* with a name rather than a shade of the terrain.
     */
    VERDANT(
        label = "Verdant",
        detail = "Illustrated fantasy: teal sea, deep woods",
        oceanRamp = intArrayOf(
            0xFF10454F.toInt(), 0xFF14555F.toInt(), 0xFF1A6873.toInt(),
            0xFF238089.toInt(), 0xFF3E9DA4.toInt()
        ),
        landRamp = intArrayOf(
            0xFFE4D5A8.toInt(), 0xFFDDCB99.toInt(), 0xFFD3BE88.toInt(), 0xFFC6AE78.toInt(),
            0xFFB59A68.toInt(), 0xFF9E8258.toInt(), 0xFF836A49.toInt(), 0xFFEDE6D4.toInt()
        ),
        paper = 0xFFEFE3C0.toInt(),
        biomeWash = 0.62f,
        // Barely muted, unlike the aged styles: on these maps the forest is a named place and is
        // meant to read as forest, not as a shade the terrain happens to take.
        biomeMuting = 0.08f,
        river = 0xFF2C7A86.toInt(),
        lake = 0xFF3E9DA4.toInt(),
        lakeDeep = 0xFF1A6873.toInt(),
        coastline = 0xFF123C44.toInt(),
        coastlineStrength = 0.8f,
        border = 0xFF7A4A22.toInt(),
        wilderness = 0xFFC9BC95.toInt(),
        reliefStrength = 0.9f,
        // An illustrated map draws the desert as a desert and the wood as a wood — they are named
        // places on it, which is the same reason its biome wash runs higher than anyone else's.
        climateTint = 0.80f,
        isobathInk = 0.10f,
        glyphMuting = 0.15f,
        lineArt = false,
        inkGain = 0f,
        backdrop = 0xFF0A2A31.toInt()
    ),

    /**
     * Painted parchment, after the illustrated maps of maritime South-East Asia: sage and ochre
     * land on a stained page, a muted jade sea, and vermilion for anything a person made.
     */
    SCROLL(
        label = "Scroll",
        detail = "Painted parchment, jade sea, vermilion marks",
        oceanRamp = intArrayOf(
            0xFF7E9A92.toInt(), 0xFF8DA79E.toInt(), 0xFF9DB4AB.toInt(),
            0xFFAFC2B9.toInt(), 0xFFC3D1C8.toInt()
        ),
        landRamp = intArrayOf(
            0xFFDCD9AE.toInt(), 0xFFD2D1A2.toInt(), 0xFFC6C795.toInt(), 0xFFBCBB88.toInt(),
            0xFFAEA97A.toInt(), 0xFF9C9469.toInt(), 0xFF87805A.toInt(), 0xFFE8E3CC.toInt()
        ),
        paper = 0xFFE9E2C4.toInt(),
        biomeWash = 0.30f,
        biomeMuting = 0.45f,
        river = 0xFF5F7A72.toInt(),
        lake = 0xFF9DB4AB.toInt(),
        lakeDeep = 0xFF7E9A92.toInt(),
        coastline = 0xFF4A4632.toInt(),
        coastlineStrength = 0.72f,
        border = 0xFFA32F26.toInt(),
        wilderness = 0xFFC8C4A2.toInt(),
        reliefStrength = 1.15f,
        // Half, like the other painted parchment: the sage and ochre it is painted in are already
        // most of the way to being climate colours.
        climateTint = 0.50f,
        isobathInk = 0.12f,
        glyphMuting = 0.25f,
        lineArt = false,
        inkGain = 0f,
        backdrop = 0xFF2B2A20.toInt()
    ),

    /**
     * An engraved map: everything on the sheet is a mark made by a pen, and there is no fill
     * anywhere.
     *
     * The four conventions of the engraved atlas, each drawn per pixel from a field the raster
     * already has, and all of them in [Engraving]:
     *
     *  - **Hachures for the relief.** Short strokes running straight down the slope, thickening and
     *    blackening as the ground steepens and leaving the plain as paper — Lehmann's rule, which
     *    is how a pen describes a mountain when it has no colour to describe it with. The direction
     *    comes from the aspect at each pixel, so a range reads as a range rather than as a scribble
     *    laid at one bearing over everything.
     *  - **A vignette round the coast.** Four lines following the shore out to sea at widening
     *    spacing, fading as they go, and nothing at all beyond them. This is what makes an engraved
     *    ocean read as water rather than as the paper it is printed on.
     *  - **Ruled water in the lakes.** Horizontal lines, close under the shore and fading toward the
     *    middle, inside a firm outline — instead of a grey blot, which is a colour decision and this
     *    style has no colour to spend.
     *  - **Stipple on the ice**, and a **dotted** border rather than a solid one. Borders are the
     *    one thing in colour, as they often are on these maps.
     */
    PEN_AND_INK(
        label = "Pen and ink",
        detail = "Engraved: hachured relief, coastal vignette, red borders",
        // One tone, five times over. The open sea past the vignette is the paper itself: a depth
        // gradient nobody can see is still a fill, and an engraver had none to give.
        oceanRamp = intArrayOf(
            0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(),
            0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt()
        ),
        landRamp = intArrayOf(
            0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(),
            0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt()
        ),
        paper = 0xFFFBF8F0.toInt(),
        biomeWash = 0f,
        biomeMuting = 1f,
        // One pen: the rivers, the coast and the hachures are all the same ink.
        river = 0xFF17130F.toInt(),
        // Paper, so the lake branch leaves the water blank and the ruling is all that is on it.
        lake = 0xFFFBF8F0.toInt(),
        lakeDeep = 0xFFFBF8F0.toInt(),
        coastline = 0xFF17130F.toInt(),
        coastlineStrength = 0.95f,
        border = 0xFFA82820.toInt(),
        wilderness = 0xFFF1EDE3.toInt(),
        reliefStrength = 1f,
        // Nothing, three times over. There is no tint to modulate, no colour to veil the low ground
        // with, and the sea already carries four lines of its own — a second family of them running
        // the other way would turn the ocean into a net.
        climateTint = 0f,
        isobathInk = 0f,
        glyphMuting = 0.5f,
        lineArt = true,
        // Full weight at a slope of 0.40, which is the seventy-fifth percentile of this world's
        // land: the ranges draw at the widest the lattice will hold, the rolling country between
        // them draws at half that, and the plains draw nothing. Lower and a mountain reads as a
        // scatter of hairlines; higher and the ranges fill solid and take the shape with them.
        inkGain = 3.0f,
        backdrop = 0xFF262320.toInt()
    ),

    /**
     * The same world as a dry planet: rust and ochre ground rising to pale dust and white, and no
     * water anywhere.
     *
     * Every other style paints the sea because the sea is water. Here it is not: the ocean basins
     * are drawn as basalt plains, dark and flat, the way Mars's northern lowlands are drawn on a
     * shaded-relief chart of a body that lost its ocean. The generator is untouched — the world
     * underneath still has a sea, rivers and lakes, exactly as it does under Vellum — and this is
     * the whole point of a style: the same world, read as somewhere else.
     *
     * Four decisions carry it, and each is a palette entry rather than a new pass through the
     * rasterizer, which is what lets the graphics card draw it identically (see [RasterRecipe]:
     * the accelerator is handed pre-packed colours and knows nothing about which style they came
     * from):
     *
     *  - the **ocean ramp** runs from near-black basalt in the abyssal plains to a dusty grey at
     *    the shelf, so depth still reads as depth without a drop of blue;
     *  - the **coastline** is a faint scarp rather than a shoreline — the ink is a weak umber at
     *    [coastlineStrength] 0.3, which draws the old sea's edge as the eroded step it would be
     *    after the water went, instead of as a drawn line around a body of water;
     *  - **rivers and lakes** are darker than the ground they cross rather than lighter, which is
     *    what a dry channel looks like from above: a shadow in the dust, not a ribbon of water;
     *  - the **land ramp** is the hypsometric sequence of an iron world — dark rust in the
     *    lowlands through ochre to pale dust, and white where a chart would put the snow line,
     *    which is also what puts a pale cap over the ice at each pole.
     *
     * The biome wash is left low and the muting high, so vegetation shows only as a change in the
     * dust rather than as green: a forest on Mars is a slightly darker plain. The diagnostic views
     * ignore all of this, as they ignore every style.
     */
    MARS(
        label = "Mars",
        detail = "A dry world: rust and ochre, basalt where the sea was",
        // Basalt, abyss first. Warm-grey rather than neutral, because the dust gets everywhere.
        oceanRamp = intArrayOf(
            0xFF1F1917.toInt(), 0xFF29211D.toInt(), 0xFF352A24.toInt(),
            0xFF43352C.toInt(), 0xFF544336.toInt()
        ),
        // Rust at the shore, ochre through the middle, dust high up, white at the top.
        landRamp = intArrayOf(
            0xFF7E3A20.toInt(), 0xFF8F4A26.toInt(), 0xFFA35C2E.toInt(), 0xFFB77439.toInt(),
            0xFFC78F4C.toInt(), 0xFFD5A868.toInt(), 0xFFE2C79B.toInt(), 0xFFF4EEE4.toInt()
        ),
        paper = 0xFFC8A67E.toInt(),
        biomeWash = 0.22f,
        biomeMuting = 0.68f,
        // Dry channels: darker than everything around them, which is the only way a riverbed reads
        // on a world with no water to make it brighter.
        river = 0xFF3B2A20.toInt(),
        lake = 0xFF352A24.toInt(),
        lakeDeep = 0xFF211A17.toInt(),
        coastline = 0xFF6B4A31.toInt(),
        // A third of the usual weight. The old shoreline is a scarp the wind has been working on,
        // not an inked edge.
        coastlineStrength = 0.30f,
        // Pale dust, so a border reads over rust ground and over basalt alike.
        border = 0xFFF0E2CC.toInt(),
        wilderness = 0xFF6E5241.toInt(),
        // Pushed hard: with no water and no vegetation the relief is most of what there is to see.
        reliefStrength = 1.4f,
        // A third. A dry world has one climate, so what little the modulation has to say comes out
        // as pale dust against darker plain — and the polar caps, which the cold term draws.
        climateTint = 0.35f,
        // On the contours of a sea that is not there any more, which is exactly how the northern
        // lowlands of a dry planet are charted: the old floor, still with its terraces.
        isobathInk = 0.12f,
        glyphMuting = 0.25f,
        lineArt = false,
        inkGain = 0f,
        backdrop = 0xFF17100D.toInt()
    ),

    /**
     * The world as a satellite sees it: no paper, no ink convention, only ground.
     *
     * Every other style in this list is a *drawing* of a world — a chart, a plate, an engraving —
     * and each one is honest about it, because the conventions are what make a map readable. This
     * one is the photograph, and its whole palette is measured off one: a "Blue Marble" view of
     * Earth centred on North America, sampled region by region rather than invented. Each colour
     * below carries the pixel box it came out of, on that 570-pixel image, and the median of the
     * box is the value; where a stop had to be derived because the photograph had nothing at that
     * height, the derivation is written beside it and it is arithmetic on a sample rather than a
     * guess.
     *
     * Four decisions carry it, and every one of them is a lever the raster already reads:
     *
     *  - **[climateTint] at full**, which is the whole claim. A satellite photograph has no
     *    hypsometric series at all: the colour of a place is what grows there, and the height only
     *    shows through where nothing does. At 1 a desert is ochre at the coast as surely as on the
     *    plateau, frozen ground pales toward snow, and a closed canopy is darker ground — see
     *    [ClimateTint], whose three numbers this spends in full.
     *  - **A land ramp of earths under it**, so height still reads where the cover is uniform: the
     *    reference's own woodland green at the shore, its Great Plains olive where the earth band
     *    begins ([ClimateTint.ARID_RAMP_FLOOR] lands on that fourth stop), its Great Basin umber
     *    and Colorado rust above, and its Greenland snow at the top.
     *  - **A sea taken from the same photograph**: the deep saturated cobalt of the open Pacific,
     *    running up to the turquoise of a sunlit shelf. The depth contours are left faint, at
     *    [isobathInk] 0.08 — a photograph has none, and what little is drawn here is there so the
     *    shelf break reads at all.
     *  - **One ink for the coast, the borders and the sheet's lettering**, chosen by measurement
     *    rather than taste. `NaturalStyleTest` searches for the ink that reads best over the
     *    darkest and lightest ground this style can paint and holds this one to what it found.
     *
     * The one thing it is not is a filter over Atlas. Atlas is a modern atlas plate — a
     * hypsometric series modulated by climate, which is Imhof's rule — and its greens are the
     * greens a cartographer chose. These are the greens a camera recorded.
     */
    NATURAL(
        label = "Natural",
        detail = "Satellite colours",
        // Abyss first, and nine stops rather than the usual five, because of where this generator
        // actually asks to be painted. `relativeElevation` normalises the sea floor by its deepest
        // trench, so half of every ocean reads within a tenth of the surface: measured over the
        // author's two worlds at 2048, the sea's deciles along this ramp are 0.00 0.63 0.76 0.86
        // 0.90 0.91 0.93 0.94 0.96 0.97 0.98. A five-stop ramp spreads its top segment over a
        // quarter of the range and therefore over most of the water, which painted the whole sea
        // the colour of a sunlit shelf — the first render of this style was a turquoise ocean.
        // Nine stops put the shelf in the top eighth, where the shelf is, and hand the photograph's
        // own open-ocean cobalt to the middle where the mass of the water sits. At the median
        // t of 0.90 this reads #015394, against the #015294 the reference's own open ocean measures.
        oceanRamp = intArrayOf(
            0xFF001731.toInt(), // the deepest lit water at 0.85^6: the abyss no lit disc shows
            0xFF001B3A.toInt(), // the same at 0.85^5
            0xFF002044.toInt(), // 0.85^4
            0xFF002651.toInt(), // 0.85^3
            0xFF002C5F.toInt(), // 0.85^2
            0xFF003470.toInt(), // 0.85
            0xFF003E84.toInt(), // the deepest lit open water: 2nd percentile of (120,230)-(220,400)
            0xFF004892.toInt(), // open Pacific off Baja, (148,338)-(180,366)
            0xFF09839C.toInt() // sunlit shelf, Hudson Bay, (296,206)-(320,226)
        ),
        // Coast first, snow line last, and every stop but the top two is a region of the reference
        // read straight off it. They climb in relative luminance without a step backwards —
        // 0.099, 0.110, 0.167, 0.220, 0.258, 0.317, 0.446, 0.615 — which is what lets height read
        // through a cover that is doing most of the talking.
        //
        // The fourth stop is where the earth band has to begin, because that is where
        // [ClimateTint.ARID_RAMP_FLOOR] lands, and it decides whether a desert is ever drawn as a
        // lawn. The reference's Great Plains olive sits at exactly equal red and green, which left
        // a sea-level desert one rounding from reading green and pinned the biome wash high to
        // rescue it — and a high wash over this palette's greens is what made the first render of
        // this style khaki. The Great Basin's umber is on the floor instead: it gives a desert
        // twenty units of red over green with nothing else doing any work, and frees the wash to
        // come down to where the photograph's saturation survives.
        //
        // The third stop is then free to be what the reference's continental interior actually is,
        // which is a dark saturated olive-green rather than the pale yellow-green a plate would
        // use. It matters more than any other stop, because half of every world's land sits
        // between the second and the fourth: the measured deciles of the author's two worlds run
        // 0.02 0.05 0.08 0.12 0.15 0.20 0.25 0.32 0.42, so this is the colour most of the map is.
        landRamp = intArrayOf(
            0xFF2C6504.toInt(), // eastern woodland, (328,282)-(344,298)
            0xFF3A6904.toInt(), // Mississippi lowland grass, (300,288)-(316,304)
            0xFF797425.toInt(), // southern plains interior, (292,320)-(306,334)
            0xFF977E4A.toInt(), // Great Basin umber, (252,312)-(270,328) — the earth band begins
            0xFFAB8455.toInt(), // Chihuahua plateau ochre, (244,330)-(258,344)
            0xFFC08F61.toInt(), // Colorado Plateau red rock, (250,318)-(262,332)
            0xFFC7AE95.toInt(), // that red rock and the snow, half and half: see below
            0xFFCECECA.toInt() // Greenland interior snow, (318,136)-(332,150) — see below
        ),
        // The reference is a photograph and has no paper. The one pale thing in it is its snow, so
        // that is what the cold pales toward and what the sheet plates its scale bar on. The
        // sample reads #CDCECA, one unit greener than it is red; over the 196 pixels of the box
        // that difference runs from -5 to +7 with a mean of 1.5, which is the JPEG's own scatter
        // and not a colour. Red is lifted the one unit to meet green, because a summit whose
        // strongest channel is green is a summit `ClimateTintTest` reads as a lawn.
        paper = 0xFFCECECA.toInt(),
        // Lower than Atlas's, which is not where this started. The wash is a coat of `MapPalette`'s
        // biome colours, and those are a cartographer's: chosen to be told apart on a plate, and
        // sitting around a third to two fifths saturated. Laid on at Atlas's weight over a palette
        // whose own greens run to 0.96 they average the photograph away — the first render of this
        // style measured 0.41 saturated against the reference's 0.68, and read as khaki. At 0.30
        // the cover is still plainly legible and the ground underneath is still the photograph's.
        biomeWash = 0.30f,
        // None at all. Muting is what makes an aged chart look stained rather than printed, and
        // nothing here is stained: a camera records the cover at the saturation it has, and this
        // palette has none to spare.
        biomeMuting = 0f,
        // The shelf turquoise lightened three tenths toward the snow, which is the one blue that
        // reads over the dark woodland greens the lowlands are painted in. Inland water in the
        // reference is a handful of pixels across and every box over it takes in its banks, so it
        // is derived from the shelf rather than sampled badly.
        river = 0xFF4499A9.toInt(),
        // A lake is shallow water and takes the shelf's colour; a deep one takes the open sea's.
        lake = 0xFF09839C.toInt(),
        lakeDeep = 0xFF004892.toInt(),
        // The boreal forest east of Hudson Bay — the darkest ground anywhere in the reference at a
        // relative luminance of 0.030 — taken down to a third of itself. A photograph of a planet
        // has no ink in it, and this is the nearest thing to one it owns. A third rather than a
        // half because the graticule's figures are ruled across the open sea as well as across the
        // land, and this style's abyss is darker than most styles' ink: `NaturalStyleTest` holds
        // the ink to being the darkest thing the style owns, and at a half it was not.
        coastline = 0xFF091205.toInt(),
        // Half. A shoreline on a satellite image is a change of surface rather than a drawn line,
        // and inking it at the weight a chart would use turns every coast into a cartoon.
        coastlineStrength = 0.5f,
        // The same ink. A style whose whole claim is that it draws no conventions has no business
        // owning two of them, and one ink for the coast, the realm borders and the sheet's
        // lettering is the smallest set that draws the map.
        border = 0xFF091205.toInt(),
        // The tundra of the Arctic archipelago, (268,178)-(286,194): a grey-olive that no realm
        // colour comes near, and the colour of ground nobody holds.
        wilderness = 0xFF7D8774.toInt(),
        // The hillshade exactly as computed, which no other style asks for. Every one of them
        // either leans on the relief because it has no colour to spare or softens it because ink
        // would smother the tints; a photograph does neither. The light in it is the light that
        // was there, and exaggerating it is the one thing that would make this a shaded-relief
        // plate rather than a picture.
        reliefStrength = 1f,
        // The full effect, and the reason this style exists. See the note above.
        climateTint = 1f,
        // The faintest of the twelve, at half the next lowest. A photograph has no contours at all,
        // and on the first renders of this style they were the one mark on the sheet that gave the
        // drawing away — a ring round every seamount in open water, where the reference has nothing
        // but blue. They are kept rather than switched off because the shelf break is worth being
        // able to find, and at this weight they are a thickening of the water rather than a line.
        isobathInk = 0.05f,
        glyphMuting = 0.15f,
        lineArt = false,
        inkGain = 0f,
        // Space, measured over the four corners of the reference well clear of the limb, with the
        // stars excluded: black, at the median of 6,281 pixels.
        backdrop = 0xFF000000.toInt()
    ),

    /**
     * The same world drawn so that nothing in it is told by hue alone.
     *
     * About one man in twelve cannot separate a red from a green, and every other style in this
     * list asks him to: Atlas puts green lowlands against brown uplands, Verdant puts a teal sea
     * against a cream shore, and the political view hands out realm colours off a hue wheel, which
     * is the worst case there is — nine countries that differ in hue and in nothing else. This
     * style is the answer, and it is built out of four decisions rather than a filter over the
     * others:
     *
     *  - **The sea is one flat slate** (#1F2A3A). Depth banding is a second ordered variable
     *    competing with the land's, and under a simulation the two run together at the shore; a
     *    single dark tone means the coastline is the only thing the eye has to find, and it finds
     *    it instantly.
     *  - **The land ramp is ordered by lightness, not by hue** — dark olive at the shore through
     *    amber to a pale yellow highland and white above the snow line. It runs along the blue-
     *    yellow axis that both dichromacies keep, and every stop is lighter than the one below it,
     *    so height still reads as height when the hues collapse. Adjacent stops are at least
     *    8.00 CIEDE2000 apart under deuteranopia and protanopia both, which `ClearStyleTest`
     *    measures.
     *  - **The vegetation wash is off entirely** ([biomeWash] 0). It is the one thing in the
     *    fantasy view that carries meaning by hue, and washing it over the ramp would also mean
     *    the colour on the page was no longer the colour the guard measured.
     *  - **Realms come from Paul Tol's nine "muted" colours**, which were chosen for exactly this
     *    and are the best nine anyone has published, with a hatch over each further turn of the
     *    cycle so that a tenth realm is told from the first by its texture rather than by a
     *    tenth hue nobody could find. Rivers are white, which is the only ink that reads over
     *    every stop of the ramp and over the slate as well; the coastline is a single black cell
     *    at full strength.
     *
     * The diagnostic views are untouched, as they are by every style: their colours mean specific
     * things — a temperature, a biome, a plate — and a legend is what makes those readable.
     */
    CLEAR(
        label = "Colour-blind",
        detail = "Ordered by lightness: safe under deuteranopia and protanopia",
        // One slate, five times over. A flat sea is a decision, not an omission: see the note.
        oceanRamp = intArrayOf(
            0xFF1F2A3A.toInt(), 0xFF1F2A3A.toInt(), 0xFF1F2A3A.toInt(),
            0xFF1F2A3A.toInt(), 0xFF1F2A3A.toInt()
        ),
        // Cividis's own ordering — monotone lightness along the blue-yellow axis — recoloured as a
        // hypsometric sequence: dark olive shore, amber middle, pale yellow highland, white snow.
        landRamp = intArrayOf(
            0xFF2B2E1C.toInt(), 0xFF454326.toInt(), 0xFF615A2E.toInt(), 0xFF7F7038.toInt(),
            0xFF9E8842.toInt(), 0xFFBFA34E.toInt(), 0xFFDCC271.toInt(), 0xFFF7F4E6.toInt()
        ),
        paper = 0xFFF7F4E6.toInt(),
        // Off, not merely low. See the note: the biome wash is the one hue-carried variable left
        // in the fantasy view, and turning it off is also what makes the ramp the guard measures
        // the ramp the reader sees.
        biomeWash = 0f,
        biomeMuting = 1f,
        // White, because it is the only ink that reads over the dark shore, the pale highland and
        // the slate sea alike.
        river = 0xFFFFFFFF.toInt(),
        lake = 0xFF1F2A3A.toInt(),
        lakeDeep = 0xFF1F2A3A.toInt(),
        coastline = 0xFF000000.toInt(),
        // Full strength: one black cell, not a blend of black with whatever it crosses.
        coastlineStrength = 1f,
        border = 0xFF000000.toInt(),
        // A neutral no realm's colour comes near, since unclaimed land has to be told from claimed
        // land as surely as one realm is told from another.
        wilderness = 0xFF9A9A9A.toInt(),
        // Enough to keep the mountains, short of enough to push a stop into its neighbour.
        reliefStrength = 0.9f,
        // Off, all three, for the reason the biome wash is off: this style's ramp is a measured
        // promise — every adjacent pair at least 8.00 CIEDE2000 apart under both red-green
        // deficiencies — and a climate that moved a cell along the ramp, an air that lifted the
        // lowlands toward white or a contour that broke the flat sea would each make the colour on
        // the page something other than the colour the guard measured.
        climateTint = 0f,
        isobathInk = 0f,
        glyphMuting = 0.2f,
        lineArt = false,
        inkGain = 0f,
        backdrop = 0xFF10161F.toInt(),
        // Paul Tol's "muted" qualitative scheme, in his order. Nine colours is where a qualitative
        // scheme stops being separable at all, which is why the tenth realm is hatched instead.
        realmRamp = intArrayOf(
            0xFF332288.toInt(), // indigo
            0xFF88CCEE.toInt(), // cyan
            0xFF44AA99.toInt(), // teal
            0xFF117733.toInt(), // green
            0xFF999933.toInt(), // olive
            0xFFDDCC77.toInt(), // sand
            0xFFCC6677.toInt(), // rose
            0xFF882255.toInt(), // wine
            0xFFAA4499.toInt()  // purple
        )
    );

    internal fun ocean(depth: Float): Int =
        MapPalette.ramp(oceanRamp, 1f - depth.coerceIn(0f, 1f))

    internal fun land(elevation: Float): Int =
        MapPalette.ramp(landRamp, elevation.coerceIn(0f, 1f))

    /**
     * The land at [relative] height under this climate, as this style would paint it.
     *
     * The whole of the land colour in one call, so that the processor and the graphics card cannot
     * end up doing it in a different order: the ramp read at a height the drought has lifted, paled
     * toward the paper by the cold, darkened under a canopy, and finally washed with the biome's own
     * colour exactly as [tint] always did. [dryness], [coldness] and [canopy] are the three numbers
     * [ClimateTint] computes for the cell; at [climateTint] 0 none of them is read and this is the
     * colour the plain ramp gave before the climate reached it, to the bit.
     */
    internal fun ground(
        relative: Float,
        dryness: Float,
        coldness: Float,
        canopy: Float,
        biome: Biome
    ): Int {
        if (climateTint <= 0f) return tint(land(relative), biome)

        val height = relative.coerceIn(0f, 1f)
        // Dry ground starts at the ramp's earth band and climbs what is left of the ramp from
        // there, so a desert is sand at the coast and still reaches snow at the summit.
        val lifted = height + climateTint * dryness * ClimateTint.ARID_RAMP_FLOOR * (1f - height)
        var colour = land(lifted)
        colour = MapPalette.blend(
            colour, paper, climateTint * coldness * ClimateTint.COLD_PALING
        )
        colour = MapPalette.shade(
            colour, 1f - climateTint * canopy * ClimateTint.CANOPY_DARKENING
        )
        return tint(colour, biome)
    }

    /** The biome colour as this style would print it: muted toward the paper, then washed in. */
    internal fun tint(base: Int, biome: Biome): Int {
        if (biomeWash <= 0f) return base
        val muted = MapPalette.blend(MapPalette.biome(biome), paper, biomeMuting)
        return MapPalette.blend(base, muted, biomeWash)
    }

    /** A landmark marker as this style would ink it. */
    internal fun glyph(color: Int): Int = when {
        // A pen has one colour. Keeping the marker key would leave the only coloured things on
        // the page sitting over an otherwise entirely monochrome drawing.
        lineArt -> coastline
        glyphMuting <= 0f -> color
        else -> MapPalette.blend(color, paper, glyphMuting)
    }

    /** Relief, exaggerated or softened. 1 leaves the hillshade exactly as computed. */
    internal fun relief(shade: Float): Float = 1f + (shade - 1f) * reliefStrength

    /**
     * Whether the political and peoples views take this style's own realm set.
     *
     * False for every style but [CLEAR], which is what leaves the other ten drawing exactly the
     * pixels they drew before a realm set existed.
     */
    internal val ownsRealms: Boolean get() = realmRamp != null

    /**
     * Whether the political and peoples views take this style's own water and land as well.
     *
     * Ordinarily they do not: a political map that changed colour with the style would make twelve
     * political maps out of one, and the shared ramps keep it one. Two styles have to be exceptions
     * and for the same reason — the ground they hand those views is *part of the style's claim*,
     * not decoration. [CLEAR] declares a realm set chosen so that no two realms can be confused,
     * which is worth nothing under a sea that competes with them; [PEN_AND_INK] draws with a pen,
     * and a pen has no blue.
     */
    internal val ownsPoliticalGround: Boolean get() = realmRamp != null || lineArt

    /** The colour of realm [id], cycling through the declared set where there is one. */
    internal fun realm(id: Int): Int {
        val ramp = realmRamp ?: return MapPalette.nation(id)
        return ramp[id.mod(ramp.size)]
    }

    /**
     * The colour of people [id].
     *
     * The same set, entered four colours further along. [MapPalette.culture] deliberately differs
     * from [MapPalette.nation] in step and in saturation so that flipping between the two layers
     * reads as a change of subject; a set chosen for separability has no spare saturation to give
     * away, so the change of subject is carried by the reshuffle alone.
     */
    internal fun people(id: Int): Int {
        val ramp = realmRamp ?: return MapPalette.culture(id)
        return ramp[(id + PEOPLES_OFFSET_IN_THE_SET).mod(ramp.size)]
    }

    /**
     * Whether the sheet pixel at [sheetX], [sheetY] of realm [id] takes the hatch.
     *
     * The nine colours run out at nine realms and the tenth begins the set again, so each further
     * turn of the cycle is given a texture instead of a hue: diagonal strokes for the second nine,
     * the other diagonal for the third. The comb is the one [inked] draws for line art, at a
     * coarser pitch — two pixels of ink in six — so that it reads as a hatch at map scale rather
     * than as a dither.
     *
     * Ruled on the true-shape sheet ([SheetGeometry]) and asked once a cell, at the cell's top-left
     * pixel, so the diagonals run at forty-five degrees on the drawn map; ruled in cells they would
     * lean to the shape of a cell.
     */
    internal fun hatched(id: Int, sheetX: Int, sheetY: Int): Boolean {
        val ramp = realmRamp ?: return false
        return when ((id / ramp.size) % HATCHES_BEFORE_REPEATING) {
            1 -> (sheetX + sheetY) % COMB_PITCH_PIXELS < COMB_INK_PIXELS
            // Written with no negative operand anywhere, rather than as (x - y) mod 6: GLSL leaves
            // % undefined when either side is negative, and the shader has to agree with this to
            // the bit. (x + (6 - y mod 6)) mod 6 is the same anti-diagonal.
            2 -> (sheetX + (COMB_PITCH_PIXELS - sheetY % COMB_PITCH_PIXELS)) % COMB_PITCH_PIXELS <
                COMB_INK_PIXELS
            else -> false
        }
    }

    /** A realm fill with its hatch applied, where the sheet pixel takes one. */
    internal fun realmFill(id: Int, sheetX: Int, sheetY: Int): Int {
        val fill = realm(id)
        return if (hatched(id, sheetX, sheetY)) {
            MapPalette.blend(fill, coastline, HATCH_STRENGTH)
        } else {
            fill
        }
    }

    /** The same for a people. */
    internal fun peopleFill(id: Int, sheetX: Int, sheetY: Int): Int {
        val fill = people(id)
        return if (hatched(id, sheetX, sheetY)) {
            MapPalette.blend(fill, coastline, HATCH_STRENGTH)
        } else {
            fill
        }
    }

    companion object {
        /**
         * How dark a hatch stroke runs over the fill beneath it.
         *
         * Far enough to be a texture at a glance (7.52 CIEDE2000 at worst against the fill it
         * crosses, over every ground and under both red-green deficiencies, measured in
         * `ClearStyleTest`), short of far enough to swallow which colour is underneath it.
         *
         * Public only because [RasterRecipe] carries it to the graphics card.
         */
        const val HATCH_STRENGTH: Float = 0.45f

        /**
         * How many turns of the realm set are told apart before the textures repeat.
         *
         * Three: the plain fill, one diagonal, the other. Twenty-eight realms would begin the
         * whole cycle again, which is well past the point at which a reader is counting rather
         * than recognising.
         */
        private const val HATCHES_BEFORE_REPEATING = 3

        /**
         * The comb the hatch is ruled at: two sheet pixels of ink in every six.
         *
         * Coarser than the comb [MapStyle.PEN_AND_INK] draws with, deliberately, so that at map
         * scale it reads as a hatch rather than as a dither of the fill it crosses. On cells two
         * pixels wide, asked at every other pixel, it still comes to one cell in three along a row.
         */
        private const val COMB_PITCH_PIXELS = 6
        private const val COMB_INK_PIXELS = 2

        /**
         * How far along the realm set the peoples' colours start.
         *
         * Four of nine, so no people shares a colour with the realm of the same id and the two
         * layers are plainly a change of subject. See [people].
         */
        private const val PEOPLES_OFFSET_IN_THE_SET = 4
    }
}
