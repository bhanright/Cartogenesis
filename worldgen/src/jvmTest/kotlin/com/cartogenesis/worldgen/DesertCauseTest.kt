package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome
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
 * Three candidates, and they are distinguishable. A desert can be dry because its row is dry (the
 * latitude bands), because the air reaching it crossed a mountain (rain shadow), or because the
 * air reaching it crossed a great deal of land and had nothing left (continentality). Each cell is
 * measured for all three, and the misplaced deserts are compared against the correctly placed ones.
 */
class DesertCauseTest {

    @Test
    fun `report what the misplaced deserts have in common`() {
        listOf(7L, 42L, 1234L, 99L).forEach { seed ->
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val w = world.width
            val h = world.height

            // How far the air travelled over land before arriving, and the greatest climb it made
            // on the way. Both are walked along each row's own wind direction, exactly as the
            // precipitation march does.
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
                    else "%s: %d cells, fetch %.0f, upwind climb %.3f, band factor %.2f".format(
                        name, count, fetchTotal.toDouble() / count,
                        climbTotal / count, bandTotal / count
                    )
            }

            val placed = Group("desert 15-45")
            val misplaced = Group("desert outside")
            val other = Group("other land   ")

            for (y in 0 until h) {
                val lat = abs(latitudeOfRow(y, h))
                val band = bandFactor(lat)
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!world.sea.isLand[i]) continue
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

    /** Mirrors ClimateStage's bands, so the reported factor is the one actually applied. */
    private fun bandFactor(lat: Float): Float {
        fun bell(centre: Float, width: Float): Float {
            val t = (lat - centre) / width
            return kotlin.math.exp(-t * t)
        }
        return (1f + 1.0f * bell(0f, 12f) - 0.55f * bell(30f, 13f) +
            0.5f * bell(55f, 15f) - 0.35f * bell(90f, 18f)).coerceAtLeast(0.05f)
    }
}
