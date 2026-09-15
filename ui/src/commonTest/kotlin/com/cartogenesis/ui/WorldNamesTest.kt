package com.cartogenesis.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The list is data, and data can rot: a duplicate, a stray diacritic or a name out of order would
 * change which seed gets which name without anyone noticing. So the list's shape is asserted, and
 * so is the pick's spread, which is what [CartoucheTest] relies on.
 */
class WorldNamesTest {

    @Test
    fun `every name is plain, capitalised, and of a size a title can carry`() {
        val shape = Regex("[A-Z][a-z]{3,11}")
        val wrong = WorldNames.ALL.filterNot { shape.matches(it) }
        assertTrue(wrong.isEmpty(), "not names a title can carry: $wrong")
    }

    @Test
    fun `the list is three hundred names with no repeats, in the order the seeds depend on`() {
        assertEquals(300, WorldNames.ALL.size)
        assertEquals(WorldNames.ALL.size, WorldNames.ALL.toSet().size, "a repeated name")
        assertEquals(WorldNames.ALL.sorted(), WorldNames.ALL, "the list is not alphabetical")
    }

    @Test
    fun `neighbouring seeds spread across the list`() {
        val names = (0L until 300L).map { WorldNames.pick(it, 7919L) }.toSet()
        assertTrue(names.size > 150, "only ${names.size} distinct names from 300 neighbouring seeds")
    }
}
