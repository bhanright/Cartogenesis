package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.BoundaryLayer
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test

/**
 * Why the deserts are where they are.
 *
 * Deserts should sit near the horse latitudes, roughly 15 to 45 degrees, where descending air of
 * the subtropical high suppresses rain. The audit says they partly do — 97% and 75% of desert
 * falls in that band on two seeds, but only 53% and 43% on two others — and the question this
 * answers is what the misplaced ones have in common.
 *
 * Three candidates, and they are distinguishable. A desert can be dry because the atmosphere sinks
 * over it (the solved descent at the boundary layer's top), because the air reaching it crossed a
 * mountain (rain shadow), or because the air reaching it crossed a great deal of land and had
 * nothing left (continentality). Each cell is
 * measured for all three, and the misplaced deserts are compared against the correctly placed ones.
 */
class DesertCauseTest {

    @Test
    fun `report what the misplaced deserts have in common`() {
        listOf(7L, 42L, 1234L, 99L).forEach { seed ->
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig.forRows(seed, 512)
            )
            val w = world.width
            val h = world.height
            val ascentMmPerS = yearAscentMmPerS(world)

            // How far the air travelled over land before arriving, and the greatest climb it made
            // on the way. Both are walked along one direction per row, the annual zonal wind read
            // at the row's first column. That is an approximation of the march and not the march:
            // since A3 and W2 the march is seasonal, per cell, slanted and swept twice, so these
            // figures describe a simpler air than the one that made the deserts.
            val fetch = IntArray(w * h)
            val climb = FloatArray(w * h)
            for (y in 0 until h) {
                val direction = world.climate.windDirection[y * w]
                var overLand = 0
                var highest = 0f
                // Two laps, so a cell near the upwind edge is not credited with a short fetch
                // merely because the walk started there.
                for (lap in 0 until 2) {
                    for (step in 0 until w) {
                        val x = if (direction > 0) step else w - 1 - step
                        val i = y * w + x
                        if (!world.sea.isLand[i]) {
                            overLand = 0
                            highest = 0f
                            continue
                        }
                        overLand++
                        val elevation = world.sea.relativeElevation.data[i]
                        if (elevation > highest) highest = elevation
                        if (lap == 1) {
                            fetch[i] = overLand
                            climb[i] = highest
                        }
                    }
                }
            }

            class Group(val name: String) {
                var count = 0
                var fetchTotal = 0L
                var climbTotal = 0.0
                var bandTotal = 0.0
                fun add(f: Int, c: Float, band: Float) {
                    count++; fetchTotal += f; climbTotal += c; bandTotal += band
                }
                override fun toString(): String =
                    if (count == 0) "$name: none"
                    else "%s: %d cells, fetch %.0f, upwind climb %.3f, ascent %.2f mm/s".format(
                        name, count, fetchTotal.toDouble() / count,
                        climbTotal / count, bandTotal / count
                    )
            }

            val placed = Group("desert 15-45")
            val misplaced = Group("desert outside")
            val other = Group("other land   ")

            for (y in 0 until h) {
                val lat = abs(latitudeOfRow(y, h))
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!world.sea.isLand[i]) continue
                    val band = ascentMmPerS[i]
                    val group = when {
                        world.climate.biome[i] != Biome.DESERT -> other
                        lat in 15f..45f -> placed
                        else -> misplaced
                    }
                    group.add(fetch[i], climb[i], band)
                }
            }

            println("DESERT seed $seed")
            listOf(placed, misplaced, other).forEach { println("DESERT   $it") }
        }
    }

    private fun latitudeOfRow(y: Int, height: Int): Float =
        90f - 180f * (y + 0.5f) / height

    /**
     * The vertical velocity at the boundary layer's top a cell's rain actually saw, the mean of the
     * two halves', millimeters a second, positive up: the coupled atmosphere's own
     * ([BoundaryLayer.groundAscentMps]), read from the stage rather than restated.
     */
    private fun yearAscentMmPerS(world: com.cartogenesis.worldgen.model.WorldMap): FloatArray {
        val coupled = ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean) ?: return FloatArray(world.width * world.height)
        val atmosphere = coupled.coupling.atmosphere
        val july = BoundaryLayer.groundAscentMps(atmosphere, atmosphere.julyHalf)
        val january = BoundaryLayer.groundAscentMps(atmosphere, atmosphere.januaryHalf)
        return FloatArray(july.size) { (july[it] + january[it]) * 500f }
    }
}
