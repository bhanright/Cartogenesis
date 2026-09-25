package com.cartogenesis.worldgen.noise

import com.cartogenesis.worldgen.model.WorldGenConfig

/**
 * Where a cell of the map sits on a noise lattice whose cells are square on the ground.
 *
 * Noise here is counted in cycles round the map east to west, so a feature has the same size in
 * kilometres at every grid. A lattice sampled with as many cycles down the map as across it is
 * square in *cells*, and this map's cells are twice as wide as they are tall: every feature the
 * noise shapes came out twice as long east-west as north-south on the ground, and the land's own
 * outlines ran twice as far east-west as north-south (Audit III's R13-6). So a row advances the
 * lattice by its own height on the ground, [WorldGenConfig.cellHeightInCellWidths] of what a
 * column advances it by, and a lattice cell is as tall in kilometres as it is wide.
 *
 * East-west the lattice still joins up round the map, [period] cycles to the circuit. North-south
 * the map does not wrap, and the lattice covers only half as many cycles down it as across it on a
 * world twice as wide as it is tall, so the same [period] is never reached and no row of the map
 * meets the lattice's own seam.
 */
class GroundLattice(config: WorldGenConfig, cyclesAcrossMap: Float) {

    private val cyclesPerColumn = cyclesAcrossMap / config.width
    private val cyclesPerRow = (cyclesAcrossMap * config.cellHeightInCellWidths / config.width).toFloat()

    /** Lattice cycles round the map east to west, and the period handed to [PerlinNoise] on both axes. */
    val period: Int = cyclesAcrossMap.toInt()

    /** The lattice's x at [column]. */
    fun x(column: Float): Float = column * cyclesPerColumn

    /** The lattice's y at [row]. */
    fun y(row: Float): Float = row * cyclesPerRow

    fun x(column: Int): Float = x(column.toFloat())

    fun y(row: Int): Float = y(row.toFloat())
}
