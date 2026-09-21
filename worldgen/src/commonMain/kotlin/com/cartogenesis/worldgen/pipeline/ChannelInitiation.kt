package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.RiverConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Where a channel begins: the drainage area a slope has to gather before running water can cut one.
 *
 * Montgomery and Dietrich (1988, 1989; Dietrich et al. 1993) measured channel heads in the field
 * and found the support area above them is not a constant — it runs from one to ten hectares on a
 * humid steepland to square kilometres on an arid lowland, two to three orders of magnitude — but
 * that the *product* of that area with the square of the local gradient is nearly one number for a
 * region. Their two extremes say so: five hectares at a head slope of 0.4 is `0.05 x 0.16 = 0.008`
 * square kilometres, and three square kilometres at a lowland's 0.05 is `3 x 0.0025 = 0.0075`. That
 * product is [CHANNEL_HEAD_AREA_SLOPE_SQUARED_KM2] and it is what this file thresholds on.
 *
 * Two things make it a criterion rather than a number. The area is **weighted by runoff**, so a wet
 * hillside reaches the threshold on less ground than a dry one and starts its channels closer
 * together; and the threshold itself **rises with the plant cover**, because what the water has to
 * beat at a channel head is the ground's critical shear stress and roots raise that several-fold.
 * The two pull opposite ways as a country gets wetter, which is why drainage density on Earth peaks
 * in semi-arid country and falls away on both sides (Langbein & Schumm 1958; Moglen, Eltahir & Bras
 * 1998) instead of simply tracking the rain.
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
     * The threshold on drainage area times the square of the gradient, in square kilometres, over
     * bare ground.
     *
     * Montgomery and Dietrich's own two ends, worked above: 0.008 from their humid steepland heads
     * and 0.0075 from their arid lowland ones. The agreement is the finding their papers are about
     * — the support area spans three orders of magnitude and `A x S^2` spans none — so eight
     * thousandths is a measurement and not a fitted number.
     *
     * "Over bare ground" because the shear stress a channel head has to beat is the bare soil's
     * here; [coverFactor] raises it where there is a canopy. Both of their site classes carry some
     * cover, so this is if anything a shade high for true bare ground, and that is the conservative
     * direction: it makes the dry end of the map less dissected rather than more.
     */
    const val CHANNEL_HEAD_AREA_SLOPE_SQUARED_KM2 = 0.008f

    /**
     * How much more resistant to erosion a closed canopy makes the ground than bare soil, as a
     * factor on the threshold.
     *
     * Two hundred, and it is the universal soil-loss equation's cover-management factor read
     * backwards. `C` in that equation is 1.0 on bare tilled soil and 0.005 or less under a closed
     * forest canopy (Wischmeier & Smith 1978), which is the same statement as "the same rainfall
     * erodes two hundred times less" — and a surface two hundred times harder to erode needs two
     * hundred times the area-slope product before running water can cut a head in it. Five
     * thousandths is the *conservative* end of the published range; a tenth of that is quoted for
     * undisturbed rainforest, and this takes the smaller claim.
     *
     * **This is the one number here that Montgomery and Dietrich's field range does not settle**,
     * and it is worth saying so. Their humid steepland heads and their arid lowland ones give
     * nearly the same `A x S^2` — which is what [CHANNEL_HEAD_AREA_SLOPE_SQUARED_KM2] is — but the
     * two site classes differ in relief and in cover at the same time, so a product that agrees
     * across them constrains neither term on its own. Moglen, Eltahir and Bras (1998) require the
     * cover term to be the larger of the two above semi-arid country, or their curve does not turn
     * over; the soil-loss equation is where the size of it comes from.
     */
    const val CLOSED_CANOPY_RESISTANCE_GAIN = 200.0

    /**
     * The rainfall the runoff weight is measured against, in millimetres a year: Earth's mean over
     * land, about 715 (Legates & Willmott's land mean, quoted at 700-750 in every later census).
     *
     * An absolute reference and not the world's own mean, which is what makes the weight a physical
     * quantity. Against the world's mean a world twice as wet would draw the same network, because
     * every cell's weight would be divided by twice as much; against Earth's it draws a denser one,
     * which is the climate term this criterion exists to carry.
     */
    const val EARTH_MEAN_LAND_RAINFALL_MM = 715f

    /**
     * The least runoff weight a cell may carry, as a share of [EARTH_MEAN_LAND_RAINFALL_MM].
     *
     * A fiftieth is fifteen millimetres a year, which is the Atacama and the inner Sahara. Below
     * that the arithmetic is dividing by a rainfall nobody measures, and — more to the point — a
     * hyper-arid surface is not channel-free: its channels are cut by the rare storm rather than by
     * the annual mean, which is the same reason `RiverStage.RUNOFF_FLOOR` exists. This floor is
     * what keeps a desert range's wadis on the map.
     */
    const val DRIEST_RUNOFF_SHARE_OF_EARTH_MEAN = 0.02f

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
        return kotlin.math.exp(ln(CLOSED_CANOPY_RESISTANCE_GAIN) * cover).toFloat()
    }

    /** A cell's runoff as a share of Earth's mean over land, floored — see the two constants. */
    fun runoffShareOfEarthMean(precipitationMm: Float): Float =
        (precipitationMm / EARTH_MEAN_LAND_RAINFALL_MM)
            .coerceAtLeast(DRIEST_RUNOFF_SHARE_OF_EARTH_MEAN)

    /**
     * The drainage area above each cell in square kilometres, each contributing cell counted in
     * proportion to the runoff it sheds.
     *
     * The generator's own accumulation over the generator's own D8 tree, with a weight of
     * [runoffShareOfEarthMean] times the ground one cell stands for. Where the rain is Earth's mean
     * this is the plain catchment area; where it is a tenth of that the catchment counts for a
     * tenth of its ground, which is the climate term in [isChannelHead]'s left-hand side rather
     * than in its threshold.
     *
     * Not `RiverResult.flowAccumulation`, which is the same walk over a 0..1 rainfall weight with a
     * floor: that field is a relative quantity the width and the erosion read against their own
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
     * [areaKm2] is [runoffWeightedAreaKm2]'s, [gradient] is the dimensionless drop to the cell's
     * own receiver, and [config] supplies the threshold and its cover gain.
     */
    fun isChannelHead(
        areaKm2: Float,
        gradient: Float,
        vegetationDensity: Float,
        config: RiverConfig
    ): Boolean {
        val cover = if (config.coverRaisesChannelHead) coverFactor(vegetationDensity) else 1f
        return areaKm2 * gradient * gradient >= config.channelHeadAreaSlopeSquaredKm2 * cover
    }

    /**
     * Every cell the terrain carries a channel through: the heads, and everything downstream of
     * one.
     *
     * The second half is not a convenience. Discharge only grows downstream, so a channel that has
     * started cannot stop; a reach crossing a flat where the gradient is the fill's own hair would
     * otherwise drop out of the network and leave a river in two pieces with a gap in the middle.
     * Walked over [FlowRouting.drainageOrder], which puts every cell after everything draining into
     * it, so one pass settles the whole forest.
     *
     * Open water is out of the mask for the reason `RiverStage.traceRivers` gives — a lake is not a
     * reach of river — and a playa is in, because it is dry ground most of the year.
     */
    fun channelMask(
        config: WorldGenConfig,
        isLand: BooleanArray,
        landCellCount: Int,
        filled: FloatField,
        flowTarget: IntArray,
        climate: ClimateResult,
        isOpenWater: (Int) -> Boolean
    ): BooleanArray {
        val precipitationMm = climate.precipitationMm
        val vegetationDensity = climate.vegetationDensity
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val channel = BooleanArray(cellCount)
        if (landCellCount == 0) return channel

        val areaKm2 = runoffWeightedAreaKm2(
            cellsAcross, cellsDown, isLand, landCellCount, filled, flowTarget, precipitationMm,
            config.squareKilometresPerCell
        )
        val gradient = gradientToReceiver(config, isLand, filled, flowTarget)

        for (cell in 0 until cellCount) {
            if (!isLand[cell] || isOpenWater(cell)) continue
            if (neverThaws(config, climate, cell)) continue
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
     * Nothing stores the mask: it is eight fields' worth of arithmetic over arrays the world
     * already holds, and a boolean a cell is four megabytes at 2048. The guards and the render
     * harnesses call this so that what they measure is the network the stage drew and not a second
     * definition of one.
     */
    fun channelMaskOf(world: com.cartogenesis.worldgen.model.WorldMap): BooleanArray = channelMask(
        world.config, world.sea.isLand, world.sea.landCellCount, world.rivers.filledElevation,
        world.rivers.flowTarget, world.climate
    ) { world.rivers.lakes.isOpenWater(it) }

    /**
     * Ground that never thaws, and so never starts a channel of its own.
     *
     * Thornthwaite's demand is exactly zero where neither season rises above freezing, which is the
     * same test `EarthLikeness` files as its FROZEN class and the same arithmetic the lake water
     * balance runs. Such a cell is under perennial snow or ice or is a polar desert on frozen
     * ground: water does not run over it in any season, so nothing there cuts a head. What crosses
     * it from warmer ground upstream is still a channel — the downstream rule in [channelMask]
     * carries it — which is the Lena and the Yenisey, rivers that rise where the summer thaws and
     * run on over ground that does not.
     *
     * A head rule and not a mask over the network, for exactly that reason, and it is why the
     * drainage densities this criterion produces can be read as a curve against aridity at all:
     * without it the coldest country came out as the most finely dissected on the map, on cells
     * where no water runs.
     */
    fun neverThaws(config: WorldGenConfig, climate: ClimateResult, cell: Int): Boolean =
        LakeWaterBalance.potentialEvaporationMm(
            climate.summerTemperature.data[cell],
            climate.winterTemperature.data[cell],
            config.lakes.evaporationScale
        ) <= 0f

    /**
     * The dimensionless drop from each land cell to the cell it drains into: metres of fall over
     * metres of ground.
     *
     * The fall is read off the depression-filled surface, because that is the surface the water
     * runs down; inside a basin the fill has raised it is flat to within the hair the flood nudges
     * each cell by, which is exactly right — there is no gradient there and no channel head, and
     * the reach that crosses it is carried by the downstream rule in [channelMask].
     *
     * Zero where the water leaves the world, on the polar rows where there is no further cell.
     */
    fun gradientToReceiver(
        config: WorldGenConfig,
        isLand: BooleanArray,
        filled: FloatField,
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
            val fallMetres = scale.metresAboveShoreline(filled.data[cell]) -
                scale.metresAboveShoreline(filled.data[receiver])
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
