package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.OceanCirculation
import com.cartogenesis.worldgen.pipeline.OceanStencil
import com.cartogenesis.worldgen.pipeline.PressureResponse
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The solved pressure against what its equations say, on fixtures whose answers are known.
 *
 * The response to a forcing on a β-plane is carried west by the long Rossby wave's drift and spread
 * by the layer's mass adjustment, and both are checked here against analytic figures: the
 * one-dimensional reaches east and west of a forced block, the roots of `K λ² - V λ - α = 0`
 * ([PressureResponse.reachesMeters]); and the displacement of a response's centroid from its
 * forcing's, which for this operator is exactly the forcing-weighted drift over α. Each is also
 * held at twice the planet's radius, where β halves and the formula says what the reaches become.
 */
class PressureResponseTest {

    private val relax: (OceanStencil, FloatArray, Int) -> FloatArray = { stencil, values, passes ->
        OceanCirculation.relax(stencil, values, passes); values
    }

    /**
     * A channel of [rows] rows spanning [heightKm] about [centerDegrees], [lengthKm] long and
     * periodic, all sea, whose `f` is the sphere's at each row's own latitude on a planet of [scale].
     */
    private class Channel(val across: Int, val down: Int, val widthMeters: Double, val heightMeters: Double, val coriolisAt: (Double) -> Double)

    private fun channel(scale: WorldScale, centerDegrees: Double, heightKm: Double, rows: Int, lengthKm: Double, cellKm: Double): Channel {
        val across = (lengthKm / cellKm).toInt().let { it + (it and 1) }
        val degreesSpanned = heightKm * 1000 / scale.metersPerDegreeLatitude
        val northEdge = centerDegrees + degreesSpanned / 2
        return Channel(across, rows, lengthKm * 1000 / across, heightKm * 1000 / rows) { share ->
            PressureResponse.coriolisPerS(northEdge - degreesSpanned * share)
        }
    }

    /**
     * Along a row of a narrow channel at 30 degrees, west of a forced block and east of it, the
     * response decays at the analytic reaches, on this generator's world and on one twice its
     * radius.
     *
     * The channel is 100 km across, four rows, so the rows share one latitude's drift to a few
     * parts in a thousand; its block is forced for half of a 24,000 km length, so each edge is a
     * dozen reaches from the other. Each reach is read off the logarithm of the response between
     * 300 and 1,500 km past the edge. The bar is 2%: the fitted fluxes are exact along the drift at
     * any spacing, and the relaxation's share reads a length short by `(Δ/L)²/24`, a hundredth of a
     * percent at the 50 km spacing here; the rest is the row-to-row spread of the drift. A drift
     * taken away, a channel on an `f`-plane, reads one reach both ways, which is the local balance
     * with isotropic smoothing this replaced, and fails the western clause by more than a third.
     */
    @Test
    fun `a forced strip decays at the analytic reaches on both sides, at two radii`() {
        val failures = ArrayList<String>()
        for (widthKm in listOf(12_000.0, 24_000.0)) {
            val scale = WorldScale(worldWidthKm = widthKm)
            val (westExpected, eastExpected) = PressureResponse.reachesMeters(STRIP_DEGREES, PressureResponse.SEA_DRAG_PER_S, scale)
            val measured = stripReaches(channel(scale, STRIP_DEGREES, 100.0, 4, 24_000.0, 50.0))
            println("PRESSURE strip at ${STRIP_DEGREES.toInt()} degrees, world ${widthKm.toInt()} km: west reach %.0f km against %.0f, east %.0f km against %.0f"
                .format(measured.first / 1000, westExpected / 1000, measured.second / 1000, eastExpected / 1000))
            if (abs(measured.first / westExpected - 1) > STRIP_TOLERANCE) failures += "world ${widthKm.toInt()} km: west reach ${measured.first / 1000} km against ${westExpected / 1000}"
            if (abs(measured.second / eastExpected - 1) > STRIP_TOLERANCE) failures += "world ${widthKm.toInt()} km: east reach ${measured.second / 1000} km against ${eastExpected / 1000}"
        }
        val scale = WorldScale()
        val plane = channel(scale, STRIP_DEGREES, 100.0, 4, 24_000.0, 50.0).let { strip ->
            val fixed = PressureResponse.coriolisPerS(STRIP_DEGREES)
            Channel(strip.across, strip.down, strip.widthMeters, strip.heightMeters) { fixed }
        }
        val (westOnPlane, eastOnPlane) = stripReaches(plane)
        val (westExpected, _) = PressureResponse.reachesMeters(STRIP_DEGREES, PressureResponse.SEA_DRAG_PER_S, scale)
        println("PRESSURE control, an f-plane with no drift: west reach %.0f km, east %.0f km, against a western %.0f with the drift".format(westOnPlane / 1000, eastOnPlane / 1000, westExpected / 1000))
        assertTrue(abs(westOnPlane / westExpected - 1) > STRIP_TOLERANCE, "the f-plane control passed the western clause")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** The reaches west of the forced block's western edge and east of its eastern edge, meters. */
    private fun stripReaches(strip: Channel): Pair<Double, Double> {
        val half = strip.across / 2
        val forcing = DoubleArray(strip.across * strip.down) { cell -> if (cell % strip.across < half) FORCING_M2_PER_S2 else 0.0 }
        val response = PressureResponse.solveOn(
            strip.across, strip.down, strip.widthMeters, strip.heightMeters, FloatArray(forcing.size), forcing,
            strip.coriolisAt, removeRowMeans = false, relax = relax
        )
        fun columnMean(column: Int): Double {
            val wrapped = (column % strip.across + strip.across) % strip.across
            return (0 until strip.down).sumOf { response.geopotentialM2PerS2[it * strip.across + wrapped].toDouble() } / strip.down
        }
        val near = (NEAR_KM * 1000 / strip.widthMeters).toInt()
        val far = (FAR_KM * 1000 / strip.widthMeters).toInt()
        // West of the western edge, column 0, is the unforced half's eastern end.
        val westNear = columnMean(-near)
        val westFar = columnMean(-far)
        val westReach = (far - near) * strip.widthMeters / ln(westNear / westFar)
        val eastNear = columnMean(half - 1 + near)
        val eastFar = columnMean(half - 1 + far)
        val eastReach = (far - near) * strip.widthMeters / ln(eastNear / eastFar)
        return westReach to eastReach
    }

    /**
     * A cooled disc's high lies west of it, by the drift over α: on this operator the displacement
     * of the response's centroid from the forcing's is the response-weighted drift over α, exactly,
     * for any diffusivity, since the diffusion moves no centroid on a periodic channel with walls
     * that pass nothing.
     *
     * The disc, 300 km across, sits at 45 degrees in a channel from 30 to 60 degrees, 24,000 km
     * long, on this world and on one twice its radius, at aspects 1.0 and 0.5. The bar is 3%, the
     * scheme's discretization error at a 50 km spacing read off the strip above with room for the
     * meridional spread the strip does not have. Today's local balance with isotropic smoothing,
     * three box passes over the same disc, moves the centroid nowhere and fails it.
     */
    @Test
    fun `a cooled disc's response lies west of it by the drift over alpha, at two radii and two aspects`() {
        val failures = ArrayList<String>()
        for (widthKm in listOf(12_000.0, 24_000.0)) {
            val scale = WorldScale(worldWidthKm = widthKm)
            for (aspect in listOf(1.0, 0.5)) {
                val heightKm = 30 * scale.metersPerDegreeLatitude / 1000
                val cellKm = 50.0
                val rows = (heightKm / (cellKm * aspect)).toInt()
                val box = channel(scale, DISC_DEGREES, heightKm, rows, 24_000.0, cellKm)
                val forcing = disc(box)
                val response = PressureResponse.solveOn(
                    box.across, box.down, box.widthMeters, box.heightMeters, FloatArray(forcing.size), forcing,
                    box.coriolisAt, removeRowMeans = false, relax = relax
                )
                val phi = DoubleArray(forcing.size) { response.geopotentialM2PerS2[it].toDouble() }
                val shift = centroidX(box, phi) - centroidX(box, forcing)
                var weightedDrift = 0.0
                var total = 0.0
                for (row in 0 until box.down) {
                    val latitude = DISC_DEGREES + 15.0 - 30.0 * (row + 0.5) / box.down
                    val drift = PressureResponse.eastwardDriftMps(latitude, PressureResponse.SEA_DRAG_PER_S, scale)
                    for (column in 0 until box.across) {
                        weightedDrift += drift * phi[row * box.across + column]
                        total += phi[row * box.across + column]
                    }
                }
                val expected = weightedDrift / total / PressureResponse.THERMAL_RELAXATION_PER_S
                val atCenter = PressureResponse.eastwardDriftMps(DISC_DEGREES, PressureResponse.SEA_DRAG_PER_S, scale) / PressureResponse.THERMAL_RELAXATION_PER_S
                println("PRESSURE disc at ${DISC_DEGREES.toInt()} degrees, world ${widthKm.toInt()} km, aspect $aspect: centroid moved %.0f km against %.0f km (the drift at the disc's own latitude over alpha: %.0f km)"
                    .format(shift / 1000, expected / 1000, atCenter / 1000))
                if (!(shift < 0) || abs(shift / expected - 1) > DISC_TOLERANCE) failures += "world ${widthKm.toInt()} km aspect $aspect: moved ${shift / 1000} km against ${expected / 1000}"
            }
        }
        val scale = WorldScale()
        val box = channel(scale, DISC_DEGREES, 30 * scale.metersPerDegreeLatitude / 1000, 20, 24_000.0, 50.0)
        val forcing = disc(box)
        val blurred = FloatField(box.across, box.down).also { field -> for (i in forcing.indices) field.data[i] = forcing[i].toFloat() }
        val radiusCells = (PressureWindSmoothingKm / 50.0).toInt()
        BoxBlur.apply(blurred, radiusAcross = radiusCells, radiusDown = radiusCells, passes = BoxBlur.PASSES_FOR_GAUSSIAN)
        val controlShift = centroidX(box, DoubleArray(forcing.size) { blurred.data[it].toDouble() }) - centroidX(box, forcing)
        println("PRESSURE control, today's smoothing of the same disc: centroid moved %.0f km".format(controlShift / 1000))
        assertTrue(abs(controlShift) < abs(PressureResponse.eastwardDriftMps(DISC_DEGREES, PressureResponse.SEA_DRAG_PER_S, scale) / PressureResponse.THERMAL_RELAXATION_PER_S) * (1 - DISC_TOLERANCE),
            "today's smoothing passed the disc's bar")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun disc(box: Channel): DoubleArray {
        val centerColumn = box.across / 2
        val centerRow = box.down / 2
        return DoubleArray(box.across * box.down) { cell ->
            val dx = (cell % box.across - centerColumn + 0.5) * box.widthMeters
            val dy = (cell / box.across - centerRow + 0.5) * box.heightMeters
            if (sqrt(dx * dx + dy * dy) <= DISC_RADIUS_M) FORCING_M2_PER_S2 else 0.0
        }
    }

    private fun centroidX(box: Channel, field: DoubleArray): Double {
        var moment = 0.0
        var total = 0.0
        for (cell in field.indices) {
            moment += (cell % box.across) * box.widthMeters * field[cell]
            total += field[cell]
        }
        return moment / total
    }

    /**
     * The departure carries no zonal mean: each row's mean of the solved field is zero to its
     * rounding once taken out, on a globe with land, where a drag that differs between land and
     * sea leaves a small one in the field as solved. Solved with the removal off, the rows keep
     * that mean, which is the control.
     */
    @Test
    fun `the departure has no zonal mean`() {
        val scale = WorldScale()
        val (across, down) = PressureResponse.solveGrid(scale)
        val widthMeters = scale.worldWidthKm * 1000 / across
        val heightMeters = scale.worldWidthKm * 500 / down
        // Two continents, one straddling the tropics and one at high northern latitudes, warmer than
        // the sea by a zonal-mean-free forcing: each row's mean is taken out of the forcing first.
        val land = FloatArray(across * down) { cell ->
            val column = cell % across
            val row = cell / across
            if ((column in across / 8 until across * 3 / 8 && row in down / 4 until down * 3 / 4) ||
                (column in across / 2 until across * 3 / 4 && row in down / 8 until down / 3)) 1f else 0f
        }
        val forcing = DoubleArray(across * down) { -2_000.0 * land[it] }
        for (row in 0 until down) {
            val mean = (0 until across).sumOf { forcing[row * across + it] } / across
            for (column in 0 until across) forcing[row * across + column] -= mean
        }
        val kept = PressureResponse.solveOn(across, down, widthMeters, heightMeters, land, forcing, removeRowMeans = true, relax = relax)
        val raw = PressureResponse.solveOn(across, down, widthMeters, heightMeters, land, forcing, removeRowMeans = false, relax = relax)
        fun worstRowMeanShare(field: FloatArray): Double {
            var worst = 0.0
            val largest = field.maxOf { abs(it) }.toDouble()
            for (row in 0 until down) worst = maxOf(worst, abs((0 until across).sumOf { field[row * across + it].toDouble() } / across))
            return worst / largest
        }
        println("PRESSURE zonal mean: %.2e of the largest departure after, %.4f as solved".format(worstRowMeanShare(kept.geopotentialM2PerS2), raw.solvedRowMeanShare))
        assertTrue(worstRowMeanShare(raw.geopotentialM2PerS2) > ROW_MEAN_ROUNDING, "the control's rows have no mean to remove")
        assertTrue(worstRowMeanShare(kept.geopotentialM2PerS2) <= ROW_MEAN_ROUNDING, "a row keeps a mean of ${worstRowMeanShare(kept.geopotentialM2PerS2)} of the largest departure")
    }

    private companion object {
        const val STRIP_DEGREES = 30.0
        const val DISC_DEGREES = 45.0
        const val DISC_RADIUS_M = 150_000.0
        const val FORCING_M2_PER_S2 = -1_000.0
        const val NEAR_KM = 300.0
        const val FAR_KM = 1_500.0
        const val STRIP_TOLERANCE = 0.02
        const val DISC_TOLERANCE = 0.03

        /** Today's smoothing length, `N H / f` at 45 degrees, for the control. */
        val PressureWindSmoothingKm: Double = com.cartogenesis.worldgen.pipeline.PressureWind.rossbyRadiusKm()

        /** A float's rounding over a row of a hundred-odd values, as a share of the largest. */
        const val ROW_MEAN_ROUNDING = 1e-6
    }
}
