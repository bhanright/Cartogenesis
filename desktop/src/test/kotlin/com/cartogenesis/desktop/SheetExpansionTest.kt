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
 * A world of square cells is drawn a cell to a pixel, where the expansion is the raster itself and
 * interpolation has nothing between two pixels to invent. So the world's own sheet is held to its
 * palette, and the same raster is copied onto a sheet whose cells are two pixels across, the branch
 * of [SheetGeometry] a grid as many cells tall as wide takes, and held there too: the control has
 * its teeth on that sheet.
 */
@ExtendWith(SharedWorldsCheck::class)
class SheetExpansionTest {

    private val world get() = SharedWorlds.world(WorldGenConfig.forRows(seed = 234475L, rows = 512))

    @Test
    fun `a categorical map carries no colour that is not in its palette after expansion`() {
        val map = world
        val ownSheet = SheetGeometry.of(map)
        assertTrue(ownSheet.isCellForPixel, "a world of square cells is not drawn a cell to a pixel")
        // The same cells, two pixels across and one down: the branch a grid as many cells tall as
        // wide takes, on the same raster.
        val twoPixelSheet = SheetGeometry(map.width, map.height, 2, 1, ownSheet.kilometresPerPixel / 2)
        val palette = Biome.entries.map { MapPalette.biome(it) and RGB }.toSet()
        val bare = RenderOptions(
            showRivers = false, showCoastline = false, showHillshade = false, showLakes = false,
            showLandmarks = false
        )

        val biomes = bare.copy(view = MapView.BIOMES)
        val biomeRaster = MapRasterizer.rasterize(map, biomes)
        val political = bare.copy(view = MapView.POLITICAL)
        val politicalRaster = MapRasterizer.rasterize(map, political)
        for ((name, geometry, biomeSheet, politicalSheet) in listOf(
            Sheets(
                "the world's own", ownSheet,
                pixels(MapImage.toBitmap(map, biomes, biomeRaster, MapSheet.UNGENERALISED)),
                pixels(MapImage.toBitmap(map, political, politicalRaster, MapSheet.UNGENERALISED))
            ),
            Sheets(
                "two pixels a cell", twoPixelSheet,
                pixels(MapImage.sheetBitmap(twoPixelSheet, biomeRaster)),
                pixels(MapImage.sheetBitmap(twoPixelSheet, politicalRaster))
            )
        )) {
            assertEquals(geometry.pixelCount, biomeSheet.size)
            val strays = biomeSheet.filter { it !in palette }.toSet()
            println("EXPANSION $name biome sheet ${geometry.widthPixels}x${geometry.heightPixels}: ${palette.size} palette colors, ${strays.size} others")
            assertTrue(strays.isEmpty(), "$name biome sheet carries ${strays.size} colors no biome has")

            var wrong = 0
            for (sheetRow in 0 until geometry.heightPixels) {
                for (sheetColumn in 0 until geometry.widthPixels) {
                    val cell = geometry.cellAt(sheetColumn + HALF, sheetRow + HALF)
                    val expected = politicalRaster[cell] and RGB
                    if (politicalSheet[sheetRow * geometry.widthPixels + sheetColumn] != expected) wrong++
                }
            }
            println("EXPANSION $name political sheet: $wrong of ${geometry.pixelCount} pixels are not their cell's color")
            assertEquals(0, wrong, "$name political sheet is not its raster copied out cell for cell")
        }

        // The control: the biome raster brought to the two-pixel sheet by interpolation along each
        // row. On the world's own sheet a cell is one pixel and there is nothing to interpolate.
        val interpolated = linearlyStretched(biomeRaster.map { it and RGB }.toIntArray(), twoPixelSheet)
        val invented = interpolated.filter { it !in palette }.toSet()
        println("EXPANSION CONTROL interpolated biome sheet: ${invented.size} colours no biome has")
        assertTrue(invented.isNotEmpty(), "interpolation invented no colour, so the guard cannot tell it from duplication")
    }

    /** One sheet's name, its geometry and the two views' pixels drawn on it. */
    private data class Sheets(
        val name: String,
        val geometry: SheetGeometry,
        val biomes: IntArray,
        val political: IntArray
    )

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

        // And the geometry every size of the ladder by rows gets, 256 to 8192 rows: a cell to a
        // pixel, one scale both ways; and a grid as many cells tall as wide, which the sheet still
        // draws, two pixels across and one down.
        listOf(256, 512, 1024, 2048, 4096, 8192).forEach { rows ->
            val grid = SheetGeometry.of(WorldScale(), 2 * rows, rows)
            assertEquals(1, grid.pixelsPerCellAcross)
            assertEquals(1, grid.pixelsPerCellDown)
            assertEquals(grid.kilometresPerPixel, WorldScale().cellWidthKm(2 * rows), 1e-9)
            assertEquals(grid.kilometresPerPixel, WorldScale().cellHeightKm(rows), 1e-9)
            val halfHeight = SheetGeometry.of(WorldScale(), rows, rows)
            assertEquals(2, halfHeight.pixelsPerCellAcross)
            assertEquals(1, halfHeight.pixelsPerCellDown)
            assertEquals(
                halfHeight.kilometresPerPixel * halfHeight.pixelsPerCellAcross,
                WorldScale().cellWidthKm(rows), 1e-9
            )
            assertEquals(
                halfHeight.kilometresPerPixel * halfHeight.pixelsPerCellDown,
                WorldScale().cellHeightKm(rows), 1e-9
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
