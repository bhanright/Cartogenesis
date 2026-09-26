# Cartogenesis

[![CI](https://github.com/bhanright/Cartogenesis/actions/workflows/ci.yml/badge.svg)](https://github.com/bhanright/Cartogenesis/actions/workflows/ci.yml)
[![Nightly audit](https://github.com/bhanright/Cartogenesis/actions/workflows/nightly.yml/badge.svg)](https://github.com/bhanright/Cartogenesis/actions/workflows/nightly.yml)

Cartogenesis generates fantasy world maps from a seed. It models plate tectonics, erosion, climate
and drainage, then adds realms, peoples and landmarks. The desktop and browser versions share a
Kotlin Multiplatform engine and a Compose Multiplatform interface.

**Try it** at [cartogenesis.com](https://cartogenesis.com). It runs entirely in the browser and
uploads nothing.

**Download it** from the [latest release](https://github.com/bhanright/Cartogenesis/releases/latest):
a portable Windows zip, an MSI installer, a Debian package and a portable Linux tarball, or the
browser build as a zip for hosting yourself. Debian and Ubuntu can install and stay up to date from
an apt repository on the site. The download bundles its own Java runtime; nothing needs to be
installed first. [docs/INSTALL.md](docs/INSTALL.md) has the steps for each platform, including the
three apt commands and what to do about the unsigned installer's SmartScreen warning.

## What you get

- Fifteen map views: the fantasy map, political borders, peoples, elevation, biomes, seasonal
  temperature and rainfall, tectonic plates, ocean currents and winds.
- Twelve map styles, from a modern atlas to parchment, ink, a nautical chart, Mars and a
  satellite-colour Natural style, plus a colour-blind-safe palette.
- Interface themes grouped into Standard, Accessible and Styled.
- Editing of generated names and borders, kept with the world.
- Save files containing the generated world and your edits, readable by both front ends.
- Image exports as PNG, WebP or JPEG, drawn at the world's true shape — a picture of an N world is
  2N × N, so a 4096 world is 8192 × 4096 — and data exports (heightmap, biome map, realm map), one
  sample per cell with JSON sidecars.

MIT licensed; see [LICENSE](LICENSE).

## Quick start

Run the desktop app:

```bash
./gradlew :desktop:run
```

Run the browser build:

```bash
./gradlew :web:wasmJsBrowserProductionRun
```

This serves the optimised bundle at http://localhost:8080. Use the production task for interactive
testing; the development build (`wasmJsBrowserDevelopmentRun`) uses unoptimised WebAssembly and runs
several times slower. `./gradlew :web:wasmJsBrowserDistribution` writes a static site to
`web/build/dist/wasmJs/productionExecutable` instead of serving it. The build must be served over
HTTP: Wasm does not load from `file://`, and WebGPU needs a secure context, which `localhost` is.

While a world is generating, the Generate button reads **Stop**. Selecting it cancels within a
round of erosion, keeps the previous map on screen, and shows which stage was interrupted. Partially
generated worlds are discarded.

## How a world is made

Each stage feeds the next, and every stage is deterministic for a given seed. The world has a
declared physical size (`WorldScale`): 12,000 km across, land up to 6,000 m above the waterline, sea
floor down to 10,000 m below it, and one hydraulic round standing for about 336,000 years
(`WorldScale.yearsPerHydraulicRound`, 336,476.4, derived from the stream-power constants rather
than chosen), so twelve rounds are about four million years. Both ends of the vertical range are cell means rather than points — a cell of the
default grid is 23 km across, and no cell that size holds a summit. Every reach, depth and rate in
the generator is written in those units and converted to whatever grid the world is generated at.

1. **Terrain.** Seeded Perlin noise produces a gradient field, integrated into a height map by
   Frankot–Chellappa least-squares integration (a 2D FFT). The terrain filter emphasises relief at
   roughly 400 km while keeping some variation across the whole map; these broad slopes are what let
   long river systems form. Below 200 km, roughness depends on the surrounding relief, so plains
   come out smooth and mountain ranges rough.
2. **Plates.** The world splits into drifting Voronoi plates of continental or oceanic crust.
   Boundaries are classified by relative motion and by which crusts meet, raising coastal ranges,
   collision plateaus, island arcs, rifts or ridges. Three earlier epochs are stamped and aged first,
   so old worn ranges can stand far from any present boundary. Isostasy then turns crust into
   altitude: continental crust floats at Earth's mean land elevation, thicker and higher in the
   interior and thinner toward its margins, so the edge of a continent drowns as a shelf; the sea
   floor sits at the depth its age gives it (Parsons and Sclater).
3. **Erosion.** Thermal erosion moves material off slopes steeper than a critical gradient, and
   stream-power incision (`E = K A^0.5 S`) cuts channels in proportion to the water draining through
   them, round by round, solved implicitly (Braun and Willett 2013) from the outlets upstream, so the
   law and not a numerical limit sets every cut and no cell is cut below the sea or below the cell
   it drains into. Uplift continues under active belts during the same rounds, and the plate
   flexes under what the water moves: stripped ranges rebound, forelands sink under sediment, and an
   ice sheet holds its bed down.
4. **Deposition.** Sediment settles where a cell's load exceeds what its slope can carry: graded
   floodplains along lower trunks, deltas at the sea, fans at range fronts and lake inflows. Mass
   moved off the land equals mass laid down or carried to sea.
5. **Sea level.** Sea level is a chosen elevation percentile. The rounds before it grade to a lower
   base level (Earth's last lowstand), so the rise afterwards drowns lower valleys into estuaries. Two
   passes then shape the coast: drowned valleys narrower than half a cell return to land, and on the
   low-lying third of the shoreline sediment fills the small re-entrants, so coasts on plains come
   out as graded arcs while coasts under mountains keep their rias.
6. **Shelves.** Sea floor near a coast is remapped onto a shallow continental shelf falling away to
   the abyss, so the coastline reads as bathymetry rather than an underwater cliff.
7. **Currents.** Ocean currents are calculated from wind stress within each ocean basin. The
   resulting gyres produce warm poleward currents along western ocean margins and cold equatorward
   currents along eastern margins.
8. **Climate.** Temperature comes from a one-dimensional energy balance over latitude bands marched
   through the year, with separate air columns over land and sea and a shallow ocean slab, so
   continents get winters and coasts stay milder. Albedo follows the ice the model grows. Rainfall
   comes from moist air marched along wind belts that shift between two seasons, giving rain shadows
   and monsoons; biomes are classified Köppen-style from the seasonal figures. A snow mass balance
   decides where land ice can persist.
9. **Glaciation.** Where the snow balance is positive, valley glaciers widen river valleys into
   U-shaped troughs with cirques at their heads, and ice sheets scour flat ground into the closed
   basins of shield lake country.
10. **Rivers and lakes.** Depressions are filled so no water dead-ends inland, flow is routed downhill
    and traced to the coast, and each basin's outlet incises its sill over time. A filled basin
    becomes a lake only as far as its water balance allows; where evaporation wins it sits below its
    rim as an endorheic lake or dries to a playa, and a basin that closes keeps its rain from the
    basins below it. Discharge is the rain that falls, in millimetres, summed downstream. Rivers run
    from their farthest headwater, are drawn at a width proportional to the square root of their
    discharge (Leopold and Maddock), and stop at the shoreline.
11. **Realms.** Borders are assigned by whole drainage catchment, so frontiers fall on watersheds.
    Catchments are cut at their confluences to a bounded area of ground, a closed basin stays whole
    with its lake, and a small one joins a neighbour on its own landmass. Large catchments are split
    along their trunk river, enclaves dissolve into their surrounding neighbour, and no realm holds
    more than 30% of the world's land. Each realm's population, exports and
    imports derive from the land it holds.
12. **Peoples.** A second, independent layer: cultures spread from seeded hearths at a cost set by
    how unlike home the next land is, so a people's territory follows climate rather than politics.
13. **Landmarks.** Lairs, ruins, hazards and resources are placed on terrain that suits them, biased
    toward land no realm claims.

The derivation behind each stage, with the measurements that shaped it, is in
[docs/DESIGN_LEDGER.md](docs/DESIGN_LEDGER.md); what the generator holds by construction, and where
it still deviates from Earth, is in [docs/GEOGRAPHY.md](docs/GEOGRAPHY.md).

## Map styles, views and colours

The twelve styles are **Atlas** (elevation and climate tints), **Vellum** (aged parchment and
sepia ink), **Ink wash** (sumi-e grey on pale paper), **Nautical** (an admiralty chart with
depth-banded water), **Midnight** (moonlit, rivers left luminous), **Schoolroom** (a saturated
classroom wall map), **Verdant** (illustrated fantasy: teal sea, cream land, deep woods),
**Scroll** (painted parchment, jade sea, vermilion marks), **Pen and ink** (line art, relief hatched
by slope), **Mars** (the same world as a dry planet), **Natural** (a palette sampled from a Blue
Marble photograph of Earth) and **Colour-blind** (a cividis land ramp over one flat sea, so nothing
is told by hue alone).

A style changes only appearance. The same seed gives the same world in every style, and the
diagnostic views (elevation, biomes, climate and the rest) ignore styles, since their colours carry
meaning. `StyleGalleryTest` renders all twelve and asserts that they differ from one another. Most
of the difference between styles is four settings: how much vegetation colour shows through, how far
biome colours are pulled toward the paper, how far the height ramp follows the climate, and how
strongly the relief is shaded.

**Climate colours.** An elevation-only palette can make a desert plain look as green as a wet one.
Cartogenesis adjusts terrain colours using vegetation, aridity and ice cover, and each style controls
how strongly those adjustments affect its palette. Aridity follows De Martonne's index.

**Relief.** The default relief is lit from the whole sky rather than one north-west lamp, following
Kennelly and Stewart's sky models, so slopes facing away from the light still read. The single lamp
remains as a toggle in the Cartography section. Depth contours are drawn every 500 m in the sea
and fade out on abyssal plains and where they would crowd.

The derivations and measurements for the tints, the sky light and the contours are in
[docs/DESIGN_LEDGER.md](docs/DESIGN_LEDGER.md) (F13).

## Map scale, coordinates and detail

Maps include scale-dependent detail, an optional latitude–longitude grid, and a scale bar. These
affect how the map is drawn without changing the generated world.

- **Generalisation.** A sheet draws as much river line per square kilometre of land as a published
  map at its own scale does — measured off Natural Earth's 1:50M and 1:10M river layers and carried
  between scales by Töpfer and Pillewizer's radical law — so the faintest rivers are dropped as you
  zoom out and return as you zoom in, and the same country looks the same whether the world behind
  it was generated at 512 or at 2048. A River density slider in the Cartography panel scales that
  ink from a quarter of the published figure, which still keeps the largest river, up to every
  river the sheet has room for. The coast is traced as a simplified polyline over the raster.
- **Graticule.** Lines every ten degrees with figured edges (`40°N`, `170°W`), on screen and on
  exports.
- **Scale bar.** In the legend and on exports, restating itself as you zoom; the cartouche gives
  the scale at the sheet's own size — one figure, since the map is drawn at the world's true shape
  and a pixel covers the same ground either way — quoted at the equator because east–west distance
  on an equirectangular map shrinks with latitude.
- **True shape.** The world is twice as wide as it is tall on the ground, and its grid is square,
  so a cell is twice as wide as it is tall. Every picture — on screen and exported — draws a cell
  two pixels wide and one tall, copying its colour exactly, and lays the ink over it at its own
  width; data exports keep the grid, one sample per cell.

All of these read the one declared width, `WorldScale.worldWidthKm`, and are drawn as geometry so
they appear identically on screen and in a PNG. The measurements behind them are in
[docs/GEOGRAPHY.md](docs/GEOGRAPHY.md) and [docs/DESIGN_LEDGER.md](docs/DESIGN_LEDGER.md) (F14).

## Exports

Export re-runs the whole pipeline at the target size rather than upscaling the preview, so a larger
map is more detailed. `WorldGenConfig.atResolution` rescales every cell-based setting to make that
true; a new cell-based setting must be added there or exports will drift from the preview.

**Pictures** are PNG, WebP or JPEG. PNG is lossless. WebP is smaller and loses a little detail in
thin rivers and borders. JPEG is for tools that will not open WebP; it is smaller still and softer.
The measured trade-offs are in [docs/PERFORMANCE.md](docs/PERFORMANCE.md); `ExportSmokeTest` and
`DataExportTest` keep the interface's descriptions true.

**Data** exports write the numbers behind the picture, for Blender, Unity, Unreal and QGIS:

| Data export | Image | Sidecar carries |
|---|---|---|
| Heightmap | 16-bit greyscale PNG, one cell per pixel | the metre scale, the sea-level grey value, the cell size |
| Biomes | 8-bit palette PNG, one biome ordinal per pixel | index → biome name, colour and cell count |
| Realms | 8-bit palette PNG, sea 0, unclaimed land 1, realms from 2 | index → realm name, colour and cell count |

Each data export is a PNG and a JSON sidecar of the same name. The sidecar carries the seed, the
pixel dimensions (the grid's, one sample per cell, unlike the picture exports), the world's width
(12,000 km), the cell size east-west and north-south, the square kilometres per cell, the
save format version and the build. **Sea level is grey level 32768 on every world**, fixed rather
than derived per world, because its job is to be typed into somebody else's program. There are
32767 levels either side of the waterline, and the sidecar states a metres-per-level figure for
each half, because the vertical range is two numbers rather than one: at the defaults a level above
the waterline is 0.1831 m and one below it 0.3052 m, so white is +6,000 m and black is -10,000 m.
Land below the waterline is written as it is, not clamped — a basin the sea cannot reach drains out
into a salt flat below sea level, which on seed 42 at 512 is 497 cells.

In the browser, data exports download as a ZIP containing the PNG and its JSON; this keeps the
files together and avoids browser restrictions on multiple downloads. Why the PNGs come out of this
project's own encoder, and why the browser sends one archive rather than two files, are in
[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

Generation is most of an export's cost: at 4096, about three minutes of world against a few
seconds of encoding.

## Resolution, limits and acceleration

The interface offers generation resolutions of 512 to 4096 and exports of 2048, 4096 and 8192. The
desktop goes to 4096; 8192 is shown disabled because the world's fields exhaust a 10 GB heap during
generation, before anything is drawn. The browser goes to 2048, on a phone or a computer, for the
world on screen and for exports: a 4096 generation killed a desktop browser's tab before anything
was drawn, so the 4096 chips are shown disabled there, a stored 4096 preference is brought down to
2048 with a line saying why, and a 4096 save from the desktop is refused from its header rather than
opened into a tab that cannot hold its 2.45 GB of arrays. One ceiling covers both rows because an
export makes the world again at its own size; it lives in `Platform.generationCeiling`, with the two
values and their measurements in `WorldCeilings`. The browser starts at a generation resolution of
512 and the desktop at 1024, because a browser tab has one thread and generation blocks the page
while it runs.

**Graphics acceleration** is an opt-in toggle in the header and in Settings (as *Graphics
acceleration at launch*). It runs the erosion sweeps and the ocean-current solve on the graphics
device on both platforms, and the export raster on the desktop as well (OpenGL compute on the
desktop, WGSL in the browser); the panel says which through the `Platform` seam. Drawing the map
runs on the graphics device unconditionally, outside this toggle, because rasterising pixels makes
no promise about reproducing a world from its seed. The accelerated erosion agrees with the
processor to about seven parts in a million but is not bit-identical, so a world generated with
acceleration stores its terrain in the save (`TerrainSnapshot`) rather than relying on
regeneration. The browser path can be checked on any machine by loading the web build with
`?selftest` in the URL.

Measured timings, and the reasons behind the 8192 limit and the erosion cost, are in
[docs/PERFORMANCE.md](docs/PERFORMANCE.md), with the machine and date beside each table.

## Modules

- `:worldgen`: world generation, shared between JVM and WebAssembly.
- `:cartography`: map rendering and vector-overlay geometry, and the save format. Per-pixel work is
  plain `IntArray` maths and overlays are described as geometry, so every platform decides alike.
- `:ui`: the shared Compose Multiplatform interface, including the renderer.
- `:desktop`: desktop integration, including file dialogs and OpenGL.
- `:web`: browser integration, including local storage, downloads and WebGPU.

The two front ends are the same application. What differs arrives through the `Platform` seam:
where saves live, what export means, whether a graphics device exists.

`:worldgen` targets **jvm** and **wasmJs**. The correctness suite lives in `commonTest` and runs on
both; `DebugMapDump` stays JVM-only because it renders through `java.awt`. A third target, **js**,
was removed in T1; the reason is in [docs/DESIGN_LEDGER.md](docs/DESIGN_LEDGER.md).

## Building and testing

Built and verified against JDK 21, Kotlin 2.4.10, Gradle 9.7.1 and Compose Multiplatform 1.9.3.
Any JDK 17 or newer should work; `:desktop` targets 17.

What a builder installs, beyond the JDK. Nothing here is needed to *run* a download: the packaged
application bundles its own runtime, and a reader installing it needs none of this.

| Platform | Beyond a JDK 21 | Needed for |
| --- | --- | --- |
| All | JDK 21, with `jpackage` — a full JDK, not a JRE or Android Studio's JetBrains Runtime | Everything; `jpackage` only for packaging |
| Linux | `fakeroot` and `dpkg` (`sudo apt install fakeroot dpkg`) | `:desktop:packageDeb` |
| Windows | The WiX toolset v3, on the path | `:desktop:packageMsi` |
| macOS | Xcode's command-line tools (`xcode-select --install`) | `:desktop:packageDmg` |

Each installer format only builds on its own operating system, so the table is a list of what each
machine needs rather than what any one machine needs.

The per-merge tier, which `.github/workflows/ci.yml` runs on every push:

```bash
./gradlew :worldgen:jvmTest :worldgen:wasmJsNodeTest
./gradlew :cartography:jvmTest :cartography:wasmJsNodeTest
./gradlew :desktop:test
```

The interface's own tests run on the JVM and in a headless browser. CI runs the browser half; run
both before a merge:

```bash
./gradlew :ui:jvmTest :ui:wasmJsTest
```

The audit tier, run nightly by `.github/workflows/nightly.yml` and on demand:

```bash
./gradlew audit
```

It carries the slower checks: the `DebugMapDump` render harness (PNGs under `worldgen/build/maps/`),
`StageProfileTest`, `GenerationSpeedTest`, `DesertCauseTest`, `ColdCapReportTest` and
`ErosionConvergenceTest`, the 2048-scale cases of `GlaciationTest` and `RealmIdRangeTest` (split
into `*AuditTest` siblings), `ExportSmokeTest`'s larger exports (split into `ExportAuditTest`; a
1024 export stays per merge, and `DataExportTest` holds the data exports at 512), and every render
harness and printed report that asserts nothing, `:cartography`'s `W4RenderDump` among them. Each
module's build script lists its audit classes, and a few single reports by class and method. To
regenerate the render PNGs alone: `./gradlew :worldgen:audit --tests '*DebugMapDump*' --rerun`
(the per-merge task excludes the class, so asking it for the class finds nothing).

The per-merge JVM suites run in parallel workers, and a test that reads a standard world borrows
it from `SharedWorlds` (in `worldgen/src/sharedTestSupport`) rather than generating its own; each
borrowed world is fingerprinted and checked whenever it is lent, after every test and every class
that borrowed it, and before it is dropped, so a test that writes to one fails and is named. The
audit tasks run one after another rather than side by side, for the memory their 2048 worlds want.
A timing report — each test task's wall time and the slowest classes — is printed at the end of
any run that tests.

CI also compares a JVM-versus-Wasm world fingerprint (`WorldFingerprintTest`, read from the test
runs' own output) and reports a difference as a warning rather than a failure, since saves carry the
world and the platforms no longer need to agree bit for bit.

### Packaging

```bash
./gradlew :desktop:createDistributable
```

produces a self-contained folder at `desktop/build/compose/binaries/main/app/Cartogenesis` with a
bundled runtime; `./gradlew :desktop:packageMsi` builds the Windows installer, and `packageDeb` and
`packageDmg` exist for the other platforms but only build on their own OS; the Debian packager
also needs `fakeroot` installed. The desktop app, its tests and `packageDeb` were run on Ubuntu 24.04
with the open-source graphics stack (nouveau, NVK, zink), where the GPU check found the card and the
accelerated paths ran; the proprietary NVIDIA driver is untested. Packaging needs
`jpackage`, so point the build at a full JDK with `-PjdkHome=/path/to/jdk` or the `JPACKAGE_HOME`
environment variable if your default runtime lacks it. The `-Xmx12g` from the application block is
baked into the launcher, so a packaged build has the headroom a Gradle run has.

The packaged application needs the `jdk.unsupported` module (which holds `sun.misc.Unsafe`) for
LWJGL's GPU detection: `jpackage` runs `jlink`, which cannot see through LWJGL's reflection and
would otherwise strip it, so `nativeDistributions` asks for it explicitly. Because no test can catch
that in the packaged build, check it directly:

```bash
Cartogenesis.exe --gpu-check
```

It prints the device it found, or why it found none, for the erosion sweeps and the export raster in
turn, and exits without opening a window. Finding no device is an answer rather than a failure, and
it exits cleanly either way, which is what `.github/workflows/release-linux.yml` relies on when it
asks the same question of the packaged Linux build on a runner that has no graphics card.

The Windows files and the release itself are made by hand from a Windows machine; the two Linux
files are built and uploaded to that release by `.github/workflows/release-linux.yml` when the `v*`
tag is pushed. It waits up to thirty minutes for the release to appear, so the order of the two does
not matter. The apt repository the `.deb` is served from is rebuilt by the site deploy — see
"The apt repository" in [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md), and
[docs/INSTALL.md](docs/INSTALL.md) for the signing key's one-time setup.

## Save format

A save is an uncompressed JSON header (format version, settings, overrides, labels, title, which
front end wrote it, and a directory of the payload's sections) followed by the payload: the world's
lists (rivers, lakes, realms, peoples, landmarks) as JSON, then one little-endian binary section per
per-cell array, 146 bytes a cell in all. The payload is cut into one-mebibyte chunks, each gzipped
and checksummed on its own, so a save is written and read a chunk at a time: a 4096 world, 2.45 GB
of arrays, saves and opens without any array its size existing in between. The header is
checksummed too, and each chunk's checksum is bound to the header and to the chunk's place, so an
edited header, or a header put in front of another save's chunks, is found. The browser keeps its
library in IndexedDB the same way, a mebibyte to a record, or in a folder the reader chose, a
mebibyte to a write. `WorldCodec` in
`:cartography` is the whole format, shared by both front ends, with serializers generated from the
config classes so a new setting cannot go missing from a save.

The format is version 14, and only that version opens. A save carries its whole world or it does not
open: an older or newer file, a truncated one, one whose directory or payload disagrees with this
build's layout, or one holding an id, a reference, a number or a flag it could not have been written
with is refused with the reason, never opened as whatever its settings would regenerate. Its
settings are always the ones its world was made with.

Edits sit in a `WorldOverrides` layer and survive regeneration. A new generated attribute needs an
override path and a line in `WorldSections` or it will not survive a save.

### The library, and keeping it in the cloud

The desktop keeps saves as ordinary `.cgw` files in `~/.cartogenesis/worlds`, or in the folder
chosen under Settings ▸ Library folder. Any `.cgw` file in that folder is listed under its own name
and opens as itself, whatever it is called, so a save downloaded from the browser can simply be
dropped in. A file that will not open is listed with the reason. A world brought in from a file is
a new document, so its first Save lands beside any copy already in the library rather than over
it, and two Saves of one world always leave the later one on disk.

To keep your worlds in the cloud, choose a folder that a file-sync client already keeps in step
across your machines as the library folder. Cartogenesis never talks to the service itself; it only
behaves well in a folder another program is syncing. Each save is written to a temporary file beside
it and renamed into place, so the sync client never uploads half of one. A file the client has not
finished bringing down, or an online-only placeholder it cannot fetch, is refused as incomplete
rather than opened as something else. And the copies a client makes when two machines edit one
world (`world (1).cgw`, `world (conflicted copy).cgw` and the like) are listed as worlds of their
own, each of which opens, and saves, without touching the other.

In the browser the library starts in the browser's own storage, where clearing the site's data
removes it. In Chrome and Edge, which offer web pages a folder picker, the Library pane's **Choose a
folder…** moves it into a folder on your device instead: the same `.cgw` files under the same
names as the desktop's, so one folder, synced or not, serves both. Firefox and Safari offer no such
picker; there the library stays in the browser's storage and moves in and out by Download and
Upload. The browser remembers the folder between visits but asks again before a page may use it,
so a new visit shows **Reconnect to <folder>** until you click it, and saves nowhere until then.
**Use this browser's storage** goes back, remembered as a choice of its own, and worlds already in
the browser's storage can be copied into the folder with one click. A new save is written under a
temporary name, `.<name>.<token>.tmp`, which most file managers hide, and moved into place whole
(not the desktop's `~<name>.<token>.tmp`: Chrome refuses a name that begins with a tilde in a
folder on the disk); a save over an existing file goes through the browser's
own swap file, committed only when complete. Writes from one tab are made in order; another tab,
the desktop app or a sync client writing the same folder at the same moment is not ordered against
it, but a save never leaves half a file, and a new save that finds its name taken takes the next
free one. Two gaps remain that the browser gives a page no way to close. The name is checked once
more immediately before the save is put in place, and a file that another program creates under
that name in the moment between the check and the move is replaced by the save, because a browser
cannot create a file only if it does not already exist. And in a browser too old to rename files in
a folder on the disk, the save is copied into its name instead, so an empty `.cgw` with that name
shows in the folder, and to a sync client, while the copy runs; a file another program writes into
that name after the copy has checked it is empty, and before the copy starts, is written over, for
the same reason. Nothing is uploaded anywhere: the
page reads and writes that folder and nothing else.

Chrome on Android offers the folder picker too, over Android's own storage rather than a directory,
and two things are weaker there that a page cannot mend. A file cannot be renamed, so every new save
is copied into its name, the empty `.cgw` showing while the copy runs. And the browser keeps its
swap file in its own cache and, on close, empties the file and copies the new bytes into it, so a
save over an existing world that fails partway can leave that file short. A new save is whole
under its temporary name before it is copied, so when the copy fails that file is kept rather than
removed, and the failure names it: renamed without its leading dot and ending in `.cgw`, it
opens. The Library pane shows
each save, open, delete and copy while it runs, a save with the megabytes written so far, and then
how it ended, with the browser's own name for any failure (`NotAllowedError` and the like), on a
phone as on a computer. Opening the app with `?foldertest` in its address, as
`cartogenesis.com/app/?foldertest`, gives a page instead that takes each of the library's steps in
a folder you pick, on files of its own named `cartogenesis-folder-check-…` that it removes again,
and reports what the browser answered to each, as text to copy into a bug report.

## Menus, settings and themes

A strip along the top carries **File** (random world, open library, save, save as, export, copy
link to this world, settings, quit on the desktop), **View** (theme, panel sections, the map toolbar) and **Help** (check for
updates, report a bug, about). It is drawn in `:ui` so the browser build has it too; the desktop
binds the usual keystrokes (Ctrl+N, Ctrl+O, Ctrl+S, Ctrl+Shift+S, Ctrl+E, Ctrl+L, Ctrl+comma,
Ctrl+Q) and the browser binds none, so it never steals Ctrl+S from the tab.

**Copy link to this world** puts on the clipboard an address that makes the world on screen again
in the browser: `https://cartogenesis.com/app/?seed=718106#v=1&size=1024&plates=18&style=vellum`.
The seed is in the query, so `/app/?seed=718106` typed by hand opens that seed at the size and
settings a fresh window starts with; the rest follows `#`, which a browser never sends to the
server: the link format's version, the size, and every setting of the world and of the drawing that
differs from its default. The desktop copies the published address, the browser its own page's. A
link carries no saved world, no name, no labels and no edits, and neither where the work runs nor
the river density, which belong to the machine and the reader. Opened, a part the application
cannot use (a seed that is not a number, a setting it does not know, a value outside its control's
range) is set aside with one line of status and the rest applies; a size above the browser's
ceiling is brought down to it with the reason; and a link in a format this build does not write is
refused whole rather than misread. A link naming a size larger than the one the window starts at
(512 in a browser, 1024 on the desktop) makes nothing until the reader answers one question: make it
at the link's size, or open it at the starting size with the link's other settings. The question
quotes how long that size took where it has been measured on that kind of machine (`LargeLinks`
holds the figures and their sources) and says so where it has not.

Settings persist through the `Platform` seam as one JSON document
(`%APPDATA%\Cartogenesis\settings.json` on Windows, local storage in the browser); a document from a
different build, or a hand-edited typo, opens anyway rather than refusing to start.

Themes come in three groups. **Standard**: System, Light and Dark, on an off-white and a neutral
charcoal with inks and an accent taken from a map style. **Accessible**: High contrast (black and
white, every text pair past WCAG AAA) and Colorblind (Okabe-Ito orange and sky blue, with a shape
cue wherever a state would otherwise be told by hue). **Styled**: Nautical, Midnight, Mars, Allied
(1940s Army Map Service buff and olive drab), Hallowed, Baroque, Matrix, Hessian, Roman, Hitchcock,
Lemon Blueberry (dark blue-violet with lemon-yellow text and pink alerts) and Blacklight (deep
violet with bright lime text and orange alerts). Every text pair in every theme is measured against
WCAG AA, and AAA for High contrast.

**Check for updates** compares GitHub's `releases/latest` tag with the build's version (generated
from `gradle.properties`). It is off by default and runs only from the menu, so launching the app
never contacts GitHub uninvited. **Report a bug** copies a report with the seed, resolution and
changed settings and opens a pre-filled issue. **About** lists the version, build date, licence and
third-party notices; a Gradle task generates the notices from the build's dependency graph so they
match what is bundled.

## Deploying the web build

`site/` holds cartogenesis.com: the description page, the loading shell, and Cloudflare Pages'
`_headers` and `_redirects`. `./gradlew :web:assembleSite` builds the application and assembles the
site into `web/build/site`; `./gradlew :desktop:siteTest` does that and checks the result.
`.github/workflows/site.yml` runs both on any pushed `v*` tag, or by hand from the Actions tab, and
uploads to the Cloudflare Pages project `cartogenesis` using the repository secrets
`CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`. `site/README.md` covers deploying by hand.

For hosting elsewhere: upload `web/build/dist/wasmJs/productionExecutable` to any static host. It is
about 16 MB of files, 13 MB of that the loader and the two `.wasm` modules, which gzip to about
4.6 MB — the figure the site build measures and the figure a visitor waits for. Paths are relative,
the `.wasm` MIME type does not matter because the build instantiates from a buffer, and HTTPS is
required for WebGPU.

The loading shell depends on two names in this repository, `VIEWPORT_ID` in the web module's
`Main.kt` and `hideLoadingMessage()` in `Browser.kt`, and breaking either fails silently.
`WebDeploymentContractTest` pins both. The reasons, and the loader-stamping the deploy does to avoid
stale caches, are in [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Documentation

- [docs/DESIGN_LEDGER.md](docs/DESIGN_LEDGER.md): one row per piece of work, what each
  changed and the figures it measured, in the order the work was planned.
- [docs/GEOGRAPHY.md](docs/GEOGRAPHY.md): what the generator holds by construction and where it
  still deviates from Earth.
- [docs/REALISM_AUDIT.md](docs/REALISM_AUDIT.md): the programme of work queued for the 3.x line.
- [docs/PERFORMANCE.md](docs/PERFORMANCE.md): what generation, rendering and export cost, with the
  machine and date beside each table.
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md): how the site is assembled and published, and what the
  loading shell depends on.
- [docs/CONVENTIONS.md](docs/CONVENTIONS.md): the naming and comment rules code here follows.
- [docs/TODO.md](docs/TODO.md): issues found but not yet scheduled.
- [ROADMAP.md](ROADMAP.md): planned releases and what each brings.

## Licence

MIT; see [LICENSE](LICENSE). The bundled typefaces are under the SIL Open Font License; every other
third-party component is listed with its licence in the About dialog.
