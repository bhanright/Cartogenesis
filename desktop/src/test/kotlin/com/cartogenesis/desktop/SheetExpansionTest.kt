package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapPalette
import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.SheetGeometry
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.Biome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.extension.ExtendWith

/**
 * The raster's one colour a cell reaches the true-shape sheet by exact duplication, never by
 * interpolation.
 *
 * A categorical map is a promise that every colour on it means one thing: a biome, a realm. A sheet
 * made by blending a cell's colour with its neighbour's breaks that promise at every boundary, with
 * colours that are in no legend. So the finished bitmap of the biome view, which is its palette and
 * nothing else once the relief and the ink are off, is held to that palette pixel for pixel, and the
 * political view — whose realm colours are let down toward the relief, so its palette is whatever
 * the raster decided — is held to the raster's own colours, each pixel to its cell's. The control is
 * the same raster brought to the sheet by linear interpolation, which is what stretching a picture
 * does; it puts colours on the sheet that no cell has.
 *
 * And a grid whose cells are square on the ground is drawn a cell to a pixel by the same code: the
 * later grid of that shape needs nothing here to change.
 */
@ExtendWith(SharedWorldsCheck::class)
class SheetExpansionTest {

    private val world get() = SharedWorlds.world(WorldGenConfig(seed = 234475L, width = 512, height = 512))

    @Test
    fun `a categorical map carries no colour that is not in its palette after expansion`() {
        val map = world
        val geometry = SheetGeometry.of(map)
        val palette = Biome.entries.map { MapPalette.biome(it) and RGB }.toSet()
        val bare = RenderOptions(
            showRivers = false, showCoastline = false, showHillshade = false, showLakes = false,
            showLandmarks = false
        )

        val biomes = bare.copy(view = MapView.BIOMES)
        val biomeRaster = MapRasterizer.rasterize(map, biomes)
        val biomeSheet = pixels(MapImage.toBitmap(map, biomes, biomeRaster, MapSheet.UNGENERALISED))
        assertEquals(geometry.pixelCount, biomeSheet.size)
        val strays = biomeSheet.filter { it !in palette }.toSet()
        println("EXPANSION biome sheet ${geometry.widthPixels}x${geometry.heightPixels}: ${palette.size} palette colours, ${strays.size} others")
        assertTrue(strays.isEmpty(), "the biome sheet carries ${strays.size} colours no biome has")

        val political = bare.copy(view = MapView.POLITICAL)
        val politicalRaster = MapRasterizer.rasterize(map, political)
        val politicalSheet = pixels(MapImage.toBitmap(map, political, politicalRaster, MapSheet.UNGENERALISED))
        var wrong = 0
        for (sheetRow in 0 until geometry.heightPixels) {
            for (sheetColumn in 0 until geometry.widthPixels) {
                val cell = geometry.cellAt(sheetColumn + HALF, sheetRow + HALF)
                val expected = politicalRaster[cell] and RGB
                if (politicalSheet[sheetRow * geometry.widthPixels + sheetColumn] != expected) wrong++
            }
        }
        println("EXPANSION political sheet: $wrong of ${geometry.pixelCount} pixels are not their cell's colour")
        assertEquals(0, wrong, "the political sheet is not its raster copied out cell for cell")

        // The control: the biome raster brought to the sheet by interpolation along each row.
        val interpolated = linearlyStretched(biomeRaster.map { it and RGB }.toIntArray(), geometry)
        val invented = interpolated.filter { it !in palette }.toSet()
        println("EXPANSION CONTROL interpolated biome sheet: ${invented.size} colours no biome has")
        assertTrue(invented.isNotEmpty(), "interpolation invented no colour, so the guard cannot tell it from duplication")
    }

    @Test
    fun `a grid whose cells are square on the ground is drawn a cell to a pixel`() {
        val squareCells = SheetGeometry.of(WorldScale(), SQUARE_CELLS_ACROSS, SQUARE_CELLS_ACROSS / 2)
        assertEquals(1, squareCells.pixelsPerCellAcross)
        assertEquals(1, squareCells.pixelsPerCellDown)
        assertTrue(squareCells.isCellForPixel)
        val raster = IntArray(squareCells.cellsAcross * squareCells.cellsDown) { cell ->
            OPAQUE or ((cell * STRIDE_PRIME) and RGB)
        }
        val sheet = pixels(MapImage.sheetBitmap(squareCells, raster))
        assertEquals(raster.size, sheet.size)
        assertTrue(raster.indices.all { sheet[it] == raster[it] and RGB }, "a square cell was not one pixel")

        // And the geometry every size of the square grids the application offers gets: two pixels
        // across and one down, one scale both ways.
        listOf(512, 1024, 2048, 4096, 8192).forEach { side ->
            val grid = SheetGeometry.of(WorldScale(), side, side)
            assertEquals(2, grid.pixelsPerCellAcross)
            assertEquals(1, grid.pixelsPerCellDown)
            assertEquals(
                grid.kilometresPerPixel * grid.pixelsPerCellAcross,
                WorldScale().cellWidthKm(side), 1e-9
            )
            assertEquals(
                grid.kilometresPerPixel * grid.pixelsPerCellDown,
                WorldScale().cellHeightKm(side), 1e-9
            )
        }
    }

    /** Each row of [cells] carried onto the sheet by linear interpolation between cell centres. */
    private fun linearlyStretched(cells: IntArray, geometry: SheetGeometry): IntArray {
        val out = IntArray(geometry.pixelCount)
        val across = geometry.pixelsPerCellAcross.toFloat()
        for (sheetRow in 0 until geometry.heightPixels) {
            val row = geometry.rowAt(sheetRow + HALF)
            for (sheetColumn in 0 until geometry.widthPixels) {
                val position = (sheetColumn + HALF) / across - HALF
                val left = kotlin.math.floor(position).toInt()
                val share = position - left
                val a = cells[row * geometry.cellsAcross + left.mod(geometry.cellsAcross)]
                val b = cells[row * geometry.cellsAcross + (left + 1).mod(geometry.cellsAcross)]
                out[sheetRow * geometry.widthPixels + sheetColumn] = MapPalette.blend(a, b, share) and RGB
            }
        }
        return out
    }

    private fun pixels(bitmap: Bitmap): IntArray {
        val bytes = bitmap.readPixels() ?: error("could not read the sheet back")
        val out = IntArray(bitmap.width * bitmap.height)
        for (pixel in out.indices) {
            val at = pixel * BYTES_PER_PIXEL
            out[pixel] = (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16)
        }
        bitmap.close()
        return out
    }

    private companion object {
        const val HALF = 0.5f
        const val RGB = 0x00FFFFFF
        const val OPAQUE = 0xFF shl 24
        const val BYTES_PER_PIXEL = 4
        const val SQUARE_CELLS_ACROSS = 256

        /** Knuth's multiplicative hash constant, 0x9E3779B1: neighbouring cells, unrelated colours. */
        const val STRIDE_PRIME = -1_640_531_535
    }
}
