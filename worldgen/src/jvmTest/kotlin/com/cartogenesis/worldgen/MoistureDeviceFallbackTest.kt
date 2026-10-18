package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureAccelerator
import com.cartogenesis.worldgen.pipeline.MoistureDevice
import com.cartogenesis.worldgen.pipeline.MoistureDeviceRun
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the moisture march's device does not take a run, or drops it, the climate is the
 * processor's to the bit.
 *
 * Three ways a run stays on the processor, each without a graphics card: the config's acceleration
 * is the processor's, so no device is asked; a device declines the run ([MoistureAccelerator.open]
 * returns null), as one without the features the march needs does; and a device that took the run
 * fails partway through it, when the water in flight it held is gone, so the climate stage marches
 * the whole run again on the processor with an atmosphere of its own. The control is a device that
 * marches nothing and says it succeeded: its climate is not the processor's, so the bit-equality
 * the three clauses hold is not met by a climate that never asked the device at all.
 */
class MoistureDeviceFallbackTest {

    private companion object {
        /** A small grid: the clauses are about the climate stage's control flow, not its physics. */
        const val ROWS = 64
        const val SEED = 42L

        /** The lap a failing device fails in: after two laps it has marched, as a lost device would. */
        const val FAILING_LAP = 2

        val world: WorldMap by lazy { WorldGenerationEngine.generateBlocking(WorldGenConfig.forRows(SEED, ROWS)) }
        val processorRain: FloatArray by lazy { ClimateStage.generate(world.config, world.sea, world.ocean).precipitationMm.data }
    }

    private val onTheCard = world.config.copy(erosion = world.config.erosion.copy(acceleration = Acceleration.GPU))

    /** A device that takes runs, marches nothing, and fails in [failingLap] if one is given. */
    private class IdleDevice(private val failingLap: Int?) : MoistureAccelerator {
        override val name = "an idle device"
        var opened = 0
        var lapsStarted = 0
        override fun open(run: MoistureDeviceRun): MoistureDevice {
            opened++
            return object : MoistureDevice {
                override fun startLap(fixedFieldsChanged: Boolean): Boolean {
                    lapsStarted++
                    return failingLap == null || lapsStarted <= failingLap
                }
                override fun finishLap() = true
                override fun readState() = true
                override fun close() {}
            }
        }
    }

    @Test
    fun `with the processor's acceleration no device is asked, and the rain is the processor's`() {
        val device = IdleDevice(null)
        val rain = ClimateStage.generate(world.config, world.sea, world.ocean, device).precipitationMm.data
        assertEquals(0, device.opened, "the device was asked for a run the config keeps on the processor")
        assertContentEquals(processorRain, rain, "the rain moved with a device the config does not ask for")
    }

    @Test
    fun `a device that declines the run leaves the processor's rain to the bit`() {
        var asked = 0
        val declining = object : MoistureAccelerator {
            override val name = "a declining device"
            override fun open(run: MoistureDeviceRun): MoistureDevice? {
                asked++
                return null
            }
        }
        val rain = ClimateStage.generate(onTheCard, world.sea, world.ocean, declining).precipitationMm.data
        assertEquals(1, asked, "the climate asked the device $asked times for one run")
        assertContentEquals(processorRain, rain, "the rain moved after the device declined")
    }

    @Test
    fun `a device that fails partway through the run leaves the processor's rain to the bit`() {
        val failing = IdleDevice(FAILING_LAP)
        val rain = ClimateStage.generate(onTheCard, world.sea, world.ocean, failing).precipitationMm.data
        assertEquals(1, failing.opened, "the device was opened ${failing.opened} times")
        assertEquals(FAILING_LAP + 1, failing.lapsStarted, "the device was asked for ${failing.lapsStarted} laps, failing in lap $FAILING_LAP")
        assertContentEquals(processorRain, rain, "the rain after the device failed is not the processor's own run")
    }

    @Test
    fun `control - a device that marches nothing and succeeds is not the processor's rain`() {
        val idle = IdleDevice(null)
        val rain = ClimateStage.generate(onTheCard, world.sea, world.ocean, idle).precipitationMm.data
        assertTrue(idle.lapsStarted > 0, "the idle device marched no laps")
        assertFalse(rain.contentEquals(processorRain), "a device that marched nothing gave the processor's rain")
    }
}
