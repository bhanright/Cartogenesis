package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.SeaChannels
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The narrowest sea the generator traces rivers down ([SeaChannels]) is the narrowest sea the
 * drawing inks a coast round: one figure, the coast's pen, in two modules.
 *
 * The generator holds the rule because the tracer needs it and the drawing reads the generator;
 * the pen is the drawing's. If either moves alone, a river is traced to water the map draws
 * without a coast, or the coast is inked over water a river was carried through, and the line
 * stops short of the sea again (docs/DESIGN_LEDGER.md, RM1).
 */
class NarrowSeaTest {

    @Test
    fun `the narrowest drawn sea is the coast's pen at every sheet width`() {
        for (sheetWidthPixels in listOf(256, 512, 1024, 2048, 3000, 4096, 8192, 16384)) {
            assertEquals(
                MapRasterizer.coastPenPixels(sheetWidthPixels),
                SeaChannels.narrowestDrawnSeaPixels(sheetWidthPixels),
                "a sheet $sheetWidthPixels pixels wide"
            )
        }
    }

    @Test
    fun `the open block is the pen measured on the sheet, on every grid and planet`() {
        val earth = WorldGenConfig(seed = 1L).scale
        val planets = listOf(earth, earth.copy(worldWidthKm = earth.worldWidthKm / 3), earth.copy(worldWidthKm = earth.worldWidthKm * 2.5))
        for (scale in planets) {
            for (rows in listOf(128, 256, 512, 1024, 2048, 4096)) {
                for (columnsPerRow in listOf(1, 2, 4)) {
                    val config = WorldGenConfig(seed = 1L, width = columnsPerRow * rows, height = rows, scale = scale)
                    val sheet = SheetGeometry.of(config)
                    val pen = MapRasterizer.coastPenPixels(sheet.widthPixels)
                    val block = SeaChannels.openBlock(config)
                    val where = "${config.width} by ${config.height} cells over ${scale.worldWidthKm} km"
                    assertEquals(NarrowSea.cellsWiderThanPen(pen, sheet.pixelsPerCellAcross), block.columns, where)
                    assertEquals(NarrowSea.cellsWiderThanPen(pen, sheet.pixelsPerCellDown), block.rows, where)
                }
            }
        }
    }

    @Test
    fun `one cell's answer is the whole mask's`() {
        val random = Random(20261013)
        for (trial in 0 until 40) {
            val cellsAcross = 24
            val cellsDown = 16
            val isLand = BooleanArray(cellsAcross * cellsDown) { random.nextFloat() < 0.35f }
            val columns = 1 + trial % 3
            val rows = 1 + (trial / 3) % 3
            val mask = SeaChannels.mask(isLand, cellsAcross, columns, rows)
            for (cell in isLand.indices) {
                assertEquals(
                    mask[cell], SeaChannels.isNarrow(isLand, cellsAcross, cell, columns, rows),
                    "cell $cell of trial $trial, block $columns by $rows"
                )
            }
        }
    }
}
