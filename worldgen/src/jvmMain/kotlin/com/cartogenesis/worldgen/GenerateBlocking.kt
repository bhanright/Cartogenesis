package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import kotlinx.coroutines.runBlocking

/**
 * Generates a world and waits for it, for callers that are not in a coroutine.
 *
 * [WorldGenerationEngine.generate] suspends for one reason — a WebGPU accelerator has to await its
 * device and its buffers — and that reason does not exist on the JVM, where the accelerator is an
 * OpenGL context driven synchronously and every other stage is plain blocking work. Without this,
 * `suspend` would spread from a browser API into every test in the project, which is a poor trade
 * for a call that never actually suspends here.
 *
 * Only for callers already on a background thread, since it blocks the one it is on. Anything
 * inside a coroutine should call [WorldGenerationEngine.generate] directly.
 */
fun WorldGenerationEngine.generateBlocking(
    config: WorldGenConfig,
    previous: WorldMap? = null,
    accelerator: ErosionAccelerator? = null,
    progress: GenerationProgress = GenerationProgress { _, _, _ -> }
): WorldMap = runBlocking { generate(config, previous, accelerator, progress) }
