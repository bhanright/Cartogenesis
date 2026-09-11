package com.cartogenesis.desktop

import com.cartogenesis.cartography.ByteWorldLibrary
import com.cartogenesis.cartography.Compressor
import java.io.File
import java.io.RandomAccessFile

/**
 * Saved worlds under the user's home directory, in a place they can find and back up.
 *
 * Format comes from `:cartography`, so a world saved in a browser opens here and vice versa. A
 * file is no longer a few kilobytes of seed and settings: it carries the world itself, which at
 * 1024 is tens of megabytes gzipped — the price of a save that does not depend on this machine
 * generating the same world as the one that wrote it.
 */
class DesktopWorldStore(
    private val directory: File = File(System.getProperty("user.home"), ".cartogenesis/worlds"),
    compressor: Compressor = GzipCompressor,
    writtenBy: String = desktopSignature()
) : ByteWorldLibrary(compressor, writtenBy) {

    init {
        directory.mkdirs()
    }

    val location: String get() = directory.absolutePath

    // Plain blocking file I/O, same as before `save`/`load`/`list` suspended: the JVM never has a
    // reason to actually suspend here, the way `GzipCompressor` never does either. The interface
    // suspends for the browser's sake, and this side of the seam simply does not use it.
    override suspend fun names(): List<String> =
        directory.listFiles { file ->
            file.name.endsWith(EXTENSION) || file.name.endsWith(LEGACY_EXTENSION)
        }?.map { it.name } ?: emptyList()

    override suspend fun read(name: String): ByteArray? =
        File(directory, name).takeIf { it.exists() }?.readBytes()

    /**
     * The front of a file, which is where the header is.
     *
     * Without this, listing a library of 1024 worlds would read every array in every one of them
     * to put a title and a date on screen.
     */
    override suspend fun readPrefix(name: String, limit: Int): ByteArray? {
        val file = File(directory, name).takeIf { it.exists() } ?: return null
        val wanted = minOf(limit.toLong(), file.length()).toInt()
        return RandomAccessFile(file, "r").use { handle ->
            ByteArray(wanted).also { handle.readFully(it) }
        }
    }

    override suspend fun write(name: String, bytes: ByteArray) {
        File(directory, name).writeBytes(bytes)
    }

    override suspend fun remove(name: String) {
        File(directory, name).delete()
    }
}

/**
 * Which build wrote a save, for the header.
 *
 * The packaged build carries its version in the jar manifest; a development run has none, and
 * says so rather than inventing one.
 */
private fun desktopSignature(): String {
    val version = DesktopWorldStore::class.java.`package`?.implementationVersion ?: "dev"
    return "desktop $version"
}
