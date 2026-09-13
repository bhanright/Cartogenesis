import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The generation engine is plain Kotlin with no platform dependencies, so it is built for the JVM
 * (which the Android app consumes) and for the browser via Wasm.
 *
 * `commonTest` holds the correctness suite and runs on every target â€” which is what proves the
 * engine really is portable, rather than merely compiling. `jvmTest` holds `DebugMapDump`, which
 * renders PNGs through `java.awt` and so cannot be shared.
 *
 * There used to be a Kotlin/JS target too, kept only for reference: Kotlin/JS routes sin/cos/pow
 * through JavaScript's Math, whose results differ from the JVM in the last bit, which compounds
 * through the FFT and fails the resolution-consistency test, where Kotlin/Wasm matches the JVM
 * exactly. Nothing consumed it once the web build moved to Wasm, so T1 removed it (2026-09-12).
 */
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            // Config classes carry @Serializable so the save format is derived from them directly.
            // Mirroring them into hand-written DTOs would mean every new setting had to be added
            // in two places, and would silently drop from saves when someone forgot.
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Erosion and the FFT both hold several grid-sized float buffers at once, and the profiling and
// audit tests run the pipeline at export resolutions. The default test heap cannot take 2048.
tasks.withType<Test>().configureEach {
    maxHeapSize = "8g"
}

/*
 * T1: two tiers of test, split by class name rather than by `@Tag`.
 *
 * `jvmTest` runs on the JUnit4 vintage runner (`kotlin("test-junit")` above), which has no `@Tag`
 * — that is a JUnit5 idea, its own equivalent is `@Category`, and wiring `useJUnit { excludeCategories
 * (...) }` through a marker interface is more machinery than a plain class-name filter for a
 * fixed, known list of classes. `:desktop:test` runs on JUnit5, but the same filter mechanism
 * works unchanged there too, so one approach covers both runners instead of two.
 *
 * A generic suffix convention (e.g. every class ending `AuditTest`) was considered and rejected:
 * `GeographyAuditTest` already carries that name for an unrelated reason — the desert-in-band
 * audit — and is one of the fast, per-merge guards, not one of these. So the classes below are
 * named explicitly rather than matched by a pattern that would also catch it.
 *
 * Heavy, whole classes moved to the audit tier: `DebugMapDump` (the render harness, 259s, always
 * run with `--rerun` anyway), `StageProfileTest` (158s), `GenerationSpeedTest`, `DesertCauseTest`,
 * `ColdCapReportTest` and `ErosionConvergenceTest` (55s together — the last of those asserts
 * thread-splitting that fails on CI's small runners). `GlaciationAuditTest` and
 * `RealmIdRangeAuditTest` are new classes holding just the 2048-scale cases split out of
 * `GlaciationTest` and `RealmIdRangeTest`; their 512/1024 siblings stay in the per-merge classes.
 * `LakeWaterBalanceTest` has no 2048-scale case today (only comments describing one), so nothing
 * moved out of it — noted rather than invented.
 */
val auditOnlyClasses = listOf(
    "com.cartogenesis.worldgen.DebugMapDump",
    "com.cartogenesis.worldgen.StageProfileTest",
    "com.cartogenesis.worldgen.GenerationSpeedTest",
    "com.cartogenesis.worldgen.DesertCauseTest",
    "com.cartogenesis.worldgen.ColdCapReportTest",
    "com.cartogenesis.worldgen.ErosionConvergenceTest",
    "com.cartogenesis.worldgen.GlaciationAuditTest",
    "com.cartogenesis.worldgen.RealmIdRangeAuditTest",
    // H1's cost report: sixteen runs of the tectonic stage at 1024 and 2048, reported rather than
    // asserted. Its guards run at 512 and stay in `TectonicHistoryTest`.
    "com.cartogenesis.worldgen.TectonicHistoryAuditTest",
    // H2's cost report, which builds a 2048 world's terrain to time the balance on it.
    "com.cartogenesis.worldgen.SnowBalanceAuditTest",
    // E6: the one measurement that separates a graded floodplain from a flat one does it in the
    // author's own valley at 2048, and nowhere else. `BayHeadDeltaTest` holds the four that were
    // written for the whole world at 1024 and could not, and is here rather than in the per-merge
    // tier because it asserts nothing: six 1024 worlds for a printed report is an audit's job.
    "com.cartogenesis.worldgen.BayHeadDeltaAuditTest",
    "com.cartogenesis.worldgen.BayHeadDeltaTest",
    // E7: whether a rift floor of sub-basins holds more water than E4's smooth wedge can only be
    // answered in the author's own trough at 2048 — `RiftDepthTest` measures the same question at
    // 512 and records that nothing there can. Two 2048 worlds for one comparison is an audit's job.
    "com.cartogenesis.worldgen.RiftDepthAuditTest",
    // F18: the network statistics and the moved-cell tally need every world generated twice, once
    // under each routing rule — ten worlds, a pair of them at 1024. The census that guards the
    // chunk is in `StraightRunTest` and runs per merge.
    "com.cartogenesis.worldgen.StraightRunAuditTest"
)

tasks.named<Test>("jvmTest") {
    filter {
        auditOnlyClasses.forEach { excludeTestsMatching(it) }
    }
}

tasks.register<Test>("audit") {
    group = "verification"
    description = "Runs the on-demand / nightly audit tier: renders, profiles, reports and the " +
        "2048-scale cases excluded from jvmTest."
    val jvmTestTask = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTestTask.testClassesDirs
    classpath = jvmTestTask.classpath
    filter {
        auditOnlyClasses.forEach { includeTestsMatching(it) }
        isFailOnNoMatchingTests = true
    }
}
