package com.cartogenesis.cartography

import com.cartogenesis.worldgen.BorrowsSharedWorlds
import com.cartogenesis.worldgen.pipeline.Biome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That [MapStyle.SCHOOLROOM] colors the land by elevation and by nothing else.
 *
 * The style's promise, on the landing page and in its own KDoc, is a classroom wall map's
 * hypsometric tint: one color for each height, whatever grows there and whatever the weather. So
 * the claim is checked where it could be broken — on the rendered land of a generated world, with
 * the relief shading and the coastline's stroke left off because both are laid over the fill
 * rather than being part of it — and on [MapStyle.ground] itself, across every climate and biome
 * it could be handed.
 */
class SchoolroomElevationTest : BorrowsSharedWorlds() {

    private companion object {
        /**
         * Below this many distinct biomes on dry land the world would not be asking the question.
         * Four is a desert, a grassland, a forest and a tundra: enough for a climate tint or a
         * biome wash to have something to disagree about.
         */
        const val MIN_LAND_BIOMES = 4

        /** Heights asked of [MapStyle.ground]: every twentieth of the ramp, both ends included. */
        const val HEIGHT_STEPS = 20

        /** Climate inputs asked at each height: 0, a half and 1 for each of the three. */
        val CLIMATE_LEVELS = floatArrayOf(0f, 0.5f, 1f)
    }

    @Test
    fun `every dry land cell is drawn the ramp's color at its own height`() {
        val world = TestWorlds.gallery
        val style = MapStyle.SCHOOLROOM
        val drawn = MapRasterizer.rasterize(
            world,
            RenderOptions(style = style, showHillshade = false, showCoastline = false)
        )

        var landCells = 0
        var offTheRamp = 0
        var firstOff = ""
        val biomesOnLand = HashSet<Biome>()
        for (cell in drawn.indices) {
            if (!world.sea.isLand[cell] || world.rivers.lakes.isLake(cell)) continue
            landCells++
            biomesOnLand += world.climate.biome[cell]
            val expected = style.land(DrawnRelief.of(world).data[cell])
            if (drawn[cell] != expected) {
                if (offTheRamp == 0) {
                    firstOff = "cell $cell (${world.climate.biome[cell]}) drew " +
                        "#%06X where its height gives #%06X".format(
                            drawn[cell] and 0xFFFFFF, expected and 0xFFFFFF
                        )
                }
                offTheRamp++
            }
        }
        println(
            "SCHOOLROOM $offTheRamp of $landCells land cells off the elevation ramp, " +
                "${biomesOnLand.size} biomes on land"
        )
        assertTrue(
            biomesOnLand.size >= MIN_LAND_BIOMES,
            "only ${biomesOnLand.size} biomes on this world's land, too few for the guard to mean anything"
        )
        assertEquals(
            0, offTheRamp,
            "$offTheRamp of $landCells land cells are not the color their height gives; first: $firstOff"
        )
    }

    @Test
    fun `the ground's color at a height ignores dryness, cold, canopy and biome`() {
        val style = MapStyle.SCHOOLROOM
        for (step in 0..HEIGHT_STEPS) {
            val height = step.toFloat() / HEIGHT_STEPS
            val ramp = style.land(height)
            for (dryness in CLIMATE_LEVELS) for (coldness in CLIMATE_LEVELS) for (canopy in CLIMATE_LEVELS) {
                Biome.entries.forEach { biome ->
                    assertEquals(
                        ramp,
                        style.ground(height, dryness, coldness, canopy, biome),
                        "at height $height, dryness $dryness, cold $coldness, canopy $canopy and " +
                            "$biome the ground is not the ramp's color"
                    )
                }
            }
        }
    }

    /**
     * The graphics card's half of the same promise: the recipe it is handed carries no climate
     * field and asks for neither lever, so the device has nothing per cell to bend the ramp with.
     * `GpuRasterTest` holds the device's pixels to the processor's for every style.
     */
    @Test
    fun `the graphics card is handed no climate for this style`() {
        val recipe = RasterRecipe.of(TestWorlds.gallery, RenderOptions(style = MapStyle.SCHOOLROOM))!!
        assertEquals(0f, recipe.climateTint, "the recipe asks the device to follow the climate")
        assertEquals(0f, recipe.biomeWash, "the recipe asks the device to wash the biome over the ramp")
        assertNull(recipe.vegetation, "the canopy is uploaded for a style that does not read it")
        assertNull(recipe.scalarA, "the dryness is uploaded for a style that does not read it")
        assertNull(recipe.scalarB, "the coldness is uploaded for a style that does not read it")
    }
}
