package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.PlateResult
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln

/**
 * The instruments the tectonic history's guards read (docs/DESIGN_LEDGER.md, TH1-1): one function
 * per figure, the same arithmetic on any world, each taking the plate stage's result and the land
 * the sea leaves on it.
 *
 * Areas are on the sphere: a cell weighs the cosine of its latitude. Distances are on the map's
 * ground, a cell [WorldGenConfig.cellWidthKm] across at every latitude, as the generator's settings
 * mean them. Coasts and the crust's edge are read only between 60 degrees north and south, where a
 * cell's width on the map is within a factor of two of its width on the ground, as D3 read them.
 */
internal object CrustMeasures {

    /** Where the measures read a line: equatorward of this latitude, degrees. */
    const val MAX_LINE_LATITUDE = 60.0

    /** The land the sea's percentile cut leaves on the plate stage's height: the land before erosion. */
    fun landBeforeErosion(config: WorldGenConfig, plates: PlateResult): BooleanArray =
        SeaLevelStage.percentileCut(plates.height, config.seaLevel, config.scale).isLand

    fun latitudeDegrees(config: WorldGenConfig, row: Int): Double = 90.0 - (row + 0.5) * 180.0 / config.height

    private fun areaWeights(config: WorldGenConfig): DoubleArray =
        DoubleArray(config.height) { cos(latitudeDegrees(config, it) * PI / 180.0) }

    /** Landmasses, 8-connected and wrapping east-west, by their area on the sphere. */
    class Landmasses(
        val largestShare: Double,
        val topThreeShare: Double,
        val overOnePercent: Int,
        val overTenthPercent: Int,
        /** Korčák's exponent of `N(>A) ~ A^-K` over landmasses of 0.01% to 1% of the land; NaN without enough of them. */
        val korcakK: Double
    )

    fun landmasses(config: WorldGenConfig, land: BooleanArray): Landmasses {
        val width = config.width
        val height = config.height
        val weights = areaWeights(config)
        val label = IntArray(width * height) { -1 }
        val sizes = ArrayList<Double>()
        val stack = IntArray(width * height)
        for (start in land.indices) {
            if (!land[start] || label[start] >= 0) continue
            val id = sizes.size
            var top = 0
            stack[top++] = start
            label[start] = id
            var area = 0.0
            while (top > 0) {
                val cell = stack[--top]
                val row = cell / width
                val column = cell % width
                area += weights[row]
                for (rowStep in -1..1) {
                    val next = row + rowStep
                    if (next < 0 || next >= height) continue
                    for (columnStep in -1..1) {
                        val neighbor = next * width + (column + columnStep).mod(width)
                        if (land[neighbor] && label[neighbor] < 0) {
                            label[neighbor] = id
                            stack[top++] = neighbor
                        }
                    }
                }
            }
            sizes.add(area)
        }
        if (sizes.isEmpty()) return Landmasses(0.0, 0.0, 0, 0, Double.NaN)
        sizes.sortDescending()
        val total = sizes.sum()
        val low = 1e-4 * total
        val high = 1e-2 * total
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (point in 0 until KORCAK_POINTS) {
            val area = low * Math.pow(high / low, point / (KORCAK_POINTS - 1.0))
            val count = sizes.count { it >= area }
            if (count > 0) {
                xs.add(ln(area))
                ys.add(ln(count.toDouble()))
            }
        }
        val korcak = if (xs.size >= 2 && ys.first() > ys.last()) -slope(xs, ys) else Double.NaN
        return Landmasses(
            largestShare = sizes[0] / total,
            topThreeShare = sizes.take(3).sum() / total,
            overOnePercent = sizes.count { it >= 0.01 * total },
            overTenthPercent = sizes.count { it >= 0.001 * total },
            korcakK = korcak
        )
    }

    private fun slope(xs: List<Double>, ys: List<Double>): Double {
        val n = xs.size
        val meanX = xs.average()
        val meanY = ys.average()
        var top = 0.0
        var bottom = 0.0
        for (index in 0 until n) {
            top += (xs[index] - meanX) * (ys[index] - meanY)
            bottom += (xs[index] - meanX) * (xs[index] - meanX)
        }
        return top / bottom
    }

    /** Each plate's share of the sphere, largest first. */
    fun plateShares(config: WorldGenConfig, plates: PlateResult): List<Double> {
        val weights = areaWeights(config)
        val areas = DoubleArray(plates.plates.size)
        for (cell in plates.plateId.indices) areas[plates.plateId[cell]] += weights[cell / config.width]
        val total = areas.sum()
        return areas.filter { it > 0.0 }.map { it / total }.sortedDescending()
    }

    /**
     * Land over the area of the plates that carry it — every plate holding at least 1% land —
     * on the sphere: Earth's is 0.41 (Bird 2003's thirteen land-bearing plates against Earth's
     * land).
     */
    fun landShareOfItsPlates(config: WorldGenConfig, plates: PlateResult, land: BooleanArray): Double {
        val weights = areaWeights(config)
        val plateArea = DoubleArray(plates.plates.size)
        val plateLand = DoubleArray(plates.plates.size)
        for (cell in land.indices) {
            val weight = weights[cell / config.width]
            plateArea[plates.plateId[cell]] += weight
            if (land[cell]) plateLand[plates.plateId[cell]] += weight
        }
        var landSum = 0.0
        var areaSum = 0.0
        for (plate in plateArea.indices) {
            if (plateLand[plate] > LAND_BEARING_SHARE * plateArea[plate]) {
                landSum += plateLand[plate]
                areaSum += plateArea[plate]
            }
        }
        return if (areaSum > 0.0) landSum / areaSum else 0.0
    }

    /** The crust's edge: continental cells at a share of a half beside an oceanic one, equatorward of [MAX_LINE_LATITUDE]. */
    fun crustEdgeCells(config: WorldGenConfig, plates: PlateResult): List<Int> {
        val width = config.width
        val share = plates.continentalShare.data
        val cells = ArrayList<Int>()
        for (cell in share.indices) {
            if (share[cell] < 0.5f) continue
            val row = cell / width
            if (abs(latitudeDegrees(config, row)) >= MAX_LINE_LATITUDE) continue
            val column = cell % width
            val beside = intArrayOf(
                row * width + (column + 1) % width, row * width + (column + width - 1) % width,
                if (row > 0) cell - width else cell, if (row + 1 < config.height) cell + width else cell
            )
            if (beside.any { share[it] < 0.5f }) cells.add(cell)
        }
        return cells
    }

    /** The share of [cells] farther than [km] from any plate boundary. */
    fun shareFartherThan(config: WorldGenConfig, plates: PlateResult, cells: List<Int>, km: Double): Double {
        if (cells.isEmpty()) return Double.NaN
        val cellWidthKm = config.cellWidthKm
        return cells.count { plates.boundaryDistance.data[it] * cellWidthKm > km }.toDouble() / cells.size
    }

    /** Each boundary class's share of the plate boundaries' length, read as the boundary cells of each class. */
    fun boundaryClassShares(plates: PlateResult): DoubleArray {
        val counts = IntArray(BoundaryClass.entries.size)
        var all = 0
        for (cell in plates.plateId.indices) {
            if (plates.boundaryDistance.data[cell] > 0f) continue
            val type = plates.nearestBoundaryClass[cell]
            if (type < 0) continue
            counts[type]++
            all++
        }
        return DoubleArray(counts.size) { if (all == 0) 0.0 else counts[it].toDouble() / all }
    }

    /** The ocean floor's ages, area-weighted, over the cells mostly oceanic crust: mean, and the shares older than 100 and 150 Myr. */
    class FloorAges(val meanMyr: Double, val olderThan100Share: Double, val olderThan150Share: Double, val oldestMyr: Double)

    fun floorAges(config: WorldGenConfig, plates: PlateResult): FloorAges {
        val weights = areaWeights(config)
        var weight = 0.0
        var sum = 0.0
        var over100 = 0.0
        var over150 = 0.0
        var oldest = 0.0
        for (cell in plates.seafloorAgeMyr.data.indices) {
            if (plates.continentalShare.data[cell] >= 0.5f) continue
            val w = weights[cell / config.width]
            val age = plates.seafloorAgeMyr.data[cell].toDouble()
            weight += w
            sum += w * age
            if (age > 100.0) over100 += w
            if (age > 150.0) over150 += w
            oldest = maxOf(oldest, age)
        }
        return FloorAges(sum / weight, over100 / weight, over150 / weight, oldest)
    }

    /**
     * A line on the map: the boundary of a mask, traced as polylines of cell edges in kilometers,
     * unwrapped east-west, the mask on the right. D3's tracing, by which the crust's edge was found
     * to be the plates' polygons.
     */
    class Lines(val polylines: List<Pair<DoubleArray, DoubleArray>>, val worldWidthKm: Double, val cellHeightKm: Double, val rows: Int)

    fun traceLines(config: WorldGenConfig, mask: BooleanArray): Lines {
        val width = config.width
        val height = config.height
        val cellWidthKm = config.cellWidthKm
        val cellHeightKm = config.cellHeightKm
        val corners = (height + 1) * width
        val firstOut = IntArray(corners) { -1 }
        val secondOut = IntArray(corners) { -1 }
        val from = ArrayList<Int>()
        val to = ArrayList<Int>()
        val artificial = ArrayList<Boolean>()
        val side = ArrayList<Int>()
        fun addEdge(a: Int, b: Int, isArtificial: Boolean, direction: Int) {
            val id = from.size
            from.add(a); to.add(b); artificial.add(isArtificial); side.add(direction)
            if (firstOut[a] < 0) firstOut[a] = id else secondOut[a] = id
        }
        for (row in 0 until height) for (column in 0 until width) {
            val cell = row * width + column
            if (!mask[cell]) continue
            val right = (column + 1) % width
            val topLeft = row * width + column
            val topRight = row * width + right
            val bottomRight = (row + 1) * width + right
            val bottomLeft = (row + 1) * width + column
            if (row == 0 || !mask[cell - width]) addEdge(topLeft, topRight, row == 0, 0)
            if (!mask[row * width + right]) addEdge(topRight, bottomRight, false, 1)
            if (row == height - 1 || !mask[cell + width]) addEdge(bottomRight, bottomLeft, row == height - 1, 2)
            if (!mask[row * width + (column + width - 1) % width]) addEdge(bottomLeft, topLeft, false, 3)
        }
        val worldWidthKm = width * cellWidthKm
        fun cornerKm(corner: Int): Pair<Double, Double> = Pair((corner % width) * cellWidthKm, (corner / width) * cellHeightKm)
        val used = BooleanArray(from.size)
        val polylines = ArrayList<Pair<DoubleArray, DoubleArray>>()
        for (start in from.indices) {
            if (used[start]) continue
            val loop = ArrayList<Int>()
            var edge = start
            while (!used[edge]) {
                used[edge] = true
                loop.add(edge)
                val end = to[edge]
                val first = firstOut[end]
                val second = secondOut[end]
                val next = if (second < 0) first else {
                    val wanted = (side[edge] + 1) % 4
                    when {
                        !used[first] && side[first] == wanted -> first
                        !used[second] && side[second] == wanted -> second
                        !used[first] -> first
                        else -> second
                    }
                }
                if (next < 0) break
                edge = next
            }
            // Split the loop at the poles' artificial edges into open runs of real ones.
            val firstArtificial = loop.indexOfFirst { artificial[it] }
            val begin = if (firstArtificial < 0) 0 else firstArtificial
            var current = ArrayList<Int>()
            val pieces = ArrayList<List<Int>>()
            for (step in loop.indices) {
                val id = loop[(begin + step) % loop.size]
                if (artificial[id]) {
                    if (current.isNotEmpty()) { pieces.add(current); current = ArrayList() }
                } else current.add(id)
            }
            if (current.isNotEmpty()) pieces.add(current)
            for (piece in pieces) {
                val xs = DoubleArray(piece.size + 1)
                val ys = DoubleArray(piece.size + 1)
                val (startX, startY) = cornerKm(from[piece[0]])
                xs[0] = startX; ys[0] = startY
                for (index in piece.indices) {
                    val (x, y) = cornerKm(to[piece[index]])
                    var step = x - (xs[index].mod(worldWidthKm))
                    if (step > worldWidthKm / 2) step -= worldWidthKm
                    if (step < -worldWidthKm / 2) step += worldWidthKm
                    xs[index + 1] = xs[index] + step
                    ys[index + 1] = y
                }
                polylines.add(xs to ys)
            }
        }
        return Lines(polylines, worldWidthKm, cellHeightKm, height)
    }

    /** Points every [stepKm] along a polyline. */
    private fun resample(xs: DoubleArray, ys: DoubleArray, stepKm: Double): Pair<DoubleArray, DoubleArray> {
        val outX = ArrayList<Double>()
        val outY = ArrayList<Double>()
        outX.add(xs[0]); outY.add(ys[0])
        var carry = 0.0
        for (index in 1 until xs.size) {
            val dx = xs[index] - xs[index - 1]
            val dy = ys[index] - ys[index - 1]
            val length = hypot(dx, dy)
            var along = stepKm - carry
            while (along <= length) {
                outX.add(xs[index - 1] + dx * along / length)
                outY.add(ys[index - 1] + dy * along / length)
                along += stepKm
            }
            carry = length - (along - stepKm)
        }
        return outX.toDoubleArray() to outY.toDoubleArray()
    }

    private fun Lines.latitudeAtKm(yKm: Double): Double = 90.0 - yKm / (rows * cellHeightKm) * 180.0

    /**
     * The share of windows a hundredth of the map's width long whose two halves turn by more than
     * 45 degrees: D3's sharp corners at map scale. A polygon of plates reads near nothing; a natural
     * outline, which turns at every scale, near half.
     */
    fun cornerShare(lines: Lines): Double = turningShare(lines, CORNER_WINDOW_SHARE, CORNER_DEGREES, sharper = true)

    /**
     * The share of windows a tenth of the map's width long whose two halves turn by under 10
     * degrees: D3's straight runs at the scale a reader sees a continent's side.
     */
    fun straightShare(lines: Lines): Double = turningShare(lines, STRAIGHT_WINDOW_SHARE, STRAIGHT_DEGREES, sharper = false)

    private fun turningShare(lines: Lines, windowShare: Double, degrees: Double, sharper: Boolean): Double {
        val stepKm = lines.worldWidthKm / RESAMPLE_DIVISIONS
        val window = (lines.worldWidthKm * windowShare / stepKm).toInt()
        val half = window / 2
        var windows = 0L
        var counted = 0L
        for ((xs, ys) in lines.polylines) {
            val (px, py) = resample(xs, ys, stepKm)
            if (px.size <= window) continue
            var start = 0
            while (start + window < px.size) {
                if (abs(lines.latitudeAtKm(py[start + half])) < MAX_LINE_LATITUDE) {
                    val chord = hypot(px[start + window] - px[start], py[start + window] - py[start])
                    if (sharper || chord >= 0.5 * window * stepKm) {
                        val first = atan2(py[start + half] - py[start], px[start + half] - px[start])
                        val second = atan2(py[start + window] - py[start + half], px[start + window] - px[start + half])
                        var turn = abs(second - first)
                        if (turn > PI) turn = 2 * PI - turn
                        windows++
                        val threshold = degrees * PI / 180.0
                        if (if (sharper) turn > threshold else turn < threshold) counted++
                    }
                }
                start += maxOf(1, window / if (sharper) 4 else 8)
            }
        }
        return if (windows == 0L) Double.NaN else counted.toDouble() / windows
    }

    /**
     * The box-counting dimension of a line at map scale, over boxes a 256th to a 32nd of the map's
     * width, counted on the midpoints of its cell edges equatorward of [MAX_LINE_LATITUDE].
     */
    fun boxDimension(lines: Lines): Double {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for ((lx, ly) in lines.polylines) {
            for (index in 1 until lx.size) {
                val y = (ly[index] + ly[index - 1]) / 2
                if (abs(lines.latitudeAtKm(y)) >= MAX_LINE_LATITUDE) continue
                xs.add(((lx[index] + lx[index - 1]) / 2).mod(lines.worldWidthKm))
                ys.add(y)
            }
        }
        if (xs.isEmpty()) return Double.NaN
        val logSize = ArrayList<Double>()
        val logCount = ArrayList<Double>()
        var size = lines.worldWidthKm / 256
        while (size <= lines.worldWidthKm / 32 * 1.01) {
            val columns = (lines.worldWidthKm / size).toLong() + 1
            val boxes = HashSet<Long>()
            for (index in xs.indices) boxes.add(floor(ys[index] / size).toLong() * columns + floor(xs[index] / size).toLong())
            logSize.add(ln(1.0 / size))
            logCount.add(ln(boxes.size.toDouble()))
            size *= kotlin.math.sqrt(2.0)
        }
        return slope(logSize, logCount)
    }

    /**
     * A configuration category from the largest landmass's share of the land, defined before the
     * ensemble was run: assembled where one landmass holds three quarters of the land or more, as
     * Pangea did nearly all of it; highly dispersed under 0.39, below Earth's 0.57 by as much
     * as assembled is above it; intermediate between, where Earth stands today.
     */
    enum class Phase { ASSEMBLED, INTERMEDIATE, DISPERSED }

    fun phaseOf(largestShare: Double): Phase = when {
        largestShare >= ASSEMBLED_LARGEST_SHARE -> Phase.ASSEMBLED
        largestShare < DISPERSED_LARGEST_SHARE -> Phase.DISPERSED
        else -> Phase.INTERMEDIATE
    }

    const val ASSEMBLED_LARGEST_SHARE = 0.75
    const val DISPERSED_LARGEST_SHARE = 0.39

    /** A plate holding more land than this share of itself is one of the plates that carry land. */
    private const val LAND_BEARING_SHARE = 0.01

    private const val KORCAK_POINTS = 8
    private const val RESAMPLE_DIVISIONS = 2048.0
    private const val CORNER_WINDOW_SHARE = 1.0 / 100
    private const val CORNER_DEGREES = 45.0
    private const val STRAIGHT_WINDOW_SHARE = 1.0 / 10
    private const val STRAIGHT_DEGREES = 10.0
}
