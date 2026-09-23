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
 *   the workers' memory outside their heaps, measured                               1.7 GB
 *   the browser tests' Node and headless Chrome, measured                           0.6 GB
 *   the Gradle daemon and the Kotlin compiler's daemon, measured                    1.4 GB
 *                                                                      16 threads, 23.2 GB
 *
 * The workers are counted at their ceilings, because they reach them: a busy JVM's heap grows to
 * its `-Xmx`. The two daemons are counted at what they were measured holding while the tier ran,
 * not at their 4 GB ceilings, because during the tier they schedule and do not compile; a run that
 * compiles first holds the compiler's daemon larger for the minutes it compiles. The tier does not
 * reach that sum at any one moment either: cartography's suite, the interface's and the browser's
 * finish in the first few minutes, while `:worldgen`'s heaps are still growing; the whole build's
 * measured peak, and what one worker cost against it, are in the ledger's T5 row. 23 GB leaves 9 of
 * the machine's 32 to whatever else it is doing, and on a machine with a record of memory faults
 * under load no worker is given more than its measured peak with room over it.
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

subprojects {
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
