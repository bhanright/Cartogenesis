# Cartogenesis
<img width="1280" height="640" alt="cartogenesis-github-preview" src="https://github.com/user-attachments/assets/f223d5c3-4c9e-44cd-ab14-8bcc000af028" />

[![CI](https://github.com/bhanright/Cartogenesis/actions/workflows/ci.yml/badge.svg)](https://github.com/bhanright/Cartogenesis/actions/workflows/ci.yml)
[![Nightly audit](https://github.com/bhanright/Cartogenesis/actions/workflows/nightly.yml/badge.svg)](https://github.com/bhanright/Cartogenesis/actions/workflows/nightly.yml)

Cartogenesis generates fantasy world maps from a seed. It models plate tectonics, erosion, climate
and drainage, then adds realms, peoples and landmarks. It is a desktop application for Windows and
Linux, written in Kotlin with a Compose Multiplatform interface.

**Download it** from the [latest release](https://github.com/bhanright/Cartogenesis/releases/latest):
a portable Windows zip, an MSI installer, a Debian package and a portable Linux tarball. Debian and
Ubuntu can install and stay up to date from an apt repository on the site. The download bundles its own Java runtime; nothing needs to be
installed first. [docs/INSTALL.md](docs/INSTALL.md) has the steps for each platform, including the
three apt commands and what to do about the unsigned installer's SmartScreen warning.

An older browser preview stays online at [cartogenesis.com](https://cartogenesis.com/app/). It is
no longer developed: it runs the generator as it stood when the browser version was frozen, so its
worlds differ from the desktop app's.

## What you get

- Fifteen map views: the fantasy map, political borders, peoples, elevation, biomes, seasonal
  temperature and rainfall, tectonic plates, ocean currents and winds.
- Twelve map styles, from a modern atlas to parchment, ink, a nautical chart, Mars and a
  satellite-color Natural style, plus a color-blind-safe palette.
- Interface themes grouped into Standard, Accessible and Styled.
- Editing of generated names and borders, kept with the world.
- Save files containing the generated world and your edits.
- Image exports as PNG, WebP or JPEG, drawn at the world's true shape a cell to a pixel — every world
  is made on one grid of 1024 rows and 2048 columns of square cells, so a picture is 2048 × 1024 —
  and data exports (heightmap, biome map, realm map), one sample per cell with JSON sidecars.

MIT licensed; see [LICENSE](LICENSE).

## Quick start

Run the desktop app:

```bash
./gradlew :desktop:run
```

While a world is generating, the Generate button reads **Stop**. Selecting it cancels within a
round of erosion, keeps the previous map on screen, and shows which stage was interrupted. Partially
generated worlds are discarded.

## How a world is made

Each stage feeds the next, and every stage is deterministic for a given seed. The world has a
declared physical size (`WorldScale`): 12,000 km across, land up to 6,000 m above the waterline, sea
floor down to 10,000 m below it, and one hydraulic round standing for about 336,000 years
(`WorldScale.yearsPerHydraulicRound`, 336,476.4, derived from the stream-power constants rather
than chosen), so twelve rounds are about four million years. Both ends of the vertical range are cell means rather than points — a cell of the
grid is 5.9 km across, and no cell that size holds a summit. Every reach, depth and rate in the
generator is written in those units and converted to the grid the world is generated on, so
nothing depends on the grid being the one it is.

1. **Terrain.** Seeded Perlin noise produces a gradient field, integrated into a height map by
   Frankot–Chellappa least-squares integration (a 2D FFT). The terrain filter emphasizes relief at
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
7. **Currents.** The wind's stress drives Stommel's circulation, solved on a grid sized by the
   planet's own physics rather than the map's: bottom friction and the change of the Coriolis
   effect with latitude crowd each gyre's return flow into a narrow current along the basin's
   western side. The sea temperature is then solved as a steady balance between the currents
   carrying heat, the eddies spreading it and the air resetting it, so warm water runs poleward
   along western margins and cooler water drifts equatorward along the eastern ones.
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
    basins below it. Discharge is the rain that falls, in millimeters, summed downstream. Rivers run
    from their farthest headwater, are drawn at a width proportional to the square root of their
    discharge (Leopold and Maddock), and stop at the shoreline.
11. **Realms.** Borders are assigned by whole drainage catchment, so frontiers fall on watersheds.
    Catchments are cut at their confluences to a bounded area of ground, a closed basin stays whole
    with its lake, and a small one joins a neighbor on its own landmass. Large catchments are split
    along their trunk river, enclaves dissolve into their surrounding neighbor, and no realm holds
    more than 30% of the world's land. Each realm's population, exports and
    imports derive from the land it holds.
12. **Peoples.** A second, independent layer: cultures spread from seeded hearths at a cost set by
    how unlike home the next land is, so a people's territory follows climate rather than politics.
13. **Landmarks.** Lairs, ruins, hazards and resources are placed on terrain that suits them, biased
    toward land no realm claims.

The derivation behind each stage, with the measurements that shaped it, is in
[docs/DESIGN_LEDGER.md](docs/DESIGN_LEDGER.md); what the generator holds by construction, and where
it still deviates from Earth, is in [docs/GEOGRAPHY.md](docs/GEOGRAPHY.md).

## Map styles, views and colors

The twelve styles are **Atlas** (elevation and climate tints), **Vellum** (aged parchment and
sepia ink), **Ink wash** (sumi-e gray on pale paper), **Nautical** (an admiralty chart with
depth-banded water), **Midnight** (moonlit, rivers left luminous), **Schoolroom** (a saturated
classroom wall map, the land tinted by elevation alone), **Verdant** (illustrated fantasy: teal
sea, cream land, deep woods), **Scroll** (painted parchment, jade sea, vermilion marks), **Pen and
ink** (line art, relief hatched by slope), **Mars** (the same world as a dry planet), **Natural** (a palette sampled from a Blue
Marble photograph of Earth) and **Color-blind** (a cividis land ramp over one flat sea, so nothing
is told by hue alone).

A style changes only appearance. The same seed gives the same world in every style, and the
diagnostic views (elevation, biomes, climate and the rest) ignore styles, since their colors carry
meaning. `StyleGalleryTest` renders all twelve and asserts that they differ from one another. Most
of the difference between styles is four settings: how much vegetation color shows through, how far
biome colors are pulled toward the paper, how far the height ramp follows the climate, and how
strongly the relief is shaded.

**Climate colors.** An elevation-only palette can make a desert plain look as green as a wet one.
Cartogenesis adjusts terrain colors using vegetation, aridity and ice cover, and each style controls
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

- **Generalization.** A sheet draws as much river line per square kilometer of land as a published
  map at its own scale does — measured off Natural Earth's 1:50M and 1:10M river layers and carried
  between scales by Töpfer and Pillewizer's radical law — so the faintest rivers are dropped as you
  zoom out and return as you zoom in. A River density slider in the Cartography panel scales that
  ink from a quarter of the published figure, which still keeps the largest river, up to every
  river the sheet has room for. The coast is traced as a simplified polyline over the raster.
- **Graticule.** Lines every ten degrees with figured edges (`40°N`, `170°W`), on screen and on
  exports.
- **Scale bar.** In the legend and on exports, restating itself as you zoom; the cartouche gives
  the scale at the sheet's own size — one figure, since the map is drawn at the world's true shape
  and a pixel covers the same ground either way — quoted at the equator because east–west distance
  on an equirectangular map shrinks with latitude.
- **True shape.** The world is twice as wide as it is tall on the ground, and its grid is twice as
  many cells across as down, so a cell is square: 5.9 km a side on the grid of 1024 rows. Every
  picture — on screen and exported — draws a cell to a pixel, copying its color exactly, and lays
  the ink over it at its own width; data exports keep the grid, one sample per cell, and so have
  the picture's shape.

All of these read the one declared width, `WorldScale.worldWidthKm`, and are drawn as geometry so
they appear identically on screen and in a PNG. The measurements behind them are in
[docs/GEOGRAPHY.md](docs/GEOGRAPHY.md) and [docs/DESIGN_LEDGER.md](docs/DESIGN_LEDGER.md) (F14).

## Exports

An export is the world on screen, drawn a cell to a pixel: nothing is made again. Every world is
made on one grid, 1024 rows, so a picture is 2048 × 1024. The generator itself is not tied to that
grid: every setting is a length on the ground, a depth or a time, converted to cells where each
stage reads it, so `WorldGenConfig.atResolution` changes only the grid, which the tests use to make
small worlds and which a choice of planet size will use later.

**Pictures** are PNG, WebP or JPEG. PNG is lossless. WebP is smaller and loses a little detail in
thin rivers and borders. JPEG is for tools that will not open WebP; it is smaller still and softer.
The measured trade-offs are in [docs/PERFORMANCE.md](docs/PERFORMANCE.md); `ExportSmokeTest` and
`DataExportTest` keep the interface's descriptions true.

**Data** exports write the numbers behind the picture, for Blender, Unity, Unreal and QGIS:

| Data export | Image | Sidecar carries |
|---|---|---|
| Heightmap | 16-bit grayscale PNG, one cell per pixel | the meter scale, the sea-level gray value, the cell size |
| Biomes | 8-bit palette PNG, one biome ordinal per pixel | index → biome name, color and cell count |
| Realms | 8-bit palette PNG, sea 0, unclaimed land 1, realms from 2 | index → realm name, color and cell count |

Each data export is a PNG and a JSON sidecar of the same name. The sidecar carries the seed, the
pixel dimensions (the grid's, one sample per cell, unlike the picture exports), the world's width
(12,000 km), the cell size east-west and north-south, the square kilometers per cell, the
save format version and the build. **Sea level is gray level 32768 on every world**, fixed rather
than derived per world, because its job is to be typed into somebody else's program. There are
32767 levels either side of the waterline, and the sidecar states a meters-per-level figure for
each half, because the vertical range is two numbers rather than one: at the defaults a level above
the waterline is 0.1831 m and one below it 0.3052 m, so white is +6,000 m and black is -10,000 m.
Land below the waterline is written as it is, not clamped — a basin the sea cannot reach drains out
into a salt flat below sea level, which on seed 42 at 512 is 497 cells.

Why the PNGs come out of this project's own encoder is in [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## The grid, and acceleration

Every world is made on one grid: 1024 rows and 2048 columns of square cells, 5.9 km a side, which
generates in about fifty seconds on the processor. The interface offers no other; it used to offer
512 to 4096 rows, and the browser version went to 1024 (docs/DESIGN_LEDGER.md, G1, for why that
stopped). A save made at another grid while the grid was a choice still opens, and is drawn and
exported at its own grid; `Platform.generationCeiling` and `WorldCeilings` still refuse a save the
machine's memory cannot hold, with the reason.

**Graphics acceleration** is an opt-in toggle in the header and in Settings (as *Graphics
acceleration at launch*). It runs the erosion sweeps, the ocean-current solve, the ice sheet's
profile and flow and the export raster on the graphics device, through OpenGL compute, behind the
accelerator seams; the panel says what it found through the `Platform` seam. Drawing the map
runs on the graphics device unconditionally, outside this toggle, because rasterizing pixels makes
no promise about reproducing a world from its seed. The accelerated erosion agrees with the
processor to about seven parts in a million but is not bit-identical, so a world generated with
acceleration stores its terrain in the save (`TerrainSnapshot`) rather than relying on
regeneration.

Measured timings, and the reasons behind the erosion cost, are in
[docs/PERFORMANCE.md](docs/PERFORMANCE.md), with the machine and date beside each table.

## Modules

- `:worldgen`: world generation.
- `:cartography`: map rendering and vector-overlay geometry, and the save format. Per-pixel work is
  plain `IntArray` math and overlays are described as geometry.
- `:ui`: the Compose Multiplatform interface, including the renderer.
- `:desktop`: desktop integration, including file dialogs and OpenGL, and the site's assembly.

What belongs to the host arrives through the `Platform` seam: where saves live, what export means,
whether a graphics device exists.

`:worldgen`, `:cartography` and `:ui` are Kotlin Multiplatform modules with one target, the JVM.
They were also built for the browser through Kotlin/Wasm until G1 removed the browser build; the
correctness suite still lives in `commonTest`, and what reads files or renders through `java.awt`,
such as `DebugMapDump`, in `jvmTest`.

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
./gradlew :worldgen:jvmTest :cartography:jvmTest :ui:jvmTest
./gradlew :desktop:test
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

A clause that holds one world to the same answer on two grids prints its verdict and figures under
`CROSS-GRID` and does not fail (`CrossGridReport` in the shared test support), since the
application makes one grid; every single-grid guard still fails.

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
and checksummed on its own, so a save is written and read a chunk at a time: a 4096 world, 4.9 GB
of arrays, saves and opens without any array its size existing in between. The header is
checksummed too, and each chunk's checksum is bound to the header and to the chunk's place, so an
edited header, or a header put in front of another save's chunks, is found. `WorldCodec` in
`:cartography` is the whole format, with serializers generated from the config classes so a new
setting cannot go missing from a save.

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
and opens as itself, whatever it is called, so a save downloaded from the browser preview can
simply be dropped in. A file that will not open is listed with the reason. A world brought in from a file is
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

The older browser preview keeps its library in the browser's own storage or, in Chrome and Edge, in
a folder on the device, as the same `.cgw` files; how it behaves there is as the browser version
left it, and docs/DESIGN_LEDGER.md has the history. Its saves are format 14 and open in the desktop
app.

## Menus, settings and themes

A strip along the top carries **File** (random world, open library, save, save as, export, copy
link to this world, settings, quit on the desktop), **View** (theme, panel sections, the map toolbar) and **Help** (check for
updates, report a bug, about), with the usual keystrokes (Ctrl+N, Ctrl+O, Ctrl+S, Ctrl+Shift+S,
Ctrl+E, Ctrl+L, Ctrl+comma, Ctrl+Q).

**Copy link to this world** puts on the clipboard an address for the browser preview:
`https://cartogenesis.com/app/?seed=718106#v=2&size=1024&plates=18&style=vellum`. The seed is in the
query and everything else follows `#`, which a browser never sends to the server: the link format's
version, the size, and every setting of the world and of the drawing that differs from its default.
A link carries no saved world, no name, no labels and no edits. The preview that opens it runs an
older generator than the desktop app, so the world it makes from the link is not the one it was
copied from (docs/TODO.md).

Settings persist through the `Platform` seam as one JSON document
(`%APPDATA%\Cartogenesis\settings.json` on Windows); a document from a different build, or a
hand-edited typo, opens anyway rather than refusing to start.

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
changed settings and opens a pre-filled issue. **About** lists the version, build date, license and
third-party notices; a Gradle task generates the notices from the build's dependency graph so they
match what is bundled.

## Deploying the site

`site/` holds cartogenesis.com: the description page, the browser preview's loading shell, and
Cloudflare Pages' `_headers` and `_redirects`. The browser preview itself is no longer built: it is a
stored copy, `web-frozen.zip` on the GitHub release `web-frozen`, made from the last commit that
built it. `./gradlew :desktop:assembleSite` lays `site/` over that copy, renders the figures from the
engine and assembles the site into `desktop/build/site`; `./gradlew :desktop:siteTest` does that and
checks the result. `.github/workflows/site.yml` downloads the stored copy, runs both on any pushed
`v*` tag or by hand from the Actions tab, and uploads to the Cloudflare Pages project
`cartogenesis` using the repository secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
`site/README.md` covers deploying by hand, and [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) the loading
shell and the loader stamping.

## Documentation

- [docs/DESIGN_LEDGER.md](docs/DESIGN_LEDGER.md): one row per piece of work, what each
  changed and the figures it measured, in the order the work was planned.
- [docs/GEOGRAPHY.md](docs/GEOGRAPHY.md): what the generator holds by construction and where it
  still deviates from Earth.
- [docs/REALISM_AUDIT.md](docs/REALISM_AUDIT.md): the programme of work queued for the 3.x line.
- [docs/PERFORMANCE.md](docs/PERFORMANCE.md): what generation, rendering and export cost, with the
  machine and date beside each table.
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md): how the site is assembled and published, and what the
  browser preview's loading shell depends on.
- [docs/CONVENTIONS.md](docs/CONVENTIONS.md): the naming and comment rules code here follows.
- [docs/TODO.md](docs/TODO.md): issues found but not yet scheduled.
- [ROADMAP.md](ROADMAP.md): planned releases and what each brings.

## License

MIT; see [LICENSE](LICENSE). The bundled typefaces are under the SIL Open Font License; every other
third-party component is listed with its license in the About dialog.
