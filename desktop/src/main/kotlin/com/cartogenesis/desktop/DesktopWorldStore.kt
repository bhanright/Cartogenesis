package com.cartogenesis.desktop

import com.cartogenesis.cartography.ByteWorldLibrary
import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.LibraryKeys
import com.cartogenesis.cartography.SaveProblem
import com.cartogenesis.cartography.SaveSink
import com.cartogenesis.cartography.SaveSource
import com.cartogenesis.cartography.WorldFormatException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Saved worlds in a folder: under the user's home directory unless the reader chose another.
 *
 * Format comes from `:cartography`, so a world saved in a browser opens here and vice versa. A
 * file carries the world itself, which at 1024 is tens of megabytes gzipped — the price of a save
 * that does not depend on this machine generating the same world as the one that wrote it.
 *
 * The folder may be one a sync client keeps in step with other machines, which is how a reader
 * keeps their worlds in the cloud, and everything here is written to behave in one:
 *
 * - A save is written to a temporary file beside its target and renamed over it only once it is
 *   whole, so neither a reader nor a sync client ever sees half of one. The temporary file's name
 *   does not end in `.cgw`, so the listing never shows it.
 * - Every file ending in `.cgw` is listed under its own name, whatever world it holds, so the
 *   copies a sync client makes when two machines edit one world open, and save, as themselves.
 * - A file that is still arriving, or an online-only placeholder its client cannot fetch, is read
 *   as far as it goes and refused with that reason — see [SaveProblem.INCOMPLETE] and
 *   [SaveProblem.UNREADABLE] — never opened as something else.
 *
 * Every key is resolved inside the folder and checked to have stayed there, so no name, from the
 * listing or from a file's own header, reaches anywhere else. All file work runs on
 * [Dispatchers.IO], and a save's reading and writing run there too, a chunk at a time.
 */
class DesktopWorldStore(
    directory: File = File(System.getProperty("user.home"), ".cartogenesis/worlds"),
    compressor: Compressor = GzipCompressor,
    writtenBy: String = desktopSignature()
) : ByteWorldLibrary(compressor, writtenBy) {

    private val directory: File = directory.absoluteFile

    init {
        this.directory.mkdirs()
    }

    val location: String get() = directory.path

    override suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        directory.list()?.filter { it.endsWith(LibraryKeys.EXTENSION) } ?: emptyList()
    }

    override suspend fun <T> reading(name: String, block: suspend (SaveSource) -> T): T? =
        withContext(Dispatchers.IO) {
            val file = fileFor(name)
            if (!file.exists()) return@withContext null
            val input = try {
                BufferedInputStream(FileInputStream(file), BUFFER_BYTES)
            } catch (refused: IOException) {
                throw unreadable(refused)
            }
            input.use { block(StreamSource(it)) }
        }

    /**
     * The front of a file, which is where the header is.
     *
     * Without this, listing a library of 1024 worlds would read every array in every one of them
     * to put a title and a date on screen.
     */
    override suspend fun readPrefix(name: String, limitBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        val file = fileFor(name)
        if (!file.exists()) return@withContext null
        try {
            RandomAccessFile(file, "r").use { handle ->
                val wanted = minOf(limitBytes.toLong(), handle.length()).toInt()
                val bytes = ByteArray(wanted)
                var filled = 0
                while (filled < wanted) {
                    val count = handle.read(bytes, filled, wanted - filled)
                    if (count < 0) break
                    filled += count
                }
                if (filled == wanted) bytes else bytes.copyOf(filled)
            }
        } catch (refused: IOException) {
            throw unreadable(refused)
        }
    }

    override suspend fun replacing(name: String, contents: suspend (SaveSink) -> Unit) = withContext(Dispatchers.IO) {
        val target = fileFor(name)
        val temporary = temporaryBeside(target)
        try {
            FileOutputStream(temporary).use { output ->
                val buffered = output.buffered(BUFFER_BYTES)
                contents(StreamSink(buffered))
                buffered.flush()
                // On the disk before the rename makes it the save, so a crash after the rename
                // cannot leave a whole-looking file with nothing in it.
                output.channel.force(true)
            }
            replaceAtomically(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    override suspend fun remove(name: String) {
        withContext(Dispatchers.IO) { fileFor(name).delete() }
    }

    /**
     * The file [name] names, which must be inside the folder once every link and `..` in the path
     * has been followed: a key is checked for separators before it gets here, and this is the
     * check that does not depend on having thought of every way a name can leave a folder.
     */
    private fun fileFor(name: String): File {
        require(LibraryKeys.isValid(name)) { "'$name' is not a library key" }
        val file = File(directory, name)
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "'$name' is not inside the library" }
        return file
    }

    private class StreamSource(private val input: InputStream) : SaveSource {
        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int =
            try {
                input.read(into, offset, length)
            } catch (refused: IOException) {
                throw unreadable(refused)
            }
    }

    private class StreamSink(private val output: OutputStream) : SaveSink {
        override suspend fun write(bytes: ByteArray, offset: Int, length: Int) = output.write(bytes, offset, length)
    }

    private companion object {
        /** 64 KB between the codec's chunks and the disk. */
        const val BUFFER_BYTES = 1 shl 16

        fun unreadable(cause: IOException) =
            WorldFormatException(SaveProblem.UNREADABLE, cause.message ?: cause::class.simpleName.orEmpty())
    }
}

/**
 * A fresh temporary file beside [target], named so no listing takes it for a save: it starts with
 * a tilde and ends in `.tmp`, which is also the shape sync clients leave alone as scratch.
 */
internal fun temporaryBeside(target: File): File =
    File(target.parentFile, "~${target.name}.${UUID.randomUUID()}$TEMPORARY_SUFFIX")

private const val TEMPORARY_SUFFIX = ".tmp"

/**
 * Renames [temporary] over [target] in one step: there is never a moment when [target] is half
 * written, missing, or anything but the old file or the new one.
 *
 * Tried a few times, because a sync client that has just seen [target] change may hold it open
 * for a moment to read it, and on Windows a file held open cannot be replaced. Never falls back
 * to writing [target] in place, which is the half-written file this exists to prevent.
 */
internal suspend fun replaceAtomically(temporary: File, target: File) {
    var attempt = 1
    while (true) {
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
            return
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw unsupported
        } catch (held: FileSystemException) {
            if (attempt == REPLACE_ATTEMPTS) throw held
            attempt++
            delay(REPLACE_RETRY_MILLIS)
        }
    }
}

/**
 * Five tries, a fifth of a second apart: a second in all. Long enough to outlast a sync client
 * reading a file it has just seen change, short enough that a save which cannot be written says
 * so while the reader is still looking. A judgement, not a measurement: no client's hold time is
 * published.
 */
private const val REPLACE_ATTEMPTS = 5
private const val REPLACE_RETRY_MILLIS = 200L

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
