package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.abs

/**
 * Where the air rises deep enough to rain, each half-year, apart from where the surface wind meets.
 *
 * The circulation at each longitude ([LocalCirculation.forHalf]) rises at its sector's energy flux
 * equator, found from the air's temperature, and over a summer continent that is the heat low:
 * the surface wind converges there, at the intertropical front, and the boundary layer holds it.
 * Earth's heat low rises only in a shallow circulation, which returns below 600 to 700 hPa, and its
 * deep convection and rain stand equatorward of it, near the maximum of the subcloud moist static
 * energy (Nie, Boos and Kuang 2010, read: over North Africa in July the rain peaks near 9 N, the
 * subcloud equivalent potential temperature near 14 to 15 N and the potential temperature near
 * 22 N; Boos and Emanuel 2009, read, after Privé and Plumb 2007). So the deep circulation is the
 * same circulation with its rising branch at the sector's subcloud moist static energy
 * ([LocalCirculation.moistStaticEnergyBands]), the march's own humidity making the energy, and the
 * difference between the two is the shallow circulation:
 *
 * - **The surface wind and the pressure** stay the temperature's, so the convergence line inland
 *   stays at the heat low.
 * - **The ascent the march condenses** is the deep circulation's: the boundary layer's convergence
 *   less the shallow circulation's own ([ascentMps]), whose rising air returns in the lower
 *   troposphere without reaching the levels where it would rain out.
 * - **The column's water** returns with each: the deep circulation's above 500 hPa, the shallow
 *   circulation's between the boundary layer's top and the shallow heating's ([BoundaryLayer.columnWindMps]).
 *
 * The humidity is the last lap's, so the deep ascent is solved in the coupled loop with the rain:
 * the first lap runs with the deep ascent where the surface wind's is, and each lap after reads the
 * humidity the lap before it left. [largestShifts] records how far the rising branch moved between
 * laps, the loop's own measure of whether the placement has settled.
 */
internal class DeepAscent(
    private val config: WorldGenConfig,
    private val sea: SeaLevelResult,
    private val zonal: ZonalClimate,
    /** Height above the sea in meters at land cells, zero at sea, row-major. */
    elevationM: FloatArray,
    private val julyCirculation: HadleyCirculation,
    private val januaryCirculation: HadleyCirculation
) {
    private val cellsAcross = config.width
    private val cellsDown = config.height

    /** Each map row's energy-balance band. */
    private val bandOfRow = IntArray(cellsDown) { row ->
        val latitude = ClimateStage.latitudeOf(row, cellsDown)
        ((EnergyBalance.POLE_DEGREES - latitude) * EnergyBalance.BANDS / EnergyBalance.POLE_TO_POLE_DEGREES).toInt()
            .coerceIn(0, EnergyBalance.BANDS - 1)
    }

    /** Each band's land cells and their mean height, meters; zero where a band has no land. */
    private val landCellsOfBand = IntArray(EnergyBalance.BANDS)
    private val seaCellsOfBand = IntArray(EnergyBalance.BANDS)
    private val landElevationMByBand = FloatArray(EnergyBalance.BANDS).also { elevation ->
        val sum = DoubleArray(EnergyBalance.BANDS)
        for (cell in 0 until cellsAcross * cellsDown) {
            val band = bandOfRow[cell / cellsAcross]
            if (sea.isLand[cell]) {
                landCellsOfBand[band]++
                sum[band] += elevationM[cell].toDouble()
            } else {
                seaCellsOfBand[band]++
            }
        }
        for (band in elevation.indices) if (landCellsOfBand[band] > 0) elevation[band] = (sum[band] / landCellsOfBand[band]).toFloat()
    }

    /** The largest move of the deep rising branch at any column from one lap to the next, degrees, both halves. */
    val largestShifts = mutableListOf<Double>()

    private val lastAscentDegrees = arrayOfNulls<DoubleArray>(2)

    /** Each half's deep rising branch at every column on the last lap, degrees north, July's then January's, or none. */
    fun lastAscent(): Pair<DoubleArray, DoubleArray>? {
        val july = lastAscentDegrees[0] ?: return null
        val january = lastAscentDegrees[1] ?: return null
        return july to january
    }

    /**
     * The deep circulation's surface wind for the half about July when [julyHalf], meters a second,
     * row-major, eastward and southward, its rising branch at the moist static energy that
     * [columnHumidity] (the column's water over its saturated water at every cell, row-major) makes.
     */
    fun surfaceWindMps(julyHalf: Boolean, columnHumidity: FloatArray?): PressureWind.Vectors {
        val season = if (julyHalf) Season.JULY_HALF else Season.JANUARY_HALF
        val circulation = if (julyHalf) julyCirculation else januaryCirculation
        if (columnHumidity == null) {
            // Before any lap there is no humidity to read, and the deep ascent starts at the zonal
            // circulation's rising branch at every longitude: one profile for both columns moves no
            // sector from the zonal blend.
            val seaBands = zonal.sea.at(season)
            val start = LocalCirculation.onProfiles(config, sea.isLand, seaBands, seaBands, circulation)
            lastAscentDegrees[if (julyHalf) 0 else 1] = start.ascentLatitudeDegrees.copyOf()
            return start.surfaceWindMps(cellsAcross, cellsDown)
        }
        val landSum = DoubleArray(EnergyBalance.BANDS)
        val seaSum = DoubleArray(EnergyBalance.BANDS)
        for (cell in columnHumidity.indices) {
            val band = bandOfRow[cell / cellsAcross]
            // The column's humidity over its share at surface saturation is the surface air's own.
            val surfaceHumidity = (columnHumidity[cell] / MoistureMarch.HOLDABLE_SHARE).coerceIn(0.0, 1.0)
            if (sea.isLand[cell]) landSum[band] += surfaceHumidity else seaSum[band] += surfaceHumidity
        }
        // A band with no land takes its sea's humidity, and one with no sea its land's, so each
        // column's profile is whole across the span the domes are fitted over.
        val landHumidity = FloatArray(EnergyBalance.BANDS)
        val seaHumidity = FloatArray(EnergyBalance.BANDS)
        for (band in 0 until EnergyBalance.BANDS) {
            val land = if (landCellsOfBand[band] > 0) landSum[band] / landCellsOfBand[band] else Double.NaN
            val water = if (seaCellsOfBand[band] > 0) seaSum[band] / seaCellsOfBand[band] else Double.NaN
            landHumidity[band] = (if (land.isNaN()) water else land).let { if (it.isNaN()) 0.0 else it }.toFloat()
            seaHumidity[band] = (if (water.isNaN()) land else water).let { if (it.isNaN()) 0.0 else it }.toFloat()
        }
        val (landEnergy, seaEnergy) = LocalCirculation.moistStaticEnergyBands(
            zonal, season, landHumidity, seaHumidity, landElevationMByBand, config.climate.lapseRateCPerKm.toDouble()
        )
        val deep = LocalCirculation.onProfiles(
            config, sea.isLand, landEnergy, seaEnergy, circulation
        )
        val half = if (julyHalf) 0 else 1
        lastAscentDegrees[half]?.let { previous ->
            var largest = 0.0
            for (column in previous.indices) largest = maxOf(largest, abs(deep.ascentLatitudeDegrees[column] - previous[column]))
            largestShifts += largest
        }
        lastAscentDegrees[half] = deep.ascentLatitudeDegrees.copyOf()
        return deep.surfaceWindMps(cellsAcross, cellsDown)
    }

    /**
     * The ascent the march condenses under [half] of [atmosphere], meters a second, positive up,
     * row-major: the boundary layer's ([BoundaryLayer.groundAscentMps]) less the shallow
     * circulation's, the temperature's circulation's surface wind less [deepWind]'s, whose
     * convergence rises only into the lower troposphere. The boundary layer's alone with no
     * [deepWind], as on the first lap.
     */
    fun ascentMps(atmosphere: BoundaryLayer.Atmosphere, half: BoundaryLayer.Half, deepWind: PressureWind.Vectors?): FloatArray {
        val ascent = BoundaryLayer.groundAscentMps(atmosphere, half)
        if (deepWind == null) return ascent
        val cellCount = ascent.size
        val change = BoundaryLayer.circulationAscentChangeMps(
            atmosphere,
            FloatArray(cellCount) { deepWind.eastwardMps[it] - half.beltEastwardMps[it] },
            FloatArray(cellCount) { deepWind.southwardMps[it] - half.beltSouthwardMps[it] }
        )
        for (cell in 0 until cellCount) ascent[cell] += change[cell]
        return ascent
    }
}
