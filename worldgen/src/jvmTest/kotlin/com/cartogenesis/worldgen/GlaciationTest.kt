package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.GlaciationStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the ice leaves the country it worked on looking like glaciated country.
 *
 * The signature is not the trough, which is hard to measure and easy to fake, but the lakes. A
 * river network cannot leave a hollow in its own bed: every cell grades toward its outlet, so
 * standing water inland is the exception and needs a dam or a tectonic basin to explain it.
 * Ice can and does — it is a solid being pushed from behind, it gouges where it is thick and
 * confined, and it drops a wall of till at its snout — which is why Finland has two hundred
 * thousand lakes and the Iberian plateau at the same distance from its sea has almost none.
 *
 * So the guard is a density ratio between the two kinds of country on one map, which also makes it
 * immune to a world simply having more water in it: both zones are measured on the same world, and
 * the control world differs only by [com.cartogenesis.worldgen.model.GlaciationConfig.enabled].
 */
class GlaciationTest {

    private val base = WorldGenConfig(seed = 42L, width = 512, height = 512)

    /**
     * Glaciated country: the ice and tundra the carving is bounded to, *and the taiga below it*.
     *
     * The third one is not a loosening, it is the whole point, and the measurement found it the
     * hard way. A glacier's bed is not where the ice is thickest, it is the valley the ice runs
     * down, and that valley is below the snowline by definition — an ablation zone is what a snout
     * is. So the lakes this stage makes come out in the boreal valleys draining the frozen uplands,
     * at one to five degrees, and the first version of this guard measured the bare plateau above
     * them and found almost nothing. That is also where they are on Earth: Windermere, Como, the
     * Finger Lakes and the whole of the Canadian Shield's two million lakes lie in country that is
     * boreal now and was under ice twenty thousand years ago, not in country that is under ice
     * today.
     */
    private fun glaciatedZone(biome: Biome) =
        biome == Biome.ICE_SHEET || biome == Biome.TUNDRA || biome == Biome.TAIGA

    /**
     * The control: warm-temperate and dry-temperate country, which on Earth is the ground the ice
     * sheets stopped short of. Iberia, the Po plain and the American southwest against Finland and
     * Ontario, which is exactly the contrast being claimed.
     */
    private fun temperateZone(biome: Biome) = when (biome) {
        Biome.TEMPERATE_FOREST, Biome.TEMPERATE_RAINFOREST, Biome.GRASSLAND,
        Biome.SHRUBLAND, Biome.MEDITERRANEAN -> true
        else -> false
    }

    @Test
    fun `glaciated country holds far more lakes than temperate country`() {
        val iced = WorldGenerationEngine.generateBlocking(base)
        val bare = WorldGenerationEngine.generateBlocking(
            base.copy(glaciation = base.glaciation.copy(enabled = false))
        )

        reportBudget(base, iced)

        val without = measure(bare, "GLACIATION off")
        val with = measure(iced, "GLACIATION on ")

        // Vacuity checks first. A ratio computed over a handful of cells says nothing, and the
        // first version of this guard could have passed on a world with no cold ground at all.
        assertTrue("no glaciated country to measure", with.coldLand > 2000)
        assertTrue("no temperate country to measure", with.warmLand > 2000)
        assertTrue("control has no glaciated country", without.coldLand > 2000)

        assertTrue(
            "without glaciation the two zones are alike: cold ${"%.2f".format(without.coldDensity)}" +
                " against temperate ${"%.2f".format(without.warmDensity)} lakes per 10k cells," +
                " ratio ${"%.2f".format(without.ratio)} — if this is already above 3 the guard is" +
                " measuring something other than the ice",
            without.ratio < 3f
        )
        assertTrue(
            "glaciated country holds only ${"%.2f".format(with.ratio)}x the lake density of" +
                " temperate country (cold ${"%.2f".format(with.coldDensity)}, temperate" +
                " ${"%.2f".format(with.warmDensity)} lakes per 10k cells)",
            with.ratio >= 3f
        )
    }

    /** The stage's own tally, which is not required to balance but is required to be looked at. */
    private fun reportBudget(config: WorldGenConfig, world: WorldMap) {
        val sea = SeaLevelStage.apply(world.erosion.height, config.seaLevel, config.sea)
        GlaciationStage.apply(config, sea) { mass ->
            println(
                "GLACIATION budget frozen=${mass.frozenCells} ice=${mass.glacierCells}" +
                    " cirques=${mass.cirques} moraines=${mass.moraines}" +
                    " excavated=${"%.2f".format(mass.excavated)}" +
                    " deposited=${"%.2f".format(mass.deposited)}" +
                    " seafloor=${"%.2f".format(mass.submarine)}"
            )
        }
    }

    private class Zones(
        val coldLand: Int,
        val warmLand: Int,
        val coldLakes: Int,
        val warmLakes: Int,
        val coldLakeCells: Int,
        val warmLakeCells: Int
    ) {
        val coldDensity = coldLakes * 10_000f / coldLand.coerceAtLeast(1)
        val warmDensity = warmLakes * 10_000f / warmLand.coerceAtLeast(1)

        /**
         * A floor under the denominator rather than a division by zero. Temperate country with no
         * lakes at all is the strongest possible version of the claim, not an undefined one, so it
         * reads as a very large ratio — but the floor keeps it finite, and it is small enough
         * (a tenth of a lake per ten thousand cells) that it cannot manufacture a pass: the
         * numerator still has to clear three tenths of a lake, which is more than zero.
         */
        val ratio = coldDensity / maxOf(warmDensity, 0.1f)
    }

    private fun measure(world: WorldMap, label: String): Zones {
        var coldLand = 0
        var warmLand = 0
        var coldLakeCells = 0
        var warmLakeCells = 0
        val lakeCold = IntArray(world.rivers.lakes.lakes.size)
        val lakeWarm = IntArray(world.rivers.lakes.lakes.size)

        for (i in world.climate.biome.indices) {
            if (!world.sea.isLand[i]) continue
            val cold = glaciatedZone(world.climate.biome[i])
            val warm = temperateZone(world.climate.biome[i])
            if (cold) coldLand++
            if (warm) warmLand++

            val lake = world.rivers.lakes.lakeId[i]
            if (lake < 0) continue
            if (cold) { coldLakeCells++; lakeCold[lake]++ }
            if (warm) { warmLakeCells++; lakeWarm[lake]++ }
        }

        // A lake belongs to the zone most of it lies in, so one body of water is never counted
        // twice and a lake straddling the tree line lands on the side it mostly occupies.
        var coldLakes = 0
        var warmLakes = 0
        world.rivers.lakes.lakes.forEach { lake ->
            val c = lakeCold[lake.id]
            val t = lakeWarm[lake.id]
            when {
                c > t && c * 2 >= lake.cellCount -> coldLakes++
                t > c && t * 2 >= lake.cellCount -> warmLakes++
            }
        }

        val zones = Zones(coldLand, warmLand, coldLakes, warmLakes, coldLakeCells, warmLakeCells)
        println(
            "$label lakes=${world.rivers.lakes.lakes.size}" +
                " cold: ${zones.coldLakes} lakes / $coldLand cells" +
                " (${"%.2f".format(zones.coldDensity)} per 10k, ${coldLakeCells} lake cells)" +
                " temperate: ${zones.warmLakes} lakes / $warmLand cells" +
                " (${"%.2f".format(zones.warmDensity)} per 10k, ${warmLakeCells} lake cells)" +
                " ratio=${"%.2f".format(zones.ratio)}"
        )
        return zones
    }
}
