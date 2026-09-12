package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlinx.coroutines.runBlocking

/**
 * Erodes and waits for it, for callers that are not in a coroutine.
 *
 * The companion to `generateBlocking`, for the tests and tools that drive this one stage directly.
 * See that function for why the suspension exists at all and why it never happens on the JVM.
 *
 * Each variant below runs the *whole* stage and differs only in what it lets a test observe or
 * switch off, except [thermalSweepBlocking], which runs the thermal sweeps alone. They carry
 * separate names rather than overloading one: two of them once took a `Boolean` third argument
 * meaning entirely different things, and a positional call resolved to whichever needed no default
 * — quietly running a different piece of the pipeline than the caller asked for.
 */
fun erodeBlocking(
    config: WorldGenConfig,
    height: FloatField,
    accelerator: ErosionAccelerator? = null
): ErosionResult = runBlocking { ErosionStage.apply(config, height, accelerator) }

/**
 * The thermal sweeps alone, with the activity-tile skip switchable — the control
 * `ErosionSkipTest` needs. No hydraulic rounds, so this is not the whole stage.
 */
internal fun thermalSweepBlocking(
    config: WorldGenConfig,
    height: FloatField,
    skipSettled: Boolean
): ErosionResult = ErosionStage.thermalSweep(config, height, skipSettled)

/** The whole stage, reporting each hydraulic round's mass budget to the deposition guard. */
internal fun erodeBlockingReportingRounds(
    config: WorldGenConfig,
    height: FloatField,
    onRound: (RoundMass) -> Unit
): ErosionResult = runBlocking { ErosionStage.apply(config, height, null, onRound) }

/** The whole stage, recording which mechanism laid sediment where, for the fan-outline guards. */
internal fun erodeBlockingLoggingDeposition(
    config: WorldGenConfig,
    height: FloatField,
    log: DepositionLog
): ErosionResult = runBlocking { ErosionStage.apply(config, height, null, null, log) }

/**
 * The whole stage, with the receiver clamp switchable — the control `ReceiverClampTest` needs.
 *
 * Kept off `WorldGenConfig` deliberately. The clamp is not a taste and not a feature: it is the
 * bound every landscape-evolution model since FastScape holds a node's new elevation to, and a
 * world generated without it has holes in its rivers' beds. So the only thing that can turn it off
 * is a test driving this stage directly.
 */
internal fun erodeBlockingWithReceiverClamp(
    config: WorldGenConfig,
    height: FloatField,
    receiverClamp: Boolean,
    onRound: ((RoundMass) -> Unit)? = null
): ErosionResult = runBlocking {
    ErosionStage.apply(config, height, null, onRound, receiverClamp = receiverClamp)
}
