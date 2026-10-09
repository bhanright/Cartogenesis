package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.EnergyBalance
import com.cartogenesis.worldgen.pipeline.OceanStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Which way the solved gyres turn on the standard worlds, against the wind that drives them.
 *
 * Sign bars with no world-derived figure in them, each read off Stommel's solution for a basin under
 * this generator's own belts, and each held on the four standard seeds per merge at
 * [SharedWorlds.COARSE_ROWS], since a gyre's sense and a share of the energy balance's transport
 * are the ground's figures and not the grid's detail ([OceanCurrentAuditTest] holds the author's
 * 2048 world). Earth's surface drifter climatology
 * (Lumpkin and Johnson 2013, *J. Geophys. Res. Oceans* 118, 2992-3006) has the same signs: eastward
 * mean flow at 40 to 50 degrees and a poleward western boundary current in every subtropical basin.
 */
class OceanCurrentTest : BorrowsSharedWorlds() {

    private companion object {
        /**
         * The share of the energy balance's meridional transport the anomaly's row means may stand
         * for: a chosen tolerance, not a derived bound. See `the anomaly's row means carry a small
         * share of the energy balance's transport` for why this figure.
         */
        const val DOUBLE_COUNT_SHARE = 0.02
    }

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
        // Armed again at K2: on the Earth-sized planet the control ocean under the belts' wind
        // alone solves on every standard world, seed 42 at 256 rows included, so K1's stall no
        // longer holds here, nor in the three other clauses that read the control.
        val failures = ArrayList<String>()
        for (seed in SharedWorlds.STANDARD_SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, SharedWorlds.COARSE_ROWS))
            val beltsOnly = world.config.copy(climate = world.config.climate.copy(pressureWinds = false))
            val beltsOcean = OceanStage.generate(beltsOnly, world.sea)
            failures += OceanSense.check("seed $seed at ${SharedWorlds.COARSE_ROWS} rows", world.config, world.sea, world.ocean, beltsOcean)
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
        val seeds = listOf(7L, 42L, 1234L)
        val gaps = seeds.map { seed -> checkCoasts(seed) }
        // The floor, per seed. Seed 7's gap was 4.1% at L1 and -0.5% at its review round while the
        // rift valleys were 328 km across, when it was recorded; with them at Earth's width it is
        // back over the floor, and armed again (docs/DESIGN_LEDGER.md, L1).
        val under = seeds.zip(gaps).filter { (_, gap) -> gap <= 1.0 }
            .joinToString("; ") { (seed, gap) -> "seed $seed at %.1f%%".format((gap - 1) * 100) }
        val pooled = gaps.average()
        println(
            "OCEAN pooled coastal gap %.1f%% over %d seeds".format((pooled - 1) * 100, gaps.size)
        )
        // Recorded at K2, when settlement waited for the atlas overhaul on the Earth-sized planet,
        // and armed again at C1b, whose climate passes it (docs/DESIGN_LEDGER.md, K2 and C1b).
        val complaints = listOfNotNull(
            under.takeIf { it.isNotEmpty() }?.let { "cold coasts settled no worse than warm ones: $it" },
            "pooled %.1f%%".format((pooled - 1) * 100).takeIf { pooled <= 1.02 }
        )
        assertTrue(complaints.isEmpty(), complaints.joinToString("; "))
    }

    /**
     * The anomaly's reference is a band mean, so a row's anomaly does not average exactly to zero
     * (see `OceanStage.buildAnomaly`); the heat those row means stand for is held to a small share of
     * the energy balance's own meridional transport, so that it is not counted a second time by much.
     *
     * A row whose water sits `m` degrees off its zonal mean gives the air `λ m` watts a square
     * meter more than the energy balance knows about, `λ` the surface exchange. Summed from a pole
     * to a latitude, with the whole ocean's net taken out first, that is a transport across the
     * latitude the currents carry on top of the balance's own. It is held to a share of the
     * balance's transport there, `2π a² D cos φ dT/dφ`, read as `EnergyBalanceTest`'s transport
     * report reads it, on the same world, and must be under [DOUBLE_COUNT_SHARE] of it.
     *
     * Both bars here are chosen tolerances, not derived error bounds. 2% of the transport is chosen
     * because it is small beside how far the balance's own transport stands from Earth's: 4.6, 4.9
     * and 3.2 PW at 30, 45 and 60 degrees against Trenberth and Caron's 5.3, 5.0 and 3.3, misses of
     * 13, 2 and 3%; an addition that size is not one anything this generator is measured against
     * could tell apart. That comparison is the reason for the figure, not a bound on the error an
     * added transport makes, which would need the balance run again with it.
     *
     * The whole ocean's net, set aside above, is a mean offset of the sea's temperature rather
     * than a transport. It is held under `EnergyBalance.SECANT_TOLERANCE_C`, a twentieth of a
     * degree: chosen as the closeness to which the balance itself is asked to set the planet's
     * global mean when a world asks for a warmer or a cooler climate, again a comparison and not a
     * bound. Its spin-up residual, a few ten-thousandths, is printed beside it.
     *
     * Every figure must be a number: a NaN compares false with any bar, and would pass it.
     */
    @Test
    fun `the anomaly's row means carry a small share of the energy balance's transport`() {
        val failures = ArrayList<String>()
        for (seed in SharedWorlds.STANDARD_SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, SharedWorlds.COARSE_ROWS))
            val across = world.width
            val down = world.height
            val radiusMeters = world.config.scale.radiusMeters
            val exchange = EnergyBalance.SURFACE_EXCHANGE_W_PER_M2_C
            val rowSourceW = DoubleArray(down)
            val rowWaterAreaM2 = DoubleArray(down)
            for (row in 0 until down) {
                val latitude = ClimateStage.latitudeOf(row, down) * PI / 180.0
                val cellAreaM2 = (2 * PI * radiusMeters * cos(latitude) / across) * (PI * radiusMeters / down)
                var anomalySumC = 0.0
                var water = 0
                for (column in 0 until across) {
                    val cell = row * across + column
                    if (world.sea.isLand[cell]) continue
                    anomalySumC += world.ocean.anomaly.data[cell]
                    water++
                }
                rowSourceW[row] = exchange * anomalySumC * cellAreaM2
                rowWaterAreaM2[row] = water * cellAreaM2
            }
            val netPerM2 = rowSourceW.sum() / rowWaterAreaM2.sum()
            val oceanMeanC = netPerM2 / exchange
            val zonal = ClimateStage.zonalClimate(world.config, world.sea)
            val landFraction = EnergyBalance.landFractionByBand(across, down, world.sea.isLand)
            fun bandMeanC(degrees: Double): Double {
                val band = ((EnergyBalance.POLE_DEGREES - degrees) * EnergyBalance.BANDS /
                    EnergyBalance.POLE_TO_POLE_DEGREES).toInt().coerceIn(0, EnergyBalance.BANDS - 1)
                val share = landFraction[band].toDouble()
                return share * zonal.land.annualC[band] + (1 - share) * zonal.sea.annualC[band]
            }
            val shares = ArrayList<String>()
            for (degrees in listOf(30.0, 45.0, 60.0, -30.0, -45.0, -60.0)) {
                val poleward = if (degrees > 0) 1.0 else -1.0
                val step = 5.0
                val gradientPerRadian = (bandMeanC(degrees - step) - bandMeanC(degrees + step)) / (2 * step * PI / 180.0)
                val balanceW = abs(
                    2 * PI * radiusMeters * radiusMeters * EnergyBalance.diffusivityAt(abs(degrees)) *
                        cos(degrees * PI / 180.0) * gradientPerRadian
                )
                var doubleCountW = 0.0
                for (row in 0 until down) {
                    val latitude = ClimateStage.latitudeOf(row, down).toDouble()
                    if ((latitude - degrees) * poleward <= 0.0) continue
                    doubleCountW += rowSourceW[row] - netPerM2 * rowWaterAreaM2[row]
                }
                val share = abs(doubleCountW) / balanceW
                shares += "%+.0f %.2f%%".format(degrees, share * 100)
                if (!share.isFinite() || share >= DOUBLE_COUNT_SHARE) {
                    failures += "seed $seed: the row means carry %.2f%% of the balance's transport across %+.0f".format(share * 100, degrees)
                }
            }
            println("OCEAN DOUBLE COUNT seed $seed: " + shares.joinToString() +
                "; whole ocean %+.4f C against the balance's spin-up residual %.4f C".format(oceanMeanC, zonal.spinUpResidualC))
            if (!oceanMeanC.isFinite() || abs(oceanMeanC) >= EnergyBalance.SECANT_TOLERANCE_C) {
                failures += "seed $seed: the whole ocean's anomaly is %+.4f C, past the %.2f C the balance sets its mean to"
                    .format(oceanMeanC, EnergyBalance.SECANT_TOLERANCE_C)
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** The warm quartile's coastal habitability over the cold quartile's, as a ratio. */
    private fun checkCoasts(seed: Long): Double {
        val config = WorldGenConfig.forRows(seed, SharedWorlds.COARSE_ROWS)
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
        // tips slightly negative, which is what the floor in the test above catches.
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
