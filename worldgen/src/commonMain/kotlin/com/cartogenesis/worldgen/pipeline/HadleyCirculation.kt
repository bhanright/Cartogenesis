package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.ClimateConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One calendar half-year's zonal-mean circulation at the surface: two Hadley cells about a rising
 * branch that follows the season's heating, the eddy-driven westerlies poleward of them and the
 * polar easterlies, as one wind in meters a second on each row of the map.
 *
 * **The Hadley cells** are Lindzen and Hou's (1988) for a heating centered off the equator, in the
 * form Hill, Bordoni and Mitchell (2019, J. Atmos. Sci. 76, read: their equations 6, 8 and 9 and
 * section 3c) state it:
 *
 * - the radiative-convective equilibrium of the troposphere's mean potential temperature,
 *   `theta_rce / theta_0 = 1 + (Delta_h / 3)(1 - 3 (sin phi - sin phi_m)^2)`, peaking at the heating's
 *   latitude `phi_m`;
 * - the angular-momentum-conserving circulation's own temperature about the latitude it rises at,
 *   `phi_a`, `(theta - theta_a) / theta_0 = -(Omega^2 a^2 / (2 g H)) (cos^2 phi_a - cos^2 phi)^2 / cos^2 phi`;
 * - the equal-area closure: the two temperatures meet at each cell's poleward edge, and each cell
 *   conserves energy, the integral of their difference over it zero.
 *
 * Four conditions fix `phi_a`, the winter cell's edge, the summer cell's edge and `theta_a`
 * ([EqualArea]). Off the equator they are strongly unequal: the rising branch stands poleward of the
 * heating, the cross-equatorial winter cell spans both sides of the equator and the summer cell
 * shrinks, while the two poleward edges move far less than the rising branch does. Solved here on
 * Earth's figures it gives Hill and others' own `phi_a` of 21.2 N for a heating at 6 N and their
 * 61.7 S to 44.8 N for one at 23.5 N (`HadleyCirculationTest`).
 *
 * **The heating's latitude** is the energy balance's own: where its half-year marine air is warmest
 * ([heatingLatitudeDegrees]). The marine column because the troposphere's mean temperature in the
 * tropics, which `theta_rce` stands for, is set by moist convection out of the sea's boundary layer,
 * whose maximum the rising branch follows (Hill and others' section 1, after Lindzen and Hou and
 * Emanuel 1995), and not by the land's hot dry ground.
 *
 * **Not counting the energy balance's transport twice.** The energy balance already carries the
 * zonal-mean heat poleward by its diffusion, and its temperatures are after that transport. Only the
 * heating's latitude is read from it. The cells' shape and inequality come from the equilibrium
 * contrast Lindzen and Hou impose, [EQUILIBRIUM_CONTRAST_SHARE], and their strength from the
 * atmosphere's radiative loss (below), neither from the energy balance's temperatures; and the heat
 * the cells move goes nowhere: nothing here feeds the energy balance, which keeps its transport as
 * the one account of the year's heat. What the circulation adds is the motion that transport
 * implies, its ascent, its descent and its surface winds.
 *
 * **The vertical motion** is the equal-area model's own thermodynamics in its shape: the air rises
 * where the troposphere is heated toward its equilibrium and sinks where it is cooled, `omega`
 * going as `-(theta_rce - theta) / (tau S)` with `tau` the free troposphere's thermal damping,
 * [WaveDamping.TING_YU_DAYS], and `S` the basic state's stability at mid-troposphere, so each cell's
 * ascent equals its descent by the equal-area closure. Its strength is the atmosphere's energy
 * budget and not the Newtonian relaxation's, which leaves Held and Hou's cells a fraction of Earth's
 * strength: on its mean over both cells' descent the sinking air is warmed by its descent as fast as
 * it cools by radiation, `omega = Q / S` (the radiative-subsidence balance, MIT 12.811 lecture 10
 * after Sobel and Bretherton 2000), at the atmosphere's mean radiative loss,
 * [ATMOSPHERE_RADIATIVE_LOSS_W_PER_M2]. One factor scales the whole field, so the shape and the two
 * cells' inequality are Lindzen and Hou's.
 *
 * **The lower branch** is the lower half of the troposphere, [LOWER_BRANCH_PA], the two models'
 * equatorward level: its continuity gives the surface's meridional wind, toward the rising branch from
 * both sides and zero at the cells' edges. The boundary layer reads that wind, and its convergence
 * over the boundary layer's own depth is the boundary layer's ascent (`BoundaryLayer`), a quarter of
 * the mid-troposphere's as the lower branch's linear profile of `omega` gives it; no other Hadley
 * vertical motion is added.
 *
 * **Poleward of the cells** the Ferrel and polar cells' surface legs are the belts' meridional legs
 * ([ClimateConfig.meridionalWindShare]) between each cell's edge and [SurfaceBelts.WESTERLY_BELT_EDGE_DEGREES]
 * and beyond it; the polar front does not migrate.
 *
 * **The surface's zonal wind** is the boundary layer's zonal momentum balance, `k u = f v + F`: the
 * surface's drag at the wind's own speed ([BoundaryLayer.surfaceDragPerSecond]) against the Coriolis
 * force on the meridional flow and the eddies' momentum forcing `F`. An axisymmetric model has no
 * eddies, and without them the surface wind at the equator, where `f` vanishes, is calm in every
 * season, where Earth's zonal-mean wind there is easterly all year. So `F` is the forcing that holds
 * Earth's annual zonal-mean surface wind ([SurfaceBelts.zonalShare] of [PressureWind.BELT_SPEED_MPS],
 * the profile the ocean's stress reads) against the two halves' mean meridional wind, taken the same
 * in both halves (docs/TODO.md): each half's zonal wind departs from Earth's annual one by the
 * Coriolis force on its own departure of meridional flow, easterly where the winter cell's air flows
 * toward the equator and westerly where it has crossed it, the summer monsoon's southwesterly.
 *
 * The sea-level pressure's zonal mean is this wind's drag balance ([BoundaryLayer.zonalPressurePa]):
 * a trough under the rising branch and ridges in the subtropics.
 */
internal class HadleyCirculation private constructor(
    /** The heating's latitude, `phi_m`, degrees. */
    val heatingLatitudeDegrees: Double,
    /** The cells' edges and the rising branch ([EqualArea]). */
    val cells: Cells,
    private val radiusMeters: Double,
    private val meridionalShare: Double
) {
    /** The latitude the two cells rise at, degrees. */
    val ascentLatitudeDegrees: Double get() = cells.ascentLatitudeDegrees

    /** The winter cell's and the summer cell's poleward edges, degrees. */
    val winterEdgeDegrees: Double get() = cells.winterEdgeDegrees
    val summerEdgeDegrees: Double get() = cells.summerEdgeDegrees

    /** The mid-troposphere vertical motion per unit of `(theta_rce - theta) / theta_0`, pascals a second. */
    private val omegaPerContrast: Double = -MID_TROPOSPHERE_KELVIN / (THERMAL_DAMPING_SECONDS * MID_TROPOSPHERE_STABILITY)

    /** The cells' sines, south edge to north edge, at [PROFILE_POINTS] points. */
    private val profileSines = DoubleArray(PROFILE_POINTS) { cells.lowSine + (cells.highSine - cells.lowSine) * it / (PROFILE_POINTS - 1) }

    /**
     * The vertical motion at each of [profileSines], pascals a second, positive down: the equal-area
     * model's own, rising where the troposphere is heated toward its equilibrium and sinking where it
     * is cooled, scaled so its mean descent is the radiative-subsidence balance's
     * ([RADIATIVE_SUBSIDENCE_PA_PER_S]). One factor on the whole field, so each cell's ascent still
     * equals its descent by the equal-area closure.
     */
    private val verticalMotion: DoubleArray = run {
        val relaxed = DoubleArray(PROFILE_POINTS) { omegaPerContrast * cells.contrast(profileSines[it]) }
        val sinking = DoubleArray(PROFILE_POINTS) { maxOf(0.0, relaxed[it]) }
        val sinkingShare = DoubleArray(PROFILE_POINTS) { if (relaxed[it] > 0.0) 1.0 else 0.0 }
        val meanDescent = integral(sinking) { true } / integral(sinkingShare) { true }
        DoubleArray(PROFILE_POINTS) { relaxed[it] * RADIATIVE_SUBSIDENCE_PA_PER_S / meanDescent }
    }

    /** The integral in the sine of [values] over the steps of [profileSines] whose middle [inside] takes, by the trapezoid rule. */
    private fun integral(values: DoubleArray, inside: (Double) -> Boolean): Double {
        var sum = 0.0
        for (point in 1 until PROFILE_POINTS) {
            val middle = 0.5 * (profileSines[point] + profileSines[point - 1])
            if (inside(middle)) sum += 0.5 * (values[point] + values[point - 1]) * (profileSines[point] - profileSines[point - 1])
        }
        return sum
    }

    /**
     * [verticalMotion]'s integral in the sine from the south edge to each of [profileSines], with the
     * trapezoid rule's remainder at the north edge spread evenly over the cells, so the flow closes
     * exactly.
     */
    private val risenFromSouth: DoubleArray = DoubleArray(PROFILE_POINTS).also { cumulative ->
        for (point in 1 until PROFILE_POINTS) {
            cumulative[point] = cumulative[point - 1] +
                0.5 * (verticalMotion[point] + verticalMotion[point - 1]) * (profileSines[point] - profileSines[point - 1])
        }
        val remainder = cumulative[PROFILE_POINTS - 1]
        for (point in 0 until PROFILE_POINTS) cumulative[point] -= remainder * point / (PROFILE_POINTS - 1)
    }

    /** [values] at the sine [sine] inside the cells, linearly between [profileSines]. */
    private fun interpolate(values: DoubleArray, sine: Double): Double {
        val position = (sine - cells.lowSine) / (cells.highSine - cells.lowSine) * (PROFILE_POINTS - 1)
        val below = position.toInt().coerceIn(0, PROFILE_POINTS - 2)
        val share = position - below
        return values[below] * (1.0 - share) + values[below + 1] * share
    }

    /**
     * The mid-troposphere vertical motion at [latitudeDegrees] inside the cells, pascals a second,
     * positive down; zero outside them.
     */
    fun verticalMotionPaPerSecond(latitudeDegrees: Double): Double {
        if (!inCells(latitudeDegrees)) return 0.0
        return interpolate(verticalMotion, sin(latitudeDegrees * DEGREES))
    }

    /**
     * The boundary layer's meridional wind of the cells at [latitudeDegrees], meters a second,
     * northward: continuity from the south edge, `v cos(phi) = (a / dp) int omega d(sin phi)`.
     */
    fun cellNorthwardMps(latitudeDegrees: Double): Double {
        if (!inCells(latitudeDegrees)) return 0.0
        val integral = interpolate(risenFromSouth, sin(latitudeDegrees * DEGREES))
        return radiusMeters / LOWER_BRANCH_PA * integral / cos(latitudeDegrees * DEGREES)
    }

    private fun inCells(latitudeDegrees: Double): Boolean {
        val sine = sin(latitudeDegrees * DEGREES)
        return sine > cells.lowSine && sine < cells.highSine
    }

    /**
     * The surface's meridional wind at [latitudeDegrees], meters a second, northward: the Hadley
     * cells' lower branch inside them, the Ferrel and polar cells' legs outside.
     */
    fun northwardMps(latitudeDegrees: Double): Double {
        if (inCells(latitudeDegrees)) return cellNorthwardMps(latitudeDegrees)
        val fromEquator = abs(latitudeDegrees)
        val edge = if (latitudeDegrees >= 0.0) cells.northEdgeDegrees else cells.southEdgeDegrees
        val poleward = if (latitudeDegrees >= 0.0) 1.0 else -1.0
        val legSpeed = meridionalShare * PressureWind.BELT_SPEED_MPS * PI / 2
        val front = SurfaceBelts.WESTERLY_BELT_EDGE_DEGREES.toDouble()
        return if (fromEquator < front && edge < front) {
            poleward * legSpeed * sin(PI * (fromEquator - edge) / (front - edge))
        } else {
            val polarEdge = maxOf(front, edge)
            -poleward * legSpeed * sin(PI * (fromEquator - polarEdge) / (POLE_DEGREES - polarEdge))
        }
    }

    /**
     * Lindzen and Hou's equal-area model for a heating at `sin phi_m` [heatingSine] at or north of the
     * equator, on a planet whose `Omega^2 a^2 / (2 g H)` is [amcScale] (the angular-momentum-conserving
     * temperature's own scale), with the equilibrium's contrast [contrastShare], `Delta_h`. Worked in
     * the sine of latitude, where every integral it asks is a closed form.
     */
    class EqualArea(val heatingSine: Double, val amcScale: Double, val contrastShare: Double) {

        /** `theta_rce / theta_0` at [sine]. */
        fun equilibrium(sine: Double): Double =
            1.0 + contrastShare / 3.0 - contrastShare * (sine - heatingSine) * (sine - heatingSine)

        /** Its integral in the sine from zero. */
        fun equilibriumIntegral(sine: Double): Double =
            (1.0 + contrastShare / 3.0) * sine - contrastShare * ((sine - heatingSine).pow(3) + heatingSine.pow(3)) / 3.0

        /** How far the circulated temperature at [sine] stands under its value at the rising branch [ascentSine], `/ theta_0`. */
        fun amcDrop(sine: Double, ascentSine: Double): Double {
            val gap = sine * sine - ascentSine * ascentSine
            return amcScale * gap * gap / (1.0 - sine * sine)
        }

        /**
         * [amcDrop]'s integral in the sine from zero: `(s^2 - c)^2 / (1 - s^2) = -(s^2 + 1 - 2c) +
         * (1 - c)^2 / (1 - s^2)`, with `c` the rising branch's sine squared.
         */
        fun amcDropIntegral(sine: Double, ascentSine: Double): Double {
            val squared = ascentSine * ascentSine
            val atanh = 0.5 * ln((1.0 + sine) / (1.0 - sine))
            return amcScale * (-sine.pow(3) / 3.0 - (1.0 - 2.0 * squared) * sine + (1.0 - squared) * (1.0 - squared) * atanh)
        }

        /**
         * The summer cell's edge and the rising branch's temperature, `theta_a / theta_0`, for a rising
         * branch at [ascentSine]: the edge where the two temperatures meet and the cell's energy,
         * `int (theta_amc - theta_rce)` from the rising branch, closes.
         */
        private fun summerCell(ascentSine: Double): Pair<Double, Double>? {
            fun residual(edge: Double): Double {
                val theta = equilibrium(edge) + amcDrop(edge, ascentSine)
                val amcIntegral = theta * (edge - ascentSine) - (amcDropIntegral(edge, ascentSine) - amcDropIntegral(ascentSine, ascentSine))
                return amcIntegral - (equilibriumIntegral(edge) - equilibriumIntegral(ascentSine))
            }
            val edge = firstRoot(ascentSine + SINE_STEP, LARGEST_SINE, ::residual) ?: return null
            return edge to equilibrium(edge) + amcDrop(edge, ascentSine)
        }

        /**
         * The winter cell's edge for a rising branch at [ascentSine] at temperature [theta]: moving out
         * toward the winter pole, the latitude at which the circulated temperature falls back under
         * the equilibrium's after standing over it.
         */
        private fun winterEdge(ascentSine: Double, theta: Double): Double? {
            fun excess(sine: Double) = theta - amcDrop(sine, ascentSine) - equilibrium(sine)
            var previous = ascentSine - SINE_STEP
            var previousExcess = excess(previous)
            while (previous - SINE_STEP > -LARGEST_SINE) {
                val next = previous - SINE_STEP
                val nextExcess = excess(next)
                if (previousExcess > 0.0 && nextExcess <= 0.0) return bisect(next, previous, ::excess)
                previous = next
                previousExcess = nextExcess
            }
            return null
        }

        /** The winter cell's energy, `int (theta_amc - theta_rce)` over it, for a rising branch at [ascentSine]. */
        private fun winterResidual(ascentSine: Double): Double? {
            val (_, theta) = summerCell(ascentSine) ?: return null
            val edge = winterEdge(ascentSine, theta) ?: return null
            val amcIntegral = theta * (ascentSine - edge) - (amcDropIntegral(ascentSine, ascentSine) - amcDropIntegral(edge, ascentSine))
            return amcIntegral - (equilibriumIntegral(ascentSine) - equilibriumIntegral(edge))
        }

        /**
         * The four conditions solved: the rising branch by bisection on the winter cell's energy, each
         * candidate's summer cell closed first; at the equator, Held and Hou's cells, mirror images
         * about it.
         */
        fun solve(): Cells = checkNotNull(solveOrNull()) { "no equal-area cells for a heating at ${asin(heatingSine) / DEGREES} degrees" }

        /** [solve], or null where the cells do not close short of the poles. */
        fun solveOrNull(): Cells? {
            require(heatingSine >= 0.0) { "a northern heating; mirror a southern one" }
            val ascent = if (heatingSine < SYMMETRIC_SINE) 0.0 else {
                var low = heatingSine + SINE_STEP
                var lowResidual = winterResidual(low)
                var found: Double? = null
                while (found == null && low + SINE_STEP < LARGEST_SINE) {
                    val high = low + SINE_STEP
                    val highResidual = winterResidual(high)
                    if (lowResidual != null && highResidual != null && sign(lowResidual) != sign(highResidual)) {
                        val fallback = lowResidual
                        found = bisect(low, high) { winterResidual(it) ?: fallback }
                    }
                    low = high
                    lowResidual = highResidual
                }
                found ?: return null
            }
            val (summer, theta) = summerCell(ascent) ?: return null
            val winter = if (ascent == 0.0) -summer else winterEdge(ascent, theta) ?: return null
            return Cells(this, ascent, theta, winter, summer, mirrored = false)
        }

        private fun firstRoot(from: Double, to: Double, function: (Double) -> Double): Double? {
            var previous = from
            var previousValue = function(previous)
            while (previous < to) {
                val next = minOf(previous + SINE_STEP, to)
                val nextValue = function(next)
                if (sign(previousValue) != sign(nextValue)) return bisect(previous, next, function)
                previous = next
                previousValue = nextValue
            }
            return null
        }

        private fun bisect(start: Double, end: Double, function: (Double) -> Double): Double {
            var low = start
            var high = end
            val lowSign = sign(function(low))
            repeat(BISECTIONS) {
                val middle = 0.5 * (low + high)
                if (sign(function(middle)) == lowSign) low = middle else high = middle
            }
            return 0.5 * (low + high)
        }
    }

    /**
     * The solved cells of [equalArea]: the rising branch's sine and temperature, `theta_a / theta_0`,
     * and the winter and summer edges' sines, for the northern heating it was solved for; [mirrored]
     * when the heating was southern, every latitude then the mirror image.
     */
    class Cells(
        val equalArea: EqualArea,
        private val ascentSineNorthern: Double,
        private val ascentTheta: Double,
        private val winterSineNorthern: Double,
        private val summerSineNorthern: Double,
        val mirrored: Boolean
    ) {
        private fun real(northernSine: Double) = if (mirrored) -northernSine else northernSine

        val ascentLatitudeDegrees: Double get() = asin(real(ascentSineNorthern)) / DEGREES
        val winterEdgeDegrees: Double get() = asin(real(winterSineNorthern)) / DEGREES
        val summerEdgeDegrees: Double get() = asin(real(summerSineNorthern)) / DEGREES

        /** The cells' northern and southern edges, degrees from the equator. */
        val northEdgeDegrees: Double get() = maxOf(winterEdgeDegrees, summerEdgeDegrees)
        val southEdgeDegrees: Double get() = -minOf(winterEdgeDegrees, summerEdgeDegrees)

        /** The sines of the southern and northern edges. */
        val lowSine: Double get() = minOf(real(winterSineNorthern), real(summerSineNorthern))
        val highSine: Double get() = maxOf(real(winterSineNorthern), real(summerSineNorthern))

        /** `(theta_rce - theta_amc) / theta_0` at the real [sine]: heating where positive. */
        fun contrast(sine: Double): Double {
            val northern = real(sine)
            return equalArea.equilibrium(northern) - (ascentTheta - equalArea.amcDrop(northern, ascentSineNorthern))
        }

        /** [contrast]'s integral over the real sines from [lowSine] to [sine]. */
        fun contrastFromSouthEdge(sine: Double): Double {
            fun northernIntegral(northern: Double) = equalArea.equilibriumIntegral(northern) - ascentTheta * northern +
                equalArea.amcDropIntegral(northern, ascentSineNorthern)
            // Mirrored, x -> -x turns the integral from the low edge to a point into one from minus
            // the point to minus that edge.
            return if (mirrored) northernIntegral(-lowSine) - northernIntegral(-sine) else northernIntegral(sine) - northernIntegral(lowSine)
        }

        fun mirrored(): Cells = Cells(equalArea, ascentSineNorthern, ascentTheta, winterSineNorthern, summerSineNorthern, !mirrored)
    }

    companion object {
        /**
         * The surface wind of the half-year [half] at [latitudeDegrees], meters a second, eastward and
         * northward, with [other] the other half: the meridional wind its own ([northwardMps]), the
         * zonal wind from the boundary layer's momentum balance `k u = f v + F`, `F` the eddies'
         * forcing that holds Earth's annual zonal-mean wind against the two halves' mean meridional
         * wind (see the class). `k = c sqrt(u^2 + v^2 + U_t^2)` makes `u` the root of a quadratic in
         * `u^2`, solved exactly.
         */
        fun windMps(half: HadleyCirculation, other: HadleyCirculation, latitudeDegrees: Double): Pair<Double, Double> {
            val north = half.northwardMps(latitudeDegrees)
            val meanNorth = 0.5 * (north + other.northwardMps(latitudeDegrees))
            val annualEast = PressureWind.BELT_SPEED_MPS * SurfaceBelts.zonalShare(latitudeDegrees.toFloat()).toDouble()
            val coriolis = PressureWind.coriolisParameter(latitudeDegrees.toFloat()).toDouble()
            val perSpeed = BoundaryLayer.dragPerSpeed(isLand = false)
            val gustsSquared = BoundaryLayer.TRANSIENT_WIND_MPS * BoundaryLayer.TRANSIENT_WIND_MPS
            val annualDrag = perSpeed * sqrt(annualEast * annualEast + meanNorth * meanNorth + gustsSquared)
            val eddyForcing = annualDrag * annualEast - coriolis * meanNorth
            val pull = (coriolis * north + eddyForcing) / perSpeed
            val rest = north * north + gustsSquared
            val squared = 2.0 * pull * pull / (rest + sqrt(rest * rest + 4.0 * pull * pull))
            return sign(pull) * sqrt(squared) to north
        }

        /** [windMps] on every one of [rows] rows of the map, eastward and southward, meters a second. */
        fun windOfRows(half: HadleyCirculation, other: HadleyCirculation, rows: Int): PressureWind.Vectors {
            val eastward = FloatArray(rows)
            val southward = FloatArray(rows)
            for (row in 0 until rows) {
                val (east, north) = windMps(half, other, ClimateStage.latitudeOf(row, rows).toDouble())
                eastward[row] = east.toFloat()
                southward[row] = (-north).toFloat()
            }
            return PressureWind.Vectors(eastward, southward)
        }

        private const val DEGREES = PI / 180.0
        private const val POLE_DEGREES = 90.0

        /**
         * Lindzen and Hou's imposed equator-to-pole contrast of the equilibrium temperature, as a
         * share of `theta_0`: a third, Held and Hou's figure, at which Hill and others read the
         * thermal Rossby number 0.15 on Earth with a 10 km tropopause.
         */
        const val EQUILIBRIUM_CONTRAST_SHARE = 1.0 / 3.0

        /**
         * The depth of the troposphere the cells fill, meters: the repository's 10 km tropopause
         * ([PressureWind.TROPOPAUSE_DEPTH_M]), Hill and others' `H` too.
         */
        val TROPOSPHERE_DEPTH_M: Double = PressureWind.TROPOPAUSE_DEPTH_M

        /** The relaxation time toward equilibrium: the free troposphere's thermal damping, Ting and Yu's 15 days. */
        val THERMAL_DAMPING_SECONDS: Double = WaveDamping.TING_YU_DAYS * WaveDamping.SECONDS_PER_DAY

        /**
         * The depth of the cells' lower branch, pascals: the lower half of the troposphere, Held and
         * Hou's and Lindzen and Hou's two levels, the equatorward flow at the surface and the poleward
         * at the tropopause, each standing for half the column's mass. The surface's meridional wind
         * is the lower half's mean, and the boundary layer's top, a quarter of the way up it, takes a
         * quarter of the mid-troposphere's vertical motion through the layer's own convergence.
         */
        val LOWER_BRANCH_PA: Double = AtmosphereLevels.REFERENCE_SURFACE_PRESSURE_PA / 2

        /** Points the cells' vertical motion is tabulated at between their edges: a few hundredths of a degree apart. */
        private const val PROFILE_POINTS = 4_001

        /** The mid-troposphere where the cells' vertical motion is read, pascals: 500 hPa. */
        private const val MID_TROPOSPHERE_PA = 50_000.0

        /** The basic state's mean temperature there, kelvin ([ZonalBasicState.JABLONOWSKI_WILLIAMSON_TEMPERATURE]). */
        val MID_TROPOSPHERE_KELVIN: Double = ZonalBasicState.JABLONOWSKI_WILLIAMSON_TEMPERATURE.kelvin(MID_TROPOSPHERE_PA)

        /** Its static stability there, `S = kappa T / p - dT/dp`, kelvin a pascal, as the wave model reads it. */
        val MID_TROPOSPHERE_STABILITY: Double = DryAir.KAPPA * MID_TROPOSPHERE_KELVIN / MID_TROPOSPHERE_PA -
            ZonalBasicState.JABLONOWSKI_WILLIAMSON_TEMPERATURE.kelvinPerPascal(MID_TROPOSPHERE_PA)

        /**
         * The atmosphere's mean radiative loss, watts a square meter: what the surface hands it as
         * latent and sensible heat and it radiates away, 80.0 and 17 (Trenberth, Fasullo and Kiehl
         * 2009, Table 2b, read).
         */
        const val ATMOSPHERE_RADIATIVE_LOSS_W_PER_M2 = 80.0 + 17.0

        /**
         * The descent that loss holds, pascals a second: the column's mean cooling, `L g / (c_p p_s)`,
         * 0.82 K a day, over the stability at mid-troposphere; about 11 hPa a day.
         */
        val RADIATIVE_SUBSIDENCE_PA_PER_S: Double = ATMOSPHERE_RADIATIVE_LOSS_W_PER_M2 * DryAir.GRAVITY_MPS2 /
            (DryAir.HEAT_CAPACITY_J_PER_KG_K * AtmosphereLevels.REFERENCE_SURFACE_PRESSURE_PA) / MID_TROPOSPHERE_STABILITY

        /** The step the edges are bracketed at in the sine of latitude, a thousandth (about 0.06 degrees). */
        private const val SINE_STEP = 1.0e-3

        /** The sine past which no edge is sought, about 87 degrees. */
        private const val LARGEST_SINE = 0.9986

        /** Below this heating sine (0.006 degrees) the cells are taken as the equator's. */
        private const val SYMMETRIC_SINE = 1.0e-4

        /** Halvings of a bracket: 60 take a thousandth of the sine to the double's last digits. */
        private const val BISECTIONS = 60

        /** `Omega^2 a^2 / (2 g H)` on a planet of [radiusMeters]. */
        fun amcScale(radiusMeters: Double): Double {
            val spin = WorldScale.ROTATION_RATE_PER_S.toDouble()
            return spin * spin * radiusMeters * radiusMeters / (2.0 * DryAir.GRAVITY_MPS2 * TROPOSPHERE_DEPTH_M)
        }

        /** The cells for a heating at [heatingLatitudeDegrees] on a planet of [radiusMeters]. */
        fun cellsFor(heatingLatitudeDegrees: Double, radiusMeters: Double): Cells {
            lastCells?.let { (heating, radius, cells) -> if (heating == heatingLatitudeDegrees && radius == radiusMeters) return cells }
            return solvedCells(heatingLatitudeDegrees, radiusMeters).also { lastCells = Triple(heatingLatitudeDegrees, radiusMeters, it) }
        }

        /**
         * The last cells solved and what they were solved for: a climate run asks for each half's
         * twice, the ocean's stress and the climate, and the cells are a pure function of the two.
         */
        @kotlin.concurrent.Volatile
        private var lastCells: Triple<Double, Double, Cells>? = null

        private fun solvedCells(heatingLatitudeDegrees: Double, radiusMeters: Double): Cells {
            val scale = amcScale(radiusMeters)
            fun cellsAt(degrees: Double) = EqualArea(sin(degrees * DEGREES), scale, EQUILIBRIUM_CONTRAST_SHARE).solveOrNull()
            val northern = cellsAt(abs(heatingLatitudeDegrees)) ?: run {
                // A heating so far from the equator (a planet tilted far past Earth's, its summer pole
                // the warmest) that the cells reach a pole first: held at the farthest heating whose
                // cells close, found by bisection, so the circulation stays defined at every tilt.
                var closes = 0.0
                var fails = abs(heatingLatitudeDegrees)
                repeat(FARTHEST_HEATING_BISECTIONS) {
                    val middle = 0.5 * (closes + fails)
                    if (cellsAt(middle) != null) closes = middle else fails = middle
                }
                checkNotNull(cellsAt(closes))
            }
            return if (heatingLatitudeDegrees < 0.0) northern.mirrored() else northern
        }

        /** Halvings of the search for the farthest heating whose cells close: twenty, about 1e-4 degrees. */
        private const val FARTHEST_HEATING_BISECTIONS = 20

        /**
         * The latitude the half-year [season]'s marine air is warmest at in [zonal], degrees: the
         * warmest band, refined by the parabola through it and its two neighbors.
         */
        fun heatingLatitudeDegrees(zonal: ZonalClimate, season: Season): Double {
            val marine = zonal.sea.at(season)
            var warmest = 0
            for (band in marine.indices) if (marine[band] > marine[warmest]) warmest = band
            val latitude = EnergyBalance.latitudeOfBand(warmest).toDouble()
            if (warmest == 0 || warmest == marine.size - 1) return latitude
            val north = marine[warmest - 1].toDouble()
            val here = marine[warmest].toDouble()
            val south = marine[warmest + 1].toDouble()
            val curvature = north - 2.0 * here + south
            if (curvature >= 0.0) return latitude
            // The parabola's vertex, in bands toward the south; bands run north to south, so a step
            // toward the south is a fall in latitude.
            val southwardBands = 0.5 * (north - south) / curvature
            return latitude - southwardBands * EnergyBalance.POLE_TO_POLE_DEGREES / marine.size
        }

        /** [season]'s circulation over the world [config] describes, under the energy balance [zonal]. */
        fun forHalf(config: WorldGenConfig, zonal: ZonalClimate, season: Season): HadleyCirculation =
            forHeating(heatingLatitudeDegrees(zonal, season), config.scale.radiusMeters, config.climate.meridionalWindShare.toDouble())

        /**
         * The circulation for a heating at [heatingLatitudeDegrees] on a planet of [radiusMeters], the
         * eddy-driven belts' meridional legs at [meridionalShare] of their speed.
         */
        fun forHeating(
            heatingLatitudeDegrees: Double,
            radiusMeters: Double,
            meridionalShare: Double = ClimateConfig().meridionalWindShare.toDouble()
        ): HadleyCirculation =
            HadleyCirculation(heatingLatitudeDegrees, cellsFor(heatingLatitudeDegrees, radiusMeters), radiusMeters, meridionalShare)
    }
}
