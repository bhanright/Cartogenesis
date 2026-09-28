package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.OceanCirculation
import com.cartogenesis.worldgen.pipeline.OceanHeat
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ekman's transport and the water it draws up, on fixtures whose answers are known: the damped
 * Ekman balance `M = τ (r - i f) / (ρ (r² + f²))`, its divergence counted on the cells' faces with
 * nothing through a coast, and the mixed layer's steady state where the risen water renews it.
 */
class OceanUpwellingTest {

    private val seawaterDensity = 1025.0

    /** The offshore transport under an alongshore stress [stress] at [latitude], square meters a second. */
    private fun offshoreTransport(stress: Double, latitude: Double): Double {
        val f = (2.0 * com.cartogenesis.worldgen.model.WorldScale.ROTATION_RATE_PER_S * sin(latitude * PI / 180.0))
        val r = OceanStage.SURFACE_LAYER_FRICTION_PER_S
        return stress * abs(f) / (seawaterDensity * (r * r + f * f))
    }

    /**
     * A straight coast, meridional and at 30 degrees to the grid, with land to its east and an
     * equatorward stress along it: the water rising beside it, less the open ocean's own at the same
     * row, is the offshore Ekman transport per length of coast, `τ f / (ρ (r² + f²))`, which is
     * Ekman's `τ/(ρf)` to within `(r/f)²`. At 20 to 40 degrees north and south, at aspects 1.0 and
     * 0.5, and on a world twice the radius, where it must not move: nothing in it reads the radius.
     *
     * The bar is a thousandth, for the transport's change with latitude down the band, and for the
     * slanted coast one cell's width over the band's length of coast besides: a uniform transport's
     * flux through a staircase is its flux through the straight line between the staircase's ends,
     * whatever the steps, and those ends can each sit up to half a cell from where the straight
     * coast crosses the band's edges, so the alongshore component's flux through them is uncounted
     * or counted twice by at most a cell's width of coast. Without the upwelling term there is no
     * rising water at all, and a poleward stress sinks it instead (the next case).
     */
    @Test
    fun `an equatorward wind along a coast raises Ekman's offshore transport beside it`() {
        val failures = ArrayList<String>()
        for (widthKm in listOf(12_000.0, 24_000.0)) {
            for (aspect in listOf(1.0, 0.5)) {
                for (bearingDegrees in listOf(0.0, 30.0)) {
                    for (hemisphere in listOf(1.0, -1.0)) {
                        val (measured, expected, endStepShare) = coastalRise(widthKm, aspect, bearingDegrees, hemisphere, equatorward = true)
                        val bar = COAST_TOLERANCE + if (bearingDegrees == 0.0) 0.0 else endStepShare
                        println("UPWELLING coast at %2.0f degrees to the grid, %s, world %.0f km, aspect %.1f: %.4f m2/s per meter of coast against %.4f, off by %.2f%% against a bar of %.2f%%"
                            .format(bearingDegrees, if (hemisphere > 0) "north" else "south", widthKm, aspect, measured, expected, abs(measured / expected - 1) * 100, bar * 100))
                        if (abs(measured / expected - 1) > bar) {
                            failures += "bearing $bearingDegrees, hemisphere $hemisphere, world $widthKm, aspect $aspect: $measured against $expected"
                        }
                    }
                }
            }
        }
        // The control: a coast that passes the transport through it, as an ocean with no faces
        // shut at the land would, raises nothing beside it.
        val (throughCoast, expected, _) = coastalRise(12_000.0, 1.0, 0.0, 1.0, equatorward = true, coastShut = false)
        println("UPWELLING control, the transport let through the coast: %.4f m2/s per meter of coast against %.4f".format(throughCoast, expected))
        assertTrue(abs(throughCoast / expected - 1) > COAST_TOLERANCE, "a coast that passes the transport passed the bar")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * The risen water outcropped where Luyten, Pedlosky and Stommel's eastern-boundary geometry
     * puts its isopycnal: `sin φ_o = sin φ / (1 - D/H)`, 38.7 degrees beneath 30 for 100 m under a
     * 500 m thermocline, no further poleward than the subtropical gyre's edge at 45, the latitude's
     * own poleward of that edge, continuous across it, and the mirror image in the south. The
     * mixed layer's own latitude, the proxy the closure replaced, fails the first clause.
     */
    @Test
    fun `the risen water outcropped where the ventilated thermocline puts it`() {
        val expected = asin(sin(30.0 * PI / 180) / (1 - OceanStage.UPWELLING_SOURCE_DEPTH_M / 500.0)) * 180 / PI
        val measured = OceanStage.outcropLatitude(30f).toDouble()
        println("UPWELLING outcrop beneath 30 degrees: %.3f against %.3f; beneath -30 %.3f; beneath 44.9 %.3f, 45 %.3f, 60 %.3f, 0 %.3f"
            .format(measured, expected, OceanStage.outcropLatitude(-30f), OceanStage.outcropLatitude(44.9f), OceanStage.outcropLatitude(45f),
                OceanStage.outcropLatitude(60f), OceanStage.outcropLatitude(0f)))
        assertTrue(abs(30.0 - expected) > OUTCROP_TOLERANCE_DEGREES, "the mixed layer's own latitude passed the first clause")
        assertTrue(abs(measured - expected) < OUTCROP_TOLERANCE_DEGREES, "beneath 30 degrees the water outcropped at $measured, not $expected")
        assertTrue(abs(OceanStage.outcropLatitude(-30f) + measured) < OUTCROP_TOLERANCE_DEGREES, "the south is not the north's mirror image")
        assertTrue(abs(OceanStage.outcropLatitude(44.9f) - 45f) < OUTCROP_TOLERANCE_DEGREES, "the water beneath 44.9 degrees outcropped beyond the gyre")
        assertTrue(OceanStage.outcropLatitude(60f) == 60f, "the subpolar gyre's water is not its own latitude's")
        assertTrue(OceanStage.outcropLatitude(0f) == 0f, "the equator's water outcropped off the equator")
    }

    /** A poleward stress along the same coasts pushes the surface water onshore, and nothing rises. */
    @Test
    fun `a poleward wind along a coast raises nothing`() {
        for (bearingDegrees in listOf(0.0, 30.0)) {
            for (hemisphere in listOf(1.0, -1.0)) {
                val (measured, _) = coastalRise(12_000.0, 1.0, bearingDegrees, hemisphere, equatorward = false)
                println("UPWELLING poleward wind, coast at %2.0f degrees, %s: %.4f m2/s per meter of coast".format(bearingDegrees, if (hemisphere > 0) "north" else "south", measured))
                assertTrue(measured < 0.0, "a poleward wind raised $measured m2/s per meter of coast")
            }
        }
    }

    /**
     * The rise beside the coast over the band from 20 to 40 degrees in one hemisphere, net of the
     * open ocean's in each row, per meter of coast, and the analytic transport averaged the same way.
     */
    private fun coastalRise(widthKm: Double, aspect: Double, bearingDegrees: Double, hemisphere: Double, equatorward: Boolean, coastShut: Boolean = true): Triple<Double, Double, Double> {
        val scale = WorldScale(worldWidthKm = widthKm)
        val cellKm = 20.0 * widthKm / 12_000.0
        val across = (widthKm / cellKm).toInt()
        val down = (widthKm / 2 / (cellKm * aspect)).toInt()
        val widthMeters = widthKm * 1000 / across
        val heightMeters = widthKm * 500 / down
        val bearing = bearingDegrees * PI / 180
        // The coast runs through the middle column at 30 degrees of latitude, its bearing measured
        // from north toward east; land lies east of it.
        val middleRow = (0 until down).minByOrNull { abs(ClimateStage.latitudeOf(it, down) - 30.0 * hemisphere) }!!
        val isWater = BooleanArray(across * down) { cell ->
            val row = cell / across
            val column = cell % across
            val northMeters = (middleRow - row) * heightMeters
            val coastColumn = across / 2 + northMeters * tan(bearing) / widthMeters
            column + 0.5 < coastColumn
        }
        // Along the coast, pointing toward the equator (south in the north, north in the south).
        val towardEquator = -hemisphere
        val alongEast = sin(bearing) * towardEquator * (if (equatorward) 1.0 else -1.0)
        val alongNorth = cos(bearing) * towardEquator * (if (equatorward) 1.0 else -1.0)
        val stressEast = DoubleArray(across * down) { STRESS_N_PER_M2 * alongEast }
        val stressNorth = DoubleArray(across * down) { STRESS_N_PER_M2 * alongNorth }
        val faces = if (coastShut) isWater else BooleanArray(across * down) { true }
        val upward = OceanStage.upwellingMps(OceanStage.Stress(stressEast, stressNorth), faces, across, down, widthMeters, heightMeters)
        var risenPerSecond = 0.0
        var coastMeters = 0.0
        var expectedPerSecond = 0.0
        for (row in 0 until down) {
            val latitude = ClimateStage.latitudeOf(row, down).toDouble() * hemisphere
            if (latitude < 20.0 || latitude >= 40.0) continue
            val openOcean = upward[row * across + across / 8]
            var excess = 0.0
            for (column in 0 until across) {
                val cell = row * across + column
                if (!isWater[cell] || column < across / 4) continue
                excess += (upward[cell] - openOcean) * widthMeters * heightMeters
            }
            val rowCoastMeters = heightMeters / cos(bearing)
            risenPerSecond += excess
            coastMeters += rowCoastMeters
            expectedPerSecond += offshoreTransport(STRESS_N_PER_M2, latitude) * rowCoastMeters
        }
        return Triple(risenPerSecond / coastMeters, expectedPerSecond / coastMeters, widthMeters / coastMeters)
    }

    /**
     * On the equator an easterly stress drives the surface water poleward on both sides, and the
     * water rises between at `β τ (r² - f²) / (ρ (r² + f²)²)`: finite, `β τ / (ρ r²)` on the line
     * itself, where Ekman's undamped `τ/(ρf)` has no value. The grid is 4 km, a twentieth of the
     * equatorial layer `r/β`, where the face-centered difference is good to `(Δ/L)²/6`, under a
     * thousandth; the bar is 1%.
     */
    @Test
    fun `an easterly wind on the equator raises the damped Ekman divergence`() {
        val scale = WorldScale()
        val across = 3000
        val down = 1500
        val widthMeters = scale.worldWidthKm * 1000 / across
        val heightMeters = scale.worldWidthKm * 500 / down
        val stress = OceanStage.Stress(DoubleArray(across * down) { -STRESS_N_PER_M2 }, DoubleArray(across * down))
        val upward = OceanStage.upwellingMps(stress, BooleanArray(across * down) { true }, across, down, widthMeters, heightMeters)
        val beta = scale.planetaryVorticityGradientPerMeterSecond(0.0)
        val r = OceanStage.SURFACE_LAYER_FRICTION_PER_S
        for (row in listOf(down / 2 - 1, down / 2)) {
            val latitude = ClimateStage.latitudeOf(row, down).toDouble()
            val f = (2.0 * com.cartogenesis.worldgen.model.WorldScale.ROTATION_RATE_PER_S * sin(latitude * PI / 180.0))
            val expected = beta * STRESS_N_PER_M2 * (r * r - f * f) / (seawaterDensity * (r * r + f * f) * (r * r + f * f))
            val measured = upward[row * across].toDouble()
            println("UPWELLING equator, row at %.3f degrees: %.3e m/s (%.2f m/day) against %.3e".format(latitude, measured, measured * 86_400, expected))
            assertTrue(abs(measured / expected - 1) < EQUATOR_TOLERANCE, "at $latitude degrees the rise is $measured m/s against $expected")
        }
    }

    /**
     * A uniform patch of rising water settles at `(T_lat/τ + (w/h) T_sub) / (1/τ + w/h)`: the
     * relaxation to the latitude and the renewal by the risen water in proportion to their rates.
     * Without the entrainment it settles at the latitude's own temperature, and fails.
     */
    @Test
    fun `a patch of rising water settles between the latitude's temperature and the risen water's`() {
        val across = 64
        val down = 32
        val latitudeC = 20.0
        val risenC = 12.0
        val upwardMps = 2.0e-5
        val entrainment = FloatArray(across * down) { (upwardMps / OceanStage.MIXED_LAYER_DEPTH_M).toFloat() }
        fun settled(withEntrainment: Boolean): Double {
            val stencil = OceanHeat.stencil(
                across, down, 50_000.0, 50_000.0, BooleanArray(across * down) { true }, FloatArray(across * down),
                FloatArray(across * down) { latitudeC.toFloat() }, OceanStage.RELAXATION_SECONDS, withTarget = true,
                diffusivityAt = { QUIET_EDDIES_M2_PER_S },
                entrainmentPerS = if (withEntrainment) entrainment else null,
                subsurfaceC = FloatArray(across * down) { risenC.toFloat() }
            )
            val values = FloatArray(across * down) { latitudeC.toFloat() }
            OceanCirculation.relax(stencil, values, SETTLING_PASSES)
            return values[down / 2 * across + across / 2].toDouble()
        }
        val rate = 1.0 / OceanStage.RELAXATION_SECONDS
        val renewal = upwardMps / OceanStage.MIXED_LAYER_DEPTH_M
        val expected = (latitudeC * rate + renewal * risenC) / (rate + renewal)
        val measured = settled(withEntrainment = true)
        val control = settled(withEntrainment = false)
        println("UPWELLING patch: settles at %.4f C against %.4f; without the entrainment %.4f".format(measured, expected, control))
        assertTrue(abs(control - expected) > PATCH_TOLERANCE_C, "the control settled where the upwelling puts it")
        assertTrue(abs(measured - expected) < PATCH_TOLERANCE_C, "the patch settled at $measured C, not $expected")
    }

    /**
     * The risen water's temperature, the winter mixed layer where its isopycnal outcrops, is never
     * warmer than the annual water it replaces, at any latitude of a real world, so the term can only
     * cool. The warmest month read in its place fails.
     */
    @Test
    fun `the risen water is never warmer than the water it replaces`() {
        val config = WorldGenConfig(seed = 42L, width = 128, height = 128)
        val isLand = BooleanArray(128 * 128) { (it % 128) in 30 until 70 && (it / 128) in 20 until 100 }
        val sea = SeaLevelResult(0.5f, isLand, FloatField(128, 128), isLand.count { it })
        val zonal = ClimateStage.zonalClimate(config, sea)
        var warmest = Double.NEGATIVE_INFINITY
        var controlWarmest = Double.NEGATIVE_INFINITY
        var latitude = -89.5f
        while (latitude <= 89.5f) {
            val annual = zonal.waterC(latitude, Season.ANNUAL)
            warmest = maxOf(warmest, (OceanStage.subsurfaceTemperatureC(zonal, latitude) - annual).toDouble())
            controlWarmest = maxOf(controlWarmest, (zonal.waterC(latitude, Season.SUMMER) - annual).toDouble())
            latitude += 0.5f
        }
        println("UPWELLING risen water less the annual water: at most %+.3f C; the warmest month read instead, at most %+.3f C".format(warmest, controlWarmest))
        assertTrue(controlWarmest > 0.0, "the warmest month was no warmer than the year")
        assertTrue(warmest <= 0.0, "the risen water is ${warmest} C warmer than the water it replaces somewhere")
    }

    private companion object {
        /** A trade wind's stress, newtons a square meter: 7.5 m/s at the drag the ocean uses. */
        const val STRESS_N_PER_M2 = 0.083
        const val COAST_TOLERANCE = 1e-3
        const val EQUATOR_TOLERANCE = 0.01
        const val PATCH_TOLERANCE_C = 1e-3
        const val QUIET_EDDIES_M2_PER_S = 1.0
        const val SETTLING_PASSES = 50
        const val OUTCROP_TOLERANCE_DEGREES = 1e-3
    }
}
