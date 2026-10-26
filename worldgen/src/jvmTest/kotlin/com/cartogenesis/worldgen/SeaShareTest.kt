package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The ocean-coverage slider asks for a share of the planet's surface, and the sea-level cut puts that
 * share of its area under water, not that share of the map's cells (docs/DESIGN_LEDGER.md, TH1-1b).
 *
 * The map's cells are a degree of longitude wide at every latitude, so a polar row is a sliver of
 * the ground an equatorial row is; the tectonic history lays its crust on the sphere, and a cut that
 * counted cells drowned a different share of the planet wherever the crust happened to lie. The
 * ground here falls from the equator to both poles, so whatever is drowned is the high latitudes:
 * asked for half the planet, the cut must drown the ground poleward of thirty degrees, where half
 * the sphere's area lies (`1 - sin 30`). The control is the cut the map's cells would make, half of
 * the rows, which drowns only the ground poleward of forty-five degrees: 0.29 of the planet.
 */
class SeaShareTest {

    @Test
    fun `the sea covers the share of the planet the slider asks for`() {
        val columns = 512
        val rows = 256
        val height = FloatField(columns, rows)
        for (row in 0 until rows) {
            val latitude = 90.0 - (row + 0.5) * 180.0 / rows
            for (column in 0 until columns) {
                // Lower toward the poles, and each cell its own height so the cut is never a tie.
                height.data[row * columns + column] = (-abs(latitude) + column * 1e-4).toFloat()
            }
        }
        val scale = WorldGenConfig.forRows(1L, rows).scale
        val asked = 0.5f
        val cut = SeaLevelStage.percentileCut(height, asked, scale)
        val drowned = areaShare(height) { it < cut.shorelineHeight }
        val byCells = height.data.sorted()[(height.data.size * asked).toInt()]
        val drownedByCells = areaShare(height) { it < byCells }
        println(
            "SEA SHARE asked %.3f of the planet: the cut drowns %.4f of its area; a cut by the map's cells would drown %.4f"
                .format(Locale.ROOT, asked, drowned, drownedByCells)
        )
        assertTrue("the cut drowns $drowned of the planet's area where $asked was asked", abs(drowned - asked) <= ROW_WEIGHT_SLACK)
        assertTrue(
            "a cut by cells drowns $drownedByCells of the area, as the cut by area does, so the test cannot tell them apart",
            abs(drownedByCells - asked) > CONTROL_GAP
        )
    }

    /** The share of the planet's area, each cell weighing the cosine of its latitude, that [drowned] holds for. */
    private fun areaShare(height: FloatField, drowned: (Float) -> Boolean): Double {
        var inside = 0.0
        var all = 0.0
        for (row in 0 until height.height) {
            val weight = cos((90.0 - (row + 0.5) * 180.0 / height.height) * PI / 180.0)
            for (column in 0 until height.width) {
                all += weight
                if (drowned(height.data[row * height.width + column])) inside += weight
            }
        }
        return inside / all
    }

    private companion object {
        /** The cut lands on a cell, so it may miss by one cell's share of the planet: far less than this. */
        const val ROW_WEIGHT_SLACK = 1e-3

        /** The control misses by 0.21; it must miss by more than this to tell the two cuts apart. */
        const val CONTROL_GAP = 0.1
    }
}
