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

    /**
     * The ice's edge — occupancy, sheet, carving and the drawn ice — runs straight along a row,
     * and at 2048 along a grid diagonal too. At 2048 the worst straight edge of the occupancy lies
     * on row 1851 or 1852 (72.7 degrees south) on four of the seven worlds, and the sheet's on row
     * 196 (72.7 degrees north) on two, up to 1,975 km long; on 969495 the sheet's flat facets and
     * straight creases stand on that edge.
     */
    const val ICE_EDGE_ALONG_A_ROW = "the ice's edge runs straight along a row"

    /** The ice's edges meet square on the sheet. */
    const val ICE_EDGE_CORNERS = "the ice's edges turn square corners"

    /** The ice's surface and its cut follow circular arcs to within a quarter of a cell. */
    const val ICE_ARCS = "the ice's surface and cut follow circular arcs"

    /** An outlet trough runs straight along the grid diagonal. */
    const val OUTLET_TROUGH_ON_THE_GRID = "an outlet trough runs along the grid diagonal"

    /** A scour basin's side runs straight along the grid diagonal. */
    const val SCOUR_BASIN_ON_THE_GRID = "a scour basin's side runs along the grid diagonal"

    /**
     * A lake's shore, and the open water a drawn river stops at, runs straight along a grid line
     * — on 718106 against the ice sheet's straight edge.
     */
    const val LAKE_SHORE_ON_THE_GRID = "a lake shore runs along a grid line"

    /** Delta lobes are half-discs, round to a quarter of a cell, some with a straight side. */
    const val DELTA_LOBES_ROUND = "delta lobes are round half-discs"

    /** Lake fans have straight sides along the grid and round rims. */
    const val LAKE_FANS_STAMPED = "lake fans have grid sides and round rims"

    /** A river course follows a circular arc to within a quarter of a cell. */
    const val RIVER_ARC = "a river course follows a circular arc"

    /**
     * The sea floor's level lines run ruler-straight along its ridges and, at two worlds, along a
     * row: the floor there is a planar ramp.
     */
    const val SEA_FLOOR_RAMPS = "isobaths run ruler-straight over planar ramps"

    /** Seamounts are cones: their isobaths are circles about one centre. */
    const val SEAMOUNT_CONES = "seamounts are circular cones"

    /** The land's level lines follow a circular arc. */
    const val TERRAIN_ARC = "a terrain contour follows a circular arc"

    /** The peoples' borders run straight along rows and turn square, following ice and biome edges. */
    const val PEOPLES_BORDERS_ON_THE_GRID = "peoples' borders run along grid lines"

    /** A realm border turns square corners on the grid's axes. */
    const val REALM_BORDER_CORNERS = "a realm border turns square corners"

    /** The rainfall's level lines run ruler-straight. */
    const val ISOHYETS_STRAIGHT = "isohyets run ruler-straight"

    /** The pane's generalised coast runs along the grid diagonal. */
    const val DRAWN_COAST_ON_THE_GRID = "the drawn coast runs along the grid diagonal"

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
