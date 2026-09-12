package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.TectonicsConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.test.Test

/**
 * What a rift trough is actually shaped like, measured — E7's record, and the reason that chunk
 * changed no geometry.
 *
 * The author, on the coastal rift trough of seed 718106 at 2048 after H5b: *"in some ways the
 * version of this rift on the left looks more natural. it feels odd to have a delta deposition
 * within a lake or closed sea rift like that."* Before H5b the trough held one long lake the size of
 * the Caspian; after it the post-cut outlet drained it to a strip against the eastern wall and the
 * river's lacustrine fan stood exposed across the northern half as a flat plain. E7 was dispatched
 * on two readings of that picture — that the trough is **shallow**, and that its floor is a
 * **plane** — and measured both before changing anything. Neither survives.
 *
 *  - **The floors are not shallow.** The plan's own Earth figure is a floor 12-20% of the land's
 *    relief below the shoulder crest. Measured on the stamp over five seeds at 512 and at 2048,
 *    E4's floor already stands **45-72%** below its crest, against Earth's own 21-50% (Baikal
 *    3.2-4.0 km of crest-to-floor against 8 km of relief, Tanganyika 2.8-3.8, Malawi 1.7-2.7, the
 *    Dead Sea 1.7-1.9). The water it ends up holding is Earth's too: the deepest rift lake on the
 *    author's world is 24.2% of the land's relief at 2048 against Baikal's 20%. Raising
 *    [TectonicsConfig.riftDepth] was tried and reverted with the figures — see its own comment.
 *  - **The floor is not a plane either.** Within half a segment it already rises and falls by
 *    65-76% of the trough's own depth: the accommodation zones, the per-segment depth factor, the
 *    terrain blend and the detail noise between them. The straight-contour guard the plan asked for
 *    was written three ways and could not tell E4's floor from a floor with a chain of hashed deeps
 *    laid on it.
 *  - **What is wrong with the author's trough** is that only 29% of its flat floor lies below sea
 *    level, which `RiftDepthAuditTest` measures. The post-cut outlet does exactly what it is meant
 *    to — it cuts the sill to the waterline and stops, the lake's surface ending at 0.001 of the
 *    land's relief — and a half-graben's floor is a wedge, so what stays wet is the part under the
 *    sea and nothing else. The hinge shelf is the plain the author is looking at.
 *
 * A floor of hashed sub-basins *was* built and does improve that scene — 38% of the trough's floor
 * under water against 29% — and was reverted anyway, because at every amplitude from 0.12 to 0.45
 * of the segment's depth it left a closed basin below the sea-level cut that the post-cut outlet
 * cannot open: seeds 718106 and 99 came out holding a drowned basin of 0.46-0.54% of their land,
 * about twice the Caspian's share of Earth's, against `OutletIncisionTest`'s bar. A rift lake
 * larger than the largest lake Earth has is not the improvement the chunk was for, and rule 5 says
 * the bar does not move to let it through. What is left is this: the measurements, standing, so
 * that the next attempt starts from them.
 */
class RiftDepthTest {

    /** The three the plan renders every chunk against, plus the author's own two. */
    private val seeds = listOf(7L, 42L, 1234L, 718106L, 59758L)

    private fun config(seed: Long, size: Int = 512): WorldGenConfig {
        val base = WorldGenConfig(seed = seed, width = 512, height = 512, seaLevel = 0.62f)
        val authored = base.copy(
            tectonics = base.tectonics.copy(plateCount = 14),
            nations = base.nations.copy(nationCount = 12)
        )
        return if (size == 512) authored else authored.atResolution(size, size)
    }

    /**
     * The stamped profile of a rift against Earth's, and how much the floor varies within itself.
     *
     * Reported, not asserted, and at both grids, because the stamp's units are shares of a field
     * that is normalized and then cut at a percentile, so the share of the *land's* relief they
     * come out as is not the same number at 512 and at 2048. Measured on the stamp alone, so the
     * whole class costs two tectonic stages per seed and no erosion at all.
     */
    @Test
    fun `report the stamped rift profile and the floor's own relief`() {
        listOf(512, 2048).forEach { size ->
            seeds.forEach { seed -> stampedProfile(config(seed, size), seed) }
        }
        seeds.forEach { seed -> floorRelief(config(seed), seed) }
    }

    /**
     * What the rift troughs of a finished world hold at 512, and why nothing about it is asserted.
     *
     * A 512 world carries one or two lakes in a rift, of twelve to two hundred and eighty cells,
     * and pooled over these five seeds the troughs hold about four hundred cells of water. Numbers
     * that small cannot separate one rift geometry from another — the chain-of-deeps experiment
     * moved them from 419 cells in 6 lakes to 413 in 8 — so the question is asked at 2048 in
     * `RiftDepthAuditTest` and only reported here.
     */
    @Test
    fun `report what a rift trough holds at 512`() {
        var water = 0
        var bodies = 0
        seeds.forEach { seed ->
            val trough = troughWater(WorldGenerationEngine.generateBlocking(config(seed)), seed)
            water += trough.cells
            bodies += trough.lakes
        }
        println(
            "E7 512 pooled over five seeds: rift troughs hold $water cells of standing water in " +
                "$bodies lakes"
        )
    }

    // ------------------------------------------------------------------------------ measurement

    private class Trough(val cells: Int, val lakes: Int, val deepest: Double)

    /**
     * The standing water lying in a rift trough: how many cells of it, in how many separate lakes,
     * and how deep the deepest of them is as a share of the land's relief.
     *
     * The trough is the corridor out to the shoulder crests of a present-epoch continental-rift
     * boundary, which is the corridor [inRiftTrough] uses for the same purpose. A lake counts when
     * more than half of it lies in one. Depth is the water surface minus the lowest ground under
     * it, both read off `sea.relativeElevation`, whose land half is scaled by the land's own relief
     * — so the difference is already a share of that relief, at any grid.
     */
    private fun troughWater(world: WorldMap, seed: Long): Trough {
        val w = world.width
        val lakes = world.rivers.lakes
        if (lakes.lakes.isEmpty()) return Trough(0, 0, 0.0)
        val relative = world.sea.relativeElevation.data
        val cells = IntArray(lakes.lakes.size)
        val riftCells = IntArray(lakes.lakes.size)
        val floor = FloatArray(lakes.lakes.size) { Float.MAX_VALUE }
        for (i in 0 until w * world.height) {
            val id = lakes.lakeId[i]
            if (id < 0) continue
            cells[id]++
            if (inRiftTrough(world, i)) riftCells[id]++
            if (relative[i] < floor[id]) floor[id] = relative[i]
        }
        var deepest = 0.0
        var water = 0
        var bodies = 0
        lakes.lakes.forEach { lake ->
            if (riftCells[lake.id] * 2 <= cells[lake.id]) return@forEach
            bodies++
            water += lake.cellCount
            val depth = (lake.surfaceElevation - floor[lake.id]).toDouble()
            if (depth > deepest) deepest = depth
        }
        println(
            "E7 seed %d at %d: %d rift lakes, %d cells of water, deepest %.1f%% of relief"
                .format(seed, w, bodies, water, deepest * 100)
        )
        return Trough(water, bodies, deepest)
    }

    /** Crest to floor across a rift, as a share of the stamped field's own land relief. */
    private fun stampedProfile(config: WorldGenConfig, seed: Long): Double {
        val w = config.width
        val h = config.height
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val height = plates.height.data
        val rift = BoundaryClass.CONTINENTAL_RIFT.ordinal
        val classes = plates.nearestBoundaryClass
        val distance = plates.boundaryDistance.data

        // The land's relief on this field: the sea-level percentile to the highest ground.
        val sorted = height.copyOf()
        sorted.sort()
        val cut = sorted[(sorted.size * config.seaLevel).toInt().coerceIn(0, sorted.size - 1)]
        val relief = (sorted.last() - cut).coerceAtLeast(1e-6f)

        val floorReach = config.tectonics.riftWidth * config.tectonics.riftFloorShare
        val crestLo = config.tectonics.riftShoulderOffset - config.tectonics.riftShoulderWidth * 0.3f
        val crestHi = config.tectonics.riftShoulderOffset + config.tectonics.riftShoulderWidth * 0.3f
        val floors = ArrayList<Float>()
        val crests = ArrayList<Float>()
        for (i in 0 until w * h) {
            if (classes[i] != rift) continue
            val d = distance[i]
            if (d <= floorReach) floors.add(height[i])
            else if (d in crestLo..crestHi) crests.add(height[i])
        }
        if (floors.isEmpty() || crests.isEmpty()) {
            println("E7 STAMP seed $seed at $w: no rift")
            return 0.0
        }
        floors.sort()
        crests.sort()
        // The deepest tenth of the floor against the highest tenth of the crest: a rift's depth is
        // the depth of its deeps and its shoulder height the height of its footwalls, not means
        // over ground that includes every accommodation zone and every hinge flank.
        val deep = floors[floors.size / 10]
        val high = crests[crests.size - 1 - crests.size / 10]
        val share = (high - deep) / relief
        println(
            ("E7 STAMP seed %d at %d: relief %.4f, floor %.4f, crest %.4f, crest-to-floor %.1f%% " +
                "of relief (Earth 21-50%%: Baikal 40-50, Tanganyika 35-47, Malawi 21-34)")
                .format(seed, w, relief, deep, high, share * 100)
        )
        return share.toDouble()
    }

    /**
     * How much a rift floor rises and falls within itself, as a share of the trough's own depth.
     *
     * For every cell of the flat floor, the range of the floor's height within half a segment of
     * it, averaged, divided by the depth of the trough (crest to floor, as [stampedProfile]
     * measures them). Half a segment because a rift is a chain of basins one segment long and the
     * question is whether one basin's floor differs from the next.
     *
     * This was written to be E7's guard, and is the measurement that stopped the chunk: E4's floor
     * reads 65-76% here, so it is not a plane in any sense a number can hold, and a floor with a
     * chain of hashed deeps laid on it reads 70-79% — the same population. Most of what it finds is
     * the accommodation zone rising to the regional level at each join, and under all of it the
     * terrain blend, which no belt profile flattens.
     */
    private fun floorRelief(config: WorldGenConfig, seed: Long): Float {
        val w = config.width
        val h = config.height
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val rift = BoundaryClass.CONTINENTAL_RIFT.ordinal
        val cfg = config.tectonics
        val height = plates.height.data
        val classes = plates.nearestBoundaryClass
        val distance = plates.boundaryDistance.data
        val floorReach = cfg.riftWidth * cfg.riftFloorShare
        val inFloor = BooleanArray(w * h) { classes[it] == rift && distance[it] <= floorReach }
        var floorCells = 0
        for (i in 0 until w * h) if (inFloor[i]) floorCells++
        if (floorCells == 0) {
            println("E7 seed $seed at $w: no rift floor to measure")
            return 0f
        }

        val crestLo = cfg.riftShoulderOffset - cfg.riftShoulderWidth * 0.3f
        val crestHi = cfg.riftShoulderOffset + cfg.riftShoulderWidth * 0.3f
        val floors = ArrayList<Float>()
        val crests = ArrayList<Float>()
        for (i in 0 until w * h) {
            if (classes[i] != rift) continue
            val d = distance[i]
            if (d <= floorReach) floors.add(height[i])
            else if (d in crestLo..crestHi) crests.add(height[i])
        }
        floors.sort()
        crests.sort()
        val depth = (crests[crests.size - 1 - crests.size / 10] - floors[floors.size / 10])
            .coerceAtLeast(1e-6f)

        val radius = (cfg.riftSegmentMin * w / 2f).toInt().coerceAtLeast(2)
        var total = 0.0
        var counted = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!inFloor[y * w + x]) continue
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -radius..radius) {
                        var nx = (x + dx) % w
                        if (nx < 0) nx += w
                        val n = ny * w + nx
                        if (!inFloor[n]) continue
                        val v = height[n]
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                }
                if (hi > lo) {
                    total += (hi - lo) / depth
                    counted++
                }
            }
        }
        val share = if (counted == 0) 0f else (total / counted).toFloat()
        println(
            ("E7 seed %d at %d: %d floor cells, trough depth %.4f, the floor's own relief within " +
                "half a segment (%d cells) is %.1f%% of that depth")
                .format(seed, w, floorCells, depth, radius, share * 100)
        )
        return share
    }
}
