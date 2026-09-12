package com.cartogenesis.ui

import com.cartogenesis.worldgen.GenerationStage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.PartialWorld
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import kotlinx.coroutines.delay

/**
 * A generation the reader can see happening.
 *
 * The engine has reported its ten stages since long before there was an interface to show them in,
 * and on the desktop that was enough: generation runs on `Dispatchers.Default`, which is a pool of
 * real threads, so writing a stage name into the interface's state from one of them puts it on
 * screen a frame later. In a browser it was worth nothing at all. There is one thread there — the
 * page, the generator and every repaint share it — so the ten reports land one after another with
 * no frame between them, and the whole generation is a single unbroken block of arithmetic during
 * which nothing is painted. William hit that on his phone at 2048: ninety-odd seconds, which he was
 * happy with, and "it does hang for a bit when you first hit generate without any sign it's still
 * working", which he was not. It was not hanging. It had no way to say so.
 *
 * So the thread is handed back twice over: once before the first cell is computed, so that whatever
 * the press of Generate set — the busy state, the progress banner, the sheet's summary — is on
 * screen before the arithmetic starts, and once at every stage boundary, so that each of the ten
 * names is a thing that actually appears rather than a string written to a variable nobody will
 * read until the end. That is why [com.cartogenesis.worldgen.GenerationProgress] suspends.
 *
 * On the JVM this costs about a sixth of a second across a whole generation and buys nothing,
 * because the interface was never blocked; it is not conditional on the platform, because a
 * platform test here would be a second thing to keep true and this one is cheap enough not to be
 * worth it.
 */
internal object Generation {

    /**
     * How long to stand aside for.
     *
     * One frame at 60 Hz. What actually matters is that the wait is a *timer* rather than a yield:
     * a browser runs its microtasks — which is what a bare `yield` schedules — before it paints, so
     * yielding gives the page the thread back and still shows nothing. A timer is a macrotask, and
     * the frame goes out before it fires.
     */
    private const val ONE_FRAME_MILLIS: Long = 16L

    /**
     * Generates [config]'s world, naming each stage as it starts and letting the interface draw.
     *
     * [previous] is the world already on screen, so the engine can skip whatever the settings did
     * not change; [accelerator] is the graphics device, or the terrain a version-2 save carried.
     */
    suspend fun run(
        config: WorldGenConfig,
        previous: PartialWorld?,
        accelerator: ErosionAccelerator?,
        onStage: (GenerationStage) -> Unit
    ): WorldMap {
        letTheInterfaceDraw()
        return WorldGenerationEngine.generate(config, previous, accelerator) { stage, _, _ ->
            onStage(stage)
            letTheInterfaceDraw()
        }
    }

    private suspend fun letTheInterfaceDraw() = delay(ONE_FRAME_MILLIS)
}
