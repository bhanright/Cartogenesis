package com.cartogenesis.cartography

import com.cartogenesis.worldgen.pipeline.Biome

/** Colour ramps for the map views. Colours are packed ARGB ints. */
object MapPalette {

    const val PARCHMENT = 0xFFF2E4C6.toInt()
    const val COASTLINE = 0xFF3E4A52.toInt()
    const val RIVER = 0xFF3C7EA8.toInt()

    /** Fresh water. Deliberately lighter and greener than the sea, so a lake never reads as ocean. */
    const val LAKE = 0xFF4E92B4.toInt()
    const val LAKE_DEEP = 0xFF2F6B8C.toInt()

    private val OCEAN_RAMP = intArrayOf(
        0xFF0B2239.toInt(), // abyss
        0xFF11395B.toInt(),
        0xFF1B5479.toInt(),
        0xFF2B7398.toInt(),
        0xFF57A5C4.toInt()  // shelf
    )

    private val LAND_RAMP = intArrayOf(
        0xFF9DBE7A.toInt(), // coastal lowland
        0xFF8AAE63.toInt(),
        0xFFB9C070.toInt(),
        0xFFC8B072.toInt(),
        0xFFA98A63.toInt(),
        0xFF8A6F58.toInt(),
        0xFF7C6656.toInt(),
        0xFFEDEDE8.toInt()  // snow line
    )

    private val BIOME_COLORS = mapOf(
        Biome.OCEAN to 0xFF16405F.toInt(),
        Biome.SHALLOW_OCEAN to 0xFF3C82A8.toInt(),
        Biome.ICE_SHEET to 0xFFEFF4F7.toInt(),
        Biome.TUNDRA to 0xFFB5BBA6.toInt(),
        Biome.TAIGA to 0xFF5C7A5C.toInt(),
        Biome.TEMPERATE_FOREST to 0xFF4F7B45.toInt(),
        Biome.TEMPERATE_RAINFOREST to 0xFF2F5F3C.toInt(),
        Biome.GRASSLAND to 0xFFB3BF6E.toInt(),
        Biome.SHRUBLAND to 0xFF9BA86A.toInt(),
        Biome.DESERT to 0xFFDCC493.toInt(),
        Biome.SAVANNA to 0xFFC6B95F.toInt(),
        Biome.TROPICAL_SEASONAL_FOREST to 0xFF5E8F3E.toInt(),
        Biome.TROPICAL_RAINFOREST to 0xFF2C6B33.toInt(),
        Biome.ALPINE to 0xFFA9A29B.toInt(),
        // Olive against the yellower savanna and the greener shrubland it sits between, so a
        // dry-summer coast reads as its own country rather than as a variant of either.
        Biome.MEDITERRANEAN to 0xFFA89A4E.toInt(),
        // Greener and cooler than the seasonal forest, short of the rainforest's near-black.
        Biome.MONSOON_FOREST to 0xFF3E8C5E.toInt()
    )

    fun biome(biome: Biome): Int = BIOME_COLORS[biome] ?: PARCHMENT

    /** @param depth 0 at the shoreline, 1 at the deepest point. */
    fun ocean(depth: Float): Int = ramp(OCEAN_RAMP, 1f - depth.coerceIn(0f, 1f))

    /** @param elevation 0 at the shoreline, 1 at the highest peak. */
    fun land(elevation: Float): Int = ramp(LAND_RAMP, elevation.coerceIn(0f, 1f))

    private val TEMPERATURE_RAMP = intArrayOf(
        0xFF3B4CC0.toInt(), 0xFF6F92E8.toInt(), 0xFFDDDDDD.toInt(),
        0xFFF0A15C.toInt(), 0xFFB40426.toInt()
    )

    private val PRECIPITATION_RAMP = intArrayOf(
        0xFFE8D9A8.toInt(), 0xFFC9CE7F.toInt(), 0xFF7FB07A.toInt(),
        0xFF3A8C8C.toInt(), 0xFF1F4E79.toInt()
    )

    /**
     * The two ends of the temperature ramp, in degrees Celsius.
     *
     * A generated world runs from about -28 at the poles to about 32 at the equator before the
     * lapse rate takes anything off, so -30 to 40 holds every cell of every seed with a little
     * room at each end and spends none of the ramp on temperatures nothing ever reaches.
     */
    private const val COLDEST_ON_THE_RAMP_C = -30f
    private const val RAMP_SPAN_C = 70f

    fun temperature(celsius: Float): Int {
        val alongTheRamp = ((celsius - COLDEST_ON_THE_RAMP_C) / RAMP_SPAN_C).coerceIn(0f, 1f)
        return ramp(TEMPERATURE_RAMP, alongTheRamp)
    }

    fun precipitation(value: Float): Int = ramp(PRECIPITATION_RAMP, value.coerceIn(0f, 1f))

    /**
     * The golden angle, in degrees.
     *
     * Stepping a hue wheel by it is the standard way to hand out colours that stay well spaced
     * however many are asked for: it is the step that comes back nearest its own starting point
     * most slowly, so no two plates near each other in id land near each other in hue.
     */
    private const val GOLDEN_ANGLE_DEGREES = 137.508f

    private const val DEGREES_ROUND_THE_HUE_WHEEL = 360f

    /** Muted and light, so the boundary colours laid over them still read. */
    private const val PLATE_SATURATION = 0.45f
    private const val PLATE_VALUE = 0.85f

    /** Stable, well-spaced hues so neighbouring plates stay visually distinct. */
    fun plate(id: Int): Int {
        val hue = (id * GOLDEN_ANGLE_DEGREES) % DEGREES_ROUND_THE_HUE_WHEEL
        return hsvToRgb(hue, PLATE_SATURATION, PLATE_VALUE)
    }

    /**
     * What a plate boundary builds, by [com.cartogenesis.worldgen.pipeline.BoundaryClass] ordinal.
     *
     * Warm for the convergent pairs and cool for the rest, so the three kinds of collision read as
     * a family on the plates view while a rift or a ridge does not get mistaken for one.
     */
    fun boundaryClass(ordinal: Int): Int = when (ordinal) {
        0 -> 0xFFD9683A.toInt() // Andean margin
        1 -> 0xFFE0B33C.toInt() // collision plateau
        2 -> 0xFFC94F7C.toInt() // island arc
        3 -> 0xFF3FA9A0.toInt() // ocean ridge
        4 -> 0xFF6D7FD6.toInt() // continental rift
        5 -> 0xFF8C8F99.toInt() // transform fault
        else -> 0xFF404040.toInt()
    }

    /**
     * The realm wheel: how far one realm's hue is from the next, and where the first one starts.
     *
     * Not the golden angle, on purpose. A political map is read a handful of neighbours at a time
     * rather than as a whole set, so what matters is that adjacent *ids* differ sharply, and 47.5
     * degrees is a step that neither divides 360 nor comes near doing so — the first realm to
     * repeat a hue is the eighth. The 15 degrees of offset keeps realm 0 off pure red, which reads
     * as a warning rather than as a country.
     */
    private const val REALM_HUE_STEP_DEGREES = 47.5f
    private const val FIRST_REALM_HUE_DEGREES = 15f

    /** Enough colour to tell nine realms apart, short of enough to fight the relief beneath. */
    private const val REALM_SATURATION = 0.52f

    /** The two lightnesses realms alternate between. See [nation]. */
    private const val EVEN_REALM_VALUE = 0.88f
    private const val ODD_REALM_VALUE = 0.74f

    /** Realm colours. A different hue step from plates so the two views never look alike. */
    fun nation(id: Int): Int {
        val hue = (id * REALM_HUE_STEP_DEGREES + FIRST_REALM_HUE_DEGREES) %
            DEGREES_ROUND_THE_HUE_WHEEL
        // Alternating value keeps two realms with similar hues apart on the map.
        val value = if (id % 2 == 0) EVEN_REALM_VALUE else ODD_REALM_VALUE
        return hsvToRgb(hue, REALM_SATURATION, value)
    }

    /**
     * A people's colour.
     *
     * Offset from [nation] by a different hue step and a lower saturation, so that flipping between
     * the political and the peoples layer is obviously a change of subject rather than a reshuffle
     * of the same countries.
     */
    fun culture(id: Int): Int {
        val hue = (id * PEOPLE_HUE_STEP_DEGREES + FIRST_PEOPLE_HUE_DEGREES) %
            DEGREES_ROUND_THE_HUE_WHEEL
        val value = if (id % 2 == 0) EVEN_PEOPLE_VALUE else ODD_PEOPLE_VALUE
        return hsvToRgb(hue, PEOPLE_SATURATION, value)
    }

    /**
     * The peoples' wheel: a wider step, and starting in the blues rather than the reds.
     *
     * Both differ from the realms' on purpose — see [culture]. Starting at 200 degrees means
     * people 0 is a blue where realm 0 is a warm orange, which is the difference a reader sees
     * first when the two layers are flipped between.
     */
    private const val PEOPLE_HUE_STEP_DEGREES = 73.5f
    private const val FIRST_PEOPLE_HUE_DEGREES = 200f

    /** Paler than a realm's, so the peoples layer reads as the softer of the two. */
    private const val PEOPLE_SATURATION = 0.38f
    private const val EVEN_PEOPLE_VALUE = 0.82f
    private const val ODD_PEOPLE_VALUE = 0.68f

    /** Land no realm claims. Deliberately drab, so borders read as the thing with colour. */
    const val WILDERNESS = 0xFF6E6A5E.toInt()
    const val BORDER = 0xFF2A2118.toInt()

    /**
     * Diverging blue-to-red scale for how far the sea is from normal for its latitude.
     * Deliberately not the same ramp as absolute temperature, which is a different question.
     */
    fun temperatureAnomaly(degrees: Float): Int {
        val fromNormal = (degrees / STRONGEST_ANOMALY_C).coerceIn(-1f, 1f)
        return if (fromNormal >= 0f) blend(ANOMALY_MID, ANOMALY_WARM, fromNormal)
        else blend(ANOMALY_MID, ANOMALY_COLD, -fromNormal)
    }

    /**
     * The anomaly at which the ramp is fully warm or fully cold, in degrees Celsius.
     *
     * Seven, which is about what the Gulf Stream is worth off Norway — the strongest departure
     * from a latitude's own mean that a current on Earth produces. A wider ramp would draw every
     * gyre this generator makes as a faint tint.
     */
    private const val STRONGEST_ANOMALY_C = 7f

    /** Normal for the latitude, warmer than it, colder than it. */
    internal const val ANOMALY_MID = 0xFF20384C.toInt()
    internal const val ANOMALY_WARM = 0xFFC4442E.toInt()
    internal const val ANOMALY_COLD = 0xFF3E86C4.toInt()

    /*
     * The ramps and the biome table, handed out for a [RasterAccelerator] to upload. Read-only by
     * convention — nothing outside this file has a reason to write to one, and a copy per export
     * would be pointless traffic.
     */
    internal val plainOceanRamp: IntArray get() = OCEAN_RAMP
    internal val plainLandRamp: IntArray get() = LAND_RAMP
    internal val temperatureRamp: IntArray get() = TEMPERATURE_RAMP
    internal val precipitationRamp: IntArray get() = PRECIPITATION_RAMP

    /** [from], moved [toward] of the way to [to]. Alpha is not blended: every colour here is opaque. */
    fun blend(from: Int, to: Int, toward: Float): Int {
        val share = toward.coerceIn(0f, 1f)
        val fromRed = (from shr 16) and 0xFF
        val fromGreen = (from shr 8) and 0xFF
        val fromBlue = from and 0xFF
        val toRed = (to shr 16) and 0xFF
        val toGreen = (to shr 8) and 0xFF
        val toBlue = to and 0xFF
        return argb(
            (fromRed + (toRed - fromRed) * share).toInt(),
            (fromGreen + (toGreen - fromGreen) * share).toInt(),
            (fromBlue + (toBlue - fromBlue) * share).toInt()
        )
    }

    /** @param factor 1 leaves the colour untouched, below 1 darkens, above 1 lightens. */
    fun shade(color: Int, factor: Float): Int {
        val red = ((color shr 16) and 0xFF) * factor
        val green = ((color shr 8) and 0xFF) * factor
        val blue = (color and 0xFF) * factor
        return argb(red.toInt(), green.toInt(), blue.toInt())
    }

    /** [colors] read at [along], 0 at the first stop and 1 at the last, blended between them. */
    internal fun ramp(colors: IntArray, along: Float): Int {
        val clamped = along.coerceIn(0f, 1f)
        val atStop = clamped * (colors.size - 1)
        // Never the last stop, so there is always a stop above to blend toward; a value of exactly
        // 1 lands at the top of the interval below it instead, which is the same colour.
        val stop = atStop.toInt().coerceAtMost(colors.size - 2)
        return blend(colors[stop], colors[stop + 1], atStop - stop)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    /** Sixty degrees: the hue wheel's six sectors, one per pair of primaries. */
    private const val DEGREES_PER_SECTOR = 60f

    private fun hsvToRgb(hue: Float, saturation: Float, value: Float): Int {
        val chroma = value * saturation
        val sector = hue / DEGREES_PER_SECTOR
        // The second-strongest channel: full at a sector's boundary, nothing at its middle.
        val second = chroma * (1f - kotlin.math.abs(sector % 2f - 1f))
        val (red, green, blue) = when (sector.toInt()) {
            0 -> Triple(chroma, second, 0f)
            1 -> Triple(second, chroma, 0f)
            2 -> Triple(0f, chroma, second)
            3 -> Triple(0f, second, chroma)
            4 -> Triple(second, 0f, chroma)
            else -> Triple(chroma, 0f, second)
        }
        // What is left of the value once the chroma is spent goes to all three channels alike.
        val grey = value - chroma
        return argb(
            ((red + grey) * 255).toInt(),
            ((green + grey) * 255).toInt(),
            ((blue + grey) * 255).toInt()
        )
    }
}
