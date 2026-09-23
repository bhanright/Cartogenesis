package com.cartogenesis.cartography.geometry

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * One statistic's spread over the natural controls, in whatever transform makes it roughly normal
 * (a logarithm for a length, a logit for a share): its mean, its standard deviation and the
 * largest value any control reached.
 */
internal class Tail(val count: Int, val mean: Double, val spread: Double, val largest: Double) {

    /**
     * The bar at the one-sided quantile [z]: the larger of the normal tail there and the largest
     * value any control reached, so a bar is never set where the controls themselves already stand.
     */
    fun bar(z: Double): Double = maxOf(mean + z * spread, largest)

    override fun toString(): String = "n %d, mean %.3f, sd %.3f, largest %.3f".format(count, mean, spread, largest)

    companion object {
        fun of(values: List<Double>): Tail {
            if (values.isEmpty()) return Tail(0, 0.0, 0.0, 0.0)
            return Tail(values.size, values.average(), Statistics.standardDeviation(values.toDoubleArray()), values.max())
        }
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
 * Two classes of natural line, because a layer is compared with the kind of line it is. The
 * **rough** controls are Mandelbrot's coast ([IsotropicNoise], dimension 1.25): outlines of
 * thresholded fields isotropic on the ground and on the sheet, level lines of the same noise
 * clipped by a band, and courses wandering through the cells. The **smooth** controls are the same
 * noise with nothing finer than 16, 32 and 64 cells, the way a blurred climate field is smooth:
 * their level lines are long and gently curved, and round where the field is locally a
 * paraboloid. Nothing here reads a world.
 *
 * Every figure is in cells or cell widths and the noise's wavelengths are in cells, so the tails
 * are the same at every grid of this map's cell shape (a cell twice as wide as tall) and are read
 * once. A place's bar is the natural tail at the census's corrected level for places ([Judge]),
 * so the per-place searches share the family error the layer-wide tests spend; the tail assumes
 * the transformed statistic is normal, and the bar is never under the largest value any control
 * reached. `GeometryControlTest` prints each tail beside the bar it sets.
 */
internal class NaturalTails private constructor(
    /** Logit of the fill of every measured natural ring. */
    val fill: Tail,
    /** Log of the longest aligned stretch in a window, in grid steps. */
    val aligned: Tail,
    /** Log of the longest straight run in a window, in cell widths. */
    val run: Tail,
    /** Log of the strongest crease in a window, over the windows holding a junction of two long runs. */
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
    val rings: Int,
    val lineCellWidths: Double
) {
    fun bars(zPlace: Double): PlaceBars = PlaceBars(
        alignedSteps = exp(aligned.bar(zPlace)),
        runCellWidths = exp(run.bar(zPlace)),
        creaseStrength = exp(crease.bar(zPlace)),
        fill = maxOf(ComponentShapes.RECTANGLE_FILL, logistic(fill.bar(zPlace)))
    )

    override fun toString(): String =
        ("%d windows and %d measured rings over %.0f cell widths: fill %s; aligned %s; run %s; crease %s; " +
            "%.3f right angles and %.3f arcs per 1000 cell widths (%d arcs); %d windows with a corner pair").format(
            windows, rings, lineCellWidths, fill, aligned, run, crease, cornersPer1000CellWidths, arcsPer1000CellWidths,
            arcs, windowsWithCornerPairs
        )

    companion object {
        /** The square the controls are drawn on, in cells. */
        const val SIDE_CELLS = 256

        private val SEEDS = longArrayOf(11L, 23L, 37L)
        private val ROTATIONS = doubleArrayOf(0.0, 29.0)
        private val SMOOTHNESS_CELLS = intArrayOf(16, 32, 64)
        private val LEVELS = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)

        /** Any cell width will do, since every figure is in cells; this is the default world's at 512. */
        private const val CELL_WIDTH_KM = 12000.0 / 512

        private val cache = HashMap<String, NaturalTails>()

        /** The tails for [frame]'s cell shape, of the [smooth] or the rough class. */
        @Synchronized
        fun of(frame: GridFrame, smooth: Boolean): NaturalTails =
            cache.getOrPut("%.6f/%s".format(frame.cellHeightKm / frame.cellWidthKm, smooth)) { build(canvasFor(frame), smooth) }

        /** The controls' own grid: [SIDE_CELLS] square, of [frame]'s cell shape. */
        fun canvasFor(frame: GridFrame): GridFrame =
            GridFrame(SIDE_CELLS, SIDE_CELLS, CELL_WIDTH_KM, CELL_WIDTH_KM * frame.cellHeightKm / frame.cellWidthKm)

        /** Every line of the class, on [canvas]. */
        fun linesOf(canvas: GridFrame, smooth: Boolean): List<Outline> {
            val outlines = ArrayList<Outline>()
            // The canvas wraps east to west and the noise does not, so level lines are read inside a
            // band clear of the seam; they end where the band does, which is what makes them open.
            val band = BooleanArray(canvas.cellCount) { canvas.columnOf(it) in 3 until canvas.cellsAcross - 3 }
            fun levelLines(noise: IsotropicNoise) {
                val field = sampled(canvas) { x, y -> noise.at(x, y) }
                for (level in LEVELS) outlines.addAll(Contours.ofField(field, level, canvas, band))
            }
            if (smooth) {
                for (finest in SMOOTHNESS_CELLS) for (seed in SEEDS.take(2)) {
                    levelLines(IsotropicNoise(seed * 5 + finest, finest * canvas.cellWidthKm, 2.0 * canvas.cellsAcross * canvas.cellWidthKm))
                }
            } else {
                for (seed in SEEDS) for (rotation in ROTATIONS) for (inCells in listOf(false, true)) {
                    val mask = Controls.naturalField(canvas, seed, 0.35, 60 * canvas.cellWidthKm,
                        rotationDegrees = rotation, isotropicInCells = inCells)
                    outlines.addAll(Contours.ofMask(mask, canvas))
                }
                for (seed in SEEDS) {
                    levelLines(IsotropicNoise(seed * 3 + 1, 2 * canvas.cellWidthKm, 120 * canvas.cellWidthKm))
                }
                outlines.addAll(Controls.naturalCourses(canvas, 91L, 120, 200, 6.0))
            }
            return outlines
        }

        private fun build(canvas: GridFrame, smooth: Boolean): NaturalTails {
            val outlines = linesOf(canvas, smooth)
            val all = ComponentShapes.windows(outlines, canvas)
            // The bars are read off whole windows; a line shorter than half a window holds less, and
            // judging it against whole windows' maxima is the stricter reading.
            val windows = all.filter { it.lengthKm >= 0.5 * ComponentShapes.WINDOW_CELL_WIDTHS * canvas.cellWidthKm }
            val rings = ComponentShapes.rings(outlines, canvas).filter { it.measured }
            val cellWidths = outlines.sumOf { it.lengthKm() } / canvas.cellWidthKm
            val arcs = Arcs.measure(outlines, canvas).arcs.size
            return NaturalTails(
                fill = Tail.of(rings.map { logit(it.fill) }),
                aligned = Tail.of(windows.filter { it.alignedSteps > 0 }.map { ln(it.alignedSteps) }),
                run = Tail.of(windows.filter { it.runCellWidths > 0 }.map { ln(it.runCellWidths) }),
                crease = Tail.of(windows.filter { it.creaseStrength > 0 }.map { ln(it.creaseStrength) }),
                cornersPer1000CellWidths = (all.sumOf { it.rightAngles } + 1) * 1000.0 / cellWidths,
                arcsPer1000CellWidths = (arcs + 1) * 1000.0 / cellWidths,
                arcs = arcs,
                windowsWithCornerPairs = all.count { it.cornerPairs > 0 },
                windows = windows.size,
                rings = rings.size,
                lineCellWidths = cellWidths
            )
        }

        fun logit(share: Double): Double {
            val clamped = share.coerceIn(1e-6, 1 - 1e-6)
            return ln(clamped / (1 - clamped))
        }

        fun logistic(value: Double): Double = 1.0 / (1.0 + exp(-value))
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
 * at the latitudes where isotherms and the ice's edge run: at about 61 N, Bergen (+7 C in the annual
 * normals) and Yakutsk (-8 C) stand about 15 K apart, which natural variation of standard deviation
 * 3.75 K reaches between its two-sigma extremes, and the annual 0 C isotherm reaches from the
 * Norwegian coast near 71 N to eastern Siberia near 50 N — twenty degrees, four standard deviations
 * of its wander at 0.75 K a degree, the mid-latitude gradient of the annual zonal mean. The gradient
 * is held constant where Earth's flattens in the tropics, which makes this field more zonal than
 * Earth's there and so a more lenient null. The noise runs from 2 cells (or, for a smooth field
 * like the generator's blurred climate, from 16) to 4,000 km, a continent.
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
