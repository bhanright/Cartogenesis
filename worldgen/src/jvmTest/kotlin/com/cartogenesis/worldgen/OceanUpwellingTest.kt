package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.EnergyBalance
import com.cartogenesis.worldgen.pipeline.OceanCirculation
import com.cartogenesis.worldgen.pipeline.OceanHeat
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ekman's transport and the water it draws up, on fixtures whose answers are known: the damped
 * Ekman balance `M = τ (r - i f) / (ρ (r² + f²))`, its divergence counted on the cells' faces with
 * nothing through a coast, and the mixed layer's steady state where the risen water renews it.
 */
class OceanUpwellingTest {

    private val seawaterDensity = 1025.0

    /** The offshore transport under an alongshore stress [stress] at [latitude], square meters a second. */
    private fun offshoreTransport(stress: Double, latitude: Double): Double {
        val f = (2.0 * com.cartogenesis.worldgen.model.WorldScale.ROTATION_RATE_PER_S * sin(latitude * PI / 180.0))
        val r = OceanStage.SURFACE_LAYER_FRICTION_PER_S
        return stress * abs(f) / (seawaterDensity * (r * r + f * f))
    }

    /**
     * A straight coast, meridional and at 30 degrees to the grid, with land to its east and an
     * equatorward stress along it: the water rising beside it, less the open ocean's own at the same
     * row, is the offshore Ekman transport per length of coast, `τ f / (ρ (r² + f²))`, which is
     * Ekman's `τ/(ρf)` to within `(r/f)²`. At 20 to 40 degrees north and south, at aspects 1.0 and
     * 0.5, and on a world twice the radius, where it must not move: nothing in it reads the radius.
     *
     * The bar is a thousandth, for the transport's change with latitude down the band, and for the
     * slanted coast one cell's width over the band's length of coast besides: a uniform transport's
     * flux through a staircase is its flux through the straight line between the staircase's ends,
     * whatever the steps, and those ends can each sit up to half a cell from where the straight
     * coast crosses the band's edges, so the alongshore component's flux through them is uncounted
     * or counted twice by at most a cell's width of coast. Without the upwelling term there is no
     * rising water at all, and a poleward stress sinks it instead (the next case).
     */
    @Test
    fun `an equatorward wind along a coast raises Ekman's offshore transport beside it`() {
        val failures = ArrayList<String>()
        for (widthKm in listOf(12_000.0, 24_000.0)) {
            for (aspect in listOf(1.0, 0.5)) {
                for (bearingDegrees in listOf(0.0, 30.0)) {
                    for (hemisphere in listOf(1.0, -1.0)) {
                        val (measured, expected, endStepShare) = coastalRise(widthKm, aspect, bearingDegrees, hemisphere, equatorward = true)
                        val bar = COAST_TOLERANCE + if (bearingDegrees == 0.0) 0.0 else endStepShare
                        println("UPWELLING coast at %2.0f degrees to the grid, %s, world %.0f km, aspect %.1f: %.4f m2/s per meter of coast against %.4f, off by %.2f%% against a bar of %.2f%%"
                            .format(bearingDegrees, if (hemisphere > 0) "north" else "south", widthKm, aspect, measured, expected, abs(measured / expected - 1) * 100, bar * 100))
                        if (abs(measured / expected - 1) > bar) {
                            failures += "bearing $bearingDegrees, hemisphere $hemisphere, world $widthKm, aspect $aspect: $measured against $expected"
                        }
                    }
                }
            }
        }
        // The control: a coast that passes the transport through it, as an ocean with no faces
        // shut at the land would, raises nothing beside it.
        val (throughCoast, expected, _) = coastalRise(12_000.0, 1.0, 0.0, 1.0, equatorward = true, coastShut = false)
        println("UPWELLING control, the transport let through the coast: %.4f m2/s per meter of coast against %.4f".format(throughCoast, expected))
        assertTrue(abs(throughCoast / expected - 1) > COAST_TOLERANCE, "a coast that passes the transport passed the bar")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * The risen water outcropped where Luyten, Pedlosky and Stommel's eastern-boundary geometry
     * puts its isopycnal: `sin φ_o = sin φ / (1 - D/H)`, 38.7 degrees beneath 30 for 100 m under a
     * 500 m thermocline, no further poleward than the subtropical gyre's edge at 45, the latitude's
     * own poleward of that edge, continuous across it, and the mirror image in the south. The
     * mixed layer's own latitude, the proxy the closure replaced, fails the first clause.
     */
    @Test
    fun `the risen water outcropped where the ventilated thermocline puts it`() {
        val expected = asin(sin(30.0 * PI / 180) / (1 - OceanStage.UPWELLING_SOURCE_DEPTH_M / 500.0)) * 180 / PI
        val measured = OceanStage.outcropLatitude(30f).toDouble()
        println("UPWELLING outcrop beneath 30 degrees: %.3f against %.3f; beneath -30 %.3f; beneath 44.9 %.3f, 45 %.3f, 60 %.3f, 0 %.3f"
            .format(measured, expected, OceanStage.outcropLatitude(-30f), OceanStage.outcropLatitude(44.9f), OceanStage.outcropLatitude(45f),
                OceanStage.outcropLatitude(60f), OceanStage.outcropLatitude(0f)))
        assertTrue(abs(30.0 - expected) > OUTCROP_TOLERANCE_DEGREES, "the mixed layer's own latitude passed the first clause")
        assertTrue(abs(measured - expected) < OUTCROP_TOLERANCE_DEGREES, "beneath 30 degrees the water outcropped at $measured, not $expected")
        assertTrue(abs(OceanStage.outcropLatitude(-30f) + measured) < OUTCROP_TOLERANCE_DEGREES, "the south is not the north's mirror image")
        assertTrue(abs(OceanStage.outcropLatitude(44.9f) - 45f) < OUTCROP_TOLERANCE_DEGREES, "the water beneath 44.9 degrees outcropped beyond the gyre")
        assertTrue(OceanStage.outcropLatitude(60f) == 60f, "the subpolar gyre's water is not its own latitude's")
        assertTrue(OceanStage.outcropLatitude(0f) == 0f, "the equator's water outcropped off the equator")
    }

    /**
     * The trades tilt the equatorial thermocline, and the east's rising water comes from beneath it:
     * along an equatorial basin under a uniform easterly stress `τ`, the thermocline's depth is the
     * analytic `h² = H² + (2τ / ρg') (x - x_w - L/2)`, shoaling eastward, and the share of rising
     * water drawn from beneath it, `(1 + tanh((D - h)/δ))/2`, follows it to 1e-4. With no stress the
     * thermocline is flat and the east draws no more than the west, which is the control.
     */
    @Test
    fun `the trades tilt the equatorial thermocline and the east draws from beneath it`() {
        val across = 960
        val down = 480
        val widthMeters = 12_000_000.0 / across
        val westShore = 200
        val eastShore = 700
        val isWater = BooleanArray(across * down) { cell -> (cell % across) in westShore until eastShore }
        fun stressOf(stressEast: Double) = OceanStage.Stress(DoubleArray(across * down) { stressEast }, DoubleArray(across * down))
        fun sharesUnder(stressEast: Double): FloatArray = OceanStage.equatorialDeepShare(stressOf(stressEast), isWater, across, down, widthMeters)
        val flat = sharesUnder(0.0)
        val lengthMeters = (eastShore - westShore) * widthMeters
        val meanDepth = OceanStage.EQUATORIAL_THERMOCLINE_DEPTH_M
        // A stress whose tilt stays inside the layer, and one three times as strong, whose eastern
        // end surfaces and is clipped.
        for (stress in listOf(STRESS_N_PER_M2, 3 * STRESS_N_PER_M2)) {
            val tilted = sharesUnder(-stress)
            val depths = OceanStage.equatorialThermoclineDepths(stressOf(-stress), isWater, across, down, widthMeters)
            val slope = 2 * -stress / (seawaterDensity * OceanStage.EQUATORIAL_REDUCED_GRAVITY_M_PER_S2)
            fun analyticDepth(column: Int, shoalsAtMeters: Double): Double {
                val x = (column - westShore + 0.5) * widthMeters
                return kotlin.math.sqrt(maxOf(meanDepth * meanDepth + slope * (x - shoalsAtMeters), 0.0))
            }
            // Where along the basin the analytic `h` is `H`, found so the basin's mean `h` is `H`.
            var low = -lengthMeters
            var high = 2 * lengthMeters
            repeat(200) {
                val middle = (low + high) / 2
                val mean = (westShore until eastShore).sumOf { analyticDepth(it, middle) } / (eastShore - westShore)
                if (mean < meanDepth) low = middle else high = middle
            }
            var worst = 0.0
            var clipped = 0
            for (column in westShore until eastShore) {
                val depth = analyticDepth(column, (low + high) / 2)
                if (depth == 0.0) clipped++
                val expected = (1 + kotlin.math.tanh((OceanStage.UPWELLING_SOURCE_DEPTH_M - depth) / OceanStage.THERMOCLINE_HALF_THICKNESS_M)) / 2
                worst = maxOf(worst, abs(tilted[column] - expected))
            }
            val meanMeters = (westShore until eastShore).sumOf { depths[it].toDouble() } / (eastShore - westShore)
            println("UPWELLING equatorial thermocline under %.3f N/m2 over a %.0f km basin: %d columns surfaced; mean depth %.4f m against %.1f; the western end draws %.3f from beneath, the eastern %.3f; worst against the analytic %.1e; with no stress %.3f and %.3f"
                .format(stress, lengthMeters / 1000, clipped, meanMeters, meanDepth, tilted[westShore], tilted[eastShore - 1], worst, flat[westShore], flat[eastShore - 1]))
            assertTrue(abs(meanMeters - meanDepth) < MEAN_DEPTH_TOLERANCE_M, "the basin's mean thermocline depth is $meanMeters m, not $meanDepth")
            assertTrue(tilted[eastShore - 1] > tilted[westShore], "the east did not draw more from beneath than the west")
            assertTrue(worst < 1e-4, "the share departs from the analytic tilt by $worst")
            assertTrue(tilted[0].isNaN(), "land on the equator was given a thermocline")
        }
        assertTrue(!(flat[eastShore - 1] > flat[westShore]), "a flat thermocline drew more from beneath in the east")
    }

    /**
     * The belts' own annual stress, with no regional wind, raises water in the two rows beside the
     * equator at the damped Ekman divergence of its easterly `β τ_x / (ρ r²)`: its meridional leg
     * must pass through zero at the equator rather than reverse between the two rows, or the leg's
     * down-wind transport `τ_y / (ρ r)`, several times the easterly's there, converges on the
     * equator and sinks the water instead.
     *
     * The bar is the two departures the analytic figure leaves out, each computed from the
     * constants, and half a percent for the rest (the easterly's own curvature over two rows, and
     * the leg's share of the wind's speed in the drag): the leg's own convergence where it turns,
     * `(2/π) s r / (β T)` of the easterly's term with `s` the leg's share and `T` the belts'
     * migration in meters, and the damping's `f²` across the first cell, `1.75 (β Δ / r)²`.
     */
    @Test
    fun `the trades raise water in the rows beside the equator`() {
        val base = WorldGenConfig(seed = 42L, width = 256, height = 128)
        val config = base.copy(climate = base.climate.copy(pressureWinds = false))
        val (across, down) = OceanStage.solveGrid(config.scale)
        val sea = SeaLevelResult(0.5f, BooleanArray(256 * 128), FloatField(256, 128), 0)
        val stress = OceanStage.annualStress(config, sea, across, down)
        val widthMeters = config.scale.worldWidthKm * 1000 / across
        val heightMeters = config.scale.worldWidthKm * WorldScale.WORLD_HEIGHT_AS_SHARE_OF_WIDTH * 1000 / down
        val upward = OceanStage.upwellingMps(stress, BooleanArray(across * down) { true }, across, down, widthMeters, heightMeters)
        val beta = config.scale.planetaryVorticityGradientPerMeterSecond(0.0)
        val r = OceanStage.SURFACE_LAYER_FRICTION_PER_S
        val migrationMeters = config.climate.seasonalTiltDegrees * config.scale.metersPerDegreeLatitude
        val legShare = 2 / PI * config.climate.meridionalWindShare * r / (beta * migrationMeters)
        val dampingShare = 1.75 * (beta * heightMeters / r) * (beta * heightMeters / r)
        val bar = legShare + dampingShare + EQUATOR_REST_SHARE
        for (row in listOf(down / 2 - 1, down / 2)) {
            val expected = -beta * stress.eastward[row * across] / (seawaterDensity * r * r)
            val measured = upward[row * across].toDouble()
            println("UPWELLING equator under the belts, row at %.3f degrees: %.3e m/s (%.2f m/day) against %.3e (%.2f m/day), off by %.2f%% against a bar of %.2f%% (the leg %.2f%%, the damping %.2f%%)"
                .format(ClimateStage.latitudeOf(row, down), measured, measured * 86_400, expected, expected * 86_400,
                    abs(measured / expected - 1) * 100, bar * 100, legShare * 100, dampingShare * 100))
            assertTrue(measured > 0.0, "the row at ${ClimateStage.latitudeOf(row, down)} degrees sinks at $measured m/s")
            assertTrue(abs(measured / expected - 1) < bar, "the row at ${ClimateStage.latitudeOf(row, down)} degrees rises at $measured m/s against $expected")
        }
    }

    /**
     * The shelf's reach runs through water. A sea 60 m deep, closed but for a strip of land two
     * cells wide (23 km) between it and an ocean 1,000 m deep, lies within a shelf's width of the
     * deep water as the crow flies and not at all by water, so nothing reaches it from the deep
     * side and every cell of it draws on no deeper than its own floor. The control opens the strip
     * to the same shallow water: the deep water's reach then crosses into the sea as a shelf's does.
     */
    @Test
    fun `a shallow sea behind an isthmus draws only its own floor`() {
        val config = WorldGenConfig(seed = 42L, width = 1024, height = 1024)
        val (gridAcross, gridDown) = OceanStage.solveGrid(config.scale)
        val closed = shelteredSeaFloor(config, gridAcross, gridDown, isthmus = true)
        val open = shelteredSeaFloor(config, gridAcross, gridDown, isthmus = false)
        println("UPWELLING sheltered sea %.0f m deep: the deepest source any cell of it draws on is %.2f m behind the isthmus, %.2f m with the strip opened"
            .format(SHELTERED_SEA_M, closed, open))
        assertTrue(open > SHELTERED_SEA_M + 1.0, "the opened sea drew on nothing deeper than its own floor")
        assertTrue(closed <= SHELTERED_SEA_M + 0.01, "the sea behind the isthmus drew on $closed m through the land")
    }

    /**
     * The deepest source [OceanStage.floorDepthOn] gives any solve cell lying wholly over the
     * sheltered sea: deep ocean over map columns 100 to 399, the strip at 400 and 401 (land, or with
     * [isthmus] off the sea's own depth), the sea from 402 to 419, land beyond and outside rows 20
     * to 40 degrees north.
     */
    private fun shelteredSeaFloor(config: WorldGenConfig, gridAcross: Int, gridDown: Int, isthmus: Boolean): Double {
        val across = config.width
        val down = config.height
        val isLand = BooleanArray(across * down)
        val relative = FloatField(across, down)
        for (cell in isLand.indices) {
            val latitude = ClimateStage.latitudeOf(cell / across, down)
            val column = cell % across
            val depthMeters = when {
                latitude < 20f || latitude > 40f -> null
                column in 100 until 400 -> 1_000.0
                column in 400 until 402 -> if (isthmus) null else SHELTERED_SEA_M
                column in 402 until 420 -> SHELTERED_SEA_M
                else -> null
            }
            isLand[cell] = depthMeters == null
            relative.data[cell] = if (depthMeters == null) 0.1f else config.scale.depthShareOfMetres((-depthMeters).toFloat())
        }
        val floor = OceanStage.floorDepthOn(config, SeaLevelResult(0.5f, isLand, relative, isLand.count { it }), gridAcross, gridDown)
        var deepest = 0.0
        for (row in 0 until gridDown) {
            val mapRow = (row + 0.5) * down / gridDown - 0.5
            val latitude = ClimateStage.latitudeOf(row, gridDown)
            if (latitude < 21f || latitude > 39f) continue
            for (column in 0 until gridAcross) {
                val mapColumn = (column + 0.5) * across / gridAcross - 0.5
                if (mapColumn < 402.0 || mapColumn > 418.0 || mapRow < 0) continue
                deepest = maxOf(deepest, floor[row * gridAcross + column].toDouble())
            }
        }
        return deepest
    }

    /**
     * Renewal with the column's own water changes nothing. A closed basin 40 m deep, shallower
     * than the mixed layer and with no deeper water in reach, under a wind whose curl turns a gyre
     * in it and whose Ekman transport raises water along its shores: the gyre carries a warm
     * anomaly, and with the upwelling on every cell settles where it settles with it off, to the
     * heat solve's own reach. On the closure that renewed the layer with the latitude's annual
     * water, the rise relaxed the anomaly away beside the shores.
     */
    @Test
    fun `a shallow sea's own water leaves a current's anomaly as it found it`() {
        val (config, sea) = basin(latitude = 35f, halfSpanDegrees = 15f, floorMeters = 40.0)
        val zonal = ClimateStage.zonalClimate(config, sea)
        val (gridAcross, gridDown) = OceanStage.solveGrid(config.scale)
        val east = DoubleArray(gridAcross * gridDown)
        val north = DoubleArray(gridAcross * gridDown)
        for (cell in east.indices) {
            val latitude = ClimateStage.latitudeOf(cell / gridAcross, gridDown).toDouble()
            // Easterlies in the south and westerlies in the north: a subtropical gyre, with a warm
            // current up its western side; and a wind toward the equator besides.
            east[cell] = -GYRE_STRESS_N_PER_M2 * kotlin.math.cos(PI * (latitude - 20.0) / 30.0)
            north[cell] = -STRESS_N_PER_M2
        }
        val stress = OceanStage.Stress(east, north)
        val on = OceanStage.circulateUnder(config, sea, zonal, stress, relax)
        val off = OceanStage.circulateUnder(config.copy(ocean = config.ocean.copy(upwelling = false)), sea, zonal, stress, relax)
        var warmest = 0.0
        var worst = 0.0
        var rising = 0
        for (cell in on.isWater.indices) {
            if (!on.isWater[cell]) continue
            val latitudeC = zonal.waterC(ClimateStage.latitudeOf(cell / gridAcross, gridDown), Season.ANNUAL)
            warmest = maxOf(warmest, (off.temperatureC[cell] - latitudeC).toDouble())
            worst = maxOf(worst, abs(on.temperatureC[cell] - off.temperatureC[cell]).toDouble())
            if (on.upwellingMps[cell] > 0f) rising++
        }
        println("UPWELLING shallow gyre: warmest anomaly %.3f C, %d cells rising; the upwelling moves the water by at most %.4f C".format(warmest, rising, worst))
        assertTrue(warmest > WARM_ANOMALY_C, "the gyre carried no warm anomaly: $warmest C")
        assertTrue(rising > 0, "nothing rose in the basin")
        assertTrue(worst <= FLOOR_TOLERANCE_C, "the basin's own water moved its temperature by $worst C")
    }

    /**
     * Upwelled water cannot be colder than sea water can be: over a world with water at every
     * latitude, deep enough for every source, no cell's risen water is below the freezing point of
     * sea water. The energy balance's own coldest month, which is the ice's surface where the sea
     * freezes, goes far below it, and is what the risen water must not be taken for.
     */
    @Test
    fun `no risen water is colder than sea water can be`() {
        val config = WorldGenConfig(seed = 42L, width = 128, height = 128)
        val isLand = BooleanArray(128 * 128) { (it % 128) in 30 until 70 && (it / 128) in 20 until 100 }
        val relative = FloatField(128, 128)
        for (cell in isLand.indices) relative.data[cell] = if (isLand[cell]) 0.1f else config.scale.depthShareOfMetres(-1_000f)
        val sea = SeaLevelResult(0.5f, isLand, relative, isLand.count { it })
        val zonal = ClimateStage.zonalClimate(config, sea)
        val (across, down) = OceanStage.solveGrid(config.scale)
        val isWater = OceanStage.waterOn(config, sea, across, down)
        val floor = OceanStage.floorDepthOn(config, sea, across, down)
        val still = OceanStage.Stress(DoubleArray(across * down), DoubleArray(across * down))
        val risen = OceanStage.subsurfaceTemperatures(config, zonal, still, isWater, floor, across, down, config.scale.worldWidthKm * 1000 / across)
        var coldest = Double.POSITIVE_INFINITY
        var coldestAt = 0f
        for (cell in risen.indices) {
            if (!isWater[cell] || risen[cell] >= coldest) continue
            coldest = risen[cell].toDouble()
            coldestAt = ClimateStage.latitudeOf(cell / across, down)
        }
        val coldestWinter = (0 until down).minOf { zonal.waterC(ClimateStage.latitudeOf(it, down), Season.WINTER) }
        println("UPWELLING the coldest risen water: %.3f C at %.1f degrees, against sea water's freezing point %.1f C; the balance's coldest month %.2f C"
            .format(coldest, coldestAt, EnergyBalance.SEA_FREEZING_C, coldestWinter))
        assertTrue(coldestWinter < EnergyBalance.SEA_FREEZING_C, "no month of this world is below freezing, so the guard tests nothing")
        assertTrue(coldest >= EnergyBalance.SEA_FREEZING_C, "risen water at $coldest C, below sea water's freezing point, at $coldestAt degrees")
    }

    /**
     * Entrained water acts on the liquid mixed layer, open to the air only in the months the sea is
     * not frozen. A closed basin 1,000 m deep from 55 to 85 degrees north, where the balance's sea
     * freezes for part of the year, under a wind raising water along its eastern shore: the most
     * the rise can do is bring the open months' water to the risen water's temperature and leave the
     * ice months as they were, so no cell may settle colder than the coldest of the basin's rows'
     * annual water moved by its open share times the risen water less the open months' water, nor
     * warmer than the warmest row's annual water moved the same way. Renewing the annual mean with
     * the coldest month's water, which under ice is the ice's surface, drives the basin below that
     * range, and renewing it with water at the freezing point at the year's full rate warms the
     * rows whose ice months sit below freezing above it.
     */
    @Test
    fun `a seasonally frozen sea is driven no further than its open months allow`() {
        val (config, sea) = basin(latitude = 70f, halfSpanDegrees = 15f, floorMeters = 1_000.0)
        val zonal = ClimateStage.zonalClimate(config, sea)
        val (gridAcross, gridDown) = OceanStage.solveGrid(config.scale)
        val stress = OceanStage.Stress(DoubleArray(gridAcross * gridDown), DoubleArray(gridAcross * gridDown) { -STRESS_N_PER_M2 })
        val solved = OceanStage.circulateUnder(config, sea, zonal, stress, relax)
        var lowest = Double.POSITIVE_INFINITY
        var highest = Double.NEGATIVE_INFINITY
        var coldest = Double.POSITIVE_INFINITY
        var warmest = Double.NEGATIVE_INFINITY
        var seasonalRows = 0
        for (row in 0 until gridDown) {
            val cells = (row * gridAcross until (row + 1) * gridAcross).filter { solved.isWater[it] }
            if (cells.isEmpty()) continue
            val latitude = ClimateStage.latitudeOf(row, gridDown)
            val annualC = zonal.waterC(latitude, Season.ANNUAL).toDouble()
            val openShare = zonal.openWaterShare(latitude).toDouble()
            if (openShare > 0.05 && openShare < 0.95) seasonalRows++
            val risenC = maxOf(OceanStage.subsurfaceTemperatureC(zonal, latitude), EnergyBalance.SEA_FREEZING_C).toDouble()
            val shiftC = openShare * (risenC - zonal.openWaterC(latitude))
            lowest = minOf(lowest, annualC + minOf(shiftC, 0.0))
            highest = maxOf(highest, annualC + maxOf(shiftC, 0.0))
            for (cell in cells) {
                coldest = minOf(coldest, solved.temperatureC[cell].toDouble())
                warmest = maxOf(warmest, solved.temperatureC[cell].toDouble())
            }
        }
        println("UPWELLING seasonally frozen basin: %d rows freeze for part of the year; the water spans %.3f to %.3f C, the open months allow %.3f to %.3f C"
            .format(seasonalRows, coldest, warmest, lowest, highest))
        assertTrue(seasonalRows > 0, "no row of the basin freezes for part of the year, so the guard tests nothing")
        assertTrue(coldest >= lowest - FLOOR_TOLERANCE_C, "the basin settled at $coldest C, below the $lowest its open months allow")
        assertTrue(warmest <= highest + FLOOR_TOLERANCE_C, "the basin settled at $warmest C, above the $highest its open months allow")
    }

    /**
     * A world of land with one closed basin [floorMeters] deep between [halfSpanDegrees] either side
     * of [latitude] north, over a quarter of the columns.
     */
    private fun basin(latitude: Float, halfSpanDegrees: Float, floorMeters: Double): Pair<WorldGenConfig, SeaLevelResult> {
        val config = WorldGenConfig(seed = 42L, width = 256, height = 128)
        val across = config.width
        val down = config.height
        val isLand = BooleanArray(across * down) { cell ->
            val rowLatitude = ClimateStage.latitudeOf(cell / across, down)
            val column = cell % across
            abs(rowLatitude - latitude) > halfSpanDegrees || column !in across / 4 until across / 2
        }
        val relative = FloatField(across, down)
        for (cell in isLand.indices) relative.data[cell] = if (isLand[cell]) 0.1f else config.scale.depthShareOfMetres((-floorMeters).toFloat())
        return config to SeaLevelResult(0.5f, isLand, relative, isLand.count { it })
    }

    private val relax: (com.cartogenesis.worldgen.pipeline.OceanStencil, FloatArray, Int) -> FloatArray = { s, v, p -> OceanCirculation.relax(s, v, p); v }

    /**
     * Upwelled water cannot come from below the sea floor. A closed basin 40 m deep, shallower than
     * the mixed layer and with no deeper water anywhere in it, under a wind that raises water along
     * its eastern shore, at 30 and at 70 degrees: no cell of it settles colder than the coldest own
     * water of the basin's rows, the latitude's annual water, since the column's own water is all
     * there is to rise. Per cell a shallow basin can still sit a few hundredths below its own row's
     * water, because the stronger renewal beside the shore pins the colder rows harder while the
     * eddies mix them into the warmer; that is the basin's own water redistributed, which this bar
     * allows, and not cold from elsewhere, which it does not. The same basin 1,000 m deep at 30 N,
     * the control, cools below it beside its shore, as a deep sea's upwelling should (at 70 N the
     * deep basin's risen water is at the freezing point and acts only in its open months, which
     * `a seasonally frozen sea is driven no further than its open months allow` holds). Before the
     * floor was read the shallow basin cooled too, its coldest cell 3.02 C under the basin's
     * coldest own water at 30 N; now, with no term at all where the floor is within the mixed
     * layer, it stays 2.25 C above it at 30 N and 2.46 at 70.
     */
    @Test
    fun `a sea shallower than the mixed layer raises no cold from below its floor`() {
        val deep = basinBelowOwnWater(30f, floorMeters = 1_000.0)
        println("UPWELLING basin at 30 N, 1,000 m deep: its coldest cell under the basin's coldest own water by %.4f C".format(deep))
        assertTrue(deep > FLOOR_TOLERANCE_C, "the deep basin's upwelling did not cool it: $deep")
        for (latitude in listOf(30f, 70f)) {
            val shallow = basinBelowOwnWater(latitude, floorMeters = 40.0)
            println("UPWELLING basin at %.0f N, 40 m deep: its coldest cell under the basin's coldest own water by %.4f C".format(latitude, shallow))
            assertTrue(shallow <= FLOOR_TOLERANCE_C, "a basin 40 m deep cooled $shallow C below its own water")
        }
    }

    /**
     * How far, in degrees, the coldest cell of a closed basin at [latitude], [floorMeters] deep and
     * under an equatorward wind along its eastern shore, settles below the coldest of its rows'
     * annual water; zero or less where none does.
     */
    private fun basinBelowOwnWater(latitude: Float, floorMeters: Double): Double {
        val config = WorldGenConfig(seed = 42L, width = 256, height = 128)
        val across = config.width
        val down = config.height
        val isLand = BooleanArray(across * down) { cell ->
            val rowLatitude = ClimateStage.latitudeOf(cell / across, down)
            val column = cell % across
            abs(rowLatitude - latitude) > 8f || column !in across / 4 until across / 2
        }
        val relative = FloatField(across, down)
        for (cell in isLand.indices) relative.data[cell] = if (isLand[cell]) 0.1f else config.scale.depthShareOfMetres((-floorMeters).toFloat())
        val sea = SeaLevelResult(0.5f, isLand, relative, isLand.count { it })
        val zonal = ClimateStage.zonalClimate(config, sea)
        val (gridAcross, gridDown) = OceanStage.solveGrid(config.scale)
        val stress = OceanStage.Stress(DoubleArray(gridAcross * gridDown), DoubleArray(gridAcross * gridDown) { -STRESS_N_PER_M2 })
        val relax: (com.cartogenesis.worldgen.pipeline.OceanStencil, FloatArray, Int) -> FloatArray = { s, v, p -> OceanCirculation.relax(s, v, p); v }
        val with = OceanStage.circulateUnder(config, sea, zonal, stress, relax)
        var coldestOwnC = Double.POSITIVE_INFINITY
        var coldestC = Double.POSITIVE_INFINITY
        for (cell in with.isWater.indices) {
            if (!with.isWater[cell]) continue
            coldestOwnC = minOf(coldestOwnC, zonal.waterC(ClimateStage.latitudeOf(cell / gridAcross, gridDown), Season.ANNUAL).toDouble())
            coldestC = minOf(coldestC, with.temperatureC[cell].toDouble())
        }
        return coldestOwnC - coldestC
    }

    /** A poleward stress along the same coasts pushes the surface water onshore, and nothing rises. */
    @Test
    fun `a poleward wind along a coast raises nothing`() {
        for (bearingDegrees in listOf(0.0, 30.0)) {
            for (hemisphere in listOf(1.0, -1.0)) {
                val (measured, _) = coastalRise(12_000.0, 1.0, bearingDegrees, hemisphere, equatorward = false)
                println("UPWELLING poleward wind, coast at %2.0f degrees, %s: %.4f m2/s per meter of coast".format(bearingDegrees, if (hemisphere > 0) "north" else "south", measured))
                assertTrue(measured < 0.0, "a poleward wind raised $measured m2/s per meter of coast")
            }
        }
    }

    /**
     * The rise beside the coast over the band from 20 to 40 degrees in one hemisphere, net of the
     * open ocean's in each row, per meter of coast, and the analytic transport averaged the same way.
     */
    private fun coastalRise(widthKm: Double, aspect: Double, bearingDegrees: Double, hemisphere: Double, equatorward: Boolean, coastShut: Boolean = true): Triple<Double, Double, Double> {
        val scale = WorldScale(worldWidthKm = widthKm)
        val cellKm = 20.0 * widthKm / 12_000.0
        val across = (widthKm / cellKm).toInt()
        val down = (widthKm / 2 / (cellKm * aspect)).toInt()
        val widthMeters = widthKm * 1000 / across
        val heightMeters = widthKm * 500 / down
        val bearing = bearingDegrees * PI / 180
        // The coast runs through the middle column at 30 degrees of latitude, its bearing measured
        // from north toward east; land lies east of it.
        val middleRow = (0 until down).minByOrNull { abs(ClimateStage.latitudeOf(it, down) - 30.0 * hemisphere) }!!
        val isWater = BooleanArray(across * down) { cell ->
            val row = cell / across
            val column = cell % across
            val northMeters = (middleRow - row) * heightMeters
            val coastColumn = across / 2 + northMeters * tan(bearing) / widthMeters
            column + 0.5 < coastColumn
        }
        // Along the coast, pointing toward the equator (south in the north, north in the south).
        val towardEquator = -hemisphere
        val alongEast = sin(bearing) * towardEquator * (if (equatorward) 1.0 else -1.0)
        val alongNorth = cos(bearing) * towardEquator * (if (equatorward) 1.0 else -1.0)
        val stressEast = DoubleArray(across * down) { STRESS_N_PER_M2 * alongEast }
        val stressNorth = DoubleArray(across * down) { STRESS_N_PER_M2 * alongNorth }
        val faces = if (coastShut) isWater else BooleanArray(across * down) { true }
        val upward = OceanStage.upwellingMps(OceanStage.Stress(stressEast, stressNorth), faces, across, down, widthMeters, heightMeters)
        var risenPerSecond = 0.0
        var coastMeters = 0.0
        var expectedPerSecond = 0.0
        for (row in 0 until down) {
            val latitude = ClimateStage.latitudeOf(row, down).toDouble() * hemisphere
            if (latitude < 20.0 || latitude >= 40.0) continue
            val openOcean = upward[row * across + across / 8]
            var excess = 0.0
            for (column in 0 until across) {
                val cell = row * across + column
                if (!isWater[cell] || column < across / 4) continue
                excess += (upward[cell] - openOcean) * widthMeters * heightMeters
            }
            val rowCoastMeters = heightMeters / cos(bearing)
            risenPerSecond += excess
            coastMeters += rowCoastMeters
            expectedPerSecond += offshoreTransport(STRESS_N_PER_M2, latitude) * rowCoastMeters
        }
        return Triple(risenPerSecond / coastMeters, expectedPerSecond / coastMeters, widthMeters / coastMeters)
    }

    /**
     * On the equator an easterly stress drives the surface water poleward on both sides, and the
     * water rises between at `β τ (r² - f²) / (ρ (r² + f²)²)`: finite, `β τ / (ρ r²)` on the line
     * itself, where Ekman's undamped `τ/(ρf)` has no value. The grid is 4 km, a twentieth of the
     * equatorial layer `r/β`, where the face-centered difference is good to `(Δ/L)²/6`, under a
     * thousandth; the bar is 1%.
     */
    @Test
    fun `an easterly wind on the equator raises the damped Ekman divergence`() {
        val scale = WorldScale()
        val across = 3000
        val down = 1500
        val widthMeters = scale.worldWidthKm * 1000 / across
        val heightMeters = scale.worldWidthKm * 500 / down
        val stress = OceanStage.Stress(DoubleArray(across * down) { -STRESS_N_PER_M2 }, DoubleArray(across * down))
        val upward = OceanStage.upwellingMps(stress, BooleanArray(across * down) { true }, across, down, widthMeters, heightMeters)
        val beta = scale.planetaryVorticityGradientPerMeterSecond(0.0)
        val r = OceanStage.SURFACE_LAYER_FRICTION_PER_S
        for (row in listOf(down / 2 - 1, down / 2)) {
            val latitude = ClimateStage.latitudeOf(row, down).toDouble()
            val f = (2.0 * com.cartogenesis.worldgen.model.WorldScale.ROTATION_RATE_PER_S * sin(latitude * PI / 180.0))
            val expected = beta * STRESS_N_PER_M2 * (r * r - f * f) / (seawaterDensity * (r * r + f * f) * (r * r + f * f))
            val measured = upward[row * across].toDouble()
            println("UPWELLING equator, row at %.3f degrees: %.3e m/s (%.2f m/day) against %.3e".format(latitude, measured, measured * 86_400, expected))
            assertTrue(abs(measured / expected - 1) < EQUATOR_TOLERANCE, "at $latitude degrees the rise is $measured m/s against $expected")
        }
    }

    /**
     * A uniform patch of rising water settles at `(T_lat/τ + (w/h) T_sub) / (1/τ + w/h)`: the
     * relaxation to the latitude and the renewal by the risen water in proportion to their rates.
     * Without the entrainment it settles at the latitude's own temperature, and fails.
     */
    @Test
    fun `a patch of rising water settles between the latitude's temperature and the risen water's`() {
        val across = 64
        val down = 32
        val latitudeC = 20.0
        val risenC = 12.0
        val upwardMps = 2.0e-5
        val entrainment = FloatArray(across * down) { (upwardMps / OceanStage.MIXED_LAYER_DEPTH_M).toFloat() }
        fun settled(withEntrainment: Boolean): Double {
            val stencil = OceanHeat.stencil(
                across, down, 50_000.0, 50_000.0, BooleanArray(across * down) { true }, FloatArray(across * down),
                FloatArray(across * down) { latitudeC.toFloat() }, OceanStage.RELAXATION_SECONDS, withTarget = true,
                diffusivityAt = { QUIET_EDDIES_M2_PER_S },
                entrainmentPerS = if (withEntrainment) entrainment else null,
                subsurfaceC = FloatArray(across * down) { risenC.toFloat() }
            )
            val values = FloatArray(across * down) { latitudeC.toFloat() }
            OceanCirculation.relax(stencil, values, SETTLING_PASSES)
            return values[down / 2 * across + across / 2].toDouble()
        }
        val rate = 1.0 / OceanStage.RELAXATION_SECONDS
        val renewal = upwardMps / OceanStage.MIXED_LAYER_DEPTH_M
        val expected = (latitudeC * rate + renewal * risenC) / (rate + renewal)
        val measured = settled(withEntrainment = true)
        val control = settled(withEntrainment = false)
        println("UPWELLING patch: settles at %.4f C against %.4f; without the entrainment %.4f".format(measured, expected, control))
        assertTrue(abs(control - expected) > PATCH_TOLERANCE_C, "the control settled where the upwelling puts it")
        assertTrue(abs(measured - expected) < PATCH_TOLERANCE_C, "the patch settled at $measured C, not $expected")
    }

    /**
     * The risen water's temperature, the winter mixed layer where its isopycnal outcrops and never
     * below the freezing point of sea water, is never warmer than the open months' water it
     * replaces, at any latitude of a real world where the sea is open at all, so the term can only
     * cool. The warmest month read in its place fails.
     */
    @Test
    fun `the risen water is never warmer than the water it replaces`() {
        val config = WorldGenConfig(seed = 42L, width = 128, height = 128)
        val isLand = BooleanArray(128 * 128) { (it % 128) in 30 until 70 && (it / 128) in 20 until 100 }
        val sea = SeaLevelResult(0.5f, isLand, FloatField(128, 128), isLand.count { it })
        val zonal = ClimateStage.zonalClimate(config, sea)
        var warmest = Double.NEGATIVE_INFINITY
        var controlWarmest = Double.NEGATIVE_INFINITY
        var latitude = -89.5f
        while (latitude <= 89.5f) {
            if (zonal.openWaterShare(latitude) > 0f) {
                val open = zonal.openWaterC(latitude)
                val risen = maxOf(OceanStage.subsurfaceTemperatureC(zonal, latitude), EnergyBalance.SEA_FREEZING_C)
                warmest = maxOf(warmest, (risen - open).toDouble())
                controlWarmest = maxOf(controlWarmest, (zonal.waterC(latitude, Season.SUMMER) - open).toDouble())
            }
            latitude += 0.5f
        }
        println("UPWELLING risen water less the open months' water: at most %+.3f C; the warmest month read instead, at most %+.3f C".format(warmest, controlWarmest))
        assertTrue(controlWarmest > 0.0, "the warmest month was no warmer than the year")
        assertTrue(warmest <= 0.0, "the risen water is ${warmest} C warmer than the water it replaces somewhere")
    }

    private companion object {
        /** A trade wind's stress, newtons a square meter: 7.5 m/s at the drag the ocean uses. */
        const val STRESS_N_PER_M2 = 0.083
        const val COAST_TOLERANCE = 1e-3
        const val EQUATOR_TOLERANCE = 0.01
        const val PATCH_TOLERANCE_C = 1e-3
        const val QUIET_EDDIES_M2_PER_S = 1.0
        const val SETTLING_PASSES = 50
        const val OUTCROP_TOLERANCE_DEGREES = 1e-3

        /** The heat solve's own reach: its tolerance, a thousandth of the largest balance, is a few thousandths of a degree. */
        const val FLOOR_TOLERANCE_C = 0.01

        /** A millimeter: the thermocline's depths are held in single precision, good to a few hundredths of one at 150 m. */
        const val MEAN_DEPTH_TOLERANCE_M = 1e-3

        /** The share of the equatorial rise the easterly's curvature and the leg's share of the drag may move, besides the two stated departures. */
        const val EQUATOR_REST_SHARE = 0.005

        /** The sheltered sea's depth, meters: between the mixed layer and the source depth, so its own floor is a source of its own. */
        const val SHELTERED_SEA_M = 60.0

        /** The shallow gyre's westerly and easterly stress, newtons a square meter. */
        const val GYRE_STRESS_N_PER_M2 = 0.1

        /** The least warm anomaly the shallow gyre must carry for its guard to test anything, degrees. */
        const val WARM_ANOMALY_C = 0.5
    }
}
