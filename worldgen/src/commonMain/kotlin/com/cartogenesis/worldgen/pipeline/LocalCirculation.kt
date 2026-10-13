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
 * **The local cells.** The zonal circulation's cells keep their edges, where baroclinic eddies take
 * over and which the land does not move here, and their rising branch moves by the column's heating
 * shift, the ITCZ following the energy flux equator one for one, as the first-order theory has it.
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
 * cells from the northern edge, as [BoundaryLayer.zonalPressurePa] integrates the zonal mean, and
 * outside the cells the zonal mean itself, where the column's wind is the zonal one. What the
 * integral leaves at the southern edge is spread back over the cells in the row, so the trough
 * moves with the rising branch and the pressure is continuous at both edges ([Belts.pressurePa]).
 */
internal class LocalCirculation private constructor(
    /** The zonal circulation the columns' cells are carried from. */
    val zonal: HadleyCirculation,
    /** Each column's heating latitude less the zonal one, degrees north. */
    val heatingShiftDegrees: DoubleArray,
    /** Each column's rising branch, degrees north. */
    val ascentLatitudeDegrees: DoubleArray
) {
    private val northEdgeSine = sin(zonal.northEdgeDegrees * DEGREES)
    private val southEdgeSine = -sin(zonal.southEdgeDegrees * DEGREES)
    private val zonalAscentSine = sin(zonal.ascentLatitudeDegrees * DEGREES)

    /**
     * The zonal cells' sine that the sine [localSine] of column [column]'s cells stands for: the
     * identity outside the cells, and inside them each cell carried from its edge to the column's
     * rising branch by a monotone cubic in the sine ([carried]), whose slope is one at the edges, as
     * the identity's is outside them, and the same either side of the rising branch. Carried
     * linearly instead, the map's slope stepped at each edge, and with it the vertical motion its
     * mass flux sets, and the rain drew both edges along their rows.
     */
    fun zonalSineOf(column: Int, localSine: Double): Double {
        if (localSine <= southEdgeSine || localSine >= northEdgeSine) return localSine
        val localAscentSine = sin(ascentLatitudeDegrees[column] * DEGREES)
        val southSlope = (zonalAscentSine - southEdgeSine) / (localAscentSine - southEdgeSine)
        val northSlope = (northEdgeSine - zonalAscentSine) / (northEdgeSine - localAscentSine)
        // Fritsch and Butland's slope at the interior knot, the secants' harmonic mean, which keeps
        // a monotone piecewise cubic monotone.
        val ascentSlope = 2.0 * southSlope * northSlope / (southSlope + northSlope)
        return if (localSine <= localAscentSine) {
            carried(localSine, southEdgeSine, localAscentSine, southEdgeSine, zonalAscentSine, EDGE_SLOPE, ascentSlope)
        } else {
            carried(localSine, localAscentSine, northEdgeSine, zonalAscentSine, northEdgeSine, ascentSlope, EDGE_SLOPE)
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
     * eastward and northward: the zonal circulation's at the latitude the column's cells carry
     * there, its northward mass flux `v cos(phi)` held.
     */
    fun windMps(column: Int, latitudeDegrees: Double): Pair<Double, Double> {
        val localSine = sin(latitudeDegrees * DEGREES)
        val zonalSine = zonalSineOf(column, localSine)
        if (zonalSine == localSine) return zonal.windMps(latitudeDegrees)
        val zonalLatitude = asin(zonalSine) / DEGREES
        val (eastward, northward) = zonal.windMps(zonalLatitude)
        val massFluxShare = sqrt(1.0 - zonalSine * zonalSine) / sqrt(1.0 - localSine * localSine)
        return eastward to northward * massFluxShare
    }

    /**
     * The circulation's surface wind on every cell of a map [cellsAcross] by [cellsDown], row-major,
     * meters a second, eastward and southward ([windMps]).
     */
    fun surfaceWindMps(cellsAcross: Int, cellsDown: Int): PressureWind.Vectors {
        val cellCount = cellsAcross * cellsDown
        val eastward = FloatArray(cellCount)
        val southward = FloatArray(cellCount)
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
        return PressureWind.Vectors(eastward, southward)
    }

    /**
     * The circulation's surface wind and the sea-level pressure that holds it on every cell of a
     * map [cellsAcross] by [cellsDown], row-major: the wind in meters a second, eastward and
     * southward, and the pressure in pascals ([BoundaryLayer.Belts]).
     */
    fun belts(cellsAcross: Int, cellsDown: Int, radiusMeters: Double): BoundaryLayer.Belts {
        val wind = surfaceWindMps(cellsAcross, cellsDown)
        val eastward = wind.eastwardMps
        val southward = wind.southwardMps
        val zonalRows = HadleyCirculation.windOfRows(zonal, cellsDown)
        val cellCount = cellsAcross * cellsDown
        val zonalPressure = BoundaryLayer.zonalPressurePa(cellsDown, radiusMeters, zonalRows.eastwardMps, zonalRows.southwardMps)
        val pressure = DoubleArray(cellCount) { zonalPressure[it / cellsAcross] }
        // The rows the cells cover, and the zonal mean's own rows on either side of them.
        var firstInside = -1
        var lastInside = -1
        for (row in 0 until cellsDown) {
            val latitude = ClimateStage.latitudeOf(row, cellsDown)
            if (latitude < zonal.northEdgeDegrees && latitude > -zonal.southEdgeDegrees) {
                if (firstInside < 0) firstInside = row
                lastInside = row
            }
        }
        if (firstInside > 0 && lastInside < cellsDown - 1) {
            val spacingMeters = radiusMeters * PI / cellsDown
            val density = PressureWind.AIR_DENSITY_KG_PER_M3.toDouble()
            val above = firstInside - 1
            val below = lastInside + 1
            parallelChunks(0, cellsAcross) { startColumn, endColumn -> for (column in startColumn until endColumn) {
                fun northwardGradient(row: Int): Double {
                    val cell = row * cellsAcross + column
                    val coriolis = PressureWind.coriolisParameter(ClimateStage.latitudeOf(row, cellsDown)).toDouble()
                    val drag = PressureWind.beltDrag(eastward[cell], southward[cell])
                    return -density * (drag * -southward[cell] + coriolis * eastward[cell])
                }
                var previousGradient = northwardGradient(above)
                pressure[above * cellsAcross + column] = zonalPressure[above]
                for (row in firstInside..below) {
                    val gradient = northwardGradient(row)
                    pressure[row * cellsAcross + column] =
                        pressure[(row - 1) * cellsAcross + column] - 0.5 * (previousGradient + gradient) * spacingMeters
                    previousGradient = gradient
                }
                val leftOver = pressure[below * cellsAcross + column] - zonalPressure[below]
                for (row in firstInside..below) {
                    pressure[row * cellsAcross + column] -= leftOver * (row - above).toDouble() / (below - above)
                }
            } }
        }
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
         * maxima (`LocalCirculationEarthTest`).
         */
        fun forHalf(
            config: WorldGenConfig,
            zonal: ZonalClimate,
            isLand: BooleanArray,
            season: Season,
            zonalCirculation: HadleyCirculation = HadleyCirculation.forHalf(config, zonal, season),
            heldToLandOrSea: Boolean = false
        ): LocalCirculation = onProfiles(config, isLand, zonal.land.at(season), zonal.sea.at(season), zonalCirculation, heldToLandOrSea)

        /**
         * [zonalCirculation] at each longitude of the world [config] describes, its rising branch
         * moved by each sector's land share between two columns' profiles by latitude, [landBands]
         * and [seaBands], one entry per energy-balance band ([EnergyBalance.latitudeOfBand]): the
         * temperature of the air for the circulation that holds the surface wind ([forHalf]), the
         * subcloud moist static energy for its deep ascent ([moistStaticEnergyBands]). The domes,
         * the sector and the carrying are the same for either, so the two placements differ by what
         * the columns' profiles say and by nothing else.
         */
        fun onProfiles(
            config: WorldGenConfig,
            isLand: BooleanArray,
            landBands: FloatArray,
            seaBands: FloatArray,
            zonalCirculation: HadleyCirculation,
            heldToLandOrSea: Boolean = false
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
            val northmost = zonalCirculation.northEdgeDegrees - rossbyRadiusDegrees
            val southmost = -zonalCirculation.southEdgeDegrees + rossbyRadiusDegrees
            val ascent = DoubleArray(cellsAcross) { column ->
                val moved = zonalCirculation.ascentLatitudeDegrees + shift[column]
                if (southmost >= northmost) zonalCirculation.ascentLatitudeDegrees else moved.coerceIn(southmost, northmost)
            }
            return LocalCirculation(zonalCirculation, shift, ascent)
        }

        /**
         * The subcloud moist static energy of the land column and the marine column in each
         * energy-balance band in [season], joules per kilogram, `h = c_p T + L_v q + g z` at the
         * ground: the band's air temperature from [zonal] carried up to the land's mean height in the
         * band, [landElevationMByBand] meters, at the climate's lapse [lapseRateCPerKm], its pressure
         * hydrostatic, and its specific humidity at the surface relative humidity the march leaves
         * there, [landHumidityByBand] and [seaHumidityByBand] (0..1, one entry per band).
         *
         * Deep convection is organized by this energy and not by the air's temperature: the monsoon's
         * ascent and its rain stand just equatorward of its maximum (Privé and Plumb 2007, as Boos
         * and Emanuel 2009 and Nie, Boos and Kuang 2010 report and test it, read), while the hot,
         * dry heat low poleward of it, at the temperature's maximum, rises only in a shallow
         * circulation that returns below 600 to 700 hPa (Nie and others: "the poleward extents of
         * deep and shallow circulations are bounded by maxima" in the two).
         */
        fun moistStaticEnergyBands(
            zonal: ZonalClimate,
            season: Season,
            landHumidityByBand: FloatArray,
            seaHumidityByBand: FloatArray,
            landElevationMByBand: FloatArray,
            lapseRateCPerKm: Double
        ): Pair<FloatArray, FloatArray> {
            val landColumn = zonal.land.at(season)
            val seaColumn = zonal.sea.at(season)
            fun energy(seaLevelC: Double, elevationM: Double, relativeHumidity: Double): Double {
                val groundC = seaLevelC - lapseRateCPerKm * elevationM / METERS_PER_KM
                val pressureKpa = ColumnWater.SEA_LEVEL_PRESSURE_KPA *
                    exp(-STANDARD_GRAVITY_MPS2 * elevationM / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * (groundC + ColumnWater.KELVIN_AT_ZERO_C)))
                val humidity = relativeHumidity.coerceIn(0.0, 1.0) *
                    ColumnWater.specificHumidity(ColumnWater.saturationVaporPressureKpa(groundC), pressureKpa)
                return DRY_AIR_HEAT_CAPACITY_J_PER_KG_K * (groundC + ColumnWater.KELVIN_AT_ZERO_C) +
                    ColumnWater.LATENT_HEAT_J_PER_KG * humidity + STANDARD_GRAVITY_MPS2 * elevationM
            }
            val land = FloatArray(landColumn.size) { band ->
                energy(landColumn[band].toDouble(), landElevationMByBand[band].toDouble(), landHumidityByBand[band].toDouble()).toFloat()
            }
            val sea = FloatArray(seaColumn.size) { band -> energy(seaColumn[band].toDouble(), 0.0, seaHumidityByBand[band].toDouble()).toFloat() }
            return land to sea
        }

        /** Dry air's heat capacity and gas constant, standard gravity, and meters in a kilometer. */
        private const val DRY_AIR_HEAT_CAPACITY_J_PER_KG_K = 1004.0
        private const val DRY_AIR_GAS_CONSTANT_J_PER_KG_K = 287.04
        private const val STANDARD_GRAVITY_MPS2 = 9.80665
        private const val METERS_PER_KM = 1_000.0

        /** The control's majority: half the sector. */
        private const val MAJORITY = 0.5

        /** Standard deviations a Gaussian is carried to: past four, its weight is under 0.03% of its peak. */
        private const val GAUSSIAN_REACH_SIGMAS = 4.0
    }
}
