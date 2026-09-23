package com.cartogenesis.cartography.geometry

/** The guard's detectors, by the names the census and the known-failures list use. */
internal enum class Detector(val label: String) {
    ISOTROPY("straight runs by bearing"),
    ALIGNED_SIDE("straight aligned side"),
    RECTANGLE("rectangularity"),
    FACETS("straight facets"),
    RIGHT_ANGLES("right-angle corners"),
    ARCS("circles and arcs"),
    COMBS("ruled lines")
}

/**
 * Every detector's reading of one layer, with the worst component named, and the clause each
 * detector's bar makes of it.
 */
internal class LayerReading(
    val layer: String,
    val frame: GridFrame,
    val outlineKm: Double,
    val runs: Int,
    val isotropy: List<BearingIsotropy.Result>,
    val rings: List<ComponentShapes.Ring>,
    val arcs: Arcs.Result,
    val combs: List<Combs.Comb>,
    val rightAngles: Int,
    val cornerRateBar: Double,
    val cornerLowerRate: Double,
    val absent: String?
) {
    private val measuredRings get() = rings.filter { it.measured }

    fun outcome(detector: Detector): Outcome = when {
        absent != null -> Outcome.INSUFFICIENT
        else -> when (detector) {
            Detector.ISOTROPY -> when {
                isotropy.any { it.outcome == Outcome.VIOLATION } -> Outcome.VIOLATION
                isotropy.any { it.outcome == Outcome.CLEAN } -> Outcome.CLEAN
                else -> Outcome.INSUFFICIENT
            }
            Detector.ALIGNED_SIDE -> ringOutcome(forRuns = true) { it.hasLongAlignedSide }
            Detector.RECTANGLE -> ringOutcome(forRuns = false) { it.isRectangle }
            Detector.FACETS -> ringOutcome(forRuns = true) { it.hasFacets }
            Detector.RIGHT_ANGLES -> when {
                measuredRings.any { it.hasCornerPair } -> Outcome.VIOLATION
                outlineKm <= 0.0 -> Outcome.INSUFFICIENT
                cornerLowerRate > cornerRateBar -> Outcome.VIOLATION
                else -> Outcome.CLEAN
            }
            Detector.ARCS -> when {
                outlineKm <= 0.0 -> Outcome.INSUFFICIENT
                arcs.arcs.isNotEmpty() || arcs.concentricSets.isNotEmpty() -> Outcome.VIOLATION
                else -> Outcome.CLEAN
            }
            Detector.COMBS -> when {
                outlineKm <= 0.0 -> Outcome.INSUFFICIENT
                combs.isNotEmpty() -> Outcome.VIOLATION
                else -> Outcome.CLEAN
            }
        }
    }

    private fun ringOutcome(forRuns: Boolean, past: (ComponentShapes.Ring) -> Boolean): Outcome {
        val judged = if (forRuns) rings.filter { it.measuredForRuns } else measuredRings
        return when {
            judged.any(past) -> Outcome.VIOLATION
            judged.isEmpty() -> Outcome.INSUFFICIENT
            else -> Outcome.CLEAN
        }
    }

    /** One line: the figure the detector's bar reads, and the worst component and where it is. */
    fun describe(detector: Detector): String = when (detector) {
        Detector.ISOTROPY -> {
            val worst = isotropy.maxByOrNull { if (it.outcome == Outcome.INSUFFICIENT) 0.0 else it.ratio }
            isotropy.joinToString("; ") { it.toString() } +
                (worst?.let { " | worst %.1f deg".format(it.gridBearingDegrees) } ?: "")
        }
        Detector.ALIGNED_SIDE -> worstRing(
            true, { it.longestAlignedKm / it.alignedAllowanceKm },
            { "longest aligned side %.0f km against %.0f allowed at %s".format(it.longestAlignedKm, it.alignedAllowanceKm, it.where(frame)) }
        )
        Detector.RECTANGLE -> worstRing(
            false, { it.fill },
            { "fill %.2f%s at %s, %.0f by %.0f cells".format(it.fill, if (it.alignedRectangle) " aligned" else " unaligned", it.where(frame), it.rectangleLongCells, it.rectangleShortCells) }
        )
        Detector.FACETS -> worstRing(
            true, { it.longestRunKm / it.facetAllowanceKm },
            { "longest run %.0f km against %.0f allowed at %s".format(it.longestRunKm, it.facetAllowanceKm, it.where(frame)) }
        )
        Detector.RIGHT_ANGLES -> {
            val pairs = measuredRings.filter { it.hasCornerPair }
            "%d right angles over %.0f km, %.2f per 1000 km (lower bound %.2f against %.2f)%s".format(
                rightAngles, outlineKm, if (outlineKm > 0) rightAngles * 1000 / outlineKm else 0.0,
                cornerLowerRate, cornerRateBar,
                if (pairs.isEmpty()) "" else "; corner pairs on ${pairs.size} rings, worst " +
                    pairs.maxBy { it.cornerPairs }.let { "${it.cornerPairs} at ${it.where(frame)}" }
            )
        }
        Detector.ARCS -> {
            val shown = arcs.arcs.sortedBy { it.rmsCells }.take(3).joinToString("; ") { it.describe(frame) }
            "%d arcs, %d concentric sets%s".format(arcs.arcs.size, arcs.concentricSets.size, if (shown.isEmpty()) "" else ": $shown")
        }
        Detector.COMBS -> {
            val shown = combs.sortedByDescending { it.teeth }.take(3).joinToString("; ") { it.describe(frame) }
            "%d combs%s".format(combs.size, if (shown.isEmpty()) "" else ": $shown")
        }
    }

    private fun worstRing(forRuns: Boolean, score: (ComponentShapes.Ring) -> Double, text: (ComponentShapes.Ring) -> String): String {
        val measured = if (forRuns) rings.filter { it.measuredForRuns } else measuredRings
        if (measured.isEmpty()) return "no ring large enough to measure (${rings.size} rings)"
        val worst = measured.maxBy(score)
        return "${measured.size} rings measured; worst: ${text(worst)}"
    }

    /** The clause: throws [GeometryViolation] past the bar, [InsufficientSample] when unmeasurable. */
    fun assertClean(detector: Detector, world: String) {
        when (outcome(detector)) {
            Outcome.CLEAN -> Unit
            Outcome.INSUFFICIENT -> throw InsufficientSample(
                "$world $layer ${detector.label}: insufficient — ${absent ?: describe(detector)}"
            )
            Outcome.VIOLATION -> throw GeometryViolation(
                "$world $layer ${detector.label}: ${describe(detector)}"
            )
        }
    }
}

/**
 * Runs every detector over a layer. Detector code is test code, so it has no accelerator path
 * (rule 8); what it costs a world is printed by the census.
 */
internal object GeometryGuard {

    /**
     * How many independent tests one layer of one world makes, for the census's multiplicity: four
     * grid bearings of isotropy and the right-angle rate. The per-component bars and the combs'
     * geometric bar are not tests at a level; they are held against the natural ensemble with the census's
     * size in view (see `GeometryControlTest`).
     */
    const val TESTS_PER_LAYER = 5

    fun read(layer: Layer, frame: GridFrame, familySize: Int, naturalCornersPer1000Km: Double): LayerReading {
        val runs = StraightRuns.of(layer.outlines, frame)
        val isotropy = BearingIsotropy.measure(runs, frame, familySize, tracedTwice = layer.tracedTwice)
        val rings = ComponentShapes.measure(layer.outlines, frame)
        val arcs = Arcs.measure(layer.outlines, frame)
        val combs = Combs.measure(runs, frame)
        val rightAngles = ComponentShapes.rightAnglesOf(layer.outlines, frame)
        val outlineKm = layer.outlines.sumOf { it.lengthKm() } / if (layer.tracedTwice) 2.0 else 1.0
        val counted = if (layer.tracedTwice) rightAngles / 2.0 else rightAngles.toDouble()
        // The rate's lower bound at the census's level: the count's own Poisson spread, on the
        // square-root scale where it is nearly normal.
        val z = Statistics.zFor(familySize)
        val lowerCount = (kotlin.math.sqrt(counted) - z / 2).coerceAtLeast(0.0).let { it * it }
        val lowerRate = if (outlineKm > 0) lowerCount * 1000 / outlineKm else 0.0
        return LayerReading(
            layer.name, frame, outlineKm, runs.size, isotropy, rings, arcs, combs, rightAngles,
            cornerRateBar = naturalCornersPer1000Km * CORNER_RATE_OVER_NATURAL,
            cornerLowerRate = lowerRate,
            absent = layer.absent ?: if (layer.outlines.isEmpty()) "nothing drawn" else null
        )
    }

    /**
     * How far past the natural controls' rate of right angles a layer's rate may run before the
     * excess matters: the same effect size the isotropy test asks of a bearing, since a right-angle
     * corner is two aligned runs meeting.
     */
    const val CORNER_RATE_OVER_NATURAL = BearingIsotropy.EFFECT_RATIO
}
