package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.ColumnWater
import com.cartogenesis.worldgen.pipeline.MoistureMarch
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The atmosphere coupled to the rain ([com.cartogenesis.worldgen.pipeline.AtmosphereCoupling]), on
 * the standard worlds ([SharedWorlds.DETAIL_ROWS]).
 *
 * - **The heating is the condensation's.** The latent heating the atmosphere reads, on its own grid,
 *   integrates over the sphere to `L_v` times the march's condensation on the map, to rounding; the
 *   condensation is what the march turned from vapor, not what fell, so the cloud the wind carries
 *   heats where it formed.
 * - **The loop settles**: the heating the last lap ran under within [MoistureMarch.CONVERGED_SHARE]
 *   of what its condensation asked for, and the land's rain within the same of its last lap's,
 *   before [MoistureMarch.MAX_LAPS].
 * - **What settles it, and that where it starts does not matter** (the deep tier): the plain
 *   iteration, the control, takes more laps over the worlds together and does not settle on some
 *   within the ceiling, and its slowest lap-to-lap ratio is the loop's feedback; started from twice the settled heating the loop settles on the same answer,
 *   within what a contraction of that feedback allows two answers each within their residual.
 */
class AtmosphereCouplingTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS
        const val ROWS = SharedWorlds.DETAIL_ROWS

        /**
         * How far the heating's area integral on the atmosphere's grid may stand from `L_v` times
         * the map's condensation, as a share: the area mean carried down is exact to rounding, the
         * spread keeps it exactly and the series filter's loss is put back as a constant
         * ([com.cartogenesis.worldgen.pipeline.AtmosphereRemap.forcing]); single precision on the
         * map's millions of cells, with a margin.
         */
        const val CONSERVATION_TOLERANCE = 1e-5

        /** The plain iteration's laps read for its slowest ratio: the last third of the cap. */
        const val TAIL_LAPS = MoistureMarch.MAX_LAPS / 3

        /** What the second start multiplies the settled heating by. */
        const val SECOND_START_FACTOR = 2.0
    }

    private fun world(seed: Long): WorldMap = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))

    @Test
    fun `the atmosphere's heating is the latent heat of the march's condensation`() {
        var worst = 0.0
        for (seed in seeds) {
            val world = world(seed)
            val coupled = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean))
            val coupling = coupled.coupling
            val grid = coupling.atmosphere.remap.coarse
            for ((name, heating, condensation, rain) in listOf(
                Quad("July", coupling.julyTargetWPerM2!!, coupled.march.julyHalfCondensationMm, coupled.march.julyHalfRainMm.data),
                Quad("January", coupling.januaryTargetWPerM2!!, coupled.march.januaryHalfCondensationMm, coupled.march.januaryHalfRainMm.data)
            )) {
                var coarse = 0.0
                for (cell in heating.indices) coarse += heating[cell] * grid.cellAreaSquareMeters[cell / grid.columns]
                val wattsPerMm = ColumnWater.LATENT_HEAT_J_PER_KG / MoistureMarch.SECONDS_PER_YEAR
                var ground = 0.0
                var fallen = 0.0
                var groundArea = 0.0
                for (cell in condensation.indices) {
                    val area = cos(ClimateStage.latitudeOf(cell / world.width, world.height) * PI / 180)
                    ground += area * condensation[cell] * wattsPerMm
                    fallen += area * rain[cell] * wattsPerMm
                    groundArea += area
                }
                // The map's sum over its cells' areas, scaled to the sphere's.
                val scale = grid.totalAreaSquareMeters / groundArea
                val share = abs(coarse / (ground * scale) - 1)
                worst = maxOf(worst, share)
                println("COUPLING seed %d %s: heating %.4e W against L_v times the condensation %.4e W (%.1e off); the rain's latent heat %.4e W (x%.4f)"
                    .format(seed, name, coarse, ground * scale, share, fallen * scale, fallen / ground))
            }
        }
        assertTrue(worst < CONSERVATION_TOLERANCE, "the heating stands $worst from L_v times the condensation")
    }

    @Test
    fun `the coupled loop settles`() {
        val unsettled = mutableListOf<String>()
        for (seed in seeds) {
            val world = world(seed)
            val coupled = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean))
            val residuals = coupled.coupling.residuals
            val changes = coupled.march.landRainChanges
            println("COUPLING seed $seed: ${coupled.march.laps} laps, the heating's residual ${residuals.joinToString { "%.4f".format(it) }}")
            println("COUPLING seed $seed: the land rain's change ${changes.joinToString { "%.4f".format(it) }}")
            if (residuals.last() >= MoistureMarch.CONVERGED_SHARE || changes.last() >= MoistureMarch.CONVERGED_SHARE) {
                unsettled.add("seed $seed at %.4f and %.4f".format(residuals.last(), changes.last()))
            }
        }
        assertTrue(unsettled.isEmpty(), "the loop did not settle on ${unsettled.joinToString()}")
    }

    /**
     * The plain relaxed iteration, the acceleration's control, and the loop started from twice the
     * settled heating. The bound on the two settled answers' distance is a contraction's: an
     * iterate `x` with residual `r` stands within `r / (1 - mu)` of the fixed point, `mu` the
     * loop's slowest feedback, read from the plain iteration's slowest lap-to-lap ratio.
     */
    @Test
    fun `the plain iteration does not settle, and where the loop starts does not move where it settles`() {
        var plainUnsettled = 0
        var plainLaps = 0
        var acceleratedLaps = 0
        var outside = 0
        for (seed in seeds) {
            val world = world(seed)
            val settled = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean))
            val plain = checkNotNull(
                ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean, ClimateStage.CouplingStart(depth = 0))
            )
            val plainResiduals = plain.coupling.residuals
            val tail = plainResiduals.takeLast(TAIL_LAPS)
            val ratios = tail.zipWithNext { before, after -> after / before }
            val feedback = ratios.sorted()[ratios.size / 2]
            if (plainResiduals.last() >= MoistureMarch.CONVERGED_SHARE) plainUnsettled++
            plainLaps += plain.march.laps
            acceleratedLaps += settled.march.laps
            val doubled = settled.coupling.julyLatentWPerM2.map { it * SECOND_START_FACTOR }.toDoubleArray() to
                settled.coupling.januaryLatentWPerM2.map { it * SECOND_START_FACTOR }.toDoubleArray()
            val restarted = checkNotNull(
                ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean, ClimateStage.CouplingStart(initialLatentWPerM2 = doubled))
            )
            val distance = eddyDistance(
                settled.coupling.julyLatentWPerM2 + settled.coupling.januaryLatentWPerM2,
                restarted.coupling.julyLatentWPerM2 + restarted.coupling.januaryLatentWPerM2,
                settled.coupling.atmosphere.remap.coarse
            )
            val bound = (settled.coupling.residuals.last() + restarted.coupling.residuals.last()) / (1 - feedback)
            if (distance > bound) outside++
            println(("COUPLING seed %d: the plain iteration %d laps, its residual %.4f at the last, its median ratio over the last %d laps %.3f " +
                "(the loop's feedback); started from %.0f times the settled heating, %d laps, settled %.4f from the first answer against a bound of %.4f")
                .format(seed, plain.march.laps, plainResiduals.last(), TAIL_LAPS, feedback, SECOND_START_FACTOR, restarted.march.laps, distance, bound))
        }
        println("COUPLING laps over the four worlds: the plain iteration %d, accelerated %d; the plain iteration unsettled on %d".format(plainLaps, acceleratedLaps, plainUnsettled))
        assertTrue(
            plainUnsettled > 0 && plainLaps > acceleratedLaps,
            "the plain iteration settles every world in $plainLaps laps against the acceleration's $acceleratedLaps, so the acceleration is not what settles the loop"
        )
        assertTrue(outside == 0, "on $outside worlds the loop settles somewhere else when started elsewhere")
    }

    /**
     * How far [second] stands from [first], each both halves on [grid] one after the other: the
     * area integral of their departures' difference from each row's mean over [first]'s, as the
     * loop's own residual reads it.
     */
    private fun eddyDistance(first: DoubleArray, second: DoubleArray, grid: SphericalGrid): Double {
        var moved = 0.0
        var size = 0.0
        val columns = grid.columns
        for (start in first.indices step columns) {
            val area = grid.cellAreaSquareMeters[(start % grid.cellCount) / columns]
            var firstMean = 0.0
            var secondMean = 0.0
            for (cell in start until start + columns) { firstMean += first[cell]; secondMean += second[cell] }
            firstMean /= columns
            secondMean /= columns
            for (cell in start until start + columns) {
                moved += area * abs((first[cell] - firstMean) - (second[cell] - secondMean))
                size += area * abs(first[cell] - firstMean)
            }
        }
        return moved / size
    }

    private data class Quad(val name: String, val heating: DoubleArray, val condensation: FloatArray, val rain: FloatArray)
}
