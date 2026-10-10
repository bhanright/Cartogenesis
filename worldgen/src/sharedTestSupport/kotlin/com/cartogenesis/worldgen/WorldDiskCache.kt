package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json

/**
 * Generated worlds kept on disk between test workers, between runs, and between the checkouts and
 * worktrees of one machine.
 *
 * The per-merge tier made 274 worlds on its last run before this existed, 208 of them distinct: the
 * workers side by side each made their own, and nothing outlived the run. A world is a pure function
 * of its settings and of the generator's code, so it is stored under both: a directory per
 * [generatorFingerprint], a hash of the generator's compiled classes, and in it a file per seed, grid
 * and hash of the full settings. Any change to the generator lands in a new directory and so misses
 * every world made before it, and two checkouts of the same code share every world.
 *
 * One [directory] serves every checkout on the machine ([sharedDirectory]), so worktrees with
 * different code use it at once, and nothing one does may cost another a world it is reading:
 * - A file is written whole to a temporary name and renamed into place, so no reader ever sees half
 *   of one. Two writers of the same world, in one JVM or two, take a lock on it: the first
 *   generates, and the second waits and reads what the first wrote.
 * - A reader opens the file before anything else and reads from that handle, so a deletion cannot
 *   cut it short: Windows refuses to delete a file `FileInputStream` holds open, and elsewhere the
 *   deletion takes only the name. A file gone before the reader opened it is a miss like any other.
 * - Nothing deletes a lock file but the removal of a whole generator's directory, which waits until
 *   nothing in it has been touched for [staleAfterMillis]: a lock file deleted while a writer held
 *   it would let a second writer lock a new file of the same name and generate beside the first.
 *
 * Each file carries the digests [ReachableState] took of the fresh world, and every read checks the
 * world it read against them, so a round trip that was not exact fails the borrowing test rather
 * than handing it a different world. A file that cannot be read at all — cut short, or written by a
 * different layout — is deleted and the world generated again.
 *
 * Kept small two ways. At its first use a cache removes every other generator's directory in which
 * nothing has been read, written or locked for [staleAfterMillis]: the code of a commit since
 * changed, whose worlds nobody will ask for again. And the files of every generator together are
 * held under [capacityBytes], the least recently used going first. A world's use is its file's
 * modification time, set on every hit, since the file system's own access time is not kept on
 * every machine. The one exception to recency is this generator's standard worlds — a seed at its
 * default settings, which every everyday run reads — which go only when nothing else is left: the
 * deep tier makes far more worlds than the cache holds, and must not push out the ones the next
 * everyday run of the same code needs.
 */
class WorldDiskCache(
    val directory: File,
    private val capacityBytes: Long,
    private val staleAfterMillis: Long,
    generatorFingerprint: () -> String = ::compiledGeneratorFingerprint
) {
    /** A world and where it came from, for the lender's report. */
    class Obtained(val world: WorldMap, val readFromDisk: Boolean)

    /**
     * This generator's directory. Made at the cache's first use, which is also when the other
     * generators' stale directories are removed; made first, so that another cache pruning at the
     * same moment sees this one in use.
     */
    private val generatorDirectory: File by lazy {
        File(directory, generatorFingerprint()).also { own ->
            own.mkdirs()
            removeStaleGenerators(directory, staleAfterMillis, keep = own)
        }
    }

    /** The world [config] makes: read from disk if it is there, otherwise [generate]d and stored. */
    fun obtain(config: WorldGenConfig, generate: (WorldGenConfig) -> WorldMap): Obtained {
        val file = fileFor(config)
        readIfPresent(file, config)?.let { return Obtained(it, readFromDisk = true) }
        synchronized(monitors.computeIfAbsent(file.absolutePath) { Any() }) {
            generatorDirectory.mkdirs()
            val lockFile = File(generatorDirectory, file.name + LOCK_SUFFIX)
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    // A generation under way is a use of its directory, so no prune takes its lock.
                    lockFile.setLastModified(System.currentTimeMillis())
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
        File(
            generatorDirectory,
            "seed${config.seed}-${config.width}x${config.height}-" +
                (if (isPlainWorld(config)) PLAIN else VARIANT) + "-${settingsHash(config)}$SUFFIX"
        )

    /**
     * The digests stored beside [config]'s world, taken from it fresh off the generator, or null if
     * the cache holds no readable copy. Reads the header only, and is not a use of the world.
     */
    fun storedDigests(config: WorldGenConfig): Map<String, Long>? {
        val file = fileFor(config)
        return try {
            DataInputStream(BufferedInputStream(file.inputStream(), BUFFER_BYTES)).use { input ->
                if (input.readLong() != MAGIC || input.readInt() != LAYOUT_VERSION) return null
                input.skipNBytes(input.readInt().toLong())
                LinkedHashMap<String, Long>().also { digests ->
                    repeat(input.readInt()) { digests[input.readUTF()] = input.readLong() }
                }
            }
        } catch (unreadable: IOException) {
            null
        }
    }

    private fun readIfPresent(file: File, config: WorldGenConfig): WorldMap? {
        // Absent, or evicted by another process since this one looked: a miss either way.
        val opened = try {
            file.inputStream()
        } catch (absent: FileNotFoundException) {
            return null
        }
        // Marked as used before it is read, so an eviction or a prune that lists the files while
        // this read is under way sees it as the newest there is.
        file.setLastModified(System.currentTimeMillis())
        val stored = try {
            DataInputStream(BufferedInputStream(opened, BUFFER_BYTES)).use { input ->
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
     * fit [capacityBytes], and any temporary file a worker that died mid-write left behind. Lock
     * files and directories stay: they go with their generator, by age ([removeStaleGenerators]).
     */
    private fun evictBeyondCapacity(keep: File) {
        val now = System.currentTimeMillis()
        val files = directory.walkTopDown().filter { it.isFile }.toList()
        files.filter { it.name.endsWith(TEMPORARY_SUFFIX) && now - it.lastModified() > ABANDONED_AFTER_MILLIS }
            .forEach { it.delete() }
        // Each file's use and length read once: other processes touch them while this one sorts.
        class Held(val file: File, val lastUsedMillis: Long, val bytes: Long, val ownStandard: Boolean)
        val worlds = files.filter { it.name.endsWith(SUFFIX) }
            .map { Held(it, it.lastModified(), it.length(), it.parentFile.name == generatorDirectory.name && "-$PLAIN-" in it.name) }
            .sortedWith(compareBy<Held>({ it.ownStandard }, { it.lastUsedMillis }))
        var total = worlds.sumOf { it.bytes }
        for (oldest in worlds) {
            if (total <= capacityBytes) break
            if (oldest.file == keep) continue
            // On Windows a file another worker is reading cannot be deleted; it goes next time.
            if (oldest.file.delete()) total -= oldest.bytes
        }
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
        private const val PLAIN = "default"
        private const val VARIANT = "variant"
        private const val LOCK_SUFFIX = ".lock"
        private const val TEMPORARY_SUFFIX = ".partial"

        /** A temporary file this old belongs to a worker that died; none takes an hour to write. */
        private const val ABANDONED_AFTER_MILLIS = 60 * 60 * 1000L

        private const val BUFFER_BYTES = 1 shl 20

        const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

        /** The build's `-PworldCacheDirectory`, passed on only when it is given. */
        const val DIRECTORY_PROPERTY = "cartogenesis.worldCache.directory"

        /** The environment's choice of directory, read when the build names none. */
        const val DIRECTORY_ENVIRONMENT = "CARTOGENESIS_WORLD_CACHE"

        /** Where the cache lives under the user's home when nothing else is named. */
        const val DEFAULT_UNDER_HOME = ".cartogenesis/world-cache"

        private const val CAPACITY_PROPERTY = "cartogenesis.worldCache.capacityBytes"
        private const val STALE_DAYS_PROPERTY = "cartogenesis.worldCache.staleAfterDays"

        /** Every setting written out, defaults included, so that the hash reads them all. */
        private val settingsFormat = Json { encodeDefaults = true }

        fun settingsJson(config: WorldGenConfig): String =
            settingsFormat.encodeToString(WorldGenConfig.serializer(), config)

        fun settingsHash(config: WorldGenConfig): String = sha256Hex(settingsJson(config).toByteArray()).take(16)

        /**
         * The directory every checkout on the machine shares: [configured] (the build's
         * `-PworldCacheDirectory`) if given, else [environment] (`CARTOGENESIS_WORLD_CACHE`), else
         * `.cartogenesis/world-cache` under [userHome]. A blank value counts as not given.
         */
        fun sharedDirectory(configured: String?, environment: String?, userHome: String): File =
            configured?.takeIf { it.isNotBlank() }?.let(::File)
                ?: environment?.takeIf { it.isNotBlank() }?.let(::File)
                ?: File(userHome, DEFAULT_UNDER_HOME)

        /** The shared directory as this JVM's properties and environment name it. */
        fun configuredDirectory(): File =
            sharedDirectory(System.getProperty(DIRECTORY_PROPERTY), System.getenv(DIRECTORY_ENVIRONMENT), System.getProperty("user.home"))

        /**
         * The cache the build configured, or null when it configured none: a test run from an
         * IDE without the build's properties, or with `-PworldCache=off`, generates every world as
         * before.
         */
        fun fromSystemProperties(): WorldDiskCache? {
            val capacity = System.getProperty(CAPACITY_PROPERTY)?.toLong() ?: return null
            val staleAfterMillis = configuredStaleAfterMillis() ?: return null
            return WorldDiskCache(configuredDirectory(), capacity, staleAfterMillis)
        }

        /** The build's `-PworldCacheStaleDays`, in milliseconds, or null when the build passed none. */
        fun configuredStaleAfterMillis(): Long? = System.getProperty(STALE_DAYS_PROPERTY)?.toLong()?.times(MILLIS_PER_DAY)

        /**
         * Removes every generator's directory under [directory] but [keep] in which no file has been
         * touched for [staleAfterMillis] — no world read or written, no generation locked — and an
         * empty one whose own time is that old. Returns the directories it removed; one holding a
         * file another process has open stays in part, and goes at a later start.
         */
        fun removeStaleGenerators(
            directory: File,
            staleAfterMillis: Long,
            keep: File? = null,
            nowMillis: Long = System.currentTimeMillis()
        ): List<File> {
            val generators = directory.listFiles()?.filter { it.isDirectory && it.name != keep?.name } ?: return emptyList()
            return generators.filter { generator ->
                val lastUsedMillis = generator.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() }
                    ?: generator.lastModified()
                nowMillis - lastUsedMillis > staleAfterMillis
            }.onEach { it.deleteRecursively() }
        }
    }
}

/**
 * The build's `clearWorldCache` (`clear`) and `clearWorldCacheStale` (`stale`): the shared directory
 * as the build and environment name it ([WorldDiskCache.configuredDirectory]), emptied, or rid of
 * what a cache's first use would prune.
 */
object WorldCacheMaintenance {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val directory = WorldDiskCache.configuredDirectory()
        when (arguments.singleOrNull()) {
            "clear" -> {
                directory.deleteRecursively()
                val left = directory.walkTopDown().count { it.isFile }
                println("World cache at $directory cleared" + if (left > 0) "; $left files a running test holds open stay" else "")
            }
            "stale" -> {
                val staleAfterMillis = WorldDiskCache.configuredStaleAfterMillis()
                    ?: error("the build passes the stale age; run this through ./gradlew clearWorldCacheStale")
                val removed = WorldDiskCache.removeStaleGenerators(directory, staleAfterMillis)
                println("World cache at $directory: removed ${removed.size} generators' directories unused for ${staleAfterMillis / WorldDiskCache.MILLIS_PER_DAY} days")
            }
            else -> {
                System.err.println("usage: clear | stale")
                exitProcess(2)
            }
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
