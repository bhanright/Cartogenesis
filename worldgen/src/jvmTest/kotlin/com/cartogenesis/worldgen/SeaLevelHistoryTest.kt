package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * H5: the coast remembers that the sea has moved.
 *
 * Two claims with two causes, so two guards and two controls.
 *
 * **The lowstand.** The hydraulic rounds grade every channel to the sea they can see. With the sea
 * fixed where it is today no valley may continue below it, so every coastline is a percentile cut
 * through the land and every river arrives at the water down a slope that stops exactly at the
 * waterline. Earth's rivers did not cut to today's sea level; they cut to a stand about 120 m
 * lower, and when the ice melted the sea came back up their valleys and made the Chesapeake, the
 * Severn, Galicia's rias and the sounds of the Atlantic seaboard. `SeaConfig.lowstand` puts the
 * base level back down where it was for most of the rounds and walks it up to today over the last
 * three. What is measured is what that leaves behind: how many river mouths sit more than three
 * cells inside a narrow inlet, and how much longer the ocean's shoreline is than that of a compact
 * shape of the same area.
 *
 * **The enclosed water.** Sea level is a percentile over the height field, so any hollow below the
 * cut is drawn as ocean whether the ocean can reach it or not, and a D8 river ends at the first one
 * it meets. `SeaConfig.enclosedSeaIsLand` marks water the ocean cannot reach as land at the height
 * it already stands at and leaves the river stage to decide whether it holds a lake. What is
 * measured is the number of water bodies outside the ocean, which has to be nought, and the number
 * of river mouths ending in one, which has to be nought as well.
 *
 * The lowstand guard runs with the enclosure rule switched off on *both* sides, and that is not
 * fussiness. Marking the enclosed water as land takes cells out of the ocean's area, so the cut has
 * to fall further to keep the promise the sea-level slider makes (see
 * `SeaLevelStage.cutToOceanCoverage`), and a deeper cut drowns low coast and makes inlets of its
 * own. Measured with both halves on, seed 7 gains no estuaries at all over the enclosure rule
 * alone, while seeds 42 and 1234 gain a third and three times over — the rule has already done on
 * that seed what the lowstand would have done. Holding the enclosure rule off on both sides asks
 * the question this guard means to ask, and every seed answers it.
 */
class SeaLevelHistoryTest {

    private val seeds = listOf(7L, 42L, 1234L)

    /**
     * Bars read off the measurement rather than the other way round.
     *
     * At 512 with the enclosure rule off, seeds 7/42/1234 carry 13/14/7 river mouths more than
     * three cells inside an inlet with the sea held at today's level for every round, and 24/23/20
     * with the lowstand at its default of 0.015 — ratios of 1.85, 1.64 and 2.86. The ocean's
     * shoreline runs 4.49/7.01/8.05 times the perimeter of a square of the same land area, and
     * 6.10/8.30/11.42 after — 1.36, 1.18 and 1.42.
     *
     * The bars sit under the worst of each, and the control bar over the best of the world without
     * the lowstand, so both halves of ground rule 2 are asserted rather than described.
     */
    private val estuaryGain = 1.5
    private val indentationGain = 1.10
    private val controlEstuaryCeiling = 18

    @Test
    fun `the sea comes back up the valleys, and does not with the lowstand at zero`() {
        var controlFailures = 0
        seeds.forEach { seed ->
            // Both worlds with the enclosure rule off: see the class comment. This is the lowstand
            // measured against itself and nothing else.
            val base = WorldGenConfig(seed = seed, width = 512, height = 512).let {
                it.copy(sea = it.sea.copy(enclosedSeaIsLand = false))
            }
            val today = Coast(
                WorldGenerationEngine.generateBlocking(base.copy(sea = base.sea.copy(lowstand = 0f))),
                "seed $seed lowstand 0     "
            )
            val lowered = Coast(
                WorldGenerationEngine.generateBlocking(base),
                "seed $seed lowstand ${base.sea.lowstand}"
            )
            if (today.estuaries < controlEstuaryCeiling) controlFailures++

            assertTrue(
                lowered.estuaries >= today.estuaries * estuaryGain,
                "seed $seed: ${lowered.estuaries} river mouths more than three cells inside an " +
                    "inlet, against ${today.estuaries} with the sea held at today's level for " +
                    "every round — not the ${estuaryGain}x a drowned valley owes"
            )
            assertTrue(
                lowered.indentation >= today.indentation * indentationGain,
                "seed $seed: the ocean's shoreline is ${lowered.indentation} times a compact one " +
                    "of the same area, against ${today.indentation} with the sea held at today's " +
                    "level — not the ${indentationGain}x a drowned coast owes"
            )
        }

        // The other half of ground rule 2: the world without the lowstand has to fail a bar the
        // world with it clears, or this guard is measuring nothing.
        assertTrue(
            controlFailures == seeds.size,
            "the world with the sea held at today's level was expected to fall short of " +
                "$controlEstuaryCeiling estuary mouths on all ${seeds.size} seeds and did so on " +
                "$controlFailures"
        )
    }

    @Test
    fun `no water is left that the ocean cannot reach, and plenty is without the rule`() {
        var controlPockets = 0
        var controlMouths = 0
        seeds.forEach { seed ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            val loose = Coast(
                WorldGenerationEngine.generateBlocking(
                    base.copy(sea = base.sea.copy(enclosedSeaIsLand = false))
                ),
                "seed $seed enclosed sea   "
            )
            val closed = Coast(
                WorldGenerationEngine.generateBlocking(base),
                "seed $seed enclosed land  "
            )
            controlPockets += loose.pockets
            controlMouths += loose.pocketMouths

            assertTrue(
                closed.pockets == 0,
                "seed $seed: ${closed.pockets} bodies of water the ocean cannot reach survived " +
                    "the cut, holding ${closed.pocketCells} cells"
            )
            assertTrue(
                closed.pocketMouths == 0,
                "seed $seed: ${closed.pocketMouths} river mouths still end in water the ocean " +
                    "cannot reach"
            )
            println(
                ("SEA HISTORY seed %d: %d pockets of %d cells and %d mouths in them became land; " +
                    "land fraction %.5f -> %.5f against the %.5f the slider asks for").format(
                    seed, loose.pockets, loose.pocketCells, loose.pocketMouths,
                    loose.landFraction, closed.landFraction, 1f - base.seaLevel
                )
            )
        }
        assertTrue(
            controlPockets > 0 && controlMouths > 0,
            "the control was expected to leave water the ocean cannot reach ($controlPockets " +
                "bodies) with rivers ending in it ($controlMouths mouths), so this guard proves " +
                "nothing"
        )
    }

    /**
     * The whole before-and-after table, printed rather than asserted: the two halves of H5 on their
     * own and together, so the report can say which did what.
     */
    @Test
    fun `report every corner of the pair`() {
        seeds.forEach { seed ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            listOf(
                "PRE-H5      " to base.sea.copy(lowstand = 0f, enclosedSeaIsLand = false),
                "lowstand    " to base.sea.copy(enclosedSeaIsLand = false),
                "enclosure   " to base.sea.copy(lowstand = 0f),
                "H5          " to base.sea
            ).forEach { (name, sea) ->
                Coast(
                    WorldGenerationEngine.generateBlocking(base.copy(sea = sea)),
                    "seed $seed $name"
                )
            }
        }
        // The promise the sea-level slider makes, at the two ends `PipelineTest` asks about and at
        // the author's own setting in between.
        listOf(0.3f, 0.62f, 0.8f).forEach { level ->
            listOf(128, 512).forEach { size ->
                val base = WorldGenConfig(seed = 42L, width = size, height = size)
                    .copy(seaLevel = level)
                val off = WorldGenerationEngine.generateBlocking(
                    base.copy(sea = base.sea.copy(enclosedSeaIsLand = false, lowstand = 0f))
                )
                val on = WorldGenerationEngine.generateBlocking(base)
                println(
                    "SEA HISTORY PROMISE seaLevel %.2f at %d: land %.5f -> %.5f (wanted %.5f)"
                        .format(level, size, off.landFraction(), on.landFraction(), 1f - level)
                )
            }
        }
    }
}

/**
 * Everything the guards above read off a finished world, taken together because they share one
 * labelling of the water and one distance field over it.
 *
 * The ocean is the largest connected body of water, by the eight-connected walk that wraps in x
 * that every other neighbour walk in this generator uses. Everything else is a pocket. Because a
 * pocket is not eight-connected to the ocean it is not four-connected to it either, so no cell of
 * it lies on the ocean's shoreline: the estuary and indentation figures below are measured against
 * the ocean alone and the enclosure rule cannot move them by one edge.
 */
internal class Coast(world: WorldMap, label: String) {
    /** Bodies of water outside the ocean, how many cells they hold, and how many mouths end in one. */
    val pockets: Int
    val pocketCells: Int
    val pocketMouths: Int

    /** River mouths lying more than [INLET_LENGTH] cells inside water narrower than [NARROW]. */
    val estuaries: Int

    /** The ocean's shoreline, against the perimeter of a square holding the same land area. */
    val indentation: Double
    val landFraction: Double

    init {
        val w = world.width
        val h = world.height
        val size = w * h
        val land = world.sea.isLand
        // In cells at 512 and rescaled with the grid: an estuary is a shape on the map, not a
        // number of samples of it.
        val scale = w / 512f
        val narrow = NARROW * scale
        val inletLength = INLET_LENGTH * scale

        val body = IntArray(size) { -1 }
        val stack = IntArray(size)
        var bodies = 0
        var ocean = -1
        var oceanCells = 0
        for (start in 0 until size) {
            if (land[start] || body[start] >= 0) continue
            val id = bodies++
            var top = 0
            body[start] = id
            stack[top++] = start
            var cells = 0
            while (top > 0) {
                val c = stack[--top]
                cells++
                eightNeighbours(w, h, c) { n ->
                    if (!land[n] && body[n] < 0) {
                        body[n] = id
                        stack[top++] = n
                    }
                }
            }
            if (cells > oceanCells) {
                oceanCells = cells
                ocean = id
            }
        }
        pockets = if (bodies == 0) 0 else bodies - 1
        pocketCells = size - world.sea.landCellCount - oceanCells

        // How far each cell of water lies from land, so that narrow water can be told from open.
        val toLand = FloatArray(size) { if (land[it]) 0f else JumpFloodDistance.INFINITE }
        val nearest = IntArray(size) { if (land[it]) it else -1 }
        JumpFloodDistance.run(w, h, toLand, nearest)

        // And how far into the narrow water each cell of it lies: a breadth-first walk inward from
        // the open sea. Open water is depth zero; water the walk never reaches keeps -1, which is
        // every pocket and nothing else once the enclosure rule has run.
        val depth = IntArray(size) { -1 }
        val queue = IntArray(size)
        var head = 0
        var tail = 0
        for (i in 0 until size) {
            if (body[i] == ocean && toLand[i] > narrow) {
                depth[i] = 0
                queue[tail++] = i
            }
        }
        while (head < tail) {
            val i = queue[head++]
            eightNeighbours(w, h, i) { n ->
                if (!land[n] && depth[n] < 0) {
                    depth[n] = depth[i] + 1
                    queue[tail++] = n
                }
            }
        }

        var estuaryCount = 0
        var pocketMouthCount = 0
        world.rivers.rivers.forEach { river ->
            val mouth = river.cells.last()
            if (land[mouth]) return@forEach
            if (body[mouth] != ocean) {
                pocketMouthCount++
                return@forEach
            }
            if (depth[mouth] > inletLength) estuaryCount++
        }
        estuaries = estuaryCount
        pocketMouths = pocketMouthCount

        // The ocean's shoreline in four-connected edges, against the perimeter a square of the same
        // land area would have. Dimensionless, so 512 and 2048 are comparable; one is a coast with
        // no bays in it at all.
        var edges = 0
        for (i in 0 until size) {
            if (!land[i]) continue
            val x = i % w
            val y = i / w
            if (body[y * w + (x + 1) % w] == ocean) edges++
            if (body[y * w + (x + w - 1) % w] == ocean) edges++
            if (y > 0 && body[(y - 1) * w + x] == ocean) edges++
            if (y < h - 1 && body[(y + 1) * w + x] == ocean) edges++
        }
        val area = world.sea.landCellCount.toDouble()
        indentation = if (area <= 0) 0.0 else edges / (4.0 * sqrt(area))
        landFraction = area / size

        println(
            ("SEA HISTORY %s: %d estuary mouths, %d in unreachable water; indentation %.4f; " +
                "%d pockets of %d cells; land %.5f; %d rivers, %d lakes, largest lake %.4f%%")
                .format(
                    label, estuaries, pocketMouths, indentation, pockets, pocketCells,
                    landFraction, world.rivers.rivers.size, world.rivers.lakes.lakes.size,
                    (world.rivers.lakes.lakes.maxOfOrNull { it.cellCount } ?: 0) * 100.0 / size
                )
        )
    }

    companion object {
        /**
         * Half-width, in cells at 512, below which water counts as an inlet rather than as open
         * sea. Two cells is a channel about four across — twenty kilometres to the cell makes that
         * a sound or a wide estuary, and narrow enough that an ordinary open coast scores nothing.
         */
        const val NARROW = 2f

        /** How far inside that narrow water a mouth must lie to be an estuary, in cells at 512. */
        const val INLET_LENGTH = 3f
    }
}

/** Eight neighbours, wrapping in x and stopping at the poles, as `FlowRouting` does. */
internal inline fun eightNeighbours(w: Int, h: Int, cell: Int, action: (Int) -> Unit) {
    val x = cell % w
    val y = cell / w
    for (dy in -1..1) {
        val ny = y + dy
        if (ny < 0 || ny >= h) continue
        for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            action(ny * w + ((x + dx + w) % w))
        }
    }
}
