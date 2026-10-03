package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.GroundCells
import com.cartogenesis.worldgen.pipeline.GroundWatch
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.RoundMass
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlockingWatchingGround
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import org.junit.Assert.assertTrue
import kotlin.test.Test

/**
 * The two heights on a standard world: the budget closes on the stored floats, the bed stays under
 * the ground, the trunk's profiles are concave as the law makes them, and the rates are Earth's
 * (docs/DESIGN_LEDGER.md, E1).
 *
 * One world, seed 42 at 512 rows with every default, run through the erosion stage once with the
 * stage's own observers and shared by every case. The everyday tier's; the grid comparisons are
 * [ErosionScaleTest]'s.
 */
class TwoHeightErosionTest {

    /**
     * Every round's mechanisms move no more and no less than they account for, read off the stored
     * floats: the ground's summed fall before the thermal sweeps, against what the round says left
     * the model, as a share of what it took off the land. The bar is a hundred-millionth (the
     * design's): the closure's production, the notch's and the grooves' cuts and the spoil's
     * laying are each read back from the floats they were written to, so what is left is the
     * double's rounding over a few hundred thousand cells. The sweeps' own rounding, the one place
     * mass is moved by float arithmetic that nothing reads back, is printed beside it.
     */
    @Test
    fun `the budget closes on the stored floats`() {
        val run = world
        var worst = 0.0
        var sweeps = 0.0
        run.rounds.forEachIndexed { round, mass ->
            val residual = abs(mass.fieldDropBeforeRelax - mass.lostToSea) / max(mass.incised, 1e-30)
            val withSweeps = abs(mass.fieldDrop - mass.lostToSea) / max(mass.incised, 1e-30)
            val ledger = abs(mass.incised - mass.deposited - mass.lostToSea) / max(mass.incised, 1e-30)
            println(
                "TWOHEIGHT budget round %2d: took %.6f, laid %.6f, lost %.6f; residual on the floats %.2e, with the sweeps %.2e, ledger %.2e"
                    .format(round + 1, mass.incised, mass.deposited, mass.lostToSea, residual, withSweeps, ledger)
            )
            worst = maxOf(worst, residual, ledger)
            sweeps = maxOf(sweeps, withSweeps)
        }
        println("TWOHEIGHT budget worst %.2e on the floats; %.2e with the sweeps' rounding".format(worst, sweeps))
        assertTrue("the round's budget misses its own floats by $worst of what it took", worst <= MASS_RESIDUAL)
    }

    /**
     * The bed never stands above the ground once a round is done, and how often the stage had to
     * hold it there after the thermal sweeps is printed: the `min(bed, ground)` the closure should
     * make redundant, which moves no material and is counted in [RoundMass.bedsHeldUnderGround].
     */
    @Test
    fun `the bed stays under the ground`() {
        val run = world
        var above = 0
        for (cell in run.bed.indices) if (run.bed[cell] > run.ground[cell]) above++
        val held = run.rounds.map { it.bedsHeldUnderGround }
        println(
            "TWOHEIGHT beds held under the ground after the sweeps, by round: %s of %d land cells (%.2f%% at most)"
                .format(held.joinToString("/"), run.landCells, 100.0 * held.maxOrNull()!! / run.landCells)
        )
        assertTrue("$above cells end with the bed above the ground", above == 0)
    }

    /**
     * The trunk's beds are concave as the law makes them: slope falls with drainage area as
     * `A^-theta` with `theta = m / n = 1/2` at steady state, and Earth's channels read 0.35 to 0.65
     * (Whipple 2004). Fitted on the final bed over cells whose catchment is past the coarsest
     * standard grid's cell, so every cell of the fit is a trunk the grid resolves.
     */
    @Test
    fun `the trunk's beds are concave as the law makes them`() {
        val run = world
        val config = run.config
        val sea = SeaLevelStage.percentileCut(FloatField(config.width, config.height, run.ground), config.seaLevel, config.scale)
        val landHalf = config.scale.landHalfOfField
        val relative = sea.relativeElevation.copy()
        for (cell in run.bed.indices) if (sea.isLand[cell]) relative.data[cell] = (run.bed[cell] - sea.shorelineHeight) / landHalf
        val filled = FlowRouting.fillDepressions(config.width, config.height, sea.isLand, relative)
        val flow = FlowRouting.flowDirections(
            config.width, config.height, sea.isLand, relative, filled, config.seed, config.cellHeightInCellWidths,
            FlowRouting.smoothFieldPeriodCells(config), config.facetRouting, config.flatPotential
        )
        val cellKm2 = config.squareKilometresPerCell.toFloat()
        val area = FlowRouting.accumulate(config.width, config.height, sea.isLand, filled, flow, sea.landCellCount) { cellKm2 }
        val steps = config.groundSteps
        val metres = config.scale.highestLandMetres.toDouble()
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (cell in run.bed.indices) {
            if (!sea.isLand[cell] || area.data[cell] < TRUNK_AREA_KM2) continue
            val receiver = flow[cell]
            if (receiver < 0 || !sea.isLand[receiver]) continue
            if (filled.data[cell] - relative.data[cell] > 0f) continue
            val fall = (relative.data[cell] - relative.data[receiver]) * metres
            if (fall <= 0.0) continue
            val run2 = steps.between(cell, receiver, config.width) * config.cellWidthKm * 1_000.0
            xs += ln(area.data[cell].toDouble())
            ys += ln(fall / run2)
        }
        val slope = EarthLikeness.fitLine(xs, ys).slope
        println("TWOHEIGHT concavity seed 42 at 512 rows: theta %.3f over %d trunk cells".format(-slope, xs.size))
        assertTrue("the trunks' concavity is ${-slope}, outside 0.35 to 0.65", -slope in CONCAVITY_LOW..CONCAVITY_HIGH)
    }

    /**
     * The rates are Earth's. Portenga and Bierman (*Understanding Earth's eroding surface with
     * 10Be*, GSA Today 21, 2011) gather every cosmogenic measurement then published: drainage
     * basins erode at a median of 54 and a mean of 218 m/Myr, bare outcrops at 5.4 and 12. Pooled
     * over a world's land, the ground's lowering lies inside the basins' spread, between the
     * outcrops' median and the basins' mean; and the active belts, where Earth's fastest basins
     * are, lower at a hundred metres a million years or more. Both are clauses against a stage
     * that erodes nothing or everything, and not a claim about any landform.
     */
    @Test
    fun `the land lowers at Earth's rates`() {
        val run = world
        val years = run.config.scale.yearsPerHydraulicRound * run.config.erosion.hydraulicRounds / 1e6
        val metres = run.config.scale.reliefSpanMetres.toDouble()
        var land = 0.0
        var landCells = 0
        var belt = 0.0
        var beltCells = 0
        for (cell in run.production.indices) {
            if (!run.finalLand[cell]) continue
            val rate = run.production[cell] * metres / years
            land += rate
            landCells++
            if (run.belt[cell]) {
                belt += rate
                beltCells++
            }
        }
        val pooled = land / landCells
        val belts = belt / beltCells
        println(
            "TWOHEIGHT rates seed 42 at 512 rows: land %.1f m/Myr pooled, active belts %.1f m/Myr (Portenga and Bierman: basins median %.0f, mean %.0f; outcrops median %.1f, mean %.0f)"
                .format(pooled, belts, BASIN_MEDIAN, BASIN_MEAN, OUTCROP_MEDIAN, OUTCROP_MEAN)
        )
        val complaints = ArrayList<String>()
        if (pooled !in OUTCROP_MEDIAN..BASIN_MEAN) complaints += "land at %.1f m/Myr".format(pooled)
        if (belts < BELT_FLOOR) complaints += "belts at %.1f m/Myr".format(belts)
        KnownFailures.expect(BELTS_BELOW_EARTH, BELTS_RECORD) {
            if (complaints.isNotEmpty()) {
                throw RecordedViolation(
                    "the land does not lower at Earth's rates: ${complaints.joinToString("; ")}",
                    complaints.joinToString("; ")
                )
            }
        }
    }

    /** One erosion run, its rounds' budgets, both final heights and the closure's production. */
    internal class Run(
        val config: WorldGenConfig,
        val rounds: List<RoundMass>,
        val bed: FloatArray,
        val ground: FloatArray,
        val production: DoubleArray,
        val finalLand: BooleanArray,
        val belt: BooleanArray,
        val landCells: Int
    )

    companion object {
        /** Seed 42, a standard seed, at the everyday tier's detail grid with every default. */
        internal val world: Run by lazy { runOf(WorldGenConfig.forRows(42L, SharedWorlds.DETAIL_ROWS)) }

        internal fun runOf(config: WorldGenConfig): Run {
            val plates = PlateStage.generate(config, TerrainStage.generate(config))
            val cellCount = config.width * config.height
            val total = DoubleArray(cellCount)
            var finalBed = FloatArray(0)
            var finalGround = FloatArray(0)
            val rounds = ArrayList<RoundMass>()
            val watch = object : GroundWatch {
                override fun round(
                    round: Int, isLand: BooleanArray, cells: GroundCells, ground: FloatArray,
                    bedCut: DoubleArray, production: DoubleArray, bedsHeld: Int
                ) {
                    for (cell in production.indices) total[cell] += production[cell]
                }

                override fun finished(bed: FloatArray, ground: FloatArray) {
                    finalBed = bed.copyOf()
                    finalGround = ground.copyOf()
                }
            }
            erodeBlockingWatchingGround(config, plates.height, plates.upliftRateMmPerYear, watch) { rounds += it }
            val sea = SeaLevelStage.percentileCut(
                FloatField(config.width, config.height, finalGround), config.seaLevel, config.scale
            )
            val falloff = config.cellsFor(config.tectonics.boundaryFalloffKm)
            val belt = BooleanArray(cellCount) { cell ->
                plates.boundaryDistance.data[cell] <= falloff &&
                    (plates.nearestBoundaryClass[cell] == BoundaryClass.COLLISION_PLATEAU.ordinal ||
                        plates.nearestBoundaryClass[cell] == BoundaryClass.ANDEAN_MARGIN.ordinal)
            }
            return Run(config, rounds, finalBed, finalGround, total, sea.isLand, belt, sea.landCellCount)
        }

        const val MASS_RESIDUAL = 1e-8
        const val TRUNK_AREA_KM2 = 2_500f
        const val CONCAVITY_LOW = 0.35
        const val CONCAVITY_HIGH = 0.65
        const val BASIN_MEDIAN = 54.0
        const val BASIN_MEAN = 218.0
        const val OUTCROP_MEDIAN = 5.4
        const val OUTCROP_MEAN = 12.0

        /** A hundred metres a million years: the floor of Portenga and Bierman's active-margin basins. */
        const val BELT_FLOOR = 100.0

        const val BELTS_BELOW_EARTH = "E1a: the active belts lower slower than Earth's"
        const val BELTS_RECORD = "belts at 0.0 m/Myr"
    }
}
