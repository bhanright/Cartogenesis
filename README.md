# Cartogenesis

[![CI](https://github.com/bhanright/Cartogenesis/actions/workflows/ci.yml/badge.svg)](https://github.com/bhanright/Cartogenesis/actions/workflows/ci.yml)

A desktop app that procedurally generates fantasy world maps, built on a Kotlin Multiplatform
generation engine that also runs in the browser.

There was an Android build. It was retired in favour of desktop and web: a phone's memory ceiling
capped exports at a fraction of what the pipeline can produce, and the erosion stage in particular
wants far more compute than a handset will give it. The engine itself never depended on Android and
is unchanged by its removal — see **Multiplatform status**.

## The pipeline

Each stage feeds the next, and all of them are deterministic for a given seed.

1. **Normal map** — seeded Perlin noise generates a random surface-gradient field.
2. **Height map** — the gradient field is integrated into elevation using
   [Frankot–Chellappa](https://doi.org/10.1109/34.3909) least-squares integration via a 2D FFT.
3. **Tectonics** — the world is divided into drifting Voronoi plates. Boundaries are classified as
   convergent, divergent, or transform from the plates' relative motion, and elevation is deformed
   accordingly: mountain ranges where continents collide, volcanic arcs and trenches at subduction
   zones, ridges and rift valleys where plates separate.
4. **Erosion** — rock does not stand at an arbitrary angle: past a critical slope it fails and
   slides, and the debris piles against the foot until the pile reaches that angle too. Sweeping
   that rule over the grid lowers crests and builds aprons around them, turning the walls the
   uplift left into ridges with flanks. It conserves mass, which is the point — the debris is what
   widens a belt's footprint.

   Then water. Stream-power incision lowers a cell in proportion to the square root of the area
   draining through it times the slope it sits on, so a channel that cuts down gathers more water
   and cuts deeper still — which is where dendritic drainage and sharp divides come from, and no
   amount of smoothing produces them. The two are interleaved rather than run in sequence: alone,
   incision cuts a slot one cell wide, and a one-cell slot is twice as steep on a grid twice as
   fine. Letting the walls fail between rounds caps them at an angle that belongs to the map.

   Runs before sea level, since eroding the terrain changes which elevation the percentile lands
   on — and nothing cuts below that level, because it is the base level every river grades to.
5. **Sea level** — everything below a chosen elevation percentile floods.
6. **Ocean currents** — wind dragging on the sea has a curl, and the stream function satisfying
   that curl inside a closed basin *is* a gyre, so the currents are solved for rather than drawn.
   Water advects its temperature along them, giving warm poleward flow on western ocean margins
   and cold equatorward flow on eastern ones.
7. **Climate** — temperature from latitude and altitude, then pulled toward the sea temperature
   offshore; rainfall by marching moist air along prevailing wind bands, so windward slopes soak
   and leeward slopes fall into rain shadow. This is what lets a high-latitude west coast be
   temperate and a coast beside a cold current be arid at the same latitude. Biomes come from the
   resulting temperature/rainfall pairing.
8. **Rivers** — depressions are filled with priority-flood so no water dead-ends inland, flow is
   routed downhill (D8), rainfall accumulates downstream, and channels are traced to the coast.
   Basins the flood had to raise become lakes, with an outlet river leaving at the spill point.

## Modules

- **`:worldgen`** — the whole generation pipeline as Kotlin Multiplatform, with no platform
  dependencies at all. Builds for the JVM, WebAssembly and JS; see **Multiplatform status**.
- **`:cartography`** — turning a world into a picture, also with no graphics toolkit. The
  per-pixel work is plain `IntArray` maths and the vector overlays are *described* as geometry
  rather than drawn, so every platform makes identical decisions and implements only the drawing
  calls. Builds for JVM and Wasm.
- **`:ui`** — the interface, once, as Compose Multiplatform. Builds for the JVM and Wasm and knows
  nothing about where it is running; what genuinely differs arrives as a `Platform`.
- **`:desktop`** — a window, a native save dialog, files on disk, and OpenGL. Around forty lines.
- **`:web`** — a page, local storage, downloads, and WebGPU. Around the same.

## Running it

```bash
./gradlew :desktop:run
```

For the browser build:

```bash
./gradlew :web:wasmJsBrowserProductionRun
```

That serves the optimised bundle at http://localhost:8080. Use the *production* task rather than
`wasmJsBrowserDevelopmentRun`: the development bundle is unoptimised Wasm and generates several
times slower, which on a single-threaded target is the difference between a pause and a wait. To
produce a folder to host rather than serve locally, `./gradlew :web:wasmJsBrowserDistribution`
writes one to `web/build/dist/wasmJs/productionExecutable`.

It has to be served over HTTP rather than opened as a file: Wasm will not load from `file://`, and
WebGPU is only offered in a secure context, which `localhost` counts as.

The two are the same application. `:ui` holds all of it, including the renderer — Compose
Multiplatform carries the same Skia in both places, so a map drawn in a tab is drawn by exactly the
code that draws it on the desktop. What each front end supplies is the short list in `Platform`:
where saved worlds live, what export means, and whether there is a graphics device.

The browser starts at a working resolution of 512 against the desktop's 1024, and that is a
platform decision rather than a preference. A tab has one thread, and `Dispatchers.Default` there
is that same thread, so generating does not merely take longer — it stops the page answering until
it finishes. Measured, 512 takes about 1.6 seconds of CPU work in Wasm against 1.4 on the JVM's
fifteen threads; 1024 would be over a minute and would read as a hang. With WebGPU enabled the
erosion part of that drops to about 23 milliseconds.

### Packaging

```bash
./gradlew :desktop:createDistributable
```

Produces a self-contained folder at `desktop/build/compose/binaries/main/app/Cartogenesis` with
its own bundled JRE — run `Cartogenesis.exe`, no install needed. `./gradlew :desktop:packageMsi`
builds a Windows installer instead; `packageDeb` and `packageDmg` exist for the other platforms
but only build on their own OS.

A packaged build runs on a different runtime from the one the tests use: `jpackage` runs `jlink`,
which bundles only the modules it can prove are needed. It cannot see through LWJGL's reflection,
so the first packaged build shipped without `jdk.unsupported` — the module holding
`sun.misc.Unsafe` — and reported the GPU as unavailable with a `NoClassDefFoundError`, while
everything in development worked. `nativeDistributions` now asks for that module explicitly.

Because no test can catch that class of problem, the packaged app answers for itself:

```bash
Cartogenesis.exe --gpu-check
```

which prints the device it found, or why it found none, and exits without opening a window.

Packaging needs `jpackage`, which the JetBrains Runtime bundled with Android Studio does **not**
include, so the build looks for a full JDK in the usual install locations. Point it somewhere else
with `-PjdkHome=/path/to/jdk` or the `JPACKAGE_HOME` environment variable. Only the packaging step
uses it; the rest of the build carries on under whatever Gradle is running.

The `-Xmx12g` from the application block is baked into the launcher, so a packaged build has the
same headroom as running through Gradle.

The desktop build exists for headroom. Measured on this machine:

| Export | Time | Peak heap |
|---|---|---|
| 2048 x 2048 | 14 s | 626 MB |
| 4096 x 4096 | 53 s | 2.0 GB |

The app requests `-Xmx12g`, which is what makes those sizes reachable at all.

Exports are written as PNG or WebP. PNG is lossless. WebP comes out around a quarter of the size,
but Skia exposes no lossless WebP encoder, and the loss lands where a map can least afford it: the
average pixel drifts about 3 of 255, while the worst 0.1% drift by nearly 60, and those are the
river lines and borders, because that is where the sharp edges are. `ExportSmokeTest` measures
both numbers so the description in the UI stays true.

Where the time goes, at 2048 (see `StageProfileTest`): erosion 82%, realms 6%, ocean currents 4%,
tectonics 2%, landmarks 2%, terrain 2%, rivers and climate 1% each. A 2048 world takes about 36
seconds, of which erosion is 29.

Erosion dominates for a structural reason. Material moves one cell per sweep, so covering a given
distance across the map takes proportionally more sweeps on a finer grid, and the cost per doubling
of resolution is therefore eightfold rather than fourfold. Tiles that have gone quiet are skipped,
which is exact — `ErosionSkipTest` asserts bit-identical output — but only buys around 1.3x,
because terrain roughness at cell scale rises with resolution and most of a fine grid is genuinely
still moving.

Erosion is a pure stencil over independent cells, so it is also the one stage worth running on a
graphics card, and there is an opt-in toggle for it. On an RTX 3070 Ti the sweeps that take 1.3
seconds on fifteen CPU threads take 22 milliseconds — around 55x — through OpenGL compute shaders
on the desktop, and about 70x through WGSL in the browser. Realm expansion is a Dijkstra over a
priority queue and would not suit a GPU regardless.

Both paths run the same algorithm and agree with the CPU to about seven parts in a million. The
browser one can be checked on any machine by loading the web build with `?selftest` in the URL,
which erodes a terrain both ways and reports the timings and the difference — a browser's device
cannot be reached from an ordinary test, so that is where its test lives.

The catch is arithmetic. Graphics hardware fuses multiplies and adds and need not sum in any
particular order, so the terrain it returns is not bit-identical to the CPU's, which breaks the
assumption that a seed and a config are enough to reproduce a world. Measured, the gap is six parts
in a million of the elevation range, and it survived the rest of the pipeline without changing a
single coastline cell, river or border on the seed it was tested against. But "did not differ this
time" is not a guarantee, so a world generated this way stores its terrain in the save rather than
relying on being regenerated — see `TerrainSnapshot`. Worlds generated on the CPU store nothing
extra, because for them the seed really is enough.

## Building

Built and verified against JDK 25, Kotlin 2.4.10, Gradle 9.7.1 and Compose Multiplatform 1.9.3.
Any JDK 17 or newer will do; `:desktop` targets 17.

```bash
./gradlew :worldgen:jvmTest
```

```bash
./gradlew :worldgen:wasmJsNodeTest
```

```bash
./gradlew :app:assembleDebug
```

## Realms, the atlas, and saving

After the physical world is generated, realms are settled on it. Origins are seeded on the most
liveable ground and grown outwards by cheapest-cost expansion, where mountains, deserts, ice and
open water all cost more to cross than farmland — so borders end up on ranges, rivers and coasts
without ever being told to follow them. `WildernessMode` decides whether the leftovers get carved
up too (`CLAIM_ALL_LAND`) or stay unclaimed (`LEAVE_WILDERNESS`); it only changes the leftovers,
since cheapest-path assignment gives the same borders between settled regions either way.

Landmarks — monster lairs, ruins, hazards, resources — are then placed on terrain that suits them
and biased hard toward country no realm claims, which is what gives unclaimed wilderness a point.

The atlas derives what it can from the map: population from the carrying capacity of the land
actually held, exports from the biomes people actually farm (habitability-weighted, not raw area —
a realm can be mostly polar waste and still be a temperate farming nation), imports from the
staples it cannot supply. Names come from per-culture syllable inventories, so neighbouring realms
sound like different peoples.

**All of it is a starting point.** Generated values sit under a `WorldOverrides` layer and every
one can be replaced by the user. Anything untouched keeps following the generator, including after
a regeneration, so a new setting does not wipe out edits. Any new generated attribute needs an
override path and a line in `WorldStore`, or it will not survive a save.

A save records the seed, the settings and the overrides — not the world, which is rebuilt from
them. A whole world is a few kilobytes.

**The format is shared.** `WorldCodec` in `:cartography` is the entire thing, built on
kotlinx.serialization, and it lives in shared code rather than in any one app, so every front end
writes files that open in the others; each supplies only where the bytes go. Serializers are generated from the config classes themselves
rather than hand-written mirrors — a parallel schema would need every new setting adding twice, and
would silently drop from saves whenever someone forgot.

`ignoreUnknownKeys` and `encodeDefaults` are what keep old saves opening: a field that no longer
exists is skipped, and one that did not exist when the file was written falls back to today's
default. `WorldCodecTest` covers that directly, along with the subtler property that untouched
override fields stay null — if they came back populated, editing one field would pin a realm's
whole entry to whatever the generator happened to produce at save time.

## Resolution

Export re-runs the whole pipeline at the target size rather than upscaling the preview, so a
bigger map means genuinely more detail. That only holds because `WorldGenConfig.atResolution`
rescales the settings that are measured in cells — the mountain-belt falloff and the per-cell
rainfall rate. Anything new that is expressed in cells rather than as a frequency or a fraction of
the map needs adding there, or exports will drift in character from what the preview showed.

Export offers 2048, 4096 and 8192. 4096 peaks around 2.6GB and takes about 50 seconds, most of it
erosion. 8192 is offered but untested; it would want roughly four times that memory, and going
much beyond it needs the pipeline reworked to run in tiles.

## Looking at the output

`DebugMapDump` in the `:worldgen` test source set renders worlds straight to PNGs under
`worldgen/build/maps/`, so generation can be inspected without launching anything — one image per
pipeline stage (normals, elevation, plates, biome, rainfall, temperature, ocean currents, winds)
plus the composed map.
It also prints river-network statistics, and includes a parameter sweep for judging the trade-off
between terrain roughness and tectonic influence by eye.

Since it runs as part of `:worldgen:jvmTest`, the maps refresh on every JVM test run.

## Continuous integration

`.github/workflows/ci.yml` runs the engine's tests on both the JVM and WebAssembly, and compiles
and tests the desktop app, on every push and pull request.

The step worth knowing about compares the **JVM and Wasm fingerprints against each other** rather
than against a hardcoded value. That catches a platform silently drifting — which would break save
portability — without failing every time the pipeline is deliberately retuned.

## Multiplatform status

`:worldgen` is a Kotlin Multiplatform module targeting **jvm** (what the desktop app consumes),
**wasmJs**, and **js**. The whole correctness suite lives in `commonTest` and runs on every target;
`DebugMapDump` stays in `jvmTest` because it renders PNGs through `java.awt`.

`WorldFingerprintTest` prints a checksum of a generated world, built from raw float bits so it
catches a difference in the last bit. Run it on two targets and compare — it is the check that says
whether a world saved on one platform reopens identically on another.

Measured on 2026-08-23, seed 42 at 128x128:

| Target | Shared suite | Elevation fingerprint |
|---|---|---|
| jvm | 16/16 pass | `4283446780793226894` |
| wasmJs | 16/16 pass | `4283446780793226894` |
| js | 15/16 pass | `-2412777715130564537` |

**Wasm is bit-identical to the JVM. Kotlin/JS is not.** JS routes `sin`/`cos`/`pow` through
JavaScript's `Math`, which differs from the JVM in the last bit; the FFT compounds that, and the
same seed produces a different world — different enough to fail the resolution-consistency guard.
Since a save stores a seed rather than a world, **Wasm is the target that keeps saves portable**,
and that matters more here than the usual performance argument.
