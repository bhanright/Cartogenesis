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
 * and the square, diamond and octagon a breadth-first or windowed operator draws. Not every
 * detector is asked to catch every stamp: the matrix printed below says which catches which, and
 * the assertions hold each stamp to the detectors built for it and every natural control to none.
 */
class GeometryControlTest {

    private companion object {
        /** The default world's grid at 512: 12,000 km by 6,000 km over 512 cells each way. */
        val FRAME = GridFrame.of(WorldGenConfig(width = 512, height = 512))

        /**
         * The census the per-merge tier runs: four worlds, the layers `MapLayers` lists, and
         * [GeometryGuard.TESTS_PER_LAYER] tests on each. The controls are held to the same
         * corrected level a world is.
         */
        val FAMILY = Census.familySize(4)

        /** A smaller square of the same cells, for the per-component controls. */
        val SQUARE = GridFrame(256, 256, FRAME.cellWidthKm, FRAME.cellHeightKm)

        /** A larger one, for the populations the layer-wide isotropy test reads. */
        val BIG = GridFrame(1024, 1024, FRAME.cellWidthKm, FRAME.cellHeightKm)
    }

    private fun read(name: String, mask: BooleanArray, frame: GridFrame, twice: Boolean = false): LayerReading =
        GeometryGuard.read(Layer(name, Contours.ofMask(mask, frame), twice), frame, FAMILY, NaturalFigures.of(frame).cornersPer1000Km)

    private fun readMany(name: String, masks: List<BooleanArray>, frame: GridFrame): LayerReading =
        GeometryGuard.read(Layer(name, masks.flatMap { Contours.ofMask(it, frame) }), frame, FAMILY, NaturalFigures.of(frame).cornersPer1000Km)

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
        val middleRings = ComponentShapes.measure(Contours.ofMask(middle, SQUARE), SQUARE).filter { it.measured }
        val seamRings = ComponentShapes.measure(Contours.ofMask(onSeam, SQUARE), SQUARE).filter { it.measured }
        val middleBig = middleRings.maxBy { it.areaCells }
        val seamBig = seamRings.maxBy { it.areaCells }
        lines.add("island in the middle: ${middleBig.describe(SQUARE)}")
        lines.add("island on the seam:   ${seamBig.describe(SQUARE)}")
        if (abs(middleBig.areaCells - seamBig.areaCells) > 1e-6 || abs(middleBig.fill - seamBig.fill) > 1e-9 ||
            abs(middleBig.longestRunKm - seamBig.longestRunKm) > 1e-6
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

        // A stamp turned off the grid: still a rectangle, no longer aligned.
        for (turn in listOf(0.0, 3.0, 7.0, 23.0, 41.0)) {
            val ring = ComponentShapes.measure(Contours.ofMask(
                Controls.rectangle(SQUARE, 60.0, 30.0, SQUARE.cellsAcross / 2.0, SQUARE.cellsDown / 2.0, turn), SQUARE), SQUARE).single()
            lines.add("rectangle turned %4.1f deg: fill %.3f, %s, aligned side %s, %d corner pairs".format(
                turn, ring.fill, if (ring.alignedRectangle) "aligned" else "not aligned",
                if (ring.hasLongAlignedSide) "flagged" else "clear", ring.cornerPairs))
            if (!ring.isRectangle) failures.add("the rectangle turned $turn deg is no longer read as a rectangle")
            if (turn == 0.0 && !ring.isAlignedStamp) failures.add("the rectangle on the grid is not flagged as aligned")
            if (turn >= 3.0 && (ring.alignedRectangle || ring.hasCornerPair)) failures.add("the rectangle turned $turn deg is still flagged for alignment")
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
        // A violation is recorded under its finding, and the clause passes.
        KnownFailures.expect("control finding", { throw GeometryViolation("a stamped rectangle") }, sink)
        assertEquals(listOf("control finding: a stamped rectangle"), recorded)
        // A clause that no longer fails fails the helper, naming the finding.
        val fixed = assertFailsWith<AssertionError> { KnownFailures.expect("control finding", { }, sink) }
        assertEquals("control finding fixed: arm this clause", fixed.message)
        // Everything else goes through untouched: too little data, another assertion, a setup error.
        assertFailsWith<InsufficientSample> {
            KnownFailures.expect("control finding", { throw InsufficientSample("three runs") }, sink)
        }
        val other = assertFailsWith<AssertionError> {
            KnownFailures.expect("control finding", { assertEquals(1, 2) }, sink)
        }
        assertTrue(other !is GeometryViolation && other.message?.contains("fixed") != true)
        assertFailsWith<IllegalStateException> {
            KnownFailures.expect("control finding", { error("the world did not generate") }, sink)
        }
        assertEquals(1, recorded.size, "only the violation was recorded")
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
     * The per-component bars that are taken from the control ensemble, shown against it: every
     * ring of an ensemble of natural islands — three sizes of roughness, isotropic on the ground
     * and on the sheet, at many rotations and radii — measured, and the largest figure any of them
     * reaches printed beside the bar it sets.
     */
    @Test
    fun `the natural ensemble sits below every bar it sets`() {
        val rings = ArrayList<ComponentShapes.Ring>()
        var arcs = 0
        val arcNotes = ArrayList<String>()
        val arcRadii = ArrayList<Pair<Double, Double>>()
        val shortArmPairs = ArrayList<Double>()
        var outlineKm = 0.0
        val random = Random(17)
        for (roughness in listOf(0.25, 0.4, 0.6)) for (inCells in listOf(false, true)) for (index in 0 until 30) {
            val radiusCells = 4.0 + random.nextDouble() * 41.0
            val mask = Controls.naturalIsland(
                SQUARE, 900L + index * 7 + (roughness * 100).toLong(), radiusCells * SQUARE.cellWidthKm,
                SQUARE.worldWidthKm / 2, SQUARE.cellsDown * SQUARE.cellHeightKm / 2,
                rotationDegrees = random.nextDouble() * 180.0, isotropicInCells = inCells, roughnessOverRadius = roughness
            )
            val outlines = Contours.ofMask(mask, SQUARE)
            outlineKm += outlines.sumOf { it.lengthKm() }
            for (outline in outlines.filter { it.isRing }) {
                val corners = LatticeRuns.corners(LatticeRuns.of(outline, SQUARE, 0.0), outline.vertexCount, true, SQUARE, minimumSteps = 2.0)
                for (side in LatticeRuns.cornerPairs(corners)) {
                    val before = corners.filter { it.second === side }.maxOf { it.first.steps }
                    val after = corners.filter { it.first === side }.maxOf { it.second.steps }
                    shortArmPairs.add(minOf(side.steps, before, after))
                }
            }
            rings.addAll(ComponentShapes.measure(outlines, SQUARE))
            val found = Arcs.measure(outlines, SQUARE)
            arcs += found.arcs.size
            found.arcs.forEach {
                arcNotes.add("r=%.1f roughness %.2f: %s".format(radiusCells, roughness, it.describe(SQUARE)))
                arcRadii.add(it.radiusCells to it.rmsCells)
            }
        }
        val fieldRings = ArrayList<ComponentShapes.Ring>()
        for (inCells in listOf(false, true)) for (rotation in listOf(0.0, 17.0, 45.0, 71.0)) {
            val mask = Controls.naturalField(FRAME, 101L + rotation.toLong(), 0.35, 60 * FRAME.cellWidthKm,
                rotationDegrees = rotation, isotropicInCells = inCells)
            fieldRings.addAll(ComponentShapes.measure(Contours.ofMask(mask, FRAME), FRAME))
        }
        val measured = rings.filter { it.measured }
        val worstFill = measured.maxBy { it.fill }
        val forRuns = rings.filter { it.measuredForRuns }
        val worstFacet = forRuns.maxBy { it.longestRunKm / it.facetAllowanceKm }
        val worstSide = forRuns.maxBy { it.longestAlignedKm / it.alignedAllowanceKm }
        println("GEOMETRY ENSEMBLE %d rings, %d measured, %.0f km of outline".format(rings.size, measured.size, outlineKm))
        println("GEOMETRY ENSEMBLE fill: worst %.3f (%s) against the bar %.2f".format(worstFill.fill, worstFill.describe(SQUARE), ComponentShapes.RECTANGLE_FILL))
        println("GEOMETRY ENSEMBLE facets: worst %.3f of the allowance (%s)".format(
            worstFacet.longestRunKm / worstFacet.facetAllowanceKm, worstFacet.describe(SQUARE)))
        println("GEOMETRY ENSEMBLE aligned side: worst %.3f of the allowance (%s)".format(
            worstSide.longestAlignedKm / worstSide.alignedAllowanceKm, worstSide.describe(SQUARE)))
        println("GEOMETRY ENSEMBLE corner pairs: %d rings; arcs: %d".format(measured.count { it.hasCornerPair }, arcs))
        arcNotes.take(10).forEach { println("GEOMETRY ENSEMBLE arc $it") }
        println("GEOMETRY ENSEMBLE arcs by radius: " + arcRadii.groupBy { minOf(it.first.toInt(), 16) }.toSortedMap()
            .map { (radius, list) -> "%d: %d (least rms %.2f)".format(radius, list.size, list.minOf { it.second }) }.joinToString("; "))
        val pairSteps = (rings + fieldRings).filter { it.cornerPairs > 0 }.map { it.cornerPairRunSteps }.sorted()
        println("GEOMETRY ENSEMBLE corner pairs at %.0f steps and more: $pairSteps".format(LatticeRuns.CORNER_STEPS))
        println("GEOMETRY ENSEMBLE corner pairs at 2 steps and more, by their shortest arm: " +
            shortArmPairs.groupBy { it.toInt() }.toSortedMap().map { (steps, list) -> "$steps: ${list.size}" }.joinToString("; "))
        for (radius in listOf(4.0, 6.0, 8.0, 10.0, 12.0, 16.0, 24.0)) for (inCells in listOf(false, true)) {
            val disc = Controls.disc(SQUARE, if (inCells) radius else radius * SQUARE.cellWidthKm,
                if (inCells) SQUARE.cellsAcross / 2.0 + 0.3 else SQUARE.worldWidthKm / 2 + 0.3 * SQUARE.cellWidthKm,
                if (inCells) SQUARE.cellsDown / 2.0 + 0.2 else SQUARE.cellsDown * SQUARE.cellHeightKm / 2 + 0.2 * SQUARE.cellHeightKm, inCells)
            val found = Arcs.measure(Contours.ofMask(disc, SQUARE), SQUARE).arcs
            println("GEOMETRY ENSEMBLE disc %s radius %.0f: %d arcs, %s".format(if (inCells) "on the sheet" else "on the ground", radius, found.size,
                found.joinToString("; ") { "%s %.1f cells %.0f deg rms %.2f".format(it.frame, it.radiusCells, it.coverageDegrees, it.rmsCells) }))
        }
        for (bound in listOf(64, 128, 256, 512, 1024, 4096)) {
            val band = rings.filter { it.areaCells >= bound && it.rectangleShortCells >= ComponentShapes.MINIMUM_WIDTH_CELLS }
            if (band.isEmpty()) continue
            println("GEOMETRY ENSEMBLE from %5d cells: %3d rings, worst fill %.3f, worst facet %.3f, worst side %.3f".format(
                bound, band.size, band.maxOf { it.fill }, band.maxOf { it.longestRunKm / it.facetAllowanceKm },
                band.maxOf { it.longestAlignedKm / it.alignedAllowanceKm }))
        }
        assertTrue(measured.none { it.isRectangle }, "a natural ring filled its rectangle: ${worstFill.describe(SQUARE)}")
        assertTrue(measured.none { it.hasFacets }, "a natural ring has a facet: ${worstFacet.describe(SQUARE)}")
        assertTrue(measured.none { it.hasLongAlignedSide }, "a natural ring has a long aligned side: ${worstSide.describe(SQUARE)}")
        assertTrue(measured.none { it.hasCornerPair }, "a natural ring has an aligned corner pair")
        assertEquals(0, arcs, "natural outlines fitted circles:\n" + arcNotes.take(10).joinToString("\n"))
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
            if (missed.isNotEmpty()) failures.add("$name: missed by ${missed.joinToString()}")
            if (extra.isNotEmpty()) failures.add("$name: flagged by ${extra.joinToString()} — " +
                extra.joinToString(" | ") { reading.describe(it) })
        }

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
            setOf(Detector.RECTANGLE, Detector.ALIGNED_SIDE, Detector.RIGHT_ANGLES), setOf(Detector.FACETS))
        expect("rectangle 60x30 turned 23 deg", read("rect turned", Controls.rectangle(SQUARE, 60.0, 30.0, centreColumn, centreRow, 23.0), SQUARE),
            setOf(Detector.RECTANGLE), setOf(Detector.FACETS))
        expect("square window mask", read("window", Controls.squareWindowMask(FRAME, 4L, 9, 40 * FRAME.cellWidthKm), FRAME),
            setOf(Detector.RIGHT_ANGLES), setOf(Detector.RECTANGLE, Detector.ALIGNED_SIDE, Detector.ISOTROPY, Detector.FACETS))
        expect("chessboard staircase, steps of 8", read("stairs", Controls.chessboardStaircase(SQUARE, 8, 120, 60, 60), SQUARE),
            setOf(Detector.RIGHT_ANGLES), setOf(Detector.ALIGNED_SIDE, Detector.COMBS, Detector.RECTANGLE, Detector.FACETS))
        expect("half-disc on the sheet", read("half disc", Controls.halfDisc(SQUARE, 40.0, centreColumn, centreRow, 30.0, inCells = true), SQUARE),
            setOf(Detector.ARCS), setOf(Detector.FACETS, Detector.ALIGNED_SIDE))
        expect("half-disc on the ground", read("half disc km", Controls.halfDisc(SQUARE, 40.0 * SQUARE.cellWidthKm,
            SQUARE.worldWidthKm / 2, SQUARE.cellsDown * SQUARE.cellHeightKm / 2, 30.0, inCells = false), SQUARE),
            setOf(Detector.ARCS), setOf(Detector.FACETS, Detector.ALIGNED_SIDE))
        val voronoi = readMany("voronoi", Controls.voronoiCells(BIG, 3L, 14, 960, 32, 32), BIG)
        lines.add("  nearest-seed partition's facets: " + voronoi.describe(Detector.FACETS))
        expect("nearest-seed partition", voronoi, setOf(Detector.FACETS), setOf(Detector.RECTANGLE, Detector.ALIGNED_SIDE))
        expect("ruled comb along a row, 6 cells", read("comb", Controls.comb(SQUARE, 9, 6.0, 2.0, 60.0, centreColumn, centreRow, 0.0), SQUARE),
            setOf(Detector.COMBS), setOf(Detector.ALIGNED_SIDE, Detector.RIGHT_ANGLES, Detector.RECTANGLE, Detector.FACETS, Detector.ISOTROPY))
        expect("ruled comb at 33 deg, 7 cells", read("comb turned", Controls.comb(SQUARE, 9, 7.0, 2.0, 60.0, centreColumn, centreRow, 33.0), SQUARE),
            setOf(Detector.COMBS), setOf(Detector.RECTANGLE, Detector.FACETS))
        expect("diamond (four-connected ring)", read("diamond", Controls.diamond(SQUARE, 40.0, centreColumn, centreRow), SQUARE),
            setOf(Detector.RECTANGLE, Detector.RIGHT_ANGLES), setOf(Detector.ALIGNED_SIDE, Detector.FACETS))
        expect("square (eight-connected ring)", read("chebyshev", Controls.chebyshevSquare(SQUARE, 30.0, centreColumn, centreRow), SQUARE),
            setOf(Detector.RECTANGLE, Detector.RIGHT_ANGLES, Detector.ALIGNED_SIDE), setOf(Detector.FACETS))
        // Not among the brief's stamps; shown for what catches it, nothing asserted.
        expect("octagonal window", read("octagon", Controls.octagon(SQUARE, 40.0, centreColumn, centreRow), SQUARE),
            emptySet(), Detector.entries.toSet())

        // Populations, for the one detector that reads a layer rather than a component.
        expect("union of 12-cell blocks", read("blocks", Controls.blockUnion(BIG, 21L, 12, 0.35, 90 * BIG.cellWidthKm), BIG),
            setOf(Detector.ISOTROPY), setOf(Detector.RIGHT_ANGLES, Detector.RECTANGLE, Detector.ALIGNED_SIDE, Detector.FACETS, Detector.COMBS))
        // A smooth field — nothing finer than sixteen cells, as a blurred climate field is — whose
        // level lines are round wherever it is locally a paraboloid: read as a rough outline the
        // arc detector finds them, and read as the smooth field it is, only concentric sets count.
        for (finest in listOf(16, 32, 64)) {
            val field = FloatArray(BIG.cellCount)
            val noise = IsotropicNoise(83L, finest * BIG.cellWidthKm, 480 * BIG.cellWidthKm)
            for (cell in field.indices) {
                field[cell] = noise.at((BIG.columnOf(cell) + 0.5) * BIG.cellWidthKm, (BIG.rowOf(cell) + 0.5) * BIG.cellHeightKm).toFloat()
            }
            val smoothOutlines = listOf(-1f, -0.5f, 0f, 0.5f, 1f).flatMap { Contours.ofField(field, it, BIG) }
            val smoothAsRough = GeometryGuard.read(Layer("smooth", smoothOutlines), BIG, FAMILY, NaturalFigures.of(BIG).cornersPer1000Km)
            lines.add("  a field smooth below $finest cells, its level lines read as rough outlines: " + smoothAsRough.describe(Detector.ARCS))
            expect("a field smooth below $finest cells, as a smooth field", GeometryGuard.read(Layer("smooth", smoothOutlines, smoothField = true),
                BIG, FAMILY, NaturalFigures.of(BIG).cornersPer1000Km), emptySet())
        }

        // Concentric terraces: a cone stamped into a field, its level lines four circles about one
        // centre, flagged even where single arcs are forgiven.
        val cone = FloatArray(SQUARE.cellCount) { cell ->
            val dx = SQUARE.columnOf(cell) + 0.5 - centreColumn
            val dy = SQUARE.rowOf(cell) + 0.5 - centreRow
            kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
        }
        val terraces = listOf(12f, 16f, 20f, 24f).flatMap { Contours.ofField(cone, it, SQUARE) }
        expect("concentric terraces, as a smooth field", GeometryGuard.read(Layer("terraces", terraces, smoothField = true), SQUARE, FAMILY,
            NaturalFigures.of(SQUARE).cornersPer1000Km), setOf(Detector.ARCS), setOf(Detector.FACETS))

        // Lobes: the grid's eight steps against any bearing at all, two thousand of each.
        val lobeRadiusKm = 5.0 * BIG.cellWidthKm
        val gridSteps = doubleArrayOf(0.0, BIG.diagonalDegrees, 90.0, 180.0 - BIG.diagonalDegrees)
        expect("lobes facing the grid's eight steps", read("lobes", Controls.lobeField(BIG, 41L, 22, lobeRadiusKm) { random ->
            gridSteps[random.nextInt(4)] + if (random.nextBoolean()) 180.0 else 0.0
        }, BIG), setOf(Detector.ORIENTATION), setOf(Detector.ARCS, Detector.RECTANGLE, Detector.ISOTROPY))
        expect("lobes facing any bearing", read("lobes", Controls.lobeField(BIG, 41L, 22, lobeRadiusKm) { random ->
            random.nextDouble() * 360.0
        }, BIG), emptySet(), setOf(Detector.ARCS, Detector.RECTANGLE))

        // A coast inked on every shore, and one inked only where the water lies east or south.
        val land = Controls.naturalField(FRAME, 71L, 0.4, 60 * FRAME.cellWidthKm)
        fun water(cell: Int, dx: Int, dy: Int): Boolean {
            val row = FRAME.rowOf(cell) + dy
            if (row < 0 || row >= FRAME.cellsDown) return false
            return !land[row * FRAME.cellsAcross + (FRAME.columnOf(cell) + dx + FRAME.cellsAcross) % FRAME.cellsAcross]
        }
        val everyShore = BooleanArray(FRAME.cellCount) { land[it] && (water(it, 1, 0) || water(it, -1, 0) || water(it, 0, 1) || water(it, 0, -1)) }
        val eastAndSouth = BooleanArray(FRAME.cellCount) { land[it] && (water(it, 1, 0) || water(it, 0, 1)) }
        fun inked(name: String, ink: BooleanArray) =
            GeometryGuard.read(Layer(name, emptyList(), facing = FacingShares.of(land, ink, FRAME)), FRAME, FAMILY, 1.0)
        expect("a coast inked on every shore", inked("every shore", everyShore), emptySet())
        expect("a coast inked east and south only", inked("east and south", eastAndSouth), setOf(Detector.FACING))
        lines.add("  " + inked("east and south", eastAndSouth).describe(Detector.FACING))

        val natural = Controls.naturalCourses(BIG, 31L, 600, 300, 6.0)
        expect("natural courses", GeometryGuard.read(Layer("courses", natural), BIG, FAMILY, NaturalFigures.of(BIG).cornersPer1000Km), emptySet())
        val routed = Controls.eightNeighbourCourses(BIG, 31L, 600, 300, 6.0)
        expect("eight-neighbour courses", GeometryGuard.read(Layer("d8", routed), BIG, FAMILY, NaturalFigures.of(BIG).cornersPer1000Km),
            setOf(Detector.ISOTROPY), setOf(Detector.RIGHT_ANGLES, Detector.COMBS, Detector.ARCS))

        println(lines.joinToString("\n", prefix = "GEOMETRY MATRIX\n"))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private val ISOTROPY_BINS = 36
    private val ISOTROPY_SAMPLES = 36_000
    private val NEAR_LEVEL = 0.02
}
