package com.cartogenesis.cartography

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The stroke geometry of an engraved map: how big each mark of the pen is, in pixels.
 *
 * In pixels, and that is the whole of the idea. A pen does not grow with the sheet. An engraver
 * handed a plate twice the size does not draw the same picture twice as large; he draws the same
 * hachure with the same nib and fits four times as many strokes on it, so the larger plate carries
 * more of the country rather than a bigger version of less of it. Every figure here is therefore a
 * fixed count of output pixels at every resolution, and what grows with the grid is the *number* of
 * marks — as the square of the grid ratio, so a 2048 render lays sixteen strokes over the ground a
 * 512 render gives one.
 *
 * Sizing them as a share of the width instead was tried first and reviewed: at 2048 the hachures
 * came out as black dashes thirty pixels long and the ice stipple as polka dots, which is a woodcut
 * and not an engraving.
 *
 * Only two figures still depend on the map's width, and neither of them is a mark: how many lattice
 * columns fit across it, which is what lets the strokes meet at the date line, and what a central
 * difference has to be multiplied by to mean the same slope at any resolution.
 *
 * Built once per render and handed to both paths — the rasteriser reads it directly, the graphics
 * card gets the same numbers as uniforms (see [RasterRecipe.engraving]) — so neither can derive a
 * different pitch from the same width.
 */
class EngravingPlan(width: Int) {

    /**
     * Side of the lattice one stroke is drawn on, in pixels.
     *
     * The strokes are placed rather than combed. Every lattice cell holds one stroke, nudged off
     * the cell's centre by a hash of the cell so the field does not read as a grid, and a pixel
     * asks the nine cells around it whether any of their strokes covers it. That is the whole of
     * the arrangement, and it is what makes the drawing possible at all: a stripe field whose phase
     * is the pixel's position projected onto the local contour cannot work, because the position is
     * hundreds of pixels from the origin and a single degree of turn in the aspect then slides the
     * phase by a whole stripe. Measured, before that was replaced: the ink came out as isolated
     * specks with no direction in it at all, and the guard scored it 42.9 degrees against a random
     * bearing's 44.8. Anchoring each stroke to its own lattice cell bounds the arm at one cell, so a
     * turn in the aspect bends a stroke instead of shattering the field.
     */
    val hachureLatticeCells: Int = HACHURE_LATTICE_PIXELS

    /**
     * How many lattice cells fit across the map.
     *
     * The east-west axis wraps, so the hash that places a stroke is taken on the column index
     * modulo this and the pattern meets itself at the date line instead of showing a seam. Exact
     * whenever the width divides by the pitch, which every power of two does.
     */
    val hachureLatticeColumns: Int = (width / hachureLatticeCells).coerceAtLeast(1)

    /** Half the length of one stroke, down the slope: three quarters of the lattice pitch. */
    val strokeHalfLengthCells: Float = HACHURE_LATTICE_PIXELS * 0.75f

    /**
     * Half the width of one stroke at full steepness, across the slope.
     *
     * A hairline, and deliberately: Lehmann's rule is carried by how black a stroke runs and by how
     * many of them there are, not by how fat each one is. The width is this times the steepness, so
     * flat ground draws nothing and a cliff draws a two-pixel line.
     */
    val strokeHalfWidthCells: Float = 1f

    /**
     * How far the central difference that gives the slope and the aspect reaches, in pixels.
     *
     * One lattice pitch, which is the physically right figure: a drawing whose strokes are a pitch
     * apart cannot express a change of direction finer than a pitch, and reading the aspect off a
     * single pair of neighbouring cells on eroded ground gives a direction that changes every cell.
     */
    val gradientStencilCells: Int = HACHURE_LATTICE_PIXELS

    /**
     * What the central difference is multiplied by to become a slope.
     *
     * `ReliefShading.slopeScale` over the stencil's reach. This is the one figure that has to
     * scale with the width, and for the opposite reason to everything else here: eight pixels of a
     * 2048 grid cover a quarter of the ground eight pixels of a 512 grid cover, so without the scale
     * the same hillside would read four times flatter on the larger plate and take four times less
     * ink.
     */
    val gradientScale: Float = ReliefShading.slopeScale(width) / gradientStencilCells

    /**
     * Where the first coastal line sits, in pixels from the shore.
     *
     * Line `k` sits at `base * (k + 1) * (k + 2) / 2`, so the four lines fall at one, three, six
     * and ten times this — four, twelve, twenty-four and forty pixels — and the gap between them
     * widens the way an engraver's does.
     */
    val vignetteBaseCells: Float = 4f

    /** Half the thickness of a coastal line. */
    val vignetteHalfWidthCells: Float = 0.6f

    /** How many lines follow the coast out to sea. Engravers drew three to five. */
    val vignetteLineCount: Int = 4

    /** How far the solid shore ink reaches out over the water from the coast. */
    val shoreInkCells: Float = 1.5f

    /**
     * How far the same firm ink reaches in from a lake's bank.
     *
     * Thinner than the coast's, because a lake is a small thing and at the coast's width a narrow
     * one would come out as a solid blot, which is one of the things this style was redrawn to stop
     * doing. Lakes narrower than twice this still fill, and at that size an engraver filled them too.
     */
    val lakeRimCells: Float = 1.2f

    /** Distance between the horizontal water lines inside a lake. */
    val lakeLinePitchCells: Float = 6f

    /** Half the thickness of one water line. */
    val lakeLineHalfWidthCells: Float = 0.45f

    /**
     * How far from the bank a lake's water lines have faded to nothing.
     *
     * Eight rulings' worth. A lake wider than twice this keeps a blank middle, which is what an
     * engraver left: the ruling says "water" at the bank rather than filling the basin.
     */
    val lakeFadeCells: Float = 48f

    /** Distance between stipple dots on the ice. */
    val stipplePitchCells: Int = 6

    /** Radius of one stipple dot. Small enough that a dot always sits clear of its cell's edges. */
    val stippleRadiusCells: Float = 1f

    /** Side of the block a dotted border is broken into. */
    val borderDashCells: Int = 4

    companion object {

        /**
         * Side of the hachure lattice, in output pixels.
         *
         * Eight, which is what the 512 plate was drawn at and reviewed at. Every other mark here is
         * this or a fraction of it, and none of them changes with the size of the sheet.
         */
        const val HACHURE_LATTICE_PIXELS: Int = 8

        /**
         * How wide the soft edge of a drawn line is, in pixels.
         *
         * A pen leaves a soft edge, and a hard one-bit threshold reads as a dither rather than as
         * ink. It is also what keeps the two paths within a channel of each other: a hard threshold
         * on a square root flips whole pixels where the two hardwares round differently, and a ramp
         * moves them by one step instead.
         */
        const val ANTIALIAS_CELLS: Float = 0.6f

        /**
         * The slope below which the ground is left blank, in the same units
         * [EngravingPlan.gradientScale] produces.
         *
         * Lehmann's rule is that a hachure map leaves the flat blank and darkens with the slope, so
         * there has to be a figure for flat. Set from the ground rather than by eye: it is the tenth
         * percentile of the land slope of seed 234475 measured at this stencil, so a tenth of the
         * land — the deltas, the basin floors, the coastal plain — takes no ink at all.
         */
        const val SLOPE_FLOOR: Float = 0.07f

        /**
         * The steepness at which a stroke is fully black, as a fraction of the way from
         * [SLOPE_FLOOR] to the steepest ground.
         *
         * Below it the stroke is grey, which is what a light hand on gentle ground looks like. A
         * third of the way up, so the rolling country between the ranges still takes a legible
         * mark and only the ranges themselves run solid.
         */
        const val FULL_INK_AT_STEEPNESS: Float = 0.34f

        /** How black a lake's water lines run against the firm ink of its shore. */
        const val LAKE_LINE_STRENGTH: Float = 0.8f

        /** What share of a dotted border's blocks take ink. */
        const val BORDER_DUTY_PERCENT: Int = 62
    }
}

/**
 * How far a pixel of an engraved map is dragged toward the ink.
 *
 * The reference implementation. Every one of these has a copy in the compute shader
 * (`GpuRaster.SOURCE`), written line for line against this file; the two are one drawing in two
 * languages and have to be changed together, exactly as the hillshade is.
 *
 * Three properties are load-bearing and worth stating, because they are what lets the same picture
 * come off a graphics card whose square root and divide round differently from the JVM's:
 *
 *  - **Nothing is a hard threshold on a rounded quantity.** Every edge is a [smoothstep] across
 *    [EngravingPlan.ANTIALIAS_CELLS], so a last-bit difference in a phase moves a channel by one
 *    step rather than flipping a pixel from paper to ink.
 *  - **Nothing quantised can change the answer.** Where an index has to be worked out — which
 *    lattice cell a pixel is in, which stipple cell — the mark it selects sits far enough inside
 *    that cell that a pixel on the boundary is covered identically whichever side it is counted on,
 *    so the two paths may disagree about the index and still draw the same pixel.
 *  - **What can be integer is integer.** The stipple's dot centres and the border's dashes are
 *    whole cells, hashed with 32-bit wrapping arithmetic that Kotlin and GLSL agree on to the bit.
 */
internal object Engraving {

    /**
     * The hachures: short strokes running down the slope, heavier where the ground is steeper.
     *
     * Lehmann's rule, drawn per pixel. One stroke sits in each cell of a lattice, nudged off centre
     * by a hash of the cell, and every stroke lies along the aspect at the pixel asking about it —
     * so a stroke bends with the ground it is drawn on and a range comes out combed downhill rather
     * than scribbled at one bearing. Steepness sets the stroke's width and how black it runs; flat
     * ground draws nothing.
     *
     * The nine cells around the pixel are enough, and that is a property worth keeping: a stroke
     * reaches at most [EngravingPlan.strokeHalfLengthCells] plus its half width from its own seed,
     * which is under one and a quarter lattice cells, and a seed two cells away is at least that
     * far. It is also what makes the two paths agree — the cell index is the only quantised thing
     * here, and a pixel on a cell boundary gets the same answer from either side of it, because the
     * cells the two neighbourhoods differ by cannot reach it.
     *
     * @return 0 for blank paper, 1 for solid ink.
     */
    fun hachure(
        pixelX: Int,
        pixelY: Int,
        gradientX: Float,
        gradientY: Float,
        cellHeightInCellWidths: Float,
        plan: EngravingPlan,
        inkGain: Float
    ): Float {
        // The ground's slope, which is what the stroke's weight says: down a column a pixel is a
        // row, a share of a cell width, so the fall per pixel there is less than the fall per cell
        // width of ground by that share.
        val southwardOnTheGround = gradientY / cellHeightInCellWidths
        val slope = sqrt(gradientX * gradientX + southwardOnTheGround * southwardOnTheGround)
        val steepness =
            ((slope - EngravingPlan.SLOPE_FLOOR) * inkGain).coerceIn(0f, 1f)
        if (steepness <= 0f) return 0f

        // And the ground's fall line as the sheet draws it, which is the stroke's direction: a cell
        // width of ground southward is 1 / [cellHeightInCellWidths] rows of the sheet.
        val sheetSouthward = southwardOnTheGround / cellHeightInCellWidths
        val perSheetSlope = 1f / sqrt(gradientX * gradientX + sheetSouthward * sheetSouthward)
        val downhillX = gradientX * perSheetSlope
        val downhillY = sheetSouthward * perSheetSlope

        val pitch = plan.hachureLatticeCells
        val latticeColumns = plan.hachureLatticeColumns
        val halfLength = plan.strokeHalfLengthCells
        val halfWidth = plan.strokeHalfWidthCells * steepness
        val softEdge = EngravingPlan.ANTIALIAS_CELLS
        val hereColumn = pixelX / pitch
        val hereRow = pixelY / pitch

        var strongest = 0f
        for (offsetRow in -1..1) {
            for (offsetColumn in -1..1) {
                val column = hereColumn + offsetColumn
                val row = hereRow + offsetRow
                val bits = hashBits(column.mod(latticeColumns), row)
                val seedX = column * pitch +
                    pitch * (NUDGE_FROM + NUDGE_SPAN * unitFrom(bits, HASH_SHIFT_X))
                val seedY = row * pitch +
                    pitch * (NUDGE_FROM + NUDGE_SPAN * unitFrom(bits, HASH_SHIFT_Y))
                val fromSeedX = pixelX - seedX
                val fromSeedY = pixelY - seedY
                val alongStroke = abs(fromSeedX * downhillX + fromSeedY * downhillY)
                val acrossStroke = abs(fromSeedX * -downhillY + fromSeedY * downhillX)
                val coverage =
                    (1f - smoothstep(
                        halfLength - softEdge, halfLength + softEdge, alongStroke
                    )) * (1f - smoothstep(
                        halfWidth - softEdge, halfWidth + softEdge, acrossStroke
                    ))
                if (coverage > strongest) strongest = coverage
            }
        }

        val darkness = (steepness / EngravingPlan.FULL_INK_AT_STEEPNESS).coerceAtMost(1f)
        return strongest * darkness
    }

    /**
     * The coastal vignette: lines following the shore out to sea at widening spacing, fading.
     *
     * The convention of every engraved chart, and the reason an old map's ocean reads as water
     * rather than as the paper it is printed on. [shoreCells] is the distance from the nearest dry
     * land; inside [EngravingPlan.shoreInkCells] the water is the shore's own solid ink.
     */
    fun coastalWater(shoreCells: Float, plan: EngravingPlan): Float {
        if (shoreCells < plan.shoreInkCells) return 1f

        // The nth line sits at base * (n+1)(n+2)/2; this inverts that triangular series, so the
        // whole number part of `line` names the nearest line and the fraction says how far past
        // it this pixel is. The quarter under the root completes the square of the inversion.
        val line = floor(
            sqrt(2f * shoreCells / plan.vignetteBaseCells + 0.25f) - 1f
        )
        if (line < 0f || line >= plan.vignetteLineCount) return 0f

        val centre = plan.vignetteBaseCells * (line + 1f) * (line + 2f) * 0.5f
        val coverage = 1f - smoothstep(
            plan.vignetteHalfWidthCells - EngravingPlan.ANTIALIAS_CELLS,
            plan.vignetteHalfWidthCells + EngravingPlan.ANTIALIAS_CELLS,
            abs(shoreCells - centre)
        )
        // Each line further out is drawn fainter, so the vignette dies away into open paper
        // rather than stopping at a fourth line as firm as the first.
        return coverage * (1f - line / plan.vignetteLineCount)
    }

    /**
     * A lake: a firm shoreline and horizontal water lines inside it, fading toward the middle.
     *
     * The lake itself is left as paper — a grey blot is a colour decision and this style has no
     * colour to spend — so the water is described the way an engraver describes it, by ruling it.
     */
    fun lakeWater(pixelY: Int, shoreCells: Float, plan: EngravingPlan): Float {
        if (shoreCells < plan.lakeRimCells) return 1f

        val pitch = plan.lakeLinePitchCells
        val linesDown = pixelY / pitch
        // The rulings are centred half a pitch into each period, so the distance to the nearest
        // one is how far this row's fraction of a period sits from the middle of it.
        val fromLine = abs(linesDown - floor(linesDown) - 0.5f) * pitch
        val coverage = 1f - smoothstep(
            plan.lakeLineHalfWidthCells - EngravingPlan.ANTIALIAS_CELLS,
            plan.lakeLineHalfWidthCells + EngravingPlan.ANTIALIAS_CELLS,
            fromLine
        )
        val fade = (1f - shoreCells / plan.lakeFadeCells).coerceIn(0f, 1f)
        return coverage * fade * EngravingPlan.LAKE_LINE_STRENGTH
    }

    /**
     * Ice, as stipple: a dot per lattice cell, each nudged off centre by a hash of the cell.
     *
     * The nudge is kept to the middle two fifths of the cell, so a dot together with its soft edge
     * always sits clear of the cell's own boundary: a pixel one side of a boundary is outside every
     * dot the other side of it, whichever cell it is counted in, and the two paths cannot draw a
     * different dot even where they disagree about the cell. The offset is an exact fraction of a
     * power of two, so both place the centre on the same spot to the bit.
     */
    fun stipple(pixelX: Int, pixelY: Int, plan: EngravingPlan): Float {
        val pitch = plan.stipplePitchCells
        val column = pixelX / pitch
        val row = pixelY / pitch
        val bits = hashBits(column, row)
        val centreX = column * pitch +
            pitch * (DOT_NUDGE_FROM + DOT_NUDGE_SPAN * unitFrom(bits, HASH_SHIFT_X))
        val centreY = row * pitch +
            pitch * (DOT_NUDGE_FROM + DOT_NUDGE_SPAN * unitFrom(bits, HASH_SHIFT_Y))
        val fromCentreX = pixelX - centreX
        val fromCentreY = pixelY - centreY
        val distance = sqrt(fromCentreX * fromCentreX + fromCentreY * fromCentreY)
        return 1f - smoothstep(
            plan.stippleRadiusCells - EngravingPlan.ANTIALIAS_CELLS,
            plan.stippleRadiusCells + EngravingPlan.ANTIALIAS_CELLS,
            distance
        )
    }

    /**
     * Whether a border cell takes ink, so the boundary reads as a dotted line rather than a solid.
     *
     * The map is cut into blocks and a hashed majority of them take ink, so a border running in any
     * direction is broken at irregular intervals — which is what a boundary drawn by hand looks
     * like, and what a regular dash pattern cannot manage for a line that turns.
     */
    fun borderDot(pixelX: Int, pixelY: Int, plan: EngravingPlan): Boolean {
        val block = plan.borderDashCells
        val bits = hashBits(pixelX / block, pixelY / block)
        return (bits ushr HASH_SHIFT_X) % A_HUNDRED < EngravingPlan.BORDER_DUTY_PERCENT
    }

    /** The Hermite ramp GLSL's `smoothstep` is defined as, written out so both paths agree. */
    fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * A 32-bit avalanche, in arithmetic Kotlin's `Int` and GLSL's `uint` perform identically:
     * wrapping multiplies, logical shifts and exclusive ors, and no division or sign anywhere.
     */
    fun hashBits(column: Int, row: Int): Int {
        var bits = (column * COLUMN_PRIME) xor (row * ROW_PRIME)
        bits = bits xor (bits ushr 15)
        bits *= -2048144789 // 0x85EBCA6B
        bits = bits xor (bits ushr 13)
        bits *= -1028477387 // 0xC2B2AE35
        bits = bits xor (bits ushr 16)
        return bits
    }

    /**
     * The two large primes a lattice cell's column and row are mixed with before the avalanche.
     *
     * Teschner and others' spatial hash. Two neighbouring cells differ by a whole prime rather
     * than by one, which is what stops a lattice's marks falling into diagonal rows.
     */
    private const val COLUMN_PRIME = 73856093
    private const val ROW_PRIME = 19349663

    /**
     * Twelve bits of a hash from [shift] upward, as a fraction in 0..1.
     *
     * Divided by a power of two, so the fraction is exact on any hardware that rounds at all and
     * the two paths place a stroke's seed on precisely the same spot.
     */
    fun unitFrom(bits: Int, shift: Int): Float =
        ((bits ushr shift) and TWELVE_BITS).toFloat() / TWELVE_BITS_PLUS_ONE

    /**
     * Where in a hash the x and the y nudge are read from.
     *
     * Twelve bits each, twelve apart, so the two never share a bit and one mark's two coordinates
     * are independent. Eight rather than nought because the avalanche's last step is an exclusive
     * or with a right shift of sixteen, which leaves the very lowest bits the least mixed.
     */
    private const val HASH_SHIFT_X = 8
    private const val HASH_SHIFT_Y = 20

    private const val TWELVE_BITS = 0xFFF
    private const val TWELVE_BITS_PLUS_ONE = 4096f

    /**
     * How far off its lattice cell's corner a hachure's seed may sit, as a fraction of the pitch:
     * from a quarter in, over the middle half.
     *
     * Enough to break the grid — the strokes have to look placed rather than ruled — and no more,
     * because a seed that could reach its cell's edge could put a stroke wholly inside its
     * neighbour, and the nine-cell neighbourhood [hachure] searches would then miss it.
     */
    private const val NUDGE_FROM = 0.25f
    private const val NUDGE_SPAN = 0.5f

    /**
     * The same for a stipple dot: from three tenths in, over the middle two fifths.
     *
     * Tighter than a hachure's, and for a stronger reason than looks: a dot together with its soft
     * edge has to stay clear of its cell's own boundary, so that a pixel one side of a boundary is
     * outside every dot the other side of it and the processor and the graphics card cannot draw a
     * different dot even where they disagree about which cell the pixel is in.
     */
    private const val DOT_NUDGE_FROM = 0.3f
    private const val DOT_NUDGE_SPAN = 0.4f

    /** The hundred [EngravingPlan.BORDER_DUTY_PERCENT] is a percentage of. */
    private const val A_HUNDRED = 100
}

/**
 * Euclidean distance from every cell to the nearest cell of a mask, in cells.
 *
 * The engraving reads it twice: out at sea it is the distance to the coast, which is what the
 * vignette's lines follow, and inside a lake it is the distance to that lake's shore, which is
 * what the water lines fade with. Both come from one field seeded on dry land, because a lake is
 * surrounded by dry land and the sea is bounded by it.
 *
 * Exact rather than approximate, and that is the whole reason this is here rather than a chamfer
 * transform: a chamfer can only step along the eight directions a square grid offers, so its
 * contours are octagons, and a vignette drawn from octagonal contours has visible corners in the
 * open sea where the coast has none. The algorithm is Felzenszwalb and Huttenlocher's separable
 * transform — a scan down each column for the vertical distance, then a lower envelope of
 * parabolas along each row — which is linear in the number of cells and gives the true Euclidean
 * distance, not a metric that resembles it.
 *
 * The east-west axis wraps as the map does. The row pass runs over three copies of the row and
 * keeps the middle one, which is the cheapest exact way to let a cell near one edge find a source
 * near the other.
 *
 * Computed once on the processor and uploaded, like every colour table the accelerator is handed:
 * a field computed twice, once on each device, would put the vignette's lines a pixel apart.
 */
internal object ShoreDistance {

    /** Reported where the mask is empty, so a world with no land at all still draws. */
    const val UNREACHED: Float = 1e18f

    /** @param source 1 where a cell is a source, 0 elsewhere. */
    fun of(cellsAcross: Int, cellsDown: Int, source: ByteArray): FloatArray {
        val cellCount = cellsAcross * cellsDown
        val distance = FloatArray(cellCount)
        if (cellCount == 0) return distance

        // Larger than any squared distance a grid this size can hold, and finite, so the envelope
        // below can do arithmetic on it without meeting an infinity.
        val unreachableSquared =
            4.0 * (cellsAcross.toDouble() * cellsAcross + cellsDown.toDouble() * cellsDown)
        // Small enough that adding one all the way down a column cannot overflow.
        val noSourceInColumn = Int.MAX_VALUE / 4

        // First pass: how far each cell is from the nearest source in its own column, by one scan
        // down and one back up.
        val downColumn = IntArray(cellCount)
        for (column in 0 until cellsAcross) {
            var nearestRows = noSourceInColumn
            var cell = column
            for (row in 0 until cellsDown) {
                nearestRows = when {
                    source[cell].toInt() != 0 -> 0
                    nearestRows == noSourceInColumn -> noSourceInColumn
                    else -> nearestRows + 1
                }
                downColumn[cell] = nearestRows
                cell += cellsAcross
            }
            nearestRows = noSourceInColumn
            cell = column + (cellsDown - 1) * cellsAcross
            for (row in cellsDown - 1 downTo 0) {
                nearestRows = when {
                    source[cell].toInt() != 0 -> 0
                    nearestRows == noSourceInColumn -> noSourceInColumn
                    else -> nearestRows + 1
                }
                if (nearestRows < downColumn[cell]) downColumn[cell] = nearestRows
                cell -= cellsAcross
            }
        }

        // Second pass, along each row: the lower envelope of one parabola per column, rooted at
        // that column's vertical distance. Three copies of the row wide, so a cell near one edge
        // can find a source near the other and the middle copy is the answer.
        val scanWidth = cellsAcross * COPIES_OF_EACH_ROW
        val rootedAt = DoubleArray(scanWidth)
        val parabola = IntArray(scanWidth)
        val envelopeEdge = DoubleArray(scanWidth + 1)
        for (row in 0 until cellsDown) {
            val rowStart = row * cellsAcross
            for (sample in 0 until scanWidth) {
                val rows = downColumn[rowStart + sample % cellsAcross]
                rootedAt[sample] =
                    if (rows >= noSourceInColumn) unreachableSquared
                    else rows.toDouble() * rows
            }

            var top = 0
            parabola[0] = 0
            envelopeEdge[0] = -BEYOND_THE_SCAN
            envelopeEdge[1] = BEYOND_THE_SCAN
            for (sample in 1 until scanWidth) {
                var meeting = intersection(rootedAt, parabola[top], sample)
                while (meeting <= envelopeEdge[top]) {
                    top--
                    meeting = intersection(rootedAt, parabola[top], sample)
                }
                top++
                parabola[top] = sample
                envelopeEdge[top] = meeting
                envelopeEdge[top + 1] = BEYOND_THE_SCAN
            }

            top = 0
            for (column in 0 until cellsAcross) {
                val sample = cellsAcross + column
                while (envelopeEdge[top + 1] < sample) top++
                val acrossCells = (sample - parabola[top]).toDouble()
                val squared = acrossCells * acrossCells + rootedAt[parabola[top]]
                distance[rowStart + column] =
                    if (squared >= unreachableSquared) UNREACHED else sqrt(squared).toFloat()
            }
        }
        return distance
    }

    /** Where the parabolas rooted at columns [left] and [right] cross. */
    private fun intersection(rootedAt: DoubleArray, left: Int, right: Int): Double {
        val leftColumn = left.toDouble()
        val rightColumn = right.toDouble()
        return ((rootedAt[right] + rightColumn * rightColumn) -
            (rootedAt[left] + leftColumn * leftColumn)) /
            (2.0 * rightColumn - 2.0 * leftColumn)
    }

    /**
     * How many copies of a row the envelope is solved over, to let the east-west axis wrap.
     *
     * Three: the row itself with one copy either side of it, so a cell in the middle copy can
     * reach a source anywhere in the row whichever way round it lies. It is the cheapest exact
     * way to wrap a separable transform.
     */
    private const val COPIES_OF_EACH_ROW = 3

    /** Further out than any sample of the scan, so the first and last envelope edges bound it. */
    private const val BEYOND_THE_SCAN = 1e30
}
