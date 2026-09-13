package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether a depth contour is a line.
 *
 * On a slope it is: the floor crosses the level once, and the pixels near that crossing are a curve
 * a pixel or so wide. On an abyssal plain it is not. The floor there is level to within its own
 * roughness over a whole basin, so the level of any contour lying in it is crossed again and again,
 * and the drawing fills the open sea with a ragged nest of closed loops that say nothing. The first
 * render review of F13 caught it in the open basin of seed 718106 at 2048.
 *
 * The floor below is made rather than generated, for the same reason the cone in `ReliefShadingTest`
 * is: the defect is a property of flat ground, and a made floor has exactly as much flat ground as
 * the measurement needs, at any resolution, in a tenth of a second. Half of it is a continental
 * slope falling through nine contours; half is a basin filled level at the depth of one of them.
 * What is measured is how much of each half takes ink, and the control is the same drawing without
 * the flatness rule.
 */
class IsobathTest {

    private companion object {

        /** Side of the made floor, and where the slope gives way to the plain. */
        const val SIDE = 512
        const val SLOPE_COLUMNS = SIDE / 2

        /**
         * The plain's depth, and how far its floor rises toward the margins of the basin.
         *
         * It sits exactly on a contour, which is the worst case and the common one: an abyssal
         * plain is a basin filled level by what settles into it, so its floor is at one depth over
         * hundreds of kilometres and some plain is always near some level. The rise is 12 m at the
         * generator's default 6,000 m range and it goes as the fourth power of the distance from
         * the middle, which is the shape of a filled basin — dead level in the centre, turning up
         * at the edges.
         */
        const val PLAIN_DEPTH = 0.5f
        const val PLAIN_RIPPLE = 0.002f

        /**
         * How rough the plain is from one cell to the next, on top of that wander.
         *
         * Two metres at the default range, and it is the whole of the defect: a generated sea floor
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
         * Nine contours a pixel and a half wide across 256 columns is about 5% of that half, so the
         * bar is 3%.
         */
        const val MIN_SLOPE_INKED = 0.03

        /** The world these figures are read against: the generator's own defaults. */
        val CONFIG = WorldGenConfig(seed = 1L, width = SIDE, height = SIDE)
    }

    /**
     * A sea floor with a slope on one side and a plain on the other.
     *
     * Depths are positive here, as [Isobaths.ink] takes them: 0 at the shore and 1 at the deepest
     * floor the field can hold.
     */
    private fun madeFloor(): FloatArray {
        val depth = FloatArray(SIDE * SIDE)
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                depth[y * SIDE + x] = if (x < SLOPE_COLUMNS) {
                    SLOPE_FROM + (SLOPE_TO - SLOPE_FROM) * x / SLOPE_COLUMNS
                } else {
                    // The floor of a filled basin: level in the middle, turning up at the margins,
                    // with a fine roughness over all of it. See [PLAIN_ROUGHNESS].
                    val acrossPlain =
                        (x - (SLOPE_COLUMNS + SIDE) / 2f) / ((SIDE - SLOPE_COLUMNS) / 2f)
                    val downPlain = (y - SIDE / 2f) / (SIDE / 2f)
                    val fromMiddle = maxOf(kotlin.math.abs(acrossPlain), kotlin.math.abs(downPlain))
                    val margin = fromMiddle * fromMiddle * fromMiddle * fromMiddle
                    PLAIN_DEPTH - PLAIN_RIPPLE * margin + PLAIN_ROUGHNESS * roughness(x, y)
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
    private fun roughness(x: Int, y: Int): Float =
        (sin(x * 1.1f + y * 0.7f) + sin(x * 0.6f - y * 1.3f)) * 0.5f

    /** The ink each cell of the made floor takes, with the flatness rule on or off. */
    private fun contourInk(depth: FloatArray, flatnessRule: Boolean): FloatArray {
        val interval = Isobaths.interval(CONFIG.climate.maxAltitudeMetres)
        val flattest = if (flatnessRule) Isobaths.flattestSlope(CONFIG, SIDE, SIDE) else 0f
        val reach = Isobaths.slopeStencil(SIDE)
        val span = 1f / (2f * reach)
        val ink = FloatArray(depth.size)
        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                val eastward = (sample(depth, x + reach, y) - sample(depth, x - reach, y)) * span
                val southward = (sample(depth, x, y + reach) - sample(depth, x, y - reach)) * span
                val slope = sqrt(eastward * eastward + southward * southward)
                ink[y * SIDE + x] = Isobaths.ink(depth[y * SIDE + x], slope, interval, flattest)
            }
        }
        return ink
    }

    private fun sample(depth: FloatArray, x: Int, y: Int): Float =
        depth[y.coerceIn(0, SIDE - 1) * SIDE + ((x % SIDE) + SIDE) % SIDE]

    /**
     * How much of one half of the floor took ink, and the largest connected patch of it.
     *
     * [from] and [until] bound the columns, so the slope and the plain are measured apart: the
     * whole point is that one of them should be drawn on and the other should not.
     */
    private class Patch(ink: FloatArray, val from: Int, val until: Int) {
        val cells = (until - from) * SIDE
        val inked = (0 until SIDE).sumOf { y ->
            (from until until).count { x -> ink[y * SIDE + x] >= INKED }
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
                    val x = cell % SIDE
                    val y = cell / SIDE
                    val neighbours = intArrayOf(
                        y * SIDE + (x + 1) % SIDE,
                        y * SIDE + (x + SIDE - 1) % SIDE,
                        if (y + 1 < SIDE) (y + 1) * SIDE + x else cell,
                        if (y > 0) (y - 1) * SIDE + x else cell
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

    /** And that the slope half keeps every contour the interval puts on it. */
    @Test
    fun `the slope keeps its contours`() {
        val floor = madeFloor()
        val ink = contourInk(floor, flatnessRule = true)
        val interval = Isobaths.interval(CONFIG.climate.maxAltitudeMetres)
        val expected = ((SLOPE_TO - SLOPE_FROM) / interval).toInt()

        // One row across the slope, counting the runs of ink it crosses.
        var lines = 0
        var inside = false
        for (x in 0 until SLOPE_COLUMNS) {
            val wet = ink[(SIDE / 2) * SIDE + x] >= INKED
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
