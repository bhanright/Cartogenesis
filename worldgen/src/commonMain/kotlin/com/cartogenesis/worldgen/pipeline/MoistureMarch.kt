package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp

/**
 * The rain: water carried along the wind in kilograms per square meter, taken up from the sea and
 * the ground, rained out where the column cannot keep it, and conserved between the two.
 *
 * # What is solved
 *
 * The steady state of the column-water budget on the sphere,
 *
 *     div(W V) = E - P,
 *
 * with `W` the column's water (kg m⁻², which is millimeters), `V` the surface wind, `E` what the
 * sea and the ground put in and `P` what rains. The zonal wind is carried at one speed,
 * [TRANSPORT_SPEED_MPS], east or west as each cell's own wind blows; the meridional wind is each
 * cell's own, in meters a second. Marched column by column along the zonal wind, the steady
 * equation is a march in space and not in time: crossing a column of ground width
 * `dx = cellWidth cos(latitude)` takes `dt = dx / U`, every source and sink is charged over that
 * `dt`, and the water a row carries across a column's side is a mass flux, `U W cellHeight`, the
 * same for every row because a row's side is the same length at every latitude. So the parcels of
 * different rows add without weights, and the water a face between two rows passes, `v W` times
 * the face's length `cellWidth cos(latitude of the face)`, is in the same unit as the parcels:
 *
 *     sigma = (v / U) cos(latitude of the face) (cellWidth / cellHeight)
 *
 * of the donor's parcel per column, donor-cell upwind, out of one row and into the other. That is
 * the transport, and it is conservative by construction: what one row loses through a face the
 * other gains, in kilograms a second, whatever the two rows' time steps (docs/DESIGN_LEDGER.md,
 * C1b, for the clock it replaced).
 *
 * # Two sweeps, and the bank between them
 *
 * A column-by-column march has to run one way. A cell whose wind blows east is marched by the
 * eastward sweep and one whose wind blows west by the westward one; every cell belongs to exactly
 * one. Water that crosses from a cell of one sweep into a cell of the other, through a face between
 * rows or where two zonal winds meet head on, is **banked** at the receiving cell and taken up by
 * the other sweep when it reaches it: in the same lap for water the eastward sweep hands west,
 * which runs first, and in the next for water the westward sweep hands east. The bank is storage,
 * the ledger counts it as storage, and at the march's steady state what enters it in one lap
 * leaves it in the next. No row is walled off from another, so a circulation belt's edge is not a
 * line the rain can see.
 *
 * # Positivity
 *
 * A row may give up more than its own water through its two faces in one column where the
 * meridional wind is fast against the zonal; the column's transport is then cut into as many
 * sub-steps as keep every parcel's outflow under its content, so no parcel goes negative and none
 * is clamped (the ledger counts both).
 *
 * # One season at a time, coupled through the year's rain
 *
 * Each half-year is marched on its own wind and temperatures. The ground's return couples them:
 * it is Budyko's share of the cell's **annual** rain, after the blur, against the annual
 * potential evaporation, which is what the rivers and lakes read too, so the surface budget, rain
 * equals the ground's return plus the runoff, closes cell by cell. The laps run both seasons in
 * step and update the return from the year's rain between laps.
 */
object MoistureMarch {

    /**
     * The lifetime of a column's water against rain, in days: the turnover time of the
     * atmosphere's water, its storage over its flux. Trenberth (1998) found 8.9 days from the
     * evaporation and 9.1 from the precipitation, and van der Ent and Tuinenburg (2017) derive
     * 8.9 +- 0.4 from the closed budget of Rodell and others (2015) and Trenberth and others'
     * (2011) storage, and dispute the 4 to 5 days of Laederach and Sodemann (2016), whose figure is
     * the median of a long-tailed distribution and not its mean. It is the baseline hazard of the
     * march's rain; the belts' descent modulates it and the orographic and saturation sinks (and
     * the convergence closure, when `ClimateConfig.convergenceRain` switches it on) are added to it,
     * so the turnover the world ends with is a result, which `RainAgainstEarthTest` diagnoses
     * rather than asserts.
     */
    const val RAIN_LIFETIME_DAYS = 8.9

    /**
     * The speed the march carries water along the zonal wind, in meters a second: the belts'
     * own, [PressureWind.BELT_SPEED_MPS]. One speed everywhere until the atmosphere gives a wind
     * per cell (docs/TODO.md, "Build the atmosphere").
     */
    const val TRANSPORT_SPEED_MPS = PressureWind.BELT_SPEED_MPS.toDouble()

    /**
     * Laps of both seasons round the planet. The first starts from air at four fifths of its
     * saturated column over dry ground and leaves a year's rain for the second's ground to give back; each later one
     * carries the return a lap further toward the year it belongs to. Ten is where the last lap's
     * storage changes by well under the uncertainty of Earth's own global rain on the standard
     * worlds and on the application's 1,024 rows, the bar `MoistureClosureTest` holds it to;
     * eight left 0.9 percent on one seed at 1,024 rows (docs/DESIGN_LEDGER.md, C1b).
     */
    const val LAPS = 10

    /** Seconds in a year: 365.25 days. */
    const val SECONDS_PER_YEAR = 3.15576e7

    /** Seconds in a day. */
    private const val SECONDS_PER_DAY = 86_400.0

    /**
     * The share of its saturated column the first lap's air starts at. A starting guess and no
     * more: the laps wash it out, and nearer the march's own humidity they have less to wash.
     */
    private const val INITIAL_HUMIDITY = 0.8

    /** What a season's march reads, every per-cell array row-major on the world's grid. */
    class Season(
        val warm: Boolean,
        /** The half-year's air temperature, degrees Celsius. */
        val airTemperatureC: FloatArray,
        /** The water's temperature at sea cells, current and all; ignored on land. */
        val seaSurfaceC: FloatArray,
        val seaIce: BooleanArray,
        /** The zonal wind's direction at each cell: +1 east, -1 west. */
        val zonalDirection: IntArray,
        /** The meridional wind, meters a second, positive toward the south (down the map). */
        val southwardMps: FloatArray,
        /** The belts' rain factor per row: [ClimateStage.seasonalBand]. */
        val beltRainFactorOfRow: FloatArray,
        /** The marine inversion's hold on each land cell's rain, 0..1, or null when it is off. */
        val inversionSuppression: FloatArray?,
        /** The half-year's mean sun at the top of the air, per row, MJ m⁻² day⁻¹. */
        val extraterrestrialOfRow: DoubleArray
    )

    /** What the march reads beyond the two seasons. */
    class Inputs(
        val config: WorldGenConfig,
        val isLand: BooleanArray,
        /** `SeaLevelResult.relativeElevation`, for the climb the orographic term reads. */
        val relativeElevation: FloatArray,
        /** Height above the sea in meters at land cells, zero at sea. */
        val elevationM: FloatArray,
        val warm: Season,
        val cold: Season,
        /** The relief share of the inversion's lid, `SeaLevelResult.relativeElevation` units. */
        val lidElevation: Float,
        /** The blur's width on the ground, the standard deviation of its Gaussian, kilometers. */
        val blurSigmaKm: Double
    )

    /** What the march hands back, every field row-major, millimeters a year. */
    class Result(
        val warmRainMm: FloatField,
        val coldRainMm: FloatField,
        /** The year's rain whose water last evaporated from land: the recycling numerator. */
        val landOriginRainMm: FloatField,
        /** The year's FAO-56 reference evapotranspiration at every cell. */
        val potentialEvapotranspirationMm: FloatField,
        /** The year's open-water evaporation at every cell. */
        val openWaterEvaporationMm: FloatField
    )

    /**
     * Marches both seasons [LAPS] times and returns the year's rain, blurred, with the potential
     * evaporation it was coupled to. [ledger], when handed in, is filled with every term; nothing
     * the march computes depends on it.
     */
    internal fun run(inputs: Inputs, ledger: MoistureLedger? = null): Result {
        val config = inputs.config
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val grid = Grid(config)
        val warm = SeasonMarch(inputs, inputs.warm, grid, keepCells = ledger != null)
        val cold = SeasonMarch(inputs, inputs.cold, grid, keepCells = ledger != null)
        val seasons = arrayOf(warm, cold)

        val annualRain = FloatField(cellsAcross, cellsDown)
        val potentialAnnual = FloatArray(cellCount)
        ledger?.millimetersPerYearPerUnit = grid.millimetersPerYearPerUnit

        for (lap in 0 until LAPS) {
            val recording = lap == LAPS - 1
            val laps = Array(2) { season -> ledger?.let { MoistureLedger.Lap(season == 0, lap) } }
            // The two seasons share nothing inside a lap but the ground's return, which was set
            // before it began, so they march side by side.
            parallelChunks(0, 2) { first, last ->
                for (season in first until last) seasons[season].lap(laps[season], recording)
            }
            laps.forEach { entry -> if (entry != null) ledger?.laps?.add(entry) }

            if (recording) continue
            // The year's rain, as the rivers will read it, and the potential evaporation the two
            // seasons' air sets: the ground's return for the next lap.
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (cell in startRow * cellsAcross until endRow * cellsAcross) {
                    annualRain.data[cell] = (warm.rainMmRowMajor(cell) + cold.rainMmRowMajor(cell)) * 0.5f
                }
            }
            SphereBlur.apply(config, annualRain, inputs.blurSigmaKm)
            warm.updateLandPotential()
            cold.updateLandPotential()
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (cell in startRow * cellsAcross until endRow * cellsAcross) {
                    if (!inputs.isLand[cell] || !config.climate.groundReturn) continue
                    val potential = (warm.potentialMmRowMajor(cell) + cold.potentialMmRowMajor(cell)) * 0.5f
                    val annualReturnMm = groundReturnMm(annualRain.data[cell], potential)
                    warm.setGroundReturn(cell, annualReturnMm, potential)
                    cold.setGroundReturn(cell, annualReturnMm, potential)
                }
            }
        }
        warm.finishPotentials()
        cold.finishPotentials()
        for (cell in 0 until cellCount) {
            potentialAnnual[cell] = (warm.potentialMmRowMajor(cell) + cold.potentialMmRowMajor(cell)) * 0.5f
        }

        val warmRain = warm.rainField()
        val coldRain = cold.rainField()
        val landOrigin = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) {
            landOrigin.data[cell] =
                (warm.landRainMmRowMajor(cell) + cold.landRainMmRowMajor(cell)) * 0.5f
        }
        if (ledger != null) {
            ledger.warmHalf = warm.cells
            ledger.coldHalf = cold.cells
        }
        SphereBlur.apply(config, warmRain, inputs.blurSigmaKm)
        SphereBlur.apply(config, coldRain, inputs.blurSigmaKm)
        SphereBlur.apply(config, landOrigin, inputs.blurSigmaKm)

        val potential = FloatField(cellsAcross, cellsDown, potentialAnnual.copyOf())
        val openWater = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) {
            openWater.data[cell] = (warm.openWaterMmRowMajor(cell) + cold.openWaterMmRowMajor(cell)) * 0.5f
        }

        if (ledger != null) {
            val finalRain = FloatField(cellsAcross, cellsDown)
            val sources = FloatField(cellsAcross, cellsDown)
            val residual = FloatField(cellsAcross, cellsDown)
            for (cell in 0 until cellCount) {
                finalRain.data[cell] = (warmRain.data[cell] + coldRain.data[cell]) * 0.5f
                sources.data[cell] = (warm.sourceMmRowMajor(cell) + cold.sourceMmRowMajor(cell)) * 0.5f
                if (inputs.isLand[cell]) {
                    val rain = finalRain.data[cell]
                    val returned = (warm.groundReturnMmRowMajor(cell) + cold.groundReturnMmRowMajor(cell)) * 0.5f
                    val runoff = rain * LakeWaterBalance.runoffShareOfRain(rain, potential.data[cell])
                    residual.data[cell] = rain - returned - runoff
                }
            }
            ledger.finalAnnualRainMm = finalRain
            ledger.finalAnnualSourcesMm = sources
            ledger.surfaceResidualMm = residual
        }
        return Result(warmRain, coldRain, landOrigin, potential, openWater)
    }

    /**
     * The share of a row's water the mid-latitude eddies trade through the face between two rows
     * at [faceLatitudeDegrees] in one column of the march, each way, on [config]'s grid.
     *
     * The mean meridional wind is not the whole of what carries water across the latitude lines:
     * poleward of the subtropics most of it is carried by the depressions of the storm track,
     * which stir a wet air mass and a dry one together without a mean wind between them. Written
     * as a diffusivity `K`, the same eddies' diffusivity for heat in the energy balance
     * ([EnergyBalance.eddyDiffusivityAt], in watts per square meter per kelvin per radian²) turned
     * into square meters a second by the air column's heat capacity and the planet's radius,
     * `K = D a² g / (p_s c_p)`: the energy balance carries the latent heat of that water already,
     * and the same eddies carry the water. About 1.2e6 m²/s under the polar floor and 2.2e6 at
     * the storm track's 50 degrees on Earth.
     *
     * In the march's units a diffusive flux `K dW/dy` through a face `cellWidth cos(face)` long,
     * over a row `cellHeight` tall, against the zonal throughflow `U cellHeight`, is
     * `K cellWidth cos(face) / (U cellHeight²)` of each side's water per column. This is what lets
     * water cross a circulation belt's edge, where the mean meridional wind is zero and the two
     * belts blow opposite ways: without it the edge is a line two air masses of different origin
     * meet along, and the rain draws it straight along a row.
     */
    fun eddyMixingShare(config: WorldGenConfig, faceLatitudeDegrees: Double): Double {
        val radius = config.scale.radiusMeters
        val diffusivity = EnergyBalance.eddyDiffusivityAt(faceLatitudeDegrees) * radius * radius *
            STANDARD_GRAVITY_MPS2 / (SEA_LEVEL_PRESSURE_PA * AIR_HEAT_CAPACITY_J_PER_KG_K)
        val cellWidthM = config.cellWidthKm * METERS_PER_KM
        val cellHeightM = config.cellHeightKm * METERS_PER_KM
        return diffusivity * cellWidthM * cos(faceLatitudeDegrees * PI / 180.0) /
            (TRANSPORT_SPEED_MPS * cellHeightM * cellHeightM)
    }

    /** Standard gravity, the standard atmosphere's surface pressure and dry air's heat capacity. */
    private const val STANDARD_GRAVITY_MPS2 = 9.80665
    private const val SEA_LEVEL_PRESSURE_PA = 101_325.0
    private const val AIR_HEAT_CAPACITY_J_PER_KG_K = 1004.0

    /**
     * The share of a column the march rains by its lifetime alone in crossing one cell of [row]
     * on [config]'s grid, before the belts' factor: the crossing time over the lifetime. A figure
     * per kilometer of ground and not per cell, which `GroundFiguresTest` holds on every planet.
     */
    fun lifetimeSharePerColumn(config: WorldGenConfig, row: Int): Double =
        Grid(config).secondsPerColumn[row] / (RAIN_LIFETIME_DAYS * SECONDS_PER_DAY)

    /**
     * What the ground under a cell gives back to the air in a year, in millimeters: Budyko's
     * (1974) evaporated share of [annualRainMm] against [potentialMm], the curve
     * [LakeWaterBalance.runoffShareOfRain] is the other side of. One function, so the march's
     * return and the rivers' runoff add to the rain exactly.
     */
    fun groundReturnMm(annualRainMm: Float, potentialMm: Float): Float =
        annualRainMm * (1f - LakeWaterBalance.runoffShareOfRain(annualRainMm, potentialMm))

    /** The grid's geometry as the march spends it. */
    private class Grid(config: WorldGenConfig) {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cosRow = DoubleArray(cellsDown) { cos(ClimateStage.latitudeOf(it, cellsDown) * PI / 180.0) }

        /** Seconds the transport takes to cross one column of each row. */
        val secondsPerColumn = DoubleArray(cellsDown) {
            config.cellWidthKm * METERS_PER_KM * cosRow[it] / TRANSPORT_SPEED_MPS
        }

        /**
         * The share of the donor's parcel a meridional wind of one meter a second passes through
         * the face below each row in one column: `cos(face) (cellWidth / cellHeight) / U`.
         */
        val faceSharePerMps = DoubleArray(cellsDown) { row ->
            val faceLatitude = 90.0 - 180.0 * (row + 1) / cellsDown
            cos(faceLatitude * PI / 180.0) * (config.cellWidthKm / config.cellHeightKm) / TRANSPORT_SPEED_MPS
        }

        /**
         * The share of a row's water the eddies exchange through the face below it in one column,
         * each way: [eddyMixingShare] at the face's latitude.
         */
        val faceMixing = DoubleArray(cellsDown) { row ->
            eddyMixingShare(config, 90.0 - 180.0 * (row + 1) / cellsDown)
        }

        /** See [MoistureLedger.millimetersPerYearPerUnit]. */
        val millimetersPerYearPerUnit =
            TRANSPORT_SPEED_MPS * SECONDS_PER_YEAR /
                (cellsAcross * config.cellWidthKm * METERS_PER_KM * cosRow.sum())
    }

    private const val METERS_PER_KM = 1_000.0

    /** Surfaces, as the march stores them per cell. */
    private const val LAND: Byte = 0
    private const val OPEN_SEA: Byte = 1
    private const val SEA_ICE: Byte = 2

    /**
     * One season's march: its precomputed fields, column-major so a column's rows are adjacent
     * in memory, and its state between laps.
     */
    private class SeasonMarch(
        val inputs: Inputs,
        val season: Season,
        val grid: Grid,
        keepCells: Boolean
    ) {
        val cellsAcross = grid.cellsAcross
        val cellsDown = grid.cellsDown
        val cellCount = cellsAcross * cellsDown
        val config = inputs.config

        // Column-major: cell (row, column) is column * cellsDown + row.
        val direction = ByteArray(cellCount)
        val surface = ByteArray(cellCount)
        val saturatedMm = FloatArray(cellCount)
        val airSaturationHumidity = FloatArray(cellCount)
        val seaSurfaceHumidity = FloatArray(cellCount)
        val lifetimeShare = FloatArray(cellCount)
        val convergenceShare = FloatArray(cellCount)
        val orographicShare = FloatArray(cellCount)
        val faceShare = FloatArray(cellCount)

        /** The ground's return this season, millimeters a second, land cells only. */
        val groundReturnRate = FloatArray(cellCount)

        // This lap's results, column-major.
        val rainMm = FloatArray(cellCount)
        val landRainMm = FloatArray(cellCount)
        val humidity = FloatArray(cellCount)
        val sourceMm = FloatArray(cellCount)

        /** The water each cell's parcel held as it left, column-major, for the other sweep to read. */
        val lastWater = FloatArray(cellCount)

        // Potential evaporation from the last lap's air, row-major, and its terms in the air's
        // humidity at each land cell, built once.
        val potentialMm = FloatArray(cellCount)
        val openWaterMm = FloatArray(cellCount)
        val potentialConstant = FloatArray(cellCount)
        val potentialPerRootHumidity = FloatArray(cellCount)
        val potentialPerHumidity = FloatArray(cellCount)

        // The march's state: one parcel per row and sweep, and the bank of each cell.
        val eastParcel = DoubleArray(cellsDown)
        val eastParcelLand = DoubleArray(cellsDown)
        val westParcel = DoubleArray(cellsDown)
        val westParcelLand = DoubleArray(cellsDown)
        val bank = DoubleArray(cellCount)
        val bankLand = DoubleArray(cellCount)

        // Working space for one column.
        val arriving = DoubleArray(cellsDown)
        val arrivingLand = DoubleArray(cellsDown)
        val snapshot = DoubleArray(cellsDown)
        val snapshotLand = DoubleArray(cellsDown)
        val active = BooleanArray(cellsDown)

        val cells: MoistureLedger.Cells? =
            if (keepCells) MoistureLedger.Cells(cellsAcross, cellsDown) else null

        /**
         * The eddies' exchange between the rows of one column ([eddyMixingShare]). Between two rows
         * this sweep marches, an implicit diffusion step over each run of them, which is stable
         * however large the share and moves only what one row gives the next. Between a row this
         * sweep marches and one the other sweep does, the other row's water is the one it left
         * there when it last passed ([lastWater]), and the wetter side sends the share of the
         * difference a backward step of two-reservoir diffusion moves, `share / (1 + 2 share)`,
         * banked at the receiver: never more than half the difference, so nothing goes negative,
         * and only the difference, so the two sweeps do not hand the same water back and forth a
         * lap at a time.
         */
        private fun mixAcrossRows(base: Int) {
            arriving.copyInto(snapshot)
            arrivingLand.copyInto(snapshotLand)
            for (face in 0 until cellsDown - 1) {
                val above = face
                val below = face + 1
                if (active[above] == active[below]) continue
                val mine = if (active[above]) above else below
                val theirs = if (active[above]) below else above
                val difference = snapshot[mine] - lastWater[base + theirs]
                if (difference <= 0.0) continue
                val mixing = grid.faceMixing[face]
                val moved = difference * mixing / (1.0 + 2.0 * mixing)
                val movedLand = if (snapshot[mine] > 0.0) moved * snapshotLand[mine] / snapshot[mine] else 0.0
                arriving[mine] -= moved
                arrivingLand[mine] -= movedLand
                bank[base + theirs] += moved
                bankLand[base + theirs] += movedLand
            }
            // Within each run of this sweep's rows, implicitly.
            var start = 0
            while (start < cellsDown) {
                if (!active[start]) { start++; continue }
                var end = start
                while (end + 1 < cellsDown && active[end + 1]) end++
                if (end > start) {
                    diffuseImplicitly(start, end, arriving)
                    diffuseImplicitly(start, end, arrivingLand)
                }
                start = end + 1
            }
        }

        /**
         * One backward-Euler step of the flux-form diffusion over rows [first]..[last] of [water],
         * in place, by the Thomas algorithm: `w - share_up (w_up - w) - share_down (w_down - w) =
         * w_before`. Conservative because each face's flux enters one row's equation with each
         * sign, and positive because the matrix is an M-matrix.
         */
        private fun diffuseImplicitly(first: Int, last: Int, water: DoubleArray) {
            val count = last - first + 1
            val upper = thomasUpper
            val solved = thomasSolved
            var previousUpper = 0.0
            var previousSolved = 0.0
            for (index in 0 until count) {
                val row = first + index
                val shareUp = if (index > 0) grid.faceMixing[row - 1] else 0.0
                val shareDown = if (index < count - 1) grid.faceMixing[row] else 0.0
                val diagonal = 1.0 + shareUp + shareDown
                val sub = -shareUp
                val denominator = diagonal - sub * previousUpper
                upper[index] = -shareDown / denominator
                solved[index] = (water[row] - sub * previousSolved) / denominator
                previousUpper = upper[index]
                previousSolved = solved[index]
            }
            water[last] = solved[count - 1]
            for (index in count - 2 downTo 0) {
                val row = first + index
                water[row] = solved[index] - upper[index] * water[row + 1]
            }
        }

        private val thomasUpper = DoubleArray(cellsDown)
        private val thomasSolved = DoubleArray(cellsDown)

        /** `rho C_E U`, kilograms per square meter per second per unit of specific humidity. */
        val transferMassFlux = PressureWind.AIR_DENSITY_KG_PER_M3 *
            SurfaceEvaporation.evaporationTransferCoefficient(TRANSPORT_SPEED_MPS) * TRANSPORT_SPEED_MPS

        init {
            precompute()
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (row in startRow until endRow) {
                    val sun = season.extraterrestrialOfRow[row]
                    for (column in 0 until cellsAcross) {
                        val cell = row * cellsAcross + column
                        if (!inputs.isLand[cell]) continue
                        val terms = SurfaceEvaporation.referenceTerms(
                            season.airTemperatureC[cell].toDouble(), sun, inputs.elevationM[cell].toDouble()
                        )
                        potentialConstant[cell] = terms.constant.toFloat()
                        potentialPerRootHumidity[cell] = terms.perRootHumidity.toFloat()
                        potentialPerHumidity[cell] = terms.perHumidity.toFloat()
                    }
                }
            }
            for (row in 0 until cellsDown) {
                val start = INITIAL_HUMIDITY * saturatedMm[row]
                eastParcel[row] = if (direction[row] > 0) start else 0.0
                westParcel[row] = if (direction[(cellsAcross - 1) * cellsDown + row] < 0) start else 0.0
            }
        }

        private fun index(row: Int, column: Int) = column * cellsDown + row

        private fun precompute() {
            val climate = config.climate
            val lifetimeSeconds = RAIN_LIFETIME_DAYS * SECONDS_PER_DAY
            val lapseRate = climate.lapseRateCPerKm.toDouble()
            parallelChunks(0, cellsAcross) { startColumn, endColumn ->
                for (column in startColumn until endColumn) {
                    for (row in 0 until cellsDown) {
                        val cell = row * cellsAcross + column
                        val here = index(row, column)
                        direction[here] = if (season.zonalDirection[cell] >= 0) 1 else -1
                        surface[here] = when {
                            inputs.isLand[cell] -> LAND
                            season.seaIce[cell] -> SEA_ICE
                            else -> OPEN_SEA
                        }
                        val airC = season.airTemperatureC[cell].toDouble()
                        saturatedMm[here] = ColumnWater.saturatedColumnMm(airC, lapseRate).toFloat()
                        airSaturationHumidity[here] = ColumnWater.specificHumidity(
                            ColumnWater.saturationVaporPressureKpa(airC), ColumnWater.SEA_LEVEL_PRESSURE_KPA
                        ).toFloat()
                        if (surface[here] == OPEN_SEA) {
                            seaSurfaceHumidity[here] =
                                SurfaceEvaporation.seaSurfaceHumidity(season.seaSurfaceC[cell].toDouble()).toFloat()
                        }
                    }
                }
            }
            // Faces: the meridional wind at the face below each row, the mean of the two rows'.
            parallelChunks(0, cellsAcross) { startColumn, endColumn ->
                for (column in startColumn until endColumn) {
                    for (row in 0 until cellsDown - 1) {
                        val above = season.southwardMps[row * cellsAcross + column]
                        val below = season.southwardMps[(row + 1) * cellsAcross + column]
                        faceShare[index(row, column)] = ((above + below) * 0.5 * grid.faceSharePerMps[row]).toFloat()
                    }
                }
            }
            // The fixed shares: the lifetime under the belts' factor, the air's own convergence
            // and the climb, each per column crossed.
            parallelChunks(0, cellsAcross) { startColumn, endColumn ->
                for (column in startColumn until endColumn) {
                    val west = (column + cellsAcross - 1) % cellsAcross
                    val east = (column + 1) % cellsAcross
                    for (row in 0 until cellsDown) {
                        val cell = row * cellsAcross + column
                        val here = index(row, column)
                        val lid = if (inputs.isLand[cell]) lidFactor(cell) else 1.0
                        lifetimeShare[here] = (grid.secondsPerColumn[row] / lifetimeSeconds *
                            season.beltRainFactorOfRow[row] * lid).toFloat()

                        // The air's own budget per column: what flows in through the four sides less
                        // what flows out, as shares of the column. Zonally a cell always sends its
                        // column on and takes in each neighbor that blows toward it.
                        val fromWest = direction[index(row, west)] > 0
                        val fromEast = direction[index(row, east)] < 0
                        var inflow = (if (fromWest) 1.0 else 0.0) + (if (fromEast) 1.0 else 0.0)
                        var outflow = 1.0
                        var climbWeight = 0.0
                        var climbSum = 0.0
                        if (fromWest) { climbWeight += 1.0; climbSum += inputs.relativeElevation[row * cellsAcross + west] }
                        if (fromEast) { climbWeight += 1.0; climbSum += inputs.relativeElevation[row * cellsAcross + east] }
                        if (row > 0) {
                            val north = faceShare[index(row - 1, column)].toDouble()
                            if (north > 0.0) {
                                inflow += north
                                climbWeight += north
                                climbSum += north * inputs.relativeElevation[(row - 1) * cellsAcross + column]
                            } else outflow -= north
                        }
                        if (row < cellsDown - 1) {
                            val south = faceShare[here].toDouble()
                            if (south < 0.0) {
                                inflow -= south
                                climbWeight -= south
                                climbSum -= south * inputs.relativeElevation[(row + 1) * cellsAcross + column]
                            } else outflow += south
                        }
                        convergenceShare[here] =
                            if (config.climate.convergenceRain) (inflow - outflow).coerceAtLeast(0.0).toFloat() else 0f
                        if (inputs.isLand[cell] && climbWeight > 0.0) {
                            val rise = (inputs.relativeElevation[cell] - climbSum / climbWeight).coerceAtLeast(0.0)
                            orographicShare[here] = (config.climate.orographicStrength * rise * lid).toFloat()
                        }
                    }
                }
            }
        }

        /** The marine inversion's hold on a land cell's rain, as [MoistureBudget] defines it. */
        private fun lidFactor(cell: Int): Double {
            val suppression = season.inversionSuppression ?: return 1.0
            val lid = inputs.lidElevation
            val aboveLid =
                if (lid <= 0f) 1.0 else (inputs.relativeElevation[cell] / lid).toDouble().coerceIn(0.0, 1.0)
            return 1.0 - suppression[cell] * (1.0 - aboveLid)
        }

        /** Sets this season's ground return from the year's: in proportion to its potential. */
        fun setGroundReturn(cell: Int, annualReturnMm: Float, annualPotentialMm: Float) {
            val here = index(cell / cellsAcross, cell % cellsAcross)
            val shareOfYear = if (annualPotentialMm > 0f) potentialMm[cell] / annualPotentialMm else 1f
            groundReturnRate[here] = (annualReturnMm * shareOfYear / SECONDS_PER_YEAR).toFloat()
        }

        fun lap(entry: MoistureLedger.Lap?, recording: Boolean) {
            if (entry != null) {
                entry.storageAtStart = storage()
                entry.landStorageAtStart = landStorage()
            }
            for (step in 0 until cellsAcross) sweepColumn(step, 1, entry, recording)
            for (step in 0 until cellsAcross) sweepColumn(cellsAcross - 1 - step, -1, entry, recording)
            if (entry != null) {
                entry.storageAtEnd = storage()
                entry.landStorageAtEnd = landStorage()
                entry.bankAtEnd = bank.sum()
            }
        }

        private fun storage(): Double = eastParcel.sum() + westParcel.sum() + bank.sum()
        private fun landStorage(): Double = eastParcelLand.sum() + westParcelLand.sum() + bankLand.sum()

        private fun sweepColumn(column: Int, sweep: Int, entry: MoistureLedger.Lap?, recording: Boolean) {
            val base = column * cellsDown
            val parcel = if (sweep > 0) eastParcel else westParcel
            val parcelLand = if (sweep > 0) eastParcelLand else westParcelLand

            // What arrives: the row's parcel from the column behind and whatever the other sweep
            // banked here.
            var mostOutflow = 0.0
            for (row in 0 until cellsDown) {
                val here = base + row
                val isActive = direction[here].toInt() == sweep
                active[row] = isActive
                if (isActive) {
                    arriving[row] = parcel[row] + bank[here]
                    arrivingLand[row] = parcelLand[row] + bankLand[here]
                    bank[here] = 0.0
                    bankLand[here] = 0.0
                } else {
                    arriving[row] = 0.0
                    arrivingLand[row] = 0.0
                }
            }
            for (row in 0 until cellsDown) {
                if (!active[row]) continue
                var outflow = 0.0
                if (row < cellsDown - 1) outflow += faceShare[base + row].toDouble().coerceAtLeast(0.0)
                if (row > 0) outflow += (-faceShare[base + row - 1].toDouble()).coerceAtLeast(0.0)
                if (outflow > mostOutflow) mostOutflow = outflow
            }

            // Across the rows, donor-cell, in as many sub-steps as keep every parcel positive.
            val substeps = ceil(mostOutflow).toInt().coerceAtLeast(1)
            if (entry != null && substeps > entry.mostSubsteps) entry.mostSubsteps = substeps
            for (substep in 0 until substeps) {
                arriving.copyInto(snapshot)
                arrivingLand.copyInto(snapshotLand)
                for (face in 0 until cellsDown - 1) {
                    val share = faceShare[base + face].toDouble() / substeps
                    if (share == 0.0) continue
                    val donor = if (share > 0.0) face else face + 1
                    val receiver = if (share > 0.0) face + 1 else face
                    if (!active[donor]) continue
                    val moved = abs(share) * snapshot[donor]
                    val movedLand = abs(share) * snapshotLand[donor]
                    arriving[donor] -= moved
                    arrivingLand[donor] -= movedLand
                    if (active[receiver]) {
                        arriving[receiver] += moved
                        arrivingLand[receiver] += movedLand
                    } else {
                        bank[base + receiver] += moved
                        bankLand[base + receiver] += movedLand
                    }
                }
            }

            mixAcrossRows(base)

            // The column's physics, row by row, and the water it sends on.
            val downwindBase = ((column + sweep + cellsAcross) % cellsAcross) * cellsDown
            for (row in 0 until cellsDown) {
                if (!active[row]) {
                    parcel[row] = 0.0
                    parcelLand[row] = 0.0
                    continue
                }
                val here = base + row
                var water = arriving[row]
                var land = arrivingLand[row]
                if (entry != null) {
                    if (water < -NEGATIVE_SLACK_MM) entry.negativeParcels++
                    val slack = TRACER_SLACK * (abs(water) + 1.0)
                    if (land < -slack || land > water + slack) {
                        entry.tracerOutOfBounds++
                    }
                }
                val seconds = grid.secondsPerColumn[row]
                val kind = surface[here]
                val surfaceIndex = kind.toInt()

                // The rate sinks, each a share of the column per column crossed, charged together
                // so that the water they take is never more than the column held.
                val lifetime = lifetimeShare[here].toDouble()
                val convergence = convergenceShare[here].toDouble()
                val orographic = orographicShare[here].toDouble()
                val totalShare = lifetime + convergence + orographic
                var rained = 0.0
                if (totalShare > 0.0 && water > 0.0) {
                    rained = water * (1.0 - exp(-totalShare))
                    if (entry != null) {
                        entry.rain[MoistureLedger.Sink.LIFETIME.ordinal][surfaceIndex] += rained * lifetime / totalShare
                        entry.rain[MoistureLedger.Sink.CONVERGENCE.ordinal][surfaceIndex] += rained * convergence / totalShare
                        entry.rain[MoistureLedger.Sink.OROGRAPHIC.ordinal][surfaceIndex] += rained * orographic / totalShare
                    }
                }
                val landShareOfWater = if (water > 0.0) (land / water).coerceIn(0.0, 1.0) else 0.0
                var landRained = rained * landShareOfWater
                water -= rained
                land -= landRained

                // The sources: the sea by the bulk formula against the air the parcel brings, the
                // ground by its share of the year's return.
                val saturated = saturatedMm[here].toDouble()
                var added = 0.0
                if (kind == OPEN_SEA) {
                    val relative = if (saturated > 0.0) (water / saturated).coerceIn(0.0, 1.0) else 1.0
                    val evaporation = SurfaceEvaporation.bulkEvaporationKgPerM2S(
                        seaSurfaceHumidity[here].toDouble(),
                        relative * airSaturationHumidity[here],
                        transferMassFlux
                    )
                    added = evaporation * seconds
                    if (entry != null) entry.seaEvaporation += added
                } else if (kind == LAND) {
                    added = groundReturnRate[here].toDouble() * seconds
                    land += added
                    if (entry != null) entry.groundReturn += added
                }
                water += added

                // What the column cannot hold at its temperature rains here.
                var excess = 0.0
                if (water > saturated) {
                    excess = water - saturated
                    val excessLand = excess * (land / water).coerceIn(0.0, 1.0)
                    land -= excessLand
                    landRained += excessLand
                    water = saturated
                    if (entry != null) entry.rain[MoistureLedger.Sink.SATURATION.ordinal][surfaceIndex] += excess
                }
                if (land > water) land = water
                if (land < 0.0) land = 0.0
                if (entry != null) {
                    entry.landOriginRain += landRained
                    if (!water.isFinite() || !land.isFinite()) entry.nonFinite++
                }

                val rate = SECONDS_PER_YEAR / seconds
                rainMm[here] = ((rained + excess) * rate).toFloat()
                landRainMm[here] = (landRained * rate).toFloat()
                humidity[here] = if (saturated > 0.0) (water / saturated).toFloat() else 1f
                lastWater[here] = water.toFloat()
                if (recording) {
                    sourceMm[here] = (added * rate).toFloat()
                    cells?.let { record ->
                        val cell = row * cellsAcross + column
                        record.rain.data[cell] = rainMm[here]
                        record.columnWater.data[cell] = water.toFloat()
                        if (kind == LAND) record.groundReturn.data[cell] = (added * rate).toFloat()
                        if (kind == OPEN_SEA) record.seaEvaporation.data[cell] = (added * rate).toFloat()
                        if (totalShare > 0.0) {
                            record.convergenceRain.data[cell] = (rained * convergence / totalShare * rate).toFloat()
                        }
                    }
                }

                // On downwind: to the next cell of this sweep, or banked where the wind turns.
                val downwind = downwindBase + row
                if (direction[downwind].toInt() == sweep) {
                    parcel[row] = water
                    parcelLand[row] = land
                } else {
                    bank[downwind] += water
                    bankLand[downwind] += land
                    parcel[row] = 0.0
                    parcelLand[row] = 0.0
                }
            }
        }

        /**
         * The land's potential evapotranspiration this season's air sets, from the lap just run:
         * what the ground's return for the next lap is read against.
         */
        fun updateLandPotential() {
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (row in startRow until endRow) {
                    for (column in 0 until cellsAcross) {
                        val cell = row * cellsAcross + column
                        if (!inputs.isLand[cell]) continue
                        val humidity = humidity[index(row, column)].toDouble().coerceIn(0.0, 1.0)
                        potentialMm[cell] = ((potentialConstant[cell] + potentialPerRootHumidity[cell] *
                            kotlin.math.sqrt(humidity) + potentialPerHumidity[cell] * humidity)
                            .coerceAtLeast(0.0) * SurfaceEvaporation.DAYS_PER_YEAR_DOUBLE).toFloat()
                    }
                }
            }
        }

        /**
         * Both potential rates at every cell, land and sea, from the last lap's air: the fields
         * the climate hands on. The sea's cells take the rate land would have there, because the
         * shoreline moves under the erosion that reads it.
         */
        fun finishPotentials() {
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (row in startRow until endRow) {
                    val sun = season.extraterrestrialOfRow[row]
                    for (column in 0 until cellsAcross) {
                        val cell = row * cellsAcross + column
                        val airC = season.airTemperatureC[cell].toDouble()
                        val elevation = inputs.elevationM[cell].toDouble()
                        val humidity = humidity[index(row, column)].toDouble()
                        potentialMm[cell] = (SurfaceEvaporation.referenceTerms(airC, sun, elevation)
                            .mmPerDay(humidity) * SurfaceEvaporation.DAYS_PER_YEAR_DOUBLE).toFloat()
                        openWaterMm[cell] = (SurfaceEvaporation.openWaterTerms(airC, sun, elevation)
                            .mmPerDay(humidity) * SurfaceEvaporation.DAYS_PER_YEAR_DOUBLE).toFloat()
                    }
                }
            }
        }

        fun rainMmRowMajor(cell: Int): Float = rainMm[index(cell / cellsAcross, cell % cellsAcross)]
        fun landRainMmRowMajor(cell: Int): Float = landRainMm[index(cell / cellsAcross, cell % cellsAcross)]
        fun sourceMmRowMajor(cell: Int): Float = sourceMm[index(cell / cellsAcross, cell % cellsAcross)]
        fun groundReturnMmRowMajor(cell: Int): Float =
            if (surface[index(cell / cellsAcross, cell % cellsAcross)] == LAND) sourceMmRowMajor(cell) else 0f
        fun potentialMmRowMajor(cell: Int): Float = potentialMm[cell]
        fun openWaterMmRowMajor(cell: Int): Float = openWaterMm[cell]

        fun rainField(): FloatField {
            val field = FloatField(cellsAcross, cellsDown)
            for (cell in 0 until cellCount) field.data[cell] = rainMmRowMajor(cell)
            return field
        }
    }

    /**
     * How far the land-origin water may stand outside zero to the column's water before the ledger
     * counts it, as a share: a millionth, for the rounding of the doubles it is carried in.
     */
    private const val TRACER_SLACK = 1.0e-6

    /**
     * How far below zero a parcel may stand after transport before the ledger counts it negative,
     * in millimeters: a billionth, the rounding of a donor giving up all it holds through two
     * faces in doubles.
     */
    private const val NEGATIVE_SLACK_MM = 1.0e-9
}
