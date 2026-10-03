plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

/*
 * The per-merge tier's share of the machine: how many test workers each JVM test task forks, how
 * many processors each worker may use, and how much heap, decided here because they are one budget.
 *
 * With `org.gradle.parallel` the JVM test tasks of the tier run side by side, so what they hold at
 * once is the sum over all of them, not any one task's figure. Every JVM sizes itself to the whole
 * processor — the common `ForkJoinPool` generation parallelises through, the garbage collector's
 * threads, the compiler's — so left alone seven workers would each behave as though they had the
 * machine to themselves. Each worker is told how many processors are its share
 * (`-XX:ActiveProcessorCount`, which sizes all three) and its pool is set to the same width. On a
 * sixteen-thread, 32 GB machine:
 *
 *   :worldgen:jvmTest     4 workers x 2 processors, 3.5 GB heap each    8 threads, 14 GB
 *   :desktop:test         1 worker  x 6 processors, 3 GB heap           6 threads,  3 GB
 *   :desktop:siteTest     1 worker, 0.5 GB heap, only after :desktop:test (one project, one lock)
 *   :cartography:jvmTest  1 worker  x 1 processor, 2 GB heap            1 thread,   2 GB
 *   :ui:jvmTest           1 worker  x 1 processor, 0.5 GB heap          1 thread,   0.5 GB
 *   the workers' memory outside their heaps, measured                               1.9 GB
 *   the browser tests' Node and headless Chrome, measured                           0.6 GB
 *   the Gradle daemon and its launcher, and the compiler's daemon idle, measured    1.4 GB
 *                                                                      16 threads, 23.4 GB
 *
 * The workers are counted at their ceilings, because they reach them: a busy JVM's heap grows to
 * its `-Xmx`. The two daemons are counted at what they were measured holding while the tier ran,
 * not at their 4 GB ceilings, because during the tier they schedule and do not compile; a run that
 * compiles first holds the compiler's daemon larger for the minutes it compiles. The tier does not
 * reach that sum at any one moment either: cartography's suite, the interface's and the browser's
 * finish in the first few minutes, while `:worldgen`'s heaps are still growing; the whole build's
 * measured peak, and what one worker cost against it, are in the ledger's T5 row. 23.4 GB leaves
 * 8.6 of the machine's 32 to whatever else it is doing, and on a machine with a record of memory
 * faults under load no worker is given more than its measured peak with room over it: the 2048
 * worlds two per-merge guards generate need the whole 3.5 GB of the worker that draws them.
 *
 * Generation is mostly serial — a lone worker with a pool as wide as the machine kept it under a
 * quarter busy — so `:worldgen`'s many classes are spread over narrow workers, and `:desktop`'s,
 * which cannot be spread (see its build script), get a wide one: its 2048 worlds are the part of
 * generation that does parallelise. The figures scale down with the machine. Below twelve hardware
 * threads there is too little to share out, so a task's one worker takes the whole processor, as a
 * CI runner that runs one task at a time wants.
 *
 * `-PtestWorkers=1` puts `:worldgen`'s classes back in one worker, for a failure that depends on
 * which classes shared one.
 */
val hardwareThreads = Runtime.getRuntime().availableProcessors()
val machineIsShared = hardwareThreads >= 12
val worldgenTestForks = providers.gradleProperty("testWorkers").map { it.toInt() }
    .getOrElse((hardwareThreads / 4).coerceIn(1, 4))
extra["worldgenTestForks"] = worldgenTestForks
extra["worldgenTestProcessors"] =
    if (machineIsShared) (hardwareThreads / 2 / worldgenTestForks).coerceAtLeast(2) else hardwareThreads
extra["worldgenTestHeap"] = "3584m"
extra["desktopTestProcessors"] = if (machineIsShared) hardwareThreads * 3 / 8 else hardwareThreads
extra["desktopTestHeap"] = "3g"
extra["cartographyTestHeap"] = "2g"
extra["lightTestProcessors"] = if (machineIsShared) (hardwareThreads / 16).coerceAtLeast(1) else hardwareThreads

/*
 * The audit tier's share: one audit task at a time, each with the machine to itself.
 *
 * Its heaps were sized for 2048 and 4096 worlds — `:worldgen` 8 GB, `:cartography` 8 GB, `:desktop`
 * 10 GB — and with `org.gradle.parallel` the three tasks would otherwise run together: 26 GB of
 * heap ceilings with the 4 GB daemon's beside them, on a 32 GB machine, and the 16 GB runner the
 * nightly uses has been taken down twice by two of them together. In this order, one after
 * another, the most held at once is `:desktop`'s 10 GB and the daemon's. Each task's pool is set to
 * the machine less the one thread the daemon and the operating system need, which is what a lone
 * JVM would choose for itself, stated here so that it is a decision rather than a default.
 */
val auditTasksInOrder = listOf(":worldgen:audit", ":cartography:audit", ":desktop:audit")
val auditPoolThreads = (hardwareThreads - 1).coerceAtLeast(1)

/**
 * The test tiers' timing report, printed once at the end of any build that ran tests: each test
 * task's wall time, the whole run's, and the slowest classes, so a change that makes the tier slower
 * shows in the log of the run that made it.
 *
 * A task's wall time is read off its root suite, which opens when its first class starts and closes
 * when its last finishes, so parallel workers count once. A class's time is its own suite's, and
 * with workers side by side the classes of a task add up to more than its wall time; both are
 * printed so the difference shows.
 */
abstract class TestTimingReport : BuildService<BuildServiceParameters.None>, AutoCloseable {

    private class ClassTiming(val taskPath: String, val className: String, val millis: Long)

    private class TaskTiming(
        val startMillis: Long,
        val endMillis: Long,
        val tests: Long,
        val failed: Long,
        val skipped: Long
    )

    private val classes = java.util.concurrent.ConcurrentLinkedQueue<ClassTiming>()
    private val tasks = java.util.concurrent.ConcurrentHashMap<String, TaskTiming>()

    fun recordClass(taskPath: String, className: String, millis: Long) {
        classes.add(ClassTiming(taskPath, className, millis))
    }

    fun recordTask(taskPath: String, result: TestResult) {
        tasks[taskPath] = TaskTiming(
            result.startTime, result.endTime,
            result.testCount, result.failedTestCount, result.skippedTestCount
        )
    }

    override fun close() {
        if (tasks.isEmpty()) return
        val firstStart = tasks.values.minOf { it.startMillis }
        val lastEnd = tasks.values.maxOf { it.endMillis }
        val report = StringBuilder()
        report.appendLine()
        report.appendLine(
            "Test timing: ${tasks.size} test tasks, ${clock(lastEnd - firstStart)} from the first " +
                "class starting to the last one finishing"
        )
        for ((path, task) in tasks.entries.sortedBy { it.value.startMillis }) {
            val taskClasses = classes.filter { it.taskPath == path }
            report.appendLine(
                "  %-26s %8s wall, from %7s in; %3d classes, %8s in classes; %d tests, %d failed, %d skipped"
                    .format(
                        path, clock(task.endMillis - task.startMillis),
                        clock(task.startMillis - firstStart), taskClasses.size,
                        clock(taskClasses.sumOf { it.millis }), task.tests, task.failed, task.skipped
                    )
            )
        }
        report.appendLine("  Slowest classes:")
        for (timing in classes.sortedByDescending { it.millis }.take(SLOWEST_CLASSES_SHOWN)) {
            report.appendLine("    %8s  %-24s %s".format(clock(timing.millis), timing.taskPath, timing.className))
        }
        println(report)
    }

    private fun clock(millis: Long): String {
        val seconds = millis / 1000
        return if (seconds >= 60) "%dm %02ds".format(seconds / 60, seconds % 60)
        else "%d.%ds".format(seconds, millis % 1000 / 100)
    }

    private companion object {
        /** Enough to show every class that costs a noticeable share of a tier, and no more. */
        const val SLOWEST_CLASSES_SHOWN = 15
    }
}

val testTimingReport =
    gradle.sharedServices.registerIfAbsent("testTimingReport", TestTimingReport::class.java) {}

/*
 * Record mode for the suites' pinned figures (`PinRecords` in the shared test support): with
 * `-Precord`, a known failure's signature, a geometry census entry or a render record that no
 * longer holds is written to `build/pin-records/<task>` as its replacement instead of failing, and
 * `:worldgen:applyPinRecords` writes the replacements into the source for review as a diff. The
 * records are cleared before each task runs, so they are always one run's.
 */
val recordingPins = providers.gradleProperty("record").map { it != "false" }.getOrElse(false)

/*
 * The test tiers' world cache (`WorldDiskCache` in the shared test support): every world a test
 * borrows from `SharedWorlds` is kept here once made, keyed by its settings and a hash of the
 * generator's compiled classes, and read back by any later worker or run instead of being generated
 * again. One directory for the three modules that generate worlds, since they ask for the same
 * standard ones. Under `build/`, so it is never committed and a clean removes it.
 *
 * The cap is 20 GB unless `-PworldCacheGigabytes` says otherwise. Measured on the tiers at T1: the
 * everyday tier's 13 standard worlds take 0.75 GB, and the deep tier's 194 more 16.2 GB, 6.8 GB of
 * that its 21 worlds of 1,024 rows. Past the cap the least recently used variants go first and the
 * standard worlds last, so a deep run never costs the next everyday run its worlds. `-PworldCache=off` generates every
 * world as before, for a run that should not trust the cache; `clearWorldCache` empties it.
 */
val worldCacheDirectory = layout.buildDirectory.dir("world-cache").get().asFile
val worldCacheOn = providers.gradleProperty("worldCache").map { it != "off" }.getOrElse(true)
val worldCacheBytes = providers.gradleProperty("worldCacheGigabytes").map { it.toLong() }.getOrElse(20L) * 1_000_000_000L

/*
 * The deep tier's stages, and what each one reaches downstream (docs/PIPELINE.md). The everyday tier
 * (`jvmTest`, and `:desktop:test`) reads only standard worlds: default settings, at 512 rows or
 * fewer, one grid per seed. The deep tier (`deepTest` in `:worldgen`, `:cartography` and
 * `:desktop`) holds the classes, and the methods of classes that mix the two, that build their own
 * variants for an on/off control, compare grids, or build worlds of 1,024 rows or more; each
 * module's build script lists them by the stage they guard.
 *
 * `-Pstages=climate,ocean` runs the deep classes of those stages and of every stage they reach, so
 * a change is followed as far as it can move a world. The reach includes the pipeline's loops: the
 * climate runs inside erosion (the climate feed, over a still ocean) and inside the sea level (the
 * ice's snow balance), so a change to the climate or the ocean reaches back to erosion and on down.
 * `engine` (reuse, stopping, the whole pipeline at several grids) and `drawing` read every stage's
 * results, so every selection reaches them. Without `-Pstages`, every deep class runs.
 */
val pipelineReach: Map<String, List<String>> = mapOf(
    "terrain" to listOf("plates"),
    "plates" to listOf("erosion"),
    "erosion" to listOf("sea"),
    "sea" to listOf("ocean", "climate", "rivers"),
    "ocean" to listOf("climate", "erosion"),
    "climate" to listOf("rivers", "realms", "peoples", "landmarks", "erosion", "sea"),
    "rivers" to listOf("realms", "peoples", "landmarks"),
    "realms" to listOf("landmarks"),
    "peoples" to emptyList(),
    "landmarks" to emptyList(),
    "engine" to emptyList(),
    "drawing" to emptyList()
)

val deepStagesAsked: Set<String>? = providers.gradleProperty("stages").orNull?.let { asked ->
    val named = asked.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val unknown = named.filter { it !in pipelineReach }
    require(unknown.isEmpty()) {
        "-Pstages names ${unknown.joinToString()}, which is no stage; the stages are ${pipelineReach.keys.joinToString()}"
    }
    val reached = LinkedHashSet<String>()
    val waiting = ArrayDeque(named)
    while (waiting.isNotEmpty()) {
        val stage = waiting.removeFirst()
        if (reached.add(stage)) waiting.addAll(pipelineReach.getValue(stage))
    }
    reached + setOf("engine", "drawing")
}

/** The stages whose deep classes this build runs: those `-Pstages` names and what they reach, or all. */
extra["deepStages"] = deepStagesAsked ?: pipelineReach.keys
extra["deepStagesLimited"] = deepStagesAsked != null

tasks.register<Delete>("clearWorldCache") {
    group = "verification"
    description = "Deletes every world the test tiers have cached on disk."
    delete(worldCacheDirectory)
}

subprojects {
    if (worldCacheOn) {
        tasks.withType<Test>().configureEach {
            systemProperty("cartogenesis.worldCache.directory", worldCacheDirectory.absolutePath)
            systemProperty("cartogenesis.worldCache.capacityBytes", worldCacheBytes.toString())
        }
    }

    if (recordingPins) {
        tasks.withType<Test>().configureEach {
            val records = layout.buildDirectory.dir("pin-records/$name").get().asFile
            systemProperty("cartogenesis.record", "true")
            systemProperty("cartogenesis.recordDirectory", records.absolutePath)
            doFirst { records.deleteRecursively() }
        }
    }

    tasks.withType<Test>().matching { it.path in auditTasksInOrder }.configureEach {
        mustRunAfter(auditTasksInOrder.takeWhile { it != path })
        jvmArgs("-Djava.util.concurrent.ForkJoinPool.common.parallelism=$auditPoolThreads")
    }

    tasks.withType<AbstractTestTask>().configureEach {
        usesService(testTimingReport)
        val taskPath = path
        val report = testTimingReport
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}
            override fun beforeTest(testDescriptor: TestDescriptor) {}
            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                val className = suite.className
                when {
                    suite.parent == null -> report.get().recordTask(taskPath, result)
                    className != null && suite.parent?.className != className ->
                        report.get().recordClass(taskPath, className, result.endTime - result.startTime)
                }
            }
        })
    }
}
