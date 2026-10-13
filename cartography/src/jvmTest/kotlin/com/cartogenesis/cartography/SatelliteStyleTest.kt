package com.cartogenesis.cartography

import com.cartogenesis.cartography.geometry.Contours
import com.cartogenesis.cartography.geometry.Detector
import com.cartogenesis.cartography.geometry.GeometryGuard
import com.cartogenesis.cartography.geometry.GridFrame
import com.cartogenesis.cartography.geometry.Judge
import com.cartogenesis.cartography.geometry.Layer
import com.cartogenesis.cartography.geometry.LayerReading
import com.cartogenesis.cartography.geometry.MapLayers
import com.cartogenesis.cartography.geometry.Outcome
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.math.GaussianBlur
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The satellite style's guards: its colour answers to the fields it claims to be drawn from, the
 * same world draws the same picture, and none of the layers it adds to the map carries the grid's
 * geometry (conventions rule 13).
 *
 * The graphics card's copy of the colour is held to the processor's by `GpuRasterTest` in
 * `:desktop`, which draws every style in every view and so takes this one with the rest.
 *
 * Measured on seed 969495 at 512 rows ([WORLD]), and the colour's two responses are asked as
 * counterfactuals of it: the same world with older crust, and with more cover, so nothing but the
 * one field differs between the two pictures compared.
 */
class SatelliteStyleTest {

    private companion object {
        /**
         * Seed 969495 at 512 rows of square cells on the default planet: a world with deserts
         * large enough that their streaks fill the census's blocks, where the gallery's world has
         * too little dry ground to measure.
         */
        val WORLD: WorldGenConfig = WorldGenConfig.forRows(seed = 969495L, rows = 512)

        /** A cell is desert for these clauses where its drought is over this and its cover under. */
        const val DESERT_DROUGHT = 0.5f
        const val DESERT_COVER = 0.2f

        /** The fewest desert cells a clause is believed on. */
        const val FEWEST_DESERT_CELLS = 500

        /** The per-merge census's judge: four worlds, the levels `GeometryGuardTest` reads at. */
        val JUDGE = Judge.forCensus(4)

        /**
         * The detectors the satellite's layers are held to: every one that asks about the grid's
         * bearings, its lattice or its corners. Arcs and facets are asked of drawn outlines and
         * partitions; a smooth field's level set is held to them by the census already.
         */
        val HELD = listOf(
            Detector.ISOTROPY, Detector.ALIGNED_SIDE, Detector.RECTANGLE, Detector.COMBS,
            Detector.RIGHT_ANGLES, Detector.ORIENTATION
        )

        /** A green less red can move by a level of each channel's truncation, two in all. */
        const val TRUNCATION_LEVELS = 2

        /** The grid's bearings on the ground, as axes: east-west, the two diagonals, north-south. */
        val GRID_BEARINGS = listOf(0.0, 45.0, 90.0, 135.0)

        /**
         * How far off every grid bearing the wind must lie for the along-the-grid clause to ask
         * about a cell, in degrees: nearer than this, along the wind and along the grid are nearly
         * one direction and the clause measures nothing.
         */
        const val GRID_CLEAR_DEGREES = 10.0

        /** The control wind's bearing, east of the east-west axis and clear of the grid's. */
        const val OFF_GRID_WIND_DEGREES = 20.0

        /** Where a streak's level lines are traced: a streak this far from none, either way. */
        const val STREAK_LEVEL = 0.5f
    }

    private val world: WorldMap by lazy { SharedWorlds.world(WORLD) }

    private fun redness(colour: Int): Int = ((colour shr 16) and 0xFF) - (colour and 0xFF)
    private fun greenness(colour: Int): Int = ((colour shr 8) and 0xFF) - ((colour shr 16) and 0xFF)
    private fun byteOf(packed: Int, index: Int): Float = ((packed ushr (index * 8)) and 0xFF) / 255f

    private fun desertCells(surface: SatelliteSurface): List<Int> = world.sea.isLand.indices.filter { cell ->
        world.sea.isLand[cell] && byteOf(surface.climate[cell], 3) > DESERT_DROUGHT &&
            surface.cover[cell] < DESERT_COVER
    }

    @Test
    fun `a desert on old crust is redder than the same desert on young crust`() {
        fun withCrustAge(age: Float): WorldMap {
            val field = FloatField(world.width, world.height)
            field.data.fill(age)
            return world.copy(plates = world.plates.copy(crustAge = field))
        }
        val old = SatelliteGround.of(withCrustAge(1f))
        val young = SatelliteGround.of(withCrustAge(0.2f))
        val deserts = desertCells(old)
        assertTrue(deserts.size >= FEWEST_DESERT_CELLS, "only ${deserts.size} desert cells to measure")
        val oldRed = deserts.map { redness(SatelliteGround.groundColor(old, it)) }.average()
        val youngRed = deserts.map { redness(SatelliteGround.groundColor(young, it)) }.average()
        val redderEverywhere = deserts.count {
            redness(SatelliteGround.groundColor(old, it)) >= redness(SatelliteGround.groundColor(young, it))
        }
        println("SATELLITE red less blue over ${deserts.size} desert cells: craton %.1f, young crust %.1f".format(oldRed, youngRed))
        assertTrue(oldRed > youngRed + 10, "old crust %.1f against young %.1f".format(oldRed, youngRed))
        assertTrue(redderEverywhere == deserts.size, "${deserts.size - redderEverywhere} cells less red on old crust")
    }

    @Test
    fun `more living cover is greener ground`() {
        val more = FloatField(world.width, world.height)
        val density = world.climate.vegetationDensity.data
        for (cell in density.indices) more.data[cell] = (density[cell] + 0.3f).coerceAtMost(1f)
        val greener = world.copy(climate = world.climate.copy(vegetationDensity = more))
        val before = SatelliteGround.of(world)
        val after = SatelliteGround.of(greener)
        // Snow lies over the cover and an ice sheet is white whatever grows, so the clause is asked
        // where the ground shows.
        val ground = world.sea.isLand.indices.filter {
            world.sea.isLand[it] && byteOf(before.climate[it], 2) < 0.05f && density[it] < 0.7f
        }
        assertTrue(ground.size >= FEWEST_DESERT_CELLS, "only ${ground.size} cells to measure")
        val beforeGreen = ground.map { greenness(SatelliteGround.groundColor(before, it)) }.average()
        val afterGreen = ground.map { greenness(SatelliteGround.groundColor(after, it)) }.average()
        // Less green by more than the two channels' truncations, a level each, can account for.
        val notGreener = ground.count {
            greenness(SatelliteGround.groundColor(after, it)) <
                greenness(SatelliteGround.groundColor(before, it)) - TRUNCATION_LEVELS
        }
        println("SATELLITE green less red over ${ground.size} cells: %.1f, with more cover %.1f".format(beforeGreen, afterGreen))
        assertTrue(afterGreen > beforeGreen + 10, "more cover %.1f against %.1f".format(afterGreen, beforeGreen))
        assertTrue(notGreener == 0, "$notGreener cells less green under more cover")
    }

    @Test
    fun `the same world draws the same picture`() {
        val first = MapRasterizer.rasterize(world, RenderOptions(style = MapStyle.SATELLITE))
        val second = MapRasterizer.rasterize(world, RenderOptions(style = MapStyle.SATELLITE))
        assertContentEquals(first, second)
        val surface = SatelliteGround.of(world)
        val again = SatelliteGround.of(world)
        assertContentEquals(surface.composition, again.composition)
        assertContentEquals(surface.climate, again.climate)
        assertContentEquals(surface.terrain, again.terrain)
        assertContentEquals(surface.cover, again.cover)
    }

    @Test
    fun `the satellite's layers carry none of the grid's geometry`() {
        val readings = layersOf(world, SatelliteGround.of(world))
        val failures = ArrayList<String>()
        readings.forEach { reading ->
            println("SATELLITE census ${reading.layer}: " + HELD.joinToString("; ") { "${it.name} ${reading.outcome(it)}" })
            HELD.filter { reading.outcome(it) == Outcome.VIOLATION }.forEach {
                failures.add("${reading.layer} ${it.label}: ${reading.describe(it)}")
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        val streaks = readings.first { it.layer == "satellite streaks" }
        assertTrue(
            streaks.outcome(Detector.ALIGNED_SIDE) != Outcome.INSUFFICIENT,
            "the streaks are too few to measure: ${streaks.describe(Detector.ALIGNED_SIDE)}"
        )
    }

    /**
     * The streaks' bearing, which the census's own isotropy test cannot read on one world's
     * deserts (it asks for 456 blocks of chords and a desert's streaks fill fewer than a hundred).
     *
     * Read as how fast the streak field changes along a bearing. A streak is long along the wind
     * and narrow across it, so the field changes more slowly along the wind than across it; and
     * for each of the grid's four bearings, wherever the wind lies off that bearing, it changes
     * more slowly along the wind than along the grid. A style that laid its streaks along a row or
     * a diagonal would change slowest along that bearing instead, wherever the wind is not there.
     */
    @Test
    fun `the streaks follow the wind and not the grid`() {
        val reading = streakGrain(world, SatelliteGround.of(world))
        println("SATELLITE streak grain: $reading")
        assertTrue(reading.cells >= FEWEST_DESERT_CELLS, "only ${reading.cells} dry cells to measure")
        assertTrue(reading.alongOverAcross < 1.0, "the streaks have no grain along the wind: $reading")
        assertTrue(reading.worstGridRatio < 1.0, "the streaks lie along a grid bearing rather than the wind: $reading")
    }

    /**
     * The controls: the same style with its streaks laid along the rows — the world's wind left as
     * it is and the streaks traced along an east-west one — which the clause above must catch, or
     * its passing says nothing; and streaks traced along a uniform wind at a bearing off the
     * grid's, which it must pass.
     */
    @Test
    fun `the streak census catches streaks laid along the rows`() {
        val eastWest = FloatField(world.width, world.height)
        eastWest.data.fill(1f)
        val alongTheRows = world.copy(
            climate = world.climate.copy(windEastwardMps = eastWest, windSouthwardMps = FloatField(world.width, world.height))
        )
        val rows = streakGrain(world, SatelliteGround.of(alongTheRows))
        println("SATELLITE control, streaks along the rows: $rows")
        assertTrue(rows.worstGridRatio >= 1.0, "row-aligned streaks passed: $rows")

        val east = FloatField(world.width, world.height)
        val south = FloatField(world.width, world.height)
        east.data.fill(kotlin.math.cos(Math.toRadians(OFF_GRID_WIND_DEGREES)).toFloat())
        south.data.fill(kotlin.math.sin(Math.toRadians(OFF_GRID_WIND_DEGREES)).toFloat())
        val turned = world.copy(climate = world.climate.copy(windEastwardMps = east, windSouthwardMps = south))
        val offGrid = streakGrain(turned, SatelliteGround.of(turned))
        println("SATELLITE control, a wind at $OFF_GRID_WIND_DEGREES deg: $offGrid")
        assertTrue(offGrid.alongOverAcross < 1.0 && offGrid.worstGridRatio < 1.0, "streaks along an off-grid wind failed: $offGrid")
    }

    private class StreakGrain(
        val cells: Int,
        val alongOverAcross: Double,
        /** Change along the wind over change along each grid bearing, east-west first. */
        val gridRatios: DoubleArray
    ) {
        val worstGridRatio: Double get() = gridRatios.max()
        override fun toString(): String =
            "%d dry cells, change along the wind over across it %.3f, over along the grid's bearings %s"
                .format(cells, alongOverAcross, GRID_BEARINGS.indices.joinToString(" ") {
                    "%.0f deg %.3f".format(GRID_BEARINGS[it], gridRatios[it])
                })
    }

    /**
     * The mean square of the streak field's change, a cell's width either way, along the wind of
     * [world], across it, and along each grid bearing, over the dry land; each grid bearing is
     * asked only where the wind lies at least [GRID_CLEAR_DEGREES] off it, where the two are
     * different directions.
     */
    private fun streakGrain(world: WorldMap, surface: SatelliteSurface): StreakGrain {
        val across = world.width
        val rowScale = world.config.cellHeightInCellWidths
        val streak = FloatField(across, world.height)
        for (cell in streak.data.indices) {
            streak.data[cell] = ((surface.climate[cell] and 0xFF) - SatelliteGround.STREAK_ZERO_BYTE).toFloat()
        }
        fun change(column: Int, row: Int, bearingDegrees: Double): Double {
            val east = kotlin.math.cos(Math.toRadians(bearingDegrees))
            val south = kotlin.math.sin(Math.toRadians(bearingDegrees)) / rowScale
            val ahead = bilinear(streak, column + east, row + south)
            val behind = bilinear(streak, column - east, row - south)
            return (ahead - behind) / 2.0
        }
        var along = 0.0
        var acrossTheWind = 0.0
        val alongWhereClear = DoubleArray(GRID_BEARINGS.size)
        val alongGrid = DoubleArray(GRID_BEARINGS.size)
        var cells = 0
        val land = world.sea.isLand
        for (cell in land.indices) {
            val row = cell / across
            if (!land[cell] || row < 2 || row > world.height - 3) continue
            if (byteOf(surface.climate[cell], 3) <= DESERT_DROUGHT) continue
            val column = cell % across
            val wind = Math.toDegrees(atan2(
                world.climate.windSouthwardMps.data[cell].toDouble(), world.climate.windEastwardMps.data[cell].toDouble()
            ))
            val alongHere = change(column, row, wind)
            along += alongHere * alongHere
            val acrossHere = change(column, row, wind + 90.0)
            acrossTheWind += acrossHere * acrossHere
            cells++
            GRID_BEARINGS.forEachIndexed { index, bearing ->
                if (axialDifference(wind, bearing) >= GRID_CLEAR_DEGREES) {
                    val gridHere = change(column, row, bearing)
                    alongWhereClear[index] += alongHere * alongHere
                    alongGrid[index] += gridHere * gridHere
                }
            }
        }
        return StreakGrain(
            cells,
            along / acrossTheWind.coerceAtLeast(1e-9),
            DoubleArray(GRID_BEARINGS.size) { alongWhereClear[it] / alongGrid[it].coerceAtLeast(1e-9) }
        )
    }

    private fun bilinear(field: FloatField, x: Double, y: Double): Double {
        val left = kotlin.math.floor(x).toInt()
        val top = kotlin.math.floor(y).toInt()
        val across = x - left
        val down = y - top
        val upper = field.sample(left, top) + (field.sample(left + 1, top) - field.sample(left, top)) * across
        val lower = field.sample(left, top + 1) + (field.sample(left + 1, top + 1) - field.sample(left, top + 1)) * across
        return upper + (lower - upper) * down
    }

    /** The smaller angle between two bearings taken as axes, 0 to 90 degrees. */
    private fun axialDifference(first: Double, second: Double): Double {
        val difference = ((abs(first - second) % 180.0) + 180.0) % 180.0
        return minOf(difference, 180.0 - difference)
    }


    /**
     * The layers the satellite style adds, as the census reads them: the level sets of its four
     * bare surfaces and of the rivers' green, each a smooth field's, and the streaks' level lines
     * over all the dry ground, which is wider than the sand they are drawn on and so the stricter.
     */
    private fun layersOf(world: WorldMap, surface: SatelliteSurface): List<LayerReading> {
        val frame = GridFrame.of(world.config)
        val land = world.sea.isLand
        fun share(packed: IntArray, index: Int) = FloatArray(land.size) { if (land[it]) byteOf(packed[it], index) else 0f }
        fun level(name: String, field: FloatArray, at: Float) =
            Layer(name, Contours.ofField(field, at, frame, land), smoothField = true)
        val sand = share(surface.composition, 2)
        val dry = BooleanArray(land.size) { land[it] && byteOf(surface.climate[it], 3) > DESERT_DROUGHT }
        val streak = FloatArray(land.size) {
            (((surface.climate[it] and 0xFF) - SatelliteGround.STREAK_ZERO_BYTE) * SatelliteGround.UNIT_PER_STREAK_BYTE)
        }
        val layers = listOf(
            level("satellite iron red", share(surface.composition, 0), 0.25f),
            level("satellite rock", share(surface.composition, 1), 0.5f),
            level("satellite sand", sand, 0.5f),
            level("satellite salt", share(surface.composition, 3), 0.5f),
            level("satellite riverbank green", share(surface.climate, 1), 0.4f),
            Layer("satellite streaks", MapLayers.levelLines(streak, floatArrayOf(-STREAK_LEVEL, STREAK_LEVEL), frame, dry))
        )
        return layers.map { GeometryGuard.read(it, frame, JUDGE) }
    }
}
