package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole of a generated world, fingerprinted: every field reachable from it, branch by branch.
 *
 * Two things rest on this. A world is a pure function of its settings, so two generations of one
 * seed in one process must be the same world to the last bit of every field, and that is asserted
 * here over everything [ReachableState] can reach — every stage's arrays, lists and names, the
 * settings and the labels — rather than over one height field and two counts. And a change that is
 * meant to move no bit (a rename, a test-visible hook that defaults to off, a refactor) is checked
 * by printing these digests before the change and after it and comparing them; that is the check
 * `docs/CONVENTIONS.md` rule 10 names. The digests are printed as `WORLD DIGEST` lines, one per
 * branch, so a comparison says which field moved rather than that the world did.
 *
 * On the JVM only, because the walk is reflection. `CrossPlatformFingerprintTest` prints the
 * fingerprint the JVM and Wasm are compared on, and `PipelineTest` holds determinism over the main
 * per-cell answers on both targets.
 */
class WorldFingerprintTest {

    private fun generate(config: WorldGenConfig): WorldMap = WorldGenerationEngine.generateBlocking(config)

    @Test
    fun `two generations of one seed are the same world in every field`() {
        val config = WorldGenConfig(seed = 42L, width = 128, height = 128)
        val first = ReachableState.digestsByBranch(generate(config))
        val second = ReachableState.digestsByBranch(generate(config))
        assertTrue(first.size > 20, "the walk found only ${first.size} branches of the world, so it compared almost nothing")
        val differing = first.keys.filter { first[it] != second[it] }
        assertTrue(differing.isEmpty(), "two generations of seed 42 differ in ${differing.joinToString()}")
        assertEquals(first.keys, second.keys, "the two worlds do not have the same fields")
    }

    /**
     * The control: the same comparison sees the last bit of one float in one array, and names the
     * branch it is in.
     */
    @Test
    fun `the fingerprint sees one bit of one cell`() {
        val world = generate(WorldGenConfig(seed = 42L, width = 128, height = 128))
        val copy = ReachableState.deepCopy(world)
        val before = ReachableState.digestsByBranch(world)
        assertEquals(before, ReachableState.digestsByBranch(copy), "a deep copy is not the same world")
        val cell = 64 * 128 + 64
        val temperature = copy.climate.summerTemperature.data
        temperature[cell] = Float.fromBits(temperature[cell].toRawBits() xor 1)
        val after = ReachableState.digestsByBranch(copy)
        val moved = before.keys.filter { before[it] != after[it] }
        assertEquals(listOf("climate.summerTemperature"), moved, "one bit of one cell moved these branches")
    }

    /**
     * Seed 42 at 512, the standard world, printed branch by branch for a before-and-after
     * comparison. Generated fresh rather than borrowed, since what is printed is what this build
     * generates.
     */
    @Test
    fun `print the standard world's digests`() {
        val digests = ReachableState.digestsByBranch(generate(WorldGenConfig(seed = 42L, width = 512, height = 512)))
        digests.forEach { (branch, digest) -> println("WORLD DIGEST %-40s %016x".format(branch, digest)) }
        assertTrue(digests.size > 20, "the walk found only ${digests.size} branches")
    }
}
