package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.OceanCirculation
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import kotlin.math.E
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The circulation's laws scale with the planet as the physics says they must, measured on one
 * basin at the default radius and at twice it.
 *
 * With the rotation held and the radius doubled, β = 2Ω cos φ / a halves, and so does the curl of a
 * stress that is the same at every latitude but spread over twice the distance. So:
 *  - the Stommel layer `δ_S = r/β` doubles in kilometers;
 *  - the Sverdrup transport per unit width, `curl / (ρ β)`, and with it the interior's velocity,
 *    is unchanged.
 *
 * A figure written for one radius (a β, a length of a degree, a solve grid counted in kilometers)
 * would break one of the two. The heat is left out: the eddies and the relaxation do not scale with
 * the radius, and should not.
 */
class OceanPlanetSizeTest {

    private class Measured(val interiorNorthwardMps: Double, val layerWidthKm: Double)

    private fun measure(worldWidthKm: Double): Measured {
        val base = WorldGenConfig(seed = 1L, width = 256, height = 256)
        val config = base.copy(
            scale = WorldScale(worldWidthKm = worldWidthKm),
            climate = base.climate.copy(pressureWinds = false)
        )
        val across = config.width
        val down = config.height
        // One basin a quarter of the world wide, from 70 degrees south to 70 north.
        val isLand = BooleanArray(across * down) { cell ->
            val column = cell % across
            val latitude = ClimateStage.latitudeOf(cell / across, down)
            column !in across / 8 until across * 3 / 8 || abs(latitude) > 70f
        }
        val sea = SeaLevelResult(0.5f, isLand, FloatField(across, down), isLand.count { it })
        val zonal = ClimateStage.zonalClimate(config, sea)
        val solved = OceanStage.circulate(config, sea, zonal) { stencil, values, passes ->
            OceanCirculation.relax(stencil, values, passes); values
        }
        val gridAcross = solved.cellsAcross
        val row = (0 until solved.cellsDown).minByOrNull { abs(ClimateStage.latitudeOf(it, solved.cellsDown) - 30f) }!!
        val firstWater = (0 until gridAcross).first { solved.isWater[row * gridAcross + it] }
        val lastWater = (firstWater until gridAcross).last { solved.isWater[row * gridAcross + it] }
        val speeds = DoubleArray(lastWater - firstWater + 1) { solved.northwardMps[row * gridAcross + firstWater + it].toDouble() }
        val peak = (speeds.indices).maxByOrNull { speeds[it] }!!
        var index = peak
        while (index + 1 < speeds.size && speeds[index + 1] > speeds[peak] / E) index++
        val share = (speeds[index] - speeds[peak] / E) / (speeds[index] - speeds[index + 1])
        val layerWidthKm = (index - peak + share) * solved.cellWidthMeters / 1000.0
        val interior = speeds[speeds.size / 2]
        println("OCEAN planet of ${worldWidthKm.toInt()} km round: interior v at 30N $interior m/s, western layer e-folding ${"%.2f".format(layerWidthKm)} km")
        return Measured(interior, layerWidthKm)
    }

    @Test
    fun `the Stommel layer doubles and the Sverdrup interior holds when the radius doubles`() {
        val radius = measure(12_000.0)
        val twice = measure(24_000.0)
        val layerRatio = twice.layerWidthKm / radius.layerWidthKm
        val interiorRatio = twice.interiorNorthwardMps / radius.interiorNorthwardMps
        println("OCEAN at twice the radius: layer x${"%.4f".format(layerRatio)}, interior x${"%.4f".format(interiorRatio)}")
        assertTrue(radius.interiorNorthwardMps < 0.0, "the subtropical interior does not flow equatorward")
        assertTrue(abs(layerRatio - 2.0) < 2.0 * ONE_PERCENT, "the Stommel layer went x$layerRatio with the radius, where r/β doubles")
        assertTrue(abs(interiorRatio - 1.0) < ONE_PERCENT, "the Sverdrup interior went x$interiorRatio with the radius, where curl/(ρβ) holds")
    }

    private companion object {
        const val ONE_PERCENT = 0.01
    }
}
