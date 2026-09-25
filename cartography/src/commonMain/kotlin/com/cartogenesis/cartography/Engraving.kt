package com.cartogenesis.cartography

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The stroke geometry of an engraved map: how big each mark of the pen is, in pixels of the sheet.
 *
 * In pixels, and that is the whole of the idea. A pen does not grow with the sheet. An engraver
 * handed a plate twice the size does not draw the same picture twice as large; he draws the same
 * hachure with the same nib and fits four times as many strokes on it, so the larger plate carries
 * more of the country rather than a bigger version of less of it. Every figure here is therefore a
 * fixed count of the sheet's pixels at every resolution, and what grows with the grid is the
 * *number* of marks — as the square of the grid ratio, so a 2048 render lays sixteen strokes over
 * the ground a 512 render gives one.
 *
 * Sizing them as a share of the width instead was tried first and reviewed: at 2048 the hachures
 * came out as black dashes thirty pixels long and the ice stipple as polka dots, which is a woodcut
 * and not an engraving.
 *
 * The pixels are the true-shape sheet's ([SheetGeometry]), where one covers the same ground both
 * ways, so a stroke, a dot or a ruling is laid out there at its true direction and length. The
 * raster still decides one colour a cell: each cell asks the pattern about the sheet pixel at its
 * own top-left corner ([sheetX], [sheetY]) and every pixel of the cell takes the answer. On a cell
 * two pixels wide a mark running north-south therefore comes out two pixels wide and one running
 * east-west one pixel tall; that is the most a cell can say, and the placing and the bearing of
 * every mark are still the sheet's.
 *
 * Only three figures depend on the grid, and none of them is a mark: how many lattice columns fit
 * across the sheet, which is what lets the strokes meet at the date line; how many cells the
 * central difference reaches, which is a lattice pitch of the sheet counted in each axis's cells;
 * and what that difference has to be multiplied by to mean the same slope at any resolution.
 *
 * Built once per render and handed to both paths — the rasteriser reads it directly, the graphics
 * card gets the same numbers as uniforms (see [RasterRecipe.engraving]) — so neither can derive a
 * different pitch from the same grid.
 */
class EngravingPlan(sheet: SheetGeometry) {

    /** How many sheet pixels one cell spans east-west: where a column's pattern is asked. */
    val pixelsPerCellAcross: Int = sheet.pixelsPerCellAcross

    /** How many sheet pixels one cell spans north-south: where a row's pattern is asked. */
    val pixelsPerCellDown: Int = sheet.pixelsPerCellDown

    /** The sheet pixel a cell in [column] asks the pattern about: its left-hand pixel. */
    fun sheetX(column: Int): Int = column * pixelsPerCellAcross

    /** The sheet pixel a cell in [row] asks the pattern about: its top pixel. */
    fun sheetY(row: Int): Int = row * pixelsPerCellDown

    /**
     * Side of the lattice one stroke is drawn on, in sheet pixels.
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
    val hachureLatticePixels: Int = HACHURE_LATTICE_PIXELS

    /**
     * How many lattice cells fit across the sheet.
     *
     * The east-west axis wraps, so the hash that places a stroke is taken on the column index
     * modulo this and the pattern meets itself at the date line instead of showing a seam. Exact
     * whenever the sheet's width divides by the pitch, which every power of two does.
     */
    val hachureLatticeColumns: Int = (sheet.widthPixels / hachureLatticePixels).coerceAtLeast(1)

    /** Half the length of one stroke, down the slope: three quarters of the lattice pitch. */
    val strokeHalfLengthPixels: Float = HACHURE_LATTICE_PIXELS * STROKE_HALF_LENGTH_SHARE_OF_PITCH

    /**
     * Half the width of one stroke at full steepness, across the slope.
     *
     * A hairline, and deliberately: Lehmann's rule is carried by how black a stroke runs and by how
     * many of them there are, not by how fat each one is. The width is this times the steepness, so
     * flat ground draws nothing and a cliff draws a two-pixel line.
     */
    val strokeHalfWidthPixels: Float = 1f

    /**
     * How far the central difference that gives the slope and the aspect reaches, in columns and
     * in rows.
     *
     * One lattice pitch of the sheet, which is the physically right figure: a drawing whose strokes
     * are a pitch apart cannot express a change of direction finer than a pitch, and reading the
     * aspect off a single pair of neighbouring cells on eroded ground gives a direction that changes
     * every cell. Counted in each axis's own cells, so the reach is the same ground both ways — on
     * cells two pixels wide, four columns east and west and eight rows north and south.
     */
    val gradientStencilColumns: Int =
        (HACHURE_LATTICE_PIXELS / sheet.pixelsPerCellAcross).coerceAtLeast(1)
    val gradientStencilRows: Int =
        (HACHURE_LATTICE_PIXELS / sheet.pixelsPerCellDown).coerceAtLeast(1)

    /**
     * What each axis's central difference is multiplied by to become a slope: per cell width along
     * a row, and per row down a column (which `Engraving.hachure` then puts on the ground).
     *
     * `ReliefShading.slopeScale` over the stencil's reach in that axis. The scale is the one figure
     * that has to grow with the grid, and for the opposite reason to everything else here: a cell
     * of a 2048 grid covers a quarter of the ground a cell of a 512 grid covers, so without it the
     * same hillside would read four times flatter on the larger plate and take four times less ink.
     */
    val gradientScaleAcross: Float =
        ReliefShading.slopeScale(sheet.cellsAcross) / gradientStencilColumns
    val gradientScaleDown: Float =
        ReliefShading.slopeScale(sheet.cellsAcross) / gradientStencilRows

    /**
     * Where the first coastal line sits, in pixels from the shore.
     *
     * Line `k` sits at `base * (k + 1) * (k + 2) / 2`, so the four lines fall at one, three, six
     * and ten times this — four, twelve, twenty-four and forty pixels — and the gap between them
     * widens the way an engraver's does.
     */
    val vignetteBasePixels: Float = 4f

    /** Half the thickness of a coastal line. */
    val vignetteHalfWidthPixels: Float = 0.6f

    /** How many lines follow the coast out to sea. Engravers drew three to five. */
    val vignetteLineCount: Int = 4

    /** How far the solid shore ink reaches out over the water from the coast. */
    val shoreInkPixels: Float = 1.5f

    /**
     * How far the same firm ink reaches in from a lake's bank.
     *
     * Thinner than the coast's, because a lake is a small thing and at the coast's width a narrow
     * one would come out as a solid blot, which is one of the things this style was redrawn to stop
     * doing. Lakes narrower than twice this still fill, and at that size an engraver filled them too.
     */
    val lakeRimPixels: Float = 1.2f

    /** Distance between the horizontal water lines inside a lake. */
    val lakeLinePitchPixels: Float = 6f

    /** Half the thickness of one water line. */
    val lakeLineHalfWidthPixels: Float = 0.45f

    /**
     * How far from the bank a lake's water lines have faded to nothing.
     *
     * Eight rulings' worth. A lake wider than twice this keeps a blank middle, which is what an
     * engraver left: the ruling says "water" at the bank rather than filling the basin.
     */
    val lakeFadePixels: Float = 48f

    /** Distance between stipple dots on the ice. */
    val stipplePitchPixels: Int = 6

    /** Radius of one stipple dot. Small enough that a dot always sits clear of its cell's edges. */
    val stippleRadiusPixels: Float = 1f

    /** Side of the block a dotted border is broken into. */
    val borderDashPixels: Int = 4

    companion object {

        /**
         * Side of the hachure lattice, in sheet pixels.
         *
         * Eight, which is what the 512 plate was drawn at and reviewed at. Every other mark here is
         * this or a fraction of it, and none of them changes with the size of the sheet.
         */
        const val HACHURE_LATTICE_PIXELS: Int = 8

        /** A stroke's half length against the lattice pitch: three quarters, so neighbours meet. */
        private const val STROKE_HALF_LENGTH_SHARE_OF_PITCH = 0.75f

        /**
         * How wide the soft edge of a drawn line is, in pixels.
         *
         * A pen leaves a soft edge, and a hard one-bit threshold reads as a dither rather than as
         * ink. It is also what keeps the two paths within a channel of each other: a hard threshold
         * on a square root flips whole pixels where the two hardwares round differently, and a ramp
         * moves them by one step instead.
         */
        const val ANTIALIAS_PIXELS: Float = 0.6f

        /**
         * The slope below which the ground is left blank, in the same units
         * [EngravingPlan.gradientScaleAcross] produces, on the ground.
         *
         * Lehmann's rule is that a hachure map leaves the flat blank and darkens with the slope, so
         * there has to be a figure for flat. Set from the ground rather than by eye: it is the tenth
         * percentile of the land slope of seed 234475 measured at this stencil, so a tenth of the
         * land — the deltas, the basin floors, the coastal plain — takes no ink at all. It was 0.07
         * until the incision became the stream-power law's implicit update, whose ground is rougher:
         * the tenth percentile reads 0.084 there (docs/DESIGN_LEDGER.md, Fix 3b).
         */
        const val SLOPE_FLOOR: Float = 0.08f

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
 * Every position here is a pixel of the true-shape sheet, and every length is in those pixels; see
 * [EngravingPlan] for how a cell of the raster is placed on it.
 *
 * Three properties are load-bearing and worth stating, because they are what lets the same picture
 * come off a graphics card whose square root and divide round differently from the JVM's:
 *
 *  - **Nothing is a hard threshold on a rounded quantity.** Every edge is a [smoothstep] across
 *    [EngravingPlan.ANTIALIAS_PIXELS], so a last-bit difference in a phase moves a channel by one
 *    step rather than flipping a pixel from paper to ink.
 *  - **Nothing quantised can change the answer.** Where an index has to be worked out — which
 *    lattice cell a pixel is in, which stipple cell — the mark it selects sits far enough inside
 *    that cell that a pixel on the boundary is covered identically whichever side it is counted on,
 *    so the two paths may disagree about the index and still draw the same pixel.
 *  - **What can be integer is integer.** The stipple's dot centres and the border's dashes are
 *    whole pixels, hashed with 32-bit wrapping arithmetic that Kotlin and GLSL agree on to the bit.
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
     * [gradientX] is the difference along a row, per cell width of ground, and [gradientY] the one
     * down a column, per row; a row is [cellHeightInCellWidths] of a cell width on the ground.
     *
     * The nine cells around the pixel are enough, and that is a property worth keeping: a stroke
     * reaches at most [EngravingPlan.strokeHalfLengthPixels] plus its half width from its own seed,
     * which is under one and a quarter lattice cells, and a seed two cells away is at least that
     * far. It is also what makes the two paths agree — the cell index is the only quantised thing
     * here, and a pixel on a cell boundary gets the same answer from either side of it, because the
     * cells the two neighbourhoods differ by cannot reach it.
     *
     * @return 0 for blank paper, 1 for solid ink.
     */
    fun hachure(
        sheetX: Int,
        sheetY: Int,
        gradientX: Float,
        gradientY: Float,
        cellHeightInCellWidths: Float,
        plan: EngravingPlan,
        inkGain: Float
    ): Float {
        // The ground's slope, which is what the stroke's weight says: down a column a step is a
        // row, a share of a cell width, so the fall per cell width of ground there is the fall per
        // row divided by that share.
        val southwardOnTheGround = gradientY / cellHeightInCellWidths
        val slope = sqrt(gradientX * gradientX + southwardOnTheGround * southwardOnTheGround)
        val steepness =
            ((slope - EngravingPlan.SLOPE_FLOOR) * inkGain).coerceIn(0f, 1f)
        if (steepness <= 0f) return 0f

        // And the ground's fall line, which is the stroke's direction: the sheet draws the ground
        // at its true shape, so the bearing on the ground is the bearing on the sheet.
        val perSlope = 1f / slope
        val downhillX = gradientX * perSlope
        val downhillY = southwardOnTheGround * perSlope

        val pitch = plan.hachureLatticePixels
        val latticeColumns = plan.hachureLatticeColumns
        val halfLength = plan.strokeHalfLengthPixels
        val halfWidth = plan.strokeHalfWidthPixels * steepness
        val softEdge = EngravingPlan.ANTIALIAS_PIXELS
        val hereColumn = sheetX / pitch
        val hereRow = sheetY / pitch

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
                val fromSeedX = sheetX - seedX
                val fromSeedY = sheetY - seedY
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
     * rather than as the paper it is printed on. [shorePixels] is the distance from the nearest dry
     * land, in sheet pixels; inside [EngravingPlan.shoreInkPixels] the water is the shore's own
     * solid ink.
     */
    fun coastalWater(shorePixels: Float, plan: EngravingPlan): Float {
        if (shorePixels < plan.shoreInkPixels) return 1f

        // The nth line sits at base * (n+1)(n+2)/2; this inverts that triangular series, so the
        // whole number part of `line` names the nearest line and the fraction says how far past
        // it this pixel is. The quarter under the root completes the square of the inversion.
        val line = floor(
            sqrt(2f * shorePixels / plan.vignetteBasePixels + 0.25f) - 1f
        )
        if (line < 0f || line >= plan.vignetteLineCount) return 0f

        val centre = plan.vignetteBasePixels * (line + 1f) * (line + 2f) * 0.5f
        val coverage = 1f - smoothstep(
            plan.vignetteHalfWidthPixels - EngravingPlan.ANTIALIAS_PIXELS,
            plan.vignetteHalfWidthPixels + EngravingPlan.ANTIALIAS_PIXELS,
            abs(shorePixels - centre)
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
     * [sheetY] is the sheet pixel row and [shorePixels] the distance to the bank in sheet pixels.
     */
    fun lakeWater(sheetY: Int, shorePixels: Float, plan: EngravingPlan): Float {
        if (shorePixels < plan.lakeRimPixels) return 1f

        val pitch = plan.lakeLinePitchPixels
        val linesDown = sheetY / pitch
        // The rulings are centred half a pitch into each period, so the distance to the nearest
        // one is how far this row's fraction of a period sits from the middle of it.
        val fromLine = abs(linesDown - floor(linesDown) - 0.5f) * pitch
        val coverage = 1f - smoothstep(
            plan.lakeLineHalfWidthPixels - EngravingPlan.ANTIALIAS_PIXELS,
            plan.lakeLineHalfWidthPixels + EngravingPlan.ANTIALIAS_PIXELS,
            fromLine
        )
        val fade = (1f - shorePixels / plan.lakeFadePixels).coerceIn(0f, 1f)
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
    fun stipple(sheetX: Int, sheetY: Int, plan: EngravingPlan): Float {
        val pitch = plan.stipplePitchPixels
        val column = sheetX / pitch
        val row = sheetY / pitch
        val bits = hashBits(column, row)
        val centreX = column * pitch +
            pitch * (DOT_NUDGE_FROM + DOT_NUDGE_SPAN * unitFrom(bits, HASH_SHIFT_X))
        val centreY = row * pitch +
            pitch * (DOT_NUDGE_FROM + DOT_NUDGE_SPAN * unitFrom(bits, HASH_SHIFT_Y))
        val fromCentreX = sheetX - centreX
        val fromCentreY = sheetY - centreY
        val distance = sqrt(fromCentreX * fromCentreX + fromCentreY * fromCentreY)
        return 1f - smoothstep(
            plan.stippleRadiusPixels - EngravingPlan.ANTIALIAS_PIXELS,
            plan.stippleRadiusPixels + EngravingPlan.ANTIALIAS_PIXELS,
            distance
        )
    }

    /**
     * Whether a border cell takes ink, so the boundary reads as a dotted line rather than a solid.
     *
     * The sheet is cut into blocks and a hashed majority of them take ink, so a border running in
     * any direction is broken at irregular intervals — which is what a boundary drawn by hand looks
     * like, and what a regular dash pattern cannot manage for a line that turns.
     */
    fun borderDot(sheetX: Int, sheetY: Int, plan: EngravingPlan): Boolean {
        val block = plan.borderDashPixels
        val bits = hashBits(sheetX / block, sheetY / block)
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
 * Euclidean distance from every cell to the nearest cell of a mask, in pixels of the true-shape
 * sheet.
 *
 * The engraving reads it twice: out at sea it is the distance to the coast, which is what the
 * vignette's lines follow, and inside a lake it is the distance to that lake's shore, which is
 * what the water lines fade with. Both come from one field seeded on dry land, because a lake is
 * surrounded by dry land and the sea is bounded by it.
 *
 * Measured on the sheet rather than in cells, because a cell is not the same size both ways: a
 * column is [SheetGeometry.pixelsPerCellAcross] pixels wide and a row
 * [SheetGeometry.pixelsPerCellDown] tall, and a vignette whose lines stood so many *cells* out
 * would stand twice as far off an east coast as off a north one. From cell centre to cell centre,
 * which is where the raster asks.
 *
 * Exact rather than approximate, and that is the whole reason this is here rather than a chamfer
 * transform: a chamfer can only step along the eight directions a square grid offers, so its
 * contours are octagons, and a vignette drawn from octagonal contours has visible corners in the
 * open sea where the coast has none. The algorithm is Felzenszwalb and Huttenlocher's separable
 * transform — a scan down each column for the vertical distance, then a lower envelope of
 * parabolas along each row — which is linear in the number of cells and gives the true Euclidean
 * distance, not a metric that resembles it; the two axes' spacings enter it as the parabolas'
 * roots and positions, which the transform allows as it stands.
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

    /**
     * @param source 1 where a cell is a source, 0 elsewhere, one entry per cell of [sheet]'s grid.
     */
    fun of(sheet: SheetGeometry, source: ByteArray): FloatArray {
        val cellsAcross = sheet.cellsAcross
        val cellsDown = sheet.cellsDown
        val columnPixels = sheet.pixelsPerCellAcross.toDouble()
        val rowPixels = sheet.pixelsPerCellDown.toDouble()
        val cellCount = cellsAcross * cellsDown
        val distance = FloatArray(cellCount)
        if (cellCount == 0) return distance

        // Larger than any squared distance a sheet this size can hold, and finite, so the envelope
        // below can do arithmetic on it without meeting an infinity.
        val widthPixels = cellsAcross * columnPixels
        val heightPixels = cellsDown * rowPixels
        val unreachableSquared = 4.0 * (widthPixels * widthPixels + heightPixels * heightPixels)
        // Small enough that adding one all the way down a column cannot overflow.
        val noSourceInColumn = Int.MAX_VALUE / 4

        // First pass: how many rows each cell is from the nearest source in its own column, by one
        // scan down and one back up.
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
        // that column's vertical distance in pixels and standing at its position in pixels. Three
        // copies of the row wide, so a cell near one edge can find a source near the other and the
        // middle copy is the answer.
        val scanWidth = cellsAcross * COPIES_OF_EACH_ROW
        val rootedAt = DoubleArray(scanWidth)
        val parabola = IntArray(scanWidth)
        val envelopeEdge = DoubleArray(scanWidth + 1)
        for (row in 0 until cellsDown) {
            val rowStart = row * cellsAcross
            for (sample in 0 until scanWidth) {
                val rows = downColumn[rowStart + sample % cellsAcross]
                val downPixels = rows * rowPixels
                rootedAt[sample] =
                    if (rows >= noSourceInColumn) unreachableSquared
                    else downPixels * downPixels
            }

            var top = 0
            parabola[0] = 0
            envelopeEdge[0] = -BEYOND_THE_SCAN
            envelopeEdge[1] = BEYOND_THE_SCAN
            for (sample in 1 until scanWidth) {
                var meeting = intersection(rootedAt, parabola[top], sample, columnPixels)
                while (meeting <= envelopeEdge[top]) {
                    top--
                    meeting = intersection(rootedAt, parabola[top], sample, columnPixels)
                }
                top++
                parabola[top] = sample
                envelopeEdge[top] = meeting
                envelopeEdge[top + 1] = BEYOND_THE_SCAN
            }

            top = 0
            for (column in 0 until cellsAcross) {
                val sample = cellsAcross + column
                val atPixels = sample * columnPixels
                while (envelopeEdge[top + 1] < atPixels) top++
                val acrossPixels = (sample - parabola[top]) * columnPixels
                val squared = acrossPixels * acrossPixels + rootedAt[parabola[top]]
                distance[rowStart + column] =
                    if (squared >= unreachableSquared) UNREACHED else sqrt(squared).toFloat()
            }
        }
        return distance
    }

    /**
     * Where the parabolas rooted at samples [left] and [right] cross, in pixels along the row,
     * the samples standing [columnPixels] apart.
     */
    private fun intersection(
        rootedAt: DoubleArray,
        left: Int,
        right: Int,
        columnPixels: Double
    ): Double {
        val leftAt = left * columnPixels
        val rightAt = right * columnPixels
        return ((rootedAt[right] + rightAt * rightAt) - (rootedAt[left] + leftAt * leftAt)) /
            (2.0 * rightAt - 2.0 * leftAt)
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
