package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.DrownedValleys
import com.cartogenesis.worldgen.pipeline.LittoralGrading
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlocking
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * Where the cell-scale fringe on every coast comes from, octave by octave.
 *
 * Asserts nothing. F17 begins with a diagnosis — William's crops at 1024 show every coastline, low
 * or mountainous, sheltered or exposed, carrying the same saw-tooth — and M1's pooled box dimension
 * of 1.20 sits inside Earth's 1.2 to 1.3 band, so the pooled figure cannot be what is wrong. This
 * asks the two questions the pooled figure cannot: at which scale the roughness sits, and which
 * upstream rule puts it there.
 *
 * The coastline is decided by one stage. `GlaciationStage` never touches [SeaLevelResult.isLand]
 * (its own KDoc says so, and the marine troughs are graded to the waterline rather than drowned),
 * and every stage after it reads the mask rather than writing it, so the whole of the answer is in
 * the height field erosion hands over and in the four rules `SeaLevelStage` applies to it. Each
 * variant below switches off exactly one of those and re-cuts; the ones that change nothing
 * upstream of erosion reuse the same eroded field, so what moves between two rows is that rule and
 * nothing else.
 */
class CoastVarietyAuditTest {

    private val seeds = listOf(7L, 42L, 1234L, 99L, 298405L)

    /** The shipped defaults at [cellsAcross], which is 62% ocean and fourteen plates. */
    private fun baseConfig(seed: Long, cellsAcross: Int) =
        WorldGenConfig(seed = seed, width = 512, height = 512)
            .atResolution(cellsAcross, cellsAcross)

    @Test
    fun `report the coast's roughness by octave, and which rule puts it there`() {
        val cellsAcross = 512
        println("F17 diagnosis: coastline roughness by octave, $cellsAcross x $cellsAcross")
        println(
            "variant | seed | edges | N1 | N2 | N4 | N8 | N16 | d(1-2) | d(2-4) | d(4-8) | " +
                "d(8-16) | pooled(4,8,16) | smooth share | sd"
        )

        val pooled = LinkedHashMap<String, MutableList<CoastRoughness.BoxCount>>()
        val pooledOctaves = LinkedHashMap<String, MutableList<CoastRoughness.BoxCount>>()
        val pooledSpread = LinkedHashMap<String, CoastRoughness.Spread>()

        seeds.forEach { seed ->
            val shipped = baseConfig(seed, cellsAcross)
            val terrain = TerrainStage.generate(shipped)
            val plates = PlateStage.generate(shipped, terrain)
            val eroded = erodeBlocking(shipped, plates.height)

            // The rules that live inside the sea stage: same eroded field, one rule off at a time.
            val sameField = listOf(
                "shipped" to shipped,
                "littoral grading off" to
                    shipped.copy(sea = shipped.sea.copy(littoralGrading = false)),
                "shelf off" to shipped.copy(sea = shipped.sea.copy(shelfWidthKm = 0.0)),
                "enclosure off" to shipped.copy(sea = shipped.sea.copy(enclosedSeaIsLand = false)),
                "post-cut outlet off" to shipped.copy(sea = shipped.sea.copy(postCutOutlet = false))
            )
            sameField.forEach { (name, config) ->
                report(name, seed, SeaLevelStage.apply(eroded.height, config).isLand, cellsAcross,
                    pooled, pooledOctaves, pooledSpread)
            }

            // The rules upstream of the cut, each of which needs its own eroded field.
            val lowstandOff = shipped.copy(sea = shipped.sea.copy(lowstandMetres = 0f))
            val lowstandOffField = erodeBlocking(lowstandOff, plates.height)
            report("lowstand off", seed, SeaLevelStage.apply(lowstandOffField.height, lowstandOff).isLand,
                cellsAcross, pooled, pooledOctaves, pooledSpread)

            val erosionOff = shipped.copy(erosion = shipped.erosion.copy(enabled = false))
            val erosionOffField = erodeBlocking(erosionOff, plates.height)
            report("erosion off", seed, SeaLevelStage.apply(erosionOffField.height, erosionOff).isLand,
                cellsAcross, pooled, pooledOctaves, pooledSpread)

            val detailOff = shipped.copy(tectonics = shipped.tectonics.copy(detailAmplitude = 0f))
            val detailOffPlates = PlateStage.generate(detailOff, terrain)
            val detailOffField = erodeBlocking(detailOff, detailOffPlates.height)
            report("plate detail noise off", seed,
                SeaLevelStage.apply(detailOffField.height, detailOff).isLand,
                cellsAcross, pooled, pooledOctaves, pooledSpread)
        }

        println()
        println("pooled over the five seeds")
        println("variant | N1 | N2 | N4 | N8 | N16 | d(1-2) | d(2-4) | d(4-8) | d(8-16) | pooled | smooth share | sd")
        pooled.keys.forEach { name ->
            val octaves = pooledOctaves.getValue(name).reduce { a, b -> a + b }
            val three = pooled.getValue(name).reduce { a, b -> a + b }
            val spread = pooledSpread.getValue(name)
            println(
                "$name | ${octaves.boxes.joinToString(" | ")} | " +
                    (0 until 4).joinToString(" | ") { format(octaves.dimensionAcrossOctave(it)) } +
                    " | ${format(three.dimension)} | ${format(spread.smoothShare)} | " +
                    format(spread.dimensionStandardDeviation)
            )
        }
    }

    /**
     * What the littoral pass costs at export resolution, on the processor.
     *
     * Plan ground rule 8: a per-cell pass is written against the accelerator seam's shape and
     * measured at 2048, and under fifty milliseconds it stays where it is, as H2's snow balance did.
     *
     * Both passes, timed on themselves rather than as the difference between two whole cuts. That
     * was tried and it does not work: the whole cut at 2048 is four and a half seconds of priority floods and
     * jump flooding, and its run-to-run spread is several hundred milliseconds, so the difference
     * came out at -431 ms on one run and +81 on another. The pass allocates its own arrays and reads
     * nothing but the cut it is handed, so calling it directly measures all of it.
     */
    @Test
    fun `report what the littoral pass costs at 2048`() {
        val cellsAcross = 2048
        val config = baseConfig(718106L, cellsAcross)
        val control = config.copy(sea = config.sea.copy(littoralGrading = false))
        val terrain = TerrainStage.generate(config)
        val plates = PlateStage.generate(config, terrain)
        val eroded = erodeBlocking(config, plates.height)
        val cut = SeaLevelStage.apply(eroded.height, control)

        repeat(2) {
            DrownedValleys.apply(cut, eroded.height, config)
            LittoralGrading.apply(cut, config)
        }
        var valleys = Long.MAX_VALUE
        var littoral = Long.MAX_VALUE
        repeat(5) {
            valleys = minOf(
                valleys,
                measureTime { DrownedValleys.apply(cut, eroded.height, config) }
                    .inWholeMilliseconds
            )
            littoral = minOf(
                littoral,
                measureTime { LittoralGrading.apply(cut, config) }
                    .inWholeMilliseconds
            )
        }
        println(
            "F17 cost at $cellsAcross on seed 718106: the drowned-valley fill is $valleys ms and " +
                "the littoral pass is $littoral ms"
        )
    }

    /**
     * The coastline measured with a ruler of one, two, four, eight and sixteen cells, which is the
     * measurement the octave table above cannot make.
     *
     * A box count at one cell has a ceiling of one box per position, so a coast with a tooth in
     * every cell runs into it and the finest octave reads smoother than the next one up. A length
     * has no ceiling. What this asks is whether the coast is the same shape at every scale the grid
     * resolves, which is what Richardson's straight lines say a real coast is, and where it is not.
     */
    @Test
    fun `report the coastline's length by ruler, and the excess at the cell`() {
        val cellsAcross = 512
        println("F17 Richardson lengths, $cellsAcross x $cellsAcross")
        println("variant | seed | L1 | L2 | L4 | L8 | L16 | D(1-2) | D(2-4) | D(4-8) | D(8-16) | D(4-16) | excess")

        seeds.forEach { seed ->
            val shipped = baseConfig(seed, cellsAcross)
            val terrain = TerrainStage.generate(shipped)
            val plates = PlateStage.generate(shipped, terrain)
            val eroded = erodeBlocking(shipped, plates.height)
            val variants = listOf(
                "shipped" to shipped,
                "2.0.2 (no coast passes)" to shipped.copy(
                    sea = shipped.sea.copy(littoralGrading = false, drownedValleyFill = false)
                ),
                "littoral only" to shipped.copy(sea = shipped.sea.copy(drownedValleyFill = false)),
                "valley fill only" to shipped.copy(sea = shipped.sea.copy(littoralGrading = false))
            )
            variants.forEach { (name, config) ->
                reportRulers(name, seed, SeaLevelStage.apply(eroded.height, config).isLand, cellsAcross)
            }

            val lowstandOff = shipped.copy(sea = shipped.sea.copy(lowstandMetres = 0f))
            val lowstandOffField = erodeBlocking(lowstandOff, plates.height)
            reportRulers(
                "lowstand off", seed,
                SeaLevelStage.apply(lowstandOffField.height, lowstandOff).isLand, cellsAcross
            )

            // Every drowned notch filled, whatever it drains: the ceiling on what filling notches
            // can do at all, and so the answer to how much of the excess at the cell is channels.
            val everyNotch = SeaLevelStage.applyWithValleyBar(
                eroded.height, shipped, resolvedShareOfCell = 1000f
            )
            reportRulers("every notch filled", seed, everyNotch.isLand, cellsAcross)

            // The floor this instrument has on this grid. A percentile cut through the integrated
            // noise with no erosion in it is the smoothest coast the generator can draw, and
            // whatever excess *it* shows over its own coarse octaves is the digitisation and not the
            // coast: a curve on a grid is a staircase, and a majority coarsening of a staircase is
            // not the same curve at half the scale.
            val erosionOff = shipped.copy(erosion = shipped.erosion.copy(enabled = false))
            val erosionOffField = erodeBlocking(erosionOff, plates.height)
            reportRulers(
                "erosion off (the floor)", seed,
                SeaLevelStage.apply(erosionOffField.height, erosionOff).isLand, cellsAcross
            )
        }

        // And the same on a shape with no texture at all, so the floor is not itself a property of
        // one seed's noise: a disc a quarter of the map across, which is analytically smooth.
        val disc = BooleanArray(cellsAcross * cellsAcross)
        val centre = cellsAcross / 2
        val radius = cellsAcross / 4
        for (row in 0 until cellsAcross) {
            for (column in 0 until cellsAcross) {
                val dy = (row - centre).toDouble()
                val dx = (column - centre).toDouble()
                disc[row * cellsAcross + column] = dx * dx + dy * dy <= radius.toDouble() * radius
            }
        }
        reportRulers("a plain disc", 0L, disc, cellsAcross)
    }

    private fun reportRulers(name: String, seed: Long, isLand: BooleanArray, cellsAcross: Int) {
        val rulers = listOf(1, 2, 4, 8, 16)
        val lengths = rulers.map {
            CoastRoughness.richardsonLength(isLand, cellsAcross, cellsAcross, it)
        }
        val octaves = (0 until 4).map {
            CoastRoughness.richardsonDimension(lengths[it], lengths[it + 1])
        }
        val coarse = CoastRoughness.richardsonDimensionOver(
            lengths.subList(2, 5), rulers.subList(2, 5)
        )
        println(
            "$name | $seed | ${lengths.joinToString(" | ") { "%.0f".format(it) }} | " +
                octaves.joinToString(" | ") { "%.3f".format(it) } +
                " | %.3f | %.3f".format(coarse, octaves[0] - coarse)
        )
    }

    private fun report(
        name: String,
        seed: Long,
        isLand: BooleanArray,
        cellsAcross: Int,
        pooled: MutableMap<String, MutableList<CoastRoughness.BoxCount>>,
        pooledOctaves: MutableMap<String, MutableList<CoastRoughness.BoxCount>>,
        pooledSpread: MutableMap<String, CoastRoughness.Spread>
    ) {
        val octaves = CoastRoughness.boundaryBoxCount(isLand, cellsAcross, cellsAcross)
        val three = CoastRoughness.coastlineBoxCount(isLand, cellsAcross, cellsAcross)
        val spread = CoastRoughness.spreadOfCoast(isLand, cellsAcross, cellsAcross)
        val edges = CoastRoughness.shorelineEdges(isLand, cellsAcross, cellsAcross)
        println(
            "$name | $seed | $edges | ${octaves.boxes.joinToString(" | ")} | " +
                (0 until 4).joinToString(" | ") { format(octaves.dimensionAcrossOctave(it)) } +
                " | ${format(three.dimension)} | ${format(spread.smoothShare)} | " +
                format(spread.dimensionStandardDeviation)
        )
        pooled.getOrPut(name) { ArrayList() }.add(three)
        pooledOctaves.getOrPut(name) { ArrayList() }.add(octaves)
        pooledSpread[name] = pooledSpread[name]?.plus(spread) ?: spread
    }

    private fun format(value: Double) = ((value * 1000).toInt() / 1000.0).toString()
}
