import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** Common install locations for a full JDK, newest first. */
fun javaHomeCandidates(): List<String> = listOf(
    File("C:/Program Files/Eclipse Adoptium"),
    File("C:/Program Files/Java"),
    File("C:/Program Files/Microsoft"),
    File("/usr/lib/jvm")
).flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
    .filter { it.isDirectory && it.name.contains("jdk", ignoreCase = true) }
    .sortedByDescending { it.name }
    .map { it.absolutePath }

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    // The shared worlds and their check, compiled into this module's tests as well; see
    // `:worldgen`'s build script for why the files are shared rather than depended on.
    sourceSets.named("test") {
        kotlin.srcDir(rootProject.layout.projectDirectory.dir("worldgen/src/sharedTestSupport/kotlin"))
    }
}

// Kotlin and Java must agree, or the build refuses. Without this, javac defaults to whatever the
// running JDK is (25 from Android Studio's JBR) while Kotlin targets 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// LWJGL ships its native libraries as classifier artifacts, one set per platform, so the host has
// to be named explicitly. Only the running platform's natives are pulled in.
val lwjglNatives = when {
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "natives-windows"
    org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
        if (System.getProperty("os.arch").startsWith("aarch64")) "natives-macos-arm64"
        else "natives-macos"
    else -> "natives-linux"
}

dependencies {
    testImplementation(kotlin("test"))
    // Composes the whole interface offscreen so `ChromeGalleryTest` can photograph it. It is the
    // only way to see the theme without a person opening the window.
    testImplementation(compose.desktop.uiTestJUnit4)
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)

    // GPU-accelerated erosion, offered as an opt-in. GLFW is here only to obtain an offscreen
    // OpenGL context; no window is ever shown.
    implementation(platform(libs.lwjgl.bom))
    implementation(libs.lwjgl.core)
    implementation(libs.lwjgl.opengl)
    implementation(libs.lwjgl.glfw)
    runtimeOnly(variantOf(libs.lwjgl.core) { classifier(lwjglNatives) })
    runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier(lwjglNatives) })
    runtimeOnly(variantOf(libs.lwjgl.glfw) { classifier(lwjglNatives) })
}

// Exports at 4096 and beyond are the point of this module, so the tests need room to prove it.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "10g"

    // `GpuExportBenchmarkTest` measures whole exports at 4096 and 8192 and is the better part of
    // half an hour, nearly all of it generating worlds. It stands aside unless a run asks for it:
    // `-Pbenchmark=true`. The correctness guards beside it are not gated and always run.
    systemProperty(
        "cartogenesis.benchmark",
        providers.gradleProperty("benchmark").getOrElse("false")
    )

    // The known failures the tests record (`KnownFailures`, a twin of `:cartography`'s): a file
    // handed to the tests, cleared before the task runs and printed once it has, pass or fail, as
    // `:cartography`'s build script does for its own.
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

// The tests whose 2048 and 4096 work belongs to the on-demand / nightly audit tier rather than to
// every merge. Split by class name and a filter, matching `:worldgen` (see that module's build
// script for why a `@Tag` was not used there; the same filter mechanism works unchanged on this
// module's JUnit5 runner, so both modules are split the same way).
val auditOnlyClasses = listOf(
    // The 2048 and 4096 exports: minutes of pipeline before a pixel is drawn.
    "com.cartogenesis.desktop.ExportAuditTest",
    // A 2048 world and a 4096 one saved and opened through the real library: two 4096 worlds are
    // five gigabytes, which the per-merge worker's heap does not have.
    "com.cartogenesis.desktop.SaveResolutionAuditTest",
    // A 2048 pair, four worlds and four renders: the same tier for the same reason.
    "com.cartogenesis.desktop.SeaLevelHistoryAuditTest",
    // A render review: two worlds at 2048, drawn in three styles with four details of each.
    // Ninety seconds of generation for thirty pictures nothing but a person can judge.
    "com.cartogenesis.desktop.ClimateReliefGalleryTest",
    // The generalisation crops: two 2048 worlds, eight sheets and their crops, and the shoreline
    // trace timed against the raster. Nothing per-merge depends on any of it.
    "com.cartogenesis.desktop.GeneralisationRenderTest",
    // The littoral before-and-after pictures: four worlds, one at 2048, and twenty renders.
    "com.cartogenesis.desktop.LittoralCoastRenderTest",
    // The routing crops: two worlds drawn under each routing rule, one pair of them at 2048, so
    // the rivers can be compared by eye. Nothing per-merge depends on any of it.
    "com.cartogenesis.desktop.StraightRunRenderTest",
    // The Natural style's review: the same two worlds at 2048 again, whole and in three details
    // each, to be held beside the photograph the palette was sampled off.
    "com.cartogenesis.desktop.NaturalGalleryTest",
    // W2's review: the same two worlds at 2048 with the pressure wind off and on, in the Winds,
    // Rainfall and Fantasy views. Four worlds at 2048 for pictures only a person can judge.
    "com.cartogenesis.desktop.PressureWindRenderTest",
    // X1c: the author's world at 2048 and one seed at 512, 1024 and 2048, for the figure that
    // says one pane draws one map whatever grid it was generated on, and for the before-and-after
    // sheets. Four worlds, three of them above 512. Its 512 guards are `:cartography`'s
    // `RiverSelectionTest` and run on every merge.
    "com.cartogenesis.desktop.RiverSelectionAuditTest",
    // T5, by method: three measurements inside classes whose other cases are guards, which stay.
    // The ocean stage's wall clock on the card and off it at 2048 and 4096, which its own KDoc says
    // is reported and not asserted: seven minutes of every merge. The engraved style drawn at 512
    // and at 2048 for a person to look at. And how far a world generated on the card drifts from
    // one generated without it, printed. A class name and a method name, which Gradle's filter
    // matches the same way on both sides.
    "com.cartogenesis.desktop.GpuOceanTest.ocean wall clock at export sizes",
    "com.cartogenesis.desktop.StyleGalleryTest.the engraved style, at 512 and at 2048",
    "com.cartogenesis.desktop.GpuErosionTest.how far a world drifts when the gpu generates it",
    // The page's opening band, seed 1 at 2048: its narrow sea and its lakes drawn as water.
    // Its 512 twin in the same class runs on every merge.
    "com.cartogenesis.desktop.WaterDrawnAsWaterTest.the water in the opening band is drawn as water"
)

/**
 * `SiteAssemblyTest` reads the tree `assembleSite` writes, so it belongs to that task and not to
 * the per-merge suite: run on its own it would either fail for want of a tree or, worse, pass
 * against whatever an earlier run left in `desktop/build/site`. `siteTest` below builds the tree
 * first.
 */
val siteAssemblyClass = "com.cartogenesis.desktop.SiteAssemblyTest"

/*
 * The per-merge suite's heap and pool are the root build script's budget. One worker, not the
 * several `:worldgen` runs: the graphics tests here each drive the one card, and two of them
 * driving it at once from separate workers is a combination nothing has tested.
 */
/*
 * T1 (the world cache and the deep tier, 2026-10-01): this module's deep tier, by the stage each entry guards; see the root build
 * script for the tiers and `:worldgen`'s for how the entries are read. The graphics-card classes
 * compare a world made on the card with one made without it, a setting moved, and skip on a machine
 * with no card; the benchmarks skip unless asked for.
 */
val deepClassesByStage: Map<String, List<String>> = mapOf(
    "erosion" to listOf(
        "com.cartogenesis.desktop.GpuErosionTest"
    ),
    "ocean" to listOf(
        "com.cartogenesis.desktop.GpuOceanTest"
    ),
    "rivers" to listOf(
        // Two seeds on the square 512, 1,024 and 2,048 grids.
        "com.cartogenesis.desktop.OutletResolutionTest"
    ),
    "drawing" to listOf(
        "com.cartogenesis.desktop.EngravedRasterBenchmarkTest",
        "com.cartogenesis.desktop.ExportSmokeTest",
        "com.cartogenesis.desktop.GpuExportBenchmarkTest",
        "com.cartogenesis.desktop.TrueShapeSheetTest",
        "com.cartogenesis.desktop.WorldLinkDesktopTest",
        "com.cartogenesis.desktop.DataExportTest.only water and drowned basin floors come back darker than the stated sea level",
        "com.cartogenesis.desktop.RiverWidthTest.the pen is the same share of the sheet at every size",
        "com.cartogenesis.desktop.StyleGalleryTest.every style renders, and none of them look alike"
    )
)
val deepClasses = deepClassesByStage.values.flatten()

/** The per-merge tier's share of the machine, which the deep tier takes too. */
fun Test.withPerMergeBudget() {
    val budget = rootProject.extra
    maxHeapSize = budget["desktopTestHeap"] as String
    val processors = budget["desktopTestProcessors"] as Int
    jvmArgs(
        "-XX:ActiveProcessorCount=$processors",
        "-Djava.util.concurrent.ForkJoinPool.common.parallelism=$processors"
    )
    // The interface's tests generate worlds inside `runDesktopComposeUiTest`, whose outer `runTest`
    // takes the coroutine test library's default timeout and no other. Since the atmosphere is
    // coupled to the rain a world carries the loop's laps on the planet's own grid whatever the
    // map's size, so a test that makes three of the platforms' 512-row worlds outgrew the default
    // minute on the world's own cost (docs/DESIGN_LEDGER.md, A1-5). Ten minutes, the library's own
    // property: each generation inside is still held to its own five-minute wait, so a hang fails.
    systemProperty("kotlinx.coroutines.test.default_timeout", "10m")
}

tasks.named<Test>("test") {
    filter {
        auditOnlyClasses.forEach { excludeTestsMatching(it) }
        deepClasses.forEach { excludeTestsMatching(it) }
        excludeTestsMatching(siteAssemblyClass)
    }
    withPerMergeBudget()
}

tasks.register<Test>("deepTest") {
    group = "verification"
    description = "Runs this module's deep tier: the controls, grid comparisons and 1,024-row " +
        "worlds excluded from the per-merge test task."
    val testTask = tasks.named<Test>("test").get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath
    @Suppress("UNCHECKED_CAST")
    val stages = rootProject.extra["deepStages"] as Set<String>
    filter {
        val selected = deepClassesByStage.filterKeys { it in stages }.values.flatten()
        // An empty include list would include everything.
        selected.ifEmpty { listOf("com.cartogenesis.NoDeepClassInTheStagesAsked") }.forEach { includeTestsMatching(it) }
        auditOnlyClasses.forEach { excludeTestsMatching(it) }
        isFailOnNoMatchingTests = !(rootProject.extra["deepStagesLimited"] as Boolean)
    }
    withPerMergeBudget()
}

tasks.register<Test>("audit") {
    group = "verification"
    description = "Runs the on-demand / nightly audit tier: the 2048/4096 exports excluded from " +
        "the per-merge test task."
    val testTask = tasks.named<Test>("test").get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath
    filter {
        auditOnlyClasses.forEach { includeTestsMatching(it) }
        isFailOnNoMatchingTests = true
    }
}

// ---------------------------------------------------------------------------------------------
// cartogenesis.com
// ---------------------------------------------------------------------------------------------

/** Where `renderSiteImagery` leaves the figures the page shows, for `assembleSite` to pick up. */
val siteImageryDir = layout.buildDirectory.dir("site-imagery")

/**
 * Renders every picture on cartogenesis.com from the engine, into `desktop/build/site-imagery`.
 *
 * It lives here because only a JVM module can run the generator and Skia's encoder, and it must
 * run on the deploy runner, which is Linux with no graphics card and no display, so nothing here
 * asks for either: `SiteImagery` never requests the raster accelerator, and Skia writes to memory.
 * Headless is stated anyway, so a stray AWT touch fails here rather than on the runner.
 *
 * What it costs a deploy: 57 s on a sixteen-core desktop and 219 s pinned to two cores with
 * `-XX:ActiveProcessorCount=2`, which is the shape of a GitHub runner. Nearly all of it is
 * generating the world once; the handful of rasterisations and their WebP encodes are a few
 * seconds between them. Measured 2026-09-12, when the page showed seven figures.
 *
 * `-Pcontact` additionally writes the whole map at half size with a coordinate grid over it and
 * the page's windows outlined, which is how a window is chosen. Not wanted by a deploy.
 */
tasks.register<JavaExec>("renderSiteImagery") {
    group = "distribution"
    description = "Renders cartogenesis.com's figures from seed 718106 at 2048 rows into " +
        "desktop/build/site-imagery."
    mainClass = "com.cartogenesis.desktop.SiteImagery"
    classpath = sourceSets["main"].runtimeClasspath
    // The two worlds are 2048 rows, 4096 by 2048 cells, and every stage keeps float fields over
    // them: 3.0 GB live at the fullest (docs/DESIGN_LEDGER.md, Q5), with the sheets drawn from it
    // on top. 8g leaves the collector room, and is well inside the 16 GB a hosted runner has.
    maxHeapSize = "8g"
    systemProperty("java.awt.headless", "true")
    if (project.hasProperty("contact")) {
        systemProperty("cartogenesis.siteImagery.contact", "true")
    }

    val output = siteImageryDir.get().asFile
    argumentProviders.add { listOf(output.absolutePath) }
    outputs.dir(output)
    // The pictures are a function of the generator, so any change to it must re-render them. The
    // whole source of the three modules that decide what a map looks like is the input; anything
    // narrower would let a change to a stage ship yesterday's coastline. The pictures carry no
    // lettering since each became a card's, so no typeface is an input.
    inputs.files(
        rootProject.fileTree("worldgen/src"),
        rootProject.fileTree("cartography/src"),
        rootProject.files("desktop/src/main/kotlin/com/cartogenesis/desktop/SiteImagery.kt")
    ).withPropertyName("generatorSourcesThatDecideWhatTheFiguresShow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

/**
 * The browser application the site serves under `/app/`: a stored copy, not a build.
 *
 * The browser version is no longer developed (docs/DESIGN_LEDGER.md, G1). What the site serves is
 * the application exactly as it was last assembled, from main at 8198db27: a zip attached to the
 * GitHub release `web-frozen` as `web-frozen.zip`, holding `app/` as `:web:assembleSite` wrote it
 * less the loading shell, which is `site/app/index.html` and is laid over it like every other page.
 * The deploy downloads it (`.github/workflows/site.yml`); a local run downloads it the same way:
 *
 *     gh release download web-frozen --pattern web-frozen.zip --dir build/web-frozen
 *
 * `-PfrozenWebApp=<path>` names another copy of the zip, and docs/WEB_VERSION.md says how to make a
 * new one. Whichever copy is read, it must be the one stored: its SHA-256 is [frozenWebAppSha256], so a release asset replaced by mistake, or a
 * download cut short, stops the assembly instead of publishing a different application.
 */
val frozenWebApp: File = providers.gradleProperty("frozenWebApp")
    .map { rootProject.file(it) }
    .getOrElse(rootProject.layout.projectDirectory.file("build/web-frozen/web-frozen.zip").asFile)

/** The SHA-256 of `web-frozen.zip` as it was made from 8198db27, in lowercase hex. */
val frozenWebAppSha256 = "184683473159993afa34c79f5d2b8027ade1a1263dec1919a04b143bc4108023"

/**
 * The token `site/app/index.html` carries where the loader's cache-buster goes. It is replaced
 * during assembly, and `SiteAssemblyTest` fails if a copy of it survives into the built tree.
 */
val loaderStampPlaceholder = "__STAMP__"

/** The loader's `src` attribute, before and after stamping. Scoped, so prose is never rewritten. */
fun loaderTag(stamp: String) = """src="cartogenesis.js?v=$stamp""""

/**
 * What the deploy stamps into the loader's URL: the short commit the site was assembled from.
 *
 * Only its *changing* matters. The two `.wasm` files are content-hashed and can be cached for a
 * year; `cartogenesis.js` never changes name, so without a fresh query on every deploy a returning
 * visitor's cached loader asks for a wasm hash that no longer exists — a 404 and a dead app rather
 * than a stale one, which is not a theory but what the v1.0.1 deploy did. The application is frozen
 * now, so its loader no longer changes, and a fresh stamp costs a returning visitor one fetch of it;
 * kept because a stamp that stopped changing would be the one thing to remember if it ever thawed.
 *
 * A UTC timestamp is the fallback for the case where git cannot answer: a source download, or a
 * checkout with no history. It is coarser but it still changes per deploy, which is the whole job.
 *
 * `by lazy` so that a build which never assembles the site never shells out to git.
 */
val siteStamp: String by lazy {
    val fromGit = runCatching {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0) output else ""
    }.getOrDefault("")

    fromGit.ifEmpty {
        DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC).format(Instant.now())
    }
}

/**
 * The token both pages carry where the size of the download goes.
 *
 * Two pages quote it — the landing page's browser note and the loading shell a reader watches
 * while it arrives — and a number typed into either goes stale silently. So neither types one: the
 * assembly measures the files it is about to publish and writes the same figure into both.
 */
val bundleSizePlaceholder = "__BUNDLE_MB__"

/**
 * The compressed size of the three files a visitor fetches to run the generator, in MB.
 *
 * Compressed, because that is what a reader waits for: every host this site is served from sends
 * these gzipped or better, and the raw 13 MB is a number nobody experiences. Gzip at the default
 * level is the measurement rather than brotli because it is the one every JDK can make — the live
 * figure is a little smaller, so the page never promises a faster download than it delivers.
 *
 * The three files are the loader and the two wasm modules, which is what the shell's own progress
 * bar counts; the bundled type faces are fetched by the application after it starts.
 */
fun engineDownloadMegabytes(appDirectory: File): String {
    val engine = appDirectory.listFiles()
        .orEmpty()
        .filter { it.isFile && (it.extension == "wasm" || it.name == "cartogenesis.js") }
    check(engine.size >= 2) {
        "expected the loader and the wasm modules in ${appDirectory.absolutePath}, found " +
            engine.joinToString { it.name }
    }
    val compressed = engine.sumOf { source ->
        val sink = ByteArrayOutputStream()
        GZIPOutputStream(sink).use { it.write(source.readBytes()) }
        sink.size().toLong()
    }
    val tenthsOfAMegabyte = (compressed * 10 + 512 * 1024) / (1024 * 1024)
    return "${tenthsOfAMegabyte / 10}.${tenthsOfAMegabyte % 10}"
}

/**
 * `ROADMAP.md`: the one place the planned releases and what they bring are written down.
 *
 * The page carries a marker comment where its table goes and the assembly puts the table there, so
 * the page cannot say a release the file does not and the file cannot plan a release the page
 * never shows. `SiteAssemblyTest` reads both back and compares them row for row.
 */
val roadmapFile = rootProject.layout.projectDirectory.file("ROADMAP.md").asFile

/** The comment in `site/index.html` the table replaces. */
val roadmapMarker = "<!-- roadmap -->"

/** One row of the file's table: which release, what it brings, and whether it is the current one. */
class RoadmapRow(val release: String, val brings: String, val current: Boolean)

/**
 * The rows of the one Markdown table in [roadmapFile], in the order it lists them.
 *
 * The heading row and the `---` rule under it are dropped by their shape rather than by counting
 * lines, so prose may be added above or below the table without moving anything. A release marked
 * `(current)` is the release the site is describing; the marker is taken off the name and carried
 * as [RoadmapRow.current], which is what the page draws a tag for.
 */
fun roadmapRows(): List<RoadmapRow> {
    check(roadmapFile.isFile) { "ROADMAP.md is missing: the page's roadmap is drawn from it" }
    val rows = roadmapFile.readLines()
        .map { it.trim() }
        .filter { it.startsWith("|") && it.endsWith("|") }
        .map { line -> line.trim('|').split('|').map { it.trim() } }
        .filter { it.size == 2 }
        .filterNot { it[0].equals("Release", ignoreCase = true) }
        .filterNot { cells -> cells.all { it.isNotEmpty() && it.all { char -> char == '-' } } }
        .map { (release, brings) ->
            RoadmapRow(
                release = release.removeSuffix("(current)").trim(),
                brings = brings,
                current = release.contains("(current)")
            )
        }
    check(rows.isNotEmpty()) { "ROADMAP.md has no table rows for the page to draw" }
    check(rows.count { it.current } == 1) {
        "ROADMAP.md marks ${rows.count { it.current }} releases as (current); exactly one is"
    }
    return rows
}

/** `&`, `<` and `>` as a browser must read them, for text that came out of a Markdown file. */
fun asHtmlText(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * The roadmap as the page's own Features list is written: a `spec` list, one row per release.
 *
 * No new style and no new class. The Features list above it is already a two-column list of a term
 * and a sentence separated by hairlines, which is exactly what a roadmap is, and the current
 * release is marked with the same `tag` the download card wears.
 */
fun roadmapTable(indent: String): String = buildString {
    append(indent).append("""<dl class="spec">""")
    roadmapRows().forEach { row ->
        val mark = if (row.current) """<span class="tag">Current</span>""" else ""
        appendLine()
        append(indent).append("  <div><dt>").append(asHtmlText(row.release)).append(mark)
            .append("</dt><dd>").append(asHtmlText(row.brings)).append("</dd></div>")
    }
    appendLine()
    append(indent).append("</dl>")
}

/**
 * Assembles the whole of cartogenesis.com into `desktop/build/site`, ready to hand to a static host:
 * the stored browser application under `app/`, `site/` laid over it, and the figures.
 *
 * `Sync` rather than `Copy` because the wasm filenames carry content hashes: a tree that is only
 * ever added to keeps every orphan and deploys all of them.
 */
tasks.register<Sync>("assembleSite") {
    group = "distribution"
    description = "Assembles cartogenesis.com into desktop/build/site: the stored browser " +
        "application under app/, site/ laid over it, and the figures, with the loader stamped."

    // The shell is UTF-8 and full of em dashes. Left to the platform default, filtering reads it
    // as ANSI on Windows and writes mojibake back out; stating the charset is the whole fix.
    filteringCharset = "UTF-8"

    // Every picture on the page is rendered from the engine by this task, from a fixed seed and
    // fixed crop windows, so the release that changes what a coastline looks like changes the
    // coastline the page shows. Nothing in site/ is an image any more.
    dependsOn("renderSiteImagery")

    into(layout.buildDirectory.dir("site"))

    // The roadmap the landing page draws: a change to it has to re-assemble the page, and Gradle
    // cannot see a file read inside a copy action.
    inputs.file(roadmapFile).withPropertyName("roadmapTheLandingPageDraws")

    // The stored application first, so that the pages below are laid over it. Its own copy of the
    // shell is never in the zip; `site/app/index.html` is the shell.
    doFirst {
        check(frozenWebApp.isFile) {
            "the stored browser application is not at ${frozenWebApp.absolutePath}. Download it " +
                "with `gh release download web-frozen --pattern web-frozen.zip --dir build/web-frozen`, " +
                "or name a copy with -PfrozenWebApp=<path>."
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(frozenWebApp.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == frozenWebAppSha256) {
            "${frozenWebApp.absolutePath} has SHA-256 $digest, not the stored application's " +
                "$frozenWebAppSha256"
        }
    }
    from(provider { if (frozenWebApp.isFile) zipTree(frozenWebApp) else files() }) {
        include("app/**")
        // 1.7 MB of debug-only weight that also publishes the original Kotlin, had one been kept.
        exclude("**/*.map")
        exclude("app/index.html")
        includeEmptyDirs = false
    }

    from(rootProject.layout.projectDirectory.dir("site")) {
        // Documentation for whoever maintains the site, not part of the site.
        exclude("README.md")

        // Three files that are about the site rather than part of it: the list of release file
        // names the page's Download and Installation section is checked against, the rule that
        // keeps reprepro's output out of git, and reprepro's own configuration, which the site
        // deploy reads from here and writes back with the signing key's id in it.
        //
        // Everything else under site/ is copied as it stands, folders this script has never heard
        // of included. The apt repository itself is not among them: it is 100 MB of packages, and
        // every test task here declares site/ as an input, so the deploy builds it straight into
        // the assembled tree after this task has run. See docs/DEPLOYMENT.md.
        exclude("downloads.txt")
        // The web fonts' generator and its record of what each was cut from, which SiteFontsTest
        // reads: the fonts are published, these two are not.
        exclude("fonts/build_web_fonts.py")
        exclude("fonts/faces.json")
        exclude("apt/.gitignore")
        exclude("apt/conf/**")

        // The landing page's "What comes next" table, put in where the page keeps its marker.
        // A whole-line replacement, so the table takes the marker's own indentation with it.
        filesMatching("index.html") {
            filter { line ->
                if (line.trim() == roadmapMarker) roadmapTable(line.substringBefore("<")) else line
            }
        }

        filesMatching("app/index.html") {
            filter { line ->
                line.replace(loaderTag(loaderStampPlaceholder), loaderTag(siteStamp))
            }
        }
    }

    // The page's typefaces are site/fonts/*.woff2, cut from the application's own faces by
    // site/fonts/build_web_fonts.py and copied with the rest of site/. Their licenses are the
    // application's, published beside them as the SIL Open Font License asks.
    into("fonts") {
        from(rootProject.layout.projectDirectory.dir("ui/licences")) { include("OFL-*.txt") }
    }

    // The figures, and the relief's heights, which are a PNG because they must arrive without
    // loss. `include` rather than the whole directory because `-Pcontact` leaves contact sheets in
    // there, which are a tool for choosing a crop and not part of the site.
    into("img") {
        from(siteImageryDir) { include("*.webp", "relief-heights.png") }
    }

    doLast {
        val site = destinationDir

        // What the download weighs, written into both pages that quote it, from one measurement of
        // the tree that is about to be published.
        val megabytes = engineDownloadMegabytes(File(site, "app"))
        listOf("index.html", "app/index.html").forEach { path ->
            val page = File(site, path)
            val before = page.readText(Charsets.UTF_8)
            // A page that has lost the token is a page that has stopped saying how big the
            // download is, or has gone back to a number typed by hand. Both deploy quietly.
            check(before.contains(bundleSizePlaceholder)) {
                "$path no longer carries $bundleSizePlaceholder, so nothing measures the size " +
                    "it tells a reader to expect"
            }
            page.writeText(before.replace(bundleSizePlaceholder, megabytes), Charsets.UTF_8)
        }
        logger.lifecycle("The browser preview is $megabytes MB compressed; both pages say so")

        val shell = File(site, "app/index.html")
        // A stamp left unreplaced means the scoped replacement above stopped matching — a rename
        // of the loader, or the src attribute reformatted. Silent otherwise, and the failure it
        // leads to is a 404 on a returning visitor's second deploy, which is a bad way to find out.
        check(shell.isFile && !shell.readText(Charsets.UTF_8).contains(loaderStampPlaceholder)) {
            "app/index.html still carries the loader placeholder. The replacement is scoped to " +
                """src="cartogenesis.js?v=..."; check that attribute in site/app/index.html."""
        }
        // A marker left behind means the roadmap was never drawn and the page deploys with a
        // heading over nothing. Silent otherwise, exactly as the loader's stamp would be.
        val assembledPage = File(site, "index.html").readText(Charsets.UTF_8)
        check(!assembledPage.contains(roadmapMarker)) {
            "index.html still carries $roadmapMarker, so the roadmap table was not substituted. " +
                "The replacement matches the marker on a line of its own in site/index.html."
        }
        val releases = roadmapRows()
        check(releases.all { assembledPage.contains(">${it.release}<") }) {
            "the assembled page is missing a release ROADMAP.md names: " +
                releases.map { it.release }.filterNot { assembledPage.contains(">$it<") }
        }
        logger.lifecycle(
            "Roadmap drawn from ROADMAP.md: " + releases.joinToString {
                it.release + if (it.current) " (current)" else ""
            }
        )

        // The page's weight is a thing the design has a target for, so the assembly reports it
        // rather than leaving it to be measured by hand. "Before the app" is what a reader who
        // never clicks the browser preview pays: the page, its five faces and its figures, and
        // nothing under app/, which is the 12 MB of WebAssembly the preview fetches.
        fun weigh(dir: String) = File(site, dir).walkTopDown()
            .filter { it.isFile }.sumOf { it.length() }
        val bytes = site.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val page = File(site, "index.html").length()
        val fonts = weigh("fonts")
        val images = weigh("img")
        logger.lifecycle(
            "Site assembled at $site (${bytes / 1024 / 1024} MB), loader stamp $siteStamp"
        )
        logger.lifecycle(
            "Landing page before the app: ${(page + fonts + images) / 1024} KB " +
                "(html ${page / 1024}, fonts ${fonts / 1024}, images ${images / 1024})"
        )
    }
}

tasks.register<Test>("siteTest") {
    group = "verification"
    description = "Assembles cartogenesis.com and checks the tree that would be uploaded."
    dependsOn("assembleSite")
    val testTask = tasks.named<Test>("test").get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath
    filter {
        includeTestsMatching(siteAssemblyClass)
        isFailOnNoMatchingTests = true
    }
    // It reads a tree of files and generates nothing: the largest thing it holds is the opening's
    // band decoded, 4096 by 800 pixels, 13 MB.
    maxHeapSize = "512m"
    // What this test reads is the assembled tree, which it never declares: never being up to date
    // costs a few seconds and is the whole point, since the run has to look at the tree that was
    // just assembled. Never taken from the build cache either, for the same reason: a cache key
    // blind to the tree would hand back an earlier pass over a tree that has since changed.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

compose.desktop {
    application {
        mainClass = "com.cartogenesis.desktop.MainKt"

        // Packaging needs jpackage, which the JetBrains Runtime that ships with Android Studio
        // does not include. Point only the packaging step at a full JDK; the rest of the build
        // carries on using whatever Gradle is running under.
        // Override with -PjdkHome=/path/to/jdk if yours lives elsewhere.
        javaHome = (findProperty("jdkHome") as String?)
            ?: System.getenv("JPACKAGE_HOME")
            ?: javaHomeCandidates().firstOrNull { File(it, "bin/jpackage.exe").exists() ||
                File(it, "bin/jpackage").exists() }
            ?: System.getProperty("java.home")

        // The whole point of the desktop build: generation at export resolutions needs gigabytes,
        // which is exactly what Android could not give it. 4096 wants roughly 4GB, 8192 four times
        // that, and the FFT buffers are transient spikes on top. The heap is a share of the
        // machine's memory rather than a fixed figure, so a 32GB machine offers 24GB to an export
        // and an 8GB one still runs 4096 inside its 6GB; a fixed 12GB was the wall an 8192 export
        // hit on a machine that had twice that to give.
        jvmArgs += listOf("-XX:MaxRAMPercentage=75")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "Cartogenesis"
            // Declared in gradle.properties so the build is the single source of truth for the
            // version, rather than something to be kept in step by hand at release time. The
            // suffix is dropped here because the installer formats will not take one: MSI wants
            // MAJOR.MINOR.BUILD and DMG wants MAJOR[.MINOR][.PATCH], and a development version
            // such as `3.0.0-dev` fails *configuration* of this project, which with no
            // configuration-on-demand takes down every task in the build including the tests.
            // The suffix still reaches the app and the update check, which is where it matters.
            packageVersion = providers.gradleProperty("cartogenesisVersion").get()
                .substringBefore('-')

            // jpackage runs jlink, which bundles only the modules it can prove are needed -- and
            // it cannot see through LWJGL's reflection, so it left out jdk.unsupported. That is
            // the module holding sun.misc.Unsafe, which LWJGL uses for native memory, so the
            // packaged build could not start a GL context at all and reported the GPU as
            // unavailable with a NoClassDefFoundError. Nothing was wrong in development, where the
            // full JDK is on hand; only the trimmed runtime was short.
            modules("jdk.unsupported")
        }
    }
}

/*
 * Files the tests read by path, which Gradle has no way to know about: without declaring them, a
 * test task stays up to date when they change, and the build cache restores the previous
 * *passing* result. Caught exactly that way more than once, each guard reporting success from
 * cache after it had been broken on purpose to prove it bites.
 */
tasks.withType<Test>().configureEach {
    // `SitePaletteContrastTest` reads the landing page's own CSS and measures every pair of
    // colours it sets. Same trap, caught the same way: without this the task stays up to date
    // when the page changes and the build cache hands back the previous *passing* result. Proved
    // by putting the old page's failing grey back and watching the guard report success.
    inputs.files(rootProject.fileTree("site"))
        .withPropertyName("sitePagesReadByThePaletteContrastTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // `SiteAssemblyTest` reads the roadmap the page is drawn from and checks that the issue forms
    // the page links to exist. Same trap again: without these the task stays up to date when a
    // release line moves or a form is renamed, and the cache hands back the previous pass.
    inputs.files(
        rootProject.files("ROADMAP.md"),
        rootProject.fileTree(".github/ISSUE_TEMPLATE")
    ).withPropertyName("roadmapAndIssueFormsReadByTheSiteAssemblyTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // `SiteSourcesTest` holds the page's file names and apt source line to the installation
    // document and the release notes' template: the same trap, the same declaration.
    inputs.files(
        rootProject.files("docs/INSTALL.md", "docs/RELEASE_NOTES_TEMPLATE.md")
    ).withPropertyName("documentsReadByTheSiteSourcesTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // `SiteFontsTest` holds the page's web fonts to the application's faces they were cut from,
    // read by path rather than off the classpath: the same trap, the same declaration.
    inputs.files(rootProject.fileTree("ui/src/commonMain/composeResources/font"))
        .withPropertyName("applicationFacesReadByTheSiteFontsTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
