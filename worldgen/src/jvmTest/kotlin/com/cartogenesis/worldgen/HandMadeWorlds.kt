package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateResult
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.ColumnWater
import com.cartogenesis.worldgen.pipeline.LakeResult
import com.cartogenesis.worldgen.pipeline.RiverResult
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.SurfaceEvaporation

/**
 * Worlds drawn by hand, a stage's inputs and nothing else: a land mask and the ground under it,
 * a climate given cell by cell, a routing given cell by cell. A guard built on one of these asks
 * one question of one stage with every other answer fixed, which a generated world cannot do.
 *
 * Every function takes the cell's column and row and every grid is row-major; the config is the
 * default one at [side] cells square, so a cell is the default world's ground at that grid.
 */
internal object HandMadeWorlds {

    fun config(side: Int = 64): WorldGenConfig = WorldGenConfig(seed = 1L, width = side, height = side)

    fun sea(config: WorldGenConfig, isLand: (Int, Int) -> Boolean, ground: (Int, Int) -> Float): SeaLevelResult {
        val cellsAcross = config.width
        val land = BooleanArray(cellsAcross * config.height) { isLand(it % cellsAcross, it / cellsAcross) }
        val elevation = FloatField(cellsAcross, config.height)
        for (cell in land.indices) elevation.data[cell] = ground(cell % cellsAcross, cell / cellsAcross)
        return SeaLevelResult(0.625f, land, elevation, land.count { it })
    }

    /**
     * A climate with [rainMm] a year and the two seasons' temperatures in degrees Celsius; the
     * rest of it is bare ground under a still sky, which no stage these worlds are built for reads.
     */
    fun climate(
        config: WorldGenConfig,
        rainMm: (Int, Int) -> Float,
        summerC: (Int, Int) -> Float = { _, _ -> 20f },
        winterC: (Int, Int) -> Float = { _, _ -> 5f },
        /** The year's potential evapotranspiration, mm; by default [referenceYearMm] of the two seasons. */
        potentialMm: (Int, Int) -> Float = { x, y -> referenceYearMm(summerC(x, y), winterC(x, y), open = false) },
        /** The year's open-water evaporation, mm; by default [referenceYearMm] of the two seasons. */
        openWaterMm: (Int, Int) -> Float = { x, y -> referenceYearMm(summerC(x, y), winterC(x, y), open = true) }
    ): ClimateResult {
        val cellsAcross = config.width
        val cellCount = cellsAcross * config.height
        fun field(value: (Int, Int) -> Float) = FloatField(cellsAcross, config.height).also { field ->
            for (cell in 0 until cellCount) field.data[cell] = value(cell % cellsAcross, cell / cellsAcross)
        }
        val precipitationMm = field(rainMm)
        val summer = field(summerC)
        val winter = field(winterC)
        return ClimateResult(
            temperature = field { x, y -> (summerC(x, y) + winterC(x, y)) / 2f },
            summerTemperature = summer,
            winterTemperature = winter,
            julyTemperature = summer,
            januaryTemperature = winter,
            precipitation = field { x, y -> (rainMm(x, y) / ClimateStage.REFERENCE_MM).coerceIn(0f, 1f) },
            julyHalfPrecipitation = field { x, y -> (rainMm(x, y) / 2f / ClimateStage.REFERENCE_MM).coerceIn(0f, 1f) },
            januaryHalfPrecipitation = field { x, y -> (rainMm(x, y) / 2f / ClimateStage.REFERENCE_MM).coerceIn(0f, 1f) },
            precipitationMm = precipitationMm,
            windDirection = IntArray(cellCount) { 1 },
            windMeridional = FloatField(cellsAcross, config.height),
            julyHalfSeaIce = BooleanArray(cellCount),
            januaryHalfSeaIce = BooleanArray(cellCount),
            biome = Array(cellCount) { Biome.GRASSLAND },
            vegetationDensity = FloatField(cellsAcross, config.height),
            permafrost = ByteArray(cellCount),
            potentialEvapotranspirationMm = field(potentialMm),
            openWaterEvaporationMm = field(openWaterMm)
        )
    }

    /**
     * A year's evaporative demand over ground whose two half-years stand at [summerC] and
     * [winterC], in millimeters: FAO-56's reference rate, or Penman's open water when [open],
     * under the sun of 30 degrees and air at half saturation. A hand-made world has no air to
     * read a humidity off, so it states one; the figure is the world's own, as the climate's is.
     */
    fun referenceYearMm(summerC: Float, winterC: Float, open: Boolean): Float {
        fun half(temperatureC: Float, julyHalf: Boolean): Double {
            val sun = SurfaceEvaporation.halfYearExtraterrestrialMjPerM2Day(HAND_MADE_LATITUDE, EARTH_OBLIQUITY, julyHalf)
            val vapor = HAND_MADE_HUMIDITY * ColumnWater.saturationVaporPressureKpa(temperatureC.toDouble())
            return if (open) {
                SurfaceEvaporation.openWaterEvaporationMmPerDay(temperatureC.toDouble(), vapor, sun, 0.0)
            } else {
                SurfaceEvaporation.referenceEvapotranspirationMmPerDay(temperatureC.toDouble(), vapor, sun, 0.0)
            }
        }
        return ((half(summerC, true) + half(winterC, false)) * 0.5 * SurfaceEvaporation.DAYS_PER_YEAR_DOUBLE).toFloat()
    }

    private const val HAND_MADE_LATITUDE = 30.0
    private const val EARTH_OBLIQUITY = 23.44
    private const val HAND_MADE_HUMIDITY = 0.5

    /**
     * A routing drawn by hand: [receiver] gives each land cell's receiver as a cell index, or -1.
     * The filled surface is flat, so nothing that reads this can order by height and be right, and
     * the flow is whatever [flowOf] is summed down the routing.
     */
    fun rivers(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        receiver: (Int, Int) -> Int,
        lakes: LakeResult = LakeResult(IntArray(config.width * config.height) { LakeResult.NO_LAKE }, emptyList(), cellsAcross = config.width),
        flowOf: (Int) -> Float = { 1f }
    ): RiverResult {
        val cellsAcross = config.width
        val cellCount = cellsAcross * config.height
        val target = IntArray(cellCount) { cell ->
            if (sea.isLand[cell]) receiver(cell % cellsAcross, cell / cellsAcross) else -1
        }
        val flow = FloatField(cellsAcross, config.height)
        for (cell in 0 until cellCount) if (sea.isLand[cell]) flow.data[cell] = flowOf(cell)
        // Summed down the routing by walking each cell's path, which a hand-made routing small
        // enough to draw can afford and which reads no order the code under test might share.
        val accumulated = FloatField(cellsAcross, config.height)
        for (start in 0 until cellCount) {
            if (!sea.isLand[start]) continue
            var cell = start
            var steps = 0
            while (cell >= 0 && sea.isLand[cell] && steps++ < cellCount) {
                accumulated.data[cell] += flow.data[start]
                cell = target[cell]
            }
        }
        val flat = FloatField(cellsAcross, config.height)
        for (cell in 0 until cellCount) flat.data[cell] = if (sea.isLand[cell]) 0.1f else sea.relativeElevation.data[cell]
        return RiverResult(flat, accumulated, target, emptyList(), lakes)
    }

    /** One step from ([x], [y]) along a row, a column or a diagonal, wrapping east to west. */
    fun cellAt(config: WorldGenConfig, x: Int, y: Int): Int =
        y * config.width + ((x % config.width) + config.width) % config.width

    /**
     * The land mask's own components, walking the eight neighbours as the routing does: which
     * piece of dry ground each land cell stands on, or -1 at sea.
     */
    fun landComponents(config: WorldGenConfig, isLand: BooleanArray): IntArray {
        val cellsAcross = config.width
        val component = IntArray(isLand.size) { -1 }
        var count = 0
        val stack = ArrayDeque<Int>()
        for (start in isLand.indices) {
            if (!isLand[start] || component[start] >= 0) continue
            component[start] = count
            stack.addLast(start)
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                val x = cell % cellsAcross
                val y = cell / cellsAcross
                for (dy in -1..1) for (dx in -1..1) {
                    if (y + dy !in 0 until config.height) continue
                    val neighbour = cellAt(config, x + dx, y + dy)
                    if (isLand[neighbour] && component[neighbour] < 0) {
                        component[neighbour] = count
                        stack.addLast(neighbour)
                    }
                }
            }
            count++
        }
        return component
    }
}
