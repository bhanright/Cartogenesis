package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the ground looks like, as two numbers rather than as an opinion.
 *
 * S2's first two passes were right about the physics and wrong about the picture, and neither of
 * the guards they wrote could see it: the hypsometry was bimodal, the sea floor was at Earth's
 * depth, the uplift balanced the denudation, and the render showed continents pocked with lakes,
 * regions drowned into mazes of inlets and a collision belt drawn as a smooth pale ring round a
 * ponded plateau. These two measurements are what the eye was reading, in figures a test can hold.
 *
 * Both are compared against `main` at 2eb0f0d — the tree before S2 — measured by exactly this
 * arithmetic on the same five seeds, and both carry the control that shows the bar bite.
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
            val here = flankTexture(WorldGenerationEngine.generateBlocking(config))
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
     */
    @Test
    fun `a continent's long slopes drain it, and a tenth of them does not`() {
        val lakeShares = ArrayList<Double>()
        val densities = ArrayList<Double>()
        val controlLakeShares = ArrayList<Double>()
        var squareKilometresPerCell = 0.0
        SEEDS.forEach { seed ->
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val world = WorldGenerationEngine.generateBlocking(config)
            val metrics = EarthLikeness.measure(world, "ground/$seed")
            val control = EarthLikeness.measure(
                WorldGenerationEngine.generateBlocking(
                    config.copy(
                        terrain = config.terrain.copy(
                            regionalReliefShare = REGIONAL_RELIEF_SHARE_BEFORE
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
            "the control at a tenth of the map-scale relief reads" +
                " ${"%.4f".format(pooledControlLakes)} of land in lakes, which is already inside" +
                " the bar — so this guard would pass without the fix and proves nothing",
            pooledControlLakes > earthLakeShare * LAKE_SHARE_ALLOWANCE
        )
        assertTrue(
            "the drainage density is ${"%.4f".format(pooledDensity)} km/km2 against the" +
                " ${"%.4f".format(MAIN_DRAINAGE_DENSITY_KM_PER_KM2)} the tree before S2 measured," +
                " which is further than a fifth either way",
            pooledDensity in (MAIN_DRAINAGE_DENSITY_KM_PER_KM2 * 0.8)..
                (MAIN_DRAINAGE_DENSITY_KM_PER_KM2 * 1.2)
        )
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

    private companion object {
        /** `GeographyAuditTest`'s standard seeds, plus the author's own world. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L, 718106L)

        /** The window the flank's roughness is measured in, in cells of a 512 grid. */
        const val FLANK_WINDOW_CELLS = 4f

        /** The band of altitude a belt's flank occupies, in metres. */
        const val FLANK_FLOOR_METRES = 1_000f
        const val FLANK_CEILING_METRES = 3_000f

        /**
         * What `main` at 2eb0f0d measured, by this arithmetic, on these five seeds.
         *
         * Taken by running this measurement on that tree rather than remembered: the belt flank
         * reads 138, 176, 201, 173 and 195 m, and the drainage density 0.0030, 0.0030, 0.0020,
         * 0.0025 and 0.0023 km/km2.
         */
        const val MAIN_BELT_FLANK_TEXTURE_METRES = 177.0
        const val MAIN_DRAINAGE_DENSITY_KM_PER_KM2 = 0.00256

        /** Half again Earth's own share, which is the slack M1's row is read with. */
        const val LAKE_SHARE_ALLOWANCE = 1.5

        /** The two settings this chunk moved, at the value S2's second pass left them. */
        const val CRITICAL_FALL_BEFORE_S2 = 12f
        const val REGIONAL_RELIEF_SHARE_BEFORE = 0.10
    }
}
