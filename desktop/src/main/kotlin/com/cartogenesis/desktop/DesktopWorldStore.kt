package com.cartogenesis.desktop

import com.cartogenesis.cartography.TextWorldLibrary
import java.io.File

/**
 * Saved worlds under the user's home directory, in a place they can find and back up.
 *
 * Format comes from `:cartography`, so a world saved on a phone opens here and vice versa — the
 * file is a few kilobytes of seed and settings, small enough to move by any means.
 */
class DesktopWorldStore(
    private val directory: File = File(System.getProperty("user.home"), ".cartogenesis/worlds")
) : TextWorldLibrary() {

    init {
        directory.mkdirs()
    }

    val location: String get() = directory.absolutePath

    override fun names(): List<String> =
        directory.listFiles { file -> file.extension == "json" }?.map { it.name } ?: emptyList()

    override fun read(name: String): String? =
        File(directory, name).takeIf { it.exists() }?.readText()

    override fun write(name: String, text: String) {
        File(directory, name).writeText(text)
    }

    override fun remove(name: String) {
        File(directory, name).delete()
    }
}
