package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [WorldGenConfig.atResolution] promises: the width and the height, and nothing else.
 *
 * This class used to assert that a dozen named settings were multiplied by the grid ratio, and
 * then that the tectonics' belt widths were, which was the only way to ask "is this still the same
 * world at export size" while a setting was a count of cells. Every one is a length in kilometers,
 * a depth in meters or a rate in years now, converted where each stage reads it, so a world built
 * directly at a grid and one re-targeted to it are the same world, and `ScaleFreeTest` asks whether
 * the finished world holds across grids.
 *
 * What is asserted is read off the whole of the settings through their serialised form, so a
 * setting added later is covered without being named: a change of grid moves the width and the
 * height and leaves every section and the world's own scale as they were; and the tectonics carry
 * no count of cells, which nothing would carry to another grid any more.
 */
class ResolutionScalingTest {

    private val base = WorldGenConfig(seed = 1L, width = 512, height = 512)

    private val json = Json { encodeDefaults = true }

    private fun sections(config: WorldGenConfig): JsonObject =
        json.encodeToJsonElement(WorldGenConfig.serializer(), config).jsonObject

    @Test
    fun `a change of grid moves the width and the height and nothing else`() {
        val scaled = base.atResolution(2048, 1024)
        assertEquals(2048, scaled.width)
        assertEquals(1024, scaled.height)
        assertEquals(base.copy(width = 2048, height = 1024), scaled, "atResolution moved a setting")

        val before = sections(base)
        val after = sections(scaled)
        val untouched = before.keys - setOf("width", "height")
        assertTrue(
            untouched.contains("tectonics") && untouched.contains("scale"),
            "the sections were not read"
        )
        untouched.forEach { name ->
            assertEquals(
                before.getValue(name), after.getValue(name),
                "$name moved with the grid: a length, a depth, an area, a frequency or a share is " +
                    "the same at every grid, and the conversion is the stage's, not this function's"
            )
        }
    }

    @Test
    fun `the tectonics carry no count of cells`() {
        val counted = sections(base).getValue("tectonics").jsonObject.keys.filter { it.endsWith("Cells") }
        assertTrue(
            counted.isEmpty(),
            "tectonics.${counted.joinToString()} is a count of cells, which is half as far on the " +
                "ground on a grid of twice the cells and which nothing carries across a change of grid"
        )
    }

    @Test
    fun `changing resolution and changing back returns the original world`() {
        // The resolution picker calls this on every change, so a user moving the slider back and
        // forth must end where they started.
        val roundTripped = base.atResolution(2048, 2048).atResolution(512, 512)
        assertEquals(base, roundTripped)

        val viaSteps = base.atResolution(1024, 1024).atResolution(2048, 2048)
        assertEquals(base.atResolution(2048, 2048), viaSteps)
    }
}
