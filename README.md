# Cartogenesis

[![CI](https://github.com/bhanright/Cartogenesis/actions/workflows/ci.yml/badge.svg)](https://github.com/bhanright/Cartogenesis/actions/workflows/ci.yml)

A desktop app that procedurally generates fantasy world maps, built on a Kotlin Multiplatform
generation engine that also runs in the browser.

## The pipeline

Each stage feeds the next, and all of them are deterministic for a given seed.

1. **Normal map** — seeded Perlin noise generates a random surface-gradient field.
2. **Height map** — the gradient field is integrated into elevation using
   [Frankot–Chellappa](https://doi.org/10.1109/34.3909) least-squares integration via a 2D FFT.
3. **Tectonics** — the world is divided into drifting Voronoi plates. Boundaries are classified as
   convergent, divergent, or transform from the plates' relative motion, and a convergent boundary
   is then refined by which crusts are meeting: ocean under continent raises a narrow coastal range
   with a volcanic arc inland of its trench (the Andes), continent against continent raises a
   broad flat-topped plateau instead of a line (Tibet), and ocean under ocean raises a trench and a
   chain of volcanic islands. Divergent boundaries under continental crust open a rift valley
   between rebounding shoulders: not one trough for the boundary's whole length but a chain of
   half-grabens, each tilted the opposite way from its neighbour and parted by an accommodation
   zone where the floor rises, so at a low sea level a rift is gulfs and lakes behind sills rather
   than a single strait. Under oceanic crust they stay a spreading ridge. A few oceanic
   plates also carry a hotspot, a fixed point the plate drifts over, leaving a decaying line of
   seamounts along its path — the only islands the pipeline places away from a boundary.
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

   A river is a conveyor rather than a drain, so the water pass also carries what it cuts. Each
   cell's sediment load travels down the same flow network, and wherever the load exceeds what the
   local gradient can hold, the surplus settles: floodplains along the lower trunks, alluvial fans
   where a range front meets the plain, fans at lake inflows, and deltas where the big rivers reach
   the sea. Mass balances round by round — everything taken off the land is either laid down again
   or carried out to sea — and no cell is ever raised as high as the ground feeding it, so nothing
   ends up running uphill. A delta lobe slopes seaward from its apex and the river runs across it
   to open water rather than ending in a pocket behind it.

   A basin that fills to its spill point overflows, and the overflow cuts its lip down. Each round
   breaches the sill of every filled basin by the stream power of the outflow — the basin's whole
   catchment over the outlet channel's slope — never below the basin floor, so the notch deepens
   toward grade and the next fill is shallower. That is what drained Lake Bonneville and Lake
   Agassiz, and it is why a filled tectonic basin no longer survives as a lake larger than the
   Caspian's share of the map.

   Runs before sea level, since eroding the terrain changes which elevation the percentile lands
   on — and nothing cuts below that level, because it is the base level every river grades to.
5. **Sea level** — everything below a chosen elevation percentile floods. The rivers cut to a stand
   about 120 m lower, so the flooding drowns their lower valleys — but only the trunk ones show,
   because a valley narrower than half a cell is a channel through a cell of dry ground rather than
   a bay, and at six to twelve kilometres Earth's coasts are indented by the Chesapeake and the
   Severn and by nothing smaller. Then the waves get the six
   thousand years since the sea stopped rising. On the third of the shoreline with low ground behind
   it — Earth's own share of depositional coast — sediment fills the re-entrants the drowning left,
   never damming a channel and never cutting a headland back, because in six thousand years a cliff
   retreats a few kilometres and a delta plain advances a hundred. So a coast on a plain comes out a
   graded arc and a coast under mountains keeps its rias. The sea floor near a
   coast is then remapped onto a shallow continental shelf that falls away to the abyss beyond it,
   so a coastline reads as bathymetry rather than a cliff underwater. In the same step, wherever
   the provisional annual temperature sits below freezing, ice takes over, in one of two ways
   decided by local relief. In channelled ground valley glaciers take the valleys the rivers already
   cut: they widen and flatten them into U-shaped troughs, bite a cirque out of every head, and dam
   a staircase of over-deepened basins behind moraines. On flat ground an ice sheet scours instead,
   planing the surface down and leaving irregular closed basins that owe nothing to the drainage
   grid — which is where shield lake country comes from. Nothing here moves the coastline; it only
   reshapes what is already land or already sea.
6. **Ocean currents** — wind dragging on the sea has a curl, and the stream function satisfying
   that curl inside a closed basin *is* a gyre, so the currents are solved for rather than drawn.
   Water advects its temperature along them, giving warm poleward flow on western ocean margins
   and cold equatorward flow on eastern ones.
7. **Climate** — temperature from latitude and altitude, then pulled toward the sea temperature
   offshore; rainfall by marching moist air along prevailing wind bands, so windward slopes soak
   and leeward slopes fall into rain shadow. The wind is not purely zonal: trades carry toward the
   thermal equator and westerlies toward the pole as well as around it, so the march runs
   diagonally rather than along rows, and a ridge running east-west is no longer invisible to the
   rain. This is what lets a high-latitude west coast be temperate and a coast beside a cold
   current be arid at the same latitude. All of it runs twice, for the warm season and the cold
   one, with the thermal equator — and the wind and rain belts riding on it — migrating toward
   whichever hemisphere is in summer; where that carries onshore flow over a tropical coast, it is
   the monsoon. The swing between the two seasons is damped over open water and amplified with
   distance from it, so an interior climbs and drops further through the year than a coast at the
   same latitude does. Rainfall is calibrated to approximate millimetres per year rather than
   rescaled per world, so an arid world actually classifies as drier than a lush one. Biomes come
   from the four seasonal numbers, Köppen-style: the temperate/continental/tundra boundary reads
   the coldest and warmest month rather than the annual mean, which is what lets a mild-winter
   maritime coast forest over while its interior at the same latitude stays taiga. The result tells
   a Mediterranean coast (wet winter, dry summer) and a monsoon forest (dry winter, drenching
   summer) apart from a temperate forest of the same annual rainfall.
8. **Rivers** — depressions are filled with priority-flood so no water dead-ends inland, flow is
   routed downhill (D8), rainfall accumulates downstream, and channels are traced to the coast.
   Basins the flood had to raise become lakes — but only as much lake as the water balance allows.
   Where the catchment's runoff cannot match evaporation from the full surface (Thornthwaite, from
   the two seasonal temperature fields: about 2300 mm a year in a hot desert, 550 in cool temperate
   country) the lake settles below its rim, is endorheic, and the rivers end in it; a basin too dry
   to hold water at all is a playa. A basin that balances at the brim overflows as before, with an
   outlet river leaving at the spill point. A course runs from its farthest headwater rather than
   its biggest, so a river is the whole of the longest watercourse in its catchment, and it runs on
   through water one cell wide — a lake's spill level covers the channel that feeds it, and a strip
   of water that narrow is the river. A channel is drawn as wide as the water it carries: Leopold
   and Maddock's downstream hydraulic geometry has width going as the square root of discharge, so
   the map's smallest stream is a 0.8-pixel hairline and its biggest river a quarter of a percent
   of the map's width — 2.5 pixels at 1024, 4.9 at 2048, 9.8 at 4096 — which is the same weight of
   ink against the same country whatever size the sheet is. The stroke stops at the shoreline
   rather than running on into the sea.

## What a map says about itself

Three things a chart carries that a picture does not, all of them drawn on top of the raster and
none of them touching the world underneath.

**Generalisation.** A map is not the same map at every size, so the drawing is done for the scale it
will be seen at. Rivers below a discharge threshold are dropped as the reader zooms out and come
back as they zoom in, the count kept by Töpfer and Pillewizer's radical law (1966): the number of
features that survive a reduction in scale goes as the square root of the change in scale. A 2048
world fitted into a laptop's pane is shown at about 0.44 pixels to the cell, so about 70% of its
rivers are drawn — 198 of 718106's 279 — and at four times zoom every one of them is back. An
export is drawn cell for pixel and never loses anything. The coast is traced off the land mask as
polylines and simplified by Douglas–Peucker at half a drawn pixel, then stroked over the raster, so
it reads as a *line* rather than as a staircase of cell edges; the fill stays the raster's.

**A graticule**, as a Cartography toggle beside Relief shading and Coastline, drawn on screen and on
exports. Lines of latitude and longitude every ten degrees, which on an equirectangular map of a
whole globe is exactly a thirty-sixth of the width and an eighteenth of the height — the spacing is
not rounded to whole cells, because that would put the equator off the middle row. The edges are
figured (`40°N`, `170°W`), at ten degrees on a 2048 sheet and at twenty or thirty on smaller ones,
where the figures would otherwise run into one another.

**A scale bar and a scale.** The legend along the map's foot carries a bar in kilometres, its length
the longest round distance from the 1–2–5 series that fits a quarter of the frame; it restates
itself as the reader zooms, so at fit it reads 2000 km and at 32 pixels to the cell it reads 20 km.
An exported sheet carries the same bar drawn in its bottom-left corner, since there is no legend
beside a PNG. The cartouche gains a line giving the scale at the sheet's own size — `5.9 km per
pixel · about 1:22 000 000 at the equator` for 2048 — the fraction quoted at the CSS reference
pixel's 96 to the inch, to two figures, and *at the equator* because on an equirectangular map
east-west distances shrink with the cosine of the latitude and no scale bar can pretend otherwise.

All of it comes off one number the world already carries, `NationsConfig.worldWidthKm`: twelve
thousand kilometres east to west, which is also where realm areas and the heightmap sidecar's cell
size come from. The two words a chart prints — a graticule figure and the bar's distance — are
drawn as stroked geometry rather than set as type, because they have to appear on an exported PNG
as surely as on the screen and a typeface that resolves on the desktop but not in a browser would
make one map into two.

## Styles

Twelve ways of drawing the finished map: **Atlas** (modern hypsometric tints), **Vellum** (aged
parchment and sepia ink), **Ink wash** (sumi-e, grey ink on pale paper), **Nautical** (an admiralty
chart with depth-banded water), **Midnight** (moonlit, rivers left luminous), **Schoolroom** (the
saturated pull-down physical map from a classroom wall), **Verdant** (illustrated fantasy: teal sea,
cream land, deep woods), **Scroll** (painted parchment with a jade sea and vermilion marks),
**Pen and ink** (line art: no fill at all, relief hatched, borders in red), **Mars** (the same
world as a dry planet), **Natural** (the world as a satellite sees it, in a palette sampled off a
Blue Marble photograph: saturated forest greens, olive plains, ochre and rust deserts, a deep
cobalt sea turning turquoise over the shelves) and **Colour-blind** (a cividis-ordered land ramp
over one flat slate sea, with Paul Tol's muted nine hatched beyond nine realms, so nothing is told
by hue alone).

Mars is the one that changes what the map *says* rather than only how it looks. The world beneath
it still has a sea, rivers and lakes — the generator is untouched — but the ocean basins are drawn
as basalt plains instead of water, the old shoreline is a faint scarp rather than a coastline, the
rivers are dark channels in the dust, and the land climbs from rust through ochre to pale dust and
white. All of it is palette, which is why the graphics card draws it identically: the accelerator is
handed pre-packed colours and knows nothing about which style they came from.

Pen and ink is the one that is a different *way* of drawing rather than a different palette:
nothing is tinted by height, the paper shows through everywhere, and relief is hatched — diagonal
strokes laid where the ground is steep and left off where it is flat. It stops short of what it
imitates, in one honest respect: a hand-drawn map draws each range as a little picture shaded by
eye, where this hatches by slope, so the texture is right and the pictograms are not there.

Natural is the one whose palette was measured rather than chosen. Every colour in it is sampled
off one photograph — a Blue Marble view of Earth centred on North America — region by region, and
each constant in the source carries the pixel box its median came out of: the land ramp is that
image's eastern woodland, Mississippi lowland, Pacific north-west, Great Plains olive, Great Basin
umber, Chihuahua ochre, Colorado red rock and Greenland snow, in that order, which happens to be
their order of lightness. Its climate lever is at full, because on a photograph the colour of a
place is what grows there and the height only shows through where nothing does.

A style changes appearance and nothing else — the same seed gives the same world in all twelve — and
the diagnostic views ignore styles entirely, because their colours mean something and a prettier
ramp would make them lie.

Most of the difference between them is four numbers rather than five separate repaints. How much
vegetation colour is let through decides whether a map reads as terrain seen from above or as
something drawn. How far each biome colour is dragged toward the paper first is what stops an aged
chart looking like a modern one with a filter over it — old inks are earths, not dimmed greens. The
third is how far the height ramp itself follows the climate, which is what the next section is
about. And how hard the hillshade is exaggerated is why the ink style works at all: with the colour
gone, relief is the only thing left describing the mountains.

### Tints that follow the climate, and light from the sky

A hypsometric ramp says that this height is that colour, and on a world with more than one climate
that is a lie: the green a ramp gives a coastal plain is a wet plain's green, and drawn over a
desert it puts a lawn on the Sahara. Imhof's answer, and every good atlas's, is that the ramp is not
one series but a series modulated by what grows there, and that is what the land colour now is. Each
cell carries three numbers about its own ground — how bare it is, how frozen, and how closed the
canopy over it — and each style says through one lever how much of that to let through: the full
effect on Atlas and Schoolroom, a suggestion on the aged papers, nothing at all on Pen and ink,
which has no tint, or on Colour-blind, whose ramp is a measured promise nothing may move. How bare
the ground is comes from De Martonne's aridity index, the year's rain over the mean temperature plus
ten, spent *inside* the band of bare ground the biome itself allows — barren land is over nine
tenths bare whatever the weather does, a grassland between a twentieth and a half of it, a closed
forest none — so a desert comes out sand at every height, a steppe comes out straw, and a forest
darkens the lowland greens. Getting that wrong in the first draft, by letting the index take any
climate all the way to bare, drew the interior of a continent as Sahara.

The relief is lit by a sky rather than a lamp. One light in the north-west is the convention every
shaded-relief map has used since the nineteenth century, and it has one failure no exaggeration
fixes: a slope facing away from it receives nothing at all, so a range running the wrong way comes
out with one side white, the other black, and nothing readable inside the black. After Kennelly and
Stewart's sky models, the light now comes from eight lamps round the whole compass, each as bright
as its own eighth of the sky, plus an ambient term that falls with how much sky the ground can
actually see, measured as a horizon along those same eight bearings. How much brighter the sky is
around the light than opposite it, and how much of its light is diffuse, are two faces of one
number — how hazy the day is — and that number is derived rather than chosen: it is the haze at
which the shaded relief has exactly the contrast of the lamp it replaces (0.169 against 0.171 over
the land of the standard world), so nothing is given up in exchange. What changes is *where* the
darkness falls: on a synthetic cone as steep as the steepest tenth of a world's land, a third of the
bearings receive no light at all from the single lamp, and none do from the dome. The single lamp is
still there, as **Single-lamp relief** in the Cartography section of the panel, and under it the
older picture comes back bit for bit.

And **depth contours** in the sea, every 500 m, which is what GEBCO's small-scale sheets are drawn
at. A line is held at a fixed width in pixels by dividing by how fast the floor falls, which is
measured over a short distance of ground rather than between two neighbouring cells — between
neighbours the answer is the floor's own roughness, and a line drawn to that width covers an abyssal
plain in a nest of closed loops that mean nothing. They fade out where they would crowd closer than
four pixels, so a continental slope reads as a slope rather than as a moiré, and again where the
floor is flatter than one in a thousand, which is the definition of an abyssal plain and the point
below which a contour stops describing anything.

`StyleGalleryTest` writes all ten out to be looked at, since no number says whether something
resembles vellum. What it does assert is that they differ from one another — a style quietly
falling back to the default would pass any test that only asked whether rendering succeeded.

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

While a world is being built the Generate button reads **Stop**, and pressing it hands the settings
straight back: the generation is abandoned within a round of erosion rather than at the end of it,
the map that was on screen stays there — an empty canvas stays empty — and the status line says
which stage it had reached. Nothing half-built is kept, so the next generation reuses whatever
stages of the last *finished* world its settings still allow.

### Putting it somewhere

`:web:wasmJsBrowserDistribution` writes a complete static site to
`web/build/dist/wasmJs/productionExecutable`. Upload the contents of that folder to any static host
— Netlify, Cloudflare Pages, GitHub Pages, itch.io, a directory on a web server. There is no server
side to it.

Four things worth knowing before choosing a host, all of them checked rather than assumed:

- **Size.** About 13MB of files, of which 12MB is the two `.wasm` blobs, but roughly **4.3MB** once
  gzipped, which every static host does by default. Skia is the bulk of it.
- **The `.wasm` MIME type does not matter.** The build instantiates from a buffer rather than
  streaming, so a host serving `application/octet-stream` still works. This is the usual reason a
  Wasm site fails to start elsewhere, and it does not apply here.
- **Paths are relative**, so a subfolder is fine — `example.com/maps/` works without rebuilding.
- **Use HTTPS.** WebGPU is only offered in a secure context; over plain `http` the page still works
  but silently falls back to the processor, which at 512 is the difference between a pause and a
  wait.

`cartogenesis.js.map` is a 1.7MB source map. Browsers only fetch it when devtools are open, so it
is harmless, but it is also of no use to anyone but you and can be deleted from the upload.

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

which prints the device it found, or why it found none, once for the erosion sweeps and once for the
export raster — they compile different shaders on the same context — and exits without opening a
window.

Packaging needs `jpackage`, which the JetBrains Runtime bundled with Android Studio does **not**
include, so the build looks for a full JDK in the usual install locations. Point it somewhere else
with `-PjdkHome=/path/to/jdk` or the `JPACKAGE_HOME` environment variable. Only the packaging step
uses it; the rest of the build carries on under whatever Gradle is running.

The `-Xmx12g` from the application block is baked into the launcher, so a packaged build has the
same headroom as running through Gradle.

The desktop build exists for headroom. Measured on this machine:

| Export | Time | Peak heap |
|---|---|---|
| 2048 x 2048 | 26-35 s | 1.0 GB |
| 4096 x 4096 | 154-182 s | 3.2-4.0 GB |
| 8192 x 8192 | — | exhausts a 10 GB heap after 19 min |

Measured 2026-09-12 by `ExportSmokeTest`, both sizes in one JVM, heap read once at the end; the
spread is two runs of the same test on the same machine, which is how much a figure like this can
be trusted. In a fresh JVM, where nothing is warm, 4096 takes 224 s and its high-water occupancy
sampled throughout is 7.5GB — worth knowing, because that is what someone who exports the first
thing they generate actually waits for. Both times have grown a long way since the 53 s in an
earlier revision of this table: the generator has gained the crust-pair belts, the deltas and the
Koppen biomes since, and none of that is free.

The app requests `-Xmx12g`, which is what makes those sizes reachable at all — and is not enough for
8192, as the Resolution section below records.

A finished world can leave in two kinds of file: a picture of the map, or the world's own numbers.

**Pictures** are written as PNG, WebP or JPEG. PNG is lossless. WebP comes out around a quarter of
the size, but Skia exposes no lossless WebP encoder, and the loss lands where a map can least
afford it: the average pixel drifts about 4 of 255, while the worst 0.1% drift by about 75, and
those are the river lines and borders, because that is where the sharp edges are — and more so
since rivers were sized by their discharge, which draws every headwater as a sub-pixel thread.
JPEG, at quality 90 through the JDK's own encoder on the desktop and Skia's in the browser, is
there for the programs that still will not open a WebP and for no other reason. It is not the
better format, but it is not the larger one either, and the two facts are worth stating together
because the expectation runs the other way: on seed 42 at 512 the same picture is 78 KB as a JPEG
at 90 and 108 KB as a WebP at Skia's maximum, and the WebP is the more faithful of the two at every
percentile (mean drift 5.53 against 6.99, 99th 53 against 55). Ask the JPEG encoder for 100 and it
produces 214 KB — twice the WebP — for the WebP's own fidelity. So the choice is a smaller, softer
file or a larger, sharper one, and the chip's small print says so. `ExportSmokeTest` and
`DataExportTest` measure every one of those figures, so the description in the UI stays true.

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

Generation is the whole of the wait, as it is for the pictures: at 4096 a heightmap is three seconds
of encoding behind three minutes of world. The 16-bit PNG is the largest file the application
writes after the lossless picture — 18.1 MB from 33.5 MB of raw samples, so the filtering earns
about half — and the two index maps are almost free, because a biome map is large flat regions and
that is what deflate is for.

Each is a pair of files: the PNG and a JSON sidecar of the same name. The sidecar always carries the
seed, the pixel dimensions, the world's width in kilometres (12,000), the cell size in kilometres at
that export size, the square kilometres per cell, the save format version and the build that wrote
it; the heightmap's adds the metre scale. **Sea level is grey level 32768, on every world** — a
fixed number rather than one derived per world, because its job is to be typed into somebody else's
program, and there is one metres-per-grey-level for the whole image rather than one for the land and
another for the sea: 32767 levels each side of the waterline, so at the default 6,000 m of relief a
grey level is 0.1831 m and white and black are +6,000 m and -6,000 m. Land below the waterline is
written as it is rather than clamped — a basin the sea cannot reach is drained out into a salt flat
below sea level, which is the Qattara, and on seed 42 at 512 that is 497 cells.

Both PNGs are written by a small encoder in `:cartography` rather than by either host's imaging
library, because neither will write what these need: Skia is eight bits a channel everywhere and a
browser canvas is eight-bit RGBA by construction, and neither writes an indexed image at all. The
image data is a zlib stream, which common Kotlin cannot build, so the deflate comes out of the gzip
each host already has behind `Compressor` — the same stream in a different envelope — with a
stored-deflate fallback for a host that has neither. The fallback is about a third larger and every
reader still opens it; `DataExportTest` checks that both paths produce identical samples.

The desktop writes the two files side by side from one save dialog. The browser sends a single zip
holding both, and that is a deliberate choice rather than a limitation: two downloads from one click
raises Chrome's unexplained "download multiple files" prompt and has historically lost the second
file in Safari, while a heightmap that arrives without its metre scale is a grey rectangle. The
archive stores rather than deflates — the PNG inside is already compressed and the page has one
thread.

Where the time goes, at 2048 (see `StageProfileTest`): erosion 82%, realms 6%, ocean currents 4%,
tectonics 2%, landmarks 2%, terrain 2%, rivers and climate 1% each. A 2048 world takes about 36
seconds, of which erosion is 29.

Erosion dominates for a structural reason. Material moves one cell per sweep, so covering a given
distance across the map takes proportionally more sweeps on a finer grid, and the cost per doubling
of resolution is therefore eightfold rather than fourfold. Tiles that have gone quiet are skipped,
which is exact — `ErosionSkipTest` asserts bit-identical output — but only buys around 1.3x,
because terrain roughness at cell scale rises with resolution and most of a fine grid is genuinely
still moving.

Drawing the map is on the graphics card too, and not behind that toggle. `MapRasterizer`'s work is
per-pixel — a ramp lookup, a climate-modulated tint, the sky's light and the ground's horizon, a
coast, a contour and a border test — so the whole of it is one compute dispatch per tile of the
export (`GpuRaster`, behind the `RasterAccelerator` seam in `:cartography`). At 4096 it draws 16.7
million pixels in 0.43 s against the processor's 1.65 s, and at 8192, in sixteen tiles, in 0.84 s.
The sky model is what widened that gap: it asks the terrain twenty-four more questions per land
pixel than a single lamp does, which doubles the processor's raster (0.81 s at 4096 under the lamp)
and costs the device nothing it notices. The shader is handed a `RasterRecipe` — every colour
already packed, every ramp already chosen, a colour table per realm, people and plate, and the two
per-cell numbers the climate has to say about the ground — so nothing about the palette or the
aridity index is written twice; the blends are integer and truncate where `MapPalette` truncates,
and `GpuRasterTest` holds the two within one channel step of 255 at the 99.9th percentile across all
fifteen views in all twelve styles. It is not behind the acceleration toggle because that
toggle is a promise about whether the *world* can be regenerated from its seed, and drawing pixels
makes no such promise either way.

What that is worth end to end is less than it sounds: a 4096 export spends over three minutes
generating the world and under a second drawing it, so the raster was never the bottleneck the
profile suggested. Erosion, below, is.

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
./gradlew :cartography:jvmTest :cartography:wasmJsNodeTest
```

```bash
./gradlew :desktop:test
```

## Realms, the atlas, and saving

After the physical world is generated, realms are settled on it — not cell by cell, but by handing
out whole **drainage catchments**. A realm takes a catchment or it does not, never half of one, and
that single rule is what puts its borders on the ground: the edge of a catchment *is* a watershed,
and a watershed *is* a ridge, so a frontier runs along high country because there is nowhere else
for it to run. This replaced cheapest-cost cell expansion, which sounded as though it would settle
borders onto ridges and rivers and measurably did not — a cheapest-path border lands where two cost
fields meet, and no amount of making mountains expensive moves that meeting point onto a feature.

Three things sit on top of the basic idea, each because the plain version was wrong in a way you
could see:

- Large catchments are **cut along their trunk river**, left bank from right. Without it a river is
  always *interior* to somebody's territory and borders start avoiding water; with it a world has
  both kinds of frontier, the way the Pyrenees and the Rio Grande are both borders.
- Coastal ground looks a short way out to sea for a **far bank**, so realms can cross a strait to an
  island or a second continent. Crossing costs something — free crossings let one realm island-hop
  an archipelago and hold most of the world.
- **Enclaves are dissolved**: a pocket you can walk out of goes to whichever neighbour surrounds it
  most, while overseas islands, which you cannot walk out of, stay put. A realm keeps its largest
  piece, and if its capital is not on that piece the capital moves - keeping the capital's piece
  instead once produced a nineteen-cell sovereign state whose country had been given away.
- **No realm may hold more than a set share of the world** (`maxRealmShare`, 30%). Appetite alone
  cannot bound a realm, because it is a brake relative to the neighbours bidding for the same
  ground, and a realm that is the only bidder for a region takes it regardless - one seed produced
  a 42% empire that way. Spacing the seeds further apart stops it, but was measured to halve how
  often borders follow rivers, because river valleys are the richest ground and spacing seeds out
  of them leaves both banks to one realm. So instead a realm over the cap is split along its own
  internal watersheds, largest first, until none is.

Realm sizes are deliberately uneven. Each realm draws an appetite from a long-tailed distribution,
so a world gets a couple of great powers, several middling states and a scattering of small ones;
and a realm holding several catchments may **schism** along one of its own internal watersheds,
which is where the interesting borders come from — a line drawn inside what is geographically one
region because the people either side of it stopped agreeing.

`WildernessMode` decides whether hostile leftovers get carved up too (`CLAIM_ALL_LAND`) or stay
unclaimed (`LEAVE_WILDERNESS`); it only changes the leftovers, not the borders between settled
regions.

Landmarks — monster lairs, ruins, hazards, resources — are then placed on terrain that suits them
and biased hard toward country no realm claims, which is what gives unclaimed wilderness a point.

The atlas derives what it can from the map: population from the carrying capacity of the land
actually held, exports from the biomes people actually farm (habitability-weighted, not raw area —
a realm can be mostly polar waste and still be a temperate farming nation), imports from the
staples it cannot supply. Names come from per-culture syllable inventories, so neighbouring realms
sound like different peoples.

## Menus, settings, updates and notices

A thin strip along the top of the window carries **File** (New world, Open library, Save, Save as,
Export, Settings, and Quit on the desktop), **View** (the chrome, which panel sections are unrolled,
and the toolbar over the map) and **Help** (Check for updates, About). It is drawn in `:ui` rather
than hung off the window as a native menu bar, because a native one would leave the browser build
with no menus at all. The desktop also binds the File items to the obvious keystrokes — Ctrl+N,
Ctrl+O, Ctrl+S, Ctrl+Shift+S, Ctrl+E, Ctrl+comma, Ctrl+Q — and the browser build binds none of them,
since a page quietly taking Ctrl+S from its host is how somebody loses a tab full of work.

Settings are preferences rather than settings of a world: the chrome, the working resolution a new
world starts at, whether graphics acceleration is on at launch without being asked for, the default
export format and size, the library folder, the interface scale, and whether to check for updates at
launch. They persist through the `Platform` seam — `%APPDATA%\Cartogenesis\settings.json` on Windows
and the equivalent directory elsewhere, browser local storage on the web — as one JSON document that
shared code serialises, so neither front end owns the shape of it. A file written by a later build,
an older one, or a hand that mistyped a theme name all open, because the failure mode of a strict
parser here is an application that will not start.

There are sixteen chromes, offered on three shelves so that a list of sixteen is still a list.
**Standard** is System, Light and Dark — the paper-and-ink pair F1 drew, with System following the
host. **Accessible** is two chromes whose promise is a measured threshold rather than a look: *High
contrast* (pure black and white, 2 dp rules, type a step larger, every text pair past WCAG AAA) and
*Colorblind* (Okabe–Ito's orange and sky blue on warm dark greys, with a shape cue — an underline, a
doubled rule, a strike — wherever a state would otherwise be told by hue alone). **Styled** is
eleven rooms to work in: *Nautical*, *Midnight* and *Mars* lifted from the map styles of those names;
*Allied*, a 1940s Army Map Service sheet in buff and olive drab with its title block boxed;
*Hallowed*, an illuminated manuscript in lapis and vellum with its section rules doubled in gold
leaf; *Baroque*, gilt and walnut with the headings in italic; *Matrix*, a phosphor terminal set
throughout in IBM Plex Mono with a `>` before every heading; *Hessian*, burlap and unbleached linen
with a woven crosshatch behind the panels, running-stitch rules and a sewn label for a cartouche;
*Roman*, Pompeian red and marble with pointed inscriptional capitals and a Greek key under each
heading; and *Hitchcock*, Saul Bass's charcoal and vermilion with the section rules cut into three
displaced bars and Vertigo's spiral beside the world's name; and *Lemon Blueberry*, a deep
blue-violet room written in lemon, whose alarm is the pink blueberry pigment turns when a lemon is
squeezed into it. Every text pair in all sixteen is measured — AAA for High contrast, AA for the
rest — and the numbers are asserted rather than claimed.

**Check for updates** reads GitHub's `releases/latest` for the repository and compares its tag with
this build's version, which is generated at build time from `gradle.properties` rather than typed
into source. If there is a newer one it shows the release name, the opening of its notes, and a
button that opens the release page: nothing is downloaded and nothing updates itself. The check is
off at launch by default and is otherwise a menu item, so opening the application — and in
particular loading the web bundle — never talks to GitHub on its own.

**About** shows the version, the build date, the project licence, and the third-party notices. The
notices are generated by a Gradle task from the build's own resolved dependency graph, with each
component's licence read out of its POM, plus the three bundled OFL type faces; a hand-written list
would stop being true the first time a dependency changed. The project licence is the MIT
License in `LICENSE` at the repository root, which the About dialog reads at build time.

## Licence

MIT. See `LICENSE`. The bundled type faces are under the SIL Open Font License and every other
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

An older mirror on the author's personal site is served as static files from Porkbun and
deployed from that site's own repository by copying `web/build/dist/wasmJs/productionExecutable` and
discarding the source map, the empty `composeResources/` directories, and the emitted `index.html` —
that site supplies its own shell, for the same reason and by the same means as `site/app/index.html`
does here.

**Either shell depends on two names in this repo, and breaking either name fails silently** — the
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

One more thing a host has to work around, which is this repo's fault rather than the host's: **the
two `.wasm` files carry content hashes but `cartogenesis.js` does not.** A new build therefore lands
under new wasm names while the loader keeps its old URL, so a returning visitor with a cached loader
asks for a wasm hash the deploy has just deleted — a 404 and a dead app, not a stale one. Both sites
work around it by loading `cartogenesis.js?v=<stamp>` and stamping it on every deploy;
`:web:assembleSite` does the stamping for cartogenesis.com and fails the build rather than shipping
an unstamped shell. If this build is ever hosted somewhere else again, that host needs the same
trick, or cache headers that make it unnecessary.

The host must serve `.wasm` as `application/wasm` or the browser's streaming compiler refuses it.
Compression is worth turning on: 12.4 MB raw is 4.4 MB gzipped, and Skia is two thirds of it.

## Peoples

A second layer over the same ground: who lives there, as opposed to who governs there. It is
generated from a different pressure than the realms are, which is the entire reason it exists.

A realm takes catchments, because a state's reach is a question of ground it can hold. A people
spreads through country that resembles the country it came from — so a culture grows outward from
its hearth at a cost set by how *unlike home* the next piece of land is, and comes to rest where the
climate turns rather than where a border was drawn. The cost is measured against the hearth and not
against the neighbour: measured against the neighbour a people drifts, because every step is a small
change and a chain of small changes walks a steppe people into a rainforest.

Hearths are seeded once, before any spreading starts, and are shared out between landmasses in
proportion to each one's *habitable* area rather than scored globally — otherwise the few
best-scoring sites crowd onto whichever landmass is largest, and the hearths left over for
everywhere else split too little ground between too many competitors. Which cells end up settled
is then decided per cell against that cell's own biome rather than by a vote across the larger unit
it belongs to, so a catchment straddling a retreating ice margin can end up half tundra and settled,
half ice sheet and not, instead of the ice vote stranding the whole thing.

There are fewer peoples than realms, because a culture is the larger unit. The result is that the
two layers disagree — a people spans several states, a state holds several peoples, and the mismatch
is where a world's history comes from. `CultureRealmTest` measures the disagreement rather than
trusting it: peoples span 1.4–1.9 realms each, and 70–100% of cultural frontier runs inside a
country rather than along its border. A layer that quietly reproduced the political map would be
worse than no layer at all.

Ice sheets are left empty, and are crossed rather than settled: treating them as impassable stranded
everything behind them, which on one seed meant a third of the world's land.

**All of it is a starting point.** Generated values sit under a `WorldOverrides` layer and every
one can be replaced by the user. Anything untouched keeps following the generator, including after
a regeneration, so a new setting does not wipe out edits. Any new generated attribute needs an
override path and a line in `WorldStore`, or it will not survive a save.

**A save carries the world, not the recipe for it.** It used to record the seed, the settings and
the overrides, and rebuild the world on open — which made a file a few kilobytes and made
bit-identical generation on every platform a hard requirement, since a world saved in a browser
had to come back the same on the desktop. The GPU toggle broke that rule outright and had to
smuggle its eroded terrain into the file to get round it.

So a version-3 save is a container: an uncompressed JSON header — format version, settings,
overrides, labels, title, which front end wrote it, and a directory of what follows — then one
binary section per per-cell array, gzipped. Binary because the arrays *are* the file: 1024x1024 is
fifteen float fields, seven id maps and two byte maps, 94 MB before compression and roughly three
times that as JSON. Little-endian `float32` for anything that must round-trip exactly, `int32` for
cell ids, a byte per cell for land and biome; the small lists — rivers, lakes, realms, peoples,
landmarks — stay in the JSON header, where a tool can read them without knowing the layout.

Gzip takes a 1024 save to about 38 MB and a 512 save to about 10 MB, roughly 2.5:1. The spread is
the interesting part: the id maps are long runs of the same integer and compress 100:1 or better,
while the height fields are noise by construction and barely move at 1.1:1. Compression is a seam
on `Platform`, because the JVM has `java.util.zip` and a browser has `CompressionStream` and
common code has neither; a platform that cannot compress stores the payload raw and says `none` in
the header, so the file still opens anywhere.

Opening a save is deserialisation followed by a generation pass that reuses every stage and
computes none — the same reuse chain live editing runs on, so editing a setting after opening
recomputes only what lies downstream of it.

**Version-2 saves still open.** They are JSON text with no world in them, so they are regenerated
from the seed exactly as before, and the next save writes them out in full. Nothing has to be
migrated by hand.

**A save missing a section regenerates rather than refuses.** Sections are grouped by the stage
that produced them, and every future field added to a stage is a new section — which would
otherwise make each such change unable to open a save written before it. Instead a stage with any
section missing comes back `null` and the same reuse chain that skips an unchanged stage on a live
edit regenerates a missing one and everything downstream of it; a corrupt section — the wrong
length, a bad type code — still throws, because that is a different problem than an old file. The
header records which stages are present, so the library pane can say a save "opens with
regeneration" without reading a single array.

**The format is shared.** `WorldCodec` in `:cartography` is the entire thing, built on
kotlinx.serialization for the header, and it lives in shared code rather than in any one app, so
every front end writes files that open in the others; each supplies only where the bytes go, and
what it can compress with. Serializers are generated from the config classes themselves
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

Export offers 2048, 4096 and 8192. 4096 takes a little under four minutes on this machine, nearly
all of it erosion, and its high-water heap is around 7.5GB of the 12 that the launcher asks for.
The size chips cap the data exports exactly as they cap the pictures — the ceiling is about how big
a world this build can finish, and knows nothing about what kind of file comes out of it.

8192 does not work, and now there is a measurement rather than a suspicion: it exhausts a 10GB heap
after about nineteen minutes, inside the generator, before a single pixel is drawn (`-Pbenchmark=true`
on `GpuExportBenchmarkTest` repeats it). The fields for a world that size come to roughly 9GB before
the transient buffers the FFT and the erosion sweeps want on top. The drawing is not the problem —
the graphics card rasters 8192 in 1.4 seconds, in sixteen tiles, with no world in memory at all — so
reaching that size means making generation work in tiles or on disk, not making the renderer bigger.
Until then 8192 should be treated as a size the UI offers and the machine refuses.

## Looking at the output

`DebugMapDump` in the `:worldgen` test source set renders worlds straight to PNGs under
`worldgen/build/maps/`, so generation can be inspected without launching anything — one image per
view: normals, elevation, plates, biome, rainfall, temperature, realms, peoples, habitability, a
coarse wind-vector field, and a boundary-class view coloured by crust pair (Andean margin,
collision plateau, island arc, ridge, rift, transform), plus the composed fantasy map. Rainfall and
temperature are each dumped three times — annual, warm season, cold season — and a fourth
season-contrast view shows which half of the year the rain falls in, since the seasonal fields are
the only place the belts can be seen to migrate. `OceanCurrentTest` writes its own current-vector
image to the same directory. Glaciated coasts also get a four-times close-up, with and without ice,
since a trough or a tarn is a few cells wide and disappears at whole-map scale. It also prints
river-network statistics, and includes a parameter sweep for judging the trade-off between terrain
roughness and tectonic influence by eye.

`DebugMapDump` moved to the audit tier in T1 (see below), so the maps no longer refresh on every
JVM test run — run `./gradlew audit` (or `./gradlew :worldgen:jvmTest --tests '*DebugMapDump*'
--rerun` directly) to regenerate them.

## Testing: two tiers

T1 (2026-09-12) split the test suite into a fast tier that runs on every merge and an on-demand /
nightly tier for the tests that are slow, or report rather than assert, or exist to be looked at
rather than to gate a build.

**Per-merge** (`:worldgen:jvmTest :cartography:jvmTest :desktop:test`) is what CI and a developer
run before every merge: correctness guards only, chosen to be fast. Measured on this build, loaded:
worldgen dropped from roughly 23 minutes (13 idle) to about 6, and desktop from about 5 to about 2.

**Audit** (`./gradlew audit`, unqualified so it runs the `audit` task in every subproject that
declares one — `:worldgen` and `:desktop` today) carries everything that moved out: the render
harness `DebugMapDump`, `StageProfileTest`, `GenerationSpeedTest`, `DesertCauseTest`,
`ColdCapReportTest` and `ErosionConvergenceTest` (whole classes — they report rather than assert,
or assert something CI's small runners cannot, such as `ErosionConvergenceTest`'s thread-splitting
case), the 2048-scale cases of `GlaciationTest` and `RealmIdRangeTest` (split into
`GlaciationAuditTest` and `RealmIdRangeAuditTest`; their 512/1024 siblings stay in the per-merge
classes), and `ExportSmokeTest`'s 2048/4096 exports (split into `ExportAuditTest`, which since F12
covers all three picture formats and all three data exports from one world per size; a 1024 export
stays in `ExportSmokeTest` as a per-merge smoke check, and `DataExportTest` holds the data exports
at 512). `.github/workflows/nightly.yml` runs it
once a day, on a cron schedule, so a regression in the parts the per-merge tier no longer covers is
still caught within a day.

The split is by exact class name plus a Gradle `filter { excludeTestsMatching(...) }` on the
per-merge test task and the inverse (`includeTestsMatching`) on `audit`, in both `:worldgen`
(JUnit4, via `kotlin("test-junit")` — no `@Tag`, JUnit4's nearest equivalent is `@Category`, which
needs more Gradle wiring than a class-name filter for a fixed list) and `:desktop` (JUnit5, where
the same mechanism works unchanged). A generic naming convention such as every class ending
`AuditTest` was considered and rejected: `GeographyAuditTest` already carries that name for an
unrelated reason (the desert-in-band audit) and is a fast, per-merge guard, not a slow one — so the
classes moved to the audit tier are named explicitly in each module's `build.gradle.kts` rather
than matched by a suffix that would also catch it.

`DepositionTest` also lost its absolute elevation-checksum pin in T1: it had been re-recorded nine
times in two days as unrelated terrain changes moved it, proving nothing beyond "this is whatever
the code currently produces". Its land-count assertion, its structural cases (mass conservation,
deltas gaining land) and the off-equals-on-at-zero-rates identity are unchanged and remain the guard.

## Continuous integration

`.github/workflows/ci.yml` runs the engine's tests on both the JVM and WebAssembly, and compiles
and tests the desktop app, on every push and pull request — the per-merge tier described above.
Both also run on Wasm, where the shared suite (`:worldgen:wasmJsNodeTest`,
`:cartography:wasmJsNodeTest`) is a subset of the JVM one, since JVM-only tests such as
`DebugMapDump` render through `java.awt`.

The step worth knowing about compares the **JVM and Wasm fingerprints** to detect platform drift.
A divergence is informational — usually worth a glance to catch a platform-dependent bug — but does
not fail the build, since a save carries the world and platforms may generate differently. T1
stopped this from being a third engine test run: the JVM and Wasm test steps run with `-i` and tee
their console to a log file, and the comparison step reads `WorldFingerprintTest`'s `FINGERPRINT`
lines back out of those logs instead of rerunning either suite with `--rerun-tasks`.

`.github/workflows/nightly.yml` runs `gradlew audit` once a day on a cron schedule — see
**Testing: two tiers** above.

## Multiplatform status

`:worldgen` is a Kotlin Multiplatform module targeting **jvm** (what the desktop app consumes) and
**wasmJs** (the web build). The whole correctness suite lives in `commonTest` and runs on every
target; `DebugMapDump` stays in `jvmTest` because it renders PNGs through `java.awt`.

There used to be a third target, **js**, kept only as a record of why Wasm was chosen: JS routes
`sin`/`cos`/`pow` through JavaScript's `Math`, which differs from the JVM in the last bit, and the
FFT compounds that difference into a measurably different world from the same seed — enough to
fail the resolution-consistency guard, where Wasm matches the JVM bit-for-bit. Measured on
2026-08-23, seed 42 at 128x128: jvm and wasmJs both passed 16/16 with elevation fingerprint
`4283446780793226894`; js passed 15/16 with fingerprint `-2412777715130564537`. Nothing had
consumed the js target since the web build moved to Wasm, so T1 (2026-09-12) removed it.

`WorldFingerprintTest` prints a checksum of a generated world, built from raw float bits so it
catches a difference in the last bit. Run it on jvm and wasmJs and compare to detect
platform-dependent divergence; a difference is informational but not a blocker, since saves carry
the world.
