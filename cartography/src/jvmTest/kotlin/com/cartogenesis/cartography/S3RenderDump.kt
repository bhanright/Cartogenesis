package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * S3's pictures: a range and its rain shadow, cut by rain and cut by flat rain, so the difference
 * can be looked at rather than read off a ratio.
 *
 * Runs only when asked (`CARTOGENESIS_S3_RENDER=1`). Two of the five worlds are 2048 and each has
 * to be generated twice, which is minutes of work for a person to look at and nothing for a guard
 * to assert.
 */
class S3RenderDump {

    private companion object {
        val SEEDS = listOf(7L to 512, 42L to 512, 1234L to 512, 718106L to 2048, 59758L to 2048)

        /** Side of the crop, in cells of the world's own grid. */
        const val CROP_AT_512 = 256

        /** Cells a candidate window is stepped by while the shadow is looked for. */
        const val SEARCH_STRIDE = 32

        /** How high a cell must stand for the window to count it as range. */
        const val RANGE_METRES = 1000f

        /** And how much of a window must be range before its rainfall contrast is believed. */
        const val RANGE_SHARE = 0.15
    }

    private val outputDir = File("../desktop/build/s3-crops")

    @Test
    fun `dump five worlds cut by the rain and cut by flat rain`() {
        if (System.getenv("CARTOGENESIS_S3_RENDER") != "1") return
        outputDir.mkdirs()
        SEEDS.forEach { (seed, side) ->
            val after = generate(seed, side, climateFeed = true)
            val before = generate(seed, side, climateFeed = false)
            // The window is chosen on the fed world, because that is the one that has a shadow to
            // find; the same window is then cut out of both, so the two pictures are of the same
            // ground rather than of each world's own most interesting corner.
            val shadow = rainShadowWindow(after, side)
            println(
                "S3 RENDER seed=$seed at $side: shadow window ${shadow.left},${shadow.top} " +
                    "${shadow.side} cells, rainfall ${"%.0f".format(shadow.wettest)} to " +
                    "${"%.0f".format(shadow.driest)} mm across it"
            )
            dump(after, seed, side, "after", shadow, "shadow")
            dump(before, seed, side, "before", shadow, "shadow")

            // And the belt the guard actually measured, which is not always the window with the
            // strongest contrast: `ClimateFedErosionTest` picks the largest connected run of cells
            // near an Andean or collisional boundary standing above a kilometre, and asserts the
            // rain's forcing on its two flanks. A picture of some other range is a picture of
            // something the guard never looked at, so the belt is found here by the same rule and
            // cropped to as well.
            val belt = beltWindow(after, side)
            if (belt == null) {
                println("S3 RENDER seed=$seed at $side: no belt over $RANGE_METRES m to crop to")
            } else {
                println(
                    "S3 RENDER seed=$seed at $side: belt window ${belt.left},${belt.top} " +
                        "${belt.side} cells, rainfall ${"%.0f".format(belt.wettest)} to " +
                        "${"%.0f".format(belt.driest)} mm across it"
                )
                dump(after, seed, side, "after", belt, "belt")
                dump(before, seed, side, "before", belt, "belt")
            }
        }
    }

    private fun generate(seed: Long, side: Int, climateFeed: Boolean): WorldMap {
        val base = WorldGenConfig(seed = seed, width = 512, height = 512)
        val config = (if (side == 512) base else base.atResolution(side, side))
        return WorldGenerationEngine.generateBlocking(
            config.copy(erosion = config.erosion.copy(climateFeed = climateFeed))
        )
    }

    private fun dump(
        world: WorldMap,
        seed: Long,
        side: Int,
        label: String,
        window: Window,
        which: String
    ) {
        listOf(
            "atlas" to RenderOptions(view = MapView.FANTASY, style = MapStyle.ATLAS),
            "rainfall" to RenderOptions(view = MapView.RAINFALL)
        ).forEach { (name, options) ->
            val image = rasterOf(world, options)
            val crop = image.getSubimage(window.left, window.top, window.side, window.side)
            ImageIO.write(crop, "png", File(outputDir, "$seed-$side-$which-$name-$label.png"))
            // The whole map beside the crop, at 512 whatever the grid, so the window can be
            // placed on the world it was cut from.
            val whole = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
            val pen = whole.createGraphics()
            pen.drawImage(image, 0, 0, 512, 512, null)
            pen.dispose()
            ImageIO.write(whole, "png", File(outputDir, "$seed-$side-$which-$name-$label-whole.png"))
        }
    }

    /** Where a window was taken, and the rainfall contrast that chose it. */
    private class Window(
        val left: Int,
        val top: Int,
        val side: Int,
        val wettest: Double,
        val driest: Double
    )

    /**
     * The window across a range that holds the strongest rain shadow.
     *
     * Scored on the spread of mean rainfall between the window's four quadrants, over land, and
     * only where enough of the window stands above [RANGE_METRES] — a wet coast beside a dry sea
     * is a large spread and not a rain shadow. Chosen by search rather than written down as
     * coordinates, because a window written down in cells stops pointing at anything the moment
     * the terrain moves; `BayHeadDeltaAuditTest` is on record as having lost one that way.
     */
    private fun rainShadowWindow(world: WorldMap, side: Int): Window {
        val cropSide = CROP_AT_512 * side / 512
        val rainfall = world.climate.precipitationMm.data
        val land = world.sea.isLand
        val relative = world.sea.relativeElevation.data
        val scale = world.config.scale
        val stride = SEARCH_STRIDE * side / 512

        var best: Window? = null
        var bestSpread = -1.0
        var top = 0
        while (top + cropSide <= side) {
            var left = 0
            while (left + cropSide <= side) {
                var range = 0
                val quadrantRain = DoubleArray(4)
                val quadrantCells = IntArray(4)
                for (row in top until top + cropSide) {
                    for (column in left until left + cropSide) {
                        val cell = row * side + column
                        if (!land[cell]) continue
                        if (scale.metresAboveShoreline(relative[cell]) >= RANGE_METRES) range++
                        val quadrant = (if (row - top >= cropSide / 2) 2 else 0) +
                            (if (column - left >= cropSide / 2) 1 else 0)
                        quadrantRain[quadrant] += rainfall[cell].toDouble()
                        quadrantCells[quadrant]++
                    }
                }
                if (range >= cropSide * cropSide * RANGE_SHARE &&
                    quadrantCells.all { it > 0 }
                ) {
                    val means = DoubleArray(4) { quadrantRain[it] / quadrantCells[it] }
                    val spread = means.max() - means.min()
                    if (spread > bestSpread) {
                        bestSpread = spread
                        best = Window(left, top, cropSide, means.max(), means.min())
                    }
                }
                left += stride
            }
            top += stride
        }
        // Nothing on this world stands high enough to hold a shadow; the middle of the map is
        // then as good a window as any, and the print says the contrast was nothing.
        return best ?: Window((side - cropSide) / 2, (side - cropSide) / 2, cropSide, 0.0, 0.0)
    }

    /**
     * A window over the largest mountain belt, found by `ClimateFedErosionTest`'s own rule.
     *
     * The largest 8-connected run of land cells lying within the tectonics' boundary falloff of an
     * Andean margin or a collisional plateau and standing above [RANGE_METRES], cropped to its own
     * bounding box's centre. Kept in step with the guard by construction rather than by comment: a
     * picture is worth looking at only if it is a picture of the ground the figures came from.
     */
    private fun beltWindow(world: WorldMap, side: Int): Window? {
        val cropSide = CROP_AT_512 * side / 512
        val land = world.sea.isLand
        val relative = world.sea.relativeElevation.data
        val scale = world.config.scale
        val distance = world.plates.boundaryDistance.data
        val boundaryClass = world.plates.nearestBoundaryClass
        val falloff = world.config.tectonics.boundaryFalloffCells

        val inBelt = BooleanArray(side * side) { cell ->
            land[cell] && distance[cell] <= falloff &&
                (boundaryClass[cell] == BoundaryClass.ANDEAN_MARGIN.ordinal ||
                    boundaryClass[cell] == BoundaryClass.COLLISION_PLATEAU.ordinal) &&
                scale.metresAboveShoreline(relative[cell]) >= RANGE_METRES
        }

        val label = IntArray(inBelt.size) { -1 }
        val queue = IntArray(inBelt.size)
        var best = -1
        var bestSize = 0
        var next = 0
        for (start in inBelt.indices) {
            if (!inBelt[start] || label[start] >= 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            var size = 0
            while (head < tail) {
                val cell = queue[head++]
                size++
                val column = cell % side
                val row = cell / side
                for (rowStep in -1..1) for (columnStep in -1..1) {
                    if (rowStep == 0 && columnStep == 0) continue
                    val neighbourRow = row + rowStep
                    if (neighbourRow < 0 || neighbourRow >= side) continue
                    val neighbour = neighbourRow * side + (column + columnStep + side) % side
                    if (!inBelt[neighbour] || label[neighbour] >= 0) continue
                    label[neighbour] = next
                    queue[tail++] = neighbour
                }
            }
            if (size > bestSize) {
                bestSize = size
                best = next
            }
            next++
        }
        if (best < 0) return null

        var minRow = side
        var maxRow = 0
        var minColumn = side
        var maxColumn = 0
        for (cell in label.indices) {
            if (label[cell] != best) continue
            val row = cell / side
            val column = cell % side
            if (row < minRow) minRow = row
            if (row > maxRow) maxRow = row
            if (column < minColumn) minColumn = column
            if (column > maxColumn) maxColumn = column
        }
        val left = ((minColumn + maxColumn) / 2 - cropSide / 2).coerceIn(0, side - cropSide)
        val top = ((minRow + maxRow) / 2 - cropSide / 2).coerceIn(0, side - cropSide)

        val rainfall = world.climate.precipitationMm.data
        var wettest = 0.0
        var driest = Double.MAX_VALUE
        for (row in top until top + cropSide) {
            for (column in left until left + cropSide) {
                val cell = row * side + column
                if (!land[cell]) continue
                val mm = rainfall[cell].toDouble()
                if (mm > wettest) wettest = mm
                if (mm < driest) driest = mm
            }
        }
        return Window(left, top, cropSide, wettest, if (driest == Double.MAX_VALUE) 0.0 else driest)
    }

    private fun rasterOf(world: WorldMap, options: RenderOptions): BufferedImage {
        val pixels = MapRasterizer.rasterize(world, options)
        val image = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, world.width, world.height, pixels, 0, world.width)
        return image
    }
}
