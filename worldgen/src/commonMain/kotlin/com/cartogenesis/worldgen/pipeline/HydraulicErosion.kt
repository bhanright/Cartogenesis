package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.standAside
import com.cartogenesis.worldgen.math.GroundSteps
import com.cartogenesis.worldgen.model.ErosionConfig
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What one hydraulic round moved, in the height units the field itself is kept in.
 *
 * The three tallies are the mass budget: every scrap of material the incision took off the land
 * either settled somewhere else on the land, or went out to sea and left the model. [fieldDrop] is
 * the same quantity measured independently — how much the summed height field actually fell over
 * the round — so a mistake in the bookkeeping cannot hide behind the bookkeeping.
 */
internal data class RoundMass(
    val incised: Double,
    val deposited: Double,
    val lostToSea: Double,
    val fieldDrop: Double,
    /**
     * Metres of rock the tectonic uplift added this round, summed over the map, and the metres of
     * bend the flexure answered it and the erosion with, summed as a magnitude.
     *
     * The coupling, in two numbers. In a belt at steady state the first is spent against the
     * incision and the second holds most of it back, which is why a range does not grow by five
     * millimetres a year for four million years. The bend is summed unsigned because its signed
     * sum is zero: the filter carries no zero-frequency term, so the plate tips rather than sinks.
     */
    val uplifted: Double = 0.0,
    val deflected: Double = 0.0,
    /** Of [incised], how much the outlet notches took. Zero when the notch is switched off. */
    val notched: Double = 0.0,
    /** How many cells the notches were cut into. */
    val notchCells: Int = 0,
    /** How many depressions the fill had to raise this round. */
    val basins: Int = 0,
    /** Cells in the largest of them, and how deep the fill stands over its lowest ground. */
    val largestBasinCells: Int = 0,
    val largestBasinDepth: Float = 0f,
    /** The rim cell that basin spills over, or -1 if it has none. */
    val largestBasinSpill: Int = -1,
    /** The deepest fill anywhere on the map, over any basin's lowest ground. */
    val deepestBasin: Float = 0f,
    /**
     * How deep the standing fill lies over the land as the round opens, in metres: the fill's
     * depth over the ground summed over every cell raised by more than the pond depth, over the
     * land's cell count. The volume of water the basins hold, spread over the land, so it answers
     * how deep the fill is without depending on which basin happens to be the largest.
     */
    val fillDepthOverLandMetres: Double = 0.0,
    /**
     * Channel cells standing lower than the cell they drain into, counted after each of the round's
     * mechanisms in turn: as the round opened, after the outlet notch, after the incision, after
     * the deposition, after the closing passes, and after the thermal relaxation.
     *
     * The pit census the receiver clamp was written from. A channel cell below its own receiver
     * is a hole in a river's bed: the next round's priority flood has to raise it, and along a
     * channel those raised cells line up into the thin grid-bearing bars `GlaciationTest`'s comb
     * measurement catches. Measured per mechanism because the plan asks for exactly one clamp to
     * be added on evidence rather than four on suspicion. See docs/DESIGN_LEDGER.md, H5b, for the
     * counts.
     *
     * Measured on the rock, which is the surface the next round's fill will route over: the spoil
     * is held off the terrain until the last round and cannot pond anything before then. So the
     * deposition slot is the incision slot again for every round but the last, and what the
     * deposition and the closing passes do to the finished surface shows up in the closing slot,
     * which is taken after the spoil is laid.
     *
     * A channel is a cell carrying at least [DRAWN_RIVER] of the land's water, which is the
     * erosion stage's own rule for one and not the network the map draws. Diagnostics only;
     * nothing reads them back, and they are computed only when a caller asked for the round tally.
     */
    val channelPits: IntArray = IntArray(0)
)

/**
 * What the implicit incision pass did to each cell it reached, for the guards that have to see the
 * law's rate and the base level a cell was cut toward, which the finished heights hide.
 * Diagnostics only: nothing reads an answer back, and a pass with no watcher computes nothing for
 * one.
 */
internal interface IncisionWatch {

    /**
     * One land cell with a receiver, as the implicit pass met it, every height in the height
     * field's own units.
     *
     * [courantNumber] is the round's `F` for the cell (see [HydraulicErosion.Rates.courantCoefficient]),
     * dimensionless. [before] is the cell's height as the pass found it and [after] as it left it.
     * [baseBefore] and [baseAfter] are the level the cell grades to, before the pass and once its
     * receiver was final: the receiver's own ground on land, the round's shoreline where the
     * receiver is sea, and the water's surface where the receiver stands under a filled basin's
     * water. The law's explicit rate on the ground as the pass found it, the stream-power term, is
     * `courantNumber * (before - baseBefore)`; the cut the pass realised is `before - after`.
     */
    fun cut(
        round: Int,
        cell: Int,
        receiver: Int,
        courantNumber: Float,
        before: Float,
        baseBefore: Float,
        baseAfter: Float,
        after: Float
    )

    /**
     * The implicit pass has cut every cell of [round]. [surface] is the height field as it left it;
     * [ground] (the depression-filled surface the routing ran on) and [relative] are the round's
     * shoreline-relative fields after the outlet notch, whose land half is [landRange] of the
     * height field; [shorelineHeight] is the round's shoreline in the height field. Every array is
     * the pass's own and must not be written.
     */
    fun incised(
        round: Int,
        isLand: BooleanArray,
        directions: IntArray,
        ground: FloatArray,
        relative: FloatArray,
        discharge: FloatArray,
        landCells: Float,
        landRange: Float,
        shorelineHeight: Float,
        surface: FloatArray
    )
}

/** The points in a round the pit census above is taken at. */
internal object PitStage {
    const val OPENING = 0
    const val NOTCH = 1
    const val INCISION = 2
    /** After the spoil is laid on the rock, which happens once, in the last round. */
    const val SPOIL = 3
    /** After the closing breach and the distributary grooves. */
    const val CLOSING = 4
    const val RELAX = 5
    const val COUNT = 6
    val names = arrayOf("opening", "notch", "incision", "spoil", "closing", "relax")
}

/**
 * Cutting valleys with running water, and putting the spoil back down.
 *
 * Thermal erosion answers a question about rock — how steeply it can stand before it fails — and
 * gives mountains their flanks. It does not answer the question about water, which is why rivers
 * were finding their way down terrain their own flow had never shaped: they ran in whatever
 * hollows the noise happened to leave rather than in valleys they had cut.
 *
 * This is stream-power incision, the standard of landscape-evolution models: a cell lowers in
 * proportion to the square root of the area draining through it times the slope it sits on. The
 * feedback is the point. A channel that cuts down gathers more water next round, which cuts it
 * deeper still, and the divides between channels sharpen as the channels fall away from them. That
 * is where V-shaped valleys, dendritic drainage and ridge lines come from, and none of them can be
 * had by smoothing.
 *
 * The other half is where the spoil goes. A river is a conveyor, not a drain: it carries what it
 * cuts until the gradient slackens and it can no longer hold it, and then it lays it down. That is
 * the difference between a landscape of nothing but valleys and one with floodplains along its
 * lower trunks, alluvial fans where a range meets the plain, and — the visible prize — deltas where
 * the biggest rivers meet the sea. Sediment is carried down the same D8 network the incision walks,
 * in one pass, sources first, so a cell knows what its tributaries brought it before it decides
 * whether to cut or to settle.
 *
 * The water that does all this is the water the sky actually delivers. The circle — the climate
 * depends on terrain the erosion has not shaped yet — is cut by running a provisional climate on
 * the thermally weathered uplift once, before the first round, and holding its answer for all of
 * them. Discharge is then `Q = P * A` rather than `A`: a cell contributes its own rainfall to
 * everything downstream of it, so a wet flank's channels carry more water than a dry one's at the
 * same catchment and cut harder for it. And the cover on the ground resists the cut, so a forested
 * slope wears more slowly than a bare one at the same discharge and gradient. See
 * [provisionalWeather] and `ErosionConfig.climateFeed`, which is the control the guards are shown
 * failing against.
 */
internal object HydraulicErosion {

    /**
     * How far the depression-filled surface must stand above real ground before a cell counts as
     * standing water rather than a flat the epsilon-fill nudged, in metres.
     *
     * Deliberately the same figure as `LakesConfig.minDepthMetres`, and deliberately a constant
     * rather than a read of that setting: the lakes section is chosen long after erosion runs, and
     * having erosion read it would mean adding `lakes` to erosion's reuse guard so that moving a
     * river setting re-cut every valley.
     */
    internal const val POND_DEPTH_METRES = 24f

    /**
     * Every rate, reach and depth this stage spends, converted out of [WorldScale] and the grid
     * once, where the stage reads them.
     *
     * One class rather than a scatter of conversions, so a reader can see the whole ruler in one
     * place and a guard can read every one of them back. Nothing below is a fraction of an assumed
     * range: each is a length in kilometres, a depth in metres or a rate in years, and what the
     * grid does to it is arithmetic.
     */
    internal class Rates(config: WorldGenConfig) {

        private val scale = config.scale
        private val erosion = config.erosion

        /**
         * Stream-power incision per round, as the coefficient on `sqrt(catchment share) * slope`
         * that the walk actually spends on the height field.
         *
         * `E = K * A^m * S^n` with m = 0.5 and n = 1, over
         * [WorldScale.yearsPerHydraulicRound]. The stage holds the catchment as a share of all
         * land and the slope as a rise per map width, so `sqrt(A)` is `sqrt(share * landArea)`.
         * The rise is read on the shoreline-relative field, whose unit is
         * [WorldScale.highestLandMetres], so `S` is that rise times `highestLandMetres` over the
         * map's width in metres; the cut is spent on the height field, whose unit is
         * [WorldScale.reliefSpanMetres], so the metres are divided by that. What is left is
         * `K * years * sqrt(landArea) / worldWidth * highestLandMetres / reliefSpanMetres`.
         * Everything but the share and the rise is constant over a generation, and this is it.
         *
         * The land's area is the configured share of the world rather than the round's own count
         * of land cells. Sea level is a percentile, so that share *is* the land's area by
         * construction; taking the count instead would make the coefficient wobble a percent from
         * round to round as the lowstand moved the shoreline, which is a property of the sea's
         * history and not of the rock.
         *
         * The ratio of the two rulers was taken to cancel from S2's second pass until Fix 3, and
         * the round was labelled 2.67 times shorter to keep the cut; see
         * [WorldScale.yearsPerHydraulicRound]. The label is back at the years the cut is worth and
         * the ratio back in the coefficient, which is the same float it was.
         */
        val incisionCoefficient: Float = run {
            val landAreaKm2 = (1.0 - config.seaLevel.toDouble().coerceIn(0.0, 1.0)) * scale.worldAreaKm2
            val perYear = erosion.bedrockErodibilityPerYear.toDouble() * scale.yearsPerHydraulicRound
            val geometry = sqrt(landAreaKm2) / scale.worldWidthKm
            val landRulerOverFieldRuler = scale.highestLandMetres.toDouble() / scale.reliefSpanMetres.toDouble()
            (perYear * geometry * landRulerOverFieldRuler).toFloat()
        }

        /**
         * The same rate in the shoreline-relative units the outlet notch is measured and spent in,
         * which is [incisionCoefficient] read off the land's half of the ruler instead of the
         * whole field's: `K * years * sqrt(landArea) / worldWidth`, which reads no vertical ruler.
         */
        val relativeIncisionCoefficient: Float =
            incisionCoefficient * scale.reliefSpanMetres / scale.highestLandMetres

        /**
         * The part of a round's Courant number `F` that is the same for every cell: `F` is this
         * times `sqrt(share) * erodibility / stepCellWidths`, where `share` is the cell's share of
         * the land's water, `erodibility` the cover's factor on it and `stepCellWidths` the step to
         * its receiver on the ground in cell widths. All four factors are dimensionless, so `F` is.
         *
         * `F` is the stream-power law's cut in one round over the drop that cut is taken from, both
         * in the height field's unit. From [incisionCoefficient] as it is spent: the law's cut is
         * `incisionCoefficient * sqrt(share) * slope * erodibility`, in the height field's unit,
         * where `slope` is `drop / stepCellWidths * cellsAcross` and the drop is read on the
         * shoreline-relative field. That drop is `dropInField / landHalfOfField`, the land's half
         * of the field being the relative field's unit, so the cut is
         * `incisionCoefficient / landHalfOfField * cellsAcross * sqrt(share) * erodibility /
         * stepCellWidths` times the drop in the height field's own unit. The factor before
         * `sqrt(share)` is this. At the stock world it is `relativeIncisionCoefficient *
         * cellsAcross`, 0.1467 times the grid's width, which over `sqrt` of the land's cell count
         * makes `F` about `0.24 sqrt(cells of catchment)` along a row at every grid.
         *
         * `F` is what decides whether an explicit step is bounded: past one it asks for more than
         * the drop. The implicit update the rounds spend it through is bounded at every `F`; see
         * the pass in [apply].
         */
        val courantCoefficient: Float =
            incisionCoefficient / scale.landHalfOfField * config.width

        /**
         * The diffusivity of a cell's unresolved channels and hillslopes on bare ground at mean
         * rain, in square metres a year: `K dx dy`. See [ErosionConfig.subGridTransport] for its
         * derivation; the cover's factor and the square root of the runoff weight multiply it per
         * cell.
         */
        val subGridDiffusivity: Double =
            erosion.bedrockErodibilityPerYear.toDouble() * config.cellWidthKm * 1000.0 * config.cellHeightKm * 1000.0

        /** Metres on the ground across a cell and down a row. */
        val cellWidthMetres: Double = config.cellWidthKm * 1000.0
        val cellHeightMetres: Double = config.cellHeightKm * 1000.0

        /** [POND_DEPTH_METRES] as a share of the land's relief, which is what the routing works in. */
        val pondDepth: Float = scale.reliefShareOfMetres(POND_DEPTH_METRES)

        /** [NOTCH_FALL_METRES_PER_KM] as the fall over one cell width, in those same units. */
        val notchFallPerCell: Float =
            scale.reliefShareOfMetres(NOTCH_FALL_METRES_PER_KM * config.cellWidthKm.toFloat())

        /**
         * [SHELF_BREAK_METRES] as a share of the raw height field, which is where the depth it is
         * compared against is measured.
         *
         * The field's own ruler and not either half of the piecewise one, for the reason
         * `SeaConfig.lowstandMetres` gives: a depth taken from the shoreline down into the height
         * field is a distance in that field, and multiplying it by a measured range would make the
         * same 130 m a different depth on every seed. It was 0.015 of the land's relief, which
         * came to 0.0088 of the field on the worlds measured against this one's 0.0081.
         */
        val shelfBreak: Float = scale.fieldShareOfMetres(SHELF_BREAK_METRES)

        /** [ErosionConfig.deltaFreeboardMetres] as a share of the land's relief. */
        val deltaFreeboard: Float = scale.reliefShareOfMetres(erosion.deltaFreeboardMetres)

        /** [ErosionConfig.deltaReachKm] as a whole number of cells of this grid. */
        val deltaReachCells: Int = config.wholeCellsFor(erosion.deltaReachKm)

        /** [ErosionConfig.outletReachKm] as a whole number of cells of this grid. */
        val outletReachCells: Int = config.wholeCellsFor(erosion.outletReachKm)

        /**
         * How long a step to each neighbour is on the ground, in cell widths: every slope this
         * stage takes to a neighbour divides by one of these, and every channel length it adds up
         * is a sum of them. The unit stays the cell width, so a slope along a row is what the
         * coefficients above were calibrated on; a step down a column is a row's height of it.
         */
        val groundSteps: GroundSteps = config.groundSteps
    }

    /**
     * The fall of a freshly cut breach, in metres per kilometre of channel.
     *
     * A notch has to slope, or the D8 step out of the basin has nowhere to go and the next round's
     * fill turns the whole channel back into part of the lake. It does not have to slope by much,
     * and it must not slope by more at one grid than at another, which is what a gradient gets:
     * two and a half millimetres per kilometre is ten times the epsilon the fill itself uses at
     * 512 cells, enough to give every cell along the breach a strictly lower neighbour, and over
     * the longest breach the map allows it is a rounding error against the depth of the water it
     * is letting out.
     */
    private const val NOTCH_FALL_METRES_PER_KM = 0.0025f

    /**
     * How many times the outlets are cut again on the finished surface, once the spoil has been
     * laid on the rock and the last relaxation has run.
     *
     * One, because the spoil is laid once: this is the round of cutting the deposition never got,
     * not a licence to keep going until nothing stands anywhere. Measured at three instead, on
     * seeds 7, 42, 1234 and 718106 at 512 with the ice off, it takes seed 42 from four lakes to
     * none at all and seed 7 from five to one — a world with no standing water outside the glaciated
     * north is as wrong as one paved with it.
     */
    private const val CLOSING_BREACHES = 1

    /**
     * The share of the rounds given over to the sea coming back up. The rest are spent at the
     * lowstand.
     *
     * Earth's sea level spent most of the last glacial cycle well below where it is now and came
     * back up in the last ten thousand years of it, so the caricature is: cut for most of the run
     * at the low stand, then raise the base level to today over the final rounds. A quarter of
     * twelve is three, which is enough for the deltas to be built at the level the map is drawn at
     * — a delta laid at the lowstand is under water by the time anyone sees it — and few enough
     * that the valleys still get the nine rounds of feedback that make them valleys.
     *
     * Doing it as a ramp rather than as one step is not decoration either. A drowned lower valley
     * that spends three rounds with the sea partway up it collects some of the sediment coming
     * down, which is what a real estuary does with its own catchment's load; a single jump would
     * leave every ria scoured clean.
     */
    private const val TRANSGRESSION_SHARE = 0.25f

    /**
     * How far below today's shoreline the sea stands for [round], as a fraction of the land's
     * relief — the one thing the sea-level history changes about the hydraulic rounds.
     *
     * Zero for every round when [com.cartogenesis.worldgen.model.SeaConfig.lowstandMetres] is zero, and
     * zero for the final round always, so the world the map is cut from is a world whose last act
     * was at the present sea level.
     */
    private fun standBelowToday(config: WorldGenConfig, round: Int): Float {
        val lowstand = config.scale.fieldShareOfMetres(config.sea.lowstandMetres)
        val rounds = config.erosion.hydraulicRounds
        if (lowstand <= 0f || rounds <= 1) return 0f
        val rising = (rounds * TRANSGRESSION_SHARE).toInt().coerceAtLeast(1)
        val held = rounds - rising
        if (round < held) return lowstand
        return lowstand * (rounds - 1 - round).toFloat() / rising.toFloat()
    }

    /**
     * The weather the rounds cut with: what each land cell contributes to the water below it, and
     * what is growing on it to hold the soil down.
     *
     * Both fields are one entry per cell, row-major, and both are zero at sea — nothing off the
     * land routes anywhere or resists anything.
     */
    internal class Weather(
        /**
         * What each land cell contributes to the water below it, in millimetres a year and not yet
         * normalised: annual rainfall, held at or above the floor in [provisionalWeather].
         *
         * The stage never routes this directly. [normaliseOverLand] divides it by its own mean
         * over the land each round routes on, and the quotient is what weights the accumulation.
         */
        val rainfallMm: FloatArray,
        /** Plant cover, 0 for bare ground and 1 for closed canopy. See [VEGETATION_SHIELDING]. */
        val vegetationDensity: FloatArray
    )

    /**
     * Today's rain and today's plant cover over the terrain as the thermal sweeps left it, for the
     * hydraulic rounds to cut with.
     *
     * The circle this breaks is that the climate wants a finished terrain and the erosion wants a
     * climate. It is broken the same way [GlaciationStage]'s provisional snow balance breaks it:
     * take the sea the first round will take, solve the ocean without its gyres — the expensive
     * half, and the one the rainfall is least sensitive to — and run the real climate machinery
     * over that. Nothing computed here reaches the saved world; it is read once, by the rounds.
     *
     * No glacial cooling. These are the valleys today's rivers cut, and today's rivers run under
     * today's sky; the ice has its own stage and its own colder world.
     */
    internal fun provisionalWeather(
        config: WorldGenConfig,
        terrain: FloatField,
        provisionalSeaLevel: Float,
        /** Which round is about to cut with this, so the march sees the sea that round sees. */
        round: Int = 0
    ): Weather {
        val cellCount = config.width * config.height
        val cut = SeaLevelStage.percentileCut(
            terrain, provisionalSeaLevel, config.scale, standBelowToday(config, round)
        )
        val rainfall = FloatArray(cellCount) { Runoff.FLOOR_MM }
        if (cut.landCellCount == 0) return Weather(rainfall, FloatArray(cellCount))

        val climate =
            ClimateStage.generateWithSeasonalMm(
                config, cut, OceanStage.withoutCurrents(config, cut)
            ).result
        val annualMm = climate.precipitationMm.data

        // [Runoff.annualWeightMm] and its floor, which is the one every stage in the pipeline
        // reads: see that file for why there is a floor at all and for why this stage divides the
        // weight by its own land's mean where `ChannelInitiation` divides it by Earth's.
        //
        // Every cell, and the sea's cells too. The provisional march's shoreline is not the
        // shoreline of every round: the sea stands lower early on and rises up the valleys as the
        // rounds close, so ground that is under the march's water is land the later rounds route
        // over. Left at zero, that ground would contribute nothing to the water below it — a hole
        // in the discharge field exactly where a river mouth is — until the midpoint march caught
        // up with it. The floor is the honest figure for a cell the march has no rainfall for, and
        // it is the same floor an arid upland gets.
        for (cell in 0 until cellCount) {
            rainfall[cell] = Runoff.annualWeightMm(annualMm[cell])
        }

        // Zero everywhere when the vegetation section is switched off, because that is the field
        // the climate stage hands back then — and a density of zero is a shielding factor of one,
        // so switching the cover off leaves the incision exactly as bare rock's.
        return Weather(rainfall, climate.vegetationDensity.data)
    }

    /**
     * Turns a plant cover into the erodibility factor the cut spends, into [erodibility].
     *
     * `(1 - VEGETATION_SHIELDING * density)` over its own mean across [isLand], so the land's mean
     * erodibility is exactly 1 however much of the world is wooded and only the contrast between
     * one cell and another is left. See [VEGETATION_SHIELDING] for why it has to be relative.
     *
     * Taken again every round beside [normaliseOverLand], and for the same reason: the shoreline
     * moves, and a mean taken over a coastline that no longer exists is not the mean of the land
     * this round is cutting.
     *
     * With the cover off the density is zero everywhere, so every factor is 1 and the mean is 1
     * and the quotient is exactly the 1f that multiplied nothing before there was a cover at all.
     */
    private fun shieldingOverLand(
        density: FloatArray,
        isLand: BooleanArray,
        landCellCount: Int,
        erodibility: FloatArray
    ) {
        var summed = 0.0
        for (cell in density.indices) {
            if (isLand[cell]) summed += (1f - VEGETATION_SHIELDING * density[cell]).toDouble()
        }
        val mean = (summed / landCellCount).toFloat()
        if (mean <= 0f) {
            erodibility.fill(1f)
            return
        }
        for (cell in density.indices) {
            erodibility[cell] = (1f - VEGETATION_SHIELDING * density[cell]) / mean
        }
    }

    /**
     * Divides a rainfall field by its own mean over [isLand], into [weights].
     *
     * This is the whole of what makes the stage's arithmetic keep working, and it must not be
     * simplified away. The accumulation sums these weights over a catchment and the stage divides
     * that sum by the land's cell count to get a share — in [incise] and in the transport capacity
     * both. With a mean of exactly 1 the land's total weight *is* its cell count, so both
     * divisions go on meaning "this cell's share of the land's water" and
     * [Rates.incisionCoefficient] keeps the calibration it was derived with. Without it the
     * divisor would have to become the summed rainfall in two places at once, and `E = K A^m S^n`
     * would be spent against a differently scaled A than the one it was fitted to.
     *
     * Taken again for **every routing pass**, over the mask that pass actually routes on rather
     * than over the march's, because the shoreline moves as the land wears down and the sea rises
     * up the valleys — and because the closing breach and the post-cut outlet pass route over a
     * spoil-laid surface with a shoreline of their own. A mean taken once would be the mean of a
     * coastline that no longer exists, and the land the pass routes on would carry a mean a little
     * off 1, which is the one thing the divisor below cannot tolerate.
     *
     * Returns the summed weight over the mask, which is the land's cell count when the mean is
     * exactly 1 — the passes print it, so the invariant is visible rather than assumed.
     *
     * What the scheme is, said plainly: **relative climatic forcing**. It redistributes the
     * world's water across the land and cannot change how much there is, so a world made uniformly
     * wetter or drier erodes exactly as it did — the division cancels it. That is deliberate,
     * since the incision coefficient was fitted at one total and nothing here re-fits it, but it
     * does mean this stage answers where the rain falls and not how much. And the weight is
     * rainfall standing in for runoff: what actually reaches a channel is rainfall less what the
     * plants breathe out and the ground takes in, delivered in floods rather than evenly, and none
     * of those three is modelled here.
     *
     * With the feed off the field is all ones, whose mean is one, so every weight comes back as
     * exactly the 1f the accumulation used to be handed and the old world is reproduced to the bit.
     *
     * The field handed in is [Runoff.annualWeightMm] per cell. That file carries the floor under
     * it and the other divisor the same weight is taken against — `ChannelInitiation` divides it
     * by Earth's land mean, for reasons that are the mirror image of the ones above.
     */
    /**
     * What a routing pass hands its accumulation, summed over the land it routes on, and that
     * land's cell count: the figure the weight guard holds to one per cell. Taken from the arrays
     * the accumulation is given rather than from [normaliseOverLand]'s own return, which is that
     * function's arithmetic read back and could not differ from one; this can, if a pass
     * normalises over one mask and routes over another. Only called when a guard is listening.
     */
    private fun reportRoutedWeight(
        report: (String, Double, Int) -> Unit,
        pass: String,
        weights: FloatArray,
        routedLand: BooleanArray
    ) {
        var summed = 0.0
        var landCells = 0
        for (cell in weights.indices) {
            if (!routedLand[cell]) continue
            summed += weights[cell].toDouble()
            landCells++
        }
        report(pass, summed, landCells)
    }

    private fun normaliseOverLand(
        rainfallMm: FloatArray,
        isLand: BooleanArray,
        landCellCount: Int,
        weights: FloatArray
    ): Double {
        var summed = 0.0
        for (cell in rainfallMm.indices) if (isLand[cell]) summed += rainfallMm[cell].toDouble()
        val mean = (summed / landCellCount).toFloat()
        if (mean <= 0f) {
            weights.fill(0f)
            return 0.0
        }
        var weighted = 0.0
        for (cell in rainfallMm.indices) {
            weights[cell] = rainfallMm[cell] / mean
            if (isLand[cell]) weighted += weights[cell].toDouble()
        }
        return weighted
    }

    /**
     * @param provisionalSeaLevel the fraction of the world that will end up under water. Erosion
     *   runs before the sea level is chosen, but water needs somewhere to go, so it works to the
     *   level the sea *will* take.
     * @param onRound handed the mass budget for each round as it closes. Diagnostics only; nothing
     *   here reads it back, so it cannot affect the world.
     * @param log filled in with which mechanism laid sediment on which cell, if a caller wants to
     *   know. Diagnostics only, on the same terms as [onRound]. It exists because "which of the
     *   four things that lay sediment made that shape" cannot be answered from the finished map.
     * @param relax a few thermal sweeps, run after every round.
     *
     *   Not decoration, and not merely for looks. Incision on its own cuts a slot one cell wide,
     *   whose walls stand at whatever angle the arithmetic leaves them -- and a slot one cell wide
     *   is twice as steep on a grid twice as fine, so the world stops being the same world at
     *   different resolutions. Letting the walls fail between rounds caps them at the critical
     *   slope, which is a property of the map rather than the grid. It is also what actually
     *   happens: valley sides are worn back by mass wasting as fast as the river cuts down, which
     *   is why a valley is a V and not a slot.
     */
    /**
     * @param receiverClamp whether a cell standing at or below the level it grades to is left
     *   alone rather than moved toward it, the half of the receiver bound the implicit update does
     *   not carry by construction. Only ever false in the guards that show what happens without
     *   it; see [incise].
     */
    suspend fun apply(
        config: WorldGenConfig,
        height: FloatField,
        provisionalSeaLevel: Float,
        /**
         * How fast the rock is rising under each cell, in millimetres a year — `PlateStage`'s own
         * field, or null where a caller wants the rounds without the tectonics (which is the
         * control the uplift guards are shown to fail against).
         */
        upliftRateMmPerYear: FloatField? = null,
        onRound: ((RoundMass) -> Unit)? = null,
        log: DepositionLog? = null,
        receiverClamp: Boolean = true,
        /**
         * Handed every routing pass's name, the weights that pass's accumulation is given summed
         * over the land it routes on, and how many land cells that is (see [reportRoutedWeight]).
         * Diagnostics only, on the same terms as [onRound]: the two agree to the last few bits when
         * the normalisation is doing its job, and a guard reads them back rather than taking the
         * invariant on trust.
         */
        weightSums: ((String, Double, Int) -> Unit)? = null,
        /** Whether [incise] takes the cover's factor. Only ever false in the cover's own guard. */
        shieldCut: Boolean = true,
        /** Handed every cell's cut and every round's result; see [IncisionWatch]. */
        incisionWatch: IncisionWatch? = null,
        relax: suspend (FloatField) -> FloatField
    ): FloatField {
        val erosion = config.erosion
        if (erosion.hydraulicRounds <= 0 || erosion.bedrockErodibilityPerYear <= 0f) return height
        val rates = Rates(config)

        val cellsAcross = config.width
        val cellsDown = config.height
        var working = height.copy()

        // The sky the rounds work under, solved on the terrain the thermal sweeps left and taken
        // again halfway through.
        //
        // Twice and not once, on measurement. A single march at the top was the cheaper design and
        // it was tried: the rainfall the last round would have cut with differs from the rainfall
        // the first round cut with by a third of the land's mean, because twelve rounds take
        // enough off a range to change how much lift it forces out of the wind crossing it. A
        // third is too much to hold fixed. Twice and not per round because a march is not free and
        // the drift is not where the value is — the rain shadow's *place* is set by the coastlines
        // and the belts, which no round moves. See docs/DESIGN_LEDGER.md, S3, for the drift
        // figures and the cost.
        //
        // Switched off, the weights are all ones and the cover is nothing, which is the world this
        // stage cut before it could see the weather, to the last bit.
        val refreshAtRound =
            if (erosion.climateFeed && erosion.hydraulicRounds > 1) erosion.hydraulicRounds / 2
            else -1
        val opening =
            if (erosion.climateFeed) provisionalWeather(config, working, provisionalSeaLevel)
            else Weather(FloatArray(cellsAcross * cellsDown) { 1f }, FloatArray(cellsAcross * cellsDown))
        // Held as the arrays rather than as the pair, because the accumulation reads one of them
        // once per land cell per round and a field load there is not free.
        var rainfallMm = opening.rainfallMm
        var vegetationDensity = opening.vegetationDensity
        // Filled at the top of every round by [normaliseOverLand] and [shieldingOverLand], once
        // that round's shoreline is known. Allocated here so the rounds share them.
        val runoff = FloatArray(cellsAcross * cellsDown)
        val erodibility = FloatArray(cellsAcross * cellsDown)
        require(listOf(erosion.subGridTransport, erosion.subGridTransportAcrossTheFall, erosion.subGridTransportUndrainedShare).count { it } <= 1) {
            "at most one form of the sub-grid transport may be on"
        }
        // What the round's routing leaves undrained by each cell's receiver, for the sub-grid
        // transport's undrained-share form; see [ErosionConfig.subGridTransportUndrainedShare].
        val undrainedShare = if (erosion.subGridTransportUndrainedShare) FloatArray(cellsAcross * cellsDown) else null
        // The closing breach and the post-cut outlet pass route over surfaces the rounds never
        // routed over, each with its own shoreline, so each takes its own normalisation.
        val spoilRunoff = FloatArray(cellsAcross * cellsDown)

        // The solid earth's two answers to what the water is doing, both of them off by default
        // and both switched by their own setting so a guard can measure the world without them.
        //
        // The uplift is the rock still rising under an active belt, spent per round over the years
        // `WorldScale` says a round stands for; the flexure is the plate bending under what that
        // uplift stacks on it and springing back under what the rivers carry away. They are here
        // rather than in the plate stage because neither is a thing that happens once: a range
        // that is being pushed up while it is being cut down reaches a height where the two
        // balance, and that balance is the whole of what S2 exists to model. See docs/DESIGN_LEDGER.md,
        // S2, and [Isostasy].
        val tectonics = config.tectonics
        val scale = config.scale
        val metresPerFieldUnit = scale.reliefSpanMetres
        val upliftMetresPerRoundPerMm =
            (scale.yearsPerHydraulicRound / MILLIMETRES_PER_METRE).toFloat()
        val elevationLimit =
            PlateStage.Limit(tectonics.elevationLimitKneeMetres, scale.highestLandMetres)
        val flexure =
            if (config.isostasy.enabled && config.isostasy.flexure) Isostasy.Flexure(config) else null
        // What the field looked like before any of this, and the bend that has been applied to it
        // so far, so that each round asks the plate about the *whole* load it is carrying rather
        // than about that round's instalment. Replacing the bend rather than adding to it is what
        // makes the pass idempotent: run twice on an unchanged load, the second run changes
        // nothing.
        val reference = if (flexure != null) working.data.copyOf() else FloatArray(0)
        val deflectionMetres = if (flexure != null) FloatArray(cellsAcross * cellsDown) else FloatArray(0)
        val loadPascals = if (flexure != null) FloatArray(cellsAcross * cellsDown) else FloatArray(0)
        // What the tectonics have added so far, kept apart from what the rivers have moved. The
        // uplift is not a load: a belt rises because its crust is thickening from below, and the
        // topography that thickening supports is Airy-compensated the moment it appears — exactly
        // as the stamped profiles it continues are. Handing it to the flexure as well would
        // compensate it twice and leave a range holding a seventh of what pushed it up. What the
        // plate does answer is everything the water has done since.
        val upliftedMetres =
            if (flexure != null && upliftRateMmPerYear != null) FloatArray(cellsAcross * cellsDown)
            else FloatArray(0)
        val loadDensity = config.isostasy.continentalCrustDensity
        val gravity = config.isostasy.gravity

        val carryingSediment = erosion.deposition
        val reachCells = rates.deltaReachCells.coerceAtLeast(0)
        // Sediment in transit, per cell, handed on as the walk works its way downstream.
        //
        // In double, and that is not fussiness. A trunk near the coast carries the yield of its
        // whole catchment while the cells feeding it hand over a ten-thousandth of that each; in
        // float those additions land below the accumulator's last bit and vanish, and the mass
        // budget went three percent short at round three before this was widened.
        val load = if (carryingSediment) DoubleArray(cellsAcross * cellsDown) else DoubleArray(0)
        // Sediment at rest, per cell, accumulated over every round and laid on the terrain once,
        // at the end.
        //
        // Keeping it off the terrain while the rounds run is deliberate, and it is the difference
        // between a stage that behaves and one that does not. Incision is self-pinning: a channel
        // that cuts gathers more water and cuts deeper, so its position is stable against a
        // difference in the last bit of the input. Aggradation is the opposite -- a cell that
        // rises can send the next round's steepest-descent step somewhere else -- and feeding the
        // spoil straight back into the routing surface made the whole pipeline a lottery. On the
        // GPU-versus-CPU guard the worst cell swung between 0.006 and 0.034 of the elevation range
        // across parameter values that were otherwise indistinguishable, and downstream a people
        // on seed 42 grew from 29% of the habitable world to 49% for no reason anyone could name.
        //
        // So the water routes over the rock it cut, always, and the spoil is laid on top
        // afterwards. What that gives up is the feedback where a river is steered by its own
        // deposits -- real at the scale of a floodplain, mostly numerical at the scale of one cell.
        // What it keeps is every channel exactly where the rock put it.
        val sediment = if (carryingSediment) FloatArray(cellsAcross * cellsDown) else FloatArray(0)
        // What the incision took off each cell this round, handed from the ordered pass that cuts
        // to the walk that carries the spoil away. See the two passes below.
        val incisedAt = if (carryingSediment) DoubleArray(cellsAcross * cellsDown) else DoubleArray(0)
        // The pit census, when a caller asked for the round tally. One counter per mechanism, and
        // the mask of cells that were already pits when the round opened — see [census].
        val pits = if (onRound != null) IntArray(PitStage.COUNT) else IntArray(0)
        val openingPit = if (onRound != null) BooleanArray(cellsAcross * cellsDown) else BooleanArray(0)
        // Scratch for the little flood fills that build a delta. One stamp per mouth, so a cell
        // cannot be visited twice; the ids only ever increase, so the array never needs clearing.
        //
        // Two sets of them, because there are two ways to grow a fan and one of them is the
        // control the other is measured against: the breadth-first walk with its Chebyshev step
        // count, and the best-first walk over Euclidean distance bent by depth. Only one is ever
        // allocated. See `ErosionConfig.deltaOutline`.
        // The breadth-first walk is still needed when the outline is switched off, and also when
        // the *lobe* is switched off — `deltaLobe = false` is the flat slab the stage built before
        // there were lobes, kept exactly as it was because `DeltaMouthTest` measures against it.
        val squareFans = carryingSediment && (!erosion.deltaOutline || !erosion.deltaLobe)
        val stamp = if (squareFans) IntArray(cellsAcross * cellsDown) else IntArray(0)
        val fanCapacity = (2 * reachCells + 1) * (2 * reachCells + 1)
        val fanQueue = IntArray(if (squareFans) fanCapacity else 0)
        val fanDistance = IntArray(if (squareFans) fanCapacity else 0)
        val scratch =
            if (carryingSediment && erosion.deltaOutline) {
                DeltaFan.Scratch(cellsAcross * cellsDown, reachCells, config.cellHeightInCellWidths)
            } else {
                null
            }
        var mouthId = 0

        // Lays the accumulated spoil on the rock. Called once, on the way out, and always before
        // the last relaxation -- partly so a fresh delta gets the same slope-limiting every other
        // landform gets, and partly because the accelerator seam replaces that call wholesale when
        // a stored terrain is being replayed. Anything added after it would be added to a world
        // that had just been overwritten by the snapshot, and `TerrainSnapshotTest` says so.
        fun settle() {
            if (!carryingSediment) return
            for (cell in sediment.indices) working.data[cell] += sediment[cell]
            sediment.fill(0f)
        }

        repeat(erosion.hydraulicRounds) { round ->
            // A round routes the water over the whole map and then cuts with it, and at export
            // sizes that is seconds of work with nothing in the middle of it that could notice a
            // reader pressing Stop. So the question is asked here, where a round has just closed
            // and the field is a whole terrain rather than half of one, and the thread is handed
            // back — on a browser, where that is the only way the press ever arrives at all.
            currentCoroutineContext().ensureActive()
            standAside()

            // Halfway down, the sky is asked again — against the ground as the rounds have left
            // it and the sea as this round will take it, which is the same pair the march at the
            // top was given.
            if (round == refreshAtRound) {
                val refreshed = provisionalWeather(config, working, provisionalSeaLevel, round)
                rainfallMm = refreshed.rainfallMm
                vegetationDensity = refreshed.vegetationDensity
            }

            // The rock rises first, before the water is routed over it: a round is a span of time,
            // and what the rivers of that span work on is the ground the tectonics of that span
            // have already lifted.
            //
            // Rock uplift, not surface uplift — England and Molnar's distinction, and the flexure
            // below is what turns the one into the other. A cell that gains a metre of rock gains
            // rather less than a metre of altitude, because the plate it is stacked on bends under
            // the weight; what is left over is the surface uplift, and in a belt at steady state
            // the rivers take that away too.
            var upliftedThisRound = 0.0
            if (upliftRateMmPerYear != null) {
                val rate = upliftRateMmPerYear.data
                val surface = working.data
                for (cell in surface.indices) {
                    if (rate[cell] <= 0f) continue
                    val altitude = scale.altitudeAtField(surface[cell])
                    val metres = rate[cell] * upliftMetresPerRoundPerMm *
                        elevationLimit.upliftShareAt(altitude)
                    if (metres <= 0f) continue
                    surface[cell] += metres / metresPerFieldUnit
                    if (upliftedMetres.isNotEmpty()) upliftedMetres[cell] += metres
                    upliftedThisRound += metres.toDouble()
                }
            }

            // And the plate answers — at the top of the round, to everything the rounds before it
            // did, rather than at the bottom to what this one just did.
            //
            // The load is the same load either way and the arithmetic is the same arithmetic; what
            // changes is what the world ends on. A flexure is a filter over the whole field, so its
            // answer is a broad warp, and a broad warp laid on a landscape *after* the water has
            // finished routing over it is a landscape whose rivers no longer run downhill: the
            // shallow basins the bend makes have no outlet cut through them and the lake stage
            // fills them. Measured on the five standard worlds at 512, the bend spent at the tail
            // of the last round left 0.9 of a percentage point of extra land under lakes. Spent at
            // the head instead, every bend the plate makes has a round of rivers after it to
            // adjust to it, which is also the order the Earth does it in: a plate takes ten
            // thousand years to answer a load and a river answers the plate as it moves.
            //
            // The first round's load is nothing, so its bend is nothing and no world is disturbed
            // by having one.
            var deflectedThisRound = 0.0
            if (flexure != null) {
                val surface = working.data
                val uplifted = upliftedMetres
                for (cell in surface.indices) {
                    // Everything the rounds have done to the column, in metres of rock: what the
                    // rivers cut away, less what the tectonics stacked on (which arrives
                    // compensated), plus the spoil the walk is still holding off the terrain. That
                    // last term matters more than it looks — the sediment is not laid on the rock
                    // until the final round, so without it the plate would not feel a grain of the
                    // debris a range sheds into its foreland until the world was finished, and a
                    // foreland basin is that debris.
                    val columnChangeMetres =
                        (surface[cell] - reference[cell]) * metresPerFieldUnit +
                            deflectionMetres[cell] -
                            (if (uplifted.isEmpty()) 0f else uplifted[cell]) +
                            (if (sediment.isEmpty()) 0f else sediment[cell] * metresPerFieldUnit)
                    loadPascals[cell] = columnChangeMetres * loadDensity * gravity
                }
                flexure.deflectionMetres(loadPascals, loadPascals)
                for (cell in surface.indices) {
                    val bend = loadPascals[cell]
                    surface[cell] += (deflectionMetres[cell] - bend) / metresPerFieldUnit
                    deflectionMetres[cell] = bend
                    // Summed as a magnitude, because the signed sum is zero by construction: the
                    // filter drops the zero-frequency term, so a bend down somewhere is a bend up
                    // somewhere else and the world's mean altitude does not move. That is also what
                    // keeps the round's mass budget closing across this pass.
                    deflectedThisRound += if (bend < 0f) -bend.toDouble() else bend.toDouble()
                }
            }

            log?.round = round
            // The shoreline moves as the land wears down, so it is found again each round rather
            // than fixed once. This is the same percentile the sea level stage will use — taken,
            // for all but the last few rounds, at the stand the sea was actually at while these
            // valleys were being cut. See [standBelowToday].
            val sea = SeaLevelStage.percentileCut(
                working, provisionalSeaLevel, config.scale, standBelowToday(config, round)
            )
            if (sea.landCellCount == 0) {
                settle()
                return working
            }
            // This round's shoreline is now known, so both fields are taken against this round's
            // land. See [normaliseOverLand] for why that is where the mean has to come from, and
            // [VEGETATION_SHIELDING] for why the cover is spent relatively too.
            normaliseOverLand(rainfallMm, sea.isLand, sea.landCellCount, runoff)
            if (shieldCut) {
                shieldingOverLand(vegetationDensity, sea.isLand, sea.landCellCount, erodibility)
            } else {
                erodibility.fill(1f)
            }
            weightSums?.let { reportRoutedWeight(it, "round $round", runoff, sea.isLand) }

            val filled = FlowRouting.fillDepressions(
                cellsAcross, cellsDown, sea.isLand, sea.relativeElevation
            )
            val directions = FlowRouting.flowDirections(
                cellsAcross, cellsDown, sea.isLand, sea.relativeElevation, filled,
                config.seed, config.cellHeightInCellWidths, config.facetRouting, config.flatPotential,
                undrainedShare
            )
            // Discharge and not catchment: each cell hands on what falls on it, so what arrives
            // at a channel is `Q = P * A` and the accumulation is a rainfall-weighted cell count
            // whose land mean is one. The two read the same everywhere it rains evenly.
            val area = FlowRouting.accumulate(
                cellsAcross, cellsDown, sea.isLand, filled, directions, sea.landCellCount
            ) { cell -> runoff[cell] }
            val order = FlowRouting.drainageOrder(cellsAcross, cellsDown, sea.isLand, directions, sea.landCellCount)

            val landCells = sea.landCellCount.toFloat()
            val isLand = sea.isLand
            val relative = sea.relativeElevation.data
            val ground = filled.data
            val surfaceOf = working.data

            // `relative` is elevation measured from the shoreline in units of the land's own half
            // of the ruler, so converting between the two needs that half. Everything below that is
            // a height has to say which of the two it is in; see [settled].
            //
            // Declared and not measured since S2. The height field carries absolute altitudes now,
            // so the land's half of it is `highestLandMetres / reliefSpanMetres` on every seed and
            // at every grid — where before it was the distance from the shoreline to whatever the
            // tallest cell happened to be, which is 0.25 of the field on one world and 0.39 on the
            // same world at a finer grid, and is what made a rate written in one unit and spent in
            // the other depend on the cell size.
            val landRange = config.scale.landHalfOfField.coerceAtLeast(1e-6f)
            val toRelative = 1f / landRange

            // Ground as the walk leaves it: the pre-round elevation plus everything this round has
            // already added or taken away. Deposition is judged against this rather than against
            // the stale field, or a cell could be raised past the neighbour that feeds it.
            //
            // In **shoreline-relative units**, because that is what it is seeded from and what
            // `breach` has always subtracted from it, and every amount added to it below is now
            // converted into them. They were not: the spoil and the incision were added in height
            // units while the seed was relative, so the margin `headroom` measures was a relative
            // number spent as a height one, and an alluvial dam could stand `1 / landRange` times
            // higher than the no-uphill rule allows — about four times, on the worlds measured.
            // The incision side had the same muddle and was closed first; this is the
            // deposition half of it. See docs/DESIGN_LEDGER.md, H5b and E6.
            val settled = if (carryingSediment) relative.copyOf() else relative
            if (carryingSediment) {
                load.fill(0.0)
                incisedAt.fill(0.0)
                // The no-uphill rule is judged against the finished surface, spoil included, or
                // the rounds would each be allowed the same margin over and over.
                for (cell in settled.indices) settled[cell] += sediment[cell] * toRelative
            }

            // Raw height a delta cell is built up to.
            val deltaTop = sea.shorelineHeight + rates.deltaFreeboard * landRange
            // Where the rim of a lobe stands, and how deep the water has to be before the lobe
            // stops wanting to cross it. See [SHELF_BREAK_METRES].
            val rimTop = sea.shorelineHeight + (deltaTop - sea.shorelineHeight) * LOBE_RIM
            val shelfDepth = rates.shelfBreak.coerceAtLeast(1e-9f)

            var incised = 0.0
            var deposited = 0.0
            var lost = 0.0
            val startingMass =
                if (onRound != null) totalMass(surfaceOf) + totalMass(sediment) else 0.0

            // The lips of the basins the fill just raised, cut down before the water is routed
            // over them.
            //
            // Before, and not during, because the outlet has to be cut as a whole — a lip can only
            // fall if the ground between it and the open valley below falls with it — and the walk
            // that follows runs one cell at a time from the sources down. Running it first also
            // keeps the two honest about each other: the notch lowers the round's routing surface
            // along with the terrain, so the walk sees the ground as the notch left it and takes
            // its own bite out of what is actually there. Whatever the notch removes is handed to
            // the cell's sediment load, so the walk carries it away like any other spoil and the
            // budget closes in the round it was opened.
            if (onRound != null) {
                openingPit.fill(false)
                census(
                    pits, PitStage.OPENING, cellsAcross, isLand, directions, area.data, landCells, surfaceOf,
                    null, openingPit
                )
            }

            val notch = if (erosion.outletIncision || onRound != null) {
                FlowRouting.spillways(
                    cellsAcross, cellsDown, isLand, relative, ground, directions, rates.pondDepth
                )
            } else {
                null
            }
            var openingFillDepthMetres = 0.0
            if (onRound != null) {
                var summed = 0.0
                for (cell in ground.indices) {
                    val standing = ground[cell] - relative[cell]
                    if (isLand[cell] && standing > rates.pondDepth) summed += standing.toDouble()
                }
                openingFillDepthMetres = summed * config.scale.highestLandMetres / landCells
            }
            var notched = 0.0
            var notchCells = 0
            if (notch != null && erosion.outletIncision) {
                val cut = breach(
                    erosion, rates, cellsAcross, notch, isLand, relative, ground, directions, area.data, landCells,
                    landRange, surfaceOf,
                    settled = if (carryingSediment) settled else null,
                    load = if (carryingSediment) load else null
                )
                notched = cut.moved
                notchCells = cut.cells
                incised += cut.moved
            }

            if (onRound != null) {
                census(
                    pits, PitStage.NOTCH, cellsAcross, isLand, directions, area.data, landCells, surfaceOf,
                    null, openingPit
                )
            }

            // The incision: Braun and Willett's implicit update, from the outlets upstream. See
            // [incise] for the update, its bounds and what it does and does not promise.
            //
            // It replaces the explicit cut and nothing else in the round: the uplift and the
            // flexure above run first, the routing and the notch before it, and the deposition
            // walk below is fed what it took through [incisedAt], sources first, as it always was.
            incise(
                rates, cellsAcross, order, directions, isLand, ground, relative, area.data, landCells, landRange,
                sea.shorelineHeight, erodibility, surfaceOf,
                incisedAt = if (carryingSediment) incisedAt else null,
                onlyAboveBase = receiverClamp,
                watch = incisionWatch,
                round = round
            )
            incisionWatch?.incised(
                round, isLand, directions, ground, relative, area.data, landCells, landRange,
                sea.shorelineHeight, surfaceOf
            )

            if (onRound != null) {
                census(
                    pits, PitStage.INCISION, cellsAcross, isLand, directions, area.data, landCells, surfaceOf,
                    null, openingPit
                )
            }

            // Sources first, so every cell has already received whatever its tributaries were
            // carrying by the time it is asked what to do with it.
            for (rank in order.indices) {
                val cell = order[rank]
                val receiver = directions[cell]
                var carried = if (carryingSediment) load[cell] else 0.0

                if (receiver < 0) {
                    // Water runs off the polar edge, and whatever it carries goes with it.
                    lost += carried
                    continue
                }

                // Both terms are held against the map rather than the grid, so a finer grid cuts
                // the same valleys rather than deeper ones: area as a share of all land, slope as
                // a rise over a fraction of the map's width.
                // The rock is already cut, by the ordered pass above; what is left for this walk is
                // where the spoil goes.
                if (!carryingSediment) continue

                val toSea = !isLand[receiver]
                val drop = fallToReceiver(ground, isLand, cell, receiver)

                // Under standing water there is no channel to aggrade: the river here *is* the
                // lake, its gradient is the epsilon the flood-fill left, and anything it was
                // carrying was dropped at the inflow. Skipping deposition inside a basin is what
                // keeps lakes from silting up into meadows.
                //
                // Only deposition, though. An earlier version skipped the incision too, and that
                // was a cliff: a cell a hair either side of this depth either cut or did not, so a
                // difference in the last bit of the input -- which is exactly what the GPU's
                // thermal pass produces -- could change a cell by the full depth of its channel.
                // `GpuErosionTest` found it, at five cells in a million.
                val ponded = ground[cell] - relative[cell] > rates.pondDepth
                run {
                    val distance = rates.groundSteps.between(cell, receiver, cellsAcross)
                    val slope = if (drop > 0f) drop / distance * cellsAcross else 0f
                    val capacity =
                        (erosion.transportCapacity * sqrt(area.data[cell] / landCells) * slope).toDouble()

                    // What the ordered pass above took off this cell, picked up here so that it
                    // travels downstream with everything the tributaries brought. Tallied and
                    // subtracted from the deposition's own surface at the same point in the walk
                    // the single combined pass used to, so the no-uphill rule below sees exactly
                    // the margins it always saw.
                    val moved = incisedAt[cell]
                    if (moved > 0.0) {
                        // In the relative units [settled] is kept in; see its note.
                        settled[cell] -= (moved * toRelative).toFloat()
                        carried += moved
                        incised += moved
                    }

                    if (!ponded && carried > capacity) {
                        // More than the flow can hold, so some of the surplus settles: a floodplain
                        // where a trunk flattens out, a fan where a range front drops onto the
                        // plain.
                        //
                        // Cutting and settling both, rather than one or the other. Making them
                        // exclusive was tried first and is what a strictly transport-limited model
                        // would do, but a trunk gathers more than it can carry long before it stops
                        // being steep, so every lower channel switched off its incision and the
                        // valleys filled in: `ValleyIncisionTest` fell from 1.90x to 1.00x, which is
                        // to say the water might as well not have run at all.
                        //
                        // The room above is what keeps this from simply erasing a valley. A channel
                        // cell may never stand as high as the cell feeding it -- that is an uphill
                        // river -- and taking only a fraction of that margin per round means the
                        // floor creeps toward grade over the rounds rather than jumping to it.
                        // The slope this river needs in order to carry what it is holding: the
                        // slope at which `capacity` equals `carried`, read straight off the line
                        // above. That is the equilibrium slope of a transport-limited channel, and
                        // it costs no new constant — it is the same expression solved for slope
                        // instead of for capacity.
                        //
                        // Why it is here at all: without it the rule was "a cell may rise until it
                        // is level with the cell that feeds it", whose fixed point is a **flat**.
                        // Twelve rounds of creeping a fraction of the way toward that turned the
                        // lower valleys into planes, and a plane meeting the sea has a level set
                        // that is a straight line — which is what the author's rift mouth was: 32
                        // cells of dead-straight shore in the scene against 16 on the same ground
                        // with no deposition at all. A river does not aggrade to a flat; it
                        // aggrades until it is steep enough to carry its load, and then it stops.
                        val grade = if (erosion.gradedAggradation) {
                            val conveyance =
                                (erosion.transportCapacity * sqrt(area.data[cell] / landCells)).toDouble()
                            // In the same relative units `settled` and `ground` are kept in: the
                            // slope above is a rise per unit of map width, so one cell of it is
                            // that over `w`.
                            if (conveyance > 1e-12) (carried / conveyance / cellsAcross).toFloat() else 0f
                        } else {
                            0f
                        }
                        // `room` comes back in the relative units [settled] is kept in; the load
                        // and the field are heights, so it is converted here and nowhere else.
                        val room =
                            headroom(cellsAcross, cellsDown, cell, drop, directions, settled, grade, rates.groundSteps) *
                                landRange
                        val give = minOf(carried - capacity, room.toDouble()) * erosion.depositionRate
                        if (give > 0.0) {
                            // Tallied from what the field actually took, never from what it was
                            // asked to take: the terrain is float, so a small enough increment
                            // rounds away, and a budget counted on intent would not notice.
                            val moved = raise(sediment, cell, give)
                            settled[cell] += (moved * toRelative).toFloat()
                            carried -= moved
                            deposited += moved
                            log?.record(cell, DepositionLog.FLOODPLAIN, -1, moved)
                        }
                    }
                }

                when {
                    toSea -> {
                        // The river's whole remaining load arrives at once. A share of it settles
                        // in the receiving cells and builds land; the rest disperses offshore and
                        // is gone, which is what the sea does with most of the world's sediment.
                        //
                        // Only for a watercourse big enough to be a river, though. Let every rill
                        // build and the coastline merely creeps outward everywhere at once, which
                        // is a wider continent rather than a delta.
                        val river = area.data[cell] / landCells >= erosion.deltaMinCatchment
                        // Which way the trunk was pointing when it arrived, so the lobe can build
                        // out in front of the river rather than equally in every direction.
                        val outX = shortestX(receiver % cellsAcross - cell % cellsAcross, cellsAcross).toFloat()
                        val outY = (receiver / cellsAcross - cell / cellsAcross).toFloat()
                        val budget = if (river) carried * erosion.deltaShare else 0.0
                        // Shaped only when the lobe is a lobe at all: `deltaLobe = false` is the
                        // flat slab the stage built before there were lobes and stays exactly
                        // that, because `DeltaMouthTest` measures against it.
                        val laid = if (erosion.deltaLobe && erosion.deltaOutline) {
                            val rim = DeltaFan.Rim(
                                apex = receiver,
                                width = cellsAcross,
                                cellHeightInCellWidths = config.cellHeightInCellWidths,
                                reachCells = reachCells.toFloat(),
                                outX = outX,
                                outY = outY,
                                hash = DeltaFan.hash(config.seed, mouthKey(receiver, reachCells, cellsAcross)),
                                grooved = true
                            )
                            rim.pruneGrooves(cellsDown) { candidate -> !isLand[candidate] }
                            growFan(
                                cellsAcross, cellsDown, budget, rim, scratch!!, ++mouthId,
                                surfaceOf, sediment, settled, toRelative,
                                wholeCells = true,
                                log = log,
                                mark = DepositionLog.SEA_LOBE,
                                accepts = { candidate -> !isLand[candidate] },
                                // The cost of building into a cell is the accommodation space it
                                // offers, which is its depth. Held against the shelf break rather
                                // than against the cell beside it, so the same delta bends the same
                                // way whatever the sea floor happens to be doing elsewhere.
                                advance = { candidate ->
                                    val depth = sea.shorelineHeight - (surfaceOf[candidate] + sediment[candidate])
                                    1f + DEPTH_COST *
                                        (if (depth > 0f) depth else 0f) / shelfDepth
                                },
                                // Apex to rim as a fraction of the rim in this cell's own
                                // direction, so the whole edge of the lobe stands at the rim level
                                // however far out that edge happens to be.
                                levelOf = { candidate, reachFraction ->
                                    val level = deltaTop + (rimTop - deltaTop) * reachFraction
                                    if (rim.grooved(rim.columnOffset(candidate), rim.rowOffset(candidate))) {
                                        sea.shorelineHeight +
                                            (level - sea.shorelineHeight) * GROOVE_KEEP
                                    } else {
                                        level
                                    }
                                }
                            )
                        } else {
                            fan(
                                cellsAcross, cellsDown, receiver, budget, reachCells,
                                stamp, ++mouthId, fanQueue, fanDistance, surfaceOf, sediment,
                                settled, toRelative,
                                wholeCells = erosion.deltaLobe,
                                log = log,
                                mark = DepositionLog.SEA_LOBE,
                                accepts = { candidate, stepsFromApex ->
                                    !isLand[candidate] && (
                                        !erosion.deltaLobe ||
                                            stepsFromApex <= lobeReach(
                                                reachCells, receiver, candidate,
                                                outX, outY, cellsAcross
                                            )
                                        )
                                },
                                levelOf = { _, stepsFromApex ->
                                    if (erosion.deltaLobe) {
                                        lobeLevel(deltaTop, sea.shorelineHeight, reachCells, stepsFromApex)
                                    } else {
                                        deltaTop
                                    }
                                }
                            )
                        }
                        deposited += laid
                        lost += carried - laid
                    }

                    !ponded && ground[receiver] - relative[receiver] > rates.pondDepth -> {
                        // A lake inflow. The basin traps a share of the load as a fan built up
                        // toward the water surface, and the rest passes through to the outlet.
                        //
                        // Toward, never to. A fan is stopped twice the pond depth short of the
                        // surface, so however many rounds run and however much sediment arrives,
                        // every cell it touches is still standing water afterwards. A lacustrine
                        // delta shallows a lake; it is not allowed to abolish one, which is what
                        // filling to the brim did — seed 99 lost every lake it had.
                        //
                        // The direction the inflow was travelling, so a lacustrine fan is a cone in
                        // front of its river exactly as a delta is. Without it the fan's own
                        // acceptance rule — "any ponded cell" — takes the whole breadth-first
                        // square, which is where the rafts with right-angle corners came from.
                        val inX = shortestX(receiver % cellsAcross - cell % cellsAcross, cellsAcross).toFloat()
                        val inY = (receiver / cellsAcross - cell / cellsAcross).toFloat()
                        val laid = if (erosion.deltaOutline) {
                            val rim = DeltaFan.Rim(
                                apex = receiver,
                                width = cellsAcross,
                                cellHeightInCellWidths = config.cellHeightInCellWidths,
                                reachCells = reachCells.toFloat(),
                                outX = inX,
                                outY = inY,
                                hash = DeltaFan.hash(config.seed, mouthKey(receiver, reachCells, cellsAcross)),
                                grooved = false
                            )
                            growFan(
                                cellsAcross, cellsDown, carried * erosion.lakeShare, rim, scratch!!, ++mouthId,
                                surfaceOf, sediment, settled, toRelative,
                                wholeCells = false,
                                log = log,
                                mark = DepositionLog.LAKE_FAN,
                                accepts = { candidate ->
                                    isLand[candidate] && ground[candidate] - relative[candidate] > rates.pondDepth
                                },
                                advance = { candidate ->
                                    val depth = (ground[candidate] - relative[candidate]) * landRange
                                    1f + DEPTH_COST *
                                        (if (depth > 0f) depth else 0f) / shelfDepth
                                },
                                levelOf = { candidate, reachFraction ->
                                    val depth = 2f * rates.pondDepth * (1f + LAKE_FAN_SLOPE * reachFraction) *
                                        (0.9f + 0.35f * wobble(candidate))
                                    sea.shorelineHeight + (ground[candidate] - depth) * landRange
                                }
                            )
                        } else {
                            fan(
                                cellsAcross, cellsDown, receiver, carried * erosion.lakeShare, reachCells,
                                stamp, ++mouthId, fanQueue, fanDistance, surfaceOf, sediment,
                                settled, toRelative,
                                wholeCells = false,
                                log = log,
                                mark = DepositionLog.LAKE_FAN,
                                accepts = { candidate, _ ->
                                    isLand[candidate] && ground[candidate] - relative[candidate] > rates.pondDepth
                                },
                                // Deeper the further from the inflow, and uneven cell by cell.
                                //
                                // Laid to one depth below the surface — which is what this was —
                                // every cell of a fan ends at exactly the same height, and a lake
                                // whose floor is a plane has a level set that is a straight line:
                                // the water balance then draws it with a ruler-straight shore.
                                // A real fan slopes away from the river that built it and is
                                // rough, so this one does too. The taper is a fraction of the rim
                                // rather than a charge per cell; see [LAKE_FAN_SLOPE], and
                                // docs/DESIGN_LEDGER.md, E5, for the flat-floored lakes it replaced.
                                levelOf = { candidate, stepsFromApex ->
                                    val depth = 2f * rates.pondDepth *
                                        (1f + LAKE_FAN_SLOPE * stepsFromApex / reachCells.coerceAtLeast(1)) *
                                        (0.9f + 0.35f * wobble(candidate))
                                    sea.shorelineHeight + (ground[candidate] - depth) * landRange
                                }
                            )
                        }
                        deposited += laid
                        load[receiver] += carried - laid
                    }

                    else -> load[receiver] += carried
                }
            }

            val closing = round == erosion.hydraulicRounds - 1
            // Where the spoil went, kept before it stops being a layer of its own and becomes
            // terrain: it is how the mouths below tell fresh ground from old.
            //
            // Empty rather than absent when nothing is being carried, so that the pass below runs
            // either way. `DepositionTest` holds a world with deposition switched off and a world
            // with it running and every rate at zero to be bit-identical, which is the assertion
            // that says the deposition machinery is a layer on top of the erosion rather than part
            // of it — and it caught this the first time too.
            val spoil = if (closing) {
                if (carryingSediment) sediment.copyOf() else FloatArray(cellsAcross * cellsDown)
            } else {
                null
            }
            if (closing) settle()

            if (onRound != null) {
                census(
                    pits, PitStage.SPOIL, cellsAcross, isLand, directions, area.data, landCells, surfaceOf,
                    null, openingPit
                )
            }

            // One last breach, over the spoil, once the terrain is otherwise finished.
            //
            // The whole of the world's deposition is laid on the rock in the line above, after the
            // last time the water was routed, and a floodplain or a fan laid across a valley mouth
            // dams it. Nothing routes again after this point, so without this pass the lakes a
            // world ends up with are mostly the ones its own sediment made in the last instant of
            // its history: measured on seeds 7 and 42, with the spoil switched off the notch leaves
            // no tectonic lake at all, and with the spoil on the largest lake on the map is one the
            // spoil built. A river does not let its own floodplain dam it, and this is where it
            // says so.
            //
            // Before the last relaxation, and it has to be. The accelerator seam replaces that call
            // wholesale when a stored terrain is being replayed — the snapshot *is* the answer, and
            // anything cut after it would be cut into a field that had just been overwritten by the
            // snapshot and would then be cut again every time the save was opened.
            // `TerrainSnapshotTest` says so, and said so: with this block after the relaxation a
            // reopened save differed from the world it was taken from at the first cell it looked
            // at. What it costs is that the sweeps run over the fresh notch and partly fill it back
            // in, which is the same thing they do to every other channel cut in the same round.
            //
            // What this takes leaves the model: there is no walk left to carry it downstream, so it
            // is accounted as material the outflow took away. The round's own field measurement is
            // read afterwards, so the two halves of the budget still close on the same number.
            //
            // Gated on the notch alone and not on whether anything was carried. The spoil is the
            // reason this pass exists, but making it conditional on the spoil would mean a world
            // with deposition running and every rate at zero had different rock from a world with
            // deposition switched off — and `DepositionTest` holds those two to be bit-identical,
            // which is the assertion that says the deposition machinery is a layer on top of the
            // erosion rather than part of it. It caught this.
            if (closing && erosion.outletIncision) {
                repeat(CLOSING_BREACHES) {
                    val after = SeaLevelStage.percentileCut(working, provisionalSeaLevel, config.scale)
                    if (after.landCellCount == 0) return@repeat
                    val spoilGround = after.relativeElevation
                    val spoilFilled = FlowRouting.fillDepressions(cellsAcross, cellsDown, after.isLand, spoilGround)
                    val spoilFlow =
                        FlowRouting.flowDirections(
                            cellsAcross, cellsDown, after.isLand, spoilGround, spoilFilled,
                            config.seed, config.cellHeightInCellWidths, config.facetRouting,
                            config.flatPotential
                        )
                    // The closing breach cuts the sill a fresh delta laid across a drainage, and
                    // what it has to cut with is the discharge behind that sill. Weighted as the
                    // rounds weight it — but renormalised here, because this pass routes over the
                    // spoil-laid surface and that surface has a shoreline of its own.
                    normaliseOverLand(rainfallMm, after.isLand, after.landCellCount, spoilRunoff)
                    weightSums?.let { reportRoutedWeight(it, "breach $round", spoilRunoff, after.isLand) }
                    val spoilArea = FlowRouting.accumulate(
                        cellsAcross, cellsDown, after.isLand, spoilFilled, spoilFlow, after.landCellCount
                    ) { cell -> spoilRunoff[cell] }
                    val cut = breach(
                        erosion, rates, cellsAcross,
                        FlowRouting.spillways(
                            cellsAcross, cellsDown, after.isLand, spoilGround.data, spoilFilled.data, spoilFlow,
                            rates.pondDepth
                        ),
                        after.isLand, spoilGround.data, spoilFilled.data, spoilFlow, spoilArea.data,
                        after.landCellCount.toFloat(),
                        config.scale.landHalfOfField.coerceAtLeast(1e-6f), working.data,
                        settled = null, load = null
                    )
                    incised += cut.moved
                    lost += cut.moved
                    notched += cut.moved
                    notchCells += cut.cells
                }
            }

            // And the last thing of all: give every river that ends on its own delta a way through
            // it.
            //
            // Two things stop a river short of the water and neither is the lobe's outline: the
            // sea it reached is a pocket the ocean cannot reach, which is the sea-level cut's
            // business, and the ground it would have had to cross to find the ocean is dead flat,
            // which is this pass's. See [openMouths].
            if (closing && erosion.deltaLobe && spoil != null) {
                val opened = openMouths(
                    cellsAcross, cellsDown, working, provisionalSeaLevel, config.scale, spoil,
                    rates.pondDepth, config.seed, config.cellHeightInCellWidths, config.facetRouting,
                    config.flatPotential,
                    rainfallMm, weightSums
                )
                incised += opened.removed
                lost += opened.removed
            }

            if (onRound != null) {
                census(
                    pits, PitStage.CLOSING, cellsAcross, isLand, directions, area.data, landCells, surfaceOf,
                    null, openingPit
                )
            }

            // The unresolved channels' and hillslopes' own transport, an experiment behind its
            // setting: see [ErosionConfig.subGridTransport] and [subGridCreep].
            if (erosion.subGridTransport || erosion.subGridTransportAcrossTheFall || erosion.subGridTransportUndrainedShare) {
                val toSea = subGridCreep(
                    rates, cellsAcross, cellsDown, isLand, sea.shorelineHeight, erodibility, runoff, working.data,
                    scale.yearsPerHydraulicRound,
                    receiver = if (erosion.subGridTransportAcrossTheFall) directions else null,
                    undrainedShare = undrainedShare
                )
                incised += toSea
                lost += toSea
            }

            working = relax(working)

            if (onRound != null) {
                // Against the same round's routing, on the field the relaxation left, which is the
                // field the next round — or the sea-level cut — will route over.
                census(
                    pits, PitStage.RELAX, cellsAcross, isLand, directions, area.data, landCells, working.data,
                    null, openingPit
                )
                onRound(
                    RoundMass(
                        incised = incised,
                        deposited = deposited,
                        lostToSea = lost,
                        // Measured off the finished field, spoil and relaxation included. The
                        // thermal sweeps only move material between neighbours, so they do not
                        // change the total and the comparison stays a comparison with what left.
                        fieldDrop = startingMass - totalMass(working.data) - totalMass(sediment),
                        uplifted = upliftedThisRound,
                        deflected = deflectedThisRound,
                        notched = notched,
                        notchCells = notchCells,
                        basins = notch?.count ?: 0,
                        largestBasinCells = notch?.largestCells ?: 0,
                        largestBasinDepth = notch?.largestDepth ?: 0f,
                        largestBasinSpill = notch?.let {
                            if (it.largest >= 0) it.spill[it.largest] else -1
                        } ?: -1,
                        deepestBasin = notch?.deepest ?: 0f,
                        fillDepthOverLandMetres = openingFillDepthMetres,
                        channelPits = pits.copyOf()
                    )
                )
            }
        }
        return working
    }

    /**
     * How high a delta lobe stands [d] cells out from its apex.
     *
     * A fan slope, small but never nothing: the freeboard at the apex, falling away to a rim that
     * still clears the water by a sixth of it. Two things come out of that, and the second is the
     * reason for it. It reads as a landform rather than a slab — a lobe laid flat at one level is a
     * blocky raft with a straight edge, which is what the author saw jutting into a bay. And, since
     * the surface descends seaward the whole way across, the trunk keeps a downhill step over its
     * own delta and runs on to the new coast instead of arriving at a flat and stopping: the
     * distributary is the gradient, not a channel cut afterwards.
     */
    private fun lobeLevel(
        apexLevel: Float,
        shoreline: Float,
        reachCells: Int,
        distanceCells: Int
    ): Float {
        val rim = shoreline + (apexLevel - shoreline) * LOBE_RIM
        return apexLevel + (rim - apexLevel) * distanceCells.toFloat() / (reachCells + 1).toFloat()
    }

    /**
     * How far out the lobe may grow in the direction of one cell.
     *
     * A delta builds in front of its river, not in a circle around it: the load arrives moving, and
     * what it meets on the flanks is the coast it came past. So the reach is the full one straight
     * ahead and a little over a third of it to the sides and behind, plus a few cells of wobble
     * keyed to the cell's own position so that no two lobes and no two sides of one lobe have the
     * same outline. The wobble is arithmetic on the cell index — there is no table and no hash
     * ordering anywhere in it, so the shape is the same shape on any machine.
     */
    private fun lobeReach(
        reachCells: Int,
        apex: Int,
        cell: Int,
        outX: Float,
        outY: Float,
        cellsAcross: Int
    ): Int {
        val columnOffset = shortestX(cell % cellsAcross - apex % cellsAcross, cellsAcross).toFloat()
        val rowOffset = (cell / cellsAcross - apex / cellsAcross).toFloat()
        val distanceCells = sqrt(columnOffset * columnOffset + rowOffset * rowOffset)
        val outLength = sqrt(outX * outX + outY * outY)
        val ahead =
            if (distanceCells <= 0f || outLength <= 0f) 1f
            else (columnOffset * outX + rowOffset * outY) / (distanceCells * outLength)
        val shape = LOBE_SIDES + (1f - LOBE_SIDES) * ahead.coerceAtLeast(0f)
        return (reachCells * (shape + LOBE_WOBBLE * wobble(cell))).toInt()
    }

    /**
     * The identity of a mouth, for the purpose of hashing its lobe's outline.
     *
     * Not the cell itself. A lobe is rebuilt every round and the cell its trunk arrives at migrates
     * as the delta grows, so hashing the cell would give the same delta a different set of bays
     * every round and twelve rounds of different bays average out to a disc. Quantising the apex to
     * a block the size of the lobe's own reach means a mouth that wanders inside its own delta
     * keeps one outline, and two mouths a delta apart get different ones.
     */
    private fun mouthKey(apex: Int, reachCells: Int, cellsAcross: Int): Int {
        val block = reachCells.coerceAtLeast(1)
        return (apex / cellsAcross / block) * MOUTH_KEY_ROW_STRIDE +
            (apex % cellsAcross / block)
    }

    /** A fixed, repeatable number in 0..1 for a cell, from its index and nothing else. */
    private fun wobble(cell: Int): Float {
        var bits = cell * -0x61c88647
        bits = bits xor (bits ushr 15)
        bits *= 0x2c1b3c6d
        bits = bits xor (bits ushr 12)
        return ((bits ushr 8) and 0xFFFF).toFloat() / 65535f
    }

    /** A column difference across a map that wraps in x, taken the short way round. */
    private fun shortestX(columnOffset: Int, cellsAcross: Int): Int = when {
        columnOffset > cellsAcross / 2 -> columnOffset - cellsAcross
        columnOffset < -cellsAcross / 2 -> columnOffset + cellsAcross
        else -> columnOffset
    }

    /**
     * How far apart two mouths' keys are set by one block of rows, in [mouthKey].
     *
     * Larger than any column block a grid this program will draw can produce, so a row and a
     * column can never mix into the same key.
     */
    private const val MOUTH_KEY_ROW_STRIDE = 0x2000

    /** Where a lobe's rim stands, as a share of the freeboard its apex stands at. */
    private const val LOBE_RIM = 0.15f

    /** How far a lobe reaches sideways and behind, as a share of how far it reaches ahead. */
    private const val LOBE_SIDES = 0.38f

    /** How much of the reach is given over to the per-cell wobble in the outline. */
    private const val LOBE_WOBBLE = 0.18f

    /**
     * How far a distributary falls per cell, as a share of the freeboard its lobe stands at.
     *
     * Enough that the D8 step across a lobe has one answer rather than the fill's epsilon and a
     * coin toss — two orders of magnitude more than that epsilon — and little enough that a channel
     * ten cells long is a groove across the delta rather than a canyon through it.
     */
    private const val DISTRIBUTARY_FALL = 0.15f

    /**
     * The grid every figure in this file that is written per cell was measured at.
     *
     * Only two are, and both are gradients: dividing by the grid in use and multiplying by this
     * keeps them fixed against the map rather than against the cell, which is the difference
     * between a world with more detail in it and a different world.
     */
    private const val REFERENCE_GRID = 512f

    /**
     * How much of the land's water a watercourse must carry before this stage treats it as a river.
     *
     * **A share of runoff and no longer a share of area, and that is the intended effect.** The
     * accumulation this is compared against is weighted by rainfall now, and [normaliseOverLand]
     * leaves the land a mean weight of exactly one, so dividing a catchment's summed weight by the
     * land's cell count still gives a share — of the water that falls on the map rather than of the
     * ground it falls on. Under flat rain the two were the same number and this threshold sat at a
     * fixed fraction of the map's *area*. It now sits where the water is: on a wet flank a smaller
     * catchment reaches it and on a dry one a larger one does not, which is a channel threshold
     * following the discharge rather than the geometry.
     *
     * **This stage's own rule, and a separate approximation from the network the map draws.**
     * Since R1 a watercourse is drawn where `ChannelInitiation` says the ground can be cut — an
     * area-slope criterion in square kilometres, raised by the plant cover and held off ground that
     * never thaws — and nothing about that criterion is a share of anything. This constant does not
     * follow it, for two reasons that are worth keeping separate.
     *
     * The first is the practical one, and is the same reason [POND_DEPTH_METRES] is a constant
     * here rather than a read of its setting: the criterion is parameterised by `config.rivers`,
     * the rivers section is chosen long after erosion runs, and reading it here would mean adding
     * `rivers` to erosion's reuse guard so that moving a river setting re-cut twelve rounds of
     * valleys.
     *
     * The second is that the two rules answer different questions. The criterion asks whether
     * running water can open a head in *this* ground, which is about the gradient and what is
     * rooted in it; the passes below ask whether enough water arrives at a place for a delta to be
     * built there and a distributary to be worth cutting, which is about discharge alone — and they
     * ask it on a delta lobe, which is precisely the flat ground where the criterion initiates
     * nothing of its own and relies on carrying a channel downstream onto it. So this is a
     * discharge rule standing in for a network the stage cannot see, and a coarse one: it will
     * count a wet-country trunk the map does not draw and miss a dry-country one it does. What it
     * has to be right about is the order of magnitude of the water at a mouth, and it is.
     * See docs/DESIGN_LEDGER.md, S3 and R1.
     */
    private const val DRAWN_RIVER = 0.0006f

    /**
     * How much of bare rock's erodibility a closed plant canopy takes away.
     *
     * Roots bind the regolith, litter breaks the rain's impact and stems slow the overland flow
     * before it concentrates, and the measured effect is large: Istanbulluoglu and Bras (*Vegetation
     * modulates landscape erosion*, JGR Earth Surface 110, 2005) find full cover roughly halves the
     * erodibility of otherwise identical ground, and the erosional response of a vegetated
     * catchment to the same storms is about half its bare equivalent's. So a half, spent linearly
     * in the cover: bare ground cuts at the full rate and closed canopy at half it.
     *
     * A chosen approximation, and worth saying so: Istanbulluoglu and Bras do not halve an
     * erodibility. Their model partitions the flow's shear stress between the plants and the bed
     * and raises the threshold the bed fails at with the cohesion the roots add, which is a
     * different shape of law with its own parameters. A half spent linearly in the cover is the
     * effect their results carry, at the resolution this stage works to, and not their formula.
     *
     * **It is a relative half, and it is spent relatively.** `1 - 0.5 * density` is divided by its
     * own mean over the land before it reaches the cut — see [shieldingOverLand] — so what the
     * term carries is the contrast between bare ground and closed canopy, with the blanket
     * attenuation taken out. It does not conserve the erosion: a mean of one over cells is not a
     * mean of one over cuts, because the cover sits where the cutting is (wet ground is both best
     * wooded and hardest cut), and the denudation off an active belt measured 0.218 mm/yr
     * against 0.271 without the term when S3 built it, not 0.271 again. The relative form is the least the
     * calibration can lose, not its preservation. `ErosionConfig.bedrockErodibilityPerYear` carries Stock and
     * Montgomery's and Lague's figures for real bedrock rivers, and those rivers ran through
     * forests: the cover is already in the number. Multiplying it by `1 - 0.5 * density` again
     * counts the cover twice, and it was built that way first and measured - denudation off an
     * active belt fell from 0.271 to 0.189 mm/yr, a third of the world's erosion gone, and seven
     * guards in seven classes went red behind it. Istanbulluoglu and Bras compare a vegetated
     * catchment with a bare one; they do not say what either does in absolute terms, so a relative
     * half is also the only thing their result licenses. The runoff weight is normalised for
     * exactly this reason and in exactly this way.
     *
     * Only the ordinary stream-power cut in [incise] reads it, which is every hillslope and channel
     * cell of every round. The outlet notch and the distributary grooves do not, and the reason is
     * not that the ground there is bare: a notch is a spill's base-level fall, spent over a fixed
     * reach to grade a sill down to the water it drains to, rather than a cell deciding how much
     * of itself the water passing over it takes away. An erodibility multiplier belongs to the
     * second and has nothing to scale in the first.
     */
    private const val VEGETATION_SHIELDING = 0.5f

    /**
     * How much deeper a lacustrine fan lies per cell of distance from the river that built it, as a
     * share of the two pond-depths it is held below the surface at its apex.
     *
     * A fan is a slope, not a shelf: the coarse material drops at the inflow and the fine carries
     * further out, so the floor falls away from the mouth. Modest, because the whole fan sits in
     * water a few pond-depths deep and the point is a floor with a shape rather than a canyon.
     *
     * Charged against the fraction of the fan's rim and not against the cell, because a charge
     * per cell gives the same lake a different floor at every grid — which is the thing
     * the units exist to prevent. One and a half against a rim fraction reproduces the 512
     * figure exactly and holds it at every grid. See docs/DESIGN_LEDGER.md, E6, for the depths measured
     * each way.
     */
    private const val LAKE_FAN_SLOPE = 1.5f

    /**
     * How deep the water has to be, in metres, before a fan finds it as expensive to build into as
     * it finds one whole cell of distance.
     *
     * Earth's shelf break stands at about 130 m, which is what this is — and not by coincidence
     * within a few metres of `SeaConfig.lowstandMetres`, because the shelf break is roughly where
     * the shoreline stood at the last glacial maximum. It is the right scale for the question this
     * constant answers, which is how much accommodation space a fan has to fill before it can
     * advance. On a shelf a delta walks out almost freely and builds the Nile's two hundred
     * kilometres of new land; over the lip it is paying eight or ten times as much per cell and
     * stops, which is why a fjord-head delta is a step and not a fan. `DeltaOutlineTest` measures
     * the ratio on a synthetic coast.
     *
     * Read off the height field's own ruler rather than either half of the piecewise one: the
     * depth this is compared against is taken from the shoreline down into that field, so it is a
     * distance in it. 130 m of 16,000 is 0.0081, where the constant it replaced was 0.015 of the
     * land's relief above the shoreline — which came to 0.0088 of the field on the worlds
     * measured, and to a different figure on every one of them.
     */
    private const val SHELF_BREAK_METRES = 130f

    /** How many cells of distance one shelf-break depth of water costs a fan. */
    private const val DEPTH_COST = 1f

    /**
     * How much of a lobe's freeboard a distributary groove keeps, as a share.
     *
     * Deep enough that the D8 step across a delta follows the groove rather than the fill's
     * epsilon, so what the rivers stage draws is a bird's foot; shallow enough that the groove is
     * still dry land, because a groove cut below the waterline would be a finger of sea reaching
     * into the lobe and the delta would come apart into islands.
     */
    private const val GROOVE_KEEP = 0.45f

    /** What one pass of the outlet notch took off, and out of how many cells. */
    internal class Breached(val moved: Double, val cells: Int)

    /** What cutting the grooves took off the land, and out of how many cells. */
    private class Opened(val removed: Double, val cuts: Int)

    /**
     * Cuts each drawn river one channel to follow wherever its own path crosses ground the fill had
     * to raise.
     *
     * A delta lobe is built round after round around whichever cell the trunk was reaching at the
     * time, laid a little at a time and then handed to the thermal sweeps, and what comes out is
     * flat enough that the depression fill has to level it. The water then crosses it on the fill's
     * epsilon, which is a coin toss cell by cell: the trunk arrives and breaks into a fan of
     * one-cell threads lying at the grid's own bearings, none of them carrying enough accumulation
     * to be drawn as a river. What the reader sees is a river stopping at the inner edge of a pale
     * slab. Measured on seed 59758 at 2048, 336 of the 614 land cells within twelve of that mouth
     * had no lower neighbour at all.
     *
     * So the river's own path is cut to a surface that falls by a fixed step at every cell, for as
     * long as it is crossing raised ground. The walk is in drainage order, sources first, so each
     * cell is cut against the level its upstream neighbour was left at and the groove descends the
     * whole way. Standing water counts as raised ground where the river is running over its own
     * fresh sediment — a puddle in a week-old fan is not a lake held in rock, and the spoil test is
     * what keeps this off one.
     *
     * What it deliberately does not do is touch the water. An earlier version of this also opened
     * every pocket of sea a river ended in, by cutting an inlet from it to the ocean, and the
     * measurements were good — the mouths ending in a pocket fell below the count in a world with
     * no deposition at all. It is reverted all the same: a small body of water the ocean cannot
     * reach is not always an artefact. `RiftSegmentationTest` asks a flooded rift to be a chain of
     * gulfs with land bridges between them, and those gulfs are exactly such bodies; joining them
     * to the ocean turned the chain back into the channel that chunk existed to break up, and moved
     * enough coastline besides to unsettle the ocean-current and culture guards. Water the sea
     * cannot reach is a question for the sea-level cut, and GEOGRAPHY.md now records it as one.
     */
    private fun openMouths(
        cellsAcross: Int,
        cellsDown: Int,
        working: FloatField,
        provisionalSeaLevel: Float,
        scale: WorldScale,
        spoil: FloatArray,
        pondDepth: Float,
        seed: Long,
        cellHeightInCellWidths: Double,
        byFacet: Boolean,
        overPotential: Boolean,
        /** The march's rainfall in millimetres, floored; this pass normalises it for itself. */
        rainfallMm: FloatArray,
        weightSums: ((String, Double, Int) -> Unit)?
    ): Opened {
        val cellCount = cellsAcross * cellsDown
        val sea = SeaLevelStage.percentileCut(working, provisionalSeaLevel, scale)
        if (sea.landCellCount == 0) return Opened(0.0, 0)
        val isLand = sea.isLand
        val surfaceOf = working.data
        val landRange = scale.landHalfOfField.coerceAtLeast(1e-6f)
        // Measured against the pond depth rather than against the delta's freeboard, though a
        // freeboard is what it is cutting through. `DepositionTest` holds that no deposition knob
        // may change a world with deposition switched off, and this pass runs either way; reading
        // `deltaFreeboard` here let the fiddled-knobs case move the terrain. It caught that too.
        val step = pondDepth * landRange
        // Per cell, from a gradient held against the map, so a groove of a given length on the
        // ground is the same groove however fine the grid that cuts it.
        val fall = (step * DISTRIBUTARY_FALL * REFERENCE_GRID / cellsAcross).coerceAtLeast(1e-7f)
        val floor = sea.shorelineHeight + step * LOBE_RIM

        val filled =
            FlowRouting.fillDepressions(cellsAcross, cellsDown, isLand, sea.relativeElevation)
        val flow = FlowRouting.flowDirections(
            cellsAcross, cellsDown, isLand, sea.relativeElevation, filled, seed, cellHeightInCellWidths,
            byFacet, overPotential
        )
        // Weighted as the rounds weighted it, so a groove is cut where a river's water is and not
        // merely where a lot of ground drains — and normalised over this pass's own land, which is
        // the finished terrain's and not any round's.
        val runoff = FloatArray(cellCount)
        normaliseOverLand(rainfallMm, isLand, sea.landCellCount, runoff)
        weightSums?.let { reportRoutedWeight(it, "outlet", runoff, isLand) }
        val area = FlowRouting.accumulate(
            cellsAcross, cellsDown, isLand, filled, flow, sea.landCellCount
        ) { cell -> runoff[cell] }
        val landCells = sea.landCellCount.toFloat()

        var removed = 0.0
        var cuts = 0
        val order = FlowRouting.drainageOrder(cellsAcross, cellsDown, isLand, flow, sea.landCellCount)
        // Mouths first. Reversed, the drainage order reaches a cell only after the cell it drains
        // into, so each one is cut to sit one step above ground that is already final — and a
        // groove built that way descends the whole way to the water by construction.
        //
        // Sources first was tried and is what a channel dug from the top down actually does: it
        // reaches the level it is allowed to stop at, stops, and leaves a trench with a closed end
        // for the next fill to pond. Two of those on seed 718106 at 2048, thirty-three cells
        // between them, and `GlaciationTest` counted them as thin straight water at a grid bearing,
        // which is exactly what they were.
        for (rank in order.indices.reversed()) {
            val cell = order[rank]
            // Every watercourse carrying a river's worth of water, not only the few big enough
            // to build a delta: at `deltaMinCatchment` instead — five times as much — the trunk at
            // the author's own mouth on seed 59758 did not qualify and nothing was cut. Which
            // courses the map goes on to draw is `ChannelInitiation`'s answer and not this one;
            // see [DRAWN_RIVER] for why this pass keeps a rule of its own.
            if (area.data[cell] / landCells < DRAWN_RIVER) continue
            val standing = filled.data[cell] - sea.relativeElevation.data[cell]
            val onFlat = standing > 0f && (standing <= pondDepth || spoil[cell] > 0f)
            if (!onFlat) continue
            val receiver = flow[cell]
            if (receiver < 0) continue
            val below = if (isLand[receiver]) surfaceOf[receiver] else sea.shorelineHeight
            val want = minOf(surfaceOf[cell], below + fall).coerceAtLeast(floor)
            if (surfaceOf[cell] > want) {
                removed += -raise(surfaceOf, cell, (want - surfaceOf[cell]).toDouble())
                cuts++
            }
        }
        return Opened(removed, cuts)
    }

    /**
     * Cuts every filled basin's lip down by what its own outflow can take, and cuts the sill below
     * the lip down with it.
     *
     * The second half is the part that is easy to leave out and fatal to leave out. Lowering the
     * rim cell alone changes nothing: the fill finds the same rim the next time it runs, because
     * what dams a basin is not one cell but the whole sill between the lip and the first ground
     * that already lies below the new lake surface. So the channel is *breached* — cut to a surface
     * that begins at the new lip level and falls away from it cell by cell down the flow path,
     * stopping at the first cell that is already lower than that surface. Beyond a steep rim that
     * is one or two cells; on a plateau it is a gorge, and the length of that gorge is exactly why
     * a lake on a plateau lasts and one behind a ridge does not.
     *
     * Breaching rather than filling is the older of the two answers to a depression in the
     * hydrology literature and the one that matches what the ground actually does; this pipeline
     * fills, because the router needs an outlet for every cell in a single pass, and this is where
     * the other half is put back.
     *
     * The breach begins at the lip, the basin's pour point, and not at the first cell outside its
     * deep water: where the lake shelves those are different cells, and a breach begun on the
     * margin stopped on it (see [FlowRouting.Spillways.entry]). The outflow's power is measured
     * from the lip too, over the catchment gathered there and the channel below it, because that
     * is the water crossing the sill and the slope it crosses at; measured from the margin, the
     * level margin diluted the slope. And the surface is carried back from the lip across the
     * margin as far as the margin stands above it, so a round whose drop is deeper than the margin
     * lowers the whole sill rather than leaving the margin as a new one.
     *
     * @param settled the surface deposition is judged against, lowered with the terrain, or null
     *   when nothing is being carried.
     * @param load where the spoil goes, or null when there is no walk left to carry it — in which
     *   case the caller accounts for it as material that left the model.
     * @param belowSea whether the notch may be cut past the shoreline. False inside the rounds,
     *   where the sea is the base level every river grades to; true for the one pass the sea-level
     *   stage runs on the far side of the cut, where the water behind the sill stands *below* the
     *   shoreline and the river crossing it is grading to that instead. See
     *   `SeaConfig.postCutOutlet`.
     */
    internal fun breach(
        erosion: ErosionConfig,
        rates: Rates,
        cellsAcross: Int,
        notch: FlowRouting.Spillways,
        isLand: BooleanArray,
        relative: FloatArray,
        ground: FloatArray,
        directions: IntArray,
        area: FloatArray,
        landCells: Float,
        landRange: Float,
        surfaceOf: FloatArray,
        settled: FloatArray?,
        load: DoubleArray?,
        belowSea: Boolean = false
    ): Breached {
        var moved = 0.0
        var cells = 0

        for (basin in 0 until notch.count) {
            val spill = notch.spill[basin]
            if (spill < 0) continue
            val level = notch.level[basin]
            val floor = notch.floor[basin]
            if (level - floor <= 0f) continue

            // How far this round's outflow lowers the lip. Stream power, in the same form and with
            // the same coefficient as the ordinary incision, behind a ratio: the discharge is the
            // basin's whole catchment, which is what flow accumulation has already gathered at the
            // rim, and the slope is the one the outlet channel actually stands at, measured over
            // the notch's own length rather than across the single step under the lip. That step is
            // a saddle's, and a saddle is by construction the flattest way out of a basin: a rate
            // taken from it drains nothing in the twelve rounds a world gets, which is the
            // measurement that decided this shape.
            // The channel's length on the ground, in cell widths: each cell on it counts the step it
            // drains through, so a notch running down a column is as long as the ground it crosses
            // and not twice that. How many cells it is, apart, is what the fill's staircase is
            // counted in.
            val steps = rates.groundSteps
            var fall = 0f
            var lengthCellWidths = 0f
            var cellsWalked = 0
            var lastStep = steps.eastWest
            var cell = spill
            while (cell >= 0 && isLand[cell] && lengthCellWidths < rates.outletReachCells) {
                fall = level - relative[cell]
                if (fall > level - floor) break
                val next = directions[cell]
                lastStep = if (next >= 0) steps.between(cell, next, cellsAcross) else steps.eastWest
                lengthCellWidths += lastStep
                cellsWalked++
                cell = next
            }
            // The walk above stops on the last cell of *land*, one step short of the water the
            // outflow empties into, and where the sill runs level all the way to that water the
            // whole of its fall is in the step it did not take. What it measures instead is the
            // [FlowRouting.FLAT_GRADIENT_STEP] the depression fill nudges a flat by: not a small
            // gradient but the absence of one, and therefore no stream power and a sill that stands
            // for the life of the world however large the catchment behind it. Seed 99 at 512 kept
            // a 668-cell basin below the shoreline that way — 2.64 times the Caspian's share of its
            // land — its outflow's measured fall 1.0e-6 against the 2.5e-2 it actually descends,
            // unmoved over every pass it was given.
            //
            // So where the walk found no fall the fill did not put there, the step into the water
            // counts. One flat-gradient step a cell is the staircase the flood leaves on a flat, so
            // the test is that whole staircase and no more, and what it changes is only outlets
            // that were cutting nothing whatever. An outlet that measured a real gradient keeps the
            // answer it had: re-rating those as well hands every coastal sill the whole fall to sea
            // level at once, which empties basins that ought to hold their water.
            if (erosion.outletFallToTheWater &&
                fall <= FlowRouting.FLAT_GRADIENT_STEP * cellsWalked &&
                cell >= 0 && !isLand[cell]
            ) {
                fall = level - relative[cell]
                lengthCellWidths += lastStep
            }
            if (cellsWalked == 0) continue
            val slope = (fall / lengthCellWidths * cellsAcross).coerceAtLeast(0f)
            val power = rates.relativeIncisionCoefficient * erosion.outletIncisionRatio *
                sqrt(area[spill] / landCells) * slope

            // Never below the floor of its own basin, because past that there is no lake left to
            // let out; never below the sea, the base level everything grades to.
            //
            // All three in the shoreline-relative units the basin is measured in, and converted to
            // the height field's own units once, at the point of cutting. That is not tidiness: the
            // range of the land is not the same number at every grid — on seed 718106 it is 0.25 at
            // 512 and 0.39 at 1024, because a finer grid resolves finer and therefore steeper
            // detail — so a rate written in one unit and applied in the other is a rate that
            // depends on the cell size. It was, and the lake it left grew threefold from 512 to
            // 2048 on seed 59758 while every other length in the stage held.
            val dropRelative =
                if (belowSea) minOf(power, level - floor) else minOf(power, level - floor, level)
            if (dropRelative <= 0f) continue
            val newLevel = level - dropRelative
            cell = spill
            var fromLipCellWidths = 0f
            // The breach's own fall, per cell width, from a gradient in metres per kilometre: a
            // channel of a given length on the ground descends by the same amount however many
            // cells that length is cut into, and whichever way it runs.
            val gradient = rates.notchFallPerCell
            while (cell >= 0 && isLand[cell] && fromLipCellWidths < rates.outletReachCells) {
                // Inside the rounds the sea is the base level below the lip too, and the falling
                // surface stops at it rather than running a few centimetres a cell past it.
                val falling = newLevel - fromLipCellWidths * gradient
                val cutLevel = if (belowSea) falling else falling.coerceAtLeast(SHORELINE)
                if (relative[cell] <= cutLevel) break
                val take = (relative[cell] - cutLevel).toDouble() * landRange
                val ponded = ground[cell] - relative[cell] > rates.pondDepth
                val removedHere = -raise(surfaceOf, cell, -take)
                if (removedHere > 0.0) {
                    val asRelative = (removedHere / landRange).toFloat()
                    relative[cell] -= asRelative
                    // Dry ground goes down with the terrain; a cell that was standing under water
                    // keeps its surface, since deepening a pond does not lower what is on top of it.
                    if (!ponded) ground[cell] -= asRelative
                    settled?.let { it[cell] -= asRelative }
                    load?.let { it[cell] += removedHere }
                    moved += removedHere
                    cells++
                }
                val next = directions[cell]
                fromLipCellWidths += if (next >= 0) steps.between(cell, next, cellsAcross) else steps.eastWest
                cell = next
            }

            // Back across the lake's shallow margin, from the lip to where the deep water begins:
            // the same surface, rising by the same gradient per cell away from the lip, stopping at
            // the first cell already below it. Nothing to do where the ground falls straight from
            // the water to the lip, which is where the entry is the lip itself.
            var backCellWidths = 0f
            val entry = notch.entry[basin]
            if (entry >= 0 && entry != spill) {
                var marginCells = 0
                var walker = entry
                var marginWidths = 0f
                while (walker >= 0 && walker != spill && isLand[walker] && marginWidths <= rates.outletReachCells) {
                    val next = directions[walker]
                    marginWidths += if (next >= 0) steps.between(walker, next, cellsAcross) else steps.eastWest
                    marginCells++
                    walker = next
                }
                if (walker == spill) {
                    val margin = IntArray(marginCells)
                    walker = entry
                    for (index in 0 until marginCells) {
                        margin[index] = walker
                        walker = directions[walker]
                    }
                    var downstream = spill
                    for (index in marginCells - 1 downTo 0) {
                        val marginCell = margin[index]
                        backCellWidths += steps.between(marginCell, downstream, cellsAcross)
                        val cutLevel = newLevel + backCellWidths * gradient
                        if (relative[marginCell] <= cutLevel) break
                        val take = (relative[marginCell] - cutLevel).toDouble() * landRange
                        val ponded = ground[marginCell] - relative[marginCell] > rates.pondDepth
                        val removedHere = -raise(surfaceOf, marginCell, -take)
                        if (removedHere > 0.0) {
                            val asRelative = (removedHere / landRange).toFloat()
                            relative[marginCell] -= asRelative
                            if (!ponded) ground[marginCell] -= asRelative
                            settled?.let { it[marginCell] -= asRelative }
                            load?.let { it[marginCell] += removedHere }
                            moved += removedHere
                            cells++
                        }
                        downstream = marginCell
                    }
                }
            }

            // And, on the far side of the cut only, the sill on the *basin's* own side.
            //
            // Inside the rounds the notch can never be cut below the lake's own surface, so there
            // is by construction nothing between the water and the lip for it to go through: the
            // sill is entirely the ground beyond the lip, which the walk above cuts. Lift that
            // limit — which is what [belowSea] does, because the water behind one of these sills
            // stands below the shoreline and the sea is not its base level — and the other half of
            // the sill appears. The lake bed between the deep water and the lip is now above the
            // target too, and it dams the basin exactly as the outside of the lip did.
            //
            // Measured, and this is not a subtlety: on seed 99 at 512 the outflow over the biggest
            // drowned basin's sill has the power to cut it to the basin's own floor, well below the
            // waterline, and cutting only the outside of the lip moved the lake from 1486 cells to
            // 1428 and then not by one cell over eight further passes, because the walk above began
            // at a spill it had already taken below the target and stopped at once. The dam was one
            // step upstream of where it was looking.
            //
            // So the same surface is continued back across the lake bed: from the lip, up the
            // inflow with the largest catchment — the path the lake's own water takes to the outlet,
            // and therefore the channel that incises across the floor as the water goes down —
            // rising by the same gradient per cell, stopping at the first ground already below it
            // and never leaving the water. Bounded by the same reach, and the tie between two
            // donors of equal catchment falls to the lower cell index, so the channel is one
            // specific channel on every machine.
            if (belowSea) {
                var from = if (entry >= 0) entry else spill
                while (backCellWidths <= rates.outletReachCells) {
                    var bestDonor = -1
                    var bestArea = -1f
                    FlowRouting.forEachNeighbour(
                        cellsAcross,
                        ground.size / cellsAcross,
                        from % cellsAcross,
                        from / cellsAcross
                    ) { neighbour ->
                        if (isLand[neighbour] && directions[neighbour] == from &&
                            ground[neighbour] - relative[neighbour] > rates.pondDepth
                        ) {
                            val neighbourArea = area[neighbour]
                            val ties = neighbourArea == bestArea &&
                                (bestDonor < 0 || neighbour < bestDonor)
                            if (neighbourArea > bestArea || ties) {
                                bestArea = neighbourArea
                                bestDonor = neighbour
                            }
                        }
                    }
                    if (bestDonor < 0) break
                    backCellWidths += steps.between(bestDonor, from, cellsAcross)
                    if (backCellWidths > rates.outletReachCells) break
                    val cutLevel = newLevel + backCellWidths * gradient
                    if (relative[bestDonor] <= cutLevel) break
                    val take = (relative[bestDonor] - cutLevel).toDouble() * landRange
                    val taken = -raise(surfaceOf, bestDonor, -take)
                    if (taken > 0.0) {
                        val asRelative = (taken / landRange).toFloat()
                        relative[bestDonor] -= asRelative
                        settled?.let { it[bestDonor] -= asRelative }
                        load?.let { it[bestDonor] += taken }
                        moved += taken
                        cells++
                    }
                    from = bestDonor
                }
            }
        }
        return Breached(moved, cells)
    }

    /**
     * Counts the channel cells standing lower than the cell they drain into, into [into] at [slot].
     *
     * The measurement the receiver clamp is justified by, taken after each mechanism of a round
     * in turn so that the clamp could be put where the pits actually come from rather than
     * everywhere a clamp might plausibly belong. See docs/DESIGN_LEDGER.md, H5b. Judged against the round's own routing — the D8
     * receivers and the flow accumulation the fill produced when the round opened — because that
     * is the network the mechanisms were working on.
     *
     * [already] is what makes the number mean something. A cell on the floor of a filled basin is
     * below its receiver before the round starts and will be after it: the routing runs on the fill
     * and the ground beneath a lake does not slope. Counting those would drown the signal in the
     * basins the world legitimately has, so the opening census records them and every later one
     * counts only cells that were *not* pits when the round began. What each slot therefore holds
     * is the number of holes that mechanism put in a river's bed.
     *
     * A cell whose receiver is water is never a pit: the sea is the base level and standing below
     * it is what a river mouth does. [spoil], where the round is still holding its sediment off the
     * terrain, is added to both cells so that the surface measured is the one the next fill sees.
     */
    private fun census(
        into: IntArray,
        slot: Int,
        cellsAcross: Int,
        isLand: BooleanArray,
        directions: IntArray,
        area: FloatArray,
        landCells: Float,
        surface: FloatArray,
        spoil: FloatArray?,
        already: BooleanArray
    ) {
        val opening = slot == PitStage.OPENING
        var count = 0
        for (cell in directions.indices) {
            if (!isLand[cell]) continue
            val receiver = directions[cell]
            if (receiver < 0 || !isLand[receiver]) continue
            if (area[cell] / landCells < DRAWN_RIVER) continue
            if (!opening && already[cell]) continue
            val here = surface[cell] + (spoil?.get(cell) ?: 0f)
            val there = surface[receiver] + (spoil?.get(receiver) ?: 0f)
            if (here < there) {
                count++
                if (opening) already[cell] = true
            }
        }
        into[slot] = count
    }

    /**
     * How far [cell] falls to the water level it drains to, in shoreline-relative units: to its
     * receiver's filled surface on land, and to the shoreline, which is zero, where the receiver is
     * sea. The sea's surface is the base level a river grades to, and a depth below it is on the
     * sea's half of the ruler, not the land's; the two cannot be subtracted.
     */
    private fun fallToReceiver(ground: FloatArray, isLand: BooleanArray, cell: Int, receiver: Int): Float =
        ground[cell] - if (isLand[receiver]) ground[receiver] else 0f

    /**
     * One round of stream-power incision over [surfaceOf], by Braun and Willett's implicit update
     * (*A very efficient O(n), implicit and parallel method to solve the stream power equation
     * governing fluvial incision and landscape evolution*, Geomorphology 180-181, 2013): the
     * FastScape scheme, at `n = 1`.
     *
     * **The law.** `E = K A^m S^n` with `m = 0.5` and `n = 1`, spent over the round's years. Per
     * cell that is `F` times the drop to the ground the cell falls toward, where `F` is
     * [Rates.courantCoefficient] times `sqrt(share) * erodibility / stepCellWidths`: [discharge] over
     * [landCells] is the share, [erodibility] the cover's factor and the step the one to the
     * receiver on the ground. The explicit update, `z' = z - F (z - z_r)`, is bounded only while
     * `F` is under one, and on this map `F` is about `0.24 sqrt(cells of catchment)`: two where a
     * drawn river starts and tens on a trunk. So it needed a cap, and the cap set every drawn
     * channel's cut (docs/DESIGN_LEDGER.md, Fix 3).
     *
     * **The update.** Taken at the end of the round instead, the law reads the receiver's *new*
     * height, `z' = z - F (z' - z_r')`, which solves to `z' = (z + F z_r') / (1 + F)`: the cell
     * keeps `1 / (1 + F)` of its height over its receiver's new one and loses `F / (1 + F)` of it.
     * [order] is `FlowRouting.drainageOrder`, which puts every cell after everything draining into
     * it, so read backwards it reaches every receiver before its donors and `z_r'` is final when
     * it is read. One pass, O(n), no iteration and no limiter.
     *
     * **The bounds, which hold on [surfaceOf] as this returns.**
     * - *The base level a cell grades to*, `z_r'`: its receiver's new ground on land; the round's
     *   [shorelineHeight] where the receiver is sea, so a river mouth grades to the sea's surface
     *   and the cap the explicit update needed at the shoreline is this boundary condition; and
     *   the water's surface where the receiver stands under a filled basin's water by more than
     *   [Rates.pondDepth], because a river entering a lake grades to the lake as one entering the
     *   sea grades to the sea.
     * - *A lake falls with its outlet.* Its surface for the pass is the lower of its filled level
     *   and the level its outlet drains to once the outlet is cut, carried upstream through the
     *   lake's cells (receivers first, so the outlet is final before the lake behind it). A cell
     *   under that surface is neither cut nor raised; a cell the falling water uncovers is graded
     *   like any other; and an inflow grades to the surface as it now stands. The filled surface
     *   [ground] is read only to find the water and its level before the pass: everywhere else the
     *   pass works on the actual ground [surfaceOf] as the notch left it.
     * - *No cell the pass moves ends below its base level.* The update is a weighted mean of the
     *   cell's own height and `z_r'`, with weights `1 / (1 + F)` and `F / (1 + F)`, both positive
     *   for any `F`, so it lies between them; carried out as `z_r' + (z - z_r') / (1 + F)` in
     *   double, it rounds to a float between the two. Cells the pass leaves alone keep their
     *   height, below their base or not: a basin's floor under water, and a cell already at or
     *   below the level it grades to.
     * - *No cell is raised.* A cell standing at or below its base level is neither cut nor
     *   raised: the floor of a basin, where the routing runs over the fill and the ground does not
     *   slope, has no channel to deepen, and the weighted mean would lift it toward its receiver,
     *   which is deposition by the back door. The test is against the receiver's *new* height, so
     *   a cell that stood below its receiver when the round opened is cut once the receiver has
     *   been cut below it. [onlyAboveBase] false takes this rule out, and is only ever the guards'
     *   control.
     *
     * **What it does not promise.** Stability is not accuracy. At [WorldScale.yearsPerHydraulicRound]
     * a round, a cell with a large `F` loses nearly its whole drop in one step, and the scheme
     * spreads a knickpoint over a few cells as any first-order upwind scheme does, a numerical
     * diffusion that scales with the step; a shorter round would resolve a profile's shape more
     * finely and would not change where it grades to. And the realised cut is not proportional to
     * the law's rate: it is `F / (1 + F)` of the drop, so a factor that multiplies `F`, the cover's
     * or the rain's, multiplies the cut fully where `F` is small and less and less as it grows.
     * The law's rate is `F (z - z_r)` on the ground as the pass found it, which [watch] is handed.
     *
     * @param ground the depression-filled surface the round routed on, and [relative] the actual
     *   one, both shoreline-relative with the land's half of the field, [landRange], as their unit;
     *   read only to find standing water.
     * @param incisedAt where what each cell lost goes, in the height field's unit, for the
     *   deposition walk; null when nothing is carried.
     */
    internal fun incise(
        rates: Rates,
        cellsAcross: Int,
        order: IntArray,
        directions: IntArray,
        isLand: BooleanArray,
        ground: FloatArray,
        relative: FloatArray,
        discharge: FloatArray,
        landCells: Float,
        landRange: Float,
        shorelineHeight: Float,
        erodibility: FloatArray,
        surfaceOf: FloatArray,
        incisedAt: DoubleArray?,
        onlyAboveBase: Boolean = true,
        watch: IncisionWatch? = null,
        round: Int = 0
    ) {
        val asFound = if (watch != null) surfaceOf.copyOf() else null
        // The water's surface over each cell of a filled basin for this pass, NaN elsewhere: set
        // receivers first, so the outlet's new height is known before the lake behind it.
        val waterSurface = FloatArray(surfaceOf.size) { Float.NaN }
        for (rank in order.indices.reversed()) {
            val cell = order[rank]
            val receiver = directions[cell]
            val underStandingWater = ground[cell] - relative[cell] > rates.pondDepth
            if (receiver < 0) {
                if (underStandingWater) waterSurface[cell] = shorelineHeight + ground[cell] * landRange
                continue
            }
            val baseAfter = baseLevel(receiver, surfaceOf, isLand, waterSurface, shorelineHeight)
            if (underStandingWater) {
                // The lake falls with its outlet: its surface is its filled level or the level its
                // outlet now drains to, whichever is lower, and a cell behind another lake cell
                // shares that cell's surface.
                val filledLevel = shorelineHeight + ground[cell] * landRange
                val downstream = if (isLand[receiver] && !waterSurface[receiver].isNaN()) waterSurface[receiver] else baseAfter
                waterSurface[cell] = minOf(filledLevel, downstream)
            }
            val height = surfaceOf[cell]
            val stepCellWidths = rates.groundSteps.between(cell, receiver, cellsAcross)
            val courantNumber =
                rates.courantCoefficient * sqrt(discharge[cell] / landCells) * erodibility[cell] / stepCellWidths
            // Under the water as it now stands there is no channel to cut and nothing to raise; a
            // cell the falling water has uncovered is graded like any other.
            val submerged = underStandingWater && height <= waterSurface[cell]
            val after =
                if (submerged || (height <= baseAfter && onlyAboveBase)) height
                else (baseAfter + (height - baseAfter).toDouble() / (1.0 + courantNumber)).toFloat()
            if (after != height) {
                surfaceOf[cell] = after
                if (incisedAt != null && after < height) incisedAt[cell] = height.toDouble() - after.toDouble()
            }
            if (watch != null && asFound != null) {
                // Before the pass the lake stood at its filled level.
                val baseBefore = when {
                    !isLand[receiver] -> shorelineHeight
                    ground[receiver] - relative[receiver] > rates.pondDepth ->
                        maxOf(asFound[receiver], shorelineHeight + ground[receiver] * landRange)
                    else -> asFound[receiver]
                }
                watch.cut(round, cell, receiver, courantNumber, height, baseBefore, baseAfter, after)
            }
        }
    }

    /**
     * One round of the sub-grid transport [ErosionConfig.subGridTransport] describes: linear
     * diffusion of [surface] over the land for [years], conservative between land cells, with the
     * diffusivity [Rates.subGridDiffusivity] times each cell's [erodibility] and the square root
     * of its [runoff] weight, a face taking the mean of its two cells'.
     *
     * Net of the resolved incision in one of two ways, or neither. Given [receiver], each cell's
     * diffusivity on a face is taken down by the square of its receiver's bearing across that face,
     * [ErosionConfig.subGridTransportAcrossTheFall]'s form. Given [undrainedShare], it is scaled by
     * that share, [ErosionConfig.subGridTransportUndrainedShare]'s form. Not both.
     *
     * Five-point, on the ground: a face across a row is a cell width long and one down a column a
     * row's height, each difference over its own length squared. Explicit, in as many equal steps
     * as keep the fastest cell's `dt D (2/dx^2 + 2/dy^2)` at or under a fifth: stable is under
     * one, and a fifth keeps the steps' own damping within a hundredth of the law's on a ripple
     * eight rows long (`SubGridTransportTest`). The sea is a
     * boundary at [shorelineHeight]: land above it loses to it what the face carries, never more
     * than its own height over the shoreline, and the sea gives nothing back. The poles are closed.
     *
     * @return what crept into the sea, summed in the height field's unit.
     */
    internal fun subGridCreep(
        rates: Rates,
        cellsAcross: Int,
        cellsDown: Int,
        isLand: BooleanArray,
        shorelineHeight: Float,
        erodibility: FloatArray,
        runoff: FloatArray,
        surface: FloatArray,
        years: Double,
        receiver: IntArray? = null,
        undrainedShare: FloatArray? = null
    ): Double {
        require(receiver == null || undrainedShare == null) { "one net form of the sub-grid transport at a time" }
        val cellCount = cellsAcross * cellsDown
        // Each cell's diffusivity on its faces across a row and on its faces down a column.
        val acrossRowDiffusivity = DoubleArray(cellCount)
        val downColumnDiffusivity = DoubleArray(cellCount)
        var fastest = 0.0
        for (cell in 0 until cellCount) {
            if (!isLand[cell]) continue
            var whole = rates.subGridDiffusivity * erodibility[cell] * sqrt(runoff[cell].coerceAtLeast(0f).toDouble())
            if (undrainedShare != null) whole *= undrainedShare[cell].coerceIn(0f, 1f)
            val alongRowShareOfFall = if (receiver != null) {
                receiverBearingAlongRowSquared(rates, cellsAcross, cell, receiver[cell])
            } else null
            // The receiver's bearing squared along the row is what the face across the row loses,
            // and its bearing down the column what the face down the column loses: I - u u^T.
            acrossRowDiffusivity[cell] = if (alongRowShareOfFall != null) whole * (1.0 - alongRowShareOfFall) else whole
            downColumnDiffusivity[cell] = if (alongRowShareOfFall != null) whole * alongRowShareOfFall else whole
            if (whole > fastest) fastest = whole
        }
        if (fastest <= 0.0) return 0.0
        val acrossRow = 1.0 / (rates.cellWidthMetres * rates.cellWidthMetres)
        val downColumn = 1.0 / (rates.cellHeightMetres * rates.cellHeightMetres)
        val steps = kotlin.math.ceil(years * fastest * (2 * acrossRow + 2 * downColumn) / 0.2).toInt().coerceAtLeast(1)
        val dt = years / steps
        val height = DoubleArray(cellCount) { surface[it].toDouble() }
        val change = DoubleArray(cellCount)
        val shore = shorelineHeight.toDouble()
        var toSea = 0.0
        repeat(steps) {
            change.fill(0.0)
            for (cell in 0 until cellCount) {
                if (!isLand[cell]) continue
                val row = cell / cellsAcross
                val column = cell % cellsAcross
                // East and south faces between land cells, each once.
                val east = row * cellsAcross + (column + 1) % cellsAcross
                val faces = if (row + 1 < cellsDown) 2 else 1
                for (face in 0 until faces) {
                    val other = if (face == 0) east else cell + cellsAcross
                    val weight = if (face == 0) acrossRow else downColumn
                    val faceDiffusivity = if (face == 0) acrossRowDiffusivity else downColumnDiffusivity
                    if (isLand[other]) {
                        val flux = 0.5 * (faceDiffusivity[cell] + faceDiffusivity[other]) * weight * dt * (height[cell] - height[other])
                        change[cell] -= flux
                        change[other] += flux
                    }
                }
                // Every face onto the sea, from this cell's side.
                if (height[cell] > shore) {
                    var out = 0.0
                    val west = row * cellsAcross + (column - 1 + cellsAcross) % cellsAcross
                    val acrossRowRate = acrossRowDiffusivity[cell] * acrossRow * dt * (height[cell] - shore)
                    val downColumnRate = downColumnDiffusivity[cell] * downColumn * dt * (height[cell] - shore)
                    if (!isLand[east]) out += acrossRowRate
                    if (!isLand[west]) out += acrossRowRate
                    if (row > 0 && !isLand[cell - cellsAcross]) out += downColumnRate
                    if (row + 1 < cellsDown && !isLand[cell + cellsAcross]) out += downColumnRate
                    out = minOf(out, height[cell] - shore)
                    change[cell] -= out
                    toSea += out
                }
            }
            for (cell in 0 until cellCount) if (isLand[cell]) height[cell] += change[cell]
        }
        for (cell in 0 until cellCount) if (isLand[cell]) surface[cell] = height[cell].toFloat()
        return toSea
    }

    /**
     * `u_x^2`, the square of the along-row component of the unit vector from [cell] to [receiver] on
     * the ground, a cell width across a row and a row's height down a column, the world wrapping
     * east-west; its down-column component squared is one less this. Nought, so that neither face
     * loses anything, where the cell has no receiver.
     */
    private fun receiverBearingAlongRowSquared(rates: Rates, cellsAcross: Int, cell: Int, receiver: Int): Double? {
        if (receiver < 0) return null
        var columnStep = receiver % cellsAcross - cell % cellsAcross
        if (columnStep > 1) columnStep -= cellsAcross
        if (columnStep < -1) columnStep += cellsAcross
        val rowStep = receiver / cellsAcross - cell / cellsAcross
        val alongRowMetres = columnStep * rates.cellWidthMetres
        val downColumnMetres = rowStep * rates.cellHeightMetres
        return alongRowMetres * alongRowMetres / (alongRowMetres * alongRowMetres + downColumnMetres * downColumnMetres)
    }

    /**
     * The level a cell draining into [receiver] grades to, in the height field's unit: the round's
     * shoreline where the receiver is sea; where the receiver stands under a filled basin's water,
     * that water's surface as this pass leaves it ([waterSurface], already set, receivers being
     * first), or the receiver's ground if the falling water has uncovered it; and otherwise the
     * receiver's own ground in [surface]. See [incise].
     */
    private fun baseLevel(
        receiver: Int,
        surface: FloatArray,
        isLand: BooleanArray,
        waterSurface: FloatArray,
        shorelineHeight: Float
    ): Float = when {
        !isLand[receiver] -> shorelineHeight
        !waterSurface[receiver].isNaN() -> maxOf(surface[receiver], waterSurface[receiver])
        else -> surface[receiver]
    }

    /**
     * How far a cell may be raised before it stands as high as the ground that drains into it.
     *
     * This is the rule that keeps deposition from inventing uphill rivers. The drainage order
     * visits everything upstream of a cell before the cell itself, so their final heights for the
     * round are already known and the margin is exact rather than estimated. A headwater has
     * nothing above it to dam, so it is allowed the drop below it instead.
     */
    private fun headroom(
        cellsAcross: Int,
        cellsDown: Int,
        cell: Int,
        drop: Float,
        directions: IntArray,
        settled: FloatArray,
        grade: Float,
        steps: GroundSteps
    ): Float {
        var room = Float.MAX_VALUE
        var fed = false
        FlowRouting.forEachNeighbour(
            cellsAcross,
            cellsDown,
            cell % cellsAcross,
            cell / cellsAcross
        ) { neighbour ->
            if (directions[neighbour] == cell) {
                fed = true
                // The margin up to the feeder, less the fall the channel needs to keep over that
                // step. At grade this is nought and the cell stops rising; on a reach steeper than
                // the river needs it is positive and the floor creeps up toward grade.
                val step = steps.between(neighbour, cell, cellsAcross)
                val margin = settled[neighbour] - settled[cell] - grade * step
                if (margin < room) room = margin
            }
        }
        // The grade is already taken off each feeder's margin above; a cell with no feeder at all
        // has only the fall to its own receiver to play with, and it must keep the grade out of
        // that too.
        return if (fed) room else drop - grade
    }

    /**
     * Lays [budget] of sediment into the water around a mouth, nearest cells first, building each
     * up to [levelOf] and no higher.
     *
     * Breadth-first from the receiving cell out to [reachCells], so a delta grows from the mouth
     * outward the way a real one does, and a big river's load spreads over more of the shelf than
     * a small one's. Cells are enumerated in a fixed order and marked with a stamp rather than
     * collected in a set, so the result does not depend on any hash ordering.
     *
     * @return how much was actually laid down. Whatever the fill could not place — because the
     *   water was too deep, or the reach ran out — is the caller's to account for.
     */
    private inline fun fan(
        cellsAcross: Int,
        cellsDown: Int,
        start: Int,
        budget: Double,
        reachCells: Int,
        stamp: IntArray,
        id: Int,
        queue: IntArray,
        distanceAt: IntArray,
        surfaceOf: FloatArray,
        sediment: FloatArray,
        settled: FloatArray,
        toRelative: Float,
        wholeCells: Boolean,
        log: DepositionLog?,
        mark: Byte,
        accepts: (Int, Int) -> Boolean,
        levelOf: (Int, Int) -> Float
    ): Double {
        if (budget <= 0.0 || !accepts(start, 0)) return 0.0

        var remaining = budget
        var laid = 0.0
        var head = 0
        var tail = 0
        queue[tail] = start
        distanceAt[tail] = 0
        tail++
        stamp[start] = id

        while (head < tail && remaining > 0.0) {
            val cell = queue[head]
            val stepsFromApex = distanceAt[head]
            head++

            // Whole cells only.
            //
            // A cell the budget can only half fill is a cell left under water, and once the lobe
            // has grown past it in a later round it is under water with land all around it: an
            // enclosed pocket of sea, which is where a river was seen to stop dead a few cells
            // short of the coast. Forty-two of a hundred and fifty-two mouths on seed 59758 at 2048
            // ended in one. Leaving the shortfall unspent instead costs nothing — it disperses
            // offshore with the rest of the load, which is where the other six sevenths of it was
            // going anyway — and it makes "every cell of a lobe stands above the water" true by
            // construction rather than by luck.
            val need = levelOf(cell, stepsFromApex).toDouble() -
                surfaceOf[cell].toDouble() - sediment[cell].toDouble()
            if (need > 0.0) {
                if (wholeCells && need > remaining) break
                val moved = raise(sediment, cell, if (need < remaining) need else remaining)
                settled[cell] += (moved * toRelative).toFloat()
                remaining -= moved
                laid += moved
                log?.record(cell, mark, start, moved)
            }

            if (stepsFromApex >= reachCells) continue
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            for (rowStep in -1..1) {
                val neighbourRow = row + rowStep
                if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                for (columnStep in -1..1) {
                    if (columnStep == 0 && rowStep == 0) continue
                    var neighbourColumn = (column + columnStep) % cellsAcross
                    if (neighbourColumn < 0) neighbourColumn += cellsAcross
                    val neighbour = neighbourRow * cellsAcross + neighbourColumn
                    if (stamp[neighbour] == id || !accepts(neighbour, stepsFromApex + 1)) continue
                    stamp[neighbour] = id
                    if (tail < queue.size) {
                        queue[tail] = neighbour
                        distanceAt[tail] = stepsFromApex + 1
                        tail++
                    }
                }
            }
        }
        return laid
    }

    /**
     * Adds [amount] to one cell and reports what the field actually took.
     *
     * The terrain is a float array and the amounts moved in a single round are, cell by cell, very
     * much smaller than the elevations they are added to, so an increment can round away entirely.
     * Reading the change back rather than assuming it is what keeps the mass budget honest about
     * the terrain rather than about its own arithmetic.
     */
    private fun raise(field: FloatArray, cell: Int, amount: Double): Double {
        val prior = field[cell]
        field[cell] = (prior.toDouble() + amount).toFloat()
        return field[cell].toDouble() - prior.toDouble()
    }

    /** Summed in double, because a million floats added in float order lose the small changes. */
    private fun totalMass(values: FloatArray): Double {
        var sum = 0.0
        for (value in values) sum += value.toDouble()
        return sum
    }

    /** The shoreline on the shoreline-relative field, which is where that field is zero. */
    private const val SHORELINE = 0f

    /** Millimetres in a metre, for the uplift rates this stage is handed in millimetres a year. */
    private const val MILLIMETRES_PER_METRE = 1_000.0

}
