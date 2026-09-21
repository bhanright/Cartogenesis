package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ChannelInitiation
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
 *
 * Since F35 it asks that last question twice: once of the statistics, which is what the class was
 * built on, and once of the ground itself, because a world whose plates have all moved weighs the
 * same as the world it replaced and the statistics said so for a month.
 */
class ScaleFreeTest {

    @Test
    fun `the same world at 512 and 1024 measures the same and stands on the same ground`() {
        val complaints = ArrayList<String>()
        val findings = ArrayList<String>()
        SEEDS.forEach { seed ->
            val coarseWorld = worldAt(seed, 512)
            val fineWorld = worldAt(seed, 1024)
            val coarse = ScaleFree.measure(coarseWorld, "seed $seed")
            val fine = ScaleFree.measure(fineWorld, "seed $seed")
            ScaleFree.print(coarse)
            ScaleFree.print(fine)
            val verdict = ScaleFree.compare(coarse, fine)
            complaints += verdict.complaints
            findings += verdict.findings
            complaints += standOnTheSameGround(seed, coarseWorld, fineWorld)
        }
        findings.forEachIndexed { rank, finding ->
            println("SCALEFREE FINDING ${rank + 1}. $finding")
        }
        assertTrue(
            "the world is not the same world at 512 and 1024: ${complaints.joinToString("; ")}",
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

    /**
     * The same seed puts its plates in the same places at 512, 1024 and 2048.
     *
     * The clause the suite above did not have, and the reason F35 went a month unnoticed: measured
     * in shares of the land and kilometres of coast, a world whose plates have all moved is still a
     * plausible world. The seeds are the first thing the pipeline draws and everything else stands
     * on them, so if they agree across grids the worlds are the same world, and if they do not,
     * nothing downstream can be.
     */
    @Test
    fun `the same seed puts its plates in the same places at every grid`() {
        val worst = HashMap<String, Double>()
        SEEDS.forEach { seed ->
            listOf(1024, 2048).forEach { fineSize ->
                val drift = ScaleFree.plateSeedDriftCoarseCells(
                    configAt(seed, 512), configAt(seed, fineSize)
                )
                println("SCALEFREE plate seeds  seed %d  512 against %d  worst drift %.3f cells"
                    .format(seed, fineSize, drift))
                if (drift > PLATE_SEED_DRIFT_COARSE_CELLS) {
                    worst["seed $seed at 512 against $fineSize"] = drift
                }
            }
        }
        assertTrue(
            "a plate seed sits at a different fraction of the grid at one size than at another," +
                " so the same seed is a different world at each: ${worst.entries.joinToString(";" +
                    " ") { "${it.key} moved by ${"%.1f".format(it.value)} coarse cells" }}",
            worst.isEmpty()
        )
    }

    /**
     * The channel-head threshold is the same area of ground at every grid, and so is the network
     * it picks out.
     *
     * The clause R1 owes this suite. A threshold in square kilometres against a gradient is a
     * statement about ground, so `atResolution` must leave it exactly alone — where the rule it
     * replaced was a share of the world's runoff, a count of courses and a count of cells, each of
     * which described different ground at every grid. The share of land the criterion calls channel
     * is the other half: it is not asked to be equal, because a finer grid resolves gradients a
     * coarse one averages away and the criterion reads gradients, but it is asked to stay within
     * the factor the drainage-density clause already allows.
     */
    @Test
    fun `the channel-head threshold is an area of ground and does not move with the grid`() {
        val complaints = ArrayList<String>()
        SEEDS.forEach { seed ->
            val coarse = configAt(seed, 512)
            val fine = configAt(seed, 1024)
            if (coarse.rivers.channelHeadAreaSlopeSquaredKm2 !=
                fine.rivers.channelHeadAreaSlopeSquaredKm2
            ) {
                complaints.add(
                    "seed $seed: the channel-head threshold is" +
                        " ${coarse.rivers.channelHeadAreaSlopeSquaredKm2} km2 at 512 and" +
                        " ${fine.rivers.channelHeadAreaSlopeSquaredKm2} at 1024"
                )
            }
            if (coarse.rivers.shortestDrawnCourseKm != fine.rivers.shortestDrawnCourseKm) {
                complaints.add(
                    "seed $seed: the shortest drawn course is" +
                        " ${coarse.rivers.shortestDrawnCourseKm} km at 512 and" +
                        " ${fine.rivers.shortestDrawnCourseKm} at 1024"
                )
            }
            val coarseShare = channelShareOfLand(worldAt(seed, 512))
            val fineShare = channelShareOfLand(worldAt(seed, 1024))
            val ratio = fineShare / coarseShare
            println(
                ("SCALEFREE channel head seed %d  %.4f of the land is channel at 512 and %.4f at" +
                    " 1024 (x%.2f)").format(seed, coarseShare, fineShare, ratio)
            )
            if (ratio < 1.0 / CHANNEL_SHARE_FACTOR || ratio > CHANNEL_SHARE_FACTOR) {
                complaints.add(
                    "seed $seed: the criterion calls ${"%.4f".format(coarseShare)} of the land" +
                        " channel at 512 and ${"%.4f".format(fineShare)} at 1024, a factor of" +
                        " ${"%.2f".format(ratio)} over the $CHANNEL_SHARE_FACTOR allowed"
                )
            }
        }
        assertTrue(
            "the channel-head criterion is not the same criterion at two grids:" +
                " ${complaints.joinToString("; ")}",
            complaints.isEmpty()
        )
    }

    /** What share of the land the channel-head criterion calls channel. */
    private fun channelShareOfLand(world: WorldMap): Double {
        val channel = ChannelInitiation.channelMaskOf(world)
        var channelCells = 0L
        for (cell in channel.indices) if (channel[cell]) channelCells++
        return channelCells.toDouble() / world.sea.landCellCount
    }

    /**
     * The plates own the same ground, and the sea stands on the same coast, at 512 and at 1024.
     *
     * Where the clause above asks about fourteen points, these two ask about every cell: the
     * partition each grid grew from those points, and the land mask the sea level and the coast
     * passes left. Both are compared by taking the fine grid's nearest cell to each coarse cell —
     * see [ScaleFree.plateFieldAgreement] and [ScaleFree.landMaskAgreement] for what each one
     * excuses and why.
     */
    private fun standOnTheSameGround(
        seed: Long,
        coarse: WorldMap,
        fine: WorldMap
    ): List<String> {
        val complaints = ArrayList<String>()

        val plates = ScaleFree.plateFieldAgreement(coarse, fine)
        println(
            ("SCALEFREE plate field  seed %d  512 against 1024  %.5f of the interior agrees" +
                "  (the interior is %.3f of the grid; the deepest disagreement sits %.2f" +
                " cells from a boundary)")
                .format(
                    seed, plates.matchedShare, plates.comparedShare, plates.deepestMismatchCells
                )
        )
        if (plates.matchedShare < PLATE_INTERIOR_AGREEMENT) {
            complaints.add(
                "seed $seed: ${"%.5f".format(plates.matchedShare)} of the plate interiors agree" +
                    " between 512 and 1024, under the $PLATE_INTERIOR_AGREEMENT a warped" +
                    " boundary is worth — a cell further than" +
                    " ${ScaleFree.INTERIOR_MARGIN_CELLS} coarse cell from a boundary belongs to" +
                    " the same plate at any grid"
            )
        }

        val land = ScaleFree.landMaskAgreement(coarse, fine)
        println(
            ("SCALEFREE land mask    seed %d  512 against 1024  %.5f of the grid agrees" +
                "  (the shore touches %.5f of it)")
                .format(seed, land.matchedShare, land.comparedShare)
        )
        if (land.matchedShare < 1.0 - land.comparedShare) {
            complaints.add(
                "seed $seed: the land mask agrees on ${"%.5f".format(land.matchedShare)} of the" +
                    " grid between 512 and 1024, where only the" +
                    " ${"%.5f".format(land.comparedShare)} the shore touches may differ"
            )
        }
        return complaints
    }

    internal companion object {
        /** The standard seeds, which are `GeographyAuditTest`'s and `EarthLikenessTest`'s. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L)

        /**
         * How far a plate seed may sit from itself between grids, in cells of the coarser one.
         *
         * One cell. The draw is a fraction and the only thing between it and a cell is the
         * rounding, which cannot be worth more than the cell it rounds into.
         */
        const val PLATE_SEED_DRIFT_COARSE_CELLS = 1.0

        /**
         * What share of a plate's interior must carry the same plate at both grids.
         *
         * Not all of it, and here is the cell it is not. A seed may sit a coarse cell from where
         * it sits at the other grid — the clause above allows exactly that, and it is the rounding
         * of a fraction into a cell — so the bisector between two seeds may move by a cell too;
         * and the domain warp that bends a Voronoi edge into a meander is a displacement of many
         * cells, so wherever its gradient is steep that one cell of bisector is carried some way
         * into the map. Measured, the deepest disagreement on the four seeds sits 2.83 coarse
         * cells from a boundary and there are four of them in a quarter of a million interior
         * cells (0.99996 to 1.00000): that is the thickness of a warped boundary, not a different
         * partition. A thousandth of the interior is the bar, four hundred times the worst
         * measured and three orders of magnitude off the 0.139 to 0.257 the generator scored
         * before F35 — the two cases are nowhere near each other.
         */
        const val PLATE_INTERIOR_AGREEMENT = 0.999

        /**
         * How far the share of land under channel may move between 512 and 1024.
         *
         * The same 1.35 [ScaleFree.TOLERANCES] allows the drainage density itself, and for the same
         * reason: the criterion reads the gradient to a cell's own receiver, and a finer grid
         * resolves relief that a coarse one averages into a gentler slope, so a network extracted
         * on the same ground is drawn finer without being denser. What the clause refuses is what
         * a count of cells did — sixteen times the cells at four times the grid, which is a factor
         * of four in any share.
         */
        const val CHANNEL_SHARE_FACTOR = 1.35

        fun configAt(seed: Long, size: Int): WorldGenConfig {
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            return if (size == 512) base else base.atResolution(size, size)
        }

        fun worldAt(seed: Long, size: Int): WorldMap =
            WorldGenerationEngine.generateBlocking(configAt(seed, size))
    }
}
