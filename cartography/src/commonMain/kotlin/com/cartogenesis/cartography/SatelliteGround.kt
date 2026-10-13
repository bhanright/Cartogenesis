package com.cartogenesis.cartography

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.GaussianBlur
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the ground of each land cell is made of, as a satellite would see it: how much living cover
 * stands on it and, where the cover is thin, which bare surface shows through.
 *
 * Every number here is read off a field the world already keeps, so the variety on the drawn map
 * is the world's own and not a noise laid over it:
 *
 *  - **Living cover** is the vegetation density, `ClimateResult.vegetationDensity`, mixed over the
 *    bare ground as a continuous share rather than a biome's step.
 *  - **Iron red** is old, stable ground in a dry climate: the crust's age (`PlateResult.crustAge`,
 *    1 on a craton no epoch deformed) times the drought. Red desert soils and sands are coated in
 *    iron oxides that take a long, quiet exposure to form, which is why Australia's centre and the
 *    Sahara's shield are red and the young basins beside them are not.
 *  - **Dark rock** is ground too steep to hold soil, and the active belts: the slope over a fixed
 *    ground baseline, and the present epoch's belts (crust age near 0) at altitude.
 *  - **Pale sand** is loose sediment on flat, dry ground, where the erosion stage put it down
 *    (`ErosionResult.height` above `PlateResult.height`) or where the prevailing wind carries it
 *    from uplands upwind that the erosion wore away.
 *  - **White salt** is the playa floor of a closed basin, `LakeResult.playa`, softened by a
 *    Gaussian a cell across so a pan is not drawn as a block of cells.
 *  - **Wind streaks** are the terrain's own small relief averaged along the prevailing wind
 *    upwind of each cell, so they run with the wind and bend where it bends.
 *  - **Green threads** follow the rivers that carry water through dry country, by their
 *    discharge (`RiverResult.flowAccumulation`).
 *  - **Snow and frost** are the share of the year a cell spends below freezing, from its warmest
 *    and coldest months, masked by a canopy that stands above the snow.
 *
 * The fields are worked out once on the processor and handed to both the processor's raster and
 * the graphics card as bytes, for the reason [ClimateTint]'s are: a drought or a wind trace solved
 * twice would be two slightly different deserts, while a byte is the same byte on both sides and
 * the colour arithmetic in [groundColor] then agrees exactly.
 */
internal object SatelliteGround {

    /**
     * The surface of every cell of [world], packed for upload and for [groundColor].
     *
     * Sea cells carry zeros: the raster draws the water there and never asks.
     */
    fun of(world: WorldMap): SatelliteSurface {
        val cellsAcross = world.width
        val cellsDown = world.height
        val cellCount = cellsAcross * cellsDown
        val config = world.config
        val land = world.sea.isLand
        val cellWidthKm = config.cellWidthKm
        val cellHeightKm = config.cellHeightKm
        val highestLandMetres = config.scale.highestLandMetres
        val reliefSpanMetres = config.scale.reliefSpanMetres
        val relative = world.sea.relativeElevation.data

        val altitudeMetres = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) {
            altitudeMetres.data[cell] = if (land[cell]) relative[cell] * highestLandMetres else 0f
        }
        val slopeDegrees = slopeDegrees(altitudeMetres, land, cellWidthKm, cellHeightKm)

        // Net change the erosion stage made to each cell, in metres: what it carried away from the
        // uplands and what it laid down in the valleys and basins.
        val erodedMetres = FloatField(cellsAcross, cellsDown)
        val depositedMetres = FloatArray(cellCount)
        val beforeErosion = world.plates.height.data
        val afterErosion = world.erosion.height.data
        for (cell in 0 until cellCount) {
            if (!land[cell]) continue
            val changeMetres = (afterErosion[cell] - beforeErosion[cell]) * reliefSpanMetres
            if (changeMetres > 0f) depositedMetres[cell] = changeMetres
            else erodedMetres.data[cell] = -changeMetres
        }

        val smallRelief = smallReliefInRegionalUnits(altitudeMetres, land, cellWidthKm, cellHeightKm)
        val traces = upwindTraces(world, smallRelief, erodedMetres, cellWidthKm, cellHeightKm)

        val salt = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) if (world.rivers.lakes.isPlaya(cell)) salt.data[cell] = 1f
        GaussianBlur.apply(
            salt, SALT_PAN_SOFTENING_KM / cellWidthKm, SALT_PAN_SOFTENING_KM / cellHeightKm
        )

        val riparian = FloatField(cellsAcross, cellsDown)
        val cellAreaSquareMetres = cellWidthKm * cellHeightKm * SQUARE_METRES_PER_SQUARE_KM
        for (cell in 0 until cellCount) {
            if (!land[cell]) continue
            val dischargeCubicKmPerYear = world.rivers.flowAccumulation.data[cell] /
                MILLIMETRES_PER_METRE * cellAreaSquareMetres / CUBIC_METRES_PER_CUBIC_KM
            if (dischargeCubicKmPerYear <= 0.0) continue
            riparian.data[cell] = smoothstep(
                THINNEST_GREEN_RIVER_LOG10_KM3, FULLY_GREEN_RIVER_LOG10_KM3,
                log10(dischargeCubicKmPerYear).toFloat()
            ) * RIVERBANK_COVER
        }
        GaussianBlur.apply(
            riparian, RIVERBANK_SOFTENING_KM / cellWidthKm, RIVERBANK_SOFTENING_KM / cellHeightKm
        )

        val streakScale = streakScaleOf(traces.streak, land, depositedMetres, traces.supplyMetres)

        val composition = IntArray(cellCount)
        val climate = IntArray(cellCount)
        val cover = FloatArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!land[cell]) continue
            val crustAge = world.plates.crustAge.data[cell]
            val drought = ClimateTint.droughtAt(world, cell)
            val slope = slopeDegrees[cell]
            val altitude = altitudeMetres.data[cell]

            val red = drought * smoothstep(OLDEST_UNREDDENED_CRUST_AGE, 1f, crustAge)

            val steep = smoothstep(BARE_ROCK_SLOPE_START_DEGREES, BARE_ROCK_SLOPE_FULL_DEGREES, slope)
            val activeBelt = (1f - smoothstep(0f, ACTIVE_BELT_CRUST_AGE, crustAge)) *
                smoothstep(ROCKY_BELT_START_METRES, ROCKY_BELT_FULL_METRES, altitude)
            val rock = max(steep, activeBelt)

            val flat = 1f - smoothstep(FLAT_GROUND_DEGREES, SAND_SHEDDING_DEGREES, slope)
            val looseMetres = depositedMetres[cell] + traces.supplyMetres[cell]
            val sand = drought * flat * smoothstep(0f, SAND_SEA_SEDIMENT_METRES, looseMetres)

            val streak = (traces.streak[cell] / streakScale).coerceIn(-1f, 1f)

            val frozen = frozenShareOfYear(
                world.climate.summerTemperature.data[cell], world.climate.winterTemperature.data[cell]
            )
            val density = ClimateTint.canopyAt(world, cell)
            val snow = if (world.climate.biome[cell] == Biome.ICE_SHEET) {
                1f
            } else {
                frozen * frozen * (1f - CANOPY_ABOVE_SNOW * density)
            }

            composition[cell] = pack(red, rock, sand, salt.data[cell])
            climate[cell] = pack(
                (streak + 1f) * HALF, riparian.data[cell], snow, drought
            )
            cover[cell] = density
        }
        return SatelliteSurface(composition, climate, cover)
    }

    /**
     * The colour of a land cell's ground from its packed [composition] and [climate] and its
     * living [cover], before the relief: the bare ground mixed from its surfaces, the wind's
     * streaks laid on the sand, the cover over that, and the snow over everything.
     *
     * Every step is a float lerp in one fixed order and the channel is truncated once at the end,
     * which is what lets the shader's copy (`GpuRaster.satelliteGround`) agree to the bit.
     */
    fun groundColor(composition: Int, climate: Int, cover: Float): Int {
        val red = byteAt(composition, 0) * UNIT_PER_BYTE
        val rock = byteAt(composition, 1) * UNIT_PER_BYTE
        val sand = byteAt(composition, 2) * UNIT_PER_BYTE
        val salt = byteAt(composition, 3) * UNIT_PER_BYTE
        val streak = (byteAt(climate, 0) - STREAK_ZERO_BYTE) * UNIT_PER_STREAK_BYTE
        val riverbank = byteAt(climate, 1) * UNIT_PER_BYTE
        val snow = byteAt(climate, 2) * UNIT_PER_BYTE
        val arid = byteAt(climate, 3) * UNIT_PER_BYTE
        val streakLight = 1f + STREAK_CONTRAST * streak * sand
        val shown = max(cover, riverbank)
        return argb(
            channel(16, red, rock, sand, salt, streakLight, shown, snow, arid),
            channel(8, red, rock, sand, salt, streakLight, shown, snow, arid),
            channel(0, red, rock, sand, salt, streakLight, shown, snow, arid)
        )
    }

    /** [groundColor] for one cell of a [SatelliteSurface]. */
    fun groundColor(surface: SatelliteSurface, cell: Int): Int =
        groundColor(surface.composition[cell], surface.climate[cell], surface.cover[cell])

    private fun channel(
        shift: Int,
        red: Float,
        rock: Float,
        sand: Float,
        salt: Float,
        streakLight: Float,
        shown: Float,
        snow: Float,
        arid: Float
    ): Int {
        fun of(colour: Int): Float = ((colour shr shift) and 0xFF).toFloat()
        val soil = of(HUMID_SOIL) + (of(ARID_SOIL) - of(HUMID_SOIL)) * arid
        var bare = soil + (of(IRON_RED) - soil) * red
        bare += (of(SAND) - bare) * sand
        bare += (of(ROCK) - bare) * rock
        bare += (of(SALT) - bare) * salt
        bare *= streakLight
        val green = of(SPARSE_GREEN) + (of(DENSE_GREEN) - of(SPARSE_GREEN)) * shown
        var ground = bare + (green - bare) * shown
        ground += (of(SNOW) - ground) * snow
        return floor(ground).toInt().coerceIn(0, 255)
    }

    /**
     * The palette in the order the shader reads it from its colour table: humid soil, arid soil,
     * iron red, sand, rock, salt, sparse green, dense green, snow.
     */
    val PALETTE: IntArray
        get() = intArrayOf(
            HUMID_SOIL, ARID_SOIL, IRON_RED, SAND, ROCK, SALT, SPARSE_GREEN, DENSE_GREEN, SNOW
        )

    /*
     * The colours, from true-colour satellite composites — NASA's Blue Marble and its Next
     * Generation monthly mosaics, where a region of the surface fills many pixels of one kind.
     * The five that `MapStyle.NATURAL` sampled pixel box by pixel box off a Blue Marble view of
     * North America are reused at its measured values and say so; the others are the composites'
     * typical values for regions the North American view does not show, not a fresh measurement.
     */

    /** Bare soil under a humid climate: the Great Basin umber Natural sampled, shaded to 0.8. */
    const val HUMID_SOIL: Int = 0xFF79653B.toInt()

    /** Bare soil under an arid one: the Chihuahua plateau ochre Natural sampled. */
    const val ARID_SOIL: Int = 0xFFAB8455.toInt()

    /**
     * Iron-oxide red: the red centre of Australia and the iron-stained ergs of the central
     * Sahara, typical of the composites. Redder than the Colorado Plateau's #C08F61, which is a
     * sandstone country seen through sparse juniper.
     */
    const val IRON_RED: Int = 0xFFB8683C.toInt()

    /** Pale buff and cream: the sand seas of the Sahara and Arabia, typical of the composites. */
    const val SAND: Int = 0xFFDEC496.toInt()

    /** Dark brown-grey rock: the Tibesti, the Hoggar and bare high ranges, typical. */
    const val ROCK: Int = 0xFF5B5146.toInt()

    /** Salt crust: the Salar de Uyuni and the Chott el Djerid, near white and a little warm. */
    const val SALT: Int = 0xFFE6E2D8.toInt()

    /** Thin living cover, a steppe or a dry grassland: the southern plains olive Natural sampled. */
    const val SPARSE_GREEN: Int = 0xFF797425.toInt()

    /** A closed canopy: the eastern woodland green Natural sampled. */
    const val DENSE_GREEN: Int = 0xFF2C6504.toInt()

    /** Snow: the Greenland interior Natural sampled, red lifted one to meet green. */
    const val SNOW: Int = 0xFFCECECA.toInt()

    /**
     * How far one full streak brightens or darkens the sand it lies on, as a share of the light.
     *
     * A tenth either way: linear dunes and the bright and dark wind streaks of the Sahara and the
     * Ordos change the composites' brightness by about that much against the sand around them, and
     * any stronger reads as a ruled texture rather than as ground.
     */
    const val STREAK_CONTRAST: Float = 0.10f

    /** The byte a streak of zero is packed as: the middle of a byte, so both signs fit. */
    const val STREAK_ZERO_BYTE: Int = 128

    /** One unit of a 0..1 share per byte step, multiplied rather than divided on both sides. */
    const val UNIT_PER_BYTE: Float = 1f / 255f

    /** One unit of a streak per byte step either side of [STREAK_ZERO_BYTE]. */
    const val UNIT_PER_STREAK_BYTE: Float = 1f / 127f

    /**
     * The crust age below which ground has not reddened at all, on `PlateResult.crustAge`'s scale
     * of 0 for the present epoch's belts to 1 for a craton.
     *
     * A half, so that the redness grows over the older half of the history and is full only on
     * ground no epoch touched: rubification takes from a hundred thousand to a million years of
     * stable exposure, and only the shields carry red soils over whole regions.
     */
    private const val OLDEST_UNREDDENED_CRUST_AGE = 0.5f

    /**
     * Where bare rock begins and where it is all there is, as the slope over
     * [SLOPE_BASELINE_KM] of ground, in degrees.
     *
     * Over a baseline of about twelve kilometres the Great Plains slope well under a degree, worn
     * uplands like the Appalachians three to six, and the Alps and the Himalaya twelve to twenty;
     * soil holds on the first two and the last is mostly rock and scree in the composites.
     */
    private const val BARE_ROCK_SLOPE_START_DEGREES = 5f
    private const val BARE_ROCK_SLOPE_FULL_DEGREES = 16f

    /**
     * The ground length the slope is read over, in kilometres, so it is the same slope on every
     * grid; the central difference spans this, rounded to whole cells and at least one each way.
     */
    private const val SLOPE_BASELINE_KM = 12.0

    /**
     * The crust age under which ground is the present epoch's own belt, rough and unweathered:
     * the first tenth of `PlateResult.crustAge`'s scale, which is the youngest band of a history
     * of ten epochs.
     */
    private const val ACTIVE_BELT_CRUST_AGE = 0.1f

    /** The altitudes over which an active belt goes from soil to rock: its foothills to its range. */
    private const val ROCKY_BELT_START_METRES = 1_000f
    private const val ROCKY_BELT_FULL_METRES = 3_000f

    /**
     * The slopes over which loose sand stops lying: below the first a sand sea, above the second
     * the wind strips it. Sand seas on Earth fill basins and plains of under a degree over a
     * baseline of tens of kilometres; by three degrees the ground is reg, hamada or upland.
     */
    private const val FLAT_GROUND_DEGREES = 1f
    private const val SAND_SHEDDING_DEGREES = 3f

    /**
     * How much loose sediment, in metres, makes a sand sea: laid down on the cell or carried to it
     * on the wind. The great ergs stand tens to a hundred metres deep; ten metres is a sheet of
     * sand covering the ground beneath.
     */
    private const val SAND_SEA_SEDIMENT_METRES = 10f

    /**
     * How far upwind the wind's sand comes from, in kilometres: the e-folding length of the upwind
     * trace. The Saharan sand seas sit a few hundred kilometres downwind of the massifs and wadis
     * that feed them.
     */
    private const val SAND_SUPPLY_REACH_KM = 250.0

    /**
     * How long a wind streak runs, in kilometres: the e-folding length of the upwind average.
     * Linear dunes and wind streaks in the Sahara and the Ordos run tens of kilometres.
     */
    private const val STREAK_LENGTH_KM = 40.0

    /**
     * The spread of the relief a streak is drawn from, in kilometres: the terrain less its own
     * Gaussian average at this spread, which keeps the hills and hollows a few cells across and
     * drops the regional slope that would otherwise shade a whole basin one way.
     */
    private const val STREAK_SOURCE_SPREAD_KM = 20.0

    /**
     * The spread over which the small relief is measured against its own size, in kilometres, so
     * a streak on a plain is as plain to see as one among hills: the sand does not know how high
     * the hill upwind of it was, only that it was there.
     */
    private const val STREAK_SOURCE_REGION_KM = 150.0

    /** The quietest small relief that counts, in metres, so a dead-flat plain is not amplified. */
    private const val QUIETEST_RELIEF_METRES = 2f

    /** How many of the trace's e-folding lengths it walks before the rest weighs nothing. */
    private const val TRACE_LENGTHS = 3.0

    /**
     * A streak's full scale, in standard deviations of the streak field over the sand: the
     * strongest streaks are two standard deviations out and everything past them is clamped.
     */
    private const val STREAK_FULL_SCALE_SIGMAS = 2f

    /** How far a playa's crust is softened, in kilometres: about a cell on the application's grid. */
    private const val SALT_PAN_SOFTENING_KM = 4.0

    /**
     * The discharges at which a river begins to green its banks and is fully green, as the
     * base-ten logarithm of cubic kilometres a year: a tenth of a cubic kilometre, a perennial
     * desert river, to thirty, the Nile and the Indus whose valleys are a ribbon of green through
     * the composites.
     */
    private const val THINNEST_GREEN_RIVER_LOG10_KM3 = -1f
    private const val FULLY_GREEN_RIVER_LOG10_KM3 = 1.5f

    /** The cover a fully green river gives its banks: the irrigated Nile valley's, nearly closed. */
    private const val RIVERBANK_COVER = 0.8f

    /** How far a river's green spreads from its course, in kilometres: half a cell or so. */
    private const val RIVERBANK_SOFTENING_KM = 3.0

    /**
     * How much of the snow a closed canopy hides. Boreal forest in winter is dark in the
     * composites, the snow on the ground beneath and the crowns standing above it: about two
     * fifths of the white comes through a closed stand.
     */
    private const val CANOPY_ABOVE_SNOW = 0.6f

    private const val HALF = 0.5f
    private const val SQUARE_METRES_PER_SQUARE_KM = 1e6
    private const val CUBIC_METRES_PER_CUBIC_KM = 1e9
    private const val MILLIMETRES_PER_METRE = 1_000.0
    private const val METRES_PER_KM = 1_000.0
    private const val DEGREES_PER_RADIAN = 180.0 / PI

    /**
     * The share of the year a cell spends below freezing, 0 to 1, from its warmest and coldest
     * months: the year taken as a sinusoid between the two, and the share of it under 0 C.
     */
    fun frozenShareOfYear(warmestMonthC: Float, coldestMonthC: Float): Float {
        if (warmestMonthC <= 0f) return 1f
        if (coldestMonthC >= 0f) return 0f
        val meanC = (warmestMonthC + coldestMonthC) / 2f
        val swingC = (warmestMonthC - coldestMonthC) / 2f
        // The year as mean - swing * cos(phase), the phase running a half-turn from the coldest
        // month to the warmest: it stays below freezing until cos(phase) falls to mean / swing.
        return (acos((meanC / swingC).toDouble().coerceIn(-1.0, 1.0)) / PI).toFloat()
    }

    /** The slope of the land in degrees over [SLOPE_BASELINE_KM]; 0 at sea. */
    private fun slopeDegrees(
        altitudeMetres: FloatField,
        land: BooleanArray,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): FloatArray {
        val reachColumns = (SLOPE_BASELINE_KM / 2.0 / cellWidthKm).roundToInt().coerceAtLeast(1)
        val reachRows = (SLOPE_BASELINE_KM / 2.0 / cellHeightKm).roundToInt().coerceAtLeast(1)
        val acrossMetres = 2.0 * reachColumns * cellWidthKm * METRES_PER_KM
        val downMetres = 2.0 * reachRows * cellHeightKm * METRES_PER_KM
        val cellsAcross = altitudeMetres.width
        val slope = FloatArray(land.size)
        parallelChunks(0, land.size) { from, to ->
            for (cell in from until to) {
                if (!land[cell]) continue
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                val eastward = (altitudeMetres.sample(column + reachColumns, row) -
                    altitudeMetres.sample(column - reachColumns, row)) / acrossMetres
                val southward = (altitudeMetres.sample(column, row + reachRows) -
                    altitudeMetres.sample(column, row - reachRows)) / downMetres
                slope[cell] = (atan(sqrt(eastward * eastward + southward * southward)) *
                    DEGREES_PER_RADIAN).toFloat()
            }
        }
        return slope
    }

    /**
     * The land's small relief — its altitude less a Gaussian average of it — over the size small
     * relief has in its own region, so the streaks a plain draws read as plainly as a hill
     * country's. 0 at sea.
     */
    private fun smallReliefInRegionalUnits(
        altitudeMetres: FloatField,
        land: BooleanArray,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): FloatField {
        val average = altitudeMetres.copy()
        GaussianBlur.apply(
            average, STREAK_SOURCE_SPREAD_KM / cellWidthKm, STREAK_SOURCE_SPREAD_KM / cellHeightKm
        )
        val small = FloatField(altitudeMetres.width, altitudeMetres.height)
        val power = FloatField(altitudeMetres.width, altitudeMetres.height)
        for (cell in land.indices) {
            if (!land[cell]) continue
            val residual = altitudeMetres.data[cell] - average.data[cell]
            small.data[cell] = residual
            power.data[cell] = residual * residual
        }
        GaussianBlur.apply(
            power, STREAK_SOURCE_REGION_KM / cellWidthKm, STREAK_SOURCE_REGION_KM / cellHeightKm
        )
        for (cell in land.indices) {
            if (!land[cell]) continue
            val regional = max(sqrt(power.data[cell]), QUIETEST_RELIEF_METRES)
            small.data[cell] = small.data[cell] / regional
        }
        return small
    }

    /** The two upwind averages, per cell: the streaks and the sand the wind brings. */
    private class Traces(val streak: FloatArray, val supplyMetres: FloatArray)

    /**
     * Walks upwind from every land cell along the prevailing wind, a cell's width at a time, and
     * averages two fields along the way with an exponential weight: the small relief over
     * [STREAK_LENGTH_KM], which is the streak, and the depth the erosion stage removed over
     * [SAND_SUPPLY_REACH_KM], which is the sand the wind delivers. A walk stops at the sea, which
     * brings no sand and has no relief to streak.
     *
     * The wind is `ClimateResult.windDirection` east-west and `windMeridional` rows a column,
     * carried onto the ground by the cells' own lengths, so the walk follows the wind's bearing on
     * the ground at every step and turns where it turns. Fields are read between cells
     * bilinearly, so no step lands on the grid's rows.
     */
    private fun upwindTraces(
        world: WorldMap,
        smallRelief: FloatField,
        erodedMetres: FloatField,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): Traces {
        val cellsAcross = world.width
        val cellsDown = world.height
        val land = world.sea.isLand
        val cellCount = cellsAcross * cellsDown
        val streak = FloatArray(cellCount)
        val supply = FloatArray(cellCount)
        val stepKm = cellWidthKm
        val streakSteps = (TRACE_LENGTHS * STREAK_LENGTH_KM / stepKm).toInt().coerceAtLeast(1)
        val supplySteps = (TRACE_LENGTHS * SAND_SUPPLY_REACH_KM / stepKm).toInt().coerceAtLeast(1)
        val steps = max(streakSteps, supplySteps)
        val streakDecay = exp(-stepKm / STREAK_LENGTH_KM).toFloat()
        val supplyDecay = exp(-stepKm / SAND_SUPPLY_REACH_KM).toFloat()
        val eastward = world.climate.windDirection
        val southward = world.climate.windMeridional.data

        parallelChunks(0, cellCount) { from, to ->
            for (cell in from until to) {
                if (!land[cell]) continue
                var x = (cell % cellsAcross).toDouble()
                var y = (cell / cellsAcross).toDouble()
                var streakSum = 0f
                var streakWeights = 0f
                var supplySum = 0f
                var supplyWeights = 0f
                var streakWeight = 1f
                var supplyWeight = 1f
                for (step in 0..steps) {
                    val here = nearestCell(x, y, cellsAcross, cellsDown)
                    if (!land[here]) break
                    if (step <= streakSteps) {
                        streakSum += streakWeight * bilinear(smallRelief, x, y)
                        streakWeights += streakWeight
                        streakWeight *= streakDecay
                    }
                    supplySum += supplyWeight * bilinear(erodedMetres, x, y)
                    supplyWeights += supplyWeight
                    supplyWeight *= supplyDecay
                    // The wind's bearing on the ground, then back into cells: one column is a
                    // cell's width east, one row a cell's height south.
                    val eastKm = eastward[here] * cellWidthKm
                    val southKm = southward[here] * cellHeightKm
                    val lengthKm = sqrt(eastKm * eastKm + southKm * southKm)
                    if (lengthKm <= 0.0) break
                    x -= eastKm / lengthKm * stepKm / cellWidthKm
                    y -= southKm / lengthKm * stepKm / cellHeightKm
                    if (y < 0.0 || y > cellsDown - 1.0) break
                }
                streak[cell] = if (streakWeights > 0f) streakSum / streakWeights else 0f
                supply[cell] = if (supplyWeights > 0f) supplySum / supplyWeights else 0f
            }
        }
        return Traces(streak, supply)
    }

    /**
     * The streak field's spread over the cells that could be sand, times
     * [STREAK_FULL_SCALE_SIGMAS]: the value a full streak is packed at. One per world, so the
     * strongest streaks are the strongest this world's wind draws.
     */
    private fun streakScaleOf(
        streak: FloatArray,
        land: BooleanArray,
        depositedMetres: FloatArray,
        supplyMetres: FloatArray
    ): Float {
        var sumOfSquares = 0.0
        var count = 0
        for (cell in streak.indices) {
            if (!land[cell] || depositedMetres[cell] + supplyMetres[cell] <= 0f) continue
            sumOfSquares += streak[cell].toDouble() * streak[cell]
            count++
        }
        if (count == 0) return 1f
        return max(sqrt(sumOfSquares / count).toFloat() * STREAK_FULL_SCALE_SIGMAS, 1e-6f)
    }

    private fun nearestCell(x: Double, y: Double, cellsAcross: Int, cellsDown: Int): Int {
        var column = floor(x + HALF).toInt() % cellsAcross
        if (column < 0) column += cellsAcross
        val row = floor(y + HALF).toInt().coerceIn(0, cellsDown - 1)
        return row * cellsAcross + column
    }

    /** [field] between cell centres, wrapping east-west and clamped at the poles. */
    private fun bilinear(field: FloatField, x: Double, y: Double): Float {
        val left = floor(x).toInt()
        val top = floor(y).toInt()
        val across = (x - left).toFloat()
        val down = (y - top).toFloat()
        val topRow = field.sample(left, top) + (field.sample(left + 1, top) - field.sample(left, top)) * across
        val bottomRow = field.sample(left, top + 1) +
            (field.sample(left + 1, top + 1) - field.sample(left, top + 1)) * across
        return topRow + (bottomRow - topRow) * down
    }

    private fun pack(first: Float, second: Float, third: Float, fourth: Float): Int =
        toByte(first) or (toByte(second) shl 8) or (toByte(third) shl 16) or (toByte(fourth) shl 24)

    private fun toByte(share: Float): Int = (share.coerceIn(0f, 1f) * 255f).roundToInt()

    private fun byteAt(packed: Int, index: Int): Int = (packed ushr (index * 8)) and 0xFF

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float =
        Engraving.smoothstep(edge0, edge1, value)

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

/**
 * The satellite ground of every cell, packed as [SatelliteGround.of] makes it.
 *
 * [composition] holds four bytes a cell, low first: iron red, rock, sand and salt, each a share of
 * the bare ground. [climate] holds the streak (128 is none), the riverbank green, the snow and the
 * drought. [cover] is the living cover, 0 to 1. All three are row-major, a cell each.
 */
class SatelliteSurface internal constructor(
    val composition: IntArray,
    val climate: IntArray,
    val cover: FloatArray
)
