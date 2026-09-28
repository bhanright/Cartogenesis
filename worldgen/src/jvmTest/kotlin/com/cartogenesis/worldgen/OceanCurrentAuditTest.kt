package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.OceanHeat
import com.cartogenesis.worldgen.pipeline.OceanStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [OceanCurrentTest]'s sense bars on the author's own world at 2048, built as the app builds it,
 * and where that world puts its coldest and warmest water against where Earth puts its own.
 * In the audit tier: one 2048 world is minutes of erosion for one more world under the same bars.
 */
class OceanCurrentAuditTest {

    private companion object {
        val CONFIG = WorldGenConfig(seed = 969495L, width = 512, height = 512).atResolution(2048, 2048)

        /** Generated once for both clauses, since it is the cost of the class. */
        val WORLD: WorldMap by lazy { WorldGenerationEngine.generateBlocking(CONFIG) }

        /**
         * Where Earth's coldest west-coast water lies, in degrees from the equator: 10.15 to 42.31,
         * the four eastern-boundary upwelling systems' zones together as Abrahams, Schlegel and Smit
         * (2021, *Front. Mar. Sci.* 8, 626411) take them from the studies before them: the
         * California Current's 33.88 to 42.31 N, the Canary's 18.89 to 32.63 N, the Humboldt's
         * 10.15 to 37.62 S and the Benguela's 16.39 to 30.13 S. The 15 to 35 this held before was
         * attributed to a comparison that was never read, and leaves out most of the California
         * Current. Searched for from twice the equatorial deformation radius, where the equatorial
         * closure's cold tongue stops, to 50 degrees, so the band asked for is narrower than the one
         * searched and the tongue is not taken for a coast's upwelling (`ColdWaterPlacementTest`
         * asks the same of the standard worlds, and says why).
         */
        const val COLD_COAST_EQUATORWARD_DEGREES = 10.15f
        const val COLD_COAST_POLEWARD_DEGREES = 42.31f
        val SEARCH_EQUATORWARD_DEGREES: Float = (2.0 * OceanHeat.deformationRadiusMeters(0.0, CONFIG.scale.radiusMeters) /
            CONFIG.scale.metersPerDegreeLatitude).toFloat()
        const val SEARCH_POLEWARD_DEGREES = 50f

        /**
         * Where "high latitude" starts for the warm water, in degrees: poleward of the westerlies'
         * center, where Earth's warmest departure is the Norwegian Sea's, on the North Atlantic's
         * eastern side, and stops at the edge of the polar easterlies' center.
         */
        const val HIGH_LATITUDE_DEGREES = 45f
        const val POLAR_DEGREES = 75f

    }

    @Test
    fun `the gyres turn with the wind on the author's world at 2048`() {
        val beltsOnly = CONFIG.copy(climate = CONFIG.climate.copy(pressureWinds = false))
        val failures = OceanSense.check("seed 969495 at 2048", CONFIG, WORLD.sea, WORLD.ocean, OceanStage.generate(beltsOnly, WORLD.sea))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * The coldest subtropical west-coast water lies within the upwelling systems' 10.15 to 42.31
     * degrees, and the warmest water poleward of 45 lies in the eastern half of its basin, as
     * Earth's do: the Norwegian Sea's,
     * where the North Atlantic Current ends.
     * The anomaly's range is printed beside them.
     */
    @Test
    fun `the coldest west coast and the warmest high-latitude water sit where Earth's do`() {
        val across = WORLD.width
        val down = WORLD.height
        val sea = WORLD.sea
        val anomaly = WORLD.ocean.anomaly.data

        // Open water only: each row's runs of water between two shores at least a basin long
        // (OceanSense.SHORTEST_BASIN_KM), so that a strait, a bay or a lake is not an ocean's side.
        val shortest = (OceanSense.SHORTEST_BASIN_KM / CONFIG.scale.cellWidthKm(across)).toInt()
        var coldestC = 0f
        var coldestLatitude = Float.NaN
        var warmestC = Float.NEGATIVE_INFINITY
        var warmestLatitude = Float.NaN
        var warmestShareFromWest = Float.NaN
        for (row in 0 until down) {
            val latitude = ClimateStage.latitudeOf(row, down)
            val firstLand = (0 until across).firstOrNull { sea.isLand[row * across + it] } ?: continue
            var offset = 1
            while (offset <= across) {
                if (sea.isLand[row * across + (firstLand + offset) % across]) { offset++; continue }
                val runStart = offset
                while (offset <= across && !sea.isLand[row * across + (firstLand + offset) % across]) offset++
                val length = offset - runStart
                if (length < shortest) continue
                val eastEnd = row * across + (firstLand + runStart + length - 1) % across
                if (abs(latitude) in SEARCH_EQUATORWARD_DEGREES..SEARCH_POLEWARD_DEGREES && anomaly[eastEnd] < coldestC) {
                    coldestC = anomaly[eastEnd]; coldestLatitude = latitude
                }
                if (abs(latitude) !in HIGH_LATITUDE_DEGREES..POLAR_DEGREES) continue
                for (k in 0 until length) {
                    val cell = row * across + (firstLand + runStart + k) % across
                    if (anomaly[cell] > warmestC) {
                        warmestC = anomaly[cell]; warmestLatitude = latitude
                        warmestShareFromWest = k.toFloat() / (length - 1)
                    }
                }
            }
        }
        val water = sea.isLand.indices.filter { !sea.isLand[it] }.map { anomaly[it] }.sorted()
        println(
            "OCEAN PLACEMENT seed 969495 at 2048: coldest west-coast water %.2f C at %.1f degrees; warmest open water poleward of 45, %.2f C at %.1f degrees, %.2f of its basin's width from the western shore; anomaly P1 %.2f, P99 %.2f, min %.2f, max %.2f C"
                .format(
                    coldestC, coldestLatitude, warmestC, warmestLatitude, warmestShareFromWest,
                    water[(water.size - 1) / 100], water[(water.size - 1) * 99 / 100], water.first(), water.last()
                )
        )
        assertTrue(warmestShareFromWest > 0.5f, "the warmest high-latitude water is on its basin's western side")
        assertTrue(
            abs(coldestLatitude) in COLD_COAST_EQUATORWARD_DEGREES..COLD_COAST_POLEWARD_DEGREES,
            "the coldest west-coast water, %.2f C, lies at %.1f degrees, outside Earth's %.0f to %.0f"
                .format(coldestC, coldestLatitude, COLD_COAST_EQUATORWARD_DEGREES, COLD_COAST_POLEWARD_DEGREES)
        )
    }
}
