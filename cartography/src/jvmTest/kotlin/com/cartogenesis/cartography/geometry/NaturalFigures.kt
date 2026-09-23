package com.cartogenesis.cartography.geometry

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * One per-place statistic's tail over the natural controls — the longest aligned stretch, straight
 * run or crease in a window — and the bar it sets.
 *
 * The bar is where a natural window passes with the chance the census allows one place
 * ([Judge.placeExceedance]), which is far out beyond anything a control ensemble can hold, so the
 * tail is modelled. Past the controls' [TAIL_SHARE] quantile the excess is taken as exponential,
 * with its scale the mean excess (the peaks-over-threshold estimate, Davison and Smith 1990): the
 * Gumbel domain, where the maxima of Gaussian fields fall, since a line drawn from a Gaussian field
 * staying straight, or on one row, for a length is a persistence event whose chance falls off
 * exponentially with that length. The bar is never under the largest value any control reached.
 */
internal class Tail(values: List<Double>) {
    val count = values.size
    private val sorted = values.sorted()
    val largest: Double = sorted.lastOrNull() ?: 0.0

    /** The [TAIL_SHARE] quantile, where the tail is taken to start. */
    val threshold: Double = if (sorted.isEmpty()) 0.0 else sorted[((1 - TAIL_SHARE) * (sorted.size - 1)).toInt()]

    /** The mean excess over [threshold]: the exponential tail's scale. */
    val scale: Double = sorted.filter { it > threshold }.let { above -> if (above.isEmpty()) 0.0 else above.average() - threshold }

    /** Where a natural value passes with chance [exceedance]. */
    fun bar(exceedance: Double): Double = maxOf(largest, threshold + scale * ln(TAIL_SHARE / exceedance))

    override fun toString(): String =
        "n %d, 95th percentile %.2f, mean excess %.2f, largest %.2f".format(count, threshold, scale, largest)

    companion object {
        /** A twentieth: in the smallest ensemble read, the smooth one of some seven hundred windows, thirty-six excesses. */
        const val TAIL_SHARE = 0.05
    }
}

/**
 * The natural rings' fill, bounded by 1, taken as normal on the logit scale; its bar is floored
 * at [ComponentShapes.RECTANGLE_FILL].
 */
internal class FillTail(fills: List<Double>) {
    private val logits = fills.map { logit(it) }
    val count = fills.size
    val mean: Double = if (logits.isEmpty()) 0.0 else logits.average()
    val spread: Double = Statistics.standardDeviation(logits.toDoubleArray())
    val largest: Double = fills.maxOrNull() ?: 0.0

    fun bar(z: Double): Double = maxOf(ComponentShapes.RECTANGLE_FILL, largest, logistic(mean + z * spread))

    override fun toString(): String = "n %d, logit mean %.2f, sd %.2f, largest %.3f".format(count, mean, spread, largest)

    companion object {
        fun logit(share: Double): Double {
            val clamped = share.coerceIn(1e-6, 1 - 1e-6)
            return ln(clamped / (1 - clamped))
        }

        fun logistic(value: Double): Double = 1.0 / (1.0 + exp(-value))
    }
}

/** What a place is held to: the bars on each per-place statistic, in their own units. */
internal class PlaceBars(
    /** Steps of the grid along one grid line. */
    val alignedSteps: Double,
    /** Cell widths of one straight run. */
    val runCellWidths: Double,
    /** See [ComponentShapes.creaseStrength]. */
    val creaseStrength: Double,
    /** Area over the smallest bounding rectangle, for a ring whose rectangle lies along the grid. */
    val fill: Double
) {
    override fun toString(): String =
        "aligned side %.1f steps, straight run %.1f cell widths, crease %.2f, fill %.3f".format(alignedSteps, runCellWidths, creaseStrength, fill)
}

/**
 * What the natural controls read on this map's cells: the tails the per-place bars are held to,
 * and the natural rates of right angles and arcs the layer-wide rates are held to.
 *
 * Three classes of natural line ([LineClass]), because a layer is compared with the kind of line it
 * is. The **rough** controls are Mandelbrot's coast ([IsotropicNoise], dimension 1.25): outlines
 * of thresholded fields isotropic on the ground and on the sheet, and level lines of the same
 * noise. The **course** controls are walks traced from cell to cell ([Controls.naturalCourses]),
 * as a river is, which run along a row wherever their heading lies near one: two and three times
 * the aligned stretch any outline makes, so a river is not held to an outline's bar nor an outline
 * to a river's. The **smooth** controls are the same noise with nothing
 * finer than 16, 32 and 64 cells, the way a blurred climate field is smooth: their level lines are
 * long and gently curved, round where the field is locally a paraboloid and kinked where a line
 * passes close to a saddle. Every line is read inside a band clear of the canvas's east and west
 * edges and ends where the band does, so no control is cut by a straight edge it did not make.
 * Nothing here reads a world.
 *
 * Every figure is in cells or cell widths and the noise's wavelengths are in cells, so the tails
 * are the same at every grid of this map's cell shape (a cell twice as wide as tall) and are read
 * once. `GeometryControlTest` prints each tail, part by part, beside the bar it sets.
 */
internal class NaturalTails private constructor(
    val fill: FillTail,
    val aligned: Tail,
    val run: Tail,
    val crease: Tail,
    /** Right-angle corners per 1000 cell widths of natural line, one added as a prior. */
    val cornersPer1000CellWidths: Double,
    /** Circular arcs per 1000 cell widths of natural line, one added as a prior. */
    val arcsPer1000CellWidths: Double,
    /** Natural arcs found, before the prior: none on the rough class, or the arc bar is wrong. */
    val arcs: Int,
    /** Natural windows holding a corner pair: none, or the corner bar is wrong. */
    val windowsWithCornerPairs: Int,
    val windows: Int,
    val lineCellWidths: Double,
    /** Each part of the ensemble's own tails, for the control test to print. */
    val parts: List<String>
) {
    fun bars(judge: Judge): PlaceBars = PlaceBars(
        alignedSteps = aligned.bar(judge.placeExceedance),
        runCellWidths = run.bar(judge.placeExceedance),
        creaseStrength = crease.bar(judge.placeExceedance),
        fill = fill.bar(judge.zPlace)
    )

    override fun toString(): String =
        ("%d windows over %.0f cell widths: fill %s; aligned %s; run %s; crease %s; %.3f right angles and %.3f arcs " +
            "per 1000 cell widths (%d arcs); %d windows with a corner pair").format(
            windows, lineCellWidths, fill, aligned, run, crease, cornersPer1000CellWidths, arcsPer1000CellWidths,
            arcs, windowsWithCornerPairs
        )

    companion object {
        private val SEEDS = longArrayOf(11L, 23L, 37L, 53L)
        private val ROTATIONS = doubleArrayOf(0.0, 29.0)
        private val SMOOTHNESS_CELLS = intArrayOf(16, 32, 64)
        private val LEVELS = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)

        /** The rough controls' square, in cells, and the smooth ones', whose lines are longer. */
        private const val ROUGH_SIDE_CELLS = 384
        private const val SMOOTH_SIDE_CELLS = 512

        /** Any cell width will do, since every figure is in cells; this is the default world's at 512. */
        private const val CELL_WIDTH_KM = 12000.0 / 512

        private val cache = HashMap<String, NaturalTails>()

        /** The tails for [frame]'s cell shape, of [lines]' class. */
        @Synchronized
        fun of(frame: GridFrame, lines: LineClass): NaturalTails =
            cache.getOrPut("%.6f/%s".format(frame.cellHeightKm / frame.cellWidthKm, lines)) { build(canvasFor(frame, lines), lines) }

        /** The controls' own grid, of [frame]'s cell shape. */
        fun canvasFor(frame: GridFrame, lines: LineClass): GridFrame {
            val side = if (lines == LineClass.SMOOTH) SMOOTH_SIDE_CELLS else ROUGH_SIDE_CELLS
            return GridFrame(side, side, CELL_WIDTH_KM, CELL_WIDTH_KM * frame.cellHeightKm / frame.cellWidthKm)
        }

        /** Every line of the class on [canvas], by the part of the ensemble it belongs to. */
        fun linesOf(canvas: GridFrame, lines: LineClass): Map<String, List<Outline>> {
            val parts = LinkedHashMap<String, List<Outline>>()
            // The canvas wraps east to west and the noise does not, so every line is read inside a
            // band clear of the seam; it ends where the band does, which makes it an open line.
            val band = BooleanArray(canvas.cellCount) { canvas.columnOf(it) in 3 until canvas.cellsAcross - 3 }
            fun levelLines(noise: IsotropicNoise): List<Outline> {
                val field = sampled(canvas) { x, y -> noise.at(x, y) }
                return LEVELS.flatMap { Contours.ofField(field, it, canvas, band) }
            }
            if (lines == LineClass.SMOOTH) {
                for (finest in SMOOTHNESS_CELLS) {
                    parts["level lines smooth below $finest cells"] = SEEDS.flatMap { seed ->
                        levelLines(IsotropicNoise(seed * 5 + finest, finest * canvas.cellWidthKm, 2.0 * canvas.cellsAcross * canvas.cellWidthKm))
                    }
                }
            } else if (lines == LineClass.ROUGH) {
                for (inCells in listOf(false, true)) {
                    parts["outlines isotropic ${if (inCells) "on the sheet" else "on the ground"}"] = SEEDS.flatMap { seed ->
                        ROTATIONS.flatMap { rotation ->
                            val unitsAcross = if (inCells) canvas.cellWidthKm else 1.0
                            val unitsDown = if (inCells) canvas.cellHeightKm else 1.0
                            val noise = IsotropicNoise(
                                seed, 2 * canvas.cellWidthKm / unitsAcross, 60 * canvas.cellWidthKm / unitsAcross,
                                rotation, unitsAcross, unitsDown
                            )
                            val field = sampled(canvas) { x, y -> noise.at(x, y) }
                            // Thresholded so that about a third is inside, a country of lakes and islands.
                            val level = field.sorted()[((1 - COVER_SHARE) * (field.size - 1)).toInt()]
                            Contours.ofMask(BooleanArray(field.size) { field[it] >= level }, canvas, band)
                        }
                    }
                }
                parts["level lines"] = SEEDS.flatMap { seed -> levelLines(IsotropicNoise(seed * 3 + 1, 2 * canvas.cellWidthKm, 120 * canvas.cellWidthKm)) }
            }
            if (lines == LineClass.COURSE) parts["courses"] = Controls.naturalCourses(canvas, 91L, 600, 200, 6.0)
            return parts
        }

        /** How much of a thresholded control is inside. */
        private const val COVER_SHARE = 0.35

        private fun build(canvas: GridFrame, lines: LineClass): NaturalTails {
            val parts = linesOf(canvas, lines)
            val halfWindowKm = 0.5 * ComponentShapes.WINDOW_CELL_WIDTHS * canvas.cellWidthKm
            val allWindows = ArrayList<ComponentShapes.Window>()
            val windows = ArrayList<ComponentShapes.Window>()
            val rings = ArrayList<ComponentShapes.Ring>()
            val summaries = ArrayList<String>()
            var arcs = 0
            var cellWidths = 0.0
            for ((name, outlines) in parts) {
                val all = ComponentShapes.windows(outlines, canvas)
                // The bars are read off whole windows; a line shorter than half a window holds less,
                // and judging it against whole windows' tails is the stricter reading.
                val whole = all.filter { it.lengthKm >= halfWindowKm }
                val partRings = ComponentShapes.rings(outlines, canvas).filter { it.measured }
                val partArcs = Arcs.perLine(Arcs.measure(outlines, canvas), outlines.size).sum()
                summaries.add("%s: %d lines, %d windows, %d measured rings, %d arcs; aligned %s; run %s; crease %s".format(
                    name, outlines.size, whole.size, partRings.size, partArcs,
                    Tail(whole.map { it.alignedSteps }), Tail(whole.map { it.runCellWidths }),
                    Tail(whole.filter { it.creaseJunctions > 0 }.map { it.creaseStrength })))
                allWindows.addAll(all)
                windows.addAll(whole)
                rings.addAll(partRings)
                arcs += partArcs
                cellWidths += outlines.sumOf { it.lengthKm() } / canvas.cellWidthKm
            }
            return NaturalTails(
                fill = FillTail(rings.map { it.fill }),
                aligned = Tail(windows.map { it.alignedSteps }),
                run = Tail(windows.map { it.runCellWidths }),
                crease = Tail(windows.filter { it.creaseJunctions > 0 }.map { it.creaseStrength }),
                cornersPer1000CellWidths = (allWindows.sumOf { it.rightAngles } + 1) * 1000.0 / cellWidths,
                arcsPer1000CellWidths = (arcs + 1) * 1000.0 / cellWidths,
                arcs = arcs,
                windowsWithCornerPairs = allWindows.count { it.cornerPairs > 0 },
                windows = windows.size,
                lineCellWidths = cellWidths,
                parts = summaries
            )
        }
    }
}

/** A field over [frame], [at] asked at each cell's centre in kilometres, cells in parallel. */
internal fun sampled(frame: GridFrame, at: (Double, Double) -> Double): FloatArray {
    val field = FloatArray(frame.cellCount)
    IntStream.range(0, frame.cellCount).parallel().forEach { cell ->
        field[cell] = at((frame.columnOf(cell) + 0.5) * frame.cellWidthKm, (frame.rowOf(cell) + 0.5) * frame.cellHeightKm).toFloat()
    }
    return field
}

/**
 * The null for a layer that follows the latitude: a zonal field with Earth's own east-west
 * variation, whose level lines prefer the east-west bearing for a real reason.
 *
 * Temperature falls [POLEWARD_GRADIENT_K_PER_DEGREE] toward each pole and varies along each
 * parallel as isotropic natural noise of standard deviation [ALONG_THE_PARALLEL_K], so a level line
 * wanders `3.75 / 0.75` = 5 degrees of latitude either way in standard deviation. That is Earth's
 * at the latitudes where isotherms and the ice's edge run: at about 61 N, Bergen (about +8 C in the
 * annual normals) and Yakutsk (about -9 C) stand some 16 K apart, about the span between the
 * two-sigma extremes of variation of standard deviation 3.75 K; and the annual 0 C isotherm
 * reaches from the Norwegian coast near 71 N to eastern Siberia near 50 N, twenty degrees, four
 * standard deviations of its wander at 0.75 K a degree, the mid-latitude gradient of the annual
 * zonal mean. The gradient is held constant where Earth's flattens in the tropics, which makes
 * this field more zonal than Earth's there and so a more lenient null. The noise runs from 2 cells
 * (or, for a smooth field like the generator's blurred climate, from 16) to 4,000 km, a continent.
 *
 * Measured, the null comes out at 1.0 to 1.1 on every bearing at 512 and 2048: at the chord's
 * length the along-parallel variation turns a level line more than the poleward gradient holds it,
 * so an Earth-like zonal line does not prefer the row at that scale, and a layer that does, by the
 * effect size, is not following the latitude the way Earth's lines do.
 *
 * Its level lines every [LEVEL_STEP_K] are read by the same chord reading as a layer's, on a
 * canvas of the census's own cells from pole to pole, and the ratio each grid bearing's bin reaches
 * over its neighbours — the larger of the rough and the smooth field's, and never under 1 — is the
 * null a latitude-following layer is held to in place of 1: a zonal line is a row artefact only
 * when it prefers the row by the effect size beyond what Earth-like zonal lines do.
 */
internal class ZonalFigures private constructor(
    val ratios: DoubleArray,
    val rough: DoubleArray,
    val smooth: DoubleArray,
    private val bearings: DoubleArray
) {

    override fun toString(): String = bearings.indices.joinToString("; ") {
        "%.1f deg: null %.2f (rough %.2f, smooth %.2f)".format(bearings[it], ratios[it], rough[it], smooth[it])
    }

    companion object {
        const val POLEWARD_GRADIENT_K_PER_DEGREE = 0.75
        const val ALONG_THE_PARALLEL_K = 3.75
        const val LEVEL_STEP_K = 5.0
        private const val CONTINENT_KM = 4000.0
        private const val EQUATOR_C = 30.0

        /** The widest canvas read, in columns: half the world at 2048, all of it at 1024 and under. */
        private const val WIDEST_CANVAS = 1024

        private val cache = HashMap<String, ZonalFigures>()

        @Synchronized
        fun of(frame: GridFrame): ZonalFigures = cache.getOrPut("${frame.cellsDown}/${frame.cellWidthKm}/${frame.cellHeightKm}") {
            val canvas = canvasFor(frame)
            val rough = BearingIsotropy.measureChords(levelLines(canvas, 131L, 2), canvas, 1).map { it.ratio }.toDoubleArray()
            val smooth = BearingIsotropy.measureChords(levelLines(canvas, 131L, 16), canvas, 1).map { it.ratio }.toDoubleArray()
            ZonalFigures(DoubleArray(4) { maxOf(1.0, rough[it], smooth[it]) }, rough, smooth, canvas.gridBearings)
        }

        fun canvasFor(frame: GridFrame): GridFrame =
            GridFrame(minOf(frame.cellsAcross, WIDEST_CANVAS), frame.cellsDown, frame.cellWidthKm, frame.cellHeightKm)

        /** The zonal field's level lines over [canvas], its noise no finer than [finestCells]. */
        fun levelLines(canvas: GridFrame, seed: Long, finestCells: Int): List<Outline> {
            val noise = IsotropicNoise(seed, finestCells * canvas.cellWidthKm, CONTINENT_KM)
            val heightKm = canvas.cellsDown * canvas.cellHeightKm
            val field = sampled(canvas) { x, y ->
                val latitude = 90.0 - y / heightKm * 180.0
                EQUATOR_C - POLEWARD_GRADIENT_K_PER_DEGREE * abs(latitude) + ALONG_THE_PARALLEL_K * noise.at(x, y)
            }
            // Clear of the canvas's seam, where the noise does not wrap.
            val band = BooleanArray(canvas.cellCount) { canvas.columnOf(it) in 3 until canvas.cellsAcross - 3 }
            return (-7..5).flatMap { Contours.ofField(field, (it * LEVEL_STEP_K).toFloat(), canvas, band) }
        }
    }
}
