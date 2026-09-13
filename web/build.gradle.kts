import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

    from(rootProject.layout.projectDirectory.dir("site")) {
        // Documentation for whoever maintains the site, not part of the site.
        exclude("README.md")

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

    // The figures. `include` rather than the whole directory because `-Pcontact` leaves contact
    // sheets in there, which are a tool for choosing a crop and not part of the site.
    into("img") {
        from(siteImagery) { include("*.webp") }
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
        val shell = File(site, "app/index.html")
        // A stamp left unreplaced means the scoped replacement above stopped matching — a rename
        // of the loader, or the src attribute reformatted. Silent otherwise, and the failure it
        // leads to is a 404 on a returning visitor's second deploy, which is a bad way to find out.
        check(shell.isFile && !shell.readText(Charsets.UTF_8).contains(loaderStampPlaceholder)) {
            "app/index.html still carries the loader placeholder. The replacement is scoped to " +
                """src="cartogenesis.js?v=..."; check that attribute in site/app/index.html."""
        }
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
