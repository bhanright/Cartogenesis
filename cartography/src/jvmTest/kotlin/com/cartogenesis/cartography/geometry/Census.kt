package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.LayerCapture
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.sqrt

/**
 * What a census expects of today's worlds: the findings its known failures record, the signature
 * of each known violation, and the clauses it expects to be too small to measure.
 *
 * [findings] names the finding a violation records by layer and detector; [signatures] holds, by
 * `seed/layer/DETECTOR`, the violation each known failure is ([Signature]); [insufficient] the
 * clauses that cannot be measured on today's worlds at this grid, which is the census's expected
 * coverage: a clause that could be measured and no longer can fails, and so does one that can now
 * be measured and is still listed, so the table is kept true both ways.
 */
internal class Expectations {
    val findings = HashMap<Pair<String, Detector>, String>()
    val signatures = HashMap<String, Signature>()
    val insufficient = HashSet<String>()

    fun finding(layer: String, detector: Detector, name: String) {
        findings[layer to detector] = name
    }

    fun known(key: String, signature: String) {
        signatures[key] = Signature.parse(signature)
    }

    fun insufficient(layer: String, detector: Detector, vararg seeds: Long) {
        for (seed in seeds) insufficient.add(Census.key(seed, layer, detector))
    }
}

/**
 * The geometry guard run over a set of worlds: every layer, every detector, every world, what it
 * found and where, and the clauses each finding makes.
 *
 * Worlds are generated one at a time and dropped once read, so a census at 2048 holds one world
 * at a time. What is kept is the readings, which are small.
 */
internal class Census(val side: Int, worlds: Int) {

    val judge = Judge.forCensus(worlds)

    class WorldReading(
        val name: String,
        val seed: Long,
        val readings: List<LayerReading>,
        val generationSeconds: Double,
        val layerSeconds: Double,
        val detectorSeconds: Double
    )

    val worlds = ArrayList<WorldReading>()

    /** Generates [config] with a capture, reads every layer and keeps the readings. */
    fun read(config: WorldGenConfig): WorldReading {
        val name = "${config.seed}@${config.width}"
        val started = System.nanoTime()
        val capture = LayerCapture()
        val world = WorldGenerationEngine.generateBlocking(config, capture = capture)
        val generated = System.nanoTime()
        val layers = MapLayers.of(world, capture)
        val traced = System.nanoTime()
        val frame = GridFrame.of(config)
        val readings = layers.map { GeometryGuard.read(it, frame, judge) }
        val read = System.nanoTime()
        if (readings.any { reading -> Detector.entries.any { reading.outcome(it) == Outcome.VIOLATION } }) {
            val raster = CensusImages.rasterOf(world)
            val directory = java.io.File("build/geometry-census/$side")
            layers.zip(readings).forEach { (layer, reading) -> CensusImages.write(world, raster, name, layer, reading, directory) }
        }
        val reading = WorldReading(
            name, config.seed, readings,
            (generated - started) / 1e9, (traced - generated) / 1e9, (read - traced) / 1e9
        )
        worlds.add(reading)
        print(reading)
        return reading
    }

    fun print(world: WorldReading) {
        println("GEOMETRY CENSUS ${world.name}: generation %.1f s, tracing the layers %.1f s, the detectors %.1f s; %s"
            .format(world.generationSeconds, world.layerSeconds, world.detectorSeconds, judge))
        for (reading in world.readings) {
            for (detector in Detector.entries) {
                val verdict = reading.verdict(detector)
                println("GEOMETRY CENSUS ${world.name} | ${reading.layer} | ${detector.label} | ${verdict.outcome}" +
                    (if (verdict.outcome == Outcome.VIOLATION) " ${verdict.signature()}" else "") + " | " + verdict.text)
            }
        }
    }

    /** The table of outcomes over all worlds, a layer to a line, and which layers are clean. */
    fun summary(): String {
        val lines = ArrayList<String>()
        val layers = worlds.first().readings.map { it.layer }
        lines.add("%-26s %s".format("layer \\ detector (V violation, . clean, - insufficient, blank not asked)",
            Detector.entries.joinToString(" ") { it.name.take(8).padEnd(8) }))
        val clean = ArrayList<String>()
        for (layer in layers) {
            val cells = Detector.entries.map { detector ->
                worlds.joinToString("") { world ->
                    when (world.readings.first { it.layer == layer }.outcome(detector)) {
                        Outcome.VIOLATION -> "V"
                        Outcome.CLEAN -> "."
                        Outcome.INSUFFICIENT -> "-"
                        Outcome.NOT_APPLICABLE -> " "
                    }
                }.padEnd(8)
            }
            lines.add("%-26s %s".format(layer, cells.joinToString(" ")))
            val outcomes = worlds.flatMap { world ->
                Detector.entries.map { world.readings.first { it.layer == layer }.outcome(it) }
            }
            if (outcomes.none { it == Outcome.VIOLATION } && outcomes.any { it == Outcome.CLEAN }) clean.add(layer)
        }
        lines.add("clean on every detector that could measure it, in every world: ${clean.joinToString()}")
        lines.add("cost per world: " + worlds.joinToString("; ") {
            "%s %.1f s generating, %.1f s tracing, %.1f s detecting".format(it.name, it.generationSeconds, it.layerSeconds, it.detectorSeconds)
        })
        return lines.joinToString("\n")
    }

    /**
     * The census's findings as the lines that record them: every violation's signature and every
     * clause too small to measure, ready to be carried into a test's [Expectations].
     */
    fun recordLines(): List<String> {
        val lines = ArrayList<String>()
        for (world in worlds) for (reading in world.readings) for (detector in Detector.entries) {
            val verdict = reading.verdict(detector)
            if (verdict.outcome == Outcome.VIOLATION) {
                lines.add("GEOMETRY RECORD known(\"${key(world.seed, reading.layer, detector)}\", \"${verdict.signature()}\")")
            }
        }
        val layers = worlds.first().readings.map { it.layer }
        for (layer in layers) for (detector in Detector.entries) {
            val seeds = worlds.filter { world -> world.readings.first { it.layer == layer }.outcome(detector) == Outcome.INSUFFICIENT }
                .map { "${it.seed}L" }
            if (seeds.isNotEmpty()) lines.add("GEOMETRY RECORD insufficient(\"$layer\", Detector.${detector.name}, ${seeds.joinToString()})")
        }
        return lines
    }

    /**
     * Every clause the census makes, held to [expected]: known failures through
     * [KnownFailures.expect] with their signatures, clauses too small to measure against the
     * expected coverage (and written to the tier's report), and everything else asserted clean.
     * Every failure is collected and thrown together, so one run names all of them.
     */
    fun failures(expected: Expectations): List<String> {
        val failed = ArrayList<String>()
        val made = HashSet<String>()
        for (world in worlds) for (reading in world.readings) {
            if (reading.places > Judge.MOST_PLACES_PER_LAYER) {
                failed.add("[${world.name} ${reading.layer}] ${reading.places} places, past the ${Judge.MOST_PLACES_PER_LAYER} the place family was sized for")
            }
            for (detector in Detector.entries) {
                val key = key(world.seed, reading.layer, detector)
                made.add(key)
                val verdict = reading.verdict(detector)
                val signature = expected.signatures[key]
                val listedInsufficient = key in expected.insufficient
                when (verdict.outcome) {
                    Outcome.INSUFFICIENT -> when {
                        signature != null -> failed.add("[$key] a known failure can no longer be measured: ${verdict.text}")
                        !listedInsufficient -> failed.add("[$key] coverage lost, the clause can no longer be measured: ${verdict.text}")
                        else -> KnownFailures.insufficient(key, verdict.text)
                    }
                    Outcome.NOT_APPLICABLE -> if (signature != null || listedInsufficient) {
                        failed.add("[$key] listed, but the detector asks nothing of this layer")
                    }
                    Outcome.CLEAN, Outcome.VIOLATION -> {
                        if (listedInsufficient) failed.add("[$key] coverage gained, the clause is measured now: take it off the insufficient list")
                        try {
                            if (signature == null) reading.assertClean(detector, world.name)
                            else {
                                val finding = expected.findings[reading.layer to detector]
                                    ?: error("no finding named for ${reading.layer} and ${detector.name}")
                                KnownFailures.expect(finding, signature) { reading.assertClean(detector, world.name) }
                            }
                        } catch (failure: AssertionError) {
                            failed.add("[$key] ${failure.message}")
                        }
                    }
                }
            }
        }
        (expected.signatures.keys - made).forEach { failed.add("[$it] names no clause this census makes") }
        (expected.insufficient - made).forEach { failed.add("[$it] names no clause this census makes") }
        return failed
    }

    /** One comb at the finer grid, and what the coarser grid found where it lies. */
    class CombPairing(val comb: Int, val line: String, val groundFixed: Boolean)

    companion object {
        fun key(seed: Long, layer: String, detector: Detector): String = "$seed/$layer/${detector.name}"

        /** The layer-wide family a census of [worlds] worlds makes, over the layers [MapLayers] lists. */
        fun familySize(worlds: Int): Int = worlds * LAYERS * GeometryGuard.TESTS_PER_LAYER

        /** How many layers [MapLayers.of] returns; `GeometryGuardTest` checks it against a world. */
        const val LAYERS = 28

        /** How far apart, in kilometres, a comb at one grid and one at the other may lie and be one comb. */
        const val SAME_COMB_KM = 150.0

        /**
         * The spacing ratio between a comb fixed in cells (1: the same count of cells at both grids)
         * and one fixed on the ground (2: twice the cells at a grid twice as fine), taken at their
         * geometric mean, `sqrt(2)`.
         */
        val GROUND_FIXED_RATIO = sqrt(2.0)

        /**
         * Each comb [fine] found at a grid twice as fine as [coarse]'s, matched with the nearest comb
         * [coarse] found along the same bearing within [SAME_COMB_KM]: fixed on the ground where its
         * spacing in cells has doubled, and the grid's where it has not or where it has no partner.
         */
        fun pairCombs(fine: LayerReading, fineFrame: GridFrame, coarse: LayerReading, coarseFrame: GridFrame): List<CombPairing> =
            fine.combs.mapIndexed { index, comb ->
                val x = comb.anchorXCells * fineFrame.cellWidthKm
                val y = comb.anchorYCells * fineFrame.cellHeightKm
                fun distance(other: Combs.Comb): Double =
                    lengthOf(other.anchorXCells * coarseFrame.cellWidthKm - x, other.anchorYCells * coarseFrame.cellHeightKm - y)
                val match = coarse.combs
                    .filter { fineFrame.bearingGapDegrees(it.bearingDegrees, comb.bearingDegrees) <= Combs.PARALLEL_DEGREES * 2 }
                    .minByOrNull(::distance)
                if (match == null || distance(match) > SAME_COMB_KM) {
                    CombPairing(index, "${comb.describe(fineFrame)} | no comb at ${coarseFrame.cellsAcross} within $SAME_COMB_KM km: stands", false)
                } else {
                    val ratio = comb.spacingCells / match.spacingCells
                    val ground = ratio >= GROUND_FIXED_RATIO
                    CombPairing(
                        index,
                        "%s | at %d %.2f cells apart, %.2f times: %s".format(
                            comb.describe(fineFrame), coarseFrame.cellsAcross, match.spacingCells, ratio,
                            if (ground) "fixed on the ground, let stand" else "fixed in cells, the grid's"
                        ),
                        ground
                    )
                }
            }
    }
}
