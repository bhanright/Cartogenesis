# The browser version

Cartogenesis ran in a browser as well as on the desktop until G1 (docs/DESIGN_LEDGER.md) removed
the browser build to spend the development budget on one application. This note is the archive:
what the browser version was, how it was built, tested and deployed, what was removed, how the
frozen copy is served today, and how to bring the browser version back.

The last commit that built it is **8198db27** on main, tagged **`web-frozen`** (an annotated tag on
the repository). Everything below that is in the past tense can be done again from that tag.

## What it was

- **`:web`**, the browser front end: `web/src/wasmJsMain` (the page's entry point `Main.kt`, the
  `Platform` implementation `WebPlatform.kt`, IndexedDB and folder libraries `WebStorage.kt` and
  `FolderLibrary.kt`, the `?selftest` and `?foldertest` pages, and WebGPU), its browser tests in
  `web/src/wasmJsTest`, and its Karma settings in `web/karma.config.d`. Its build script also held
  the site's assembly, `:web:assembleSite`.
- **The Kotlin/Wasm targets** of the multiplatform modules: `wasmJs { nodejs() }` in `:worldgen` and
  `:cartography`, `wasmJs { browser() }` in `:ui`, with their source sets —
  `worldgen/src/wasmJsMain` (the one-thread `parallelFor` and `standAside` actuals),
  `cartography/src/wasmJsTest` (the test gzip through `CompressionStream`), `ui/src/wasmJsMain`
  (`epochMillis`, `randomId`, `formatTimestamp`) and `ui/src/wasmJsTest`, and `ui/karma.config.d`.
  A Kotlin/JS target had been removed earlier (T1); `worldgen/src/jsMain` was its leftover.
- **The WGSL paths**: `WebGpu.kt`, `WebGpuErosion.kt`, `WebGpuOcean.kt`, `WebGpuIceSheet.kt` and the
  shader sources in `WgslModules.kt`, all in `:web`, implementing the same accelerator seams the
  desktop's OpenGL compute implements; `WgslCompilesTest` and `WgslReservedWordsTest` guarded them.
- **The parity tests**: `CrossPlatformFingerprintTest` in `:worldgen`'s common tests printed
  `FINGERPRINT` lines on the JVM and on Wasm, which CI compared as a warning, not a failure (a save
  carries its world, so the platforms did not have to agree to the bit); the common suites of
  `:worldgen`, `:cartography` and `:ui` ran on both targets; `GzipInteroperabilityTest` decoded one
  checked-in save with both platforms' gzip; and `FolderInteropTest` on each side opened a save the
  other side's library wrote (`RegenerateInteropFixtures` in `:desktop` made both).
- **The site build**: `:web:assembleSite` synced `site/` with the production bundle under `app/`,
  stamped the loader, measured the download and drew the roadmap, into `web/build/site`;
  `:desktop:siteTest` assembled it and checked it, with `WebDeploymentContractTest` pinning the two
  names the loading shell reaches for in the Kotlin source.

## How it was built, tested and deployed

From a checkout of the tag:

```bash
git checkout web-frozen

# Run it: the optimized bundle at http://localhost:8080. Wasm does not load from file://, and
# WebGPU needs a secure context, which localhost is.
./gradlew :web:wasmJsBrowserProductionRun

# A static copy of the application, for any host, in web/build/dist/wasmJs/productionExecutable.
./gradlew :web:wasmJsBrowserDistribution

# The browser tiers. The two Karma suites need Chrome (CHROME_BIN, which CI's runner sets).
./gradlew :worldgen:wasmJsNodeTest :cartography:wasmJsNodeTest
./gradlew :ui:wasmJsTest :web:wasmJsTest

# The site, assembled into web/build/site and checked.
./gradlew :web:assembleSite :desktop:siteTest
```

`.github/workflows/ci.yml` ran `:worldgen:wasmJsNodeTest` and `:cartography:wasmJsNodeTest` in the
engine job, compared the `FINGERPRINT` lines, and ran `:ui:wasmJsTest` and `:web:wasmJsTest` in the
desktop job. `.github/workflows/site.yml` ran `:web:assembleSite :desktop:siteTest` on each `v*` tag
and uploaded `web/build/site`. Each release also carried `Cartogenesis-<version>-web.zip`, made by
hand from the production distribution, for hosting elsewhere. When the save format moved, the two
interoperability saves were made again by `RegenerateInteropFixtures`, whose KDoc gave the three
steps.

What the browser tiers cost, measured on the tag on the development machine (sixteen threads) on
2026-10-04, with the main code already compiled: 4 min 47 s of wall time for the four together —
`:worldgen:wasmJsNodeTest` 4 min 36 s (42 tests), `:cartography:wasmJsNodeTest` 59 s (49),
`:ui:wasmJsBrowserTest` 46 s (165) and `:web:wasmJsBrowserTest` 32 s (45). The production bundle and
the site from a clean checkout took 8 min 58 s, the site's figures included.

## What G1 removed

On `chunk/g1-single-grid`, first in commit c70d3115 ("remove the browser module, the Wasm targets'
sources and the parity tests") and then in the commits after it:

- deleted: `web/`, `kotlin-js-store/`, `ui/karma.config.d/`, `worldgen/src/jsMain`,
  `worldgen/src/wasmJsMain`, `cartography/src/wasmJsTest`, `ui/src/wasmJsMain`,
  `ui/src/wasmJsTest`, `CrossPlatformFingerprintTest`, `WebDeploymentContractTest` and
  `RegenerateInteropFixtures`;
- edited: `settings.gradle.kts` (the `:web` include and the Node.js, Binaryen and Yarn
  repositories), `gradle.properties` (`kotlin.incremental.wasm=false`), the `wasmJs` targets and the
  `mustRunAfter` on Wasm tasks in `:worldgen`'s, `:cartography`'s and `:ui`'s build scripts, `:ui`'s
  notices (the Wasm runtime graph) and `InterfaceGlyphsTest`'s reading of `web/src`, `:desktop`'s
  build script (the site's assembly moved into it, reading the stored copy), `FolderInteropTest`
  (its desktop-written half), `ci.yml` and `site.yml`;
- and, as part of the one grid rather than of the browser, the interface's grid and export-size
  chips, the settings that stored them, and the large-link question (`LargeLinks`).

## What was kept so it can come back

- **The accelerator seams**, in common code: `ErosionAccelerator`, `OceanAccelerator`,
  `IceSheetAccelerator` (`:worldgen`) and `RasterAccelerator` (`:cartography`). They suspend
  because WebGPU answers with promises; the WGSL kernels plug into them unchanged.
- **The `expect` declarations** with only the JVM's `actual` left: `parallelFor`,
  `parallelChunks`, `parallelism` and `standAside` (`:worldgen`), `epochMillis`, `randomId` and
  `formatTimestamp` (`:ui`), and in the tests `platformGzip*` (`:cartography`) and
  `appendToKnownFailuresReport` (`:ui`). Restoring the removed `wasmJs` source sets supplies the
  other `actual`s.
- **Common code stays common.** The modules are still Kotlin Multiplatform with `commonMain` and
  `commonTest`; G1 moved nothing out of them. The `Platform` interface in `:ui` keeps every member
  `WebPlatform` implemented (`openedAt`, `worldLinkBase`, `generationCeiling`, `heapBytes`,
  `coarsePointer`, `graphicsApiPresent` and the rest), `WorldCeilings.BROWSER_TAB` is still there,
  and `:web`'s sources at the tag name nothing of `:ui` that G1 removed.
- **The site's assembly** keeps the loader stamp, the download measurement and the shell's two
  names, and `SiteAssemblyTest` still checks the browser application's tree.

## How the frozen copy is served now

The site still serves the browser version at `/app/`, as an older preview. It is not built: it is
`web-frozen.zip`, attached to the GitHub release `web-frozen` (which hangs off the tag), holding
`app/` exactly as `:web:assembleSite` assembled it at the tag, less the loading shell and the source
map. `site.yml` downloads it before the assembly:

```bash
gh release download web-frozen --pattern web-frozen.zip --dir build/web-frozen
./gradlew :desktop:assembleSite :desktop:siteTest
```

`:desktop:assembleSite` checks the zip's SHA-256 against `frozenWebAppSha256` in
`desktop/build.gradle.kts`, unpacks it, lays `site/` over it (so `site/app/index.html`, the shell,
is the current one, with its note that the preview is older), renders the figures and writes
`desktop/build/site`. A deploy without the release fails at the download rather than publishing a
site with no `/app/`.

**To refresh the stored copy**, for instance from a revived browser build: assemble the site with
the browser module (`:web:assembleSite` at the tag, or its successor), zip that tree's `app/`
directory under an `app/` prefix without `index.html` and `*.map`, replace the asset on the
`web-frozen` release, and put the new zip's SHA-256 in `frozenWebAppSha256` in the same commit. The
zip on the release today was made that way from a clean export of the tag; its SHA-256 is
`184683473159993afa34c79f5d2b8027ade1a1263dec1919a04b143bc4108023`.

## How to revive it

Two ways, depending on what is wanted.

- **As it was.** Check out the tag and build there, as above. It is the generator as it stood at
  8198db27.
- **On a later main.** Restore the deleted paths from the tag, then put back the edits by hand:

  ```bash
  git checkout web-frozen -- web kotlin-js-store ui/karma.config.d \
    worldgen/src/wasmJsMain cartography/src/wasmJsTest ui/src/wasmJsMain ui/src/wasmJsTest \
    worldgen/src/commonTest/kotlin/com/cartogenesis/worldgen/CrossPlatformFingerprintTest.kt \
    desktop/src/test/kotlin/com/cartogenesis/desktop/WebDeploymentContractTest.kt \
    desktop/src/test/kotlin/com/cartogenesis/desktop/RegenerateInteropFixtures.kt
  ```

  Then: `include(":web")` and the three repositories in `settings.gradle.kts`; the `wasmJs` targets
  in the three modules' build scripts (`git show web-frozen:<module>/build.gradle.kts` has them); the
  Wasm graph in `:ui`'s notices; `web/src` back in `InterfaceGlyphsTest`; the browser jobs in
  `ci.yml`; and the site's `app/` taken from `:web:wasmJsBrowserDistribution` instead of the zip in
  `:desktop:assembleSite`. Whatever the common code gained since the tag must compile for Wasm too:
  anything JVM-only in `commonMain` is the first thing a revived build will find. And the interface
  now makes one grid; a browser's grid is `Platform.defaultResolution`, which `WebPlatform` sets to
  512, under `generationCeiling`, which it sets to `WorldCeilings.BROWSER_TAB`.
