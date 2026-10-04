package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.IceSheet
import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The ice sheet leaves the processor only when the reader has turned graphics acceleration on.
 *
 * Every host hands the engine its ice device whatever the setting says, as the application and the
 * exports both do, so the switch has to be read where the device would be called.
 * A card rounds its square roots its own way and the sheet's surface is the elevation every later
 * stage reads, so a sheet drawn on one while the switch is off is a different world from the same
 * seed on a machine without one. The device here is a counting fake that answers with a thickness
 * no profile would give, so both halves are visible: whether it was asked, and whether its answer
 * reached the world.
 */
class IceAcceleratorSwitchTest {

    @Test
    fun `the ice sheet is drawn on the device only when acceleration is on`() = runTest {
        val device = CountingIceDevice()

        val onTheProcessor = WorldGenerationEngine.generate(config, iceAccelerator = device)
        assertEquals(
            0, device.calls,
            "the glaciation stage asked the ice device ${device.calls} time(s) with acceleration " +
                "off, so this seed makes a different world on a machine with a card"
        )

        val capture = LayerCapture()
        val onTheDevice = WorldGenerationEngine.generate(
            acceleratedConfig, iceAccelerator = device, capture = capture
        )
        assertTrue(device.calls >= 1, "with acceleration on the ice device was never asked")
        assertTrue(
            device.sheetCellsSeen > 0,
            "seed ${config.seed} at ${config.width} grew no sheet for the device to draw"
        )

        // The device's answer is the ice the stage went on with, cell for cell, and it reached
        // the map: the ground under the sheet stands where the sentinel put it, not where the
        // processor's profile would have.
        val ice = assertNotNull(capture.ice, "the accelerated world carries no ice")
        var sheetCells = 0
        var movedCells = 0
        for (cell in ice.iceThicknessMetres.indices) {
            if (!device.lastSheet[cell]) continue
            sheetCells++
            assertEquals(
                SENTINEL_THICKNESS_METRES, ice.iceThicknessMetres[cell],
                "cell $cell's thickness is not the device's answer"
            )
            val processorGround = onTheProcessor.sea.relativeElevation.data[cell]
            if (onTheDevice.sea.relativeElevation.data[cell] != processorGround) movedCells++
        }
        assertTrue(sheetCells > 0, "the device was handed a sheet with no cells on it")
        assertTrue(movedCells > 0, "the device's thickness never reached the world's elevation")
    }

    /**
     * Rule 12's half of the switch: the setting lives in the erosion section, which the engine
     * reuses on, so turning it on or off must re-run the ice rather than hand back a sheet drawn
     * under the other answer. Shown on the world's own ground, not by reading the guards.
     */
    @Test
    fun `turning acceleration on or off re-draws the ice rather than reusing it`() = runTest {
        val device = CountingIceDevice()
        val onTheProcessor = WorldGenerationEngine.generate(config, iceAccelerator = device)
        assertEquals(0, device.calls, "the processor's world asked the device")

        val turnedOn = WorldGenerationEngine.generate(
            acceleratedConfig, previous = onTheProcessor, iceAccelerator = device
        )
        assertTrue(
            device.calls >= 1,
            "turning acceleration on reused the processor's ice instead of asking the device"
        )

        val callsWhileOn = device.calls
        val turnedOff = WorldGenerationEngine.generate(
            config, previous = turnedOn, iceAccelerator = device
        )
        assertEquals(callsWhileOn, device.calls, "turning acceleration off still asked the device")
        assertContentEquals(
            onTheProcessor.sea.relativeElevation.data, turnedOff.sea.relativeElevation.data,
            "turning acceleration off kept the device's ice instead of the processor's"
        )
    }

    /**
     * A device that counts its calls and answers with [SENTINEL_THICKNESS_METRES] over the whole
     * sheet, and with the flow a truthful device would give for that thickness, so everything
     * downstream of it runs on a sheet it can handle.
     */
    private class CountingIceDevice : IceSheetAccelerator {
        override val name = "counting test device"
        var calls = 0
        var sheetCellsSeen = 0
        var lastSheet = BooleanArray(0)

        override suspend fun sheet(
            cellsAcross: Int,
            cellsDown: Int,
            marginDistanceKm: FloatArray,
            nearestMarginCell: IntArray,
            bedRelative: FloatArray,
            onTheSheet: BooleanArray,
            metresPerRootKilometre: Float,
            metresPerFieldUnit: Float,
            cellHeightInCellWidths: Float,
            cellSpanKm: Float
        ): IceSheetAccelerator.Sheet {
            calls++
            lastSheet = onTheSheet.copyOf()
            sheetCellsSeen += onTheSheet.count { it }
            val thickness = FloatArray(onTheSheet.size) {
                if (onTheSheet[it]) SENTINEL_THICKNESS_METRES else 0f
            }
            val flow = IceSheet.flowReceivers(
                cellsAcross, cellsDown, bedRelative, thickness, onTheSheet, metresPerFieldUnit,
                cellHeightInCellWidths
            )
            return IceSheetAccelerator.Sheet(thickness, flow)
        }
    }

    private companion object {
        /**
         * A thickness no plastic profile gives exactly, so finding it in the world means the
         * device's answer was used. A kilometre and a bit is an ordinary sheet's order, so nothing
         * downstream is asked to carry ice it would not meet.
         */
        const val SENTINEL_THICKNESS_METRES = 1_111f

        /**
         * The smallest of the four standard seeds (7, 42, 99 and 1234) whose world grows an ice
         * sheet at 128, which is the rule the seed was picked by: 128 is the smallest grid the
         * common suite generates, and a world with no sheet never reaches the device at all.
         */
        const val SEED = 7L

        val config = WorldGenConfig.forRows(SEED, 128)

        val acceleratedConfig =
            config.copy(erosion = config.erosion.copy(acceleration = Acceleration.GPU))
    }
}
