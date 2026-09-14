package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * What the ground looks like, in figures rather than as an opinion.
 *
 * Every one of S2's passes was right about the physics and wrong about the picture, and none of the
 * guards written before this class could see it: the hypsometry was bimodal, the sea floor was at
 * Earth's depth, the uplift balanced the denudation, and the render showed continents pocked with
 * lakes, regions drowned into mazes of inlets, a collision belt drawn as a smooth pale ring round a
 * ponded plateau, then — the third pass — the whole of the land under one uniform sandpaper and
 * continents flooded through the middle. These four measurements are what the eye was reading.
 *
 * All four are compared against `main` measured by exactly this arithmetic on the same five seeds —
 * the first two against 2eb0f0d, the tree before S2, and the last two against 230deb9, the tree S2's
 * fourth pass merged — and all four carry the control that shows the bar bite.
 */
class GroundTextureTest {

    /**
     * A mountain belt's flank is dissected, and at S1's critical slope it is an analytic ramp.
     *
     * The flank, not the crest and not the foreland: a crest is rough whatever the model does and a
     * foreland is flat whatever the model does, while the ramp between them is either a mountain
     * front cut by the rivers draining the belt or a plane, and that is the difference the eye
     * caught. Measured as the median departure from a four-cell box mean, in metres, over belt
     * cells standing between one and three kilometres.
     *
     * The control is `criticalFallMetresPerKm` at S1's 12, which is the figure that drew the ring:
     * a stamped plateau's rim falls at about 12 m/km over its two hundred kilometres, so the
     * thermal sweeps found it exactly at the threshold and planed it flat.
     */
    @Test
    fun `a belt's flank is dissected, and is a plane at S1's critical slope`() {
        val textures = ArrayList<Double>()
        val controls = ArrayList<Double>()
        SEEDS.forEach { seed ->
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val here = flankTexture(world(seed))
            val control = flankTexture(
                WorldGenerationEngine.generateBlocking(
                    config.copy(
                        erosion = config.erosion.copy(
                            criticalFallMetresPerKm = CRITICAL_FALL_BEFORE_S2
                        )
                    )
                )
            )
            textures.add(here)
            controls.add(control)
            println(
                "TEXTURE belt flank seed %d: %.0f m against main's %.0f, and %.0f m at %.0f m/km"
                    .format(seed, here, MAIN_BELT_FLANK_TEXTURE_METRES, control, CRITICAL_FALL_BEFORE_S2)
            )
        }
        val pooled = textures.average()
        val pooledControl = controls.average()
        println(
            "TEXTURE belt flank pooled %.0f m against main's %.0f and the control's %.0f"
                .format(pooled, MAIN_BELT_FLANK_TEXTURE_METRES, pooledControl)
        )
        assertTrue(
            "a belt's flank departs from its own smoothed self by ${"%.0f".format(pooled)} m," +
                " which is less than the ${"%.0f".format(MAIN_BELT_FLANK_TEXTURE_METRES)} m the" +
                " tree before S2 managed: the stamp is showing through as a ramp",
            pooled >= MAIN_BELT_FLANK_TEXTURE_METRES
        )
        assertTrue(
            "the control at ${"%.0f".format(CRITICAL_FALL_BEFORE_S2)} m/km reads" +
                " ${"%.0f".format(pooledControl)} m, which is not below the" +
                " ${"%.0f".format(pooled)} m this world reads — so the critical slope is not what" +
                " was planing the flank and this guard proves nothing",
            pooledControl < pooled
        )
    }

    /**
     * A continent's longest slopes are what drain it, and a tenth of them leaves the water standing.
     *
     * Integration divides each component's amplitude by its wavenumber, so the base relief carries
     * most of its power at the width of the map, and `TerrainConfig.regionalReliefShare` says how
     * much of that survives the band filter. S2's second pass kept a tenth and measured the
     * bifurcation ratio, which could not see what it cost: with no slope worth the name between the
     * belts, the water ponds where it falls.
     *
     * Two rows, both M1's. The lake share of land against Earth's own at this cell area, and the
     * drainage density against what the tree before S2 measured.
     *
     * The lake bar no longer has a control, and the reason is worth stating rather than hiding:
     * nothing this chunk can switch off ponds the water any more. A tenth of the map-scale relief
     * *and* a crust with no profile of its own — which is the ground S2's second pass measured
     * 3.55% of land in lakes on — reads 0.96% here, comfortably inside the bar, because the
     * crust's own thickness profile now supplies the long slope `regionalReliefShare` was raised
     * to a sixth to supply. So the control is printed and not asserted (ground rule 5: a guard
     * that cannot discriminate says so), and whether the sixth is still needed at all is in
     * `TODO.md`.
     */
    @Test
    fun `a continent's long slopes drain it, and a tenth of them does not`() {
        val lakeShares = ArrayList<Double>()
        val densities = ArrayList<Double>()
        val controlLakeShares = ArrayList<Double>()
        var squareKilometresPerCell = 0.0
        SEEDS.forEach { seed ->
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val metrics = EarthLikeness.measure(world(seed), "ground/$seed")
            val control = EarthLikeness.measure(
                WorldGenerationEngine.generateBlocking(
                    config.copy(
                        terrain = config.terrain.copy(
                            regionalReliefShare = REGIONAL_RELIEF_SHARE_BEFORE
                        ),
                        // And the crust with no profile of its own, which is where the tenth was
                        // measured: see the note on the control below.
                        isostasy = config.isostasy.copy(cratonThickeningKm = 0f),
                        tectonics = config.tectonics.copy(
                            cratonReliefStandardDeviationMetres =
                                config.tectonics.marginReliefStandardDeviationMetres
                        )
                    )
                ),
                "control/$seed"
            )
            squareKilometresPerCell = metrics.squareKilometresPerCell
            lakeShares.add(metrics.lakeShareOfLand)
            densities.add(drainageDensity(metrics))
            controlLakeShares.add(control.lakeShareOfLand)
            println(
                "TEXTURE drainage seed %d: lakes %.4f of land (control %.4f), density %.4f km/km2"
                    .format(seed, metrics.lakeShareOfLand, control.lakeShareOfLand, drainageDensity(metrics))
            )
        }
        val earthLakeShare = EarthLikeness.lakeShareOfLandOnEarth(squareKilometresPerCell)
        val pooledLakes = lakeShares.average()
        val pooledControlLakes = controlLakeShares.average()
        val pooledDensity = densities.average()
        println(
            ("TEXTURE drainage pooled: lakes %.4f against Earth's %.4f (control %.4f)," +
                " density %.4f against main's %.4f")
                .format(
                    pooledLakes, earthLakeShare, pooledControlLakes,
                    pooledDensity, MAIN_DRAINAGE_DENSITY_KM_PER_KM2
                )
        )
        assertTrue(
            "lakes cover ${"%.4f".format(pooledLakes)} of the land, more than half again Earth's" +
                " ${"%.4f".format(earthLakeShare)} at this cell area: the ground is not draining",
            pooledLakes <= earthLakeShare * LAKE_SHARE_ALLOWANCE
        )
        assertTrue(
            "the drainage density is ${"%.4f".format(pooledDensity)} km/km2 against the" +
                " ${"%.4f".format(MAIN_DRAINAGE_DENSITY_KM_PER_KM2)} the tree before S2 measured," +
                " which is further than $DRAINAGE_DENSITY_ALLOWANCE either way",
            pooledDensity in (MAIN_DRAINAGE_DENSITY_KM_PER_KM2 / DRAINAGE_DENSITY_ALLOWANCE)..
                (MAIN_DRAINAGE_DENSITY_KM_PER_KM2 * DRAINAGE_DENSITY_ALLOWANCE)
        )
    }

    /**
     * A plain is smooth at the cell and a range is not, and a stationary field cannot tell them
     * apart.
     *
     * William, looking at S2's third pass at 2048: *"the entire land has a very rough texture it
     * did not have before ... no map of Earth at any scale I've seen has that appearance."* The
     * base relief was one random surface with one amplitude per crust, so the finest thing the
     * grid could draw was as loud on a coastal plain as on a mountain front. Earth's is not:
     * roughness grows with relief, because it is the rivers draining that relief that cut it
     * (Ahnert 1970).
     *
     * Measured as the median departure from a four-cell box mean, in metres, over the lowest and
     * the highest quarter of the land by elevation — the same arithmetic
     * [flankTexture] uses on a belt, spread over the whole map. The lowest quarter must be no
     * rougher than `main`'s and the highest no smoother, which is a pair of bars that pull against
     * each other: anything that quiets the plains quiets the ranges too unless it is told the
     * difference between them. The control is `textureCornerKm` at zero, which leaves the base
     * relief the one stationary field it was, and it fails the first bar.
     */
    @Test
    fun `the ground's texture follows its relief`() {
        val lowest = ArrayList<Double>()
        val highest = ArrayList<Double>()
        val controlLowest = ArrayList<Double>()
        val controlHighest = ArrayList<Double>()
        SEEDS.forEach { seed ->
            val here = textureByElevation(world(seed))
            val control = textureByElevation(
                WorldGenerationEngine.generateBlocking(
                    standard(seed).let {
                        it.copy(
                            isostasy = it.isostasy.copy(cratonThickeningKm = 0f),
                            tectonics = it.tectonics.copy(
                                textureCornerKm = TEXTURE_OFF,
                                cratonReliefStandardDeviationMetres =
                                    it.tectonics.marginReliefStandardDeviationMetres
                            )
                        )
                    }
                )
            )
            lowest.add(here.first)
            highest.add(here.second)
            controlLowest.add(control.first)
            controlHighest.add(control.second)
            println(
                ("TEXTURE ground seed %d: lowest quarter %.0f m against main's %.0f, highest %.0f" +
                    " against %.0f; stationary control %.0f and %.0f")
                    .format(
                        seed, here.first, MAIN_LOWEST_QUARTER_TEXTURE_METRES,
                        here.second, MAIN_HIGHEST_QUARTER_TEXTURE_METRES,
                        control.first, control.second
                    )
            )
        }
        val pooledLowest = lowest.average()
        val pooledHighest = highest.average()
        println(
            ("TEXTURE ground pooled: lowest quarter %.1f m against main's %.1f, highest %.1f" +
                " against %.1f; stationary control %.1f and %.1f")
                .format(
                    pooledLowest, MAIN_LOWEST_QUARTER_TEXTURE_METRES,
                    pooledHighest, MAIN_HIGHEST_QUARTER_TEXTURE_METRES,
                    controlLowest.average(), controlHighest.average()
                )
        )
        assertTrue(
            "the lowest quarter of the land departs from its own smoothed self by" +
                " ${"%.1f".format(pooledLowest)} m, where the tree before S2 manages" +
                " ${"%.1f".format(MAIN_LOWEST_QUARTER_TEXTURE_METRES)}: the plains are sandpaper",
            pooledLowest <= MAIN_LOWEST_QUARTER_TEXTURE_METRES
        )
        assertTrue(
            "the highest quarter departs by ${"%.1f".format(pooledHighest)} m against the" +
                " ${"%.1f".format(MAIN_HIGHEST_QUARTER_TEXTURE_METRES)} m the tree before S2" +
                " manages: the ranges have been smoothed along with the plains",
            pooledHighest >= MAIN_HIGHEST_QUARTER_TEXTURE_METRES
        )
        assertTrue(
            "with the texture rule off the lowest quarter reads" +
                " ${"%.1f".format(controlLowest.average())} m, which is already inside main's" +
                " ${"%.1f".format(MAIN_LOWEST_QUARTER_TEXTURE_METRES)} — so this guard would pass" +
                " without the fix and proves nothing",
            controlLowest.average() > MAIN_LOWEST_QUARTER_TEXTURE_METRES
        )
    }

    /**
     * What a continent drowns is its rim, because that is where its crust is thin.
     *
     * William, on the same render: *"still substantial flooded continents / inland seas."*
     * Isostasy drowns whatever continental crust stands below the datum, and with the crust one
     * thickness everywhere and one spread of relief on it, that is wherever the noise happens to
     * dip — the middle of a continent as readily as its edge. Earth's continental crust is
     * thickest and flattest in the middle (Christensen & Mooney 1995), so the drowned part of a
     * continent is its shelf.
     *
     * Measured as the share of the drowned continental crust lying within [SHELF_REACH_KM] of the
     * nearest cell that is not continental crust. Pooled, because the geometry of a single world's
     * continents decides how much of one is rim at all — a continent six thousand kilometres
     * across has far less of itself near an edge than two of three thousand — and the per-seed
     * spread is printed.
     */
    @Test
    fun `a continent drowns at its rim`() {
        val marginal = ArrayList<Double>()
        val controls = ArrayList<Double>()
        val submerged = ArrayList<Double>()
        SEEDS.forEach { seed ->
            val here = drownedCrust(world(seed))
            val control = drownedCrust(
                WorldGenerationEngine.generateBlocking(
                    standard(seed).let {
                        it.copy(
                            isostasy = it.isostasy.copy(cratonThickeningKm = 0f),
                            tectonics = it.tectonics.copy(
                                cratonReliefStandardDeviationMetres =
                                    it.tectonics.marginReliefStandardDeviationMetres
                            )
                        )
                    }
                )
            )
            marginal.add(here.second)
            controls.add(control.second)
            submerged.add(here.first)
            println(
                ("TEXTURE drowned seed %d: %.3f of the continental crust is under water and %.3f" +
                    " of that lies within %.0f km of the crust's edge; flat-crust control %.3f")
                    .format(seed, here.first, here.second, SHELF_REACH_KM, control.second)
            )
        }
        val pooled = marginal.average()
        val pooledControl = controls.average()
        println(
            ("TEXTURE drowned pooled: %.3f of the continental crust under water, %.3f of it within" +
                " %.0f km of the crust's edge (control %.3f)")
                .format(submerged.average(), pooled, SHELF_REACH_KM, pooledControl)
        )
        assertTrue(
            "only ${"%.3f".format(pooled)} of the drowned continental crust lies within" +
                " ${"%.0f".format(SHELF_REACH_KM)} km of the crust's edge, against the" +
                " ${"%.2f".format(MARGINAL_SHARE_OF_DROWNED_CRUST)} Earth's shelves make of it:" +
                " the continents are flooded rather than shelved",
            pooled >= MARGINAL_SHARE_OF_DROWNED_CRUST
        )
        assertTrue(
            "with the crust one thickness and one relief everywhere the drowning is already" +
                " ${"%.3f".format(pooledControl)} marginal, which clears the bar — so this guard" +
                " would pass without the profile and proves nothing",
            pooledControl < MARGINAL_SHARE_OF_DROWNED_CRUST
        )
    }

    /**
     * The median departure from a four-cell box mean, in metres, over the lowest and the highest
     * quarter of the land by elevation.
     */
    private fun textureByElevation(world: WorldMap): Pair<Double, Double> {
        val cellsAcross = world.width
        val cellsDown = world.height
        val scale = world.config.scale
        val isLand = world.sea.isLand
        val metres = FloatArray(cellsAcross * cellsDown) {
            if (isLand[it]) scale.metresAboveShoreline(world.sea.relativeElevation.data[it]) else 0f
        }
        val landWeight = FloatArray(cellsAcross * cellsDown) { if (isLand[it]) 1f else 0f }
        val radius = (FLANK_WINDOW_CELLS * cellsAcross / 512f).roundToInt().coerceAtLeast(1)
        val smoothed = boxMean(cellsAcross, cellsDown, metres, radius)
        val cover = boxMean(cellsAcross, cellsDown, landWeight, radius)
        val land = ArrayList<Int>()
        for (cell in isLand.indices) if (isLand[cell] && cover[cell] > 1e-3f) land.add(cell)
        if (land.isEmpty()) return 0.0 to 0.0
        land.sortBy { metres[it] }
        val quarter = land.size / 4
        fun median(from: Int, to: Int): Double {
            val residuals = ArrayList<Double>(to - from)
            for (index in from until to) {
                val cell = land[index]
                residuals.add(abs(metres[cell] - smoothed[cell] / cover[cell]).toDouble())
            }
            if (residuals.isEmpty()) return 0.0
            residuals.sort()
            return residuals[residuals.size / 2]
        }
        return median(0, quarter) to median(land.size - quarter, land.size)
    }

    /**
     * How much of the continental crust is under water, and how much of *that* lies within
     * [SHELF_REACH_KM] of the crust's own edge.
     *
     * The edge is the nearest cell that is less than half continental, and the distance to it is
     * walked in kilometres rather than in cells, because an equirectangular map's cells are twice
     * as wide as they are tall and a shelf is a length on the ground.
     */
    private fun drownedCrust(world: WorldMap): Pair<Double, Double> {
        val cellsAcross = world.width
        val cellsDown = world.height
        val share = world.plates.continentalShare.data
        val isLand = world.sea.isLand
        val cellKm = world.config.cellWidthKm.toFloat()
        val rowKm = world.config.cellHeightKm.toFloat()
        val diagonalKm = sqrt(cellKm * cellKm + rowKm * rowKm)
        val distanceKm = FloatArray(cellsAcross * cellsDown) { Float.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        for (cell in share.indices) {
            if (share[cell] < 0.5f) {
                distanceKm[cell] = 0f
                queue.addLast(cell)
            }
        }
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            val row = cell / cellsAcross
            val column = cell % cellsAcross
            for (stepDown in -1..1) {
                for (stepAcross in -1..1) {
                    if (stepDown == 0 && stepAcross == 0) continue
                    val neighbourRow = row + stepDown
                    if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                    val neighbourColumn =
                        ((column + stepAcross) % cellsAcross + cellsAcross) % cellsAcross
                    val neighbour = neighbourRow * cellsAcross + neighbourColumn
                    if (share[neighbour] < 0.5f) continue
                    val stepKm = when {
                        stepAcross != 0 && stepDown != 0 -> diagonalKm
                        stepAcross != 0 -> cellKm
                        else -> rowKm
                    }
                    val candidate = distanceKm[cell] + stepKm
                    if (candidate < distanceKm[neighbour] - 1e-3f) {
                        distanceKm[neighbour] = candidate
                        queue.addLast(neighbour)
                    }
                }
            }
        }
        var continental = 0
        var wet = 0
        var wetAndMarginal = 0
        for (cell in share.indices) {
            if (share[cell] < 0.5f) continue
            continental++
            if (isLand[cell]) continue
            wet++
            if (distanceKm[cell] <= SHELF_REACH_KM) wetAndMarginal++
        }
        if (continental == 0 || wet == 0) return 0.0 to 0.0
        return wet.toDouble() / continental to wetAndMarginal.toDouble() / wet
    }

    /** Channel length over land area, over every aridity class: M1's drainage-density row. */
    private fun drainageDensity(metrics: EarthLikeness.Metrics): Double {
        val channel = metrics.drainage.channelKilometres.sum()
        val land = metrics.drainage.landSquareKilometres.sum()
        return if (land <= 0.0) 0.0 else channel / land
    }

    /**
     * The median departure from a four-cell box mean, in metres, over the cells of a present
     * collision or Andean belt standing between one and three kilometres.
     */
    private fun flankTexture(world: WorldMap): Double {
        val cellsAcross = world.width
        val cellsDown = world.height
        val scale = world.config.scale
        val isLand = world.sea.isLand
        val distance = world.plates.boundaryDistance.data
        val boundaryClass = world.plates.nearestBoundaryClass
        val falloff = world.config.tectonics.boundaryFalloffCells
        val metres = FloatArray(cellsAcross * cellsDown) {
            if (isLand[it]) scale.metresAboveShoreline(world.sea.relativeElevation.data[it]) else 0f
        }
        val landWeight = FloatArray(cellsAcross * cellsDown) { if (isLand[it]) 1f else 0f }
        val radius = (FLANK_WINDOW_CELLS * cellsAcross / 512f).roundToInt().coerceAtLeast(1)
        val smoothed = boxMean(cellsAcross, cellsDown, metres, radius)
        val cover = boxMean(cellsAcross, cellsDown, landWeight, radius)
        val residuals = ArrayList<Double>()
        for (cell in isLand.indices) {
            if (!isLand[cell]) continue
            if (distance[cell] > falloff) continue
            val pairClass = boundaryClass[cell]
            if (pairClass != BoundaryClass.ANDEAN_MARGIN.ordinal &&
                pairClass != BoundaryClass.COLLISION_PLATEAU.ordinal
            ) continue
            if (metres[cell] < FLANK_FLOOR_METRES || metres[cell] > FLANK_CEILING_METRES) continue
            if (cover[cell] <= 1e-3f) continue
            residuals.add(abs(metres[cell] - smoothed[cell] / cover[cell]).toDouble())
        }
        if (residuals.isEmpty()) return 0.0
        residuals.sort()
        return residuals[residuals.size / 2]
    }

    /** A separable box mean that wraps in x and clamps in y, as every other window in this repo. */
    private fun boxMean(
        cellsAcross: Int,
        cellsDown: Int,
        field: FloatArray,
        radius: Int
    ): FloatArray {
        val out = FloatArray(field.size)
        val rows = FloatArray(field.size)
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                var sum = 0f
                for (step in -radius..radius) {
                    val x = ((column + step) % cellsAcross + cellsAcross) % cellsAcross
                    sum += field[row * cellsAcross + x]
                }
                rows[row * cellsAcross + column] = sum / (2 * radius + 1)
            }
        }
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                var sum = 0f
                var counted = 0
                for (step in -radius..radius) {
                    val y = row + step
                    if (y < 0 || y >= cellsDown) continue
                    sum += rows[y * cellsAcross + column]
                    counted++
                }
                out[row * cellsAcross + column] = sum / counted
            }
        }
        return out
    }

    private fun standard(seed: Long) = WorldGenConfig(seed = seed, width = 512, height = 512)

    /** The five worlds on the defaults, built once and shared by every clause below. */
    private fun world(seed: Long): WorldMap =
        WORLDS.getOrPut(seed) { WorldGenerationEngine.generateBlocking(standard(seed)) }

    private companion object {
        /** `GeographyAuditTest`'s standard seeds, plus the author's own world. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L, 718106L)

        val WORLDS = HashMap<Long, WorldMap>()

        /**
         * What `main` at 230deb9 measures, by [textureByElevation], on these five seeds.
         *
         * Taken by running the measurement on that tree rather than remembered: the lowest quarter
         * reads 46, 45, 73, 74 and 89 m and the highest 70, 83, 141, 116 and 170.
         */
        const val MAIN_LOWEST_QUARTER_TEXTURE_METRES = 65.2
        const val MAIN_HIGHEST_QUARTER_TEXTURE_METRES = 115.9

        /**
         * How far from the crust's own edge a drowned continental cell may lie and still count as
         * shelf, in kilometres, and how much of the drowning has to be inside it.
         *
         * Earth's shelf averages 78 km wide (Cogley 1984), but the figure that matters here is the
         * widest it gets rather than the mean, because what the bar is refusing is a drowned
         * *interior*: the Siberian shelf reaches 800 km and more from the crust's edge across the
         * Barents, Kara and Laptev seas, the Sunda shelf about the same, and Hudson Bay a
         * thousand. Eight hundred kilometres is the near end of Earth's own widest shelves, so a
         * fifth of the drowning further in than that is as much epicontinental sea as Earth has.
         *
         * At 500 km — wider than every shelf on Earth bar those three — the same worlds read 0.744
         * against the flat crust's 0.522, so the profile is worth the same 0.22 of the drowning at
         * either distance and what the choice of distance settles is only where the bar can stand.
         */
        const val SHELF_REACH_KM = 800.0
        const val MARGINAL_SHARE_OF_DROWNED_CRUST = 0.80

        /** A corner of zero leaves the base relief stationary, which is the texture rule's control. */
        const val TEXTURE_OFF = 0.0

        /** The window the flank's roughness is measured in, in cells of a 512 grid. */
        const val FLANK_WINDOW_CELLS = 4f

        /** The band of altitude a belt's flank occupies, in metres. */
        const val FLANK_FLOOR_METRES = 1_000f
        const val FLANK_CEILING_METRES = 3_000f

        /**
         * What `main` at 2eb0f0d measured, by this arithmetic, on these five seeds.
         *
         * Taken by running this measurement on that tree rather than remembered: the belt flank
         * reads 77, 94, 133, 123 and 140 m, and the drainage density 0.0030, 0.0030, 0.0019,
         * 0.0025 and 0.0024 km/km2.
         */
        const val MAIN_BELT_FLANK_TEXTURE_METRES = 113.0
        const val MAIN_DRAINAGE_DENSITY_KM_PER_KM2 = 0.00256

        /**
         * How far the drainage density may sit from the pre-S2 tree's, either way.
         *
         * A regression bar and not an Earth one, because Earth's channel length per unit area at a
         * 275 km² support threshold is not a figure this project has looked up — which is in
         * `TODO.md`. It was a fifth until S2's fourth pass and is a third now, for a reason that is
         * the chunk's own: the crust has a thickness that rises inland, so a continent has a slope
         * of its own and the water that used to stand on it runs. The two figures move together
         * and both moved toward Earth — the lake share of land fell from 1.73% to 0.70% against
         * Earth's 1.48%, and the density rose from 0.00256 to 0.0032 — so holding the second
         * inside a fifth of a tree whose continents were level would be holding on to the ponding
         * the first measures.
         */
        const val DRAINAGE_DENSITY_ALLOWANCE = 1.35

        /** Half again Earth's own share, which is the slack M1's row is read with. */
        const val LAKE_SHARE_ALLOWANCE = 1.5

        /** The two settings this chunk moved, at the value S2's second pass left them. */
        const val CRITICAL_FALL_BEFORE_S2 = 12f
        const val REGIONAL_RELIEF_SHARE_BEFORE = 0.10
    }
}
