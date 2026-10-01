package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json

/**
 * Generated worlds kept on disk between test workers and between runs.
 *
 * The per-merge tier made 274 worlds on its last run before this existed, 208 of them distinct: the
 * workers side by side each made their own, and nothing outlived the run. A world is a pure function
 * of its settings and of the generator's code, so it is stored under both: a directory per
 * [generatorFingerprint], a hash of the generator's compiled classes, and in it a file per seed, grid
 * and hash of the full settings. Any change to the generator lands in a new directory and so misses
 * every world made before it; the old directories are the first to go when the cache is full.
 *
 * A file is written whole to a temporary name and renamed into place, so no reader ever sees half of
 * one. Two workers that want the same world at once take a lock on it: the first generates, and the
 * second waits and reads what the first wrote, rather than spending the same minutes beside it.
 *
 * Each file carries the digests [ReachableState] took of the fresh world, and every read checks the
 * world it read against them, so a round trip that was not exact fails the borrowing test rather
 * than handing it a different world. A file that cannot be read at all — cut short, or written by a
 * different layout — is deleted and the world generated again.
 *
 * The files together are held under [capacityBytes]; past it, the least recently used go first.
 */
class WorldDiskCache(
    private val directory: File,
    private val capacityBytes: Long,
    generatorFingerprint: () -> String = ::compiledGeneratorFingerprint
) {
    /** A world and where it came from, for the lender's report. */
    class Obtained(val world: WorldMap, val readFromDisk: Boolean)

    private val generatorDirectory: File by lazy { File(directory, generatorFingerprint()) }

    /** The world [config] makes: read from disk if it is there, otherwise [generate]d and stored. */
    fun obtain(config: WorldGenConfig, generate: (WorldGenConfig) -> WorldMap): Obtained {
        val file = fileFor(config)
        readIfPresent(file, config)?.let { return Obtained(it, readFromDisk = true) }
        synchronized(monitors.computeIfAbsent(file.absolutePath) { Any() }) {
            generatorDirectory.mkdirs()
            val lockFile = File(generatorDirectory, file.name + LOCK_SUFFIX).toPath()
            FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    // Another worker may have made it while this one waited for the lock.
                    readIfPresent(file, config)?.let { return Obtained(it, readFromDisk = true) }
                    val world = generate(config)
                    store(file, config, world)
                    return Obtained(world, readFromDisk = false)
                }
            }
        }
    }

    /** The file [config]'s world is kept in. */
    fun fileFor(config: WorldGenConfig): File =
        File(generatorDirectory, "seed${config.seed}-${config.width}x${config.height}-${settingsHash(config)}$SUFFIX")

    private fun readIfPresent(file: File, config: WorldGenConfig): WorldMap? {
        if (!file.isFile) return null
        val stored = try {
            DataInputStream(BufferedInputStream(file.inputStream(), BUFFER_BYTES)).use { input ->
                if (input.readLong() != MAGIC || input.readInt() != LAYOUT_VERSION) {
                    throw IOException("not a world file of this layout")
                }
                val settings = String(ByteArray(input.readInt()).also { input.readFully(it) }, Charsets.UTF_8)
                if (settings != settingsJson(config)) {
                    throw IOException("holds a world of other settings under the same hash")
                }
                val digests = LinkedHashMap<String, Long>()
                repeat(input.readInt()) { digests[input.readUTF()] = input.readLong() }
                digests to WorldFile.read(input)
            }
        } catch (unreadable: Exception) {
            println("WORLD CACHE could not read ${file.name} ($unreadable); deleting it and generating the world")
            file.delete()
            return null
        }
        val (digests, world) = stored
        val now = ReachableState.digestsByBranch(world)
        val differing = (digests.keys + now.keys).filter { digests[it] != now[it] }
        check(differing.isEmpty()) {
            "the world read from ${file.path} differs from the one generated: $differing. The " +
                "world file's round trip is not exact; clear the cache with " +
                "`./gradlew clearWorldCache` and fix WorldFile before relying on it."
        }
        // Reading counts as use, so the worlds read most stay longest.
        file.setLastModified(System.currentTimeMillis())
        return world
    }

    private fun store(file: File, config: WorldGenConfig, world: WorldMap) {
        val temporary = File(file.parentFile, "${file.name}.${ProcessHandle.current().pid()}$TEMPORARY_SUFFIX")
        try {
            DataOutputStream(BufferedOutputStream(temporary.outputStream(), BUFFER_BYTES)).use { output ->
                output.writeLong(MAGIC)
                output.writeInt(LAYOUT_VERSION)
                // As a length and bytes: every setting with its default is longer than `writeUTF` takes.
                val settings = settingsJson(config).toByteArray(Charsets.UTF_8)
                output.writeInt(settings.size)
                output.write(settings)
                val digests = ReachableState.digestsByBranch(world)
                output.writeInt(digests.size)
                for ((branch, digest) in digests) {
                    output.writeUTF(branch)
                    output.writeLong(digest)
                }
                WorldFile.write(world, output)
            }
            if (temporary.length() > capacityBytes) {
                temporary.delete()
                return
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failed: IOException) {
            // A full disk or a file another process holds open costs the next run a generation and
            // nothing else, so it is reported and the world handed out as made.
            println("WORLD CACHE could not store ${file.name}: $failed")
            temporary.delete()
            return
        }
        evictBeyondCapacity(keep = file)
    }

    /**
     * Deletes the least recently used world files, in every generator's directory, until those left
     * fit [capacityBytes], and any temporary file a worker that died mid-write left behind.
     */
    private fun evictBeyondCapacity(keep: File) {
        val now = System.currentTimeMillis()
        val files = directory.walkTopDown().filter { it.isFile }.toList()
        files.filter { it.name.endsWith(TEMPORARY_SUFFIX) && now - it.lastModified() > ABANDONED_AFTER_MILLIS }
            .forEach { it.delete() }
        val worlds = files.filter { it.name.endsWith(SUFFIX) && it.isFile }.sortedBy { it.lastModified() }
        var total = worlds.sumOf { it.length() }
        for (oldest in worlds) {
            if (total <= capacityBytes) break
            if (oldest == keep) continue
            val length = oldest.length()
            // On Windows a file another worker is reading cannot be deleted; it goes next time.
            if (oldest.delete()) total -= length
        }
        directory.listFiles()?.filter { it.isDirectory && it.listFiles()?.none { file -> file.name.endsWith(SUFFIX) } == true }
            ?.filter { it != generatorDirectory }
            ?.forEach { it.deleteRecursively() }
    }

    companion object {
        /**
         * One monitor per file for the threads of this JVM, whichever cache instance they ask
         * through; the file lock is for the other JVMs, and a JVM may not take it twice at once.
         */
        private val monitors = ConcurrentHashMap<String, Any>()

        /** "CGWORLD1": the first eight bytes of every world file. */
        private const val MAGIC = 0x4347574F524C4431L

        /** Raised whenever [WorldFile]'s layout or this header changes, so old files are refused. */
        private const val LAYOUT_VERSION = 1

        private const val SUFFIX = ".world"
        private const val LOCK_SUFFIX = ".lock"
        private const val TEMPORARY_SUFFIX = ".partial"

        /** A temporary file this old belongs to a worker that died; none takes an hour to write. */
        private const val ABANDONED_AFTER_MILLIS = 60 * 60 * 1000L

        private const val BUFFER_BYTES = 1 shl 20

        /** Every setting written out, defaults included, so that the hash reads them all. */
        private val settingsFormat = Json { encodeDefaults = true }

        fun settingsJson(config: WorldGenConfig): String =
            settingsFormat.encodeToString(WorldGenConfig.serializer(), config)

        fun settingsHash(config: WorldGenConfig): String = sha256Hex(settingsJson(config).toByteArray()).take(16)

        /**
         * The cache the build configured, or null when it configured none: a test run from an
         * IDE without the build's properties generates every world as before.
         */
        fun fromSystemProperties(): WorldDiskCache? {
            val directory = System.getProperty("cartogenesis.worldCache.directory") ?: return null
            val capacity = System.getProperty("cartogenesis.worldCache.capacityBytes")?.toLong() ?: return null
            return WorldDiskCache(File(directory), capacity)
        }
    }
}

/**
 * A hash of everything a world is made by besides its settings: the generator's compiled classes,
 * read from wherever this JVM loads them — `:worldgen`'s class directory in its own tests, its jar in
 * `:cartography`'s and `:desktop`'s, which hash the same since only the classes' names and bytes are
 * read — and the versions of the libraries and of the JVM it runs on, whose arithmetic it uses.
 */
fun compiledGeneratorFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val location = File(WorldGenerationEngine::class.java.protectionDomain.codeSource.location.toURI())
    if (location.isDirectory) {
        location.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(location).invariantSeparatorsPath to it }
            .filter { !it.first.startsWith("META-INF/") }
            .sortedBy { it.first }
            .forEach { (name, file) ->
                digest.update(name.toByteArray())
                digest.update(file.readBytes())
            }
    } else {
        ZipFile(location).use { jar ->
            jar.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("META-INF/") }
                .sortedBy { it.name }
                .forEach { entry ->
                    digest.update(entry.name.toByteArray())
                    digest.update(jar.getInputStream(entry).readBytes())
                }
        }
    }
    val libraries = listOf(
        KotlinVersion.CURRENT.toString(),
        File(kotlinx.coroutines.CoroutineScope::class.java.protectionDomain.codeSource.location.toURI()).name,
        File(Json::class.java.protectionDomain.codeSource.location.toURI()).name,
        System.getProperty("java.vm.vendor"),
        System.getProperty("java.vm.version"),
        System.getProperty("os.arch")
    )
    digest.update(libraries.joinToString("|").toByteArray())
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
