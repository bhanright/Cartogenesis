package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.GroundSteps
import kotlin.math.sqrt

/**
 * The two heights of every cell, and what one hydraulic round does to the ground between them.
 *
 * [bed] and the ground (the stage's own height field, which the caller holds) are both in the
 * height field's units, one entry per cell, row-major. The bed is the surface the rounds route the
 * water over and the stream-power law cuts; the ground is the cell's mean, the surface the sea, the
 * climate and the plate read and the stage hands on. The bed never stands above the ground. See
 * [GroundClosure] for the law between them.
 *
 * What lives here is the round's arithmetic over the grid: which cells carry a channel and what
 * shape their in-cell ground is ([shape]), how far the ground follows the bed ([closeChannels],
 * [closeHillslopes]), and how a fill or a cut is shared between the two heights so that the
 * volume is the same whichever height it is measured on.
 */
internal class GroundCells(cellCount: Int, initialGround: FloatArray) {

    /** The bed, in the height field's units; equal to the ground until the first channel is cut. */
    val bed: FloatArray = initialGround.copyOf()

    /**
     * The share of each cell the trunk's bed covers, `s_b`, 0..1: set each round on channel cells,
     * one on a cell below every head (whose two heights are one), and kept where a cell is under
     * water and the round leaves it alone.
     */
    val bedShare: FloatArray = FloatArray(cellCount) { 1f }

    /**
     * The rate the base of each cell's hillslopes lowered at in the last round, in metres a year
     * against the rock: on a channel cell its in-cell network's (the trunk's, where it has no
     * network), on a cell below every head the first channel's downstream, or the uplift's where
     * the slope runs to the sea. What [shape] reconstructs a channel head's gradient from (see
     * [GroundClosure.headSupportAreaSquareMetres]). Zero before the first round.
     */
    val baseLoweringMetresPerYear: FloatArray = FloatArray(cellCount)

    /**
     * The relief of each channel cell's in-cell network over its trunk, in metres: the mean height
     * of the beds of the channels the cell holds and the grid does not, the piece of the
     * interfluves' relief that follows the trunk on the network's own response time (see
     * [GroundClosure]). Never more than the cell's whole relief; zero on a cell whose two heights
     * are one.
     */
    val networkReliefMetres: FloatArray = FloatArray(cellCount)

    /** Whether each cell carries a channel this round: a head in or above it, or a channel above it. */
    val isChannel: BooleanArray = BooleanArray(cellCount)

    /** Whether each cell is land below every channel head this round, its two heights one. */
    val isHillslope: BooleanArray = BooleanArray(cellCount)

    /** The channel head's geometric support area for each land cell, in square metres; infinite where none forms. */
    val headAreaSquareMetres: FloatArray = FloatArray(cellCount)

    /** On channel cells, the in-cell hillslope's length `L_h`, in metres. */
    val hillslopeLengthMetres: FloatArray = FloatArray(cellCount)

    /** On channel cells, the in-cell network's factor `N(r)` ([GroundClosure.networkFactor]). */
    val networkFactor: FloatArray = FloatArray(cellCount)

    /** On channel cells, the interfluves' height before this round's cut, in the field's units. */
    private val interfluve = FloatArray(cellCount)

    /** Whether a channel drains into each cell this round; scratch for [shape]. */
    private val fedByChannel = BooleanArray(cellCount)

    /** Scratch for [closeHillslopes]: each hillslope cell's foot, in the field's units, and its distance to it in metres. */
    private val footLevel = FloatArray(cellCount)
    private val footDistanceMetres = FloatArray(cellCount)

    /**
     * How high the interfluves stand above the bed, in the field's units: `(ground - bed) / (1 - s_b)`,
     * which is the ground's mean less the bed's share, over the hillslopes' share. Zero on a cell
     * whose two heights are one.
     */
    fun reliefAboveBed(cell: Int, ground: FloatArray): Double {
        val share = bedShare[cell].toDouble()
        if (share >= 1.0) return 0.0
        val difference = ground[cell].toDouble() - bed[cell].toDouble()
        return if (difference > 0.0) difference / (1.0 - share) else 0.0
    }

    /**
     * Lowers the bed of [cell] by [bedDrop] (already taken off [bed] by the caller) and the ground
     * by what that cut removes, `s_b * bedDrop`: the channel deepens and the interfluves stand as
     * they were. Returns the volume removed, per unit of the cell's area, as the ground actually
     * took it.
     */
    fun groundFollowsCut(cell: Int, bedDrop: Double, ground: FloatArray): Double {
        if (bedDrop <= 0.0) return 0.0
        val share = bedShare[cell].toDouble()
        // A cell whose bed stood at its ground before the cut is one height: the whole of it went.
        val wanted = if (bed[cell] + bedDrop >= ground[cell]) bedDrop else share * bedDrop
        val prior = ground[cell]
        val lowered = (prior.toDouble() - wanted).toFloat()
        // The ground never stands below the bed: where a cut takes the bed past the ground's mean,
        // the cell has no interfluve left and its two heights are one.
        ground[cell] = if (lowered < bed[cell]) bed[cell] else lowered
        return prior.toDouble() - ground[cell].toDouble()
    }

    /** How far a held fill of [volume] would raise [cell]'s bed, on its hypsometry as the ground now stands. */
    fun bedRiseFor(cell: Int, volume: Double, ground: FloatArray): Double =
        GroundClosure.bedRiseForFill(volume, bedShare[cell].toDouble(), reliefAboveBed(cell, ground))

    /** How much of [cell] is under water standing [riseOfBed] over its bed, on its hypsometry as the ground now stands. */
    fun floodedShare(cell: Int, riseOfBed: Double, ground: FloatArray): Double =
        GroundClosure.floodedShare(riseOfBed, bedShare[cell].toDouble(), reliefAboveBed(cell, ground))

    /** The fill that raises [cell]'s bed from [heldRise] above it to [heldRise] + [rise]. */
    fun fillToRaiseBed(cell: Int, heldRise: Double, rise: Double, ground: FloatArray): Double {
        val share = bedShare[cell].toDouble()
        val relief = reliefAboveBed(cell, ground)
        return GroundClosure.fillForBedRise(heldRise + rise, share, relief) -
            GroundClosure.fillForBedRise(heldRise, share, relief)
    }

    /**
     * What the last [lay] could not hold: the held volume less what the ground's floats took, per
     * unit of a cell's area summed over the map. A float added to a float rounds, and the budget is
     * counted on what the field holds; the caller accounts this as material that left.
     */
    var layRounding: Double = 0.0
        private set

    /**
     * Lays the held [fill] on both heights: the ground rises by the volume, the bed by the level
     * that volume reaches on the cell's hypsometry. Returns how many cells' beds had to be held
     * under their ground by rounding.
     */
    fun lay(fill: FloatArray, ground: FloatArray): Int {
        var clamped = 0
        layRounding = 0.0
        for (cell in fill.indices) {
            val volume = fill[cell].toDouble()
            if (volume <= 0.0) continue
            val rise = bedRiseFor(cell, volume, ground)
            val prior = ground[cell]
            ground[cell] = (ground[cell].toDouble() + volume).toFloat()
            layRounding += volume - (ground[cell].toDouble() - prior.toDouble())
            bed[cell] = (bed[cell].toDouble() + rise).toFloat()
            if (bed[cell] > ground[cell]) {
                bed[cell] = ground[cell]
                clamped++
            }
        }
        return clamped
    }

    /**
     * Brings the bed back to the ground after the thermal sweeps have moved the ground alone:
     * a cell below every head is one height and its bed is its ground, wherever the sweeps left
     * it; any other cell's bed is held at or under its ground. Moves no material, the ground being
     * the mass. Returns how many two-height cells had to be held, which is the `min(bed, ground)`
     * the closure is meant to make redundant.
     */
    fun holdBedUnderGround(ground: FloatArray): Int {
        var held = 0
        for (cell in bed.indices) {
            if (isHillslope[cell]) {
                bed[cell] = ground[cell]
            } else if (bed[cell] > ground[cell]) {
                bed[cell] = ground[cell]
                held++
            }
        }
        return held
    }

    /** What one cell of the grid measures, converted once for the round. */
    class Ruler(
        val cellWidthMetres: Double,
        val cellAreaSquareMetres: Double,
        val steps: GroundSteps,
        /** Metres in one unit of the height field. */
        val metresPerFieldUnit: Double,
        /** The years one round stands for. */
        val years: Double,
        /** The stream-power coefficient `K`, per year. */
        val erodibilityPerYear: Double
    )

    /**
     * Which land cells carry a channel this round and what shape each cell's ground is.
     *
     * A cell carries a channel where its catchment at the outlet, weighted by runoff against
     * Earth's mean, reaches the head's threshold `A_c S^1.65 = 0.011 km2 x cover`
     * ([ChannelInitiation]'s constants, unchanged), with the head's gradient reconstructed from
     * the rate the base of the cell's slopes lowered at last round ([baseLoweringMetresPerYear],
     * [GroundClosure.headSupportAreaSquareMetres]); and wherever a
     * channel drains into it, because discharge only grows, so a channel crosses a flat the head
     * rule would not start one on. Under standing water a cell is neither, and passes the channel
     * on.
     *
     * On a channel cell: the trunk's length in the cell is the whole step where the channel enters
     * it and the stretch below the head where the head lies inside it, `l (A_out - A_c) / (A_out -
     * A_in)` in runoff-weighted area; the bed's share is a mean-annual channel width along that
     * length; the hillslopes reach `X = a (1 - s_b) / (2 l_ch)` from the trunk, no further than
     * the cell is wide; and `L_h = min(sqrt(A_c), X)` and `r = X / sqrt(A_c)`.
     *
     * [discharge] is the round's accumulation of [runoff], the runoff weight over its land mean;
     * [rainfallMm] the floored rainfall it was normalised from and [landMeanRainfallMm] that mean;
     * [order] the drainage order, sources first; [relativeBed] and [filledBed] the bed and its fill
     * in the routing's shoreline-relative units.
     */
    fun shape(
        cellsAcross: Int,
        isLand: BooleanArray,
        relativeBed: FloatArray,
        filledBed: FloatArray,
        directions: IntArray,
        order: IntArray,
        discharge: FloatArray,
        runoff: FloatArray,
        rainfallMm: FloatArray,
        landMeanRainfallMm: Double,
        vegetationDensity: FloatArray,
        ground: FloatArray,
        shorelineHeight: Float,
        landHalfOfField: Float,
        pondDepth: Float,
        ruler: Ruler
    ) {
        isChannel.fill(false)
        isHillslope.fill(false)
        fedByChannel.fill(false)
        val cellArea = ruler.cellAreaSquareMetres
        val earthWeightedPerCell = landMeanRainfallMm / Runoff.EARTH_MEAN_LAND_RAINFALL_MM * cellArea
        val thresholdSquareMetres =
            ChannelInitiation.CHANNEL_HEAD_AREA_SLOPE_KM2 * GroundClosure.SQUARE_METRES_PER_SQUARE_KILOMETRE
        val cubicMetresPerSecondPerWeightedCell = GroundClosure.RUNOFF_SHARE_OF_RAINFALL *
            landMeanRainfallMm / MILLIMETRES_PER_METRE * cellArea / GroundClosure.SECONDS_PER_YEAR
        val widestReach = sqrt(cellArea)
        for (cell in order) {
            if (!isLand[cell]) continue
            val receiver = directions[cell]
            val drainsOnLand = receiver >= 0 && isLand[receiver]
            if (filledBed[cell] - relativeBed[cell] > pondDepth) {
                headAreaSquareMetres[cell] = Float.POSITIVE_INFINITY
                if (fedByChannel[cell] && drainsOnLand) fedByChannel[receiver] = true
                continue
            }
            val stepMetres =
                (if (receiver >= 0) ruler.steps.between(cell, receiver, cellsAcross) else ruler.steps.eastWest) *
                    ruler.cellWidthMetres
            // The fall from the cell's ground to the water surface it drains to: its receiver's bed
            // (or the lake standing on it), or the sea. Not to the receiver's ground, which
            // carries the receiver's own in-cell relief: a slope whose foot is a channel falls to
            // the channel.
            val fallMetres = when {
                receiver < 0 -> 0.0
                isLand[receiver] ->
                    (ground[cell] - (shorelineHeight + filledBed[receiver] * landHalfOfField)).toDouble() *
                        ruler.metresPerFieldUnit
                else -> (ground[cell] - shorelineHeight).toDouble() * ruler.metresPerFieldUnit
            }
            val resolvedGradient = if (fallMetres > 0.0) fallMetres / stepMetres else 0.0
            val earthShare = Runoff.shareOfEarthMean(rainfallMm[cell]).toDouble()
            val cover = ChannelInitiation.coverFactor(vegetationDensity[cell]).toDouble()
            val headArea = GroundClosure.headSupportAreaSquareMetres(
                thresholdSquareMetres * cover / earthShare, resolvedGradient, baseLoweringMetresPerYear[cell].toDouble()
            )
            headAreaSquareMetres[cell] = headArea.toFloat()
            val headWeighted = earthShare * headArea
            val outletWeighted = discharge[cell] * earthWeightedPerCell
            val inletWeighted = (discharge[cell] - runoff[cell]) * earthWeightedPerCell
            val channelEnters = fedByChannel[cell] || inletWeighted >= headWeighted
            if (!channelEnters && outletWeighted < headWeighted) {
                isHillslope[cell] = true
                bedShare[cell] = 1f
                networkReliefMetres[cell] = 0f
                continue
            }
            isChannel[cell] = true
            if (drainsOnLand) fedByChannel[receiver] = true
            val channelLengthMetres =
                if (channelEnters) stepMetres
                else stepMetres * (outletWeighted - headWeighted) / (outletWeighted - inletWeighted)
            val width = GroundClosure.channelWidthMetres(discharge[cell] * cubicMetresPerSecondPerWeightedCell)
            val share = GroundClosure.bedShareOf(width, channelLengthMetres, cellArea)
            bedShare[cell] = share.toFloat()
            val reach =
                if (channelLengthMetres > 0.0) minOf(cellArea * (1.0 - share) / (2.0 * channelLengthMetres), widestReach)
                else widestReach
            val headLength = sqrt(headArea)
            hillslopeLengthMetres[cell] = minOf(headLength, reach).toFloat()
            networkFactor[cell] = GroundClosure.networkFactor(reach / headLength).toFloat()
        }
    }

    /**
     * The interfluves' height on every channel cell before the round's cut, from the ground and the
     * bed as they stand: what [closeChannels] measures the relief the bed was cut into against.
     * Taken after [shape], so that a change in the bed's share between rounds is a change in how
     * the same ground is divided and not a change in the ground: the conservative repartition.
     */
    fun noteInterfluves(ground: FloatArray) {
        for (cell in interfluve.indices) {
            if (!isChannel[cell]) continue
            interfluve[cell] = (bed[cell].toDouble() + reliefAboveBed(cell, ground)).toFloat()
        }
    }

    /**
     * The ground of every channel cell after the round, its bed already cut by [bedCut] (per unit
     * of the cell's area, in the field's units): the cut climbs the in-cell network, which lowers
     * at the rate [GroundClosure.networkRateMetresPerYear] gives, and the network's lowering climbs
     * the hillslopes, which lower at the rate [GroundClosure.hillslopeRateMetresPerYear] solves for;
     * the ground is the bed's share at the new bed and the rest at the interfluves, lowered by the
     * hillslopes' rate. [production] is handed what each cell's ground lost, per unit of its area in
     * the field's units, read back from the stored floats. [erodibility] and [runoff] are the
     * round's relative cover factor and runoff weight.
     */
    fun closeChannels(
        ground: FloatArray,
        bedCut: DoubleArray,
        erodibility: FloatArray,
        runoff: FloatArray,
        ruler: Ruler,
        production: DoubleArray
    ) {
        val metres = ruler.metresPerFieldUnit
        val years = ruler.years
        for (cell in ground.indices) {
            if (!isChannel[cell]) continue
            val share = bedShare[cell].toDouble()
            val bedNow = bed[cell].toDouble()
            val cutMetres = bedCut[cell] * metres
            val newGround: Double
            if (share >= 1.0) {
                newGround = bedNow
                networkReliefMetres[cell] = 0f
                baseLoweringMetresPerYear[cell] = (cutMetres / years).toFloat()
            } else {
                val interfluveBefore = interfluve[cell].toDouble()
                // The relief the round opened with, the trunk's cut taken back off what stands over
                // the cut bed now; the network's share of it as last round left it, never more.
                val reliefBefore = ((interfluveBefore - bedNow) * metres - cutMetres).coerceAtLeast(0.0)
                val networkBefore = networkReliefMetres[cell].toDouble().coerceIn(0.0, reliefBefore)
                val hillslopeBefore = reliefBefore - networkBefore
                val erodibilityPrime = ruler.erodibilityPerYear * erodibility[cell] * sqrt(runoff[cell].toDouble())
                val response = GroundClosure.networkResponseYears(networkFactor[cell].toDouble(), erodibilityPrime)
                val networkRate = GroundClosure.networkRateMetresPerYear(networkBefore + cutMetres, response, years)
                val hillslopeRate = GroundClosure.hillslopeRateMetresPerYear(
                    hillslopeBefore + networkRate * years, hillslopeLengthMetres[cell].toDouble(), years
                )
                networkReliefMetres[cell] = (networkRate * response).toFloat()
                baseLoweringMetresPerYear[cell] = networkRate.toFloat()
                val interfluveAfter = interfluveBefore - hillslopeRate * years / metres
                newGround = share * bedNow + (1.0 - share) * maxOf(interfluveAfter, bedNow)
            }
            val prior = ground[cell]
            val lowered = newGround.toFloat()
            if (lowered < prior) {
                ground[cell] = if (lowered < bed[cell]) bed[cell] else lowered
                production[cell] += prior.toDouble() - ground[cell].toDouble()
            }
        }
    }

    /**
     * The ground of every cell below every channel head after the round: a stretch of hillslope
     * running from its divides to the first channel downstream (or the sea, or a lake's surface),
     * lowered at the rate [GroundClosure.stretchRateMetresPerYear] solves for on the steady profile
     * of that whole slope, its bed the same height. Walked receivers first, so each cell's foot is
     * known before the cells that drain to it. [cellsUpstream] is each cell's count of cells
     * draining through it, itself included, which with the step gives its place on the slope; the
     * stretch it stands for runs from `(n - 1) l` to `n l` from the divide, on a slope that ends at
     * the foot `d` beyond its centre, `d` the distance along the flow to the foot.
     *
     * Each cell's [baseLoweringMetresPerYear] is set to the rate its foot lowered at against the
     * rock this round, which is what the head rule reads next round: a channel foot's trunk cut
     * ([bedCut], per unit of a cell's area in the field's units), the sea's as the rock rises past it
     * ([upliftMetresPerYearAt], the rock's uplift rate at a cell, null where nothing rises), nothing at a lake's
     * surface, which rises and falls with the ground that holds it. A slope creeps far too slowly at
     * the grid's lengths to keep up with a foot that lowers, so the foot steepens it from the bottom
     * up, and that is where a channel head forms.
     */
    fun closeHillslopes(
        cellsAcross: Int,
        isLand: BooleanArray,
        filledBed: FloatArray,
        directions: IntArray,
        order: IntArray,
        cellsUpstream: FloatArray,
        ground: FloatArray,
        bedCut: DoubleArray,
        upliftMetresPerYearAt: ((Int) -> Double)?,
        shorelineHeight: Float,
        landHalfOfField: Float,
        ruler: Ruler,
        production: DoubleArray
    ) {
        val metres = ruler.metresPerFieldUnit
        for (rank in order.indices.reversed()) {
            val cell = order[rank]
            if (!isHillslope[cell]) continue
            footLevel[cell] = Float.NaN
            baseLoweringMetresPerYear[cell] = 0f
            bed[cell] = ground[cell]
            val receiver = directions[cell]
            if (receiver < 0) continue
            val stepMetres = ruler.steps.between(cell, receiver, cellsAcross) * ruler.cellWidthMetres
            val foot: Float
            val distance: Double
            when {
                !isLand[receiver] -> {
                    foot = shorelineHeight
                    distance = stepMetres
                    if (upliftMetresPerYearAt != null) baseLoweringMetresPerYear[cell] = upliftMetresPerYearAt(cell).toFloat()
                }
                isChannel[receiver] -> {
                    foot = bed[receiver]
                    distance = stepMetres
                    baseLoweringMetresPerYear[cell] = (bedCut[receiver] * metres / ruler.years).toFloat()
                }
                isHillslope[receiver] -> {
                    if (footLevel[receiver].isNaN()) continue
                    foot = footLevel[receiver]
                    distance = stepMetres + footDistanceMetres[receiver]
                    baseLoweringMetresPerYear[cell] = baseLoweringMetresPerYear[receiver]
                }
                // Under standing water: the hillslope ends at the water's surface.
                else -> { foot = shorelineHeight + filledBed[receiver] * landHalfOfField; distance = stepMetres }
            }
            footLevel[cell] = foot
            footDistanceMetres[cell] = distance.toFloat()
            val upstream = cellsUpstream[cell].toDouble().coerceAtLeast(1.0)
            val slopeLength = (upstream - 0.5) * stepMetres + distance
            val fromShare = (upstream - 1.0) * stepMetres / slopeLength
            val toShare = (upstream * stepMetres / slopeLength).coerceAtMost(1.0)
            val target = (ground[cell] - foot).toDouble() * metres
            val rate = GroundClosure.stretchRateMetresPerYear(target, slopeLength, fromShare, toShare, ruler.years)
            val prior = ground[cell]
            val lowered = (prior.toDouble() - rate * ruler.years / metres).toFloat()
            if (lowered < prior) {
                ground[cell] = lowered
                production[cell] += prior.toDouble() - lowered.toDouble()
            }
            bed[cell] = ground[cell]
        }
    }

    private companion object {
        const val MILLIMETRES_PER_METRE = 1_000.0
    }
}
