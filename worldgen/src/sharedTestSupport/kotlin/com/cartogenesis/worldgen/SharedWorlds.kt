package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
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
 * test sees. So each is fingerprinted when it is made ([WorldGuard]), checked again every time it is
 * handed out, and checked after every test that borrowed it, by the [Check] rule the borrowing class
 * declares; the first check that finds a change fails the test it runs in and names the test that
 * last held the world. A test that means to write asks for [privateCopy] instead.
 */
object SharedWorlds {

    /**
     * The retained worlds' arrays, in bytes, before one goes.
     *
     * A 512 world holds 39 MB of arrays and a 1024 one four times that, so this keeps the four
     * standard worlds with room for a dozen variants or three 1024 worlds. It is what a worker can
     * spare: the largest thing a per-merge worker does is generate a 2048 world, which stood at
     * 2.5 GB live at its peak (3.7 GB after a collection with 1.2 GB retained), so 600 MB keeps
     * that peak inside the 3.5 GB the root build script gives a `:worldgen` worker.
     */
    private const val RETAINED_ARRAY_BYTES = 600_000_000L

    /**
     * The largest world kept once its borrower is done with it, in cells.
     *
     * Larger worlds are generated for the test that asks and not retained: a 2048 world is 620 MB
     * of arrays, one class at a time uses it, and holding it past that class would crowd out a
     * dozen 512 worlds that many classes share.
     */
    private const val LARGEST_RETAINED_CELLS = 1024 * 1024

    internal val lender = WorldLender(
        generate = { config -> WorldGenerationEngine.generateBlocking(config) },
        retainedArrayBytes = RETAINED_ARRAY_BYTES,
        largestRetainedCells = LARGEST_RETAINED_CELLS
    )

    /**
     * The world [config] generates, shared: do not write to it.
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
     * Declared as a rule by every class that borrows a shared world:
     * `@get:Rule val sharedWorlds = SharedWorlds.Check()`.
     *
     * Before each test it names the borrower; after it, every shared world the class has borrowed so
     * far is checked against its fingerprint, and the test fails if one changed. Checked after every
     * test rather than once at the end of the run so that the failure lands on the test that wrote,
     * and so that it is the runner that reports it rather than a hook at JVM exit.
     */
    class Check : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val borrower = "${description.className}.${description.methodName}"
                    lender.beginTest(description.className, borrower)
                    var failure: Throwable? = null
                    try {
                        base.evaluate()
                    } catch (thrown: Throwable) {
                        failure = thrown
                    }
                    try {
                        lender.endTest(description.className, borrower)
                    } catch (changed: AssertionError) {
                        if (failure == null) throw changed
                        failure.addSuppressed(changed)
                    }
                    if (failure != null) throw failure
                }
            }
    }
}

/**
 * The fingerprint of one world: a digest of every branch of it, and a way to ask what has changed.
 *
 * See [ReachableState.digestsByBranch] for what a branch is and what the digest reads.
 */
class WorldGuard(private val world: WorldMap) {
    private val digests: Map<String, Long> = ReachableState.digestsByBranch(world)

    /** The branches whose contents differ from when this guard was made, in the world's order. */
    fun changedBranches(): List<String> {
        val now = ReachableState.digestsByBranch(world)
        return digests.keys.filter { now[it] != digests[it] } + now.keys.filter { it !in digests }
    }
}

/**
 * The cache behind [SharedWorlds], separate from it so its failure paths can be exercised on worlds
 * of their own without touching the shared one.
 *
 * Retains worlds up to [retainedArrayBytes] of arrays and never one larger than
 * [largestRetainedCells]. When it is full, the world to go is the least recently lent of those only
 * one class has asked for, and only when there are none of those the least recently lent of all:
 * most of what a tier generates is a variant one class asks for and nobody else, and a plain
 * least-recently-used rule would let a run of those push out the four standard worlds forty classes
 * share. Every world is checked when lent again and before it goes.
 */
class WorldLender(
    private val generate: (WorldGenConfig) -> WorldMap,
    private val retainedArrayBytes: Long,
    private val largestRetainedCells: Int
) {
    private class Loan(val config: WorldGenConfig, val world: WorldMap) {
        val guard = WorldGuard(world)
        val arrayBytes: Long = ReachableState.arrayBytes(world)
        var lastBorrower: String = ""
        var lastLentInTest = 0
        val borrowingClasses = HashSet<String>()
    }

    /** Access-ordered, so the first entry is the least recently lent. */
    private val loans = LinkedHashMap<WorldGenConfig, Loan>(16, 0.75f, true)
    private val loansByClass = HashMap<String, MutableSet<WorldGenConfig>>()
    private var retainedBytes = 0L
    private var activeClass: String? = null
    private var activeBorrower: String? = null

    /** Counts tests begun, so a loan can tell a second request from the same test run apart. */
    private var testsBegun = 0

    /** How many worlds this lender has generated, retained or not. */
    var generated = 0
        private set

    @Synchronized
    fun beginTest(testClass: String, borrower: String) {
        activeClass = testClass
        activeBorrower = borrower
        testsBegun++
    }

    /** Checks every retained world [testClass] has borrowed, failing on the first that changed. */
    @Synchronized
    fun endTest(testClass: String, borrower: String) {
        try {
            val borrowed = loansByClass[testClass].orEmpty().toList()
            for (config in borrowed) {
                val loan = loans[config] ?: continue
                requireUnchanged(loan, "after $borrower, which had borrowed it") { changed ->
                    "$borrower wrote to the shared world for ${describe(config)}: " +
                        "${changed.joinToString()} changed. A test that writes asks for " +
                        "SharedWorlds.privateCopy."
                }
            }
        } finally {
            activeClass = null
            activeBorrower = null
        }
    }

    /**
     * The world [config] generates: the retained one if there is one and it is unchanged, otherwise a
     * new one, retained if it is small enough.
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
            // The generation about to run is the largest thing a worker does, so the variants
            // nobody else has asked for make way for it first.
            loans.values.filter { it.borrowingClasses.size < 2 }.forEach { dropToMakeRoom(it, borrower) }
            generated++
            return generate(config).also { report("generated, too large to keep", config, borrower, started) }
        }
        val existing = loans[config]
        if (existing != null && existing.lastLentInTest == testsBegun) {
            // Asked for again by the test that already holds it — a getter read in a loop, say.
            // Nothing else has held it since it was last checked, and the check after this test
            // covers whatever this test does to it, so it is handed straight back.
            return existing.world
        }
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
        loan.lastLentInTest = testsBegun
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

    private fun admit(loan: Loan, borrower: String) {
        while (retainedBytes + loan.arrayBytes > retainedArrayBytes && loans.isNotEmpty()) {
            dropToMakeRoom(loans.values.firstOrNull { it.borrowingClasses.size < 2 } ?: loans.values.first(), borrower)
        }
        loans[loan.config] = loan
        retainedBytes += loan.arrayBytes
    }

    /** Checks [leaving] one last time and lets it go, failing [borrower]'s test if it changed. */
    private fun dropToMakeRoom(leaving: Loan, borrower: String) {
        requireUnchanged(leaving, "before it was dropped to make room for $borrower's") { changed ->
            "the shared world for ${describe(leaving.config)} was written to while " +
                "${leaving.lastBorrower} held it: ${changed.joinToString()} changed. Found " +
                "when it was dropped to make room, so $borrower fails in its place."
        }
        forget(leaving)
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

    private fun describe(config: WorldGenConfig): String {
        val defaults = WorldGenConfig(seed = config.seed, width = config.width, height = config.height)
        val same = if (config == defaults) "default settings" else "non-default settings"
        return "seed ${config.seed} at ${config.width}x${config.height}, $same"
    }
}
