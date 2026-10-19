package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One calendar half-year's Hadley circulation at each longitude: the zonal-mean cells
 * ([HadleyCirculation]) with their rising branch moved, column by column, to that longitude's own
 * energy flux equator, so the ITCZ goes inland over a summer continent and stays near the equator
 * over the open sea.
 *
 * **The local energy flux equator.** Adam, Bischoff and Schneider (2016, J. Climate 29, read) find
 * the ITCZ at each longitude where the meridional part of the divergent energy transport vanishes,
 * and to first order a sector's ITCZ at its cross-equatorial transport over its own net energy
 * input, which "captures the seasonal migrations of the ITCZ in the African, Asian, and Atlantic
 * sectors". In the energy balance a band's transport runs down the gradient of its mean air, the
 * land column and the marine column by the band's land share ([ZonalClimate.bandC]); a sector's is
 * the same two columns by the sector's own land share. Over the tropics each column is a dome, the
 * least-squares parabola through its bands ([domeOver]), whose transport is a straight line through
 * zero at its vertex with a slope that is its net energy input, and the blend's transport vanishes
 * at the two vertices' mean weighted by each column's share and slope
 * ([sectorEnergyFluxEquatorDegrees]): the first-order theory's ITCZ at the transport over the net
 * input, for two columns. That is smooth and monotone in the share, so the heating moves
 * continuously with the land and never jumps from one maximum to another as an argmax across the
 * blend would (conventions rule 13).
 *
 * **The sector** is each column's longitude weighted by a Gaussian of the equatorial Rossby radius
 * ([equatorialRossbyRadiusMeters]), over the latitudes the energy flux equator can take, from the
 * marine column's warmest to the land's. Gill (1980, Q. J. R. Meteorol. Soc. 106, read) states the
 * tropical response to a heating in that length, about ten degrees of latitude, and its Hadley part
 * stays within the heated region and close west of it; Adam and others smooth their zonally varying
 * energy flux equator with a 2,500 km running mean, a standard deviation of 720 km, and find the
 * framework "inapplicable at small regional scales". On Earth the radius is 830 km.
 *
 * **The local cells.** Each column's rising branch moves by its heating shift, the ITCZ following
 * the energy flux equator one for one, as the first-order theory has it; and each column's cells end
 * where its own sector's troposphere turns baroclinically unstable ([HadleyCirculation.baroclinicOnsetDegrees]
 * on the sector's air, its land and marine columns by its own land share at every latitude), since
 * the storm tracks the eddies grow in are local: regions of eddy growth at the local minima of the
 * Eady growth period, on the scale of the deformation radius, about 1,000 km (Williams, Wilson and
 * Hughes 2007, J. Phys. Oceanogr. 37, read). The sector here is a Gaussian of that radius
 * ([PressureWind.rossbyRadiusKm]) along each latitude circle ([sectorEdges]). The eddy-driven belts
 * poleward of the cells, the westerlies, the polar front and the polar easterlies, start from each
 * column's own edge ([HadleyCirculation.beltWindMps]), at the zonal circulation's westerly peaks;
 * on a sector of the band's own land share at every latitude the edges are the zonal circulation's.
 * Each cell is carried in the sine of latitude from its edge to the moved rising branch
 * ([zonalSineOf]), as [HadleyCirculation] carries the axisymmetric cells onto its own edges.
 *
 * **Mass continuity.** What is carried is the lower branch's mass flux, `v cos(phi)`, which the
 * zonal cells integrate from their vertical motion and which vanishes at both edges, and not the
 * wind: a cell squeezed toward its edge carries the same mass on a shorter path and rises and sinks
 * harder over it. Each column's overturning therefore closes between the same edges, and the zonal
 * wind's change from one column to the next adds a divergence along each latitude circle whose
 * integral round the circle is zero: an overturning in each meridian's plane and one along each
 * latitude circle, each closed. The zonal wind is carried with the cell, its momentum balance read
 * where the zonal cell's own was.
 *
 * **The pressure** that holds this wind is its drag balance integrated down each column through the
 * rows where its wind differs from the zonal circulation's, as [BoundaryLayer.zonalPressurePa]
 * integrates the zonal mean, and elsewhere the zonal mean itself. What the integral leaves at the
 * last such row is spread back over those rows, so the trough moves with the rising branch and the
 * subpolar trough with the polar front, and the pressure is continuous where the column meets the
 * zonal mean ([Belts.pressurePa]).
 */
internal class LocalCirculation private constructor(
    /** The zonal circulation the columns' cells are carried from. */
    val zonal: HadleyCirculation,
    /** Each column's heating latitude less the zonal one, degrees north. */
    val heatingShiftDegrees: DoubleArray,
    /** Each column's rising branch, degrees north. */
    val ascentLatitudeDegrees: DoubleArray,
    /** Each column's cells' northern edge, degrees north, where its sector turns baroclinically unstable. */
    val northEdgeDegrees: DoubleArray,
    /** Each column's cells' southern edge, degrees south, positive. */
    val southEdgeDegrees: DoubleArray
) {
    private val zonalNorthEdgeSine = sin(zonal.northEdgeDegrees * DEGREES)
    private val zonalSouthEdgeSine = -sin(zonal.southEdgeDegrees * DEGREES)
    private val zonalAscentSine = sin(zonal.ascentLatitudeDegrees * DEGREES)
    private val northEdgeSine = DoubleArray(northEdgeDegrees.size) { sin(northEdgeDegrees[it] * DEGREES) }
    private val southEdgeSine = DoubleArray(southEdgeDegrees.size) { -sin(southEdgeDegrees[it] * DEGREES) }

    /** Each column's eddy-driven belts, from its own edges. */
    val northBelts: Array<HadleyCirculation.Belt> = Array(northEdgeDegrees.size) { zonal.Belt(northEdgeDegrees[it]) }
    val southBelts: Array<HadleyCirculation.Belt> = Array(southEdgeDegrees.size) { zonal.Belt(southEdgeDegrees[it]) }

    /** Whether [latitudeDegrees] lies inside column [column]'s cells. */
    fun inCells(column: Int, latitudeDegrees: Double): Boolean {
        val sine = sin(latitudeDegrees * DEGREES)
        return sine > southEdgeSine[column] && sine < northEdgeSine[column]
    }

    /**
     * The zonal cells' sine that the sine [localSine] inside column [column]'s cells stands for: each
     * cell carried from the column's own edge to the zonal cell's and from the column's rising
     * branch to the zonal one by a monotone cubic in the sine ([carried]), whose slope is one at the
     * edges and the same either side of the rising branch. Carried linearly instead, the map's slope
     * stepped at each edge, and with it the vertical motion its mass flux sets, and the rain drew
     * both edges along their rows. Outside the column's cells, the sine itself.
     */
    fun zonalSineOf(column: Int, localSine: Double): Double {
        val southEdge = southEdgeSine[column]
        val northEdge = northEdgeSine[column]
        if (localSine <= southEdge || localSine >= northEdge) return localSine
        val localAscentSine = sin(ascentLatitudeDegrees[column] * DEGREES)
        val southSlope = (zonalAscentSine - zonalSouthEdgeSine) / (localAscentSine - southEdge)
        val northSlope = (zonalNorthEdgeSine - zonalAscentSine) / (northEdge - localAscentSine)
        // Fritsch and Butland's slope at the interior knot, the secants' harmonic mean, which keeps
        // a monotone piecewise cubic monotone.
        val ascentSlope = 2.0 * southSlope * northSlope / (southSlope + northSlope)
        return if (localSine <= localAscentSine) {
            carried(localSine, southEdge, localAscentSine, zonalSouthEdgeSine, zonalAscentSine, EDGE_SLOPE, ascentSlope)
        } else {
            carried(localSine, localAscentSine, northEdge, zonalAscentSine, zonalNorthEdgeSine, ascentSlope, EDGE_SLOPE)
        }
    }

    /**
     * The cubic Hermite from ([from], [fromValue]) to ([to], [toValue]) with end slopes [fromSlope]
     * and [toSlope], at [x]; the slopes scaled down together where they would carry it past
     * monotone, by Fritsch and Carlson's (1980) bound of three times the secant in quadrature.
     */
    private fun carried(x: Double, from: Double, to: Double, fromValue: Double, toValue: Double, fromSlope: Double, toSlope: Double): Double {
        val width = to - from
        val secant = (toValue - fromValue) / width
        var startSlope = fromSlope
        var endSlope = toSlope
        val alpha = startSlope / secant
        val beta = endSlope / secant
        val size = alpha * alpha + beta * beta
        if (size > MONOTONE_BOUND * MONOTONE_BOUND) {
            val scale = MONOTONE_BOUND / sqrt(size)
            startSlope *= scale
            endSlope *= scale
        }
        val t = (x - from) / width
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * fromValue + (t3 - 2 * t2 + t) * width * startSlope +
            (-2 * t3 + 3 * t2) * toValue + (t3 - t2) * width * endSlope
    }

    /**
     * The surface wind of column [column]'s circulation at [latitudeDegrees], meters a second,
     * eastward and northward: inside its cells the zonal circulation's at the latitude the column's
     * cells carry there, its northward mass flux `v cos(phi)` held; outside them its own belts.
     */
    fun windMps(column: Int, latitudeDegrees: Double): Pair<Double, Double> {
        if (!inCells(column, latitudeDegrees)) return zonal.beltWindMps(latitudeDegrees, northBelts[column], southBelts[column])
        val localSine = sin(latitudeDegrees * DEGREES)
        val zonalSine = zonalSineOf(column, localSine)
        if (zonalSine == localSine) return zonal.windMps(latitudeDegrees)
        val zonalLatitude = asin(zonalSine) / DEGREES
        val (eastward, northward) = zonal.windMps(zonalLatitude)
        val massFluxShare = sqrt(1.0 - zonalSine * zonalSine) / sqrt(1.0 - localSine * localSine)
        return eastward to northward * massFluxShare
    }

    /**
     * The circulation's surface wind and the sea-level pressure that holds it on every cell of a
     * map [cellsAcross] by [cellsDown], row-major: the wind in meters a second, eastward and
     * southward, and the pressure in pascals ([BoundaryLayer.Belts]).
     */
    fun belts(cellsAcross: Int, cellsDown: Int, radiusMeters: Double): BoundaryLayer.Belts {
        val cellCount = cellsAcross * cellsDown
        val eastward = FloatArray(cellCount)
        val southward = FloatArray(cellCount)
        val zonalRows = HadleyCirculation.windOfRows(zonal, cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val latitude = ClimateStage.latitudeOf(row, cellsDown).toDouble()
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    val (east, north) = windMps(column, latitude)
                    eastward[cell] = east.toFloat()
                    southward[cell] = (-north).toFloat()
                }
            }
        }
        val zonalPressure = BoundaryLayer.zonalPressurePa(cellsDown, radiusMeters, zonalRows.eastwardMps, zonalRows.southwardMps)
        val pressure = DoubleArray(cellCount) { zonalPressure[it / cellsAcross] }
        val spacingMeters = radiusMeters * PI / cellsDown
        val density = PressureWind.AIR_DENSITY_KG_PER_M3.toDouble()
        parallelChunks(0, cellsAcross) { startColumn, endColumn -> for (column in startColumn until endColumn) {
            // The rows where the column's wind is not the zonal circulation's, and one either side.
            var firstDiffering = -1
            var lastDiffering = -1
            for (row in 0 until cellsDown) {
                val cell = row * cellsAcross + column
                if (eastward[cell] != zonalRows.eastwardMps[row] || southward[cell] != zonalRows.southwardMps[row]) {
                    if (firstDiffering < 0) firstDiffering = row
                    lastDiffering = row
                }
            }
            if (firstDiffering < 0) continue
            val above = (firstDiffering - 1).coerceAtLeast(0)
            val below = (lastDiffering + 1).coerceAtMost(cellsDown - 1)
            if (below <= above) continue
            fun northwardGradient(row: Int): Double {
                val cell = row * cellsAcross + column
                val coriolis = PressureWind.coriolisParameter(ClimateStage.latitudeOf(row, cellsDown)).toDouble()
                val drag = PressureWind.beltDrag(eastward[cell], southward[cell])
                return -density * (drag * -southward[cell] + coriolis * eastward[cell])
            }
            var previousGradient = northwardGradient(above)
            pressure[above * cellsAcross + column] = zonalPressure[above]
            for (row in above + 1..below) {
                val gradient = northwardGradient(row)
                pressure[row * cellsAcross + column] =
                    pressure[(row - 1) * cellsAcross + column] - 0.5 * (previousGradient + gradient) * spacingMeters
                previousGradient = gradient
            }
            val leftOver = pressure[below * cellsAcross + column] - zonalPressure[below]
            for (row in above + 1..below) {
                pressure[row * cellsAcross + column] -= leftOver * (row - above).toDouble() / (below - above)
            }
        } }
        return BoundaryLayer.Belts(eastward, southward, pressure)
    }

    companion object {
        private const val DEGREES = PI / 180.0

        /** The map's slope at the cells' edges: the identity's, which it meets there. */
        private const val EDGE_SLOPE = 1.0

        /** Fritsch and Carlson's (1980) radius of the end slopes over the secant within which a cubic Hermite stays monotone. */
        private const val MONOTONE_BOUND = 3.0

        /**
         * The equatorial Rossby radius on a planet of [scale], meters: Gill's (1980) `(c / 2 beta)^1/2`,
         * with `c = N D / pi` the long gravity wave of the troposphere's gravest mode under a rigid lid
         * (his equation 2.1), at the repository's stratification and tropopause
         * ([PressureWind.BUOYANCY_FREQUENCY_PER_S], [PressureWind.TROPOPAUSE_DEPTH_M]), and `beta = 2 Omega / a`
         * at the equator. 31.8 m/s and 830 km on Earth.
         */
        fun equatorialRossbyRadiusMeters(scale: WorldScale): Double {
            val gravityWaveMps = PressureWind.BUOYANCY_FREQUENCY_PER_S * PressureWind.TROPOPAUSE_DEPTH_M / PI
            val beta = 2.0 * WorldScale.ROTATION_RATE_PER_S / scale.radiusMeters
            return sqrt(gravityWaveMps / (2.0 * beta))
        }

        /**
         * A column's tropical dome: the vertex of the least-squares parabola through its bands over a
         * span of latitude, degrees north, and its curvature, kelvin per degree squared, positive for
         * a maximum (zero where the column has none over the span).
         */
        class Dome(val latitudeDegrees: Double, val curvatureKPerDegreeSquared: Double)

        /** The latitude of [bandsC]'s warmest band within [LARGEST_HEATING_DEGREES] of the equator, degrees north. */
        fun warmestLatitudeDegrees(bandsC: FloatArray): Double {
            var warmest = -1
            for (band in bandsC.indices) {
                if (kotlin.math.abs(EnergyBalance.latitudeOfBand(band)) > LARGEST_HEATING_DEGREES) continue
                if (warmest < 0 || bandsC[band] > bandsC[warmest]) warmest = band
            }
            return EnergyBalance.latitudeOfBand(warmest).toDouble()
        }

        /** [bandsC]'s [Dome] over its bands from [southDegrees] to [northDegrees]. */
        fun domeOver(bandsC: FloatArray, southDegrees: Double, northDegrees: Double): Dome {
            val centre = 0.5 * (southDegrees + northDegrees)
            // Least squares in x = latitude - center, of y = a x^2 + b x + c.
            var n = 0.0; var sx = 0.0; var sxx = 0.0; var sxxx = 0.0; var sxxxx = 0.0
            var sy = 0.0; var sxy = 0.0; var sxxy = 0.0
            for (band in bandsC.indices) {
                val latitude = EnergyBalance.latitudeOfBand(band).toDouble()
                if (latitude < southDegrees || latitude > northDegrees) continue
                val x = latitude - centre
                val y = bandsC[band].toDouble()
                n += 1; sx += x; sxx += x * x; sxxx += x * x * x; sxxxx += x * x * x * x
                sy += y; sxy += x * y; sxxy += x * x * y
            }
            // The normal equations by Cramer's rule.
            fun determinant(
                a11: Double, a12: Double, a13: Double, a21: Double, a22: Double, a23: Double, a31: Double, a32: Double, a33: Double
            ) = a11 * (a22 * a33 - a23 * a32) - a12 * (a21 * a33 - a23 * a31) + a13 * (a21 * a32 - a22 * a31)
            val whole = determinant(sxxxx, sxxx, sxx, sxxx, sxx, sx, sxx, sx, n)
            if (whole == 0.0) return Dome(centre, 0.0)
            val quadratic = determinant(sxxy, sxxx, sxx, sxy, sxx, sx, sy, sx, n) / whole
            val linear = determinant(sxxxx, sxxy, sxx, sxxx, sxy, sx, sxx, sy, n) / whole
            if (quadratic >= 0.0) return Dome(centre, 0.0)
            val halfSpan = 0.5 * (northDegrees - southDegrees)
            return Dome(centre + (-linear / (2.0 * quadratic)).coerceIn(-halfSpan, halfSpan), -2.0 * quadratic)
        }

        /**
         * The energy flux equator of a sector whose land share is [landShare], degrees north: where
         * the blend of the land column's transport and the marine column's vanishes, each column a
         * [Dome] whose transport is a straight line through zero at its vertex with a slope of its
         * curvature, so the two vertices' mean weighted by share and curvature. The marine column's
         * own where neither column has a maximum.
         */
        fun sectorEnergyFluxEquatorDegrees(landShare: Double, land: Dome, sea: Dome): Double {
            val landWeight = landShare * land.curvatureKPerDegreeSquared
            val seaWeight = (1.0 - landShare) * sea.curvatureKPerDegreeSquared
            if (landWeight + seaWeight <= 0.0) return sea.latitudeDegrees
            return (landWeight * land.latitudeDegrees + seaWeight * sea.latitudeDegrees) / (landWeight + seaWeight)
        }

        /**
         * The farthest from the equator a column's warmest latitude is sought, degrees: past the
         * subtropics a column's warmest air is the summer pole's on a tilted planet, which is no
         * tropical heating; the zonal circulation's own bound ([HadleyCirculation]) is its cells'.
         */
        private const val LARGEST_HEATING_DEGREES = 45.0

        /**
         * [season]'s circulation at each longitude of the world [config] describes, with [isLand]
         * row-major on its grid, under the energy balance [zonal] and its zonal circulation
         * [zonalCirculation].
         *
         * [heldToLandOrSea] is the guards' control: each sector taken as all land or all sea by its
         * majority, so the rising branch jumps from the sea's latitude to the land's between two
         * columns, as a heating found by an argmax across the blend would jump between its two
         * maxima (`LocalCirculationEarthTest`). [zonalEdges] is another: every column's cells end at
         * the zonal circulation's edges, as they did before the edges were read by sector, so the
         * belts and the polar front stand on one row the world round (`RainGeometryTest`).
         */
        fun forHalf(
            config: WorldGenConfig,
            zonal: ZonalClimate,
            isLand: BooleanArray,
            season: Season,
            zonalCirculation: HadleyCirculation = HadleyCirculation.forHalf(config, zonal, season),
            heldToLandOrSea: Boolean = false,
            zonalEdges: Boolean = false
        ): LocalCirculation {
            val cellsAcross = config.width
            val cellsDown = config.height
            val radiusMeters = config.scale.radiusMeters
            val rossbyRadiusMeters = equatorialRossbyRadiusMeters(config.scale)
            val rossbyRadiusDegrees = rossbyRadiusMeters / radiusMeters / DEGREES
            // Both columns over the one span, from their warmest latitudes a Rossby radius outward
            // each way: fitted over the same latitudes, the two domes' blend is the band's own, and at
            // the world's land share the blend's energy flux equator stands near the zonal
            // circulation's heating, the band mean's warmest latitude (docs/DESIGN_LEDGER.md, F1, for
            // the figures on the standard worlds). The columns' shifts are taken from that blend, so
            // a sector of the world's own land share is the zonal circulation.
            val landBands = zonal.land.at(season)
            val seaBands = zonal.sea.at(season)
            val landWarmest = warmestLatitudeDegrees(landBands)
            val seaWarmest = warmestLatitudeDegrees(seaBands)
            val spanSouth = minOf(landWarmest, seaWarmest) - rossbyRadiusDegrees
            val spanNorth = maxOf(landWarmest, seaWarmest) + rossbyRadiusDegrees
            val land = domeOver(landBands, spanSouth, spanNorth)
            val sea = domeOver(seaBands, spanSouth, spanNorth)
            val lowest = minOf(land.latitudeDegrees, sea.latitudeDegrees)
            val highest = maxOf(land.latitudeDegrees, sea.latitudeDegrees)

            // Each column's land share over the latitudes the energy flux equator can take, by area.
            val landArea = DoubleArray(cellsAcross)
            val area = DoubleArray(cellsAcross)
            for (row in 0 until cellsDown) {
                val latitude = ClimateStage.latitudeOf(row, cellsDown).toDouble()
                if (latitude < lowest || latitude > highest) continue
                val weight = cos(latitude * DEGREES)
                for (column in 0 until cellsAcross) {
                    area[column] += weight
                    if (isLand[row * cellsAcross + column]) landArea[column] += weight
                }
            }
            val zonalShare = if (area.sum() > 0.0) landArea.sum() / area.sum() else 0.0
            // The sector: a Gaussian of the Rossby radius along the equator, wrapping round the planet.
            val sigmaColumns = rossbyRadiusMeters / (radiusMeters * 2.0 * PI / cellsAcross)
            val reach = minOf(ceil(GAUSSIAN_REACH_SIGMAS * sigmaColumns).toInt(), cellsAcross / 2)
            val kernel = DoubleArray(2 * reach + 1) { exp(-0.5 * ((it - reach) / sigmaColumns).let { it * it }) }
            val shift = DoubleArray(cellsAcross)
            val zonalHeating = sectorEnergyFluxEquatorDegrees(zonalShare, land, sea)
            for (column in 0 until cellsAcross) {
                var weightedLand = 0.0
                var weightedArea = 0.0
                for (offset in -reach..reach) {
                    val other = Math.floorMod(column + offset, cellsAcross)
                    weightedLand += kernel[offset + reach] * landArea[other]
                    weightedArea += kernel[offset + reach] * area[other]
                }
                val share = if (weightedArea > 0.0) weightedLand / weightedArea else zonalShare
                val used = if (heldToLandOrSea) (if (share >= MAJORITY) 1.0 else 0.0) else share
                shift[column] = sectorEnergyFluxEquatorDegrees(used, land, sea) - zonalHeating
            }
            // The rising branch moves with the heating, and stays inside the cells by the Rossby radius
            // at least, so neither cell is carried onto less than the length its overturning is
            // organized over (Gill 1980). It binds on none of the standard worlds.
            fun bounded(moved: Double, northEdge: Double, southEdge: Double): Double {
                val northmost = northEdge - rossbyRadiusDegrees
                val southmost = -southEdge + rossbyRadiusDegrees
                return if (southmost >= northmost) zonalCirculation.ascentLatitudeDegrees else moved.coerceIn(southmost, northmost)
            }
            val (northEdge, southEdge) = if (zonalEdges) {
                DoubleArray(cellsAcross) { zonalCirculation.northEdgeDegrees } to DoubleArray(cellsAcross) { zonalCirculation.southEdgeDegrees }
            } else {
                sectorEdges(config, zonal, isLand, season, zonalCirculation, heldToLandOrSea)
            }
            val ascent = DoubleArray(cellsAcross) { column ->
                bounded(zonalCirculation.ascentLatitudeDegrees + shift[column], northEdge[column], southEdge[column])
            }
            return LocalCirculation(zonalCirculation, shift, ascent, northEdge, southEdge)
        }

        /**
         * Each column's cells' edges, degrees from the equator, north then south: where its sector's
         * troposphere turns baroclinically unstable ([HadleyCirculation.baroclinicOnsetDegrees]), the
         * sector's air its land and marine columns by its own land share, to first order in the share
         * about each column's own onset ([blendedOnsetDegrees]), and the shift from the zonal
         * circulation's edge taken from that blend, so a sector of the band's own land share stands
         * at the zonal edge.
         *
         * The sector is a Gaussian of the storm track's deformation radius on the ground
         * ([PressureWind.rossbyRadiusKm], 970 km on Earth), the scale the eddies that end the cells
         * grow on, along each band's latitude circle and then across the bands, so its share is the
         * large-scale land's and not a coast's. Its shares are read at longitudes [SECTOR_SAMPLES_PER_RADIUS]
         * to the radius at the equator, each bin's land share first pooled over its own map columns,
         * and the edges carried to every column linearly between them: a length on the ground and not
         * a count of cells, so every grid fine enough to hold the radius reads the same edges. Where
         * either column never turns unstable short of the search's end the zonal circulation's edge
         * stands at every longitude.
         * [heldToLandOrSea] holds every sector to all land or all sea by its majority, the guards'
         * control, as [forHalf]'s does for the rising branch.
         */
        private fun sectorEdges(
            config: WorldGenConfig,
            zonal: ZonalClimate,
            isLand: BooleanArray,
            season: Season,
            zonalCirculation: HadleyCirculation,
            heldToLandOrSea: Boolean
        ): Pair<DoubleArray, DoubleArray> {
            val cellsAcross = config.width
            val cellsDown = config.height
            val radiusMeters = config.scale.radiusMeters
            val storm = PressureWind.rossbyRadiusKm() * METERS_PER_KM
            val samples = maxOf(MIN_SECTOR_SAMPLES, ceil(2.0 * PI * radiusMeters * SECTOR_SAMPLES_PER_RADIUS / storm).toInt())
                .coerceAtMost(cellsAcross)
            val bands = EnergyBalance.BANDS
            // Each band's land share in each longitude bin, pooled from the map's rows whose center
            // lies in the band; a band with no rows takes the one before it.
            val landInBin = Array(bands) { DoubleArray(samples) }
            val cellsInBin = Array(bands) { DoubleArray(samples) }
            for (row in 0 until cellsDown) {
                val latitude = ClimateStage.latitudeOf(row, cellsDown)
                val band = ((EnergyBalance.POLE_DEGREES - latitude) * bands / EnergyBalance.POLE_TO_POLE_DEGREES).toInt().coerceIn(0, bands - 1)
                for (column in 0 until cellsAcross) {
                    val bin = column * samples / cellsAcross
                    cellsInBin[band][bin] += 1.0
                    if (isLand[row * cellsAcross + column]) landInBin[band][bin] += 1.0
                }
            }
            val binShare = Array(bands) { DoubleArray(samples) }
            var lastBand = -1
            for (band in 0 until bands) {
                if (cellsInBin[band].sum() > 0.0) {
                    for (bin in 0 until samples) binShare[band][bin] = if (cellsInBin[band][bin] > 0.0) landInBin[band][bin] / cellsInBin[band][bin] else 0.0
                    lastBand = band
                } else if (lastBand >= 0) {
                    binShare[band] = binShare[lastBand].copyOf()
                }
            }
            // Each band's sector share at each sample: the Gaussian of the storm's radius along the
            // band's circle, wrapping round the planet.
            val landBands = zonal.land.at(season)
            val seaBands = zonal.sea.at(season)
            val sectorShare = Array(bands) { DoubleArray(samples) }
            for (band in 0 until bands) {
                val circleMeters = 2.0 * PI * radiusMeters * cos(EnergyBalance.latitudeOfBand(band) * DEGREES)
                val sigmaBins = storm / (circleMeters / samples)
                val reach = minOf(ceil(GAUSSIAN_REACH_SIGMAS * sigmaBins).toInt(), samples / 2)
                for (sample in 0 until samples) {
                    var weighted = 0.0
                    var total = 0.0
                    for (offset in -reach..reach) {
                        val weight = exp(-0.5 * (offset / sigmaBins) * (offset / sigmaBins))
                        weighted += weight * binShare[band][Math.floorMod(sample + offset, samples)]
                        total += weight
                    }
                    sectorShare[band][sample] = weighted / total
                }
            }
            // And the same Gaussian across the latitude circles: the gradient the eddies feel is the
            // large-scale air's, so a coast running along a band does not by itself make a front.
            val sigmaBands = storm / (PI * radiusMeters / bands)
            val bandReach = ceil(GAUSSIAN_REACH_SIGMAS * sigmaBands).toInt()
            val acrossBands = Array(bands) { DoubleArray(samples) }
            for (band in 0 until bands) {
                for (sample in 0 until samples) {
                    var weighted = 0.0
                    var total = 0.0
                    for (offset in -bandReach..bandReach) {
                        val other = band + offset
                        if (other < 0 || other >= bands) continue
                        val weight = exp(-0.5 * (offset / sigmaBands) * (offset / sigmaBands))
                        weighted += weight * sectorShare[other][sample]
                        total += weight
                    }
                    acrossBands[band][sample] = weighted / total
                }
            }
            // The sector's share at a latitude, between the bands' centers.
            fun shareAt(sample: Int, latitudeDegrees: Double): Double {
                val position = (EnergyBalance.POLE_DEGREES - latitudeDegrees) * bands / EnergyBalance.POLE_TO_POLE_DEGREES - 0.5
                val lower = position.toInt().coerceIn(0, bands - 1)
                val upper = (lower + 1).coerceAtMost(bands - 1)
                val within = (position - lower).coerceIn(0.0, 1.0)
                val smooth = acrossBands[lower][sample] * (1.0 - within) + acrossBands[upper][sample] * within
                return if (heldToLandOrSea) (if (smooth >= MAJORITY) 1.0 else 0.0) else smooth
            }
            fun sampled(north: Boolean, zonalEdge: Double): DoubleArray {
                val sign = if (north) 1.0 else -1.0
                val from = maxOf(sign * zonalCirculation.ascentLatitudeDegrees, 0.0) + ONSET_STEP_DEGREES
                val land = onsetOf(landBands, zonal, north, from)
                val sea = onsetOf(seaBands, zonal, north, from)
                if (land == null || sea == null) return DoubleArray(samples) { zonalEdge }
                val zonalShare = zonal.bandValueAt(zonal.landShare, (sign * zonalEdge).toFloat()).toDouble()
                val zonalBlend = blendedOnsetDegrees(zonalShare, land, sea)
                return DoubleArray(samples) { sample ->
                    // The share read where the edge stands, found by one step from the zonal edge.
                    val first = zonalEdge + blendedOnsetDegrees(shareAt(sample, sign * zonalEdge), land, sea) - zonalBlend
                    zonalEdge + blendedOnsetDegrees(shareAt(sample, sign * first), land, sea) - zonalBlend
                }
            }
            val sampleNorth = sampled(north = true, zonalCirculation.northEdgeDegrees)
            val sampleSouth = sampled(north = false, zonalCirculation.southEdgeDegrees)
            fun toColumns(sampled: DoubleArray) = DoubleArray(cellsAcross) { column ->
                val position = (column + 0.5) * samples / cellsAcross - 0.5
                val before = kotlin.math.floor(position).toInt()
                val share = position - before
                sampled[Math.floorMod(before, samples)] * (1.0 - share) + sampled[Math.floorMod(before + 1, samples)] * share
            }
            return toColumns(sampleNorth) to toColumns(sampleSouth)
        }

        /**
         * Where a column of the energy balance, [bandsC], first turns baroclinically unstable poleward
         * of [fromDegrees] in the hemisphere [north] says ([HadleyCirculation.baroclinicOnsetDegrees]),
         * with the supercriticality's slope there, per degree; null where it never does, or does not
         * cross rising.
         */
        fun onsetOf(bandsC: FloatArray, zonal: ZonalClimate, north: Boolean, fromDegrees: Double): Onset? {
            val latitude = HadleyCirculation.baroclinicOnsetDegrees(bandsC, zonal, north, fromDegrees) ?: return null
            val slope = HadleyCirculation.supercriticalitySlopePerDegree(bandsC, zonal, north, latitude)
            return if (slope > 0.0) Onset(latitude, slope) else null
        }

        /** A column's baroclinic onset, degrees from the equator, and its supercriticality's slope there, per degree. */
        class Onset(val latitudeDegrees: Double, val slopePerDegree: Double)

        /**
         * The onset of a sector whose land share is [landShare], degrees from the equator: Held's
         * criterion on the blend of the [land] and [sea] columns' air, linear in the share at each
         * latitude, its supercriticality each column's own linearized about its onset, so the blend
         * reaches one at the onsets' mean weighted by share and slope. Smooth and monotone in the
         * share, where the first crossing of the blend itself jumps between two crossings as the
         * share moves (rule 13); the first-order onset, as [sectorEnergyFluxEquatorDegrees] is the
         * first-order energy flux equator.
         */
        fun blendedOnsetDegrees(landShare: Double, land: Onset, sea: Onset): Double {
            val landWeight = landShare * land.slopePerDegree
            val seaWeight = (1.0 - landShare) * sea.slopePerDegree
            return (landWeight * land.latitudeDegrees + seaWeight * sea.latitudeDegrees) / (landWeight + seaWeight)
        }

        /**
         * Longitudes a sector's share is read at per storm radius at the equator: four, so the
         * Gaussian's own curve is drawn through several points a radius and its linear reading
         * between them stands within about a hundredth of its swing.
         */
        private const val SECTOR_SAMPLES_PER_RADIUS = 4.0

        /** The fewest longitudes a sector is read at, on a planet small beside its storms: enough to draw a Gaussian round it. */
        private const val MIN_SECTOR_SAMPLES = 16

        /** The step the edge is searched from past the rising branch, as [HadleyCirculation.forHalf] steps it, degrees. */
        private const val ONSET_STEP_DEGREES = 0.25

        private const val METERS_PER_KM = 1_000.0

        /** The control's majority: half the sector. */
        private const val MAJORITY = 0.5

        /** Standard deviations a Gaussian is carried to: past four, its weight is under 0.03% of its peak. */
        private const val GAUSSIAN_REACH_SIGMAS = 4.0
    }
}
