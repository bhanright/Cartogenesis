package com.cartogenesis.cartography

import com.cartogenesis.cartography.geometry.KnownFailures
import com.cartogenesis.cartography.geometry.RecordedViolation
import com.cartogenesis.worldgen.BorrowsSharedWorlds
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.River
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the map is drawn for the scale it is being seen at, and says what that scale is.
 *
 * Four claims, and each has a control that is what the renderer did before generalisation existed
 * or what a plausible shortcut would do instead. See docs/DESIGN_LEDGER.md, F14.
 *
 *  - **The coast stays inside its own band.** Douglas-Peucker promises the simplified line never
 *    strays further from the original than the tolerance it was given. The control is decimation —
 *    keeping every n'th vertex — which throws away the same number of points and misses the bar by
 *    a wide margin, because it has no idea which points were carrying the shape.
 *  - **Fewer rivers at whole-world scale than at four times zoom.** The control is the renderer as
 *    it stood: one overlay whatever the zoom, so the two counts were equal. These two clauses are
 *    now asked at the top mark of [RiverSelection]'s density scale, because the law is no
 *    longer what chooses the
 *    rivers a sheet draws: it is a share of the traced count and so has no answer in kilometres of
 *    ink per square kilometre of land, which is what X1c measures a map by. Nothing about the law
 *    itself has changed, and neither have the figures here. See `RiverSelectionTest`.
 *  - **The scale bar measures what the world says it measures.** Against the true-shape sheet's
 *    one scale, `WorldScale.cellWidthKm` over the pixels a cell spans east-west and
 *    `WorldScale.cellHeightKm` over the pixels it spans north-south, which is the same arithmetic
 *    the heightmap sidecar writes, so the bar and the exported metadata cannot drift apart; and the
 *    printed bar against the sheet's pixel rather than the grid's.
 *  - **The graticule's spacing is exact.** The map is the whole globe, so ten degrees is exactly a
 *    thirty-sixth of the width and an eighteenth of the height; the control is the same spacing
 *    rounded to whole cells, which puts the equator off the middle row.
 */
class GeneralisationTest : BorrowsSharedWorlds() {

    private companion object {
        const val SIDE = 512

        /**
         * The two scales the generalisation is asked about, in screen pixels to a pixel of the
         * whole sheet.
         *
         * A 2048 world's true-shape sheet, 4096 pixels across, fitted into a 900-pixel pane is at
         * 0.22, which is what the application shows on a laptop the moment a world finishes; four
         * times zoom on the same window is 0.88. [MapSheet.onScreen] quantises both to half-octave
         * bands, so the figures the arithmetic actually runs on are 0.25 and 1.
         */
        const val AT_FIT = 900f / 4096f
        const val AT_FOUR_TIMES = 4f * AT_FIT

        /**
         * How near a half-cell boundary a coordinate has to fall to count as being on one.
         *
         * A ten-thousandth of a cell. Every traced coordinate is a whole or a half exactly, so
         * this is only against a float that arrived through arithmetic rather than a literal.
         */
        const val ON_A_CELL_CENTRE = 1e-4f

        /** The four sides a land cell's water can lie on, in the order the counts are kept. */
        val FACINGS = listOf("east", "south", "west", "north")
        const val EAST = 0
        const val SOUTH = 1
        const val WEST = 2
        const val NORTH = 3

        /** The known failures these clauses record, by the audit finding each is. */
        const val COAST_INKED_EAST_AND_SOUTH = "Audit III F-D2: the raster coast inks east- and south-facing shores only"
    }

    private fun world(seed: Long): WorldMap = SharedWorlds.world(
        WorldGenConfig(seed = seed, width = SIDE, height = SIDE)
    )

    // ---- the coast ---------------------------------------------------------------------------

    @Test
    fun `the simplified coast never leaves the tolerance it was simplified at`() {
        val map = world(42L)
        val sheet = MapSheet.onScreen(AT_FIT)
        val tolerance = sheet.simplifyTolerancePixels
        val geometry = SheetGeometry.of(map)

        // Carried onto the true-shape sheet, where the tolerance is set and a pixel is the same
        // ground both ways, and measured there.
        var traced = emptyList<FloatArray>()
        val traceMs = measureTimeMillis {
            traced = Shoreline.trace(map.sea.isLand, map.width, map.height).map { line ->
                FloatArray(line.size) { at ->
                    if (at % 2 == 0) geometry.sheetX(line[at]) else geometry.sheetY(line[at])
                }
            }
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
            "SCALE coast at $SIDE: ${traced.size} lines, $full vertices traced in $traceMs ms, " +
                "$kept kept at a tolerance of $tolerance sheet pixels; " +
                "Douglas-Peucker strays ${worst.round()} pixels, decimation to the same count " +
                "strays ${worstDecimated.round()}"
        )
        assertTrue(
            worst <= tolerance,
            "the simplified coast strays ${worst.round()} pixels, past its $tolerance tolerance"
        )
        assertTrue(
            worstDecimated > tolerance,
            "decimation to the same vertex count stayed inside the band too, at " +
                "${worstDecimated.round()} pixels: the guard cannot tell a simplification from a cull"
        )
    }

    /**
     * The traced coast runs between a land cell and a water cell at every vertex, and the raster
     * inks the land cell of that pair: `Shoreline` says the two "agree wherever both are drawn".
     *
     * What the raster inks is read off the rendering, the fantasy view drawn with its coast and
     * without, rather than off the land mask, which is where the traced line comes from and so
     * could only agree with it. The raster inks a land cell only where water lies east or south of
     * it (Audit III, F-D2), so today the landward cell of every west- and north-facing pair is left
     * bare; the clause is kept running as a known failure under that finding, by the facings the
     * bare cells face.
     */
    @Test
    fun `every traced vertex sits on the boundary the raster inks`() {
        val map = world(42L)
        val land = map.sea.isLand
        val withCoast = MapRasterizer.rasterize(map, RenderOptions(view = MapView.FANTASY, showCoastline = true))
        val withoutCoast = MapRasterizer.rasterize(map, RenderOptions(view = MapView.FANTASY, showCoastline = false))
        var checked = 0
        // How many vertices leave their land cell bare, by where the water lies from it.
        val bareByFacing = IntArray(FACINGS.size)
        Shoreline.trace(land, map.width, map.height).forEach { line ->
            var at = 0
            while (at < line.size) {
                val vertexX = line[at]
                val vertexY = line[at + 1]
                // A vertex is halfway along a cell edge: one coordinate lands on a cell centre and
                // the other between two of them, and those two are the pair the coast divides.
                val across = abs(vertexX - vertexX.toInt() - 0.5f) < ON_A_CELL_CENTRE
                val (first, second) =
                    if (across) {
                        cellAt(map, vertexX, vertexY - 0.5f) to
                            cellAt(map, vertexX, vertexY + 0.5f)
                    } else {
                        cellAt(map, vertexX - 0.5f, vertexY) to
                            cellAt(map, vertexX + 0.5f, vertexY)
                    }
                assertTrue(
                    land[first] != land[second],
                    "a coast vertex at $vertexX, $vertexY has the same ground on both sides of it"
                )
                val landward = if (land[first]) first else second
                if (withCoast[landward] == withoutCoast[landward]) {
                    // `first` is north of `second` across a row edge and west of it across a
                    // column edge, so the water's side follows from which of the two is land.
                    val facing = when {
                        across && landward == first -> SOUTH
                        across -> NORTH
                        landward == first -> EAST
                        else -> WEST
                    }
                    bareByFacing[facing]++
                }
                checked++
                at += 2
            }
        }
        val bare = FACINGS.indices.filter { bareByFacing[it] > 0 }
        println(
            "SCALE checked $checked coast vertices at $SIDE; the land cell left uninked by the raster, " +
                "by the water's side: " + FACINGS.indices.joinToString { "${FACINGS[it]} ${bareByFacing[it]}" }
        )
        KnownFailures.expect(COAST_INKED_EAST_AND_SOUTH, "uninked where the water lies west and north") {
            if (bare.isNotEmpty()) {
                throw RecordedViolation(
                    "the raster leaves the land cell of ${bareByFacing.sum()} of $checked coast vertices uninked: " +
                        FACINGS.indices.joinToString { "${FACINGS[it]} ${bareByFacing[it]}" },
                    "uninked where the water lies " + bare.joinToString(" and ") { FACINGS[it] }
                )
            }
        }
    }

    @Test
    fun `a coast is simplified harder the further out the reader stands`() {
        val map = world(42L)
        val counts = listOf(AT_FIT, AT_FOUR_TIMES, 1f).map { pixelsPerSheetPixel ->
            val sheet = if (pixelsPerSheetPixel == 1f) MapSheet.UNGENERALISED
            else MapSheet.onScreen(pixelsPerSheetPixel)
            sheet to Shoreline.of(map.sea.isLand, SheetGeometry.of(map), sheet)
                .sumOf { it.size / 2 }
        }
        counts.forEach { (sheet, vertices) ->
            println(
                "SCALE coast vertices at ${sheet.pixelsPerSheetPixel} px per sheet pixel " +
                    "(tolerance ${sheet.simplifyTolerancePixels} sheet pixels): $vertices"
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
        // The radical law by name: it is a setting now, and these are its guards. See the class
        // comment, and `RiverSelectionTest` for what the default rule is measured against instead.
        val options = RenderOptions(riverInkStep = RiverSelection.EVERY_COURSE_STEP)

        // The control is the call every front end made before generalisation existed: no sheet,
        // so nothing to generalise for, and the same overlay whatever the reader was looking at.
        // It comes out equal at both zooms *by construction*, which is exactly the defect.
        val unaware = MapRasterizer.overlay(map, options).riversDrawn
        println("SCALE CONTROL rivers drawn with no sheet to draw for: $unaware at every zoom")
        assertEquals(map.rivers.rivers.size, unaware, "the sheetless overlay already drops rivers")

        val atFit = MapRasterizer.overlay(map, options, MapSheet.onScreen(AT_FIT)).riversDrawn
        val zoomed = MapRasterizer.overlay(map, options, MapSheet.onScreen(AT_FOUR_TIMES)).riversDrawn
        val onTheSheet = MapRasterizer.overlay(map, options, MapSheet.UNGENERALISED).riversDrawn
        println(
            "SCALE rivers drawn: $atFit at fit, $zoomed at 4x, $onTheSheet on the sheet " +
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

    /**
     * Which rivers the sheet at fit draws, against which it drops: the smallest peak among the
     * drawn has to be at least the largest among the dropped. Read off the rivers the drawing
     * chooses ([RiverSelection.drawnOn], the call [MapRasterizer.overlay] makes, tied to the
     * overlay by its count), so a selection that kept the right number of the wrong rivers fails.
     */
    @Test
    fun `the rivers that survive are the ones carrying the most water`() {
        val map = world(42L)
        val options = RenderOptions(riverInkStep = RiverSelection.EVERY_COURSE_STEP)
        val sheet = MapSheet.onScreen(AT_FIT)
        val overlay = MapRasterizer.overlay(map, options, sheet)
        val drawn = RiverSelection.drawnOn(map, sheet, options.riverInkStep)
        assertEquals(overlay.riversDrawn, drawn.size, "the overlay drew another selection than the one read here")
        val drawnSet = drawn.toSet()
        fun peak(river: River): Float = river.widthRatio.maxOrNull() ?: 0f
        val dropped = map.rivers.rivers.filterNot { it in drawnSet }
        assertTrue(dropped.isNotEmpty(), "the sheet at fit dropped no river, so there is no cut to measure")
        val smallestDrawn = drawn.minOf(::peak)
        val largestDropped = dropped.maxOf(::peak)
        println(
            "SCALE at fit ${drawn.size} of ${map.rivers.rivers.size} rivers drawn: the smallest drawn " +
                "peaks at a width ratio of ${smallestDrawn.round()}, the largest dropped at ${largestDropped.round()}"
        )
        assertTrue(
            smallestDrawn >= largestDropped,
            "a river peaking at ${largestDropped.round()} was dropped while one peaking at " +
                "${smallestDrawn.round()} was drawn"
        )
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
        val scale = WorldGenConfig().scale
        val cellsAcross = 2048
        val geometry = SheetGeometry.of(scale, cellsAcross, cellsAcross)
        val perPixel = MapScale.kilometresPerPixel(geometry, 1f)

        // The true-shape sheet is twice the grid's width, so its pixel is half a cell's width of
        // ground east-west and one row's height north-south: the same ground both ways.
        assertEquals(scale.worldWidthKm / geometry.widthPixels, perPixel, 1e-9)
        assertEquals(scale.cellWidthKm(cellsAcross) / geometry.pixelsPerCellAcross, perPixel, 1e-9)
        assertEquals(scale.cellHeightKm(cellsAcross) / geometry.pixelsPerCellDown, perPixel, 1e-9)

        val frame = geometry.widthPixels.toFloat()
        val bar = MapScale.longestBarThatFits(perPixel, frame)
        println(
            "SCALE scale bar on a $cellsAcross world's ${geometry.widthPixels}-pixel sheet: " +
                "${bar.label} over ${bar.lengthPixels} px, at ${MapScale.oneDecimal(perPixel)} km " +
                "per pixel; " + MapScale.cartoucheLine(geometry)
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

        // The sheet is drawn at the world's true shape, so a pixel covers the same ground along a
        // meridian as along a parallel and the sheet has one scale: the cartouche quotes one figure,
        // and it is that one. (The squeezed sheet this replaced had two, and quoted both; Audit III,
        // F-C1.)
        val line = MapScale.cartoucheLine(geometry)
        println("SCALE the cartouche says: $line")
        assertTrue(
            line.startsWith("${MapScale.oneDecimal(perPixel)} km per pixel ·") &&
                "east-west" !in line && "north-south" !in line,
            "the cartouche does not quote the sheet's one scale, " +
                "${MapScale.oneDecimal(perPixel)} km a pixel: $line"
        )
    }

    /**
     * The printed sheet's own bar, placed by the renderer, is measured against the sheet's pixel
     * width and not the grid's.
     *
     * On a 2048 world's sheet a pixel is 12,000 / 4096 = 2.9297 km, so a 500 km bar is 170.7
     * pixels long. The control is the arithmetic of the squeezed sheet this replaced — a cell's
     * width of ground to the pixel — which draws the same bar at 85.3 pixels, half the length it
     * should be on the true-shape sheet.
     */
    @Test
    fun `the printed bar is as long on the sheet as the distance it names`() {
        val scale = WorldGenConfig().scale
        val geometry = SheetGeometry.of(scale, 2048, 2048)
        val kilometresPerPixel = scale.worldWidthKm / geometry.widthPixels
        assertEquals(2.9296875, kilometresPerPixel, 1e-12)

        val placed = MapRasterizer.placedScaleBar(geometry)
        assertEquals(
            placed.bar.kilometres / kilometresPerPixel, placed.bar.lengthPixels.toDouble(), 1e-3,
            "the printed ${placed.bar.label} bar runs ${placed.bar.lengthPixels} pixels"
        )
        assertTrue(
            placed.bar.lengthPixels <= geometry.widthPixels * MapScale.SHARE_OF_FRAME,
            "the printed bar runs past a quarter of the sheet"
        )

        // A 500 km bar, as a 900-pixel frame at this scale chooses it.
        val fiveHundred =
            MapScale.longestBarThatFits(MapScale.kilometresPerPixel(geometry, 1f), 900f)
        assertEquals(500.0, fiveHundred.kilometres)
        assertEquals(500.0 / 2.9296875, fiveHundred.lengthPixels.toDouble(), 1e-3)
        val squeezedPixels = 500.0 / scale.cellWidthKm(2048)
        println(
            "SCALE the printed bar on a 2048 world's sheet: ${placed.bar.label} over " +
                "${placed.bar.lengthPixels} px; 500 km is ${fiveHundred.lengthPixels} px, and was " +
                "$squeezedPixels px on the squeezed sheet"
        )
        assertTrue(
            abs(squeezedPixels - fiveHundred.lengthPixels) > 1.0,
            "the squeezed sheet's arithmetic draws 500 km the same length, so the guard cannot " +
                "tell them apart"
        )
    }

    @Test
    fun `the bar shortens as the reader zooms in, and stays a round number`() {
        val geometry = SheetGeometry.of(WorldGenConfig().scale, 2048, 2048)
        val quoted = listOf(0.125f, 0.25f, 0.5f, 1f, 4f, 16f).map { pixelsPerSheetPixel ->
            val bar = MapScale.longestBarThatFits(
                MapScale.kilometresPerPixel(geometry, pixelsPerSheetPixel),
                900f
            )
            assertTrue(oneTwoOrFive(bar.kilometres), "${bar.label} is not a 1-2-5 distance")
            pixelsPerSheetPixel to bar.label
        }
        println("SCALE the legend's bar as the zoom climbs: $quoted")
        assertTrue(
            quoted.first().second != quoted.last().second,
            "the bar quotes the same distance at every zoom"
        )
    }

    // ---- the graticule -----------------------------------------------------------------------

    @Test
    fun `the graticule's spacing is exact in sheet pixels`() {
        listOf(512, 1024, 2048, 4096).forEach { side ->
            val graticule = Graticule.of(side, side)
            val meridianSpacing = side / 36f
            val parallelSpacing = side / 18f

            assertEquals(meridianSpacing, graticule.meridianSpacingPixels, 0f)
            assertEquals(parallelSpacing, graticule.parallelSpacingPixels, 0f)

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
            "SCALE graticule spacing at 512/1024/2048/4096: " +
                listOf(512, 1024, 2048, 4096).map { Graticule.of(it, it).meridianSpacingPixels }
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
        println("SCALE graticule figures at 2048: ${graticule.labels.size} of them, ${texts.size} distinct")
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
                "SCALE at $side: $figured figures, set every " +
                    "${Graticule.figuresEveryNthLine(
                        graticule.meridianSpacingPixels,
                        Graticule.labelHeightPixels(graticule.meridianSpacingPixels)
                    ) * Graticule.DEGREES} degrees at " +
                    "${Graticule.labelHeightPixels(graticule.meridianSpacingPixels)} px"
            )
        }
    }

    @Test
    fun `an export carries a scale bar and the live view does not`() {
        val map = world(42L)
        assertTrue(MapRasterizer.overlay(map, RenderOptions(), MapSheet.PRINTED).scaleBar != null)
        assertTrue(MapRasterizer.overlay(map, RenderOptions(), MapSheet.UNGENERALISED).scaleBar == null)
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
