package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.ColumnWater
import com.cartogenesis.worldgen.pipeline.MoistureMarch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The boundary layer's thermodynamics as the moisture march reads it ([ColumnWater],
 * [MoistureMarch]): the lifted layer condenses nothing under its lifting condensation level and,
 * above it, what a fine climb up the moist adiabat condenses; the reference column splits into the
 * boundary layer's part and the free troposphere's; the exchange velocity is the free troposphere's
 * radiative subsidence; and the rain law's humidity is the column's where the boundary layer stands
 * at the marine humidity the law was read on.
 */
class BoundaryLayerWaterTest {

    private companion object {
        /** The fine climb's step up the moist adiabat, meters. */
        const val FINE_STEP_M = 1.0

        /** How far the yield may stand from the fine climb's: the linearization over the saturated part, a few percent. */
        const val YIELD_SHARE = 0.05

        const val DRY_AIR_GAS_CONSTANT = 287.04
        const val GRAVITY = 9.80665
        const val DRY_HEAT_CAPACITY = 1004.0
    }

    /**
     * What the fine climb condenses per meter of lift, kg m⁻² m⁻¹: the surface air at [temperatureC]
     * and [pressureKpa] with [humidity] (kg/kg) carried up the dry adiabat in [FINE_STEP_M] steps to
     * where its water saturates it, and the saturated air above, to the layer's top, each meter of it
     * condensing `rho (-dq_s/dz)` along the moist adiabat.
     */
    private fun fineYield(temperatureC: Double, pressureKpa: Double, depthM: Double, humidity: Double): Double {
        var kelvin = temperatureC + ColumnWater.KELVIN_AT_ZERO_C
        var pressure = pressureKpa
        var height = 0.0
        var saturated = false
        var condensed = 0.0
        while (height < depthM) {
            val celsius = kelvin - ColumnWater.KELVIN_AT_ZERO_C
            val saturation = ColumnWater.saturationSpecificHumidity(celsius, pressure)
            if (!saturated && saturation <= humidity) saturated = true
            val lapse = if (saturated) ColumnWater.moistAdiabaticLapseKPerM(celsius, pressure) else GRAVITY / DRY_HEAT_CAPACITY
            val nextKelvin = kelvin - lapse * FINE_STEP_M
            val nextPressure = pressure * exp(-GRAVITY * FINE_STEP_M / (DRY_AIR_GAS_CONSTANT * kelvin))
            if (saturated) {
                val nextSaturation = ColumnWater.saturationSpecificHumidity(nextKelvin - ColumnWater.KELVIN_AT_ZERO_C, nextPressure)
                val density = pressure * 1000.0 / (DRY_AIR_GAS_CONSTANT * kelvin)
                condensed += density * (saturation - nextSaturation)
            }
            kelvin = nextKelvin
            pressure = nextPressure
            height += FINE_STEP_M
        }
        return condensed
    }

    @Test
    fun `the lifted layer condenses what the fine climb does above its condensation level, and nothing under it`() {
        val yield = DoubleArray(2)
        var worst = 0.0
        for ((temperatureC, pressureKpa) in listOf(25.0 to 101.3, 10.0 to 85.0, -5.0 to 70.0)) {
            val depth = ColumnWater.boundaryLayerDepthM(temperatureC, pressureKpa)
            val saturation = ColumnWater.saturationSpecificHumidity(temperatureC, pressureKpa)
            for (relativeHumidity in listOf(0.3, 0.6, 0.8, 0.9, 0.97, 1.0)) {
                val humidity = relativeHumidity * saturation
                ColumnWater.boundaryLayerLiftYield(temperatureC, pressureKpa, depth, humidity, yield)
                val fine = fineYield(temperatureC, pressureKpa, depth, humidity)
                println("LIFT %.0f C, %.0f kPa, RH %.2f: %.3e against the fine climb's %.3e kg m-2 per meter".format(temperatureC, pressureKpa, relativeHumidity, yield[0], fine))
                if (fine == 0.0) {
                    assertEquals(0.0, yield[0], 0.0, "air under its condensation level condensed")
                } else if (fine > 0.05 * fineYield(temperatureC, pressureKpa, depth, saturation)) {
                    worst = maxOf(worst, abs(yield[0] - fine) / fine)
                }
            }
        }
        println("LIFT worst %.3f of the fine climb's yield".format(worst))
        assertTrue(worst <= YIELD_SHARE, "the yield stands %.3f from the fine climb's".format(worst))
    }

    @Test
    fun `the yield's slope is its own derivative in the layer's water`() {
        val yield = DoubleArray(2)
        val above = DoubleArray(2)
        val random = Random(20261017)
        repeat(200) {
            val temperatureC = random.nextDouble(-20.0, 30.0)
            val pressureKpa = random.nextDouble(60.0, 102.0)
            val depth = ColumnWater.boundaryLayerDepthM(temperatureC, pressureKpa)
            val humidity = random.nextDouble(0.75, 0.99) * ColumnWater.saturationSpecificHumidity(temperatureC, pressureKpa)
            ColumnWater.boundaryLayerLiftYield(temperatureC, pressureKpa, depth, humidity, yield)
            if (yield[0] <= 0.0) return@repeat
            val stepMm = 1.0e-4
            ColumnWater.boundaryLayerLiftYield(temperatureC, pressureKpa, depth, humidity + stepMm / ColumnWater.BOUNDARY_LAYER_MASS_KG_PER_M2, above)
            val numerical = (above[0] - yield[0]) / stepMm
            assertEquals(numerical, yield[1], 0.15 * abs(numerical) + 1e-12, "the slope at %.1f C, %.1f kPa".format(temperatureC, pressureKpa))
        }
    }

    @Test
    fun `the reference column splits into the boundary layer's part and the free troposphere's`() {
        for (temperatureC in listOf(-30.0, 0.0, 15.0, 27.0)) {
            val whole = ColumnWater.saturatedColumnMm(temperatureC)
            val boundary = ColumnWater.referenceBoundaryLayerSaturatedMm(temperatureC)
            val free = ColumnWater.referenceFreeSaturatedMm(temperatureC)
            println("REFERENCE %.0f C: %.2f mm, the boundary layer's %.2f, the free troposphere's %.2f".format(temperatureC, whole, boundary, free))
            assertEquals(whole, boundary + free, 1e-9)
            assertTrue(boundary > 0.0 && free > 0.0, "a part of the column is empty")
        }
    }

    @Test
    fun `the exchange is the free troposphere's radiative subsidence`() {
        val velocity = MoistureMarch.exchangeVelocityMps(6.5)
        val expected = MoistureMarch.FREE_TROPOSPHERE_COOLING_K_PER_DAY / 86_400.0 / (GRAVITY / DRY_HEAT_CAPACITY - 6.5e-3)
        println("EXCHANGE at 6.5 K/km: %.2f mm a second".format(velocity * 1000))
        assertEquals(expected, velocity, 1e-12)
        assertEquals(0.0, MoistureMarch.exchangeVelocityMps(10.0), 0.0)
    }

    @Test
    fun `the law's humidity is the column's over a boundary layer at the marine humidity`() {
        for (temperatureC in listOf(5.0, 20.0, 28.0)) {
            val saturated = ColumnWater.saturatedColumnMm(temperatureC)
            val column = 0.7 * saturated
            val freeWater = column - MoistureMarch.lawOffsetMm(temperatureC)
            assertEquals(0.7, MoistureMarch.lawHumidity(freeWater, temperatureC), 1e-12)
        }
    }
}
