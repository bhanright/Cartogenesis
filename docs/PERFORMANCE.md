# Performance

What generation, rendering and export actually cost, and why the limits are where they are. The
README states the limits; this file holds the measurements behind them.

## The machine

Every table below was measured on one machine, and each table says when:

**AMD Ryzen 7 5700X (8 cores, 16 threads), 32 GB RAM, NVIDIA GeForce RTX 3070 Ti, Windows 11,
JDK 21.**

Figures moved here from the README on 2026-09-15 keep whatever date they were recorded with. Where
the README carried none, the row says so rather than inventing one; those are still this machine's
numbers, but their date is unknown and they have not been re-measured.

## What each size costs on the desktop

Sizes are named by their rows, on a grid of square cells twice as many across: a 2048 world is
4096 by 2048 cells, 2.9 km a side, and its picture 4096 by 2048 pixels. Machine as above, with the
heap the packaged app takes on a 16 GB machine, 12 GB. Measured 2026-09-28 (the ledger's Q5 row),
seed 42, one size a JVM, the app's work in its order: generate, draw the pane's sheet, export a PNG
at the world's own size, save. Peak live is the largest heap a collection left; resident is the
process's peak working set.

| Size | Generation | Drawn | PNG export | Save | Peak live | Peak resident |
|---|---|---|---|---|---|---|
| 1024, processor | 50.3 s | 0.6 s | 1.4 s | 7.2 s, 137 MB | 1.1 GB | 3.7 GB |
| 1024, acceleration on | 46.7 s | 0.6 s | 1.4 s | 7.2 s, 137 MB | 1.4 GB | 3.9 GB |
| 2048, processor | 210.8 s | 1.8 s | 4.6 s | 27.7 s, 555 MB | 3.0 GB | 4.8 GB |
| 4096, acceleration on | 994.6 s | 19.1 s | 29.8 s | 109.2 s, 2.25 GB | 10.3 GB | 14.0 GB |
| 8192 | not offered | | | | | |

The drawing, export and save figures of the two 1024 runs are one measurement. Acceleration saves
little because the implicit incision, most of erosion's time, has no graphics-card path yet.

Every desktop offers 2048. 4096 finished under the 12 GB heap, but with 10.3 GB of it live and
14.0 GB resident, which leaves a 16 GB machine about 2 GB for everything else, so a desktop offers
it only where its heap is at least 17.1 GB: a machine of 23 GB or more under the packaged app's
three quarters. `WorldCeilings.HEAP_FOR_LARGEST_DESKTOP_BYTES` derives the threshold from these
figures, and `DesktopPlatform` reads the heap when it runs. See **Why 8192 is not offered** below.

## Picture formats: size against fidelity

Machine as above. Measured by `ExportSmokeTest` and `DataExportTest`, which assert these figures so
the interface's descriptions stay true. Date not recorded when measured; moved here from the README
on 2026-09-15.

PNG is lossless. WebP is about a quarter the size, but Skia exposes no lossless WebP encoder, and
the loss lands where a map can least afford it: the average pixel drifts about 4 of 255, which is
invisible, while the worst 0.1% — the river lines and the borders — drift by about 75. That worst
figure grew when rivers were sized by their discharge, because that draws every headwater as a
sub-pixel thread.

JPEG is written at quality 90, through the JDK's own encoder on the desktop and Skia's in the
browser. It exists for programs that will not open a WebP. On seed 42 at 512:

| Format | Size | Mean drift | 99th percentile drift |
|---|---|---|---|
| JPEG, quality 90 | 78 KB | 6.99 | 55 |
| WebP, Skia's maximum | 108 KB | 5.53 | 53 |

The WebP is the more faithful of the two at every percentile as well as the larger. Asking the JPEG
encoder for quality 100 costs 214 KB to reach the WebP's fidelity. So the choice is a smaller,
softer file or a larger, sharper one, and not the ranking the format names suggest.

## What each export costs

Machine as above. Measured 2026-09-29 by `ExportAuditTest` in the audit tier (a 10 GB heap, the
pool at fifteen threads), one world per size, seed 42, on the processor. The raster is the
processor's with the sheet it is laid on, a cell to a pixel; the picture formats are encoded from
that sheet at the world's own size, and the data exports are one sample a cell.

| Export | 1024 (2048 × 1024) | 2048 (4096 × 2048) |
|---|---|---|
| Generation (once, whatever comes out of it) | 51.2 s | 211.4 s |
| Raster and sheet (once, for the three pictures) | 0.6 s | 1.8 s |
| PNG | 2.9 MB, 1.1 s | 10.2 MB, 3.6 s |
| WebP | 0.8 MB, 0.2 s | 2.8 MB, 0.7 s |
| JPEG | 0.5 MB, 0.1 s | 2.0 MB, 0.2 s |
| Heightmap | 2.6 MB + 1.2 KB, 0.5 s | 8.8 MB + 1.2 KB, 1.3 s |
| Biomes | 0.1 MB + 2.0 KB, 0.1 s | 0.2 MB + 2.0 KB, 0.2 s |
| Realms | under 0.1 MB + 2.0 KB, under 0.1 s | 0.1 MB + 2.2 KB, 0.1 s |
| Heap in use after the exports, garbage included | 1.1 GB | 4.1 GB |

Generation is the whole of the wait, for a data export as much as for a picture: at 2048 a
heightmap is a second of encoding behind three and a half minutes of world. 4096 rows was measured
on its own (the table above): its world holds more than the audit tier's heap.

The 16-bit heightmap PNG is the largest file the application writes after the lossless picture:
8.8 MB at 2048 from 16.8 MB of raw samples, so the filtering earns about half. The two index maps
are almost free, because a biome map is large flat regions and that is what deflate is for.

## Why 8192 is not offered

The export row offers 1024, 2048, 4096 and 8192, and the 8192 chip is drawn disabled everywhere.
8192 rows is 16,384 by 8,192 cells, 134 million, four times 4096's. 4096 held 10.3 GB live, so
8192 would want about 41 GB at the same rate, past the 24 GB heap of a 32 GB machine; it has not
been tried on square cells. On the grid as many cells tall as wide, 8192 by 8192, half its cells,
exhausted a 10 GB heap inside the generator after about nineteen minutes, before a pixel was
drawn (date not recorded; moved here from the README on 2026-09-15).

Rendering is not the constraint. The graphics device rasters 8192 by 4096 cells, the 4096 world,
in eight tiles in half a second with no world in memory at all. Raising the ceiling therefore means
generating in tiles or on disk, not building a bigger renderer.

The limit lives in `Platform.generationCeiling`: 2048 on every desktop and 4096 where its heap
holds it, 1024 in any browser, phone or not, for the working resolution and the exports alike. The
browser's figure is its own measurement, not the phone's borrowed: see `docs/TODO.md`, "A 4096
world cannot be made in a browser tab". The same chips cap the data exports as cap the pictures:
the ceiling is a question of how big a world this build can finish, and knows nothing about what
kind of file comes out of it.

## Drawing the map on the graphics device

Machine as above. Measured 2026-09-29 on seed 42 at 2048 rows, the best of three after a warm-up;
the 4096 row on fields invented for the purpose, 2026-09-28 (`GpuRasterTest`, the ledger's Q4 row).

Drawing runs on the graphics card unconditionally, not behind the acceleration toggle, because
rasterising pixels makes no promise about reproducing a world from its seed the way erosion does.

`MapRasterizer`'s per-pixel work — a ramp lookup, a climate-modulated tint, the sky's light and the
ground's horizon, a coast, a contour and a border test — runs as one GPU compute dispatch per export
tile (`GpuRaster`, behind the `RasterAccelerator` seam in `:cartography`):

| Raster | Graphics device | Processor |
|---|---|---|
| 2048 (4096 × 2048) | 0.30 s | 1.12 s |
| 2048, single-lamp relief | 0.29 s | 0.57 s |
| 4096 (8192 × 4096), in eight tiles | 0.47 s | — |

The sky model is what widened the gap: it asks the terrain twenty-four more questions per land pixel
than a single lamp does, which doubles the processor's raster and costs the device nothing it
notices.

The shader is handed a `RasterRecipe` — every color already packed, and the two per-cell numbers
the climate has to say about the ground — so neither the palette nor the aridity index is computed
twice. `GpuRasterTest` holds the two paths within one channel step of 255 at the 99.9th percentile,
across all fifteen views and twelve styles.

None of this is the bottleneck it looks like: a 2048 export spends three and a half minutes
generating the world and about a second drawing it.

## Where generation time goes, and why erosion has most of it

Machine as above. Measured 2026-09-29 by `StageProfileTest` in the audit tier, seed 42, on the
processor with the pool at fifteen threads, a stage timed from its own start to the next one's.

| Stage | 512 | 1024 | 2048 | 2048, share |
|---|---|---|---|---|
| terrain (noise + FFT) | 0.1 s | 0.3 s | 0.9 s | 0.5% |
| plate tectonics | 0.7 s | 2.3 s | 8.1 s | 4.1% |
| erosion | 5.8 s | 25.4 s | 124.5 s | 63.8% |
| sea level | 3.0 s | 10.2 s | 44.9 s | 23.0% |
| ocean currents | 2.1 s | 1.8 s | 3.3 s | 1.7% |
| climate | 0.3 s | 1.4 s | 6.0 s | 3.1% |
| rivers | 0.2 s | 0.5 s | 2.2 s | 1.1% |
| realms | 0.4 s | 0.8 s | 3.1 s | 1.6% |
| peoples | 0.1 s | 0.3 s | 1.0 s | 0.5% |
| landmarks | 0.1 s | 0.3 s | 1.3 s | 0.7% |
| total | 12.9 s | 43.3 s | 195.2 s | |

**Erosion takes 63.8% of a 2048 generation.** The table this replaces, of 2026-09-15, measured 70.9%
on 2048 by 2048 cells, before the implicit incision and square cells.

Each doubling of the rows is four times the cells, and erosion's time grew 4.3 times from 512 to
1024 and 4.9 times from 1024 to 2048, more than the cells; what the rest is spent on is not measured
here. Tiles of the thermal sweeps that have gone quiet
are skipped, and the skip is exact — `ErosionSkipTest` asserts bit-identical output — but it buys
only around 1.3x, because roughness at cell scale rises with resolution and most of a fine grid is
genuinely still moving.

## What the accelerator buys

Machine as above (RTX 3070 Ti). Date not recorded when measured; moved here from the README on
2026-09-15.

Erosion is a pure stencil over independent cells, which is what makes it worth running on a graphics
device. The ocean's circulation and its heat are relaxed there too, because the gyres set the sea
temperature, which sets the climate, though today that path is slower than the processor (below).
Realm expansion is a Dijkstra over a priority queue and would not suit a GPU regardless.

| Path | Erosion sweeps | Against |
|---|---|---|
| Fifteen CPU threads | 1.3 s | — |
| OpenGL compute, desktop | 22 ms | around 55x |
| WGSL, browser | — | about 70x |

Re-measured on square cells, 2026-09-28 (the ledger's Q4 row): on the gallery's world at 512 rows
the desktop card ran the sweeps 9.9 times faster than the processor, the worst cell 1.5e-5 of the
range from the processor's. The sweeps are now a small part of erosion, whose implicit incision has
no card path, which is why a whole 1024 world is 46.7 s with acceleration against 50.3 s without.

Both accelerated paths agree with the processor to about seven parts in a million. They are not
bit-identical: graphics hardware fuses multiplies and adds in whatever order it likes, and "has not
differed yet" is not a guarantee that it never will. So a world generated with acceleration stores
its terrain in the save (`TerrainSnapshot`) rather than relying on regeneration; a world generated
on the processor stores nothing extra, because for it the seed really is enough. The browser path
can be checked on any machine by loading the web build with `?selftest` in the URL.

## The ocean's circulation and its heat

Machine as above. Measured 2026-09-26 on seed 42 by chunk 4a's probes and by `GpuOceanTest`; every
world above 512 built as the app built it then, as many cells tall as wide, so a column's size is
that grid's side. The whole ocean stage, with the heat carried to the map:

| Path | 512 | 1024 | 2048 |
|---|---|---|---|
| Processor, all threads | 2.8 s | — | 2.7 s |
| Processor, one thread | 3.3 s | 3.8 s | — |
| Wasm in Node, one thread | 10.5 s | 12.0 s | — |
| OpenGL device, desktop | 6.7 s | — | 6.6 s |
| Before 4a, one thread | 0.45 s | 1.24 s | — |
| Before 4a, Wasm | 0.88 s | 2.83 s | — |

Re-measured 2026-09-28 on seed 42 after chunk 4b-1 (the belts' meridional leg, Ekman upwelling in
the heat, the circulation's residual taken in double precision), by that chunk's probes and
`GpuOceanTest`, same machine, a test worker holding two threads:

| Path | 512 | 1024 | 2048 |
|---|---|---|---|
| Processor, two threads | 1.0 to 1.9 s | 1.6 s | 1.7 to 1.9 s |
| Processor, one thread | 1.9 to 2.9 s | 2.1 to 2.8 s | — |
| Wasm in Node, one thread | 4.8 s | — | — |
| OpenGL device, desktop | 3.1 to 3.3 s | — | 3.0 to 3.5 s |

After the chunk's last corrections the whole stage, on seed 42 with two threads, is 1.2 to 2.8 s at
512, 1.7 to 1.9 s at 1024 and 1.9 to 3.0 s at 2048, and on the device 3.4 to 3.8 s at 512 and 3.4
to 3.5 s at 2048, agreeing with the processor to the bit. Of that, the shelf's reach, now measured
through water by fast marching over the map (`FastMarchingDistance`), is 62 to 96 ms at 512, 70 to
72 ms at 1024 and 110 to 117 ms at 2048, 3.0 to 4.1% of the stage: one pass over the map, which
stays on the processor.

On square cells (2026-09-29, `StageProfileTest` above, fifteen threads) the stage read 2.1 s at
512 rows, 1.8 s at 1024 and 3.3 s at 2048: still flat against the map, since the solve grid is the
planet's.

The stage got faster rather than slower: the heat's Krylov solve now converges in two to four
iterations where 4a's took fourteen, and the circulation's multigrid in seven to nine V-cycles. The
rising water adds `w/h` to each rising cell's margin, which may be why; that is measured, not
analyzed. The Ekman divergence and the equatorial
thermocline are a pass or two over the solve grid, too small to read in these figures. The device is
still slower than the processor for the reason below.

**The cost hardly moves with the map, because the solve grid does not.** The circulation and the
heat are solved on a grid sized by the planet's physics, `4πΩ/r` rows rounded to 960 by 1,920,
which holds Stommel's boundary layer across two cells at its narrowest and is the same for every
map size (`OceanStage.solveGrid`); only the carry to the map grows with it. Most of the time is the
heat's Krylov solve (fourteen iterations of a multigrid-preconditioned BiCGSTAB), not the
circulation's multigrid (eight V-cycles).

**The device is slower than the processor.** It relaxes each batch of passes on the card and hands
the grid back, and the multigrid's other steps stay on the processor between batches; grids under
2^16 cells are declined as not worth the trip (`GpuOcean`). The two agree to the bit. Keeping the
whole solve resident on the card is recorded in `TODO.md`.

**The browser pays about ten seconds for it.** A 512 world in a tab spends about 10.5 s of one
thread on the ocean where it spent under one. The desktop is what the generator is designed for
and a browser runs a cut-down trial of it, allowed 40 to 50 s for a whole generation in the
slowest browsers; the stage stays inside that. The stage-by-stage
table above predates 4a: its ocean row, 0.6 s at 2048, is the Poisson solve 4a replaced.

## The browser's one thread

Machine as above. Measured 2026-09-28 (the ledger's Q5 row) in a tab of the production build, Chrome
152, acceleration off, the JavaScript heap sampled every 250 ms.

The browser starts at a generation resolution of 512 and the desktop at 1024. That is a platform
decision rather than a preference: a tab has one thread, and `Dispatchers.Default` there is that
same thread, so generating stops the page answering rather than merely taking longer.

| Size | Generated | Generated and drawn | Tab heap at most |
|---|---|---|---|
| 512 (1024 × 512) | 36.2 s | 39.7 s | 0.9 GB |
| 1024 (2048 × 1024) | — | 171.6 s | 1.7 GB |

The browser stops at 1024: 2048 is four times its cells, and at the measured heap a cell it would
want about 6.8 GB, past the 4 GB a Wasm heap can address. A phone has not been timed on square
cells; `LargeLinks.MEASURED` states its 1024 as about four minutes, an estimate from the tab with a
phone's core taken as up to half again slower, and `docs/TODO.md` asks for a phone's figure.

Because the page's one thread is the generator's, the interface is handed a frame before the first
stage starts and again at every stage boundary, so the ten stage names appear as work proceeds.
Without that the page simply stops, which reads as a crash.

## Test tiers

The slow measurements above are in the audit tier, not the per-merge tier. `./gradlew audit` runs
`StageProfileTest`, `GenerationSpeedTest`, `ExportAuditTest` and the rest, and
`.github/workflows/nightly.yml` runs it once a day. Splitting them out took `:worldgen`'s per-merge
run from about 23 minutes to 6, and `:desktop`'s from about 5 to 2.

By 2026-09-23 the per-merge tier had grown back to 1 hour 25 minutes, and T5 took it to 22 minutes
on the same machine: the four JVM suites in parallel workers, each worker's thread pool a share of
the processor (the root `build.gradle.kts` holds the budget), the standard worlds generated once a
worker and lent through `SharedWorlds` instead of once a class, and every class that asserts
nothing moved to the audit tier. The end of every run that tests prints each task's wall time and
the slowest classes. The ledger's T5 row has the measurements.

Q2's square cells gave every standard world twice the cells and took `:worldgen`'s run to between
52 and 71 minutes. Q2b builds the worlds whose figures do not depend on the grid's detail at 256
rows (`SharedWorlds.COARSE_ROWS`), which took 23 minutes out of the classes and almost none out
of the wall: the run lasted as long as the worker that drew `GlaciationTest`, 21.6 minutes on its
own, which is now three classes so that its worlds can fall to three workers. The ledger's Q2b row
has the measurements, and `docs/TODO.md` what is left.

To re-measure the stage profile alone, on a 2048 world among others:

```bash
./gradlew :worldgen:audit --tests '*StageProfileTest*' --rerun
```
