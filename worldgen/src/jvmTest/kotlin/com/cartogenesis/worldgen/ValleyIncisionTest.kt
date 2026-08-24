package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether rivers run in valleys they cut, or merely in whatever hollows the noise left.
 *
 * The measure is the cross-section. For every point on every drawn river, look at the ground a few
 * cells away *across* the flow and ask how much higher it stands. A river that carved its own
 * valley sits in a notch and the answer is clearly positive; a river that simply found the lowest
 * line across noise-shaped terrain sits barely below its surroundings.
 *
 * Reported as a share of the elevation range so the number means the same at any resolution.
 */
class ValleyIncisionTest {

    @Test
    fun `rivers sit in valleys they cut`() {
        val seeds = listOf(7L, 42L, 1234L)
        var withTotal = 0.0
        var withoutTotal = 0.0

        seeds.forEach { seed ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            val eroded = incision(base)
            val bare = incision(
                base.copy(erosion = base.erosion.copy(hydraulicRounds = 0))
            )
            withTotal += eroded
            withoutTotal += bare
            println(
                "INCISION seed %d: %.4f of the elevation range, against %.4f with no water"
                    .format(seed, eroded, bare)
            )
        }

        val with = withTotal / seeds.size
        val without = withoutTotal / seeds.size
        println("INCISION mean %.4f against %.4f, %.1fx".format(with, without, with / without))
        assertTrue(
            with > without * 1.5,
            "hydraulic erosion barely deepened the valleys: $with against $without"
        )
    }

    /** Mean height of the banks above the channel, over every drawn river point. */
    private fun incision(config: WorldGenConfig): Double {
        val world = WorldGenerationEngine.generateBlocking(config)
        val w = world.width
        val h = world.height
        val elevation = world.sea.relativeElevation.data

        var total = 0.0
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

                var banks = 0f
                var found = 0
                for (side in intArrayOf(-1, 1)) {
                    val bx = ((x + acrossX * reach * side) % w + w) % w
                    val by = y + acrossY * reach * side
                    if (by < 0 || by >= h) continue
                    val b = by * w + bx
                    if (!world.sea.isLand[b]) continue
                    banks += elevation[b] - elevation[here]
                    found++
                }
                if (found == 0) continue
                total += banks / found
                samples++
            }
        }
        return if (samples == 0) 0.0 else total / samples
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
