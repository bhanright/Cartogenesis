# Realism plan

*Drawn up 2026-09-11. Nine improvements to the geography and climate, implemented by subagents in
chunks that each leave `main` green and pushed, so that a session cut off by usage limits loses at
most the chunk in flight. The ledger at the bottom is the source of truth for progress; a fresh
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
3. **JVM and Wasm must agree after every pipeline change.** Run `:worldgen:jvmTest --tests
   '*WorldFingerprintTest*'` and `:worldgen:wasmJsNodeTest`, compare the six `FINGERPRINT` lines.
   Anything that iterates a `HashMap`/`HashSet` and picks "first" or "max" diverges between
   platforms — sort, or tie-break on a key. CI enforces this; do not push red.
4. **New config sections must be declared to the reuse chain.** If a stage reads a new section of
   `WorldGenConfig`, add it to that stage's guard in `WorldGenerationEngine` and to the variant
   list in `IncrementalReuseTest`, which compares reuse against fresh generation for every section.
5. **Measure, do not tune.** Report the number before and after. Never move a threshold to make a
   guard green; if a guard cannot discriminate, say so and fall back to the render.
6. **Windows file locks.** Gradle on this machine locks `build/` subdirectories between runs. The
   cure is `gradlew --stop`, then delete the module's `build` directory in a *separate* command
   from any that mentions the JDK path (the sandbox misreads the two together), then run.
7. **Small reports.** A subagent's final report is under 300 words: what changed, the before/after
   numbers, the render verdict, and anything it could not verify. The orchestrator is on a tight
   budget and reads the report, not the diff.

## Session protocol

Each session, on any model:

1. Read the **Ledger** below. Take the first unchecked chunk whose dependencies are checked.
2. Dispatch one subagent for it, with the chunk's spec pasted verbatim plus the ground rules. One
   chunk at a time, sequentially — the account is on usage credits, so parallel agents are off.
3. When the report comes back green: update the ledger entry (date, numbers, commit hash), commit
   with the chunk's name in the subject, push, wait for CI green.
4. If the session dies mid-chunk, the next session reverts any uncommitted change (`git checkout
   -- .`) and restarts that chunk. Nothing in a chunk depends on a previous session's memory.

Model per chunk is given below. Rule of thumb: Opus where the algorithm is the work; Sonnet where
the spec is precise and the test is clear; Haiku for docs, renders and tallies. The orchestrator
itself does as little as possible.

## Save compatibility — decide before chunk 1

Every chunk below changes what a seed generates. Saves record seed and settings, not the world, so
**every existing CPU save will open as a different world** after this work. GPU saves carry their
terrain and are unaffected below the climate stage but will get new climate, rivers and realms.

Options: (a) accept it — this is a pre-1.0 generator and the saves are the author's own; (b) bump
`WorldCodec.FORMAT_VERSION` and have old saves store their terrain on first open, as GPU saves
already do. (b) is a chunk of its own and protects nothing the author has said he values. **The
plan assumes (a); confirm or change before starting.**

---

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
- Determinism risk is high here (rule 3): a diagonal march touches cells in a new order.

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

## Ledger

Update the entry when the chunk's commit is on `main` and CI is green. Record the numbers the
guard reported, so the next chunk knows its baseline.

| Chunk | Model | Status | Date | Commit | Numbers |
|---|---|---|---|---|---|
| A0 GEOGRAPHY.md reconcile | Haiku | not started | | | |
| A1 Seasons | Opus | not started | | | |
| A2 Continentality | Sonnet | not started | | | |
| A3 Meridional wind / monsoon | Opus | not started | | | |
| A4 Absolute rainfall | Sonnet | not started | | | |
| A5 Cold-cap report | Haiku | not started | | | |
| B1 Continental shelves | Sonnet | not started | | | |
| B2 Crust-pair boundaries | Opus | not started | | | |
| B3 Deposition | Opus | not started | | | |
| B4 Glaciation | Opus | not started | | | |
| C1 Docs and release | Haiku | not started | | | |

Suggested order, balancing value against interruption risk: **A0, B1, A1, A2, B2, A3, B3, A4, A5,
B4, C1.** A0 and B1 are cheap and independent, so early sessions bank progress fast; A1 is the
keystone and gets a fresh session; B chunks interleave so a stalled climate chunk does not stall
everything.
