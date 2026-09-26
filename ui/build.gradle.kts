import java.io.File
import java.time.LocalDate
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    // The settings document is JSON and the release API answers in JSON, and both are read in
    // common code so that neither front end owns the shape of them.
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The interface, once, for every front end.
 *
 * Everything here is Compose Multiplatform and knows nothing about where it is running. The parts
 * that genuinely differ between a desktop window and a browser tab — where saved worlds live, what
 * "export" means, whether there is a GPU to offer — arrive as [com.cartogenesis.ui.Platform],
 * which each front end supplies. What is left is the actual application, and it is shared rather
 * than reimplemented.
 */
kotlin {
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    // The browser tests run through Karma, which reads mocha's timeout from
    // `karma.config.d/mocha-timeout.js` beside this file and nowhere else; see that file. A
    // `useMocha { timeout }` here reaches nothing on a Wasm target: the Kotlin plugin logs that
    // Mocha is not supported for Wasm and never applies the block.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":cartography"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            // The three type faces the theme is set in travel with the module rather than being
            // asked of the host, which is the only way the browser build renders in the same
            // faces as the desktop one instead of in whatever the page happens to have.
            implementation(compose.components.resources)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // The naming guard writes a save header and reads it back, and `WorldCodec.encode` is
            // suspend. Nothing else here needs a coroutine.
            implementation(libs.kotlinx.coroutines.test)
        }
        // The whole-world fingerprint, which the link guard holds a linked world to; see
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

/*
 * Where the generated accessors for `src/commonMain/composeResources` land. Named explicitly
 * rather than left to the plugin's default, which derives a package from the module coordinates
 * and would change under the code if the module were ever renamed. Internal, because the fonts are
 * an implementation detail of the theme.
 */
compose.resources {
    packageOfResClass = "com.cartogenesis.ui.generated.resources"
    publicResClass = false
}

// The JVM tests' share of the processor is the root build script's budget: they run beside the
// other modules' and generate nothing larger than 128 cells, so one processor is what they get.
tasks.named<Test>("jvmTest") {
    val processors = rootProject.extra["lightTestProcessors"] as Int
    jvmArgs(
        "-XX:ActiveProcessorCount=$processors",
        "-Djava.util.concurrent.ForkJoinPool.common.parallelism=$processors"
    )

    // `BugReportFormTest` reads the bug form the report opens: an input, or the task stays up to
    // date, and the build cache hands back an earlier pass, when only the form changes.
    inputs.files(rootProject.fileTree(".github/ISSUE_TEMPLATE"))
        .withPropertyName("issueFormsReadByTheBugReportFormTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // `InterfaceGlyphsTest` reads the characters out of `:web`'s sources, which this module does
    // not compile, and holds them to the bundled faces: both inputs, for the same reason.
    inputs.files(rootProject.fileTree("web/src") { include("**/*.kt") })
        .withPropertyName("webSourcesReadByTheInterfaceGlyphsTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(fileTree("src/commonMain/composeResources/font"))
        .withPropertyName("facesReadByTheInterfaceGlyphsTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

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

/*
 * ---------------------------------------------------------------------------------------------
 * What the About dialog and the update check know about this build, generated rather than typed.
 *
 * Two facts and one list, and all three are the kind of thing that rots silently when written by
 * hand: a version constant drifts from `gradle.properties` the first time somebody renumbers a
 * release (this project has done exactly that — see the note in `gradle.properties` about a 1.2.0
 * zip that turned out byte-identical to the 1.0.0 one), a build date typed into source is a lie
 * the moment it is committed, and a hand-written notices list stops naming what the build actually
 * contains the first time a dependency changes. So all three are produced here, from the build's
 * own inputs, and `:ui` compiles the result.
 * ---------------------------------------------------------------------------------------------
 */

/** Declared once, in `gradle.properties`, exactly as the installer's version is. */
val declaredVersion: Provider<String> = providers.gradleProperty("cartogenesisVersion")

/**
 * The build date, to the day.
 *
 * To the day and not to the second on purpose: a timestamp would put this task, and therefore
 * every compilation of `:ui`, out of date on every run, and "built 2026-09-12" is what an About
 * dialog is asked for anyway. `-PbuildDate=…` pins it, for a reproducible release build.
 */
val buildDate: Provider<String> = providers.gradleProperty("buildDate")
    .orElse(providers.provider { LocalDate.now().toString() })

/**
 * The project's own licence, read from the repository root rather than asserted here.
 *
 * There is no licence file in this repository at all, so this says so in as many words. That is
 * the honest answer and it is visible in the About dialog, which is the point: a constant reading
 * "MIT" would be a claim nobody has made.
 */
val licenceNotice: Provider<String> = providers.provider {
    val file = listOf("LICENSE", "LICENSE.md", "LICENSE.txt", "LICENCE", "LICENCE.md", "COPYING")
        .map { rootProject.layout.projectDirectory.file(it).asFile }
        .firstOrNull { it.isFile }
    if (file == null) {
        "No licence has been declared for Cartogenesis: there is no licence file in the " +
            "repository, so no terms of use are granted or implied. The third-party components " +
            "below carry their own licences, which apply regardless."
    } else {
        val firstLine = file.readLines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val suffix = if (firstLine.isEmpty()) "" else ": $firstLine"
        "Cartogenesis is distributed under the terms in ${file.name}$suffix"
    }
}

val generateBuildInfo = tasks.register("generateBuildInfo") {
    val output = layout.buildDirectory.dir("generated/buildInfo/kotlin")
    outputs.dir(output)
    inputs.property("version", declaredVersion)
    inputs.property("date", buildDate)
    inputs.property("licence", licenceNotice)
    val version = declaredVersion
    val date = buildDate
    val licence = licenceNotice
    doLast {
        fun String.escaped(): String = replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", "")

        val dir = output.get().asFile.resolve("com/cartogenesis/ui")
        dir.mkdirs()
        dir.resolve("BuildInfo.kt").writeText(
            buildString {
                appendLine("package com.cartogenesis.ui")
                appendLine()
                appendLine("/**")
                appendLine(" * Which build this is. **Generated by `ui/build.gradle.kts`; do not edit.**")
                appendLine(" *")
                appendLine(" * The version comes from `gradle.properties`, which is also where the")
                appendLine(" * installer's version comes from, so the About dialog and the packaged")
                appendLine(" * artefact cannot disagree about which release this is.")
                appendLine(" *")
                appendLine(" * Public rather than internal: a data export's sidecar names the")
                appendLine(" * build that wrote it, and the two front ends are the ones writing")
                appendLine(" * the file, so they have to be able to read this.")
                appendLine(" */")
                appendLine("object BuildInfo {")
                appendLine("    const val VERSION: String = \"${version.get().escaped()}\"")
                appendLine("    const val BUILD_DATE: String = \"${date.get().escaped()}\"")
                appendLine(
                    "    const val PROJECT_URL: String = " +
                        "\"https://github.com/bhanright/Cartogenesis\""
                )
                appendLine("    const val LICENCE: String = \"${licence.get().escaped()}\"")
                appendLine("}")
            }
        )
    }
}

/**
 * Everything the build resolves, with the licence its POM declares.
 *
 * Three graphs are read. `:ui`'s own JVM runtime is the bulk of it — Compose, Skiko, kotlinx and
 * what they drag in — and its Wasm runtime adds whatever the browser bundle carries that the
 * desktop does not. The third is [noticesExtra], which exists because the desktop front end links
 * LWJGL and this module cannot depend on `:desktop` (that module depends on *this* one, and a
 * configuration cycle is a build failure rather than a clever trick). Its coordinates come from the
 * version catalog, so the versions are still declared in exactly one place.
 */
val noticesExtra: Configuration = configurations.create("noticesExtra") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    noticesExtra(platform(libs.lwjgl.bom))
    noticesExtra(libs.lwjgl.core)
    noticesExtra(libs.lwjgl.opengl)
    noticesExtra(libs.lwjgl.glfw)
}

/** `group:name:version|/path/to/artifact`, flattened so the task's input is plain strings. */
fun graphOf(name: String): Provider<List<String>> =
    configurations.named(name).flatMap { it.incoming.artifacts.resolvedArtifacts }
        .map { artifacts ->
            artifacts.mapNotNull { artifact ->
                val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier
                    ?: return@mapNotNull null
                "${id.group}:${id.module}:${id.version}|${artifact.file.absolutePath}"
            }
        }

val generateNotices = tasks.register("generateNotices") {
    val output = layout.buildDirectory.dir("generated/notices/kotlin")
    outputs.dir(output)

    val graphs = objects.listProperty(String::class.java)
    graphs.addAll(graphOf("jvmRuntimeClasspath"))
    configurations.findByName("wasmJsRuntimeClasspath")?.takeIf { it.isCanBeResolved }?.let {
        graphs.addAll(graphOf(it.name))
    }
    graphs.addAll(graphOf("noticesExtra"))
    inputs.property("graph", graphs)

    // The three faces are bundled as Compose resources rather than resolved as dependencies, so
    // they appear in no graph — and they are the three notices that are a licence requirement
    // rather than a courtesy. The OFL text beside them is what is read here. The mono cut, which
    // Matrix sets its type in, comes from the same IBM Plex release as the sans.
    inputs.files(layout.projectDirectory.dir("licences").asFileTree)
        .withPropertyName("bundledFontLicences")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    val spectral = layout.projectDirectory.file("licences/OFL-Spectral.txt").asFile
    val plex = layout.projectDirectory.file("licences/OFL-IBMPlexSans.txt").asFile
    val plexMono = layout.projectDirectory.file("licences/OFL-IBMPlexMono.txt").asFile

    doLast {
        val undeclared = "Licence not declared in this component's POM"

        fun String.escaped(): String = replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", "")

        // The `<licenses>` block of the POM beside an artifact in Gradle's module cache. The cache
        // lays a module out as `…/<group>/<name>/<version>/<hash>/<file>`, with the POM under a
        // sibling hash directory of the jar, so the version directory is two levels up and the POM
        // is the one `.pom` beneath it. A small regex rather than an XML parser: this reads one
        // element out of a file Gradle itself wrote, and adding a parser to the build for it would
        // be the larger risk. A POM that inherits its licence from a parent this does not follow
        // comes back as `undeclared` rather than being dropped, because a component with no stated
        // licence is precisely the one somebody needs to know about.
        fun licenceOf(artifact: File): String {
            val versionDir = artifact.parentFile?.parentFile ?: return undeclared
            val pom = versionDir.walkTopDown().maxDepth(2)
                .firstOrNull { it.isFile && it.name.endsWith(".pom") } ?: return undeclared
            val text = runCatching { pom.readText() }.getOrNull() ?: return undeclared
            val block = Regex("<licenses>(.*?)</licenses>", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1) ?: return undeclared
            val names = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
                .findAll(block)
                .map { it.groupValues[1].trim().replace(Regex("\\s+"), " ") }
                .filter { it.isNotEmpty() }
                .toList()
            return if (names.isEmpty()) undeclared else names.joinToString("; ")
        }

        fun ofl(face: String, file: File): Pair<String, String> {
            val heading = file.takeIf { it.isFile }?.readLines()
                ?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val licence =
                if (heading.isEmpty()) "SIL Open Font License 1.1"
                else "SIL Open Font License 1.1 - $heading"
            return "$face (bundled type face)" to licence
        }

        val resolved = sortedMapOf<String, String>()
        listOf(
            ofl("Spectral", spectral),
            ofl("IBM Plex Sans", plex),
            ofl("IBM Plex Mono", plexMono)
        ).forEach { (name, licence) -> resolved[name] = licence }

        graphs.get().forEach { entry ->
            val coordinates = entry.substringBefore('|')
            if (!resolved.containsKey(coordinates)) {
                resolved[coordinates] = licenceOf(File(entry.substringAfter('|')))
            }
        }

        val dir = output.get().asFile.resolve("com/cartogenesis/ui")
        dir.mkdirs()
        dir.resolve("Notices.kt").writeText(
            buildString {
                appendLine("package com.cartogenesis.ui")
                appendLine()
                appendLine("/**")
                appendLine(" * What this build is made of. **Generated by `ui/build.gradle.kts`; do not edit.**")
                appendLine(" *")
                appendLine(" * Every resolved artifact of the interface, the desktop's graphics library and")
                appendLine(" * the three bundled type faces, with whatever licence each POM declares. Read by")
                appendLine(" * the About dialog, and checked by `NoticesTest`.")
                appendLine(" */")
                appendLine("internal object Notices {")
                appendLine()
                appendLine("    class Notice(val name: String, val licence: String)")
                appendLine()
                appendLine("    val entries: List<Notice> = listOf(")
                resolved.entries.forEachIndexed { index, (name, licence) ->
                    val comma = if (index == resolved.size - 1) "" else ","
                    appendLine("        Notice(\"${name.escaped()}\", \"${licence.escaped()}\")$comma")
                }
                appendLine("    )")
                appendLine("}")
            }
        )
        logger.lifecycle("NOTICES generated ${resolved.size} entries")
    }
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generateBuildInfo)
    kotlin.srcDir(generateNotices)
}
