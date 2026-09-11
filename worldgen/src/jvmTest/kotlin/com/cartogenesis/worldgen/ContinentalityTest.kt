package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether a continental interior actually swings further through the year than a coast does, at
 * the same latitude.
 *
 * `ClimateStage.seasonalTemperature` scales the seasonal departure from the annual mean by
 * `1 + continentality * continentalityFactor`, where `continentalityFactor` is
 * `ClimateStage.waterDistance` — an actual cell distance to the nearest sea, from the same chamfer
 * distance transform `SeaLevelStage` already uses for the continental shelf — clamped to 0..1 over
 * three `coastalReach`. A shoreline cell keeps the amplitude at 1; a cell three reaches inland or
 * further reaches the full `1 + continentality`. This is Siberia versus Ireland.
 *
 * An earlier version of both the feature and this guard read the blurred water-exposure field
 * instead, on the theory that "exposed to water" and "close to water" were the same question asked
 * two ways. Measured, they were not at this radius: two box-blur passes leave a cell right at the
 * edge of `coastalReach` reading only around 0.2-0.3 exposure, not the ~1 that would make a coast
 * read as barely continental, so the near/far amplitude gap that version could produce topped out
 * around 3 C against the plan's stated 6. Reading the actual distance instead removes that ceiling;
 * see the A2 follow-up report for the before/after numbers.
 */
class ContinentalityTest {

    private companion object {
        /** Matches the plan's own worked example: "Interior at 50 deg should swing markedly more
         * than a coast at 50 deg." */
        const val SAMPLE_LATITUDE = 50f
        const val SAMPLE_SPAN = 6f

        /** The plan's own figure for the guard. */
        const val TARGET_GAP = 6.0
    }

    @Test
    fun `interior land swings further through the year than a coast at the same reach`() {
        val (near, far) = measure(continentality = 0.6f)
        println(
            "CONTINENTALITY seed 42 at ${SAMPLE_LATITUDE.toInt()} deg, continentality=0.6: " +
                "coast (within reach) swing %.1f C over %d cells, interior (beyond 3x reach) swing %.1f C over %d cells"
                    .format(near.gap, near.cells, far.gap, far.cells)
        )
        assertTrue(near.cells > 0 && far.cells > 0, "not enough coastal or interior land to compare")
        assertTrue(
            far.gap - near.gap >= TARGET_GAP,
            "interior swings only %.1f C more than the coast (interior %.1f C, coast %.1f C)"
                .format(far.gap - near.gap, far.gap, near.gap)
        )
    }

    @Test
    fun `without continentality the coast and the interior swing alike`() {
        // The guard above, shown to fail: with the multiplier switched off every land cell's
        // amplitude is exactly 1 regardless of distance to water, so the gap this test measures
        // should collapse to whatever noise is left within the band.
        val (near, far) = measure(continentality = 0f)
        println(
            "CONTINENTALITY seed 42 at ${SAMPLE_LATITUDE.toInt()} deg, continentality=0: " +
                "coast (within reach) swing %.1f C over %d cells, interior (beyond 3x reach) swing %.1f C over %d cells"
                    .format(near.gap, near.cells, far.gap, far.cells)
        )
        assertTrue(near.cells > 0 && far.cells > 0, "not enough coastal or interior land to compare")
        assertTrue(
            far.gap - near.gap < TARGET_GAP,
            "interior swings %.1f C more than the coast even with continentality off — " +
                "the guard cannot discriminate the feature from its absence"
                    .format(far.gap - near.gap)
        )
    }

    private data class Group(val gap: Double, val cells: Int)

    private fun measure(continentality: Float): Pair<Group, Group> {
        val base = WorldGenConfig(seed = 42L, width = 512, height = 512)
        val world = WorldGenerationEngine.generateBlocking(
            base.copy(climate = base.climate.copy(continentality = continentality))
        )
        val w = world.width
        val h = world.height
        val reach = world.config.ocean.coastalReach
        // The same field production reads, not a re-derivation of it — see the class doc for why
        // that distinction mattered here.
        val distance = ClimateStage.waterDistance(world.config, world.sea)

        var nearSum = 0.0
        var nearCount = 0
        var farSum = 0.0
        var farCount = 0
        for (y in 0 until h) {
            val lat = abs(ClimateStage.latitudeOf(y, h))
            if (abs(lat - SAMPLE_LATITUDE) > SAMPLE_SPAN) continue
            for (x in 0 until w) {
                val i = y * w + x
                if (!world.sea.isLand[i]) continue
                val gap = abs(
                    (world.climate.summerTemperature.data[i] -
                        world.climate.winterTemperature.data[i]).toDouble()
                )
                val d = distance.data[i]
                when {
                    d <= reach -> {
                        nearSum += gap
                        nearCount++
                    }
                    d > reach * 3 -> {
                        farSum += gap
                        farCount++
                    }
                }
            }
        }
        return Group(if (nearCount == 0) 0.0 else nearSum / nearCount, nearCount) to
            Group(if (farCount == 0) 0.0 else farSum / farCount, farCount)
    }
}
