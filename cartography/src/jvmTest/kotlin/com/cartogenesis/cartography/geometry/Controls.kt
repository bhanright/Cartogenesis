package com.cartogenesis.cartography.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A field with no preferred bearing, defined everywhere on the plane rather than on a grid.
 *
 * A sum of plane waves, [WAVES_PER_OCTAVE] to each octave of wavelength between
 * [shortestWavelength] and [longestWavelength] with their directions spread evenly round the
 * compass and their wavelengths drawn uniformly in their logarithm within the octave, each
 * with an amplitude of `wavelength ^ HURST`. That is a random-phase spectral synthesis of fractional Brownian relief with Hurst exponent [HURST], and a level line
 * of such relief has fractal dimension `2 - H`: at 0.75 that is 1.25, which is Richardson's west
 * coast of Britain as Mandelbrot (1967) reports it, the figure `CoastRoughness` already holds the
 * generator's coasts to. So its thresholded outlines are the natural shapes the guard's bars are
 * measured on.
 *
 * Nothing about it knows a grid: it is evaluated at whatever points it is asked about, so a grid
 * that samples it samples a shape that owes the grid nothing, and turning [rotationDegrees] turns
 * the whole shape rigidly. G1's point that a thresholded noise *generated on this grid* is not
 * automatically isotropic is why it is generated here and not by the terrain stage's own noise;
 * `GeometryControlTest` shows its isotropy directly, from the gradient directions at its level
 * line sampled at random points of the plane, before any detector reads it.
 *
 * [unitsAcross] and [unitsDown] say what one unit of the plane is: kilometres for a shape
 * isotropic on the ground (1, 1), or cells for one isotropic on the sheet (a cell's width and
 * height, so that it is round in cells and twice as tall as wide on the ground). The generator's
 * own land is the second kind (docs/GEOGRAPHY.md, on the outlines' cell-space anisotropy), so the
 * guard's bars are shown to hold on both.
 */
internal class IsotropicNoise(
    seed: Long,
    private val shortestWavelength: Double,
    private val longestWavelength: Double,
    rotationDegrees: Double = 0.0,
    private val unitsAcross: Double = 1.0,
    private val unitsDown: Double = 1.0
) {
    // A band of wavelengths an octave wide at a time, counted down from the longest, and within
    // each band the directions spread evenly round the half-circle with one random offset: drawn
    // independently, the handful of shortest waves that dominate the gradient would each be a
    // direction of their own. Each octave draws from its own stream, so asking for finer detail
    // adds octaves and leaves the coarser ones — the shape at large — exactly as they were.
    private val octaves = maxOf(1, kotlin.math.ceil(ln(longestWavelength / shortestWavelength) / ln(2.0)).toInt())
    private val waves = octaves * WAVES_PER_OCTAVE
    private val waveX = DoubleArray(waves)
    private val waveY = DoubleArray(waves)
    private val phase = DoubleArray(waves)
    private val amplitude = DoubleArray(waves)

    init {
        val rotation = Math.toRadians(rotationDegrees)
        var power = 0.0
        var random = Random(seed)
        var bandOffset = 0.0
        for (wave in 0 until waves) {
            val octave = wave / WAVES_PER_OCTAVE
            val slot = wave % WAVES_PER_OCTAVE
            if (slot == 0) {
                random = Random(seed * OCTAVE_STREAM_STRIDE + octave)
                bandOffset = random.nextDouble()
            }
            val direction = (slot + bandOffset) / WAVES_PER_OCTAVE * PI + rotation
            val top = ln(longestWavelength) - octave * ln(2.0)
            val bottom = maxOf(top - ln(2.0), ln(shortestWavelength))
            val logWavelength = bottom + random.nextDouble() * (top - bottom)
            val wavelength = exp(logWavelength)
            val number = 2 * PI / wavelength
            waveX[wave] = number * cos(direction)
            waveY[wave] = number * sin(direction)
            phase[wave] = random.nextDouble() * 2 * PI
            amplitude[wave] = wavelength.pow(HURST)
            power += amplitude[wave] * amplitude[wave] / 2
        }
        // Unit variance, so a level is a number of standard deviations whatever the wavelengths.
        val scale = 1.0 / sqrt(power)
        for (wave in 0 until waves) amplitude[wave] *= scale
    }

    /** The field at a point given in kilometres. */
    fun at(xKm: Double, yKm: Double): Double {
        val x = xKm / unitsAcross
        val y = yKm / unitsDown
        var sum = 0.0
        for (wave in 0 until waves) sum += amplitude[wave] * cos(waveX[wave] * x + waveY[wave] * y + phase[wave])
        return sum
    }

    /** The gradient at a point given in kilometres, per kilometre. */
    fun gradientAt(xKm: Double, yKm: Double): Pair<Double, Double> {
        val x = xKm / unitsAcross
        val y = yKm / unitsDown
        var gx = 0.0
        var gy = 0.0
        for (wave in 0 until waves) {
            val slope = -amplitude[wave] * sin(waveX[wave] * x + waveY[wave] * y + phase[wave])
            gx += slope * waveX[wave]
            gy += slope * waveY[wave]
        }
        return gx / unitsAcross to gy / unitsDown
    }

    companion object {
        /** Waves in each octave of wavelength, spread evenly in direction; see [IsotropicNoise]. */
        const val WAVES_PER_OCTAVE = 36

        /** Mandelbrot's 1.25 for a coastline is `2 - H`; see [IsotropicNoise]. */
        const val HURST = 0.75

        /** Separates the octaves' random streams. */
        private const val OCTAVE_STREAM_STRIDE = 7919L
    }
}

/**
 * The controls the detectors are shown against: natural shapes, which no detector may flag, and
 * the stamps this project's past causes drew, each of which some detector must.
 *
 * Every control is a mask on a [GridFrame], because every layer the guard reads is one: a shape
 * is rasterised the way the map rasterises, one cell at a time at the cell's centre, and then
 * traced by the same [Contours] the layers are.
 */
internal object Controls {

    /** A mask of [frame] with [inside] asked at each cell's centre, given in kilometres. */
    fun rasterise(frame: GridFrame, inside: (Double, Double) -> Boolean): BooleanArray =
        BooleanArray(frame.cellCount) { cell ->
            inside(
                (frame.columnOf(cell) + 0.5) * frame.cellWidthKm,
                (frame.rowOf(cell) + 0.5) * frame.cellHeightKm
            )
        }

    /** The same, asked in cells: column and row of the cell's centre. */
    fun rasteriseInCells(frame: GridFrame, inside: (Double, Double) -> Boolean): BooleanArray =
        BooleanArray(frame.cellCount) { cell ->
            inside(frame.columnOf(cell) + 0.5, frame.rowOf(cell) + 0.5)
        }

    /**
     * The east-west offset from [centre] to [x], taken the short way round a world [worldWidth]
     * wide, so a shape centred on the seam is one shape.
     */
    fun wrappedOffset(x: Double, centre: Double, worldWidth: Double): Double {
        var offset = x - centre
        offset -= worldWidth * floor(offset / worldWidth + 0.5)
        return offset
    }

    // ---------------------------------------------------------------- natural shapes

    /**
     * A natural island: a bump of radius [radiusKm] roughened by [IsotropicNoise], thresholded at
     * zero, centred at [centreXKm], [centreYKm] and wrapped round the seam.
     *
     * The roughness is [ROUGHNESS_OVER_RADIUS] of the radius at the island's own scale and runs
     * down to [finestWavelengthKm], so its coast is a fractal of dimension 1.25 between the
     * island's size and the finest detail the grid can hold. Rotating it turns the noise and so
     * the whole shape.
     */
    fun naturalIsland(
        frame: GridFrame,
        seed: Long,
        radiusKm: Double,
        centreXKm: Double,
        centreYKm: Double,
        rotationDegrees: Double = 0.0,
        finestWavelengthKm: Double = 2.0 * maxOf(frame.cellWidthKm, frame.cellHeightKm),
        isotropicInCells: Boolean = false,
        roughnessOverRadius: Double = ROUGHNESS_OVER_RADIUS
    ): BooleanArray {
        val noise = IsotropicNoise(
            seed,
            shortestWavelength = finestWavelengthKm / if (isotropicInCells) frame.cellWidthKm else 1.0,
            longestWavelength = 2.0 * radiusKm / if (isotropicInCells) frame.cellWidthKm else 1.0,
            rotationDegrees = rotationDegrees,
            unitsAcross = if (isotropicInCells) frame.cellWidthKm else 1.0,
            unitsDown = if (isotropicInCells) frame.cellHeightKm else 1.0
        )
        val stretchDown = if (isotropicInCells) frame.cellHeightKm / frame.cellWidthKm else 1.0
        return rasterise(frame) { x, y ->
            val dx = wrappedOffset(x, centreXKm, frame.worldWidthKm)
            val dy = (y - centreYKm) / stretchDown
            val radial = 1.0 - (dx * dx + dy * dy) / (radiusKm * radiusKm)
            radial + roughnessOverRadius * noise.at(dx, y - centreYKm) > 0.0
        }
    }

    /** How rough a natural island is at its own scale, in units of the bump's height. */
    const val ROUGHNESS_OVER_RADIUS = 0.6

    /**
     * A natural landscape: [IsotropicNoise] over the whole of a region, thresholded so that
     * [coverShare] of it is inside, and faded to outside within [marginKm] of the region's edge so
     * that no shape touches the edge or the seam — which would draw a straight line the shape
     * did not make.
     */
    fun naturalField(
        frame: GridFrame,
        seed: Long,
        coverShare: Double,
        longestWavelengthKm: Double,
        rotationDegrees: Double = 0.0,
        finestWavelengthKm: Double = 2.0 * maxOf(frame.cellWidthKm, frame.cellHeightKm),
        marginKm: Double = longestWavelengthKm / 4,
        isotropicInCells: Boolean = false
    ): BooleanArray {
        val noise = IsotropicNoise(
            seed,
            shortestWavelength = finestWavelengthKm / if (isotropicInCells) frame.cellWidthKm else 1.0,
            longestWavelength = longestWavelengthKm / if (isotropicInCells) frame.cellWidthKm else 1.0,
            rotationDegrees = rotationDegrees,
            unitsAcross = if (isotropicInCells) frame.cellWidthKm else 1.0,
            unitsDown = if (isotropicInCells) frame.cellHeightKm else 1.0
        )
        val values = DoubleArray(frame.cellCount) { cell ->
            noise.at((frame.columnOf(cell) + 0.5) * frame.cellWidthKm, (frame.rowOf(cell) + 0.5) * frame.cellHeightKm)
        }
        val level = values.sorted()[((1.0 - coverShare) * (values.size - 1)).toInt()]
        val widthKm = frame.worldWidthKm
        val heightKm = frame.cellsDown * frame.cellHeightKm
        return BooleanArray(frame.cellCount) { cell ->
            val x = (frame.columnOf(cell) + 0.5) * frame.cellWidthKm
            val y = (frame.rowOf(cell) + 0.5) * frame.cellHeightKm
            val edge = minOf(x, widthKm - x, y, heightKm - y)
            val fade = if (edge >= marginKm) 0.0 else 4.0 * (1.0 - edge / marginKm)
            values[cell] - fade > level
        }
    }

    /**
     * Natural courses: [count] walks whose heading wanders by a Gaussian turn of
     * [turnDegreesPerCell] per cell of travel, each [lengthCells] long, followed through the cells
     * they cross. A river traced on a grid is a path of cells; this is one whose course owes the
     * grid nothing but the cells it is drawn in.
     */
    fun naturalCourses(frame: GridFrame, seed: Long, count: Int, lengthCells: Int, turnDegreesPerCell: Double): List<Outline> =
        courses(frame, seed, count, lengthCells, turnDegreesPerCell, stepByNearestNeighbour = false)

    /**
     * The same walks routed the way the plain steepest-of-eight rule routes water: each step goes
     * to whichever of the eight neighbours lies nearest the heading, so a heading held for a while
     * becomes a straight run along one of the grid's bearings — the ruled reaches the routing drew
     * before the facet rule.
     */
    fun eightNeighbourCourses(frame: GridFrame, seed: Long, count: Int, lengthCells: Int, turnDegreesPerCell: Double): List<Outline> =
        courses(frame, seed, count, lengthCells, turnDegreesPerCell, stepByNearestNeighbour = true)

    private fun courses(
        frame: GridFrame,
        seed: Long,
        count: Int,
        lengthCells: Int,
        turnDegreesPerCell: Double,
        stepByNearestNeighbour: Boolean
    ): List<Outline> {
        val random = Random(seed)
        val lines = ArrayList<Outline>()
        repeat(count) {
            // Heading and position on the ground; a cell's width is the unit of travel.
            var heading = random.nextDouble() * 2 * PI
            var x = (0.25 + 0.5 * random.nextDouble()) * frame.worldWidthKm
            var y = (0.25 + 0.5 * random.nextDouble()) * frame.cellsDown * frame.cellHeightKm
            val xs = DoubleArrayBuilder()
            val ys = DoubleArrayBuilder()
            var column = floor(x / frame.cellWidthKm).toInt()
            var row = floor(y / frame.cellHeightKm).toInt()
            xs.add((column + 0.5) * frame.cellWidthKm)
            ys.add((row + 0.5) * frame.cellHeightKm)
            var steps = 0
            while (steps < lengthCells) {
                heading += Math.toRadians(turnDegreesPerCell) * gaussian(random)
                if (stepByNearestNeighbour) {
                    // The neighbour whose ground bearing is nearest the heading.
                    var best = 0 to 0
                    var nearest = Double.MAX_VALUE
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val bearing = kotlin.math.atan2(dy * frame.cellHeightKm, dx * frame.cellWidthKm)
                        var gap = abs(bearing - heading) % (2 * PI)
                        if (gap > PI) gap = 2 * PI - gap
                        if (gap < nearest) { nearest = gap; best = dx to dy }
                    }
                    column += best.first
                    row += best.second
                } else {
                    // A quarter of a cell at a time, taking every cell the walk enters.
                    x += cos(heading) * frame.cellWidthKm / 4
                    y += sin(heading) * frame.cellWidthKm / 4
                    val nextColumn = floor(x / frame.cellWidthKm).toInt()
                    val nextRow = floor(y / frame.cellHeightKm).toInt()
                    if (nextColumn == column && nextRow == row) continue
                    column = nextColumn
                    row = nextRow
                }
                if (row < 1 || row >= frame.cellsDown - 1) break
                xs.add((column + 0.5) * frame.cellWidthKm)
                ys.add((row + 0.5) * frame.cellHeightKm)
                steps++
            }
            if (xs.size >= 2) lines.add(Outline(xs.toArray(), ys.toArray(), closed = false, belt = false))
        }
        return lines
    }

    private fun gaussian(random: Random): Double {
        val u = random.nextDouble().coerceAtLeast(1e-12)
        val v = random.nextDouble()
        return sqrt(-2.0 * ln(u)) * cos(2 * PI * v)
    }

    // ---------------------------------------------------------------- stamps

    /**
     * A union of fixed blocks: a natural field decided once per [blockCells]-square block rather
     * than once per cell, the swath of blocks the ice's old basin opening drew.
     */
    fun blockUnion(frame: GridFrame, seed: Long, blockCells: Int, coverShare: Double, longestWavelengthKm: Double): BooleanArray {
        val natural = naturalField(frame, seed, coverShare, longestWavelengthKm)
        return BooleanArray(frame.cellCount) { cell ->
            val column = frame.columnOf(cell) / blockCells * blockCells + blockCells / 2
            val row = frame.rowOf(cell) / blockCells * blockCells + blockCells / 2
            natural[row.coerceAtMost(frame.cellsDown - 1) * frame.cellsAcross + column.coerceAtMost(frame.cellsAcross - 1)]
        }
    }

    /** A rectangle [lengthCells] by [widthCells], turned [rotationDegrees] on the sheet. */
    fun rectangle(
        frame: GridFrame,
        lengthCells: Double,
        widthCells: Double,
        centreColumn: Double,
        centreRow: Double,
        rotationDegrees: Double = 0.0
    ): BooleanArray {
        val turn = Math.toRadians(rotationDegrees)
        return rasteriseInCells(frame) { column, row ->
            val dx = wrappedOffset(column, centreColumn, frame.cellsAcross.toDouble())
            val dy = row - centreRow
            val along = dx * cos(turn) + dy * sin(turn)
            val across = -dx * sin(turn) + dy * cos(turn)
            abs(along) <= lengthCells / 2 && abs(across) <= widthCells / 2
        }
    }

    /**
     * What a square sliding window leaves: the maximum of a natural field over a square of
     * [windowCells] cells about every cell, thresholded. Each high point becomes a square, and
     * where squares overlap their union has edges on the axes and corners at right angles.
     */
    fun squareWindowMask(frame: GridFrame, seed: Long, windowCells: Int, longestWavelengthKm: Double): BooleanArray {
        val noise = IsotropicNoise(seed, 3.0 * frame.cellWidthKm, longestWavelengthKm)
        val values = DoubleArray(frame.cellCount) { cell ->
            noise.at((frame.columnOf(cell) + 0.5) * frame.cellWidthKm, (frame.rowOf(cell) + 0.5) * frame.cellHeightKm)
        }
        val level = values.sorted()[(0.995 * (values.size - 1)).toInt()]
        val half = windowCells / 2
        val peaks = BooleanArray(frame.cellCount) { values[it] > level }
        val margin = windowCells + 2
        return BooleanArray(frame.cellCount) { cell ->
            val column = frame.columnOf(cell)
            val row = frame.rowOf(cell)
            if (column < margin || row < margin || column >= frame.cellsAcross - margin ||
                row >= frame.cellsDown - margin
            ) return@BooleanArray false
            for (dy in -half..half) for (dx in -half..half) {
                if (peaks[(row + dy) * frame.cellsAcross + column + dx]) return@BooleanArray true
            }
            false
        }
    }

    /**
     * The depression fill's staircase: a block [sizeCells] across whose edge falls along the
     * grid's diagonal in steps [stepCells] square, the shape a chessboard metric draws.
     */
    fun chessboardStaircase(
        frame: GridFrame,
        stepCells: Int,
        sizeCells: Int,
        cornerColumn: Int,
        cornerRow: Int
    ): BooleanArray = rasteriseInCells(frame) { column, row ->
        val x = column - cornerColumn
        val y = row - cornerRow
        x >= 0 && y >= 0 && x < sizeCells && y < sizeCells &&
            floor(x / stepCells).toInt() + floor(y / stepCells).toInt() < sizeCells / stepCells
    }

    /**
     * Half a disc of radius [radius], its straight side facing [facingDegrees]: a delta lobe as
     * the cosine fan once drew it. Round on the sheet when [inCells], round on the ground if not.
     */
    fun halfDisc(
        frame: GridFrame,
        radius: Double,
        centreX: Double,
        centreY: Double,
        facingDegrees: Double,
        inCells: Boolean
    ): BooleanArray {
        val facing = Math.toRadians(facingDegrees)
        val test = { x: Double, y: Double ->
            val dx = x - centreX
            val dy = y - centreY
            dx * dx + dy * dy <= radius * radius && dx * cos(facing) + dy * sin(facing) >= 0.0
        }
        return if (inCells) rasteriseInCells(frame, test) else rasterise(frame, test)
    }

    /** A full disc, the same two ways. */
    fun disc(frame: GridFrame, radius: Double, centreX: Double, centreY: Double, inCells: Boolean): BooleanArray {
        val test = { x: Double, y: Double ->
            val dx = x - centreX
            val dy = y - centreY
            dx * dx + dy * dy <= radius * radius
        }
        return if (inCells) rasteriseInCells(frame, test) else rasterise(frame, test)
    }

    /**
     * A nearest-seed partition: [seeds] points scattered over a region [spanCells] square, each
     * cell given to the nearest by Euclidean distance on the sheet. Returned as one mask per
     * partition cell that does not touch the region's edge.
     */
    fun voronoiCells(frame: GridFrame, seed: Long, seeds: Int, spanCells: Int, originColumn: Int, originRow: Int): List<BooleanArray> {
        val random = Random(seed)
        val seedX = DoubleArray(seeds) { originColumn + random.nextDouble() * spanCells }
        val seedY = DoubleArray(seeds) { originRow + random.nextDouble() * spanCells }
        val owner = IntArray(frame.cellCount) { -1 }
        for (cell in 0 until frame.cellCount) {
            val x = frame.columnOf(cell) + 0.5
            val y = frame.rowOf(cell) + 0.5
            if (x < originColumn || y < originRow || x >= originColumn + spanCells || y >= originRow + spanCells) continue
            var best = -1
            var nearest = Double.MAX_VALUE
            for (index in 0 until seeds) {
                val distance = (x - seedX[index]).pow(2) + (y - seedY[index]).pow(2)
                if (distance < nearest) { nearest = distance; best = index }
            }
            owner[cell] = best
        }
        val touchesEdge = BooleanArray(seeds)
        for (cell in 0 until frame.cellCount) {
            val id = owner[cell]
            if (id < 0) continue
            val x = frame.columnOf(cell)
            val y = frame.rowOf(cell)
            if (x == originColumn || y == originRow || x == originColumn + spanCells - 1 || y == originRow + spanCells - 1) {
                touchesEdge[id] = true
            }
        }
        return (0 until seeds).filter { !touchesEdge[it] }.map { id -> BooleanArray(frame.cellCount) { owner[it] == id } }
    }

    /**
     * A ruled comb: [teeth] bars [barCells] wide and [lengthCells] long, [spacingCells] apart
     * centre to centre, laid along [bearingDegrees] on the sheet.
     */
    fun comb(
        frame: GridFrame,
        teeth: Int,
        spacingCells: Double,
        barCells: Double,
        lengthCells: Double,
        centreColumn: Double,
        centreRow: Double,
        bearingDegrees: Double
    ): BooleanArray {
        val turn = Math.toRadians(bearingDegrees)
        val span = (teeth - 1) * spacingCells
        return rasteriseInCells(frame) { column, row ->
            val dx = column - centreColumn
            val dy = row - centreRow
            val along = dx * cos(turn) + dy * sin(turn)
            val across = -dx * sin(turn) + dy * cos(turn) + span / 2
            if (abs(along) > lengthCells / 2 || across < -barCells || across > span + barCells) {
                false
            } else {
                val nearestTooth = Math.round(across / spacingCells).coerceIn(0L, (teeth - 1).toLong())
                abs(across - nearestTooth * spacingCells) <= barCells / 2
            }
        }
    }

    /** Manhattan distance within [radiusCells]: a four-connected breadth-first ring, a diamond. */
    fun diamond(frame: GridFrame, radiusCells: Double, centreColumn: Double, centreRow: Double): BooleanArray =
        rasteriseInCells(frame) { column, row ->
            abs(column - centreColumn) + abs(row - centreRow) <= radiusCells
        }

    /** Chebyshev distance within [radiusCells]: an eight-connected breadth-first ring, a square. */
    fun chebyshevSquare(frame: GridFrame, radiusCells: Double, centreColumn: Double, centreRow: Double): BooleanArray =
        rasteriseInCells(frame) { column, row ->
            max(abs(column - centreColumn), abs(row - centreRow)) <= radiusCells
        }

    /** An octagonal window: Chebyshev and Manhattan together, as the relief window once was. */
    fun octagon(frame: GridFrame, radiusCells: Double, centreColumn: Double, centreRow: Double): BooleanArray =
        rasteriseInCells(frame) { column, row ->
            val dx = abs(column - centreColumn)
            val dy = abs(row - centreRow)
            max(dx, dy) <= radiusCells && dx + dy <= radiusCells * OCTAGON_DIAGONAL_SHARE
        }

    /**
     * How far out a regular octagon's diagonal sides stand, in Manhattan distance over the
     * radius: every side of a regular octagon stands at the same distance from its centre, and a
     * diagonal side at distance `r` is `|dx| + |dy| = r * sqrt(2)`.
     */
    private val OCTAGON_DIAGONAL_SHARE = sqrt(2.0)

    /**
     * [stamp] with its edge moved by up to [amplitudeCells] by natural noise: the cells within the
     * amplitude of the edge are decided by the noise's sign, weighted by how far in they lie.
     */
    fun roughened(frame: GridFrame, stamp: BooleanArray, seed: Long, amplitudeCells: Double): BooleanArray {
        val noise = IsotropicNoise(seed, 3.0 * frame.cellWidthKm, 12.0 * frame.cellWidthKm)
        val inside = DistanceToEdge.signedCells(frame, stamp)
        return BooleanArray(frame.cellCount) { cell ->
            val x = (frame.columnOf(cell) + 0.5) * frame.cellWidthKm
            val y = (frame.rowOf(cell) + 0.5) * frame.cellHeightKm
            inside[cell] + amplitudeCells * noise.at(x, y) > 0.0
        }
    }
}

/** Signed distance to a mask's edge in cells, positive inside, by a small exact search. */
internal object DistanceToEdge {
    fun signedCells(frame: GridFrame, mask: BooleanArray, reachCells: Int = 6): DoubleArray {
        val across = frame.cellsAcross
        return DoubleArray(frame.cellCount) { cell ->
            val column = frame.columnOf(cell)
            val row = frame.rowOf(cell)
            val here = mask[cell]
            var nearest = (reachCells + 1).toDouble()
            for (dy in -reachCells..reachCells) {
                val r = row + dy
                if (r < 0 || r >= frame.cellsDown) continue
                for (dx in -reachCells..reachCells) {
                    val c = (column + dx + across) % across
                    if (mask[r * across + c] != here) {
                        val distance = sqrt((dx * dx + dy * dy).toDouble()) - 0.5
                        if (distance < nearest) nearest = distance
                    }
                }
            }
            if (here) nearest else -nearest
        }
    }
}
