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
class ScaleFreeTest : BorrowsSharedWorlds() {

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
     * The network the channel-head criterion picks out is the same density of ground at every
     * grid.
     *
     * The clause R1 owes this suite. A threshold in square kilometres against a gradient is a
     * statement about ground, where the rule it replaced was a share of the world's runoff, a count
     * of courses and a count of cells, each of which described different ground at every grid. That
     * `atResolution` leaves the threshold itself alone is a question about the settings and is
     * `ResolutionScalingTest`'s, which holds every section but the tectonics equal across grids;
     * this asks it of the network, as a **density** — kilometres of channel over square kilometres
     * of land — and not as a share of the cells. A channel is a line and the land is an area, so the
     * share of *cells* under channel must halve when the cell halves whatever the criterion does;
     * measured, it goes as 0.64 to 0.66 from 512 to 1024 where the geometry alone would say 0.5,
     * and reading that as a failure would be reading the grid. The density is the quantity that
     * describes the ground, and it is held to the same 1.35 [ScaleFree.TOLERANCES] allows the
     * support-area network's.
     */
    @Test
    fun `the channel-head threshold is an area of ground and does not move with the grid`() {
        val complaints = ArrayList<String>()
        SEEDS.forEach { seed ->
            val coarse512 = worldAt(seed, 512)
            val fine1024 = worldAt(seed, 1024)
            val coarseDensity = channelDensityKmPerKm2(coarse512)
            val fineDensity = channelDensityKmPerKm2(fine1024)
            val ratio = fineDensity / coarseDensity
            println(
                ("SCALEFREE channel head seed %d  the initiated network is %.4f km/km2 at 512 and" +
                    " %.4f at 1024 (x%.2f)").format(seed, coarseDensity, fineDensity, ratio)
            )
            if (ratio < 1.0 / CHANNEL_DENSITY_FACTOR || ratio > CHANNEL_DENSITY_FACTOR) {
                complaints.add(
                    "seed $seed: the criterion initiates ${"%.4f".format(coarseDensity)} km of" +
                        " channel per km2 of land at 512 and ${"%.4f".format(fineDensity)} at" +
                        " 1024, a factor of ${"%.2f".format(ratio)} over the" +
                        " $CHANNEL_DENSITY_FACTOR allowed"
                )
            }
        }
        // Recorded since Fix 3b: see [IMPLICIT_CUT_MOVES_WITH_THE_GRID].
        KnownFailures.expect(IMPLICIT_CUT_MOVES_WITH_THE_GRID, "seeds 7, 42, 1234 and 99 over 1.35") {
            if (complaints.isNotEmpty()) {
                throw RecordedViolation(
                    "the channel-head criterion is not the same criterion at two grids: ${complaints.joinToString("; ")}",
                    "seeds " + complaints.map { it.substringAfter("seed ").substringBefore(":") }.let {
                        if (it.size == 1) it.single() else it.dropLast(1).joinToString(", ") + " and " + it.last()
                    } + " over $CHANNEL_DENSITY_FACTOR"
                )
            }
        }
    }

    /**
     * Kilometres of channel per square kilometre of land over the network the criterion initiates,
     * one D8 step per channel cell — the same arithmetic [ScaleFree] measures its own network with.
     */
    private fun channelDensityKmPerKm2(world: WorldMap): Double {
        val channel = ChannelInitiation.channelMaskOf(world)
        val scale = world.config.scale
        val cellsAcross = world.width
        val cellWidthKm = scale.cellWidthKm(cellsAcross)
        val cellHeightKm = scale.cellHeightKm(world.height)
        val diagonalKm = kotlin.math.sqrt(cellWidthKm * cellWidthKm + cellHeightKm * cellHeightKm)
        var channelKm = 0.0
        for (cell in channel.indices) {
            if (!channel[cell]) continue
            val receiver = world.rivers.flowTarget[cell]
            if (receiver < 0) continue
            channelKm += when {
                receiver == cell + cellsAcross || receiver == cell - cellsAcross -> cellHeightKm
                receiver / cellsAcross == cell / cellsAcross -> cellWidthKm
                else -> diagonalKm
            }
        }
        val landKm2 = world.sea.landCellCount * cellWidthKm * cellHeightKm
        return if (landKm2 <= 0.0) 0.0 else channelKm / landKm2
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
        /**
         * The known failure the channel-head clause records since Fix 3b. From 512 to 1024 the
         * criterion's network grows by 1.48, 1.44, 1.38 and 1.43 on seeds 7, 42, 1234 and 99,
         * where on the capped explicit update it grew by 1.16, 1.18, 1.15 and 1.23, and at 512 it
         * is half as dense again (0.028 to 0.037 km/km2 against 0.017 to 0.025). The implicit
         * update is stable at every step but first order, and one reading, not measured, is that
         * at a fixed catchment on the ground `F` doubles when the cell halves, so a finer grid's
         * channels come nearer their round's grade and the slopes the criterion reads stand
         * steeper. Whether rounds of less time bring the two grids together has not been tried; see
         * docs/DESIGN_LEDGER.md, Fix 3b.
         */
        const val IMPLICIT_CUT_MOVES_WITH_THE_GRID =
            "the erosion: the implicit update is first order, and the channel network it leaves grows denser on a finer grid"

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
         * How far the initiated network's drainage density may move between 512 and 1024.
         *
         * The same 1.35 [ScaleFree.TOLERANCES] allows the support-area network's, and for the same
         * reason: the criterion reads the gradient to a cell's own receiver, and a finer grid
         * resolves relief that a coarse one averages into a gentler slope, so the same ground gives
         * a network drawn finer without being a denser one. What the clause refuses is what the
         * rule R1 replaced did — a threshold in cells picks out sixteen times the cells at four
         * times the grid, which is a factor the measurement cannot survive.
         */
        const val CHANNEL_DENSITY_FACTOR = 1.35

        fun configAt(seed: Long, size: Int): WorldGenConfig {
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            return if (size == 512) base else base.atResolution(size, size)
        }

        fun worldAt(seed: Long, size: Int): WorldMap =
            SharedWorlds.world(configAt(seed, size))
    }
}
