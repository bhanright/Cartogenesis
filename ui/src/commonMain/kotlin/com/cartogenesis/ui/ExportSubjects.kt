package com.cartogenesis.ui

import com.cartogenesis.cartography.ExportedWorld
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import com.cartogenesis.worldgen.pipeline.OceanAccelerator

/** The world an export draws, and whether it is the one the reader was looking at. */
class ExportSubject(val world: WorldMap, val source: ExportedWorld)

/**
 * What an export at a given size draws, which both front ends ask the same way.
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
     * The export row's line about where its pictures come from, for the world on screen made with
     * [onScreen]: which of [sizes], if any, is that world, and what every other size is — and that
     * a size is named by its rows, which a picture draws twice as wide, one pixel a cell.
     */
    fun note(onScreen: WorldGenConfig, sizes: List<Int>): String {
        val own = sizes.firstOrNull { isOnScreen(onScreen, it) }
        return if (own != null) {
            "At $own, the world's own size, the export is the world on screen. At any other " +
                "size it is made again from this world's settings and will differ in detail. " +
                PICTURE_SHAPE
        } else {
            "Each size is made again from this world's settings at that size, so it will differ in " +
                "detail from the ${Knobs.sizeOf(onScreen)} world on screen. " + PICTURE_SHAPE
        }
    }

    /**
     * What a size means for the file: the rows of the grid, which is twice as many cells across,
     * each one pixel of a picture and one sample of a data export.
     */
    private const val PICTURE_SHAPE =
        "A size N is a grid of N rows and 2N columns: the picture is 2N by N pixels, the world's " +
            "true shape, and a data export 2N by N samples, one a cell."
}
