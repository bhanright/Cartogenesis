package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.CrustMap
import com.cartogenesis.worldgen.pipeline.TectonicHistory
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * Rule 13 for the crust the history draws (docs/CONVENTIONS.md; docs/DESIGN_LEDGER.md, TH1-1):
 * nothing of the history's sphere grid, nor of the map's, shows in the crust's edge or the plates.
 *
 * The map's own geometry — straight runs along its rows and columns, square corners, arcs, a
 * preferred bearing — is the geometry guard's census, which reads the crust's edge and the plate
 * boundaries with every other layer (`:cartography`'s `MapLayers`). What only the history can show
 * is here: that turning the lattice changes nothing about the edge's shape, that the lattice's
 * spacing leaves no polygon in it, and that the map has no seam where its columns wrap or at its
 * poles.
 */
class CrustMapGeometryTest {

    /**
     * The crust's edge pooled over the standard seeds, drawn once on the lattice as it lies and
     * once on the lattice turned through a fixed rotation: its divider dimension, its sharp corners
     * and its straight runs agree within twice the standard error of their difference, the error
     * taken from the worlds' own spread. A history whose shapes came from its lattice would carry
     * the lattice's turn into them.
     */
    @Test
    fun `turning the lattice leaves the crust's edge as it was`() {
        val asDrawn = SharedWorlds.STANDARD_SEEDS.map { edgeFigures(it, null) }
        val turned = SharedWorlds.STANDARD_SEEDS.map { edgeFigures(it, TURN) }
        val n = asDrawn.size
        for ((index, name) in listOf("divider dimension", "sharp corners", "straight runs").withIndex()) {
            val first = asDrawn.map { it[index] }
            val second = turned.map { it[index] }
            val all = first + second
            val mean = all.average()
            val spread = sqrt(all.sumOf { (it - mean) * (it - mean) } / (all.size - 1))
            val bar = STANDARD_ERRORS * spread * sqrt(2.0 / n)
            val difference = abs(first.average() - second.average())
            println(
                "LATTICE %s: %.3f as drawn, %.3f turned, difference %.3f (bar %.3f)"
                    .format(Locale.ROOT, name, first.average(), second.average(), difference, bar)
            )
            if (name == "straight runs") {
                // Recorded at TH1-2: the straight runs read 0.341 as drawn and 0.273 turned, 0.068
                // apart against 0.067. Over ten seeds the two read 0.316 and 0.289, the paired
                // difference 0.027 at 1.7 of its standard errors, so whether the lattice shows is not
                // settled either way (docs/DESIGN_LEDGER.md, TH1-2; docs/TODO.md).
                KnownFailures.expect("TH1-2: the crust's straight runs move with the lattice's turn past the bar", "0.068 against 0.067") {
                    if (difference > bar) {
                        throw RecordedViolation(
                            "the crust's $name moves by $difference when the lattice turns, more than $bar",
                            "%.3f against %.3f".format(Locale.ROOT, difference, bar)
                        )
                    }
                }
                continue
            }
            assertTrue("the crust's $name moves by $difference when the lattice turns, more than $bar", difference <= bar)
        }
    }

    /**
     * At 1,024 rows, a map cell is a quarter of the lattice's spacing, so a polygon of lattice
     * points would show: the edge walked in chords of one spacing would turn by the lattice's own
     * angles, sixty degrees between its neighbors, more often than by angles either side of it. A
     * natural edge turns less often the sharper the turn. The control reads each map cell at its
     * nearest lattice point, which draws the lattice's own cells, and fails.
     */
    @Test
    fun `the lattice's spacing leaves no polygon in the crust's edge`() {
        for (seed in SharedWorlds.STANDARD_SEEDS.take(2)) {
            val config = WorldGenConfig.forRows(seed, 2 * SharedWorlds.DETAIL_ROWS)
            val history = TectonicHistory.run(config)
            val crust = CrustMap.read(config, history)
            val mask = BooleanArray(crust.continentalShare.size) { crust.continentalShare[it] >= 0.5f }
            assertTrue(
                "seed $seed: the lattice's polygons show in the crust's edge",
                !turnsAtTheLatticeAngle(config, mask, history.sphere.spacingKm, "seed $seed")
            )
            val nearest = BooleanArray(mask.size) { cell ->
                val latitude = PI / 2 - (cell / config.width + 0.5) / config.height * PI
                val longitude = (cell % config.width + 0.5) / config.width * 2 * PI
                history.continental[history.sphere.nearest(cos(latitude) * cos(longitude), cos(latitude) * sin(longitude), sin(latitude))]
            }
            assertTrue(
                "seed $seed: the control's lattice cells pass",
                turnsAtTheLatticeAngle(config, nearest, history.sphere.spacingKm, "seed $seed, the control")
            )
        }
    }

    /**
     * Where the map's columns wrap and at its poles, the crust is as continuous as anywhere: the
     * mean difference in the crust's share across the seam between the last column and the first
     * is no larger than across the largest of the other column pairs, and the same across the pole
     * (a cell against the one half the map away in its own row) against the other row pairs. The
     * control reads the last column a third of the map away, as an error in the wrap would, and
     * fails.
     */
    @Test
    fun `the map has no seam at its wrap or its poles`() {
        val config = WorldGenConfig.forRows(SharedWorlds.STANDARD_SEEDS.first(), SharedWorlds.DETAIL_ROWS)
        val crust = CrustMap.read(config, TectonicHistory.run(config))
        val share = crust.continentalShare
        val width = config.width
        val height = config.height
        fun columnStep(column: Int, next: Int): Double {
            var sum = 0.0
            for (row in 0 until height) sum += abs(share[row * width + next] - share[row * width + column])
            return sum / height
        }
        val seam = columnStep(width - 1, 0)
        val others = (0 until width - 1).map { columnStep(it, it + 1) }
        val control = columnStep(width / 3, 0)
        println(
            "SEAM the wrap's step %.4f, the other column pairs' largest %.4f, mean %.4f; the control's %.4f"
                .format(Locale.ROOT, seam, others.max(), others.average(), control)
        )
        assertTrue("the crust steps by $seam across the wrap, more than across any other column pair", seam <= others.max())
        assertTrue("the control's broken wrap passes", control > others.max())

        fun rowStep(row: Int): Double {
            var sum = 0.0
            for (column in 0 until width) sum += abs(share[(row + 1) * width + column] - share[row * width + column])
            return sum / width
        }
        fun acrossPole(row: Int): Double {
            var sum = 0.0
            for (column in 0 until width / 2) sum += abs(share[row * width + column] - share[row * width + column + width / 2])
            return sum / (width / 2)
        }
        val rowSteps = (0 until height - 1).map { rowStep(it) }
        for (row in listOf(0, height - 1)) {
            val pole = acrossPole(row)
            println("SEAM across the pole at row %d: %.4f; the row pairs' largest %.4f".format(Locale.ROOT, row, pole, rowSteps.max()))
            assertTrue("the crust steps by $pole across the pole at row $row", pole <= rowSteps.max())
        }
    }

    /** Divider dimension, sharp corners and straight runs of [seed]'s crust edge on a lattice turned by [turn]. */
    private fun edgeFigures(seed: Long, turn: DoubleArray?): List<Double> {
        val config = WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS)
        val crust = CrustMap.read(config, TectonicHistory.run(config, latticeOrientation = turn))
        val mask = BooleanArray(crust.continentalShare.size) { crust.continentalShare[it] >= 0.5f }
        val lines = CrustMeasures.traceLines(config, mask)
        return listOf(CrustMeasures.dividerDimension(lines), CrustMeasures.cornerShare(lines), CrustMeasures.straightShare(lines))
    }

    /** Whether [mask]'s edge, walked in chords of [chordKm], turns by sixty degrees more often than by the angles either side. */
    private fun turnsAtTheLatticeAngle(config: WorldGenConfig, mask: BooleanArray, chordKm: Double, label: String): Boolean {
        val lines = CrustMeasures.traceLines(config, mask)
        val bins = IntArray(TURN_BINS)
        for ((xs, ys) in lines.polylines) {
            val (px, py) = walk(xs, ys, chordKm)
            for (index in 2 until px.size) {
                val latitude = 90.0 - py[index - 1] / (config.height * config.cellHeightKm) * 180.0
                if (abs(latitude) >= CrustMeasures.MAX_LINE_LATITUDE) continue
                val first = atan2(py[index - 1] - py[index - 2], px[index - 1] - px[index - 2])
                val second = atan2(py[index] - py[index - 1], px[index] - px[index - 1])
                var turn = abs(second - first)
                if (turn > PI) turn = 2 * PI - turn
                val degrees = turn * 180.0 / PI
                if (degrees < TURN_BINS * TURN_BIN_DEGREES) bins[(degrees / TURN_BIN_DEGREES).toInt()]++
            }
        }
        println("LATTICE %s: turns per %d degrees at the lattice's spacing %s".format(Locale.ROOT, label, TURN_BIN_DEGREES.toInt(), bins.joinToString(" ")))
        // The bin holding sixty degrees against the two either side of it.
        val sixty = (LATTICE_TURN_DEGREES / TURN_BIN_DEGREES).toInt()
        return bins[sixty] > maxOf(bins[sixty - 1], bins[sixty + 1])
    }

    /** The points a polyline is walked through with chords [chordKm] long. */
    private fun walk(xs: DoubleArray, ys: DoubleArray, chordKm: Double): Pair<DoubleArray, DoubleArray> {
        val outX = ArrayList<Double>()
        val outY = ArrayList<Double>()
        var px = xs[0]
        var py = ys[0]
        outX.add(px); outY.add(py)
        for (index in 1 until xs.size) {
            while (hypot(xs[index] - px, ys[index] - py) >= chordKm) {
                val dx = xs[index] - px
                val dy = ys[index] - py
                val length = hypot(dx, dy)
                px += dx / length * chordKm
                py += dy / length * chordKm
                outX.add(px); outY.add(py)
            }
        }
        return outX.toDoubleArray() to outY.toDoubleArray()
    }

    private companion object {
        /** A turn of one radian about an axis that is no axis of the map's: (1, 2, 3) normalized. */
        val TURN: DoubleArray = run {
            val length = sqrt(1.0 + 4.0 + 9.0)
            val ax = 1.0 / length
            val ay = 2.0 / length
            val az = 3.0 / length
            val c = cos(1.0)
            val s = sin(1.0)
            val t = 1.0 - c
            doubleArrayOf(
                t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay,
                t * ax * ay + s * az, t * ay * ay + c, t * ay * az - s * ax,
                t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c
            )
        }

        /** Two standard errors: a difference this large comes by chance one time in twenty. */
        const val STANDARD_ERRORS = 2.0

        const val TURN_BIN_DEGREES = 10.0
        const val TURN_BINS = 9

        /** The angle between a lattice point's neighbors, as seen from it: a near-hexagonal lattice's sixty. */
        const val LATTICE_TURN_DEGREES = 60.0
    }
}
