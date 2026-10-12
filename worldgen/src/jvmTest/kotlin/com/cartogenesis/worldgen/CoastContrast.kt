package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.PI
import kotlin.math.cos

/**
 * The temperature contrast across a coast, measured the way Earth's is read: the departure of a
 * month's sea-level temperature from its latitude circle's mean, its extreme over the land behind
 * a coast against its extreme over the sea in front of it, along the same row.
 *
 * Earth's figures are read off reanalysis maps of exactly that departure. Nakamura and Miyasaka
 * (2004, AMS preprint J1.1, Fig. 2d) map July's zonally asymmetric 1000 hPa temperature from the
 * NCEP/NCAR reanalysis and state the contrast across the west coasts of the subtropical continents
 * as 18 to 20 C; Seager and others (2002, Q. J. R. Meteorol. Soc. 128, Fig. 1) map January's
 * departure of the surface air temperature from its zonal mean from the same reanalysis. A 1000 hPa
 * temperature over a high desert is the column carried down to the sea's pressure, so the map's
 * temperature is brought to sea level at the lapse rate it was lifted by.
 *
 * The extremes on those maps sit a thousand to two thousand kilometers from the coast on either
 * side (Fig. 2d: the cold core off California near 125 to 130 W, the heat over the Great Basin and
 * the Sahara's west), so each side is searched [REACH_KM] deep, and a coast counts only where the
 * land runs [MIN_RUN_KM] behind it and the sea as far in front, a continent's coast against an
 * ocean as Earth's figures are, not an island's or a strait's.
 */
internal object CoastContrast {

    /** How deep each side of a coast is searched for its extreme, km along the row. */
    const val REACH_KM = 2_000.0

    /** How far the land must run behind a coast, and the sea before it, for the coast to count, km. */
    const val MIN_RUN_KM = 1_000.0

    /** One coast set's reading: the mean contrast, land's extreme less the sea's, and its count. */
    class Reading(
        val contrastC: Double,
        val coasts: Int,
        /** The energy balance's land column less its marine column in the same window, the same coasts. */
        val columnGapC: Double,
        /** The mean marine-air share at the land's extreme, which is how much of the gap the blend withholds. */
        val landMarineShare: Double,
        /** The mean current anomaly of the sea at its extreme, degrees Celsius. */
        val seaAnomalyC: Double
    ) {
        override fun toString(): String =
            "%+.1f C over %d coasts (columns %+.1f, marine share at the land's extreme %.2f, sea anomaly %+.1f)"
                .format(contrastC, coasts, columnGapC, landMarineShare, seaAnomalyC)
    }

    /**
     * The mean contrast over [world]'s coasts between latitudes [fromDegrees] and [toDegrees]
     * (signed, either order), on [monthC], one month's temperature at every cell in degrees
     * Celsius. [westCoast] is a coast with the sea to its west; otherwise the sea is to its east.
     * [landWarm] takes the land's warmest departure against the sea's coldest, as in a summer;
     * otherwise the land's coldest against the sea's warmest, as in a winter, and the contrast is
     * negative where the land is the colder. [columnGapC] is the energy balance's own land less
     * marine air at a latitude, for the decomposition.
     */
    fun measure(
        world: WorldMap,
        monthC: FloatArray,
        fromDegrees: Float,
        toDegrees: Float,
        westCoast: Boolean,
        landWarm: Boolean,
        columnGapC: (Float) -> Float,
        marineShare: FloatArray
    ): Reading {
        val cellsAcross = world.width
        val cellsDown = world.height
        val lapseCPerM = world.config.climate.lapseRateCPerKm.toDouble() / WorldScale.METRES_PER_KM
        val south = minOf(fromDegrees, toDegrees)
        val north = maxOf(fromDegrees, toDegrees)
        var contrastSum = 0.0
        var columnSum = 0.0
        var shareSum = 0.0
        var anomalySum = 0.0
        var coasts = 0
        val departure = DoubleArray(cellsAcross)
        for (row in 0 until cellsDown) {
            val latitude = ClimateStage.latitudeOf(row, cellsDown)
            if (latitude < south || latitude > north) continue
            var rowSum = 0.0
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                departure[column] = monthC[cell].toDouble() + if (world.sea.isLand[cell]) {
                    world.config.scale.metresAboveShoreline(world.sea.relativeElevation.data[cell])
                        .coerceAtLeast(0f).toDouble() * lapseCPerM
                } else 0.0
                rowSum += departure[column]
            }
            val rowMean = rowSum / cellsAcross
            for (column in 0 until cellsAcross) departure[column] -= rowMean

            val cellKm = world.config.scale.cellWidthKm(cellsAcross) * cos(latitude * PI / 180.0)
            val reachCells = (REACH_KM / cellKm).toInt().coerceIn(1, cellsAcross / 2)
            val minRunCells = (MIN_RUN_KM / cellKm).toInt().coerceIn(1, cellsAcross / 2)
            // A step toward the sea, round the cylinder.
            val seaward = if (westCoast) -1 else 1
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!world.sea.isLand[cell]) continue
                val seaColumn = Math.floorMod(column + seaward, cellsAcross)
                if (world.sea.isLand[row * cellsAcross + seaColumn]) continue

                var landExtreme = departure[column]
                var landExtremeCell = cell
                var landRun = 0
                while (landRun < reachCells) {
                    val next = Math.floorMod(column - seaward * landRun, cellsAcross)
                    if (!world.sea.isLand[row * cellsAcross + next]) break
                    if (if (landWarm) departure[next] > landExtreme else departure[next] < landExtreme) {
                        landExtreme = departure[next]
                        landExtremeCell = row * cellsAcross + next
                    }
                    landRun++
                }
                var seaExtreme = departure[seaColumn]
                var seaExtremeCell = row * cellsAcross + seaColumn
                var seaRun = 0
                while (seaRun < reachCells) {
                    val next = Math.floorMod(column + seaward * (seaRun + 1), cellsAcross)
                    if (world.sea.isLand[row * cellsAcross + next]) break
                    if (if (landWarm) departure[next] < seaExtreme else departure[next] > seaExtreme) {
                        seaExtreme = departure[next]
                        seaExtremeCell = row * cellsAcross + next
                    }
                    seaRun++
                }
                if (landRun < minRunCells || seaRun < minRunCells) continue
                contrastSum += landExtreme - seaExtreme
                columnSum += columnGapC(latitude)
                shareSum += marineShare[landExtremeCell]
                anomalySum += if (world.config.ocean.enabled) world.ocean.anomaly.data[seaExtremeCell].toDouble() else 0.0
                coasts++
            }
        }
        if (coasts == 0) return Reading(Double.NaN, 0, Double.NaN, Double.NaN, Double.NaN)
        return Reading(
            contrastSum / coasts, coasts, columnSum / coasts, shareSum / coasts, anomalySum / coasts
        )
    }
}
