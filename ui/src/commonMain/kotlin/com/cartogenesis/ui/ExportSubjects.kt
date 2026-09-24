package com.cartogenesis.ui

import com.cartogenesis.cartography.ExportedWorld
import com.cartogenesis.worldgen.WorldGenerationEngine
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
 * that size from the on-screen world's settings, re-targeted to the larger grid as
 * `WorldGenConfig.atResolution` does, and with the same acceleration setting and the same devices
 * the generation that made it had. That world is like the one on screen and not the same, and
 * [ExportSubject.source] says so for the notice and the data sidecar to repeat.
 */
object ExportSubjects {

    suspend fun at(
        onScreen: WorldMap,
        size: Int,
        accelerator: ErosionAccelerator?,
        oceanAccelerator: OceanAccelerator?,
        iceAccelerator: IceSheetAccelerator?
    ): ExportSubject {
        if (onScreen.width == size && onScreen.height == size) {
            return ExportSubject(onScreen, ExportedWorld.OnScreen)
        }
        val madeAgain = WorldGenerationEngine.generate(
            onScreen.config.atResolution(size, size),
            accelerator = accelerator,
            oceanAccelerator = oceanAccelerator,
            iceAccelerator = iceAccelerator
        )
        return ExportSubject(madeAgain, ExportedWorld.of(onScreen, madeAgain))
    }

    /**
     * The export row's line about where its pictures come from, for a world [worldSize] cells
     * across: which size, if any, is the world on screen, and what every other size is.
     */
    fun note(worldSize: Int, sizes: List<Int>): String =
        if (worldSize in sizes) {
            "At $worldSize, the world's own size, the export is the world on screen. At any other " +
                "size it is made again from this world's settings and will differ in detail."
        } else {
            "Each size is made again from this world's settings at that size, so it will differ in " +
                "detail from the $worldSize world on screen."
        }
}
