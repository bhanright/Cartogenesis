package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.DistanceTransform
import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * G4's guard: the distance field the pipeline reads has round contours, and the chamfer transform
 * it replaced does not.
 *
 * Three measurements, in order of how much they abstract away:
 *
 *  1. **Exactness.** Jump flooding is only worth the passes if what it produces really is the
 *     Euclidean distance, so it is set against a brute-force nearest-source search: on scattered
 *     random sources, and on sources shaped like the land masks the pipeline floods from, where it
 *     is not exact today (a known failure, recorded where it is asserted).
 *  2. **A single seed cell.** One source in an empty grid, the iso-contour at a given radius read
 *     at sixteen bearings, and the eighth Fourier harmonic of those sixteen radii relative to their
 *     mean — the same measurement `BoundaryPairTest` uses on hotspot cones. A perfect circle reads
 *     zero; an octagon reads several percent. This is the cleanest statement of what changed,
 *     because nothing but the metric is in it.
 *  3. **Seed 42's shelf.** On a real coastline the sixteen-bearing read says nothing — the radius
 *     from any centre is dominated by the shape of the coast, not by the metric — so the question
 *     is asked of the distance itself, bearing by bearing: the distance the shelf stage used,
 *     read back off the depth it drew, against the true distance to the land nearest each cell.
 *     A round contour is flat across the bearings and an octagonal one is a cosine with eight
 *     lobes, so the eighth harmonic of that error is the facet, in the same units — a fraction of
 *     the radius — as the lone-source measurement above.
 */
class JumpFloodDistanceTest : BorrowsSharedWorlds() {

    private companion object {
        /** How far a distance may sit from brute force and still be the same distance, in cells. */
        const val FLOAT_ROUNDING_CELLS = 1e-3

        /**
         * The fewest plateau cells the shelf case reads its harmonic over. Sixty-four bearings, and
         * a few dozen cells each before a bin's mean is a mean.
         */
        const val MIN_PLATEAU_CELLS = 2_000
    }

    /** The floor the plan asks for: an eight-fold component under 1% of the radius. */
    private val roundnessFloor = 0.01

    /** A row as tall as a column is wide, which is what a distance in plain cells assumes. */
    private val squareCells = 1.0

    /** A row half as tall as a column is wide: this project's grids on a 2:1 world. */
    private val equirectangularCells = 0.5

    /**
     * Exact against brute force on scattered sources, on square cells and on this project's own
     * 2:1 ones.
     *
     * The row scale is the whole of the anisotropic case: at 1 the flood measures in cells and the
     * brute force does too, and at a half a step down the map is worth half a step across it, in
     * the comparisons the flood makes as much as in the distance it finally reports. Getting the
     * second right is not free — a nearer source in cells can be the further one on the ground, so
     * the flood has to carry the scale through every pass rather than scaling the answer at the end
     * — and brute force is what says whether it did. Four fixed draws of two dozen sources: what
     * the flood does on the sources the pipeline actually hands it is the case below.
     */
    @Test
    fun `jump flooding is exact against a brute-force nearest source on scattered sources`() {
        // Two shapes, one square and one not, both with a wrapping X axis and a hard Y edge; and
        // two row scales, cells and the 2:1 cells of an equirectangular map.
        listOf(64 to 64, 97 to 53).forEach { (w, h) ->
            listOf(squareCells, equirectangularCells).forEach { rowScale ->
                val rnd = Random(20260912)
                val sources = ArrayList<Int>()
                repeat(24) { sources.add(rnd.nextInt(w * h)) }
                val seeds = sources.distinct().sorted()

                val dist = FloatArray(w * h) { JumpFloodDistance.INFINITE }
                val label = IntArray(w * h) { -1 }
                seeds.forEach { dist[it] = 0f; label[it] = it }
                JumpFloodDistance.run(w, h, dist, label, rowScale)

                fun distanceFrom(source: Int, x: Int, y: Int): Double {
                    var dx = abs(x - source % w)
                    dx = min(dx, w - dx)
                    val dy = (y - source / w) * rowScale
                    return sqrt(dx * dx + dy * dy)
                }

                var worst = 0.0
                var worstAt = -1
                for (i in 0 until w * h) {
                    val x = i % w
                    val y = i / w
                    var best = Double.MAX_VALUE
                    for (s in seeds) {
                        val d = distanceFrom(s, x, y)
                        if (d < best) best = d
                    }
                    val error = abs(best - dist[i])
                    if (error > worst) { worst = error; worstAt = i }
                    // The label must name a source that really is that far away, or the boundary
                    // profiles would read the wrong pair's ground.
                    assertTrue(
                        abs(distanceFrom(label[i], x, y) - dist[i]) < 1e-3,
                        "$w x $h at row scale $rowScale, cell $i: label ${label[i]} is not at the" +
                            " reported distance ${dist[i]}"
                    )
                }
                println(
                    ("JFA $w x $h, ${seeds.size} sources, a row worth %.2f of a column: worst" +
                        " error against brute force %.6f cell widths (at $worstAt)")
                        .format(rowScale, worst)
                )
                assertTrue(
                    worst < 1e-3,
                    "$w x $h at row scale $rowScale: jump flooding was off by $worst cell widths"
                )
            }
        }
    }

    /**
     * Exact against brute force on the sources the pipeline hands it: every land cell of a mask
     * shaped like a coastline, and the two three-source layouts a second reading of the flood found
     * it wrong on.
     *
     * `JumpFloodDistance` says the plain flood "finds it exact, not merely close", and the scattered
     * draws above are all that claim rested on. The shelf, the belts and the ice all flood from a
     * land mask, whose sources come in a solid body with a ragged edge, so that is what is drawn
     * here: blobs of a smooth random field on two grid shapes, at both row scales, four seeds each.
     * The two layouts are the ones the audit found by hand — on a 64-cell square grid, sources at
     * cells 1254, 298 and 1503 at a row scale of a half, where cell 224 is handed 298 at the root of
     * 100.25 though 1254 stands exactly ten widths away; and sources 3750, 2670 and 2798 at a row
     * scale of one, where cell 4032 is handed a squared distance of 724 against a true 701. Whatever
     * the halving schedule cannot reach is counted here and the clause fails on the first cell off
     * by more than float rounding.
     *
     * It fails today, and that is Audit III's finding A-I11:
     * the flood is wrong on a few cells in several thousand, by a fraction of a cell width. It is
     * kept running as a known failure rather than loosened, and the fix that makes the flood exact —
     * or the change that states it as approximate with a bound — turns it back on.
     */
    @Test
    fun `jump flooding is exact on a coastline's sources and on the known counterexamples`() {
        KnownFailures.expect(
            "A-I11: the plain jump flood is not exact on land-mask sources",
            Signature(11, 0, 63, 0.4308)
        ) {
            val misses = ArrayList<Miss>()
            misses += nearestSourceMisses(64, 64, listOf(1254, 298, 1503), equirectangularCells, "three sources, row scale 0.5")
            misses += nearestSourceMisses(64, 64, listOf(3750, 2670, 2798), squareCells, "three sources, row scale 1")
            listOf(96 to 96, 128 to 64).forEach { (w, h) ->
                listOf(squareCells, equirectangularCells).forEach { rowScale ->
                    (1L..4L).forEach { seed ->
                        val land = blobMask(w, h, seed)
                        val sources = (0 until w * h).filter { land[it] }
                        misses += nearestSourceMisses(w, h, sources, rowScale, "$w x $h blobs seed $seed, row scale $rowScale")
                    }
                }
            }
            val worst = misses.maxByOrNull { it.errorCells }
            println(
                "JFA coastline sources: ${misses.size} cells off the brute-force nearest source" +
                    (worst?.let { ", worst %.4f cell widths at (%d,%d) on %s".format(it.errorCells, it.column, it.row, it.fixture) } ?: "")
            )
            misses.groupBy { it.fixture }.forEach { (fixture, off) -> println("JFA   $fixture: ${off.size} cells off") }
            GuardViolation.unless(
                worst == null,
                { Signature(misses.size, worst!!.column, worst.row, worst.errorCells) }
            ) {
                "jump flooding handed ${misses.size} cells a source that is not their nearest, the " +
                    "worst by ${worst!!.errorCells} cell widths at (${worst.column},${worst.row}) on ${worst.fixture}"
            }
        }
    }

    private class Miss(val fixture: String, val column: Int, val row: Int, val errorCells: Double)

    /**
     * Every cell [JumpFloodDistance.run] answers with a distance off the true nearest of [sources]
     * by more than float rounding, the truth being brute force over the sources that can be
     * nearest: a source with every neighbour a source is never the nearest to anything outside, so
     * only the edge of a solid mask is searched.
     */
    private fun nearestSourceMisses(
        w: Int,
        h: Int,
        sources: List<Int>,
        rowScale: Double,
        fixture: String
    ): List<Miss> {
        val isSource = BooleanArray(w * h).also { mask -> sources.forEach { mask[it] = true } }
        val dist = FloatArray(w * h) { if (isSource[it]) 0f else JumpFloodDistance.INFINITE }
        val label = IntArray(w * h) { if (isSource[it]) it else -1 }
        JumpFloodDistance.run(w, h, dist, label, rowScale)

        val edge = sources.filter { cell ->
            val x = cell % w
            val y = cell / w
            (-1..1).any { dy ->
                (-1..1).any { dx ->
                    val ny = y + dy
                    ny in 0 until h && !isSource[ny * w + (x + dx + w) % w]
                }
            }
        }
        val misses = ArrayList<Miss>()
        for (cell in 0 until w * h) {
            if (isSource[cell]) continue
            val x = cell % w
            val y = cell / w
            var best = Double.MAX_VALUE
            for (source in edge) {
                var dx = abs(x - source % w)
                dx = min(dx, w - dx)
                val dy = (y - source / w) * rowScale
                val d = sqrt(dx.toDouble() * dx + dy * dy)
                if (d < best) best = d
            }
            val error = abs(best - dist[cell])
            if (error > FLOAT_ROUNDING_CELLS) misses += Miss(fixture, x, y, error)
        }
        return misses
    }

    /**
     * Land shaped like a coastline: a smooth random field on a lattice eight cells apart, blended
     * between its corners and cut at its middle, so the land comes in bodies with ragged edges.
     */
    private fun blobMask(w: Int, h: Int, seed: Long): BooleanArray {
        val lattice = 8
        val across = w / lattice + 1
        val down = h / lattice + 2
        val random = Random(seed)
        val corners = DoubleArray(across * down) { random.nextDouble() }
        fun corner(i: Int, j: Int) = corners[(j.coerceAtMost(down - 1)) * across + i % across]
        return BooleanArray(w * h) { cell ->
            val u = (cell % w).toDouble() / lattice
            val v = (cell / w).toDouble() / lattice
            val i = u.toInt()
            val j = v.toInt()
            val fu = u - i
            val fv = v - j
            val top = corner(i, j) * (1 - fu) + corner(i + 1, j) * fu
            val bottom = corner(i, j + 1) * (1 - fu) + corner(i + 1, j + 1) * fu
            top * (1 - fv) + bottom * fv > 0.5
        }
    }

    @Test
    fun `a lone source has round contours where the chamfer transform has octagonal ones`() {
        val size = 256
        val radius = 40.0

        fun field(transform: (Int, Int, FloatArray, IntArray) -> Unit): FloatArray {
            val dist = FloatArray(size * size) { JumpFloodDistance.INFINITE }
            val label = IntArray(size * size) { -1 }
            val centre = (size / 2) * size + size / 2
            dist[centre] = 0f
            label[centre] = centre
            transform(size, size, dist, label)
            return dist
        }

        // Sixteen bearings out from the source, stopping at the cell where the field first reads
        // past `radius`; nearest-cell sampling, the way every stage reads a field.
        fun radii(dist: FloatArray): DoubleArray {
            val cx = (size / 2).toFloat()
            val cy = (size / 2).toFloat()
            return DoubleArray(16) { k ->
                val theta = 2.0 * PI * k / 16.0
                val dx = cos(theta).toFloat()
                val dy = sin(theta).toFloat()
                var r = 0f
                while (r < size / 3f) {
                    val xi = (cx + dx * r).toInt().coerceIn(0, size - 1)
                    val yi = (cy + dy * r).toInt().coerceIn(0, size - 1)
                    if (dist[yi * size + xi] > radius) break
                    r += 0.05f
                }
                r.toDouble()
            }
        }

        val jfa = eightFoldRelativeAmplitude(radii(field(JumpFloodDistance::run)))
        val chamfer = eightFoldRelativeAmplitude(radii(field(DistanceTransform::run)))
        println(
            "G4 lone source, iso-contour at $radius cells read at 16 bearings: " +
                "jump flood eight-fold amplitude %.4f, chamfer %.4f".format(jfa, chamfer)
        )

        assertTrue(
            jfa < roundnessFloor,
            "the jump-flooded field's contour has an eight-fold component of $jfa, " +
                "wanted under $roundnessFloor"
        )
        assertTrue(
            chamfer > roundnessFloor,
            "the chamfer control should fail the same measurement; it read $chamfer"
        )
    }

    /**
     * Seed 42's shelf, as the stage drew it, follows a round contour of its coast.
     *
     * Read off the stage's own output rather than off a field rebuilt beside it. Across the plateau
     * `SeaLevelStage` lays its wedge at a depth linear in the distance it measured to land — from
     * `SHELF_DEPTH_AT_COAST_METRES` at the coast to the shelf break at the shelf's width — and keeps
     * the wedge wherever it stands above the unshelved floor. So on every water cell where the
     * drawn floor is above the floor the same cut leaves with the shelf switched off, and no deeper
     * than the break, the distance the stage used can be read straight back off the depth it wrote.
     * Each is set against the true Euclidean distance to the nearest land by brute force over the
     * land around it, and two things are asked of the difference: that it is float rounding and no
     * more, which is what a cell that slipped past the flood or a metric other than Euclid's would
     * break; and that its eighth harmonic by bearing is under the plan's floor, which is the round
     * contour the name promises. The chamfer transform's field on the same cells fails the second.
     *
     * In cells, because that is what the stage asks the flood for. That the shelf it draws is half as
     * wide north-south as east-west on the ground is Audit III's C7, and `ContinentalShelfTest`
     * measures the shelf in kilometres.
     */
    @Test
    fun `seed 42's shelf break follows a round contour`() {
        val config = WorldGenConfig(seed = 42L, width = 512, height = 512)
        val world = SharedWorlds.world(config)
        val w = world.width
        val h = world.height
        // The cut the engine makes, with the shelf and without it: the stage's own two answers.
        val drawn = SeaLevelStage.apply(world.erosion.height, config)
        val unshelved = SeaLevelStage.apply(
            world.erosion.height, config.copy(sea = config.sea.copy(shelfWidthKm = 0.0))
        )
        assertTrue(drawn.isLand.contentEquals(unshelved.isLand), "the shelf moved the coast, so the two cuts are not comparable")
        val shelfCells = config.cellsFor(config.sea.shelfWidthKm).toDouble()
        val coastDepth = config.scale.depthShareOfMetres(SeaLevelStage.SHELF_DEPTH_AT_COAST_METRES).toDouble()
        val breakDepth = -config.scale.depthShareOfMetres(config.sea.shelfDepthMetres).toDouble()
        val window = shelfCells.toInt() + 2

        val chamfer = FloatArray(w * h) { if (drawn.isLand[it]) 0f else JumpFloodDistance.INFINITE }
        DistanceTransform.run(w, h, chamfer, IntArray(w * h) { if (drawn.isLand[it]) it else -1 })

        // Every plateau cell, binned by the bearing from the land cell actually nearest it, carrying
        // how far the stage's distance and the chamfer's sit from the true one, as a share of it.
        val bins = 64
        val stageSum = DoubleArray(bins)
        val chamferSum = DoubleArray(bins)
        val count = IntArray(bins)
        var plateauCells = 0
        var worstError = 0.0
        var worstAt = -1
        var offCells = 0
        for (cell in 0 until w * h) {
            if (drawn.isLand[cell]) continue
            val floor = drawn.relativeElevation.data[cell].toDouble()
            if (floor <= unshelved.relativeElevation.data[cell] || floor < breakDepth) continue
            val stageDistance = (floor - coastDepth) / (breakDepth - coastDepth) * shelfCells
            val x = cell % w
            val y = cell / w
            var truth = Double.MAX_VALUE
            var towardX = 0
            var towardY = 0
            for (dy in -window..window) {
                val row = y + dy
                if (row < 0 || row >= h) continue
                for (dx in -window..window) {
                    if (!drawn.isLand[row * w + (x + dx + w) % w]) continue
                    val d = sqrt((dx * dx + dy * dy).toDouble())
                    if (d < truth) { truth = d; towardX = dx; towardY = dy }
                }
            }
            if (truth == Double.MAX_VALUE) continue
            plateauCells++
            val error = abs(stageDistance - truth)
            if (error > FLOAT_ROUNDING_CELLS) offCells++
            if (error > worstError) { worstError = error; worstAt = cell }
            val bearing = atan2(-towardY.toDouble(), -towardX.toDouble())
            var bin = ((bearing + PI) / (2 * PI) * bins).toInt()
            if (bin >= bins) bin = bins - 1
            stageSum[bin] += (stageDistance - truth) / truth
            chamferSum[bin] += (chamfer[cell] - truth) / truth
            count[bin]++
        }
        if (plateauCells < MIN_PLATEAU_CELLS) {
            throw InsufficientSample("seed 42 has $plateauCells plateau cells where the wedge governs, under $MIN_PLATEAU_CELLS")
        }
        val stageEightFold = eightFoldByBearing(stageSum, count)
        val chamferEightFold = eightFoldByBearing(chamferSum, count)
        println(
            ("G4 seed 42 at 512: %d plateau cells read back off the shelf the stage drew; %d of them" +
                " off the true distance by more than rounding, the worst by %.6f cells at (%d,%d);" +
                " eight-fold component %.5f of the radius against the chamfer's %.4f")
                .format(
                    plateauCells, offCells, worstError, worstAt % w, worstAt / w,
                    stageEightFold, chamferEightFold
                )
        )
        assertTrue(
            offCells == 0,
            "$offCells cells of seed 42's shelf stand at a distance from land that is not the true " +
                "one, the worst by $worstError cells at (${worstAt % w},${worstAt / w})"
        )
        assertTrue(
            stageEightFold < roundnessFloor,
            "the shelf the stage drew has an eight-fold component of $stageEightFold, over $roundnessFloor"
        )
        assertTrue(
            chamferEightFold > roundnessFloor,
            "the chamfer control should fail the same measurement; it read $chamferEightFold"
        )
    }

    /** The eighth harmonic, relative to the radius, of a relative error binned by bearing. */
    private fun eightFoldByBearing(sum: DoubleArray, count: IntArray): Double {
        val bins = sum.size
        val filled = (0 until bins).filter { count[it] > 0 }
        if (filled.isEmpty()) return 0.0
        val error = DoubleArray(bins) { if (count[it] == 0) 0.0 else sum[it] / count[it] }
        val mean = filled.sumOf { error[it] } / filled.size
        var re = 0.0
        var im = 0.0
        filled.forEach { bin ->
            val theta = 2 * PI * bin / bins
            re += (error[bin] - mean) * cos(8 * theta)
            im += (error[bin] - mean) * sin(8 * theta)
        }
        return 2.0 * sqrt(re * re + im * im) / filled.size
    }

    /**
     * What the exactness costs, at the three resolutions the app offers. Reported, not asserted:
     * a timing that failed a build would fail it on whatever else the machine was doing.
     *
     * The mask is a band of land across the middle of the grid, so every cell has a nearest source
     * at a realistic distance and the flood has real work to do at every step of its schedule.
     */
    @Test
    fun `reports what the transform costs at each resolution`() {
        listOf(512, 1024, 2048).forEach { size ->
            val land = BooleanArray(size * size) { i ->
                val y = i / size
                y > size * 4 / 10 && y < size * 6 / 10
            }

            fun time(transform: (Int, Int, FloatArray, IntArray) -> Unit): Double {
                var best = Double.MAX_VALUE
                repeat(3) {
                    val dist = FloatArray(size * size) { JumpFloodDistance.INFINITE }
                    val label = IntArray(size * size) { -1 }
                    for (i in 0 until size * size) {
                        if (land[i]) { dist[i] = 0f; label[i] = i }
                    }
                    val started = System.nanoTime()
                    transform(size, size, dist, label)
                    val took = (System.nanoTime() - started) / 1e6
                    if (took < best) best = took
                }
                return best
            }

            val jfa = time(JumpFloodDistance::run)
            val chamfer = time(DistanceTransform::run)
            println(
                "G4 timing at $size: jump flood %.0f ms, chamfer %.0f ms (%.1fx), %d cores"
                    .format(jfa, chamfer, jfa / chamfer, Runtime.getRuntime().availableProcessors())
            )
        }
    }

    /** DFT magnitude at the eighth harmonic of a 16-sample series, relative to its mean. */
    private fun eightFoldRelativeAmplitude(radii: DoubleArray): Double {
        val n = radii.size
        val mean = radii.average()
        var re = 0.0
        var im = 0.0
        for (k in 0 until n) {
            val theta = 2.0 * PI * 8 * k / n
            re += radii[k] * cos(theta)
            im += radii[k] * sin(theta)
        }
        val amp = 2.0 * sqrt(re * re + im * im) / n
        return if (mean == 0.0) 0.0 else amp / mean
    }
}
