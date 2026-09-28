package com.cartogenesis.cartography.geometry

/**
 * The findings the geometry guard's censuses record as known failures, by name.
 *
 * Each is a clause that fails on today's worlds and is kept running through
 * [KnownFailures.expect] under its violation's signature ([GeometryExpectations]), so that the fix
 * which clears it arms its guard. The census's printout gives every figure and place, and
 * `build/geometry-census` a picture of each; the figures here are the census's at the grid named.
 * Since Q4 the censuses read square cells, 512 rows (1024 by 512) and 2048 columns (2048 by 1024);
 * a figure "at 2048" before that is the 2048 by 2048 grid's, whose cells were twice as wide as tall
 * and whose natural controls set looser bars (docs/DESIGN_LEDGER.md, Q4).
 */
internal object GeometryFindings {

    /**
     * The raster coast (`MapRasterizer.drawCoastline`) inks a bank cell only where the open water
     * lies to its east or south, so east- and south-facing shores are drawn whole, north-facing
     * ones 35 to 50% and west-facing a fifth to three tenths: the most-drawn facing 3.4 to 4.7
     * times the least on every world at both grids, since the narrow sea became a bank
     * (`NarrowSea`) and the east- and south-facing shores beside it stopped counting as undrawn.
     * On square cells 3.0 to 3.5 times at 512 rows and 2.7 to 3.5 at 2048 columns.
     */
    const val COAST_INK = "the raster coast inks east- and south-facing shores only"

    /**
     * The ice's edge runs straight along a row near 72.7 degrees: at 2048 the occupancy's edge on
     * 969495 runs 337 cell widths (1,975 km) at 0.0 degrees along row 1850, and the sheet's ground
     * 73 steps exactly on row 1851; on 718106 and 59758 the same along rows 196 and 1851-1852, the
     * ice surface's level lines with it on 718106. The drawn ice, the ice-sheet biome, runs
     * ruler-straight along rows as well, 187 to 256 cell widths at 67 to 82 degrees north on four worlds.
     *
     * A straight edge carries into the surface, whose level lines lie along the margin, and not only
     * along a row: since Q2 moved 59758's ice at 2048, a level line of its surface runs 69.5 cell
     * widths (407 km) at 110.7 degrees at (1381, 503), along the sheet's straight margin beside a
     * rift valley (docs/DESIGN_LEDGER.md, Q2's 2048 census).
     */
    const val ICE_EDGE_ALONG_A_ROW = "the ice's edge runs straight along a row"

    /** The ice's edges meet square on the sheet, and more often than natural outlines' do. */
    const val ICE_EDGE_CORNERS = "the ice's edges turn square corners"

    /**
     * The ice's surface and its cut follow circular arcs to within a quarter of a cell. At 2048 on
     * 1234, 99 and 59758 before Q2; since, 1234's and 99's cut and ground are clear of it and the
     * arcs stand on 42's cut, 123 degrees of a circle 11.9 cells in radius at (235, 1254), and on
     * 969495's surface, 123 degrees at (925, 1328), where Q2's smooth field moved the ground.
     *
     * On square cells at 2048 columns (Q4) the arcs on 42's cut and on 969495's, 1234's and 59758's
     * surfaces are gone; the radius floor there is twelve cells, as the natural controls on square
     * cells set it, and 42's, 11.9, falls under it. One stands: 99's surface, 183 degrees at
     * (1587, 38).
     */
    const val ICE_ARCS = "the ice's surface and cut follow circular arcs"

    /** A valley glacier's outline doubles back in a long straight hairpin (969495, near the south pole). */
    const val VALLEY_GLACIER_HAIRPIN = "a valley glacier doubles back in a straight hairpin"

    /**
     * Delta lobes are half-discs, round to a quarter of a cell, and the coast carries their rims.
     * None counted on square cells at 2048 columns (Q4), where 969495's stood before; the radius
     * floor is twelve cells there, and whether it fell under it or off the ground is not separated.
     */
    const val DELTA_LOBES_ROUND = "delta lobes are round half-discs"

    /**
     * A coast follows a circular arc to within a quarter of a cell. A delta lobe's rim does, and the
     * coast's arcs were recorded under [DELTA_LOBES_ROUND] for that; the first on the coast since,
     * 1234's at 2048 after Q2 (157 degrees of a circle 10.9 cells in radius about (1546, 1287), 131
     * about (1816, 684)), stands on ground no delta built, not a lobe cell within 12 cells of
     * either. It is the land's own level line at the sea, rounded: the class of [TERRAIN_ARC], which
     * the same world's contours carried before Q2 (docs/DESIGN_LEDGER.md, Q2's 2048 census).
     *
     * On square cells at 2048 columns (Q4) 1234's two, 10.9 cells in radius, fall under the floor of
     * twelve the natural controls there set, and two drawn coasts carry one each: 99's, 160 degrees
     * at (2000, 489), and 969495's, 137 degrees at (251, 1000).
     */
    const val COAST_ARC = "a coast follows a circular arc"

    /** Lake fans have round rims. */
    const val LAKE_FANS_STAMPED = "lake fans have round rims"

    /**
     * A lake's shore follows a circular arc to within a quarter of a cell: 969495's at (1119, 1850)
     * at 2048, 233 degrees of a circle 10.5 cells across, first seen by 4a's census.
     */
    const val LAKE_SHORE_ARC = "a lake's shore follows a circular arc"

    /**
     * A lake's shore turns sharply between two long straight runs: 7's at (1366, 429) at 2048
     * columns, a turn of 7.4 against the natural controls' 6.3, first seen once the census read
     * square cells, whose natural lines crease far less than those of cells twice as wide as tall.
     */
    const val LAKE_SHORE_CREASE = "a lake's shore creases between straight runs"

    /**
     * A river course follows a circular arc to within a quarter of a cell. None counted on square
     * cells at 2048 columns (Q4), where 1234's two stood before; the radius floor is twelve cells
     * there, and whether they fell under it or off the ground is not separated.
     */
    const val RIVER_ARC = "a river course follows a circular arc"

    /**
     * A river course jogs through two square corners a few cells apart (1234 and 59758 at 2048). On
     * square cells at 2048 columns (Q4) both are gone, and 718106's at (1045, 377) stands.
     */
    const val RIVER_SQUARE_CORNERS = "a river course jogs through square corners"

    /**
     * A coast turns a square notch a few cells across. The drawn coast held none past the bar once
     * it was drawn round the narrow sea (`NarrowSea`): 1234's at (468, 1181) at 2048 and 42's corner
     * rate of 0.314 per thousand cell widths at 2048 both cleared with it.
     *
     * It returned on seed 7 at 2048 with Q2, 7 corners over 12,383 cell widths, 0.565 per thousand
     * where natural outlines make 0.004. Four of the seven stood at the same cells before Q2, 0.323
     * per thousand, too few for the count's lower bound to clear the bar; the other three came with
     * the smooth field's period, which Q2 put on the ground. Each is the drawn coast running six to
     * seventeen cells along a row or a column and turning square onto the other, at the head or
     * the side of an inlet; two are at the mouths of the straight drowned gullies of the far south's
     * combed flanks (docs/DESIGN_LEDGER.md, Q2's 2048 census).
     *
     * On square cells at 2048 columns (Q4) seed 7's seven are gone, and 59758's drawn coast turns
     * 0.430 square corners per thousand cell widths.
     */
    const val COAST_SQUARE_NOTCH = "a coast turns a square notch"

    /**
     * A coast runs ruler-straight for 388 km at 4 degrees (59758 at 2048, in the far south), and
     * the drawn coast of 99 at 2048 straight down a column for 111 steps at (879, 212), where the
     * land's edge is straight and only one-cell notches, now drawn as water, had broken it.
     */
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

    /**
     * A peoples' border follows a circular arc to within a quarter of a cell: two on 59758 at 2048,
     * about 123 degrees of circles 10 to 12 cells across, first seen by 4a's census.
     */
    const val PEOPLES_BORDER_ARC = "a peoples' border follows a circular arc"

    /** A realm border turns square corners on the grid's axes. */
    const val REALM_BORDER_CORNERS = "a realm border turns square corners"

    /** A realm border follows a circular arc (99 at 2048, on the continents Fix 2 drew). */
    const val REALM_BORDER_ARC = "a realm border follows a circular arc"

    /**
     * Realm borders run ruler-straight for 380 to 500 km at 2048. Since Q2 moved 59758's ground, one
     * runs 64 steps north-south down the axis of a rift lake at (1689, 1776), against a bar of 63.9.
     *
     * On square cells at 2048 columns (Q4) no realm border is past its bars, 59758's run included;
     * the finding stays mapped so that a return is named.
     */
    const val REALM_BORDERS_STRAIGHT = "realm borders run ruler-straight"

    /**
     * Plate boundaries run ruler-straight: 470 to 600 km at 2048 on every world while the partition
     * was square in cells; since it is Euclidean on the ground (Fix 2), 370 to 460 km on four worlds
     * of seven, and on five a run of 64 to 87 steps along a grid bearing.
     *
     * On square cells at 2048 columns (Q4) the runs along a grid bearing are 39 and 42 steps, on 42
     * and 1234, where 7's, 99's and 969495's are gone; straight runs past the facet bar stand on every
     * world, 56 to 87 cell widths.
     */
    const val PLATE_BOUNDARIES_STRAIGHT = "plate boundaries run ruler-straight"

    /** A plate boundary bends in a circular arc to within a quarter of a cell. */
    const val PLATE_BOUNDARY_ARC = "a plate boundary bends in a circular arc"

    /**
     * The mean-annual isotherms prefer the east-west bearing 1.8 to 2.1 times their neighbours at
     * 2048 against an Earth-like zonal field's 1.0, and run ruler-straight for 1,200 to 2,300 km.
     *
     * On square cells at 2048 columns (Q4) the preference is 1.74 to 1.86 times, and the longest runs
     * 215 to 371 cell widths.
     */
    const val ISOTHERMS_ALONG_ROWS = "isotherms run straight along rows"

    /** A rainfall level line turns sharply between two long straight runs. */
    const val ISOHYET_CREASE = "an isohyet creases between straight runs"

    /**
     * The currents view's sea temperature anomaly ran straight along rows at 512, 189 to 217
     * steps, and doubled back in straight hairpins; at 2048 it drew no line at its levels. Chunk
     * 4a, which solved the currents on the ground and measured the anomaly from a band of latitude
     * rather than one row, cleared every such clause on the four standard worlds; the finding stays
     * mapped so that a return is named.
     */
    const val ANOMALY_ALONG_ROWS = "the sea temperature anomaly runs straight along rows"
}
