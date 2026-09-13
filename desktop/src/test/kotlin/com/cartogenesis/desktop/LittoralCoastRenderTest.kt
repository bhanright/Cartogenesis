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
import org.jetbrains.skia.SamplingMode

/**
 * The two worlds F17 is judged on, before and after, with a low coast and a mountain coast cropped
 * out of each.
 *
 * There is no numeric test for "the coasts vary now". `LittoralCoastTest` in `:worldgen` measures
 * the pooled dimension and the spread; this writes the pictures the spread is supposed to be
 * visible in, and it picks the two crops by measuring rather than by anybody choosing a coordinate
 * that stops being right the day the generator changes: the window with the most shoreline and the
 * lowest ground behind it, and the window with the most shoreline and the highest. Both crops are
 * taken at the same place in both renders, so the pair can be laid side by side.
 *
 * 298405 at 1024 is the world William was looking at when he said every coastline was too jagged;
 * 718106 at 2048 on his own settings — 62% ocean, fourteen plates, twelve realms — is the world
 * the export-scale reviews use. In `:desktop` and in the audit tier because the renderer lives
 * here and because four worlds, one of them at 2048, is not a per-merge cost.
 */
class LittoralCoastRenderTest {

    /** The world William was looking at when he said every coastline was too jagged. */
    @Test
    fun `298405 at 1024, before and after the grading`() {
        val dir = File("build/f17-crops").apply { mkdirs() }
        val config = WorldGenConfig(seed = 298405L, width = 512, height = 512)
            .atResolution(1024, 1024)
        report(dir, pair(dir, "298405-1024", config, CROP_CELLS))
    }

    /** The author's own export-scale world: 62% ocean, fourteen plates, twelve realms. */
    @Test
    fun `718106 at 2048, before and after the grading`() {
        val dir = File("build/f17-crops").apply { mkdirs() }
        val base = WorldGenConfig(seed = 718106L, width = 512, height = 512, seaLevel = 0.62f)
        val config = base
            .copy(
                tectonics = base.tectonics.copy(plateCount = 14),
                nations = base.nations.copy(nationCount = 12)
            )
            .atResolution(2048, 2048)
        report(dir, pair(dir, "718106-2048", config, CROP_CELLS * 2))
    }

    private fun report(dir: File, written: List<String>) {
        println("F17 wrote ${written.size} files to ${dir.absolutePath}")
        written.forEach { println("F17 $it") }
        assertTrue(written.isNotEmpty())
    }

    /** The whole map and the two crops, for the graded world and for the control. */
    private fun pair(
        dir: File,
        name: String,
        config: WorldGenConfig,
        cropCells: Int
    ): List<String> {
        // Both coast passes off, which is the shoreline release 2.0.2 drew.
        val control = config.copy(
            sea = config.sea.copy(littoralGrading = false, drownedValleyFill = false)
        )
        val before = WorldGenerationEngine.generateBlocking(control)
        val after = WorldGenerationEngine.generateBlocking(config)

        // Both crops chosen on the ungraded world, so the after picture is the same ground.
        val lowCoast = coastWindow(before, cropCells, wantHighGround = false)
        val mountainCoast = coastWindow(before, cropCells, wantHighGround = true)
        val written = ArrayList<String>()
        listOf("before" to before, "after" to after).forEach { (label, world) ->
            val options = RenderOptions(
                view = MapView.FANTASY,
                style = MapStyle.ATLAS,
                showRivers = true
            )
            written += write(dir, "$name-$label", world, options)
            written += crop(dir, "$name-low-$label", world, options, lowCoast, cropCells, 1)
            written += crop(dir, "$name-mountain-$label", world, options, mountainCoast, cropCells, 1)
            // The same two squares blown up, because a hundred and twenty-eight pixels is the
            // right size for the map and the wrong size for a reviewer's eye. Nearest-neighbour,
            // so what is magnified is the cells and not a smoothing of them.
            written += crop(dir, "$name-low-$label-x4", world, options, lowCoast, cropCells, 4)
            written += crop(
                dir, "$name-mountain-$label-x4", world, options, mountainCoast, cropCells, 4
            )
        }
        return written
    }

    private fun write(dir: File, name: String, world: WorldMap, options: RenderOptions): String {
        val bitmap = MapImage.toBitmap(world, options)
        val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!
        val file = File(dir, "$name.png")
        file.writeBytes(data.bytes)
        bitmap.close()
        return file.absolutePath
    }

    /** A square of the render at its own pixels, taken at [corner] in the world's own cells. */
    private fun crop(
        dir: File,
        name: String,
        world: WorldMap,
        options: RenderOptions,
        corner: Pair<Int, Int>,
        side: Int,
        magnify: Int
    ): String {
        val bitmap = MapImage.toBitmap(world, options)
        val whole = Image.makeFromBitmap(bitmap)
        val span = side * magnify
        val out = Bitmap().apply {
            allocPixels(ImageInfo.makeS32(span, span, ColorAlphaType.PREMUL))
        }
        Canvas(out).drawImageRect(
            whole,
            Rect.makeXYWH(
                corner.first.toFloat(), corner.second.toFloat(), side.toFloat(), side.toFloat()
            ),
            Rect.makeWH(span.toFloat(), span.toFloat()),
            SamplingMode.DEFAULT,
            null,
            true
        )
        val data = Image.makeFromBitmap(out).encodeToData(EncodedImageFormat.PNG)!!
        val file = File(dir, "$name.png")
        file.writeBytes(data.bytes)
        out.close()
        whole.close()
        bitmap.close()
        return file.absolutePath
    }

    /**
     * The window of [side] cells holding the most coast with the lowest — or the highest — ground
     * behind it.
     *
     * Scored as the number of shoreline cells in the window that stand below — or above — the
     * postglacial rise, which is the same 120 m the grading's own criterion is bounded by. Counting
     * the shore cells themselves rather than all the land in the window is what keeps the two
     * answers apart: a window with a plain in one corner and a range in the other maximises both
     * scores at once if the land is counted, and on 718106 at 2048 it did.
     */
    private fun coastWindow(world: WorldMap, side: Int, wantHighGround: Boolean): Pair<Int, Int> {
        val step = side / 2
        var best = Pair(0, 0)
        var bestScore = -1
        var top = 0
        while (top + side <= world.height) {
            var left = 0
            while (left + side <= world.width) {
                var wanted = 0
                for (row in top until top + side) {
                    for (column in left until left + side) {
                        val cell = row * world.width + column
                        if (!world.sea.isLand[cell]) continue
                        if (!touchesWater(world, row, column)) continue
                        val high = world.sea.relativeElevation.data[cell] > POSTGLACIAL_RISE
                        if (high == wantHighGround) wanted++
                    }
                }
                if (wanted > bestScore) {
                    bestScore = wanted
                    best = Pair(left, top)
                }
                left += step
            }
            top += step
        }
        return best
    }

    private fun touchesWater(world: WorldMap, row: Int, column: Int): Boolean {
        val width = world.width
        if (!world.sea.isLand[row * width + (column + 1) % width]) return true
        if (!world.sea.isLand[row * width + (column + width - 1) % width]) return true
        if (row > 0 && !world.sea.isLand[(row - 1) * width + column]) return true
        if (row + 1 < world.height && !world.sea.isLand[(row + 1) * width + column]) return true
        return false
    }

    private companion object {
        /** Side of a crop, in the 1024 render's cells: an eighth of the sheet each way. */
        const val CROP_CELLS = 128

        /** 120 m against eight kilometres of relief; see `LittoralGrading.MAX_BACKSHORE_RISE`. */
        const val POSTGLACIAL_RISE = 0.015f
    }
}
