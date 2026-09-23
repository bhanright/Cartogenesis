package com.cartogenesis.cartography.geometry

/**
 * The findings the geometry guard's census records as known failures, by name.
 *
 * Each is a clause that fails on today's worlds and is kept running through
 * [KnownFailures.expect], so that the fix which clears it arms its guard. The census's printout
 * gives every figure and place, and `build/geometry-census` a picture of each; what is named
 * here is what the audit is to act on.
 */
internal object GeometryFindings {

    /**
     * The raster coast (`MapRasterizer.drawCoastline`) inks a land cell only where the water lies
     * to its east or south, so east- and south-facing shores are drawn whole and north- and
     * west-facing ones about two in five.
     */
    const val COAST_INK = "the raster coast inks east- and south-facing shores only"

    /** The ice's edge — occupancy, sheet, carving and the drawn ice — runs straight along a row. */
    const val ICE_EDGE_ALONG_A_ROW = "the ice's edge runs straight along a row"

    /** Biome edges run straight along rows for longer than any natural outline does. */
    const val BIOME_EDGES_ALONG_ROWS = "biome edges run straight along rows"

    /** The mean-annual isotherms run straight along rows for thousands of kilometres. */
    const val ISOTHERMS_ALONG_ROWS = "isotherms run straight along rows"

    /** The currents view's sea temperature anomaly runs straight along rows. */
    const val ANOMALY_ALONG_ROWS = "the sea temperature anomaly runs straight along rows"

    /** The sea temperature anomaly's level lines turn square corners on the grid's axes. */
    const val ANOMALY_SQUARE_CORNERS = "the sea temperature anomaly turns square corners"

    /** The land's level lines run ruler-straight along a range front, near east-west. */
    const val STRAIGHT_RANGE_FRONTS = "terrain contours run ruler-straight along range fronts"

    /** A plate boundary runs exactly along a grid line for hundreds of kilometres. */
    const val PLATE_BOUNDARY_ON_THE_GRID = "a plate boundary runs along a grid line"

    /** A plate boundary bends in a circular arc to within a quarter of a cell. */
    const val PLATE_BOUNDARY_ARC = "a plate boundary bends in a circular arc"
}
