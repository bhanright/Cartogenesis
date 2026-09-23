package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * Whether rivers run in valleys they cut, or merely in whatever hollows the noise left.
 *
 * The measure is the cross-section. For every point on every drawn river, look at the ground a few
 * cells away *across* the flow and ask how much higher it stands. A river that carved its own
 * valley sits in a notch and the answer is clearly positive; a river that simply found the lowest
 * line across noise-shaped terrain sits barely below its surroundings.
 *
 * Both sides are read in the height field's own units — the erosion stage's output against the
 * erosion stage's input, at the same cells, along the same courses. Until S2's third pass the
 * control was a second world generated with `hydraulicRounds = 0`, which traces its own rivers
 * down its own hollows, so what the comparison measured was partly how deep those hollows are.
 * Holding the courses fixed and moving only the ground under them asks the question the class is
 * named for: is this notch one the water cut, or one it found?
 *
 * Two clauses, because S2 moved the two halves in opposite directions and the ratio alone cannot
 * see it. Measured on seeds 7, 42 and 1234 at 512, `main` before S2 cut its channels to 0.0148 of
 * the field out of ground standing at 0.0075 — twice as deep as it found them. S2 cuts to 0.0186
 * out of ground standing at 0.0157, which is a quarter deeper a channel and only a fifth deeper
 * than it found it. Both figures are the same fact: the ground S2 hands the rivers is twice as
 * rough at this cross-section, because the base relief is shaped into a band at the scale a range
 * is read at and given an amplitude in metres, where before it was a `1/k` surface renormalised to
 * whatever the tallest cell of that world happened to be. So the ratio's bar comes down to 1.15
 * and a second clause holds the thing that actually matters — that the finished notch is at least
 * as deep as the pre-S2 tree cut. Whether the incision law is under-cutting on rougher ground is a
 * question for S3, and it is in `TODO.md`.
 */
class ValleyIncisionTest {

    @get:Rule
    val sharedWorlds = SharedWorlds.Check()

    @Test
    fun `rivers sit in valleys they cut`() {
        val seeds = listOf(7L, 42L, 1234L)
        var withTotal = 0.0
        var withoutTotal = 0.0

        seeds.forEach { seed ->
            // Glaciation off on both sides. It is the other stage that cuts valleys, it cuts them
            // along the same trunks, and it does not care whether the hydraulic rounds ran — so
            // left on it lands in the control as well as in the measurement and flatters the
            // control by more than it flatters the measurement. Measured with it on: 1.5x, against
            // 1.7x with it off, for no change in how much water moved.
            val base = WorldGenConfig(seed = seed, width = 512, height = 512).let {
                it.copy(glaciation = it.glaciation.copy(enabled = false))
            }
            val pair = incision(base)
            withTotal += pair.eroded
            withoutTotal += pair.bare
            println(
                "INCISION seed %d: %.4f of the elevation range along its own courses, against %.4f"
                    .format(seed, pair.eroded, pair.bare) + " on the ground before the water ran"
            )
        }

        val with = withTotal / seeds.size
        val without = withoutTotal / seeds.size
        println("INCISION mean %.4f against %.4f, %.2fx".format(with, without, with / without))
        assertTrue(
            with > without * DEEPENING_RATIO_BAR,
            "hydraulic erosion barely deepened the valleys: $with against $without"
        )
        assertTrue(
            with >= NOTCH_DEPTH_BEFORE_S2 * NOTCH_DEPTH_ALLOWANCE,
            "a finished channel stands ${"%.4f".format(with)} of the field below its banks," +
                " more than a tenth shallower than the" +
                " ${"%.4f".format(NOTCH_DEPTH_BEFORE_S2)} the tree before S2 cut"
        )
    }

    /** What the banks stand above the channel, on the eroded ground and on the ground before it. */
    private class Cross(val eroded: Double, val bare: Double)

    /**
     * Mean height of the banks above the channel over every drawn river point, measured twice: on
     * the world's own finished ground, and on the same world's ground before the hydraulic rounds
     * ran, along the same courses.
     *
     * The bare field is the plate stage's own output — the stamped, noised terrain the erosion
     * stage is handed — put on the same shoreline-relative ruler the finished world uses, so the
     * two numbers are the same measurement of the same places.
     */
    private fun incision(config: WorldGenConfig): Cross {
        val world = SharedWorlds.world(config)
        val w = world.width
        val h = world.height
        // Both sides in the height field's own units, which is what makes them comparable: the
        // erosion stage's output against the erosion stage's input, at the same cells.
        val elevation = world.erosion.height.data
        val bare = PlateStage.generate(config, TerrainStage.generate(config)).height.data

        var erodedTotal = 0.0
        var bareTotal = 0.0
        var samples = 0
        val reach = 3

        world.rivers.rivers.forEach { river ->
            for (k in 1 until river.cells.size - 1) {
                val here = river.cells[k]
                val next = river.cells[k + 1]
                val x = here % w
                val y = here / w

                // The flow direction, and the axis across it.
                val dx = signOf(next % w - x, w)
                val dy = (next / w) - y
                if (dx == 0 && dy == 0) continue
                val acrossX = -dy
                val acrossY = dx

                var erodedBanks = 0f
                var bareBanks = 0f
                var found = 0
                for (side in intArrayOf(-1, 1)) {
                    val bx = ((x + acrossX * reach * side) % w + w) % w
                    val by = y + acrossY * reach * side
                    if (by < 0 || by >= h) continue
                    val b = by * w + bx
                    if (!world.sea.isLand[b]) continue
                    erodedBanks += elevation[b] - elevation[here]
                    bareBanks += bare[b] - bare[here]
                    found++
                }
                if (found == 0) continue
                erodedTotal += erodedBanks / found
                bareTotal += bareBanks / found
                samples++
            }
        }
        if (samples == 0) return Cross(0.0, 0.0)
        return Cross(erodedTotal / samples, bareTotal / samples)
    }

    private companion object {
        /**
         * How much deeper the water must leave a channel than it found it, and how deep the
         * channel must end up, in units of the height field.
         *
         * Both taken on `main` as it stood when S2 merged into it, by exactly this arithmetic on
         * these three seeds: 0.0148 deep out of ground at 0.0075, which is 1.97 times.
         *
         * The ratio's bar was 1.15 for S2's second and third passes, because the ground they
         * handed the rivers was twice as rough at this cross-section and the ratio fell to 1.2
         * even as the channel deepened. The fourth pass gave the base relief a texture
         * proportional to the ground's own relief and the roughness went with it: the ground now
         * stands at 0.0069 against main's 0.0075 and the channel is cut to 0.0143, which is
         * **2.08 times** — deeper for its ground than the tree before S2 managed. So the ratio
         * goes back to main's own figure, less a little for the seeds' spread, and the absolute
         * depth keeps a tenth of slack: a notch is as deep as the ground it is cut into allows,
         * and this ground is 8% smoother.
         */
        const val DEEPENING_RATIO_BAR = 1.9
        const val NOTCH_DEPTH_BEFORE_S2 = 0.0148
        const val NOTCH_DEPTH_ALLOWANCE = 0.9
    }

    /** Column difference on a cylinder: a step across the seam is still one cell. */
    private fun signOf(delta: Int, width: Int): Int {
        val wrapped = when {
            delta > width / 2 -> delta - width
            delta < -width / 2 -> delta + width
            else -> delta
        }
        return if (wrapped == 0) 0 else if (wrapped > 0) 1 else -1
    }
}
