import org.gradle.api.tasks.testing.Test

/*
 * Writes down which test classes the audit tier is about to run, before any of them starts.
 *
 * The nightly's summary (`.github/scripts/audit_summary.py`) holds the result files a run left
 * against this list, so a class that never wrote one - a worker killed part-way through, or a
 * project's tier that Gradle never started - is reported as not run instead of dropping silently
 * out of a green count. The list is each `audit` task's own include filter, read once the task
 * graph is fixed, so it is whatever the build scripts declare and cannot drift from them.
 *
 * Written to `build/audit-tier-plan.tsv` under the root project, one line per filter entry: the
 * task's path, the directory its JUnit XML goes to (relative to the root, with forward slashes)
 * and the class name, separated by tabs.
 */
gradle.taskGraph.whenReady {
    val root = gradle.rootProject.layout.projectDirectory.asFile
    val planned = allTasks
        .filterIsInstance<Test>()
        .filter { it.name == "audit" }
        .flatMap { audit ->
            val resultsDirectory = audit.reports.junitXml.outputLocation.get().asFile
                .relativeTo(root).invariantSeparatorsPath
            audit.filter.includePatterns.sorted().map { className ->
                listOf(audit.path, resultsDirectory, className).joinToString("\t")
            }
        }
    // Left alone when no audit task is in the graph, so a build of something else run with this
    // script cannot replace the tier's plan with an empty one.
    if (planned.isNotEmpty()) {
        val plan = gradle.rootProject.layout.buildDirectory.file("audit-tier-plan.tsv").get().asFile
        plan.parentFile.mkdirs()
        plan.writeText(planned.joinToString("\n", postfix = "\n"))
    }
}
