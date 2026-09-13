package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * B1: continental shelves.
 *
 * A percentile cut through one height field drops the sea floor straight off the coast unless the
 * ocean floor gets a distinct hypsometric mode -- a shallow band near the coast, then a genuinely
 * deep floor. That shows up here as the share of *ocean* cells the climate stage already calls
 * [com.cartogenesis.worldgen.pipeline.Biome.SHALLOW_OCEAN] (`relativeElevation > -0.12`): high
 * near the coast, low once well clear of it.
 *
 * [com.cartogenesis.worldgen.pipeline.SeaLevelStage] remaps the ocean floor *after* the percentile
 * cut has already fixed the coastline, keyed on distance-to-land, and never touches a land cell.
 * That is the point of doing it here rather than as an extra depression in [com.cartogenesis.
 * worldgen.pipeline.PlateStage] before sea level runs: shaping the shelf earlier moved the
 * threshold itself, so any depth strong enough to be visible also reshuffled which cells were
 * land -- closing straits into land bridges and merging landmasses that should have stayed apart.
 * [`the shelf never touches land`] is the guard for that regression specifically.
 */
class ContinentalShelfTest {

    private val seeds = listOf(7L, 42L, 1234L)

    @Test
    fun `shallow water hugs the coast and the open ocean is deep`() {
        seeds.forEach { seed ->
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val shelfWidthCells = config.cellsFor(config.sea.shelfWidthKm)
            val world = WorldGenerationEngine.generateBlocking(config)
            val (near, far) = shallowShares(world, shelfWidthCells)
            println(
                "SHELF seed $seed: shelfWidthCells=%.0f near-coast shallow=%.1f%% far-from-coast shallow=%.1f%%"
                    .format(shelfWidthCells, near * 100, far * 100)
            )
            // The remap guarantees the whole plateau (-0.02 at the coast to -shelfDepth at the
            // outer edge, both shallower than the -0.12 cut) reads as shallow, so this should sit
            // near 100% -- 90% leaves room for the handful of cells right at 2x shelfWidthCells where
            // the smoothstep has already blended most of the way back to the natural floor.
            assertTrue(
                near > 0.90,
                "seed $seed: only ${(near * 100).toInt()}% of ocean within $shelfWidthCells cells of " +
                    "the coast is shallow"
            )
            assertTrue(
                far < 0.10,
                "seed $seed: ${(far * 100).toInt()}% of ocean beyond ${2 * shelfWidthCells} cells from " +
                    "the coast is still shallow"
            )
        }
    }

    /** Ground rule 2: shown failing without the fix, at exactly the width the guard above uses. */
    @Test
    fun `the near-coast share fails without the shelf`() {
        val defaultWidth = WorldGenConfig().let { it.cellsFor(it.sea.shelfWidthKm) }
        val config = WorldGenConfig(seed = 42L, width = 512, height = 512).let {
            it.copy(sea = it.sea.copy(shelfWidthKm = 0.0))
        }
        val world = WorldGenerationEngine.generateBlocking(config)
        val (near, far) = shallowShares(world, defaultWidth)
        println(
            "SHELF shelfWidthCells=0 control: near-coast shallow=%.1f%% far-from-coast shallow=%.1f%%"
                .format(near * 100, far * 100)
        )
        assertTrue(
            near <= 0.90,
            "expected the shelfWidthCells=0 control to fail the near-coast guard, but got " +
                "${(near * 100).toInt()}% shallow"
        )
    }

    /**
     * The reason this chunk was redesigned: the earlier version shaped the shelf as an extra
     * depression on oceanic crust *before* sea level was chosen, so any depth strong enough to be
     * visible also moved the percentile threshold and reshuffled which cells were land. Remapping
     * the ocean floor after the cut, and skipping every land cell outright, has to leave land
     * completely alone -- not approximately, exactly -- whatever the shelf settings are.
     */
    @Test
    fun `the shelf never touches land`() {
        seeds.forEach { seed ->
            // Glaciation off on both sides, because it is a second stage writing to the same field
            // and it *is* legitimately sensitive to the shelf: a coastal glacier picks its outlet
            // by steepest descent, ocean neighbours are compared at their true depth (see
            // `FlowRouting.flowDirections`), and remapping the sea floor can therefore send a
            // trough down the next valley along. Measured with it on, that reached 130 of seed 7's
            // hundred thousand land cells. Real, and nothing to do with the invariant this case is
            // about, which is that `SeaLevelStage`'s own remap touches only water.
            val base = WorldGenConfig(seed = seed, width = 512, height = 512).let {
                it.copy(glaciation = it.glaciation.copy(enabled = false))
            }
            val withShelf = WorldGenerationEngine.generateBlocking(base)
            val noShelf = WorldGenerationEngine.generateBlocking(
                base.copy(sea = base.sea.copy(shelfWidthKm = 0.0))
            )

            assertTrue(
                withShelf.sea.isLand.contentEquals(noShelf.sea.isLand),
                "seed $seed: the shelf setting changed which cells are land"
            )

            var mismatches = 0
            var worst = 0f
            for (i in withShelf.sea.isLand.indices) {
                if (!withShelf.sea.isLand[i]) continue
                val a = withShelf.sea.relativeElevation.data[i]
                val b = noShelf.sea.relativeElevation.data[i]
                if (a != b) {
                    mismatches++
                    worst = maxOf(worst, abs(a - b))
                }
            }
            println("SHELF seed $seed: land-invariance check, $mismatches / ${withShelf.sea.landCellCount} land cells differ (worst $worst)")
            assertTrue(
                mismatches == 0,
                "seed $seed: the shelf changed $mismatches land cells' relativeElevation (worst $worst)"
            )

            // The fingerprint the report asks for: land count is identical, but the whole-field
            // relativeElevation checksum is not, because the shelf legitimately remaps ocean
            // cells. Recording both makes that distinction explicit rather than implied.
            println(
                "SHELF seed $seed: land=${withShelf.sea.landCellCount} (no-shelf " +
                    "${noShelf.sea.landCellCount}), relativeElevation checksum " +
                    "${checksum(withShelf.sea.relativeElevation.data)} (no-shelf " +
                    "${checksum(noShelf.sea.relativeElevation.data)})"
            )
        }
    }

    private fun checksum(values: FloatArray): Long {
        var sum = 0L
        for (v in values) sum = sum * 31 + v.toRawBits()
        return sum
    }

    /**
     * @return (share of ocean within [shelfWidthCells] cells of the coast that is shallow, share of
     *   ocean beyond `2 * shelfWidthCells` cells that is shallow)
     */
    private fun shallowShares(world: WorldMap, shelfWidthCells: Float): Pair<Double, Double> {
        val w = world.width
        val h = world.height
        val land = world.sea.isLand

        // Distance in cells from every ocean cell to the nearest land — the same measure, by the
        // same transform, SeaLevelStage's shelf remap is keyed on. It has to be the same one: the
        // chamfer metric this used before G4 reads up to 8.2% further than Euclid at the bearings
        // between an axis and a diagonal, so measuring one field against a shelf cut from the
        // other would put a ring of genuinely shallow cells outside the "near" band.
        val dist = FloatArray(w * h) { JumpFloodDistance.INFINITE }
        val label = IntArray(w * h) { -1 }
        for (i in 0 until w * h) {
            if (land[i]) {
                dist[i] = 0f
                label[i] = i
            }
        }
        JumpFloodDistance.run(w, h, dist, label)

        var nearShallow = 0
        var nearTotal = 0
        var farShallow = 0
        var farTotal = 0
        for (i in 0 until w * h) {
            if (land[i]) continue
            val shallow = world.sea.relativeElevation.data[i] > -0.12f
            val d = dist[i]
            if (d <= shelfWidthCells) {
                nearTotal++
                if (shallow) nearShallow++
            } else if (d > 2f * shelfWidthCells && world.plates.continentalShare.data[i] < 0.5f) {
                // Ocean floor, and not the drowned half of a continent. The two are different
                // things and only since S2 does the model know it: the wedge this class guards is
                // sediment laid over whatever the crust puts under it, and what the crust puts
                // under a drowned platform is continental rock standing a hundred metres down for
                // as far inland as the platform runs. Counting that as "open ocean still shallow"
                // read 12.9% on seed 1234 against a bar of 10% and was measuring the continent.
                farTotal++
                if (shallow) farShallow++
            }
        }
        val near = if (nearTotal == 0) 0.0 else nearShallow.toDouble() / nearTotal
        val far = if (farTotal == 0) 0.0 else farShallow.toDouble() / farTotal
        return near to far
    }
}
