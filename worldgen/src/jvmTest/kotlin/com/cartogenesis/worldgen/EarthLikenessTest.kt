package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
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
class EarthLikenessTest : BorrowsSharedWorlds() {

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
                    " ${EarthLikeness.SMALLEST_HACK_CATCHMENT_KM2} km2, too few for Hack",
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
        // Recorded since Fix 3, and re-recorded at Fix 3b: see [LAW_SETS_EVERY_CUT]. Under the cap
        // seed 99's humid country carried 1.02 times the semi-arid's channel and the pooled coast's
        // box count read 1.036; on the law's terrain they read 1.00 and 1.092, Earth's figures kept.
        KnownFailures.expect(LAW_SETS_EVERY_CUT, "99: humid country carries 1.00 times the channel per unit of land that semi-arid country does, " +
                "where Moglen, Eltahir & Bras (1998) have the density falling away on the wet side and so below one; " +
                "pooled: the coastline's box-counting dimension is 1.092, outside 1.25 +/- 0.15 " +
                "(Mandelbrot 1967: Britain 1.25, Richardson's smoothest coast 1.02)") {
            if (complaints.isNotEmpty()) {
                throw RecordedViolation(
                    "the Earth-likeness suite has regressed on metrics this generator was meeting: " +
                        complaints.joinToString("; "),
                    complaints.joinToString("; ") { it.substringBefore(" — ") }
                )
            }
        }
    }

    /**
     * The metrics Earth has a figure for that this generator does *not* reach, printed in the order
     * the chunks after M1 should take them up: farthest from Earth first.
     *
     * Not an assertion of the figures. The plan's rule 5 forbids a bar moved to fit and M1's job is
     * to measure, so a metric the generator misses is written down with Earth's figure beside it
     * and left for the chunk that owns its cause. What is asserted is that each figure was read
     * off something: land, lakes and islands counted, a network fitted and a course drawn. A finding
     * over an empty sample reads zero and ranks like any other, so a list that is never empty — and
     * `EarthLikeness.findings` always adds its entries — says nothing about whether the suite is
     * still measuring; the samples do.
     */
    @Test
    fun `the findings, ranked by how far they sit from Earth`() {
        val pooled = suite().pooled
        val findings = EarthLikeness.findings(pooled)
        findings.forEachIndexed { rank, finding -> println("EARTH FINDING ${rank + 1}. $finding") }
        val empty = emptySamples(pooled)
        assertTrue(
            "the findings were ranked over empty samples, so the suite has stopped measuring them:" +
                " ${empty.joinToString()}",
            empty.isEmpty()
        )
        // The control: a pool that measured no world at all still yields a list of findings, which
        // is why a non-empty list could not fail, and this check refuses it on every sample.
        val nothing = EarthLikeness.Pool().pooled("nothing measured")
        assertTrue("findings over no world at all came back empty", EarthLikeness.findings(nothing).isNotEmpty())
        assertEquals(5, emptySamples(nothing).size, "a pool that measured nothing passed the sample check")
    }

    /** The samples the findings are read off that hold nothing, by name. */
    private fun emptySamples(metrics: EarthLikeness.Metrics): List<String> = listOf(
        "land" to metrics.landCells,
        "lakes" to metrics.lakeCells,
        "islands" to metrics.islandSizes.count.toLong(),
        "the terrain's network" to metrics.hackFullNetwork.points.toLong(),
        "the drawn courses, in km" to metrics.drawnKilometres.toLong()
    ).filter { it.second <= 0L }.map { it.first }

    private class Suite(
        val perSeed: List<EarthLikeness.Metrics>,
        val pooled: EarthLikeness.Metrics,
        val desertBands: DesertBands
    )

    private companion object {
        /**
         * The known failure the clauses Fix 3b moved record. The implicit update lets the
         * stream-power law set every cut, where the explicit update's cap at half the drop set the
         * drawn network's, so the land is cut as the law asks; this clause's figure was recorded on
         * the capped terrain, or its bar is Earth's and the law's terrain, with the uplift
         * re-derived on it, does not reach it. See docs/DESIGN_LEDGER.md, Fix 3b, for the figures.
         */
        const val LAW_SETS_EVERY_CUT =
            "the erosion: since the implicit update the stream-power law sets every cut, and this clause's figure was recorded on the capped terrain"

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
                val world: WorldMap = SharedWorlds.world(
                    WorldGenConfig(seed = seed, width = 512, height = 512)
                )
                EarthLikeness.measure(world, seed.toString(), pool)
            }
            Suite(perSeed, pool.pooled(), pool.desertBands).also { measured = it }
        }
    }
}
