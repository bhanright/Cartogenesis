package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What is left of [WorldGenConfig.atResolution]: the tectonics' widths in cells, and nothing else.
 *
 * This class used to assert that a dozen named settings were multiplied by the grid ratio, which
 * was the only way to ask "is this still the same world at export size" while every reach was a
 * count of cells and every depth a fraction of an assumed range. They are lengths in kilometres and
 * depths in metres now, converted where each stage reads them, so there is nothing left to carry
 * for them — `ScaleFreeTest` asks the question the contracts were standing in for, and asks it of
 * the finished world rather than of the settings.
 *
 * One group is still carried by hand, and it is held here because a contract that is still a
 * contract still needs a guard: the tectonics' belt, arc, rift, drift, blur and hotspot widths,
 * every one a count of cells named `...Cells`. What is asserted is read off the whole of the
 * setting rather than off a list of names, through its serialised form: every tectonics setting
 * whose name ends in `Cells` is scaled by the grid ratio, every other tectonics setting is left
 * alone, and every other section and the world's own scale are left alone. A width added to the
 * tectonics and forgotten in `atResolution`, or a setting scaled that carries a unit, fails here by
 * name. See [WorldGenConfig.atResolution] for the whole of the reasoning.
 */
class ResolutionScalingTest {

    private val base = WorldGenConfig(seed = 1L, width = 512, height = 512)

    private val json = Json { encodeDefaults = true }

    private fun sections(config: WorldGenConfig): JsonObject =
        json.encodeToJsonElement(WorldGenConfig.serializer(), config).jsonObject

    @Test
    fun `every tectonic width in cells is carried by hand and nothing else is`() {
        val ratio = 4f
        val scaled = base.atResolution(2048, 2048)
        assertEquals(2048, scaled.width)
        assertEquals(2048, scaled.height)

        val before = sections(base).getValue("tectonics").jsonObject
        val after = sections(scaled).getValue("tectonics").jsonObject
        val carried = before.keys.filter { it.endsWith("Cells") }
        assertTrue(carried.isNotEmpty(), "the tectonics carry no width in cells, so this compared nothing")
        before.forEach { (name, value) ->
            val moved = after.getValue(name)
            if (name in carried) {
                assertEquals(
                    (value as JsonPrimitive).float * ratio, (moved as JsonPrimitive).float,
                    "tectonics.$name is a count of cells and was not scaled with the grid"
                )
            } else {
                assertEquals(value, moved, "tectonics.$name carries no cells and was scaled with the grid")
            }
        }
    }

    @Test
    fun `everything outside the tectonics is left alone`() {
        val before = sections(base)
        val after = sections(base.atResolution(2048, 2048))
        val untouched = before.keys - setOf("tectonics", "width", "height")
        assertTrue(untouched.contains("rivers") && untouched.contains("scale"), "the sections were not read")
        untouched.forEach { name ->
            assertEquals(
                before.getValue(name), after.getValue(name),
                "$name moved with the grid: a length, a depth, an area, a frequency or a share is " +
                    "the same at every grid, and the conversion is the stage's, not this function's"
            )
        }
    }

    @Test
    fun `changing resolution and changing back returns the original world`() {
        // The resolution picker calls this on every change, so the scaling has to be reversible or
        // a user moving the slider back and forth would slowly deform their world. Every offered
        // resolution is a power of two, and scaling a float by a power of two is exact, so this is
        // an equality rather than a tolerance.
        val roundTripped = base.atResolution(2048, 2048).atResolution(512, 512)
        assertEquals(base, roundTripped)

        val viaSteps = base.atResolution(1024, 1024).atResolution(2048, 2048)
        assertEquals(base.atResolution(2048, 2048), viaSteps)
    }
}
