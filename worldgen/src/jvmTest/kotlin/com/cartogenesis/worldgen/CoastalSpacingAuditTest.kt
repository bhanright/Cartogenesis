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
 * what the ten cells are counting, with [RangeFront] — a finder that never reads a drawn river, and
 * whose every rule (belt, front eligibility, exit, outlet, catchment floor, trunk and divide
 * qualification, along-front spacing, bearing, cross-resolution matching and what an empty sample
 * reports) is written down at that object before a number is collected here.
 *
 * **A measurement.** Nothing in the generator is changed by it; the finding is in
 * docs/DESIGN_LEDGER.md, X1d, and in `docs/GEOGRAPHY.md`.
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
 * reduced to its [RangeFront.Report] — which carries no grid array — before the next is made, which
 * is T4's rule after the hosted runner went down under three retained 2048 worlds; then ten
 * controlled worlds and two floor and metric worlds at 512.
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

        val OUTPUT_DIR = File("build/maps")
    }

    private fun config(seed: Long, side: Int, edit: (WorldGenConfig) -> WorldGenConfig) =
        edit(WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(side, side))

    /**
     * Generates one world, reduces it to its report, hands it to [whileHeld] if the caller wants to
     * draw it, and lets it go before anything else is generated.
     */
    private fun reportOf(
        seed: Long,
        side: Int,
        beltFloorMetres: Float = RangeFront.BELT_FLOOR_METRES,
        edit: (WorldGenConfig) -> WorldGenConfig = { it },
        whileHeld: ((WorldMap) -> Unit)? = null
    ): RangeFront.Report {
        val beforeGeneration = System.nanoTime()
        var held: WorldMap? = WorldGenerationEngine.generateBlocking(config(seed, side, edit))
        val beforeFinder = System.nanoTime()
        val report = RangeFront.measure(held!!, beltFloorMetres)
        val after = System.nanoTime()
        whileHeld?.invoke(held)
        held = null
        System.gc()
        println(
            "X1d COST seed %d at %d: %.1f s to generate, %.1f s to find the fronts".format(
                seed, side, (beforeFinder - beforeGeneration) / 1e9, (after - beforeFinder) / 1e9
            )
        )
        return report
    }

    // ------------------------------------------------------------------ the table

    /**
     * Every seed at every grid, front by front, and the same front matched across the grids.
     *
     * Each seed's three worlds are generated one after another and reduced to reports, so the
     * matching reads three reports and never two worlds. The author's own world at 2048 is drawn
     * while it is held, which is the render half of the measurement.
     *
     * Asserted: only that the finder has a subject on at least half the seeds at every grid.
     * Everything else is printed, because a spacing is what this was sent to find out and not
     * something it was sent to hold to a bar.
     */
    @Test
    fun `the outlet spacing on every straight front, at 512, 1024 and 2048, and across them`() {
        val pooled = SIDES.associateWith { ArrayList<RangeFront.Report>() }
        val acrossKm = HashMap<String, ArrayList<Double>>()
        val acrossCells = HashMap<String, ArrayList<Double>>()

        ALL_SEEDS.forEach { seed ->
            val reports = SIDES.associateWith { side ->
                val draw = seed == AUTHORS_SEED && side == AUTHORS_SIDE
                reportOf(seed, side, whileHeld = if (draw) ::drawTheAuthorsWorld else null)
            }
            reports.forEach { (side, report) ->
                pooled.getValue(side).add(report)
                printFronts(report, "seed $seed at $side", config(seed, side) { it })
            }
            listOf(512 to 1024, 512 to 2048, 1024 to 2048).forEach { (coarseSide, fineSide) ->
                val coarse = reports.getValue(coarseSide)
                val fine = reports.getValue(fineSide)
                val matches = RangeFront.match(coarse, fine)
                println(
                    "X1d ACROSS seed %d, %d against %d: %d of %d and %d fronts matched".format(
                        seed, coarseSide, fineSide, matches.size,
                        coarse.measured.size, fine.measured.size
                    )
                )
                matches.forEach { match ->
                    val coarseKm = match.coarse.medianSpacingKm ?: return@forEach
                    val fineKm = match.fine.medianSpacingKm ?: return@forEach
                    val coarseCells = coarse.spacingInCells(match.coarse)!!
                    val fineCells = fine.spacingInCells(match.fine)!!
                    val key = "$coarseSide to $fineSide"
                    acrossKm.getOrPut(key) { ArrayList() }.add(fineKm / coarseKm)
                    acrossCells.getOrPut(key) { ArrayList() }.add(fineCells / coarseCells)
                    println(
                        ("X1d ACROSS seed %d front at (%.0f, %.0f) km, %s%s: %.1f km (%.2f cells, " +
                            "half-width %s km) at %d against %.1f km (%.2f cells, half-width %s km) " +
                            "at %d").format(
                            seed, match.coarse.front.midKmX, match.coarse.front.midKmY,
                            match.coarse.front.bearing, if (match.coarse.coastal) ", coastal" else "",
                            coarseKm, coarseCells, RangeFront.show(match.coarse.medianDivideToFrontKm),
                            coarseSide, fineKm, fineCells,
                            RangeFront.show(match.fine.medianDivideToFrontKm), fineSide
                        )
                    )
                }
            }
        }

        SIDES.forEach { side -> printPooled(pooled.getValue(side), "at $side") }
        listOf("512 to 1024", "512 to 2048", "1024 to 2048").forEach { key ->
            val kilometres = acrossKm[key].orEmpty()
            val cells = acrossCells[key].orEmpty()
            val grid = key.split(" to ").let { it[1].toDouble() / it[0].toDouble() }
            println(
                ("X1d ACROSS POOLED %s over %d matched fronts carrying a spacing at both: the fine " +
                    "spacing is %s of the coarse in kilometres (IQR %s) and %s in cells (IQR %s); a " +
                    "spacing set by the grid reads %.2f and 1.00, one set on the ground 1.00 and %.2f")
                    .format(
                        key, kilometres.size,
                        RangeFront.show(RangeFront.median(kilometres), 2),
                        showRange(RangeFront.quartiles(kilometres)),
                        RangeFront.show(RangeFront.median(cells), 2),
                        showRange(RangeFront.quartiles(cells)), 1.0 / grid, grid
                    )
            )
        }

        SIDES.forEach { side ->
            val withSample = pooled.getValue(side).count { it.sample.isNotEmpty() }
            assertTrue(
                withSample * 2 >= ALL_SEEDS.size,
                "the finder has a subject on at least half the seeds at $side: $withSample"
            )
        }
    }

    /** One line per front, then the world's census. */
    private fun printFronts(report: RangeFront.Report, label: String, config: WorldGenConfig) {
        println("X1d FRONTS $label: ${report.census}")
        report.measured.forEach { measured ->
            val front = measured.front
            println(
                ("X1d FRONT %s: %.0f km %s%s at (%.0f, %.0f) km on %s, %d catchments (%d trunks, " +
                    "%d corners set aside); trunk spacing %s km = %s cells; catchment spacing %s km " +
                    "= %s cells; half-width %s km against a stamp of %s km; ratio %s").format(
                    label, front.lengthKm, front.bearing, if (measured.coastal) " coastal" else "",
                    front.midKmX, front.midKmY, measured.boundaryClass ?: "-",
                    measured.catchments.size, measured.trunks.size, measured.cornersSetAside,
                    RangeFront.show(measured.medianSpacingKm),
                    RangeFront.show(report.spacingInCells(measured)),
                    RangeFront.show(measured.medianCatchmentSpacingKm),
                    RangeFront.show(measured.medianCatchmentSpacingKm?.let {
                        front.cellsAlong(it, report.cellWidthKm, report.cellHeightKm)
                    }),
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
     * Two medians of the spacing: over fronts (each front's own median, each front once) and over
     * gaps (every gap once), with the count behind each. The ratio is the median of the fronts'
     * own ratios, which is Hovius's statistic; the half-width is the median of every trunk's.
     */
    private fun printPooled(reports: List<RangeFront.Report>, label: String) {
        val groups = listOf<Pair<String, (RangeFront.Measured) -> Boolean>>(
            "all fronts" to { _ -> true },
            "coastal fronts" to { it.coastal },
            "east-west fronts" to { it.front.bearing == RangeFront.Bearing.EAST_WEST },
            "north-south fronts" to { it.front.bearing == RangeFront.Bearing.NORTH_SOUTH },
            "diagonal fronts" to { it.front.bearing == RangeFront.Bearing.DIAGONAL },
            "coastal east-west" to {
                it.coastal && it.front.bearing == RangeFront.Bearing.EAST_WEST
            },
            "coastal north-south" to {
                it.coastal && it.front.bearing == RangeFront.Bearing.NORTH_SOUTH
            }
        )
        val seedsWithSample = reports.count { it.sample.isNotEmpty() }
        println(
            "X1d POOLED %s: %d of %d worlds carry a front with a trunk spacing".format(
                label, seedsWithSample, reports.size
            )
        )
        groups.forEach { (name, keep) ->
            val frontSpacingKm = ArrayList<Double>()
            val frontSpacingCells = ArrayList<Double>()
            val frontRatios = ArrayList<Double>()
            val gapKm = ArrayList<Double>()
            val gapCells = ArrayList<Double>()
            val halfWidths = ArrayList<Double>()
            val combKm = ArrayList<Double>()
            val combCells = ArrayList<Double>()
            reports.forEach { report ->
                report.measured.filter(keep).forEach { measured ->
                    measured.medianSpacingKm?.let { frontSpacingKm.add(it) }
                    report.spacingInCells(measured)?.let { frontSpacingCells.add(it) }
                    measured.hoviusRatio?.let { frontRatios.add(it) }
                    val front = measured.front
                    measured.spacingsKm.forEach {
                        gapKm.add(it)
                        gapCells.add(front.cellsAlong(it, report.cellWidthKm, report.cellHeightKm))
                    }
                    if (measured.spacingsKm.isNotEmpty()) {
                        measured.trunks.forEach { halfWidths.add(it.divideToFrontKm) }
                    }
                    measured.catchmentSpacingsKm.forEach {
                        combKm.add(it)
                        combCells.add(front.cellsAlong(it, report.cellWidthKm, report.cellHeightKm))
                    }
                }
            }
            if (frontSpacingKm.isEmpty()) {
                println("X1d POOLED $label, $name: no sample")
                return@forEach
            }
            println(
                ("X1d POOLED %s, %s: %d fronts, trunk spacing %s km (IQR %s) = %s cells; over %d " +
                    "gaps %s km = %s cells; half-width %s km over %d trunks; ratio %s (IQR %s) " +
                    "against Hovius's %.1f; comb %s km = %s cells over %d gaps (the author read " +
                    "%.0f cells)").format(
                    label, name, frontSpacingKm.size,
                    RangeFront.show(RangeFront.median(frontSpacingKm)),
                    showRange(RangeFront.quartiles(frontSpacingKm)),
                    RangeFront.show(RangeFront.median(frontSpacingCells)),
                    gapKm.size, RangeFront.show(RangeFront.median(gapKm)),
                    RangeFront.show(RangeFront.median(gapCells)),
                    RangeFront.show(RangeFront.median(halfWidths)), halfWidths.size,
                    RangeFront.show(RangeFront.median(frontRatios), 2),
                    showRange(RangeFront.quartiles(frontRatios), 2), RangeFront.HOVIUS_RATIO,
                    RangeFront.show(RangeFront.median(combKm)),
                    RangeFront.show(RangeFront.median(combCells)), combKm.size,
                    REPORTED_SPACING_CELLS
                )
            )
        }
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
     * is not — so no front is matched from one run to another; each run's pooled figures are read
     * against the stock run's.
     */
    @Test
    fun `the relief wavelength and the belt width moved, with the spacing beside them`() {
        CONTROL_SEEDS.forEach { seed ->
            val runs = LinkedHashMap<String, RangeFront.Report>()
            runs["stock"] = reportOf(seed, CONTROL_SIDE)
            listOf(0.5, 2.0).forEach { factor ->
                runs["relief wavelength x$factor"] = reportOf(seed, CONTROL_SIDE, edit = { config ->
                    config.copy(
                        terrain = config.terrain.copy(
                            reliefCornerKm = config.terrain.reliefCornerKm * factor
                        )
                    )
                })
            }
            listOf(0.5, 2.0).forEach { factor ->
                runs["belt half-width x$factor"] = reportOf(seed, CONTROL_SIDE, edit = { config ->
                    config.copy(
                        tectonics = config.tectonics.copy(
                            andeanWidthCells = (config.tectonics.andeanWidthCells * factor).toFloat(),
                            collisionWidthCells =
                                (config.tectonics.collisionWidthCells * factor).toFloat()
                        )
                    )
                })
            }
            runs.forEach { (label, report) ->
                printFronts(report, "seed $seed at $CONTROL_SIDE, $label", config(seed, CONTROL_SIDE) { it })
                printPooled(listOf(report), "seed $seed at $CONTROL_SIDE, $label")
            }
            val stock = runs.getValue("stock")
            val stockSpacing = RangeFront.median(stock.gapsKm(trunksOnly = true))
            val stockHalfWidth = RangeFront.median(stock.halfWidthsKm())
            runs.forEach { (label, report) ->
                val spacing = RangeFront.median(report.gapsKm(trunksOnly = true))
                val halfWidth = RangeFront.median(report.halfWidthsKm())
                println(
                    ("X1d CONTROL seed %d, %s: trunk spacing %s km over %d gaps (%s of stock), " +
                        "half-width %s km (%s of stock), comb %s km over %d gaps").format(
                        seed, label, RangeFront.show(spacing), report.gapsKm(true).size,
                        RangeFront.show(ratioOf(spacing, stockSpacing), 2),
                        RangeFront.show(halfWidth),
                        RangeFront.show(ratioOf(halfWidth, stockHalfWidth), 2),
                        RangeFront.show(RangeFront.median(report.gapsKm(trunksOnly = false))),
                        report.gapsKm(trunksOnly = false).size
                    )
                )
            }
        }
    }

    private fun ratioOf(value: Double?, reference: Double?): Double? =
        if (value == null || reference == null || reference == 0.0) null else value / reference

    /**
     * Whether the belt floor the fronts are found at is what decides the spacing.
     *
     * [RangeFront.BELT_FLOOR_METRES] is argued rather than tuned, but a finder whose answer walked
     * with its own threshold would not be measuring the landscape. Two worlds, four floors, the
     * same figures printed at each.
     */
    @Test
    fun `the belt floor the fronts are found at does not decide the spacing`() {
        CONTROL_SEEDS.forEach { seed ->
            var held: WorldMap? = WorldGenerationEngine.generateBlocking(config(seed, CONTROL_SIDE) { it })
            val reports = listOf(1_000f, 1_500f, 2_000f, 2_500f).associateWith { floor ->
                RangeFront.measure(held!!, floor)
            }
            held = null
            System.gc()
            reports.forEach { (floor, report) ->
                printPooled(listOf(report), "seed $seed at $CONTROL_SIDE, floor ${floor.toInt()} m")
                println("X1d FLOOR seed $seed at $CONTROL_SIDE, floor ${floor.toInt()} m: ${report.census}")
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
        var held: WorldMap? = WorldGenerationEngine.generateBlocking(config(AUTHORS_SEED, CONTROL_SIDE) { it })
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
                "%.3f, so its slope reads x%.2f; down a column %.3f against %.3f, x%.2f; diagonally " +
                "%.3f against %.3f, x%.2f").format(
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
     * Not a measurement: the eye's half of one. A whole-world sheet with every front's chord, and
     * three crops, the three longest coastal fronts that carry a trunk spacing, each at twice the
     * grid's pixels. Over the Fantasy render with the drawn courses inked on it in blue — the render
     * reads the drawn rivers, the finder does not — the chord is red, each trunk basin is tinted,
     * each trunk outlet is a yellow mark and every other catchment's outlet a smaller cyan one.
     */
    private fun drawTheAuthorsWorld(world: WorldMap) {
        OUTPUT_DIR.mkdirs()
        val report = RangeFront.measure(world, keepBasinCells = true)
        val base = DebugMapDump().render(world, DebugMapDump.Mode.FANTASY)
        world.rivers.rivers.forEach { river ->
            river.cells.forEach { cell -> base.setRGB(cell % world.width, cell / world.width, 0x1F4FD0) }
        }

        val sheet = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_RGB)
        sheet.graphics.drawImage(base, 0, 0, null)
        val pen = sheet.createGraphics()
        pen.stroke = BasicStroke(3f)
        report.measured.forEach { measured ->
            pen.color = if (measured.coastal) Color(255, 40, 40) else Color(255, 160, 0)
            val front = measured.front
            pen.drawLine(
                (front.startKmX / world.config.cellWidthKm).roundToInt(),
                (front.startKmY / world.config.cellHeightKm).roundToInt(),
                (front.endKmX / world.config.cellWidthKm).roundToInt(),
                (front.endKmY / world.config.cellHeightKm).roundToInt()
            )
        }
        pen.dispose()
        ImageIO.write(sheet, "png", File(OUTPUT_DIR, "x1d-969495-2048-fronts.png"))

        val coastal = report.sample.filter { it.coastal }.sortedByDescending { it.front.lengthKm }.take(3)
        if (coastal.isEmpty()) {
            println("X1d RENDER: no coastal front carried a spacing, no crop drawn")
            return
        }
        coastal.forEachIndexed { index, measured ->
            val name = "x1d-969495-2048-front${index + 1}.png"
            val crop = crop(world, base, measured)
            ImageIO.write(crop, "png", File(OUTPUT_DIR, name))
            println(
                ("X1d RENDER %s: a %.0f km %s front at (%.0f, %.0f) km on %s, %d trunks and %d " +
                    "catchments, trunk spacing %s km (%s cells), comb %s km, half-width %s km, ratio %s")
                    .format(
                        name, measured.front.lengthKm, measured.front.bearing,
                        measured.front.midKmX, measured.front.midKmY, measured.boundaryClass ?: "-",
                        measured.trunks.size, measured.catchments.size,
                        RangeFront.show(measured.medianSpacingKm),
                        RangeFront.show(report.spacingInCells(measured)),
                        RangeFront.show(measured.medianCatchmentSpacingKm),
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

        // A margin of the front's own half-width either side, so the trunk basins are in.
        val marginKm = max(measured.medianDivideToFrontKm ?: 0.0, RangeFront.SHORTEST_FRONT_KM / 4) +
            RangeFront.FRONT_STRAIGHTNESS_KM
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
