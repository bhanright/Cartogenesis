package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class OceanResult(
    /** The surface current's eastward component, in meters a second; zero on land. */
    val velocityX: FloatField,
    /** Its southward component, in meters a second, rows running south as the map's do; zero on land. */
    val velocityY: FloatField,
    /** Sea-surface temperature in degrees Celsius. */
    val temperature: FloatField,
    /**
     * How much warmer or colder the water is than the average for its latitude.
     *
     * This is the number that matters downstream: a coast is mild or arid because of how its water
     * compares to the same latitude elsewhere, not its absolute temperature.
     */
    val anomaly: FloatField
)

/**
 * Step 5b: wind-driven surface currents, and the sea temperature they carry.
 *
 * The currents are solved, not drawn: Stommel's balance between the curl of the wind's stress, the
 * change of the Coriolis parameter with latitude and friction under the wind-driven layer, inside
 * whatever basins the coasts make ([OceanCirculation]). The heat is then carried by those currents,
 * mixed by eddies and relaxed toward each latitude's temperature ([OceanHeat]). Every figure is a
 * physical one derived from the planet — its radius through [WorldScale], its rotation through
 * [WorldScale.ROTATION_RATE_PER_S] — and both problems are solved on a grid sized by the physics
 * ([solveGrid]) rather than by the map, so a world of another size gets the circulation its own
 * physics gives, and a map of another size gets the same ocean.
 *
 * See docs/DESIGN_LEDGER.md, G3, H4 and the ocean's circulation row.
 */
object OceanStage {

    /**
     * The neutral drag coefficient of the sea surface for a 10 m wind of 4 to 11 meters a second
     * (Large and Pond 1981, *J. Phys. Oceanogr.* 11, 324-336). [PressureWind.BELT_SPEED_MPS] is
     * taken as a representative 10 m wind inside that range: it is the zonal-mean surface wind of
     * the belts' centers, an assumption rather than a measured 10 m wind.
     */
    private const val DRAG_COEFFICIENT = 1.2e-3

    /** Density of sea water at the surface, in kilograms a cubic meter. */
    private const val SEAWATER_DENSITY_KG_PER_M3 = 1025.0

    /**
     * The depth of the wind-driven layer, in meters, whose mean velocity the stream function is.
     *
     * The subtropical gyres' main thermocline lies at 500 to 1,000 m (Luyten, Pedlosky and Stommel
     * 1983, *J. Phys. Oceanogr.* 13, 292-309), and the wind-driven transport is carried above it.
     * The shallow end, because what this layer's velocity is for is carrying the surface water's
     * heat, and the surface water moves with the upper part of the layer.
     */
    private const val WIND_DRIVEN_LAYER_DEPTH_M = 500.0

    /**
     * Earth's mean radius, in meters: only to derive figures from Earth's measurements, this
     * stage's [BOTTOM_DRAG_PER_S] and `OceanHeat.BAROCLINIC_WAVE_SPEED_M_PER_S`.
     */
    internal const val EARTH_MEAN_RADIUS_M = 6.371e6

    /** The latitude the Gulf Stream's width below is read at: the Florida Current and Cape Hatteras. */
    private const val GULF_STREAM_LATITUDE_DEGREES = 30.0

    /**
     * The e-folding width of a western boundary current, in meters: half of the roughly 100 km
     * over which the Gulf Stream's surface speed falls away from its core off Florida and Cape
     * Hatteras (Stommel 1965, *The Gulf Stream*; Halkin and Rossby 1985, *J. Phys. Oceanogr.* 15,
     * 1439-1452).
     */
    private const val GULF_STREAM_WIDTH_M = 50_000.0

    /**
     * The linear bottom-drag rate `r` of the wind-driven layer, per second.
     *
     * Stommel's layer is `δ_S = r/β`, so Earth's own western boundary current gives `r`: β at 30
     * degrees on Earth, `2Ω cos φ / a` = 1.98e-11 per meter-second, times [GULF_STREAM_WIDTH_M], is
     * 9.9e-7 per second, a spin-down time `1/r` of 11.7 days. Friction is a property of the water
     * and not of the planet's size, so this figure is carried to any world unchanged and its layer
     * width comes out of that world's own β.
     */
    val BOTTOM_DRAG_PER_S: Double = 2.0 * WorldScale.ROTATION_RATE_PER_S *
        cos(GULF_STREAM_LATITUDE_DEGREES * PI / 180.0) / EARTH_MEAN_RADIUS_M * GULF_STREAM_WIDTH_M

    /**
     * τ, the time the sea surface takes to relax to its latitude's temperature, in seconds.
     *
     * The energy balance's own mixed layer over its own surface exchange, one ruler for both:
     * 2.0e8 J/m²/K over 25 W/m²/K is 8.0e6 s, 93 days. This is a slab relaxing against air held
     * fixed, not a derived damping time of the coupled air and sea; Frankignoul and Hasselmann's
     * (1977, *Tellus* 29, 289-305) damping of sea-surface anomalies, two to six months, brackets it.
     */
    val RELAXATION_SECONDS: Double =
        EnergyBalance.MIXED_LAYER_HEAT_CAPACITY_J_PER_M2_C / EnergyBalance.SURFACE_EXCHANGE_W_PER_M2_C

    /**
     * How many cells of the solve grid span the narrowest Stommel layer.
     *
     * Two. The layer's velocity profile is `exp(-x/δ_S)`, and a central difference over a spacing Δ
     * reads it `sinh(q)/q` too fast, `q = Δ/δ_S`: 17.5% at one cell a layer, 4.2% at two. Two keeps
     * the boundary current's speed, and so how far its water gets in τ, within a twentieth.
     */
    private const val CELLS_ACROSS_STOMMEL_LAYER = 2.0

    /** Meters in a kilometer. */
    private const val METERS_PER_KM = 1_000.0

    /** A share of a fine cell below which an overlap is taken for an edge merely touched. */
    private const val TOUCHING_SHARE = 1e-6

    /** How far, in solve cells, a map cell looks for water when no corner around it is water. */
    private const val NEAREST_WATER_REACH_CELLS = 2

    /**
     * A sea with its temperature but without its currents: the base sea-surface temperature by
     * latitude and nothing else, no gyres and a zero anomaly everywhere.
     *
     * For the provisional climate, which runs before the ice is carved and only to say where the
     * ice is. See docs/DESIGN_LEDGER.md, H2, for the measured cost and the measured difference.
     */
    internal fun withoutCurrents(config: WorldGenConfig, sea: SeaLevelResult): OceanResult =
        generate(config.copy(ocean = config.ocean.copy(enabled = false)), sea)

    /**
     * Solves the surface circulation for a world and carries its temperature around it.
     *
     * [sea] supplies the land mask the gyres close against. The result's velocities are in meters a
     * second, its temperature in degrees Celsius, and its anomaly in degrees away from the mean of
     * the open water near its latitude (see [buildAnomaly]). With `OceanConfig.enabled` off, every
     * velocity and every anomaly is zero and the temperature is the bare latitude profile.
     *
     * A solve that fails, on the processor, fails the stage with an
     * [OceanCirculation.OceanSolveFailure]: the processor is the reference, and there is nothing
     * truer to fall back to.
     */
    fun generate(config: WorldGenConfig, sea: SeaLevelResult): OceanResult =
        generateOcean(config, sea) { stencil, start, passes ->
            OceanCirculation.relax(stencil, start, passes)
            start
        }

    /**
     * The same ocean, relaxed on [accelerator] when the reader has graphics acceleration on.
     *
     * The accelerator is asked only for relaxation passes; the stencils that go in and the residuals
     * that decide when to stop are the processor's own, so the two paths differ in arithmetic and
     * nothing else. A device that declines gets the reference passes instead, unless the generation
     * has been cancelled, which is asked before every batch either way.
     *
     * A solve relaxed on the device that fails the processor's own check (see [generate]) is solved
     * again on the processor from the start, as a device that declines is: the device's passes are a
     * way to the reference answer, and when they do not reach it the reference is still there.
     */
    suspend fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        accelerator: OceanAccelerator?
    ): OceanResult {
        // The one graphics switch the interface offers lives in the erosion section, and governs
        // every stage that can leave the processor rather than erosion alone.
        val device = if (config.erosion.acceleration == Acceleration.GPU) accelerator else null
        if (device != null) {
            try {
                return generateOcean(config, sea) { stencil, start, passes ->
                    currentCoroutineContext().ensureActive()
                    device.solve(stencil, start, passes)
                        ?: run {
                            // A device that gave up because the generation was cancelled must not
                            // hand the rest of the solve to the processor: ask before falling back.
                            currentCoroutineContext().ensureActive()
                            OceanCirculation.relax(stencil, start, passes)
                            start
                        }
                }
            } catch (failure: OceanCirculation.OceanSolveFailure) {
                // Falls through to the processor's solve below.
            }
        }
        return generateOcean(config, sea) { stencil, start, passes ->
            currentCoroutineContext().ensureActive()
            OceanCirculation.relax(stencil, start, passes)
            start
        }
    }

    /**
     * The two solves on the solve grid, before anything is carried to the map: what the tests read
     * to hold the solves to their own tolerances and to the analytic answers.
     */
    internal class Circulation(
        val cellsAcross: Int,
        val cellsDown: Int,
        val cellWidthMeters: Double,
        val cellHeightMeters: Double,
        val isWater: BooleanArray,
        /** ψ, square meters a second. */
        val stream: FloatArray,
        val eastwardMps: FloatArray,
        val northwardMps: FloatArray,
        /** Degrees Celsius, zero on land. */
        val temperatureC: FloatArray,
        val flow: OceanCirculation.Solution,
        val heat: OceanCirculation.Solution,
        /** The year's mean wind stress on the sea, newtons a square meter, eastward and northward. */
        val stress: Stress,
        /**
         * The Ekman layer's vertical velocity at its base, meters a second, positive upward: the
         * divergence of its transport ([upwellingMps]). Zero on land, and everywhere with
         * `OceanConfig.upwelling` off.
         */
        val upwellingMps: FloatArray
    )

    private inline fun generateOcean(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        relax: (OceanStencil, FloatArray, Int) -> FloatArray
    ): OceanResult {
        val cellsAcross = config.width
        val cellsDown = config.height
        val velocityX = FloatField(cellsAcross, cellsDown)
        val velocityY = FloatField(cellsAcross, cellsDown)
        val temperature = FloatField(cellsAcross, cellsDown)
        val anomaly = FloatField(cellsAcross, cellsDown)

        // The same energy balance the climate stage reads, solved again here rather than passed
        // in: this stage runs first, and a sea whose temperature came off a different curve from
        // the land's would put back the two rulers S1 spent a chunk removing.
        val zonal = ClimateStage.zonalClimate(config, sea)
        fillBaseTemperature(config, sea, zonal, temperature)
        if (!config.ocean.enabled) return OceanResult(velocityX, velocityY, temperature, anomaly)

        return onTheMap(config, sea, zonal, circulate(config, sea, zonal, relax), temperature)
    }

    /**
     * [solved] carried to the map's cells, over [baseTemperature], the bare latitude profile on the
     * water, with its anomaly built from it: the stage's result.
     */
    internal fun onTheMap(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        zonal: ZonalClimate,
        solved: Circulation,
        baseTemperature: FloatField
    ): OceanResult {
        val cellsDown = config.height
        val velocityX = FloatField(config.width, cellsDown)
        val velocityY = FloatField(config.width, cellsDown)
        val anomaly = FloatField(config.width, cellsDown)
        carryToMap(config, sea, solved, velocityX, velocityY, baseTemperature)
        val profileC = FloatArray(cellsDown) { row -> zonal.waterC(ClimateStage.latitudeOf(row, cellsDown), Season.ANNUAL) }
        buildAnomaly(config, sea, baseTemperature, profileC, anomaly)
        return OceanResult(velocityX, velocityY, baseTemperature, anomaly)
    }

    /** The circulation and its heat on the solve grid, under the year's stress. See [Circulation]. */
    internal inline fun circulate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        zonal: ZonalClimate,
        relax: (OceanStencil, FloatArray, Int) -> FloatArray
    ): Circulation {
        val (across, down) = solveGrid(config.scale)
        return circulateUnder(config, sea, zonal, annualStress(config, sea, across, down), relax)
    }

    /**
     * The circulation and its heat on the solve grid under a given [stress] on that grid, the
     * year's mean in [circulate] and whatever a guard asks for elsewhere.
     */
    internal inline fun circulateUnder(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        zonal: ZonalClimate,
        stress: Stress,
        relax: (OceanStencil, FloatArray, Int) -> FloatArray
    ): Circulation {
        val (across, down) = solveGrid(config.scale)
        val widthMeters = config.scale.worldWidthKm * METERS_PER_KM / across
        val heightMeters = worldHeightMeters(config.scale) / down
        val isWater = waterOn(config, sea, across, down)

        val flowLevels = OceanCirculation.levels(
            circulationStencil(config, curlForcing(stress, across, down, widthMeters, heightMeters), isWater, across, down),
            widthMeters, heightMeters, everyCellWater = true
        ) { coarseAcross, coarseDown, coarseWater ->
            circulationStencil(config, null, coarseWater, coarseAcross, coarseDown)
        }
        val flow = OceanCirculation.requireSolved(
            "the circulation",
            OceanCirculation.solve(
                flowLevels, FloatArray(across * down), OceanCirculation.RESIDUAL_TOLERANCE, poleIsWall = true,
                bodies = null, relax
            ),
            OceanCirculation.RESIDUAL_TOLERANCE
        )
        val stream = flow.values
        val eastward = FloatArray(across * down)
        val northward = FloatArray(across * down)
        OceanCirculation.velocities(flowLevels.first(), stream, widthMeters, heightMeters, eastward, northward)

        val targetC = FloatArray(across * down)
        for (row in 0 until down) {
            val latitudeC = zonal.waterC(ClimateStage.latitudeOf(row, down), Season.ANNUAL)
            for (cell in row * across until (row + 1) * across) {
                if (isWater[cell]) targetC[cell] = latitudeC
            }
        }
        val subsurfaceC = subsurfaceTemperatures(config, zonal, stress, isWater, across, down, widthMeters)
        val upwelling = if (config.ocean.upwelling) {
            upwellingMps(stress, isWater, across, down, widthMeters, heightMeters)
        } else FloatArray(across * down)
        val entrainment = entrainmentPerS(upwelling)
        val heatLevels = OceanCirculation.levels(
            OceanHeat.stencil(
                across, down, widthMeters, heightMeters, isWater, stream, targetC, RELAXATION_SECONDS, withTarget = true,
                diffusivityAt = { OceanHeat.diffusivity(it, config.scale.radiusMeters) },
                entrainmentPerS = entrainment, subsurfaceC = subsurfaceC
            ),
            widthMeters, heightMeters, everyCellWater = false
        ) { coarseAcross, coarseDown, coarseWater ->
            OceanHeat.stencil(
                coarseAcross, coarseDown,
                config.scale.worldWidthKm * METERS_PER_KM / coarseAcross, worldHeightMeters(config.scale) / coarseDown,
                coarseWater, resample(stream, across, down, coarseAcross, coarseDown),
                FloatArray(coarseAcross * coarseDown), RELAXATION_SECONDS, withTarget = false,
                diffusivityAt = { OceanHeat.diffusivity(it, config.scale.radiusMeters) },
                entrainmentPerS = coarseMean(entrainment, across, down, coarseAcross, coarseDown), subsurfaceC = null
            )
        }
        val heat = OceanCirculation.requireSolved(
            "the sea's heat",
            OceanCirculation.solveByKrylov(
                heatLevels, targetC.copyOf(), OceanCirculation.RESIDUAL_TOLERANCE, poleIsWall = false,
                OceanCirculation.waterBodies(isWater, across, down), relax = relax
            ),
            OceanCirculation.RESIDUAL_TOLERANCE
        )
        return Circulation(
            across, down, widthMeters, heightMeters, isWater, stream, eastward, northward, heat.values, flow, heat,
            stress, upwelling
        )
    }

    /**
     * The grid both problems are solved on, as columns and rows: square cells on the ground, two to
     * the narrowest Stommel layer.
     *
     * Sized by the physics and not by the map. The narrowest layer is `r/β` where β is largest, at
     * the equator, `2Ω/a`, so the rows are `π a / (r a / 4Ω)`, which is `4πΩ/r`: about 925 on any
     * planet that turns as Earth does, whatever its radius, since a larger planet has a wider
     * layer to resolve in proportion. Rounded up for the V-cycle, never coarser than asked.
     */
    internal fun solveGrid(scale: WorldScale): Pair<Int, Int> {
        val spacingMeters = narrowestStommelLayerMeters(scale) / CELLS_ACROSS_STOMMEL_LAYER
        val down = rowsForCycle(ceil(worldHeightMeters(scale) / spacingMeters).toInt())
        val across = (down / WorldScale.WORLD_HEIGHT_AS_SHARE_OF_WIDTH).roundToInt()
        return (across + (across and 1)) to down
    }

    /**
     * [rows] rounded up to a multiple of the power of two that halves it to at most sixteen, so the
     * V-cycle reaches a grid small enough to relax to convergence. Up, so the spacing is never
     * coarser than the one asked for; by at most one part in sixteen.
     */
    private fun rowsForCycle(rows: Int): Int {
        var step = 1
        while ((rows + step - 1) / step > OceanCirculation.COARSEST_ROWS * 2) step *= 2
        return (rows + step - 1) / step * step
    }

    /**
     * The narrowest Stommel layer anywhere, in meters: `r/β` where β is largest, at the equator,
     * `2Ω/a`. The subtropical gyres' western boundary currents reach the equator in this world's
     * belts, whose trades peak there.
     */
    fun narrowestStommelLayerMeters(scale: WorldScale): Double =
        BOTTOM_DRAG_PER_S / scale.planetaryVorticityGradientPerMeterSecond(0.0)

    private fun worldHeightMeters(scale: WorldScale): Double =
        scale.worldWidthKm * WorldScale.WORLD_HEIGHT_AS_SHARE_OF_WIDTH * METERS_PER_KM

    /**
     * Which cells of the solve grid are water: those every map cell they overlap is water.
     *
     * Conservative on purpose. The coast moves seaward by at most one solve cell, a few kilometers,
     * and in exchange a strip of land that is one map cell wide is always land on the solve grid
     * too, whichever grid is finer, so no current and no heat crosses it. A resampling of the coast
     * rather than a union of fixed blocks: the solve grid's cells do not line up with the map's.
     */
    internal fun waterOn(config: WorldGenConfig, sea: SeaLevelResult, across: Int, down: Int): BooleanArray {
        val cellsAcross = config.width
        val cellsDown = config.height
        val isWater = BooleanArray(across * down)
        val columnsPerCell = cellsAcross.toDouble() / across
        val rowsPerCell = cellsDown.toDouble() / down
        for (row in 0 until down) {
            val firstRow = floor(row * rowsPerCell + TOUCHING_SHARE).toInt()
            val lastRow = (ceil((row + 1) * rowsPerCell - TOUCHING_SHARE).toInt() - 1).coerceAtMost(cellsDown - 1)
            for (column in 0 until across) {
                val firstColumn = floor(column * columnsPerCell + TOUCHING_SHARE).toInt()
                val lastColumn = (ceil((column + 1) * columnsPerCell - TOUCHING_SHARE).toInt() - 1).coerceAtMost(cellsAcross - 1)
                var allWater = true
                for (mapRow in firstRow..lastRow) {
                    for (mapColumn in firstColumn..lastColumn) {
                        if (sea.isLand[mapRow * cellsAcross + mapColumn]) allWater = false
                    }
                }
                isWater[row * across + column] = allWater
            }
        }
        return isWater
    }

    /**
     * The circulation's problem on one grid: β per row and, on the finest grid, [forcing], the curl
     * of the stress over `ρ H` per cell ([curlForcing]); a coarse grid of the cycle has none.
     */
    private fun circulationStencil(
        config: WorldGenConfig,
        forcing: DoubleArray?,
        isWater: BooleanArray,
        across: Int,
        down: Int
    ): OceanStencil {
        val widthMeters = config.scale.worldWidthKm * METERS_PER_KM / across
        val heightMeters = worldHeightMeters(config.scale) / down
        val beta = DoubleArray(down) {
            config.scale.planetaryVorticityGradientPerMeterSecond(ClimateStage.latitudeOf(it, down).toDouble())
        }
        return OceanCirculation.stencil(
            across, down, widthMeters, heightMeters, isWater, beta, BOTTOM_DRAG_PER_S, forcing ?: DoubleArray(across * down)
        )
    }

    /** A wind stress on the solve grid, newtons a square meter, eastward and northward, per cell. */
    internal class Stress(val eastward: DoubleArray, val northward: DoubleArray)

    /**
     * The year's wind stress on the sea, on the solve grid, `ρ_air C_D |W| W`: the belts' annual
     * wind ([SurfaceBelts] with no migration), its meridional leg included, and with
     * `ClimateConfig.pressureWinds` on the regional wind of the annual pressure ([PressureWind]),
     * read bilinearly from the map.
     *
     * One annual pattern and not the mean of two half-years' stresses. The belts' zonal profile is
     * already the annual mean's shape, so migrating it by the tilt and averaging the two halves
     * would smooth it a second time: done, it left the westerlies' mean stress with two humps and
     * three zeros of its curl, and the westerlies' band flowing west on two standard seeds
     * (docs/DESIGN_LEDGER.md, 4b-1). The temperature the pressure is read off leaves out the
     * current anomaly, which this stage has not computed and could not have: the currents cannot
     * be forced by a wind their own warmth forced.
     */
    internal fun annualStress(config: WorldGenConfig, sea: SeaLevelResult, across: Int, down: Int): Stress {
        val cellsAcross = config.width
        val cellsDown = config.height
        val wind = if (config.climate.pressureWinds) {
            PressureWind.surfaceWind(config, sea, PressureWind.pressureAnomalyHpa(config, ClimateStage.buildTemperature(config, sea)))
        } else null
        val eastward = DoubleArray(across * down)
        val northward = DoubleArray(across * down)
        parallelChunks(0, down) { startRow, endRow ->
            for (row in startRow until endRow) {
                val latitude = ClimateStage.latitudeOf(row, down)
                val belts = SurfaceBelts.windMps(latitude, config.climate.meridionalWindShare)
                val mapRow = (row + 0.5f) * cellsDown / down - 0.5f
                for (column in 0 until across) {
                    var east = belts.eastwardMps.toDouble()
                    var north = belts.northwardMps.toDouble()
                    if (wind != null) {
                        val mapColumn = (column + 0.5f) * cellsAcross / across - 0.5f
                        east += sample(wind.eastwardMps, cellsAcross, cellsDown, mapColumn, mapRow)
                        north -= sample(wind.southwardMps, cellsAcross, cellsDown, mapColumn, mapRow)
                    }
                    val dragPerMeter = PressureWind.AIR_DENSITY_KG_PER_M3 * DRAG_COEFFICIENT * sqrt(east * east + north * north)
                    val cell = row * across + column
                    eastward[cell] = dragPerMeter * east
                    northward[cell] = dragPerMeter * north
                }
            }
        }
        return Stress(eastward, northward)
    }

    /**
     * The circulation's right-hand side per cell, `curl_z τ / (ρ H)`, per second squared: the
     * stress's curl by central differences in meters, columns wrapping and the edge rows one-sided.
     */
    private fun curlForcing(stress: Stress, across: Int, down: Int, widthMeters: Double, heightMeters: Double): DoubleArray {
        val forcing = DoubleArray(across * down)
        val perDensityDepth = 1.0 / (SEAWATER_DENSITY_KG_PER_M3 * WIND_DRIVEN_LAYER_DEPTH_M)
        for (row in 0 until down) {
            val rowNorth = (row - 1).coerceAtLeast(0)
            val rowSouth = (row + 1).coerceAtMost(down - 1)
            val northToSouthMeters = (rowSouth - rowNorth) * heightMeters
            for (column in 0 until across) {
                val columnEast = if (column + 1 == across) 0 else column + 1
                val columnWest = if (column == 0) across - 1 else column - 1
                val dStressNorthDx = (stress.northward[row * across + columnEast] -
                    stress.northward[row * across + columnWest]) / (2.0 * widthMeters)
                // y runs north, and the row to the north is the one above.
                val dStressEastDy = (stress.eastward[rowNorth * across + column] -
                    stress.eastward[rowSouth * across + column]) / northToSouthMeters
                forcing[row * across + column] = (dStressNorthDx - dStressEastDy) * perDensityDepth
            }
        }
        return forcing
    }

    /**
     * `r`, the friction of the wind-driven surface layer on the water beneath it, per second:
     * (2 days)⁻¹ on a 50 m layer, Zebiak and Cane's (1987, *Mon. Wea. Rev.* 115, 2262-2278). It is
     * what keeps the layer's transport finite where `f` vanishes, so the equator needs no case of
     * its own.
     */
    internal const val SURFACE_LAYER_FRICTION_PER_S: Double = 1.0 / (2.0 * 86_400.0)

    /**
     * `h`, the depth of the mixed layer an upwelling entrains into, meters: the energy balance's own
     * fifty-meter slab ([EnergyBalance.MIXED_LAYER_DEPTH_M]).
     */
    internal const val MIXED_LAYER_DEPTH_M: Double = EnergyBalance.MIXED_LAYER_DEPTH_M

    /**
     * The surface layer's transport under a stress, square meters a second, eastward and northward:
     * the damped Ekman balance `M = τ (r - i f) / (ρ (r² + f²))` in complex form, with `r`
     * [SURFACE_LAYER_FRICTION_PER_S]. Where `f` is much larger than `r` this is Ekman's `τ/(ρf)`
     * to the right of the wind in the north and to the left in the south; on the equator it runs
     * down the wind at `τ/(ρr)`.
     */
    internal fun ekmanTransport(stressEast: Double, stressNorth: Double, latitudeDegrees: Double): Pair<Double, Double> {
        val coriolis = 2.0 * WorldScale.ROTATION_RATE_PER_S * sin(latitudeDegrees * PI / 180.0)
        val friction = SURFACE_LAYER_FRICTION_PER_S
        val perDensity = 1.0 / (SEAWATER_DENSITY_KG_PER_M3 * (friction * friction + coriolis * coriolis))
        return (friction * stressEast + coriolis * stressNorth) * perDensity to
            (friction * stressNorth - coriolis * stressEast) * perDensity
    }

    /**
     * The upward velocity at the Ekman layer's base, meters a second, per cell of the solve grid:
     * the divergence of [ekmanTransport], counted on the cells' faces. A face's transport is the
     * mean of the two cells' either side of it, and none crosses a face with land on either side or
     * a pole, so what the wind drives offshore along a coast rises in the water cells beside it,
     * exactly as much as leaves them. The coast's upwelling, the open ocean's and the equator's are
     * one law.
     */
    internal fun upwellingMps(
        stress: Stress,
        isWater: BooleanArray,
        across: Int,
        down: Int,
        widthMeters: Double,
        heightMeters: Double
    ): FloatArray {
        val eastward = DoubleArray(across * down)
        val northward = DoubleArray(across * down)
        for (row in 0 until down) {
            val latitude = ClimateStage.latitudeOf(row, down).toDouble()
            for (cell in row * across until (row + 1) * across) {
                val (east, north) = ekmanTransport(stress.eastward[cell], stress.northward[cell], latitude)
                eastward[cell] = east
                northward[cell] = north
            }
        }
        val upward = FloatArray(across * down)
        for (row in 0 until down) {
            for (column in 0 until across) {
                val cell = row * across + column
                if (!isWater[cell]) continue
                val eastCell = row * across + if (column + 1 == across) 0 else column + 1
                val westCell = row * across + if (column == 0) across - 1 else column - 1
                val outEast = if (isWater[eastCell]) (eastward[cell] + eastward[eastCell]) / 2.0 else 0.0
                val inWest = if (isWater[westCell]) (eastward[cell] + eastward[westCell]) / 2.0 else 0.0
                val outNorth = if (row > 0 && isWater[cell - across]) (northward[cell] + northward[cell - across]) / 2.0 else 0.0
                val inSouth = if (row + 1 < down && isWater[cell + across]) (northward[cell] + northward[cell + across]) / 2.0 else 0.0
                upward[cell] = ((outEast - inWest) / widthMeters + (outNorth - inSouth) / heightMeters).toFloat()
            }
        }
        return upward
    }

    /**
     * The rate the rising water renews the mixed layer, per second: `w/h` where the layer's base
     * rises, and nothing where it sinks, since water leaving the layer downward leaves at the
     * layer's own temperature and changes nothing.
     */
    internal fun entrainmentPerS(upwellingMps: FloatArray): FloatArray =
        FloatArray(upwellingMps.size) { cell -> (maxOf(upwellingMps[cell].toDouble(), 0.0) / MIXED_LAYER_DEPTH_M).toFloat() }

    /**
     * A rate per unit area on a coarse grid of the cycle: the mean of the fine cells each covers, as
     * a residual is carried down, so a coastal strip one fine cell wide keeps its total on every
     * grid, where a bilinear read would fall between its samples.
     */
    private fun coarseMean(fine: FloatArray, fineAcross: Int, fineDown: Int, coarseAcross: Int, coarseDown: Int): FloatArray {
        val coarse = OceanCirculation.restrict(DoubleArray(fine.size) { fine[it].toDouble() }, fineAcross, fineDown, coarseAcross, coarseDown)
        return FloatArray(coarse.size) { coarse[it].toFloat() }
    }

    /**
     * `T_sub`, the temperature of the water an upwelling brings up, degrees Celsius, at [latitude]:
     * the winter mixed layer's temperature at the latitude where that water's isopycnal outcrops
     * ([outcropLatitude]), from the energy balance's coldest month.
     *
     * **A closure, not a solved thermocline.** This generator's ocean has one layer and no vertical
     * structure, so the water beneath the mixed layer is not computed; what is prescribed is where it
     * came from. The ventilated thermocline (Luyten, Pedlosky and Stommel 1983, *J. Phys. Oceanogr.*
     * 13, 292-309) fills the subtropical gyre's upper thermocline with water that left the surface in
     * winter, poleward, at the latitude where its isopycnal meets the surface, and carried the winter
     * mixed layer's temperature down its pathway with it (Stommel 1979, *PNAS* 76, 3051-3055, the
     * mixed layer's "demon", is why it is the winter layer's). Mixing along the way is not modeled,
     * so the water arrives at its outcrop's temperature.
     *
     * **Density is temperature's alone**: salinity is taken to be uniform along each isopycnal's
     * path, so an isopycnal is an isotherm. On Earth salinity compensates part of the temperature on
     * many of these surfaces, so an isopycnal outcrops a little away from the isotherm's; the
     * closure's outcrop latitudes carry that error.
     */
    internal fun subsurfaceTemperatureC(zonal: ZonalClimate, latitude: Float): Float =
        zonal.waterC(outcropLatitude(latitude), Season.WINTER)

    /**
     * Where the isopycnal through [depthMeters] beneath [latitude] meets the surface, in degrees,
     * same hemisphere: Luyten, Pedlosky and Stommel's eastern-boundary geometry. The depth is the
     * upwelling's source depth, [UPWELLING_SOURCE_DEPTH_M], unless another is asked for.
     *
     * In their ventilated zone potential vorticity `f/h` is kept along each subducted layer's path
     * and the total depth of the moving layers is kept along it too, so the interface under a layer
     * that outcropped at `f_o` lies at depth `(1 - f/f_o) H` wherever `f` is, with `H` the moving
     * layers' depth at the eastern boundary, taken as this ocean's [WIND_DRIVEN_LAYER_DEPTH_M].
     * Water found at depth `D` therefore outcropped where `f_o = f / (1 - D/H)`: at 100 m under a
     * 500 m thermocline, 38.7 degrees beneath 30.
     *
     * The subtropical gyre's isopycnals outcrop no further poleward than the gyre does, which is
     * where the belts' Ekman pumping changes sign, [SurfaceBelts.WESTERLY_BELT_CENTRE_DEGREES]; water
     * whose isopycnal would outcrop beyond it is the edge's own winter water, and poleward of that
     * edge, in the subpolar gyre, upwelled water is the latitude's own winter water. The mapping is
     * continuous there. Toward the equator `f` vanishes and so does the mapping's reach: this
     * geometry has no equatorial thermocline, and the equator takes the second closure of
     * [subsurfaceTemperatures] instead.
     */
    internal fun outcropLatitude(latitude: Float, depthMeters: Double = UPWELLING_SOURCE_DEPTH_M): Float {
        val fromEquator = abs(latitude)
        val gyreEdge = SurfaceBelts.WESTERLY_BELT_CENTRE_DEGREES
        if (fromEquator >= gyreEdge) return latitude
        val outcropSine = sin(fromEquator * PI / 180.0) / (1.0 - depthMeters / WIND_DRIVEN_LAYER_DEPTH_M)
        val outcrop = if (outcropSine >= sin(gyreEdge * PI / 180.0)) gyreEdge.toDouble() else asin(outcropSine) * 180.0 / PI
        return (if (latitude < 0f) -outcrop else outcrop).toFloat()
    }

    /**
     * `D`, the depth the water a coastal or open-ocean upwelling brings up comes from, meters: 100,
     * near the middle of the 41 to 182 m Weeks, Losch and Tziperman's (2023, arXiv 2312.04706)
     * experiments span from weak wind and strong stratification to strong wind and weak
     * stratification, the dependence He and Mahadevan (2021, *J. Geophys. Res. Oceans* 126) scale.
     * One depth for every upwelling: the model has no stratification to vary it with.
     */
    internal const val UPWELLING_SOURCE_DEPTH_M = 100.0

    /**
     * `T_sub` for every cell of the solve grid, degrees Celsius, zero on land: the ventilated
     * thermocline's water ([subsurfaceTemperatureC]) everywhere, and on the equator the water under
     * a thermocline the trades tilt ([equatorialDeepShare]).
     *
     * **The equator, a second closure.** The eastern-boundary geometry has no equator: there `f`
     * vanishes and the water at the source depth is the equator's own. Earth's cold tongue is two
     * things together. The trades push the warm upper layer west, so the thermocline is deep in the
     * west and shallow in the east, and the east's upwelling reaches through it; and the water
     * beneath it is subtropical water the subtropical cells carried there, subducted in the trades
     * and fed to the equatorial thermocline (McCreary and Lu 1994, *J. Phys. Oceanogr.* 24,
     * 466-497). So on the equator the risen water is the warm layer's, the closure's own value
     * there, where the source depth lies inside the layer, and the subducted water's
     * ([subtropicalCellC]) where it lies beneath, blended by [equatorialDeepShare]. The equatorial
     * dynamics hold within the equatorial deformation radius `sqrt(c/2β)`, 4a's own
     * ([OceanHeat.deformationRadiusMeters] at the equator, 131 km at this generator's radius), so
     * the blend's weight falls off as a Gaussian of that width from the equator, and a planet of
     * another size has its own band.
     */
    internal fun subsurfaceTemperatures(
        config: WorldGenConfig,
        zonal: ZonalClimate,
        stress: Stress,
        isWater: BooleanArray,
        across: Int,
        down: Int,
        widthMeters: Double
    ): FloatArray {
        val subsurfaceC = FloatArray(across * down)
        val deepShare = equatorialDeepShare(stress, isWater, across, down, widthMeters)
        val deepC = subtropicalCellC(zonal)
        val bandMeters = OceanHeat.deformationRadiusMeters(0.0, config.scale.radiusMeters)
        for (row in 0 until down) {
            val latitude = ClimateStage.latitudeOf(row, down)
            val ventilatedC = subsurfaceTemperatureC(zonal, latitude)
            val fromEquatorMeters = latitude * config.scale.metersPerDegreeLatitude
            val equatorialWeight = exp(-0.5 * (fromEquatorMeters / bandMeters) * (fromEquatorMeters / bandMeters))
            for (column in 0 until across) {
                val cell = row * across + column
                if (!isWater[cell]) continue
                val share = deepShare[column]
                subsurfaceC[cell] = if (share.isNaN()) ventilatedC
                else (ventilatedC + equatorialWeight * share * (deepC - ventilatedC)).toFloat()
            }
        }
        return subsurfaceC
    }

    /**
     * How much of the water rising at each column of the equator comes from beneath the
     * thermocline, 0 to 1, or NaN where the equator is land.
     *
     * The thermocline's depth `h` follows the trades' stress along each equatorial basin, from the
     * reduced-gravity balance `g' ∂h/∂x = τ_x / (ρ h)`: integrated from the basin's western shore,
     * `h² = H² + (2 / (ρ g')) (I(x) - Ī)`, with `I` the stress integrated eastward and `Ī` its
     * mean over the basin, so the basin's mean `h²` is [EQUATORIAL_THERMOCLINE_DEPTH_M]'s square.
     * An easterly stress makes `I` fall eastward, and the thermocline shoals toward the east; where
     * `h²` would fall below zero the layer has surfaced, and `h` is zero there. The share drawn from
     * beneath is `(1 + tanh((D - h) / δ)) / 2`, with `D` [UPWELLING_SOURCE_DEPTH_M] and `δ`
     * [THERMOCLINE_HALF_THICKNESS_M]: a thermocline of finite thickness rather than a step, so the
     * cold tongue has no edge the grid could put there. A sea that runs round the world on the
     * equator has no western shore and no tilt, and its thermocline is `H` all the way round.
     */
    internal fun equatorialDeepShare(stress: Stress, isWater: BooleanArray, across: Int, down: Int, widthMeters: Double): FloatArray {
        val share = FloatArray(across) { Float.NaN }
        val northRow = down / 2 - 1
        val southRow = down / 2
        val onEquator = BooleanArray(across) { isWater[northRow * across + it] && isWater[southRow * across + it] }
        val stressEast = DoubleArray(across) { (stress.eastward[northRow * across + it] + stress.eastward[southRow * across + it]) / 2.0 }
        val perStressLength = 2.0 / (SEAWATER_DENSITY_KG_PER_M3 * EQUATORIAL_REDUCED_GRAVITY_M_PER_S2)
        val firstLand = (0 until across).firstOrNull { !onEquator[it] }
        if (firstLand == null) {
            for (column in 0 until across) share[column] = deepShareAt(EQUATORIAL_THERMOCLINE_DEPTH_M)
            return share
        }
        var offset = 1
        while (offset <= across) {
            if (!onEquator[(firstLand + offset) % across]) { offset++; continue }
            val runStart = offset
            while (offset <= across && onEquator[(firstLand + offset) % across]) offset++
            val length = offset - runStart
            val integral = DoubleArray(length)
            var running = 0.0
            for (k in 0 until length) {
                val stressHere = stressEast[(firstLand + runStart + k) % across]
                integral[k] = running + stressHere * widthMeters / 2.0
                running += stressHere * widthMeters
            }
            val meanIntegral = integral.average()
            for (k in 0 until length) {
                val depthSquared = EQUATORIAL_THERMOCLINE_DEPTH_M * EQUATORIAL_THERMOCLINE_DEPTH_M +
                    perStressLength * (integral[k] - meanIntegral)
                share[(firstLand + runStart + k) % across] = deepShareAt(sqrt(maxOf(depthSquared, 0.0)))
            }
        }
        return share
    }

    /** The share of rising water from beneath a thermocline at [depthMeters]; see [equatorialDeepShare]. */
    private fun deepShareAt(depthMeters: Double): Float =
        ((1.0 + tanh((UPWELLING_SOURCE_DEPTH_M - depthMeters) / THERMOCLINE_HALF_THICKNESS_M)) / 2.0).toFloat()

    /**
     * The temperature of the water beneath the equatorial thermocline, degrees Celsius: subtropical
     * water the subtropical cells carried there (McCreary and Lu 1994), the winter mixed layer
     * where its isopycnal outcrops.
     *
     * A closure. The isopycnal is the one at the thermocline's base,
     * [EQUATORIAL_THERMOCLINE_BASE_M], the water that feeds the Equatorial Undercurrent and lies
     * beneath the whole of the transition the upwelling draws across; the subtropical cells take it
     * from beneath the latitude where the trades' own Ekman pumping into the thermocline is
     * strongest, [TRADE_SUBDUCTION_DEGREES]. Mapped through the ventilated thermocline's
     * eastern-boundary geometry ([outcropLatitude]; Luyten, Pedlosky and Stommel 1983), 200 m
     * beneath 15 degrees under a 500 m thermocline outcropped where `sin φ_o = sin 15° / 0.6`, at
     * 25.5 degrees, and the water arrives at that latitude's winter temperature. The isopycnal at
     * the source depth instead, 100 m beneath 15, outcropped at 18.9, too near the equator to be
     * the undercurrent's water: on Earth the water beneath the eastern thermocline lies some ten
     * degrees under the surface above it.
     */
    internal fun subtropicalCellC(zonal: ZonalClimate): Float =
        zonal.waterC(outcropLatitude(TRADE_SUBDUCTION_DEGREES, EQUATORIAL_THERMOCLINE_BASE_M), Season.WINTER)

    /**
     * Where the subtropical cells subduct, in degrees: where the trades' Ekman pumping is strongest,
     * half-way across the trade belt, 15. The belts' trade stress goes as `-cos²(3φ)`, whose
     * derivative, and so whose curl, is greatest in magnitude at `6φ = 90` degrees.
     */
    internal const val TRADE_SUBDUCTION_DEGREES = SurfaceBelts.TRADE_BELT_EDGE_DEGREES / 2f

    /**
     * The equatorial thermocline's mean depth, meters: 150, the mean depth of Zebiak and Cane's
     * (1987, *Mon. Wea. Rev.* 115, 2262-2278) reduced-gravity upper layer of the tropical Pacific.
     * A closure for a basin mean, which the trades then tilt.
     */
    internal const val EQUATORIAL_THERMOCLINE_DEPTH_M = 150.0

    /**
     * `g'`, the reduced gravity across the equatorial thermocline, meters a second squared: `c²/H`,
     * 4a's first baroclinic wave speed ([OceanHeat.BAROCLINIC_WAVE_SPEED_M_PER_S], 2.64 m/s from
     * Chelton's equatorial deformation radius) over [EQUATORIAL_THERMOCLINE_DEPTH_M], 0.046. It is
     * the density contrast across the thermocline, 4.8 kg/m³ of sea water's 1,025, in the form a
     * one-layer model holds it; taken from the wave speed so the layer's waves and its tilt agree.
     */
    internal const val EQUATORIAL_REDUCED_GRAVITY_M_PER_S2: Double =
        OceanHeat.BAROCLINIC_WAVE_SPEED_M_PER_S * OceanHeat.BAROCLINIC_WAVE_SPEED_M_PER_S / EQUATORIAL_THERMOCLINE_DEPTH_M

    /**
     * Half the thermocline's thickness, meters: the `δ` of [equatorialDeepShare]'s `tanh`, 25, so
     * the transition from the warm layer's water to the subducted water spans about 50 m of source
     * depth. A stated transition, a third of [EQUATORIAL_THERMOCLINE_DEPTH_M], not a measured
     * thickness; it smooths the blend and sets how sharply the tongue's western edge falls off.
     */
    internal const val THERMOCLINE_HALF_THICKNESS_M = 25.0

    /**
     * The equatorial thermocline's base, meters: its mean depth [EQUATORIAL_THERMOCLINE_DEPTH_M]
     * and the whole transition beneath it, two of [THERMOCLINE_HALF_THICKNESS_M], 200.
     */
    internal const val EQUATORIAL_THERMOCLINE_BASE_M = EQUATORIAL_THERMOCLINE_DEPTH_M + 2 * THERMOCLINE_HALF_THICKNESS_M

    /** A field on one grid read bilinearly at every cell center of another covering the same map. */
    private fun resample(field: FloatArray, fromAcross: Int, fromDown: Int, toAcross: Int, toDown: Int): FloatArray {
        val result = FloatArray(toAcross * toDown)
        for (row in 0 until toDown) {
            val fromRow = (row + 0.5f) * fromDown / toDown - 0.5f
            for (column in 0 until toAcross) {
                val fromColumn = (column + 0.5f) * fromAcross / toAcross - 0.5f
                result[row * toAcross + column] = sample(field, fromAcross, fromDown, fromColumn, fromRow)
            }
        }
        return result
    }

    /** A per-cell field bilinearly at a fractional position between cell centers; columns wrap, rows clamp. */
    private fun sample(field: FloatArray, cellsAcross: Int, cellsDown: Int, column: Float, row: Float): Float {
        val left = floor(column).toInt()
        val acrossBlend = column - left
        val westColumn = ((left % cellsAcross) + cellsAcross) % cellsAcross
        val eastColumn = if (westColumn + 1 == cellsAcross) 0 else westColumn + 1
        val clampedRow = row.coerceIn(0f, (cellsDown - 1).toFloat())
        val above = minOf(clampedRow.toInt(), cellsDown - 1)
        val below = minOf(above + 1, cellsDown - 1)
        val downBlend = clampedRow - above
        val top = field[above * cellsAcross + westColumn] * (1f - acrossBlend) + field[above * cellsAcross + eastColumn] * acrossBlend
        val bottom = field[below * cellsAcross + westColumn] * (1f - acrossBlend) + field[below * cellsAcross + eastColumn] * acrossBlend
        return top * (1f - downBlend) + bottom * downBlend
    }

    /**
     * The solve grid's currents and temperature on the map's cells: the currents read bilinearly,
     * zero on the map's land; the temperature read bilinearly over the water corners alone,
     * renormalized, and where none of the four is water, from the nearest water cell within
     * [NEAREST_WATER_REACH_CELLS]; failing that the cell keeps its latitude's temperature.
     */
    private fun carryToMap(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        solved: Circulation,
        velocityX: FloatField,
        velocityY: FloatField,
        temperature: FloatField
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        val across = solved.cellsAcross
        val down = solved.cellsDown
        for (row in 0 until cellsDown) {
            val gridRow = (row + 0.5f) * down / cellsDown - 0.5f
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (sea.isLand[cell]) continue
                val gridColumn = (column + 0.5f) * across / cellsAcross - 0.5f
                velocityX.data[cell] = sample(solved.eastwardMps, across, down, gridColumn, gridRow)
                velocityY.data[cell] = -sample(solved.northwardMps, across, down, gridColumn, gridRow)
                val carried = sampleWater(solved.temperatureC, solved.isWater, across, down, gridColumn, gridRow)
                if (!carried.isNaN()) temperature.data[cell] = carried
            }
        }
    }

    /**
     * A field defined on water, read bilinearly at a fractional position with the land corners
     * left out and the weights renormalized over the water ones; where all four are land, the
     * nearest water cell's value within [NEAREST_WATER_REACH_CELLS], by distance in cells; NaN
     * beyond that.
     */
    private fun sampleWater(field: FloatArray, isWater: BooleanArray, across: Int, down: Int, column: Float, row: Float): Float {
        val left = floor(column).toInt()
        val acrossBlend = column - left
        val westColumn = ((left % across) + across) % across
        val eastColumn = if (westColumn + 1 == across) 0 else westColumn + 1
        val clampedRow = row.coerceIn(0f, (down - 1).toFloat())
        val above = minOf(clampedRow.toInt(), down - 1)
        val below = minOf(above + 1, down - 1)
        val downBlend = clampedRow - above
        var sum = 0f
        var weight = 0f
        fun add(cell: Int, cornerWeight: Float) {
            if (!isWater[cell] || cornerWeight <= 0f) return
            sum += field[cell] * cornerWeight
            weight += cornerWeight
        }
        add(above * across + westColumn, (1f - acrossBlend) * (1f - downBlend))
        add(above * across + eastColumn, acrossBlend * (1f - downBlend))
        add(below * across + westColumn, (1f - acrossBlend) * downBlend)
        add(below * across + eastColumn, acrossBlend * downBlend)
        if (weight > 0f) return sum / weight
        var nearest = Float.NaN
        var nearestDistance = Float.MAX_VALUE
        val centerColumn = (column + 0.5f).toInt()
        val centerRow = (row + 0.5f).toInt()
        for (rowOffset in -NEAREST_WATER_REACH_CELLS..NEAREST_WATER_REACH_CELLS) {
            val candidateRow = centerRow + rowOffset
            if (candidateRow < 0 || candidateRow >= down) continue
            for (columnOffset in -NEAREST_WATER_REACH_CELLS..NEAREST_WATER_REACH_CELLS) {
                val candidateColumn = ((centerColumn + columnOffset) % across + across) % across
                val cell = candidateRow * across + candidateColumn
                if (!isWater[cell]) continue
                val distance = (candidateRow - row) * (candidateRow - row) +
                    (centerColumn + columnOffset - column) * (centerColumn + columnOffset - column)
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearest = field[cell]
                }
            }
        }
        return nearest
    }

    /**
     * Fills [temperature] with the bare latitude profile over water, in degrees Celsius, leaving
     * land untouched.
     */
    internal fun fillBaseTemperature(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        zonal: ZonalClimate,
        temperature: FloatField
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        for (row in 0 until cellsDown) {
            val latitudeTemperatureC = zonal.waterC(ClimateStage.latitudeOf(row, cellsDown), Season.ANNUAL)
            for (column in 0 until cellsAcross) {
                if (!sea.isLand[row * cellsAcross + column]) {
                    temperature.data[row * cellsAcross + column] = latitudeTemperatureC
                }
            }
        }
    }

    /**
     * Fills [anomaly] with each water cell's departure, in degrees Celsius, from the mean
     * temperature of the open water near its latitude. Land is left at zero. [profileC] is the
     * energy balance's sea-surface temperature for each map row, the profile the heat relaxes to.
     *
     * The departure from the zonal mean and not from the latitude profile, so the energy balance's
     * own meridional transport, which is the ocean's and the air's together, is not counted a
     * second time: the anomaly moves heat along a latitude and leaves its mean close to where it
     * was.
     *
     * Close to, not exactly: the currents' own zonal mean, the water's mean departure from the
     * profile, is taken over a band of latitude rather than one row, a Gaussian whose standard
     * deviation is the heat's own length `sqrt(K τ)`: the distance the eddies spread the water's
     * heat in the time the air takes to reset it, 140 km at 45 degrees and, at this generator's
     * radius of 1,910 km, 320 km at the equator (see [OceanHeat.diffusivity] and
     * [RELAXATION_SECONDS]). The water's temperature cannot change
     * faster than that across latitude, so a one-row mean that does is the coastline's cells
     * entering and leaving the row, not the water: on 969495 at 2048 the one-row mean fell 0.1
     * degrees a row, against the water's own 0.04, over the five rows where fifty coastal cells
     * left, and that step ran straight across every ocean on the map as a line along the row.
     * Putting each row's mean back to zero would put the line back, since the step is what the
     * row's mean is made of, so it is not done. What it leaves, on seeds 7 and 42 at 512 and 1024
     * and on 969495 at 2048: each row's mean anomaly 0.09 to 0.14 degrees root mean square and at
     * most 0.32 to 0.64; and on the four standard worlds at 512 the whole ocean's, by area, 0.012 to
     * 0.015 below zero. `OceanCurrentTest` holds the heat those stand for under a chosen tolerance
     * of 2% of the energy balance's own transport: they carry 0.03 to 1.27% of it.
     *
     * Only the departure is averaged over the band, and the profile is added back row by row, so
     * the profile's curvature is not averaged into the reference: averaged whole, the temperature
     * itself put each row's mean 0.3 degrees root mean square off zero and the whole ocean's 0.1.
     */
    internal fun buildAnomaly(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        temperature: FloatField,
        profileC: FloatArray,
        anomaly: FloatField
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        val rowHeightMeters = worldHeightMeters(config.scale) / cellsDown
        val departureSumC = DoubleArray(cellsDown)
        val waterCells = IntArray(cellsDown)
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!sea.isLand[cell]) {
                    departureSumC[row] += temperature.data[cell] - profileC[row]
                    waterCells[row]++
                }
            }
        }
        for (row in 0 until cellsDown) {
            if (waterCells[row] == 0) continue
            val latitude = ClimateStage.latitudeOf(row, cellsDown).toDouble()
            val spreadMeters = sqrt(OceanHeat.diffusivity(latitude, config.scale.radiusMeters) * RELAXATION_SECONDS)
            val spreadRows = spreadMeters / rowHeightMeters
            val reachRows = ceil(ZONAL_MEAN_REACH_IN_SPREADS * spreadRows).toInt()
            var weightedSumC = 0.0
            var weightedCells = 0.0
            for (other in maxOf(0, row - reachRows)..minOf(cellsDown - 1, row + reachRows)) {
                val rowsAway = (other - row) / spreadRows
                val weight = exp(-0.5 * rowsAway * rowsAway)
                weightedSumC += weight * departureSumC[other]
                weightedCells += weight * waterCells[other]
            }
            val zonalMeanC = profileC[row] + (weightedSumC / weightedCells).toFloat()
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!sea.isLand[cell]) anomaly.data[cell] = temperature.data[cell] - zonalMeanC
            }
        }
    }

    /**
     * How far, in standard deviations, [buildAnomaly]'s Gaussian band reaches: a normal curve
     * holds all but 0.27% of its weight within three.
     */
    private const val ZONAL_MEAN_REACH_IN_SPREADS = 3.0
}
