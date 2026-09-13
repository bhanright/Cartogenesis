package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether the polar sea freezes, where its edge sits, and what freezing does to the rain.
 *
 * Three claims. The **edge**: a cold season closes the polar ocean, and the latitude it reaches
 * is Earth's. The **season**: the pack that survives the warm half of the year is a subset of the
 * winter one and a much smaller country, which is the difference between the Arctic in March and
 * the Arctic in September. And the **lid**: nothing evaporates through a metre of ice, so the
 * frozen sea is one of the driest surfaces on the map — which is why polar deserts exist, and
 * which is what the snow balance needs if an ice sheet at the pole is not to feed itself for ever.
 *
 * The control for all three is `ClimateConfig.seaIce` off, which is the world before W1: a polar
 * ocean evaporating as freely as the tropics.
 *
 * See REALISM_PLAN.md, W1.
 */
class SeaIceTest {

    private companion object {
        val seeds = listOf(7L, 42L, 1234L)
        const val size = 512

        /**
         * Where a cold-season ice edge belongs on the map, in degrees of latitude, and where
         * Earth's is.
         *
         * This is the guard on what a reader sees, not on the model: it measures
         * `ClimateResult.winterSeaIce`, cell by cell, on a generated world. Earth's winter pack
         * reaches about 44 N in the Sea of Okhotsk and about 75 N off the Norwegian coast, with the
         * zonal-mean March edge near 60 N; the Antarctic's September maximum sits near 60 S all the
         * way round (Fetterer et al., *Sea Ice Index*, NSIDC). The equatorward-most frozen water is
         * the Okhotsk end of that spread, so the bar is 45 to 70 and Earth's zonal mean of 60 is
         * printed beside every measurement.
         *
         * It was 40 to 78 in W1's first pass, which was loose enough to accept an edge at 49
         * degrees — nine degrees equatorward of Earth's zonal mean and past the Okhotsk. The second
         * pass moved the edge to 54-55 by correcting the model's albedo and its heat transport, and
         * tightened the bar to what Earth's own spread actually allows. Five degrees short of the
         * zonal mean is what remains, and it is reported rather than tuned: the model's sea column
         * at 60 degrees reads two degrees under Earth's marine air there, and two degrees is what
         * five degrees of latitude costs at that gradient.
         */
        const val ICE_EDGE_EQUATORWARD_LIMIT = 45f
        const val ICE_EDGE_POLEWARD_LIMIT = 70f
        const val EARTH_ZONAL_MEAN_ICE_EDGE = 60f

        /**
         * How much drier the frozen sea has to be than the open water at the same latitudes, as a
         * ratio of rainfall.
         *
         * Earth's is far starker than this: the central Arctic Ocean receives about 150 mm a year
         * and the open North Atlantic at 60 N about 1,000, a factor of seven. The guard asks for
         * **three**, because a cell of pack ice on this map still receives whatever the air
         * arriving over it was already carrying, and much of that air crossed open water a few
         * hundred kilometres upwind.
         */
        const val ICE_DRYNESS_RATIO = 3.0

        /**
         * How much wetter the same cells must be with the ice switched off, as a ratio.
         *
         * Two, and it is a different measurement from the one above rather than a looser version
         * of it. Above, the frozen sea is compared with the open water beside it on the *same*
         * world, and the gap is the full one — a lid against a source. Here the same cells are
         * compared against themselves on a world where nothing freezes, and that world's polar
         * ocean also feeds every cell downwind of it, so the comparison is between two whole
         * climates rather than between two surfaces. Measured, seed 7's frozen cells take 511 mm
         * with the lid and 1,259 mm without it, a factor of 2.5.
         */
        const val ICE_OFF_WETTER_RATIO = 2.0
    }

    @Test
    fun `the cold season closes the polar sea and its edge sits where Earth's does`() {
        seeds.forEach { seed ->
            val world = generate(seed, seaIce = true)
            val measured = measure(world)
            println(
                ("SEA ICE seed %d: cold-season ice %.2f%% of the sea reaching %.1f deg " +
                    "(Earth's zonal mean %.0f, %.1f short), warm-season ice %.2f%% reaching %.1f deg")
                    .format(
                        seed, measured.winterShare * 100, measured.winterEdge,
                        EARTH_ZONAL_MEAN_ICE_EDGE, EARTH_ZONAL_MEAN_ICE_EDGE - measured.winterEdge,
                        measured.summerShare * 100, measured.summerEdge
                    )
            )
            assertTrue(
                measured.winterShare > 0.0,
                "seed $seed froze no sea at all in the cold season"
            )
            assertTrue(
                measured.winterEdge in ICE_EDGE_EQUATORWARD_LIMIT..ICE_EDGE_POLEWARD_LIMIT,
                ("seed %d's cold-season ice edge is at %.1f deg, outside Earth's %.0f-%.0f")
                    .format(seed, measured.winterEdge, ICE_EDGE_EQUATORWARD_LIMIT, ICE_EDGE_POLEWARD_LIMIT)
            )
            assertTrue(
                measured.summerShare < measured.winterShare,
                ("seed %d's warm-season pack (%.2f%%) is not smaller than its cold-season one " +
                    "(%.2f%%)").format(
                    seed, measured.summerShare * 100, measured.winterShare * 100
                )
            )
            assertTrue(
                measured.summerOutsideWinter == 0,
                "seed $seed has ${measured.summerOutsideWinter} cells frozen in the warm season " +
                    "but not the cold one"
            )
        }
    }

    @Test
    fun `the march takes no moisture from ice, and with the ice off it takes plenty`() {
        seeds.forEach { seed ->
            val frozenWorld = generate(seed, seaIce = true)
            val measured = measure(frozenWorld)
            // The same cells on a world where the sea never freezes: identical geography, identical
            // temperatures, and the only difference is whether the march may evaporate off them.
            val openWorld = generate(seed, seaIce = false)
            val openRainMm = meanRainOver(openWorld, measured.winterFrozen)

            println(
                ("SEA ICE seed %d: cold-season rain over the frozen sea %.0f mm against %.0f mm " +
                    "over open water at the same latitudes (x%.2f); the same cells with the ice " +
                    "off take %.0f mm")
                    .format(
                        seed, measured.rainOverIceMm, measured.rainOverOpenSeaMm,
                        measured.rainOverOpenSeaMm / measured.rainOverIceMm.coerceAtLeast(0.1),
                        openRainMm
                    )
            )
            assertTrue(
                measured.rainOverOpenSeaMm / measured.rainOverIceMm.coerceAtLeast(0.1)
                    >= ICE_DRYNESS_RATIO,
                ("seed %d: the frozen sea takes %.0f mm against the open sea's %.0f, only x%.2f drier")
                    .format(
                        seed, measured.rainOverIceMm, measured.rainOverOpenSeaMm,
                        measured.rainOverOpenSeaMm / measured.rainOverIceMm.coerceAtLeast(0.1)
                    )
            )
            assertTrue(
                openRainMm > measured.rainOverIceMm * ICE_OFF_WETTER_RATIO,
                ("seed %d: with the ice off the same cells take %.0f mm against %.0f with it on, " +
                    "so the guard is not measuring the lid")
                    .format(seed, openRainMm, measured.rainOverIceMm)
            )
        }
    }

    @Test
    fun `the biome draws the pack that survives the summer`() {
        val world = generate(42L, seaIce = true)
        var iceCells = 0
        var iceOutsidePack = 0
        for (cell in world.climate.biome.indices) {
            if (world.sea.isLand[cell]) continue
            if (world.climate.biome[cell] != Biome.ICE_SHEET) continue
            iceCells++
            if (!world.climate.summerSeaIce[cell]) iceOutsidePack++
        }
        println("SEA ICE seed 42: $iceCells water cells drawn as ice, $iceOutsidePack of them off the pack")
        assertTrue(iceCells > 0, "no water cell is drawn as ice at all")
        assertTrue(iceOutsidePack == 0, "$iceOutsidePack water cells are drawn as ice off the pack")

        val open = generate(42L, seaIce = false)
        val openIce = open.climate.biome.indices.count {
            !open.sea.isLand[it] && open.climate.biome[it] == Biome.ICE_SHEET
        }
        println("SEA ICE seed 42 with the sea ice off: $openIce water cells drawn as ice")
        assertTrue(openIce == 0, "$openIce water cells are drawn as ice with the sea ice off")
    }

    private fun generate(seed: Long, seaIce: Boolean): WorldMap {
        val base = WorldGenConfig(seed = seed, width = size, height = size)
        return WorldGenerationEngine.generateBlocking(
            base.copy(climate = base.climate.copy(seaIce = seaIce))
        )
    }

    private class Measured(
        val winterShare: Double,
        val summerShare: Double,
        val winterEdge: Float,
        val summerEdge: Float,
        val summerOutsideWinter: Int,
        val rainOverIceMm: Double,
        val rainOverOpenSeaMm: Double,
        /** The cells the cold season froze, for measuring the same ones on the control world. */
        val winterFrozen: BooleanArray
    )

    private fun measure(world: WorldMap): Measured {
        val cellsAcross = world.width
        val cellsDown = world.height
        var winterCells = 0
        var summerCells = 0
        var seaCells = 0
        var winterEdge = 90f
        var summerEdge = 90f
        var summerOutsideWinter = 0

        for (row in 0 until cellsDown) {
            val latitude = abs(ClimateStage.latitudeOf(row, cellsDown))
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (world.sea.isLand[cell]) continue
                seaCells++
                if (world.climate.winterSeaIce[cell]) {
                    winterCells++
                    if (latitude < winterEdge) winterEdge = latitude
                }
                if (world.climate.summerSeaIce[cell]) {
                    summerCells++
                    if (latitude < summerEdge) summerEdge = latitude
                    if (!world.climate.winterSeaIce[cell]) summerOutsideWinter++
                }
            }
        }

        // Rain over the frozen sea against rain over the open sea in the same rows, so latitude is
        // not what the comparison is measuring. The cold season's field is the annual mean's colder
        // half; `precipitationMm` is the annual figure, and the ice is a cold-season fact, so this
        // reads the winter march's own cells.
        var iceRain = 0.0
        var iceRainCells = 0
        var openRain = 0.0
        var openRainCells = 0
        val frozenRows = BooleanArray(cellsDown)
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                if (world.climate.winterSeaIce[row * cellsAcross + column]) {
                    frozenRows[row] = true
                    break
                }
            }
        }
        for (row in 0 until cellsDown) {
            if (!frozenRows[row]) continue
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (world.sea.isLand[cell]) continue
                if (world.climate.winterSeaIce[cell]) {
                    iceRain += world.climate.precipitationMm.data[cell]
                    iceRainCells++
                } else {
                    openRain += world.climate.precipitationMm.data[cell]
                    openRainCells++
                }
            }
        }

        return Measured(
            winterShare = winterCells.toDouble() / seaCells,
            summerShare = summerCells.toDouble() / seaCells,
            winterEdge = winterEdge,
            summerEdge = summerEdge,
            summerOutsideWinter = summerOutsideWinter,
            rainOverIceMm = if (iceRainCells == 0) 0.0 else iceRain / iceRainCells,
            rainOverOpenSeaMm = if (openRainCells == 0) 0.0 else openRain / openRainCells,
            winterFrozen = BooleanArray(cellsAcross * cellsDown) { world.climate.winterSeaIce[it] }
        )
    }

    /** Mean annual rainfall in millimetres over the cells [mask] marks, on [world]. */
    private fun meanRainOver(world: WorldMap, mask: BooleanArray): Double {
        var total = 0.0
        var counted = 0
        for (cell in mask.indices) {
            if (!mask[cell]) continue
            total += world.climate.precipitationMm.data[cell]
            counted++
        }
        return if (counted == 0) 0.0 else total / counted
    }
}
