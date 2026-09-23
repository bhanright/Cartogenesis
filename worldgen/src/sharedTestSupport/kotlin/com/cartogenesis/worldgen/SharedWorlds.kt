package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.lang.ref.WeakReference
import org.junit.ClassRule
import org.junit.Rule
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Worlds generated once per test JVM and lent to every test that asks for the same settings.
 *
 * Most of the per-merge tier's time was the same few worlds generated over and over: the four
 * standard seeds at 512 were rebuilt by dozens of classes, several seconds each. A world is a pure
 * function of its [WorldGenConfig] when it is generated fresh on the processor with nobody watching
 * its progress, so a test that asks for one of those can be handed the one already made.
 *
 * What is lent is only that: [world] takes a config and nothing else. A test whose subject is
 * generation itself — determinism, reuse through `previous`, an accelerator, stopping, progress,
 * timing — calls [WorldGenerationEngine] directly, because a cached answer would make its comparison
 * with itself.
 *
 * Lent worlds carry mutable arrays, and a test that wrote into one would change what every later
 * test sees. So each is fingerprinted when it is made ([WorldGuard]) and checked every time it is
 * handed out, after every test of a class that borrowed it ([Check]), after every such class has
 * finished, its teardown included ([Sweep]), and before it is dropped; the first check that finds a
 * change fails through the runner and names the test that last held the world. A borrowing class
 * extends [BorrowsSharedWorlds], which declares both rules. A test that means to write asks for
 * [privateCopy] instead, and nothing keeps a lent world past its test: a class that holds one in its
 * companion would hold it past the checks that see it and past the bound that frees it.
 */
object SharedWorlds {

    /**
     * The retained worlds' arrays, in bytes, before one goes.
     *
     * A 512 world holds 39 MB of arrays and a 1024 one four times that, so this keeps the four
     * standard worlds with room for a dozen variants or three 1024 worlds. It is what a worker can
     * spare beside the largest thing it does, which is generating a 2048 world: with twice this
     * retained, the worker that did so stood at 3.7 GB after a collection in a 4 GB heap; with this,
     * at 2.8 GB in the 3.5 GB the root build script gives a `:worldgen` worker.
     */
    private const val RETAINED_ARRAY_BYTES = 600_000_000L

    /**
     * The largest world kept once its borrower is done with it, in cells.
     *
     * Larger worlds are generated for the test that asks and not retained: a 2048 world is sixteen
     * times a 512 one, about 620 MB of arrays, more than the whole of [RETAINED_ARRAY_BYTES], and
     * holding one past the class that made it would take that from every class after it.
     */
    private const val LARGEST_RETAINED_CELLS = 1024 * 1024

    internal val lender = WorldLender(
        generate = { config -> WorldGenerationEngine.generateBlocking(config) },
        retainedArrayBytes = RETAINED_ARRAY_BYTES,
        largestRetainedCells = LARGEST_RETAINED_CELLS
    )

    /**
     * The world [config] generates, shared: do not write to it, and do not keep it past the test.
     *
     * Shared only with a test whose class declares [Check], which is what makes the check after the
     * test run; anywhere else the world is generated for the caller and kept by nobody.
     */
    fun world(config: WorldGenConfig): WorldMap = lender.world(config)

    /**
     * The world [config] generates, deep-copied for a test that writes to it: nothing in the copy is
     * shared with the lent world or with any other copy.
     */
    fun privateCopy(config: WorldGenConfig): WorldMap = ReachableState.deepCopy(lender.world(config))

    /**
     * Before each test, names the borrower; after it, checks every shared world the class has
     * borrowed so far and fails the test if one changed. After every test rather than once at the
     * end of the run, so that the failure lands on the test that wrote, and so that it is the runner
     * that reports it rather than a hook at JVM exit. Declared by [BorrowsSharedWorlds].
     */
    class Check : TestRule {
        override fun apply(base: Statement, description: Description): Statement {
            val borrower = "${description.className}.${description.methodName}"
            return runThenCheck(
                base,
                begin = { lender.beginTest(description.className, borrower) },
                check = { lender.endTest(description.className, borrower) }
            )
        }
    }

    /**
     * After a class has finished — its tests, and its own teardown after them — checks every world
     * this worker still holds or that anything still references, and fails the class if one changed.
     *
     * The check after each test cannot see a class's teardown, and in the last class of a worker
     * nothing borrows afterwards to see it either; this is what closes that. A class rule, so it is
     * the runner that reports it, against the class. Declared by [BorrowsSharedWorlds].
     */
    class Sweep : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            runThenCheck(base, begin = {}, check = { lender.sweepAfterClass(description.className) })
    }

    /**
     * Runs [base] between [begin] and [check], and reports a change [check] finds even when [base]
     * has failed, as a suppressed exception beside that failure.
     */
    private fun runThenCheck(base: Statement, begin: () -> Unit, check: () -> Unit): Statement =
        object : Statement() {
            override fun evaluate() {
                begin()
                var failure: Throwable? = null
                try {
                    base.evaluate()
                } catch (thrown: Throwable) {
                    failure = thrown
                }
                try {
                    check()
                } catch (changed: AssertionError) {
                    if (failure == null) throw changed
                    failure.addSuppressed(changed)
                }
                if (failure != null) throw failure
            }
        }
}

/**
 * What a JUnit 4 class that borrows shared worlds extends: [SharedWorlds.Check] after each of its
 * tests and [SharedWorlds.Sweep] after the class. One line in the class's declaration rather than
 * two rules in its body, and the class rule needs a static field, which a Kotlin class can only get
 * from a companion object that many of these classes already have, most of them private.
 *
 * `:desktop`'s tests run on the JUnit Platform instead and use `SharedWorldsCheck` there.
 */
abstract class BorrowsSharedWorlds {

    @get:Rule
    val sharedWorlds: TestRule = SharedWorlds.Check()

    companion object {
        @JvmField
        @ClassRule
        val sharedWorldsSweep: TestRule = SharedWorlds.Sweep()
    }
}

/**
 * The fingerprint of one world: a digest of every branch of it, and a way to ask what has changed.
 *
 * See [ReachableState.digestsByBranch] for what a branch is and what the digest reads.
 */
class WorldGuard(private val world: WorldMap) {
    internal val digests: Map<String, Long> = ReachableState.digestsByBranch(world)

    /** The branches whose contents differ from when this guard was made, in the world's order. */
    fun changedBranches(): List<String> = changedBranches(digests, world)
}

/** The branches of [world] whose digests differ from [digests], in the world's order. */
private fun changedBranches(digests: Map<String, Long>, world: WorldMap): List<String> {
    val now = ReachableState.digestsByBranch(world)
    return digests.keys.filter { now[it] != digests[it] } + now.keys.filter { it !in digests }
}

/**
 * The cache behind [SharedWorlds], separate from it so its failure paths can be exercised on worlds
 * of their own without touching the shared one.
 *
 * Retains worlds up to [retainedArrayBytes] of arrays and never one larger than
 * [largestRetainedCells]. When it is full, the world to go is the least recently lent variant; then,
 * if there are no variants, the least recently lent plain world only one class has asked for; then
 * the least recently lent of all. Most of what a tier generates is a variant — a seed with one
 * setting moved — that the class moving it asks for and nobody else, while a plain world, a seed at
 * its default settings, is what the next class asks for too. Counting the classes that have asked
 * is not enough on its own: a standard world only its first class has reached yet counts one, and
 * a run of variants would push it out. See docs/DESIGN_LEDGER.md, T5, for what that cost.
 *
 * A world that goes is checked first and then followed by a weak reference, so that if anything
 * still holds it — which nothing should — it goes on being checked until it is collected, and the
 * reference keeps nothing alive.
 */
class WorldLender(
    private val generate: (WorldGenConfig) -> WorldMap,
    private val retainedArrayBytes: Long,
    private val largestRetainedCells: Int
) {
    private class Loan(val config: WorldGenConfig, val world: WorldMap) {
        val guard = WorldGuard(world)
        val arrayBytes: Long = ReachableState.arrayBytes(world)
        val plain: Boolean = isPlainWorld(config)
        var lastBorrower: String = ""
        val borrowingClasses = HashSet<String>()
    }

    /** A world that has gone from the cache, followed until nothing holds it. */
    private class Released(
        val config: WorldGenConfig,
        val world: WeakReference<WorldMap>,
        val digests: Map<String, Long>,
        val lastBorrower: String,
        val borrowingClasses: Set<String>
    )

    /** Access-ordered, so the first entry is the least recently lent. */
    private val loans = LinkedHashMap<WorldGenConfig, Loan>(16, 0.75f, true)
    private val loansByClass = HashMap<String, MutableSet<WorldGenConfig>>()
    private val released = ArrayList<Released>()
    private var retainedBytes = 0L
    private var activeClass: String? = null
    private var activeBorrower: String? = null

    /** How many worlds this lender has generated, retained or not. */
    var generated = 0
        private set

    @Synchronized
    fun beginTest(testClass: String, borrower: String) {
        activeClass = testClass
        activeBorrower = borrower
    }

    /**
     * Checks every world [testClass] has borrowed — retained, or gone and still referenced — and
     * fails on the first that changed.
     */
    @Synchronized
    fun endTest(testClass: String, borrower: String) {
        try {
            for (config in loansByClass[testClass].orEmpty().toList()) {
                val loan = loans[config] ?: continue
                requireUnchanged(loan, "after $borrower, which had borrowed it") { changed ->
                    "$borrower wrote to the shared world for ${describe(config)}: " +
                        "${changed.joinToString()} changed. A test that writes asks for " +
                        "SharedWorlds.privateCopy."
                }
            }
            requireReleasedUnchanged({ testClass in it.borrowingClasses }, "after $borrower")
        } finally {
            activeClass = null
            activeBorrower = null
        }
    }

    /**
     * Checks every world this lender holds or has let go of and something still references, after
     * [testClass] has finished, and fails on the first that changed.
     */
    @Synchronized
    fun sweepAfterClass(testClass: String) {
        for (loan in loans.values.toList()) {
            requireUnchanged(loan, "after $testClass had finished, its teardown included") { changed ->
                "the shared world for ${describe(loan.config)} was written to after " +
                    "${loan.lastBorrower} borrowed it: ${changed.joinToString()} changed"
            }
        }
        requireReleasedUnchanged({ true }, "after $testClass had finished, its teardown included")
    }

    /**
     * The world [config] generates: the retained one if there is one and it is unchanged, otherwise a
     * new one, retained if it is small enough. Every hand-out is checked, a second one to the same
     * test included, because a test could write between the two and restore after the second.
     *
     * Asked for outside a test that declares the check — from a static initialiser, or from a helper
     * an audit class shares with a per-merge one — the world is generated for the caller alone and
     * not retained, because nothing would check it afterwards.
     */
    @Synchronized
    fun world(config: WorldGenConfig): WorldMap {
        val started = System.nanoTime()
        val borrower = activeBorrower
        if (borrower == null) {
            generated++
            return generate(config).also {
                report("generated, not shared: no SharedWorlds check was running", config, "caller", started)
            }
        }
        val testClass = activeClass!!
        if (config.width.toLong() * config.height > largestRetainedCells) {
            // The generation about to run is the largest thing a worker does, so the variants make
            // way for it first; the plain worlds stay for the classes that come after.
            loans.values.filter { !it.plain }.forEach { dropToMakeRoom(it, borrower) }
            generated++
            return generate(config).also { report("generated, too large to keep", config, borrower, started) }
        }
        val existing = loans[config]
        val loan = if (existing != null) {
            requireUnchanged(existing, "when lent to $borrower") { changed ->
                "the shared world for ${describe(config)} was written to while " +
                    "${existing.lastBorrower} held it: ${changed.joinToString()} changed. " +
                    "It has been dropped; $borrower fails because it would have been handed it."
            }
            report("checked and lent again", config, borrower, started)
            existing
        } else {
            generated++
            Loan(config, generate(config)).also {
                admit(it, borrower)
                report("generated", config, borrower, started)
            }
        }
        loan.lastBorrower = borrower
        loan.borrowingClasses.add(testClass)
        loansByClass.getOrPut(testClass) { HashSet() }.add(config)
        return loan.world
    }

    /**
     * One line per world lent, into the borrowing test's output: which world, whether it was made or
     * reused, and what that cost. It is how a class's share of the cache is read after a run.
     */
    private fun report(what: String, config: WorldGenConfig, borrower: String, startedNanos: Long) {
        val milliseconds = (System.nanoTime() - startedNanos) / 1_000_000
        println(
            "SHARED WORLD ${describe(config)} (settings ${config.toString().hashCode()}): $what " +
                "for $borrower in $milliseconds ms; ${loans.size} retained, " +
                "${retainedBytes / 1_000_000} MB"
        )
    }

    /** The configs currently retained, least recently lent first. For the report and the tests. */
    @Synchronized
    fun retainedConfigs(): List<WorldGenConfig> = loans.keys.toList()

    /** How many worlds that have gone from the cache something still references. For the tests. */
    @Synchronized
    fun releasedStillReferenced(): Int = released.count { it.world.get() != null }

    private fun admit(loan: Loan, borrower: String) {
        while (retainedBytes + loan.arrayBytes > retainedArrayBytes && loans.isNotEmpty()) {
            val leaving = loans.values.firstOrNull { !it.plain }
                ?: loans.values.firstOrNull { it.borrowingClasses.size < 2 }
                ?: loans.values.first()
            dropToMakeRoom(leaving, borrower)
        }
        loans[loan.config] = loan
        retainedBytes += loan.arrayBytes
    }

    /**
     * Checks [leaving] one last time and lets it go, failing [borrower]'s test if it changed; a world
     * that goes unchanged is followed from then on by a weak reference.
     */
    private fun dropToMakeRoom(leaving: Loan, borrower: String) {
        requireUnchanged(leaving, "before it was dropped to make room for $borrower's") { changed ->
            "the shared world for ${describe(leaving.config)} was written to while " +
                "${leaving.lastBorrower} held it: ${changed.joinToString()} changed. Found " +
                "when it was dropped to make room, so $borrower fails in its place."
        }
        forget(leaving)
        released.add(
            Released(
                leaving.config, WeakReference(leaving.world), leaving.guard.digests,
                leaving.lastBorrower, leaving.borrowingClasses.toSet()
            )
        )
    }

    private fun forget(loan: Loan) {
        if (loans.remove(loan.config) != null) retainedBytes -= loan.arrayBytes
        loansByClass.values.forEach { it.remove(loan.config) }
    }

    /**
     * Throws if [loan]'s world has changed since it was made, after dropping it so that no later
     * test is handed the changed one.
     */
    private inline fun requireUnchanged(loan: Loan, `when`: String, message: (List<String>) -> String) {
        val changed = loan.guard.changedBranches()
        if (changed.isEmpty()) return
        forget(loan)
        throw AssertionError("${message(changed)} (checked $`when`)")
    }

    /**
     * Throws if a world that has gone from the cache, that [which] selects and that something still
     * references, has changed; forgets the ones nothing references any more.
     */
    private fun requireReleasedUnchanged(which: (Released) -> Boolean, `when`: String) {
        val iterator = released.iterator()
        while (iterator.hasNext()) {
            val gone = iterator.next()
            val world = gone.world.get()
            if (world == null) {
                iterator.remove()
                continue
            }
            if (!which(gone)) continue
            val changed = changedBranches(gone.digests, world)
            if (changed.isEmpty()) continue
            iterator.remove()
            throw AssertionError(
                "the shared world for ${describe(gone.config)}, dropped from the cache but still " +
                    "held by something, was written to after ${gone.lastBorrower} borrowed it: " +
                    "${changed.joinToString()} changed. Nothing may keep a lent world past its " +
                    "test. (checked $`when`)"
            )
        }
    }

    private fun describe(config: WorldGenConfig): String {
        val kind = if (isPlainWorld(config)) "default settings" else "non-default settings"
        return "seed ${config.seed} at ${config.width}x${config.height}, $kind"
    }
}

/**
 * The grid a test states its worlds at before scaling them: every per-merge class writes a world as
 * `WorldGenConfig(seed, 512, 512)`, and a larger one as that `.atResolution(side, side)`, which
 * carries the tectonics' widths across the change of grid.
 */
private const val PREVIEW_CELLS_ACROSS = 512

/**
 * Whether [config] is a seed at its default settings — at its own grid, or at the preview grid scaled
 * to its grid — rather than a variant with a setting moved.
 */
private fun isPlainWorld(config: WorldGenConfig): Boolean {
    val atItsGrid = WorldGenConfig(seed = config.seed, width = config.width, height = config.height)
    if (config == atItsGrid) return true
    val scaledFromPreview = WorldGenConfig(
        seed = config.seed, width = PREVIEW_CELLS_ACROSS, height = PREVIEW_CELLS_ACROSS
    ).atResolution(config.width, config.height)
    return config == scaledFromPreview
}
