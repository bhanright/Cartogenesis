package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.Test

/**
 * W1's renders: the author's two worlds at 2048 in both temperature views and the biome view,
 * whole and in three crops apiece at the render's own pixels — the poles, a continental interior
 * and a subtropical coast.
 *
 * A harness, not a guard. It is written so that it compiles against the code either side of W1,
 * so the same three crops can be taken before and after and put side by side; the crop windows are
 * chosen from the world rather than typed in, so they land on the same ground both times.
 *
 * Set `W1_RENDER_LABEL` to `before` or `after` to say which run this is. Excluded from the
 * per-merge tier by name, like the rest of the render harness.
 */
class W1RenderDump {

    private val label: String = System.getenv("W1_RENDER_LABEL") ?: "after"
    private val outputDir = File("../desktop/build/w1-crops")

    @Test
    fun `dump the author's worlds at 2048`() {
        outputDir.mkdirs()
        listOf(718106L, 59758L).forEach { seed ->
            val started = System.currentTimeMillis()
            val world = WorldGenerationEngine.generateBlocking(
                authorsConfig(seed).atResolution(2048, 2048)
            )
            println(
                "W1 RENDER seed $seed at 2048 in ${(System.currentTimeMillis() - started) / 1000}s, " +
                    "${(world.landFraction() * 100).toInt()}% land"
            )
            reportZonalProfile(seed, world)

            val views = listOf(
                "temperature-summer" to temperatureImage(world, world.climate.summerTemperature.data),
                "temperature-winter" to temperatureImage(world, world.climate.winterTemperature.data),
                "biome" to biomeImage(world)
            )
            views.forEach { (view, image) ->
                write(halved(image), "$seed-$view-$label.png")
            }

            val crops = cropWindows(world)
            crops.forEach { (name, window) ->
                views.forEach { (view, image) ->
                    write(image.getSubimage(window.x, window.y, window.width, window.height),
                        "$seed-$view-$name-$label.png")
                }
            }
        }
    }

    /**
     * The zonal-mean annual temperature over land and over sea, every ten degrees, with the land's
     * mean elevation and what the lapse rate takes off it.
     *
     * The elevation is there because without it the land column cannot be compared with anything.
     * The model gives each latitude a sea-level land temperature and the stage then subtracts
     * `metres / 1000 x lapseRate` cell by cell, so a world whose land stands high reads cold on the
     * map for a reason that has nothing to do with the model. Printing both means the report can
     * say which of the two a difference is.
     */
    private fun reportZonalProfile(seed: Long, world: WorldMap) {
        val cellsAcross = world.width
        val cellsDown = world.height
        val lapseRateCPerKm = world.config.climate.lapseRateCPerKm
        for (band in -8..8) {
            val centre = band * 10f
            var landSum = 0.0
            var landCells = 0
            var seaSum = 0.0
            var seaCells = 0
            var summerLand = 0.0
            var winterLand = 0.0
            var landMetres = 0.0
            for (row in 0 until cellsDown) {
                val latitude = ClimateStage.latitudeOf(row, cellsDown)
                if (abs(latitude - centre) > 5f) continue
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    if (world.sea.isLand[cell]) {
                        landSum += world.climate.temperature.data[cell]
                        summerLand += world.climate.summerTemperature.data[cell]
                        winterLand += world.climate.winterTemperature.data[cell]
                        landMetres += world.config.scale
                            .metresAboveShoreline(world.sea.relativeElevation.data[cell])
                        landCells++
                    } else {
                        seaSum += world.climate.temperature.data[cell]
                        seaCells++
                    }
                }
            }
            val meanMetres = if (landCells == 0) Double.NaN else landMetres / landCells
            val lapseC = meanMetres / 1000.0 * lapseRateCPerKm
            println(
                ("W1 PROFILE %s seed %d %+4.0f deg: land %.1f C (summer %.1f, winter %.1f) over " +
                    "%d cells standing %.0f m up, so %.1f C of lapse and %.1f C at sea level; " +
                    "sea %.1f C over %d cells").format(
                    label, seed, centre,
                    if (landCells == 0) Double.NaN else landSum / landCells,
                    if (landCells == 0) Double.NaN else summerLand / landCells,
                    if (landCells == 0) Double.NaN else winterLand / landCells,
                    landCells, meanMetres, lapseC,
                    if (landCells == 0) Double.NaN else landSum / landCells + lapseC,
                    if (seaCells == 0) Double.NaN else seaSum / seaCells,
                    seaCells
                )
            )
        }
    }

    private class Window(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * The three windows to crop, chosen from the world so that the before and after runs land on
     * the same ground: the north polar strip, the land cell furthest from any sea, and the
     * west-facing coast nearest 30 degrees.
     */
    private fun cropWindows(world: WorldMap): List<Pair<String, Window>> {
        val cellsAcross = world.width
        val cellsDown = world.height
        val side = 640

        val distance = ClimateStage.waterDistance(world.config, world.sea)
        var deepest = -1
        var deepestDistance = -1f
        var coast = -1
        var coastLatitudeMiss = Float.MAX_VALUE
        for (row in 0 until cellsDown) {
            val latitude = ClimateStage.latitudeOf(row, cellsDown)
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!world.sea.isLand[cell]) continue
                if (distance.data[cell] > deepestDistance) {
                    deepestDistance = distance.data[cell]
                    deepest = cell
                }
                val westward = row * cellsAcross + ((column - 1 + cellsAcross) % cellsAcross)
                if (world.sea.isLand[westward]) continue
                val miss = abs(abs(latitude) - 30f)
                if (miss < coastLatitudeMiss) {
                    coastLatitudeMiss = miss
                    coast = cell
                }
            }
        }

        fun windowAround(cell: Int): Window {
            val x = ((cell % cellsAcross) - side / 2).coerceIn(0, cellsAcross - side)
            val y = ((cell / cellsAcross) - side / 2).coerceIn(0, cellsDown - side)
            return Window(x, y, side, side)
        }

        println(
            "W1 CROPS $label: interior at cell $deepest (${deepestDistance.toInt()} cells from water), " +
                "subtropical west coast at cell $coast"
        )
        return listOf(
            "pole" to Window(0, 0, cellsAcross, side / 2),
            "interior" to windowAround(deepest),
            "coast" to windowAround(coast)
        )
    }

    /** Blue through green to red over -40..40 C, with land drawn darker than sea. */
    private fun temperatureImage(world: WorldMap, field: FloatArray): BufferedImage {
        val image = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_RGB)
        for (cell in field.indices) {
            val fraction = ((field[cell] + 40f) / 80f).coerceIn(0f, 1f)
            val red = (255 * fraction).toInt()
            val blue = (255 * (1f - fraction)).toInt()
            val green = (255 * (1f - abs(fraction - 0.5f) * 2f)).toInt()
            val shade = if (world.sea.isLand[cell]) 1.0f else 0.72f
            image.setRGB(
                cell % world.width, cell / world.width,
                ((red * shade).toInt() shl 16) or ((green * shade).toInt() shl 8) or
                    (blue * shade).toInt()
            )
        }
        return image
    }

    private fun biomeImage(world: WorldMap): BufferedImage {
        val image = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_RGB)
        for (cell in world.climate.biome.indices) {
            image.setRGB(cell % world.width, cell / world.width, colourOf(world.climate.biome[cell]))
        }
        return image
    }

    private fun colourOf(biome: Biome): Int = when (biome) {
        Biome.OCEAN -> 0x1B3B6F
        Biome.SHALLOW_OCEAN -> 0x2E6DA4
        Biome.ICE_SHEET -> 0xEFF4F7
        Biome.TUNDRA -> 0x9DB3A8
        Biome.TAIGA -> 0x3F6B4A
        Biome.TEMPERATE_FOREST -> 0x4C8C3F
        Biome.TEMPERATE_RAINFOREST -> 0x2F6B33
        Biome.GRASSLAND -> 0xA8B863
        Biome.SHRUBLAND -> 0xB8A35C
        Biome.DESERT -> 0xD9C48A
        Biome.SAVANNA -> 0xC6B24E
        Biome.TROPICAL_SEASONAL_FOREST -> 0x4F9B3A
        Biome.TROPICAL_RAINFOREST -> 0x1F6B2B
        Biome.ALPINE -> 0x8C8C8C
        Biome.MEDITERRANEAN -> 0xC08A4A
        Biome.MONSOON_FOREST -> 0x3E8B57
    }

    /** Half size, so a whole 2048 world fits on a screen; the crops stay at native pixels. */
    private fun halved(image: BufferedImage): BufferedImage {
        val small = BufferedImage(image.width / 2, image.height / 2, BufferedImage.TYPE_INT_RGB)
        val graphics = small.createGraphics()
        graphics.drawImage(image, 0, 0, small.width, small.height, null)
        graphics.dispose()
        return small
    }

    private fun write(image: BufferedImage, name: String) {
        val file = File(outputDir, name)
        ImageIO.write(image, "png", file)
        println("W1 RENDER wrote ${file.absolutePath}")
    }
}
