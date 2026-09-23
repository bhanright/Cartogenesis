package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.LayerCapture
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig

/**
 * The geometry guard run over a set of worlds: every layer, every detector, every world, what it
 * found and where, and the clauses each finding makes.
 *
 * Worlds are generated one at a time and dropped once read, so a census at 2048 holds one world
 * at a time. What is kept is the readings, which are small.
 */
internal class Census(val side: Int, val familySize: Int) {

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
        val natural = NaturalFigures.of(frame)
        val readings = layers.map { GeometryGuard.read(it, frame, familySize, natural.cornersPer1000Km) }
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
        println("GEOMETRY CENSUS ${world.name}: generation %.1f s, tracing the layers %.1f s, the detectors %.1f s"
            .format(world.generationSeconds, world.layerSeconds, world.detectorSeconds))
        for (reading in world.readings) {
            for (detector in Detector.entries) {
                println("GEOMETRY CENSUS ${world.name} | ${reading.layer} | ${detector.label} | ${reading.outcome(detector)} | " +
                    (reading.absent ?: reading.describe(detector)))
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
     * Every clause the census makes, with the known failures run through [KnownFailures.expect].
     *
     * A clause is made wherever a detector could measure a layer; where it could not, the layer is
     * reported insufficient and nothing is asserted of it — unless it is on [known], where a
     * finding that has become unmeasurable is itself a failure. Every failure is collected and
     * thrown together, so one run names all of them.
     */
    fun failures(known: Map<String, String>): List<String> {
        val failed = ArrayList<String>()
        for (world in worlds) for (reading in world.readings) for (detector in Detector.entries) {
            val key = key(world.seed, reading.layer, detector)
            val finding = known[key]
            val outcome = reading.outcome(detector)
            if (finding == null && (outcome == Outcome.INSUFFICIENT || outcome == Outcome.NOT_APPLICABLE)) continue
            try {
                if (finding == null) reading.assertClean(detector, world.name)
                else KnownFailures.expect(finding) { reading.assertClean(detector, world.name) }
            } catch (failure: AssertionError) {
                failed.add("[$key] ${failure.message}")
            }
        }
        val unused = known.keys - worlds.flatMap { world ->
            world.readings.flatMap { reading -> Detector.entries.map { key(world.seed, reading.layer, it) } }
        }.toSet()
        unused.forEach { failed.add("[$it] names no clause this census makes") }
        return failed
    }

    companion object {
        fun key(seed: Long, layer: String, detector: Detector): String = "$seed/$layer/${detector.name}"

        /** The family a census of [worlds] worlds makes, over the layers [MapLayers] lists. */
        fun familySize(worlds: Int): Int = worlds * LAYERS * GeometryGuard.TESTS_PER_LAYER

        /** How many layers [MapLayers.of] returns; `GeometryGuardTest` checks it against a world. */
        const val LAYERS = 28
    }
}
