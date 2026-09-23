package com.cartogenesis.worldgen

/**
 * The layers a generation draws into the map without any stage's result carrying them, captured
 * where they are made, for the geometry guard.
 *
 * The ice and the deltas reach the map only through the elevation field: the ice cuts troughs,
 * basins and outlets into it and then stands its own surface on top, and the fans raise the
 * ground they build over. What shape each of those was, as opposed to the shape of the ground
 * after everything else has worked on it, is in no output, and reconstructing it from the output
 * would be an approximation reported as an observation. So a caller that wants them hands one of
 * these to [WorldGenerationEngine.generate] and the two stages fill it in as they run.
 *
 * Null, which is every caller but the guard, costs a few null checks on the way through the two
 * stages, allocates nothing and moves no bit of the world; with one, the world is still the same
 * world to the last bit, which `LayerCaptureTest` shows by fingerprint. A stage
 * reused from `previous` rather than run reports nothing, so a capture is whole only for a fresh
 * generation. A world with no ice, or ice that cut nothing, leaves [ice] null: such ice draws
 * nothing on the map.
 */
class LayerCapture {

    /** What the erosion stage laid where, or null until it runs. */
    var deposition: DepositionLayers? = null
        internal set

    /** Where the ice stood and what it cut, or null until it runs or if it cut nothing. */
    var ice: IceLayers? = null
        internal set
}

/**
 * Which of the sediment-laying mechanisms last raised each cell during erosion.
 *
 * [mechanism] is one entry per cell, row-major, holding [NONE], [SEA_LOBE], [LAKE_FAN] or
 * [FLOODPLAIN]: the delta lobes built into the sea, the fans built into standing water on land,
 * and the floodplain and alluvial deposits laid along the drainage. Whichever mechanism touched a
 * cell last, over every hydraulic round.
 */
class DepositionLayers(val mechanism: ByteArray) {
    companion object {
        const val NONE: Byte = 0
        const val SEA_LOBE: Byte = 1
        const val LAKE_FAN: Byte = 2
        const val FLOODPLAIN: Byte = 3
    }
}

/**
 * The ice's layers, every array one entry per cell, row-major, on the world's own grid.
 *
 * The masks are read inside the glaciation step as it runs: [frozen] is where the snow balance put
 * ice; [valleyGlacier] the trough axes the valley regime cut across; [sheet] the ground left to the
 * sheet regime; [cutByIce] every cell whose rock the ice lowered, taken before the till, the load's
 * bend and the ice surface go back on; [cutByOutlets] the part of that the sheet's outlet troughs
 * cut. [basinFloor] names the basin each cut floor belongs to, -1 elsewhere: the valley basins are
 * `0 until valleyBasinCount` and the sheet's scour basins follow. [iceThicknessMetres] is the
 * sheet's own thickness, zero off it, and where it is positive the world's elevation is the top of
 * the ice: that is the sheet's surface.
 */
class IceLayers(
    val frozen: BooleanArray,
    val valleyGlacier: BooleanArray,
    val sheet: BooleanArray,
    val cutByIce: BooleanArray,
    val cutByOutlets: BooleanArray,
    val basinFloor: IntArray,
    val valleyBasinCount: Int,
    val iceThicknessMetres: FloatArray
)
