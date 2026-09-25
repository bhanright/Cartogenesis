package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BasinPartition
import com.cartogenesis.worldgen.pipeline.BasinUnits
import com.cartogenesis.worldgen.pipeline.CultureStage
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.Lake
import com.cartogenesis.worldgen.pipeline.LakeResult
import com.cartogenesis.worldgen.pipeline.NationStage
import com.cartogenesis.worldgen.pipeline.Runoff
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import kotlin.math.abs
import kotlin.math.sign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catchments realms and peoples are built from ([BasinPartition]): every cell in the unit of
 * the water it drains to, a closed basin whole, no unit larger than its share of the land's area,
 * and no unit on two landmasses.
 *
 * Mostly on hand-made worlds ([HandMadeWorlds]), where the routing is drawn rather than grown, so a
 * clause can put a receiver where the defect it guards against would mishandle it. See
 * docs/DESIGN_LEDGER.md, chunk 6, for what each failed on before.
 */
class CatchmentUnitsTest : BorrowsSharedWorlds() {

    /** Land in the block [columns] by [rows], sea everywhere else, on flat ground. */
    private fun block(config: WorldGenConfig, columns: IntRange, rows: IntRange): SeaLevelResult =
        HandMadeWorlds.sea(config, { x, y -> x in columns && y in rows }) { x, y ->
            if (x in columns && y in rows) 0.1f else -0.1f
        }

    private fun unitsHolding(units: BasinUnits, cells: Collection<Int>): Set<Int> =
        cells.map { units.unitOf[it] }.toSet()

    /**
     * One catchment drawn as a tree on a flat: every row runs east to a trunk down the block's
     * east column, and the trunk runs south to a mouth on the sea. Every receiver is east of or
     * below its donor, so it has the higher cell index, and the flat has one height; a walk by
     * height, ties to the lower index, therefore meets every donor before its receiver.
     *
     * One catchment is one unit when it is under the size limit. Walked lowest first, as the
     * partition was until chunk 6, each unsettled receiver opened a unit of its own — a unit per
     * cell here, and on a generated world a quarter of the land in slivers along the rows and
     * columns of the flats. Walked receivers first, down [FlowRouting.drainageOrder] reversed, it
     * is one unit whatever the heights say.
     */
    @Test
    fun `a catchment is one unit however its receivers stand on the fill`() {
        val config = HandMadeWorlds.config()
        val columns = 4..40
        val rows = 20..44
        val sea = block(config, columns, rows)
        val rivers = HandMadeWorlds.rivers(config, sea, { x, y ->
            when {
                x < 40 -> HandMadeWorlds.cellAt(config, x + 1, y)
                y < 44 -> HandMadeWorlds.cellAt(config, x, y + 1)
                else -> HandMadeWorlds.cellAt(config, x + 1, y)
            }
        })
        val units = BasinPartition.compute(config, sea, rivers, Double.MAX_VALUE)
        println("TREE %d land cells in %d units".format(sea.landCellCount, units.unitCount))
        assertEquals(1, units.unitCount, "one catchment on a flat came out as ${units.unitCount} units")
        assertEquals(sea.landCellCount, units.area[0])
    }

    /**
     * A closed basin: land draining in from every side to an endorheic lake of twenty-five cells,
     * and beyond its divide land draining out to the sea. Every cell of the lake is a sink in the
     * routing, and a sink opens a unit — so before chunk 6 a closed basin came apart into a unit
     * per cell of water, each with the slopes that happened to drain to that cell. The lake and
     * everything that drains to it are one unit when they fit the size limit.
     *
     * The same again for a playa, which is the water a basin too dry for a lake leaves: one
     * connected stretch of it is one sink.
     */
    @Test
    fun `a closed basin is one unit with the land that drains to it`() {
        for (asPlaya in listOf(false, true)) {
            val config = HandMadeWorlds.config()
            val sea = block(config, 10..30, 10..30)
            val cellCount = config.width * config.height
            fun inWater(x: Int, y: Int) = x in 18..22 && y in 18..22
            fun inBasin(x: Int, y: Int) = x in 13..27 && y in 13..27
            val lakeId = IntArray(cellCount) { LakeResult.NO_LAKE }
            val playa = BooleanArray(cellCount)
            val water = ArrayList<Int>()
            for (y in 0 until config.height) for (x in 0 until config.width) {
                if (!inWater(x, y)) continue
                val cell = HandMadeWorlds.cellAt(config, x, y)
                water += cell
                if (asPlaya) playa[cell] = true else lakeId[cell] = 0
            }
            val lakes = LakeResult(
                lakeId,
                if (asPlaya) emptyList() else listOf(Lake(0, water.size, 0.1f, water[0], endorheic = true, spillElevation = 0.12f)),
                playa,
                config.width
            )
            // Inside the divide a step toward the water, outside it a step away toward the sea.
            val rivers = HandMadeWorlds.rivers(config, sea, { x, y ->
                val stepX = (20 - x).sign
                val stepY = (20 - y).sign
                when {
                    inWater(x, y) -> -1
                    inBasin(x, y) -> HandMadeWorlds.cellAt(
                        config,
                        x + if (x in 18..22) 0 else stepX,
                        y + if (y in 18..22) 0 else stepY
                    )
                    else -> HandMadeWorlds.cellAt(config, x - stepX, y - stepY)
                }
            }, lakes)
            val units = BasinPartition.compute(config, sea, rivers, Double.MAX_VALUE)
            val basinCells = (0 until cellCount).filter { inBasin(it % config.width, it / config.width) }
            val holding = unitsHolding(units, basinCells)
            println("CLOSED BASIN (%s) %d cells of water and %d of basin in %d units".format(if (asPlaya) "playa" else "lake", water.size, basinCells.size, holding.size))
            assertEquals(1, unitsHolding(units, water).size, "the ${if (asPlaya) "playa" else "lake"}'s water is in ${unitsHolding(units, water).size} units")
            assertEquals(1, holding.size, "a closed basin and the land draining to it came out as ${holding.size} units")
            assertEquals(basinCells.size, units.area[holding.single()], "the closed basin's unit holds land outside its divide")
        }
    }

    /**
     * A closed lake of two cells with nine cells of land draining into the one that is not its
     * sink, under a limit of ten cells. The lake's water is never cut, and the land is cut at the
     * limit like any other tributary, so no unit holds more than ten cells.
     *
     * Each water cell kept its own slopes up to the limit and the sink then kept every water cell
     * with whatever it had kept, so the lake's unit came to eleven cells under a limit of ten.
     * With [Double.MAX_VALUE] as the limit, as in the closed-basin case above, that could not show.
     */
    @Test
    fun `a closed lake's unit is cut at the limit like any other`() {
        val config = HandMadeWorlds.config()
        fun land(x: Int, y: Int) = y == 20 && x in 20..30
        val sea = HandMadeWorlds.sea(config, ::land) { x, y -> if (land(x, y)) 0.1f else -0.1f }
        val cellCount = config.width * config.height
        val sink = HandMadeWorlds.cellAt(config, 20, 20)
        val other = HandMadeWorlds.cellAt(config, 21, 20)
        val lakeId = IntArray(cellCount) { LakeResult.NO_LAKE }
        lakeId[sink] = 0
        lakeId[other] = 0
        val lakes = LakeResult(
            lakeId, listOf(Lake(0, 2, 0.1f, sink, endorheic = true, spillElevation = 0.12f)),
            BooleanArray(cellCount), config.width
        )
        val rivers = HandMadeWorlds.rivers(config, sea, { x, y ->
            if (x <= 21) -1 else HandMadeWorlds.cellAt(config, x - 1, y)
        }, lakes)
        val cellKm2 = config.squareKilometresPerCell
        val limitKm2 = 10.0 * cellKm2
        val units = BasinPartition.compute(config, sea, rivers, limitKm2)
        println("CLOSED LAKE LIMIT units ${units.area.sorted()} under a limit of 10 cells")
        assertEquals(units.unitOf[sink], units.unitOf[other], "the lake's water was cut in two")
        assertTrue(units.area.all { it <= 10 }, "a unit of ${units.area.max()} cells under a limit of 10")
    }

    /**
     * One catchment whose trunk runs due south down its middle column and crosses a lake eleven
     * cells wide on the way, every other cell draining sideways to the trunk. Cut along its trunk,
     * the catchment's two banks meet on the trunk's far edge, and the lake, being water, goes whole
     * to one of them: the border keeps to the shore rather than running down the middle of the lake.
     *
     * The seam followed the trunk across the lake, and a trunk across a lake runs over the flat the
     * fill made of it, straight wherever the flat's potential lies along a bearing: on seed 42 at
     * 2048 a realm border ran due south for 65 steps across one.
     */
    @Test
    fun `a lake a trunk crosses stays whole on one bank`() {
        val config = HandMadeWorlds.config()
        fun land(x: Int, y: Int) = x in 20..40 && y in 10..40
        fun inLake(x: Int, y: Int) = x in 25..35 && y in 20..28
        val sea = HandMadeWorlds.sea(config, ::land) { x, y -> if (land(x, y)) 0.1f else -0.1f }
        val cellCount = config.width * config.height
        val lakeId = IntArray(cellCount) { if (inLake(it % config.width, it / config.width)) 0 else LakeResult.NO_LAKE }
        val lakeCells = (0 until cellCount).filter { lakeId[it] == 0 }
        val lakes = LakeResult(
            lakeId, listOf(Lake(0, lakeCells.size, 0.1f, HandMadeWorlds.cellAt(config, 30, 28), endorheic = false, spillElevation = 0.1f)),
            BooleanArray(cellCount), config.width
        )
        val rivers = HandMadeWorlds.rivers(config, sea, { x, y ->
            when {
                x < 30 -> HandMadeWorlds.cellAt(config, x + 1, y)
                x > 30 -> HandMadeWorlds.cellAt(config, x - 1, y)
                else -> HandMadeWorlds.cellAt(config, x, y + 1)
            }
        }, lakes)
        val units = BasinPartition.compute(config, sea, rivers, Double.MAX_VALUE)
        val banked = BasinPartition.splitAlongTrunks(config, sea, rivers, units, 10f)
        val holding = unitsHolding(banked, lakeCells)
        println("LAKE ON A TRUNK %d units after the split; the lake's %d cells in %d".format(banked.unitCount, lakeCells.size, holding.size))
        assertEquals(2, banked.unitCount, "the catchment was not cut along its trunk, so the case asks nothing")
        assertEquals(1, holding.size, "the lake was cut in two along the trunk")
    }

    /**
     * An arid comb: four rows running east into a trunk down one column, the trunk into the sea,
     * three times `NationsConfig.maxBasinShare` of the land in all, on a hundred millimetres of
     * rain; every other row is its own small catchment. Cut by the realm stage's own partition,
     * every unit that results is within the share, by area on the ground.
     *
     * The rule compared the rain-weighted flow with a count of cells. A cell of this comb weighs
     * a ninth of a cell at 1,200 mm on the old scale and a twelfth on the new one, so its flow
     * never reached the count and the whole comb was one unit of three times the share.
     */
    @Test
    fun `an arid catchment is cut to the size limit`() {
        val config = HandMadeWorlds.config()
        val sea = block(config, 2..62, 0..63)
        val trunkColumn = 62
        val combRows = 0 until 4
        val rivers = HandMadeWorlds.rivers(config, sea, { x, y ->
            when {
                y in combRows && x < trunkColumn -> HandMadeWorlds.cellAt(config, x + 1, y)
                y in combRows && y < combRows.last -> HandMadeWorlds.cellAt(config, x, y + 1)
                else -> HandMadeWorlds.cellAt(config, x + 1, y)
            }
        }, flowOf = { Runoff.annualWeightMm(100f) })
        val landKm2 = sea.landCellCount * config.squareKilometresPerCell
        val limitKm2 = landKm2 * config.nations.maxBasinShare
        val combKm2 = combRows.count() * 61 * config.squareKilometresPerCell
        assertTrue(combKm2 > 2.5 * limitKm2, "the comb is %.1f times the limit, not three".format(combKm2 / limitKm2))

        val units = NationStage.realmUnits(config, sea, rivers)
        val largestKm2 = units.area.max() * config.squareKilometresPerCell
        println("ARID COMB %.1f times the share; largest unit %.2f of it, %d units".format(combKm2 / limitKm2, largestKm2 / limitKm2, units.unitCount))
        assertTrue(largestKm2 <= limitKm2, "a unit of %.0f km² against a limit of %.0f".format(largestKm2, limitKm2))
    }

    /**
     * A catchment a cell and a half short of the size limit, with a two-cell catchment against its side and
     * the sea on every other side of that one. The small one is under the smallest unit kept, and
     * its one land neighbour is the large one; merging the two would pass the limit, so it stays.
     * Merging had no size check, so a unit the partition had bounded could leave it unbounded.
     */
    @Test
    fun `a merge never takes a unit past the size limit`() {
        val config = HandMadeWorlds.config()
        // The large catchment is rows 20..29 of columns 10..29, running east; the small one is
        // two cells at columns 30..31 of row 25, running east to the sea.
        fun land(x: Int, y: Int) = (x in 10..29 && y in 20..29) || (x in 30..31 && y == 25)
        val sea = HandMadeWorlds.sea(config, ::land) { x, y -> if (land(x, y)) 0.1f else -0.1f }
        val rivers = HandMadeWorlds.rivers(config, sea, { x, y ->
            when {
                x in 30..31 -> HandMadeWorlds.cellAt(config, x + 1, y)
                x < 29 -> HandMadeWorlds.cellAt(config, x + 1, y)
                y < 29 -> HandMadeWorlds.cellAt(config, x, y + 1)
                else -> HandMadeWorlds.cellAt(config, x + 1, y)
            }
        })
        val cellKm2 = config.squareKilometresPerCell
        val limitKm2 = 201.5 * cellKm2
        val units = BasinPartition.compute(config, sea, rivers, limitKm2)
        assertEquals(listOf(2, 200), units.area.sorted(), "the fixture's two catchments")
        val merged = BasinPartition.mergeSmall(config, sea, units, 3.0 * cellKm2, limitKm2)
        println("MERGE LIMIT units after merging: ${merged.area.sorted()}")
        assertTrue(merged.area.all { it * cellKm2 <= limitKm2 }, "a merge made a unit of ${merged.area.max()} cells against a limit of 201.5")
    }

    /**
     * Two islands two cells of sea apart, inside the reach a strait crossing is looked for over: a
     * four-cell island that is one unit under the smallest kept, and a large one. The small one's
     * only neighbour is across the strait, and a catchment does not cross water, so it stays; the
     * landmass count does not fall across the merge and no unit holds ground on two landmasses.
     *
     * Merging took its host from the neighbour list that includes the strait crossings, so the
     * island joined the unit across the water and the landmass flood joined the two islands.
     */
    @Test
    fun `a small island is not merged across a strait`() {
        val config = HandMadeWorlds.config()
        fun land(x: Int, y: Int) = (x in 10..11 && y in 30..31) || (x in 14..40 && y in 20..40)
        val sea = HandMadeWorlds.sea(config, ::land) { x, y -> if (land(x, y)) 0.1f else -0.1f }
        val rivers = HandMadeWorlds.rivers(config, sea, { x, y ->
            when {
                x <= 11 -> HandMadeWorlds.cellAt(config, x - 1, y)
                x < 40 -> HandMadeWorlds.cellAt(config, x + 1, y)
                else -> HandMadeWorlds.cellAt(config, x + 1, y)
            }
        })
        val cellKm2 = config.squareKilometresPerCell
        val units = BasinPartition.compute(config, sea, rivers, Double.MAX_VALUE)
        val merged = BasinPartition.mergeSmall(config, sea, units, 10.0 * cellKm2, Double.MAX_VALUE)
        val onTwo = unitsOnTwoLandmasses(config, sea, merged)
        println("STRAIT landmasses %d before merging and %d after; %d units on two".format(units.landmassCount, merged.landmassCount, onTwo))
        assertEquals(units.landmassCount, merged.landmassCount, "the merge joined landmasses")
        assertEquals(0, onTwo, "a merged unit holds ground on two landmasses")
    }

    /** How many of [units] hold cells of more than one component of the land mask. */
    private fun unitsOnTwoLandmasses(config: WorldGenConfig, sea: SeaLevelResult, units: BasinUnits): Int {
        val component = HandMadeWorlds.landComponents(config, sea.isLand)
        val componentOfUnit = IntArray(units.unitCount) { -1 }
        val spans = BooleanArray(units.unitCount)
        for (cell in units.unitOf.indices) {
            val unit = units.unitOf[cell]
            if (unit == BasinUnits.NONE) continue
            if (componentOfUnit[unit] < 0) componentOfUnit[unit] = component[cell]
            else if (componentOfUnit[unit] != component[cell]) spans[unit] = true
        }
        return spans.count { it }
    }

    /**
     * The same three rules on the standard worlds, through both stages' own partitions: every
     * land cell in one unit, no unit over its stage's size limit, and none on two landmasses.
     */
    @Test
    fun `the standard worlds' units are bounded and each on one landmass`() {
        for (seed in listOf(7L, 42L, 1234L)) {
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val world = SharedWorlds.world(config)
            val landKm2 = world.sea.landCellCount * config.squareKilometresPerCell
            for ((stage, units, share) in listOf(
                Triple("realms", NationStage.realmUnits(config, world.sea, world.rivers), config.nations.maxBasinShare),
                Triple("peoples", CultureStage.regionUnits(config, world.sea, world.rivers), config.cultures.maxRegionShare)
            )) {
                val limitKm2 = landKm2 * share
                val unassigned = world.sea.isLand.indices.count { world.sea.isLand[it] && units.unitOf[it] == BasinUnits.NONE }
                val over = units.area.count { it * config.squareKilometresPerCell > limitKm2 }
                val onTwo = unitsOnTwoLandmasses(config, world.sea, units)
                println(
                    "UNITS seed %d %s: %d units, largest %.2f of the limit, %d over, %d on two landmasses"
                        .format(seed, stage, units.unitCount, units.area.max() * config.squareKilometresPerCell / limitKm2, over, onTwo)
                )
                assertEquals(0, unassigned, "seed $seed $stage: land cells in no unit")
                assertEquals(0, over, "seed $seed $stage: units over the size limit")
                assertEquals(0, onTwo, "seed $seed $stage: units on two landmasses")
            }
        }
    }
}
