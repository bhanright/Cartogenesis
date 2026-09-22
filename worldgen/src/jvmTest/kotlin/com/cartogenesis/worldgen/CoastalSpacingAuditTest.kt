package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.awt.BasicStroke
import java.awt.Color
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
 * X1d: how far apart the coastal valleys are, in kilometres, and what sets the figure.
 *
 * The author generated seed 969495 at 2048 and reported that the coasts carry closely spaced
 * valleys perpendicular to the shore about ten cells apart, each with a river and many with a delta
 * lobe. Ten cells is a measure of the grid. This class asks whether the landscape or the grid is
 * what the ten cells are counting, and it asks it with [RangeFront] — a finder that never reads a
 * drawn river, whose every rule is written down at that object before a number is collected here.
 *
 * **A measurement chunk.** Nothing in the generator is changed by it. Where a figure would have to
 * move the landscape, the figure is printed and the chunk stops at the report; the ledger row and
 * `docs/GEOGRAPHY.md` carry what was found.
 *
 * **Three classifications, decided in advance** (the brief's, kept verbatim so the answer is not
 * chosen after the numbers are in):
 *
 * - a spacing that stays near ten cells as the grid changes implicates the grid or the routing;
 * - a spacing that tracks the terrain stage's relief wavelength implicates the input;
 * - a spacing that scales with the front's half-width is physical.
 *
 * Resolution alone cannot separate the second from the third, because at fixed settings both the
 * belt's width and the relief wavelength are constants in kilometres and do not move with the grid
 * (`WorldGenConfig.atResolution` rescales every cell-valued width). So the grid sweep is joined by
 * controlled runs that move those two settings directly, and by the one thing the grid *does*
 * decide on this map: a cell is twice as wide as it is tall, so a spacing counted in cells is the
 * same number along a front that runs east-west and along one that runs north-south, while a
 * spacing set on the ground is the same *kilometres* along both. Every table below is therefore
 * split by the front's bearing.
 *
 * In the audit tier: twenty-one whole worlds, seven of them at 2048, plus ten controlled ones at
 * 512. One world is held at a time and reduced to its [RangeFront.Report] — which carries no grid
 * array — before the next is generated, which is T4's rule after the hosted runner went down under
 * three retained 2048 worlds.
 */
class CoastalSpacingAuditTest {

    private companion object {
        /** The world the author looked at. */
        const val AUTHORS_SEED = 969_495L
        const val AUTHORS_SIDE = 2048

        /** The six audited seeds, and the author's makes seven. */
        val AUDITED_SEEDS = listOf(7L, 42L, 1234L, 99L, 718_106L, 59_758L)
        val ALL_SEEDS = listOf(AUTHORS_SEED) + AUDITED_SEEDS

        /** The two seeds the controlled runs are made on: the author's and the largest-belt one. */
        val CONTROL_SEEDS = listOf(AUTHORS_SEED, 718_106L)

        const val CONTROL_SIDE = 512

        /** The author's reported figure, which is what the cell columns are read against. */
        const val REPORTED_SPACING_CELLS = 10.0

        val OUTPUT_DIR = File("build/maps")
    }

    private fun world(seed: Long, side: Int, edit: (WorldGenConfig) -> WorldGenConfig = { it }) =
        WorldGenerationEngine.generateBlocking(
            edit(WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(side, side))
        )

    /** Generates a world, reduces it to the report, and lets the world go before the next. */
    private fun reportOf(
        seed: Long,
        side: Int,
        beltFloorMetres: Float = RangeFront.BELT_FLOOR_METRES,
        edit: (WorldGenConfig) -> WorldGenConfig = { it }
    ): RangeFront.Report {
        var held: WorldMap? = world(seed, side, edit)
        val report = RangeFront.measure(held!!, beltFloorMetres)
        held = null
        System.gc()
        return report
    }

    // ------------------------------------------------------------------ the three grids

    @Test
    fun `the outlet spacing on every straight front at 512`() = sweep(512)

    @Test
    fun `the outlet spacing on every straight front at 1024`() = sweep(1024)

    @Test
    fun `the outlet spacing on every straight front at 2048`() = sweep(2048)

    /**
     * The table for one grid: every seed's fronts, their spacing in kilometres and in cells, the
     * divide-to-front distance and Hovius's ratio beside Earth's 2.1.
     *
     * Asserted: that the finder has a subject at all, on at least half the seeds. Everything else
     * is printed, because a spacing is what this chunk was sent to find out and not something it
     * was sent to hold to a bar.
     */
    private fun sweep(side: Int) {
        val pooled = ArrayList<Double>()
        val pooledCells = ArrayList<Double>()
        val pooledRatio = ArrayList<Double>()
        val byBearing = RangeFront.Bearing.entries.associateWith { ArrayList<Double>() }
        val byBearingCells = RangeFront.Bearing.entries.associateWith { ArrayList<Double>() }
        var seedsWithSample = 0

        ALL_SEEDS.forEach { seed ->
            val report = reportOf(seed, side)
            println("X1d SPACING ${report.line("seed $seed at $side")}")
            println("X1d SPACING ${report.bearingLine("seed $seed at $side")}")
            if (report.sample.isEmpty()) {
                println("X1d SPACING seed $seed at $side census: ${report.census}")
            } else {
                seedsWithSample++
                report.sample.forEach { front ->
                    pooled.add(front.medianSpacingKm!!)
                    report.spacingInCells(front)?.let { cells ->
                        pooledCells.add(cells)
                        byBearingCells.getValue(front.front.bearing).add(cells)
                    }
                    byBearing.getValue(front.front.bearing).add(front.medianSpacingKm!!)
                    front.hoviusRatio?.let { pooledRatio.add(it) }
                }
            }
        }

        println(
            ("X1d SPACING POOLED at %d over %d seeds and %d fronts: spacing %s km, %s cells " +
                "(the author read %.0f cells), divide-to-front ratio %s against Hovius's %.1f")
                .format(
                    side, seedsWithSample, pooled.size,
                    RangeFront.show(RangeFront.median(pooled)),
                    RangeFront.show(RangeFront.median(pooledCells)),
                    REPORTED_SPACING_CELLS,
                    RangeFront.show(RangeFront.median(pooledRatio)), RangeFront.HOVIUS_RATIO
                )
        )
        RangeFront.Bearing.entries.forEach { bearing ->
            val kilometres = byBearing.getValue(bearing)
            val cells = byBearingCells.getValue(bearing)
            if (kilometres.isEmpty()) {
                println("X1d SPACING POOLED at $side, $bearing fronts: no sample")
            } else {
                println(
                    "X1d SPACING POOLED at %d, %s fronts: %d fronts, %s km, %s cells".format(
                        side, bearing, kilometres.size,
                        RangeFront.show(RangeFront.median(kilometres)),
                        RangeFront.show(RangeFront.median(cells))
                    )
                )
            }
        }
        assertTrue(
            seedsWithSample * 2 >= ALL_SEEDS.size,
            "the finder has a subject on at least half the seeds at $side: $seedsWithSample"
        )
    }

    // ------------------------------------------------------------------ across the grids

    /**
     * The author's seed and one other at all three grids, front by matched front.
     *
     * The matching rule is [RangeFront.match]'s and is stated there. What it is for: a front's
     * spacing at 512 and its spacing at 2048 are only comparable if they are the same front, and
     * courses are traced independently at each resolution with no identity shared between them, so
     * the fronts are paired by where they lie on the ground and by which way they run.
     *
     * Two worlds at 2048 and two at each of the others: the sweeps above already print the pooled
     * figures, and this case exists for the paired ones.
     */
    @Test
    fun `the same front measured at 512, 1024 and 2048`() {
        listOf(AUTHORS_SEED, 718_106L).forEach { seed ->
            val reports = listOf(512, 1024, 2048).associateWith { reportOf(seed, it) }
            val coarse = reports.getValue(512)
            listOf(1024, 2048).forEach { side ->
                val fine = reports.getValue(side)
                val matches = RangeFront.match(coarse, fine)
                println(
                    "X1d ACROSS seed %d, 512 against %d: %d of %d and %d fronts matched".format(
                        seed, side, matches.size, coarse.measured.size, fine.measured.size
                    )
                )
                val moved = ArrayList<Double>()
                matches.forEach { match ->
                    val coarseKm = match.coarse.medianSpacingKm
                    val fineKm = match.fine.medianSpacingKm
                    if (coarseKm == null || fineKm == null) return@forEach
                    moved.add(fineKm / coarseKm)
                    println(
                        ("X1d ACROSS seed %d front at (%.0f, %.0f) km, %s: %.1f km at 512 " +
                            "(%.2f cells) against %.1f km at %d (%.2f cells)").format(
                            seed, match.coarse.front.midKmX, match.coarse.front.midKmY,
                            match.coarse.front.bearing, coarseKm,
                            coarse.spacingInCells(match.coarse) ?: Double.NaN,
                            fineKm, side, fine.spacingInCells(match.fine) ?: Double.NaN
                        )
                    )
                }
                println(
                    "X1d ACROSS seed %d, 512 to %d: %d matched fronts carry a spacing, median %s of the 512 figure"
                        .format(seed, side, moved.size, RangeFront.show(RangeFront.median(moved)))
                )
            }
        }
    }

    // ------------------------------------------------------------------ the controlled runs

    /**
     * The two suspects moved directly: the relief band's wavelength and the belt's half-width.
     *
     * At 512 on two seeds, because a controlled run has to be affordable and the grid sweep already
     * says what the grid does. The relief wavelength is `TerrainConfig.reliefCornerKm`, which
     * `TerrainStage.ReliefBand` turns into the scale the base relief lives at; the belt's
     * half-width is `TectonicsConfig.andeanWidthCells` for a coastal range and `collisionWidthCells`
     * for a plateau, moved together so a world's belts are all narrower or all broader rather than
     * one kind of them.
     *
     * A halving and a doubling of each, which is a full octave either side of the stock figure —
     * enough that a spacing which tracked either setting could not be mistaken for noise, and not
     * so much that the world stops being the same world.
     */
    @Test
    fun `the relief wavelength and the belt width moved, with the spacing beside them`() {
        CONTROL_SEEDS.forEach { seed ->
            val runs = LinkedHashMap<String, RangeFront.Report>()
            runs["stock"] = reportOf(seed, CONTROL_SIDE)
            listOf(0.5, 2.0).forEach { factor ->
                runs["relief wavelength x$factor"] = reportOf(seed, CONTROL_SIDE) { config ->
                    config.copy(
                        terrain = config.terrain.copy(
                            reliefCornerKm = config.terrain.reliefCornerKm * factor
                        )
                    )
                }
            }
            listOf(0.5, 2.0).forEach { factor ->
                runs["belt half-width x$factor"] = reportOf(seed, CONTROL_SIDE) { config ->
                    config.copy(
                        tectonics = config.tectonics.copy(
                            andeanWidthCells =
                                (config.tectonics.andeanWidthCells * factor).toFloat(),
                            collisionWidthCells =
                                (config.tectonics.collisionWidthCells * factor).toFloat()
                        )
                    )
                }
            }
            runs.forEach { (label, report) ->
                println("X1d CONTROL ${report.line("seed $seed at $CONTROL_SIDE, $label")}")
                println("X1d CONTROL ${report.bearingLine("seed $seed at $CONTROL_SIDE, $label")}")
            }
            val stock = runs.getValue("stock").pooledSpacingKm
            runs.forEach { (label, report) ->
                val moved = report.pooledSpacingKm
                if (stock == null || moved == null) {
                    println("X1d CONTROL seed $seed, $label: no sample either side, no ratio")
                } else {
                    println(
                        "X1d CONTROL seed %d, %s: spacing %.2f of the stock world's".format(
                            seed, label, moved / stock
                        )
                    )
                }
            }
        }
    }

    /**
     * Whether the belt floor the fronts are found at is what decides the spacing.
     *
     * [RangeFront.BELT_FLOOR_METRES] is derived rather than tuned, but a finder whose answer walked
     * with its own threshold would not be measuring the landscape. One world, three floors, the
     * same three numbers printed.
     */
    @Test
    fun `the belt floor the fronts are found at does not decide the spacing`() {
        var held: WorldMap? = world(AUTHORS_SEED, CONTROL_SIDE)
        val world = held!!
        listOf(700f, RangeFront.BELT_FLOOR_METRES, 1_500f).forEach { floor ->
            val report = RangeFront.measure(world, floor)
            println("X1d FLOOR ${report.line("seed $AUTHORS_SEED at $CONTROL_SIDE, floor ${floor.toInt()} m")}")
        }
        held = null
        System.gc()
    }

    // ------------------------------------------------------------------ the incision's metric

    /**
     * The incision's cell metric against the tracer's rectangular kilometres, measured.
     *
     * `HydraulicErosion.cut` takes the distance to a cell's receiver as 1 for a cardinal step and
     * the square root of 2 for a diagonal one, in *cells*, and turns the drop into a slope by
     * multiplying by the number of columns. `RiverStage.stepKilometres` measures the same step as
     * `cellWidthKm` east or west, `cellHeightKm` north or south, and the hypotenuse of the two on a
     * diagonal. On this project's equirectangular grid those two rulers disagree by construction: a
     * row step covers half the ground a column step does, so a slope the incision computes down a
     * column is *half* the slope the ground has, and a diagonal step's true length is
     * `sqrt(1 + 0.25)` cell widths against the `sqrt(2)` the incision charges it.
     *
     * The consequence is anisotropy in what the rounds cut, by direction of flow, and it is a real
     * inconsistency to record. It is not by itself an explanation of a spacing — that is what this
     * chunk's classification is for — so what this case does is print the factor by direction and
     * how much of one world's drainage runs in each, and assert only that the two rulers disagree,
     * which is the fact the ledger row states.
     */
    @Test
    fun `the incision's ruler against the tracer's, by direction of flow`() {
        var held: WorldMap? = world(AUTHORS_SEED, CONTROL_SIDE)
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
                "%.3f (x%.2f); down a column %.3f against %.3f (x%.2f); diagonally %.3f against " +
                "%.3f (x%.2f)").format(
                AUTHORS_SEED, CONTROL_SIDE,
                chargedAlongRow, trueAlongRow, chargedAlongRow / trueAlongRow,
                chargedDownColumn, trueDownColumn, chargedDownColumn / trueDownColumn,
                chargedDiagonal, trueDiagonal, chargedDiagonal / trueDiagonal
            )
        )
        println(
            ("X1d METRIC seed %d at %d: of %d land steps, %.1f%% run along a row, %.1f%% down a " +
                "column and %.1f%% diagonally").format(
                AUTHORS_SEED, CONTROL_SIDE, steps,
                100.0 * alongRow / steps, 100.0 * downColumn / steps, 100.0 * diagonal / steps
            )
        )
        held = null
        System.gc()

        assertTrue(
            abs(chargedDownColumn / trueDownColumn - 1.0) > 0.01,
            "the two rulers disagree down a column, which is the fact the ledger row records"
        )
    }

    // ------------------------------------------------------------------ the renders

    /**
     * Three coastal crops on the author's world with the fronts and outlets the finder chose on them.
     *
     * Not a test: the eye's half of the measurement. The three crops are the three longest fronts
     * that carry a spacing and lie within a cell of the coast, so what is drawn is the ground the
     * author was looking at. The chord is drawn as a line, each trunk outlet as a mark on it, and
     * each trunk basin tinted, over the Fantasy render the application draws.
     */
    @Test
    fun `three coastal crops with the fronts and outlets drawn`() {
        OUTPUT_DIR.mkdirs()
        val world = world(AUTHORS_SEED, AUTHORS_SIDE)
        val report = RangeFront.measure(world)
        val coastal = report.sample
            .filter { nearTheCoast(world, it.front) }
            .sortedByDescending { it.front.lengthKm }
            .take(3)
        if (coastal.isEmpty()) {
            println("X1d RENDER: no coastal front carried a spacing, nothing drawn")
            return
        }
        val base = DebugMapDump().render(world, DebugMapDump.Mode.FANTASY)
        coastal.forEachIndexed { index, measured ->
            val name = "x1d-969495-2048-front${index + 1}.png"
            write(crop(world, base, measured), name)
            println(
                ("X1d RENDER %s: a %.0f km %s front at (%.0f, %.0f) km, %d trunk outlets, " +
                    "spacing %.1f km (%.2f cells), divide-to-front %.1f km").format(
                    name, measured.front.lengthKm, measured.front.bearing,
                    measured.front.midKmX, measured.front.midKmY, measured.trunks.size,
                    measured.medianSpacingKm ?: Double.NaN,
                    report.spacingInCells(measured) ?: Double.NaN,
                    measured.medianDivideToFrontKm ?: Double.NaN
                )
            )
        }
        println("X1d RENDER crops written to ${OUTPUT_DIR.absolutePath}")
    }

    /** Whether a front's chord passes within a belt's own straightness bar of open water. */
    private fun nearTheCoast(world: WorldMap, front: RangeFront.Front): Boolean {
        val cellWidthKm = world.config.cellWidthKm
        val cellHeightKm = world.config.cellHeightKm
        val samples = max(8, (front.lengthKm / RangeFront.FRONT_STRAIGHTNESS_KM).roundToInt())
        for (sample in 0..samples) {
            val at = front.lengthKm * sample / samples
            for (step in -2..2) {
                val away = step * RangeFront.FRONT_STRAIGHTNESS_KM
                val kmX = front.startKmX + front.alongX * at + front.landwardX * away
                val kmY = front.startKmY + front.alongY * at + front.landwardY * away
                val column = (kmX / cellWidthKm).roundToInt()
                val row = (kmY / cellHeightKm).roundToInt()
                if (column < 0 || column >= world.width || row < 0 || row >= world.height) continue
                if (!world.sea.isLand[row * world.width + column]) return true
            }
        }
        return false
    }

    /** One front's neighbourhood, with the chord, its outlets and the basins' reach drawn on. */
    private fun crop(
        world: WorldMap,
        base: BufferedImage,
        measured: RangeFront.Measured
    ): BufferedImage {
        val cellWidthKm = world.config.cellWidthKm
        val cellHeightKm = world.config.cellHeightKm
        val front = measured.front

        fun columnOf(kmX: Double) = (kmX / cellWidthKm).roundToInt()
        fun rowOf(kmY: Double) = (kmY / cellHeightKm).roundToInt()

        // A margin of the front's own divide-to-front distance either side, so the basins are in.
        val marginKm = max(measured.medianDivideToFrontKm ?: 0.0, RangeFront.SHORTEST_FRONT_KM / 4)
        val columns = listOf(front.startKmX, front.endKmX).map { columnOf(it) }
        val rows = listOf(front.startKmY, front.endKmY).map { rowOf(it) }
        val marginColumns = (marginKm / cellWidthKm).roundToInt()
        val marginRows = (marginKm / cellHeightKm).roundToInt()
        val left = max(0, columns.min() - marginColumns)
        val right = min(world.width - 1, columns.max() + marginColumns)
        val top = max(0, rows.min() - marginRows)
        val bottom = min(world.height - 1, rows.max() + marginRows)

        val width = right - left + 1
        val height = bottom - top + 1
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.graphics.drawImage(base, -left, -top, null)

        val graphics = image.createGraphics()
        graphics.stroke = BasicStroke(2f)
        graphics.color = Color(255, 64, 64)
        graphics.drawLine(
            columnOf(front.startKmX) - left, rowOf(front.startKmY) - top,
            columnOf(front.endKmX) - left, rowOf(front.endKmY) - top
        )
        graphics.color = Color(255, 232, 64)
        measured.trunks.forEach { trunk ->
            val column = trunk.outletCell % world.width - left
            val row = trunk.outletCell / world.width - top
            graphics.fillOval(column - 4, row - 4, 9, 9)
        }
        graphics.dispose()
        return image
    }

    private fun write(image: BufferedImage, name: String) {
        ImageIO.write(image, "png", File(OUTPUT_DIR, name))
    }
}
