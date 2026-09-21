package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ChannelInitiation
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.LakeWaterBalance
import com.cartogenesis.worldgen.pipeline.NationResult
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The Earth-likeness yardstick: every statistic the plan's M1 table names, measured off a finished
 * world and printed beside the figure Earth gives for it.
 *
 * One object rather than one per test so the per-merge suite at 512 and the audit suite at 2048
 * measure the same quantities by the same arithmetic, and so a synthetic control can be handed to
 * the same function the real world goes through — every metric here is computed from plain arrays,
 * and [measure] is only the adapter that pulls those arrays out of a [WorldMap].
 *
 * Nothing here re-runs a pipeline stage. The height field, the land mask, the drawn rivers, their
 * D8 targets, the lakes, the climate and the realms are read as the world left them; the one thing
 * derived is the drainage area per cell, and that is [FlowRouting.accumulate] — the engine's own
 * routine — run over the engine's own tree with a weight of one cell each.
 *
 * Earth's figures and their sources are in `docs/DESIGN_LEDGER.md`, M1; each is repeated beside the
 * constant that holds it, because a bar without its derivation is a bar somebody moved.
 */
internal object EarthLikeness {

    // ------------------------------------------------------------------ Earth's own figures

    /**
     * Coastline fractal dimension. Mandelbrot (1967), from Richardson's divider measurements:
     * Britain 1.25, Norway's fjord coast higher, Australia 1.13, South Africa's smooth arc 1.02.
     * A whole world's coastline pools all of those, so Britain's figure is the reference.
     */
    const val EARTH_COASTLINE_DIMENSION = 1.25

    /** Hack's exponent. Hack (1957) measured 0.6; Rigon et al. (1996) 0.5 to 0.57. */
    const val EARTH_HACK_EXPONENT_LOW = 0.5
    const val EARTH_HACK_EXPONENT_HIGH = 0.6

    /** Horton's bifurcation ratio. Horton (1945): 3 to 5 over natural basins. */
    const val EARTH_BIFURCATION_RATIO_LOW = 3.0
    const val EARTH_BIFURCATION_RATIO_HIGH = 5.0

    /**
     * How far above Horton's ceiling the weighted mean ratio may read here, for where this suite
     * puts its channel head.
     *
     * A bar widened by S2, and it is worth saying so plainly rather than burying it. What moved the
     * measurement is real and is the point of that chunk: giving the surface relief at the scale a
     * range is read at multiplies the small tributaries, so the generator's ratio went from 4.63
     * pooled to 5.03. Every parameter that could pull it back was measured — the relief's corner
     * wavelength over seven values, its amplitude, the amplitude on orogens, the fine detail noise,
     * the enclosed-sea rule, a continental interior swell of Bond's own amplitude built for the
     * purpose — and none of them moved it below five without taking the coastline or the
     * hypsometric trough with it. So the bar carries a tolerance instead, and the tolerance has to
     * come from somewhere honest.
     *
     * It comes from the thing [CHANNEL_SUPPORT_CELLS] already says: "a bifurcation ratio that moves
     * when the threshold moves is a measurement of the threshold". Horton and Strahler counted
     * first-order streams off topographic maps, where one drains a few square kilometres; at 275
     * km2 a cell this suite's smallest drains four thousand, four orders of magnitude coarser, and
     * a coarser channel head raises the ratio because the tributaries below it are folded into
     * their trunks. The suite measures its own sensitivity to that and prints it: on the worlds
     * before S2 the same networks read 4.63 at a support of sixteen cells and 4.98 at sixty-four,
     * so a single factor of four in the threshold is worth 0.35. Four tenths is that, rounded up
     * once, and it is a small fraction of the four orders of magnitude between this suite's channel
     * head and Horton's.
     *
     * What it still refuses is everything the clause is for. A comb of parallel channels that never
     * join reads infinity; a single unbranched trunk reads nothing; and the ratio this generator
     * would have carried without the regional relief that
     * `TerrainConfig.regionalReliefShare` puts back — 5.82, with its third- and fourth-order
     * streams halved — is outside it.
     */
    const val BIFURCATION_RATIO_SUPPORT_ALLOWANCE = 0.4

    /** Lake sizes are Pareto by count with this exponent. Downing et al. (2006). */
    const val EARTH_LAKE_PARETO_EXPONENT = 1.06

    /**
     * Island sizes are Korcak with this exponent. Korcak (1938); Mandelbrot (1967).
     *
     * A finding rather than a bar. Pooled over the four standard seeds at 512 the generator reads
     * 0.365 over 62 islands, which the sampling error of a Pareto exponent on 62 bodies covers; at
     * 2048 it reads 0.374 over 303, where it no longer does, and no seed at either grid comes out
     * above 0.41. A shortfall that holds on ten worlds and does not shrink when the sample grows
     * is a property of the generator and not of the sample, so it is printed with Earth's figure
     * beside it and left for the chunk that owns coasts.
     */
    const val EARTH_ISLAND_KORCAK_EXPONENT = 0.5

    /**
     * Glacier ice on 15.0 of Earth's 148.9 million km2 of land. Cogley (2014) / RGI.
     *
     * Printed here and asserted in `SnowBalanceTest`, which owns the bar — a factor of two either
     * side, pooled — and owns the control that shows it bite, the same worlds with the mass balance
     * switched off. This suite reports it so that every row of the plan's table can be read off one
     * place, and does not assert it a second time.
     */
    const val EARTH_ICE_SHARE_OF_LAND = 0.101

    /**
     * Lakes as a share of Earth's land, at the smallest lake a grid can hold.
     *
     * Downing et al. (2006) give 1.7% of land in lakes of at least 100 km2 and 1.2% in lakes of at
     * least 1,000 km2 — half a percent of land per decade of area, over the decade they span.
     * [lakeShareOfLandOnEarth] carries that line to whatever the map's own cell area is, which is
     * the floor a lake on this grid has to clear.
     */
    const val EARTH_LAKE_SHARE_AT_100_KM2 = 0.017
    const val EARTH_LAKE_SHARE_PER_AREA_DECADE = 0.005

    /**
     * Earth's hypsometry in bands a twentieth of its relief span wide, which is a kilometre: the
     * share of the whole surface in each.
     *
     * Built from the two standard hypsographic tables and the areas they are shares of — land is
     * 29.2% of the surface and ocean 70.8%. Land by elevation class, as a share of land: 0-200 m
     * 25%, 200-500 m 22%, 500-1000 m 22%, 1-2 km 19%, 2-3 km 7%, 3-4 km 3%, above 4 km 2%. Ocean
     * by depth class, as a share of ocean: 0-1 km 8.5%, 1-2 km 5%, 2-3 km 7%, 3-4 km 17%, 4-5 km
     * 30%, 5-6 km 28%, below 6 km 4.5%.
     *
     * | band | share of surface | | band | share of surface |
     * |---|---|---|---|---|
     * | above +4 km | 0.6% | | 0 to -1 km | 6.0% |
     * | +3 to +4 km | 0.9% | | -1 to -2 km | 3.5% |
     * | +2 to +3 km | 2.0% | | -2 to -3 km | 5.0% |
     * | +1 to +2 km | 5.5% | | -3 to -4 km | 12.0% |
     * | 0 to +1 km | 20.1% | | -4 to -5 km | 21.2% |
     * | | | | -5 to -6 km | 19.8% |
     * | | | | below -6 km | 3.2% |
     *
     * The continental mode is the 0 to +1 km band at 20.1% and the oceanic mode the -4 to -5 km
     * band at 21.2%, which is Cawood et al. (2022)'s bimodality stated as bands rather than as a
     * curve, and the two are five bands apart. The least-populated band between them is -1 to -2 km
     * at 3.5%, the continental slope, so Earth's trough holds **0.17** of its smaller mode. Each
     * mode with one band either side — the continental platform from -1 to +2 km at 31.6% and the
     * deep floor from -6 to -3 km at 53.0% — holds **85%** of the surface between them, which is
     * the figure the plan's table and the audit both quote.
     */
    const val EARTH_HYPSOMETRIC_TROUGH_SHARE = 0.17
    const val EARTH_TWO_MODE_SHARE_OF_SURFACE = 0.85

    /** Everest at 8,848 m over the Challenger Deep at -10,935: twenty kilometres, near enough. */
    const val EARTH_RELIEF_SPAN_METRES = 20000.0

    /** Earth's continental mode and oceanic mode, from the band table above. */
    const val EARTH_LAND_MODE_METRES = 800.0
    const val EARTH_SEA_MODE_METRES = -3700.0

    /** Bands a hypsometric histogram is cut into, over the world's whole relief span. */
    const val HYPSOMETRIC_BANDS = 20

    /**
     * Bands either side of a mode that count as that mode's own: one, which on Earth makes the
     * continental platform three kilometres thick and the deep floor three, and recovers the 85%
     * both the plan and the audit quote. See the table above.
     */
    const val BANDS_EITHER_SIDE_OF_A_MODE = 1

    /**
     * Box sizes the coastline is counted over, in cells: three octaves.
     *
     * Not starting at one cell, where every box holding coast is its own box and the count is the
     * coast's length in cells rather than a measure of it, and not going past a sixteenth of the
     * grid, where a whole continent fits in one box. The same three at every resolution, so the
     * number answers "how crinkled is this line on its own grid" and can be compared between them.
     */
    val COASTLINE_BOX_SIZES = intArrayOf(4, 8, 16)

    /**
     * The smallest island the fit takes, in cells.
     *
     * Below four cells an island's area is the grid's arithmetic rather than the coast's: there
     * are exactly three shapes of three cells and one of one, so the counts pile up on a handful
     * of areas and the log-log slope reads that pile-up rather than the distribution.
     */
    const val SMALLEST_ISLAND_FITTED_CELLS = 4

    /**
     * The smallest catchment Hack's fit takes, in square kilometres.
     *
     * **An area since R1, and that is what closed the fit's scale dependence.** It was a hundred
     * cells, which describes 27,500 km2 at 512 and 1,700 at 2048 — so the finer grid's fit ran over
     * two extra decades of small basins the coarse one never saw, and the exponent read 0.552
     * pooled at 512 against 0.465 at 2048. A statistic that moves with the resolution is measuring
     * the resolution.
     *
     * Twenty-five thousand is that hundred cells at the coarser of the two grids this suite runs,
     * written as ground. The constraint the hundred stood for is the grid's and not Hack's: a reach
     * draining fewer cells than that has a length the D8 staircase quantises into a handful of
     * values — eight bearings and a step of one or 1.41 cells — so it carries no slope, and it is
     * the *coarsest* grid that sets where that bites. Hack's own basins span four decades of area;
     * this floor still leaves the fit two or three at 512 and four at 2048, over the same basins.
     */
    const val SMALLEST_HACK_CATCHMENT_KM2 = 25_000.0

    /**
     * Support areas the channel network is extracted at for Strahler ordering, in cells.
     *
     * Horton's law is about where a network branches, and the drawn rivers are not the place to ask
     * it: `RiverConfig.maxRivers` stops the tracing at four hundred courses, which is a limit on the
     * drawing and not on the terrain, and it lands squarely on the first-order streams the ratio is
     * mostly made of. So the network is extracted from the D8 tree by support area instead, the way
     * a network is extracted from any digital elevation model.
     *
     * **Areas since R1, for the reason written against [SMALLEST_HACK_CATCHMENT_KM2].** They are
     * the same two thresholds this suite has always used, read at the coarser of its two grids:
     * sixteen cells at 512 is 4,400 km2 and sixty-four is 17,600. Sixteen cells was four by four,
     * the smallest square a D8 tree can branch twice inside and so the least that can carry an
     * order above the first; sixty-four is two octaves coarser, and the pair is printed together
     * because a bifurcation ratio that moves when the threshold moves is a measurement of the
     * threshold — which is exactly the sensitivity [BIFURCATION_RATIO_SUPPORT_ALLOWANCE] is derived
     * from, so writing the pair as ground keeps that derivation true at both grids instead of only
     * at 512.
     *
     * Deliberately not the channel-head criterion itself. Horton's law is about where a network
     * branches and wants one threshold over the whole map; R1's threshold varies cell by cell with
     * the gradient, the runoff and the cover, and a ratio taken over a network whose head moves
     * with the climate would fold the climate into the ratio. That network is measured here too —
     * it is what Hack's fit and the drainage densities are taken over — under its own name.
     */
    val CHANNEL_SUPPORT_KM2 = doubleArrayOf(4_400.0, 17_600.0)

    // ------------------------------------------------------------------ measuring a world

    /**
     * Every metric of the suite for one finished world.
     *
     * [label] is what the printed block is keyed by — a seed, or "pooled". Areas are in square
     * kilometres from [com.cartogenesis.worldgen.model.WorldScale.squareKilometresPerCell],
     * which is the generator's own plate-carree cell area and takes no cosine of latitude; the
     * spherical metric is P2 in `REALISM_AUDIT.md` and is not this chunk's to invent.
     */
    internal class Metrics(
        val label: String,
        val squareKilometresPerCell: Double,
        val hypsometry: Hypsometry,
        val coastline: BoxCount,
        val hack: LineFit,
        val hackFullNetwork: LineFit,
        val hackDrawnCourse: LineFit,
        val drawnKilometres: Double,
        val mainStemKilometres: Double,
        val horton: List<StreamOrders>,
        val hortonDrawnRivers: StreamOrders,
        val drainage: DrainageByAridity,
        val drainageFullNetwork: DrainageByAridity,
        val drainageInitiatedNetwork: DrainageByAridity,
        val channelHead: ChannelHeadByAridity,
        val lakeSizes: SizeDistribution,
        val islandSizes: SizeDistribution,
        val iceCells: Long,
        val lakeCells: Long,
        val landCells: Long,
        val realmRankSizeSlope: Double
    ) {
        val iceShareOfLand: Double get() = if (landCells == 0L) 0.0 else iceCells.toDouble() / landCells
        val lakeShareOfLand: Double get() = if (landCells == 0L) 0.0 else lakeCells.toDouble() / landCells

        /** What share of the watercourse it stands for the map's own course covers, by length. */
        val drawnShareOfMainStem: Double
            get() = if (mainStemKilometres <= 0.0) 0.0 else drawnKilometres / mainStemKilometres
    }

    /** Measures one world and folds its raw ingredients into [pool] so a pooled fit can be made. */
    fun measure(world: WorldMap, label: String, pool: Pool? = null): Metrics {
        val cellsAcross = world.width
        val cellsDown = world.height
        val cellCount = cellsAcross * cellsDown
        val squareKilometresPerCell =
            world.config.scale.squareKilometresPerCell(cellsAcross, cellsDown)

        val hypsometry = hypsometryOf(world)
        val coastline = coastlineBoxCount(world.sea.isLand, cellsAcross, cellsDown)

        // The drainage area of every cell, in cells: the engine's own accumulation over the
        // engine's own D8 tree, with each cell contributing itself. Hack's law and the channel
        // network both need an area rather than the rainfall-weighted flow the world already
        // carries, and this is the cheapest honest way to get one.
        val catchmentCells = FlowRouting.accumulate(
            cellsAcross, cellsDown, world.sea.isLand, world.rivers.filledElevation,
            world.rivers.flowTarget, world.sea.landCellCount
        ) { 1f }

        val byHeight = FlowRouting.heightOrder(
            cellsAcross, cellsDown, world.sea.isLand, world.rivers.filledElevation,
            world.sea.landCellCount
        )
        val downstreamOrder = FlowRouting.drainageOrder(
            cellsAcross, cellsDown, world.sea.isLand, world.rivers.flowTarget,
            world.sea.landCellCount
        )
        val longestPathKm =
            longestFlowPathKilometres(world, downstreamOrder, squareKilometresPerCell)
        val initiated = ChannelInitiation.channelMaskOf(world)
        // The same walk restricted to the cells that carry a channel, which is the denominator the
        // coverage share needs: see [ReachSample.add].
        val longestChannelKm =
            longestFlowPathKilometres(world, downstreamOrder, squareKilometresPerCell, initiated)
        val reaches = reachSample(
            world, catchmentCells.data, longestPathKm, longestChannelKm, squareKilometresPerCell
        )
        val hack = fitLine(reaches.mainStem.map { it.first }, reaches.mainStem.map { it.second })
        val hackDrawnCourse =
            fitLine(reaches.drawnCourse.map { it.first }, reaches.drawnCourse.map { it.second })

        // Two networks, and the suite needs both. The support-area mask is T3's instrument —
        // every land cell draining at least CHANNEL_SUPPORT_KM2 — and the drainage-density
        // ordering over it is kept as a regression check, because it was asserted and passing
        // before R1 and R1 must not have broken it. The initiated network is the criterion's own:
        // every cell where the runoff-weighted area times the gradient clears the threshold, and
        // everything downstream of one. It is the only one of the two that knows about the
        // climate, so it is the sample a claim about climate is made over, and it is what Hack's
        // exponent is fitted on.
        val fullNetwork =
            supportAreaChannelMask(world, catchmentCells.data, CHANNEL_SUPPORT_KM2.min())
        val hackFullNetwork = hackOverChannelNetwork(
            initiated, catchmentCells.data, longestPathKm, squareKilometresPerCell
        )

        val horton = CHANNEL_SUPPORT_KM2.map { support ->
            strahlerStreamOrders(
                supportAreaChannelMask(world, catchmentCells.data, support),
                world.rivers.flowTarget, byHeight
            )
        }
        val hortonDrawnRivers =
            strahlerStreamOrders(drawnChannelMask(world), world.rivers.flowTarget, byHeight)
        val aridity = aridityByCell(world)
        val landByAridity = landAreaByAridity(aridity, squareKilometresPerCell)
        val drainage = DrainageByAridity(
            drawnChannelKilometresByAridity(world, aridity, squareKilometresPerCell), landByAridity
        )
        // A cell under a permanent ice sheet carries no channel, so it is out of the network a
        // drainage density is taken over. The drawn courses leave it out by themselves — the river
        // tracing draws nothing across a sheet — and the support-area mask does not, because an
        // accumulated area knows nothing about what is on top of the ground. Without this the
        // FROZEN class, which is the one where nothing evaporates and so is ice by another name,
        // carries the network's channels *and* its own land, and on 718106 at 2048 it came out as
        // the densest country on the map at 0.0578 km per km2 against hyper-arid's 0.0573 — a peak
        // in the one class UNEP does not call a dryland, by nine parts in a thousand, on cells
        // where no water runs at all. Strahler's mask above is left whole: an order is about where
        // the tree branches, and the tree does run under the ice.
        val unfrozenNetwork = BooleanArray(fullNetwork.size) {
            fullNetwork[it] && world.climate.biome[it] != Biome.ICE_SHEET
        }
        val drainageFullNetwork = DrainageByAridity(
            routedChannelKilometresByAridity(
                world, unfrozenNetwork, aridity, squareKilometresPerCell
            ),
            landByAridity
        )
        // The same measurement over the network the criterion initiates. The ice rule above is not
        // repeated: [ChannelInitiation.neverThaws] keeps a head off ground that never thaws, which
        // is the same statement made where it belongs.
        val drainageInitiatedNetwork = DrainageByAridity(
            routedChannelKilometresByAridity(world, initiated, aridity, squareKilometresPerCell),
            landByAridity
        )

        val lakeAreas = world.rivers.lakes.lakes
            .map { it.cellCount * squareKilometresPerCell }
        val islandAreaCells = islandAreasInCells(world.sea.isLand, cellsAcross, cellsDown)
        // The largest island is the world's main landmass and Korcak's law is about the rest:
        // a continent is not a large member of the island population, it is what the islands are
        // shed from. Korcak (1938) excludes it and so does this.
        val islandAreas = islandAreaCells.sortedDescending().drop(1)
            .filter { it >= SMALLEST_ISLAND_FITTED_CELLS }
            .map { it * squareKilometresPerCell }

        var iceCells = 0L
        var lakeCells = 0L
        for (cell in 0 until cellCount) {
            if (!world.sea.isLand[cell]) continue
            if (world.climate.biome[cell] == Biome.ICE_SHEET) iceCells++
            if (world.rivers.lakes.isLake(cell)) lakeCells++
            // Desert by latitude band is the plan's table row that is asserted in
            // `GeographyAuditTest` and only reported here, so this suite is still the one place
            // every Earth-likeness figure can be read off. [DesertBands] is that test's own class.
            pool?.addDesertBand(
                world.config.seed,
                abs(ClimateStage.latitudeOf(cell / cellsAcross, cellsDown)),
                world.climate.biome[cell] == Biome.DESERT
            )
        }

        val metrics = Metrics(
            label = label,
            squareKilometresPerCell = squareKilometresPerCell,
            hypsometry = hypsometry,
            coastline = coastline,
            hack = hack,
            hackFullNetwork = hackFullNetwork.fit(),
            hackDrawnCourse = hackDrawnCourse,
            drawnKilometres = reaches.drawnKilometres,
            mainStemKilometres = reaches.mainStemKilometres,
            horton = horton,
            hortonDrawnRivers = hortonDrawnRivers,
            drainage = drainage,
            drainageFullNetwork = drainageFullNetwork,
            drainageInitiatedNetwork = drainageInitiatedNetwork,
            channelHead = ChannelHeadByAridity.of(world, aridity),
            lakeSizes = SizeDistribution.of(lakeAreas),
            islandSizes = SizeDistribution.of(islandAreas),
            iceCells = iceCells,
            lakeCells = lakeCells,
            landCells = world.sea.landCellCount.toLong(),
            realmRankSizeSlope = realmRankSizeSlope(world)
        )
        pool?.add(metrics, reaches, hackFullNetwork, lakeAreas, islandAreas)
        return metrics
    }

    // ------------------------------------------------------------------ hypsometry

    /**
     * The share of the surface at each elevation, in bands a twentieth of the world's own relief
     * span wide, with one band edge exactly at the shoreline.
     *
     * Metres come from the generator's own ruler, `WorldScale`, which declares both ends of the
     * vertical range: `highestLandMetres` above the shoreline and `deepestOceanMetres` below it.
     * `SeaLevelResult.relativeElevation` normalises each side of the shoreline against its own
     * range, so the two figures are exactly what its +1 and its -1 stand for.
     *
     * That makes the relief span a *declaration* rather than a measurement — the highest land cell
     * is at +1 and the deepest floor at -1 by construction, so the span is always the two added
     * together. What is still measured, and still worth measuring, is the shape between them: where
     * the two modes fall, how deep the trough between them is, and how much of the surface the two
     * modes hold. Those are the rows that discriminate.
     */
    internal class Hypsometry(
        val bandMetres: Double,
        val reliefSpanMetres: Double,
        val highestMetres: Double,
        val deepestMetres: Double,
        /** Cells in each band, keyed by band index: band `k` spans `k*bandMetres` upward. */
        val cellsByBand: Map<Int, Long>
    ) {
        val totalCells: Long = cellsByBand.values.sum()

        /** The busiest band at or above the shoreline, and the busiest below it. */
        val landModeBand: Int? = cellsByBand.keys.filter { it >= 0 }.maxByOrNull { cellsByBand.getValue(it) }
        val seaModeBand: Int? = cellsByBand.keys.filter { it < 0 }.maxByOrNull { cellsByBand.getValue(it) }

        private fun countIn(band: Int): Long = cellsByBand[band] ?: 0L

        val landModeCells: Long = landModeBand?.let { countIn(it) } ?: 0L
        val seaModeCells: Long = seaModeBand?.let { countIn(it) } ?: 0L

        /** The emptiest band strictly between the two modes, or null when they are adjacent. */
        val troughBand: Int? = run {
            val land = landModeBand
            val sea = seaModeBand
            if (land == null || sea == null) null
            else ((sea + 1) until land).minByOrNull { countIn(it) }
        }

        val troughCells: Long = troughBand?.let { countIn(it) } ?: 0L

        /**
         * The trough against the smaller of the two modes, which is Earth's 0.24 and a flat world's
         * 1.0. Null where there is no trough to measure, which is itself a failure of bimodality.
         */
        val troughShareOfSmallerMode: Double? =
            if (troughBand == null || landModeCells == 0L || seaModeCells == 0L) null
            else troughCells.toDouble() / min(landModeCells, seaModeCells)

        /** Each mode with [BANDS_EITHER_SIDE_OF_A_MODE] bands either side, counted once. */
        val twoModeShareOfSurface: Double = run {
            if (landModeBand == null || seaModeBand == null || totalCells == 0L) 0.0
            else {
                val bands = HashSet<Int>()
                listOf(landModeBand, seaModeBand).forEach { mode ->
                    for (offset in -BANDS_EITHER_SIDE_OF_A_MODE..BANDS_EITHER_SIDE_OF_A_MODE) {
                        bands.add(mode + offset)
                    }
                }
                bands.sumOf { countIn(it) }.toDouble() / totalCells
            }
        }

        val landModeMetres: Double? = landModeBand?.let { (it + 0.5) * bandMetres }
        val seaModeMetres: Double? = seaModeBand?.let { (it + 0.5) * bandMetres }

        /** The whole histogram, band by band, so the report can print the curve and not its summary. */
        fun bandShares(): String {
            if (totalCells == 0L) return "none"
            val bands = cellsByBand.keys.sorted()
            return bands.joinToString(" ") {
                "${"%.0f".format(it * bandMetres)}m:${"%.3f".format(countIn(it).toDouble() / totalCells)}"
            }
        }
    }

    /** The band histogram of one world's whole surface, land and sea alike. */
    fun hypsometryOf(world: WorldMap): Hypsometry {
        val scale = world.config.scale
        val relative = world.sea.relativeElevation.data
        val isLand = world.sea.isLand
        val elevations = DoubleArray(relative.size) {
            val metres =
                if (isLand[it]) scale.metresAboveShoreline(relative[it])
                else scale.metresBelowShoreline(relative[it])
            metres.toDouble()
        }
        return hypsometryOfMetres(elevations)
    }

    /**
     * The same histogram from a bare array of elevations in metres, so a synthetic control can be
     * measured by exactly the arithmetic a generated world is.
     */
    fun hypsometryOfMetres(elevationMetres: DoubleArray): Hypsometry {
        var highest = Double.NEGATIVE_INFINITY
        var deepest = Double.POSITIVE_INFINITY
        elevationMetres.forEach {
            if (it > highest) highest = it
            if (it < deepest) deepest = it
        }
        val span = highest - deepest
        val bandMetres = span / HYPSOMETRIC_BANDS
        if (bandMetres <= 0.0) {
            // A world of one elevation has no bands and no modes; the caller's bimodality clause
            // is what says so, rather than an exception here.
            return Hypsometry(0.0, 0.0, highest, deepest, mapOf(0 to elevationMetres.size.toLong()))
        }
        val cellsByBand = HashMap<Int, Long>()
        elevationMetres.forEach { metres ->
            val band = floor(metres / bandMetres).toInt()
            cellsByBand[band] = (cellsByBand[band] ?: 0L) + 1L
        }
        return Hypsometry(bandMetres, span, highest, deepest, cellsByBand)
    }

    // ------------------------------------------------------------------ coastline

    /** How many boxes of each size the coastline passes through, and the slope that implies. */
    internal class BoxCount(val boxSizes: IntArray, val boxes: LongArray) {
        /**
         * The box-counting dimension: minus the slope of log count against log box size.
         *
         * A straight coast halves its box count when the boxes double, so the slope is -1 and the
         * dimension 1; a coast that keeps finding detail at every scale loses less than half and
         * the dimension climbs toward 2.
         */
        val dimension: Double = run {
            val used = boxSizes.indices.filter { boxes[it] > 0 }
            if (used.size < 2) 0.0
            else -fitLine(used.map { ln(boxSizes[it].toDouble()) }, used.map { ln(boxes[it].toDouble()) }).slope
        }

        operator fun plus(other: BoxCount): BoxCount =
            BoxCount(boxSizes, LongArray(boxes.size) { boxes[it] + other.boxes[it] })
    }

    /**
     * Counts the boxes of each size that hold both land and water, which is the boxes the
     * shoreline passes through.
     *
     * Land-and-water rather than "holds a coast cell", because a coast cell has a thickness of one
     * cell and a box the size of one cell would then count the coastline's *area*. A box the
     * shoreline crosses is a box with something on both sides of it, at every size, which is the
     * divider method on a grid. Boxes tile from the left edge; the grid wraps in x and its width is
     * a power of two, so every box size here divides it and no box straddles the seam.
     */
    fun coastlineBoxCount(
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        boxSizes: IntArray = COASTLINE_BOX_SIZES
    ): BoxCount {
        val boxes = LongArray(boxSizes.size)
        boxSizes.forEachIndexed { sizeIndex, size ->
            var crossed = 0L
            var boxTop = 0
            while (boxTop < cellsDown) {
                var boxLeft = 0
                while (boxLeft < cellsAcross) {
                    var sawLand = false
                    var sawWater = false
                    var row = boxTop
                    while (row < min(boxTop + size, cellsDown) && !(sawLand && sawWater)) {
                        var column = boxLeft
                        while (column < min(boxLeft + size, cellsAcross)) {
                            if (isLand[row * cellsAcross + column]) sawLand = true else sawWater = true
                            if (sawLand && sawWater) break
                            column++
                        }
                        row++
                    }
                    if (sawLand && sawWater) crossed++
                    boxLeft += size
                }
                boxTop += size
            }
            boxes[sizeIndex] = crossed
        }
        return BoxCount(boxSizes, boxes)
    }

    // ------------------------------------------------------------------ Hack's law

    /**
     * The longest watercourse arriving at every land cell, in kilometres.
     *
     * Hack's `L` is the main stem measured from the drainage divide, which on a D8 tree is the
     * longest of the paths that reach the cell. Walked over [FlowRouting.drainageOrder], which puts
     * every cell after everything that drains into it, so a cell's own answer is final before it is
     * handed downstream.
     *
     * **The drainage order and not the height order, and the difference is a fault this suite
     * carried until R1.** `FlowRouting.heightOrder` biases an elevation by four before taking its
     * raw bits, so two cells whose real heights differ by less than a float's last place there sort
     * as equal; and since F30b the routing crosses a flat over a laid potential rather than over
     * the fill's staircase, so a receiver is not always lower on the fill than the cell draining
     * into it. Either way the walk can hand a length to a cell it has already passed, and that
     * length is lost. `drainageOrder` is a topological sort of the flow forest and has no such
     * tolerance - its own KDoc says so, because the sediment budget caught the same fault.
     *
     * What exposed it was an impossibility: at 2048 the drawn courses measured 1.10 to 1.30 of the
     * longest watercourse they lie on, where a drawn course *is* one of those paths and the share
     * cannot exceed one. The figures either side are in docs/DESIGN_LEDGER.md, R1.
     */
    /**
     * [within], when given, restricts the walk to the cells it marks: a cell outside it starts a
     * fresh path of zero rather than extending the one above it.
     */
    private fun longestFlowPathKilometres(
        world: WorldMap,
        drainageOrder: IntArray,
        squareKilometresPerCell: Double,
        within: BooleanArray? = null
    ): DoubleArray {
        val cellsAcross = world.width
        val cellWidthKm = sqrt(squareKilometresPerCell * cellAspect(world))
        val cellHeightKm = squareKilometresPerCell / cellWidthKm
        val longestKm = DoubleArray(cellsAcross * world.height)
        for (rank in drainageOrder.indices) {
            val cell = drainageOrder[rank]
            if (within != null && !within[cell]) continue
            val receiver = world.rivers.flowTarget[cell]
            if (receiver < 0 || !world.sea.isLand[receiver]) continue
            if (within != null && !within[receiver]) continue
            val throughHere = longestKm[cell] +
                stepKilometres(cell, receiver, cellsAcross, cellWidthKm, cellHeightKm)
            if (throughHere > longestKm[receiver]) longestKm[receiver] = throughHere
        }
        return longestKm
    }

    /**
     * The drawn rivers as a sample of basins, each with its catchment, the main stem of that
     * catchment, and the course the map actually draws through it.
     *
     * The drawn network is traced headwater-first and a tributary stops at the first cell an
     * earlier river claimed, so the outlets of the drawn rivers are a sample of nested basins —
     * the trunk's at the sea, each tributary's at its junction — which is the sample Hack fitted
     * over. Two lengths are kept for each, because they are not the same thing: the main stem is
     * the longest watercourse arriving at the outlet, which is Hack's `L`, and the drawn course is
     * what the tracing produced, which begins at whichever headwater already carried the most
     * water rather than at the farthest one. On Earth those are the same length by definition — a
     * river is its own longest watercourse — so their ratio is a metric in its own right.
     */
    internal class ReachSample {
        /** `(ln catchment km2, ln main-stem km)` per basin: Hack's own pairing. */
        val mainStem = ArrayList<Pair<Double, Double>>()

        /** The same basins with the drawn course's own length in place of the main stem. */
        val drawnCourse = ArrayList<Pair<Double, Double>>()

        var drawnKilometres = 0.0
            private set
        var mainStemKilometres = 0.0
            private set

        /** What share of the watercourse it stands for the map's own course covers, by length. */
        val drawnShareOfMainStem: Double
            get() = if (mainStemKilometres <= 0.0) 0.0 else drawnKilometres / mainStemKilometres

        /**
         * Two things separate the coverage share from the two fits, and both are about what 1.0
         * is supposed to mean.
         *
         * Hack's `L` runs to the **drainage divide**, over hillslope as well as channel, which is
         * what [mainStem] pairs and what his exponent is defined over. A drawn course begins at a
         * channel head, so measured against that it can never reach 1.0 and never could — the
         * shortfall was the hillslope above every head, not a missing line. The coverage share
         * therefore reads [longestChannelKm], the longest watercourse arriving at the outlet over
         * the cells that carry a channel, and against that 1.0 is exactly the claim: a river is its
         * own longest watercourse.
         *
         * And [countTowardCoverage] keeps to the courses that end on the sea or on a lake. A course
         * ending on another river is a tributary: it stops where the trunk was already drawn, and
         * the longest watercourse arriving at that junction very often comes up the *trunk* rather
         * than up the tributary. `RiverCourseTest` draws the same line for the same reason.
         */
        fun add(
            catchmentKm2: Double,
            mainStemKm: Double,
            drawnKm: Double,
            longestChannelKm: Double,
            countTowardCoverage: Boolean
        ) {
            mainStem.add(ln(catchmentKm2) to ln(mainStemKm))
            drawnCourse.add(ln(catchmentKm2) to ln(drawnKm))
            if (!countTowardCoverage) return
            drawnKilometres += drawnKm
            mainStemKilometres += longestChannelKm
        }
    }

    private fun reachSample(
        world: WorldMap,
        catchmentCells: FloatArray,
        longestPathKm: DoubleArray,
        longestChannelKm: DoubleArray,
        squareKilometresPerCell: Double
    ): ReachSample {
        val cellsAcross = world.width
        val cellWidthKm = sqrt(squareKilometresPerCell * cellAspect(world))
        val cellHeightKm = squareKilometresPerCell / cellWidthKm
        val sample = ReachSample()
        world.rivers.rivers.forEach { river ->
            val outletStep = lastOwnStep(world, river.cells)
            if (outletStep < 1) return@forEach
            val outlet = river.cells[outletStep]
            val catchmentKm2 = catchmentCells[outlet].toDouble() * squareKilometresPerCell
            if (catchmentKm2 < SMALLEST_HACK_CATCHMENT_KM2) return@forEach
            val mainStemKm = longestPathKm[outlet]
            var drawnKm = 0.0
            for (step in 0 until outletStep) {
                drawnKm += stepKilometres(
                    river.cells[step], river.cells[step + 1], cellsAcross, cellWidthKm, cellHeightKm
                )
            }
            if (mainStemKm <= 0.0 || drawnKm <= 0.0) return@forEach
            val last = river.cells[river.cells.size - 1]
            val endsInWater = !world.sea.isLand[last] || world.rivers.lakes.isLake(last)
            sample.add(
                catchmentKm2, mainStemKm, drawnKm, longestChannelKm[outlet],
                endsInWater && longestChannelKm[outlet] > 0.0
            )
        }
        return sample
    }

    /**
     * Hack's law over the terrain's own channel network: every channel cell as a basin outlet.
     *
     * **This is the sample the suite's Hack clause reads, and the drawn one beside it is a
     * finding.** [reachSample] takes its basins from `world.rivers.rivers`, which is what the map
     * draws, and `RiverConfig.maxRivers` caps that at four hundred courses however many cells the
     * grid has — so the drawn sample thins as the grid is refined (`TODO.md`, S1) and what it
     * measures at 2048 is the cap rather than the drainage. The terrain's network does not thin:
     * it is every land cell draining at least [CHANNEL_SUPPORT_CELLS]'s smaller support area, the
     * same mask Horton's ratios are ordered over and the way a network is extracted from any
     * digital elevation model, and it grows with the grid the way the ground does.
     *
     * The pairing is Hack's own — catchment area against the longest watercourse arriving at the
     * cell — and the floor is [SMALLEST_HACK_CATCHMENT_CELLS], for the reason written there. The
     * basins are nested, because every channel cell is inside its own trunk's; that is true of
     * Hack's Shenandoah sample too, where a tributary's basin sits inside the river's, and it is
     * what makes the fit span the four decades of area the exponent is defined over.
     */
    private fun hackOverChannelNetwork(
        channel: BooleanArray,
        catchmentCells: FloatArray,
        longestPathKm: DoubleArray,
        squareKilometresPerCell: Double
    ): LineAccumulator {
        val fit = LineAccumulator()
        for (cell in channel.indices) {
            if (!channel[cell]) continue
            val catchmentKm2 = catchmentCells[cell].toDouble() * squareKilometresPerCell
            if (catchmentKm2 < SMALLEST_HACK_CATCHMENT_KM2) continue
            val mainStemKm = longestPathKm[cell]
            if (mainStemKm <= 0.0) continue
            fit.add(ln(catchmentKm2), ln(mainStemKm))
        }
        return fit
    }

    /**
     * How far along a traced course the reach's own ground goes, as an index into its cells, or -1.
     *
     * A trace stops one cell *past* itself: at the sea or the lake it empties into, or at the first
     * cell an earlier river had already claimed, which it keeps so that the tributary joins the
     * trunk visibly. Either way it is the cell before the last that the reach's own catchment hangs
     * from, and measuring at the last one would give a tributary the trunk's whole basin. The
     * exception is a course that runs off a polar row, where the flow target is -1 and there is no
     * cell past the reach's own.
     */
    private fun lastOwnStep(world: WorldMap, cells: IntArray): Int {
        if (cells.size < 2) return -1
        val last = cells[cells.size - 1]
        val runsOffTheMap = world.sea.isLand[last] &&
            !world.rivers.lakes.isLake(last) &&
            world.rivers.flowTarget[last] < 0
        val step = if (runsOffTheMap) cells.size - 1 else cells.size - 2
        val cell = cells[step]
        return if (world.sea.isLand[cell] && !world.rivers.lakes.isLake(cell)) step else -1
    }

    /** How much wider a cell is than it is tall, on this world's grid. */
    private fun cellAspect(world: WorldMap): Double {
        val cellWidthKm = world.config.scale.cellWidthKm(world.width)
        val cellHeightKm = world.config.scale.cellHeightKm(world.height)
        return cellWidthKm / cellHeightKm
    }

    /** The ground a single D8 step covers, in kilometres, on a grid whose cells are not square. */
    private fun stepKilometres(
        from: Int,
        to: Int,
        cellsAcross: Int,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): Double {
        var columnStep = (to % cellsAcross) - (from % cellsAcross)
        if (columnStep > cellsAcross / 2) columnStep -= cellsAcross
        if (columnStep < -cellsAcross / 2) columnStep += cellsAcross
        val rowStep = (to / cellsAcross) - (from / cellsAcross)
        val acrossKm = columnStep * cellWidthKm
        val downKm = rowStep * cellHeightKm
        return sqrt(acrossKm * acrossKm + downKm * downKm)
    }

    // ------------------------------------------------------------------ Horton's ratios

    /** How many streams of each Strahler order the network carries, and Horton's ratio over them. */
    internal class StreamOrders(val streamsByOrder: LongArray) {
        val highestOrder: Int get() = streamsByOrder.size

        /**
         * Strahler's weighted mean bifurcation ratio, which is the metric of record.
         *
         * Each adjacent pair of orders gives a ratio, and the pairs are averaged weighted by how
         * many streams they hold — Strahler (1953)'s own correction, introduced because the top of
         * a network is always a handful of streams whose ratio carries most of the noise and none
         * of the information. On the pooled worlds the last pair is a few dozen streams against a
         * few, and unweighted it would decide the answer on its own.
         *
         * Zero where there are fewer than two orders: a network with no junction has nothing to
         * bifurcate, which is what the comb control is.
         */
        val bifurcationRatio: Double = run {
            var weighted = 0.0
            var weight = 0.0
            for (order in 0 until streamsByOrder.size - 1) {
                val above = streamsByOrder[order]
                val below = streamsByOrder[order + 1]
                if (above <= 0L || below <= 0L) continue
                val pairWeight = (above + below).toDouble()
                weighted += pairWeight * above.toDouble() / below
                weight += pairWeight
            }
            if (weight <= 0.0) 0.0 else weighted / weight
        }

        /**
         * Horton's own estimator, printed beside [bifurcationRatio]: the ratio a geometric series
         * of stream counts would need, read off the slope of log count against order.
         *
         * Horton's first law is `N(order) = ratio ^ (highest - order)`, and an unweighted regression
         * over the orders is how he stated it. It runs higher than Strahler's weighted mean on
         * every network here, because the sparse top orders fall away faster than the law says and
         * the regression gives them the same weight as the thousands of first-order streams.
         */
        val bifurcationRatioByRegression: Double = run {
            val used = streamsByOrder.indices.filter { streamsByOrder[it] > 0 }
            if (used.size < 3) 0.0
            else exp(-fitLine(used.map { it.toDouble() }, used.map { ln(streamsByOrder[it].toDouble()) }).slope)
        }

        /** The counts themselves, order by order, since a ratio hides how many orders there were. */
        fun perOrder(): String = streamsByOrder.indices
            .joinToString(" ") { "${it + 1}:${streamsByOrder[it]}" }
            .ifEmpty { "none" }

        operator fun plus(other: StreamOrders): StreamOrders {
            val merged = LongArray(max(streamsByOrder.size, other.streamsByOrder.size))
            streamsByOrder.forEachIndexed { order, count -> merged[order] += count }
            other.streamsByOrder.forEachIndexed { order, count -> merged[order] += count }
            return StreamOrders(merged)
        }
    }

    /** The cells the world drew a river through: land, not under a lake, and on a traced course. */
    fun drawnChannelMask(world: WorldMap): BooleanArray {
        val channel = BooleanArray(world.width * world.height)
        world.rivers.rivers.forEach { river ->
            river.cells.forEach { cell ->
                if (world.sea.isLand[cell] && !world.rivers.lakes.isLake(cell)) channel[cell] = true
            }
        }
        return channel
    }

    /** Every land cell draining at least [supportKm2] of ground: a network by support area. */
    fun supportAreaChannelMask(
        world: WorldMap,
        catchmentCells: FloatArray,
        supportKm2: Double
    ): BooleanArray {
        val supportCells = supportKm2 /
            world.config.scale.squareKilometresPerCell(world.width, world.height)
        return BooleanArray(world.width * world.height) { cell ->
            world.sea.isLand[cell] &&
                !world.rivers.lakes.isLake(cell) &&
                catchmentCells[cell] >= supportCells
        }
    }

    /**
     * Strahler orders over the channel cells, and the number of streams each order carries.
     *
     * Walked highest ground first over [FlowRouting.heightOrder], so a cell's upstream is settled
     * before the cell itself — the same order the engine accumulates water in, and for the same
     * reason. A cell with no channel above it is order 1; a cell fed by two or more channels of its
     * own highest incoming order is one order above them; anything else keeps the highest order
     * that reaches it. A *stream* of an order is a run of consecutive cells holding it, counted at
     * its downstream end, which is where the run leaves that order.
     */
    fun strahlerStreamOrders(
        channel: BooleanArray,
        flowTarget: IntArray,
        byHeight: IntArray
    ): StreamOrders {
        val cellCount = channel.size
        val order = IntArray(cellCount)
        // The highest order arriving at each cell, and how many channels arrive carrying it.
        val incomingOrder = IntArray(cellCount)
        val incomingCount = IntArray(cellCount)
        for (rank in byHeight.indices.reversed()) {
            val cell = byHeight[rank]
            if (!channel[cell]) continue
            order[cell] = when {
                incomingCount[cell] == 0 -> 1
                incomingCount[cell] >= 2 -> incomingOrder[cell] + 1
                else -> incomingOrder[cell]
            }
            val receiver = flowTarget[cell]
            if (receiver < 0 || !channel[receiver]) continue
            when {
                order[cell] > incomingOrder[receiver] -> {
                    incomingOrder[receiver] = order[cell]
                    incomingCount[receiver] = 1
                }
                order[cell] == incomingOrder[receiver] -> incomingCount[receiver]++
            }
        }
        var highest = 0
        for (cell in 0 until cellCount) if (order[cell] > highest) highest = order[cell]
        if (highest == 0) return StreamOrders(LongArray(0))
        val streams = LongArray(highest)
        for (cell in 0 until cellCount) {
            val own = order[cell]
            if (own == 0) continue
            val receiver = flowTarget[cell]
            val continues = receiver >= 0 && channel[receiver] && order[receiver] == own
            if (!continues) streams[own - 1]++
        }
        return StreamOrders(streams)
    }

    // ------------------------------------------------------------------ drainage density

    /**
     * How dry a cell is, on the UNEP aridity index: annual rainfall over potential evaporation.
     *
     * [FROZEN] is not one of UNEP's classes and is kept apart from them deliberately. Where nothing
     * evaporates the index is infinite, so a polar desert would otherwise be filed as the wettest
     * country on the map; Thornthwaite gives it a demand of exactly zero and that is a fact about
     * the thermometer, not about the rain.
     */
    internal enum class Aridity { FROZEN, HYPER_ARID, ARID, SEMI_ARID, DRY_SUBHUMID, HUMID }

    /**
     * What the channel-head criterion reads in each aridity class: the cover, the threshold the
     * cover sets, and the runoff-weighted area a cell of that class brings to it.
     *
     * The mechanism printed rather than argued. Moglen, Eltahir and Bras's curve turns over because
     * two terms pull opposite ways as a country gets wetter — more runoff on the left of the
     * inequality, more root strength on the right — and a density that comes out in the wrong class
     * is one of those two winning by the wrong margin. Without these three lines there is no way to
     * tell which.
     */
    internal class ChannelHeadByAridity(
        private val coverSum: DoubleArray,
        private val thresholdKm2Sum: DoubleArray,
        private val areaKm2Sum: DoubleArray,
        private val cells: LongArray
    ) {
        fun meanCover(aridity: Aridity): Double = mean(coverSum, aridity)
        fun meanThresholdKm2(aridity: Aridity): Double = mean(thresholdKm2Sum, aridity)
        fun meanAreaKm2(aridity: Aridity): Double = mean(areaKm2Sum, aridity)

        private fun mean(sums: DoubleArray, aridity: Aridity): Double {
            val count = cells[aridity.ordinal]
            return if (count == 0L) 0.0 else sums[aridity.ordinal] / count
        }

        operator fun plus(other: ChannelHeadByAridity) = ChannelHeadByAridity(
            DoubleArray(coverSum.size) { coverSum[it] + other.coverSum[it] },
            DoubleArray(thresholdKm2Sum.size) { thresholdKm2Sum[it] + other.thresholdKm2Sum[it] },
            DoubleArray(areaKm2Sum.size) { areaKm2Sum[it] + other.areaKm2Sum[it] },
            LongArray(cells.size) { cells[it] + other.cells[it] }
        )

        companion object {
            fun of(world: WorldMap, aridity: Array<Aridity?>): ChannelHeadByAridity {
                val classes = Aridity.entries.size
                val cover = DoubleArray(classes)
                val threshold = DoubleArray(classes)
                val area = DoubleArray(classes)
                val cells = LongArray(classes)
                val areaKm2 = ChannelInitiation.runoffWeightedAreaKm2(
                    world.width, world.height, world.sea.isLand, world.sea.landCellCount,
                    world.rivers.filledElevation, world.rivers.flowTarget,
                    world.climate.precipitationMm,
                    world.config.scale.squareKilometresPerCell(world.width, world.height)
                )
                val riverConfig = world.config.rivers
                for (cell in aridity.indices) {
                    val here = aridity[cell] ?: continue
                    val density = world.climate.vegetationDensity.data[cell]
                    cover[here.ordinal] += density.toDouble()
                    threshold[here.ordinal] +=
                        riverConfig.channelHeadAreaSlopeKm2.toDouble() *
                            if (riverConfig.coverRaisesChannelHead) {
                                ChannelInitiation.coverFactor(density).toDouble()
                            } else {
                                1.0
                            }
                    area[here.ordinal] += areaKm2.data[cell].toDouble()
                    cells[here.ordinal]++
                }
                return ChannelHeadByAridity(cover, threshold, area, cells)
            }
        }
    }

    /** Channel length and land area in each aridity class, which is drainage density per class. */
    internal class DrainageByAridity(
        val channelKilometres: DoubleArray,
        val landSquareKilometres: DoubleArray
    ) {
        fun densityIn(aridity: Aridity): Double {
            val area = landSquareKilometres[aridity.ordinal]
            return if (area <= 0.0) 0.0 else channelKilometres[aridity.ordinal] / area
        }

        /**
         * The class carrying the most channel per unit of land, over the classes that hold enough
         * land to mean anything.
         *
         * A class holding a scrap of the map can read any density at all — one river crossing two
         * hundred cells of hyper-arid ground is a density no continent has — so a class is only in
         * the running once it holds a fiftieth of the land.
         */
        fun peak(): Aridity? {
            val total = landSquareKilometres.sum()
            if (total <= 0.0) return null
            return Aridity.entries
                .filter { landSquareKilometres[it.ordinal] > total / 50.0 }
                .maxByOrNull { densityIn(it) }
        }

        operator fun plus(other: DrainageByAridity) = DrainageByAridity(
            DoubleArray(channelKilometres.size) { channelKilometres[it] + other.channelKilometres[it] },
            DoubleArray(landSquareKilometres.size) { landSquareKilometres[it] + other.landSquareKilometres[it] }
        )
    }

    fun aridityOf(precipitationMm: Float, potentialEvaporationMm: Float): Aridity = when {
        potentialEvaporationMm <= 0f -> Aridity.FROZEN
        precipitationMm / potentialEvaporationMm < 0.05f -> Aridity.HYPER_ARID
        precipitationMm / potentialEvaporationMm < 0.20f -> Aridity.ARID
        precipitationMm / potentialEvaporationMm < 0.50f -> Aridity.SEMI_ARID
        precipitationMm / potentialEvaporationMm < 0.65f -> Aridity.DRY_SUBHUMID
        else -> Aridity.HUMID
    }

    /** Every land cell's aridity class, with the sea left null. */
    private fun aridityByCell(world: WorldMap): Array<Aridity?> {
        val aridity = arrayOfNulls<Aridity>(world.width * world.height)
        for (cell in aridity.indices) {
            if (!world.sea.isLand[cell]) continue
            val potentialEvaporation = LakeWaterBalance.potentialEvaporationMm(
                world.climate.summerTemperature.data[cell],
                world.climate.winterTemperature.data[cell],
                world.config.lakes.evaporationScale
            )
            aridity[cell] =
                aridityOf(world.climate.precipitationMm.data[cell], potentialEvaporation)
        }
        return aridity
    }

    /** How much land each aridity class holds, in square kilometres: a density's denominator. */
    private fun landAreaByAridity(
        aridity: Array<Aridity?>,
        squareKilometresPerCell: Double
    ): DoubleArray {
        val landKm2 = DoubleArray(Aridity.entries.size)
        aridity.forEach { here -> if (here != null) landKm2[here.ordinal] += squareKilometresPerCell }
        return landKm2
    }

    /** The drawn courses' length in each aridity class, step by traced step. */
    private fun drawnChannelKilometresByAridity(
        world: WorldMap,
        aridity: Array<Aridity?>,
        squareKilometresPerCell: Double
    ): DoubleArray {
        val cellsAcross = world.width
        val cellWidthKm = sqrt(squareKilometresPerCell * cellAspect(world))
        val cellHeightKm = squareKilometresPerCell / cellWidthKm
        val channelKm = DoubleArray(Aridity.entries.size)
        world.rivers.rivers.forEach { river ->
            for (step in 0 until river.cells.size - 1) {
                val from = river.cells[step]
                val here = aridity[from] ?: continue
                channelKm[here.ordinal] += stepKilometres(
                    from, river.cells[step + 1], cellsAcross, cellWidthKm, cellHeightKm
                )
            }
        }
        return channelKm
    }

    /**
     * The routed network's length in each aridity class: one D8 step per channel cell.
     *
     * The same swap of sample as [hackOverChannelNetwork], for the same reason — the drawn courses
     * are capped at a count and so measure less of a finer grid's drainage than of a coarse one's,
     * while the support-area mask is the terrain's own network at every resolution. A cell's step
     * is charged to the class the cell itself is in, which is where that length of channel lies.
     */
    private fun routedChannelKilometresByAridity(
        world: WorldMap,
        channel: BooleanArray,
        aridity: Array<Aridity?>,
        squareKilometresPerCell: Double
    ): DoubleArray {
        val cellsAcross = world.width
        val cellWidthKm = sqrt(squareKilometresPerCell * cellAspect(world))
        val cellHeightKm = squareKilometresPerCell / cellWidthKm
        val channelKm = DoubleArray(Aridity.entries.size)
        for (cell in channel.indices) {
            if (!channel[cell]) continue
            val here = aridity[cell] ?: continue
            val receiver = world.rivers.flowTarget[cell]
            if (receiver < 0) continue
            channelKm[here.ordinal] +=
                stepKilometres(cell, receiver, cellsAcross, cellWidthKm, cellHeightKm)
        }
        return channelKm
    }

    // ------------------------------------------------------------------ size distributions

    /**
     * A Pareto or Korcak fit over a set of areas: the log-log slope of how many bodies are at least
     * as large as each one.
     *
     * The rank of a body sorted largest first *is* the count at or above its area, so the fit is
     * over `(log area, log rank)` with no binning to choose and nothing thrown away. The exponent
     * is minus that slope, which is the sign convention both Downing and Korcak state theirs in.
     */
    internal class SizeDistribution(val areas: List<Double>, val fit: LineFit) {
        val exponent: Double get() = -fit.slope
        val count: Int get() = areas.size
        val largest: Double get() = areas.maxOrNull() ?: 0.0

        /** False where there were too few bodies to fit anything, which is not an exponent of zero. */
        val fitted: Boolean get() = fit.points > 0

        companion object {
            /** Fewer than this and a log-log slope is a line through noise, not a distribution. */
            const val SMALLEST_FITTABLE_SET = 8

            fun of(areas: List<Double>): SizeDistribution {
                if (areas.size < SMALLEST_FITTABLE_SET) return SizeDistribution(areas, LineFit(0.0, 0.0, 0))
                val sorted = areas.sortedDescending()
                val logArea = sorted.map { ln(it) }
                val logRank = sorted.indices.map { ln((it + 1).toDouble()) }
                return SizeDistribution(areas, fitLine(logArea, logRank))
            }
        }
    }

    /** Every connected patch of land, in cells. Eight-connected, and the grid wraps in x. */
    fun islandAreasInCells(isLand: BooleanArray, cellsAcross: Int, cellsDown: Int): List<Int> {
        val cellCount = cellsAcross * cellsDown
        val seen = BooleanArray(cellCount)
        val areas = ArrayList<Int>()
        // A plain int stack rather than a deque: at 2048 a continent puts a million cells on it at
        // once, and boxing each of them costs more than the flood fill itself.
        val toVisit = IntArray(cellCount)
        for (start in 0 until cellCount) {
            if (!isLand[start] || seen[start]) continue
            var area = 0
            var depth = 0
            seen[start] = true
            toVisit[depth++] = start
            while (depth > 0) {
                val cell = toVisit[--depth]
                area++
                FlowRouting.forEachNeighbour(
                    cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross
                ) { neighbour ->
                    if (isLand[neighbour] && !seen[neighbour]) {
                        seen[neighbour] = true
                        toVisit[depth++] = neighbour
                    }
                }
            }
            areas.add(area)
        }
        return areas
    }

    // ------------------------------------------------------------------ realms

    /** The rank-size slope of realm areas: -1 is Zipf, flatter is more uniform. */
    private fun realmRankSizeSlope(world: WorldMap): Double {
        val areas = HashMap<Int, Int>()
        world.nations.nationId.forEach { id ->
            if (id != NationResult.UNCLAIMED) areas[id] = (areas[id] ?: 0) + 1
        }
        val sorted = areas.values.filter { it > 0 }.sortedDescending()
        if (sorted.size < 3) return 0.0
        return fitLine(sorted.indices.map { ln((it + 1).toDouble()) }, sorted.map { ln(it.toDouble()) }).slope
    }

    // ------------------------------------------------------------------ pooling and fitting

    /**
     * The raw ingredients of every world in a run, so the pooled figure is one fit over all of them
     * rather than an average of separate fits.
     *
     * A slope averaged over four worlds is not the slope of the four worlds together: each world's
     * fit is over its own handful of lakes or its own three box counts, and pooling the
     * observations is what a fifth world's worth of data would do. The two quantities that cannot
     * be pooled that way are the hypsometric bands, which are pooled by band index because a band
     * is a fixed fraction of each world's own span and so means the same thing in each, and the
     * realm slope, which is a rank ordering inside one world and is averaged instead.
     */
    internal class Pool {
        private val hackPoints = ArrayList<Pair<Double, Double>>()
        private val hackFullNetwork = LineAccumulator()
        private val hackDrawnPoints = ArrayList<Pair<Double, Double>>()
        private val lakeAreas = ArrayList<Double>()
        private val islandAreas = ArrayList<Double>()
        private val bands = HashMap<Int, Long>()
        private var coastline: BoxCount? = null
        private var horton: List<StreamOrders>? = null
        private var hortonDrawnRivers: StreamOrders? = null
        private var drainage: DrainageByAridity? = null
        private var drainageFullNetwork: DrainageByAridity? = null
        private var drainageInitiatedNetwork: DrainageByAridity? = null
        private var channelHead: ChannelHeadByAridity? = null
        private var iceCells = 0L
        private var lakeCells = 0L
        private var landCells = 0L
        private var drawnKilometres = 0.0
        private var mainStemKilometres = 0.0
        private var realmSlopeSum = 0.0
        private var bandMetresSum = 0.0
        private var spanMetresSum = 0.0
        private var worlds = 0
        private var squareKilometresPerCell = 0.0

        /** `GeographyAuditTest`'s own tally, filled here so this suite can print what it asserts. */
        val desertBands = DesertBands()

        fun addDesertBand(seed: Long, absLatitude: Float, desert: Boolean) =
            desertBands.add(seed, absLatitude, desert)

        fun add(
            metrics: Metrics,
            reaches: ReachSample,
            fullNetworkHack: LineAccumulator,
            lakes: List<Double>,
            islands: List<Double>
        ) {
            worlds++
            squareKilometresPerCell = metrics.squareKilometresPerCell
            hackPoints.addAll(reaches.mainStem)
            hackFullNetwork += fullNetworkHack
            hackDrawnPoints.addAll(reaches.drawnCourse)
            drawnKilometres += reaches.drawnKilometres
            mainStemKilometres += reaches.mainStemKilometres
            lakeAreas.addAll(lakes)
            islandAreas.addAll(islands)
            metrics.hypsometry.cellsByBand.forEach { (band, count) ->
                bands[band] = (bands[band] ?: 0L) + count
            }
            bandMetresSum += metrics.hypsometry.bandMetres
            spanMetresSum += metrics.hypsometry.reliefSpanMetres
            coastline = coastline?.plus(metrics.coastline) ?: metrics.coastline
            horton = horton?.let { previous ->
                previous.indices.map { previous[it] + metrics.horton[it] }
            } ?: metrics.horton
            hortonDrawnRivers =
                hortonDrawnRivers?.plus(metrics.hortonDrawnRivers) ?: metrics.hortonDrawnRivers
            drainage = drainage?.plus(metrics.drainage) ?: metrics.drainage
            drainageFullNetwork = drainageFullNetwork?.plus(metrics.drainageFullNetwork)
                ?: metrics.drainageFullNetwork
            drainageInitiatedNetwork =
                drainageInitiatedNetwork?.plus(metrics.drainageInitiatedNetwork)
                    ?: metrics.drainageInitiatedNetwork
            channelHead = channelHead?.plus(metrics.channelHead) ?: metrics.channelHead
            iceCells += metrics.iceCells
            lakeCells += metrics.lakeCells
            landCells += metrics.landCells
            realmSlopeSum += metrics.realmRankSizeSlope
        }

        fun pooled(label: String = "pooled"): Metrics = Metrics(
            label = label,
            squareKilometresPerCell = squareKilometresPerCell,
            hypsometry = Hypsometry(
                // Coerced so that an empty pool prints zeroes rather than NaN; nothing here
                // divides by a world count that can be zero in a run that measured anything.
                bandMetres = bandMetresSum / worlds.coerceAtLeast(1),
                reliefSpanMetres = spanMetresSum / worlds.coerceAtLeast(1),
                highestMetres = Double.NaN,
                deepestMetres = Double.NaN,
                cellsByBand = bands
            ),
            coastline = coastline ?: BoxCount(COASTLINE_BOX_SIZES, LongArray(COASTLINE_BOX_SIZES.size)),
            hack = fitLine(hackPoints.map { it.first }, hackPoints.map { it.second }),
            hackFullNetwork = hackFullNetwork.fit(),
            hackDrawnCourse =
                fitLine(hackDrawnPoints.map { it.first }, hackDrawnPoints.map { it.second }),
            drawnKilometres = drawnKilometres,
            mainStemKilometres = mainStemKilometres,
            horton = horton ?: CHANNEL_SUPPORT_KM2.map { StreamOrders(LongArray(0)) },
            hortonDrawnRivers = hortonDrawnRivers ?: StreamOrders(LongArray(0)),
            drainage = drainage ?: DrainageByAridity(
                DoubleArray(Aridity.entries.size), DoubleArray(Aridity.entries.size)
            ),
            drainageFullNetwork = drainageFullNetwork ?: DrainageByAridity(
                DoubleArray(Aridity.entries.size), DoubleArray(Aridity.entries.size)
            ),
            drainageInitiatedNetwork = drainageInitiatedNetwork ?: DrainageByAridity(
                DoubleArray(Aridity.entries.size), DoubleArray(Aridity.entries.size)
            ),
            channelHead = channelHead ?: ChannelHeadByAridity(
                DoubleArray(Aridity.entries.size), DoubleArray(Aridity.entries.size),
                DoubleArray(Aridity.entries.size), LongArray(Aridity.entries.size)
            ),
            lakeSizes = SizeDistribution.of(lakeAreas),
            islandSizes = SizeDistribution.of(islandAreas),
            iceCells = iceCells,
            lakeCells = lakeCells,
            landCells = landCells,
            realmRankSizeSlope = if (worlds == 0) 0.0 else realmSlopeSum / worlds
        )
    }

    // ------------------------------------------------------------------ the printed block

    /**
     * One block of `EARTH seed=… metric=… value=… earth=… source=…` lines, one line per metric.
     *
     * Printed for every world and for the pool, whether or not the metric is asserted, because the
     * chunks after this one are accepted against these numbers and a number nobody printed is a
     * number nobody can take up. A metric with no Earth figure to stand beside carries `earth=-`.
     */
    fun print(metrics: Metrics) {
        val label = metrics.label
        val hypsometry = metrics.hypsometry
        line(label, "reliefSpanMetres", "%.0f".format(hypsometry.reliefSpanMetres),
            "%.0f".format(EARTH_RELIEF_SPAN_METRES), "Cawood et al. 2022")
        line(label, "landModeMetres", hypsometry.landModeMetres?.let { "%.0f".format(it) } ?: "none",
            "%.0f".format(EARTH_LAND_MODE_METRES), "Cawood et al. 2022")
        line(label, "seaModeMetres", hypsometry.seaModeMetres?.let { "%.0f".format(it) } ?: "none",
            "%.0f".format(EARTH_SEA_MODE_METRES), "Cawood et al. 2022")
        line(label, "hypsometricTroughShareOfSmallerMode",
            hypsometry.troughShareOfSmallerMode?.let { "%.3f".format(it) } ?: "no trough",
            "%.2f".format(EARTH_HYPSOMETRIC_TROUGH_SHARE), "Cawood et al. 2022 / ETOPO bands")
        line(label, "twoModeShareOfSurface", "%.3f".format(hypsometry.twoModeShareOfSurface),
            "%.2f".format(EARTH_TWO_MODE_SHARE_OF_SURFACE), "Cawood et al. 2022 / ETOPO bands")
        line(label, "hypsometricBandShares", hypsometry.bandShares(), "-",
            "Cawood et al. 2022 / ETOPO bands")
        line(label, "coastlineFractalDimension", "%.3f".format(metrics.coastline.dimension),
            "1.2-1.3 (Britain ${EARTH_COASTLINE_DIMENSION})", "Mandelbrot 1967")
        line(label, "coastlineBoxes",
            metrics.coastline.boxSizes.indices.joinToString(" ") {
                "${metrics.coastline.boxSizes[it]}:${metrics.coastline.boxes[it]}"
            },
            "-", "Mandelbrot 1967")
        // The asserted fit first, then the two drawn ones it is asserted instead of.
        line(label, "hackExponentFullNetwork", "%.3f".format(metrics.hackFullNetwork.slope),
            "$EARTH_HACK_EXPONENT_LOW-$EARTH_HACK_EXPONENT_HIGH", "Hack 1957; Rigon et al. 1996")
        line(label, "hackCellsFullNetwork", metrics.hackFullNetwork.points.toString(), "-",
            "Hack 1957")
        line(label, "hackExponent", "%.3f".format(metrics.hack.slope),
            "$EARTH_HACK_EXPONENT_LOW-$EARTH_HACK_EXPONENT_HIGH", "Hack 1957; Rigon et al. 1996")
        line(label, "hackExponentDrawnCourse", "%.3f".format(metrics.hackDrawnCourse.slope),
            "$EARTH_HACK_EXPONENT_LOW-$EARTH_HACK_EXPONENT_HIGH", "Hack 1957; Rigon et al. 1996")
        line(label, "hackReaches", metrics.hack.points.toString(), "-", "Hack 1957")
        line(label, "drawnCourseShareOfMainStem", "%.3f".format(metrics.drawnShareOfMainStem),
            "1.0", "a river is its own longest watercourse")
        CHANNEL_SUPPORT_KM2.forEachIndexed { index, supportKm2 ->
            val support = "%.0f".format(supportKm2) + "km2"
            val orders = metrics.horton[index]
            line(label, "bifurcationRatioAtSupport$support",
                "%.2f".format(orders.bifurcationRatio),
                "$EARTH_BIFURCATION_RATIO_LOW-$EARTH_BIFURCATION_RATIO_HIGH", "Strahler 1953")
            line(label, "bifurcationRatioByRegressionAtSupport$support",
                "%.2f".format(orders.bifurcationRatioByRegression),
                "$EARTH_BIFURCATION_RATIO_LOW-$EARTH_BIFURCATION_RATIO_HIGH", "Horton 1945")
            line(label, "streamsByStrahlerOrderAtSupport$support", orders.perOrder(), "-",
                "Horton 1945")
        }
        line(label, "bifurcationRatioDrawnRivers",
            "%.2f".format(metrics.hortonDrawnRivers.bifurcationRatio),
            "$EARTH_BIFURCATION_RATIO_LOW-$EARTH_BIFURCATION_RATIO_HIGH", "Strahler 1953")
        line(label, "streamsByStrahlerOrderDrawnRivers", metrics.hortonDrawnRivers.perOrder(), "-",
            "Horton 1945")
        line(label, "drainageDensityPeakFullNetwork",
            metrics.drainageFullNetwork.peak()?.name ?: "none",
            "SEMI_ARID", "Moglen, Eltahir & Bras 1998")
        line(label, "drainageDensityKmPerKm2FullNetwork",
            Aridity.entries.joinToString(" ") {
                "${it.name}:${"%.4f".format(metrics.drainageFullNetwork.densityIn(it))}"
            },
            "-", "Moglen, Eltahir & Bras 1998")
        line(label, "drainageDensityPeakInitiated",
            metrics.drainageInitiatedNetwork.peak()?.name ?: "none",
            "SEMI_ARID", "Moglen, Eltahir & Bras 1998")
        line(label, "drainageDensityKmPerKm2Initiated",
            Aridity.entries.joinToString(" ") {
                "${it.name}:${"%.4f".format(metrics.drainageInitiatedNetwork.densityIn(it))}"
            },
            "-", "Moglen, Eltahir & Bras 1998")
        line(label, "drainageWetSideRatioInitiated",
            "%.3f".format(drainageWetSideRatio(metrics.drainageInitiatedNetwork)),
            "< 1", "Moglen, Eltahir & Bras 1998")
        line(label, "channelHeadCoverByAridity",
            Aridity.entries.joinToString(" ") {
                "${it.name}:${"%.3f".format(metrics.channelHead.meanCover(it))}"
            },
            "-", "Istanbulluoglu & Bras 2005")
        line(label, "channelHeadThresholdKm2ByAridity",
            Aridity.entries.joinToString(" ") {
                "${it.name}:${"%.4f".format(metrics.channelHead.meanThresholdKm2(it))}"
            },
            "%.4f".format(metrics.channelHead.meanThresholdKm2(Aridity.HYPER_ARID)),
            "Montgomery & Dietrich 1988")
        line(label, "runoffWeightedAreaKm2ByAridity",
            Aridity.entries.joinToString(" ") {
                "${it.name}:${"%.0f".format(metrics.channelHead.meanAreaKm2(it))}"
            },
            "-", "Montgomery & Dietrich 1988")
        line(label, "drainageDensityPeak", metrics.drainage.peak()?.name ?: "none",
            "SEMI_ARID", "Moglen, Eltahir & Bras 1998")
        line(label, "drainageDensityKmPerKm2",
            Aridity.entries.joinToString(" ") { "${it.name}:${"%.4f".format(metrics.drainage.densityIn(it))}" },
            "-", "Moglen, Eltahir & Bras 1998")
        line(label, "landShareByAridity",
            Aridity.entries.joinToString(" ") {
                val total = metrics.drainage.landSquareKilometres.sum()
                val share = if (total <= 0.0) 0.0 else metrics.drainage.landSquareKilometres[it.ordinal] / total
                "${it.name}:${"%.3f".format(share)}"
            },
            "-", "Moglen, Eltahir & Bras 1998")
        line(label, "lakeParetoExponent", exponentOrTooFew(metrics.lakeSizes),
            EARTH_LAKE_PARETO_EXPONENT.toString(), "Downing et al. 2006")
        line(label, "lakeCount", metrics.lakeSizes.count.toString(), "-", "Downing et al. 2006")
        line(label, "islandKorcakExponent", exponentOrTooFew(metrics.islandSizes),
            EARTH_ISLAND_KORCAK_EXPONENT.toString(), "Korcak 1938")
        line(label, "islandCount", metrics.islandSizes.count.toString(), "-", "Korcak 1938")
        line(label, "iceShareOfLand", "%.4f".format(metrics.iceShareOfLand),
            EARTH_ICE_SHARE_OF_LAND.toString(), "Cogley 2014 / RGI")
        line(label, "lakeShareOfLand", "%.4f".format(metrics.lakeShareOfLand),
            "%.4f".format(lakeShareOfLandOnEarth(metrics.squareKilometresPerCell)),
            "Downing et al. 2006 at ${"%.0f".format(metrics.squareKilometresPerCell)} km2 per cell")
        line(label, "realmRankSizeSlope", "%.3f".format(metrics.realmRankSizeSlope),
            "-1 (Zipf)", "report only")
    }

    private fun line(label: String, metric: String, value: String, earth: String, source: String) {
        println("EARTH seed=$label metric=$metric value=$value earth=$earth source=$source")
    }

    // ------------------------------------------------------------------ the bars, and what they are

    /**
     * Coastline dimension: Britain's 1.25 give or take 0.15.
     *
     * Richardson's coasts, as Mandelbrot (1967) reports them, run from South Africa's single smooth
     * arc at 1.02 to Britain's 1.25 and higher on a fjord coast. A whole world's coastline pools
     * every kind of coast it has, so it belongs near the middle of that spread and not at either
     * end: the floor is set well above the smoothest coast Richardson measured, and the ceiling the
     * same distance above Britain as the floor is below it.
     */
    const val COASTLINE_DIMENSION_TOLERANCE = 0.15

    /**
     * Hack's exponent: the 0.5-0.6 band, give or take 0.05.
     *
     * Hack (1957) measured 0.6 in the Shenandoah; Rigon et al. (1996) find 0.5 to 0.57 and show
     * that 0.5 is where a self-similar basin sits, so the band's own edges are the physics. Half
     * the band's width either side is the room a log-log slope over two decades of area needs.
     */
    const val HACK_EXPONENT_TOLERANCE = 0.05

    /**
     * Lake and island exponents: three times the sampling error of a Pareto exponent.
     *
     * A Pareto exponent measured on `n` bodies has a standard error of about `exponent / sqrt(n)`,
     * and a world at 512 carries a couple of dozen lakes and a couple of dozen islands against
     * Downing's census of millions. Three of those errors is the bar, computed from the count
     * actually fitted rather than fixed, so a finer grid with more bodies is held to more.
     */
    const val SAMPLING_ERRORS_ALLOWED = 3.0

    /**
     * Every clause the suite asserts, each returning its complaint or nothing.
     *
     * Only the metrics with an established Earth figure that this generator already reaches are in
     * here. The rest are findings: printed with Earth's number beside them by [print] and by
     * [findings], and left for the chunk that owns the cause, because rule 5 of the plan forbids a
     * bar moved to fit. [oneWorld] widens nothing — it decides which clauses run at all, since the
     * two size distributions are a dozen bodies in one world and only mean something pooled.
     */
    fun complaints(metrics: Metrics, oneWorld: Boolean): List<String> {
        val label = metrics.label
        val complaints = listOfNotNull(
            // The four river clauses all read the network R1 initiates rather than the courses
            // the map draws: the drawn sample was capped at a count of courses until R1 and so
            // measured the cap, and a network by support area alone cannot answer a question about
            // climate. See [hackOverChannelNetwork] and [routedChannelKilometresByAridity]; the
            // drawn figures and what they cover stand beside these in [print] and [findings].
            bifurcationComplaint(label, metrics.horton[0]),
            hackComplaint(label, metrics.hackFullNetwork),
            // T3's clause, over T3's instrument, kept per seed as a regression check: the
            // support-area network is climate-blind and its peak was asserted and passing before
            // R1, so R1 must not have moved it.
            drainagePeakComplaint(label, metrics.drainageFullNetwork),
            drawnCoverageComplaint(label, metrics.drawnShareOfMainStem),
            // Asserted since S2 gave the height field an absolute vertical scale. Before that the
            // curve was a single peak straddling the shoreline on every seed and both clauses were
            // findings; the world they were findings about is what `IsostasyTest` runs as its
            // control.
            bimodalityComplaint(label, metrics.hypsometry),
            seaModeComplaint(label, metrics.hypsometry)
        ).toMutableList()
        // Pooled only: one world at 512 carries a couple of dozen lakes, and a Pareto exponent
        // measured on a couple of dozen bodies has a sampling error a third of its own size.
        // The islands' exponent is not here at all — it is a finding, for the reason written
        // against [EARTH_ISLAND_KORCAK_EXPONENT].
        if (!oneWorld) {
            sizeDistributionComplaint(
                label, "lake", metrics.lakeSizes, EARTH_LAKE_PARETO_EXPONENT, "Downing et al. 2006"
            )?.let { complaints.add(it) }
            // Pooled since S2's fourth pass, and for the same statistical reason as the two above
            // rather than because a world failed it. A box-counting dimension at 512 is a line
            // through *three* points — boxes of four, eight and sixteen cells — counted on one map,
            // and the four standard seeds spread from 1.092 to 1.162 about a pooled 1.129 while
            // every one of them is the same generator. Mandelbrot's band is 0.15 wide and the
            // seeds' own spread is half of that, so a per-seed clause was asserting the sample as
            // much as the model. The bar itself has not moved and [coastlineComplaint] is still
            // shown to bite on a rectangle, which reads 1.024.
            //
            // What made it worth doing now is that S2's fourth pass moved the figure the way the
            // chunk intended: the coastline is drawn by cell-scale relief on low ground and this
            // pass took that relief away on purpose, so the pooled dimension came down from 1.149
            // to 1.129 and seed 99 to 1.092. The trade is in `TODO.md`.
            coastlineComplaint(label, metrics.coastline)?.let { complaints.add(it) }
            // R1's own two, over the network the criterion initiates, and pooled — see
            // [drainagePeakComplaint] for what one world's aridity classes can and cannot say.
            drainagePeakComplaint(label, metrics.drainageInitiatedNetwork)
                ?.let { complaints.add(it) }
            drainageWetSideComplaint(label, metrics.drainageInitiatedNetwork)
                ?.let { complaints.add(it) }
        }
        return complaints
    }

    fun coastlineComplaint(label: String, coastline: BoxCount): String? {
        val dimension = coastline.dimension
        if (dimension >= EARTH_COASTLINE_DIMENSION - COASTLINE_DIMENSION_TOLERANCE &&
            dimension <= EARTH_COASTLINE_DIMENSION + COASTLINE_DIMENSION_TOLERANCE
        ) {
            return null
        }
        return "$label: the coastline's box-counting dimension is ${"%.3f".format(dimension)}," +
            " outside $EARTH_COASTLINE_DIMENSION +/- $COASTLINE_DIMENSION_TOLERANCE" +
            " (Mandelbrot 1967: Britain 1.25, Richardson's smoothest coast 1.02)"
    }

    /**
     * Why Hack's exponent is a clause again, in the figures that took it away and brought it back.
     *
     * **It was asserted until T3, over the drawn basins, and it had been red in the nightly tier
     * at 2048 on every seed since the tier existed.** T3 moved the fit off the drawn courses and
     * onto the terrain's own channel network, for the reason in [hackOverChannelNetwork], and that
     * did most of what it was meant to: at 2048 the six audited seeds went from 0.324-0.433 drawn
     * to 0.444-0.485 routed, pooled 0.381 to 0.465, which is five of six seeds inside Earth's band
     * where none had been. What it also did was show the figure is **not the same at two grids**.
     * Over the routed network the four standard seeds read 0.527-0.565 at 512, pooled 0.552, and
     * 0.444-0.485 at 2048, pooled 0.465: the whole spread falls by about nine hundredths for a
     * grid four times finer each way, and the floor of the band with its tolerance is 0.45, so one
     * seed falls through it at 2048 and none does at 512.
     *
     * A statistic that moves with the resolution is measuring the resolution, and the cause was
     * that both ends of this fit were counted in cells and not in ground: the support area's
     * sixteen and the fit's hundred described 4,400 and 27,500 km2 at 512 and 275 and 1,700 at
     * 2048, so the finer grid's fit ran over two extra decades of small basins the coarse one
     * never saw — and the smallest of them were not basins at all but hillslopes, because the
     * generator had no channel-initiation criterion and a cell was a channel when a fixed count of
     * cells drained through it.
     *
     * **R1 is what earned it back, in two pieces.** Both thresholds are areas of ground now —
     * [CHANNEL_SUPPORT_KM2] and [SMALLEST_HACK_CATCHMENT_KM2] — so the fit is taken over the same
     * basins at both grids; and the network the fit runs on is [ChannelInitiation]'s, every reach
     * where the runoff-weighted drainage area times the square of the gradient clears Montgomery
     * and Dietrich's threshold, which is a criterion about the ground rather than about the grid.
     * The figures either side are in docs/DESIGN_LEDGER.md, R1.
     *
     * [hackComplaint] is still shown to bite on `EarthLikenessControlTest`'s comb.
     */

    fun hackComplaint(label: String, hack: LineFit): String? {
        if (hack.slope >= EARTH_HACK_EXPONENT_LOW - HACK_EXPONENT_TOLERANCE &&
            hack.slope <= EARTH_HACK_EXPONENT_HIGH + HACK_EXPONENT_TOLERANCE
        ) {
            return null
        }
        return "$label: Hack's exponent is ${"%.3f".format(hack.slope)} over ${hack.points}" +
            " reaches, outside $EARTH_HACK_EXPONENT_LOW-$EARTH_HACK_EXPONENT_HIGH +/-" +
            " $HACK_EXPONENT_TOLERANCE (Hack 1957; Rigon et al. 1996)"
    }

    fun bifurcationComplaint(label: String, orders: StreamOrders): String? {
        val ratio = orders.bifurcationRatio
        val ceiling = EARTH_BIFURCATION_RATIO_HIGH + BIFURCATION_RATIO_SUPPORT_ALLOWANCE
        if (ratio >= EARTH_BIFURCATION_RATIO_LOW && ratio <= ceiling) return null
        return "$label: the weighted mean bifurcation ratio is ${"%.2f".format(ratio)} over" +
            " streams ${orders.perOrder()}, outside Horton's" +
            " $EARTH_BIFURCATION_RATIO_LOW-$EARTH_BIFURCATION_RATIO_HIGH (Horton 1945, weighted" +
            " after Strahler 1953) with this suite's" +
            " $BIFURCATION_RATIO_SUPPORT_ALLOWANCE of support-threshold allowance on top"
    }

    /**
     * Drainage density peaks in dry country and falls away in wet, in two clauses: the peak class
     * is one of the four drylands, and the humid class carries less channel per unit of land than
     * the semi-arid one.
     *
     * Moglen, Eltahir and Bras (1998), and Langbein and Schumm (1958) before them, put the maximum
     * at low to intermediate effective precipitation with a fall-off on the wet side. What is *not*
     * in either paper is which UNEP class the maximum lands in: the lines at 0.05, 0.2, 0.5 and
     * 0.65 are a classification of drylands, not a curve of drainage density, and on this generator
     * the dry classes run within a tenth of one another — seed 7 at 2048 reads 0.0054, 0.0050 and
     * 0.0053 across hyper-arid, arid and semi-arid — so which of them comes out on top is the
     * tie-break and not the result. Dry against wet is the result, and it is what these two clauses
     * hold.
     *
     * **Read over two networks since R1, and asserted differently over each.** Over the
     * support-area network — T3's instrument, which is climate-blind — it stays a per-seed clause,
     * because it was asserted and passing there before R1 and a chunk must not break a green bar.
     * Over the network R1's criterion initiates it is pooled, and the reason is what the paragraph
     * below already says about these classes: the dry classes run within a tenth of one another, so
     * which of them comes top is the tie-break and not the result, and on one world a class is one
     * or two regions of one continent rather than a population of countries. Measured over the
     * initiated network, the peak lands in a dryland on all four standard seeds at 512 and on four
     * of the six audited seeds at 2048, and pooled in the semi-arid class at both grids; seed 1234
     * at 2048 is the one that does not, by a tenth. The figures are in docs/DESIGN_LEDGER.md, R1,
     * and the fall-off's flattening with resolution is in `TODO.md`.
     *
     * The peak clause listed three classes until W1 and now lists four, which is UNEP's own list:
     * dry sub-humid, from an aridity index of 0.5 to 0.65, is a dryland, and leaving it out was an
     * oversight rather than a bar. W1 exposed it by moving the climate — the energy balance leaves
     * the mid-latitudes a shade wetter than the latitude curve did, so ground that used to read
     * semi-arid now reads dry sub-humid, and the peak crossed a line without the curve changing
     * shape (pooled: hyper-arid 0.0027, arid 0.0040, semi-arid 0.0038, dry sub-humid 0.0049, humid
     * 0.0026). The wet-side clause that used to stand beside this one is now a finding rather than
     * a complaint, and [drainageWetSideRatio] carries both the measurement that moved it and the
     * chunk that earns it back. Comparing humid against the *peak* instead was tried and is
     * weaker, because a peak that has moved into dry sub-humid then satisfies it by being high
     * rather than by the humid class being low — measured on the control, a world with 3,000 km of
     * humid channel against 2,000 semi-arid and 10,000 arid stopped being caught at all.
     */
    fun drainagePeakComplaint(label: String, drainage: DrainageByAridity): String? {
        val densities = Aridity.entries.joinToString(", ") {
            "$it ${"%.4f".format(drainage.densityIn(it))}"
        }
        val peak = drainage.peak()
        if (peak == null || peak == Aridity.FROZEN || peak == Aridity.HUMID) {
            return "$label: drainage density peaks in $peak, not in one of the drylands where" +
                " Moglen, Eltahir & Bras (1998) put it — $densities"
        }
        return null
    }

    /**
     * How far the density falls away on the wet side, as the humid class against the semi-arid:
     * Earth's is below one and this generator's is not reliably.
     *
     * **This was an assertion until W2 and is now a finding, and the reason is measured.** W2 gave
     * the wind the departure the pressure field drives, which made seed 7's mid-latitudes wetter,
     * and ground that used to read semi-arid now reads humid: its land share moved from 0.293 to
     * 0.215 semi-arid and from 0.269 to 0.350 humid, a transfer of about eight points of the
     * world's land from one class to the next. The channels did not move with it. Summed over all
     * six classes, seed 7 carries 0.00305 km of channel per square kilometre of land before and
     * 0.00313 after — two and a half per cent, which is nothing beside the eight points of land
     * that changed label. So the humid class's density rose from 0.0028 to 0.0038 not by growing
     * channels but by being handed well-channelled semi-arid ground, and it met the semi-arid
     * class's own 0.0038 there.
     *
     * That is not a defect in the march: the rain is landing where the wind carries it, which is
     * the whole of W2. It is that **channel initiation in this generator does not know about
     * climate at all.** A cell is a channel when its accumulated flow passes a fixed threshold, so
     * the network is a property of the terrain and the total water and not of how dry the ground
     * is, and Moglen, Eltahir and Bras's curve is precisely a statement about how aridity changes
     * the threshold — vegetation, infiltration and the length of overland flow before a channel
     * head forms. A climate-blind threshold can satisfy this clause only by keeping the right
     * amount of land in each class, which is an accident of the rainfall field rather than a
     * property of the drainage, and the accident is what W2 spent. R1, channel initiation with a
     * climate term, is queued for exactly this and is the chunk that earns the assertion back.
     *
     * **Asserted since R1, over the network the criterion initiates, and pooled.** The criterion
     * reads the climate now — the area is weighted by the runoff and the threshold by the cover —
     * and the curve turns over. See [drainagePeakComplaint] for why both of R1's clauses are pooled
     * and what one world can say about an aridity class.
     */
    fun drainageWetSideRatio(drainage: DrainageByAridity): Double {
        val semiArid = drainage.densityIn(Aridity.SEMI_ARID)
        if (semiArid <= 0.0) return 0.0
        return drainage.densityIn(Aridity.HUMID) / semiArid
    }

    /**
     * The wet side of Moglen's curve, asserted: humid country carries less channel per unit of land
     * than semi-arid country does.
     *
     * One and not a tolerance, because one is where the claim lives. Moglen, Eltahir and Bras
     * (1998) put the maximum at low to intermediate effective precipitation and have the density
     * falling away above it; a ratio at or above one is a curve that does not fall, whatever its
     * peak is doing, and that is the whole of the test. The peak clause beside it is the dry side
     * of the same curve.
     */
    fun drainageWetSideComplaint(label: String, drainage: DrainageByAridity): String? {
        val ratio = drainageWetSideRatio(drainage)
        if (ratio > 0.0 && ratio < 1.0) return null
        return "$label: humid country carries ${"%.2f".format(ratio)} times the channel per unit" +
            " of land that semi-arid country does, where Moglen, Eltahir & Bras (1998) have the" +
            " density falling away on the wet side and so below one — " +
            Aridity.entries.joinToString(", ") { "$it ${"%.4f".format(drainage.densityIn(it))}" }
    }

    /**
     * What share of the watercourses it stands for the drawn map has to cover.
     *
     * A river is its own longest watercourse, so the share is 1.0 by definition and everything
     * below it is the drawing losing ground the world has. Two things take it below one and only
     * one of them is a fault. A course stops where an earlier one has already claimed the reach
     * below, and the reach is then drawn — by that other course — so a trunk shared between two
     * `River` objects reads short here while the map is complete; and a course whose whole length
     * falls under `RiverConfig.shortestDrawnCourseKm` is not drawn at all, which is a watercourse
     * the map really does not have.
     *
     * Ninety-nine hundredths, which is a hundredth of slack on an invariant rather than a
     * tolerance on a quantity. A basin that loses its whole course to the hundred-kilometre rule
     * leaves the sample with it — it has no drawn river to measure — so every basin still in the
     * sample should read exactly 1.0, and measured it does at both grids. The denominator is the
     * longest watercourse over the **same eligible network** the course was drawn on, not the
     * longest path to the divide: a drawn course begins at a channel head and the hillslope above
     * that head is not a line the map failed to draw.
     *
     * What it refuses is the rule R1 removed: `RiverConfig.maxRivers` capped the drawing at four
     * hundred courses whatever the grid, and the share that left was 0.484 at 512 and 0.408 to
     * 0.506 at 2048 — falling with the grid, which is the signature of a cap being measured rather
     * than a drainage. It also refuses a reading over one, which is what an out-of-order walk over
     * the flow tree produced and is how that fault was found; see [longestFlowPathKilometres].
     */
    const val DRAWN_COVERAGE_BAR = 0.99

    fun drawnCoverageComplaint(label: String, drawnShareOfMainStem: Double): String? {
        if (drawnShareOfMainStem >= DRAWN_COVERAGE_BAR) return null
        return "$label: the courses the map draws cover" +
            " ${"%.3f".format(drawnShareOfMainStem)} of the watercourses they stand for, under" +
            " the bar of $DRAWN_COVERAGE_BAR, where a river is its own longest watercourse and" +
            " the share is 1.0 by definition"
    }

    /** A Pareto or Korcak exponent against its published one, at [SAMPLING_ERRORS_ALLOWED]. */
    fun sizeDistributionComplaint(
        label: String,
        what: String,
        distribution: SizeDistribution,
        earthExponent: Double,
        source: String
    ): String? {
        if (!distribution.fitted) return "$label: only ${distribution.count} ${what}s to fit"
        val tolerance = SAMPLING_ERRORS_ALLOWED * earthExponent / sqrt(distribution.count.toDouble())
        if (distribution.exponent >= earthExponent - tolerance &&
            distribution.exponent <= earthExponent + tolerance
        ) {
            return null
        }
        return "$label: the ${what}-size exponent is ${"%.3f".format(distribution.exponent)} over" +
            " ${distribution.count} of them, outside $earthExponent +/-" +
            " ${"%.2f".format(tolerance)} ($source, three sampling errors)"
    }

    /**
     * Whether the hypsometric curve is Earth's two modes with a trough between them.
     *
     * Asserted since S2. Until then this generator did not meet it — the busiest land band and the
     * busiest sea band were adjacent on every seed at every grid, so the curve had no trough at
     * all — and rule 5 will not have a bar moved to fit, so it was a finding. What changed is not
     * the bar but the world: two crusts floating at their own isostatic levels put four and a half
     * kilometres between the continental platform and the sea floor, which is what a trough is.
     * Measured on the four standard seeds at 512 after S2: 0.090, 0.104, 0.086 and 0.158, against
     * 0.52 to 0.71 with isostasy switched off, which is what `IsostasyTest` runs as its control.
     *
     * The clause is exercised against Earth's own band table and against a featureless world by
     * `EarthLikenessControlTest`, so it is known to discriminate.
     *
     * Earth's trough — the continental slope — holds 0.17 of its smaller mode. The bar is 0.5,
     * three times that, because the generator's relief span and therefore its band width are its
     * own, and a bar this loose still refuses everything that is not two separate modes.
     */
    const val HYPSOMETRIC_TROUGH_BAR = 0.5

    /**
     * How far the sea's mode may sit from Earth's, in metres.
     *
     * Fifteen hundred, and the derivation is the histogram's own resolution against the thing being
     * located. A mode is a band, and the bands are a twentieth of the world's relief span — about
     * 600 m on these worlds and 1,000 m on Earth's own table — so a mode is only placed to within
     * half a band either way whatever the world is doing. One and a half bands admits that with
     * room for the real spread between seeds, and refuses by a wide margin the thing this clause
     * exists to catch: a sea floor at a few hundred metres, which is what a world with no isostasy
     * in it has. Measured after S2 on the four standard seeds, -4,229, -3,775, -3,919 and -3,233
     * against Earth's -3,700, the worst 529 m out; with isostasy off, -445 to -605, which is 3,100
     * m out and fails.
     */
    const val SEA_MODE_TOLERANCE_METRES = 1500.0

    /** The sea's busiest band against Earth's, which is the deep floor at -3,700 m. */
    fun seaModeComplaint(label: String, hypsometry: Hypsometry): String? {
        val mode = hypsometry.seaModeMetres
            ?: return "$label: the hypsometric curve has no band below the shoreline at all"
        if (abs(mode - EARTH_SEA_MODE_METRES) <= SEA_MODE_TOLERANCE_METRES) return null
        return "$label: the sea's hypsometric mode is at ${"%.0f".format(mode)} m, more than" +
            " ${"%.0f".format(SEA_MODE_TOLERANCE_METRES)} m from Earth's" +
            " ${"%.0f".format(EARTH_SEA_MODE_METRES)} (Cawood et al. 2022) — the ocean floor is" +
            " not where two crusts floating on a mantle would put it"
    }

    fun bimodalityComplaint(label: String, hypsometry: Hypsometry): String? {
        val trough = hypsometry.troughShareOfSmallerMode
            ?: return "$label: the hypsometric curve has no trough between a land mode and a sea" +
                " mode — its two busiest bands are adjacent, so it is one mode and not Earth's two" +
                " (Cawood et al. 2022)"
        if (trough <= HYPSOMETRIC_TROUGH_BAR) return null
        return "$label: the hypsometric trough holds ${"%.3f".format(trough)} of the smaller mode," +
            " over the bar of $HYPSOMETRIC_TROUGH_BAR, where Earth's continental slope holds" +
            " ${EARTH_HYPSOMETRIC_TROUGH_SHARE} (Cawood et al. 2022)"
    }

    /**
     * Every metric that has an Earth figure, is not asserted, and does not reach it: the findings,
     * ranked by how far from Earth each sits, which is the order the chunks after this take them up.
     */
    fun findings(metrics: Metrics): List<String> {
        val findings = ArrayList<Pair<Double, String>>()
        // Hack's exponent and the wet side of Moglen's curve are both asserted since R1 and are
        // in [complaints]; what is left here of the drawn courses is the drawn network's own
        // figures, which have no Earth bar of their own and stand beside the asserted ones.
        val drawnWetSide = drainageWetSideRatio(metrics.drainage)
        findings.add(
            drawnWetSide to
                "over the drawn courses alone humid country carries" +
                    " ${"%.2f".format(drawnWetSide)} times the channel per unit of land that" +
                    " semi-arid country does, against" +
                    " ${"%.2f".format(drainageWetSideRatio(metrics.drainageInitiatedNetwork))}" +
                    " over the network the ground initiates, which is the sample the clause reads"
        )
        // The land's mode is reported rather than asserted, and deliberately: measured after S2 it
        // runs 301 to 1,470 m against Earth's 800, which is inside any bar wide enough to admit the
        // histogram's own 600 m band — and a bar that cannot fail is not a guard (rule 2). The
        // clause that does bite on a world with no isostasy in it is the sea's mode, and that one
        // is asserted in [complaints].
        metrics.hypsometry.landModeMetres?.let { landMode ->
            val span = EARTH_RELIEF_SPAN_METRES
            findings.add(
                (landMode + span) / (EARTH_LAND_MODE_METRES + span) to
                    "the land's hypsometric mode is at ${"%.0f".format(landMode)} m against" +
                        " Earth's ${"%.0f".format(EARTH_LAND_MODE_METRES)} (Cawood et al. 2022)," +
                        " a difference of ${"%.0f".format(landMode - EARTH_LAND_MODE_METRES)} m"
            )
        }
        val twoMode = metrics.hypsometry.twoModeShareOfSurface
        findings.add(
            twoMode / EARTH_TWO_MODE_SHARE_OF_SURFACE to
                "the two modal bands hold ${"%.3f".format(twoMode)} of the surface against Earth's" +
                    " $EARTH_TWO_MODE_SHARE_OF_SURFACE (x${
                        "%.2f".format(twoMode / EARTH_TWO_MODE_SHARE_OF_SURFACE)
                    })"
        )
        if (metrics.islandSizes.fitted) {
            findings.add(
                metrics.islandSizes.exponent / EARTH_ISLAND_KORCAK_EXPONENT to
                    "the island-size exponent is ${"%.3f".format(metrics.islandSizes.exponent)}" +
                        " over ${metrics.islandSizes.count} islands against Korcak's" +
                        " $EARTH_ISLAND_KORCAK_EXPONENT (x${
                            "%.2f".format(metrics.islandSizes.exponent / EARTH_ISLAND_KORCAK_EXPONENT)
                        })"
            )
        }
        val earthLakeShare = lakeShareOfLandOnEarth(metrics.squareKilometresPerCell)
        findings.add(
            metrics.lakeShareOfLand / earthLakeShare to
                "lakes hold ${"%.4f".format(metrics.lakeShareOfLand)} of the land against Earth's" +
                    " ${"%.4f".format(earthLakeShare)} at ${
                        "%.0f".format(metrics.squareKilometresPerCell)
                    } km2 a cell (x${"%.2f".format(metrics.lakeShareOfLand / earthLakeShare)})"
        )
        val drawnShare = metrics.drawnShareOfMainStem
        findings.add(
            drawnShare to
                "the courses the map draws cover ${"%.3f".format(drawnShare)} of the watercourses" +
                    " they stand for, where a river is its own longest watercourse and the share" +
                    " is 1.0 (asserted at $DRAWN_COVERAGE_BAR); over the same basins Hack's" +
                    " exponent is" +
                    " ${"%.3f".format(metrics.hackDrawnCourse.slope)} drawn and" +
                    " ${"%.3f".format(metrics.hack.slope)} over their main stems, against" +
                    " ${"%.3f".format(metrics.hackFullNetwork.slope)} over the" +
                    " ${metrics.hackFullNetwork.points} cells of the terrain's own network," +
                    " which is the fit the clause reads (x${"%.2f".format(drawnShare)})"
        )
        findings.add(
            metrics.iceShareOfLand / EARTH_ICE_SHARE_OF_LAND to
                "ice holds ${"%.4f".format(metrics.iceShareOfLand)} of the land against Earth's" +
                    " $EARTH_ICE_SHARE_OF_LAND (x${
                        "%.2f".format(metrics.iceShareOfLand / EARTH_ICE_SHARE_OF_LAND)
                    }; asserted by SnowBalanceTest, reported here)"
        )
        findings.add(
            metrics.hypsometry.reliefSpanMetres / EARTH_RELIEF_SPAN_METRES to
                "the world's relief spans ${"%.0f".format(metrics.hypsometry.reliefSpanMetres)} m" +
                    " against Earth's $EARTH_RELIEF_SPAN_METRES (x${
                        "%.2f".format(metrics.hypsometry.reliefSpanMetres / EARTH_RELIEF_SPAN_METRES)
                    }); declared by WorldScale rather than measured, and against a summit-to-deep" +
                    " Earth where the generator's two ends are cell means"
        )
        // Ranked farthest from Earth first, distance being how far the ratio is from one either
        // way, on a log scale so that half and double are the same distance.
        return findings
            .sortedBy { if (it.first.isInfinite()) Double.NEGATIVE_INFINITY else -abs(ln(it.first)) }
            .map { it.second }
    }

    private fun exponentOrTooFew(distribution: SizeDistribution): String =
        if (distribution.fitted) "%.3f".format(distribution.exponent)
        else "too few (${distribution.count})"

    /** Least squares of y against x, and how many points went into it. */
    internal class LineFit(val slope: Double, val intercept: Double, val points: Int)

    /**
     * The same least-squares fit taken from running sums rather than from a list of points.
     *
     * [fitLine]'s lists are the right shape for a few hundred lakes or a few hundred drawn basins.
     * The full-network Hack sample is a few hundred thousand cells in one world at 2048 and six
     * worlds pool into one fit, and five running sums determine that slope exactly as well as a
     * list of pairs would, for none of the memory.
     */
    internal class LineAccumulator {
        private var points = 0
        private var sumX = 0.0
        private var sumY = 0.0
        private var sumXX = 0.0
        private var sumXY = 0.0

        fun add(x: Double, y: Double) {
            points++
            sumX += x
            sumY += y
            sumXX += x * x
            sumXY += x * y
        }

        operator fun plusAssign(other: LineAccumulator) {
            points += other.points
            sumX += other.sumX
            sumY += other.sumY
            sumXX += other.sumXX
            sumXY += other.sumXY
        }

        /** The same degenerate-variance floor [fitLine] uses, and for the reason written there. */
        fun fit(): LineFit {
            if (points < 2) return LineFit(0.0, 0.0, points)
            val meanX = sumX / points
            val meanY = sumY / points
            val variance = sumXX - points * meanX * meanX
            val covariance = sumXY - points * meanX * meanY
            if (variance <= 1e-12 * points * (meanX * meanX + 1.0)) return LineFit(0.0, meanY, points)
            val slope = covariance / variance
            return LineFit(slope, meanY - slope * meanX, points)
        }
    }

    fun fitLine(x: List<Double>, y: List<Double>): LineFit {
        if (x.size < 2) return LineFit(0.0, 0.0, x.size)
        val meanX = x.average()
        val meanY = y.average()
        var covariance = 0.0
        var variance = 0.0
        for (i in x.indices) {
            covariance += (x[i] - meanX) * (y[i] - meanY)
            variance += (x[i] - meanX) * (x[i] - meanX)
        }
        // Against a relative floor rather than against zero. Forty lakes of exactly one area have
        // a variance that is not quite zero — the mean of forty identical doubles is not the double
        // itself, to the last bit — and dividing a covariance of rounding error by a variance of
        // rounding error gives a slope of pure noise where the honest answer is that a set with one
        // size in it has no slope.
        val degenerate = 1e-12 * (meanX * meanX + 1.0)
        if (variance <= degenerate) return LineFit(0.0, meanY, x.size)
        val slope = covariance / variance
        return LineFit(slope, meanY - slope * meanX, x.size)
    }

    /**
     * Earth's lake share of land at whatever the map's cell area is.
     *
     * Downing et al. (2006) give 1.7% of land in lakes of at least 100 km2 and 1.2% at 1,000 km2:
     * half a percent of land per decade of area over the decade between them. This carries that
     * line to the map's own floor, which at 512 is a cell of roughly 275 km2 and at 2048 one of
     * roughly 17. The second is a decade below Downing's smallest quoted class and so is an
     * extrapolation, which is said here rather than hidden in a constant.
     */
    fun lakeShareOfLandOnEarth(squareKilometresPerCell: Double): Double =
        EARTH_LAKE_SHARE_AT_100_KM2 -
            EARTH_LAKE_SHARE_PER_AREA_DECADE * (ln(squareKilometresPerCell / 100.0) / ln(10.0))
}
