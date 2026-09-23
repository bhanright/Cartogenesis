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
    // Except the audit tier, which holds a 2048 world and its layers: see below.
    maxHeapSize = if (name == "audit") "8g" else "4g"
}

/*
 * The geometry guard's known failures: clauses that fail today and are kept running under the
 * name of the finding each records (`KnownFailures` in the tests). Each test task hands the tests
 * a file to append them to, clears it before it runs and prints it once the whole task has run,
 * pass or fail — so the list of what is known to be wrong is at the foot of every tier's output.
 */
tasks.withType<Test>().configureEach {
    val report = layout.buildDirectory.file("known-failures/$name.txt").get().asFile
    systemProperty("cartogenesis.knownFailures", report.absolutePath)
    doFirst { report.delete() }
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ suite, _ ->
        if (suite.parent == null && report.exists()) {
            val known = report.readLines()
            println("Known failures in $name (${known.size}):")
            known.forEach { println("  $it") }
        }
    }))
}

/*
 * The audit tier, as `:worldgen` and `:desktop` have it: classes too heavy for every merge, run on
 * demand and nightly. The geometry guard's census at 2048 — seven worlds, each generated with its
 * layers captured and every layer read by every detector, and each paired with the same world at
 * 1024 wherever a ruled comb is found — is one world at a time and wants the heap a 2048 world
 * does, as `:worldgen`'s tests have it.
 */
val auditOnlyClasses = listOf(
    "com.cartogenesis.cartography.geometry.GeometryGuardAuditTest"
)

tasks.named<Test>("jvmTest") {
    filter {
        auditOnlyClasses.forEach { excludeTestsMatching(it) }
    }
}

tasks.register<Test>("audit") {
    group = "verification"
    description = "Runs the on-demand / nightly audit tier: the 2048-scale geometry census."
    val jvmTestTask = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTestTask.testClassesDirs
    classpath = jvmTestTask.classpath
    filter {
        auditOnlyClasses.forEach { includeTestsMatching(it) }
        isFailOnNoMatchingTests = true
    }
}
