package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.RiverSelection
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.River
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.junit.jupiter.api.extension.ExtendWith

/**
 * The map is drawn at the world's true shape: what is round on the ground is round on the sheet,
 * and a line of ink is the same width whichever way it runs.
 *
 * Both are read off the finished bitmap [MapImage.toBitmap] hands an export, and nothing else, so
 * the measurement does not take the drawing's word for its own geometry.
 *
 *  - **A disc of land a fixed number of kilometres across**, laid on a generated world's grid in
 *    place of its own land, comes out as wide as it is tall on the sheet, at three sizes of world
 *    and in every style. The world's grid is as many cells tall as wide over ground twice as wide as
 *    tall, so a disc on the ground covers twice as many rows as columns; drawn a cell to a square
 *    pixel it comes out twice as tall as wide, which is the sheet this replaced.
 *  - **Two rivers at the full pen**, one running east-west and one north-south, are measured across
 *    their strokes: the same number of pixels, and the same kilometres of ground. A sheet drawn a
 *    cell to a square pixel passes the first and fails the second — its pixel is twice as far
 *    across east-west as north-south — and a finished bitmap stretched to the true shape fails the
 *    first, its north-south stroke doubled.
 *
 * The drawing reaches no API newer than the bitmap, so the same file measures the sheet this
 * replaced; see docs/DESIGN_LEDGER.md, Fix A, for what it found there.
 */
@ExtendWith(SharedWorldsCheck::class)
class TrueShapeSheetTest {

    @Test
    fun `a round feature on the ground is round on the sheet, at every size and in every style`() {
        SIZES.forEach { size ->
            val base = world(size)
            val sea = withDisc(base, 0.0)
            val disc = withDisc(base, DISC_RADIUS_KM)
            MapStyle.entries.forEach { style ->
                val options = RenderOptions(
                    style = style, showRivers = false, showLakes = false, showLandmarks = false
                )
                val bare = pixels(MapImage.toBitmap(sea, options, MapSheet.UNGENERALISED))
                val drawn = MapImage.toBitmap(disc, options, MapSheet.UNGENERALISED)
                val sheetWidth = drawn.width
                val sheetHeight = drawn.height
                val painted = pixels(drawn)
                var left = Int.MAX_VALUE
                var right = Int.MIN_VALUE
                var top = Int.MAX_VALUE
                var bottom = Int.MIN_VALUE
                for (y in 0 until sheetHeight) {
                    for (x in 0 until sheetWidth) {
                        if (painted[y * sheetWidth + x] == bare[y * sheetWidth + x]) continue
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
                val across = right - left + 1
                val down = bottom - top + 1
                // A cell's quantisation either way, and a pixel of antialiasing each side.
                val tolerance = maxOf(
                    ROUNDNESS_SLACK_PIXELS,
                    (maxOf(across, down) * ROUNDNESS_SHARE).toInt()
                )
                println(
                    "TRUESHAPE disc at $size, ${style.label}: $across x $down pixels on a " +
                        "${sheetWidth}x$sheetHeight sheet"
                )
                assertTrue(
                    abs(across - down) <= tolerance,
                    "a disc $DISC_RADIUS_KM km in radius is drawn $across pixels across and $down " +
                        "down at $size in ${style.label}: it is not round on the sheet"
                )
            }
        }
    }

    @Test
    fun `a line of ink is the same width east-west and north-south, in pixels and on the ground`() {
        val base = world(SIZES[1])
        val land = withDisc(base, WHOLE_WORLD_KM)
        val rivers = withCrossedRivers(land)
        val options = RenderOptions(
            style = MapStyle.ATLAS, showLakes = false, showLandmarks = false,
            showCoastline = false, showHillshade = false,
            riverInkStep = RiverSelection.EVERY_COURSE_STEP
        )
        val bare = MapImage.toBitmap(land, options, MapSheet.UNGENERALISED)
        val drawn = MapImage.toBitmap(rivers, options, MapSheet.UNGENERALISED)
        val sheetWidth = drawn.width
        val sheetHeight = drawn.height
        val ground = pixels(bare)
        val ink = pixels(drawn)
        fun changed(x: Int, y: Int) = ink[y * sheetWidth + x] != ground[y * sheetWidth + x]

        // Across the east-west river, down a column a quarter of the way along it; across the
        // north-south river, along a row a quarter of the way down it: both well clear of the
        // crossing and of either end.
        val acrossTheEastWest = (sheetWidth * 5 / 8).let { x -> (0 until sheetHeight).count { changed(x, it) } }
        val acrossTheNorthSouth = (sheetHeight * 5 / 8).let { y -> (0 until sheetWidth).count { changed(it, y) } }

        val scale = base.config.scale
        val kilometresPerPixelAcross = scale.worldWidthKm / sheetWidth
        val kilometresPerPixelDown =
            scale.worldWidthKm * WorldScale.WORLD_HEIGHT_AS_SHARE_OF_WIDTH / sheetHeight
        val eastWestKm = acrossTheEastWest * kilometresPerPixelDown
        val northSouthKm = acrossTheNorthSouth * kilometresPerPixelAcross
        println(
            "TRUESHAPE the east-west river is $acrossTheEastWest pixels across (${eastWestKm.round()} km), " +
                "the north-south one $acrossTheNorthSouth (${northSouthKm.round()} km), on a " +
                "${sheetWidth}x$sheetHeight sheet"
        )
        assertTrue(acrossTheEastWest > 1 && acrossTheNorthSouth > 1, "a river was not drawn")
        assertTrue(
            abs(acrossTheEastWest - acrossTheNorthSouth) <= INK_SLACK_PIXELS,
            "the same pen runs $acrossTheEastWest pixels across east-west and " +
                "$acrossTheNorthSouth north-south: the ink was stretched"
        )
        assertTrue(
            abs(eastWestKm - northSouthKm) <= INK_SLACK_PIXELS * maxOf(kilometresPerPixelAcross, kilometresPerPixelDown),
            "the same pen covers ${eastWestKm.round()} km of ground east-west and " +
                "${northSouthKm.round()} km north-south: a pixel of the sheet is not the same ground both ways"
        )
    }

    // ---- the worlds --------------------------------------------------------------------------

    private fun world(size: Int): WorldMap = SharedWorlds.world(
        WorldGenConfig(seed = SEED, width = 512, height = 512).atResolution(size, size)
    )

    /**
     * [base] with its land replaced: land wherever the ground is within [radiusKm] of the centre of
     * the map, flat and all at one height, sea everywhere else at one depth. Rivers are taken off,
     * because they were traced over the old land.
     */
    private fun withDisc(base: WorldMap, radiusKm: Double): WorldMap {
        val cellsAcross = base.width
        val cellsDown = base.height
        val cellWidthKm = base.config.cellWidthKm
        val cellHeightKm = base.config.cellHeightKm
        val isLand = BooleanArray(cellsAcross * cellsDown)
        val relative = FloatArray(cellsAcross * cellsDown)
        var landCells = 0
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                val eastKm = (column + HALF - cellsAcross / 2f) * cellWidthKm
                val southKm = (row + HALF - cellsDown / 2f) * cellHeightKm
                val land = hypot(eastKm, southKm) < radiusKm
                isLand[cell] = land
                relative[cell] = if (land) LAND_HEIGHT else SEA_DEPTH
                if (land) landCells++
            }
        }
        return base.copy(
            sea = base.sea.copy(
                isLand = isLand,
                relativeElevation = FloatField(cellsAcross, cellsDown, relative),
                landCellCount = landCells
            ),
            rivers = base.rivers.copy(rivers = emptyList())
        )
    }

    /**
     * [land] with two straight rivers at the full pen through the middle of the map, one along the
     * middle row and one down the middle column, each [RIVER_SHARE] of the grid long either way of
     * the centre. Straight along a row and a column on purpose: the pen is what is measured.
     */
    private fun withCrossedRivers(land: WorldMap): WorldMap {
        val cellsAcross = land.width
        val cellsDown = land.height
        val reachColumns = (cellsAcross * RIVER_SHARE).toInt()
        val reachRows = (cellsDown * RIVER_SHARE).toInt()
        val middleRow = cellsDown / 2
        val middleColumn = cellsAcross / 2
        val eastWest = IntArray(2 * reachColumns + 1) { middleRow * cellsAcross + middleColumn - reachColumns + it }
        val northSouth = IntArray(2 * reachRows + 1) { (middleRow - reachRows + it) * cellsAcross + middleColumn }
        return land.copy(
            rivers = land.rivers.copy(
                rivers = listOf(
                    River(eastWest, FloatArray(eastWest.size) { 1f }),
                    River(northSouth, FloatArray(northSouth.size) { 1f })
                )
            )
        )
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

    private fun Double.round(): String = String.format("%.1f", this)

    private companion object {
        /** The gallery's seed, whose worlds the rest of the suite already borrows. */
        const val SEED = 234475L

        /** Three sizes of world; the sheet's arithmetic is the same at every one. */
        val SIZES = listOf(256, 512, 1024)

        /** A continent's radius: a sixth of the way round the equator of a 12,000 km world across. */
        const val DISC_RADIUS_KM = 1_500.0

        /** Further than any point of the world is from its centre: land everywhere. */
        const val WHOLE_WORLD_KM = 1e9

        const val LAND_HEIGHT = 0.2f
        const val SEA_DEPTH = -0.5f

        /** From a cell's index to its centre. */
        const val HALF = 0.5f

        /** How far either way of the centre each river runs, as a share of the grid. */
        const val RIVER_SHARE = 0.3f

        /**
         * How far across and down may differ and the disc still be round: two cells of the widest
         * a cell is drawn, which is four pixels, or three per cent of the disc, whichever is more.
         */
        const val ROUNDNESS_SLACK_PIXELS = 4
        const val ROUNDNESS_SHARE = 0.03f

        /** A pixel of antialiasing either side of a stroke. */
        const val INK_SLACK_PIXELS = 2

        const val BYTES_PER_PIXEL = 4
    }
}
