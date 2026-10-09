package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.WaveFixtures.DEGREES
import com.cartogenesis.worldgen.WaveFixtures.SECONDS_PER_DAY
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.AtmosphereLevels
import com.cartogenesis.worldgen.pipeline.AtmosphereRemap
import com.cartogenesis.worldgen.pipeline.DryAir
import com.cartogenesis.worldgen.pipeline.PressureWind
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.SphericalOperators
import com.cartogenesis.worldgen.pipeline.StationaryWaveModel
import com.cartogenesis.worldgen.pipeline.VerticalModes
import com.cartogenesis.worldgen.pipeline.WaveDamping
import com.cartogenesis.worldgen.pipeline.WaveForcing
import com.cartogenesis.worldgen.pipeline.WaveResponse
import com.cartogenesis.worldgen.pipeline.ZonalBasicState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stationary-wave model held to what any correct solution of its equations must do, independent of
 * the published benchmarks (`StationaryWaveBenchmarkTest`): its vertical modes solve their own
 * eigenproblem, its damping puts each mode's stated rate on it, its vertical motion is the integral of
 * the operators' own divergence, it is linear, its geopotential's arbitrary constant moves nothing, a
 * forcing at a pole leaves a regular field, and forcing from the map converges with the grid.
 */
class StationaryWaveModelTest {

    private val radius = WorldScale().radiusMeters

    private val levelSets = listOf(
        "two levels and a boundary layer" to AtmosphereLevels.twoLevelsAndBoundaryLayer(),
        "four levels" to AtmosphereLevels.equalMass(4),
        "eight levels" to AtmosphereLevels.equalMass(8)
    )

    @Test
    fun `the vertical modes solve this discretization's eigenproblem, not N H over n pi`() {
        val grid = SphericalGrid(30, 60, radius)
        for ((name, levels) in levelSets) {
            val state = ZonalBasicState.resting(grid, levels)
            val modes = state.modes
            val size = levels.levelCount
            // G e = lambda e, with G = M^-1 D^T W D written out here from its definition.
            var residual = 0.0
            var orthonormality = 0.0
            for (mode in 0 until size) {
                val structure = modes.levelStructure[mode]
                val temperature = DoubleArray(levels.interiorCount) { interior ->
                    (structure[interior] - structure[interior + 1]) / (DryAir.GAS_CONSTANT_J_PER_KG_K * levels.logPressureRatio[interior])
                }
                val omega = DoubleArray(levels.interiorCount) { temperature[it] / state.stabilityKelvinPerPascal[it] }
                for (level in 0 until size) {
                    val below = if (level <= size - 2) omega[level] else 0.0
                    val above = if (level >= 1) omega[level - 1] else 0.0
                    val applied = (below - above) / levels.thicknessPa[level]
                    residual = max(residual, abs(applied - modes.eigenvalues[mode] * structure[level]) / (modes.eigenvalues.last() * structure.maxOf { abs(it) }))
                }
                for (other in 0 until size) {
                    val product = (0 until size).sumOf { modes.levelStructure[mode][it] * levels.thicknessPa[it] * modes.levelStructure[other][it] }
                    orthonormality = max(orthonormality, abs(product - if (mode == other) 1.0 else 0.0))
                }
                // Mode n changes sign n times down the column.
                val changes = (1 until size).count { structure[it] * structure[it - 1] < 0 }
                assertEquals(mode, changes, "$name: mode $mode changes sign $changes times")
            }
            val stability = state.stabilityKelvinPerPascal
            val buoyancy = { interior: Int ->
                // N from S at the interface: S = R T N^2 / (p g^2) T, so N^2 = S p g^2 / (R T^2).
                val pressure = levels.interiorPressurePa[interior]
                val kelvin = state.meanTemperature.kelvin(pressure)
                sqrt(stability[interior] * pressure * DryAir.GRAVITY_MPS2 * DryAir.GRAVITY_MPS2 / (DryAir.GAS_CONSTANT_J_PER_KG_K * kelvin * kelvin))
            }
            println(("VERTICAL MODES $name (Jablonowski and Williamson's mean temperature): speeds " +
                (1 until size).joinToString { "%.1f".format(modes.speedMetersPerSecond[it]) } + " m/s, equivalent depths " +
                (1 until size).joinToString { "%.0f".format(modes.equivalentDepthMeters[it]) } + " m; N at the interfaces " +
                (0 until levels.interiorCount).joinToString { "%.4f".format(buoyancy(it)) } +
                "; the design's N H / (n pi) with N = 0.01, H = 10 km: " + (1 until size).joinToString { "%.1f".format(0.01 * 10_000 / (it * PI)) } +
                "; residual %.1e, orthonormality %.1e").format(residual, orthonormality))
            assertTrue(residual < 1e-10 && orthonormality < 1e-10, "$name: residual $residual, orthonormality $orthonormality")
        }
    }

    @Test
    fun `modal damping puts each mode's stated rate on it, and the surface drag's share is reported`() {
        val grid = SphericalGrid(30, 60, radius)
        for ((name, levels) in levelSets) {
            val state = ZonalBasicState.resting(grid, levels)
            val modes = state.modes
            val damping = WaveDamping.leeAndOthers2009()
            val momentum = damping.momentumMatrix(modes)
            val thermal = damping.thermalMatrix(modes)
            for (mode in 0 until levels.levelCount) {
                val structure = modes.levelStructure[mode]
                val expected = if (mode == 0) damping.barotropicFrictionPerSecond else damping.baroclinicFrictionPerSecond
                for (level in 0 until levels.levelCount) {
                    val applied = (0 until levels.levelCount).sumOf { momentum[level][it] * structure[it] }
                    assertEquals(expected * structure[level], applied, 1e-12 * expected * structure.maxOf { abs(it) }, "$name mode $mode")
                }
                if (mode >= 1) {
                    val temperature = modes.temperatureStructure[mode - 1]
                    for (interior in 0 until levels.interiorCount) {
                        val applied = (0 until levels.interiorCount).sumOf { thermal[interior][it] * temperature[it] }
                        assertEquals(damping.thermalPerSecond * temperature[interior], applied, 1e-10 * damping.thermalPerSecond * temperature.maxOf { abs(it) }, "$name thermal mode $mode")
                    }
                }
            }
            val worlds = WaveDamping.forWorlds(levels, state.surfaceDensity)
            val worldMomentum = worlds.momentumMatrix(modes)
            println("DAMPING $name: the world set's momentum rate on each mode, days: " +
                (0 until levels.levelCount).joinToString { "%.2f".format(1 / modes.rateOnMode(worldMomentum, it) / SECONDS_PER_DAY) } +
                " (15 days free, the surface's stress on the lowest layer at %.2f days)".format(1 / WaveDamping.surfaceStressRate(levels, state.surfaceDensity) / SECONDS_PER_DAY))
        }
    }

    /** A heating and a mountain on Jablonowski and Williamson's jets, off every grid line. */
    private fun mixedForcing(grid: SphericalGrid, levels: AtmosphereLevels): WaveForcing {
        val heat = WaveFixtures.ellipse(grid, 23 * DEGREES, 1.3, 9 * DEGREES, 18 * DEGREES)
        val column = DoubleArray(grid.cellCount) { 3.0 / SECONDS_PER_DAY * heat[it] }
        val mountain = WaveFixtures.ellipse(grid, 41 * DEGREES, 4.1, 12 * DEGREES, 6 * DEGREES)
        return WaveForcing(WaveForcing.heatingFromColumn(levels, column, WaveFixtures::deepProfile), DoubleArray(grid.cellCount) { 2500.0 * mountain[it] })
    }

    @Test
    fun `the vertical motion is the integral of the operators' own divergence, down to the ground's`() {
        val grid = SphericalGrid(60, 120, radius)
        for ((name, levels) in levelSets) {
            val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
            val response = StationaryWaveModel(state, WaveDamping.forWorlds(levels, state.surfaceDensity)).solve(mixedForcing(grid, levels))
            val operators = SphericalOperators(grid)
            var integrated = DoubleArray(grid.cellCount)
            var worst = 0.0
            val scale = response.verticalMotion.maxOf { field -> field.maxOf { abs(it) } }
            for (level in 0 until levels.levelCount) {
                val divergence = operators.divergence(SphericalOperators.Vector(response.eastward[level], response.northwardAtFaces[level]))
                integrated = DoubleArray(grid.cellCount) { integrated[it] - levels.thicknessPa[level] * divergence[it] }
                val solved = if (level < levels.levelCount - 1) response.verticalMotion[level] else response.surfaceVerticalMotion
                for (cell in 0 until grid.cellCount) worst = max(worst, abs(integrated[cell] - solved[cell]) / scale)
            }
            println("CONTINUITY $name: the solved omega against the integral of the operators' divergence, worst %.1e of the largest omega (%.4f Pa/s)".format(worst, scale))
            assertTrue(worst < 1e-9, "$name: omega and the divergence part by $worst")
        }
    }

    @Test
    fun `doubling the forcing doubles the response, and two forcings superpose`() {
        val grid = SphericalGrid(48, 96, radius)
        val levels = AtmosphereLevels.equalMass(4)
        val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
        val model = StationaryWaveModel(state, WaveDamping.forWorlds(levels, state.surfaceDensity))
        val both = mixedForcing(grid, levels)
        val heatOnly = WaveForcing(both.heatingKelvinPerSecond)
        val mountainOnly = WaveForcing(surfaceHeightMeters = both.surfaceHeightMeters)
        val factored = model.factorize()
        val single = factored.solve(both)
        val doubled = factored.solve(both.scaledBy(2.0))
        val sum = factored.solve(heatOnly).surfacePressurePa.zip(factored.solve(mountainOnly).surfacePressurePa) { a, b -> a + b }
        val oneShot = model.solve(both)
        val largest = single.surfacePressurePa.maxOf { abs(it) }
        val doubling = single.surfacePressurePa.indices.maxOf { abs(doubled.surfacePressurePa[it] - 2 * single.surfacePressurePa[it]) } / largest
        val superposition = single.surfacePressurePa.indices.maxOf { abs(sum[it] - single.surfacePressurePa[it]) } / largest
        val refactored = single.surfacePressurePa.indices.maxOf { abs(oneShot.surfacePressurePa[it] - single.surfacePressurePa[it]) } / largest
        println("LINEARITY: doubling %.1e, superposition %.1e, factored once against factored per solve %.1e, of the largest surface pressure %.0f Pa".format(doubling, superposition, refactored, largest))
        assertTrue(doubling < 1e-12 && superposition < 1e-12 && refactored == 0.0, "doubling $doubling, superposition $superposition, refactored $refactored")
    }

    @Test
    fun `the geopotential's arbitrary constant moves nothing, so the zonal mean's pin can sit anywhere`() {
        val grid = SphericalGrid(40, 80, radius)
        val levels = AtmosphereLevels.equalMass(4)
        val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
        val damping = WaveDamping.forWorlds(levels, state.surfaceDensity)
        val forcing = mixedForcing(grid, levels)
        val south = StationaryWaveModel(state, damping, solveZonalMean = true).solve(forcing)
        val north = StationaryWaveModel(state, damping, solveZonalMean = true, zonalMeanPinRow = 0).solve(forcing)
        val middle = StationaryWaveModel(state, damping, solveZonalMean = true, zonalMeanPinRow = 17).solve(forcing)
        fun largestDifference(first: WaveResponse, second: WaveResponse): Double {
            var worst = 0.0
            for (level in 0 until levels.levelCount) {
                val scale = first.eastward[level].maxOf { abs(it) }
                for (cell in 0 until grid.cellCount) worst = max(worst, abs(first.eastward[level][cell] - second.eastward[level][cell]) / scale)
            }
            val pressureScale = first.surfacePressurePa.maxOf { abs(it) }
            for (cell in 0 until grid.cellCount) worst = max(worst, abs(first.surfacePressurePa[cell] - second.surfacePressurePa[cell]) / pressureScale)
            return worst
        }
        var meanPressure = 0.0
        for (cell in 0 until grid.cellCount) meanPressure += south.surfacePressurePa[cell] * grid.cellAreaSquareMeters[cell / grid.columns]
        meanPressure /= grid.totalAreaSquareMeters
        val atNorth = largestDifference(south, north)
        val atMiddle = largestDifference(south, middle)
        println("REFERENCE: the pin at the north pole's row and at row 17 against the south's, winds and surface pressure %.1e and %.1e; the surface pressure's area mean %.1e Pa".format(atNorth, atMiddle, meanPressure))
        assertTrue(atNorth < 1e-9 && atMiddle < 1e-9 && abs(meanPressure) < 1e-9, "the pin moves the answer by $atNorth and $atMiddle, mean $meanPressure")
    }

    @Test
    fun `the ground's reference pressure moves the response by about its own share`() {
        val grid = SphericalGrid(48, 96, radius)
        val responses = listOf(AtmosphereLevels.REFERENCE_SURFACE_PRESSURE_PA, 101_325.0).map { surface ->
            val levels = AtmosphereLevels.equalMass(4, surface)
            val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
            StationaryWaveModel(state, WaveDamping.forWorlds(levels, state.surfaceDensity)).solve(mixedForcing(grid, levels))
        }
        fun relative(first: DoubleArray, second: DoubleArray): Double {
            var difference = 0.0
            var norm = 0.0
            for (cell in first.indices) {
                val area = grid.cellAreaSquareMeters[cell / grid.columns]
                difference += (first[cell] - second[cell]).pow(2) * area
                norm += first[cell].pow(2) * area
            }
            return sqrt(difference / norm)
        }
        val pressure = relative(responses[0].surfacePressurePa, responses[1].surfacePressurePa)
        val wind = relative(responses[0].eastward[3], responses[1].eastward[3])
        val omega = relative(responses[0].verticalMotion[1], responses[1].verticalMotion[1])
        val share = 101_325.0 / AtmosphereLevels.REFERENCE_SURFACE_PRESSURE_PA - 1
        println("REFERENCE PRESSURE 1,000 against 1,013.25 hPa (a share of %.4f): surface pressure moves %.4f, the lowest level's wind %.4f, the middle interface's omega %.4f (relative RMS)".format(share, pressure, wind, omega))
        // The levels, the heating's mass and the terrain's lift all scale with the column, so nothing
        // should move by more than a few times the column's own share.
        assertTrue(maxOf(pressure, wind, omega) < 4 * share, "the reference moves the response by ${maxOf(pressure, wind, omega)}")
    }

    /**
     * The polar rows' regularity under a forcing at each pole, on 60 and 120 rows: for each pole and
     * zonal wave 1 to 4, the polar row's share of that wave's largest amplitude on any row, and the
     * spread round the polar row of the lowest level's wind as a Cartesian vector over the wind's RMS.
     */
    private fun polarReadings(polarWindHeldAtZero: Boolean): Pair<List<Double>, List<Double>> {
        val spreads = mutableListOf<Double>()
        val polarShares = mutableListOf<Double>()
        for (rows in listOf(60, 120)) {
            val grid = SphericalGrid(rows, 2 * rows, radius)
            val levels = AtmosphereLevels.equalMass(4)
            val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
            val heat = WaveFixtures.ellipse(grid, 80 * DEGREES, 0.7, 10 * DEGREES, 10 * DEGREES)
            val column = DoubleArray(grid.cellCount) { 3.0 / SECONDS_PER_DAY * heat[it] }
            val mountain = WaveFixtures.ellipse(grid, -78 * DEGREES, 2.0, 9 * DEGREES, 9 * DEGREES)
            val forcing = WaveForcing(WaveForcing.heatingFromColumn(levels, column, WaveFixtures::deepProfile), DoubleArray(grid.cellCount) { 3000.0 * mountain[it] })
            val response = StationaryWaveModel(state, WaveDamping.forWorlds(levels, state.surfaceDensity), polarWindHeldAtZero = polarWindHeldAtZero).solve(forcing)
            val line = StringBuilder("POLE ${if (polarWindHeldAtZero) "polar wind held at zero" else "regular"}, rows $rows:")
            for ((poleName, polarRow, nextRow) in listOf(Triple("north", 0, 1), Triple("south", rows - 1, rows - 2))) {
                // A scalar's wave m near a pole goes as the colatitude to the m: the first row against
                // the second, (1/2 over 3/2)^m.
                for (wave in 1..4) {
                    val amplitude = WaveFixtures.waveAmplitude(grid, response.geopotential[levels.levelCount - 1], wave)
                    val ratio = amplitude[polarRow] / amplitude[nextRow]
                    val ofLargest = amplitude[polarRow] / amplitude.maxOrNull()!!
                    line.append(" $poleName m$wave %.3f (regular %.3f) of largest %.2e;".format(ratio, 3.0.pow(-wave), ofLargest))
                    polarShares += ofLargest
                }
                // The wind on the polar row as a Cartesian vector: one vector, so its spread round the
                // row shrinks with the row's distance from the pole.
                val level = levels.levelCount - 1
                val north = response.northwardAtCenters(level)
                val vectors = (0 until grid.columns).map { column ->
                    val longitude = (column + 0.5) * grid.columnSpacingRadians
                    val latitude = grid.latitudeRadians[polarRow]
                    val east = response.eastward[level][polarRow * grid.columns + column]
                    val northward = north[polarRow * grid.columns + column]
                    doubleArrayOf(
                        -sin(longitude) * east - sin(latitude) * cos(longitude) * northward,
                        cos(longitude) * east - sin(latitude) * sin(longitude) * northward,
                        cos(latitude) * northward
                    )
                }
                val mean = DoubleArray(3) { axis -> vectors.sumOf { it[axis] } / vectors.size }
                val spread = sqrt(vectors.sumOf { vector -> (0..2).sumOf { (vector[it] - mean[it]).pow(2) } } / vectors.size)
                val windScale = sqrt(response.eastward[level].sumOf { it * it } / grid.cellCount)
                spreads += spread / windScale
                line.append(" $poleName wind spread %.4f of the wind's RMS;".format(spread / windScale))
            }
            println(line)
        }
        return polarShares to spreads
    }

    /**
     * Whether [polarShares] and [spreads] (from [polarReadings]) are a regular field's: a regular
     * scalar's wave m goes as the colatitude to the m, so halving the first row's colatitude divides its
     * share by 2^m, a quarter allowed for the series' next term (the first row's own shape is the
     * discretization's: waves 3 and 4 stand a few times higher against the second row than theta^m
     * would put them, and converge as theta^m does); and halving it at least nearly halves the wind's
     * spread round the row at both poles, the vector there being one vector in the limit. Returns the
     * failures.
     */
    private fun regularityFailures(polarShares: List<Double>, spreads: List<Double>): List<String> {
        val failures = mutableListOf<String>()
        val waves = 4
        for (pole in 0..1) for (wave in 1..waves) {
            val coarse = polarShares[pole * waves + wave - 1]
            val fine = polarShares[(2 + pole) * waves + wave - 1]
            if (fine / coarse >= 1.25 * 0.5.pow(wave)) failures += "pole $pole wave $wave: the polar row's share went from $coarse to $fine"
        }
        for (pole in 0..1) {
            if (spreads[2 + pole] >= 0.6 * spreads[pole]) failures += "pole $pole: the wind's spread went from ${spreads[pole]} to ${spreads[2 + pole]}"
        }
        return failures
    }

    @Test
    fun `a forcing at a pole leaves a regular field, one vector at the pole and every scalar wave vanishing there`() {
        val (shares, spreads) = polarReadings(polarWindHeldAtZero = false)
        val failures = regularityFailures(shares, spreads)
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
        // The control: the prototype's northward wind held at zero on the polar faces, which no wind
        // crossing a pole can satisfy, fails the same reading.
        val (heldShares, heldSpreads) = polarReadings(polarWindHeldAtZero = true)
        val heldFailures = regularityFailures(heldShares, heldSpreads)
        println("POLE the control's failures: " + heldFailures.joinToString("; "))
        assertTrue(heldFailures.isNotEmpty(), "holding the polar wind at zero passed the regularity reading")
    }

    /**
     * A planet's worth of rough forcing on a 1,024 by 512 map: continents from low octaves of noise,
     * heated a kelvin a day with step coasts, and terrain of fractional Brownian motion down to the
     * map's own cell, every octave carrying the same slope, as a generated world's relief does.
     */
    private fun roughGround(columns: Int, rows: Int): Pair<FloatArray, FloatArray> {
        val noise = com.cartogenesis.worldgen.noise.PerlinNoise(1981)
        val heating = FloatArray(columns * rows)
        val height = FloatArray(columns * rows)
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = column.toFloat() / columns
            val y = row.toFloat() / rows
            val isLand = noise.fbm(x * 4, y * 2, 3, 4, 2) > 0.05f
            heating[row * columns + column] = if (isLand) (1.0 / SECONDS_PER_DAY).toFloat() else 0f
            val relief = noise.fbm(x * 16 + 7.3f, y * 8 + 1.9f, 7, 16, 8)
            height[row * columns + column] = if (isLand) (2500f * (1f + 2f * relief)).coerceAtLeast(0f) else 0f
        }
        return heating to height
    }

    /** The area-mean speed of the wind the surface pressure's departure drives through the sea's drag balance. */
    private fun meanSurfaceWind(grid: SphericalGrid, surfacePressurePa: DoubleArray): Double {
        val operators = SphericalOperators(grid)
        val gradient = operators.gradient(surfacePressurePa)
        val northGradient = operators.northAtCenters(gradient)
        val spin = WorldScale.ROTATION_RATE_PER_S.toDouble()
        // The pressure wind's own balance over the sea: drag k = f(45) tan(25 degrees) (`PressureWind`).
        val drag = 2 * spin * sin(PI / 4) * tan(25 * DEGREES)
        var sum = 0.0
        for (cell in 0 until grid.cellCount) {
            val row = cell / grid.columns
            val coriolis = 2 * spin * sin(grid.latitudeRadians[row])
            val towardEast = -gradient.eastAtCenters[cell] / airDensity
            val towardNorth = -northGradient[cell] / airDensity
            val denominator = drag * drag + coriolis * coriolis
            val east = (drag * towardEast + coriolis * towardNorth) / denominator
            val north = (drag * towardNorth - coriolis * towardEast) / denominator
            sum += sqrt(east * east + north * north) * grid.cellAreaSquareMeters[row]
        }
        return sum / grid.totalAreaSquareMeters
    }

    private val airDensity = PressureWind.AIR_DENSITY_KG_PER_M3.toDouble()

    @Test
    fun `forcing spread to a length on the ground converges with the grid, where the cells' own mean does not`() {
        val (columns, rows) = 1024 to 512
        val (heating, height) = roughGround(columns, rows)
        val ladder = listOf(60, 90, 150, 240)
        val byPath = mutableMapOf<String, List<Double>>()
        for (path in listOf("cells' mean", "spread on the ground")) {
            byPath[path] = ladder.map { coarseRows ->
                val grid = SphericalGrid(coarseRows, 2 * coarseRows, radius)
                val remap = AtmosphereRemap(columns, rows, grid)
                val levels = AtmosphereLevels.equalMass(4)
                val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
                val forcing = if (path == "cells' mean") {
                    WaveForcing(WaveForcing.heatingFromColumn(levels, remap.areaMean(heating), WaveFixtures::deepProfile), remap.areaMean(height))
                } else {
                    WaveForcing.fromGround(remap, levels, heating, height, WaveFixtures::deepProfile)
                }
                meanSurfaceWind(grid, StationaryWaveModel(state, WaveDamping.forWorlds(levels, state.surfaceDensity)).solve(forcing).surfacePressurePa)
            }
            println("WIND BY GRID, $path: " + ladder.zip(byPath[path]!!).joinToString { (grid, speed) -> "$grid rows %.3f m/s".format(speed) })
        }
        fun drift(speeds: List<Double>) = abs(speeds[2] / speeds[3] - 1)
        val spread = drift(byPath["spread on the ground"]!!)
        val cells = drift(byPath["cells' mean"]!!)
        println("WIND BY GRID: 150 rows against 240, spread on the ground %.4f, the cells' mean %.4f (tolerance %.2f)".format(spread, cells, SphericalGrid.OPERATOR_TOLERANCE))
        assertTrue(spread < SphericalGrid.OPERATOR_TOLERANCE, "forcing spread on the ground still moves the wind by $spread from 150 rows to 240")
        assertTrue(cells > SphericalGrid.OPERATOR_TOLERANCE, "the cells' own mean was meant to show the growth and moved the wind by only $cells")
    }
}
