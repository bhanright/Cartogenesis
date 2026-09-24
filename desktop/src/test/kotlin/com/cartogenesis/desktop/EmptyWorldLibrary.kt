package com.cartogenesis.desktop

import com.cartogenesis.cartography.LibraryEntry
import com.cartogenesis.cartography.LibraryKeys
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.SaveProblem
import com.cartogenesis.cartography.SaveRefusal
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.worldgen.model.WorldMap

/**
 * A library with nothing in it, kept by nobody.
 *
 * What the interface tests that drive the real desktop platform hand it in place of the reader's
 * own library: the real one lists every save under the home directory when the window opens, so a
 * test's timing and what its window shows would depend on whose machine it ran on. Saving into it
 * keeps nothing, and opening from it finds nothing.
 */
internal object EmptyWorldLibrary : WorldLibrary {
    override suspend fun list(): List<LibraryEntry> = emptyList()
    override suspend fun save(document: WorldDocument, world: WorldMap, key: String?): String =
        key ?: LibraryKeys.of(document)
    override suspend fun load(key: String): LoadOutcome =
        LoadOutcome.Refused(SaveRefusal(SaveProblem.UNREADABLE, "this library keeps nothing"))
    override suspend fun delete(key: String) = Unit

    /** What the settings dialog names as the library's place. */
    const val LOCATION = "an empty library the tests hold"
}
