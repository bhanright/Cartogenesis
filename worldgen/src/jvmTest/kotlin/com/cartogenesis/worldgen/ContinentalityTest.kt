package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether a continental interior actually swings further through the year than a coast does, at
 * the same latitude.
 *
 * There is no continentality setting any more, and that is the point of this guard. The energy
 * balance solves each latitude twice — once for a surface with three metres of soil and an air
 * column to store its summer in, once for one with fifty metres of sea water — and a cell takes a
 * blend of the two decided by how much of the air over it came off the water
 * (`ClimateStage.marineAirFraction`, falling away with an e-folding of 350 km read off Earth's own
 * stations). So the gap this test measures is two published heat capacities and the world's own
 * coastline, with nothing in between for anyone to turn.
 *
 * The control is that blend removed. Every land cell then takes its band's continental column
 * whole, whatever it is standing next to, and a shoreline and an interior at the same latitude
 * swing by the same amount — which is Ireland with Siberia's winter. It is the stage's own
 * seasonal step, [ClimateStage.seasonalTemperature], run on the same world's annual field with the
 * marine-air fraction handed to it at zero on land, and the same step with the world's own fraction
 * is checked to give back the world's own months to the last bit, so nothing but the blend
 * differs.
 *
 * See docs/DESIGN_LEDGER.md, W1; A2 wrote the version of this guard that had a knob in it.
 */
class ContinentalityTest : BorrowsSharedWorlds() {

    private companion object {
        /** Matches the plan's own worked example: an interior against a coast at 50 degrees. */
        const val SAMPLE_LATITUDE = 50f
        const val SAMPLE_SPAN = 6f

        /**
         * How much further the interior must swing than the coast, in degrees Celsius.
         *
         * A2's own figure, and Earth's: at 50-56 degrees north Valentia on the Irish coast swings
         * 8 C over the year and Moscow, 650 km from the nearest sea, swings 28 — a gap of twenty.
         * Six is what the plan asked for and is kept, because the map's "coast" band is every cell
         * within one coastal reach rather than a shoreline station and its "interior" begins at
         * three reaches rather than at Moscow.
         */
        const val TARGET_GAP = 6.0

        /** The marine air's e-folding length, `ClimateStage.MARINE_REACH_KM`, which is private. */
        const val MARINE_REACH_KM = 350.0

        /** How far inland the straight coasts are read, in e-folding lengths. */
        const val MARINE_REACH_E_FOLDINGS = 4.0

        /**
         * How far a cell's marine fraction may stand from the curve: a float's rounding of a
         * fraction and of the distance it is read off, and no more.
         */
        const val MARINE_ROUNDING = 1e-4
    }

    @Test
    fun `interior land swings further through the year than a coast at the same reach`() {
        val measured = measure()
        println(
            ("CONTINENTALITY seed 42 at %d deg: coast (within reach) swing %.1f C over %d cells, " +
                "interior (beyond 3x reach) swing %.1f C over %d cells")
                .format(
                    SAMPLE_LATITUDE.toInt(), measured.coast.gap, measured.coast.cells,
                    measured.interior.gap, measured.interior.cells
                )
        )
        assertTrue(
            measured.coast.cells > 0 && measured.interior.cells > 0,
            "not enough coastal or interior land to compare"
        )
        assertTrue(
            measured.interior.gap - measured.coast.gap >= TARGET_GAP,
            "interior swings only %.1f C more than the coast (interior %.1f C, coast %.1f C)"
                .format(
                    measured.interior.gap - measured.coast.gap,
                    measured.interior.gap, measured.coast.gap
                )
        )
    }

    @Test
    fun `without the marine blend the coast and the interior swing alike`() {
        // The guard above, shown to discriminate. Take the blend away and every land cell reads
        // its band's continental column, so a coast and an interior at the same latitude are
        // separated by nothing but which latitudes each set happens to hold.
        val measured = measure()
        println(
            ("CONTINENTALITY seed 42 at %d deg with the marine blend off: coast swing %.1f C, " +
                "interior swing %.1f C")
                .format(
                    SAMPLE_LATITUDE.toInt(), measured.coastWithoutBlend, measured.interiorWithoutBlend
                )
        )
        assertTrue(
            measured.interiorWithoutBlend - measured.coastWithoutBlend < TARGET_GAP,
            ("interior swings %.1f C more than the coast even with the blend off — the guard " +
                "cannot discriminate the feature from its absence")
                .format(measured.interiorWithoutBlend - measured.coastWithoutBlend)
        )
    }

    /**
     * Sea air reaches as far inland from a coast facing north as from one facing east, on the
     * ground: the marine fraction of every land cell is `exp(-d / 350 km)` with `d` its true
     * distance from the shoreline, whichever way the shoreline faces.
     *
     * Two synthetic worlds, sea north of a straight coast along a row and sea west of one along a
     * column, and every land cell out to four e-folding lengths inland of each held to that curve.
     * The shoreline is the edge between the last water cell and the first land one, half a row or
     * half a column from the first land cell's centre, so the distance is measured from there.
     */
    @Test
    fun `sea air reaches as far inland from a coast facing north as from one facing east`() {
        val config = WorldGenConfig(seed = 42L, width = 512, height = 512)
        val cellsAcross = config.width
        val cellsDown = config.height
        val edge = cellsDown / 4
        fun sea(isLand: (Int, Int) -> Boolean): SeaLevelResult {
            val land = BooleanArray(cellsAcross * cellsDown) { isLand(it % cellsAcross, it / cellsAcross) }
            return SeaLevelResult(0f, land, FloatField(cellsAcross, cellsDown), land.count { it })
        }
        val northFacing = ClimateStage.marineAirFraction(config, sea { _, row -> row >= edge })
        val westFacing = ClimateStage.marineAirFraction(config, sea { column, _ -> column >= edge && column < cellsAcross - edge })
        var worst = 0.0
        var worstAt = ""
        var measured = 0
        for (inland in 0 until cellsDown) {
            val kmNorthFacing = (inland + 0.5) * config.cellHeightKm
            if (kmNorthFacing <= MARINE_REACH_E_FOLDINGS * MARINE_REACH_KM) {
                val read = northFacing.data[(edge + inland) * cellsAcross + cellsAcross / 2].toDouble()
                val error = abs(read - exp(-kmNorthFacing / MARINE_REACH_KM))
                measured++
                if (error > worst) { worst = error; worstAt = "${"%.0f".format(kmNorthFacing)} km south of a coast facing north" }
            }
            val kmWestFacing = (inland + 0.5) * config.cellWidthKm
            if (kmWestFacing <= MARINE_REACH_E_FOLDINGS * MARINE_REACH_KM) {
                val read = westFacing.data[cellsDown / 2 * cellsAcross + edge + inland].toDouble()
                val error = abs(read - exp(-kmWestFacing / MARINE_REACH_KM))
                measured++
                if (error > worst) { worst = error; worstAt = "${"%.0f".format(kmWestFacing)} km east of a coast facing west" }
            }
        }
        println("CONTINENTALITY marine air on two straight coasts: $measured cells, the worst off exp(-d / 350 km) by %.4f, $worstAt".format(worst))
        assertTrue(
            worst <= MARINE_ROUNDING,
            "the marine fraction stands ${"%.3f".format(worst)} off exp(-d / 350 km) at $worstAt"
        )
    }

    private data class Group(val gap: Double, val cells: Int)

    private data class Measured(
        val coast: Group,
        val interior: Group,
        val coastWithoutBlend: Double,
        val interiorWithoutBlend: Double
    )

    private fun measure(): Measured {
        val world = SharedWorlds.world(
            WorldGenConfig(seed = 42L, width = 512, height = 512)
        )
        val cellsAcross = world.width
        val cellsDown = world.height
        val reach = world.config.ocean.coastalReachCells
        // The same two fields production read, not re-derivations of them.
        val distance = ClimateStage.waterDistance(world.config, world.sea)
        val marine = ClimateStage.marineAirFraction(world.config, world.sea)
        val zonal = ClimateStage.zonalClimate(world.config, world.sea)

        // The stage's own seasonal step, twice: with the world's marine fraction it must give the
        // world's months back exactly, which is what makes the second run the production code
        // with one input changed; with the fraction at zero on land it is the blend removed.
        fun swing(fraction: FloatField): FloatArray {
            val summer = ClimateStage.seasonalTemperature(world.config, world.climate.temperature, zonal, fraction, Season.SUMMER)
            val winter = ClimateStage.seasonalTemperature(world.config, world.climate.temperature, zonal, fraction, Season.WINTER)
            return FloatArray(summer.data.size) { summer.data[it] - winter.data[it] }
        }
        val summerAgain = ClimateStage.seasonalTemperature(world.config, world.climate.temperature, zonal, marine, Season.SUMMER)
        assertTrue(
            summerAgain.data.contentEquals(world.climate.summerTemperature.data),
            "the seasonal step run again on the world's own fields is not the world's warmest month"
        )
        val noBlend = FloatField(cellsAcross, cellsDown).also { field ->
            for (cell in field.data.indices) field.data[cell] = if (world.sea.isLand[cell]) 0f else marine.data[cell]
        }
        val swingWithoutBlend = swing(noBlend)

        var coastSum = 0.0
        var coastCells = 0
        var interiorSum = 0.0
        var interiorCells = 0
        var coastWithoutBlend = 0.0
        var interiorWithoutBlend = 0.0

        for (row in 0 until cellsDown) {
            val latitude = ClimateStage.latitudeOf(row, cellsDown)
            if (abs(abs(latitude) - SAMPLE_LATITUDE) > SAMPLE_SPAN) continue
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!world.sea.isLand[cell]) continue
                val swing = abs(
                    (world.climate.summerTemperature.data[cell] -
                        world.climate.winterTemperature.data[cell]).toDouble()
                )
                val landOnlySwing = abs(swingWithoutBlend[cell].toDouble())
                when {
                    distance.data[cell] <= reach -> {
                        coastSum += swing
                        coastCells++
                        coastWithoutBlend += landOnlySwing
                    }
                    distance.data[cell] > reach * 3 -> {
                        interiorSum += swing
                        interiorCells++
                        interiorWithoutBlend += landOnlySwing
                    }
                }
            }
        }

        println(
            "CONTINENTALITY marine air fraction: shoreline %.2f, one reach in %.2f, three in %.2f"
                .format(
                    marineFractionAtDistance(marine, distance, 0f, 1f),
                    marineFractionAtDistance(marine, distance, reach.toFloat(), 1f),
                    marineFractionAtDistance(marine, distance, reach * 3f, 2f)
                )
        )
        return Measured(
            coast = Group(if (coastCells == 0) 0.0 else coastSum / coastCells, coastCells),
            interior = Group(
                if (interiorCells == 0) 0.0 else interiorSum / interiorCells, interiorCells
            ),
            coastWithoutBlend =
                if (coastCells == 0) 0.0 else coastWithoutBlend / coastCells,
            interiorWithoutBlend =
                if (interiorCells == 0) 0.0 else interiorWithoutBlend / interiorCells
        )
    }

    /** The mean marine-air fraction of the cells sitting [cells] from water, for the report. */
    private fun marineFractionAtDistance(
        marine: FloatField,
        distance: FloatField,
        cells: Float,
        tolerance: Float
    ): Double {
        var total = 0.0
        var counted = 0
        for (cell in marine.data.indices) {
            if (abs(distance.data[cell] - cells) > tolerance) continue
            total += marine.data[cell]
            counted++
        }
        return if (counted == 0) 0.0 else total / counted
    }
}
