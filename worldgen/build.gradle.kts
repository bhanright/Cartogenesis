import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The generation engine is plain Kotlin with no platform dependencies, built for the JVM, which the
 * desktop application runs it on.
 *
 * Multiplatform with one target. The engine was built for the browser through Kotlin/Wasm as well
 * until the browser build was removed (docs/DESIGN_LEDGER.md, G1). The source sets keep their shape:
 * `commonTest` holds the correctness suite and `jvmTest` what reads files or renders through
 * `java.awt`, so the engine stays free of platform code even with one platform to run on.
 */
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
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
        // The worlds the JVM test suites share and the check that none of them is written to. A
        // directory of its own rather than a source set of this module's, because `:cartography`
        // and `:desktop` compile the same files into their own tests: each test JVM holds its own
        // worlds, so what is shared is the code, and a project dependency on another module's
        // tests would put two copies of this module's classes on the path.
        named("jvmTest") { kotlin.srcDir("src/sharedTestSupport/kotlin") }
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
 * The re-armed guards' known failures: clauses that fail today on a defect an audit found and are
 * kept running under the name of that finding (`KnownFailures` in the tests, the twin of
 * `:cartography`'s). Each test task hands the tests a file to append them to,
 * clears it before it runs and prints it once the whole task has run, pass or fail, as
 * `:cartography`'s build script does for the geometry guard — so what is known to be wrong is at
 * the foot of every tier's output.
 */
tasks.withType<Test>().configureEach {
    val report = layout.buildDirectory.file("known-failures/$name.txt").get().asFile
    systemProperty("cartogenesis.knownFailures", report.absolutePath)
    doFirst { report.delete() }
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ suite, _ ->
        if (suite.parent == null && report.exists()) {
            val lines = report.readLines()
            println("Known failures in $name (${lines.count { it.startsWith("KNOWN FAILURE") }}):")
            lines.forEach { println("  $it") }
        }
    }))
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
 * `ColdCapReportTest` and `ErosionConvergenceTest` (55s together; the last of those asserts the
 * thread split only where there is more than one worker to split across). `GlaciationAuditTest` and
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
    // F30b's discriminating case: 718106 at 2048 twice, once over the potential and once over the
    // fill's staircase. Its invariants and cost run at 512 and stay in `FlatCourseTest`.
    "com.cartogenesis.worldgen.FlatCourseAuditTest",
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
    // M1: the Earth-likeness suite at export resolution. Six worlds at 2048, for the decade more
    // of lakes, islands and first-order streams a finer grid resolves and for the cell area the
    // lake share of land is compared at. `EarthLikenessTest` holds the same metrics at 512 and
    // `EarthLikenessControlTest` the synthetic worlds each bar is shown to bite on; both are
    // per-merge and cost a few seconds between them.
    "com.cartogenesis.worldgen.EarthLikenessAuditTest",
    // S1: the scale-free suite's third grid. `ScaleFreeTest` holds 512 against 1024 per
    // merge; four worlds at 2048 is four minutes of erosion for one more octave of lever.
    "com.cartogenesis.worldgen.ScaleFreeAuditTest",
    // The ocean chunk's sense bars on the author's 2048 world. The four standard seeds at 512 are
    // in `OceanCurrentTest`, per merge.
    "com.cartogenesis.worldgen.OceanCurrentAuditTest",
    // S2: what the flexure costs at 2048 and 4096, which is rule 8's question and needs a
    // 4096-cell transform pair to answer. `IsostasyTest` holds every guard at 512 and 256.
    "com.cartogenesis.worldgen.IsostasyAuditTest",
    // F17's diagnosis: five seeds cut seven ways to find which rule roughens every coast, which
    // means fifteen runs of erosion for a printed table. Its guards run at 512 in
    // `LittoralCoastTest` and stay in the per-merge tier.
    "com.cartogenesis.worldgen.CoastVarietyAuditTest",
    // W1's renders: the author's two worlds at 2048 in three views apiece, whole and cropped.
    // A harness like `DebugMapDump`, and asserts nothing.
    "com.cartogenesis.worldgen.W1RenderDump",
    // S2b's renders: the author's two worlds at 2048, whole and at each pole, before and after.
    // A harness like `DebugMapDump`, and asserts nothing.
    "com.cartogenesis.worldgen.S2bRenderDump",
    // F18: the network statistics, the moved-cell tally and the routing's own cost need every world
    // generated twice, once under each routing rule — ten worlds, a pair of them at 1024, and one
    // more at 2048 to be timed. The census that guards the chunk is in `StraightRunTest`.
    "com.cartogenesis.worldgen.StraightRunAuditTest",
    // X1d: the coastal valleys' spacing. Twenty-one whole worlds, seven of them at 2048; six more
    // for the instrument's own scales; ten controlled worlds at 512 and fourteen at 2048; about
    // forty minutes in all. The finder it drives is shown reading a range whose answer was written
    // in by hand in `RangeFrontTest`, which is per-merge and costs nothing, because it generates no
    // world at all.
    "com.cartogenesis.worldgen.CoastalSpacingAuditTest",
    // W2's diagnosis: eight worlds, each seed generated with the pressure wind and without it, for
    // a printed table of where the permanent ice went and whether a belt boundary took it. It
    // asserts nothing; the guards it was written to explain are in `PressureWindTest` and
    // `SnowBalanceTest`.
    "com.cartogenesis.worldgen.PressureWindIceTest",
    // T5: five more that assert nothing and had been running on every merge. W3's renders, which
    // said in their own KDoc that they were excluded like the rest of the harness and were not: two
    // worlds at 2048 for eight pictures. S3's three reports on the climate-fed erosion, which decide
    // things by being read and are four minutes of provisional marches, one of them at 2048. How
    // closely realm borders follow rivers and ridges, printed per seed against a null model. E7's
    // two reports on what a rift trough holds, whose conclusion is that nothing at 512 can say.
    // And the river-endings picture and tally, a 1024 world for a PNG and three more for a table.
    "com.cartogenesis.worldgen.W3RenderDump",
    "com.cartogenesis.worldgen.ClimateFedErosionMeasurementTest",
    "com.cartogenesis.worldgen.BorderRealismTest",
    "com.cartogenesis.worldgen.RiftDepthTest",
    "com.cartogenesis.worldgen.RiverEndingsTest",
    // T5, by method: three reports inside classes whose other cases are guards, which stay. H5's
    // whole before-and-after table, twenty-seven worlds, most of them variants nobody else asks for; the
    // lake budget before and after the water balance, twelve worlds, two of them at 1024; and the
    // PNGs of the coast around the largest river mouths. A class name and a method name, which
    // Gradle's filter matches the same way on both sides.
    "com.cartogenesis.worldgen.SeaLevelHistoryTest.report every corner of the pair",
    "com.cartogenesis.worldgen.LakeWaterBalanceTest.report the lake budget",
    "com.cartogenesis.worldgen.DepositionTest.render the coast around the largest river mouths",
    // Audit III's instruments: the straightness report over every shore, which generates 364673 at
    // 2048 to print a table and asserts only that it had something to measure; and the three rule-8
    // cost guards, whose bar is a share of a 2048 world and which now measure that world in the
    // same run rather than quoting a figure no code produced (`GenerationTime`), a minute or two of
    // generation the first of them pays for and the others share.
    "com.cartogenesis.worldgen.StraightRunTest.report how straight every shore is",
    "com.cartogenesis.worldgen.ChannelInitiationCostTest",
    "com.cartogenesis.worldgen.PressureWindCostTest",
    "com.cartogenesis.worldgen.VegetationCostTest"
)

/*
 * T1 (the world cache and the deep tier, 2026-10-01): the deep tier, by the stage each entry guards (see the root build script for what
 * the tiers are and how `-Pstages` selects). Every class here builds worlds the everyday tier does
 * not: its own variant with one setting moved, for the on/off control that shows a feature causes
 * its effect; the same seed on several grids; or a world of 1,024 rows or more. Each reads exactly
 * the worlds it read in the per-merge tier before; only the task it runs in moved.
 *
 * A class name runs the whole class. A class name and a method name is a control inside a class
 * whose other cases read standard worlds and stay in `jvmTest`: Gradle's filter matches it the same
 * way on both sides, as the audit tier's method entries do.
 */
val deepClassesByStage: Map<String, List<String>> = mapOf(
    "plates" to listOf(
        "com.cartogenesis.worldgen.BoundaryPairTest",
        "com.cartogenesis.worldgen.IsostasyTest",
        "com.cartogenesis.worldgen.TectonicGroundTest",
        "com.cartogenesis.worldgen.GroundTextureTest.a belt's flank is dissected, and is a plane at S1's critical slope",
        "com.cartogenesis.worldgen.GroundTextureTest.the ground's texture follows its relief",
        "com.cartogenesis.worldgen.GroundTextureTest.a continent drowns at its rim",
        "com.cartogenesis.worldgen.RiftSegmentationTest.the unsegmented rift fails every one of those",
        "com.cartogenesis.worldgen.TectonicHistoryTest.zero epochs is read as one, and a second epoch changes the ground",
        "com.cartogenesis.worldgen.TectonicHistoryTest.an old belt stands far from any present boundary",
        "com.cartogenesis.worldgen.TectonicHistoryTest.the guard finds nothing inland when the plates never moved",
        "com.cartogenesis.worldgen.TectonicHistoryTest.an old belt is lower and broader than a present one"
    ),
    "erosion" to listOf(
        "com.cartogenesis.worldgen.DeltaMouthTest",
        "com.cartogenesis.worldgen.ReceiverClampTest",
        "com.cartogenesis.worldgen.RibbonLandTest",
        "com.cartogenesis.worldgen.ValleyIncisionTest",
        "com.cartogenesis.worldgen.CombGuardTest.the comparison sees the half-height cell's comb down the columns",
        "com.cartogenesis.worldgen.ClimateFedErosionTest.the wet flank of a range is cut harder than the dry one",
        "com.cartogenesis.worldgen.ClimateFedErosionTest.report what the rain leaves on a range after twelve rounds",
        "com.cartogenesis.worldgen.ClimateFedErosionTest.dissection follows the rainfall",
        "com.cartogenesis.worldgen.ClimateFedErosionTest.cover on the ground holds the incision back",
        "com.cartogenesis.worldgen.DeltaOutlineTest.which mechanism makes which shape",
        "com.cartogenesis.worldgen.DeltaOutlineTest.lobe rims in a world",
        "com.cartogenesis.worldgen.DepositionTest.river mouths gain land, and do not without deposition",
        "com.cartogenesis.worldgen.DepositionTest.deposition off is deposition absent, and its knobs cannot leak",
        "com.cartogenesis.worldgen.ErosionSkipTest.report what skipping settled ground saves"
    ),
    "sea" to listOf(
        "com.cartogenesis.worldgen.GlacialBasinShapeTest",
        "com.cartogenesis.worldgen.GlaciationCombTest",
        "com.cartogenesis.worldgen.GlaciationLatticeTest",
        "com.cartogenesis.worldgen.GlaciationTest",
        "com.cartogenesis.worldgen.IceAcceleratorSwitchTest",
        "com.cartogenesis.worldgen.SeaLevelHistoryTest",
        "com.cartogenesis.worldgen.ContinentalShelfTest.the near-coast share fails without the shelf",
        "com.cartogenesis.worldgen.ContinentalShelfTest.the shelf never touches land",
        "com.cartogenesis.worldgen.IceSheetTest.the ice flows out from its dome and its scour follows",
        "com.cartogenesis.worldgen.IceSheetTest.the sheet feeds its outlets, and what they cut is reported",
        "com.cartogenesis.worldgen.IceSheetTest.the sheet mask's edge follows the ground",
        "com.cartogenesis.worldgen.IceSheetTest.the sheet's surface is a dome and not a ruling of the grid"
    ),
    "ocean" to listOf(
        "com.cartogenesis.worldgen.OceanLeavesTheGroundTest.the ground is the same with the currents and without them"
    ),
    "climate" to listOf(
        "com.cartogenesis.worldgen.CurrentFeedsRainTest",
        "com.cartogenesis.worldgen.GridShapeTest",
        "com.cartogenesis.worldgen.MeridionalWindTest",
        "com.cartogenesis.worldgen.MoistureBudgetTest",
        "com.cartogenesis.worldgen.PlanetWidthRainTest",
        "com.cartogenesis.worldgen.PressureWindTest",
        "com.cartogenesis.worldgen.SnowBalanceTest",
        "com.cartogenesis.worldgen.AbsoluteRainfallTest.an arid config and a lush one classify identically under the old normalization, not under this one",
        "com.cartogenesis.worldgen.GeographyAuditTest.the band guard bites on a world whose land never re-moistens",
        "com.cartogenesis.worldgen.SeaIceTest.the march takes no moisture from ice, and with the ice off it takes plenty",
        "com.cartogenesis.worldgen.SeaIceTest.the biome draws the pack that survives the summer",
        "com.cartogenesis.worldgen.SeasonsTest.without seasons there is no Mediterranean coast to find",
        "com.cartogenesis.worldgen.VegetationDensityTest.with the field off the three land shares are outside Earth's bars"
    ),
    "rivers" to listOf(
        "com.cartogenesis.worldgen.ChannelInitiationControlTest",
        "com.cartogenesis.worldgen.LakeWaterBalanceTest",
        "com.cartogenesis.worldgen.OutletIncisionTest",
        "com.cartogenesis.worldgen.StraightRunTest",
        "com.cartogenesis.worldgen.FlatCourseTest.the potential keeps every flat cell a way down and the network a forest"
    ),
    "realms" to listOf(
        "com.cartogenesis.worldgen.RealmIdRangeTest"
    ),
    "engine" to listOf(
        "com.cartogenesis.worldgen.GenerationStopTest",
        "com.cartogenesis.worldgen.ScaleFreeTest",
        "com.cartogenesis.worldgen.IncrementalReuseTest.reusing stages gives the same world as generating afresh",
        "com.cartogenesis.worldgen.IncrementalReuseTest.reuse makes a late setting change much cheaper",
        "com.cartogenesis.worldgen.PipelineTest.sea level slider controls the land fraction",
        "com.cartogenesis.worldgen.PipelineTest.world keeps its character when regenerated at a larger resolution",
        "com.cartogenesis.worldgen.PipelineTest.changing only sea level reuses the terrain and recomputes the plates and the sea"
    )
)
val deepClasses = deepClassesByStage.values.flatten()
val NO_DEEP_CLASS = "com.cartogenesis.NoDeepClassInTheStagesAsked"

/*
 * T5: the per-merge tier in workers side by side, each with the heap and the share of the processor
 * the root build script's budget gives it (see there for why those add up as they do). The audit
 * task below keeps the eight gigabytes and the one worker above, which its 2048 and 4096 cases were
 * sized for.
 *
 * The workers share the classes out, and every class that asks `SharedWorlds` for a world is
 * handed the one its worker already holds, so a standard world is generated once a worker rather
 * than once a class. Which classes share a worker is Gradle's choice — it deals them out in the
 * order it finds them and does not look at what they cost — so a world many classes ask for is
 * made at most once in each worker, and how evenly the time falls is left to chance.
 */
/** The per-merge tier's share of the machine, which the deep tier takes too: it is the same work, split. */
fun Test.withPerMergeBudget() {
    val budget = rootProject.extra
    maxParallelForks = budget["worldgenTestForks"] as Int
    maxHeapSize = budget["worldgenTestHeap"] as String
    val processors = budget["worldgenTestProcessors"] as Int
    jvmArgs(
        "-XX:ActiveProcessorCount=$processors",
        "-Djava.util.concurrent.ForkJoinPool.common.parallelism=$processors"
    )
}

tasks.named<Test>("jvmTest") {
    filter {
        auditOnlyClasses.forEach { excludeTestsMatching(it) }
        deepClasses.forEach { excludeTestsMatching(it) }
    }
    withPerMergeBudget()
}

/*
 * The deep tier: run when its part of the pipeline changes, `-Pstages` naming the parts (see the
 * root build script). The audit tier's entries inside deep classes stay the audit tier's.
 */
tasks.register<Test>("deepTest") {
    group = "verification"
    description = "Runs the deep tier: the on/off controls, grid comparisons and 1,024-row worlds " +
        "excluded from jvmTest; -Pstages=climate,ocean limits it to those stages and what they reach."
    val jvmTestTask = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTestTask.testClassesDirs
    classpath = jvmTestTask.classpath
    @Suppress("UNCHECKED_CAST")
    val stages = rootProject.extra["deepStages"] as Set<String>
    filter {
        val selected = deepClassesByStage.filterKeys { it in stages }.values.flatten()
        // An empty include list would include everything, so a selection with nothing here
        // includes a name that matches nothing instead.
        selected.ifEmpty { listOf(NO_DEEP_CLASS) }.forEach { includeTestsMatching(it) }
        auditOnlyClasses.forEach { excludeTestsMatching(it) }
        isFailOnNoMatchingTests = !(rootProject.extra["deepStagesLimited"] as Boolean)
    }
    withPerMergeBudget()
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

/*
 * Record mode's second half: writes the replacements a `-Precord` test run left under every
 * module's `build/pin-records` into the source files they name, and prints each change (file and
 * line, old value, new value, and the test that measured it) for review. `-Preview` prints what it
 * would change and writes nothing. The rewriter is `PinRecordRewriter` in the shared test support,
 * run on this module's test classpath so that its own self-test exercises the same code.
 */
tasks.register<JavaExec>("applyPinRecords") {
    group = "verification"
    description = "Writes the pinned figures a -Precord test run recorded into the source, for review."
    val jvmTestTask = tasks.named<Test>("jvmTest").get()
    classpath = jvmTestTask.classpath
    mainClass.set("com.cartogenesis.worldgen.PinRecordRewriterKt")
    args(rootProject.projectDir.absolutePath)
    if (providers.gradleProperty("review").isPresent) args("--review")
}
