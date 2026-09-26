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
 *
 * The internal variants take the tectonic uplift with no default, for the same reason. The engine
 * always hands the stage `PlateResult.upliftRateMmPerYear`, and the flexure only answers an uplift
 * it is given; a guard that let the rate default to null ran a stage the map is never made by, and
 * every one of them did (Audit III's B-I2). So each caller says which it is running: the plates'
 * own rate, which is production, or null, which is a control and says so where it is passed.
 */
fun erodeBlocking(
    config: WorldGenConfig,
    height: FloatField,
    accelerator: ErosionAccelerator? = null,
    upliftRateMmPerYear: FloatField? = null
): ErosionResult = runBlocking { ErosionStage.apply(config, height, upliftRateMmPerYear, accelerator) }

/**
 * The thermal sweeps alone, with the activity-tile skip switchable — the control
 * `ErosionSkipTest` needs. No hydraulic rounds, so this is not the whole stage.
 */
internal fun thermalSweepBlocking(
    config: WorldGenConfig,
    height: FloatField,
    skipSettled: Boolean,
    sweeps: Int = ErosionStage.sweepsFor(config)
): ErosionResult = runBlocking { ErosionStage.thermalSweep(config, height, skipSettled, sweeps) }

/** The whole stage, reporting each hydraulic round's mass budget to the deposition guard. */
internal fun erodeBlockingReportingRounds(
    config: WorldGenConfig,
    height: FloatField,
    upliftRateMmPerYear: FloatField?,
    onRound: (RoundMass) -> Unit
): ErosionResult =
    runBlocking { ErosionStage.apply(config, height, upliftRateMmPerYear, null, onRound) }

/** The whole stage, recording which mechanism laid sediment where, for the fan-outline guards. */
internal fun erodeBlockingLoggingDeposition(
    config: WorldGenConfig,
    height: FloatField,
    upliftRateMmPerYear: FloatField?,
    log: DepositionLog
): ErosionResult = runBlocking { ErosionStage.apply(config, height, upliftRateMmPerYear, null, null, log) }

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
    upliftRateMmPerYear: FloatField?,
    receiverClamp: Boolean,
    onRound: ((RoundMass) -> Unit)? = null
): ErosionResult = runBlocking {
    ErosionStage.apply(config, height, upliftRateMmPerYear, null, onRound, receiverClamp = receiverClamp)
}

/**
 * The whole stage, with the cover's shielding switchable and every routing pass's weight sum
 * reported — what `ClimateFedErosionTest` observes production through.
 *
 * Kept off `WorldGenConfig` for the reason [erodeBlockingWithReceiverClamp] is: a world cut by the
 * rain but not held back by what grows on it is not a world anyone wants, only the control that
 * shows the cover is doing something. The guards read the height field this returns rather than
 * rebuilding the incision formula beside it, so a shielding term deleted from `cut` shows up as a
 * failure instead of passing a test that was computing its own answer.
 */
internal fun erodeBlockingObservingCover(
    config: WorldGenConfig,
    height: FloatField,
    upliftRateMmPerYear: FloatField?,
    shieldCut: Boolean = true,
    /** See [erodeBlockingWithReceiverClamp]; production keeps it on. */
    receiverClamp: Boolean = true,
    weightSums: ((String, Double, Int) -> Unit)? = null,
    /** Handed every cell the incision reaches, as [erodeBlockingWatchingIncision] hands it. */
    incisionWatch: IncisionWatch? = null
): ErosionResult = runBlocking {
    ErosionStage.apply(
        config, height, upliftRateMmPerYear, null, null, null, receiverClamp = receiverClamp,
        weightSums = weightSums, shieldCut = shieldCut, incisionWatch = incisionWatch
    )
}

/**
 * The whole stage, handing every cell the implicit incision reaches and every round's result to
 * [watch]: what the incision's guards observe production through, since the finished heights hide
 * the law's rate and the level each cell was cut toward.
 */
internal fun erodeBlockingWatchingIncision(
    config: WorldGenConfig,
    height: FloatField,
    upliftRateMmPerYear: FloatField?,
    watch: IncisionWatch,
    receiverClamp: Boolean = true
): ErosionResult = runBlocking {
    ErosionStage.apply(
        config, height, upliftRateMmPerYear, null, null, null, receiverClamp = receiverClamp,
        incisionWatch = watch
    )
}
