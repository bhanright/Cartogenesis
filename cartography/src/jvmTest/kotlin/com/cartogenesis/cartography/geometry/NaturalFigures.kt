package com.cartogenesis.cartography.geometry

/**
 * What the natural controls read, at one grid's cell size: the figures a layer is compared with
 * where a bar is taken from the control ensemble rather than from a cited source.
 *
 * The ensemble is fixed — [SEEDS] seeds, each at [ROTATIONS] rotations, isotropic on the ground and
 * isotropic on the sheet, each a [SIDE_CELLS]-cell square of the grid's own cells — and never reads
 * a world, so a bar cannot drift toward what today's worlds produce.
 */
internal class NaturalFigures private constructor(
    /** Right-angle corners per 1000 km of natural outline. */
    val cornersPer1000Km: Double,
    /** The outlines the figure was read over, in km. */
    val outlineKm: Double
) {
    companion object {
        val SEEDS = longArrayOf(11L, 23L, 37L)
        val ROTATIONS = doubleArrayOf(0.0, 17.0, 45.0, 71.0)
        const val SIDE_CELLS = 256

        /** The longest wavelength of the controls, in cells: half the square. */
        private const val LONGEST_WAVELENGTH_CELLS = 128.0

        /** How much of the square is inside: a lake-and-island country rather than one body. */
        private const val COVER_SHARE = 0.4

        private val cache = HashMap<String, NaturalFigures>()

        @Synchronized
        fun of(frame: GridFrame): NaturalFigures = cache.getOrPut("${frame.cellWidthKm}/${frame.cellHeightKm}") {
            val local = GridFrame(SIDE_CELLS, SIDE_CELLS, frame.cellWidthKm, frame.cellHeightKm)
            var corners = 0
            var length = 0.0
            for (seed in SEEDS) for (rotation in ROTATIONS) for (inCells in listOf(false, true)) {
                val mask = Controls.naturalField(
                    local, seed, COVER_SHARE, LONGEST_WAVELENGTH_CELLS * frame.cellWidthKm,
                    rotationDegrees = rotation, isotropicInCells = inCells
                )
                val outlines = Contours.ofMask(mask, local)
                corners += ComponentShapes.rightAnglesOf(outlines, local)
                length += outlines.sumOf { it.lengthKm() }
            }
            // One corner's worth added, so an ensemble with none still gives a rate to hold a layer to.
            NaturalFigures((corners + 1) * 1000.0 / length, length)
        }
    }
}
