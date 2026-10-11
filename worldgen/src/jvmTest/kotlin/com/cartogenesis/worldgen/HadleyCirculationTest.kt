package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.HadleyCirculation
import com.cartogenesis.worldgen.pipeline.PressureWind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The zonal-mean circulation's Hadley cells ([HadleyCirculation]) against the source they are built
 * from, and the properties the climate relies on: mass conserved, the cells unequal off the equator,
 * the rising branch migrating while the edges hold.
 */
class HadleyCirculationTest {

    private companion object {
        /** Earth's mean radius, meters, as Hill, Bordoni and Mitchell (2019) take Earth's values. */
        const val EARTH_RADIUS_M = 6.371e6

        /**
         * How near Hill and others' figures the cells must come, degrees: their figures are quoted
         * to a tenth, and they and this solve the same closed conditions, so a few tenths is the
         * difference of Earth's constants (their `g` and `a`, quoted only as "Earth's values").
         */
        const val SOURCE_TOLERANCE_DEGREES = 0.3

        const val DEGREES = PI / 180.0
    }

    @Test
    fun `the equal-area cells are Hill, Bordoni and Mitchell's`() {
        // Their section 3c: a heating at 6 N rises at 21.2 N; one at 23.5 N spans 61.7 S to 44.8 N.
        val six = HadleyCirculation.cellsFor(6.0, EARTH_RADIUS_M)
        println("HADLEY heating 6 N: rises at %.2f, edges %.2f and %.2f".format(six.ascentLatitudeDegrees, six.winterEdgeDegrees, six.summerEdgeDegrees))
        assertEquals(21.2, six.ascentLatitudeDegrees, SOURCE_TOLERANCE_DEGREES)
        val solstice = HadleyCirculation.cellsFor(23.5, EARTH_RADIUS_M)
        println("HADLEY heating 23.5 N: rises at %.2f, edges %.2f and %.2f".format(solstice.ascentLatitudeDegrees, solstice.winterEdgeDegrees, solstice.summerEdgeDegrees))
        assertEquals(-61.7, solstice.winterEdgeDegrees, SOURCE_TOLERANCE_DEGREES)
        assertEquals(44.8, solstice.summerEdgeDegrees, SOURCE_TOLERANCE_DEGREES)
    }

    @Test
    fun `the boundary layer's flow closes in each cell`() {
        for (heating in listOf(0.0, 2.0, -3.5, 5.0)) {
            val circulation = HadleyCirculation.forHeating(heating, EARTH_RADIUS_M)
            val south = -circulation.cells.southEdgeDegrees
            val north = circulation.cells.northEdgeDegrees
            val scale = sample(south, north).maxOf { abs(circulation.cellNorthwardMps(it)) }
            // Zero at both edges: what comes down in the cells goes back up in them.
            for (latitude in listOf(south + 1e-6, north - 1e-6)) {
                val wind = circulation.cellNorthwardMps(latitude)
                assertTrue(abs(wind) < 1e-6 * scale, "heating at $heating: the cells' wind at $latitude is $wind of $scale m/s")
            }
            // The flow converges at the rising branch: northward on its south side, southward on its north.
            val rising = circulation.ascentLatitudeDegrees
            assertTrue(
                circulation.cellNorthwardMps(rising - 0.5) > 0.0 && circulation.cellNorthwardMps(rising + 0.5) < 0.0,
                "heating at $heating: the boundary layer's flow does not meet at the rising branch, $rising"
            )
            // The vertical motion's integral over the sphere between the edges is the flow through them: none.
            var ascent = 0.0
            var size = 0.0
            for (latitude in sample(south, north)) {
                val omega = circulation.verticalMotionPaPerSecond(latitude) * cos(latitude * DEGREES)
                ascent += omega
                size += abs(omega)
            }
            assertTrue(abs(ascent) < 1e-3 * size, "heating at $heating: the cells' vertical motion integrates to ${ascent / size} of itself")
        }
    }

    @Test
    fun `off the equator the winter cell carries the circulation and the summer cell little`() {
        // Lindzen and Hou (1988), as Smith's lecture notes read them: at 4 degrees the winter cell's
        // mass flux is over an order of magnitude the summer cell's; at the equator the two are equal.
        val equator = cellFluxes(0.0)
        println("HADLEY heating 0: winter cell %.3e, summer cell %.3e kg/s".format(equator.first, equator.second))
        assertEquals(1.0, equator.first / equator.second, 1e-3)
        val off = cellFluxes(4.0)
        println("HADLEY heating 4 N: winter cell %.3e, summer cell %.3e kg/s, ratio %.1f".format(off.first, off.second, off.first / off.second))
        assertTrue(off.first > 10.0 * off.second, "at 4 degrees the winter cell carries ${off.first / off.second} times the summer cell's mass")
    }

    @Test
    fun `the rising branch migrates and the cells' edges move far less, where translated belts move together`() {
        val equinox = HadleyCirculation.cellsFor(0.0, EARTH_RADIUS_M)
        val season = HadleyCirculation.cellsFor(3.0, EARTH_RADIUS_M)
        val risingMoved = season.ascentLatitudeDegrees - equinox.ascentLatitudeDegrees
        val edgesMoved = maxOf(abs(season.northEdgeDegrees - equinox.northEdgeDegrees), abs(season.southEdgeDegrees - equinox.southEdgeDegrees))
        println("HADLEY from 0 to 3 N: the rising branch moves %.2f degrees, the edges at most %.2f".format(risingMoved, edgesMoved))
        assertTrue(edgesMoved < MIGRATION_SHARE * risingMoved, "the edges moved $edgesMoved degrees against the rising branch's $risingMoved")
        // The control: the belts translated whole, as the climate moved them before, move their edges
        // with their rising branch.
        val translatedEdgesMoved = risingMoved
        assertTrue(!(translatedEdgesMoved < MIGRATION_SHARE * risingMoved), "the translated belts would pass this clause")
    }

    /**
     * The share of the rising branch's migration the edges may move by: five sevenths. Barry and
     * Chorley's subtropical highs move "a few degrees" between summer and winter, five at most, where
     * the ITCZ crosses seven degrees over the open Atlantic and Pacific, 9 N to 2 N (Schneider,
     * Bischoff and Haug 2014). Translated belts move their edges with the rising branch, a share of one.
     */
    private val MIGRATION_SHARE = 5.0 / 7.0

    @Test
    fun `a southern heating is the northern one's mirror`() {
        val north = HadleyCirculation.forHeating(3.0, EARTH_RADIUS_M)
        val south = HadleyCirculation.forHeating(-3.0, EARTH_RADIUS_M)
        assertEquals(north.ascentLatitudeDegrees, -south.ascentLatitudeDegrees, 1e-9)
        for (latitude in listOf(-25.0, -10.0, 0.0, 7.5, 18.0, 45.0)) {
            val (northEast, northNorth) = HadleyCirculation.windMps(north, south, latitude)
            val (southEast, southNorth) = HadleyCirculation.windMps(south, north, -latitude)
            assertEquals(northEast, southEast, 1e-6)
            assertEquals(northNorth, -southNorth, 1e-6)
        }
    }

    @Test
    fun `the eddies' forcing of the zonal wind is the same in both halves and holds Earth's at the equator`() {
        val july = HadleyCirculation.forHeating(3.0, EARTH_RADIUS_M)
        val january = HadleyCirculation.forHeating(-3.0, EARTH_RADIUS_M)
        for (latitude in sample(-60.0, 60.0, 240)) {
            val coriolis = PressureWind.coriolisParameter(latitude.toFloat()).toDouble()
            fun forcing(half: HadleyCirculation, other: HadleyCirculation): Double {
                val (east, north) = HadleyCirculation.windMps(half, other, latitude)
                return PressureWind.beltDrag(east.toFloat(), (-north).toFloat()) * east - coriolis * north
            }
            val inJuly = forcing(july, january)
            val inJanuary = forcing(january, july)
            assertEquals(inJuly, inJanuary, 1e-3 * (abs(inJuly) + 1e-7), "at $latitude")
        }
        // At the equator the Coriolis force vanishes and the wind is the eddies' alone, Earth's easterly, under
        // the drag of the half's own speed: within a meter a second.
        val (equatorEast, _) = HadleyCirculation.windMps(july, january, 0.0)
        assertEquals(-PressureWind.BELT_SPEED_MPS.toDouble(), equatorEast, 1.0)
    }

    @Test
    fun `a heating near the pole holds the cells at the farthest that close`() {
        // A planet tilted far past Earth's has its summer pole warmest; the equal-area cells for a
        // heating there reach a pole first, and the circulation is held where they still close.
        val cells = HadleyCirculation.cellsFor(85.0, EARTH_RADIUS_M)
        println("HADLEY heating 85 N held: rises at %.1f, edges %.1f and %.1f".format(cells.ascentLatitudeDegrees, cells.winterEdgeDegrees, cells.summerEdgeDegrees))
        assertTrue(cells.winterEdgeDegrees > -90.0 && cells.summerEdgeDegrees < 90.0 && cells.ascentLatitudeDegrees > 0.0)
    }

    @Test
    fun `a smaller planet's cells reach farther`() {
        // The thermal Rossby number goes as the inverse square of the radius at the same spin.
        val earth = HadleyCirculation.cellsFor(0.0, EARTH_RADIUS_M)
        val half = HadleyCirculation.cellsFor(0.0, EARTH_RADIUS_M / 2)
        println("HADLEY equinox edges: Earth %.1f, half Earth's radius %.1f".format(earth.northEdgeDegrees, half.northEdgeDegrees))
        assertTrue(half.northEdgeDegrees > earth.northEdgeDegrees)
    }

    /** The boundary layer's greatest mass flux toward the rising branch in the winter cell and in the summer cell, kg/s. */
    private fun cellFluxes(heating: Double): Pair<Double, Double> {
        val circulation = HadleyCirculation.forHeating(heating, EARTH_RADIUS_M)
        var winter = 0.0
        var summer = 0.0
        for (latitude in sample(-circulation.cells.southEdgeDegrees, circulation.cells.northEdgeDegrees)) {
            val flux = abs(circulation.cellNorthwardMps(latitude)) * 2 * PI * EARTH_RADIUS_M * cos(latitude * DEGREES) *
                HadleyCirculation.LOWER_BRANCH_PA / 9.80665
            if (latitude < circulation.ascentLatitudeDegrees) winter = maxOf(winter, flux) else summer = maxOf(summer, flux)
        }
        return winter to summer
    }

    private fun sample(from: Double, to: Double, steps: Int = 2_000): List<Double> =
        (1 until steps).map { from + (to - from) * it / steps }
}
