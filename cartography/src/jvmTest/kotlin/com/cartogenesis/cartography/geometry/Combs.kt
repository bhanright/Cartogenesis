package com.cartogenesis.cartography.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detector 5: ruled lines — parallel straight runs laid side by side at one fixed spacing.
 *
 * The comb on 969495's ice and the coastal combs X1d measured are rows of straight features, each
 * parallel to the next and a fixed number of cells from it. So for every run long enough to be a
 * tooth, the runs beside it that run the same way (within [PARALLEL_DEGREES] on the sheet, and in
 * the same direction along their lines, so that the two edges of one bar are not read as two
 * teeth) are gathered, their offsets across the common bearing taken in cells, and the offsets
 * tested for periodicity: the Rayleigh statistic `Z(s) = |sum exp(2 pi i o / s)|^2 / n` over the
 * spacings `s` between [SHORTEST_SPACING_CELLS] and [LONGEST_SPACING_CELLS], whose square root over
 * the count is the mean resultant length: 1 for teeth exactly a ruler's spacing apart, near 0 for
 * offsets at random.
 *
 * The bar is geometric rather than a significance level. A comb a reader sees as ruled has five
 * to ten teeth, and a Rayleigh statistic on nine offsets cannot reach the level a census of a
 * thousand tests needs whatever their regularity, so a level would pass every comb this project
 * has drawn. Periodicity alone is not a comb either — Earth's valleys down a mountain front are
 * spaced more regularly than chance (Hovius 1996) — so a comb needs [MINIMUM_TEETH] teeth on consecutive
 * multiples of the spacing, each tooth at least [TOOTH_OVER_SPACING] spacings long, and a mean
 * resultant length (how closely the teeth sit on the ideal positions) of [MINIMUM_REGULARITY]. A
 * spacing that is fixed *in cells* across grids, rather than on the ground, is the grid's; that
 * claim needs the same world at two grids, and the audit tier makes it ([Census.pairCombs]): a comb
 * found again at half the grid at half the spacing in cells is fixed on the ground and let stand,
 * and one found at the same spacing in cells, or not found again, stands as a violation.
 */
internal object Combs {

    const val PARALLEL_DEGREES = 3.0
    /**
     * The closest teeth a comb is looked for at, in cells. At two and a half cells and under,
     * natural outlines and courses make regular-looking rows of raster steps by themselves
     * (`GeometryControlTest` prints them); a comb is ruled at a spacing the eye resolves.
     */
    const val SHORTEST_SPACING_CELLS = 3.0
    const val LONGEST_SPACING_CELLS = 16.0
    const val MINIMUM_TEETH = 6

    /** A tooth is at least this many spacings long: a comb's teeth are longer than their gaps. */
    const val TOOTH_OVER_SPACING = 2.0

    /**
     * How closely the teeth must sit on the ideal positions: a mean resultant length of 0.9 is
     * teeth within about an eighth of a spacing of where a ruler would put them (a uniform jitter
     * of plus or minus 0.124 spacings has a resultant of 0.9), where the natural spacing of Earth's
     * outlet valleys varies by a quarter to a third of itself (Hovius 1996's spacing ratios).
     */
    const val MINIMUM_REGULARITY = 0.9

    /** How far across from an anchor its comb may reach, in cells. */
    private const val REACH_CELLS = LONGEST_SPACING_CELLS * 6

    private const val SPACING_STEP_CELLS = 0.05
    private const val TILE_CELLS = 64.0
    private const val HARMONIC_TIE = 0.9

    /** How close across two parallel runs lie and are one tooth, in cells: half a cell. */
    private const val COLLINEAR_CELLS = 0.5

    class Comb(
        val anchorXCells: Double,
        val anchorYCells: Double,
        val bearingDegrees: Double,
        val spacingCells: Double,
        val teeth: Int,
        val regularity: Double,
        val rayleigh: Double
    ) {
        fun describe(grid: GridFrame): String {
            var column = anchorXCells % grid.cellsAcross
            if (column < 0) column += grid.cellsAcross
            return "comb at (%d,%d) along %.0f deg on the sheet: %d teeth %.2f cells apart, regularity %.2f".format(
                column.toInt(), anchorYCells.toInt(), bearingDegrees, teeth, spacingCells, regularity
            )
        }
    }

    private class Tooth(
        val midX: Double,
        val midY: Double,
        val directionDegrees: Double,
        val lengthCells: Double,
        val ux: Double,
        val uy: Double,
        val family: Int
    )

    /**
     * How many of [runs] are long enough to be a tooth at the closest spacing, in the family that
     * has most: a layer with fewer than [MINIMUM_TEETH] cannot hold a comb, so a clean reading of it
     * is not a pass.
     */
    fun mostTeeth(runs: List<Run>, frame: GridFrame, familyOf: (Int) -> Int = { 0 }): Int =
        runs.filter { run ->
            val dx = (run.toXKm - run.fromXKm) / frame.cellWidthKm
            val dy = (run.toYKm - run.fromYKm) / frame.cellHeightKm
            sqrt(dx * dx + dy * dy) >= TOOTH_OVER_SPACING * SHORTEST_SPACING_CELLS
        }.groupingBy { familyOf(it.outline) }.eachCount().values.maxOrNull() ?: 0

    /**
     * The combs among [runs]. [familyOf] names the family each run's line belongs to, and teeth
     * are gathered within one family: the level lines of successive levels down an even slope lie
     * side by side at an even spacing by the slope's nature, so a family of level lines is one
     * level and not the stack of them.
     */
    fun measure(runs: List<Run>, frame: GridFrame, familyOf: (Int) -> Int = { 0 }): List<Comb> {
        val teeth = runs.mapNotNull { run ->
            val dx = (run.toXKm - run.fromXKm) / frame.cellWidthKm
            val dy = (run.toYKm - run.fromYKm) / frame.cellHeightKm
            val length = sqrt(dx * dx + dy * dy)
            if (length < TOOTH_OVER_SPACING * SHORTEST_SPACING_CELLS) return@mapNotNull null
            var direction = Math.toDegrees(atan2(dy, dx))
            if (direction < 0) direction += 360.0
            Tooth(run.midXKm / frame.cellWidthKm, run.midYKm / frame.cellHeightKm, direction, length, dx / length, dy / length, familyOf(run.outline))
        }
        if (teeth.size < MINIMUM_TEETH) return emptyList()
        // Tiles of the sheet, for finding an anchor's neighbours without looking at every run.
        val tiles = HashMap<Long, MutableList<Int>>()
        fun tileOf(x: Double, y: Double): Long =
            (floor(x / TILE_CELLS).toLong() shl 32) or (floor(y / TILE_CELLS).toLong() and 0xFFFFFFFFL)
        teeth.forEachIndexed { index, tooth -> tiles.getOrPut(tileOf(tooth.midX, tooth.midY)) { ArrayList() }.add(index) }

        val combs = ArrayList<Comb>()
        val claimed = BooleanArray(teeth.size)
        for (anchorIndex in teeth.indices) {
            if (claimed[anchorIndex]) continue
            val anchor = teeth[anchorIndex]
            val reachTiles = (REACH_CELLS / TILE_CELLS).toInt() + 1
            val offsets = ArrayList<Double>()
            val members = ArrayList<Int>()
            val tileX = floor(anchor.midX / TILE_CELLS).toLong()
            val tileY = floor(anchor.midY / TILE_CELLS).toLong()
            for (ty in tileY - reachTiles..tileY + reachTiles) for (tx in tileX - reachTiles..tileX + reachTiles) {
                val list = tiles[(tx shl 32) or (ty and 0xFFFFFFFFL)] ?: continue
                for (other in list) {
                    val tooth = teeth[other]
                    if (tooth.family != anchor.family) continue
                    var turn = abs(tooth.directionDegrees - anchor.directionDegrees) % 360.0
                    if (turn > 180.0) turn = 360.0 - turn
                    if (turn > PARALLEL_DEGREES) continue
                    val rx = tooth.midX - anchor.midX
                    val ry = tooth.midY - anchor.midY
                    val along = rx * anchor.ux + ry * anchor.uy
                    val across = -rx * anchor.uy + ry * anchor.ux
                    if (abs(across) > REACH_CELLS) continue
                    // Side by side: the two overlap along the bearing by half the shorter.
                    val overlap = minOf(anchor.lengthCells, tooth.lengthCells) / 2
                    if (abs(along) > (anchor.lengthCells + tooth.lengthCells) / 2 - overlap) continue
                    offsets.add(across)
                    members.add(other)
                }
            }
            if (offsets.size < MINIMUM_TEETH) continue
            // Runs along one line — pieces of one tooth, or of one long course — are one tooth and not
            // several: counted apiece they would make any spacing dividing their gaps look regular.
            val byOffset = offsets.indices.sortedBy { offsets[it] }
            val toothOffsets = ArrayList<Double>()
            val toothLengths = ArrayList<Double>()
            for (index in byOffset) {
                val length = teeth[members[index]].lengthCells
                if (toothOffsets.isEmpty() || offsets[index] - toothOffsets.last() > COLLINEAR_CELLS) {
                    toothOffsets.add(offsets[index])
                    toothLengths.add(length)
                } else if (length > toothLengths.last()) {
                    toothLengths[toothLengths.size - 1] = length
                }
            }
            if (toothOffsets.size < MINIMUM_TEETH) continue
            // A comb at spacing s scores as highly at s / 2, s / 3 and so on, where every tooth still
            // falls on a whole number of periods; the spacing is the fundamental, the longest one
            // scoring within [HARMONIC_TIE] of the best.
            val scanned = ArrayList<Pair<Double, Double>>()
            var spacing = SHORTEST_SPACING_CELLS
            while (spacing <= LONGEST_SPACING_CELLS) {
                var c = 0.0
                var s = 0.0
                for (offset in toothOffsets) {
                    val phase = 2 * PI * offset / spacing
                    c += cos(phase)
                    s += sin(phase)
                }
                scanned.add(spacing to (c * c + s * s) / toothOffsets.size)
                spacing += SPACING_STEP_CELLS
            }
            val bestZ = scanned.maxOf { it.second }
            val bestSpacing = scanned.last { it.second >= HARMONIC_TIE * bestZ }.first
            val regularity = sqrt(bestZ / toothOffsets.size)
            // The teeth that count: long enough to be teeth at this spacing, one per multiple, and side
            // by side with no tooth missing — a ruled comb has every tooth, where offsets that fall in
            // phase by chance among a crowd of lines skip multiples.
            val multiples = toothOffsets.indices
                .filter { toothLengths[it] >= TOOTH_OVER_SPACING * bestSpacing }
                .map { Math.round(toothOffsets[it] / bestSpacing) }.distinct().sorted()
            var distinctMultiples = if (multiples.isEmpty()) 0 else 1
            var consecutive = 1
            for (at in 1 until multiples.size) {
                consecutive = if (multiples[at] == multiples[at - 1] + 1) consecutive + 1 else 1
                if (consecutive > distinctMultiples) distinctMultiples = consecutive
            }
            if (distinctMultiples >= MINIMUM_TEETH && regularity >= MINIMUM_REGULARITY) {
                members.forEach { claimed[it] = true }
                combs.add(
                    Comb(anchor.midX, anchor.midY, anchor.directionDegrees % 180.0, bestSpacing, distinctMultiples, regularity, bestZ)
                )
            }
        }
        return combs
    }
}
