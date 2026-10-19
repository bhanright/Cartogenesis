package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.AtmosphereLevels
import com.cartogenesis.worldgen.pipeline.BoundaryLayer
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.LevelExtrapolation
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.StationaryWaveModel
import com.cartogenesis.worldgen.pipeline.WaveDamping
import com.cartogenesis.worldgen.pipeline.WaveForcing
import com.cartogenesis.worldgen.pipeline.WaveResponse
import com.cartogenesis.worldgen.pipeline.ZonalBasicState
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * The audit of [LevelExtrapolation]: the order at which the stationary waves' answer converges as
 * the levels are doubled, measured on the model's own benchmarks and on a world's forcing, and what
 * the extrapolation from eight and four levels stands at against sixteen and twenty-four. Reports
 * what it measures; `LevelExtrapolationTest` holds the bar per merge.
 *
 * The order needs no exact answer: with the error `C h^p`, the answer's change from four to eight
 * levels over its change from eight to sixteen is `2^p`, read here as the ratio of the two
 * changes' root mean squares by area.
 */
class LevelExtrapolationReport : BorrowsSharedWorlds() {

    private val radius = WorldScale().radiusMeters

    /** The levels the convergence is read on: each twice the one before, and 24 as the reference. */
    private val counts = listOf(4, 8, 16, 24)

    @Test
    fun `the benchmarks' order of convergence in the vertical, and the extrapolation against sixteen and twenty-four levels`() {
        val rows = 120
        val grid = SphericalGrid(rows, 2 * rows, radius)
        for (case in LevelBenchmarks.cases(grid)) report(case.name, counts.associateWith { case.solve(AtmosphereLevels.equalMass(it)) })
    }

    @Test
    fun `a world's order of convergence in the vertical, and the extrapolation against sixteen levels`() {
        for (seed in listOf(42L, 7L)) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, 512))
            val config = world.config
            val zonal = ClimateStage.zonalClimate(config, world.sea)
            val marine = ClimateStage.marineAirFraction(config, world.sea)
            val belts = listOf(true, false).map { ClimateStage.circulationBelts(config, world.sea, zonal, it) }
            val heating = checkNotNull(ClimateStage.coupledAtmosphere(config, world.sea, world.ocean)).coupling
            for ((name, latent) in listOf("dry" to null, "with the climate's latent heating" to heating)) {
                val answers = listOf(4, 8, 16).associateWith { levelCount ->
                    BoundaryLayer.Solver(
                        config, world.sea, zonal, marine, belts[0], belts[1], keptWaves = 0,
                        seaSurfaceAnomalyC = world.ocean.anomaly.data, levelCount = levelCount, extrapolated = false
                    ).solve(latent?.julyLatentWPerM2, latent?.januaryLatentWPerM2).julyHalf.response
                }
                report("seed $seed at 512 rows, July, $name", answers)
            }
        }
    }

    /** One case's figures: the order from 4, 8 and 16 levels and each answer against the finest. */
    private fun report(name: String, answers: Map<Int, WaveResponse>) {
        val grid = answers.values.first().grid
        val readings = answers.mapValues { LevelBenchmarks.readings(it.value) }
        val extrapolated = LevelBenchmarks.readings(LevelExtrapolation.extrapolate(answers.getValue(8), answers.getValue(4)))
        val finest = readings.keys.max()
        val line = StringBuilder("LEVELS $name (surface pressure, 850 hPa eastward wind, 500 hPa omega):")
        for (field in 0 until LevelBenchmarks.FIELDS) {
            val reference = readings.getValue(finest)[field]
            val coarseChange = LevelBenchmarks.relative(grid, readings.getValue(8)[field], readings.getValue(4)[field], reference)
            val fineChange = LevelBenchmarks.relative(grid, readings.getValue(16)[field], readings.getValue(8)[field], reference)
            val order = ln(coarseChange / fineChange) / ln(2.0)
            line.append(" [${LevelBenchmarks.FIELD_NAMES[field]}: order %.2f;".format(order))
            for (count in readings.keys.filter { it < finest }) {
                line.append(" $count against $finest %.3f;".format(LevelBenchmarks.relative(grid, readings.getValue(count)[field], reference, reference)))
            }
            line.append(" extrapolated 4+8 against $finest %.3f".format(LevelBenchmarks.relative(grid, extrapolated[field], reference, reference)))
            line.append("; amplitude over $finest's: " + readings.keys.filter { it < finest }.joinToString(", ") { count ->
                "$count %.3f".format(LevelBenchmarks.rms(grid, readings.getValue(count)[field]) / LevelBenchmarks.rms(grid, reference))
            } + ", extrapolated %.3f".format(LevelBenchmarks.rms(grid, extrapolated[field]) / LevelBenchmarks.rms(grid, reference)))
            line.append("; the share of 8 less 4 nearest $finest %.2f (the extrapolation's %.2f)".format(
                LevelBenchmarks.bestShare(grid, readings.getValue(8)[field], readings.getValue(4)[field], reference), LevelExtrapolation.CORRECTION_SHARE
            ))
            if (finest != 16) {
                val sixteen = readings.getValue(16)[field]
                line.append(", against 16 %.3f (8 against 16 %.3f)".format(
                    LevelBenchmarks.relative(grid, extrapolated[field], sixteen, sixteen), LevelBenchmarks.relative(grid, readings.getValue(8)[field], sixteen, sixteen)
                ))
            }
            line.append("]")
        }
        println(line)
    }
}

/** The vertical-convergence benchmarks the report and the guard share. */
internal object LevelBenchmarks {

    /** A benchmark as a function of the levels. */
    class Case(val name: String, val solve: (AtmosphereLevels) -> WaveResponse)

    /**
     * Gill's equatorial heating at rest, Hoskins and Karoly's heating at 45 N and mountain on the
     * jets, and Rodwell and Hoskins' monsoon, each with its paper's damping put on the same air at
     * every level count ([WaveBenchmarks.byMass]) and a heating whose vertical profile is the same
     * in pressure at every count, so the levels alone differ.
     *
     * Gill's case is not his benchmark's own: that one heats the levels' first vertical mode, whose
     * shape and speed change with the levels. Here his heating's horizontal shape, symmetric about
     * the equator and ten degrees by thirty, heats a deep profile on the resting state, damped at
     * his `epsilon` of 0.1 on the time scale of eight levels' first mode, the same rate at every count.
     */
    fun cases(grid: SphericalGrid): List<Case> {
        val radius = grid.radiusMeters
        val gillRate = run {
            val speed = ZonalBasicState.resting(grid, AtmosphereLevels.equalMass(8)).modes.speedMetersPerSecond[1]
            val beta = 2 * WaveBenchmarks.spin / radius
            WaveBenchmarks.GILL_EPSILON * sqrt(2 * beta * speed)
        }
        val gillShape = WaveFixtures.withoutZonalMean(grid, WaveFixtures.ellipse(grid, 0.0, PI, 10 * WaveFixtures.DEGREES, 30 * WaveFixtures.DEGREES))
        return listOf(
            Case("Gill's equatorial heating at rest") { levels ->
                val state = ZonalBasicState.resting(grid, levels)
                val column = DoubleArray(grid.cellCount) { 2.5 / WaveFixtures.SECONDS_PER_DAY * gillShape[it] }
                StationaryWaveModel(state, WaveDamping.uniform(gillRate))
                    .solve(WaveForcing(WaveForcing.heatingFromColumn(levels, column, WaveFixtures::deepProfile)))
            },
            Case("Hoskins and Karoly's heating at 45 N") { levels ->
                StationaryWaveModel(
                    ZonalBasicState.jablonowskiWilliamson(grid, levels), WaveBenchmarks.hoskinsKarolyHeatDamping(levels, radius)
                ).solve(WaveBenchmarks.hoskinsKarolyHeating(grid, levels))
            },
            Case("Hoskins and Karoly's mountain on the jets") { levels ->
                StationaryWaveModel(
                    ZonalBasicState.jablonowskiWilliamson(grid, levels), WaveBenchmarks.hoskinsKarolyMountainDamping(levels, radius)
                ).solve(WaveForcing(surfaceHeightMeters = WaveBenchmarks.hoskinsKarolyMountain(grid)))
            },
            Case("Rodwell and Hoskins' monsoon") { levels ->
                WaveBenchmarks.monsoon(ZonalBasicState.jablonowskiWilliamson(grid, levels), WaveBenchmarks.rodwellHoskinsDamping(levels, radius)).response
            }
        )
    }

    const val FIELDS = 4
    val FIELD_NAMES = listOf("surface pressure", "850 hPa wind", "500 hPa omega", "column's vapor-weighted eastward wind")

    /**
     * [response]'s surface pressure, eastward wind at 850 hPa, vertical motion at 500 hPa and the
     * whole column's eastward wind weighted by its vapor ([BoundaryLayer.vaporShares], the wind the
     * march's water rides on, summed over every level so that it reads the same air at any count),
     * each on its grid.
     */
    fun readings(response: WaveResponse): List<DoubleArray> {
        val shares = BoundaryLayer.vaporShares(response.levels)
        val column = DoubleArray(response.grid.cellCount)
        for (level in 0 until response.levels.levelCount) {
            val eastward = response.eastward[level]
            for (cell in column.indices) column[cell] += shares[level] * eastward[cell]
        }
        return listOf(
            response.surfacePressurePa,
            atPressure(response.eastward, response.levels, 85_000.0),
            WaveFixtures.verticalMotionAt(response, 50_000.0),
            column
        )
    }

    /** The root mean square by area of [field] on [grid]. */
    fun rms(grid: SphericalGrid, field: DoubleArray): Double {
        var sum = 0.0
        var area = 0.0
        for (cell in field.indices) {
            val weight = grid.cellAreaSquareMeters[cell / grid.columns]
            sum += field[cell] * field[cell] * weight
            area += weight
        }
        return sqrt(sum / area)
    }

    /**
     * The share of [fine] less [coarse] that, added to [fine], comes nearest [reference] by area:
     * a diagnostic of whether the coarser answer's departure points toward the converged one at
     * all, not a figure the extrapolation reads.
     */
    fun bestShare(grid: SphericalGrid, fine: DoubleArray, coarse: DoubleArray, reference: DoubleArray): Double {
        var along = 0.0
        var norm = 0.0
        for (cell in fine.indices) {
            val weight = grid.cellAreaSquareMeters[cell / grid.columns]
            val step = fine[cell] - coarse[cell]
            along += (reference[cell] - fine[cell]) * step * weight
            norm += step * step * weight
        }
        return along / norm
    }

    /** Level fields read at [pressurePa], linear in the logarithm of pressure between the levels either side (or the two nearest). */
    fun atPressure(fields: Array<DoubleArray>, levels: AtmosphereLevels, pressurePa: Double): DoubleArray {
        val logs = levels.levelPressurePa.map { ln(it) }
        val target = ln(pressurePa)
        val upper = (0 until levels.levelCount - 1).lastOrNull { logs[it] <= target } ?: 0
        val share = (target - logs[upper]) / (logs[upper + 1] - logs[upper])
        return DoubleArray(fields[0].size) { (1 - share) * fields[upper][it] + share * fields[upper + 1][it] }
    }

    /** The root mean square by area of [first] less [second], over [reference]'s, on [grid]. */
    fun relative(grid: SphericalGrid, first: DoubleArray, second: DoubleArray, reference: DoubleArray): Double {
        var difference = 0.0
        var norm = 0.0
        for (cell in first.indices) {
            val area = grid.cellAreaSquareMeters[cell / grid.columns]
            difference += (first[cell] - second[cell]) * (first[cell] - second[cell]) * area
            norm += reference[cell] * reference[cell] * area
        }
        return sqrt(difference / norm)
    }
}
