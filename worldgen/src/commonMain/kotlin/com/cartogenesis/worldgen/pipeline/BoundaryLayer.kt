package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The boundary layer under the dry stationary waves, for each calendar half-year: the sea-level
 * pressure, the surface wind that pressure drives, and the vertical motion at the layer's top.
 *
 * **The dry atmosphere** is [StationaryWaveModel] on [LEVEL_COUNT] equal-mass levels about
 * Jablonowski and Williamson's balanced basic state, on the atmosphere's own grid
 * ([SphericalGrid.forAtmosphere]). Two things force it, both departures from the zonal mean:
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
 *
 * Both are carried down to the atmosphere's grid spread to a quarter of the deformation radius on
 * the ground ([WaveForcing.fromGround]). There is no latent heat yet.
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
 * convergence over the model's lowest layer plus the terrain's lift under it: one boundary layer,
 * so the pressure, the wind and the vertical motion cannot disagree.
 *
 * Nothing here reads the rain, so nothing here is iterated with the march.
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
     * How much slower the 10 m wind is over land than over the sea under the same weather, as a
     * share: Archer and Jacobson's land over ocean, 3.28 over 6.64, less the part the drag balance
     * already gives land through its larger turn, `cos(40) / cos(25)` of the sea's speed at 45
     * degrees ([PressureWind.CROSS_ISOBAR_LAND_DEGREES]). What is left is the land's roughness
     * holding the 10 m wind under the boundary layer's, 0.58.
     */
    val LAND_ROUGHNESS_SHARE: Double =
        EARTH_LAND_WIND_AT_10_M_MPS / EARTH_OCEAN_WIND_AT_10_M_MPS /
            (cos(PressureWind.CROSS_ISOBAR_LAND_DEGREES * PI / 180.0) / cos(PressureWind.CROSS_ISOBAR_SEA_DEGREES * PI / 180.0))

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
        /** The dry atmosphere's whole response on its own grid. */
        val response: WaveResponse
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
        val januaryHalf: Half
    ) {
        fun half(julyHalf: Boolean): Half = if (julyHalf) this.julyHalf else januaryHalf
    }

    /**
     * Solves both calendar halves for a world: [sea] for the land, the coast and the terrain,
     * [zonal] and [marineFraction] for the surface temperature the energy balance gives each cell,
     * and each half's belts on the map's rows, eastward and southward in meters a second
     * ([julyHalfBelts], [januaryHalfBelts]). The model is factored once for both.
     */
    fun solve(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        zonal: ZonalClimate,
        marineFraction: FloatField,
        julyHalfBelts: PressureWind.Vectors,
        januaryHalfBelts: PressureWind.Vectors
    ): Atmosphere {
        val cellsAcross = config.width
        val cellsDown = config.height
        val coarse = SphericalGrid.forAtmosphere(config.scale)
        val remap = AtmosphereRemap(cellsAcross, cellsDown, coarse)
        val levels = AtmosphereLevels.equalMass(LEVEL_COUNT)
        val state = ZonalBasicState.jablonowskiWilliamson(coarse, levels)
        val model = StationaryWaveModel(state, damping(levels, state.surfaceDensity))

        val heightMeters = FloatArray(cellsAcross * cellsDown) { cell ->
            if (sea.isLand[cell]) config.scale.metresAboveShoreline(sea.relativeElevation.data[cell]).coerceAtLeast(0f) else 0f
        }
        val terrain = WaveForcing.fromGround(remap, levels, null, heightMeters) { 0.0 }.surfaceHeightMeters!!
        val widthInRows = WaveForcing.groundWidthInRows(remap)
        val rates = shallowRelaxationPerSecond(levels)
        fun forcing(season: Season): WaveForcing {
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
            val surfaceCoarse = remap.forcing(surfaceC, widthInRows)
            val heating = Array(levels.interiorCount) { interior ->
                DoubleArray(coarse.cellCount) { surfaceCoarse[it] * rates[interior] }
            }
            return WaveForcing(heating, terrain)
        }
        val (julyResponse, januaryResponse) = model.solveEach(listOf(forcing(Season.JULY_HALF), forcing(Season.JANUARY_HALF)))

        fun half(response: WaveResponse, belts: PressureWind.Vectors): Half {
            val eddyCoarse = response.surfacePressurePa
            val eddyGround = remap.outputToGround(eddyCoarse)
            val wind = PressureWind.surfaceWind(config, sea, eddyGround, belts.eastwardMps, belts.southwardMps)
            return Half(
                belts.eastwardMps, belts.southwardMps,
                zonalPressurePa(cellsDown, config.scale.radiusMeters, belts.eastwardMps, belts.southwardMps),
                eddyCoarse, eddyGround, wind.eastwardMps, wind.southwardMps, response
            )
        }
        return Atmosphere(
            remap, levels, state.surfaceDensity, terrain,
            half(julyResponse, julyHalfBelts), half(januaryResponse, januaryHalfBelts)
        )
    }

    /**
     * The dry model's damping: [WaveDamping.forWorlds], with the shallow relaxation's rate added to
     * the interfaces it heats, since relaxing toward the surface is a cooling toward it as much as a
     * heating.
     */
    fun damping(levels: AtmosphereLevels, surfaceDensity: Double): WaveDamping {
        val base = WaveDamping.forWorlds(levels, surfaceDensity)
        return WaveDamping(
            base.barotropicFrictionPerSecond, base.baroclinicFrictionPerSecond, base.thermalPerSecond,
            base.mixingSquareMetersPerSecond, base.levelFrictionPerSecond, shallowRelaxationPerSecond(levels)
        )
    }

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
        val seaDrag = PressureWind.surfaceDrag(PressureWind.CROSS_ISOBAR_SEA_DEGREES).toDouble()
        val density = PressureWind.AIR_DENSITY_KG_PER_M3.toDouble()
        val northwardGradient = DoubleArray(rows) { row ->
            val coriolis = PressureWind.coriolisParameter(ClimateStage.latitudeOf(row, rows)).toDouble()
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
        val ground = SphericalGrid(remap.groundRows, remap.groundColumns, coarse.radiusMeters)
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
        val (largeEast, largeNorth) = largeScaleWind(remap, half.eastwardMps, half.southwardMps)
        val operators = SphericalOperators(coarse)
        val slope = operators.gradient(atmosphere.surfaceHeightCoarseMeters)
        val slopeNorth = operators.northAtCenters(slope)
        val layerPa = atmosphere.levels.thicknessPa[atmosphere.levels.levelCount - 1]
        val terrain = DoubleArray(coarse.cellCount) { cell ->
            val climb = largeEast[cell] * slope.eastAtCenters[cell] + largeNorth[cell] * slopeNorth[cell]
            -atmosphere.surfaceDensity * DryAir.GRAVITY_MPS2 * climb
        }
        return BoundaryLayerOmega(DoubleArray(coarse.cellCount) { layerPa * convergence[it] }, terrain)
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
