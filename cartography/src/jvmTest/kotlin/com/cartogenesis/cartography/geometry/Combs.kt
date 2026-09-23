package com.cartogenesis.cartography.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
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
 * spacings `s` between [SHORTEST_SPACING_CELLS] and [LONGEST_SPACING_CELLS]. Random offsets give
 * `Z` distributed as a unit exponential at each spacing, and the scan over spacings tests about
 * `span * (1 / shortest - 1 / longest)` independent frequencies, which the significance is
 * corrected for along with the census's own family.
 *
 * Periodicity alone is not a comb — Earth's valleys down a mountain front are spaced more
 * regularly than chance (Hovius 1996) — so a comb also needs [MINIMUM_TEETH] teeth on distinct
 * multiples of the spacing, each tooth at least [TOOTH_OVER_SPACING] spacings long, and a mean
 * resultant length (how closely the teeth sit on the ideal positions) of [MINIMUM_REGULARITY]. A
 * spacing that is fixed *in cells* across grids, rather than on the ground, is the grid's; that
 * claim needs the same world at two grids, and the audit tier makes it.
 */
internal object Combs {

    const val PARALLEL_DEGREES = 3.0
    const val SHORTEST_SPACING_CELLS = 2.0
    const val LONGEST_SPACING_CELLS = 16.0
    const val MINIMUM_TEETH = 5

    /** A tooth is at least this many spacings long: a comb's teeth are longer than their gaps. */
    const val TOOTH_OVER_SPACING = 2.0

    /**
     * How closely the teeth must sit on the ideal positions: a mean resultant length of 0.8 is
     * teeth within about a sixth of a spacing of where a ruler would put them (a uniform jitter of
     * plus or minus 0.17 spacings has a resultant of 0.8), where the natural spacing of Earth's
     * outlet valleys varies by a quarter to a third of itself (Hovius 1996's spacing ratios).
     */
    const val MINIMUM_REGULARITY = 0.8

    /** How far across from an anchor its comb may reach, in cells. */
    private const val REACH_CELLS = LONGEST_SPACING_CELLS * 6

    private const val SPACING_STEP_CELLS = 0.05
    private const val TILE_CELLS = 64.0
    private const val HARMONIC_TIE = 0.9

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
        val uy: Double
    )

    /** The combs among [runs], at the level a census of [familySize] tests needs. */
    fun measure(runs: List<Run>, frame: GridFrame, familySize: Int): List<Comb> {
        val teeth = runs.mapNotNull { run ->
            val dx = (run.toXKm - run.fromXKm) / frame.cellWidthKm
            val dy = (run.toYKm - run.fromYKm) / frame.cellHeightKm
            val length = sqrt(dx * dx + dy * dy)
            if (length < TOOTH_OVER_SPACING * SHORTEST_SPACING_CELLS) return@mapNotNull null
            var direction = Math.toDegrees(atan2(dy, dx))
            if (direction < 0) direction += 360.0
            Tooth(run.midXKm / frame.cellWidthKm, run.midYKm / frame.cellHeightKm, direction, length, dx / length, dy / length)
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
            val span = offsets.max() - offsets.min()
            // A comb at spacing s scores as highly at s / 2, s / 3 and so on, where every tooth still
            // falls on a whole number of periods; the spacing is the fundamental, the longest one
            // scoring within [HARMONIC_TIE] of the best.
            val scanned = ArrayList<Pair<Double, Double>>()
            var spacing = SHORTEST_SPACING_CELLS
            while (spacing <= LONGEST_SPACING_CELLS) {
                var c = 0.0
                var s = 0.0
                for (offset in offsets) {
                    val phase = 2 * PI * offset / spacing
                    c += cos(phase)
                    s += sin(phase)
                }
                scanned.add(spacing to (c * c + s * s) / offsets.size)
                spacing += SPACING_STEP_CELLS
            }
            val bestZ = scanned.maxOf { it.second }
            val bestSpacing = scanned.last { it.second >= HARMONIC_TIE * bestZ }.first
            val regularity = sqrt(bestZ / offsets.size)
            // The teeth that count: long enough to be teeth at this spacing, one per multiple.
            val distinctMultiples = members.indices
                .filter { teeth[members[it]].lengthCells >= TOOTH_OVER_SPACING * bestSpacing }
                .map { Math.round(offsets[it] / bestSpacing) }.distinct().size
            val frequencies = span * (1 / SHORTEST_SPACING_CELLS - 1 / LONGEST_SPACING_CELLS) + 1
            val pValue = (frequencies * exp(-bestZ)).coerceAtMost(1.0)
            val level = Statistics.FAMILY_ERROR_RATE / (familySize.toDouble() * teeth.size)
            if (distinctMultiples >= MINIMUM_TEETH && regularity >= MINIMUM_REGULARITY && pValue < level
            ) {
                members.forEach { claimed[it] = true }
                combs.add(
                    Comb(anchor.midX, anchor.midY, anchor.directionDegrees % 180.0, bestSpacing, distinctMultiples, regularity, bestZ)
                )
            }
        }
        return combs
    }
}
