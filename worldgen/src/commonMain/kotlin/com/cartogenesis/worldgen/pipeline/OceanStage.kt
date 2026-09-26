package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.OceanHeatGrid
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class OceanResult(
    /** The surface current's eastward component, in metres a second; zero on land. */
    val velocityX: FloatField,
    /** Its southward component, in metres a second, rows running south as the map's do. */
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
 * change of the Coriolis parameter with latitude and friction at the bottom of the wind-driven
 * layer, inside whatever basins the coasts make. See [OceanCirculation] for the equation and its
 * discretisation and [OceanHeat] for how the temperature is carried. Every figure is a physical one
 * derived from the planet — its radius through [WorldScale], its rotation through
 * [WorldScale.ROTATION_RATE_PER_S] — so a world of another size gets the circulation its own
 * physics gives, and nothing here counts cells.
 *
 * See docs/DESIGN_LEDGER.md, G3, H4 and the ocean's row.
 */
object OceanStage {

    /** Where the trade-wind belt gives way to the westerlies, in degrees of latitude. */
    private const val TRADE_BELT_EDGE_DEGREES = 30f

    /** Where the westerlies give way to the polar easterlies, in degrees of latitude. */
    private const val WESTERLY_BELT_EDGE_DEGREES = 60f

    /** Middle of the westerly belt, where its eastward wind is strongest. */
    private const val WESTERLY_BELT_CENTRE_DEGREES = 45f

    /** Middle of the polar-easterly belt, where its westward wind is strongest. */
    private const val POLAR_BELT_CENTRE_DEGREES = 75f

    /** Degrees of cosine phase per degree of latitude inside the trade belt: 90 over 30. */
    private const val TRADE_PHASE_PER_DEGREE = 3.0

    /** The same for the two belts poleward of the trades: 90 over the 15 from centre to edge. */
    private const val MID_AND_POLAR_PHASE_PER_DEGREE = 6.0

    /**
     * How much weaker the polar easterlies blow than the trades and the westerlies: the polar cell
     * is the shallowest and weakest of the three.
     */
    private const val POLAR_EASTERLY_STRENGTH = 0.6f

    /**
     * The neutral drag coefficient of the sea surface for a 10 m wind of 4 to 11 metres a second
     * (Large and Pond 1981, *J. Phys. Oceanogr.* 11, 324-336). [PressureWind.BELT_SPEED_MPS] is
     * taken as a representative 10 m wind inside that range: it is the zonal-mean surface wind of
     * the belts' centres, an assumption rather than a measured 10 m wind.
     */
    private const val DRAG_COEFFICIENT = 1.2e-3

    /** Density of sea water at the surface, in kilograms a cubic metre. */
    private const val SEAWATER_DENSITY_KG_PER_M3 = 1025.0

    /**
     * The depth of the wind-driven layer, in metres, whose mean velocity the stream function is.
     *
     * The subtropical gyres' main thermocline lies at 500 to 1,000 m (Luyten, Pedlosky and Stommel
     * 1983, *J. Phys. Oceanogr.* 13, 292-309), and the wind-driven transport is carried above it.
     * The shallow end, because what this layer's velocity is for is carrying the surface water's
     * heat, and the surface water moves with the upper part of the layer.
     */
    private const val WIND_DRIVEN_LAYER_DEPTH_M = 500.0

    /** Earth's mean radius, in metres: only to derive [BOTTOM_DRAG_PER_S] from an Earth figure. */
    private const val EARTH_MEAN_RADIUS_M = 6.371e6

    /** The latitude the Gulf Stream's width below is read at: the Florida Current and Cape Hatteras. */
    private const val GULF_STREAM_LATITUDE_DEGREES = 30.0

    /**
     * The e-folding width of a western boundary current, in metres: half of the roughly 100 km
     * over which the Gulf Stream's surface speed falls away from its core off Florida and Cape
     * Hatteras (Stommel 1965, *The Gulf Stream*; Halkin and Rossby 1985, *J. Phys. Oceanogr.* 15,
     * 1439-1452).
     */
    private const val GULF_STREAM_WIDTH_M = 50_000.0

    /**
     * The linear bottom-drag rate `r` of the wind-driven layer, per second.
     *
     * Stommel's layer is `δ_S = r/β`, so Earth's own western boundary current gives `r`: β at 30
     * degrees on Earth, `2Ω cos φ / a` = 1.98e-11 per metre-second, times [GULF_STREAM_WIDTH_M], is
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
     * How many cells of the physics-sized grid span the narrowest Stommel layer.
     *
     * Two. The layer's velocity profile is `exp(-x/δ_S)`, and a central difference over a
     * spacing Δ reads it `sinh(q)/q` too fast, `q = Δ/δ_S`: 17.5% at one cell a layer, 4.2% at
     * two. Two keeps the boundary current's speed, and so how far its water gets in τ, within a
     * twentieth.
     */
    private const val CELLS_ACROSS_STOMMEL_LAYER = 2.0

    /**
     * Rows of the map-share grid per row of the map. Chosen so that at the 512 grid the map-share
     * grid's spacing is close to the physics-sized grid's on this planet.
     */
    private const val MAP_SHARE_ROWS_PER_MAP_ROW = 1.75

    /** What the last solve did, for the measurements. Not read by the generator. */
    var lastSolveReport: String = ""
        internal set

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
     * [sea] supplies the land mask the gyres close against. The result's velocities are in metres
     * a second, its temperature in degrees Celsius, and its anomaly in degrees away from the mean
     * of the same row's open water. With `OceanConfig.enabled` off, every velocity and every
     * anomaly is zero and the temperature is the bare latitude profile.
     */
    fun generate(config: WorldGenConfig, sea: SeaLevelResult): OceanResult =
        generateOcean(config, sea) { stencil, start, passes ->
            OceanCirculation.relax(stencil, start, passes)
            start
        }

    /**
     * The same circulation, solved on [accelerator] when the reader has graphics acceleration on.
     *
     * The accelerator is asked only for relaxation passes; the stencil that goes in and the residual
     * that decides when to stop are the processor's own, so the two paths differ in arithmetic and
     * nothing else. A device that declines gets the reference passes instead.
     */
    suspend fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        accelerator: OceanAccelerator?
    ): OceanResult = generateOcean(config, sea) { stencil, start, passes ->
        // The one graphics switch the interface offers lives in the erosion section, and governs
        // every stage that can leave the processor rather than erosion alone.
        val device = if (config.erosion.acceleration == Acceleration.GPU) accelerator else null
        device?.solve(stencil, start, passes)
            ?: run {
                // A device that gave up because the generation was cancelled must not hand the
                // whole solve to the processor: ask before falling back.
                currentCoroutineContext().ensureActive()
                OceanCirculation.relax(stencil, start, passes)
                start
            }
    }

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

        val wind = regionalWind(config, sea)
        val grid = heatGrid(config)
        val report = StringBuilder()

        val solveStarted = kotlin.time.TimeSource.Monotonic.markNow()
        val levels = gridLevels(config, sea, wind, grid.first, grid.second)
        val solvedStencil = levels.first()
        val solution = OceanCirculation.solve(levels, relax)
        val stream = solution.stream
        report.append("${grid.first}x${grid.second} levels ${levels.size} cycles ${solution.cycles} residual ${solution.relativeResidual} solve ${solveStarted.elapsedNow().inWholeMilliseconds} ms")
        run {
            // Diagnostic, temporary: the float floor of the residual and where the worst cell is.
            val res = OceanCirculation.residual(solvedStencil, stream)
            val largestF = OceanCirculation.largest(OceanCirculation.balanceOf(solvedStencil))
            var worstCell = 0; var worst = 0.0; var floor = 0.0; var psiMax = 0.0
            for (cell in res.indices) {
                if (!solvedStencil.isWater[cell]) continue
                val a = kotlin.math.abs(res[cell]); if (a > worst) { worst = a; worstCell = cell }
                val f = 2.0 * 1.1920929e-7 * kotlin.math.abs(stream[cell]) * solvedStencil.centreWeight[cell / grid.first]
                if (f > floor) floor = f
                psiMax = maxOf(psiMax, kotlin.math.abs(stream[cell].toDouble()))
            }
            report.append(" history " + solution.history.filterIndexed { i, _ -> i < 12 || i % 20 == 0 }.joinToString(",") { it.toString().take(7) })
            report.append(" [psi max $psiMax m2/s, float floor ${floor / largestF}, worst at row ${worstCell / grid.first} col ${worstCell % grid.first} psi ${stream[worstCell]}]")
        }
        val (across, down) = grid
        val gridWidthMetres = config.scale.worldWidthKm * METRES_PER_KM / across
        val gridHeightMetres = worldHeightMetres(config) / down
        val eastward = FloatArray(across * down)
        val northward = FloatArray(across * down)
        OceanCirculation.velocities(solvedStencil, stream, gridWidthMetres, gridHeightMetres, eastward, northward)

        val heatStarted = kotlin.time.TimeSource.Monotonic.markNow()
        val latitudeRowC = FloatArray(down) { zonal.waterC(ClimateStage.latitudeOf(it, down), Season.ANNUAL) }
        val gridTemperature = OceanHeat.carry(
            across, down, gridWidthMetres, gridHeightMetres, solvedStencil.isWater, eastward, northward,
            latitudeRowC, RELAXATION_SECONDS
        )
        report.append(" heat ${heatStarted.elapsedNow().inWholeMilliseconds} ms")
        lastSolveReport = report.toString()

        if (across == cellsAcross && down == cellsDown) {
            for (cell in 0 until cellsAcross * cellsDown) {
                if (sea.isLand[cell]) continue
                velocityX.data[cell] = eastward[cell]
                velocityY.data[cell] = -northward[cell]
                temperature.data[cell] = gridTemperature[cell]
            }
        } else {
            for (row in 0 until cellsDown) {
                val gridRow = (row + 0.5f) * down / cellsDown - 0.5f
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    if (sea.isLand[cell]) continue
                    val gridColumn = (column + 0.5f) * across / cellsAcross - 0.5f
                    velocityX.data[cell] = OceanHeat.sample(eastward, across, down, gridColumn, gridRow)
                    velocityY.data[cell] = -OceanHeat.sample(northward, across, down, gridColumn, gridRow)
                    val carried = sampleWater(gridTemperature, solvedStencil.isWater, across, down, gridColumn, gridRow)
                    if (!carried.isNaN()) temperature.data[cell] = carried
                }
            }
        }
        buildAnomaly(config, sea, temperature, anomaly)
        return OceanResult(velocityX, velocityY, temperature, anomaly)
    }

    private const val METRES_PER_KM = 1_000.0

    private fun worldHeightMetres(config: WorldGenConfig): Double =
        config.scale.worldWidthKm * WorldScale.WORLD_HEIGHT_AS_SHARE_OF_WIDTH * METRES_PER_KM

    /**
     * The grid the circulation is solved on and the heat carried on, as columns and rows.
     *
     * Temporary: the three forms the maintainer is choosing between. See `OceanConfig.heatGrid`.
     */
    internal fun heatGrid(config: WorldGenConfig): Pair<Int, Int> {
        val cellsAcross = config.width
        val cellsDown = config.height
        val heightShare = WorldScale.WORLD_HEIGHT_AS_SHARE_OF_WIDTH
        return when (config.ocean.heatGrid) {
            OceanHeatGrid.FINE -> cellsAcross to cellsDown
            OceanHeatGrid.PHYSICS, OceanHeatGrid.PHYSICS_ALWAYS -> {
                val spacingMetres = narrowestStommelLayerMetres(config.scale) / CELLS_ACROSS_STOMMEL_LAYER
                val fineWidthMetres = config.scale.cellWidthKm(cellsAcross) * METRES_PER_KM
                if (spacingMetres < fineWidthMetres && config.ocean.heatGrid == OceanHeatGrid.PHYSICS) {
                    cellsAcross to cellsDown
                } else {
                    val down = rowsForCycle((worldHeightMetres(config) / spacingMetres).roundToInt())
                    evenColumns(down, heightShare) to down
                }
            }
            OceanHeatGrid.MAP_SHARE -> {
                val down = (cellsDown * MAP_SHARE_ROWS_PER_MAP_ROW).roundToInt()
                evenColumns(down, heightShare) to down
            }
        }
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

    /** Twice the rows on the true-shape world, rounded to an even count so red-black holds on the cylinder. */
    private fun evenColumns(down: Int, heightShare: Double): Int {
        val across = (down / heightShare).roundToInt()
        return across + (across and 1)
    }

    /**
     * The narrowest Stommel layer anywhere, in metres: `r/β` where β is largest, at the equator,
     * `2Ω/a`. The subtropical gyres' western boundary currents reach the equator in this world's
     * belts, whose trades peak there.
     */
    fun narrowestStommelLayerMetres(scale: WorldScale): Double =
        BOTTOM_DRAG_PER_S / scale.planetaryVorticityGradientPerMetreSecond(0.0)

    /**
     * The grids of the V-cycle for [across] by [down], finest first: the solve grid with its
     * forcing, then each coarser one with none, since what a coarse grid solves for is a correction.
     */
    private fun gridLevels(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        wind: PressureWind.Vectors?,
        across: Int,
        down: Int
    ): List<OceanStencil> {
        val finest = stencilOn(config, wind, waterOn(config, sea, across, down), across, down, withForcing = true)
        return OceanCirculation.levels(
            finest, config.scale.worldWidthKm * METRES_PER_KM / across, worldHeightMetres(config) / down
        ) { coarseAcross, coarseDown, isWater ->
            stencilOn(config, wind, isWater, coarseAcross, coarseDown, withForcing = false)
        }
    }

    /** The regional surface wind on the full grid, metres a second, eastward and southward; null when the belts are the whole wind. */
    private fun regionalWind(config: WorldGenConfig, sea: SeaLevelResult): PressureWind.Vectors? {
        if (!config.climate.pressureWinds) return null
        // The temperature the pressure is read off leaves out the current anomaly, which this stage
        // has not computed and could not have: the currents cannot be forced by a wind forced by
        // the currents. The annual wind, because a gyre turns over in years.
        val pressureHpa =
            PressureWind.pressureAnomalyHpa(config, ClimateStage.buildTemperature(config, sea))
        return PressureWind.surfaceWind(config, sea, pressureHpa)
    }

    /**
     * The belts' zonal wind at a latitude as a share of [PressureWind.BELT_SPEED_MPS], positive
     * eastward: the trades westward at the equator, the westerlies at 45 degrees, the polar
     * easterlies at 75 at [POLAR_EASTERLY_STRENGTH].
     */
    internal fun beltWindShare(latitude: Float): Float {
        val absoluteLatitude = abs(latitude)
        return when {
            absoluteLatitude < TRADE_BELT_EDGE_DEGREES ->
                -cos(latitude * TRADE_PHASE_PER_DEGREE * PI / 180.0).toFloat()
            absoluteLatitude < WESTERLY_BELT_EDGE_DEGREES ->
                cos((absoluteLatitude - WESTERLY_BELT_CENTRE_DEGREES) * MID_AND_POLAR_PHASE_PER_DEGREE * PI / 180.0).toFloat()
            else ->
                -cos((absoluteLatitude - POLAR_BELT_CENTRE_DEGREES) * MID_AND_POLAR_PHASE_PER_DEGREE * PI / 180.0).toFloat() *
                    POLAR_EASTERLY_STRENGTH
        }
    }

    /**
     * Which cells of a grid are water: the full grid's own mask on the full grid, and elsewhere
     * the full grid's water share read bilinearly at the cell's centre, water from a half. A
     * resampling of the coast, so a coarser grid's coast follows the ground's rather than a union
     * of fixed blocks.
     */
    private fun waterOn(config: WorldGenConfig, sea: SeaLevelResult, across: Int, down: Int): BooleanArray {
        val cellsAcross = config.width
        val cellsDown = config.height
        if (across == cellsAcross && down == cellsDown) return BooleanArray(across * down) { !sea.isLand[it] }
        val waterShare = FloatArray(cellsAcross * cellsDown) { if (sea.isLand[it]) 0f else 1f }
        val isWater = BooleanArray(across * down)
        for (row in 0 until down) {
            val fineRow = (row + 0.5f) * cellsDown / down - 0.5f
            for (column in 0 until across) {
                val fineColumn = (column + 0.5f) * cellsAcross / across - 0.5f
                isWater[row * across + column] =
                    OceanHeat.sample(waterShare, cellsAcross, cellsDown, fineColumn, fineRow) >= HALF
            }
        }
        return isWater
    }

    private const val HALF = 0.5f

    /**
     * The Stommel problem on one grid: the stress of the belts' wind and the regional wind together,
     * `ρ_air C_D |W| W`, its curl by central differences in metres, and β per row.
     */
    private fun stencilOn(
        config: WorldGenConfig,
        wind: PressureWind.Vectors?,
        isWater: BooleanArray,
        across: Int,
        down: Int,
        withForcing: Boolean
    ): OceanStencil {
        val cellsAcross = config.width
        val cellsDown = config.height
        val widthMetres = config.scale.worldWidthKm * METRES_PER_KM / across
        val heightMetres = worldHeightMetres(config) / down
        val stressEast = DoubleArray(across * down)
        val stressNorth = DoubleArray(across * down)
        for (row in 0 until down) {
            val latitude = ClimateStage.latitudeOf(row, down)
            val beltEast = PressureWind.BELT_SPEED_MPS * beltWindShare(latitude)
            val fineRow = (row + 0.5f) * cellsDown / down - 0.5f
            for (column in 0 until across) {
                val cell = row * across + column
                var east = beltEast.toDouble()
                var north = 0.0
                if (wind != null) {
                    val fineColumn = (column + 0.5f) * cellsAcross / across - 0.5f
                    east += OceanHeat.sample(wind.eastwardMps, cellsAcross, cellsDown, fineColumn, fineRow)
                    north -= OceanHeat.sample(wind.southwardMps, cellsAcross, cellsDown, fineColumn, fineRow)
                }
                val speed = sqrt(east * east + north * north)
                val dragPerMetre = PressureWind.AIR_DENSITY_KG_PER_M3 * DRAG_COEFFICIENT * speed
                stressEast[cell] = dragPerMetre * east
                stressNorth[cell] = dragPerMetre * north
            }
        }
        val forcing = DoubleArray(across * down)
        val perDensityDepth = 1.0 / (SEAWATER_DENSITY_KG_PER_M3 * WIND_DRIVEN_LAYER_DEPTH_M)
        for (row in 0 until down) {
            val rowNorth = (row - 1).coerceAtLeast(0)
            val rowSouth = (row + 1).coerceAtMost(down - 1)
            val northToSouthMetres = (rowSouth - rowNorth) * heightMetres
            for (column in 0 until across) {
                val columnEast = if (column + 1 == across) 0 else column + 1
                val columnWest = if (column == 0) across - 1 else column - 1
                val dStressNorthDx = (stressNorth[row * across + columnEast] -
                    stressNorth[row * across + columnWest]) / (2.0 * widthMetres)
                // y runs north, and the row to the north is the one above.
                val dStressEastDy = (stressEast[rowNorth * across + column] -
                    stressEast[rowSouth * across + column]) / northToSouthMetres
                forcing[row * across + column] = (dStressNorthDx - dStressEastDy) * perDensityDepth
            }
        }
        val beta = DoubleArray(down) {
            config.scale.planetaryVorticityGradientPerMetreSecond(ClimateStage.latitudeOf(it, down).toDouble())
        }
        return OceanCirculation.stencil(
            across, down, widthMetres, heightMetres, isWater, beta, BOTTOM_DRAG_PER_S,
            if (withForcing) forcing else DoubleArray(across * down)
        )
    }

    /**
     * A field defined on water, read bilinearly at a fractional position with the land corners
     * left out and the weights renormalised over the water ones; NaN where all four are land.
     */
    private fun sampleWater(field: FloatArray, isWater: BooleanArray, across: Int, down: Int, column: Float, row: Float): Float {
        val left = floor(column).toInt()
        val acrossBlend = column - left
        var westColumn = left % across
        if (westColumn < 0) westColumn += across
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
        return if (weight > 0f) sum / weight else Float.NaN
    }

    /**
     * Fills [temperature] with the bare latitude profile over water, in degrees Celsius, leaving
     * land untouched.
     */
    private fun fillBaseTemperature(
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
     * temperature of the open water on its own row. Land is left at zero.
     */
    private fun buildAnomaly(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        temperature: FloatField,
        anomaly: FloatField
    ) {
        val cellsAcross = config.width
        val cellsDown = config.height
        for (row in 0 until cellsDown) {
            var temperatureSum = 0.0
            var waterCells = 0
            for (column in 0 until cellsAcross) {
                if (!sea.isLand[row * cellsAcross + column]) {
                    temperatureSum += temperature.data[row * cellsAcross + column]
                    waterCells++
                }
            }
            if (waterCells == 0) continue
            val rowMeanC = (temperatureSum / waterCells).toFloat()
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!sea.isLand[cell]) anomaly.data[cell] = temperature.data[cell] - rowMeanC
            }
        }
    }
}
