package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rift is the same rift at every grid: the same half-grabens in the same places, each as deep
 * and hanging from the same flank, whichever grid the world is cut into.
 *
 * The rifts' segments were drawn from a stream seeded by the lowest cell index of each run of
 * boundary cells, which is a different number at every grid, so the same rift broke into different
 * basins at 256, 512 and 1,024 rows. On seed 42 that put a trough's floor below the shoreline over
 * 11.8% of it at 256 rows, 2.2% at 512 and 4.5% at 1,024, and the lake it held followed: one
 * endorheic lake of 281,662 km2 at 512 rows, two of 44,117 and 24,616 km2 at 1,024
 * (docs/DESIGN_LEDGER.md, L1).
 *
 * Measured on the plate stage alone, so the class costs three tectonic stages per seed and no
 * erosion. Each rift is compared cell by cell on the ground between each coarser grid and 1,024
 * rows; a cell within a cell width of either grid's join is left out, since a join can fall
 * anywhere inside the cell that holds it at each grid.
 */
class RiftIdentityTest {

    private val seeds = listOf(7L, 42L, 1234L, 99L, 718106L, 59758L)
    private val coarseRows = listOf(256, 512)
    private val fineRows = 1024

    /** One grid's rift cells, found by where they stand on the ground. */
    private class Grid(val config: WorldGenConfig, val report: PlateStage.RiftSegmentReport) {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellWidthKm = config.cellWidthKm
        val cellHeightKm = config.cellHeightKm
        val entryAt = HashMap<Int, Int>().also { map -> report.cell.forEachIndexed { entry, cell -> map[cell] = entry } }

        fun pairOf(entry: Int) = report.lowId[entry] * PAIR_STRIDE + report.highId[entry]

        /** The entry of the rift cell of [pair] nearest the ground point, within [radiusKm], or -1. */
        fun nearest(eastKm: Double, southKm: Double, pair: Int, radiusKm: Double): Int {
            val column = (eastKm / cellWidthKm).toInt()
            val row = (southKm / cellHeightKm).toInt()
            val reachColumns = ceil(radiusKm / cellWidthKm).toInt()
            val reachRows = ceil(radiusKm / cellHeightKm).toInt()
            var best = -1
            var bestKm = Double.MAX_VALUE
            for (rowStep in -reachRows..reachRows) {
                val atRow = row + rowStep
                if (atRow < 0 || atRow >= cellsDown) continue
                for (columnStep in -reachColumns..reachColumns) {
                    val atColumn = Math.floorMod(column + columnStep, cellsAcross)
                    val entry = entryAt[atRow * cellsAcross + atColumn] ?: continue
                    if (pairOf(entry) != pair) continue
                    var acrossKm = (atColumn + 0.5) * cellWidthKm - eastKm
                    val worldKm = cellsAcross * cellWidthKm
                    if (acrossKm > worldKm / 2) acrossKm -= worldKm
                    if (acrossKm < -worldKm / 2) acrossKm += worldKm
                    val downKm = (atRow + 0.5) * cellHeightKm - southKm
                    val km = sqrt(acrossKm * acrossKm + downKm * downKm)
                    if (km <= radiusKm && km < bestKm) { bestKm = km; best = entry }
                }
            }
            return best
        }
    }

    private fun grid(seed: Long, rows: Int, worldWidthKm: Double? = null): Grid {
        val base = WorldGenConfig.forRows(seed, rows)
        val config = if (worldWidthKm == null) base else base.copy(scale = base.scale.copy(worldWidthKm = worldWidthKm))
        return Grid(config, PlateStage.presentRiftSegments(config))
    }

    /**
     * The same segment on the ground at every grid: the same depth factor and the same polarity at
     * every rift cell of a coarser grid as at the cell of the same rift nearest it at 1,024 rows.
     *
     * The bar is policy, not derivation: 95% of the compared cells of every rift. What it refuses is
     * a rift drawn from a different stream at each grid, which agrees by chance on half its polarity
     * and on none of its depths; what it allows is a join a cell or two off where the arc along a
     * meandering rift reads a little differently on a coarser grid. With it, every half-graben is
     * there at both grids under the same name over the stretch of rift both grids reach, which is the
     * same count and the same breaks, and at each end the two may differ by the one half-graben a
     * rift's end moving by a cell takes in or leaves out. So a cell in either grid's end
     * half-graben of its stretch is not compared: seed 59758's rift 1-13 reaches 57 km further at
     * 256 rows than at 1,024 and takes in one more half-graben there, and its one cell in it read
     * as a disagreement. How far apart the breaks stand is printed.
     */
    @Test
    fun `a rift breaks into the same half-grabens at every grid`() {
        val failures = ArrayList<String>()
        seeds.forEach { seed ->
            val fine = grid(seed, fineRows)
            val fineEnds = endOrdinals(fine)
            coarseRows.forEach { rows ->
                val coarse = grid(seed, rows)
                val coarseEnds = endOrdinals(coarse)
                val toleranceKm = coarse.cellWidthKm + fine.cellWidthKm
                val compared = HashMap<Int, Int>()
                val agreed = HashMap<Int, Int>()
                coarse.report.cell.indices.forEach { entry ->
                    if (coarse.report.toJoinKm[entry] < toleranceKm) return@forEach
                    val cell = coarse.report.cell[entry]
                    val eastKm = (cell % coarse.cellsAcross + 0.5) * coarse.cellWidthKm
                    val southKm = (cell / coarse.cellsAcross + 0.5) * coarse.cellHeightKm
                    val pair = coarse.pairOf(entry)
                    val match = fine.nearest(eastKm, southKm, pair, coarse.cellWidthKm)
                    if (match < 0 || fine.report.toJoinKm[match] < toleranceKm) return@forEach
                    if (coarse.report.ordinal[entry] in coarseEnds.getValue(pair to coarse.report.chain[entry])) return@forEach
                    if (fine.report.ordinal[match] in fineEnds.getValue(pair to fine.report.chain[match])) return@forEach
                    compared[pair] = (compared[pair] ?: 0) + 1
                    val same = coarse.report.footwallOnLow[entry] == fine.report.footwallOnLow[match] &&
                        abs(coarse.report.depthFactor[entry] - fine.report.depthFactor[match]) < SAME_FACTOR
                    if (same) agreed[pair] = (agreed[pair] ?: 0) + 1
                }
                compared.keys.sorted().forEach { pair ->
                    val share = (agreed[pair] ?: 0).toDouble() / compared.getValue(pair)
                    val coarseSegments = halfGrabens(coarse, pair)
                    val fineSegments = halfGrabens(fine, pair)
                    // Every course both grids draw, and within the stretch of it both reach, the same
                    // half-grabens; at each end the two may differ by the one half-graben a rift's end
                    // moving by a cell can take in or leave out.
                    var breakOffsetKm = 0.0
                    val mismatches = ArrayList<String>()
                    (coarseSegments.keys + fineSegments.keys).sorted().forEach { course ->
                        val atCoarse = coarseSegments[course].orEmpty()
                        val atFine = fineSegments[course].orEmpty()
                        if (atCoarse.isEmpty() || atFine.isEmpty()) {
                            mismatches += "stretch $course drawn at one grid only"
                            return@forEach
                        }
                        val low = maxOf(atCoarse.keys.min(), atFine.keys.min())
                        val high = minOf(atCoarse.keys.max(), atFine.keys.max())
                        if (abs(atCoarse.keys.min() - atFine.keys.min()) > 1 || abs(atCoarse.keys.max() - atFine.keys.max()) > 1) {
                            mismatches += "stretch $course runs ${atCoarse.keys.min()}..${atCoarse.keys.max()} against " +
                                "${atFine.keys.min()}..${atFine.keys.max()}"
                        }
                        for (ordinal in low..high) {
                            val coarseStart = atCoarse[ordinal]
                            val fineStart = atFine[ordinal]
                            if (coarseStart == null || fineStart == null) {
                                mismatches += "stretch $course half-graben $ordinal at one grid only"
                            } else if (ordinal != low) {
                                breakOffsetKm = maxOf(breakOffsetKm, abs(coarseStart - fineStart).toDouble())
                            }
                        }
                    }
                    println(
                        ("RIFT IDENTITY seed %d rift %d-%d at %d rows against %d: %.3f of %d cells agree; %d half-grabens " +
                            "against %d, their breaks at most %.0f km apart")
                            .format(seed, pair / PAIR_STRIDE, pair % PAIR_STRIDE, rows, fineRows, share,
                                compared.getValue(pair), coarseSegments.values.sumOf { it.size },
                                fineSegments.values.sumOf { it.size }, breakOffsetKm)
                    )
                    if (compared.getValue(pair) >= MIN_COMPARED_CELLS && share < MIN_AGREEING_SHARE) {
                        failures += "seed $seed rift ${pair / PAIR_STRIDE}-${pair % PAIR_STRIDE} at $rows rows: %.3f agree".format(share)
                    }
                    if (mismatches.isNotEmpty()) {
                        failures += "seed $seed rift ${pair / PAIR_STRIDE}-${pair % PAIR_STRIDE} at $rows rows: $mismatches"
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "a rift is a different rift at another grid: $failures")
    }

    /** The first and last half-graben of every stretch of rift, by its pair and stretch. */
    private fun endOrdinals(grid: Grid): Map<Pair<Int, Int>, Set<Int>> {
        val ends = HashMap<Pair<Int, Int>, IntArray>()
        grid.report.cell.indices.forEach { entry ->
            val key = grid.pairOf(entry) to grid.report.chain[entry]
            val ordinal = grid.report.ordinal[entry]
            val range = ends.getOrPut(key) { intArrayOf(ordinal, ordinal) }
            range[0] = minOf(range[0], ordinal)
            range[1] = maxOf(range[1], ordinal)
        }
        return ends.mapValues { (_, range) -> setOf(range[0], range[1]) }
    }

    /**
     * Every half-graben of [pair], by the stretch of rift it lies on and its place along it, with
     * where along the rift it starts in kilometers.
     */
    private fun halfGrabens(grid: Grid, pair: Int): Map<Int, Map<Int, Float>> {
        val starts = HashMap<Int, HashMap<Int, Float>>()
        grid.report.cell.indices.forEach { entry ->
            if (grid.pairOf(entry) != pair) return@forEach
            val ofCourse = starts.getOrPut(grid.report.chain[entry]) { HashMap() }
            val ordinal = grid.report.ordinal[entry]
            val along = grid.report.alongKm[entry]
            ofCourse[ordinal] = minOf(ofCourse[ordinal] ?: along, along)
        }
        return starts
    }

    /**
     * A stretch of rift keeps its half-grabens when another stretch of the same pair comes or goes.
     *
     * Seed 42's north-south rift is cut in two places, 250 km apart and more than the trough's
     * half-width across, so it breaks into three stretches with a short one of 80 km in the middle;
     * then the short one is taken away too. Which stretches a pair's boundary breaks into is a
     * question of topology, a third plate pinching it here or a gap just over the joining
     * threshold there, and it moves with the grid; so nothing about a stretch's half-grabens may
     * hang on how many other stretches the pair has or where they rank. Every cell of the two outer
     * stretches reads the same half-graben, depth, polarity and place along the rift either way.
     */
    @Test
    fun `a stretch of rift keeps its half-grabens when another stretch of the pair comes or goes`() {
        val config = WorldGenConfig.forRows(42L, 512)
        val whole = PlateStage.presentRiftSegments(config)
        val pair = 2 * PAIR_STRIDE + 7
        val entries = whole.cell.indices.filter { whole.lowId[it] * PAIR_STRIDE + whole.highId[it] == pair }
        val start = entries.minOf { whole.alongKm[it] } + CUT_FROM_THE_END_KM
        fun cellsBetween(fromKm: Float, toKm: Float) =
            entries.filter { whole.alongKm[it] in fromKm..toKm }.map { whole.cell[it] }.toSet()
        val firstGap = cellsBetween(start, start + GAP_KM)
        val short = cellsBetween(start + GAP_KM, start + GAP_KM + SHORT_STRETCH_KM)
        val secondGap = cellsBetween(start + GAP_KM + SHORT_STRETCH_KM, start + 2 * GAP_KM + SHORT_STRETCH_KM)
        val withShort = PlateStage.presentRiftSegmentsWithout(config, firstGap + secondGap)
        val withoutShort = PlateStage.presentRiftSegmentsWithout(config, firstGap + short + secondGap)
        fun byCell(report: PlateStage.RiftSegmentReport) =
            report.cell.indices.filter { report.lowId[it] * PAIR_STRIDE + report.highId[it] == pair }
                .associateBy { report.cell[it] }
        val a = byCell(withShort)
        val b = byCell(withoutShort)
        val outer = entries.map { whole.cell[it] }.filter { it !in firstGap && it !in short && it !in secondGap }
        val moved = outer.filter { cell ->
            val x = a.getValue(cell)
            val y = b.getValue(cell)
            withShort.ordinal[x] != withoutShort.ordinal[y] ||
                withShort.depthFactor[x] != withoutShort.depthFactor[y] ||
                withShort.footwallOnLow[x] != withoutShort.footwallOnLow[y] ||
                withShort.alongKm[x] != withoutShort.alongKm[y]
        }
        val stretches = listOf(withShort, withoutShort).map { report ->
            report.cell.indices.filter { report.lowId[it] * PAIR_STRIDE + report.highId[it] == pair }.map { report.chain[it] }.toSet().size
        }
        println(
            "RIFT STRETCHES seed 42 rift 2-7: %d and %d stretches with the short one and without; %d of %d outer cells read another half-graben"
                .format(stretches[0], stretches[1], moved.size, outer.size)
        )
        assertEquals(listOf(3, 2), stretches, "the cuts did not make the stretches this case is about")
        assertTrue(moved.isEmpty(), "${moved.size} of ${outer.size} cells of the other stretches read another half-graben")
    }

    /**
     * The plate floor stands below the shoreline over the same share of each rift's corridor at
     * every grid.
     *
     * The corridor is the trough, `riftWidthKm` either side of the rift's own cells. Each grid draws
     * the shoreline across it to within half its own cell on each side of the trough, so the share
     * can move by the two grids' half cells against the trough's full width, `2 riftWidthKm`, and no
     * more: 0.089 between 256 and 1,024 rows, 0.054 between 512 and 1,024. A rift shorter than two of
     * its longest half-grabens at any grid is left out; it has no chain to compare, and where the
     * plates meet a third at each end its length is the partition's, a separate question
     * (docs/TODO.md).
     */
    @Test
    fun `a rift floor lies below the shoreline over the same share of its trough at every grid`() {
        val failures = ArrayList<String>()
        seeds.forEach { seed ->
            val shares = listOf(256, 512, fineRows).associateWith { rows -> corridorShares(WorldGenConfig.forRows(seed, rows)) }
            val fine = shares.getValue(fineRows)
            coarseRows.forEach { rows ->
                val coarse = shares.getValue(rows)
                val config = WorldGenConfig.forRows(seed, rows)
                val bar = (config.cellWidthKm + WorldGenConfig.forRows(seed, fineRows).cellWidthKm) /
                    (2 * config.tectonics.riftWidthKm)
                coarse.keys.intersect(fine.keys).sorted().forEach { pair ->
                    val (coarseShare, coarseLengthKm) = coarse.getValue(pair)
                    val (fineShare, fineLengthKm) = fine.getValue(pair)
                    val shortest = 2 * config.tectonics.riftSegmentMaxKm
                    val difference = abs(coarseShare - fineShare)
                    println(
                        "RIFT FLOOR seed %d rift %d-%d: %.3f of the trough below the shoreline at %d rows, %.3f at %d (bar %.3f), %.0f and %.0f km long"
                            .format(seed, pair / PAIR_STRIDE, pair % PAIR_STRIDE, coarseShare, rows, fineShare, fineRows, bar, coarseLengthKm, fineLengthKm)
                    )
                    if (coarseLengthKm < shortest || fineLengthKm < shortest) return@forEach
                    if (difference > bar) {
                        failures += "seed $seed rift ${pair / PAIR_STRIDE}-${pair % PAIR_STRIDE}: %.3f at %d rows against %.3f at %d"
                            .format(coarseShare, rows, fineShare, fineRows)
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "a rift floor floods a different share of its trough at another grid: $failures")
    }

    /** Each rift's share of its trough below the plate stage's shoreline, and its length in km. */
    private fun corridorShares(config: WorldGenConfig): Map<Int, Pair<Double, Double>> {
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val report = PlateStage.presentRiftSegments(config)
        val shoreline = SeaLevelStage.percentileCut(plates.height, config.seaLevel, config.scale).shorelineHeight
        val cellsAcross = config.width
        val cellsDown = config.height
        val rift = BoundaryClass.CONTINENTAL_RIFT.ordinal
        val owner = IntArray(cellsAcross * cellsDown) { -1 }
        val reachKm = config.tectonics.riftWidthKm
        val reachColumns = ceil(reachKm / config.cellWidthKm).toInt()
        val reachRows = ceil(reachKm / config.cellHeightKm).toInt()
        val lengthKm = HashMap<Int, Double>()
        // Pairs in ascending order, so where two troughs overlap the lower pair keeps the cell.
        val entries = report.cell.indices.sortedBy { report.lowId[it] * PAIR_STRIDE + report.highId[it] }
        entries.forEach { entry ->
            val pair = report.lowId[entry] * PAIR_STRIDE + report.highId[entry]
            // Two boundary cells, one either side, stand for each cell width of rift.
            lengthKm[pair] = (lengthKm[pair] ?: 0.0) + config.cellWidthKm / 2
            val cell = report.cell[entry]
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            for (rowStep in -reachRows..reachRows) {
                val atRow = row + rowStep
                if (atRow < 0 || atRow >= cellsDown) continue
                val downKm = rowStep * config.cellHeightKm
                for (columnStep in -reachColumns..reachColumns) {
                    val acrossKm = columnStep * config.cellWidthKm
                    if (acrossKm * acrossKm + downKm * downKm > reachKm * reachKm) continue
                    val target = atRow * cellsAcross + Math.floorMod(column + columnStep, cellsAcross)
                    if (owner[target] < 0 && plates.nearestBoundaryClass[target] == rift) owner[target] = pair
                }
            }
        }
        val below = HashMap<Int, Int>()
        val total = HashMap<Int, Int>()
        owner.forEachIndexed { cell, pair ->
            if (pair < 0) return@forEachIndexed
            total[pair] = (total[pair] ?: 0) + 1
            if (plates.height.data[cell] < shoreline) below[pair] = (below[pair] ?: 0) + 1
        }
        return total.keys.associateWith { pair ->
            (below[pair] ?: 0).toDouble() / total.getValue(pair) to lengthKm.getValue(pair)
        }
    }

    /**
     * Every whole half-graben on the ground is one of Earth's lengths, at every grid and on a world
     * of another size: `TectonicsConfig.riftSegmentMinKm` to `riftSegmentMaxKm`.
     *
     * Two rulers, each honest on one side. A walk over the rift's own cells from one end of the
     * half-graben to the other follows every bend of the drawn boundary, so it is never shorter than
     * the course the joins were placed along; a straight line between the two ends is never longer.
     * So the walk is held to the shortest length and the straight line to the longest. Both read
     * from the middle of one end cell to the middle of the other, and each end cell lies within a
     * cell of its join on the ground, along the rift or across its two-cell band, so each ruler is
     * allowed two cells of slack. The half-grabens at each end of a rift are cut short by where the
     * rift ends, and are not measured.
     */
    @Test
    fun `every half-graben is between Earth's shortest and longest at every grid and at another size`() {
        val failures = ArrayList<String>()
        val cases = seeds.flatMap { seed ->
            listOf(256, 512, 1024).map { rows -> Triple(seed, rows, null as Double?) } +
                Triple(seed, 512, SECOND_WORLD_WIDTH_KM)
        }
        cases.forEach { (seed, rows, worldWidthKm) ->
            val grid = grid(seed, rows, worldWidthKm)
            val measured = halfGrabenLengthsKm(grid)
            val tectonics = grid.config.tectonics
            val lowestKm = tectonics.riftSegmentMinKm - 2 * grid.cellWidthKm
            val highestKm = tectonics.riftSegmentMaxKm + 2 * grid.cellWidthKm
            val walks = measured.map { it.first }.sorted()
            val lines = measured.map { it.second }.sorted()
            val tooShort = walks.filter { it < lowestKm }
            val tooLong = lines.filter { it > highestKm }
            println(
                ("RIFT LENGTH seed %d at %d rows on a world %.0f km wide: %d whole half-grabens, walked %.0f to %.0f km " +
                    "(median %.0f), straight %.0f to %.0f km (median %.0f); %d walked under %.0f, %d straight over %.0f")
                    .format(seed, rows, grid.config.scale.worldWidthKm, measured.size, walks.firstOrNull() ?: 0.0,
                        walks.lastOrNull() ?: 0.0, walks.getOrElse(walks.size / 2) { 0.0 }, lines.firstOrNull() ?: 0.0,
                        lines.lastOrNull() ?: 0.0, lines.getOrElse(lines.size / 2) { 0.0 }, tooShort.size, lowestKm,
                        tooLong.size, highestKm)
            )
            if (tooShort.isNotEmpty() || tooLong.isNotEmpty()) {
                failures += "seed $seed at $rows rows, world ${grid.config.scale.worldWidthKm} km: " +
                    (tooShort + tooLong).take(6).joinToString { "%.0f".format(it) }
            }
        }
        assertTrue(failures.isEmpty(), "half-grabens outside Earth's lengths: $failures")
    }

    /**
     * Each whole half-graben's length twice over, in kilometers: walked over its own cells from one
     * end to the other, and in a straight line between the two ends.
     */
    private fun halfGrabenLengthsKm(grid: Grid): List<Pair<Double, Double>> {
        val report = grid.report
        val bySegment = HashMap<Triple<Int, Int, Int>, MutableList<Int>>()
        val chainEnds = HashMap<Pair<Int, Int>, Pair<Int, Int>>()
        report.cell.indices.forEach { entry ->
            val chainKey = grid.pairOf(entry) to report.chain[entry]
            val ordinal = report.ordinal[entry]
            bySegment.getOrPut(Triple(chainKey.first, chainKey.second, ordinal)) { ArrayList() }.add(entry)
            val (low, high) = chainEnds[chainKey] ?: (ordinal to ordinal)
            chainEnds[chainKey] = minOf(low, ordinal) to maxOf(high, ordinal)
        }
        val steps = grid.config.groundSteps
        val result = ArrayList<Pair<Double, Double>>()
        bySegment.forEach { (key, entries) ->
            val (low, high) = chainEnds.getValue(key.first to key.second)
            if (key.third == low || key.third == high) return@forEach
            val cells = entries.map { report.cell[it] }
            val local = HashMap<Int, Int>().also { map -> cells.forEachIndexed { index, cell -> map[cell] = index } }
            val firstEnd = entries.minByOrNull { report.alongKm[it] }!!
            val lastEnd = entries.maxByOrNull { report.alongKm[it] }!!
            val walked = DoubleArray(cells.size) { Double.MAX_VALUE }
            val start = local.getValue(report.cell[firstEnd])
            walked[start] = 0.0
            val queue = java.util.PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
            queue.add(0.0 to start)
            while (queue.isNotEmpty()) {
                val (here, index) = queue.poll()
                if (here > walked[index]) continue
                val cell = cells[index]
                val column = cell % grid.cellsAcross
                val row = cell / grid.cellsAcross
                for (rowStep in -1..1) for (columnStep in -1..1) {
                    if (rowStep == 0 && columnStep == 0) continue
                    val atRow = row + rowStep
                    if (atRow < 0 || atRow >= grid.cellsDown) continue
                    val neighbor = local[atRow * grid.cellsAcross + Math.floorMod(column + columnStep, grid.cellsAcross)] ?: continue
                    val there = here + steps.of(columnStep, rowStep) * grid.cellWidthKm
                    if (there < walked[neighbor]) {
                        walked[neighbor] = there
                        queue.add(there to neighbor)
                    }
                }
            }
            val end = walked[local.getValue(report.cell[lastEnd])]
            // A cell is a cell width of rift, and the walk runs from the middle of one end cell to
            // the middle of the other.
            if (end == Double.MAX_VALUE) return@forEach
            val first = report.cell[firstEnd]
            val last = report.cell[lastEnd]
            var acrossKm = (last % grid.cellsAcross - first % grid.cellsAcross) * grid.cellWidthKm
            val worldKm = grid.cellsAcross * grid.cellWidthKm
            if (acrossKm > worldKm / 2) acrossKm -= worldKm
            if (acrossKm < -worldKm / 2) acrossKm += worldKm
            val downKm = (last / grid.cellsAcross - first / grid.cellsAcross) * grid.cellHeightKm
            result += end to sqrt(acrossKm * acrossKm + downKm * downKm)
        }
        return result
    }

    private companion object {
        const val PAIR_STRIDE = 1000

        /** Where the cuts begin along the rift, away from its end, and how long each gap is: more than `riftWidthKm`. */
        const val CUT_FROM_THE_END_KM = 1_000f
        const val GAP_KM = 250f

        /** The short stretch left between the two gaps. */
        const val SHORT_STRETCH_KM = 80f
        const val SAME_FACTOR = 1e-6f
        const val MIN_AGREEING_SHARE = 0.95
        /** A rift with fewer compared cells than this is a stub at the coarser grid. */
        const val MIN_COMPARED_CELLS = 8
        /** A second world, five thirds as wide as the one the knobs are calibrated on. */
        const val SECOND_WORLD_WIDTH_KM = 20_000.0
    }
}
