import java.io.File
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
}

// T1: `ExportAuditTest`'s 2048/4096 exports move to the on-demand / nightly audit tier, matching
// the class-name-plus-filter split used in `:worldgen` (see that module's build script for why a
// `@Tag` was not used there; the same filter mechanism works unchanged on this module's JUnit5
// runner, so both modules are split the same way).
val auditOnlyClasses = listOf(
    "com.cartogenesis.desktop.ExportAuditTest",
    // H5's own 2048 pair, four worlds and four renders: the same tier for the same reason.
    "com.cartogenesis.desktop.SeaLevelHistoryAuditTest",
    // F14's crops: two 2048 worlds, eight sheets and their crops, and the shoreline trace timed
    // against the raster. Nothing per-merge depends on any of it.
    "com.cartogenesis.desktop.GeneralisationRenderTest"
)

/**
 * `SiteAssemblyTest` reads the tree `:web:assembleSite` writes, so it belongs to that task and not
 * to the per-merge suite: run on its own it would either fail for want of a tree or, worse, pass
 * against whatever an earlier run left in `web/build/site`. `siteTest` below builds the tree first.
 */
val siteAssemblyClass = "com.cartogenesis.desktop.SiteAssemblyTest"

tasks.named<Test>("test") {
    filter {
        auditOnlyClasses.forEach { excludeTestsMatching(it) }
        excludeTestsMatching(siteAssemblyClass)
    }
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

/** Where the figures are left for `:web:assembleSite` to pick up. */
val siteImageryDir = rootProject.layout.projectDirectory.dir("web/build/site-imagery")

/**
 * Renders every picture on cartogenesis.com from the engine, into `web/build/site-imagery`.
 *
 * It lives in `:desktop` and writes into `:web`'s build directory because that is where the two
 * halves meet: only a JVM module can run the generator and Skia's encoder, and only `:web` knows
 * how to assemble a site. `:web:assembleSite` depends on this task and copies what it wrote.
 *
 * It must run on the deploy runner, which is Linux with no graphics card and no display, so
 * nothing here asks for either: `SiteImagery` never requests the raster accelerator, and Skia
 * writes to memory. Headless is stated anyway, so a stray AWT touch fails here rather than on the
 * runner.
 *
 * What it costs a deploy: 57 s on a sixteen-core desktop and 219 s pinned to two cores with
 * `-XX:ActiveProcessorCount=2`, which is the shape of a GitHub runner. Nearly all of it is
 * generating the world once; the seven rasterisations and their WebP encodes are three seconds
 * between them. Measured 2026-09-12.
 *
 * `-Pcontact` additionally writes the whole map at half size with a coordinate grid over it and
 * the page's windows outlined, which is how a window is chosen. Not wanted by a deploy.
 */
tasks.register<JavaExec>("renderSiteImagery") {
    group = "distribution"
    description = "Renders cartogenesis.com's figures from seed 718106 at 2048 into " +
        "web/build/site-imagery."
    mainClass = "com.cartogenesis.desktop.SiteImagery"
    classpath = sourceSets["main"].runtimeClasspath
    // The world is 2048x2048 and every stage keeps float fields over it; 6g is comfortable, and
    // well inside the memory a hosted runner has.
    maxHeapSize = "6g"
    systemProperty("java.awt.headless", "true")
    if (project.hasProperty("contact")) {
        systemProperty("cartogenesis.siteImagery.contact", "true")
    }

    val output = siteImageryDir.asFile
    argumentProviders.add { listOf(output.absolutePath) }
    outputs.dir(output)
    // The pictures are a function of the generator, so any change to it must re-render them. The
    // whole source of the three modules that decide what a map looks like is the input; anything
    // narrower would let a change to a stage ship yesterday's coastline.
    inputs.files(
        rootProject.fileTree("worldgen/src"),
        rootProject.fileTree("cartography/src"),
        rootProject.files("desktop/src/main/kotlin/com/cartogenesis/desktop/SiteImagery.kt")
    ).withPropertyName("generatorSourcesThatDecideWhatTheFiguresShow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.register<Test>("siteTest") {
    group = "verification"
    description = "Assembles cartogenesis.com and checks the tree that would be uploaded."
    dependsOn(":web:assembleSite")
    val testTask = tasks.named<Test>("test").get()
    testClassesDirs = testTask.testClassesDirs
    classpath = testTask.classpath
    filter {
        includeTestsMatching(siteAssemblyClass)
        isFailOnNoMatchingTests = true
    }
    // What this test reads is another project's build output. Declaring it as an input here is
    // what Gradle would want, but it also makes Gradle refuse the build for using an output
    // without a producing dependency it can see. Never being up to date costs a few seconds and
    // is the whole point: the run has to look at the tree that was just assembled.
    outputs.upToDateWhen { false }
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
        // which is exactly what Android could not give it. 4096 wants roughly 2GB, 8192 four times
        // that, and the FFT buffers are transient spikes on top.
        jvmArgs += listOf("-Xmx12g")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Dmg)
            packageName = "Cartogenesis"
            // Declared in gradle.properties so the build is the single source of truth for the
            // version, rather than something to be kept in step by hand at release time.
            packageVersion = providers.gradleProperty("cartogenesisVersion").get()

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
 * `WebDeploymentContractTest` reads the web module's sources, which Gradle has no way to know
 * about: without declaring them, the test task stays up to date when they change, and the build
 * cache cheerfully restores the previous *passing* result. Caught exactly that way - the id was
 * renamed to prove the guard bites, and the guard reported success from cache.
 */
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.fileTree("web/src/wasmJsMain/kotlin"))
        .withPropertyName("webSourcesReadByDeploymentContractTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // `SitePaletteContrastTest` reads the landing page's own CSS and measures every pair of
    // colours it sets. Same trap, caught the same way: without this the task stays up to date
    // when the page changes and the build cache hands back the previous *passing* result. Proved
    // by putting the old page's failing grey back and watching the guard report success.
    inputs.files(rootProject.fileTree("site"))
        .withPropertyName("sitePagesReadByThePaletteContrastTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
