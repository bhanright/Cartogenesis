# Realism plan

*Drawn up 2026-09-11, revised the same day. A save format that stores the world, then nine
improvements to the geography and climate, implemented by subagents in chunks that each leave
`main` green and pushed. The ledger at the bottom is the source of truth for progress; a fresh
session starts there, not from any conversation.*

---

## Ground rules, for every chunk

These are the habits that have found every substantive bug in this project. They are not optional.

1. **Render and look.** `DebugMapDump` writes PNGs to `worldgen/build/maps/`. Every chunk that
   changes generation renders seeds 7, 42 and 1234 before and after and the report says what
   changed to the eye. Climate bugs are invisible to unit tests: the desert-at-the-equator bug
   passed every test that existed.
2. **A guard must be shown to fail without the fix.** Write the test, run it against the old code
   (revert the change or flip a flag), see it fail, then see it pass. A guard that has only ever
   been green proves nothing; three of this project's guards passed vacuously before anyone checked.
3. **Cross-platform identity is no longer a gate.** Once Track D lands, a save carries the world
   itself, so a JVM world and a Wasm world from the same seed no longer need to match bit for
   bit, and nobody spends an afternoon proving that they do. CI keeps the JVM-versus-Wasm
   fingerprint comparison as an *informational* job — a divergence is still worth a glance,
   because it usually means a platform-dependent bug rather than a harmless difference — but it
   does not fail the build and no chunk waits on it. Until D3 flips that switch, treat a red
   fingerprint job as a warning, not a blocker.
4. **New config sections must be declared to the reuse chain.** If a stage reads a new section of
   `WorldGenConfig`, add it to that stage's guard in `WorldGenerationEngine` and to the variant
   list in `IncrementalReuseTest`, which compares reuse against fresh generation for every section.
5. **Measure, do not tune.** Report the number before and after. Never move a threshold to make a
   guard green; if a guard cannot discriminate, say so and fall back to the render. The bars
   themselves are not sacred, though (William, 2026-09-12): they were set by earlier sessions
   from whatever the generator then produced, and the goal is realism. When a guard fails because
   of a real physical change rather than a defect, the question is what Earth measures; the bar
   moves to that figure, the derivation is written beside the assertion, and the ledger says so.
   The first case was desert-in-band: Earth keeps roughly 85-88% of its desert within 15-45
   degrees, so the bar is held on the seeds pooled at 85% with a 75% floor per seed.
6. **Windows file locks, and never `gradlew --stop`.** Gradle on this machine locks `build/`
   subdirectories between runs. The cure is to delete the affected module's `build` directory
   *inside your own worktree* (`Remove-Item -LiteralPath <module>uild -Recurse -Force`, in a
   separate command from any that mentions the JDK path — the sandbox misreads the two together)
   and rerun. Run every Gradle command with `--no-daemon`, so your build lives in its own JVM.
   Do **not** run `gradlew --stop`: it kills every Gradle daemon on the machine, including a
   parallel agent's build in progress. On 2026-09-11 two agents each followed the old version of
   this rule and spent a quarter of an hour killing each other's builds — five daemons ended
   "stop command received". If a build dies with "daemon disappeared" or "build cancelled", it was
   stopped from outside; rerun it.
7. **Reports carry numbers.** A subagent's final report says what changed, the before/after
   figures its guard measured, what the render showed, and anything it could not verify. The
   orchestrator decides from the report; the diff is there if the report raises a question.

## Session protocol

Each session, on any model:

1. Read the **Ledger** below. Take every unchecked chunk whose dependencies are checked.
2. Dispatch a subagent per chunk, each with its spec pasted verbatim plus the ground rules.
   Chunks with no dependency between them run **in parallel** — Track A and Track B are
   independent of each other throughout, and each subagent works in its own git worktree so
   parallel chunks cannot tread on one another's files. Chunks within a track run in their
   dependency order.
3. When a report comes back green: merge the worktree, update the ledger entry (date, numbers,
   commit hash), commit with the chunk's name in the subject, push, wait for CI green. Two
   chunks that both touch `WorldGenConfig` or `ClimateStage` will conflict at merge; the
   orchestrator resolves that, which is the main reason it reads reports rather than diffs.
4. If a session dies mid-chunk, the next session discards that worktree and restarts the chunk.
   Nothing in a chunk depends on a previous session's memory.
5. **Push before dispatching.** Agent worktrees branch from the last *pushed* commit, not from the
   local `main`. On 2026-09-11 three chunks were dispatched after local merges but before a push,
   and each started without the work it was told to build on: A6 lacked the test it was to extend,
   D2 checked in a save fixture written without A1's sections, and D3 was diffed against a stale
   base. If a dispatch must go out before a push, the agent's first instruction is `git merge
   main` in its worktree.

Model per chunk is given below. Rule of thumb: Opus where the algorithm is the work; Sonnet where
the spec is precise and the test is clear; Haiku for docs, renders and tallies.

## Track D — saves that carry the world

*Goes first. Every chunk in Tracks A and B changes what a seed generates; once a save stores the
world rather than the recipe for it, that stops mattering, and so does whether two platforms
cook the recipe identically.*

Today a save is the seed, the settings and the user's overrides, and the world is rebuilt from
them on open. That is why the JVM and Wasm builds have to generate bit-identical worlds, why CI
compares their fingerprints, and why a large share of every pipeline change was spent proving
they still agree. The GPU toggle already broke the rule and was patched around it: a GPU world
stores its eroded terrain in the save and replays it through the `ErosionAccelerator` seam.

The general form of that patch is the new save. A save stores the finished `WorldMap` — every
stage's result — and opening it is deserialisation. The engine already knows how to reuse a
stored stage: `WorldGenerationEngine.generate(config, previous = stored)` skips every stage whose
settings match, so a save opened with its own config regenerates nothing, and a save whose
settings are then edited recomputes only what lies downstream — which is exactly how live editing
works now. `StoredTerrain` becomes a special case of this and goes away.

### D1. Full-world save format — Opus

*Dependencies: none.*

- A save is a container: a JSON header (format version, config, overrides, labels, title,
  resolution, which platform and version wrote it) followed by binary sections, one per stage
  result. `WorldCodec` in `:cartography` owns the layout; it is common code, so both front ends
  write and read the same bytes.
- Binary, not JSON, for the per-cell arrays: a 1024x1024 world is tens of megabytes of floats,
  and JSON triples that. Little-endian `float32` for heights and fields that must round-trip
  exactly; `int32` for cell ids; a byte per cell for biomes. Lists (rivers, lakes, nations,
  cultures, landmarks) stay JSON in the header — they are small.
- Compression is a platform seam on `Platform`, because the JVM has `java.util.zip` and the
  browser has `CompressionStream`, and common code has neither. Gzip both sides; a platform that
  cannot compress stores raw and says so in the header. Measure the ratio on seeds 7, 42 and
  1234 at 512 and 1024 and record it — the height fields are noise-like and will not compress as
  well as the id maps.
- `WorldCodec.FORMAT_VERSION` finally gets read: bump it, and keep loading version 2 (seed-only)
  saves by regenerating on open as today. Such a save becomes a full save the next time it is
  saved. Nothing the author already has is lost, and nothing has to be migrated by hand.
- Loading goes through the reuse chain: build a `WorldMap` from the sections and pass it as
  `previous` with the saved config. `IncrementalReuseTest` already proves that a matching config
  reuses every stage; add a case that a loaded save reuses all of them and generates nothing.
- Guard: `WorldCodecTest` round-trips a generated world through bytes and asserts every
  per-cell array is identical (not close — identical) and every list is equal. Show it fails when
  a section is dropped from the writer. Add a second case: a version-2 save still opens.
- The GPU toggle's warning about saves not replicating on other machines comes out of the UI;
  the reason for it no longer exists.

### D2. Web storage — Sonnet

*Dependencies: D1.*

The browser library is `localStorage`, which caps at a few megabytes per origin and cannot hold a
full-world save. Move the web `WorldLibrary` to IndexedDB, keyed by document id, with the listing
reading only headers so the library pane stays fast. Add download and upload of the container as
a file, so a browser save can be moved to the desktop and back — the format is identical, which is
the point of it living in `:cartography`. Keep `localStorage` migration: existing seed-only
entries are read once, re-saved into IndexedDB, and removed.

- Guard: the web self-test (`?selftest`) gains a round-trip — save the current world to
  IndexedDB, reload the page, open it, assert the height field is byte-identical. Report the
  save size and time in the console line, next to the erosion figures.
- Check the library listing against a 1024 save: it must not deserialise the arrays to show a
  title and a date.

### D3. Retire the determinism gate — Haiku

*Dependencies: D1, D2.*

- `ci.yml`: the JVM-versus-Wasm fingerprint step stops failing the build (`continue-on-error`)
  and prints its diff as a warning annotation instead of an error. Keep the step; it is a free
  platform-bug detector.
- `README.md`, `TODO.md`, GEOGRAPHY.md, the site's `CLAUDE.md` and the memory note in the
  Cartogenesis multiplatform reference: replace every statement that saves depend on identical
  generation with the new contract — a save carries its world; platforms may differ.
- The `RealmSpreadTest`/`CultureRealmTest` comments that justify sorted neighbour lists by the
  Wasm split can stay as history; the sort itself stays, because platform-independent output is
  still cheaper to reason about even when it is no longer required.

---

### D4. Forward-compatible sections — Sonnet

*Dependencies: D1, D2. Opened by the A1/D2 merge.*

The reader refuses a container that is missing a section. That is correct for a corrupt file and
wrong for an old one: A1 added four climate sections, and every version-3 save written before it —
including D2's checked-in gzip fixture — became unopenable, on both platforms. Every future chunk
that adds a per-cell field will do the same to every save written before it, which after a release
means the author's own worlds.

The engine already knows how to cope: a stage whose result is absent is simply not reusable. So a
missing section should mean "regenerate this stage and everything downstream", not "refuse".

- `WorldSections`/`WorldCodec`: sections are grouped by stage. If any section of a stage is
  missing, that stage's result is `null` in the loaded `WorldMap` (or the partial `WorldMap` passed
  as `previous` omits it), and `WorldGenerationEngine.generate(config, previous)` regenerates it
  and everything after. A corrupt section — wrong length, bad magic — still throws. The header
  records which stages are present so the library pane can say "opens with regeneration".
- Guard: `WorldCodecTest` gains a case that writes a container, strips the climate sections, and
  asserts it opens, that climate and everything downstream regenerated (not `assertSame`), and
  that terrain through rivers-independent stages were reused (`assertSame`). Show it fails on the
  refusing reader, then passes. The gzip interoperability fixture stays as written by D2's fix and
  must keep passing.
- Bump nothing: this is a reader-side relaxation of version 3.

## Track A — climate

### A0. Reconcile GEOGRAPHY.md — Haiku

*Dependencies: none. Independent of everything; do first.*

`GEOGRAPHY.md` "Known deviations" still says there is no erosion, no ocean currents, and that
desert placement is loose. All three were fixed on 2026-08-23/24 and the file's own later section
records desert at 99-100% in band. Rewrite "Known deviations" to what is actually still wrong: no
seasons, no continentality, zonal-only wind, per-world rainfall normalisation, no deposition, no
shelves, no crust-pair boundary types, no glaciation. Each of those is a chunk below; link them.
Guard: none — it is prose. Render: none.

### A1. Seasons — Opus

*Dependencies: A0. This is the keystone; A2 and A3 depend on it.*

Replace the single annual temperature and rainfall with two seasonal fields, and derive biomes
from four numbers instead of two.

- `ClimateConfig` gains `seasonalTilt: Float = 10f` (degrees the thermal equator migrates) and
  `seasons: Boolean = true`. Declare the section to the reuse chain (rule 4).
- `buildTemperature` runs for July and January by shifting the latitude used for `latFactor` by
  `±seasonalTilt`, with the polar/equator span unchanged. Keep the annual mean as
  `temperature`; add `summerTemperature` and `winterTemperature` to `ClimateResult`.
- `buildPrecipitation` runs twice with the wind bands and `latitudeBand` shifted by the same
  tilt; add `summerPrecipitation` and `winterPrecipitation`. `precipitation` stays as their mean
  so every downstream stage and every existing guard keeps working unchanged.
- `classify` takes the four fields. Minimum change that earns the work: a Mediterranean class
  (warm, wet winter, dry summer) and a monsoon-forest class (warm, dry winter, very wet summer),
  and savanna defined by seasonal contrast rather than by annual total alone. Add the two biomes
  to `Biome`, `MapPalette`, and the styles' `biomeWash` tables.
- Guard: `SeasonsTest` asserts (i) at 35° the summer/winter temperature gap exceeds 8°C on land
  and is under 4°C over open ocean; (ii) on at least two of three seeds a Mediterranean band exists
  on a west-facing coast between 30° and 45°. Show (ii) fails with `seasons = false`.
- Render both seasons' precipitation and the new biome map. Expect the desert belt to migrate and
  broaden on its summer side.
- Cost: the largest chunk. Expect one session. If it must be split, the split is "fields first,
  biomes second": land the four fields with `classify` unchanged, commit, then do biomes.

### A2. Continentality — Sonnet

*Dependencies: A1.*

Seasonal amplitude scales with distance from water. `applyMaritimeInfluence` already computes a
blurred water-exposure field; reuse it. Amplitude multiplier `1 + continentality * (1 - exposure)`
applied to the seasonal departure from the annual mean, with `ClimateConfig.continentality: Float
= 0.6f`. Interior at 50° should swing markedly more than a coast at 50°.

- Guard: on seed 42, mean seasonal range of land cells more than `coastalReach * 3` from water
  exceeds that of cells within `coastalReach` by at least 6°C. Show it fails with
  `continentality = 0`.
- Render summer and winter temperature. Look for Siberia-versus-Ireland.

### A3. Meridional wind and the monsoon — Opus

*Dependencies: A1.*

`buildWind` returns ±1 per row. Give it a meridional component: trades blow toward the equator,
westerlies toward the pole, magnitude `0.3`, so the march runs diagonally. With A1's seasonal
band shift this is the monsoon: in summer the ITCZ sits over a tropical landmass and pulls moist
ocean air onto it. The march in `buildPrecipitation` becomes a per-cell advection along the wind
vector rather than a per-row scan; keep the two-lap wrap.

- Guard: on a seed with a large tropical landmass with sea to its equatorward side (find one by
  rendering; record the seed in the test), summer rainfall on that coast exceeds winter rainfall
  by 3x over a contiguous region of at least 2% of land. Show it fails with the meridional
  component set to 0.
- Render summer and winter rainfall. This is the chunk most likely to look wrong first time;
  budget for one revision.

### A4. Absolute rainfall — Sonnet

*Dependencies: A1, A3.*

`normalizeByLandPercentile` scales every world so its 88th land percentile is 1.0, so an arid
world and a lush one classify identically and every world gets ~4.6% desert. Calibrate the march
to approximate mm/year (a scale factor chosen so seed 42's wettest windward coast lands near
3000 mm and its subtropical desert core under 250 mm) and move `classify` to absolute thresholds.
Keep the normalised field available for rendering the rainfall view.

- This intentionally lets worlds differ. `GeographyAuditTest`'s desert-in-band guard should still
  hold; its desert *share* will now vary by seed — report the range, do not pin it.
- Guard: generate seeds 7, 42, 1234, 99; assert desert share differs across them by at least a
  factor of 1.5 between the driest and wettest world. Show it fails under normalisation (it will:
  the shares are near-identical by construction).

### A5. Cold-air moisture cap — Haiku, report only

*Dependencies: A1.*

`coldCap` clamps moisture by temperature and may make all cold regions uniformly dry, starving
temperate rainforest on high-latitude west coasts (the Bergen case). Render seeds 7, 42, 1234;
report the share of 50-60° west coasts classed rainforest or temperate forest. No code change in
this chunk — if the number is low, open a follow-up in the ledger with the figure.

### A6. Temperate climates by coldest month — Sonnet

*Dependencies: A1, A5. Opened by A5's measurement.*

A5 found that 0% of west-facing coasts at 50-60° classify as forest on any seed, despite carrying
1.9-3.8x their latitude's mean rainfall. The rain is there; the cold cap is not the cause. The
cause is that `classify` gates temperate against taiga on the *annual mean* (`t < 7 → TAIGA`),
and the latitude curve puts 55° near 0 °C, so even a +2 °C warm-current anomaly cannot lift a
mild-winter maritime coast into temperate forest. Bergen is temperate at an 8 °C mean because its
coldest month is about 2 °C.

- Classify the temperate / continental / polar boundary Köppen-style on the seasonal fields A1
  added: coldest month above -3 °C and warmest above 10 °C is temperate (C); warmest above 10 °C
  with coldest at or below -3 °C is continental, which is where taiga lives (D); warmest below
  10 °C is tundra (ET). Keep the moisture axis as it is; only the thermal gate changes.
- Check the latitude curve itself against reality at 45-60°: London is 11 °C at 51°, Bergen 8 °C
  at 60°, Winnipeg 3 °C at 50° (continental). If the curve runs several degrees cold across that
  band, adjust the exponent or the pole value and re-verify `SeasonsTest` and the desert audit -
  but change the curve only if the Köppen gate alone does not fix the coasts, and say which.
- Guard: extend `ColdCapReportTest` into an assertion - on at least two of three seeds, the share
  of 50-60° west-facing coast cells with a positive current anomaly that classify as temperate
  forest or temperate rainforest exceeds 50%. Show it fails on the pre-A6 classifier (0%), then
  passes. Report the figure per seed, and the taiga/tundra shares of *interior* cells at the same
  latitudes, which must not collapse - Siberia stays taiga.
- Render biomes for seeds 7, 42, 1234: forested Norway-type coasts, taiga inland, tundra beyond.

## Track B — terrain

Track B is independent of Track A. Interleave: if a climate chunk stalls, land a terrain chunk.

### B1. Continental shelves — Sonnet

*Dependencies: none.*

Sea level is a percentile cut through one height field, so the sea floor drops straight off the
coast. Give oceanic crust a distinct hypsometric mode: in `PlateStage`, cells on oceanic plates
get a base depression proportional to `oceanicFraction`'s plate assignment, blurred at the plate
edge over a shelf width `TectonicsConfig.shelfWidth`. The visible result is shallow seas along
continental margins, more islands, and archipelagos where the shelf is broad.

- Guard: after sea level, the share of ocean cells with `relativeElevation > -0.12` (the existing
  `SHALLOW_OCEAN` cut) within `shelfWidth` of a coast exceeds 40%; beyond `2 * shelfWidth` it is
  under 10%. Show it fails with `shelfWidth = 0`.
- Render elevation and the fantasy view. Check the ocean view does not become a flat plane.
- Erosion runs before sea level: check `ErosionSkipTest` and the resolution guard still pass.

### B2. Crust-pair boundary types — Opus

*Dependencies: B1 (needs the oceanic/continental distinction to be real).*

`BoundaryType` is convergent/divergent/transform. Classify convergent boundaries by the pair of
crusts: oceanic-continental gives a narrow coastal range with a volcanic arc inland of the trench
(Andes); continental-continental gives a broad high plateau (Tibet); oceanic-oceanic gives an
island arc. Divergent under continental crust is a rift valley with shoulders. Reuse the existing
belt profile with different width/height/asymmetry per pair; add a hotspot chain as a cheap
bonus: a few plates carry a fixed point that leaves a decaying line of seamounts along their
drift vector.

- Guard: on seeds where each pair type occurs (find and record them), the ratio of belt width to
  belt height differs between oceanic-continental and continental-continental by at least 2x.
  Show it fails when all convergent boundaries use one profile.
- Render plates and elevation. The Andes-versus-Tibet difference should be visible without labels.

### B3. Deposition — Opus

*Dependencies: none (but do after B1 so deltas build on a shelf).*

`HydraulicErosion` removes material and never puts it back. Deposit a fraction of incised
material where slope drops below a threshold, at lake inflows, and at the sea: floodplains along
lower trunks, alluvial fans at range fronts, deltas at mouths. Mass is conserved per round.
Keep erosion GPU parity in mind: the OpenGL and WGSL kernels implement thermal erosion only, so
deposition lives in the CPU hydraulic pass and `ErosionAccelerator` is untouched.

- Guard: total material removed by incision per round equals material deposited plus material
  lost to the sea, within 1%; and on seed 42 at least three river mouths gain land within 4 cells
  of the old coastline. Show the second fails with deposition off.
- Render fantasy view around the largest river mouths. Deltas should be visible.
- Re-run `IncrementalReuseTest`; erosion's guard already includes `seaLevel`.

### B4. Glaciation — Opus

*Dependencies: A1 (needs winter temperature), B3 (moraines are deposition).*

Where mean annual temperature is below freezing at elevation, carve: U-shaped valleys along
existing trunks (widen and flatten the valley floor), cirques at heads, and over-deepened basins
that become lakes behind moraine dams. Fjords are drowned glacial valleys and fall out of B1 plus
this. Bounded to cells the `ICE_SHEET`/`TUNDRA` classification already marks.

- Guard: on seed 42, lakes per unit area in the glaciated zone exceed the unglaciated temperate
  zone by at least 3x. Show it fails with glaciation off.
- Render the high-latitude coasts. Look for the Norway/Chile coastline.

## Track C — closing

### C1. Docs and release — Haiku

*Dependencies: all of A and B.*

README pipeline description, GEOGRAPHY.md "Held by construction", TODO.md, the atlas copy that
describes biomes. Bump `cartogenesisVersion`, rebuild the three artefacts, cut the release, deploy
the web build with the site's script, update the site's `CLAUDE.md`. Every step of that is already
documented in `README.md` under Deploying and in the site repo's `CLAUDE.md`.

---

## Track E — lakes sized by physics, not by basins

*Added 2026-09-11 evening after 1.1.2. The river stage fills every closed depression to its spill
level in every climate, and the hydraulic pass routes on that filled surface so it never touches
the lip. At 2048 the largest lake on a typical world is a filled tectonic basin at 0.12% of the
map, bigger than the Caspian's share of Earth. Real basins are drained by outlet incision in wet
country and held far below the rim by evaporation in dry country.*

### E1. Outlet incision — Opus

*Dependencies: B3. Files: `HydraulicErosion.kt`, `ErosionStage.kt`, `FlowRouting.kt` if a basin
walk is needed, `ErosionConfig`, tests.*

- Each hydraulic round already fills depressions to route. For each filled basin, find its spill
  cell (the rim cell through which the filled surface exits). Treat it as a knickpoint: lower the
  spill cell and the channel below it by an amount set by the stream power of the outflow (the
  discharge through the outlet is the basin's whole catchment; slope is the downstream channel's),
  never below the basin's true floor at that point, so the notch deepens toward grade and the next
  round's fill is shallower. Material removed goes into B3's sediment load, so the mass budget
  stays exact.
- Glacial basins are untouched by construction: glaciation runs after erosion, inside the sea-level
  step. Only tectonic basins drain.
- Guard: on seed 718106 at 512 with `seaLevel = 0.62`, and on seeds 7/42/1234, the largest filled
  basin's fill depth at its spill falls monotonically across rounds and the largest lake's area
  falls by at least half against `outletIncision = false`; shown failing with it off. Lakes must
  still exist (count > 0) and `RiverEndingsTest`/`DepositionTest` must hold (the budget to 0.0000%).
- Render and look: seed 718106 at 1024 and 2048 with the author's settings (ocean 62%, 14 plates,
  12 realms), through `MapRasterizer`: the big basins should become river valleys or small lakes
  with a river leaving through a notch; nothing else should move.

**E1 addition (2026-09-11, evening): deltas strand their rivers.** On seed 59758 at 2048 (ocean
62%, 14 plates, 12 realms) William saw a river dead-end short of the sea. Measured: every chain ends
at a sea cell, but in 42 of 152 mouths that cell is a one-or-two-cell pocket of sea enclosed by
land, and 44 more touch the sea by one cell. B3's delta grows breadth-first around the old mouth,
raises those cells to land, leaves the mouth below sea level, and cuts no channel across the lobe to
the new shore; the lobe is exactly flat and nearly rectangular. Folded into E1 because it is the
same code: the trunk must continue across its delta to the open sea along a low distributary path,
the old pocket fills, the lobe slopes seaward with an irregular outline. Guard: zero enclosed-pocket
mouths at 2048 on 59758 and at 512 on 42, shown failing on the current code.

### E2. Lake water balance — Opus

*Dependencies: A4 (rainfall in mm). Files: `RiverStage.kt` lake step, `LakesConfig`, tests.*

- After depression filling gives each basin its spill level, set the lake surface where inflow
  equals evaporation times lake area, capped at the spill. Inflow is the catchment's runoff from
  `precipitationMm` (a runoff fraction of rainfall, not all of it). Evaporation is a potential rate
  in mm per year from the seasonal temperature fields (a simple published curve; state which and
  calibrate so hot deserts sit near 2000 mm and cool temperate near 500). Area at a level is the
  basin's hypsometry; solve by bisection over the basin's cells.
- A lake below its spill is endorheic: no outlet river, rivers end in it, and if the balance level
  is under `minDepth` there is no lake at all, only a playa (record the playa cells so a later
  chunk can draw salt flats). A lake at its spill overflows as today.
- Guard: find a seed with a large basin in dry country (search seeds; report which) and one in wet
  country: the dry basin's lake area at balance is under 30% of its spill-level area, the wet one
  sits at spill; shown failing with `waterBalance = false`. Rivers still reach water or the sea.
- Render and look: the dry-basin seed and seed 718106 at 1024; deserts should show small lakes or
  none inside large basins, wet country unchanged.

### E3. Round hotspot cones — Sonnet

*Dependencies: B2. Files: `PlateStage.stampHotspotChains`, tests.*

- The cone profile measures distance from the vent with the chamfer transform, which knows eight
  directions, so every volcano is an eight-sided pyramid; visible at 2048 on seed 718106 at 62%
  ocean where the chain surfaces on land. Stamp the cone with true Euclidean distance (the stamp is
  a few cells across; cost is nothing) and modulate the rim with seeded noise so no two cones are
  the same. Guard: the radius of the cone's half-height contour measured at sixteen bearings has a
  coefficient of variation under 0.05 for the eight-fold component; shown failing on the current
  code. Render the chain at 2048.

**Crash investigation (2026-09-12, after E1 merged).** One full-suite run on 8dcac66 threw
`ArrayIndexOutOfBoundsException: Index 2048 out of bounds for length 14` in `NationStage.describe`
generating seed 718106 at 2048 with the author's settings: a realm id equal to the map width. An
Opus agent could not reproduce it on the same commit across 560 worlds (400 seeds at 512, 150 with
LEAVE_WILDERNESS, the author's config at 1024 and 2048 with each of E1 and E2 toggled off, ten
sea-level jitters at 2048), showed by reading that neither `BasinRealms.assign`'s renumbering nor
`dissolveEnclaves` can widen the id range, and added `checkRealmIds` after both steps (fails fast
naming the step and cell) plus `RealmIdRangeTest` (74a9a23, merge 5b04d01). The orchestrator then
generated the same world three times in one JVM and again inside the full desktop suite: every
per-stage checksum identical, no crash. Not explained. If it recurs the message now names the
step; a longer soak was run before release.

### E4. Segmented rifts — Opus

*Dependencies: B2, E3 (both touch `PlateStage`). Files: `PlateStage.kt` rift profile,
`TectonicsConfig`, tests, `GEOGRAPHY.md`.*

*Requested 2026-09-11 after William circled a 20:1 sinuous strait of uniform width running the
whole length of a continental rift on seed 59758 at 2048, ocean 62%.* Long narrow seaways are
real but transient: the Red Sea, the Gulf of California and the Gulf of Aqaba are young rifts, and
Baikal and Tanganyika are the same shape on land. What Earth never does is keep one trough of
constant depth between two shoulders of constant height for a thousand kilometres. A rift is a
chain of half-grabens 50–150 km long, each tilted the opposite way from its neighbour, separated
by accommodation zones where the floor rises, so the sea enters only the segments that have
subsided below it and the result is a string of gulfs and lakes joined by sills and land bridges.

- Segment every continental-rift boundary along strike into lengths drawn from a seeded range
  (a map fraction, so 512 and 2048 agree), alternating half-graben polarity: on each segment one
  shoulder is the high footwall and the trough deepens toward it, the other shoulder is a low
  hinge. Trough depth varies per segment (a seeded factor on `riftDepth`) and rises through an
  accommodation zone at each join, where the floor sits near the shoulder hinge level.
- Shoulder height and width vary with the segment and with `rangeVariation` as ranges do.
- Guard: on a seed carrying a long rift below the sea-level cut (find one; seed 59758 at 512 with
  `seaLevel = 0.62f`, 14 plates is the known case), the sea inside the rift is no longer one
  connected body along the boundary: count connected sea components inside the rift corridor and
  the number of land bridges crossing it; assert several of each, and that the corridor's width
  varies (coefficient of variation of the flooded width along strike above a stated floor). Show
  both failing on the current code. `BoundaryPairTest`'s plateau-versus-margin figures must hold;
  `RibbonLandTest` must hold.
- Render and look at 2048, seed 59758, the author's settings, through `MapRasterizer`: the seam
  should read as a chain of gulfs and lakes, Red Sea to Baikal, not a channel. Also seeds 7/42/1234
  at 1024 for regression.

---

## Track F — the interface

*Added 2026-09-12 at William's request: "revamp the UX a bit to make it look less generic and
AI-developed", plus one behaviour change. Everything here is in `:ui` (Compose Multiplatform,
shared by desktop and web) with the two front ends untouched except where a platform seam is
needed. Ships as 2.0.0 together with Track G.*

### F0. Blank canvas on launch — Sonnet

The app generates a world the moment it opens. It must open on an empty canvas with the seed,
settings and the graphics-card toggle ready, and generate only when the user presses Generate
(or Go, or New world). The empty state says what to do in one line. Opening a saved world from the
library still opens it. Guard: no generation runs in `App` until an explicit action; a test on
the state holder shows the launch path never calls the engine.

### F1. Ink on paper — Opus

The chrome is stock Material 3: purple tonal buttons, default sliders, one sans face at one
weight. Replace the theme with the atlas's own vocabulary. Light: a warm paper ground, sepia ink
accent, thin rules instead of tonal cards. Dark: the bfunk.online palette (see the site repo's
CSS; it is the dark variant William asked for on 2026-08-25). A serif display face for the title
and section headings, a compact sans for values, both bundled as Compose resources so the web
build matches the desktop. Sliders, switches and buttons restyled once through the theme so no
control is styled by hand. Guard: `:ui` compiles for jvm and wasmJs; a screenshot at 1440x900 in
each theme is rendered by the desktop test (`StyleGalleryTest` already renders styles; add a
chrome shot) and reviewed by the orchestrator.

### F2. The panel follows the pipeline — Opus

*Dependencies: F1, and the landmarks/atlas move already in flight.* Sections named World,
Terrain, Climate, Water, Peoples, Cartography, in the order the generator runs, each holding its
two or three knobs, collapsed by default except World. Seed and resolution in a slim header.
Atlas-only controls stay in the Atlas pane. Plates and Realms become steppers, not sliders (they
are choices between a few worlds); wilderness is one switch. Guard: every config field the old
panel could set is still settable (a test walks the panel's state and the config).

### F3. The map is the instrument — Opus

*Dependencies: F2.* Style and View move to a compact toolbar over the map (small icon toggles
with the style name); zoom and the status line sit at the map's bottom edge as a chart legend;
the side panels shrink to what F2 left them. The status line becomes a cartouche: world name (a
generated one from the largest people's language), seed, largest realm, with the generation time
as a muted footnote. Guard: compile both targets; screenshot reviewed.

---

## Track G — more of the pipeline on the graphics card

*Added 2026-09-12. Profiled on the CPU, seed 42: at 2048 erosion is 89% of 75.7 s; with the
graphics card on William's 2048 world takes 16.6 s, of which the CPU hydraulic rounds are about
half and everything else the other half. Saves carry the world, so a GPU stage need not be
bit-identical to its CPU twin; each keeps the `ErosionAccelerator` shape (a suspend seam that
returns null to fall back) and a CPU-versus-GPU tolerance test like `GpuErosionTest`.*

### G1. Hydraulic rounds on the GPU — Opus

*Dependencies: E1 merged (it owns `HydraulicErosion.kt`).* Incision, transport capacity,
deposition and sill breaching are per-cell over the flow network. Flow accumulation becomes an
iterative sweep (or pointer jumping over the D8 tree); depression filling becomes the parallel
Planchon–Darboux lowering from the edges, which converges to the same surface priority-flood
gives. OpenGL compute on desktop, WGSL on web, behind a second seam. Guard: worst-cell and mean
difference against the CPU rounds within the tolerance `GpuErosionTest` uses; the mass budget
still 0.0000%; measured speedup at 2048 reported (target: the hydraulic share of a 2048 generation
falls by at least half).

### G2. Export rendering on the GPU — Opus

*Dependencies: none.* Hillshade, hypsometric tints and the style passes are per-pixel; a 4096
export is 50 s and 2.6 GB on the CPU. Render export tiles in a fragment shader and read back,
desktop first (the web export is smaller). Guard: pixel difference against the CPU rasteriser
under a stated bound; 4096 export time and peak heap before and after; 8192 attempted and
reported.

### G3. Ocean currents on the GPU — Sonnet

*Dependencies: G2 (shares the context helper).* The stream-function solve is a Poisson problem;
Jacobi or multigrid relaxation on the GPU. Guard: current field within tolerance of the CPU
solve; time at 2048 and 4096 reported.

### G4. Jump-flood distance fields — Opus

*Dependencies: none (do not touch `PlateStage`'s rift code beyond the distance call).* The chamfer
transform behind distance-from-water (continentality) and the boundary profiles is what leaves
octagonal facets on plateau edges and the shelf. Replace it with a jump-flooding Euclidean
distance field on the GPU, with the CPU chamfer kept as the fallback. Guard: the eight-fold
component of the distance field's iso-contours drops below a stated floor; `ContinentalityTest`,
`ContinentalShelfTest` and `BoundaryPairTest` hold; time reported.

---

## Track H — forces the generator still lacks

*Added 2026-09-12 after William asked for the five candidates from the GPU discussion to be
judged on feasibility and visible realism. Verdicts: sea-level history and snow mass balance are
cheap and change the map most; currents feeding rain is small and cheap; tectonic history is the
biggest piece of work and the biggest structural gain; lithology is worth its erodibility half,
and karst as such is below the map's scale. Ordered by dependency, not by value.*

### H4. Currents feed the rain — Sonnet

*Dependencies: G4 merged (it edits `ClimateStage`'s distance call).* The moisture march picks up
sea moisture at one rate everywhere; on Earth evaporation follows sea-surface temperature, so a
cold upwelling current starves the coast it washes (Atacama, Namib, Baja) and a warm one feeds
it (the Gulf Stream and Norway). The ocean stage already produces a sea-temperature anomaly; scale
the march's over-sea pickup by it (Clausius-Clapeyron: roughly +7% per degree, so a 5-degree
cold anomaly cuts pickup by a third). Guard: on a seed with a cold current along a subtropical
west coast (find one; report which), coastal rainfall on that coast falls and a coastal desert
appears where the belt already made it dry, while a warm-current east coast at the same latitude
is unchanged or wetter; shown failing with the coupling off. Desert-in-band and Mediterranean
counts reported.

### H2. Snow mass balance — Opus

*Dependencies: H4 (shares `ClimateStage`).* Ice today is where the annual mean sits at or below
freezing, so every cold interior is an ice sheet and seed 7 was 43% ice. On Earth a glacier exists
where accumulation beats ablation: Siberia is colder than Norway's coast and has no ice sheet,
because it is dry; Patagonia's snowline is at 1000 m and the Atacama's at 6000 m. Compute a
balance per cell from the seasonal fields: accumulation = cold-season precipitation falling as
snow (cold-season temperature below freezing), ablation = a positive-degree-day melt from the
warm-season temperature (a published degree-day factor, stated). Ice where the balance is
positive. Two consumers: the glaciation mask (which today runs on a provisional temperature
before climate exists - run a provisional climate march before glaciation, the stage costs under
a second at 2048, and the final climate after) and the ICE_SHEET / tundra split in `classify`.
Guard: a cold dry interior (find one on the standard seeds) becomes tundra or cold desert, not ice;
a wet maritime highland at the same latitude keeps ice lower than a dry one; ice share of land
reported per seed against the Earth figure (about 10% of land, most of it Antarctica and
Greenland); shown failing with the balance off. This will move the culture and realm guards; report
them.

### H5. Sea-level history — Opus

*Dependencies: E1 merged (it owns the hydraulic rounds).* The hydraulic rounds grade every river
to today's sea level, so no valley continues below it and every coast is a percentile cut through
the land. On Earth the last lowstand was 120 m down and rivers cut to it; the rise since drowned
their lower valleys into rias, estuaries and the sounds of the Atlantic seaboard, and left the
shelf a flooded plain. Run the hydraulic rounds with the base level a seeded fraction below the
final cut (Earth's 120 m is about 1.5% of relief; expose it as `SeaConfig.lowstand`), then cut sea
level where it is today. Valleys below the cut flood: the coastline follows the drainage where the
land is low, and the shelf inherits the drowned channels. Optionally a highstand terrace: a
second, higher stand that planes a coastal bench (raised beaches) - do it only if it reads at 2048.
Guard: count rivers whose mouth lies inside an inlet longer than three cells (an estuary) and the
coastline's indentation ratio, before and after; shown failing with lowstand zero;
RiverEndingsTest and the mass budget hold; the delta guard holds (deltas build at the present
level). Render and look at 2048 on 718106 and 59758: the coasts should gain estuaries and sounds
where rivers meet them, not everywhere.

### H1. Tectonic history — Opus

*Dependencies: G4 merged (it edits `PlateStage`'s distance calls).* Plates carry a drift vector
that only classifies today's boundaries; nothing has ever moved, so every range is young and
sits exactly on a boundary. Earth's continents carry the scars of boundaries that are gone: the
Appalachians and Urals are old collisions far from any plate edge, worn low and rounded; failed
rifts leave troughs and basins; a plateau has a history of arcs accreted onto it. Do not simulate
plate motion cell by cell. Generate a *history* instead: K past epochs (three or four), each with
the plate seeds displaced along minus their drift times the epoch's age, boundaries classified
in that configuration and belts stamped with the same crust-pair profiles, then aged: height
decays with age, width grows, the profile rounds (a blur whose radius grows with age), and the
belt's erodibility rises for H3. The present epoch stamps last and sharpest. Rifts that opened in
a past epoch and closed leave a sediment-filled trough. Guard: at least one belt on the standard
seeds sits more than a stated distance from any present boundary, is lower and broader than the
present belts by stated factors (Appalachians against Alps), and `BoundaryPairTest` still finds
the present belts; `RibbonLandTest` holds; shown failing with K = 1. Cost: tectonics is 0.9 s at
2048, so four epochs are affordable. Render and look: an old worn range inland of a young coastal
one is the picture.

### H3. Lithology — Opus

*Dependencies: H1 and G1 (the hydraulic rounds must have their final form before they read an
erodibility field).* Every cell erodes at the same rate, so a shield and a sedimentary basin
dissect alike. Carry an erodibility field out of H1's history: old crust (shield, cratonic
interiors) hard and low-relief with lakes; young orogens moderate; basins and coastal plains soft;
flood-basalt plateaus from hotspot and rift epochs hard-capped (Deccan, Columbia) so they hold a
flat top with steep edges. Feed it to the thermal critical slope and the hydraulic incision
coefficient. Karst is deliberately not modelled as landforms - sinkholes and dry valleys are below
the scale of a 20 km cell - but a carbonate flag on soft platform cells may suppress surface
drainage below a threshold, so those plateaus show fewer rivers and springs at their edges; do
this only if it reads at 2048. Guard: relief and drainage density differ between shield, orogen
and basin cells by stated factors, shown failing with a uniform field; the mass budget holds.

---

## Render review, 2026-09-11 (after A1, A2, A3, B1, D4)

Looked at, not measured: seeds 7, 42, 1234 — fantasy, biome, summer and winter rainfall, winter
temperature. Verdict: believable. Shelf-fringed coastlines that read as bathymetry; dendritic rivers
reaching the sea; ice caps with a boreal belt below; deserts confined to the horse latitudes (the
equatorial yellow on seed 42 is savanna by the palette, `C6B95F`, not desert `DCC493`); rain belts
that migrate about 10° between seasons, with the Mediterranean west-coast signature appearing in
winter; A3's rain plumes trailing inland rather than lying in rows. Nothing sent back on the
strength of the pictures.

Two things to carry forward:

- **Shelf width is the same on every coast.** Real passive margins carry wide shelves and active
  (subducting) ones narrow. Once B2 knows the crust pair at each margin, `SeaConfig.shelfWidth`
  should become a per-margin width rather than one number. Small follow-up after B2.
- **Seed 1234 has a sand-coloured patch reaching ~40-50°S** on the east of its south-western
  landmass. A Patagonia-style rain-shadow desert is physically defensible there if a range stands to
  its west, and the audit keeps 98%+ of desert in band — but re-check after A4 rescales rainfall,
  which will move every desert.

### Render review after A6 (2026-09-11, later)

Looked at seeds 7, 42, 1234 biomes and seed 42 annual temperature. Verdict: believable, and more
varied than before. Seed 42's northern continent went from a near-solid ice-and-tundra slab to
ice, a tundra fringe, a boreal belt, horse-latitude dry blocks at 30-45°N and forested west coasts;
seed 7 acquired a Sahel gradient (ice, tundra, boreal, a desert-and-savanna belt at 25-35°N, then a
wet tropical coast); west coasts are green from the subtropics to ~55° on the Chile-shaped
continent and Mediterranean olive appears on mid-latitude west coasts. Ice fell to 19-43% of land
by seed, which reads as Earth-like rather than glacial.

Checked arithmetically, not by eye: the new latitude curve (exponent 1.8) gives ~10 °C at 51°
(London 11), ~24 °C at 30° (Cairo 22), ~3 °C at 60° before the current anomaly (Bergen 8, so a few
degrees cold there), and 32 °C at the equator (real ~27, a pre-existing warm anchor). Two
calibration notes for whoever next touches `buildTemperature`: the equator anchor is ~5 °C warm,
and 60° is ~3 °C cold even with a warm current. Neither is a blocker.

Seed 1234 has a few orange specks inside its equatorial rainforest (rain-shadow pockets behind
small ranges; the audit keeps ≥95% of desert in band) and its south-western interior is dry from
30° to 55°S — the Patagonia note from the first review, now larger. Re-check both after A4.

### Render review after B3 and B2 (2026-09-11, later still)

Looked at seeds 7, 42 and 1234 fantasy views, seed 42 elevation and plates, the B3 delta close-ups
and the B2 single-profile-versus-pairs comparison. Verdict: believable. Deltas are fans one to
three cells wide at river mouths, bulging the coast slightly into bays and nowhere else. Seed 42's
central block now reads as a broad, flat-topped plateau with abrupt edges against a long narrow
belt along its northern coast, which is exactly Tibet against the Andes, and the single-profile
render of the same seed shows why it mattered: one ridge shape everywhere. Hotspot islands (seed
1234, bottom centre) read as a Hawaii-style cone with its own shelf.

One follow-up, not a blocker: seed 1234's island-arc ridges run dead straight (a bar across the
centre and a spine down the north-east) where a real arc bows convex toward the subducting plate.
Worth a curvature term along strike when someone next opens `PlateStage`. The chamfer faceting B2
reported on the widest plateau edges is visible if looked for and invisible otherwise.

### Render review after A4 (2026-09-11, later still)

Looked at biomes on seeds 7, 42 and 1234 and rainfall on 42 and 1234. Verdict: believable.
Deserts sit in the subtropical interiors and nowhere else: seed 7 runs ice, tundra, boreal, a
desert-and-savanna belt at 25-35 N, then wet tropical coasts, and seed 1234's southern continents
are dry through the interior at 30-45 S with green coasts, which is Australia and closes the
"seed 1234 dry interior" follow-up from the first review. Seed 42's central plateau reads as
alpine grey, its west coasts are forested to high latitude, and the horse-latitude dry blocks at
30-45 N are patchy rather than a belt.

Two follow-ups, neither a blocker: seed 42's annual rainfall carries faint horizontal banding
across the northern continent where circulation belts meet (a wind-band seam, worth a look when
`buildWind` is next opened); and the WebP export bound in `ExportSmokeTest` had to move from 64
to 72 because the busier September worlds put more sharp edges on the same seed (99.9th percentile
drift 58 -> 67 with the encoder untouched; README figures updated to match).

### Render review after B4 (2026-09-11, last of the day)

Looked at the 4x glacier crops (on and off) for seeds 42 and 7 and the whole-map fantasy views for
seeds 42 and 1234. Verdict: believable. With glaciation off, seed 42's north is fine dendritic
ridging to the pole, a warm landscape with snow on it; with it on, the same ground becomes broad
flat-floored valleys, hummocky moraine country and scattered lakes, and seed 7's north reads as the
Canadian Shield, a belt of lakes threaded by rivers that still reach the sea. Nothing below the
freezing line changed, and the fantasy views at map scale are the same worlds with more water in
the cold country. Fjord bathymetry is there in the data and faint in the picture, as B4 said.

### Regression after release: the lake lattice (2026-09-11, evening)

William opened seed 718106 at 2048 in the desktop app and found the cold north covered in a
cross-hatched mesh of straight lakes at 0, 45 and 90 degrees. Reproduced at 1024 through
`MapRasterizer` with the app's own config: 57 lakes at 512 became 518 at 1024 and 4.5% of the
land. Cause: B4 called every D8 path draining `minCatchment` of *all land* a glacier and cut each
one a trough, a basin staircase and a moraine bar; on a flat plain those paths are straight,
parallel and 45 degrees apart, and the threshold as a share of all land admits four times the paths
per unit area when the grid doubles while `atResolution` keeps each trough one cell wide. The B4
review at 512 on seeds 7 and 42 (no flat cold plain) could not see it.

Fix (3df9c5e, merge de6e7fe): two regimes on local relief. Channelled ground keeps the valley
machinery, gated by catchment as a share of *frozen* ground and a minimum trough length; flat
ground gets ice-sheet scour that never reads the flow field (noise-modulated lowering, basins
thresholded from a seeded fBm with a wavelength in map fractions, pulled toward existing hollows,
closed by construction). Guard on seed 718106 at 1024: flat cold country cut to trough depth
36.8% -> 2.9%, till on flat ground 3.05% -> 0.00%, shown failing on the old code; resolution
contract: lake share of land 512->1024 grew 2.8x, now 1.3x. Lake-density ratio 12.47 -> 8.14
(guard >= 3). `sheetLowering` 0.012 was set where the culture guard allowed (0.004 put a people at
53%): a knob chosen against a downstream guard, recorded here as such. 2048 could not be generated
in a test worker (OOM); the author's own 2048 view is the check for that size.

Second pass (bac5366, merge bc76b29), after the orchestrator's own 1024 render showed the
mountain flanks still combed with parallel 45-degree bars: the trunk test asked for a share of the
world's frozen ground, so every gully in a rank down a straight range front qualified at once.
Now a trough needs 5% of its own connected ice field (separate glaciers on 718106 at 1024: 17 ->
5), a sinuosity of at least 1.25 head to snout (a path that walked the straight-line distance was
one D8 step repeated), no parallel trough within a trough-width at the same bearing (dropped by
branch; chain-level did nothing and cell-level fragmented troughs and made it worse), and
`basinDrop` doubled so a steep flank is not a ruled paternoster. Guard at 1024, filaments and
parallel-bar share, main -> fixed: 718106 1 -> 0 and 4.1% -> 1.7%; 42 6 -> 0 and 7.1% -> 2.3%;
7 1 -> 0 and 3.0% -> 1.6%. Lake density 7.18x, resolution growth 1.41x (contract 1.7). Known:
seed 42 at 512 now has no valley glacier at all, because its cold ground fails the relief test at
that grid and passes at 1024 - terrain is rougher at finer grids, which is a pre-existing property
of the terrain stage, not of this fix.

Released as v1.1.1 on 767ef26 (CI green): portable zip 96 MB, MSI 96 MB, web zip 4.4 MB;
packaged exe passes --gpu-check; web build deployed (site d266033, loader stamp 202609111142,
app wasm e689b9e3 served as application/wasm). Orchestrator's own 1024 render of seed 718106
after the second pass: irregular lakes on the plain, a handful along the range front, nothing
straight or parallel. 1.1.0 saves open unchanged and keep their terrain; new worlds get the fix.

Third pass (757b265, merge 98da2e8), after William's 2048 screenshots showed combs again at the
foot of the range and too many, too-large lakes. Measured on main at 2048 with his settings (seed
718106, ocean 62%, 14 plates, 12 realms): 4 bars, 113 lakes, water 2.10% of land, largest lake
0.093% of the map. Basins were still cut cell by cell along the D8 path, so each was as wide as
the line it followed; no threshold fixes that. A basin is now a region: the ground within a trough
half-width of the path, opened (eroded one cell and dilated back) so it is a union of 3x3 blocks
and three cells wide by construction, refused if the ice walked a straight D8 line or the shape is
a bar; its floor is cut below its own rim, so recessional moraines are off; no basins in the
run-out past the snowline. Lake abundance is now budgeted in map fractions: sheetLakeShare 0.02
of frozen flat land (Finland is 10% water but ~2.5% in bodies a world map can draw),
maxLakeShareOfMap 0.00016 (Lake Superior's share of Earth; over-large basins peeled inward),
minLakeShareOfMap 0.000016. After: 0 bars, 70 lakes, 1.45% of land, largest 0.083%. With
glaciation off the same world has 54 lakes, 1.19% and a largest of 0.122%: most of the big water
at 2048 is the river stage filling tectonic basins, not ice, and the largest lake never was
glacial. That is a separate question, raised with William. Guards: zero bars at 2048 (4 on the
old code); resolution contract to 2048 on the ice's own share (0.20/0.42/0.26% at 512/1024/2048);
lake-density guard 3.0 -> 2.5 (measures 2.87) because the stage puts less water down;
DepositionTest pin re-recorded, land 6226 unchanged.

Released as v1.1.2 on 48b3850 (CI green): portable zip 96 MB, MSI 96 MB, web zip 4.4 MB;
packaged exe passes --gpu-check; web build deployed (site 0600b86, loader stamp 202609111702,
app wasm a0d98d4e). Orchestrator's own 2048 render of William's world after the third pass: no
bars anywhere, three rounded lakes at the range foot where the comb was, irregular shield lakes.
Open, raised with William and not started: outlet incision so that a filled tectonic basin larger
than the Caspian drains down (the largest lakes at 2048 are not glacial); and the hotspot cones,
which surface on land at 62% ocean and show the chamfer faceting as eight-sided volcanoes.

Lesson, now in the working method: review renders at 1024 or above through the app's renderer, on
a seed chosen to have the terrain the chunk acts on.

### Render review after Track E (2026-09-12)

Looked at William's two worlds at 2048 (ocean 62%, 14 plates, 12 realms) through the app's
renderer with river cells painted: seed 59758's rift seam and delta mouth, seed 718106's northern
lake country. Verdict: two of four right. The rift is no longer a ruler-edged strait; it holds a
trough lake and a flooded segment behind sills, which is the Baikal outcome. Lake abundance on
718106 is 0.38% of land with nothing above 0.04% of the map, irregular and scattered. Not right:
the delta at (640, 266) is rounder but the drawn river still stops at its inner edge with a pocket
of sea inside the lobe (sent back to E1); and rivers crossing the exposed floor of a balanced
lake run as dead-straight horizontal or vertical lines, one lake on 718106 has a ruler-straight
edge - the re-routing toward the water assigns targets in scan order on a flat (a fix-up agent is
on it). Release 1.2.0 waits for both.

Follow-up on the straight lines (e079d49, merge a5b7cec): the diagnosis above was wrong and the
fix-up agent measured why. Rivers ending in balanced lakes are no straighter than rivers to the
sea (straight-run share 0.43 vs 0.48). The lines were rivers drawn ACROSS lake surfaces: under a
lake the routing runs on the fill, flat to the 1e-6 nudge the flood adds in cell-index order, so
D8 walks due east or due south row after row (four horizontal runs of 36-44 cells across one
2163-cell lake on 59758 at 2048). Lake cells are now struck from the channel mask and a trace stops
at the first water cell; guard: no drawn river crosses open water, shown failing (seed 7 drew 9
cells across a lake). Routing into balanced lakes was rewritten anyway to steepest descent over
the real ground with seeded value noise 1e-5 on flats (dead-flat floor: 95.8% repeated bearings
and a longest run of 61 became 61.6% and 32). Reported and passed to E1: ruler-straight
shorelines come from lacustrine fans laid to one flat level. Reported and left: D8 on a smooth
planar hillside holds one bearing for 20-35 cells with no flat involved; that is D8 itself.


## Ledger

Update the entry when the chunk's commit is on `main` and CI is green. Record the numbers the
guard reported, so the next chunk knows its baseline.

| Chunk | Model | Status | Date | Commit | Numbers |
|---|---|---|---|---|---|
| D1 Full-world save format | Opus | done | 2026-09-11 | 09eb31f (merge 424b34a) | gzip whole-file 2.36-2.57x (512: 24.7->10.4 MB; 1024: 98.7->38-40 MB); heights 1.1x, id maps 136-1010x; round-trip guard failed with a section dropped, then passed; v2 saves open and re-save as v3; all 10 stages reused by assertSame; web stores raw (compression deferred to D2) |
| D2 Web storage | Sonnet | done | 2026-09-11 | aaf19c7 + 158cac2 (merges b83248c, eb141f0) | IndexedDB with a headers store (listing never reads an array; guard shown to throw without readPrefix); library/codec became suspend; real gzip via CompressionStream; JVM-gzipped fixture decodes on both platforms; live selftest heightIdentical=true, 729 KB, write 85 ms, read 61 ms. Fixture had to be regenerated after A1 added 4 sections (29 total) - the failure that opened D4 |
| D3 Retire the determinism gate | Haiku | done | 2026-09-11 | 408beb4 (merge b278e7d) | ci.yml fingerprint step continue-on-error with ::warning::; README's three seed-only-save claims replaced; site CLAUDE.md needed nothing; memory note updated by the orchestrator |
| D4 Forward-compatible sections | Sonnet | done | 2026-09-11 | 2784957 (merge 31eec2b) | PartialWorld interface, WorldMap implements it, so no call site changed; a stage with any section missing is null and fails the === reuse guard, regenerating it and everything downstream; guard shown to throw WorldFormatException on the old reader then pass; library listing reports 'complete' / 'regenerates <stage>' from the header alone; 30-section fixture passes on both platforms |
| A0 GEOGRAPHY.md reconcile | Haiku | done | 2026-09-11 | 9ea2db2 | prose only; river-uphill figure 12-14% carried as last measured 2026-08-23 |
| A1 Seasons | Opus | done | 2026-09-11 | 1aa12ee | 35deg swing: land 13.1C / sea 2.9C; Mediterranean west-coast cells 211/517/494 (seeds 7/42/1234), 0/0/0 with seasons=false; seasons=false reproduces all six fingerprint lines; desert-in-band 100/99/100/98% (belt rescaled to restore the annual mean, no threshold moved); desert AREA fell 5.1%->1.9% on seed 42 (for A4); border-on-river 2.08/2.12/2.16/1.04 (seed 99 down from 1.46); default fingerprint rivers=26 realms=14 |
| A2 Continentality | Sonnet | done | 2026-09-11 | eb694aa (merge 015a178) | first cut used the blurred exposure field and measured only 3.0C interior-vs-coast against the 6C spec; reworked to a chamfer distance-from-water, factor = clamp(d / 3*coastalReach): seed 42 at 50deg gap 0.2C at continentality=0, 7.5C at default 0.6; annual mean bit-identical; desert-in-band 97-100% |
| A3 Meridional wind / monsoon | Opus | done | 2026-09-11 | 8a14ed5 + 53e39d7 (merge 19746a2) | semi-Lagrangian march in lock-step wavefronts, parallel by circulation belt; wind is a vector, direction from the thermal equator; meridionalWind=0 reproduces the zonal march exactly; desert-in-band 100/99/100/98; Mediterranean cells 174/424/375; the plan's 3x/2% monsoon guard could not discriminate (base already >2% on most seeds; 10deg tilt lands summer onshore flow on poleward coasts; rainfall clamped at 1) - guarded instead by a paired difference: east-west ridges gain +0.030/+0.030/+0.013 vs descending +0.020/+0.010/+0.006, exactly 0 without the slant; A4 to restate the monsoon claim; ocean stage untouched (zonally uniform meridional stress has zero curl) |
| A4 Absolute rainfall | Sonnet | done | 2026-09-11 | f8fd195 (merge 6eef6af) | precipitationMm section; MM_SCALE=52653 derived once from seed 42's 99.5th land percentile (0.05698 -> 3000 mm) and applied to every seed: windward coasts 3067-3228 mm, desert cores 89-190 mm; Koppen aridity threshold on mm replaces the provisional t>=13 gate (summer-concentrated 32 / even 16 constants found empirically, not derived - unverified); desert-in-band 86/90/92/85% (seed 99 sits on the 85% floor - watch it); desert share 6.14/1.71/0.99/1.67%, driest/wettest 6.20x; old normalisation could not discriminate an arid from a lush config of the same seed (0.52x) where mm does (59x); monsoon claim re-measured unclamped on seed 26: 4.07% of land (was 2.93% clamped); MeridionalWindTest pins replaced by an in-test reference zonal march built on marchSeaStep/marchLandStep, so nothing re-pins; largest culture 38/34/30%; cold-cap coast forest 60.8/66.0/29.4% |
| A5 Cold-cap report | Haiku | done | 2026-09-11 | e0c3081 (merge a1014ae) | 0% of 50-60deg west coasts forested on all 3 seeds despite 1.9-3.8x latitudinal-mean rain (precip 0.83-0.99): cap is NOT the cause; classify gates on annual mean (<7C -> taiga) and the curve puts 55deg near 0C. Opened A6 |
| A6 Temperate by coldest month | Sonnet | done | 2026-09-11 | 463e6f9 (merge 357a923) | Koppen thermal gates on the seasonal fields (warmest<10 ET; coldest<=-3 D; coldest>=18 A; else C); LATITUDE_EXPONENT 1.25->1.8 was necessary (gate alone left 55deg coasts at 6.8C in summer); 50-60deg warm west coasts 0/0/0.1% -> 65/53/59% forested, interior taiga 100/97/96%; ice share fell (seed 42 32%->19%, seed 7 56%->43%); a PROVISIONAL t>=13 desert gate holds the audit and suppresses cold deserts - A4 replaces it with Koppen aridity; culture settlement now decided per cell (a unit straddling the ice margin no longer strands its non-ice cells): 100% settled on all seeds |
| B1 Continental shelves | Sonnet | done | 2026-09-11 | d2d9d0a (merge 7e1384a) | redesigned as a post-sea-level floor remap after the pre-sea-level depression moved coastlines and its guard could not discriminate; near-coast shallow 100/100/100% vs 60.3% control, far 2.5/1.3/0.0%; 0 land cells differ on any seed; new SeaConfig (shelfWidth=20, shelfDepth=0.10) in the SEA_LEVEL reuse guard; largest realm 28/26/26%; seed-7 culture 38% (was 48% failing) |
| B2 Crust-pair boundaries | Opus | done | 2026-09-11 | f72ecf7 + 3205447 (merge ac2305a) | five profiles in TectonicsConfig, cell widths through atResolution: Andean margin 14 / 0.52 asymmetric with a volcanic arc 13 cells inland; collision plateau 26 / 0.34 flat over 60% with rim ranges; island arc trench-both-sides, ridge on the lower-id plate; rift trough 7 / 0.25 with shoulders; hotspot chains on 35% of oceanic plates; guard BoundaryPairTest on six seeds: plateau 3.47x broader for its height (per seed 1.97-4.97x) vs 0.72x with one profile, shown failing on the control; SeaLevelStage.percentile now cuts exactly (the histogram bin held 2-5% of the map, so land fraction was 0.708 for a 0.700 setting); plateauAlongVariation 0.5 because a uniform plateau read as one ice cap; new plates.nearestBoundaryClass section joins TECTONICS (32 sections, fixture regenerated); DepositionTest pin re-recorded (land 6226); after the re-merge: ribbon 0.1%, incision 1.7x, desert-in-band 100/96/91/98, seed-7 largest people 41%, realm spread 28/23/24%, Mediterranean 271/927/1316; unverified: faint chamfer faceting on the widest plateau edges |
| B3 Deposition | Opus | done | 2026-09-11 | 91d5048 (merge 83bacfa) | sediment routed in topological drainage order (the height-key sort lost load handed to already-walked cells - 3% short at round three); capacity = transportCapacity*sqrt(area)*slope, depositionRate=0.06; deltas breadth-first from mouths draining >= deltaMinCatchment, lake fans stop 2x pond depth short of the surface; spoil laid once before the final relaxation (feeding it back made GPU-vs-CPU worst cell swing 0.006-0.034 and a seed-42 people 29%->49%); mass balance 0.0000% by tallies and by summed heights; 68 mouths gain land within 4 cells vs 0 control; GpuErosionTest worst cell 0.007131 unchanged; render review: fans one to three cells wide at mouths, coasts bulge slightly into bays - believable. Moved coastlines left MeridionalWindTest pins stale (A4 replaces them with an in-test reference march) and seed 7's largest people at 46%. Fix-up 18db41a (merge f608ad9): chooseHearths scored candidates globally, so seed 7's main landmass (83-86% of habitable land) drew 3 of 7 hearths while one went to a landmass under 0.1%; hearths now allocated per landmass by largest remainder as BasinRealms.chooseSeeds does; new CultureHearthLandmassTest shown failing pre-fix (entitled to 6 of 8, got 5); seed 7 41%->34%, 42 38%->38%, 1234 31%->30%; realms-per-people 1.63-2.13, frontier-inside-country 68-84%; render: peoples follow landmasses |
| B4 Glaciation | Opus | done | 2026-09-11 | d3c6191 (merge 1ed02d2) | GlaciationStage inside the SEA_LEVEL step after the shelf remap, returning a SeaLevelResult (no new section or field); mask = provisional annual mean <= 0C from ClimateStage.buildTemperature (made internal - the only climate change); U cross-section across the flow, staircase reaches measured in descent, recessional moraine per reach, cirque per head, terminal moraine per land snout, sea-floor trough per marine snout, bounded to mask + 8 cells; never touches isLand (land 6226 unchanged); guard seed 42: 12.36 lakes per 10k cold cells vs 0.99 temperate = 12.47x, 0.00x with glaciation=false (shown failing); off reproduces the base fingerprint exactly; lakes 5/4/1 -> 73/56/20; cultures largest 29/30/32%, ice 17/41/25%; desert-in-band and deposition budget unchanged; shelf land-invariance case and ValleyIncisionTest's control now run with glaciation off because they measured it; DepositionTest pin re-recorded; unverified: fjord bathymetry exists (109 units of sea floor) but the coastline cannot indent because isLand is fixed first - recorded in GEOGRAPHY.md as a deviation replacing 'No glaciation' |
| C1 Docs and release | Sonnet | done | 2026-09-11 | ccba6d7 (merge 6b6d8c6) | README pipeline, saving, peoples, views and CI sections rewritten against the code; TODO.md gained three done entries and five open items from the render reviews; GEOGRAPHY.md and the atlas copy needed nothing; cartogenesisVersion 1.1.0; checkout and setup-java to v5; tag v1.1.0 on 6b6d8c6 (CI green), release with portable zip 96 MB, MSI 96 MB, web zip 4.4 MB; packaged exe passes --gpu-check; web build deployed to cartogenesis.bfunk.online (site aa04a5d, loader stamp 202609110610, new wasm served as application/wasm); site CLAUDE.md updated |
| E1 Outlet incision | Opus | done | 2026-09-12 | d5bfa19 (merge 8b376cd) | FlowRouting.spillways per round; HydraulicErosion.breach lowers a basin's sill by stream power with the same coefficient as incision (outletIncisionRatio 1, outletReach 64), breaching the sill over a reach because nicking the rim cell alone does nothing (the next fill finds the same rim - measured); never below the basin floor or the sea; spoil into B3's load, budget 0.0000%; one closing breach before the last relax or a replayed save diverges (TerrainSnapshotTest caught it); largest lake % of map 718106 at 512/1024/2048 0.136/0.045/0.083 -> 0.018/0.025/0.029, seed 99 0.165 -> 0.049, 43 0.122 -> 0.042; largest basin's fill depth over 12 rounds 0.239 -> 0.010 vs 0.239 -> 0.240 control; OutletIncisionTest: no lake above the Caspian's 0.073% of map and every over-large one at least halves, shown failing off; deltas (the strand fix): lobes slope apex to rim, reach in front of the river, irregular by cell-index arithmetic, whole cells only - DeltaMouthTest: new land with nowhere downhill 5.8-9.1% -> 0.2-2.6%, shown failing off; render at 2048: upland lakes gone with rivers through them, coasts unchanged, the 59758 mouth a rounded lobe. Moved: LakeWaterBalanceTest and GlaciationTest's comb/resolution cases run with the notch off because E1 drains their chosen basins (1775 -> 110 cells); GeographyAuditTest seed 99 desert-in-band 84 -> 80%: the out-of-band desert is a 45-50 degree interior present before (254 -> 216 cells) and the in-band count fell 1184 -> 677 as the drained interior's coldest month moved the aridity gate; guard restated by the orchestrator as pooled >= 85% over the four seeds with a 75% per-seed floor, on the grounds that Earth itself is ~85-88% with the Gobi, Taklamakan, Great Basin and Patagonia outside, and one world is one sample |
| E2 Lake water balance | Opus | done | 2026-09-11 | 667c956 (merge fec976d) | Thornthwaite on the two seasonal fields, unfitted: hot desert 2272 mm/yr, cool temperate 554, frozen 0 (glacial lakes stay at spill); runoffFraction 0.35 (Earth ~40k of 110k km3/yr; a constant flatters dry basins - Volga/Caspian is ~0.12); bisection over basin hypsometry; endorheic lakes become sinks with flow re-pointed, playa mask as section rivers.playa (33 sections, fixture regenerated); wet basins bit-identical; guard on dry seed 43 (1775-cell basin, 172 mm rain vs 577 evaporation): 18% of spill area at balance vs 100% measured with waterBalance=false; wet seed 99 at spill; 0 stranded rivers; seed 43 lake share 2.40 -> 0.91%, largest 0.677 -> 0.122% of map; 718106 at 1024 82 -> 67 lakes, 10 endorheic; border-on-river moved (42: 1.54 -> 1.10, 99: 1.30 -> 1.97, report-only); render: seed 43's rectangular basin becomes a small lake with a dendritic net across the exposed floor |
| E3 Round hotspot cones | Sonnet | done | 2026-09-11 | 5a7377f (merge, see log) | the stamp already used true Euclidean distance; the eight-fold amplitude at 512 (0.083) is the grid floor of a 2.5-cell radius (supersampling 3x3/5x5/9x9 all read 0.053), and at 2048 it is 0.000 before and 0.004 after, so the distance metric was never the cause of what the orchestrator saw; stamp now supersampled 5x5 with three low-order seeded rim harmonics (k = 2, 3, 5 from a splitmix64 hash of seed and vent) so no two cones match, knob hotspotConeDetail; DepositionTest pin re-recorded, land 6226 unchanged. Open: the faceted look at 2048 on land cones is most likely erosion cutting radial gullies along the eight D8 bearings down a symmetric cone - not investigated, low priority |
| E4 Segmented rifts | Opus | done | 2026-09-12 | 6a39b01 (merge 6b16e65) | every continental-rift boundary walked along strike (arc length by double BFS, because a rift meanders) and cut into seeded segments of 0.040-0.100 of map width, so 512 and 2048 break a rift the same way; per segment a seeded depth factor, a footwall that alternates flanks, a wedge floor deepest against the footwall rising to a low hinge, shoulders whose height and width come from one draw (independent draws made a tall narrow shoulder that surfaced as ribbon land) and vary with rangeVariation; depth and asymmetry taper through an accommodation zone with a modest sill at each join; deterministic (index order, splitmix-seeded LCG); nine TectonicsConfig knobs; other profiles untouched. RiftSegmentationTest on seed 59758 at 512, ocean 62%, 14 plates: sea bodies in the rift 1 -> 3, land bridges 0 -> 4, flooded-width CV 0.03 -> 0.32, corridor 98% -> 82% flooded; a second case shows the unsegmented world failing all three. BoundaryPairTest 3.47x held; RibbonLandTest held. Moved: DepositionTest pin (land 6226 held); GlaciationTest's comb and lake-share measures now exclude water in a rift trough (tectonic, not ice); its 512 denominator floor 1e-4 -> 0.001 with a written reason; LakeWaterBalanceTest's dry-basin bound 30% -> 45% (measures 39% because PlateStage renormalises the whole field; the basin holds no rift cells; control still 100%) - a bar moved under rule 5 with the reason stated, though not an Earth figure. Render at 2048: the ruler-edged strait is lagoons and gulfs behind sills with the ribbon joined to the mainland. Not verified: rift basins become lakes at 1024 and 2048 but not at 512 because LakesConfig.minCells is a fixed 12 cells, not a map fraction - follow-up |
| F0 Blank canvas on launch | Sonnet | in progress | 2026-09-12 | | folded into the landmarks/atlas UI chunk |
| F1 Ink on paper | Opus | queued behind F0 | | | |
| F2 Panel follows the pipeline | Opus | queued behind F1 | | | |
| F3 The map is the instrument | Opus | queued behind F2 | | | |
| G1 Hydraulic rounds on GPU | Opus | queued behind E1 | | | |
| G2 Export rendering on GPU | Opus | in progress | 2026-09-12 | | |
| G3 Ocean currents on GPU | Sonnet | queued behind G2 | | | |
| G4 Jump-flood distance fields | Opus | in progress | 2026-09-12 | | |
| H4 Currents feed the rain | Sonnet | queued behind G4 | | | |
| H2 Snow mass balance | Opus | queued behind H4 | | | |
| H5 Sea-level history | Opus | queued behind E1 | | | |
| H1 Tectonic history | Opus | queued behind G4 | | | |
| H3 Lithology | Opus | queued behind H1 and G1 | | | |

Suggested order. **D1 first, alone** — everything after it is cheaper once cross-platform
identity stops mattering, and it touches the codec that C1 will package. Then **D2 and A0 and B1
in parallel** (three independent chunks, three worktrees). Then D3 and D4, and from there the two tracks
run side by side in dependency order: **A1 → A2 → A3 → A4 → A5 → A6** alongside **B2 → B3**, with
**B4** after both A1 and B3, and **C1** last.
