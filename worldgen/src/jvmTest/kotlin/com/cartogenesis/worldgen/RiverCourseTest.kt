package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ChannelInitiation
import com.cartogenesis.worldgen.pipeline.FlowRouting
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
 * The second is the author's. A lake fills its basin to the spill, which at the ends of the basin
 * covers the channel that feeds it; where that strip is one cell wide the tracer used to stop at
 * it and start again on the far side, and the map drew a whole catchment's trunk as a one-pixel
 * thread of standing water between two thick channels. See `LakeResult.openWater`.
 */
class RiverCourseTest : BorrowsSharedWorlds() {

    private companion object {
        /** Ground rule 1's seeds plus the audit's fourth, at the size a preview is drawn at. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val SIDE = 512

        /**
         * How far short of its own longest watercourse one drawn course may fall, in kilometres.
         *
         * A metre, which is a tolerance on the arithmetic and not on the rule. The drawn length and
         * the longest watercourse are summed over the same steps in the same order in doubles, so
         * they agree to the last bit when the course is right; a metre is there because "the same
         * order" is a property of two loops rather than of one. Measured, no course needs it: the
         * worst coverage on the four seeds is 1.000 and so is the pooled figure.
         *
         * It was twelve kilometres — one north-south step — for the length of one run, on the
         * theory that two heads of exactly equal length could tie to the wrong arm. They cannot:
         * the sort's tie-break is the cell index and the trace follows whichever arm it picked the
         * whole way down, so the drawn course is that arm's full length either way.
         */
        const val ONE_STEP_OF_SLACK_KM = 0.001
    }

    private fun world(seed: Long, side: Int = SIDE): WorldMap =
        SharedWorlds.world(
            WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(side, side)
        )

    /**
     * The drawn network, flattened: which cells are channel, and how long the watercourse above
     * each of them runs.
     *
     * The channel mask is rebuilt rather than read, because the tracer keeps none:
     * [com.cartogenesis.worldgen.pipeline.ChannelInitiation] is the stage's own criterion and this
     * asks it the same question about the same finished world.
     */
    private class Network(val world: WorldMap) {
        val isChannel: BooleanArray

        /** The ground one D8 step covers, so the drawn courses can be measured the same way. */
        lateinit var stepKilometres: (Int, Int) -> Double
            private set
        /**
         * The longest watercourse ending at each channel cell, in kilometres.
         *
         * Kilometres since R1 and not cells, because the tracer ranks its heads in kilometres and
         * a guard has to ask the question the rule answers. On a world twice as wide as it is tall
         * the two disagree: a cell is 23.4 km across and 11.7 down at 512, so six cells north is
         * less ground than five cells east. `RiverStage.kilometresToTheWater` has the case that
         * found it.
         */
        val longestAbove: DoubleArray
        /** The largest discharge among the headwaters that drain to each channel cell. */
        val biggestHead: FloatArray
        /** How far the course from that headwater down to each cell runs, in kilometres. */
        val fromBiggestHead: DoubleArray

        init {
            val w = world.width
            val cells = w * world.height
            val flow = world.rivers.flowAccumulation.data
            val target = world.rivers.flowTarget
            val lakes = world.rivers.lakes
            isChannel = ChannelInitiation.channelMaskOf(world)

            // Downstream order: a cell's answer needs its donors' answers, and every donor carries
            // less water than it does, so rising discharge is the order to walk in.
            val order = (0 until cells).filter { isChannel[it] }.sortedBy { flow[it] }
            val scale = world.config.scale
            val cellWidthKm = scale.cellWidthKm(w)
            val cellHeightKm = scale.cellHeightKm(world.height)
            val diagonalKm =
                kotlin.math.sqrt(cellWidthKm * cellWidthKm + cellHeightKm * cellHeightKm)
            fun stepKm(from: Int, to: Int): Double {
                var columnStep = (to % w) - (from % w)
                if (columnStep > w / 2) columnStep -= w
                if (columnStep < -w / 2) columnStep += w
                val rowStep = (to / w) - (from / w)
                return when {
                    columnStep != 0 && rowStep != 0 -> diagonalKm
                    columnStep != 0 -> cellWidthKm
                    else -> cellHeightKm
                }
            }
            longestAbove = DoubleArray(cells)
            biggestHead = FloatArray(cells)
            fromBiggestHead = DoubleArray(cells)
            val hasUpstream = BooleanArray(cells)
            order.forEach { cell ->
                val below = target[cell]
                if (below >= 0 && isChannel[below]) hasUpstream[below] = true
            }
            order.forEach { cell ->
                if (!hasUpstream[cell]) biggestHead[cell] = flow[cell]
                val below = target[cell]
                if (below < 0 || !isChannel[below]) return@forEach
                val step = stepKm(cell, below)
                if (longestAbove[cell] + step > longestAbove[below]) {
                    longestAbove[below] = longestAbove[cell] + step
                }
                if (biggestHead[cell] > biggestHead[below]) {
                    biggestHead[below] = biggestHead[cell]
                    fromBiggestHead[below] = fromBiggestHead[cell] + step
                }
            }
            this.stepKilometres = ::stepKm
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
     *
     * One case is excused, and only with proof that it is the lake exemption at work rather than a
     * fault in the order. A course that falls short of the walk here is accepted when two things
     * hold. First, the longest watercourse above its mouth, measured as the tracer measures a
     * course (every step of it, the last one into the water included), is shorter than
     * `RiverConfig.shortestDrawnCourseKm`, so the length rule would have dropped it. Second, the
     * course that was drawn instead begins at a lake's outflow, the one head the length rule
     * exempts. The walk here cannot see through a lake, because a lake is not a channel cell: it
     * starts the outflow's arm at the shore and scores the lake's own inflows as nothing, so beside
     * a short stub the outflow can look like the lesser arm when it is the only one on the map. An
     * excused course is left out of the coverage, and the number excused is printed.
     */
    @Test
    fun `a river is its own longest watercourse`() {
        var drawnTotal = 0.0
        var longestTotal = 0.0
        var beforeTotal = 0.0
        SEEDS.forEach { seed ->
            val network = Network(world(seed))
            val world = network.world
            val shortestDrawnKm = world.config.rivers.shortestDrawnCourseKm.toDouble()
            var drawnHere = 0.0
            var longestHere = 0.0
            var beforeHere = 0.0
            var courses = 0
            var excusedAsLakeOutflows = 0
            var worst = 1.0
            world.rivers.rivers.forEach { river ->
                val lastChannelAt = river.cells.indexOfLast { network.isChannel[it] }
                val last = river.cells[lastChannelAt]
                if (lastChannelAt == river.cells.size - 1) return@forEach // stops on another river
                var drawn = 0.0
                for (step in 0 until river.cells.size - 1) {
                    if (!network.isChannel[river.cells[step]]) continue
                    if (!network.isChannel[river.cells[step + 1]]) continue
                    drawn += network.stepKilometres(river.cells[step], river.cells[step + 1])
                }
                val longest = network.longestAbove[last]
                if (drawn < longest - ONE_STEP_OF_SLACK_KM) {
                    // What the tracer would have measured for the longest watercourse: the walk
                    // down to the last channel cell, and every step from there into the water.
                    var belowLastChannelKm = 0.0
                    for (step in lastChannelAt until river.cells.size - 1) {
                        belowLastChannelKm +=
                            network.stepKilometres(river.cells[step], river.cells[step + 1])
                    }
                    val longestCourseKm = longest + belowLastChannelKm
                    val head = river.cells.first()
                    assertTrue(
                        longestCourseKm < shortestDrawnKm && drainsALake(world, head),
                        "seed $seed: the course into cell $last is ${"%.3f".format(drawn)} km " +
                            "where the longest watercourse above it is ${"%.3f".format(longest)}" +
                            " (${"%.3f".format(longestCourseKm)} km to the water, against the " +
                            "drawn length ${"%.0f".format(shortestDrawnKm)}; head $head " +
                            "drains a lake: ${drainsALake(world, head)})"
                    )
                    excusedAsLakeOutflows++
                    return@forEach
                }
                courses++
                drawnHere += drawn
                longestHere += longest
                beforeHere += network.fromBiggestHead[last]
                if (longest <= 0.0) return@forEach
                worst = minOf(worst, drawn / longest)
            }
            drawnTotal += drawnHere
            longestTotal += longestHere
            beforeTotal += beforeHere
            println(
                ("RIVERCOURSE seed=$seed $courses courses into water, coverage %.3f (worst %.3f), " +
                    "%.3f from the biggest headwater; %d excused as lake outflows").format(
                    drawnHere / longestHere, worst, beforeHere / longestHere,
                    excusedAsLakeOutflows
                )
            )
        }
        val coverage = drawnTotal / longestTotal
        val before = beforeTotal / longestTotal
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
     * Whether [head] is a lake's outflow in the sense the tracer's length exemption means: some
     * neighbour of it is open water that drains into it. The same question `RiverStage` asks.
     */
    private fun drainsALake(world: WorldMap, head: Int): Boolean {
        val lakes = world.rivers.lakes
        val flowTarget = world.rivers.flowTarget
        var found = false
        FlowRouting.forEachNeighbour(
            world.width, world.height, head % world.width, head / world.width
        ) { neighbour ->
            if (lakes.isOpenWater(neighbour) && flowTarget[neighbour] == head) found = true
        }
        return found
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
            // line across. This is the thread the author saw, counted.
            //
            // A drawn *channel*, and I3 is why that word is now enforced rather than assumed. The
            // donor used to be asked only whether it was drawn, and a lake's open water is drawn —
            // it is the mouth a course ends on, added to the path and then stopped at. So a cell
            // taking its water straight out of a lake counted as having a drawn channel above it
            // when what is above it is the lake.
            //
            // I3's ice moved seed 7's ground and produced one: cell 191210, the single cell of
            // narrow water at the outflow end of a twelve-cell lake, with the lake's open water
            // above it and the trunk one cell below. No channel feeds it at all — its three donors
            // are two cells of open water and one carrying 0.88 of the source threshold's flow — so
            // nothing upstream has been severed, and there is no thread of standing water between
            // two thick channels, which is the whole of what this clause is about. What there is
            // instead is a lake's outflow that carries no line, because a cell with no channel
            // above it is a head, and the course from this head to the trunk it joins was two
            // cells against the eight `RiverConfig.minLengthCells` then asked for, so the tracer
            // discarded it as a stub. R1 replaced that count with a length in kilometres and gave
            // the ground a channel-head criterion of its own; whether an outflow carrying a whole
            // lake's catchment is now drawn is what this clause's `gaps` count says.
            val fedByADrawnChannel = BooleanArray(cells)
            for (donor in 0 until cells) {
                if (!drawn[donor] || !network.isChannel[donor]) continue
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

    /**
     * A course that ends in water has a reach on land to draw.
     *
     * The mouth cell is water — the sea, or a lake's open surface — and it is kept so the line
     * reaches the water rather than stopping a step short of it. What is drawn is therefore the
     * land above it, cut back half a stroke from the shore so the round cap is tangent to the
     * coast rather than sitting out on the water; `MapRasterizer.trimmedAtTheShore` is where that
     * happens. A course holding one cell of land and a mouth has nothing left once that cut is
     * made — there is no vertex above the last one on land to pull the end back to — so it is
     * drawn whole, and the stroke finishes at the centre of the water cell with a cap of half its
     * own width out there beside it.
     *
     * That is a fact about the course and not about the drawing, which is why it is asserted here:
     * a single cell of land between water above and water below is a rock the flow crosses, not a
     * watercourse, and no length of pen makes it one.
     */
    @Test
    fun `a course into water runs more than one cell on land`() {
        var intoWaterTotal = 0
        SEEDS.forEach { seed ->
            val world = world(seed)
            val lakes = world.rivers.lakes
            fun isWater(cell: Int) = !world.sea.isLand[cell] || lakes.isOpenWater(cell)

            var intoWater = 0
            var stubs = 0
            var firstStub = ""
            world.rivers.rivers.forEach { river ->
                val cells = river.cells
                if (!isWater(cells.last())) return@forEach // stops on another river
                intoWater++
                if (cells.size > 2) return@forEach
                stubs++
                if (firstStub.isEmpty()) firstStub = cells.joinToString(" -> ")
            }
            intoWaterTotal += intoWater
            println(
                "RIVERCOURSE seed=$seed $intoWater courses into water, $stubs of them one cell " +
                    "of land and a mouth"
            )
            assertTrue(
                stubs == 0,
                "seed $seed: $stubs courses into water hold one cell of land and a mouth, the " +
                    "first at $firstStub"
            )
        }
        assertTrue(
            intoWaterTotal > 0,
            "no course on any seed ends in water, so this guard has stopped discriminating"
        )
        println("RIVERCOURSE $intoWaterTotal courses into water, every one of them with a reach")
    }
}
