package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
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
                "shelf off" to shipped.copy(sea = shipped.sea.copy(shelfWidth = 0f)),
                "enclosure off" to shipped.copy(sea = shipped.sea.copy(enclosedSeaIsLand = false)),
                "post-cut outlet off" to shipped.copy(sea = shipped.sea.copy(postCutOutlet = false))
            )
            sameField.forEach { (name, config) ->
                report(name, seed, SeaLevelStage.apply(eroded.height, config).isLand, cellsAcross,
                    pooled, pooledOctaves, pooledSpread)
            }

            // The rules upstream of the cut, each of which needs its own eroded field.
            val lowstandOff = shipped.copy(sea = shipped.sea.copy(lowstand = 0f))
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
     * Timed on the pass itself rather than as the difference between two whole cuts. That was tried
     * and it does not work: the whole cut at 2048 is four and a half seconds of priority floods and
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
        val landRelief = eroded.height.max() - cut.threshold
        val seaRelief = cut.threshold - eroded.height.min()

        repeat(2) { LittoralGrading.apply(cut, config.sea, landRelief, seaRelief) }
        var fastest = Long.MAX_VALUE
        repeat(5) {
            val elapsed = measureTime {
                LittoralGrading.apply(cut, config.sea, landRelief, seaRelief)
            }
            fastest = minOf(fastest, elapsed.inWholeMilliseconds)
        }
        println("F17 cost at $cellsAcross on seed 718106: the littoral pass is $fastest ms")
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
