package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import kotlin.math.abs
import org.junit.Test

/**
 * TEMPORARY, NOT FOR COMMIT. Measurements that choose the guards' selectors.
 */
class H2BaselineDump {

    private val seeds = listOf(7L, 42L, 1234L, 99L)
    private fun config(seed: Long) = WorldGenConfig(seed = seed, width = 512, height = 512)
    private fun WorldGenConfig.off() = copy(climate = climate.copy(snowBalance = false))

    /** Which "cold dry interior" definition names Siberia rather than Antarctica. */
    @Test
    fun `choose the cold dry interior selector`() {
        val variants = listOf(
            Triple(5f, -20f, 300f),
            Triple(5f, -15f, 300f),
            Triple(2f, -20f, 400f),
            Triple(8f, -20f, 400f),
            Triple(5f, -25f, 500f)
        )
        val onTally = HashMap<Triple<Float, Float, Float>, IntArray>()
        seeds.forEach { seed ->
            val on = WorldGenerationEngine.generateBlocking(config(seed))
            val off = WorldGenerationEngine.generateBlocking(config(seed).off())
            val distance = ClimateStage.waterDistance(config(seed), on.sea)
            val continental = 3f * config(seed).ocean.coastalReach.coerceAtLeast(1)
            variants.forEach { v ->
                val (summerMin, winterMax, mmMax) = v
                val cells = (0 until 512 * 512).filter { i ->
                    on.sea.isLand[i] &&
                        on.climate.summerTemperature.data[i] > summerMin &&
                        on.climate.winterTemperature.data[i] < winterMax &&
                        on.climate.precipitationMm.data[i] < mmMax &&
                        distance.data[i] > continental
                }
                val onIce = cells.count { on.climate.biome[it] == Biome.ICE_SHEET }
                val offIce = cells.count { off.climate.biome[it] == Biome.ICE_SHEET }
                val t = onTally.getOrPut(v) { IntArray(3) }
                t[0] += cells.size; t[1] += offIce; t[2] += onIce
                println(
                    "H2SEL seed=$seed summer>$summerMin winter<$winterMax mm<$mmMax:" +
                        " ${cells.size} cells, ice off ${pct(offIce, cells.size)} on ${pct(onIce, cells.size)}"
                )
            }
        }
        onTally.forEach { (v, t) ->
            println("H2SEL POOLED $v cells=${t[0]} ice off ${pct(t[1], t[0])} on ${pct(t[2], t[0])}")
        }
    }

    /** How much frozen ground each glacial-maximum offset leaves, against the pre-H2 mask. */
    @Test
    fun `sweep the glacial maximum offset`() {
        listOf(42L to 512, 718106L to 1024).forEach { (seed, size) ->
            val cfg = WorldGenConfig(seed = seed, width = 128, height = 128).atResolution(size, size)
                .let { if (seed == 718106L) it.copy(seaLevel = 0.70f) else it }
            val terrain = com.cartogenesis.worldgen.pipeline.TerrainStage.generate(cfg)
            val plates = com.cartogenesis.worldgen.pipeline.PlateStage.generate(cfg, terrain)
            val erosion = com.cartogenesis.worldgen.pipeline.erodeBlocking(cfg, plates.height)
            val sea = SeaLevelStage.apply(erosion.height, cfg.seaLevel, cfg.sea)
            val old = ClimateStage.buildTemperature(cfg, sea)
            var oldFrozen = 0
            for (i in old.data.indices) if (sea.isLand[i] && old.data[i] <= 0f) oldFrozen++
            val ocean = OceanStage.withoutCurrents(cfg, sea)
            val line = StringBuilder("H2LGM seed=$seed size=$size land=${sea.landCellCount} pre-H2 frozen=$oldFrozen")
            listOf(0f, 3f, 4.5f, 6f, 7.5f, 9f).forEach { cooling ->
                val b = ClimateStage.provisionalSnowBalance(cfg, sea, ocean, cooling)
                var n = 0
                for (i in b.data.indices) if (sea.isLand[i] && b.data[i] > 0f) n++
                line.append(" | ${cooling}C=$n")
            }
            println(line)
        }
    }

    /** At equal summer temperature, does ice follow the snow? */
    @Test
    fun `report ice against rainfall at equal temperature`() {
        val windows = listOf(-6f to -3f, -4f to -1f, -2f to 1f, 0f to 3f, 2f to 5f)
        seeds.forEach { seed ->
            val on = WorldGenerationEngine.generateBlocking(config(seed))
            val off = WorldGenerationEngine.generateBlocking(config(seed).off())
            windows.forEach { (lo, hi) ->
                println("H2WARM seed=$seed summer $lo..$hi balance ${quartiles(on, lo, hi)}")
                println("H2WARM seed=$seed summer $lo..$hi control ${quartiles(off, lo, hi)}")
            }
        }
    }

    private fun quartiles(world: WorldMap, lo: Float, hi: Float): String {
        val cells = (0 until 512 * 512).filter { i ->
            world.sea.isLand[i] && world.climate.summerTemperature.data[i] in lo..hi
        }
        if (cells.size < 200) return "only ${cells.size} cells"
        val rain = cells.map { world.climate.precipitationMm.data[it] }.sorted()
        val dryCut = rain[rain.size / 4]
        val wetCut = rain[rain.size * 3 / 4]
        val wet = cells.filter { world.climate.precipitationMm.data[it] >= wetCut }
        val dry = cells.filter { world.climate.precipitationMm.data[it] <= dryCut }
        fun iced(c: List<Int>) = c.count { world.climate.biome[it] == Biome.ICE_SHEET }
        fun floor(c: List<Int>): String {
            val e = c.filter { world.climate.biome[it] == Biome.ICE_SHEET }
                .map { world.sea.relativeElevation.data[it] }.sorted()
            return if (e.size < 20) "-" else "%.3f".format(e[e.size / 20])
        }
        return "n=${cells.size} wet ${pct(iced(wet), wet.size)} iced (floor ${floor(wet)}," +
            " median rain ${"%.0f".format(rain[rain.size * 7 / 8])}mm)" +
            " dry ${pct(iced(dry), dry.size)} iced (floor ${floor(dry)}," +
            " ${"%.0f".format(rain[rain.size / 8])}mm)"
    }

    /** The snowline, wet quarter against dry quarter, band by band. */
    @Test
    fun `report snowlines band by band`() {
        seeds.forEach { seed ->
            val on = WorldGenerationEngine.generateBlocking(config(seed))
            val off = WorldGenerationEngine.generateBlocking(config(seed).off())
            println("H2SNOW seed=$seed balance: ${bands(on)}")
            println("H2SNOW seed=$seed control: ${bands(off)}")
        }
    }

    private fun bands(world: WorldMap): String {
        val out = StringBuilder()
        var gaps = 0.0
        var counted = 0
        for (low in 30..85 step 5) {
            val band = (0 until 512 * 512).filter { i ->
                world.sea.isLand[i] &&
                    abs(ClimateStage.latitudeOf(i / 512, 512)) >= low &&
                    abs(ClimateStage.latitudeOf(i / 512, 512)) < low + 5
            }
            if (band.size < 500) continue
            val rain = band.map { world.climate.precipitationMm.data[it] }.sorted()
            val dryCut = rain[rain.size / 4]
            val wetCut = rain[rain.size * 3 / 4]
            fun line(cells: List<Int>): Float? {
                val iced = cells.filter { world.climate.biome[it] == Biome.ICE_SHEET }
                    .map { world.sea.relativeElevation.data[it] }.sorted()
                return if (iced.size < 20) null else iced[iced.size / 20]
            }
            val wet = line(band.filter { world.climate.precipitationMm.data[it] >= wetCut })
            val dry = line(band.filter { world.climate.precipitationMm.data[it] <= dryCut })
            val highest = band.maxOf { world.sea.relativeElevation.data[it] }
            if (wet == null && dry == null) continue
            val wetLine = wet ?: highest
            val dryLine = dry ?: highest
            out.append(" [$low-${low + 5} n=${band.size} wet=${"%.3f".format(wetLine)}")
            out.append(if (wet == null) "*" else "")
            out.append("/dry=${"%.3f".format(dryLine)}")
            out.append(if (dry == null) "*" else "")
            out.append("]")
            if (wet != null || dry != null) {
                gaps += (dryLine - wetLine)
                counted++
            }
        }
        return "$out mean gap ${"%.3f".format(if (counted == 0) 0.0 else gaps / counted)} over $counted bands"
    }

    /** What the provisional ocean's currents are worth to the ice mask, against their 2.1s at 2048. */
    @Test
    fun `report what the provisional ocean buys`() {
        seeds.forEach { seed ->
            val cfg = config(seed)
            val world = WorldGenerationEngine.generateBlocking(cfg)
            val sea = SeaLevelStage.apply(world.erosion.height, cfg.seaLevel, cfg.sea)
            val withCurrents =
                ClimateStage.provisionalSnowBalance(cfg, sea, OceanStage.generate(cfg, sea))
            val still = cfg.copy(ocean = cfg.ocean.copy(enabled = false))
            val without =
                ClimateStage.provisionalSnowBalance(cfg, sea, OceanStage.generate(still, sea))
            var a = 0
            var b = 0
            var differ = 0
            for (i in withCurrents.data.indices) {
                if (!sea.isLand[i]) continue
                val x = withCurrents.data[i] > 0f
                val y = without.data[i] > 0f
                if (x) a++
                if (y) b++
                if (x != y) differ++
            }
            println("H2OCEAN seed=$seed frozen with currents=$a without=$b differ=$differ")
        }
    }

    private fun pct(n: Int, of: Int) = if (of == 0) "-" else "${"%.1f".format(n * 100.0 / of)}%"
}
