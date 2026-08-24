package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Not a correctness test — a visual harness. It renders generated worlds to PNGs under
 * `worldgen/build/maps/` so the pipeline's output can be eyeballed without an emulator.
 * The app has its own renderer; this one only has to be good enough to spot bad terrain.
 */
class DebugMapDump {

    private val outputDir = File("build/maps")

    @Test
    fun `dump sample worlds`() {
        outputDir.mkdirs()

        listOf(7L, 42L, 1234L).forEach { seed ->
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            write(render(world, Mode.FANTASY), "seed$seed-fantasy.png")
            println(
                "seed $seed: ${(world.landFraction() * 100).toInt()}% land, " +
                    "${world.rivers.rivers.size} rivers, " +
                    "${world.plates.plates.size} plates, " +
                    riverDiagnostics(world)
            )
        }

        // One world in every view, to check each stage independently.
        val world = WorldGenerationEngine.generateBlocking(WorldGenConfig(seed = 42L, width = 512, height = 512))
        write(render(world, Mode.ELEVATION), "seed42-elevation.png")
        write(render(world, Mode.PLATES), "seed42-plates.png")
        write(render(world, Mode.BIOME), "seed42-biome.png")
        write(render(world, Mode.RAINFALL), "seed42-rainfall.png")
        write(render(world, Mode.TEMPERATURE), "seed42-temperature.png")
        write(render(world, Mode.NORMALS), "seed42-normals.png")
        write(render(world, Mode.NATIONS), "seed42-nations.png")
        write(render(world, Mode.CULTURES), "seed42-cultures.png")
        write(render(world, Mode.HABITABILITY), "seed42-habitability.png")

        world.cultures.cultures.sortedByDescending { it.cellCount }.forEach {
            println("  ${it.name} - ${it.cellCount} cells, mostly ${it.dominantBiome}")
        }

        world.nations.nations.sortedByDescending { it.cellCount }.take(3).forEach {
            println("  ${it.name} - ${it.government}, cap. ${it.capitalName}")
            println(
                "     pop ${"%,d".format(it.population)}, heartland ${it.heartlandBiome}, " +
                    "area-biome ${it.dominantBiome}"
            )
            println("     exports: ${it.exports.joinToString()}")
            println("     imports: ${it.imports.joinToString()}")
            println("     ${it.lore}")
        }
        val claimed = world.nations.nationId.count { it >= 0 }
        println(
            "  realms=${world.nations.nations.size} claimed=" +
                "${claimed * 100 / world.sea.landCellCount.coerceAtLeast(1)}% of land"
        )

        world.landmarks.landmarks.take(10).forEach {
            println("  [${it.kind.label}] ${it.name} � ${it.detail} (wild=${it.inWilderness})")
        }
        println("  landmarks=${world.landmarks.landmarks.size}")

        assertTrue(outputDir.listFiles()!!.isNotEmpty())
        println("Maps written to ${outputDir.absolutePath}")
    }

    /**
     * Coastline detail is a trade-off between the smooth blurred plate base and the fractal noise
     * terrain. This renders the corners of that trade-off so it can be judged by eye.
     */
    @Test
    fun `sweep terrain roughness against tectonic influence`() {
        outputDir.mkdirs()
        val base = WorldGenConfig(seed = 42L, width = 512, height = 512)

        listOf(0.30f, 0.45f, 0.60f).forEach { weight ->
            listOf(0.92f, 1.0f).forEach { gain ->
                val config = base.copy(
                    terrain = base.terrain.copy(gain = gain),
                    tectonics = base.tectonics.copy(tectonicWeight = weight)
                )
                val world = WorldGenerationEngine.generateBlocking(config)
                write(render(world, Mode.FANTASY), "sweep-w${weight}-g$gain.png")
            }
        }
        println("Sweep written to ${outputDir.absolutePath}")
    }

    /** Both wilderness modes, side by side. */
    @Test
    fun `sweep wilderness mode`() {
        outputDir.mkdirs()
        val base = WorldGenConfig(seed = 42L, width = 512, height = 512)
        WildernessMode.entries.forEach { mode ->
            val world = WorldGenerationEngine.generateBlocking(
                base.copy(nations = base.nations.copy(wilderness = mode))
            )
            write(render(world, Mode.NATIONS), "nations-$mode.png")
            val claimed = world.nations.nationId.count { it >= 0 }
            println(
                "$mode realms=${world.nations.nations.size} " +
                    "claimed=${claimed * 100 / world.sea.landCellCount.coerceAtLeast(1)}%"
            )
        }
    }

    /** Prints the scale-consistency numbers used to pick thresholds for the resolution guard. */
    @Test
    fun `report resolution consistency metrics`() {
        listOf(128, 256, 512).forEach { size ->
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = 42L, width = 128, height = 128).atResolution(size, size)
            )
            val e = world.sea.relativeElevation
            var nearTotal = 0.0; var nearCount = 0
            var farTotal = 0.0; var farCount = 0
            var desert = 0; var land = 0
            val nearRadius = world.width * 0.03f

            for (y in 1 until world.height - 1) {
                for (x in 1 until world.width - 1) {
                    val i = y * world.width + x
                    if (!world.sea.isLand[i]) continue
                    land++
                    if (world.climate.biome[i] == Biome.DESERT) desert++
                    val dx = e.sample(x + 1, y) - e.sample(x - 1, y)
                    val dy = e.sample(x, y + 1) - e.sample(x, y - 1)
                    val slope = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()) * world.width
                    if (world.plates.boundaryDistance.data[i] < nearRadius) {
                        nearTotal += slope; nearCount++
                    } else {
                        farTotal += slope; farCount++
                    }
                }
            }
            val near = if (nearCount == 0) 0.0 else nearTotal / nearCount
            val far = if (farCount == 0) 0.0 else farTotal / farCount
            println(
                "size=%d land=%.3f nearSlope=%.2f farSlope=%.2f contrast=%.2f desert=%.3f"
                    .format(
                        size, world.landFraction(), near, far,
                        if (far == 0.0) 0.0 else near / far,
                        desert.toFloat() / land.coerceAtLeast(1)
                    )
            )
        }
    }

    private enum class Mode {
        FANTASY, ELEVATION, PLATES, BIOME, RAINFALL, TEMPERATURE, NORMALS, NATIONS, CULTURES,
        HABITABILITY
    }

    /** Mirrors RiverStage's threshold maths so the network can be inspected from outside. */
    private fun riverDiagnostics(world: WorldMap): String {
        val acc = world.rivers.flowAccumulation.data
        val land = world.sea.isLand
        var totalRunoff = 0f
        for (i in acc.indices) {
            if (land[i]) totalRunoff += 0.05f + world.climate.precipitation.data[i]
        }
        val threshold = (totalRunoff * world.config.rivers.sourceThreshold).coerceAtLeast(1e-4f)

        val channel = BooleanArray(acc.size) { land[it] && acc[it] >= threshold }
        val channelCount = channel.count { it }

        val hasUpstream = BooleanArray(acc.size)
        for (i in acc.indices) {
            if (!channel[i]) continue
            val t = world.rivers.flowTarget[i]
            if (t >= 0 && channel[t]) hasUpstream[t] = true
        }
        val sources = (acc.indices).count { channel[it] && !hasUpstream[it] }
        val traced = world.rivers.rivers.sumOf { it.length }

        // Degenerate drainage — every cell on a plain flowing the same way — shows up as a spike
        // in one compass direction. A healthy dendritic network is spread across all eight.
        val w = world.width
        val directions = IntArray(9)
        var routed = 0
        for (i in acc.indices) {
            if (!land[i]) continue
            val t = world.rivers.flowTarget[i]
            if (t < 0) { directions[8]++; continue }
            var dx = (t % w) - (i % w)
            if (dx > w / 2) dx -= w
            if (dx < -w / 2) dx += w
            val dy = (t / w) - (i / w)
            directions[(dy + 1) * 3 + (dx + 1)]++
            routed++
        }
        val worst = directions.take(8).max() * 100 / routed.coerceAtLeast(1)

        return "threshold=%.1f channels=%d sources=%d tracedCells=%d meanLen=%d topDir=%d%%"
            .format(
                threshold, channelCount, sources, traced,
                if (world.rivers.rivers.isEmpty()) 0 else traced / world.rivers.rivers.size,
                worst
            )
    }

    private fun write(image: BufferedImage, name: String) {
        ImageIO.write(image, "png", File(outputDir, name))
    }

    private fun render(world: WorldMap, mode: Mode): BufferedImage {
        val w = world.width
        val h = world.height
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val shade = hillshade(world)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val land = world.sea.isLand[i]
                val rel = world.sea.relativeElevation.data[i]

                // Standing fresh water sits on top of whatever the land would have been.
                if (land && world.rivers.lakes.isLake(i) &&
                    (mode == Mode.FANTASY || mode == Mode.ELEVATION)
                ) {
                    val depth = world.rivers.filledElevation.data[i] - rel
                    image.setRGB(x, y, mix(0x4E92B4, 0x2F6B8C, (depth * 12f).coerceIn(0f, 1f)))
                    continue
                }

                var rgb = when (mode) {
                    Mode.FANTASY ->
                        if (land) mix(landColor(rel), biomeColor(world.climate.biome[i]), 0.45f)
                        else oceanColor(-rel)

                    Mode.ELEVATION -> if (land) landColor(rel) else oceanColor(-rel)

                    Mode.PLATES -> {
                        val edge = (world.plates.boundaryDistance.data[i] / 12f).coerceIn(0f, 1f)
                        mix(0x202020, plateColor(world.plates.plateId[i]), edge)
                    }

                    Mode.BIOME -> biomeColor(world.climate.biome[i])

                    Mode.RAINFALL ->
                        if (land) grad(world.climate.precipitation.data[i], 0xE8D9A8, 0x1F4E79)
                        else 0x20303C

                    Mode.TEMPERATURE ->
                        grad(
                            ((world.climate.temperature.data[i] + 30f) / 70f).coerceIn(0f, 1f),
                            0x3B4CC0, 0xB40426
                        )

                    Mode.HABITABILITY ->
                        if (land) grad(world.nations.habitability.data[i], 0x5B3A29, 0x9BE564)
                        else 0x18262F

                    Mode.NATIONS -> {
                        val owner = world.nations.nationId[i]
                        when {
                            !land -> 0x16405F
                            owner < 0 -> 0x6E6A5E // unclaimed wilderness
                            else -> mix(nationColor(owner), landColor(rel), 0.35f)
                        }
                    }

                    Mode.CULTURES -> {
                        val people = world.cultures.cultureId[i]
                        when {
                            !land -> 0x16405F
                            people < 0 -> 0x6E6A5E // nobody lives here
                            else -> mix(cultureColor(people), landColor(rel), 0.35f)
                        }
                    }

                    Mode.NORMALS -> {
                        val n = world.terrain.normals.normalAt(x, y)
                        rgbOf(
                            ((n[0] * 0.5f + 0.5f) * 255).toInt(),
                            ((n[1] * 0.5f + 0.5f) * 255).toInt(),
                            ((n[2] * 0.5f + 0.5f) * 255).toInt()
                        )
                    }
                }

                if (land && (mode == Mode.FANTASY || mode == Mode.ELEVATION)) {
                    rgb = shadeBy(rgb, shade[i])
                }
                image.setRGB(x, y, rgb)
            }
        }

        if (mode == Mode.FANTASY || mode == Mode.ELEVATION) drawRivers(world, image)
        return image
    }

    private fun drawRivers(world: WorldMap, image: BufferedImage) {
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x3C7EA8)
        val w = world.width

        world.rivers.rivers.forEach { river ->
            for (k in 0 until river.cells.size - 1) {
                val from = river.cells[k]
                val to = river.cells[k + 1]
                val x0 = from % w
                val x1 = to % w
                if (abs(x1 - x0) > w / 2) continue
                g.stroke = BasicStroke(river.widths[k].coerceAtLeast(0.9f))
                g.drawLine(x0, from / w, x1, to / w)
            }
        }
        g.dispose()
    }

    private fun hillshade(world: WorldMap): FloatArray {
        val w = world.width
        val h = world.height
        val e = world.sea.relativeElevation
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (e.sample(x + 1, y) - e.sample(x - 1, y)) * 12f
                val dy = (e.sample(x, y + 1) - e.sample(x, y - 1)) * 12f
                val len = sqrt(dx * dx + dy * dy + 1f)
                val dot = (dx * 0.6f + dy * 0.6f + 0.53f) / len
                out[y * w + x] = (0.72f + 0.55f * dot).coerceIn(0.45f, 1.35f)
            }
        }
        return out
    }

    private fun landColor(elevation: Float): Int = ramp(
        elevation,
        intArrayOf(0x9DBE7A, 0x8AAE63, 0xB9C070, 0xC8B072, 0xA98A63, 0x8A6F58, 0x7C6656, 0xEDEDE8)
    )

    private fun oceanColor(depth: Float): Int = ramp(
        1f - depth.coerceIn(0f, 1f),
        intArrayOf(0x0B2239, 0x11395B, 0x1B5479, 0x2B7398, 0x57A5C4)
    )

    private fun biomeColor(biome: Biome): Int = when (biome) {
        Biome.OCEAN -> 0x16405F
        Biome.SHALLOW_OCEAN -> 0x3C82A8
        Biome.ICE_SHEET -> 0xEFF4F7
        Biome.TUNDRA -> 0xB5BBA6
        Biome.TAIGA -> 0x5C7A5C
        Biome.TEMPERATE_FOREST -> 0x4F7B45
        Biome.TEMPERATE_RAINFOREST -> 0x2F5F3C
        Biome.GRASSLAND -> 0xB3BF6E
        Biome.SHRUBLAND -> 0x9BA86A
        Biome.DESERT -> 0xDCC493
        Biome.SAVANNA -> 0xC6B95F
        Biome.TROPICAL_SEASONAL_FOREST -> 0x5E8F3E
        Biome.TROPICAL_RAINFOREST -> 0x2C6B33
        Biome.ALPINE -> 0xA9A29B
    }

    private fun nationColor(id: Int): Int {
        val hue = (id * 47.5f + 15f) % 360f
        return Color.HSBtoRGB(hue / 360f, 0.55f, 0.9f) and 0xFFFFFF
    }

    /** Distinct from [nationColor], so the two layers cannot be confused at a glance. */
    private fun cultureColor(id: Int): Int {
        val hue = (id * 73.5f + 200f) % 360f
        return hsvToRgbInt(hue, 0.42f, if (id % 2 == 0) 0.85f else 0.7f)
    }

    private fun hsvToRgbInt(hue: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return rgbOf(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }

    private fun plateColor(id: Int): Int {
        val hue = (id * 137.508f) % 360f
        return Color.HSBtoRGB(hue / 360f, 0.45f, 0.85f) and 0xFFFFFF
    }

    private fun ramp(t: Float, colors: IntArray): Int {
        val scaled = t.coerceIn(0f, 1f) * (colors.size - 1)
        val index = scaled.toInt().coerceAtMost(colors.size - 2)
        return mix(colors[index], colors[index + 1], scaled - index)
    }

    private fun grad(t: Float, from: Int, to: Int): Int = mix(from, to, t.coerceIn(0f, 1f))

    private fun mix(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return rgbOf(
            (((a shr 16) and 0xFF) + ((((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * f)).toInt(),
            (((a shr 8) and 0xFF) + ((((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * f)).toInt(),
            ((a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * f)).toInt()
        )
    }

    private fun shadeBy(rgb: Int, factor: Float): Int = rgbOf(
        (((rgb shr 16) and 0xFF) * factor).toInt(),
        (((rgb shr 8) and 0xFF) * factor).toInt(),
        ((rgb and 0xFF) * factor).toInt()
    )

    private fun rgbOf(r: Int, g: Int, b: Int): Int =
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
