package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.FlowRouting
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The generated worlds' long profiles against Earth's, reported and not guarded: how concave the
 * rivers are, and how their steepness varies with the rock's erodibility, the rain and the uplift.
 * This is what the project means by the diversity of rivers, and the figures are printed for a
 * reader to judge (docs/DESIGN_LEDGER.md, Fix 3b, has them).
 *
 * **Concavity.** `S = k_s A^-theta`, fitted by least squares on the logarithms. The courses are the
 * river stage's traced rivers of at least [MIN_COURSE_CELLS] cells; the slope is the fall on the
 * eroded ground from a cell to the next one down its course (to the shoreline at the mouth) over
 * the step's length on the ground, and the area the unweighted drainage area in square kilometres.
 * Cells under standing water and cells that do not fall are left out. Read two ways: over each
 * whole course, and over **homogeneous reaches**, runs of [REACH_CELLS] consecutive read cells
 * along which the cover's erodibility factor moves by less than [REACH_ERODIBILITY_SPREAD] and the
 * uplift does not change, with at least [REACH_AREA_DECADES] of area across them. Earth's
 * homogeneous reaches run about 0.4 to 0.7 (Whipple, DiBiase and Crosby, *Bedrock rivers*, Treatise
 * on Geomorphology 9, 2013), and whole profiles, which cross rocks, climates and uplift rates,
 * vary more widely.
 *
 * **Steepness.** `k_sn = S A^0.5`, the reference concavity being the law's `m / n`, in metres with
 * the area in square metres, per channel cell of those courses, its median in each third of the
 * cover's factor, of the rain, and for ground under active uplift against ground under none. At
 * the law's steady state `k_sn = U / (K e sqrt(P))` with `P` the catchment's rain over the land's
 * mean: steeper where the uplift is faster, gentler on softer ground and gentler in the wet. These
 * worlds are four million years of rounds, not a steady state, so this reads how far along that
 * the rivers are rather than asserting where they should be.
 */
class RiverProfileReportTest {

    private companion object {
        val SEEDS = listOf(7L, 42L, 1234L, 99L, 718106L)
        const val MIN_COURSE_CELLS = 20
        const val REACH_CELLS = 10
        const val REACH_ERODIBILITY_SPREAD = 0.2f
        const val REACH_AREA_DECADES = 0.3
        const val MIN_FIT_POINTS = 8
        /** Standing water a cell must be under to be left out, in metres: `LakesConfig`'s own depth. */
        const val POND_METRES = 24f
        const val EARTH_REACH_LOW = 0.4
        const val EARTH_REACH_HIGH = 0.7
    }

    private class Reading(val area: Double, val slope: Double, val erodibility: Float, val uplift: Float, val rain: Float)

    @Test
    fun `report the rivers' concavity and steepness against Earth`() {
        var readSomething = 0
        val allWhole = ArrayList<Double>()
        val allReach = ArrayList<Double>()
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig(seed = seed, width = 512, height = 512))
            val courses = readCourses(world)
            val whole = courses.mapNotNull { fitConcavity(it) }
            val reaches = courses.flatMap { homogeneousReaches(it) }.mapNotNull { fitConcavity(it) }
            allWhole += whole
            allReach += reaches
            readSomething += whole.size
            val cells = courses.flatten()
            println(
                "PROFILES seed %d: %d courses; whole-profile concavity %s; homogeneous reaches (%d) %s, %.0f%% in Earth's %.1f-%.1f"
                    .format(
                        seed, courses.size, quartiles(whole), reaches.size, quartiles(reaches),
                        100.0 * reaches.count { it in EARTH_REACH_LOW..EARTH_REACH_HIGH } / reaches.size.coerceAtLeast(1),
                        EARTH_REACH_LOW, EARTH_REACH_HIGH
                    )
            )
            println("PROFILES seed %d steepness k_sn (m): %s".format(seed, steepnessByClass(cells)))
        }
        println(
            "PROFILES pooled: whole profiles %s over %d; homogeneous reaches %s over %d, %.0f%% in %.1f-%.1f"
                .format(
                    quartiles(allWhole), allWhole.size, quartiles(allReach), allReach.size,
                    100.0 * allReach.count { it in EARTH_REACH_LOW..EARTH_REACH_HIGH } / allReach.size.coerceAtLeast(1),
                    EARTH_REACH_LOW, EARTH_REACH_HIGH
                )
        )
        assertTrue(readSomething > 0, "no course was long enough to fit a profile to")
    }

    /** Every traced course's read cells, source to mouth. */
    private fun readCourses(world: WorldMap): List<List<Reading>> {
        val config = world.config
        val scale = config.scale
        val width = config.width
        val isLand = world.sea.isLand
        val landCells = world.sea.landCellCount
        val filled = world.rivers.filledElevation
        val relative = world.sea.relativeElevation.data
        val cellsUpstream = FlowRouting.accumulate(width, config.height, isLand, filled, world.rivers.flowTarget, landCells) { 1f }.data
        val cellAreaKm2 = config.cellWidthKm * config.cellHeightKm
        val cellWidthMetres = config.cellWidthKm * 1_000.0
        val pond = scale.reliefShareOfMetres(POND_METRES)
        var densitySum = 0.0
        for (cell in isLand.indices) if (isLand[cell]) densitySum += 1.0 - 0.5 * world.climate.vegetationDensity.data[cell]
        val meanShield = densitySum / landCells
        var rainSum = 0.0
        for (cell in isLand.indices) if (isLand[cell]) rainSum += world.climate.precipitationMm.data[cell]
        val meanRain = rainSum / landCells
        val uplift = world.plates.upliftRateMmPerYear.data
        fun metres(cell: Int) = scale.metresAboveShoreline(relative[cell]).toDouble()
        val courses = ArrayList<List<Reading>>()
        for (river in world.rivers.rivers) {
            if (river.cells.size < MIN_COURSE_CELLS) continue
            val read = ArrayList<Reading>()
            for (index in river.cells.indices) {
                val cell = river.cells[index]
                if (!isLand[cell]) continue
                if (filled.data[cell] - relative[cell] > pond) continue
                val next = if (index + 1 < river.cells.size) river.cells[index + 1] else world.rivers.flowTarget[cell]
                if (next < 0) continue
                val fall = metres(cell) - (if (isLand[next]) metres(next) else 0.0)
                if (fall <= 0.0) continue
                val slope = fall / (config.groundSteps.between(cell, next, width) * cellWidthMetres)
                read.add(
                    Reading(
                        area = cellsUpstream[cell] * cellAreaKm2,
                        slope = slope,
                        erodibility = ((1.0 - 0.5 * world.climate.vegetationDensity.data[cell]) / meanShield).toFloat(),
                        uplift = uplift[cell],
                        rain = (world.climate.precipitationMm.data[cell] / meanRain).toFloat()
                    )
                )
            }
            if (read.size >= MIN_FIT_POINTS) courses.add(read)
        }
        return courses
    }

    /** Runs of [REACH_CELLS] reads with one uplift and a narrow spread of erodibility. */
    private fun homogeneousReaches(course: List<Reading>): List<List<Reading>> {
        val reaches = ArrayList<List<Reading>>()
        var start = 0
        while (start + REACH_CELLS <= course.size) {
            val reach = course.subList(start, start + REACH_CELLS)
            val spread = reach.maxOf { it.erodibility } - reach.minOf { it.erodibility }
            val oneUplift = reach.all { it.uplift == reach.first().uplift }
            if (spread < REACH_ERODIBILITY_SPREAD && oneUplift) reaches.add(reach)
            start += REACH_CELLS
        }
        return reaches
    }

    /** `theta` fitted on the logarithms, or null where the area does not span enough to fit. */
    private fun fitConcavity(reads: List<Reading>): Double? {
        if (reads.size < MIN_FIT_POINTS) return null
        val x = reads.map { ln(it.area) }
        val y = reads.map { ln(it.slope) }
        if ((x.max() - x.min()) / ln(10.0) < REACH_AREA_DECADES) return null
        val meanX = x.average()
        val meanY = y.average()
        var covariance = 0.0
        var variance = 0.0
        for (index in x.indices) {
            covariance += (x[index] - meanX) * (y[index] - meanY)
            variance += (x[index] - meanX) * (x[index] - meanX)
        }
        return -covariance / variance
    }

    private fun steepnessByClass(cells: List<Reading>): String {
        fun ksn(reading: Reading) = reading.slope * sqrt(reading.area * 1e6)
        fun medianOf(reads: List<Reading>): String =
            if (reads.isEmpty()) "-" else reads.map { ksn(it) }.sorted()[reads.size / 2].let { "%.0f (%d)".format(it, reads.size) }
        fun thirds(key: (Reading) -> Float, name: String): String {
            val sorted = cells.sortedBy(key)
            val third = sorted.size / 3
            return "$name low ${medianOf(sorted.subList(0, third))}, mid ${medianOf(sorted.subList(third, 2 * third))}, " +
                "high ${medianOf(sorted.subList(2 * third, sorted.size))}"
        }
        val lifted = cells.filter { it.uplift > 0f }
        val still = cells.filter { it.uplift <= 0f }
        return "${thirds({ it.erodibility }, "erodibility")}; ${thirds({ it.rain }, "rain")}; " +
            "uplift none ${medianOf(still)}, active ${medianOf(lifted)}"
    }

    private fun quartiles(values: List<Double>): String {
        if (values.isEmpty()) return "none"
        val sorted = values.sorted()
        return "median %.2f, quartiles %.2f-%.2f".format(sorted[sorted.size / 2], sorted[sorted.size / 4], sorted[sorted.size * 3 / 4])
    }
}
