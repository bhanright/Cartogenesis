package com.cartogenesis.cartography

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That every style draws its rivers and its lakes as one water, and that the water still reads.
 *
 * One water: a lake's shallows are the river's own colour, and a deep lake is that colour darker,
 * so the lake's depth shading continues the ramp the river begins (see [MapStyle.river]). A line-art
 * style has no fill to spend and draws both in its one ink: rivers in it, lakes as paper ruled in
 * it.
 *
 * Reads: measured in CIEDE2000 against the ground each style actually paints, on the gallery's
 * world at 512 with its relief and its climate, because the land a river crosses is not a ramp
 * stop but whatever the style made of the ramp there. The bar is [AT_A_GLANCE], where
 * `ColorVision.deltaE2000` puts two colours anybody would call different at a glance, and it is
 * held at the tenth percentile of the land so that nine cells in ten clear it.
 */
class WaterColorTest {

    @Test
    fun `rivers and lakes are one water in every style`() {
        val apart = MapStyle.entries.filter { style ->
            if (style.lineArt) {
                style.river != style.coastline || style.lake != style.paper || style.lakeDeep != style.paper
            } else {
                style.lake != style.river ||
                    ColorVision.luminance(style.lakeDeep) >= ColorVision.luminance(style.lake)
            }
        }
        MapStyle.entries.forEach { style ->
            println(
                "WATER %-12s river #%06X lake #%06X deep #%06X, river to lake %.1f".format(
                    style.name, style.river and 0xFFFFFF, style.lake and 0xFFFFFF,
                    style.lakeDeep and 0xFFFFFF, ColorVision.deltaE2000(style.river, style.lake)
                )
            )
        }
        assertTrue(apart.isEmpty(), "rivers and lakes are two waters in ${apart.map { it.name }}")
    }

    @Test
    fun `rivers stand out against the land and lakes against the land and the sea`() {
        val world = TestWorlds.gallery
        val failures = ArrayList<String>()
        for (style in MapStyle.entries) {
            val raster = MapRasterizer.rasterize(world, RenderOptions(style = style, showCoastline = false))
            val land = raster.indices
                .filter { world.sea.isLand[it] && !world.rivers.lakes.isLake(it) }
                .map { raster[it] }
            // A line-art style's ground is its paper, and its land's darkest tenth is its own
            // hachures, which are the river's ink by design.
            val ground = if (style.lineArt) listOf(style.paper) else land
            val riverOnLand = tenthPercentile(ground.map { ColorVision.deltaE2000(style.river, it) })
            val lakeOnLand = tenthPercentile(ground.map { ColorVision.deltaE2000(style.lake, it) })
            // Against the sea at its shallowest, which is the sea a reader compares a lake with: the
            // coastal water, not the abyss.
            val shallowSea = style.oceanRamp.last()
            val lakeFromSea = minOf(
                ColorVision.deltaE2000(style.lake, shallowSea),
                ColorVision.deltaE2000(style.lakeDeep, shallowSea)
            )
            println(
                "WATER %-12s river on land %.1f, lake on land %.1f, lake from the shallow sea %.1f"
                    .format(style.name, riverOnLand, lakeOnLand, lakeFromSea)
            )
            if (riverOnLand < AT_A_GLANCE) failures += "${style.name} river on land %.1f".format(riverOnLand)
            // A line-art lake is paper told from the land by its ruling and its inked shore, not
            // by a fill (`Engraving.lakeWater`), and its sea is paper too.
            if (style.lineArt) continue
            if (lakeOnLand < AT_A_GLANCE) failures += "${style.name} lake on land %.1f".format(lakeOnLand)
            if (lakeFromSea < AT_A_GLANCE) {
                failures += "${style.name} lake from the sea %.1f".format(lakeFromSea)
            }
        }
        assertTrue(failures.isEmpty(), "water that does not read: $failures")
    }

    private fun tenthPercentile(values: List<Double>): Double {
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 10]
    }

    private companion object {
        /** CIEDE2000: "5 upward is two colours anybody would call different at a glance". */
        const val AT_A_GLANCE = 5.0
    }
}
