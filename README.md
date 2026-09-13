# Cartogenesis

[![CI](https://github.com/bhanright/Cartogenesis/actions/workflows/ci.yml/badge.svg)](https://github.com/bhanright/Cartogenesis/actions/workflows/ci.yml)

Cartogenesis generates fantasy world maps by simulating the physics that make real ones: plate
tectonics, erosion, weather, rivers, realms and peoples, all grown from a single seed rather than
painted by hand. It is a Kotlin Multiplatform generation engine behind a Compose Multiplatform
interface, shipped as a desktop app and a browser build from the same code.

**Try it live** at [cartogenesis.com](https://cartogenesis.com): it runs entirely in the browser,
and nothing you generate is uploaded anywhere.

**Download it** from the [latest release](https://github.com/bhanright/Cartogenesis/releases/latest):
a portable Windows zip, an MSI installer, or the web build as a zip. The installer is not
code-signed, so Windows SmartScreen will warn before it runs.

A generated world carries:

- fifteen map views (fantasy, political, elevation, biomes, climate, currents and more)
- eleven drawing styles
- fifteen interface chromes on three shelves
- full editing of names, borders and the generated economy
- saves that hold the finished world, not a recipe for regenerating one
- exports up to 4096x4096

MIT licensed; see [LICENSE](LICENSE).

## The pipeline

Each stage feeds the next, and all of them are deterministic for a given seed.

The world's physical size is declared once, in `WorldScale`, and every stage reads its metres,
kilometres and years from there: the map is 12,000 km wide, its highest land stands 6,000 m above
the waterline and its deepest floor 10,000 m below it, and one hydraulic round stands for about
340,000 years. Both ends of the vertical range are cell means rather than points — a cell of the
default grid is 23 km across, and no cell that size holds a summit — and every reach, depth and
rate below is written in those units and converted to whatever grid the world is generated at.

1. **Terrain.** Seeded Perlin noise generates a random gradient field, integrated into a height map
   by Frankot-Chellappa least-squares integration (a 2D FFT).
2. **Plates.** The world splits into drifting Voronoi plates; boundaries are classified by relative
   motion and by which crusts meet, raising coastal ranges, collision plateaus, island arcs, rifts
   or ridges accordingly. Three past epochs of the same history are stamped first and aged
   (lowered, widened, rounded) before the present one, so a range can stand old and worn far from
   any boundary, the way the Appalachians do.
3. **Erosion.** Thermal erosion slides material off slopes steeper than a critical gradient of
   12 m per km while stream-power incision (`E = K A^0.5 S`, with K at Whipple and Tucker's 10^-6
   for bedrock) cuts channels in proportion to the water draining through them,
   interleaved round by round. Every cell is clamped to never end a round below the neighbour it
   drains to (the receiver clamp), which keeps a channel grading smoothly instead of filling with a
   stitch of ponds.
4. **Deposition.** What erosion cuts, the same water carries: wherever a cell's sediment load
   exceeds what its slope can hold, the surplus settles as graded floodplains along the lower
   trunks, fan-shaped deltas where rivers reach the sea, and fans at range fronts and lake inflows,
   with the mass moved off the land always equal to what gets laid down or carried out to sea.
5. **Sea level.** Sea level is a chosen elevation percentile, but the hydraulic rounds before it
   grade to a base level seeded below today's, Earth's own last lowstand, so the rise afterwards
   drowns lower valleys into estuaries and sounds. A pocket of sea the cut leaves enclosed by land
   is relabelled ground rather than left a landlocked sea, and a second pass afterwards lets a
   filled basin's overflow breach its own sill, so a basin ends up an inlet, a lake or dry ground by
   its own drainage rather than by the cut alone.
6. **Shelves.** The sea floor near a coast is remapped onto a shallow continental shelf that falls
   away to the abyss beyond it, so a coastline reads as bathymetry rather than a cliff underwater.
7. **Currents.** Wind stress on the sea has a curl, and the stream function that satisfies it inside
   a closed basin is a gyre, so the currents are solved for rather than drawn, giving warm poleward
   flow on western ocean margins and cold equatorward flow on eastern ones.
8. **Climate.** Temperature is solved rather than drawn: a one-dimensional energy balance over a
   couple of hundred latitude bands, marched through the year, weighing the sunlight a latitude
   receives against what it radiates to space and what its neighbours send it. Each band carries an
   air column over land and another over sea with the world's own coastline as their areas, and a
   fifty-metre slab of water under the marine one, so a continent gets a winter, the coast beside it
   gets a cool spell, and the sea itself barely moves — three heat capacities and a coastline, with
   no setting for any of it. The albedo follows the ice the
   model itself grows, so a cap is self-reinforcing and a colder sun is answered with a cooling that
   deepens toward the poles. Rainfall comes from moist air marched along wind belts that swing
   between two seasons, producing rain shadows and monsoons and classifying biomes Köppen-style from
   the four seasonal numbers. Where a season's water falls below the freezing point of sea
   water it is under ice, and the march takes nothing from ice, so the polar ocean is a desert. A
   snow mass balance, not a bare freezing line, decides where land ice can hold, and the
   sea-surface temperature the currents carry scales how much moisture a coast picks up.
9. **Glaciation.** Where the snow balance runs positive, valley glaciers widen and flatten existing
   river valleys into U-shaped troughs with cirques at their heads, and ice sheets scour flat ground
   into the irregular closed basins of shield lake country.
10. **Rivers and lakes.** Depressions are filled so no water dead-ends inland, flow is routed
    downhill and traced to the coast, and every basin's outlet incises its own sill down over time.
    A basin the fill raised becomes a lake only as far as its water balance allows: where
    evaporation outpaces runoff it settles below its rim as endorheic, or as a dry playa if it
    cannot hold water at all; otherwise it overflows at the brim. A channel is drawn as wide as the
    water it carries: Leopold and Maddock's downstream hydraulic geometry has width going as the
    square root of discharge, so the map's smallest stream is a 0.8-pixel thread and its biggest
    river a 5-pixel channel, with the same pen at every resolution and export size.
11. **Realms.** Political borders are handed out by whole drainage catchment, never split, so a
    frontier falls on a watershed because that is the only place a catchment boundary can run;
    large catchments are cut along their trunk river, enclaves dissolve to whichever neighbour
    surrounds them, and no realm holds more than 30% of the world. The atlas then derives each
    realm's population, exports and imports from the land it actually holds, and names come from
    per-culture syllable sets.
12. **Peoples.** A second, independent layer: cultures spread outward from seeded hearths at a cost
    set by how unlike home the next piece of land is, so a people's territory follows climate rather
    than politics and rarely matches the realm map beneath it.
13. **Landmarks.** Monster lairs, ruins, hazards and resources are placed on terrain that suits
    them, biased toward ground no realm claims.

The derivation behind each stage, and the measurements that shaped it, are in
[REALISM_PLAN.md](REALISM_PLAN.md); what the generator holds by construction, and where it still
deviates from Earth, is in [GEOGRAPHY.md](GEOGRAPHY.md).

## Styles

Eleven ways to draw the finished map: **Atlas** (modern hypsometric tints), **Vellum** (aged
parchment and sepia ink), **Ink wash** (sumi-e grey on pale paper), **Nautical** (an admiralty chart
with depth-banded water), **Midnight** (moonlit, rivers left luminous), **Schoolroom** (a saturated
classroom pull-down map), **Verdant** (illustrated fantasy: teal sea, cream land, deep woods),
**Scroll** (painted parchment, jade sea, vermilion marks), **Pen and ink** (line art, no fill, relief
hatched by slope, borders in red), **Mars** (the same world as a dry planet: oceans become basalt
plains, the old shoreline a scarp) and **Colour-blind** (a cividis land ramp over one flat sea,
ordered so nothing is told by hue alone).

A style changes only appearance; the same seed gives the same world in all eleven, and the
diagnostic views (elevation, biomes, climate and the rest) ignore styles entirely, since their
colours carry meaning a prettier ramp would obscure. `StyleGalleryTest` renders all eleven and
asserts that they differ from one another.

## Modules

- **`:worldgen`**: the whole generation pipeline as Kotlin Multiplatform, with no platform
  dependencies. Builds for the JVM and WebAssembly; see **Multiplatform status**.
- **`:cartography`**: turns a world into a picture with no graphics toolkit of its own. Per-pixel
  work is plain `IntArray` maths and vector overlays are described as geometry rather than drawn, so
  every platform makes identical decisions. Builds for JVM and Wasm.
- **`:ui`**: the interface, once, as Compose Multiplatform, oblivious to where it runs; what differs
  arrives through a `Platform` seam.
- **`:desktop`**: a window, a native save dialog, files on disk, OpenGL. Around forty lines.
- **`:web`**: a page, local storage, downloads, WebGPU. About the same.

## Running it

```bash
./gradlew :desktop:run
```

For the browser build:

```bash
./gradlew :web:wasmJsBrowserProductionRun
```

This serves the optimised bundle at http://localhost:8080. Use the *production* task, not
`wasmJsBrowserDevelopmentRun`: the development bundle is unoptimised Wasm and several times slower,
which on a single-threaded target is the difference between a pause and a wait.
`./gradlew :web:wasmJsBrowserDistribution` writes a static site to
`web/build/dist/wasmJs/productionExecutable` instead of serving it.

It must be served over HTTP: Wasm will not load from `file://`, and WebGPU needs a secure context,
which `localhost` counts as.

While a world is being built the Generate button reads **Stop**, and pressing it hands the settings
straight back: the generation is abandoned within a round of erosion rather than at the end of it,
the map that was on screen stays there — an empty canvas stays empty — and the status line says
which stage it had reached. Nothing half-built is kept, so the next generation reuses whatever
stages of the last *finished* world its settings still allow.

### Putting it somewhere

Upload the contents of `web/build/dist/wasmJs/productionExecutable` to any static host; there is no
server side to it. A few things worth knowing:

- About 13MB of files (12MB of it two `.wasm` blobs), roughly **4.3MB** once gzipped, which most
  static hosts do by default.
- The `.wasm` MIME type does not matter: the build instantiates from a buffer rather than streaming.
- Paths are relative, so hosting from a subfolder works unchanged.
- Serve HTTPS. Without it, WebGPU is unavailable and the build silently falls back to the processor.

`cartogenesis.js.map`, a 1.7MB source map fetched only when devtools are open, can be deleted from
the upload.

The two front ends are the same application: `:ui` holds the renderer as well as the interface, so a
map drawn in a tab is drawn by the same code as on the desktop. What differs is the short list in
`Platform`: where saves live, what export means, whether a graphics device exists.

The browser starts at a working resolution of 512 against the desktop's 1024, a platform decision
rather than a preference: a tab has one thread, and `Dispatchers.Default` there is that same thread,
so generating stops the page answering until it finishes rather than merely taking longer. Measured,
512 takes about 1.6 seconds of CPU work in Wasm against 1.4 on the JVM's fifteen threads; 1024 would
be over a minute and read as a hang. With WebGPU enabled the erosion part of that drops to about 23
milliseconds.

On a 2026 phone with WebGPU on — a Qualcomm handset, measured — a 1024 world generates in about
twenty seconds and a 2048 world in about ninety, so both are worth offering there; the compact
arrangement says so under the resolution chips. Because the page's one thread is the generator's,
the interface is handed a frame before the first stage starts and again at every stage boundary, so
the ten stage names are things that appear rather than things written to a variable nobody sees
until the end. Without that the page simply stops, which reads as a crash rather than as work.

### Packaging

```bash
./gradlew :desktop:createDistributable
```

Produces a self-contained folder at `desktop/build/compose/binaries/main/app/Cartogenesis` with its
own bundled JRE; run `Cartogenesis.exe`, no install needed. `./gradlew :desktop:packageMsi` builds a
Windows installer instead; `packageDeb` and `packageDmg` exist for the other platforms but only
build on their own OS.

`jpackage` runs `jlink`, which cannot see through LWJGL's reflection and would otherwise strip
`jdk.unsupported` (the module holding `sun.misc.Unsafe`), silently breaking GPU detection in the
packaged build only; `nativeDistributions` asks for that module explicitly. Because no test can
catch that class of problem, the packaged app checks itself:

```bash
Cartogenesis.exe --gpu-check
```

which prints the device it found, or why it found none, for the erosion sweeps and the export
raster in turn, and exits without opening a window.

Packaging needs `jpackage`, which the JetBrains Runtime bundled with Android Studio does not
include; point the build at a full JDK with `-PjdkHome=/path/to/jdk` or the `JPACKAGE_HOME`
environment variable. The `-Xmx12g` from the application block is baked into the launcher, so a
packaged build has the same headroom as running through Gradle.

Desktop export times, measured on this machine:

| Export | Time | Peak heap |
|---|---|---|
| 2048 x 2048 | 26-35 s | 1.0 GB |
| 4096 x 4096 | 154-182 s | 3.2-4.0 GB |
| 8192 x 8192 | (does not complete) | exhausts a 10 GB heap after 19 min |

4096 is the practical ceiling; see **Resolution and exports** for why 8192 is offered disabled
rather than removed.

A finished world leaves in one of two kinds of file: a picture of the map, or the world's own
numbers.

**Pictures** are PNG, WebP or JPEG. PNG is lossless. WebP is about a quarter the size, but Skia
exposes no lossless WebP encoder and the loss lands where a map can least afford it: the average
pixel drifts about 4 of 255, invisible, while the worst 0.1%, the river lines and borders, drift by
about 75 — more since rivers were sized by their discharge, which draws every headwater as a
sub-pixel thread. JPEG, at quality 90 through the JDK's own encoder on the desktop and Skia's in
the browser, is there for the programs that still will not open a WebP, and the chip's small print
says so: on seed 42 at 512 the same picture is 78 KB as a JPEG at 90 against 108 KB as a WebP at
Skia's maximum, and the WebP is the more faithful of the two at every percentile (mean drift 5.53
against 6.99, 99th 53 against 55); ask the JPEG encoder for 100 and it wants 214 KB for the WebP's
own fidelity. A smaller, softer file or a larger, sharper one, then, and not the ranking the format
names suggest. `ExportSmokeTest` and `DataExportTest` measure every one of those figures so the
UI's description stays true.

**Data** exports write what the picture is a picture of, for Blender, Unity, Unreal and QGIS.

| Data export | Image | Sidecar carries |
|---|---|---|
| Heightmap | 16-bit greyscale PNG, one cell per pixel | the metre scale, the sea-level grey value, the cell size |
| Biomes | 8-bit palette PNG, one biome ordinal per pixel | index → biome name, colour and cell count |
| Realms | 8-bit palette PNG, sea 0, unclaimed land 1, realms from 2 | index → realm name, colour and cell count |

What each costs, measured 2026-09-12 by `ExportAuditTest` on one world per size, seed 42:

| Export | 2048 | 4096 |
|---|---|---|
| Generation (once, whatever comes out of it) | 39.8 s | 173.8 s |
| Raster (once, for the three pictures) | 0.4 s | 1.0 s |
| PNG | 4.6 MB, 3.7 s | 16.3 MB, 12.1 s |
| WebP | 1.3 MB, 0.4 s | 4.4 MB, 1.9 s |
| JPEG | 0.9 MB, 0.2 s | 3.0 MB, 0.5 s |
| Heightmap | 5.0 MB + 606 B, 0.9 s | 18.1 MB + 605 B, 3.0 s |
| Biomes | 0.1 MB + 1.7 KB, 0.1 s | 0.2 MB + 1.7 KB, 0.2 s |
| Realms | 0.1 MB + 1.7 KB, 0.1 s | 0.1 MB + 2.0 KB, 0.3 s |
| Peak heap | 1.2 GB | 4.1 GB |

Generation is the whole of the wait for a data export too: at 4096 a heightmap is three seconds of
encoding behind three minutes of world. The 16-bit PNG is the largest file the application writes
after the lossless picture — 18.1 MB from 33.5 MB of raw samples, so the filtering earns about half
— and the two index maps are almost free, because a biome map is large flat regions and that is
what deflate is for.

Each data export is a pair of files: the PNG and a JSON sidecar of the same name. The sidecar
always carries the seed, the pixel dimensions, the world's width in kilometres (12,000), the cell
size in kilometres at that export size, the square kilometres per cell, the save format version and
the build that wrote it; the heightmap's adds the metre scale. **Sea level is grey level 32768, on
every world** — fixed rather than derived per world, because its job is to be typed into somebody
else's program. There are 32767 levels either side of the waterline and a metres-per-grey-level for
each half, because the world's vertical range is two figures rather than one: at the defaults a
grey level above the waterline is 0.1831 m and one below it is 0.3052 m, so white is +6,000 m and
black is -10,000 m. The sidecar states both. Land below the
waterline is written as it is rather than clamped — a basin the sea cannot reach is drained out
into a salt flat below sea level, which is the Qattara, and on seed 42 at 512 that is 497 cells.

Both PNGs come out of a small encoder in `:cartography` rather than either host's imaging library,
because neither will write what these need: Skia is eight bits a channel everywhere, a browser
canvas is eight-bit RGBA by construction, and neither writes an indexed image at all. The image
data is a zlib stream, which common Kotlin cannot build, so the deflate comes out of the gzip each
host already has behind `Compressor` — the same stream in a different envelope — with a
stored-deflate fallback for a host that has neither. The fallback is about a third larger and every
reader still opens it; `DataExportTest` checks that both paths produce identical samples.

The desktop writes the two files side by side from one save dialog. The browser sends a single zip
holding both, deliberately rather than for want of trying: two downloads from one click raises
Chrome's unexplained "download multiple files" prompt and has historically lost the second file in
Safari, while a heightmap that arrives without its metre scale is a grey rectangle. The archive
stores rather than deflates — the PNG inside is already compressed and the page has one thread.

Drawing the map runs on the graphics card unconditionally, not behind the acceleration toggle below,
since rasterising pixels makes no promise about reproducing the world from its seed the way erosion
does. `MapRasterizer`'s per-pixel work (a ramp lookup, a biome wash, a relief shade, a coast and
border test) runs as one GPU compute dispatch per export tile (`GpuRaster`, behind the
`RasterAccelerator` seam in `:cartography`), rasterising 4096 in 0.37s against the processor's
0.71s, and 8192, in sixteen tiles, in 1.4s; `GpuRasterTest` holds the two within one channel step of
255 at the 99.9th percentile across all fifteen views and eleven styles. None of this is the
bottleneck it looks like: a 4096 export spends over three minutes generating the world and under a
second drawing it.

Erosion is what takes the three minutes: it is the great majority of a generation (82% of a 2048
export, by `StageProfileTest`), because material moves one cell per sweep, so the cost of covering a
given distance rises eightfold rather than fourfold each time resolution doubles.

Erosion is a pure stencil over independent cells, so it is also the one stage worth running on a
graphics device, and there is an opt-in toggle for it — **Graphics acceleration**, in the header
and again in Settings as *Graphics acceleration at launch*. Not "graphics card": on a phone the
device is a block of cores on the processor's own die, and the switch was reported from one. What
it covers differs by host, and the panel says which through the `Platform` seam — the desktop runs
the erosion sweeps *and* the export raster there, the browser only the sweeps, because the WGSL
port of the raster has not been written. On an RTX 3070 Ti the sweeps that take 1.3
seconds on fifteen CPU threads take 22 milliseconds — around 55x — through OpenGL compute shaders
on the desktop, and about 70x through WGSL in the browser. Realm expansion is a Dijkstra over a
priority queue and would not suit a GPU regardless.

Both paths agree with the CPU to about seven parts in a million (the browser path can be checked on
any machine by loading the web build with `?selftest` in the URL). The catch is arithmetic: graphics
hardware fuses multiplies and adds in whatever order it likes, so its terrain is not bit-identical to
the CPU's, and "has not differed yet" is not a guarantee that it never will. A world generated this
way therefore stores its terrain in the save rather than relying on regeneration (`TerrainSnapshot`);
a world generated on the CPU stores nothing extra, because for it the seed really is enough.

## Building

Built and verified against JDK 25, Kotlin 2.4.10, Gradle 9.7.1 and Compose Multiplatform 1.9.3. Any
JDK 17 or newer will do; `:desktop` targets 17.

```bash
./gradlew :worldgen:jvmTest
./gradlew :worldgen:wasmJsNodeTest
./gradlew :cartography:jvmTest :cartography:wasmJsNodeTest
./gradlew :desktop:test
```

## Save format

A save is a container: an uncompressed JSON header (format version, settings, overrides, labels,
title, which front end wrote it, and a directory of what follows), then one gzipped binary section
per per-cell array. The arrays are the file: a 1024 world is around 94MB of floats and ids before
compression, so little-endian `float32` and `int32` sections carry them while the small lists
(rivers, lakes, realms, peoples, landmarks) stay in the JSON header. `WorldCodec` in `:cartography`
is the whole format, shared by every front end, with serializers generated from the config classes
themselves so a new setting cannot silently go missing from a save.

The format is version 4, and only version 4 opens. Anything else, older or newer, is refused by name
with an explanation rather than parsed and quietly filled in with today's defaults wherever a field
has moved: nothing was distributed before 2.0, so a refusal costs nobody a file, and a clean slate
was chosen over a compatibility shim once field names started reading as words instead of
abbreviations. Within version 4, a stage can still add a new section without another version bump: a
save written before that section existed simply lacks it, comes back `null` for that stage, and the
same reuse chain that skips an unchanged stage on a live edit regenerates the missing one and
everything downstream of it.

Generated values sit under a `WorldOverrides` layer, and anything a user edits stays edited across a
regeneration; a new generated attribute needs an override path and a line in `WorldStore` or it will
not survive a save.

## Resolution and exports

Export re-runs the whole pipeline at the target size rather than upscaling the preview, so a bigger
map is genuinely more detailed; `WorldGenConfig.atResolution` rescales every setting that is
measured in cells to make that true, and a new cell-based setting needs adding there or exports will
drift from what the preview showed.

The UI offers 2048, 4096 and 8192, but the 8192 chip is disabled: it exhausts a 10GB heap inside the
generator after about nineteen minutes, before a single pixel is drawn, because the fields for a
world that size need roughly 9GB before the FFT and erosion's own transient buffers are added on
top. Rendering is not the problem (the GPU rasters 8192 in 1.4 seconds with no world in memory at
all), so raising the ceiling means generating in tiles or on disk, not building a bigger renderer.
The limit lives in `Platform.exportCeiling` (4096 on desktop, 2048 in a phone-shaped browser
window), so a build that fixes the memory can raise it without the interface changing. The same
chips cap the data exports as cap the pictures: the ceiling is a question of how big a world this
build can finish, and knows nothing about what kind of file comes out of it.

## Menus, settings, updates and notices

A thin strip along the top carries **File** (new world, open library, save, save as, export,
settings, quit on desktop), **View** (chrome, panel sections, the map toolbar) and **Help** (check
for updates, about), drawn in `:ui` rather than a native menu bar so the browser build gets one too.
The desktop binds the obvious keystrokes (Ctrl+N, Ctrl+O, Ctrl+S, Ctrl+Shift+S, Ctrl+E, Ctrl+comma,
Ctrl+Q); the browser binds none of them, so it never steals Ctrl+S from the host tab.

Settings persist through the `Platform` seam as one JSON document (`%APPDATA%\Cartogenesis\settings.json`
on Windows, browser local storage on the web), and a file from a different build, or a hand-edited
typo, opens anyway rather than refusing to start.

There are fifteen chromes on three shelves. **Standard**: System, Light, Dark. **Accessible**: *High
contrast* (pure black and white, every text pair past WCAG AAA) and *Colorblind* (Okabe-Ito orange
and sky blue, with a shape cue wherever a state would otherwise be told by hue alone). **Styled**,
ten rooms: *Nautical*, *Midnight* and *Mars* (from the map styles of those names), *Allied* (1940s
Army Map Service buff and olive drab), *Hallowed* (an illuminated manuscript in lapis and vellum
with gold-leaf rules), *Baroque* (gilt and walnut, italic headings), *Matrix* (a phosphor terminal in
IBM Plex Mono), *Hessian* (burlap and linen with a woven crosshatch), *Roman* (Pompeian red and
marble with a Greek key) and *Hitchcock* (Saul Bass's charcoal and vermilion, with Vertigo's spiral
in the cartouche). Every text pair in all fifteen is measured, not just claimed: AAA for High
contrast, AA for the rest.

**Check for updates** compares GitHub's `releases/latest` tag against this build's own version
(generated from `gradle.properties`); it is off by default and only ever runs from the menu, so
launching the app never talks to GitHub uninvited. **About** shows the version, build date, licence
and third-party notices, the notices generated by a Gradle task from the build's own dependency
graph so they cannot drift from what is actually bundled.

## Licence

MIT. See [LICENSE](LICENSE). The bundled type faces are under the SIL Open Font License; every other
third-party component is listed, with its own licence, in the About dialog.

## Deploying the web build

The browser build's own site is <https://cartogenesis.com>, and this repository is where it lives.
`site/` holds the whole of it — the description page, the poster, the loading shell, and Cloudflare
Pages' `_headers` and `_redirects`. `./gradlew :web:assembleSite` builds the application and
assembles the two into `web/build/site`, dropping the source map and the emitted `index.html` and
stamping the loader's URL with the commit; `./gradlew :desktop:siteTest` does that and then checks
the tree it produced. `.github/workflows/site.yml` runs both on any pushed `v*` tag, or by hand from
the Actions tab, and uploads the result to the Cloudflare Pages project `cartogenesis` using two
repository secrets, `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`. The custom domain is not
configured from here: it is attached once by hand in the Cloudflare dashboard, under Workers &
Pages → `cartogenesis` → Custom domains, and the workflow asks the Pages API which branch that
project treats as production so the upload lands on the deployment the domain actually serves.
`site/README.md` covers the rest, including how to deploy by hand if Actions is down.

**The shell depends on two names in this repo, and breaking either one fails silently** — the
application keeps working and the page around it never finds out:

- `VIEWPORT_ID` in `web/.../Main.kt` must stay `composeTarget`. The shell creates the div; Compose
  mounts into it.
- `hideLoadingMessage()` in `web/.../Browser.kt` must keep removing `#loading`, and must keep being
  called on startup. The shell keeps an empty div with that id purely so this can delete it, and
  treats the deletion as its "app is ready" signal.

The second one exists because **Compose does not put its canvas in the page.** It attaches a shadow
root to the viewport div, so from outside `document.querySelector("canvas")` is null, the div
reports no children, and a MutationObserver on it never fires — all while a live canvas is
generating a world. There is no other exact readiness signal to watch.

`WebDeploymentContractTest` pins both, and was shown to fail on each in turn. It reads the web
module's source text, since the contract is an id inside a `@JsFun` body that no type system sees;
`desktop/build.gradle.kts` declares those sources as test inputs, because without that Gradle keeps
the task up to date and the build cache restores a stale pass.

One more thing the site has to work around, which is this repo's fault rather than the host's: **the
two `.wasm` files carry content hashes but `cartogenesis.js` does not.** A new build therefore lands
under new wasm names while the loader keeps its old URL, so a returning visitor with a cached loader
asks for a wasm hash the deploy has just deleted — a 404 and a dead app, not a stale one. The site
works around it by loading `cartogenesis.js?v=<stamp>` and stamping it on every deploy;
`:web:assembleSite` does the stamping and fails the build rather than shipping an unstamped shell.

The host must serve `.wasm` as `application/wasm` or the browser's streaming compiler refuses it.
Compression is worth turning on: 12.4 MB raw is 4.4 MB gzipped, and Skia is two thirds of it.

## Testing: two tiers

The suite splits into a fast per-merge tier and a slower on-demand/nightly audit tier.

```bash
./gradlew :worldgen:jvmTest :cartography:jvmTest :desktop:test
```

is what CI and a developer run before every merge: correctness guards only, chosen to be fast.
`./gradlew audit` (unqualified, so it runs the `audit` task in every subproject that declares one)
carries what moved out: the `DebugMapDump` render harness (PNGs under `worldgen/build/maps/`, for
looking at the map rather than asserting on it), `StageProfileTest`, `GenerationSpeedTest`,
`DesertCauseTest`, `ColdCapReportTest` and `ErosionConvergenceTest`, the 2048-scale cases of
`GlaciationTest` and `RealmIdRangeTest` (split into `*AuditTest` siblings), and `ExportSmokeTest`'s
2048/4096 exports (split into `ExportAuditTest`, which covers all three picture formats and all
three data exports from one world per size; a 1024 export stays as a per-merge smoke check, and
`DataExportTest` holds the data exports at 512). `.github/workflows/nightly.yml`
runs it once a day, so a regression in the parts the per-merge tier no longer covers is still caught
within a day. Loaded, the split took worldgen's per-merge run from about 23 minutes to 6 and
desktop's from about 5 to 2.

To regenerate the render PNGs without the rest of the audit tier:
`./gradlew :worldgen:jvmTest --tests '*DebugMapDump*' --rerun`.

## Continuous integration

`.github/workflows/ci.yml` runs the per-merge tier on every push and pull request: the engine's
tests on the JVM and WebAssembly, cartography on both, and the desktop app's compile and test. A
JVM-versus-Wasm fingerprint comparison flags platform drift as an informational warning rather than
a failing check, since a save carries the world and the two platforms no longer need to agree bit
for bit; it reads both suites' own console output rather than paying for a third test run to get the
numbers.

`.github/workflows/nightly.yml` runs `gradlew audit` once a day; see **Testing: two tiers**.

## Multiplatform status

`:worldgen` targets **jvm** (the desktop app) and **wasmJs** (the web build); the whole correctness
suite lives in `commonTest` and runs on both, and `DebugMapDump` stays JVM-only because it renders
through `java.awt`. A third target, **js**, was removed in T1: JavaScript routes `sin`/`cos`/`pow`
through `Math`, which differs from the JVM in the last bit, and the FFT compounded that into
measurably different worlds from the same seed, while Wasm matches the JVM bit-for-bit and nothing
had consumed `js` since the web build moved to Wasm.

`WorldFingerprintTest` prints a checksum of a generated world, built from raw float bits so it
catches a difference in the last one. Run it on both targets to compare; a difference is
informational, not a blocker, since saves carry the world rather than a recipe that has to reproduce
it.

## Where things are

- [REALISM_PLAN.md](REALISM_PLAN.md), for the plan and its ledger: what each piece of work did,
  measured, in the order it happened.
- [GEOGRAPHY.md](GEOGRAPHY.md), for what the generator holds by construction and where it still
  deviates from Earth.
- [REALISM_AUDIT.md](REALISM_AUDIT.md), for the programme of work queued for the 3.0 line.
- [CODE_STYLE.md](CODE_STYLE.md), for the naming and comment conventions the code follows.
- [TODO.md](TODO.md), for issues found but not yet scheduled into a piece of work.
