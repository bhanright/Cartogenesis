package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.LongMinHeap
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Points spread evenly over a sphere, each standing for an equal area of it, with the means to ask
 * which is nearest a place and which surround each one.
 *
 * The tectonic history's grid. A latitude-longitude grid crowds its cells toward the poles, so a
 * flood across it measures distance on the map rather than on the planet and a plate near a pole
 * is carried on more cells than one at the equator. Fibonacci's spiral lattice (González,
 * *Measurement of areas on a sphere using Fibonacci and latitude-longitude lattices*, Math. Geosci.
 * 42, 2010; Cortial et al. 2019 sample their crust with it) puts point `i` of `N` at
 * `z = 1 - (2i + 1) / N` and turns it by the golden angle from the last, so every point holds the
 * same band of `z`, which on a sphere is the same area, and no direction is preferred.
 *
 * The cell is set in kilometers on the ground ([forCellKm]), not as a count, so a planet of another
 * radius carries the same physics on more or fewer points.
 *
 * Points are numbered from the north pole to the south. [x], [y], [z] are the unit vectors, with
 * `z` toward the north pole and longitude zero along `x`, longitude growing east, as the map's
 * columns do.
 */
internal class FibonacciSphere(val cellCount: Int, val radiusKm: Double) {

    val x = DoubleArray(cellCount)
    val y = DoubleArray(cellCount)
    val z = DoubleArray(cellCount)

    /** Every point's share of the sphere, in square kilometers: all equal. */
    val cellAreaKm2: Double = 4.0 * PI * radiusKm * radiusKm / cellCount

    /** The side of a square of [cellAreaKm2], the lattice's spacing on the ground. */
    val spacingKm: Double = sqrt(cellAreaKm2)

    /** [spacingKm] as an angle at the center, radians. */
    val spacingRadians: Double = spacingKm / radiusKm

    init {
        for (point in 0 until cellCount) {
            val height = 1.0 - (2.0 * point + 1.0) / cellCount
            val ring = sqrt(max(0.0, 1.0 - height * height))
            val turn = GOLDEN_ANGLE * point
            x[point] = ring * cos(turn)
            y[point] = ring * sin(turn)
            z[point] = height
        }
    }

    // Latitude bands, each [spacingRadians] tall, each cut into longitude bins at least as wide on
    // the ground as the band is tall, so that a search of one spacing touches the bins either side
    // of the one a place falls in and no more.
    @PublishedApi internal val bandCount = max(1, (PI / spacingRadians).toInt())
    @PublishedApi internal val bandHeightRadians = PI / bandCount
    @PublishedApi internal val binsInBand = IntArray(bandCount)
    @PublishedApi internal val firstBinOfBand = IntArray(bandCount + 1)
    @PublishedApi internal val binStart: IntArray
    @PublishedApi internal val binPoints: IntArray

    init {
        for (band in 0 until bandCount) {
            // The band's poleward edge is where its longitude bins are narrowest on the ground.
            val polewardColatitude = min(band, bandCount - 1 - band) * bandHeightRadians
            val polewardCosine = sin(polewardColatitude)
            binsInBand[band] = max(1, floor(2.0 * PI * polewardCosine / bandHeightRadians).toInt())
            firstBinOfBand[band + 1] = firstBinOfBand[band] + binsInBand[band]
        }
        val binCount = firstBinOfBand[bandCount]
        val binOf = IntArray(cellCount) { binAt(x[it], y[it], z[it]) }
        binStart = IntArray(binCount + 1)
        for (point in 0 until cellCount) binStart[binOf[point] + 1]++
        for (bin in 0 until binCount) binStart[bin + 1] += binStart[bin]
        val cursor = binStart.copyOf()
        binPoints = IntArray(cellCount)
        for (point in 0 until cellCount) binPoints[cursor[binOf[point]]++] = point
    }

    private fun bandOf(unitZ: Double): Int {
        val colatitude = acos(unitZ.coerceIn(-1.0, 1.0))
        return (colatitude / bandHeightRadians).toInt().coerceIn(0, bandCount - 1)
    }

    private fun binAt(unitX: Double, unitY: Double, unitZ: Double): Int {
        val band = bandOf(unitZ)
        var longitude = atan2(unitY, unitX)
        if (longitude < 0.0) longitude += 2.0 * PI
        val bin = (longitude / (2.0 * PI) * binsInBand[band]).toInt().coerceIn(0, binsInBand[band] - 1)
        return firstBinOfBand[band] + bin
    }

    /**
     * Calls [visit] with every point within [radiusRadians] of the unit vector ([unitX], [unitY],
     * [unitZ]) and the cosine of its angle from it — every one, by searching every bin the cap can
     * touch — in an order fixed by the lattice.
     */
    inline fun forEachWithin(
        unitX: Double,
        unitY: Double,
        unitZ: Double,
        radiusRadians: Double,
        visit: (point: Int, cosine: Double) -> Unit
    ) {
        val minimumCosine = cos(radiusRadians)
        forEachCandidate(unitX, unitY, unitZ, radiusRadians) { point ->
            val cosine = x[point] * unitX + y[point] * unitY + z[point] * unitZ
            if (cosine >= minimumCosine) visit(point, cosine)
        }
    }

    /** Every point in the bins a cap of [radiusRadians] about the place can touch; a superset of the cap. */
    @PublishedApi
    internal inline fun forEachCandidate(
        unitX: Double,
        unitY: Double,
        unitZ: Double,
        radiusRadians: Double,
        visit: (point: Int) -> Unit
    ) {
        val colatitude = acos(unitZ.coerceIn(-1.0, 1.0))
        val firstBand = ((colatitude - radiusRadians) / bandHeightRadians).toInt().coerceIn(0, bandCount - 1)
        val lastBand = ((colatitude + radiusRadians) / bandHeightRadians).toInt().coerceIn(0, bandCount - 1)
        var longitude = atan2(unitY, unitX)
        if (longitude < 0.0) longitude += 2.0 * PI
        // A cap of angular radius r about colatitude t spans asin(sin r / sin t) of longitude either
        // side of its center; one that reaches a pole spans the whole ring.
        val centerSine = sin(colatitude)
        val radiusSine = sin(radiusRadians)
        val wholeRing = colatitude - radiusRadians <= 0.0 || colatitude + radiusRadians >= PI || centerSine <= radiusSine
        val halfWidthRadians = if (wholeRing) PI else asin(radiusSine / centerSine)
        for (band in firstBand..lastBand) {
            val bins = binsInBand[band]
            val first = firstBinOfBand[band]
            if (wholeRing || bins <= 3) {
                for (point in binStart[first] until binStart[first + bins]) visit(binPoints[point])
                continue
            }
            val binWidth = 2.0 * PI / bins
            val lowBin = floor((longitude - halfWidthRadians) / binWidth).toInt()
            val highBin = floor((longitude + halfWidthRadians) / binWidth).toInt()
            if (highBin - lowBin + 1 >= bins) {
                for (point in binStart[first] until binStart[first + bins]) visit(binPoints[point])
                continue
            }
            for (unwrapped in lowBin..highBin) {
                val bin = first + unwrapped.mod(bins)
                for (point in binStart[bin] until binStart[bin + 1]) visit(binPoints[point])
            }
        }
    }

    /**
     * The point nearest the unit vector given: the one at the smallest angle from it, ties to the
     * lower index. Searched within twice the spacing first, which always holds the nearest on a
     * Fibonacci lattice (its covering radius is under one spacing; `FibonacciSphereTest`), then the
     * whole sphere if that ever comes back empty.
     */
    fun nearest(unitX: Double, unitY: Double, unitZ: Double): Int {
        var best = -1
        var bestCosine = -2.0
        forEachCandidate(unitX, unitY, unitZ, NEAREST_SEARCH_SPACINGS * spacingRadians) { point ->
            val cosine = x[point] * unitX + y[point] * unitY + z[point] * unitZ
            if (cosine > bestCosine || (cosine == bestCosine && point < best)) {
                bestCosine = cosine
                best = point
            }
        }
        if (best >= 0) return best
        for (point in 0 until cellCount) {
            val cosine = x[point] * unitX + y[point] * unitY + z[point] * unitZ
            if (cosine > bestCosine) {
                bestCosine = cosine
                best = point
            }
        }
        return best
    }

    /**
     * Each point's neighbors, compressed: point `p`'s are `neighbors[neighborStart[p] until
     * neighborStart[p + 1]]`, nearest first. The points within [NEIGHBOR_REACH_SPACINGS] spacings,
     * the nearest [MAX_NEIGHBORS] of them, then made symmetric, so that a flood or a diffusion
     * across them moves as much one way as the other.
     */
    val neighborStart: IntArray
    val neighbors: IntArray

    init {
        val lists = Array(cellCount) { IntArrayList() }
        val reach = NEIGHBOR_REACH_SPACINGS * spacingRadians
        val candidates = ArrayList<Long>()
        for (point in 0 until cellCount) {
            candidates.clear()
            forEachWithin(x[point], y[point], z[point], reach) { other, cosine ->
                if (other != point) {
                    // Sorted by angle and then index: the angle's bits order as the angle does.
                    val angleBits = acos(cosine.coerceIn(-1.0, 1.0)).toFloat().toRawBits().toLong()
                    candidates.add((angleBits shl 32) or other.toLong())
                }
            }
            candidates.sort()
            for (index in 0 until min(MAX_NEIGHBORS, candidates.size)) {
                lists[point].add((candidates[index] and 0xFFFFFFFFL).toInt())
            }
        }
        // Symmetric: a point another counts among its neighbors counts that one among its own.
        for (point in 0 until cellCount) {
            val own = lists[point]
            for (index in 0 until own.size) {
                val other = own[index]
                if (!lists[other].contains(point)) lists[other].add(point)
            }
        }
        neighborStart = IntArray(cellCount + 1)
        for (point in 0 until cellCount) neighborStart[point + 1] = neighborStart[point] + lists[point].size
        neighbors = IntArray(neighborStart[cellCount])
        for (point in 0 until cellCount) {
            val own = lists[point]
            // Nearest first, ties to the lower index, whichever way round the list was built.
            val sorted = (0 until own.size).map { own[it] }.sortedWith(
                compareBy({ angleBetween(point, it) }, { it })
            )
            sorted.forEachIndexed { index, other -> neighbors[neighborStart[point] + index] = other }
        }
    }

    /** The angle between two points at the sphere's center, radians. */
    fun angleBetween(first: Int, second: Int): Double =
        acos((x[first] * x[second] + y[first] * y[second] + z[first] * z[second]).coerceIn(-1.0, 1.0))

    /** The great-circle distance between two points, km. */
    fun distanceKm(first: Int, second: Int): Double = angleBetween(first, second) * radiusKm

    /** A point's latitude, radians, north positive. */
    fun latitudeRadians(point: Int): Double = asin(z[point].coerceIn(-1.0, 1.0))

    /** A point's longitude, radians, 0 to 2 pi east of the map's western edge. */
    fun longitudeRadians(point: Int): Double {
        val longitude = atan2(y[point], x[point])
        return if (longitude < 0.0) longitude + 2.0 * PI else longitude
    }

    /**
     * The great-circle distance from every point to the nearest of [sources], km, and which source
     * that is, flooded across the neighbors: [JumpFloodDistance]'s idea on this lattice, carrying a
     * source and measuring the exact angle to it rather than adding up steps, so the answer has no
     * metric error of the lattice's own, only the rare point that never hears of its nearest source.
     *
     * [allowed], where given, limits the flood to the points it marks, and [region], where given,
     * to the points of the same region as the source that reaches them, so a distance inside one
     * plate does not leak across another. Points the flood never reaches keep [Double.MAX_VALUE]
     * and -1. Sources are taken in ascending order and ties go to the lower source, so the answer
     * does not depend on how the caller listed them.
     */
    fun distanceFlood(
        sources: IntArray,
        allowed: BooleanArray? = null,
        reachKm: Double = Double.MAX_VALUE,
        region: IntArray? = null
    ): Pair<DoubleArray, IntArray> {
        val distanceKm = DoubleArray(cellCount) { Double.MAX_VALUE }
        val source = IntArray(cellCount) { -1 }
        val heap = LongMinHeap(max(16, sources.size * 2))
        for (start in sources.sorted()) {
            if (source[start] >= 0) continue
            distanceKm[start] = 0.0
            source[start] = start
            heap.push(floodKey(0.0, start))
        }
        while (!heap.isEmpty()) {
            val key = heap.pop()
            val point = (key and 0xFFFFFFFFL).toInt()
            val from = source[point]
            for (index in neighborStart[point] until neighborStart[point + 1]) {
                val neighbor = neighbors[index]
                if (allowed != null && !allowed[neighbor]) continue
                if (region != null && region[neighbor] != region[from]) continue
                val throughKm = angleBetween(from, neighbor) * radiusKm
                if (throughKm > reachKm) continue
                if (throughKm < distanceKm[neighbor] || (throughKm == distanceKm[neighbor] && from < source[neighbor])) {
                    distanceKm[neighbor] = throughKm
                    source[neighbor] = from
                    heap.push(floodKey(throughKm, neighbor))
                }
            }
        }
        return distanceKm to source
    }

    private fun floodKey(distanceKm: Double, point: Int): Long =
        (distanceKm.toFloat().toRawBits().toLong() shl 32) or point.toLong()

    /** A growable list of ints, so the neighbor tables are built without boxing. */
    private class IntArrayList {
        private var values = IntArray(8)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        operator fun get(index: Int): Int = values[index]

        fun contains(value: Int): Boolean {
            for (index in 0 until size) if (values[index] == value) return true
            return false
        }
    }

    companion object {
        /** The golden angle, `pi (3 - sqrt 5)`, by which each point turns from the last. */
        val GOLDEN_ANGLE = PI * (3.0 - sqrt(5.0))

        /**
         * How far [nearest] first looks, in spacings: twice the lattice's spacing, against a
         * covering radius under one (`FibonacciSphereTest` measures it), so the first search always
         * holds the nearest point.
         */
        const val NEAREST_SEARCH_SPACINGS = 2.0

        /**
         * How far a point's neighbors may lie, in spacings, and how many it keeps: a point of a
         * near-hexagonal lattice has six at about 1.07 spacings, and the spiral's irregularity puts
         * a seventh or eighth near 1.3, so 1.6 holds every Delaunay neighbor and eight keeps them.
         */
        const val NEIGHBOR_REACH_SPACINGS = 1.6
        const val MAX_NEIGHBORS = 8

        /**
         * A lattice whose cells are [cellKm] on a side on a sphere of [radiusKm]. The last one made is
         * kept and handed out again for the same two figures: a lattice is never written after it is
         * built, and building one, its neighbors above all, is a second of the history's time.
         */
        fun forCellKm(radiusKm: Double, cellKm: Double): FibonacciSphere {
            val count = (4.0 * PI * radiusKm * radiusKm / (cellKm * cellKm)).roundToInt().coerceAtLeast(MIN_CELLS)
            lastBuilt?.let { if (it.cellCount == count && it.radiusKm == radiusKm) return it }
            return FibonacciSphere(count, radiusKm).also { lastBuilt = it }
        }

        /** The lattice [forCellKm] last built; a race between two threads only builds it twice. */
        @kotlin.concurrent.Volatile
        private var lastBuilt: FibonacciSphere? = null

        /** The fewest points a history lattice may have, so a tiny test planet still has plates. */
        const val MIN_CELLS = 2_000
    }
}
