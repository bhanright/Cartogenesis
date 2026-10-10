package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The boundary layer under the stationary waves, for each calendar half-year: the sea-level
 * pressure, the surface wind that pressure drives, and the vertical motion at the layer's top.
 *
 * **The atmosphere** is [StationaryWaveModel] on [LEVEL_COUNT] equal-mass levels about
 * Jablonowski and Williamson's balanced basic state, on the atmosphere's own grid
 * ([SphericalGrid.forAtmosphere]). Three things force it, all departures from the zonal mean:
 *
 * - **The land and the sea.** The lower troposphere, every interface below Nakamura and Miyasaka's
 *   `sigma` of [SHALLOW_HEATING_TOP_SHARE], is relaxed toward the energy balance's own surface
 *   temperature at sea level: each cell's blend of its band's land and marine columns for the
 *   half-year ([ZonalClimate]). The rate is the energy balance's surface exchange,
 *   [EnergyBalance.SURFACE_EXCHANGE_W_PER_M2_C], over the heat capacity of the air it heats, so the
 *   relaxation is the same exchange the energy balance already spends, not a new strength. The
 *   weather noise, the altitude's lapse and the currents' anomaly are not the energy balance's and
 *   are not in it; the currents' warmth is the marine air's, a later chunk's (docs/TODO.md).
 * - **The terrain**, lifting the basic state's surface wind.
 * - **The latent heat of the rain's condensation**, when the climate couples it to the march
 *   ([Solver], [AtmosphereCoupling]), spread up each column by [latentHeatingShape].
 *
 * All are carried down to the atmosphere's grid spread to a quarter of the deformation radius on
 * the ground ([WaveForcing.fromGround]).
 *
 * **The sea-level pressure** is the waves' own ([WaveResponse.surfacePressurePa]), carried up to the
 * map by its double Fourier series ([AtmosphereRemap.outputToGround]), on top of a zonal mean that is the
 * belts' own under the boundary layer's drag balance ([zonalPressurePa]). The belts stay the zonal
 * mean of the surface wind: the zonal mean the waves leave alone.
 *
 * **The surface wind** is that pressure's drag balance on the map's own grid with land's drag and
 * the sea's ([PressureWind.surfaceWind]), so over the open sea its zonal mean is the belts exactly.
 *
 * **The vertical motion at the layer's top** ([boundaryLayerOmega]) is the surface wind's own mass
 * convergence over the model's lowest layer plus the terrain's lift under it: one boundary layer
 * under one drag ([surfaceDragPerSecond]), the model's lowest level's and the surface wind's, so
 * the pressure, the wind and the vertical motion cannot disagree.
 */
internal object BoundaryLayer {

    /**
     * Levels of the dry atmosphere. Against 24 levels the surface pressure of A1-3's benchmarks
     * stands 17 to 21% off at eight and 50 to 65% off at four, where things lie holding at every
     * count; sixteen levels cost about a second a solve (docs/DESIGN_LEDGER.md, A1-3 and A1-4).
     */
    const val LEVEL_COUNT = 8

    /**
     * The top of the shallow land-sea heating, as a share of the ground's pressure: Nakamura and
     * Miyasaka's (2004) `sigma = 0.667`, above which their lower-tropospheric heating alone
     * reproduces 70 to 75% of the summer subtropical highs.
     */
    const val SHALLOW_HEATING_TOP_SHARE = 0.667

    /**
     * The area mean the sea-level pressure is referred to, pascals: the standard atmosphere's
     * 1,013.25 hectopascals. The winds read only its gradient.
     */
    const val SEA_LEVEL_REFERENCE_PA = 101_325.0

    /**
     * Earth's mean wind at 10 m over the ocean and over land, meters a second: Archer and
     * Jacobson's (2005, J. Geophys. Res. 110, D12110) global averages of the year 2000 from 7,753
     * surface stations and buoys, 6.64 and 3.28. Each is a mean of the speed, through every gust
     * and calm, not the speed of the mean wind.
     */
    const val EARTH_OCEAN_WIND_AT_10_M_MPS = 6.64
    const val EARTH_LAND_WIND_AT_10_M_MPS = 3.28

    /**
     * FAO-56's conversion of a wind measured at 10 m to the 2 m its Penman-Monteith reads, over
     * its short grass: `4.87 / ln(67.8 z - 5.42)` at `z = 10`, 0.748 (Allen and others 1998,
     * equation 47 and its example 14).
     */
    val FAO_TEN_METERS_TO_TWO: Double = 4.87 / ln(67.8 * 10.0 - 5.42)

    /**
     * The part of the scalar mean wind the half-year's mean wind does not carry, meters a second:
     * the day-to-day weather, gusts and calms, which is what keeps the sea evaporating where the
     * mean wind turns through zero.
     *
     * Derived from Earth: the mean speed over the ocean, [EARTH_OCEAN_WIND_AT_10_M_MPS], is taken as
     * the mean wind's and the weather's in quadrature, `s^2 = |V|^2 + U^2`, and the mean wind's mean
     * square as the belts' own, whose shape and peak are Earth's zonal-mean surface wind
     * ([SurfaceBelts.zonalShare], [PressureWind.BELT_SPEED_MPS]), averaged over the sphere by area.
     * About 4.3 m/s. One figure for every latitude until storms are solved (docs/TODO.md).
     */
    val TRANSIENT_WIND_MPS: Double = run {
        val steps = 9_000
        var weighted = 0.0
        var area = 0.0
        for (step in 0 until steps) {
            val latitude = (step + 0.5) * 90.0 / steps
            val weight = cos(latitude * PI / 180.0)
            val belt = PressureWind.BELT_SPEED_MPS * SurfaceBelts.zonalShare(latitude.toFloat())
            weighted += weight * belt * belt
            area += weight
        }
        sqrt(EARTH_OCEAN_WIND_AT_10_M_MPS * EARTH_OCEAN_WIND_AT_10_M_MPS - weighted / area)
    }

    /**
     * The surface's drag coefficient over the sea at 10 m, Large and Pond's 1.2e-3, as the ocean's
     * stress takes it ([WaveDamping.SURFACE_DRAG_COEFFICIENT]).
     */
    const val SEA_DRAG_COEFFICIENT = WaveDamping.SURFACE_DRAG_COEFFICIENT

    /**
     * The surface's drag coefficient over land at 10 m: the neutral logarithmic profile over
     * FAO-56's reference surface, `(k / ln((z - d) / z_om))^2` with its grass of 0.12 m, the
     * displacement `d = 2/3 h`, the momentum roughness `z_om = 0.123 h` and von Karman's `k` of
     * 0.41 (Allen and others 1998, equation 4 and its Box 4), read at the 10 m the sea's figure is
     * stated at. About 4.0e-3, 3.3 times the sea's: short grass, the smoothest land, so a floor on
     * the land's drag and not its mean.
     */
    val LAND_DRAG_COEFFICIENT: Double = run {
        val displacementM = 2.0 / 3.0 * FAO_GRASS_HEIGHT_M
        val roughnessM = FAO_ROUGHNESS_PER_HEIGHT * FAO_GRASS_HEIGHT_M
        val profile = VON_KARMAN / ln((DRAG_HEIGHT_M - displacementM) / roughnessM)
        profile * profile
    }

    /** FAO-56's reference grass height, its momentum roughness per height and von Karman's constant. */
    private const val FAO_GRASS_HEIGHT_M = 0.12
    private const val FAO_ROUGHNESS_PER_HEIGHT = 0.123
    private const val VON_KARMAN = 0.41

    /** The height both drag coefficients are stated at, meters. */
    private const val DRAG_HEIGHT_M = 10.0

    /**
     * The boundary layer's thickness, pascals: the dry model's lowest layer, the mass the surface's
     * stress acts on in both the model and the surface wind's balance.
     */
    const val LAYER_THICKNESS_PA = AtmosphereLevels.REFERENCE_SURFACE_PRESSURE_PA / LEVEL_COUNT

    /**
     * The bulk stress's drag on the boundary layer per meter a second of the wind's scalar speed,
     * per second per meter a second, over land when [isLand]: `rho C_D g / dp`, the surface's
     * stress `rho C_D s V` (Large and Pond's form) spread over the layer's mass.
     */
    fun dragPerSpeed(isLand: Boolean): Double {
        val coefficient = if (isLand) LAND_DRAG_COEFFICIENT else SEA_DRAG_COEFFICIENT
        return PressureWind.AIR_DENSITY_KG_PER_M3 * coefficient * DryAir.GRAVITY_MPS2 / LAYER_THICKNESS_PA
    }

    /**
     * The one drag on the boundary layer's wind, per second, over land when [isLand], under a
     * half-year's mean wind of [meanSpeedMps]: the surface's bulk stress `rho C_D s V` on the
     * layer's mass, `k = rho C_D s g / dp`, with `s` the scalar speed the stress feels, the mean
     * wind and the weather's gusts in quadrature ([TRANSIENT_WIND_MPS]), as the sea's evaporation
     * reads it ([scalarWindAt10mMps]).
     *
     * This is the well-mixed layer's momentum budget (de Roode and Siebesma 2020, equations 3, 4,
     * 8 and 10, read; the mixed layer of Stevens and others 2002), whose cross-isobaric transport
     * is the surface stress over `rho f`, so its Ekman pumping is the stress's curl over `rho f`
     * (their equation 7) whatever depth the layer is given. The stress is quadratic in the wind,
     * so a fast wind is held harder than the belts' and the drag is the bulk law's own at every
     * speed. The surface wind's balance ([PressureWind.surfaceWind], at each cell's own speed) and
     * the dry model's lowest level ([damping], at its row's belts) both read it: one layer, one
     * drag. At the belts' 7.5 m/s, 1.2 days over the sea and 8.5 hours over land at eight levels.
     *
     * Not in it: the entrainment of the free troposphere's momentum at the layer's top, their
     * `w_e`, which de Roode and Siebesma find halves the tropics' Ekman pumping (docs/TODO.md).
     */
    fun surfaceDragPerSecond(isLand: Boolean, meanSpeedMps: Double = PressureWind.BELT_SPEED_MPS.toDouble()): Double =
        dragPerSpeed(isLand) * sqrt(meanSpeedMps * meanSpeedMps + TRANSIENT_WIND_MPS * TRANSIENT_WIND_MPS)

    /**
     * How much slower the 10 m wind is over land than over the sea under the same weather, as a
     * share: Archer and Jacobson's land over ocean, 3.28 over 6.64, less the part the drag balance
     * already gives land through its larger drag, `sqrt((f^2 + k_sea^2) / (f^2 + k_land^2))` of the
     * sea's speed at 45 degrees ([surfaceDragPerSecond]). What is left is the land's roughness
     * holding the 10 m wind under the boundary layer's.
     */
    val LAND_ROUGHNESS_SHARE: Double = run {
        val coriolis = PressureWind.coriolisParameter(45f).toDouble()
        val sea = surfaceDragPerSecond(isLand = false)
        val land = surfaceDragPerSecond(isLand = true)
        val balanceShare = sqrt((coriolis * coriolis + sea * sea) / (coriolis * coriolis + land * land))
        EARTH_LAND_WIND_AT_10_M_MPS / EARTH_OCEAN_WIND_AT_10_M_MPS / balanceShare
    }

    /**
     * The mean speed of the wind at 10 m, meters a second, for a cell whose half-year mean wind is
     * [eastwardMps] by [southwardMps] over land when [isLand]: the mean wind and
     * [TRANSIENT_WIND_MPS] in quadrature, times [LAND_ROUGHNESS_SHARE] over land.
     */
    fun scalarWindAt10mMps(eastwardMps: Float, southwardMps: Float, isLand: Boolean): Float {
        val squared = eastwardMps.toDouble() * eastwardMps + southwardMps.toDouble() * southwardMps
        val speed = sqrt(squared + TRANSIENT_WIND_MPS * TRANSIENT_WIND_MPS)
        return (if (isLand) speed * LAND_ROUGHNESS_SHARE else speed).toFloat()
    }

    /**
     * The wind at 2 m over FAO-56's grass for the same cell, meters a second: the land's scalar
     * wind at 10 m, at sea too, since a sea cell's potential is the rate land would have there.
     */
    fun windAt2mMps(eastwardMps: Float, southwardMps: Float): Float =
        (scalarWindAt10mMps(eastwardMps, southwardMps, isLand = true) * FAO_TEN_METERS_TO_TWO).toFloat()

    /** One calendar half-year's boundary layer. */
    class Half(
        /** The belts' zonal-mean wind on each row of the map, meters a second, eastward and southward. */
        val beltEastwardMps: FloatArray,
        val beltSouthwardMps: FloatArray,
        /** The sea-level pressure's zonal mean on each row of the map, pascals. */
        val zonalPressurePa: DoubleArray,
        /** The waves' sea-level pressure on the atmosphere's grid, pascals, zero in each row's mean. */
        val eddyPressureCoarsePa: DoubleArray,
        /** The same carried up to the map, row-major, pascals. */
        val eddyPressurePa: FloatArray,
        /** The surface wind, row-major on the map, meters a second. */
        val eastwardMps: FloatArray,
        val southwardMps: FloatArray,
        /** The atmosphere's whole response on its own grid. */
        val response: WaveResponse,
        /** The column's latent heating it was solved under, watts per square meter on its grid, or none. */
        val latentHeatingWPerM2: DoubleArray? = null
    ) {
        /** The sea-level pressure at a map [cell] of a map [columns] wide, pascals. */
        fun seaLevelPressurePa(cell: Int, columns: Int): Double = zonalPressurePa[cell / columns] + eddyPressurePa[cell]
    }

    /**
     * Both halves, with what they share: the carrying between grids, the levels and the terrain on
     * the atmosphere's grid in meters, spread as the model read it.
     */
    class Atmosphere(
        val remap: AtmosphereRemap,
        val levels: AtmosphereLevels,
        val surfaceDensity: Double,
        val surfaceHeightCoarseMeters: DoubleArray,
        val julyHalf: Half,
        val januaryHalf: Half,
        /** The drag on the model's lowest level on each of its rows, per second ([Solver.dragOfRow]). */
        val dragOfRow: DoubleArray
    ) {
        fun half(julyHalf: Boolean): Half = if (julyHalf) this.julyHalf else januaryHalf
    }

    /**
     * Solves both calendar halves for a world without latent heat, the dry atmosphere the ocean's
     * stress reads and the climate's coupling starts from: [sea] for the land, the coast and the
     * terrain, [zonal] and [marineFraction] for the surface temperature the energy balance gives
     * each cell, and each half's belts on the map's rows, eastward and southward in meters a second
     * ([julyHalfBelts], [januaryHalfBelts]). Each wave is factored once for both halves and let go.
     */
    fun solve(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        zonal: ZonalClimate,
        marineFraction: FloatField,
        julyHalfBelts: PressureWind.Vectors,
        januaryHalfBelts: PressureWind.Vectors
    ): Atmosphere = Solver(config, sea, zonal, marineFraction, julyHalfBelts, januaryHalfBelts, keptWaves = 0).solve(null, null)

    /**
     * One world's boundary layer, ready to be solved for any latent heating: everything that does
     * not depend on the rain, built once. The basic state and the drag are the same in both
     * calendar halves, so the first [keptWaves] waves are factored once here and back-substitute
     * at each solve after, two a coupling lap, and the others are factored afresh at each solve,
     * both halves at once (every wave's factors are about 700 MB on Earth's planet at eight levels;
     * [wavesToKeep] decides). The answer is the same to the bit however many are kept.
     */
    class Solver(
        val config: WorldGenConfig,
        val sea: SeaLevelResult,
        zonal: ZonalClimate,
        marineFraction: FloatField,
        private val julyHalfBelts: PressureWind.Vectors,
        private val januaryHalfBelts: PressureWind.Vectors,
        keptWaves: Int
    ) {
        private val cellsAcross = config.width
        private val cellsDown = config.height
        val coarse: SphericalGrid = SphericalGrid.forAtmosphere(config.scale)
        val remap = AtmosphereRemap(cellsAcross, cellsDown, coarse)
        val levels: AtmosphereLevels = AtmosphereLevels.equalMass(LEVEL_COUNT)
        private val state = ZonalBasicState.jablonowskiWilliamson(coarse, levels)

        /**
         * Each coarse row's drag on the lowest level: the row's mean of the map's by area, land's and
         * the sea's, each at the speed of its row's belts over the two halves, the root mean square
         * (the model's basic state is the same in both).
         */
        val dragOfRow: DoubleArray = run {
            val ground = FloatArray(cellsAcross * cellsDown) { cell ->
                val row = cell / cellsAcross
                val squared = 0.5 * (julyHalfBelts.eastwardMps[row].squared() + julyHalfBelts.southwardMps[row].squared() +
                    januaryHalfBelts.eastwardMps[row].squared() + januaryHalfBelts.southwardMps[row].squared())
                surfaceDragPerSecond(sea.isLand[cell], sqrt(squared)).toFloat()
            }
            val mean = remap.areaMean(ground)
            DoubleArray(coarse.rows) { row ->
                var sum = 0.0
                for (column in 0 until coarse.columns) sum += mean[row * coarse.columns + column]
                sum / coarse.columns
            }
        }
        val model = StationaryWaveModel(state, damping(levels, dragOfRow))
        private val widthInRows = WaveForcing.groundWidthInRows(remap)

        /** The terrain on the atmosphere's grid in meters, spread as the model reads it. */
        val terrain: DoubleArray = run {
            val heightMeters = FloatArray(cellsAcross * cellsDown) { cell ->
                if (sea.isLand[cell]) config.scale.metresAboveShoreline(sea.relativeElevation.data[cell]).coerceAtLeast(0f) else 0f
            }
            WaveForcing.fromGround(remap, levels, null, heightMeters) { 0.0 }.surfaceHeightMeters!!
        }

        /**
         * One half's surface temperature at sea level on the atmosphere's grid, degrees Celsius:
         * the energy balance's blend of its band's land and marine columns, which the shallow
         * heating relaxes toward and the latent heating's profile is read from.
         */
        private fun surfaceCoarseC(zonal: ZonalClimate, marineFraction: FloatField, season: Season): DoubleArray {
            val surfaceC = FloatArray(cellsAcross * cellsDown)
            for (row in 0 until cellsDown) {
                val latitude = ClimateStage.latitudeOf(row, cellsDown)
                val landC = zonal.landC(latitude, season)
                val seaC = zonal.seaC(latitude, season)
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    surfaceC[cell] = landC + (seaC - landC) * marineFraction.data[cell]
                }
            }
            return remap.forcing(surfaceC, widthInRows)
        }

        private val julySurfaceC = surfaceCoarseC(zonal, marineFraction, Season.JULY_HALF)
        private val januarySurfaceC = surfaceCoarseC(zonal, marineFraction, Season.JANUARY_HALF)
        private val shallowRates = shallowRelaxationPerSecond(levels)
        private val factored: StationaryWaveModel.Factored = model.factorize(keptWaves)

        /**
         * The forcing of one half: the shallow land-sea heating toward [surfaceC], plus
         * [latentWPerM2] (the column's latent heating on the atmosphere's grid, watts per square
         * meter, or none) spread up the column by [latentHeatingShape], and the terrain.
         */
        private fun forcing(surfaceC: DoubleArray, latentWPerM2: DoubleArray?): WaveForcing {
            val latent = latentWPerM2?.let { latentHeatingKelvinPerSecond(levels, it, surfaceC) }
            val heating = Array(levels.interiorCount) { interior ->
                DoubleArray(coarse.cellCount) { cell ->
                    surfaceC[cell] * shallowRates[interior] + (latent?.get(interior)?.get(cell) ?: 0.0)
                }
            }
            return WaveForcing(heating, terrain)
        }

        /**
         * Both halves under the latent heating [julyLatentWPerM2] and [januaryLatentWPerM2], each
         * the column's heating on the atmosphere's grid in watts per square meter (none for the
         * dry atmosphere).
         */
        fun solve(julyLatentWPerM2: DoubleArray?, januaryLatentWPerM2: DoubleArray?): Atmosphere {
            val forcings = listOf(forcing(julySurfaceC, julyLatentWPerM2), forcing(januarySurfaceC, januaryLatentWPerM2))
            val responses = factored.solveEach(forcings)
            return Atmosphere(
                remap, levels, state.surfaceDensity, terrain,
                half(responses[0], julyHalfBelts, julyLatentWPerM2), half(responses[1], januaryHalfBelts, januaryLatentWPerM2),
                dragOfRow
            )
        }

        private fun half(response: WaveResponse, belts: PressureWind.Vectors, latentWPerM2: DoubleArray?): Half {
            val eddyCoarse = response.surfacePressurePa
            val eddyGround = remap.outputToGround(eddyCoarse)
            val wind = PressureWind.surfaceWind(config, sea, eddyGround, belts.eastwardMps, belts.southwardMps)
            return Half(
                belts.eastwardMps, belts.southwardMps,
                zonalPressurePa(cellsDown, config.scale.radiusMeters, belts.eastwardMps, belts.southwardMps),
                eddyCoarse, eddyGround, wind.eastwardMps, wind.southwardMps, response, latentWPerM2
            )
        }
    }

    /**
     * How many waves' factors a world's coupled loop keeps between its laps: as many as take no
     * more than [FACTORS_SHARE_OF_HEAP] of the most heap the platform gives
     * ([com.cartogenesis.worldgen.concurrent.maximumHeapBytes]), every wave in a large heap. A kept
     * wave back-substitutes each lap; any other factors afresh. The two compute the same numbers in
     * the same order ([StationaryWaveModel.Factored]), so the count moves the cost of a world and
     * never the world (`StationaryWaveModelTest`).
     */
    fun wavesToKeep(config: WorldGenConfig): Int {
        val coarse = SphericalGrid.forAtmosphere(config.scale)
        val blockSize = 4 * LEVEL_COUNT - 1
        val waves = coarse.columns / 3
        val bytesPerWave = coarse.rows.toLong() * FACTOR_BLOCKS_PER_ROW * blockSize * blockSize * COMPLEX_BYTES
        val budget = com.cartogenesis.worldgen.concurrent.maximumHeapBytes() / FACTORS_SHARE_OF_HEAP
        return (budget / bytesPerWave).coerceIn(0L, waves.toLong()).toInt()
    }

    /**
     * The share of the heap the factors may take, as its inverse: an eighth. Every wave's factors
     * are about 700 MB on Earth's planet at eight levels, kept whole in a heap of 5.6 GB or more,
     * the application's (three quarters of the machine's memory); the test workers' 3 to 4 GB,
     * which hold a world of 1,024 rows, its march and a drawing beside them, ran out of heap with
     * every wave kept at a quarter, and keep half of them at an eighth.
     */
    const val FACTORS_SHARE_OF_HEAP = 8L

    /** The block-tridiagonal factors' blocks per row (below, on and above the diagonal) and a complex double's bytes. */
    private const val FACTOR_BLOCKS_PER_ROW = 3L
    private const val COMPLEX_BYTES = 16L

    /**
     * The dry model's damping: [WaveDamping.forWorlds]'s free atmosphere and mixing, the shallow
     * relaxation's rate added to the interfaces it heats (relaxing toward the surface is a cooling
     * toward it as much as a heating), and in place of its one surface stress the boundary layer's
     * own drag on the lowest level, row by row ([dragOfRow], the map's [surfaceDragPerSecond] by
     * area), so the model's lowest layer and the surface wind's balance are one layer under one drag.
     */
    fun damping(levels: AtmosphereLevels, dragOfRow: DoubleArray): WaveDamping {
        val base = WaveDamping.forWorlds(levels, PressureWind.AIR_DENSITY_KG_PER_M3.toDouble())
        return WaveDamping(
            base.barotropicFrictionPerSecond, base.baroclinicFrictionPerSecond, base.thermalPerSecond,
            base.mixingSquareMetersPerSecond, null, shallowRelaxationPerSecond(levels), dragOfRow
        )
    }

    /**
     * The latent heating at each interior interface of [levels], kelvin a second, `[interface][cell]`,
     * of a column heating [columnWPerM2] (watts per square meter, `L_v` times the condensation) over
     * columns whose surface stands at [surfaceC] degrees Celsius at sea level: the column's mean
     * heating rate `Q g / (c_p p_s)` spread up it by [latentHeatingShape], which holds the column's
     * mass-weighted mean to it, so the area integral of the heating is the condensation's.
     */
    fun latentHeatingKelvinPerSecond(levels: AtmosphereLevels, columnWPerM2: DoubleArray, surfaceC: DoubleArray): Array<DoubleArray> {
        val perWatt = DryAir.GRAVITY_MPS2 / (DryAir.HEAT_CAPACITY_J_PER_KG_K * levels.surfacePressurePa)
        val result = Array(levels.interiorCount) { DoubleArray(columnWPerM2.size) }
        val shape = DoubleArray(levels.interiorCount)
        for (cell in columnWPerM2.indices) {
            latentHeatingShape(levels, surfaceC[cell], shape)
            val columnRate = columnWPerM2[cell] * perWatt
            for (interior in 0 until levels.interiorCount) result[interior][cell] = columnRate * shape[interior]
        }
        return result
    }

    /**
     * The latent heating's shape up a column whose surface stands at [surfaceC] degrees Celsius,
     * one weight per interior interface of [levels] into [into], normalized so the column's
     * mass-weighted mean is one (each interface standing for the mass between its levels).
     *
     * A half-sine in height from the cloud's base to the top of the condensing column. The base is
     * the boundary layer's top, the model's lowest interior interface: the layer under it is the
     * well-mixed boundary layer whose drag is the surface's ([surfaceDragPerSecond]), the
     * subcloud layer of Stevens and others' (2002) mixed layer, which the air rises out of and
     * condenses above, so the latent heat is released in the free troposphere and none at its
     * base. The top is the height on the moist adiabat from the surface above which the saturated
     * column holds [CONDENSING_TOP_SHARE] of its water ([ColumnWater.moistAdiabatHeights]), so the
     * heating is deep over a warm column and shallow over a cold one, as the condensation it stands
     * for is. On a column at 27 C the base is 1.3 km, the top 12.9 km and the peak 7.1 km, inside
     * Schumacher, Houze and Kraucunas's (2004, section 3, read) heating maxima of 6.5 km for the
     * tropics' mean stratiform share of 40% and 7.5 km at 70% (4.5 km for convective rain alone);
     * at 10 C the top is 8.7 km and at 0 C 7.4 km.
     *
     * Heated at its base, the boundary layer's own top, the coupled loop does not settle: the
     * heating there is spread over the whole lower layer the model's eight levels give it, its
     * convergence feeds the march's column water back at a gain near one, and equatorial cells
     * condense five kilowatts a square meter (docs/DESIGN_LEDGER.md, A1-5).
     */
    fun latentHeatingShape(levels: AtmosphereLevels, surfaceC: Double, into: DoubleArray) {
        val position = ((surfaceC - SHAPE_TABLE_COLDEST_C) / SHAPE_TABLE_STEP_C).coerceIn(0.0, (SHAPE_TABLE_ROWS - 1).toDouble())
        val below = position.toInt().coerceAtMost(SHAPE_TABLE_ROWS - 2)
        val share = position - below
        val table = shapeTable(levels)
        val count = levels.interiorCount
        for (interior in 0 until count) {
            into[interior] = table[below * count + interior] * (1.0 - share) + table[(below + 1) * count + interior] * share
        }
    }

    /**
     * The share of a saturated column's water above the top of its latent heating: a thousandth.
     * With it a column at 27 C, the warm tropical ocean's, peaks at Schumacher and others' 6.5 km
     * (see [latentHeatingShape]); a round figure checked against theirs, not fitted to a guard.
     */
    const val CONDENSING_TOP_SHARE = 1.0e-3

    /** The shape table's span and step in surface temperature, degrees Celsius. */
    private const val SHAPE_TABLE_COLDEST_C = -80.0
    private const val SHAPE_TABLE_STEP_C = 0.5
    private const val SHAPE_TABLE_ROWS = 261

    /** [latentHeatingShape]'s table for one set of levels and the levels it was built for. */
    private class ShapeTable(val interfacePressuresPa: DoubleArray, val values: DoubleArray)

    @kotlin.concurrent.Volatile
    private var lastShapeTable: ShapeTable? = null

    /** [latentHeatingShape]'s table for [levels], built once per set of levels. */
    private fun shapeTable(levels: AtmosphereLevels): DoubleArray {
        lastShapeTable?.let { cached -> if (cached.interfacePressuresPa.contentEquals(levels.interfacePressuresPa)) return cached.values }
        val count = levels.interiorCount
        val table = DoubleArray(SHAPE_TABLE_ROWS * count)
        val pressuresKpa = DoubleArray(count) { levels.interiorPressurePa[it] / PASCALS_PER_KPA }
        // The cloud's base: the boundary layer's top, the lowest interior interface.
        val baseIndex = count - 1
        for (entry in 0 until SHAPE_TABLE_ROWS) {
            val surfaceC = SHAPE_TABLE_COLDEST_C + entry * SHAPE_TABLE_STEP_C
            val adiabat = ColumnWater.moistAdiabatHeights(surfaceC, pressuresKpa, CONDENSING_TOP_SHARE)
            val base = adiabat.heightsMeters[baseIndex]
            var columnMean = 0.0
            for (interior in 0 until count) {
                val height = adiabat.heightsMeters[interior]
                val weight = if (height > base && height < adiabat.topMeters) sin(PI * (height - base) / (adiabat.topMeters - base)) else 0.0
                table[entry * count + interior] = weight
                columnMean += weight * massShare(levels, interior)
            }
            if (columnMean <= 0.0) {
                // A column too cold to condense above its boundary layer heats the interface above it alone.
                table[entry * count + baseIndex - 1] = 1.0
                columnMean = massShare(levels, baseIndex - 1)
            }
            for (interior in 0 until count) table[entry * count + interior] /= columnMean
        }
        lastShapeTable = ShapeTable(levels.interfacePressuresPa.copyOf(), table)
        return table
    }

    /** The share of the column's mass interior interface [interior] of [levels] stands for: the mass between its two levels. */
    private fun massShare(levels: AtmosphereLevels, interior: Int): Double =
        (levels.levelPressurePa[interior + 1] - levels.levelPressurePa[interior]) / levels.surfacePressurePa

    /** Pascals in a kilopascal. */
    private const val PASCALS_PER_KPA = 1_000.0

    private fun Float.squared(): Double = toDouble() * toDouble()

    /**
     * The rate each interior interface of [levels] relaxes toward the surface's temperature, per
     * second: the energy balance's surface exchange over the heat capacity of every interface below
     * [SHALLOW_HEATING_TOP_SHARE], each standing for the mass between the levels either side of it,
     * `rate = K g / (c_p dp)`, and zero above. 1.2 days at eight levels (250 hPa of air), the same
     * as at four, where the one interface below the share stands for 250 hPa too.
     */
    fun shallowRelaxationPerSecond(levels: AtmosphereLevels): DoubleArray {
        val shallow = BooleanArray(levels.interiorCount) {
            levels.interiorPressurePa[it] / levels.surfacePressurePa > SHALLOW_HEATING_TOP_SHARE
        }
        var massPa = 0.0
        for (interior in 0 until levels.interiorCount) {
            if (shallow[interior]) massPa += levels.levelPressurePa[interior + 1] - levels.levelPressurePa[interior]
        }
        require(massPa > 0.0) { "no interface below sigma $SHALLOW_HEATING_TOP_SHARE to heat" }
        val rate = EnergyBalance.SURFACE_EXCHANGE_W_PER_M2_C * DryAir.GRAVITY_MPS2 / (DryAir.HEAT_CAPACITY_J_PER_KG_K * massPa)
        return DoubleArray(levels.interiorCount) { if (shallow[it]) rate else 0.0 }
    }

    /**
     * The sea-level pressure's zonal mean on each of [rows] rows of the map, pascals: the belts'
     * wind, [beltEastwardMps] and [beltSouthwardMps] per row, under the sea's drag balance,
     * `dp/dy = -rho (k v + f u)` northward, integrated down the rows by the trapezoid rule and
     * referred to [SEA_LEVEL_REFERENCE_PA] by area on the sphere.
     */
    fun zonalPressurePa(rows: Int, radiusMeters: Double, beltEastwardMps: FloatArray, beltSouthwardMps: FloatArray): DoubleArray {
        val density = PressureWind.AIR_DENSITY_KG_PER_M3.toDouble()
        val northwardGradient = DoubleArray(rows) { row ->
            val coriolis = PressureWind.coriolisParameter(ClimateStage.latitudeOf(row, rows)).toDouble()
            val seaDrag = PressureWind.beltDrag(beltEastwardMps[row], beltSouthwardMps[row])
            -density * (seaDrag * -beltSouthwardMps[row] + coriolis * beltEastwardMps[row])
        }
        val spacingMeters = radiusMeters * PI / rows
        val pressure = DoubleArray(rows)
        // Southward, a row at a time: the pressure falls by the northward gradient times the step.
        for (row in 1 until rows) {
            pressure[row] = pressure[row - 1] - 0.5 * (northwardGradient[row - 1] + northwardGradient[row]) * spacingMeters
        }
        var weighted = 0.0
        var area = 0.0
        for (row in 0 until rows) {
            val weight = cos(ClimateStage.latitudeOf(row, rows) * PI / 180.0)
            weighted += pressure[row] * weight
            area += weight
        }
        val offset = SEA_LEVEL_REFERENCE_PA - weighted / area
        for (row in 0 until rows) pressure[row] += offset
        return pressure
    }

    /**
     * The vertical motion at the boundary layer's top on the atmosphere's grid, pascals a second,
     * positive down, in its two parts: the surface wind's mass convergence over the model's lowest
     * layer, which is the boundary layer ([convergencePaPerSecond], `dp div(V)`), and the lift of
     * that wind over the terrain the model reads ([terrainPaPerSecond], `-rho_s g V . grad(h)`):
     * continuity integrated up from the ground through the layer.
     */
    class BoundaryLayerOmega(val convergencePaPerSecond: DoubleArray, val terrainPaPerSecond: DoubleArray) {
        /** Both together, the vertical motion itself. */
        val totalPaPerSecond = DoubleArray(convergencePaPerSecond.size) { convergencePaPerSecond[it] + terrainPaPerSecond[it] }
    }

    /**
     * [half]'s [BoundaryLayerOmega]. The convergence is the surface wind's own divergence on the
     * map's grid, where the wind is, carried down as the forcing is, spread to
     * [WaveForcing.GROUND_FORCING_WIDTH_METERS] on the ground ([AtmosphereRemap.forcing]), which
     * conserves it, so it is the large-scale part the model resolves. The terrain's part is the
     * large-scale wind ([largeScaleWind]) over the terrain the model read; the ground keeps its own
     * climb.
     */
    fun boundaryLayerOmega(atmosphere: Atmosphere, half: Half): BoundaryLayerOmega {
        val remap = atmosphere.remap
        val coarse = remap.coarse
        val convergence = convergenceOmegaPaPerSecond(atmosphere, half)
        val (largeEast, largeNorth) = largeScaleWind(remap, half.eastwardMps, half.southwardMps)
        val operators = SphericalOperators(coarse)
        val slope = operators.gradient(atmosphere.surfaceHeightCoarseMeters)
        val slopeNorth = operators.northAtCenters(slope)
        val terrain = DoubleArray(coarse.cellCount) { cell ->
            val climb = largeEast[cell] * slope.eastAtCenters[cell] + largeNorth[cell] * slopeNorth[cell]
            -atmosphere.surfaceDensity * DryAir.GRAVITY_MPS2 * climb
        }
        return BoundaryLayerOmega(convergence, terrain)
    }

    /**
     * [BoundaryLayerOmega.convergencePaPerSecond] alone: the surface wind's divergence on the map's
     * grid times the layer's thickness, carried down as a forcing is.
     */
    fun convergenceOmegaPaPerSecond(atmosphere: Atmosphere, half: Half): DoubleArray {
        val remap = atmosphere.remap
        val ground = SphericalGrid(remap.groundRows, remap.groundColumns, remap.coarse.radiusMeters)
        val groundOperators = SphericalOperators(ground)
        val columns = remap.groundColumns
        // The northward wind on the map's faces between rows, the mean of the rows either side; the
        // polar faces have no length and carry nothing.
        val northFaces = DoubleArray((remap.groundRows + 1) * columns)
        for (face in 1 until remap.groundRows) {
            for (column in 0 until columns) {
                northFaces[face * columns + column] =
                    -0.5 * (half.southwardMps[(face - 1) * columns + column] + half.southwardMps[face * columns + column])
            }
        }
        val east = DoubleArray(half.eastwardMps.size) { half.eastwardMps[it].toDouble() }
        val divergence = groundOperators.divergence(SphericalOperators.Vector(east, northFaces))
        val convergence = remap.forcing(FloatArray(divergence.size) { divergence[it].toFloat() }, WaveForcing.groundWidthInRows(remap))
        val layerPa = atmosphere.levels.thicknessPa[atmosphere.levels.levelCount - 1]
        return DoubleArray(convergence.size) { layerPa * convergence[it] }
    }

    /**
     * The large-scale vertical velocity at the boundary layer's top on the map, meters a second,
     * positive up, row-major: the layer's own convergence ([convergenceOmegaPaPerSecond]) carried
     * up, `w = -omega / (rho g)`. What the march's sinks read as the atmosphere's ascent and
     * descent, with the ground's own climb over the terrain added cell by cell (`MoistureMarch`):
     * the terrain's large-scale lift ([BoundaryLayerOmega.terrainPaPerSecond]) is that climb
     * smoothed, so the ground's residual climb and it carried up would sum to the ground's whole.
     */
    fun groundAscentMps(atmosphere: Atmosphere, half: Half): FloatArray {
        val omega = atmosphere.remap.outputToGround(convergenceOmegaPaPerSecond(atmosphere, half))
        val perPascal = -1.0 / (PressureWind.AIR_DENSITY_KG_PER_M3 * DryAir.GRAVITY_MPS2)
        return FloatArray(omega.size) { (omega[it] * perPascal).toFloat() }
    }

    /**
     * A wind on the map, [eastwardMps] and [southwardMps], on [remap]'s coarse centers as the model
     * reads a forcing: its Cartesian components each carried down and spread
     * ([WaveForcing.groundWidthInRows]), then turned back into east and north at each coarse center.
     * Returns the eastward then the northward component, meters a second.
     */
    fun largeScaleWind(remap: AtmosphereRemap, eastwardMps: FloatArray, southwardMps: FloatArray): Pair<DoubleArray, DoubleArray> {
        val columns = remap.groundColumns
        val rows = remap.groundRows
        val towardX = FloatArray(columns * rows)
        val towardY = FloatArray(columns * rows)
        val towardZ = FloatArray(columns * rows)
        for (row in 0 until rows) {
            val latitude = PI / 2 - (row + 0.5) * PI / rows
            val sinLatitude = sin(latitude)
            val cosLatitude = cos(latitude)
            for (column in 0 until columns) {
                val cell = row * columns + column
                val longitude = (column + 0.5) * 2.0 * PI / columns
                val east = eastwardMps[cell].toDouble()
                val north = -southwardMps[cell].toDouble()
                towardX[cell] = (-sin(longitude) * east - sinLatitude * cos(longitude) * north).toFloat()
                towardY[cell] = (cos(longitude) * east - sinLatitude * sin(longitude) * north).toFloat()
                towardZ[cell] = (cosLatitude * north).toFloat()
            }
        }
        val width = WaveForcing.groundWidthInRows(remap)
        val x = remap.forcing(towardX, width)
        val y = remap.forcing(towardY, width)
        val z = remap.forcing(towardZ, width)
        val coarse = remap.coarse
        val east = DoubleArray(coarse.cellCount)
        val north = DoubleArray(coarse.cellCount)
        for (cell in 0 until coarse.cellCount) {
            val row = cell / coarse.columns
            val longitude = (cell % coarse.columns + 0.5) * coarse.columnSpacingRadians
            val sinLatitude = sin(coarse.latitudeRadians[row])
            east[cell] = -sin(longitude) * x[cell] + cos(longitude) * y[cell]
            north[cell] = -sinLatitude * (cos(longitude) * x[cell] + sin(longitude) * y[cell]) + coarse.cosLatitude[row] * z[cell]
        }
        return east to north
    }
}
