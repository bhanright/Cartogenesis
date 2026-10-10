package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BoundaryLayer
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.DryAir
import com.cartogenesis.worldgen.pipeline.PressureWind
import com.cartogenesis.worldgen.pipeline.Season
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.SphericalOperators
import com.cartogenesis.worldgen.pipeline.ZonalBasicState
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The boundary layer's three fields hold together: the sea-level pressure, the surface wind it
 * drives and the vertical motion at the layer's top, on the standard worlds
 * ([SharedWorlds.DETAIL_ROWS]), and nothing carried up from the atmosphere's grid shows its period.
 *
 * - **The wind is the pressure's.** At every map cell the surface wind meets the drag balance
 *   ([PressureWind.surfaceWind]) of the sea-level pressure carried up and the belts' zonal mean.
 * - **The vertical motion is the wind's.** The layer's mass convergence integrates to nothing
 *   over the sphere, as the divergence theorem asks; and in the middle latitudes it follows the
 *   pressure as Ekman's balance has it, ascent in the lows and descent in the highs.
 * - **The dry model's own vertical motion** at the same interface is printed against it: the
 *   model's lowest layer is the same boundary layer under its own drag (docs/TODO.md).
 * - **No trace of the coarse grid** (conventions rule 13) in the pressure or the vertical motion
 *   carried up ([CoarsePeriod]).
 * - **The jets** of the prescribed basic state against the energy balance's own temperature
 *   gradient by thermal wind, printed.
 */
class BoundaryLayerTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS
        const val ROWS = SharedWorlds.DETAIL_ROWS

        /**
         * How far the wind may stand from the drag balance of the pressure it is computed from, as a
         * share of the balance's own terms: the rounding of the single-precision fields it is
         * carried in, a few parts in ten million, with a margin.
         */
        const val BALANCE_TOLERANCE = 1e-4

        /**
         * How much of the layer's mass convergence may be left over the sphere, as a share of its
         * mean size: the divergence theorem asks nothing, and the operators keep it to rounding.
         */
        const val MASS_TOLERANCE = 1e-9

        /**
         * The least correlation between the layer's eddy vertical motion and minus the eddy
         * pressure's Laplacian, 35 to 65 degrees: Ekman's convergence is `k/f^2` of the Laplacian
         * over the open sea, and the one other term of the drag balance's divergence, the
         * geostrophic wind's `-beta v / f`, is `beta L / k`, about 0.4 of it at the deformation
         * radius on Earth. A correlation over a half says the Ekman term leads.
         */
        const val EKMAN_CORRELATION = 0.5

        /** The middle latitudes the Ekman clause reads, degrees from the equator. */
        const val MIDDLE_FROM_DEGREES = 35.0
        const val MIDDLE_TO_DEGREES = 65.0
    }

    private fun world(seed: Long): WorldMap = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))

    @Test
    fun `the surface wind is the drag balance of the sea-level pressure`() {
        var worst = 0.0
        for (seed in seeds) {
            val world = world(seed)
            val atmosphere = ClimateStage.atmosphere(world.config, world.sea)
            for (half in listOf(atmosphere.julyHalf, atmosphere.januaryHalf)) worst = maxOf(worst, balanceResidual(world, half))
        }
        println("BOUNDARY LAYER the wind's residual against the pressure's drag balance, worst of ${seeds.size} worlds and both halves: %.2e of the balance's terms".format(worst))
        assertTrue(worst < BALANCE_TOLERANCE, "the wind stands $worst from the drag balance of its own pressure")
    }

    /**
     * The largest residual of `k u - f v + (1/rho) dp/dx - F` and `k v + f u + (1/rho) dp/dy`
     * over the map, each over the largest of its terms there, with the pressure the zonal mean
     * plus the eddies carried up, both differentiated on the sphere here.
     */
    private fun balanceResidual(world: WorldMap, half: BoundaryLayer.Half): Double {
        val columns = world.width
        val rows = world.height
        val grid = SphericalGrid.forGround(columns, rows, world.config.scale)
        val operators = SphericalOperators(grid)
        val pressure = DoubleArray(columns * rows) { half.seaLevelPressurePa(it, columns) }
        val gradient = operators.gradient(pressure)
        val gradientNorth = operators.northAtCenters(gradient)
        val seaDrag = PressureWind.surfaceDrag(PressureWind.CROSS_ISOBAR_SEA_DEGREES).toDouble()
        val landDrag = PressureWind.surfaceDrag(PressureWind.CROSS_ISOBAR_LAND_DEGREES).toDouble()
        val density = PressureWind.AIR_DENSITY_KG_PER_M3.toDouble()
        var worst = 0.0
        // The zonal mean's northward gradient is the trapezoid rule's between rows, so it is read at
        // rows away from where it is integrated from; the interior rows are the clause.
        for (row in 2 until rows - 2) {
            val coriolis = PressureWind.coriolisParameter(ClimateStage.latitudeOf(row, rows)).toDouble()
            val beltEast = half.beltEastwardMps[row].toDouble()
            val beltNorth = -half.beltSouthwardMps[row].toDouble()
            val push = seaDrag * beltEast - coriolis * beltNorth
            // The trapezoid's own zonal-mean gradient at this row: the mean of the faces either side.
            val zonalNorth = (half.zonalPressurePa[row - 1] - half.zonalPressurePa[row + 1]) / (2 * grid.rowSpacingMeters)
            val exactZonalNorth = -density * (seaDrag * beltNorth + coriolis * beltEast)
            for (column in 0 until columns) {
                val cell = row * columns + column
                val drag = if (world.sea.isLand[cell]) landDrag else seaDrag
                val east = half.eastwardMps[cell].toDouble()
                val north = -half.southwardMps[cell].toDouble()
                // The eddies' gradient here, and the zonal mean's as the wind was built with it.
                val eddyNorth = gradientNorth[cell] - zonalNorth
                val pressureNorth = eddyNorth + exactZonalNorth
                val zonalResidual = drag * east - coriolis * north + gradient.eastAtCenters[cell] / density - push
                val meridionalResidual = drag * north + coriolis * east + pressureNorth / density
                val scale = maxOf(abs(drag * east), abs(coriolis * north), abs(gradient.eastAtCenters[cell] / density), abs(push),
                    abs(drag * north), abs(coriolis * east), abs(pressureNorth / density), 1e-12)
                worst = maxOf(worst, abs(zonalResidual) / scale, abs(meridionalResidual) / scale)
            }
        }
        return worst
    }

    @Test
    fun `the layer's vertical motion is the wind's convergence, and follows the pressure as Ekman's balance does`() {
        var correlationSum = 0.0
        var correlationCount = 0
        var worstMass = 0.0
        var lowestCorrelation = 1.0
        for (seed in seeds) {
            val world = world(seed)
            val atmosphere = ClimateStage.atmosphere(world.config, world.sea)
            val grid = atmosphere.remap.coarse
            val operators = SphericalOperators(grid)
            for ((name, half) in listOf("July" to atmosphere.julyHalf, "January" to atmosphere.januaryHalf)) {
                val omega = BoundaryLayer.boundaryLayerOmega(atmosphere, half)
                var net = 0.0
                var size = 0.0
                for (cell in 0 until grid.cellCount) {
                    val area = grid.cellAreaSquareMeters[cell / grid.columns]
                    net += omega.convergencePaPerSecond[cell] * area
                    size += abs(omega.convergencePaPerSecond[cell]) * area
                }
                worstMass = maxOf(worstMass, abs(net) / size)
                val laplacian = operators.laplacian(half.eddyPressureCoarsePa)
                val ekman = correlation(grid, omega.convergencePaPerSecond, DoubleArray(grid.cellCount) { -laplacian[it] })
                val model = half.response.verticalMotion[atmosphere.levels.interiorCount - 1]
                val modelConvergence = DoubleArray(grid.cellCount) { model[it] - half.response.surfaceVerticalMotion[it] }
                val againstModel = correlation(grid, omega.convergencePaPerSecond, modelConvergence)
                val ratio = regression(grid, omega.convergencePaPerSecond, modelConvergence)
                println(("BOUNDARY LAYER seed %d %s, %.0f to %.0f degrees: the layer's convergence against minus the pressure's " +
                    "Laplacian r = %.3f; the dry model's own convergence at the layer's top against it r = %.3f, %.2f of its size")
                    .format(seed, name, MIDDLE_FROM_DEGREES, MIDDLE_TO_DEGREES, ekman, againstModel, ratio))
                correlationSum += ekman
                correlationCount++
                lowestCorrelation = minOf(lowestCorrelation, ekman)
            }
        }
        println("BOUNDARY LAYER mass left over the sphere, worst %.2e of the convergence's size; Ekman's correlation mean %.3f, lowest %.3f (bar %.2f)"
            .format(worstMass, correlationSum / correlationCount, lowestCorrelation, EKMAN_CORRELATION))
        assertTrue(worstMass < MASS_TOLERANCE, "the layer's convergence leaves $worstMass of itself over the sphere")
        assertTrue(lowestCorrelation > EKMAN_CORRELATION, "the layer's vertical motion follows the pressure's Laplacian at r = $lowestCorrelation")
    }

    /** The eddies of [first] (each row's mean removed) against [second], by area, in the middle latitudes. */
    private fun correlation(grid: SphericalGrid, first: DoubleArray, second: DoubleArray): Double {
        val (xy, xx, yy) = moments(grid, first, second)
        return xy / sqrt(xx * yy)
    }

    /** The regression of [second] on [first]'s eddies, in the middle latitudes. */
    private fun regression(grid: SphericalGrid, first: DoubleArray, second: DoubleArray): Double {
        val (xy, xx, _) = moments(grid, first, second)
        return xy / xx
    }

    private fun moments(grid: SphericalGrid, first: DoubleArray, second: DoubleArray): Triple<Double, Double, Double> {
        var xy = 0.0
        var xx = 0.0
        var yy = 0.0
        for (row in 0 until grid.rows) {
            val latitude = abs(grid.latitudeRadians[row] * 180 / PI)
            if (latitude < MIDDLE_FROM_DEGREES || latitude > MIDDLE_TO_DEGREES) continue
            var firstMean = 0.0
            var secondMean = 0.0
            for (column in 0 until grid.columns) {
                firstMean += first[row * grid.columns + column] / grid.columns
                secondMean += second[row * grid.columns + column] / grid.columns
            }
            for (column in 0 until grid.columns) {
                val cell = row * grid.columns + column
                val weight = grid.cosLatitude[row]
                val a = first[cell] - firstMean
                val b = second[cell] - secondMean
                xy += weight * a * b
                xx += weight * a * a
                yy += weight * b * b
            }
        }
        return Triple(xy, xx, yy)
    }

    @Test
    fun `nothing the boundary layer carries up shows the atmosphere's grid`() {
        var failures = 0
        for (seed in seeds) {
            val world = world(seed)
            val atmosphere = ClimateStage.atmosphere(world.config, world.sea)
            val coarse = atmosphere.remap.coarse
            for ((name, half) in listOf("July" to atmosphere.julyHalf, "January" to atmosphere.januaryHalf)) {
                val omegaUp = atmosphere.remap.outputToGround(BoundaryLayer.boundaryLayerOmega(atmosphere, half).totalPaPerSecond)
                for ((field, values) in listOf("sea-level pressure" to half.eddyPressurePa, "vertical motion" to omegaUp)) {
                    val down = CoarsePeriod.reading(values, world.width, world.height, coarse.rows, alongRows = false)
                    val along = CoarsePeriod.reading(values, world.width, world.height, coarse.columns, alongRows = true)
                    println("BOUNDARY LAYER PERIOD seed $seed $name $field: down the map $down, along it $along")
                    if (!down.passes || !along.passes) failures++
                }
            }
        }
        assertTrue(failures == 0, "$failures fields carried up show the atmosphere's grid")
    }

    /**
     * The basic state's jets are Jablonowski and Williamson's, prescribed; the energy balance has a
     * temperature gradient of its own, and the thermal wind it implies from the ground to 250 hPa,
     * `dU = -(R / (f a)) ln(1000 / 250) dT/dphi` with the surface's gradient held through the
     * column, is printed beside the prescribed state's shear at the same latitudes. A report.
     */
    @Test
    fun `the prescribed jets against the energy balance's temperature gradient`() {
        val world = world(seeds.first())
        val config = world.config
        val zonal = ClimateStage.zonalClimate(config, world.sea)
        val marine = ClimateStage.marineAirFraction(config, world.sea)
        val rows = world.height
        val columns = world.width
        val radius = config.scale.radiusMeters
        val grid = SphericalGrid.forAtmosphere(config.scale)
        val levels = com.cartogenesis.worldgen.pipeline.AtmosphereLevels.equalMass(BoundaryLayer.LEVEL_COUNT)
        val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
        val upper = levels.levelPressurePa.indices.minBy { abs(levels.levelPressurePa[it] - JET_PRESSURE_PA) }
        for (season in listOf(Season.JULY_HALF, Season.JANUARY_HALF)) {
            val rowMean = DoubleArray(rows) { row ->
                val latitude = ClimateStage.latitudeOf(row, rows)
                val landC = zonal.landC(latitude, season)
                val seaC = zonal.seaC(latitude, season)
                var sum = 0.0
                for (column in 0 until columns) sum += landC + (seaC - landC) * marine.data[row * columns + column]
                sum / columns
            }
            val line = StringBuilder("BOUNDARY LAYER JETS $season:")
            for (latitudeDegrees in listOf(60.0, 45.0, 30.0, -30.0, -45.0, -60.0)) {
                val row = ((90.0 - latitudeDegrees) / 180.0 * rows - 0.5).toInt()
                val gradientPerRadian = (rowMean[row - 1] - rowMean[row + 1]) / (2 * PI / rows)
                val coriolis = 2 * WorldScale.ROTATION_RATE_PER_S * sin(latitudeDegrees * PI / 180)
                val thermalWind = -DryAir.GAS_CONSTANT_J_PER_KG_K / (coriolis * radius) * ln(100_000.0 / JET_PRESSURE_PA) * gradientPerRadian
                val coarseRow = ((90.0 - latitudeDegrees) / 180.0 * grid.rows - 0.5).toInt()
                val prescribed = state.zonalWindAtLevels[upper][coarseRow] - state.surfaceZonalWind[coarseRow]
                line.append(" %.0f: energy balance %.2f K/deg, thermal wind %.1f m/s, prescribed %.1f;".format(
                    latitudeDegrees, gradientPerRadian * PI / 180, thermalWind, prescribed))
            }
            println(line)
        }
    }

    /** Where the jets are read: 250 hPa, the level of Earth's subtropical and polar-front jets. */
    private val JET_PRESSURE_PA = 25_000.0
}
