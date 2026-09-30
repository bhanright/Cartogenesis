package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.LayerCapture
import com.cartogenesis.worldgen.PinRecord
import com.cartogenesis.worldgen.PinRecordRewriter
import com.cartogenesis.worldgen.PinRecords
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

    /**
     * Where each entry was written and how it reads, for record mode to rewrite it in place: a
     * known failure by its key, a list of seeds too small to measure by its layer and detector.
     */
    val knownLines = HashMap<String, WrittenLine>()
    val insufficientLines = HashMap<Pair<String, Detector>, MutableList<WrittenLine>>()

    /** One entry as written: its file and line, its text trimmed, and the seeds it lists, if any. */
    class WrittenLine(val site: PinRecords.SourceLine, val text: String, val seeds: List<Long> = emptyList())

    fun finding(layer: String, detector: Detector, name: String) {
        findings[layer to detector] = name
    }

    fun known(key: String, signature: String) {
        signatures[key] = Signature.parse(signature)
        knownLines[key] = WrittenLine(PinRecords.sourceLineOutside(Expectations::class.java), knownLine(key, signature))
    }

    fun insufficient(layer: String, detector: Detector, vararg seeds: Long) {
        for (seed in seeds) insufficient.add(Census.key(seed, layer, detector))
        insufficientLines.getOrPut(layer to detector) { ArrayList() }.add(
            WrittenLine(PinRecords.sourceLineOutside(Expectations::class.java), insufficientLine(layer, detector, seeds.toList()), seeds.toList())
        )
    }

    companion object {
        /** A known failure's entry as [GeometryExpectations] writes it and the census prints it. */
        fun knownLine(key: String, signature: String): String =
            "known(${PinRecordRewriter.quoted(key)}, ${PinRecordRewriter.quoted(signature)})"

        /** A list of seeds too small to measure, as [GeometryExpectations] writes it. */
        fun insufficientLine(layer: String, detector: Detector, seeds: List<Long>): String =
            "insufficient(${PinRecordRewriter.quoted(layer)}, Detector.${detector.name}, ${seeds.joinToString { "${it}L" }})"
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
        if (PinRecords.recording) return recordedFailures(expected)
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

    /**
     * [failures] under record mode ([PinRecords]): what this census found, handed to [record] to
     * rewrite [GeometryExpectations] with, and only what record mode will not decide returned as
     * failures.
     */
    private fun recordedFailures(expected: Expectations): List<String> {
        val failed = ArrayList<String>()
        val found = Found(worlds.first().readings.map { it.layer })
        for (world in worlds) for (reading in world.readings) {
            if (reading.places > Judge.MOST_PLACES_PER_LAYER) {
                failed.add("[${world.name} ${reading.layer}] ${reading.places} places, past the ${Judge.MOST_PLACES_PER_LAYER} the place family was sized for")
            }
            for (detector in Detector.entries) {
                val key = key(world.seed, reading.layer, detector)
                found.made.add(key)
                val verdict = reading.verdict(detector)
                when (verdict.outcome) {
                    Outcome.VIOLATION -> found.violations[key] = Violation(reading.layer, detector, verdict.signature())
                    Outcome.INSUFFICIENT -> found.unmeasured.getOrPut(reading.layer to detector) { ArrayList() }.add(world.seed)
                    Outcome.CLEAN, Outcome.NOT_APPLICABLE -> Unit
                }
            }
        }
        return failed + record(expected, found, PinRecords.testName())
    }

    /**
     * What one census found, for [record]: every clause it made in census order (world, then
     * layer, then detector), each violation with its layer, and the seeds each layer and detector
     * could not measure, in the order the census read them. [layers] is the census's layer order.
     */
    class Found(val layers: List<String>) {
        val made = LinkedHashSet<String>()
        val violations = LinkedHashMap<String, Violation>()
        val unmeasured = LinkedHashMap<Pair<String, Detector>, MutableList<Long>>()
    }

    /** A violation the census found: its layer, its detector and its signature. */
    class Violation(val layer: String, val detector: Detector, val signature: Signature)

    /** One comb at the finer grid, and what the coarser grid found where it lies. */
    class CombPairing(val comb: Int, val line: String, val groundFixed: Boolean)

    companion object {
        fun key(seed: Long, layer: String, detector: Detector): String = "$seed/$layer/${detector.name}"

        /**
         * Records, as changes to the lines [expected] was written on, what [found] says those lines
         * should now read, and returns what record mode leaves to a person as failures.
         *
         * A known failure whose violation no longer matches its signature ([Signature.matches], so
         * rounding in the record does not churn it) is re-taken; one whose clause is clean, or can
         * no longer be measured, is taken off; a new violation is written after the known failure
         * before it in census order. A list of seeds too small to measure is re-listed in census
         * order where its seeds changed, taken off where none is left, and a new one is written
         * after the list before it. What is not recorded: a violation on a layer and detector with
         * no finding named, since naming a finding is a decision, and an entry naming a clause this
         * census does not make, since which worlds a census reads is one too.
         */
        fun record(expected: Expectations, found: Found, test: String): List<String> {
            val failed = ArrayList<String>()
            fun write(operation: PinRecord.Operation, at: Expectations.WrittenLine, anchor: String, old: String, new: String) =
                PinRecords.write(PinRecord("geometry census", operation, test, at.site.path, at.site.line, anchor, old, new))

            for ((key, written) in expected.knownLines) {
                if (key !in found.made) { failed.add("[$key] names no clause this census makes"); continue }
                val violation = found.violations[key]
                when {
                    violation == null -> write(PinRecord.Operation.DELETE_LINE, written, "", written.text, "")
                    !expected.signatures.getValue(key).matches(violation.signature) -> write(
                        PinRecord.Operation.REPLACE_LINE, written, "", written.text,
                        Expectations.knownLine(key, violation.signature.toString())
                    )
                }
            }
            val order = found.made.toList()
            for ((key, violation) in found.violations) {
                if (key in expected.knownLines) continue
                if (expected.findings[violation.layer to violation.detector] == null) {
                    failed.add("[$key] no finding named for ${violation.layer} and ${violation.detector.name}: name one, then record again")
                    continue
                }
                val line = Expectations.knownLine(key, violation.signature.toString())
                val place = order.indexOf(key)
                val before = order.subList(0, place).lastOrNull { it in expected.knownLines }
                val after = order.subList(place, order.size).firstOrNull { it in expected.knownLines }
                when {
                    before != null -> expected.knownLines.getValue(before).let { write(PinRecord.Operation.INSERT_AFTER, it, it.text, "", line) }
                    after != null -> expected.knownLines.getValue(after).let { write(PinRecord.Operation.INSERT_BEFORE, it, it.text, "", line) }
                    else -> failed.add("[$key] a new known failure and no known failure to write it beside: $line")
                }
            }

            val pairs = found.layers.flatMap { layer -> Detector.entries.map { layer to it } }
            for ((pair, writtenLines) in expected.insufficientLines) {
                val written = writtenLines.singleOrNull()
                if (written == null) { failed.add("[${pair.first}/${pair.second.name}] listed too small to measure on ${writtenLines.size} lines: re-list it by hand"); continue }
                val strangers = written.seeds.map { key(it, pair.first, pair.second) }.filter { it !in found.made }
                if (strangers.isNotEmpty()) { strangers.forEach { failed.add("[$it] names no clause this census makes") }; continue }
                val seeds = found.unmeasured[pair].orEmpty()
                when {
                    seeds.toSet() == written.seeds.toSet() -> Unit
                    seeds.isEmpty() -> write(PinRecord.Operation.DELETE_LINE, written, "", written.text, "")
                    else -> write(PinRecord.Operation.REPLACE_LINE, written, "", written.text, Expectations.insufficientLine(pair.first, pair.second, seeds))
                }
            }
            for ((pair, seeds) in found.unmeasured) {
                if (pair in expected.insufficientLines) continue
                val line = Expectations.insufficientLine(pair.first, pair.second, seeds)
                val place = pairs.indexOf(pair)
                val before = pairs.subList(0, place).lastOrNull { expected.insufficientLines[it]?.size == 1 }
                val after = pairs.subList(place, pairs.size).firstOrNull { expected.insufficientLines[it]?.size == 1 }
                when {
                    before != null -> expected.insufficientLines.getValue(before).single().let { write(PinRecord.Operation.INSERT_AFTER, it, it.text, "", line) }
                    after != null -> expected.insufficientLines.getValue(after).single().let { write(PinRecord.Operation.INSERT_BEFORE, it, it.text, "", line) }
                    else -> failed.add("[${pair.first}/${pair.second.name}] newly too small to measure and no list to write it beside: $line")
                }
            }
            return failed
        }

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
