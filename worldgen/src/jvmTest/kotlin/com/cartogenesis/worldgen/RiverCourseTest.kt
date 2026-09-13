package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.RiverStage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a `River` is: the whole of one watercourse, unbroken by water it is wider than.
 *
 * Two claims, both about [com.cartogenesis.worldgen.pipeline.RiverStage]'s tracing rather than
 * about the terrain under it, and both raised by looking at seed 298405 at 1024 (F15).
 *
 * The first is M1's. A course used to be traced from the headwater carrying the most water, which
 * is very often a short fat tributary joining the river partway down, so the object called a
 * `River` held the wrong half of its own catchment and the longest watercourse in it was drawn
 * afterwards as a tributary stopping at the junction. Measured over seeds 7/42/1234/99 at 512 the
 * drawn courses covered 0.484 of the watercourses they stood for by length, and 0.408 over six
 * seeds at 2048, where 1.0 is the definition — a river *is* its own longest watercourse.
 *
 * The second is William's. A lake fills its basin to the spill, which at the ends of the basin
 * covers the channel that feeds it; where that strip is one cell wide the tracer used to stop at
 * it and start again on the far side, and the map drew a whole catchment's trunk as a one-pixel
 * thread of standing water between two thick channels. See `LakeResult.openWater`.
 */
class RiverCourseTest {

    private companion object {
        /** Ground rule 1's seeds plus the audit's fourth, at the size a preview is drawn at. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val SIDE = 512
    }

    private fun world(seed: Long, side: Int = SIDE): WorldMap =
        WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(side, side)
        )

    /**
     * The drawn network, flattened: which cells are channel, and how long the watercourse above
     * each of them runs.
     *
     * The channel mask is rebuilt rather than read, because the tracer keeps none: it is the same
     * three conditions, land, not open water, and carrying at least the source threshold's share
     * of the world's runoff.
     */
    private class Network(val world: WorldMap) {
        val isChannel: BooleanArray
        /** The longest watercourse ending at each channel cell, in cells, itself included. */
        val longestAbove: IntArray
        /** The largest discharge among the headwaters that drain to each channel cell. */
        val biggestHead: FloatArray
        /** How long the course from that headwater down to each cell runs, in cells. */
        val fromBiggestHead: IntArray

        init {
            val w = world.width
            val cells = w * world.height
            val flow = world.rivers.flowAccumulation.data
            val target = world.rivers.flowTarget
            val lakes = world.rivers.lakes
            var runoff = 0f
            for (i in 0 until cells) {
                if (world.sea.isLand[i]) runoff += 0.05f + world.climate.precipitation.data[i]
            }
            val threshold = (runoff * world.config.rivers.sourceFlowShare)
                .coerceAtLeast(RiverStage.MIN_SOURCE_FLOW)
            isChannel = BooleanArray(cells) {
                world.sea.isLand[it] && !lakes.isOpenWater(it) && flow[it] >= threshold
            }

            // Downstream order: a cell's answer needs its donors' answers, and every donor carries
            // less water than it does, so rising discharge is the order to walk in.
            val order = (0 until cells).filter { isChannel[it] }.sortedBy { flow[it] }
            longestAbove = IntArray(cells) { 1 }
            biggestHead = FloatArray(cells)
            fromBiggestHead = IntArray(cells) { 1 }
            val hasUpstream = BooleanArray(cells)
            order.forEach { cell ->
                val below = target[cell]
                if (below >= 0 && isChannel[below]) hasUpstream[below] = true
            }
            order.forEach { cell ->
                if (!hasUpstream[cell]) biggestHead[cell] = flow[cell]
                val below = target[cell]
                if (below < 0 || !isChannel[below]) return@forEach
                if (longestAbove[cell] + 1 > longestAbove[below]) {
                    longestAbove[below] = longestAbove[cell] + 1
                }
                if (biggestHead[cell] > biggestHead[below]) {
                    biggestHead[below] = biggestHead[cell]
                    fromBiggestHead[below] = fromBiggestHead[cell] + 1
                }
            }
        }
    }

    /**
     * A drawn river runs the whole of its own longest watercourse.
     *
     * Measured only on the courses that end in water, because those are the ones that stand for a
     * whole catchment; a course ending on another river is a tributary, and the reach it holds is
     * the right one by the same rule applied to what was left. The control is the order this
     * replaces — from the headwater with the most water, whose course down to the same mouth is
     * what [Network.fromBiggestHead] follows — and it has to fail.
     */
    @Test
    fun `a river is its own longest watercourse`() {
        var drawnTotal = 0L
        var longestTotal = 0L
        var beforeTotal = 0L
        SEEDS.forEach { seed ->
            val network = Network(world(seed))
            val world = network.world
            var drawnHere = 0L
            var longestHere = 0L
            var beforeHere = 0L
            var courses = 0
            var worst = 1.0
            world.rivers.rivers.forEach { river ->
                val last = river.cells.last { network.isChannel[it] }
                if (river.cells.last() == last) return@forEach // stops on another river
                courses++
                val drawn = river.cells.count { network.isChannel[it] }
                val longest = network.longestAbove[last]
                drawnHere += drawn
                longestHere += longest
                beforeHere += network.fromBiggestHead[last]
                worst = minOf(worst, drawn.toDouble() / longest)
                assertTrue(
                    drawn >= longest,
                    "seed $seed: the course into cell $last is $drawn cells where the longest " +
                        "watercourse above it is $longest"
                )
            }
            drawnTotal += drawnHere
            longestTotal += longestHere
            beforeTotal += beforeHere
            println(
                ("RIVERCOURSE seed=$seed $courses courses into water, coverage %.3f (worst %.3f), " +
                    "%.3f from the biggest headwater").format(
                    drawnHere.toDouble() / longestHere, worst,
                    beforeHere.toDouble() / longestHere
                )
            )
        }
        val coverage = drawnTotal.toDouble() / longestTotal
        val before = beforeTotal.toDouble() / longestTotal
        println(
            "RIVERCOURSE pooled coverage %.3f now against %.3f from the biggest headwater"
                .format(coverage, before)
        )
        assertTrue(coverage >= 1.0, "pooled coverage is %.3f, under 1.0".format(coverage))
        assertTrue(
            before < 1.0,
            "the biggest-headwater order now covers the watercourse too, so this guard has " +
                "stopped discriminating"
        )
    }

    /**
     * A river runs through water narrower than itself, and stops only at open water.
     *
     * The population is real and worth printing: these are the cells a lake's spill level puts
     * under water along the channel that feeds it, every one of which used to end a course and
     * begin another.
     */
    @Test
    fun `a course is not broken by water one cell wide`() {
        var narrowTotal = 0
        var drawnTotal = 0
        SEEDS.forEach { seed ->
            val network = Network(world(seed))
            val world = network.world
            val lakes = world.rivers.lakes
            val cells = world.width * world.height
            val drawn = BooleanArray(cells)
            // A cell a course runs *through*, as against the one it stops on: a tributary's last
            // cell is the trunk it joins, and the trunk carries on past it.
            val runThrough = BooleanArray(cells)
            world.rivers.rivers.forEach { river ->
                river.cells.forEachIndexed { k, cell ->
                    drawn[cell] = true
                    if (k < river.cells.size - 1) runThrough[cell] = true
                }
            }

            var narrow = 0
            var narrowDrawn = 0
            for (i in 0 until cells) {
                if (!lakes.isLake(i) || lakes.isOpenWater(i) || !network.isChannel[i]) continue
                narrow++
                if (drawn[i]) narrowDrawn++
            }
            narrowTotal += narrow
            drawnTotal += narrowDrawn

            // A break: a drawn line stops at water one cell wide and nothing carries on through
            // it, *and there was somewhere for it to carry on to*.
            //
            // Unless there is nothing to carry on to. A lake below its spill is endorheic — the
            // Caspian, not Erie — so its cells are sinks in the flow graph and the rivers that run
            // into it are the end of the story. A course stopping at one of those has not been
            // broken by the water it stopped at; it has arrived, and a line drawn onward would be
            // drawing water that is not there.
            //
            // Both sides of the merge met this on seed 99 and each wrote the arrival down its own
            // way, so both tests stand. The facet routing found three courses ending in the same
            // 668-cell endorheic lake at cells whose receiver is -1 by construction; S1's units
            // found three on the same seed in a 242-cell one, at places where it happens to be one
            // cell wide. The other three seeds have none either way. Counting them was measuring
            // the lake's dryness rather than the tracing this class is about.
            var broken = 0
            world.rivers.rivers.forEach { river ->
                val end = river.cells.last()
                if (!lakes.isLake(end) || lakes.isOpenWater(end) || runThrough[end]) return@forEach
                if (world.rivers.flowTarget[end] < 0) return@forEach
                if (lakes.lakes[lakes.lakeId[end]].endorheic) return@forEach
                broken++
            }
            // A gap: narrow water with a drawn channel above it and a drawn channel below, and no
            // line across. This is the thread William saw, counted.
            val fedByADrawnChannel = BooleanArray(cells)
            for (donor in 0 until cells) {
                if (!drawn[donor]) continue
                val below = world.rivers.flowTarget[donor]
                if (below >= 0) fedByADrawnChannel[below] = true
            }
            var gaps = 0
            for (i in 0 until cells) {
                if (drawn[i] || !network.isChannel[i] || !fedByADrawnChannel[i]) continue
                if (!lakes.isLake(i) || lakes.isOpenWater(i)) continue
                val below = world.rivers.flowTarget[i]
                if (below >= 0 && drawn[below]) gaps++
            }

            println(
                "RIVERCOURSE seed=$seed $narrow channel cells under water one cell wide, " +
                    "$narrowDrawn of them drawn, $broken breaks, $gaps gaps"
            )
            assertTrue(broken == 0, "seed $seed: $broken courses stop dead at water one cell wide")
            assertTrue(gaps == 0, "seed $seed: $gaps drawn lines have a gap at water one cell wide")
        }
        // Under the rule this replaces every one of these cells was struck out of the channel mask,
        // so none of them was ever drawn and every line that reached one stopped there.
        assertTrue(
            narrowTotal > 0,
            "no lake on any seed is one cell wide anywhere, so this guard has stopped discriminating"
        )
        println(
            "RIVERCOURSE $narrowTotal channel cells under water one cell wide, $drawnTotal drawn, " +
                "none of them drawn at all under the rule this replaces"
        )
    }
}
