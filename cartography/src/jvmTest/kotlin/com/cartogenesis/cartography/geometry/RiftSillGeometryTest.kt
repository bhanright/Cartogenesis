package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.PlateResult
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.PlateType
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The sills between a rift's half-grabens are not straight bars across the trough.
 *
 * Since L1 a rift breaks every 60 to 160 km, and a join drawn square to the rift's axis is a rung
 * across the trough: on a rift running north-south it is a grid row, on one running east-west a
 * column, which is rule 13's straight grid-bearing edge. Earth's accommodation zones are relay
 * ramps where overlapping border faults hand the extension on, oblique and curved (Rosendahl 1987;
 * Morley and others 1990 on transfer zones).
 *
 * Read on the plate floor, where the sills are made: its level lines every [LEVEL_STEP_METERS]
 * over the trough on its continental flanks within [JOIN_REACH_KM] of a join, through every
 * detector of the geometry census, on seed 42's north-south rift and its two east-west ones
 * separately. The bars are the census's natural controls at a place family sized to this layer
 * ([SILL_PLACES]) rather than to a whole world's, since the lines here are the sills and nothing
 * else. No detector may find a violation but the crease detector, recorded below; the three that
 * read a line's own shape, [MEASURED], must have lines enough to read, and the layer-wide ones
 * have too few components on a few rifts' joins to be measured and say so.
 *
 * Shown failing on the joins drawn square to the axis, before the relay ramps: on the north-south
 * rift the straight runs along the east-west bearing read 1.53 times the others' (the rungs), and
 * the crease detector 9.98 against its natural bar of 4.37, a turn of 177 degrees at cell
 * (259, 279), where a level line ran up one side of a rung and back down the other. The
 * straight-facet and aligned-side detectors did not catch the rungs, each rung being a few cells
 * long. The east-west rifts read clean before and after: they have too few joins in the trough
 * for the bearing count, and their rungs, down columns, made no crease past the bar.
 */
class RiftSillGeometryTest {

    @Test
    fun `a rift's sills cross its trough as ragged oblique ramps, not straight rungs`() {
        val config = WorldGenConfig.forRows(SEED, ROWS)
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val report = PlateStage.presentRiftSegments(config)
        val frame = GridFrame(config.width, config.height, config.cellWidthKm, config.cellHeightKm)
        val failures = ArrayList<String>()
        var creases: Verdict? = null
        listOf("north-south" to NORTH_SOUTH_PAIRS, "east-west" to EAST_WEST_PAIRS).forEach { (name, pairs) ->
            val nearJoin = sillCells(config, report, plates, pairs)
            val heights = plates.height.data
            val inside = nearJoin.indices.filter { nearJoin[it] }
            val low = inside.minOf { heights[it] }
            val high = inside.maxOf { heights[it] }
            val step = (LEVEL_STEP_METERS / config.scale.reliefSpanMetres).toFloat()
            val levels = generateSequence(low + step / 2) { it + step }.takeWhile { it < high }.toList().toFloatArray()
            val layer = Layer("$name rift sills", MapLayers.levelLines(heights, levels, frame, nearJoin))
            val reading = GeometryGuard.read(layer, frame, Judge(GeometryGuard.TESTS_PER_LAYER, Judge.PLACE_TESTS * SILL_PLACES))
            Detector.entries.forEach { detector ->
                val verdict = reading.verdict(detector)
                println("RIFT SILLS seed $SEED at $ROWS rows, $name rifts, ${inside.size} cells, ${levels.size} levels: ${detector.label} ${verdict.outcome} ${verdict.text}")
                if (detector == Detector.CREASES && name == "north-south") {
                    creases = verdict
                } else if (verdict.outcome == Outcome.VIOLATION) {
                    failures += "$name ${detector.label}: ${verdict.text}"
                }
                if (verdict.outcome == Outcome.INSUFFICIENT && detector in MEASURED) {
                    failures += "$name ${detector.label} could not be measured: ${verdict.text}"
                }
            }
        }
        assertTrue(failures.isEmpty(), "a rift's sills run straight: $failures")

        // The saddles' level lines still turn back more sharply than natural ground's, as sharply
        // as the rungs' did: recorded with its magnitude, so a worsening fails as a different
        // violation. What the rungs failed and the ramps pass is the bearing count above.
        val crease = creases!!
        KnownFailures.expect(SADDLES_FOLD_THE_LEVEL_LINES, CREASES_WITHIN_THEIR_RECORD) {
            if (crease.outcome == Outcome.VIOLATION) {
                val worst = crease.worst?.figure ?: 0.0
                throw RecordedViolation(
                    "north-south creases: ${crease.text}",
                    if (worst <= CREASE_RECORD * (1 + CREASE_RECORD_TOLERANCE)) CREASES_WITHIN_THEIR_RECORD
                    else "worst crease %.2f past its record %.2f".format(worst, CREASE_RECORD)
                )
            }
        }
    }

    /**
     * The trough within [JOIN_REACH_KM] of a join of the rifts in [pairs]: every cell of the rift's
     * corridor on a continental plate no further than `riftWidthKm` on the ground from one of the
     * rift's own cells, taking the nearest such cell's distance to its join. The oceanic side of a
     * pair that has one is a spreading ridge's flank and has no half-grabens or sills; its level
     * lines run the length of the rift and are the ridge's to answer for, not this test's.
     */
    private fun sillCells(
        config: WorldGenConfig,
        report: PlateStage.RiftSegmentReport,
        plates: PlateResult,
        pairs: Set<Int>
    ): BooleanArray {
        val nearestClass = plates.nearestBoundaryClass
        val cellsAcross = config.width
        val cellsDown = config.height
        val toJoinKm = FloatArray(cellsAcross * cellsDown) { Float.NaN }
        val nearestKm = DoubleArray(cellsAcross * cellsDown) { Double.MAX_VALUE }
        val reachKm = config.tectonics.riftWidthKm
        val reachColumns = ceil(reachKm / config.cellWidthKm).toInt()
        val reachRows = ceil(reachKm / config.cellHeightKm).toInt()
        val rift = BoundaryClass.CONTINENTAL_RIFT.ordinal
        report.cell.indices.forEach { entry ->
            if (report.lowId[entry] * PAIR_STRIDE + report.highId[entry] !in pairs) return@forEach
            val column = report.cell[entry] % cellsAcross
            val row = report.cell[entry] / cellsAcross
            for (rowStep in -reachRows..reachRows) {
                val atRow = row + rowStep
                if (atRow < 0 || atRow >= cellsDown) continue
                for (columnStep in -reachColumns..reachColumns) {
                    val km = sqrt(
                        (columnStep * config.cellWidthKm).let { it * it } + (rowStep * config.cellHeightKm).let { it * it }
                    )
                    if (km > reachKm) continue
                    val cell = atRow * cellsAcross + (column + columnStep).mod(cellsAcross)
                    if (nearestClass[cell] != rift || km >= nearestKm[cell]) continue
                    if (plates.plates[plates.plateId[cell]].type != PlateType.CONTINENTAL) continue
                    nearestKm[cell] = km
                    toJoinKm[cell] = report.toJoinKm[entry]
                }
            }
        }
        return BooleanArray(toJoinKm.size) { !toJoinKm[it].isNaN() && abs(toJoinKm[it]) <= JOIN_REACH_KM }
    }

    private companion object {
        const val SEED = 42L
        const val ROWS = 512
        const val PAIR_STRIDE = 1000

        /** Seed 42's rift between plates 2 and 7 runs north-south for 4,300 km at about 3,100 km east. */
        val NORTH_SOUTH_PAIRS = setOf(2 * PAIR_STRIDE + 7)

        /** Its rifts between plates 10 and 11 and between 1 and 4 run east-west. */
        val EAST_WEST_PAIRS = setOf(10 * PAIR_STRIDE + 11, 1 * PAIR_STRIDE + 4)

        /**
         * How far along the rift from a join the sill's ground is read: twice the accommodation
         * zone's 25 km half-length, so the whole sill at the axis and the start of each basin either
         * side of it. A relay moves the join by up to half the half-graben's length less 12.5 km
         * toward the walls, so there the read takes in most of a saddle and not all of it.
         */
        const val JOIN_REACH_KM = 50f

        /** The floor's level lines' spacing: a hundred meters, a tenth of a sill's height or less. */
        const val LEVEL_STEP_METERS = 100.0

        /**
         * The saddles between half-grabens still fold the trough's level lines back past the
         * natural bar: 9.38 against 4.37 on the north-south rift, where the rungs read 9.98. The
         * trough is 330 km across and its half-grabens 60 to 160 km long, where Earth's rifts are
         * 40 to 70 km across, so every saddle runs across the trough for two to five times the
         * length of the half-grabens either side of it, and its two flanks run on as long level
         * lines that meet at its ends (docs/TODO.md).
         */
        const val SADDLES_FOLD_THE_LEVEL_LINES = "L1: a rift's saddles fold its level lines back past the natural bar"
        const val CREASE_RECORD = 9.38

        /** How far past its record the worst crease may read before it is a worsening: a tenth, policy. */
        const val CREASE_RECORD_TOLERANCE = 0.1
        const val CREASES_WITHIN_THEIR_RECORD = "worst crease within a tenth of its record"

        /** The detectors that read each line's own shape, which every rift here has lines enough for. */
        val MEASURED = setOf(Detector.ALIGNED_SIDE, Detector.FACETS, Detector.CREASES)

        /**
         * The place family's size, in windows: this layer's own, a few hundred on these rifts, held
         * at two thousand so a busier rift does not lower the bar under it.
         */
        const val SILL_PLACES = 2_000
    }
}
