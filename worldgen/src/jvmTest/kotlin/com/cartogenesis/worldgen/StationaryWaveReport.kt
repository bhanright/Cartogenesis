package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.WaveFixtures.DEGREES
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.AtmosphereLevels
import com.cartogenesis.worldgen.pipeline.AtmosphereRemap
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.StationaryWaveModel
import com.cartogenesis.worldgen.pipeline.WaveDamping
import com.cartogenesis.worldgen.pipeline.WaveForcing
import com.cartogenesis.worldgen.pipeline.WaveResponse
import com.cartogenesis.worldgen.pipeline.ZonalBasicState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The stationary-wave model's audit: each benchmark on the atmosphere's ladder of grids (do the winds
 * and the vertical motion converge, and at what grid), on two, four and eight levels, with the damping
 * halved and doubled, and under the two published damping sets. Reports what it measures; the one bar
 * it holds is the grid rule's: at the rows [SphericalGrid.rowsForAtmosphere] gives Earth's planet,
 * every benchmark's fields are within [SphericalGrid.OPERATOR_TOLERANCE] of the finest grid's.
 */
class StationaryWaveReport {

    private val radius = WorldScale().radiusMeters

    /** The A1-2 ladder, every rung with 5-smooth longitudes. */
    private val ladder = listOf(60, 90, 120, 150, 180, 240)

    /** A benchmark as a function of the grid's rows, the levels and a damping factor. */
    private class Experiment(val name: String, val run: (rows: Int, levels: AtmosphereLevels, dampingFactor: Double) -> WaveResponse)

    private val experiments = listOf(
        Experiment("Gill's heating at rest") { rows, levels, factor -> WaveBenchmarks.gill(radius, rows, levels, factor).response },
        Experiment("Hoskins and Karoly's mountain in super-rotation") { rows, levels, factor ->
            val grid = SphericalGrid(rows, 2 * rows, radius)
            val state = ZonalBasicState.solidBodyRotation(grid, levels, radius * WaveBenchmarks.spin * WaveBenchmarks.SUPER_ROTATION_SHARE)
            StationaryWaveModel(state, WaveBenchmarks.hoskinsKarolyMountainDamping(levels, radius).scaledBy(factor))
                .solve(WaveForcing(surfaceHeightMeters = WaveBenchmarks.hoskinsKarolyMountain(grid)))
        },
        Experiment("Hoskins and Karoly's mountain on the jets") { rows, levels, factor ->
            val grid = SphericalGrid(rows, 2 * rows, radius)
            val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
            StationaryWaveModel(state, WaveBenchmarks.hoskinsKarolyMountainDamping(levels, radius).scaledBy(factor))
                .solve(WaveForcing(surfaceHeightMeters = WaveBenchmarks.hoskinsKarolyMountain(grid)))
        },
        Experiment("Hoskins and Karoly's heating at 45 N on the jets") { rows, levels, factor ->
            val grid = SphericalGrid(rows, 2 * rows, radius)
            val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
            StationaryWaveModel(state, WaveBenchmarks.hoskinsKarolyHeatDamping(levels, radius).scaledBy(factor))
                .solve(WaveBenchmarks.hoskinsKarolyHeating(grid, levels))
        },
        Experiment("Rodwell and Hoskins' monsoon on the jets") { rows, levels, factor ->
            val grid = SphericalGrid(rows, 2 * rows, radius)
            val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
            WaveBenchmarks.monsoon(state, WaveBenchmarks.rodwellHoskinsDamping(levels, radius).scaledBy(factor)).response
        }
    )

    /** [field] on [grid] read at the [target] grid's centers by the double Fourier series. */
    private fun carried(grid: SphericalGrid, field: DoubleArray, target: SphericalGrid): DoubleArray =
        AtmosphereRemap(target.columns, target.rows, grid).let { it.evaluate(it.coefficients(field), target.rows, target.columns) }

    /** The relative RMS by area of [first] against [reference] on [grid]. */
    private fun relative(grid: SphericalGrid, first: DoubleArray, reference: DoubleArray): Double {
        var difference = 0.0
        var norm = 0.0
        for (cell in first.indices) {
            val area = grid.cellAreaSquareMeters[cell / grid.columns]
            difference += (first[cell] - reference[cell]) * (first[cell] - reference[cell]) * area
            norm += reference[cell] * reference[cell] * area
        }
        return sqrt(difference / norm)
    }

    /** The lowest level's wind as its three Cartesian components, the 500 hPa omega and the surface pressure, carried to [target]. */
    private fun fieldsOn(response: WaveResponse, target: SphericalGrid): List<DoubleArray> {
        val grid = response.grid
        val bottom = response.levels.levelCount - 1
        val remap = AtmosphereRemap(target.columns, target.rows, grid)
        val (east, north) = remap.vectorToGround(response.eastward[bottom], response.northwardAtCenters(bottom))
        val omega = carried(grid, WaveFixtures.verticalMotionAt(response, 50_000.0), target)
        val pressure = carried(grid, response.surfacePressurePa, target)
        return listOf(DoubleArray(east.size) { east[it].toDouble() }, DoubleArray(north.size) { north[it].toDouble() }, omega, pressure)
    }

    /**
     * [solve]'s wind, 500 hPa omega and surface pressure on every rung against the finest, and each
     * error extrapolated to the exact answer's: a second-order error at `J` rows read against the
     * finest `F` is the exact one times `1 - (J / F)^2`. Returns the line and the worst extrapolated
     * error at the rule's rows, and the rows that error says the field needs for [SphericalGrid.OPERATOR_TOLERANCE].
     */
    private fun convergence(name: String, solve: (Int) -> WaveResponse): Pair<String, Double> {
        val ruleRows = SphericalGrid.rowsForAtmosphere(radius)
        val finest = ladder.last()
        val target = SphericalGrid(finest, 2 * finest, radius)
        val reference = fieldsOn(solve(finest), target)
        val line = StringBuilder("CONVERGENCE $name against $finest rows (wind, 500 hPa omega, surface pressure; extrapolated):")
        var atRule = Double.NaN
        for (rows in ladder.dropLast(1)) {
            val fields = fieldsOn(solve(rows), target)
            val windError = sqrt((relative(target, fields[0], reference[0]).let { it * it } + relative(target, fields[1], reference[1]).let { it * it }) / 2)
            val errors = listOf(windError, relative(target, fields[2], reference[2]), relative(target, fields[3], reference[3]))
            val toExact = 1 - (rows.toDouble() / finest).let { it * it }
            line.append(" $rows rows " + errors.joinToString(" ") { "%.2e".format(it) } + " (" + errors.joinToString(" ") { "%.2e".format(it / toExact) } + ");")
            if (rows == ruleRows) atRule = errors.maxOf { it } / toExact
        }
        val needed = ruleRows * sqrt(atRule / SphericalGrid.OPERATOR_TOLERANCE)
        line.append(" at the rule's $ruleRows rows %.2e, so %.0f rows for %.0f%%".format(atRule, needed, 100 * SphericalGrid.OPERATOR_TOLERANCE))
        return line.toString() to atRule
    }

    @Test
    fun `every benchmark's winds and vertical motion converge on the ladder of grids`() {
        val levels = AtmosphereLevels.equalMass(4)
        for (experiment in experiments) println(convergence(experiment.name) { rows -> experiment.run(rows, levels, 1.0) }.first)
    }

    /**
     * The forcing a world will give the model, through [WaveForcing.fromGround]: the grid rule holds
     * for it. The benchmarks above read their published forcings unspread, and the one of them on a
     * state with no critical line, Hoskins and Karoly's mountain in super-rotation, rings the longest;
     * its line says what it needs.
     */
    @Test
    fun `forcing from a rough map converges on the ladder when spread on the ground`() {
        val (columns, rows) = 1024 to 512
        val noise = com.cartogenesis.worldgen.noise.PerlinNoise(1981)
        val heating = FloatArray(columns * rows)
        val height = FloatArray(columns * rows)
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = column.toFloat() / columns
            val y = row.toFloat() / rows
            val isLand = noise.fbm(x * 4, y * 2, 3, 4, 2) > 0.05f
            heating[row * columns + column] = if (isLand) (1.0 / WaveFixtures.SECONDS_PER_DAY).toFloat() else 0f
            height[row * columns + column] = if (isLand) (2500f * (1f + 2f * noise.fbm(x * 16 + 7.3f, y * 8 + 1.9f, 7, 16, 8))).coerceAtLeast(0f) else 0f
        }
        val levels = AtmosphereLevels.equalMass(4)
        for (spread in listOf(false, true)) {
            val (line, atRule) = convergence("rough map, " + if (spread) "spread on the ground" else "the cells' mean") { coarseRows ->
                val grid = SphericalGrid(coarseRows, 2 * coarseRows, radius)
                val remap = AtmosphereRemap(columns, rows, grid)
                val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
                val forcing = if (spread) WaveForcing.fromGround(remap, levels, heating, height, WaveFixtures::deepProfile)
                else WaveForcing(WaveForcing.heatingFromColumn(levels, remap.areaMean(heating), WaveFixtures::deepProfile), remap.areaMean(height))
                StationaryWaveModel(state, WaveDamping.forWorlds(levels, state.surfaceDensity)).solve(forcing)
            }
            println(line)
            if (spread) assertTrue(atRule < SphericalGrid.OPERATOR_TOLERANCE, "forcing spread on the ground stands $atRule off at the rule's rows")
        }
    }

    @Test
    fun `the benchmarks on two levels and a boundary layer, four and eight`() {
        val rows = SphericalGrid.rowsForAtmosphere(radius)
        for ((name, levels) in listOf(
            "2+BL" to AtmosphereLevels.twoLevelsAndBoundaryLayer(), "4" to AtmosphereLevels.equalMass(4),
            "5" to AtmosphereLevels.equalMass(5), "8" to AtmosphereLevels.equalMass(8), "16" to AtmosphereLevels.equalMass(16)
        )) println("LEVELS $name: " + figures(rows, levels, 1.0))
    }

    /** [response]'s fields at fixed pressures, so responses on different levels compare: geopotential and eastward wind interpolated in log pressure between levels. */
    private fun atPressure(fields: Array<DoubleArray>, levels: AtmosphereLevels, pressurePa: Double): DoubleArray {
        val logs = levels.levelPressurePa.map { kotlin.math.ln(it) }
        val target = kotlin.math.ln(pressurePa)
        val upper = (0 until levels.levelCount - 1).lastOrNull { logs[it] <= target } ?: 0
        val share = (target - logs[upper]) / (logs[upper + 1] - logs[upper])
        return DoubleArray(fields[0].size) { (1 - share) * fields[upper][it] + share * fields[upper + 1][it] }
    }

    /**
     * The review's question, whether four levels are enough, as a convergence in the vertical: each
     * extratropical benchmark and the rough map on 2+BL, 4, 8 and 16 levels against 24, at fixed
     * pressures (the surface pressure, the 850 hPa wind and the 500 hPa geopotential and omega).
     */
    @Test
    fun `the responses converge in the vertical`() {
        val rows = 120
        val grid = SphericalGrid(rows, 2 * rows, radius)
        val counts = listOf("2+BL" to AtmosphereLevels.twoLevelsAndBoundaryLayer(), "4" to AtmosphereLevels.equalMass(4),
            "8" to AtmosphereLevels.equalMass(8), "16" to AtmosphereLevels.equalMass(16), "24" to AtmosphereLevels.equalMass(24))
        val cases: List<Pair<String, (AtmosphereLevels) -> WaveResponse>> = listOf(
            "Hoskins and Karoly's heating at 45 N" to { levels ->
                StationaryWaveModel(ZonalBasicState.jablonowskiWilliamson(grid, levels), WaveBenchmarks.hoskinsKarolyHeatDamping(levels, radius))
                    .solve(WaveBenchmarks.hoskinsKarolyHeating(grid, levels))
            },
            "Hoskins and Karoly's mountain on the jets" to { levels ->
                StationaryWaveModel(ZonalBasicState.jablonowskiWilliamson(grid, levels), WaveBenchmarks.hoskinsKarolyMountainDamping(levels, radius))
                    .solve(WaveForcing(surfaceHeightMeters = WaveBenchmarks.hoskinsKarolyMountain(grid)))
            },
            "Rodwell and Hoskins' monsoon" to { levels ->
                WaveBenchmarks.monsoon(ZonalBasicState.jablonowskiWilliamson(grid, levels), WaveBenchmarks.rodwellHoskinsDamping(levels, radius)).response
            },
            "the monsoon under the worlds' damping" to { levels ->
                val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
                StationaryWaveModel(state, WaveDamping.forWorlds(levels, state.surfaceDensity)).solve(WaveBenchmarks.rodwellHoskinsHeating(grid, levels))
            }
        )
        for ((name, run) in cases) {
            val readings = counts.map { (_, levels) ->
                val response = run(levels)
                val bottom = levels.levelCount - 1
                listOf(
                    response.surfacePressurePa,
                    atPressure(response.eastward, levels, 85_000.0),
                    atPressure(response.geopotential, levels, 50_000.0),
                    WaveFixtures.verticalMotionAt(response, 50_000.0)
                ).also { check(bottom >= 1) }
            }
            val reference = readings.last()
            val line = StringBuilder("VERTICAL $name against 24 levels (surface pressure, 850 hPa eastward wind, 500 hPa geopotential, 500 hPa omega):")
            for ((index, count) in counts.dropLast(1).withIndex()) {
                line.append(" ${count.first} " + (0..3).joinToString(" ") { "%.3f".format(relative(grid, readings[index][it], reference[it])) } + ";")
            }
            println(line)
        }
    }

    @Test
    fun `the benchmarks with their damping halved and doubled`() {
        val rows = SphericalGrid.rowsForAtmosphere(radius)
        for (factor in listOf(0.5, 1.0, 2.0)) println("DAMPING x%.1f: ".format(factor) + figures(rows, AtmosphereLevels.equalMass(4), factor))
    }

    @Test
    fun `the extratropical benchmarks under Lee and others' damping and the worlds' set`() {
        val rows = SphericalGrid.rowsForAtmosphere(radius)
        val levels = AtmosphereLevels.equalMass(4)
        val grid = SphericalGrid(rows, 2 * rows, radius)
        val jets = ZonalBasicState.jablonowskiWilliamson(grid, levels)
        for ((name, damping) in listOf(
            "Lee and others 2009" to WaveDamping.leeAndOthers2009(),
            "the worlds' set" to WaveDamping.forWorlds(levels, jets.surfaceDensity),
            "Ting and Yu's 15 days alone" to WaveDamping.uniform(WaveDamping.perDays(WaveDamping.TING_YU_DAYS), WaveDamping.LEE_MIXING_M2_PER_S)
        )) {
            val heat = StationaryWaveModel(jets, damping).solve(WaveBenchmarks.hoskinsKarolyHeating(grid, levels))
            val row = (0 until rows).minByOrNull { abs(grid.latitudeRadians[it] - 45 * DEGREES) }!!
            val surface = (0 until grid.columns).map { heat.surfacePressurePa[row * grid.columns + it] }
            val trough = surface.indices.minByOrNull { surface[it] }!!
            val monsoon = WaveBenchmarks.monsoon(jets, damping)
            val mountain = StationaryWaveModel(jets, damping).solve(WaveForcing(surfaceHeightMeters = WaveBenchmarks.hoskinsKarolyMountain(grid)))
            val upper = WaveFixtures.nearestLevel(levels, 30_000.0)
            val train = WaveBenchmarks.waveTrain(grid, mountain.geopotential[upper], 30 * DEGREES, PI, WaveBenchmarks.MOUNTAIN_RADIUS)
            println(("DAMPING SET $name: the 45 N heating's surface trough %.2f hPa at %+.0f degrees; the monsoon's descent at 35 N %.2f hPa/h at %+.0f degrees; " +
                "the mountain's upper train %d extrema, wavelength %.0f km, largest %.0f m2/s2").format(surface[trough] / 100,
                Math.toDegrees((trough + 0.5) * grid.columnSpacingRadians) - 180, monsoon.descentHpaPerHour, monsoon.descentOffsetDegrees,
                train.extrema.size, train.wavelengthMeters / 1e3, train.extrema.maxOfOrNull { abs(it.value) } ?: 0.0))
        }
    }

    /** Every benchmark's headline figures at [rows] on [levels] with its damping times [factor]. */
    private fun figures(rows: Int, levels: AtmosphereLevels, factor: Double): String {
        val grid = SphericalGrid(rows, 2 * rows, radius)
        val gill = WaveBenchmarks.gill(radius, rows, levels, factor)
        val upper = WaveFixtures.nearestLevel(levels, 30_000.0)
        val angular = WaveBenchmarks.spin * WaveBenchmarks.SUPER_ROTATION_SHARE
        val epsilon = sqrt(angular / (2 * (WaveBenchmarks.spin + angular)))
        val superRotation = ZonalBasicState.solidBodyRotation(grid, levels, radius * angular)
        val mountain = StationaryWaveModel(superRotation, WaveBenchmarks.hoskinsKarolyMountainDamping(levels, radius).scaledBy(factor))
            .solve(WaveForcing(surfaceHeightMeters = WaveBenchmarks.hoskinsKarolyMountain(grid)))
        val train = WaveBenchmarks.waveTrain(grid, mountain.geopotential[upper], 30 * DEGREES, PI, WaveBenchmarks.MOUNTAIN_RADIUS)
        val jets = ZonalBasicState.jablonowskiWilliamson(grid, levels)
        val heat = StationaryWaveModel(jets, WaveBenchmarks.hoskinsKarolyHeatDamping(levels, radius).scaledBy(factor)).solve(WaveBenchmarks.hoskinsKarolyHeating(grid, levels))
        val row = (0 until rows).minByOrNull { abs(grid.latitudeRadians[it] - 45 * DEGREES) }!!
        val surface = (0 until grid.columns).map { heat.surfacePressurePa[row * grid.columns + it] }
        val trough = surface.indices.minByOrNull { surface[it] }!!
        val monsoon = WaveBenchmarks.monsoon(jets, WaveBenchmarks.rodwellHoskinsDamping(levels, radius).scaledBy(factor))
        return ("Gill (epsilon %.2f): mode 1 %.1f m/s, field %.3f off, decays %.4f and %.4f (the full damped waves' %.4f and %.4f); super-rotation mountain: train of %d, %.1f degrees off its circle at most, " +
            "wavelength %+.1f%% of %.0f km, largest %.0f m2/s2; the 45 N heating's trough %.2f hPa at %+.0f; the monsoon's descent %.2f hPa/h at %+.0f, %.0f hPa").format(
            gill.epsilon, gill.speedMetersPerSecond, gill.fieldError, gill.kelvinDecay, gill.rossbyDecay, gill.epsilon, WaveBenchmarks.exactRossbyDecay(gill.epsilon), train.extrema.size, train.worstOffCircleDegrees,
            100 * (train.wavelengthMeters / (2 * PI * radius * epsilon) - 1), 2 * PI * radius * epsilon / 1e3, train.extrema.maxOfOrNull { abs(it.value) } ?: 0.0,
            surface[trough] / 100, Math.toDegrees((trough + 0.5) * grid.columnSpacingRadians) - 180, monsoon.descentHpaPerHour, monsoon.descentOffsetDegrees, monsoon.descentPressureHpa)
    }
}
