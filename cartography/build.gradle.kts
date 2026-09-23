import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Turning a generated world into a picture, without depending on any graphics toolkit.
 *
 * The per-pixel work — hypsometric tints, biome wash, relief shading, coastlines, borders — is
 * plain integer maths over an IntArray, so it is identical on every platform and belongs here.
 * The vector overlays are handled by describing them as geometry rather than drawing them, which
 * leaves each platform with only the drawing calls to implement and keeps the decisions shared.
 */
kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { nodejs() }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(project(":worldgen"))
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // The shared worlds and their check, compiled into this module's JVM tests as well; see
        // `:worldgen`'s build script for why the files are shared rather than depended on.
        named("jvmTest") {
            kotlin.srcDir(rootProject.layout.projectDirectory.dir("worldgen/src/sharedTestSupport/kotlin"))
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

/**
 * A 1024 world and the pictures drawn from it do not fit in a test worker's default half gigabyte.
 * `WorldLibraryTest` saves one, and the drawing guards hold a 512 world and several rasters of it
 * at once; four gigabytes is what `:desktop` gives the same work, less the export sizes. The
 * per-merge suite takes its heap from the root build script's budget instead: see below.
 */
tasks.withType<Test>().configureEach {
    // Except the audit tier, which holds worlds at 2048 and the geometry census's layers: see below.
    maxHeapSize = if (name == "audit") "8g" else "4g"
}

/*
 * The geometry guard's known failures: clauses that fail today and are kept running under the
 * name of the finding each records (`KnownFailures` in the tests), and the clauses too small to
 * measure on today's worlds. Each test task hands the tests a file to append them to, clears it
 * before it runs and prints it once the whole task has run, pass or fail — so the list of what is
 * known to be wrong, and of what the tier could not see, is at the foot of every tier's output.
 */
tasks.withType<Test>().configureEach {
    val report = layout.buildDirectory.file("known-failures/$name.txt").get().asFile
    systemProperty("cartogenesis.knownFailures", report.absolutePath)
    doFirst { report.delete() }
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ suite, _ ->
        if (suite.parent == null && report.exists()) {
            val lines = report.readLines()
            val known = lines.count { it.startsWith("KNOWN FAILURE") }
            val insufficient = lines.count { it.startsWith("INSUFFICIENT") }
            println("Known failures in $name ($known), and clauses too small to measure ($insufficient):")
            lines.forEach { println("  $it") }
        }
    }))
}

/**
 * This module's audit tier, split from the per-merge one by class name the way `:worldgen` and
 * `:desktop` split theirs (see `:worldgen`'s build script for why by name).
 *
 * The render harnesses, none of which asserts anything. W4's is the one that cost: five worlds,
 * two of them at 2048 and each generated twice, for pictures a person judges, six minutes of every
 * merge until T5. F30b's, I3's, R1's and S3's only draw when their environment variable is set
 * and skip or return otherwise; they are here because a render harness is an audit's job
 * whatever it costs, so that the per-merge list is guards and nothing else. The two classes that
 * regenerate checked-in fixtures on request stay where they are: they are tools, not measurements.
 *
 * And the geometry guard's census at 2048: seven worlds, each generated with its layers captured
 * and every layer read by every detector, and each paired with the same world at 1024 wherever a
 * ruled comb is found. It is one world at a time and wants the heap a 2048 world does.
 */
val auditOnlyClasses = listOf(
    "com.cartogenesis.cartography.W4RenderDump",
    "com.cartogenesis.cartography.F30bRenderDump",
    "com.cartogenesis.cartography.I3RenderDump",
    "com.cartogenesis.cartography.R1RenderDump",
    "com.cartogenesis.cartography.S3RenderDump",
    "com.cartogenesis.cartography.geometry.GeometryGuardAuditTest"
)

/*
 * The per-merge suite's heap and pool are the root build script's budget, and it waits for this
 * project's Wasm tasks for the same project-lock reason `:worldgen`'s build script gives.
 */
tasks.named<Test>("jvmTest") {
    filter {
        auditOnlyClasses.forEach { excludeTestsMatching(it) }
    }
    val budget = rootProject.extra
    maxHeapSize = budget["cartographyTestHeap"] as String
    val processors = budget["lightTestProcessors"] as Int
    jvmArgs(
        "-XX:ActiveProcessorCount=$processors",
        "-Djava.util.concurrent.ForkJoinPool.common.parallelism=$processors"
    )
    mustRunAfter(tasks.matching { it.name.contains("WasmJs", ignoreCase = true) && !it.name.endsWith("Test") })
}

tasks.register<Test>("audit") {
    group = "verification"
    description = "Runs the on-demand / nightly audit tier: the render harnesses excluded from " +
        "jvmTest, and the 2048-scale geometry census."
    val jvmTestTask = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTestTask.testClassesDirs
    classpath = jvmTestTask.classpath
    filter {
        auditOnlyClasses.forEach { includeTestsMatching(it) }
        isFailOnNoMatchingTests = true
    }
}
