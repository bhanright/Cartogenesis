package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The Earth-likeness yardstick at 512, on the four standard seeds: every metric of the plan's M1
 * table measured, printed with Earth's figure beside it, and asserted where Earth has a figure and
 * this generator already reaches it.
 *
 * Every number the later chunks are accepted against is printed here, whether or not it is
 * asserted, because a chunk that improves a metric has to be able to say what the metric was.
 * The ones this generator does not reach are printed as findings and left alone: plan rule 5 will
 * not have a bar moved to fit, and repairing them is S1's business and the chunks after it.
 *
 * The clauses are shown to bite in [EarthLikenessControlTest]; the same worlds at export resolution
 * on the author's own settings are in [EarthLikenessAuditTest]. See [EarthLikeness] for what each
 * metric is and where Earth's number comes from.
 */
class EarthLikenessTest {

    @Test
    fun `every metric of the suite, per seed and pooled`() {
        val suite = suite()
        suite.perSeed.forEach { EarthLikeness.print(it) }
        EarthLikeness.print(suite.pooled)
        // The plan's table row that lives in `GeographyAuditTest`: printed here so every figure of
        // the suite can be read off one place, and asserted there, where its derivation lives.
        suite.desertBands.report(seeds, "EARTH BAND")
        // Every clause below divides by something this suite counted, so a world that produced no
        // coast, no basins or no land would pass them all by measuring nothing.
        suite.perSeed.forEach { metrics ->
            assertTrue("seed ${metrics.label} has no land to measure", metrics.landCells > 0)
            assertTrue(
                "seed ${metrics.label} has no coastline to count boxes along",
                metrics.coastline.boxes.all { it > 0 }
            )
            assertTrue(
                "seed ${metrics.label} has only ${metrics.hack.points} basins over" +
                    " ${EarthLikeness.SMALLEST_HACK_CATCHMENT_CELLS} cells, too few for Hack",
                metrics.hack.points > 50
            )
            assertTrue(
                "seed ${metrics.label} has a channel network of one order, so nothing branches",
                metrics.horton[0].highestOrder >= 3
            )
        }
    }

    /**
     * The metrics that have an Earth figure this generator already reaches, on every standard seed
     * and on the four pooled.
     *
     * Four of them, and each bar is Earth's own number with its tolerance derived beside the
     * constant that holds it in [EarthLikeness]: the coastline's box-counting dimension against
     * Mandelbrot's 1.25, Hack's exponent against Hack's and Rigon's 0.5-0.6, the weighted mean
     * bifurcation ratio against Horton's 3-5, and where drainage density peaks against Moglen,
     * Eltahir and Bras. One more is pooled only — the lake-size Pareto exponent — because one world
     * at 512 carries a couple of dozen lakes and a Pareto exponent on a couple of dozen bodies has
     * a sampling error a third of its own size.
     */
    @Test
    fun `the metrics Earth has a figure for and this generator reaches`() {
        val suite = suite()
        val complaints = ArrayList<String>()
        suite.perSeed.forEach { complaints += EarthLikeness.complaints(it, oneWorld = true) }
        complaints += EarthLikeness.complaints(suite.pooled, oneWorld = false)
        assertTrue(
            "the Earth-likeness suite has regressed on metrics this generator was meeting: " +
                complaints.joinToString("; "),
            complaints.isEmpty()
        )
    }

    /**
     * The metrics Earth has a figure for that this generator does *not* reach, printed in the order
     * the chunks after M1 should take them up: farthest from Earth first.
     *
     * Not an assertion. The plan's rule 5 forbids a bar moved to fit and M1's job is to measure, so
     * a metric the generator misses is written down with Earth's figure beside it and left for the
     * chunk that owns its cause. The one thing asserted is that the list is not *empty*, which
     * would mean either that the generator had become Earth or — far likelier — that the findings
     * had stopped being measured.
     */
    @Test
    fun `the findings, ranked by how far they sit from Earth`() {
        val findings = EarthLikeness.findings(suite().pooled)
        findings.forEachIndexed { rank, finding -> println("EARTH FINDING ${rank + 1}. $finding") }
        assertTrue(
            "no findings at all, which means the suite has stopped measuring them rather than" +
                " that the generator has become Earth",
            findings.isNotEmpty()
        )
    }

    private class Suite(
        val perSeed: List<EarthLikeness.Metrics>,
        val pooled: EarthLikeness.Metrics,
        val desertBands: DesertBands
    )

    private companion object {
        /** The standard seeds, which are `GeographyAuditTest`'s. */
        val seeds = listOf(7L, 42L, 1234L, 99L)

        private var measured: Suite? = null

        /**
         * Measures every seed once and keeps the result, so the three tests above share one run of
         * the pipeline rather than four each. Four worlds at 512 is the whole cost of this class.
         */
        fun suite(): Suite = measured ?: run {
            val pool = EarthLikeness.Pool()
            val perSeed = seeds.map { seed ->
                val world: WorldMap = WorldGenerationEngine.generateBlocking(
                    WorldGenConfig(seed = seed, width = 512, height = 512)
                )
                EarthLikeness.measure(world, seed.toString(), pool)
            }
            Suite(perSeed, pool.pooled(), pool.desertBands).also { measured = it }
        }
    }
}
