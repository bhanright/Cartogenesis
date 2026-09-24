package com.cartogenesis.cartography.geometry

/**
 * The findings the geometry guard's censuses record as known failures, by name.
 *
 * Each is a clause that fails on today's worlds and is kept running through
 * [KnownFailures.expect] under its violation's signature ([GeometryExpectations]), so that the fix
 * which clears it arms its guard. The census's printout gives every figure and place, and
 * `build/geometry-census` a picture of each; the figures here are the census's at the grid named.
 */
internal object GeometryFindings {

    /**
     * The raster coast (`MapRasterizer.drawCoastline`) inks a land cell only where the water lies
     * to its east or south, so east- and south-facing shores are drawn whole and north- and
     * west-facing ones two in five: the least-drawn facing 2.2 to 2.6 times under the most on
     * every world at both grids.
     */
    const val COAST_INK = "the raster coast inks east- and south-facing shores only"

    /**
     * The ice's edge runs straight along a row near 72.7 degrees: at 2048 the occupancy's edge on
     * 969495 runs 337 cell widths (1,975 km) at 0.0 degrees along row 1850, and the sheet's ground
     * 73 steps exactly on row 1851; on 718106 and 59758 the same along rows 196 and 1851-1852, the
     * ice surface's level lines with it on 718106. The drawn ice, the ice-sheet biome, runs
     * ruler-straight along rows as well, 187 to 256 cell widths at 67 to 82 degrees north on four worlds.
     */
    const val ICE_EDGE_ALONG_A_ROW = "the ice's edge runs straight along a row"

    /** The ice's edges meet square on the sheet, and more often than natural outlines' do. */
    const val ICE_EDGE_CORNERS = "the ice's edges turn square corners"

    /** The ice's surface and its cut follow circular arcs to within a quarter of a cell. */
    const val ICE_ARCS = "the ice's surface and cut follow circular arcs"

    /** A valley glacier's outline doubles back in a long straight hairpin (969495, near the south pole). */
    const val VALLEY_GLACIER_HAIRPIN = "a valley glacier doubles back in a straight hairpin"

    /** Delta lobes are half-discs, round to a quarter of a cell, and the coast carries their rims. */
    const val DELTA_LOBES_ROUND = "delta lobes are round half-discs"

    /** Lake fans have round rims. */
    const val LAKE_FANS_STAMPED = "lake fans have round rims"

    /** A river course follows a circular arc to within a quarter of a cell. */
    const val RIVER_ARC = "a river course follows a circular arc"

    /** A river course jogs through two square corners a few cells apart (1234 and 59758 at 2048). */
    const val RIVER_SQUARE_CORNERS = "a river course jogs through square corners"

    /** A coast turns a square notch a few cells across (1234 and 718106 at 2048). */
    const val COAST_SQUARE_NOTCH = "a coast turns a square notch"

    /** A coast runs ruler-straight for 388 km at 4 degrees (59758 at 2048, in the far south). */
    const val COAST_STRAIGHT = "a coast runs ruler-straight"

    /**
     * The sea floor's level lines run ruler-straight, 63 to 130 cell widths at 2048 on every world,
     * and double back in straight hairpins: the floor there is planar ramps and ridges.
     */
    const val SEA_FLOOR_RAMPS = "isobaths run ruler-straight over planar ramps"

    /** An isobath turns a square corner on the grid's axes (969495 at 2048, near the south pole). */
    const val ISOBATH_SQUARE_CORNER = "an isobath turns a square corner"

    /** Seamounts are cones: their isobaths are circles. */
    const val SEAMOUNT_CONES = "seamounts are circular cones"

    /** The land's level lines run ruler-straight along a range front, near east-west. */
    const val STRAIGHT_RANGE_FRONTS = "terrain contours run ruler-straight along range fronts"

    /** The land's level lines follow a circular arc. */
    const val TERRAIN_ARC = "a terrain contour follows a circular arc"

    /**
     * A level line of the land or of the temperature turns a square corner, where the ice's edge
     * does and a lake lies against it (59758 at 2048).
     */
    const val LEVEL_LINE_CORNER_AT_THE_ICE = "a level line turns a square corner at the ice's edge"

    /** A biome edge turns a square corner on the grid's axes, and the peoples' border there with it. */
    const val BIOME_EDGE_CORNER = "a biome edge turns a square corner"

    /** Biome edges double back in long straight hairpins. */
    const val BIOME_EDGE_HAIRPINS = "biome edges double back in straight hairpins"

    /** The peoples' borders run along grid lines and turn square, following ice and biome edges. */
    const val PEOPLES_BORDERS_ON_THE_GRID = "peoples' borders run along grid lines"

    /** A realm border turns square corners on the grid's axes. */
    const val REALM_BORDER_CORNERS = "a realm border turns square corners"

    /** A realm border follows a circular arc (99 at 2048, on the continents Fix 2 drew). */
    const val REALM_BORDER_ARC = "a realm border follows a circular arc"

    /** Realm borders run ruler-straight for 380 to 500 km at 2048. */
    const val REALM_BORDERS_STRAIGHT = "realm borders run ruler-straight"

    /**
     * Plate boundaries run ruler-straight: 470 to 600 km at 2048 on every world while the partition
     * was square in cells; since it is Euclidean on the ground (Fix 2), 370 to 460 km on four worlds
     * of seven, and on five a run of 64 to 87 steps along a grid bearing.
     */
    const val PLATE_BOUNDARIES_STRAIGHT = "plate boundaries run ruler-straight"

    /** A plate boundary bends in a circular arc to within a quarter of a cell. */
    const val PLATE_BOUNDARY_ARC = "a plate boundary bends in a circular arc"

    /**
     * The mean-annual isotherms prefer the east-west bearing 1.8 to 2.1 times their neighbours at
     * 2048 against an Earth-like zonal field's 1.0, and run ruler-straight for 1,200 to 2,300 km.
     */
    const val ISOTHERMS_ALONG_ROWS = "isotherms run straight along rows"

    /** A rainfall level line turns sharply between two long straight runs. */
    const val ISOHYET_CREASE = "an isohyet creases between straight runs"

    /**
     * The currents view's sea temperature anomaly runs straight along rows at 512, 189 to 217
     * steps, and doubles back in straight hairpins; at 2048 it draws no line at its levels.
     */
    const val ANOMALY_ALONG_ROWS = "the sea temperature anomaly runs straight along rows"
}
