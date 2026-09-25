package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.HydraulicErosion
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

        private val observed = HashMap<Triple<Boolean, Boolean, Boolean>, FloatArray>()

        /** Metres the real stage took off each cell in one round, under the three switches. */
        fun observedIncision(
            climateFeed: Boolean,
            shieldCut: Boolean,
            receiverClamp: Boolean = true
        ): FloatArray =
            observed.getOrPut(Triple(climateFeed, shieldCut, receiverClamp)) {
                val after = erodeBlockingObservingCover(
                    once.copy(erosion = once.erosion.copy(climateFeed = climateFeed)),
                    bare, upliftRateMmPerYear = null, shieldCut = shieldCut, receiverClamp = receiverClamp
                )
                val metresPerUnit = once.scale.reliefSpanMetres
                FloatArray(bare.data.size) {
                    (bare.data[it] - after.height.data[it]) * metresPerUnit
                }
            }

        /**
         * Where neither of the round's two caps bit on either run, so the quotient of two observed
         * incisions is the cover's factor and nothing else.
         *
         * Both runs, and that matters now the factor is relative: it runs from about 0.58 on
         * closed canopy to about 1.3 on bare ground, so the shielded cut is the *larger* of the
         * two wherever the ground is barer than the land's mean, and it can reach a cap the
         * unshielded one does not.
         */
        val bareUnclamped: BooleanArray by lazy {
            val filledBare = FlowRouting.fillDepressions(
                once.width, once.height, bareCut.isLand, bareCut.relativeElevation
            )
            val receiversBare = FlowRouting.flowDirections(
                once.width, once.height, bareCut.isLand, bareCut.relativeElevation, filledBare,
                once.seed, once.cellHeightInCellWidths, once.facetRouting, once.flatPotential
            )
            val area = FlowRouting.accumulate(
                config.width, config.height, bareCut.isLand, filledBare, receiversBare,
                bareCut.landCellCount
            ) { cell -> bareRunoff[cell] }
            val landCells = bareCut.landCellCount.toFloat()
            val coefficient = HydraulicErosion.Rates(once).incisionCoefficient
            val relative = bareCut.relativeElevation.data
            val floor = filledBare.data
            // The caps in the height field's unit, which is what the stage spends them in since
            // Fix 3: the drop and the height above the shoreline are read on the relative field,
            // whose unit is this share of it, and a drop to the sea is to the shoreline.
            val landHalfOfField = once.scale.landHalfOfField
            BooleanArray(area.data.size) { cell ->
                val receiver = receiversBare[cell]
                if (receiver < 0 || !bareCut.isLand[cell]) return@BooleanArray false
                val toSea = !bareCut.isLand[receiver]
                val drop = floor[cell] - if (toSea) 0f else floor[receiver]
                if (drop <= 0f) return@BooleanArray false
                // The step on the ground, as the stage's own cut measures it.
                val slope = drop / config.groundSteps.between(cell, receiver, config.width) * config.width
                val unshielded = coefficient * sqrt(area.data[cell] / landCells) * slope
                val larger = unshielded * maxOf(1f, bareErodibility[cell])
                unshielded > 0f && larger < drop * 0.5f * landHalfOfField &&
                    larger < relative[cell].coerceAtLeast(0f) * landHalfOfField
            }
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

            // The forcing, read off what the stage actually did. One round each way on the same
            // terrain, so the receivers and the gradients are shared and the only thing that
            // differs is whether the water and the cover are the world's own or flat and absent.
            val fed = ground.observedIncision(climateFeed = true, shieldCut = true)
            val flat = ground.observedIncision(climateFeed = false, shieldCut = true)
            val figures = Flank(
                seed = seed,
                rainRatio = windwardRain / leewardRain,
                fedRatio = meanOver(fed, belt.windward) / meanOver(fed, belt.leeward),
                flatRatio = meanOver(flat, belt.windward) / meanOver(flat, belt.leeward),
                windwardCells = belt.windwardCells,
                leewardCells = belt.leewardCells
            )
            println(
                ("S3 FLANK seed=%d  rain %.0f/%.0f mm = %.2fx; first-round incision windward " +
                    "over leeward fed %.3fx, flat %.3fx, so the feed multiplies it by %.2fx; " +
                    "law asks %.2f; %d windward and %d leeward cells").format(
                    seed, windwardRain, leewardRain, figures.rainRatio, figures.fedRatio,
                    figures.flatRatio, figures.forcing, figures.bar,
                    figures.windwardCells, figures.leewardCells
                )
            )
            figures
        }
        assertTrue(
            measurements.size >= 2,
            "only ${measurements.size} of ${SEEDS.size} seeds offered a belt to measure"
        )
        // Seed 1234 has fallen short since the ground was put on its ruler, and runs as a known
        // failure rather than under a smaller slack. Its new belt takes three and a half times the
        // rain on its windward flank, and the feed buys 1.26 of the law's 1.87 where the bar is
        // 1.49; with the erodibility a tenth of itself, so that the half-the-drop cap binds on
        // fewer cells, it buys 1.41. The cap, which a north-south step reaches twice as soon now
        // that its drop is read on the ground (Audit III's B-D1, the erosion's units), takes some
        // of the difference and not all of it (docs/DESIGN_LEDGER.md, Fix 2).
        val short = measurements.filter { it.forcing < it.bar }
        KnownFailures.expect(WET_FLANK_UNDER_THE_LAW, "seed 1234: 1.12 under 1.49") {
            if (short.isNotEmpty()) {
                val found = short.joinToString { String.format(Locale.ROOT, "seed %d: %.2f under %.2f", it.seed, it.forcing, it.bar) }
                throw RecordedViolation(
                    "the windward flank takes more of the rain and turning the feed on multiplies its " +
                        "share of the first round's incision by less than the stream-power law asks for: $found",
                    found
                )
            }
        }
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
        val shortfalls = ArrayList<String>()
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
                shortfalls += String.format(Locale.ROOT, "seed %d fed %.3f flat %.3f", seed, fed, flat)
                complaints += "seed $seed: flat rain already correlates at ${"%.3f".format(flat)} against the " +
                    "fed ${"%.3f".format(fed)}, so the fed figure says little about the rain"
            }
        }
        KnownFailures.expect(CAP_SETS_EVERY_CUT, "seed 42 fed 0.203 flat 0.144; seed 1234 fed 0.180 flat 0.126") {
            if (complaints.isNotEmpty()) {
                val found = shortfalls.joinToString("; ")
                throw RecordedViolation(complaints.joinToString("; "), found)
            }
        }
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
            "seed 7 at 0.014, seed 1234 at 0.180, seed 99 at 0.134; seed 7's flat-rain control at -0.057"
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
     * The cover multiplies the incision by exactly what the constant says, cell by cell — and
     * takes no rock off the world in the aggregate.
     *
     * Two clauses, and the second is the one that was learned the hard way. The multiplier is
     * relative: `1 - 0.5 * density` over its own mean across the land, so the land's mean
     * erodibility is exactly 1 and the term redistributes the cutting rather than reducing it.
     * `bedrockErodibilityPerYear` was calibrated on real bedrock rivers, which ran through
     * forests, so an absolute multiplier counts the cover twice — it was built that way first and
     * it cost a third of the world's erosion. So the mean of the factor over land is asserted to
     * be 1, and that is what makes the calibration survive the feature.
     *
     * The per-cell clause is asserted per cell and not as a ratio between two bands, because a
     * band ratio cannot test it: `1 - 0.5 * density` over cells at density >= 0.6 against cells at
     * density <= 0.1 can land anywhere from about 0.50 to 0.74 depending on how the density is
     * distributed inside each band, so a bar drawn at Istanbulluoglu and Bras's half would fail a
     * correct implementation as readily as a wrong one. Take the first round's incision with the
     * cover and without it on the same terrain and the same receivers, keep the cells where
     * neither of the two caps bit — a capped cut is the cap's figure and not the law's — and the
     * quotient must be the factor to the last few bits of a float. The band ratio is printed
     * beside it, and being a ratio of two relative factors it is the same figure the absolute form
     * gave: the mean divides out of it.
     */
    @Test
    fun `cover on the ground holds the incision back`() {
        for (seed in SEEDS) {
            val ground = ground(seed)
            // Both from the stage, on the same terrain, with one switch between them: the second
            // run is the production cut with its shielding taken out. Nothing here recomputes the
            // incision, so a shielding term deleted from `cut` fails this rather than passing it.
            val shielded = ground.observedIncision(
                climateFeed = true, shieldCut = true, receiverClamp = false
            )
            val unshielded = ground.observedIncision(
                climateFeed = true, shieldCut = false, receiverClamp = false
            )
            val unclamped = ground.bareUnclamped
            val density = ground.bareDensity
            val factor = ground.bareErodibility

            var checked = 0
            var worst = 0.0
            var worstCell = -1
            var control = 0.0
            var outliers = 0
            for (cell in shielded.indices) {
                // A floor on the cut itself, and not fussiness. The two incisions are differences
                // of a float height field whose own resolution is about a millimetre of ground, so
                // a cell the round barely touched carries a quotient made mostly of rounding. A
                // metre of cut leaves three decimal places of signal in it.
                if (!unclamped[cell] || unshielded[cell] < OBSERVED_FLOOR_METRES) continue
                checked++
                val measured = shielded[cell].toDouble() / unshielded[cell].toDouble()
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

            val wooded = BooleanArray(density.size) { unclamped[it] && density[it] >= WOODED_DENSITY }
            val bareBand = BooleanArray(density.size) { unclamped[it] && density[it] <= BARE_DENSITY }
            val bandRatio =
                perUnitPower(shielded, unshielded, wooded) / perUnitPower(shielded, unshielded, bareBand)
            println(
                ("S3 COVER seed=%d  observed on %d unclamped cells of one production round: the " +
                    "factor is right within %.0e on all but %d of them, worst %.2e, where the " +
                    "same measurement on the unshielded control is out by %.2f; the factor runs " +
                    "%.3f to %.3f; the band ratio (density >= %.1f " +
                    "against <= %.1f) is %.2f over %d and %d cells, where the cover means %.2f " +
                    "and %.2f").format(
                    seed, checked, OBSERVED_TOLERANCE, outliers, worst, control,
                    factor.filterIndexed { cell, _ -> ground.bareCut.isLand[cell] }.min(),
                    factor.filterIndexed { cell, _ -> ground.bareCut.isLand[cell] }.max(),
                    WOODED_DENSITY, BARE_DENSITY, bandRatio,
                    wooded.count { it }, bareBand.count { it },
                    meanOver(density, wooded), meanOver(density, bareBand)
                )
            )

            assertTrue(checked > MIN_FLANK_CELLS, "seed $seed: only $checked unclamped cells")
            // All but a handful, rather than all. The two runs are whole passes of the stage,
            // and a few cells in a hundred thousand diverge in ways this cap model does not
            // reproduce exactly - a cell whose receiver was cut to a different depth in the two
            // runs meets a different drop, and both caps are read off that drop. The tail is what
            // is bounded, and tightly enough that a shielding term deleted from `cut` could not
            // hide in it: that would put *every* cell out by the factor's own spread, which the
            // control clause below measures.
            assertTrue(
                outliers <= checked / OBSERVED_OUTLIER_SHARE,
                "seed $seed: $outliers of $checked unclamped cells are out by more than " +
                    "$OBSERVED_TOLERANCE, worst cell $worstCell shielded by " +
                    "${"%.6f".format(shielded[worstCell] / unshielded[worstCell])} where its " +
                    "cover of ${"%.3f".format(density[worstCell])} asks for " +
                    "${"%.6f".format(factor[worstCell])}"
            )
            // And the control is shown to fail the same clause: with the shielding taken out of
            // the production cut the quotient is 1 everywhere, which is wrong by the width of the
            // factor's own spread. Without this the clause above would pass a stage that had lost
            // its shielding term, because it would be comparing 1 against 1.
            assertTrue(
                control > OBSERVED_CONTROL_MARGIN,
                "seed $seed: the factor never departs from 1 by more than " +
                    "${"%.3f".format(control)}, so a stage with no shielding at all would pass " +
                    "the clause above and this measurement proves nothing"
            )
            // The calibration, that the factor averages 1 over the land so a world of uniform
            // cover erodes as a bare one did, is held by the per-cell clause above and not asked
            // again here: the factor that clause checks the stage against is normalised over the
            // land, so a stage that spent an unnormalised one would miss it on every cell. The
            // mean of the test's own normalised factor, which this used to assert, is one by the
            // test's own arithmetic and could not fail.
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
        erodeBlockingObservingCover(config, plates.height, plates.upliftRateMmPerYear) { name, summed, landCells ->
            passes += Triple(name, summed, landCells)
        }
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
        /**
         * The known failure the clauses Fix 3 moved record: with the incision's caps spent in the
         * height field's own unit, the cap at half the drop sets the cut on every drawn channel, so
         * the rounds cut less than the stream-power law asks and the explicit update, not the law,
         * shapes the channels. The implicit solver's chunk is where it is next taken up; see
         * docs/DESIGN_LEDGER.md, Fix 3.
         */
        const val CAP_SETS_EVERY_CUT =
            "the erosion: with its caps in one unit the half-the-drop cap sets every drawn channel's cut, and the explicit incision cuts less than the stream-power law asks"

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

        /** The known failure the wet-flank clause records. See docs/DESIGN_LEDGER.md, Fix 2. */
        const val WET_FLANK_UNDER_THE_LAW =
            "the erosion: seed 1234's windward flank is cut less for its rain than the stream-power law asks"




        /** A rise smaller than this is crest or bench, not flank. See [beltFlanks]. */
        const val CREST_SHARE_OF_CRITICAL_SLOPE = 0.1f

        /** Catchment a cell needs before the network counts it as channel, in cells. */
        const val CHANNEL_SUPPORT_CELLS = 16f

        const val WOODED_DENSITY = 0.6f
        const val BARE_DENSITY = 0.1f

        /**
         * How far the observed quotient of two production height fields may sit from the factor.
         *
         * Not one expression compared with itself but two runs of a whole stage differenced through
         * a float height field, where the cut is subtracted from an elevation of order 1 and the
         * difference is of order 1e-4. A part in ten thousand of the factor is what that arithmetic
         * can carry.
         */
        const val OBSERVED_TOLERANCE = 1e-3

        /**
         * The least a cell must have been cut for its quotient to carry the factor.
         *
         * The height field is a float on a 16 km relief span, so its own resolution is of order a
         * millimetre of ground; below a metre of cut the quotient of two of them is mostly the
         * subtraction's rounding. See [OBSERVED_TOLERANCE], which is what a metre leaves.
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
