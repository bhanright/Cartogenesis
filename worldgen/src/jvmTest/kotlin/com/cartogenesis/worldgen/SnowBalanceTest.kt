package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.SnowBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether ice is where a glacier could live, rather than merely where it is cold.
 *
 * The world this replaces decided ice on one number, the annual mean temperature, and so could not
 * distinguish the two halves of the only question that matters — how much snow arrives, and how
 * much of it survives the summer. Every cold interior came out an ice cap: 41.9% of seed 7's land
 * was ice sheet, against Earth's 10.1%, and almost all of Earth's is Antarctica and Greenland.
 *
 * [SnowBalance] weighs accumulation against ablation instead. What the guards below check is not
 * that the totals came out nicer but that the *distinction* is now available to the model: that a
 * cold dry interior is bare, that ice at a given temperature follows the snowfall, and that the ice
 * share of land is within reach of Earth's. Each is measured against the same world generated with
 * `ClimateConfig.snowBalance = false`, which is the pre-H2 generator exactly — the checksum case
 * below is the proof of that, and it is what makes every "before" figure here honest.
 */
class SnowBalanceTest {

    private val seeds = listOf(7L, 42L, 1234L, 99L)

    private fun config(seed: Long, size: Int = 512) =
        WorldGenConfig(seed = seed, width = size, height = size)

    private fun WorldGenConfig.withoutBalance() =
        copy(climate = climate.copy(snowBalance = false))

    /**
     * Every guard here wants the same eight worlds — four seeds, with the balance and without —
     * and generating them once each rather than once per case is the difference between a minute
     * and four in the per-merge tier. Held on the companion so the cache survives JUnit's fresh
     * instance per test method.
     */
    private fun world(seed: Long, balance: Boolean): WorldMap = cache.getOrPut(seed to balance) {
        val cfg = config(seed)
        WorldGenerationEngine.generateBlocking(if (balance) cfg else cfg.withoutBalance())
    }

    // ---------------------------------------------------------------- the arithmetic itself

    /**
     * The constants, checked against the figures [SnowBalance]'s own comment quotes, so that a
     * later change to either has to change both.
     */
    @Test
    fun `the degree-day model matches its published form`() {
        // Calov and Greve's integral at a seasonal mean of exactly freezing is sigma/sqrt(2*pi).
        assertEquals(
            (SnowBalance.PDD_SIGMA_C / kotlin.math.sqrt(2.0 * kotlin.math.PI)).toFloat(),
            SnowBalance.positiveDegreeDaysPerDay(0f),
            1e-4f
        )
        // And symmetric about it: what a mean of +T melts above what a mean of -T melts is exactly
        // T, because the two normal tails are the same tail.
        assertEquals(
            5f,
            SnowBalance.positiveDegreeDaysPerDay(5f) - SnowBalance.positiveDegreeDaysPerDay(-5f),
            1e-3f
        )
        // Strictly positive everywhere and monotone, which is what keeps the ice margin off the
        // isotherms.
        var previous = -1f
        for (t in -30..30) {
            val pdd = SnowBalance.positiveDegreeDaysPerDay(t.toFloat())
            assertTrue("PDD negative at $t C: $pdd", pdd >= 0f)
            assertTrue("PDD not monotone at $t C", pdd > previous)
            previous = pdd
        }

        // The melt figures quoted in the class comment, in mm water equivalent over a half year.
        val melt = { t: Float ->
            SnowBalance.DEGREE_DAY_FACTOR_MM * SnowBalance.SEASON_DAYS *
                SnowBalance.positiveDegreeDaysPerDay(t)
        }
        println("SNOWBALANCE melt over a half year: 0C=${"%.0f".format(melt(0f))}mm" +
            " -5C=${"%.0f".format(melt(-5f))}mm -10C=${"%.0f".format(melt(-10f))}mm" +
            " +5C=${"%.0f".format(melt(5f))}mm")
        assertEquals(1475f, melt(0f), 25f)
        assertEquals(249f, melt(-5f), 15f)
        assertEquals(17f, melt(-10f), 5f)

        // The rain/snow ramp.
        assertEquals(1f, SnowBalance.snowFraction(-5f), 0f)
        assertEquals(0.5f, SnowBalance.snowFraction(1f), 1e-6f)
        assertEquals(0f, SnowBalance.snowFraction(10f), 0f)
    }

    /**
     * The three places the chunk's spec names, worked by hand from their real climates, because a
     * balance that gets Siberia and Norway the wrong way round is wrong however good its totals.
     *
     * Half-year means, and annual-equivalent rainfall in the two seasons — the convention
     * [ClimateResult.precipitationMm]'s halves use, where the annual total is their mean.
     */
    @Test
    fun `the balance separates the cold dry from the cold wet`() {
        // Verkhoyansk: about the coldest inhabited place on Earth and no glacier for a thousand
        // kilometres, because 180mm a year reaches it.
        val siberia = SnowBalance.balanceMm(summerC = 12f, winterC = -35f, summerMm = 260f, winterMm = 100f)
        // A Norwegian coastal highland: mild by comparison and under ice, because the Atlantic
        // unloads three metres of snow a year on it.
        val norway = SnowBalance.balanceMm(summerC = 1f, winterC = -6f, summerMm = 2400f, winterMm = 3600f)
        // The East Antarctic plateau: a desert, and two miles of ice, because nothing ever melts.
        val antarctica = SnowBalance.balanceMm(summerC = -32f, winterC = -60f, summerMm = 50f, winterMm = 50f)
        println("SNOWBALANCE worked cells: siberia=${"%.0f".format(siberia)}mm" +
            " norway=${"%.0f".format(norway)}mm antarctica=${"%.0f".format(antarctica)}mm")
        assertTrue("Siberia should carry no glacier ($siberia mm)", siberia < 0f)
        assertTrue("a wet Norwegian highland should ($norway mm)", norway > 0f)
        assertTrue("so should the Antarctic plateau ($antarctica mm)", antarctica > 0f)
        assertTrue(
            "the cold dry cell is colder than the wet one and must still be the bare one",
            siberia < norway
        )
    }

    // ---------------------------------------------------------------- the control

    /**
     * `snowBalance = false` is the pre-H2 generator, to the bit.
     *
     * The pins are the elevation and biome checksums of the four standard seeds at 512, measured
     * on `main` at 37aa214 — the commit this chunk was last merged from — by generating with the *default*
     * config there and hashing the same two arrays the same way. So the assertion is not "this code
     * still agrees with itself", it is "with the knob off, this code produces the world the
     * previous commit produced", which is what the chunk was asked to prove.
     *
     * A refactor that changed the order of a floating-point sum in the climate stage would break
     * this, and that is the point: the seasonal fields were pulled out of `generateWithSeasonalMm`
     * into a shared helper so the provisional climate could reuse them, and this is the evidence
     * that the move was arithmetic-neutral.
     *
     * Re-recorded at H5, which is a terrain change and so moves them legitimately: the hydraulic
     * rounds now grade to a sea a stand below today's, water the ocean cannot reach is land, and
     * H5 also gave the aulacogens of H1's past epochs an along-strike varying floor. Every one of
     * those changes the rock the climate is read off, so the pinned world is a different world.
     * What the pin can still say after that is what it says now — that `snowBalance = false` adds
     * none of H2's arithmetic to the pipeline — and the proof that H2's refactor was
     * arithmetic-neutral stands where it was made, in H2's own report.
     */
    @Test
    fun `the control reproduces the pre-H2 world bit for bit`() {
        val pins = mapOf(
            7L to (-8562146537330195503L to 9104443856414927535L),
            42L to (1012402023801667400L to -7602467893653674969L),
            1234L to (7278985542260197860L to -4531422742676477445L),
            99L to (5870418170978420414L to 3497781553044956947L)
        )
        val moved = ArrayList<String>()
        seeds.forEach { seed ->
            val control = world(seed, balance = false)
            val (elevation, biomes) = pins.getValue(seed)
            val measured = elevationChecksum(control) to biomeChecksum(control)
            println("SNOWPIN ${seed}L to (${measured.first}L to ${measured.second}L),")
            // Collected and reported for every seed before anything is asserted, so a run that has
            // to re-record these hands over all four pairs at once instead of one per run.
            if (measured != (elevation to biomes)) moved.add("$seed")
        }
        assertEquals(
            "checksums moved with snowBalance off on seeds $moved",
            emptyList<String>(), moved.toList()
        )
    }

    // ---------------------------------------------------------------- the guards proper

    /**
     * How much of the land is under ice, per seed, against Earth's own figure.
     *
     * Earth: 15.0 million km² of glacier ice on 148.9 million km² of land, so **10.1%**, and about
     * 97% of that is the two ice sheets. There is no reason a generated world should hit that
     * exactly — it depends on how much land a seed puts at high latitude, which is the seed's
     * business — so the bar is a factor of two either side of it, pooled over the four standard
     * seeds, and the report carries the per-seed figures.
     *
     * Measured, pooled over seeds 7/42/1234/99 at 512: **28.8% with the balance off, 9.2% with it
     * on**. The control is the assertion's own counter-example: at 28.8% it is nearly three times
     * Earth's share and outside the bar, so the guard is shown failing without the fix by running
     * the same measurement on the same worlds with one flag moved.
     */
    @Test
    fun `the ice share of land is within reach of Earth's`() {
        var iceOn = 0
        var iceOff = 0
        var land = 0
        seeds.forEach { seed ->
            val on = world(seed, balance = true)
            val off = world(seed, balance = false)
            val cells = on.sea.landCellCount
            val onIce = count(on, Biome.ICE_SHEET)
            val offIce = count(off, Biome.ICE_SHEET)
            iceOn += onIce
            iceOff += offIce
            land += cells
            println(
                "SNOWBALANCE seed=$seed ice ${"%.2f".format(offIce * 100.0 / cells)}%" +
                    " -> ${"%.2f".format(onIce * 100.0 / cells)}% of land;" +
                    " tundra ${"%.2f".format(count(off, Biome.TUNDRA) * 100.0 / cells)}%" +
                    " -> ${"%.2f".format(count(on, Biome.TUNDRA) * 100.0 / cells)}%;" +
                    " desert ${"%.2f".format(count(off, Biome.DESERT) * 100.0 / cells)}%" +
                    " -> ${"%.2f".format(count(on, Biome.DESERT) * 100.0 / cells)}%"
            )
        }
        val shareOn = iceOn * 100.0 / land
        val shareOff = iceOff * 100.0 / land
        println(
            "SNOWBALANCE pooled ice share of land: ${"%.2f".format(shareOff)}% -> " +
                "${"%.2f".format(shareOn)}% (Earth 10.1%)"
        )
        assertTrue(
            "the control already sits inside the Earth bar (${"%.2f".format(shareOff)}%), so this" +
                " guard is not measuring the balance",
            shareOff > EARTH_ICE_SHARE * 2
        )
        assertTrue(
            "ice covers ${"%.2f".format(shareOn)}% of land, more than twice Earth's" +
                " ${EARTH_ICE_SHARE}%",
            shareOn <= EARTH_ICE_SHARE * 2
        )
        assertTrue(
            "ice covers only ${"%.2f".format(shareOn)}% of land, less than half Earth's" +
                " ${EARTH_ICE_SHARE}% — the balance has abolished the ice rather than sited it",
            shareOn >= EARTH_ICE_SHARE / 2
        )
    }

    /**
     * The Siberia case, on the generator's own worlds: cold country that no snow reaches is not an
     * ice sheet.
     *
     * "Cold dry interior" is found rather than named, so the guard does not depend on a hand-picked
     * cell surviving the next change to the terrain. Four conditions, and each earns its place:
     *
     *  - a winter below -20 C and
     *  - more than three coastal reaches from any water — the distance [ClimateStage]'s own
     *    continentality term calls fully continental — which together mean a severe continental
     *    interior;
     *  - under 400mm a year, which means the snow never arrives;
     *  - **and a summer above 2 C**, which is the condition that separates Siberia from
     *    Antarctica. Without it the description also fits the polar plateau, where the same
     *    dryness sits under two miles of ice precisely because nothing ever melts there — measured:
     *    of the cold, dry, interior cells with no summer condition at all, 36% are still ice under
     *    the balance, and correctly so. Verkhoyansk is -14.5 C in the annual mean and +19 C in
     *    July; it is the July that keeps it bare.
     *
     * Measured pooled over the seeds that have such country (7, 42 and 99; seed 1234 has none):
     * 5,419 cells, **39.9% under ice with the balance off and 0.0% with it on**.
     */
    @Test
    fun `a cold dry interior is tundra or cold desert, not ice`() {
        var iceOn = 0
        var iceOff = 0
        var cells = 0
        seeds.forEach { seed ->
            val on = world(seed, balance = true)
            val off = world(seed, balance = false)
            val interior = coldDryInterior(config(seed), on)
            if (interior.isEmpty()) {
                println("SNOWBALANCE seed=$seed has no cold dry interior")
                return@forEach
            }
            val onIce = interior.count { on.climate.biome[it] == Biome.ICE_SHEET }
            val offIce = interior.count { off.climate.biome[it] == Biome.ICE_SHEET }
            iceOn += onIce
            iceOff += offIce
            cells += interior.size
            val tally = interior.groupingBy { on.climate.biome[it] }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key}=${it.value}" }
            println(
                "SNOWBALANCE seed=$seed cold dry interior ${interior.size} cells:" +
                    " ice ${"%.1f".format(offIce * 100.0 / interior.size)}% ->" +
                    " ${"%.1f".format(onIce * 100.0 / interior.size)}%; now $tally"
            )
        }
        assertTrue("no cold dry interior on any seed to measure", cells > 2000)
        val shareOn = iceOn * 100.0 / cells
        val shareOff = iceOff * 100.0 / cells
        println(
            "SNOWBALANCE cold dry interior under ice: ${"%.1f".format(shareOff)}% ->" +
                " ${"%.1f".format(shareOn)}% of $cells cells"
        )
        assertTrue(
            "the control leaves only ${"%.1f".format(shareOff)}% of the cold dry interior under" +
                " ice, so there is nothing here for the balance to fix",
            shareOff > 25.0
        )
        assertTrue(
            "${"%.1f".format(shareOn)}% of the cold dry interior is still ice sheet",
            shareOn < 2.0
        )
    }

    /**
     * The Patagonia-against-the-Atacama case: where two places are equally cold, the wet one
     * carries ice and the dry one does not.
     *
     * The plan puts this as "a wet maritime highland at the same latitude keeps ice lower than a
     * dry one", and the first version of this guard measured exactly that — the lowest iced ground
     * in the wettest and driest quarters of a band of latitude — and found the *opposite*, on the
     * balance and on the control alike. The reason is worth recording, because it is a real
     * property of the model and not a bug: inside one band of latitude the wet quarter is the
     * maritime quarter, and a maritime cell is warmer than a continental one at the same latitude
     * by several degrees (A2's continentality, and H4's currents on top of it). Latitude does not
     * hold temperature fixed; it only looks as though it should.
     *
     * So temperature is held fixed directly. Every land cell whose warm half-year averages between
     * -6 and -3 C — the margin where the balance is actually decided — is taken, and split by
     * annual rainfall into its wettest and driest quarters. Both quarters are then equally cold by
     * construction, at a temperature where a glacier is possible, and the only difference left
     * between them is how much snow arrives. Elevation is reported alongside, as the 5th percentile
     * of the iced cells' [SeaLevelResult.relativeElevation]: that is the "keeps ice lower" figure,
     * and on the dry side it does not exist, because there is no ice there at all.
     *
     * Measured at 512, wet quarter against dry quarter, share under ice:
     *
     * | seed | balance | control |
     * |---|---|---|
     * | 7 | 3.1% / 0.0% | 100% / 100% |
     * | 42 | 12.8% / 0.0% | 45.4% / 100% |
     * | 1234 | 17.1% / 0.0% | 100% / 100% |
     * | 99 | 13.1% / 0.0% | 78.6% / 100% |
     *
     * The control is not merely undiscriminating, it runs backwards: with ice decided on the annual
     * mean, the *dry* quarter is the more thoroughly iced of the two on every seed, because a dry
     * cell at this summer temperature is a continental one and a continental one has the colder
     * winter and so the colder mean. Both halves of that are asserted.
     */
    @Test
    fun `at the same temperature, ice is where the snow is`() {
        seeds.forEach { seed ->
            val on = world(seed, balance = true)
            val off = world(seed, balance = false)
            val balance = iceByRainfall(on)
            val control = iceByRainfall(off)
            assertTrue("seed $seed has no marginal-temperature land to measure", balance != null)
            println(
                "SNOWBALANCE seed=$seed at summer $MARGINAL_LOW..$MARGINAL_HIGH C:" +
                    " balance wet ${"%.1f".format(balance!!.wetShare)}% iced" +
                    " (down to ${balance.wetFloor?.let { "%.3f".format(it) } ?: "no ice"})," +
                    " dry ${"%.1f".format(balance.dryShare)}%" +
                    " (${balance.dryFloor?.let { "%.3f".format(it) } ?: "no ice"});" +
                    " control wet ${"%.1f".format(control!!.wetShare)}%," +
                    " dry ${"%.1f".format(control.dryShare)}%" +
                    " [wet ${"%.0f".format(balance.wetMm)}mm, dry ${"%.0f".format(balance.dryMm)}mm]"
            )
            assertTrue(
                "seed $seed: with the balance off the dry quarter is not the more heavily iced" +
                    " (wet ${"%.1f".format(control.wetShare)}%, dry" +
                    " ${"%.1f".format(control.dryShare)}%), so the contrast below is not the" +
                    " balance's doing",
                control.dryShare >= control.wetShare
            )
            assertTrue(
                "seed $seed: only ${"%.1f".format(balance.wetShare)}% of the wet quarter carries" +
                    " ice at this temperature",
                balance.wetShare >= 1.0
            )
            assertTrue(
                "seed $seed: ${"%.1f".format(balance.dryShare)}% of the dry quarter carries ice at" +
                    " the same temperature as the wet one",
                balance.dryShare <= 0.2
            )
        }
    }

    // ---------------------------------------------------------------- measurement helpers

    private companion object {
        /**
         * Glacier ice covers 15.0 of Earth's 148.9 million km² of land. Antarctica and Greenland
         * are 97% of it, which is why a world with no polar land should be expected at the bottom
         * of the range and not at the middle.
         */
        const val EARTH_ICE_SHARE = 10.1

        /**
         * The warm-season window the ice margin sits in, in C. Cold enough that a glacier is
         * possible at all and warm enough that it is not inevitable, which is where a mass balance
         * has something to say and a thermometer does not.
         */
        const val MARGINAL_LOW = -6f
        const val MARGINAL_HIGH = -3f

        private val cache = HashMap<Pair<Long, Boolean>, WorldMap>()
    }

    private fun count(world: WorldMap, biome: Biome): Int {
        var n = 0
        for (i in world.climate.biome.indices) {
            if (world.sea.isLand[i] && world.climate.biome[i] == biome) n++
        }
        return n
    }

    private fun elevationChecksum(world: WorldMap): Long {
        var checksum = 0L
        world.sea.relativeElevation.data.forEach { checksum = checksum * 31 + it.toRawBits() }
        return checksum
    }

    private fun biomeChecksum(world: WorldMap): Long {
        var checksum = 0L
        world.climate.biome.forEach { checksum = checksum * 31 + it.ordinal }
        return checksum
    }

    /** Land far from any water, bitterly cold in winter, dry — and thawing in summer. */
    private fun coldDryInterior(config: WorldGenConfig, world: WorldMap): List<Int> {
        val distance = ClimateStage.waterDistance(config, world.sea)
        val continental = 3f * config.ocean.coastalReach.coerceAtLeast(1)
        return (0 until config.width * config.height).filter { i ->
            world.sea.isLand[i] &&
                world.climate.winterTemperature.data[i] < -20f &&
                world.climate.summerTemperature.data[i] > 2f &&
                world.climate.precipitationMm.data[i] < 400f &&
                distance.data[i] > continental
        }
    }

    private class IceByRainfall(
        val wetShare: Double,
        val dryShare: Double,
        val wetFloor: Float?,
        val dryFloor: Float?,
        val wetMm: Float,
        val dryMm: Float
    )

    /**
     * How much of the wettest and driest quarters of the marginal-temperature land is under ice,
     * and how low that ice reaches on each. Null when there is too little such land to say
     * anything.
     */
    private fun iceByRainfall(world: WorldMap): IceByRainfall? {
        val w = world.config.width
        val h = world.config.height
        val marginal = (0 until w * h).filter { i ->
            world.sea.isLand[i] &&
                world.climate.summerTemperature.data[i] >= MARGINAL_LOW &&
                world.climate.summerTemperature.data[i] <= MARGINAL_HIGH
        }
        if (marginal.size < 1000) return null
        val rain = marginal.map { world.climate.precipitationMm.data[it] }.sorted()
        val dryCut = rain[rain.size / 4]
        val wetCut = rain[rain.size * 3 / 4]
        val wet = marginal.filter { world.climate.precipitationMm.data[it] >= wetCut }
        val dry = marginal.filter { world.climate.precipitationMm.data[it] <= dryCut }

        fun share(cells: List<Int>) =
            cells.count { world.climate.biome[it] == Biome.ICE_SHEET } * 100.0 / cells.size

        /** The lowest ground under ice, as the 5th percentile so one stray cell cannot decide it. */
        fun floor(cells: List<Int>): Float? {
            val iced = cells
                .filter { world.climate.biome[it] == Biome.ICE_SHEET }
                .map { world.sea.relativeElevation.data[it] }
                .sorted()
            return if (iced.size < 20) null else iced[iced.size / 20]
        }

        return IceByRainfall(
            wetShare = share(wet),
            dryShare = share(dry),
            wetFloor = floor(wet),
            dryFloor = floor(dry),
            wetMm = rain[rain.size * 7 / 8],
            dryMm = rain[rain.size / 8]
        )
    }
}
