package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How far apart the coastal valleys are, in kilometres, and what sets the figure.
 *
 * The author generated seed 969495 at 2048 and reported that the coasts carry closely spaced
 * valleys perpendicular to the shore about ten cells apart, each with a river and many with a delta
 * lobe. Ten cells is a measure of the grid. This class asks whether the landscape or the grid is
 * what the ten cells are counting, with [RangeFront] — a finder whose fronts, basins and trunks
 * never read a drawn river, and whose every rule (belt, front eligibility, exit, outlet, catchment
 * floor, trunk and divide qualification, along-front spacing and the two combs, bearing,
 * cross-resolution matching and what an empty sample reports) is written down at that object before
 * a number is collected here.
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
 * controlled runs that move those two settings directly, and every table is split by the front's
 * bearing, which the grid *does* decide: a cell is twice as wide as it is tall, so each reading
 * predicts its own ratio between a front running east-west and one running north-south.
 *
 * - Set by the grid: the same count of cells on both, so twice the kilometres east-west.
 * - Set by the relief band: the same kilometres on both, so half the cells east-west; the band is
 *   isotropic in kilometres (`TerrainStage.ReliefBand`), though the noise it filters is drawn on a
 *   lattice with as many cycles down the map as across it.
 * - Set by the belt: `PlateStage` counts a belt's half-width in cells with no row scale, so an
 *   east-west belt is half as wide in kilometres as a north-south one, and a spacing that followed
 *   the half-width would be half the kilometres, and a quarter of the cells, east-west. The
 *   measured half-width is printed on every front, so this reading is tested by the ratio itself
 *   rather than by the bearing alone.
 *
 * In the audit tier: twenty-one whole worlds, seven of them at 2048, generated once each and each
 * reduced to its two reports — which carry no grid array — before the next is made, which is T4's
 * rule after the hosted runner went down under three retained 2048 worlds; then ten controlled
 * worlds and three more at 512 for the floor and the rulers.
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

        /** The two seeds the controlled runs are made on: the author's and the largest-belt one. */
        val CONTROL_SEEDS = listOf(AUTHORS_SEED, 718_106L)
        const val CONTROL_SIDE = 512

        /** The author's reported figure, which is what the cell columns are read against. */
        const val REPORTED_SPACING_CELLS = 10.0

        /** A crop is drawn of a front whose drawn comb has at least this many gaps to show. */
        const val FEWEST_DRAWN_GAPS_TO_CROP = 5

        val OUTPUT_DIR = File("build/maps")
    }

    /** The two kinds of front every world is measured at. */
    private enum class Floor(val metres: Float) {
        COAST(RangeFront.SHORELINE_FLOOR_METRES),
        RANGE(RangeFront.BELT_FLOOR_METRES);

        val label: String get() = if (this == COAST) "coast" else "range at ${metres.toInt()} m"
    }

    private fun config(seed: Long, side: Int, edit: (WorldGenConfig) -> WorldGenConfig = { it }) =
        edit(WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(side, side))

    /**
     * Generates one world, reduces it to a report at each floor, hands it to [whileHeld] if the
     * caller wants to draw it, and lets it go before anything else is generated.
     */
    private fun reportsOf(
        seed: Long,
        side: Int,
        edit: (WorldGenConfig) -> WorldGenConfig = { it },
        whileHeld: ((WorldMap) -> Unit)? = null
    ): Map<Floor, RangeFront.Report> {
        val beforeGeneration = System.nanoTime()
        var held: WorldMap? = WorldGenerationEngine.generateBlocking(config(seed, side, edit))
        val beforeFinder = System.nanoTime()
        val reports = Floor.entries.associateWith { RangeFront.measure(held!!, it.metres) }
        val after = System.nanoTime()
        whileHeld?.invoke(held!!)
        held = null
        System.gc()
        println(
            "X1d COST seed %d at %d: %.1f s to generate, %.1f s to find the fronts at two floors"
                .format(seed, side, (beforeFinder - beforeGeneration) / 1e9, (after - beforeFinder) / 1e9)
        )
        return reports
    }

    // ------------------------------------------------------------------ the table

    /**
     * Every seed at every grid, front by front, and the same front matched across the grids.
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

        ALL_SEEDS.forEach { seed ->
            val reports = SIDES.associateWith { side ->
                val draw = seed == AUTHORS_SEED && side == AUTHORS_SIDE
                reportsOf(seed, side, whileHeld = if (draw) ::drawTheAuthorsWorld else null)
            }
            Floor.entries.forEach { floor ->
                reports.forEach { (side, byFloor) ->
                    val report = byFloor.getValue(floor)
                    pooled.getOrPut(floor to side) { ArrayList() }.add(report)
                    printFronts(report, "seed $seed at $side, ${floor.label}", config(seed, side))
                }
                listOf(512 to 1024, 512 to 2048, 1024 to 2048).forEach { (coarseSide, fineSide) ->
                    val (kilometres, cells) = across.getOrPut("${floor.label}, $coarseSide to $fineSide") {
                        ArrayList<Double>() to ArrayList()
                    }
                    printMatches(
                        seed, floor, coarseSide, fineSide,
                        reports.getValue(coarseSide).getValue(floor),
                        reports.getValue(fineSide).getValue(floor),
                        kilometres, cells
                    )
                }
            }
        }

        Floor.entries.forEach { floor ->
            SIDES.forEach { side -> printPooled(pooled.getValue(floor to side), "${floor.label} at $side") }
            listOf(512 to 1024, 512 to 2048, 1024 to 2048).forEach { (coarseSide, fineSide) ->
                val key = "${floor.label}, $coarseSide to $fineSide"
                val (kilometres, cells) = across[key] ?: Pair(emptyList<Double>(), emptyList<Double>())
                val grid = fineSide.toDouble() / coarseSide
                println(
                    ("X1d ACROSS POOLED %s over %d matched fronts carrying a trunk spacing at both: " +
                        "the fine spacing is %s of the coarse in kilometres (IQR %s) and %s in cells " +
                        "(IQR %s); a spacing set by the grid reads %.2f and 1.00, one set on the " +
                        "ground 1.00 and %.2f").format(
                        key, kilometres.size,
                        RangeFront.show(RangeFront.median(kilometres), 2),
                        showRange(RangeFront.quartiles(kilometres), 2),
                        RangeFront.show(RangeFront.median(cells), 2),
                        showRange(RangeFront.quartiles(cells), 2), 1.0 / grid, grid
                    )
                )
            }
        }

        SIDES.forEach { side ->
            val withSample = pooled.getValue(Floor.COAST to side).count { it.sample.isNotEmpty() }
            assertTrue(
                withSample * 2 >= ALL_SEEDS.size,
                "a coast carries a trunk spacing on at least half the seeds at $side: $withSample"
            )
        }
    }

    /** One seed's fronts at two grids, paired, with the three spacings of each pair side by side. */
    private fun printMatches(
        seed: Long,
        floor: Floor,
        coarseSide: Int,
        fineSide: Int,
        coarse: RangeFront.Report,
        fine: RangeFront.Report,
        pooledKm: MutableList<Double>,
        pooledCells: MutableList<Double>
    ) {
        val matches = RangeFront.match(coarse, fine)
        println(
            "X1d ACROSS seed %d, %s, %d against %d: %d of %d and %d fronts matched".format(
                seed, floor.label, coarseSide, fineSide, matches.size,
                coarse.measured.size, fine.measured.size
            )
        )
        matches.forEach { match ->
            val coarseKm = match.coarse.medianSpacingKm
            val fineKm = match.fine.medianSpacingKm
            if (coarseKm != null && fineKm != null) {
                pooledKm.add(fineKm / coarseKm)
                pooledCells.add(fine.spacingInCells(match.fine)!! / coarse.spacingInCells(match.coarse)!!)
            }
            println(
                ("X1d ACROSS seed %d, %s, front at (%.0f, %.0f) km %s: trunk %s km, catchment %s " +
                    "km, drawn %s km, half-width %s km at %d; trunk %s km, catchment %s km, drawn %s " +
                    "km, half-width %s km at %d").format(
                    seed, floor.label, match.coarse.front.midKmX, match.coarse.front.midKmY,
                    match.coarse.front.bearing,
                    RangeFront.show(coarseKm), RangeFront.show(match.coarse.medianCatchmentSpacingKm),
                    RangeFront.show(match.coarse.medianDrawnSpacingKm),
                    RangeFront.show(match.coarse.medianDivideToFrontKm), coarseSide,
                    RangeFront.show(fineKm), RangeFront.show(match.fine.medianCatchmentSpacingKm),
                    RangeFront.show(match.fine.medianDrawnSpacingKm),
                    RangeFront.show(match.fine.medianDivideToFrontKm), fineSide
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
                ("X1d FRONT %s: %.0f km %s%s at (%.0f, %.0f) km on %s; %d catchments, %d trunks, %d " +
                    "set aside, %d drawn outlets; trunk %s km = %s cells, catchment %s km = %s " +
                    "cells, drawn %s km = %s cells; half-width %s km against a stamp of %s km; " +
                    "ratio %s").format(
                    label, front.lengthKm, front.bearing, if (measured.coastal) " coastal" else "",
                    front.midKmX, front.midKmY, measured.boundaryClass ?: "-",
                    measured.catchments.size, measured.trunks.size, measured.cornersSetAside,
                    measured.drawnAlongKm?.size ?: 0,
                    RangeFront.show(measured.medianSpacingKm), cells(measured, measured.medianSpacingKm),
                    RangeFront.show(measured.medianCatchmentSpacingKm),
                    cells(measured, measured.medianCatchmentSpacingKm),
                    RangeFront.show(measured.medianDrawnSpacingKm),
                    cells(measured, measured.medianDrawnSpacingKm),
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
     * ratios, which is Hovius's statistic; the half-width is the median of every trunk's. The two
     * combs are over gaps.
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
            val trunkGaps = Gaps()
            val catchmentGaps = Gaps()
            val drawnGaps = Gaps()
            reports.forEach { report ->
                report.measured.filter(keep).forEach { measured ->
                    measured.medianSpacingKm?.let { frontSpacingKm.add(it) }
                    report.spacingInCells(measured)?.let { frontSpacingCells.add(it) }
                    measured.hoviusRatio?.let { frontRatios.add(it) }
                    if (measured.spacingsKm.isNotEmpty()) {
                        measured.trunks.forEach { halfWidths.add(it.divideToFrontKm) }
                    }
                    trunkGaps.add(report, measured, measured.spacingsKm)
                    catchmentGaps.add(report, measured, measured.catchmentSpacingsKm)
                    drawnGaps.add(report, measured, measured.drawnSpacingsKm)
                }
            }
            val fronts = reports.sumOf { report -> report.measured.count(keep) }
            println(
                ("X1d POOLED %s, %s: %d fronts, %d with a trunk spacing: trunk %s km (IQR %s) = %s " +
                    "cells by front, %s by gap over %d gaps; half-width %s km over %d trunks; ratio %s " +
                    "(IQR %s) against Hovius's %.1f; catchment comb %s; drawn comb %s (the author " +
                    "read %.0f cells)").format(
                    label, name, fronts, frontSpacingKm.size,
                    RangeFront.show(RangeFront.median(frontSpacingKm)),
                    showRange(RangeFront.quartiles(frontSpacingKm)),
                    RangeFront.show(RangeFront.median(frontSpacingCells)),
                    trunkGaps.summary(), trunkGaps.kilometres.size,
                    RangeFront.show(RangeFront.median(halfWidths)), halfWidths.size,
                    RangeFront.show(RangeFront.median(frontRatios), 2),
                    showRange(RangeFront.quartiles(frontRatios), 2), RangeFront.HOVIUS_RATIO,
                    catchmentGaps.summary(), drawnGaps.summary(), REPORTED_SPACING_CELLS
                )
            )
        }
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

    // ------------------------------------------------------------------ the controlled runs

    /**
     * The two suspects moved directly: the relief band's wavelength and the belt's half-width.
     *
     * At 512 on two seeds, because a controlled run has to be affordable and the grid sweep already
     * says what the grid does. The relief wavelength is `TerrainConfig.reliefCornerKm`, which
     * `TerrainStage.ReliefBand` turns into the scale the base relief is loudest at; the belt's
     * half-width is `TectonicsConfig.andeanWidthCells` for a coastal range and
     * `collisionWidthCells` for a plateau, moved together so a world's belts are all narrower or all
     * broader rather than one kind of them. A halving and a doubling of each, a full octave either
     * side of the stock figure: enough that a spacing which tracked either setting could not be
     * mistaken for noise, and not so much that the world stops being the same world.
     *
     * A changed setting is a different world — the plates are the same, but the ground they carry
     * is not — so no front is matched from one run to another; each run's pooled gaps are read
     * against the stock run's.
     */
    @Test
    fun `the relief wavelength and the belt width moved, with the spacing beside them`() {
        CONTROL_SEEDS.forEach { seed ->
            val runs = LinkedHashMap<String, Map<Floor, RangeFront.Report>>()
            runs["stock"] = reportsOf(seed, CONTROL_SIDE)
            listOf(0.5, 2.0).forEach { factor ->
                runs["relief wavelength x$factor"] = reportsOf(seed, CONTROL_SIDE, edit = { config ->
                    config.copy(
                        terrain = config.terrain.copy(
                            reliefCornerKm = config.terrain.reliefCornerKm * factor
                        )
                    )
                })
            }
            listOf(0.5, 2.0).forEach { factor ->
                runs["belt half-width x$factor"] = reportsOf(seed, CONTROL_SIDE, edit = { config ->
                    config.copy(
                        tectonics = config.tectonics.copy(
                            andeanWidthCells = (config.tectonics.andeanWidthCells * factor).toFloat(),
                            collisionWidthCells =
                                (config.tectonics.collisionWidthCells * factor).toFloat()
                        )
                    )
                })
            }
            Floor.entries.forEach { floor ->
                runs.forEach { (label, byFloor) ->
                    printPooled(listOf(byFloor.getValue(floor)), "seed $seed at $CONTROL_SIDE, ${floor.label}, $label")
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
                        "X1d CONTROL seed %d, %s, %s: trunk %s; half-width %s; catchment comb %s; drawn comb %s"
                            .format(
                                seed, floor.label, label,
                                moved { it.gapsKm(trunksOnly = true) },
                                moved { it.halfWidthsKm() },
                                moved { r -> r.measured.flatMap { it.catchmentSpacingsKm } },
                                moved { r -> r.measured.flatMap { it.drawnSpacingsKm } }
                            )
                    )
                }
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
            var held: WorldMap? = WorldGenerationEngine.generateBlocking(config(seed, CONTROL_SIDE))
            val floors = listOf(0f, 1_000f, 1_500f, 2_000f, 2_500f)
            val reports = floors.associateWith { floor -> RangeFront.measure(held!!, floor) }
            held = null
            System.gc()
            reports.forEach { (floor, report) ->
                println("X1d FLOOR seed $seed at $CONTROL_SIDE, floor ${floor.toInt()} m: ${report.census}")
                printPooled(listOf(report), "seed $seed at $CONTROL_SIDE, floor ${floor.toInt()} m")
            }
        }
    }

    // ------------------------------------------------------------------ the two rulers

    /**
     * The incision's cell metric against the tracer's rectangular kilometres, measured.
     *
     * `HydraulicErosion.cut` takes the distance to a cell's receiver as 1 for a cardinal step and
     * the square root of 2 for a diagonal one, in *cells*, and turns the drop into a slope by
     * multiplying by the number of columns, so every step is charged in cell widths.
     * `RiverStage.stepKilometres` measures the same step as `cellWidthKm` east or west,
     * `cellHeightKm` north or south, and the hypotenuse of the two on a diagonal. On this grid those
     * two rulers disagree by construction: a row step covers half the ground a column step does, so
     * the slope the incision computes for a step down a column is *half* the slope the ground has,
     * and a diagonal step's true length is `sqrt(1 + 0.25)` cell widths against the `sqrt(2)` the
     * incision charges it. `FlowRouting.flowDirections` compares its facets on the same square
     * ruler, so the routing, too, reads a north-south fall as a fall over one cell width.
     *
     * The consequence is anisotropy in what the rounds cut, by direction of flow, and it is a real
     * inconsistency to record. It is not by itself an explanation of a spacing — that is what the
     * bearing split in the table is for — so this case prints the factor by direction and how much
     * of one world's drainage runs in each, and asserts only that the two rulers disagree, which is
     * the fact the ledger row states.
     */
    @Test
    fun `the incision's ruler against the tracer's, by direction of flow`() {
        var held: WorldMap? = WorldGenerationEngine.generateBlocking(config(AUTHORS_SEED, CONTROL_SIDE))
        val world = held!!
        val cellsAcross = world.width
        val cellWidthKm = world.config.cellWidthKm
        val cellHeightKm = world.config.cellHeightKm
        val diagonalKm = hypot(cellWidthKm, cellHeightKm)

        var alongRow = 0L
        var downColumn = 0L
        var diagonal = 0L
        val flowTarget = world.rivers.flowTarget
        for (cell in flowTarget.indices) {
            if (!world.sea.isLand[cell]) continue
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
        held = null
        System.gc()
        val steps = (alongRow + downColumn + diagonal).coerceAtLeast(1)

        // What the incision charges a step, in cell widths, against what the step really covers.
        val chargedAlongRow = 1.0
        val chargedDownColumn = 1.0
        val chargedDiagonal = sqrt(2.0)
        val trueAlongRow = 1.0
        val trueDownColumn = cellHeightKm / cellWidthKm
        val trueDiagonal = diagonalKm / cellWidthKm

        println(
            ("X1d METRIC seed %d at %d: a step along a row is charged %.3f cell widths and covers " +
                "%.3f, so the incision reads its slope at x%.2f of the ground's; down a column %.3f " +
                "against %.3f, x%.2f; diagonally %.3f against %.3f, x%.2f").format(
                AUTHORS_SEED, CONTROL_SIDE,
                chargedAlongRow, trueAlongRow, trueAlongRow / chargedAlongRow,
                chargedDownColumn, trueDownColumn, trueDownColumn / chargedDownColumn,
                chargedDiagonal, trueDiagonal, trueDiagonal / chargedDiagonal
            )
        )
        println(
            ("X1d METRIC seed %d at %d: of %d land steps, %.1f%% run along a row, %.1f%% down a " +
                "column and %.1f%% diagonally").format(
                AUTHORS_SEED, CONTROL_SIDE, steps,
                100.0 * alongRow / steps, 100.0 * downColumn / steps, 100.0 * diagonal / steps
            )
        )
        assertTrue(
            abs(chargedDownColumn / trueDownColumn - 1.0) > 0.01,
            "the two rulers disagree down a column, which is the fact the ledger row records"
        )
    }

    // ------------------------------------------------------------------ the renders

    /**
     * The author's world with the fronts and outlets the finder chose drawn over it.
     *
     * Not a measurement: the eye's half of one. A whole-world sheet with every front's chord (coasts
     * red, range fronts orange), and three crops at twice the grid's pixels: the three coasts
     * carrying a trunk spacing whose drawn comb is finest, among those with at least
     * [FEWEST_DRAWN_GAPS_TO_CROP] drawn gaps, since the finest comb is the author's complaint. Over
     * the Fantasy render with the drawn courses inked on it in blue — the render reads the drawn
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
            .filter { it.drawnSpacingsKm.size >= FEWEST_DRAWN_GAPS_TO_CROP }
            .sortedBy { it.medianDrawnSpacingKm }
            .take(3)
        if (chosen.isEmpty()) {
            println("X1d RENDER: no coast carried a trunk spacing and a drawn comb, no crop drawn")
            return
        }
        chosen.forEachIndexed { index, measured ->
            val name = "x1d-969495-2048-front${index + 1}.png"
            ImageIO.write(crop(world, base, measured), "png", File(OUTPUT_DIR, name))
            println(
                ("X1d RENDER %s: a %.0f km %s coast at (%.0f, %.0f) km, columns %d to %d and rows %d " +
                    "to %d, on %s; %d trunks and %d catchments; trunk %s km (%s cells), catchment " +
                    "comb %s km, drawn comb %s km (%s cells), half-width %s km, ratio %s").format(
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
                    RangeFront.show(measured.medianDrawnSpacingKm),
                    RangeFront.show(measured.medianDrawnSpacingKm?.let {
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
