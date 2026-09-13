package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.FlowRouting
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The same world at three grids, measured in physical units.
 *
 * The audit's N2, folded into S1 because S1 is what makes it possible. Before the pipeline had
 * units there was no way to ask whether 512 and 2048 were the same world: every quantity a guard
 * could measure was a count of cells, and a count of cells is four times itself on a grid twice as
 * fine whether or not the world has changed. Measured in kilometres, metres and shares of the land
 * instead, the question has an answer, and the per-stage contracts that used to stand in for it —
 * `ResolutionScalingTest`'s hand-carried rescalings — become arithmetic.
 *
 * The answer, on the first run of it, is **no**: the relief is the same world at every grid and
 * nothing else is. That is what the suite exists to say, and it says it the way `EarthLikeness`
 * does — every metric printed with what a finer grid *should* do beside it, asserted where the
 * generator holds and reported as a finding where it does not, with no bar moved to fit. See
 * [TOLERANCES] for each expectation and where it comes from.
 */
internal object ScaleFree {

    /** Every metric this suite compares across grids, for one world. */
    class Measurement(
        val label: String,
        val cellsAcross: Int,
        val squareKilometresPerCell: Double,
        /** Highest land less deepest floor, in metres, on `WorldScale`'s own ruler. */
        val reliefSpanMetres: Double,
        /** The length of the land-water boundary, in kilometres, as a staircase of cell edges. */
        val coastlineKm: Double,
        /** Channel length per unit of land, in kilometres per square kilometre. */
        val drainageDensityKmPerKm2: Double,
        /** The largest standing body of fresh water, in square kilometres. */
        val largestLakeKm2: Double,
        /** Ice as a share of the land. */
        val iceShareOfLand: Double,
        /** Desert as a share of the land in each of the three latitude bands. */
        val desertByBand: DoubleArray,
        val landKm2: Double
    )

    /** The three bands `GeographyAuditTest` reports the deserts in, by absolute latitude. */
    val BAND_EDGES_DEGREES = doubleArrayOf(15.0, 45.0, 90.0)

    /**
     * The catchment a cell needs before this suite calls it a channel, in square kilometres.
     *
     * An area and not a count of cells, which is the whole point: a support area picks out the same
     * network on the same ground at every grid, where a count of cells picks out a network four
     * times denser each time the grid doubles. Five thousand square kilometres is eighteen cells at
     * 512 and two hundred and ninety-one at 2048 — the Severn above Gloucester, so the network it
     * extracts is the one a map of a whole world would draw.
     *
     * Deliberately not `RiverConfig.sourceFlowShare`, which is a share of the world's *runoff* and
     * so answers to the climate as well as the terrain, and deliberately not the drawn courses,
     * which `RiverConfig.maxRivers` caps at four hundred whatever the grid.
     */
    const val CHANNEL_SUPPORT_KM2 = 5_000.0

    fun measure(world: WorldMap, label: String): Measurement {
        val config = world.config
        val scale = config.scale
        val cellsAcross = world.width
        val cellsDown = world.height
        val cellCount = cellsAcross * cellsDown
        val cellWidthKm = scale.cellWidthKm(cellsAcross)
        val cellHeightKm = scale.cellHeightKm(cellsDown)
        val cellAreaKm2 = cellWidthKm * cellHeightKm
        val isLand = world.sea.isLand
        val relative = world.sea.relativeElevation.data

        var highestMetres = 0.0
        var deepestMetres = 0.0
        for (cell in 0 until cellCount) {
            val metres =
                if (isLand[cell]) scale.metresAboveShoreline(relative[cell]).toDouble()
                else scale.metresBelowShoreline(relative[cell]).toDouble()
            if (metres > highestMetres) highestMetres = metres
            if (metres < deepestMetres) deepestMetres = metres
        }

        // The coastline, edge by edge. A neighbour to the east or west shares a vertical edge one
        // cell tall; one to the north or south shares a horizontal edge one cell wide. Counted from
        // the land side only, so each edge is counted once.
        var coastlineKm = 0.0
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!isLand[cell]) continue
                val east = row * cellsAcross + (column + 1) % cellsAcross
                val west = row * cellsAcross + (column - 1 + cellsAcross) % cellsAcross
                if (!isLand[east]) coastlineKm += cellHeightKm
                if (!isLand[west]) coastlineKm += cellHeightKm
                if (row > 0 && !isLand[cell - cellsAcross]) coastlineKm += cellWidthKm
                if (row < cellsDown - 1 && !isLand[cell + cellsAcross]) coastlineKm += cellWidthKm
            }
        }

        // The channel network the terrain carries, extracted by support area — see
        // [CHANNEL_SUPPORT_KM2] for why that and not the drawn courses.
        val catchmentCells = FlowRouting.accumulate(
            cellsAcross, cellsDown, isLand, world.rivers.filledElevation,
            world.rivers.flowTarget, world.sea.landCellCount
        ) { 1f }
        val supportCells = CHANNEL_SUPPORT_KM2 / cellAreaKm2
        val diagonalKm = sqrt(cellWidthKm * cellWidthKm + cellHeightKm * cellHeightKm)
        var channelKm = 0.0
        var landCells = 0L
        for (cell in 0 until cellCount) {
            if (!isLand[cell]) continue
            landCells++
            if (catchmentCells.data[cell] < supportCells) continue
            val receiver = world.rivers.flowTarget[cell]
            if (receiver < 0) continue
            val step = when {
                receiver == cell + cellsAcross || receiver == cell - cellsAcross -> cellHeightKm
                receiver / cellsAcross == cell / cellsAcross -> cellWidthKm
                else -> diagonalKm
            }
            channelKm += step
        }
        val landKm2 = landCells * cellAreaKm2

        val largestLakeKm2 =
            (world.rivers.lakes.lakes.maxOfOrNull { it.cellCount } ?: 0) * cellAreaKm2

        var iceCells = 0L
        val desertCells = DoubleArray(BAND_EDGES_DEGREES.size)
        val bandCells = DoubleArray(BAND_EDGES_DEGREES.size)
        for (cell in 0 until cellCount) {
            if (!isLand[cell]) continue
            if (world.climate.biome[cell] == Biome.ICE_SHEET) iceCells++
            val latitude = abs(ClimateStage.latitudeOf(cell / cellsAcross, cellsDown))
            val band = BAND_EDGES_DEGREES.indexOfFirst { latitude <= it }.coerceAtLeast(0)
            bandCells[band]++
            if (world.climate.biome[cell] == Biome.DESERT) desertCells[band]++
        }
        val desertByBand = DoubleArray(BAND_EDGES_DEGREES.size) {
            if (bandCells[it] == 0.0) 0.0 else desertCells[it] / bandCells[it]
        }

        return Measurement(
            label = label,
            cellsAcross = cellsAcross,
            squareKilometresPerCell = cellAreaKm2,
            reliefSpanMetres = highestMetres - deepestMetres,
            coastlineKm = coastlineKm,
            drainageDensityKmPerKm2 = if (landKm2 <= 0.0) 0.0 else channelKm / landKm2,
            largestLakeKm2 = largestLakeKm2,
            iceShareOfLand = if (landCells == 0L) 0.0 else iceCells.toDouble() / landCells,
            desertByBand = desertByBand,
            landKm2 = landKm2
        )
    }

    fun print(measurement: Measurement) {
        val bands = measurement.desertByBand.joinToString(" ") { "%.3f".format(it) }
        println(
            ("SCALEFREE %-16s %,10.0f km2/cell  relief %,7.0f m  coast %,10.0f km  " +
                "drainage %.5f km/km2  lake %,9.0f km2  ice %.4f  desert %s  land %,12.0f km2")
                .format(
                    measurement.label,
                    measurement.squareKilometresPerCell,
                    measurement.reliefSpanMetres,
                    measurement.coastlineKm,
                    measurement.drainageDensityKmPerKm2,
                    measurement.largestLakeKm2,
                    measurement.iceShareOfLand,
                    bands,
                    measurement.landKm2
                )
        )
    }

    /**
     * What a metric should do between a coarse grid and a finer one, and why.
     *
     * [expectedFor] is the ratio a *correct* finer grid gives — one where the metric is genuinely
     * scale-free, and something other than one where a finer grid legitimately resolves more of the
     * same landscape. [factor] is how far the measurement may sit from that before it is worth
     * saying so, and [asserted] whether the generator meets it today. A metric it does not meet is
     * printed as a finding with its figures rather than given a tolerance it happens to fit.
     */
    class Tolerance(
        val name: String,
        val expectedFor: (Double) -> Double,
        val factor: Double,
        val why: String,
        val asserted: Boolean
    )

    /**
     * The tolerances, in the order the report prints them.
     *
     * `refinement` throughout is the ratio of the coarse cell's width to the fine one's: 2 from
     * 512 to 1024, 4 from 512 to 2048.
     */
    val TOLERANCES = listOf(
        Tolerance(
            name = "relief",
            expectedFor = { 1.0 },
            factor = 1.10,
            asserted = true,
            why = "WorldScale declares both ends of the range, so a world that reaches them at one" +
                " grid reaches them at every grid; what is left is whether a seed's own extremes" +
                " get there, and a tenth covers a world whose deepest trench is a cell short"
        ),
        Tolerance(
            name = "coastline",
            expectedFor = { 1.0 },
            factor = 1.25,
            asserted = false,
            why = "a staircase of cell edges traces the same boundary at any ruler — it does not" +
                " obey Richardson's law, which is about a ruler laid along a coast rather than" +
                " around its cells — so a finer grid should give the same length unless it has" +
                " resolved inlets the coarse one could not. Measured x1.01, x1.49, x0.85 and x0.98" +
                " from 512 to 1024: seed 42 finds half as much coast again and seed 1234 loses a" +
                " seventh, which is a different coastline rather than a better-drawn one"
        ),
        Tolerance(
            name = "drainage",
            expectedFor = { 1.0 },
            factor = 1.35,
            asserted = true,
            why = "the network is extracted at a support area in square kilometres, so it is the" +
                " same network on the same ground at both grids and its length should differ only" +
                " by how finely the D8 path between two points is drawn. Measured x1.09, x1.07," +
                " x1.16 and x1.13 from 512 to 1024, all of them the finer path and none of them a" +
                " denser network. Extracted at `RiverConfig.sourceFlowShare` instead it reads x0.61" +
                " to x0.21, which is the threshold moving and not the drainage"
        ),
        Tolerance(
            name = "largest lake",
            expectedFor = { 1.0 },
            factor = 2.5,
            asserted = false,
            why = "chaos, and the audit's N3 says so: which basin ends up largest is sensitive to" +
                " everything upstream of it. Measured x1.86, x5.08, x1.03 and x1.14 from 512 to" +
                " 1024, which is wider than the factor `OutletIncisionTest` measured on the" +
                " largest lake *in the land*, so it is reported rather than asserted"
        ),
        Tolerance(
            name = "ice share",
            expectedFor = { 1.0 },
            factor = 1.30,
            asserted = false,
            why = "a share of the land, so scale-free by construction — except that the snow" +
                " balance reads each cell's altitude and a finer grid resolves higher ground." +
                " Measured x1.07, x1.88, x0.83 and x0.64 from 512 to 1024"
        )
    )

    /**
     * What one pair of grids has to say: the complaints, which are the asserted metrics that
     * missed, and the findings, which are the rest.
     */
    class Verdict(val complaints: List<String>, val findings: List<String>)

    /**
     * Prints the table and splits what it says into complaints and findings.
     *
     * The desert bands are compared as absolute shares rather than as a ratio, because a band whose
     * desert share is near zero has a ratio that means nothing.
     */
    fun compare(coarse: Measurement, fine: Measurement): Verdict {
        val refinement = fine.cellsAcross.toDouble() / coarse.cellsAcross
        val values = listOf(
            coarse.reliefSpanMetres to fine.reliefSpanMetres,
            coarse.coastlineKm to fine.coastlineKm,
            coarse.drainageDensityKmPerKm2 to fine.drainageDensityKmPerKm2,
            coarse.largestLakeKm2 to fine.largestLakeKm2,
            coarse.iceShareOfLand to fine.iceShareOfLand
        )
        val complaints = ArrayList<String>()
        val findings = ArrayList<Pair<Double, String>>()
        TOLERANCES.forEachIndexed { index, tolerance ->
            val (from, to) = values[index]
            if (from <= 0.0) return@forEachIndexed
            val expected = tolerance.expectedFor(refinement)
            val measured = to / from
            val departure = measured / expected
            println(
                ("SCALEFREE %-12s %-15s %,12.5f -> %,12.5f  x%.3f against x%.3f expected" +
                    "  (departure x%.3f)").format(
                    tolerance.name,
                    "${coarse.label}->${fine.cellsAcross}",
                    from, to, measured, expected, departure
                )
            )
            if (departure in (1.0 / tolerance.factor)..tolerance.factor) return@forEachIndexed
            val said = "${coarse.label} at ${coarse.cellsAcross} against ${fine.cellsAcross}:" +
                " ${tolerance.name} moved by x${"%.2f".format(measured)} where" +
                " x${"%.2f".format(expected)} is what a finer grid should give" +
                " (departure x${"%.2f".format(departure)}, outside ${tolerance.factor}) —" +
                " ${tolerance.why}"
            if (tolerance.asserted) {
                complaints.add(said)
            } else {
                findings.add(departureRank(departure) to said)
            }
        }
        coarse.desertByBand.indices.forEach { band ->
            val from = coarse.desertByBand[band]
            val to = fine.desertByBand[band]
            val edge = BAND_EDGES_DEGREES[band].toInt()
            println(
                "SCALEFREE %-12s %-15s %12.5f -> %12.5f  (%+.4f)".format(
                    "desert <$edge", "${coarse.label}->${fine.cellsAcross}", from, to, to - from
                )
            )
            if (abs(to - from) <= DESERT_BAND_TOLERANCE) return@forEach
            findings.add(
                abs(to - from) to
                    "${coarse.label} at ${coarse.cellsAcross} against ${fine.cellsAcross}: the" +
                        " desert share below $edge degrees moved from ${"%.3f".format(from)} to" +
                        " ${"%.3f".format(to)}, more than the $DESERT_BAND_TOLERANCE a band is" +
                        " allowed — a biome share is a share of the land and has no reason to move" +
                        " with the grid, so what moves it is the moisture march, whose rain rate is" +
                        " charged per cell of wind travel and whose orographic term is charged" +
                        " against a per-cell rise (W3 in REALISM_AUDIT.md)"
            )
        }
        return Verdict(complaints, findings.sortedByDescending { it.first }.map { it.second })
    }

    /**
     * How far a band's desert share may move between grids, as an absolute share of that band's
     * land.
     *
     * Five points. A biome share is scale-free by construction — a count of cells over a count of
     * cells — so the only thing that should move it is the finer grid resolving relief that changes
     * a cell's rainfall, and `GeographyAuditTest` already holds the pooled figure to Peel,
     * Finlayson and McMahon's per-band fractions with a tolerance of the same order.
     */
    const val DESERT_BAND_TOLERANCE = 0.05

    /** How far a departure sits from one, on a log scale, so half and double rank alike. */
    fun departureRank(departure: Double): Double =
        if (departure <= 0.0) Double.MAX_VALUE else abs(ln(departure))
}
