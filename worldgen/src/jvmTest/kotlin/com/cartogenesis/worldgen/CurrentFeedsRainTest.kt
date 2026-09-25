package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureBudget
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * H4: the moisture march's over-sea pickup now scales by
 * [com.cartogenesis.worldgen.pipeline.OceanResult.anomaly] rather than by latitude alone, so a
 * cold upwelling current starves the coast it washes and a warm one feeds it.
 *
 * The sample is a world with a subtropical west coast in the southern hemisphere, 27 to 33
 * degrees, whose offshore water sits at least 0.8 degrees colder than its latitude's own mean — the
 * model's equivalent of the Humboldt or Benguela current — and an east coast at the same latitudes
 * north of the equator washed by a warm, poleward-flowing western-boundary current (the Gulf Stream
 * and Kuroshio's role): the gyres this world solves are mirrored about the equator, the way Earth's
 * are. It is the first seed counting up from 1 whose world carries at least ten cells of the one
 * coast and five of the other, the two floors the guard itself asserts. That was seed 26, found by
 * inspection, until the ground's ruler redrew every continent and left seed 26 five cells of cold
 * coast; the same criterion, run over seeds 1 to 40, now finds seed 1 first (docs/DESIGN_LEDGER.md,
 * Fix 2).
 */
class CurrentFeedsRainTest : BorrowsSharedWorlds() {

    private companion object {
        const val SEED = 1L

        /** The known failure the cold-coast clause records. See docs/DESIGN_LEDGER.md, Fix 2. */
        const val COLD_COAST_BARELY_MOVES =
            "the climate: the current coupling moves a cold-current coast's rain by tenths of a percent, either way"
        // The cold-current stretch: bounds wide enough to catch a whole subtropical coastal run,
        // narrow enough that it does not wander into a different current regime.
        const val COLD_LAT_LO = -33f
        const val COLD_LAT_HI = -27f
        const val COLD_ANOMALY_MAX = -0.8f
        // The warm-current stretch, at the same distance from the equator in the opposite
        // hemisphere: the belts are symmetric about the equator, and so are the gyres (a northern
        // subtropical gyre's warm western-boundary current sits on its *east* coast, mirroring the
        // cold eastern-boundary current on the southern gyre's *west* coast at the same
        // |latitude|).
        const val WARM_LAT_LO = 27f
        const val WARM_LAT_HI = 33f
        const val WARM_ANOMALY_MIN = 0.15f
    }

    /**
     * Ground rule 2's other half: [ClimateStage.marchSeaStep] at `currentMoisture = 0` must be
     * bit for bit what the march computed before H4, whatever the anomaly says — the multiply
     * that couples them collapses to exactly 1.0, which is an exact no-op in IEEE float, not an
     * approximation. Checked directly on the shared function rather than by rebuilding the whole
     * march a second time, the way `MeridionalWindTest` already avoids restating this physics.
     */
    @Test
    fun `currentMoisture = 0 reproduces the pre-H4 sea step bit for bit`() {
        val cfg = WorldGenConfig().climate.copy(currentMoisture = 0f)
        // The two per-cell shares the step now takes as arguments rather than reading off a
        // per-cell rate of its own, at the reference grid's 23.4 km cell. See W3.
        val evaporationPerCell = 23.4375f / cfg.oceanEvaporationLengthKm
        val seaRainPerCell = 23.4375f / (cfg.depletionLengthKm * MoistureBudget.SEA_DEPLETION_SHARE)
        val samples = listOf(
            Triple(0.0f, -5f, -4.0f),
            Triple(0.35f, 12f, 0.0f),
            Triple(0.62f, 24f, 2.7f),
            Triple(0.9f, 18f, -6.3f),
            Triple(0.15f, -2f, 5.0f)
        )
        samples.forEach { (moisture, seaTemp, anomaly) ->
            val withAnomaly = ClimateStage.marchSeaStep(
                cfg, evaporationPerCell, seaRainPerCell, moisture, seaTemp, anomaly
            )
            val withoutAnomaly = ClimateStage.marchSeaStep(
                cfg, evaporationPerCell, seaRainPerCell, moisture, seaTemp, 0f
            )
            // Bit for bit: raw bits, not merely "close enough".
            assertTrue(
                withAnomaly.moisture.toRawBits() == withoutAnomaly.moisture.toRawBits() &&
                    withAnomaly.rain.toRawBits() == withoutAnomaly.rain.toRawBits(),
                "currentMoisture=0 should erase any dependence on the anomaly ($anomaly), but " +
                    "moisture ${withAnomaly.moisture} vs ${withoutAnomaly.moisture}, " +
                    "rain ${withAnomaly.rain} vs ${withoutAnomaly.rain}"
            )
        }
        println("H4 currentMoisture=0 reproduces marchSeaStep bit for bit across ${samples.size} samples")
    }

    /**
     * The guard proper. Two worlds from the same seed, differing only in [currentMoisture]:
     * 0 (the coupling off, i.e. today's field) and the default 0.07 (Clausius-Clapeyron). On the
     * identified cold-current stretch, average coastal rainfall must fall; on the warm-current
     * stretch a few degrees away, it must not.
     *
     * Run against a build with `currentFactor` hardcoded to 1 (the coupling permanently off), the
     * cold-coast assertion failed exactly as it should: off and on both read 1531.4838mm, to the
     * last bit, because the two configs then differed in a setting neither world ever reads.
     */
    @Test
    fun `a cold-current coast dries out while a warm one does not`() {
        val base = WorldGenConfig(seed = SEED, width = 512, height = 512)
        val on = SharedWorlds.world(base)
        val off = SharedWorlds.world(
            base.copy(climate = base.climate.copy(currentMoisture = 0f))
        )
        val w = on.width
        val h = on.height

        data class Coast(val x: Int, val y: Int, val lat: Float, val anomaly: Float)

        val coldCoast = ArrayList<Coast>()
        val warmCoast = ArrayList<Coast>()
        for (y in 0 until h) {
            val lat = ClimateStage.latitudeOf(y, h)
            for (x in 0 until w) {
                val i = y * w + x
                if (!on.sea.isLand[i]) continue
                val westX = (x - 1 + w) % w
                if (!on.sea.isLand[y * w + westX]) {
                    val a = on.ocean.anomaly.data[y * w + westX]
                    if (lat in COLD_LAT_LO..COLD_LAT_HI && a <= COLD_ANOMALY_MAX) {
                        coldCoast.add(Coast(x, y, lat, a))
                    }
                }
                val eastX = (x + 1) % w
                if (!on.sea.isLand[y * w + eastX]) {
                    val a = on.ocean.anomaly.data[y * w + eastX]
                    if (lat in WARM_LAT_LO..WARM_LAT_HI && a >= WARM_ANOMALY_MIN) {
                        warmCoast.add(Coast(x, y, lat, a))
                    }
                }
            }
        }

        assertTrue(coldCoast.size >= 10, "too few cold-coast cells found: ${coldCoast.size}")
        assertTrue(warmCoast.size >= 5, "too few warm-coast cells found: ${warmCoast.size}")

        fun meanMm(cells: List<Coast>, world: com.cartogenesis.worldgen.model.WorldMap): Double =
            cells.map { world.climate.precipitationMm.data[it.y * w + it.x].toDouble() }.average()

        val coldOn = meanMm(coldCoast, on)
        val coldOff = meanMm(coldCoast, off)
        val warmOn = meanMm(warmCoast, on)
        val warmOff = meanMm(warmCoast, off)

        val coldLatMean = coldCoast.map { it.lat }.average()
        val warmLatMean = warmCoast.map { it.lat }.average()
        val coldAnomalyMean = coldCoast.map { it.anomaly }.average()
        val warmAnomalyMean = warmCoast.map { it.anomaly }.average()

        println(
            "H4 seed $SEED cold coast: n=${coldCoast.size}, mean lat %.1f, mean anomaly %.2f, ".format(
                coldLatMean, coldAnomalyMean
            ) + "rainfall off=%.0fmm on=%.0fmm (%.2f%% change)".format(
                coldOff, coldOn, (coldOn - coldOff) / coldOff * 100
            )
        )
        println(
            "H4 seed $SEED warm coast: n=${warmCoast.size}, mean lat %.1f, mean anomaly %.2f, ".format(
                warmLatMean, warmAnomalyMean
            ) + "rainfall off=%.0fmm on=%.0fmm (%.2f%% change)".format(
                warmOff, warmOn, (warmOn - warmOff) / warmOff * 100
            )
        )

        val alreadyDesert = coldCoast.filter { on.climate.biome[it.y * w + it.x] == Biome.DESERT }
        if (alreadyDesert.isNotEmpty()) {
            val deepened = alreadyDesert.count {
                on.climate.precipitationMm.data[it.y * w + it.x] <
                    off.climate.precipitationMm.data[it.y * w + it.x]
            }
            println(
                "H4 seed $SEED: ${alreadyDesert.size} cold-coast cells were already desert " +
                    "(the belt made them dry); $deepened of them got drier still with the coupling on"
            )
        }
        // Measured, not tuned: at the Clausius-Clapeyron default (0.07/deg), no seed among the 40
        // scanned during development crossed a west-coast cell from a wetter biome into DESERT -
        // the march's moisture is usually close to saturated by the time it reaches land, so a
        // roughly 10% pickup-rate change over one current's stretch of sea shows up as a few
        // percent of rainfall, not a biome flip. Reported rather than asserted, per rule 5.

        // Seed 1's cold coast comes out 0.66% wetter with the coupling on, where seed 26's came
        // out 0.41% drier: a few tenths of a percent either way is what the coupling moves a
        // cold-current coast's rain by, so the sign is the sample's. Recorded rather than
        // re-picked to a seed that dries: the claim is the coupling's, and at this size it is not
        // one the march makes (docs/DESIGN_LEDGER.md, Fix 2).
        KnownFailures.expect(COLD_COAST_BARELY_MOVES, "off 1908 mm, on 1918 mm") {
            if (coldOn >= coldOff) {
                throw RecordedViolation(
                    "cold-current coast should get drier with the coupling on: off=$coldOff, on=$coldOn",
                    String.format(java.util.Locale.ROOT, "off %.0f mm, on %.0f mm", coldOff, coldOn)
                )
            }
        }
        assertTrue(
            warmOn >= warmOff * 0.999,
            "warm-current coast should not get drier with the coupling on: off=$warmOff, on=$warmOn"
        )
    }
}
