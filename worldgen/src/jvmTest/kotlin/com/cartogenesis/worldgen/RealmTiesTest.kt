package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A realm's and a people's description is the same in every run of the program.
 *
 * The realm stage tallies each realm's biomes in a hash map keyed by [Biome], and an enum's hash on
 * the JVM is the object's identity hash, which is handed out when the object is first hashed and
 * differs from one program start to the next. A sort by count left tied biomes in the map's order,
 * so the atlas listed them differently from run to run and the dominant biome flipped wherever the
 * top two tied. One process cannot see it — every map in it hashes the same objects the same way —
 * so this generates seed 42 at 512 in two child processes, the second with the enum's hashes drawn
 * from a different point in the hash sequence, and compares every field of every realm and every
 * people.
 *
 * See docs/DESIGN_LEDGER.md, chunk 6.
 */
class RealmTiesTest {

    @Test
    fun `two program starts describe the same realms and peoples`() {
        val first = describeInChildProcess(hashesDrawnFirst = 0)
        val second = describeInChildProcess(hashesDrawnFirst = 7919)
        println("TIES first run  ${first.joinToString(" ")}")
        println("TIES second run ${second.joinToString(" ")}")
        assertEquals(2, first.size, "the child process printed no digest: $first")
        assertEquals(first[0], second[0], "the realms of seed 42 at 512 differ between two program starts")
        assertEquals(first[1], second[1], "the peoples of seed 42 at 512 differ between two program starts")
    }

    private fun describeInChildProcess(hashesDrawnFirst: Int): List<String> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java, "-Xmx3g", "-cp", System.getProperty("java.class.path"),
            DescribeSeed42.javaClass.name, hashesDrawnFirst.toString()
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readLines()
        assertTrue(process.waitFor(CHILD_MINUTES, TimeUnit.MINUTES), "the child process did not finish")
        assertEquals(0, process.exitValue(), "the child process failed:\n" + output.takeLast(20).joinToString("\n"))
        return output.filter { it.startsWith(DIGEST) }.map { it.removePrefix(DIGEST) }
    }

    private companion object {
        const val DIGEST = "DIGEST "
        const val CHILD_MINUTES = 15L
    }
}

/**
 * The child process: draws [hashesDrawnFirst] identity hashes, hashes every [Biome] so each takes
 * the next ones in the sequence, then generates seed 42 at 512 and prints a digest of its realms and
 * one of its peoples.
 */
internal object DescribeSeed42 {
    @JvmStatic
    fun main(arguments: Array<String>) {
        repeat(arguments[0].toInt()) { Any().hashCode() }
        Biome.entries.forEach { it.hashCode() }
        val world = WorldGenerationEngine.generateBlocking(WorldGenConfig(seed = 42L, width = 512, height = 512))
        println("DIGEST " + sha256(world.nations.nations.toString()))
        println("DIGEST " + sha256(world.cultures.cultures.toString()))
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
