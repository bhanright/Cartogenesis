package com.cartogenesis.ui

import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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
        assertEquals("seed 59758 · 2048 × 2048", Cartouches.facts(59758L, 2048, 2048))
        assertEquals("seed 7 · 512 × 512", Cartouches.facts(7L, 512, 512))
    }

    @Test
    fun `the footnote quotes seconds, or milliseconds for a fast world`() {
        assertEquals("generated in 840 ms", Cartouches.footnote(840L))
        assertEquals("generated in 1.8 s", Cartouches.footnote(1_840L))
        assertEquals("generated in 75.7 s", Cartouches.footnote(75_712L))
        // An opened save was not generated here, so there is nothing to quote.
        assertEquals("", Cartouches.footnote(0L))
    }

    // ---- the name in the header, which is also the name on the file -------------------------

    @Test
    fun `a generated world is named, and a new seed renames it`() {
        val naming = WorldNaming()
        naming.generated(59758L, "Viimsenem")
        assertEquals("Viimsenem", naming.name)

        naming.generated(1234L, "Thauskiakhy")
        assertEquals("Thauskiakhy", naming.name)
    }

    /**
     * The rule worth having a test of: adjusting a world must not rename it, and making a
     * different one must. Every settings change kicks off a generation, so without the seed check
     * a name someone typed would be wiped by the next nudge of the ocean slider.
     */
    @Test
    fun `a name the reader typed survives a settings edit and is lost only to a new world`() {
        val naming = WorldNaming()
        naming.generated(59758L, "Viimsenem")
        naming.rename("The Hollow Sea")

        // Ocean coverage, mountain height, realms: same seed, same world, same name.
        repeat(3) { naming.generated(59758L, "Somethingelse") }
        assertEquals("The Hollow Sea", naming.name)

        // New world. A name given to the old one would be a lie about this one.
        naming.generated(42L, "Bymorybiald")
        assertEquals("Bymorybiald", naming.name)
    }

    @Test
    fun `a cleared field still files the save under something`() {
        val naming = WorldNaming()
        naming.generated(7L, "Sheogyinisk")
        naming.rename("")
        assertEquals("", naming.name, "the field shows what was typed, including nothing")
        assertEquals("Untitled world", naming.title)
        // And the next generation of the same world fills the empty field back in.
        naming.generated(7L, "Sheogyinisk")
        assertEquals("Sheogyinisk", naming.name)
    }

    @Test
    fun `an opened save keeps its own name through the generation that opens it`() {
        val naming = WorldNaming()
        naming.generated(59758L, "Viimsenem")

        naming.opened(1234L, "Someone else's world")
        // Opening hands the world back to the engine, which generates with every stage reused.
        naming.generated(1234L, "Thauskiakhy")
        assertEquals("Someone else's world", naming.name)
    }

    /**
     * The name is the save's `title`, which the container carries in its JSON header — so it comes
     * back from the file, and the library listing reads it without expanding the payload.
     */
    @Test
    fun `the name round-trips through the save header`() = runTest {
        val naming = WorldNaming()
        naming.generated(59758L, "Viimsenem")
        naming.rename("The Hollow Sea")
        naming.generated(59758L, "Somethingelse")

        val document = WorldDocument(
            id = "a-world",
            title = naming.title,
            config = WorldGenConfig(seed = 59758L, width = 256, height = 256),
            savedAt = 1_700_000_000_000L
        )
        // No world in the file: the title lives in the header, which is the part being tested,
        // and a generated world would cost a minute to say nothing more.
        val bytes = WorldCodec.encode(document, world = null)
        assertEquals("The Hollow Sea", WorldCodec.decodeHeader(bytes).document.title)

        // And what comes back off the shelf is what the header field then holds.
        val reopened = WorldNaming()
        reopened.opened(59758L, WorldCodec.decodeHeader(bytes).document.title)
        assertEquals("The Hollow Sea", reopened.name)
    }
}
