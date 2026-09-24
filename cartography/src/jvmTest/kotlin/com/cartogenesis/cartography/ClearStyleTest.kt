package com.cartogenesis.cartography

import com.cartogenesis.cartography.ColorVision.Deficiency.DEUTERANOPIA
import com.cartogenesis.cartography.ColorVision.Deficiency.PROTANOPIA
import com.cartogenesis.cartography.geometry.KnownFailures
import com.cartogenesis.cartography.geometry.RecordedViolation
import com.cartogenesis.worldgen.BorrowsSharedWorlds
import com.cartogenesis.worldgen.model.WorldMap
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
class ClearStyleTest : BorrowsSharedWorlds() {

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

        /** The known failures the rendered clauses record, by the audit finding. */
        const val FILLS_SHADED_TOGETHER =
            "Audit III F-I9: the colour-blind realm fills come closer than the margin where the relief shades them"
        const val HATCH_SHADED_AWAY =
            "Audit III F-I9: the colour-blind hatch comes closer to its fill than the margin where the relief shades it"
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
            for (stop in 0 until stops.size - 1) {
                val apart = difference(stops[stop], stops[stop + 1], deficiency)
                if (apart < worst) {
                    worst = apart
                    worstWhere = "stops $stop and ${stop + 1} under $name"
                }
                assertTrue(
                    apart >= MARGIN,
                    "CLEAR: land stops $stop and ${stop + 1} are ${apart.rounded()} apart under " +
                        "$name, short of $MARGIN"
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
        for (stop in 0 until stops.size - 1) {
            assertTrue(
                luminance[stop + 1] > luminance[stop],
                "CLEAR: land stop ${stop + 1} is darker than stop $stop, so the ramp is not ordered"
            )
        }
        println(
            "CLEAR land ramp luminance: " +
                luminance.joinToString(" → ") { ((it * 100).rounded()) }
        )
    }

    /**
     * The nine realm fills, blended toward every stop of the land ramp.
     *
     * Not the nine colours as published. The rasterizer blends a realm
     * [MapRasterizer.REALM_RELIEF_BLEED] of the way toward the land beneath it so that the
     * political map still reads as a map of somewhere, and that blend drags all nine toward one
     * colour — most at the snow line, where the land is nearly white. So the measurement is taken
     * over every stop of the ramp, and the worst of those is what the bar is set against.
     *
     * This is the palette on its own, before the relief shading every view lays over it and against
     * the ramp's stops rather than the political view's own ground; the fills as the political view
     * draws them are the next clause's.
     */
    @Test
    fun `every pair of realm fills stays apart under both deficiencies, over every ground`() {
        val realms = REALMS
        var worst = Double.MAX_VALUE
        var worstWhere = ""
        SIMULATIONS.forEach { (name, deficiency) ->
            LAND_STOPS.forEachIndexed { stop, land ->
                val fills = realms.map {
                    MapPalette.blend(it, land, MapRasterizer.REALM_RELIEF_BLEED)
                }
                for (first in fills.indices) for (second in first + 1 until fills.size) {
                    val apart = difference(fills[first], fills[second], deficiency)
                    if (apart < worst) {
                        worst = apart
                        worstWhere = "realms $first and $second over stop $stop under $name"
                    }
                    assertTrue(
                        apart >= MARGIN,
                        "CLEAR: realms $first and $second are ${apart.rounded()} apart over land " +
                            "stop $stop under $name, short of $MARGIN"
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
     * The nine realm fills as the political view draws them: the gallery's world rendered once
     * with all its land given to each realm in turn, so that each land cell carries every realm
     * over the same ground and under the same relief shade, and every pair compared cell by cell.
     * Lakes are left out, since they are drawn as water whoever holds them.
     *
     * The relief darkens the fills together where the ground faces away from the light, and on the
     * gallery's world some pairs come closer there than the margin (Audit III, F-I9, on this
     * clause's older form, which measured the fills unshaded); the clause runs as a known failure
     * recorded by the pairs that do.
     */
    @Test
    fun `every pair of realm fills stays apart as the political view draws them`() {
        val world = TestWorlds.gallery
        val setSize = MapStyle.CLEAR.realmRamp!!.size
        val drawn = (0 until setSize).map { drawnAs(world, it) }
        val cells = realmCells(world)
        var worst = Double.MAX_VALUE
        var worstWhere = ""
        val shortPairs = sortedSetOf<String>()
        val shortCells = HashMap<String, Int>()
        SIMULATIONS.forEach { (name, deficiency) ->
            val measured = HashMap<Long, Double>()
            for (first in 0 until setSize) for (second in first + 1 until setSize) {
                var short = 0
                for (cell in cells) {
                    val a = drawn[first][cell]
                    val b = drawn[second][cell]
                    val apart = measured.getOrPut((a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)) { difference(a, b, deficiency) }
                    if (apart < worst) {
                        worst = apart
                        worstWhere = "realms $first and $second at cell $cell under $name"
                    }
                    if (apart < MARGIN) short++
                }
                if (short > 0) {
                    shortPairs.add("$first-$second")
                    shortCells["$first-$second $name"] = short
                }
            }
        }
        println(
            "CLEAR realms as drawn over ${cells.size} land cells of seed 234475: worst ${worst.rounded()} " +
                "CIEDE2000 ($worstWhere), bar $MARGIN; under it: " +
                shortCells.entries.joinToString { "${it.key} on ${it.value} cells" }.ifEmpty { "none" }
        )
        KnownFailures.expect(FILLS_SHADED_TOGETHER, "pairs under the margin: 0-8, 2-6, 3-4, 3-6") {
            if (shortPairs.isNotEmpty()) {
                throw RecordedViolation(
                    "realm fills as drawn come within ${worst.rounded()} of each other ($worstWhere), under $MARGIN",
                    "pairs under the margin: " + shortPairs.joinToString()
                )
            }
        }
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
                    val plain =
                        MapPalette.blend(style.realm(id), land, MapRasterizer.REALM_RELIEF_BLEED)
                    val struck = MapPalette.blend(
                        MapPalette.blend(style.realm(id), style.coastline, MapStyle.HATCH_STRENGTH),
                        land,
                        MapRasterizer.REALM_RELIEF_BLEED
                    )
                    val apart = difference(plain, struck, deficiency)
                    worst = minOf(worst, apart)
                    assertTrue(
                        apart >= MARGIN,
                        "CLEAR: the hatch over realm $id is only ${apart.rounded()} from the " +
                            "fill under $name"
                    )
                }
            }
        }
        println("CLEAR hatch: worst stroke-against-fill ${worst.rounded()} CIEDE2000, bar $MARGIN")
    }

    /**
     * The hatch as the political view draws it: each realm of the second turn of the set against
     * its twin of the first, rendered over the gallery's world the way the clause above renders
     * the fills, and compared at the cells the hatch strikes, which are the only ones that differ.
     *
     * Where the relief darkens the ground, the stroke and the fill come closer than the margin for
     * some realms (Audit III, F-I9, on the older clause, which measured them unshaded); kept running
     * as a known failure recorded by the hatched realms that do.
     */
    @Test
    fun `the hatch stands out from its fill as the political view draws it`() {
        val world = TestWorlds.gallery
        val setSize = MapStyle.CLEAR.realmRamp!!.size
        val cells = realmCells(world)
        var worst = Double.MAX_VALUE
        var worstWhere = ""
        val shortRealms = sortedSetOf<Int>()
        for (id in 0 until setSize) {
            val plain = drawnAs(world, id)
            val hatched = drawnAs(world, id + setSize)
            val struck = cells.filter { plain[it] != hatched[it] }
            assertTrue(
                struck.size > cells.size / 4,
                "realm ${id + setSize}'s hatch struck ${struck.size} of ${cells.size} cells, not the third it rules"
            )
            SIMULATIONS.forEach { (name, deficiency) ->
                val measured = HashMap<Long, Double>()
                for (cell in struck) {
                    val a = plain[cell]
                    val b = hatched[cell]
                    val apart = measured.getOrPut((a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)) { difference(a, b, deficiency) }
                    if (apart < worst) {
                        worst = apart
                        worstWhere = "realm ${id + setSize} at cell $cell under $name"
                    }
                    if (apart < MARGIN) shortRealms.add(id + setSize)
                }
            }
        }
        println("CLEAR hatch as drawn: worst stroke-against-fill ${worst.rounded()} CIEDE2000 ($worstWhere), bar $MARGIN")
        KnownFailures.expect(HATCH_SHADED_AWAY, "hatched realms under the margin: 12, 16") {
            if (shortRealms.isNotEmpty()) {
                throw RecordedViolation(
                    "the hatch comes within ${worst.rounded()} of its fill ($worstWhere), under $MARGIN",
                    "hatched realms under the margin: " + shortRealms.joinToString()
                )
            }
        }
    }

    /** The land cells a realm's fill is drawn on: every land cell that is not under a lake. */
    private fun realmCells(world: WorldMap): List<Int> =
        world.sea.isLand.indices.filter { world.sea.isLand[it] && !world.rivers.lakes.isLake(it) }

    /**
     * What the political view draws for realm [id] on every land cell of [world]: the world with
     * all its land given to that realm, rendered in the colour-blind style with the coast's ink
     * left off, since the coast is drawn over the fill whoever holds it.
     */
    private fun drawnAs(world: WorldMap, id: Int): IntArray {
        val land = world.sea.isLand
        val owners = IntArray(land.size) { cell -> if (land[cell]) id else world.nations.nationId[cell] }
        val held = world.copy(nations = world.nations.copy(nationId = owners))
        return MapRasterizer.rasterize(
            held, RenderOptions(view = MapView.POLITICAL, style = MapStyle.CLEAR, showCoastline = false)
        )
    }

    /** And that the hatch is laid where the style says it is, and only past the ninth realm. */
    @Test
    fun `the hatch begins at the tenth realm and changes direction at the nineteenth`() {
        val style = MapStyle.CLEAR
        val setSize = style.realmRamp!!.size
        // One whole tile of the comb, which is where a two-in-six hatch repeats.
        val tile = 6
        val inkedPerTile = tile * 2

        // Nothing in the first turn of the set takes ink anywhere.
        for (id in 0 until setSize) {
            for (row in 0 until tile) for (column in 0 until tile) {
                assertTrue(
                    !style.hatched(id, column, row), "realm $id is hatched at $column,$row"
                )
            }
        }
        // The second turn takes two cells in six, on the leading diagonal.
        val second = (0 until tile).flatMap { row ->
            (0 until tile).map { column -> style.hatched(setSize, column, row) }
        }
        assertEquals(
            inkedPerTile, second.count { it }, "the second turn is not hatched two cells in six"
        )
        // The third takes the other diagonal, so the two never coincide across a whole tile.
        val third = (0 until tile).flatMap { row ->
            (0 until tile).map { column -> style.hatched(setSize * 2, column, row) }
        }
        assertEquals(
            inkedPerTile, third.count { it }, "the third turn is not hatched two cells in six"
        )
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
                val apart = difference(sea, land, deficiency)
                worst = minOf(worst, apart)
                assertTrue(
                    apart >= MARGIN,
                    "CLEAR: the sea is only ${apart.rounded()} from a land stop under $name"
                )
            }
        }
        println("CLEAR sea: worst sea-against-land ${worst.rounded()} CIEDE2000, bar $MARGIN")
    }

    /**
     * That the whole mechanism is inert in every other style.
     *
     * The realm set and the hatch are both reached through [MapStyle.realmRamp] being non-null, so
     * this is the single assertion that adding a colour-blind style could not have changed a pixel
     * of the ten that came before it. The two political ramps are reached through
     * [MapStyle.ownsPoliticalGround], which takes in the line-art style as well — a pen has no
     * blue to paint a political sea with — so that one is asserted separately below.
     */
    @Test
    fun `no other style declares a realm set, and none of them changed`() {
        MapStyle.entries.filter { it != MapStyle.CLEAR }.forEach { style ->
            assertNull(style.realmRamp, "${style.label} now declares a realm set")
            assertTrue(!style.ownsRealms, "${style.label} now owns its realms")
            assertEquals(
                style.lineArt,
                style.ownsPoliticalGround,
                "${style.label} paints its own political ground without drawing in line art"
            )
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
