package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The same seed at 512 and at 1024, measured in kilometres, metres and shares of the land.
 *
 * The per-merge half of the scale-free suite; [ScaleFreeAuditTest] adds 2048, which is four worlds
 * of a minute each and belongs to the on-demand tier. What each metric is and where its tolerance
 * comes from is in [ScaleFree].
 *
 * This is what replaced the per-stage resolution contracts. `ResolutionScalingTest` used to assert
 * that `atResolution` multiplied a dozen named settings by the grid ratio, which is a test of a
 * rescaling function rather than of the world; now that a reach is a length in kilometres and a
 * depth is a number of metres, the scaling is arithmetic and what is worth asserting is the thing
 * the contracts were standing in for — that the world at one grid is the world at another.
 */
class ScaleFreeTest {

    @Test
    fun `the same world at 512 and 1024 measures the same in physical units`() {
        val complaints = ArrayList<String>()
        val findings = ArrayList<String>()
        SEEDS.forEach { seed ->
            val coarse = ScaleFree.measure(worldAt(seed, 512), "seed $seed")
            val fine = ScaleFree.measure(worldAt(seed, 1024), "seed $seed")
            ScaleFree.print(coarse)
            ScaleFree.print(fine)
            val verdict = ScaleFree.compare(coarse, fine)
            complaints += verdict.complaints
            findings += verdict.findings
        }
        findings.forEachIndexed { rank, finding ->
            println("SCALEFREE FINDING ${rank + 1}. $finding")
        }
        assertTrue(
            "the world is not the same world at 512 and 1024 on a metric this generator was" +
                " holding: ${complaints.joinToString("; ")}",
            complaints.isEmpty()
        )
        // The other half of the claim, and what stops the clause above passing because nothing was
        // measured. If this list ever empties, the generator has become scale-free and the
        // findings should be promoted to assertions, one at a time and each with its own chunk.
        assertTrue(
            "no findings at all, which means the suite has stopped measuring rather than that" +
                " every metric has become scale-free",
            findings.isNotEmpty()
        )
    }

    internal companion object {
        /** The standard seeds, which are `GeographyAuditTest`'s and `EarthLikenessTest`'s. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L)

        fun worldAt(seed: Long, size: Int): com.cartogenesis.worldgen.model.WorldMap {
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            return WorldGenerationEngine.generateBlocking(
                if (size == 512) base else base.atResolution(size, size)
            )
        }
    }
}
