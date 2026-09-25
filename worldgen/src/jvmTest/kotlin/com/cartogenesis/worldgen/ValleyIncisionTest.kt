package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether rivers run in valleys they cut, or merely in whatever hollows the noise left.
 *
 * The measure is the cross-section. For every point on every drawn river, look at the ground a few
 * cell widths away *across* the flow and ask how much higher it stands. A river that carved its own
 * valley sits in a notch and the answer is clearly positive; a river that simply found the lowest
 * line across noise-shaped terrain sits barely below its surroundings.
 *
 * Across on the ground and as far on the ground whichever way the river runs, since Fix 2 put the
 * land on the ground's ruler. The banks were read three cells across the flow, which is three rows
 * for a river running east-west and so half as far on the ground as for one running north-south;
 * on land isotropic in cells that was the same share of a valley either way, and on land isotropic
 * on the ground it reads an east-west valley's banks halfway up its walls. Read in cells, the
 * finished channels on the ground's ruler stood 0.0129 below their banks on the three seeds and the
 * ground before the water 0.0037; read on the ground, 0.0127 and 0.0037.
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
class ValleyIncisionTest : BorrowsSharedWorlds() {

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
                    .format(seed, pair.eroded, pair.bare) + " on the ground before the water ran;" +
                    " by the course's step, ${pair.byStep}"
            )
        }

        val with = withTotal / seeds.size
        val without = withoutTotal / seeds.size
        println("INCISION mean %.4f against %.4f, %.2fx".format(with, without, with / without))
        // Recorded since Fix 3: see [CAP_SETS_EVERY_CUT].
        KnownFailures.expect(CAP_SETS_EVERY_CUT, "1.68x") {
            if (with <= without * DEEPENING_RATIO_BAR) {
                throw RecordedViolation(
                    "hydraulic erosion barely deepened the valleys: $with against $without",
                    String.format(Locale.ROOT, "%.2fx", with / without)
                )
            }
        }
        // Under Audit III's B-D1 since the erosion was put on the ground's ruler: see
        // [INCISION_CAPPED_PER_STEP].
        KnownFailures.expect(INCISION_CAPPED_PER_STEP, "0.0077") {
            if (with < NOTCH_DEPTH_BEFORE_S2 * NOTCH_DEPTH_ALLOWANCE) {
                throw RecordedViolation(
                    "a finished channel stands ${"%.4f".format(with)} of the field below its banks," +
                        " more than a tenth shallower than the" +
                        " ${"%.4f".format(NOTCH_DEPTH_BEFORE_S2)} the tree before S2 cut",
                    String.format(java.util.Locale.ROOT, "%.4f", with)
                )
            }
        }
    }

    /**
     * What the banks stand above the channel, on the eroded ground and on the ground before it, and
     * the finished notch by which way the course steps there, for the report.
     */
    private class Cross(val eroded: Double, val bare: Double, val byStep: String = "")

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
        val rowScale = config.cellHeightInCellWidths
        // Along a row, down a column, on a diagonal: the notch by the course's own step.
        val stepDepth = DoubleArray(3)
        val stepSamples = IntArray(3)

        world.rivers.rivers.forEach { river ->
            for (k in 1 until river.cells.size - 1) {
                val here = river.cells[k]
                val next = river.cells[k + 1]
                val x = here % w
                val y = here / w

                // The flow direction, and the axis across it on the ground: the flow's step in
                // cell widths is (dx, dy r), so across it is (-dy r, dx), taken out to the reach
                // and turned back into columns and rows.
                val dx = signOf(next % w - x, w)
                val dy = (next / w) - y
                if (dx == 0 && dy == 0) continue
                val acrossEast = -dy * rowScale
                val acrossSouth = dx.toDouble()
                val acrossLength = kotlin.math.sqrt(acrossEast * acrossEast + acrossSouth * acrossSouth)
                val acrossColumns = kotlin.math.round(acrossEast / acrossLength * BANK_REACH_CELL_WIDTHS).toInt()
                val acrossRows = kotlin.math.round(acrossSouth / acrossLength * BANK_REACH_CELL_WIDTHS / rowScale).toInt()

                var erodedBanks = 0f
                var bareBanks = 0f
                var found = 0
                for (side in intArrayOf(-1, 1)) {
                    val bx = ((x + acrossColumns * side) % w + w) % w
                    val by = y + acrossRows * side
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
                val step = if (dy == 0) 0 else if (dx == 0) 1 else 2
                stepDepth[step] += (erodedBanks / found).toDouble()
                stepSamples[step]++
            }
        }
        if (samples == 0) return Cross(0.0, 0.0)
        val byStep = listOf("along a row", "down a column", "on a diagonal").indices.joinToString { step ->
            "%s %.4f over %d".format(
                listOf("along a row", "down a column", "on a diagonal")[step],
                if (stepSamples[step] == 0) 0.0 else stepDepth[step] / stepSamples[step],
                stepSamples[step]
            )
        }
        return Cross(erodedTotal / samples, bareTotal / samples, byStep)
    }

    private companion object {
        /**
         * The known failure the clauses Fix 3 moved record: with the incision's caps spent in the
         * height field's own unit, the cap at half the drop sets the cut on every drawn channel, so
         * the rounds cut less than the stream-power law asks and the explicit update, not the law,
         * shapes the channels. The implicit solver's chunk is where it is next taken up; see
         * docs/DESIGN_LEDGER.md, Fix 3.
         */
        const val CAP_SETS_EVERY_CUT =
            "the erosion: with its caps in one unit the half-the-drop cap sets every drawn channel's cut, and the explicit incision cuts less than the stream-power law asks"

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
         *
         * All three are regression pins on the tree's own measurement — main's figure at the S2
         * merge, less an allowance — and not figures of Earth's; they catch a change that shallows
         * the valleys, not a valley that is the wrong depth.
         */
        const val DEEPENING_RATIO_BAR = 1.9
        const val NOTCH_DEPTH_BEFORE_S2 = 0.0148
        const val NOTCH_DEPTH_ALLOWANCE = 0.9

        /**
         * How far across the flow the banks are read, in cell widths of ground: the three cells the
         * class has always read, which were three cell widths for a river running north-south and
         * are now that for every river.
         */
        const val BANK_REACH_CELL_WIDTHS = 3.0

        /**
         * The known failure the depth clause records, and the finding it is: `GroundIsotropyTest`
         * records the same cap under the same name.
         *
         * The tree before Fix 2 cut its channels to 0.0158 on these seeds, read in cells on land
         * isotropic in cells. On the ground's ruler they stand 0.0127 read on the ground, and the
         * shortfall is in the courses that step down a column: where a river steps along a row its
         * notch is 0.0152, 0.0172 and 0.0119 deep on seeds 7, 42 and 1234, on a diagonal 0.0136,
         * 0.0162 and 0.0115, and down a column 0.0104, 0.0129 and 0.0091, a quarter to a third
         * shallower than along a row. The incision is capped at half the drop to the receiver in a
         * round, a drop is in proportion to the step, and a step down a column is half as long on
         * the ground, so where the cap and not the law sets the cut a course running north-south is
         * cut half as far a round. The erosion's units, which set how often the cap binds, are the
         * next chunk's (docs/DESIGN_LEDGER.md, Fix 2).
         */
        const val INCISION_CAPPED_PER_STEP =
            "Audit III B-D1: the incision's cap sets the cut, and a cap per step cuts a north-south channel half as deep a round"
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
