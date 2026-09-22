package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.RiverConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Where a channel begins: the drainage area a slope has to gather before running water can cut one.
 *
 * Montgomery and Dietrich (1988, 1989; Dietrich et al. 1993) surveyed channel heads in the field and
 * found that the support area above one is not a constant — it runs from one to ten hectares on a
 * humid steepland to square kilometres on an arid lowland, two to three orders of magnitude — but
 * that it falls along a line against the local gradient. **Their own fit over the steepland heads is
 * `A ∝ S^-1.65`**, so the quantity that is nearly one number for a region is `A · S^1.65`, and that
 * is what this file thresholds on. The exponent is theirs and is not the two that gets quoted:
 * `A · S^2` is Dietrich et al.'s form for the *landsliding* threshold, a different way for a hollow
 * to become a channel, and using it here would be citing one regime's paper for another's law.
 *
 * **The regime this criterion is about is the shear-stress channel head**: overland flow gathering
 * down a hollow until the stress it exerts beats what holds the surface together. That is why the
 * two terms below are the ones they are. The area is **weighted by runoff**, so a wet hillside
 * reaches the threshold on less ground than a dry one; and the threshold itself **rises with the
 * plant cover**, because what the flow has to cut is the ground's resistance and roots raise that
 * by two orders of magnitude. The two pull opposite ways as a country gets wetter, which is why
 * drainage density on Earth peaks in semi-arid country and falls away on both sides (Langbein &
 * Schumm 1958; Moglen, Eltahir & Bras 1998) instead of simply tracking the rain — runoff alone
 * could not produce that, since more rain only ever puts more cells over a fixed bar.
 *
 * Everything here is in square kilometres and in dimensionless gradient, converted out of
 * `WorldScale` once, so the same ground gives the same network at 512 and at 2048 and
 * `WorldGenConfig.atResolution` has nothing to carry. That is the whole of what this replaces: the
 * old rule drew a channel where the accumulated runoff passed a share of the world's own total,
 * capped the drawing at a count of courses and dropped stubs under a count of cells, and all three
 * meant a different thing at every grid.
 *
 * See docs/DESIGN_LEDGER.md, R1, and docs/GEOGRAPHY.md on channel initiation.
 */
object ChannelInitiation {

    /**
     * The exponent the gradient enters the threshold at: Montgomery and Dietrich's own 1.65.
     *
     * Read off their steepland fit, `A ∝ S^-1.65`. Two is what gets quoted most often and it
     * belongs to a different question — Dietrich et al.'s landsliding threshold, where a hollow
     * fails rather than being cut — so it is not used here. See the class note.
     */
    const val GRADIENT_EXPONENT = 1.65

    /**
     * The threshold on drainage area times the gradient to [GRADIENT_EXPONENT], in square
     * kilometres, over bare ground.
     *
     * From Montgomery and Dietrich's steepland heads, which is where their exponent comes from: a
     * support area of five hectares — the middle of the one-to-ten they report — at a head gradient
     * of 0.4 gives `0.05 × 0.4^1.65 = 0.011` km².
     *
     * **Extrapolated to lowlands rather than re-fitted there**, and it is worth saying how. Their
     * arid lowland sites, a support area of about three square kilometres at a gradient near 0.05,
     * read `3 × 0.05^1.65 = 0.021` — twice this, not two hundred times it, which is the finding
     * those papers are about: the support area spans three orders of magnitude between the two site
     * classes and the product spans a factor of two. A factor of two is well inside what the cover
     * term below spans on its own, and those two site classes differ in cover as well as in relief,
     * so re-fitting the constant per climate would be fitting the cover term twice over. The
     * steepland value is taken because it is the one their exponent was measured with.
     *
     * "Over bare ground" because [coverFactor] raises it where there is a canopy.
     *
     * **What this criterion can and cannot resolve at these grids.** A cell is about 275 km² at 512
     * and 17 at 2048, and Montgomery and Dietrich's humid support areas are 0.01 to 0.1 km² — an
     * area far below one cell at either grid. So in wet country the *area* side of this criterion
     * is settled before it is asked: a cell's own ground already carries more catchment than a
     * humid channel head needs, and what decides whether it carries a channel is the gradient and
     * the cover. **No guard here places an individual channel head, and none could.** What R1 can
     * resolve is the *aridity dependence*: the threshold reaches 0.4 to 0.6 km² under a closed
     * canopy and a dry lowland's support area runs to square kilometres, which spans cells at 2048
     * and so is a difference the grid can draw. Every guard on this chunk is about that dependence
     * and about the network's shape.
     */
    const val CHANNEL_HEAD_AREA_SLOPE_KM2 = 0.011f

    /**
     * How much more resistant to being cut a closed canopy makes the ground than bare soil, as a
     * factor on the threshold.
     *
     * Two hundred, and it is the universal soil-loss equation's cover-management factor read
     * backwards. `C` in that equation is 1.0 on bare tilled soil and 0.005 or less under a closed
     * forest canopy (Wischmeier & Smith 1978), which is the same statement as "the same rainfall
     * erodes two hundred times less" — and a surface two hundred times harder to cut needs two
     * hundred times the area-gradient product before running water can open a head in it. Five
     * thousandths is the *conservative* end of the published range; a tenth of that is quoted for
     * undisturbed rainforest, and this takes the smaller claim.
     *
     * **This is the one number here that Montgomery and Dietrich's field range does not settle**,
     * and it is worth saying so. Their two site classes differ in relief and in cover at the same
     * time, so a product that agrees across them to a factor of two constrains neither term on its
     * own. Moglen, Eltahir and Bras (1998) require the cover term to be the larger of the two above
     * semi-arid country, or their curve does not turn over; the soil-loss equation is where the
     * size of it comes from. `RiverConfig.coverRaisesChannelHead` turns it off, and that is the
     * control `ChannelInitiationControlTest` shows the drainage clauses failing against.
     */
    const val CLOSED_CANOPY_RESISTANCE_GAIN = 200.0

    /**
     * The rainfall the runoff weight is measured against, in millimetres a year: Earth's mean over
     * land, about 715 (Legates & Willmott's land mean, quoted at 700-750 in every later census).
     *
     * An absolute reference and not the world's own mean, which is what makes the weight a physical
     * quantity. Against the world's mean a world twice as wet would draw the same network, because
     * every cell's weight would be divided by twice as much; against Earth's it draws a denser one.
     */
    const val EARTH_MEAN_LAND_RAINFALL_MM = 715f

    /**
     * Runoff a cell sheds over and above its own rainfall, in millimetres a year.
     *
     * `RiverStage.RUNOFF_FLOOR` in the unit this file works in. That floor is 0.05 of
     * `ClimateResult.precipitation`, which is `precipitationMm / 3000`, so it is a hundred and fifty
     * millimetres a year — and it is here for the same reason it is there: an arid upland still
     * gathers a trickle from snowmelt and the odd storm, and its channels are cut by the rare storm
     * rather than by the annual mean. The two have to be the same number, or the stage would
     * accumulate water down a network drawn by a different rule about where water comes from.
     */
    const val RUNOFF_FLOOR_MM = 150f

    /**
     * How much the threshold is multiplied by over ground carrying [vegetationDensity] of cover:
     * one over bare ground and [CLOSED_CANOPY_RESISTANCE_GAIN] under a closed canopy.
     *
     * Exponential in the cover between those two ends, which is the shape the soil-loss literature
     * measures rather than a convenience: Elwell and Stocking (1976) fit soil loss against
     * percentage cover as a decaying exponential, and the USLE's own cover-management table is that
     * curve tabulated. A linear interpolation between the same two ends would put nine tenths of
     * the resistance in the last tenth of the canopy, which is the opposite of what a grass sward
     * does — the first thirty per cent of cover is where most of the protection is.
     */
    fun coverFactor(vegetationDensity: Float): Float {
        val cover = vegetationDensity.coerceIn(0f, 1f)
        return exp(ln(CLOSED_CANOPY_RESISTANCE_GAIN) * cover).toFloat()
    }

    /** A cell's runoff as a share of Earth's mean over land — see the two constants above. */
    fun runoffShareOfEarthMean(precipitationMm: Float): Float =
        (precipitationMm + RUNOFF_FLOOR_MM) / EARTH_MEAN_LAND_RAINFALL_MM

    /**
     * The drainage area above each cell in square kilometres, each contributing cell counted in
     * proportion to the runoff it sheds.
     *
     * The generator's own accumulation over the generator's own D8 tree, with a weight of
     * [runoffShareOfEarthMean] times the ground one cell stands for. Where the runoff is Earth's
     * mean this is the plain catchment area; where it is a fifth of that the catchment counts for a
     * fifth of its ground, which is the climate term in [isChannelHead]'s left-hand side rather
     * than in its threshold.
     *
     * Not `RiverResult.flowAccumulation`, which is the same walk over the same runoff on a 0..1
     * scale: that field is a relative quantity the width and the erosion read against their own
     * maxima, and this one has to be square kilometres or the threshold is not an area.
     */
    fun runoffWeightedAreaKm2(
        cellsAcross: Int,
        cellsDown: Int,
        isLand: BooleanArray,
        landCellCount: Int,
        filled: FloatField,
        flowTarget: IntArray,
        precipitationMm: FloatField,
        squareKilometresPerCell: Double
    ): FloatField {
        val cellAreaKm2 = squareKilometresPerCell.toFloat()
        return FlowRouting.accumulate(
            cellsAcross, cellsDown, isLand, filled, flowTarget, landCellCount
        ) { cell -> runoffShareOfEarthMean(precipitationMm.data[cell]) * cellAreaKm2 }
    }

    /**
     * Whether a cell of this much runoff-weighted area, on this gradient, under this much cover,
     * carries a channel head.
     *
     * [areaKm2] is [runoffWeightedAreaKm2]'s and [gradient] is the dimensionless fall of the **true
     * ground** to the cell's own receiver — see [gradientToReceiver] for why it is never the
     * routing surface's.
     */
    fun isChannelHead(
        areaKm2: Float,
        gradient: Float,
        vegetationDensity: Float,
        config: RiverConfig
    ): Boolean {
        if (gradient <= 0f) return false
        val cover = if (config.coverRaisesChannelHead) coverFactor(vegetationDensity) else 1f
        val areaSlope = areaKm2 * gradient.toDouble().pow(GRADIENT_EXPONENT).toFloat()
        return areaSlope >= config.channelHeadAreaSlopeKm2 * cover
    }

    /**
     * Every cell the terrain carries a channel through: the heads, and everything downstream of
     * one.
     *
     * The second half is not a convenience, and since F30b it is load-bearing. Discharge only grows
     * downstream, so a channel that has started cannot stop; and the gradient this criterion reads
     * is the *true ground's*, which across a filled flat is zero or uphill, so a reach crossing one
     * initiates nothing of its own. Without this rule a river would be drawn in two pieces with the
     * flat between them blank. Walked over [FlowRouting.drainageOrder], which puts every cell after
     * everything draining into it, so one pass settles the whole forest — and `ChannelGapTest`
     * asserts what it buys: no channel cell drains into a land cell that is not one.
     *
     * Open water is out of the mask for the reason `RiverStage.traceRivers` gives — a lake is not a
     * reach of river — and a playa is in, because it is dry ground most of the year.
     */
    fun channelMask(
        config: WorldGenConfig,
        isLand: BooleanArray,
        landCellCount: Int,
        ground: FloatField,
        filled: FloatField,
        flowTarget: IntArray,
        climate: ClimateResult,
        isOpenWater: (Int) -> Boolean
    ): BooleanArray {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val channel = BooleanArray(cellCount)
        if (landCellCount == 0) return channel

        val areaKm2 = runoffWeightedAreaKm2(
            cellsAcross, cellsDown, isLand, landCellCount, filled, flowTarget,
            climate.precipitationMm, config.squareKilometresPerCell
        )
        val gradient = gradientToReceiver(config, isLand, ground, flowTarget)
        val vegetationDensity = climate.vegetationDensity

        for (cell in 0 until cellCount) {
            if (!isLand[cell] || isOpenWater(cell)) continue
            if (neverThaws(climate, cell)) continue
            channel[cell] = isChannelHead(
                areaKm2.data[cell], gradient[cell], vegetationDensity.data[cell], config.rivers
            )
        }

        val order = FlowRouting.drainageOrder(
            cellsAcross, cellsDown, isLand, flowTarget, landCellCount
        )
        for (cell in order) {
            if (!channel[cell]) continue
            val receiver = flowTarget[cell]
            if (receiver >= 0 && isLand[receiver] && !isOpenWater(receiver)) channel[receiver] = true
        }
        return channel
    }

    /**
     * The same mask for a world that has already been generated, rebuilt from what the save
     * carries.
     *
     * Nothing stores the mask: it is a handful of fields' worth of arithmetic over arrays the world
     * already holds, and a boolean a cell is four megabytes at 2048. The guards and the render
     * harnesses call this so that what they measure is the network the stage drew and not a second
     * definition of one.
     */
    fun channelMaskOf(world: WorldMap): BooleanArray = channelMask(
        world.config, world.sea.isLand, world.sea.landCellCount, world.sea.relativeElevation,
        world.rivers.filledElevation, world.rivers.flowTarget, world.climate
    ) { world.rivers.lakes.isOpenWater(it) }

    /**
     * Ground that never thaws, and so never starts a channel of its own: ground whose own warmest
     * month stays at or below freezing.
     *
     * `ClimateResult.summerTemperature` is the cell's warmest month wherever it lies, so this is
     * the whole of the question — a cell that never rises above freezing is under perennial snow or
     * ice or is a polar desert on frozen ground, water does not run over it in any season, and
     * nothing there cuts a head. What crosses it from warmer ground upstream is still a channel —
     * the downstream rule in [channelMask] carries it — which is the Lena and the Yenisey, rivers
     * that rise where the summer thaws and run on over ground that does not.
     *
     * **Read off the thermometer, and no longer off Thornthwaite's demand.** The demand is zero on
     * exactly this ground, so it gave the right answer and was the tidier way to say it; but the
     * demand that was run is [LakeWaterBalance.potentialEvaporationMm], whose last act is to
     * multiply by `LakesConfig.evaporationScale`. That figure is a setting on how hard a lake's
     * surface evaporates, and a world that turns it off got a demand of zero everywhere: every land
     * cell read as permanently frozen, no cell anywhere passed the head test, and the map came out
     * with no channel on it at all. Seed 42 at 512 initiates 52,525 channel cells and initiated
     * none. Whether water runs over a hillside in summer is not a lake's business, and the rule now
     * asks the field that answers it.
     *
     * A head rule and not a mask over the network, for exactly that reason, and it is why the
     * drainage densities this criterion produces can be read as a curve against aridity at all:
     * without it the coldest country came out as the most finely dissected on the map, on cells
     * where no water runs.
     */
    fun neverThaws(climate: ClimateResult, cell: Int): Boolean =
        climate.summerTemperature.data[cell] <= FREEZING_C

    /** Where water stops running, in degrees Celsius. */
    const val FREEZING_C = 0f

    /**
     * The dimensionless fall from each land cell to the cell it drains into, on the **true ground**:
     * metres of fall over metres of ground, and zero where the ground does not fall.
     *
     * The true ground and never the routing surface, and the difference is the whole of what a
     * gradient means here. The depression fill raises a basin to its spill level, and since F30b a
     * potential is laid across the flats it makes so that water can be routed over them; both are
     * bookkeeping that let a receiver be chosen, and neither is a slope. Read off the fill, a flat
     * would report the hair the flood nudges each cell by — a gradient of about 1e-7 that is an
     * artefact of the sweep's direction — and read off the potential it would report a number with
     * no length in it at all. Read off the ground, a flat reports zero, which is true: there is no
     * gradient there and no channel head, and the reach that crosses it is carried by the
     * downstream rule in [channelMask].
     *
     * Zero, too, where the ground *rises* to the receiver, which is every cell inside a filled
     * basin below its rim. That is the same statement.
     */
    fun gradientToReceiver(
        config: WorldGenConfig,
        isLand: BooleanArray,
        ground: FloatField,
        flowTarget: IntArray
    ): FloatArray {
        val cellsAcross = config.width
        val scale = config.scale
        val cellWidthMetres = (config.cellWidthKm * 1000.0).toFloat()
        val cellHeightMetres = (config.cellHeightKm * 1000.0).toFloat()
        val diagonalMetres =
            sqrt(cellWidthMetres * cellWidthMetres + cellHeightMetres * cellHeightMetres)
        val gradient = FloatArray(cellsAcross * config.height)
        for (cell in gradient.indices) {
            if (!isLand[cell]) continue
            val receiver = flowTarget[cell]
            if (receiver < 0) continue
            val fallMetres = scale.metresAboveShoreline(ground.data[cell]) -
                scale.metresAboveShoreline(ground.data[receiver])
            if (fallMetres <= 0f) continue
            var columnStep = (receiver % cellsAcross) - (cell % cellsAcross)
            if (columnStep > cellsAcross / 2) columnStep -= cellsAcross
            if (columnStep < -cellsAcross / 2) columnStep += cellsAcross
            val rowStep = (receiver / cellsAcross) - (cell / cellsAcross)
            val stepMetres = when {
                columnStep != 0 && rowStep != 0 -> diagonalMetres
                columnStep != 0 -> cellWidthMetres
                else -> cellHeightMetres
            }
            gradient[cell] = fallMetres / stepMetres
        }
        return gradient
    }
}
