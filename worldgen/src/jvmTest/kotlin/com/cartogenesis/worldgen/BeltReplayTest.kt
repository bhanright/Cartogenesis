package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BeltErosion
import com.cartogenesis.worldgen.pipeline.BeltReplay
import com.cartogenesis.worldgen.pipeline.ErosionTectonics
import com.cartogenesis.worldgen.pipeline.PlateResult
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import java.util.Locale
import kotlin.math.abs
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The belts' replay of their uplift and wear over the exposure window (`BeltErosion`,
 * docs/DESIGN_LEDGER.md, TH1-2): its sediment books close, and what it cuts does not depend on the
 * length of its step.
 *
 * Both read the replay alone, on seed [SEED]'s plate stage at [ROWS] rows under an even rain of
 * [RAIN_MM] and no cover, so the figures are the replay's and nothing else's: the rounds after it,
 * the climate and the cover are held by their own guards.
 */
class BeltReplayTest {

    /**
     * What the rivers cut leaves the belts at their outlets, into the sea or onto the ground beyond
     * them; the meters each cell lost, summed over the cells, are the rivers' cut and the slopes'
     * share; and what the slopes shed beside the belts is booked as laid there. Every term is summed
     * in double precision from the same float meters, so the books close to the rounding of a sum of
     * a few hundred thousand floats, [CLOSING_TOLERANCE] of the cut.
     */
    @Test
    fun `the replay's sediment books close`() {
        val config = WorldGenConfig.forRows(SEED, ROWS)
        val plates = platesOf(config)
        val replay = replayOf(config, plates)
        val exported = replay.exportedToSeaCubicMeters + replay.exportedToLandCubicMeters
        val gap = abs(replay.cutCubicMeters - exported) / replay.cutCubicMeters
        println(
            ("REPLAY seed %d at %d rows: %d rounds over %d belt cells; uplift %.4g km3, rivers cut %.4g km3, to the sea %.4g," +
                " onto the land beyond %.4g, shed beside by the slopes %.4g; the rivers' books close to %.2e")
                .format(
                    Locale.ROOT, SEED, ROWS, replay.rounds, replay.beltCells, replay.upliftedCubicMeters / 1e9, replay.cutCubicMeters / 1e9,
                    replay.exportedToSeaCubicMeters / 1e9, replay.exportedToLandCubicMeters / 1e9, replay.shedBesideCubicMeters / 1e9, gap
                )
        )
        assertTrue("the replay ran no rounds on seed $SEED", replay.rounds > 0 && replay.cutCubicMeters > 0.0)
        assertTrue("what the rivers cut, ${replay.cutCubicMeters} m3, is not what left the belts, $exported", gap <= CLOSING_TOLERANCE)
    }

    /**
     * The replay at its own step, at half and at a quarter, over the same window: the rounds' years
     * halved and their count doubled, so the stream-power law's implicit step and the slopes' sweeps
     * a year are the same law at a finer step. The eroded meters, cell by cell, change less from the
     * half to the quarter than from the whole to the half: the scheme converges. Braun and Willett's
     * update is implicit Euler, first order, so the change should halve as the step does; the bar
     * asks for it to fall at least by a quarter, and the finest change to stay under [CONVERGED_SHARE]
     * of the mean eroded depth. Deep tier: three replays.
     */
    @Test
    fun `the replay converges as its step halves`() {
        val config = WorldGenConfig.forRows(SEED, ROWS)
        val plates = platesOf(config)
        val eroded = listOf(1, 2, 4).map { split ->
            val stepped = config.copy(
                scale = config.scale.copy(yearsPerHydraulicRound = config.scale.yearsPerHydraulicRound / split),
                erosion = config.erosion.copy(hydraulicRounds = config.erosion.hydraulicRounds * split)
            )
            val replay = replayOf(stepped, plates)
            println("REPLAY step 1/%d: %d rounds, rivers cut %.4g km3, slopes shed %.4g km3".format(Locale.ROOT, split, replay.rounds, replay.cutCubicMeters / 1e9, replay.shedBesideCubicMeters / 1e9))
            replay.erodedMeters
        }
        val mean = eroded.last().average()
        fun change(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { abs((a[it] - b[it]).toDouble()) } / a.size
        val coarse = change(eroded[0], eroded[1])
        val fine = change(eroded[1], eroded[2])
        println(
            "REPLAY convergence: mean eroded %.1f m a cell; change from the step to its half %.2f m, from the half to the quarter %.2f m (ratio %.2f)"
                .format(Locale.ROOT, mean, coarse, fine, fine / coarse)
        )
        assertTrue("the eroded depth changes by $fine m from the half step to the quarter, not less than the $coarse m before", fine <= CONVERGENCE_RATIO * coarse)
        assertTrue("the eroded depth still changes by $fine m a cell at a quarter step, over $CONVERGED_SHARE of its mean $mean", fine <= CONVERGED_SHARE * mean)
    }

    private fun platesOf(config: WorldGenConfig): PlateResult = PlateStage.generate(config, TerrainStage.generate(config))

    private fun replayOf(config: WorldGenConfig, plates: PlateResult): BeltReplay {
        val cells = config.width * config.height
        val tectonics = ErosionTectonics.of(plates)
        return BeltErosion.replay(
            config, plates.height.data.copyOf(), tectonics.window!!, plates.upliftRateMmPerYear.data, FloatArray(cells) { RAIN_MM },
            FloatArray(cells), plates.rockErodibility.data, config.seaLevel, shieldCut = false
        )
    }

    private companion object {
        const val SEED = 42L
        const val ROWS = 256
        const val RAIN_MM = 1_000f

        /** A double sum of float meters: some 2^-24 of each of a few hundred thousand terms, far under a part in a million of the whole. */
        const val CLOSING_TOLERANCE = 1e-6

        /** First order halves the change as the step halves; the bar asks for at least a quarter's fall. */
        const val CONVERGENCE_RATIO = 0.75

        /** The finest change, as a share of the mean eroded depth, under which the step no longer shapes the cut. */
        const val CONVERGED_SHARE = 0.1
    }
}
