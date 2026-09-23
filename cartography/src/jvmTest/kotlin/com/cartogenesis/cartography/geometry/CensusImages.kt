package com.cartogenesis.cartography.geometry

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.model.WorldMap
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A picture of each finding, so a violation can be looked at and not only read: the map as the
 * atlas draws it, the offending layer's lines over it, and a ring round the worst component,
 * cropped to the ground round it. Written under the module's `build/geometry-census`, where the
 * census tests leave them; nothing reads them back.
 */
internal object CensusImages {

    /** How much ground round a finding is shown, in cells either way. */
    private const val HALF_WINDOW_CELLS = 96

    /** How many pixels a cell is drawn at. */
    private const val PIXELS_PER_CELL = 3

    fun write(world: WorldMap, raster: IntArray, worldName: String, layer: Layer, reading: LayerReading, directory: File) {
        val findings = Detector.entries.filter { reading.outcome(it) == Outcome.VIOLATION }
        if (findings.isEmpty()) return
        directory.mkdirs()
        val frame = GridFrame.of(world.config)
        for (detector in findings) {
            val (column, row) = where(reading, detector, frame)
            val left = (column - HALF_WINDOW_CELLS).coerceIn(0, maxOf(0, frame.cellsAcross - 2 * HALF_WINDOW_CELLS))
            val top = (row - HALF_WINDOW_CELLS).coerceIn(0, maxOf(0, frame.cellsDown - 2 * HALF_WINDOW_CELLS))
            val across = minOf(2 * HALF_WINDOW_CELLS, frame.cellsAcross)
            val down = minOf(2 * HALF_WINDOW_CELLS, frame.cellsDown)
            val image = BufferedImage(across * PIXELS_PER_CELL, down * PIXELS_PER_CELL, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until down) for (x in 0 until across) {
                val colour = raster[(top + y) * frame.cellsAcross + left + x]
                for (dy in 0 until PIXELS_PER_CELL) for (dx in 0 until PIXELS_PER_CELL) {
                    image.setRGB(x * PIXELS_PER_CELL + dx, y * PIXELS_PER_CELL + dy, colour)
                }
            }
            val pen = image.createGraphics()
            pen.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            pen.color = Color(220, 20, 60)
            pen.stroke = BasicStroke(1.2f)
            for (outline in layer.outlines) {
                for (vertex in 0 until outline.vertexCount - (if (outline.closed) 0 else 1)) {
                    val next = (vertex + 1) % outline.vertexCount
                    val x0 = (outline.xKm[vertex] / frame.cellWidthKm - left) * PIXELS_PER_CELL
                    val y0 = (outline.yKm[vertex] / frame.cellHeightKm - top) * PIXELS_PER_CELL
                    val x1 = (outline.xKm[next] / frame.cellWidthKm - left) * PIXELS_PER_CELL
                    val y1 = (outline.yKm[next] / frame.cellHeightKm - top) * PIXELS_PER_CELL
                    if (x0 < -10 && x1 < -10 || y0 < -10 && y1 < -10) continue
                    if (x0 > image.width + 10 && x1 > image.width + 10 || y0 > image.height + 10 && y1 > image.height + 10) continue
                    pen.drawLine(x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt())
                }
            }
            pen.color = Color(255, 215, 0)
            pen.stroke = BasicStroke(2.5f)
            val cx = (column - left) * PIXELS_PER_CELL
            val cy = (row - top) * PIXELS_PER_CELL
            pen.drawOval(cx - 30, cy - 30, 60, 60)
            pen.dispose()
            val name = "$worldName-${layer.name}-${detector.name}".replace(Regex("[^A-Za-z0-9@_-]+"), "_")
            ImageIO.write(image, "png", File(directory, "$name.png"))
        }
    }

    /** The map as the atlas draws it, one pixel a cell. */
    fun rasterOf(world: WorldMap): IntArray = MapRasterizer.rasterize(world, RenderOptions(view = MapView.FANTASY))

    /** Where the worst instance of a finding lies, in cells; the middle of the map for a finding of the whole layer. */
    private fun where(reading: LayerReading, detector: Detector, frame: GridFrame): Pair<Int, Int> {
        val worst = reading.verdict(detector).worst
        return if (worst == null || worst.row < 0) frame.cellsAcross / 2 to frame.cellsDown / 2 else worst.column to worst.row
    }
}
