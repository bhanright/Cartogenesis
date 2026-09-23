package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The geometry guard's detectors, shown failing on the stamps this project's past causes drew and
 * passing on natural outlines, before they are let near a world.
 *
 * Natural means [IsotropicNoise] thresholded: fractional Brownian relief of Hurst 0.75, whose level
 * lines are Mandelbrot's coast of dimension 1.25, generated in continuous coordinates at arbitrary
 * rotations and only then rasterised onto a grid of this map's own cells. Its isotropy is shown
 * directly first, off the gradient at its level line at random points of the plane, since a noise
 * generated on the grid would not be isotropic by construction.
 *
 * The stamps are the causes the rule lists: a rectangle, a square window's thresholded mask, the
 * depression fill's chessboard staircase, a half-disc lobe, a nearest-seed partition, a ruled comb,
 * and the square, diamond and octagon a breadth-first or windowed operator draws; and beside them
 * a natural island cut along a grid line, a reach laid along one among natural courses, a pyramid's
 * creased level lines, a comb read at two grids, and Earth-like zonal lines. Not every detector is
 * asked to catch every stamp: the matrix printed below says which catches which, and the
 * assertions hold each stamp to the detectors built for it and every natural control to none.
 *
 * The per-place bars are the natural controls' tails at the census's corrected level
 * ([NaturalTails], [Judge]); the ensemble test prints each class's tails part by part beside the
 * bars they set, and holds a separate set of natural islands, which the tails were not read from,
 * under every bar.
 */
class GeometryControlTest {

    private companion object {
        /** The default world's grid at 512: 12,000 km by 6,000 km over 512 cells each way. */
        val FRAME = GridFrame.of(WorldGenConfig(width = 512, height = 512))

        /**
         * The census the per-merge tier runs: four worlds, the layers `MapLayers` lists, and
         * [GeometryGuard.TESTS_PER_LAYER] tests and the place family on each. The controls are held
         * to the same corrected levels a world is.
         */
        val JUDGE = Judge.forCensus(4)
        val FAMILY = JUDGE.familySize

        /** The audit tier's census, seven worlds, whose bars sit a little higher. */
        val AUDIT_JUDGE = Judge.forCensus(7)

        /** A smaller square of the same cells, for the per-component controls. */
        val SQUARE = GridFrame(256, 256, FRAME.cellWidthKm, FRAME.cellHeightKm)

        /** A larger one, for the populations the layer-wide isotropy test reads. */
        val BIG = GridFrame(1024, 1024, FRAME.cellWidthKm, FRAME.cellHeightKm)
    }

    private fun read(name: String, mask: BooleanArray, frame: GridFrame, twice: Boolean = false): LayerReading =
        GeometryGuard.read(Layer(name, Contours.ofMask(mask, frame), twice), frame, JUDGE)

    private fun readMany(name: String, masks: List<BooleanArray>, frame: GridFrame): LayerReading =
        GeometryGuard.read(Layer(name, masks.flatMap { Contours.ofMask(it, frame) }), frame, JUDGE)

    private fun readLines(layer: Layer, frame: GridFrame): LayerReading = GeometryGuard.read(layer, frame, JUDGE)

    private fun flagged(reading: LayerReading): Set<Detector> =
        Detector.entries.filter { reading.outcome(it) == Outcome.VIOLATION }.toSet()

    private fun row(name: String, reading: LayerReading): String =
        "%-44s %s".format(name, Detector.entries.joinToString(" ") { detector ->
            when (reading.outcome(detector)) {
                Outcome.VIOLATION -> "  X  "
                Outcome.CLEAN -> "  .  "
                Outcome.INSUFFICIENT -> "  -  "
                Outcome.NOT_APPLICABLE -> "     "
            }
        })

    @Test
    fun `the grid's bearings and lattice are read off the configuration at every grid`() {
        listOf(512, 1024, 2048).forEach { side ->
            val config = WorldGenConfig(width = 512, height = 512).atResolution(side, side)
            val frame = GridFrame.of(config)
            println("GEOMETRY FRAME $side: $frame")
            assertEquals(0.5, frame.cellHeightKm / frame.cellWidthKm, 1e-12, "cell aspect at $side")
            assertEquals(26.565, frame.diagonalDegrees, 1e-3, "diagonal bearing at $side")
            assertEquals(153.435, frame.gridBearings[3], 1e-3)
        }
    }

    @Test
    fun `the natural controls have no preferred bearing before any detector reads them`() {
        // The direction of the gradient where the field crosses its level is the normal of the
        // level line there; over an isotropic field it is uniform round the half-circle. Sampled at
        // random points of the plane, never at a grid's.
        val random = Random(5)
        listOf(false, true).forEach { inCells ->
            val noise = IsotropicNoise(
                seed = 3L, shortestWavelength = 2.0, longestWavelength = 200.0, rotationDegrees = 0.0,
                unitsAcross = if (inCells) FRAME.cellWidthKm else 1.0,
                unitsDown = if (inCells) FRAME.cellHeightKm else 1.0
            )
            val bins = IntArray(ISOTROPY_BINS)
            var kept = 0
            while (kept < ISOTROPY_SAMPLES) {
                val x = random.nextDouble() * 20000.0
                val y = random.nextDouble() * 20000.0
                if (abs(noise.at(x, y)) > NEAR_LEVEL) continue
                var (gx, gy) = noise.gradientAt(x, y)
                // On the sheet, a field isotropic in cells is read in cells.
                if (inCells) { gx *= FRAME.cellWidthKm; gy *= FRAME.cellHeightKm }
                var angle = atan2(gy, gx)
                if (angle < 0) angle += PI
                if (angle >= PI) angle -= PI
                bins[(angle / PI * ISOTROPY_BINS).toInt().coerceAtMost(ISOTROPY_BINS - 1)]++
                kept++
            }
            val expected = ISOTROPY_SAMPLES.toDouble() / ISOTROPY_BINS
            val chiSquare = bins.sumOf { (it - expected) * (it - expected) / expected }
            val worst = bins.maxOf { abs(it - expected) / expected }
            println("GEOMETRY CONTROL isotropy (%s): chi-square %.1f on %d degrees of freedom, worst bin %.1f%% off".format(
                if (inCells) "in cells" else "on the ground", chiSquare, ISOTROPY_BINS - 1, worst * 100))
            // 36 bins: the 0.1% point of chi-square on 35 degrees of freedom is 66.6.
            assertTrue(chiSquare < 66.6, "the natural control is not isotropic: chi-square $chiSquare")
        }
    }

    @Test
    fun `a straight edge at any bearing comes out as one run at that bearing`() {
        val frame = SQUARE
        val random = Random(9)
        repeat(24) {
            val bearing = random.nextDouble() * 180.0
            // A half-plane through the square's centre, on the ground, with its edge at `bearing`.
            val ux = cos(Math.toRadians(bearing))
            val uy = sin(Math.toRadians(bearing))
            val cx = frame.cellsAcross * frame.cellWidthKm / 2
            val cy = frame.cellsDown * frame.cellHeightKm / 2
            val reach = 90.0 * frame.cellHeightKm
            val mask = Controls.rasterise(frame) { x, y ->
                val along = (x - cx) * ux + (y - cy) * uy
                val across = -(x - cx) * uy + (y - cy) * ux
                abs(along) < reach && across > 0 && across < reach
            }
            val runs = StraightRuns.of(Contours.ofMask(mask, frame), frame)
            val edge = runs.maxBy { run -> run.lengthKm * if (frame.bearingGapDegrees(run.bearingDegrees, bearing) < 3.0) 1.0 else 0.0 }
            val gap = frame.bearingGapDegrees(edge.bearingDegrees, bearing)
            assertTrue(edge.lengthKm > 1.8 * reach * 0.9 && gap < 1.0,
                "an edge at %.1f deg came out as %.0f km at %.2f deg".format(bearing, edge.lengthKm, edge.bearingDegrees))
        }
    }

    /**
     * G2 and G6's controls on the instrument itself: a natural shape reads the same at every
     * rotation, across the seam, against a pole and at every grid; a stamp turned off the grid
     * stops being flagged for alignment; and the stamps' sizes and roughening say where each
     * detector stops seeing them.
     */
    @Test
    fun `the controls across rotation, size, seam, poles, noise and grid`() {
        val lines = ArrayList<String>()
        val failures = ArrayList<String>()

        // Rotation: one natural field turned through twelve angles.
        val ratios = Array(4) { ArrayList<Double>() }
        for (step in 0 until 12) {
            val rotation = step * 15.0 + 4.0
            val mask = Controls.naturalField(FRAME, 202L, 0.35, 60 * FRAME.cellWidthKm, rotationDegrees = rotation)
            val reading = read("natural turned", mask, FRAME)
            reading.isotropy.forEach { ratios[it.bearingIndex].add(it.ratio) }
            if (flagged(reading).isNotEmpty()) failures.add("natural field turned $rotation deg flagged by ${flagged(reading)}")
        }
        ratios.forEachIndexed { index, list ->
            val mean = list.average()
            val spread = Statistics.standardDeviation(list.toDoubleArray())
            lines.add("natural field over 12 rotations, grid bearing %.1f: ratio %.2f +- %.2f (range %.2f to %.2f)".format(
                FRAME.gridBearings[index], mean, spread, list.min(), list.max()))
            if (mean > BearingIsotropy.EFFECT_RATIO / 1.2 || mean < 1.0 / (BearingIsotropy.EFFECT_RATIO / 1.2)) {
                failures.add("natural rotations average %.2f at %.1f deg".format(mean, FRAME.gridBearings[index]))
            }
        }

        // The seam: the same island centred on it and centred on the middle of the map.
        val middle = Controls.naturalIsland(SQUARE, 44L, 40 * SQUARE.cellWidthKm, SQUARE.worldWidthKm / 2, SQUARE.cellsDown * SQUARE.cellHeightKm / 2)
        val onSeam = Controls.naturalIsland(SQUARE, 44L, 40 * SQUARE.cellWidthKm, 0.0, SQUARE.cellsDown * SQUARE.cellHeightKm / 2)
        val middleOutlines = Contours.ofMask(middle, SQUARE)
        val seamOutlines = Contours.ofMask(onSeam, SQUARE)
        val middleBig = ComponentShapes.rings(middleOutlines, SQUARE).filter { it.measured }.maxBy { it.areaCells }
        val seamBig = ComponentShapes.rings(seamOutlines, SQUARE).filter { it.measured }.maxBy { it.areaCells }
        val middleWindows = ComponentShapes.windows(middleOutlines, SQUARE)
        val seamWindows = ComponentShapes.windows(seamOutlines, SQUARE)
        fun local(windows: List<ComponentShapes.Window>) = "longest aligned %.2f steps, longest run %.3f cell widths, strongest crease %.3f".format(
            windows.maxOf { it.alignedSteps }, windows.maxOf { it.runCellWidths }, windows.maxOf { it.creaseStrength })
        lines.add("island in the middle: ${middleBig.describe(SQUARE)}; ${local(middleWindows)}")
        lines.add("island on the seam:   ${seamBig.describe(SQUARE)}; ${local(seamWindows)}")
        if (abs(middleBig.areaCells - seamBig.areaCells) > 1e-6 || abs(middleBig.fill - seamBig.fill) > 1e-9 ||
            abs(middleWindows.maxOf { it.alignedSteps } - seamWindows.maxOf { it.alignedSteps }) > 1e-6 ||
            abs(middleWindows.maxOf { it.runCellWidths } - seamWindows.maxOf { it.runCellWidths }) > 1e-6
        ) failures.add("the island reads differently across the seam")

        // A pole: an island run off the top of the map leaves an open line and no run along the row.
        val pole = Controls.naturalIsland(SQUARE, 45L, 40 * SQUARE.cellWidthKm, SQUARE.worldWidthKm / 2, 10 * SQUARE.cellHeightKm)
        val poleOutlines = Contours.ofMask(pole, SQUARE)
        // The polar row's own line runs through its cells' centres, half a row down; a shore read off
        // the map's edge would be traced along it, two vertices after one another on that line.
        val polarLineKm = 0.5 * SQUARE.cellHeightKm + 1e-9
        val alongThePole = poleOutlines.flatMap { outline ->
            (0 until outline.vertexCount - 1).filter { outline.yKm[it] <= polarLineKm && outline.yKm[it + 1] <= polarLineKm }
        }
        lines.add("island over the pole: %d lines, %d open, %d traced segments along the polar row".format(
            poleOutlines.size, poleOutlines.count { !it.closed }, alongThePole.size))
        if (alongThePole.isNotEmpty() || poleOutlines.none { !it.closed }) failures.add("the pole was read as a shore")
        val poleReading = read("pole", pole, SQUARE)
        if (flagged(poleReading).isNotEmpty()) failures.add("the island over the pole was flagged by ${flagged(poleReading)}")

        // Grids: the same ground at 512, 1024 and 2048.
        for (side in listOf(512, 1024, 2048)) {
            val frame = GridFrame.of(WorldGenConfig(width = 512, height = 512).atResolution(side, side))
            val canvas = GridFrame(side / 2, side / 2, frame.cellWidthKm, frame.cellHeightKm)
            // The same ground at large, and detail down to two cells of each grid, as the generator's own.
            val mask = Controls.naturalField(canvas, 303L, 0.35, 1500.0, finestWavelengthKm = 2 * frame.cellWidthKm)
            val reading = read("natural at $side", mask, canvas)
            lines.add("the same natural ground at %d: %s".format(side, reading.isotropy.joinToString("; ") { "%.1f deg %.2fx %s".format(it.gridBearingDegrees, it.ratio, it.outcome) }))
            lines.add("  its arcs: " + reading.describe(Detector.ARCS))
            if (flagged(reading).isNotEmpty()) failures.add("the natural ground at $side flagged by ${flagged(reading)}")
        }

        // A stamp turned off the grid: no longer aligned, so no longer rejected as a rectangle or for
        // its sides. Turned on the sheet, a rectangle is a parallelogram on the ground, and it is on
        // the ground that its smallest rectangle is found; a rectangle turned on the ground stays one.
        val bars = NaturalTails.of(SQUARE, LineClass.ROUGH).bars(JUDGE)
        for (turn in listOf(0.0, 3.0, 7.0, 23.0, 41.0)) {
            val outlines = Contours.ofMask(Controls.rectangle(SQUARE, 60.0, 30.0, SQUARE.cellsAcross / 2.0, SQUARE.cellsDown / 2.0, turn), SQUARE)
            val ring = ComponentShapes.rings(outlines, SQUARE).single()
            val windows = ComponentShapes.windows(outlines, SQUARE)
            val reading = read("rectangle turned", Controls.rectangle(SQUARE, 60.0, 30.0, SQUARE.cellsAcross / 2.0, SQUARE.cellsDown / 2.0, turn), SQUARE)
            lines.add("rectangle turned %4.1f deg on the sheet: %s; longest aligned side %.1f steps; %d corner pairs; flagged by %s".format(
                turn, ring.describe(SQUARE), windows.maxOf { it.alignedSteps }, windows.sumOf { it.cornerPairs }, flagged(reading)))
            if (turn == 0.0 && (!ring.alignedRectangle || ring.fill <= bars.fill || Detector.RECTANGLE !in flagged(reading))) {
                failures.add("the rectangle on the grid is not flagged as an aligned rectangle")
            }
            if (turn >= 3.0 && (ring.alignedRectangle || windows.any { it.cornerPairs > 0 } ||
                    flagged(reading).any { it in setOf(Detector.RECTANGLE, Detector.RIGHT_ANGLES) })
            ) failures.add("the rectangle turned $turn deg is still flagged for alignment")
        }
        for (turn in listOf(0.0, 11.0, 37.0)) {
            // Turned on the ground: in kilometres about the centre, then rasterised.
            val radians = Math.toRadians(turn)
            val cx = SQUARE.worldWidthKm / 2
            val cy = SQUARE.cellsDown * SQUARE.cellHeightKm / 2
            val mask = Controls.rasterise(SQUARE) { x, y ->
                val along = (x - cx) * cos(radians) + (y - cy) * sin(radians)
                val across = -(x - cx) * sin(radians) + (y - cy) * cos(radians)
                abs(along) <= 30 * SQUARE.cellWidthKm && abs(across) <= 12 * SQUARE.cellWidthKm
            }
            val ring = ComponentShapes.rings(Contours.ofMask(mask, SQUARE), SQUARE).single()
            lines.add("rectangle turned %4.1f deg on the ground: %s".format(turn, ring.describe(SQUARE)))
            if (ring.fill <= ComponentShapes.RECTANGLE_FILL) failures.add("a rectangle turned $turn deg on the ground no longer fills its rectangle")
            if ((turn == 0.0) != ring.alignedRectangle) failures.add("a rectangle turned $turn deg on the ground reads aligned ${ring.alignedRectangle}")
        }

        // Sizes and roughening: where each detector stops seeing a rectangle.
        for (size in listOf(8.0, 12.0, 16.0, 24.0, 32.0, 48.0, 64.0)) for (rough in listOf(0.0, 0.5, 1.5)) {
            var mask = Controls.rectangle(SQUARE, size * 2, size, SQUARE.cellsAcross / 2.0, SQUARE.cellsDown / 2.0)
            if (rough > 0) mask = Controls.roughened(SQUARE, mask, 12L, rough)
            lines.add(row("rectangle %.0fx%.0f roughened %.1f".format(size * 2, size, rough), read("rect", mask, SQUARE)))
        }

        println(lines.joinToString("\n", prefix = "GEOMETRY CONTROLS\n"))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `isotropy read off runs and off fixed chords, on the controls`() {
        fun both(name: String, outlines: List<Outline>, frame: GridFrame) {
            val runs = BearingIsotropy.measure(StraightRuns.of(outlines, frame), frame, FAMILY)
            val chords = BearingIsotropy.measureChords(outlines, frame, FAMILY)
            println("GEOMETRY ISOTROPY %-34s runs %s | chords %s".format(name,
                runs.joinToString(" ") { "%.2f".format(it.ratio) + if (it.outcome == Outcome.VIOLATION) "!" else "" },
                chords.joinToString(" ") { "%.2f".format(it.ratio) + if (it.outcome == Outcome.VIOLATION) "!" else "" } +
                    " (blocks ${chords.map { it.blocks }})"))
        }
        for (inCells in listOf(false, true)) for (rotation in listOf(0.0, 17.0, 45.0, 71.0, 90.0, 133.0)) {
            val mask = Controls.naturalField(FRAME, 101L + rotation.toLong(), 0.35, 60 * FRAME.cellWidthKm,
                rotationDegrees = rotation, isotropicInCells = inCells)
            both("field ${if (inCells) "cells" else "ground"} ${rotation.toInt()}", Contours.ofMask(mask, FRAME), FRAME)
        }
        both("natural courses", Controls.naturalCourses(BIG, 31L, 600, 300, 6.0), BIG)
        both("natural courses, straighter", Controls.naturalCourses(BIG, 32L, 600, 300, 2.0), BIG)
        both("eight-neighbour courses", Controls.eightNeighbourCourses(BIG, 31L, 600, 300, 6.0), BIG)
        both("union of 12-cell blocks", Contours.ofMask(Controls.blockUnion(BIG, 21L, 12, 0.35, 90 * BIG.cellWidthKm), BIG), BIG)
        both("union of 24-cell blocks", Contours.ofMask(Controls.blockUnion(BIG, 21L, 24, 0.35, 180 * BIG.cellWidthKm), BIG), BIG)
    }

    @Test
    fun `the known-failure helper catches the guard's own violation and nothing else`() {
        val recorded = ArrayList<String>()
        val sink = { finding: String, detail: String -> recorded.add("$finding: $detail"); Unit }
        val stamp = Signature(1, 30, 40, 12.5)
        // A violation with the recorded signature is recorded under its finding, and the clause passes.
        KnownFailures.expect("control finding", stamp, { throw GeometryViolation("a stamped rectangle", Signature(1, 31, 39, 12.6)) }, sink)
        assertEquals(listOf("control finding: a stamped rectangle"), recorded)
        // Another violation in the same slot fails: moved, grown, or joined by a second place.
        for (other in listOf(Signature(1, 90, 40, 12.5), Signature(1, 30, 40, 14.0), Signature(2, 30, 40, 12.5), null)) {
            val different = assertFailsWith<AssertionError> {
                KnownFailures.expect("control finding", stamp, { throw GeometryViolation("another stamp", other) }, sink)
            }
            assertTrue(different !is GeometryViolation && different.message!!.contains("a different violation"), "$other passed as $stamp")
        }
        assertEquals(Signature.parse(stamp.toString()).toString(), stamp.toString(), "a signature reads back as written")
        // Every place past the bar is part of the violation, not only the worst: two stamps, of
        // which the lesser is mended while a third breaks elsewhere, keep the count, the worst place
        // and its figure, and are a different violation all the same.
        val aside = ArrayList<String>()
        val asideSink = { finding: String, detail: String -> aside.add("$finding: $detail"); Unit }
        val pair = Signature(2, 30, 40, 12.5, listOf(90 to 90))
        KnownFailures.expect("control finding", pair, { throw GeometryViolation("two stamps", Signature(2, 30, 40, 12.5, listOf(91 to 89))) }, asideSink)
        val replaced = assertFailsWith<AssertionError> {
            KnownFailures.expect("control finding", pair, { throw GeometryViolation("one mended, one new", Signature(2, 30, 40, 12.5, listOf(150 to 20))) }, asideSink)
        }
        assertTrue(replaced.message!!.contains("a different violation"), "a mended place replaced by a new one passed as the old pair")
        assertEquals(pair.toString(), Signature.parse(pair.toString()).toString(), "a signature with its other places reads back as written")
        // A grid bearing is a name, not a place: north-south is not east-west two columns over.
        val eastWest = Signature(1, 0, Signature.LAYER_WIDE_ROW, 2.0)
        KnownFailures.expect("control finding", eastWest, { throw GeometryViolation("along the rows", Signature(1, 0, Signature.LAYER_WIDE_ROW, 2.01)) }, asideSink)
        val otherBearing = assertFailsWith<AssertionError> {
            KnownFailures.expect("control finding", eastWest, { throw GeometryViolation("along the columns", Signature(1, 2, Signature.LAYER_WIDE_ROW, 2.0)) }, asideSink)
        }
        assertTrue(otherBearing.message!!.contains("a different violation"), "a north-south violation passed as the recorded east-west one")
        assertEquals(2, aside.size, "the two matching violations were recorded")
        // A clause outside the guard: its own violation type and its text signature, whole.
        KnownFailures.expect("text finding", "west 3, north 2", { throw RecordedViolation("shores left uninked", "west 3, north 2") }, asideSink)
        val otherText = assertFailsWith<AssertionError> {
            KnownFailures.expect("text finding", "west 3, north 2", { throw RecordedViolation("shores left uninked", "west 3, north 1") }, asideSink)
        }
        assertTrue(otherText.message!!.contains("a different violation"), "another text signature passed")
        assertEquals("text finding fixed: arm this clause",
            assertFailsWith<AssertionError> { KnownFailures.expect("text finding", "west 3, north 2", { }, asideSink) }.message)
        // Each overload catches its own type and lets the other's through untouched.
        assertFailsWith<GeometryViolation> {
            KnownFailures.expect("text finding", "west 3, north 2", { throw GeometryViolation("a stamp", stamp) }, asideSink)
        }
        assertFailsWith<RecordedViolation> {
            KnownFailures.expect("control finding", stamp, { throw RecordedViolation("shores left uninked", "west 3, north 2") }, asideSink)
        }
        assertFailsWith<AssertionError> {
            KnownFailures.expect("text finding", "west 3, north 2", { assertEquals(1, 2) }, asideSink)
        }.also { assertTrue(it !is RecordedViolation && it.message?.contains("fixed") != true) }
        assertEquals(3, aside.size, "only the matching violations were recorded")
        // A clause that no longer fails fails the helper, naming the finding.
        val fixed = assertFailsWith<AssertionError> { KnownFailures.expect("control finding", stamp, { }, sink) }
        assertEquals("control finding fixed: arm this clause", fixed.message)
        // Everything else goes through untouched: too little data, another assertion, a setup error.
        assertFailsWith<InsufficientSample> {
            KnownFailures.expect("control finding", stamp, { throw InsufficientSample("three runs") }, sink)
        }
        val other = assertFailsWith<AssertionError> {
            KnownFailures.expect("control finding", stamp, { assertEquals(1, 2) }, sink)
        }
        assertTrue(other !is GeometryViolation && other.message?.contains("fixed") != true)
        assertFailsWith<IllegalStateException> {
            KnownFailures.expect("control finding", stamp, { error("the world did not generate") }, sink)
        }
        assertEquals(1, recorded.size, "only the violation was recorded")
    }

    /**
     * [Census.pairCombs] lets a comb stand as the ground's only where the reading at the coarser
     * grid is the same comb on the ground: its spacing in kilometres the same, and its teeth where
     * the finer reading's are. Combs laid down by hand, so each case is exactly the one named: the
     * ground's own comb, the same place and spacing with the teeth half a spacing over (another
     * comb), a third of the cells between the teeth rather than a half, the same count of cells
     * (the grid's), and the ground's comb again with its two readings anchored either side of the
     * seam.
     */
    @Test
    fun `a comb is the ground's at two grids only with the same spacing on the ground and the same teeth`() {
        val fine = GridFrame(512, 512, SQUARE.cellWidthKm / 2, SQUARE.cellHeightKm / 2)
        // Teeth along the rows, laid one under another: across is down the sheet.
        fun comb(x: Double, y: Double, spacing: Double, teeth: Int = 8) =
            Combs.Comb(x, y, 0.0, spacing, teeth, 0.99, 50.0, acrossX = 0.0, acrossY = 1.0,
                toothOffsetsCells = List(teeth) { it * spacing })
        fun reading(frame: GridFrame, comb: Combs.Comb) = LayerReading(
            "combs", frame, 0.0, emptyList(), listOf(comb), 0, mapOf(Detector.COMBS to Verdict(Outcome.CLEAN, "")), null
        )
        val cases = listOf(
            Triple("the ground's comb", comb(128.0, 100.0, 6.0) to comb(256.0, 200.0, 12.0), true),
            Triple("the teeth half a spacing over", comb(128.0, 100.0, 6.0) to comb(256.0, 206.0, 12.0), false),
            Triple("a third of the cells", comb(128.0, 100.0, 6.0) to comb(256.0, 200.0, 18.0), false),
            Triple("the same count of cells", comb(128.0, 100.0, 6.0) to comb(256.0, 200.0, 6.0), false),
            Triple("the ground's comb read either side of the seam", comb(2.0, 100.0, 6.0) to comb(510.0, 200.0, 12.0), true)
        )
        val failures = ArrayList<String>()
        for ((name, pairing, groundFixed) in cases) {
            val (coarseComb, fineComb) = pairing
            val result = Census.pairCombs(reading(fine, fineComb), fine, reading(SQUARE, coarseComb), SQUARE).single()
            println("GEOMETRY CONTROL comb pairing, $name: ${result.line}")
            if (result.groundFixed != groundFixed) failures.add("$name: let stand ${result.groundFixed}, expected $groundFixed — ${result.line}")
        }
        println("GEOMETRY CONTROL same spacing on the ground within %.3f, same tooth within %.3f spacings".format(
            Census.SAME_SPACING_SHARE, Census.SAME_TOOTH_SPACINGS))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * A partition's borders are traced once from each side, so a layer holding them holds every
     * block of line twice, and the two copies always agree. [RateTest] reads such a layer as the
     * same border traced once: the same count, the same length and the same spread, where resampling
     * the copies as independent blocks had narrowed the spread by the square root of two and put the
     * lower bound nearer the rate.
     */
    @Test
    fun `a border traced from both sides is no surer of its rate than traced once`() {
        val random = Random(23)
        // Corners come in clumps — a stamp that makes one makes four — so the blocks' spread is
        // well above the Poisson floor and it is the bootstrap that sets the bound.
        val once = List(40) { (if (random.nextInt(8) == 0) 20 else 0) to 50.0 }
        val single = RateTest.of(once, 1, 0.5, JUDGE.z, 1.0, 5L)
        val twice = RateTest.of(once + once, 2, 0.5, JUDGE.z, 1.0, 5L)
        println("GEOMETRY CONTROL rate traced once: $single, spread %.3f; traced twice: $twice, spread %.3f".format(
            single.spreadOnRoots, twice.spreadOnRoots))
        assertEquals(single.count, twice.count, 1e-9)
        assertEquals(single.cellWidths, twice.cellWidths, 1e-9)
        assertTrue(single.spreadOnRoots > 1.0, "the control's blocks are not clumped enough to set the spread")
        assertTrue(
            abs(twice.spreadOnRoots / single.spreadOnRoots - 1) < 0.1,
            "a border traced twice spreads %.3f against %.3f traced once".format(twice.spreadOnRoots, single.spreadOnRoots)
        )
    }

    /**
     * [BearingIsotropy.correlationLengthChords], shown on the natural controls: the autocorrelation
     * of whether a chord lies in one of the grid's bins, by lag, and the block length the rule picks
     * from it — where the autocorrelation has fallen under a tenth.
     */
    @Test
    fun `chords along a natural outline decorrelate within a block`() {
        val indicators = ArrayList<DoubleArray>()
        val stepKm = 0.5 * minOf(FRAME.cellWidthKm, FRAME.cellHeightKm)
        val stride = kotlin.math.ceil(BearingIsotropy.chordKm(FRAME) / stepKm).toInt()
        for (inCells in listOf(false, true)) for (rotation in listOf(0.0, 29.0)) {
            val mask = Controls.naturalField(FRAME, 61L + rotation.toLong(), 0.35, 60 * FRAME.cellWidthKm,
                rotationDegrees = rotation, isotropicInCells = inCells)
            for (outline in Contours.ofMask(mask, FRAME)) {
                if (!outline.closed) continue
                val (xs, ys) = Arcs.resample(outline, stepKm)
                if (xs.size < 8 * stride) continue
                indicators.add(DoubleArray(xs.size) { at ->
                    val to = (at + stride) % xs.size
                    val bearing = FRAME.bearingDegrees(xs[to] - xs[at], ys[to] - ys[at])
                    if (FRAME.gridBearings.any { FRAME.bearingGapDegrees(bearing, it) <= BearingIsotropy.BIN_HALF_WIDTH_DEGREES }) 1.0 else 0.0
                })
            }
        }
        val all = indicators.flatMap { it.asList() }
        val mean = all.average()
        val variance = all.sumOf { (it - mean) * (it - mean) } / all.size
        // Lags in half-chords, out to four chords.
        val correlations = (1..8).map { halves ->
            val lag = halves * stride / 2
            var sum = 0.0
            var pairs = 0
            for (series in indicators) for (index in series.indices) {
                sum += (series[index] - mean) * (series[(index + lag) % series.size] - mean)
                pairs++
            }
            sum / pairs / variance
        }
        println("GEOMETRY CONTROL chord indicator autocorrelation, by lag in chords: " +
            correlations.mapIndexed { index, value -> "%.1f:%.3f".format((index + 1) / 2.0, value) }.joinToString(" ") +
            " (%d outlines, chord %.0f km)".format(indicators.size, BearingIsotropy.chordKm(FRAME)))
        val block = BearingIsotropy.correlationLengthChords(indicators, stride)
        println("GEOMETRY CONTROL the natural outlines' blocks: $block chords")
        assertTrue(abs(correlations[2 * block - 1]) < BearingIsotropy.DECORRELATED, "the block the rule picked is still correlated")
    }

    /**
     * The per-place bars the natural controls set ([NaturalTails]), printed beside the tails they
     * come from, and shown on natural shapes the tails were not read from: natural islands — three
     * roughnesses, isotropic on the ground and on the sheet, at many rotations and radii — none of
     * whose windows or rings may reach a bar, and which may fit no arc and make no corner pair.
     */
    @Test
    fun `the natural ensemble sits below every bar it sets`() {
        val failures = ArrayList<String>()
        for (lines in LineClass.entries) {
            val tails = NaturalTails.of(FRAME, lines)
            val name = lines.label
            println("GEOMETRY TAILS $name: $tails")
            println("GEOMETRY BARS $name at the per-merge census's level (z %.2f): %s".format(JUDGE.zPlace, tails.bars(JUDGE)))
            println("GEOMETRY BARS $name at the audit census's level (z %.2f): %s".format(AUDIT_JUDGE.zPlace, tails.bars(AUDIT_JUDGE)))
            tails.parts.forEach { println("GEOMETRY TAILS $name part $it") }
            if (tails.windowsWithCornerPairs > 0) failures.add("the $name natural controls make ${tails.windowsWithCornerPairs} corner pairs")
            if (lines != LineClass.SMOOTH && tails.arcs > 0) failures.add("the $name natural controls fit ${tails.arcs} arcs")
        }
        val bars = NaturalTails.of(SQUARE, LineClass.ROUGH).bars(JUDGE)
        val windows = ArrayList<ComponentShapes.Window>()
        val rings = ArrayList<ComponentShapes.Ring>()
        val arcNotes = ArrayList<String>()
        var arcs = 0
        val random = Random(17)
        for (roughness in listOf(0.25, 0.4, 0.6)) for (inCells in listOf(false, true)) for (index in 0 until 30) {
            val radiusCells = 4.0 + random.nextDouble() * 41.0
            val mask = Controls.naturalIsland(
                SQUARE, 900L + index * 7 + (roughness * 100).toLong(), radiusCells * SQUARE.cellWidthKm,
                SQUARE.worldWidthKm / 2, SQUARE.cellsDown * SQUARE.cellHeightKm / 2,
                rotationDegrees = random.nextDouble() * 180.0, isotropicInCells = inCells, roughnessOverRadius = roughness
            )
            val outlines = Contours.ofMask(mask, SQUARE)
            windows.addAll(ComponentShapes.windows(outlines, SQUARE))
            rings.addAll(ComponentShapes.rings(outlines, SQUARE))
            val found = Arcs.measure(outlines, SQUARE)
            arcs += found.arcs.size
            found.arcs.forEach { arcNotes.add("r=%.1f roughness %.2f: %s".format(radiusCells, roughness, it.describe(SQUARE))) }
        }
        val measured = rings.filter { it.measured }
        println("GEOMETRY ENSEMBLE islands: %d rings, %d measured, %d windows; bars %s".format(rings.size, measured.size, windows.size, bars))
        println("GEOMETRY ENSEMBLE worst fill: %s".format(measured.maxByOrNull { it.fill }?.describe(SQUARE)))
        println("GEOMETRY ENSEMBLE worst windows: aligned %.1f steps, run %.1f cell widths, crease %.2f; %d corner pairs; %d arcs".format(
            windows.maxOf { it.alignedSteps }, windows.maxOf { it.runCellWidths }, windows.maxOf { it.creaseStrength },
            windows.sumOf { it.cornerPairs }, arcs))
        // Why nine cells across: the fill natural rings reach by their width, measured or not.
        val bands = listOf(2.0, 4.0, 6.0, 9.0, 12.0, 1e9)
        for (band in 0 until bands.size - 1) {
            val within = rings.filter { it.areaCells >= 16 && it.cellsAcross >= bands[band] && it.cellsAcross < bands[band + 1] }
            if (within.isEmpty()) continue
            println("GEOMETRY ENSEMBLE rings %.0f to %.0f cells across: %d, worst fill %.3f".format(
                bands[band], minOf(bands[band + 1], 999.0), within.size, within.maxOf { it.fill }))
        }
        arcNotes.take(10).forEach { println("GEOMETRY ENSEMBLE arc $it") }
        for (radius in listOf(4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 24.0)) for (inCells in listOf(false, true)) {
            val disc = Controls.disc(SQUARE, if (inCells) radius else radius * SQUARE.cellWidthKm,
                if (inCells) SQUARE.cellsAcross / 2.0 + 0.3 else SQUARE.worldWidthKm / 2 + 0.3 * SQUARE.cellWidthKm,
                if (inCells) SQUARE.cellsDown / 2.0 + 0.2 else SQUARE.cellsDown * SQUARE.cellHeightKm / 2 + 0.2 * SQUARE.cellHeightKm, inCells)
            val found = Arcs.measure(Contours.ofMask(disc, SQUARE), SQUARE).arcs
            println("GEOMETRY ENSEMBLE disc %s radius %.0f: %d arcs, %s".format(if (inCells) "on the sheet" else "on the ground", radius, found.size,
                found.joinToString("; ") { "%s %.1f cells %.0f deg rms %.2f".format(it.frame, it.radiusCells, it.coverageDegrees, it.rmsCells) }))
        }
        measured.filter { it.alignedRectangle && it.fill > bars.fill }.forEach { failures.add("a natural ring filled an aligned rectangle: ${it.describe(SQUARE)}") }
        windows.filter { it.alignedSteps > bars.alignedSteps }.forEach { failures.add("a natural window has an aligned side of %.1f steps".format(it.alignedSteps)) }
        windows.filter { it.runCellWidths > bars.runCellWidths }.forEach { failures.add("a natural window has a run of %.1f cell widths".format(it.runCellWidths)) }
        windows.filter { it.creaseStrength > bars.creaseStrength }.forEach { failures.add("a natural window has a crease of %.2f".format(it.creaseStrength)) }
        if (windows.any { it.cornerPairs > 0 }) failures.add("a natural island has a corner pair")
        if (arcs > 0) failures.add("natural islands fitted circles:\n" + arcNotes.take(10).joinToString("\n"))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `the detector and control matrix`() {
        val lines = ArrayList<String>()
        lines.add("%-44s %s".format("control", Detector.entries.joinToString(" ") { it.name.take(5).padEnd(5) }))
        val failures = ArrayList<String>()
        fun expect(name: String, reading: LayerReading, mustFlag: Set<Detector>, mayFlag: Set<Detector> = emptySet()) {
            lines.add(row(name, reading))
            val flags = flagged(reading)
            val missed = mustFlag - flags
            val extra = flags - mustFlag - mayFlag
            if (missed.isNotEmpty()) failures.add("$name: missed by ${missed.joinToString()} — " +
                missed.joinToString(" | ") { "${it.name}: ${reading.outcome(it)} ${reading.describe(it)}" })
            if (extra.isNotEmpty()) failures.add("$name: flagged by ${extra.joinToString()} — " +
                extra.joinToString(" | ") { reading.describe(it) })
        }
        val corners = setOf(Detector.RIGHT_ANGLES, Detector.CORNER_RATE)

        // Natural controls: nothing may flag them.
        for (inCells in listOf(false, true)) for (rotation in listOf(0.0, 17.0, 45.0, 71.0)) {
            val mask = Controls.naturalField(FRAME, 101L + rotation.toLong(), 0.35, 60 * FRAME.cellWidthKm,
                rotationDegrees = rotation, isotropicInCells = inCells)
            expect("natural field ${if (inCells) "cells" else "ground"} ${rotation.toInt()} deg", read("natural", mask, FRAME), emptySet())
        }
        val islands = (0 until 24).map { index ->
            val radius = (6.0 + index * 3.0) * FRAME.cellWidthKm
            Controls.naturalIsland(SQUARE, 500L + index, radius / 2.2, SQUARE.worldWidthKm / 2, SQUARE.cellsDown * SQUARE.cellHeightKm / 2,
                rotationDegrees = index * 13.0)
        }
        expect("natural islands, 24 sizes", readMany("islands", islands, SQUARE), emptySet())
        val seam = Controls.naturalIsland(SQUARE, 77L, 30 * SQUARE.cellWidthKm, 0.0, SQUARE.cellsDown * SQUARE.cellHeightKm / 2)
        expect("natural island across the seam", read("seam", seam, SQUARE), emptySet())
        val pole = Controls.naturalIsland(SQUARE, 78L, 30 * SQUARE.cellWidthKm, SQUARE.worldWidthKm / 2, 0.0)
        expect("natural island over the pole", read("pole", pole, SQUARE), emptySet())

        // Stamps.
        val centreColumn = SQUARE.cellsAcross / 2.0
        val centreRow = SQUARE.cellsDown / 2.0
        expect("rectangle 60x30 on the grid", read("rect", Controls.rectangle(SQUARE, 60.0, 30.0, centreColumn, centreRow), SQUARE),
            setOf(Detector.RECTANGLE, Detector.RIGHT_ANGLES), setOf(Detector.ALIGNED_SIDE, Detector.FACETS, Detector.CREASES, Detector.CORNER_RATE))
        // Turned off the grid, a rectangle is caught by its straight sides once they pass the facet bar,
        // and not before: at 512 a side of 56 cell widths, 1,300 km, lies within the natural tail.
        expect("rectangle 60x30 turned 23 deg", read("rect turned", Controls.rectangle(SQUARE, 60.0, 30.0, centreColumn, centreRow, 23.0), SQUARE),
            emptySet(), setOf(Detector.FACETS, Detector.CREASES))
        expect("rectangle 120x50 turned 23 deg", read("rect turned", Controls.rectangle(SQUARE, 120.0, 50.0, centreColumn, centreRow, 23.0), SQUARE),
            setOf(Detector.FACETS), setOf(Detector.CREASES))
        expect("square window mask", read("window", Controls.squareWindowMask(FRAME, 4L, 9, 40 * FRAME.cellWidthKm), FRAME),
            setOf(Detector.RIGHT_ANGLES), setOf(Detector.RECTANGLE, Detector.ALIGNED_SIDE, Detector.ISOTROPY, Detector.FACETS, Detector.CREASES, Detector.CORNER_RATE))
        expect("chessboard staircase, steps of 8", read("stairs", Controls.chessboardStaircase(SQUARE, 8, 120, 60, 60), SQUARE),
            setOf(Detector.RIGHT_ANGLES), setOf(Detector.ALIGNED_SIDE, Detector.COMBS, Detector.RECTANGLE, Detector.FACETS, Detector.CREASES, Detector.CORNER_RATE))
        expect("half-disc on the sheet", read("half disc", Controls.halfDisc(SQUARE, 40.0, centreColumn, centreRow, 30.0, inCells = true), SQUARE),
            setOf(Detector.ARCS), setOf(Detector.FACETS, Detector.ALIGNED_SIDE, Detector.CREASES))
        expect("half-disc on the ground", read("half disc km", Controls.halfDisc(SQUARE, 40.0 * SQUARE.cellWidthKm,
            SQUARE.worldWidthKm / 2, SQUARE.cellsDown * SQUARE.cellHeightKm / 2, 30.0, inCells = false), SQUARE),
            setOf(Detector.ARCS), setOf(Detector.FACETS, Detector.ALIGNED_SIDE, Detector.CREASES))
        val voronoi = readMany("voronoi", Controls.voronoiCells(BIG, 3L, 14, 960, 32, 32), BIG)
        lines.add("  nearest-seed partition's facets: " + voronoi.describe(Detector.FACETS))
        expect("nearest-seed partition", voronoi, setOf(Detector.FACETS), setOf(Detector.RECTANGLE, Detector.ALIGNED_SIDE, Detector.CREASES, Detector.CORNER_RATE))
        expect("ruled comb along a row, 6 cells", read("comb", Controls.comb(SQUARE, 9, 6.0, 2.0, 60.0, centreColumn, centreRow, 0.0), SQUARE),
            setOf(Detector.COMBS), setOf(Detector.ALIGNED_SIDE, Detector.RECTANGLE, Detector.FACETS, Detector.ISOTROPY, Detector.CREASES) + corners)
        expect("ruled comb at 33 deg, 7 cells", read("comb turned", Controls.comb(SQUARE, 9, 7.0, 2.0, 60.0, centreColumn, centreRow, 33.0), SQUARE),
            setOf(Detector.COMBS), setOf(Detector.RECTANGLE, Detector.FACETS, Detector.CREASES))
        // A Manhattan diamond is a square turned on the sheet, a rhombus on the ground: its sides lie
        // along the grid's diagonals and meet square on the sheet, but on the ground it fills only
        // 0.625 of its smallest rectangle, which is not along the grid.
        expect("diamond (four-connected ring)", read("diamond", Controls.diamond(SQUARE, 40.0, centreColumn, centreRow), SQUARE),
            setOf(Detector.RIGHT_ANGLES), setOf(Detector.ALIGNED_SIDE, Detector.FACETS, Detector.CREASES, Detector.CORNER_RATE))
        expect("square (eight-connected ring)", read("chebyshev", Controls.chebyshevSquare(SQUARE, 30.0, centreColumn, centreRow), SQUARE),
            setOf(Detector.RECTANGLE, Detector.RIGHT_ANGLES), setOf(Detector.ALIGNED_SIDE, Detector.FACETS, Detector.CREASES, Detector.CORNER_RATE))
        // G4's local subshape: a large natural island with one side cut along a row, and one cut along
        // the grid's diagonal, each side about ninety steps long, which the aligned-side bar is for.
        // The stamps above have sides of forty to sixty steps, inside the natural tail at this
        // census's level, and are caught as rectangles and by their corners instead.
        val island = Controls.naturalIsland(SQUARE, 64L, 50 * SQUARE.cellWidthKm, SQUARE.worldWidthKm / 2, SQUARE.cellsDown * SQUARE.cellHeightKm / 2,
            roughnessOverRadius = 0.25)
        expect("natural island cut along a row", read("cut", BooleanArray(SQUARE.cellCount) {
            island[it] && SQUARE.rowOf(it) >= centreRow - 40
        }, SQUARE), setOf(Detector.ALIGNED_SIDE), setOf(Detector.FACETS, Detector.CREASES, Detector.RECTANGLE) + corners)
        expect("natural island cut along the diagonal", read("cut", BooleanArray(SQUARE.cellCount) {
            island[it] && SQUARE.columnOf(it) + SQUARE.rowOf(it) >= centreColumn + centreRow - 30
        }, SQUARE), setOf(Detector.ALIGNED_SIDE), setOf(Detector.FACETS, Detector.CREASES, Detector.RECTANGLE) + corners)
        // Not among the brief's stamps; shown for what catches it, nothing asserted.
        expect("octagonal window", read("octagon", Controls.octagon(SQUARE, 40.0, centreColumn, centreRow), SQUARE),
            emptySet(), Detector.entries.toSet())

        // A pyramid, turned off the grid: its level lines are rhombi whose straight sides meet at
        // its ridges, the crease the detector is for.
        val cx = SQUARE.worldWidthKm / 2
        val cy = SQUARE.cellsDown * SQUARE.cellHeightKm / 2
        val turn = Math.toRadians(17.0)
        val pyramid = sampled(SQUARE) { x, y ->
            val u = (x - cx) * cos(turn) + (y - cy) * sin(turn)
            val v = -(x - cx) * sin(turn) + (y - cy) * cos(turn)
            -(abs(u) + 1.6 * abs(v))
        }
        val pyramidLines = listOf(-300f, -600f, -900f, -1200f).flatMap { Contours.ofField(pyramid, it, SQUARE) }
        expect("a pyramid's level lines, turned 17 deg", readLines(Layer("pyramid", pyramidLines), SQUARE),
            setOf(Detector.CREASES), setOf(Detector.FACETS, Detector.ALIGNED_SIDE, Detector.RECTANGLE, Detector.ISOTROPY, Detector.ORIENTATION) + corners)
        // An ice sheet's plastic profile over a natural island, the square root of the distance to
        // its margin: its level lines are the margin's offsets, curved, and meet at the divide.
        val sheetIsland = Controls.naturalIsland(SQUARE, 61L, 40 * SQUARE.cellWidthKm, cx, cy)
        val margin = (0 until SQUARE.cellCount).filter { cell ->
            !sheetIsland[cell] && listOf(-1, 1, -SQUARE.cellsAcross, SQUARE.cellsAcross).any { step ->
                val next = cell + step
                next in 0 until SQUARE.cellCount && sheetIsland[next]
            }
        }
        val profile = FloatArray(SQUARE.cellCount) { cell ->
            if (!sheetIsland[cell]) return@FloatArray 0f
            val x = (SQUARE.columnOf(cell) + 0.5) * SQUARE.cellWidthKm
            val y = (SQUARE.rowOf(cell) + 0.5) * SQUARE.cellHeightKm
            kotlin.math.sqrt(margin.minOf { lengthOf((SQUARE.columnOf(it) + 0.5) * SQUARE.cellWidthKm - x, (SQUARE.rowOf(it) + 0.5) * SQUARE.cellHeightKm - y) }).toFloat()
        }
        val highest = profile.max()
        val profileLines = (1..6).flatMap { Contours.ofField(profile, highest * it / 7f, SQUARE) }
        expect("a sheet's profile over a natural island (shown, nothing asserted)", readLines(Layer("sheet", profileLines), SQUARE),
            emptySet(), Detector.entries.toSet())
        lines.add("  its creases: " + readLines(Layer("sheet", profileLines), SQUARE).describe(Detector.CREASES))

        // Populations, for the one detector that reads a layer rather than a component.
        expect("union of 12-cell blocks", read("blocks", Controls.blockUnion(BIG, 21L, 12, 0.35, 90 * BIG.cellWidthKm), BIG),
            setOf(Detector.ISOTROPY), setOf(Detector.RECTANGLE, Detector.ALIGNED_SIDE, Detector.FACETS, Detector.COMBS, Detector.CREASES) + corners)
        // A smooth field — nothing finer than sixteen cells, as a blurred climate field is — whose
        // level lines are round wherever it is locally a paraboloid: read as a rough outline the
        // arc detector finds them, and read as the smooth field it is, its arcs come at the smooth
        // controls' rate.
        // Read clear of the seam, which the noise does not wrap across.
        val bigBand = BooleanArray(BIG.cellCount) { BIG.columnOf(it) in 3 until BIG.cellsAcross - 3 }
        for (finest in listOf(16, 32, 64)) {
            val noise = IsotropicNoise(83L, finest * BIG.cellWidthKm, 480 * BIG.cellWidthKm)
            val field = sampled(BIG) { x, y -> noise.at(x, y) }
            val smoothOutlines = listOf(-1f, -0.5f, 0f, 0.5f, 1f).flatMap { Contours.ofField(field, it, BIG, bigBand) }
            lines.add("  a field smooth below $finest cells, its level lines read as rough outlines: " +
                readLines(Layer("smooth", smoothOutlines), BIG).describe(Detector.ARCS))
            expect("a field smooth below $finest cells, as a smooth field", readLines(Layer("smooth", smoothOutlines, smoothField = true), BIG), emptySet())
        }

        // Concentric terraces: a cone stamped into a field, its level lines four circles about one
        // centre, flagged even where single arcs are held to a rate.
        val cone = FloatArray(SQUARE.cellCount) { cell ->
            val dx = SQUARE.columnOf(cell) + 0.5 - centreColumn
            val dy = SQUARE.rowOf(cell) + 0.5 - centreRow
            kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
        }
        val terraces = listOf(12f, 16f, 20f, 24f).flatMap { Contours.ofField(cone, it, SQUARE) }
        expect("concentric terraces, as a smooth field", readLines(Layer("terraces", terraces, smoothField = true), SQUARE),
            setOf(Detector.ARCS), setOf(Detector.FACETS, Detector.CREASES))

        // Lobes: the grid's eight steps against any bearing at all.
        val lobeRadiusKm = 5.0 * BIG.cellWidthKm
        val gridSteps = doubleArrayOf(0.0, BIG.diagonalDegrees, 90.0, 180.0 - BIG.diagonalDegrees)
        expect("lobes facing the grid's eight steps", read("lobes", Controls.lobeField(BIG, 41L, 22, lobeRadiusKm) { random ->
            gridSteps[random.nextInt(4)] + if (random.nextBoolean()) 180.0 else 0.0
        }, BIG), setOf(Detector.ORIENTATION), setOf(Detector.ARCS, Detector.RECTANGLE, Detector.ISOTROPY, Detector.ALIGNED_SIDE, Detector.FACETS, Detector.CREASES))
        expect("lobes facing any bearing", read("lobes", Controls.lobeField(BIG, 41L, 22, lobeRadiusKm) { random ->
            random.nextDouble() * 360.0
        }, BIG), emptySet(), setOf(Detector.ARCS, Detector.RECTANGLE, Detector.FACETS, Detector.CREASES))

        // A coast inked on every shore, and one inked only where the water lies east or south.
        val land = Controls.naturalField(FRAME, 71L, 0.4, 60 * FRAME.cellWidthKm)
        fun water(cell: Int, dx: Int, dy: Int): Boolean {
            val row = FRAME.rowOf(cell) + dy
            if (row < 0 || row >= FRAME.cellsDown) return false
            return !land[row * FRAME.cellsAcross + (FRAME.columnOf(cell) + dx + FRAME.cellsAcross) % FRAME.cellsAcross]
        }
        val everyShore = BooleanArray(FRAME.cellCount) { land[it] && (water(it, 1, 0) || water(it, -1, 0) || water(it, 0, 1) || water(it, 0, -1)) }
        val eastAndSouth = BooleanArray(FRAME.cellCount) { land[it] && (water(it, 1, 0) || water(it, 0, 1)) }
        fun inked(name: String, ink: BooleanArray) = readLines(Layer(name, emptyList(), facing = FacingShares.of(land, ink, FRAME)), FRAME)
        expect("a coast inked on every shore", inked("every shore", everyShore), emptySet())
        expect("a coast inked east and south only", inked("east and south", eastAndSouth), setOf(Detector.FACING))
        lines.add("  " + inked("east and south", eastAndSouth).describe(Detector.FACING))

        // Open lines: natural courses, the same routed by the steepest of eight, and natural courses
        // with one reach laid along a row and one along the grid's diagonal, which the local tests
        // find however many natural courses surround them.
        val natural = Controls.naturalCourses(BIG, 31L, 600, 300, 6.0)
        expect("natural courses", readLines(Layer("courses", natural, openLines = true), BIG), emptySet())
        val routed = Controls.eightNeighbourCourses(BIG, 31L, 600, 300, 6.0)
        expect("eight-neighbour courses", readLines(Layer("d8", routed, openLines = true), BIG),
            setOf(Detector.ISOTROPY), setOf(Detector.COMBS, Detector.ARCS, Detector.ALIGNED_SIDE, Detector.FACETS, Detector.CREASES) + corners)
        val alongARow = Outline(DoubleArray(150) { (300 + it + 0.5) * BIG.cellWidthKm }, DoubleArray(150) { 500.5 * BIG.cellHeightKm }, closed = false, belt = false)
        val alongADiagonal = Outline(DoubleArray(130) { (600 + it + 0.5) * BIG.cellWidthKm }, DoubleArray(130) { (300 + it + 0.5) * BIG.cellHeightKm }, closed = false, belt = false)
        expect("natural courses and one reach along a row", readLines(Layer("courses", natural + alongARow, openLines = true), BIG),
            setOf(Detector.ALIGNED_SIDE), setOf(Detector.FACETS))
        expect("natural courses and one reach along the diagonal", readLines(Layer("courses", natural + alongADiagonal, openLines = true), BIG),
            setOf(Detector.ALIGNED_SIDE), setOf(Detector.FACETS))

        // Following the latitude: the zonal control's own level lines, from another seed than the
        // null's, read as a latitude-following field is; and read as if isotropy were the null.
        val zonalCanvas = ZonalFigures.canvasFor(FRAME)
        lines.add("  the zonal null at 512: " + ZonalFigures.of(FRAME))
        for (finest in listOf(2, 16)) {
            val zonal = ZonalFigures.levelLines(zonalCanvas, 7L, finest)
            expect("zonal lines finest $finest cells, following the latitude",
                readLines(Layer("zonal", zonal, smoothField = finest > 2, followsLatitude = true), zonalCanvas), emptySet())
            val asIsotropic = readLines(Layer("zonal", zonal, smoothField = finest > 2), zonalCanvas)
            lines.add(row("  the same, held to an isotropic null", asIsotropic))
            lines.add("    " + asIsotropic.describe(Detector.ISOTROPY))
        }

        // Ruled lines at two grids: a comb fixed on the ground doubles its spacing in cells at a grid
        // twice as fine and is let stand; one fixed in cells keeps it and stands as a violation.
        val fineSquare = GridFrame(512, 512, SQUARE.cellWidthKm / 2, SQUARE.cellHeightKm / 2)
        val coarseComb = read("comb", Controls.comb(SQUARE, 9, 6.0, 2.0, 60.0, centreColumn, centreRow, 0.0), SQUARE)
        val groundComb = read("comb", Controls.comb(fineSquare, 9, 12.0, 4.0, 120.0, 2 * centreColumn, 2 * centreRow, 0.0), fineSquare)
        val cellComb = read("comb", Controls.comb(fineSquare, 9, 6.0, 2.0, 60.0, 2 * centreColumn, 2 * centreRow, 0.0), fineSquare)
        for ((name, fine) in listOf("fixed on the ground" to groundComb, "fixed in cells" to cellComb)) {
            if (fine.combs.isEmpty()) failures.add("the comb $name was not found at the finer grid")
            val pairings = Census.pairCombs(fine, fineSquare, coarseComb, SQUARE)
            fine.exemptGroundFixed(pairings.filter { it.groundFixed }.map { it.comb })
            pairings.forEach { lines.add("  comb $name: ${it.line}") }
        }
        val combCompanions = setOf(Detector.ALIGNED_SIDE, Detector.RECTANGLE, Detector.FACETS, Detector.ISOTROPY, Detector.CREASES) + corners
        expect("ruled comb fixed on the ground, at two grids", groundComb, emptySet(), combCompanions)
        expect("ruled comb fixed in cells, at two grids", cellComb, setOf(Detector.COMBS), combCompanions)

        println(lines.joinToString("\n", prefix = "GEOMETRY MATRIX\n"))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private val ISOTROPY_BINS = 36
    private val ISOTROPY_SAMPLES = 36_000
    private val NEAR_LEVEL = 0.02
}
