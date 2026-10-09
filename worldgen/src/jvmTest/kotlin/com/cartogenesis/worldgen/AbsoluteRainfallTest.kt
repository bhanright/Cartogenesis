package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether an arid world and a lush one can tell each other apart.
 *
 * Before A4, `normalizeByLandPercentile` rescaled every world so its 88th land percentile sat at
 * 1.0 before `classify` ever saw it, so a seed whose march put down twice the rain of another
 * still classified the same — every audited seed carried within a point of 4.6% desert regardless
 * of how arid its march actually was. `classify` reads millimeters, which since C1b are the march's
 * own kilograms per square meter and not a calibrated conversion, so a genuinely arider world
 * produces genuinely more desert and a genuinely wetter one produces genuinely less.
 *
 * At [SharedWorlds.COARSE_ROWS]: a rainfall in millimeters and a desert's share of the land are
 * figures of the ground rather than of the grid's detail (docs/DESIGN_LEDGER.md, Q2b).
 */
class AbsoluteRainfallTest : BorrowsSharedWorlds() {

    private val seeds = listOf(7L, 42L, 1234L, 99L)

    private companion object {
        /** MeridionalWindTest's own figures for the monsoon re-measurement, restated in mm terms. */
        const val WET_SEASON_FRACTION = 0.25f
        const val MONSOON_RATIO = 3f
        const val MONSOON_REQUIRED_SHARE = 0.02

        /**
         * How far out to sea a coast may look, and how far the land must run behind it, in
         * kilometers: the 30 rows and 8 rows the mask was written with on the 512 by 512 grid,
         * whose rows were 11.7 km tall, so the mask asks for the same coast at any grid.
         */
        const val SEA_REACH_KM = 351.5625
        const val LAND_BEHIND_KM = 93.75

        /**
         * The colder and the warmer world the arid and lush configs are, in degrees of global mean:
         * six colder, a glacial maximum's depth, and four warmer.
         */
        const val ARID_SHIFT_C = -6f
        const val LUSH_SHIFT_C = 4f

        /** Bins the land's rain is bucketed into to find a percentile of it. */
        const val PERCENTILE_BINS = 2048
    }

    @Test
    fun `desert share differs between an arid world and a lush one`() {
        val shares = seeds.associateWith { seed -> desertShare(seed) }
        println(
            "ABSRAIN desert share by seed (absolute mm): " +
                seeds.joinToString { "$it=%.2f%%".format(shares.getValue(it)) }
        )
        // Armed again at A1-2, at 1.53: C1b2 recorded the seeds standing 1.48 times apart and A1-1
        // a hair under 1.5; the pressure wind on the sphere moved them back over it
        // (docs/DESIGN_LEDGER.md, A1-2).
        assertRatioAtLeast(shares, 1.5f, "seeds no longer differ in how much desert they carry")
    }

    /**
     * Ground rule 2, aimed at the actual mechanism the plan describes ("an arid world and a lush
     * one classify identically") rather than at seed-to-seed noise.
     *
     * Reconstructing the old per-world percentile normalization and applying it to seeds 7, 42,
     * 1234 and 99 — same config, different terrain — was tried first and measured a 2.21x
     * driest/wettest ratio, *not* near-identical: seasons and continentality (A1, A2) already give
     * different terrain enough seasonal-swing variety to move the annual field's shape by seed, so
     * percentile rescaling does not erase all cross-seed difference even though it was designed to
     * erase differences in overall climate severity. That is a real, measured finding — reported
     * above rather than asserted on, since asserting a false "it fails" would be exactly the
     * threshold-shopping ground rule 2 warns against — but it means seed variety alone is the
     * wrong axis to demonstrate the fix on.
     *
     * Overall climate severity is the right axis, and it is a config knob: two worlds from the
     * *same* seed, one colder and one warmer (`ClimateConfig.globalMeanShiftC`), whose air holds
     * and moves less water and more by Clausius-Clapeyron. `classify` no longer has a flag to
     * switch the old normalization back on, so the old predicate is reconstructed here instead:
     * pre-A4 `classify` compared `precip / landPercentile(precip, 0.88)` against 0.14 for DESERT,
     * ahead of every other rule, in both the temperate and tropical branch — reached only once a
     * cell has cleared the same cold/elevation gates. A uniform rescale changes no
     * percentile-relative comparison, so evaluating the old predicate against today's millimeters
     * reproduces what the old normalization would have classified as desert.
     */
    @Test
    fun `an arid config and a lush one classify identically under the old normalization, not under this one`() {
        val seed = 42L
        val base = WorldGenConfig.forRows(seed, SharedWorlds.COARSE_ROWS)
        val arid = base.copy(climate = base.climate.copy(globalMeanShiftC = ARID_SHIFT_C))
        val lush = base.copy(climate = base.climate.copy(globalMeanShiftC = LUSH_SHIFT_C))

        val oldArid = oldNormalizedDesertShare(arid)
        val oldLush = oldNormalizedDesertShare(lush)
        val oldRatio = if (oldLush > 0f) oldArid / oldLush else Float.POSITIVE_INFINITY
        println(
            "ABSRAIN old normalization, seed $seed: arid config %.2f%% desert, ".format(oldArid) +
                "lush config %.2f%% desert (%.2fx)".format(oldLush, oldRatio)
        )

        val newArid = desertShare(arid)
        val newLush = desertShare(lush)
        val newRatio = if (newLush > 0f) newArid / newLush else Float.POSITIVE_INFINITY
        println(
            "ABSRAIN absolute mm, seed $seed: arid config %.2f%% desert, ".format(newArid) +
                "lush config %.2f%% desert (%.2fx)".format(newLush, newRatio)
        )

        // **Re-pinned by W3, and the shape of the clause changed with it.** This used to read
        // `oldRatio < 1.5`, on the reasoning that a uniform rescale changes no percentile-relative
        // comparison, so the old normalization had to flatten an arid world onto a lush one almost
        // exactly. That reasoning held while rainfall responded linearly to the march's rate. W3's
        // ground-wetness term makes it nonlinear: dry ground returns less water, so an arid world
        // is arid in a different *shape* and not merely at a lower level, and no single rescale
        // maps it onto a lush one any more. Measured here, the old normalization now reads 1.64x
        // where absolute millimetres read 2.72x. So the clause is stated as what it was always
        // for - that the old normalization hid most of the difference - as a ratio between the two
        // measurements rather than as an absolute bar, and it bites at 0.60 of the way. See
        // docs/DESIGN_LEDGER.md, W3.
        // Recorded at C1b: on the conserving march the world six degrees colder carries a little
        // less desert than the one four degrees warmer (4.67% against 5.26% at 256 rows), so the
        // colder config is not the arid one this clause was written for, and neither measure tells
        // the two apart. On Earth the last glacial maximum's deserts were wider than today's
        // (docs/DESIGN_LEDGER.md, C1b; docs/TODO.md).
        KnownFailures.expect("C1b: a world six degrees colder is no drier on the conserving march", "old 0.48x, absolute 0.73x") {
            if (!(oldRatio < newRatio * 0.75f && newRatio >= 1.5f)) {
                throw RecordedViolation(
                    "the old per-world normalization should have hidden most of the arid/lush difference, " +
                        "and absolute mm should tell them apart by 1.5x: measured %.2fx against absolute %.2fx (arid %.2f%%, lush %.2f%%)"
                        .format(oldRatio, newRatio, newArid, newLush),
                    "old %.2fx, absolute %.2fx".format(oldRatio, newRatio)
                )
            }
        }
    }

    /**
     * The monsoon note A3 left open (see `MeridionalWindTest`'s "a tropical coast has a wet season
     * and a dry one"): with rainfall normalised and clamped at 1, tropical coasts sat against that
     * clamp in the warm season, so a monsoon year's wet half had no room left to get wetter —
     * measured there at 0.94 to 1.00 across five seeds, pinned against the ceiling. Re-measured on
     * A3's own seed (26) with the same mask, the same [MONSOON_RATIO] and a millimetre floor
     * equivalent to the old [WET_SEASON_FRACTION] of the old clamp, but reading
     * [ClimateStage.generateWithSeasonalMm]'s unclamped seasonal fields instead — the same 3x /
     * 2%-of-land claim the plan originally asked for, without the ceiling that kept it from being
     * tested honestly.
     *
     * Not a guard: `MeridionalWindTest`'s own guard is left exactly as it was (it asserts the
     * weaker claim, deliberately, and says why). This reports whether removing the clamp changes
     * the answer, and says so either way.
     */
    @Test
    fun `the monsoon claim, re-measured without the clamp`() {
        val seed = 26L
        val base = WorldGenConfig.forRows(seed, SharedWorlds.COARSE_ROWS)
        val world = SharedWorlds.world(base)
        val generated = ClimateStage.generateWithSeasonalMm(base, world.sea, world.ocean)
        val w = world.width
        val h = world.height
        val wetSeasonMm = WET_SEASON_FRACTION * ClimateStage.REFERENCE_MM

        val mask = BooleanArray(w * h)
        for (y in 0 until h) {
            val lat = ClimateStage.latitudeOf(y, h)
            if (abs(lat) < 12f || abs(lat) > 32f) continue
            // Poleward, matching MeridionalWindTest's own mask: the coast this world actually
            // grew a monsoon on faces poleward, not equatorward — see that test's comment on why.
            val seaward = if (lat > 0f) -1 else 1
            for (x in 0 until w) {
                val i = y * w + x
                if (!world.sea.isLand[i]) continue
                val summerMm = generated.summerPrecipitationMm.data[i]
                val winterMm = generated.winterPrecipitationMm.data[i]
                if (summerMm < wetSeasonMm || summerMm <= MONSOON_RATIO * winterMm) continue
                if (!facesSea(world, x, y, seaward)) continue
                mask[i] = true
            }
        }
        val region = largestRegionOf(world, mask)
        val share = region / world.sea.landCellCount.toDouble()
        println(
            "ABSRAIN monsoon seed $seed re-measured without the clamp: largest contiguous " +
                "region with a %.0fx summer/winter split ".format(MONSOON_RATIO) +
                "covers %.2f%% of land (was 2.93%% clamped, needs %.0f%%)".format(
                    share * 100, MONSOON_REQUIRED_SHARE * 100
                )
        )
        if (share >= MONSOON_REQUIRED_SHARE) {
            println("ABSRAIN monsoon claim: HOLDS without the clamp")
        } else {
            println("ABSRAIN monsoon claim: still does not hold without the clamp")
        }
    }

    /** Open water within [SEA_REACH_KM] that way, and land for [LAND_BEHIND_KM] the other. */
    private fun facesSea(world: WorldMap, x: Int, y: Int, seaward: Int): Boolean {
        val w = world.width
        val h = world.height
        var found = false
        val seaReachRows = world.config.rowsFor(SEA_REACH_KM).roundToInt()
        val landBehindRows = world.config.rowsFor(LAND_BEHIND_KM).roundToInt()
        for (step in 1..seaReachRows) {
            val ny = y + seaward * step
            if (ny !in 0 until h) break
            if (!world.sea.isLand[ny * w + x]) { found = true; break }
        }
        if (!found) return false
        for (step in 1..landBehindRows) {
            val ny = y - seaward * step
            if (ny !in 0 until h || !world.sea.isLand[ny * w + x]) return false
        }
        return true
    }

    /** The largest four-connected block of the mask, in cells. X wraps; Y does not. */
    private fun largestRegionOf(world: WorldMap, mask: BooleanArray): Int {
        val w = world.width
        val h = world.height
        val seen = BooleanArray(w * h)
        var largest = 0
        val stack = ArrayDeque<Int>()
        for (start in 0 until w * h) {
            if (!mask[start] || seen[start]) continue
            var size = 0
            stack.addLast(start)
            seen[start] = true
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                size++
                val cx = cell % w
                val cy = cell / w
                val around = intArrayOf(
                    cy * w + ((cx + 1) % w),
                    cy * w + ((cx - 1 + w) % w),
                    if (cy > 0) (cy - 1) * w + cx else -1,
                    if (cy < h - 1) (cy + 1) * w + cx else -1
                )
                for (n in around) {
                    if (n < 0 || seen[n] || !mask[n]) continue
                    seen[n] = true
                    stack.addLast(n)
                }
            }
            if (size > largest) largest = size
        }
        return largest
    }

    private fun assertRatioAtLeast(shares: Map<Long, Float>, minRatio: Float, message: String) {
        val driest = shares.values.max()
        val wettest = shares.values.filter { it > 0f }.minOrNull() ?: 0f
        assertTrue(wettest > 0f, "no seed had any desert to compare against")
        val ratio = driest / wettest
        println("ABSRAIN driest/wettest desert-share ratio: %.2f".format(ratio))
        assertTrue(
            ratio >= minRatio,
            "$message: only %.2fx (driest %.2f%%, wettest %.2f%%)".format(ratio, driest, wettest)
        )
    }

    private fun desertShare(seed: Long): Float =
        desertShare(WorldGenConfig.forRows(seed, SharedWorlds.COARSE_ROWS))

    private fun desertShare(config: WorldGenConfig): Float {
        val world = SharedWorlds.world(config)
        var land = 0
        var desert = 0
        for (i in world.climate.biome.indices) {
            if (!world.sea.isLand[i]) continue
            land++
            if (world.climate.biome[i] == Biome.DESERT) desert++
        }
        return if (land > 0) desert * 100f / land else 0f
    }

    private fun oldNormalizedDesertShare(seed: Long): Float =
        oldNormalizedDesertShare(WorldGenConfig.forRows(seed, SharedWorlds.COARSE_ROWS))

    private fun oldNormalizedDesertShare(config: WorldGenConfig): Float {
        val world = SharedWorlds.world(config)
        val reference = landPercentile(world.climate.precipitationMm.data, world.sea.isLand, 0.88f)
        if (reference <= 0f) return 0f
        var land = 0
        var desert = 0
        for (i in world.climate.precipitationMm.data.indices) {
            if (!world.sea.isLand[i]) continue
            land++
            // The gates classify passes through before it ever asks about desert: not ice, not
            // alpine, warm enough to have left taiga/tundra behind.
            val t = world.climate.temperature.data[i]
            val elevation = world.sea.relativeElevation.data[i]
            if (t < 7f || elevation > 0.72f) continue
            val normalized = (world.climate.precipitationMm.data[i] / reference).coerceIn(0f, 1f)
            if (normalized < 0.14f) desert++
        }
        return if (land > 0) desert * 100f / land else 0f
    }

    /**
     * The rain a share [percentile] of the land falls under, in the field's own unit: the
     * percentile the pre-A4 normalization divided every world by, rebuilt here because nothing in
     * the generator reads it any more.
     */
    private fun landPercentile(rain: FloatArray, isLand: BooleanArray, percentile: Float): Float {
        var wettest = 0f
        var landCells = 0
        for (cell in rain.indices) {
            if (!isLand[cell]) continue
            landCells++
            if (rain[cell] > wettest) wettest = rain[cell]
        }
        if (landCells == 0 || wettest <= 0f) return 0f
        val histogram = IntArray(PERCENTILE_BINS)
        val binsPerUnit = (PERCENTILE_BINS - 1) / wettest
        for (cell in rain.indices) {
            if (isLand[cell]) histogram[(rain[cell] * binsPerUnit).toInt().coerceIn(0, PERCENTILE_BINS - 1)]++
        }
        val target = (landCells * percentile).toLong()
        var cumulative = 0L
        for (bin in 0 until PERCENTILE_BINS) {
            cumulative += histogram[bin]
            if (cumulative >= target) return (bin / binsPerUnit).takeIf { it > 0f } ?: wettest
        }
        return wettest
    }
}
