package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect

/**
 * W2's renders: the author's two worlds at 2048 in the Winds, Rainfall and Fantasy views, with the
 * pressure term off and on, plus a crop of the largest continent's interior in Rainfall.
 *
 * A harness, not a guard, and in the audit tier for the reason every render harness is: four
 * worlds at 2048 is several minutes of erosion before a pixel is drawn.
 *
 * "Before" here is `ClimateConfig.pressureWinds` off rather than a checkout of the commit before
 * this chunk, because the two are the same world — the control is exactly the belt wind the
 * generator had — and running both from one tree means the two pictures are guaranteed to be of
 * the same ground, with the same erosion and the same coastline, rather than merely believed to be.
 */
class PressureWindRenderTest {

    private companion object {
        val seeds = listOf(718106L, 59758L)
        const val size = 2048
        const val cropWidth = 900
        const val cropHeight = 700
    }

    @Test
    fun `both worlds at 2048, in three views, with the pressure term off and on`() {
        val root = File("build/w2-crops")
        var written = 0
        seeds.forEach { seed ->
            listOf("before" to false, "after" to true).forEach { (label, pressureWinds) ->
                val dir = File(root, label).apply { mkdirs() }
                val base = WorldGenConfig(seed = seed, width = size, height = size)
                val started = System.currentTimeMillis()
                val world = WorldGenerationEngine.generateBlocking(
                    base.copy(climate = base.climate.copy(pressureWinds = pressureWinds))
                )
                println(
                    "W2 RENDER $label seed $seed at $size in " +
                        "${(System.currentTimeMillis() - started) / 1000}s, " +
                        "${(world.landFraction() * 100).toInt()}% land"
                )
                val interior = interiorWindow(world)
                listOf(MapView.WIND, MapView.RAINFALL, MapView.FANTASY).forEach { view ->
                    val bitmap = MapImage.toBitmap(
                        world, RenderOptions(view = view, style = MapStyle.ATLAS)
                    )
                    val whole = Image.makeFromBitmap(bitmap)
                    val name = view.name.lowercase()
                    write(File(dir, "$seed-$name-$label.png"), whole)
                    written++
                    if (view == MapView.RAINFALL) {
                        write(
                            File(dir, "$seed-$name-interior-$label.png"),
                            crop(whole, interior.first, interior.second)
                        )
                        written++
                    }
                    whole.close()
                    bitmap.close()
                }
            }
        }
        println("W2 RENDER wrote $written pictures under ${root.absolutePath}")
        assertTrue(written >= 16, "only $written pictures were written to ${root.absolutePath}")
    }

    /**
     * The top-left corner of a window over the land cell furthest from any sea.
     *
     * Chosen from the world rather than typed in, so the before and after crops frame the same
     * ground — the erosion and the coastline are identical between the two runs, so the deepest
     * interior cell is too.
     */
    private fun interiorWindow(world: WorldMap): Pair<Int, Int> {
        // A breadth-first sweep out from every coast at once, counting cells rather than
        // kilometres: this only has to pick a window, and the stage's own distance field is
        // internal to the generator. Four-connected, wrapping east to west, and visited in index
        // order, so the same world gives the same window every time.
        val cellsAcross = world.width
        val cellsDown = world.height
        val distance = IntArray(cellsAcross * cellsDown) { -1 }
        val queue = ArrayDeque<Int>()
        for (cell in 0 until cellsAcross * cellsDown) {
            if (!world.sea.isLand[cell]) {
                distance[cell] = 0
                queue.addLast(cell)
            }
        }
        var deepest = 0
        var deepestDistance = 0
        while (queue.isNotEmpty()) {
            val cell = queue.removeFirst()
            if (distance[cell] > deepestDistance) {
                deepestDistance = distance[cell]
                deepest = cell
            }
            val row = cell / cellsAcross
            val column = cell % cellsAcross
            val neighbours = intArrayOf(
                row * cellsAcross + if (column + 1 == cellsAcross) 0 else column + 1,
                row * cellsAcross + if (column == 0) cellsAcross - 1 else column - 1,
                if (row > 0) (row - 1) * cellsAcross + column else cell,
                if (row + 1 < cellsDown) (row + 1) * cellsAcross + column else cell
            )
            neighbours.forEach { neighbour ->
                if (distance[neighbour] < 0) {
                    distance[neighbour] = distance[cell] + 1
                    queue.addLast(neighbour)
                }
            }
        }
        println("W2 RENDER interior crop at cell $deepest, $deepestDistance cells from water")
        return Pair(
            (deepest % world.width - cropWidth / 2).coerceIn(0, world.width - cropWidth),
            (deepest / world.width - cropHeight / 2).coerceIn(0, world.height - cropHeight)
        )
    }

    private fun write(file: File, image: Image) {
        file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    /** The detail at [left], [top], at the render's own pixels — no scaling anywhere. */
    private fun crop(whole: Image, left: Int, top: Int): Image {
        val bitmap = Bitmap().apply {
            allocPixels(ImageInfo.makeS32(cropWidth, cropHeight, ColorAlphaType.PREMUL))
        }
        Canvas(bitmap).drawImageRect(
            whole,
            Rect.makeXYWH(
                left.toFloat(), top.toFloat(), cropWidth.toFloat(), cropHeight.toFloat()
            ),
            Rect.makeWH(cropWidth.toFloat(), cropHeight.toFloat())
        )
        val image = Image.makeFromBitmap(bitmap)
        bitmap.close()
        return image
    }
}
