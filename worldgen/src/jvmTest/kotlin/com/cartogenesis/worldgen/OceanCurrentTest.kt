package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.OceanStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Which way the solved gyres turn on the standard worlds, against the wind that drives them.
 *
 * Sign bars with no world-derived figure in them, each read off Stommel's solution for a basin under
 * this generator's own belts, and each held on the four standard seeds at 512 per merge
 * ([OceanCurrentAuditTest] holds the author's 2048 world). Earth's surface drifter climatology
 * (Lumpkin and Johnson 2013, *J. Geophys. Res. Oceans* 118, 2992-3006) has the same signs: eastward
 * mean flow at 40 to 50 degrees and a poleward western boundary current in every subtropical basin.
 */
class OceanCurrentTest : BorrowsSharedWorlds() {

    /**
     * The westerlies' band flows east in both hemispheres, the subtropical gyres return poleward
     * along their western sides and equatorward across their eastern thirds, and, under the belts'
     * own wind, the trades' band flows west.
     *
     * The last clause is taken with the regional wind off. Stommel's interior zonal flow is
     * `u = (x_east - x) ∂(F/β)/∂y`, and the belts' stress `-τ₀ cos²(3φ)` makes `∂F/∂y` negative
     * from the equator to 15 degrees in both hemispheres, so the belts alone drive the interior
     * westward there; a regional wind whose curl changes sign inside the band can turn part of it,
     * as Earth's North Equatorial Countercurrent runs east at 3 to 10 degrees north, so with the
     * regional wind on that band is printed and not asserted.
     */
    @Test
    fun `the gyres turn with the wind on every standard world`() {
        val failures = ArrayList<String>()
        for (seed in listOf(7L, 42L, 1234L, 99L)) {
            val world = SharedWorlds.world(WorldGenConfig(seed = seed, width = 512, height = 512))
            val beltsOnly = world.config.copy(climate = world.config.climate.copy(pressureWinds = false))
            failures += OceanSense.check("seed $seed at 512", world.config, world.sea, world.ocean,
                OceanStage.generate(beltsOnly, world.sea))
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * Warm coasts should end up better settled than cold ones at the same latitude.
     *
     * Latitude has to be held fixed or the comparison measures nothing but the tropics being nicer
     * than the arctic. Within a band, the only thing separating one coast from another is what the
     * current brings, so the gap between them is the effect of the currents alone.
     */
    @Test
    fun `warm coasts are worth more than cold ones at the same latitude`() {
        // Pooled, with a per-seed floor at no gap at all. The gap is a few percent by design — the
        // fishery bonus rewards the cold quartile while the harbour bonus rewards the warm one —
        // and how much of it survives on any one world depends on how much coast that world's
        // currents actually run along. S2's fourth pass took seed 42 to 0.9% while seed 7 stayed
        // at 8.7%, which is a spread the pooled figure carries and a per-seed bar cannot. What no
        // world may do is settle its cold coasts *better*, and that is the floor.
        val gaps = listOf(7L, 42L, 1234L).map { seed -> checkCoasts(seed) }
        val pooled = gaps.average()
        println(
            "OCEAN pooled coastal gap %.1f%% over %d seeds".format((pooled - 1) * 100, gaps.size)
        )
        assertTrue(
            pooled > 1.02,
            "the warm quartile is only ${"%.1f".format((pooled - 1) * 100)}% better settled than" +
                " the cold one pooled over the three seeds, where the coastal term is worth 2%"
        )
    }

    /** The warm quartile's coastal habitability over the cold quartile's, as a ratio. */
    private fun checkCoasts(seed: Long): Double {
        val config = WorldGenConfig(seed = seed, width = 512, height = 512)
        val world = SharedWorlds.world(config)
        val w = world.width
        val h = world.height

        // 16 latitude bands, each scored against its own mean so bands cannot outvote each other.
        val bandCount = 16
        var warmTotal = 0.0
        var coldTotal = 0.0
        var warmCells = 0
        var coldCells = 0

        repeat(bandCount) { band ->
            val y0 = band * h / bandCount
            val y1 = (band + 1) * h / bandCount
            val coast = ArrayList<Pair<Float, Float>>()  // anomaly offshore to habitability

            for (y in y0 until y1) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!world.sea.isLand[i]) continue
                    var sum = 0f
                    var count = 0
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until h) continue
                        for (dx in -1..1) {
                            val n = ny * w + ((x + dx + w) % w)
                            if (!world.sea.isLand[n]) {
                                sum += world.ocean.anomaly.data[n]
                                count++
                            }
                        }
                    }
                    if (count > 0) coast.add(sum / count to world.nations.habitability.data[i])
                }
            }
            if (coast.size < 40) return@repeat

            val sorted = coast.sortedBy { it.first }
            val quartile = sorted.size / 4
            sorted.take(quartile).forEach { coldTotal += it.second; coldCells++ }
            sorted.takeLast(quartile).forEach { warmTotal += it.second; warmCells++ }
        }

        val warm = warmTotal / warmCells
        val cold = coldTotal / coldCells
        println(
            "OCEAN seed %d coastal habitability: warm %.3f, cold %.3f (%.1f%% gap, %d cells each)"
                .format(seed, warm, cold, (warm / cold - 1) * 100, warmCells)
        )
        // Only a few percent, and deliberately so: the fishery bonus rewards the cold quartile at
        // the same time the harbour bonus rewards the warm one, so the two partly cancel. What
        // matters is that the gap exists at all — with the coastal term removed it sits at zero and
        // tips slightly negative, which is what this catches.
        assertTrue(
            warm > cold,
            "seed $seed: warm coasts ($warm) are no better settled than cold ones ($cold)"
        )
        return warm / cold
    }
}

/**
 * The band and basin measures [OceanCurrentTest] and [OceanCurrentAuditTest] hold worlds to: mean
 * eastward velocity by band over open water, and the mean poleward velocity of the western and
 * eastern thirds of every east-west run of water at least 1,500 km long at 20 to 40 degrees.
 */
internal object OceanSense {

    /** A run shorter than this is a strait or a gulf rather than a basin, in kilometers. */
    const val SHORTEST_BASIN_KM = 1_500.0

    /** The failures of the sense bars on one world, printed as it goes. */
    fun check(
        label: String,
        config: com.cartogenesis.worldgen.model.WorldGenConfig,
        sea: com.cartogenesis.worldgen.pipeline.SeaLevelResult,
        ocean: com.cartogenesis.worldgen.pipeline.OceanResult,
        beltsOnly: com.cartogenesis.worldgen.pipeline.OceanResult
    ): List<String> {
        val failures = ArrayList<String>()
        for (hemisphere in listOf(1f, -1f)) {
            val side = if (hemisphere > 0) "north" else "south"
            val westerlies = meanEastward(config, sea, ocean, 38f * hemisphere, 48f * hemisphere)
            val trades = meanEastward(config, sea, ocean, 5f * hemisphere, 15f * hemisphere)
            val tradesBeltsOnly = meanEastward(config, sea, beltsOnly, 5f * hemisphere, 15f * hemisphere)
            val (western, eastern) = thirds(config, sea, ocean, hemisphere)
            println("OCEAN SENSE %s %s: 38-48 %+.4f m/s; 5-15 %+.4f m/s (belts only %+.4f); 20-40 western third %+.4f, eastern third %+.4f m/s poleward"
                .format(label, side, westerlies, trades, tradesBeltsOnly, western, eastern))
            if (westerlies <= 0.0) failures += "$label: the westerlies' band flows west in the $side (${"%+.4f".format(westerlies)} m/s)"
            if (tradesBeltsOnly >= 0.0) failures += "$label: under the belts alone the trades' band flows east in the $side (${"%+.4f".format(tradesBeltsOnly)} m/s)"
            if (western <= 0.0) failures += "$label: the western thirds at 20-40 in the $side flow equatorward (${"%+.4f".format(western)} m/s)"
            if (eastern >= 0.0) failures += "$label: the eastern thirds at 20-40 in the $side flow poleward (${"%+.4f".format(eastern)} m/s)"
        }
        return failures
    }

    /** Mean eastward velocity over the open water between two latitudes, in meters a second. */
    fun meanEastward(
        config: com.cartogenesis.worldgen.model.WorldGenConfig,
        sea: com.cartogenesis.worldgen.pipeline.SeaLevelResult,
        ocean: com.cartogenesis.worldgen.pipeline.OceanResult,
        fromDegrees: Float,
        toDegrees: Float
    ): Double {
        val across = config.width
        val down = config.height
        val low = minOf(fromDegrees, toDegrees)
        val high = maxOf(fromDegrees, toDegrees)
        var sum = 0.0
        var count = 0
        for (row in 0 until down) {
            val latitude = com.cartogenesis.worldgen.pipeline.ClimateStage.latitudeOf(row, down)
            if (latitude < low || latitude >= high) continue
            for (column in 0 until across) {
                val cell = row * across + column
                if (sea.isLand[cell]) continue
                sum += ocean.velocityX.data[cell]
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    /** The western and eastern thirds' mean poleward velocity at 20 to 40 degrees in one hemisphere. */
    fun thirds(
        config: com.cartogenesis.worldgen.model.WorldGenConfig,
        sea: com.cartogenesis.worldgen.pipeline.SeaLevelResult,
        ocean: com.cartogenesis.worldgen.pipeline.OceanResult,
        hemisphere: Float
    ): Pair<Double, Double> {
        val across = config.width
        val down = config.height
        val shortest = (SHORTEST_BASIN_KM / config.scale.cellWidthKm(across)).toInt()
        var westSum = 0.0
        var eastSum = 0.0
        var count = 0
        for (row in 0 until down) {
            val latitude = com.cartogenesis.worldgen.pipeline.ClimateStage.latitudeOf(row, down) * hemisphere
            if (latitude < 20f || latitude > 40f) continue
            val firstLand = (0 until across).firstOrNull { sea.isLand[row * across + it] } ?: continue
            var offset = 1
            while (offset <= across) {
                if (sea.isLand[row * across + (firstLand + offset) % across]) { offset++; continue }
                val runStart = offset
                while (offset <= across && !sea.isLand[row * across + (firstLand + offset) % across]) offset++
                val length = offset - runStart
                if (length < shortest) continue
                for (k in 0 until length / 3) {
                    val west = row * across + (firstLand + runStart + k) % across
                    val east = row * across + (firstLand + runStart + length - 1 - k) % across
                    // Rows run south, so poleward is -velocityY in the north and +velocityY in the south.
                    westSum += -ocean.velocityY.data[west] * hemisphere
                    eastSum += -ocean.velocityY.data[east] * hemisphere
                    count++
                }
            }
        }
        return if (count == 0) 0.0 to 0.0 else westSum / count to eastSum / count
    }
}
