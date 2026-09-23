package com.cartogenesis.cartography.geometry

import com.cartogenesis.cartography.geometry.GeometryFindings as F

/**
 * What the geometry censuses expect of today's worlds: which finding each layer's violations of
 * each detector record, and at each grid, the signature of every known violation and every clause
 * too small to measure. The census prints its own `GEOMETRY RECORD` lines in this form.
 */
internal object GeometryExpectations {

    /** The finding a violation records, by layer and detector, at every grid. */
    fun findings(expected: Expectations) = with(expected) {
        finding("coast as inked", Detector.FACING, F.COAST_INK)
        for (layer in listOf("ice occupancy", "ice sheet ground", "ice carving", "ice as drawn")) {
            finding(layer, Detector.ALIGNED_SIDE, F.ICE_EDGE_ALONG_A_ROW)
            finding(layer, Detector.FACETS, F.ICE_EDGE_ALONG_A_ROW)
            finding(layer, Detector.RIGHT_ANGLES, F.ICE_EDGE_CORNERS)
        }
        finding("ice carving", Detector.ARCS, F.ICE_ARCS)
        finding("ice surface", Detector.ARCS, F.ICE_ARCS)
        finding("outlet troughs", Detector.ALIGNED_SIDE, F.OUTLET_TROUGH_ON_THE_GRID)
        finding("scour basins", Detector.ALIGNED_SIDE, F.SCOUR_BASIN_ON_THE_GRID)
        finding("scour basins", Detector.FACETS, F.SCOUR_BASIN_ON_THE_GRID)
        for (layer in listOf("lakes", "lakes' open water")) {
            finding(layer, Detector.ALIGNED_SIDE, F.LAKE_SHORE_ON_THE_GRID)
            finding(layer, Detector.FACETS, F.LAKE_SHORE_ON_THE_GRID)
        }
        for (detector in listOf(Detector.ARCS, Detector.FACETS, Detector.ALIGNED_SIDE)) finding("delta lobes", detector, F.DELTA_LOBES_ROUND)
        finding("coast", Detector.ARCS, F.DELTA_LOBES_ROUND)
        finding("coast as drawn", Detector.ARCS, F.DELTA_LOBES_ROUND)
        finding("coast as drawn", Detector.ALIGNED_SIDE, F.DRAWN_COAST_ON_THE_GRID)
        finding("lake fans", Detector.ALIGNED_SIDE, F.LAKE_FANS_STAMPED)
        finding("lake fans", Detector.ARCS, F.LAKE_FANS_STAMPED)
        finding("river courses", Detector.ARCS, F.RIVER_ARC)
        finding("isobaths", Detector.FACETS, F.SEA_FLOOR_RAMPS)
        finding("isobaths", Detector.ALIGNED_SIDE, F.SEA_FLOOR_RAMPS)
        finding("isobaths", Detector.ARCS, F.SEAMOUNT_CONES)
        for (detector in listOf(Detector.FACETS, Detector.ALIGNED_SIDE, Detector.ISOTROPY)) finding("terrain contours", detector, F.STRAIGHT_RANGE_FRONTS)
        finding("terrain contours", Detector.ARCS, F.TERRAIN_ARC)
        for (detector in listOf(Detector.ALIGNED_SIDE, Detector.FACETS, Detector.RIGHT_ANGLES)) {
            finding("biome edges", detector, F.BIOME_EDGES_ALONG_ROWS)
            finding("peoples' borders", detector, F.PEOPLES_BORDERS_ON_THE_GRID)
        }
        finding("realm borders", Detector.RIGHT_ANGLES, F.REALM_BORDER_CORNERS)
        for (detector in listOf(Detector.ISOTROPY, Detector.FACETS, Detector.ALIGNED_SIDE)) finding("isotherms", detector, F.ISOTHERMS_ALONG_ROWS)
        finding("isohyets", Detector.FACETS, F.ISOHYETS_STRAIGHT)
        finding("sea temperature anomaly", Detector.ALIGNED_SIDE, F.ANOMALY_ALONG_ROWS)
        finding("sea temperature anomaly", Detector.FACETS, F.ANOMALY_ALONG_ROWS)
        finding("sea temperature anomaly", Detector.RIGHT_ANGLES, F.ANOMALY_SQUARE_CORNERS)
        finding("plate boundaries", Detector.ALIGNED_SIDE, F.PLATE_BOUNDARY_ON_THE_GRID)
        finding("plate boundaries", Detector.ARCS, F.PLATE_BOUNDARY_ARC)
    }

    /** The four standard worlds at 512. */
    fun at512(expected: Expectations) = with(expected) {
    }

    /** The six audited worlds and 969495 at 2048. */
    fun at2048(expected: Expectations) = with(expected) {
    }
}
