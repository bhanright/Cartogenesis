package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.HydraulicErosion
import com.cartogenesis.worldgen.pipeline.IncisionWatch
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlockingWatchingIncision
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The incision is Braun and Willett's implicit update, and the stream-power law and not a limiter
 * sets every cut (`HydraulicErosion.incise`).
 *
 * Four claims, each shown failing on the explicit update chunk 3 left (the cut capped at half the
 * drop) or on a deliberately wrong update, with the figures in docs/DESIGN_LEDGER.md, Fix 3b:
 * - **the bounds and the eligibility**, on production's rounds and on a fixture: no cell the pass
 *   moves ends below the level it grades to or above where it stood, no river mouth below the sea,
 *   a cell with `F` over one loses more than half its drop, and a cell below its receiver when the
 *   round opened is cut once its receiver has been cut below it;
 * - **the update is the law's**, on a plane whose every term is known, at small `F` and large: each
 *   cell keeps `1 / (1 + F)` of its height over its receiver's new one, and the heights come out
 *   where a recursion in metres, written here from `K`, the clock and the catchment, puts them;
 * - **the steady state is the law's**, on synthetic landscapes run to balance under uniform uplift:
 *   the long profiles' concavity is `m / n = 0.5` and the channel steepness is `U / K`, across two
 *   rocks in one landscape, across two uplift rates, and across two rain zones, where the law puts
 *   it at `U / (K sqrt(P))` in the rain's normalised weight;
 * - **a knickpoint retreats as far north-south as east-west**, on the ground, in a round and in two
 *   rounds of half the time.
 */
class ImplicitIncisionTest {

    private companion object {
        /** Metres in a kilometre. */
        const val METRES_PER_KM = 1_000.0

        /**
         * How far a height may sit from where the arithmetic here puts it, in metres: ten of the
         * height field's float steps near the shoreline, each 16,000 m over 2^24, a little under a
         * millimetre. The update is carried out in double and rounded to the field's float once
         * per cell, and the rounding of a receiver carries into its donors damped by `F / (1 + F)`.
         */
        const val HEIGHT_TOLERANCE_METRES = 0.01

        /**
         * `F` below which a cell is read as the small-`F` case and above which as the large: a
         * tenth, where the implicit cut is within a tenth of the explicit law's, and ten, where it
         * is nine tenths of the whole drop.
         */
        const val SMALL_COURANT = 0.1
        const val LARGE_COURANT = 10.0

        /** The plane's fall, in metres per kilometre. */
        const val PLANE_SLOPE_METRES_PER_KM = 1.0

        /** Where the plane's sea begins below the shoreline, and how much deeper each cell of it lies. */
        const val SEA_SURFACE_DEPTH_METRES = 1f
        const val SEA_FLOOR_STEP_METRES = 0.01f

        /** Erodibilities that put the plane's cells under [SMALL_COURANT] and over [LARGE_COURANT]. */
        const val SMALL_ERODIBILITY_PER_YEAR = 1e-7f
        const val LARGE_ERODIBILITY_PER_YEAR = 5e-5f

        // ------------------------------------------------------------------ the steady state

        /**
         * The steady-state landscapes' grid: small enough that a few thousand rounds are a few
         * seconds, large enough that the longest rivers gather a few hundred cells.
         */
        const val STEADY_SIDE = 64

        /** Rock uplift for the steady-state landscapes, in millimetres a year, and the doubled rate. */
        const val STEADY_UPLIFT_MM_PER_YEAR = 0.1
        const val DOUBLED_UPLIFT_MM_PER_YEAR = 0.2

        /**
         * The balance a landscape has to reach to be read as steady: no land cell's height moves by
         * more than this share of one round's uplift from one round to the next. A thousandth of
         * the 34 m a round lifts is 3.4 cm, a few of the field's float steps, where the drops the
         * slopes are read off are tens of metres: each slope is within a few parts in ten thousand
         * of its steady value, well inside the tolerances the claims are held to.
         */
        const val STEADY_SHARE_OF_UPLIFT = 1e-3

        /** The most rounds a landscape is given to reach balance before the case fails. */
        const val MAX_STEADY_ROUNDS = 20_000

        /**
         * How many rounds the water is routed afresh before the network is held: long enough for
         * the drainage to organise (the first landscape balanced in about five hundred with the
         * routing live). Held after that because a handful of cells on the divides cycle for ever
         * between two receivers of nearly equal fall, under the facet routing and plain D8 alike,
         * and that cycle is the routing's, not the law's; the claim is the law's balance on a
         * network, which the held network gives.
         */
        const val ROUTED_ROUNDS = 1_000

        /**
         * The smallest catchment read as a channel, in cells. Every cell obeys the steady-state
         * law, so this only keeps the fit to the cells a long profile is made of.
         */
        const val CHANNEL_CELLS = 4f

        /**
         * How far the fitted concavity may sit from the law's 0.5, and a steepness ratio from the
         * law's: a hundredth, and a hundredth of the ratio. The steady state of the implicit update
         * is exactly the law's (`F (z - z_r) = U T` at every cell), so what is left is the balance
         * criterion above and the field's float steps on the smallest drops.
         */
        const val CONCAVITY_TOLERANCE = 0.01
        const val STEEPNESS_TOLERANCE = 0.01

        /** The law's concavity, `m / n` at `m = 0.5`, `n = 1`. */
        const val LAW_CONCAVITY = 0.5

        /** The two rocks' erodibility factors, and the two rain zones' annual rainfall ratio. */
        const val HARD_ROCK = 0.5f
        const val SOFT_ROCK = 2f
        const val WET_OVER_DRY_RAIN = 4f

        // ------------------------------------------------------------------ the knickpoint

        /**
         * The knickpoint profiles: the ground they run over in cell widths, where the break in
         * slope stands to begin with, the lower and upper slopes in metres per cell width, and `F`
         * along a row. A column's step is half a cell width, so `F` down a column is twice this.
         */
        const val PROFILE_CELL_WIDTHS = 40
        const val KNICK_CELL_WIDTHS = 12.0
        const val LOWER_FALL_METRES = 60.0
        const val UPPER_FALL_METRES = 20.0
        const val EAST_WEST_COURANT = 2.0

        /**
         * How far apart the two bearings' retreats may be where the knickpoint is read as a sharp
         * break of equal relief: a thousandth of a cell width. The implicit update carries a
         * profile's mean upstream at the law's celerity whatever the step, so the two agree to the
         * float steps of the heights.
         */
        const val RETREAT_TOLERANCE_CELL_WIDTHS = 1e-3

        /**
         * And where it is read as the point the slope is halfway between the reaches: one cell
         * width, the resolution of the coarser bearing, because that point is the median of the
         * scheme's smear and the smear's shape depends on `F`, which differs between the bearings.
         */
        const val MIDPOINT_TOLERANCE_CELL_WIDTHS = 1.0
    }

    // ================================================================== the bounds

    /**
     * Everything the pass reported of one round, held until the round's end so each cell can be
     * judged against the round's sea and the pass's finished heights.
     */
    private class Bounds(cellCount: Int, private val spanMetres: Float, private val pondDepth: Float) : IncisionWatch {
        private val reached = BooleanArray(cellCount)
        private val receiverOf = IntArray(cellCount)
        private val courantAt = FloatArray(cellCount)
        private val beforeAt = FloatArray(cellCount)
        private val baseBeforeAt = FloatArray(cellCount)
        private val baseAfterAt = FloatArray(cellCount)
        private val afterAt = FloatArray(cellCount)

        var cuts = 0
        var largeCourant = 0
        var mouths = 0
        var leftAlone = 0
        var receiversLowered = 0
        var madeEligible = 0
        var tooCloseToJudge = 0
        val violations = ArrayList<String>()

        override fun cut(
            round: Int, cell: Int, receiver: Int, courantNumber: Float, before: Float, baseBefore: Float,
            baseAfter: Float, after: Float
        ) {
            reached[cell] = true
            receiverOf[cell] = receiver
            courantAt[cell] = courantNumber
            beforeAt[cell] = before
            baseBeforeAt[cell] = baseBefore
            baseAfterAt[cell] = baseAfter
            afterAt[cell] = after
        }

        var excludedCells = 0

        /**
         * A cell the head criterion left out: the pass must not have moved it, and the law's own
         * clauses above are not asked of it.
         */
        override fun excluded(round: Int, cell: Int, before: Float, after: Float) {
            excludedCells++
            if (after != before && violations.size < 20) {
                violations.add("round $round cell $cell was left out of the incision and moved from ${before * spanMetres} m to ${after * spanMetres} m")
            }
        }

        override fun incised(
            round: Int, isLand: BooleanArray, directions: IntArray, ground: FloatArray, relative: FloatArray,
            discharge: FloatArray, landCells: Float, landRange: Float, shorelineHeight: Float, surface: FloatArray
        ) {
            for (cell in reached.indices) {
                if (!reached[cell]) continue
                reached[cell] = false
                val receiver = receiverOf[cell]
                val before = beforeAt[cell]
                val after = afterAt[cell]
                val base = baseAt(receiver, isLand, directions, ground, relative, landRange, shorelineHeight, surface)
                fun wrong(what: String) {
                    if (violations.size < 20) {
                        violations.add(
                            "round $round cell $cell $what: %.3f m to %.3f m over a base of %.3f m, F %.2f".format(
                                before * spanMetres, after * spanMetres, base * spanMetres, courantAt[cell]
                            )
                        )
                    }
                }
                // The watch is read against the field itself, so a report cannot hide the pass.
                if (surface[cell] != after) wrong("reported ${after * spanMetres} m where the field holds ${surface[cell] * spanMetres}")
                if (baseAfterAt[cell] != base) wrong("graded to ${baseAfterAt[cell] * spanMetres} m where its base is")
                if (before <= base) {
                    leftAlone++
                    if (after != before) wrong("moved though it stood at or below its base")
                    continue
                }
                if (after < base) wrong("cut below its base")
                if (after > before) wrong("raised")
                if (after < before) cuts++
                if (!isLand[receiver]) {
                    mouths++
                    if (after < shorelineHeight) wrong("cut below the shoreline")
                }
                // More than half the drop wherever `F` is over one, `F / (1 + F)` being over a
                // half there, held strictly. Where the law's cut clears the half by less than two
                // float steps of the height, the rounding of one cell's result can land either
                // side of it, so those cells are counted and not judged.
                if (courantAt[cell] > 1f) {
                    largeCourant++
                    val courant = courantAt[cell].toDouble()
                    val drop = before.toDouble() - base.toDouble()
                    val margin = (courant / (1.0 + courant) - 0.5) * drop
                    if (margin < 2.0 * Math.ulp(before)) tooCloseToJudge++
                    else if (before.toDouble() - after.toDouble() <= 0.5 * drop) wrong("cut no more than half its drop at F over one")
                }
                if (base < baseBeforeAt[cell]) {
                    receiversLowered++
                    if (before <= baseBeforeAt[cell]) {
                        madeEligible++
                        if (after >= before) wrong("left standing after its receiver was cut below it")
                    }
                }
            }
        }

        /**
         * The level a cell draining into [receiver] grades to, found here from the pass's result
         * and not taken from its report: the shoreline for the sea; for a receiver under a filled
         * basin's water, the lake as it now stands, the lowest of the filled levels on the way down
         * through the lake and of what the lake finally spills onto (the sea's surface, or the new
         * ground of the first dry cell), or the receiver's new ground if that is higher; and
         * otherwise the receiver's new ground.
         */
        private fun baseAt(
            receiver: Int, isLand: BooleanArray, directions: IntArray, ground: FloatArray, relative: FloatArray,
            landRange: Float, shorelineHeight: Float, surface: FloatArray
        ): Float {
            if (!isLand[receiver]) return shorelineHeight
            fun ponded(cell: Int) = ground[cell] - relative[cell] > pondDepth
            if (!ponded(receiver)) return surface[receiver]
            var water = Float.POSITIVE_INFINITY
            var cell = receiver
            while (true) {
                water = minOf(water, shorelineHeight + ground[cell] * landRange)
                val next = directions[cell]
                if (next < 0) break
                if (!isLand[next]) { water = minOf(water, shorelineHeight); break }
                if (!ponded(next)) { water = minOf(water, surface[next]); break }
                cell = next
            }
            return maxOf(surface[receiver], water)
        }
    }

    /**
     * No cell the pass moves ends below the level it grades to or above where it stood, no river
     * mouth ends below the shoreline, a cell at or below its base is left alone, every cell with
     * `F` over one loses more than half its drop, and a cell its receiver's cut left above it is
     * cut. Over the twelve rounds of seed 42 at 512, through the watch, each report checked against
     * the field the pass left.
     *
     * The bounds alone pass on the capped explicit update and on a pass that cuts nothing, so the
     * case also asks for the part only the law gives: more than half the drop wherever `F` is over
     * one. On the capped update that clause fails on every large-`F` cell; on a pass that cuts
     * nothing the counts it first asserts are nought.
     */
    @Test
    fun `the pass keeps its bounds and cuts past half the drop where F is over one`() {
        val config = WorldGenConfig(seed = 42L, width = 512, height = 512)
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val bounds = Bounds(config.width * config.height, config.scale.reliefSpanMetres, HydraulicErosion.Rates(config).pondDepth)
        erodeBlockingWatchingIncision(config, plates.height, plates.upliftRateMmPerYear, bounds)
        println(
            "IMPLICIT bounds seed 42@512 over the rounds: ${bounds.cuts} cuts, ${bounds.largeCourant} at F over one, " +
                "${bounds.mouths} mouths, ${bounds.leftAlone} left alone at or below their base, " +
                "${bounds.receiversLowered} whose receiver the pass lowered and ${bounds.madeEligible} of them cut " +
                "only because it was; ${bounds.tooCloseToJudge} at F over one too close to half their drop to judge; " +
                "${bounds.violations.size} violations"
        )
        assertTrue(bounds.cuts > 0, "the pass cut nothing, so this case saw nothing to test")
        assertTrue(bounds.largeCourant > 0, "no cell had F over one, so the law's own clause saw nothing")
        assertTrue(bounds.mouths > 0, "no river mouth was reached")
        assertTrue(bounds.leftAlone > 0, "no cell stood at or below its base, so the eligibility rule saw nothing")
        assertTrue(bounds.madeEligible > 0, "no cell was made eligible by its receiver's cut")
        assertTrue(bounds.violations.isEmpty(), "the pass broke its bounds: ${bounds.violations}")
    }

    /**
     * With the head-criterion experiment on (`ErosionConfig.incisionNeedsChannelHead`), the cells
     * it leaves out are not moved, and every clause above still holds on the cells it cuts. Seed 42
     * at 512 again, through the same watch, which is told which cells were left out.
     */
    @Test
    fun `with the head criterion on, the cells it leaves out stand and the rest keep the bounds`() {
        val base = WorldGenConfig(seed = 42L, width = 512, height = 512)
        val config = base.copy(erosion = base.erosion.copy(incisionNeedsChannelHead = true))
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val bounds = Bounds(config.width * config.height, config.scale.reliefSpanMetres, HydraulicErosion.Rates(config).pondDepth)
        erodeBlockingWatchingIncision(config, plates.height, plates.upliftRateMmPerYear, bounds)
        println(
            "IMPLICIT with the head criterion seed 42@512: ${bounds.cuts} cuts, ${bounds.excludedCells} cells left out, " +
                "${bounds.largeCourant} at F over one; ${bounds.violations.size} violations"
        )
        assertTrue(bounds.excludedCells > 0, "the criterion left nothing out, so this case saw nothing to test")
        assertTrue(bounds.cuts > 0, "the pass cut nothing")
        assertTrue(bounds.violations.isEmpty(), "the pass broke its bounds: ${bounds.violations}")
    }

    /**
     * On a fixture of three cells in a row running into the sea, the middle one standing ten
     * metres below the cell it drains into: that receiver is cut in the pass to below it, and then
     * it is cut too, by exactly `F / (1 + F)` of its drop to the receiver's new height.
     *
     * Eligibility is judged against the receiver's *new* height. A pass that judged it against the
     * old one leaves the middle cell where it stood, and fails here.
     */
    @Test
    fun `a cell below its receiver is cut once its receiver is cut below it`() {
        val config = WorldGenConfig(seed = 1L, width = 16, height = 16)
        val scale = config.scale
        val rates = HydraulicErosion.Rates(config)
        val cellCount = config.width * config.height
        val shoreline = scale.fieldAtAltitude(0f)
        val landRange = scale.landHalfOfField
        val row = 8 * config.width
        val sea = row + 2
        val mouth = row + 3
        val below = row + 4
        val head = row + 5
        val isLand = BooleanArray(cellCount)
        val directions = IntArray(cellCount) { -1 }
        val surface = FloatArray(cellCount) { scale.fieldAtAltitude(-SEA_SURFACE_DEPTH_METRES) }
        for ((cell, metres, receiver) in listOf(Triple(mouth, 100f, sea), Triple(below, 90f, mouth), Triple(head, 300f, below))) {
            isLand[cell] = true
            directions[cell] = receiver
            surface[cell] = scale.fieldAtAltitude(metres)
        }
        val relative = FloatArray(cellCount) { (surface[it] - shoreline) / landRange }
        // One land cell, so a cell's share is its discharge, chosen to give each cell its `F` on
        // a step along a row: nine at the mouth, one below it, one at the head.
        val wanted = mapOf(mouth to 9.0, below to 1.0, head to 1.0)
        val discharge = FloatArray(cellCount)
        for ((cell, courant) in wanted) discharge[cell] = (courant / rates.courantCoefficient).let { (it * it).toFloat() }
        val before = surface.copyOf()
        val courantSeen = HashMap<Int, Float>()
        val watch = object : IncisionWatch {
            override fun cut(
                round: Int, cell: Int, receiver: Int, courantNumber: Float, before: Float, baseBefore: Float,
                baseAfter: Float, after: Float
            ) {
                courantSeen[cell] = courantNumber
            }

            override fun incised(
                round: Int, isLand: BooleanArray, directions: IntArray, ground: FloatArray, relative: FloatArray,
                discharge: FloatArray, landCells: Float, landRange: Float, shorelineHeight: Float, surface: FloatArray
            ) = Unit
        }
        HydraulicErosion.incise(
            rates, config.width, intArrayOf(head, below, mouth), directions, isLand, relative, relative, discharge,
            landCells = 1f, landRange = landRange, shorelineHeight = shoreline,
            erodibility = FloatArray(cellCount) { 1f }, surfaceOf = surface, incisedAt = null, watch = watch
        )
        fun metres(field: Float) = scale.altitudeAtField(field).toDouble()
        val mouthAfter = metres(surface[mouth])
        val belowBefore = metres(before[below])
        val belowAfter = metres(surface[below])
        val courant = courantSeen.getValue(below).toDouble()
        val expected = mouthAfter + (belowBefore - mouthAfter) / (1.0 + courant)
        println(
            "IMPLICIT eligibility: the mouth %.2f m to %.2f m at F %.2f; the cell below it %.2f m to %.2f m at F %.2f, the law's %.2f m"
                .format(metres(before[mouth]), mouthAfter, courantSeen.getValue(mouth), belowBefore, belowAfter, courant, expected)
        )
        assertTrue(before[below] < before[mouth], "the fixture's middle cell does not stand below its receiver")
        assertTrue(mouthAfter < belowBefore, "the receiver was not cut below the middle cell, so this saw nothing to test")
        assertTrue(
            abs(belowAfter - expected) <= HEIGHT_TOLERANCE_METRES,
            "the middle cell ended at %.3f m where its receiver's new height of %.3f m and F %.3f put it at %.3f m"
                .format(belowAfter, mouthAfter, courant, expected)
        )
    }

    /** Where the draining-lake fixture's three cells ended, in metres. */
    private class Drained(val outlet: Double, val bed: Double, val inflow: Double)

    /**
     * Three cells in a row running into the sea: an outlet at 100 m, a lake's bed at [bedMetres]
     * whose water stands at the outlet's 100 m, and an inflow at 120 m draining into the lake. The
     * outlet is cut at [outletCourant]; the bed and the inflow at `F` nine.
     */
    private fun drainLake(outletCourant: Double, bedMetres: Float): Drained {
        val config = WorldGenConfig(seed = 1L, width = 16, height = 16)
        val scale = config.scale
        val rates = HydraulicErosion.Rates(config)
        val cellCount = config.width * config.height
        val shoreline = scale.fieldAtAltitude(0f)
        val landRange = scale.landHalfOfField
        val row = 8 * config.width
        val sea = row + 2
        val outlet = row + 3
        val bed = row + 4
        val inflow = row + 5
        val isLand = BooleanArray(cellCount)
        val directions = IntArray(cellCount) { -1 }
        val surface = FloatArray(cellCount) { scale.fieldAtAltitude(-SEA_SURFACE_DEPTH_METRES) }
        for ((cell, metres, receiver) in listOf(Triple(outlet, 100f, sea), Triple(bed, bedMetres, outlet), Triple(inflow, 120f, bed))) {
            isLand[cell] = true
            directions[cell] = receiver
            surface[cell] = scale.fieldAtAltitude(metres)
        }
        val relative = FloatArray(cellCount) { (surface[it] - shoreline) / landRange }
        // The filled surface: the bed's water stands at the outlet's height.
        val filled = relative.copyOf()
        filled[bed] = relative[outlet]
        assertTrue(filled[bed] - relative[bed] > rates.pondDepth, "the fixture's lake is too shallow to stand as water")
        val wanted = mapOf(outlet to outletCourant, bed to 9.0, inflow to 9.0)
        val discharge = FloatArray(cellCount)
        for ((cell, courant) in wanted) discharge[cell] = (courant / rates.courantCoefficient).let { (it * it).toFloat() }
        HydraulicErosion.incise(
            rates, config.width, intArrayOf(inflow, bed, outlet), directions, isLand, filled, relative, discharge,
            landCells = 1f, landRange = landRange, shorelineHeight = shoreline,
            erodibility = FloatArray(cellCount) { 1f }, surfaceOf = surface, incisedAt = null
        )
        fun metres(cell: Int) = scale.altitudeAtField(surface[cell]).toDouble()
        return Drained(metres(outlet), metres(bed), metres(inflow))
    }

    /**
     * A lake falls with its outlet: its surface for the pass is the lower of its filled level and
     * its outlet's new height. A bed still under that surface is neither cut nor raised, a bed the
     * falling water uncovers is graded like any other cell, and an inflow grades to the surface as
     * it now stands, not as it stood when the round opened.
     *
     * Two cases. The outlet cut from 100 m to 10 m at `F` nine drains the lake past its 70 m bed:
     * the bed is cut to 16 m and the inflow to 26.4 m. The outlet cut to 80 m at `F` a quarter
     * leaves the lake standing over its 50 m bed at 80 m: the bed stays, and the inflow grades to
     * 80 m, to 84 m. A pass that holds the lake at its filled level for the whole pass grades the
     * inflow to 100 m in both, to 102 m, and fails here.
     */
    @Test
    fun `a lake falls with its outlet and its inflow grades to the lowered water`() {
        val drained = drainLake(outletCourant = 9.0, bedMetres = 70f)
        val standing = drainLake(outletCourant = 0.25, bedMetres = 50f)
        println(
            "IMPLICIT draining lake: drained, the outlet to %.2f m, the bed to %.2f m, the inflow to %.2f m; standing, the outlet to %.2f m, the bed %.2f m, the inflow to %.2f m"
                .format(drained.outlet, drained.bed, drained.inflow, standing.outlet, standing.bed, standing.inflow)
        )
        for ((what, got, want) in listOf(
            Triple("the drained lake's outlet", drained.outlet, 10.0),
            Triple("the drained lake's uncovered bed", drained.bed, 16.0),
            Triple("the drained lake's inflow", drained.inflow, 26.4),
            Triple("the standing lake's outlet", standing.outlet, 80.0),
            Triple("the standing lake's submerged bed", standing.bed, 50.0),
            Triple("the standing lake's inflow", standing.inflow, 84.0)
        )) {
            assertTrue(abs(got - want) <= HEIGHT_TOLERANCE_METRES, "$what ended at %.3f m where the law puts it at %.3f m".format(got, want))
        }
    }

    // ================================================================== the law

    /** One production round over the plane, and where the law puts every cell of it. */
    private class PlaneRound(
        val courant: DoubleArray,
        val beforeMetres: DoubleArray,
        val afterMetres: DoubleArray,
        val predictedMetres: DoubleArray,
        val baseAfterMetres: DoubleArray
    )

    /**
     * A plane of land 32 cells wide falling due west at [PLANE_SLOPE_METRES_PER_KM] into a shallow
     * sea, one production round with nothing but the incision moving it (as `ErosionUnitsTest`'s
     * clock case), at [erodibilityPerYear]. Read along one row, from the foot to the crest: the crest
     * drains east into the sea behind it, and every other cell due west, so a cell `n` below the
     * crest gathers `n` cells' water.
     *
     * The prediction is written here in metres from `K`, the clock, the catchment the stage
     * defines (the configured land's area over its count of cells) and the cell's width, and
     * owes nothing to the stage's coefficients: from the sea upward, each cell's new height is
     * `(z + F z_r') / (1 + F)` with `F = K T sqrt(A) / L`.
     */
    private fun planeRound(erodibilityPerYear: Float): PlaneRound {
        val cellsAcross = 128
        val base = WorldGenConfig(seed = 7L, width = cellsAcross, height = cellsAcross)
        val landColumns = cellsAcross / 4
        val config = base.copy(
            seaLevel = 1f - landColumns.toFloat() / cellsAcross,
            erosion = base.erosion.copy(
                hydraulicRounds = 1,
                climateFeed = false,
                deposition = false,
                outletIncision = false,
                bedrockErodibilityPerYear = erodibilityPerYear
            ),
            isostasy = base.isostasy.copy(flexure = false),
            sea = base.sea.copy(lowstandMetres = 0f)
        )
        val scale = config.scale
        val firstLandColumn = (cellsAcross - landColumns) / 2
        val crest = firstLandColumn + landColumns - 1
        val cellWidthMetres = config.cellWidthKm * METRES_PER_KM
        val fallPerColumnMetres = PLANE_SLOPE_METRES_PER_KM / METRES_PER_KM * cellWidthMetres
        val ground = FloatField(cellsAcross, config.height)
        for (row in 0 until config.height) {
            for (column in 0 until cellsAcross) {
                val stepsUp = column - firstLandColumn + 1
                ground.data[row * cellsAcross + column] =
                    if (stepsUp in 1..landColumns) scale.fieldAtAltitude((stepsUp * fallPerColumnMetres).toFloat())
                    // Every sea cell at its own depth, or the percentile ties.
                    else scale.fieldAtAltitude(-SEA_SURFACE_DEPTH_METRES - (row * cellsAcross + column) * SEA_FLOOR_STEP_METRES)
            }
        }
        val cut = SeaLevelStage.percentileCut(ground, config.seaLevel, scale)
        val shorelineMetres = scale.altitudeAtField(cut.shorelineHeight).toDouble()
        val before = ground.data.copyOf()
        val after = runBlocking { HydraulicErosion.apply(config, ground.copy(), config.seaLevel) { it } }

        val row = config.height / 2
        val landAreaKm2 = (1.0 - config.seaLevel.toDouble()) * scale.worldAreaKm2
        val cellAreaSquareMetres = landAreaKm2 / cut.landCellCount * METRES_PER_KM * METRES_PER_KM
        val years = scale.yearsPerHydraulicRound
        val columns = firstLandColumn..crest
        val courant = DoubleArray(columns.count())
        val beforeMetres = DoubleArray(columns.count())
        val afterMetres = DoubleArray(columns.count())
        val predicted = DoubleArray(columns.count())
        val baseAfter = DoubleArray(columns.count())
        var receiverAfter = shorelineMetres
        for ((index, column) in columns.withIndex()) {
            val cell = row * cellsAcross + column
            val catchmentCells = if (column == crest) 1 else crest - column
            courant[index] = erodibilityPerYear.toDouble() * years * sqrt(catchmentCells * cellAreaSquareMetres) / cellWidthMetres
            beforeMetres[index] = scale.altitudeAtField(before[cell]).toDouble()
            afterMetres[index] = scale.altitudeAtField(after.data[cell]).toDouble()
            // The crest's receiver is the sea to its east; every other cell's is the one to its west.
            val grade = if (column == crest) shorelineMetres else receiverAfter
            baseAfter[index] = if (column == crest) shorelineMetres else (if (index == 0) shorelineMetres else afterMetres[index - 1])
            predicted[index] = (beforeMetres[index] + courant[index] * grade) / (1.0 + courant[index])
            receiverAfter = predicted[index]
        }
        return PlaneRound(courant, beforeMetres, afterMetres, predicted, baseAfter)
    }

    /**
     * One production round on the plane removes exactly `F / (1 + F)` of each cell's drop to its
     * receiver's new height, and leaves every cell where a recursion written here in metres puts
     * it, at small `F` (under [SMALL_COURANT]), at the stock erodibility and at large `F` (over
     * [LARGE_COURANT]).
     *
     * Two readings, and the second does not use the first: the fraction of the realised drop the
     * cell lost, read off the field the round left; and the heights themselves, against the
     * recursion. A capped update fails both at large `F`, where it takes half the drop; the
     * explicit law without a cap fails the second at every `F`, taking `F` times the drop.
     */
    @Test
    fun `one round removes F over one plus F of the drop, at small F and at large`() {
        val readings = ArrayList<String>()
        var smallSeen = 0
        var largeSeen = 0
        val failures = ArrayList<String>()
        for (erodibility in listOf(SMALL_ERODIBILITY_PER_YEAR, 1e-6f, LARGE_ERODIBILITY_PER_YEAR)) {
            val round = planeRound(erodibility)
            for (index in round.courant.indices) {
                val courant = round.courant[index]
                if (courant < SMALL_COURANT) smallSeen++
                if (courant > LARGE_COURANT) largeSeen++
                val drop = round.beforeMetres[index] - round.baseAfterMetres[index]
                val removed = round.beforeMetres[index] - round.afterMetres[index]
                val lawRemoves = courant / (1.0 + courant) * drop
                if (abs(removed - lawRemoves) > HEIGHT_TOLERANCE_METRES) {
                    failures.add("K %.0e cell %d: removed %.3f m of a %.3f m drop, the law %.3f m at F %.3f".format(
                        erodibility, index, removed, drop, lawRemoves, courant))
                }
                if (abs(round.afterMetres[index] - round.predictedMetres[index]) > HEIGHT_TOLERANCE_METRES) {
                    failures.add("K %.0e cell %d: ended at %.3f m, the recursion %.3f m at F %.3f".format(
                        erodibility, index, round.afterMetres[index], round.predictedMetres[index], courant))
                }
            }
            val foot = 0
            val top = round.courant.size - 2
            readings.add(
                "K %.0e: F %.3f at the foot, where %.2f m of %.2f m went, and %.3f at the top, where %.2f m of %.2f m went".format(
                    erodibility, round.courant[foot], round.beforeMetres[foot] - round.afterMetres[foot],
                    round.beforeMetres[foot] - round.baseAfterMetres[foot], round.courant[top],
                    round.beforeMetres[top] - round.afterMetres[top], round.beforeMetres[top] - round.baseAfterMetres[top]
                )
            )
        }
        println("IMPLICIT law on the plane: " + readings.joinToString("; "))
        assertTrue(smallSeen > 0, "no cell of the plane had F under $SMALL_COURANT")
        assertTrue(largeSeen > 0, "no cell of the plane had F over $LARGE_COURANT")
        assertTrue(failures.isEmpty(), "${failures.size} cells off the law: ${failures.take(8)}")
    }

    // ================================================================== the steady state

    /** A landscape run to balance, and what its channels read. */
    private class Steady(val rounds: Int, val concavity: Map<String, Double>, val steepness: Map<String, Double>)

    /**
     * A synthetic island [STEADY_SIDE] cells square, lifted at [upliftMmPerYear] every round and
     * cut by production's routing and production's [HydraulicErosion.incise] until no land cell
     * moves by more than [STEADY_SHARE_OF_UPLIFT] of a round's uplift, with no thermal pass, no
     * deposition and no notch. The routing is taken afresh for [ROUTED_ROUNDS] rounds and then
     * held. [zoneOf] names each cell's zone, [erodibilityOf] and [rainOf] its
     * rock and its rain; the rain is weighted as the stage weights it, over its own land mean.
     *
     * Read per zone over the channels whose whole catchment lies in that zone: the concavity,
     * `theta` in `S = k_s A^-theta` fitted by least squares on the logarithms, and the median
     * steepness `S sqrt(A)`, in metres, the catchment in square metres as the stage defines it (the
     * configured land's area times the share) and the slope on the ground.
     */
    private fun steadyState(
        upliftMmPerYear: Double,
        zoneOf: (row: Int) -> String,
        erodibilityOf: (String) -> Float,
        rainOf: (String) -> Float
    ): Steady {
        val base = WorldGenConfig(seed = 11L, width = STEADY_SIDE, height = STEADY_SIDE)
        val config = base.copy(erosion = base.erosion.copy(climateFeed = false, deposition = false, outletIncision = false))
        val side = STEADY_SIDE
        val cellCount = side * side
        val scale = config.scale
        val rates = HydraulicErosion.Rates(config)
        val landRange = scale.landHalfOfField
        val shoreline = scale.fieldAtAltitude(0f)
        val isLand = BooleanArray(cellCount) { cell ->
            val row = cell / side
            val column = cell % side
            row in 2 until side - 2 && column in 3 until side - 3
        }
        val landCells = isLand.count { it }
        val zones = Array(cellCount) { zoneOf(it / side) }
        val erodibility = FloatArray(cellCount) { erodibilityOf(zones[it]) }
        var rainSum = 0.0
        for (cell in 0 until cellCount) if (isLand[cell]) rainSum += rainOf(zones[cell])
        val rainMean = (rainSum / landCells).toFloat()
        val weight = FloatArray(cellCount) { rainOf(zones[it]) / rainMean }
        val upliftPerRoundField =
            scale.fieldShareOfMetres((upliftMmPerYear / METRES_PER_KM * scale.yearsPerHydraulicRound).toFloat())
        // A metre above the sea and a few metres of fixed roughness, so the first routing has
        // somewhere to go that is not the grid's own order.
        val surface = FloatArray(cellCount) { cell ->
            if (!isLand[cell]) scale.fieldAtAltitude(-100f)
            else scale.fieldAtAltitude(1f + 5f * (((cell * 2654435761L) ushr 7) % 1000L).toFloat() / 1000f)
        }
        val relative = FloatField(side, side)
        var rounds = 0
        var discharge = FloatArray(0)
        var directions = IntArray(0)
        var order = IntArray(0)
        while (true) {
            rounds++
            assertTrue(rounds <= MAX_STEADY_ROUNDS, "the landscape did not reach balance in $MAX_STEADY_ROUNDS rounds")
            val previous = surface.copyOf()
            for (cell in 0 until cellCount) if (isLand[cell]) surface[cell] += upliftPerRoundField
            for (cell in 0 until cellCount) {
                relative.data[cell] =
                    if (isLand[cell]) (surface[cell] - shoreline) / landRange
                    else (surface[cell] - shoreline) / scale.seaHalfOfField
            }
            val filled = FlowRouting.fillDepressions(side, side, isLand, relative)
            if (rounds <= ROUTED_ROUNDS) {
                directions = FlowRouting.flowDirections(
                    side, side, isLand, relative, filled, config.seed, config.cellHeightInCellWidths,
                    config.facetRouting, config.flatPotential
                )
                discharge = FlowRouting.accumulate(side, side, isLand, filled, directions, landCells) { weight[it] }.data
                order = FlowRouting.drainageOrder(side, side, isLand, directions, landCells)
            }
            HydraulicErosion.incise(
                rates, side, order, directions, isLand, filled.data, relative.data, discharge, landCells.toFloat(),
                landRange, shoreline, erodibility, surface, incisedAt = null
            )
            var largest = 0f
            for (cell in 0 until cellCount) if (isLand[cell]) largest = maxOf(largest, abs(surface[cell] - previous[cell]))
            if (rounds > ROUTED_ROUNDS && largest < STEADY_SHARE_OF_UPLIFT * upliftPerRoundField) break
        }

        // Which catchments lie wholly in one zone: the cells of each zone accumulated alone.
        val cellsUpstream = FlowRouting.accumulate(side, side, isLand, relative, directions, landCells) { 1f }.data
        val landAreaSquareMetres = (1.0 - config.seaLevel) * scale.worldAreaKm2 * METRES_PER_KM * METRES_PER_KM
        val cellWidthMetres = config.cellWidthKm * METRES_PER_KM
        val concavity = HashMap<String, Double>()
        val steepness = HashMap<String, Double>()
        for (zone in zones.toSet()) {
            val inZone = FlowRouting.accumulate(side, side, isLand, relative, directions, landCells) {
                if (zones[it] == zone) 1f else 0f
            }.data
            val logArea = ArrayList<Double>()
            val logSlope = ArrayList<Double>()
            val steep = ArrayList<Double>()
            for (cell in 0 until cellCount) {
                if (!isLand[cell] || zones[cell] != zone) continue
                if (cellsUpstream[cell] < CHANNEL_CELLS || inZone[cell] != cellsUpstream[cell]) continue
                val receiver = directions[cell]
                if (receiver < 0) continue
                val grade = if (isLand[receiver]) surface[receiver] else shoreline
                val fallMetres = (surface[cell] - grade).toDouble() * scale.reliefSpanMetres
                if (fallMetres <= 0.0) continue
                val slope = fallMetres / (config.groundSteps.between(cell, receiver, side) * cellWidthMetres)
                // The catchment the law is spent on: the unweighted cells' share of the land, times
                // the configured land's area, as the stage defines it.
                val areaSquareMetres = cellsUpstream[cell].toDouble() / landCells * landAreaSquareMetres
                logArea.add(ln(areaSquareMetres))
                logSlope.add(ln(slope))
                steep.add(slope * sqrt(areaSquareMetres))
            }
            assertTrue(logArea.size > 50, "zone $zone has only ${logArea.size} channel cells to read")
            concavity[zone] = -leastSquaresSlope(logArea, logSlope)
            steep.sort()
            steepness[zone] = steep[steep.size / 2]
        }
        return Steady(rounds, concavity, steepness)
    }

    private fun leastSquaresSlope(x: List<Double>, y: List<Double>): Double {
        val meanX = x.average()
        val meanY = y.average()
        var covariance = 0.0
        var variance = 0.0
        for (index in x.indices) {
            covariance += (x[index] - meanX) * (y[index] - meanY)
            variance += (x[index] - meanX) * (x[index] - meanX)
        }
        return covariance / variance
    }

    /**
     * Under uniform uplift and uniform rain, the long profiles' concavity at balance is the law's
     * `m / n = 0.5`, and the channel steepness is `U / (K e)` in each of two rocks in one landscape,
     * and doubles when the uplift doubles.
     *
     * The implicit update's steady state is the law's exactly: a cell holds its height when its
     * round's cut equals its round's uplift, `F (z - z_r) = U T`, which is `S = U / (K e sqrt(A))`.
     * The explicit update capped at half the drop balances at a slope of twice the uplift over the
     * step wherever the cap binds, whatever the catchment, and fails the concavity.
     */
    @Test
    fun `at balance the profiles are the law's, across rock and across uplift`() {
        val zoneOf = { row: Int -> if (row < STEADY_SIDE / 2) "hard" else "soft" }
        val rockOf = { zone: String -> if (zone == "hard") HARD_ROCK else SOFT_ROCK }
        val erodibility = WorldGenConfig().erosion.bedrockErodibilityPerYear.toDouble()
        val failures = ArrayList<String>()
        val results = HashMap<Double, Steady>()
        for (uplift in listOf(STEADY_UPLIFT_MM_PER_YEAR, DOUBLED_UPLIFT_MM_PER_YEAR)) {
            val steady = steadyState(uplift, zoneOf, rockOf) { 1f }
            results[uplift] = steady
            for (zone in listOf("hard", "soft")) {
                val theta = steady.concavity.getValue(zone)
                val law = uplift / METRES_PER_KM / (erodibility * rockOf(zone))
                val read = steady.steepness.getValue(zone)
                println(
                    "IMPLICIT steady U %.1f mm/yr, %s rock (e %.1f), balanced in %d rounds: concavity %.4f, steepness %.1f m against U/(K e) %.1f m"
                        .format(uplift, zone, rockOf(zone), steady.rounds, theta, read, law)
                )
                if (abs(theta - LAW_CONCAVITY) > CONCAVITY_TOLERANCE) failures.add("U $uplift $zone concavity %.4f".format(theta))
                if (abs(read / law - 1.0) > STEEPNESS_TOLERANCE) failures.add("U $uplift $zone steepness %.1f m of %.1f".format(read, law))
            }
        }
        for (zone in listOf("hard", "soft")) {
            val ratio = results.getValue(DOUBLED_UPLIFT_MM_PER_YEAR).steepness.getValue(zone) /
                results.getValue(STEADY_UPLIFT_MM_PER_YEAR).steepness.getValue(zone)
            println("IMPLICIT steady $zone rock: doubling the uplift multiplied the steepness by %.4f".format(ratio))
            if (abs(ratio / 2.0 - 1.0) > STEEPNESS_TOLERANCE) failures.add("$zone uplift ratio %.4f".format(ratio))
        }
        val rockRatio = results.getValue(STEADY_UPLIFT_MM_PER_YEAR).let { it.steepness.getValue("hard") / it.steepness.getValue("soft") }
        println("IMPLICIT steady: hard rock over soft %.4f, the law's %.4f".format(rockRatio, SOFT_ROCK / HARD_ROCK))
        if (abs(rockRatio / (SOFT_ROCK / HARD_ROCK) - 1.0) > STEEPNESS_TOLERANCE) failures.add("rock ratio %.4f".format(rockRatio))
        assertTrue(failures.isEmpty(), "the balance is not the law's: $failures")
    }

    /**
     * Two rain zones in one landscape, the wet one taking [WET_OVER_DRY_RAIN] times the dry one's
     * rain, under uniform uplift and rock.
     *
     * What the law predicts there: the stage routes discharge, the rain's weight over the land's
     * mean summed over the catchment, so a channel's `sqrt(A)` becomes `sqrt(A * P)`, with `P` the
     * catchment's mean weight, and at balance `S sqrt(A) = U / (K sqrt(P))`. The concavity inside a
     * zone is unchanged, 0.5, and the steepness falls as the square root of the rain: the wet zone
     * over the dry is `1 / sqrt(4)`, a half. Because the weight is normalised over the land, a world
     * made uniformly wetter is cut exactly as it was: only where the rain falls matters, not how
     * much (see `HydraulicErosion.normaliseOverLand`).
     */
    @Test
    fun `at balance the steepness falls as the square root of the rain`() {
        val zoneOf = { row: Int -> if (row < STEADY_SIDE / 2) "wet" else "dry" }
        val rainOf = { zone: String -> if (zone == "wet") WET_OVER_DRY_RAIN else 1f }
        val steady = steadyState(STEADY_UPLIFT_MM_PER_YEAR, zoneOf, { 1f }, rainOf)
        val erodibility = WorldGenConfig().erosion.bedrockErodibilityPerYear.toDouble()
        // The weights as the stage takes them, over the land's mean: the two zones are near
        // enough equal in land that the mean is their average, but it is computed, not assumed.
        val failures = ArrayList<String>()
        val wet = steady.steepness.getValue("wet")
        val dry = steady.steepness.getValue("dry")
        for (zone in listOf("wet", "dry")) {
            val theta = steady.concavity.getValue(zone)
            println("IMPLICIT steady rain, $zone zone, balanced in ${steady.rounds} rounds: concavity %.4f, steepness %.1f m".format(theta, steady.steepness.getValue(zone)))
            if (abs(theta - LAW_CONCAVITY) > CONCAVITY_TOLERANCE) failures.add("$zone concavity %.4f".format(theta))
        }
        val law = 1.0 / sqrt(WET_OVER_DRY_RAIN.toDouble())
        println("IMPLICIT steady rain: wet over dry steepness %.4f, the law's %.4f; U/K alone %.1f m".format(
            wet / dry, law, STEADY_UPLIFT_MM_PER_YEAR / METRES_PER_KM / erodibility))
        if (abs((wet / dry) / law - 1.0) > STEEPNESS_TOLERANCE) failures.add("wet over dry %.4f".format(wet / dry))
        assertTrue(failures.isEmpty(), "the balance is not the law's under two rains: $failures")
    }

    // ================================================================== the knickpoint

    /** Where a knickpoint stands after some rounds, read two ways, in cell widths from the sea. */
    private class Knick(val sharp: Double, val midpoint: Double, val spread: Double)

    /**
     * A profile [PROFILE_CELL_WIDTHS] cell widths long on the ground, running due west (along a
     * row) or due south (down a column) into the sea, with a break in slope [KNICK_CELL_WIDTHS]
     * from the sea: [LOWER_FALL_METRES] a cell width below it and [UPPER_FALL_METRES] above. Every
     * cell carries the same water, so the law's celerity `K sqrt(A)` is the same along the whole
     * profile and on both bearings, `EAST_WEST_COURANT` cell widths a round; the sea is lowered each
     * round by what the law takes off the lower reach, so that reach keeps its slope and the break
     * is the only feature that moves. Cut by production's [HydraulicErosion.incise] for [rounds]
     * rounds of [roundShare] of the clock.
     *
     * Read two ways. **As a sharp break of equal relief**: the distance from the sea at which a
     * profile of the two slopes meeting at a corner would fall as far as this one does over its
     * length, `((z_top - z_sea) - s_upper X) / (s_lower - s_upper)`. **As the steepest-change
     * point**: where the slope, walking up from the sea, first falls below halfway between the two
     * reaches', interpolated between cells. And the smear, the ground between the tenth and the
     * ninetieth part of the slope's change.
     */
    private fun knickpoint(southward: Boolean, rounds: Int, roundShare: Double): Knick {
        val side = 128
        val base = WorldGenConfig(seed = 3L, width = side, height = side)
        val config = base.copy(scale = base.scale.copy(yearsPerHydraulicRound = base.scale.yearsPerHydraulicRound * roundShare))
        val scale = config.scale
        val rates = HydraulicErosion.Rates(config)
        val steps = config.groundSteps
        val stepCellWidths = if (southward) steps.northSouth.toDouble() else steps.eastWest.toDouble()
        val cellsLong = (PROFILE_CELL_WIDTHS / stepCellWidths).toInt()
        val cellCount = side * side
        // The sea cell, then the profile's cells walking away from it.
        val start = if (southward) 20 * side + 64 else 64 * side + 4
        val stride = if (southward) side else 1
        val profile = IntArray(cellsLong) { start + (it + 1) * stride }
        val isLand = BooleanArray(cellCount)
        val directions = IntArray(cellCount) { -1 }
        for ((index, cell) in profile.withIndex()) {
            isLand[cell] = true
            directions[cell] = if (index == 0) start else profile[index - 1]
        }
        val metresPerField = scale.reliefSpanMetres.toDouble()
        val seaLevelMetres = 1_000.0
        fun heightAt(groundCellWidths: Double): Double =
            if (groundCellWidths <= KNICK_CELL_WIDTHS) seaLevelMetres + LOWER_FALL_METRES * groundCellWidths
            else seaLevelMetres + LOWER_FALL_METRES * KNICK_CELL_WIDTHS + UPPER_FALL_METRES * (groundCellWidths - KNICK_CELL_WIDTHS)
        val surface = FloatArray(cellCount) { scale.fieldAtAltitude(0f) }
        for ((index, cell) in profile.withIndex()) surface[cell] = scale.fieldAtAltitude(heightAt((index + 1) * stepCellWidths).toFloat())
        // The same `F` in ground terms on both bearings: `EAST_WEST_COURANT` along a row, and so
        // twice that down a column, whose step is half as long; a round of a share of the clock
        // takes that share of it. One land cell, so the share is the discharge.
        val rootShare = EAST_WEST_COURANT / (rates.courantCoefficient / roundShare)
        val discharge = FloatArray(cellCount) { (rootShare * rootShare).toFloat() }
        val erodibility = FloatArray(cellCount) { 1f }
        val order = profile.reversedArray()
        // The celerity is F times the step, in cell widths a round; at the lower reach's slope the
        // law lowers that reach by celerity times slope, and the sea goes with it.
        val celerityCellWidths = EAST_WEST_COURANT * roundShare
        var seaMetres = seaLevelMetres
        repeat(rounds) {
            seaMetres -= celerityCellWidths * LOWER_FALL_METRES
            val relative = FloatArray(cellCount) { (surface[it] - scale.fieldAtAltitude(0f)) / scale.landHalfOfField }
            HydraulicErosion.incise(
                rates, side, order, directions, isLand, relative, relative, discharge, 1f, scale.landHalfOfField,
                scale.fieldAtAltitude(seaMetres.toFloat()), erodibility, surface, incisedAt = null
            )
        }
        val heights = DoubleArray(cellsLong) { scale.altitudeAtField(surface[profile[it]]).toDouble() }
        val lengthCellWidths = cellsLong * stepCellWidths
        val sharp = ((heights.last() - seaMetres) - UPPER_FALL_METRES * lengthCellWidths) / (LOWER_FALL_METRES - UPPER_FALL_METRES)
        // Slope per cell width on each link, the link's middle at its ground distance from the sea.
        val slopes = DoubleArray(cellsLong) { index ->
            val below = if (index == 0) seaMetres else heights[index - 1]
            (heights[index] - below) / stepCellWidths
        }
        val middles = DoubleArray(cellsLong) { (it + 0.5) * stepCellWidths }
        fun crossing(share: Double): Double {
            val level = UPPER_FALL_METRES + (LOWER_FALL_METRES - UPPER_FALL_METRES) * share
            for (index in 1 until cellsLong) {
                if (slopes[index - 1] >= level && slopes[index] < level) {
                    val along = (slopes[index - 1] - level) / (slopes[index - 1] - slopes[index])
                    return middles[index - 1] + along * (middles[index] - middles[index - 1])
                }
            }
            return Double.NaN
        }
        return Knick(sharp, crossing(0.5), crossing(0.1) - crossing(0.9))
    }

    /**
     * A knickpoint retreats at the law's celerity, as far on the ground running north-south as
     * running east-west, and a round retreats it as far as two rounds of half the time.
     *
     * The capped explicit update is the case this was written against (Audit III's B-D1): it cuts
     * half the drop on a large-`F` channel, and a drop is in proportion to the step, so a channel
     * running down a column, whose step is half a cell width, fell half as far a round and its
     * knickpoint retreated half as far on the ground. The implicit update carries a profile
     * upstream at the law's celerity `K sqrt(A)` in ground distance whatever the step and whatever
     * the round's length; the smear it leaves, the scheme's numerical diffusion, is printed.
     */
    @Test
    fun `a knickpoint retreats as far north-south as east-west, and as far in two half rounds`() {
        val start = knickpoint(southward = false, rounds = 0, roundShare = 1.0)
        val eastWest = knickpoint(southward = false, rounds = 1, roundShare = 1.0)
        val northSouth = knickpoint(southward = true, rounds = 1, roundShare = 1.0)
        val eastWestHalves = knickpoint(southward = false, rounds = 2, roundShare = 0.5)
        val northSouthHalves = knickpoint(southward = true, rounds = 2, roundShare = 0.5)
        val northSouthStart = knickpoint(southward = true, rounds = 0, roundShare = 1.0)
        fun line(name: String, knick: Knick, from: Knick) =
            "%s: sharp break %.4f cell widths from the sea (retreat %.4f), steepest change %.3f (retreat %.3f), smear %.2f"
                .format(name, knick.sharp, knick.sharp - from.sharp, knick.midpoint, knick.midpoint - from.midpoint, knick.spread)
        println("IMPLICIT knickpoint " + line("east-west, one round", eastWest, start))
        println("IMPLICIT knickpoint " + line("north-south, one round", northSouth, northSouthStart))
        println("IMPLICIT knickpoint " + line("east-west, two half rounds", eastWestHalves, start))
        println("IMPLICIT knickpoint " + line("north-south, two half rounds", northSouthHalves, northSouthStart))
        // The law's celerity `K sqrt(A)` is `F` cell widths a round along a row: first that the
        // knickpoint moved at all, then that it moved at the law's speed, which a pass that cut
        // nothing, the sea alone moving, reads as the sea's fall over the break's change of slope.
        val retreat = eastWest.sharp - start.sharp
        assertTrue(retreat > 0.0, "the knickpoint did not move, so this saw nothing")
        assertTrue(
            abs(retreat - EAST_WEST_COURANT) <= RETREAT_TOLERANCE_CELL_WIDTHS,
            "the knickpoint retreated %.4f cell widths east-west in one round, where the law's celerity is %.1f".format(retreat, EAST_WEST_COURANT)
        )
        val sharpRetreats = listOf(
            "north-south" to northSouth.sharp - northSouthStart.sharp,
            "east-west in two halves" to eastWestHalves.sharp - start.sharp,
            "north-south in two halves" to northSouthHalves.sharp - northSouthStart.sharp
        )
        for ((name, other) in sharpRetreats) {
            assertTrue(
                abs(other - retreat) <= RETREAT_TOLERANCE_CELL_WIDTHS,
                "the knickpoint read as a sharp break retreated %.4f cell widths $name against %.4f east-west in one round".format(other, retreat)
            )
        }
        val midpointRetreat = eastWest.midpoint - start.midpoint
        val midpointRetreats = listOf(
            "north-south" to northSouth.midpoint - northSouthStart.midpoint,
            "east-west in two halves" to eastWestHalves.midpoint - start.midpoint,
            "north-south in two halves" to northSouthHalves.midpoint - northSouthStart.midpoint
        )
        for ((name, other) in midpointRetreats) {
            assertTrue(
                abs(other - midpointRetreat) <= MIDPOINT_TOLERANCE_CELL_WIDTHS,
                "the steepest change retreated %.3f cell widths $name against %.3f east-west in one round".format(other, midpointRetreat)
            )
        }
    }
}
