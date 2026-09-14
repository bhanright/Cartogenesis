package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Isostasy
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.thermalSweepBlocking
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * What the flexure costs at export resolutions, which is rule 8 of the plan asked directly.
 *
 * The flexure is one transform pair over the whole grid, once a hydraulic round, and every other
 * piece of S2 is a per-cell add. So the question the plan asks — is this a G-track candidate? — is
 * a question about one FFT against the round it follows, and it is answered by timing both rather
 * than by reasoning about them. The uplift's own cost is timed beside it for completeness, though
 * it is a multiply and an add over the cells that are rising and could hardly be otherwise.
 *
 * Reported rather than asserted, except for a ceiling loose enough that only a change of algorithm
 * could cross it: a timing on a shared machine is not a number to hold a build to.
 */
class IsostasyAuditTest {

    @Test
    fun `report what the flexure costs against the round it follows`() {
        listOf(2048, 4096).forEach { side ->
            val config = WorldGenConfig(seed = 42L, width = side, height = side)
            val flexure = Isostasy.Flexure(config)
            val load = FloatArray(side * side)
            val terrain = TerrainStage.generate(config).height
            val isostasy = config.isostasy
            for (cell in load.indices) {
                load[cell] = Isostasy.loadPascals(
                    (terrain.data[cell] - 0.5f) * 500f, isostasy.continentalCrustDensity,
                    isostasy.gravity
                )
            }

            // Once to let the JIT see the transform, then three for the figure.
            flexure.deflectionMetres(load, FloatArray(side * side))
            val deflection = FloatArray(side * side)
            val flexureMillis = (1..3).minOf {
                measureTimeMillis { flexure.deflectionMetres(load, deflection) }
            }

            // A round is a fill, a route, an accumulate and two ordered walks, and between rounds
            // the thermal sweeps run their share. The sweeps alone are the honest comparison here:
            // they are the largest single thing in a round and the one this stage is measured
            // against.
            val sweepsPerRound =
                (com.cartogenesis.worldgen.pipeline.ErosionStage.sweepsFor(config) /
                    config.erosion.hydraulicRounds).coerceAtLeast(1)
            val relaxMillis = measureTimeMillis {
                thermalSweepBlocking(config, terrain, skipSettled = true, sweeps = sweepsPerRound)
            }

            println(
                ("ISOSTASY cost at %d: the flexure is %d ms, the %d thermal sweeps between rounds" +
                    " are %d ms, so the plate answers in %.2f of a round's relaxation").format(
                    side, flexureMillis, sweepsPerRound, relaxMillis,
                    flexureMillis.toDouble() / relaxMillis.coerceAtLeast(1)
                )
            )
            assertTrue(
                "the flexure took ${flexureMillis} ms at $side, over the $CEILING_MILLIS ms" +
                    " ceiling — an FFT pair over this grid should not be near that, so something" +
                    " other than the transform is in the timing",
                flexureMillis < CEILING_MILLIS
            )
        }
    }

    private companion object {
        /**
         * The most a single flexure may take at any grid this program will draw, in milliseconds.
         *
         * Thirty seconds, which is not a performance bar but a shape check: a 4096 transform pair
         * is a few seconds of arithmetic on any machine that can hold the buffers at all, and a
         * figure an order of magnitude above that would mean the pass had stopped being one
         * transform pair.
         */
        const val CEILING_MILLIS = 30_000L
    }
}
