import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The browser front end.
 *
 * Almost nothing lives here. The application is `:ui`, the renderer and save format are shared
 * with the desktop build, and what this module supplies is a page to draw into and the browser's
 * answers to the three questions in `Platform`: where worlds are kept, what export means, and
 * whether there is a GPU.
 */
kotlin {
    // The browser tests run through Karma in a headless Chrome, as `:ui`'s do, because what they
    // test — a folder handle's streams and the private file system behind it — exists only in a
    // browser. Karma reads its settings from `karma.config.d/` beside this file; see the files there.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "cartogenesis.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":ui"))
            implementation(compose.runtime)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Karma is started with an environment of the plugin's making rather than the build's, so the one
// variable the browser tests read is handed on by name. See `RegenerateInteropFixtures` in
// `:desktop`'s tests for what it does.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    providers.environmentVariable("REGENERATE_INTEROP_FIXTURES").orNull?.let {
        environment("REGENERATE_INTEROP_FIXTURES", it)
    }
}

// ---------------------------------------------------------------------------------------------
// cartogenesis.com
// ---------------------------------------------------------------------------------------------

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
 * than a stale one, which is not a theory but what the v1.0.1 deploy did.
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

val browserDistribution = tasks.named("wasmJsBrowserDistribution")

// ---------------------------------------------------------------------------------------------
// How big the download actually is
// ---------------------------------------------------------------------------------------------

/**
 * The token both pages carry where the size of the download goes.
 *
 * Two pages quote it — the landing page's browser note and the loading shell a reader watches
 * while it arrives — and a number typed into either goes stale the first time the bundle changes,
 * silently and in the direction that matters (the figure only ever grows). So neither types one:
 * the assembly measures the files it is about to publish and writes the same figure into both.
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
 * The five faces the landing page sets its type in, taken from the application's own resources.
 *
 * The sixth bundled face, Plex Mono bold, is not here: the page never asks for it, and 154 KB of
 * a weight nothing draws a glyph of is 154 KB. The page's `@font-face` rules name these files, so
 * `SiteAssemblyTest` checks that both lists still agree.
 */
val siteFontFiles = listOf(
    "spectral_regular.ttf",
    "spectral_semibold.ttf",
    "plex_sans_regular.ttf",
    "plex_sans_medium.ttf",
    "plex_mono_regular.ttf"
)

/** Where `:desktop:renderSiteImagery` leaves the figures the page shows. */
val siteImagery = rootProject.layout.projectDirectory.dir("web/build/site-imagery")

// ---------------------------------------------------------------------------------------------
// The roadmap, drawn from the file rather than written on the page
// ---------------------------------------------------------------------------------------------

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
 * Assembles the whole of cartogenesis.com into `web/build/site`, ready to hand to a static host.
 *
 * `Sync` rather than `Copy` because the wasm filenames carry content hashes: a new build lands
 * *beside* the old one instead of replacing it, so a tree that is only ever added to grows about
 * 12 MB of orphans per build and deploys all of them.
 */
tasks.register<Sync>("assembleSite") {
    group = "distribution"
    description = "Assembles cartogenesis.com into web/build/site: the site/ folder plus this " +
        "module's browser build under app/, with the loader stamped."

    // The shell is UTF-8 and full of em dashes. Left to the platform default, filtering reads it
    // as ANSI on Windows and writes mojibake back out; stating the charset is the whole fix.
    filteringCharset = "UTF-8"

    // Every picture on the page is rendered from the engine by this task, from a fixed seed and
    // fixed crop windows, so the release that changes what a coastline looks like changes the
    // coastline the page shows. Nothing in site/ is an image any more.
    dependsOn(":desktop:renderSiteImagery")

    into(layout.buildDirectory.dir("site"))

    // The roadmap the landing page draws: a change to it has to re-assemble the page, and Gradle
    // cannot see a file read inside a copy action.
    inputs.file(roadmapFile).withPropertyName("roadmapTheLandingPageDraws")

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
        // every :desktop: test task declares site/ as an input, so the deploy builds it straight
        // into the assembled tree after this task has run. See docs/DEPLOYMENT.md.
        exclude("downloads.txt")
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

    // The page's typefaces, copied rather than committed a second time: the repository keeps one
    // copy of each face, under ui/, and the site is assembled from it.
    into("fonts") {
        from(rootProject.layout.projectDirectory.dir("ui/src/commonMain/composeResources/font")) {
            siteFontFiles.forEach { include(it) }
        }
    }

    // The figures, and the relief's heights, which are a PNG because they must arrive without
    // loss. `include` rather than the whole directory because `-Pcontact` leaves contact sheets in
    // there, which are a tool for choosing a crop and not part of the site.
    into("img") {
        from(siteImagery) { include("*.webp", "relief-heights.png") }
    }

    // A task stands in for its own output files, and brings the dependency on itself with it.
    into("app") {
        from(browserDistribution)

        // 1.7 MB of debug-only weight that also publishes the original Kotlin.
        exclude("*.map")

        // The build emits its own index.html — a bare div and a script tag. site/app/index.html
        // is the real one: it shows a loading screen, and it is what WebDeploymentContractTest's
        // two names are written against.
        exclude("index.html")

        // composeResources carries the interface's bundled fonts, and is published like any other
        // asset. Before 2.0 it was a tree of empty directories; dropping empty directories keeps
        // that from being published again without needing a rule that names the folder.
        includeEmptyDirs = false
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
        logger.lifecycle("The generator is $megabytes MB compressed; both pages say so")

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
        // never clicks Open pays: the page, its five faces and its figures, and nothing under
        // app/, which is the 12 MB of WebAssembly the launch button fetches.
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
