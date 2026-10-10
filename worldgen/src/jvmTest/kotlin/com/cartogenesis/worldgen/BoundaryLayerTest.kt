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
import kotlin.math.tan
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
 *   pressure as the drag balance has it, Ekman's ascent in the lows and descent in the highs with
 *   the beta term beside it.
 * - **One layer, one drag.** The dry model's own vertical motion at the same interface agrees with
 *   it in size, and in pattern in the subtropics: the model's lowest layer is the same boundary
 *   layer under the same drag ([BoundaryLayer.surfaceDragPerSecond]).
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
         * The least correlation between the layer's eddy vertical motion, 35 to 65 degrees, and the
         * drag balance's own of the eddy pressure solved on the atmosphere's grid with each row's
         * mean drag: what is left between them is the coast's drag (land's is 3.3 times the sea's,
         * where the row's mean stands between), the stress's growth with a fast wind, and the
         * spread of the ground's convergence carried down. Over a half says the balance leads.
         */
        const val BALANCE_CORRELATION = 0.5

        /**
         * How far the model's convergence at the layer's top may stand from the diagnosed layer's
         * in size (root mean square), as a factor either way: the model's lowest level takes its
         * row's mean drag where the diagnosed layer takes each cell's, and Ekman's pumping goes as
         * the drag, `k / f^2`, so with land's drag 3.3 times the sea's a cell pumps from 0.57 to 1.9
         * times what its row's mean drag would under a row a third land: a factor of two.
         */
        const val DRAG_AGREEMENT_FACTOR = 2.0

        /**
         * The least correlation of the two in the subtropics, 5 to 35 degrees, where the basic
         * state's surface wind is weak and the drag balance is most of the lowest level's balance;
         * in the middle latitudes the model's lowest level carries the basic state's westerlies'
         * advection, `m U / (a cos)`, as large as the drag for the waves the terrain makes, which
         * the diagnosed layer does not, and the correlation there is printed. A half, as the
         * drag-balance clause's: the shared balance leads.
         */
        const val DRAG_AGREEMENT_CORRELATION = 0.5

        /** The subtropics the drag clause reads, degrees from the equator, to the middle latitudes' start. */
        const val SUBTROPICS_FROM_DEGREES = 5.0

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
        val density = PressureWind.AIR_DENSITY_KG_PER_M3.toDouble()
        var worst = 0.0
        // The zonal mean's northward gradient is the trapezoid rule's between rows, so it is read at
        // rows away from where it is integrated from; the interior rows are the clause.
        for (row in 2 until rows - 2) {
            val coriolis = PressureWind.coriolisParameter(ClimateStage.latitudeOf(row, rows)).toDouble()
            val beltEast = half.beltEastwardMps[row].toDouble()
            val beltNorth = -half.beltSouthwardMps[row].toDouble()
            val beltDrag = PressureWind.beltDrag(half.beltEastwardMps[row], half.beltSouthwardMps[row])
            val push = beltDrag * beltEast - coriolis * beltNorth
            // The trapezoid's own zonal-mean gradient at this row: the mean of the faces either side.
            val zonalNorth = (half.zonalPressurePa[row - 1] - half.zonalPressurePa[row + 1]) / (2 * grid.rowSpacingMeters)
            val exactZonalNorth = -density * (beltDrag * beltNorth + coriolis * beltEast)
            for (column in 0 until columns) {
                val cell = row * columns + column
                val east = half.eastwardMps[cell].toDouble()
                val north = -half.southwardMps[cell].toDouble()
                // The bulk stress's drag at the cell's own speed, which the balance was solved for.
                val drag = BoundaryLayer.surfaceDragPerSecond(world.sea.isLand[cell], sqrt(east * east + north * north))
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
    fun `the layer's vertical motion is the wind's convergence, and follows the pressure as the drag balance does`() {
        var lowestCorrelation = 1.0
        var worstMass = 0.0
        for (seed in seeds) {
            val world = world(seed)
            val atmosphere = ClimateStage.atmosphere(world.config, world.sea)
            val grid = atmosphere.remap.coarse
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
                val balance = balanceConvergence(atmosphere, half)
                val (xy, xx, yy) = moments(grid, omega.convergencePaPerSecond, balance, MIDDLE_FROM_DEGREES, MIDDLE_TO_DEGREES)
                val correlation = xy / sqrt(xx * yy)
                println("BOUNDARY LAYER seed %d %s, %.0f to %.0f degrees: the layer's convergence against the drag balance of the eddy pressure on the atmosphere's grid r = %.3f"
                    .format(seed, name, MIDDLE_FROM_DEGREES, MIDDLE_TO_DEGREES, correlation))
                lowestCorrelation = minOf(lowestCorrelation, correlation)
            }
        }
        println("BOUNDARY LAYER mass left over the sphere, worst %.2e of the convergence's size; the balance's lowest correlation %.3f (bar %.2f)"
            .format(worstMass, lowestCorrelation, BALANCE_CORRELATION))
        assertTrue(worstMass < MASS_TOLERANCE, "the layer's convergence leaves $worstMass of itself over the sphere")
        assertTrue(lowestCorrelation > BALANCE_CORRELATION, "the layer's vertical motion follows the pressure's drag balance at r = $lowestCorrelation")
    }

    /**
     * The drag balance's vertical motion at the layer's top from the eddy pressure on the
     * atmosphere's grid, pascals a second, positive down: the balance of [PressureWind.surfaceWind]
     * with each row's own drag ([BoundaryLayer.Atmosphere.dragOfRow]) solved at the coarse centers,
     * its divergence by the sphere's operators, times the layer's thickness.
     */
    private fun balanceConvergence(atmosphere: BoundaryLayer.Atmosphere, half: BoundaryLayer.Half): DoubleArray {
        val grid = atmosphere.remap.coarse
        val operators = SphericalOperators(grid)
        val gradient = operators.gradient(half.eddyPressureCoarsePa)
        val north = operators.northAtCenters(gradient)
        val density = PressureWind.AIR_DENSITY_KG_PER_M3.toDouble()
        val east = DoubleArray(grid.cellCount)
        val northward = DoubleArray(grid.cellCount)
        for (cell in 0 until grid.cellCount) {
            val row = cell / grid.columns
            val drag = atmosphere.dragOfRow[row]
            val coriolis = 2 * WorldScale.ROTATION_RATE_PER_S * sin(grid.latitudeRadians[row])
            val accelerationEast = -gradient.eastAtCenters[cell] / density
            val accelerationNorth = -north[cell] / density
            val inverse = 1.0 / (drag * drag + coriolis * coriolis)
            east[cell] = (drag * accelerationEast + coriolis * accelerationNorth) * inverse
            northward[cell] = (drag * accelerationNorth - coriolis * accelerationEast) * inverse
        }
        val faces = DoubleArray((grid.rows + 1) * grid.columns)
        for (face in 1 until grid.rows) {
            for (column in 0 until grid.columns) {
                faces[face * grid.columns + column] = 0.5 * (northward[(face - 1) * grid.columns + column] + northward[face * grid.columns + column])
            }
        }
        val divergence = operators.divergence(SphericalOperators.Vector(east, faces))
        val layerPa = atmosphere.levels.thicknessPa[atmosphere.levels.levelCount - 1]
        return DoubleArray(grid.cellCount) { layerPa * divergence[it] }
    }

    /**
     * The model's lowest layer and the diagnosed one are one layer under one drag: the dry model's
     * own convergence at the layer's top, its lowest interior `omega` less the ground's, against the
     * diagnosed layer's ([BoundaryLayer.boundaryLayerOmega]). Held in size, the model's root mean
     * square over the diagnosed one's within [DRAG_AGREEMENT_FACTOR] either way in the subtropics
     * and the middle latitudes (not the regression, which a correlation under one shrinks), and in
     * pattern where the drag balance is most of the lowest level's balance, the subtropics. Shown
     * failing on a diagnosed layer twice the model's lowest layer, its pumping twice the model's for
     * the same drag. A1-4's two drags, the stress's on the model and the surface's turn on the
     * diagnosed layer, are printed beside it: the convergence's size moves less than their ratio,
     * since the beta term `-beta v / f`, which no drag sets, is as large as Ekman's at the
     * deformation radius under the stress's drag.
     */
    @Test
    fun `the dry model's lowest layer and the diagnosed boundary layer are one layer under one drag`() {
        var failures = 0
        var controlFailures = 0
        for (seed in seeds) {
            val world = world(seed)
            val atmosphere = ClimateStage.atmosphere(world.config, world.sea)
            val grid = atmosphere.remap.coarse
            for ((name, half) in listOf("July" to atmosphere.julyHalf, "January" to atmosphere.januaryHalf)) {
                val model = half.response.verticalMotion[atmosphere.levels.interiorCount - 1]
                val modelConvergence = DoubleArray(grid.cellCount) { model[it] - half.response.surfaceVerticalMotion[it] }
                val diagnosed = BoundaryLayer.convergenceOmegaPaPerSecond(atmosphere, half)
                val turned = BoundaryLayer.convergenceOmegaPaPerSecond(atmosphere, turnedHalf(world, half))
                val control = DoubleArray(diagnosed.size) { 2 * diagnosed[it] }
                for ((from, to) in listOf(SUBTROPICS_FROM_DEGREES to MIDDLE_FROM_DEGREES, MIDDLE_FROM_DEGREES to MIDDLE_TO_DEGREES)) {
                    val (xy, xx, yy) = moments(grid, diagnosed, modelConvergence, from, to)
                    val size = sqrt(yy / xx)
                    val correlation = xy / sqrt(xx * yy)
                    val regression = xy / xx
                    val (_, controlXx, controlYy) = moments(grid, control, modelConvergence, from, to)
                    val controlSize = sqrt(controlYy / controlXx)
                    val (turnedXy, turnedXx, turnedYy) = moments(grid, turned, modelConvergence, from, to)
                    val turnedSize = sqrt(turnedYy / turnedXx)
                    val turnedRegression = turnedXy / turnedXx
                    val subtropics = from == SUBTROPICS_FROM_DEGREES
                    val holds = abs(ln(size)) < ln(DRAG_AGREEMENT_FACTOR) && (!subtropics || correlation > DRAG_AGREEMENT_CORRELATION)
                    if (!holds) failures++
                    if (abs(ln(controlSize)) >= ln(DRAG_AGREEMENT_FACTOR)) controlFailures++
                    println(("BOUNDARY LAYER DRAG seed %d %s, %.0f to %.0f degrees: the model's convergence against the diagnosed layer's " +
                        "%.2f of its root mean square at r = %.3f, regression %.2f; under A1-4's two drags %.2f, regression %.2f; " +
                        "against a layer twice as thick %.2f")
                        .format(seed, name, from, to, size, correlation, regression, turnedSize, turnedRegression, controlSize))
                }
            }
        }
        println("BOUNDARY LAYER DRAG $failures of ${seeds.size * 4} readings outside x%.1f in size or under r = %.2f in the subtropics; the control fails $controlFailures"
            .format(DRAG_AGREEMENT_FACTOR, DRAG_AGREEMENT_CORRELATION))
        assertTrue(controlFailures > 0, "a diagnosed layer twice the model's passes the clause, so it cannot tell the layers apart")
        assertTrue(failures == 0, "$failures readings of the model's and the diagnosed layer's convergence disagree")
    }

    /**
     * [half] with its surface wind made again as A1-4 made it: the same pressure's balance with a
     * drag that turns the wind 25 degrees over the sea and 40 over land at 45 degrees, `f(45) tan`,
     * 5.8 and 3.2 hours, where the dry model's lowest level was held by the stress's 1.4 days.
     */
    private fun turnedHalf(world: WorldMap, half: BoundaryLayer.Half): BoundaryLayer.Half {
        val columns = world.width
        val rows = world.height
        val grid = SphericalGrid.forGround(columns, rows, world.config.scale)
        val operators = SphericalOperators(grid)
        val gradient = operators.gradient(DoubleArray(columns * rows) { half.eddyPressurePa[it].toDouble() })
        val north = operators.northAtCenters(gradient)
        val coriolisAt45 = PressureWind.coriolisParameter(45f).toDouble()
        val seaDrag = coriolisAt45 * tan(25 * PI / 180)
        val landDrag = coriolisAt45 * tan(40 * PI / 180)
        val density = PressureWind.AIR_DENSITY_KG_PER_M3.toDouble()
        val east = FloatArray(columns * rows)
        val south = FloatArray(columns * rows)
        for (row in 0 until rows) {
            val coriolis = PressureWind.coriolisParameter(ClimateStage.latitudeOf(row, rows)).toDouble()
            val beltEast = half.beltEastwardMps[row].toDouble()
            val beltNorth = -half.beltSouthwardMps[row].toDouble()
            for (column in 0 until columns) {
                val cell = row * columns + column
                val accelerationEast = -gradient.eastAtCenters[cell] / density + seaDrag * beltEast - coriolis * beltNorth
                val accelerationNorth = -north[cell] / density + seaDrag * beltNorth + coriolis * beltEast
                val drag = if (world.sea.isLand[cell]) landDrag else seaDrag
                val inverse = 1.0 / (drag * drag + coriolis * coriolis)
                east[cell] = ((drag * accelerationEast + coriolis * accelerationNorth) * inverse).toFloat()
                south[cell] = (-(drag * accelerationNorth - coriolis * accelerationEast) * inverse).toFloat()
            }
        }
        return BoundaryLayer.Half(
            half.beltEastwardMps, half.beltSouthwardMps, half.zonalPressurePa, half.eddyPressureCoarsePa,
            half.eddyPressurePa, east, south, half.response
        )
    }

    private fun moments(grid: SphericalGrid, first: DoubleArray, second: DoubleArray, fromDegrees: Double, toDegrees: Double): Triple<Double, Double, Double> {
        var xy = 0.0
        var xx = 0.0
        var yy = 0.0
        for (row in 0 until grid.rows) {
            val latitude = abs(grid.latitudeRadians[row] * 180 / PI)
            if (latitude < fromDegrees || latitude > toDegrees) continue
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
