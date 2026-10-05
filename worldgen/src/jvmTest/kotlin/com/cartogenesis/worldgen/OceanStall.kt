package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.OceanCirculation

/**
 * The ocean's circulation that stops short of its tolerance on one control world, kept running as
 * a known failure by the clauses that read that world.
 *
 * Since K1's square weather moved seed 42's ground at 256 rows, its ocean under the belts' wind
 * alone (`ClimateConfig.pressureWinds` off, the control several climate guards read) stops at a
 * relative residual of 0.00120 against the solve's 0.001 within its 200 V-cycles. Given a
 * thousand, the world's own solve gets there and the ocean re-solved on the standard world's sea
 * stays at 0.0011985, a floor. With the pressure winds on, and at 512 rows either way, the same
 * seed solves, as every other standard seed does. L1 met the same stall on two of these worlds
 * (residuals 0.00107 and 0.00116) and took its change back out; this one is the brief's
 * (docs/DESIGN_LEDGER.md, K1, and `TODO.md`).
 *
 * A clause measures every other seed as before and passes a stalled one by, and the stalled seeds
 * are held here under one finding, so the day the ocean solves on them the clause asks to be armed.
 */
internal object OceanStall {

    const val FINDING =
        "K1: the ocean's circulation stops at 0.0012 against 0.001 on seed 42's 256-row world under the belts' wind alone"

    const val RECORDED = "seed 42"

    /** [measure] for [seed], or null with [seed] added to [stalled] where the ocean did not solve. */
    fun <T> orStalled(seed: Long, stalled: MutableList<Long>, measure: () -> T): T? =
        try {
            measure()
        } catch (stall: OceanCirculation.OceanSolveFailure) {
            println("OCEAN STALL seed $seed: ${stall.message}")
            stalled += seed
            null
        }

    /** The seeds [orStalled] passed by, held to the finding. */
    fun record(stalled: List<Long>) = KnownFailures.expect(FINDING, RECORDED) {
        if (stalled.isNotEmpty()) {
            throw RecordedViolation(
                "the ocean's circulation did not solve under the belts' wind alone on ${stalled.joinToString { "seed $it" }}",
                stalled.distinct().joinToString { "seed $it" }
            )
        }
    }
}
