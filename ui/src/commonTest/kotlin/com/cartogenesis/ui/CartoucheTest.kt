package com.cartogenesis.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The legend's left-hand half, which is the only new *text* F3 puts on screen.
 *
 * The parts that can be wrong are all arithmetic and formatting, and none of them need a world or
 * a composition: whether the same seed always names the same world, whether two different peoples
 * name it differently, whether a realm's share is a share of the land rather than of the sheet.
 * [Cartouches.of] glues these to a `WorldMap` and is the one line here that a screenshot checks
 * instead.
 */
class CartoucheTest {

    @Test
    fun `the same seed always names the same world`() {
        val language = 42L * 31 + 7919L
        val once = Cartouches.worldName(59758L, language)
        val again = Cartouches.worldName(59758L, language)
        assertEquals(once, again)
        assertTrue(once.length >= 3, "\"$once\" is too short to be a name")
        assertEquals(once.first(), once.first().uppercaseChar(), "\"$once\" is not capitalised")
    }

    /**
     * The name comes from the largest people's *language*, so a world whose largest people are
     * different is a differently-named world even on the same seed. Two hundred languages is
     * enough for the odds of a wholesale collision to be nil; the assertion is only that the
     * language is being used at all, which a name derived from the seed alone would fail.
     */
    @Test
    fun `a different people names the world differently`() {
        val names = (0 until 200).map { Cartouches.worldName(59758L, it * 7919L + 13) }.toSet()
        assertTrue(names.size > 100, "only ${names.size} distinct names from 200 languages")
    }

    @Test
    fun `a world with no peoples is still named`() {
        val orphan = Cartouches.worldName(1234L, null)
        assertTrue(orphan.isNotEmpty())
        assertEquals(orphan, Cartouches.worldName(1234L, null))
        // And not the same name as every other peopleless world.
        assertTrue(orphan != Cartouches.worldName(7L, null))
    }

    @Test
    fun `the facts read as a chart's small print`() {
        assertEquals(
            "seed 59758 · 2048 × 2048 · largest realm Kelmaria (23%)",
            Cartouches.facts(59758L, 2048, 2048, "Kelmaria", 23)
        )
        // A world nobody has settled: no realm, and no empty parenthesis left behind.
        assertEquals("seed 7 · 512 × 512", Cartouches.facts(7L, 512, 512, null, 0))
    }

    @Test
    fun `the share is a share of the land, not of the sheet`() {
        // A quarter of the world is land, and one realm holds half of that.
        assertEquals(50, Cartouches.share(cellCount = 32_768, landCellCount = 65_536))
        assertEquals(100, Cartouches.share(cellCount = 65_536, landCellCount = 65_536))
        // An all-ocean world divides by nothing.
        assertEquals(0, Cartouches.share(cellCount = 0, landCellCount = 0))
    }

    @Test
    fun `the footnote quotes seconds, or milliseconds for a fast world`() {
        assertEquals("generated in 840 ms", Cartouches.footnote(840L))
        assertEquals("generated in 1.8 s", Cartouches.footnote(1_840L))
        assertEquals("generated in 75.7 s", Cartouches.footnote(75_712L))
        // An opened save was not generated here, so there is nothing to quote.
        assertEquals("", Cartouches.footnote(0L))
    }
}
