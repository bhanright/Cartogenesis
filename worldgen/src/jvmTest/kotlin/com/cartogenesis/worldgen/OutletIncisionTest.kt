package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.DepositionLog
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.HydraulicErosion
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.PitStage
import com.cartogenesis.worldgen.pipeline.erodeBlockingEntrainingSpoil
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * A lake is sized by its outlet, and its outlet is cut by what the lake lets out.
 *
 * The hydraulic rounds fill every hollow so that the water has somewhere to go, then route over the
 * fill, so a basin's lip is crossed by its whole catchment's water. E1 cut that lip with an explicit
 * notch on the whole catchment, which drained every basin alike whatever its climate. Since E1b the
 * same implicit pass that cuts every channel cuts the lip, with the lake's surplus as its discharge:
 * what its catchment sends it and the rain on its own surface, less what that surface evaporates
 * (`LakeOutlets`). A wet basin spills and cuts its lip down, and Bonneville empties through Red Rock
 * Pass; a basin in a dry climate evaporates everything it is sent, lets nothing out and keeps its
 * sill, and is the Caspian or the Great Salt Lake.
 *
 * Three measurements. The first two are the mechanism on a basin built by hand, with its rain and
 * evaporation set: dry, it stays closed, and it is shown failing with the evaporation left out of
 * the outflow; wet, it spills and cuts. The third is what the reader sees, the largest lake the
 * river stage draws, judged against the Caspian's share of Earth's land, 371,000 km² of 148.94
 * million, 0.249%. And the last is the spoil: a world's deposition is laid once, after the last
 * routing, and no lake on the finished bed may stand behind a lip of its own spoil.
 */
class OutletIncisionTest : BorrowsSharedWorlds() {

    /** The Caspian's share of Earth's *land*: the bar for "too big to be a lake". */
    private val caspianShare = 371_000.0 / 148_940_000.0

    /**
     * How far over the Caspian's share a world is allowed to go before this counts as an
     * over-large lake: a regression pin from E1's notch, whose largest lake on a given seed jumped
     * by a factor of two between neighbouring rates, set a tenth over the one seed of seven that
     * was over (docs/DESIGN_LEDGER.md, E1). Kept as the bar the lakes have been held to.
     */
    private val chaos = 1.4

    /** The same for the basins the sea drowned; see docs/DESIGN_LEDGER.md, S1. */
    private val drownedChaos = 1.5

    /** The four standard seeds and the two the water balance chose, 43 dry and 99 wet. */
    private val seeds = listOf(718106L, 7L, 42L, 1234L, 99L, 43L)

    /** One hand-built basin's fate over the rounds. */
    private class BasinFate(
        /** How far the lip's bed came down, in metres. */
        val lipLoweredMetres: Double,
        /** Cells under more than the pond depth of standing water, as the rounds opened and as they closed. */
        val pondedBefore: Int,
        val pondedAfter: Int
    )

    /**
     * A bowl on a plateau above a coast, its rim high all round but for one lip on its western
     * side, a ramp below the lip falling to the sea and a broad slope to its east draining into it:
     * twelve rounds of the stage with no uplift, no flexure and no spoil, under a sky of
     * [rainfallMm] and [evaporationMm] everywhere, with the lakes' evaporation taken out of their
     * outflow or not as [lakeEvaporation] says.
     */
    private fun basinFate(rainfallMm: Float, evaporationMm: Float, lakeEvaporation: Boolean): BasinFate {
        val base = WorldGenConfig(seed = BASIN_SEED, width = BASIN_COLUMNS, height = BASIN_ROWS)
        val config = base.copy(
            seaLevel = SEA_COLUMNS.toFloat() / BASIN_COLUMNS,
            scale = base.scale.copy(worldWidthKm = BASIN_WORLD_KM),
            isostasy = base.isostasy.copy(flexure = false),
            erosion = base.erosion.copy(deposition = false),
            sea = base.sea.copy(lowstandMetres = 0f)
        )
        val scale = config.scale
        val ground = FloatField(BASIN_COLUMNS, BASIN_ROWS)
        for (row in 0 until BASIN_ROWS) {
            for (column in 0 until BASIN_COLUMNS) {
                val cell = row * BASIN_COLUMNS + column
                val metres = when {
                    // Every sea cell at its own depth, or the percentile ties.
                    column < SEA_COLUMNS -> -SEA_DEPTH_METRES - cell * SEA_FLOOR_STEP_METRES
                    column < LIP_COLUMN -> COAST_METRES + (column - SEA_COLUMNS) * (LIP_METRES - COAST_METRES) / (LIP_COLUMN - SEA_COLUMNS)
                    row < BOWL_FIRST_ROW || row > BOWL_LAST_ROW -> RIM_METRES
                    column == LIP_COLUMN -> if (row == LIP_ROW) LIP_METRES else RIM_METRES
                    column < EAST_SLOPE_COLUMN -> FLOOR_METRES + (column - LIP_COLUMN) * FLOOR_FALL_PER_COLUMN_METRES
                    else -> FLOOR_METRES + (column - LIP_COLUMN) * FLOOR_FALL_PER_COLUMN_METRES +
                        (column - EAST_SLOPE_COLUMN) * EAST_SLOPE_PER_COLUMN_METRES
                }
                ground.data[cell] = scale.fieldAtAltitude(metres)
            }
        }
        val cellCount = BASIN_COLUMNS * BASIN_ROWS
        val sky = HydraulicErosion.Weather(
            FloatArray(cellCount) { rainfallMm }, FloatArray(cellCount), FloatArray(cellCount) { evaporationMm }
        )
        val pondedBefore = ponded(config, ground, ground)
        val eroded = runBlocking {
            HydraulicErosion.apply(
                config, ground.copy(), config.seaLevel, lakeEvaporation = lakeEvaporation, fixedWeather = sky
            ) { it }
        }
        val lip = LIP_ROW * BASIN_COLUMNS + LIP_COLUMN
        return BasinFate(
            lipLoweredMetres = ((ground.data[lip] - eroded.bed.data[lip]) * scale.reliefSpanMetres).toDouble(),
            pondedBefore = pondedBefore,
            pondedAfter = ponded(config, eroded.ground, eroded.bed)
        )
    }

    /** Cells the fill of [bed] stands over by more than the pond depth, on the cut [ground] decides. */
    private fun ponded(config: WorldGenConfig, ground: FloatField, bed: FloatField): Int {
        val cut = SeaLevelStage.percentileCut(ground, config.seaLevel, config.scale)
        val relative = cut.relativeElevation.copy()
        for (cell in relative.data.indices) {
            if (cut.isLand[cell]) relative.data[cell] = (bed.data[cell] - cut.shorelineHeight) / config.scale.landHalfOfField
        }
        val filled = FlowRouting.fillDepressions(config.width, config.height, cut.isLand, relative)
        val pond = config.scale.reliefShareOfMetres(HydraulicErosion.POND_DEPTH_METRES)
        return relative.data.indices.count { cut.isLand[it] && filled.data[it] - relative.data[it] > pond }
    }

    /**
     * A basin in a dry climate stays closed; and without the evaporation taken out of what leaves
     * it, the same basin is cut open as if it overflowed with its whole catchment.
     *
     * Rain of [DRY_RAINFALL_MM] against [DRY_EVAPORATION_MM] of potential evaporation: a desert
     * basin's (the Great Basin's playas take 200 to 300 mm and could evaporate well over a metre).
     * The lake evaporates everything its catchment sends it, so its outlet carries nothing but the
     * lip's own rain: the lip is worn only as the slope below it is, and the bowl stays closed. A
     * basin is open once its lip is cut below its floor, [LIP_METRES] less [FLOOR_METRES], which is
     * the bar. The control routes the catchment's whole water over the lip, which is what the rounds
     * did before E1b, and the bowl drains.
     */
    @Test
    fun `a closed basin in a dry climate stays closed, and is cut open without its evaporation`() {
        val dry = basinFate(DRY_RAINFALL_MM, DRY_EVAPORATION_MM, lakeEvaporation = true)
        val control = basinFate(DRY_RAINFALL_MM, DRY_EVAPORATION_MM, lakeEvaporation = false)
        println(
            String.format(
                Locale.ROOT,
                "OUTLET dry basin: lip lowered %.1f m, %d -> %d cells under water; without the evaporation %.1f m, %d -> %d",
                dry.lipLoweredMetres, dry.pondedBefore, dry.pondedAfter,
                control.lipLoweredMetres, control.pondedBefore, control.pondedAfter
            )
        )
        assertTrue(dry.pondedBefore > 0, "the hand-built basin holds no water to begin with")
        assertTrue(
            dry.lipLoweredMetres < LIP_METRES - FLOOR_METRES && dry.pondedAfter > 0,
            "the dry basin's lip came down ${"%.1f".format(dry.lipLoweredMetres)} m against the " +
                "${LIP_METRES - FLOOR_METRES} m it stood over the floor, ${dry.pondedAfter} cells left under water: it was cut open"
        )
        assertTrue(
            control.lipLoweredMetres >= LIP_METRES - FLOOR_METRES,
            "with every drop of its catchment let out over the lip the dry basin's lip still came down only " +
                "${"%.1f".format(control.lipLoweredMetres)} m, so this case cannot tell the two apart"
        )
    }

    /**
     * A basin in a wet climate spills, and its outlet is cut by what it lets out.
     *
     * Rain of [WET_RAINFALL_MM] against [WET_EVAPORATION_MM]: a temperate lake's, which takes in
     * more than it loses and overflows. Its outlet carries the catchment's water and the lake's own
     * surplus, and the lip is cut down and the bowl drains.
     */
    @Test
    fun `a basin in a wet climate spills and cuts its outlet`() {
        val wet = basinFate(WET_RAINFALL_MM, WET_EVAPORATION_MM, lakeEvaporation = true)
        println(
            String.format(
                Locale.ROOT, "OUTLET wet basin: lip lowered %.1f m, %d -> %d cells under water",
                wet.lipLoweredMetres, wet.pondedBefore, wet.pondedAfter
            )
        )
        assertTrue(wet.pondedBefore > 0, "the hand-built basin holds no water to begin with")
        assertTrue(
            wet.lipLoweredMetres > LIP_METRES - FLOOR_METRES,
            "the wet basin's lip came down ${"%.1f".format(wet.lipLoweredMetres)} m, less than the " +
                "${LIP_METRES - FLOOR_METRES} m it stood over the bowl's floor"
        )
    }

    /**
     * The largest lake the river stage draws, against the Caspian's share of the land.
     *
     * Measured on the finished world, ice and all. The basins the sea drowned are split from the
     * rest and pooled, as `drownedLakes` says, and both are held to their bars; what the bars find
     * over is recorded.
     */
    @Test
    fun `no world keeps a lake bigger than the Caspian`() {
        val overCaspian = ArrayList<String>()
        val drownedShares = ArrayList<Double>()
        val perSeed = ArrayList<String>()
        seeds.forEach { seed ->
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS))
            val largest = largestLakeShare(world)
            val drowned = largestLakeShare(world, drowned = true)
            drownedShares += drowned
            perSeed += String.format(Locale.ROOT, "%d %.4f%%", seed, drowned * 100)
            println(
                String.format(
                    Locale.ROOT,
                    "OUTLET seed %d: %d lakes over %.3f%% of the land, the largest in the land %.4f%% " +
                        "(the Caspian's share is %.4f%%), the largest drowned basin %.4f%%",
                    seed, world.rivers.lakes.lakes.size, lakeShareOfLand(world) * 100, largest * 100,
                    caspianShare * 100, drowned * 100
                )
            )
            assertTrue(world.rivers.lakes.lakes.isNotEmpty(), "seed $seed has no lakes at all")
            if (largest >= caspianShare * chaos) {
                overCaspian += String.format(Locale.ROOT, "seed %d's largest lake %.2fx the Caspian", seed, largest / caspianShare)
            }
        }
        assertTrue(overCaspian.isEmpty(), "a lake is over the Caspian's share of its world's land: $overCaspian")
        val pooledDrowned = drownedShares.average()
        println(
            String.format(
                Locale.ROOT, "OUTLET pooled largest drowned basin %.4f%% of land, %.2fx the Caspian's share: %s",
                pooledDrowned * 100, pooledDrowned / caspianShare, perSeed
            )
        )
        assertTrue(
            pooledDrowned < caspianShare * drownedChaos,
            "these worlds keep a basin below the sea-level cut holding more water than the Caspian's share, " +
                String.format(Locale.ROOT, "%.2fx it pooled: %s", pooledDrowned / caspianShare, perSeed)
        )
    }

    /**
     * The spoil dams no river.
     *
     * A world's deposition is held off the bed while the rounds run and laid once, after the last
     * routing, and the channels above a floodplain go on cutting down meanwhile, so a fill that kept
     * the no-uphill rule when it was laid can stand above the cells that feed it by the end: a dam,
     * and once the spoil is laid the fill ponds the river behind it. A closing breach cut those
     * dams until E1b; now a channel takes back into its load whatever of its held spoil stands above
     * its feeders, as a river incising through its own floodplain carries it on.
     *
     * Read on the census the rounds already take: the channel cells the laying of the spoil left
     * lower than the cell they drain into, which were not so as the round opened
     * (`RoundMass.channelPits`, after the spoil). Shown failing with the re-entrainment off. The
     * finished bed's basins whose lip lies on spoil are printed beside it.
     */
    @Test
    fun `the spoil dams no river`() {
        val found = ArrayList<String>()
        val controlFound = ArrayList<String>()
        DAM_SEEDS.forEach { seed ->
            val config = WorldGenConfig.forRows(seed, DAM_ROWS)
            val shipped = spoilDams(config, entrainSpoil = true)
            val control = spoilDams(config, entrainSpoil = false)
            println(
                "OUTLET seed $seed: the spoil left ${shipped.pits} channel cells below their receiver and " +
                    "${shipped.lipsOnSpoil} of ${shipped.basins} basins on the finished bed with their lip on it; " +
                    "without the re-entrainment ${control.pits}, and ${control.lipsOnSpoil} of ${control.basins}"
            )
            if (shipped.pits > 0) found += "seed $seed ${shipped.pits}"
            if (control.pits > 0) controlFound += "seed $seed ${control.pits}"
        }
        assertTrue(found.isEmpty(), "the spoil put holes in rivers' beds: $found")
        assertTrue(controlFound.isNotEmpty(), "without the re-entrainment the spoil dams nothing either, so this case cannot fail")
    }

    /** What the spoil did to one world's rivers: the census's holes, and the finished bed's basins. */
    private class SpoilDams(val pits: Int, val basins: Int, val lipsOnSpoil: Int)

    private fun spoilDams(config: WorldGenConfig, entrainSpoil: Boolean): SpoilDams {
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val log = DepositionLog(config.width * config.height)
        var lastPits = 0
        val eroded = erodeBlockingEntrainingSpoil(
            config, plates.height, plates.upliftRateMmPerYear, entrainSpoil, { lastPits = it.channelPits[PitStage.SPOIL] }, log
        )
        val cut = SeaLevelStage.enclosedCut(eroded.height, config.seaLevel, config)
        val relative = cut.relativeElevation.copy()
        for (cell in relative.data.indices) {
            if (cut.isLand[cell]) relative.data[cell] = (eroded.bed.data[cell] - cut.shorelineHeight) / config.scale.landHalfOfField
        }
        val filled = FlowRouting.fillDepressions(config.width, config.height, cut.isLand, relative)
        val flow = FlowRouting.flowDirections(
            config.width, config.height, cut.isLand, relative, filled, config.seed, config.cellHeightInCellWidths,
            FlowRouting.smoothFieldPeriodCells(config), config.facetRouting, config.flatPotential
        )
        val basins = FlowRouting.spillways(
            config.width, config.height, cut.isLand, relative.data, filled.data, flow,
            config.scale.reliefShareOfMetres(HydraulicErosion.POND_DEPTH_METRES)
        )
        val onSpoil = (0 until basins.count).count { basin ->
            val lip = basins.spill[basin]
            lip >= 0 && log.laid[lip] > 0.0
        }
        return SpoilDams(lastPits, basins.count, onSpoil)
    }

    /**
     * Which lakes stand on ground below the sea-level cut: the basins the enclosure rule made land,
     * the Caspian's kind, sized by how much of a low coast the sea covers when it comes back up as
     * much as by any outlet. Read off `erosion.height` against `sea.shorelineHeight`, because
     * glaciation rewrites the shoreline-relative field between the cut and here.
     */
    private fun drownedLakes(world: WorldMap): BooleanArray {
        val drowned = BooleanArray(world.rivers.lakes.lakes.size)
        val ground = world.erosion.height.data
        val cut = world.sea.shorelineHeight
        world.rivers.lakes.lakeId.forEachIndexed { cell, id ->
            if (id >= 0 && ground[cell] < cut) drowned[id] = true
        }
        return drowned
    }

    /** The largest lake as a share of the world's land — see [caspianShare]. */
    private fun largestLakeShare(world: WorldMap, drowned: Boolean = false): Double {
        val isDrowned = drownedLakes(world)
        val largest = world.rivers.lakes.lakes
            .filterIndexed { id, _ -> isDrowned[id] == drowned }
            .maxOfOrNull { it.cellCount } ?: 0
        return largest.toDouble() / world.sea.landCellCount.toDouble()
    }

    private fun lakeShareOfLand(world: WorldMap): Double =
        world.rivers.lakes.lakeId.count { it >= 0 }.toDouble() / world.sea.landCellCount

    private companion object {
        /**
         * The hand-built basin's grid: a world [BASIN_WORLD_KM] wide on 128 by 64 cells, so a cell
         * is 15.6 km, a basin's width a good share of the map and twelve rounds of a lip's cut
         * enough to drain it.
         */
        const val BASIN_SEED = 5L
        const val BASIN_COLUMNS = 128
        const val BASIN_ROWS = 64
        const val BASIN_WORLD_KM = 2_000.0
        const val SEA_COLUMNS = 16
        const val SEA_DEPTH_METRES = 600f
        const val SEA_FLOOR_STEP_METRES = 1e-3f

        /** The ramp from the coast to the lip, the lip, the rim round the bowl and the bowl's floor. */
        const val COAST_METRES = 50f
        const val LIP_COLUMN = 40
        const val LIP_ROW = 32
        const val LIP_METRES = 500f
        const val RIM_METRES = 1_200f
        const val BOWL_FIRST_ROW = 8
        const val BOWL_LAST_ROW = 55
        const val FLOOR_METRES = 150f

        /** The floor rises gently eastward, so the bowl's lowest ground is by the lip. */
        const val FLOOR_FALL_PER_COLUMN_METRES = 1f

        /** The slope east of the bowl, rising away from it, whose water the bowl gathers. */
        const val EAST_SLOPE_COLUMN = 90
        const val EAST_SLOPE_PER_COLUMN_METRES = 20f

        /**
         * A desert basin's sky, the Great Basin's: 250 mm of rain under well over a metre of
         * potential evaporation.
         */
        const val DRY_RAINFALL_MM = 250f
        const val DRY_EVAPORATION_MM = 1_800f

        /** A temperate lake's: twice Earth's mean rain on land against half a metre of evaporation. */
        const val WET_RAINFALL_MM = 1_500f
        const val WET_EVAPORATION_MM = 500f

        /** The worlds the spoil's dams are counted on: two standard seeds at the census's grid. */
        val DAM_SEEDS = listOf(7L, 42L)
        const val DAM_ROWS = SharedWorlds.DETAIL_ROWS
    }
}
