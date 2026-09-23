package com.cartogenesis.cartography.geometry

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random

/** The guard's detectors, by the names the census and the known-failures lists use. */
internal enum class Detector(val label: String) {
    ISOTROPY("straight runs by bearing"),
    ALIGNED_SIDE("straight aligned side"),
    RECTANGLE("aligned rectangle"),
    FACETS("straight facets"),
    CREASES("creases"),
    RIGHT_ANGLES("square corner pairs"),
    CORNER_RATE("right-angle corners' rate"),
    ARCS("circles and arcs"),
    COMBS("ruled lines"),
    ORIENTATION("components' axes by bearing"),
    FACING("shores drawn by facing")
}

/** The grid bearings by name, in [GridFrame.gridBearings]' order. */
internal val BEARING_NAMES = listOf("east-west", "south-east diagonal", "north-south", "north-east diagonal")

/**
 * The census's two families, and the level each spends.
 *
 * The layer-wide tests — each grid bearing's isotropy and components' axes, the rates of corners
 * and of arcs, the shores' facing, [GeometryGuard.TESTS_PER_LAYER] to a layer — are one family of
 * [familySize]. The per-place searches — every window of every line for its aligned side, its
 * straight run and its crease, and every ring for its rectangle — are another, of [placeFamily]
 * places, whose bars sit at that family's level on the natural controls' tails ([NaturalTails]).
 * Each spends [Statistics.FAMILY_ERROR_RATE], so the census as a whole flags something natural at
 * most twice that often.
 */
internal class Judge(val familySize: Int, val placeFamily: Int) {
    val z: Double = Statistics.zFor(familySize)
    val zPlace: Double = Statistics.zFor(placeFamily)

    /** The chance one natural place may pass its bar: the family error spread over [placeFamily]. */
    val placeExceedance: Double = Statistics.FAMILY_ERROR_RATE / placeFamily

    override fun toString(): String = "layer tests %d at z %.2f; places %d at z %.2f".format(familySize, z, placeFamily, zPlace)

    companion object {
        /**
         * The most places one layer of one world may hold for the place family to be sized right:
         * twenty thousand windows and rings. The census fails a layer past it rather than let its
         * places go uncounted; at 2048 the longest layers hold a few thousand.
         */
        const val MOST_PLACES_PER_LAYER = 20_000

        /** The per-place statistics each place is searched for: aligned side, straight run, crease, fill. */
        const val PLACE_TESTS = 4

        fun forCensus(worlds: Int): Judge = Judge(
            worlds * Census.LAYERS * GeometryGuard.TESTS_PER_LAYER,
            worlds * Census.LAYERS * PLACE_TESTS * MOST_PLACES_PER_LAYER
        )
    }
}

/** A place on the grid past a bar, and how far past: the figure in the detector's own units. */
internal class Place(val column: Int, val row: Int, val figure: Double)

/** One detector's reading of one layer: its outcome, a line saying why, and the places past the bar. */
internal class Verdict(
    val outcome: Outcome,
    val text: String,
    /** Every place past the bar, one to a cell, for a detector that finds places. */
    val places: List<Place> = emptyList(),
    /** The layer-wide figure, for a detector that reads the layer whole. */
    val figure: Double = 0.0
) {
    val worst: Place? get() = places.maxByOrNull { it.figure }

    /** What a known failure records of this violation: how many places, the worst and its figure. */
    fun signature(): Signature = worst?.let { Signature(places.size, it.column, it.row, it.figure) }
        ?: Signature(1, -1, -1, figure)
}

/**
 * A rate of events along a layer's lines — right angles, or arcs on a smooth field — against the
 * natural controls' rate, with the events counted in blocks: they are not independent, since a
 * stamp that makes one corner makes four, so the spread is a block bootstrap's and never narrower
 * than the Poisson count's. Violation when the rate's lower bound at the layer family's level is
 * past the natural rate times the effect size.
 */
internal class RateTest(
    val count: Double,
    val cellWidths: Double,
    val lowerPer1000: Double,
    val naturalPer1000: Double,
    val minimumCellWidths: Double,
    val blocks: Int
) {
    val ratePer1000: Double get() = if (cellWidths > 0) count * 1000 / cellWidths else 0.0
    val barPer1000: Double get() = naturalPer1000 * BearingIsotropy.EFFECT_RATIO

    val outcome: Outcome
        get() = when {
            cellWidths < minimumCellWidths -> Outcome.INSUFFICIENT
            lowerPer1000 > barPer1000 -> Outcome.VIOLATION
            else -> Outcome.CLEAN
        }

    override fun toString(): String =
        "%.0f over %.0f cell widths in %d blocks, %.3f per 1000 (lower %.3f) against %.3f, the natural %.3f times %.1f%s".format(
            count, cellWidths, blocks, ratePer1000, lowerPer1000, barPer1000, naturalPer1000, BearingIsotropy.EFFECT_RATIO,
            if (outcome == Outcome.INSUFFICIENT) " [insufficient: needs %.0f cell widths]".format(minimumCellWidths) else ""
        )

    companion object {
        private const val RESAMPLES = 1000

        /**
         * [blocks] as (events, cell widths of line) each, [duplication] times over for a layer traced
         * from both sides. [eventCellWidths] is the least line one event takes: the test can bind
         * only once the count's lower bound can leave zero, at `(z / 2)^2` events on the square-root
         * scale where the Poisson spread is a half, so a layer holding fewer than one more than that
         * many events' worth of line is insufficient.
         */
        fun of(
            blocks: List<Pair<Int, Double>>,
            duplication: Int,
            naturalPer1000: Double,
            z: Double,
            eventCellWidths: Double,
            seed: Long
        ): RateTest {
            val total = blocks.sumOf { it.first }.toDouble()
            val length = blocks.sumOf { it.second }
            // On the square-root scale, the bootstrap's spread over blocks; a layer traced twice has
            // each block twice, which halves the bootstrap's variance of a count that is itself
            // doubled, and the two cancel on this scale, so the spread reads straight across.
            var spread = POISSON_SPREAD_ON_ROOTS
            if (blocks.size >= 2 && total > 0 && length > 0) {
                val random = Random(seed)
                val roots = DoubleArray(RESAMPLES) {
                    var events = 0.0
                    var line = 0.0
                    repeat(blocks.size) {
                        val block = blocks[random.nextInt(blocks.size)]
                        events += block.first
                        line += block.second
                    }
                    sqrt(if (line > 0) events / duplication * length / line else 0.0)
                }
                spread = maxOf(spread, Statistics.standardDeviation(roots))
            }
            val count = total / duplication
            val cellWidths = length / duplication
            val lowerRoot = (sqrt(count) - z * spread).coerceAtLeast(0.0)
            val lower = if (cellWidths > 0) lowerRoot * lowerRoot * 1000 / cellWidths else 0.0
            val minimum = ceil(z * z / 4 + 1) * eventCellWidths
            return RateTest(count, cellWidths, lower, naturalPer1000, minimum, blocks.size / duplication)
        }

        /** A Poisson count's standard deviation on the square-root scale. */
        private const val POISSON_SPREAD_ON_ROOTS = 0.5
    }
}

/**
 * Every detector's reading of one layer: the outcome each reaches, the figures it read, and the
 * places past its bar. Worked out once when the layer is read, so the census can drop the layer's
 * lines; only the ruled lines' verdict is worked out on demand, since pairing a world across grids
 * ([exemptGroundFixed]) can change it.
 */
internal class LayerReading(
    val layer: String,
    val frame: GridFrame,
    val outlineKm: Double,
    val isotropy: List<BearingIsotropy.Result>,
    val combs: List<Combs.Comb>,
    /** Windows and rings: the places this layer adds to the place family. */
    val places: Int,
    private val verdicts: Map<Detector, Verdict>,
    private val combEligibility: String?
) {
    private val groundFixed = HashSet<Int>()

    /** Mark the combs at [indices] as fixed on the ground: found at two grids at the same spacing in km. */
    fun exemptGroundFixed(indices: Collection<Int>) {
        groundFixed.addAll(indices)
    }

    fun verdict(detector: Detector): Verdict =
        if (detector == Detector.COMBS && verdicts[detector]?.outcome != Outcome.NOT_APPLICABLE &&
            verdicts[detector]?.outcome != Outcome.INSUFFICIENT) combVerdict()
        else verdicts.getValue(detector)

    fun outcome(detector: Detector): Outcome = verdict(detector).outcome

    fun describe(detector: Detector): String = verdict(detector).text

    private fun combVerdict(): Verdict {
        val standing = combs.indices.filter { it !in groundFixed }
        val shown = standing.map { combs[it] }.sortedByDescending { it.teeth }.take(3).joinToString("; ") { it.describe(frame) }
        val text = "%d combs, %d fixed on the ground and let stand%s".format(
            combs.size, groundFixed.size, if (shown.isEmpty()) "" else ": $shown"
        ) + (combEligibility?.let { " ($it)" } ?: "")
        if (standing.isEmpty()) return Verdict(Outcome.CLEAN, text)
        val places = standing.map { combs[it] }.map { comb ->
            val (column, row) = cellOfCells(comb.anchorXCells, comb.anchorYCells, frame)
            Place(column, row, comb.teeth.toDouble())
        }
        return Verdict(Outcome.VIOLATION, text, onePerCell(places))
    }

    /** The clause: throws [GeometryViolation] past the bar, [InsufficientSample] when unmeasurable. */
    fun assertClean(detector: Detector, world: String) {
        val verdict = verdict(detector)
        when (verdict.outcome) {
            Outcome.CLEAN, Outcome.NOT_APPLICABLE -> Unit
            Outcome.INSUFFICIENT -> throw InsufficientSample("$world $layer ${detector.label}: insufficient — ${verdict.text}")
            Outcome.VIOLATION -> throw GeometryViolation(
                "$world $layer ${detector.label}: ${verdict.signature()} ${verdict.text}", verdict.signature()
            )
        }
    }
}

/** A place in cells, from a position in cells, with the seam wrapped. */
internal fun cellOfCells(xCells: Double, yCells: Double, frame: GridFrame): Pair<Int, Int> {
    var column = xCells % frame.cellsAcross
    if (column < 0) column += frame.cellsAcross
    return column.toInt() to yCells.toInt()
}

/** The worst of [places] in each cell: a border traced from both sides is one place and not two. */
internal fun onePerCell(places: List<Place>): List<Place> =
    places.groupBy { it.column to it.row }.values.map { group -> group.maxBy { it.figure } }.sortedByDescending { it.figure }

/**
 * Runs every detector over a layer. Detector code is test code, so it has no accelerator path
 * (rule 8); what it costs a world is printed by the census.
 */
internal object GeometryGuard {

    /**
     * How many layer-wide tests one layer of one world makes, for the census's multiplicity: four
     * grid bearings of the lines' isotropy, four of the components' axes, the rate of right angles,
     * the rate of arcs on a smooth field, and the shores' facing. The per-place searches are the
     * place family's ([Judge]).
     */
    const val TESTS_PER_LAYER = 11

    fun read(layer: Layer, frame: GridFrame, judge: Judge): LayerReading {
        val outlines = layer.outlines
        val duplication = if (layer.tracedTwice) 2 else 1
        val outlineKm = outlines.sumOf { it.lengthKm() } / duplication
        val longestKm = outlines.maxOfOrNull { it.lengthKm() } ?: 0.0
        val verdicts = HashMap<Detector, Verdict>()

        verdicts[Detector.FACING] = layer.facing?.let { facing ->
            Verdict(facing.outcome(), facing.describe(), figure = facing.worstOverBest())
        } ?: Verdict(Outcome.NOT_APPLICABLE, "not a drawn line")
        val others = Detector.entries - Detector.FACING
        if (layer.facing != null && outlines.isEmpty()) {
            others.forEach { verdicts[it] = Verdict(Outcome.NOT_APPLICABLE, "read for its facing only") }
            return LayerReading(layer.name, frame, 0.0, emptyList(), emptyList(), 0, verdicts, null)
        }
        val absent = layer.absent ?: if (outlines.isEmpty()) "nothing drawn" else null
        if (absent != null) {
            others.forEach { verdicts[it] = Verdict(Outcome.INSUFFICIENT, absent) }
            return LayerReading(layer.name, frame, 0.0, emptyList(), emptyList(), 0, verdicts, null)
        }

        val tails = NaturalTails.of(frame, layer.lineClass)
        val bars = tails.bars(judge)
        val windows = ComponentShapes.windows(outlines, frame)
        val rings = ComponentShapes.rings(outlines, frame)
        val shortestStepKm = minOf(frame.cellWidthKm, frame.cellHeightKm)
        fun cell(at: Pair<Double, Double>): Pair<Int, Int> = cellOf(at, frame)
        fun tooShort(neededKm: Double, what: String): String? =
            if (longestKm >= neededKm) null
            else "no line long enough to hold %s: %.0f km needed, the longest %.0f km".format(what, neededKm, longestKm)

        // Aligned sides, straight runs and creases, window by window.
        run {
            val eligibility = tooShort(bars.alignedSteps * shortestStepKm, "a side at the bar of %.1f steps".format(bars.alignedSteps))
            val past = windows.filter { it.alignedSteps > bars.alignedSteps }
            val worst = windows.maxByOrNull { it.alignedSteps / bars.alignedSteps }
            val text = "%d windows; bar %.1f steps (%s natural); worst %s".format(
                windows.size, bars.alignedSteps, tailClass(layer),
                worst?.let { "%.1f steps along the %s at %s".format(it.alignedSteps, BEARING_NAMES.getOrElse(it.alignedBearing) { "none" }, cellAt(it.alignedAtKm.first, it.alignedAtKm.second, frame)) } ?: "none"
            )
            verdicts[Detector.ALIGNED_SIDE] = perPlace(past.map { cell(it.alignedAtKm).let { (c, r) -> Place(c, r, it.alignedSteps) } }, eligibility, text)
        }
        run {
            val eligibility = tooShort(bars.runCellWidths * frame.cellWidthKm, "a run at the bar of %.1f cell widths".format(bars.runCellWidths))
            val past = windows.filter { it.runCellWidths > bars.runCellWidths }
            val worst = windows.maxByOrNull { it.runCellWidths }
            val text = "%d windows; bar %.1f cell widths (%s natural); worst %s".format(
                windows.size, bars.runCellWidths, tailClass(layer),
                worst?.let { "%.1f cell widths (%.0f km) at %.1f deg at %s".format(it.runCellWidths, it.runCellWidths * frame.cellWidthKm, it.runBearingDegrees, cellAt(it.runAtKm.first, it.runAtKm.second, frame)) } ?: "none"
            )
            verdicts[Detector.FACETS] = perPlace(past.map { cell(it.runAtKm).let { (c, r) -> Place(c, r, it.runCellWidths) } }, eligibility, text)
        }
        run {
            // Two arms reaching the bar at the sharpest turn there is, half a turn.
            val tolerance = StraightRuns.toleranceKm(frame)
            val armKm = maxOf(bars.creaseStrength * 8 * tolerance / PI, ComponentShapes.CREASE_ARM_CELL_WIDTHS * frame.cellWidthKm)
            val eligibility = tooShort(2 * armKm, "two arms reaching the crease bar of %.2f".format(bars.creaseStrength))
                ?: if (windows.none { it.creaseJunctions > 0 }) "no two straight runs of %.0f cell widths meet".format(ComponentShapes.CREASE_ARM_CELL_WIDTHS) else null
            val past = windows.filter { it.creaseStrength > bars.creaseStrength }
            val worst = windows.maxByOrNull { it.creaseStrength }
            val text = "%d windows, %d junctions of long runs; bar %.2f (%s natural); worst %s".format(
                windows.size, windows.sumOf { it.creaseJunctions }, bars.creaseStrength, tailClass(layer),
                worst?.takeIf { it.creaseStrength > 0 }?.let { "%.2f, a %.0f deg turn at %s".format(it.creaseStrength, it.creaseTurnDegrees, cellAt(it.creaseAtKm.first, it.creaseAtKm.second, frame)) } ?: "none"
            )
            verdicts[Detector.CREASES] = perPlace(past.map { cell(it.creaseAtKm).let { (c, r) -> Place(c, r, it.creaseStrength) } }, eligibility, text)
        }

        // Rectangles: rings whose smallest rectangle on the ground lies along the grid and is filled.
        run {
            val measured = rings.filter { it.measured }
            val past = measured.filter { it.alignedRectangle && it.fill > bars.fill }
            val worst = measured.filter { it.alignedRectangle }.maxByOrNull { it.fill }
            val filledOffGrid = measured.filter { !it.alignedRectangle && it.fill > bars.fill }
            val text = "%d rings, %d measured; bar %.3f on aligned rectangles; worst aligned %s%s".format(
                rings.size, measured.size, bars.fill, worst?.describe(frame) ?: "none",
                if (filledOffGrid.isEmpty()) "" else "; %d filled rectangles off the grid, reported: %s".format(filledOffGrid.size, filledOffGrid.maxBy { it.fill }.describe(frame))
            )
            verdicts[Detector.RECTANGLE] = when {
                layer.openLines -> Verdict(Outcome.NOT_APPLICABLE, "open lines only")
                else -> perPlace(
                    past.map { ring -> cell(ring.centreXKm to ring.centreYKm).let { (c, r) -> Place(c, r, ring.fill) } },
                    if (measured.isEmpty()) "no ring %.0f cells across and %.0f in area (%d rings)".format(ComponentShapes.MINIMUM_WIDTH_CELLS, ComponentShapes.MINIMUM_AREA_CELLS, rings.size) else null,
                    text
                )
            }
        }

        // Right angles: a corner pair anywhere, and the rate of corners over the layer.
        run {
            val pairSpanKm = 3 * LatticeRuns.CORNER_STEPS * shortestStepKm
            val past = windows.filter { it.cornerPairs > 0 }
            val text = "%d windows, %d corner pairs (the natural controls make none)%s".format(
                windows.size, past.sumOf { it.cornerPairs },
                past.maxByOrNull { it.cornerPairs }?.let { "; worst %d at %s".format(it.cornerPairs, cellAt(it.cornerPairAtKm.first, it.cornerPairAtKm.second, frame)) } ?: ""
            )
            verdicts[Detector.RIGHT_ANGLES] = perPlace(
                past.map { cell(it.cornerPairAtKm).let { (c, r) -> Place(c, r, it.cornerPairs.toDouble()) } },
                tooShort(pairSpanKm, "a corner pair of three %.0f-step stretches".format(LatticeRuns.CORNER_STEPS)), text
            )
            val rate = RateTest.of(
                windows.map { it.rightAngles to it.lengthKm / frame.cellWidthKm }, duplication,
                tails.cornersPer1000CellWidths, judge.z, 2 * LatticeRuns.CORNER_STEPS * shortestStepKm / frame.cellWidthKm, 7L
            )
            verdicts[Detector.CORNER_RATE] = Verdict(rate.outcome, rate.toString(), figure = rate.ratePer1000)
        }

        // Arcs: every one on a rough line; on a smooth field, concentric sets, and the rate of the rest.
        run {
            val arcs = Arcs.measure(outlines, frame)
            val perLine = Arcs.perLine(arcs, outlines.size)
            val arcKm = Arcs.MINIMUM_RADIUS_CELLS * maxOf(frame.cellWidthKm, frame.cellHeightKm) * Math.toRadians(Arcs.MINIMUM_COVERAGE_DEGREES)
            val concentric = arcs.concentricSets.map { set ->
                val (column, row) = cellOfCells(set.first().centreXCells, set.first().centreYCells, frame)
                Place(column, row, set.size.toDouble())
            }
            val shown = arcs.arcs.sortedBy { it.rmsCells }.take(3).joinToString("; ") { it.describe(frame) }
            val found = "%d arcs, %d concentric sets%s".format(perLine.sum(), arcs.concentricSets.size, if (shown.isEmpty()) "" else ": $shown")
            verdicts[Detector.ARCS] = if (!layer.smoothField) {
                val places = arcs.arcs.map { arc -> cellOfCells(arc.centreXCells, arc.centreYCells, frame).let { (c, r) -> Place(c, r, arc.coverageDegrees) } }
                perPlace(places + concentric, tooShort(arcKm, "an arc of %.0f cells' radius over %.0f degrees".format(Arcs.MINIMUM_RADIUS_CELLS, Arcs.MINIMUM_COVERAGE_DEGREES)),
                    "$found (the rough natural controls fit none)")
            } else {
                val rate = RateTest.of(
                    outlines.indices.map { perLine[it] to outlines[it].lengthKm() / frame.cellWidthKm }, duplication,
                    tails.arcsPer1000CellWidths, judge.z, arcKm / frame.cellWidthKm, 11L
                )
                val text = "$found; isolated arcs against the smooth controls: $rate"
                when {
                    concentric.isNotEmpty() -> Verdict(Outcome.VIOLATION, text, onePerCell(concentric))
                    else -> Verdict(rate.outcome, text, figure = rate.ratePer1000)
                }
            }
        }

        // Isotropy, against 1 or, for a layer following the latitude, against the zonal control.
        val isotropy = BearingIsotropy.measureChords(outlines, frame, judge.familySize, tracedTwice = layer.tracedTwice)
        run {
            val nulls = if (layer.followsLatitude) ZonalFigures.of(frame).ratios else DoubleArray(4) { 1.0 }
            val outcomes = isotropy.map { result ->
                val minimum = BearingIsotropy.minimumBlocks(judge.z, nulls[result.bearingIndex])
                when {
                    result.blocks < minimum -> Outcome.INSUFFICIENT
                    result.ratio >= BearingIsotropy.EFFECT_RATIO * nulls[result.bearingIndex] && result.lowerRatio > nulls[result.bearingIndex] -> Outcome.VIOLATION
                    else -> Outcome.CLEAN
                }
            }
            val text = isotropy.joinToString("; ") { it.toString() } +
                if (layer.followsLatitude) " | against the zonal control's ratios " + nulls.joinToString("/") { "%.2f".format(it) } else ""
            val places = isotropy.filterIndexed { index, _ -> outcomes[index] == Outcome.VIOLATION }
                .map { Place(it.bearingIndex, -1, it.ratio) }
            verdicts[Detector.ISOTROPY] = Verdict(allOf(outcomes), text, places)
        }

        // Components' axes.
        run {
            val axes = ComponentAxes.measure(outlines, rings, frame, judge.familySize)
            val outcomes = axes.map { it.outcome }
            verdicts[Detector.ORIENTATION] = if (layer.openLines) Verdict(Outcome.NOT_APPLICABLE, "open lines only")
            else Verdict(
                allOf(outcomes), axes.joinToString("; ") { it.toString() },
                axes.filter { it.outcome == Outcome.VIOLATION }.map { Place(it.bearingIndex, -1, it.ratio) }
            )
        }

        // Ruled lines.
        val runs = StraightRuns.of(outlines, frame)
        val family = { index: Int -> outlines[index].level }
        val combs = Combs.measure(runs, frame, family)
        val teeth = Combs.mostTeeth(runs, frame, family)
        val combEligibility = "%d runs long enough to be teeth in the fullest family".format(teeth)
        verdicts[Detector.COMBS] = if (teeth < Combs.MINIMUM_TEETH && combs.isEmpty())
            Verdict(Outcome.INSUFFICIENT, "fewer than ${Combs.MINIMUM_TEETH} runs long enough to be teeth: $teeth")
        else Verdict(Outcome.CLEAN, "")

        return LayerReading(layer.name, frame, outlineKm, isotropy, combs, windows.size + rings.size, verdicts, combEligibility)
    }

    /** Which class of natural control the layer's places are held to. */
    private fun tailClass(layer: Layer): String = layer.lineClass.label

    /** Past the bar at any place; insufficient when [eligibility] says the layer cannot reach it. */
    private fun perPlace(places: List<Place>, eligibility: String?, text: String): Verdict = when {
        places.isNotEmpty() -> Verdict(Outcome.VIOLATION, text, onePerCell(places))
        eligibility != null -> Verdict(Outcome.INSUFFICIENT, "$eligibility; $text")
        else -> Verdict(Outcome.CLEAN, text)
    }

    /** A violation anywhere, else insufficient anywhere, else clean: no part passes for the whole. */
    private fun allOf(outcomes: List<Outcome>): Outcome = when {
        outcomes.any { it == Outcome.VIOLATION } -> Outcome.VIOLATION
        outcomes.isEmpty() || outcomes.any { it == Outcome.INSUFFICIENT } -> Outcome.INSUFFICIENT
        else -> Outcome.CLEAN
    }
}
