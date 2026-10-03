package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.GroundCells
import com.cartogenesis.worldgen.pipeline.GroundWatch
import com.cartogenesis.worldgen.pipeline.HydraulicErosion
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlockingWatchingGround
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The erosion is the same erosion at every grid: what the two heights were built for
 * (docs/DESIGN_LEDGER.md, E1).
 *
 * Each seed's plates are eroded at 256, 512 and 1,024 rows, and what each grid's rounds took off
 * the ground is averaged over the 256-row grid's cells, so the three are compared over the same
 * ground: the blocks that are land at all three. The deep tier's (`-Pstages=erosion`): fifteen
 * worlds at three grids, two more seeds on a planet of another size, two seeds held out of every
 * figure the closure was designed against, a round of half the years and a slope facing two ways.
 *
 * Every bar is the design's and every one that fails today is recorded under E1a's findings with
 * the figures it fails by, so a worsening fails as another violation.
 */
class ErosionScaleTest {

    /**
     * The ground's lowering per area over common land, gross (everything the rounds took off,
     * before the spoil is laid), the largest grid's over the smallest's: at most
     * [DENUDATION_FACTOR], for the five standard seeds and for two of them on a planet 20,000 km
     * round, whose cells are larger at every grid. Earth's rates for each grid are printed.
     */
    @Test
    fun `the ground lowers by the same depth at every grid`() {
        val over = ArrayList<String>()
        val figures = ArrayList<String>()
        for ((seed, widthKm) in SEEDS.map { it to DEFAULT_WIDTH_KM } + SECOND_RADIUS_SEEDS.map { it to SECOND_WIDTH_KM }) {
            val spread = denudationSpread(seed, widthKm)
            figures += spread.figure
            if (spread.ratio > DENUDATION_FACTOR) over += "seed $seed at ${widthKm.toInt()} km x%.2f".format(spread.ratio)
        }
        KnownFailures.expect(DENUDATION_FOLLOWS_THE_GRID, DENUDATION_RECORD) {
            if (over.isNotEmpty()) {
                throw RecordedViolation(
                    "the ground lowers by a different depth at each grid, over x$DENUDATION_FACTOR: ${figures.joinToString("; ")}",
                    over.joinToString("; ")
                )
            }
        }
    }

    /**
     * Seeds no figure of the design was taken from, held to the same bar: the closure is not
     * fitted to the five standard worlds.
     */
    @Test
    fun `seeds held out of the design lower by the same depth at every grid`() {
        val over = ArrayList<String>()
        val figures = ArrayList<String>()
        for (seed in HELD_OUT_SEEDS) {
            val spread = denudationSpread(seed, DEFAULT_WIDTH_KM)
            figures += spread.figure
            if (spread.ratio > DENUDATION_FACTOR) over += "seed $seed x%.2f".format(spread.ratio)
        }
        KnownFailures.expect(DENUDATION_FOLLOWS_THE_GRID, HELD_OUT_RECORD) {
            if (over.isNotEmpty()) {
                throw RecordedViolation(
                    "held-out seeds lower by a different depth at each grid: ${figures.joinToString("; ")}",
                    over.joinToString("; ")
                )
            }
        }
    }

    /**
     * The trunk's own cut, class by class of drainage area on the ground, the classes every grid
     * resolves (catchments past the 256-row grid's cell): at most [BED_CUT_FACTOR] apart. The
     * stream-power law is unchanged by the two heights, so this separates the trunk's own grid
     * dependence from the closure's.
     */
    @Test
    fun `the trunk cuts the same depth at every grid, class by class`() {
        val over = ArrayList<String>()
        val figures = ArrayList<String>()
        for (seed in SEEDS) {
            val runs = GRIDS.map { summary(seed, it, DEFAULT_WIDTH_KM) }
            for (bin in CLASS_EDGES_KM2.indices.drop(1)) {
                val cuts = runs.map { it.bedCutByClass[bin - 1] }
                val ratio = cuts.maxOrNull()!! / cuts.minOrNull()!!
                val name = "seed $seed %s km2".format(className(bin))
                figures += "$name %s x%.2f".format(cuts.joinToString("/") { "%.0f".format(it) }, ratio)
                if (ratio > BED_CUT_FACTOR) over += "$name x%.2f".format(ratio)
            }
        }
        println("EROSIONSCALE bed cut by class, m at 256/512/1,024 rows: " + figures.joinToString("; "))
        KnownFailures.expect(BED_CUT_FOLLOWS_THE_GRID, BED_CUT_RECORD) {
            if (over.isNotEmpty()) {
                throw RecordedViolation(
                    "the trunk cuts a different depth at each grid, over x$BED_CUT_FACTOR: ${over.joinToString("; ")}",
                    over.joinToString("; ")
                )
            }
        }
    }

    /**
     * The bed's standing water, the cells its fill raises past the pond depth, as a share of the
     * land: at most [LAKE_FACTOR] apart across the grids, the lake census's provisional bar
     * (`ScaleFreeTest`).
     */
    @Test
    fun `the bed's standing water is the same share at every grid`() {
        val over = ArrayList<String>()
        val figures = ArrayList<String>()
        for (seed in SEEDS) {
            val shares = GRIDS.map { summary(seed, it, DEFAULT_WIDTH_KM).lakeShare }
            val ratio = shares.maxOrNull()!! / max(shares.minOrNull()!!, 1e-12)
            figures += "seed $seed %s x%.2f".format(shares.joinToString("/") { "%.4f".format(it) }, ratio)
            if (ratio > LAKE_FACTOR) over += "seed $seed x%.2f".format(ratio)
        }
        println("EROSIONSCALE standing water on the bed, share of land at 256/512/1,024 rows: " + figures.joinToString("; "))
        KnownFailures.expect(LAKES_FOLLOW_THE_GRID, LAKES_RECORD) {
            if (over.isNotEmpty()) {
                throw RecordedViolation("the bed holds a different share of water at each grid: ${over.joinToString("; ")}", over.joinToString("; "))
            }
        }
    }

    /**
     * The heads and the network inside a cell, each on its own: the median support area of the
     * channel cells' heads, and the in-cell drainage density (half the reciprocal of the hillslope
     * length, area-weighted over the land, nothing on a cell below every head), each within
     * [HEAD_FACTOR] across the grids, the factor `ScaleFreeTest` allows a network's density. The
     * head is reconstructed from the ground's own erosion so that it does not drift with the cell;
     * this is where it would show if it did.
     */
    @Test
    fun `the heads and the in-cell network are the same at every grid`() {
        val over = ArrayList<String>()
        val figures = ArrayList<String>()
        for (seed in SEEDS) {
            val runs = GRIDS.map { summary(seed, it, DEFAULT_WIDTH_KM) }
            val heads = runs.map { it.headAreaMedianKm2 }
            val densities = runs.map { it.inCellDensityKmPerKm2 }
            val headRatio = heads.maxOrNull()!! / heads.minOrNull()!!
            val densityRatio = densities.maxOrNull()!! / densities.minOrNull()!!
            figures += "seed $seed heads %s km2 x%.2f, density %s km/km2 x%.2f".format(
                heads.joinToString("/") { "%.3f".format(it) }, headRatio,
                densities.joinToString("/") { "%.3f".format(it) }, densityRatio
            )
            if (headRatio > HEAD_FACTOR) over += "seed $seed heads x%.2f".format(headRatio)
            if (densityRatio > HEAD_FACTOR) over += "seed $seed density x%.2f".format(densityRatio)
        }
        println("EROSIONSCALE heads and in-cell network at 256/512/1,024 rows: " + figures.joinToString("; "))
        KnownFailures.expect(HEADS_FOLLOW_THE_GRID, HEADS_RECORD) {
            if (over.isNotEmpty()) {
                throw RecordedViolation("the heads or the in-cell network differ across grids: ${over.joinToString("; ")}", over.joinToString("; "))
            }
        }
    }

    /**
     * Half the years a round, twice the rounds: the same world, its time cut finer. The trunk's
     * implicit update and the closure's backward Euler are both first-order in the step, so this
     * measures what the round's length costs, against the grid bar.
     */
    @Test
    fun `a round of half the years lowers the ground by the same depth`() {
        val config = WorldGenConfig.forRows(TIME_STEP_SEED, SharedWorlds.COARSE_ROWS)
        val finer = config.copy(
            erosion = config.erosion.copy(hydraulicRounds = config.erosion.hydraulicRounds * 2),
            scale = config.scale.copy(yearsPerHydraulicRound = config.scale.yearsPerHydraulicRound / 2)
        )
        val coarse = summaryOf(config)
        val fine = summaryOf(finer)
        val ratio = max(coarse.landMeanMetres, fine.landMeanMetres) / minOf(coarse.landMeanMetres, fine.landMeanMetres)
        val figure = "seed $TIME_STEP_SEED at 256 rows: %.1f m in 12 rounds, %.1f m in 24 of half the years, x%.3f"
            .format(coarse.landMeanMetres, fine.landMeanMetres, ratio)
        println("EROSIONSCALE time step $figure")
        KnownFailures.expect(DENUDATION_FOLLOWS_THE_STEP, STEP_RECORD) {
            if (ratio > DENUDATION_FACTOR) throw RecordedViolation("the ground's lowering follows the round's length: $figure", "x%.2f".format(ratio))
        }
    }

    /**
     * A plain tilted to the east and the same plain tilted to the north-east, on square cells,
     * under the same uplift: the in-cell geometry reads the trunk's step, which is longer on a
     * diagonal, so this is where a preferred bearing of the closure would show. At most
     * [DENUDATION_FACTOR] apart.
     */
    @Test
    fun `a slope lowers alike whichever way it faces`() {
        val east = rampDenudationMetres(1.0, 0.0)
        val northEast = rampDenudationMetres(sqrt(0.5), sqrt(0.5))
        val ratio = max(east, northEast) / minOf(east, northEast)
        val figure = "facing east %.2f m, north-east %.2f m, x%.3f".format(east, northEast, ratio)
        println("EROSIONSCALE bearing $figure")
        assertTrue("a slope lowers by its bearing: $figure", ratio <= DENUDATION_FACTOR)
    }

    private class Spread(val ratio: Double, val figure: String)

    private fun denudationSpread(seed: Long, widthKm: Double): Spread {
        val runs = GRIDS.map { summary(seed, it, widthKm) }
        val blocks = runs[0].blockLand.size
        val common = BooleanArray(blocks) { block -> runs.all { it.blockLand[block] } }
        val count = common.count { it }
        val means = runs.map { run ->
            var sum = 0.0
            for (block in 0 until blocks) if (common[block]) sum += run.blockMetres[block]
            sum / count
        }
        val years = runs[0].millionYears
        val ratio = means.maxOrNull()!! / means.minOrNull()!!
        val figure = "seed %d at %.0f km: %s m (%s m/Myr; belts %s) x%.3f".format(
            seed, widthKm, means.joinToString("/") { "%.1f".format(it) },
            means.joinToString("/") { "%.1f".format(it / years) },
            runs.joinToString("/") { "%.1f".format(it.beltRate) }, ratio
        )
        println("EROSIONSCALE denudation $figure")
        return Spread(ratio, figure)
    }

    /** What one erosion run leaves for these clauses, read on the 256-row footprint where it is compared. */
    private class Summary(
        val blockMetres: DoubleArray,
        val blockLand: BooleanArray,
        val bedCutByClass: DoubleArray,
        val lakeShare: Double,
        val headAreaMedianKm2: Double,
        val inCellDensityKmPerKm2: Double,
        val landMeanMetres: Double,
        val beltRate: Double,
        val millionYears: Double
    )

    private fun summary(seed: Long, rows: Int, widthKm: Double): Summary = CACHE.getOrPut("$seed $rows $widthKm") {
        val base = WorldGenConfig.forRows(seed, rows)
        summaryOf(if (widthKm == DEFAULT_WIDTH_KM) base else base.copy(scale = base.scale.copy(worldWidthKm = widthKm)))
    }

    private fun summaryOf(config: WorldGenConfig): Summary {
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val cellCount = config.width * config.height
        val produced = DoubleArray(cellCount)
        val cut = DoubleArray(cellCount)
        var finalBed = FloatArray(0)
        var finalGround = FloatArray(0)
        var heads = FloatArray(0)
        var channel = BooleanArray(0)
        var hillslopeLength = FloatArray(0)
        val watch = object : GroundWatch {
            override fun round(
                round: Int, isLand: BooleanArray, cells: GroundCells, ground: FloatArray,
                bedCut: DoubleArray, production: DoubleArray, bedsHeld: Int
            ) {
                for (cell in production.indices) {
                    produced[cell] += production[cell]
                    cut[cell] += bedCut[cell]
                }
                heads = cells.headAreaSquareMetres.copyOf()
                channel = cells.isChannel.copyOf()
                hillslopeLength = cells.hillslopeLengthMetres.copyOf()
            }

            override fun finished(bed: FloatArray, ground: FloatArray) {
                finalBed = bed.copyOf()
                finalGround = ground.copyOf()
            }
        }
        erodeBlockingWatchingGround(config, plates.height, plates.upliftRateMmPerYear, watch)

        val metres = config.scale.reliefSpanMetres.toDouble()
        val millionYears = config.scale.yearsPerHydraulicRound * config.erosion.hydraulicRounds / 1e6
        val sea = SeaLevelStage.percentileCut(FloatField(config.width, config.height, finalGround), config.seaLevel, config.scale)
        val isLand = sea.isLand

        // The 256-row footprint, whatever this grid is.
        val factor = config.height / SharedWorlds.COARSE_ROWS
        val blocksAcross = config.width / factor
        val blockCount = blocksAcross * (config.height / factor)
        val blockMetres = DoubleArray(blockCount)
        val landCount = IntArray(blockCount)
        for (cell in 0 until cellCount) {
            val block = (cell / config.width / factor) * blocksAcross + (cell % config.width / factor)
            blockMetres[block] += produced[cell] * metres / (factor * factor)
            if (isLand[cell]) landCount[block]++
        }
        val blockLand = BooleanArray(blockCount) { landCount[it] * 2 > factor * factor }

        // The final bed's routing: drainage area on the ground for the classes, and its standing water.
        val relative = sea.relativeElevation.copy()
        val landHalf = config.scale.landHalfOfField
        for (cell in 0 until cellCount) if (isLand[cell]) relative.data[cell] = (finalBed[cell] - sea.shorelineHeight) / landHalf
        val filled = FlowRouting.fillDepressions(config.width, config.height, isLand, relative)
        val flow = FlowRouting.flowDirections(
            config.width, config.height, isLand, relative, filled, config.seed, config.cellHeightInCellWidths,
            FlowRouting.smoothFieldPeriodCells(config), config.facetRouting, config.flatPotential
        )
        val cellKm2 = config.squareKilometresPerCell.toFloat()
        val area = FlowRouting.accumulate(config.width, config.height, isLand, filled, flow, sea.landCellCount) { cellKm2 }
        val pond = config.scale.reliefShareOfMetres(HydraulicErosion.POND_DEPTH_METRES)
        val classSum = DoubleArray(CLASS_EDGES_KM2.size - 1)
        val classCount = IntArray(CLASS_EDGES_KM2.size - 1)
        var standing = 0
        var landMetres = 0.0
        var beltSum = 0.0
        var beltCells = 0
        var density = 0.0
        val headKm2 = ArrayList<Double>()
        val falloff = config.cellsFor(config.tectonics.boundaryFalloffKm)
        for (cell in 0 until cellCount) {
            if (!isLand[cell]) continue
            if (filled.data[cell] - relative.data[cell] > pond) standing++
            landMetres += produced[cell] * metres
            val bin = CLASS_EDGES_KM2.indexOfLast { area.data[cell] >= it }
            if (bin in classSum.indices) {
                classSum[bin] += cut[cell] * metres
                classCount[bin]++
            }
            val inBelt = plates.boundaryDistance.data[cell] <= falloff &&
                (plates.nearestBoundaryClass[cell] == BoundaryClass.COLLISION_PLATEAU.ordinal ||
                    plates.nearestBoundaryClass[cell] == BoundaryClass.ANDEAN_MARGIN.ordinal)
            if (inBelt) {
                beltSum += produced[cell] * metres / millionYears
                beltCells++
            }
            if (channel[cell] && hillslopeLength[cell] > 0f) {
                density += METRES_PER_KM / (2.0 * hillslopeLength[cell])
                if (heads[cell].isFinite()) headKm2 += heads[cell] / SQUARE_METRES_PER_KM2
            }
        }
        headKm2.sort()
        val landCells = sea.landCellCount
        return Summary(
            blockMetres, blockLand,
            DoubleArray(classSum.size) { if (classCount[it] > 0) classSum[it] / classCount[it] else Double.NaN },
            standing.toDouble() / landCells,
            if (headKm2.isEmpty()) Double.NaN else headKm2[headKm2.size / 2],
            density / landCells,
            landMetres / landCells,
            if (beltCells > 0) beltSum / beltCells else 0.0,
            millionYears
        )
    }

    /**
     * The mean lowering of a tilted plain's uplifted land, in metres, on a small world of square
     * cells: a disc of ground in a deep sea, its surface a plane rising along the bearing
     * ([towardEast], [towardSouth]) from a shore through the disc's centre, uplifted uniformly over
     * its land. The disc is the same at every bearing, so the land is the same half-disc turned and
     * only the bearing against the grid differs. The flexure, the deposition, the climate, the
     * lowstand and the outlet notch are off, so that nothing but the rounds and the closure act.
     */
    private fun rampDenudationMetres(towardEast: Double, towardSouth: Double): Double {
        val base = WorldGenConfig(seed = RAMP_SEED, width = RAMP_CELLS_ACROSS, height = RAMP_CELLS_ACROSS / 2)
        val config = base.copy(
            seaLevel = RAMP_SEA_SHARE,
            scale = base.scale.copy(worldWidthKm = RAMP_WORLD_KM),
            isostasy = base.isostasy.copy(flexure = false),
            erosion = base.erosion.copy(climateFeed = false, deposition = false, outletIncision = false),
            sea = base.sea.copy(lowstandMetres = 0f)
        )
        val scale = config.scale
        val noise = TerrainStage.generate(config).height
        val ground = FloatField(config.width, config.height)
        val uplift = FloatField(config.width, config.height)
        val cellKm = config.cellWidthKm
        val centreColumn = config.width / 2.0
        val centreRow = config.height / 2.0
        for (row in 0 until config.height) for (column in 0 until config.width) {
            val cell = row * config.width + column
            val eastKm = (column + 0.5 - centreColumn) * cellKm
            val southKm = (row + 0.5 - centreRow) * cellKm
            val inDisc = eastKm * eastKm + southKm * southKm <= RAMP_DISC_RADIUS_KM * RAMP_DISC_RADIUS_KM
            val along = eastKm * towardEast + southKm * towardSouth
            val altitude =
                if (inDisc) RAMP_GRADIENT * METRES_PER_KM * along + (noise.data[cell] - 0.5) * RAMP_NOISE_METRES
                else RAMP_SEA_FLOOR_METRES
            ground.data[cell] = scale.fieldAtAltitude(altitude.toFloat())
            uplift.data[cell] = if (inDisc && altitude > 0.0) RAMP_UPLIFT_MM_PER_YEAR else 0f
        }
        val produced = DoubleArray(config.width * config.height)
        val watch = object : GroundWatch {
            override fun round(
                round: Int, isLand: BooleanArray, cells: GroundCells, ground: FloatArray,
                bedCut: DoubleArray, production: DoubleArray, bedsHeld: Int
            ) {
                for (cell in production.indices) produced[cell] += production[cell]
            }

            override fun finished(bed: FloatArray, ground: FloatArray) {}
        }
        erodeBlockingWatchingGround(config, ground, uplift, watch)
        val metres = scale.reliefSpanMetres.toDouble()
        var sum = 0.0
        var count = 0
        for (cell in produced.indices) {
            if (uplift.data[cell] <= 0f) continue
            sum += produced[cell] * metres
            count++
        }
        return sum / count
    }

    private fun className(bin: Int): String {
        val low = CLASS_EDGES_KM2[bin - 1]
        val high = CLASS_EDGES_KM2[bin]
        return if (high.isInfinite()) "%.0f+".format(low) else "%.0f-%.0f".format(low, high)
    }

    private companion object {
        val CACHE = HashMap<String, Summary>()

        val SEEDS = listOf(7L, 42L, 99L, 1234L, 718106L)
        val SECOND_RADIUS_SEEDS = listOf(7L, 42L)

        /** Two seeds no figure of the design or of this chunk's measurements was taken on. */
        val HELD_OUT_SEEDS = listOf(3L, 11L)
        val GRIDS = listOf(SharedWorlds.COARSE_ROWS, SharedWorlds.DETAIL_ROWS, 2 * SharedWorlds.DETAIL_ROWS)
        const val DEFAULT_WIDTH_KM = 12_000.0

        /** A planet 5/3 the default's width, whose cells are 5/3 as wide at every grid. */
        const val SECOND_WIDTH_KM = 20_000.0
        const val TIME_STEP_SEED = 42L

        /**
         * Drainage-area classes on the ground, km2: from past the 256-row grid's cell (549 km2) up,
         * so every class is resolved at every grid.
         */
        val CLASS_EDGES_KM2 = doubleArrayOf(2_500.0, 10_000.0, 100_000.0, Double.POSITIVE_INFINITY)

        /** The design's bars: the ground's lowering a twentieth apart, the trunk's cut a tenth. */
        const val DENUDATION_FACTOR = 1.05
        const val BED_CUT_FACTOR = 1.10

        /** `ScaleFreeTest`'s provisional lake bar, and the factor it allows a network's density. */
        const val LAKE_FACTOR = 1.35
        const val HEAD_FACTOR = 1.35

        const val METRES_PER_KM = 1_000.0
        const val SQUARE_METRES_PER_KM2 = 1e6

        // The ramp: 128 by 64 square cells of 1.5625 km on a 200 km world, a disc 40 km round in a
        // sea 2 km deep, a 2% plane rising across it from a shore through its centre, fifty metres
        // of noise for the water to choose among, and a tenth of a millimetre a year of uplift over
        // its land. The land, half the disc, is an eighth of the world, so the sea's percentile
        // takes seven eighths and its shoreline stands near the plane's zero.
        const val RAMP_SEED = 4243L
        const val RAMP_CELLS_ACROSS = 128
        const val RAMP_WORLD_KM = 200.0
        const val RAMP_DISC_RADIUS_KM = 40.0
        const val RAMP_GRADIENT = 0.02
        const val RAMP_NOISE_METRES = 50.0
        const val RAMP_SEA_FLOOR_METRES = -2_000.0
        const val RAMP_SEA_SHARE = 0.875f
        const val RAMP_UPLIFT_MM_PER_YEAR = 0.1f

        const val DENUDATION_FOLLOWS_THE_GRID = "E1a: the ground still lowers by more on a coarser grid"
        const val DENUDATION_RECORD =
            "seed 7 at 12000 km x1.38; seed 42 at 12000 km x1.41; seed 99 at 12000 km x1.39; seed 1234 at 12000 km x1.34; " +
                "seed 718106 at 12000 km x1.38; seed 7 at 20000 km x1.42; seed 42 at 20000 km x1.44"
        const val HELD_OUT_RECORD = "seed 3 x1.40; seed 11 x1.30"
        const val BED_CUT_FOLLOWS_THE_GRID = "E1a: the trunk's implicit cut follows the cell through its F"
        const val BED_CUT_RECORD =
            "seed 7 2500-10000 km2 x1.20; seed 7 10000-100000 km2 x1.20; seed 42 2500-10000 km2 x1.29; " +
                "seed 42 10000-100000 km2 x1.18; seed 99 2500-10000 km2 x1.22; seed 1234 2500-10000 km2 x1.19; " +
                "seed 1234 100000+ km2 x1.15; seed 718106 2500-10000 km2 x1.20; seed 718106 10000-100000 km2 x1.11"
        const val LAKES_FOLLOW_THE_GRID = "E1a: the bed's standing water follows the grid"
        const val LAKES_RECORD = "seed 7 x1.48; seed 1234 x1.49"
        const val HEADS_FOLLOW_THE_GRID = "E1a: the heads or the in-cell network follow the grid"
        const val HEADS_RECORD =
            "seed 7 heads x1.39; seed 7 density x1.46; seed 42 density x1.39; seed 99 heads x1.44; seed 99 density x1.52; " +
                "seed 1234 heads x1.52; seed 1234 density x1.54; seed 718106 heads x1.37; seed 718106 density x1.47"
        const val DENUDATION_FOLLOWS_THE_STEP = "E1a: the ground's lowering follows the round's length"
        const val STEP_RECORD = "x1.18"
    }
}
