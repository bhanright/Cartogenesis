package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.LakePockets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Fill, spill and merge over a basin's pockets, on ground laid by hand.
 *
 * Two hollows under one brim, a saddle between them: the arrangement Bonneville left, where Utah
 * Lake still spills into Great Salt Lake down the Jordan River and the two stand at different
 * levels. What is checked is the physics the solve promises: a pocket balances its own supply
 * against its own water, a full pocket passes on only its surplus, two pockets become one surface
 * only when both stand at their saddle, and a level is where a lake filling from empty first stops,
 * which on ground whose evaporation varies is not where a bisection lands.
 */
class LakePocketsTest {

    /**
     * A strip of ground three rows deep across [profile]'s columns, with a wall of cells outside
     * the basin either side of it. Each row stands a hair higher than the one above, so no two
     * cells tie.
     */
    private class Strip(profile: List<Float>, val rainMm: (Int) -> Float, val evaporationMm: (Int) -> Float) {
        val cellsAcross = profile.size + 2
        val cellsDown = 3
        val ground = FloatArray(cellsAcross * cellsDown) { cell ->
            val column = cell % cellsAcross
            if (column == 0 || column == cellsAcross - 1) 1f else profile[column - 1] + (cell / cellsAcross) * 1e-4f
        }
        val cells = (0 until cellsAcross * cellsDown).filter { val column = it % cellsAcross; column != 0 && column != cellsAcross - 1 }.toIntArray()
        val rain = FloatArray(cellsAcross * cellsDown) { rainMm(it % cellsAcross - 1) }
        val evaporation = FloatArray(cellsAcross * cellsDown) { evaporationMm(it % cellsAcross - 1) }
        val runoff = FloatArray(cellsAcross * cellsDown) { RUNOFF * rain[it] }

        fun pockets(brim: Float): LakePockets {
            val byGround = LongArray(cells.size) { FlowRouting.encode(ground[cells[it]], cells[it]) }
            byGround.sort()
            return LakePockets.build(
                cellsAcross, cellsDown, cells, byGround, ground, rain, evaporation, runoff, brim,
                IntArray(cellsAcross * cellsDown) { -1 }
            )
        }

        fun column(cell: Int) = cell % cellsAcross - 1
    }

    /** The leaf pocket whose floor lies in [column]. */
    private fun leafAt(pockets: LakePockets, strip: Strip, column: Int): Int =
        (0 until pockets.pocketCount).first { pocket ->
            pockets.isLeaf(pocket) && strip.column(pockets.layout[pockets.regionStart[pocket]]) == column
        }

    /** Hollow A in columns 0..4, floor at column 2; saddle at column 5; hollow B in 6..11, floor at 8 and 9. */
    private val twoHollows = listOf(0.30f, 0.20f, 0.10f, 0.20f, 0.30f, 0.40f, 0.35f, 0.25f, 0.05f, 0.06f, 0.25f, 0.45f)

    @Test
    fun `a wet pocket spills its surplus into a dry one and the two keep their own levels`() {
        // A's ground is wet and cold, B's dry and hot.
        val strip = Strip(twoHollows, { column -> if (column <= 5) 900f else 20f }, { column -> if (column <= 5) 100f else 1800f })
        val pockets = strip.pockets(brim = 0.45f)
        val hollowA = leafAt(pockets, strip, 2)
        val hollowB = leafAt(pockets, strip, 8)
        val supply = DoubleArray(pockets.pocketCount)
        supply[hollowA] = 3000.0
        supply[hollowB] = 200.0
        val water = pockets.solve(supply)

        assertTrue(water.full[hollowA], "the wet pocket should fill to its saddle")
        assertFalse(water.full[hollowB], "the dry pocket should stand below its saddle")
        val joined = pockets.parent[hollowA]
        assertEquals(joined, pockets.parent[hollowB])
        assertFalse(water.merged[joined], "two pockets are one surface only when both reach the saddle")
        assertFalse(water.rootFull)

        // Conservation. What reaches B is its own supply and A's surplus, no more: A's surplus is
        // A's supply plus what its water gains, and B's lake stops at the last cell that surplus
        // and B's own supply can pay for.
        fun gainOver(cells: IntRange): Double = cells.sumOf { index ->
            val cell = pockets.layout[index]
            (strip.rain[cell] - strip.runoff[cell]).toDouble() - strip.evaporation[cell]
        }
        val surplusA = supply[hollowA] + gainOver(pockets.regionStart[hollowA] until pockets.regionEnd[hollowA])
        val underB = water.ownCellsUnderWater[hollowB]
        val startB = pockets.ownStart[hollowB]
        val paid = supply[hollowB] + surplusA + gainOver(startB until startB + underB)
        val oneMore = paid + gainOver(startB + underB until startB + underB + 1)
        println("POCKETS spill: A full with a surplus of %.0f mm-cells, B holds %d of %d cells, %.0f left, %.0f one cell more"
            .format(surplusA, underB, pockets.regionEnd[hollowB] - startB, paid, oneMore))
        assertTrue(underB in 1 until pockets.regionEnd[hollowB] - startB, "B should hold a lake below its saddle")
        assertTrue(paid >= 0.0 && oneMore < 0.0, "B's level is where its supply and A's surplus stop paying: $paid then $oneMore")
        assertTrue(
            pockets.groundOfLaidOut(startB + underB - 1) < pockets.topLevel(hollowA),
            "B's water stands below A's"
        )
    }

    @Test
    fun `two full pockets become one surface above their saddle`() {
        val strip = Strip(twoHollows, { 900f }, { 100f })
        val pockets = strip.pockets(brim = 0.45f)
        val hollowA = leafAt(pockets, strip, 2)
        val hollowB = leafAt(pockets, strip, 8)
        val supply = DoubleArray(pockets.pocketCount)
        supply[hollowA] = 50.0
        supply[hollowB] = 50.0
        val water = pockets.solve(supply)
        val joined = pockets.parent[hollowA]
        assertTrue(water.full[hollowA] && water.full[hollowB], "both pockets fill")
        assertTrue(water.merged[joined], "both at the saddle: one surface from there up")
        assertTrue(water.rootFull, "the joined lake gains everywhere, so it reaches the brim")
    }

    @Test
    fun `a lake stops at the first level it cannot pay for, not wherever a bisection lands`() {
        // One hollow whose floor is hot, whose middle slopes are frozen and whose rim is hot again:
        // flooding the floor costs water, flooding the slopes above it gains it back, and the rim
        // costs it again, so the gain falls through zero, climbs back over it and falls again. A
        // lake filling from empty stops at the first crossing; a bisection lands on the last.
        val profile = listOf(0.90f, 0.80f, 0.70f, 0.60f, 0.50f, 0.40f, 0.30f, 0.20f, 0.10f, 0.05f, 0.15f, 0.95f)
        val hot = setOf(0, 1, 8, 9, 11)
        val strip = Strip(profile, { column -> if (column in hot) 0f else 1500f }, { column -> if (column in hot) 2000f else 0f })
        val pockets = strip.pockets(brim = 0.95f)
        assertEquals(1, pockets.pocketCount, "one hollow")
        val supply = DoubleArray(1) { 7000.0 }
        val water = pockets.solve(supply)

        val net = DoubleArray(pockets.layout.size) { index ->
            val cell = pockets.layout[index]
            (strip.rain[cell] - strip.runoff[cell]).toDouble() - strip.evaporation[cell]
        }
        var running = supply[0]
        var firstStop = net.size
        for (index in net.indices) {
            running += net[index]
            if (running < 0.0) { firstStop = index; break }
        }
        // The bisection the balance used before, on the same gains.
        fun gain(submerged: Int) = supply[0] + (0 until submerged).sumOf { net[it] }
        var balances = 1
        var doesNot = net.size
        while (balances + 1 < doesNot) {
            val middle = (balances + doesNot) / 2
            if (gain(middle) >= 0.0) balances = middle else doesNot = middle
        }
        println("POCKETS non-monotone: first stop %d cells, the bisection %d, the solve %d; gain at the end %.0f"
            .format(firstStop, balances, water.ownCellsUnderWater[0], gain(net.size)))
        assertTrue(gain(net.size) < 0.0, "the case needs a basin that does not fill")
        assertNotEquals(firstStop, balances, "the case must be one a bisection gets wrong")
        assertEquals(firstStop, water.ownCellsUnderWater[0], "the level is the first place the lake stops paying")
    }

    @Test
    fun `equal ground joins in one order on every run`() {
        val strip = Strip(List(12) { 0.2f }, { 100f }, { 900f })
        val first = strip.pockets(brim = 0.2f)
        val second = strip.pockets(brim = 0.2f)
        assertTrue(first.layout.contentEquals(second.layout))
        assertTrue(first.parent.contentEquals(second.parent))
        val supply = DoubleArray(first.pocketCount) { if (first.isLeaf(it)) 40.0 else 0.0 }
        assertTrue(first.solve(supply).ownCellsUnderWater.contentEquals(second.solve(supply).ownCellsUnderWater))
    }

    private companion object {
        const val RUNOFF = 0.35f
    }
}
