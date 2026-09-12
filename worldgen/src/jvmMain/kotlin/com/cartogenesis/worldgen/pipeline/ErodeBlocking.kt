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

/** As above, reporting each hydraulic round's mass budget to the deposition guard. */
internal fun erodeBlocking(
    config: WorldGenConfig,
    height: FloatField,
    onRound: (RoundMass) -> Unit
): ErosionResult = runBlocking { ErosionStage.apply(config, height, null, onRound) }

/** As above, recording which mechanism laid sediment where, for the fan-outline guards. */
internal fun erodeBlocking(
    config: WorldGenConfig,
    height: FloatField,
    log: DepositionLog
): ErosionResult = runBlocking { ErosionStage.apply(config, height, null, null, log) }

/**
 * As above, with the receiver clamp switchable — the control `ReceiverClampTest` needs.
 *
 * Kept off `WorldGenConfig` deliberately. The clamp is not a taste and not a feature: it is the
 * bound every landscape-evolution model since FastScape holds a node's new elevation to, and a
 * world generated without it has holes in its rivers' beds. So the only thing that can turn it off
 * is a test driving this stage directly.
 */
internal fun erodeBlocking(
    config: WorldGenConfig,
    height: FloatField,
    receiverClamp: Boolean,
    onRound: ((RoundMass) -> Unit)? = null
): ErosionResult = runBlocking {
    ErosionStage.apply(config, height, null, onRound, receiverClamp = receiverClamp)
}
