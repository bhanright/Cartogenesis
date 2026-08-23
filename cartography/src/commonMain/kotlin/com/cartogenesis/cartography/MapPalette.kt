package com.cartogenesis.cartography

import com.cartogenesis.worldgen.pipeline.Biome

/** Colour ramps for the map views. Colours are packed ARGB ints. */
object MapPalette {

    const val PARCHMENT = 0xFFF2E4C6.toInt()
    const val COASTLINE = 0xFF3E4A52.toInt()
    const val RIVER = 0xFF3C7EA8.toInt()

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
        Biome.ALPINE to 0xFFA9A29B.toInt()
    )

    fun biome(biome: Biome): Int = BIOME_COLORS[biome] ?: PARCHMENT

    /** @param depth 0 at the shoreline, 1 at the deepest point. */
    fun ocean(depth: Float): Int = ramp(OCEAN_RAMP, 1f - depth.coerceIn(0f, 1f))

    /** @param elevation 0 at the shoreline, 1 at the highest peak. */
    fun land(elevation: Float): Int = ramp(LAND_RAMP, elevation.coerceIn(0f, 1f))

    fun temperature(celsius: Float): Int {
        val t = ((celsius + 30f) / 70f).coerceIn(0f, 1f)
        return ramp(
            intArrayOf(
                0xFF3B4CC0.toInt(), 0xFF6F92E8.toInt(), 0xFFDDDDDD.toInt(),
                0xFFF0A15C.toInt(), 0xFFB40426.toInt()
            ),
            t
        )
    }

    fun precipitation(value: Float): Int = ramp(
        intArrayOf(
            0xFFE8D9A8.toInt(), 0xFFC9CE7F.toInt(), 0xFF7FB07A.toInt(),
            0xFF3A8C8C.toInt(), 0xFF1F4E79.toInt()
        ),
        value.coerceIn(0f, 1f)
    )

    /** Stable, well-spaced hues so neighbouring plates stay visually distinct. */
    fun plate(id: Int): Int {
        val hue = (id * 137.508f) % 360f
        return hsvToRgb(hue, 0.45f, 0.85f)
    }

    /** Realm colours. A different hue step from plates so the two views never look alike. */
    fun nation(id: Int): Int {
        val hue = (id * 47.5f + 15f) % 360f
        // Alternating value keeps two realms with similar hues apart on the map.
        val value = if (id % 2 == 0) 0.88f else 0.74f
        return hsvToRgb(hue, 0.52f, value)
    }

    /** Land no realm claims. Deliberately drab, so borders read as the thing with colour. */
    const val WILDERNESS = 0xFF6E6A5E.toInt()
    const val BORDER = 0xFF2A2118.toInt()

    fun blend(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        return argb(
            (ar + (br - ar) * f).toInt(),
            (ag + (bg - ag) * f).toInt(),
            (ab + (bb - ab) * f).toInt()
        )
    }

    /** @param factor 1 leaves the colour untouched, below 1 darkens, above 1 lightens. */
    fun shade(color: Int, factor: Float): Int {
        val r = ((color shr 16) and 0xFF) * factor
        val g = ((color shr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return argb(r.toInt(), g.toInt(), b.toInt())
    }

    private fun ramp(colors: IntArray, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val scaled = clamped * (colors.size - 1)
        val index = scaled.toInt().coerceAtMost(colors.size - 2)
        return blend(colors[index], colors[index + 1], scaled - index)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    private fun hsvToRgb(hue: Float, saturation: Float, value: Float): Int {
        val c = value * saturation
        val h = hue / 60f
        val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = value - c
        return argb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }
}
