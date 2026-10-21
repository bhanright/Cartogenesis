package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test

/**
 * The finished world's sea surface and the planet's mean temperature against Earth's, on the
 * standard seeds as the guards build them. `EnergyBalanceTest` holds the energy balance on Earth's
 * own land; this holds what a world draws, the currents' anomaly and the weather included.
 *
 * Earth's figures, each from a source read:
 * - **The tropical sea**: NOAA's optimum-interpolation climatology for 1971-2000 (OI SST version
 *   2, Reynolds and others 2002), read cell by cell from NOAA's Physical Sciences Laboratory's file
 *   with its land mask: 27.64 C over the ocean from 10 S to 10 N by area, 27.96 over its northern
 *   half and 27.33 over its southern. A world is held within half the difference between those two
 *   halves, the spread Earth's own hemispheres stand at about the band's mean (as
 *   `EnergyBalanceTest` argues its bands). The World Ocean Atlas 2023's 1991-2020 surface reads 27.82.
 * - **The planet's mean**: Jones, New, Parker, Martin and Rigor (1999, Rev. Geophys. 37, abstract
 *   read), 14.0 C over 1961-1990, the northern hemisphere 14.6 and the southern 13.4. A world's
 *   annual temperature at its own ground, by area, is held between the two hemispheres'.
 *
 * The sea surface is the stored annual air over each sea cell, which carries the currents'
 * anomaly and the weather, and the energy balance's water above its air at that latitude, as
 * `ClimateStage.seaSurfaceTemperature` builds every season's.
 */
class SeaSurfaceEarthTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS
        const val ROWS = SharedWorlds.DETAIL_ROWS

        const val TROPICAL_DEGREES = 10f
        const val OISST_TROPICS_NORTH_C = 27.96
        const val OISST_TROPICS_SOUTH_C = 27.33
        const val OISST_TROPICS_C = 27.64
        const val TROPICS_ENVELOPE_C = (OISST_TROPICS_NORTH_C - OISST_TROPICS_SOUTH_C) / 2

        const val JONES_NORTH_C = 14.6
        const val JONES_SOUTH_C = 13.4
        const val JONES_C = 14.0

        const val TROPICAL_SEA_COLD = "W2c: the tropical sea stands under Earth's"
    }

    /** The open sea's annual surface from 10 S to 10 N, degrees Celsius, by area. */
    private fun tropicalSeaC(world: WorldMap): Double {
        val zonal = ClimateStage.zonalClimate(world.config, world.sea)
        var weighted = 0.0
        var area = 0.0
        for (row in 0 until world.height) {
            val latitude = ClimateStage.latitudeOf(row, world.height)
            if (abs(latitude) > TROPICAL_DEGREES) continue
            val waterAboveAirC = zonal.waterC(latitude, Season.ANNUAL) - zonal.seaC(latitude, Season.ANNUAL)
            val weight = cos(latitude * PI / 180.0)
            for (column in 0 until world.width) {
                val cell = row * world.width + column
                if (world.sea.isLand[cell]) continue
                weighted += weight * (world.climate.temperature.data[cell] + waterAboveAirC)
                area += weight
            }
        }
        return weighted / area
    }

    /** The annual temperature at the ground over the whole planet, degrees Celsius, by area. */
    private fun planetMeanC(world: WorldMap): Double {
        var weighted = 0.0
        var area = 0.0
        for (row in 0 until world.height) {
            val weight = cos(ClimateStage.latitudeOf(row, world.height) * PI / 180.0)
            for (column in 0 until world.width) {
                weighted += weight * world.climate.temperature.data[row * world.width + column]
                area += weight
            }
        }
        return weighted / area
    }

    @Test
    fun `the tropical sea stands on Earth's`() {
        val misses = ArrayList<String>()
        for (seed in seeds) {
            val seaC = tropicalSeaC(SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS)))
            println("SEA SURFACE seed $seed: 10 S to 10 N %.2f C (OISST %.2f, its halves %.2f and %.2f)"
                .format(seaC, OISST_TROPICS_C, OISST_TROPICS_NORTH_C, OISST_TROPICS_SOUTH_C))
            if (abs(seaC - OISST_TROPICS_C) > TROPICS_ENVELOPE_C) misses += "seed $seed %+.2f".format(seaC - OISST_TROPICS_C)
        }
        KnownFailures.expect(TROPICAL_SEA_COLD, "seed 7 -0.58; seed 42 -0.62; seed 1234 -0.60; seed 99 -0.57") {
            if (misses.isNotEmpty()) {
                throw RecordedViolation("the tropical sea stands outside Earth's halves: ${misses.joinToString("; ")}", misses.joinToString("; "))
            }
        }
    }

    @Test
    fun `the planet's mean temperature is Earth's`() {
        val misses = ArrayList<String>()
        for (seed in seeds) {
            val meanC = planetMeanC(SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS)))
            println("SEA SURFACE seed $seed: the planet's mean %.2f C at its ground (Jones and others %.1f, hemispheres %.1f and %.1f)"
                .format(meanC, JONES_C, JONES_NORTH_C, JONES_SOUTH_C))
            if (meanC !in JONES_SOUTH_C..JONES_NORTH_C) misses += "seed $seed %.2f C".format(meanC)
        }
        // Recorded again at TH1-1, on whose worlds two seeds' planets stand under Earth's southern
        // hemisphere (docs/DESIGN_LEDGER.md, TH1-1).
        KnownFailures.expect("TH1-1: on the history's worlds the planet's mean stands under Earth's", "seed 7 13.39 C") {
            if (misses.isNotEmpty()) {
                throw RecordedViolation("the planet's mean stands outside Earth's hemispheres: ${misses.joinToString("; ")}", misses.joinToString("; "))
            }
        }
    }
}
