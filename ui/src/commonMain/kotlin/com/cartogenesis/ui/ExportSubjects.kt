package com.cartogenesis.ui

import com.cartogenesis.cartography.ExportedWorld
import com.cartogenesis.cartography.SheetGeometry
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import com.cartogenesis.worldgen.pipeline.OceanAccelerator

/** The world an export draws, and whether it is the one the reader was looking at. */
class ExportSubject(val world: WorldMap, val source: ExportedWorld)

/**
 * What an export at a given size draws.
 *
 * The interface asks only for the world's own size (docs/DESIGN_LEDGER.md, G1); the other sizes
 * are kept for the exports the tests and the audit tier make at other grids.
 *
 * At the world's own size it is the world on screen, exactly: an opened save, a world made on a
 * graphics card, a world an older build made — whatever is on screen is what is written, and
 * nothing is regenerated. At any other size there is no such world to draw, so one is made at
 * that size from the on-screen world's settings, re-targeted to the larger grid as the panel's
 * chips re-target a world ([Knobs.atResolution]), and with the same acceleration setting and the
 * same devices the generation that made it had. That world is like the one on screen and not the
 * same, and [ExportSubject.source] says so for the notice and the data sidecar to repeat.
 */
object ExportSubjects {

    /** The settings of the world an export named [size] makes again from [onScreen]'s. */
    fun configAt(onScreen: WorldGenConfig, size: Int): WorldGenConfig = Knobs.atResolution(onScreen, size)

    /**
     * Whether an export named [size] is [onScreen] itself: its grid is the one the size names, so
     * drawing it needs nothing made again.
     */
    fun isOnScreen(onScreen: WorldGenConfig, size: Int): Boolean = Knobs.makesAtSize(onScreen, size)

    suspend fun at(
        onScreen: WorldMap,
        size: Int,
        accelerator: ErosionAccelerator?,
        oceanAccelerator: OceanAccelerator?,
        iceAccelerator: IceSheetAccelerator?
    ): ExportSubject {
        if (isOnScreen(onScreen.config, size)) {
            return ExportSubject(onScreen, ExportedWorld.OnScreen)
        }
        val madeAgain = WorldGenerationEngine.generate(
            configAt(onScreen.config, size),
            accelerator = accelerator,
            oceanAccelerator = oceanAccelerator,
            iceAccelerator = iceAccelerator
        )
        return ExportSubject(madeAgain, ExportedWorld.of(onScreen, madeAgain))
    }

    /**
     * The export row's line about what its button writes for the world on screen made with
     * [onScreen]: that world itself, one pixel or one sample a cell, at the picture's size.
     */
    fun note(onScreen: WorldGenConfig): String {
        val sheet = SheetGeometry.of(onScreen)
        return "The export is the world on screen, one pixel a cell: a picture of " +
            "${sheet.widthPixels} by ${sheet.heightPixels} pixels, the world's true shape, or a " +
            "data export of ${onScreen.width} by ${onScreen.height} samples."
    }
}
