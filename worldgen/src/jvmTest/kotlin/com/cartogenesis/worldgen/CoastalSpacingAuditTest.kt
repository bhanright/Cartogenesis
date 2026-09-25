package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.FlowRouting
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How far apart the coastal valleys are, in kilometres, and what the figure responds to.
 *
 * The author generated seed 969495 at 2048 and reported that the coasts carry closely spaced
 * valleys perpendicular to the shore about ten cells apart, each with a river and many with a delta
 * lobe. Ten cells is a measure of the grid. This class asks whether the landscape or the grid is
 * what the ten cells are counting, with [RangeFront] — a finder whose fronts, basins and trunks
 * never read a traced river, and whose every rule (belt, front eligibility, exit, outlet, catchment
 * floor, trunk and divide qualification and the proxy's limits, along-front spacing and the two
 * combs, bearing, cross-resolution matching and what an empty sample reports) is written down at
 * that object before a number is collected here.
 *
 * **A measurement.** Nothing in the generator is changed by it; the finding is in
 * docs/DESIGN_LEDGER.md, X1d, and in `docs/GEOGRAPHY.md`.
 *
 * **Two kinds of front.** At [RangeFront.SHORELINE_FLOOR_METRES] every land cell is belt ground and
 * the fronts are straight coasts, which is the author's report read literally. At
 * [RangeFront.BELT_FLOOR_METRES] the fronts are mountain fronts, which is Hovius's measurement.
 * Every table is printed for both.
 *
 * **Three readings, decided before the numbers:**
 *
 * - a spacing that stays near a fixed count of cells as the grid changes implicates the grid or
 *   the routing;
 * - a spacing that tracks the terrain stage's relief wavelength implicates the input;
 * - a spacing that scales with the front's half-width is physical.
 *
 * Resolution alone cannot separate the second from the third, because at fixed settings both the
 * belt's width and the relief wavelength are constants in kilometres and do not move with the grid
 * (`WorldGenConfig.atResolution` rescales every cell-valued width). So the grid sweep is joined by
 * controlled runs that move those two settings directly. Neither can resolution alone clear the
 * instrument, whose own scales — the reference grid, the shortest front, the divide's strips and
 * the catchment floor — are kilometre constants too and could steady a spacing across grids by
 * themselves; so the strips and the floor are moved as well.
 *
 * Every table is also split by the front's bearing, because a cell is twice as wide as it is tall
 * and each reading was expected to predict its own ratio between a front running east-west and one
 * running north-south. Measured, the split cannot be read that way: the world's outlines are
 * consistent with cell-space anisotropy in the kilometre metric — 969495's coastline projects about
 * twice as far east-west as north-south, which this class prints for every world — so the ground
 * reads much as the grid would in both directions, and the finder, isotropic in kilometres on a
 * world that is not, finds few north-south coasts. See docs/DESIGN_LEDGER.md, X1d.
 *
 * In the audit tier: twenty-one whole worlds, seven of them at 2048, generated once each and each
 * reduced to reports — which carry no grid array — before the next is made, which is T4's rule
 * after the nightly machine went down under three retained 2048 worlds; six more for the
 * instrument's sensitivity; ten controlled worlds at 512 and fourteen at 2048; and three more at
 * 512 for the floor and the rulers.
 */
class CoastalSpacingAuditTest {

    private companion object {
        /** The world the author looked at. */
        const val AUTHORS_SEED = 969_495L
        const val AUTHORS_SIDE = 2048

        /** The six audited seeds, and the author's makes seven. */
        val AUDITED_SEEDS = listOf(7L, 42L, 1234L, 99L, 718_106L, 59_758L)
        val ALL_SEEDS = listOf(AUTHORS_SEED) + AUDITED_SEEDS
        val SIDES = listOf(512, 1024, 2048)
        val GRID_PAIRS = listOf(512 to 1024, 512 to 2048, 1024 to 2048)

        /** The two seeds the controlled runs are made on: the author's and the largest-belt one. */
        val CONTROL_SEEDS = listOf(AUTHORS_SEED, 718_106L)
        const val CONTROL_SIDE = 512

        /** The author's reported figure, which is what the cell columns are read against. */
        const val REPORTED_SPACING_CELLS = 10.0

        /** A crop is drawn of a front whose traced comb has at least this many gaps to show. */
        const val FEWEST_TRACED_GAPS_TO_CROP = 5

        /**
         * The author's own coast, on 969495's eastern peninsula, as a line fixed in kilometres: the
         * front the finder chose there at 2048, its ends rounded to that grid's cells (columns 1731
         * and 1685, rows 678 and 763, at 5.859375 by 2.9296875 km). Fixed, so the same stretch is
         * measured at every grid whatever each grid's outline makes of it.
         */
        const val AUTHORS_COAST_START_KM_X = 1731 * 5.859375
        const val AUTHORS_COAST_START_KM_Y = 678 * 2.9296875
        const val AUTHORS_COAST_END_KM_X = 1685 * 5.859375
        const val AUTHORS_COAST_END_KM_Y = 763 * 2.9296875

        /** The altitude whose contour is measured for isotropy beside the coast's, in metres. */
        const val CONTOUR_METRES = 2_000f

        val OUTPUT_DIR = File("build/maps")
    }

    /** The two kinds of front every world is measured at. */
    private enum class Floor(val metres: Float) {
        COAST(RangeFront.SHORELINE_FLOOR_METRES),
        RANGE(RangeFront.BELT_FLOOR_METRES);

        val label: String get() = if (this == COAST) "coast" else "range at ${metres.toInt()} m"
    }

    /** The three spacings along a front, each with the per-front median the matching compares. */
    private enum class Spacing(val label: String, val medianKm: (RangeFront.Measured) -> Double?) {
        TRUNK("trunk", { it.medianSpacingKm }),
        CATCHMENT("catchment comb", { it.medianCatchmentSpacingKm }),
        TRACED("traced comb", { it.medianTracedSpacingKm })
    }

    private fun config(seed: Long, side: Int, edit: (WorldGenConfig) -> WorldGenConfig = { it }) =
        edit(WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(side, side))

    /**
     * Generates one world, hands it to [use], and lets it go before anything else is generated:
     * whatever [use] returns must hold no grid-sized array of the world's.
     */
    private fun <T> withWorld(
        seed: Long,
        side: Int,
        edit: (WorldGenConfig) -> WorldGenConfig = { it },
        use: (WorldMap) -> T
    ): T {
        val beforeGeneration = System.nanoTime()
        var held: WorldMap? = WorldGenerationEngine.generateBlocking(config(seed, side, edit))
        val beforeUse = System.nanoTime()
        val result = use(held!!)
        val after = System.nanoTime()
        held = null
        System.gc()
        println(
            "X1d COST seed %d at %d: %.1f s to generate, %.1f s to measure".format(
                seed, side, (beforeUse - beforeGeneration) / 1e9, (after - beforeUse) / 1e9
            )
        )
        return result
    }

    /** One world reduced to a report at each floor. */
    private fun reportsOf(
        seed: Long,
        side: Int,
        edit: (WorldGenConfig) -> WorldGenConfig = { it }
    ): Map<Floor, RangeFront.Report> = withWorld(seed, side, edit) { world ->
        Floor.entries.associateWith { RangeFront.measure(world, it.metres) }
    }

    // ------------------------------------------------------------------ the table

    /**
     * Every seed at every grid, front by front; the same front matched across the grids; the
     * author's coast followed on one fixed line; and the outline's isotropy of every world.
     *
     * Each seed's three worlds are generated one after another and reduced to reports, so the
     * matching reads reports and never two worlds. The author's own world at 2048 is drawn while it
     * is held, which is the render half of the measurement.
     *
     * Asserted: only that the finder has a subject — a coast carrying a trunk spacing — on at least
     * half the seeds at every grid. Everything else is printed, because a spacing is what this was
     * sent to find out and not something it was sent to hold to a bar.
     */
    @Test
    fun `the outlet spacing on every straight front, at 512, 1024 and 2048, and across them`() {
        val pooled = HashMap<Pair<Floor, Int>, ArrayList<RangeFront.Report>>()
        val across = HashMap<String, Pair<ArrayList<Double>, ArrayList<Double>>>()
        val isotropy = HashMap<Int, ArrayList<Pair<Double, Double>>>()

        ALL_SEEDS.forEach { seed ->
            val reports = SIDES.associateWith { side ->
                withWorld(seed, side) { world ->
                    val coastRatio = printIsotropy(world, seed, side)
                    isotropy.getOrPut(side) { ArrayList() }.add(coastRatio)
                    if (seed == AUTHORS_SEED) printTheAuthorsCoast(world, side)
                    if (seed == AUTHORS_SEED && side == AUTHORS_SIDE) drawTheAuthorsWorld(world)
                    Floor.entries.associateWith { RangeFront.measure(world, it.metres) }
                }
            }
            Floor.entries.forEach { floor ->
                reports.forEach { (side, byFloor) ->
                    val report = byFloor.getValue(floor)
                    pooled.getOrPut(floor to side) { ArrayList() }.add(report)
                    printFronts(report, "seed $seed at $side, ${floor.label}", config(seed, side))
                }
                GRID_PAIRS.forEach { (coarseSide, fineSide) ->
                    Spacing.entries.forEach { spacing ->
                        val (kilometres, cells) =
                            across.getOrPut("${floor.label}, ${spacing.label}, $coarseSide to $fineSide") {
                                ArrayList<Double>() to ArrayList()
                            }
                        printMatches(
                            seed, floor, spacing, coarseSide, fineSide,
                            reports.getValue(coarseSide).getValue(floor),
                            reports.getValue(fineSide).getValue(floor),
                            kilometres, cells
                        )
                    }
                }
            }
        }

        Floor.entries.forEach { floor ->
            SIDES.forEach { side -> printPooled(pooled.getValue(floor to side), "${floor.label} at $side") }
            Spacing.entries.forEach { spacing ->
                GRID_PAIRS.forEach { (coarseSide, fineSide) ->
                    val key = "${floor.label}, ${spacing.label}, $coarseSide to $fineSide"
                    val (kilometres, cells) = across[key] ?: Pair(emptyList<Double>(), emptyList<Double>())
                    printAcross(key, kilometres, cells, fineSide.toDouble() / coarseSide)
                }
            }
        }
        SIDES.forEach { side ->
            val ratios = isotropy.getValue(side)
            println(
                ("X1d ISOTROPY POOLED at %d over %d worlds: the coastline's east-west over north-south " +
                    "projection %s (range %s to %s), the %d m contour's %s (range %s to %s); land " +
                    "isotropic on the ground reads 1").format(
                    side, ratios.size,
                    RangeFront.show(RangeFront.median(ratios.map { it.first }), 2),
                    RangeFront.show(ratios.minOf { it.first }, 2), RangeFront.show(ratios.maxOf { it.first }, 2),
                    CONTOUR_METRES.toInt(),
                    RangeFront.show(RangeFront.median(ratios.map { it.second }), 2),
                    RangeFront.show(ratios.minOf { it.second }, 2), RangeFront.show(ratios.maxOf { it.second }, 2)
                )
            )
        }

        SIDES.forEach { side ->
            val withSample = pooled.getValue(Floor.COAST to side).count { it.sample.isNotEmpty() }
            assertTrue(
                withSample * 2 >= ALL_SEEDS.size,
                "a coast carries a trunk spacing on at least half the seeds at $side: $withSample"
            )
        }
    }

    /** One pooled line of fine-over-coarse ratios for one spacing between two grids. */
    private fun printAcross(key: String, kilometres: List<Double>, cells: List<Double>, grid: Double) {
        println(
            ("X1d ACROSS POOLED %s over %d matched fronts: the fine spacing is %s of the coarse in " +
                "kilometres (IQR %s) and %s in cells (IQR %s); a spacing set by the grid reads %.2f " +
                "and 1.00, one set on the ground 1.00 and %.2f").format(
                key, kilometres.size,
                RangeFront.show(RangeFront.median(kilometres), 2),
                showRange(RangeFront.quartiles(kilometres), 2),
                RangeFront.show(RangeFront.median(cells), 2),
                showRange(RangeFront.quartiles(cells), 2), 1.0 / grid, grid
            )
        )
    }

    /**
     * One seed's fronts at two grids, paired for one spacing, with each pair's figures.
     *
     * The pairing is [RangeFront.match]'s over the fronts carrying this spacing at both grids, so
     * each of the three spacings has its own set of pairs.
     */
    private fun printMatches(
        seed: Long,
        floor: Floor,
        spacing: Spacing,
        coarseSide: Int,
        fineSide: Int,
        coarse: RangeFront.Report,
        fine: RangeFront.Report,
        pooledKm: MutableList<Double>,
        pooledCells: MutableList<Double>
    ) {
        val matches = RangeFront.match(coarse, fine) { spacing.medianKm(it) != null }
        println(
            "X1d ACROSS seed %d, %s, %s, %d against %d: %d pairs of %d and %d fronts carrying it".format(
                seed, floor.label, spacing.label, coarseSide, fineSide, matches.size,
                coarse.measured.count { spacing.medianKm(it) != null },
                fine.measured.count { spacing.medianKm(it) != null }
            )
        )
        matches.forEach { match ->
            val coarseKm = spacing.medianKm(match.coarse)!!
            val fineKm = spacing.medianKm(match.fine)!!
            val coarseCells = match.coarse.front.cellsAlong(coarseKm, coarse.cellWidthKm, coarse.cellHeightKm)
            val fineCells = match.fine.front.cellsAlong(fineKm, fine.cellWidthKm, fine.cellHeightKm)
            pooledKm.add(fineKm / coarseKm)
            pooledCells.add(fineCells / coarseCells)
            println(
                ("X1d ACROSS seed %d, %s, %s, front at (%.0f, %.0f) km %s: %.1f km (%.2f cells, " +
                    "half-width %s km) at %d; %.1f km (%.2f cells, half-width %s km) at %d").format(
                    seed, floor.label, spacing.label, match.coarse.front.midKmX,
                    match.coarse.front.midKmY, match.coarse.front.bearing,
                    coarseKm, coarseCells, RangeFront.show(match.coarse.medianDivideToFrontKm), coarseSide,
                    fineKm, fineCells, RangeFront.show(match.fine.medianDivideToFrontKm), fineSide
                )
            )
        }
    }

    /** One line per front, then the world's census. */
    private fun printFronts(report: RangeFront.Report, label: String, config: WorldGenConfig) {
        println("X1d FRONTS $label: ${report.census}")
        fun cells(measured: RangeFront.Measured, kilometres: Double?) = RangeFront.show(
            kilometres?.let { measured.front.cellsAlong(it, report.cellWidthKm, report.cellHeightKm) }
        )
        report.measured.forEach { measured ->
            val front = measured.front
            println(
                ("X1d FRONT %s: %.0f km %s%s at (%.0f, %.0f) km on %s; %d catchments, %d trunks (%d " +
                    "alone in their strip), %d set aside, %d traced outlets; trunk %s km = %s cells, " +
                    "catchment %s km = %s cells, traced %s km = %s cells; half-width %s km against a " +
                    "stamp of %s km; ratio %s").format(
                    label, front.lengthKm, front.bearing, if (measured.coastal) " coastal" else "",
                    front.midKmX, front.midKmY, measured.boundaryClass ?: "-",
                    measured.catchments.size, measured.trunks.size,
                    measured.trunks.count { it.aloneInItsStrip }, measured.cornersSetAside,
                    measured.tracedAlongKm?.size ?: 0,
                    RangeFront.show(measured.medianSpacingKm), cells(measured, measured.medianSpacingKm),
                    RangeFront.show(measured.medianCatchmentSpacingKm),
                    cells(measured, measured.medianCatchmentSpacingKm),
                    RangeFront.show(measured.medianTracedSpacingKm),
                    cells(measured, measured.medianTracedSpacingKm),
                    RangeFront.show(measured.medianDivideToFrontKm),
                    RangeFront.show(RangeFront.stampedHalfWidthKm(config, measured)),
                    RangeFront.show(measured.hoviusRatio, 2)
                )
            )
        }
    }

    /**
     * The pooled table for one group of reports, by bearing and for the coastal fronts alone.
     *
     * The trunk spacing twice: over fronts (each front's own median, each front once) and over gaps
     * (every gap once), with the count behind each. The ratio is the median of the fronts' own
     * ratios, which is Hovius's statistic read on this instrument's divide proxy; the half-width is
     * the median of every trunk's. The two combs are over gaps. Then the proportionality test: over
     * the fronts carrying a trunk spacing and a positive median half-width, an ordinary
     * least-squares fit of the log of the front's median trunk spacing on the log of its median
     * half-width, whose slope is 1 where spacing is proportional to half-width as on Hovius's belts.
     */
    private fun printPooled(reports: List<RangeFront.Report>, label: String) {
        val groups = listOf<Pair<String, (RangeFront.Measured) -> Boolean>>(
            "all fronts" to { _ -> true },
            "coastal fronts" to { it.coastal },
            "east-west fronts" to { it.front.bearing == RangeFront.Bearing.EAST_WEST },
            "north-south fronts" to { it.front.bearing == RangeFront.Bearing.NORTH_SOUTH },
            "diagonal fronts" to { it.front.bearing == RangeFront.Bearing.DIAGONAL }
        )
        println(
            "X1d POOLED %s: %d of %d worlds carry a front with a trunk spacing".format(
                label, reports.count { it.sample.isNotEmpty() }, reports.size
            )
        )
        groups.forEach { (name, keep) ->
            val frontSpacingKm = ArrayList<Double>()
            val frontSpacingCells = ArrayList<Double>()
            val frontRatios = ArrayList<Double>()
            val halfWidths = ArrayList<Double>()
            var trunks = 0
            var trunksAlone = 0
            val logHalfWidth = ArrayList<Double>()
            val logSpacing = ArrayList<Double>()
            val trunkGaps = Gaps()
            val catchmentGaps = Gaps()
            val tracedGaps = Gaps()
            reports.forEach { report ->
                report.measured.filter(keep).forEach { measured ->
                    measured.medianSpacingKm?.let { frontSpacingKm.add(it) }
                    report.spacingInCells(measured)?.let { frontSpacingCells.add(it) }
                    measured.hoviusRatio?.let { frontRatios.add(it) }
                    if (measured.spacingsKm.isNotEmpty()) {
                        measured.trunks.forEach { halfWidths.add(it.divideToFrontKm) }
                        trunks += measured.trunks.size
                        trunksAlone += measured.trunks.count { it.aloneInItsStrip }
                    }
                    val spacing = measured.medianSpacingKm
                    val halfWidth = measured.medianDivideToFrontKm
                    if (spacing != null && halfWidth != null && halfWidth > 0.0) {
                        logHalfWidth.add(ln(halfWidth))
                        logSpacing.add(ln(spacing))
                    }
                    trunkGaps.add(report, measured, measured.spacingsKm)
                    catchmentGaps.add(report, measured, measured.catchmentSpacingsKm)
                    tracedGaps.add(report, measured, measured.tracedSpacingsKm)
                }
            }
            val fronts = reports.sumOf { report -> report.measured.count(keep) }
            val fit = leastSquares(logHalfWidth, logSpacing)
            println(
                ("X1d POOLED %s, %s: %d fronts, %d with a trunk spacing: trunk %s km (IQR %s) = %s " +
                    "cells by front, %s by gap; half-width %s km over %d trunks, %d of them alone in " +
                    "their strip; ratio %s (IQR %s) against Hovius's %.1f; log spacing on log " +
                    "half-width slope %s, r %s over %d fronts; catchment comb %s; traced comb %s (the " +
                    "author read %.0f cells)").format(
                    label, name, fronts, frontSpacingKm.size,
                    RangeFront.show(RangeFront.median(frontSpacingKm)),
                    showRange(RangeFront.quartiles(frontSpacingKm)),
                    RangeFront.show(RangeFront.median(frontSpacingCells)),
                    trunkGaps.summary(),
                    RangeFront.show(RangeFront.median(halfWidths)), trunks, trunksAlone,
                    RangeFront.show(RangeFront.median(frontRatios), 2),
                    showRange(RangeFront.quartiles(frontRatios), 2), RangeFront.HOVIUS_RATIO,
                    RangeFront.show(fit?.first, 2), RangeFront.show(fit?.second, 2), logSpacing.size,
                    catchmentGaps.summary(), tracedGaps.summary(), REPORTED_SPACING_CELLS
                )
            )
        }
    }

    /** Slope and correlation of an ordinary least-squares line of [y] on [x], or null under three. */
    private fun leastSquares(x: List<Double>, y: List<Double>): Pair<Double, Double>? {
        if (x.size < 3) return null
        val meanX = x.average()
        val meanY = y.average()
        var spreadXX = 0.0
        var spreadYY = 0.0
        var spreadXY = 0.0
        for (index in x.indices) {
            spreadXX += (x[index] - meanX) * (x[index] - meanX)
            spreadYY += (y[index] - meanY) * (y[index] - meanY)
            spreadXY += (x[index] - meanX) * (y[index] - meanY)
        }
        if (spreadXX <= 0.0 || spreadYY <= 0.0) return null
        return spreadXY / spreadXX to spreadXY / sqrt(spreadXX * spreadYY)
    }

    /** Gaps pooled over fronts, each kept in kilometres and in its own front's cells. */
    private class Gaps {
        val kilometres = ArrayList<Double>()
        val cells = ArrayList<Double>()

        fun add(report: RangeFront.Report, measured: RangeFront.Measured, gapsKm: List<Double>) {
            gapsKm.forEach {
                kilometres.add(it)
                cells.add(measured.front.cellsAlong(it, report.cellWidthKm, report.cellHeightKm))
            }
        }

        fun summary(): String = "%s km (IQR %s) = %s cells over %d gaps".format(
            RangeFront.show(RangeFront.median(kilometres)),
            RangeFront.quartiles(kilometres)?.let {
                "${RangeFront.show(it.first)} to ${RangeFront.show(it.second)}"
            } ?: "-",
            RangeFront.show(RangeFront.median(cells)), kilometres.size
        )
    }

    private fun showRange(range: Pair<Double, Double>?, decimals: Int = 1): String =
        range?.let {
            "${RangeFront.show(it.first, decimals)} to ${RangeFront.show(it.second, decimals)}"
        } ?: "-"

    // ------------------------------------------------------------------ the author's coast

    /**
     * The author's coast measured on one fixed line at this grid, whatever this grid's outline
     * makes of it: the three spacings and how many gaps stand behind each.
     */
    private fun printTheAuthorsCoast(world: WorldMap, side: Int) {
        val measured = RangeFront.measureLine(
            world, AUTHORS_COAST_START_KM_X, AUTHORS_COAST_START_KM_Y,
            AUTHORS_COAST_END_KM_X, AUTHORS_COAST_END_KM_Y
        )
        fun cells(kilometres: Double?) = RangeFront.show(
            kilometres?.let { measured.front.cellsAlong(it, world.config.cellWidthKm, world.config.cellHeightKm) }
        )
        println(
            ("X1d AUTHORS COAST at %d: a %.0f km line; catchment comb %s km = %s cells over %d gaps; " +
                "traced comb %s km = %s cells over %d gaps; trunk %s km over %d gaps; %d catchments of " +
                "median area %s km2").format(
                side, measured.front.lengthKm,
                RangeFront.show(measured.medianCatchmentSpacingKm), cells(measured.medianCatchmentSpacingKm),
                measured.catchmentSpacingsKm.size,
                RangeFront.show(measured.medianTracedSpacingKm), cells(measured.medianTracedSpacingKm),
                measured.tracedSpacingsKm.size,
                RangeFront.show(measured.medianSpacingKm), measured.spacingsKm.size,
                measured.catchments.size,
                RangeFront.show(RangeFront.median(measured.catchments.map { it.areaKm2 }), 0)
            )
        )
    }

    // ------------------------------------------------------------------ isotropy

    /**
     * How far a world's coastline and its [CONTOUR_METRES] contour run east-west against
     * north-south, in kilometres, printed and returned as the two ratios.
     *
     * Every boundary between two vertically adjacent cells on either side of the line is a segment
     * running east-west one cell width long, and every boundary between horizontally adjacent ones,
     * the seam included, a segment running north-south one cell height long. Summed, those are the
     * outline's projections on the two axes, and an outline isotropic on the ground projects as far
     * on one as on the other.
     */
    private fun printIsotropy(world: WorldMap, seed: Long, side: Int): Pair<Double, Double> {
        val isLand = world.sea.isLand
        val coast = projectedOutlineKm(world, isLand)
        val high = BooleanArray(isLand.size) {
            isLand[it] && world.config.scale.metresAboveShoreline(world.sea.relativeElevation.data[it]) >=
                CONTOUR_METRES
        }
        val contour = projectedOutlineKm(world, high)
        println(
            ("X1d ISOTROPY seed %d at %d: the coastline projects %.0f km east-west and %.0f km " +
                "north-south, %.2f to 1; the %d m contour %.0f and %.0f km, %.2f to 1").format(
                seed, side, coast.first, coast.second, coast.first / coast.second,
                CONTOUR_METRES.toInt(), contour.first, contour.second, contour.first / contour.second
            )
        )
        return coast.first / coast.second to contour.first / contour.second
    }

    private fun projectedOutlineKm(world: WorldMap, inside: BooleanArray): Pair<Double, Double> {
        val cellsAcross = world.width
        val cellsDown = world.height
        var rowBoundaries = 0L
        var columnBoundaries = 0L
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (row + 1 < cellsDown && inside[cell] != inside[cell + cellsAcross]) rowBoundaries++
                val east = row * cellsAcross + (column + 1) % cellsAcross
                if (inside[cell] != inside[east]) columnBoundaries++
            }
        }
        return rowBoundaries * world.config.cellWidthKm to columnBoundaries * world.config.cellHeightKm
    }

    // ------------------------------------------------------------------ the instrument's own scales

    /**
     * Whether the instrument's own scales steady the spacing: the divide's strip width and the
     * catchment floor halved and doubled, on two seeds at all three grids.
     *
     * Moving an instrument scale needs no new world, so each world is generated once and measured
     * five ways at each floor. Printed for each setting: the pooled trunk and catchment spacing at
     * each grid, and the fine-over-coarse ratio of matched fronts, which is the figure a scale that
     * held the spacing steady across grids would move toward 1.
     */
    @Test
    fun `the divide's strips and the catchment floor moved, with the spacing beside them`() {
        val settings = linkedMapOf(
            "stock" to RangeFront.InstrumentScales(),
            "strips x0.5" to RangeFront.InstrumentScales(divideStripKm = RangeFront.DIVIDE_STRIP_KM * 0.5),
            "strips x2" to RangeFront.InstrumentScales(divideStripKm = RangeFront.DIVIDE_STRIP_KM * 2),
            "floor x0.5" to RangeFront.InstrumentScales(catchmentFloorKm2 = RangeFront.SMALLEST_CATCHMENT_KM2 * 0.5),
            "floor x2" to RangeFront.InstrumentScales(catchmentFloorKm2 = RangeFront.SMALLEST_CATCHMENT_KM2 * 2)
        )
        val reports = HashMap<String, RangeFront.Report>()
        fun key(seed: Long, side: Int, floor: Floor, setting: String) = "$seed $side ${floor.name} $setting"
        CONTROL_SEEDS.forEach { seed ->
            SIDES.forEach { side ->
                withWorld(seed, side) { world ->
                    Floor.entries.forEach { floor ->
                        settings.forEach { (setting, scales) ->
                            reports[key(seed, side, floor, setting)] =
                                RangeFront.measure(world, floor.metres, scales = scales)
                        }
                    }
                }
            }
        }
        Floor.entries.forEach { floor ->
            settings.keys.forEach { setting ->
                val bySide = SIDES.joinToString("; ") { side ->
                    val group = CONTROL_SEEDS.map { reports.getValue(key(it, side, floor, setting)) }
                    val trunk = group.flatMap { it.gapsKm(trunksOnly = true) }
                    val comb = group.flatMap { it.gapsKm(trunksOnly = false) }
                    "%d: trunk %s km over %d gaps, catchment comb %s km over %d".format(
                        side, RangeFront.show(RangeFront.median(trunk)), trunk.size,
                        RangeFront.show(RangeFront.median(comb)), comb.size
                    )
                }
                val acrossGrids = GRID_PAIRS.joinToString("; ") { (coarseSide, fineSide) ->
                    val ratios = CONTROL_SEEDS.flatMap { seed ->
                        RangeFront.match(
                            reports.getValue(key(seed, coarseSide, floor, setting)),
                            reports.getValue(key(seed, fineSide, floor, setting))
                        ) { it.medianSpacingKm != null }
                            .map { it.fine.medianSpacingKm!! / it.coarse.medianSpacingKm!! }
                    }
                    "%d to %d %s over %d".format(
                        coarseSide, fineSide, RangeFront.show(RangeFront.median(ratios), 2), ratios.size
                    )
                }
                println("X1d SENSITIVITY ${floor.label}, $setting: $bySide; matched trunk fine over coarse $acrossGrids")
            }
        }
    }

    // ------------------------------------------------------------------ the controlled runs

    /**
     * The two suspects moved directly — the relief band's wavelength and the belt's half-width —
     * and a third the bearing split cannot tell from the grid, the terrain noise's finest octave.
     *
     * On two seeds, at 512, where a controlled world costs seconds, and again at 2048, because at
     * 512 the comb sits at two to four cells, the grid's own limit, where nothing could be seen to
     * track anything. The relief wavelength is `TerrainConfig.reliefCornerKm`, which
     * `TerrainStage.ReliefBand` turns into the scale the base relief is loudest at; the belt's
     * half-width is `TectonicsConfig.andeanWidthCells` for a coastal range and `collisionWidthCells`
     * for a plateau, moved together so a world's belts are all narrower or all broader rather than
     * one kind of them. A halving and a doubling of each, a full octave either side of the stock
     * figure: enough that a spacing which tracked either setting could not be mistaken for noise,
     * and not so much that the world stops being the same world.
     *
     * The octave count is `TerrainConfig.octaves`, changed by one either way, which doubles and
     * halves the finest wavelength the noise carries. The noise is drawn on a lattice with as many
     * cycles down the map as across it, so its finest octave is the same count of cells in both
     * directions and twice the kilometres east-west — the bearing signature a grid-set spacing has.
     * Eight octaves put the finest at 512 cycles, 23 km across and 12 km down, which is four cells
     * at 2048. At 512 a ninth octave would fall between the cells, so it is moved at 2048 only.
     *
     * A changed setting is a different world — the plates are the same, but the ground they carry
     * is not — so no front is matched from one run to another; each run's pooled gaps are read
     * against the stock run's at the same grid.
     */
    @Test
    fun `the relief wavelength, the belt width and the finest octave moved, with the spacing beside them`() {
        listOf(CONTROL_SIDE, AUTHORS_SIDE).forEach { side ->
            CONTROL_SEEDS.forEach { seed -> controlledRuns(seed, side) }
        }
    }

    private fun controlledRuns(seed: Long, side: Int) {
        val runs = LinkedHashMap<String, Map<Floor, RangeFront.Report>>()
        runs["stock"] = reportsOf(seed, side)
        listOf(0.5, 2.0).forEach { factor ->
            runs["relief wavelength x$factor"] = reportsOf(seed, side, edit = { config ->
                config.copy(
                    terrain = config.terrain.copy(reliefCornerKm = config.terrain.reliefCornerKm * factor)
                )
            })
        }
        listOf(0.5, 2.0).forEach { factor ->
            runs["belt half-width x$factor"] = reportsOf(seed, side, edit = { config ->
                config.copy(
                    tectonics = config.tectonics.copy(
                        andeanWidthCells = (config.tectonics.andeanWidthCells * factor).toFloat(),
                        collisionWidthCells = (config.tectonics.collisionWidthCells * factor).toFloat()
                    )
                )
            })
        }
        if (side > CONTROL_SIDE) {
            listOf(-1, 1).forEach { step ->
                runs["octaves %+d".format(step)] = reportsOf(seed, side, edit = { config ->
                    config.copy(terrain = config.terrain.copy(octaves = config.terrain.octaves + step))
                })
            }
        }
        Floor.entries.forEach { floor ->
            runs.forEach { (label, byFloor) ->
                printPooled(listOf(byFloor.getValue(floor)), "seed $seed at $side, ${floor.label}, $label")
            }
            val stock = runs.getValue("stock").getValue(floor)
            runs.forEach { (label, byFloor) ->
                val report = byFloor.getValue(floor)
                fun moved(of: (RangeFront.Report) -> List<Double>): String {
                    val here = RangeFront.median(of(report))
                    val there = RangeFront.median(of(stock))
                    val ratio = if (here == null || there == null || there == 0.0) null else here / there
                    return "%s km over %d (%s of stock)".format(
                        RangeFront.show(here), of(report).size, RangeFront.show(ratio, 2)
                    )
                }
                println(
                    ("X1d CONTROL seed %d at %d, %s, %s: trunk %s; half-width %s; catchment comb %s; " +
                        "traced comb %s").format(
                        seed, side, floor.label, label,
                        moved { it.gapsKm(trunksOnly = true) },
                        moved { it.halfWidthsKm() },
                        moved { r -> r.measured.flatMap { it.catchmentSpacingsKm } },
                        moved { r -> r.measured.flatMap { it.tracedSpacingsKm } }
                    )
                )
            }
        }
    }

    /**
     * Whether the floor the fronts are found at is what decides the spacing.
     *
     * [RangeFront.BELT_FLOOR_METRES] is argued rather than tuned, but a finder whose answer walked
     * with its own threshold would not be measuring the landscape. Two worlds, the shoreline and
     * four floors above it, the same figures printed at each.
     */
    @Test
    fun `the belt floor the fronts are found at does not decide the spacing`() {
        CONTROL_SEEDS.forEach { seed ->
            val floors = listOf(0f, 1_000f, 1_500f, 2_000f, 2_500f)
            val reports = withWorld(seed, CONTROL_SIDE) { world ->
                floors.associateWith { floor -> RangeFront.measure(world, floor) }
            }
            reports.forEach { (floor, report) ->
                println("X1d FLOOR seed $seed at $CONTROL_SIDE, floor ${floor.toInt()} m: ${report.census}")
                printPooled(listOf(report), "seed $seed at $CONTROL_SIDE, floor ${floor.toInt()} m")
            }
        }
    }

    // ------------------------------------------------------------------ the two rulers

    /**
     * The routing's ruler measured, and the incision's printed beside it as arithmetic.
     *
     * `FlowRouting.flowDirections` builds its facets on the ground: a leg out to a cardinal
     * neighbour and a leg on to the diagonal, a cell width and a row's height one way round or the
     * other, where X1d found it comparing falls over one cell or the square root of two whichever
     * way the step ran. This case measures what the routing does on a plane falling equally fast,
     * per kilometre, eastward and southward — steepest descent at 45 degrees on the ground — routed
     * by the production function, with the mean bearing of the steps it chooses read in kilometres.
     * A router isotropic on the ground returns 45 degrees; one reading every step in cell widths
     * took the east-to-south-east facet with half its weight on the diagonal, 14.0 degrees, which is
     * what X1d measured and what this case asserted until the facets were mended
     * (docs/DESIGN_LEDGER.md, Fix 2).
     *
     * Asserted: that the routing's mean bearing lies within two degrees of the plane's, the bar
     * `RoutingGroundTest` holds a dozen planes to per merge; the square ruler's error was fifteen
     * times it.
     *
     * `HydraulicErosion.cut` is private and divides its drop by the same step's length on the
     * ground, a row's height down a column, which is printed beside the routing as arithmetic
     * rather than measured; so is the share of one world's land steps in each direction.
     */
    @Test
    fun `the routing's ruler measured, and the incision's beside it`() {
        val cellsAcross = 512
        val cellsDown = 512
        val probe = config(AUTHORS_SEED, CONTROL_SIDE)
        val cellWidthKm = probe.cellWidthKm
        val cellHeightKm = probe.cellHeightKm
        val fallPerKm = 1e-5
        val plane = FloatField(cellsAcross, cellsDown, FloatArray(cellsAcross * cellsDown) { cell ->
            (1.0 - fallPerKm * ((cell % cellsAcross) * cellWidthKm + (cell / cellsAcross) * cellHeightKm)).toFloat()
        })
        val receiver = FlowRouting.flowDirections(
            cellsAcross, cellsDown, BooleanArray(cellsAcross * cellsDown) { true }, plane, plane,
            AUTHORS_SEED, probe.cellHeightInCellWidths, byFacet = true, overPotential = false
        )
        // Away from the seam, where the plane is not periodic and the wrap would drain west.
        val margin = 16
        var sumKmX = 0.0
        var sumKmY = 0.0
        var steps = 0
        for (row in margin until cellsDown - margin) {
            for (column in margin until cellsAcross - margin) {
                val target = receiver[row * cellsAcross + column]
                if (target < 0) continue
                sumKmX += ((target % cellsAcross) - column) * cellWidthKm
                sumKmY += ((target / cellsAcross) - row) * cellHeightKm
                steps++
            }
        }
        val routedDegrees = Math.toDegrees(atan2(sumKmY, sumKmX))
        val squareRulerDegrees = Math.toDegrees(atan2(cellHeightKm / 2, cellWidthKm))
        println(
            ("X1d METRIC routing: on a plane falling equally per kilometre east and south (45.0 degrees " +
                "on the ground), %d routed steps average %.1f degrees south of east, against %.1f for a " +
                "router reading every step in cell widths").format(steps, routedDegrees, squareRulerDegrees)
        )

        val flowTarget = withWorld(AUTHORS_SEED, CONTROL_SIDE) { world ->
            IntArray(world.width * world.height) { if (world.sea.isLand[it]) world.rivers.flowTarget[it] else -1 }
        }
        var alongRow = 0L
        var downColumn = 0L
        var diagonal = 0L
        for (cell in flowTarget.indices) {
            val target = flowTarget[cell]
            if (target < 0) continue
            var columnStep = (target % cellsAcross) - (cell % cellsAcross)
            if (columnStep > cellsAcross / 2) columnStep -= cellsAcross
            if (columnStep < -cellsAcross / 2) columnStep += cellsAcross
            val rowStep = (target / cellsAcross) - (cell / cellsAcross)
            when {
                columnStep != 0 && rowStep != 0 -> diagonal++
                columnStep != 0 -> alongRow++
                else -> downColumn++
            }
        }
        val allSteps = (alongRow + downColumn + diagonal).coerceAtLeast(1)
        println(
            ("X1d METRIC incision, from the source's arithmetic: a slope down a column read at %.2f " +
                "of the ground's and a diagonal one at %.2f; of %d land steps on seed %d at %d, %.1f%% " +
                "run along a row, %.1f%% down a column and %.1f%% diagonally").format(
                1.0, 1.0,
                allSteps, AUTHORS_SEED, CONTROL_SIDE,
                100.0 * alongRow / allSteps, 100.0 * downColumn / allSteps, 100.0 * diagonal / allSteps
            )
        )
        assertTrue(
            abs(routedDegrees - 45.0) <= 2.0,
            "the routing's mean bearing on the plane, $routedDegrees degrees, is off the ground's 45"
        )
    }

    // ------------------------------------------------------------------ the renders

    /**
     * The author's world with the fronts and outlets the finder chose drawn over it.
     *
     * Not a measurement: the eye's half of one. A whole-world sheet with every front's chord (coasts
     * red, range fronts orange), and three crops at twice the grid's pixels: the three coasts
     * carrying a trunk spacing whose traced comb is finest, among those with at least
     * [FEWEST_TRACED_GAPS_TO_CROP] traced gaps, since the finest comb is the author's complaint. Over
     * the Fantasy render with the traced courses inked on it in blue — the render reads the traced
     * rivers, the finder's fronts and trunks do not — the chord is red, each trunk basin is tinted,
     * each trunk outlet is a yellow mark and every other catchment's outlet a smaller cyan one.
     */
    private fun drawTheAuthorsWorld(world: WorldMap) {
        OUTPUT_DIR.mkdirs()
        val coast = RangeFront.measure(world, RangeFront.SHORELINE_FLOOR_METRES, keepBasinCells = true)
        val range = RangeFront.measure(world, RangeFront.BELT_FLOOR_METRES)
        val base = DebugMapDump().render(world, DebugMapDump.Mode.FANTASY)
        world.rivers.rivers.forEach { river ->
            river.cells.forEach { cell -> base.setRGB(cell % world.width, cell / world.width, 0x1F4FD0) }
        }

        val sheet = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_RGB)
        sheet.graphics.drawImage(base, 0, 0, null)
        val pen = sheet.createGraphics()
        pen.stroke = BasicStroke(3f)
        listOf(coast to Color(255, 40, 40), range to Color(255, 160, 0)).forEach { (report, colour) ->
            pen.color = colour
            report.measured.forEach { measured ->
                val front = measured.front
                pen.drawLine(
                    (front.startKmX / world.config.cellWidthKm).roundToInt(),
                    (front.startKmY / world.config.cellHeightKm).roundToInt(),
                    (front.endKmX / world.config.cellWidthKm).roundToInt(),
                    (front.endKmY / world.config.cellHeightKm).roundToInt()
                )
            }
        }
        pen.dispose()
        ImageIO.write(sheet, "png", File(OUTPUT_DIR, "x1d-969495-2048-fronts.png"))

        val chosen = coast.sample
            .filter { it.tracedSpacingsKm.size >= FEWEST_TRACED_GAPS_TO_CROP }
            .sortedBy { it.medianTracedSpacingKm }
            .take(3)
        if (chosen.isEmpty()) {
            println("X1d RENDER: no coast carried a trunk spacing and a traced comb, no crop drawn")
            return
        }
        chosen.forEachIndexed { index, measured ->
            val name = "x1d-969495-2048-front${index + 1}.png"
            ImageIO.write(crop(world, base, measured), "png", File(OUTPUT_DIR, name))
            println(
                ("X1d RENDER %s: a %.0f km %s coast at (%.0f, %.0f) km, columns %d to %d and rows %d " +
                    "to %d, on %s; %d trunks and %d catchments; trunk %s km (%s cells), catchment " +
                    "comb %s km, traced comb %s km (%s cells), half-width %s km, ratio %s").format(
                    name, measured.front.lengthKm, measured.front.bearing,
                    measured.front.midKmX, measured.front.midKmY,
                    (min(measured.front.startKmX, measured.front.endKmX) / world.config.cellWidthKm).roundToInt(),
                    (max(measured.front.startKmX, measured.front.endKmX) / world.config.cellWidthKm).roundToInt(),
                    (min(measured.front.startKmY, measured.front.endKmY) / world.config.cellHeightKm).roundToInt(),
                    (max(measured.front.startKmY, measured.front.endKmY) / world.config.cellHeightKm).roundToInt(),
                    measured.boundaryClass ?: "-", measured.trunks.size, measured.catchments.size,
                    RangeFront.show(measured.medianSpacingKm),
                    RangeFront.show(coast.spacingInCells(measured)),
                    RangeFront.show(measured.medianCatchmentSpacingKm),
                    RangeFront.show(measured.medianTracedSpacingKm),
                    RangeFront.show(measured.medianTracedSpacingKm?.let {
                        measured.front.cellsAlong(it, world.config.cellWidthKm, world.config.cellHeightKm)
                    }),
                    RangeFront.show(measured.medianDivideToFrontKm),
                    RangeFront.show(measured.hoviusRatio, 2)
                )
            )
        }
        println("X1d RENDER written to ${OUTPUT_DIR.absolutePath}")
    }

    /** One front's neighbourhood at twice the grid's pixels, with the finder's choices on it. */
    private fun crop(world: WorldMap, base: BufferedImage, measured: RangeFront.Measured): BufferedImage {
        val cellWidthKm = world.config.cellWidthKm
        val cellHeightKm = world.config.cellHeightKm
        val front = measured.front

        fun columnOf(kmX: Double) = (kmX / cellWidthKm).roundToInt()
        fun rowOf(kmY: Double) = (kmY / cellHeightKm).roundToInt()

        // Enough ground either side to show the trunks' heads, capped so a crop stays readable.
        val marginKm = min(
            max(measured.medianDivideToFrontKm ?: 0.0, RangeFront.SHORTEST_FRONT_KM / 4),
            RangeFront.SHORTEST_FRONT_KM
        ) + RangeFront.FRONT_STRAIGHTNESS_KM
        val columns = listOf(front.startKmX, front.endKmX).map { columnOf(it) }
        val rows = listOf(front.startKmY, front.endKmY).map { rowOf(it) }
        val left = max(0, columns.min() - (marginKm / cellWidthKm).roundToInt())
        val right = min(world.width - 1, columns.max() + (marginKm / cellWidthKm).roundToInt())
        val top = max(0, rows.min() - (marginKm / cellHeightKm).roundToInt())
        val bottom = min(world.height - 1, rows.max() + (marginKm / cellHeightKm).roundToInt())

        val tinted = BufferedImage(right - left + 1, bottom - top + 1, BufferedImage.TYPE_INT_RGB)
        tinted.graphics.drawImage(base, -left, -top, null)
        val tints = intArrayOf(0xE04040, 0x40A040, 0x8040E0, 0xE0A020)
        measured.trunks.forEachIndexed { index, trunk ->
            trunk.cells?.forEach { cell ->
                val column = cell % world.width - left
                val row = cell / world.width - top
                if (column in 0 until tinted.width && row in 0 until tinted.height) {
                    tinted.setRGB(column, row, blend(tinted.getRGB(column, row), tints[index % tints.size]))
                }
            }
        }

        val scale = 2
        val image = BufferedImage(tinted.width * scale, tinted.height * scale, BufferedImage.TYPE_INT_RGB)
        val pen = image.createGraphics()
        pen.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        pen.drawImage(tinted, 0, 0, image.width, image.height, null)
        pen.stroke = BasicStroke(2f)
        pen.color = Color(255, 40, 40)
        pen.drawLine(
            (columnOf(front.startKmX) - left) * scale, (rowOf(front.startKmY) - top) * scale,
            (columnOf(front.endKmX) - left) * scale, (rowOf(front.endKmY) - top) * scale
        )
        measured.catchments.forEach { outlet ->
            val column = (outlet.outletCell % world.width - left) * scale
            val row = (outlet.outletCell / world.width - top) * scale
            if (outlet.isTrunk) {
                pen.color = Color(255, 232, 64)
                pen.fillOval(column - 5, row - 5, 11, 11)
            } else {
                pen.color = Color(64, 232, 255)
                pen.fillOval(column - 3, row - 3, 7, 7)
            }
        }
        pen.dispose()
        return image
    }

    /** Half the base colour and half the tint, so the ground still reads through a basin. */
    private fun blend(base: Int, tint: Int): Int {
        fun channel(shift: Int) = (((base shr shift) and 0xFF) + ((tint shr shift) and 0xFF)) / 2
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
