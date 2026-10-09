package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.ComplexFft
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * A latitude-longitude grid on a sphere, with its metric: the rows' latitudes and cosines, every
 * cell's area on the sphere and the lengths of its faces.
 *
 * Rows are cell-centered and run from the north pole to the south, as the map's do
 * (`ClimateStage.latitudeOf`), so row `j` spans the latitudes between faces `j` and `j + 1`, face 0
 * being the north pole and face [rows] the south. Columns run east from the map's western edge, so a
 * ground cell and a cell of this grid that share a longitude share it exactly. Nothing sits on a
 * pole: the poles are faces, of zero length, which is what lets a flux form close them without a
 * special case.
 *
 * The same type serves the atmosphere's coarse grid ([forAtmosphere]) and the map's own
 * ([forGround]), because the operators and the remapping are statements about the sphere and not
 * about any one grid (conventions rule 14).
 *
 * A field on the grid is a `DoubleArray` row-major over the cell centers, `rows * columns`; a
 * meridional component lives on the faces, `(rows + 1) * columns`, face `f` of column `i` at
 * `f * columns + i`.
 */
class SphericalGrid(val rows: Int, val columns: Int, val radiusMeters: Double) {

    init {
        require(rows >= 2 && columns >= 2) { "a spherical grid needs two rows and two columns, got $rows by $columns" }
        // A meridian continued over a pole arrives on the meridian opposite, which is a column
        // only when the columns are even in number.
        require(columns % 2 == 0) { "a spherical grid needs an even number of columns, got $columns" }
    }

    /** Latitude between neighboring faces, in radians. */
    val rowSpacingRadians: Double = PI / rows

    /** Longitude between neighboring columns, in radians. */
    val columnSpacingRadians: Double = 2.0 * PI / columns

    /** Each row's center latitude, in radians, north positive. */
    val latitudeRadians = DoubleArray(rows) { PI / 2 - (it + 0.5) * rowSpacingRadians }

    /** `cos` of each row's center latitude. */
    val cosLatitude = DoubleArray(rows) { cos(latitudeRadians[it]) }

    /** `sin` of each face's latitude: exactly one at the north pole and minus one at the south. */
    val sinFace = DoubleArray(rows + 1) { face ->
        when (face) {
            0 -> 1.0
            rows -> -1.0
            else -> sin(PI / 2 - face * rowSpacingRadians)
        }
    }

    /**
     * `cos` of each face's latitude: exactly zero at both poles, where the face has no length and
     * no flux crosses it.
     */
    val cosFace = DoubleArray(rows + 1) { face ->
        if (face == 0 || face == rows) 0.0 else cos(PI / 2 - face * rowSpacingRadians)
    }

    /**
     * How much the sine of latitude falls across each row: `sin(phi_north) - sin(phi_south)`,
     * written as `2 cos(phi) sin(dphi / 2)` so the polar rows keep their precision.
     */
    val sinSpanOfRow = DoubleArray(rows) { 2.0 * cosLatitude[it] * sin(rowSpacingRadians / 2) }

    /** Each cell's area on the sphere, square meters: `a^2 dlambda (sin phi_north - sin phi_south)`. */
    val cellAreaSquareMeters = DoubleArray(rows) {
        radiusMeters * radiusMeters * columnSpacingRadians * sinSpanOfRow[it]
    }

    /** The length of a cell's east or west face, meters: the same `a dphi` on every row. */
    val eastFaceLengthMeters: Double = radiusMeters * rowSpacingRadians

    /** The length of each face along a row, meters: `a cos(phi) dlambda`, zero at the poles. */
    val northFaceLengthMeters = DoubleArray(rows + 1) { radiusMeters * cosFace[it] * columnSpacingRadians }

    /** The spacing down a meridian, meters: the grid's resolution on the ground. */
    val rowSpacingMeters: Double = radiusMeters * rowSpacingRadians

    val cellCount: Int get() = rows * columns

    /** The plan every row's transform shares. */
    internal val rowTransform: ComplexFft by lazy { ComplexFft(columns) }

    /** The sphere's whole area as this grid sums it, square meters: `4 pi a^2` to rounding. */
    val totalAreaSquareMeters: Double get() = cellAreaSquareMeters.sum() * columns

    companion object {
        /**
         * The atmosphere's grid on the planet [scale] describes: [rowsForAtmosphere] rows and twice
         * as many columns.
         */
        fun forAtmosphere(scale: WorldScale): SphericalGrid {
            val rows = rowsForAtmosphere(scale.radiusMeters)
            return SphericalGrid(rows, 2 * rows, scale.radiusMeters)
        }

        /** The map's own grid: [cellsAcross] columns and [cellsDown] rows on the planet [scale] describes. */
        fun forGround(cellsAcross: Int, cellsDown: Int, scale: WorldScale): SphericalGrid =
            SphericalGrid(cellsDown, cellsAcross, scale.radiusMeters)

        /**
         * How many rows the atmosphere needs on a planet of [radiusMeters], spinning at
         * [WorldScale.ROTATION_RATE_PER_S]: enough that the smallest forcing scale its guards read
         * is resolved by the operators to [OPERATOR_TOLERANCE], and no more.
         *
         * The smallest of those scales is the storm track's, the first baroclinic deformation radius
         * `L_d = N H / f` at 45 degrees ([PressureWind.rossbyRadiusKm], 970 km on Earth): a monsoon
         * of about 2,000 km and a subtropical cell of 3,000 to 5,000 km are both wider. The rule is
         * [CELLS_PER_DEFORMATION_RADIUS] rows across `L_d`, so
         *
         * `rows = q pi a / L_d = q pi a 2 Omega sin(45 deg) / (N H)`,
         *
         * rounded up until the `2 rows` longitudes are 5-smooth for the transform. The rows go as the
         * radius times the spin; on Earth's planet that is 120, at half the radius 60 and at twice
         * 240. The third vertical mode's deformation radius in mid-latitudes, about 100 km, is not
         * the scale: the forcing has none of it, and whether the winds and vertical motion the model
         * makes from that forcing converge at this grid is the dry model's to show.
         */
        fun rowsForAtmosphere(radiusMeters: Double): Int {
            val deformationRadiusMeters = PressureWind.rossbyRadiusKm() * WorldScale.METRES_PER_KM
            val rowsNeeded = ceil(CELLS_PER_DEFORMATION_RADIUS * PI * radiusMeters / deformationRadiusMeters).toInt()
            var columns = 2 * rowsNeeded
            while (!ComplexFft.isFiveSmooth(columns)) columns += 2
            return columns / 2
        }

        /**
         * The relative error, root mean square over the sphere's area, that the gradient,
         * divergence, curl and Laplacian may make on a feature of the storm track's scale.
         *
         * One percent. The first guard the atmosphere is built for reads a subtropical cell's
         * prominence at 2 hPa on eddies of Earth's 10 to 20 hPa; a percent of 20 hPa is 0.2 hPa,
         * a tenth of that prominence, so the grid cannot make or unmake a cell the guard counts.
         */
        const val OPERATOR_TOLERANCE = 0.01

        /**
         * Rows across one deformation radius: the fewest at which every operator meets
         * [OPERATOR_TOLERANCE] on a Gaussian of that standard deviation, measured on the ladder of
         * grids `SphericalOperatorsTest` runs and recorded in docs/DESIGN_LEDGER.md, A1-2.
         */
        const val CELLS_PER_DEFORMATION_RADIUS = 7.1
    }
}
