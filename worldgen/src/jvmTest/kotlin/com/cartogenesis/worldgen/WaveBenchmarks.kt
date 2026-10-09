package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.WaveFixtures.DEGREES
import com.cartogenesis.worldgen.WaveFixtures.SECONDS_PER_DAY
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.AtmosphereLevels
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.StationaryWaveModel
import com.cartogenesis.worldgen.pipeline.WaveDamping
import com.cartogenesis.worldgen.pipeline.WaveForcing
import com.cartogenesis.worldgen.pipeline.WaveResponse
import com.cartogenesis.worldgen.pipeline.ZonalBasicState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The three published benchmarks as runnable experiments, each returning the figures its source gives:
 * Gill (1980), Hoskins and Karoly (1981) and Rodwell and Hoskins (2001). Shared by the per-merge
 * benchmark test, which holds the figures, and the audit report, which runs them on the grid ladder,
 * on two, four and eight levels, and with the damping halved and doubled.
 */
internal object WaveBenchmarks {

    val spin = WorldScale.ROTATION_RATE_PER_S.toDouble()

    // ---------------------------------------------------------------- Gill (1980)

    /** Gill's figure 1: `epsilon = 0.1` and the heating's half-width `L = 2` Rossby radii. */
    const val GILL_EPSILON = 0.1
    const val GILL_HALF_WIDTH = 2.0

    /** Gill's figures read off a solved response. */
    class GillFigures(
        val epsilon: Double,
        val fieldError: Double,
        val eastwardError: Double,
        val kelvinDecay: Double,
        val rossbyDecay: Double,
        val rossbyRadiusMeters: Double,
        val speedMetersPerSecond: Double,
        val response: WaveResponse,
        val modeGeopotential: DoubleArray
    ) {
        val decayRatio get() = rossbyDecay / kelvinDecay
    }

    /**
     * The first planetary wave's westward decay rate on Gill's beta-plane without his long-wave
     * approximation. Matsuno's equatorial dispersion relation, `w^2 - k^2 - k / w = 2n + 1` in his
     * units, is `2 w^2 - 2 k^2 - k / w = 2n + 1` in Gill's (whose length and time are Matsuno's over
     * root two); a steady damped wave has `w = i epsilon`, and for `n = 1` the root that decays
     * westward is `k = -i kappa`, `kappa = (sqrt(1 + 8 epsilon^2 (3 + 2 epsilon^2)) - 1) / (4 epsilon)`:
     * `3 epsilon` to first order (Gill's), 2.857 epsilon at his `epsilon = 0.1`. The Kelvin wave's rate is
     * `epsilon` exactly either way.
     */
    fun exactRossbyDecay(epsilon: Double): Double =
        (sqrt(1 + 8 * epsilon * epsilon * (3 + 2 * epsilon * epsilon)) - 1) / (4 * epsilon)

    /**
     * Gill's experiment on a resting state over a planet of [radiusMeters], on [rows] rows and
     * [levels]: a heating with the first baroclinic mode's temperature profile, so that mode alone
     * answers, `F(x) exp(-y^2/4)` in that mode's Rossby radius, and Gill's `epsilon` on momentum and heat
     * ([damping] scales it, for the sensitivity runs). Returns the first mode's response against his
     * analytic solution, and the decay rates of his two waves along the equator.
     */
    fun gill(radiusMeters: Double, rows: Int, levels: AtmosphereLevels, dampingFactor: Double = 1.0): GillFigures {
        val grid = SphericalGrid(rows, 2 * rows, radiusMeters)
        val state = ZonalBasicState.resting(grid, levels)
        val modes = state.modes
        val speed = modes.speedMetersPerSecond[1]
        val beta = 2 * spin / radiusMeters
        val length = sqrt(speed / (2 * beta))
        val time = 1 / sqrt(2 * beta * speed)
        val epsilon = GILL_EPSILON * dampingFactor
        val solution = GillSolution(epsilon, GILL_HALF_WIDTH, 2 * PI * radiusMeters / length)
        val amplitude = 1e-5
        fun x(cell: Int) = solution.wrap(radiusMeters * ((cell % grid.columns + 0.5) * grid.columnSpacingRadians - PI) / length)
        fun y(row: Int) = radiusMeters * grid.latitudeRadians[row] / length
        val horizontal = DoubleArray(grid.cellCount) { cell -> val along = y(cell / grid.columns); amplitude * solution.forcing(x(cell)) * exp(-along * along / 4) }
        val profile = modes.temperatureStructure[0]
        val heating = Array(levels.interiorCount) { interior -> DoubleArray(grid.cellCount) { profile[interior] * horizontal[it] } }
        val model = StationaryWaveModel(state, WaveDamping.uniform(epsilon / time), solveZonalMean = true, highestZonalWave = grid.columns / 2 - 1)
        val response = model.solve(WaveForcing(heating))
        val structure = modes.levelStructure[1]
        fun project(fields: Array<DoubleArray>) = DoubleArray(grid.cellCount) { cell ->
            (0 until levels.levelCount).sumOf { structure[it] * levels.thicknessPa[it] * fields[it][cell] }
        }
        val geopotential = project(response.geopotential)
        val eastward = project(response.eastward)
        // Gill's variables: the mode's geopotential is -T Q0 p and its wind -(T Q0 / c) u (see GillSolution).
        val pressureScale = -time * amplitude
        val windScale = -time * amplitude / speed
        var pressureError = 0.0; var pressureNorm = 0.0; var windError = 0.0; var windNorm = 0.0
        for (cell in 0 until grid.cellCount) {
            val row = cell / grid.columns
            val along = y(row)
            if (abs(along) > GILL_COMPARED_RADII) continue
            val area = grid.cellAreaSquareMeters[row]
            val exactPressure = pressureScale * solution.pressure(x(cell), along)
            val exactWind = windScale * solution.eastward(x(cell), along)
            pressureError += (geopotential[cell] - exactPressure) * (geopotential[cell] - exactPressure) * area
            pressureNorm += exactPressure * exactPressure * area
            windError += (eastward[cell] - exactWind) * (eastward[cell] - exactWind) * area
            windNorm += exactWind * exactWind * area
        }
        // Along the equator Gill's p and u separate his two waves: at y0, p - u = 2 q2 e0 and the rest is q0.
        val equatorRows = listOf(rows / 2 - 1, rows / 2)
        val y0 = abs(y(equatorRows[0]))
        val envelope = exp(-y0 * y0 / 4)
        fun along(x: Double): Pair<Double, Double> {
            val column = Math.floorMod(Math.round((x * length / radiusMeters + PI) / grid.columnSpacingRadians - 0.5).toInt(), grid.columns)
            val p = equatorRows.sumOf { geopotential[it * grid.columns + column] } / 2 / pressureScale
            val u = equatorRows.sumOf { eastward[it * grid.columns + column] } / 2 / windScale
            val rossby = (p - u) / (2 * envelope)
            val kelvin = 2 * p / envelope - rossby * (1 + y0 * y0)
            return kelvin to rossby
        }
        fun slope(from: Double, to: Double, wave: (Pair<Double, Double>) -> Double): Double {
            val xs = (0..SLOPE_SAMPLES).map { from + (to - from) * it / SLOPE_SAMPLES }
            val ys = xs.map { ln(abs(wave(along(it)))) }
            val meanX = xs.average(); val meanY = ys.average()
            return xs.indices.sumOf { (xs[it] - meanX) * (ys[it] - meanY) } / xs.indices.sumOf { (xs[it] - meanX) * (xs[it] - meanX) }
        }
        // East of the heating the Kelvin wave alone; west of it the planetary wave alone (each one
        // Rossby radius clear of the heating's edge, and short of where the other's image matters).
        val kelvinDecay = -slope(GILL_HALF_WIDTH + 1, GILL_HALF_WIDTH + 6) { it.first }
        val rossbyDecay = slope(-GILL_HALF_WIDTH - 3, -GILL_HALF_WIDTH - 0.5) { it.second }
        return GillFigures(
            epsilon, sqrt(pressureError / pressureNorm), sqrt(windError / windNorm), kelvinDecay, rossbyDecay, length, speed, response, geopotential
        )
    }

    /** Gill's solution is compared within four Rossby radii of the equator, where his figure 1 is drawn. */
    const val GILL_COMPARED_RADII = 4.0
    private const val SLOPE_SAMPLES = 24

    // ---------------------------------------------------------------- Hoskins and Karoly (1981)

    /**
     * Their constant-angular-velocity flow (section 5c): `w / Omega = 1 / 30.875`, 15 m/s at the
     * equator, on which every stationary barotropic ray is a great circle and the wavelength is
     * `epsilon` times the circumference, `epsilon^2 = w / (2 (Omega + w))`, 5,019 km on Earth.
     */
    const val SUPER_ROTATION_SHARE = 1 / 30.875

    /**
     * Their biharmonic, 2.338e16 m^4/s, as the Laplacian that damps their truncation's wave (total
     * wavenumber 25) at the same rate: `A = K n (n + 1) / a^2`, 3.7e5 m^2/s on Earth's radius. The
     * Laplacian replaces the biharmonic here for the reason in [WaveDamping].
     */
    fun hoskinsKarolyMixing(radiusMeters: Double) = HK_BIHARMONIC * HK_TRUNCATION * (HK_TRUNCATION + 1) / (radiusMeters * radiusMeters)

    private const val HK_BIHARMONIC = 2.338e16
    private const val HK_TRUNCATION = 25.0

    /**
     * A damping given as rates by `sigma = p / p_s`, put on [levels] by mass: each level's friction is
     * [friction]'s mean over its own layer and each interior interface's cooling [cooling]'s mean over
     * the air between the levels either side of it. A rate stated for one of Hoskins and Karoly's five
     * layers is then the same rate on the same air at any vertical resolution, which is what lets the
     * two-, four- and eight-level runs be compared.
     */
    fun byMass(levels: AtmosphereLevels, mixing: Double, friction: (Double) -> Double, cooling: (Double) -> Double): WaveDamping {
        fun mean(top: Double, bottom: Double, rate: (Double) -> Double): Double {
            val steps = MASS_STEPS
            return (0 until steps).sumOf { rate(top + (bottom - top) * (it + 0.5) / steps) } / steps
        }
        val surface = levels.surfacePressurePa
        return WaveDamping(
            0.0, 0.0, 0.0, mixing,
            levelFrictionPerSecond = DoubleArray(levels.levelCount) {
                mean(levels.interfacePressuresPa[it] / surface, levels.interfacePressuresPa[it + 1] / surface, friction)
            },
            interfaceThermalPerSecond = DoubleArray(levels.interiorCount) {
                mean(levels.levelPressurePa[it] / surface, levels.levelPressurePa[it + 1] / surface, cooling)
            }
        )
    }

    private const val MASS_STEPS = 1000

    /** Hoskins and Karoly's five layers' bottom one, sigma 0.8 to 1. */
    private const val HK_LOWEST_LAYER_TOP = 0.8

    /** Their top one, sigma 0 to 0.2. */
    private const val HK_TOP_LAYER_BOTTOM = 0.2

    /**
     * Their damping for the thermal sources (section 3b): velocity and temperature on the lowest level
     * at 5 days, temperature above at 10 days, put on the same air ([byMass]).
     */
    fun hoskinsKarolyHeatDamping(levels: AtmosphereLevels, radiusMeters: Double) = byMass(
        levels, hoskinsKarolyMixing(radiusMeters),
        friction = { sigma -> if (sigma >= HK_LOWEST_LAYER_TOP) WaveDamping.perDays(5.0) else 0.0 },
        cooling = { sigma -> if (sigma >= HK_LOWEST_LAYER_TOP) WaveDamping.perDays(5.0) else WaveDamping.perDays(10.0) }
    )

    /** Their damping for the mountain (section 4b): velocity (10, -, -, -, 5) days and temperature (10, 10, 10, 10, 5), top first ([byMass]). */
    fun hoskinsKarolyMountainDamping(levels: AtmosphereLevels, radiusMeters: Double) = byMass(
        levels, hoskinsKarolyMixing(radiusMeters),
        friction = { sigma ->
            when {
                sigma >= HK_LOWEST_LAYER_TOP -> WaveDamping.perDays(5.0)
                sigma <= HK_TOP_LAYER_BOTTOM -> WaveDamping.perDays(10.0)
                else -> 0.0
            }
        },
        cooling = { sigma -> if (sigma >= HK_LOWEST_LAYER_TOP) WaveDamping.perDays(5.0) else WaveDamping.perDays(10.0) }
    )

    /** Their mountain (section 4b): `cos^2` in a circle of 45 degrees' diameter at 30 N, 2 km high, its zonal mean removed. */
    fun hoskinsKarolyMountain(grid: SphericalGrid, longitude: Double = PI): DoubleArray =
        WaveFixtures.withoutZonalMean(grid, WaveFixtures.ellipse(grid, 30 * DEGREES, longitude, 22.5 * DEGREES, 22.5 * DEGREES).let { shape ->
            DoubleArray(shape.size) { 2000.0 * shape[it] }
        })

    const val MOUNTAIN_RADIUS = 22.5 * DEGREES

    /**
     * Their circular midlatitude source (section 3c): `cos^2` in a circle of 16 degrees' radius at 45 N,
     * the area of their subtropical ellipse, deep (`sin(pi sigma)`), 2.5 K/day vertically averaged at its
     * center, its zonal mean removed.
     */
    fun hoskinsKarolyHeating(grid: SphericalGrid, levels: AtmosphereLevels, latitude: Double = 45 * DEGREES): WaveForcing {
        val shape = WaveFixtures.ellipse(grid, latitude, PI, 16 * DEGREES, 16 * DEGREES)
        val column = DoubleArray(grid.cellCount) { 2.5 / SECONDS_PER_DAY * shape[it] }
        return WaveForcing(WaveForcing.heatingFromColumn(levels, WaveFixtures.withoutZonalMean(grid, column), WaveFixtures::deepProfile))
    }

    /** A wave train read off an upper level: its extrema in order from the source, the great circle they lie on and their spacing. */
    class WaveTrain(
        val extrema: List<WaveFixtures.Extremum>,
        val worstOffCircleDegrees: Double,
        val meanOffCircleDegrees: Double,
        val wavelengthMeters: Double,
        val meanLatitudeDegrees: Double
    )

    /**
     * The train in [field] downstream of a source at ([sourceLatitude], [sourceLongitude]) of radius
     * [sourceRadius]: the extrema at least [TRAIN_SHARE] of the field's largest, poleward of the source and
     * within 150 degrees east of it, beyond the source's own radius (where the forced response, not
     * the free wave, stands). The wavelength is twice the mean arc between successive extrema of
     * opposite sign along the best great circle through the source.
     */
    fun waveTrain(grid: SphericalGrid, field: DoubleArray, sourceLatitude: Double, sourceLongitude: Double, sourceRadius: Double): WaveTrain {
        val source = WaveFixtures.unit(sourceLatitude, sourceLongitude)
        val found = WaveFixtures.extrema(grid, field, TRAIN_SHARE, sourceLatitude, 88 * DEGREES).filter {
            val east = Math.floorMod(Math.round(Math.toDegrees(it.longitude - sourceLongitude)).toInt(), 360)
            east in 0..TRAIN_REACH_DEGREES && WaveFixtures.arc(source, WaveFixtures.unit(it.latitude, it.longitude)) > sourceRadius
        }.sortedBy { WaveFixtures.arc(source, WaveFixtures.unit(it.latitude, it.longitude)) }
        // Keep the train's sign alternating: a second extremum of the same sign beside the first is the
        // same lobe's shoulder.
        val train = mutableListOf<WaveFixtures.Extremum>()
        for (extremum in found) {
            if (train.isEmpty() || (train.last().value > 0) != (extremum.value > 0)) train += extremum
            else if (abs(extremum.value) > abs(train.last().value)) train[train.size - 1] = extremum
        }
        val pole = WaveFixtures.bestGreatCircle(source, train)
        val offsets = train.map { Math.toDegrees(WaveFixtures.offCircle(pole, it)) }
        val heading = WaveFixtures.cross(pole, source)
        val positions = train.map { extremum ->
            val point = WaveFixtures.unit(extremum.latitude, extremum.longitude)
            kotlin.math.atan2(WaveFixtures.dot(point, heading), WaveFixtures.dot(point, source))
        }
        val spacings = positions.zipWithNext { first, second -> abs(second - first) }
        val wavelength = if (spacings.isEmpty()) Double.NaN else 2 * spacings.average() * grid.radiusMeters
        return WaveTrain(train, offsets.maxOrNull() ?: Double.NaN, offsets.average(), wavelength, Math.toDegrees(train.map { it.latitude }.average()))
    }

    /**
     * The smallest extremum, as a share of the field's largest, counted in a train: the third
     * extremum of the jets' mountain train stands at a fifth of the largest and the super-rotation's
     * at near a half, so this takes both.
     */
    private const val TRAIN_SHARE = 0.15
    private const val TRAIN_REACH_DEGREES = 150

    /**
     * The stationary barotropic wavelength on the ground at [latitude] for [state]'s wind at [level]:
     * `2 pi cos(phi) / K_s`, `K_s = (beta_M / u_M)^(1/2)` in Hoskins and Karoly's Mercator coordinates
     * (their 5.10 and 5.16), from the rows' own winds by centered differences.
     */
    fun stationaryWavelengthMeters(state: ZonalBasicState, level: Int, latitude: Double): Double {
        val grid = state.grid
        val radius = grid.radiusMeters
        val rows = grid.rows
        val wind = state.zonalWindAtLevels[level]
        fun mercatorWind(row: Int) = wind[row] / grid.cosLatitude[row]
        // d/dy = (cos phi / a) d/dphi; latitude falls with the row.
        fun inner(row: Int): Double {
            val north = grid.cosLatitude[row - 1].let { it * it } * mercatorWind(row - 1)
            val south = grid.cosLatitude[row + 1].let { it * it } * mercatorWind(row + 1)
            return (north - south) / (2 * grid.rowSpacingRadians) * grid.cosLatitude[row] / radius / (grid.cosLatitude[row] * grid.cosLatitude[row])
        }
        val row = (2 until rows - 2).minByOrNull { abs(grid.latitudeRadians[it] - latitude) }!!
        val cosine = grid.cosLatitude[row]
        val curvature = (inner(row - 1) - inner(row + 1)) / (2 * grid.rowSpacingRadians) * cosine / radius
        val betaMercator = 2 * spin * cosine * cosine / radius - curvature
        val stationary = sqrt(betaMercator / mercatorWind(row))
        return 2 * PI * cosine / stationary
    }

    // ---------------------------------------------------------------- Rodwell and Hoskins (2001)

    /**
     * Their idealized monsoon (section 3b): deep heating at 25 N, 90 E, maximizing at 400 hPa at 5 K a
     * day, elliptical; its horizontal extent is not stated in the paper (it is "as used in" their 1996
     * paper, which could not be opened), so a `cos^2` ellipse of 10 degrees north-south and 20 east-west
     * is taken, which reaches the 35 N their section cuts at, as their figure's ascent there shows.
     */
    fun rodwellHoskinsHeating(grid: SphericalGrid, levels: AtmosphereLevels): WaveForcing {
        val shape = WaveFixtures.ellipse(grid, 25 * DEGREES, 90 * DEGREES, 10 * DEGREES, 20 * DEGREES)
        val profile = WaveFixtures.peakedProfile(0.4)
        return WaveForcing(Array(levels.interiorCount) { interior ->
            val share = profile(levels.interiorPressurePa[interior] / levels.surfacePressurePa)
            DoubleArray(grid.cellCount) { 5.0 / SECONDS_PER_DAY * share * shape[it] }
        })
    }

    /**
     * Their damping (section 2 and 3b): a drag in the lowest two of their fifteen levels, sigma 0.967 at
     * a day and 0.887 at five, which over their layers (about 0.066 and 0.094 of the column) is a
     * column drag of 0.085 a day; here it is put on the layers below sigma 0.84 in proportion to the
     * mass of each that lies there. No Newtonian relaxation in their first experiment; a steady
     * linear model needs some, so their standard relaxation of 25 days is taken.
     */
    fun rodwellHoskinsDamping(levels: AtmosphereLevels, radiusMeters: Double): WaveDamping {
        val localRate = RH_COLUMN_DRAG_PER_DAY / (1 - RH_DRAG_TOP_SIGMA) / SECONDS_PER_DAY
        return byMass(
            levels, hoskinsKarolyMixing(radiusMeters),
            friction = { sigma -> if (sigma >= RH_DRAG_TOP_SIGMA) localRate else 0.0 },
            cooling = { WaveDamping.perDays(RH_RELAXATION_DAYS) }
        )
    }

    private const val RH_DRAG_TOP_SIGMA = 0.84
    private const val RH_COLUMN_DRAG_PER_DAY = 0.085
    private const val RH_RELAXATION_DAYS = 25.0

    /** Their section's figures at 35 N: where the descent and the low-level flow are, east of the heating positive. */
    class MonsoonFigures(
        val descentOffsetDegrees: Double,
        val descentHpaPerHour: Double,
        val descentPressureHpa: Double,
        val equatorwardOffsetDegrees: Double,
        val equatorwardMps: Double,
        val polewardOffsetDegrees: Double,
        val polewardMps: Double,
        val response: WaveResponse
    )

    fun monsoon(state: ZonalBasicState, damping: WaveDamping): MonsoonFigures {
        val grid = state.grid
        val levels = state.levels
        val response = StationaryWaveModel(state, damping).solve(rodwellHoskinsHeating(grid, levels))
        val row = (0 until grid.rows).minByOrNull { abs(grid.latitudeRadians[it] - 35 * DEGREES) }!!
        fun offset(column: Int) = Math.toDegrees((column + 0.5) * grid.columnSpacingRadians) - 90.0
        var bestOmega = -1.0; var bestColumn = 0; var bestPressure = 0.0
        for (pressure in (2..8).map { it * 10_000.0 }) {
            val omega = WaveFixtures.verticalMotionAt(response, pressure)
            for (column in 0 until grid.columns) {
                val value = omega[row * grid.columns + column]
                if (value > bestOmega) { bestOmega = value; bestColumn = column; bestPressure = pressure }
            }
        }
        val lowest = response.northwardAtCenters(levels.levelCount - 1)
        val line = (0 until grid.columns).map { lowest[row * grid.columns + it] }
        val equatorward = line.indices.minByOrNull { line[it] }!!
        val poleward = line.indices.maxByOrNull { line[it] }!!
        return MonsoonFigures(
            offset(bestColumn), bestOmega * 36.0, bestPressure / 100, offset(equatorward), line[equatorward], offset(poleward), line[poleward], response
        )
    }
}
