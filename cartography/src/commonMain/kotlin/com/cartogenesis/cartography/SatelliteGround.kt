package com.cartogenesis.cartography

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.GaussianBlur
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
 *  - **Weathering** follows the crust's age (`PlateResult.crustAge`, 1 on a craton no epoch
 *    deformed) on dry ground: young ground pale cream, middle-aged ground pinkish buff, and only
 *    the oldest sand iron red. Desert sands redden as an iron-oxide coat grows on grains a
 *    stable dune holds still, so the red is a patch where old crust and a sand sea meet rather
 *    than the color of every shield.
 *  - **Dark stone** (hamada) is dry ground that sheds its sand: a high plateau or rough hill
 *    country, short of the slopes that are all rock.
 *  - **Dark rock** is ground too steep to hold soil, the active belts (the present epoch's,
 *    crust age near 0, at altitude), dry slopes, and the alpine slopes between the tree line and
 *    the snow, where the warmest month is too cold for the cover to close; there the rock breaks
 *    through the cover as well as coloring the ground beneath it.
 *  - **Pale sand** is loose sediment on flat, dry ground: where the erosion stage lowered the
 *    ground less than its region (`PlateResult.height` less `ErosionResult.height`) or the ground
 *    stands in a basin, and where the prevailing wind carries it from uplands upwind that the
 *    erosion wore down more than their region.
 *  - **Pale washes** are the wadi floors, by the ground each drains, and the alluvial fans at the
 *    foot of rough ground.
 *  - **White salt** is the playa floor of a closed basin, `LakeResult.playa`, softened by a
 *    Gaussian a cell across so a pan is not drawn as a block of cells.
 *  - **Wind streaks** are the terrain's own small relief averaged along the prevailing wind
 *    upwind of each cell, so they run with the wind and bend where it bends. They move the sand's
 *    share and the light over all the dry ground.
 *  - **Green corridors** follow the rivers through dry country, as wide as their discharge
 *    makes their valley floors (`RiverResult.flowAccumulation`) and no higher than those floors.
 *  - **Snow and frost** are the share of the year a cell spends below freezing, from its warmest
 *    and coldest months, masked by a canopy that stands above the snow.
 *
 * The fields are worked out once on the processor and handed to both the processor's raster and
 * the graphics card as bytes, for the reason [ClimateTint]'s are: a drought or a wind trace solved
 * twice would be two slightly different deserts, while a byte is the same byte on both sides and
 * the color arithmetic in [groundColor] then agrees exactly.
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

        val smallRelief = smallReliefInRegionalUnits(altitudeMetres, land, cellWidthKm, cellHeightKm)
        val sediment = sedimentOf(world, altitudeMetres)
        val traces = upwindTraces(world, smallRelief, sediment.shedMetres, cellWidthKm, cellHeightKm)
        val looseMetres = FloatArray(cellCount) { cell ->
            if (land[cell]) sediment.keptMetres[cell] + traces.supplyMetres[cell] else 0f
        }

        // Where sand can lie, spread as the sediment is: a sand sea blankets the low swells and
        // valley sides of the ground beneath it, so it is the region's slope that sheds it and not
        // each valley wall's.
        val flatEnough = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) {
            if (!land[cell]) continue
            flatEnough.data[cell] =
                1f - smoothstep(FLAT_GROUND_DEGREES, SAND_SHEDDING_DEGREES, slopeDegrees[cell])
        }
        val flatness = landAverage(
            flatEnough, land, SAND_SEA_SPREAD_KM / cellWidthKm, SAND_SEA_SPREAD_KM / cellHeightKm
        )

        // How far the dry ground has weathered toward red, by the crust's age and softened by a
        // Gaussian so the hues fade over a few tens of kilometers rather than stopping at the line
        // where the age jumps from one epoch's band to the next. Two stages of one process: a
        // middle-aged surface has gone pinkish buff, and only the oldest has gone iron red.
        val crustAge = world.plates.crustAge.copy()
        GaussianBlur.apply(
            crustAge, IRON_STAIN_SOFTENING_KM / cellWidthKm, IRON_STAIN_SOFTENING_KM / cellHeightKm
        )

        val salt = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) if (world.rivers.lakes.isPlaya(cell)) salt.data[cell] = 1f
        GaussianBlur.apply(
            salt, SALT_PAN_SOFTENING_KM / cellWidthKm, SALT_PAN_SOFTENING_KM / cellHeightKm
        )

        val drought = FloatArray(cellCount) { cell -> if (land[cell]) ClimateTint.droughtAt(world, cell) else 0f }
        val riparian = riverCorridors(world, altitudeMetres)
        val washes = wadiAndFanWashes(world, altitudeMetres, slopeDegrees)

        val streakScale = streakScaleOf(traces.streak, land, drought)

        val composition = IntArray(cellCount)
        val climate = IntArray(cellCount)
        val terrain = IntArray(cellCount)
        val cover = FloatArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!land[cell]) continue
            val age = crustAge.data[cell]
            val dry = drought[cell]
            val slope = slopeDegrees[cell]
            val altitude = altitudeMetres.data[cell]
            val warmestMonthC = world.climate.summerTemperature.data[cell]

            val flat = flatness[cell]
            val sand = dry * flat *
                smoothstep(SAND_SHEET_METRES, SAND_SEA_METRES, looseMetres[cell])

            // Red is the oldest sand's: an iron coat grows on grains a stable dune holds still
            // for long enough, while the reg and hamada between the ergs go dark with varnish.
            // Bare ground on the oldest crust takes a little of it, as Australia's gibber does.
            val oldest = smoothstep(OLDEST_UNREDDENED_CRUST_AGE, 1f, age)
            val red = dry * IRON_STAIN_MOST * oldest * (RED_OFF_THE_SAND + (1f - RED_OFF_THE_SAND) * sand)
            val blush = dry * BLUSH_MOST * smoothstep(YOUNGEST_BLUSHED_CRUST_AGE, OLDEST_UNREDDENED_CRUST_AGE, age) *
                (BLUSH_OFF_THE_SAND + (1f - BLUSH_OFF_THE_SAND) * sand)

            // Stone: the dark, varnished lag of ground that sheds its sand rather than holding it:
            // ground the erosion wore down more than its region, by as much as a sand sheet holds,
            // a high plateau, or rough ground short of a slope that is all rock.
            val plateau = smoothstep(PLATEAU_START_METRES, PLATEAU_FULL_METRES, altitude)
            val rough = smoothstep(ROUGH_GROUND_START_DEGREES, BARE_ROCK_SLOPE_START_DEGREES, slope)
            val deflated = smoothstep(0f, SAND_SHEET_METRES, sediment.shedMetres.data[cell])
            val stone = dry * (1f - sand) * max(max(plateau, rough), deflated) * STONE_MOST

            // Rock: slopes too steep to hold soil, the active belts' unweathered ranges, and the
            // alpine slopes above the tree line, where the cold leaves too short a season for
            // the cover to close over the scree.
            val steep = smoothstep(BARE_ROCK_SLOPE_START_DEGREES, BARE_ROCK_SLOPE_FULL_DEGREES, slope)
            val activeBelt = (1f - smoothstep(0f, ACTIVE_BELT_CRUST_AGE, world.plates.crustAge.data[cell])) *
                smoothstep(ROCKY_BELT_START_METRES, ROCKY_BELT_FULL_METRES, altitude)
            // Above the tree line the cold, not the slope, keeps the cover open, so any ground too
            // steep to hold loose sediment shows its scree; a flat alpine plain is tundra.
            val alpine = (1f - smoothstep(CLOSED_ALPINE_COVER_C, TREE_LINE_WARMEST_C, warmestMonthC)) *
                smoothstep(SAND_SHEDDING_DEGREES, ROUGH_GROUND_START_DEGREES, slope)
            val drySlope = dry * smoothstep(ROUGH_GROUND_START_DEGREES, BARE_ROCK_SLOPE_FULL_DEGREES, slope)
            val rock = max(max(steep, activeBelt), max(alpine, drySlope))
            // How much of the living cover the rock breaks through: all of it above the tree line,
            // and on slopes past the steepest any soil holds.
            val exposure = max(alpine, steep * STEEP_ROCK_THROUGH_COVER)

            val streak = (traces.streak[cell] / streakScale).coerceIn(-1f, 1f)

            // Snow: the perennial snow above the snow line, where even the warmest month freezes,
            // and the seasonal snow and frost below it, of which a composite built from the
            // clearest days of the year keeps only part, so the alpine rock between the tree line
            // and the snow line shows.
            val frozen = frozenShareOfYear(warmestMonthC, world.climate.winterTemperature.data[cell])
            val density = ClimateTint.canopyAt(world, cell)
            val perennial = 1f - smoothstep(SNOW_LINE_LOW_C, SNOW_LINE_HIGH_C, warmestMonthC)
            val snow = if (world.climate.biome[cell] == Biome.ICE_SHEET) {
                1f
            } else {
                max(perennial, frozen * frozen * SEASONAL_SNOW_SHOWN) * (1f - CANOPY_ABOVE_SNOW * density)
            }

            composition[cell] = pack(red, rock, sand, salt.data[cell])
            climate[cell] = pack((streak + 1f) * HALF, riparian[cell], snow, dry)
            terrain[cell] = pack(stone, dry * washes[cell], blush, exposure)
            cover[cell] = density
        }
        return SatelliteSurface(composition, climate, terrain, cover)
    }

    /**
     * The color of a land cell's ground from its packed [composition], [climate] and [terrain]
     * and its living [cover], before the relief: the bare ground mixed from its surfaces, the
     * wind's streaks laid over the dry ground, the cover over that where the rock does not break
     * through it, and the snow over everything.
     *
     * Every step is a float lerp in one fixed order and the channel is truncated once at the end,
     * which is what lets the shader's copy (`GpuRaster.satelliteGround`) agree to the bit.
     */
    fun groundColor(composition: Int, climate: Int, terrain: Int, cover: Float): Int {
        val shares = FloatArray(SHARE_COUNT)
        shares[RED] = byteAt(composition, 0) * UNIT_PER_BYTE
        shares[ROCK_SHARE] = byteAt(composition, 1) * UNIT_PER_BYTE
        val sand = byteAt(composition, 2) * UNIT_PER_BYTE
        shares[SALT_SHARE] = byteAt(composition, 3) * UNIT_PER_BYTE
        val streak = (byteAt(climate, 0) - STREAK_ZERO_BYTE) * UNIT_PER_STREAK_BYTE
        val riverbank = byteAt(climate, 1) * UNIT_PER_BYTE
        shares[SNOW_SHARE] = byteAt(climate, 2) * UNIT_PER_BYTE
        val arid = byteAt(climate, 3) * UNIT_PER_BYTE
        shares[ARID] = arid
        shares[STONE] = byteAt(terrain, 0) * UNIT_PER_BYTE
        shares[WASH_SHARE] = byteAt(terrain, 1) * UNIT_PER_BYTE
        shares[BLUSH_SHARE] = byteAt(terrain, 2) * UNIT_PER_BYTE
        val exposure = byteAt(terrain, 3) * UNIT_PER_BYTE
        // A streak is sand drifted along the wind and the ground between laid bare, so it moves
        // how much of the ground is sand as well as how much light comes off it.
        shares[STREAKED_SAND] = (sand * (1f + SAND_STREAK * streak)).coerceIn(0f, 1f)
        shares[STREAK_LIGHT] = 1f + STREAK_CONTRAST * streak * arid
        shares[SHOWN] = max(cover * (1f - exposure), riverbank)
        // The arid soil's ochre is a shrubland's, its scattered plants in it; where nothing
        // grows at all the ground is the bare reg's paler gray-buff.
        shares[BARREN] = arid * (1f - cover)
        return argb(channel(16, shares), channel(8, shares), channel(0, shares))
    }

    /** [groundColor] for one cell of a [SatelliteSurface]. */
    fun groundColor(surface: SatelliteSurface, cell: Int): Int = groundColor(
        surface.composition[cell], surface.climate[cell], surface.terrain[cell], surface.cover[cell]
    )

    private const val RED = 0
    private const val ROCK_SHARE = 1
    private const val STREAKED_SAND = 2
    private const val SALT_SHARE = 3
    private const val STREAK_LIGHT = 4
    private const val SHOWN = 5
    private const val SNOW_SHARE = 6
    private const val ARID = 7
    private const val STONE = 8
    private const val WASH_SHARE = 9
    private const val BLUSH_SHARE = 10
    private const val BARREN = 11
    private const val SHARE_COUNT = 12

    private fun channel(shift: Int, shares: FloatArray): Int {
        fun of(color: Int): Float = ((color shr shift) and 0xFF).toFloat()
        var soil = of(HUMID_SOIL) + (of(ARID_SOIL) - of(HUMID_SOIL)) * shares[ARID]
        soil += (of(REG) - soil) * shares[BARREN]
        // The varnished stone first, as the ground the sand lies on; then the sand, then its
        // weathering, pink before red so that an old erg is red and a middle-aged one pink; then
        // the pale washes the floods leave over all of it, the rock and the salt.
        var bare = soil + (of(HAMADA) - soil) * shares[STONE]
        bare += (of(SAND) - bare) * shares[STREAKED_SAND]
        bare += (of(BLUSH) - bare) * shares[BLUSH_SHARE]
        bare += (of(IRON_RED) - bare) * shares[RED]
        bare += (of(WASH) - bare) * shares[WASH_SHARE]
        bare += (of(ROCK) - bare) * shares[ROCK_SHARE]
        bare += (of(SALT) - bare) * shares[SALT_SHARE]
        bare *= shares[STREAK_LIGHT]
        val shown = shares[SHOWN]
        val green = of(SPARSE_GREEN) + (of(DENSE_GREEN) - of(SPARSE_GREEN)) * shown
        var ground = bare + (green - bare) * shown
        ground += (of(SNOW) - ground) * shares[SNOW_SHARE]
        return floor(ground).toInt().coerceIn(0, 255)
    }

    /**
     * The palette in the order the shader reads it from its color table: humid soil, arid soil,
     * iron red, sand, rock, salt, sparse green, dense green, snow, hamada, blush, wash, reg.
     */
    val PALETTE: IntArray
        get() = intArrayOf(
            HUMID_SOIL, ARID_SOIL, IRON_RED, SAND, ROCK, SALT, SPARSE_GREEN, DENSE_GREEN, SNOW,
            HAMADA, BLUSH, WASH, REG
        )

    /*
     * The colors, from true-color satellite composites — NASA's Blue Marble and its Next
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
     * Hamada: the dark, varnished stone of the Saharan plateaus (the Tademait, the Tinrhert, the
     * Tassili's lower benches) and Arabia's Najd, typical of the composites. Set between [SAND]
     * and [ROCK] at about the geometric mean of their luminances, because MODIS's albedo over
     * these deserts sorts the same way: the brightest sand sheets reflect over twice what the
     * darkest soils and volcanic ground do (Tsvetsinskaya and others 2002, 2006), and the lag
     * gravels and varnished plateaus fall between.
     */
    const val HAMADA: Int = 0xFF937862.toInt()

    /**
     * Pinkish buff: sand and soil partly reddened, between the young pale cream and the old iron
     * red: the salmon tones of the Tenere and of Egypt's Western Desert, and the eastern Rub al
     * Khali, typical of the composites.
     */
    const val BLUSH: Int = 0xFFD6A888.toInt()

    /**
     * The pale wash of wadi floors and alluvial fans: silt, gravel and calcrete a flood laid down,
     * the threads through the Hoggar and the aprons round the Tibesti in the composites, a little
     * brighter and grayer than the sand.
     */
    const val WASH: Int = 0xFFE4D9C3.toInt()

    /**
     * Reg: the bare gravel and sand plain of a desert with nothing growing on it, the Tanezrouft
     * and the Libyan Desert's floor, light gray-buff in the composites, paler and grayer than the
     * Chihuahua's shrub-flecked ochre ([ARID_SOIL]).
     */
    const val REG: Int = 0xFFC8B491.toInt()

    /**
     * How far one full streak brightens or darkens the dry ground it lies on, as a share of the
     * light, at full drought.
     *
     * An appearance choice, and said to be one: no measurement found gives a streak's own
     * brightness against the ground beside it at the map's scale. It is bounded by one that does
     * exist: over the Sahara and Arabia the brightest sand sheets reflect a little over twice
     * what the darkest desert soils do in the visible (Tsvetsinskaya and others 2002, 2006), and
     * a streak alternates the two, so at full scale it should stay well inside a factor of two.
     * A quarter either way in light is a factor of 1.67 from the brightest streak to the darkest,
     * and the typical streak, at one standard deviation, moves the light by half that.
     */
    const val STREAK_CONTRAST: Float = 0.25f

    /**
     * How far one full streak moves the share of the ground that is sand: a half either way, so
     * a streak is drifted sand along its crest and the ground laid bare between, and its contrast
     * is the palette's own, sand against soil, stone or red. An appearance choice inside the
     * same bound as [STREAK_CONTRAST]: the colors it moves between are Earth's.
     */
    const val SAND_STREAK: Float = 0.5f

    /** The byte a streak of zero is packed as: the middle of a byte, so both signs fit. */
    const val STREAK_ZERO_BYTE: Int = 128

    /** One unit of a 0..1 share per byte step, multiplied rather than divided on both sides. */
    const val UNIT_PER_BYTE: Float = 1f / 255f

    /** One unit of a streak per byte step either side of [STREAK_ZERO_BYTE]. */
    const val UNIT_PER_STREAK_BYTE: Float = 1f / 127f

    /**
     * The crust ages over which dry ground weathers from pale cream through pinkish buff to iron
     * red, on `PlateResult.crustAge`'s scale of 0 for the present epoch's belts to 1 for a craton.
     *
     * Desert sands redden with age: a dune field's young sand is yellow-cream, and it goes pink
     * and then red as an iron-oxide coat grows on the grains over a hundred thousand to a million
     * years of stability (Walker 1979; Gardner and Pye 1981, on the reddening of dune sands), and
     * only the shields carry red sand over whole regions. So the pink grows over the middle of
     * the history, from [YOUNGEST_BLUSHED_CRUST_AGE], and the red only past
     * [OLDEST_UNREDDENED_CRUST_AGE]. See docs/DESIGN_LEDGER.md, S1 Satellite look, round 2, for
     * the share of dry land each reaches.
     */
    private const val YOUNGEST_BLUSHED_CRUST_AGE = 0.3f
    private const val OLDEST_UNREDDENED_CRUST_AGE = 0.75f

    /**
     * The share of the pink the ground between the sands takes: a third. The pink is the sand's
     * own weathering, so the reg and soils between keep most of their buff.
     */
    private const val BLUSH_OFF_THE_SAND = 0.33f

    /**
     * The snow line's span of warmest-month temperatures, in degrees Celsius: perennial snow
     * where even the warmest month stays below freezing, Koppen's ice-cap boundary at 0 C, blended
     * two degrees either side so the snow line is a gradient and not an edge.
     */
    private const val SNOW_LINE_LOW_C = -2f
    private const val SNOW_LINE_HIGH_C = 2f

    /**
     * How much of the seasonal snow and frost the drawing keeps below the snow line: a half. An
     * appearance choice: a true-color composite is built from the clearest days, which favors
     * the snow-free season, so seasonal snow shows only in part.
     */
    private const val SEASONAL_SNOW_SHOWN = 0.5f

    /**
     * The most of the pinkish buff the ground takes. Seven tenths: the pink stage is a tint on
     * the sand and soil, not a surface of its own.
     */
    private const val BLUSH_MOST = 0.7f

    /**
     * The share of the red the bare ground between the sands takes on the oldest crust: a
     * quarter. A gravel plain's stones carry varnish more than an iron coat, so the Sahara's reg
     * and hamada read brown and gray beside its red ergs; Australia's gibber is the exception,
     * and a quarter lets it show without making red the shield's default.
     */
    private const val RED_OFF_THE_SAND = 0.25f

    /**
     * The altitudes over which dry ground becomes plateau, its sand blown off and its stones
     * varnished dark: the Saharan basins (the Tanezrouft, the chotts, the Libyan Desert's floor)
     * lie at 0 to 500 m and its stone plateaus (the Tademait at 500 to 800, the Tassili at 1,000
     * to 2,000, Arabia's Najd at 700 to 1,000) above.
     */
    private const val PLATEAU_START_METRES = 500f
    private const val PLATEAU_FULL_METRES = 1_500f

    /**
     * The slope at which dry ground stops being a plain and becomes stony hill country, in
     * degrees over [SLOPE_BASELINE_KM]: two degrees, past the upper slope of a pediment, whose
     * gravel plains fall less than a degree to the basins.
     */
    private const val ROUGH_GROUND_START_DEGREES = 2f

    /**
     * The most of the hamada's dark stone dry ground takes: seven tenths, so a plateau's own
     * relief and its sand patches still show through it.
     */
    private const val STONE_MOST = 0.7f

    /**
     * The tree line: the warmest month's 10 C, Koppen's boundary of the tundra climates and the
     * one `ClimateStage` classifies by.
     */
    private const val TREE_LINE_WARMEST_C = 10f

    /**
     * Where alpine cover gives out to scree and rock: the closed alpine grassland ends 600 to
     * 800 m above the tree line (Koerner 2003, Alpine Plant Life), which at the standard
     * atmosphere's 6.5 C a kilometer is 4 to 5 C cooler than the tree line's warmest month.
     */
    private const val CLOSED_ALPINE_COVER_C = TREE_LINE_WARMEST_C - 4.5f

    /**
     * How much of the living cover a slope too steep to hold soil breaks through below the tree
     * line: a half. An appearance choice: forest clings to steep slopes in a wet range, and over
     * a 12 km baseline the slope says where cliffs and scree are common, not where every
     * hillside is bare.
     */
    private const val STEEP_ROCK_THROUGH_COVER = 0.5f

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
     * The ground length the slope is read over, in kilometers, so it is the same slope on every
     * grid: the central difference spans this, rounded to whole cells and at least one each way,
     * and a longer span is carried back to this one ([slopeDegrees]).
     */
    private const val SLOPE_BASELINE_KM = 12.0

    /** The continents' Hurst exponent, which carries a slope between baselines ([slopeDegrees]). */
    private const val CONTINENTAL_HURST_EXPONENT = 0.66

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
     * the wind strips it. The great ergs fill basins and plains sloping a few tenths of a degree
     * over a baseline of kilometres; by a degree the ground is reg, hamada or upland.
     */
    private const val FLAT_GROUND_DEGREES = 0.3f
    private const val SAND_SHEDDING_DEGREES = 1f

    /**
     * How much loose sediment, in metres, begins a sand sheet and makes a sand sea: kept on the
     * cell or carried to it on the wind ([sedimentOf]).
     *
     * [sedimentOf] is a proxy rather than a thickness — a basin's depth below its region counts in
     * it — so the two ends are set against Earth's deserts rather than against an erg's depth:
     * sand covers about a fifth of the Sahara and a third of Arabia, the rest being reg, hamada
     * and rock. With these ends a sand sea (a weight over a half) covers about a third of the dry
     * land of seed 969495 at 1,024 rows, whose loose sediment runs 32 to 521 m between its first
     * and ninth deciles. See docs/DESIGN_LEDGER.md, S1 Satellite look.
     */
    private const val SAND_SHEET_METRES = 60f
    private const val SAND_SEA_METRES = 200f

    /**
     * How far the regional average reaches that tells a source of sediment from a sink, in
     * kilometres: the size of the basins and uplands that feed and hold a sand sea.
     */
    private const val SEDIMENT_REGION_KM = 150.0

    /**
     * The most of the iron red the sand takes on the oldest dry crust. Six tenths: the reddest
     * ergs (the Simpson, the Murzuq, Arabia's Ad Dahna) read orange-red in the composites, not the
     * full red of the oxide itself.
     */
    private const val IRON_STAIN_MOST = 0.6f

    /** How far a craton's red fades out across its edge, in kilometres. */
    private const val IRON_STAIN_SOFTENING_KM = 30.0

    /**
     * How far upwind the wind's sand comes from, in kilometres: the e-folding length of the upwind
     * trace. The Saharan sand seas sit a few hundred kilometres downwind of the massifs and wadis
     * that feed them.
     */
    private const val SAND_SUPPLY_REACH_KM = 250.0

    /**
     * How long a wind streak runs, in kilometres: the e-folding length of the upwind average.
     * The linear dunes of the Namib and the Simpson and the wind streaks of the Sahara and the
     * Ordos run tens of kilometres to a few hundred; two hundred is long against the
     * spacing of the valleys the small relief is mostly made of, so the average draws streaks and
     * not the drainage.
     */
    private const val STREAK_LENGTH_KM = 200.0

    /**
     * How far the sediment a sink keeps is spread, in kilometres: a sand sea fills its basin
     * rather than tracing the valley floors of the ground beneath it.
     */
    private const val SAND_SEA_SPREAD_KM = 25.0

    /**
     * The spread of the relief a streak is drawn from, in kilometres: the terrain less its own
     * Gaussian average at this spread, which keeps the hills and hollows a few cells across and
     * drops the regional slope that would otherwise shade a whole basin one way.
     */
    private const val STREAK_SOURCE_SPREAD_KM = 40.0

    /**
     * The finest relief a streak is drawn from, in kilometres: the terrain is Gaussian-averaged at
     * this spread before the regional average is taken off it, so a streak is a few cells across.
     * A streak one cell wide can only run between cells as a staircase, and its grain measures as
     * the grid's bearings whatever bearing the wind gave it.
     */
    private const val STREAK_SOURCE_FINEST_KM = 12.0

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
     * The Nile's natural discharge below Aswan, about 84 cubic km a year, and the width of the
     * cultivated valley floor it waters between Aswan and Cairo, 10 to 15 km: the anchor of a
     * river's green corridor ([riverCorridors]).
     */
    private const val NILE_DISCHARGE_CUBIC_KM_PER_YEAR = 84.0
    private const val NILE_VALLEY_FLOOR_KM = 12.0

    /**
     * How far above its channel ground can stand and still be the valley floor a river waters or
     * a wadi floods, in meters: a floodplain stands meters above its river, and a cell's altitude
     * is its ground averaged over kilometers, so fifty lets a cell beside the channel count while
     * the valley's walls and the terraces above them do not.
     */
    private const val VALLEY_FLOOR_RISE_METRES = 50f

    /**
     * The narrowest a corridor is drawn, in cells: two, a cell either side of its channel, so a
     * corridor sits between cells the way the ground does rather than in a row of single cells.
     * The drawing's own choice ([corridors]).
     */
    private const val NARROWEST_DRAWN_CORRIDOR_CELLS = 2f

    /**
     * The widest a corridor is drawn either side of its channel, in kilometers: twice the Nile's
     * half, past which a river's green is its delta's or its basin's and the cover already says so.
     */
    private const val WIDEST_CORRIDOR_HALF_KM = 12f

    /**
     * A wadi floor's width at a reference catchment, the anchor its square-root widening is drawn
     * from: about a kilometer and a half at ten thousand square kilometers, the wide floors of the
     * Hoggar's and the Tibesti's main drainages. An appearance choice read off the composites,
     * not a measured law; the widening's exponent is the river's.
     */
    private const val WADI_REFERENCE_FLOOR_KM = 1.5
    private const val WADI_REFERENCE_CATCHMENT_SQUARE_KM = 10_000.0

    /** The smallest catchment that cuts a wadi floor wide enough to show, in square kilometers. */
    private const val WADI_SMALLEST_CATCHMENT_SQUARE_KM = 500f

    /** The most of the pale wash a full wadi floor takes, and a fan. */
    private const val WADI_WASH_MOST = 0.6f
    private const val FAN_WASH_MOST = 0.4f

    /**
     * How far an alluvial fan spreads from the uplands that feed it, in kilometers: the Gaussian
     * reach of the rough ground's share. The fans of the Tibesti, the Makran and Death Valley run
     * from a few kilometers to a few tens.
     */
    private const val FAN_REACH_KM = 10.0

    /** The share of rough ground nearby at which a fan is full. */
    private const val FAN_FULL_UPLAND_SHARE = 0.3f

    /** The cover a fully green river gives its banks: the irrigated Nile valley's, nearly closed. */
    private const val RIVERBANK_COVER = 0.8f


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

    /**
     * The slope of the land in degrees as a baseline of [SLOPE_BASELINE_KM] would read it; 0 at
     * sea.
     *
     * The central difference spans whole cells, at least one each way, so on a grid coarser than
     * the baseline it reads over a longer one, and a longer baseline reads a gentler slope: relief
     * grows more slowly than the length it is measured over. Earth's continents are self-affine
     * over these scales with a Hurst exponent of about two thirds (Gagnon, Lovejoy and Schertzer
     * 2006, 0.66 for the continents), so relief over a length L grows as L to that power and the
     * mean slope as L to its excess over one. The measured slope is carried back to the
     * baseline's by that law, so every threshold in degrees here means on every grid what it
     * means on Earth over twelve kilometers.
     */
    internal fun slopeDegrees(
        altitudeMetres: FloatField,
        land: BooleanArray,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): FloatArray {
        val reachColumns = (SLOPE_BASELINE_KM / 2.0 / cellWidthKm).roundToInt().coerceAtLeast(1)
        val reachRows = (SLOPE_BASELINE_KM / 2.0 / cellHeightKm).roundToInt().coerceAtLeast(1)
        val acrossMetres = 2.0 * reachColumns * cellWidthKm * METRES_PER_KM
        val downMetres = 2.0 * reachRows * cellHeightKm * METRES_PER_KM
        val measuredBaselineKm = 2.0 * reachColumns * cellWidthKm
        val toBaseline = (measuredBaselineKm / SLOPE_BASELINE_KM).pow(1.0 - CONTINENTAL_HURST_EXPONENT)
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
                slope[cell] = (atan(sqrt(eastward * eastward + southward * southward) * toBaseline) *
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
    internal fun smallReliefInRegionalUnits(
        altitudeMetres: FloatField,
        land: BooleanArray,
        cellWidthKm: Double,
        cellHeightKm: Double
    ): FloatField {
        val average = altitudeMetres.copy()
        GaussianBlur.apply(
            average, STREAK_SOURCE_SPREAD_KM / cellWidthKm, STREAK_SOURCE_SPREAD_KM / cellHeightKm
        )
        val finest = altitudeMetres.copy()
        GaussianBlur.apply(
            finest, STREAK_SOURCE_FINEST_KM / cellWidthKm, STREAK_SOURCE_FINEST_KM / cellHeightKm
        )
        val small = FloatField(altitudeMetres.width, altitudeMetres.height)
        val power = FloatField(altitudeMetres.width, altitudeMetres.height)
        for (cell in land.indices) {
            if (!land[cell]) continue
            val residual = finest.data[cell] - average.data[cell]
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

    /**
     * What the erosion stage's work says about loose sediment, per land cell, in metres: what an
     * eroding upland sheds ([shedMetres]) and what a sink keeps ([keptMetres]).
     */
    internal class Sediment(val shedMetres: FloatField, val keptMetres: FloatArray)

    /**
     * The erosion's sources and sinks, from how far it lowered each cell against its region.
     *
     * The stage lowers nearly every cell of a world by a hundred to three hundred metres (the
     * probes at 1,024 rows put the dry land's deciles at 135 to 325 m), so the raw difference
     * between the plate stage's height and the erosion stage's says how old the ground is more
     * than where its sediment went. Against the Gaussian average of the lowering over
     * [SEDIMENT_REGION_KM] it says the second: a cell lowered more than its region has shed
     * material, one lowered less has kept it. A cell standing below its region's average ground is
     * a basin and keeps sediment too, by as much as it stands below it.
     */
    internal fun sedimentOf(world: WorldMap, altitudeMetres: FloatField): Sediment {
        val config = world.config
        val land = world.sea.isLand
        val cellsAcross = world.width
        val cellsDown = world.height
        val sigmaAcross = SEDIMENT_REGION_KM / config.cellWidthKm
        val sigmaDown = SEDIMENT_REGION_KM / config.cellHeightKm
        val reliefSpanMetres = config.scale.reliefSpanMetres
        val beforeErosion = world.plates.height.data
        val afterErosion = world.erosion.height.data

        val lowered = FloatField(cellsAcross, cellsDown)
        for (cell in land.indices) {
            if (land[cell]) lowered.data[cell] = (beforeErosion[cell] - afterErosion[cell]) * reliefSpanMetres
        }
        val regionalLowering = landAverage(lowered, land, sigmaAcross, sigmaDown)
        val regionalAltitude = landAverage(altitudeMetres, land, sigmaAcross, sigmaDown)

        val shed = FloatField(cellsAcross, cellsDown)
        val keptField = FloatField(cellsAcross, cellsDown)
        val kept = keptField.data
        for (cell in land.indices) {
            if (!land[cell]) continue
            val againstRegion = lowered.data[cell] - regionalLowering[cell]
            if (againstRegion > 0f) shed.data[cell] = againstRegion
            kept[cell] = max(0f, -againstRegion) + max(0f, regionalAltitude[cell] - altitudeMetres.data[cell])
        }
        val spreadKept = landAverage(
            keptField, land, SAND_SEA_SPREAD_KM / config.cellWidthKm, SAND_SEA_SPREAD_KM / config.cellHeightKm
        )
        for (cell in land.indices) if (!land[cell]) spreadKept[cell] = 0f
        return Sediment(shed, spreadKept)
    }

    /**
     * [field]'s Gaussian average over the land alone: the blur of the field over the blur of the
     * land mask, so a coast does not average its ground with the sea's zeros.
     */
    private fun landAverage(field: FloatField, land: BooleanArray, sigmaAcross: Double, sigmaDown: Double): FloatArray {
        val sum = FloatField(field.width, field.height)
        val weight = FloatField(field.width, field.height)
        for (cell in land.indices) {
            if (!land[cell]) continue
            sum.data[cell] = field.data[cell]
            weight.data[cell] = 1f
        }
        GaussianBlur.apply(sum, sigmaAcross, sigmaDown)
        GaussianBlur.apply(weight, sigmaAcross, sigmaDown)
        return FloatArray(land.size) { cell ->
            if (weight.data[cell] > 0f) sum.data[cell] / weight.data[cell] else 0f
        }
    }

    /**
     * The green a river lays along its valley, per land cell, 0 to [RIVERBANK_COVER]: a corridor
     * whose width follows the river's discharge, kept to the ground that stands no higher than the
     * valley floor beside it.
     *
     * The width is the downstream hydraulic geometry's: a channel's width grows as the square root
     * of its discharge (Leopold and Maddock 1953), and the valley floor it waters is taken to grow
     * with it, anchored on the Nile below Aswan, whose cultivated plain is 10 to 15 km wide for a
     * natural discharge of about 84 cubic km a year. A corridor narrower than a cell covers only
     * its share of the cell, so a small river's green thins and fades rather than being drawn a
     * cell wide.
     */
    internal fun riverCorridors(world: WorldMap, altitudeMetres: FloatField): FloatArray {
        val config = world.config
        val land = world.sea.isLand
        val cellAreaSquareMetres = config.cellWidthKm * config.cellHeightKm * SQUARE_METRES_PER_SQUARE_KM
        val halfWidthKm = FloatArray(land.size)
        for (cell in land.indices) {
            if (!land[cell]) continue
            val dischargeCubicKmPerYear = world.rivers.flowAccumulation.data[cell] /
                MILLIMETRES_PER_METRE * cellAreaSquareMetres / CUBIC_METRES_PER_CUBIC_KM
            if (dischargeCubicKmPerYear <= 0.0) continue
            halfWidthKm[cell] = (HALF * NILE_VALLEY_FLOOR_KM *
                sqrt(dischargeCubicKmPerYear / NILE_DISCHARGE_CUBIC_KM_PER_YEAR)).toFloat()
        }
        val share = corridors(world, halfWidthKm, altitudeMetres)
        for (cell in share.indices) share[cell] *= RIVERBANK_COVER
        return share
    }

    /**
     * The pale washes of dry country, per land cell, 0 to 1, before the drought weighs them: the
     * floors of the wadis, whose floods leave silt and gravel paler than the ground they cross,
     * and the alluvial fans spread at the foot of the uplands that shed them.
     *
     * A wadi's floor widens with its catchment as a river's valley does with its discharge,
     * because a dry country's floods grow with the ground that drains to them rather than with a
     * runoff it barely has. A fan is ground gentler than a hill country's within a few kilometers
     * of rough or rocky ground.
     */
    internal fun wadiAndFanWashes(world: WorldMap, altitudeMetres: FloatField, slopeDegrees: FloatArray): FloatArray {
        val config = world.config
        val land = world.sea.isLand
        val catchment = catchmentSquareKm(world)
        val halfWidthKm = FloatArray(land.size)
        for (cell in land.indices) {
            if (!land[cell] || catchment[cell] < WADI_SMALLEST_CATCHMENT_SQUARE_KM) continue
            halfWidthKm[cell] = (HALF * WADI_REFERENCE_FLOOR_KM *
                sqrt(catchment[cell] / WADI_REFERENCE_CATCHMENT_SQUARE_KM)).toFloat()
        }
        val wadis = corridors(world, halfWidthKm, altitudeMetres)

        val uplands = FloatField(world.width, world.height)
        for (cell in land.indices) {
            if (land[cell]) {
                uplands.data[cell] =
                    smoothstep(ROUGH_GROUND_START_DEGREES, BARE_ROCK_SLOPE_START_DEGREES, slopeDegrees[cell])
            }
        }
        val nearUplands = uplands.copy()
        GaussianBlur.apply(nearUplands, FAN_REACH_KM / config.cellWidthKm, FAN_REACH_KM / config.cellHeightKm)
        return FloatArray(land.size) { cell ->
            if (!land[cell]) {
                0f
            } else {
                val fan = smoothstep(0f, FAN_FULL_UPLAND_SHARE, nearUplands.data[cell]) *
                    (1f - uplands.data[cell]) * FAN_WASH_MOST
                max(wadis[cell] * WADI_WASH_MOST, fan)
            }
        }
    }

    /** The ground each land cell drains, its own included, in square kilometers. */
    private fun catchmentSquareKm(world: WorldMap): FloatArray {
        val land = world.sea.isLand
        val target = world.rivers.flowTarget
        val cellSquareKm = (world.config.cellWidthKm * world.config.cellHeightKm).toFloat()
        // The drainage tree in topological order: a cell flows on once everything above it has
        // reached it, so its area is complete before it is added downstream.
        val waiting = IntArray(land.size)
        for (cell in land.indices) {
            val next = target[cell]
            if (land[cell] && next >= 0 && land[next]) waiting[next]++
        }
        val area = FloatArray(land.size) { if (land[it]) cellSquareKm else 0f }
        val ready = IntArray(land.size)
        var readyCount = 0
        for (cell in land.indices) if (land[cell] && waiting[cell] == 0) ready[readyCount++] = cell
        var taken = 0
        while (taken < readyCount) {
            val cell = ready[taken++]
            val next = target[cell]
            if (next < 0 || !land[next]) continue
            area[next] += area[cell]
            waiting[next]--
            if (waiting[next] == 0) ready[readyCount++] = next
        }
        return area
    }

    /**
     * The share of each land cell a set of channel corridors covers, 0 to 1: a corridor
     * [halfWidthKm] either side of each channel cell (0 where there is none), across the ground
     * no more than [VALLEY_FLOOR_RISE_METRES] above that channel.
     *
     * The share is the corridor's overlap with the cell along the line between their centers, so
     * one three cells wide covers its neighbors too and fades at its edges by how far into them
     * it reaches. A corridor narrower than [NARROWEST_DRAWN_CORRIDOR_CELLS] is drawn that wide
     * and fainter as it is narrower, by the square root of its width over the drawn width: a
     * valley a ninth of the drawn width is a soft thread a third as strong, not a cell-wide
     * stroke. The square root is an appearance choice, the map's emphasis on a feature smaller
     * than its pixels: strictly by area, every river but the greatest would vanish on a grid of
     * twenty-kilometer cells, where the Nile's own valley is little over half a cell wide.
     */
    private fun corridors(world: WorldMap, halfWidthKm: FloatArray, altitudeMetres: FloatField): FloatArray {
        val cellsAcross = world.width
        val cellsDown = world.height
        val land = world.sea.isLand
        val cellWidthKm = world.config.cellWidthKm
        val cellHeightKm = world.config.cellHeightKm
        val cellKm = sqrt(cellWidthKm * cellHeightKm).toFloat()
        val narrowestHalfKm = cellKm * NARROWEST_DRAWN_CORRIDOR_CELLS * HALF
        var widest = narrowestHalfKm
        for (cell in halfWidthKm.indices) {
            halfWidthKm[cell] = halfWidthKm[cell].coerceAtMost(WIDEST_CORRIDOR_HALF_KM)
            widest = max(widest, halfWidthKm[cell])
        }
        val reachColumns = ceil((widest + cellKm) / cellWidthKm).toInt()
        val reachRows = ceil((widest + cellKm) / cellHeightKm).toInt()
        val share = FloatArray(land.size)
        parallelChunks(0, land.size) { from, to ->
            for (cell in from until to) {
                if (!land[cell]) continue
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                val altitude = altitudeMetres.data[cell]
                var best = 0f
                for (rowOffset in -reachRows..reachRows) {
                    val channelRow = row + rowOffset
                    if (channelRow < 0 || channelRow >= cellsDown) continue
                    val downKm = rowOffset * cellHeightKm
                    for (columnOffset in -reachColumns..reachColumns) {
                        var channelColumn = (column + columnOffset) % cellsAcross
                        if (channelColumn < 0) channelColumn += cellsAcross
                        val channel = channelRow * cellsAcross + channelColumn
                        val physicalHalf = halfWidthKm[channel]
                        if (physicalHalf <= 0f) continue
                        val half = max(physicalHalf, narrowestHalfKm)
                        val strength = sqrt(physicalHalf / half)
                        val acrossKm = columnOffset * cellWidthKm
                        val distanceKm = sqrt(acrossKm * acrossKm + downKm * downKm).toFloat()
                        val overlapKm = min(distanceKm + cellKm * HALF, half) -
                            max(distanceKm - cellKm * HALF, -half)
                        if (overlapKm <= 0f) continue
                        val rise = altitude - altitudeMetres.data[channel]
                        val onTheFloor = 1f - smoothstep(0f, VALLEY_FLOOR_RISE_METRES, rise)
                        best = max(best, (overlapKm / cellKm).coerceAtMost(1f) * strength * onTheFloor)
                    }
                }
                share[cell] = best
            }
        }
        return share
    }

    /** The two upwind averages, per cell: the streaks and the sand the wind brings. */
    internal class Traces(val streak: FloatArray, val supplyMetres: FloatArray)

    /**
     * Walks upwind from every land cell along the prevailing wind, a cell's width at a time, and
     * averages two fields along the way with an exponential weight: the small relief over
     * [STREAK_LENGTH_KM], which is the streak, and what the uplands shed ([sedimentOf]) over
     * [SAND_SUPPLY_REACH_KM], which is the sand the wind delivers. A walk stops at the sea, which
     * brings no sand and has no relief to streak.
     *
     * The wind is `ClimateResult.windEastwardMps` and `windSouthwardMps`, the annual surface wind
     * on the ground before the moisture march clamped its slant. It is read between cells
     * bilinearly as every field here is, and each step goes a cell's width along it and is carried
     * back into cells by the cells' own lengths, so the walk follows the wind's bearing and turns
     * where it turns. The march's clamped `windMeridional` would hold a quarter of the dry land at
     * one row a column, which is the grid's diagonal, and lay every streak there along it.
     */
    internal fun upwindTraces(
        world: WorldMap,
        smallRelief: FloatField,
        shedMetres: FloatField,
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
        val eastwardMps = world.climate.windEastwardMps
        val southwardMps = world.climate.windSouthwardMps

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
                    supplySum += supplyWeight * bilinear(shedMetres, x, y)
                    supplyWeights += supplyWeight
                    supplyWeight *= supplyDecay
                    // A step along the wind on the ground, then back into cells: one column is a
                    // cell's width east, one row a cell's height south.
                    val east = bilinear(eastwardMps, x, y).toDouble()
                    val south = bilinear(southwardMps, x, y).toDouble()
                    val speed = sqrt(east * east + south * south)
                    if (speed <= 0.0) break
                    x -= east / speed * stepKm / cellWidthKm
                    y -= south / speed * stepKm / cellHeightKm
                    if (y < 0.0 || y > cellsDown - 1.0) break
                }
                streak[cell] = if (streakWeights > 0f) streakSum / streakWeights else 0f
                supply[cell] = if (supplyWeights > 0f) supplySum / supplyWeights else 0f
            }
        }
        return Traces(streak, supply)
    }

    /**
     * The streak field's spread over the dry ground, weighted by its drought, times
     * [STREAK_FULL_SCALE_SIGMAS]: the value a full streak is packed at. One per world, so the
     * strongest streaks are the strongest this world's wind draws.
     */
    private fun streakScaleOf(streak: FloatArray, land: BooleanArray, drought: FloatArray): Float {
        var sumOfSquares = 0.0
        var weights = 0.0
        for (cell in streak.indices) {
            if (!land[cell] || drought[cell] <= 0f) continue
            sumOfSquares += drought[cell].toDouble() * streak[cell] * streak[cell]
            weights += drought[cell]
        }
        if (weights <= 0.0) return 1f
        return max(sqrt(sumOfSquares / weights).toFloat() * STREAK_FULL_SCALE_SIGMAS, 1e-6f)
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
 * drought. [terrain] holds the hamada's stone, the pale wash of wadis and fans, the pinkish buff of
 * partly reddened ground, and how far the rock breaks through the living cover. [cover] is the
 * living cover, 0 to 1. All four are row-major, a cell each.
 */
class SatelliteSurface internal constructor(
    val composition: IntArray,
    val climate: IntArray,
    val terrain: IntArray,
    val cover: FloatArray
)
