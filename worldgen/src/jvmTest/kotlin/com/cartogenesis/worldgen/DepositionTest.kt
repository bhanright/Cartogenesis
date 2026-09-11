package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.RoundMass
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlocking
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B3: rivers put material back down.
 *
 * Two things have to be true, and they pull against each other. The bookkeeping has to balance —
 * every scrap the incision takes off the land has to end up somewhere, either settled further down
 * the network or carried out to sea — and the result has to be *visible*, which for deposition
 * means new land at the mouths of the biggest rivers.
 *
 * The mass check is the one that catches a broken implementation; the delta check is the one that
 * catches an implementation that is correct and does nothing. Both are needed, which is why the
 * second is shown failing against the `deposition = false` control before it is shown passing.
 */
class DepositionTest {

    /** The sea-level percentile every world here is generated at, and measured at. */
    private val seaLevel = WorldGenConfig().seaLevel

    /**
     * The fingerprint of the world without deposition, at the size and seed
     * `WorldFingerprintTest` uses.
     *
     * First measured on the merge base 04001f5, and re-recorded when B2 merged: the crust-pair
     * boundary profiles are a terrain change, so the world this pins is a different one and the
     * land count moved from 6354 to 6226. The assertion below is unchanged in meaning — with the
     * switch off, the hydraulic pass must produce the same rock it did before deposition existed —
     * and the two assertions after it, which prove no deposition knob leaks and that running the
     * pass while laying nothing down leaves the rock bit-identical, are what carry that claim
     * structurally rather than by memory of a number.
     *
     * `deposition = false` has to reproduce it exactly. Deposition adds arithmetic to the hydraulic
     * pass but must add none of it when the switch is off, and "nearly the same world" would not
     * prove that — a single changed float in the incision expression shows up here as a completely
     * different number.
     *
     * Re-recorded once more by B4, as the note left for it here said it would be: glaciation
     * carves the height field between the sea-level cut and everything downstream, so the rock the
     * hydraulic pass hands on is no longer the rock this pinned. The land count did *not* move —
     * `GlaciationStage` never touches `isLand`, by construction — which is itself worth reading as
     * a check on that claim, at a seed and size nothing else in B4 measures.
     *
     * A later chunk that changes what the terrain stages produce will move these legitimately, and
     * is expected to re-record them and say so in its report. Nothing but a terrain change should
     * touch them.
     *
     * Re-recorded again when `GlaciationStage` was split into its two regimes — valley glaciers
     * only where the ground is channelled, ice-sheet scour everywhere else — which changes what the
     * ice leaves behind on every cold world and therefore what the hydraulic pass is handed. The
     * land count did not move, again: 6226 before and after, because the coastline is still cut
     * before this stage runs and this stage still never touches it.
     */
    private val startingPointElevation = 7450875979753259226L
    private val startingPointLand = 6226

    @Test
    fun `every round conserves mass`() {
        val config = WorldGenConfig(seed = 42L, width = 512, height = 512)
        val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height

        val rounds = ArrayList<RoundMass>()
        erodeBlocking(config, uplift) { rounds.add(it) }
        assertEquals(config.erosion.hydraulicRounds, rounds.size, "not every round reported")

        var worstBudget = 0.0
        var worstField = 0.0
        rounds.forEachIndexed { round, mass ->
            // What came off the land went somewhere: downstream, or out to sea.
            val budgetError = abs(mass.incised - (mass.deposited + mass.lostToSea)) /
                max(mass.incised, 1e-12)
            // And independently: the height field itself fell by exactly what left the model. This
            // is the check the tallies cannot fudge, since it is read off the terrain rather than
            // counted as the terrain is written.
            val fieldError = abs(mass.fieldDrop - mass.lostToSea) / max(mass.lostToSea, 1e-12)
            worstBudget = max(worstBudget, budgetError)
            worstField = max(worstField, fieldError)
            println(
                "DEPOSITION round %2d: incised %.5f = deposited %.5f + lost %.5f (%.4f%% off); field fell %.5f (%.4f%% off)"
                    .format(
                        round + 1, mass.incised, mass.deposited, mass.lostToSea,
                        budgetError * 100, mass.fieldDrop, fieldError * 100
                    )
            )
            assertTrue(
                budgetError < 0.01,
                "round ${round + 1}: incised ${mass.incised} against deposited ${mass.deposited} " +
                    "plus lost ${mass.lostToSea}"
            )
            assertTrue(
                fieldError < 0.01,
                "round ${round + 1}: the field fell ${mass.fieldDrop} but ${mass.lostToSea} left"
            )
        }
        println(
            "DEPOSITION worst of %d rounds: budget %.4f%%, field %.4f%%"
                .format(rounds.size, worstBudget * 100, worstField * 100)
        )

        // A round that moved nothing would balance trivially. Say out loud that it did not.
        assertTrue(rounds.all { it.deposited > 0.0 }, "some round deposited nothing at all")
    }

    @Test
    fun `river mouths gain land, and do not without deposition`() {
        val base = WorldGenConfig(seed = 42L, width = 512, height = 512)
        val without = WorldGenerationEngine.generateBlocking(
            base.copy(erosion = base.erosion.copy(deposition = false))
        )

        // Ground rule 2: the same measurement against a world that cannot deposit. The old
        // coastline is compared with itself, so nothing can have been gained.
        val control = mouthsGainingLand(
            WorldGenerationEngine.generateBlocking(
                base.copy(erosion = base.erosion.copy(deposition = false))
            ),
            without
        )
        println("DELTA control (deposition off): $control mouths gained land within 4 cells")
        assertTrue(
            control < 3,
            "the deposition-off control was expected to fail the guard, but gained land at " +
                "$control mouths"
        )

        val world = WorldGenerationEngine.generateBlocking(base)
        val gained = mouthsGainingLand(world, without)
        // The guard's own radius is generous, so the tighter figure is reported beside it: new
        // ground the mouth is standing on rather than merely near.
        val touching = mouthsGainingLand(world, without, reach = 1)
        val newCells = gainedLand(world, without).count { it }
        println(
            "DELTA seed 42: $gained mouths gained land within 4 cells of the old coastline " +
                "($touching with new ground immediately beside the mouth); $newCells new land " +
                "cells in all"
        )
        assertTrue(
            gained >= 3,
            "only $gained river mouths gained land within 4 cells of the old coastline"
        )
    }

    @Test
    fun `deposition off reproduces the world this branch started from`() {
        val off = WorldGenConfig(seed = 42L, width = 128, height = 128).let {
            it.copy(erosion = it.erosion.copy(deposition = false))
        }
        val world = WorldGenerationEngine.generateBlocking(off)
        println(
            "DEPOSITION off: elevation=${checksum(world)} land=${world.sea.landCellCount} " +
                "rivers=${world.rivers.rivers.size} realms=${world.nations.nations.size}"
        )

        val on = WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = 42L, width = 128, height = 128)
        )
        println(
            "DEPOSITION on:  elevation=${checksum(on)} land=${on.sea.landCellCount} " +
                "rivers=${on.rivers.rivers.size} realms=${on.nations.nations.size}"
        )

        assertEquals(
            startingPointLand, world.sea.landCellCount, "land count moved with the switch off"
        )
        assertEquals(
            startingPointElevation, checksum(world),
            "the elevation fingerprint moved with deposition off"
        )

        // And the switch really is a switch: with it off, none of the knobs beside it can leak.
        val fiddled = off.copy(
            erosion = off.erosion.copy(
                transportCapacity = 0.1f,
                depositionRate = 1f,
                deltaShare = 1f,
                deltaMinCatchment = 0f,
                lakeShare = 1f,
                deltaReach = 20,
                deltaFreeboard = 0.5f
            )
        )
        assertEquals(
            checksum(world), checksum(WorldGenerationEngine.generateBlocking(fiddled)),
            "a deposition knob changed the world while deposition was off"
        )

        // The stronger claim, and the one that makes this chunk safe to build on: with deposition
        // *running* but laying nothing down, the rock is bit-identical to the rock with the whole
        // machinery switched off. Sediment is carried as its own layer and never fed back into the
        // routing surface, so the water cuts the same valleys in the same places it always did;
        // what deposition changes is only the spoil lying on top of them.
        val silent = WorldGenConfig(seed = 42L, width = 128, height = 128).let {
            it.copy(
                erosion = it.erosion.copy(deltaShare = 0f, depositionRate = 0f, lakeShare = 0f)
            )
        }
        assertEquals(
            checksum(world), checksum(WorldGenerationEngine.generateBlocking(silent)),
            "running the deposition pass changed the rock even with nothing laid down"
        )
    }

    /**
     * The render the plan asks for: the coast around each seed's biggest river mouths, magnified,
     * with the ground the rivers built picked out so it is unmistakable.
     *
     * A whole-world PNG is useless for this — a delta a dozen cells across is a smudge at 512 — so
     * each mouth gets its own 96-cell window at eight times scale.
     */
    @Test
    fun `render the coast around the largest river mouths`() {
        val dir = java.io.File("build/maps").apply { mkdirs() }
        listOf(7L, 42L, 1234L).forEach { seed ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
            val without = WorldGenerationEngine.generateBlocking(
                base.copy(erosion = base.erosion.copy(deposition = false))
            )
            val world = WorldGenerationEngine.generateBlocking(base)
            val gained = gainedLand(world, without)
            val w = world.width

            // A delta needs a big river, and only a river that actually reaches the sea can build
            // one — most end by joining another.
            val mouths = world.rivers.rivers
                .filter { river ->
                    val c = river.cells.last()
                    !world.sea.isLand[c] ||
                        neighboursOf(c, world.width, world.height).any { !world.sea.isLand[it] }
                }
                .sortedByDescending { it.widths.last() }
                .map { it.cells.last() }
                .take(3)

            mouths.forEachIndexed { rank, mouth ->
                val file = java.io.File(dir, "seed$seed-delta${rank + 1}.png")
                javax.imageio.ImageIO.write(
                    coastCrop(world, gained, mouth % w, mouth / w), "png", file
                )
                println(
                    "DELTA render seed $seed mouth ${rank + 1} at (${mouth % w},${mouth / w})" +
                        " -> ${file.name}"
                )
            }
        }
    }

    /** A magnified window on the coast: land shaded by height, rivers drawn, new ground picked out. */
    private fun coastCrop(
        world: WorldMap,
        gained: BooleanArray,
        centreX: Int,
        centreY: Int,
        span: Int = 96,
        scale: Int = 8
    ): java.awt.image.BufferedImage {
        val w = world.width
        val h = world.height
        val x0 = centreX - span / 2
        val y0 = (centreY - span / 2).coerceIn(0, h - span)
        val image = java.awt.image.BufferedImage(
            span * scale, span * scale, java.awt.image.BufferedImage.TYPE_INT_RGB
        )

        for (py in 0 until span) {
            val y = y0 + py
            for (px in 0 until span) {
                val x = ((x0 + px) % w + w) % w
                val c = y * w + x
                val e = world.sea.relativeElevation.data[c]
                val colour = when {
                    gained[c] -> 0xC8A24B
                    world.sea.isLand[c] -> {
                        val v = (150 + (e * 95).toInt()).coerceIn(0, 255)
                        (v shl 16) or (v shl 8) or (v * 2 / 3)
                    }
                    else -> {
                        val v = (70 + (e * 60).toInt()).coerceIn(0, 90)
                        ((v / 3) shl 16) or ((v / 2) shl 8) or (v + 60)
                    }
                }
                for (dy in 0 until scale) {
                    for (dx in 0 until scale) {
                        image.setRGB(px * scale + dx, py * scale + dy, colour)
                    }
                }
            }
        }

        val g = image.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_ANTIALIASING,
            java.awt.RenderingHints.VALUE_ANTIALIAS_ON
        )
        g.color = java.awt.Color(0x3C7EA8)
        world.rivers.rivers.forEach { river ->
            for (k in 0 until river.cells.size - 1) {
                val a = river.cells[k]
                val b = river.cells[k + 1]
                if (abs(a % w - b % w) > w / 2) continue
                g.stroke = java.awt.BasicStroke(river.widths[k].coerceAtLeast(1f) * scale / 2f)
                g.drawLine(
                    (a % w - x0) * scale, (a / w - y0) * scale,
                    (b % w - x0) * scale, (b / w - y0) * scale
                )
            }
        }
        g.dispose()
        return image
    }

    /**
     * Which cells stand above water in [world] and did not in [before], both judged at the same
     * exact sea-level percentile rather than at each world's own shoreline.
     *
     * This detour is not fussiness; it is the difference between measuring deltas and measuring an
     * artefact. `SeaLevelStage` finds its threshold from a 4096-bin histogram and returns the bin's
     * lower edge, and near the shoreline a single bin holds thousands of cells — so the smallest
     * change anywhere in the height field can snap the threshold to the next bin and flip three or
     * four thousand cells between land and sea across the whole map at once. Against that, a delta
     * of forty cells is invisible: the first version of this guard reported 110 mouths "gaining
     * land" from a change that had built nothing like 110 deltas.
     *
     * Sorting the raw eroded height field and cutting at the exact rank gives both worlds the same
     * number of land cells by construction, so what is left is *where* the land is rather than how
     * much of it there is.
     */
    private fun gainedLand(world: WorldMap, before: WorldMap): BooleanArray {
        val now = exactLandMask(world.erosion.height.data)
        val then = exactLandMask(before.erosion.height.data)
        return BooleanArray(now.size) { now[it] && !then[it] }
    }

    private fun exactLandMask(height: FloatArray): BooleanArray {
        val sorted = height.copyOf()
        sorted.sort()
        val threshold = sorted[(sorted.size * seaLevel).toInt().coerceIn(0, sorted.size - 1)]
        return BooleanArray(height.size) { height[it] >= threshold }
    }

    /**
     * How many river mouths in [world] have new land beside them — a cell that stands above water
     * now and did not in [before], within [reach] cells of the mouth.
     *
     * Four cells is the plan's figure and is a low bar on purpose: a delta that reaches further is
     * more convincing, not less.
     */
    private fun mouthsGainingLand(world: WorldMap, before: WorldMap, reach: Int = 4): Int {
        val w = world.width
        val h = world.height
        val gained = gainedLand(world, before)

        var count = 0
        world.rivers.rivers.forEach { river ->
            val mouth = river.cells.last()
            val mx = mouth % w
            val my = mouth / w
            var grew = false
            for (dy in -reach..reach) {
                val y = my + dy
                if (y < 0 || y >= h) continue
                for (dx in -reach..reach) {
                    if (gained[y * w + (((mx + dx) % w) + w) % w]) grew = true
                }
            }
            if (grew) count++
        }
        return count
    }

    /** The eight cells around one, wrapping in x as the world does. */
    private fun neighboursOf(cell: Int, w: Int, h: Int): List<Int> {
        val x = cell % w
        val y = cell / w
        val out = ArrayList<Int>(8)
        for (dy in -1..1) {
            val ny = y + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                out.add(ny * w + (((x + dx) % w) + w) % w)
            }
        }
        return out
    }

    private fun checksum(world: WorldMap): Long {
        var sum = 0L
        world.sea.relativeElevation.data.forEach { sum = sum * 31 + it.toRawBits() }
        return sum
    }
}
