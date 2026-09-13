package com.cartogenesis.worldgen

import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The third grid: the same seeds at 2048, against both of the coarser ones.
 *
 * Split from [ScaleFreeTest] for the reason every audit-tier class is: four worlds at 2048 is four
 * minutes of erosion, and the per-merge tier is not the place for it. The tolerances, and the
 * derivation of each, are [ScaleFree]'s; what this adds is the longer lever — a quartering of the
 * cell rather than a halving, which is where a metric that is quietly grid-dependent stops being
 * able to hide inside a tolerance.
 */
class ScaleFreeAuditTest {

    @Test
    fun `the same world at 512, 1024 and 2048 measures the same in physical units`() {
        val complaints = ArrayList<String>()
        ScaleFreeTest.SEEDS.forEach { seed ->
            val at512 = ScaleFree.measure(ScaleFreeTest.worldAt(seed, 512), "seed $seed")
            val at1024 = ScaleFree.measure(ScaleFreeTest.worldAt(seed, 1024), "seed $seed")
            val at2048 = ScaleFree.measure(ScaleFreeTest.worldAt(seed, 2048), "seed $seed")
            listOf(at512, at1024, at2048).forEach { ScaleFree.print(it) }
            val findings = ArrayList<String>()
            listOf(at512 to at1024, at1024 to at2048, at512 to at2048).forEach { (coarse, fine) ->
                val verdict = ScaleFree.compare(coarse, fine)
                complaints += verdict.complaints
                findings += verdict.findings
            }
            findings.forEachIndexed { rank, finding ->
                println("SCALEFREE FINDING seed $seed ${rank + 1}. $finding")
            }
        }
        assertTrue(
            "the world is not the same world at 512, 1024 and 2048 on a metric this generator" +
                " was holding: ${complaints.joinToString("; ")}",
            complaints.isEmpty()
        )
    }
}
