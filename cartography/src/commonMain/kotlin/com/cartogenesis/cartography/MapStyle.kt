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
        backdrop = 0xFF080B12.toInt()
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
    internal fun glyph(color: Int): Int =
        if (glyphMuting <= 0f) color else MapPalette.blend(color, paper, glyphMuting)

    /** Relief, exaggerated or softened. 1 leaves the hillshade exactly as computed. */
    internal fun relief(shade: Float): Float = 1f + (shade - 1f) * reliefStrength
}
