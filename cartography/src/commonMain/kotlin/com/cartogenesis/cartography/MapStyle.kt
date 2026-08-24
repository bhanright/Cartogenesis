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
 * The three levers that do most of the work are worth naming, since they are what separates a
 * cartographer's map from a satellite photograph:
 *
 *  - **[biomeWash]** — how much of the vegetation colour is allowed through. At 0.45 the map reads
 *    as terrain seen from above; at 0 it reads as something drawn, where height alone carries the
 *    shape.
 *  - **[biomeMuting]** — how far each biome colour is dragged toward the paper before it is used.
 *    This is what stops an old chart looking like a modern one with a filter over it: aged inks
 *    are not saturated greens dimmed, they are earths.
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
     * A different way of drawing rather than a different palette. Nothing is tinted by height at
     * all: the paper shows through everywhere, and relief is expressed by hatching — short diagonal
     * strokes laid down where the ground is steep and left off where it is flat, which is how a
     * pen describes a mountain when it has no colour to describe it with. Ridges come out dense,
     * plains blank, and the shape reads from the density alone.
     *
     * It stops short of the thing it is imitating. A hand-drawn map draws each range as a little
     * picture of a mountain, repeated and shaded by eye; this hatches by slope, so the texture is
     * right and the pictograms are not there.
     */
    internal val lineArt: Boolean,
    /** How readily the hatching darkens as the ground steepens. Only used when [lineArt]. */
    internal val inkGain: Float,
    /** Drawn behind the map, and used by a front end for the surround. */
    val backdrop: Int
) {
    ATLAS(
        label = "Atlas",
        detail = "Modern hypsometric tints",
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
        detail = "Pull-down classroom wall map",
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
        glyphMuting = 0.25f,
        lineArt = false,
        inkGain = 0f,
        backdrop = 0xFF2B2A20.toInt()
    ),

    /**
     * Pen and ink on blank paper, after the maps drawn for high fantasy. No fill anywhere: the
     * coast is a line, the rivers are lines, and the mountains are hatching laid on where the
     * ground is steep. Borders are the one thing in colour, as they often are on those maps.
     */
    PEN_AND_INK(
        label = "Pen and ink",
        detail = "Line art: hatched relief, red borders",
        oceanRamp = intArrayOf(
            0xFFF6F2E8.toInt(), 0xFFF6F2E8.toInt(), 0xFFF7F3EA.toInt(),
            0xFFF8F4EC.toInt(), 0xFFF9F6EF.toInt()
        ),
        landRamp = intArrayOf(
            0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(),
            0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt(), 0xFFFBF8F0.toInt()
        ),
        paper = 0xFFFBF8F0.toInt(),
        biomeWash = 0f,
        biomeMuting = 1f,
        river = 0xFF1E1A16.toInt(),
        lake = 0xFFDCD8CE.toInt(),
        lakeDeep = 0xFFC6C2B8.toInt(),
        coastline = 0xFF17130F.toInt(),
        coastlineStrength = 0.95f,
        border = 0xFFA82820.toInt(),
        wilderness = 0xFFF1EDE3.toInt(),
        reliefStrength = 1f,
        glyphMuting = 0.5f,
        lineArt = true,
        // Low enough that only a genuinely steep face fills solid. At 3.2 anything with a slope at
        // all crossed the threshold on every hatch line, and whole ranges came out as black mass.
        inkGain = 1.15f,
        backdrop = 0xFF262320.toInt()
    );

    internal fun ocean(depth: Float): Int =
        MapPalette.ramp(oceanRamp, 1f - depth.coerceIn(0f, 1f))

    internal fun land(elevation: Float): Int =
        MapPalette.ramp(landRamp, elevation.coerceIn(0f, 1f))

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

    /**
     * Whether this cell takes ink, for a line-art style.
     *
     * The hatch is a diagonal comb: a cell's threshold depends on where it sits, so ink lands in
     * lines running across the slope rather than as a grey smear. The steeper the ground the lower
     * the bar, so a ridge fills solid, a hillside becomes stripes, and a plain stays blank.
     */
    internal fun inked(x: Int, y: Int, shade: Float): Boolean {
        if (!lineArt) return false
        val steepness = (1f - shade).coerceAtLeast(0f)
        val hatch = (((x + y) % 5) + 1) / 6f
        return steepness * inkGain > hatch
    }

    /** Relief, exaggerated or softened. 1 leaves the hillshade exactly as computed. */
    internal fun relief(shade: Float): Float = 1f + (shade - 1f) * reliefStrength
}
