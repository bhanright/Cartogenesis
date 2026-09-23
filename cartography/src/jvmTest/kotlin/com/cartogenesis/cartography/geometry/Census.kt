package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.LayerCapture
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.abs

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
        val fullest = world.readings.maxBy { it.places }
        println("GEOMETRY CENSUS ${world.name}: generation %.1f s, tracing the layers %.1f s, the detectors %.1f s; %s; most places %d, %s"
            .format(world.generationSeconds, world.layerSeconds, world.detectorSeconds, judge, fullest.places, fullest.layer))
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
         * How far one comb's spacing read at two grids may disagree with itself on the ground, as a
         * share of it. A comb that just clears the regularity bar has each tooth up to
         * [Combs.TOOTH_JITTER_SPACINGS] of a spacing from its ruled place, and the spacing fitted
         * over the fewest teeth a comb has, [Combs.MINIMUM_TEETH], is out by at most the slope such a
         * jitter can put through them, `a * sum|k - mean| / sum (k - mean)^2` over the teeth's
         * indices (6.4% for six), and by half the scan's step at the closest spacing scanned (0.8%).
         * The two readings, one at each grid, may be out that far in opposite directions: 14%.
         */
        val SAME_SPACING_SHARE: Double = run {
            val indices = (0 until Combs.MINIMUM_TEETH).map { it.toDouble() }
            val mean = indices.average()
            val fitted = Combs.TOOTH_JITTER_SPACINGS * indices.sumOf { abs(it - mean) } / indices.sumOf { (it - mean) * (it - mean) }
            2 * (fitted + Combs.SPACING_STEP_CELLS / 2 / Combs.SHORTEST_SPACING_CELLS)
        }

        /**
         * How far across the bearing one tooth read at two grids may lie from itself, in spacings:
         * each reading within [Combs.TOOTH_JITTER_SPACINGS] of the ruled place both share, so twice
         * that. A second comb's tooth laid down at random falls within it half the time, which is why
         * a pairing asks for a whole comb's worth of teeth to agree and not one.
         */
        val SAME_TOOTH_SPACINGS: Double = 2 * Combs.TOOTH_JITTER_SPACINGS

        /**
         * Each comb [fine] found at a grid finer than [coarse]'s, matched with the nearest comb
         * [coarse] found along the same bearing within [SAME_COMB_KM], the distance taken the short
         * way round the seam. It is fixed on the ground, and let stand, only where the two are one
         * comb on the ground: the same spacing in kilometres, which is as many more cells between the
         * teeth as the finer grid has cells to the coarser's (within [SAME_SPACING_SHARE]), and at
         * least [Combs.MINIMUM_TEETH] of its teeth lying on the partner's (within
         * [SAME_TOOTH_SPACINGS]). Fixed in cells, at another spacing, with its teeth elsewhere, or
         * with no partner, it is the grid's and stands.
         */
        fun pairCombs(fine: LayerReading, fineFrame: GridFrame, coarse: LayerReading, coarseFrame: GridFrame): List<CombPairing> {
            val groundRatio = coarseFrame.cellWidthKm / fineFrame.cellWidthKm
            return fine.combs.mapIndexed { index, comb ->
                val x = comb.anchorXCells * fineFrame.cellWidthKm
                val y = comb.anchorYCells * fineFrame.cellHeightKm
                fun distance(other: Combs.Comb): Double = lengthOf(
                    fineFrame.wrappedEastwardKm(other.anchorXCells * coarseFrame.cellWidthKm - x),
                    other.anchorYCells * coarseFrame.cellHeightKm - y
                )
                val match = coarse.combs
                    .filter { fineFrame.bearingGapDegrees(it.bearingDegrees, comb.bearingDegrees) <= Combs.PARALLEL_DEGREES * 2 }
                    .minByOrNull(::distance)
                if (match == null || distance(match) > SAME_COMB_KM) {
                    CombPairing(index, "${comb.describe(fineFrame)} | no comb at ${coarseFrame.cellsAcross} within $SAME_COMB_KM km: stands", false)
                } else {
                    val ratio = comb.spacingCells / match.spacingCells
                    val onTheGround = ratio / groundRatio
                    val spacingAgrees = onTheGround <= 1 + SAME_SPACING_SHARE && onTheGround >= 1 / (1 + SAME_SPACING_SHARE)
                    val shared = sharedTeeth(comb, fineFrame, match, coarseFrame)
                    val ground = spacingAgrees && shared >= Combs.MINIMUM_TEETH
                    val verdict = when {
                        ground -> "fixed on the ground, let stand"
                        !spacingAgrees && abs(ratio - 1) <= SAME_SPACING_SHARE -> "fixed in cells, the grid's"
                        !spacingAgrees -> "at another spacing on the ground, not the same comb: stands"
                        else -> "its teeth are not the partner's, not the same comb: stands"
                    }
                    CombPairing(
                        index,
                        "%s | at %d %.2f cells apart, %.2f times (%.2f of the same spacing on the ground), %d teeth in common: %s".format(
                            comb.describe(fineFrame), coarseFrame.cellsAcross, match.spacingCells, ratio, onTheGround, shared, verdict
                        ),
                        ground
                    )
                }
            }
        }

        /**
         * How many of [fine]'s counted teeth lie on one of [coarse]'s, across [fine]'s bearing and
         * within [SAME_TOOTH_SPACINGS] of its spacing: both combs' teeth placed on the finer sheet,
         * east-west offsets taken the short way round the seam.
         */
        private fun sharedTeeth(fine: Combs.Comb, fineFrame: GridFrame, coarse: Combs.Comb, coarseFrame: GridFrame): Int {
            val xScale = coarseFrame.cellWidthKm / fineFrame.cellWidthKm
            val yScale = coarseFrame.cellHeightKm / fineFrame.cellHeightKm
            val coarseTeeth = coarse.toothOffsetsCells.map { offset ->
                val xFine = (coarse.anchorXCells + offset * coarse.acrossX) * xScale
                val yFine = (coarse.anchorYCells + offset * coarse.acrossY) * yScale
                val dx = fineFrame.wrappedEastwardKm((xFine - fine.anchorXCells) * fineFrame.cellWidthKm) / fineFrame.cellWidthKm
                dx * fine.acrossX + (yFine - fine.anchorYCells) * fine.acrossY
            }
            val tolerance = SAME_TOOTH_SPACINGS * fine.spacingCells
            return fine.toothOffsetsCells.count { offset -> coarseTeeth.any { abs(it - offset) <= tolerance } }
        }
    }
}
