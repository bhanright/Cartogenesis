package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.HydraulicErosion
import com.cartogenesis.worldgen.pipeline.IncisionWatch
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlocking
import com.cartogenesis.worldgen.pipeline.erodeBlockingObservingCover
import com.cartogenesis.worldgen.pipeline.thermalSweepBlocking
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the rain does to the rock, and what the cover on the rock does back.
 *
 * Three claims, each shown failing with `ErosionConfig.climateFeed` off — which is not a weaker
 * version of the stage but the stage as it was, flat rain and bare ground, so a control figure
 * here is the old world's figure and nothing has been arranged to make the new one look good.
 *
 * The claims are the stream-power law's own, not this code's. `E = K A^m S^n` with discharge in
 * place of area says a flank taking n times its neighbour's rain cuts `sqrt(n)` times as fast at
 * the same catchment and gradient, and Earth agrees at the extreme: the Southern Alps of New
 * Zealand take about ten times as much rain on their western flank as on their eastern and exhume
 * at 5-10 mm a year against the east's under one (Willett, *Orogeny and orography*, JGR 104, 1999;
 * Hovius, Stark and Allen, *Sediment flux from a mountain belt derived by landslide mapping*,
 * Geology 25, 1997). A wet flank is also a more finely divided one, because a channel needs less
 * ground behind it to start where more water falls on that ground.
 */
class ClimateFedErosionTest {

    /**
     * A mountain belt and which of its cells the wind climbs.
     *
     * Written here because nothing in the suite classified a flank: `MeridionalWindTest` asks the
     * same question of a whole map row by row, and `GroundTextureTest` picks belt cells without
     * asking which way they face, so this is the two put together. The belt is the largest
     * connected run of cells near an Andean or collisional boundary standing above
     * [BELT_FLOOR_METRES]; a cell of it is windward when the ground rises from the cell the wind
     * reaches first to this one, and leeward when it falls. Cells on neither — along the crest,
     * and wherever the rise is too slight to call — are left out of both.
     */
    private class Belt(val windward: BooleanArray, val leeward: BooleanArray) {
        val windwardCells = windward.count { it }
        val leewardCells = leeward.count { it }
    }

    /** Everything one seed's measurement needs, generated once and asked several questions. */
    private class Ground(val config: WorldGenConfig) {
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val weathered = thermalSweepBlocking(config, plates.height, skipSettled = true).height
        val cut = SeaLevelStage.percentileCut(weathered, config.seaLevel, config.scale)

        /** The march the rounds themselves run, re-taken here so the guard reads the same rain. */
        val climate =
            ClimateStage.generateWithSeasonalMm(
                config, cut, OceanStage.withoutCurrents(config, cut)
            ).result

        private val weather = HydraulicErosion.provisionalWeather(config, weathered, config.seaLevel)

        /**
         * The weights the first round routes with: the march's rainfall over its own mean across
         * the land that round sees, which is what `normaliseOverLand` does inside the stage.
         */
        val runoff = FloatArray(weather.rainfallMm.size).also { weights ->
            var summed = 0.0
            for (cell in weights.indices) if (cut.isLand[cell]) summed += weather.rainfallMm[cell]
            val mean = (summed / cut.landCellCount).toFloat()
            for (cell in weights.indices) weights[cell] = weather.rainfallMm[cell] / mean
        }

        val flat = FloatArray(runoff.size) { 1f }

        /** The weights a round over [terrain] would route with: rainfall over its own land mean. */
        fun weightsOver(terrain: FloatField): FloatArray {
            val over = SeaLevelStage.percentileCut(terrain, config.seaLevel, config.scale)
            val rainfall =
                HydraulicErosion.provisionalWeather(config, terrain, config.seaLevel).rainfallMm
            var summed = 0.0
            for (cell in rainfall.indices) if (over.isLand[cell]) summed += rainfall[cell].toDouble()
            val mean = (summed / over.landCellCount).toFloat()
            return FloatArray(rainfall.size) { rainfall[it] / mean }
        }

        // ---------------------------------------------------------------------------------
        // What the production stage actually does, observed rather than reconstructed.
        //
        // The guards below used to rebuild the stream-power expression here and compare it with
        // the multiplier they had just put into it, which is a test of the test: delete the
        // shielding from `cut` outright and it would still have gone green. So the stage is run,
        // and what is asserted is the difference between two height fields it produced.
        //
        // One round, on ground both runs share. Everything else that could move a cell is off:
        // no deposition, so nothing is laid back down; no thermal budget, so neither the opening
        // sweeps nor the relaxation between rounds touches the field and the terrain the round is
        // handed is the raw uplift; no outlet notch; no delta lobe, so the closing round opens no
        // mouths and grooves no flats; and no uplift rate, passed as null where the run is made,
        // because a rising cell would put the tectonics in the difference. What is left in the
        // height field is that round's incision and nothing else.
        // ---------------------------------------------------------------------------------

        /** The terrain the one-round runs are handed, the thermal budget being switched off. */
        val bare = plates.height

        /**
         * The very config those runs use, and every reference field below is read through it.
         *
         * Not the twelve-round one, and the difference is not cosmetic: `standBelowToday` puts the
         * sea below today's shoreline for the early rounds of a twelve-round world and leaves it
         * at today's for a one-round world, because the transgression is a schedule over the round
         * count. Reading the march through the twelve-round config gave a different sea, so a
         * different coastline, a different rainfall and a different land mean to normalise over -
         * and the guard was comparing what the stage did against a factor built for another world.
         */
        val once = config.copy(
            erosion = config.erosion.copy(
                hydraulicRounds = 1,
                deposition = false,
                // Both halves of the thermal pass, because the relaxation between rounds takes
                // `sweepsFor / rounds` coerced up to at least one sweep, so a zero travel distance
                // does not switch it off - and one sweep moves material between cells after the
                // cut, which is not incision and would land in the height difference read back.
                debrisTravelKm = 0.0,
                rate = 0f,
                outletIncision = false,
                deltaLobe = false
            )
        )

        val bareCut = SeaLevelStage.percentileCut(bare, once.seaLevel, once.scale)
        private val bareWeather = HydraulicErosion.provisionalWeather(once, bare, once.seaLevel)

        /**
         * The march over that same terrain, for the wind that tells a windward flank from a
         * leeward one and the rainfall the law's bar is read from.
         *
         * The belt has to be found on the terrain the incision is observed on, not on the
         * thermally weathered one: the flanks are picked by the sign of a rise, and the sweeps
         * move exactly the rises that a flank is made of.
         */
        val bareClimate = ClimateStage.generateWithSeasonalMm(
            once, bareCut, OceanStage.withoutCurrents(once, bareCut)
        ).result

        /** The cover that round shields with, and the factor it turns into. Relative, mean 1. */
        val bareDensity = bareWeather.vegetationDensity
        val bareErodibility = FloatArray(bareDensity.size).also { factors ->
            var summed = 0.0
            for (cell in factors.indices) {
                if (bareCut.isLand[cell]) summed += (1.0 - 0.5 * bareDensity[cell])
            }
            val mean = (summed / bareCut.landCellCount).toFloat()
            for (cell in factors.indices) factors[cell] = (1f - 0.5f * bareDensity[cell]) / mean
        }
        val bareRunoff = FloatArray(bareDensity.size).also { weights ->
            var summed = 0.0
            for (cell in weights.indices) {
                if (bareCut.isLand[cell]) summed += bareWeather.rainfallMm[cell].toDouble()
            }
            val mean = (summed / bareCut.landCellCount).toFloat()
            for (cell in weights.indices) weights[cell] = bareWeather.rainfallMm[cell] / mean
        }

        private val observed = HashMap<Pair<Boolean, Boolean>, Observed>()

        /**
         * What the real stage's one round did to each cell under the two switches, read through
         * its watch: the law's rate, `F` times the drop to the receiver as the pass found it, in
         * metres; `F` itself; and the share of the drop to the receiver's new height the cell
         * lost, NaN where the pass left the cell alone.
         *
         * The law's rate and not the realised cut is what carries the rain and the cover in
         * proportion. The implicit update realises `F / (1 + F)` of the drop, so a factor on `F`
         * reaches the cut in full only where `F` is small and less and less as it grows; the
         * realised cut responds to an erodibility factor `e` as `e (1 + F) / (1 + e F)`.
         */
        fun observed(climateFeed: Boolean, shieldCut: Boolean): Observed =
            observed.getOrPut(climateFeed to shieldCut) {
                val cells = bare.data.size
                val spanMetres = once.scale.reliefSpanMetres
                val result = Observed(FloatArray(cells), FloatArray(cells), FloatArray(cells) { Float.NaN })
                val watch = object : IncisionWatch {
                    override fun cut(
                        round: Int, cell: Int, receiver: Int, courantNumber: Float, before: Float,
                        baseBefore: Float, baseAfter: Float, after: Float
                    ) {
                        result.courant[cell] = courantNumber
                        if (before > baseBefore) result.lawMetres[cell] = courantNumber * (before - baseBefore) * spanMetres
                        if (before > baseAfter) result.shareOfDrop[cell] = (before - after) / (before - baseAfter)
                    }

                    override fun incised(
                        round: Int, isLand: BooleanArray, directions: IntArray, ground: FloatArray,
                        relative: FloatArray, discharge: FloatArray, landCells: Float, landRange: Float,
                        shorelineHeight: Float, surface: FloatArray
                    ) = Unit
                }
                erodeBlockingObservingCover(
                    once.copy(erosion = once.erosion.copy(climateFeed = climateFeed)),
                    bare, upliftRateMmPerYear = null, shieldCut = shieldCut, incisionWatch = watch
                )
                result
            }

        private val carved = HashMap<Boolean, FloatField>()

        /** Memoised: four guards ask the same two worlds, and each is twelve hydraulic rounds. */
        fun eroded(climateFeed: Boolean): FloatField = carved.getOrPut(climateFeed) {
            erodeBlocking(
                config.copy(erosion = config.erosion.copy(climateFeed = climateFeed)),
                plates.height,
                upliftRateMmPerYear = plates.upliftRateMmPerYear
            ).height
        }

        /** How far each cell fell over the whole run, in metres of the world's own ruler. */
        fun lowering(after: FloatField): FloatArray {
            val metresPerUnit = config.scale.reliefSpanMetres
            return FloatArray(after.data.size) {
                (weathered.data[it] - after.data[it]) * metresPerUnit
            }
        }
    }

    /**
     * Windward incision against leeward, against what the stream-power law says the rain alone
     * should buy.
     *
     * The bar is `sqrt(P_windward / P_leeward) * 0.8` — the law's own prediction at m = 0.5, with a
     * fifth off it because the two flanks are not equal in area, do not carry equal catchments and
     * do not stand at equal gradients, and none of that is the rain's doing.
     *
     * Read on the law's rate, and on the rain alone: the fed run with the cover's factor taken out,
     * against the control with neither. Turning the feed on also turns on the cover, and the wet
     * flank is the better wooded, so the fed run's figure carries the cover's factor as well as the
     * rain and the bar above is the rain's only; the cover's own factor is held exactly by
     * `cover on the ground holds the incision back`. The fed figure, cover and all, is printed.
     * Until Fix 3b this read the realised first-round cut with the cover in, and seed 1234 ran as a
     * known failure, 1.12 under 1.49 under the capped explicit update; on the law's rate with the
     * cover it reads 1.42, and with the rain alone 1.67 (docs/DESIGN_LEDGER.md, Fix 3b).
     */
    @Test
    fun `the wet flank of a range is cut harder than the dry one`() {
        val measurements = SEEDS.mapNotNull { seed ->
            val ground = ground(seed)
            val belt = beltFlanks(ground) ?: return@mapNotNull null
            if (belt.windwardCells < MIN_FLANK_CELLS || belt.leewardCells < MIN_FLANK_CELLS) {
                return@mapNotNull null
            }
            val rainfall = ground.bareClimate.precipitationMm.data
            val windwardRain = meanOver(rainfall, belt.windward)
            val leewardRain = meanOver(rainfall, belt.leeward)

            // The forcing, read off what the stage actually asked. One round each way on the same
            // terrain, so the receivers and the gradients are shared and the only thing that
            // differs is whether the water and the cover are the world's own or flat and absent.
            // The law's rate and not the realised cut, which the implicit update saturates as
            // `F / (1 + F)`; see [Ground.observed].
            val fed = ground.observed(climateFeed = true, shieldCut = true).lawMetres
            val flat = ground.observed(climateFeed = false, shieldCut = true).lawMetres
            // The rain alone, the cover's factor taken out of the fed run: see the KDoc.
            val rainOnly = ground.observed(climateFeed = true, shieldCut = false).lawMetres
            val figures = Flank(
                seed = seed,
                rainRatio = windwardRain / leewardRain,
                fedRatio = meanOver(rainOnly, belt.windward) / meanOver(rainOnly, belt.leeward),
                flatRatio = meanOver(flat, belt.windward) / meanOver(flat, belt.leeward),
                windwardCells = belt.windwardCells,
                leewardCells = belt.leewardCells
            )
            println(
                ("S3 FLANK seed=%d  rain %.0f/%.0f mm = %.2fx; first-round law's rate, rain alone, windward " +
                    "over leeward fed %.3fx, flat %.3fx, so the feed multiplies it by %.2fx; " +
                    "law asks %.2f; %d windward and %d leeward cells").format(
                    seed, windwardRain, leewardRain, figures.rainRatio, figures.fedRatio,
                    figures.flatRatio, figures.forcing, figures.bar,
                    figures.windwardCells, figures.leewardCells
                ) + "; with the cover's factor in as well, %.2fx".format(
                    (meanOver(fed, belt.windward) / meanOver(fed, belt.leeward)) / figures.flatRatio
                )
            )
            figures
        }
        assertTrue(
            measurements.size >= 2,
            "only ${measurements.size} of ${SEEDS.size} seeds offered a belt to measure"
        )
        val short = measurements.filter { it.forcing < it.bar }
        assertTrue(
            short.isEmpty(),
            "the windward flank takes more of the rain and turning the rain on multiplies its share of the law's " +
                "rate by less than the stream-power law asks for: " +
                short.joinToString { String.format(Locale.ROOT, "seed %d: %.2f under %.2f", it.seed, it.forcing, it.bar) }
        )
    }

    /**
     * One belt's figures, kept so every seed is printed before any of them is judged.
     *
     * The control's [flatRatio] is not expected anywhere near 1 and is not asserted to be: a
     * belt's two flanks are not mirror images — the windward one is the one the ocean is on, so it
     * is shorter, steeper and younger — and flat rain over an asymmetric range cuts its two sides
     * by different amounts for reasons that have nothing to do with rain. What the guard is about
     * is the *ratio of the ratios*, which is what the feed itself did.
     */
    private class Flank(
        val seed: Long,
        val rainRatio: Double,
        val fedRatio: Double,
        val flatRatio: Double,
        val windwardCells: Int,
        val leewardCells: Int
    ) {
        /** What turning the feed on multiplied the windward-over-leeward incision ratio by. */
        val forcing = fedRatio / flatRatio

        /** `sqrt(P_windward / P_leeward)` at m = 0.5, less a fifth. See the class KDoc. */
        val bar = sqrt(rainRatio) * STREAM_POWER_SLACK
    }

    /**
     * What twelve rounds of it leave on the ground, printed beside Earth's figures.
     *
     * Findings and not clauses. After twelve rounds the two terrains have diverged, so a
     * difference between them is the whole coupled history — the rain, the cover, the sea moving
     * up the valleys, the spoil — and not the law, and there is no control that isolates one term
     * of it. What the law is asked about is the first round, in the guard above.
     */
    @Test
    fun `report what the rain leaves on a range after twelve rounds`() {
        var checked = 0
        for (seed in SEEDS) {
            val ground = ground(seed)
            val belt = beltFlanks(ground) ?: continue
            if (belt.windwardCells < MIN_FLANK_CELLS || belt.leewardCells < MIN_FLANK_CELLS) continue
            checked++

            val fedTerrain = ground.eroded(true)
            val flatTerrain = ground.eroded(false)
            val fedLowering = ground.lowering(fedTerrain)
            val flatLowering = ground.lowering(flatTerrain)
            val fedChannel = channelShare(ground.config, fedTerrain, belt, ground.flat)
            val flatChannel = channelShare(ground.config, flatTerrain, belt, ground.flat)
            println(
                ("S3 FLANK AFTER TWELVE seed=%d  relief lost windward over leeward fed %.2fx, " +
                    "flat %.2fx; routed drainage density (flat support) fed %.3f/%.3f = %.2fx, " +
                    "flat %.3f/%.3f = %.2fx").format(
                    seed,
                    meanOver(fedLowering, belt.windward) / meanOver(fedLowering, belt.leeward),
                    meanOver(flatLowering, belt.windward) / meanOver(flatLowering, belt.leeward),
                    fedChannel.first, fedChannel.second, fedChannel.first / fedChannel.second,
                    flatChannel.first, flatChannel.second, flatChannel.first / flatChannel.second
                )
            )

            // And how far the weights themselves moved on the two flanks over the same twelve
            // rounds, which is the refresh question asked where the chunk's claim lives rather
            // than over the whole land. See `ClimateFedErosionMeasurementTest`.
            val opening = ground.runoff
            val closing = ground.weightsOver(fedTerrain)
            println(
                ("S3 FLANK DRIFT seed=%d  the weight's mean went %.3f -> %.3f windward and " +
                    "%.3f -> %.3f leeward, so the flanks' ratio went %.2f -> %.2f").format(
                    seed,
                    meanOver(opening, belt.windward), meanOver(closing, belt.windward),
                    meanOver(opening, belt.leeward), meanOver(closing, belt.leeward),
                    meanOver(opening, belt.windward) / meanOver(opening, belt.leeward),
                    meanOver(closing, belt.windward) / meanOver(closing, belt.leeward)
                )
            )
        }
        assertTrue(checked >= 2, "only $checked of ${SEEDS.size} seeds offered a belt to measure")
        // Earth's own figure for the same comparison, so a reader has the scale of it: the western
        // flank of the Southern Alps takes about ten times the eastern's rain and exhumes at 5-10
        // mm a year against the east's under one (Willett, *Orogeny and orography*, JGR 104, 1999;
        // Hovius, Stark and Allen, *Sediment flux from a mountain belt derived by landslide
        // mapping*, Geology 25, 1997). An order of magnitude, where these are tens of per cent.
        println("S3 FLANK AFTER TWELVE Earth: the Southern Alps, 10x the rain and 5-10x the exhumation")
    }

    /**
     * Where it rains, the land is cut. Over the whole of the land and not one range: a rank
     * correlation between a cell's rainfall and how far it fell.
     *
     * Rank rather than linear because neither quantity is normally distributed and the claim is
     * about order, not about a slope.
     */
    @Test
    fun `dissection follows the rainfall`() {
        val underThePin = ArrayList<Pair<Long, Double>>()
        val uncontrolled = ArrayList<Pair<Long, Double>>()
        // Every seed measured before any is judged, so one run prints all four.
        val complaints = ArrayList<String>()
        for (seed in SEEDS) {
            val ground = ground(seed)
            val land = ground.cut.isLand
            val rainfall = ground.climate.precipitationMm.data
            val fed = spearman(rainfall, ground.lowering(ground.eroded(true)), land)
            val flat = spearman(rainfall, ground.lowering(ground.eroded(false)), land)
            println("S3 DISSECTION seed=%d  fed %+.3f, flat %+.3f".format(seed, fed, flat))
            if (fed < DISSECTION_CORRELATION) underThePin += seed to fed
            // Against a control that correlates at all: a negative control would make the bar
            // below pass on any positive figure, so a seed whose control does not correlate is
            // not judged by it, and is recorded with the pin below.
            if (flat <= 0.0) {
                uncontrolled += seed to flat
                continue
            }
            if (fed < flat * DISSECTION_OVER_CONTROL) {
                complaints += "seed $seed: flat rain already correlates at ${"%.3f".format(flat)} against the " +
                    "fed ${"%.3f".format(fed)}, so the fed figure says little about the rain"
            }
        }
        // Armed at Fix 3b: under the capped explicit update seeds 42 and 1234 fell short of it.
        assertTrue(complaints.isEmpty(), complaints.joinToString("; "))
        // The pin was taken on rounds run without the tectonic uplift, which no world is made by;
        // on the uplift path, which is the path this measures since Audit III (its B-I2), a seed
        // can read under it. Not re-set to fit: kept running as a known failure until the pin is
        // re-derived on the path the map is made by. On the ground's ruler that seed is 7, whose
        // wettest ground is the ground the belts are rising under: what a cell lost is its erosion
        // less its uplift, the uplift is heaviest where the rain is, and its flat-rain control
        // ranks against the rain, so neither figure says what the rain cut (docs/DESIGN_LEDGER.md,
        // Fix 2). Taking the erosion itself, the uplift added back, is the re-derivation B-I2 asks.
        KnownFailures.expect(
            "B-I2: the rain-dissection pin was set on rounds without the uplift",
            "seed 7 at 0.135; seed 7's flat-rain control at -0.050"
        ) {
            if (underThePin.isNotEmpty() || uncontrolled.isNotEmpty()) {
                val found = underThePin.joinToString { (seed, fed) -> String.format(Locale.ROOT, "seed %d at %.3f", seed, fed) } +
                    "; " + uncontrolled.joinToString { (seed, flat) ->
                        String.format(Locale.ROOT, "seed %d's flat-rain control at %.3f", seed, flat)
                    }
                throw RecordedViolation(
                    "rainfall and incision rank together at only $found, under the pin of $DISSECTION_CORRELATION " +
                        "or against a control that does not correlate at all",
                    found
                )
            }
        }
    }

    /**
     * The cover multiplies the law's rate by exactly what the constant says, cell by cell; and
     * where `F` is small the realised cut follows it too, departing only by what the implicit
     * update's own arithmetic says.
     *
     * The multiplier is relative: `1 - 0.5 * density` over its own mean across the land, so the
     * land's mean erodibility is exactly 1 and the term redistributes the cutting rather than
     * reducing it. `bedrockErodibilityPerYear` was calibrated on real bedrock rivers, which ran
     * through forests, so an absolute multiplier counts the cover twice; the factor this checks the
     * stage against is normalised over the land, so a stage that spent an unnormalised one would
     * miss it on every cell.
     *
     * Asserted per cell and not as a ratio between two bands, because a band ratio cannot test it:
     * `1 - 0.5 * density` over cells at density >= 0.6 against cells at density <= 0.1 can land
     * anywhere from about 0.50 to 0.74 depending on how the density is distributed inside each band.
     *
     * **The law's rate.** One production round with the cover and one without, on the same terrain
     * and the same receivers, read through the watch: `F` times the drop as the pass found it. The
     * drop is the same in both runs, so the quotient is the factor to the last few bits of a float,
     * on every cell.
     *
     * **The realised cut, where `F` is small.** Under the implicit update a cell loses `F / (1 + F)`
     * of its drop to its receiver's new height, so a factor `e` on `F` moves that share by
     * `e (1 + F) / (1 + e F)`, not by `e`. Read as that share, so the receiver's own cut in each run
     * divides out, the quotient must be exactly this on cells under [SMALL_COURANT], where it is
     * within `e |1 - e| F` of the factor, the proportional law the clause used to assert.
     */
    @Test
    fun `cover on the ground holds the incision back`() {
        for (seed in SEEDS) {
            val ground = ground(seed)
            // Both from the stage, on the same terrain, with one switch between them: the second
            // run is the production cut with its shielding taken out. Nothing here recomputes the
            // incision, so a shielding term deleted from the pass fails this rather than passing it.
            val shielded = ground.observed(climateFeed = true, shieldCut = true)
            val unshielded = ground.observed(climateFeed = true, shieldCut = false)
            val density = ground.bareDensity
            val factor = ground.bareErodibility

            var checked = 0
            var worst = 0.0
            var worstCell = -1
            var control = 0.0
            var outliers = 0
            for (cell in factor.indices) {
                // A floor on the rate itself: a metre leaves three decimal places of signal in a
                // quotient of two floats read off a height field of millimetre resolution.
                if (unshielded.lawMetres[cell] < OBSERVED_FLOOR_METRES) continue
                checked++
                val measured = shielded.lawMetres[cell].toDouble() / unshielded.lawMetres[cell].toDouble()
                val off = abs(measured - factor[cell])
                if (off > OBSERVED_TOLERANCE) outliers++
                if (off > worst) {
                    worst = off
                    worstCell = cell
                }
                // What the same measurement reads for the control run against itself, which is
                // exactly 1 and is what a stage with no shielding in it would give.
                val flat = abs(1.0 - factor[cell])
                if (flat > control) control = flat
            }

            var smallChecked = 0
            var smallWorst = 0.0
            var smallFromFactor = 0.0
            for (cell in factor.indices) {
                val courant = unshielded.courant[cell].toDouble()
                if (courant <= 0.0 || courant >= SMALL_COURANT) continue
                val bareShare = unshielded.shareOfDrop[cell]
                val coveredShare = shielded.shareOfDrop[cell]
                if (bareShare.isNaN() || coveredShare.isNaN() || unshielded.lawMetres[cell] < OBSERVED_FLOOR_METRES) continue
                smallChecked++
                val e = factor[cell].toDouble()
                val measured = coveredShare.toDouble() / bareShare.toDouble()
                smallWorst = maxOf(smallWorst, abs(measured - e * (1.0 + courant) / (1.0 + e * courant)))
                smallFromFactor = maxOf(smallFromFactor, abs(measured - e))
            }

            val wooded = BooleanArray(density.size) { unshielded.lawMetres[it] >= OBSERVED_FLOOR_METRES && density[it] >= WOODED_DENSITY }
            val bareBand = BooleanArray(density.size) { unshielded.lawMetres[it] >= OBSERVED_FLOOR_METRES && density[it] <= BARE_DENSITY }
            val bandRatio =
                perUnitPower(shielded.lawMetres, unshielded.lawMetres, wooded) /
                    perUnitPower(shielded.lawMetres, unshielded.lawMetres, bareBand)
            println(
                ("S3 COVER seed=%d  the law's rate on %d cells of one production round: the factor is " +
                    "right within %.0e on all but %d, worst %.2e, where the unshielded control is out by " +
                    "%.2f; the factor runs %.3f to %.3f; the band ratio (density >= %.1f against <= %.1f) is " +
                    "%.2f. The realised share on %d cells under F %.1f: within %.1e of e(1+F)/(1+eF), and " +
                    "at most %.3f from the factor itself").format(
                    seed, checked, OBSERVED_TOLERANCE, outliers, worst, control,
                    factor.filterIndexed { cell, _ -> ground.bareCut.isLand[cell] }.min(),
                    factor.filterIndexed { cell, _ -> ground.bareCut.isLand[cell] }.max(),
                    WOODED_DENSITY, BARE_DENSITY, bandRatio, smallChecked, SMALL_COURANT, smallWorst, smallFromFactor
                )
            )

            assertTrue(checked > MIN_FLANK_CELLS, "seed $seed: only $checked cells with a law's rate to read")
            assertTrue(
                outliers <= checked / OBSERVED_OUTLIER_SHARE,
                "seed $seed: $outliers of $checked cells are out by more than $OBSERVED_TOLERANCE, worst cell " +
                    "$worstCell shielded by ${"%.6f".format(shielded.lawMetres[worstCell] / unshielded.lawMetres[worstCell])} " +
                    "where its cover of ${"%.3f".format(density[worstCell])} asks for ${"%.6f".format(factor[worstCell])}"
            )
            // The control fails the same clause: with the shielding taken out the quotient is 1
            // everywhere, wrong by the width of the factor's own spread.
            assertTrue(
                control > OBSERVED_CONTROL_MARGIN,
                "seed $seed: the factor never departs from 1 by more than ${"%.3f".format(control)}, so a stage " +
                    "with no shielding at all would pass the clause above and this measurement proves nothing"
            )
            assertTrue(smallChecked > 0, "seed $seed: no cell under F $SMALL_COURANT to read the realised cut on")
            assertTrue(
                smallWorst <= OBSERVED_TOLERANCE,
                "seed $seed: where F is under $SMALL_COURANT the realised share moved by up to " +
                    "${"%.2e".format(smallWorst)} off e(1+F)/(1+eF)"
            )
        }
    }

    /**
     * Every routing pass weights its water against its own land, and the weights sum to that
     * land's cell count.
     *
     * The invariant the whole scheme rests on, read back out of production rather than assumed.
     * A pass whose weights averaged anything but one would be spending `E = K A^m S^n` against a
     * differently scaled A than the coefficient was fitted to — and the three passes route over
     * three different surfaces, the round's rock, the spoil-laid surface the closing breach cuts,
     * and the finished terrain the outlet pass grooves, each with a shoreline of its own.
     *
     * What each pass reports is summed where it routes: over the weights and the land mask the
     * accumulation is handed, not the normalising helper's own return, which is its own arithmetic
     * read back and cannot differ from one. And every pass has to report: one for each round, the
     * outlet grooves once, so a routing pass that stopped weighting its water, or one added that
     * never did, is missing from the list by name.
     */
    @Test
    fun `every routing pass weights its water against its own land`() {
        val config = WorldGenConfig(seed = 42L, width = 256, height = 256)
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val passes = mutableListOf<Triple<String, Double, Int>>()
        erodeBlockingObservingCover(config, plates.height, plates.upliftRateMmPerYear, weightSums = { name, summed, landCells ->
            passes += Triple(name, summed, landCells)
        })
        val names = passes.map { it.first }.toSet()
        val missing = (0 until config.erosion.hydraulicRounds).map { "round $it" }.filter { it !in names } +
            listOf("outlet").filter { config.erosion.deltaLobe && it !in names }
        assertTrue(missing.isEmpty(), "routing passes that reported no weights: $missing, of ${names.sorted()}")
        var worst = 0.0
        var worstPass = ""
        passes.forEach { (name, summed, landCells) ->
            val off = abs(summed / landCells - 1.0)
            if (off > worst) {
                worst = off
                worstPass = name
            }
        }
        println(
            "S3 WEIGHT SUMS %d passes, worst mean off 1 by %.2e at %s".format(
                passes.size, worst, worstPass
            )
        )
        assertTrue(
            worst <= MEAN_TOLERANCE,
            "$worstPass summed its weights to ${"%.3f".format(worst + 1.0)} of its own land's " +
                "cell count, so that pass routed water it had not normalised"
        )
    }

    /** One [Ground] per seed for the whole class: each is two full erosion runs. */
    private fun ground(seed: Long): Ground =
        measured.getOrPut(seed) { Ground(WorldGenConfig(seed = seed, width = 512, height = 512)) }

    /** The belt, and which of its cells the wind is climbing when it reaches them. */
    private fun beltFlanks(ground: Ground): Belt? {
        val config = ground.config
        val cellsAcross = config.width
        val cellsDown = config.height
        val distance = ground.plates.boundaryDistance.data
        val boundaryClass = ground.plates.nearestBoundaryClass
        val falloff = config.tectonics.boundaryFalloffCells
        val relative = ground.bareCut.relativeElevation.data
        val land = ground.bareCut.isLand

        val inBelt = BooleanArray(cellsAcross * cellsDown) { cell ->
            land[cell] && distance[cell] <= falloff &&
                (boundaryClass[cell] == BoundaryClass.ANDEAN_MARGIN.ordinal ||
                    boundaryClass[cell] == BoundaryClass.COLLISION_PLATEAU.ordinal) &&
                config.scale.metresAboveShoreline(relative[cell]) >= BELT_FLOOR_METRES
        }
        val largest = largestRun(inBelt, cellsAcross, cellsDown) ?: return null

        val zonal = ground.bareClimate.windDirection
        val meridional = ground.bareClimate.windMeridional.data
        val windward = BooleanArray(largest.size)
        val leeward = BooleanArray(largest.size)
        // How much of a rise counts as a flank rather than as a crest or a bench: a tenth of what
        // the critical slope lets one cell hold, so the cut-off is the same ground everywhere
        // rather than the same number of metres on every grid.
        val flankRise = config.erosion.criticalFallMetresPerKm * config.cellWidthKm.toFloat() *
            CREST_SHARE_OF_CRITICAL_SLOPE
        for (cell in largest.indices) {
            if (!largest[cell]) continue
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            val upwindRow = row - if (meridional[cell] > 0f) 1 else if (meridional[cell] < 0f) -1 else 0
            if (upwindRow < 0 || upwindRow >= cellsDown) continue
            val upwindColumn = (column - zonal[cell] + cellsAcross) % cellsAcross
            val upwind = upwindRow * cellsAcross + upwindColumn
            if (!land[upwind]) continue
            val rise = config.scale.metresAboveShoreline(relative[cell]) -
                config.scale.metresAboveShoreline(relative[upwind])
            if (rise > flankRise) windward[cell] = true else if (rise < -flankRise) leeward[cell] = true
        }
        return Belt(windward, leeward)
    }

    /** The largest 8-connected run of a mask, wrapping in x as the world does. */
    private fun largestRun(mask: BooleanArray, cellsAcross: Int, cellsDown: Int): BooleanArray? {
        val label = IntArray(mask.size) { -1 }
        var best = -1
        var bestSize = 0
        var next = 0
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || label[start] >= 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            var size = 0
            while (head < tail) {
                val cell = queue[head++]
                size++
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                for (rowStep in -1..1) for (columnStep in -1..1) {
                    if (rowStep == 0 && columnStep == 0) continue
                    val neighbourRow = row + rowStep
                    if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                    val neighbour = neighbourRow * cellsAcross +
                        (column + columnStep + cellsAcross) % cellsAcross
                    if (!mask[neighbour] || label[neighbour] >= 0) continue
                    label[neighbour] = next
                    queue[tail++] = neighbour
                }
            }
            if (size > bestSize) {
                bestSize = size
                best = next
            }
            next++
        }
        if (best < 0) return null
        return BooleanArray(mask.size) { label[it] == best }
    }

    /** Share of a flank's cells the routed network makes channel. */
    private fun channelShare(
        config: WorldGenConfig,
        terrain: FloatField,
        belt: Belt,
        weights: FloatArray
    ): Pair<Double, Double> {
        val cut = SeaLevelStage.percentileCut(terrain, config.seaLevel, config.scale)
        val filled = FlowRouting.fillDepressions(
            config.width, config.height, cut.isLand, cut.relativeElevation
        )
        val directions = FlowRouting.flowDirections(
            config.width, config.height, cut.isLand, cut.relativeElevation, filled,
            config.seed, config.cellHeightInCellWidths, config.facetRouting, config.flatPotential
        )
        val area = FlowRouting.accumulate(
            config.width, config.height, cut.isLand, filled, directions, cut.landCellCount
        ) { cell -> weights[cell] }
        var windwardChannel = 0
        var windwardLand = 0
        var leewardChannel = 0
        var leewardLand = 0
        for (cell in area.data.indices) {
            val channel = cut.isLand[cell] && area.data[cell] >= CHANNEL_SUPPORT_CELLS
            if (belt.windward[cell]) {
                windwardLand++
                if (channel) windwardChannel++
            } else if (belt.leeward[cell]) {
                leewardLand++
                if (channel) leewardChannel++
            }
        }
        return Pair(
            if (windwardLand == 0) 0.0 else windwardChannel.toDouble() / windwardLand,
            if (leewardLand == 0) 0.0 else leewardChannel.toDouble() / leewardLand
        )
    }

    /** Summed [shielded] over summed [bare] across a mask: the band's own shielding, pooled. */
    private fun perUnitPower(
        shielded: FloatArray,
        bare: FloatArray,
        mask: BooleanArray
    ): Double {
        var cut = 0.0
        var spent = 0.0
        for (cell in mask.indices) {
            if (!mask[cell]) continue
            cut += shielded[cell].toDouble()
            spent += bare[cell].toDouble()
        }
        return if (spent <= 0.0) 0.0 else cut / spent
    }

    private fun meanOver(field: FloatArray, mask: BooleanArray): Double {
        var summed = 0.0
        var cells = 0
        for (cell in mask.indices) if (mask[cell]) {
            summed += field[cell].toDouble()
            cells++
        }
        return if (cells == 0) 0.0 else summed / cells
    }

    /** Spearman's rank correlation of two fields over a mask, ties averaged. */
    private fun spearman(first: FloatArray, second: FloatArray, mask: BooleanArray): Double {
        val cells = (first.indices).filter { mask[it] }
        if (cells.size < 2) return 0.0
        val firstRanks = ranks(cells, first)
        val secondRanks = ranks(cells, second)
        val mean = (cells.size - 1) / 2.0
        var covariance = 0.0
        var firstSpread = 0.0
        var secondSpread = 0.0
        for (index in cells.indices) {
            val a = firstRanks[index] - mean
            val b = secondRanks[index] - mean
            covariance += a * b
            firstSpread += a * a
            secondSpread += b * b
        }
        val spread = sqrt(firstSpread * secondSpread)
        return if (spread <= 0.0) 0.0 else covariance / spread
    }

    private fun ranks(cells: List<Int>, field: FloatArray): DoubleArray {
        val order = cells.indices.sortedBy { field[cells[it]] }
        val rank = DoubleArray(cells.size)
        var index = 0
        while (index < order.size) {
            var last = index
            while (last + 1 < order.size &&
                field[cells[order[last + 1]]] == field[cells[order[index]]]
            ) {
                last++
            }
            val shared = (index + last) / 2.0
            for (tie in index..last) rank[order[tie]] = shared
            index = last + 1
        }
        return rank
    }

    private companion object {
        /** `GeographyAuditTest`'s seeds, which is what "the standard seeds" means in this suite. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L)

        /**
         * How high a cell near a convergent boundary must stand to be part of a range rather than
         * part of the country in front of it. A kilometre, which is `GroundTextureTest`'s floor
         * for the same question.
         */
        const val BELT_FLOOR_METRES = 1000f

        /** Below this a flank is too small a sample to mean anything. */
        const val MIN_FLANK_CELLS = 200

        /** See the class KDoc: a fifth off the law's prediction, for everything but the rain. */
        const val STREAM_POWER_SLACK = 0.8

        /** A rise smaller than this is crest or bench, not flank. See [beltFlanks]. */
        const val CREST_SHARE_OF_CRITICAL_SLOPE = 0.1f

        /** Catchment a cell needs before the network counts it as channel, in cells. */
        const val CHANNEL_SUPPORT_CELLS = 16f

        /**
         * Below this `F` the realised cut is read: a tenth, where `e (1 + F) / (1 + e F)` is within
         * a tenth of `e |1 - e|` of the factor, the proportional law's own reading.
         */
        const val SMALL_COURANT = 0.1

        const val WOODED_DENSITY = 0.6f
        const val BARE_DENSITY = 0.1f

        /**
         * How far an observed quotient may sit from what it is held to: a thousandth. The rates are
         * products of floats read off a height field of order 1 against drops of order 1e-4, and the
         * realised shares differences of that field, so a part in a thousand is what the
         * arithmetic can carry.
         */
        const val OBSERVED_TOLERANCE = 1e-3

        /**
         * The least law's rate a cell must carry for its quotient to carry the factor.
         *
         * The height field is a float on a 16 km relief span, so its own resolution is of order a
         * millimetre of ground; below a metre the quotient of two of them is mostly rounding. See
         * [OBSERVED_TOLERANCE], which is what a metre leaves.
         */
        const val OBSERVED_FLOOR_METRES = 1f

        /**
         * One cell in this many may sit outside [OBSERVED_TOLERANCE], as a divisor on the count.
         *
         * A thousand, so a tenth of a per cent, against a tail measured in the dozens out of
         * eighty-odd thousand. It is a bar on a *count* rather than on a figure, which is what
         * makes it safe: a stage that had lost its shielding would put every cell outside the
         * tolerance at once, not one in a thousand.
         */
        const val OBSERVED_OUTLIER_SHARE = 1000

        /**
         * How far the factor must depart from 1 somewhere, for the control to mean anything.
         *
         * The control run takes the shielding out of the production cut, so its quotient is 1 on
         * every cell. If the factor were within a whisker of 1 everywhere, the clause above could
         * not tell that run from the shielded one and would be passing on a coincidence. Measured
         * spread is about 0.4 either way; a tenth is the floor.
         */
        const val OBSERVED_CONTROL_MARGIN = 0.1

        /**
         * How far a routing pass's weights may average from 1 over its own land: a sum of a hundred
         * thousand floats in a double, divided by their count, which is float rounding and no more.
         */
        const val MEAN_TOLERANCE = 1e-6

        /**
         * A regression pin, not a derived figure: set under the correlation S3 measured and
         * recorded (see docs/DESIGN_LEDGER.md, S3), so a change that loosened the link between the
         * rain and the cut would show here.
         */
        const val DISSECTION_CORRELATION = 0.20

        /** How many times the control's correlation the fed one must reach. A pin, likewise. */
        const val DISSECTION_OVER_CONTROL = 1.5

        private val measured = HashMap<Long, Ground>()
    }
}
