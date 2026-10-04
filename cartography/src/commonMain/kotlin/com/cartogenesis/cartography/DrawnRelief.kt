package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The surface the map draws the land from, D, on the sea stage's shoreline-relative ruler: the
 * ground, with each cell's own relief drawn as the dissection it is, and the bed wherever a river
 * or a lake is drawn.
 *
 * The erosion keeps two heights a cell (`ErosionResult`): the **bed**, the trunk channel the water
 * runs in, and the **ground**, the cell's mean over its own hillslopes and in-cell channels. The
 * ground is the right height for everything that reads an altitude, and the wrong surface to shade:
 * a cell's mean hides the valleys inside it, so a range drawn from it alone reads as smooth and
 * featureless, and a river drawn on it would cross the rises its own valley stands in. So:
 *
 * - **Under every traced river and every lake, the bed.** The water is drawn in its channel and on
 *   its basin's floor, never across ground standing above it. A lake is drawn where its surface
 *   reaches the ground (the rivers stage marks only those cells), and the river runs on through the
 *   rest of its basin in the bed's channel.
 * - **Everywhere else, the ground with its relief drawn back in.** The relief inside a cell,
 *   `ground - bed` here (the interfluves' relief over the bed less the bed's own share of the cell,
 *   which is a few hundredths), is real stored state, and the cell's hypsometry spreads its land
 *   evenly from the bed to twice that above it (`GroundClosure.fillForBedRise`). Each cell is
 *   drawn at one point of that spread, `ground + (ground - bed) P`, where `P` is a pattern of ridges
 *   and hollows running down the cell's slope, spread uniformly over -1 to 1 so that the drawn
 *   heights have the cell's own hypsometry and their mean is its ground. Where a cell holds no
 *   relief over its bed, as a cell below every channel head does, it is drawn at its ground.
 *
 * **The pattern** is Gabor noise (Lagae and others, *Procedural noise using sparse Gabor
 * convolution*, SIGGRAPH 2009): randomly placed kernels, each a Gaussian envelope times a stripe,
 * summed. Each kernel's stripes run down the slope of the cell it stands on, read off the ground's
 * gradient on the ground's own metric, so ridges and hollows run downhill and turn with the
 * terrain, and no kernel stands on a lattice: their places, weights and phases are a hash of the
 * world's seed and the cell. The stripes' spacing is the cell's valley spacing, twice the channel
 * head's support length `sqrt(A_c)` (`ErosionResult.channelHeadAreaKm2`), the finest valleys the
 * cell holds; a sheet drawing one colour a cell cannot hold valleys a few hundred metres apart, so
 * the spacing is taken up by whole octaves until it reaches the finest the sheet can draw at every
 * bearing ([FINEST_WAVELENGTH_CELLS]). The in-cell network repeats its geometry octave by octave
 * (`GroundClosure.NETWORK_RELIEF_PER_OCTAVE`), so the drawn spacing keeps the cell's place on that
 * ladder: closer-set heads draw closer-set marks. The sum is read through the normal distribution
 * to a uniform one, which is what makes the drawn heights the hypsometry's.
 *
 * What it does not do is invent relief: the amplitude is the cell's stored relief, a cell's mean
 * is unchanged, and the bed's own valleys, the resolved ones, are drawn where the erosion cut them.
 *
 * Computed on the processor, once a world (the last world asked for is kept), and handed to the
 * raster and its accelerator as their elevation; the dissection is per cell and is to have a
 * device path behind [RasterAccelerator] held to this answer (docs/TODO.md, E1c).
 */
object DrawnRelief {

    /**
     * The finest stripe spacing a raster of one colour a cell draws at every bearing, in cell
     * widths. Two cells is the lattice's own limit along a row or a column (Nyquist); a kernel's
     * envelope one wavelength wide ([ENVELOPE_WAVELENGTHS]) spreads its spectrum one cycle a
     * wavelength either side of its centre before it falls to 4% (`exp(-pi)`), so the centre is held
     * to half that limit, and four cells is the finest spacing whose whole band the sheet can hold
     * without folding it onto the grid's axes.
     */
    internal const val FINEST_WAVELENGTH_CELLS = 4.0

    /**
     * A kernel's Gaussian envelope, `exp(-pi r^2 / w^2)`, is this many of its stripes wide: one, so
     * a kernel is one ridge with a hollow either side, the length of a hillslope and no longer, and
     * a ridge longer than that is drawn by kernels whose stripes happen to line up down the slope.
     */
    private const val ENVELOPE_WAVELENGTHS = 1.0

    /**
     * How many kernels stand over each point of the sheet, on the mean: sixteen, enough for their
     * sum to be near enough a normal variate (the central limit theorem; Lagae and others take ten
     * to twenty) that reading it through the normal distribution gives a uniform one.
     */
    private const val KERNELS_OVER_A_POINT = 16.0

    /** Side of the tiles the kernels are filed in, in cells: bookkeeping, so a cell reads only the tiles near it. */
    private const val TILE_CELLS = 8

    private var lastWorld: WorldMap? = null
    private var lastDrawn: FloatField? = null
    private var lastPlainWorld: WorldMap? = null
    private var lastPlain: FloatField? = null

    /**
     * D without the dissection: the ground, and the bed under the rivers and the lakes. What a
     * pen's strokes are laid down the slope of, since a stroke is itself the mark a pen gives the
     * relief and drawn across the dissection it would follow each cell's ridge rather than the
     * slope. Same units and layout as [of]; not to be written.
     */
    fun withoutDissection(world: WorldMap): FloatField {
        val world0 = lastPlainWorld
        val plain0 = lastPlain
        if (world0 === world && plain0 != null) return plain0
        val sea = world.sea
        val plain = FloatField(world.width, world.height, sea.relativeElevation.data.copyOf())
        val under = underWater(world)
        for (cell in under.indices) {
            if (under[cell] && sea.isLand[cell]) plain.data[cell] = sea.relativeBed.data[cell]
        }
        lastPlainWorld = world
        lastPlain = plain
        return plain
    }

    /**
     * The surface [style] draws: [of] for a style that shades the relief, [withoutDissection] for a
     * line-art style, whose hachures are the relief's mark.
     */
    fun forStyle(world: WorldMap, style: MapStyle): FloatField =
        if (style.lineArt) withoutDissection(world) else of(world)

    /** The cells under a traced river or a lake, where the water is drawn on the bed. */
    private fun underWater(world: WorldMap): BooleanArray {
        val under = BooleanArray(world.width * world.height)
        world.rivers.rivers.forEach { river -> river.cells.forEach { under[it] = true } }
        world.rivers.lakes.lakeId.forEachIndexed { cell, id -> if (id >= 0) under[cell] = true }
        return under
    }

    /**
     * D for [world], one value per cell, row-major, on `sea.relativeElevation`'s ruler: equal to it
     * at sea, the bed under rivers and lakes, and the ground with its relief drawn as dissection
     * elsewhere on land. Not to be written: the last world's answer is shared.
     */
    fun of(world: WorldMap): FloatField {
        val world0 = lastWorld
        val drawn0 = lastDrawn
        if (world0 === world && drawn0 != null) return drawn0
        val drawn = compute(world)
        lastWorld = world
        lastDrawn = drawn
        return drawn
    }

    /** [of] without the cache. */
    internal fun compute(world: WorldMap): FloatField {
        val cellsAcross = world.width
        val cellsDown = world.height
        val cellCount = cellsAcross * cellsDown
        val sea = world.sea
        val ground = sea.relativeElevation.data
        val bed = sea.relativeBed.data
        val drawn = FloatField(cellsAcross, cellsDown, ground.copyOf())
        if (bed === ground) return drawn

        val underWater = underWater(world)

        val rowHeight = world.config.cellHeightInCellWidths
        val cellWidthKm = world.config.cellWidthKm
        val headAreaKm2 = world.erosion.channelHeadAreaKm2.data
        val seed = world.config.seed

        // Each land cell's stripe spacing, in cell widths, and its downslope bearing; NaN where it
        // draws no dissection.
        val wavelength = FloatArray(cellCount) { Float.NaN }
        val downX = FloatArray(cellCount)
        val downY = FloatArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!sea.isLand[cell] || underWater[cell]) continue
            if (ground[cell] - bed[cell] <= 0f) continue
            val head = headAreaKm2[cell]
            // No area is no head: a cell under water, or ground that neither falls nor wears.
            if (!(head > 0f) || head.isInfinite()) continue
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            val east = ground[row * cellsAcross + (column + 1) % cellsAcross]
            val west = ground[row * cellsAcross + (column - 1 + cellsAcross) % cellsAcross]
            val north = ground[(row - 1).coerceAtLeast(0) * cellsAcross + column]
            val south = ground[(row + 1).coerceAtMost(cellsDown - 1) * cellsAcross + column]
            // Downhill on the ground's own metric: a row is [rowHeight] cell widths tall.
            val gradientX = (east - west) / 2.0
            val gradientY = (south - north) / (2.0 * rowHeight)
            val steepness = sqrt(gradientX * gradientX + gradientY * gradientY)
            if (steepness <= 0.0) continue
            downX[cell] = (-gradientX / steepness).toFloat()
            downY[cell] = (-gradientY / steepness).toFloat()
            wavelength[cell] = drawnWavelengthCells(2.0 * sqrt(head.toDouble()) / cellWidthKm).toFloat()
        }

        // The kernels, each on a random point of a cell that draws dissection, filed by tile.
        val tilesAcross = (cellsAcross + TILE_CELLS - 1) / TILE_CELLS
        val tilesDown = (cellsDown + TILE_CELLS - 1) / TILE_CELLS
        val kernelTile = ArrayList<Int>()
        val kernelX = ArrayList<Float>()
        val kernelY = ArrayList<Float>()
        val kernelCell = ArrayList<Int>()
        val kernelWeight = ArrayList<Float>()
        val kernelPhase = ArrayList<Float>()
        for (cell in 0 until cellCount) {
            val lambda = wavelength[cell]
            if (lambda.isNaN()) continue
            val envelope = ENVELOPE_WAVELENGTHS * lambda
            // Kernels a cell, so that [KERNELS_OVER_A_POINT] stand over a point on the mean.
            val perCell = KERNELS_OVER_A_POINT * rowHeight / (PI * envelope * envelope)
            var draw = mix(seed, cell)
            val whole = floor(perCell).toInt()
            val count = whole + if (unit(draw) < perCell - whole) 1 else 0
            repeat(count) {
                draw = mix(draw, cell)
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                kernelX.add((column + unit(draw)).toFloat())
                draw = mix(draw, cell)
                kernelY.add(((row + unit(draw)) * rowHeight).toFloat())
                draw = mix(draw, cell)
                kernelWeight.add(if (unit(draw) < 0.5) -1f else 1f)
                draw = mix(draw, cell)
                kernelPhase.add((2.0 * PI * unit(draw)).toFloat())
                kernelCell.add(cell)
                kernelTile.add((row / TILE_CELLS) * tilesAcross + column / TILE_CELLS)
            }
        }
        val kernelCount = kernelTile.size
        val tileStart = IntArray(tilesAcross * tilesDown + 1)
        for (tile in kernelTile) tileStart[tile + 1]++
        for (tile in 0 until tilesAcross * tilesDown) tileStart[tile + 1] += tileStart[tile]
        val filed = IntArray(kernelCount)
        val cursor = tileStart.copyOf()
        for (kernel in 0 until kernelCount) filed[cursor[kernelTile[kernel]]++] = kernel

        // The widest envelope any kernel has, in cell widths and in tiles each way.
        val widestEnvelope = ENVELOPE_WAVELENGTHS * 2.0 * FINEST_WAVELENGTH_CELLS
        val tileReachAcross = ceil(widestEnvelope / TILE_CELLS).toInt()
        val tileReachDown = ceil(widestEnvelope / (TILE_CELLS * rowHeight)).toInt()
        // The sum's spread where kernels of one spacing stand round a point: each of them adds
        // `exp(-2 pi r^2 / w^2) cos^2` to the variance on the mean, a quarter of `w^2` over the
        // ground, at [KERNELS_OVER_A_POINT] for every `pi w^2`.
        val spread = sqrt(KERNELS_OVER_A_POINT / (4.0 * PI))
        val width = cellsAcross.toDouble()

        for (cell in 0 until cellCount) {
            if (wavelength[cell].isNaN()) continue
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            val x = column + 0.5
            val y = (row + 0.5) * rowHeight
            val tileColumn = column / TILE_CELLS
            val tileRow = row / TILE_CELLS
            var sum = 0.0
            for (tileStepDown in -tileReachDown..tileReachDown) {
                val nearRow = tileRow + tileStepDown
                if (nearRow < 0 || nearRow >= tilesDown) continue
                for (tileStepAcross in -tileReachAcross..tileReachAcross) {
                    val nearColumn = (tileColumn + tileStepAcross).mod(tilesAcross)
                    val tile = nearRow * tilesAcross + nearColumn
                    for (index in tileStart[tile] until tileStart[tile + 1]) {
                        val kernel = filed[index]
                        var offsetX = x - kernelX[kernel]
                        if (offsetX > width / 2) offsetX -= width
                        if (offsetX < -width / 2) offsetX += width
                        val offsetY = y - kernelY[kernel]
                        val home = kernelCell[kernel]
                        val lambda = wavelength[home].toDouble()
                        val envelope = ENVELOPE_WAVELENGTHS * lambda
                        val distanceSquared = offsetX * offsetX + offsetY * offsetY
                        if (distanceSquared >= envelope * envelope) continue
                        // Across the slope: the stripes run down it.
                        val across = -offsetX * downY[home] + offsetY * downX[home]
                        sum += kernelWeight[kernel] *
                            exp(-PI * distanceSquared / (envelope * envelope)) *
                            cos(2.0 * PI * across / lambda + kernelPhase[kernel])
                    }
                }
            }
            val share = errorFunction(sum / (spread * SQRT_TWO))
            drawn.data[cell] = (ground[cell] + (ground[cell] - bed[cell]) * share).toFloat()
        }
        for (cell in 0 until cellCount) if (underWater[cell] && sea.isLand[cell]) drawn.data[cell] = bed[cell]
        return drawn
    }

    /**
     * The spacing a cell's dissection is drawn at, in cell widths, for valleys [valleySpacingCells]
     * apart: taken by whole octaves into `[FINEST_WAVELENGTH_CELLS, 2 * FINEST_WAVELENGTH_CELLS)`,
     * so the drawn spacing keeps its place on the network's ladder of octaves.
     */
    internal fun drawnWavelengthCells(valleySpacingCells: Double): Double {
        if (!(valleySpacingCells > 0.0) || valleySpacingCells.isInfinite()) return FINEST_WAVELENGTH_CELLS
        val octaves = floor(ln(valleySpacingCells / FINEST_WAVELENGTH_CELLS) / LN_TWO)
        var spacing = valleySpacingCells / exp(octaves * LN_TWO)
        if (spacing < FINEST_WAVELENGTH_CELLS) spacing *= 2.0
        if (spacing >= 2.0 * FINEST_WAVELENGTH_CELLS) spacing /= 2.0
        return spacing
    }

    /** A fixed 64-bit mix of [state] and [cell], the same on every platform. */
    private fun mix(state: Long, cell: Int): Long {
        var bits = state xor (cell.toLong() * -0x61c8864680b583ebL)
        bits = (bits xor (bits ushr 30)) * -0x40a7b892e31b1a47L
        bits = (bits xor (bits ushr 27)) * -0x6b2fb644ecceee15L
        return bits xor (bits ushr 31)
    }

    /** The top 53 bits of [bits] as a number in [0, 1). */
    private fun unit(bits: Long): Double = (bits ushr 11).toDouble() / (1L shl 53).toDouble()

    /**
     * The error function, by Abramowitz and Stegun's 7.1.26, to 1.5e-7: what reads a normal
     * variate of unit spread over root two as a uniform one on -1 to 1.
     */
    private fun errorFunction(x: Double): Double {
        val t = 1.0 / (1.0 + ERF_P * abs(x))
        val polynomial = ((((ERF_A5 * t + ERF_A4) * t + ERF_A3) * t + ERF_A2) * t + ERF_A1) * t
        val value = 1.0 - polynomial * exp(-x * x)
        return if (x >= 0.0) value else -value
    }

    private val LN_TWO = ln(2.0)
    private val SQRT_TWO = sqrt(2.0)

    // Abramowitz and Stegun, Handbook of Mathematical Functions, 7.1.26.
    private const val ERF_P = 0.3275911
    private const val ERF_A1 = 0.254829592
    private const val ERF_A2 = -0.284496736
    private const val ERF_A3 = 1.421413741
    private const val ERF_A4 = -1.453152027
    private const val ERF_A5 = 1.061405429
}
