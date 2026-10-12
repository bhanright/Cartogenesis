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
import kotlin.math.tan

/**
 * One calendar half-year's zonal-mean circulation at the surface: two Hadley cells heated about the
 * season's energy flux equator, the eddy-driven westerlies poleward of them and the polar
 * easterlies, as one wind in meters a second on each row of the map.
 *
 * **Where the cells are heated.** About the energy balance's energy flux equator for the half
 * ([energyFluxEquatorDegrees]): the latitude where its meridional transport, the band's mean air
 * carried down its own gradient, changes direction. A deep overturning circulation carries energy
 * in the direction of its upper branches, away from its ascent, so the ITCZ "can be expected to lie
 * near the energy flux equator, where the atmospheric meridional energy flux F changes sign"
 * (Schneider, Bischoff and Haug 2014, Nature 513, read). The energy balance's tropical diffusivity
 * stands for that circulation's transport (`EnergyBalance.DIFFUSION_TROPICS_W_PER_M2_C`), so its zero
 * is the circulation's heating's latitude. It is the band's air, land and sea by their shares, and not the marine
 * column alone, because the transport moves the band's heat: over the summer continents the band is
 * warmest well poleward of the sea's warmest row (docs/DESIGN_LEDGER.md, A1-6b).
 *
 * **The cells' shape** is Lindzen and Hou's (1988) for that heating, in the form Hill, Bordoni and
 * Mitchell (2019, J. Atmos. Sci. 76, read: their equations 6, 8 and 9 and section 3c) state it: the
 * radiative-convective equilibrium `theta_rce / theta_0 = 1 + (Delta_h / 3)(1 - 3 (sin phi -
 * sin phi_m)^2)` peaking at the heating's latitude `phi_m`, and the angular-momentum-conserving
 * circulation's own temperature about its rising latitude `phi_a`, `(theta - theta_a) / theta_0 =
 * -(Omega^2 a^2 / (2 g H)) (cos^2 phi_a - cos^2 phi)^2 / cos^2 phi` ([EqualArea]). The circulation
 * parts where the latitude-by-latitude equilibrium stops being able to hold without a circulation,
 * Hill, Bordoni and Mitchell's (2021, J. Atmos. Sci. 78, read) supercritical forcing extent for that
 * heating ([EqualArea.supercriticalSine]): the equal-area model's own closure for it is "predictive
 * but inaccurate over much of the relevant parameter space" (the same), putting it far poleward,
 * where Earth's solstitial ascending edge sits "around 15 latitude in the summer hemisphere" and
 * the ITCZ equatorward of it. The summer cell keeps Lindzen and Hou's energy closure, which sets
 * `theta_a`, and the winter cell's edge their continuity of temperature ([EqualArea.risingAt]); the
 * strongest ascent then stands just equatorward of the heating, inside the winter cell.
 *
 * **The cells' edges** are where baroclinic eddies take over ([baroclinicOnsetDegrees]): the poleward
 * extent of the Hadley cell "is set by the location where the thermally driven jet first becomes
 * baroclinically unstable" (Lu, Vecchi and Reichler 2007, Geophys. Res. Lett. 34, read), by Held's
 * (2000, the Woods Hole lectures, read) two-layer criterion, here read on the half's own temperature
 * gradient. Winter's steep gradient makes the troposphere unstable near the subtropics and summer's
 * weak one far poleward of them, so the summer cell reaches farther than the winter one, as Earth's
 * eddy-driven summer cell does ("the summer HC is largely driven by eddies", Ceppi and Hartmann
 * 2013, J. Climate 26, read), where Lindzen and Hou's own summer edge stands equatorward of their
 * winter edge. Each cell keeps its axisymmetric profile, carried in the sine of latitude from its
 * own equal-area edge to this one, and its descent is scaled so what rises in it sinks in it.
 *
 * **The vertical motion** is that profile, `omega` going as `-(theta_rce - theta) / (tau S)` with
 * `tau` the free troposphere's thermal damping, [WaveDamping.TING_YU_DAYS], and `S` the basic state's
 * stability at mid-troposphere. Its strength is the atmosphere's energy budget: on its mean over the
 * cells' descent the sinking air is warmed by its descent as fast as it cools by radiation,
 * `omega = Q / S` (the radiative-subsidence balance, MIT 12.811 lecture 10 after Sobel and
 * Bretherton 2000), at the atmosphere's mean radiative loss, [ATMOSPHERE_RADIATIVE_LOSS_W_PER_M2].
 *
 * **The lower branch is the boundary layer.** "The mass transports in the surface branches of these
 * atmospheric meridional circulations are simply the boundary layer Ekman mass transports that
 * balance the surface stresses" (Held 2000, lecture 1), so the cells' equatorward mass flux runs
 * through the boundary layer's own depth, [LOWER_BRANCH_PA]: its continuity gives the surface's
 * meridional wind, and the boundary layer's convergence carries the cells' whole ascent at its top.
 *
 * **The zonal wind in the cells** is the boundary layer's zonal momentum balance, the Coriolis force
 * on the meridional flow and that flow's own advection of zonal momentum against the surface's drag
 * at the wind's own speed, `(v / a) du/dphi = (f + u tan(phi) / a) v - k u`, marched along the flow
 * from each cell's edge, where the air is still, to the rising branch ([cellEastward]). The trades
 * are the Coriolis force on the inflow; the air crossing the equator carries its easterly momentum
 * with it and turns westerly beyond, the summer monsoon's southwesterly.
 *
 * **Poleward of the cells** the surface wind is the eddies' ([Belt]). "Understanding the
 * distribution of surface winds and surface stresses is equivalent (outside of the deep tropics) to
 * understanding the convergence of the eddy fluxes of angular momentum" (Held 2000, lecture 1), and
 * the eddies converge westerly momentum into the baroclinic zone they grow in: a westerly belt from
 * the cell's edge, where the surface wind turns, to the polar front, with Earth's annual belts' width
 * ([SurfaceBelts]), and Earth's polar easterlies beyond it. Its strength is the hemisphere's
 * angular-momentum balance: what the trades take from the ground the westerlies give back, the
 * surface's torque over the hemisphere zero ([westerlyPeakMps]). The Ferrel and polar cells' legs
 * are the slope [ClimateConfig.meridionalWindShare] on that wind, poleward under the westerlies and
 * back under the polar easterlies, so the polar front moves with the jet and the meridional wind
 * passes through zero at it.
 *
 * The sea-level pressure's zonal mean is this wind's drag balance ([BoundaryLayer.zonalPressurePa]):
 * a trough under the rising branch, ridges where the trades meet the westerlies at the cells' edges,
 * and the subpolar trough at the polar front.
 *
 * **At each longitude** the cells rise at the longitude's own energy flux equator, carried from
 * these ([LocalCirculation]); this is the zonal circulation they are carried from.
 *
 * **Not counting the energy balance's transport twice.** The energy balance carries the zonal-mean
 * heat poleward by its diffusion and its temperatures are after that transport. The circulation
 * reads from them only where the transport parts and where the gradient it leaves turns the
 * troposphere unstable, and feeds nothing back.
 */
internal class HadleyCirculation private constructor(
    /** The energy flux equator, `phi_m`, degrees: the heating's latitude. */
    val heatingLatitudeDegrees: Double,
    /** The axisymmetric cells for the heating ([EqualArea.risingAt]), whose profile each cell carries. */
    val cells: Cells,
    /** The cells' northern edge, degrees north, where baroclinic eddies take over ([baroclinicOnsetDegrees]). */
    val northEdgeDegrees: Double,
    /** The cells' southern edge, degrees south, positive. */
    val southEdgeDegrees: Double,
    private val radiusMeters: Double,
    private val meridionalShare: Double
) {
    /** The latitude the two cells rise at, degrees. */
    val ascentLatitudeDegrees: Double get() = cells.ascentLatitudeDegrees

    /** The winter cell's and the summer cell's poleward edges, degrees, signed: the winter one opposite the heating. */
    val winterEdgeDegrees: Double get() = if (heatingLatitudeDegrees >= 0.0) -southEdgeDegrees else northEdgeDegrees
    val summerEdgeDegrees: Double get() = if (heatingLatitudeDegrees >= 0.0) northEdgeDegrees else -southEdgeDegrees

    /** The mid-troposphere vertical motion per unit of `(theta_rce - theta) / theta_0`, pascals a second. */
    private val omegaPerContrast: Double = -MID_TROPOSPHERE_KELVIN / (THERMAL_DAMPING_SECONDS * MID_TROPOSPHERE_STABILITY)

    private val lowSine = -sin(southEdgeDegrees * DEGREES)
    private val highSine = sin(northEdgeDegrees * DEGREES)
    private val ascentSine = sin(ascentLatitudeDegrees * DEGREES)

    /** The cells' sines, south edge to north edge, at [PROFILE_POINTS] points. */
    private val profileSines = DoubleArray(PROFILE_POINTS) { lowSine + (highSine - lowSine) * it / (PROFILE_POINTS - 1) }

    /**
     * The axisymmetric cells' sine a sine of these cells stands for: each cell's profile carried
     * linearly in the sine from its own equal-area edge to this one's, the rising branch held.
     */
    private fun axisymmetricSine(sine: Double): Double =
        if (sine >= ascentSine) ascentSine + (sine - ascentSine) * (cells.highSine - ascentSine) / (highSine - ascentSine)
        else ascentSine - (ascentSine - sine) * (ascentSine - cells.lowSine) / (ascentSine - lowSine)

    /**
     * The vertical motion at each of [profileSines], pascals a second, positive down: the equal-area
     * model's own, rising where the troposphere is heated toward its equilibrium and sinking where it
     * is cooled, each cell's descent scaled so the cell closes, and the whole scaled so its mean
     * descent is the radiative-subsidence balance's ([RADIATIVE_SUBSIDENCE_PA_PER_S]).
     */
    private val verticalMotion: DoubleArray = run {
        val relaxed = DoubleArray(PROFILE_POINTS) { omegaPerContrast * cells.contrast(axisymmetricSine(profileSines[it])) }
        for (northCell in listOf(false, true)) {
            val inCell: (Double) -> Boolean = { sine -> if (northCell) sine > ascentSine else sine < ascentSine }
            val rising = integral(DoubleArray(PROFILE_POINTS) { minOf(0.0, relaxed[it]) }, inCell)
            val sinking = integral(DoubleArray(PROFILE_POINTS) { maxOf(0.0, relaxed[it]) }, inCell)
            if (sinking <= 0.0) continue
            val closing = -rising / sinking
            for (point in 0 until PROFILE_POINTS) if (inCell(profileSines[point]) && relaxed[point] > 0.0) relaxed[point] *= closing
        }
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

    /** The boundary layer's meridional wind at each of [profileSines], meters a second, northward. */
    private val cellNorthward = DoubleArray(PROFILE_POINTS) { point ->
        val cosine = sqrt(1.0 - profileSines[point] * profileSines[point])
        if (cosine <= 0.0) 0.0 else radiusMeters / LOWER_BRANCH_PA * risenFromSouth[point] / cosine
    }

    /**
     * The boundary layer's zonal wind at each of [profileSines], meters a second, eastward: its zonal
     * momentum balance, `(v / a) du/dphi = (f + u tan(phi) / a) v - k u`, marched along the
     * meridional flow from each edge, where the air is still, to the rising branch, each step implicit
     * in the drag so the march holds where the flow slows to nothing; at the rising branch the flow
     * has stopped and the wind with it.
     */
    private val cellEastward: DoubleArray = DoubleArray(PROFILE_POINTS).also { eastward ->
        val rising = ((ascentSine - lowSine) / (highSine - lowSine) * (PROFILE_POINTS - 1)).toInt().coerceIn(0, PROFILE_POINTS - 1)
        for (point in 1..rising) eastward[point] = boundaryLayerStep(eastward[point - 1], point - 1, point)
        for (point in PROFILE_POINTS - 2 downTo rising + 1) eastward[point] = boundaryLayerStep(eastward[point + 1], point + 1, point)
    }

    /** One implicit step of [cellEastward]'s march from the point [from], whose wind is [eastwardFrom], to [to]. */
    private fun boundaryLayerStep(eastwardFrom: Double, from: Int, to: Int): Double {
        val latitude = asin(profileSines[to])
        val step = latitude - asin(profileSines[from])
        val coriolis = PressureWind.coriolisParameter((latitude / DEGREES).toFloat()).toDouble()
        val northward = cellNorthward[to]
        // Air still, or turned back against the march by the tabulation's last digits: no flow to
        // carry momentum along, and the local balance with no meridional flow is calm.
        if (northward * step <= 0.0) return 0.0
        val perSpeed = BoundaryLayer.dragPerSpeed(isLand = false)
        val gustsSquared = BoundaryLayer.TRANSIENT_WIND_MPS * BoundaryLayer.TRANSIENT_WIND_MPS
        var eastward = eastwardFrom
        repeat(DRAG_PASSES) {
            val drag = perSpeed * sqrt(eastward * eastward + northward * northward + gustsSquared)
            eastward = (eastwardFrom + step * radiusMeters * coriolis) /
                (1.0 - step * tan(latitude) + step * radiusMeters * drag / northward)
        }
        return eastward
    }

    /** [values] at the sine [sine] inside the cells, linearly between [profileSines]. */
    private fun interpolate(values: DoubleArray, sine: Double): Double {
        val position = (sine - lowSine) / (highSine - lowSine) * (PROFILE_POINTS - 1)
        val below = position.toInt().coerceIn(0, PROFILE_POINTS - 2)
        val share = position - below
        return values[below] * (1.0 - share) + values[below + 1] * share
    }

    private fun inCells(latitudeDegrees: Double): Boolean {
        val sine = sin(latitudeDegrees * DEGREES)
        return sine > lowSine && sine < highSine
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
        return interpolate(cellNorthward, sin(latitudeDegrees * DEGREES))
    }

    /**
     * The eddy-driven belts of one hemisphere poleward of the cells' edge [edgeDegrees] (degrees from
     * the equator): a westerly belt of Earth's annual width, [WESTERLY_HALF_WIDTH_M] either side of its
     * jet on the ground, from the edge to the polar front, and Earth's polar easterlies from the front
     * to the pole, each a cosine as [SurfaceBelts.zonalShare]'s are. On a planet too small for the belt
     * to fit, the belt fills what is left and the polar easterlies go.
     */
    inner class Belt(val edgeDegrees: Double) {
        private val halfWidthDegrees = minOf(WESTERLY_HALF_WIDTH_M / radiusMeters / DEGREES, (POLE_DEGREES - edgeDegrees) / 2)

        /** The eddy-driven jet, degrees from the equator: where the westerlies blow hardest. */
        val jetDegrees: Double = edgeDegrees + halfWidthDegrees

        /** The polar front, degrees from the equator: where the westerlies give way to the polar easterlies. */
        val frontDegrees: Double = edgeDegrees + 2 * halfWidthDegrees

        /** The belts' zonal wind at [fromEquatorDegrees], meters a second, eastward, under a westerly peak of [westerlyPeakMps]. */
        fun eastwardMps(fromEquatorDegrees: Double, westerlyPeakMps: Double): Double = when {
            fromEquatorDegrees < edgeDegrees -> 0.0
            fromEquatorDegrees <= frontDegrees -> westerlyPeakMps * cos(PI / 2 * (fromEquatorDegrees - jetDegrees) / halfWidthDegrees)
            else -> {
                val centre = 0.5 * (frontDegrees + POLE_DEGREES)
                val polarHalfWidth = 0.5 * (POLE_DEGREES - frontDegrees)
                -POLAR_EASTERLY_PEAK_MPS * cos(PI / 2 * (fromEquatorDegrees - centre) / polarHalfWidth)
            }
        }
    }

    /** The two hemispheres' eddy-driven belts. */
    val northBelt: Belt = Belt(northEdgeDegrees)
    val southBelt: Belt = Belt(southEdgeDegrees)

    /**
     * The surface wind at [latitudeDegrees] under the westerly peaks [northPeakMps] and
     * [southPeakMps], meters a second, eastward and northward.
     */
    private fun windMps(latitudeDegrees: Double, northPeakMps: Double, southPeakMps: Double): Pair<Double, Double> {
        if (inCells(latitudeDegrees)) {
            val sine = sin(latitudeDegrees * DEGREES)
            return interpolate(cellEastward, sine) to interpolate(cellNorthward, sine)
        }
        val north = latitudeDegrees >= 0.0
        val eastward = if (north) northBelt.eastwardMps(latitudeDegrees, northPeakMps) else southBelt.eastwardMps(-latitudeDegrees, southPeakMps)
        return eastward to (if (north) 1.0 else -1.0) * meridionalShare * eastward
    }

    /**
     * The surface's torque on the atmosphere over one hemisphere under a westerly peak of [peakMps],
     * in units proportional to newton meters: the bulk stress `c s u` (the boundary layer's drag per
     * unit of speed, [BoundaryLayer.dragPerSpeed], with the weather's gusts in the speed) times the
     * arm `a cos(phi)` and the circle `2 pi a cos(phi)`, summed over latitude.
     */
    private fun hemisphereTorque(north: Boolean, peakMps: Double): Double {
        val perSpeed = BoundaryLayer.dragPerSpeed(isLand = false)
        val gustsSquared = BoundaryLayer.TRANSIENT_WIND_MPS * BoundaryLayer.TRANSIENT_WIND_MPS
        var sum = 0.0
        for (step in 0 until TORQUE_STEPS) {
            val fromEquator = (step + 0.5) * POLE_DEGREES / TORQUE_STEPS
            val (eastward, northward) = windMps(if (north) fromEquator else -fromEquator, peakMps, peakMps)
            val cosine = cos(fromEquator * DEGREES)
            sum += perSpeed * sqrt(eastward * eastward + northward * northward + gustsSquared) * eastward * cosine * cosine
        }
        return sum
    }

    /**
     * The westerly peak in one hemisphere, meters a second: the one at which the surface's torque
     * over the hemisphere is zero, what the trades and the polar easterlies take from the ground
     * given back under the westerlies, by bisection (the torque rises with the peak). Zero where the
     * hemisphere's easterlies take nothing to give back.
     */
    private fun westerlyPeakMps(north: Boolean): Double {
        if (hemisphereTorque(north, 0.0) >= 0.0) return 0.0
        if (hemisphereTorque(north, LARGEST_WESTERLY_PEAK_MPS) < 0.0) return LARGEST_WESTERLY_PEAK_MPS
        var low = 0.0
        var high = LARGEST_WESTERLY_PEAK_MPS
        repeat(PEAK_BISECTIONS) {
            val middle = 0.5 * (low + high)
            if (hemisphereTorque(north, middle) < 0.0) low = middle else high = middle
        }
        return 0.5 * (low + high)
    }

    /** The westerlies' peak in the northern and the southern hemisphere, meters a second. */
    val northWesterlyPeakMps: Double = westerlyPeakMps(north = true)
    val southWesterlyPeakMps: Double = westerlyPeakMps(north = false)

    /** The surface wind at [latitudeDegrees], meters a second, eastward and northward. */
    fun windMps(latitudeDegrees: Double): Pair<Double, Double> = windMps(latitudeDegrees, northWesterlyPeakMps, southWesterlyPeakMps)

    /** The surface's meridional wind at [latitudeDegrees], meters a second, northward. */
    fun northwardMps(latitudeDegrees: Double): Double = windMps(latitudeDegrees).second

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

        /**
         * The sine of the latitude the equilibrium [equilibrium] stops being able to hold without a
         * circulation in the summer hemisphere: Hill, Bordoni and Mitchell's (2021, J. Atmos. Sci. 78,
         * read) supercritical forcing extent, where its gradient wind's absolute vorticity changes
         * sign, the root of their equation 10, `(1 + 2 Ro / sin phi_m) s^3 - (3/2) Ro s^2 - (1/2) Ro = 0`,
         * with the thermal Rossby number `Ro = (g H / (Omega a)^2) Delta_h sin phi_m`. A large-scale
         * circulation "must exist spanning at minimum all such latitudes". At the equator it is the
         * equator.
         */
        fun supercriticalSine(): Double {
            if (heatingSine < SYMMETRIC_SINE) return 0.0
            val rossby = contrastShare * heatingSine / (2.0 * amcScale)
            fun cubic(sine: Double) = (1.0 + 2.0 * rossby / heatingSine) * sine * sine * sine - 1.5 * rossby * sine * sine - 0.5 * rossby
            // Negative at zero and positive at one, and rising between: one root.
            return bisect(0.0, 1.0, ::cubic)
        }

        /**
         * The cells rising at the heating's supercritical forcing extent ([supercriticalSine]): the
         * summer cell closed by Lindzen and Hou's own two conditions, its temperature meeting the
         * equilibrium's at its edge and its energy closing, which sets the rising branch's temperature;
         * the winter cell's edge where the circulated temperature falls back under the equilibrium's
         * ([winterEdge]). At the equator, Held and Hou's cells. Null where they do not close short of
         * the poles.
         */
        fun risingAt(): Cells? {
            require(heatingSine >= 0.0) { "a northern heating; mirror a southern one" }
            val ascent = supercriticalSine()
            val (summer, theta) = summerCell(ascent) ?: return null
            val winter = if (ascent == 0.0) -summer else winterEdge(ascent, theta) ?: return null
            return Cells(this, ascent, theta, winter, summer, mirrored = false)
        }

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
        /** [HadleyCirculation.windMps] on every one of [rows] rows of the map, eastward and southward, meters a second. */
        fun windOfRows(half: HadleyCirculation, rows: Int): PressureWind.Vectors {
            val eastward = FloatArray(rows)
            val southward = FloatArray(rows)
            for (row in 0 until rows) {
                val (east, north) = half.windMps(ClimateStage.latitudeOf(row, rows).toDouble())
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
         * The depth of the cells' lower branch, pascals: the boundary layer's, [BoundaryLayer.LAYER_THICKNESS_PA],
         * whose Ekman transport the surface branch is (Held 2000, lecture 1). Its mean wind is the
         * surface's meridional wind, and its convergence carries the cells' ascent at its top.
         */
        const val LOWER_BRANCH_PA: Double = BoundaryLayer.LAYER_THICKNESS_PA

        /** Points the cells' vertical motion is tabulated at between their edges: a few hundredths of a degree apart. */
        private const val PROFILE_POINTS = 4_001

        /**
         * Passes of the drag at each step of the boundary layer's march: the drag is the wind's own
         * speed's, so the step is solved for it by fixed point; a step is a hundredth of a degree,
         * over which the wind moves by a few millimeters a second, and the second pass moves the
         * wind by less than a part in a million.
         */
        private const val DRAG_PASSES = 3

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

        /**
         * The westerly belt's half-width on the ground, meters: Earth's annual belts' from the trades'
         * edge to the westerlies' center, [SurfaceBelts.TRADE_BELT_EDGE_DEGREES] to
         * [SurfaceBelts.WESTERLY_BELT_CENTRE_DEGREES], on Earth's radius. A length and not an angle,
         * since the eddies that drive the belt have their own size on the ground.
         */
        val WESTERLY_HALF_WIDTH_M: Double = (SurfaceBelts.WESTERLY_BELT_CENTRE_DEGREES - SurfaceBelts.TRADE_BELT_EDGE_DEGREES) *
            DEGREES * WorldScale.EARTH_EQUATOR_KM * 1_000.0 / (2.0 * PI)

        /** Earth's polar easterlies at their strongest, meters a second: [SurfaceBelts.POLAR_EASTERLY_STRENGTH] of the belts' speed. */
        val POLAR_EASTERLY_PEAK_MPS: Double = SurfaceBelts.POLAR_EASTERLY_STRENGTH.toDouble() * PressureWind.BELT_SPEED_MPS

        /** Steps the hemisphere's torque is summed over: a fortieth of a degree each, finer than the map's rows. */
        private const val TORQUE_STEPS = 3_600

        /**
         * The strongest westerly peak the torque's balance is sought below, meters a second: a ceiling
         * and not a figure, ten times Earth's.
         */
        private const val LARGEST_WESTERLY_PEAK_MPS = 75.0

        /** Halvings of the westerly peak's bracket: forty take seventy-five meters a second below a micrometer a second. */
        private const val PEAK_BISECTIONS = 40

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

        /**
         * Lindzen and Hou's own cells for a heating at [heatingLatitudeDegrees] on a planet of
         * [radiusMeters], their rising branch by their four conditions ([EqualArea.solve]): the source
         * model, against which `HadleyCirculationTest` holds Hill and others' figures.
         */
        fun cellsFor(heatingLatitudeDegrees: Double, radiusMeters: Double): Cells =
            heldWhereTheyClose(heatingLatitudeDegrees, radiusMeters) { it.solveOrNull() }

        /**
         * The cells rising at a heating at [heatingLatitudeDegrees] on a planet of [radiusMeters]
         * ([EqualArea.risingAt]): the circulation's own.
         */
        fun cellsRisingAt(heatingLatitudeDegrees: Double, radiusMeters: Double): Cells {
            lastCells?.let { (heating, radius, cells) -> if (heating == heatingLatitudeDegrees && radius == radiusMeters) return cells }
            return heldWhereTheyClose(heatingLatitudeDegrees, radiusMeters) { it.risingAt() }
                .also { lastCells = Triple(heatingLatitudeDegrees, radiusMeters, it) }
        }

        /**
         * The last cells solved and what they were solved for: a climate run asks for each half's
         * twice, the ocean's stress and the climate, and the cells are a pure function of the two.
         */
        @kotlin.concurrent.Volatile
        private var lastCells: Triple<Double, Double, Cells>? = null

        /**
         * [solveCells] for the northern heating as far from the equator as [heatingLatitudeDegrees],
         * mirrored for a southern one; for a heating so far from the equator (a planet tilted far past
         * Earth's, its summer pole the warmest) that the cells reach a pole first, held at the farthest
         * heating whose cells close, found by bisection, so the circulation stays defined at every tilt.
         */
        private fun heldWhereTheyClose(heatingLatitudeDegrees: Double, radiusMeters: Double, solveCells: (EqualArea) -> Cells?): Cells {
            val scale = amcScale(radiusMeters)
            fun cellsAt(degrees: Double) = solveCells(EqualArea(sin(degrees * DEGREES), scale, EQUILIBRIUM_CONTRAST_SHARE))
            val northern = cellsAt(abs(heatingLatitudeDegrees)) ?: run {
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
         * The energy balance [zonal]'s energy flux equator in the half-year [season], degrees: the
         * latitude where its meridional transport changes direction, which is where the band's mean air
         * ([ZonalClimate.bandC]) is warmest, since the transport runs down that air's gradient; the
         * warmest band, refined by the parabola through it and its two neighbors.
         */
        fun energyFluxEquatorDegrees(zonal: ZonalClimate, season: Season): Double {
            val band = zonal.bandC(season)
            var warmest = 0
            for (index in band.indices) if (band[index] > band[warmest]) warmest = index
            val latitude = EnergyBalance.latitudeOfBand(warmest).toDouble()
            if (warmest == 0 || warmest == band.size - 1) return latitude
            val north = band[warmest - 1].toDouble()
            val here = band[warmest].toDouble()
            val south = band[warmest + 1].toDouble()
            val curvature = north - 2.0 * here + south
            if (curvature >= 0.0) return latitude
            // The parabola's vertex, in bands toward the south; bands run north to south, so a step
            // toward the south is a fall in latitude.
            val southwardBands = 0.5 * (north - south) / curvature
            return latitude - southwardBands * EnergyBalance.POLE_TO_POLE_DEGREES / band.size
        }

        /**
         * Where the troposphere of the energy balance [zonal] first turns baroclinically unstable in
         * the half-year [season], degrees from the equator in the northern hemisphere when [north] or
         * the southern, searching poleward from [fromDegrees]; null where it never does short of
         * [LARGEST_ONSET_DEGREES].
         *
         * Held's (2000, lecture 6, read) two-layer criterion: two layers of equal depth `H / 2` are
         * unstable where the interface between them slopes by more than `beta (H / 2) / f`, and the
         * interface lies along the isentropes, whose slope is `(d theta / dy) / (d theta / dz)`. So the
         * troposphere is unstable where `2 f (d theta / dy) / (beta H d theta / dz)` reaches one,
         * `2 tan(phi) (d theta / d phi) / (theta N^2 H / g)` on the sphere. The gradient is the band's
         * mean air's ([ZonalClimate.bandC]) in the half, the stratification and depth the repository's
         * ([PressureWind.BUOYANCY_FREQUENCY_PER_S], [PressureWind.TROPOPAUSE_DEPTH_M]).
         */
        fun baroclinicOnsetDegrees(zonal: ZonalClimate, season: Season, north: Boolean, fromDegrees: Double): Double? {
            val band = zonal.bandC(season)
            fun airC(fromEquator: Double) = zonal.bandValueAt(band, (if (north) fromEquator else -fromEquator).toFloat()).toDouble()
            val stratification = PressureWind.BUOYANCY_FREQUENCY_PER_S * PressureWind.BUOYANCY_FREQUENCY_PER_S *
                PressureWind.TROPOPAUSE_DEPTH_M / DryAir.GRAVITY_MPS2
            fun supercriticality(fromEquator: Double): Double {
                val polewardDrop = (airC(fromEquator - GRADIENT_HALF_SPAN_DEGREES) - airC(fromEquator + GRADIENT_HALF_SPAN_DEGREES)) /
                    (2.0 * GRADIENT_HALF_SPAN_DEGREES * DEGREES)
                val kelvin = airC(fromEquator) + ColumnWater.KELVIN_AT_ZERO_C
                return 2.0 * tan(fromEquator * DEGREES) * polewardDrop / (kelvin * stratification)
            }
            var previous = maxOf(fromDegrees, ONSET_STEP_DEGREES)
            var previousValue = supercriticality(previous)
            if (previousValue >= 1.0) return previous
            while (previous + ONSET_STEP_DEGREES <= LARGEST_ONSET_DEGREES) {
                val next = previous + ONSET_STEP_DEGREES
                val nextValue = supercriticality(next)
                if (nextValue >= 1.0) return previous + (next - previous) * (1.0 - previousValue) / (nextValue - previousValue)
                previous = next
                previousValue = nextValue
            }
            return null
        }

        /** The half-span the band's gradient is taken across, degrees: two thirds of a band of the energy balance's. */
        private const val GRADIENT_HALF_SPAN_DEGREES = 0.5

        /** The step the onset is searched at, degrees: a quarter, finer than the energy balance's bands. */
        private const val ONSET_STEP_DEGREES = 0.25

        /** The farthest latitude the onset is sought at, degrees: past it a row is no longer the circulation's. */
        private const val LARGEST_ONSET_DEGREES = 80.0

        /** [season]'s circulation over the world [config] describes, under the energy balance [zonal]. */
        fun forHalf(config: WorldGenConfig, zonal: ZonalClimate, season: Season): HadleyCirculation {
            val heating = energyFluxEquatorDegrees(zonal, season)
            val radiusMeters = config.scale.radiusMeters
            val cells = cellsRisingAt(heating, radiusMeters)
            val ascent = cells.ascentLatitudeDegrees
            // Each edge is sought poleward of the rising branch, so the summer cell is never empty.
            val north = baroclinicOnsetDegrees(zonal, season, north = true, fromDegrees = maxOf(ascent, 0.0) + ONSET_STEP_DEGREES)
                ?: cells.northEdgeDegrees
            val south = baroclinicOnsetDegrees(zonal, season, north = false, fromDegrees = maxOf(-ascent, 0.0) + ONSET_STEP_DEGREES)
                ?: cells.southEdgeDegrees
            return HadleyCirculation(heating, cells, north, south, radiusMeters, config.climate.meridionalWindShare.toDouble())
        }

        /**
         * The circulation for a heating at [heatingLatitudeDegrees] on a planet of [radiusMeters], its
         * edges at [northEdgeDegrees] north and [southEdgeDegrees] south (the axisymmetric cells' own
         * unless given), the eddy-driven belts' meridional legs at [meridionalShare] of their speed.
         */
        fun forHeating(
            heatingLatitudeDegrees: Double,
            radiusMeters: Double,
            meridionalShare: Double = ClimateConfig().meridionalWindShare.toDouble(),
            northEdgeDegrees: Double = cellsRisingAt(heatingLatitudeDegrees, radiusMeters).northEdgeDegrees,
            southEdgeDegrees: Double = cellsRisingAt(heatingLatitudeDegrees, radiusMeters).southEdgeDegrees
        ): HadleyCirculation = HadleyCirculation(
            heatingLatitudeDegrees, cellsRisingAt(heatingLatitudeDegrees, radiusMeters), northEdgeDegrees, southEdgeDegrees,
            radiusMeters, meridionalShare
        )
    }
}
