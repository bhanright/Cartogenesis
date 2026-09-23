package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.DistanceTransform
import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.WorldGenConfig
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
import org.junit.Rule

/**
 * G4's guard: the distance field the pipeline reads has round contours, and the chamfer transform
 * it replaced does not.
 *
 * Three measurements, in order of how much they abstract away:
 *
 *  1. **Exactness.** Jump flooding is only worth the passes if what it produces really is the
 *     Euclidean distance; against a brute-force nearest-source search on randomly seeded grids it
 *     is, to the last bit, not merely close.
 *  2. **A single seed cell.** One source in an empty grid, the iso-contour at a given radius read
 *     at sixteen bearings, and the eighth Fourier harmonic of those sixteen radii relative to their
 *     mean — the same measurement `BoundaryPairTest` uses on hotspot cones. A perfect circle reads
 *     zero; an octagon reads several percent. This is the cleanest statement of what changed,
 *     because nothing but the metric is in it.
 *  3. **Seed 42's shelf edge.** On a real coastline the sixteen-bearing read says nothing — the
 *     radius from any centre is dominated by the shape of the coast, not by the metric — so the
 *     same question is asked of the contour's *direction* instead: the eighth harmonic of the
 *     distribution of iso-contour normals, `|mean(exp(8 i theta))|`, over the cells on the shelf
 *     break: every cell of the break, binned by the bearing from the land cell actually nearest
 *     it, carrying how far out along that bearing the chamfer field puts the contour compared with
 *     the true radius. A round contour is flat across the bearings and an octagonal one is a
 *     cosine with eight lobes, so the eighth harmonic of that curve is the facet, in the same
 *     units — a fraction of the radius — as the lone-source measurement above. The jump-flooded
 *     field is the reference the error is measured against, which it is entitled to be only
 *     because measurement 1 shows it exact; what is checked of it here is that no cell of a real
 *     coastline slipped past the flood.
 */
class JumpFloodDistanceTest {

    @get:Rule
    val sharedWorlds = SharedWorlds.Check()

    /** The floor the plan asks for: an eight-fold component under 1% of the radius. */
    private val roundnessFloor = 0.01

    /** A row as tall as a column is wide, which is what a distance in plain cells assumes. */
    private val squareCells = 1.0

    /** A row half as tall as a column is wide: this project's grids on a 2:1 world. */
    private val equirectangularCells = 0.5

    /**
     * Exact against brute force, on square cells and on this project's own 2:1 ones.
     *
     * The row scale is the whole of the anisotropic case: at 1 the flood measures in cells and the
     * brute force does too, and at a half a step down the map is worth half a step across it, in
     * the comparisons the flood makes as much as in the distance it finally reports. Getting the
     * second right is not free — a nearer source in cells can be the further one on the ground, so
     * the flood has to carry the scale through every pass rather than scaling the answer at the end
     * — and brute force is what says whether it did.
     */
    @Test
    fun `jump flooding is exact against a brute-force nearest source`() {
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

    @Test
    fun `seed 42's shelf break follows a round contour`() {
        val config = WorldGenConfig(seed = 42L, width = 512, height = 512)
        val world = SharedWorlds.world(config)
        val w = world.width
        val h = world.height
        val shelf = config.cellsFor(config.sea.shelfWidthKm)

        fun field(transform: (Int, Int, FloatArray, IntArray) -> Unit): Pair<FloatArray, IntArray> {
            val dist = FloatArray(w * h) { JumpFloodDistance.INFINITE }
            val label = IntArray(w * h) { -1 }
            for (i in 0 until w * h) {
                if (world.sea.isLand[i]) { dist[i] = 0f; label[i] = i }
            }
            transform(w, h, dist, label)
            return dist to label
        }

        val (exact, nearest) = field(JumpFloodDistance::run)
        val (chamfer, _) = field(DistanceTransform::run)

        // Every cell on the shelf break, binned by the bearing from the land cell that is actually
        // nearest to it, carrying how much further out that bearing pushes the contour than the
        // true radius. A round contour is flat across the bearings; an octagonal one is a cosine
        // with eight lobes, largest at 22.5 degrees where a grid walk has to zig-zag hardest.
        val bins = 64
        val sum = DoubleArray(bins)
        val count = IntArray(bins)
        var jfaWorst = 0.0
        for (i in 0 until w * h) {
            val d = exact[i]
            if (d < shelf - 1f || d > shelf + 1f) continue
            val s = nearest[i]
            if (s < 0) continue
            var dx = (i % w - s % w).toDouble()
            if (dx > w / 2.0) dx -= w
            if (dx < -w / 2.0) dx += w
            val dy = (i / w - s / w).toDouble()
            val bearing = atan2(dy, dx)
            var bin = ((bearing + PI) / (2 * PI) * bins).toInt()
            if (bin >= bins) bin = bins - 1
            sum[bin] += (chamfer[i] - d) / d
            count[bin]++
            // The jump-flooded field is the reference here, so its own error is zero by
            // construction; the brute-force test above is what earns it that standing. What is
            // worth checking anyway is that no cell of the real mask slipped past the flood.
            val error = abs(sqrt(dx * dx + dy * dy) - d)
            if (error > jfaWorst) jfaWorst = error
        }

        val filled = (0 until bins).filter { count[it] > 0 }
        val error = DoubleArray(bins) { if (count[it] == 0) 0.0 else sum[it] / count[it] }
        val mean = filled.sumOf { error[it] } / filled.size
        var re = 0.0
        var im = 0.0
        filled.forEach { bin ->
            val theta = 2 * PI * bin / bins
            re += (error[bin] - mean) * cos(8 * theta)
            im += (error[bin] - mean) * sin(8 * theta)
        }
        val chamferEightFold = 2.0 * sqrt(re * re + im * im) / filled.size
        val peak = filled.maxByOrNull { error[it] }!!
        val trough = filled.minByOrNull { error[it] }!!

        println(
            ("G4 seed 42 at 512, shelf break (distance to land $shelf +/- 1 cell, " +
                "${filled.size} of $bins bearings occupied): the chamfer contour sits %.1f%% " +
                "further out on average, %.1f%% at its worst bearing (%.0f deg) and %.1f%% at " +
                "its best (%.0f deg); eight-fold component %.4f of the radius against %.4f for " +
                "the jump-flooded field, whose worst cell is %.6f cells from its own source")
                .format(
                    mean * 100, error[peak] * 100, peak * 360.0 / bins - 180,
                    error[trough] * 100, trough * 360.0 / bins - 180,
                    chamferEightFold, 0.0, jfaWorst
                )
        )

        assertTrue(
            jfaWorst < 1e-3,
            "the jump-flooded shelf break is not exactly Euclidean; worst cell $jfaWorst"
        )
        assertTrue(
            chamferEightFold > roundnessFloor,
            "the chamfer control should fail the same measurement; it read $chamferEightFold"
        )
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
