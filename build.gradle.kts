plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

/*
 * The per-merge tier's share of the machine: how many test workers each JVM test task forks, how
 * wide each worker's thread pool is, and how much heap, decided here because they are one budget.
 *
 * With `org.gradle.parallel` the four JVM test tasks of the tier run side by side, so what they
 * hold at once is the sum over all of them, not any one task's figure. Generation parallelises
 * through the common `ForkJoinPool`, which sizes itself to the whole processor in every JVM that
 * starts one, so left alone seven workers would each run a pool as wide as the machine. Each
 * worker's pool is given its share instead, and the shares add up to the hardware threads:
 *
 *   :worldgen:jvmTest     4 workers x 2 threads, 3.5 GB heap each     8 threads, 14 GB
 *   :desktop:test         1 worker  x 6 threads, 3 GB heap            6 threads,  3 GB
 *   :cartography:jvmTest  1 worker  x 1 thread,  2 GB heap            1 thread,   2 GB
 *   :ui:jvmTest           1 worker  x 1 thread,  0.5 GB heap          1 thread,   0.5 GB
 *   the Gradle daemon (org.gradle.jvmargs)                                        4 GB
 *                                                                    16 threads, 23.5 GB
 *
 * on a sixteen-thread, 32 GB machine, which leaves the rest of its memory to whatever else it is
 * doing. Generation is mostly serial — a lone worker with a pool as wide as the machine kept it
 * under a quarter busy — so `:worldgen`'s many classes are spread over four narrow workers, and
 * `:desktop`'s, which cannot be spread (see its build script), get a wide one: its 2048 worlds are
 * the part of generation that does parallelise. The collector's and the compiler's threads are
 * left to the JVM: capping them too (`-XX:ActiveProcessorCount`) was tried and made the tier
 * slower, because they work in short bursts on a processor that is idle half the time. The heaps
 * are each worker's measured peak with room over it; see the ledger's T5 row. The figures scale
 * down with the machine, never below one worker and two threads for a generating worker, so a
 * four-core runner gets one `:worldgen` worker rather than four contending for it.
 *
 * The audit tier is not in this budget: it runs on its own, one worker per task, with the heap
 * its 2048 and 4096 cases were sized for.
 *
 * `-PtestWorkers=1` puts `:worldgen`'s classes back in one worker, for a failure that depends on
 * which classes shared one.
 */
val hardwareThreads = Runtime.getRuntime().availableProcessors()
extra["worldgenTestForks"] = providers.gradleProperty("testWorkers").map { it.toInt() }
    .getOrElse((hardwareThreads / 4).coerceIn(1, 4))
extra["worldgenTestPoolThreads"] = (hardwareThreads / 8).coerceAtLeast(2)
extra["worldgenTestHeap"] = "3584m"
extra["desktopTestPoolThreads"] = (hardwareThreads * 3 / 8).coerceAtLeast(2)
extra["desktopTestHeap"] = "3g"
extra["cartographyTestHeap"] = "2g"
extra["lightTestPoolThreads"] = (hardwareThreads / 16).coerceAtLeast(1)

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
