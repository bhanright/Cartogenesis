package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether a hotspot's trail is a line a ruler drew (docs/CONVENTIONS.md, rule 13).
 *
 * A plume fixed in the mantle under a plate that moves on one drift leaves its volcanoes on a
 * straight line, and every trail on the plate parallel to the next: on the Earth-sized planet, a
 * dozen dashed lines thousands of kilometers long ruled across the ocean. Earth's trails bend where
 * their plate turned and wander where their plume drifted (`PlateStage.hotspotTrails`).
 *
 * Some of Earth's are straight all the same: the Ninetyeast Ridge runs some 5,000 km down one
 * meridian. A straight trail is no defect; a planet whose trails are straight as a rule is, so the
 * guard holds the share of long trails that are ruled to [MOST_TRAILS_BEND]'s complement, pooled
 * over the seeds, against the fixed plumes under unturning plates, every one of whose long trails
 * the measure must find ruled.
 *
 * A trail counts as ruled when a strip no wider than its own largest volcano's base holds every
 * volcano along a run longer than one plume course: within [TectonicsConfig.hotspotRadiusKm] of one
 * line, along more than 2,450 km. The base is the bar because a chain of cones whose centers all lie
 * within one cone's radius of a line draws that line; the course is the length because a plume holds
 * its course that long (Tarduno et al. 2003), so a shorter run may be straight on Earth too. The
 * narrowest strip is found exactly: it is bounded by a line through two of the volcanoes, so every
 * pair is tried as RuledLines does for water.
 *
 * Only the volcanoes standing on their own plate count, since the stamp clips the rest away, and of
 * those the longest unbroken run. Measured on the trails themselves, in kilometers on the ground,
 * so the grid sets only which plate a cell belongs to.
 */
class HotspotTrailTest {

    @Test
    fun `hotspot trails are not ruled lines`() {
        val ruled = ArrayList<String>()
        var longRuns = 0
        var controlLongRuns = 0
        var controlRuled = 0
        for (seed in SEEDS) {
            val config = WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS)
            val plates = PlateStage.generate(config, TerrainStage.generate(config))
            val drifting = runsOf(config, PlateStage.hotspotTrails(config, plates.plates, plates.plateId), plates.plateId)
            val fixed = runsOf(
                config,
                PlateStage.hotspotTrails(config, plates.plates, plates.plateId, plumeSpeedShare = 0f, plateTurns = false),
                plates.plateId
            )
            for (run in drifting) {
                println(
                    "TRAILS seed %d plate %d: run %.0f km, %d volcanoes, strip half-width %.0f km, bearing %.0f"
                        .format(seed, run.plate, run.lengthKm, run.volcanoes, run.stripHalfWidthKm, run.bearingDegrees)
                )
            }
            val long = drifting.filter { it.lengthKm > PLUME_COURSE_KM }
            longRuns += long.size
            long.filter { it.stripHalfWidthKm < config.tectonics.hotspotRadiusKm }.forEach {
                ruled.add("seed $seed plate ${it.plate}: %.0f km within %.0f km of one line".format(it.lengthKm, it.stripHalfWidthKm))
            }
            val fixedLong = fixed.filter { it.lengthKm > PLUME_COURSE_KM }
            controlLongRuns += fixedLong.size
            controlRuled += fixedLong.count { it.stripHalfWidthKm < config.tectonics.hotspotRadiusKm }
            println(
                "TRAILS seed %d: %d runs over a course long, %d with fixed plumes, of which ruled %d"
                    .format(seed, long.size, fixedLong.size, fixedLong.count { it.stripHalfWidthKm < config.tectonics.hotspotRadiusKm })
            )
        }
        println("TRAILS pooled: drifting plumes %d long runs, %d ruled; fixed plumes %d long runs, %d ruled".format(
            longRuns, ruled.size, controlLongRuns, controlRuled))

        // The control: with every plume fixed and no plate turning, every long trail is ruled, so
        // the measure sees it.
        assertTrue(
            controlLongRuns > 0 && controlRuled == controlLongRuns,
            "with fixed plumes only $controlRuled of $controlLongRuns long trails measured as ruled; the measure is blind"
        )
        assertTrue(longRuns > 0, "no trail ran longer than a plume course on seeds $SEEDS; nothing was measured")
        val ruledShare = ruled.size.toDouble() / longRuns
        assertTrue(
            ruledShare <= 1.0 - MOST_TRAILS_BEND,
            "%.2f of the long hotspot trails are ruled across the ocean, where most should bend: %s"
                .format(ruledShare, ruled.joinToString("; "))
        )
    }

    /** One unbroken run of a trail's volcanoes on its own plate, measured on the ground. */
    private class TrailRun(
        val plate: Int,
        val volcanoes: Int,
        val lengthKm: Double,
        val stripHalfWidthKm: Double,
        val bearingDegrees: Double
    )

    /** Each trail's longest run of volcanoes standing on the trail's own plate. */
    private fun runsOf(config: WorldGenConfig, trails: List<PlateStage.HotspotTrail>, plateId: IntArray): List<TrailRun> {
        val across = config.width
        val down = config.height
        return trails.mapNotNull { trail ->
            var best: List<PlateStage.HotspotVent> = emptyList()
            var current = ArrayList<PlateStage.HotspotVent>()
            for (vent in trail.vents) {
                val row = vent.row.toInt()
                val onPlate = row in 0 until down &&
                    plateId[row * across + Math.floorMod(vent.column.toInt(), across)] == trail.plate
                if (onPlate) current.add(vent) else current = ArrayList()
                if (current.size > best.size) best = ArrayList(current)
            }
            if (best.size < 2) return@mapNotNull null
            val xs = DoubleArray(best.size) { best[it].column * config.cellWidthKm }
            val ys = DoubleArray(best.size) { best[it].row * config.cellHeightKm }
            var lengthKm = 0.0
            for (k in 1 until best.size) lengthKm += hypot(xs[k] - xs[k - 1], ys[k] - ys[k - 1])
            TrailRun(
                plate = trail.plate,
                volcanoes = best.size,
                lengthKm = lengthKm,
                stripHalfWidthKm = narrowestStripHalfWidth(xs, ys),
                bearingDegrees = Math.toDegrees(atan2(ys.last() - ys.first(), xs.last() - xs.first()))
            )
        }
    }

    /** Half the width of the narrowest strip of the plane holding every point, tried on every pair's line. */
    private fun narrowestStripHalfWidth(xs: DoubleArray, ys: DoubleArray): Double {
        var narrowest = Double.MAX_VALUE
        for (first in xs.indices) {
            for (second in first + 1 until xs.size) {
                val dx = xs[second] - xs[first]
                val dy = ys[second] - ys[first]
                val length = hypot(dx, dy)
                if (length == 0.0) continue
                var lowest = Double.MAX_VALUE
                var highest = -Double.MAX_VALUE
                for (point in xs.indices) {
                    val offset = ((xs[point] - xs[first]) * dy - (ys[point] - ys[first]) * dx) / length
                    if (offset < lowest) lowest = offset
                    if (offset > highest) highest = offset
                }
                narrowest = minOf(narrowest, highest - lowest)
            }
        }
        return if (narrowest == Double.MAX_VALUE) 0.0 else narrowest / 2.0
    }

    private companion object {
        /** The everyday tier's standard seeds and two more, for trails enough to judge. */
        val SEEDS = listOf(42L, 969495L, 7L, 1234L, 99L)

        /**
         * The share of long trails that must bend: more than half, since a population of trails
         * reads as ruled when most of its members are, and Earth's longest bend (Hawaiian-Emperor,
         * Louisville, Tristan's Walvis Ridge) where the Ninetyeast Ridge does not.
         */
        const val MOST_TRAILS_BEND = 0.5

        /** How far a plate travels while its plume holds one course, in kilometers. */
        const val PLUME_COURSE_KM = PlateStage.PLUME_COURSE_KM
    }
}
