package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.OceanCirculation
import com.cartogenesis.worldgen.pipeline.OceanStage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The circulation's solve reaches its tolerance where its V-cycles alone stall
 * (`OceanCirculation.solveWithKrylovRescue`).
 *
 * The world is seed 1234 at 256 rows under the belts' wind alone, the control ocean the wind guards
 * build (`MeridionalWindTest`). On the tectonic history's worlds its V-cycles spend their whole
 * budget of [OceanCirculation.MOST_CYCLES] and stop at a residual of 1.165e-3 against the
 * tolerance's 1e-3, and before the rescue the stage failed there by name (`OceanSolveFailure`):
 * that is the failure this guards. The rescue's BiCGSTAB, preconditioned by the same cycle, takes
 * it under the tolerance in one iteration (docs/DESIGN_LEDGER.md, TH1-1).
 */
class CirculationRescueTest : BorrowsSharedWorlds() {

    @Test
    fun `the circulation solves where its cycles alone stall`() {
        val base = WorldGenConfig.forRows(SEED, SharedWorlds.COARSE_ROWS)
        val config = base.copy(climate = base.climate.copy(meridionalWindShare = 0f, pressureWinds = false))
        val world = SharedWorlds.world(config)
        val zonal = ClimateStage.zonalClimate(world.config, world.sea)
        val circulation = OceanStage.circulate(world.config, world.sea, zonal) { stencil, values, passes ->
            OceanCirculation.relax(stencil, values, passes); values
        }
        val flow = circulation.flow
        println("RESCUE seed $SEED at ${SharedWorlds.COARSE_ROWS} rows: ${flow.cycles} cycles and iterations to ${flow.relativeResidual}")
        assertTrue(
            flow.cycles > OceanCirculation.MOST_CYCLES,
            "the cycles alone solved this world in ${flow.cycles}, so it no longer shows the stall the rescue is for"
        )
        assertTrue(
            flow.relativeResidual < OceanCirculation.RESIDUAL_TOLERANCE,
            "the circulation stopped at ${flow.relativeResidual} after ${flow.cycles} cycles and iterations"
        )
    }

    private companion object {
        const val SEED = 1234L
    }
}
