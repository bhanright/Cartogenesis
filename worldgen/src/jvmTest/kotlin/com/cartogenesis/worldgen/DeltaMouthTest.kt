package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.DepositionLog
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlockingLoggingDeposition
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A river crosses its own delta and reaches the open sea.
 *
 * The author found rivers on seed 59758 at 2048 that stopped at the inner edge of a pale flat slab
 * of new land jutting into a bay. Measured on that world before this chunk: the trunk's drawn chain
 * ended at (640,267), which is a cell of sea — but sea in a body of its own, the 105th of 475 on
 * that map, with the delta's own new land all round it and no way out to the ocean. Within twelve
 * cells of it, 336 of 614 land cells had no lower neighbour at all.
 *
 * Three faults, all in how a lobe was built, and all measured here against `deltaLobe = false`,
 * which is the old behaviour exactly:
 *
 *  - it was laid flat at one level, so a river arriving at its own delta had no downhill step to
 *    follow and the fill spread its water across the slab in threads too thin to draw;
 *  - the growth stepped over cells it could not afford and later rounds built past them, closing
 *    pockets of sea inside the lobe that the sea cannot reach;
 *  - its outline was the square the growth ran out at.
 *
 * The pockets that are *not* a delta's doing were a separate matter and were never claimed here:
 * with deposition switched off entirely a third of every seed's mouths still ended in one, because
 * the shoreline is a percentile and any hollow below it was drawn as sea whether the sea could reach
 * it or not. H5 closed that where this test's own note said it belonged, in the cut itself, and the
 * figure is nought on every seed now — still printed, because a zero that used to be forty is worth
 * seeing.
 */
class DeltaMouthTest {

    private val seeds = listOf(59758L, 42L, 7L, 1234L)

    /**
     * Which cells the sea-mouth lobe built, from the same erosion the world was cut from.
     *
     * The measurement below used to be taken over every cell that became land, and E5 showed that
     * conflates two mechanisms with nothing to do with each other. Measured on the four seeds at
     * 512 with each cell's mechanism recorded: of the ground a *delta* laid, 0.4-1.2% has nowhere
     * downhill; of the ground the *floodplain* laid along the coast, 8-23% has, and that figure is
     * the same before and after E5 because E5 does not touch the floodplain. So a chunk that makes
     * deltas smaller — which E5 does, by half, because a lobe no longer reaches into deep water —
     * moves a measurement of the two pooled together without making a single delta flatter.
     *
     * The floodplain's own flats are real and are recorded in `TODO.md`; the cure is the receiver
     * clamp H5b is fitting to the incision loop, not a change to the fan.
     */
    private fun lobeOf(config: WorldGenConfig, deltaLobe: Boolean): BooleanArray {
        val cfg = config.copy(erosion = config.erosion.copy(deltaLobe = deltaLobe))
        val log = DepositionLog(cfg.width * cfg.height)
        erodeBlockingLoggingDeposition(
            cfg,
            PlateStage.generate(cfg, TerrainStage.generate(cfg)).height,
            log
        )
        return BooleanArray(log.mechanism.size) { log.mechanism[it] == DepositionLog.SEA_LOBE }
    }

    @Test
    fun `a delta slopes to the sea, where it used to be a slab`() {
        var controlStranded = 0
        var controlPockets = 0
        var controlFlat = 0
        seeds.forEach { seed ->
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val before = WorldGenerationEngine.generateBlocking(
                config.copy(erosion = config.erosion.copy(deltaLobe = false))
            )
            val after = WorldGenerationEngine.generateBlocking(config)
            val bare = WorldGenerationEngine.generateBlocking(
                config.copy(erosion = config.erosion.copy(deposition = false))
            )

            val cap = (2 * config.erosion.deltaReachCells + 1) * (2 * config.erosion.deltaReachCells + 1)
            val was = Delta(before, bare, config.seaLevel, cap, lobeOf(config, deltaLobe = false))
            val now = Delta(after, bare, config.seaLevel, cap, lobeOf(config, deltaLobe = true))
            val floor = Delta(bare, bare, config.seaLevel, cap, BooleanArray(0)).inPocket
            controlStranded += was.stranded
            controlPockets += if (was.inPocket > floor) 1 else 0
            // 0.05 until H5, which moved every coastline on the map: the lowstand cuts the lower
            // valleys deeper and the sea then floods them, and water the ocean cannot reach below
            // the size of the largest lake Earth has is no longer sea at all. The old lobe's flat
            // share measured 5.2/6.6/4.0/4.4% over all new land where it was above 5% on all of
            // them, and the sloping lobe's 0.8/0.8/1.2/1.1%. E5 narrowed the measure to the ground
            // the *delta* laid — see `lobeOf` — and the slab still fails it on every seed by a
            // wider margin than before: 5.5/5.6/5.1/4.2%, against the sloping lobe's
            // 1.1/0.6/0.8/0.6%. The bar stays where H5 left it. What is asserted is still the
            // halving, and it is a fivefold to eightfold fall.
            if (was.flat > 0.03) controlFlat++

            println(
                ("DELTA seed %d: rivers ending on a delta but not on open water %d -> %d; delta " +
                    "ground with nowhere downhill %.1f%% -> %.1f%% (over all new land of any " +
                    "origin, %.1f%% -> %.1f%%); mouths in a pocket of any kind %d -> %d, against " +
                    "%d with no deposition at all; pockets with delta land on their shore " +
                    "%d -> %d").format(
                    seed, was.stranded, now.stranded, was.flat * 100, now.flat * 100,
                    was.flatAnywhere * 100, now.flatAnywhere * 100,
                    was.inPocket, now.inPocket, floor, was.pockets, now.pockets
                )
            )

            // The claim, and it is the one a reader can see at the end of a river: the ground a
            // delta lays down slopes, so the trunk has a downhill step to follow across it and
            // stays one drawn channel instead of breaking into a fan of threads.
            //
            // The two figures beside it — rivers ending on a delta without reaching open water, and
            // mouths ending in a pocket of sea — are reported and not asserted, because they are
            // not the delta's to fix. A world with deposition switched off entirely, with no lobe
            // anywhere on it, ends the same third of its rivers in a pocket: the shoreline is a
            // percentile and any hollow under it is drawn as sea whether the sea can reach it or
            // not. Cutting an inlet from each such pocket to the ocean was written, measured
            // (59758: 40 stranded mouths to 15, and the pocket count below the no-deposition
            // floor) and reverted, because a small body of water the ocean cannot reach is
            // sometimes a landform: `RiftSegmentationTest` asks a flooded rift to be a chain of
            // gulfs with land bridges between them, and joining those gulfs to the ocean turned
            // the chain back into a channel. H5 put the fix where GEOGRAPHY.md said it belonged, in
            // the cut: water the ocean cannot reach is land, up to the size of the largest lake
            // Earth has, and a rift gulf is far larger than that and stays a gulf.
            assertTrue(
                now.flat <= was.flat / 2,
                "seed $seed: the delta ground at the mouths went from ${was.flat * 100}% with nowhere " +
                    "downhill to ${now.flat * 100}%, which is not the halving a sloping lobe owes"
            )
        }
        // The pocket clause that stood here — that the old lobe stranded more mouths in a pocket of
        // sea than a world with no deposition at all — is gone, because H5 closed the hole it was
        // measuring. Water the ocean cannot reach, up to the size of the largest lake Earth has, is
        // land now, and nothing on these four seeds ends a river in what is left: the figures
        // printed above read 0 -> 0 against a floor of 0 on every one of them, where before H5 they
        // ran into the dozens. A clause that can only report zero is not a control.
        assertTrue(
            controlStranded > 0 && controlPockets == 0 && controlFlat == seeds.size,
            "the old lobe was expected to strand rivers ($controlStranded) and to lie flat on " +
                "every seed ($controlFlat of ${seeds.size}), with no pocket left for either world " +
                "to strand a mouth in ($controlPockets seeds had one), and did not, so this guard " +
                "proves nothing"
        )
    }

    /**
     * The three measurements, taken together because they share a flood fill and a land mask.
     *
     * New land is found the way `DepositionTest` finds it, by cutting both worlds at the same exact
     * elevation rank rather than at their own histogram thresholds, so what is measured is where
     * the land is rather than how much of it there is.
     */
    private class Delta(
        world: WorldMap,
        bare: WorldMap,
        seaLevel: Float,
        cap: Int,
        lobe: BooleanArray
    ) {
        val stranded: Int
        val pockets: Int
        val inPocket: Int
        val flat: Double
        val flatAnywhere: Double

        init {
            val w = world.width
            val h = world.height
            val land = world.sea.isLand
            val size = w * h

            val now = exactLandMask(world.erosion.height.data, seaLevel)
            val then = exactLandMask(bare.erosion.height.data, seaLevel)
            val gained = BooleanArray(size) { now[it] && !then[it] }

            // Every body of water; the ocean is the largest. A body with delta land on its shore is
            // the delta's doing.
            val body = IntArray(size) { -1 }
            val stack = IntArray(size)
            var bodies = 0
            val sizes = ArrayList<Int>()
            val deltaic = ArrayList<Boolean>()
            var ocean = -1
            var oceanSize = 0
            for (start in 0 until size) {
                if (land[start] || body[start] >= 0) continue
                var top = 0
                stack[top++] = start
                body[start] = bodies
                var cells = 0
                var touches = false
                while (top > 0) {
                    val c = stack[--top]
                    cells++
                    forEachNeighbour(w, h, c) { n ->
                        if (land[n]) {
                            if (gained[n]) touches = true
                        } else if (body[n] < 0) {
                            body[n] = bodies
                            stack[top++] = n
                        }
                    }
                }
                sizes.add(cells)
                deltaic.add(touches)
                if (cells > oceanSize) {
                    oceanSize = cells
                    ocean = bodies
                }
                bodies++
            }

            // A pocket is water the sea cannot reach and that is too small to be a sea in its own
            // right: no wider than a delta lobe's own footprint. Bigger bodies than that are
            // landforms — one of them on seed 59758 holds 5880 cells — and a river ending in one
            // has reached water, whatever the connectivity says.
            val speck = { b: Int -> b != ocean && sizes[b] <= cap }
            pockets = (0 until bodies).count { speck(it) && deltaic[it] }

            var strandedCount = 0
            var inPocketCount = 0
            world.rivers.rivers.forEach { river ->
                val mouth = river.cells.last()
                if (!land[mouth] && body[mouth] != ocean) inPocketCount++

                // Is this river's mouth on a delta at all? Its own cell, or the ground beside it.
                var onDelta = land[mouth] && gained[mouth]
                var open = !land[mouth] && !speck(body[mouth])
                forEachNeighbour(w, h, mouth) { n ->
                    if (land[n] && gained[n]) onDelta = true
                    if (!land[n] && !speck(body[n])) open = true
                }
                if (onDelta && !open) strandedCount++
            }
            stranded = strandedCount
            inPocket = inPocketCount

            val height = world.erosion.height.data
            var gainedCells = 0
            var stuck = 0
            var lobeCells = 0
            var lobeStuck = 0
            for (i in 0 until size) {
                if (!gained[i]) continue
                gainedCells++
                var lower = false
                forEachNeighbour(w, h, i) { n -> if (height[n] < height[i]) lower = true }
                if (!lower) stuck++
                if (lobe[i]) {
                    lobeCells++
                    if (!lower) lobeStuck++
                }
            }
            flat = if (lobeCells == 0) 0.0 else lobeStuck.toDouble() / lobeCells
            flatAnywhere = if (gainedCells == 0) 0.0 else stuck.toDouble() / gainedCells
        }

        private fun exactLandMask(height: FloatArray, seaLevel: Float): BooleanArray {
            val sorted = height.copyOf()
            sorted.sort()
            val threshold = sorted[(sorted.size * seaLevel).toInt().coerceIn(0, sorted.size - 1)]
            return BooleanArray(height.size) { height[it] >= threshold }
        }
    }
}

private inline fun forEachNeighbour(w: Int, h: Int, cell: Int, action: (Int) -> Unit) {
    val x = cell % w
    val y = cell / w
    for (dy in -1..1) {
        val ny = y + dy
        if (ny < 0 || ny >= h) continue
        for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            var nx = (x + dx) % w
            if (nx < 0) nx += w
            action(ny * w + nx)
        }
    }
}
