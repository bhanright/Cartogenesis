package com.cartogenesis.desktop

import com.cartogenesis.cartography.ColorVision
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.NarrowSea
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.SheetGeometry
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith

/**
 * That the water on the map is drawn as water: a river's line stops at a lake's shore rather than
 * crossing the lake, and a channel of sea too narrow for two shores is drawn as the channel it is
 * rather than inked from both banks into a black line.
 *
 * Both are read off the drawing itself, through the same Skia the application draws with, because
 * both faults were faults of the drawing and not of the world: the world underneath them was right.
 */
@ExtendWith(SharedWorldsCheck::class)
class WaterDrawnAsWaterTest {

    /**
     * No river-coloured pixel inside a lake's open water farther than [RIVER_REACH_INTO_LAKE_PIXELS]
     * from its shore, on the gallery's world at 512.
     *
     * A lake's area here is its open water (`LakeResult.openWater`), the part at least two cells
     * across. A strip of lake one cell wide is the river's own reach and carries its line by
     * design (see that property), and every pixel of a strip is within a pixel of its bank anyway;
     * what must not happen is the line running out across the body of the lake. The rivers are
     * drawn alone on a clear sheet, so a pixel is the river's when its stroke covers at least half
     * of it.
     */
    @Test
    fun `a river's line stops at a lake's shore`() {
        val world = SharedWorlds.world(WorldGenConfig(seed = GALLERY_SEED, width = 512, height = 512))
        val reach = riverReachIntoOpenLakes(world)
        println(
            "WATER river pixels inside open lake water: ${reach.pixelsInside} of ${reach.riverPixels}; " +
                "deepest %.2f px from the shore, bar %.2f".format(reach.deepestPixels, RIVER_REACH_INTO_LAKE_PIXELS)
        )
        assertTrue(reach.riverPixels > 0, "no river was drawn, so this measured nothing")
        assertTrue(
            reach.deepestPixels <= RIVER_REACH_INTO_LAKE_PIXELS,
            "a river's line runs %.2f px into a lake's open water, past the %.2f its shore allows"
                .format(reach.deepestPixels, RIVER_REACH_INTO_LAKE_PIXELS)
        )
    }

    /**
     * Every narrow sea cell of the gallery's world at 512 keeps some of its water, away from the
     * mouths where the coast crosses a channel.
     */
    @Test
    fun `a sea channel too narrow for two shores is drawn as water`() {
        val world = SharedWorlds.world(WorldGenConfig(seed = GALLERY_SEED, width = 512, height = 512))
        val swallowed = swallowedNarrowSea(world, window = null)
        println("WATER narrow sea at 512: ${swallowed.swallowed} of ${swallowed.checked} cells inked over")
        assertTrue(swallowed.checked > 0, "this world has no narrow sea to measure")
        assertTrue(
            swallowed.swallowed == 0,
            "${swallowed.swallowed} of ${swallowed.checked} narrow sea cells are inked over by the coast, " +
                "first at ${swallowed.first}"
        )
    }

    /**
     * The channel the page's opening band showed as a near-black line, on the world the band is cut
     * from: seed 1 at 2048, in the band's own window, where it crosses the sheet's east-west seam.
     * At 2048, so in the audit tier.
     */
    @Test
    fun `the channel in the opening band is drawn as water`() {
        val world = WorldGenerationEngine.generateBlocking(SiteImagery.bandConfig())
        val swallowed = swallowedNarrowSea(world, SiteImagery.BAND)
        println("WATER narrow sea in the band: ${swallowed.swallowed} of ${swallowed.checked} cells inked over")
        assertTrue(swallowed.checked > 0, "the band holds no narrow sea to measure")
        assertTrue(
            swallowed.swallowed == 0,
            "${swallowed.swallowed} of ${swallowed.checked} narrow sea cells in the band are inked over, " +
                "first at ${swallowed.first}"
        )
        val reach = riverReachIntoOpenLakes(world)
        println(
            "WATER river pixels inside open lake water on the band's world: ${reach.pixelsInside}; " +
                "deepest %.2f px".format(reach.deepestPixels)
        )
        assertTrue(reach.deepestPixels <= RIVER_REACH_INTO_LAKE_PIXELS)
    }

    private class Reach(val riverPixels: Int, val pixelsInside: Int, val deepestPixels: Double)

    private fun riverReachIntoOpenLakes(world: WorldMap): Reach {
        val sheet = SheetGeometry.of(world)
        val clear = IntArray(world.width * world.height)
        val bitmap = MapImage.toBitmap(world, RenderOptions(style = MapStyle.ATLAS, showCoastline = false), clear)
        val bytes = bitmap.readPixels()!!
        bitmap.close()
        val widthPixels = sheet.widthPixels
        val heightPixels = sheet.heightPixels
        val open = world.rivers.lakes.openWater
        fun openAt(x: Int, y: Int): Boolean {
            val wrapped = ((x % widthPixels) + widthPixels) % widthPixels
            return open[sheet.cellAt(wrapped + HALF_A_PIXEL, y + HALF_A_PIXEL)]
        }
        var riverPixels = 0
        var inside = 0
        var deepest = 0.0
        for (y in 0 until heightPixels) for (x in 0 until widthPixels) {
            val coverage = bytes[(y * widthPixels + x) * BYTES_PER_PIXEL + ALPHA_BYTE].toInt() and 0xFF
            if (coverage < HALF_COVERED) continue
            riverPixels++
            if (!openAt(x, y)) continue
            inside++
            deepest = maxOf(deepest, distanceToShorePixels(x, y, heightPixels, ::openAt))
        }
        return Reach(riverPixels, inside, deepest)
    }

    /** Pixel centre to the nearest pixel centre outside open water, searched out to [SEARCH_PIXELS]. */
    private fun distanceToShorePixels(
        x: Int,
        y: Int,
        heightPixels: Int,
        openAt: (Int, Int) -> Boolean
    ): Double {
        var best = SEARCH_PIXELS.toDouble()
        for (dy in -SEARCH_PIXELS..SEARCH_PIXELS) for (dx in -SEARCH_PIXELS..SEARCH_PIXELS) {
            val row = y + dy
            if (row < 0 || row >= heightPixels) continue
            if (!openAt(x + dx, row)) best = minOf(best, sqrt((dx * dx + dy * dy).toDouble()))
        }
        return best
    }

    private class Swallowed(val checked: Int, val swallowed: Int, val first: String)

    /**
     * How many narrow sea cells ([NarrowSea]) keep none of their water once the coast is inked, of
     * those in [window] (the whole sheet when null) that are not at a mouth.
     *
     * A cell is inked over when every one of its sheet pixels moved more than [INKED_DELTA_E]
     * CIEDE2000 between the Natural map drawn without its coast and with it, rivers off in both so
     * only the coast differs. A mouth is a narrow cell touching open sea, where the coast crosses
     * the channel by design.
     */
    private fun swallowedNarrowSea(world: WorldMap, window: SiteImagery.Window?): Swallowed {
        val sheet = SheetGeometry.of(world)
        val cellsAcross = world.width
        val isLand = world.sea.isLand
        val narrow = NarrowSea.mask(isLand, cellsAcross)
        val options = RenderOptions(style = MapStyle.NATURAL, showRivers = false)
        val inked = MapImage.toBitmap(world, options)
        val bare = MapImage.toBitmap(world, options.copy(showCoastline = false))
        val inkedBytes = inked.readPixels()!!
        val bareBytes = bare.readPixels()!!
        inked.close()
        bare.close()
        val widthPixels = sheet.widthPixels

        fun inWindow(column: Int, row: Int): Boolean {
            if (window == null) return true
            val left = column * sheet.pixelsPerCellAcross
            val top = row * sheet.pixelsPerCellDown
            val intoWindow = ((left - window.x) % widthPixels + widthPixels) % widthPixels
            return intoWindow < window.width && top >= window.y && top < window.y + window.height
        }
        fun atMouth(column: Int, row: Int): Boolean {
            for (dy in -1..1) for (dx in -1..1) {
                val neighbourRow = row + dy
                if (neighbourRow < 0 || neighbourRow >= world.height) continue
                val neighbour = neighbourRow * cellsAcross + ((column + dx) % cellsAcross + cellsAcross) % cellsAcross
                if (!isLand[neighbour] && !narrow[neighbour]) return true
            }
            return false
        }
        fun colourAt(bytes: ByteArray, x: Int, y: Int): Int {
            val at = (y * widthPixels + x) * BYTES_PER_PIXEL
            return (0xFF shl 24) or ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at].toInt() and 0xFF)
        }

        var checked = 0
        var swallowed = 0
        var first = "nowhere"
        for (cell in narrow.indices) {
            if (!narrow[cell]) continue
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            if (!inWindow(column, row) || atMouth(column, row)) continue
            checked++
            var keepsWater = false
            for (dy in 0 until sheet.pixelsPerCellDown) for (dx in 0 until sheet.pixelsPerCellAcross) {
                val x = column * sheet.pixelsPerCellAcross + dx
                val y = row * sheet.pixelsPerCellDown + dy
                if (ColorVision.deltaE2000(colourAt(inkedBytes, x, y), colourAt(bareBytes, x, y)) <= INKED_DELTA_E) {
                    keepsWater = true
                }
            }
            if (!keepsWater) {
                if (swallowed == 0) first = "cell $column,$row"
                swallowed++
            }
        }
        return Swallowed(checked, swallowed, first)
    }

    private companion object {
        /** The gallery's world, the one the drawing guards measure on. */
        const val GALLERY_SEED = 234475L

        /**
         * How far a river's line may reach into a lake's open water, in sheet pixels: one.
         *
         * The line is cut back so its round cap is tangent to the shore (`trimmedAtTheShore`), so
         * all that may cross it is the antialiased edge of the stroke, which is a pixel; the pixels
         * of the shore itself are within one pixel of it on either side.
         */
        const val RIVER_REACH_INTO_LAKE_PIXELS = 1.5

        /**
         * How far a pixel's colour may move under the coast and still be the water it was: 5
         * CIEDE2000, where two colours part at a glance (`ColorVision.deltaE2000`).
         */
        const val INKED_DELTA_E = 5.0

        /** Farther than any river's cap could reach at the sizes measured: a stroke is ten pixels at most. */
        const val SEARCH_PIXELS = 12

        /** A stroke that covers half a pixel or more is the river's pixel. */
        const val HALF_COVERED = 128

        const val HALF_A_PIXEL = 0.5f
        const val BYTES_PER_PIXEL = 4

        /** Skia's S32 is blue, green, red, alpha in memory on this platform. */
        const val ALPHA_BYTE = 3
    }
}
