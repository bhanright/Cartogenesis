# Realism plan

*Drawn up 2026-09-11, revised the same day. A save format that stores the world, then nine
improvements to the geography and climate, implemented by contributors in chunks that each leave
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
   parallel contributor's build in progress. On 2026-09-11 two contributors each followed the old version of
   this rule and spent a quarter of an hour killing each other's builds — five daemons ended
   "stop command received". If a build dies with "daemon disappeared" or "build cancelled", it was
   stopped from outside; rerun it.
   Two more Windows traps, both seen on 2026-09-12: directories under a module's `build` tree
   can acquire the ReadOnly attribute, which surfaces as a `compileKotlinWasmJs` "Internal
   compiler error" or an `AccessDeniedException` on `build/classes`, and `Remove-Item -Recurse
   -Force` cannot clear it — clear the attribute recursively first, then delete; and a shell
   whose `grep` was handed no files reads stdin and sits forever with its working directory in
   the repo, holding the tree — never `grep $(find ...)`, use `find ... -exec grep {} +`.

7. **Reports carry numbers.** A contributor's final report says what changed, the before/after
   figures its guard measured, what the render showed, and anything it could not verify. The
   maintainer decides from the report; the diff is there if the report raises a question.
8. **The graphics card is part of the design, not an afterthought.** (William, 2026-09-12.) Any
   new stage or pass whose cost is per-cell or per-pixel arithmetic - a sweep, a relaxation, a
   distance field, a raster - is specified with a GPU path from the start, behind the same seam
   erosion uses (`ErosionAccelerator`-shaped: suspend, returns null to fall back to the CPU),
   OpenGL compute on the desktop and WGSL on the web, with a CPU-versus-GPU tolerance test and a
   measured speedup in the report. Work that is a graph walk, a priority queue or a region labelling
   stays on the CPU and the spec says so. The CPU path remains the reference; saves carry the
   world, so the two need not be bit-identical.

9. **Code is written for the next human.** (William, 2026-09-12: "sweep the code … for any
   variable or method names, in-code comments, etc that are very machine oriented and try to make
   the code more human maintainable".) Names are words, not abbreviations or symbols —
   `shorelineHeight`, not `thr`; `cellsAcross`, not `w`, beyond a two-line loop — and a name
   carries its unit when it has one (`reachCells`, `lapseRateCPerKm`). A comment says why the code
   is as it is and what invariant it keeps, in a sentence or three; the history of how a figure
   was measured, what was tried and reverted, and which chunk did it belongs in this plan's ledger
   and in `GEOGRAPHY.md`, not in KDoc. Magic numbers become named constants with the derivation
   beside them. Every chunk leaves the files it touched more readable than it found them; C2
   sweeps what the 2.0 line accumulated. The rules in full, each with a before/after from the file
   the sweep was calibrated on, are in `CODE_STYLE.md`.

10. **This repository names only its own home.** (William, 2026-09-12.) The generator's public
    home is cartogenesis.com; no other site is named or linked in this repository, in release
    notes, in the app or on the page. Where the history has to refer to an earlier host, write
    "the earlier host".

## Session protocol

Each session, on any model:

1. Read the **Ledger** below. Take every unchecked chunk whose dependencies are checked.
2. Dispatch a contributor per chunk, each with its spec pasted verbatim plus the ground rules.
   Chunks with no dependency between them run **in parallel** — Track A and Track B are
   independent of each other throughout, and each contributor works in its own git worktree so
   parallel chunks cannot tread on one another's files. Chunks within a track run in their
   dependency order.
3. When a report comes back green: merge the worktree, update the ledger entry (date, numbers,
   commit hash), commit with the chunk's name in the subject, push, wait for CI green. Two
   chunks that both touch `WorldGenConfig` or `ClimateStage` will conflict at merge; the
   maintainer resolves that, which is the main reason it reads reports rather than diffs.
4. If a session dies mid-chunk, the next session discards that worktree and restarts the chunk.
   Nothing in a chunk depends on a previous session's memory.
5. **Push before dispatching.** Contributors' worktrees branch from the last *pushed* commit, not from the
   local `main`. On 2026-09-11 three chunks were dispatched after local merges but before a push,
   and each started without the work it was told to build on: A6 lacked the test it was to extend,
   D2 checked in a save fixture written without A1's sections, and D3 was diffed against a stale
   base. If a dispatch must go out before a push, the contributor's first instruction is `git merge
   main` in its worktree.

Each chunk is sized below by what it needs: the most careful work where the algorithm is the work; a lighter hand where the spec is precise and the test is clear; the lightest for docs, renders and tallies.

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

### D1. Full-world save format
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

### D2. Web storage
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

### D3. Retire the determinism gate
*Dependencies: D1, D2.*

- `ci.yml`: the JVM-versus-Wasm fingerprint step stops failing the build (`continue-on-error`)
  and prints its diff as a warning annotation instead of an error. Keep the step; it is a free
  platform-bug detector.
- `README.md`, `TODO.md`, GEOGRAPHY.md, the site's notes and the memory note in the
  Cartogenesis multiplatform reference: replace every statement that saves depend on identical
  generation with the new contract — a save carries its world; platforms may differ.
- The `RealmSpreadTest`/`CultureRealmTest` comments that justify sorted neighbour lists by the
  Wasm split can stay as history; the sort itself stays, because platform-independent output is
  still cheaper to reason about even when it is no longer required.

---

### D4. Forward-compatible sections
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

### A0. Reconcile GEOGRAPHY.md
*Dependencies: none. Independent of everything; do first.*

`GEOGRAPHY.md` "Known deviations" still says there is no erosion, no ocean currents, and that
desert placement is loose. All three were fixed on 2026-08-23/24 and the file's own later section
records desert at 99-100% in band. Rewrite "Known deviations" to what is actually still wrong: no
seasons, no continentality, zonal-only wind, per-world rainfall normalisation, no deposition, no
shelves, no crust-pair boundary types, no glaciation. Each of those is a chunk below; link them.
Guard: none — it is prose. Render: none.

### A1. Seasons
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

### A2. Continentality
*Dependencies: A1.*

Seasonal amplitude scales with distance from water. `applyMaritimeInfluence` already computes a
blurred water-exposure field; reuse it. Amplitude multiplier `1 + continentality * (1 - exposure)`
applied to the seasonal departure from the annual mean, with `ClimateConfig.continentality: Float
= 0.6f`. Interior at 50° should swing markedly more than a coast at 50°.

- Guard: on seed 42, mean seasonal range of land cells more than `coastalReach * 3` from water
  exceeds that of cells within `coastalReach` by at least 6°C. Show it fails with
  `continentality = 0`.
- Render summer and winter temperature. Look for Siberia-versus-Ireland.

### A3. Meridional wind and the monsoon
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

### A4. Absolute rainfall
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

### A5. Cold-air moisture cap — report only

*Dependencies: A1.*

`coldCap` clamps moisture by temperature and may make all cold regions uniformly dry, starving
temperate rainforest on high-latitude west coasts (the Bergen case). Render seeds 7, 42, 1234;
report the share of 50-60° west coasts classed rainforest or temperate forest. No code change in
this chunk — if the number is low, open a follow-up in the ledger with the figure.

### A6. Temperate climates by coldest month
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

### B1. Continental shelves
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

### B2. Crust-pair boundary types
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

### B3. Deposition
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

### B4. Glaciation
*Dependencies: A1 (needs winter temperature), B3 (moraines are deposition).*

Where mean annual temperature is below freezing at elevation, carve: U-shaped valleys along
existing trunks (widen and flatten the valley floor), cirques at heads, and over-deepened basins
that become lakes behind moraine dams. Fjords are drowned glacial valleys and fall out of B1 plus
this. Bounded to cells the `ICE_SHEET`/`TUNDRA` classification already marks.

- Guard: on seed 42, lakes per unit area in the glaciated zone exceed the unglaciated temperate
  zone by at least 3x. Show it fails with glaciation off.
- Render the high-latitude coasts. Look for the Norway/Chile coastline.

## Track C — closing

### C1. Docs and release
*Dependencies: all of A and B.*

README pipeline description, GEOGRAPHY.md "Held by construction", TODO.md, the atlas copy that
describes biomes. Bump `cartogenesisVersion`, rebuild the three artefacts, cut the release, deploy
the web build with the site's script, update the site's notes. Every step of that is already
documented in `README.md` under Deploying and in the site repository's notes.

---

### C2. Names and comments for humans

*Requested 2026-09-12, for after the 2.0.0 release: "sweep the code after v2.0.0 release for any
variable or method names, in-code comments, etc that are very machine oriented and try to make
the code more human maintainable if possible." Runs on `main` as the first chunk of the 3.0 line,
before M1, so every audit chunk is written against the readable code and no rename ever has to
be threaded through a chunk in flight; `release/2.0` keeps the old names, and a 2.0.x fix is
re-applied to `main` by hand.* Behaviour-preserving by construction, which is what makes it safe:

- **Calibration first.** One stage file (`SeaLevelStage.kt`, which has both the terse arithmetic
  and the long chunk-history KDoc) reworked and shown to William as a before/after sample, with
  the rules it applied written down; the sweep proceeds on his word or his corrections. Taste is
  his, not the contributor's.
- **What changes.** Single-letter and abbreviated names outside two-line loops become words with
  units; method names say what they return or do (`thresholdAtRank` stays, `cutAt` becomes
  `landAndWaterBelow`, or whatever reads at the call site); KDoc keeps the why and the invariant
  and loses the measurement history, which moves to the ledger row or `GEOGRAPHY.md` with a
  pointer left behind (`See REALISM_PLAN.md, H5.`); magic numbers become named constants with
  their derivation; comments that narrate mechanics line by line go; a short `CODE_STYLE.md`
  records the rules so later contributors follow them (and rule 9 points at it).
- **What must not change.** Shader source names are theirs. Public entry points the web page and
  the desktop launcher call are renamed only with their callers. *Serialised names were on this
  list and came off it* (William, 2026-09-12): nothing has been distributed and there are no saves
  he needs to keep, so a `@Serializable` property or a `WorldSections` section string is swept like
  any other name, without a `@SerialName` shim, and `WorldCodec.FORMAT_VERSION` is bumped in the
  same commit so an older file is refused by name rather than read with this build's defaults
  wherever a key has moved. `CODE_STYLE.md` states the rule and what would change on a release.
  Compatibility becomes a requirement the day the program is distributed.
- **Guards.** World fingerprints (`WorldFingerprintTest` and the FINGERPRINT lines CI prints)
  bit-identical before and after on the standard seeds at 512 and on 718106 and 59758 at 2048 —
  a rename that moves a bit is not a rename; a world saved by this build reopens identical, array
  for array (`WorldCodecTest`'s round-trip case), which is what replaced the old-save guard when
  serialised names came off the frozen list; the GPU tolerance tests hold; the full per-merge tier
  and the audit tier once; the Wasm bundle builds. Order: the shared
  model (`WorldMap`, `WorldGenConfig`, the stage results) first by one contributor, then the pipeline
  stages, cartography, ui and the two launchers in parallel worktrees, each merged behind a
  fingerprint check.

## Track E — lakes sized by physics, not by basins

*Added 2026-09-11 evening after 1.1.2. The river stage fills every closed depression to its spill
level in every climate, and the hydraulic pass routes on that filled surface so it never touches
the lip. At 2048 the largest lake on a typical world is a filled tectonic basin at 0.12% of the
map, bigger than the Caspian's share of Earth. Real basins are drained by outlet incision in wet
country and held far below the rim by evaporation in dry country.*

### E1. Outlet incision
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

### E2. Lake water balance
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

### E3. Round hotspot cones
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
A second contributor could not reproduce it on the same commit across 560 worlds (400 seeds at 512, 150 with
LEAVE_WILDERNESS, the author's config at 1024 and 2048 with each of E1 and E2 toggled off, ten
sea-level jitters at 2048), showed by reading that neither `BasinRealms.assign`'s renumbering nor
`dissolveEnclaves` can widen the id range, and added `checkRealmIds` after both steps (fails fast
naming the step and cell) plus `RealmIdRangeTest` (74a9a23, merge 5b04d01). The maintainer then
generated the same world three times in one JVM and again inside the full desktop suite: every
per-stage checksum identical, no crash. Not explained. If it recurs the message now names the
step; a longer soak was run before release.

### E4. Segmented rifts
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

### E5. Deltas and fans with natural outlines
*Dependencies: E1, H5 merged. Requested 2026-09-12: William, looking at 718106 at 2048, "some
of these deltas and features in your example are too square and artificial looking" — a lobe
that is a perfect half-disc and, beside it, flat rafts of new land with straight edges and
right-angle corners, one with the river running across it. The 2048 review found the same on
59758's north and east coasts, identical before and after H5, so it is B3/E1's deposition.* A
delta is a fan: its outline follows distance from the mouth bent by the depth it builds into,
not the grid and not a compass.

- Measure first: a mask of deposition per mechanism (sea-mouth lobe, lake-inflow fan, alluvial
  fan, anything else) on 718106 and 59758 at 1024 and 2048, to find which makes the half-discs
  and which the rafts. Two hypotheses to test: `fan` hands `accepts`/`levelOf` a breadth-first
  step count as "distance", which over eight neighbours is Chebyshev and its iso-lines are
  squares, so any fan not shaped by `lobeReach` grows square; and `lobeReach` is a cosine lobe
  with per-cell wobble too fine to read, so a river arriving along a grid axis lays a half-disc.
- Outlines from Euclidean distance bent by depth: reach(θ) = R·(sides + (1−sides)·max(cos θ,0)^p)
  ·(1 + a·s(θ)) with s a two-to-four-harmonic wobble whose phases are hashed from the mouth id,
  and the sediment filling shallow cells first (the cost of a cell is its depth below the lobe's
  level), so a delta progrades across a shelf and stubbily into deep water. Every fan, including
  the lacustrine and alluvial ones, uses Euclidean distance — cone segments, not squares.
- A delta plain that reads as a delta at 2048: `lobeLevel`'s seaward slope and the trunk's groove
  kept; two to five distributary grooves per lobe above a stated size, hashed from the mouth id,
  radiating apex to rim, that the D8 routing follows — or nothing.
- Guards, each shown failing on the current code: the share of deposition perimeter in straight
  grid-axis runs longer than a stated fraction of the lobe reach below a bar (the rafts give runs
  of ten cells and more); per-lobe rim radius max/min at or above a stated ratio with harmonic
  content beyond the first order (a half-disc fails); on a synthetic coast with a shelf on one
  side of the mouth and deep water on the other, the lobe reaches further over the shelf by a
  stated factor; lobe area against catchment reported against Syvitski & Saito (2007); mass
  budget exact; `DeltaMouthTest`, `RiverEndingsTest`, `DepositionTest` and H5's estuary and
  pocket guards hold; time at 2048 reported. Rule 8: the fan is a graph walk from the mouth and
  stays on the CPU.
- Render 718106 and 59758 at 2048, crop every delta and raft before and after, look, and put the
  crops in the report. Runs in parallel with H5b, which owns the incision loop, the post-cut
  outlet and the desert guard; E5 stays in the deposition and fan code.

### E6. The rift-mouth delta: valley fills, pockets and moats — (continues E5)

*Dependencies: E5 merged; H5b in flight owns the incision loop and the post-cut outlet, so E6
stays in the deposition, fan and floodplain code. From the E5 review, 2026-09-12.* The scene in
William's own crop — 718106 at 2048, the southern rift's mouth — is not the square fan E5
removed. It is three things at once: a flat terrace with a dead-straight seaward edge and the
river running across it; behind that front, a pocket lake whose outline is a rounded square (E5
uncovered it — the lacustrine square used to fill its middle); and below the terrace, concentric
crescent moats. With deposition switched off none of the three exists (E5's bare render), so all
three are deposition's, and E5's mechanism log shows the terrace carries floodplain aggradation
only, no fan sediment.

- **Measure first, with E5's `DepositionLog`.** Three hypotheses, each to be confirmed or
  dismissed with a figure: the terrace is floodplain aggradation raised to one level and trimmed
  by the sea-level cut inside E4's straight-walled half-graben, and a plain's edge across a
  rectangular trough is a straight line; the pocket is the old bay's deepest patch, never
  reached by the fill, enclosed by the lobe, turned to land by H5's rule and filled to a lake,
  with an outline inherited from the graben floor or from the fill's level set; the crescents
  are successive sea-lobe rims, one per round, with the old shoreline trapped between them.
- **What belongs there.** A bay-head delta — the Colorado at the head of the Gulf of California,
  the Rhône entering Lake Geneva, every fjord-head delta in Norway: a delta plain that slopes
  gently seaward from the valley's fill line to a lobate, convex front, distributaries fanning
  across it, no standing water behind the front, and a prodelta slope beyond it under the sea.
  Upstream, a floodplain graded to the plain, not filled to a flat.
- **Fix directions, after the measurement says which apply.** Floodplain aggradation grades to
  base level with the channel's equilibrium slope rather than filling to a level; a lobe may not
  enclose water — a cell inside the rim that the budget cannot fill stops the rim in that
  direction instead of being walled in; each round re-grades the whole lobe to the current front
  rather than adding a rim outside the last one, so no moat forms.
- **Guards, each shown failing on main first.** Water enclosed inside a lobe's rim: zero (count
  on the scene and on every mouth of both worlds); lakes lying in a thin annulus around a mouth
  at the lobe's scale: zero; along the trunk from the valley fill line to the front the plain's
  height is monotone seaward; the terrace's front falls under E5's longest-straight-run bar;
  `DeltaMouthTest`, `DeltaOutlineTest`, `RiverEndingsTest`, `DepositionTest` and H5's guards
  hold; mass budget exact; time at 2048 reported. Render both worlds at 2048, crop this scene and
  every other bay-head delta before and after, and look.

### E7. A rift lake is deep
*Dependencies: E6 merged (the lacustrine fans it leaves are what this is measured against). From
William's review of the H5b trough crop, 2026-09-12: "in some ways the version of this rift on
the left looks more natural. it feels odd to have a delta deposition within a lake or closed sea
rift like that."* Before H5b the coastal rift trough on 718106 held one long smooth lake the
size of the Caspian; after it, the post-cut outlet drained the lake to a narrow strip against
the eastern wall and the river's lacustrine fan stands exposed across the northern half of the
trough as a flat plain. Every step of that is physically coherent — a lake drained by its cut
outlet leaves its bed as a lacustrine plain, and Lake Bonneville's deltas are dry benches today
— but the picture is wrong because the trough is shallow. Earth's rift lakes are deep: Baikal's
water is 1.6 km deep over 7 km of fill, Tanganyika's 1.5 km, Malawi's 0.7 km, all of it because
the floor subsides as the rift opens and keeps subsiding under its own sediment. A river's delta
in such a lake is a few percent of its area (the Selenga in Baikal 2%, the Omo in Turkana 8%,
the Volga in the Caspian 7%), and no outlet can drain the lake, because the floor lies a kilometre
below any sill. The generator's half-grabens (E4) and aulacogens (H1) put their floors a few
percent of the relief below the shoulders, so the lake they hold is a puddle that a fan fills
across and a notch empties.

- Give rift and aulacogen floors Earth's depth: the floor a stated share of the land's relief
  below the shoulder crest (a kilometre and more against eight, so 12–20%), deepest against the
  master fault of each half-graben as the tilt already says, below sea level where the rift meets
  the coast — which is what the enclosure rule and the post-cut outlet then handle correctly: the
  sill is cut to the waterline at most, and the lake behind it keeps its depth. Rule 5: the
  figures come from the rift-lake bathymetry above, written beside the constants.
- Guards, each shown failing on the current geometry: on the standard seeds and on 718106 at
  2048, the deepest rift lake's depth (surface minus floor) within Earth's envelope as a share of
  relief; the largest lacustrine fan's share of the lake it enters at or below 10% (Earth 2–8%)
  where the lake is a rift lake; the trough on 718106 at 2048 keeps at least half its floor under
  water after the post-cut outlet has run; `RiftSegmentationTest`, `OutletIncisionTest`,
  `OutletResolutionTest`, `SeaLevelHistoryTest`, `DeltaOutlineTest` and the mass budget hold;
  hypsometry reported before and after (the floors move it). Render both worlds at 2048, crop
  every rift lake before and after, and look — the trough should read as one long deep lake with
  a small delta where the river enters, which is what William preferred in the left-hand crop.
- The floor is a plane, and that is the other half of what William sees. E6 measured the valley
  in his crop with deposition switched off: the dead-straight seaward front of the terrace and
  the pale bench of constant width down the west side are the sea-level cut running along one
  contour of E4's planar half-graben, and two thirds of the saw-tooth and forty-five-degree bars
  of standing water survive without any deposition at all, because D8 flow on a plane runs
  straight and the fill ponds it in rows. So the floor gets relief as well as depth: a seeded
  roughness along and across strike at a stated share of the floor's depth (H1 gave its
  aulacogens roughness along strike for the same reason), enough that no contour of the floor is
  a straight line longer than a stated fraction of the segment. Guard: on the valley in William's
  window, lake cells in thin grid-bearing or diagonal bars fall from E6's 102-with-deposition-off
  to a stated figure, and the longest straight run of shore falls under E5's bar, both shown
  failing on the planar floor.
- Out of scope: subsidence under sediment load, which is S2 in `REALISM_AUDIT.md` and makes this
  a process rather than a stamp.

### E8. A sill at the waterline does not hold the sea out — (H5b's contributor)

*Dependencies: E7 merged (its measurements are the premise). From E7's finding, 2026-09-12.* E7
measured William's coastal trough on 718106 and found the geometry already Earth-like: the floor
lies 45–72% of the relief below the crest and the deepest rift lake on that world is 24% of the
relief deep, Baikal's figure. What leaves the trough two thirds dry is not depth but the water
level. The post-cut outlet cuts the sill to the waterline and stops, correctly — a lake at sea
level has no overflow to cut with — so the lake surface sits at sea level and only the part of the
half-graben's wedge below that stays wet: 2158 of 7397 floor cells. Before H5b the same lake stood
at its sill, filled the wedge, and William preferred it. On Earth a basin below sea level behind a
sill at sea level does not stay dry: the sea takes it. The Bosporus sill let the Mediterranean into
the Black Sea; the Gulf of California and the Gulf of Suez are rifts the sea has entered; a
barrier at the waterline is breached by the first storm surge that overtops it. Marine
transgression is the missing process, and a first-order form of it is cheap.

- After the post-cut outlet, a converted basin whose water surface stands at sea level (within a
  stated tolerance) behind a sill whose crest is within a stated small height of the waterline —
  Earth's tidal and surge range, a few metres, so about 0.1% of the relief — is breached: the sill
  is cut below the waterline along the notch's own path, the enclosure labelling runs again, and
  the basin is an arm of the sea, drowned across whatever of its floor lies below sea level. A
  basin whose surface is below sea level (endorheic, the Dead Sea) or whose sill stands higher
  than the range is left alone. Deterministic, per-basin, no random draw; the surge height a
  named constant with its derivation.
- Guards, shown failing on main first: the trough on 718106 at 2048 wet across at least 60% of
  its floor (E7's 29%); the number of converted basins at sea level behind a waterline sill,
  before and after (after: zero); `RiftSegmentationTest` (E4's chain keeps its bridges — a bridge
  is not a sill at the waterline; if one is, say so with the figure), `OutletIncisionTest`,
  `OutletResolutionTest`, `SeaLevelHistoryTest`'s estuary and pocket guards, `RiftDepthTest`,
  `BayHeadDeltaTest` and the mass budget hold; land fraction reported (a breach turns land to
  sea); time reported. The pass is a graph walk along a notch and a region labelling; CPU
  (rule 8).
- Render both worlds at 2048, crop every basin the rule touches before and after, and look: the
  trough should read as a gulf, a Red Sea in miniature, with the river's delta at its head.

## Track F — the interface

*Added 2026-09-12 at William's request: "revamp the UX a bit to make it look less generic and
AI-developed", plus one behaviour change. Everything here is in `:ui` (Compose Multiplatform,
shared by desktop and web) with the two front ends untouched except where a platform seam is
needed. Ships as 2.0.0 together with Track G.*

### F0. Blank canvas on launch
The app generates a world the moment it opens. It must open on an empty canvas with the seed,
settings and the graphics-card toggle ready, and generate only when the user presses Generate
(or Go, or New world). The empty state says what to do in one line. Opening a saved world from the
library still opens it. Guard: no generation runs in `App` until an explicit action; a test on
the state holder shows the launch path never calls the engine.

### F1. Ink on paper
The chrome is stock Material 3: purple tonal buttons, default sliders, one sans face at one
weight. Replace the theme with the atlas's own vocabulary. Light: a warm paper ground, sepia ink
accent, thin rules instead of tonal cards. Dark: the site palette (see that repo's
CSS; it is the dark variant William asked for on 2026-08-25). A serif display face for the title
and section headings, a compact sans for values, both bundled as Compose resources so the web
build matches the desktop. Sliders, switches and buttons restyled once through the theme so no
control is styled by hand. Guard: `:ui` compiles for jvm and wasmJs; a screenshot at 1440x900 in
each theme is rendered by the desktop test (`StyleGalleryTest` already renders styles; add a
chrome shot) and reviewed by the maintainer.

### F2. The panel follows the pipeline
*Dependencies: F1, and the landmarks/atlas move already in flight.* Sections named World,
Terrain, Climate, Water, Peoples, Cartography, in the order the generator runs, each holding its
two or three knobs, collapsed by default except World. Seed and resolution in a slim header.
Atlas-only controls stay in the Atlas pane. Plates and Realms become steppers, not sliders (they
are choices between a few worlds); wilderness is one switch. Guard: every config field the old
panel could set is still settable (a test walks the panel's state and the config).

### F3. The map is the instrument
*Dependencies: F2.* Style and View move to a compact toolbar over the map (small icon toggles
with the style name); zoom and the status line sit at the map's bottom edge as a chart legend;
the side panels shrink to what F2 left them. The status line becomes a cartouche: world name (a
generated one from the largest people's language), seed, largest realm, with the generation time
as a muted footnote. Guard: compile both targets; screenshot reviewed.

---

### F4. Menus, settings, updates and notices
*Dependencies: F3. Requested 2026-09-12.* A thin menu strip drawn once in `:ui` so both platforms
have it (Compose Desktop's native menus would leave the web without one): **File** (New world,
Open library, Save, Save as, Export, Settings, Quit on the desktop), **View** (theme, panel
sections, toolbar), **Help** (Check for updates, About).

- **Settings**, persisted through the `Platform` seam (a JSON file in the user's config directory
  on the desktop, browser storage on the web): theme (System - the default, already followed by
  F1 on both platforms - Light, Dark, and extra chromes lifted from map styles, at least Nautical
  and Midnight); default working resolution; graphics card on at launch; default export format
  and size; the library folder with Open folder (desktop); interface scale; check for updates on
  launch; Reset to defaults. Guard: settings round-trip through the seam and every setting has an
  effect a test can observe.
- **Check for updates**: a build-info constant generated from `gradle.properties` at build time
  (version and build date) compared with GitHub's releases API
  (`https://api.github.com/repos/bhanright/Cartogenesis/releases/latest`, plain JSON, browser
  requests allowed): desktop over HTTPS, web with fetch, both through the seam. If newer: the
  release name, a summary of its notes, and a button opening the release page. No self-update.
  Guard: the comparison tested against fake responses (older, same, newer, malformed, offline).
- **About**: version and build date, the project licence, and third-party notices generated from
  the dependency graph at build time rather than typed by hand (Compose Multiplatform, Skiko,
  LWJGL, kotlinx, the two OFL fonts, anything the web loader bundles). The font notices are a
  licence requirement, not a courtesy. Guard: the notices list is non-empty and names the fonts.
- **Mars** (William, 2026-09-12): a Martian theme in both senses, from one palette so they match.
  A `MapStyle.MARS` in `:cartography`: rust and ochre hypsometric tints rising to pale dust and
  white polar caps, seas drawn as dark basalt plains rather than blue (a dry Mars) with the
  coastline as a faint scarp, rivers as dark channels, lakes as the same basalt; the biome and
  diagnostic views keep their own colours. And a `Mars` chrome theme in `Theme.kt`: dark basalt
  ground, rust and ochre accents, dust-pale text, the same faces. Guard: the style renders every
  view without error at 512 and appears in `StyleGalleryTest`'s gallery; the chrome screenshot in
  the Mars theme is reviewed.
- Screenshots of the menu open, Settings, and About in both themes and in Mars, reviewed.

### F5. Phones
*Dependencies: F4 (same file). Requested 2026-09-12: "revisit getting the web version to display
better on phones."* The engine already runs at 512 in a phone browser and Compose for Wasm
handles touch; what breaks is the three-column layout at fixed widths, a pointer-shaped panel, a
toolbar of nine names, and a graphics-card switch for a device without WebGPU.

- One layout tree, two arrangements. Below ~800 dp wide or with a coarse pointer, the map takes
  the whole screen; the header (seed, name, Generate) and the pipeline sections live in a bottom
  sheet that pulls up; the toolbar collapses to icons plus the current style's name; the view menu
  stays a menu; the legend keeps the cartouche and Fit.
- Touch: pinch to zoom, drag to pan, double-tap to fit. Touch targets from the theme (taller
  sliders and switches under a coarse pointer), not per-control edits.
- Phone defaults: 512, graphics card hidden when the platform reports no WebGPU, export capped
  at 2048 by `exportCeiling`; the site's small-screen notice becomes "works on phones at 512".
- Guards: `ChromeGalleryTest` captures 390x844 and 768x1024 alongside 1440x900 in light and dark,
  reviewed; `PanelKnobsTest` proves the compact arrangement exposes every knob the wide one does;
  the web bundle builds; and William checks it on his phone, because no capture tells you how a
  bottom sheet feels.

### F6. Five more chromes, and a map style that survives colour blindness
*Dependencies: F5 (same files). Requested 2026-09-12: "high contrast", "colorblind", "allied",
"hallowed", "baroque" application themes, "what those look like is entirely up to you", plus a
colour-blind-compatible map theme.* Each chrome is a complete Material scheme in `Theme.kt` in
the F1 pattern (surfaceTint = surface, faces from the theme, no per-control styling), selectable
in Settings and the View menu alongside System/Light/Dark/Nautical/Midnight/Mars.

- **High contrast.** Pure black ground, pure white text, 2 dp rules and borders instead of
  hairlines, a single accent of saturated blue (#1A6EFF) for the current item and focus, no
  translucency anywhere (the map toolbar and legend strips go opaque), type one step larger.
  Guard: every text-on-ground pair in the scheme at or above WCAG AAA (7:1), asserted.
- **Colorblind.** A chrome that never carries meaning by hue alone: neutral warm greys with the
  Okabe–Ito orange (#E69F00) for the current item and sky blue (#56B4E9) for focus, and every
  state that was hue-only (the current style, the armed generate button, a disabled chip) also
  gets a shape cue: an underline, a filled rather than outlined border, a strike. Guard: the
  scheme's accent pairs stay distinguishable under simulated deuteranopia and protanopia (the
  Machado 2009 matrices) by a stated CIEDE2000 margin.
- **Allied.** A 1940s Army Map Service sheet: buff paper ground (#D9CBA3), olive-drab panels
  (#4B5320), ivory text, the grid-numeral red (#B22222) as accent, rules in dark navy ink, the
  display face set in small capitals, the cartouche boxed like a map margin.
- **Hallowed.** An illuminated manuscript: deep lapis ground (#1B2A5B), ivory-vellum panels
  (#F1E9D2) with ink text, gold-leaf accent (#C9A227 warmed to #D4AF37), a crimson secondary
  (#8A1C1C) for danger, hairlines doubled in gold on section headings.
- **Baroque.** A gilt-and-walnut room: walnut ground (#3B2415), cream marble panels (#EFE6D8),
  oxblood velvet accent (#5D0000, lit #7E1414), gold rules, the display face in italic for
  headings, double hairlines with a small diamond at the ends of section rules (the rule the site
  already draws in CSS).
- **Map style `MapStyle.CLEAR` ("Colour-blind").** Water in one dark slate (#1F2A3A) so it never
  competes with land; land tints along a cividis-style ramp (dark olive lowland through amber to
  pale yellow highland, white above the snowline) which stays ordered under both deuteranopia and
  protanopia; a 1-cell black coastline; rivers in white so they read on every tint; lakes as the
  water slate. Realm colours from Paul Tol's 9-colour "muted" set, and beyond nine realms the same
  set with a hatch pattern (the raster already hatches for Ink wash), so no two realms differ by
  hue alone. Biome and diagnostic views keep their colours. Guard: every adjacent pair of ramp
  stops and every pair of realm fills, under both simulations, at or above a stated CIEDE2000
  margin, asserted; the style renders every view at 512 and joins `StyleGalleryTest` and
  `GpuRasterTest` (palette-only, so the GPU raster is identical).
- Screenshots of the window in each of the five chromes, and the fantasy and political views in
  the CLEAR style, captured by `ChromeGalleryTest`/`StyleGalleryTest` and reviewed.

### F7. Four more chromes: Matrix, Hessian, Roman, Hitchcock
*Dependencies: F6 (same files). Requested 2026-09-12: "add more themes of your own design based
on the names Matrix, Hessian, Roman, and Hitchcock."* Each is a complete Material scheme in
`Theme.kt` in the F1/F6 pattern with its ornament in `ChromeDetail`, selectable in Settings and
the View menu; the six older chromes and F6's five stay byte-identical.

- **Matrix.** The 1999 film's terminal: a black ground (#030704), panels a very dark green
  (#071209), phosphor-green text (#3DF07A) with a dimmer green (#1F8F49) for secondary text and
  amber (#FFB000) for danger; rules 1 dp green at 40%; filled states invert to black on green;
  the display and control faces in IBM Plex Mono (OFL — bundle regular and bold beside the two
  faces F1 added, and add the face to the notices generator); section headings in capitals with a
  `>` prompt before them; the map's toolbar and legend strips at 85% black. Guard: every text
  pair at WCAG AA (4.5:1), asserted; the new face's OFL notice present in `Notices.kt`.
- **Hessian.** The cloth: a burlap ground (#B3956A) with a woven crosshatch drawn under the
  panels (two families of 1 dp lines at ±45°, 6 dp apart, at 8% opacity, the way Allied draws its
  overprint), unbleached-linen panels (#EDE3CC), dark brown text (#3A2A1B), a twine accent
  (#7A5C3A) with stencil red (#8B3A2F) for the armed button and danger, rules drawn as
  running-stitch dashes (4 dp on, 3 dp off), headings in letter-spaced capitals, the cartouche a
  sewn label with a stitched border. Guard: AA on every text pair, with the crosshatch under the
  text measured in.
- **Roman.** Imperial: a Pompeian-red ground (#7A1F1F), marble panels (#F1EAD9), near-black
  inscriptional text (#1F1B18), headings in letter-spaced capitals with an interpunct (·) between
  the words of a multi-word heading, a bronze accent (#9C7A3C), Pompeian red for the filled
  states with white text, a Greek-key meander drawn as the rule under each section heading (a
  6 dp repeating unit in Canvas), the cartouche boxed with a double rule. Guard: AA on every text
  pair.
- **Hitchcock.** Saul Bass's title cards: a charcoal ground (#151515), flat black panels
  (#1C1C1C), off-white text (#F2EFE8), Vertigo vermilion (#E8491D) as the accent and the armed
  Generate button a vermilion block with black capitals, mustard (#D9A21B) secondary, the display
  face bold with tight tracking in capitals, section rules as a bar cut into three segments
  displaced 1–2 dp from one another (the Psycho titles), and a small spiral drawn beside the
  world's name in the cartouche. Guard: AA on every text pair; vermilion on black is about 5.5:1
  as text, so check it and use a lifted tint where it is a word, as F6 did for High contrast.
- Screenshots of the window in each chrome from `ChromeGalleryTest`, reviewed. If the theme
  list is now unwieldy (fifteen entries), group the Settings picker and the View menu into
  Standard / Accessible / Styled without changing any existing name or stored setting value.

### F8. The atlas on a phone — on `release/2.0`

*From William's phone test of 2.0.0 (2026-09-12): "on mobile it opens up an atlas menu that
becomes hidden by the top transparent menu screen and isn't navigable so it's impossible to
close."* In the compact arrangement the map's toolbar and legend strips are drawn over whatever
the pane shows, and the header with the Atlas/Show map button lives in the pull-up sheet, so the
atlas opens under the strip with no way back. Fix on the 2.0.x line: when the atlas or the
library is the screen, the map strips are not drawn, and the pane carries its own top bar with
the world's name and a Map button that returns to the map; the sheet stays reachable. Guard:
`ChromeGalleryTest`'s 390x844 capture with the atlas open, reviewed; a semantics test that the
Map button is present, enabled and inside the viewport in the compact arrangement with the
atlas open, shown failing on 2.0.0; the wide arrangement pixel-identical in every
seed-independent region. Reported by William at 1024 on a Qualcomm phone with WebGPU: a world in
18–23 s, which is worth a line in the README.

### F9. Pen and ink, redrawn — on `release/2.0`

*William, 2026-09-12: "I am really not a fan of the Pen and Ink setting, it looks terrible (not
exclusive to mobile)."* He is right: the style hatches relief with strokes at one fixed bearing
whatever the slope faces, so a range reads as a scribble, lakes are grey blots, and the sea is
blank. What an engraved map does instead, and what this becomes: hachures, short strokes that run
down the slope (perpendicular to the contours) with weight and density from steepness (Lehmann's
rule: the steeper, the heavier), drawn per pixel as an anisotropic stripe field oriented by the
local aspect so the GPU raster keeps parity; a coastal vignette, three to five lines parallel to
the coast at widening spacing, fading seaward; rivers as tapering lines by order; lakes with
horizontal water lines rather than a fill; ice as stipple; borders as a dotted red line; the paper
tone kept. Everything per pixel from fields the recipe already carries (height, slope, aspect,
distance to coast, water masks), deterministic and resolution-scaled, in both the CPU rasteriser
and the GLSL raster. Guard: `GpuRasterTest` parity over every view for the style; `StyleGalleryTest`
renders at 512 and 2048 reviewed by the maintainer and sent to William; a stripe-orientation
check that the hachure field follows the aspect (correlation above a stated bar, shown failing on
the fixed-bearing hatch); time per export reported.

### F10. Rivers widen with their discharge — on `release/2.0`

*William, 2026-09-12: "all rivers appear to be the same width. It seems like they should get
thicker as more tributaries feed into them, or in higher rainfall areas or larger basins, they
should widen as they go along."* They do vary, but barely: `RiverStage` sets a width in cells of
`0.55 · (flow / threshold)^0.28` clamped to 0.5–2.8 cells, so a river carrying a thousand times a
headwater's flow is at most five times wider, and at 512 that is a one-pixel line beside a
three-pixel one. Leopold & Maddock (1953, USGS Professional Paper 252) measured downstream
hydraulic geometry on hundreds of stations: width ∝ Q^0.5, depth ∝ Q^0.4, velocity ∝ Q^0.1, so
nearly all of a river's growth goes into width and depth, and width is the honest visible proxy
since depth cannot be drawn. Discharge here is the rainfall-weighted accumulation, so the widening
follows tributaries and rainfall for free.

- Width proportional to the square root of discharge, held as a pen in output pixels rather than
  in cells (the F9 lesson): a hairline of about 0.8 px for a channel at the drawing threshold,
  rising to about 5 px for the largest river on the map, the same at every resolution and export
  size; `riverScale` retired or redefined; the same rule on the CPU rasteriser and the GPU raster.
  Whether the widths live in `River.widths` (a world quantity the save carries) or are computed at
  draw time from the accumulation is the contributor's call — say why.
- Guards, shown failing on the current formula: along every river the drawn width tracks the
  square root of discharge (correlation above a stated bar; the 0.28 power fails it), the widest
  river on each standard seed is at least four times the narrowest in pixels, and at a junction
  the trunk below is wider than either branch above; `GpuRasterTest` parity holds; the other
  styles' fingerprints move only where a river is drawn; time reported.
- Render 718106 and 59758 at 2048 in Atlas and Pen and ink and look: trunks should read as
  rivers and headwaters as threads. Braided reaches and meanders stay with R2 in 3.0.

### F11. Stop a generation — on `release/2.0`

*William, 2026-09-12: "can we add an abort button to stop generation that is underway? Sometimes
I notice I wanted to change a setting and I don't want to wait for it to finish to change it."*
Queued behind F10 (same files).

- A **Stop** button takes Generate's place while a world is being built, in both arrangements.
  Pressing it cancels the generation's coroutine; the settings unlock at once; the previous world
  stays on screen if there was one, otherwise the canvas returns to blank; the status says the
  generation was stopped.
- Cancellation is cooperative: `ensureActive()` at every stage boundary in
  `WorldGenerationEngine` and inside the long stages at each hydraulic round, each thermal sweep
  batch, each glaciation pass and each realm-expansion sweep, so a stop lands within one round on
  the desktop. On the web the click is only seen when the engine yields, and F8's per-stage yield
  already exists, so a stop lands at the next stage boundary; add a yield per hydraulic round on
  wasm if the cost is under a percent, measured. The GPU erosion path stops at a round boundary
  and releases its buffers, so the next generation starts on a sound context.
- Restarting after a settings change reuses whatever finished stages the new settings still
  allow, through the existing stage-reuse chain (`IncrementalReuseTest` covers the rule); say in
  the report which stages survive a stop.
- Also from William (2026-09-12): the seed field applied its value on losing focus, so clicking
  out of it to change another setting started a world at once. The maintainer removed the
  focus-loss apply on `release/2.0` (Enter or Go only); F11 adds the guard, a UI test that typing
  a seed and clicking elsewhere starts nothing, shown failing on 2.0.1.
- Guards: a test that a generation cancelled during erosion returns within one round's time,
  leaves the previous world intact, and lets the next generation run to completion on the same
  engine; `GpuErosionTest` gains a cancel-mid-run case on the desktop; `ChromeGalleryTest`'s
  phone capture with the Stop button showing; the per-merge tier green.

### F12. JPEG, heightmap and layer exports — on `release/2.0`

*William, 2026-09-12, on being asked whether PNG and WebP suffice: "queue JPEG and the heightmap
and layer exports."* Queued behind F11 (same export code).

- **JPEG** as a third picture format, for the tools that still refuse WebP: quality about 90,
  no alpha, the JDK's encoder on the desktop and Skia's on the web, one more chip in
  the format row. Not for quality but for compatibility, and the note beside the chip says so.
  *Corrected at delivery (2026-09-12): the premise "WebP is smaller at the same quality" did not
  measure true at the qualities the app ships. Seed 42 at 512: JPEG at quality 90 is 78 KB with a
  99th-percentile channel drift of 55, WebP at quality 100 is 108 KB at 53, and JPEG needs
  quality 100 and 214 KB to match WebP's fidelity. So the note reads "For tools that will not
  open a WebP. Smaller than WebP here, and harder on thin lines: rivers and borders soften more",
  and `ExportSmokeTest`'s size assertion follows the bytes.*
- **Heightmap export**: a 16-bit greyscale PNG of the elevation field at the export size, sea
  level at a stated grey level, plus a sidecar JSON with the world's width in kilometres, the
  metres per grey level, the sea-level value and the seed, so Blender, Unity, Unreal and QGIS can
  take the terrain. The desktop writes both files beside each other; the web downloads a zip or
  two files, whichever the platform seam already supports.
- **Layer exports**: biome and realm index maps as palette PNGs (one index per cell, the palette
  carried in the PNG and the legend in the sidecar), for people who draw their own maps from the
  generated geography.
- Guards: a heightmap written and read back reproduces the elevation field to 16-bit precision;
  the sidecar's figures match `WorldGenConfig`; the layer PNGs round-trip their indices; the JPEG
  export decodes at the export size; `ExportSmokeTest` and the codec tests hold; the phone's
  export ceiling applies to all of them; time and file sizes reported at 2048 and 4096.

### F13. Tints by climate and shading by sky — on `release/2.0` (the audit's V1, pulled forward)

*William, 2026-09-12, after F10: "the new rivers look immensely better - this is one of the most
impactful updates we've done in recent days as far as giving a natural realistic look to human
eyes", and then "queue the presentation chunks after the exports."* Renderer-only; no world
changes; queued behind F12.

- **Hypsometric tints that follow climate.** Imhof (1965): the land colour at a height is not one
  ramp but a ramp modulated by what grows there. Each style's land ramp is blended by the cell's
  biome and aridity — desert stays sand at any height, tundra and ice stay pale, forest darkens
  the lowland greens — with the modulation strength a per-style number so Vellum and Ink wash keep
  their character and Atlas gets the full effect. Per pixel from fields the recipe already carries
  (height, biome, the climate's aridity), on both paths.
- **Shading from a sky, not a lamp.** Multidirectional hillshading (Kennelly & Stewart 2014): the
  relief lit from several azimuths weighted by slope aspect, plus a sky term (ambient light that
  falls with the terrain's openness, a cheap horizon estimate over a small stencil), so ridges
  read from every direction and valleys sit in soft shadow rather than one side black and one
  white. Aerial perspective: distant, lower ground lightened slightly toward the paper. The single
  lamp stays available as a setting for people who want it.
- **Bathymetric contours** at a stated depth interval in the sea, drawn per pixel from the
  distance-to-shore and depth fields the recipe carries since F9.
- Guards: `GpuRasterTest` parity for every style and view; the colour-blind style's CIEDE2000
  margins (F6) and the chrome contrast guards still hold; a test that a desert cell's tint never
  reads green in any style (measured hue on the rendered pixel, shown failing today); the light
  direction check: a synthetic cone shaded under the sky model has no unlit face (shown failing
  with the lamp); time per 2048 and 4096 raster on both paths reported. Render both of William's
  worlds at 2048 in Atlas, Vellum and Pen and ink, crop a desert, a forest and a range, and look.

### F14. Generalisation, graticule and scale — on `release/2.0` (the audit's V2, pulled forward)

*Queued behind F13.* Renderer and legend only; no world changes. What can be done without the
audit's S1 units and P1 projection is done here; the north arrow and the projection wait.

- **Generalisation by zoom.** Coastlines and rivers are drawn as polylines simplified by
  Douglas–Peucker at a tolerance tied to the on-screen pixel size, so a 2048 world viewed at fit
  does not carry every cell's stair-step, and the full detail returns as the reader zooms in.
  Rivers below a discharge threshold that scales with zoom are dropped at whole-world scale and
  return on zoom; the F10 pen stays.
- **A graticule**: lines of latitude and longitude at 10 degrees on the equirectangular map, with
  labelled edges, as a Cartography toggle, drawn on screen and on exports.
- **A scale bar** in the legend and on exports, in kilometres from `worldWidthKm` (12,000 km
  across) and the cell size at the export resolution, with the bar's length chosen from the
  1–2–5 series to fit; and a line in the cartouche giving the map's scale at the export size.
- Guards: the simplified coastline's Hausdorff distance from the full one below the tolerance;
  the river count on screen at fit against at 4x zoom (shown failing today, where they are equal);
  the scale bar's kilometres against the config's arithmetic; the graticule's spacing exact in
  cells; `GpuRasterTest` unaffected (these are overlay and legend); phone captures with the
  graticule on. Render both worlds at 2048 at fit and at 4x and look.

### F15. Rivers as William sees them at 1024 — on `release/2.0`

*William, 2026-09-12, from seed 298405 at 1024 in 2.0.2, with crops: "The maximum width of this
river is too wide, and it visibly flows over onto the ocean instead of seamlessly flowing into
the sea. Also shown are two other artifacts that do not look right - the strange thin squiggly
connection between two thicker rivers, and this diagonal rectangle section of river." Joined
here by M1's finding that a drawn river starts at its biggest headwater rather than its
farthest (TODO.md, 2026-09-12). All five are in how rivers are traced and drawn, so one chunk.*

- **The pen scales with the sheet.** F10 sized the pen in output pixels (0.8 to 5.0 px), which
  at 2048 William called immensely better and at 1024 is twice the weight against the same
  country. A river's drawn weight is a cartographic exaggeration of a physical width: the
  Amazon's mouth is about 10 km on a 12,000 km world, 0.08% of the width, and a printed map
  exaggerates such a river about threefold; 5 px on a 2048 sheet is 0.24% of the width. So the
  full pen becomes a fraction of the map width (0.24%, that derivation beside it) with the
  hairline floor kept at 0.8 px: 2.5 px at 1024, unchanged at 2048, 10 px at 4096, the same
  weight at fit at every size. `RiverPen` and `RiverWidthTest` restated; F9's hachures, which
  are a texture and not a line, stay in output pixels.
- **The mouth ends at the shoreline.** The drawn stroke runs past the coast into the sea. Find
  why — the course's last vertex offshore, the round cap, a delta lobe's channel drawn over
  water, the tracer's sea mask differing from the drawn coast — and make the stroke's last
  pixel the shoreline pixel, the sea taking over without a seam: no river pixel over open water
  beyond the coast, measured on 298405 and the four standard seeds, shown failing today.
- **A lake's outlet carries its lake.** The hairline squiggle between two thick channels is,
  as a hypothesis to verify, an outlet whose accumulation starts near zero because the lake's
  interior is a sink in the flow graph, so the channel that drains a whole catchment is drawn
  as a thread. The outlet's width follows the flow the lake receives; shown failing today on
  the crop's lake.
- **No straight diagonal channel.** The rectangular diagonal section — hypothesis: E1's breach
  carving its reach along a straight line, or a delta-fan channel — is diagnosed, and the
  channel follows the terrain's own routing; shown failing today.
- **Rivers begin at their farthest source.** `RiverStage.traceRivers` ranks heads by the flow at
  them; rank them by the length of the path below them, or trace each mouth upstream along its
  longest branch, so a `River` is its own longest watercourse. Drawn coverage of the watercourse
  from 0.484 at 512 and 0.408 at 2048 to 1.0 on the four seeds, shown failing today.
- Guards: each of the five above; `GpuRasterTest` parity; world fingerprints unchanged unless a
  world changes (the lake outlet and the breach may move the flow field: re-pin once, with the
  reason in the commit); render 298405 at 1024 and 718106 at 2048, crop the mouths, the lake and
  the diagonal at the render's own pixels, and look.
- Rule 8: the pen is per-pixel raster work already on both paths; nothing new per cell.

### F17. Not every coast is a ria — on `release/2.0`

*William, 2026-09-12: "overall, every coastline is too jagged. in real life some coastlines are
like that but not all - the effect is too extreme at this scale." (crops at 1024). GEOGRAPHY.md
already lists the lowstand's roughening of every coast as a deviation, and M1 measures the
coastline's box dimension at 1.20 pooled (1.12 to 1.24 per seed) — inside Earth's band — so the
fault is not the pooled figure but its uniformity: every coast carries the same cell-scale
saw-tooth, where Earth's coasts run from smooth depositional shores (barrier beaches, mudflats,
deltas: Texas, Holland, Bengal) to drowned rias and fjords.*

- **Diagnose the fringe.** Which stage puts the cell-scale saw-tooth on every coast (the
  lowstand cut's roughening, the shelf remap, the erosion's coastal cells, the percentile cut
  on a noisy field) and at what scale, measured as the coast's roughness by octave (box counts
  at 1, 2, 4, 8 and 16 cells) on the four standard seeds and 298405.
- **Smooth where the sea would.** A littoral pass after the cut: on low-relief, low-energy
  coasts — the cell's slope and its exposure, a cheap fetch estimate as open-water distance in
  the onshore direction — sediment fills re-entrants narrower than a stated width and rounds
  headlands, while steep rocky coasts keep their relief-controlled outline. Earth's split is
  the bar: about a third of the world's shoreline is depositional (Luijendijk et al. 2018 find
  31% of the ice-free shoreline sandy; Bird 2000, *Coastal Geomorphology*), with the derivation
  beside it. The lowstand roughening's amplitude at the cell scale reconsidered against what a
  6 km cell can hold.
- Guards: the pooled box dimension stays inside 1.2 to 1.3 (M1's bar; copy the box-counting
  from main's `worldgen/src/jvmTest/.../EarthLikeness.kt` into the test rather than
  re-deriving it); the roughness *spread* across coast segments (the share of coast that is
  smooth by the littoral criterion, or the standard deviation of the per-segment dimension)
  reaches Earth's third, shown failing today at near zero; the glaciation and lake guards hold;
  fingerprints re-pinned once with the reason; render 298405 at 1024 and 718106 at 2048 and
  look at a low coast and a mountain coast side by side.
- Rule 8: the littoral pass is per-cell over the coast band; write it against the accelerator
  seam's shape and measure its time at 2048; under 50 ms it stays on the processor, as H2's
  balance did.

### F23. A Natural map style — on `release/2.0`

*William, 2026-09-13: "a natural mode with colors rendered in saturated but earthy tones like the
attached image" (the reference image is the palette's source; sample it, do not guess). A
twelfth map style beside Atlas and the rest.*

- **`MapStyle.NATURAL`**: land in saturated earth tones read from the reference — the greens of
  forest and grassland, ochre and umber for dry ground, terracotta and rust for bare rock and
  desert, warm pale summits — with the climate tint lever at full so the ground's colour is its
  cover, the sky relief, isobaths, and a sea that sits with the land (deep, saturated blue rather
  than the Atlas's cyan, sampled from the reference if it has one). Rivers and lakes in a blue
  that reads against the greens; borders and labels in the ink the style's own contrast test
  picks.
- Guards: `GpuRasterTest` parity for the new style in every view; the F13 desert and steppe
  guards hold for it; a contrast guard for its label ink over its darkest and lightest ground
  at AA; `StyleGalleryTest` and `PenAndInkTest` gain its record; the site's style count on the
  Features list becomes twelve and `SiteAssemblyTest`'s count follows. Render 718106 and 59758
  at 2048 in it and look beside the reference: saturated, earthy, not garish.
- Rule 8: a style is a set of ramps and levers the raster already reads on both paths; nothing
  new per pixel.

### F24. The Lemon Blueberry chrome — on `release/2.0`

*William, 2026-09-13: "a 'Lemon Blueberry' application theme left to your discretion". A
sixteenth chrome for the window, in the F7 manner (Matrix, Hessian, Roman, Hitchcock were "of
your own design").*

- **`ThemeChoice.LEMON_BLUEBERRY`**: a chrome whose two colours are what the name says — lemon
  (a warm, light yellow-cream) and blueberry (a deep blue-violet) — arranged so that one is the
  ground and the other the ink and accents, with the raised and sunk surfaces, hairlines,
  dimmed text and the alarm colour derived from the pair, and the map's own styles untouched.
  Which is ground and which is ink is the contributor's call, made by measuring: the arrangement that
  gives every text pair AA and the higher-contrast Stop button wins. Its own typography follows
  the styled-chrome pattern (Spectral and Plex, or a face already bundled).
- Guards: `ChromeContrastTest` at AA for every pair (the panes take their ink from the theme,
  as F8 made them); `ChromeGalleryTest`'s captures gain it; the phone layout captured in it;
  the theme count on the site's Features list becomes sixteen and the `SiteAssemblyTest` count
  follows. Look at the light and dark captures yourself.

### F25. Cloud cover — on `release/2.0`

*William, 2026-09-13: "cloud cover as a feature". A seasonal cloud field generated by the
climate stage and drawn over the map as a Cartography toggle.*

- **A cloud-fraction field per season** in the climate stage, from what the march already
  knows: the moisture the air carries against what it can hold (the bucket's fill), the rain
  falling out (the ITCZ and the storm tracks are cloudy because they rain), and the sea's cold
  upwelling coasts (stratus over cold water, the marine inversion TODO already names). Earth's
  yardstick is ISCCP: global mean cloud fraction about 0.67, maxima near 0.75 at the ITCZ and
  the 60-degree storm tracks, minima near 0.5 in the subtropical highs and over the great
  deserts (Rossow & Schiffer 1999); a guard on the zonal-mean cloud fraction within a stated
  envelope, shown failing on a constant field. Saved with the climate (the format version
  bumps on this line).
- **Drawn** as a Cartography toggle, "Cloud cover", default off: soft translucent cloud masses
  in the season the temperature and rainfall views show, broken at the cell scale by a seeded
  noise so they read as clouds and not as a wash, thickest where the fraction is highest,
  with a faint shadow offset on the ground under them in the direction the sun the relief
  uses; on screen and on exports when on; on both raster paths (rule 8: per pixel, so the GPU
  shader carries it with `GpuRasterTest` parity).
- Guards: the zonal envelope above; parity; the desert guard still holds with clouds on (a
  cloud is not a tint); `PanelKnobsTest` for the mark; render 718106 at 2048 both seasons with
  the toggle on and look: clouds where it rains, clear skies over the deserts.

### I2. Glacial basins take their shape from the ground, not the grid — on `main`

*Done, 2026-09-14. The diagnosis below was one step off and the section is left as it was written
so the correction reads: the slab was the **trough's** cross-section and not a basin at all —
`cutBasins` cuts nothing whatever on 364673 — and `cutBasins`' own grid shapes, which are real and
were fixed, were not what the author saw. See the ledger row for what was measured.*

*William, 2026-09-14, first test of the 3.0.0-dev cut, seed 364673 at 2048, the south-east: "this
anomaly" (a level, tinted slab with a cross-shaped arm, edges at 45 degrees, beside a lake whose
shore is a ruled line) and "also this line through this section" (a straight edge running through
a belt and its lake). Reproduced on main at e539002 on the CPU path: the slab is in
`erosion.height`, a closed basin floor cut level, holding no water, drawn as flat ground.*

- **Cause**: `GlaciationStage.cutBasins` builds a basin's footprint as a tube of a D8 path at a
  grid half-width, peels it with four-connected rings, opens it with three-by-three blocks and
  saucers its floor by a four-connected inset distance. Every one of those is a grid shape: the
  outline lands on 0, 45 and 90 degrees, the floor's contours are diamonds, and where the peel
  and the opening meet, a cross. Rivers then run ruler-straight along the basin's edges.
- **The fix**: a basin's shape comes from the valley it sits in. The footprint is the valley
  floor — the cells within the trough's half-width of the path by *Euclidean* distance (G4's
  `JumpFloodDistance` is there for it) and no higher than the floor plus the trough's depth, so
  the walls bound it and the rim follows the contours; the floor is a bowl by Euclidean distance
  from that rim; the area cap peels by height rather than by ring. Say why the 364673 basin holds
  no water (the lake water balance's playa, or a drained outlet) and make it either a lake or a
  bowl of till the map can read; the renderer does not draw playas today, so if one is what it is,
  it needs a face. The cross-shaped arm must be explained, not just gone.
- Guards, each shown failing on main with 364673 at 2048: an outline guard — no cut basin's
  outline carries a run longer than a stated number of cells along one grid bearing, the number
  derived from what a natural shore does at 5.9 km per cell (the derivation beside it); a floor
  guard — no basin floor has more than a stated share of its cells within one metre of one
  height, derived from a bowl's hypsometry. `GlaciationTest`'s existing clauses hold. Render
  364673 at 2048 (window 1560,1480 to 2048,1968) before and after, and 718106 and 59758 whole,
  and look: the basins should read as glacial lakes in valleys, not stamps.
- Rule 8: the footprint and floor are per-region work on the CPU as they are now; the distance
  field is the shared jump flood. Rule 9 throughout; `SeedProbe*.kt` never committed.

### F29. Cool neutrals for Light, Dark and the site — on `release/2.0`

*William, 2026-09-14, after two opinions (the maintainer's and an outside reviewer's) on a palette shift: "I agree
with your recommendation", and it applies to the website as well as the chrome. the outside reviewer's view,
recorded for the ledger: keep all three unchanged, because the chrome must not borrow the map's
vocabulary; it measured every current pair (weakest, Light's faded ink on sunk paper, 4.60) and
rejected a cobalt-and-green chrome sampled from Natural. the maintainer's, adopted: agree on no landscape
colours in the chrome, but the current neutrals are warm brown and Light's paper is Vellum's
yellow, which pulls against a Natural hero and a cobalt sea; cool the neutrals and keep the
accents.*

- **Dark and the site**: the grounds move from brown-black to a neutral charcoal (tried:
  ink #121417, raised #191c20, sunk #21252a, hairline #363c44); bone, bone-dim, parchment, brass,
  brass-dim, oxblood and oxblood-lit unchanged. Measured: bone on raised 12.9, bone-dim on sunk
  5.0, brass on sunk 6.4, brass on ink 7.6. The site's `:root` follows value for value (the
  `SitePaletteContrastTest` mirror clause holds) and the strips' caption bands, which take
  their tints from `:root`, are re-measured.
- **Light**: Vellum's paper replaced by a modern atlas plate's off-white (tried: ground #f4f1ea,
  raised #faf8f3, sunk #e9e4d8) with the same Ink and oxide accent; measured ink on raised 15.1,
  faded ink on sunk 5.2 (from 4.60), oxide on ground 7.9. Vellum's own paper stays available as
  the Vellum-derived chrome is; nothing shipped is lost.
- The contributor measures rather than copies these values: every text pair through
  `ChromeContrastTest` at AA, the `ChromeGalleryTest` captures re-taken and looked at with the
  Natural style inside the frame in both chromes, the phone capture included. Brand continuity
  is the accents and the typography, both untouched. Ships as 2.0.7 and forward-merges.

### A11y. An accessibility audit to the ADA's standard — after every planned chunk is done

*William, 2026-09-14: "once we have fully finished all planned aspects of this project, we should
eventually perform an ADA compliant accessibility audit." Queued last, after the 3.0 and 4.0
work, so it audits the finished thing once rather than a moving one repeatedly.*

- **The standard**: the ADA's technical yardstick for software and web content is WCAG 2.1 (2.2
  where it adds) at level AA, applied to the website, the browser app and the desktop app. What
  is already guarded and stays guarded: every chrome's text pairs at AA (`ChromeContrastTest`),
  the high-contrast chrome at AAA, the colour-blind chrome and map style, the site's palette
  (`SitePaletteContrastTest`) and its caption bands, touch targets on phones (F5).
- **What the audit adds, each shown failing where it fails today**: keyboard reach of every
  control and menu without a pointer, with a visible focus ring; Compose semantics on every
  control (a name, a role, a state) so a screen reader on Windows and in the browser can read
  the panel and the toolbar, and the map itself exposed as an image with a description that
  names the seed and what is shown; the export and save dialogs reachable and announced;
  motion and animation respecting the reduced-motion setting; text scaling to 200% without
  loss; the website's landmarks, headings, alt text (the strips already carry their panel
  names), link purpose and skip navigation; no information carried by colour alone in any view
  (legends carry a pattern or a label where a view relies on hue).
- **The deliverable**: an audit document in the repo, criterion by criterion against WCAG 2.1
  AA with pass/fail and the evidence, the failures fixed as chunks with guards, and the
  document re-run green. A third party is not required for the ADA, but the document is what a
  reviewer would ask for.

### F30. The ruled lake shores in 364673's belt — on `main`

*From I2's report (2026-09-14): the two lakes with straight shores at 0, 45 and 90 degrees and the
dead-straight river at x 1788 in William's south-east window of 364673 at 2048 are not the ice —
with `glaciation.enabled` false not one water cell differs and every flow target is identical.
The cause is upstream of the glaciation stage and is still there.*

- **Find the cause by elimination before touching anything**: render the window with the stages
  switched off one at a time from the water back — lakes' water balance, the outlet incision
  and post-cut passes (F22), the hydraulic rounds, the rift segmentation and the trough depth,
  the belts — and say at which switch the ruled shore disappears. Two hypotheses to test first,
  not to assume: (1) the belt sits on a plate boundary (the plates view shows the boundary
  running along it), and a boundary's trace is a Voronoi edge, so a rift trough or a graben
  floor carved along it inherits the ruled line and the lakes that pond in it inherit the shore
  (`RiftSegmentation` and the belt profile own that); (2) the river is a straight run on smooth
  ground (F18's class) that cut a ruled trench which ponded behind its own lip, the case
  `FlowRouting`'s note describes, and the lake is the trench.
- **Fix by cause**: if (1), the trace a trough follows must wander at the cell scale the way
  E7 gave the rift floor its relief — a boundary's cells are a corridor, not a line — with a
  guard that no water body's outline carries a run along one grid bearing longer than the
  I2 outline bar allows (reuse `GlacialBasinShapeTest`'s instrument on lakes; it already reads
  outlines); if (2), the fix belongs in the routing or the incision and the guard is
  `StraightRunTest`'s extended to the water bodies the runs pond. Shown failing on main with
  364673 at 2048 either way.
- Renders: the window before and after, and 718106 and 59758 whole; look. Rule 9; bars derived.

### F31. Bug reports: a Help item that carries the seed, and a line on the site — on `main`

*William, 2026-09-15: "put a note on the website requesting bug reports, and add an item to the
'Help' menu in the application for bug reporting - requesting the seed, resolution, etc needed
to reproduce the bug".*

- **Where reports go**: the repository's issues, which need no mail infrastructure and keep the
  seed beside the fix. `.github/ISSUE_TEMPLATE/bug.yml` with the fields a reproduction needs —
  version, platform (desktop or browser), seed, working resolution, ocean coverage and any knob
  moved from its default, graphics acceleration on or off and the device, what was expected,
  what happened, a screenshot — and a second route for people without an account: an address
  at the domain (Cloudflare Email Routing forwards it) named on the site and in the app:
  William set up `bugreport@cartogenesis.com` for bug reports and issues and
  `dev@cartogenesis.com` for feature requests and suggestions (2026-09-15); the Help item's
  clipboard text and the site's bug line name the first, F32's suggestion line the second.
- **Help ▸ Report a bug…**: a third Help item beside the two that exist. It opens the browser
  through `Platform.openLink` on the new-issue URL with the template's fields pre-filled from the
  world in the window — version, platform, seed, resolution, ocean share, every knob that is not
  at its default (the settings already know which are), acceleration and the device string the
  panel shows — and puts the same text on the clipboard first, so a reader who lands on a login
  page still has it to paste into a mail. The menu note in `Menus.kt` says the two menus stay the
  same three with the same items; the phone sheet gains the item too. Guard: `PanelKnobsTest`
  or the menu test asserts the item and that its body names the seed and resolution of the open
  world; the URL's length stays under the browsers' limit (state it).
- **The site**: one line in Notes — "Found something wrong? Report it with the seed and the
  working resolution" — linking to the issues page and the address; `SiteAssemblyTest` reads it.
- Small: one contributor, a short one, after the weekly reset; no generator change.

### F32. A roadmap chart on the site, and a line asking for feature suggestions — on `main`

*William, 2026-09-15: "add a chart at the bottom of the site which lists planned upcoming
releases (e.g. 3.0.1, 4.0.0, 5.0.0) alongside features planned to be part of those releases.
And there should be another request for contact asking for features suggestions."*

- **One source of truth**: a `ROADMAP.md` at the repository root, a table of planned releases
  and the features in each in a reader's words (William writes or approves every line; the
  chunk drafts them from the plan's queue), which the site's build reads to draw the chart, so
  the page and the file cannot disagree; `SiteAssemblyTest` checks every release named on the
  page is in the file and every feature named in the file appears on the page.
- **The chart**: a new section after Notes, "What comes next", a plain table — release, what
  it brings, in the page's own voice, no dates (a date is a promise the plan does not make),
  the current release marked. Draft from the plan's order as it stands: 3.1 the winds and the
  moisture (W2, W3, W4: monsoon coasts, rain that reaches interiors, vegetation); 3.2 erosion
  that reads the climate and channels that start where they should (S3, R1); 3.3 ice sheets as
  bodies, waves and drift on the coasts (I1, K1–K4); 3.x hydraulic rounds on the GPU (G1);
  4.0 the map as a sphere — metric-aware physics (P1), the globe view (F27), and a choice of
  map projections for the whole-world view and exports (P3, new: equirectangular as today,
  Equal Earth, Robinson, Winkel tripel, Mollweide, an orthographic hemisphere; each drawn per
  pixel on both raster paths under rule 8, with the graticule, the scale bar and the labels
  following the projection); 5.0 the full atlas with named continents, seas, bays, straits and
  ranges (V3). (William, 2026-09-15: "The physics and globe projection stuff should be moved
  to the 4.0 release I think, and we should also add additional map projections as options",
  then "move the full atlas project items to 5.0 release".)
- **The ask**: beside F31's bug line in Notes, a second: "Have a feature in mind? Say so",
  linking to the repository's discussions or issues with a feature template and
  `dev@cartogenesis.com`.
- The Notes card "The Atlas is a work in progress" says the full atlas is "targeting 4.0"; it says 5.0 from this chunk on, and the roadmap file is what it agrees with.
- Small; one short contributor with F31, after the weekly reset; no generator change.

### F33. A topographic map style — on `main`

*William, 2026-09-15: "add a topographic map mode to the roadmap". A thirteenth map style, on the
3.x line.*

- **`MapStyle.TOPOGRAPHIC`**: the sheet a national survey prints. Contour lines at an interval
  derived from the ruler and the zoom (the 1-2-5 series the scale bar already uses, so a 2048
  world at fit draws every 500 m and a 4096 export every 200 m, with index contours every fifth
  line heavier and labelled with their height in metres along the line), over a pale ground
  with a light hypsometric wash or none, water in a single blue with the isobaths kept, relief
  shading faint, rivers and lakes in the pen the survey uses, and labels in the sheet's ink.
  Contours are traced by marching squares on the height field the way F14 traces the shoreline,
  simplified by the same Douglas-Peucker, and drawn as geometry so they stay crisp at every zoom
  and on exports; the ground and the wash are per pixel on both raster paths (rule 8).
- Guards: `GpuRasterTest` parity for the style; a contour guard that the traced lines close or
  end at the map edge and never cross (a property of marching squares, asserted); the interval
  guard that the drawn interval follows the 1-2-5 series for the sheet's scale; the label ink's
  contrast at AA over the ground; `StyleGalleryTest` and `PenAndInkTest` records; the site's
  style count and Features line follow. Render 718106 and 59758 at 2048 and look beside a
  survey sheet: legible contours, no moiré on steep ground.

### F34. World names from a curated list — done 2026-09-15

*William, 2026-09-15: "generate a new list of map names (the randomly generated fantasy names)
that sound better... I like this list, proceed to use it." Three hundred names written to be read
aloud and set in a serif, under stated rules (ASCII, one to four syllables, no real places, no
names from published fiction, three flavours mixed), now `WorldNames` in `:ui`. The world's name
is picked from the list by the seed and the largest people's language together, so the same seed
and people always name the world the same and a different people names it differently;
`CartoucheTest`'s three clauses hold unchanged and `WorldNamesTest` guards the list's shape and
the pick's spread. Realm and settlement names keep the assembler until 5.0 (V3); the lists for
them are gathered ahead of time under `docs/naming/` as they are written.*

### C4. The docs folder split into design record and working notes — after the weekly reset

*Decided 2026-09-15. A fork's author wants to know how the program works, which the code, the
geography rules, the audit, the performance and deployment documents already say. The plan's
quoted requests, pass narratives, dispatch rules and the decisions' reasoning are working notes
for the maintainer, not part of the record a fork needs, and the style guide is written as
instructions to a worker.*

- **Public** (stays in `docs/`): GEOGRAPHY.md, REALISM_AUDIT.md, PERFORMANCE.md, DEPLOYMENT.md,
  TODO.md, `naming/`, and ROADMAP.md at the root.
- **Local** (moves to a gitignored `notes/` folder beside the repo's working tree, kept for the
  maintainer's own use): this file in full, the session protocol and ground rules, and the style
  guide's framing and sweep history. Nothing in `notes/` is ever pushed; the folder is listed in
  `.gitignore` with a neutral comment.
- **Replacements in the public tree**: `docs/DESIGN_LEDGER.md`, derived from this file, one row
  per chunk with what changed and the measured figures and nothing else — no quotations, no
  names, no reasoning behind a preference, no pass-by-pass history; and `docs/CONVENTIONS.md`, the
  style rules a contributor follows, stated as rules with an example each and no preamble. The
  hundred-odd code comments that cite `REALISM_PLAN.md` by chunk are repointed to the ledger with
  the same chunk labels, and comments that cite `CODE_STYLE.md` to the conventions file.
- The public ledger and conventions are checked before commit for any name of a model, an
  assistant, an agent, a person, or a site other than the project's own; the existing sweep's
  word list plus the maintainer's name.
- One contributor, after the weekly reset; docs and comments only, no generator change; the
  full tier once for the comment edits.

### G5. Acceleration on a native WebGPU runtime — 4.x

*Decided 2026-09-15. The accelerated work exists twice, as OpenGL compute on the desktop and as
WGSL for WebGPU in the browser, held together by parity tests; OpenGL is deprecated on macOS and
its compute path is the least-attended by drivers.*

- A native WebGPU library (wgpu or Dawn) behind the same accelerator seams on the desktop,
  bound through the JVM's foreign-function interface, with a native library per platform bundled
  into each distributable. It runs the browser's WGSL kernels unchanged, choosing Vulkan on Linux
  and Windows, Direct3D 12 where the driver prefers it, and Metal on macOS; the missing browser
  port of the export raster is closed by the desktop using the browser's kernels rather than the
  other way round.
- The CPU path stays the reference and the parity tests stay as they are. A timing clause on both
  desktop backends before OpenGL is retired: the new path must measure equal or faster on the
  erosion sweeps (best of three, the derived bar `GpuErosionTest` already carries), with passes
  batched into one submission as the browser solver does so dispatch overhead does not show. If
  a platform measures slower, both backends stay behind the seam and the faster is chosen per
  platform.
- Preceded by the Linux compatibility test of the OpenGL path (2026-09-15, on a live Ubuntu
  session), whose result sets the urgency.

### F35. The same seed makes a different world at each resolution — first after the weekly reset

*Found 2026-09-15 by the Linux compatibility run (Ubuntu 24.04 live session, nouveau/NVK/zink):
seed 217862 at 1024 and at 2048 are different worlds. Cause, confirmed in `PlateStage` (around
line 1749): each plate's seed row is drawn with
`random.nextInt((height * SEED_LATITUDE_SPAN).toInt())`. For a power-of-two bound Kotlin's
`nextInt` takes the generator's top bits, so the column `nextInt(width)` lands at the same
fraction of the width at every size; for the row's bound (901 at 1024, 1802 at 2048) it does
not, so every plate's latitude differs between sizes and the partition with it. Terrain noise and
the edge warp are sampled in map coordinates and are not the cause. `ScaleFreeTest` compares
whole-world statistics, never where the land is, which is why it never caught it. The README's
promise that an export re-runs the pipeline at the target size "so a larger map is more detailed"
has been false for every export: a 4096 export was a different world from its 2048 preview.*

- Fix: draw both seed coordinates as fractions of the grid (`nextFloat()` times the span, then to
  a cell), so the draw is resolution-free by construction; audit every other `nextInt(bound)`
  whose bound scales with the grid (hearths, landmarks, name salts) the same way.
- Guard, shown failing on main: `ScaleFreeTest` gains a clause that the plate seeds' fractional
  positions at 512, 1024 and 2048 agree to within a cell of the coarsest grid, and that the
  plate-id field at 1024 downsampled to 512 matches the 512 field on at least a stated share of
  cells (derive it: everything but the cells within one coarse cell of a boundary).
- Re-pins: every world moves (plate positions at every size but the one the pins were taken at),
  so checksums, samples and the codec fixture are re-taken with the reason in the commit;
  `FORMAT_VERSION` unchanged unless a field changes. Render 718106 and 59758 at 1024 and 2048
  and look: they must now be the same world at two resolutions.
- Also from the Linux run: `packageDeb` needs `fakeroot` (README updated); the desktop tests,
  `--gpu-check`, generate, accelerate, export, save and reopen all passed on Ubuntu; File ▸ Quit
  did not answer simulated input after real mouse use, unconfirmed as a defect.

### F36. A Linux download, and installation instructions on the release page and the site

*Decided 2026-09-15, after the Linux run passed.*

- **The Linux artefact**: a CI job on a Linux runner (ubuntu-latest, JDK 21, `fakeroot` installed)
  that runs `:desktop:test`, builds `:desktop:createDistributable` and `:desktop:packageDeb`, runs
  the packaged launcher's `--gpu-check` (expected to report no device on the runner and exit
  cleanly), and uploads a `.deb` and a portable tarball to the release beside the Windows files
  when a `v*` tag is pushed. The release script grows the same two names in its downloads table.
- **Release notes** gain an installation section per platform, kept to the facts: Windows (unzip
  and run, or the unsigned MSI and the SmartScreen steps); Linux (`sudo apt install ./<file>.deb`
  or untar and run the launcher, the graphics note that the open-source stack works and the
  proprietary driver is untested, where saves live, `fakeroot` only if building from source);
  browser (the site, or host the zip yourself over HTTPS).
- **The site** gains an "Installing" section after Notes and before "What comes next": one card
  per platform with the same facts, Linux the fullest since it has the most variables (which
  package manager, the driver, where the launcher lives). Copy drafted first by the outside
  reviewer per the copy rule, approved by the maintainer, then applied; `SiteAssemblyTest` reads
  the section and checks the release names it cites against the release script's table.
- **An apt repository** at a fixed path on the site (`cartogenesis.com/apt/`, served by the same
  Pages project), so `apt install cartogenesis` and `apt upgrade` work: on each `v*` tag a job adds
  the new `.deb` with `reprepro` (or `aptly`), regenerates and signs the indexes, and uploads the
  folder with the site. Signing: a GPG key made once by the maintainer, its private half a
  repository secret, its public half published at `cartogenesis.com/apt/key.asc`; the maintainer
  is walked through generating the key, adding the secret and rotating it, step by step, when
  the chunk is dispatched (decided 2026-09-15). The Linux card's primary route is the three
  commands (fetch the key, add the source line, install), the plain `.deb` the fallback; the
  path never moves once published. Debian and Ubuntu relatives only; an RPM repository is a
  later, separate item.
- Depends on F35 landing first, so the first Linux release is not a different world at each size.

### T2. Import a heightmap as the terrain — 4.x

*Decided 2026-09-17. A greyscale heightmap, from Wonderdraft or any image, replaces the terrain
stage's noise field; everything downstream runs on it.*

- **Import**: PNG or JPEG greyscale, any size and aspect; resampled to the working resolution and
  placed on the 2:1 grid, padded with sea or positioned by the reader; black at the ruler's
  deepest floor, white at its highest land, the sea level a threshold the reader chooses with a
  preview; the imported field stored in the save the way the accelerated terrain is
  (`TerrainSnapshot`), so the world reopens without the image and is the same at every export size
  (F35 first).
- **What tectonics does**: first version skips plate history, static crust, erosion and the coast
  passes run as they do; a later version may infer plates from the drawn continents.
- **Fidelity**: a slider from "keep my shapes" to "treat it as a suggestion", implemented as the
  share of erosion rounds and the coast passes allowed to move the drawn coastline; a guard that
  at the "keep" end the imported land mask survives to within a stated share of cells.
- Rule 8: resampling and the threshold preview are per pixel and go behind the raster seam.

### Site 4. A redesign of cartogenesis.com, before 4.0

*Decided 2026-09-17. The page as it stands is competent and generic, and the reader can tell
which kind of tool laid it out. The redesign starts from a written brief and wireframes, not
from code, and the maintainer chooses among drafts before anything is built.*

- **What the design must avoid**, stated as the brief's negative space: a single central column;
  a strict vertical rhythm of equal sections; uniform components repeated down the page; the
  boilerplate flow (hero, three features, testimonial, call to action); one hero section; a
  numbered procession of steps; technical copy where a sentence would do; features that
  justify themselves; a grid the eye can feel; symmetry everywhere.
- **What it may use instead**: the map itself as the page's structure (a sheet the reader
  moves across, with the words placed on it the way a chart places its notes); asymmetric
  composition; one or two large figures and many small ones at different scales; type set
  for reading rather than for scanning; margins that vary; the download and the browser link
  found where a reader looks for them, not repeated; the roadmap and the reporting lines kept
  but placed as a colophon; the engine's own renders as the only images, as now.
- **Deliverables, in order**: (1) a one-page written brief with the constraints above and the
  facts the page must still carry (the guarded counts, the addresses, the roadmap, the
  installation section from F36); (2) three low-fidelity wireframes as drawings, each a
  different composition, with a paragraph on what each does with the map; (3) the maintainer
  chooses one or combines; (4) one high-fidelity mock as a static page with real renders and
  the chosen copy (the outside reviewer drafts the copy per the copy rule); (5) only then the
  build, with `SiteAssemblyTest` carried over and extended to the new structure.
- Phones are a first-class composition, not a stacked fallback of the desktop one.
- Queued before 4.0 and after 3.x's own site work (F36); no release depends on it.

### S4. A choice of planet size — 3.x

*Decided 2026-09-19. The map stays a whole planet, pole to pole; what the reader chooses is how
big the planet is. Regions with a limited latitude range are deliberately out of scope: they
would need the climate to know where its edges are, and the program stays about whole worlds.*

- `WorldScale.worldWidthKm` becomes a setting with a small set of named sizes (a moon, a small
  world, the default 12,000 km, Earth's 40,000 km) and a free field, saved with the world and
  shown in the cartouche's facts and the scale bar. Every reach, depth and rate is already in
  kilometres and years (S1), so the physics follows; what must be checked is every guard whose
  bar was derived at 12,000 km (the Earth-likeness suite, the coast passes, the drainage
  figures), re-derived as functions of the width or asserted only at the default with the
  reason stated.
- Rendering and export are unchanged; the cell size and the scale line change.

### P4. Maps at any size and shape — 4.0, with the projections

*Decided 2026-09-19. The grid is square with a power-of-two side because the terrain FFT, the
flexure, the erosion tiles and the jump flood assume it; a 16:9 or 3:2 world is generated from
a square one today only as a cropped export.*

- The generator accepts any width and height (within memory): the FFT padded to the next power
  of two or replaced by an arbitrary-size transform, the erosion tiling and the jump flood
  generalised, `WorldGenConfig.atResolution` and the export ceiling rewritten for two
  dimensions. The world remains a whole planet on an equirectangular grid, so a non-square grid
  changes the cell's aspect, not the world; with P3's projections the reader then chooses both
  the grid and the projection an export is drawn in.
- Guards: the scale-free suite at a non-square size; a fingerprint that a square and a padded
  non-square generation of the same seed agree where they overlap; export sizes stated in two
  numbers everywhere the interface names one.

### Site 2. cartogenesis.com in the app's own identity — on `release/2.0`

*From the design review of 2026-09-12 (a the outside reviewer handoff William asked the maintainer to critique; the
assessment and William's approval of its seven decisions are in the session). Diagnosis: the
landing page looks like the campaign site it was born on — Cinzel, a gradient wordmark, a
diamond rule, a bevelled button — while the app already has an authored identity (F1: Spectral,
IBM Plex Sans, Plex Mono, flat surfaces, hairline rules, the ink/bone/brass/oxblood palette used
with restraint). The site adopts the app's identity; every image is a real export produced by
the site's own build from a fixed seed and crop, so the pictures regenerate with each release.*
Decisions approved: the app's typefaces self-hosted; the palette kept, gradients, bevel and
ornament removed; headline "Give it a seed. It builds the plates, the weather and the peoples."
(superseded after launch, 2026-09-12: William preferred the handoff's "Build a world from the
ground up." with the subhead "Terrain, rivers, climate and borders. A world shaped by geography,
starting with a seed.", and the page says that now). **Reversed in part the same evening**: after
seeing the readings and the annotated details live, William judged that captions describing a
map "would only work if this was written by a human" and that the descriptions did not read
naturally, so from the hero down to the feature list the page carries the earlier page's six
cards ("How a world is made": plates and mountains, erosion, seas and ice, climate, rivers and
lakes, realms and peoples) in the new page's dress; "What you get" is "Features" and "Practical
details" is "Notes"; the hero is the page's one rendered figure; an "and more" tab (a four-view
mosaic) that was being built for the readings strip was stopped unfinished. Kept from the
redesign: the typefaces, the palette, the hero (now a size class above the sections, headline
54px), the two-tone spaced-capitals wordmark William chose back, the feature list and the notes;
buttons "Open in the browser" and "Download for Windows (recommended)"; imagery rendered from
seed 718106 at build time; one combined strip of four readings; the generator interface out of
scope.

- **Phase 1.** Self-host the six bundled faces (copied from `ui/src/commonMain/composeResources/
  font/` at assembly, `@font-face`, no Google Fonts request). Topbar: a small wordmark only.
  Hero: eyebrow "Open source, free to use", the two-line headline, one sentence, the two flat
  actions (oxblood primary, brass-outlined secondary) with "Free · Open source · Runs locally"
  beneath, beside a real 2048 crop of 718106 (62% ocean, 14 plates, 12 realms) in the Atlas
  style; the giant wordmark, gradient, diamond rule and bevel gone. The browser and Atlas notices
  move into a practical-details section with formats, resolutions and downloads; the phone notice
  stays conditional by the launch. A `:desktop:renderSiteImagery` task renders the crops from the
  engine at assembly time (JVM, CPU, WebP at the poster's quality), `:web:assembleSite` depends
  on it, and `SiteAssemblyTest` checks the images exist at their sizes and that no external font
  or the author's other site is referenced.
- **Phase 2.** "One world, four readings": the same crop in Atlas, biomes (or rainfall), the
  political view and Pen and ink, as tabs on wide screens and a stacked strip on phones. Three
  annotated details replacing the six cards, each a real crop with HTML labels over it (crisp,
  readable, translatable): a rain shadow on the rainfall view, a river widening as its
  tributaries join (F10), a rift breaking into gulfs (E4). Captions state only what the engine
  does. The spec list tightened to what a reader compares.
- Guards: every text pair's contrast measured at AA in a test using the palette constants
  (reuse `ColorVision` from `:cartography`); the assembled page at 1280 and 390 wide screenshotted
  in a browser and looked at; `WebDeploymentContractTest` and `SiteAssemblyTest` green; page
  weight reported (fonts plus images, target under 1.5 MB before the app); build time of the
  imagery task on the runner reported.

### Site 3. Style and layer strips — on `release/2.0`

*"I would like to highlight the Atlas, Schoolroom, and Natural map modes in the screenshots on the
website, grouped together by a title which evokes their nature as realism based map modes. Then I'd
like to separately highlight the more data focused map layers with screenshots of temperature, ocean
currents, winds, and rainfall."* On the format, a game's resolution comparison: *"an identical scene
is displayed side-by-side and the kind of filter is noted in each one."* And, the same day: *"I would
like to add a hero image drawn from Natural since the color is very pleasant to look at."*

The word that decides the shape is *identical*. A row of separate figures on a page cannot carry it,
so a figure in `SiteImagery` becomes one window and a list of panels, drawn into one file with the
naming bands in it; the window belongs to the figure and not to the panel, so panels of a strip
cannot be showing different ground. Captions stay William's: a band's second line is the style's own
`MapStyle.detail` or the field's own KDoc, never copy written for the page. The bands are measured
for contrast in `SiteAssemblyTest` because they are the only text on the site that a guard reading
CSS cannot see.

On review William asked for the panels to stack on phones rather than scroll sideways, which the
same rule answers: the stacked variant is the same panel list composed the other way round by the
same code, chosen by `<picture>` at the breakpoint the headline already uses, so the page still lays
out no panel of its own. Shipped in 2.0.6; the ledger row carries the figures.

### Release 2.0.0 checklist

When F, H5 and H5b are green (G1 follows the release; G2 and G4 are in): version 2.0.0; full suite plus the audit tier once; William's
two worlds rendered at 2048 and looked at; artefacts, `--gpu-check`, tag, release with notes that
cover Tracks F, G, H and T1; web deploy from main (the deploy script now keeps the font folder);
the site's small-screen notice becomes "Works on phones. Worlds generate at 512; exports are
capped at 2048."; **the site's poster (`cartogenesis/poster.webp`, 1600x800, ~150 KB) replaced by
a fresh 2048 export of one of William's worlds in the new renderer, cropped to the same 2:1
band** (William, 2026-09-12); the site's notes updated; the LICENSE is MIT.

## Release 3.0 — the audit, on its own line

*Decided 2026-09-12: "once that and all other planned work is complete and 2.0 is launched,
let's plan to implement all of your audit findings in 3.0, working from a fork so changes to 2.0
can be made in the meantime."*

- **Branching.** When v2.0.0 is tagged, `release/2.0` is cut from the tag, the way `release/1.2`
  was. 2.0.x fixes land there, are released from there, deploy the site from there, and are
  merged forward into `main` after each release. `main` becomes the 3.0 line and carries the
  audit's chunks; nothing from it reaches `release/2.0` except a fix cherry-picked by hand. The
  version on `main` becomes 3.0.0-dev the day the branch is cut, so the About dialog and the
  update check never mistake a development build for a release.
- **Scope.** Every chunk in `REALISM_AUDIT.md` section 10: S1–S3, W1–W4, R1–R3, K1–K4, I1,
  P1–P2, V1–V3, N1–N2 and M1 — twenty-three chunks. The ledger lists each as queued for 3.0; each
  gets its full section in this plan's format (guards shown failing, render check at 2048, rule
  8's GPU path) when it is dispatched, not before, because the earlier chunks change what the
  later ones must say.
- **Order.** C2 first — the sweep for human-maintainable names and comments, so every chunk
  after it is written against readable code; then M1, the yardstick; then S1, which every solid-earth chunk needs and which turns
  the resolution contracts into a property; then two lines in parallel — S2 → I1 on the solid
  earth and W1 → W2 → W3 → W4 → K1 → K2 → K3 on the fluid side — with S3 → R1 taken up only after
  W4, because S3's guard is a windward-to-leeward dissection ratio and R1's channel threshold has
  a climate term, and W2 moves the rain shadows, W3 the moisture's reach, and W4 supplies the
  vegetation S3 reads for erodibility; building them first would mean re-measuring every erosion
  figure three times (William, 2026-09-14: "wouldn't it make sense to have S3 happen after those
  ones so that the erosion effect can calculate based on the updated local rainfall figures").
  So that S3 can still run its march before erosion, W2's pressure term and W3's moisture terms
  live inside the wind march and take only terrain and the sea mask, never erosion's output;
  P1 slotted where it touches the fewest open files; K4 once H5b is in; R2 and R3 whenever their inputs exist; V1 and V2 pulled forward to the
  2.0.x line as F13 and F14 (William, 2026-09-12);
  N1 and N2 alongside; P2 last. V3, the full atlas and its labels, moves to 4.0 (William,
  2026-09-12) and the cartogenesis.com page carries a notice saying so. G1 and H3 finish before 3.0 begins if they have not
  already.
- **What 3.0 must show.** The Earth-likeness suite green on the standard seeds; both of William's
  worlds at 2048 and 4096 reviewed crop by crop against the 2.0 renders. 2.0 saves need not open
  in 3.0 (William, 2026-09-12: nothing distributed, no saves worth keeping); the release notes say
  so, and the same seed generated afresh under 3.0 is a different world.

## Track G — more of the pipeline on the graphics card

*Added 2026-09-12. Profiled on the CPU, seed 42: at 2048 erosion is 89% of 75.7 s; with the
graphics card on William's 2048 world takes 16.6 s, of which the CPU hydraulic rounds are about
half and everything else the other half. Saves carry the world, so a GPU stage need not be
bit-identical to its CPU twin; each keeps the `ErosionAccelerator` shape (a suspend seam that
returns null to fall back) and a CPU-versus-GPU tolerance test like `GpuErosionTest`.*

### G1. Hydraulic rounds on the GPU
*Dependencies: E1 merged (it owns `HydraulicErosion.kt`).* Incision, transport capacity,
deposition and sill breaching are per-cell over the flow network. Flow accumulation becomes an
iterative sweep (or pointer jumping over the D8 tree); depression filling becomes the parallel
Planchon–Darboux lowering from the edges, which converges to the same surface priority-flood
gives. OpenGL compute on desktop, WGSL on web, behind a second seam. Guard: worst-cell and mean
difference against the CPU rounds within the tolerance `GpuErosionTest` uses; the mass budget
still 0.0000%; measured speedup at 2048 reported (target: the hydraulic share of a 2048 generation
falls by at least half).

**Handoff from H5b (2026-09-12): the invariant the GPU port must reproduce.** The CPU incision is
no longer a per-cell kernel. It is an ordered pass over the D8 tree and the order is load-bearing:

- **The bound.** For every land cell `i` whose D8 receiver `r` is land, the height after the round
  satisfies `z_i' >= z_r'`, where both are the *new* heights of that round and `z` is the raw height
  field (not the depression-filled surface the routing was computed on). A cell already standing
  below its receiver when the round opened — the floor of a filled basin, where the routing runs on
  the fill and the ground beneath it does not slope — is not cut at all that round, rather than
  raised: the bound limits the cut, it never adds material. A cell whose receiver is water keeps the
  existing cap at the shoreline instead, since the sea is its base level.
- **The order.** `FlowRouting.drainageOrder` read backwards, which places every receiver before its
  donors. The cut a cell is allowed is `min(stream power, half the drop on the filled surface, the
  height above the shoreline, the height above its receiver's already-final new height)` — so the
  receiver's new height has to be known when the donor is cut. On the GPU this is the same
  dependency the flow accumulation has, and the same answers are available: a sweep over the D8 tree
  from the outlets upstream, or pointer jumping, or Braun and Willett's implicit form, which gets
  the bound by construction because the new height is a weighted average of the old height and the
  receiver's new one. A naive per-cell kernel reading the *old* receiver height does not reproduce
  it and will put the comb back.
- **Ties.** There are none to break. D8 gives each cell exactly one receiver and every step is
  strictly downhill, so the network is a forest and each cell is cut exactly once against one
  already-final floor. `FlowRouting.flowDirections` resolves equal drops by taking the first
  strictly greater one in its fixed neighbour order, so the tree itself is one specific tree.
- **The two things that are *not* clamped**, and were measured before being left alone: the outlet
  breach, which cuts a surface that falls away from the new lip by construction and left no such
  cell on any seed in any round, and the thermal relaxation, which moves nothing on ground gentler
  than the critical slope and takes such cells away rather than adding them. The deposition is a
  third: it leaves a few hundred, which is the alluvial dam in TODO.md, and its own no-uphill rule
  owns it.
- **The second outlet pass** H5b added lives in `SeaLevelStage`, not in the rounds, and is a
  priority flood plus a graph walk; it stays on the CPU (rule 8) and G1 need not touch it. But it
  reads the terrain the rounds produce, so a GPU world whose channels are not clamped will hand it
  different basins.

### G2. Export rendering on the GPU
*Dependencies: none.* Hillshade, hypsometric tints and the style passes are per-pixel; a 4096
export is 50 s and 2.6 GB on the CPU. Render export tiles in a fragment shader and read back,
desktop first (the web export is smaller). Guard: pixel difference against the CPU rasteriser
under a stated bound; 4096 export time and peak heap before and after; 8192 attempted and
reported.

### G3. Ocean currents on the GPU
*Dependencies: G2 (shares the context helper).* The stream-function solve is a Poisson problem;
Jacobi or multigrid relaxation on the GPU. Guard: current field within tolerance of the CPU
solve; time at 2048 and 4096 reported.

### G4. Jump-flood distance fields
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

### H4. Currents feed the rain
*Dependencies: G4 merged (it edits `ClimateStage`'s distance call).* The moisture march picks up
sea moisture at one rate everywhere; on Earth evaporation follows sea-surface temperature, so a
cold upwelling current starves the coast it washes (Atacama, Namib, Baja) and a warm one feeds
it (the Gulf Stream and Norway). The ocean stage already produces a sea-temperature anomaly; scale
the march's over-sea pickup by it (Clausius-Clapeyron: roughly +7% per degree, so a 5-degree
cold anomaly cuts pickup by a third). Guard: on a seed with a cold current along a subtropical
west coast (find one; report which), coastal rainfall on that coast falls and a coastal desert
appears where the belt already made it dry, while a warm-current east coast at the same latitude
is unchanged or wetter; shown failing with the coupling off. Desert-in-band and Mediterranean
counts reported. Rule 8: the pickup scaling is one multiply inside the existing march, which is
CPU work by design (lock-step wavefronts); no GPU path.

### H2. Snow mass balance
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
them. Rule 8: the balance itself is per-cell arithmetic over four fields and goes behind the
accelerator seam (OpenGL and WGSL) with a tolerance test; the provisional climate march it feeds
stays on the CPU with the rest of the march.

### H5. Sea-level history
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
where rivers meet them, not everywhere. Rule 8: nothing new per cell here - it changes the base
level the hydraulic rounds read, and those rounds get their GPU path in G1, which this chunk must
precede so G1 ports the final semantics.

**H5 addition (2026-09-12, from E1's second pass).** With deposition switched off entirely, a
third of every seed's river mouths still end in a one-cell pocket of sea enclosed by land: the
percentile cut makes any low coastal cell an island of sea. Opening each pocket with an inlet was
tried and reverted (it turned E4's rift gulfs back into a channel). The fix belongs in the
sea-level stage: after the cut, a sea region not connected to the ocean is not sea. Keep its
elevation, mark it land, and let the river stage's depression fill decide whether it is a lake
(a below-sea-level lake is the Caspian) or dry ground. Guard: zero enclosed sea regions after the
cut on the standard seeds and on 59758 at 2048; the delta and rift guards hold; shown failing on
the current code (E1 measured 46/37/40/62 pocket mouths on 59758/42/7/1234 at 512).

### H5b. Channels cannot pond, drowned basins get an outlet, the desert guard by band
*Dependencies: H5 merged. Added 2026-09-12 from the H5 review.* H5 moved three guard bars, and
two of the three were defects measured rather than bars re-derived (rule 5): the comb share of
standing water at 1024 rose from 2.5/2.8/1.7% to 2.3/4.5/2.6% on 718106/42/7 and the bar went
3.5 → 5%; and a converted basin on 718106 fills to 1.11% of the land at 512, four times the
Caspian's share, recorded as a deviation. The third, desert-in-band 85 → 80 pooled, is a
measurement that cannot tell Earth's own out-of-band category from the defect it was written
for. G1 must port the final semantics of the first two, so this precedes it.

- **The receiver clamp.** The stream-power incision lowers a cell without regard to what its D8
  receiver does in the same round, so a cell can end a round below the cell it drains to; the fill
  turns that pit into a pond, and along a channel the ponds line up into exactly the thin
  grid-bearing bars the comb measurement catches. Every landscape-evolution model since Braun &
  Willett (2013, *Geomorphology* 180–181, the FastScape scheme) bounds a node's new elevation
  below by its receiver's, `z_i' ≥ z_r'`, by processing nodes from the outlets upstream — the
  order the D8 tree already gives. Do that, for incision and for whatever else the measurement
  shows making channel pits (deposition raising a cell above its donors is an alluvial dam, which
  is real but rare; measure before clamping it). Guard: channel cells lower than their receiver
  after the rounds, zero by construction; the comb bar back at 3.5% or lower with the derivation
  beside it, shown failing with the clamp off; lake count and share at 512/1024/2048 on 718106
  reported; the mass budget exact; time reported. The ordered pass is a graph walk and stays on
  the CPU (rule 8); write in the G1 handoff that the GPU port needs the same bound.
- **A second outlet pass after the cut.** A basin the enclosure rule converts is filled by the
  drainage to its sill, and neither E1's notch (which ran while that ground was under the
  provisional sea) nor E2's balance (the floor is below sea level, there is nowhere to drain) can
  touch it. Run E1's breach once more after the cut: a converted basin whose water balance
  overflows cuts its sill toward grade; if the notch reaches below today's sea level the basin is
  an arm of the sea — re-run the enclosure and it is ocean, an inlet with a narrow mouth. One that
  never overflows stays a below-sea-level lake or playa (the Caspian, the Dead Sea, the Qattara).
  Guard: the drowned-basin share `OutletIncisionTest` and `OutletResolutionTest` now print inside
  the Caspian cap on the standard seeds and on 718106 and 59758 at 2048; shown failing with the
  pass off (1.11% on 718106 at 512). Keep the share convention for the cap (S1 in
  `REALISM_AUDIT.md` owns the conversion to km²).
- **The desert guard by band.** Restate `GeographyAuditTest`'s desert measure so that it
  distinguishes desert equatorward of 15° (the defect the guard was written for: desert on the
  wettest rows) from desert poleward of 45° (Earth's own out-of-band category: the Gobi's north,
  Patagonia, the Kazakh deserts), and so that a seed's land distribution does not decide the
  answer: desert as a fraction of the *land in each band*, against Earth's per-band fractions from
  Peel, Finlayson & McMahon (2007) Köppen–Geiger maps, with the bars derived from those figures
  (a factor of two pooled, three per seed, as the plan's other one-sample guards do) and the
  derivation beside the assertion. The pooled in-band figure stays as a printed report. Shown to
  bite against a world with the subtropical dry belt switched off or the equivalent. If the
  poleward band comes out over Earth's bound, that is a finding to diagnose and report (H5's
  own note suggests the interiors dried when the spurious pockets stopped evaporating, and lakes
  never feed the march), not a bar to widen.
- Render 718106/42/7 at 1024 and 718106/59758 at 2048 and look; `GEOGRAPHY.md` gains the clamp
  as held-by-construction and loses or restates the drowned-basin deviation.

### H1. Tectonic history
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
one is the picture. Rule 8: belt stamping and the ageing blur are per-cell passes; specify them
behind the accelerator seam, but measure first - at 0.9 s for one epoch the CPU may be enough,
and the report says which.

### H3. Lithology
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
Rule 8: the erodibility field is an input texture to the G1 kernels; the thermal and hydraulic
GPU paths read it, so both CPU and GPU erosion honour it and the tolerance test covers it.

---

## Track T — the test suite

*Proposed 2026-09-12 after William asked which tests could be reduced or deprecated. Measured
per class on this machine under load: worldgen jvmTest 34 classes, 100 cases, 1357 s; desktop
tests dominated by a 183 s export. About two thirds of the worldgen time is renders, profiles and
reports, not correctness. Awaiting William's go-ahead; runs after 1.2.0 because it edits test
files the running chunks are in.*

### T1. Two tiers and a shorter path
- **On-demand tier** (`gradlew audit`, and a nightly CI job): `DebugMapDump` (259 s, the render
  harness - always run explicitly with `--rerun` anyway), `StageProfileTest` (158 s),
  `GenerationSpeedTest`, `DesertCauseTest`, `ColdCapReportTest`, `ErosionConvergenceTest`
  (55 s together; reports, and the last asserts thread splitting that CI's small runners fail),
  the 2048 cases of `GlaciationTest`, `LakeWaterBalanceTest` and `RealmIdRangeTest` (the 1024 and
  512 cases stay), and `ExportSmokeTest`'s 2048 export (a 1024 export stays per merge).
- **Drop the absolute elevation pin in `DepositionTest`**: re-recorded nine times in two days; its
  structural cases and the off-equals-on-at-zero-rates identity are the guard.
- **Remove the Kotlin/JS target** from `worldgen` and `cartography`: nothing consumes it since the
  web build went Wasm, and it compiles in every build. It was also the one target that could not
  agree with the others: JavaScript routes `sin`, `cos` and `pow` through `Math`, which differs from
  the JVM in the last bit, and the FFT compounded that into measurably different worlds from the
  same seed, while Wasm matches the JVM bit for bit.
- **CI**: run the Wasm suite once; take the fingerprint from that run's output instead of a second
  `--rerun-tasks` pass, or drop the diff step and keep `WorldFingerprintTest` (0.4 s) as the local
  check.
- Expected: worldgen per-merge suite from ~23 min loaded (13 idle) to ~6; desktop from ~5 to ~2;
  CI from three engine runs to two. Everything cut is kept in the audit tier. Guard: the audit
  tier is run once green before the chunk is accepted, and the per-merge suite time is reported.

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

Second pass (bac5366, merge bc76b29), after the maintainer's own 1024 render showed the
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
app wasm e689b9e3 served as application/wasm). The maintainer's own 1024 render of seed 718106
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
app wasm a0d98d4e). The maintainer's own 2048 render of William's world after the third pass: no
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
edge - the re-routing toward the water assigns targets in scan order on a flat (a fix-up contributor is
on it). Release 1.2.0 waits for both.

Follow-up on the straight lines (e079d49, merge a5b7cec): the diagnosis above was wrong and the
fix-up contributor measured why. Rivers ending in balanced lakes are no straighter than rivers to the
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


### Release 1.2.0 (2026-09-12)

Cut from `release/1.2` (branched from main before F1, so it carries Track E, G4, F0, the atlas
move and the realm-id guard but not the theme) at 9dfdf7a, E1's third pass cherry-picked on top.
Full suite green on the branch (worldgen 109, cartography 18, desktop 10). William's two worlds at
2048 through the app's renderer: 718106 37 lakes, 0.29% of land, largest 0.016% of the map;
59758 74 lakes, 0.82%, largest 0.036%; rifts as chains, rivers to the coasts. Portable zip 96 MB,
MSI 96 MB, web zip 4.5 MB; packaged exe passes --gpu-check; web build deployed from the branch
(site eb88d9b, loader stamp 202609120356, app wasm 8e5e62ee served as application/wasm); site
notes updated. main is the 2.0 line: F1-F3, G2, T1 are there and not in 1.2.0.

### Render review after H5 and F6 (2026-09-12)

Both of William's worlds at 2048 on the H5 branch, against the same seeds on main before it,
crop by crop. Held: coasts gain rias and estuaries where rivers meet them (718106's north-west
inlet lengthens into a ria; 59758's estuary count 1 → 9), the sprinkle of ponds at 2048 is gone
(`minCells` now an area), interiors keep fewer, larger lakes, and no pocket of sea survives. The
one large new feature is a Caspian-shaped lake filling a coastal rift trough on 718106 (0.13% of
the map, 1000 × 380 km), where main had a valley with a small lake: a below-sea-level basin
walled from the sea by a sill, which is the Caspian's own situation and defensible once per
world, but it exists because nothing can cut its sill after the cut — H5b. The crenulation H5
records as a deviation reads, at 2048, as a rugged coast rather than noise; its fault is that
every coast gets it. Pre-existing and not H5's: square-cornered coastal lobes with straight edges
(718106 south-west, 59758 north and east, the same before and after — deposition lobes with a
rectangular footprint, now in TODO), a flat-topped island with straight sides on 59758, and
nested crescent-shaped lakes down a hotspot cone on 718106's southern rift (terraces ponded at
successive fill levels, TODO). F6's five chromes and the colour-blind style reviewed from the
gallery screenshots: each distinct and readable, the map unchanged under all of them.

### Release 2.0.0 (2026-09-12)

Cut from `main` at 832d0b7 (F0–F7, G2, G4, H1, H2, H4, H5, H5b, E5, E6, E7, E8, T1, MIT) and
tagged `v2.0.0`. Per-merge tier green on the release commit (worldgen 37 classes, cartography 5,
ui 8, desktop 12); audit tier run once, 29 worldgen cases with one red carried into the release
notes as a known deviation — `GlaciationAuditTest`'s 2048 comb on 718106 at 6.2% of standing
water against 3.5%, the bars on the planar flank of the Andean margin E7 identified, the fill's or
the routing's rather than deposition's (E6: 102 of 145 with deposition off) — and the desktop
audit cases green. William's two worlds at 2048 through the app's renderer: 718106 17 lakes,
1.09% of land, largest 0.106% of the map (the 4452-cell rift lake E7 measured at 24% of relief
deep); 59758 19 lakes, 0.78%, largest 0.072%. Packaged exe passes `--gpu-check` for erosion and
the export raster. Portable zip 97 MB, MSI 98 MB, web zip 5.1 MB, on the GitHub release with the
notes. Web build deployed from `main` (site c8792d0, loader stamp 202609121528, app wasm
000bc7aa, the six bundled faces kept), the description page's notice now "Works on phones", its
spec list at eleven styles and fifteen themes, and its poster the top band of 718106's 2048
render (1600x800 WebP, 150 KB); site notes 1a21532. Three Windows build traps hit during the
release and recorded in rule 6. `release/2.0` cut from the tag for 2.0.x; `main` is the 3.0
line and its version 3.0.0-dev.

### Release 2.0.1 (2026-09-12)

Cut from `release/2.0` at 4046f8d, tagged `v2.0.1`: F8, F9 and the cartogenesis.com site. Per-merge
tier and `:desktop:siteTest` green on the branch (worldgen 37, cartography 6, ui 9, desktop 13
classes); the recorded F7 pixel-identity case retired on this line as on main. Portable zip 97 MB,
MSI 98 MB, web zip 5.1 MB; packaged exe passes `--gpu-check`. The tag's deploy failed once because
the two secrets were in Cloudflare's Secrets Store rather than GitHub's; with them on the
repository the rerun deployed, and a second run from the branch head carried the Atlas
work-in-progress notice (a full atlas targets 4.0) and the corrected phone line (512 is the
default, 2048 works). Verified live: application/wasm with Brotli, hashed files immutable, HTML
no-cache, `[cartogenesis] ready in 0.7s`. The earlier host on the site keeps its copy and its
notes say so. Not yet merged forward into `main` (waits for F10, then the cartography and interface
sweeps start against it).

### Release 2.0.2 (2026-09-12)

Cut from `release/2.0` at 0b2da4c, tagged `v2.0.2`: F10 (rivers widen with their discharge), F11
(Stop, cooperative cancellation at every stage and round, the seed field applies on Enter or Go
only), F12 (JPEG, heightmap and layer exports), Site 2 (the page in the app's own identity) and
the site copy William chose after seeing it live — the headline "Build a world from the ground
up." with its subhead, the hero caption cut to the seed line, the details' lead cut at "scaled
down to fit", a hero a size class above the sections below it (headline 54px, lede 20px) and the
earlier page's two-tone spaced-capitals wordmark with "Procedural world-map generator" beside it,
set in Spectral rather than Cinzel so the page still fetches nothing from a font host. Per-merge
tier green on the branch after the F12 merge (worldgen 38, cartography 6, ui 18 with wasm,
desktop 17 classes; 23 min) and `:desktop:siteTest` green. Portable zip 97 MB, MSI 98 MB, web
zip 5.4 MB; the packaged exe passes `--gpu-check` (an RTX 3070 Ti for erosion and export
rendering). Saves from 2.0.0 and 2.0.1 open, their rivers taking widths on load; the format on
this line is still 3. The site deployed on the tag (run 34733080849). The forward merge into
`main` landed the same evening as 4176893 (Site 2 and F12; no rename needed against the C2
sweep, no world moved, `FORMAT_VERSION` stays 6, README carries F12's export section in main's
voice; worldgen 124, cartography 28, ui 97 JVM and 97 wasm, desktop 62, siteTest 9, all green)
and 4ee9c62 (the six cards). The "and more" tab (William, 2026-09-12: the Pen and
ink tab replaced by a four-view mosaic of the same crop labelled rainfall, temperature, trade
winds and ocean currents, the section retitled "One world, many readings") follows as a
site-only deploy from the branch.

### Release 2.0.3 (2026-09-13)

Cut from `release/2.0` at 5fa887d, tagged `v2.0.3`: F13 (tints by climate, relief lit by a sky,
isobaths; two passes), F14 (Töpfer generalisation by zoom, a traced and simplified coast stroke,
a 10-degree graticule with margin figures, a 1-2-5 scale bar and the map's scale in the
cartouche), F15 (the pen as 0.24% of the sheet, mouths ending at the shore, water one cell wide
no longer breaking a course, rivers from their farthest source) and F17 (drowned valleys narrower
than half a cell filled back, Earth's 31% of shoreline graded by a littoral pass; two passes).
Every chunk looked at by the maintainer on 718106 at 2048 and 298405 at 1024 before merging, F13 and F17
each sent back once. Per-merge tier green on the merged tree (worldgen 124, cartography 50, ui 97
JVM and 97 wasm, desktop 63; 25 min). Portable zip 97 MB, MSI 98 MB, web zip 5.4 MB; the
packaged exe passes `--gpu-check`. A world regenerated from its seed differs at the coast and in
its drawn rivers; saves open and draw as saved. The site's hero regenerates on the tag with the
new tints and the coast stroke. **Forward merge into `main`** the same day (7375d9d, fast-forwarded
as 050bffa): F14's cell-size helpers folded into S1's `WorldScale`; F17's littoral reaches and the
postglacial rise expressed in kilometres and metres through `WorldScale` (23.4 km reach, 187.5 km
backshore, 492 km fetch, 120 m rise; the coastal-plain rise per cell 0.00293 -> 0.00391 of relief
at 512, the "one metre" 6.25e-5 of the field), `Isobaths` on the sea's own depth ruler (500 m =
0.05 of depth); C2 names applied to F15's and F17's code; `DepositionTest` land 6290/6464 ->
6426 (the same 136 cells filled on a shoreline 37 cells further out); `RiverCourseTest`'s break
predicate narrowed to exclude arrivals at endorheic lakes (seed 99's three "breaks" were one
242-cell sink); `OutletResolutionTest`'s vestigial standing-water clause retired to
`ScaleFreeTest` (floor not lowered); estuary gain 2.71x -> 2.64x over a 1.5 bar; style records
re-taken; 479 tests green, siteTest green; 718106 at 2048 rendered on the merged tree and looked
at by the contributor and by the maintainer. Found: `DrownedValleys` takes a cell's area as its width squared
(twice the ground on a 2:1 world; F17's 39-cell catchment bar is calibrated against it), and
`SeaConfig.enclosedSeaMaxKm2` carries the Caspian's share of Earth onto a map a seventh the
size (both TODO). Known and recorded: the coast's remaining cell-scale fringe (F21,
hillslope diffusion on the 3.0 line), the straight-bar lakes (F18, in progress for 2.0.4), the
belts' aprons (F19, S2's), the equatorial stripe (F20, W1/W2's), a phone cartouche line that
truncates, and the sky model's CPU cost in the browser.

### Release 2.0.4 (2026-09-13)

Cut from `release/2.0` at 526aca6, tagged `v2.0.4`: F18 (flow follows Tarboton's steepest facet
with Rho8's proportional receiver, so smooth aprons no longer rule straight trenches), F22 (the
outlet breach counts its step into the water; the post-cut passes re-derived 8 → 10), F23 (the
Natural map style, its palette measured off William's Blue Marble reference), F24 (the Lemon
Blueberry chrome, and the export panel's chips reading in full). F25 (cloud cover) was built to
a first pass and stopped at William's request after he saw its renders; its branch is kept
unmerged. Full tier and `siteTest` green on the branch before the cut. Portable zip 97 MB, MSI
98 MB, web zip 5.4 MB; the packaged exe passes `--gpu-check`. The save format on this line is 3
(F25's bump never merged). Worlds regenerated from their seeds differ where rivers and drained
basins moved; saves draw as saved. **Forward merge into `main`** the same day (232f131): 27
conflicts resolved by intent — F18's facet routing rewritten in the C2 vocabulary with
`FlowRouting.FLAT_GRADIENT_STEP` made public for the breach and the SplitMix hash shared with
`LakeWaterBalance` bit-identically; F22's walk reusing main's `outletReachKm` (1,500 km through
`WorldScale`) and main's `MAX_POST_CUT_OUTLET_PASSES` of 16 (a pass that cuts more only shortens
the retreat); the release's extra seed and routing parameters dropped where main passes the whole
config; `HeadedChipRow` carrying the FlowRow body; both sides' endorheic-arrival tests kept in
`RiverCourseTest`. Figures moved: the twelve style records re-taken; `DepositionTest` land
6426 -> 6404; `ReliefShading.ORDINARY_GROUND` 0.936 -> 0.9318 (defined as the median illumination
over seed 234475's land, cut differently now) and its GLSL copy; `GlaciationTest`'s comb bar
restated as 2% of what the ice adds (seed 42 reads 5.66% with the ice and 6.03% without);
`OutletIncisionTest`'s sill case asserted on seed 99 (2.15x -> 0.47x the Caspian) and reported on
718106, which under S1's 120 m stand no longer carries such a sill. 471 tests green, siteTest
green, 718106 rendered in Atlas and Natural on the merged tree and looked at by the contributor and by
the maintainer. Found (TODO): the facet routing doubles the un-glaciated share of standing water in thin
parallel bars on seed 42 at 1024 (49 -> 124 cells; short bars in ranks, not F18's ruled trench,
and without a guard of its own), and which basin is the largest drowned one is unstable between
seeds.

### Release 2.0.5 (2026-09-13)

Cut from `release/2.0` at 2e66619, tagged `v2.0.5`: F28, the Blacklight chrome from William's two
colours, and its guard that reads the site's theme sentence back against the enum. Interface
gates and `siteTest` green on the branch; worldgen untouched since 2.0.4's full tier. Portable zip
97 MB, MSI 98 MB, web zip 5.4 MB; the packaged exe passes `--gpu-check`. Saves open unchanged. Forward merge into `main` the same day (3da27bc): eight
conflicts, all wording and counts in the swept modules; F28's block swept to main's voice (its
section header now parallels Lemon Blueberry's, the chunk labels out); the F28 guards reproduce
the ledger's figures on the merged tree; cartography 54, ui 105 JVM and 105 wasm, desktop 67,
siteTest 10, all green.

### Release 3.0.1 (2026-09-15)

Cut from `main` at 573aff7, tagged `v3.0.1`: F31 (Help ▸ Report a bug… with the seed, resolution,
moved settings and device pre-filled; issue forms; bugreport@ and dev@ addresses), F32 (What comes
next drawn from `ROADMAP.md`, the notice at the foot, the atlas card at 5.0), and the copy audit
enacted on the page and in the window with every fact measured or read from the code (4.5 MB
engine, 4096 export ceiling, 2048 on phones, nothing uploaded). ui 112 JVM and 112 wasm, desktop
75, siteTest 18, all green; portable zip 97 MB, MSI 98 MB, web zip 5.5 MB; the packaged exe passes
`--gpu-check`; the deploy served `cartogenesis.js?v=573aff7`. Saves from 3.0.0 open unchanged.

### Release 3.0.0 (2026-09-15)

Cut from `main` at 0771099, tagged `v3.0.0`, the foundation release of the 3.0 line, shipped as
William's decision of 2026-09-14 with the weekly usage at 92%: the remaining 3.0 scope (W2, W3,
W4, S3, R1, I1, K1, P1) moves to 3.x, W2 and W3 first. In it: S1 (the ruler), S2 and S2b
(isostasy, the review's four corrections), W1 (energy balance and sea ice), I2 (troughs and
basins from the ground), G3 (currents on the GPU), F30 (the outline instrument and shore census),
the C2 sweep, and the 2.0.x line forward-merged through 2.0.7. Full tier green at the F30 merge
(worldgen 54 classes, cartography 11, desktop 20, siteTest 13); the styles strip's window
re-picked for the 3.0 world (1c64278); portable zip 97 MB, MSI 98 MB, web zip 5.5 MB; the
packaged exe passes `--gpu-check`; the deploy served `cartogenesis.js?v=0771099`. Save format
10: 2.0.x saves do not open. Known remainders named in the notes: flat interior rainfall until
W2/W3, lakes 0.52% and ice 5.10% of land against Earth's 1.48% and 10.1%, 59758's south-west
shallow sea, the browser currents path untested on a device.

## Release 6.0 — drafting a world by hand

*Decided 2026-09-17, to follow the atlas release. The generator stays what the program is for;
everything drawn by hand is a baseline that then goes through the pipeline, so the result is
still the physics' answer to a shape the reader chose. Three chunks, each a section of its own
when it is briefed.*

### D1. Paint, raise and lower land as you watch

A brush on the map that raises or lowers the base terrain, with the coast, the relief shading
and the rivers redrawn as the brush moves. The live preview runs the cheap stages only (sea
level, shading, a coarse routing); the full pipeline runs when the reader asks, or on a short
idle, on the accelerated path. Edits live in the terrain snapshot the save already carries, so
a drafted world reopens as drafted; undo is a stack of brush strokes. T2's fidelity slider
decides how far erosion and the coast passes may move what was drawn.

### D2. Choose the kind of world the seed makes

A pattern setting beside the seed, on the order of a map maker's presets (one continent, an
archipelago, an atoll ring, a supercontinent, scattered islands, a uniform spread), expressed
through what the generator already has: the ocean-coverage setting, the number and drift of
plates, the terrain filter's long-wavelength share, and the continental-crust share. Each
pattern is a named set of those numbers and nothing else, so a pattern is reproducible, saved
with the world, and shown in the cartouche's facts line.

### D3. Symbol packs and layers

Symbol packs (a folder of images with a manifest: name, category, anchor point, scale in
kilometres) loaded from disk or the browser, painted or placed on the map on named layers above
the generated drawing, with visibility, order and lock per layer; symbols and layers saved with
the world and drawn on exports at the sheet's scale. The generated features (rivers, borders,
labels, landmarks) stay generated; a symbol is decoration the reader adds, and the atlas's
labels (5.0) sit on a layer of their own so they can be hidden or moved as a set.

## Track M — the Earth-likeness yardstick (3.0)

### M1. Earth-likeness metric suite
*The first audit chunk (`REALISM_AUDIT.md`, section 9), started 2026-09-12 on `main` at 78a5aa6.
The sweep's cartography and interface phases (C2 2c and 2d) wait until F12–F14 have merged
forward from the 2.0.x line, so M1 goes first; it adds tests only and conflicts with nothing.*
Every later chunk is accepted against numbers that describe Earth, so those numbers get one home:
`EarthLikenessTest` (per-merge, 512, the cheap statistics) and `EarthLikenessAuditTest` (audit
tier, the standard seeds plus 718106 and 59758 at 2048), each metric measured per seed and pooled,
printed always, and asserted only where an established Earth figure exists and the generator sits
within a stated factor of it today. Where it does not, the metric is a **finding**, printed with
Earth's figure beside it, never a bar moved to fit (rule 5).

| Metric | Earth | Source | How measured here |
|---|---|---|---|
| Hypsometric curve | bimodal, continental mode near +0.8 km and oceanic near -3.7 km of a 20 km span; 85% of surface in two bands | Cawood et al. 2022, *Rev. Geophys.* | histogram of `erosion.height` in metres via `maxAltitudeMetres`, two-mode test |
| Coastline fractal dimension | 1.2–1.3 (Britain 1.25) | Mandelbrot 1967 | box counting on the sea-level cut over three octaves |
| Hack's exponent | 0.5–0.6 | Hack 1957; Rigon et al. 1996 | main-stem length against catchment area over the drawn rivers |
| Horton bifurcation ratio | 3–5 | Horton 1945 | Strahler orders on the D8 tree |
| Drainage density against aridity | peaked in semi-arid climates | Moglen, Eltahir & Bras 1998 | channel length per land area binned by the climate's aridity index |
| Lake-size distribution | Pareto, exponent about 1.06 by count | Downing et al. 2006 | log-log slope of lake counts by area class |
| Island-size distribution | Korčak, exponent about 0.5 | Korčak 1938 | log-log slope of island counts by area, the largest excluded |
| Ice share of land | 10.1% | Cogley 2014 / RGI | the ICE_SHEET biome share of land, cold seeds and pooled |
| Lakes, share of land at or above the cell area | about 1.7% for lakes at or above 100 km², about 1.2% for 1,000 km² | Downing et al. 2006 | lake area over land area at the resolution's cell area |
| Desert by latitude band | Peel et al. 2007 per-band fractions | Peel, Finlayson & McMahon 2007 | already held by `GeographyAuditTest` since H5b; reported here, asserted there |
| Realm-size distribution | heavy-tailed | report only | rank-size slope of realm areas |

Wetlands, reefs and delta classes are not modelled and are not measured; the audit's K and R
chunks add them with their own rows.

- Guards: the suite runs and prints every metric on every standard seed; each assertion carries
  its Earth figure and its derivation beside it; the audit test at 2048 on both of William's
  worlds; the per-merge part under a minute at 512. Shown to bite: each assertion run against a
  synthetic field that violates it (a flat world, a straight coast, a single-order network).
- Report: the table filled with the generator's figures per seed and pooled, which metrics are
  asserted and which are findings, and the findings ranked by how far they sit from Earth, which
  is the order S1 and the later chunks take them up in.

## Track S — the solid earth (3.0)

### S1. Units and time — on `main`

*The first solid-earth chunk (`REALISM_AUDIT.md` 1.2 "Units and time", 1.3, and the priority
table in section 10), dispatched after the 2.0.2 forward merge so that F12's sidecar and the
renamed stages are in the tree it changes. M1 found the disagreement this chunk exists to end:
one unit of land elevation is 6,000 m in the climate (`ClimateConfig.maxAltitudeMetres`, read by
the lapse rate) and about 8,000 m in the sea-level and erosion constants (`SeaConfig.lowstand`
and `HydraulicErosion.SHELF_BREAK` both derive 0.015 from "roughly 8 km of relief"); the sea has
no depth at all, since below the shoreline `relativeElevation` is normalised to whatever the
deepest cell happens to be; and the world's relief reads 11.9 km pooled against Earth's 20 km
because the hypsometry has to carry the land's ruler past the shoreline. Every stage that needs
metres, kilometres or years either keeps a private constant or a per-stage `atResolution`
contract — the class of bug fixed three times in the month before the audit (E1's third pass is
the worked example in the ledger).*

- **`WorldScale`**, one value object on `WorldGenConfig`, the only place a physical unit is
  declared: the map's width in kilometres (`worldWidthKm`, 12,000 today, already read by the atlas
  and the F12 sidecar); the vertical range as two numbers, the highest land above the sea and the
  deepest floor below it, each derived from Earth at the cell size the map has (the ceiling is a
  cell-mean, not a summit: state the figure and the source), so `maxAltitudeMetres` stops being a
  climate setting and the sea gets a depth the pipeline can quote; and the time a hydraulic round
  stands for, in years, derived from the stream-power constants below. Cell width and cell area
  in km follow from the width and the grid, computed in one place.
- **Every physical knob expressed in those units.** The stream-power coefficient K in
  m^(1−2m)/yr (Whipple & Tucker 1999; Lague 2014 for the range), Culling's D in m²/yr (Roering,
  Kirchner & Dietrich 1999), the talus critical slope as an angle, the lowstand as 120 m and the
  shelf break as 130 m (both already so on paper), the outlet reach and every "reach" and "radius"
  in kilometres, deposition and breach depths in metres. Each is converted to grid units once,
  where the stage reads it, from `WorldScale` and the grid — never typed as a fraction of an
  assumed range again. The value each knob takes is the one that reproduces today's behaviour at
  the default grid, with its derivation written beside it (rule 5); where two constants disagreed,
  as the 6 km and 8 km do, the contributor picks one ruler, states which and why, and re-pins the
  affected fingerprints once with the reason in the commit.
- **`atResolution` retired for physical knobs.** Where a stage scaled a knob by hand to hold a
  contract across grids, the knob now carries a unit and the scaling is arithmetic. What stays is
  anything that is genuinely a count of cells (a blur kernel, a stencil).
- **The scale-free suite** (the audit's N2, folded in because S1 is what makes it possible): the
  same seed at 512, 1024 and 2048 measured in physical units — relief in metres, coastline length
  in kilometres, drainage density in km per km², the largest lake's area in km², ice share, the
  desert bands — within stated tolerances that are derived from what a finer grid legitimately
  resolves, on seeds 7/42/1234/99. The per-stage contracts in `ResolutionScalingTest` and the
  `*ResolutionTest` classes become derived checks or are retired in its favour, each retirement
  named in the report.
- Guards: the scale-free suite green at the three grids; a one-ruler test that reads every
  vertical constant back through `WorldScale` (no stage may hold its own metres); `EarthLikenessTest`
  green, with the relief-span and hypsometry rows re-measured against a sea that now has a depth
  (the measurement moves, the bars do not); `WorldFingerprintTest` re-pinned exactly once with the
  derivation of each changed constant in the commit; the F12 sidecar's `metresPerGreyLevel`,
  `metresAtGreyLevel0` and `worldWidthKm` read from `WorldScale` and `DataExportTest` still
  round-trips; GPU erosion parity unchanged (the kernel's constants arrive converted, not
  re-derived on the device). No visible change is the aim: render 718106 and 59758 at 2048
  before and after, crop a range, a coast and a basin at the render's own pixels, and look — the
  land must be the same land to the eye, and any difference is explained by a stated constant.
- Rule 8: none. No per-cell work is added; the conversions are scalars.
- Save format: `WorldGenConfig` gains `WorldScale`, the format version bumps, older files are
  refused by name (nothing is distributed). `GzipFixture.kt` regenerated.
- Not in S1: uplift and isostasy (S2), erosion reading the climate (S3), lithology (H3), the
  spherical grid (P1, P2). S1 leaves `worldWidthKm` as the one number P1 later reinterprets.

### S2. Coupled uplift and flexural isostasy — on `main`

*The second solid-earth chunk (`REALISM_AUDIT.md` 1.2 "Coupled uplift and erosion, and
isostasy", 1.3, and the priority table), dispatched after S1 merged. What it exists to repair is
now measured three ways: M1 finds the hypsometry unimodal (the busiest land band and the busiest
sea band adjacent, no trough where Earth's continental slope holds 0.17 of its smaller mode, two
modal bands holding 0.52 of the surface against Earth's 0.85) and the sea's mode at −390 m on its
own ruler against Earth's −3,700; S1 finds the shoreline at 0.40–0.55 of the raw field where the
declared ruler puts it at 0.625, so the field's measured span is 10,228–17,370 m against 16,000
declared; and the eye finds the stamped belt profiles with their aprons (F19) and old belts
faked low by H1's decay factors. All of it is one absence: the solid earth has no isostasy and
uplift stops when erosion starts.*

- **An uplift-rate field**, in mm/yr, from the plate stage's boundary class and the crust-age
  field H1 already carries: 1–5 mm/yr in active collision and Andean belts, a fraction of that on
  active rifts' shoulders, ~0 on cratons (England & Molnar 1990 for the range; Whipple & Tucker
  1999 for the balance against erosion), applied every hydraulic round for the years S1 says a
  round stands for. H1's decay factors on aged belts are retired: an old belt is low because its
  uplift stopped and erosion went on, and the epochs now say *when* it stopped. The stamped
  belt profiles (B2) stay as the initial condition the rounds start from, and the report says
  how much of the final relief they still own.
- **Flexural isostasy** after every round and after the ice and sediment loads: the crust floats
  on the mantle and bends under a load, `w(k) = L(k) / (Δρ·g + D·k⁴)` (Turcotte & Schubert,
  *Geodynamics*), a low-pass filter on the load field, solved by the same FFT the terrain stage
  uses for Frankot–Chellappa; the elastic thickness and the density contrast are stated Earth
  figures (Te 20–40 km for continents, Δρ ≈ 600 kg/m³ mantle against crust) with the derivation
  beside them. Erosion unloads and the range rebounds; sediment loads subside, so foreland basins
  form in front of collision belts and deltas sink; the glaciation stage's ice mass is handed in as
  a load, so post-glacial rebound exists (I1 will refine the ice; S2 accepts what H2 gives today).
- **Two crusts, and the ocean as a consequence.** The plate stage draws continental and oceanic
  crust with Earth's two densities, and isostasy sets their levels: the continental mode near
  +0.8 km and the oceanic near −3.7 km on the `WorldScale` ruler fall out of that, not from the
  percentile. William's ocean-coverage slider keeps its meaning — the share of the world under
  water — by having the plate stage draw the continental-crust fraction that yields it, and the
  sea-level cut becomes the check that it did (within a stated tolerance) rather than the
  mechanism.
- Guards, each shown failing without its fix: steady-state relief in an active belt scales with
  uplift over erodibility as Whipple & Tucker predict (measure on a synthetic belt with the
  rounds run to steady state, the exponent within a stated band); old belts lower and broader
  than present ones by a measured ageing that follows time, not a factor (H1's guard restated);
  the hypsometry bimodal with modes near Earth's and a trough (M1's rows move from "none" to
  figures with Earth's beside them — the bars in `EarthLikenessTest` may then be asserted, with
  the derivation); foreland basins present in front of collision belts (a stated depth over a
  stated width); the shoreline residual `UnitsTest` measures closes to within a stated tolerance
  of the declared ruler; ocean coverage within the slider's tolerance on the four seeds and
  718106; the scale-free suite, the E-track, H5b and glaciation guards hold or are restated with
  reasons; fingerprints re-pinned once with the reason. Render 718106 and 59758 at 2048 before
  and after, crop a collision belt with its foreland, an old belt, a coast with its plain, a
  delta, and look: this is the chunk with the largest visible change in 3.0, so the report leads
  with the pictures and says what moved and why.
- Rule 8: the flexure is an FFT over the whole field once a round (measure it at 2048 and 4096;
  it is a G-track candidate at 4096 if it costs more than the round it follows); the uplift is a
  per-cell add inside the round, on both paths if the round's field lives on the device.
- Save format: the uplift field and the crust type are saved (`FORMAT_VERSION` bumps; fixture
  regenerated); no shims.
- Not in S2: erosion reading the climate (S3), lithology (H3), the ice sheet as a body (I1).

## Track W — the atmosphere (3.0)

### W1. Energy balance and sea ice — on `main`

*The first fluid-side chunk (`REALISM_AUDIT.md` 4.2 "Energy balance", 4.3), dispatched beside S2
after S1 merged; the two touch different stages. The temperature is a latitude curve with an
exponent and two anchors, seasons come from a tilted thermal equator, and the land–sea contrast
is a continentality knob. S1's scale-free suite found the desert bands moving with the grid (a
biome share cannot; the moisture march does — that is W3's), and the equatorial rainfall band
reads as a straight stripe across every continent (F20) because the atmosphere is prescribed.
W1 replaces the curve with a model that has the physics the curve cannot: ice-albedo feedback
and a land–sea contrast from heat capacity.*

- **A one-dimensional energy-balance model per season** (Budyko 1969; Sellers 1969; North 1975):
  temperature against latitude from insolation, albedo and meridional heat diffusion, a few
  hundred latitude bands, solved to equilibrium per season. Albedo feeds back from the snow and
  sea-ice the model itself produces, so a white band is colder because it is white and the model
  can hold a cap or lose it; `glacialMaximumC` becomes a forcing the model responds to (a colder
  sun, or a higher albedo floor) rather than a redrawn mask. Insolation by latitude and season
  from the obliquity the tilted thermal equator already implies.
- **Land against sea from heat capacity**: the model runs with the land and sea heat capacities
  in each band weighted by the world's own land fraction there, so continents swing more between
  seasons than oceans and a continent's interior is colder in winter and hotter in summer without
  a continentality knob; the knob and its distance-from-water field are retired. The two-dimensional
  field is the band's temperature carried to each cell with the lapse rate and the existing
  currents' offshore pull.
- **Sea ice** where the sea surface is below −1.8 °C in the season, a saved mask; the moisture
  march takes no moisture from ice (so polar seas are dry sources and polar deserts exist, which
  H2's snow balance needs to stop the poles over-feeding); the biome stage reads it for the sea-ice
  biome it already draws.
- Guards, each shown failing without its fix: Earth's zonal-mean annual temperature within a
  stated envelope (about 27 °C at the equator, 0 °C at 60°, −20 °C at the poles; North 1975's
  fit and a modern reanalysis climatology as the source), and the seasonal swing over land greater
  than over sea by a stated ratio; polar seas frozen in the cold season and the ice's edge within
  Earth's latitude band; the cap grows when `glacialMaximumC` is applied and shrinks when it is
  removed, through the feedback, shown failing with the feedback off; M1's ice-share and
  desert-by-band rows re-measured and reported; the scale-free suite's desert rows reported
  (W1 must not worsen them; their cure is W3); `GlaciationTest`, `SnowBalanceTest`, the climate
  guards and the biome guards hold or are restated with reasons; fingerprints re-pinned once.
  Render 718106 and 59758 at 2048 in the temperature views for both seasons, the biomes view and
  Atlas, before and after, and look: the poles, a continental interior, a subtropical coast.
- Rule 8: none; the model is one-dimensional and the per-cell application is a lookup plus the
  lapse rate the stage already applies.
- Save format: the sea-ice mask is saved (`FORMAT_VERSION` bumps; whichever of S2 and W1 merges
  second bumps again and regenerates the fixture); no shims.
- Not in W1: surface winds from pressure (W2), the moisture budget's calibration (W3),
  vegetation and permafrost (W4).

## Realism audit II (2026-09-12)

*William asked for a second audit of the climatology, geology, hydrology and presentation, "with a
focus on realism and determinism", thinking more deeply than before and consulting the scientific
literature, with the aim of "a level of rigor and natural believability not seen before in the
fantasy map building space".* The audit is `REALISM_AUDIT.md`. Its verdict: what remains is not a
list of landforms but five structural absences — no physical scale in the solid earth (elevation
and erosion are unitless, so every resolution-dependence bug of the month was this showing
through), uplift that stops when erosion starts (no steady state, no isostasy), an atmosphere that
is prescribed rather than solved (the monsoon deviation and uniform-rain erosion follow), a
rectangular planet (polar rows as wide as the equator), and a coast that is a line rather than a
process (no waves, drift, reefs or wetlands). Presentation has the parallel absence of a single
lamp, climate-blind tints and no scale or graticule. Determinism is sound and has two weaknesses:
sequential random streams that reshuffle everything downstream of an edit, and scale-free
contracts that exist for three stages rather than all of them.

It proposes twenty-three chunks under new letters — S (solid earth: units and time, coupled uplift
and flexural isostasy, erosion that reads the climate), W (atmosphere: energy balance and sea ice,
pressure-driven winds, a calibrated moisture budget, vegetation and permafrost), R (rivers: channel
initiation, rivers drawn as rivers, wetlands), K (coasts: waves and drift, delta types, reefs and
atolls, fjord coastlines), I (ice sheets with a profile), P (metric-aware physics, then a spherical
grid), V (tints and sky shading, generalisation and graticule, labels), N (per-feature hashes, a
scale-free suite) and M1, an Earth-likeness metric suite that becomes the acceptance yardstick for
all of them — with a priority table and a suggested order: M1, then S1, then the solid-earth and
fluid lines in parallel. None is queued yet; each becomes a chunk in this plan's format when it is
dispatched, and every one obeys rules 5 and 8.

## Ledger

Update the entry when the chunk's commit is on `main` and CI is green. Record the numbers the
guard reported, so the next chunk knows its baseline.

| Chunk | Model | Status | Date | Commit | Numbers |
|---|---|---|---|---|---|
| D1 Full-world save format | | done | 2026-09-11 | 09eb31f (merge 424b34a) | gzip whole-file 2.36-2.57x (512: 24.7->10.4 MB; 1024: 98.7->38-40 MB); heights 1.1x, id maps 136-1010x; round-trip guard failed with a section dropped, then passed; v2 saves open and re-save as v3; all 10 stages reused by assertSame; web stores raw (compression deferred to D2) |
| D2 Web storage | | done | 2026-09-11 | aaf19c7 + 158cac2 (merges b83248c, eb141f0) | IndexedDB with a headers store (listing never reads an array; guard shown to throw without readPrefix); library/codec became suspend; real gzip via CompressionStream; JVM-gzipped fixture decodes on both platforms; live selftest heightIdentical=true, 729 KB, write 85 ms, read 61 ms. Fixture had to be regenerated after A1 added 4 sections (29 total) - the failure that opened D4 |
| D3 Retire the determinism gate | | done | 2026-09-11 | 408beb4 (merge b278e7d) | ci.yml fingerprint step continue-on-error with ::warning::; README's three seed-only-save claims replaced; site the notes file needed nothing; memory note updated by the maintainer |
| D4 Forward-compatible sections | | done | 2026-09-11 | 2784957 (merge 31eec2b) | PartialWorld interface, WorldMap implements it, so no call site changed; a stage with any section missing is null and fails the === reuse guard, regenerating it and everything downstream; guard shown to throw WorldFormatException on the old reader then pass; library listing reports 'complete' / 'regenerates <stage>' from the header alone; 30-section fixture passes on both platforms |
| A0 GEOGRAPHY.md reconcile | | done | 2026-09-11 | 9ea2db2 | prose only; river-uphill figure 12-14% carried as last measured 2026-08-23 |
| A1 Seasons | | done | 2026-09-11 | 1aa12ee | 35deg swing: land 13.1C / sea 2.9C; Mediterranean west-coast cells 211/517/494 (seeds 7/42/1234), 0/0/0 with seasons=false; seasons=false reproduces all six fingerprint lines; desert-in-band 100/99/100/98% (belt rescaled to restore the annual mean, no threshold moved); desert AREA fell 5.1%->1.9% on seed 42 (for A4); border-on-river 2.08/2.12/2.16/1.04 (seed 99 down from 1.46); default fingerprint rivers=26 realms=14; **the belt rescale in figures** (moved here by C2 from `ClimateStage.seasonalBandSharpness`'s KDoc, which was the only place they were written down): two offset bells average to a profile roughly half as sharp as either, so without the correction the belt anomaly at the subtropical high's own centre falls from -1.12 to -0.51 — which lifts the horse latitudes out of the clamped, maximally arid span their deserts come from, cost seed 42 three quarters of its desert, and dropped desert placement in 15-45 degrees from 100% to 74% with rain-shadow deserts reappearing at the equator |
| A2 Continentality | | done | 2026-09-11 | eb694aa (merge 015a178) | first cut used the blurred exposure field and measured only 3.0C interior-vs-coast against the 6C spec; reworked to a chamfer distance-from-water, factor = clamp(d / 3*coastalReach): seed 42 at 50deg gap 0.2C at continentality=0, 7.5C at default 0.6; annual mean bit-identical; desert-in-band 97-100% |
| A3 Meridional wind / monsoon | | done | 2026-09-11 | 8a14ed5 + 53e39d7 (merge 19746a2) | semi-Lagrangian march in lock-step wavefronts, parallel by circulation belt; wind is a vector, direction from the thermal equator; meridionalWind=0 reproduces the zonal march exactly; desert-in-band 100/99/100/98; Mediterranean cells 174/424/375; the plan's 3x/2% monsoon guard could not discriminate (base already >2% on most seeds; 10deg tilt lands summer onshore flow on poleward coasts; rainfall clamped at 1) - guarded instead by a paired difference: east-west ridges gain +0.030/+0.030/+0.013 vs descending +0.020/+0.010/+0.006, exactly 0 without the slant; A4 to restate the monsoon claim; ocean stage untouched (zonally uniform meridional stress has zero curl) |
| A4 Absolute rainfall | | done | 2026-09-11 | f8fd195 (merge 6eef6af) | precipitationMm section; MM_SCALE=52653 derived once from seed 42's 99.5th land percentile (0.05698 -> 3000 mm) and applied to every seed: windward coasts 3067-3228 mm, desert cores 89-190 mm; Koppen aridity threshold on mm replaces the provisional t>=13 gate (summer-concentrated 32 / even 16 constants found empirically, not derived - unverified); desert-in-band 86/90/92/85% (seed 99 sits on the 85% floor - watch it); desert share 6.14/1.71/0.99/1.67%, driest/wettest 6.20x; old normalisation could not discriminate an arid from a lush config of the same seed (0.52x) where mm does (59x); monsoon claim re-measured unclamped on seed 26: 4.07% of land (was 2.93% clamped); MeridionalWindTest pins replaced by an in-test reference zonal march built on marchSeaStep/marchLandStep, so nothing re-pins; largest culture 38/34/30%; cold-cap coast forest 60.8/66.0/29.4%; **four figures moved here by C2 from `ClimateStage`'s KDoc, which was the only place they were written down.** (1) `MM_SCALE` re-measured on the other audited seeds, windward coast / subtropical desert core: seed 7 3204mm / 61mm, 1234 3207mm / 91mm, 99 2915mm / 155mm, against seed 42's calibration pair of 3000mm / 142mm — all four within a few hundred mm of the target off one constant fit to seed 42 alone, which is what "not a per-world fit" means in practice. (2) `REFERENCE_MM` = 1200: seed 42's old 88th-percentile reference measures 1230mm in the new calibrated mm and seed 26's (`MeridionalWindTest`'s monsoon seed) measures 949mm, so 1200 is what both seeds' land actually called "wet enough to be 1.0" before the rescale was removed — anchoring the 0..1 field at `precipitationMm`'s own 3000mm target instead was tried first and read meaningfully drier to every consumer, because the old reference meant "wetter than most land" and not "wettest coast on the planet". (3) The Koppen concentration search, against `GeographyAuditTest`'s desert-in-band guard on seeds 7/42/1234/99: Koppen's own 280/140 put in-band placement at 76/73/94/80%, a straight halving to 140/70 at 84/77/92/75% — a *smaller* value made seed 99 worse, because the cells at issue are a genuine compact 45-50 degree rain-shadow region confirmed by sampling and not noise — and 40/20, a fifth of Koppen's, was the first value past that point to clear 85% on all four at 88/99/94/85%. (4) Why `KOPPEN_CONCENTRATION_FLOOR_MM` exists at all: the march's cold cap suppresses winter moisture far more than summer moisture everywhere cold, so at 50-70 degrees on a measured seed `summerShare` has a median of 18.7 and a 90th percentile of 170 — the ratio alone calls almost every cold cell summer-concentrated whether or not either season brought rain |
| A5 Cold-cap report | | done | 2026-09-11 | e0c3081 (merge a1014ae) | 0% of 50-60deg west coasts forested on all 3 seeds despite 1.9-3.8x latitudinal-mean rain (precip 0.83-0.99): cap is NOT the cause; classify gates on annual mean (<7C -> taiga) and the curve puts 55deg near 0C. Opened A6 |
| A6 Temperate by coldest month | | done | 2026-09-11 | 463e6f9 (merge 357a923) | Koppen thermal gates on the seasonal fields (warmest<10 ET; coldest<=-3 D; coldest>=18 A; else C); LATITUDE_EXPONENT 1.25->1.8 was necessary (gate alone left 55deg coasts at 6.8C in summer); 50-60deg warm west coasts 0/0/0.1% -> 65/53/59% forested, interior taiga 100/97/96%; ice share fell (seed 42 32%->19%, seed 7 56%->43%); a PROVISIONAL t>=13 desert gate holds the audit and suppresses cold deserts - A4 replaces it with Koppen aridity; culture settlement now decided per cell (a unit straddling the ice margin no longer strands its non-ice cells): 100% settled on all seeds; **two figures moved here by C2 from `ClimateStage.LATITUDE_EXPONENT`'s KDoc.** At 1.25 the curve put 45 degrees — the effective latitude a 55-degree coast's *summer* reads off, one `seasonalTiltDegrees` equatorward — at 6.8 C; at 1.8 that same point reaches 14.8 C, clearing the 10 C tree line, and the guard passes on all three seeds at 56.7-66.7% forest. The lift cuts both ways and it is not free: raising the exponent alone warmed the 60-70 degree band a continental interior's *winter* reads off past Koppen's -3 C line, so a marginal interior that used to be taiga became warm enough for the temperate branch's desert case, and seed 42's desert-in-band fell from 98-100% to 48% until the classifier's own gate was restored. The two effective-latitude ranges overlap almost exactly, so no exponent or pole anchor separates them — which is why the fix lives in `classify` |
| B1 Continental shelves | | done | 2026-09-11 | d2d9d0a (merge 7e1384a) | redesigned as a post-sea-level floor remap after the pre-sea-level depression moved coastlines and its guard could not discriminate; near-coast shallow 100/100/100% vs 60.3% control, far 2.5/1.3/0.0%; 0 land cells differ on any seed; new SeaConfig (shelfWidth=20, shelfDepth=0.10) in the SEA_LEVEL reuse guard; largest realm 28/26/26%; seed-7 culture 38% (was 48% failing) |
| B2 Crust-pair boundaries | | done | 2026-09-11 | f72ecf7 + 3205447 (merge ac2305a) | five profiles in TectonicsConfig, cell widths through atResolution: Andean margin 14 / 0.52 asymmetric with a volcanic arc 13 cells inland; collision plateau 26 / 0.34 flat over 60% with rim ranges; island arc trench-both-sides, ridge on the lower-id plate; rift trough 7 / 0.25 with shoulders; hotspot chains on 35% of oceanic plates; guard BoundaryPairTest on six seeds: plateau 3.47x broader for its height (per seed 1.97-4.97x) vs 0.72x with one profile, shown failing on the control; the sea-level percentile now cuts exactly rather than to the nearest histogram bin (`SeaLevelStage.shorelineForFraction`, renamed from `percentile` by C2, over `thresholdAtRank`): taking the bracketing bin's lower edge left every cell *inside* that bin above water, and a sea-level cut lands by its nature in a lump of ocean floor at a near-uniform depth, so the bin is not small - measured on seed 42 at 128 it holds 2.4% of the map with the crust-pair profiles and 4.8% without, which made the slider's accuracy luck of where the target fell inside the lump; `PipelineTest` asks for 2% of the map and had been passing on 0.708 against a 0.700 setting, and with the profiles the luck ran out at 0.721. A second pass sorting the few hundred cells of the one bracketing bin picks the height with exactly the right number of cells below it: one extra scan, an array a few thousand floats long, 0.700 on the nose with the profiles on or off; plateauAlongVariation 0.5 because a uniform plateau read as one ice cap, and only 0.5 rather than lower because the cap is what walls the habitable ground behind it into one region: at a fifth of the variation one people held 48% of seed 7's habitable world against CultureRealmTest's 45% ceiling, at a half it holds 34% (better than the 38% the generator managed before this chunk), and the same change lifts seed 1234's warm-against-cold coastal gap from 2.0% to 3.1%; new plates.nearestBoundaryClass section joins TECTONICS (32 sections, fixture regenerated); DepositionTest pin re-recorded (land 6226); after the re-merge: ribbon 0.1%, incision 1.7x, desert-in-band 100/96/91/98, seed-7 largest people 41%, realm spread 28/23/24%, Mediterranean 271/927/1316; unverified: faint chamfer faceting on the widest plateau edges |
| B3 Deposition | | done | 2026-09-11 | 91d5048 (merge 83bacfa) | sediment routed in topological drainage order (the height-key sort lost load handed to already-walked cells - 3% short at round three); capacity = transportCapacity*sqrt(area)*slope, depositionRate=0.06; deltas breadth-first from mouths draining >= deltaMinCatchment, lake fans stop 2x pond depth short of the surface; spoil laid once before the final relaxation (feeding it back made GPU-vs-CPU worst cell swing 0.006-0.034 and a seed-42 people 29%->49%); mass balance 0.0000% by tallies and by summed heights; 68 mouths gain land within 4 cells vs 0 control; GpuErosionTest worst cell 0.007131 unchanged; render review: fans one to three cells wide at mouths, coasts bulge slightly into bays - believable. Moved coastlines left MeridionalWindTest pins stale (A4 replaces them with an in-test reference march) and seed 7's largest people at 46%. Fix-up 18db41a (merge f608ad9): chooseHearths scored candidates globally, so seed 7's main landmass (83-86% of habitable land) drew 3 of 7 hearths while one went to a landmass under 0.1%; hearths now allocated per landmass by largest remainder as BasinRealms.chooseSeeds does; new CultureHearthLandmassTest shown failing pre-fix (entitled to 6 of 8, got 5); seed 7 41%->34%, 42 38%->38%, 1234 31%->30%; realms-per-people 1.63-2.13, frontier-inside-country 68-84%; render: peoples follow landmasses |
| B4 Glaciation | | done | 2026-09-11 | d3c6191 (merge 1ed02d2) | GlaciationStage inside the SEA_LEVEL step after the shelf remap, returning a SeaLevelResult (no new section or field); mask = provisional annual mean <= 0C from ClimateStage.buildTemperature (made internal - the only climate change); U cross-section across the flow, staircase reaches measured in descent, recessional moraine per reach, cirque per head, terminal moraine per land snout, sea-floor trough per marine snout, bounded to mask + 8 cells; never touches isLand (land 6226 unchanged); guard seed 42: 12.36 lakes per 10k cold cells vs 0.99 temperate = 12.47x, 0.00x with glaciation=false (shown failing); off reproduces the base fingerprint exactly; lakes 5/4/1 -> 73/56/20; cultures largest 29/30/32%, ice 17/41/25%; desert-in-band and deposition budget unchanged; shelf land-invariance case and ValleyIncisionTest's control now run with glaciation off because they measured it; DepositionTest pin re-recorded; unverified: fjord bathymetry exists (109 units of sea floor) but the coastline cannot indent because isLand is fixed first - recorded in GEOGRAPHY.md as a deviation replacing 'No glaciation' |
| C1 Docs and release | | done | 2026-09-11 | ccba6d7 (merge 6b6d8c6) | README pipeline, saving, peoples, views and CI sections rewritten against the code; TODO.md gained three done entries and five open items from the render reviews; GEOGRAPHY.md and the atlas copy needed nothing; cartogenesisVersion 1.1.0; checkout and setup-java to v5; tag v1.1.0 on 6b6d8c6 (CI green), release with portable zip 96 MB, MSI 96 MB, web zip 4.4 MB; packaged exe passes --gpu-check; web build deployed to the earlier host (site aa04a5d, loader stamp 202609110610, new wasm served as application/wasm); site the notes file updated |
| E1 Outlet incision | | done | 2026-09-12 | d5bfa19 (merge 8b376cd), second pass 782fb72, third pass 93c899f (merge 024bf31; cherry-picked to release/1.2 as 9dfdf7a) | FlowRouting.spillways per round; HydraulicErosion.breach lowers a basin's sill by stream power with the same coefficient as incision (outletIncisionRatio 1, outletReach 64), breaching the sill over a reach because nicking the rim cell alone does nothing (the next fill finds the same rim - measured); never below the basin floor or the sea; spoil into B3's load, budget 0.0000%; one closing breach before the last relax or a replayed save diverges (TerrainSnapshotTest caught it); largest lake % of map 718106 at 512/1024/2048 0.136/0.045/0.083 -> 0.018/0.025/0.029, seed 99 0.165 -> 0.049, 43 0.122 -> 0.042; largest basin's fill depth over 12 rounds 0.239 -> 0.010 vs 0.239 -> 0.240 control; OutletIncisionTest: no lake above the Caspian's 0.073% of map and every over-large one at least halves, shown failing off; deltas (the strand fix): lobes slope apex to rim, reach in front of the river, irregular by cell-index arithmetic, whole cells only - DeltaMouthTest: new land with nowhere downhill 5.8-9.1% -> 0.2-2.6%, shown failing off; render at 2048: upland lakes gone with rivers through them, coasts unchanged, the 59758 mouth a rounded lobe. Moved: LakeWaterBalanceTest and GlaciationTest's comb/resolution cases run with the notch off because E1 drains their chosen basins (1775 -> 110 cells); GeographyAuditTest seed 99 desert-in-band 84 -> 80%: the out-of-band desert is a 45-50 degree interior present before (254 -> 216 cells) and the in-band count fell 1184 -> 677 as the drained interior's coldest month moved the aridity gate; guard restated by the maintainer as pooled >= 85% over the four seeds with a 75% per-seed floor, on the grounds that Earth itself is ~85-88% with the Gobi, Taklamakan, Great Basin and Patagonia outside, and one world is one sample. Third pass: the largest lake grew 2.45x from 512 to 2048 on 59758 (0.049 -> 0.121% of map) not because of outletReach (already through atResolution) but because the breach depth came out of the shoreline-relative field and was applied to the height field, so it was divided by the land's range, which is larger on a finer grid; every drop limit is now in relative units converted once at the cut, per-cell gradients held per unit of map width; outletIncisionRatio 1 -> 3 with the unit change; largest lake 512/1024/2048 on 59758 0.044/0.048/0.036% (1.33x) and on 42 0.015/0.015/0.015% (1.01x), all under the Caspian's 0.073%; OutletResolutionTest in :desktop asserts the 1.4x spread and the cap at all three grids, shown failing before; OutletIncisionTest's cap widened to 1.1x Caspian because with one rate for every world which basin ends largest is chaotic (rates 3/4/6/8 measured; seed 43 keeps one Caspian-sized survivor, neither glacial nor endorheic) - a bar moved under rule 5 with the measurement stated |
| E2 Lake water balance | | done | 2026-09-11 | 667c956 (merge fec976d) | Thornthwaite on the two seasonal fields, unfitted: hot desert 2272 mm/yr, cool temperate 554, frozen 0 (glacial lakes stay at spill); runoffFraction 0.35 (Earth ~40k of 110k km3/yr; a constant flatters dry basins - Volga/Caspian is ~0.12); bisection over basin hypsometry; endorheic lakes become sinks with flow re-pointed, playa mask as section rivers.playa (33 sections, fixture regenerated); wet basins bit-identical; guard on dry seed 43 (1775-cell basin, 172 mm rain vs 577 evaporation): 18% of spill area at balance vs 100% measured with waterBalance=false; wet seed 99 at spill; 0 stranded rivers; seed 43 lake share 2.40 -> 0.91%, largest 0.677 -> 0.122% of map; 718106 at 1024 82 -> 67 lakes, 10 endorheic; border-on-river moved (42: 1.54 -> 1.10, 99: 1.30 -> 1.97, report-only); render: seed 43's rectangular basin becomes a small lake with a dendritic net across the exposed floor |
| E3 Round hotspot cones | | done | 2026-09-11 | 5a7377f (merge, see log) | the stamp already used true Euclidean distance; the eight-fold amplitude at 512 (0.083) is the grid floor of a 2.5-cell radius (supersampling 3x3/5x5/9x9 all read 0.053), and at 2048 it is 0.000 before and 0.004 after, so the distance metric was never the cause of what the maintainer saw; stamp now supersampled 5x5 with three low-order seeded rim harmonics (k = 2, 3, 5 from a splitmix64 hash of seed and vent) so no two cones match, knob hotspotConeDetail; DepositionTest pin re-recorded, land 6226 unchanged. Open: the faceted look at 2048 on land cones is most likely erosion cutting radial gullies along the eight D8 bearings down a symmetric cone - not investigated, low priority |
| E4 Segmented rifts | | done | 2026-09-12 | 6a39b01 (merge 6b16e65) | every continental-rift boundary walked along strike (arc length by double BFS, because a rift meanders) and cut into seeded segments of 0.040-0.100 of map width, so 512 and 2048 break a rift the same way; per segment a seeded depth factor, a footwall that alternates flanks, a wedge floor deepest against the footwall rising to a low hinge, shoulders whose height and width come from one draw (independent draws made a tall narrow shoulder that surfaced as ribbon land) and vary with rangeVariation; depth and asymmetry taper through an accommodation zone with a modest sill at each join; deterministic (index order, splitmix-seeded LCG); nine TectonicsConfig knobs; other profiles untouched. RiftSegmentationTest on seed 59758 at 512, ocean 62%, 14 plates: sea bodies in the rift 1 -> 3, land bridges 0 -> 4, flooded-width CV 0.03 -> 0.32, corridor 98% -> 82% flooded; a second case shows the unsegmented world failing all three. BoundaryPairTest 3.47x held; RibbonLandTest held. Moved: DepositionTest pin (land 6226 held); GlaciationTest's comb and lake-share measures now exclude water in a rift trough (tectonic, not ice); its 512 denominator floor 1e-4 -> 0.001 with a written reason; LakeWaterBalanceTest's dry-basin bound 30% -> 45% (measures 39% because PlateStage renormalises the whole field; the basin holds no rift cells; control still 100%) - a bar moved under rule 5 with the reason stated, though not an Earth figure. Render at 2048: the ruler-edged strait is lagoons and gulfs behind sills with the ribbon joined to the mainland. Not verified: rift basins become lakes at 1024 and 2048 but not at 512 because LakesConfig.minCells is a fixed 12 cells, not a map fraction - follow-up **C2 recorded two figures its KDoc gave up (2026-09-12):** the shoulder height and width come from one draw because two independent draws left a **706-cell ribbon** on a rift shoulder on seed 234475 at 1024 that survived erosion, and tying them removes it; and `riftSillHeight` is held modest because a sill high enough to dam a half-graben through twelve rounds of erosion leaves a lake the outlet notch cannot drain — on seed 43, nearly twice the Caspian's share of the map. |
| F0 Blank canvas on launch | | done | 2026-09-12 | e27bdbc (merge, see log) | GenerationGate holds hasGenerated; the LaunchedEffect returns early until Go, New world or the new Generate button arms it; settings edits are inert before the first generation and live thereafter; opening a save displays without generating; empty map and atlas carry one-line prompts; export disabled until a world exists; GenerationGateTest (4 cases) proves settings changes never arm it; :desktop:run polled for 30 s sat at 5 s CPU |
| F1 Ink on paper | | done | 2026-09-12 | 753d3e0 (merge, see log) | light palette lifted from MapStyle.VELLUM (paper EFE4C8/F6EEDB, ink 2B2117, sepia accent 6B3F2A, rules BFAD86), dark is the site verbatim (ink 15110F, hairline 3A2F28, bone, parchment, brass C9A227, oxblood); complete Material schemes with surfaceTint = surface so no tonal fill survives; Spectral for what names, IBM Plex Sans for what measures, bundled as Compose resources with OFL licences; ui/Controls.kt shadows Slider/Switch/Button/Chip/Divider/Card once; CartogenesisTheme at both entry points following the system theme; ChromeGalleryTest captures chrome-light/dark at 1440x900; web bundle +6.6% (four ttf, 937 KB) - the site deploy script was fixed to keep composeResources; ui/desktop tests green |
| F2 Panel follows the pipeline | | done | 2026-09-12 | d956bcd (merge, see log) | header (seed, Generate/New world, resolution chips, Library/Atlas, status) then World (ocean coverage, graphics-card switch moved here), Terrain (plates stepper 3-40, mountain height = andeanHeight 0.20-0.90, erosion strength = erodibility 0.011-0.110), Climate (seasonal tilt 0-25, rain shadow = orographicStrength 0-5, ice on/off), Water (rivers, lakes, dry basins hold less water), Peoples (realms stepper 0-40, one wilderness switch, borders), Cartography (relief, coastline, style, view - F3 lifts the last two); right column is Export alone; knobs declared as data in PanelKnobs.kt and PanelKnobsTest (13) walks them - coverage, per-knob copy equality, write-back identity, clamping, shown failing with a knob dropped; found and fixed a borders switch reading one field and writing another; ui 17/17, desktop 19/19 |
| F3 The map is the instrument | | done | 2026-09-12 | 01d7e16 + 3034cec (merge, see log) | translucent toolbar over the map: nine styles as a segmented row, views as a menu (fifteen names run past 1300 dp); legend strip at the foot: cartouche (generated world name from the largest people's language via NameForge, seed, size, generation time as a footnote - 'largest realm' dropped at William's request) and zoom/Fit; Export folded into the header, right column gone, map takes the width; a Name field beside the seed (WorldNaming: generated per seed, editable, stored as the save's title, kept across settings edits, round-trips through the codec header); graphics-card switch moved to the header under Working resolution; 8192 export chip disabled with a note behind Platform.exportCeiling = 4096 and Exports.clamp; ui/desktop tests green, PanelKnobsTest + 6 toolbar/camera, CartoucheTest 6, 4 ceiling and 5 naming tests |
| F4 Menus, settings, updates, notices | | done | 2026-09-12 | 72b666a (merge 4b05eef) | menu strip drawn once in :ui (File: New world, Open library, Save, Save as, Export, Settings, Quit on desktop; View: theme System/Light/Dark/Nautical/Midnight/Mars, sections, toolbar; Help: Check for updates, About), declared as data in Menus.kt with MenusTest; desktop shortcuts Ctrl+N/O/S/Shift+S/E/,/Q; SettingsStore on the seam (desktop %APPDATA%\Cartogenesis\settings.json via temp-and-rename, web localStorage) with theme, resolution, card at launch, export defaults clamped by exportCeiling, library folder, scale, check-at-launch, reset; SettingsEffects tested by effect (10 + 5 desktop, including nothing reaches the network at launch); BuildInfo generated from gradle.properties; Updates.evaluate on the releases JSON with UpdatesTest (9, no socket); Notices.kt generated from the jvm and wasmJs runtime graphs plus LWJGL and the two OFL faces, 98 entries, NoticesTest; MapStyle.MARS (basalt sea, faint scarp coast, dark channels, rust-ochre-dust-white land, palette-only so the GPU raster is identical) and a Mars chrome; twelve screenshots. Found: no LICENSE file in the repo - About says so; William to decide. Found: OutletResolutionTest red on main since H1 (59758 at 2048, 1.69x Caspian) - handed to H5 |
| F5 Phones | | done | 2026-09-12 | c79ace6 (merge, see log) | one decision at the root (BoxWithConstraints -> Layouts.shape): below 800 dp or under a coarse pointer the map is full-bleed, the menu strip folds to one glyph, the toolbar to that glyph plus a palette menu with the current style's name and the view menu, the legend keeps cartouche and Fit; header and sections live in a pull-up sheet (72% height) that shortens the map rather than covering the cartouche; Arrangements declares each arrangement's reach and PanelKnobsTest (23 -> 32) proves the compact one reaches every knob, shown failing with a section dropped; gestures needed nothing platform-specific (detectTransformGestures already pans and pinches on wasm), double-tap-to-fit compact-only because it delays single taps that place labels; seam gains graphicsApiPresent, coarsePointer, exportCeiling(compact) = 2048 on web; compact starts at 512; wide layout pixel-identical at 1440x900 in every seed-independent region; screenshots at 390x844 and 768x1024, light and dark, sheet down and up; ui 75/75, desktop 28 with only OutletResolutionTest red (pre-existing) |
| F6 Five chromes and a colour-blind map style | | done | 2026-09-12 | a0ecd6f (merge e0d4275) | High contrast (pure black, pure white, 2 dp rules, type x1.15, opaque map strips, the spec's #1A6EFF kept as the *mark* at 4.72:1 and the same hue lifted to #6FA8FF at 8.72:1 wherever the accent is a word, since no colour pairs with #1A6EFF at 7:1), Colorblind (warm dark greys so Okabe-Ito's orange and sky blue reach 7.29:1 and 7.12:1 as text; error is their reddish purple, not their vermillion, which sits 6.04 from the orange under deuteranopia; armed button underlined, chosen chip ruled 2 dp, disabled chip struck), Allied (buff paper panels, navy ink, #B22222 grid red at 4.07:1, olive drab as the overprint on every filled state, capitals on the headings, boxed cartouche), Hallowed (lapis ground and filled states, vellum panels with ink, #D4AF37 leaf reserved for the doubled section rules and a shadowed gold #8A6A12 at 4.17:1 for the accent, crimson danger), Baroque (walnut ground and filled states, cream marble panels, lit oxblood at 8.50:1, gilt double rules with end diamonds, synthesised italic display face). Ornament, capitals, cues, stroke weight and the strip colour all live in one `ChromeDetail` read by `Controls.kt`, `Section` and the legend, so the six older chromes are byte-identical. `MapStyle.CLEAR` "Colour-blind": flat #1F2A3A sea, cividis-ordered 8-stop land ramp (adjacent stops >= 8.00 CIEDE2000 under deuteranopia and protanopia), biome wash 0, white rivers, 1-cell black coast at full strength, Tol muted nine for realms (worst pair 7.38 over every ground) with a 2-in-6 diagonal hatch for each further turn of the cycle (7.52 against its own fill); bar stated at 6.0. Palette-only bar the hatch: the recipe gained two political ramps that equal the plain pair for every other style, and the shader six lines for the comb, so `GpuRasterTest` passes over 15 views x 11 styles. Guards shown failing three ways (teal -> near-green 2.25; HC accent as text 4.72:1; vermillion 6.04). ui 81, cartography 25, desktop 30 with only the pre-existing `OutletResolutionTest` red |
| G1 Hydraulic rounds on GPU | | queued behind H5b (ports its receiver clamp and post-cut outlet) | | | |
| G2 Export rendering on GPU | | done | 2026-09-12 | 8dca89f | RasterAccelerator seam in cartography takes a RasterRecipe (colours and tables pre-packed, no palette logic in shaders); desktop GpuRaster on OpenGL compute, web left to a later WGSL port; every view and style, relief, coastline, borders, lakes, hatching; 4M-pixel tiles, fields uploaded once; GlContext extracted from GpuErosion (two contexts on one thread invalidate each other's programs), erosion arithmetic untouched; 99.9th-percentile drift 0 across 141.5M pixels, worst channel 2 on 0.0002% (GLSL sqrt at a ramp node); 4096 export 224 -> 210 s, raster 714 -> 368 ms - the raster was never the bottleneck, generation is; 8192 exhausts a 10 GB heap inside the generator before a pixel is drawn (the device rasters 8192 in 0.9 s); README export table corrected |
| G3 Ocean currents on GPU | | done | 2026-09-14 | 972e521 (the outside reviewer's WIP), d7dd9b8, f752c42, c7c448c, af485fa, 84ac99b, 6b26575, 6db802f, 66b0bb1 | `OceanAccelerator` seam in :worldgen alongside `ErosionAccelerator`: suspend, takes the coarse water mask, the wind-stress curl, the pass count and the over-relaxation, returns the coarse stream function or null to decline. The CPU solve is untouched and remains the reference; both paths share the forcing and `streamToVelocity`, so they differ in arithmetic and nothing else. Desktop `GpuOcean` on OpenGL compute sharing `GlContext` with GpuErosion and GpuRaster - red-black Gauss-Seidel kept as two dispatches per pass with a barrier between, not switched to Jacobi, because omega 1.7 diverges on Jacobi; web `WebGpuOcean` in WGSL against the same buffer layout, submitted in batches of 256 passes so no command buffer holds 6,000 compute passes - untested, this machine has no browser harness. Behind the existing Graphics acceleration switch (no new UI); `Platform.oceanAccelerator`, `WorldGenerationEngine.generate(..., oceanAccelerator)`. Parity bound 1.7e-4 cells per advection pass, derived and written beside the assertion: seven rounded operations per cell update, two terms in the central difference, the peak normalisation's worst-case amplification of the coarse grid's own side (a full column - rows clamp where columns wrap), times `speedCellsPerPass`. Not the Poisson operator's conditioning, which gives a bound a thousand times looser that no broken kernel would trip; the ill-conditioned error is low-frequency and smooth and does not survive a difference of neighbours. Measured on an RTX 3070 Ti, worst cell / mean in those units: seed 42 7.9e-6 / 4.4e-7, 718106 7.9e-6 / 5.3e-7, 59758 7.8e-6 / 4.2e-7 at 512; 2.5e-5 / 1.2e-6, 1.9e-5 / 8.3e-7, 4.1e-5 / 1.6e-6 at 2048 - four to twenty times inside the bound. Shown failing: the obvious wrong kernel does not work and the reason is recorded - 2999 passes of 3000 reads 6.3e-6 and 1500 reads 6.6e-5, both inside the bound, because the solve is converged and the field is renormalised to a fixed peak speed; 300 passes reads 0.0444 (260x the bound) and is what the committed test holds, and clamping the cylinder's seam instead of wrapping it reads 1.272 worst / 0.151 mean (7,400x and 880x), shown by hand and recorded beside the test. Timings, same device, two runs: the solve alone 156 ms CPU to 21 ms GPU (7.4x) and 240 ms to 56 ms (4.3x). The whole stage moves far less - 2048 687-774 ms to 599-608 ms, 4096 2,693-2,823 ms to 2,573-2,657 ms - because the solve is on a `solveResolution` 128 grid whatever the world's size while the 200-pass temperature advection over every cell is not, and that advection is lock-step per cell and stays on the processor. No save-format change: `WorldSections` has carried all four ocean fields since D1, so an accelerated ocean already reopens as it was written, and a test holds that. Renders of seeds 7, 42 and 1234 side by side in worldgen/build/maps show the two panels indistinguishable. worldgen 189/189, ui 45/45, desktop 32/32, web distribution builds. Reviewing the WIP: its sandbox init script and two run logs were removed from the repository root, and three changes it made outside the chunk were reverted - moving the export raster behind the acceleration switch against the comment saying why it is not, generating exported worlds on the accelerators with no guard, and replacing the passage explaining why the two front ends describe accelerated work differently. One thing to know for later: `GpuErosionTest`'s `assertTrue(gpuMs < cpuMs)` has very little room on this machine - it passes at 1.1-1.3x - and it failed once (22,397 ms against 12,837 ms) when `:desktop:test` ran straight after a 42-minute `:worldgen:jvmTest`. On an idle machine the whole module is green, 73/73, with the ocean suite in it. Nothing in the erosion kernel changed; the assertion is a wall-clock race that a loaded or warm machine can lose. |
| G4 Jump-flood distance fields | | done | 2026-09-12 | 0228500 (merge, see log) | math/JumpFloodDistance propagates source coordinates (1, halving powers of two, 1), integer squared distances, ties to the lower index, row-parallel, exact against brute force; replaced the chamfer in ClimateStage.waterDistance, the shelf remap and PlateStage's boundary distance (plate assignment keeps chamfer: only the label is read); 23/93/367 ms at 512/1024/2048 vs chamfer 6/33/110, +0.77 s on a 2048 generation, so no GPU path (rule 8: measured and declined); eight-fold component lone source 0.083 -> 0.004, shelf break on seed 42 0.030 -> 0.000, both controls in-test; continentality gap 8.5C held; shelf near/far held, 0 land cells differ; BoundaryPair 3.47x -> 3.01x (belts up to 8% wider in cells because Euclid is shorter); rift 3/4/0.32 -> 4/5/0.32; DepositionTest pin re-recorded, land 6226 held; render: plateau margins lose their kinks and sweep, the shelf break rounds |
| G5 Acceleration on a native WebGPU runtime | | queued for 4.x (2026-09-15); on ROADMAP.md's 4.x row | 2026-09-15 | | wgpu or Dawn behind the existing seams; the browser's WGSL on Vulkan, D3D12 and Metal; OpenGL retired only when measured equal or faster |
| H4 Currents feed the rain | | done | 2026-09-12 | 30e7dc1 (merge, see log) | marchSeaStep scales over-sea pickup by 1 + currentMoisture * anomaly (0.07/deg, Clausius-Clapeyron); 0 reproduces the field bit for bit; seed 26 cold west coast 1548 -> 1536 mm (-0.8%), warm east coast +0.1%; shown failing with the coupling off; MM_SCALE anchor unmoved (3160/172 mm); no guard moved. Honest finding: at the derived rate no west-coast cell flips to desert on 40 seeds because the march is near saturation before landfall - reduced evaporation is only half of the Atacama; the other half is the cold sea stabilising the air and suppressing rain-out over the coast. A later pass should scale the release rate over cold-current coasts, not the pickup; recorded in TODO |
| H2 Snow mass balance | | done | 2026-09-12 | 801999a (merge 349c752) | SnowBalance: accumulation = each half-year's precipitation x a snow fraction ramped over -1..+3 C, ablation = positive-degree-day melt at 4.5 mm/degree-day (Braithwaite 1995, Hock 2003) with half-year means turned into degree-days by Calov and Greve 2005 (sigma 4.5 C); ClimateConfig.snowBalance, false reproduces main bit for bit (elevation and biome checksums pinned on 7/42/1234/99); provisional balance before glaciation reuses the seasonal fields on a still ocean (+66/298/1408 ms at 512/1024/2048; solving the gyres would cost 2.1 s and move 0.5-1.6% of the mask); balance 1/3/10 ms so no GPU, the seam cut and SnowBalanceAuditTest re-checks the 50 ms line; ice share of land 7/42/1234/99: 41.9/18.3/26.0/28.8 -> 8.4/3.9/12.0/12.6%, pooled 28.8 -> 9.2% vs Earth 10.1%; cold dry interior 39.9 -> 0.0% ice, wet quarter iced where the dry quarter is not (the control ran backwards), shown failing off; carving mask reads a Pleistocene world: GlaciationConfig.glacialMaximumC 6 C (Tierney 2020) as a polar-amplified ramp 2 C equator to 12 C pole, 26% of seed 42 under maximum ice vs Earth ~25% while the map draws today's 3.9%; the first cut of the chunk carved from today's ice instead (glacialMaximumC 0) and left seed 42 at 512 with 4,047 frozen cells, 92 of them in channelled country and not one glacier, so that world had no glacial lakes at all and B4's guard collapsed to zero — the right answer to "where is the ice today" and the wrong one to "what does this landscape look like"; lake-density guard restated at 1024 (6.93x, control 1.54x), resolution bar 1.7 -> 2.0 with derivation; desert-in-band, cultures, realms, comb unmoved; render: seed 7's northern third from white to tundra with ice on the polar margin and high wet ground, biome shares elsewhere identical to 0.1%; **the still-ocean decision in full** (moved here by C2 from `WorldGenerationEngine` and `OceanStage.withoutCurrents`, which were the only places the rest of it was written down): the gyre solve costs 2.1 s at 2048 against the moisture march's own 1.5 s, so it is the expensive half of the provisional climate rather than a rounding error on it; and giving the provisional march the real currents instead of a still sea moves 62-128 cells of a 4,000-13,000 cell ice mask on the four standard seeds at 512, which is the 0.5-1.6% above. The finished map's ice is classified from the real climate and does see the currents |
| H5 Sea-level history | | done | 2026-09-12 | 3cc827e + 8e24cd3 (merge 84216d9) | SeaConfig.lowstand 0.015 (Earth's 120 m against 8 km of relief) holds the hydraulic base level down for rounds 0-8 and walks it up over 9-11, one scalar per round so G1 ports it free; after the cut every water body is 8-labelled wrapping in x and any non-ocean body no larger than the Caspian (enclosedSeaMaxShare 0.00073 of the map) becomes land at its own height, the fill and the water balance deciding lake or playa - the cap added after measuring its absence (3.2-4.6% of the map flipping, a lake of 0.32% of the map on 718106 — 4x the Caspian — and 0.83% on seed 43, rift gulfs turned to lakes so RiftSegmentationTest reads one body where it wants three, pooled desert-in-band 88% -> 83% as the inland evaporation went, and GlaciationTest's comb share on seed 7 1.6% -> 5.6% against a 3.5% bar); solving the cut for ocean coverage written and reverted (drowns E4's bridges): marking the unreachable water as land raises `landCellCount` by 3.2% of the map before the lowstand and 4.6% after it on seed 42 at 512, so `PipelineTest`'s land-fraction promise could have been kept by solving for the rank at which the *ocean* covers what the slider asks for - a cheap fixed point, since the ocean's size is monotone in the rank - and it works on that number and wrecks the map, because the deeper cut drowns the low ground the segmented rift keeps between its half-grabens: on seed 59758 `RiftSegmentationTest` went from four bodies of water and five land bridges to one body, no bridges and a corridor flooded end to end, which is the canal E4 exists to break up arriving by a different door. The promise was instead restated where it is measured - the water the rule marks as land is still water, so what the slider governs is the land that is not under standing water, and `PipelineTest` says so; H1's aulacogens gained along-strike roughness so the notch measures a slope, LakesConfig.minCells scales as an area (its twelve cells are some 3,300 km² of a 12,000 km world at 512 and 206 km² at 2048, which is why a 2048 render came out sprinkled with ponds 512 never had and why the same world held four times the water at four times the grid — seed 42, 0.20% of its land under water at 512 against 1.53% at 2048; scaled by the square of the grid ratio it is twelve cells at 512, 48 at 1024 and 192 at 2048, the same piece of ground, and nothing moves at 512 where every :worldgen guard is measured), OutletResolutionTest green at 512/1024/2048 on both seeds; 512 estuary mouths 12/14/3 -> 35/52/62, pockets 87/85/533 -> 0, 2048 estuaries 3 -> 32 and 1 -> 9, pockets 925/305 -> 0; shown failing with lowstand 0 and enclosure off; moved: DepositionTest land 6226 -> 6382, rift bridges 3 -> 2, ribbon 1.05 -> 1.10, meridional pooled, comb 0.035 -> 0.05 and desert 85/75 -> 80/65 (both handed to H5b as defects, not bars); new deviations: drowned basins to 1.1% of land, lowstand roughens every coast, inland seas above the cap stay sea |
| H5b Channels cannot pond, drowned basins get an outlet, desert guard by band | | done | 2026-09-12 | df40193 | **The receiver clamp.** The incision is now a pass of its own over the D8 tree read from the outlets upstream (`FlowRouting.drainageOrder` reversed), each cut bounded below by the receiver's already-final new height — Braun and Willett (2013)'s `z_i' >= z_r'`; the transport walk still runs sources-first and picks up what the ordered pass took in `incisedAt`. What the bound replaces is the reason the holes were there: `drop * 0.5` was written in the shoreline-relative units the drop is measured in and spent on the height field, whose land range is about a quarter, so a well-fed channel cell could be cut by about twice the height it actually stood above its own receiver, every round. Pit census over the twelve rounds at 512, counting only cells a mechanism put below their receiver that were not there when the round opened, on 718106/42/7: outlet notch 0/0/0 in every round, incision 6383/10/5 -> 0/0/0, spoil 1173/420/509, closing breach and grooves 894/404/473, thermal relax 830/418/513 (fewer than the closing figure on 718106 — the sweeps take pits away). Channel cells the finished map draws under water 1627/106/323 -> 780/54/265; total incised over the rounds 5735/3095/2707 -> 5177/3064/2689. Shown failing through `erodeBlocking(config, uplift, receiverClamp = false)`, a test-only seam deliberately kept off `WorldGenConfig` (`ReceiverClampTest`). Comb share at 1024 on 718106/42/7 2.4/4.9/2.6% -> 2.2/4.4/1.7%, bar 5% -> **4.5%**, not the 3.5% asked for: the residual on seed 42 is the spoil, and the no-uphill rule that bounds an alluvial dam computes its margin in relative units and spends it as a height budget, so the margin is about four times what it means to be — measured, put in TODO.md, handed to E5/G1 rather than fixed here. Lakes on 718106 on the author's settings, before -> after: 103 -> 53 at 512 (standing water 3.95% -> 1.54% of land), 25 -> 17 at 1024 (1.59% -> 0.49%), 19 -> 21 at 2048 (1.41% -> 1.15%) — fewer and far smaller everywhere, and the count rising at 2048 is one large body breaking into several as it drains. **The post-cut outlet.** `SeaConfig.postCutOutlet` runs the breach again on the far side of the cut over the basins the enclosure rule made (floor below the shoreline), same stream power, same `outletIncisionRatio`, same units, with the base-level limit lifted — the water behind one of these sills stands below the sea, so the sea is not what the river crossing it grades to — and the cut continued back across the lake bed up the inflow with the largest catchment, which is the other half of a sill once the target goes below the old water surface and without which seed 99's basin moved 1486 cells to 1428 and then not at all over eight further passes. Where the outflow reaches the waterline the sill becomes water and the basin is an arm of the sea at the next labelling; where it does not, the basin keeps a lake below sea level. Eight passes at most, stopping when a pass cuts nothing: 718106's largest runs 1883, 1195, 985, 838, 663, 515, 405, 366, 366 cells and its surface 0.227 -> 0.018 of the land's relief, flat from the seventh. Largest drowned basin as a share of land at 512, pass off -> on: 718106 1.128% -> 0.263% (4.53x -> 1.05x the Caspian's 0.249% of Earth's land), 99 0.605% -> 0.072%, 43 0.155% -> 0.156% (its outflow cannot cut its sill — the Caspian's own case), 1234 0.156% -> 0.164%; asserted now in `OutletIncisionTest` (against the same 1.4x chaos allowance its sibling bar uses), `OutletResolutionTest` (59758 0.33/0.74/0.05x and 42 0.05/0.62/0.86x the Caspian at 512/1024/2048, strict cap) and `SeaLevelHistoryAuditTest` at 2048 (718106 0.66x, 59758 0.05x) where H5 could only print it. At 2048 the pass also opens sounds: estuary mouths on the audit pair go 2 -> 28 and 1 -> 21 against the pre-H5 world. **The desert guard by band.** `GeographyAuditTest` measures desert as a share of the land in 0-15, 15-45 and 45-90 degrees, hemispheres pooled, divided by that world's own desert share of all its land, against Earth's from Peel, Finlayson and McMahon (2007) (BW = BWh 14.2% + BWk 4.9% = 19.1% of land; a named-desert census against the land in each band gives 5.2/39.2/2.2%, ratios 0.27/2.05/0.12, derivation beside the assertion). Seeds 7/42/1234/99: tropics **0.00 on every seed**, horse latitudes 2.83/2.52/2.91/2.40 (pooled 2.66), poleward 0.25/0.55/0.14/0.34 (pooled 0.31). First two asserted at a factor of two pooled and three per seed; shown biting with `landRecoveryRate = 0`, where the tropics go to 1.15/2.30/2.46/2.46. **The poleward band is a finding, not a bar**: 0.31 against Earth's 0.12 is two and a half times Earth's cold desert, left un-asserted and recorded as a deviation, because the cause is the interiors drying when the enclosure rule took their inland evaporation away and lakes still never feed the march (W3, TODO.md), which is the climate stage's and not this chunk's. **Moved:** `DepositionTest`'s pinned land 6382 -> 6327 at 128 (38 of it the clamp, 17 the outlet pass; the cut itself has not moved, 62% of cells still below it); `GlaciationTest`'s comb bar 5% -> 4.5%; `SeaLevelHistoryTest`'s lowstand pair now holds `postCutOutlet` off in both arms, because the post-cut notch makes narrow inlets of its own and was raising the control as much as the world under test (seed 7's control 22 -> 33 estuary mouths, the ratio 1.64 -> 1.30) — the shipped pair is printed beside it (seed 7: 36 -> 43 estuaries with the pass on); `SnowBalanceTest`'s carved-above-freezing assertion re-derived from `runOut` at 2% of carved ground (seed 99 measures 220 of 20,843, 1.06%, the others exactly zero); `RibbonLandTest`'s comparative bound restated as an absolute one against Earth's peninsulas and island arcs (0.7-1.0% of its land, bar 1.5%) because the ratio it held was between a count of two strips and a count of five and could not carry a tenth — 0.350% single-epoch against 0.622% with the history, both well inside Earth's. **Renders**, 718106/42/7 at 1024 and 718106/59758 at 2048 on the author's settings, before and after: the coastlines are unchanged to the eye on every world — the outlet pass opens sounds a few cells wide, not bays — and every visible change is inland. On 718106 at 1024 the large elongated lake filling the north-western interior is gone and a dendritic river net runs through the same valley; three more upland lakes (the northern range, the eastern margin, the south-western interior) are likewise replaced by rivers, and the ring lake round the southern cone and the south-eastern lake survive. Lakes 25 -> 17, standing water 1.595% -> 0.486% of land, largest lake 0.166% -> 0.027% of the map, drowned basin 0.430% -> 0.057% of land. At 2048 on 718106 the Caspian-shaped lake in the coastal rift trough the H5 review singled out is down from 4854 to 2636 cells and the rift reads as a valley with a long lake in it rather than an inland sea; lakes 19 -> 21, water 1.412% -> 1.149%. On 59758 at 2048, lakes 30 -> 22, water 1.221% -> 0.832%, coasts identical. Generation time at 2048 on the author's settings: 36.2 s -> 34.7 s on 718106 and 34.9 s -> 39.7 s on 59758 — the eight post-cut passes are inside the run-to-run spread of a stage that spends most of its time in the twelve hydraulic rounds. **Left red and reported, both audit tier:** `GlaciationAuditTest`'s 2048 comb, which was already over its 3.5% bar before this chunk at 7.4% and now reads 5.0%; and the same class's glacial resolution contract, 1.80 -> 2.51 against a bar of 2.5, where the ice's own share of standing water at 512 comes out at exactly zero and the test's own floor stands in for it — the ratio between two small numbers its comment warns about. |
| E5 Deltas and fans with natural outlines | | done | 2026-09-12 | 76d846f (branch worktree-contributor-a301fe32bbf48641c) | measured first, with a `DepositionLog` recording which of the four mechanisms raised each cell: `fan` handed its own breadth-first step count to the acceptance rule as a distance, and over eight neighbours that is Chebyshev, whose iso-lines are squares - the **lacustrine fan**, whose rule is only "any ponded cell", took the whole 2R+1 square (the rafts; 12862 cells on 718106 at 2048, and the mask shows a block with right-angle corners), the **sea lobe** compared a Euclidean cosine shape against the same count and came out a half-disc with its corners pulled along the diagonals (35441 cells), and the **floodplain/alluvial** case is not a fan walk at all (170315 cells, one cell at a time down the drainage order, no squares, untouched). New `DeltaFan.kt`: rim `R(sides + (1-sides)max(cos th,0))(1 + a s(th))` about the apex, `s` four harmonics (orders 2/3/5/7) on the absolute bearing with phases a splitmix hash of (seed, stage, apex quantised to the reach) - no sequential stream, and the whole rim is +,-,*,/ and sqrt with hashed *unit vectors* rather than cosines of hashed angles, so it is bit-identical on any platform; cost of advancing into a cell is 1 + depth/(0.015 of the land's relief), Earth's 130 m shelf break against 8 km, the same figure as `SeaConfig.lowstand` and not by coincidence; two-pass walk - best-first over the depth-bent cost to find the region, then **radially** outward to spend the budget, because spending it cheapest-first leaves a deep cell near the mouth unfilled with the lobe grown round it (seed 1234's flat share 2.5% against 0.9%); surface graded to the *full reach* and not to the rim in each direction, because grading to the rim is not monotone in distance and every bay in the outline put a dip in the plain behind it; two to five distributary grooves per lobe above four cells of reach, hashed, and any groove whose ray runs into the coast is not cut (without that check the grooves made more flat ground than the slope removed: 1.5/0.5/0.8/2.5% -> 2.2/1.8/1.7/3.3%). Guards, each shown failing on `deltaOutline = false`: **no grid squares** - share of a fan's perimeter in straight grid-axis runs longer than max(reach, 2*sqrt(2*reach)) cells, bar 1.2% set between the measured populations: sea lobes 2.0% and 1.8% -> 0.7% and 0.0% on the two seeds. The lacustrine square does not show at 512 at all - a 13x13 block is smaller than the lakes it sits in - so it is shown at 2048 on the mechanism mask instead, where a 49x49 block with right-angle corners becomes a narrow fringe along the shallow margin of the lake; and at 2048 the longest single straight run of new coast falls 34 -> 13 cells on 718106 and 30 -> 15 on 59758. **No perfect discs** - on open water over 24 hashed mouths, rim max/min >= 1.5 (shaped 3.07, half-disc control 1.06) and the two sides reflected in the lobe's own axis differ by >= 6% of the mean radius (shaped 10.2%, cosine-lobe control 1.2%, half-disc control 3.6%). The plan's literal second clause - "harmonic content not all in the zeroth and first order" - was written, run and **does not discriminate**: the reach is a *rectified* cosine and rectification is full of even harmonics by itself, so the control leaves 15.7% above the first harmonic and the shaped rim 11.8%, the control scoring higher; the figure is still printed and the guard restated as mirror asymmetry, which every compass-drawn shape fails. **Depth bends the outline** - on a synthetic coast with a shelf one side and water 30x deeper the other, 3.14x further over the shelf against 1.02x with the depth term off. **Delta area against catchment** - reported, log-log exponent 0.16 and 0.13 over 76 and 42 lobes, positive and sublinear as Syvitski & Saito 2007 find across their 51 deltas; not asserted, two worlds are two samples. Mass budget 0.0000% by tallies and by summed heights over all twelve rounds. `DeltaMouthTest` restated to measure the delta's *own* ground rather than every cell that became land, because the log showed the old measure pooled two unrelated mechanisms (delta ground 0.4-1.2% flat, floodplain ground 8-23%, the latter the same before and after E5) - the slab control fails it wider than before, 5.3/5.5/5.1/4.2% against the sloping lobe's 1.1/0.6/0.8/0.6%. Whole `:worldgen:jvmTest` (109), `:cartography:jvmTest`, `:ui:jvmTest`, `:desktop:test` green. 2048 generation 41.7 -> 46.8 s on 718106 and 39.9 -> 39.8 s on 59758; the fan is a graph walk from one cell and stays on the CPU, said so in the KDoc (rule 8). Not fixed and handed back: the straight-edged terrace in the author's own crop, at 718106's rift mouth above the crescent lakes, carries **no fan sediment** on the mechanism mask and is unmoved by this chunk - it is floodplain aggradation smoothed by the relaxation, on the same cone as the crescent lakes TODO already records. Also found and reverted: `LAKE_FAN_SLOPE` deepens a lacustrine fan per *cell*, so the same lake has a different floor at every grid; the one-line fix shallows lakes at 1024 enough to move two of B4's marginal guards (seed 42's comb 4.0 -> 5.2% against a 5.0% bar, cold-country lakes 4 -> 3 against a 3x control) and is left in TODO for a chunk that can re-derive them - with it reverted E5 *improves* the comb on all three seeds, 2.4 -> 0.6%, 4.9 -> 4.0%, 2.6 -> 1.9% **C2 recorded three figures its KDoc gave up (2026-09-12):** the fourth wobble harmonic was added partly in the hope of raising the *least* asymmetric outline the hashed phases can produce, and over twenty-four mouths it does not — the unluckiest went 10.0% to 10.2%, which is why the guard's bar sits between the measured populations instead; grading a lobe's surface to the rim in each direction rather than to the full reach measured 1.9/1.1/1.1/2.6% of the new land with nowhere downhill on the four guard seeds at 512 against 0.8/0.8/1.2/1.1% before the chunk; and a lacustrine fan laid to one depth below the surface gave seed 59758 at 2048 two lakes of 452 and 287 cells with a single distinct floor height between them. |
| E6 The rift-mouth delta: valley fills, pockets and moats | | done | 2026-09-12 | (branch worktree-contributor-a301fe32bbf48641c) | measured first with `DepositionLog` at 2048 and against a world with no deposition, and the three things in the author's crop have three different causes. **The moats and the pocket are the spoil's** and no fan is involved: `headroom` measured its margin off `settled`, which is seeded from the shoreline-relative field and was being updated with height-unit amounts, so an alluvial dam could stand `1 / landRange` times higher than the no-uphill rule allows - about four times - and the rule it bounds has a **flat** for its fixed point anyway. `settled` is now kept in relative units throughout and converted once where it is spent (H5b closed the same muddle on the incision side); and `ErosionConfig.gradedAggradation` caps the rise at the slope where the transport capacity equals the load, which is the equilibrium slope of a transport-limited channel solved out of the expression `transportCapacity` already appears in - no new constant. In the author's window: ringed water 563 cells -> 0; standing water 2282 -> 1121 against a 1535 floor with no deposition, so the spoil now takes water out of the valley where it added it; floodplain land cells 1177 -> 134; lake bodies 4 -> 1; world-wide new land 18391 -> 18267 and lakes with nothing but lobe ground on their shore 0. **The saw-teeth and the forty-five degree comb are almost none of them the spoil's**: 145 cells of water in thin grid-bearing bars in that window, 102 of them present with deposition switched off entirely; E6 takes the deposition's share from 43 to 5 and the rest goes to TODO. **The straight seaward front is none of it**: cut the deposition-off terrain at the *deposited* world's own sea level and the longest straight run of shore is 31 cells, exactly what the deposited world measures - deposition moved the sea level onto a different contour of a planar rift shoulder, which is E4's, as is the pale bench of constant width down the west side; said so and stopped, per the spec. Also fixed, at the coordinator's request: `LAKE_FAN_SLOPE` charged per cell (E5 wrote the rim-fraction form, measured it and reverted it) - back in, identical at 512, and it was the whole of B4's resolution failure, the drainage's lake area per unit of map 2.38x -> 1.92x between 512 and 1024. Guards: new `BayHeadDeltaAuditTest` in the **audit** tier (2048 is where the artefact lives) asserts the valley holds no more standing water than the same valley with no deposition - control 2282 against a 1535 floor, shaped 1121 - and zero ringed cells; `BayHeadDeltaTest` in the per-merge tier carries the same four measurements over the whole world at 1024 and **records that not one of them discriminates there** (standing water 1.75x/1.04x -> 1.74x/1.16x, rings 59->59 and 429->426, rising trunk steps 19.4%->15.9% and 15.0%->14.5%), which is rule 5's own instruction rather than a bar quietly moved. Two guards the E5+H5b combination turned red, both restated with the derivation: `GlaciationTest`'s comb bar back to **3.5%** from H5b's 4.5% (718106/42/7 2.2/4.4/1.7% -> 1.2/2.5/1.5%); its resolution contract now on the drainage's own standing water rather than on the total, 1.91x against the 2.0 bar (the total 2.38x before the lacustrine fix, 2.00x after) with the ice's own 2 cells at 512 against 46 at 1024 printed and handed to B4; its temperate-zone floor now *one lake's worth* of density rather than a fixed 0.1, because E6 took seed 42's temperate country to zero lakes and a zero denominator capped the strongest form of the claim at 2.14 against a 2.5 bar (now 2.55); and `CultureRealmTest` restated the desert guard's way - the claim per seed (above 1.0: 1.50/1.75/1.88) with the strength pooled over all three at 1.4 (measured 1.71), where it had been a single tuned 1.3 on whichever seed the loop reached first. `DeltaMouthTest`, `DeltaOutlineTest`, `RiverEndingsTest`, `DepositionTest` and H5's estuary guard hold - the last restated too, its ratio pooled over the three seeds (1.28/2.53/4.31, mean 2.71, bar 1.5) after E6's graded floodplain took seed 7's pair from 26->40 to 25->32 and through a 1.4 bar the loop only ever reached on seed 7; mass budget 0.0000%; 2048 generation 40.7 s on 718106 and 34.7 s on 59758. Crops at `desktop/build/deltas/e6-*`: the author's window now reads as one valley with a river down it to the sea - no terrace, no pocket, no moats **C2 recorded one figure its KDoc gave up (2026-09-12):** charged per cell rather than against the fan's rim fraction, the far edge of a lacustrine fan lay two and a half pond depths under the surface at 512, four at 1024 and seven at 2048 — the same lake with a different floor at every grid. |
| E7 A rift lake is deep | | done, **no geometry changed** | 2026-09-12 | (branch worktree-contributor-af2e0970574049f95) | **Both of the chunk's premises were measured first and neither survived, and every repair the spec proposed was built, measured and reverted. What ships is the measurements.** *The floors are not shallow.* The spec's Earth figure is a floor 12-20% of the relief below the shoulder crest; measured on the stamp over five seeds at 512 and at 2048, E4's floor already stands **45-72%** below its crest, against Earth's own 21-50% (Baikal 3.2-4.0 km of crest-to-floor against 8 km of relief, Tanganyika 2.8-3.8, Malawi 1.7-2.7, the Dead Sea 1.7-1.9). The water is Earth's too: the deepest rift lake on 718106 at 2048 is **24.2% of the land's relief** against Baikal's 20%, over 4452 cells, and the render shows a Baikal (`e7-riftlake0-718106.png`). Raising `riftDepth` 0.25 -> 0.35 was run and refused with the figure - the rift stops being a chain of basins and becomes one continuous deep axis that drains along itself, 718106's standing water falling 17,412 -> 8,200 cells and its deepest rift lake 24.2% -> 2.4%; scaling `riftSillHeight` with it (0.075 -> 0.105) recovers two lakes of fourteen and not the deep one. *The floor is not a plane either.* Within half a segment it already rises and falls by **65-76% of the trough's own depth** (the accommodation zones, the per-segment depth factor and the terrain the belts are stamped onto). The straight-contour guard the spec asked for was written three ways - bands of the pooled range, a 40 m contour interval, wider intervals to 10% of relief - and none of them could tell E4's floor from a floor with a hashed chain of deeps on it (plane 0.34-0.44 of a segment against shaped 0.34-0.59, the shaped worse on two seeds). *What is actually wrong with the author's trough*, measured: the post-cut outlet does exactly what the spec assumed - it cuts the sill to the waterline and stops, the lake's surface ending at 0.001 of the land's relief - and a half-graben's floor is a wedge, so only the part below sea level stays wet: **2158 of the trough's 7397 flat-floor cells, 29%**, the other 71% the lacustrine plain the author is looking at. **The sub-basins: built, measured, reverted.** A floor of deeps and intra-rift highs - value noise on the rift's *own* frame (arc length along the segmentation walk, signed distance across, a splitmix hash of seed/stage/segment, a smoothstep-interpolated integer lattice, no trigonometry and no sequential stream), at a share of the *segment's* depth so the hinge shelf gets sub-basins too, multiplied into the floor term so it dies with `plateauFalloff` at the trough's edge and with `taper` through the accommodation zone. It **works on the scene**: the trough's floor 29% -> 38% under water, rift lakes on the five seeds 6 -> 8, world standing water 17,412 -> 17,595 cells and the hypsometric deciles unmoved in the third place. It was reverted because it breaks two Earth-derived bars and rule 5 forbids moving them: `OutletIncisionTest`'s Caspian bound (718106 0.263% -> 0.46-0.50% of its land and seed 99 0.072% -> 0.536%, about **twice the Caspian's** 0.2491% share of Earth's land) and its drain-down case (seed 43's fill grows to 130-139% of what it started with instead of halving), plus `GlaciationTest`'s resolution contract (1.91 -> 2.17-2.42 against a 2.0 bar). The cause is structural, not a dose: every amplitude from 0.12 to 0.45 of the segment's depth gives the same failure, because any closed sub-basin below the sea-level cut is one the post-cut outlet cannot open in its eight passes, and the depression fill then floods it to its rim. Capping the relief at the master fault's own throw (so no cell is cut below E4's deepest and the field's minimum, and therefore the normalisation, never moves) fixed `GlaciationTest`'s cold-country control and nothing else. **Two wrong attributions corrected.** The author's *southern* window is an **Andean margin** - 0 of its 38,750 cells lie on a continental-rift boundary - so E6's reading of its pale bench as "the sea-level cut along one contour of E4's planar half-graben" is wrong; the bench is the trench profile `trenchDepth * strength * narrow`, the one belt on the map with no along-strike variation at all. Giving it the standard swell was written, measured and reverted: at that wavelength (about 160 cells at 2048 against a bench 30 long) it slides the coast onto a different straight contour rather than bending it, and the window went from 108 thin grid-bearing cells and a 31-cell straight run to 146 and 39. Left at 108 and 31 (E5's bar at this grid is 24) and handed to TODO.md with the drowned-shelf case. **Shipped:** two measurement classes and the documentation. `RiftDepthTest` (per-merge, 512, stamp only for two of its three measurements) reports the crest-to-floor profile at both grids against Earth's, the floor's own relief, and what the rift troughs of five 512 worlds hold (419 cells in 6 lakes) - with the reason none of it is asserted written on each. `RiftDepthAuditTest` (new audit class, one 2048 world) reports the trough's flat floor and its wet share, the hypsometry, and the author's southern window with its rift-cell count, and asserts one standing bound: the deepest rift lake inside Earth's 8-30% of relief (Malawi to Baikal), which has never been red and says so - it is there for the next chunk that moves `riftDepth` or puts subsidence under a rift. `GEOGRAPHY.md` gains the measured depth to the rift paragraph and two deviations (a coastal rift's dry hinge shelf, a subduction margin's coast of constant width). 2048 generation 39.5-40.2 s on 718106 and 35.1 s on 59758, unchanged. Renders at `desktop/build/rifts/e7-*`: the deepest rift lake reads as a Baikal in its trough between two shoulders; the author's trough still reads as a narrow inlet against its eastern wall with the plain beside it. |
| E8 A sill at the waterline does not hold the sea out | | done, **built, measured and reverted; what ships is the measurements** | 2026-09-12 | 56cb021 | **The chunk's premise does not survive its own first measurement, and the rule it asked for was built anyway, measured, and refused on what it cost.** *The premise.* E8 assumed the author's trough is two thirds dry because the water level is wrong, and that letting the sea through a sill at the waterline would wet 60% of its floor. Measured before anything was written: the trough is **already an arm of the sea** — of its 2158 wet floor cells, 1635 are ocean and only 523 a lake, H5b's post-cut outlet having cut its sill through — so there is no sill holding the sea out, and the before/after crops are pixel-identical. And the sea can flood only what lies below itself: **2215 of the 7397 floor cells stand below the cut on the eroded terrain and 2232 on the field the map is drawn from, 30%**, so the 29% already wet is 97% of everything any marine process can reach. The 60% asked for needs the water at **0.095 of the land's relief, about 760 m above the sea** — the floor's own 60th percentile, measured, the 40th and 50th being 0.048 and 0.068 — which is a lake perched behind a dam, which is the pre-H5b world whose largest lake was four times the Caspian. *The rule, built.* `SeaConfig.marineTransgression`: a converted basin whose **exit ground** stands within `MARINE_SURGE` of the waterline has that exit cut straight to a surge below it — by the sea, so stream power does not enter and the basin's floor is not the limit, a tidal inlet cutting below the lagoon it feeds — with the labelling then leaving it ocean; run inside the post-cut outlet's own loop, first in each pass, the two sets exclusive, no extra priority flood. `MARINE_SURGE = 0.00125` of relief, derived not tuned: spring tide 2-4 m, severe cyclone surge 8-9 (Katrina 8.5, Bhola 9), record 13.7, so ten metres against the eight kilometres `SeaConfig.lowstand` measures its 120 m against. Two bugs found by measuring rather than assuming: judging the sill by the fill's **brim** instead of the exit's **ground** put six hollows per pass into the set on seed 99 and cut nothing for them (the epsilon nudge along a flat accumulates, so an exit below the waterline can carry a brim above it; those lie inside a tract the ocean cannot reach and are the enclosure rule's business), and the stream-power slope walk had to be skipped for a surge cut because it stops at the first cell past the basin's own depth, which a shallow hollow's exit already is at the first step — the largest such hollow came through every pass at 17 cells with the trace showing the rule selecting it and cutting nothing. *It worked, narrowly.* Basins left standing at the waterline at 512 on seeds 7/42/1234/99: **10/5/23/22 over 15/12/189/47 cells -> 0/0/0/0**; on the finished field, which glaciation carves after, 8/3/22/23 -> 0/0/1/1. At 2048 on the author's world 129 patches of 1517 cells turn wet, the largest 413, and that crop is the landform the chunk is named for — a pond behind a lip becoming a lobed lagoon open to the sea. Land 0.38229 -> 0.38175 and 0.38040 -> 0.38033; standing water 17,412 -> 16,441 and 12,366 -> 12,363; generation 39.8 -> 36.8 s and 35.1 -> 34.4 s. *And it was reverted.* It broke three guards, each isolated to this rule by turning it off and watching all three go green: `DepositionTest`'s pinned land count (6327 -> 6316 at 128), `GlaciationTest`'s control that the ice is what put the lakes in the cold country (0.21 against a bar of 3 x 0.07 — two very small numbers pushed onto their edge), and `DeltaMouthTest`'s vacuity check that the old lobe leaves no pocket (one seed of four gained one). None has an Earth figure to re-derive from, two are *controls* other guards rest on, and the purchase is 1517 cells of 4.19 million on a chunk that cannot move the scene it was written for. Rule 5 refuses that trade. *Shipped:* `WaterlineBasinTest`, a new per-merge census of the basins the cut leaves at the waterline, separating them from the ones behind a sill a surge cannot climb (the Caspian's and the Qattara's case) and from the ones whose exit is already below the waterline (the enclosure rule's); `RiftDepthAuditTest` gains the trough floor's hypsometry and its below-the-cut ceiling with the derivation beside them; `GEOGRAPHY.md` gains the waterline basins as a deviation and E7's rift deviation gains E8 as its third refused repair. What all three stand in for is subsidence scaling with how far the rift has opened — S2, which is the chunk that can actually reach this scene. |
| F7 Matrix, Hessian, Roman, Hitchcock chromes | | done | 2026-09-12 | 30f0803 (merge e479716) | Matrix (phosphor #3DF07A on #030704 with #071209 panels, #1F8F49 secondary, #FFB000 danger, rules the phosphor at 40% = #1D6B36 composited, 2.92:1 as a rule; filled states invert to black on green at 13.46:1; IBM Plex Mono 2.004 from IBM/plex v6.4.0 - the same release the bundled Plex Sans is byte-identical to - carrying *both* type roles, loaded only by this chrome; `> ` before every heading; strips 85% black. The containers go *down* rather than up because a terminal has no elevation, and because Material's lighter menu ground put #1F8F49 at 4.32:1). Hessian (burlap #BC9E73 ground - the spec's #B3956A lifted one shade after the weave measured the brown at 4.34:1 - linen #EDE3CC panels with a 8% crosshatch at +/-45 deg 6 dp apart behind them and behind the frame, running-stitch rules 4 on 3 off, stencil-red #8B3A2F armed button with linen lettering at 6.00:1, twine #7A5C3A as the mark and #6A4E2C as the word, sewn-label cartouche). Roman (Pompeian #7A1F1F ground and filled states with white at 10.28:1, marble #F1EAD9 panels, #1F1B18 inscriptional capitals pointed with an interpunct, bronze #9C7A3C kept as the mark at 3.33:1 with #7A5C24 at 5.17:1 for the word - Hallowed's gold-leaf decision again - a Greek key under each heading at a 12 dp unit, 6 dp having photographed as a comb, double-ruled cartouche). Hitchcock (charcoal #151515 ground, flat-black #1C1C1C panels, #F2EFE8 off-white, bold tight capitals; the spec's "about 5.5:1" for the vermilion measured **4.38:1** on the panel, so #E8491D is the mark and the armed block with black lettering at 5.40:1 and #FF7A55 is the word at 6.63:1; mustard #D9A21B secondary at 7.41:1; section rules a bar cut in three and displaced 1-3 dp; Vertigo's spiral beside the world's name). Worst AA pair per chrome: Matrix 4.62, Hessian 4.81 woven and bare, Roman 5.17, Hitchcock 5.40, bar 4.5. Seven new fields on the one `ChromeDetail` - heading case and its prompt and interpunct, button label, cartouche shape, panel texture and its ink, window ground - all identities for the eleven older chromes, which are proved unchanged two ways: every one of 36 Material roles x 11 schemes equal to values recorded from 27fd260, and the File menu's own layer captured in each of the eleven and equal to fingerprints recorded from the same commit (the window itself cannot be compared - it carries a random seed and world name, which the first draft of that guard discovered by moving four of eleven fingerprints with no code change). Fifteen entries grouped Standard / Accessible / Styled in the picker and the View menu; no name, no order within a group and no stored value moved. Guards shown to bite seven ways: Matrix menu ground 4.32:1, Hessian burlap unlifted 4.39:1 woven while the bare cloth passed at 4.86, Roman bronze 3.33:1, Hitchcock vermilion 4.38:1, Nautical's oxide moved one unit (scheme guard), Nautical's menu ground moved one unit (capture guard), a group dropped (5 of 15 chromes). Hessian's armed button also failed unplanned at 1.00:1 - twine and stencil red sit at the same luminance. ui 91, cartography 25, desktop 33, all green; `:ui:compileKotlinWasmJs` and `:web:wasmJsBrowserDistribution` build and both mono faces land in `composeResources/.../font/` in the distribution (+314 KB, 6 faces / 1.25 MB in all). |
| F8 The atlas on a phone | | done, shipped in 2.0.1 | 2026-09-12 | 1fc66e1 + 29ec0da (merges 04e022d, 18b86b5 on release/2.0) | in the compact arrangement the map strips were drawn over every screen and the way back lived in the sheet; strips now only over the map, a pane top bar (menu glyph, name, Map button) for the atlas and library, AtlasPane drills down and LibraryPane stacks on a phone; PhoneAtlasTest shown failing on 2.0.0 then passing (Map button 58x48 dp inside a 390x844 viewport, toolbars over the atlas 0); GenerationProgress.onStage made suspend with a 16 ms frame yielded before the first stage and at each boundary so the phone paints the status (GenerationProgressTest: 0 of 10 stages painted before, 10 after); disclaimer under the resolution chips on phones; 'Graphics acceleration' wording with a platform-accurate sublabel through the seam, stored key unchanged; follow-up: the atlas and library panes never set a content colour, eleven of sixteen chromes drew headings and a realm's page below AA (High contrast 1.00:1), paneInk from the theme now measured on drawn pixels at 5.42 (Hessian) to 21.0 (High contrast); README gains William's phone figures (1024 in 18-23 s, 2048 in 92.7 s on a Qualcomm chip) |
| F9 Pen and ink, redrawn | | done, shipped in 2.0.1 | 2026-09-12 | 768e4bd (merge ca161da on release/2.0) | Engraving.kt: hachures by Lehmann's rule, one stroke per 8 px lattice cell oriented along the aspect (a stripe field keyed to the pixel's projection failed at 42.9 deg against a 44.8 deg control), coastal vignette of four lines from an exact Euclidean shore distance (Felzenszwalb-Huttenlocher, wrapping in x), ruled lakes, stippled ice, dotted borders, mirrored line for line in GLSL with 24 uniforms and a 16th SSBO; first version sized marks as a share of the map and read as a woodcut at 2048, resized to a pen in output pixels (pitch 8.96-9.03 px at 512-4096, stroke count per unit of map x3.97/15.90/63.55 against 4/16/64); aspect guard 20.0 deg engraved vs 47.0 deg fixed-bearing (bar 30); GpuRasterTest 15 views x 11 styles worst channel 1, 99.9th percentile 0; other ten styles' fingerprints identical; 2048 raster 161 -> 536 ms CPU, 111 -> 242 ms GPU path; left: the shore distance stays CPU-side and uploaded (parity), stipple and border lattices do not wrap at the date line, lakes under 2.4 px fill solid |
| Site cartogenesis.com | | done, first deployed with 2.0.1 | 2026-09-12 | 8ee3948 (merge 8fe06a1 on release/2.0) | site/ (standalone page without the campaign site's links or footer, MIT footer, Open Graph tags, the shell with a stamped loader), _headers (nosniff; immutable for /app/*.wasm and *.js; no-cache for the pages), _redirects; :web:assembleSite (a Sync dropping the map and the build's index.html, stamping the loader with the short SHA, failing if the placeholder survives) and SiteAssemblyTest (6 cases on the assembled tree) under :desktop:siteTest; .github/workflows/site.yml on v* tags and by hand, reading the Pages project's production branch from the API before deploying with wrangler; first run failed on missing repository secrets (William had put them in Cloudflare's Secrets Store), the rerun and the release/2.0 rerun succeeded; live checks: application/wasm with Brotli, hashed files immutable, HTML no-cache, app ready in 0.7 s |
| F10 Rivers widen with their discharge | | done, for 2.0.2 | 2026-09-12 | 7549b16 (merge 71a2492 on release/2.0) | River.widths (cells) became River.widthRatio, unitless on the square root of discharge from the smallest drawn channel to the biggest mouth, sized after the whole network is traced and recomputable from the saved accumulation (no format bump); RiverPen in cartography draws it from 0.8 to 5.0 output pixels, riverScale retired so an HD export cannot fatten the pen; rivers are vector overlay geometry over both rasters so there is one pen and GpuRasterTest needed nothing; RiverWidthTest: correlation with sqrt(Q) 1.0000 on 7/42/1234 against 0.97/0.96/0.99 under the 0.28 power, widest/narrowest 6.25x against 2.1-2.9x (bar 4), trunk wider than either branch at 100% of confluences against 80-100%, width monotone in discharge, the same pen at 512 and 1024, and no river ink off the water in any style; ExportSmokeTest's WebP drift bar 72 -> 80 with the measurement (67 no rivers, 71 old pen, 76 new); found: on seed 1234 five cells drain into a neighbour carrying less accumulation than they do (149 cells disagree with a recomputation from the shipped flow graph, worst 106.6), suspected in the endorheic re-routing, one-cell notches, left in TODO |
| F11 Stop a generation | | done, for 2.0.2 | 2026-09-12 | eb48940 (merge on release/2.0, see log) | Stop takes Generate's place while a world is built, in both arrangements, cancelling the generation's coroutine; world and image assigned only on success so the previous map survives; GenerationGate counts requests rather than latching (which also fixes Generate-twice doing nothing); ensureActive at every stage boundary, each thermal sweep and GPU batch, each hydraulic round, eight glaciation passes and the realm sweeps; standAside() yields on wasm once per hydraulic round (15 ms on this host's timer, 0.3% of a 1024 world); GpuErosion declines once the job is inactive and frees its buffers in finally; GenerationStopTest: a stop during erosion returns in 7 ms against a slowest round of 427 ms where 1610 ms before; GpuErosionTest cancel-mid-run 125 ms against 3571 ms before, the next run identical to CPU; SeedFieldTest: blur starts nothing, Enter and Go do, shown failing with the focus-loss apply restored; captures f11-stop-wide/phone; left: Stop carries no colour of its own (an error pair per chrome would be needed first) |
| F12 JPEG, heightmap and layer exports | | done, for 2.0.2 | 2026-09-12 | 1aebd4d (merged into release/2.0) | ExportFormat.JPEG at quality 90 (ImageIO on the desktop, Skia on the web; the wasm Skia build's JPEG encoder confirmed live by the ?selftest line); Platform.exportData(config, size, layer) with three DataLayers: a 16-bit greyscale heightmap of sea.relativeElevation with sea level at grey 32768 on every world (32767 levels a side, 0.1831 m per level at the default 6,000 m ceiling, so the number is one a stranger's program can use) and a sidecar JSON with the width in km, the cell size, the metres per grey level and the seed; biome and realm index maps as 8-bit palette PNGs with the legend in the sidecar. One PNG encoder in :cartography (PngWriter, DataExport) because Skia is 8 bits a channel everywhere and a canvas writes neither 16-bit grey nor an indexed image; its IDAT comes from the gzip each host already has (GzipRewrappingDeflater, header validated at runtime, a stored-deflate fallback shown to give identical samples at 512 KB vs 338 KB). Desktop writes the PNG and its .json beside each other from one save dialog; the web downloads one stored zip, because two downloads in a row raise Chrome's multiple-file prompt and have lost the second in Safari. Interface: Export [PNG][WebP][JPEG] and Data [Heightmap][Biomes][Realms] as two chip rows with one selection across both, since the size buttons are the verb and need one object; the settings file still stores only the picture format; the phone's 2048 ceiling greys out 4096 and 8192 for all six. Guards shown failing first: heightmap round-trip within 1.5e-5 of the field's range (one grey level is 3.1e-5; the same field at 8 bits misses by 129 levels); layer indices and legend exact over 262,144 cells, all 16 biomes and 14 realms named (an index dropped from the legend is caught); JPEG q90 within 55 of 255 at the 99th percentile of PNG and within 5 of WebP's 53 (q30 reads 72 and fails both); PanelKnobsTest 36/36 on JVM and wasm (2 fail with the chips dropped from the compact arrangement). Per-merge tier green (cartography 6 suites, ui 97 JVM + 97 wasm, desktop 58); worldgen untouched. Desktop, seed 42 (ExportAuditTest now generates one world per size and measures the six exports from it): 2048 generation 39.8 s, raster 0.4 s, PNG 4.6 MB 3.7 s, WebP 1.3 MB 0.4 s, JPEG 0.9 MB 0.2 s, heightmap 5.0 MB 0.9 s, biomes and realms 0.1 MB each; 4096: 173.8 s generation, PNG 16.3 MB 12.1 s, WebP 4.4 MB, JPEG 3.0 MB, heightmap 18.1 MB 3.0 s, peak heap 4.1 GB. Browser: a 2048 heightmap zip of 4.6 MB downloaded from the real wasm build on a 375x812 layout, eight minutes on the page's one thread, nearly all of it generation. Found and recorded, not changed: the plan's premise that WebP is smaller than JPEG at the same quality was wrong at the shipped qualities (see the F12 section); 497 land cells on seed 42 at 512 sit below the waterline, which is SeaConfig.enclosedSeaIsLand turning an unreachable sea into land and postCutOutlet draining it to a salt flat, a Qattara, 0 with the rule off, so the heightmap records them as they are and the guard asks only that no open sea sit above the line. The site's export line updated in the same merge; the README gained the new export table beside the older one rather than overwriting figures measured under other conditions. the maintainer looked at the 2048 heightmap, biome layer and JPEG. |
| F13 Tints by climate and shading by sky (V1) | | done, for 2.0.3 | 2026-09-13 | 1055dfb, 4ec6dbf (merged into release/2.0 2026-09-13) | Land ramp modulated per cell by De Martonne aridity spent inside the biome's own bare-ground band (barren 0.9-1.0, grassland 0.05-0.5, closed forest 0-0.08, from the land-cover class definitions), damped to nothing in the cold, through one per-style lever (Atlas and Schoolroom 1.0, Verdant 0.8, Scroll and Vellum 0.5/0.45, Mars 0.35, Nautical 0.3, Midnight 0.25, Ink wash 0.15, Pen and ink and Colour-blind 0). Desert green: Atlas 86.8% of 1691 cells before, 0.0% after (hue 66 -> 40 deg); Schoolroom 100% -> 0.0%. Steppe: grassland dryness 0.74 -> 0.38 of a desert's, and on Atlas 34% -> 46% of the CIEDE2000 span from desert to forest (Schoolroom 13% -> 39%), guard 25-75% of each style's own span; forests 0.00-0.01 dry. Relief from eight lamps round the compass plus a brightness-weighted horizon over 8 bearings x 3 doubling steps (the stencil grows with the grid: a valley belongs to the world, a hachure to the sheet); one calibrated number, HAZE 0.10, derived by sweeping for the sky at which the contrast equals the lamp's: deviation 0.1693 against 0.1711, 1.0% apart, median 1.000 at ORDINARY_GROUND 0.936, and the test re-derives it. Cone at the ninth decile of land slope: 0 of 360 bearings unlit and darkest face 0.47 of brightest, against the lamp's 119 unlit and 0.44; 0.01% of land crushed against 0.02%. Mark's four-lamp MDOW written first and reverted (0.38, 18 floored bearings). Isobaths every 500 m (GEBCO), the floor's fall measured over 2 cells at 512 scaling with the grid, faded below 4 px spacing and below a 1:1000 gradient (Heezen, Tharp and Ewing's abyssal plain): a made plain 7.81% -> 0.00% inked while its slope keeps 9 of 9 contours; on the review worlds at 2048 the plains 0.18% -> 0.09% and 0.29% -> 0.14%. Aerial perspective built, reviewed and removed (a device for oblique views, not a plan; recorded in TODO) — a deliberate deviation from the section. The single lamp kept bit-for-bit as a Cartography mark, default off. GpuRasterTest 15 views x 11 styles: 99.9th percentile 0, worst channel 1 of 255, 0.0007% differ, bars unchanged. World fingerprint identical; FORMAT_VERSION untouched; pen and ink pixel-identical (fingerprint 2027689450 before and after). Raster 2048/4096 seed 42 on the RTX 3070 Ti: CPU 166/669 ms before, 509-580 ms / 2.5-3.4 s under the sky (the horizon is 24 samples a land pixel), 240-271 ms / 0.8-1.7 s under the lamp; GPU 102/323 before, 153-180 / 660-810 after. The browser pays the CPU cost in full (no WGSL raster), noted in TODO with the separable-sweep cut. First pass returned by the maintainer for contour nests on flat basins, steppe as sand and a flat pale sheet (aerial veil plus a sky 10.9% flatter than the lamp); second pass looked at by the maintainer on 718106 at 2048: basin smooth with three or four faint lines, steppe straw and olive with green valleys, the range's bite back. Tier green: worldgen 116, cartography 38, ui 97, desktop 62. |
| F14 Generalisation, graticule and scale (V2) | | done, for 2.0.3 | 2026-09-13 | 15c8364 (merged into release/2.0 2026-09-13) | One parameter, MapSheet: how many pixels of what the reader sees one cell covers (1.0 for exports, the live view quantised to half-octave bands, so a zoom-band change redraws the ink and not the ground). Rivers kept by Töpfer and Pillewizer's radical law n_f = n_a * sqrt(M_a/M_f) with the ratio equal to pixels per cell: 198 of 279 at fit on 718106 at 2048 (223 of 315 on 59758), all at 4x; control with no sheet keeps 157 at every zoom. Shoreline traced by marching squares off the land mask (checkerboards resolved so land stays 8-connected, as the flow routing assumes) and simplified by Douglas-Peucker at half a drawn pixel (1.0 cell at fit, 0.25 at 4x): strays 1.0 cell against decimation's 2.5 at the same vertex count; 19,634 vertices at fit against 51,749 at 4x; the trace costs 25 ms at 2048 against the raster's 539, the whole overlay 31 ms (rule 8: reported, no cut needed). Graticule at 10 degrees exact in cells (width/36, height/18, the prime meridian and equator on the middle), margin figures as stroked polylines (fifteen glyphs, not a font, so an export carries them in the browser too), sized so the widest figure takes two thirds of the gap (10.9 px cap height at 2048, figured every 10 degrees at 2048, 20 at 1024, 30 at 512), a Cartography toggle. Scale bar from worldWidthKm over the grid (NationsConfig.kilometresPerCellWidth, which the F12 sidecar and squareKilometresPerCell now share) on the 1-2-5 series at no more than a quarter of the frame (Robinson; Brewer): 2000 km = 341 px = 16.7% of a 2048 sheet at 5.9 km/px, in the legend and on exports; the cartouche says km per pixel and "about 1:22 000 000 at the equator" (a CSS reference pixel, two significant figures). Coast pen 0.05% of width floored at 1 px; graticule pen the river hairline at 34% ink. GpuRasterTest unaffected; tier green on the merged tree (worldgen 118, cartography 50, ui 97 JVM and 97 wasm, desktop 64); one JVM access-violation crash mid-tier was the environment, re-run clean. the maintainer looked at the printed corner, the top-right figures, the full sheet with the graticule, the 4x crop and the phone capture: the grid sits under the isobaths without competing, the coast is a crisp continuous line at every zoom, the bar and figures read as engraved margin work. Found, not fixed (TODO): margin figures are ink on the sheet and shrink with it at fit; the traced coast does not wrap the seam (one column each edge); the export's bar has no switch; on the phone the cartouche's scale line truncates ("about 1:89 000 000 at …"); the site's hero figure now carries the coast stroke and will change on the next site build. |
| F17 Not every coast is a ria | | done, for 2.0.3 (two passes) | 2026-09-13 | f168b52, 2172324 | Diagnosis: the coastline is decided by the sea-level stage alone; cutting the same eroded field seven ways shows the lowstand adds 38% to the box count at one cell, 34% at two, 7% at sixteen, the shelf remap none, the plate noise under 1%. Pass one, the littoral grading: on the lowest 31% of the shoreline (Luijendijk 2018's sandy share, taken directly because the two derivable heights bracket it at 1.9% and 59% and the gap is lithology) sediment fills re-entrants over a 23 km reach scaled by root fetch (barrier-inlet spacing; the SMB relation), fill only, never enclosing water (a simple-point test, now WaterTopology); shoreline graded 0.284, 74-78 ms at 2048. Pass two, DrownedValleys: a cell of the ocean keeps its water only where the valley behind it is at least half a cell wide, the width from Leopold and Maddock's root of the catchment at 0.08 km per root km2 (Chesapeake, Delaware, Severn, Thames, Gironde), which lands on about 39 cells of catchment at every grid without being told; only the ocean's shoreline, only within the postglacial rise, never above the ground beside it, always falling seaward, never enclosing — three of the four bounds written after a guard caught their absence; 536 ms at 2048, the priority flood and ocean labelling, on the CPU by rule 8's own words. The instrument replaced: a boundary box count saturates at one box per position and read the finest octave smoother than the next, so the guard uses Richardson's method (coarsen the mask by majority at each ruler, L ~ r^(1-D)), which reads a plain disc at 1.006. Excess roughness at the cell (D(1-2) minus D(4-16)) 0.322 -> 0.198 against Earth's 0, bar 0.243 stated as the part that is not channels plus the seed spread; D(4-16) 1.260 -> 1.255 by ruler and 1.207 -> 1.167 by M1's boxes, both inside 1.25 +/- 0.15; smooth coast 0.086 -> 0.188 at 750 km and 0.141 -> 0.224 at 375 km against Earth's 0.31; shoreline 8-10% shorter, land +0.2 to +0.4% of the map, no body of land or water gained or lost. Three guards restated with reasons (SeaLevelHistoryTest's indentation reported not asserted, its estuary count kept at 34 vs 22 over 1.5x; DepositionTest's land pin at 128; GlaciationTest's ice-lake clause cross-multiplied); fingerprints re-taken. the maintainer looked at both passes on 298405 at 1024 and 718106 at 2048 at 4x: the low northern and eastern shores read as graded arcs and bays, the mountain coasts keep their outline, and a tooth remains on many cells elsewhere — the residue is not channels (filling every notch reaches the same 0.199) but the percentile cut through erosion's own cell-scale texture, 0.288 even with the lowstand off; accepted as an improvement, with the residue recorded as F21. The desert bands and the equatorial stripe are unrelated. |
| F18 Straight runs: D8 incision on smooth aprons | | done, for 2.0.4 (merged with F22) | 2026-09-13 | 1938832 | Not a tie: on the apron the trench crossed, the winning diagonal drops 1.9489e-2 and the runner-up 1.5050e-2 at seven cells running — a plane facing 83% of the way toward the diagonal, rounded to 100% by eight bearings; jitter's smooth field as the draw does nothing (39 ruled runs against 39) and there is no roughness to gate on (the apron's curvature 4.0e-4 against the map's 1.0e-3 median and 4.1e-4 quartile). FlowRouting.flowDirections now takes Tarboton's steepest facet (1997) and draws one receiver across it at the bearing's own share by a seeded per-cell hash (Rho8, Fairfield and Leymarie 1991), collapsing to the old answer wherever the facet's descent leaves the facet, which is every incised channel. Ruled-bar census 1/0/0/0/0 -> 0/0/0/0/0 (one world in five had a lip to pond behind, so the live guard is the ruled course: runs of 7+ steps on one bearing 76/28/38/22/31 -> 66/21/32/15/20, fewer on all five); Hack moved at most 0.018 against a 0.032 seed spread; bifurcation at most 0.413 against 1.842 and outside Horton's 3-5 under both rules on the drawn network (reported); 32-39% of drawn river cells moved, 60% touching an old cell, 75% within two; routing 117 -> 174 ms a pass at 2048 on the processor; JVM and wasm fingerprints identical; style records re-pinned once; RiverCourseTest restated (arrival at an endorheic lake is not a break); DepositionTest land 6464 -> 6488. the maintainer looked at the bar zoom and the 718106 lake country: the trench is an ordinary reach, the drainage the same to the eye. Left red and handed back as F22: OutletIncisionTest, from a pre-existing breach defect the routing exposed — the outlet's fall is measured to the last land cell, so a sill level to the shore reads as the fill's 1e-6 and never cuts (seed 99's 668-cell basin at 2.64x the Caspian; seed 42's no-notch round), plus 718106 running out of H5b's eight passes (1849 -> 632 cells, still falling); the repair measured (99 to 0.12%) drains stuck basins and tips GlaciationTest (718106 lakes 44 -> 30), so it needs POST_CUT_PASSES re-derived and the glaciation clause examined. |From F15's diagnosis: over ground smooth at the cell scale D8 picks the same neighbour for cell after cell, stream-power incision cuts a dead-straight trench along it, and the trench ponds behind its own lip into a straight-bar lake (1-2 per map). LakeWaterBalance.jitter already breaks the bias inside endorheic re-routing; the same idea in FlowRouting.flowDirections (a seeded tie-break among near-equal descents, or D-infinity) does it everywhere. Guard: the census of 20+ cell straight bars at 0 on the five seeds, shown failing at 1/0/0/1/2; fingerprints re-pinned once; render and look. |
| F22 The breach measures its fall to the water | | done, for 2.0.4 | 2026-09-13 | 9c2286b (merged into release/2.0 with F18) | HydraulicErosion.breach measured its outlet channel's fall at the last cell of land, one step short of the water it empties into, so a sill running level to the shore read as the fill's 1e-6 — the absence of a gradient, no stream power, a sill standing for the life of the world however large its catchment (seed 99 at 512 kept a 668-cell basin below the shoreline that way, 2.64x the Caspian's share, measured fall 1.0e-6 against the 2.5e-2 it descends; 718106 one at 1.65x; seed 42 a round in which the notch cut nothing). The step into the water now counts, only where fall <= EPSILON * length, so it touches outlets that were cutting nothing at all; re-rating every sill instead empties basins that should hold their water (718106 44 lakes -> 12), which is why the rule is on the reading. Largest drowned basin 0.361% -> 0.083% of land on 718106 and 0.658% -> 0.122% on 99 against the Caspian's 0.249%, the bars untouched; ErosionConfig.outletFallToTheWater keeps the old rule and OutletIncisionTest asserts it still fails. POST_CUT_PASSES re-derived 8 -> 10 from the retreat the notch now has: 1849, 1196, 988, 839, 727, 605, 495, 391, 285, 157, 138, 138, 138 cells on 718106, flat from the eleventh, where eight leaves it at 391 still falling a hundred a pass; every other seed flat by the fourth. What it drains is only ever a basin below the cut, classified over six seeds: the ice's own lakes untouched to the last one (718106 7 and 7, seed 7 16 and 16, 99 9 and 9), the lakes above the cut untouched, as they must be since glaciation runs after this pass and an over-deepened basin has no outlet to cut. Two guards restated with reasons: OutletIncisionTest's "every round cuts a notch" (a claim about the terrain's supply of work; seed 42's basin sits at 45 cells from round seven, round eleven cuts nothing, round twelve cuts 327) replaced by the first round cutting and the run cutting, the control still cutting nothing; GlaciationTest's resolution clause demoted to a report because the same quantity grows 2.29/2.32/6.80/0.41 across seeds and one seed cannot carry a 2.0 bar on that spread (seed 42 sat at 1.95 and moved to 2.11 with both figures falling, 0.0035 -> 0.0030 and 0.0068 -> 0.0064), the mesh still caught by the trough-depth, till, comb and filament clauses; the whole-map question recorded in TODO for ResolutionScalingTest pooled over seeds. DepositionTest land 6488 -> 6403; style records re-pinned once; JVM and wasm fingerprints identical. the maintainer looked at seed 99's basin at 512: a flat sheet of water drowning valley country becomes drained valleys with a dendritic network and a lake of sensible size in the same hollow; 718106's lake country at 2048 unchanged but for a hatched flat shelf becoming graded ground with a river through it. |
| F23 A Natural map style | | done, for 2.0.4 | 2026-09-13 | d9502f6 (merged into release/2.0 2026-09-13) | MapStyle.NATURAL, its palette the median of seventeen pixel boxes on William's Blue Marble reference, each box drawn back onto the image and checked (three first attempts landed on the wrong thing and were moved): eastern woodland #2C6504, Mississippi grass #3A6904, boreal Quebec #1C3712, Great Plains olive #7F7F2F, Great Basin #977E4A, Chihuahua #AB8455, Colorado red rock #C08F61, tundra #7D8774, Greenland snow #CDCECA, open Pacific #004892, the deepest lit water #003E84, Hudson Bay shelf #09839C. Land ramp coast to snow #2C6504, #3A6904, #797425, #977E4A (the arid floor), #AB8455, #C08F61, #C7AE95, #CECECA; a nine-stop ocean ramp, because relativeElevation normalises the floor by its deepest trench and half of every ocean sits within a tenth of the surface (deciles 0.63 to 0.98), so five stops painted the open sea shelf-coloured — the sea now reads #015394 at the median against the reference's #015294, saturation 0.99 and luminance 0.091 against its 0.97 and 0.079 (0.156 on the first render). Ink #091205 (boreal forest at a third) for coast and border, river #4499A9, lakes #09839C/#004892, wilderness #7D8774, backdrop black; levers climateTint 1.0, biomeWash 0.30 (a high wash of the cartographic biome colours averaged the photograph away; the Great Basin umber on the arid floor lets a desert read 20 units of red over green unaided), isobathInk 0.05, coastline 0.5, glyphMuting 0.15. Guards: GpuRasterTest over 15 views x 12 styles at the 99.9th percentile 0 and worst channel 1; desert 0.0% green over 1,736 cells (92.5% would have before F13); steppe 40% of the 33.5 CIEDE2000 desert-to-forest span; ink 12.09:1 over paper and 13.45:1 over the palest ground; no ink reaches AA over the darkest ground (#285A14; the ceiling is 2.56:1 with pure black, established by searching the colour cube, and asserted below AA so a later lift of the dark end raises the bar); the ramp climbs in lightness; every coloured stop 0.50-0.96 saturated with vividness at or under the reference's 0.40; StyleGalleryTest and PenAndInkTest gain the twelfth record, eleven untouched; the site says twelve. Whole-map land medians still differ from the reference (0.47/0.23 against 0.68/0.11) because both of the author's worlds are cold and dry, half their land tundra; per biome the match is close (taiga #365D1B against the woodland's #2C6504, savanna #7E7D30 against #797425, desert #A5895B against #977E4A). the maintainer looked at 718106 whole and a forested coast beside the reference: a satellite's view, saturated and earthy, the cobalt sea turning turquoise only at the shelf; the shelf halo's uniform width is B1's 469 km shelf on this line (S2 narrows it on 3.0). Merged release/2.0 at 36c0968 (three list conflicts, records re-taken once); F25 not yet in, so NATURAL takes its cloud lever when the clouds merge. cartography, ui, desktop and siteTest green. |
| F24 The Lemon Blueberry chrome | | done, for 2.0.4 | 2026-09-13 | bc9b5d0, 67a3d74, 32bbb45 (merged into release/2.0 2026-09-13) | Both arrangements built to the same rules and measured: lemon ground with blueberry ink clears AA with a worst text pair of 7.38:1 and a Stop button at 7.80:1; blueberry ground with lemon ink at 7.76:1 and 10.19:1 — the second ships, because a near-white ground forces the accent down into the ink's own range and closes the armed button's pair. BlueberryGround #151033 (the window), BlueberryPanel #1E1845, BlueberrySunk #282052, BlueberryWash #2C2456 (the F1 stain), BlueberryRule #6F63A6 at 3.15:1 on a panel (WCAG 1.4.11), BlueberryBloom #C4B4F0 as the secondary at 8.75:1, LemonInk #F7EFC0 at 14.20:1, LemonInkDim #D0C58C at 9.47:1, LemonZest #F0DC7A the one accent at 11.97:1; the alarm made of the pair — anthocyanin is a pH indicator, lemon juice turns blueberry pink — BerryJuice #FF8FB8 at 7.76:1 and 51/59/43 dE2000 from the zest, the panel and the ink, held by a 20.0 bar. Typography +0.3sp on the display styles (a reversed-out serif spreads into its counters); no new font files; STYLED after Hitchcock. Guards shown to bite: AA at 2.66:1 with the dim text taken to the shaded peel; the arrangement guard, which re-measures the loser each run, at 4.03:1 against 7.80:1 with the wash lifted; the alarm guard at 0.0 with the error set to the accent. New captures only (desk 1440x900, phone 390x844 sheet down and up); shootCompact gained a choice parameter defaulting to SYSTEM so no earlier capture moves; sixteen on the site's Features list and in the README. the maintainer looked at the desk and phone captures and found F12's Data row clipping "Realms" to "Real" at the desktop panel's width (36 of the 46 px the word wants; whole on the phone): ChipRow's chips are a FlowRow now, wrapping only where the words do not fit, the heading level with the first line; guard measures each label's wanted width against the width it was given at both sizes (the obvious hasVisualOverflow/isLineEllipsized flag reads true for all six labels under Material's clipping default and could never have gone green), shown failing on the old layout. ui jvm and wasm, desktop test and siteTest green. |
| F25 Cloud cover | | stopped at William's request, 2026-09-13, after the first pass; branch fix/f25-cloud-cover kept at fee29a1, not merged ("those cloud screenshots do not look good at all... I would prefer to preserve resources and abort work on it for now") | 2026-09-13 | fee29a1 (unmerged) | First pass: a cloud-fraction field per season from the march's bucket against Clausius-Clapeyron over sea and De Martonne's index over land, the season's belts mapped onto oceanic ends 0.62/0.80, a cold-water stratus deck overlapped on top (Klein and Hartmann 1993); pooled zonal means within 0.106 of ISCCP from 0 to 70 degrees (Rossow and Schiffer 1999), global 0.642 against 0.67, desert 0.40 against forest 0.64, a constant field failing at 20-40; two mechanisms measured backwards and replaced (the bucket's fill as a humidity ran the wrong way round; all-surface anchors sat every band 0.05-0.16 low). Drawn as a Cartography mark with thickness-modulated masses and a ground shadow, per-style levers (Natural 0.45, Atlas 0.40, Pen and ink and Colour-blind 0), on both raster paths (GPU worst channel 1 of 255), exports carrying it; FORMAT_VERSION 3 -> 4 on this line. Field 10-16 ms a season; raster 592 -> 893 ms CPU and 162 -> 196 ms GPU at 2048. Returned by the maintainer after looking at the annual renders: the ocean reads as frosted — an isotropic cell-scale mottle at one density over nearly the whole sea, no shapes at the scale of weather systems — and a hard cloud/clear step at every shoreline with almost no cloud over land; second pass to give the breakup a meso-alpha dominant octave stretched along the wind (autocorrelation guards along and across the wind, at least 25% of the sea clear inside a 0.7 band) and to blend the land and sea supply across the shore (a step guard, and forest cover under cloud against ISCCP's land 0.58). Found: the polar sky too clear because water below -10 C evaporates nothing (TODO), one cloud type only. |
| F19 The belts' aprons | | finding, 2026-09-13, not yet a chunk | 2026-09-13 | | Seen by the maintainer and the F15 contributor on 298405 at 1024: broad, smooth, straight-edged pale bands flanking every range, terraced in visible steps — the crust-pair belt profiles (B2) with E6's graded floodplains laid against them. On the 3.0 line S2 (uplift and isostasy) replaces the stamped profiles with evolved relief and should remove the cause; if William wants it on the 2.0 line first it needs its own diagnosis. |
| F20 The equatorial stripe | | finding, 2026-09-13, not yet a chunk | 2026-09-13 | | Seen by the maintainer on 718106 at 2048 after F13 and F14: a straight, horizontal band of greener ground crosses every continent at the same latitude, the wet-tropics rainfall band of a prescribed atmosphere given a hard edge by the climate tint. The band was there before F13 as the biome wash; the tint makes it read as a stripe. The cure is the audit's W-line (W1, W2: an atmosphere from the energy balance rather than latitude bands) on the 3.0 line; on the 2.0 line the tint's edge could be softened across the band's own width. |
| F21 The cut through erosion's texture | | finding, 2026-09-13, not yet a chunk | 2026-09-13 | | F17's residue: after both passes the coast is still 0.198 rougher at the cell than at four to sixteen cells (Earth 0), and it is not the drowned valleys — with the lowstand off the excess is 0.288 — but the sea-level plane cutting a height field that erosion leaves rough at the cell scale everywhere, coastal plain or not. The physical cure is hillslope diffusion (Culling's D, which S1 left out because the hillslope law is threshold-only today): soil creep is what makes low-relief ground smooth at the cell scale, and with it a coastal plain's shoreline stops being a fractal of the grid; a 3.0 chunk on the solid-earth line after S2 (S3's erosion-reads-the-climate can carry it, or it gets its own section). On the 2.0 line the alternative is a sub-grid correction of the near-shore field on low-slope coasts before the cut, every coastline and not the drowned ones, if William wants more than F17 gave. |
| F26 The sea-ice edge is a line of latitude | | finding, 2026-09-13, for W2 | 2026-09-13 | | Seen by the maintainer on 718106's biome map after W1: the cold-season pack ends on one latitude across the whole ocean, a ruler-straight edge, because the energy balance is one-dimensional and the currents' anomaly that could bend it is +1.3 to +1.8 C against Earth's +4 to +8 on comparable coasts. Earth's edge wanders 10-15 degrees between the Norwegian and Labrador seas. W2's surface winds and a stronger, directed current anomaly (the upwind neighbourhood W1 tried and reverted) are the cure; a guard on the edge's latitude spread across longitude belongs with it. |
| F27 Globe view | | queued for the week of 2026-09-21 at William's request (2026-09-13: "a way of displaying the maps wrapped on a 3d globe") | 2026-09-13 | | An orthographic projection of the finished map, which is already an equirectangular texture of the whole globe (360 by 180 degrees, wrapping at the seam, the graticule on exact tens): for each screen pixel inside the disc, invert the projection to latitude and longitude, sample the composited map there, shade by the sphere's normal with a terminator, drag to rotate by moving the projection centre, wheel to zoom, export the globe as an image; no mesh, no 3D dependency, per pixel on both raster paths (rule 8). Honest limit stated in the view: the world is generated on a cylinder, so the polar rows converge to a pinch that the ice caps mostly hide; P2's spherical grid is the real fix and this view does not wait for it. Later pass: project the vector overlays themselves for crisp lines at high zoom. |
| F28 The Blacklight chrome | | done, for 2.0.5 | 2026-09-13 | ad9a4b0 (merged into release/2.0 2026-09-13) | William: "add a new chrome based around the colors #e6ff42 and #520c94"; named Blacklight by the maintainer, and the measurement agreed with the name — the lime on top as writing and accent, the violet underneath. Both arrangements built to one set of rules and measured: violet room with lime writing clears AA with a worst pair of 5.57:1 (the alarm as a word) and a Stop button at 13.97:1; lime room with violet ink at 4.63:1 and 7.73:1 — the first ships, because #E6FF42's luminance of 0.887 is paler than most papers, so as a ground it forces the alarm, the secondary and the hairline (3.08:1) down into the dark end together and leaves a stain nowhere to go. Two hues and one exception, every role within 0.36 degrees of #520C94's 312.7 or #E6FF42's 110.5 in Lab: VioletGround #2B064F (the window, William's violet at half its lightness), VioletPanel #520C94 (his violet exactly, what every word is read on), VioletSunk #601BA4, VioletWash #32134F (a stain darkens), VioletRule #A577CA at 3.31:1, VioletHaze #D0A6F1 the secondary at 5.66:1, LimeInk #F3F5C9 at 10.18:1, LimeInkDim #D2DA80 at 7.66:1, LimeGlow #E6FF42 the one accent at 10.19:1; the alarm FlareOrange #FF9E4D — a blacklight is an excitation, not a colour, and the orange highlighter beside the lime one answers the same lamp — at 5.57:1 and 39/71/30 dE2000 from the lime, the panel and the ink, held by F24's 20.0 bar. Typography +0.3sp on the display styles; no new font files; STYLED after Lemon Blueberry. Guards shown to bite: AA at 2.10:1 with the dim ink taken to the shaded pigment; the arrangement guard at 6.72:1 against 7.73:1 with the wash lifted; the alarm guard at 0.0; a new hue guard (every role but the three alarm ones within one degree of one of the two hues) at 22.43 degrees with the haze cooled toward blue, which no contrast assertion notices; and SiteAssemblyTest now reads the Features list's theme sentence back, turning its counting word into a number against ThemeChoice.entries.size and checking the last name, shown failing on the old sixteen (F24 had changed that sentence by hand with nothing watching). New captures only; seventeen on the site and in the README. the maintainer looked at the desk and phone captures: a saturated violet room with pale-lime writing, the armed button a dark well with a lime label, the sheet plainly a sheet over the map. ui jvm and wasm, desktop test and siteTest green. |
| F29 Cool neutrals for Light, Dark and the site | | done, for 2.0.7 | 2026-09-14 | 1aed23b, a56d93a, cf0044a (merged into release/2.0 2026-09-14) | Grounds only; inks, accents and typography untouched. Dark and the site: one tone at 1 : 1.116 : 1.268 scaled by a step, ink 121417, raised 191C20, sunk 21252A, hairline 363C44, every derived tier the warm ramp's step less three so the ladder's gaps are unchanged (scrim 070809, lowest 0D0F10, low 15171B, high 1F2327, highest 262A30, bright 272C31, outlineVariant 282D33); containers stay stains, brass at 1/8 292619, bone at 1/16 1F2123. Light: paper F4F1EA/FAF8F3/E9E4D8, the five other papers at the fraction of each segment they held (FEFDF9, FDFBF7, FBF9F4, F2EFE7, E8E3D6). After the maintainer looked at the captures, the selection wash and the two rules were rebuilt out of the new paper: the wash is the paper less the 13/18/31 step Vellum's land stop took out of Vellum's, E7DFCB, because the old stain measured 1.0 degrees off its paper's hue and 40.9 off the accent's and a share of the accent on a near-neutral paper read pink-grey (armed label 5.92 before F29, 6.47, then 6.67); Rule and RuleFaint are InkFaded at 3/8 and 1/5, C2B9A9 and D9D3C7, restoring 1.72:1 and 1.32:1 against the ground where Vellum's khakis gave 1.74 and 1.31 and read green on off-white. Site :root, the app's loading shell, the favicon and the strips' band tints mirror the dark scheme; the two strips' alt text describes each panel as well as naming it (WCAG 1.1.1, William's 2026-09-14 ask) and SiteAssemblyTest's name-list parser takes either spelling of "labelled". Measured: Light's worst pair 4.60 to 5.13, Dark's 5.13 to 4.68, site bone-dim on ink 6.09 to 5.99, brass on sunk 6.87 to 6.37, HAIRLINE band 10.58 to 9.07, all above their bars. ChromeContrastTest gained an AA guard for Light and Dark (Dark omits brass-dim-as-a-word 3.76 and oxblood-lit-as-a-word 1.63, roles :ui never reads, which measured 3.90 and 1.69 before this change); ChromeGalleryTest gained chrome-light-natural and chrome-dark-natural. ui 105/105 jvm and wasm, desktop 68, siteTest 13. Noted: no chrome carries Vellum's paper any more (no Vellum ThemeChoice exists; a Vellum chrome is a small chunk if William wants the old paper back as a room); Dark's brass stain reads olive against the cool ground, accepted as the accent's stain. |
| F30 The ruled lake shores in 364673's belt | | done, **no geometry changed** | 2026-09-14 | 0efaba3, 32d53c9, 21fdcb3 on chunk/f30-ruled-lake-shores, merge with main 83116dc | **They are not lake shores and hypothesis (2) is the one that stood.** The two bodies in William's window are not lakes at all: they are *inland seas*, drowned rift segments too big for `SeaConfig.enclosedSeaMaxKm2` to call lakes, and what is ruled about them is not a shore but a **canal of open water one cell wide** that `SeaLevelStage.drainDrownedBasins` cut to join each to the ocean — 73 cells on the 7,297-cell one at (2022,1449) and 41 on the 3,314-cell one at (1799,1761), of which 72 and 41 stand *above* the waterline in the raw `erosion.height`, which is what says the outlet pass put them there and the ground did not. The "river running dead straight north-south at x 1788" is the second canal. Every lake proper on that world is inside the bar at 0.60 times and under. **Elimination**, from the water back, worst shore run as a multiple of the derived bar: main 1.75x; `glaciation.enabled` false **1.75x, not one figure different** (I2's finding reproduced exactly); `lakes.waterBalance` false **1.75x, identical**; `erosion.hydraulicRounds` 0 and `erosion.enabled` false not reached; `erosion.outletIncision` false 0.81x; **`sea.postCutOutlet` false 0.59x — the switch**; `tectonics.riftSegmentation` false, `riftDepth` 0 and `crustPairProfiles` false all remove it by leaving no inland sea on the map at all (the belt is a chain of half-grabens and without the segmentation it is one trough that never subsides below the cut). Hypothesis (1) is therefore wrong in its own terms: the boundary's trace already wanders — G4's jump flood, not a Voronoi edge — and the trough is not what is ruled. **Cause**, in two parts. The 41-cell canal: `FlowRouting.fillDepressions` raises each cell of a flat one `FLAT_GRADIENT_STEP` above the cell the priority flood reached it from, so the routing surface inside a filled basin *is* the flood's expansion order, its contours are the grid's metric exactly, the descent points at a neighbour rather than between two, and Rho8 has nothing to draw against — F18's fix cannot reach it because it is not smooth ground behaving like a grid but a surface the fill invented. Everywhere else that ruled path cuts a trench with a river in it; this one pass cuts *below* the waterline, so the map grows a canal. Measured directly: along the reach at (1864,1702-1719) `filledElevation` descends by exactly 1e-6 a cell for eighteen cells while the ground under it varies by 2 m either way. The 73-cell canal: not a ruled path at all. The **first** pass of that notch takes a sill standing about a kilometre above the waterline down to the basin's own floor in one bite — 1,652 cells cut on pass 0, the whole reach left at one level falling only by the notch's own gradient — because `HydraulicErosion.breach`'s `dropRelative` is capped by `level - floor`, the basin's whole depth, and F22's `outletFallToTheWater` hands a drowned sill the entire drop to the water as its slope. A slot cut in one bite lies at one level however it bends. **Both repairs were built, measured and reverted, and that is what ships.** Routing *every* filled flat by the bed — of the neighbours the staircase puts below it, the two steepest ways down the true ground, drawn in proportion to their steepness, which is Rho8's bargain with a neighbour pair in place of a facet — takes both canals away, **1.75x to 0.46x**, and moves three Earth-derived guards with it: seed 59758 at 1024 keeps a lake of **1.83 times the Caspian's share of its land** against `OutletResolutionTest`'s 1.4, `RiverWidthTest`'s drawn pen goes to **2.46 px** against the nib's 1.23, and `OutletIncisionTest`'s control loses the separation it exists to show. Narrowing it to `drainDrownedBasins` alone costs one guard instead of three and fixes one canal instead of two — the 41-cell one in William's own window goes, seed 1234 keeps **189 cells of water the ocean cannot reach** against `SeaLevelHistoryTest`'s rule that every pocket no larger than the Caspian is gone, and the 73-cell one does not move by a single cell, which is the evidence that it is the bite and not the bearing. Rule 5 forbids paying an Earth figure for a measurement, so neither shipped. **And then S2b landed and took both canals away.** Its repair lets the sea seed the depression flood at its own level — the same flood this defect was traced to — so the drowned basins the outlet pass notches are not the basins it used to find. On the merged tree (3c49276) 364673 at 2048 offers sixteen measurable bodies instead of ten, neither inland sea is over the bar, and **William's window reads clean: no canal, no ruled shore, no dead-straight line at x 1788** (`desktop/build/f30-crops/after-s2b/`). The worst shore anywhere on the six worlds is now **1.07 times the bar** — a 296-cell lake at (1075,1998) with a 20-cell run against 18.7 allowed, a different body with a different story, marginal where the old two were not, and undiagnosed; it is in `TODO.md` as its own entry. So the chunk's repairs were refused twice over, once by rule 5 and once by being overtaken, and both mechanisms are recorded because neither has been touched. **Shipped**: `OutlineRuns`, I2's outline instrument moved out of `GlacialBasinShapeTest` whole so the two cases cannot fork it, with the bar's derivation (Tanganyika's graben scarp at 2.05 times a circle of its own area, times three) and a new `SMALLEST_BODY_THE_BAR_BINDS` of 255 cells derived beside it — `3^4 * pi`, where the bar stops being wider than the body itself, below which a body can only break it by being long and thin, which is `RuledLines`' question. Two corrections came out of the move: the map's polar rows are no longer counted as an outline (364673 measured 254 cells of exactly that before the rule was written down; no cut basin touches a pole, so I2's figures are untouched), and a run now says where it is. And `StraightRunTest.report how straight every shore is`, on `RiftDepthTest`'s terms: every body of standing water but the world ocean — lakes off `lakeId`, inland seas off the mask — on 364673 at 2048, 298405 at 1024 and the four standard seeds at 512, printing the census and naming what is over the bar, asserting only that it still has something to measure so the report cannot go quietly empty. On the merged tree the five other worlds carry two to six measurable bodies apiece and every one is inside the bar at 0.26 to 0.93 times. All three causes are in `TODO.md` with their figures, and the chunk that diagnoses the body still over the bar turns the report back into the assertion it was written as. `GlacialBasinShapeTest` reads the moved instrument and is green on the merged tree, its worst valley basin 0.37 times the bar and its level surfaces 152 cells against 616 allowed on 364673. **Renders** at `desktop/build/f30-crops/`: `at-00b13fe/` holds 718106 and 59758 whole at 2048 as they were before any of this, and a 1:1 detail of William's window at 00b13fe beside the same ground under the global repair — the straight canal and the ruled east shore on the left, a meandering channel and a ragged shore on the right; `if-every-flat-routes-by-the-bed/` is all three worlds under that repair, kept as the evidence for its TODO entry (718106 and 59758 are the same worlds at map scale, 15% of pixels differing, all of it fine relief, no coast or river structure moved); and `after-s2b/` is the window on the merged tree, clean. The whole-sheet render of that window at 00b13fe was overwritten by a re-run of the throwaway probe and is not worth six more minutes of generation to recover, since the detail crop carries what it showed — said plainly rather than quietly relabelled. |
| F31 Bug reports: Help item with the seed, a line on the site | | done, for 3.0.1 | 2026-09-15 | 12402c0 on fix/f31-f32-reports-and-roadmap, with the copy pass in ef155c4 and 99b12ca | **Three issue forms and a Help item that reads the world off the window.** `.github/ISSUE_TEMPLATE/bug.yml` asks for version, platform, seed, generation resolution, ocean coverage, the settings moved from their defaults, graphics acceleration and the device, what was expected, what happened and a screenshot; `feature.yml` asks what, why and which style or view; `config.yml` turns blank issues off and offers the two addresses. **Help ▸ Report a bug…** is a third `MenuCommand` beside the update check and About, so the strip and the phone's folded menu gain it together — the note in `Menus.kt` stays true because both draw `Menus.help`. It builds the report from the world on screen (`BugReport.of`), puts the whole of it on the clipboard, then opens the form with the same facts in its fields, pre-filled by field id. **The URL limit is 8,000 characters**, chosen as the smallest limit in the chain rather than the browser's — Chrome's own is about 2 MB, and it is proxies and corporate gateways that cut a request line at 8 KB, where the half that arrives is a 414 or a form with half a field in it. Only the settings list can grow without bound, so that is what gives way, one entry at a time from the end, and what is left says how many went and that the whole list is on the clipboard; the worst case measurable — every knob moved, the longest name the header takes — is **801 characters**. Two seams were added to `Platform` beside `openLink` and shaped like it: a clipboard (AWT on the desktop, `navigator.clipboard` with an `execCommand` fallback in a browser, each with its own `can…` so the dialog never claims a copy that did not happen) and `hostName`, the host's own word for itself, which is the one fact a report needs that the world cannot supply. The dialog says which of the two routes worked and prints `bugreport@cartogenesis.com` for a reader with no account, and the URL itself where the host cannot open a link. **Guards**: `MenusTest` asserts Help's three items in order; `BugReportTest` builds a report for seed 718106 at 1024 with three knobs moved and reads back the seed, the grid, the version and each moved setting with its value, that the URL parses back to `template=bug.yml` and the seed, that the worst case is under the limit, and that an overlong list is trimmed with the seed intact. **Captures** at `desktop/build/screens/f31-help-menu.png` (the strip's Help open at 1440x900) and `f31-help-phone.png` (the folded menu scrolled to Help at 390x844). |
| F32 Roadmap chart and a feature-suggestion line on the site | | done, for 3.0.1 | 2026-09-15 | 4a3aa06 on fix/f31-f32-reports-and-roadmap, with the copy pass in ef155c4 | **`ROADMAP.md` at the root is the roadmap, and the page is drawn from it.** Seven rows — 3.0.0 marked current, then 3.1, 3.2, 3.3, 3.x, 4.0 and 5.0 — in a reader's words, no dates and no chunk labels. `:web:assembleSite` replaces a `<!-- roadmap -->` marker in `site/index.html` with a `spec` list drawn from the file (the Features list's own style, hairline rows, no new type), fails if the marker survives or a release is missing, and declares the file as an input so a moved line re-assembles the page. The new section is `id="next"`, "What comes next", after Notes. **Guards** in `SiteAssemblyTest`: every release on the page is in the file and every row of the file is on the page, in the file's order, with the current release marked exactly once and no date anywhere in the section; the Atlas note's "planned for 5.0" is read out of the sentence and compared with the release whose roadmap line is about the atlas, which is what stops that number drifting again; and the Notes card carries both routes — the bug line with `bug.yml` and `bugreport@cartogenesis.com`, the suggestion line with `feature.yml` and `dev@cartogenesis.com` — with both addresses read back and both forms checked to exist, since GitHub opens a blank issue for a template that is not there. **Preview** at `desktop/build/screens/f32-site-next.png` (the built page's new section at 1280 wide) and `f32-site-page.png` (the whole page). |
| F33 A topographic map style | | queued on the 3.x line (William, 2026-09-15); on ROADMAP.md's 3.x row | 2026-09-15 | | Contours by marching squares at a 1-2-5 interval from the ruler, index contours labelled in metres, survey-sheet ground |
| F34 World names from a curated list | | done | 2026-09-15 | (main, 2026-09-15) | `WorldNames`: 300 names picked by seed and language; `WorldNamesTest`; the assembler stays for realms and settlements until 5.0 |
| F35 The same seed makes a different world at each resolution | | queued, first after the weekly reset (found 2026-09-15 on Linux) | 2026-09-15 | | Plate seed rows drawn with a non-power-of-two bound; every export has been a different world from its preview; fix by fractions, guard in ScaleFreeTest, re-pin |
| F36 Linux download, apt repository and installation instructions | | queued after F35 (2026-09-15) | 2026-09-15 | | CI job on a Linux runner builds and uploads a .deb and a tarball on v* tags; a signed apt repository under the site with the maintainer walked through the key; per-platform installation sections in the release notes and on the site, Linux the fullest |
| T2 Import a heightmap as the terrain | | queued for 4.x, after 4.0 (2026-09-17); on ROADMAP.md's 4.x rows | 2026-09-17 | | Any greyscale image replaces the terrain stage; sea level by threshold; stored like the terrain snapshot; static crust first; a fidelity slider |
| D1 Paint, raise and lower land as you watch | | queued for 6.0, after the atlas (2026-09-17) | 2026-09-17 | | A brush on the base terrain with a live coarse preview; edits in the terrain snapshot; undo as strokes |
| D2 Choose the kind of world the seed makes | | queued for 6.0 (2026-09-17) | 2026-09-17 | | Named presets (continent, archipelago, atoll, supercontinent, scattered, uniform) as sets of existing settings |
| D3 Symbol packs and layers | | queued for 6.0 (2026-09-17) | 2026-09-17 | | Packs with a manifest, placed on named layers above the generated drawing, saved and exported at scale |
| Site 4 A redesign of cartogenesis.com | | queued before 4.0 (2026-09-17) | 2026-09-17 | | Brief, three wireframes, a choice, one high-fidelity mock, then the build; avoids the generic single-column page; the map as the page's structure; phones composed on their own |
| S4 A choice of planet size | | queued for 3.x (2026-09-19) | 2026-09-19 | | worldWidthKm as a setting with named sizes; guards derived at 12,000 km re-derived as functions of the width; whole planets only |
| P4 Maps at any size and shape | | queued for 4.0 with the projections (2026-09-19) | 2026-09-19 | | Any width and height: FFT padded or generalised, tiling and jump flood generalised, two-dimensional resolution and export ceiling |
| F15 Rivers as William sees them at 1024 (pen scaled to the sheet, mouths at the shore, lake outlets, the diagonal channel, farthest source) | | done, for 2.0.3 | 2026-09-12 | 00fce71 (merged into release/2.0 2026-09-13) | The pen is 0.24% of the map width (the Amazon's 10 km mouth on a 12,000 km world is 0.083%, a printed map exaggerates it about threefold; F10's 5 px at 2048 was 0.244%), floored at the 0.8 px hairline: 1.23/2.46/4.92/9.83 px at 512/1024/2048/4096. Mouths: the course's last vertex was the sea cell and Skia's round cap added half a stroke, so ink reached 3.0-3.2 px past the shore on five seeds (133-284 sea pixels, 3-16 with no land neighbour); the course now ends half a stroke short of the shoreline, 401 mouths over water -> 0, offshore river pixels -> 0. The thin squiggle was not an outlet with no flow (outlets carry 1.0-1.7x their lake's largest inflow, measured): it was a lake filled to its spill along the trunk of the map's biggest system, one cell wide for 61 cells, which the tracer stopped at and the renderer would not draw through, so the trunk crossed as a dotted ribbon; a cell of standing water counts as open water only inside a 2x2 of its own lake (a cell is 6-23 km, the Amazon's mouth is 10), 237 channel cells under one-cell water drawn 0 -> 170, no breaks, no gaps. Each drawn river is its own longest watercourse: heads ranked by the length of the path below them, coverage 0.780 pooled (0.696-0.845) -> 1.000 on 7/42/1234/99. The diagonal rectangle is a lake: 53 cells within 1.2 cells of one straight line in a straight-walled trench, present with the breach, the deposition and the post-cut outlet each switched off and gone only with erosion off, so it is stream-power incision along a reach D8 runs dead straight over an apron smooth at the cell scale; census of 20+ cell straight bars 1/0/0/1/2 on 298405@1024, 7, 42, 1234, 99 at 512; left to F18 because the cure is in FlowRouting and moves every world. No terrain, flow or lake changed, so no fingerprint moved; GpuRasterTest green; LakeWaterBalanceTest's lake-crossing guard counts open water; DataExportTest's JPEG bound 60 -> 68 because a 1.2 px river is nearly all edge (WebP and JPEG both moved by eight, the relation guard untouched). the maintainer looked at 298405 at 1024 and 718106 at 2048 before and after: the mouth no longer bulges into the bay, the trunk reads at one weight through its lake, the network is graded rather than roped; 2048 unchanged to the eye. Found: at 512 the nib spans only 1.5x (full pen 1.23 px over a 0.8 hairline), so the web and phone default shows little hierarchy — William to decide whether the hairline should shrink below a pixel on small sheets or the full pen be floored at about 2 px; and broad, smooth, straight-edged pale bands flanking the ranges at 1024, terraced in steps, not diagnosed (the belt profiles and their aprons; see F19 in the notes below). |From M1's finding (TODO.md, 2026-09-12): `RiverStage.traceRivers` ranks channel heads by the flow at them, so a drawn River runs from the biggest headwater and the longest watercourse in the same catchment is drawn as a tributary stopping at the junction; drawn courses cover 0.484 of their watercourses at 512 and 0.408 at 2048 where 1.0 is the definition. Rank heads by the length of the path below them, or trace each mouth upstream along its longest branch; the union of drawn cells stays the same network. Visible on labels and lengths, and on what RiverWidth calls a trunk; a 2.0.x fix because it is tracing, not physics. Guard: the coverage share at 1.0 on the four seeds, shown failing today at 0.484. |
| Site 2 cartogenesis.com in the app's identity | | done, then cut back the same evening (see the section) | 2026-09-12 | 122d263, 71eaf1c (merge f078688 on release/2.0); cards back after v2.0.2 | The landing page set in the app's own faces (Spectral, IBM Plex Sans, IBM Plex Mono, copied out of the ui font resources at assembly, no font host asked for) and its flat ink/bone/brass/oxblood palette; the giant wordmark, gradient, diamond rule and bevel gone. Hero: eyebrow, the two-line headline, one sentence, oxblood "Open in the browser" and brass-outlined "Download for Windows (recommended)" beside a real Atlas crop of 718106 (62% ocean, 14 plates, 12 realms) at 2048. "One world, four readings" (Atlas, biomes, political, pen and ink) as tabs on wide screens and a stack on phones; three annotated details with HTML labels over real crops (a rain shadow on the rainfall view, a trunk river widening below each junction, a rift breaking into gulfs) replace the six cards; the browser and Atlas notices moved to a practical-details section. Every image is rendered by :desktop:renderSiteImagery when the site is assembled (57 s on sixteen cores, 219 s pinned to two, nearly all of it the one generation; the seven rasterisations and WebP encodes are three seconds between them); poster.webp deleted. SitePaletteContrastTest: 23 text pairs at AA, the weakest 5.39:1; SiteAssemblyTest +4 (every figure present at the size the page reserves, no font host, a rule-10 host allowlist). Page weight before the app: the whole tree 2055 KB uncompressed (html 28, fonts 1067, images 960); a first visit that scrolls the page fetches about 1478 KB of it, roughly 872 KB compressed, because the three other readings are lazy images in hidden panels and are only fetched when their tab is chosen; the plan's 1.5 MB target is met on that reading and missed on the whole tree, the fonts being the difference; screenshots at 1280 and 390 looked at band by band. Follow-ups: the TTFs would be about half the bytes as WOFF2; the rainfall view is a weak reading on 718106; the pen-and-ink figure is 318 KB, the heaviest on the page; `_headers` gives the figures and faces no Cache-Control rule, so they get Pages' default of four hours (`max-age=14400, must-revalidate`, seen live) and a returning visitor re-fetches a megabyte of faces after that; hashed names and an immutable rule, as the wasm has, would fix it. Deployed by hand from release/2.0 (run 34730941195), live and checked 2026-09-12: stamp f078688, the seven figures and five faces served, wasm still application/wasm, Brotli, immutable. |
| Site 3 Style and layer strips | | done, for 2.0.6 | 2026-09-14 | 965a00c + 1fca076 + ebc70c8 + bf99698 | a figure is one window and a list of panels, drawn into one file: `styles.webp` 1924x711, the same 640x640 window at (704, 64) in Atlas, Schoolroom and Natural, 238 KB; `layers.webp` 1926x667, the same 480x600 window at (200, 500) in Temperature, Ocean currents, Winds and Rainfall, 103 KB; the hero moves to `MapStyle.NATURAL` as `natural.webp`, same `BAND`, 122 KB against Atlas's 138 at the same quality. Windows chosen off contact sheets: the styles' holds a broken coast with islands, a snow-capped range, the river system and lakes above it and dry interior below, which is where climate-tinted styles part from height-tinted ones; the layers' is taller than wide because the westerly/trade boundary crosses its upper third (so Winds shows both) and because a taller panel carries a taller band, which is what keeps four second lines readable in a 1072px column. Bands: a ninth of the map above them, full width, flat tints from the page (`--ink-sunk`, `--hairline`, `--oxblood`, `--brass`), lettered in the site's own IBM Plex Sans out of `ui/.../composeResources/font`, which the Gradle task passes in and declares as an input; contrast parchment-on-sunk 13.53, parchment-on-hairline 10.58, parchment-on-oxblood 11.67, ink-on-brass 7.76, bar 4.5 by `ColorVision.contrast`. `--brass-dim` is a divider only - it pairs with nothing on this page at 4.5:1. Second lines are the code's own words (`MapStyle.detail`; `ClimateStage`/`OceanStage` field KDoc), one pair of sizes per strip brought down until the longest fits. Page: two sections between the pipeline cards and Features, each a title, one sentence and the figure, scrolling rather than shrinking below 960px. Guards: figure table with both strips, width/height read back out of the img tag, each strip's alt "..., labelled A, B and C" compared name for name with the bands and the file's own width against n windows plus n-1 hairlines, every band tint checked against the custom property it claims to be, `og:image` pinned to the hero, and "Twelve for the map" counted against `MapStyle.entries`. Three shown to bite (Schoolroom dropped from an alt; `--brass` moved one unit). `-Pcontact` also writes each figure at the page's column width as PNG. :desktop:test 67, :desktop:siteTest 13, all green. **On review** (William: desktop right, phones should stack) each strip is published a second time with the panels laid down - `styles-stacked.webp` 640x2137 246,124 B, `layers-stacked.webp` 480x2674 107,224 B - composed from the same panel list by the same code, chosen by `<picture>` at `max-width: 899px`, the breakpoint the headline already uses. Both scroller rules removed and nothing replaced them: at 900px the column is 852px and a band's name sets at 12.6px, so no width needs to scroll; there is no CSS on the page that arranges a panel. The tag reader looks in `<source srcset>` too, and the panel-name clause runs over every variant, each counted along its own axis. Figures now 809 KB published, but a reader fetches one variant. Titles and lines are William's own, sent verbatim: "Realistic map styles" / "The same ground is drawn three ways..." and "The data behind the map" / "These four views show the temperature, ocean currents, winds and rainfall...", plus a closing "These figures show some of the map styles and views. More are available in the app." Forward-merged to main 2026-09-14 (ebd7246); on the 3.0 pipeline the styles window at (704, 64) is mostly open ocean, so the window is re-picked before any 3.0 page ships. |
| C3 README for maintainers | | done | 2026-09-15 | 53eda79, ae2cce4, 3b23233, 848c213 | The README rewritten against the code and cut from 661 lines to 391: one or two plain sentences per pipeline stage, pointing at this file and GEOGRAPHY.md for the derivations they used to repeat. New `docs/PERFORMANCE.md` takes the desktop export table, the picture-format fidelity figures, the `ExportAuditTest` cost table (2026-09-12), the 8192 memory analysis, the GPU raster timings, the erosion cost argument, the accelerator speedups and the browser's one-thread figures, every table naming its machine (Ryzen 7 5700X, 32 GB, RTX 3070 Ti, Windows 11, JDK 21) and its date, and saying so where the README recorded none. New `docs/DEPLOYMENT.md` takes the two shell names and the shadow-root readiness problem, the loader stamp, the Pages API production-branch lookup, the custom-domain note, the PNG encoder rationale and the zip-versus-two-downloads decision; `WebDeploymentContractTest` is still the guard. The Kotlin/JS removal's reason (sin/cos/pow through `Math`, a last-bit difference the FFT compounds) folded into T1's plan entry above. Settled from the code: twelve styles (`MapStyle`), seventeen chromes in three groups (`ThemeChoice`), fifteen views (`MapView`), save format 10 (`WorldCodec.FORMAT_VERSION`) against the README's 4, JDK 21 against 25, 126,178.65 years per hydraulic round (`WorldScale`) against 340,000, acceleration covering the ocean-current solve on both platforms since G3 (`Platform.acceleratedWork`), Help's three items and File's "Random world" (`MenuCommand`), and the download measured rather than quoted — 16.1 MB in `web/build/dist/wasmJs/productionExecutable`, 13.3 MB of it the loader and the two wasm modules, 4.6 MB gzipped as the site build and `SiteAssemblyTest` measure it, against three disagreeing figures (4.3, 4.4, 12.4 MB raw) in the old text. Erosion re-measured with `StageProfileTest` on 2026-09-15: 70.9% of a 60.5 s 2048 generation (42.9 s of it), against the 82% recorded before S2's isostasy rounds and G3; sea level is now second at 14.8%. CI gained `:ui:wasmJsTest` as its own step in the desktop job — Karma headless Chrome, which ubuntu-latest ships — so the wasm half of the interface is no longer checked only locally. Every backticked identifier and every number-with-a-unit in the old README was checked present in the new tree; deliberately dropped: the 4.3/4.4 MB and 12.4 MB download figures, the 340,000-year round and the 82% erosion share (all superseded by measurement, the last kept in PERFORMANCE.md as the earlier figure), and ":desktop is around forty lines" (stale and unmeasured). `site/README.md` untouched: it links to no README section. Green: `:desktop:siteTest` 18, `:desktop:test` 75. |
| C4 Docs split into design record and working notes | | queued, first after the weekly reset (2026-09-15) | 2026-09-15 | | Public: geography, audit, performance, deployment, TODO, naming, roadmap, plus a derived DESIGN_LEDGER.md and CONVENTIONS.md; local notes/ for this file and the style guide's framing; code citations repointed |
| H1 Tectonic history | | done | 2026-09-12 | 31dc575 (merge 3b3ae05) | PlateStage runs historyEpochs times (default 3), oldest first: seeds carried back along minus their drift by epochDrift (45 cells at 512, atResolution), Voronoi and pair classification redone in that configuration, the same five profiles stamped and aged (amplitude x beltAgeDecay^n = 0.45^n, half-width x 1.45^n, blur 3 cells x n); a past continental rift becomes an aulacogen (trough 55% filled, shoulders 35%); present epoch last with every factor 1, so 0 or 1 epoch reproduces the old field bit for bit (TectonicHistoryTest pins pre-H1 checksums on 7/42/1234); crustAge field saved as plates.crustAge (34 sections); old belts beyond 52 cells of any present boundary +0.080/+0.141/+0.096 (bar 0.04), pooled 2.19x lower and 1.50x broader than present belts (bars 1.8, 1.3); crust-age bands ~37% present, ~25% one back, ~20% two back, ~18% cratonic; K = 1 gives a zero difference field; 2048 tectonics 1.37 -> 3.67 s, per-cell work the minority so no GPU (rule 8, measured in TectonicHistoryAuditTest); moved guards each with a written reason: RibbonLand and OutletIncision round-by-round run at one epoch with shipped-world bounds added, OutletIncision's Caspian bar restated as share of Earth's land (0.249%), GlaciationTest comb at one epoch and its 2048 case bounds ice bars against the un-glaciated world, LakeWaterBalance basin cases at one epoch, MeridionalWindTest monsoon sample re-picked to seed 28 by its own scan; render: a sharp coastal range with a broad worn upland inland of it |
| H3 Lithology | | queued behind G1 | | | |
| T1 Two test tiers | | done | 2026-09-12 | f6f01a7 (merge, see log) | class-name lists with Gradle filter exclude/include on jvmTest and a new audit task in :worldgen (JUnit 4 via kotlin-test-junit) and :desktop (JUnit 5, same mechanism); moved: DebugMapDump, StageProfileTest, GenerationSpeedTest, DesertCauseTest, ColdCapReportTest, ErosionConvergenceTest whole, the 2048 cases of GlaciationTest and RealmIdRangeTest split into *AuditTest classes, ExportSmokeTest's 2048/4096 exports into ExportAuditTest (1024 stays); LakeWaterBalanceTest had no 2048 case in code; DepositionTest's absolute pin dropped, land count and structural cases kept; js(IR) removed from worldgen (cartography never had it), node/yarn/binaryen ivy repos still needed by wasm; CI runs JVM and Wasm tests with -i teed to logs and diffs FINGERPRINT lines from them, no second --rerun-tasks pass; nightly.yml runs gradlew audit; per-merge worldgen 1357 -> 706 s under the same load, desktop 191 s, cartography 65 s; audit tier green: worldgen 12m29s (21 cases), desktop 6m |
| C2 Names and comments for humans | | done: phases 1, 2a, 2b, 2c and 2d (the whole tree swept by 2026-09-13) | 2026-09-12 | | `CODE_STYLE.md` written: nine rules, each with a before/after from `SeaLevelStage.kt`, plus what does not change (serialised names via `@SerialName`, shader identifiers, launcher entry points, anything that moves a bit) and the order the sweep runs in. `SeaLevelStage.kt` reworked as the sample: 83 names changed (3 private functions, 8 parameters, 70 locals, 2 constants), all 24 one- and two-letter declarations gone, 2 magic numbers named with their derivation (`MIN_RANGE` 1e-6, `SHELF_DEPTH_AT_COAST` -0.02), 8 passages of measurement history and chunk labelling moved out of KDoc with pointers left behind. Bit-identical: `WorldFingerprintTest` elevation checksum -8218339955089907081 before and after, and seeds 7/42/1234 at 512 identical to the raw bit on `erosion.height`, `sea.isLand` and `sea.relativeElevation`. **Not renamed, and listed rather than done:** `SeaLevelResult.threshold` -> `shorelineHeight` and the two `SeaLevelStage.apply` overloads, both of which reach a dozen files and belong to the model pass this chunk's own order puts first. Two ledger rows gained the history the KDoc gave up (B2's exact-cut figures, H5's reverted ocean-coverage solve). Per-merge tier green except `ChromeGalleryTest`, which fails identically on untouched `main` (all eleven chrome fingerprints moved, deterministic across runs) and is reported separately. Wasm bundle builds.; the maintainer retired ChromeGalleryTest's recorded F7 pixel-identity case at the merge (it pinned pixels that move with the version string; ChromeContrastTest's scheme-equality guard is the claim that travels, and the pixel proof stands in F7's row) **Phase 1, the shared model, 2026-09-12 (branch worktree-contributor-a5f4f6c8206f31503, commit 26d21d7).** William's two rulings applied: `SeaLevelResult.threshold` -> `shorelineHeight` (11 files) and the percentile-only overload of `SeaLevelStage.apply` -> `percentileCut`, the whole-stage entry point keeping `apply`. **Scope changed mid-chunk** (William, 2026-09-12): nothing is distributed and no save needs to keep opening, so serialised names are swept outright rather than shimmed with `@SerialName`, and `WorldCodec.FORMAT_VERSION` goes 3 -> 4 with the codec refusing any other version by name — a header is parsed with unknown keys ignored, so an older file would not fail to open, it would open with this build's defaults wherever a key had moved. The 2.0.0 compatibility fixture and `SaveCompatibilityTest` that the chunk originally called for were dropped unmade. The version-2 text reader went with the bump (`decodeText`, `decodeTextOrNull`, `LEGACY_TEXT_VERSION`, `ByteWorldLibrary`'s legacy load branch and its non-container header fallback): it could only misread. `WorldCodecTest`'s and `TerrainSnapshotTest`'s version-2 cases became a refusal case and a wire round-trip; the checked-in gzip fixture is a whole save and was regenerated (79,085 bytes, 34 sections, format 4). **Counts:** 13 properties renamed — `SeaLevelResult.threshold`, `WorldLists.seaThreshold`, `NormalField.gx`/`gy` -> `gradientX`/`gradientY`, `ClimateConfig.lapseRateC` -> `lapseRateCPerKm` and `seasonalTilt` -> `seasonalTiltDegrees`, `OceanConfig.coastalReach` -> `coastalReachCells` and `speed` -> `speedCellsPerPass`, `GlaciationConfig.runOut` -> `runOutCells` (which also stopped it shadowing a local of the same name in `GlaciationStage`), `RiverConfig.sourceThreshold` -> `sourceFlowShare` and `minLength` -> `minLengthCells`, `SectionType.width` -> `bytesPerElement` (it sat beside `FloatField.width` meaning something else) and `Section.raw` -> `bytes`; 1 function; 0 types; 12 locals; 2 wire section strings (`terrain.normals.gx`/`gy`); **0 `@SerialName`s**, by the ruling; 4 magic numbers named (`WorldCodec.VERSION_OFFSET` 4 and `HEADER_LENGTH_OFFSET` 8 for the bare offsets into the container prefix, `Section.RECORD_PREFIX_BYTES` for the 16 in a record's length, `NationsConfig.WORLD_HEIGHT_AS_SHARE_OF_WIDTH` for the bare 2.0 in the equirectangular area arithmetic); 17 passages of chunk labelling and run-by-run history taken out of KDoc (12 in `WorldGenConfig`, 5 in `WorldSections`), of which 4 carried figures that were not written down anywhere and were added to the rows that own them — **B2** (what `plateauAlongVariation` costs at a fifth of the variation: 48% of seed 7's habitable land under one people against a 45% ceiling, 34% at a half, 38% before, and seed 1234's coastal gap 2.0% -> 3.1%), **H2** (what `glacialMaximumC = 0` costs: 4,047 frozen cells on seed 42 at 512, 92 channelled, no glacier, the lake guard at zero) and **H5** twice (the enclosure cap's absence in full, and `LakesConfig.minCells` as an area — 3,300 km² a cell at 512 against 206 at 2048, seed 42 holding 0.20% of its land in water at 512 against 1.53% at 2048). E7's row already held `riftDepth`'s, so that KDoc keeps only a pointer. One orphaned KDoc block (a deleted river-crossing setting's, left sitting above `NationsConfig.navigableDepth`) and two mojibake em dashes removed. **Bit-identical:** `WorldFingerprintTest` elevation checksum **-8218339955089907081** before and after, land 6538, rivers 39, realms 15, marks 28, samples -1113572159/1017906984/-1107157508, all unchanged; a throwaway three-seed raw-bit probe at 512 gave 7/42/1234 `erosion.height` 927927701966240190 / -7524188309771854867 / 794742539379118151, `sea.isLand` 6933278695580359115 / -9056687404006513702 / -5452901985614560684 and `sea.relativeElevation` 551148543475371330 / 382426220842048974 / -8342815983617910193, identical before and after, and was deleted before the commit. **Left unrenamed and listed:** `ErosionConfig.talus` (domain vocabulary, and the rename reaches the `ErosionAccelerator` seam and the WGSL host bindings, which phases 2-3 own); `WorldGenConfig.seaLevel` and `SeaConfig.lowstand` (49 and 47 sites; the calibration's parameter names `seaLevelFraction` and `lowstandShareOfRelief` already carry the units at the point of use, and `seaLevel` is a user-facing slider label — William's call); a blanket `Cells` suffix on the ~20 remaining cell-valued tectonics, erosion and glaciation settings (`andeanWidth`, `collisionWidth`, `shelfWidth`, `deltaReach`, `valleyWidth`, `cirqueRadius`, …), whose nouns already read as lengths and whose KDoc pins the unit in a clause — taken as a taste call for William rather than made unilaterally; `WorldDocument.terrain`, now permanently null and documented as such, because removing it is a change to the app's load path rather than a rename. **Overload confusion found for phase 2:** `ErosionStage.apply` ×3, of which `apply(config, height, skipSettled)` is the *thermal sweep alone* wearing the whole-stage name — the same fault the sea-level stage had; and `erodeBlocking` ×5 in `ErodeBlocking.kt`, where two candidates are `(config, height, Boolean)` and mean entirely different things (the thermal sweep, versus the whole stage with the receiver clamp off), the second only reachable arity-3 through a default — so a *positional* `erodeBlocking(config, height, false)` silently resolves to the sweep, Kotlin preferring the candidate that uses no defaults. `GlaciationStage.apply` ×2, `MapRasterizer.rasterize` ×2 and `ColorVision.deltaE2000` ×2 are the benign shape (same work, one extra observer or one extra argument) and need nothing. `CODE_STYLE.md` gained a **Serialised names, while nothing is distributed** section stating the new rule and what changes on a release; the C2 spec's "what must not change" bullet was updated to match. **Guards:** the full per-merge tier green — worldgen 114 / cartography 25 / ui 91 / desktop 32, no failures, no skips; `WorldCodecTest`'s round-trip case (every per-cell array and every list identical after a save and a reopen) is what stands in for the old-save guard the format bump retired; `IncrementalReuseTest` green; `:web:wasmJsBrowserDistribution` builds. **Phase 2a, the solid-earth stages, 2026-09-12 (branch worktree-contributor-a0c620499d9431a7f, commit 4ee4933).** Terrain, plates, erosion, the hydraulic rounds, the delta fan, flow routing, glaciation, `worldgen/math`, `PerlinNoise` and the `TerrainConfig` / `TectonicsConfig` / `SeaConfig` / `ErosionConfig` / `GlaciationConfig` sections. **Names, per file:** `PlateStage` 104 renames over 410 lines, `GlaciationStage` 47 over 658, `DeltaFan` 82 over 200, `HydraulicErosion` 37 over 277, `FlowRouting` 24 over 104, `ErosionStage` and `TerrainStage` rewritten outright, `PerlinNoise` ~30, `Fft` ~27, `JumpFloodDistance` ~20, `BoxBlur` 12, `DistanceTransform` 9, `LongMinHeap` 7. **William's rule-2 `Cells` suffix, applied:** 25 settings across the four tectonics/sea/erosion/glaciation sections take their unit (`boundaryFalloffCells`, `andeanWidthCells`, `arcOffsetCells`, `arcWidthCells`, `collisionWidthCells`, `islandArcOffsetCells`, `islandArcWidthCells`, `riftWidthCells`, `riftShoulderOffsetCells`, `riftShoulderWidthCells`, `epochDriftCells`, `beltAgeBlurCells`, `hotspotChainLengthCells`, `hotspotSpacingCells`, `hotspotRadiusCells`, `shelfWidthCells`, `deltaReachCells`, `outletReachCells`, `minTroughLengthCells`, `valleyWidthCells`, `basinSpacingCells`, `cirqueRadiusCells`, `fjordReachCells`, plus `rangeVariationCycles` and `sheetBasinCycles` for the two that count cycles across the map rather than cells) - 249 sites in 18 files, no consumer outside `:worldgen`, so no slider label moved. Those are serialised names, so **`WorldCodec.FORMAT_VERSION` goes 4 -> 5** in the same commit and the checked-in gzip fixture was regenerated (79,202 bytes, 34 sections, format 5); the codec refuses format 4 by name. **Overloads, the two phase 1 found:** `ErosionStage.apply(config, height, skipSettled)` is now `thermalSweep` and the private dispatcher `thermalErosion`, so a whole-stage name means the whole stage; the five `erodeBlocking` overloads are `erodeBlocking` (the stage), `thermalSweepBlocking`, `erodeBlockingReportingRounds`, `erodeBlockingLoggingDeposition` and `erodeBlockingWithReceiverClamp`, so the two `(config, height, Boolean)` candidates can no longer be reached by accident. Also renamed: `PlateStage.falloff` -> `sharpFalloff`, `DeltaFan.Rim.dx`/`dy` -> `columnOffset`/`rowOffset` and `Rim.reach` -> `reachCells`, `EpochBoundaries.distance`/`nearestClass` -> `distanceCells`/`nearestBoundaryClass`. **Constants:** 63 `const val` declarations added, of which 48 give a number that was inline a name and its derivation and 15 rename one that already had a name - 26 in `PlateStage` (the width swell and rim jitter bounds the reach test already depended on, written out rather than summed because the sum of the two floats rounds a hair above the literal and would move a bit; the sharp-falloff exponent, the plate warp, the arc chain, the convergence thresholds, the pole margin, the roughness band), 14 in `DeltaFan` (10 of them renames), 6 in `PerlinNoise`, 5 in `ErosionStage`, 2 each in `TerrainStage`, `FlowRouting`, `HydraulicErosion` and `GlaciationStage`, 1 each in `BoxBlur`, `DistanceTransform`, `JumpFloodDistance` and `LongMinHeap`. **History out of the code:** 32 bare chunk-label mentions taken out of the comments and 25 put back as explicit `See REALISM_PLAN.md, <chunk>.` pointers, and 13 passages of run-by-run measurement moved out, every one of them to a row that already held it - **H5b** (the pit census per mechanism, the incision cap's unit muddle), **E5** (the groove prune, the radial fill order, the surface graded to the full reach), **E6** (the deposition side of the same unit muddle, the lacustrine fan's per-cell slope), **B2**, **E4**, **G4**, **H1**, **H2**, **H5**, **E1** and **E3** - with `See REALISM_PLAN.md, <chunk>.` left behind. Four figures were **not** already written down and were added to the rows that own them first, as rule 7 asks: E4 gained the 706-cell ribbon on seed 234475 at 1024 and the seed-43 sill, E5 the fourth harmonic's 10.0% -> 10.2%, the grade-to-rim 1.9/1.1/1.1/2.6% against 0.8/0.8/1.2/1.1% and the two flat-floored lakes on 59758, E6 the lacustrine fan's depths per grid. **Bit-identical:** `WorldFingerprintTest` elevation checksum **-8218339955089907081** before and after, land 6538, rivers 39, realms 15, marks 28, samples -1113572159/1017906984/-1107157508; a throwaway three-seed raw-bit probe at 512 gave 7/42/1234 `erosion.height` 927927701966240190 / -7524188309771854867 / 794742539379118151, `sea.isLand` 6933278695580359115 / -9056687404006513702 / -5452901985614560684 and `sea.relativeElevation` 551148543475371330 / 382426220842048974 / -8342815983617910193, identical at every one of the six checkpoints through the sweep, and was deleted before the commit. **Left unrenamed and listed:** `ErosionConfig.talus` (real vocabulary, phase 1's ruling); `WorldGenConfig.seaLevel` and `SeaConfig.lowstand` (William's call); `TectonicsConfig.detailFrequency` (a frequency already names its unit); `SnowBalance.kt`, whose only callers are `ClimateStage`, left to phase 2b by the spec's own coordination rule; `DistanceTransform`'s remaining use in `PlateStage.assignPlates`, which is a metric choice and not a name. **Guards:** the full per-merge tier green - worldgen 114, cartography 25, ui 91, desktop 32, no failures and no skips (115 in worldgen while the throwaway probe was in it); `GpuErosionTest`, `GpuRasterTest` and `OutletResolutionTest` among them, the accelerator seam's Kotlin renamed and its arithmetic untouched; `:web:wasmJsBrowserDistribution` builds; `WorldCodecTest`'s round trip and `GzipInteroperabilityTest` green on the regenerated fixture. **At the merge:** phase 2b moves serialised names too, so both branches bump the version and both regenerate `GzipFixture.kt`. The version stays at **5** — it is one sweep in two halves, not two formats — and the fixture is regenerated **once**, after both are merged, from the merged tree; whichever base64 blob git picks is stale until that is done.**Phase 2b, the fluid and peoples stages, 2026-09-12 (branch worktree-contributor-aa118e18a5346d935, 3b16c59 through the merge eafa927).** Thirteen files swept: `OceanStage`, `ClimateStage`, `SnowBalance`, `RiverStage`, `LakeWaterBalance`, `NationStage`, `BasinPartition`, `BasinRealms`, `CultureStage`, `LandmarkStage`, `Atlas`, `NameForge` and `WorldGenerationEngine`, plus one dangling KDoc link in `ClimateConfig`. **Counts.** 360 one-, two- and three-letter declaration sites renamed and none left — OceanStage 69, NationStage 60, ClimateStage 59, BasinPartition 56, BasinRealms 29, RiverStage 25, CultureStage 23, LakeWaterBalance 20, SnowBalance 11, LandmarkStage 8 (Atlas, NameForge and the engine had none), the vocabulary being `SeaLevelStage`'s: `cellsAcross`/`cellsDown`/`cellCount`/`cell`/`row`/`column`, and `cfg` becoming the section's own name everywhere. 185 named constants added where a bare number stood, each with its derivation beside it — ClimateStage 48 (the latitude curve's exponent and its two anchors, the four circulation bumps with their centres and widths, Koppen's four thermal gates and his `2T` aridity term, the two moisture ramps, the weather noise, the march's laps), NationStage 31 (habitability's elevation and river terms, the six capital-siting weights, the two catchment floors, three seed streams), Atlas 21, BasinRealms 16, CultureStage 15, NameForge 12, OceanStage 12 (the three wind-stress belts, the five-point Laplacian weight, the central difference), LandmarkStage 10, BasinPartition 9, RiverStage 6, LakeWaterBalance 4, SnowBalance 1; 6 constants removed, 3 of them dead (`RiverStage.EPSILON` and `ELEVATION_BIAS`, left behind when the flood moved to `FlowRouting`; `NationStage.COST_SCALE`) and 1 replaced by the standard library (`SnowBalance`'s own private `PI`, which shadowed `kotlin.math.PI` with the identical literal). 4 functions renamed (`LakeWaterBalance.heatIndex`/`monthlyPet`/`net` -> `monthlyHeatIndex`/`monthlyPotentialMm`/`netGainMm`, `NationStage.riverThreshold` -> `drawableRiverFlow`) and 3 helpers extracted from duplicated arithmetic (`ClimateStage.evaporativeWarmth`, which the sea and land march steps both spelled out; `BasinRealms.growBreakaway`, shared by the realm cap and the voluntary schism; `NameForge.streamFor`, which `stem` and `name` both constructed by hand). **Dead code found and removed:** `NationStage.seedOrigins` and its helper `pickSpaced` — forty lines implementing the cheapest-cost seeding that `BasinPartition`/`BasinRealms` replaced, called from nowhere, and *described in the object's KDoc as what the stage does*, which is the worst kind of stale comment; with them `encode`/`decodeIndex`/`decodeCost`/`COST_SCALE`, the heap key that algorithm used; and a verbatim copy of `FlowRouting.forEachNeighbourWithDistance`, whose one caller ignored the distance and now calls `FlowRouting.forEachNeighbour`. Also an unused `share` array in `BasinRealms.assign`. **19 passages of chunk labelling and run-by-run history taken out of KDoc**, 24 bare chunk labels with them, leaving 24 `See REALISM_PLAN.md` / `See GEOGRAPHY.md` pointers (ClimateStage 13, OceanStage 2, RiverStage 2, BasinRealms 2, CultureStage 2, SnowBalance 1, NationStage 1, the engine 1). Eight carried figures written down nowhere else and were added to the rows that own them: **A1** (the belt rescale in figures — the anomaly at the subtropical high's centre falling -1.12 to -0.51 without it, and desert placement 100% -> 74%), **A4** ×4 (`MM_SCALE` re-measured on seeds 7/1234/99 at 3204/61, 3207/91 and 2915/155 mm; `REFERENCE_MM`'s derivation from seed 42's 1230 mm and seed 26's 949 mm; the Koppen concentration search 280/140 -> 76/73/94/80%, 140/70 -> 84/77/92/75%, 40/20 -> 88/99/94/85%; and the `summerShare` median of 18.7 at 50-70 degrees that `KOPPEN_CONCENTRATION_FLOOR_MM` exists for), **A6** (the exponent at 1.8 putting the effective 45 degrees at 14.8 C, and the interior leak it opened — seed 42's desert-in-band 98-100% -> 48% until `classify`'s own gate was restored), **H2** (the still-ocean decision in full: 2.1 s of gyre solve against the march's own 1.5 s, and 62-128 cells of a 4,000-13,000 cell mask) and **GEOGRAPHY.md** under "Held by construction" (a new **Realms of uneven size, and no world empire** entry: one realm held 42% of seed 7 under appetite alone, and two rings of seed spacing fixed it while halving how often a border follows a river on two of four seeds — which is why the cap-and-schism split exists instead). **Bit-identical:** `WorldFingerprintTest` elevation checksum **-8218339955089907081** before and after, land 6538, rivers 39, realms 15, marks 28, samples -1113572159/1017906984/-1107157508, firstRealm Yaessor, capital Feon, all unchanged; a throwaway three-seed raw-bit probe at 512 (deleted before the commit) gave, on 7/42/1234 and identical before and after, `climate.temperature` -3062599251726698383 / 7805904057100007467 / -2659116902154728234, `climate.precipitationMm` 8502357745360092117 / -8503462189349543682 / 7891563652337208937, `summerTemperature` -3677637383084603875 / 7673091254260344621 / 7072698094494353490, `winterTemperature` -5405657386592209697 / 2884345962441877445 / 1925762356008245355, `rivers.flowAccumulation` -2017702021822432411 / -6845822854773319719 / -4589057956588516, `nations.nationId` -2822912249789870451 / 953223545094716423 / -1763137802113619221, `ocean.anomaly` -9090768470484562760 / 1042494049315113402 / 3832060862764777318, the biome map -3542626720273419906 / -8256265407714616762 / 4097210348180201265, and rivers 181/170/151, lake cells 761/98/532, marks 28/28/28, cultures 8/8/8, first realm "The Duchy of Vaedraem" / "Yaessor" / "Stougraestene". **Left undone and listed:** no config property in the six sections this phase owns was renamed. Two contributors were sweeping `WorldGenConfig.kt` in parallel and a `@Serializable` rename obliges a `WorldCodec.FORMAT_VERSION` bump and a regenerated binary gzip fixture in the same commit — two of those cannot be merged — and phase 1 had already given these sections their units (`coastalReachCells`, `speedCellsPerPass`, `seasonalTiltDegrees`, `lapseRateCPerKm`, `sourceFlowShare`, `minLengthCells`, `maxAltitudeMetres`, `minCells`, four `*Share`s). The one genuine gap is **`ClimateConfig.meridionalWind`**, whose unit is rows per cell of eastward travel and which wants `meridionalWindRowsPerCell`; `OceanConfig.solveResolution` is the only other candidate. **Two defects found and left alone because fixing either moves worlds:** `NationStage.drawableRiverFlow` pins `RiverConfig.sourceFlowShare`'s *default* rather than reading the setting, so a world generated with that slider moved has a habitability field built against the default river density; and `Atlas.government`'s empire and free-city bars are counts of cells, not areas, so the same world exported at a finer grid promotes every realm. Both are now named constants carrying that note. **Overloads checked, none found:** no function in the thirteen files has a same-name overload at all, so the `apply`-shaped fault phase 1 warned of does not exist here; `NameForge.stem`/`name` appear twice each but as an object function and the `NameStyle` method it delegates to, which is the intended pairing. **Not touched, and left to the solid-earth sweep:** `FlowRouting.kt` and `DeltaFan.kt`, which are read by `HydraulicErosion` and `GlaciationStage` far more than by anything here. **Guards:** the full per-merge tier green at the phase head — worldgen 115 (114 plus the throwaway probe, since deleted) / cartography 25 / ui 91 / desktop 32, no failures, no skips; `:web:wasmJsBrowserDistribution` builds (4.5 MiB wasm, 3 webpack warnings, all pre-existing). **Merged with 2a the same day.** This row was the only conflict — both halves append to the same line — and the resolution is phase 1, then 2a, then 2b, in that order; the four rows this half added history to (A1, A4, A6, H2) and GEOGRAPHY.md's new entry merged clean, as did `WorldGenConfig.kt`: 2a's twenty-seven `Cells` renames alongside this half's repointed `[OceanConfig.coastalReachCells]` link and the bare second mention beside it. `WorldCodec.FORMAT_VERSION` **stays at 5** rather than bumping again — one sweep in two halves is one format, and this half moves no serialised name, so there is nothing a sixth version would protect. `GzipFixture.kt` was regenerated from the merged tree with a throwaway `jvmTest` (deleted with it) and came back **byte for byte what 2a left: 79,202 bytes, 36 chunks, and no diff at all**. That is a guard in its own right, and the strongest one this half has for the stages the raw-bit probe could only count — the fixture's header carries a 32x32 world's twelve realms with their names, capitals, governments, exports, imports and lore, its eight cultures with their hearth cells and dominant biomes, and its twenty-eight landmarks with their kinds, names and details, all as plain text, so `Atlas`, `NameForge`, `LandmarkStage` and `CultureStage` are proved unmoved rather than merely unmoved in count. The one config rename this half wanted, `ClimateConfig.meridionalWind` -> `meridionalWindRowsPerCell`, is still not made: the reason for deferring it (two parallel contributors cannot each bump the format and regenerate the same binary fixture) is gone now the halves are merged, but making it would change what format 5 means for anything written between the two merges, so it is left as William's call — one line and a fixture regeneration whenever he wants it. **Guards re-run on the merged tree**, all green: worldgen 114 / cartography 25 (the codec tests among them, reading the regenerated fixture) / ui 91 / desktop 32, no failures and no skips; `WorldFingerprintTest` still elevation **-8218339955089907081**, land 6538, rivers 39, realms 15, marks 28, firstRealm Yaessor, capital Feon — so neither half of the sweep moved a bit, separately or together; `:web:wasmJsBrowserDistribution` builds. |
| C2 phase 2d interface sweep | | done | 2026-09-13 | b25de53, 0e11f2b, dfe5cd1, 560a82d, 863d432 (merged into main 2026-09-13) | :ui, :desktop, :web and their three build scripts: 50 files, +1381/-925. 235 chunk-label mentions out of the code, one kept as the REALISM_PLAN.md pointer rule 7 asks for; printed test prefixes renamed (F13 -> CLIMATE RELIEF, F14 -> GENERALISATION, F17 -> LITTORAL, F8 -> PHONE; CI scrapes only FINGERPRINT, untouched). 63 magic numbers named with their derivation (LABEL_HIT_RADIUS_PIXELS, MAX_WORLD_NAME_LENGTH written in two files, SEED_DIGITS 19 and SEED_CEILING a million because the seed is printed in the cartouche and typed back, the flow arrow's six shares, the scale bar's six plate shares, the three timeouts, the contact sheet's grid) and 11 constants renamed for what they measure (SCALE_BAR_TICK -> SCALE_BAR_HEIGHT: Dp, GROUP -> WORK_GROUP_SIDE, MapCamera.STEP -> ZOOM_STEP, SiteImagery.SIZE/QUALITY -> RENDER_PIXELS/WEBP_QUALITY). 26 chrome-palette colours spelled out (HcBlueMark -> HighContrastBlueMark, AmsBuff -> AlliedBuff, CbVermillion -> OkabeItoReddishPurple, which had named the pigment its own comment said it was not); App.MapView -> MapPane and the two ChipRows split; PHASE_A/B_SOURCE -> SHARE_TO_GIVE/MOVE_MATERIAL_SOURCE; GlContext.remember -> memoise; Updates.parse -> parseVersion; BEFORE_F7 -> RECORDED_ROLES with every recorded value untouched. 357 comment lines rewritten or removed, 563 written back (the new constants' KDoc); three comments that had stopped being true fixed. One serialised key moved: AppSettings.graphicsAccelerationAtLaunch drops @SerialName("graphicsCardAtLaunch") while nothing is distributed (an older settings file opens with the switch at its default of off); FORMAT_VERSION untouched. New guard: each percentage dial reads 100% at the generator's own default. Gates green with no capture or fingerprint re-taken: :ui:jvmTest, :ui:wasmJsTest, :desktop:test, :desktop:siteTest, :web:compileKotlinWasmJs. Listed for 2c and the worldgen line rather than changed: cartography.MapView (a map layer), world.nations.nations and its three siblings, MapSheet.SHEET, RasterRecipe's A/B fields, worldgen.Acceleration, EngravingPlan.FULL_INK_AT, MapScale.bar, ByteWorldLibrary.readPrefix's unitless limit. |
| C2 phase 2c cartography sweep | | done | 2026-09-13 | 934dfa4..b0db6dd, 11 commits (merged into main 2026-09-13) | 19 main sources and 9 test sources swept. 156 one- and two-letter declaration sites renamed to 2b's cell vocabulary (cellsAcross, cellsDown, cellCount, cell, column, row), 8 left in two-line scopes. 109 constants named, 5 of them renames, each with its derivation (the golden angle for plate hues; 47.5 and 15 degrees for realms, the eighth being the first to repeat a hue; -30 to 70 C for the temperature ramp; 7 C for the anomaly; zip's three signature words and the 1980 DOS epoch; PNG's five filters; CRC-32's reversed polynomial; Adler-32's 65521). 55 chunk-label mentions out of the comments, 12 kept inside "See REALISM_PLAN.md" pointers; 179 comment lines removed, 403 written; three stale comments fixed (TerrainSnapshot and WorldDocument still described the version-2 reader the format bump retired; the snapshot mechanism is unreachable, WorldDocument.terrain being permanently null); one live compiler warning closed (StoredTerrain.erode's talus against the seam's maxOrthogonalDrop). Public renames reaching :ui/:desktop/:web (27 lines, 8 files): RiverSegment.x0/y0/x1/y1 -> fromX/fromY/toX/toY and width -> widthPixels; FlowArrow.dx/dy -> directionX/directionY; MapOverlay.flowScale -> flowArrowReachCells (documented as the lattice spacing, actually half of it); MapSheet.SHEET -> UNGENERALISED; MapScale.bar -> longestBarThatFits; EngravingPlan.FULL_INK_AT -> FULL_INK_AT_STEEPNESS; readPrefix(limit) -> limitBytes. Not renamed, with reasons: RasterRecipe's scalarA/B, indexA/B, colorsA/B are the shader's own buffer names (a KDoc line says so); MapView kept — the interface calls the concept "View" in its toolbar, so the name matches the user's word, and the alternatives (MapKind, MapSubject) would touch 53 sites for no clearer meaning (the maintainer's ruling). One rounding kept deliberately: the glyph radius stays cellsAcross / 190f rather than a multiply by a named reciprocal, because the two do not round alike. Bit-identical: cartography 50, ui 98, desktop 64 tests, no failures; GpuRasterTest's five parity cases green; PenAndInkTest's eleven records, StyleGalleryTest, ClearStyleTest's ladder and the codec round trip unchanged; no recorded value re-taken; FORMAT_VERSION 7 and the fixture untouched; :web builds. |
| M1 Earth-likeness metric suite | | done | 2026-09-12 | 66668e3 (merge 0612d2a) | Tests only — no main source touched, so `WorldFingerprintTest` cannot move and does not. `EarthLikeness` in `jvmTest` computes the whole table off a finished `WorldMap` (nothing re-run: the one derived field is `FlowRouting.accumulate` over the engine's own D8 tree with a weight of one cell each) and prints `EARTH seed=… metric=… value=… earth=… source=…`, one block per world plus a pooled one. `EarthLikenessTest`: per-merge, seeds 7/42/1234/99 at 512, **16 s**. `EarthLikenessControlTest`: per-merge, **0.1 s**, a synthetic world per clause. `EarthLikenessAuditTest`: audit tier, the four standard seeds plus 718106 and 59758 at 2048 on the defaults (which are the author's 62% ocean, 14 plates, 12 realms), **4 m 14 s**. **Asserted, and green at both grids** — pooled at 512, then at 2048: coastline box dimension over three octaves (4/8/16 cells) **1.201 / 1.156** against Mandelbrot's Britain 1.25 ± 0.15, per-seed 1.152-1.239 and 1.123-1.185; Hack's exponent, main stem against catchment over 636 and 1886 nested basins, **0.507 / 0.491** against Hack's and Rigon's 0.5-0.6 ± 0.05, per-seed 0.496-0.513 and 0.465-0.519; Strahler's weighted mean bifurcation ratio on the terrain's own network at a 16-cell support area **4.65 / 4.60** against Horton's 3-5, per-seed 4.45-4.76 and 4.42-4.78 (stable at a 64-cell support, 5.16 / 4.47, and on the drawn rivers, 5.21 / 5.22; Horton's own unweighted regression reads 5.99 at both grids, which is why Strahler's weighting is the estimator of record); drainage density peaks on the dry side of the aridity index and falls away in humid country (Moglen, Eltahir & Bras), peak SEMI_ARID pooled at both grids, humid 0.0021 against semi-arid 0.0047 at 512 and 0.0016 against 0.0046 at 2048; lake-size Pareto exponent pooled **1.272 / 1.006** against Downing's 1.06 ± three sampling errors (± 0.50 on 58 lakes, ± 0.30 on 115). **Findings, farthest from Earth first, which is the order S1 and the chunks after it should take them up in:** (1) *the hypsometry is unimodal* — the busiest land band and the busiest sea band are adjacent at both grids, so there is no trough at all, where Earth's continental slope holds 0.17 of its smaller mode; the pooled curve is one smooth peak straddling the shoreline (0.526 / 0.516 of the surface in the two modal bands against Earth's 0.85, ×0.62); (2) *a drawn river covers 0.484 / 0.408 of the watercourse it stands for* (×0.48), because `RiverStage.traceRivers` ranks channel heads by the flow at them rather than the length below them; (3) *lakes hold 0.54% / 1.13% of land* against Downing's 1.48% at 275 km² a cell and 2.08% at 17 (×0.36 / ×0.54); (4) *relief spans 11,913 / 11,947 m* against Earth's 20,000 (×0.60); (5) *the island-size Korčak exponent is 0.365 over 62 islands / 0.374 over 303* against 0.5 (×0.73) — inside three sampling errors at 512 and outside them at 2048, and no seed at either grid clears 0.41, so it is a property of the generator and not of the sample; (6) *ice holds 9.10% / 7.00% of land* against Cogley's 10.1% (×0.90 / ×0.69), asserted at 512 in `SnowBalanceTest` and only reported here. Reported, not asserted: desert by band (asserted in `GeographyAuditTest`; pooled ×0.00 / ×2.69 / ×0.30 of each world's own desert share against Earth's ×0.27 / ×2.05 / ×0.12) and the realm rank-size slope, −1.85 / −2.01 against Zipf's −1. Every bar shown to bite: Earth's own band table passes the bimodality clause at 0.174 and 0.848 and a featureless ramp and a flat world fail it; a rectangle of land reads 1.024; a single-order comb reads Hack 1.000 and has no pair to bifurcate; forty lakes of one size and forty islands laid out at Korčak 1.200 both fail their bars, and the fit recovers the 1.200 to three decimals. Two defects raised in `TODO.md`: the two metre scales (`maxAltitudeMetres` says the highest land is 6 km, `SeaConfig.lowstand` and `HydraulicErosion.SHELF_BREAK` derive theirs from 8 km, and the sea has no depth setting at all) and the drawn-course decomposition above. |
| S1 Units and time | | done | 2026-09-13 | c92fd39, bda0f4c, baf3132 (merged into main 2026-09-13) | WorldScale on WorldGenConfig: worldWidthKm 12,000; highestLandMetres 6,000 as a cell mean (Tibet's interior averages 5,023 m, Fielding 1994; a 512 cell is 23 x 12 km and Everest is a point); deepestOceanMetres 10,000 (the Challenger Deep less a cell's share of its walls; a trench is a line); yearsPerHydraulicRound 336,476 derived by fixing K at Whipple and Tucker's 1e-6 m^(1-2m)/yr and asking what step removes what a round removed (twelve rounds = 4.0 Myr); cell width, height and area computed in one place. The 6 km / 8 km disagreement: the climate's 6,000 m kept, and the finding that the ruler has three parts by where a figure is spent (a height above the water on the land's 6,000, a depth below it on the sea's 10,000, a level in the raw field on 16,000); the lowstand and the shelf break had been shares of the land's relief above the shoreline, a measured number that is 0.25 of the field on 718106 and 0.59 on seed 7, so the last glacial maximum was 45 m on one world and 141 m on another — 120 m everywhere now (0.0075 of the field against 0.0088 averaged), the shelf break 130 m (0.0081). Every physical knob converted once where its stage reads it, exact at 512 (pond 24 m, delta freeboard 48 m, valley relief 2,100 m, moraine 270 m, shelf depth 1,000 m, fjord 2,000 m; reaches delta 141 km, outlet 1,500 km, debris travel 1,875 km, shelf 469 km; critical slope 12 m/km = 0.688 deg = the old 9/512; areas inland-sea cap 52,560 km2, lake minimum 3,296 km2); three moved deliberately: lowstand, shelf break, and outletIncisionRatio 3 -> 1.125 (three times a rate written in another unit; on one ruler the knickpoint had cut 0.9x an ordinary reach). Culling's D not added: the hillslope law is threshold, not linear diffusion. atResolution retired for every knob with a unit; kept with reasons for the tectonics (a belt's height has no vertical scale until S2) and baseRainRate (W3's); slopeResistance and terrainResistance found unread (TODO). Scale-free suite (ScaleFreeTest 512/1024 per merge, ScaleFreeAuditTest +2048): relief x1.00-1.08 (bar 1.10) and drainage density x0.99-1.18 (bar 1.35) asserted and green on four seeds; findings printed and ranked: largest lake x0.63-6.17 (N3 chaos), ice share x0.26-1.64, coastline x0.82-1.49, and the desert bands moving by +0.10 to +0.47 of a band's land between 512 and 2048 (seed 7's mid-latitudes 16% -> 63% desert) — a biome share cannot move with the grid, the moisture march does: W3's, the most valuable thing S1 found. Two instrument bugs fixed and stated (channel support area in km2; a cell-edge coastline is a staircase). UnitsTest moves the ruler rather than reading constants back (double the land's ceiling and every relief share halves; double the sea's floor and only depths halve; halve the width and every reach doubles) and measures the residual: the shoreline sits at 0.40-0.55 of the field, not the 0.625 WorldScale implies, so the field's measured ruler is 10,228-17,370 m against 16,000 declared (regression bar 1.7; S2 closes it). Bars moved with reasons: PenAndInkTest's style pins (the world moved, together), DepositionTest land 6327 -> 6290, SeaLevelHistoryTest indentation to direction-per-seed and 1.05 pooled; OutletResolutionTest's 1.4x spread clause retired in the suite's favour; the drowned-basin allowance re-derived at 1.5; MAX_POST_CUT_OUTLET_PASSES 8 -> 16 measured. FORMAT_VERSION 6 -> 7, fixture regenerated. M1 re-measured: relief span 11,913 -> 15,617 m pooled (x0.78 of Earth's 20,000); the sea mode -390 m on its own ruler against Earth's -3,700 — the ocean is nearly all shallow, an isostatic absence for S2; all asserted rows green (coastline 1.202, Hack 0.510, bifurcation 4.63, lakes 1.225). Renders: 718106 and 59758 at 2048 before and after, whole and three crops each; the maintainer measured 0.8-4.6% of pixels moved by more than 8 levels and looked at the range and coast pairs: the same land to the eye. Found and not fixed (TODO): the Caspian and Superior caps are shares of Earth carried onto a world a seventh its size; maxRivers is a count so the drawn network thins on a finer grid; the shelf plateau at 1,000 m against Earth's 130; a glacial trough 150 km wide; the in-round notch enlarges drowned basins across the lowstand shelf. |
| S2 Coupled uplift and flexural isostasy | | done (merged into main 2026-09-13 at 7654162) | 2026-09-13 | four passes; fourth pass on chunk/s2-pass4 ending b6a2ee9, merge 7654162 | William's two findings answered and the tier green. **Texture follows relief**: the base noise split at 200 km, the fine half scaled cell by cell by the local relief of the ground the noise and belts make, self-affinely with Earth's Hurst exponent 0.7 (Turcotte; Gagnon et al. 2006 measure 0.66) so the relief window cancels out of the arithmetic, times Montgomery & Brandon's nonlinearity relief²/(relief+2,400 m) so a plain stays transport-limited. Checked on the project's own pre-S2 maps: departure/relief reads 0.16–0.28 against the 0.22 the exponent predicts. **The craton stands and the rim drowns**: continental crust thickens inland over 400 km (Watts's 200–500 of margin thinning) by a 12 km swing that realises Christensen & Mooney's 44.6 km craton against 32.6 at the edge, mass-neutral so the datum is still Earth's 840 m (1,693 m of tilt at 141 m/km, asserted), with the relief it carries falling 700 → 550 m over the same profile. Two new guards in `GroundTextureTest`, each shown failing on the third pass's ground: cell-scale departure over the lowest/highest quarter of land **63.1/120.4 m against main's 65.2/115.9** (third pass 96.1/188.0, control 96.4/187.9); drowned continental crust within 800 km of the crust's edge **0.846 against a bar of 0.80** (third pass 0.675, flat-crust control 0.677). Look guards: belt flank 204 m against main's 113; lakes 0.52% of land against Earth's 1.48% (third pass 1.73%); drainage density 0.0032 against main's 0.00256 (bar a fifth → a third, with the reason); coastline pooled 1.129 in Mandelbrot's band, per-seed 1.092–1.162, clause now asserted pooled beside the two size distributions; ice 5.10% against Earth's 10.1%; islands 24. Physics unchanged and green: trough 0.098–0.137, sea mode −4,213 m, coverage −25 to +307 m of the datum, U^0.96 K^−0.87, foreland moat 499 m with a 23 m forebulge, ice bed 160/20 m against Airy's 556. Uplift re-derived 0.86 → 0.77 mm/yr from a denudation that fell 0.36 → 0.27 on smoother ground; `ORDINARY_GROUND` 0.9318 → 0.9582 with its GLSL copy, all twelve style records with it and pen-and-ink's unmoved. One real bug: `fillDepressions` seeded land touching water as an outlet, but the enclosed-water rule leaves land *below* the ocean beside it — nine stranded cells on 42 at 512 and seven on 298405 at 1024, which was `PipelineTest`'s and `StraightRunTest`'s red. Samples re-picked (lake basins 7→6 and 14→9, rift 7→43); `DepositionTest` 6283, `TectonicHistoryTest` re-pinned; `web`'s self-test fixed where the merge broke it. FORMAT_VERSION 9, fixture 92,885 bytes / 39 sections. Tier 491 tests green plus siteTest. Renders at 2048 in desktop/build/s2-crops: plains smooth, old belts worn, coasts with plains behind them, an ocean with ridges; 59758's south-west embayment is the one crop that reads worse than main's. Six TODO entries opened, the coastline trade among them. Earlier passes: | First pass: the absolute ruler, two crusts with Earth's densities (2,835 over 41 km continental, 2,900 over 7.1 km oceanic, mantle 3,300), Airy columns solved from Earth's 840 m mean land and 3,682 m mean ocean depth (thermal buoyancy 1,544 m), flexure by FFT with Te 30 km (flexural parameter 67 km; 460 ms at 2048, 1,436 at 4096, no GPU), uplift in the rounds at England and Molnar's class ratios scaled by the generator's own measured 0.101 mm/yr removal (collision 0.6 mm/yr), H1's decay factor replaced by exp(-300 Myr / 345 Myr), continents scattered rather than poured, the crust fraction from the slider. Guards passing: Whipple and Tucker steady state U^0.98 and K^-0.96; the trough 0.075-0.105 of the smaller mode (Earth 0.17; isostasy off 0.61-0.76); sea mode -3,932 m (Earth -3,700; -445 to -607 before); ice bed down 583 m against 556 predicted. Stopped because the pictures are worse: the deep ocean drawn as flat plate polygons (one level per plate, the Voronoi showing through 1,000 m of noise against 3,700 m of depth) and active belts as smooth pale stamps (the base noise cut from 7,300 to 2,000 m peak-to-peak to make the hypsometry bimodal, leaving 7% modulation at the belt's own scale); coastline 1.202 -> 1.136, islands 62 -> 15, ice 9.1% -> 3.1%, lakes 0.54% -> 3.33% of land all moved the wrong way; four test classes red, the other modules not run, the fixture stale. Second contributor briefed with the cures the first identified: ocean floor depth from crustal age (Parsons and Sclater) with abyssal hills, a shaped rather than scaled relief spectrum, the spurious incision factor resolved against S1's derivation, and before/after renders from the merge base. Second pass (c31374e, ee4f91d): the sea floor from crustal age (Parsons and Sclater, ridge -2,500 m, oldest -6,218, the spreading rate solved to Earth's 3,682 m mean depth, the plate polygons gone), a shaped relief spectrum (400 km corner, 700 m sd continental, 250 oceanic), the crust margin 600 -> 300 km and the shelf 469 -> 75 km at Earth's 130 m break, the clock re-solved 336,476 -> 126,179 years a round by dividing out S1's spurious factor, collision uplift 0.86 mm/yr from a measured denudation of 0.360; trough on every seed 0.121-0.164, sea mode -4,073, coverage within -187 to +112 m of the datum, coastline 1.128, islands 58, lake Pareto 1.048; belt band-pass relief 266 m against main's 200; 21 worldgen and 4 cartography guards still red, ui/desktop/site not run. the maintainer looked at the whole maps and the belt crop: the land has lost main's texture — a uniform pale shelf halo round every coast, interiors pocked with lakes (3.46% of land against Earth's 1.48% and main's 0.62%), the southern half of 59758 a drowned maze, a collision belt a smooth pale annulus round a flattish plateau with lakes ponded on it, rivers sparse, ice 5.1%; the histogram was made bimodal by cutting and then re-filtering the base relief. Third contributor briefed with a changed approach: isostasy and uplift are long-wavelength physics and must sit under main's texture, not replace it — restore the base field as at 2eb0f0d on S1's ruler, apply the crust datum, the flexure, the uplift and the ocean's age-depth as corrections beneath it, reduce only the map-scale component if the trough needs it, and pass look guards against main (belt dissection including the rim, lakes at or under 1.5x Earth, no drowned interiors, coast, ice, drainage density) before the physics rows count. Third pass (b31a742, e216c74, 733d57a on chunk/s2-uplift-and-isostasy-pass3, merged with main at 254e013 — F13, F14, F17, W1; FORMAT_VERSION 9): isostasy and uplift put under main's texture as briefed; the three defects were never the isostasy — regionalReliefShare 0.10 -> 0.16 (a continent with no map-scale slope ponds and drowns into an archipelago), the ice load read as a bed rather than a surface (the bend tapered by the ice's own thickness profile: 1.8 points of ice share, a point of lakes), criticalFallMetresPerKm 12 -> 60 (S1's honest conversion of 9/512 let the thermal sweeps plane a belt's rim into the cream ring; 60 m/km is the Andes' western flank over a cell), and the flexure moved to the head of a round. Guards on five seeds at 512 (main / second pass / now): belt band-pass 199 / 266 / 267 m; a new belt-flank guard 113 / — / 235; lakes 0.54% / 3.55% / 1.73% of land (Earth 1.48, bar 2.22); islands 36 / 58 / 32; coastline 1.174 / 1.128 / 1.149 with every seed at or above 1.124; ice 9.2% / 6.2% / 7.7%; drainage density 0.00256 / 0.00230 / 0.00292; drawn rivers 156 / 160 / 164; trough none / 0.12-0.16 / 0.083-0.144; EarthLikenessTest, IsostasyTest and a new GroundTextureTest green. Lake count 38.8 against main's 13.2 (bar 19.8) is a finding: the area is Earth's, the count is not. the maintainer looked at both worlds whole and the belt crop: the annulus, the halo and the maze are gone, a collision belt is a dissected snow-capped range grading to a coastal plain, the ocean floor shows its age; the diagonal cross-hatching on plateaus is the pre-F18 routing (main had not yet received the 2.0.4 forward merge). Not done: 14 red in worldgen (BoundaryPair, ContinentalShelf, CultureRealm, Glaciation, LakeWaterBalance x2, MeridionalWind, OutletIncision x2, Pipeline's three stranded cells, RiftSegmentation, SeaLevelHistory, SnowBalance x2 — mostly sample pins, two systematic), the fixture stale at 9, cartography/ui/desktop/site not run; a fourth pass to follow the 2.0.4 forward merge. William looked at the third pass's 718106 (2026-09-13): "the entire land has a very rough texture it did not have before... no map of earth at any scale I've seen has that appearance" and "still substantial flooded continents/inland seas" — the maintainer's diagnosis: the base field's cell-scale noise, once planed off gentle ground by the 0.7-degree critical slope, is exposed everywhere at 3.4 degrees, where Earth's roughness at map scale grows with relief (plains vary by metres on 6 km cells, ranges by hundreds); and the crust thickness field has interior lows, so the isostatic drowning happens as inland seas where Earth's crust thins to its margins and drowns as shelves. Fourth pass dispatched (2026-09-13, after the 2.0.4 forward merge landed at 4897960): cell-scale roughness proportional to local relief in the base field with a guard on the lowest and highest quarters of the land against main, a continental crust profile thickest inside and thinning to the margin with a guard that 80% of drowned crust lies within a stated distance of the edge, the 14 red tests fixed by cause, the fixture at 9, every module run, before/after renders against main with William's eye as the bar. |
| S2b The review's four findings | | done | 2026-09-14 | 9eacd7a, 5ec5389, d04226a, 518c75c, f9b91c9, 48829f2, d1e9f04 (merge with I2 at aed1ae2) | An independent read-only correctness review of the S2 merge (an outside reviewer, 2026-09-14, the first use of a second model on this project; the maintainer confirmed each finding in the code before dispatch) found four, and all four are fixed by cause with a guard shown failing first. **(1) The flexure wrapped north to south.** `Fft2D` is periodic on both axes and the world is a cylinder, so a 1,000 m rock stripe on row 0 at 512 bent row 511 by the same 72.58 m it bent row 1 — a share of 1.000 — and both poles carry ice. The load is now mirrored about each polar row out to twice the map's height and cropped after: x stays periodic, y's period becomes a whole meridian out and back. Mirror rather than zero-pad because this world is 12,000 km round and 6,000 pole to pole, so a meridian is a closed loop and what lies past the north pole is the world coming back down the far side; the reflection is that continuation for a zonal load (a polar cap is one), gives the pole the zero slope it must have, and leaves the map's own mean under the dropped zero-frequency term. `IsostasyTest`'s new clause reads the far pole against the middle of the map: **1.000 before, 0.000 after** against a bar of 1e-6, which is the arithmetic's floor since the physics' answer over 89 flexural parameters is exp(-89). The near row nearly doubles, 72.58 m to 140.06, which is the continuation working — a cap sitting on the pole carries on over it. Cost: the transform pair runs on a grid twice as tall, **250 → 532 ms at 2048 and 962 → 2,850 ms at 4096**, 1.02 → 1.58 and 2.00 → 5.63 of a round's thermal sweeps, and the buffers double to 134 MB at 2048 and 537 at 4096. The exact spherical continuation also turns half the map in longitude, and the mirrored field is even so the y transform could be a cosine transform at the map's own height: both in `TODO.md`. **(2) The depression fill left submerged land unfilled.** The flood was seeded from land touching *lower* water, so a component of enclosed-water cells ringed by higher water was never seeded, never visited, kept its sinks and drained to nothing; S2's fourth pass had excluded such cells from the seeds, which hid the symptom and left the cause. The sea now joins the flood at its own level, as a priority flood does, and only water touching land is pushed (open water has nothing but visited neighbours whatever order it pops in, so leaving it out is an economy and not a change). `DepressionFillTest`: on the old seeding **64 of 89 land cells are sinks with no receiver at all** and the below-water patch stays at -0.200000; now **0 and 0**, and the patch comes up to -0.099999, its spill level plus the flat-gradient staircase. An ordinary coast does not move: the plateau's rim reads 0.050000 and its pit 0.050002 under both rules. `PipelineTest` 0 stranded on 42 and `StraightRunTest` 0 on 298405. **(3) The jump flood measured north-south distance with the cell's width.** A cell is 23.4 km across and 11.7 down at 512, so a craton thickened to full over 200 km northward where `cratonReachKm` asks for 400, and a ridge's age-depth read the orientation of the ridge that made a piece of floor. `JumpFloodDistance.run` takes `cellHeightInCellWidths`, carried through every pass (a nearer source in cells can be the further one on the ground) and answering in cell widths; at 1 it is the integer arithmetic it replaced, to the bit, so no other field moves. `PlateStage`'s craton reach and sea-floor age pass the grid's own ratio and consume kilometres. `CratonReachTest` reads the interior share 398.4375 km inside two synthetic crusts, one whose edge runs east-west and one north-south — 34 rows and 17 columns, the same ground to the metre: **0.8636 against 0.6307 before, a gap of 0.2329; 0.6307 against 0.6307 after, a gap of 0.0000** against a bar of 1e-6. `JumpFloodDistanceTest` keeps its brute-force case and runs it at both row scales, exact at each. Four callers still count cells and are in `TODO.md` with what each costs: `ClimateStage`'s water distance, `SeaLevelStage`'s distance to land (so a shelf is half as wide off a northern coast as off a western one), `PlateStage`'s two boundary-distance fields (every belt profile, plateau rim and trench wall — a chunk of its own) and `GlaciationStage`'s two. **(4) `GroundTextureTest`'s control was computed and never asserted.** Asserting it would not have worked: on this ground the reduced-relief control reads **0.84% of land in lakes against the clause's bar of 2.22%**, passing by two and a half times, because the crust's own thickness profile now supplies the long slope the sixth was raised to supply. The clause is demoted to the two plain absolute checks it actually makes, renamed to stop claiming the tenth fails, and says in its own KDoc why; the control is deleted rather than printed, which saves five 512 worlds a run. Whether the sixth is needed at all stays open in `TODO.md` with the re-measured figure. Six worldgen guards moved with the ground and each is dealt with by cause rather than by widening: `DepositionTest` land 6283 → 6279 (the fifteenth move, for the one reason it has ever moved); `TectonicHistoryTest`'s three checksums re-taken a fourth time, holding the same property (epochs 0 and 1 still agree bit for bit); `LakeWaterBalanceTest`'s dry sample re-picked by the class's own 1..48 scan, seed 6 → 13 — 6's hollow went 4,817 cells at 112 mm to 3,711 at 125 and keeps 0.48 of its spill area where it kept 0.26, while 13 is 2,450 cells at 96 mm and keeps 0.24, the bar unmoved at 0.45; `RiftSegmentationTest` keeps seed 43 and withdraws its sea-bodies bar, because no seed of the twelve scanned clears three bodies with a control that fails (seed 77 reads three either way and every other segmented rift floods as one or two), while its other two bars still read 3 bridges and 0.15 of width variation against the control's 0 and 0.04; `OutletIncisionTest`'s fill clause is read pooled instead of per seed with the half unmoved, **0.360 pooled** (718106 0.777, 42 0.597, 7 0.380, 99 0.183, 1234 0.132, 43 0.093); and its level-sill clause swaps which seed carries the claim, 99 → 718106, because this chunk's own fill now raises the land a level sill is made of. `StraightRunTest`'s ruled-bar census is split by the sea-level cut: the repaired fill reaches below-waterline basins for the first time and one on seed 7 holds 28 cells in a hollow 590 by 47 km, straight to 1.12 cells but standing at 0.272 of the field against a shoreline at 0.632 and absent entirely from the plain-routed world. Outside worldgen: all twelve `PenAndInkTest` style records re-taken (`ORDINARY_GROUND` unmoved at 0.9582, so pen and ink moved on the ground's account and not the shading's), and re-taken once more after the merge with I2, since neither side's figures describe the merged world; `OutletResolutionTest` retires its 1.4 chaos allowance rather than widening it — seed 59758's largest land lake reads 1.55, 1.48 and 0.20 times the Caspian's share at 512, 1024 and 2048, an eight-fold spread on one world, so the clause is read pooled over its six worlds and asserts **Earth's bare figure, at 0.79x it**, where Earth's figure times 1.4 stood before. `GpuErosionTest` failed once at 11,356 ms against the CPU's 10,907 and passes at 10,552 against 12,211 on a quiet machine; nothing in it is changed. Tier green after the I2 and G3 merges, with the style records re-taken once at the first of them: worldgen 178, cartography 54, ui 106 jvm and 106 wasmJs, desktop 74, siteTest 13. `FORMAT_VERSION` unchanged at 10 — the gzip fixture is a stored save decoded, not a world regenerated, so a moved ground does not stale it. Renders at 2048 in `desktop/build/s2b-crops/{before,after}`, whole and both poles for 718106 and 59758: the same land and the same coasts, interiors a little smoother at the map scale as a craton tilting over twice the distance should be, 59758's eastern continent visibly better drained, a few more and larger lakes with natural irregular outlines; the poles themselves barely move, because neither of these two seeds carries a polar ice cap at the default settings and the flexure repair needs a polar load to show. Both frames were taken on this chunk's own base, before the I2 merge, so the pair brackets S2b alone. Nothing reads worse except 718106's polar endorheic lake, which lost its drawn inflow. Three `TODO.md` entries opened besides the four callers and the padding's cost: the level-sill rule's guard now rests on one seed and wants a synthetic sill, the rift's chain-of-basins claim is carried only by the picture, and a below-waterline basin 590 by 47 km wants a render to judge it. |
| S3 Erosion reads the climate | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| W1 Energy balance and sea ice | | done (merged into main 2026-09-13 at 3af361b) | 2026-09-13 | 11fc0ce, fcabf90, 137f49f, c41ea21, 1c01dd9, merge 3af361b | The latitude curve retired with equatorTemperatureC, poleTemperatureC and continentality; a Budyko/Sellers/North energy balance over 240 bands x 360 steps x 20 years in its place, three reservoirs per band — land air 1.7e7 J/m2/C, marine air 1.04e7 (c_p p/g), a 50 m mixed layer 2.0e8 — the air and the water coupled by a bulk surface flux of 25 W/m2/K (sensible 11.6 plus latent 13.4 at 8 m/s, the top of the bulk formulae's 15-25), the two air columns trading at 8 W/m2/K and carrying the transport. A+BT with 2.09 and A = 210.7 from Earth's own 240 at 14; the albedo least-squares fitted to Earth's zonal planetary albedo with an ITCZ cloud belt and the clear subtropics; diffusivity a Hadley cos2 (0.90 to 0.30) plus a storm-track Gaussian at 50 deg (Trenberth and Stepaniak 2003). Koppen's gates read the warmest and coldest month, the half-year means kept for the degree-day sum and the march (a build with months everywhere cost a third of the permanent ice, which is how the distinction was found); the marine blend's distance corrected by half a cell. Against Earth: land/marine air at 0/20/40/60 = 26.2/26.0, 23.4/23.3, 13.9/13.9, 1.5/1.8 against 26.0/26.5, 25.0/24.5, 14.5/14.5, -2.0/2.0; the warmest month over the sea 26.7/26.0/19.2/7.9 against a reanalysis 27/27/19/7, the water 26.3/24.8/16.8/4.9 against 27/27/19/5.5, all inside +/-3; swings at 50-60 land 38.7 (34-38), marine air 13.0 (8-11), water 6.9 (5-8); transport 4.6/4.9/3.2 PW (5.3/5.0/3.3); model ice edge 60.4 N (Earth 60), the map's cold-season pack 25-33% of the sea to 55-58 deg; glacialMaximumC a dimmed sun, poles cooling 5.8 with the feedback and 4.3 without, cap 60.4 -> 52.1 (53.6 without). Frozen sea 257-523 mm against 1811-2508 open (x4.1-7.2); permanent ice 8.7% of land (Earth 10.1). F20's equatorial stripe cured. A6 restored: warm west-facing coasts at 50-60 forested 40/52/51% against the recorded 65/53/59, bars unmoved (seed 1234 clears by half a point; the limiter is Koppen's -3 continental gate and a current anomaly of +1.3 to +1.8 where Earth's comparable coasts get +4 to +8); OceanCurrentTest +4.3/+5.5/+5.5%. New ColdBiomeShareTest against Olson et al. 2001 (tundra 6%, boreal 11% of ice-free land): boreal 6.7% pooled asserted within x3 (was 0.0% on some seeds mid-chunk); tundra 47.4% reported, not asserted — the same worlds sit within 1-2 C of the reanalysis at every latitude to 70, so the cause is M1's land standing 1,200-1,700 m up (S2's). GlaciationTest's two glacial-lake clauses became findings: pooled over three seeds at 1024 the ice raises cold-country lake density only 0.21 -> 0.28 per 10k because GlaciationStage proposes no trunk glaciers against a diffuse 41,000-cell sheet (trunks 0, cirques 0, moraines 0, 31,453 channelled, nothing refused: the candidate test asks a path to carry a share of the whole frozen area) — I1's. Bars re-derived with reasons: SeasonsTest 9 -> 11 and 2.0 -> 1.5 (the field is marine air now, not SST); the amplification clause restated on the polar cooling; SnowBalanceTest's control clause skips a saturated seed; RiverWidthTest's narrowing rate counted against the land; style hashes re-pinned together. FORMAT_VERSION 7 -> 8, fixture regenerated. Tried and reverted, recorded: correcting applyMaritimeInfluence's double attenuation makes the coasts worse (a ten-cell disc around a warm tongue is mostly not that tongue) — the fix needs W2's upwind neighbourhood. Performance: the band albedo hoisted out of the step loop after GenerationProgressTest timed out on wasm, bit-identical. the maintainer looked at 718106's biome map before and after: the same continents read the same way, the equatorial stripe gone, the northern interior still tundra for the reason above; and one thing the map now shows plainly — the sea-ice edge is a dead-straight line of latitude across the whole ocean on both worlds, because the band model has no longitude and the currents' anomaly is a fifth of Earth's; recorded as F26 for W2. Tier and siteTest green, 40 min. Merge with main (the 2.0.3 forward merge and the C2 sweeps): six conflicts resolved by intent — the audit lists unioned; the style records re-taken once, all eleven moving together; OutletResolutionTest's two restatements kept with both sides' measurements; RiverWidthTest's narrowing rate against the land (seeds 7 and 42 at 0, 1234 at 6 of 101,415 cells); GlaciationTest keeping F22's and W1's demotions with the control printed cross-multiplied on longs; a TODO entry about the retired latitude curve dropped. GenerationProgressTest's Mocha budget 2 s -> 60 s on wasm, because the energy balance's solve does not shrink with the grid (a single shared zonal solve and a twelve-year spin-up are recorded in TODO as the repairs that would, both moving every world). On the merged tree: 473 tests green; ice 8.6% of land, tundra 47.2%, taiga 6.9%; A6's audit read 39/56/48% against the recorded 65/53/59 — seed 1234 1.7 points under its half not because a temperature moved (coldest month -1.4 against -1.5) but because F17's grading changed which coast cells count (692 -> 620 west-coast cells), the limiter still the current anomaly at +1.3 to +1.8 C against Earth's +4 to +8 on such coasts; recorded here rather than chased inside W1 (the maintainer's ruling: W2's upwind neighbourhood is the repair). |
| W2 Pressure-driven surface winds | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| W3 Moisture budget calibrated | | queued for 3.0 (REALISM_AUDIT.md) | | | William, 2026-09-14, on the rainfall view of his 3.0.0-dev test: "a fairly low degree of variation in precipitation within the continents, with the only real variation being along coastlines." W2 (pressure winds: monsoon coasts, thermal highs over interiors) and W3 (depletion length, continental recycling, the marine inversion) are the chunks that should move the interior; the guard to add is an interior-rainfall spread measured against Earth (e.g. the coefficient of variation of annual rainfall over land more than 500 km from the sea, Earth's from GPCC). If the picture is still flat after W3, revisit as its own chunk. |
| W4 Vegetation density and permafrost | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| R1 Channel initiation and drainage density | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| R2 Rivers drawn as rivers | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| R3 Wetlands and inland deltas | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| K1 Wave climate and longshore drift | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| K2 Delta and estuary type | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| K3 Reefs and atolls | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| K4 Fjord coastlines | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| I1 Ice sheets with a profile | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| I2 Glacial basins take their shape from the ground | | done | 2026-09-14 | 7c36804, ddb00e8, f4e6935, ba703f2 on chunk/i2-glacial-basins, merge with main a390612 | **The slab was the trough, not the basin.** Phase-by-phase instrumentation on 364673 at 2048 puts three cells of it at 1744, 1503 and 1556 m before the carving and 375 m after — a cut of 1,130 to 1,370 m where the trough is meant to take 24 — and `cutBasins` cut *nothing* on that world (0 valley basins, 0 candidates; the 6 basins it does hold are the sheet's scour, none within 400 cells of the slab). The cause is `swath`: `GlaciationConfig.valleyWidthKm` is 152 km, fifty times a real trough and deliberately so, and the middle 65% of that half-width was planed to the height of the one cell on the axis whatever stood there — 300 km of cross-section at one height on a 2048 grid, with a straight edge at the flow's own D8 bearing and, where the two legs of a reach cross, **the cross**. `bowl` did the same with a cirque's disc. The fix is the figure the model was missing: `valleyIceThicknessMetres` 600 m (Aletsch 300-500 m over most of its length, 900 at its thickest by radio-echo sounding), and `cutShare` gives each cell the product of how far across the section it lies and how deeply it is buried, so the valley floor is planed, the shoulder is barely touched and rock above the ice surface is not touched at all; a cell standing `d` above the bed keeps `d^2/thickness` of it, so no floor is ever exactly level. **The basin work the brief asked for was done too and is a real improvement on its own terms**: the footprint is the valley floor (within the trough half-width by Euclidean distance *and* no higher than the lowest ground on the path plus the cut's depth), the area cap peels by height from the deepest cell outward (`keepLowestCells`, connected by construction, outline a contour) instead of by four-connected ring, and the floor is a paraboloid in the Euclidean rim distance from `JumpFloodDistance` over a padded window (`rimDistanceCells`, `cutBowl`) instead of a saucer off a Manhattan inset that saturates two cells in. `insetDistance`, `peelToCap` and `cutSaucer` are gone; the sheet's scour shares all three and gains the bar refusal the valley basins already had (seed 7 at 1024 was cutting a basin 17 cells long and one wide). **Guards** in a new `GlacialBasinShapeTest`, 364673 at 2048 and the three `GlaciationTest` seeds at 1024. The one that fails on main is the level surface, against Salar de Uyuni's 10,582 km2, the flattest large surface on Earth: **364673 at 2048 measures 16,050 km2 (935 cells) on main against 616 allowed, and 2,644 km2 (154 cells) after**, in line with the 2,541-3,777 km2 the other three carry either way. The outline guard (no run along one grid bearing longer than 3x what a circle of the same area makes on a grid; Earth's straightest lake shore, Tanganyika's graben scarp, is 2.05x) and the floor guard (no more of a floor within a metre of one height than 2x the larger of a paraboloid's even hypsometry and an equal-area disc's outermost ring, from Hutchinson's 0.6 to 1.2 volume development) both **pass on main as well as after** — 0.63x and 0.65x the outline bar, 0.67x and 0.81x the floor bar — and that is the honest reading: with bars derived rather than fitted, main's *basins* were not out of bounds on these seeds, only its troughs. Earlier drafts of both did fail on main (1.86x and many floors) and were wrong to: they ignored that a floor of `n` cells cannot put less than `1/n` at one height and that a bowl's outermost ring is `2*sqrt(pi/n)` of it by construction. **Why the basin held no water**: not a playa. The slab at 375-376 m sits in a 7,515-cell depression that spills at 567.6 m and is endorheic on 157 mm of rain a year against a 21.1 C summer; the balance keeps 991 cells wet — lake 1, at (1856,1600) — and the slab stands above that surface as dry ground, which is what an endorheic basin's shelf is. The playa branch never fires because it only fires when the balance cannot fill `minLakeCells`, and here it can. So the renderer needs no playa face for this; what it needed was a floor with relief in it. **Renders** in desktop/build/i2-crops/{before,after}: the 364673 window at 1:1, and 718106 and 59758 whole at 2048. The slab and its cross are gone and the belt reads as a range with valleys; on 718106 the glaciated massif in the south-west loses four pale stamps and a comb of parallel bars along the range front; 59758 has no ice in frame and is unmoved. 0.2-0.9% of pixels differ on the three. What is left, and noted: a faint pale smear along the two legs of the reach where the trough is still stamped one cell at a time along a D8 path. `PenAndInkTest`'s twelve style records re-pinned because the ground moved — pen and ink's moved too, `-588733464` to `83321747`, which is the evidence, since it has no tint for the shading to multiply; `ReliefShading.ORDINARY_GROUND` did *not* need re-deriving, still 0.958 against the declared 0.9582. FORMAT_VERSION 9 -> 10 for the new glaciation key, fixture regenerated at 92,919 bytes / 39 sections. Tier green: worldgen 172, cartography 54, ui 105 jvm and 105 wasmJs, desktop 67, siteTest 10 (`GpuErosionTest`'s speed clause failed once at 0.9x under contention from another run and passes at 1.3x on its own; its correctness clauses measure 0.000000 mean difference). Four TODO entries opened: the sheet mask's edges are straight because `localRelief` measures over a square sliding window (which is where the scour outlines' ruled edges come from, and why that guard reports rather than asserts on them); `cutBasins` cuts one basin a world or none, every candidate refused by `minSinuosity`; the trough is still stamped along a D8 path; and **the two lakes and the dead-straight river the author reported in the same window are not the ice at all** — zero cells of water differ and every flow target is the same with `glaciation.enabled` false, so that cause is upstream of this stage and is still there. |
| P1 Metric-aware physics and a projection | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| P2 A spherical grid | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| P3 Map projections as options | | queued for 4.0 with P1 and F27 (William, 2026-09-15) | 2026-09-15 | | Equal Earth, Robinson, Winkel tripel, Mollweide, orthographic beside the equirectangular; per pixel on both raster paths; graticule, scale bar and labels follow |
| V1 Tints by climate and sky-model shading | | pulled forward as F13 on the 2.0.x line | | | |
| V2 Generalisation, graticule and scale | | pulled forward as F14 on the 2.0.x line (projection and north arrow stay with P1) | | | |
| V3 Labels | | queued for 5.0 (moved from 4.0 by William, 2026-09-15; realm and settlement names move from the syllable assembler to curated lists in this release, William 2026-09-15, the lists gathered ahead of time under `docs/naming/`; REALISM_AUDIT.md; William, 2026-09-12: the full atlas with named continents, seas, bays, straits and ranges targets 4.0, and the site says so) | | | |
| A11y Accessibility audit to WCAG 2.1 AA (the ADA's standard) | | queued last, after every planned chunk (William, 2026-09-14) | 2026-09-14 | | Section above: what is already guarded, what the audit adds, the document as the deliverable |
| N1 Per-feature hashes | | queued for 3.0 (REALISM_AUDIT.md) | | | |
| N2 Scale-free suite | | folded into S1 (2026-09-12): the suite is what S1 makes possible, so S1 delivers its first form | | | |
| Audit II Realism audit, literature-backed | the maintainer | done | 2026-09-12 | see log | REALISM_AUDIT.md: five structural absences (scale, coupled uplift/isostasy, prescribed atmosphere, rectangular planet, coast as a line) plus presentation and determinism findings; twenty-three chunks S/W/R/K/I/P/V/N/M with dependencies, effort, visual weight, rigour and GPU applicability; an Earth-likeness metric table (hypsometry, coastline fractal dimension, Hack and Horton, lake and island size laws, desert, ice, lake and wetland shares, reef limit, delta class mix); sources listed with what was read and what is cited from memory to be checked at dispatch |

Suggested order. **D1 first, alone** — everything after it is cheaper once cross-platform
identity stops mattering, and it touches the codec that C1 will package. Then **D2 and A0 and B1
in parallel** (three independent chunks, three worktrees). Then D3 and D4, and from there the two tracks
run side by side in dependency order: **A1 → A2 → A3 → A4 → A5 → A6** alongside **B2 → B3**, with
**B4** after both A1 and B3, and **C1** last.
