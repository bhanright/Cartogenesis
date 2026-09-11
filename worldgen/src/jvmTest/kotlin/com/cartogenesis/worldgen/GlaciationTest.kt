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

    /**
     * The lattice guard: on flat frozen country, the ice must not behave like a valley glacier.
     *
     * What the author saw on seed 718106 was a cross-hatched mesh of straight one- and two-cell
     * lines of water at 0, 45 and 90 degrees over the whole cold lowland — the eight directions of
     * the D8 flow grid, showing through. The cause was that the stage ran the valley machinery
     * everywhere the ice was: on a plain, every flow path was given a U-trough, a staircase of
     * over-deepened basins and a recessional moraine bar at the end of every reach, and the flow
     * paths on a plain are straight, parallel and meet at 45 degrees.
     *
     * So the two things measured here are the two machines that built the mesh, and both are
     * measured on flat cold ground only, which is where sheet ice belongs:
     *
     *  - **the trough**: what share of that ground the ice excavates by a trough's depth. Sheet ice
     *    planes a province and gouges basins in it; it does not cut a valley down every flow line.
     *  - **the bar**: what share of it the ice lays till across. A recessional moraine is a dam
     *    *across a valley*, and a plain has no valleys to dam — every one of those bars on a plain
     *    was a straight line of the mesh.
     *
     * Both are measured from outside the stage, as the difference between the world with the ice
     * and the same world without it, so the same measurement runs against any version of the code.
     *
     * Two worlds, at two sea levels and two grid sizes, because the defect scales with how much
     * flat cold lowland there is and the sea-level slider is the one control the desktop app gives
     * the author over that: the world he reported carried far more land than the default. The 1024
     * case is the desktop's own default resolution, where every length in
     * [com.cartogenesis.worldgen.model.GlaciationConfig] is doubled by `atResolution` and the mesh
     * was at its worst.
     *
     * Measured, before the regime split and after, on seed 718106:
     *
     * | | 512 at sea 0.50 | 1024 at sea 0.70 |
     * |---|---|---|
     * | flat cold cells | 9036 | 52317 |
     * | cut to trough depth | 23.3% -> 3.3% | 36.8% -> 2.9% |
     * | till laid | 2.91% -> 0.03% | 3.05% -> 0.00% |
     * | mean cut | 0.0118 -> 0.0072 | 0.0175 -> 0.0071 |
     *
     * The mean cut barely falls, and that is the point rather than a disappointment: sheet ice does
     * remove a great deal of rock from a shield, it just removes it *broadly*. What changes is not
     * how much comes off but whether it comes off in channels.
     *
     * What the shape of the water itself says is reported but *not* asserted, and the reason is
     * worth recording for whoever measures this next. The obvious statistics — the share of lake
     * cells lying in runs of six or more along a grid axis at a width of one or two, the bearing
     * anisotropy of lake-cell pairs, the grid-alignment of the bearings between separate lakes,
     * lake perimeter over area — all move far less than the eye does, and one of them moves the
     * wrong way. The run statistic on the water the ice adds to flat cold ground goes 0.271 -> 0.109
     * at 1024 but 0.111 -> 0.194 at 512, where after the fix there are only a couple of hundred
     * such cells and a handful of scour basins decide the figure. The reason none of them
     * discriminates is that the
     * pre-fix carving was never a set of lines: it was a *blanket*, a quarter to a third of the cold
     * lowland cut to trough depth, and the mesh the eye saw was the un-cut ridges left standing
     * between overlapping troughs, with the till bars ponding water along them. So the guard
     * measures the two machines rather than the pattern they left, and the pattern is checked by
     * looking at the render, which is what found it in the first place.
     */
    @Test
    fun `flat frozen country is scoured, not grooved along the flow grid`() {
        // 1024 first, and deliberately: it is the resolution the desktop app opens at, it is where
        // the author saw the mesh, and it is the case that discriminates. The 512 world at the same
        // sea level is the one the crops in `DebugMapDump` have always shown — mild enough that the
        // defect got through review there — and the low sea level is the flat-lowland case.
        val results = LinkedHashMap<String, IceWork>()
        listOf(
            Triple(1024, 0.70f, "1024 at sea 0.70, the desktop default"),
            Triple(512, 0.70f, "512 at sea 0.70"),
            Triple(512, 0.50f, "512 at sea 0.50")
        ).forEach { (size, level, label) ->
            val config = WorldGenConfig(seed = 718106L, width = 512, height = 512)
                .copy(seaLevel = level)
                .atResolution(size, size)
            val iced = WorldGenerationEngine.generateBlocking(config)
            val bare = WorldGenerationEngine.generateBlocking(
                config.copy(glaciation = config.glaciation.copy(enabled = false))
            )
            val work = measureIceWork(bare, iced, config)
            results[label] = work
            println(
                "LATTICE seed 718106 $label: coldFlat=${work.coldFlat}" +
                    " deepCut=${"%.4f".format(work.deepCut)} till=${"%.4f".format(work.till)}" +
                    " meanCut=${"%.5f".format(work.meanCut)}" +
                    " lakeShareOfLand=${"%.4f".format(work.lakeShareOfLand)}" +
                    " addedWater=${work.addedWater} of which in straight grid runs" +
                    " ${"%.3f".format(work.addedAxial)}"
            )
        }

        results.forEach { (label, work) ->
            assertTrue("no flat cold country to measure on $label", work.coldFlat > 5000)
            assertTrue(
                "on $label the ice cut a trough's depth into" +
                    " ${"%.1f".format(work.deepCut * 100)}% of the flat frozen country" +
                    " (${work.coldFlat} cells, mean cut ${"%.5f".format(work.meanCut)}): flat" +
                    " ground is under a sheet, and a sheet does not drive a valley down every" +
                    " line of the flow grid",
                work.deepCut < 0.15f
            )
            assertTrue(
                "on $label the ice laid till on ${"%.2f".format(work.till * 100)}% of the flat" +
                    " frozen country — a recessional moraine is a bar across a valley, and every" +
                    " one of them on a plain is a straight line of the lattice",
                work.till < 0.01f
            )
        }

        // Resolution invariance, which is half the defect. Every length this stage uses is in cells
        // and is doubled by `atResolution`, so a threshold expressed per *cell* admits four times as
        // many parallel flow paths per unit of map at 1024 as at 512 while each trough stays as
        // narrow a fraction of the map as before — which is exactly why the mesh appeared at the
        // desktop's own default resolution and not in the 512 crops this stage was reviewed on. The
        // contract, in the spirit of `ResolutionScalingTest`: the same world at twice the grid is
        // the same world with more detail in it, so the water covers roughly the same share of the
        // land. Before the regime split that share went 1.4% -> 3.8% from 512 to 1024, a factor of
        // 2.8; after it, 1.5% -> 1.9%.
        val fine = results.getValue("1024 at sea 0.70, the desktop default").lakeShareOfLand
        val coarse = results.getValue("512 at sea 0.70").lakeShareOfLand
        assertTrue("no water to compare across resolutions", coarse > 0.002f && fine > 0.002f)
        val growth = fine / coarse
        assertTrue(
            "doubling the grid multiplies the lake share of land by" +
                " ${"%.2f".format(growth)} (512: ${"%.4f".format(coarse)}," +
                " 1024: ${"%.4f".format(fine)}) — glacial features are being selected per cell" +
                " rather than per unit of map, so a finer grid grows more of them",
            growth < 1.7f
        )
    }

    private class IceWork(
        val coldFlat: Int,
        val deepCut: Float,
        val till: Float,
        val meanCut: Float,
        val addedWater: Int,
        val addedAxial: Float,
        /** Standing fresh water as a share of all land: the resolution-invariant figure. */
        val lakeShareOfLand: Float
    )

    /**
     * What the ice did to the flat cold country, as the difference between two worlds that differ
     * only by [com.cartogenesis.worldgen.model.GlaciationConfig.enabled].
     */
    private fun measureIceWork(bare: WorldMap, iced: WorldMap, config: WorldGenConfig): IceWork {
        val w = bare.width
        val h = bare.height
        // Flat is measured on the untouched world, at the scale of the trough the ice would cut
        // there — two trough-widths, written out rather than read from
        // [com.cartogenesis.worldgen.model.GlaciationConfig.reliefWindow], so that the region the
        // guard looks at cannot be moved by the settings it is guarding.
        val radius = (2f * config.glaciation.valleyWidth).toInt()
        val flat = flatGround(bare, radius, FLAT_RELIEF)
        val before = bare.sea.relativeElevation.data
        val after = iced.sea.relativeElevation.data

        var coldFlat = 0
        var deep = 0
        var laid = 0
        var sum = 0.0
        val added = BooleanArray(w * h)
        for (i in 0 until w * h) {
            if (!flat[i] || !glaciatedZone(bare.climate.biome[i])) continue
            coldFlat++
            val cut = before[i] - after[i]
            sum += cut.toDouble()
            if (cut >= TROUGH_DEPTH) deep++
            if (cut <= -TILL) laid++
            if (iced.rivers.lakes.lakeId[i] >= 0 && bare.rivers.lakes.lakeId[i] < 0) added[i] = true
        }
        val n = coldFlat.coerceAtLeast(1)
        var lakeCells = 0
        for (i in 0 until w * h) if (iced.rivers.lakes.lakeId[i] >= 0) lakeCells++
        return IceWork(
            coldFlat = coldFlat,
            deepCut = deep.toFloat() / n,
            till = laid.toFloat() / n,
            meanCut = (sum / n).toFloat(),
            addedWater = added.count { it },
            addedAxial = axialRunShare(added, w, h),
            lakeShareOfLand = lakeCells.toFloat() / iced.sea.landCellCount.coerceAtLeast(1)
        )
    }

    /** Land whose elevation range within [radius] cells is under [limit] of the land's range. */
    private fun flatGround(world: WorldMap, radius: Int, limit: Float): BooleanArray {
        val w = world.width
        val h = world.height
        val rel = world.sea.relativeElevation.data
        val isLand = world.sea.isLand
        val surface = FloatArray(w * h) { rel[it].coerceAtLeast(0f) }
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!isLand[i]) continue
                var lo = Float.MAX_VALUE
                var hi = -Float.MAX_VALUE
                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    for (dx in -radius..radius) {
                        var nx = (x + dx) % w
                        if (nx < 0) nx += w
                        val v = surface[ny * w + nx]
                        if (v < lo) lo = v
                        if (v > hi) hi = v
                    }
                }
                out[i] = hi - lo < limit
            }
        }
        return out
    }

    /**
     * The share of a water mask lying in a straight run of six or more cells along one of the four
     * grid directions at a width of one or two — the shape the author described, reported rather
     * than asserted for the reason given on the guard above.
     */
    private fun axialRunShare(water: BooleanArray, w: Int, h: Int): Float {
        fun at(x: Int, y: Int): Boolean {
            if (y < 0 || y >= h) return false
            var nx = x % w
            if (nx < 0) nx += w
            return water[y * w + nx]
        }
        val axes = arrayOf(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(1, -1))
        var total = 0
        var lines = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!at(x, y)) continue
                total++
                for (a in axes) {
                    var run = 1
                    var s = 1
                    while (run < 64 && at(x + a[0] * s, y + a[1] * s)) { run++; s++ }
                    s = 1
                    while (run < 64 && at(x - a[0] * s, y - a[1] * s)) { run++; s++ }
                    if (run < 6) continue
                    var thick = 1
                    s = 1
                    while (thick <= 2 && at(x - a[1] * s, y + a[0] * s)) { thick++; s++ }
                    s = 1
                    while (thick <= 2 && at(x + a[1] * s, y - a[0] * s)) { thick++; s++ }
                    if (thick <= 2) { lines++; break }
                }
            }
        }
        return if (total == 0) 0f else lines.toFloat() / total
    }

    private companion object {
        /**
         * The elevation range, as a fraction of the land's own, under which ground counts as flat
         * for this guard.
         *
         * Deliberately *tighter* than [com.cartogenesis.worldgen.model.GlaciationConfig.valleyRelief],
         * so the ground measured is unambiguously flat rather than merely whatever the stage
         * decided to call a sheet. A guard whose region is defined by the setting it is guarding
         * moves with that setting and proves nothing.
         */
        const val FLAT_RELIEF = 0.25f

        /** A trough's depth: what a full glacier cuts, over-deepening included. */
        const val TROUGH_DEPTH = 0.02f

        /** Enough till to be a bar rather than float rounding. */
        const val TILL = 0.001f
    }

    /** The stage's own tally, which is not required to balance but is required to be looked at. */
    private fun reportBudget(config: WorldGenConfig, world: WorldMap) {
        val sea = SeaLevelStage.apply(world.erosion.height, config.seaLevel, config.sea)
        GlaciationStage.apply(config, sea) { mass ->
            println(
                "GLACIATION budget frozen=${mass.frozenCells}" +
                    " channelled=${mass.channelledCells} ice=${mass.glacierCells}" +
                    " sheet=${mass.sheetCells} scour=${mass.scourCells}/${mass.scourBasins}" +
                    " cirques=${mass.cirques} moraines=${mass.moraines} riegels=${mass.riegels}" +
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
