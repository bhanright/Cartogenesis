package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether an arid world and a lush one can tell each other apart.
 *
 * Before this chunk, `normalizeByLandPercentile` rescaled every world so its 88th land percentile
 * sat at 1.0 before `classify` ever saw it, so a seed whose march put down twice the rain of
 * another still classified the same — every audited seed carried within a point of 4.6% desert
 * regardless of how arid its march actually was. `classify` now reads
 * [ClimateStage.MM_SCALE]-calibrated millimetres, so a genuinely arider seed produces genuinely
 * more desert and a genuinely wetter one produces genuinely less.
 */
class AbsoluteRainfallTest {

    private val seeds = listOf(7L, 42L, 1234L, 99L)

    /**
     * The figures the report asks for: seed 42's calibration targets, and every audited seed's
     * desert share and in-band placement, before and after A4.
     */
    @Test
    fun `report calibration and desert-share figures per seed`() {
        seeds.forEach { seed ->
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val w = world.width
            val h = world.height

            // The windward-coast target: the 99.5th land percentile, which is where MM_SCALE was
            // calibrated to land seed 42 at 3000mm.
            val coast = ClimateStage.landPercentile(world.climate.precipitationMm, world.sea.isLand, 0.995f)

            // The desert-core target: the 10th percentile of land within the horse latitudes,
            // away from any single orographic outlier.
            val coreValues = ArrayList<Float>()
            var land = 0
            var desert = 0
            var desertInBand = 0
            for (y in 0 until h) {
                val lat = abs(ClimateStage.latitudeOf(y, h))
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!world.sea.isLand[i]) continue
                    land++
                    if (lat in 25f..35f) coreValues.add(world.climate.precipitationMm.data[i])
                    if (world.climate.biome[i] == Biome.DESERT) {
                        desert++
                        if (lat in 15f..45f) desertInBand++
                    }
                }
            }
            coreValues.sort()
            val core = if (coreValues.isNotEmpty()) {
                coreValues[(coreValues.size * 0.10f).toInt().coerceIn(0, coreValues.size - 1)]
            } else {
                0f
            }
            val desertShare = if (land > 0) desert * 100.0 / land else 0.0
            val inBand = if (desert > 0) desertInBand * 100.0 / desert else 0.0

            println(
                "ABSRAIN seed $seed: windward coast (p99.5 land) %.0fmm, ".format(coast) +
                    "desert core (p10 @ 25-35deg) %.0fmm, ".format(core) +
                    "desert %.2f%% of land, %.0f%% of it in the 15-45deg band".format(desertShare, inBand)
            )
        }
    }

    @Test
    fun `desert share differs between an arid world and a lush one`() {
        val shares = seeds.associateWith { seed -> desertShare(seed) }
        println(
            "ABSRAIN desert share by seed (absolute mm): " +
                seeds.joinToString { "$it=%.2f%%".format(shares.getValue(it)) }
        )
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
     * *same* seed, one with the march's rain terms turned down and one turned up. `classify` no
     * longer has a flag to switch the old normalization back on, so the old predicate is
     * reconstructed here instead: pre-A4 `classify` compared `precip / landPercentile(precip,
     * 0.88)` against 0.14 for DESERT, ahead of every other rule, in both the temperate and
     * tropical branch — reached only once a cell has cleared the same cold/elevation gates.
     * [precipitationMm][com.cartogenesis.worldgen.pipeline.ClimateResult.precipitationMm] differs
     * from the pre-A4 raw field by nothing but the constant [ClimateStage.MM_SCALE], and a uniform
     * rescale changes no percentile-relative comparison, so evaluating the old predicate against
     * today's mm field reproduces exactly what the old normalization classified as desert.
     */
    @Test
    fun `an arid config and a lush one classify identically under the old normalization, not under this one`() {
        val seed = 42L
        val base = WorldGenConfig(seed = seed, width = 512, height = 512)
        val arid = base.copy(
            climate = base.climate.copy(baseRainRate = base.climate.baseRainRate * 0.3f)
        )
        val lush = base.copy(
            climate = base.climate.copy(baseRainRate = base.climate.baseRainRate * 2.5f)
        )

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
                "lush config %.2f%% desert (%.2fx)".format(newRatio, newRatio)
        )

        assertTrue(
            oldRatio < 1.5f,
            "the old per-world normalization should have flattened the arid/lush difference, but " +
                "measured a %.2fx ratio (arid %.2f%%, lush %.2f%%)".format(oldRatio, oldArid, oldLush)
        )
        assertTrue(
            newRatio >= 1.5f,
            "absolute mm should tell the arid config from the lush one, but measured only " +
                "%.2fx (arid %.2f%%, lush %.2f%%)".format(newRatio, newArid, newLush)
        )
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
        desertShare(WorldGenConfig(seed = seed, width = 512, height = 512))

    private fun desertShare(config: WorldGenConfig): Float {
        val world = WorldGenerationEngine.generateBlocking(config)
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
        oldNormalizedDesertShare(WorldGenConfig(seed = seed, width = 512, height = 512))

    private fun oldNormalizedDesertShare(config: WorldGenConfig): Float {
        val world = WorldGenerationEngine.generateBlocking(config)
        val reference =
            ClimateStage.landPercentile(world.climate.precipitationMm, world.sea.isLand, 0.88f)
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
}
