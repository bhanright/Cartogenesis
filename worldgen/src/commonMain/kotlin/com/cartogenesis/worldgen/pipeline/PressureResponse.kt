package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The surface pressure the land's and the sea's heating raise, solved: the steady, linear, damped
 * response of the lower troposphere (Matsuno 1966, *J. Meteor. Soc. Japan* 44, 25-43; Gill 1980,
 * *Q. J. R. Meteorol. Soc.* 106, 447-462), read at the surface as Lindzen and Nigam read it (1987,
 * *J. Atmos. Sci.* 44, 2418-2436).
 *
 * **The balance**, for the layer's geopotential departure Φ, in m²/s², and its wind (u east, v
 * north), on the same flat plate carrée the ocean is solved on:
 *
 * ```
 * ε u - f v = -∂Φ/∂x      ε v + f u = -∂Φ/∂y      α Φ + c² (∂u/∂x + ∂v/∂y) = α Φ_eq
 * ```
 *
 * `Φ_eq` is the pressure the column's own temperature would hold, [PressureWind.HPA_PER_KELVIN]
 * per degree below its row's mean, over the air's density; ε is the surface drag, `f` the
 * Coriolis parameter, α the air's thermal relaxation and `c` the layer's gravity-wave speed.
 * The two momentum equations give the wind from the gradient cell by cell, exactly as
 * [PressureWind.surfaceWind] does: `u = -a Φ_x - b Φ_y`, `v = -a Φ_y + b Φ_x`, with
 * `a = ε / (ε² + f²)` and `b = f / (ε² + f²)`. Put into the third, with ε free to differ from cell
 * to cell, that is
 *
 * `α Φ - ∇·(c² a ∇Φ) + c² (∂b/∂y ∂Φ/∂x - ∂b/∂x ∂Φ/∂y) = α Φ_eq`
 *
 * with nothing dropped: the x derivatives of `a` and `b` that a drag differing between land and
 * sea brings are all there. The second term is a diffusion with diffusivity `K = c² a`. The third
 * is an advection by the velocity `(c² ∂b/∂y, -c² ∂b/∂x)`, which is divergence-free, since it is
 * derived from the stream function `ψ = -c² b`. So the whole problem is [OceanHeat]'s, `u·∇T -
 * ∇·(K∇T) + (T - T_target)/τ = 0`, with its velocity taken from a stream function at the cell
 * corners, and it is discretized the same way: Scharfetter-Gummel fluxes, every weight
 * non-negative, a margin of α, an [OceanStencil] relaxed by the same multigrid and the same device.
 *
 * **What the advection is.** Where `f` is much larger than ε, `∂b/∂y` is `-β/f²` and the drift
 * is westward at `β c² / f²`: the long Rossby wave, which is what carries a cooled ocean's high,
 * and a heated continent's low, west of their forcing (`βv = f ∂w/∂z` on Earth; Rodwell and
 * Hoskins 2001, *J. Climate* 14, 3192-3211). Within `|f| < ε`, 19 degrees of the equator over the
 * sea and 36 over land at these drags, `∂b/∂y` is positive and the drift is eastward, fastest on
 * the equator at `c² β / ε²`: the damped counterpart of Gill's eastward Kelvin response. The
 * elimination is exact, not the long-wave approximation Gill made, so the equatorial band is solved
 * with both and needs no case of its own.
 *
 * **Linearized about a resting layer, not about the belts.** A full linearization about a zonal
 * wind `U` adds `U ∂Φ/∂x` and the departure's meridional wind carrying the basic state's own
 * thickness gradient, `v ∂Φ̄/∂y`. In a single layer whose basic flow is geostrophic, `∂Φ̄/∂y` is
 * `-f U`, and for the long waves the two cancel: the basic state's tilted surface adds `f² U / c²`
 * to the gradient of potential vorticity, which slows the long wave by exactly the `U` the flow
 * speeds it by (Pedlosky 1987, *Geophysical Fluid Dynamics*, the shallow-water potential
 * vorticity). Adding `U ∂Φ/∂x` alone would therefore shift the response by a wind the complete
 * linearization does not. And the complete one cannot be taken about these belts, because they are
 * prescribed and are not a solution of this layer's own balance: its drag would give a zonal
 * wind with no zonal pressure gradient a meridional part `ε/f` of its speed, 0.66 over the sea at
 * 30 degrees, more than four times the belts' own slope of 0.15. What is
 * left out is the momentum carried by the mean wind, a share `U/(εL)` of the drag, about 0.16 for
 * the belts' 7.5 m/s over a thousand kilometers; and the damped remainder of the cancellation,
 * which is not small in the subtropics. docs/TODO.md records both.
 *
 * **The poles** pass no mass: no diffusive flux and no drift crosses the map's top or bottom edge,
 * the stream function taking one value along each.
 *
 * **Its zonal mean.** The forcing has no row mean. With a drag that depends only on the row, nor
 * would the answer, since the operator's zonal mean would then be an equation in the row mean alone
 * with nothing to drive it; with land's drag differing from the sea's it is not zero by
 * construction. It comes to about a hundredth of the largest departure, a few hundredths of a
 * hectopascal, and it is taken out after the solve ([withoutRowMeans]): the belts are the zonal
 * mean of the wind, and a departure with a zonal mean of its own would count part of it twice.
 */
internal object PressureResponse {

    /**
     * The speed of the troposphere's first baroclinic gravity waves, in meters a second: `N H / π`,
     * 31.8, the first vertical mode of a layer of uniform stratification between the ground and a
     * rigid lid (Gill 1982, *Atmosphere-Ocean Dynamics*, section 6.11), from the same stratification
     * and tropopause [PressureWind]'s own figures are built on. One speed for the deformation radius
     * and the drift alike.
     */
    val GRAVITY_WAVE_SPEED_MPS: Double =
        PressureWind.BUOYANCY_FREQUENCY_PER_S * PressureWind.TROPOPAUSE_DEPTH_M / PI

    /**
     * α, how fast the layer's thickness relaxes to what its surface's temperature would hold, per
     * second: the energy balance's own surface exchange over the heat capacity of the layer whose
     * warming makes the pressure, 2.4 days, one ruler with the climate.
     *
     * The layer is [PressureWind.HPA_PER_KELVIN]'s, from the surface to the level of non-divergence,
     * so it holds the share `1 - 500 / 1013.25` of the air column's mass. The energy balance's
     * marine air, 1.04e7 J/m²/K, is the whole column (1004 J/kg/K times 101,325 Pa over 9.81 m/s²),
     * and the layer's share of it is 5.3e6. The air above the level of non-divergence warms with the
     * column but does not move the surface pressure in this model, so it is not what the pressure
     * relaxes with. Held and Suarez's (1994, *Bull. Amer. Meteor. Soc.* 75, 1825-1830) Newtonian
     * relaxation at the surface, four days, is the same order.
     */
    val THERMAL_RELAXATION_PER_S: Double =
        EnergyBalance.SURFACE_EXCHANGE_W_PER_M2_C /
            (EnergyBalance.MARINE_AIR_HEAT_CAPACITY_J_PER_M2_C *
                (1.0 - PressureWind.NON_DIVERGENT_LEVEL_HPA / PressureWind.SEA_LEVEL_PRESSURE_HPA))

    /** ε over the sea, per second: the drag [PressureWind] turns the surface wind by, 5.8 hours. */
    val SEA_DRAG_PER_S: Double = PressureWind.surfaceDrag(PressureWind.CROSS_ISOBAR_SEA_DEGREES).toDouble()

    /** ε over land, per second: rougher ground, a deeper boundary layer, 3.2 hours. */
    val LAND_DRAG_PER_S: Double = PressureWind.surfaceDrag(PressureWind.CROSS_ISOBAR_LAND_DEGREES).toDouble()

    /**
     * How many cells of the solve grid span the problem's shortest length ([shortestLengthMeters]).
     *
     * Four. Along the drift the fitted fluxes are exact at the nodes whatever the spacing; what the
     * spacing costs is the relaxation's share, whose discrete decay rate `q` over a spacing Δ
     * satisfies `2 (cosh q - 1) = (Δ/L)²`, reading a length L short by `(Δ/L)²/24`: a quarter of a
     * percent at four cells. The one-dimensional strip in `PressureResponseTest` measures the
     * scheme's exponents on both sides against the analytic ones.
     */
    private const val CELLS_ACROSS_SHORTEST_LENGTH = 4.0

    /** The latitude step the shortest length is searched over, in degrees. */
    private const val LATITUDE_SEARCH_STEP_DEGREES = 0.25

    /** Meters in a kilometer. */
    private const val METERS_PER_KM = 1_000.0

    /** A pascal is a hectopascal over a hundred. */
    private const val PASCALS_PER_HPA = 100.0

    private const val DEGREES_TO_RADIANS = PI / 180.0

    /** The Coriolis parameter at [latitudeDegrees], per second. */
    fun coriolisPerS(latitudeDegrees: Double): Double =
        2.0 * WorldScale.ROTATION_RATE_PER_S * sin(latitudeDegrees * DEGREES_TO_RADIANS)

    /** `f` at a share of a whole map's height from its top edge, the north pole. */
    fun coriolisFromPoleToPole(shareOfHeightFromTop: Double): Double = coriolisPerS(90.0 - 180.0 * shareOfHeightFromTop)

    /** `a = ε / (ε² + f²)`, in seconds: the share of a pressure gradient the wind runs down. */
    fun downGradientSeconds(dragPerS: Double, coriolisPerS: Double): Double =
        dragPerS / (dragPerS * dragPerS + coriolisPerS * coriolisPerS)

    /** `b = f / (ε² + f²)`, in seconds: the share the wind runs along the isobars. */
    fun alongIsobarSeconds(dragPerS: Double, coriolisPerS: Double): Double =
        coriolisPerS / (dragPerS * dragPerS + coriolisPerS * coriolisPerS)

    /** `K = c² a`, square meters a second: how fast the layer's mass spreads a departure. */
    fun diffusivityM2PerS(dragPerS: Double, coriolisPerS: Double): Double =
        GRAVITY_WAVE_SPEED_MPS * GRAVITY_WAVE_SPEED_MPS * downGradientSeconds(dragPerS, coriolisPerS)

    /**
     * The drift's eastward speed, meters a second, under a uniform drag: `c² ∂b/∂y`, with
     * `∂b/∂y = β (ε² - f²) / (ε² + f²)²`. Westward, negative, where `f` is larger than ε.
     */
    fun eastwardDriftMps(latitudeDegrees: Double, dragPerS: Double, scale: WorldScale): Double {
        val coriolis = coriolisPerS(latitudeDegrees)
        val beta = scale.planetaryVorticityGradientPerMeterSecond(latitudeDegrees)
        val sum = dragPerS * dragPerS + coriolis * coriolis
        return GRAVITY_WAVE_SPEED_MPS * GRAVITY_WAVE_SPEED_MPS * beta * (dragPerS * dragPerS - coriolis * coriolis) / (sum * sum)
    }

    /**
     * The diffusion-relaxation length, `sqrt(K/α) = sqrt(c² ε / (α (ε² + f²)))`, meters: about
     * `(c/|f|) sqrt(ε/α)` where `f` dominates, and the response's reach with no drift.
     */
    fun diffusionRelaxationLengthMeters(latitudeDegrees: Double, dragPerS: Double): Double =
        sqrt(diffusivityM2PerS(dragPerS, coriolisPerS(latitudeDegrees)) / THERMAL_RELAXATION_PER_S)

    /**
     * How far a forcing's response reaches east and west, meters, under a uniform drag: the two
     * roots of `K λ² - V λ - α = 0`, the one-dimensional balance along a row with the drift `V`,
     * as e-folding lengths. The first is the western reach, the second the eastern.
     */
    fun reachesMeters(latitudeDegrees: Double, dragPerS: Double, scale: WorldScale): Pair<Double, Double> {
        val diffusivity = diffusivityM2PerS(dragPerS, coriolisPerS(latitudeDegrees))
        val drift = eastwardDriftMps(latitudeDegrees, dragPerS, scale)
        val root = sqrt(drift * drift + 4.0 * diffusivity * THERMAL_RELAXATION_PER_S)
        val westward = 2.0 * diffusivity / (drift + root)
        val eastward = 2.0 * diffusivity / (root - drift)
        return westward to eastward
    }

    /**
     * The problem's shortest length, meters: the shorter reach of [reachesMeters] at whichever
     * latitude and over whichever surface makes it least. On this generator's world, the western
     * reach over the sea at the equator, where the drift runs east fastest, about 580 km; it reads
     * the radius through β, so a planet of another size has its own.
     */
    fun shortestLengthMeters(scale: WorldScale): Double {
        var shortest = Double.MAX_VALUE
        var latitude = 0.0
        while (latitude <= 90.0) {
            for (drag in doubleArrayOf(SEA_DRAG_PER_S, LAND_DRAG_PER_S)) {
                val (west, east) = reachesMeters(latitude, drag, scale)
                shortest = min(shortest, min(west, east))
            }
            latitude += LATITUDE_SEARCH_STEP_DEGREES
        }
        return shortest
    }

    /**
     * The grid the response is solved on, columns and rows: square on the ground, the same at every
     * map size, [CELLS_ACROSS_SHORTEST_LENGTH] to [shortestLengthMeters], rounded up for the V-cycle.
     */
    fun solveGrid(scale: WorldScale): Pair<Int, Int> {
        val spacingMeters = shortestLengthMeters(scale) / CELLS_ACROSS_SHORTEST_LENGTH
        val worldHeightMeters = scale.worldWidthKm * WorldScale.WORLD_HEIGHT_AS_SHARE_OF_WIDTH * METERS_PER_KM
        val down = OceanStage.rowsForCycle(ceil(worldHeightMeters / spacingMeters).toInt())
        val across = (down / WorldScale.WORLD_HEIGHT_AS_SHARE_OF_WIDTH).roundToInt()
        return (across + (across and 1)) to down
    }

    /**
     * The problem on one grid. [landShare] is each cell's share of land, which sets its drag as the
     * area-weighted mean of the two; [equilibriumM2PerS2] is `Φ_eq` per cell, or null for a coarse
     * grid of the cycle that solves for a correction. Every cell is active: there is no coast for
     * the air. [coriolisAt] is `f` per second at a share of the grid's height from its top edge,
     * the world's own from pole to pole unless a guard asks for a β-plane.
     */
    fun stencil(
        cellsAcross: Int,
        cellsDown: Int,
        cellWidthMeters: Double,
        cellHeightMeters: Double,
        landShare: FloatArray,
        equilibriumM2PerS2: DoubleArray?,
        coriolisAt: (shareOfHeightFromTop: Double) -> Double = ::coriolisFromPoleToPole
    ): OceanStencil {
        val cells = cellsAcross * cellsDown
        val speedSquared = GRAVITY_WAVE_SPEED_MPS * GRAVITY_WAVE_SPEED_MPS
        val drag = DoubleArray(cells) { SEA_DRAG_PER_S + (LAND_DRAG_PER_S - SEA_DRAG_PER_S) * landShare[it] }
        val coriolisOfRow = DoubleArray(cellsDown) { coriolisAt((it + 0.5) / cellsDown) }
        val coriolisOfBoundary = DoubleArray(cellsDown + 1) { coriolisAt(it.toDouble() / cellsDown) }

        // ψ = -c² b at the corners: corner (boundary, column) is the one at the north-west of cell
        // (boundary, column). Inside, the mean of the four cells around it; on a pole, one value
        // along the whole edge, so no drift crosses it.
        val corner = DoubleArray((cellsDown + 1) * cellsAcross)
        for (boundary in 1 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val west = if (column == 0) cellsAcross - 1 else column - 1
                var sum = 0.0
                for (row in intArrayOf(boundary - 1, boundary)) {
                    for (side in intArrayOf(west, column)) {
                        sum += -speedSquared * alongIsobarSeconds(drag[row * cellsAcross + side], coriolisOfRow[row])
                    }
                }
                corner[boundary * cellsAcross + column] = sum / 4.0
            }
        }
        for ((boundary, edgeRow) in listOf(0 to 0, cellsDown to cellsDown - 1)) {
            var sum = 0.0
            for (column in 0 until cellsAcross) {
                sum += -speedSquared * alongIsobarSeconds(drag[edgeRow * cellsAcross + column], coriolisOfBoundary[boundary])
            }
            val poleValue = sum / cellsAcross
            for (column in 0 until cellsAcross) corner[boundary * cellsAcross + column] = poleValue
        }

        val east = FloatArray(cells)
        val west = FloatArray(cells)
        val north = FloatArray(cells)
        val south = FloatArray(cells)
        val centre = DoubleArray(cells)
        val balance = DoubleArray(cells)
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                val columnEast = if (column + 1 == cellsAcross) 0 else column + 1
                val columnWest = if (column == 0) cellsAcross - 1 else column - 1
                val northWest = corner[row * cellsAcross + column]
                val northEast = corner[row * cellsAcross + columnEast]
                val southWest = corner[(row + 1) * cellsAcross + column]
                val southEast = corner[(row + 1) * cellsAcross + columnEast]
                // Outward drift through each face, meters a second, u = -∂ψ/∂y and v = ∂ψ/∂x.
                val outEast = -(northEast - southEast) / cellHeightMeters
                val outWest = (northWest - southWest) / cellHeightMeters
                val outNorth = (northEast - northWest) / cellWidthMeters
                val outSouth = -(southEast - southWest) / cellWidthMeters
                // A face's drag is the mean of the two cells' either side of it.
                val eastWeight = OceanHeat.faceWeight(
                    speedSquared * downGradientSeconds((drag[cell] + drag[row * cellsAcross + columnEast]) / 2.0, coriolisOfRow[row]),
                    outEast, cellWidthMeters
                )
                val westWeight = OceanHeat.faceWeight(
                    speedSquared * downGradientSeconds((drag[cell] + drag[row * cellsAcross + columnWest]) / 2.0, coriolisOfRow[row]),
                    outWest, cellWidthMeters
                )
                val northWeight = if (row == 0) 0.0 else OceanHeat.faceWeight(
                    speedSquared * downGradientSeconds((drag[cell] + drag[cell - cellsAcross]) / 2.0, coriolisOfBoundary[row]),
                    outNorth, cellHeightMeters
                )
                val southWeight = if (row == cellsDown - 1) 0.0 else OceanHeat.faceWeight(
                    speedSquared * downGradientSeconds((drag[cell] + drag[cell + cellsAcross]) / 2.0, coriolisOfBoundary[row + 1]),
                    outSouth, cellHeightMeters
                )
                val centreWeight = eastWeight + westWeight + northWeight + southWeight + THERMAL_RELAXATION_PER_S
                east[cell] = (eastWeight / centreWeight).toFloat()
                west[cell] = (westWeight / centreWeight).toFloat()
                north[cell] = (northWeight / centreWeight).toFloat()
                south[cell] = (southWeight / centreWeight).toFloat()
                centre[cell] = centreWeight
                if (equilibriumM2PerS2 != null) balance[cell] = -equilibriumM2PerS2[cell] * THERMAL_RELAXATION_PER_S
            }
        }
        return OceanCirculation.withBalance(
            OceanStencil(cellsAcross, cellsDown, BooleanArray(cells) { true }, east, west, north, south, FloatArray(cells), centre),
            balance
        )
    }

    /**
     * A solved response: Φ on its grid, m²/s², the grid's spacing on the ground and each cell's
     * land share, and the solve's own record for the guards.
     */
    class Response(
        val cellsAcross: Int,
        val cellsDown: Int,
        val cellWidthMeters: Double,
        val cellHeightMeters: Double,
        val landShare: FloatArray,
        val equilibriumM2PerS2: DoubleArray,
        val geopotentialM2PerS2: FloatArray,
        val solution: OceanCirculation.Solution,
        /**
         * The largest row mean of the field as solved, over its largest value: what a drag that
         * differs between land and sea leaves in the zonal mean, taken out of [geopotentialM2PerS2].
         */
        val solvedRowMeanShare: Double
    ) {
        /** The response as a surface pressure departure, hectopascals, per solve cell. */
        fun pressureHpa(): FloatArray = FloatArray(geopotentialM2PerS2.size) {
            (geopotentialM2PerS2[it] * PressureWind.AIR_DENSITY_KG_PER_M3 / PASCALS_PER_HPA).toFloat()
        }
    }

    /**
     * The response to one season's column temperature, [columnTemperatureC] on the map's grid in
     * degrees Celsius (see [ClimateStage.columnTemperature]), on [solveGrid]. [relax] is the
     * relaxation, the processor's or a device's; a solve that does not reach its tolerance throws
     * [OceanCirculation.OceanSolveFailure], as the ocean's own do.
     */
    inline fun solve(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        columnTemperatureC: FloatField,
        relax: (OceanStencil, FloatArray, Int) -> FloatArray
    ): Response {
        val (across, down) = solveGrid(config.scale)
        val widthMeters = config.scale.worldWidthKm * METERS_PER_KM / across
        val heightMeters = config.scale.worldWidthKm * WorldScale.WORLD_HEIGHT_AS_SHARE_OF_WIDTH * METERS_PER_KM / down
        val landOnMap = FloatArray(config.width * config.height) { if (sea.isLand[it]) 1f else 0f }
        val landShare = areaMean(landOnMap, config.width, config.height, across, down)
        val equilibrium = areaMean(equilibriumOnMap(config, columnTemperatureC), config.width, config.height, across, down)
        return solveOn(across, down, widthMeters, heightMeters, landShare, DoubleArray(equilibrium.size) { equilibrium[it].toDouble() }, relax = relax)
    }

    /** The response on a grid already given its land share and its `Φ_eq`: what the synthetic guards solve. */
    inline fun solveOn(
        across: Int,
        down: Int,
        widthMeters: Double,
        heightMeters: Double,
        landShare: FloatArray,
        equilibriumM2PerS2: DoubleArray,
        noinline coriolisAt: (shareOfHeightFromTop: Double) -> Double = ::coriolisFromPoleToPole,
        removeRowMeans: Boolean = true,
        relax: (OceanStencil, FloatArray, Int) -> FloatArray
    ): Response {
        val finest = stencil(across, down, widthMeters, heightMeters, landShare, equilibriumM2PerS2, coriolisAt)
        val levels = OceanCirculation.levels(finest, widthMeters, heightMeters, everyCellWater = true) { coarseAcross, coarseDown, _ ->
            val coarseLand = OceanCirculation.restrict(
                DoubleArray(landShare.size) { landShare[it].toDouble() }, across, down, coarseAcross, coarseDown
            )
            stencil(
                coarseAcross, coarseDown, widthMeters * across / coarseAcross, heightMeters * down / coarseDown,
                FloatArray(coarseLand.size) { coarseLand[it].toFloat() }, null, coriolisAt
            )
        }
        val solution = OceanCirculation.requireSolved(
            "the pressure's response",
            OceanCirculation.solveByKrylov(
                levels, FloatArray(across * down) { equilibriumM2PerS2[it].toFloat() }, OceanCirculation.RESIDUAL_TOLERANCE,
                poleIsWall = false, OceanCirculation.waterBodies(finest.isWater, across, down), relax = relax
            ),
            OceanCirculation.RESIDUAL_TOLERANCE
        )
        return withoutRowMeans(across, down, widthMeters, heightMeters, landShare, equilibriumM2PerS2, solution, removeRowMeans)
    }

    /**
     * The solved field with each row's mean taken out, so the departure carries no zonal mean and
     * the belts stay the whole of the zonal-mean wind; with [removeRowMeans] off, as solved, for the
     * guard that shows what the removal is for. What was taken out is kept on the [Response].
     */
    fun withoutRowMeans(
        across: Int,
        down: Int,
        widthMeters: Double,
        heightMeters: Double,
        landShare: FloatArray,
        equilibriumM2PerS2: DoubleArray,
        solution: OceanCirculation.Solution,
        removeRowMeans: Boolean
    ): Response {
        val solved = solution.values
        val field = solved.copyOf()
        var largestRowMean = 0.0
        var largest = 0.0
        for (row in 0 until down) {
            var sum = 0.0
            for (cell in row * across until (row + 1) * across) sum += solved[cell]
            val rowMean = sum / across
            largestRowMean = maxOf(largestRowMean, kotlin.math.abs(rowMean))
            if (removeRowMeans) {
                for (cell in row * across until (row + 1) * across) field[cell] = (solved[cell] - rowMean).toFloat()
            }
        }
        for (value in solved) largest = maxOf(largest, kotlin.math.abs(value.toDouble()))
        return Response(
            across, down, widthMeters, heightMeters, landShare, equilibriumM2PerS2, field, solution,
            if (largest > 0.0) largestRowMean / largest else 0.0
        )
    }

    /**
     * `Φ_eq` on the map's grid, m²/s²: each cell's column temperature below its row's mean, in
     * degrees, times [PressureWind.HPA_PER_KELVIN], as a geopotential. Zero in every row's mean.
     */
    fun equilibriumOnMap(config: WorldGenConfig, columnTemperatureC: FloatField): FloatArray {
        val cellsAcross = config.width
        val field = FloatArray(cellsAcross * config.height)
        val perKelvin = PressureWind.HPA_PER_KELVIN * PASCALS_PER_HPA / PressureWind.AIR_DENSITY_KG_PER_M3
        for (row in 0 until config.height) {
            var sum = 0.0
            for (cell in row * cellsAcross until (row + 1) * cellsAcross) sum += columnTemperatureC.data[cell]
            val rowMeanC = sum / cellsAcross
            for (cell in row * cellsAcross until (row + 1) * cellsAcross) {
                field[cell] = (-(columnTemperatureC.data[cell] - rowMeanC) * perKelvin).toFloat()
            }
        }
        return field
    }

    /**
     * A per-cell field on a grid [fromAcross] by [fromDown] as the mean over each cell of a grid
     * [toAcross] by [toDown] covering the same map, every cell of the first weighted by the share of
     * its area that falls in the second: a resampling, not a union of blocks, since neither grid's
     * cells line up with the other's.
     */
    fun areaMean(field: FloatArray, fromAcross: Int, fromDown: Int, toAcross: Int, toDown: Int): FloatArray {
        val columns = overlaps(fromAcross, toAcross)
        val rows = overlaps(fromDown, toDown)
        val result = FloatArray(toAcross * toDown)
        for (row in 0 until toDown) {
            for (column in 0 until toAcross) {
                var sum = 0.0
                var weight = 0.0
                for ((fromRow, rowShare) in rows[row]) {
                    for ((fromColumn, columnShare) in columns[column]) {
                        val share = rowShare * columnShare
                        sum += share * field[fromRow * fromAcross + fromColumn]
                        weight += share
                    }
                }
                result[row * toAcross + column] = (sum / weight).toFloat()
            }
        }
        return result
    }

    /** For each of [toCount] cells along an axis, the [fromCount] cells it overlaps and by how much of one. */
    private fun overlaps(fromCount: Int, toCount: Int): List<List<Pair<Int, Double>>> = List(toCount) { index ->
        val start = index.toDouble() * fromCount / toCount
        val end = (index + 1).toDouble() * fromCount / toCount
        val pieces = ArrayList<Pair<Int, Double>>()
        var from = floor(start).toInt()
        while (from < end && from < fromCount) {
            val share = min(end, from + 1.0) - maxOf(start, from.toDouble())
            if (share > 0.0) pieces += from to share
            from++
        }
        pieces
    }

    /** Φ's gradient per solve cell, m/s², eastward and northward. */
    class Gradient(val eastward: FloatArray, val northward: FloatArray)

    /**
     * The gradient of [response]'s Φ on its own grid, by central differences: columns wrap, and an
     * edge row takes the one-sided difference inward.
     */
    fun gradient(response: Response): Gradient {
        val across = response.cellsAcross
        val down = response.cellsDown
        val values = response.geopotentialM2PerS2
        val eastward = FloatArray(across * down)
        val northward = FloatArray(across * down)
        for (row in 0 until down) {
            val rowNorth = (row - 1).coerceAtLeast(0)
            val rowSouth = (row + 1).coerceAtMost(down - 1)
            val spanMeters = (rowSouth - rowNorth) * response.cellHeightMeters
            for (column in 0 until across) {
                val columnEast = if (column + 1 == across) 0 else column + 1
                val columnWest = if (column == 0) across - 1 else column - 1
                val cell = row * across + column
                eastward[cell] = ((values[row * across + columnEast] - values[row * across + columnWest]) /
                    (2.0 * response.cellWidthMeters)).toFloat()
                northward[cell] = ((values[rowNorth * across + column] - values[rowSouth * across + column]) / spanMeters).toFloat()
            }
        }
        return Gradient(eastward, northward)
    }

    /**
     * A field on the response's grid read at a fractional cell position by Catmull-Rom's cubic in
     * each direction: continuous with its first derivative, so a wind read from it has a curl with
     * no seam at the solve grid's cell edges. Columns wrap; rows clamp at the poles.
     */
    fun smoothSample(field: FloatArray, across: Int, down: Int, column: Double, row: Double): Double {
        val left = floor(column).toInt()
        val top = floor(row).toInt()
        val acrossBlend = column - left
        val downBlend = row - top
        var sum = 0.0
        for (rowOffset in -1..2) {
            val sampleRow = (top + rowOffset).coerceIn(0, down - 1)
            val rowWeight = catmullRom(downBlend, rowOffset)
            var rowSum = 0.0
            for (columnOffset in -1..2) {
                val sampleColumn = ((left + columnOffset) % across + across) % across
                rowSum += catmullRom(acrossBlend, columnOffset) * field[sampleRow * across + sampleColumn]
            }
            sum += rowWeight * rowSum
        }
        return sum
    }

    /** Catmull-Rom's weight for the sample [offset] cells from the one below a point [blend] of the way to the next. */
    private fun catmullRom(blend: Double, offset: Int): Double {
        val t = blend
        return when (offset) {
            -1 -> 0.5 * (-t + 2 * t * t - t * t * t)
            0 -> 0.5 * (2 - 5 * t * t + 3 * t * t * t)
            1 -> 0.5 * (t + 4 * t * t - 3 * t * t * t)
            else -> 0.5 * (-t * t + t * t * t)
        }
    }

    /**
     * The surface wind the response drives at [latitudeDegrees] under [dragPerS], meters a second,
     * from its gradient there ([gradientEast], [gradientNorth], m/s²): `u = -a Φ_x - b Φ_y`,
     * `v = -a Φ_y + b Φ_x`, the balance the response was solved with.
     */
    fun windMps(latitudeDegrees: Double, dragPerS: Double, gradientEast: Double, gradientNorth: Double): SurfaceBelts.Wind {
        val coriolis = coriolisPerS(latitudeDegrees)
        val a = downGradientSeconds(dragPerS, coriolis)
        val b = alongIsobarSeconds(dragPerS, coriolis)
        return SurfaceBelts.Wind(
            (-a * gradientEast - b * gradientNorth).toFloat(),
            (-a * gradientNorth + b * gradientEast).toFloat()
        )
    }
}
