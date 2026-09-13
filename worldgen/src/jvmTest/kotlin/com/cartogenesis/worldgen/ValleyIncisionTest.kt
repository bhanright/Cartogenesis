package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
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
 *
 * The control is the *same courses* on the *same world's* un-eroded ground, and it has to be.
 * Until S2's third pass the control was a second world generated with `hydraulicRounds = 0`, which
 * traces its own rivers down whatever hollows its own noise left — so what the comparison measured
 * was partly how deep the noise's own hollows are, and that changed when the critical slope did.
 * At S1's 12 m/km the thermal sweeps planed the no-water world nearly flat and the control read
 * 0.028; at the 60 m/km the Andes' western flank measures over a cell's width they reach almost
 * nothing and it reads 0.042, against 0.047 with the water — a ratio of 1.11 where the same
 * landscape had been reading 1.7. Neither figure was about the rivers. Holding the courses fixed
 * and moving only the ground under them asks the question the class is named for: is this notch
 * one the water cut, or one it found? See `ErosionConfig.criticalFallMetresPerKm`.
 */
class ValleyIncisionTest {

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
        println("INCISION mean %.4f against %.4f, %.1fx".format(with, without, with / without))
        assertTrue(
            with > without * 1.5,
            "hydraulic erosion barely deepened the valleys: $with against $without"
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
        val world = WorldGenerationEngine.generateBlocking(config)
        val w = world.width
        val h = world.height
        val scale = world.config.scale
        val elevation = world.sea.relativeElevation.data
        val shoreline = world.sea.shorelineHeight
        val bareField = PlateStage.generate(config, TerrainStage.generate(config)).height.data
        // Onto the finished world's own shoreline, so a bank's height is a height above the
        // channel either way and not a difference of two rulers.
        val bare = FloatArray(bareField.size) {
            scale.reliefShareOfMetres(
                scale.altitudeAtField(bareField[it]) - scale.altitudeAtField(shoreline)
            )
        }

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
