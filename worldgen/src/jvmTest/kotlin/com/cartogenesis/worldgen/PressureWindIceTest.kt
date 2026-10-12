package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.math.abs
import kotlin.test.Test

/**
 * Where the permanent ice went when the wind gained its regional departure, and why.
 *
 * W2 moved the pooled ice share of land under `SnowBalanceTest`'s floor of half Earth's 10.1%, and
 * this is the measurement that says whether that drop is physics or a defect. It asserts nothing:
 * it reports the two inputs the snow balance actually reads — accumulation (cold-half rainfall over
 * frozen ground) and ablation (warm-half temperature) — per cap, and the zonal-mean rainfall band
 * by band.
 *
 * **A defect's signature is a step, physics is a slope.** Since W2 the march runs each circulation
 * belt twice, once each way, and splices the two by which way each cell's own wind blows; and a
 * slant steeper than the cell's aspect ratio is clamped, so that one step of the march cannot
 * price a journey of many cells at the rate of one. Either could lose moisture, and both would
 * lose it *at a belt edge* or *where the clamp saturates* rather than smoothly across latitude. So
 * the report carries the share of cells that took the reversed march and the share sitting on the
 * clamp, per band, beside the rainfall.
 *
 * It found both. The clamp was a flat one row per cell when it was first written, and bound on 71%
 * of the cells between the equator and 10 degrees north — where the Coriolis force vanishes and
 * the pressure wind runs straight down its own gradient — costing those bands 8 to 9% of their
 * rain; that was a defect and it was fixed. What is left is a smooth fall in *cold-half* rainfall
 * over high-latitude land with warm-half temperature unmoved, which is a winter continent's
 * thermal high blowing its air out to sea, and is the mechanism working.
 *
 * One caveat on reading the report: the reversed-sweep share compares a *season's* wind against
 * the *annual* belt direction, and those differ by the seasonal tilt near a belt edge, so the
 * bands reading 100% are an artefact of that comparison rather than a wholesale reversal. The
 * bands reading a few per cent, near the equator, are the real regional reversals.
 *
 * See docs/DESIGN_LEDGER.md, W2.
 */
class PressureWindIceTest {

    private companion object {
        val seeds = listOf(7L, 42L, 1234L, 99L)
        const val size = 512

        /** Where a polar cap stops being a cap and starts being a mountain, in degrees. */
        const val POLAR_EDGE_DEGREES = 55f

        /** The width of a reported latitude band, in degrees. */
        const val BAND_DEGREES = 5f

        /**
         * What a rainfall field of 1.0 means in millimetres.
         *
         * `ClimateResult.julyHalfPrecipitation` and `januaryHalfPrecipitation` are the 0..1 copies, so
         * this recovers their millimetres. They clamp at 1, which is 1,200 mm — far above anything
         * a polar cap receives, so the recovery is exact everywhere this report looks, and the
         * pooled figures over all land carry the clamp and are labelled as such.
         */
        const val REFERENCE_MM = 1200f
    }

    @Test
    fun `report where the ice went, and whether a belt boundary took it`() {
        seeds.forEach { seed ->
            val on = generate(seed, pressureWinds = true)
            val off = generate(seed, pressureWinds = false)
            reportCaps(seed, on, off)
            reportBands(seed, on, off)
        }
    }

    /** Ice, accumulation and ablation over each cap, with the pressure term on and off. */
    private fun reportCaps(seed: Long, on: WorldMap, off: WorldMap) {
        val caps = listOf(
            "polar north" to { latitude: Float -> latitude > POLAR_EDGE_DEGREES },
            "polar south" to { latitude: Float -> latitude < -POLAR_EDGE_DEGREES },
            "mountain" to { latitude: Float -> abs(latitude) <= POLAR_EDGE_DEGREES }
        )
        val warmOn = LocalSeasons(on)
        val warmOff = LocalSeasons(off)
        caps.forEach { (name, inCap) ->
            val measuredOn = capMeasure(on, warmOn, inCap)
            val measuredOff = capMeasure(off, warmOff, inCap)
            println(
                ("ICE CAP seed %d %s: ice %d -> %d cells; over the cap's own land, cold-half " +
                    "rain %.0f -> %.0f mm, warm-half rain %.0f -> %.0f mm, warm-half temperature " +
                    "%+.2f -> %+.2f C; over the cells frozen with the term off, cold-half rain " +
                    "%.0f -> %.0f mm and warm-half temperature %+.2f -> %+.2f C")
                    .format(
                        seed, name, measuredOff.iceCells, measuredOn.iceCells,
                        measuredOff.coldRainMm, measuredOn.coldRainMm,
                        measuredOff.warmRainMm, measuredOn.warmRainMm,
                        measuredOff.warmTemperatureC, measuredOn.warmTemperatureC,
                        meanOver(warmOff, measuredOff.frozen).coldRainMm,
                        meanOver(warmOn, measuredOff.frozen).coldRainMm,
                        meanOver(warmOff, measuredOff.frozen).warmTemperatureC,
                        meanOver(warmOn, measuredOff.frozen).warmTemperatureC
                    )
            )
        }
    }

    /**
     * Zonal-mean rainfall band by band, with the share of each band that took the reversed march
     * and the share sitting on the slant clamp.
     */
    private fun reportBands(seed: Long, on: WorldMap, off: WorldMap) {
        val cellsAcross = on.width
        val cellsDown = on.height
        // Each band's own summer: the calendar half about July north of the equator, about January
        // south of it.
        val julyHalfWind = ClimateStage.seasonalSurfaceWindMps(
            on.config, on.sea, on.ocean, Season.JULY_HALF
        )
        val januaryHalfWind = ClimateStage.seasonalSurfaceWindMps(
            on.config, on.sea, on.ocean, Season.JANUARY_HALF
        )
        val cellWidthOverHeight = (on.config.scale.cellWidthKm(cellsAcross) /
            on.config.scale.cellHeightKm(cellsDown)).toFloat()
        // The march's own cap, which is the cell's aspect ratio: a step no longer than root two
        // cells. Read from the same figure the stage derives it from, so this reports the clamp
        // that was applied and not one it used to apply.
        val maxSlant = cellWidthOverHeight
        var band = 90f - BAND_DEGREES
        while (band > -90f) {
            val top = band + BAND_DEGREES
            var rainOn = 0.0
            var rainOff = 0.0
            var cells = 0
            var reversed = 0
            var clamped = 0
            for (row in 0 until cellsDown) {
                val latitude = ClimateStage.latitudeOf(row, cellsDown)
                if (latitude <= band || latitude > top) continue
                val summerWind = if (latitude > 0f) julyHalfWind else januaryHalfWind
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    rainOn += on.climate.precipitationMm.data[cell]
                    rainOff += off.climate.precipitationMm.data[cell]
                    cells++
                    // Which way the summer wind blows against the belt the row belongs to: a cell
                    // whose own wind opposes it is one the reversed sweep supplied.
                    val beltEastward = off.climate.windDirection[cell]
                    val eastward = summerWind.eastwardMps[cell]
                    if ((if (eastward >= 0f) 1 else -1) != beltEastward) reversed++
                    val speed = abs(eastward)
                    val slant = if (speed == 0f) {
                        1f
                    } else {
                        abs(summerWind.southwardMps[cell] / speed * cellWidthOverHeight)
                    }
                    if (slant >= maxSlant) clamped++
                }
            }
            if (cells > 0) {
                println(
                    ("ICE BAND seed %d %+5.0f to %+5.0f deg: rain %.0f -> %.0f mm (x%.3f), " +
                        "reversed sweep %.1f%% of cells, slant on the clamp %.1f%%")
                        .format(
                            seed, band, top, rainOff / cells, rainOn / cells,
                            if (rainOff > 0.0) rainOn / rainOff else Double.NaN,
                            reversed * 100.0 / cells, clamped * 100.0 / cells
                        )
                )
            }
            band -= BAND_DEGREES
        }
    }

    private class CapMeasure(
        val iceCells: Int,
        val coldRainMm: Double,
        val warmRainMm: Double,
        val warmTemperatureC: Double,
        /** The cells this cap froze, so the other world can be measured over the same ground. */
        val frozen: BooleanArray
    )

    /**
     * Each cell's own warm and cold half-year: the warmer and the cooler of the calendar's two
     * (Peel, Finlayson and McMahon 2007), its temperature and its rain in millimetres.
     */
    private class LocalSeasons(world: WorldMap) {
        private val julyC = ClimateStage.halfYearTemperature(
            world.config, world.sea, world.climate.temperature, Season.JULY_HALF
        ).data
        private val januaryC = ClimateStage.halfYearTemperature(
            world.config, world.sea, world.climate.temperature, Season.JANUARY_HALF
        ).data
        private val julyRain = world.climate.julyHalfPrecipitation.data
        private val januaryRain = world.climate.januaryHalfPrecipitation.data

        private fun julyIsWarm(cell: Int) = ClimateStage.localSummerIsJulyHalf(julyC[cell], januaryC[cell])
        fun warmC(cell: Int): Float = if (julyIsWarm(cell)) julyC[cell] else januaryC[cell]
        fun warmRainMm(cell: Int): Float = (if (julyIsWarm(cell)) julyRain[cell] else januaryRain[cell]) * REFERENCE_MM
        fun coldRainMm(cell: Int): Float = (if (julyIsWarm(cell)) januaryRain[cell] else julyRain[cell]) * REFERENCE_MM
    }

    private fun capMeasure(
        world: WorldMap,
        seasons: LocalSeasons,
        inCap: (Float) -> Boolean
    ): CapMeasure {
        val cellsAcross = world.width
        val cellsDown = world.height
        val frozen = BooleanArray(cellsAcross * cellsDown)
        var iceCells = 0
        var coldRain = 0.0
        var warmRain = 0.0
        var warmC = 0.0
        var landCells = 0
        for (cell in 0 until cellsAcross * cellsDown) {
            if (!world.sea.isLand[cell]) continue
            if (!inCap(ClimateStage.latitudeOf(cell / cellsAcross, cellsDown))) continue
            landCells++
            coldRain += seasons.coldRainMm(cell)
            warmRain += seasons.warmRainMm(cell)
            warmC += seasons.warmC(cell)
            if (world.climate.biome[cell] == Biome.ICE_SHEET) {
                iceCells++
                frozen[cell] = true
            }
        }
        if (landCells == 0) return CapMeasure(0, 0.0, 0.0, 0.0, frozen)
        return CapMeasure(
            iceCells, coldRain / landCells, warmRain / landCells, warmC / landCells, frozen
        )
    }

    /** The same two inputs over a fixed set of cells, so both worlds are read on one ground. */
    private fun meanOver(
        seasons: LocalSeasons,
        cells: BooleanArray
    ): CapMeasure {
        var coldRain = 0.0
        var warmRain = 0.0
        var warmC = 0.0
        var counted = 0
        for (cell in cells.indices) {
            if (!cells[cell]) continue
            counted++
            coldRain += seasons.coldRainMm(cell)
            warmRain += seasons.warmRainMm(cell)
            warmC += seasons.warmC(cell)
        }
        if (counted == 0) return CapMeasure(0, 0.0, 0.0, 0.0, cells)
        return CapMeasure(0, coldRain / counted, warmRain / counted, warmC / counted, cells)
    }

    private fun generate(seed: Long, pressureWinds: Boolean): WorldMap {
        val base = WorldGenConfig.forRows(seed, size)
        return WorldGenerationEngine.generateBlocking(
            base.copy(climate = base.climate.copy(pressureWinds = pressureWinds))
        )
    }
}
