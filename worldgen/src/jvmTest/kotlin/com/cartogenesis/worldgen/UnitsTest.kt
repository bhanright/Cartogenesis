package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionStage
import com.cartogenesis.worldgen.pipeline.GlaciationStage
import com.cartogenesis.worldgen.pipeline.HydraulicErosion
import kotlin.math.abs
import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * One ruler: every vertical constant in the pipeline is read back through `WorldScale`, and no
 * stage holds a metre of its own.
 *
 * The disagreement this exists to prevent had one unit of land elevation at 6,000 m in the climate
 * and about 8,000 m in the sea-level and erosion constants, with no ocean depth anywhere; the
 * consequence was that a constant written as "120 m" meant one thing where it was written and
 * another where it was spent. The cure is that a knob carries a unit and the conversion happens
 * once, from `WorldScale` and the grid, where the stage reads it.
 *
 * That is a property a test can hold, and this holds it the only way that proves anything: by
 * *moving the ruler*. Double the land's ceiling and every depth expressed in metres must come back
 * as half the share of the relief it was; double the sea's and every depth below the waterline
 * must halve while the land's do not move; halve the world's width and every reach must come back
 * as twice the cells. A constant that had quietly kept its own number would not move at all, which
 * is exactly what the second test below shows.
 *
 * See `docs/DESIGN_LEDGER.md`, S1.
 */
class UnitsTest : BorrowsSharedWorlds() {

    private val stock = WorldGenConfig(seed = 42L, width = 512, height = 512)

    /**
     * Every constant charged against the land's relief, read at the stock ruler and at one twice
     * as tall.
     *
     * Exact equality rather than a tolerance: these are divisions of a metre figure by a metre
     * figure, and doubling the divisor halves the quotient to the last bit.
     */
    @Test
    fun `every land-relief constant halves when the land's ceiling doubles`() {
        val taller = stock.copy(
            scale = stock.scale.copy(highestLandMetres = stock.scale.highestLandMetres * 2f)
        )
        val here = landReliefConstants(stock)
        val there = landReliefConstants(taller)

        here.forEach { (name, share) ->
            val moved = there.getValue(name)
            println("UNITS land %-22s %8.5f of the relief, %8.5f at twice the ceiling".format(name, share, moved))
            assertEquals(
                "$name is not read back through WorldScale.highestLandMetres",
                share / 2.0, moved.toDouble(), 0.0
            )
        }
        assertTrue("no land-relief constants were measured at all", here.isNotEmpty())
    }

    /** The same for everything charged against the sea's own range below the waterline. */
    @Test
    fun `every sea-depth constant halves when the ocean's floor doubles`() {
        val deeper = stock.copy(
            scale = stock.scale.copy(deepestOceanMetres = stock.scale.deepestOceanMetres * 2f)
        )
        val here = seaDepthConstants(stock)
        val there = seaDepthConstants(deeper)

        here.forEach { (name, share) ->
            val moved = there.getValue(name)
            println("UNITS sea  %-22s %8.5f of the depth,  %8.5f at twice the floor".format(name, share, moved))
            assertEquals(
                "$name is not read back through WorldScale.deepestOceanMetres",
                share / 2.0, moved.toDouble(), 0.0
            )
        }

        // And the other half of the claim: the sea's ruler is the sea's. Deepening the ocean must
        // not move a single constant charged against the land's relief.
        val landHere = landReliefConstants(stock)
        val landThere = landReliefConstants(deeper)
        landHere.forEach { (name, share) ->
            assertEquals(
                "$name moved when only the ocean's floor did, so it is reading the wrong ruler",
                share.toDouble(), landThere.getValue(name).toDouble(), 0.0
            )
        }
    }

    /**
     * The constants that are levels in the raw height field rather than heights above the water or
     * depths below it: the sea's own stand and the shelf break a delta pays to build past.
     *
     * Their ruler is the whole field, [WorldScale.reliefSpanMetres], which is the two halves added
     * together — so doubling both halves halves both constants, and no measured range enters the
     * arithmetic. That last part is the point of them: read off either half instead, the same
     * metre figure would be a different level on every seed.
     */
    @Test
    fun `every level in the height field halves when the whole range doubles`() {
        val bigger = stock.copy(
            scale = stock.scale.copy(
                highestLandMetres = stock.scale.highestLandMetres * 2f,
                deepestOceanMetres = stock.scale.deepestOceanMetres * 2f
            )
        )
        val here = fieldConstants(stock)
        val there = fieldConstants(bigger)
        here.forEach { (name, share) ->
            val moved = there.getValue(name)
            println("UNITS field %-22s %8.5f of the field, %8.5f at twice the range".format(name, share, moved))
            assertEquals(
                "$name is not read back through WorldScale.reliefSpanMetres",
                share / 2.0, moved.toDouble(), 0.0
            )
        }
    }

    /**
     * Every reach and radius, read at the stock world and at one half as wide.
     *
     * A length on the ground is more cells on a finer grid *and* more cells on a smaller world, and
     * both follow from the same division. The whole-cell figures are rounded, so they are compared
     * as counts rather than exactly doubled.
     */
    @Test
    fun `every reach is a length on the ground, not a count of cells`() {
        val narrower = stock.copy(
            scale = stock.scale.copy(worldWidthKm = stock.scale.worldWidthKm / 2.0)
        )
        val here = reaches(stock)
        val there = reaches(narrower)

        here.forEach { (name, cells) ->
            val moved = there.getValue(name)
            println("UNITS reach %-22s %8.3f cells at 12,000 km, %8.3f at 6,000".format(name, cells, moved))
            assertEquals(
                "$name is not read back through WorldScale.worldWidthKm",
                (cells * 2.0), moved.toDouble(), 1.0
            )
        }

        // And with the world's width held and the grid quadrupled, every one of them is four times
        // the cells: the same length on the ground, drawn finer.
        val finer = reaches(stock.atResolution(2048, 2048))
        here.forEach { (name, cells) ->
            assertEquals(
                "$name does not scale with the grid",
                cells * 4.0, finer.getValue(name).toDouble(), 1.0
            )
        }
    }

    /**
     * The control: a figure the pipeline reads off no vertical ruler does not move when the land's
     * ceiling does, and the comparison the first test makes refuses it.
     *
     * The stream-power coefficient is `K` times the round's length times the land's area over the
     * world's width ([HydraulicErosion.Rates.incisionCoefficient]): a rate on the whole field's
     * span, with no metre of land relief in it. So read at the stock ruler and at one twice as tall
     * it has to be the same float, and it has to fail the halving the first test asks of every
     * land-relief constant — which is what keeps the three tests above from passing vacuously: if
     * the ruler were not actually reaching the constants, they would look exactly like this one.
     * Both readings are the pipeline's own, taken through `Rates` at each ruler.
     */
    @Test
    fun `a constant with no unit does not move when the ruler does`() {
        val taller = stock.copy(
            scale = stock.scale.copy(highestLandMetres = stock.scale.highestLandMetres * 2f)
        )
        val here = HydraulicErosion.Rates(stock).incisionCoefficient.toDouble()
        val there = HydraulicErosion.Rates(taller).incisionCoefficient.toDouble()
        println("UNITS control incisionCoefficient %.6g, %.6g at twice the ceiling".format(here, there))
        assertEquals(
            "the stream-power coefficient moved when only the land's ceiling did, so it is reading" +
                " a ruler it has no business with",
            here, there, 0.0
        )
        assertTrue(
            "the stream-power coefficient halved with the land's ceiling, so the first test's" +
                " comparison cannot tell a constant with no unit from one read through the ruler",
            here / 2.0 != there
        )
    }

    /**
     * The declared ruler against the height field's own, as a residual in metres of sea level.
     *
     * `WorldScale` declares that the raw height field runs from `deepestOceanMetres` below the
     * water to `highestLandMetres` above it, so the waterline stands at
     * `WorldScale.shorelineFieldLevel` — 0.625 at the stock figures. Two stages work on that field
     * before any shoreline exists, the thermal sweeps and the stream-power incision, so both spend
     * the declared ruler; if the shoreline does not land where it is declared to, they are cutting
     * a different world from the one the rest of the pipeline reads.
     *
     * Until S2 it did not land there and could not. The field was renormalised to its own extremes
     * and the shoreline was a percentile of the *cells*, so where it fell in the *range* was an
     * output: 0.395, 0.437, 0.477 and 0.554 on these four seeds, which put the metres one field
     * unit was worth at 10,228 to 17,370 against the 16,000 declared, and this test held the
     * disagreement inside a factor of 1.7 rather than claiming there was none.
     *
     * Isostasy closed it. The plate stage builds the field out of altitudes — two crusts floating
     * at their own levels — and the ocean-coverage slider chooses how much of the world is drawn as
     * continental crust, so the percentile lands near the isostatic datum instead of wherever the
     * histogram put it. The residual is quoted in metres rather than as a factor because a factor
     * taken off the tallest cell says as much about whether a world happens to own a six-kilometre
     * mountain as it does about the ruler; the old reading is printed beside it.
     *
     * `IsostasyTest` is where the residual is shown to open up again when the crust is drawn to the
     * wrong target. Here it is a regression guard on the ruler.
     */
    @Test
    fun `the declared ruler and the height field's own agree`() {
        val worst = ArrayList<Pair<Long, Double>>()
        listOf(7L, 42L, 1234L, 99L).forEach { seed ->
            val world = SharedWorlds.world(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val scale = world.config.scale
            val shoreline = world.sea.shorelineHeight
            val highest = world.erosion.height.data.max()
            val landRange = (highest - shoreline).toDouble()
            // Where the cut landed against the level the two ends of `WorldScale` put the
            // waterline at, in metres of sea level.
            val residual = scale.altitudeAtField(shoreline).toDouble()
            // And the reading this test took before the field had an absolute scale: what one unit
            // of it is worth, measured off the land against what `WorldScale` declares.
            val measured = scale.highestLandMetres / landRange
            worst.add(seed to residual)
            println(
                ("UNITS ruler seed %-5d shoreline at %.3f of the field against %.3f declared," +
                    " %+,.0f m; land relief %.3f, %,.0f m per unit measured against %,.0f declared")
                    .format(
                        seed, shoreline, scale.shorelineFieldLevel, residual, landRange, measured,
                        scale.reliefSpanMetres.toDouble()
                    )
            )
        }
        val furthest = worst.maxByOrNull { abs(it.second) }!!
        assertTrue(
            "seed ${furthest.first}: the sea-level cut lands" +
                " ${"%.0f".format(furthest.second)} m from the level WorldScale declares the" +
                " shoreline at, outside the stated $SHORELINE_RESIDUAL_METRES m — the thermal" +
                " sweeps and the incision spend the declared ruler, so past this the world they" +
                " are cutting is not the world the rest of the pipeline is reading",
            abs(furthest.second) <= SHORELINE_RESIDUAL_METRES
        )
    }

    private fun landReliefConstants(config: WorldGenConfig): Map<String, Float> {
        val rates = HydraulicErosion.Rates(config)
        val carving = GlaciationStage.Carving(config)
        val scale = config.scale
        return linkedMapOf(
            "erosion.pondDepth" to rates.pondDepth,
            "erosion.deltaFreeboard" to rates.deltaFreeboard,
            "lakes.minDepth" to scale.reliefShareOfMetres(config.lakes.minDepthMetres),
            "ice.valleyRelief" to carving.valleyRelief,
            "ice.sheetLowering" to carving.sheetLowering,
            "ice.sheetBasinDepth" to carving.sheetBasinDepth,
            "ice.deepening" to carving.deepening,
            "ice.overDeepening" to carving.overDeepening,
            "ice.basinDrop" to carving.basinDrop,
            "ice.cirqueDepth" to carving.cirqueDepth,
            "ice.moraineHeight" to carving.moraineHeight
        )
    }

    private fun seaDepthConstants(config: WorldGenConfig): Map<String, Float> {
        val carving = GlaciationStage.Carving(config)
        val scale = config.scale
        return linkedMapOf(
            "sea.shelfDepth" to scale.depthShareOfMetres(config.sea.shelfDepthMetres),
            "nations.navigableDepth" to scale.depthShareOfMetres(config.nations.navigableDepthMetres),
            "ice.fjordDepth" to carving.fjordDepth
        )
    }

    private fun fieldConstants(config: WorldGenConfig): Map<String, Float> {
        val rates = HydraulicErosion.Rates(config)
        return linkedMapOf(
            "sea.lowstand" to config.scale.fieldShareOfMetres(config.sea.lowstandMetres),
            "erosion.shelfBreak" to rates.shelfBreak,
            "erosion.criticalDrop" to ErosionStage.maxOrthogonalDrop(config)
        )
    }

    private fun reaches(config: WorldGenConfig): Map<String, Float> {
        val rates = HydraulicErosion.Rates(config)
        val carving = GlaciationStage.Carving(config)
        return linkedMapOf(
            "erosion.deltaReach" to rates.deltaReachCells.toFloat(),
            "erosion.outletReach" to rates.outletReachCells.toFloat(),
            "erosion.debrisTravel" to ErosionStage.sweepsFor(config).toFloat(),
            "sea.shelfWidth" to config.cellsFor(config.sea.shelfWidthKm),
            "ice.valleyWidth" to carving.valleyWidthCells,
            "ice.minTroughLength" to carving.minTroughLengthCells.toFloat(),
            "ice.runOut" to carving.runOutCells.toFloat(),
            "ice.basinSpacing" to carving.basinSpacingCells,
            "ice.cirqueRadius" to carving.cirqueRadiusCells,
            "ice.fjordReach" to carving.fjordReachCells.toFloat()
        )
    }

    private companion object {
        /**
         * How far the sea-level cut may land from the level `WorldScale` declares the shoreline at,
         * in metres.
         *
         * A thousand, the same figure as `IsostasyTest.SHORELINE_RESIDUAL_BAR_METRES`, which is
         * where it is shown to bite and where what it is is set out: a regression pin above the
         * residuals this generator produces, which the case above prints, and not a derivation.
         * Before S2 the question could not be asked in metres at all — the field was renormalised
         * to its own extremes, so this test held a *ratio* inside a factor of 1.7 instead.
         */
        const val SHORELINE_RESIDUAL_METRES = 1_000.0
    }
}
