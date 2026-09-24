package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.TectonicsConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The author's coastal rift trough at 2048, measured — E7's half of the record that `RiftDepthTest`
 * begins at 512, in the audit tier for the reason `BayHeadDeltaAuditTest` is: two 2048 worlds are
 * not something the per-merge tier can afford, and the artefact lives nowhere else.
 *
 * What this settles, with figures:
 *
 *  - **How much of the trough is under water, and why.** Its flat floor is 7,397 cells inside the
 *    window and 29% of them are wet. The post-cut outlet does exactly what it is meant to: it cuts
 *    the sill to the waterline and stops there, the lake's surface ending at 0.001 of the land's
 *    relief. A half-graben's floor is a wedge, about a fifth as deep against the hinge as against
 *    the master fault, so where a rift meets the coast the water stands at sea level and the hinge
 *    shelf is dry — the lacustrine plain the author sees, with the river's fan across it. Earth's
 *    coastal rifts are drowned right across (the Gulf of California, the Red Sea) because a rift
 *    that has opened that far has thinned its crust under the whole trough; that is subsidence, a
 *    process rather than a stamp, and S2 in `REALISM_AUDIT.md`.
 *  - **How deep the water in a rift is.** Asserted, against Earth's envelope below.
 *  - **What the author's other window is.** Not a rift: every one of the 38,750 cells of it lies on
 *    an **Andean margin**, so E6's reading of the pale bench of constant width down its west side as
 *    "the sea-level cut along one contour of E4's planar half-graben" cannot be right. The bench is
 *    the trench profile, which is the one belt on this map with no along-strike variation at all.
 */
class RiftDepthAuditTest {

    /** The author's coastal rift trough, in cells at 2048, with a margin round it. */
    private val troughWindow = intArrayOf(150, 320, 330, 520)

    /** The author's southern window, which measuring settled is an Andean margin. */
    private val valleyWindow = intArrayOf(1010, 1650, 1165, 1900)

    /**
     * Earth's envelope for the deepest rift lake a world holds, in metres of water.
     *
     * Water depth, not the depth of the stamp: what the reader sees is the lake. Baikal is 1,642 m
     * deep, Tanganyika 1,470, Malawi 706, Issyk-Kul 668, the Dead Sea about 300 and Turkana 109. So
     * a world's deepest rift lake belongs at least as deep as Malawi and no deeper than Baikal.
     *
     * In metres, and read in metres: until Audit III (its A-I5) the bar was a share of "8 km of
     * relief" and the depth it was held against a share of `WorldScale.highestLandMetres`, 6 km,
     * so the envelope admitted 480 to 1,800 m where Malawi to Baikal was meant.
     *
     * A standing bound rather than a chunk's guard, and said plainly because rule 2 asks: E7
     * measured it to find out whether the floors wanted deepening, and the answer was no. What it is
     * here for is the next chunk that moves [TectonicsConfig.riftDepth] or puts subsidence under a
     * rift, either of which could take a rift lake past Baikal without anything else on the map
     * noticing.
     */
    private val deepestRiftLakeMinMetres = 706.0
    private val deepestRiftLakeMaxMetres = 1_642.0

    private fun config(): WorldGenConfig {
        val base = WorldGenConfig(seed = 718106L, width = 512, height = 512, seaLevel = 0.62f)
        return base.copy(
            tectonics = base.tectonics.copy(plateCount = 14),
            nations = base.nations.copy(nationCount = 12)
        ).atResolution(2048, 2048)
    }

    @Test
    fun `the author's trough, measured`() {
        val start = System.nanoTime()
        val world = WorldGenerationEngine.generateBlocking(config())
        val seconds = (System.nanoTime() - start) / 1e9

        troughFloor(world)
        hypsometry(world)
        valley(world)
        val deepest = deepestRiftLake(world)
        println(
            ("E7 AUDIT 718106 at 2048: deepest rift lake %.0f m of water, Earth %.0f-%.0f m " +
                "(Malawi to Baikal); generation %.1f s")
                .format(deepest, deepestRiftLakeMinMetres, deepestRiftLakeMaxMetres, seconds)
        )
        assertTrue(
            deepest in deepestRiftLakeMinMetres..deepestRiftLakeMaxMetres,
            "the deepest rift lake stands ${"%.0f".format(deepest)} m deep, outside Earth's " +
                "${deepestRiftLakeMinMetres.toInt()}-${deepestRiftLakeMaxMetres.toInt()} m"
        )
    }

    /**
     * The flat floor of the author's trough, and how much of it is under water.
     *
     * The floor is the flat bottom of the half-graben — `riftWidthCells * riftFloorShare` either side of
     * the axis, which is E4's own definition of it — inside the window, and not the whole corridor
     * out to the shoulder crests: the flanks climbing to the shoulders are a slope and stand above
     * the water for an honest reason. Under water counts the sea as well as a lake, because after
     * the post-cut outlet has taken the sill to the waterline this trough is an arm of the sea, and
     * whether the reader is looking at a gulf or a lake is not what is being asked.
     */
    private fun troughFloor(world: WorldMap) {
        val w = world.width
        val cfg = WorldGenConfig().tectonics
        val reach = cfg.riftWidthCells * cfg.riftFloorShare * (w / 512f)
        val rift = BoundaryClass.CONTINENTAL_RIFT.ordinal
        var cells = 0
        var wet = 0
        var lake = 0
        var belowTheCut = 0
        var belowTheCutNow = 0
        val ground = world.erosion.height.data
        val cut = world.sea.shorelineHeight
        val heights = ArrayList<Float>()
        for (y in troughWindow[1] until troughWindow[3]) {
            for (x in troughWindow[0] until troughWindow[2]) {
                val i = y * w + x
                if (world.plates.nearestBoundaryClass[i] != rift) continue
                if (world.plates.boundaryDistance.data[i] > reach) continue
                cells++
                if (!world.sea.isLand[i] || world.rivers.lakes.isLake(i)) wet++
                if (world.rivers.lakes.isLake(i)) lake++
                if (ground[i] < cut) belowTheCut++
                val relative = world.sea.relativeElevation.data[i]
                if (world.sea.isLand[i] && relative < 0f) belowTheCutNow++
                if (world.sea.isLand[i]) heights.add(relative)
            }
        }
        // How high the water would have to stand to cover a given share of the floor, in the
        // shoreline-relative units the rest of the stage works in. The floor of a half-graben is a
        // wedge, so this is the curve that decides how much of it any water level wets — and since
        // the sea stands at zero by definition, everything above zero here is out of the sea's
        // reach whatever is done to the sill.
        heights.sort()
        fun levelFor(share: Int): Float {
            if (heights.isEmpty()) return 0f
            val k = (cells * share / 100).coerceIn(0, heights.size - 1)
            return heights[k]
        }
        println(
            ("E7 AUDIT: the trough's floor stands at %.5f / %.5f / %.5f / %.5f of the land's " +
                "relief at its 40th / 50th / 60th / 70th percentile, where the sea is at 0")
                .format(levelFor(40), levelFor(50), levelFor(60), levelFor(70))
        )
        // The last two figures are E8's ceiling, and measuring them is what settled that chunk.
        //
        // E8 set out to wet 60% of this floor by letting the sea through a sill at the waterline.
        // Neither half of that survives the measurement. The trough is *already* an arm of the sea
        // — of the 2158 floor cells under water, 1635 are ocean and only 523 a lake, because H5b's
        // post-cut outlet cut its sill through — so there is no sill holding the sea out. And the
        // sea can only ever flood what lies below itself: 2215 of the 7397 floor cells stand below
        // the cut on the eroded terrain and 2232 on the field the map is drawn from, which is 30%,
        // so 29% wet is already 97% of everything any marine process can reach.
        //
        // What the other 70% would need is written out by the percentile line below: to cover 60%
        // of this floor the water must stand at 0.095 of the land's relief, which against the eight
        // kilometres this map is calibrated to is some 760 m *above* sea level. That is a lake
        // perched behind a dam, which is what the world held before H5b and why its largest lake
        // was four times the Caspian. The only lever that would put this wedge under water without
        // one is to put the trough itself below the cut — subsidence under the rift, which is
        // tectonics and not hydrology, and which E7 already showed cannot be had by deepening the
        // floor alone without breaking the Caspian bound.
        println(
            ("E7 AUDIT: the trough's flat floor is %d cells, %d of them under water (%d%%), %d of " +
                "that lake and the rest an arm of the sea; %d cells (%d%%) stand below the " +
                "sea-level cut on the eroded terrain, %d (%d%%) on the field the map is drawn from")
                .format(
                    cells, wet, wet * 100 / cells.coerceAtLeast(1), lake,
                    belowTheCut, belowTheCut * 100 / cells.coerceAtLeast(1),
                    belowTheCutNow + (cells - heights.size),
                    (belowTheCutNow + (cells - heights.size)) * 100 / cells.coerceAtLeast(1)
                )
        )
    }

    /** The deepest lake lying in a rift trough, in metres of water from its surface to its floor. */
    private fun deepestRiftLake(world: WorldMap): Double {
        val w = world.width
        val lakes = world.rivers.lakes
        if (lakes.lakes.isEmpty()) return 0.0
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
        var bodies = 0
        var water = 0
        lakes.lakes.forEach { lake ->
            if (riftCells[lake.id] * 2 <= cells[lake.id]) return@forEach
            bodies++
            water += lake.cellCount
            // Each end on its own half of the ruler: a floor below the shoreline stands on ground
            // the enclosure rule made land at the level it already had, which is in the sea's
            // units, and a surface above it in the land's.
            val scale = world.config.scale
            fun metres(relative: Float) =
                if (relative >= 0f) scale.metresAboveShoreline(relative) else scale.metresBelowShoreline(relative)
            val depth = (metres(lake.surfaceElevation) - metres(floor[lake.id])).toDouble()
            if (depth > deepest) deepest = depth
        }
        println("E7 AUDIT: $bodies rift lakes, $water cells of water in them")
        return deepest
    }

    /** The land's height distribution, which a deeper or rougher rift floor would move. */
    private fun hypsometry(world: WorldMap) {
        val heights = ArrayList<Float>()
        val relative = world.sea.relativeElevation.data
        for (i in 0 until world.width * world.height) {
            if (world.sea.isLand[i]) heights.add(relative[i])
        }
        heights.sort()
        val deciles = (1..9).map {
            heights[(heights.size * it / 10).coerceIn(0, heights.size - 1)]
        }
        var lakeCells = 0
        for (i in 0 until world.width * world.height) {
            if (world.rivers.lakes.isLake(i)) lakeCells++
        }
        println(
            ("E7 AUDIT: land %d cells (%.1f%% of the map), standing water %d cells (%.3f%% of " +
                "land) in %d lakes, hypsometric deciles %s")
                .format(
                    heights.size, heights.size * 100.0 / (world.width * world.height),
                    lakeCells, lakeCells * 100.0 / heights.size.coerceAtLeast(1),
                    world.rivers.lakes.lakes.size,
                    deciles.joinToString(" ") { "%.3f".format(it) }
                )
        )
    }

    /**
     * The author's southern window: standing water lying in thin bars at a grid or diagonal
     * bearing, and the longest straight run of coast in it.
     *
     * E6 left these at 102 bar cells with deposition switched off and a 31-cell straight run and
     * handed them on as E4's planar half-graben. They are neither E4's nor E7's. The window is an
     * Andean margin — not one of its cells lies on a continental rift — and the trench profile is
     * `trenchDepth * strength * narrow`, a function of the distance to the boundary and of nothing
     * else, which is a plane along strike whose every contour is a straight line, including the one
     * the sea is cut at. Modulating it with the swell every other belt uses was written, measured
     * and reverted: at that wavelength (about 160 cells at 2048, against a bench 30 cells long) it
     * slides the coast onto a different straight contour instead of bending it, and the window went
     * from 108 bar cells and a 31-cell run to 146 and 39. In `TODO.md` for a chunk that owns the
     * subduction profile.
     */
    private fun valley(world: WorldMap) {
        val w = world.width
        val h = world.height
        val lakes = world.rivers.lakes
        val land = world.sea.isLand
        val rift = BoundaryClass.CONTINENTAL_RIFT.ordinal
        fun wetAt(x: Int, y: Int): Boolean {
            if (y < 0 || y >= h) return false
            var nx = x % w
            if (nx < 0) nx += w
            return lakes.isLake(y * w + nx)
        }
        fun landAt(x: Int, y: Int): Boolean {
            if (y < 0 || y >= h) return true
            var nx = x % w
            if (nx < 0) nx += w
            return land[y * w + nx]
        }
        val axes = arrayOf(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(1, -1))
        var bars = 0
        var water = 0
        var onRift = 0
        var cells = 0
        for (y in valleyWindow[1] until valleyWindow[3]) {
            for (x in valleyWindow[0] until valleyWindow[2]) {
                cells++
                if (world.plates.nearestBoundaryClass[y * w + x] == rift) onRift++
                if (!wetAt(x, y)) continue
                water++
                for (a in axes) {
                    var run = 1
                    var k = 1
                    while (run < 64 && wetAt(x + a[0] * k, y + a[1] * k)) { run++; k++ }
                    k = 1
                    while (run < 64 && wetAt(x - a[0] * k, y - a[1] * k)) { run++; k++ }
                    if (run < 4) continue
                    var thick = 1
                    k = 1
                    while (thick <= 2 && wetAt(x - a[1] * k, y + a[0] * k)) { thick++; k++ }
                    k = 1
                    while (thick <= 2 && wetAt(x + a[1] * k, y - a[0] * k)) { thick++; k++ }
                    if (thick <= 2) { bars++; break }
                }
            }
        }
        // Unit edges between land and water: horizontal edges run in x, vertical edges in y, and a
        // run is a line of collinear ones. The same shape of measure `DeltaOutlineTest` uses.
        var longest = 0
        for (y in valleyWindow[1] until valleyWindow[3]) {
            var run = 0
            for (x in valleyWindow[0] until valleyWindow[2]) {
                if (landAt(x, y) != landAt(x, y + 1)) {
                    run++
                    if (run > longest) longest = run
                } else {
                    run = 0
                }
            }
        }
        for (x in valleyWindow[0] until valleyWindow[2]) {
            var run = 0
            for (y in valleyWindow[1] until valleyWindow[3]) {
                if (landAt(x, y) != landAt(x + 1, y)) {
                    run++
                    if (run > longest) longest = run
                } else {
                    run = 0
                }
            }
        }
        println(
            ("E7 AUDIT, the author's southern window: %d cells, %d of them on a continental rift; " +
                "%d lake cells, %d of those in thin grid-bearing or diagonal bars, longest " +
                "straight run of coast %d cells (E5's bar at this grid is 24)")
                .format(cells, onRift, water, bars, longest)
        )
    }
}
