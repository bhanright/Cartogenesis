package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.abs
import kotlin.test.Test

/**
 * Whether realm borders actually follow the ground, or merely look as though they might.
 *
 * The expansion is cost-weighted — climbing is dear, crossing a river is dear — so borders are
 * *supposed* to settle onto ridges and rivers without being told to. That is a claim, and it has
 * never been measured.
 *
 * Measured against a null model, because the raw numbers mean nothing on their own. If a tenth of
 * all land sits beside a river, then a tenth of border cells sitting beside a river tells us the
 * borders ignore rivers entirely; it is the ratio between the two that says whether the mechanism
 * bites. A ratio of 1 is indistinguishable from drawing the lines at random.
 */
class BorderRealismTest {

    @Test
    fun `report how closely borders follow rivers and ridges`() {
        listOf(7L, 42L, 1234L, 99L).forEach { seed ->
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val w = world.width
            val h = world.height
            val elevation = world.sea.relativeElevation.data

            // Cells a drawn river runs through, plus their immediate neighbours, since a border
            // following a river sits on its bank rather than in the channel.
            val nearRiver = BooleanArray(w * h)
            world.rivers.rivers.forEach { river ->
                river.cells.forEach { c ->
                    nearRiver[c] = true
                    val x = c % w
                    val y = c / w
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until h) continue
                        for (dx in -1..1) nearRiver[ny * w + ((x + dx + w) % w)] = true
                    }
                }
            }

            var borderCells = 0
            var borderOnRiver = 0
            var borderSlope = 0.0
            var borderCrest = 0.0
            var landCells = 0
            var landOnRiver = 0
            var landSlope = 0.0
            var landCrest = 0.0

            for (y in 1 until h - 1) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!world.sea.isLand[i]) continue
                    val owner = world.nations.nationId[i]

                    // Steepest step to any neighbour, in elevation per unit of map width, so the
                    // figure means the same at any resolution.
                    var steepest = 0f
                    var isBorder = false
                    for (dy in -1..1) {
                        val ny = y + dy
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val n = ny * w + ((x + dx + w) % w)
                            if (!world.sea.isLand[n]) continue
                            val rise = abs(elevation[i] - elevation[n])
                            if (rise > steepest) steepest = rise
                            if (world.nations.nationId[n] != owner) isBorder = true
                        }
                    }
                    val slope = steepest.toDouble() * w

                    // Ridge-ness, which is not the same thing as steepness and is the thing a
                    // watershed actually has. A divide stands higher than the ground on both
                    // sides of it; steepness peaks on the flanks below, so measuring slope alone
                    // looks for a ridge everywhere except on top of it.
                    var crest = 0f
                    for (axis in 0 until 2) {
                        val ax = if (axis == 0) 1 else 0
                        val ay = if (axis == 0) 0 else 1
                        val upY = y - ay
                        val downY = y + ay
                        if (upY < 0 || downY >= h) continue
                        val a = elevation[upY * w + ((x - ax + w) % w)]
                        val b = elevation[downY * w + ((x + ax) % w)]
                        val rise = minOf(elevation[i] - a, elevation[i] - b)
                        if (rise > crest) crest = rise
                    }
                    val ridge = crest.toDouble() * w

                    landCells++
                    landSlope += slope
                    landCrest += ridge
                    if (nearRiver[i]) landOnRiver++
                    if (isBorder) {
                        borderCells++
                        borderSlope += slope
                        borderCrest += ridge
                        if (nearRiver[i]) borderOnRiver++
                    }
                }
            }

            if (borderCells == 0 || landCells == 0) return@forEach
            val riverShare = borderOnRiver.toDouble() / borderCells
            val riverBase = landOnRiver.toDouble() / landCells
            val slopeShare = borderSlope / borderCells
            val slopeBase = landSlope / landCells
            val crestShare = borderCrest / borderCells
            val crestBase = landCrest / landCells

            // Parenthesised: format binds to the literal it touches, so without these the
            // arguments land on the second half of the message alone.
            println(
                ("BORDER seed %s: river %.2fx, slope %.2fx, ridge %.2fx " +
                    "(on a river %.1f%% against %.1f%% of land)").format(
                    seed,
                    riverShare / riverBase, slopeShare / slopeBase, crestShare / crestBase,
                    riverShare * 100, riverBase * 100
                )
            )
        }
    }
}
