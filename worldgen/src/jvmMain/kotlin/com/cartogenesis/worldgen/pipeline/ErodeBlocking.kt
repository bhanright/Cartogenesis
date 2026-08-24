package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlinx.coroutines.runBlocking

/**
 * Erodes and waits for it, for callers that are not in a coroutine.
 *
 * The companion to `generateBlocking`, for the tests and tools that drive this one stage directly.
 * See that function for why the suspension exists at all and why it never happens on the JVM.
 */
fun erodeBlocking(
    config: WorldGenConfig,
    height: FloatField,
    accelerator: ErosionAccelerator? = null
): ErosionResult = runBlocking { ErosionStage.apply(config, height, accelerator) }

/** As above, for the internal variant the skip-correctness test drives. */
internal fun erodeBlocking(
    config: WorldGenConfig,
    height: FloatField,
    skipSettled: Boolean
): ErosionResult = ErosionStage.apply(config, height, skipSettled)
