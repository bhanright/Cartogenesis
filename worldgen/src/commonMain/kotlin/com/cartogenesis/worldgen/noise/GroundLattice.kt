package com.cartogenesis.worldgen.noise

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.round

/**
 * Where a cell of the map sits on a noise lattice stated on the ground: a wavelength in
 * kilometers, read as a whole number of cycles round the planet.
 *
 * The wavelength is the figure, and the cycles are derived from it where the lattice is made, so
 * a feature the noise shapes is the same size in kilometers on a planet of any size and at any
 * grid. Counted in cycles round the map instead, the same noise kept its count as the planet grew:
 * on a world of Earth's 40,075 km the plate warp's 6 cycles became 6,679 km where they were 2,000,
 * and the boundaries it bends came out as straight Voronoi edges (the Earth-size audit; see
 * docs/DESIGN_LEDGER.md, K1).
 *
 * East-west the map wraps, so the cycles round it are whole, [wholeCycles] of the circumference:
 * a lattice that does not close on itself meets its own seam at the antimeridian. Rounding to a
 * whole count moves the wavelength on the ground by at most half a cycle's share of the
 * circumference, `wavelengthKm / (2 × period)`, and moves it not at all on the 12,000 km world,
 * where every wavelength here was set as a whole count.
 *
 * North-south the map does not wrap, and a row advances the lattice by its own height on the
 * ground, [WorldGenConfig.cellHeightInCellWidths] of what a column advances it by, so a lattice
 * cell is as tall in kilometers as it is wide on any grid. A grid of square cells, `forRows`'s, and
 * a grid of 512 by 512, whose cells are twice as wide as they are tall, draw the same lattice on
 * the ground. Down the map the lattice covers [cyclesDown], half of [period] on an equirectangular
 * world, which is not whole in general; the same [period] is handed to [PerlinNoise] on both axes,
 * and the lattice never reaches it north-south, so no row meets the lattice's own seam.
 */
class GroundLattice(config: WorldGenConfig, wavelengthKm: Double) {

    /**
     * Lattice cycles round the map east to west, whole, and the period handed to [PerlinNoise] on
     * both axes.
     */
    val period: Int = wholeCycles(config.scale.worldWidthKm, wavelengthKm)

    private val cyclesPerColumn = period.toFloat() / config.width
    private val cyclesPerRow = (period.toFloat() * config.cellHeightInCellWidths / config.width).toFloat()

    /** The wavelength the whole count of cycles actually draws round the equator, in kilometers. */
    val wavelengthAcrossKm: Double = config.scale.worldWidthKm / period

    /**
     * How many cycles the lattice covers from pole to pole: as many as [period] covers of the
     * same length east to west, so the lattice is square on the ground.
     */
    val cyclesDown: Double = period * config.cellHeightInCellWidths * config.height / config.width

    /**
     * The lattice's period down the map for a field that is transformed as a periodic one in both
     * directions: [cyclesDown] when it is whole, so the pole-to-pole stretch joins up at the poles,
     * and [period] when it is not, which the lattice never reaches, so the poles meet the
     * transform's seam unjoined. Only the terrain's base field is transformed that way.
     */
    val periodDownJoiningPoles: Int = round(cyclesDown).toInt().let { whole ->
        if (whole >= 1 && kotlin.math.abs(cyclesDown - whole) < WHOLE_TOLERANCE) whole else period
    }

    /** The lattice's x at [column]. */
    fun x(column: Float): Float = column * cyclesPerColumn

    /** The lattice's y at [row]. */
    fun y(row: Float): Float = row * cyclesPerRow

    fun x(column: Int): Float = x(column.toFloat())

    fun y(row: Int): Float = y(row.toFloat())

    companion object {

        /**
         * How many whole cycles of [wavelengthKm] fit along [lengthKm], the nearest count and never
         * fewer than one.
         *
         * Nearest rather than fewer, so a planet a little smaller than a whole count keeps the
         * wavelength it nearly fits rather than losing a cycle; never zero, because a lattice of no
         * cycles is a constant, and a planet smaller than one wavelength still has weather.
         */
        fun wholeCycles(lengthKm: Double, wavelengthKm: Double): Int =
            round(lengthKm / wavelengthKm).toInt().coerceAtLeast(1)

        /**
         * How near a whole number [cyclesDown] must be to count as whole: the terrain's own figure,
         * far under any count a real grid gives and far over the arithmetic's own error.
         */
        private const val WHOLE_TOLERANCE = 1e-9
    }
}
