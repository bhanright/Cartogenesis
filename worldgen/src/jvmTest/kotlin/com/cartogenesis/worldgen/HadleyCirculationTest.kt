package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.BoundaryLayer
import com.cartogenesis.worldgen.pipeline.HadleyCirculation
import com.cartogenesis.worldgen.pipeline.Season
import com.cartogenesis.worldgen.pipeline.ZonalClimate
import com.cartogenesis.worldgen.pipeline.ZonalColumn
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The zonal-mean circulation ([HadleyCirculation]) against the source its cells are built from, and
 * the properties the climate relies on: mass conserved in each cell, the cells rising at the energy
 * flux equator, their edges where the troposphere turns baroclinically unstable, the surface's torque
 * balanced in each hemisphere, and a wind that turns through zero at every belt's edge rather than
 * switching on a row.
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
    fun `the cells part at Hill and others' supercritical forcing extent, and the summer cell closes Lindzen and Hou's energy`() {
        // Their equation 12, the small-angle root of their equation 10, `(Ro / 2)^(1/3)`, which the
        // exact root stands a little equatorward of for a heating off the equator.
        for (heating in listOf(0.0, 4.0, 10.0, -8.0)) {
            val cells = HadleyCirculation.cellsRisingAt(heating, EARTH_RADIUS_M)
            println("HADLEY heating at %.1f: rises at %.2f, edges %.2f and %.2f".format(heating, cells.ascentLatitudeDegrees, cells.winterEdgeDegrees, cells.summerEdgeDegrees))
            val rossby = 9.80665 * 1.0e4 / ((7.2921159e-5 * EARTH_RADIUS_M) * (7.2921159e-5 * EARTH_RADIUS_M)) *
                HadleyCirculation.EQUILIBRIUM_CONTRAST_SHARE * sin(abs(heating) * DEGREES)
            val smallAngle = Math.cbrt(rossby / 2) / DEGREES
            assertTrue(abs(cells.ascentLatitudeDegrees) <= smallAngle + 1e-9 && abs(cells.ascentLatitudeDegrees) > 0.7 * smallAngle || heating == 0.0,
                "heating at $heating: rises at ${cells.ascentLatitudeDegrees} against the small-angle extent $smallAngle")
            assertTrue(heating == 0.0 || cells.ascentLatitudeDegrees * heating > 0.0)
            // The summer cell's energy, the integral of `theta_rce - theta` over it, closes.
            val ascentSine = sin(cells.ascentLatitudeDegrees * DEGREES)
            val summerSine = sin(cells.summerEdgeDegrees * DEGREES)
            val (low, high) = if (summerSine > ascentSine) ascentSine to summerSine else summerSine to ascentSine
            val closure = cells.contrastFromSouthEdge(high) - cells.contrastFromSouthEdge(low)
            assertTrue(abs(closure) < 1e-6, "heating at $heating: the summer cell's energy closes to $closure")
        }
    }

    @Test
    fun `the boundary layer's flow closes in each cell and meets at the rising branch`() {
        for (heating in listOf(0.0, 2.0, -3.5, 8.0)) {
            for ((north, south) in listOf(null to null, 34.0 to 27.0)) {
                val circulation = if (north == null) HadleyCirculation.forHeating(heating, EARTH_RADIUS_M)
                else HadleyCirculation.forHeating(heating, EARTH_RADIUS_M, northEdgeDegrees = north, southEdgeDegrees = south!!)
                val southEdge = -circulation.southEdgeDegrees
                val northEdge = circulation.northEdgeDegrees
                val scale = sample(southEdge, northEdge).maxOf { abs(circulation.cellNorthwardMps(it)) }
                for (latitude in listOf(southEdge + 1e-6, northEdge - 1e-6)) {
                    val wind = circulation.cellNorthwardMps(latitude)
                    assertTrue(abs(wind) < 1e-5 * scale, "heating at $heating: the cells' wind at $latitude is $wind of $scale m/s")
                }
                val rising = circulation.ascentLatitudeDegrees
                assertTrue(
                    circulation.cellNorthwardMps(rising - 0.5) > 0.0 && circulation.cellNorthwardMps(rising + 0.5) < 0.0,
                    "heating at $heating: the boundary layer's flow does not meet at the rising branch, $rising"
                )
                // Each cell's vertical motion integrates over the sphere to the flow through its edges: none.
                for ((from, to) in listOf(southEdge to rising, rising to northEdge)) {
                    var net = 0.0
                    var size = 0.0
                    for (latitude in sample(from, to)) {
                        val omega = circulation.verticalMotionPaPerSecond(latitude) * cos(latitude * DEGREES)
                        net += omega
                        size += abs(omega)
                    }
                    assertTrue(abs(net) < 5e-3 * size, "heating at $heating: a cell's vertical motion integrates to ${net / size} of itself")
                }
            }
        }
    }

    @Test
    fun `off the equator the winter cell carries more than the summer cell, and at it the two are equal`() {
        val equator = cellFluxes(0.0)
        println("HADLEY heating 0: winter cell %.3e, summer cell %.3e kg/s".format(equator.first, equator.second))
        assertEquals(1.0, equator.first / equator.second, 1e-3)
        val off = cellFluxes(8.0)
        println("HADLEY heating 8 N: winter cell %.3e, summer cell %.3e kg/s, ratio %.1f".format(off.first, off.second, off.first / off.second))
        assertTrue(off.first > off.second, "at 8 degrees the winter cell carries ${off.first / off.second} times the summer cell's mass")
    }

    @Test
    fun `a southern heating is the northern one's mirror`() {
        val north = HadleyCirculation.forHeating(3.0, EARTH_RADIUS_M)
        val south = HadleyCirculation.forHeating(-3.0, EARTH_RADIUS_M)
        assertEquals(north.ascentLatitudeDegrees, -south.ascentLatitudeDegrees, 1e-9)
        assertEquals(north.northWesterlyPeakMps, south.southWesterlyPeakMps, 1e-6)
        for (latitude in listOf(-60.0, -25.0, -10.0, 0.0, 7.5, 18.0, 45.0, 75.0)) {
            val (northEast, northNorth) = north.windMps(latitude)
            val (southEast, southNorth) = south.windMps(-latitude)
            assertEquals(northEast, southEast, 1e-6, "at $latitude")
            assertEquals(northNorth, -southNorth, 1e-6, "at $latitude")
        }
    }

    @Test
    fun `the surface's torque over each hemisphere is zero, the trades' given back under the westerlies`() {
        val perSpeed = BoundaryLayer.dragPerSpeed(isLand = false)
        val gustsSquared = BoundaryLayer.TRANSIENT_WIND_MPS * BoundaryLayer.TRANSIENT_WIND_MPS
        for (heating in listOf(0.0, 8.0, -11.0)) {
            val circulation = HadleyCirculation.forHeating(heating, EARTH_RADIUS_M, northEdgeDegrees = 33.0, southEdgeDegrees = 26.0)
            for (north in listOf(true, false)) {
                var torque = 0.0
                var size = 0.0
                for (fromEquator in sample(0.0, 90.0, 9_000)) {
                    val (east, northward) = circulation.windMps(if (north) fromEquator else -fromEquator)
                    val stress = perSpeed * sqrt(east * east + northward * northward + gustsSquared) * east * cos(fromEquator * DEGREES) * cos(fromEquator * DEGREES)
                    torque += stress
                    size += abs(stress)
                }
                println("HADLEY heating %.0f, %s: torque %.2e of %.2e, westerlies at %.1f m/s".format(
                    heating, if (north) "north" else "south", torque, size,
                    if (north) circulation.northWesterlyPeakMps else circulation.southWesterlyPeakMps))
                assertTrue(abs(torque) < 2e-3 * size, "heating $heating, ${if (north) "north" else "south"}: the torque is ${torque / size} of its size")
            }
        }
    }

    @Test
    fun `the wind turns through zero at the cells' edges and the polar fronts, never stepping on a row`() {
        val circulation = HadleyCirculation.forHeating(9.0, EARTH_RADIUS_M, northEdgeDegrees = 33.0, southEdgeDegrees = 26.0)
        // Earth's annual belts' width: the jet half of it beyond the edge, the front the whole of it.
        assertEquals(48.0, circulation.northBelt.jetDegrees, 0.1)
        assertEquals(63.0, circulation.northBelt.frontDegrees, 0.1)
        assertEquals(56.0, circulation.southBelt.frontDegrees, 0.1)
        var largestStep = 0.0
        var largestWind = 0.0
        var previous = circulation.windMps(-89.99)
        for (latitude in sample(-89.99, 89.99, 36_000)) {
            val wind = circulation.windMps(latitude)
            largestStep = maxOf(largestStep, abs(wind.first - previous.first), abs(wind.second - previous.second))
            largestWind = maxOf(largestWind, abs(wind.first), abs(wind.second))
            previous = wind
        }
        println("HADLEY the wind's largest step between latitudes 0.005 degrees apart: %.4f m/s of %.2f".format(largestStep, largestWind))
        assertTrue(largestStep < 0.01 * largestWind, "the wind steps by $largestStep m/s between neighboring latitudes")
        for (edge in listOf(33.0, -26.0, circulation.northBelt.frontDegrees, -circulation.southBelt.frontDegrees)) {
            assertTrue(abs(circulation.windMps(edge).first) < 0.05, "the zonal wind at $edge is ${circulation.windMps(edge).first} m/s")
        }
        // The trades blow from the east in both cells away from the rising branch, and the winter
        // cell's air turns westerly once it has crossed the equator.
        assertTrue(circulation.windMps(-15.0).first < 0.0 && circulation.windMps(20.0).first < 0.0)
        assertTrue(circulation.windMps(5.0).first > 0.0, "no monsoon westerly at 5 N: ${circulation.windMps(5.0).first}")
    }

    @Test
    fun `the cells' edges stand where the troposphere turns unstable, nearer the equator under a steeper gradient`() {
        // A winter hemisphere twice as steep as the summer one: its troposphere is unstable nearer
        // the equator. The onset where `2 tan(phi) (dT/dphi) / (T N^2 H / g)` reaches one, for
        // `T = T_0 - A sin^2(phi)`, is near `sin^2(phi) = (T N^2 H / g) / (4 A)`.
        val zonal = syntheticClimate(northDrop = 30.0, southDrop = 60.0)
        val north = assertNotNull(HadleyCirculation.baroclinicOnsetDegrees(zonal, Season.JULY_HALF, north = true, fromDegrees = 1.0))
        val south = assertNotNull(HadleyCirculation.baroclinicOnsetDegrees(zonal, Season.JULY_HALF, north = false, fromDegrees = 1.0))
        println("HADLEY onsets under drops of 30 and 60 K: %.2f N and %.2f S".format(north, south))
        assertTrue(north > south + 5.0, "the gentler hemisphere's edge, $north, does not stand poleward of the steeper one's, $south")
        // Mirror images where the two hemispheres are alike.
        val even = syntheticClimate(northDrop = 45.0, southDrop = 45.0)
        val evenNorth = assertNotNull(HadleyCirculation.baroclinicOnsetDegrees(even, Season.JULY_HALF, north = true, fromDegrees = 1.0))
        val evenSouth = assertNotNull(HadleyCirculation.baroclinicOnsetDegrees(even, Season.JULY_HALF, north = false, fromDegrees = 1.0))
        assertEquals(evenNorth, evenSouth, 1e-3)
    }

    @Test
    fun `the cells rise where the band's air is warmest`() {
        val zonal = syntheticClimate(northDrop = 40.0, southDrop = 40.0, peakDegrees = 9.0)
        val equator = HadleyCirculation.energyFluxEquatorDegrees(zonal, Season.JULY_HALF)
        println("HADLEY energy flux equator of a band peaking at 9 N: %.2f".format(equator))
        assertEquals(9.0, equator, 0.5)
    }

    @Test
    fun `a heating near the pole holds the cells at the farthest that close`() {
        // A planet tilted far past Earth's has its summer pole warmest; the equal-area cells for a
        // heating there reach a pole first, and the circulation is held where they still close.
        for (cells in listOf(HadleyCirculation.cellsFor(85.0, EARTH_RADIUS_M), HadleyCirculation.cellsRisingAt(85.0, EARTH_RADIUS_M))) {
            println("HADLEY heating 85 N held: rises at %.1f, edges %.1f and %.1f".format(cells.ascentLatitudeDegrees, cells.winterEdgeDegrees, cells.summerEdgeDegrees))
            assertTrue(cells.winterEdgeDegrees > -90.0 && cells.summerEdgeDegrees < 90.0 && cells.ascentLatitudeDegrees > 0.0)
        }
    }

    @Test
    fun `a smaller planet's cells reach farther`() {
        // The thermal Rossby number goes as the inverse square of the radius at the same spin.
        val earth = HadleyCirculation.cellsFor(0.0, EARTH_RADIUS_M)
        val half = HadleyCirculation.cellsFor(0.0, EARTH_RADIUS_M / 2)
        println("HADLEY equinox edges: Earth %.1f, half Earth's radius %.1f".format(earth.northEdgeDegrees, half.northEdgeDegrees))
        assertTrue(half.northEdgeDegrees > earth.northEdgeDegrees)
    }

    /**
     * An energy balance whose every column, land and sea alike, is `27 - drop sin^2(phi - peak)` in
     * every season, with the northern hemisphere's drop [northDrop] and the southern's [southDrop].
     */
    private fun syntheticClimate(northDrop: Double, southDrop: Double, peakDegrees: Double = 0.0): ZonalClimate {
        val bands = 240
        val values = FloatArray(bands) { band ->
            val latitude = 90.0 - 180.0 * (band + 0.5) / bands
            val drop = if (latitude >= peakDegrees) northDrop else southDrop
            val distance = sin((latitude - peakDegrees) * DEGREES)
            (27.0 - drop * distance * distance).toFloat()
        }
        fun column() = ZonalColumn(values, values, values, values, values, values, values, values)
        return ZonalClimate(
            land = column(), sea = column(), water = column(), globalMeanC = 15f, spinUpResidualC = 0f,
            waterOpenShare = FloatArray(bands) { 1f }, waterOpenC = values, landShare = FloatArray(bands) { 0.3f }
        )
    }

    /** The boundary layer's greatest mass flux toward the rising branch in the winter cell and in the summer cell, kg/s. */
    private fun cellFluxes(heating: Double): Pair<Double, Double> {
        val circulation = HadleyCirculation.forHeating(heating, EARTH_RADIUS_M)
        var winter = 0.0
        var summer = 0.0
        for (latitude in sample(-circulation.southEdgeDegrees, circulation.northEdgeDegrees)) {
            val flux = abs(circulation.cellNorthwardMps(latitude)) * 2 * PI * EARTH_RADIUS_M * cos(latitude * DEGREES) *
                HadleyCirculation.LOWER_BRANCH_PA / 9.80665
            if (latitude < circulation.ascentLatitudeDegrees) winter = maxOf(winter, flux) else summer = maxOf(summer, flux)
        }
        return winter to summer
    }

    private fun sample(from: Double, to: Double, steps: Int = 2_000): List<Double> =
        (1 until steps).map { from + (to - from) * it / steps }
}
