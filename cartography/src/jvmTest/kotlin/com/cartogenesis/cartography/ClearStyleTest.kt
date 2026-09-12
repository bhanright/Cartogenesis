package com.cartogenesis.cartography

import com.cartogenesis.cartography.ColorVision.Deficiency.DEUTERANOPIA
import com.cartogenesis.cartography.ColorVision.Deficiency.PROTANOPIA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether [MapStyle.CLEAR] keeps the promise its name makes.
 *
 * Every other style in the list is a claim about appearance and is reviewed by looking at the
 * gallery. This one is a claim with a number behind it — that no two things the map means to
 * distinguish are told apart by hue alone — and a style called "Colour-blind" that failed it would
 * be worse than not shipping one, because it would be chosen *for* the promise.
 *
 * Everything here is measured with [ColorVision]: the Machado, Oliveira and Fernandes 2009
 * matrices at full severity, applied in linear light, and CIEDE2000 for the difference. The chrome
 * guard in `:ui` measures with the same instrument and states the same kind of margin.
 */
class ClearStyleTest {

    private companion object {

        /**
         * How far apart two colours must stay, in CIEDE2000, under either red-green deficiency.
         *
         * Taken from the measurement rather than the other way round, and deliberately below it.
         * On the CIEDE2000 scale about 1 is the smallest difference a trained eye finds under
         * laboratory conditions, 2 to 3 is a difference a printer will argue about, and 5 upward is
         * a difference anybody would call two colours. What [MapStyle.CLEAR] actually measures is
         * 8.00 between the closest pair of adjacent ramp stops and 7.38 between the closest pair of
         * realm fills, in both cases under deuteranopia or protanopia — so 6 gives away between a
         * fifth and a quarter of the headroom, sits above the "obviously two colours" figure, and
         * still leaves the guard able to fail. It is not set at 5, because a bar set exactly at the
         * threshold it is protecting passes a palette that is only barely acceptable — 6 asks this
         * one to be a fifth better than that. And it is not set at 7.3, just under the measurement,
         * because a guard a rounding error can break is a guard somebody eventually deletes.
         *
         * Shown to bite: replacing Tol's teal (#44AA99) with a near-duplicate of his green gave
         * "realms 2 and 3 are 2.25 apart over land stop 0 under normal vision, short of 6.0".
         */
        const val MARGIN = 6.0

        /** Every stop of the ramp, since the realm fills are blended toward whichever is under them. */
        val LAND_STOPS: IntArray get() = MapStyle.CLEAR.landRamp

        /** The nine, as the political view hands them out. */
        val REALMS: List<Int> get() = (0 until MapStyle.CLEAR.realmRamp!!.size).map {
            MapStyle.CLEAR.realm(it)
        }

        val SIMULATIONS = listOf(
            "normal vision" to null,
            "deuteranopia" to DEUTERANOPIA,
            "protanopia" to PROTANOPIA
        )
    }

    private fun difference(a: Int, b: Int, deficiency: ColorVision.Deficiency?): Double =
        if (deficiency == null) ColorVision.deltaE2000(a, b)
        else ColorVision.deltaE2000(a, b, deficiency)

    /**
     * Adjacent stops of the land ramp.
     *
     * Adjacent, not every pair: a hypsometric ramp is *meant* to run smoothly, and the question a
     * reader asks of it is whether this hillside is higher than that one — which is a comparison
     * between neighbours. Two stops four apart being far apart proves nothing.
     */
    @Test
    fun `adjacent stops of the land ramp stay apart under both deficiencies`() {
        val stops = LAND_STOPS
        var worst = Double.MAX_VALUE
        var worstWhere = ""
        SIMULATIONS.forEach { (name, deficiency) ->
            for (i in 0 until stops.size - 1) {
                val d = difference(stops[i], stops[i + 1], deficiency)
                if (d < worst) {
                    worst = d
                    worstWhere = "stops $i and ${i + 1} under $name"
                }
                assertTrue(
                    d >= MARGIN,
                    "CLEAR: land stops $i and ${i + 1} are ${d.rounded()} apart under $name, " +
                        "short of $MARGIN"
                )
            }
        }
        println(
            "CLEAR land ramp: ${stops.size} stops, worst adjacent ${worst.rounded()} CIEDE2000 " +
                "($worstWhere), bar $MARGIN"
        )
    }

    /**
     * And that the ramp is *ordered* — every stop lighter than the one below it.
     *
     * This is the property that makes the ramp survive a simulation at all. Two colours can be far
     * apart in CIEDE2000 and still leave a reader unable to say which is higher ground; lightness
     * is the one channel every deficiency keeps, so height is carried by lightness and the hue is
     * decoration.
     */
    @Test
    fun `the land ramp climbs in lightness from shore to snow`() {
        val stops = LAND_STOPS
        val luminance = stops.map { ColorVision.luminance(it) }
        for (i in 0 until stops.size - 1) {
            assertTrue(
                luminance[i + 1] > luminance[i],
                "CLEAR: land stop ${i + 1} is darker than stop $i, so the ramp is not ordered"
            )
        }
        println(
            "CLEAR land ramp luminance: " +
                luminance.joinToString(" → ") { ((it * 100).rounded()) }
        )
    }

    /**
     * The nine realm fills, as the political view actually draws them.
     *
     * Not the nine colours as published. The rasterizer blends a realm 30% toward the land beneath
     * it so that the political map still reads as a map of somewhere, and that blend drags all nine
     * toward one colour — most at the snow line, where the land is nearly white. So the measurement
     * is taken over every stop of the ramp, and the worst of those is what the bar is set against.
     */
    @Test
    fun `every pair of realm fills stays apart under both deficiencies, over every ground`() {
        val realms = REALMS
        var worst = Double.MAX_VALUE
        var worstWhere = ""
        SIMULATIONS.forEach { (name, deficiency) ->
            LAND_STOPS.forEachIndexed { stop, land ->
                val fills = realms.map { MapPalette.blend(it, land, 0.3f) }
                for (i in fills.indices) for (j in i + 1 until fills.size) {
                    val d = difference(fills[i], fills[j], deficiency)
                    if (d < worst) {
                        worst = d
                        worstWhere = "realms $i and $j over stop $stop under $name"
                    }
                    assertTrue(
                        d >= MARGIN,
                        "CLEAR: realms $i and $j are ${d.rounded()} apart over land stop $stop " +
                            "under $name, short of $MARGIN"
                    )
                }
            }
        }
        println(
            "CLEAR realms: 9 fills over ${LAND_STOPS.size} grounds, 36 pairs each, " +
                "worst ${worst.rounded()} CIEDE2000 ($worstWhere), bar $MARGIN"
        )
    }

    /**
     * The tenth realm, which has no tenth colour to be given.
     *
     * Nine is where a qualitative scheme runs out; the set begins again and each further turn is
     * hatched instead. What has to be true is that the hatch reads as a texture — that a struck
     * cell is plainly darker than an unstruck one of the same realm — and that it does so for all
     * nine colours and under both simulations, since the hatch is the *only* thing separating realm
     * 1 from realm 10.
     */
    @Test
    fun `the hatch that stands in for a tenth colour is visible on every fill`() {
        val style = MapStyle.CLEAR
        var worst = Double.MAX_VALUE
        SIMULATIONS.forEach { (name, deficiency) ->
            LAND_STOPS.forEach { land ->
                REALMS.indices.forEach { id ->
                    val plain = MapPalette.blend(style.realm(id), land, 0.3f)
                    val struck = MapPalette.blend(
                        MapPalette.blend(style.realm(id), style.coastline, MapStyle.HATCH_STRENGTH),
                        land,
                        0.3f
                    )
                    val d = difference(plain, struck, deficiency)
                    worst = minOf(worst, d)
                    assertTrue(
                        d >= MARGIN,
                        "CLEAR: the hatch over realm $id is only ${d.rounded()} from the fill " +
                            "under $name"
                    )
                }
            }
        }
        println("CLEAR hatch: worst stroke-against-fill ${worst.rounded()} CIEDE2000, bar $MARGIN")
    }

    /** And that the hatch is laid where the style says it is, and only past the ninth realm. */
    @Test
    fun `the hatch begins at the tenth realm and changes direction at the nineteenth`() {
        val style = MapStyle.CLEAR
        // Nothing in the first nine takes ink anywhere.
        for (id in 0 until 9) {
            for (y in 0 until 6) for (x in 0 until 6) {
                assertTrue(!style.hatched(id, x, y), "realm $id is hatched at $x,$y")
            }
        }
        // The second nine take two cells in six, on the leading diagonal.
        val second = (0 until 6).flatMap { y -> (0 until 6).map { x -> style.hatched(9, x, y) } }
        assertEquals(12, second.count { it }, "the second nine are not hatched two cells in six")
        // The third nine take the other diagonal, so the two never coincide across a whole tile.
        val third = (0 until 6).flatMap { y -> (0 until 6).map { x -> style.hatched(18, x, y) } }
        assertEquals(12, third.count { it }, "the third nine are not hatched two cells in six")
        assertTrue(second != third, "the two hatch directions are the same pattern")
    }

    /**
     * The sea, which is the one thing on the map that must never be mistaken for the land.
     *
     * A single flat slate rather than a depth ramp, so this is one colour against eight — and it
     * has to clear the bar against the darkest stop of the ramp, which is the shore it meets.
     */
    @Test
    fun `the slate sea never comes near the land`() {
        val sea = MapStyle.CLEAR.ocean(0f)
        var worst = Double.MAX_VALUE
        SIMULATIONS.forEach { (name, deficiency) ->
            LAND_STOPS.forEach { land ->
                val d = difference(sea, land, deficiency)
                worst = minOf(worst, d)
                assertTrue(
                    d >= MARGIN,
                    "CLEAR: the sea is only ${d.rounded()} from a land stop under $name"
                )
            }
        }
        println("CLEAR sea: worst sea-against-land ${worst.rounded()} CIEDE2000, bar $MARGIN")
    }

    /**
     * That the whole mechanism is inert in every other style.
     *
     * The realm set, the hatch and the two political ramps are all reached through
     * [MapStyle.realmRamp] being non-null, so this is the single assertion that F6 could not have
     * changed a pixel of the ten styles that came before it.
     */
    @Test
    fun `no other style declares a realm set, and none of them changed`() {
        MapStyle.entries.filter { it != MapStyle.CLEAR }.forEach { style ->
            assertNull(style.realmRamp, "${style.label} now declares a realm set")
            assertTrue(!style.ownsRealms, "${style.label} now owns its realms")
            for (id in 0 until 12) {
                assertEquals(MapPalette.nation(id), style.realm(id), "${style.label} realm $id")
                assertEquals(MapPalette.culture(id), style.people(id), "${style.label} people $id")
                assertTrue(!style.hatched(id, 1, 2), "${style.label} hatches realm $id")
            }
        }
        assertEquals(9, MapStyle.CLEAR.realmRamp?.size, "the muted set is no longer nine colours")
        assertTrue(MapStyle.CLEAR.ownsRealms)
    }

    private fun Double.rounded(): String = (kotlin.math.round(this * 100.0) / 100.0).toString()
}
