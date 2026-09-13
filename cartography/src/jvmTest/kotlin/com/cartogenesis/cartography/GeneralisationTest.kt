package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F14: that the map is drawn for the scale it is being seen at, and says what that scale is.
 *
 * Four claims, and each has a control that is what the renderer did before this chunk or what a
 * plausible shortcut would do instead:
 *
 *  - **The coast stays inside its own band.** Douglas-Peucker promises the simplified line never
 *    strays further from the original than the tolerance it was given. The control is decimation —
 *    keeping every n'th vertex — which throws away the same number of points and misses the bar by
 *    a wide margin, because it has no idea which points were carrying the shape.
 *  - **Fewer rivers at whole-world scale than at four times zoom.** The control is the renderer as
 *    it stood: one overlay whatever the zoom, so the two counts were equal.
 *  - **The scale bar measures what the world says it measures.** Against
 *    `NationsConfig.kilometresPerCellWidth`, which is the same arithmetic the heightmap sidecar
 *    writes, so the bar and the exported metadata cannot drift apart.
 *  - **The graticule's spacing is exact.** The map is the whole globe, so ten degrees is exactly a
 *    thirty-sixth of the width and an eighteenth of the height; the control is the same spacing
 *    rounded to whole cells, which puts the equator off the middle row.
 */
class GeneralisationTest {

    private companion object {
        const val SIDE = 512

        /**
         * The two scales the generalisation is asked about, in screen pixels to the cell.
         *
         * A 2048 world fitted into a 900-pixel pane is at 0.44, which is what the application shows
         * on a laptop the moment a world finishes; four times zoom on the same window is 1.76.
         * [MapSheet.onScreen] quantises both to half-octave bands, so the figures the arithmetic
         * actually runs on are 0.5 and 2.0.
         */
        const val AT_FIT = 900f / 2048f
        const val AT_FOUR_TIMES = 4f * AT_FIT
    }

    private fun world(seed: Long): WorldMap = WorldGenerationEngine.generateBlocking(
        WorldGenConfig(seed = seed, width = SIDE, height = SIDE)
    )

    // ---- the coast ---------------------------------------------------------------------------

    @Test
    fun `the simplified coast never leaves the tolerance it was simplified at`() {
        val map = world(42L)
        val sheet = MapSheet.onScreen(AT_FIT)
        val tolerance = sheet.simplifyToleranceCells

        var traced = emptyList<FloatArray>()
        val traceMs = measureTimeMillis {
            traced = Shoreline.trace(map.sea.isLand, map.width, map.height)
        }
        val full = traced.sumOf { it.size / 2 }

        var worst = 0f
        var worstDecimated = 0f
        var kept = 0
        traced.forEach { line ->
            val simplified = Shoreline.simplified(line, tolerance)
            kept += simplified.size / 2
            worst = maxOf(worst, hausdorff(line, simplified))
            worstDecimated = maxOf(worstDecimated, hausdorff(line, decimated(line, simplified.size / 2)))
        }

        println(
            "F14 coast at $SIDE: ${traced.size} lines, $full vertices traced in $traceMs ms, " +
                "$kept kept at a tolerance of $tolerance cells; " +
                "Douglas-Peucker strays ${worst.round()} cells, decimation to the same count " +
                "strays ${worstDecimated.round()}"
        )
        assertTrue(
            worst <= tolerance,
            "the simplified coast strays ${worst.round()} cells, past its $tolerance tolerance"
        )
        assertTrue(
            worstDecimated > tolerance,
            "decimation to the same vertex count stayed inside the band too, at " +
                "${worstDecimated.round()} cells: the guard cannot tell a simplification from a cull"
        )
    }

    @Test
    fun `every traced vertex sits on the boundary the raster inks`() {
        val map = world(42L)
        val land = map.sea.isLand
        var checked = 0
        Shoreline.trace(land, map.width, map.height).forEach { line ->
            var at = 0
            while (at < line.size) {
                val x = line[at]
                val y = line[at + 1]
                // A vertex is halfway along a cell edge: one coordinate lands on a cell centre and
                // the other between two of them, and those two are the pair the coast divides.
                val (first, second) =
                    if (abs(x - x.toInt() - 0.5f) < 1e-4f) {
                        cellAt(map, x, y - 0.5f) to cellAt(map, x, y + 0.5f)
                    } else {
                        cellAt(map, x - 0.5f, y) to cellAt(map, x + 0.5f, y)
                    }
                assertTrue(
                    land[first] != land[second],
                    "a coast vertex at $x, $y has the same ground on both sides of it"
                )
                checked++
                at += 2
            }
        }
        println("F14 checked $checked coast vertices against the land mask at $SIDE")
    }

    @Test
    fun `a coast is simplified harder the further out the reader stands`() {
        val map = world(42L)
        val counts = listOf(AT_FIT, AT_FOUR_TIMES, 1f).map { pixelsPerCell ->
            val sheet = if (pixelsPerCell == 1f) MapSheet.SHEET else MapSheet.onScreen(pixelsPerCell)
            sheet to Shoreline.of(map.sea.isLand, map.width, map.height, sheet)
                .sumOf { it.size / 2 }
        }
        counts.forEach { (sheet, vertices) ->
            println(
                "F14 coast vertices at ${sheet.pixelsPerCell} px per cell " +
                    "(tolerance ${sheet.simplifyToleranceCells} cells): $vertices"
            )
        }
        assertTrue(
            counts[0].second < counts[1].second,
            "the coast at fit carries as many vertices as the coast at four times zoom"
        )
    }

    // ---- the rivers --------------------------------------------------------------------------

    @Test
    fun `fewer rivers are drawn at whole-world scale than at four times zoom`() {
        val map = world(42L)
        val options = RenderOptions()

        // The control is the call every front end made before this chunk: no sheet, so nothing to
        // generalise for, and the same overlay whatever the reader was looking at. It comes out
        // equal at both zooms *by construction*, which is exactly the defect.
        val unaware = MapRasterizer.overlay(map, options).riversDrawn
        println("F14 CONTROL rivers drawn with no sheet to draw for: $unaware at every zoom")
        assertEquals(map.rivers.rivers.size, unaware, "the sheetless overlay already drops rivers")

        val atFit = MapRasterizer.overlay(map, options, MapSheet.onScreen(AT_FIT)).riversDrawn
        val zoomed = MapRasterizer.overlay(map, options, MapSheet.onScreen(AT_FOUR_TIMES)).riversDrawn
        val onTheSheet = MapRasterizer.overlay(map, options, MapSheet.SHEET).riversDrawn
        println(
            "F14 rivers drawn: $atFit at fit, $zoomed at 4x, $onTheSheet on the sheet " +
                "(of ${map.rivers.rivers.size} traced)"
        )

        assertTrue(atFit < zoomed, "the same rivers are drawn at fit as at four times zoom")
        assertEquals(map.rivers.rivers.size, onTheSheet, "an export dropped a river")
        // Töpfer's law at the quantised half-octave band the fit falls in.
        val expected = MapSheet.onScreen(AT_FIT).featuresKept(map.rivers.rivers.size)
        assertTrue(
            abs(atFit - expected) <= expected / 20,
            "$atFit rivers survived where the law asks for about $expected"
        )
    }

    @Test
    fun `the rivers that survive are the ones carrying the most water`() {
        val map = world(42L)
        val drawn = MapRasterizer.overlay(map, RenderOptions(), MapSheet.onScreen(AT_FIT))
        // Every drawn segment's width comes from a ratio, and the smallest ratio still on the map
        // has to be at least as big as the biggest one that was dropped.
        val peaks = map.rivers.rivers.map { river -> river.widthRatio.maxOrNull() ?: 0f }
            .sortedDescending()
        val cut = peaks[drawn.riversDrawn - 1]
        println(
            "F14 the cut at fit falls at a width ratio of ${cut.round()}, " +
                "between ${peaks.size} rivers running ${peaks.first().round()} down to " +
                "${peaks.last().round()}"
        )
        assertTrue(cut > peaks.last(), "the cut kept every river, so it cut nothing")
    }

    @Test
    fun `Töpfer's law keeps the square root of the change in scale`() {
        // A quarter of the linear scale keeps half the features; the sheet itself keeps all of them.
        assertEquals(50, MapSheet(0.25f).featuresKept(100))
        assertEquals(100, MapSheet(1f).featuresKept(100))
        assertEquals(100, MapSheet(4f).featuresKept(100))
        assertEquals(0, MapSheet(0.25f).featuresKept(0))
        assertEquals(1, MapSheet(0.0001f).featuresKept(100), "a map of a world lost all its rivers")
    }

    // ---- the scale bar -----------------------------------------------------------------------

    @Test
    fun `the scale bar measures what the world's own arithmetic says`() {
        val nations = WorldGenConfig().nations
        val cellsAcross = 2048
        val perPixel = MapScale.kilometresPerPixel(nations, cellsAcross, 1f)

        assertEquals(nations.worldWidthKm / cellsAcross, perPixel, 1e-9)
        assertEquals(nations.kilometresPerCellWidth(cellsAcross), perPixel, 1e-9)

        val frame = cellsAcross.toFloat()
        val bar = MapScale.bar(perPixel, frame)
        println(
            "F14 scale bar on a $cellsAcross sheet: ${bar.label} over ${bar.lengthPixels} px, " +
                "at ${MapScale.oneDecimal(perPixel)} km per pixel; " +
                MapScale.cartoucheLine(nations, cellsAcross)
        )

        // The bar is as long as it says it is, to the pixel.
        assertEquals(bar.kilometres, bar.lengthPixels * perPixel, bar.kilometres * 1e-4)
        assertTrue(
            bar.lengthPixels <= frame * MapScale.SHARE_OF_FRAME,
            "the bar runs ${bar.lengthPixels} px, past its share of a $frame px frame"
        )
        // And it is the *longest* round distance that fits: the next one up would not.
        val next = MapScale.roundedDownTo125(bar.kilometres * 1.000001) * 2.0
        assertTrue(
            next / perPixel > frame * MapScale.SHARE_OF_FRAME,
            "a bar of $next km would also have fitted, so the chosen one is not the longest"
        )
        assertTrue(
            oneTwoOrFive(bar.kilometres),
            "${bar.kilometres} km is not a 1-2-5 distance"
        )
    }

    @Test
    fun `the bar shortens as the reader zooms in, and stays a round number`() {
        val nations = WorldGenConfig().nations
        val quoted = listOf(0.25f, 0.5f, 1f, 2f, 8f, 32f).map { pixelsPerCell ->
            val bar = MapScale.bar(
                MapScale.kilometresPerPixel(nations, 2048, pixelsPerCell),
                900f
            )
            assertTrue(oneTwoOrFive(bar.kilometres), "${bar.label} is not a 1-2-5 distance")
            pixelsPerCell to bar.label
        }
        println("F14 the legend's bar as the zoom climbs: $quoted")
        assertTrue(
            quoted.first().second != quoted.last().second,
            "the bar quotes the same distance at every zoom"
        )
    }

    // ---- the graticule -----------------------------------------------------------------------

    @Test
    fun `the graticule's spacing is exact in cells`() {
        listOf(512, 1024, 2048, 4096).forEach { side ->
            val graticule = Graticule.of(side, side)
            val meridianSpacing = side / 36f
            val parallelSpacing = side / 18f

            assertEquals(meridianSpacing, graticule.meridianSpacingCells, 0f)
            assertEquals(parallelSpacing, graticule.parallelSpacingCells, 0f)

            val meridians = graticule.lines.filter { it.fromX == it.toX }.map { it.fromX }.sorted()
            val parallels = graticule.lines.filter { it.fromY == it.toY }.map { it.fromY }.sorted()
            assertEquals(37, meridians.size, "$side has ${meridians.size} meridians, not 37")
            assertEquals(19, parallels.size, "$side has ${parallels.size} parallels, not 19")

            meridians.forEachIndexed { step, x ->
                assertEquals(step * meridianSpacing, x, 0f, "meridian $step is off at $side")
            }
            parallels.forEachIndexed { step, y ->
                assertEquals(step * parallelSpacing, y, 0f, "parallel $step is off at $side")
            }
            // The prime meridian and the equator fall on the middle of the sheet, to a thousandth
            // of a cell — the rest is float multiplication and not a decision. Rounding the spacing
            // to whole cells, which is the shortcut this guard exists to refuse, moves them by
            // whole cells: at 512 the prime meridian lands four cells west of the middle.
            assertEquals(side / 2f, meridians[18], 1e-3f)
            assertEquals(side / 2f, parallels[9], 1e-3f)
            val ifRounded = abs(18 * meridianSpacing.roundToInt() - side / 2f)
            assertTrue(
                ifRounded > 0.4f,
                "rounding $side's spacing to whole cells happens to land on the middle anyway, " +
                    "so this side cannot show the difference"
            )
        }
        println(
            "F14 graticule spacing at 512/1024/2048/4096: " +
                listOf(512, 1024, 2048, 4096).map { Graticule.of(it, it).meridianSpacingCells }
        )
    }

    @Test
    fun `the graticule figures name the lines they sit on`() {
        val graticule = Graticule.of(2048, 2048)
        val texts = graticule.labels.map { it.text }.distinct()
        assertTrue("0°" in texts, "the equator and the prime meridian are unlabelled")
        assertTrue("90°E" in texts && "90°W" in texts)
        assertTrue("60°N" in texts && "60°S" in texts)
        assertTrue("180°" !in texts, "the antimeridian is labelled half off the paper")
        println("F14 graticule figures at 2048: ${graticule.labels.size} of them, ${texts.size} distinct")
    }

    /**
     * No figure is written over its neighbour, and none runs off the paper.
     *
     * The control is the first draft of this, which sized the figures at a little over a quarter of
     * the spacing and labelled every line: at 2048 the top edge came out as
     * `170°W160°W150°W140°W…`, one unbroken row of digits. So the sizing is derived from the widest
     * figure there is and the sheets that still cannot fit them all label fewer of their lines.
     */
    @Test
    fun `no two graticule figures collide, at any size`() {
        listOf(512, 1024, 2048, 4096).forEach { side ->
            val graticule = Graticule.of(side, side)
            val figured = graticule.labels.size
            graticule.labels.forEach { label ->
                val right = label.leftX + Numerals.widthOf(label.text, label.heightPixels)
                assertTrue(
                    label.leftX >= 0f && right <= side.toFloat(),
                    "\"${label.text}\" runs from ${label.leftX} to $right, off a $side sheet"
                )
            }
            // The top edge is where they are tightest: those figures are set side by side, and
            // they are the only ones sharing the sheet's highest baseline.
            val topBaseline = graticule.labels.minOf { it.baselineY }
            val alongTheTop = graticule.labels
                .filter { it.baselineY == topBaseline }
                .sortedBy { it.leftX }
            alongTheTop.zipWithNext { left, next ->
                val end = left.leftX + Numerals.widthOf(left.text, left.heightPixels)
                assertTrue(
                    end <= next.leftX,
                    "at $side, \"${left.text}\" ends at $end and \"${next.text}\" starts at " +
                        "${next.leftX}: the margin is a row of digits, not a set of figures"
                )
            }
            println(
                "F14 at $side: $figured figures, set every " +
                    "${Graticule.figuresEveryNthLine(
                        graticule.meridianSpacingCells,
                        Graticule.labelHeightPixels(graticule.meridianSpacingCells)
                    ) * Graticule.DEGREES} degrees at " +
                    "${Graticule.labelHeightPixels(graticule.meridianSpacingCells)} px"
            )
        }
    }

    @Test
    fun `an export carries a scale bar and the live view does not`() {
        val map = world(42L)
        assertTrue(MapRasterizer.overlay(map, RenderOptions(), MapSheet.PRINTED).scaleBar != null)
        assertTrue(MapRasterizer.overlay(map, RenderOptions(), MapSheet.SHEET).scaleBar == null)
        assertTrue(MapRasterizer.overlay(map, RenderOptions(), MapSheet.onScreen(1f)).scaleBar == null)

        val graticuled = MapRasterizer.overlay(map, RenderOptions(showGraticule = true))
        assertTrue(graticuled.graticule != null)
        assertTrue(MapRasterizer.overlay(map, RenderOptions()).graticule == null)
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** The cell a point in cell coordinates falls in. */
    private fun cellAt(map: WorldMap, x: Float, y: Float): Int =
        y.toInt().coerceIn(0, map.height - 1) * map.width + x.toInt().coerceIn(0, map.width - 1)

    /**
     * How far [from]'s vertices stray from [to], in cells.
     *
     * One-sided by construction and symmetric in fact: every vertex of a simplified line is a vertex
     * of the line it came from, so the other direction is zero.
     */
    private fun hausdorff(from: FloatArray, to: FloatArray): Float {
        if (to.size < 4) return 0f
        var worst = 0f
        var at = 0
        while (at < from.size) {
            var nearest = Float.MAX_VALUE
            var segment = 0
            while (segment < to.size - 2) {
                nearest = minOf(
                    nearest,
                    Shoreline.distanceToSegment(
                        from[at], from[at + 1],
                        to[segment], to[segment + 1], to[segment + 2], to[segment + 3]
                    )
                )
                segment += 2
            }
            if (nearest > worst) worst = nearest
            at += 2
        }
        return worst
    }

    /** [line] culled to [vertices] points by keeping every n'th one: the naive generaliser. */
    private fun decimated(line: FloatArray, vertices: Int): FloatArray {
        val total = line.size / 2
        if (vertices >= total || vertices < 2) return line
        val step = (total - 1).toDouble() / (vertices - 1)
        val culled = FloatArray(vertices * 2)
        for (index in 0 until vertices) {
            val source = (index * step).toInt().coerceAtMost(total - 1)
            culled[index * 2] = line[source * 2]
            culled[index * 2 + 1] = line[source * 2 + 1]
        }
        return culled
    }

    private fun oneTwoOrFive(kilometres: Double): Boolean {
        var scaled = kilometres
        while (scaled >= 10.0) scaled /= 10.0
        while (scaled < 1.0) scaled *= 10.0
        return listOf(1.0, 2.0, 5.0).any { abs(scaled - it) < 1e-6 }
    }

    private fun Float.round(): Double = (this * 1000f).toInt() / 1000.0
}
