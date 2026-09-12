package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.DepositionLog
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Throwaway: E6's three hypotheses, measured on the rift-mouth scene. Never committed. */
class SeedProbeE6 {

    private fun config(seed: Long, size: Int): WorldGenConfig {
        val base = WorldGenConfig(seed = seed, width = 512, height = 512, seaLevel = 0.62f)
        return base.copy(
            tectonics = base.tectonics.copy(plateCount = 14),
            nations = base.nations.copy(nationCount = 12)
        ).atResolution(size, size)
    }

    @Test
    fun `the rift mouth scene`() {
        val size = System.getProperty("e6.size", "2048").toInt()
        val seed = System.getProperty("e6.seed", "718106").toLong()
        val dir = File("build/deltas").apply { mkdirs() }
        val config = config(seed, size)

        val log = DepositionLog(size * size)
        val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height
        val eroded = erodeBlocking(config, uplift, log)
        val world = WorldGenerationEngine.generateBlocking(config)
        val bare = WorldGenerationEngine.generateBlocking(
            config.copy(erosion = config.erosion.copy(deposition = false))
        )
        val w = size
        val h = size
        val height = world.erosion.height.data
        val land = world.sea.isLand
        val lakes = world.rivers.lakes
        val bareLand = exactLand(bare.erosion.height.data, config.seaLevel)
        val sea = SeaLevelStage.apply(eroded.height, config.seaLevel)

        // The scene, as a box in cells at 2048 and scaled for any other grid.
        val s = size / 2048.0
        val x0 = (1020 * s).toInt()
        val y0 = (1700 * s).toInt()
        val x1 = (1160 * s).toInt()
        val y1 = (1860 * s).toInt()

        // ---- hypothesis 1: the terrace is floodplain filled to one level ----
        val levels = HashMap<Float, Int>()
        var plainCells = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * w + x
            if (log.mechanism[i] != DepositionLog.FLOODPLAIN || !land[i]) continue
            plainCells++
            levels[height[i]] = (levels[height[i]] ?: 0) + 1
        }
        val top = levels.entries.sortedByDescending { it.value }.take(3)
        println(
            "E6 terrace: $plainCells floodplain land cells in the scene, ${levels.size} distinct " +
                "heights; the three commonest hold ${top.joinToString { "${it.value}" }} cells " +
                "(${"%.1f".format(top.sumOf { it.value } * 100.0 / plainCells.coerceAtLeast(1))}% " +
                "of them)"
        )

        // ---- hypothesis 2: the pocket is water the lobe walled in ----
        // Every body of water in the finished world; a lake inside the scene, ringed by cells the
        // sea lobe laid, is the pocket.
        val ringed = ringedWater(w, h, land, lakes, log, x0, y0, x1, y1)
        println("E6 pocket: ${ringed.first} lake bodies in the scene, ${ringed.second} of them " +
            "with sea-lobe ground all round; ${ringed.third} cells in the largest")

        // ---- hypothesis 3: the crescents are successive rims ----
        val rounds = IntArray(16)
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * w + x
            if (log.mechanism[i] == DepositionLog.SEA_LOBE) {
                val r = log.lastRound[i].toInt()
                if (r in 0..15) rounds[r]++
            }
        }
        println("E6 crescents: sea-lobe cells in the scene by last round: " +
            rounds.withIndex().filter { it.value > 0 }.joinToString { "${it.index}=${it.value}" })

        // ---- the whole-world figures the guards will use ----
        println(worldFigures(world, bare, log, "main"))

        // ---- how flat is the aggraded surface? ----
        // The relief across the floodplain ground in the scene, and the gradient along the trunk,
        // both in the shoreline-relative units the routing works in and per cell of map.
        val relief = world.erosion.height.max() - sea.threshold
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * w + x
            if (log.mechanism[i] != DepositionLog.FLOODPLAIN || !land[i]) continue
            if (height[i] < lo) lo = height[i]
            if (height[i] > hi) hi = height[i]
        }
        // The same box, on the ground the deposition never touched, for scale.
        var bareLo = Float.MAX_VALUE
        var bareHi = -Float.MAX_VALUE
        val bareH = bare.erosion.height.data
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * w + x
            if (!bareLand[i]) continue
            if (bareH[i] < bareLo) bareLo = bareH[i]
            if (bareH[i] > bareHi) bareHi = bareH[i]
        }
        println(
            ("E6 flatness: the aggraded ground in the scene spans %.5f of the land's relief over " +
                "%d cells (%.2e per cell); the same box on the deposition-off world spans %.5f")
                .format(
                    (hi - lo) / relief, plainCells, (hi - lo) / relief / (y1 - y0).toFloat(),
                    (bareHi - bareLo) / relief
                )
        )

        // ---- how rough is the ground, with the deposition and without it? ----
        // The mean height step between neighbouring land cells, in units of the land's relief. A
        // surface aggraded to a flat has almost none; the ground under it has plenty.
        fun roughness(hs: FloatArray, mask: BooleanArray, ax0: Int, ay0: Int, ax1: Int, ay1: Int):
            Pair<Double, Int> {
            var sum = 0.0
            var n = 0
            for (y in ay0 until ay1) for (x in ax0 until ax1) {
                val i = y * w + x
                if (!mask[i]) continue
                if (x + 1 < ax1 && mask[i + 1]) {
                    sum += kotlin.math.abs(hs[i + 1] - hs[i]).toDouble(); n++
                }
                if (y + 1 < ay1 && mask[i + w]) {
                    sum += kotlin.math.abs(hs[i + w] - hs[i]).toDouble(); n++
                }
            }
            return (if (n == 0) 0.0 else sum / n / relief) to n
        }
        listOf(
            "whole scene" to intArrayOf(x0, y0, x1, y1),
            "lower valley" to intArrayOf(x0, (1780 * s).toInt(), x1, y1)
        ).forEach { (name, b) ->
            val now = roughness(height, land, b[0], b[1], b[2], b[3])
            val was = roughness(bareH, bare.sea.isLand, b[0], b[1], b[2], b[3])
            println(
                ("E6 roughness %s: mean step between neighbouring land cells %.2e of the relief " +
                    "with deposition (%d pairs), %.2e without (%d pairs) - a factor of %.1f")
                    .format(name, now.first, now.second, was.first, was.second,
                        was.first / now.first.coerceAtLeast(1e-12))
            )
        }

        // ---- the terrace's front: the longest straight run of coast in the scene ----
        fun longestCoastRun(mask: BooleanArray): Int {
            var longest = 0
            for (y in y0 until y1) {
                for (side in 0..1) {
                    val dy = if (side == 0) -1 else 1
                    var run = 0
                    for (x in x0..x1) {
                        val open = x < x1 && mask[y * w + x] &&
                            (y + dy < 0 || y + dy >= h || !mask[(y + dy) * w + x])
                        if (open) run++ else { if (run > longest) longest = run; run = 0 }
                    }
                }
            }
            for (x in x0 until x1) {
                for (side in 0..1) {
                    val dx = if (side == 0) -1 else 1
                    var run = 0
                    for (y in y0..y1) {
                        var nx = (x + dx) % w
                        if (nx < 0) nx += w
                        val open = y < y1 && mask[y * w + x] && !mask[y * w + nx]
                        if (open) run++ else { if (run > longest) longest = run; run = 0 }
                    }
                }
            }
            return longest
        }
        // And the control that separates "deposition made this shape" from "deposition moved the
        // sea level onto a different contour of the same ground": the deposition-off terrain, cut
        // at the *deposited* world's own threshold.
        val movedCut = BooleanArray(w * h) { bareH[it] >= sea.threshold }
        var lo2 = Float.MAX_VALUE
        var hi2 = -Float.MAX_VALUE
        for (i in 0 until w * h) {
            if (bareH[i] < lo2) lo2 = bareH[i]
            if (bareH[i] > hi2) hi2 = bareH[i]
        }
        println(
            "E6 coast: longest straight run of shoreline in the scene ${longestCoastRun(land)} " +
                "cells with deposition, ${longestCoastRun(bare.sea.isLand)} without, and " +
                "${longestCoastRun(movedCut)} on the deposition-off ground cut at the deposited " +
                "world's own threshold"
        )

        // ---- the same scene without deposition ----
        val bareLakes = bare.rivers.lakes
        var bareLakeCells = 0
        var lakeCells = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * w + x
            if (bareLakes.isLake(i)) bareLakeCells++
            if (lakes.isLake(i)) lakeCells++
        }
        println("E6 scene lakes: $lakeCells cells with deposition, $bareLakeCells without")
        val bareImage = BufferedImage((x1 - x0) * 4, (y1 - y0) * 4, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until (y1 - y0)) for (x in 0 until (x1 - x0)) {
            val i = (y0 + y) * w + (x0 + x)
            val colour = when {
                bareLakes.isLake(i) -> 0x2080FF
                bare.sea.isLand[i] -> 0xB0B0A0
                else -> 0x203040
            }
            for (dy in 0 until 4) for (dx in 0 until 4) {
                bareImage.setRGB(x * 4 + dx, y * 4 + dy, colour)
            }
        }
        ImageIO.write(bareImage, "png", File(dir, "e6-scene-bare-$seed-$size.png"))

        // ---- a false-colour scene map ----
        val cw = x1 - x0
        val ch = y1 - y0
        val scale = 4
        val image = BufferedImage(cw * scale, ch * scale, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until ch) for (x in 0 until cw) {
            val i = (y0 + y) * w + (x0 + x)
            val colour = when {
                lakes.isLake(i) -> if (log.mechanism[i] == DepositionLog.NONE) 0x2080FF else 0x00E0FF
                !land[i] -> 0x203040
                log.mechanism[i] == DepositionLog.SEA_LOBE ->
                    0x400000 + 0x140000 * log.lastRound[i].toInt().coerceIn(0, 11)
                log.mechanism[i] == DepositionLog.LAKE_FAN -> 0x30C040
                log.mechanism[i] == DepositionLog.FLOODPLAIN -> 0xA0A000
                bareLand[i] -> 0xB0B0A0
                else -> 0xE0E0E0
            }
            for (dy in 0 until scale) for (dx in 0 until scale) {
                image.setRGB(x * scale + dx, y * scale + dy, colour)
            }
        }
        ImageIO.write(image, "png", File(dir, "e6-scene-mech-$seed-$size.png"))
        println("E6 scene box ($x0,$y0)-($x1,$y1), sea threshold ${sea.threshold}")
    }

    private fun ringedWater(
        w: Int,
        h: Int,
        land: BooleanArray,
        lakes: com.cartogenesis.worldgen.pipeline.LakeResult,
        log: DepositionLog,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int
    ): Triple<Int, Int, Int> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var bodies = 0
        var walled = 0
        var largest = 0
        for (sy in y0 until y1) for (sx in x0 until x1) {
            val start = sy * w + sx
            if (seen[start] || !lakes.isLake(start)) continue
            var topIdx = 0
            stack[topIdx++] = start
            seen[start] = true
            var cells = 0
            var shore = 0
            var lobeShore = 0
            while (topIdx > 0) {
                val c = stack[--topIdx]
                cells++
                neighbours(w, h, c) { n ->
                    if (lakes.isLake(n)) {
                        if (!seen[n]) { seen[n] = true; stack[topIdx++] = n }
                    } else if (land[n]) {
                        shore++
                        if (log.mechanism[n] == DepositionLog.SEA_LOBE) lobeShore++
                    }
                }
            }
            bodies++
            if (shore > 0 && lobeShore == shore) {
                walled++
                if (cells > largest) largest = cells
            }
        }
        return Triple(bodies, walled, largest)
    }

    /** The two whole-world counts E6's guards are about. */
    private fun worldFigures(
        world: WorldMap,
        bare: WorldMap,
        log: DepositionLog,
        tag: String
    ): String {
        val w = world.width
        val h = world.height
        val land = world.sea.isLand
        val lakes = world.rivers.lakes
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var walledBodies = 0
        var walledCells = 0
        var annulus = 0
        for (start in 0 until w * h) {
            if (seen[start] || !lakes.isLake(start)) continue
            var topIdx = 0
            stack[topIdx++] = start
            seen[start] = true
            var cells = 0
            var shore = 0
            var lobeShore = 0
            val members = ArrayList<Int>()
            while (topIdx > 0) {
                val c = stack[--topIdx]
                cells++
                members.add(c)
                neighbours(w, h, c) { n ->
                    if (lakes.isLake(n)) {
                        if (!seen[n]) { seen[n] = true; stack[topIdx++] = n }
                    } else if (land[n]) {
                        shore++
                        if (log.mechanism[n] == DepositionLog.SEA_LOBE) lobeShore++
                    }
                }
            }
            if (shore > 0 && lobeShore == shore) {
                walledBodies++
                walledCells += cells
                // A moat: long and thin, and curved round a mouth.
                val xs = members.map { it % w }
                val ys = members.map { it / w }
                val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min()) + 1
                if (span >= 6 && cells.toDouble() / span <= 2.5) annulus++
            }
        }
        val bareLand = exactLand(bare.erosion.height.data, 0.62f)
        val nowLand = exactLand(world.erosion.height.data, 0.62f)
        var gained = 0
        for (i in 0 until w * h) if (nowLand[i] && !bareLand[i]) gained++
        return "E6 $tag whole world: $walledBodies lakes with nothing but lobe ground on their " +
            "shore ($walledCells cells), of which $annulus are thin moats; $gained cells of new land"
    }

    private fun exactLand(height: FloatArray, seaLevel: Float): BooleanArray {
        val sorted = height.copyOf()
        sorted.sort()
        val t = sorted[(sorted.size * seaLevel).toInt().coerceIn(0, sorted.size - 1)]
        return BooleanArray(height.size) { height[it] >= t }
    }

    private inline fun neighbours(w: Int, h: Int, cell: Int, action: (Int) -> Unit) {
        val x = cell % w
        val y = cell / w
        for (dy in -1..1) {
            val ny = y + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                var nx = (x + dx) % w
                if (nx < 0) nx += w
                action(ny * w + nx)
            }
        }
    }
}
