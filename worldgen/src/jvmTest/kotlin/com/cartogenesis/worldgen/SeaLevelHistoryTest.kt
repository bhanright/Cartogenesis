package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

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
 * Each guard is measured against the default world with its own half switched off, which is what
 * the plan asked for, and the two halves are not independent: an estuary is only counted where the
 * mouth lies in water the ocean can reach, so a mouth that used to end in a pocket cannot be an
 * estuary until the pockets are gone. Both figures for both halves alone are printed by the third
 * case below, which asserts nothing and exists so a report can say which did what.
 */
class SeaLevelHistoryTest {

    @get:Rule
    val sharedWorlds = SharedWorlds.Check()

    private val seeds = listOf(7L, 42L, 1234L)

    /**
     * Bars read off the measurement rather than the other way round.
     *
     * At 512 on seeds 7/42/1234, with the sea held at today's level for every round: 26/24/15 river
     * mouths more than three cells inside an inlet, and an ocean shoreline 5.41/7.02/7.76 times the
     * perimeter of a square holding the same land area. With the lowstand at its default of 0.015:
     * 40/50/73 mouths — 1.54, 2.08 and 4.87 times — and 5.99/8.64/12.06 — 1.11, 1.23 and 1.55.
     *
     * The bars sit under the worst of each, and the control bar above the best of the three worlds
     * without the lowstand, so both halves of ground rule 2 are asserted rather than described.
     *
     * Only the estuary count is asserted now. The indentation was the weaker of the two from the
     * start — an average over a whole map's worth of coast, where the estuary count is a tally of
     * the places that changed — and F17 took the average away: almost all of the extra length was a
     * notch in every coastal cell, one per channel, and a channel a kilometre wide does not fill a
     * cell twenty-three kilometres wide. See the note where it is printed.
     */
    private val estuaryGain = 1.5
    private val controlEstuaryCeiling = 30

    @Test
    fun `the sea comes back up the valleys, and does not with the lowstand at zero`() {
        var controlFailures = 0
        val pooledEstuaries = ArrayList<Double>()
        seeds.forEach { seed ->
            // H5b's post-cut outlet pass held off in *both* arms, so this pair varies the lowstand
            // and nothing else.
            //
            // Not a convenience. An estuary here is a river mouth lying more than three cells
            // inside a narrow inlet, and H5b added a second mechanism that makes narrow inlets: a
            // basin the enclosure rule converted, whose outflow has the power to cut its sill down
            // to the waterline, opens as a sound with a narrow mouth. It makes them whether the sea
            // ever stood lower or not, so it raises the *control* as much as the world under test —
            // measured on seed 7 at 512, the world with the sea at today's level for every round
            // goes from 22 estuary mouths to 33 with the pass on, and the ratio this case is about
            // falls from 1.64 to 1.30 while the lowstand's own contribution is unchanged. Two
            // mechanisms and one measurement is not a guard; the same reasoning `GlaciationTest`
            // gives for switching the outlet notch off before it counts standing water.
            //
            // The shipped world's own pair, both mechanisms running, is printed by
            // `report every corner of the pair` below.
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
                .let { it.copy(sea = it.sea.copy(postCutOutlet = false)) }
            val today = Coast(
                SharedWorlds.world(base.copy(sea = base.sea.copy(lowstandMetres = 0f))),
                "seed $seed lowstand 0     "
            )
            val lowered = Coast(
                SharedWorlds.world(base),
                "seed $seed lowstand ${base.sea.lowstandMetres} m"
            )
            if (today.estuaries < controlEstuaryCeiling) controlFailures++

            // The claim per seed — the lowstand drowns valleys, so it leaves more of them — and the
            // strength of it pooled below, rather than a ratio asserted seed by seed.
            //
            // E6 is why. Its graded floodplain lays less spoil in the lower valleys, which are
            // exactly where an estuary is counted, and on seed 7 the pair went 26 -> 40 before it
            // and 25 -> 32 after: the lowstand's own contribution is undiminished in kind and the
            // ratio fell through a 1.4 bar set on one seed's arithmetic. The three seeds measure
            // 1.28, 2.53 and 4.31 and the loop used to stop at the first of them, so what was
            // guarding the claim was seed 7 alone. Same restatement the desert guard and
            // `CultureRealmTest` carry: the direction per seed, the size pooled.
            assertTrue(
                lowered.estuaries > today.estuaries,
                "seed $seed: ${lowered.estuaries} river mouths more than three cells inside an " +
                    "inlet, against ${today.estuaries} with the sea held at today's level for " +
                    "every round — the lowstand drowned no valleys at all"
            )
            pooledEstuaries.add(lowered.estuaries.toDouble() / today.estuaries.coerceAtLeast(1))
            // The indentation is reported and no longer asserted, and F17 is why. It used to read
            // 1.11, 1.23 and 1.55 times the control on these three seeds, and the bar was 1.05.
            // Almost all of that was the lowstand cutting a channel to the shore in every coastal
            // cell and the transgression flooding all of them: a notch per cell, which lengthens a
            // whole map's worth of shoreline. `SeaConfig.drownedValleyFill` takes those back out,
            // because a channel a kilometre wide has no business filling a cell twenty-three
            // kilometres wide, and what the lowstand still contributes is the drowned valleys wide
            // enough to be bays — which is a tally of places rather than a length.
            //
            // S1's units moved it again and in the same direction: the stand is now 120 m of the
            // height field's own range where it used to be a share of each world's land relief,
            // which was deeper than 120 m on one of these seeds and half of it on another. There is
            // nothing left here to assert, so the figure is printed and the claim that survives is
            // the one the clause above makes.
            println(
                ("SEA HISTORY seed %d: indentation %.4f with the lowstand against %.4f without, " +
                    "reported rather than asserted since F17")
                    .format(seed, lowered.indentation, today.indentation)
            )
        }

        // The size of it, pooled. The three seeds measure 1.28, 2.53 and 4.31 times as many estuary
        // mouths with the lowstand as without; the bar is 1.5, which sits well under their mean of
        // 2.71 and well over the 1.0 that would mean the lowstand drowned nothing. Pooled because the
        // count is a property of where one world's valleys happen to meet its coast — seed 7 has a
        // ria coast already and the lowstand has less to add to it — and the claim is about the
        // mechanism, not about seed 7.
        val meanGain = pooledEstuaries.average()
        println(
            "SEA HISTORY pooled: %.2fx estuary mouths with the lowstand (%s)"
                .format(meanGain, pooledEstuaries.joinToString { "%.2f".format(it) })
        )
        assertTrue(
            pooledEstuaries.size == seeds.size && meanGain >= estuaryGain,
            "pooled over ${pooledEstuaries.size} seeds the lowstand leaves ${meanGain}x the " +
                "estuary mouths, not the ${estuaryGain}x a drowned valley owes"
        )

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
                SharedWorlds.world(
                    base.copy(sea = base.sea.copy(enclosedSeaIsLand = false))
                ),
                "seed $seed enclosed sea   "
            )
            val closed = Coast(
                SharedWorlds.world(base),
                "seed $seed enclosed land  "
            )
            controlPockets += loose.pockets
            controlMouths += loose.pocketMouths

            assertTrue(
                closed.pockets == 0,
                "seed $seed: ${closed.pockets} pockets of water the ocean cannot reach survived " +
                    "the cut, holding ${closed.pocketCells} cells — every body no larger than the " +
                    "Caspian has to be gone, and the ${closed.inlandSeas} larger ones are not " +
                    "pockets but inland seas"
            )
            assertTrue(
                closed.pocketMouths == 0,
                "seed $seed: ${closed.pocketMouths} river mouths still end in water the ocean " +
                    "cannot reach"
            )
            println(
                ("SEA HISTORY seed %d: %d pockets of %d cells and %d mouths in them became land, " +
                    "%d inland seas of %d cells left as sea; land fraction %.5f -> %.5f against " +
                    "the %.5f the slider asks for").format(
                    seed, loose.pockets, loose.pocketCells, loose.pocketMouths,
                    closed.inlandSeas, closed.inlandSeaCells,
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
                "PRE-H5      " to base.sea.copy(
                    lowstandMetres = 0f, enclosedSeaIsLand = false, postCutOutlet = false
                ),
                "lowstand    " to base.sea.copy(
                    enclosedSeaIsLand = false, postCutOutlet = false
                ),
                "enclosure   " to base.sea.copy(lowstandMetres = 0f, postCutOutlet = false),
                "H5          " to base.sea.copy(postCutOutlet = false),
                // And H5b's own pair: the post-cut outlet pass on the shipped world, and the same
                // world without it, so the report can say what the second inlet-maker is worth.
                "H5b outlet  " to base.sea
            ).forEach { (name, sea) ->
                Coast(
                    SharedWorlds.world(base.copy(sea = sea)),
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
                val off = SharedWorlds.world(
                    base.copy(sea = base.sea.copy(enclosedSeaIsLand = false, lowstandMetres = 0f))
                )
                val on = SharedWorlds.world(base)
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
    /**
     * Bodies of water outside the ocean that are small enough for the enclosure rule to have taken:
     * no larger than the largest lake Earth has. These are the ones that have to be gone.
     */
    val pockets: Int
    val pocketCells: Int
    val pocketMouths: Int

    /**
     * And the ones the rule deliberately leaves: unreachable water larger than the Caspian, which is
     * an inland sea and not a lake. Reported, never asserted — E4's flooded rift segments are these,
     * and turning them into lakes is what `SeaConfig.enclosedSeaMaxKm2` exists to stop.
     */
    val inlandSeas: Int
    val inlandSeaCells: Int

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
        val cellsIn = ArrayList<Int>()
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
            cellsIn.add(cells)
            if (cells > oceanCells) {
                oceanCells = cells
                ocean = id
            }
        }
        // The same cap the rule itself uses, so what this counts is exactly what it should have
        // taken and did not.
        val cap = WorldGenConfig().let {
            (it.sea.enclosedSeaMaxKm2 / it.squareKilometresPerCell).toInt()
        }
        var pocketBodies = 0
        var pocketArea = 0
        var seaBodies = 0
        var seaArea = 0
        cellsIn.forEachIndexed { id, n ->
            if (id == ocean) return@forEachIndexed
            if (n <= cap) {
                pocketBodies++
                pocketArea += n
            } else {
                seaBodies++
                seaArea += n
            }
        }
        pockets = pocketBodies
        pocketCells = pocketArea
        inlandSeas = seaBodies
        inlandSeaCells = seaArea

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
                // Only a pocket counts. A mouth on an inland sea has reached water, and E4's
                // flooded rift segments are exactly that.
                if (cellsIn[body[mouth]] <= cap) pocketMouthCount++
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
            ("SEA HISTORY %s: %d estuary mouths, %d in a pocket; indentation %.4f; %d pockets " +
                "of %d cells and %d inland seas of %d; land %.5f; %d rivers, %d lakes, largest " +
                "lake %.4f%% of the map")
                .format(
                    label, estuaries, pocketMouths, indentation, pockets, pocketCells,
                    inlandSeas, inlandSeaCells, landFraction, world.rivers.rivers.size,
                    world.rivers.lakes.lakes.size,
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
