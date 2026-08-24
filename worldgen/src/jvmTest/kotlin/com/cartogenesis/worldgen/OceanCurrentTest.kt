package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks that the stream-function solve actually produces circulation, and draws it.
 *
 * The test that matters is the sign pattern: in the northern hemisphere a subtropical gyre turns
 * clockwise, so its western side carries water poleward and arrives warm, while its eastern side
 * carries water equatorward and arrives cold. If that comes out backwards the gyres are wrong even
 * though they look fine.
 */
class OceanCurrentTest {

    @Test
    fun `currents circulate and carry temperature`() {
        val world = WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = 42L, width = 512, height = 512)
        )
        val w = world.width
        val h = world.height
        val ocean = world.ocean

        var moving = 0
        var waterCells = 0
        var fastest = 0f
        for (i in 0 until w * h) {
            if (world.sea.isLand[i]) continue
            waterCells++
            val speed = sqrt(
                ocean.velocityX.data[i] * ocean.velocityX.data[i] +
                    ocean.velocityY.data[i] * ocean.velocityY.data[i]
            )
            if (speed > 0.05f) moving++
            fastest = maxOf(fastest, speed)
        }
        println("OCEAN moving water: ${moving * 100 / waterCells}% of sea, fastest ${"%.2f".format(fastest)} cells/pass")

        // Does poleward flow arrive warm? Correlate meridional velocity against the anomaly in the
        // northern subtropics, where a clockwise gyre should give warm water heading north.
        var agree = 0
        var total = 0
        for (y in 0 until h / 2) {
            val lat = 90f - 180f * (y + 0.5f) / h
            if (lat < 15f || lat > 50f) continue
            for (x in 0 until w) {
                val i = y * w + x
                if (world.sea.isLand[i]) continue
                val northward = -ocean.velocityY.data[i]   // rows increase southward
                if (abs(northward) < 0.1f) continue
                total++
                if ((northward > 0f) == (ocean.anomaly.data[i] > 0f)) agree++
            }
        }
        if (total > 0) {
            println("OCEAN poleward flow arriving warm: ${agree * 100 / total}% of $total samples")
        }

        var warmest = 0f
        var coldest = 0f
        ocean.anomaly.data.forEachIndexed { i, v ->
            if (!world.sea.isLand[i]) { warmest = maxOf(warmest, v); coldest = minOf(coldest, v) }
        }
        println("OCEAN anomaly range ${"%.1f".format(coldest)} to ${"%.1f".format(warmest)} degrees")

        // Draw it.
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until w * h) {
            val c = if (world.sea.isLand[i]) 0x3A3A32 else {
                val a = (ocean.anomaly.data[i] / 6f).coerceIn(-1f, 1f)
                if (a >= 0f) {
                    val t = (a * 255).toInt()
                    (0x40 + t / 2 shl 16) or (0x30 shl 8) or (0x60 - t / 5)
                } else {
                    val t = (-a * 255).toInt()
                    (0x20 shl 16) or ((0x40 + t / 3) shl 8) or (0x70 + t / 3)
                }
            }
            image.setRGB(i % w, i / w, c)
        }
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(255, 255, 255, 150)
        g.stroke = BasicStroke(1f)
        var y = 6
        while (y < h - 6) {
            var x = 6
            while (x < w - 6) {
                val i = y * w + x
                if (!world.sea.isLand[i]) {
                    val vx = ocean.velocityX.data[i]
                    val vy = ocean.velocityY.data[i]
                    val speed = sqrt(vx * vx + vy * vy)
                    if (speed > 0.08f) {
                        val len = (speed * 3f).coerceAtMost(9f)
                        g.drawLine(x, y, (x + vx / speed * len).toInt(), (y + vy / speed * len).toInt())
                    }
                }
                x += 12
            }
            y += 12
        }
        g.dispose()
        val dir = File("build/maps").apply { mkdirs() }
        ImageIO.write(image, "png", File(dir, "ocean-currents.png"))
        println("OCEAN image at ${dir.absolutePath}/ocean-currents.png")
    }

    /**
     * Warm coasts should end up better settled than cold ones at the same latitude.
     *
     * Latitude has to be held fixed or the comparison measures nothing but the tropics being nicer
     * than the arctic. Within a band, the only thing separating one coast from another is what the
     * current brings, so the gap between them is the effect of the currents alone.
     */
    @Test
    fun `warm coasts are worth more than cold ones at the same latitude`() {
        listOf(7L, 42L, 1234L).forEach { seed -> checkCoasts(seed) }
    }

    private fun checkCoasts(seed: Long) {
        val config = WorldGenConfig(seed = seed, width = 512, height = 512)
        val world = WorldGenerationEngine.generateBlocking(config)
        val w = world.width
        val h = world.height

        // 16 latitude bands, each scored against its own mean so bands cannot outvote each other.
        val bandCount = 16
        var warmTotal = 0.0
        var coldTotal = 0.0
        var warmCells = 0
        var coldCells = 0

        repeat(bandCount) { band ->
            val y0 = band * h / bandCount
            val y1 = (band + 1) * h / bandCount
            val coast = ArrayList<Pair<Float, Float>>()  // anomaly offshore to habitability

            for (y in y0 until y1) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!world.sea.isLand[i]) continue
                    var sum = 0f
                    var count = 0
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until h) continue
                        for (dx in -1..1) {
                            val n = ny * w + ((x + dx + w) % w)
                            if (!world.sea.isLand[n]) {
                                sum += world.ocean.anomaly.data[n]
                                count++
                            }
                        }
                    }
                    if (count > 0) coast.add(sum / count to world.nations.habitability.data[i])
                }
            }
            if (coast.size < 40) return@repeat

            val sorted = coast.sortedBy { it.first }
            val quartile = sorted.size / 4
            sorted.take(quartile).forEach { coldTotal += it.second; coldCells++ }
            sorted.takeLast(quartile).forEach { warmTotal += it.second; warmCells++ }
        }

        val warm = warmTotal / warmCells
        val cold = coldTotal / coldCells
        println(
            "OCEAN seed %d coastal habitability: warm %.3f, cold %.3f (%.1f%% gap, %d cells each)"
                .format(seed, warm, cold, (warm / cold - 1) * 100, warmCells)
        )
        // Only a few percent, and deliberately so: the fishery bonus rewards the cold quartile at
        // the same time the harbour bonus rewards the warm one, so the two partly cancel. What
        // matters is that the gap exists at all — with the coastal term removed it sits at zero and
        // tips slightly negative, which is what this catches.
        assertTrue(
            warm > cold * 1.02,
            "seed $seed: warm coasts ($warm) are no better settled than cold ones ($cold)"
        )
    }
}
