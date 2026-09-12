package com.cartogenesis.cartography

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The stroke geometry of an engraved map, in cells, for one map width.
 *
 * Everything a pen draws has a size, and on a map that size is a share of the sheet rather than a
 * count of pixels: a hachure pitch of four pixels on a 512-cell map and of sixteen on a 2048-cell
 * one are the *same* pitch, and a reader looking at the two pictures side by side sees one drawing
 * at two magnifications. So every length here is derived from the width and none of them is a
 * constant number of cells. The exceptions are the softness figures, which are antialiasing and so
 * belong to the pixel grid rather than to the map.
 *
 * Built once per render and handed to both paths — the rasteriser reads it directly, the graphics
 * card gets the same numbers as uniforms (see [RasterRecipe.engraving]) — so neither can derive a
 * different pitch from the same width.
 */
class EngravingPlan(width: Int) {

    /**
     * Side of the lattice one stroke is drawn on, in cells.
     *
     * The strokes are placed rather than combed. Every lattice cell holds one stroke, nudged off
     * the cell's centre by a hash of the cell so the field does not read as a grid, and a pixel
     * asks the nine cells around it whether any of their strokes covers it. That is the whole of
     * the arrangement, and it is what makes the drawing possible at all: a stripe field whose phase
     * is the pixel's position projected onto the local contour cannot work, because the position is
     * hundreds of cells from the origin and a single degree of turn in the aspect then slides the
     * phase by a whole stripe. Measured, before this was replaced: the ink came out as isolated
     * specks with no direction in it at all, and the guard below scored it 42.9 degrees against a
     * random bearing's 44.8. Anchoring each stroke to its own lattice cell bounds the arm at one
     * cell, so a turn in the aspect bends a stroke instead of shattering the field.
     */
    val hachureLatticeCells: Int = ((width + 32) / 64).coerceAtLeast(4)

    /**
     * How many lattice cells fit across the map.
     *
     * The east-west axis wraps, so the hash that places a stroke is taken on the column index
     * modulo this and the pattern meets itself at the date line instead of showing a seam.
     */
    val hachureLatticeColumns: Int = (width / hachureLatticeCells).coerceAtLeast(1)

    /** Half the length of one stroke, down the slope. */
    val strokeHalfLengthCells: Float = hachureLatticeCells * 0.75f

    /**
     * Half the width of one stroke at full steepness, across the slope.
     *
     * Lehmann's rule lives here: the width is this times the steepness, so flat ground draws
     * nothing and a cliff draws the widest stroke the lattice can hold without its neighbours
     * merging into a black mass.
     */
    val strokeHalfWidthCells: Float = hachureLatticeCells * 0.16f

    /**
     * How far the central difference that gives the slope and the aspect reaches, in cells.
     *
     * One lattice cell, which is both the resolution-independent figure and the physically right
     * one: a drawing whose strokes are a cell apart cannot express a change of direction finer than
     * a cell, and reading the aspect off a single pair of neighbouring cells on eroded ground gives
     * a direction that changes every cell.
     */
    val gradientStencilCells: Int = hachureLatticeCells

    /**
     * What the central difference is multiplied by to become a slope.
     *
     * `MapRasterizer.hillshadeScale` over the stencil's reach, so the number is the same physical
     * gradient the hillshade sees, measured over a longer baseline, and does not change with
     * resolution.
     */
    val gradientScale: Float = MapRasterizer.hillshadeScale(width) / gradientStencilCells

    /**
     * Where the first coastal line sits, in cells from the shore.
     *
     * Line `k` sits at `base * (k + 1) * (k + 2) / 2`, so the four lines fall at one, three, six
     * and ten times this and the gap between them widens the way an engraver's does.
     */
    val vignetteBaseCells: Float = width / 170f

    /** Half the thickness of a coastal line. */
    val vignetteHalfWidthCells: Float = width / 900f

    /** How many lines follow the coast out to sea. Engravers drew three to five. */
    val vignetteLineCount: Int = 4

    /** How far the solid shore ink reaches out over the water from the coast. */
    val shoreInkCells: Float = (width / 341f).coerceAtLeast(1.5f)

    /**
     * How far the same firm ink reaches in from a lake's bank.
     *
     * Half the coast's, because a lake is a small thing: at the coast's width every lake narrower
     * than a dozen cells would come out as a solid blot, which is one of the things this style was
     * redrawn to stop doing. Lakes narrower than twice this still fill, and at that size an
     * engraver filled them too.
     */
    val lakeRimCells: Float = (width / 682f).coerceAtLeast(1.2f)

    /** Distance between the horizontal water lines inside a lake. */
    val lakeLinePitchCells: Float = width / 150f

    /** Half the thickness of one water line. */
    val lakeLineHalfWidthCells: Float = width / 1100f

    /** How far from the shore a lake's water lines have faded to nothing. */
    val lakeFadeCells: Float = width / 26f

    /** Distance between stipple dots on the ice, in cells. Whole cells: the dots sit on integers. */
    val stipplePitchCells: Int = (width / 60).coerceAtLeast(4)

    /** Radius of one stipple dot. Kept under a quarter of the pitch so a dot fits inside its cell. */
    val stippleRadiusCells: Float = width / 420f

    /** Side of the block a dotted border is broken into. */
    val borderDashCells: Int = (width / 170).coerceAtLeast(1)

    companion object {

        /**
         * How wide the soft edge of a drawn line is, in cells.
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
         * The steepness at which a stroke is fully black, as a fraction of the way from the slope
         * floor to the steepest ground. Below it the stroke is grey, which is what a light hand on
         * gentle ground looks like.
         */
        const val FULL_INK_AT: Float = 0.34f

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
        x: Int,
        y: Int,
        gradientX: Float,
        gradientY: Float,
        plan: EngravingPlan,
        inkGain: Float
    ): Float {
        val slope = sqrt(gradientX * gradientX + gradientY * gradientY)
        val steepness =
            ((slope - EngravingPlan.SLOPE_FLOOR) * inkGain).coerceIn(0f, 1f)
        if (steepness <= 0f) return 0f

        val inverse = 1f / slope
        val downhillX = gradientX * inverse
        val downhillY = gradientY * inverse

        val pitch = plan.hachureLatticeCells
        val columns = plan.hachureLatticeColumns
        val halfLength = plan.strokeHalfLengthCells
        val halfWidth = plan.strokeHalfWidthCells * steepness
        val soft = EngravingPlan.ANTIALIAS_CELLS
        val cellX = x / pitch
        val cellY = y / pitch

        var strongest = 0f
        for (offsetY in -1..1) {
            for (offsetX in -1..1) {
                val column = cellX + offsetX
                val row = cellY + offsetY
                val bits = hashBits(column.mod(columns), row)
                val seedX = column * pitch + pitch * (0.25f + 0.5f * unitFrom(bits, 8))
                val seedY = row * pitch + pitch * (0.25f + 0.5f * unitFrom(bits, 20))
                val awayX = x - seedX
                val awayY = y - seedY
                val along = abs(awayX * downhillX + awayY * downhillY)
                val across = abs(awayX * -downhillY + awayY * downhillX)
                val coverage =
                    (1f - smoothstep(halfLength - soft, halfLength + soft, along)) *
                        (1f - smoothstep(halfWidth - soft, halfWidth + soft, across))
                if (coverage > strongest) strongest = coverage
            }
        }

        val darkness = (steepness / EngravingPlan.FULL_INK_AT).coerceAtMost(1f)
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

        // Line k sits at base * (k+1)(k+2)/2; this inverts that, so the whole number part of
        // `band` names the nearest line and the fractional part says how far past it we are.
        val band = floor(
            sqrt(2f * shoreCells / plan.vignetteBaseCells + 0.25f) - 1f
        )
        if (band < 0f || band >= plan.vignetteLineCount) return 0f

        val centre = plan.vignetteBaseCells * (band + 1f) * (band + 2f) * 0.5f
        val coverage = 1f - smoothstep(
            plan.vignetteHalfWidthCells - EngravingPlan.ANTIALIAS_CELLS,
            plan.vignetteHalfWidthCells + EngravingPlan.ANTIALIAS_CELLS,
            abs(shoreCells - centre)
        )
        return coverage * (1f - band / plan.vignetteLineCount)
    }

    /**
     * A lake: a firm shoreline and horizontal water lines inside it, fading toward the middle.
     *
     * The lake itself is left as paper — a grey blot is a colour decision and this style has no
     * colour to spend — so the water is described the way an engraver describes it, by ruling it.
     */
    fun lakeWater(y: Int, shoreCells: Float, plan: EngravingPlan): Float {
        if (shoreCells < plan.lakeRimCells) return 1f

        val pitch = plan.lakeLinePitchCells
        val phase = y / pitch
        val fromLine = abs(phase - floor(phase) - 0.5f) * pitch
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
     * Whole-cell arithmetic on purpose. A dot's centre is an integer pair, so the only rounded
     * quantity in the whole figure is the square root of an exact integer, and the two paths cannot
     * put a dot in different places. The nudge keeps the dot clear of its cell's edges, so a pixel
     * on a boundary is outside every dot whichever cell it is counted in.
     */
    fun stipple(x: Int, y: Int, plan: EngravingPlan): Float {
        val pitch = plan.stipplePitchCells
        val cellX = x / pitch
        val cellY = y / pitch
        val bits = hashBits(cellX, cellY)
        val spread = (pitch / 2).coerceAtLeast(1)
        val centreX = cellX * pitch + pitch / 4 + (bits ushr 8 and 0xFFF) % spread
        val centreY = cellY * pitch + pitch / 4 + (bits ushr 20 and 0xFFF) % spread
        val dx = (x - centreX).toFloat()
        val dy = (y - centreY).toFloat()
        val distance = sqrt(dx * dx + dy * dy)
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
    fun borderDot(x: Int, y: Int, plan: EngravingPlan): Boolean {
        val block = plan.borderDashCells
        val bits = hashBits(x / block, y / block)
        return (bits ushr 8) % 100 < EngravingPlan.BORDER_DUTY_PERCENT
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
    fun hashBits(a: Int, b: Int): Int {
        var h = (a * 73856093) xor (b * 19349663)
        h = h xor (h ushr 15)
        h *= -2048144789 // 0x85EBCA6B
        h = h xor (h ushr 13)
        h *= -1028477387 // 0xC2B2AE35
        h = h xor (h ushr 16)
        return h
    }

    /**
     * Twelve bits of a hash from [shift] upward, as a fraction in 0..1.
     *
     * Divided by a power of two, so the fraction is exact on any hardware that rounds at all and
     * the two paths place a stroke's seed on precisely the same spot.
     */
    fun unitFrom(bits: Int, shift: Int): Float =
        ((bits ushr shift) and 0xFFF).toFloat() / 4096f

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
    fun of(width: Int, height: Int, source: ByteArray): FloatArray {
        val cells = width * height
        val distance = FloatArray(cells)
        if (cells == 0) return distance

        // Larger than any squared distance a grid this size can hold, and finite, so the envelope
        // below can do arithmetic on it without meeting an infinity.
        val unreachable = 4.0 * (width.toDouble() * width + height.toDouble() * height)
        val noSource = Int.MAX_VALUE / 4

        val vertical = IntArray(cells)
        for (x in 0 until width) {
            var nearest = noSource
            var i = x
            for (y in 0 until height) {
                nearest = when {
                    source[i].toInt() != 0 -> 0
                    nearest == noSource -> noSource
                    else -> nearest + 1
                }
                vertical[i] = nearest
                i += width
            }
            nearest = noSource
            i = x + (height - 1) * width
            for (y in height - 1 downTo 0) {
                nearest = when {
                    source[i].toInt() != 0 -> 0
                    nearest == noSource -> noSource
                    else -> nearest + 1
                }
                if (nearest < vertical[i]) vertical[i] = nearest
                i -= width
            }
        }

        val span = width * 3
        val cost = DoubleArray(span)
        val vertex = IntArray(span)
        val boundary = DoubleArray(span + 1)
        for (y in 0 until height) {
            val rowStart = y * width
            for (j in 0 until span) {
                val d = vertical[rowStart + j % width]
                cost[j] = if (d >= noSource) unreachable else d.toDouble() * d
            }

            var k = 0
            vertex[0] = 0
            boundary[0] = -FAR
            boundary[1] = FAR
            for (q in 1 until span) {
                var meeting = intersection(cost, vertex[k], q)
                while (meeting <= boundary[k]) {
                    k--
                    meeting = intersection(cost, vertex[k], q)
                }
                k++
                vertex[k] = q
                boundary[k] = meeting
                boundary[k + 1] = FAR
            }

            k = 0
            for (x in 0 until width) {
                val q = width + x
                while (boundary[k + 1] < q) k++
                val dx = (q - vertex[k]).toDouble()
                val squared = dx * dx + cost[vertex[k]]
                distance[rowStart + x] =
                    if (squared >= unreachable) UNREACHED else sqrt(squared).toFloat()
            }
        }
        return distance
    }

    /** Where the parabolas rooted at [left] and [right] cross. */
    private fun intersection(cost: DoubleArray, left: Int, right: Int): Double {
        val l = left.toDouble()
        val r = right.toDouble()
        return ((cost[right] + r * r) - (cost[left] + l * l)) / (2.0 * r - 2.0 * l)
    }

    private const val FAR = 1e30
}
