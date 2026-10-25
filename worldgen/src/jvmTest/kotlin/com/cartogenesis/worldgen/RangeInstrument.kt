package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.OrogenyKind
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The design's named-range instrument (TH1 design, section 7), found from the ground alone and read
 * the same way on any world, and stratified by the belt's kind and age (docs/DESIGN_LEDGER.md,
 * TH1-2).
 *
 * A range's axis: the land's elevation smoothed over [SMOOTHING_KM], above [YOUNG_THRESHOLD_METERS]
 * (an old belt's own [OLD_THRESHOLD_METERS]: the young threshold misses old low belts), thinned to
 * its skeleton (Zhang and Suen), the skeleton's longest path of at least [SHORTEST_RANGE_KM]. At
 * every cell along the axis a line across it of [HALF_SWATH_KM] either way is read, and the
 * figures come from those lines: the crest's spread, the crest zone's dissection, the width's
 * variation, the high ground's runs and massifs, the crests across.
 *
 * The arithmetic is the design's tools' (`crest.py`, `ranges.py`), line for line, so a figure
 * here and one there are the same figure: Python's rounding of halves to even is kept, and a
 * percentile is numpy's linear one.
 */
internal object RangeInstrument {

    const val SMOOTHING_KM = 60.0
    const val YOUNG_THRESHOLD_METERS = 1_500.0

    /**
     * An old belt's threshold, meters: Earth's Appalachians crest at a mean of 592 m in the curated
     * table (`measures.tsv`, GMRT), under the young threshold almost everywhere, and stand at 500
     * to 1,000 m over most of their length; 500 m finds them and their kin without taking in the
     * cratons' plateaus, which stand lower once smoothed over 60 km.
     */
    const val OLD_THRESHOLD_METERS = 500.0
    const val SHORTEST_RANGE_KM = 800.0
    const val HALF_SWATH_KM = 200.0

    /** Ranges are read equatorward of this latitude, degrees, where a cell's width on the map is within a factor of three of the ground's. */
    const val MAX_LATITUDE_DEGREES = 70.0

    /** A belt older than this is old, Myr: the design's split between belts still standing on their thickening and worn ones. */
    const val OLD_AGE_MYR = 100.0

    enum class Stratum { ANDEAN, COLLISION, TERRANE, BACK_ARC, RIFT, OLD }

    /** One range's figures, as `crest.matrix_stats` reads them. */
    class Range(
        val stratum: Stratum,
        val lengthKm: Double,
        val meanM: Double,
        val cv: Double,
        val p90MinusP10M: Double,
        val passesPer1000Km: Double,
        val crestZoneLowShare: Double,
        val widthCv: Double,
        val widthMeanKm: Double,
        val highRunMeanKm: Double,
        val massifsPer1000Km: Double,
        val rangesAcrossMean: Double,
        val swathMeanCv: Double,
        /** The axis's cells, row-major indices, in order along it. */
        val axis: IntArray
    )

    /** Length-weighted means of a stratum's ranges. */
    class Pooled(val count: Int, val lengthKm: Double, val values: Map<String, Double>) {
        operator fun get(key: String): Double = values[key] ?: Double.NaN
    }

    val KEYS = listOf(
        "crestZoneLowShare", "widthCv", "highRunMeanKm", "massifsPer1000Km", "rangesAcrossMean", "cv", "p90MinusP10M",
        "meanM", "passesPer1000Km", "swathMeanCv", "widthMeanKm"
    )

    private fun valueOf(range: Range, key: String): Double = when (key) {
        "crestZoneLowShare" -> range.crestZoneLowShare
        "widthCv" -> range.widthCv
        "highRunMeanKm" -> range.highRunMeanKm
        "massifsPer1000Km" -> range.massifsPer1000Km
        "rangesAcrossMean" -> range.rangesAcrossMean
        "cv" -> range.cv
        "p90MinusP10M" -> range.p90MinusP10M
        "meanM" -> range.meanM
        "passesPer1000Km" -> range.passesPer1000Km
        "swathMeanCv" -> range.swathMeanCv
        "widthMeanKm" -> range.widthMeanKm
        else -> error("no figure $key")
    }

    fun pooled(ranges: List<Range>): Pooled {
        val length = ranges.sumOf { it.lengthKm }
        val values = HashMap<String, Double>()
        for (key in KEYS) {
            var sum = 0.0
            var weight = 0.0
            for (range in ranges) {
                val value = valueOf(range, key)
                if (!value.isFinite()) continue
                sum += value * range.lengthKm
                weight += range.lengthKm
            }
            if (weight > 0.0) values[key] = sum / weight
        }
        return Pooled(ranges.size, length, values)
    }

    /** The world's land elevation, meters: above the shoreline on land, below it at sea. */
    fun elevationMeters(world: WorldMap): DoubleArray {
        val scale = world.config.scale
        val relative = world.sea.relativeElevation.data
        val land = world.sea.isLand
        return DoubleArray(relative.size) {
            if (land[it]) scale.metresAboveShoreline(relative[it]).toDouble() else scale.metresBelowShoreline(relative[it]).toDouble()
        }
    }

    /** Every range on [world], each with its stratum. */
    fun ranges(world: WorldMap): List<Range> {
        val config = world.config
        val width = config.width
        val height = config.height
        val cellWidthKm = config.cellWidthKm
        val cellHeightKm = config.cellHeightKm
        val elevation = elevationMeters(world)
        val land = world.sea.isLand
        val found = ArrayList<Range>()
        for ((threshold, oldOnly) in listOf(YOUNG_THRESHOLD_METERS to false, OLD_THRESHOLD_METERS to true)) {
            for (axis in axes(width, height, cellWidthKm, cellHeightKm, elevation, land, threshold)) {
                val stratum = stratumOf(world, axis)
                if (oldOnly != (stratum == Stratum.OLD)) continue
                val swath = swath(width, height, cellWidthKm, cellHeightKm, elevation, land, axis)
                if (swath.size < MIN_STATIONS) continue
                found.add(figures(swath, cellWidthKm, stratum, axis))
            }
        }
        return found
    }

    /**
     * The length of every range axis found at [thresholdMeters] on [world], km, whatever its stratum:
     * what Earth's own reading counts, its ranges having no stratum. A station a cell, as the swath
     * reads them, so a length here is the instrument's `lengthKm` on Earth.
     */
    fun axisLengthsKm(world: WorldMap, thresholdMeters: Double): List<Double> {
        val config = world.config
        val elevation = elevationMeters(world)
        return axes(config.width, config.height, config.cellWidthKm, config.cellHeightKm, elevation, world.sea.isLand, thresholdMeters)
            .map { axis -> axis.size * config.cellWidthKm }
    }

    /** The stratum of a range along [axis]: its commonest kind of deformation and the median age of its crust. */
    fun stratumOf(world: WorldMap, axis: IntArray): Stratum {
        val kinds = IntArray(OrogenyKind.entries.size)
        for (cell in axis) kinds[world.plates.orogenyKind[cell]]++
        var kind = 0
        for (index in 1 until kinds.size) if (kinds[index] > kinds[kind]) kind = index
        val ages = axis.map { world.plates.crustAgeMyr.data[it].toDouble() }.sorted()
        val age = median(ages)
        val orogeny = OrogenyKind.entries[kind]
        if (age > OLD_AGE_MYR || orogeny == OrogenyKind.ASSEMBLY_SUTURE || orogeny == OrogenyKind.NONE) return Stratum.OLD
        return when (orogeny) {
            OrogenyKind.ISLAND_ARC, OrogenyKind.ACCRETED_TERRANE -> Stratum.TERRANE
            OrogenyKind.COLLISION -> Stratum.COLLISION
            OrogenyKind.ANDEAN -> Stratum.ANDEAN
            OrogenyKind.RIFT -> Stratum.RIFT
            OrogenyKind.BACK_ARC_EXTENSION -> Stratum.BACK_ARC
            else -> Stratum.OLD
        }
    }

    // ------------------------------------------------------------------------------------ axes

    fun axes(width: Int, height: Int, cellWidthKm: Double, cellHeightKm: Double, elevation: DoubleArray, land: BooleanArray, threshold: Double): List<IntArray> {
        val ground = DoubleArray(width * height) { if (land[it]) max(elevation[it], 0.0) else 0.0 }
        val radius = max(1, pythonRound(SMOOTHING_KM / cellWidthKm))
        val smooth = blur(ground, width, height, radius)
        val high = BooleanArray(width * height) { cell ->
            val latitude = 90.0 - (cell / width + 0.5) * 180.0 / height
            smooth[cell] >= threshold && abs(latitude) < MAX_LATITUDE_DEGREES
        }
        val skeleton = zhangSuen(high, width, height)
        val axes = ArrayList<IntArray>()
        for (component in components(skeleton, width, height)) {
            if (component.size * cellWidthKm < SHORTEST_RANGE_KM * 0.7) continue
            val (path, length) = longestPath(component, width, height, cellWidthKm, cellHeightKm)
            if (length >= SHORTEST_RANGE_KM) axes.add(path)
        }
        return axes
    }

    /** `ranges.blur`: a Gaussian of spread [radius] cells, reach three spreads, wrapping east-west and held at the poles. */
    fun blur(values: DoubleArray, width: Int, height: Int, radius: Int): DoubleArray {
        val reach = 3 * radius
        val kernel = DoubleArray(2 * reach + 1) { exp(-0.5 * ((it - reach).toDouble() / radius).let { t -> t * t }) }
        val total = kernel.sum()
        for (index in kernel.indices) kernel[index] /= total
        val across = DoubleArray(values.size)
        for (row in 0 until height) {
            for (column in 0 until width) {
                var sum = 0.0
                for (offset in -reach..reach) sum += kernel[offset + reach] * values[row * width + (column + offset).mod(width)]
                across[row * width + column] = sum
            }
        }
        val out = DoubleArray(values.size)
        for (row in 0 until height) {
            for (column in 0 until width) {
                var sum = 0.0
                for (offset in -reach..reach) sum += kernel[offset + reach] * across[(row + offset).coerceIn(0, height - 1) * width + column]
                out[row * width + column] = sum
            }
        }
        return out
    }

    /** Zhang and Suen's thinning, the map's edges outside it (`ranges.zhang_suen`, which pads with nothing and does not wrap). */
    fun zhangSuen(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val m = mask.copyOf()
        fun at(row: Int, column: Int): Int = if (row < 0 || row >= height || column < 0 || column >= width) 0 else if (m[row * width + column]) 1 else 0
        var changed = true
        val remove = ArrayList<Int>()
        while (changed) {
            changed = false
            for (sub in 0..1) {
                remove.clear()
                for (row in 0 until height) {
                    for (column in 0 until width) {
                        if (!m[row * width + column]) continue
                        val p2 = at(row - 1, column); val p3 = at(row - 1, column + 1); val p4 = at(row, column + 1)
                        val p5 = at(row + 1, column + 1); val p6 = at(row + 1, column); val p7 = at(row + 1, column - 1)
                        val p8 = at(row, column - 1); val p9 = at(row - 1, column - 1)
                        val ring = intArrayOf(p2, p3, p4, p5, p6, p7, p8, p9, p2)
                        val b = p2 + p3 + p4 + p5 + p6 + p7 + p8 + p9
                        var a = 0
                        for (k in 0 until 8) if (ring[k] == 0 && ring[k + 1] == 1) a++
                        val c = if (sub == 0) (p2 * p4 * p6 == 0) && (p4 * p6 * p8 == 0) else (p2 * p4 * p8 == 0) && (p2 * p6 * p8 == 0)
                        if (b in 2..6 && a == 1 && c) remove.add(row * width + column)
                    }
                }
                if (remove.isNotEmpty()) {
                    for (cell in remove) m[cell] = false
                    changed = true
                }
            }
        }
        return m
    }

    /** 8-connected pieces of [mask], wrapping east-west, each its cells in ascending order; the pieces in the order of their first cell. */
    fun components(mask: BooleanArray, width: Int, height: Int): List<IntArray> {
        val label = IntArray(mask.size) { -1 }
        val pieces = ArrayList<IntArray>()
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || label[start] >= 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = pieces.size
            while (head < tail) {
                val cell = queue[head++]
                val row = cell / width
                val column = cell % width
                for (rowStep in -1..1) {
                    val next = row + rowStep
                    if (next < 0 || next >= height) continue
                    for (columnStep in -1..1) {
                        if (rowStep == 0 && columnStep == 0) continue
                        val neighbor = next * width + (column + columnStep).mod(width)
                        if (mask[neighbor] && label[neighbor] < 0) {
                            label[neighbor] = pieces.size
                            queue[tail++] = neighbor
                        }
                    }
                }
            }
            pieces.add(queue.copyOfRange(0, tail).also { it.sort() })
        }
        return pieces
    }

    /** `ranges.longest_path`: a breadth-first walk from the first cell, again from the farthest it reached; the path to the farthest of that. */
    fun longestPath(cells: IntArray, width: Int, height: Int, cellWidthKm: Double, cellHeightKm: Double): Pair<IntArray, Double> {
        val index = HashMap<Int, Int>(cells.size * 2)
        cells.forEachIndexed { k, cell -> index[cell] = k }
        fun walk(source: Int): Pair<DoubleArray, IntArray> {
            val distance = DoubleArray(cells.size) { Double.POSITIVE_INFINITY }
            val previous = IntArray(cells.size) { -1 }
            distance[source] = 0.0
            val queue = ArrayDeque<Int>()
            queue.addLast(source)
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst()
                val row = cells[k] / width
                val column = cells[k] % width
                for (rowStep in -1..1) {
                    for (columnStep in -1..1) {
                        if (rowStep == 0 && columnStep == 0) continue
                        val next = row + rowStep
                        if (next < 0 || next >= height) continue
                        val j = index[next * width + (column + columnStep).mod(width)] ?: continue
                        if (distance[j] == Double.POSITIVE_INFINITY) {
                            distance[j] = distance[k] + hypot(columnStep * cellWidthKm, rowStep * cellHeightKm)
                            previous[j] = k
                            queue.addLast(j)
                        }
                    }
                }
            }
            return distance to previous
        }
        fun farthest(distance: DoubleArray): Int {
            var best = 0
            for (k in distance.indices) if (distance[k].isFinite() && distance[k] > (if (distance[best].isFinite()) distance[best] else -1.0)) best = k
            return best
        }
        val (first, _) = walk(0)
        val a = farthest(first)
        val (second, previous) = walk(a)
        val b = farthest(second)
        val path = ArrayList<Int>()
        var k = b
        while (k >= 0) {
            path.add(cells[k])
            k = previous[k]
        }
        path.reverse()
        return path.toIntArray() to second[b]
    }

    // ----------------------------------------------------------------------------------- swath

    /** `strata.swath`: the lines across the axis, a station per axis cell, offsets every cell either way; NaN off the map. */
    fun swath(width: Int, height: Int, cellWidthKm: Double, cellHeightKm: Double, elevation: DoubleArray, land: BooleanArray, axis: IntArray): Array<DoubleArray> {
        val ground = DoubleArray(elevation.size) { if (land[it]) elevation[it] else 0.0 }
        val n = axis.size
        val ys = DoubleArray(n) { (axis[it] / width).toDouble() }
        val xs = DoubleArray(n) { (axis[it] % width).toDouble() }
        // numpy's unwrap of the columns as angles, back to columns.
        val unwrapped = DoubleArray(n)
        unwrapped[0] = xs[0]
        for (i in 1 until n) {
            var step = xs[i] - xs[i - 1]
            if (step > width / 2.0) step -= width
            if (step < -width / 2.0) step += width
            unwrapped[i] = unwrapped[i - 1] + step
        }
        val k = floor(HALF_SWATH_KM / cellWidthKm).toInt()
        val rows = ArrayList<DoubleArray>()
        for (i in 0 until n) {
            val a = max(0, i - 2)
            val b = min(n - 1, i + 2)
            val tx = (unwrapped[b] - unwrapped[a]) * cellWidthKm
            val ty = (ys[b] - ys[a]) * cellHeightKm
            val length = hypot(tx, ty)
            if (length == 0.0) continue
            val nx = -ty / length
            val ny = tx / length
            rows.add(DoubleArray(2 * k + 1) { slot ->
                val j = slot - k
                val px = pythonRound(unwrapped[i] + nx * j).mod(width)
                val py = pythonRound(ys[i] + ny * j * cellWidthKm / cellHeightKm)
                if (py in 0 until height) max(0.0, ground[py * width + px]) else Double.NaN
            })
        }
        return rows.toTypedArray()
    }

    // --------------------------------------------------------------------------------- figures

    fun figures(swath: Array<DoubleArray>, stepKm: Double, stratum: Stratum, axis: IntArray): Range {
        val crest = DoubleArray(swath.size) { row -> swath[row].filter { it.isFinite() }.maxOrNull() ?: Double.NaN }.filter { it.isFinite() }.toDoubleArray()
        val n = crest.size
        val lengthKm = n * stepKm
        val mean = crest.average()
        val std = sqrt(crest.sumOf { (it - mean) * (it - mean) } / n)
        val sorted = crest.sorted()
        val p10 = percentile(sorted, 10.0)
        val p90 = percentile(sorted, 90.0)
        // Passes: the crest's local minima deeper than a quarter under the highest crest within 150 km either side.
        val window = max(1, pythonRound(PASS_WINDOW_KM / stepKm))
        var passes = 0
        for (i in 1 until n - 1) {
            if (crest[i] <= crest[i - 1] && crest[i] <= crest[i + 1]) {
                var left = Double.NEGATIVE_INFINITY
                for (j in max(0, i - window) until i) left = max(left, crest[j])
                var right = Double.NEGATIVE_INFINITY
                for (j in i + 1 until min(n, i + window + 1)) right = max(right, crest[j])
                if (crest[i] < PASS_DEPTH_SHARE * min(left, right)) passes++
            }
        }
        // The strip's mean over its land, along strike.
        val stripMeans = swath.mapNotNull { row ->
            val positive = row.filter { it.isFinite() && it > 0.0 }
            if (positive.isEmpty()) null else positive.average()
        }
        val stripMean = stripMeans.average()
        val stripCv = sqrt(stripMeans.sumOf { (it - stripMean) * (it - stripMean) } / stripMeans.size) / stripMean
        val across = swath.map { rangesAcross(it) }
        // The high ground's runs above 70% of the crest's 90th percentile.
        val runs = ArrayList<Int>()
        var current = 0
        for (value in crest) {
            if (value >= HIGH_RUN_SHARE * p90) current++ else if (current > 0) { runs.add(current); current = 0 }
        }
        if (current > 0) runs.add(current)
        val highRunKm = if (runs.isEmpty()) 0.0 else runs.average() * stepKm
        // The width at half the crest, along strike.
        val widths = swath.map { row ->
            val top = row.filter { it.isFinite() }.maxOrNull() ?: Double.NaN
            row.count { (if (it.isFinite()) it else MISSING_METERS) >= 0.5 * top } * stepKm
        }
        val widthMean = widths.average()
        val widthCv = sqrt(widths.sumOf { (it - widthMean) * (it - widthMean) } / widths.size) / widthMean
        // Massifs: the strip's cells at 75% of the crest's 90th percentile, 8-connected in the strip.
        val massifThreshold = MASSIF_SHARE * p90
        val stations = swath.size
        val offsets = swath[0].size
        val high = BooleanArray(stations * offsets) { val value = swath[it / offsets][it % offsets]; (if (value.isFinite()) value else MISSING_METERS) >= massifThreshold }
        val massifs = stripComponents(high, stations, offsets)
        // The crest zone's dissection: within 80 km of each station's crest, the share under 60% of it.
        val zone = max(1, pythonRound(CREST_ZONE_KM / stepKm))
        var low = 0
        var total = 0
        for (row in swath) {
            val values = DoubleArray(row.size) { if (row[it].isFinite()) row[it] else 0.0 }
            var j = 0
            for (index in 1 until values.size) if (values[index] > values[j]) j = index
            val top = values[j]
            if (top <= 0.0) continue
            val from = max(0, j - zone)
            val to = min(values.size, j + zone + 1)
            for (index in from until to) if (values[index] < CREST_ZONE_LOW_SHARE * top) low++
            total += to - from - 1
        }
        return Range(
            stratum = stratum,
            lengthKm = lengthKm,
            meanM = mean,
            cv = std / mean,
            p90MinusP10M = p90 - p10,
            passesPer1000Km = passes / lengthKm * 1_000.0,
            crestZoneLowShare = low.toDouble() / max(1, total),
            widthCv = widthCv,
            widthMeanKm = widthMean,
            highRunMeanKm = highRunKm,
            massifsPer1000Km = massifs / (crest.size * stepKm) * 1_000.0,
            rangesAcrossMean = across.average(),
            swathMeanCv = stripCv,
            axis = axis
        )
    }

    /** `crest.ranges_across`: crests on a line of at least [PROMINENCE_METERS] over the higher of their two saddles, standing over 200 m. */
    fun rangesAcross(row: DoubleArray): Int {
        val v = DoubleArray(row.size) { if (row[it].isFinite()) row[it] else MISSING_METERS }
        val n = v.size
        var count = 0
        for (i in 0 until n) {
            if ((i > 0 && v[i - 1] > v[i]) || (i < n - 1 && v[i + 1] > v[i]) || v[i] < LOWEST_CREST_METERS) continue
            var lowLeft = v[i]
            var j = i - 1
            while (j >= 0 && v[j] <= v[i]) { lowLeft = min(lowLeft, v[j]); j-- }
            var lowRight = v[i]
            j = i + 1
            while (j < n && v[j] <= v[i]) { lowRight = min(lowRight, v[j]); j++ }
            if (v[i] - max(lowLeft, lowRight) >= PROMINENCE_METERS) count++
        }
        return count
    }

    /** 8-connected pieces of the strip's high cells, not wrapping. */
    private fun stripComponents(mask: BooleanArray, rows: Int, columns: Int): Int {
        val seen = BooleanArray(mask.size)
        var pieces = 0
        val stack = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            pieces++
            var top = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val cell = stack[--top]
                val row = cell / columns
                val column = cell % columns
                for (rowStep in -1..1) for (columnStep in -1..1) {
                    val r = row + rowStep
                    val c = column + columnStep
                    if (r < 0 || r >= rows || c < 0 || c >= columns) continue
                    val next = r * columns + c
                    if (mask[next] && !seen[next]) {
                        seen[next] = true
                        stack[top++] = next
                    }
                }
            }
        }
        return pieces
    }

    /** numpy's percentile, linear between the order statistics. */
    fun percentile(sorted: List<Double>, percent: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val position = percent / 100.0 * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = min(lower + 1, sorted.size - 1)
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun median(sorted: List<Double>): Double = percentile(sorted, 50.0)

    /** Python's `round`, halves to the even neighbor. */
    fun pythonRound(value: Double): Int = Math.rint(value).roundToInt()

    private const val MIN_STATIONS = 10
    private const val PASS_WINDOW_KM = 150.0
    private const val PASS_DEPTH_SHARE = 0.75
    private const val HIGH_RUN_SHARE = 0.7
    private const val MASSIF_SHARE = 0.75
    private const val CREST_ZONE_KM = 80.0
    private const val CREST_ZONE_LOW_SHARE = 0.6
    private const val PROMINENCE_METERS = 500.0
    private const val LOWEST_CREST_METERS = 200.0
    private const val MISSING_METERS = -1e4
}
