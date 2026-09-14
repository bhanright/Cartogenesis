package com.cartogenesis.worldgen

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The controls for [EarthLikenessTest]: a synthetic world per clause, built to violate it, so that
 * every bar in the suite is known to bite rather than merely known to be green.
 *
 * Plan ground rule 2. Three of these are the plan's own — a flat world for the hypsometry, a
 * straight-edged rectangular coast for the fractal dimension, a single-order comb for Horton and
 * Hack — and the two size distributions and the drainage-density peak get one each on the same
 * principle. None of them generates a world: each hands the same functions the real suite uses a
 * field, a network or a set of areas with the property in question removed, which is what makes
 * the whole class cost nothing.
 *
 * Two of the clauses are here for a second reason: the hypsometry's and the islands' are the two
 * that [EarthLikeness.complaints] does not assert, because the generator fails them today, so this
 * class is where they are shown to work at all — the first passes on Earth's own band table and
 * fails on a featureless world, and the second recovers a known exponent to two decimal places from
 * a set laid out to have it. That is what makes the generator's failures findings rather than
 * broken measurements.
 */
class EarthLikenessControlTest {

    /**
     * Earth's hypsometry passes the bimodality clause and a featureless world does not.
     *
     * The first case is the band table in [EarthLikeness]'s own documentation, entered as a
     * histogram: two modes five bands apart with the continental slope between them. The second is
     * a world whose elevations are spread evenly from its deepest point to its highest — the
     * hypsometry of a plane, which is what a world with no crust types and no isostasy has — and
     * the third is a world of one single elevation, which has no bands at all.
     */
    @Test
    fun `the bimodality clause passes Earth and fails a featureless world`() {
        // Earth's band table from [EarthLikeness]'s documentation, in tenths of a per cent of the
        // surface so the counts are integers: kilometre bands, band 0 being 0 to +1 km.
        val earth = EarthLikeness.Hypsometry(
            bandMetres = 1000.0,
            reliefSpanMetres = 20000.0,
            highestMetres = 9000.0,
            deepestMetres = -11000.0,
            cellsByBand = mapOf(
                4 to 6L, 3 to 9L, 2 to 20L, 1 to 55L, 0 to 201L,
                -1 to 60L, -2 to 35L, -3 to 50L, -4 to 120L, -5 to 212L, -6 to 198L, -7 to 32L
            )
        )
        val earthComplaint = EarthLikeness.bimodalityComplaint("EARTH-TABLE", earth)
        println(
            "EARTH CONTROL Earth's own band table: land mode ${earth.landModeBand}," +
                " sea mode ${earth.seaModeBand}, trough ${earth.troughBand} at" +
                " ${"%.3f".format(earth.troughShareOfSmallerMode ?: 0.0)} of the smaller mode," +
                " two modal windows hold ${"%.3f".format(earth.twoModeShareOfSurface)}"
        )
        assertTrue(
            "the bimodality clause rejects Earth's own hypsometry, so it is measuring something" +
                " other than bimodality: $earthComplaint",
            earthComplaint == null
        )
        assertTrue(
            "the two-mode share read ${"%.3f".format(earth.twoModeShareOfSurface)} off Earth's own" +
                " table rather than the ${EarthLikeness.EARTH_TWO_MODE_SHARE_OF_SURFACE} the" +
                " constant claims, so the derivation beside it is wrong",
            abs(
                earth.twoModeShareOfSurface - EarthLikeness.EARTH_TWO_MODE_SHARE_OF_SURFACE
            ) < 0.01
        )

        val ramp = EarthLikeness.hypsometryOfMetres(
            DoubleArray(20_000) { -11000.0 + it * (20000.0 / 20_000) }
        )
        val rampComplaint = EarthLikeness.bimodalityComplaint("RAMP", ramp)
        println("EARTH CONTROL featureless ramp: $rampComplaint")
        assertTrue("a world of uniform hypsometry was not called unimodal", rampComplaint != null)

        val flat = EarthLikeness.hypsometryOfMetres(DoubleArray(20_000) { 0.0 })
        val flatComplaint = EarthLikeness.bimodalityComplaint("FLAT", flat)
        println("EARTH CONTROL flat world: $flatComplaint")
        assertTrue("a world of one elevation was not called unimodal", flatComplaint != null)

        // And the sea-mode clause on the same two: Earth's own deep floor passes, and a world
        // whose sea floor is a few hundred metres down — which is what a height field with no
        // isostasy in it produces, and what this generator had before S2 — does not.
        val earthSeaMode = EarthLikeness.seaModeComplaint("EARTH-TABLE", earth)
        println("EARTH CONTROL Earth's sea mode: ${earth.seaModeMetres} m, $earthSeaMode")
        assertTrue(
            "the sea-mode clause rejects Earth's own band table: $earthSeaMode",
            earthSeaMode == null
        )
        val shallow = EarthLikeness.hypsometryOfMetres(
            DoubleArray(20_000) { if (it % 5 == 0) 400.0 else -400.0 }
        )
        val shallowComplaint = EarthLikeness.seaModeComplaint("SHALLOW", shallow)
        println("EARTH CONTROL a world whose sea floor is 400 m down: $shallowComplaint")
        assertTrue(
            "a world whose sea floor sits 400 m below the waterline passed the sea-mode clause",
            shallowComplaint != null
        )
    }

    /**
     * A rectangle of land in an empty sea has a coastline of dimension one, and the bar rejects it.
     *
     * Four straight edges: doubling the box size exactly halves the boxes the line passes through,
     * which is what dimension one means, and it is the shape Richardson's smoothest coast is a
     * rough version of. Measured by the same [EarthLikeness.coastlineBoxCount] the worlds go
     * through.
     */
    @Test
    fun `the coastline clause bites on a straight-edged rectangular coast`() {
        val cellsAcross = 512
        val cellsDown = 512
        // Edges off the box grid on purpose. A rectangle whose sides land exactly on multiples of
        // sixteen has every box either wholly land or wholly sea and crosses none of them, which
        // would make the count zero rather than small — a property of the alignment, not the coast.
        val isLand = BooleanArray(cellsAcross * cellsDown) { cell ->
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            column in 130 until 381 && row in 133 until 379
        }
        val boxes = EarthLikeness.coastlineBoxCount(isLand, cellsAcross, cellsDown)
        val complaint = EarthLikeness.coastlineComplaint("RECTANGLE", boxes)
        println(
            "EARTH CONTROL rectangular coast: boxes ${
                boxes.boxSizes.indices.joinToString(" ") { "${boxes.boxSizes[it]}:${boxes.boxes[it]}" }
            }, dimension ${"%.3f".format(boxes.dimension)} — $complaint"
        )
        assertTrue(
            "a rectangle's coastline measured ${"%.3f".format(boxes.dimension)} rather than the" +
                " 1.0 a straight edge has, so the box count is not measuring a dimension",
            boxes.dimension < 1.05
        )
        assertTrue("the coastline bar accepted four straight coasts", complaint != null)
    }

    /**
     * A comb of parallel channels with no junction anywhere: Hack's exponent is one and Horton has
     * nothing to bifurcate.
     *
     * Every column of the grid is its own channel running straight down to the bottom edge, with a
     * source at a different row so the lengths run from one cell to the full height. A tooth's
     * catchment is exactly its own length, so `L = A` and Hack's exponent is 1.0 rather than the
     * half Earth's basins give; and no tooth ever meets another, so every stream is order one and
     * the bifurcation ratio has no pair to average.
     *
     * The comb's own arithmetic is written out here rather than taken from [EarthLikeness], so that
     * what is being checked is the shipped fit and the shipped bar and not a second copy of the
     * measurement.
     */
    @Test
    fun `the Hack and Horton clauses bite on a single-order comb`() {
        val cellsAcross = 64
        val cellsDown = 64
        val cellCount = cellsAcross * cellsDown
        val flowTarget = IntArray(cellCount) { -1 }
        val channel = BooleanArray(cellCount)
        // Column c is a tooth running from row (cellsDown - 1 - c) to the bottom row, so the teeth
        // are one cell long at the left of the grid and the full height at the right.
        for (column in 0 until cellsAcross) {
            for (row in (cellsDown - 1 - column) until cellsDown) {
                val cell = row * cellsAcross + column
                channel[cell] = true
                flowTarget[cell] = if (row == cellsDown - 1) -1 else (row + 1) * cellsAcross + column
            }
        }
        // Lowest ground first, which on a comb is the bottom row first.
        val byHeight = IntArray(cellCount) { rank ->
            val row = cellsDown - 1 - rank / cellsAcross
            row * cellsAcross + rank % cellsAcross
        }

        val orders = EarthLikeness.strahlerStreamOrders(channel, flowTarget, byHeight)
        val hortonComplaint = EarthLikeness.bifurcationComplaint("COMB", orders)
        println(
            "EARTH CONTROL comb network: streams ${orders.perOrder()}," +
                " weighted bifurcation ratio ${"%.2f".format(orders.bifurcationRatio)}" +
                " — $hortonComplaint"
        )
        assertTrue(
            "a comb with no junction was given ${orders.highestOrder} Strahler orders",
            orders.highestOrder == 1
        )
        assertTrue("the bifurcation bar accepted a network with no junction", hortonComplaint != null)

        // The teeth as Hack pairs, counted off the comb rather than restated: each tooth's
        // catchment is what drains to its foot and its main stem is the walk from its head down.
        val catchmentCells = IntArray(cellCount)
        val stemCells = IntArray(cellCount)
        for (rank in byHeight.indices.reversed()) {
            val cell = byHeight[rank]
            if (!channel[cell]) continue
            catchmentCells[cell]++
            val receiver = flowTarget[cell]
            if (receiver < 0) continue
            catchmentCells[receiver] += catchmentCells[cell]
            stemCells[receiver] = maxOf(stemCells[receiver], stemCells[cell] + 1)
        }
        val combPairs = (0 until cellsAcross).mapNotNull { column ->
            val foot = (cellsDown - 1) * cellsAcross + column
            if (stemCells[foot] < 1) null
            else ln(catchmentCells[foot].toDouble()) to ln(stemCells[foot].toDouble())
        }
        val hack = EarthLikeness.fitLine(combPairs.map { it.first }, combPairs.map { it.second })
        val hackComplaint = EarthLikeness.hackComplaint("COMB", hack)
        println(
            "EARTH CONTROL comb network: Hack's exponent ${"%.3f".format(hack.slope)}" +
                " — $hackComplaint"
        )
        assertTrue(
            "a comb whose every tooth drains exactly its own length gave Hack's exponent as" +
                " ${"%.3f".format(hack.slope)}, where it cannot be below 1.0",
            hack.slope > 0.99
        )
        assertTrue("the Hack bar accepted a network with no tributaries at all", hackComplaint != null)
    }

    /**
     * The two size-distribution clauses, against the two ways a generator gets a size distribution
     * wrong: bodies all at one scale, and bodies almost all at the smallest scale there is.
     *
     * Forty lakes of exactly one area have no distribution at all — the log-log fit has nothing to
     * regress against — which is the world of a generator whose lakes are the basins of one
     * process at one wavelength. A steep power law is the other: islands whose count runs as
     * `area^-1.2` against Korcak's `area^-0.5` are a coast shedding speckle, nearly every island
     * at the smallest area the grid can hold and hardly any of the middle sizes an archipelago is.
     */
    @Test
    fun `the size-distribution clauses bite on lakes of one size and islands that are all speckle`() {
        val identical = EarthLikeness.SizeDistribution.of(List(40) { 500.0 })
        val identicalComplaint = EarthLikeness.sizeDistributionComplaint(
            "IDENTICAL", "lake", identical, EarthLikeness.EARTH_LAKE_PARETO_EXPONENT,
            "Downing et al. 2006"
        )
        println(
            "EARTH CONTROL forty identical lakes: exponent" +
                " ${"%.3f".format(identical.exponent)} — $identicalComplaint"
        )
        assertTrue("forty lakes of one size passed the Pareto bar", identicalComplaint != null)

        // Areas laid out so that the count at or above `a` runs as `a^-1.2` exactly: the rank of
        // the body of area `a` is that count, so `a = largest * rank^(-1/1.2)`.
        val speckleExponent = 1.2
        val speckle = EarthLikeness.SizeDistribution.of(
            (1..40).map { rank -> 5000.0 * rank.toDouble().pow(-1.0 / speckleExponent) }
        )
        val speckleComplaint = EarthLikeness.sizeDistributionComplaint(
            "SPECKLE", "island", speckle, EarthLikeness.EARTH_ISLAND_KORCAK_EXPONENT, "Korcak 1938"
        )
        println(
            "EARTH CONTROL islands as speckle: exponent ${"%.3f".format(speckle.exponent)}" +
                " — $speckleComplaint"
        )
        assertTrue(
            "a set laid out to have a Korcak exponent of $speckleExponent measured" +
                " ${"%.3f".format(speckle.exponent)}, so the fit is not recovering the exponent",
            abs(speckle.exponent - speckleExponent) < 0.01
        )
        assertTrue("an island set that is all speckle passed the Korcak bar", speckleComplaint != null)

        val tooFew = EarthLikeness.SizeDistribution.of(List(4) { 500.0 * (it + 1) })
        val tooFewComplaint = EarthLikeness.sizeDistributionComplaint(
            "TOO-FEW", "island", tooFew, EarthLikeness.EARTH_ISLAND_KORCAK_EXPONENT, "Korcak 1938"
        )
        println("EARTH CONTROL four islands: $tooFewComplaint")
        assertTrue(
            "four islands were fitted a Korcak exponent rather than refused",
            tooFewComplaint != null
        )
    }

    /**
     * The two drainage-density clauses, one control each.
     *
     * Moglen, Eltahir and Bras (1998) put the maximum on the dry side and have it fall away on the
     * wet, so the first control is a world with every kilometre of channel in the humid class —
     * what a generator that cut channels wherever it rained would produce — and the second is one
     * whose maximum is dry, as it should be, but whose humid country is nonetheless better drained
     * than its semi-arid country, which is the fall-off missing.
     */
    @Test
    fun `the drainage-density clauses bite on a world whose channels are all in the wet`() {
        val classes = EarthLikeness.Aridity.entries.size
        val landKm2 = DoubleArray(classes) { 1_000_000.0 }
        val allWetChannelKm = DoubleArray(classes)
        allWetChannelKm[EarthLikeness.Aridity.HUMID.ordinal] = 10_000.0
        allWetChannelKm[EarthLikeness.Aridity.SEMI_ARID.ordinal] = 1_000.0
        val allWet = EarthLikeness.DrainageByAridity(allWetChannelKm, landKm2)
        val allWetComplaint = EarthLikeness.drainagePeakComplaint("ALL-WET", allWet)
        println("EARTH CONTROL channels only in the wet: peak ${allWet.peak()} — $allWetComplaint")
        assertTrue(
            "a world with ten times the channel density in its humid country as in its" +
                " semi-arid was said to peak in ${allWet.peak()}",
            allWet.peak() == EarthLikeness.Aridity.HUMID
        )
        assertTrue("the drainage-density bar accepted a peak in the humid class", allWetComplaint != null)

        val noFallOffChannelKm = DoubleArray(classes)
        noFallOffChannelKm[EarthLikeness.Aridity.ARID.ordinal] = 10_000.0
        noFallOffChannelKm[EarthLikeness.Aridity.SEMI_ARID.ordinal] = 2_000.0
        noFallOffChannelKm[EarthLikeness.Aridity.HUMID.ordinal] = 3_000.0
        val noFallOff = EarthLikeness.DrainageByAridity(noFallOffChannelKm, landKm2)
        val noFallOffComplaint = EarthLikeness.drainagePeakComplaint("NO-FALL-OFF", noFallOff)
        println(
            "EARTH CONTROL a dry peak with no fall-off: peak ${noFallOff.peak()}" +
                " — $noFallOffComplaint"
        )
        assertTrue(
            "the control's peak was meant to be dry so that the second clause is what bites," +
                " and it is ${noFallOff.peak()}",
            noFallOff.peak() == EarthLikeness.Aridity.ARID
        )
        assertTrue(
            "the drainage-density bar accepted humid country better drained than semi-arid",
            noFallOffComplaint != null
        )
    }
}
