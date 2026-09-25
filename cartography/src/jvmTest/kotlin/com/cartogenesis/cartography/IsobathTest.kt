package com.cartogenesis.cartography

import com.cartogenesis.cartography.geometry.KnownFailures
import com.cartogenesis.cartography.geometry.RecordedViolation
import com.cartogenesis.worldgen.BorrowsSharedWorlds
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether a depth contour is a line.
 *
 * On a slope it is: the floor crosses the level once, and the pixels near that crossing are a curve
 * a pixel or so wide. On an abyssal plain it is not. The floor there is level to within its own
 * roughness over a whole basin, so the level of any contour lying in it is crossed again and again,
 * and the drawing fills the open sea with a ragged nest of closed loops that say nothing. A render
 * review caught it in an open basin at 2048; see docs/DESIGN_LEDGER.md, F13.
 *
 * The floor below is made rather than generated, for the same reason the cone in `ReliefShadingTest`
 * is: the defect is a property of flat ground, and a made floor has exactly as much flat ground as
 * the measurement needs, at any resolution, in a tenth of a second. Half of it is a continental
 * slope falling through sixteen contours; half is a basin filled level at the depth of one of them.
 * What is measured is how much of each half takes ink, and the control is the same drawing without
 * the flatness rule.
 */
class IsobathTest : BorrowsSharedWorlds() {

    private companion object {

        /** Side of the made floor, and where the slope gives way to the plain. */
        const val SIDE = 512
        const val SLOPE_COLUMNS = SIDE / 2

        /**
         * The plain's depth, and how far its floor rises toward the margins of the basin.
         *
         * It sits exactly on a contour, which is the worst case and the common one: an abyssal
         * plain is a basin filled level by what settles into it, so its floor is at one depth over
         * hundreds of kilometres and some plain is always near some level. The rise is 20 m on the
         * sea's default 10,000 m ruler and it goes as the fourth power of the distance from
         * the middle, which is the shape of a filled basin — dead level in the centre, turning up
         * at the edges.
         */
        const val PLAIN_DEPTH = 0.5f
        const val PLAIN_RIPPLE = 0.002f

        /**
         * How rough the plain is from one cell to the next, on top of that wander.
         *
         * Four metres on the sea's ruler, and it is the whole of the defect: a generated sea floor
         * is rough at cell scale, so the gradient measured between two neighbours is that roughness
         * rather than the floor's own fall. A line whose width in depth is set by that gradient is
         * then far wider in depth than the floor's fall over the same distance, and every pixel of
         * a wandering plain that comes within it takes ink.
         */
        const val PLAIN_ROUGHNESS = 0.0004f

        /** The slope half falls from the shelf to the abyss across its own width. */
        const val SLOPE_FROM = 0.1f
        const val SLOPE_TO = 0.9f

        /** Where a pixel counts as inked, on the 0-to-1 scale [Isobaths.ink] returns. */
        const val INKED = 0.5f

        /**
         * How much of a plain may take contour ink, as a share of it.
         *
         * A fiftieth, which is a line or two strayed in from the margins rather than the nest the
         * control draws. Nothing at all would be too strict a bar for a rule that fades rather than
         * cuts: the fade has to cross the gradient somewhere, and the pixels it crosses at are on
         * the plain by this measure.
         */
        const val MAX_PLAIN_INKED = 0.02

        /**
         * And how much of the slope must keep it, so the rule cannot pass by drawing nothing.
         *
         * The slope falls through 0.8 of the sea's ruler, sixteen contours at the interval's 500 m
         * of its 10,000 m, and sixteen lines a pixel and a half wide across 256 columns are about
         * 9% of that half. The bar is a third of that, so the crowding fade may take the most
         * tightly packed of them and the clause still fails on a rule that took the drawing whole.
         */
        const val MIN_SLOPE_INKED = 0.03

        /** The world these figures are read against: the generator's own defaults. */
        val CONFIG = WorldGenConfig(seed = 1L, width = SIDE, height = SIDE)

        /**
         * A generated world at the same grid, where the raster's own contour arithmetic is read:
         * the default settings, as the gallery and the census use them.
         */
        val WORLD_CONFIG = WorldGenConfig(seed = 42L, width = SIDE, height = SIDE)

        /**
         * Contour ink a reader sees, on [Isobaths.ink]'s 0-to-1 scale: a tenth of a line's full
         * weight, the figure the audit sized the shoreline's doubling at.
         */
        const val VISIBLE_INK = 0.1f

        /**
         * The two made planes of the bearing clause, as shares of the plain's gradient: under the
         * fade's lower end, where the rule takes the line whole, and a tenth over its upper end,
         * where it leaves the line alone.
         */
        const val UNDER_THE_PLAIN = 0.45
        const val OVER_THE_PLAIN = 1.1

        /** The known failure the shoreline clause records. */
        const val SHORELINE_CONTOURED =
            "Audit III F-C4: the level-0 contour inks the sea beside the coast, doubling the coastline"
    }

    /**
     * A sea floor with a slope on one side and a plain on the other.
     *
     * Depths are positive here, as [Isobaths.ink] takes them: 0 at the shore and 1 at the deepest
     * floor the field can hold.
     */
    private fun madeFloor(): FloatArray {
        val depth = FloatArray(SIDE * SIDE)
        for (row in 0 until SIDE) {
            for (column in 0 until SIDE) {
                depth[row * SIDE + column] = if (column < SLOPE_COLUMNS) {
                    SLOPE_FROM + (SLOPE_TO - SLOPE_FROM) * column / SLOPE_COLUMNS
                } else {
                    // The floor of a filled basin: level in the middle, turning up at the margins,
                    // with a fine roughness over all of it. See [PLAIN_ROUGHNESS].
                    val acrossPlain =
                        (column - (SLOPE_COLUMNS + SIDE) / 2f) / ((SIDE - SLOPE_COLUMNS) / 2f)
                    val downPlain = (row - SIDE / 2f) / (SIDE / 2f)
                    val fromMiddle = maxOf(kotlin.math.abs(acrossPlain), kotlin.math.abs(downPlain))
                    // To the fourth power: dead level in the centre, turning up at the edges.
                    val margin = fromMiddle * fromMiddle * fromMiddle * fromMiddle
                    PLAIN_DEPTH - PLAIN_RIPPLE * margin +
                        PLAIN_ROUGHNESS * roughness(column, row)
                }
            }
        }
        return depth
    }

    /**
     * Roughness in -1..1 at a cell: two short waves crossing.
     *
     * Smooth over a few cells rather than white, because a generated sea floor is — and because
     * that is what makes the defect a solid patch instead of a speckle. Deterministic, so the
     * figures below are the same every run.
     */
    private fun roughness(column: Int, row: Int): Float =
        (sin(column * 1.1f + row * 0.7f) + sin(column * 0.6f - row * 1.3f)) * 0.5f

    /**
     * The ink each cell of the made floor takes, with the flatness rule on or off.
     *
     * A copy of the raster's arithmetic, since the made floor is not a world the raster can be
     * handed; `the made floor is measured the way the raster measures a world` holds the copy to
     * the raster's own [MapRasterizer.seaContour] on every sea cell of a generated world.
     */
    private fun contourInk(depth: FloatArray, flatnessRule: Boolean): FloatArray {
        val interval = Isobaths.interval(CONFIG.scale)
        val flattest = if (flatnessRule) Isobaths.flattestSlope(CONFIG, SIDE, SIDE) else 0f
        val reach = Isobaths.slopeStencil(SIDE)
        val span = 1f / (2f * reach)
        val rowScale = CONFIG.cellHeightInCellWidths.toFloat()
        val ink = FloatArray(depth.size)
        for (row in 0 until SIDE) {
            for (column in 0 until SIDE) {
                val cell = row * SIDE + column
                val eastward =
                    (sample(depth, column + reach, row) -
                        sample(depth, column - reach, row)) * span
                val southward =
                    (sample(depth, column, row + reach) -
                        sample(depth, column, row - reach)) * span
                val slope = sqrt(eastward * eastward + southward * southward)
                val southwardOnTheGround = southward / rowScale
                val slopeOnTheGround = sqrt(eastward * eastward + southwardOnTheGround * southwardOnTheGround)
                ink[cell] = Isobaths.ink(depth[cell], slope, slopeOnTheGround, interval, flattest)
            }
        }
        return ink
    }

    /** Clamped north and south, wrapped east and west, exactly as the raster samples a field. */
    private fun sample(depth: FloatArray, column: Int, row: Int): Float =
        depth[row.coerceIn(0, SIDE - 1) * SIDE + ((column % SIDE) + SIDE) % SIDE]

    /**
     * How much of one half of the floor took ink, and the largest connected patch of it.
     *
     * [from] and [until] bound the columns, so the slope and the plain are measured apart: the
     * whole point is that one of them should be drawn on and the other should not.
     */
    private class Patch(ink: FloatArray, val from: Int, val until: Int) {
        val cells = (until - from) * SIDE
        val inked = (0 until SIDE).sumOf { row ->
            (from until until).count { column -> ink[row * SIDE + column] >= INKED }
        }
        val largest: Int

        init {
            val seen = BooleanArray(ink.size)
            val stack = ArrayDeque<Int>()
            var biggest = 0
            for (start in ink.indices) {
                if (seen[start] || ink[start] < INKED) continue
                var size = 0
                stack.addLast(start)
                seen[start] = true
                while (stack.isNotEmpty()) {
                    val cell = stack.removeLast()
                    size++
                    val column = cell % SIDE
                    val row = cell / SIDE
                    val neighbours = intArrayOf(
                        row * SIDE + (column + 1) % SIDE,
                        row * SIDE + (column + SIDE - 1) % SIDE,
                        if (row + 1 < SIDE) (row + 1) * SIDE + column else cell,
                        if (row > 0) (row - 1) * SIDE + column else cell
                    )
                    for (next in neighbours) {
                        if (next == cell || seen[next] || ink[next] < INKED) continue
                        seen[next] = true
                        stack.addLast(next)
                    }
                }
                if (size > biggest) biggest = size
            }
            largest = biggest
        }

        val inkedShare: Double get() = inked.toDouble() / cells

        override fun toString(): String =
            "%.2f%% inked, largest patch %d cells".format(inkedShare * 100, largest)
    }

    @Test
    fun `a contour is a line on a slope and is not drawn on a plain`() {
        val floor = madeFloor()
        val withRule = contourInk(floor, flatnessRule = true)
        val without = contourInk(floor, flatnessRule = false)
        val plain = Patch(withRule, SLOPE_COLUMNS, SIDE)
        val plainBefore = Patch(without, SLOPE_COLUMNS, SIDE)
        val slope = Patch(withRule, 0, SLOPE_COLUMNS)
        val slopeBefore = Patch(without, 0, SLOPE_COLUMNS)

        println("ISOBATH the plain, with the flatness rule: $plain; without it: $plainBefore")
        println("ISOBATH the slope, with it: $slope; without it: $slopeBefore")
        println(
            "ISOBATH a plain is flatter than 1:%.0f, which on this grid is %.5f of the field a pixel"
                .format(
                    1.0 / Isobaths.ABYSSAL_PLAIN_GRADIENT,
                    Isobaths.flattestSlope(CONFIG, SIDE, SIDE)
                )
        )

        assertTrue(
            plainBefore.inkedShare > MAX_PLAIN_INKED,
            "without the flatness rule only ${"%.2f".format(plainBefore.inkedShare * 100)}% of " +
                "the plain is drawn on, so this guard proves nothing"
        )
        assertTrue(
            plain.inkedShare <= MAX_PLAIN_INKED,
            "${"%.2f".format(plain.inkedShare * 100)}% of the plain is drawn on, past " +
                "${MAX_PLAIN_INKED * 100}% — those lines are the floor's roughness, not its shape"
        )
        assertTrue(
            slope.inkedShare >= MIN_SLOPE_INKED,
            "only ${"%.2f".format(slope.inkedShare * 100)}% of the slope kept its contours, short " +
                "of ${MIN_SLOPE_INKED * 100}% — the rule has taken the drawing with it"
        )
    }

    /**
     * An abyssal plain is a gradient on the ground, whichever way the floor falls.
     *
     * `Isobaths.ABYSSAL_PLAIN_GRADIENT` is a metre of fall a kilometre, and a floor falling that
     * gently is a plain whether it falls north or east. A raster that measured the fall per pixel
     * and converted the rule with one cell side for both axes read a floor falling north at 0.71 of
     * its gradient and one falling east at 1.41 on this map's cells (Audit III's F-R5), so the same
     * ground was a plain one way and a slope the other.
     *
     * Planes at four bearings on the ground, each through a contour at its middle: at under half
     * the plain's gradient the rule leaves no ink at all, and at a tenth over it the rule leaves the
     * line exactly as the drawing without the rule draws it.
     */
    @Test
    fun `the plain is a gradient on the ground whichever way the floor falls`() {
        val rowScale = CONFIG.cellHeightInCellWidths
        val interval = Isobaths.interval(CONFIG.scale)
        val flattest = Isobaths.flattestSlope(CONFIG, SIDE, SIDE)
        val stencil = Isobaths.slopeStencil(SIDE)
        val plainPerCellWidth = CONFIG.scale.depthShareOfMetres(
            (Isobaths.ABYSSAL_PLAIN_GRADIENT * CONFIG.cellWidthKm * 1000.0).toFloat()
        )
        val failures = ArrayList<String>()
        for (degrees in listOf(0.0, 90.0, 45.0, 135.0)) {
            val falls = degrees * kotlin.math.PI / 180.0
            for (share in listOf(UNDER_THE_PLAIN, OVER_THE_PLAIN)) {
                val depth = FloatArray(SIDE * SIDE) { cell ->
                    val column = cell % SIDE - SIDE / 2
                    val row = cell / SIDE - SIDE / 2
                    (PLAIN_DEPTH + share * plainPerCellWidth *
                        (column * kotlin.math.cos(falls) + row * rowScale * kotlin.math.sin(falls))).toFloat()
                }
                val floor = com.cartogenesis.worldgen.model.FloatField(SIDE, SIDE, FloatArray(depth.size) { -depth[it] })
                var inkedWithRule = 0
                var differing = 0
                for (row in SIDE / 4 until SIDE * 3 / 4) {
                    for (column in SIDE / 4 until SIDE * 3 / 4) {
                        val cell = row * SIDE + column
                        val withRule = MapRasterizer.seaContour(floor, rowScale, cell, depth[cell], interval, flattest, stencil)
                        val without = MapRasterizer.seaContour(floor, rowScale, cell, depth[cell], interval, 0f, stencil)
                        if (withRule >= VISIBLE_INK) inkedWithRule++
                        if (withRule != without) differing++
                    }
                }
                val verdict = if (share == UNDER_THE_PLAIN) inkedWithRule == 0 else differing == 0
                println(
                    "ISOBATH a floor falling at %5.1f degrees on the ground at %.2f of the plain's gradient: %d cells inked, %d drawn otherwise than without the rule"
                        .format(degrees, share, inkedWithRule, differing)
                )
                if (!verdict) {
                    failures += "falling at $degrees degrees, ${share}x the plain's gradient: $inkedWithRule inked, $differing changed by the rule"
                }
            }
        }
        assertTrue(failures.isEmpty(), "the plain rule reads the same ground differently by bearing: $failures")
    }

    /**
     * The copy above, held to the raster: on every sea cell of a generated world, the ink
     * [contourInk] works out from the world's depths is the ink [MapRasterizer.seaContour] works
     * out, to the bit. The made floor's figures are the raster's figures only while this holds.
     */
    @Test
    fun `the made floor is measured the way the raster measures a world`() {
        val world = SharedWorlds.world(WORLD_CONFIG)
        assertEquals(SIDE, world.width)
        val relative = world.sea.relativeElevation.data
        val depth = FloatArray(relative.size) { -relative[it] }
        val copied = contourInk(depth, flatnessRule = true)
        val interval = Isobaths.interval(world.config.scale)
        val flattest = Isobaths.flattestSlope(world.config, world.width, world.height)
        val stencil = Isobaths.slopeStencil(world.width)
        var compared = 0
        var inked = 0
        for (cell in relative.indices) {
            if (world.sea.isLand[cell]) continue
            val raster = MapRasterizer.seaContour(world, cell, depth[cell], interval, flattest, stencil)
            assertEquals(
                raster.toRawBits(), copied[cell].toRawBits(),
                "the copy draws $cell at ${copied[cell]} and the raster at $raster"
            )
            compared++
            if (raster >= INKED) inked++
        }
        println("ISOBATH the copy agrees with the raster on all $compared sea cells of seed ${WORLD_CONFIG.seed}, $inked of them inked")
        assertTrue(inked > 0, "no sea cell of the world took contour ink, so the agreement says nothing")
    }

    /**
     * No contour doubles the coastline: `Isobaths` says the shallowest line sits below the shelf
     * break. The made floor never reaches the shore, so this is read on a generated world through
     * the raster's own [MapRasterizer.seaContour]: every sea cell beside land, and how much contour
     * ink it takes. The level the ink belongs to there is the shoreline's own, depth 0, which the
     * arithmetic treats as a line like any other (Audit III, F-C4), so the clause runs as a known
     * failure under that finding.
     */
    @Test
    fun `no contour doubles the coastline`() {
        val world = SharedWorlds.world(WORLD_CONFIG)
        val relative = world.sea.relativeElevation.data
        val interval = Isobaths.interval(world.config.scale)
        val flattest = Isobaths.flattestSlope(world.config, world.width, world.height)
        val stencil = Isobaths.slopeStencil(world.width)
        val width = world.width
        val land = world.sea.isLand
        fun besideLand(cell: Int): Boolean {
            val column = cell % width
            val row = cell / width
            return land[row * width + (column + 1) % width] || land[row * width + (column + width - 1) % width] ||
                (row > 0 && land[cell - width]) || (row + 1 < world.height && land[cell + width])
        }
        var coastal = 0
        var doubled = 0
        var openSeaInked = 0
        for (cell in relative.indices) {
            if (land[cell]) continue
            val ink = MapRasterizer.seaContour(world, cell, -relative[cell], interval, flattest, stencil)
            if (besideLand(cell)) {
                coastal++
                if (ink > VISIBLE_INK) doubled++
            } else if (ink >= INKED) {
                openSeaInked++
            }
        }
        println("ISOBATH seed ${WORLD_CONFIG.seed}: $doubled of $coastal sea cells beside land take contour ink past $VISIBLE_INK; $openSeaInked cells of open sea are inked")
        assertTrue(openSeaInked > 0, "the world's open sea carries no contour, so the coast's cannot be told apart")
        KnownFailures.expect(SHORELINE_CONTOURED, "sea cells beside land take contour ink") {
            if (doubled > 0) {
                throw RecordedViolation(
                    "$doubled of $coastal sea cells beside land take contour ink past $VISIBLE_INK",
                    "sea cells beside land take contour ink"
                )
            }
        }
    }

    /** And that the slope half keeps every contour the interval puts on it. */
    @Test
    fun `the slope keeps its contours`() {
        val floor = madeFloor()
        val ink = contourInk(floor, flatnessRule = true)
        val interval = Isobaths.interval(CONFIG.scale)
        val expected = ((SLOPE_TO - SLOPE_FROM) / interval).toInt()

        // One row across the slope, counting the runs of ink it crosses.
        var lines = 0
        var inside = false
        for (column in 0 until SLOPE_COLUMNS) {
            val wet = ink[(SIDE / 2) * SIDE + column] >= INKED
            if (wet && !inside) lines++
            inside = wet
        }
        println("ISOBATH the slope carries $lines of the $expected contours the interval puts on it")
        assertTrue(
            lines >= expected - 1,
            "the slope carries only $lines contours where the interval puts $expected"
        )
    }
}
