package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The shared-world guard, shown catching the writes it exists to catch.
 *
 * Everything here works on worlds of its own and on a [WorldLender] of its own, never on the one
 * behind [SharedWorlds]: a demonstration that wrote into a shared world would be the very failure
 * it demonstrates, landing on whichever class happened to borrow that world next.
 */
class SharedWorldsGuardTest {

    @Test
    fun `a deep copy reads the same as its original and shares no container with it`() {
        val copy = ReachableState.deepCopy(original)

        assertEquals(ReachableState.digestsByBranch(original), ReachableState.digestsByBranch(copy))
        val copyContainers = ReachableState.mutableContainers(copy)
        val shared = ReachableState.mutableContainers(original).count { it in copyContainers }
        assertEquals(0, shared, "the copy shares $shared containers with its original")
    }

    @Test
    fun `the guard names every branch a write changed, and nothing else`() {
        val copy = ReachableState.deepCopy(original)
        val guard = WorldGuard(copy)
        assertEquals(emptyList(), guard.changedBranches())

        // A float rewritten with its own value's bits flipped at the bottom, a river cell moved, and
        // a flag in a boolean mask: one write in a per-cell field, one in a list, one in a mask.
        val temperature = copy.climate.temperature.data
        temperature[1] = Float.fromBits(temperature[1].toRawBits() xor 1)
        val river = copy.rivers.rivers.first { it.cells.size > 1 }
        river.cells[0] = river.cells[1]
        copy.sea.isLand[0] = !copy.sea.isLand[0]

        assertEquals(
            setOf("sea.isLand", "climate.temperature", "rivers.rivers"),
            guard.changedBranches().toSet()
        )
    }

    @Test
    fun `a write is reported after the test that made it, naming that test`() {
        val lender = privateLender()
        lender.beginTest("Writer", "Writer.writes")
        lender.world(config).climate.precipitation.data[7] += 1f
        val reported = assertFailsWith<AssertionError> { lender.endTest("Writer", "Writer.writes") }

        assertTrue("Writer.writes wrote to the shared world" in reported.message!!, reported.message)
        assertTrue("climate.precipitation" in reported.message!!, reported.message)
        assertEquals(emptyList(), lender.retainedConfigs(), "a changed world must not be lent again")
    }

    @Test
    fun `a write missed after its test fails the next borrower, naming the one that held it`() {
        val lender = privateLender()
        lender.beginTest("Writer", "Writer.writes")
        val lent = lender.world(config)
        // Ending the writer's test without its check is what a class without the rule would do.
        lender.beginTest("Reader", "Reader.reads")
        lent.erosion.height.data[3] = -1f
        val reported = assertFailsWith<AssertionError> { lender.world(config) }

        assertTrue("while Writer.writes held it" in reported.message!!, reported.message)
        assertTrue("erosion.height" in reported.message!!, reported.message)
    }

    @Test
    fun `a write is caught before the world is dropped to make room`() {
        val roomForOne = ReachableState.arrayBytes(original) + 1
        val lender = WorldLender({ ReachableState.deepCopy(original) }, roomForOne, Int.MAX_VALUE)
        lender.beginTest("Writer", "Writer.writes")
        val lent = lender.world(config)
        lender.beginTest("Reader", "Reader.reads")
        lent.plates.plateId[0] = -2
        val reported = assertFailsWith<AssertionError> { lender.world(config.copy(seed = 43L)) }

        assertTrue("while Writer.writes held it" in reported.message!!, reported.message)
        assertTrue("dropped to make room" in reported.message!!, reported.message)
    }

    @Test
    fun `a world is lent again only while nothing has written to it`() {
        val lender = privateLender()
        lender.beginTest("First", "First.reads")
        val first = lender.world(config)
        lender.endTest("First", "First.reads")
        lender.beginTest("Second", "Second.reads")
        val second = lender.world(config)
        lender.endTest("Second", "Second.reads")

        assertTrue(first === second, "an unchanged world is the one lent again")
        assertEquals(1, lender.generated)
    }

    @Test
    fun `among plain worlds, one two classes share outlasts one only one class asked for`() {
        val roomForTwo = 2 * ReachableState.arrayBytes(original) + 1
        val lender = WorldLender({ ReachableState.deepCopy(original) }, roomForTwo, Int.MAX_VALUE)
        val askedOnce = config.copy(seed = 43L)
        val latecomer = config.copy(seed = 44L)
        borrow(lender, "First", config)
        borrow(lender, "Second", config, askedOnce)
        // The shared world is now the least recently lent, so a plain least-recently-used rule
        // would drop it for the latecomer.
        borrow(lender, "Third", latecomer)

        assertEquals(setOf(config, latecomer), lender.retainedConfigs().toSet())
    }

    @Test
    fun `a variant goes before a plain world, however recently it was lent`() {
        val roomForTwo = 2 * ReachableState.arrayBytes(original) + 1
        val lender = WorldLender({ ReachableState.deepCopy(original) }, roomForTwo, Int.MAX_VALUE)
        val variant = config.copy(seaLevel = 0.5f)
        val nextPlain = config.copy(seed = 43L)
        borrow(lender, "First", config)
        borrow(lender, "First", variant)
        // Both have one borrower, and the plain world is the less recently lent: only the rule
        // that a variant goes first keeps it.
        borrow(lender, "Second", nextPlain)

        assertEquals(setOf(config, nextPlain), lender.retainedConfigs().toSet())
    }

    @Test
    fun `outside a checked test a world is made for its caller and kept by nobody`() {
        val lender = privateLender()
        val first = lender.world(config)
        val second = lender.world(config)

        assertTrue(first !== second, "nothing would check a shared world after an unchecked caller")
        assertEquals(emptyList(), lender.retainedConfigs())
        assertEquals(2, lender.generated)
    }

    private fun borrow(lender: WorldLender, testClass: String, vararg configs: WorldGenConfig) {
        lender.beginTest(testClass, "$testClass.reads")
        configs.forEach { lender.world(it) }
        lender.endTest(testClass, "$testClass.reads")
    }

    private fun privateLender() =
        WorldLender({ ReachableState.deepCopy(original) }, Long.MAX_VALUE, Int.MAX_VALUE)

    private companion object {
        /** Small enough to generate in a second, and every stage still runs on it. */
        val config = WorldGenConfig(seed = 42L, width = 128, height = 128)

        val original: WorldMap by lazy { WorldGenerationEngine.generateBlocking(config) }
    }
}
