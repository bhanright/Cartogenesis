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
 * at once; four gigabytes is what `:desktop` gives the same work, less the export sizes.
 */
tasks.withType<Test>().configureEach {
    maxHeapSize = "4g"
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
 */
val auditOnlyClasses = listOf(
    "com.cartogenesis.cartography.W4RenderDump",
    "com.cartogenesis.cartography.F30bRenderDump",
    "com.cartogenesis.cartography.I3RenderDump",
    "com.cartogenesis.cartography.R1RenderDump",
    "com.cartogenesis.cartography.S3RenderDump"
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
    val poolThreads = budget["lightTestPoolThreads"] as Int
    jvmArgs(
        "-Djava.util.concurrent.ForkJoinPool.common.parallelism=$poolThreads"
    )
    mustRunAfter(tasks.matching { it.name.contains("WasmJs", ignoreCase = true) && !it.name.endsWith("Test") })
}

tasks.register<Test>("audit") {
    group = "verification"
    description = "Runs the on-demand / nightly audit tier: the render harnesses excluded from jvmTest."
    val jvmTestTask = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTestTask.testClassesDirs
    classpath = jvmTestTask.classpath
    filter {
        auditOnlyClasses.forEach { includeTestsMatching(it) }
        isFailOnNoMatchingTests = true
    }
}
