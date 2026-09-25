# Chunk 3b, stage 1: the implicit incision on the processor

**Branch:** `chunk/3b-implicit-erosion` at `5bb85b3`, from `origin/chunk/3-erosion-units` at 89f062f.
Not merged, and no pull request.

**Chunk 6.** Its six files (RiverStage, LakeWaterBalance, BasinPartition, BasinRealms, NationStage,
CultureStage) were not edited, and the solver did not need them. Three test files that chunk 6 also
changes were edited here, in recorded clauses only, and may want a hand merge: `GlaciationTest`,
`OutletIncisionTest` and `RealmSpreadTest`.

| Commit | What it does |
|---|---|
| `2e9d92f` | The WIP commit that opened the chunk. |
| `7330a1b` | The implicit update, its guards, and the restated `ErosionUnitsTest`, `ClimateFedErosionTest` and `ReceiverClampTest`. Adds `RiverProfileReportTest`. |
| `5295c56` | A fix to `7330a1b`: an edit had dropped the class `ClimateFedErosionTest`'s restated clauses share. |
| `63734cb` | The uplift re-derived from the law's denudation, and the calibrations re-examined in their KDocs. |
| `0e5be49` | The generator's clauses the new terrain moved: armed, re-recorded or recorded. |
| `867023d` | The ledger row, GEOGRAPHY, README, and TODO with the graphics-card specification. |
| `8e982c5` | The map's clauses (`:cartography`). |
| `5bb85b3` | TODO and the ledger record the combed flanks. |

## The headline

**The law now sets every cut.**
- On seed 42 at 512 every drawn-network cell has `F` over one (median 4.4).
- All 182,107 cuts at `F` over one across the twelve rounds take more than half their drop, which
  the cap forbade.
- On synthetic landscapes run to balance, the update gives the law's own profile to four places:
  - concavity 0.5000;
  - steepness `U / (K e)`, across two rocks and two uplift rates;
  - `U / (K sqrt(P))` across two rain zones.

**B-D1's per-step anisotropy is gone from the incision.**
- Valleys are cut to the same depth whichever way a river steps.
- A knickpoint retreats exactly as far north-south as east-west, and as far in two half rounds as
  in one.

**Most of chunk 3's `CAP_SETS_EVERY_CUT` failures pass again and are armed:**
- the valley notch, now 4.64 times the bare ground;
- the lowstand's estuaries;
- the ranges' texture and the belt's flank;
- the rain's dissection contrast, and seed 99's steep ground;
- the wet flank, restated on the law's rate.

**The uplift, re-derived on the law's denudation, is two and a half times what it was.** Collision
is 0.738 mm a year, up from 0.29.

**What did not come back, or got worse.**
- **The implicit update is first order, and the channel network grows denser on a finer grid.** It
  is 1.38 to 1.48 times denser from 512 to 1024, against 1.15 to 1.23 under the cap. Some of the
  steadiness across grids that chunk 3 had was the cap's doing.
- The coast's box dimension is 1.08. That is up from the cap's 1.03, but still under Mandelbrot's
  1.25 ± 0.15.
- The coast still projects 1.12 times as far east-west as north-south. Its per-step cause is gone,
  and the cause of what remains is not isolated.
- There are fewer lakes. The outlet notch, a calibration set under the cap, no longer holds seed
  99's largest lake under the Caspian's share.
- To the eye, the flanks of east-west ranges are combed by single-cell north-south gullies. The
  routing's bearing is the same on every tree measured; the law's deeper cut is what makes it show.

**Diversity.** The generated rivers' concavity is now Earth-like. Steepness follows uplift strongly,
and follows rock and rain only on some worlds. See [the generated worlds](#the-generated-worlds).

## F and the update, as built

**`F`.** From `HydraulicErosion.Rates` as it stands:
- The law's cut is `incisionCoefficient * sqrt(share) * slope * erodibility`, in the height field's
  unit.
- The slope is `drop / stepCellWidths * cellsAcross`, with the drop read on the shoreline-relative
  field.
- That field's unit is `landHalfOfField` of the height field's.
- So the cut is `F` times the drop in the height field's own unit, where

      F = (incisionCoefficient / landHalfOfField) * cellsAcross * sqrt(share) * erodibility / stepCellWidths

`F`, the share and the erodibility are dimensionless. The factor before `sqrt(share)` is
`Rates.courantCoefficient`, and its KDoc carries this derivation. Two guards check it:
- The clock guard (`ErosionUnitsTest`) reads `F` times the drop from the pass and compares it with
  `K T sqrt(A) S` computed from metres. The worst cell is 7e-8 off, over 90 cells at `F` 0.24 to
  1.3.
- The plane guard (`ImplicitIncisionTest`) predicts every height with a recursion in metres that
  uses none of the stage's coefficients. `F` runs from 0.024 to 66 and every cell lands within a
  centimetre.

**The pass (`HydraulicErosion.incise`).**
- **What it replaces.** It replaces the ordered explicit pass and nothing else. The uplift and
  flexure, the routing and the outlet notch run before it. The deposition walk after it is fed the
  recorded cuts through `incisedAt`, as before, and the relaxation runs after deposition.
- **Order and inputs.** It walks `FlowRouting.drainageOrder` backwards on the post-notch actual
  surface, with the discharge and the cover held for the pass.
- **Each cell.**
  - `z' = z_r' + (z - z_r') / (1 + F)`, carried out in double and rounded once, which is
    `(z + F z_r') / (1 + F)`.
  - `z_r'` is the level the cell grades to:
    - its receiver's new ground on land;
    - the round's shoreline where the receiver is sea;
    - the water's surface where the receiver stands under a filled basin's water by more than the
      pond depth.
  - A cell at or below that level is left alone. The test is against the new height, so a cell
    below its receiver when the pass began is cut once its receiver has been cut below it.
  - A cell with no receiver is unchanged.
- **The limits, holding immediately after the pass.**
  - No cell the pass moves ends below the level it grades to, or above where it stood.
  - No mouth ends below the shoreline.
  - A cell at or below its level is not moved.
  - The half-the-drop cap is gone. The shoreline cap is now the mouth's boundary condition.
- **One addition to the brief: the lake's surface as a base level.** The explicit pass read its
  drops on the filled surface, so it never cut under standing water and graded a lake's inflows to
  the lake. On the actual surface, a literal reading would have cut 3,841 cells into lake beds in
  seed 42's first round alone. A river entering a lake grades to the lake, just as one entering the
  sea grades to the sea, so the rule treats standing water as a base level.
- **What it does not promise, which the KDoc says.** Stability is not accuracy. At 336,476 years a
  round, a cell with a large `F` loses nearly its whole drop, and the scheme smears a knickpoint
  over four to five cell widths: first-order upwind diffusion. The realised cut responds to a factor
  on `F` as `e (1 + F) / (1 + e F)`, not as `e`.

## The guards, each shown failing then passing

The wrong updates were patched into a scratch copy of `incise` and the guards run against each:
- **capped** is chunk 3's cut re-expressed in the new pass: `F` times the drop read on the filled
  surface, capped at half of it and at the shoreline, then clamped to the receiver's new height;
- **no-op** cuts nothing;
- **old-height** judges eligibility against the receiver's height before the pass;
- **explicit** is the law's `F` times the drop, clamped at the base.

| Guard | Implicit (head) | Fails on |
|---|---|---|
| Bounds and eligibility, seed 42's twelve rounds | 1,200,007 cuts, 182,107 at F>1 every one past half its drop, 40,236 mouths none below the sea, 31,010 left alone, 2,468 cut only because their receiver was; 0 violations | capped (20+ violations: large-F cells cut no more than half; cells moved though at their base), no-op (no cuts), old-height (cells left standing after their receiver was cut below them) |
| Eligibility fixture | the middle cell 90 m to 50.00 m, `F / (1 + F)` of its drop to the receiver's new 10 m | old-height (left at 90 m), capped (left at 90 m), no-op |
| One round removes `F / (1 + F)`, against a recursion in metres, `F` 0.024 to 66 | every cell within 1 cm | capped (191 cells off), no-op, explicit (192 cells off) |
| Steady state, two rocks and two uplift rates | concavity 0.5000 every zone; steepness 200.0/50.0/400.0/100.0 m against `U / (K e)`; ×2.0000 with doubled uplift; hard over soft 3.9999 against 4 | capped (concavity 0.31 and 0.16; soft rock 180 m where the law says 50), explicit, no-op (never balances) |
| Steady state, two rain zones | concavity 0.5000; wet over dry 0.5000 against `sqrt(1/4)` | capped (1.10; concavity 0.11 and 0.24), explicit (0.19), no-op |
| Knickpoint isotropy | sharp-break retreat 2.0000 cell widths east-west, north-south and in two half rounds, the law's celerity; steepest-change point 1.25 to 1.60; smear 4.1 to 5.4 | capped (2.75 against 2.875), no-op (3.0000), explicit |
| Clock (`ErosionUnitsTest`) | law's rate `K T sqrt(A) S` within 7e-8 | the tree before Fix 3 (2.67×) |
| Mouths (`ErosionUnitsTest`) | 40,236 mouth cuts, none below the shoreline | no-op (no mouth cut); chunk 3's cap kept mouths up too |

**The steady-state harness.** It drives production's routing and `incise` on a 64-cell island.
The routing is taken afresh for a thousand rounds and then held, because a handful of divide cells
cycle between two receivers forever. They cycle under the facet routing and under plain D8 alike,
and the cycle is the routing's, not the law's.

**The knickpoint.** Its position is read two ways, and the two are printed side by side:
- *As a sharp break of equal relief.* That is exact under the law's celerity, and it is what the
  guard asserts to a thousandth of a cell.
- *As the steepest-change point.* That depends on the smear, and is held to a cell width.

**`GroundIsotropyTest` and `ValleyIncisionTest` (B-D1).**
- The valley notch is armed at 4.64 times the bare ground, 0.0229 deep. By the step it reads
  0.0225, 0.0230 and 0.0229 along a row, down a column and on a diagonal on seed 7, where the cap
  left the columns a quarter to a third shallower.
- Seed 99's steep ground is armed: 25.9 m/km along a row, 26.7 down a column.
- The coast's projection ratio stays recorded under a finding of its own. It reads 1.04, 1.14, 1.14
  and 1.16 on seeds 7, 42, 1234 and 99, 1.12 pooled. The cap no longer explains it, and nothing yet
  measured does.

**`ClimateFedErosionTest`.**
- **The cover clause, on the law's rate** (`F` times the drop as the pass found it): the quotient is
  the factor within 3e-7 on 89,000 to 92,000 cells a seed. The unshielded control is out by 0.37 to
  0.40.
- **The realised cut, kept where `F` is under a tenth:** the share of the drop matches
  `e (1 + F) / (1 + e F)` within 9e-4 on 5,300 to 8,700 cells a seed, and stands within 0.03 of the
  factor itself.
- **The wet flank, on the law's rate.** Turning the feed on also turns on the cover, and the wet
  flank is the better wooded. So the clause holds the unchanged bar, `sqrt(P) * 0.8`, to the rain
  alone: the fed run with the cover's factor taken out. Seed 1234 reads 1.67 against 1.49, and 1.42
  with the cover in as well. It is armed.
- **The dissection contrast** is armed. Its pin clause (B-I2) stays recorded with new figures.

**`ReceiverClampTest`.** Its control is now the eligibility rule. Without it the incision leaves
588, 1,286 and 1,036 channel cells below their receivers on 718106, 42 and 7; with it, none.

## The calibrations re-examined

None was re-tuned.
- **`transportCapacity` (20).** Its derivation is a ratio to the incision's coefficient plus a
  measurement that it barely matters. Neither reads the cap, so it stands. It is not an Earth figure.
- **The load, measured.** The load the law delivers is twice the cap's: 5,706 against 2,757 incised
  on seed 7, 7,044 against 3,664 on 42, and 4,416 against 2,131 on 1234 (field units summed over the
  map and the rounds). The deposition rose 19 to 37%, the delta lobes 15 to 30%, and the deposition's
  share of the cut fell from 1.2–1.6% to 0.8–0.9%.
- **`deltaShare` (0.15), with `deltaFreeboardMetres` and `deltaMinCatchment`.** These were chosen by
  what they did to the culture guard on the capped worlds, which is setting a value to make a world
  pass. The KDoc now says so. They are recorded as open, and an Earth figure for a delta's share of
  its river's load would derive the first.
- **`outletIncisionRatio` (1.125).** This was chosen on the largest lake at three grids, which is
  also setting a value to make worlds pass, and nothing here derives it. On the law's terrain the
  notch leaves seed 99 a lake 2.1 times the Caspian's share, and the fill 82% as deep as without
  the notch; both are recorded. Whether a knickpoint should cut harder than an ordinary reach is
  open.
- **The map's slope floor for pen and ink** is the tenth percentile of the gallery world's slope by
  its own rule, re-derived from 0.07 to 0.08.
- **The relief's haze (matched at 0.12 against 0.10) and ordinary ground (0.8756 against 0.9225).**
  Both travel to the graphics card's shader, whose parity guard cannot run here, so they are
  recorded and not re-derived. Re-derived on its own, ordinary ground also put a synthetic plane in
  the shading guard 1% past its bar.

## The denudation and the uplift

**The measurement.** `IsostasyTest` now runs its derivation with every uplift rate and the flexure
off. It takes the mean lowering of the collisional and Andean belts' land over the twelve rounds and
divides by 12 × 336,476.4 years. The belts are the test's own selection, the land within the
falloff of a collision or Andean boundary.

| Seed at 512 | 7 | 42 | 1234 | 99 | 718106 | Pooled |
|---|---|---|---|---|---|---|
| Denudation, mm a year | 0.224 | 0.261 | 0.220 | 0.231 | 0.257 | **0.238** |

For comparison: 0.074 under the cap, with the flexure on; 0.134 on the implicit update with the
flexure on.

**The derivation.** It keeps three quantities apart. Rock uplift is surface uplift plus denudation,
and the flexural response is what the flexure-off measurement keeps out. With Earth's collision
surface uplift at 0.5, the collision rock uplift is **0.738** mm a year. England and Molnar's ratios
then give:
- Andean **0.2952** (two fifths);
- island arc **0.1038**;
- rift shoulder **0.04476**.

They were 0.290625, 0.11625, 0.040875 and 0.017625.

**`RATE_WAITS_FOR_THE_LAW`** is armed. Its tolerance, by its own rule of half the five seeds'
spread, is 0.02.

**Rechecking with the uplift on.** The uplift feeds nothing the derivation reads, so it could not
move the measurement. It did move the worlds: every re-record below was taken after it.

## The generated worlds

`RiverProfileReportTest` reports this and asserts only that it measured something. It reads the
river stage's traced courses of 20 cells or more:
- **Slope:** the fall on the eroded ground to the next cell of the course, over the step on the
  ground.
- **Area:** the unweighted drainage area.
- **Left out:** cells under standing water and cells that do not fall.
- **Concavity:** fitted by least squares on the logarithms.
- **Homogeneous reaches:** runs of ten read cells with one uplift and the cover's factor within 0.2.
  Of those, the ones spanning 0.3 decades of area are fitted.

| | Implicit (head) | The capped update (89f062f) |
|---|---|---|
| Whole-profile concavity, median (quartiles) | 0.60 (0.39–0.83), 777 courses | 0.47 (0.21–0.72), 706 |
| Homogeneous reaches, median (quartiles) | 0.42 (0.22–0.66), 741 reaches | 0.47 (0.03–0.88), 711 |
| Reaches in Earth's 0.4–0.7 (Whipple and others 2013) | 31% | 21% |
| Steepness `S sqrt(A)`, active uplift over none | 1.6–1.9× (556/336, 617/391, 512/273, 545/306, 478/256 m) | 1.2–1.6× |

**Concavity.** It is Earth-like on the implicit build. The reaches' median sits in Earth's band and
their spread has narrowed a great deal, though two thirds of reaches still fall outside 0.4–0.7.
Whole profiles run concave, as Earth's do where they cross from steep belts onto plains.

**Steepness against uplift** follows the law strongly: the channels under active belts are 1.6 to
1.9 times steeper.

**Steepness against the cover's erodibility.** The law says softer ground makes gentler channels.
- Seeds 7 and 1234 follow it: 433, 420 and 328 m, then 361, 312 and 286 m, from the most resistant
  third to the least.
- Seeds 42 and 99 are mixed, and 718106 runs the other way.

**Steepness against rain.** The law says wetter ground makes gentler channels.
- 718106 follows it: 377, 304 and 248 m.
- Seeds 7 and 1234 run the other way, because their wettest ground is where the belts rise.

**The plain answer to "is the diversity there".** Partly. The rivers now differ with uplift as the
law says, and their long profiles are shaped by the law and not by a limiter. They differ with rock
and rain in the law's sense on some worlds and not on others. Four million years of rounds is not a
steady state, the uplift sits where the rain is heaviest, and the cover is a relative factor of 0.6
to 1.2, which is small against the uplift's contrast. The steady-state guards show the scheme
itself gives the law's full response to all three.

## The known failures: armed and re-recorded

Every one of chunk 3's `CAP_SETS_EVERY_CUT` clauses was re-measured. Where a bar is an Earth figure
it was kept.

**Armed:**
- the valley notch (4.64×) and its depth (0.0229);
- the steep ground's facing;
- the lowstand's estuaries (1.70× pooled);
- the belt's flank (263 m against 113) and the ranges' texture (167.8 m against 103.8);
- the rain's dissection contrast and the wet flank;
- the ice bed under Airy's share;
- the ice's comb;
- the monsoon's cold half;
- the cold-current coast (seed 1 now dries);
- the tropics' desert;
- the collision rate's derivation.

**Re-recorded with figures** (recorded before, still failing):

| Clause | Now | Before |
|---|---|---|
| Coast's projection ratio | 1.12 pooled, seed 99 1.16; under its own finding now the per-step cap is gone | 1.12 under the cap |
| Coast's box dimension (`LittoralCoastTest`, Earth-likeness) | 1.082 and 1.092, under Mandelbrot's band | 1.028 and 1.036 |
| Coast's dimension by ruler | 1.187, inside the band | — |
| Seed 99's drylands (Moglen) | 1.00 | 1.02 |
| Littoral smoothing gain | 0.548 against 0.436 | — |
| The plains' texture record | 98.1 m against 68.6; not re-taken on the world it would have to pass (the texture-off control is 113.8) | — |
| Recycling ratio | 0.295 against Earth's 0.30 | 0.279 |
| Rainfall calibration, windward coasts | 3,577 and 4,210 mm | — |
| Glacial lakes | 0.23–0.36 per 10k cells, iced zone ratio 0.79 | — |
| Ice sheets' thickness | 1,393 to 1,780 m over 409–574 km | — |
| Seed 59758's ice edge | 46 cells along a row | — |
| Foreland moat | 502 m | 242 m |
| Shelf's jump flood | 2 cells | — |
| Largest realm | seed 1234 38.2% | — |
| Rain-dissection pin (B-I2) | seed 7 at 0.027 | — |

**Newly recorded, each with its reason in its finding:**
- **The channel-head network grows with the grid:** 1.38 to 1.48 times from 512 to 1024 on seeds 7,
  42, 1234 and 99, against 1.15 to 1.23 under the cap. The implicit update is first order. Whether
  rounds of less time converge the grids is untried and is in `TODO.md`.
- **The ice's flow near its domes:** 56 to 63% of the ice outward at 77 to 84 degrees off radial on
  seeds 59758 and 7. Under the cap it read 87.5% at 49.6.
- **The notch no longer holds seed 99's lake under the Caspian:** 2.11 times its share. Seed 42's
  world also keeps just over half its water. Pooled, the fill stands 82% as deep as the control's.
- **The estuary control reaches the ceiling:** seed 1234 has 44 mouths without the lowstand.
- **The flat potential's cost:** 2.02% of a generation on a quiet machine, over rule 8's hundredth,
  because its flats hold 3,147 raised cells against 2,696.
- **In `:cartography`:**
  - the ink runs 31.9 degrees off the fall line, past its 30;
  - Scroll's steppe tint stands at 80%, past 75%;
  - seed 42's tiny sheet no longer exercises the river budget's floor.

**Re-taken by their own rules:**
- the geometry census at 512, from its own record lines (the raster coast's facing figure falls from
  3.55–4.48 to 3.16–3.82);
- the twelve render records;
- the pen-and-ink slope floor.

**Lakes.**
- **Fewer.** With the notch off at 1024 (the comb case's worlds), they hold 1,676 to 7,175 lake
  cells, against 12,257 to 24,088 on the capped update.
- **In bars, a rule 13 finding.** Without the ice, about a tenth of that water lies in thin parallel
  bars at grid bearings, against about a hundredth before. In cells that is 181, 560 and 467,
  against 172, 193 and 234. It is recorded in `TODO.md`, since the comb clause only measures the
  ice's own addition.

## Cost per round at 2048

Seed 969495 at 2048, with 1.59 million land cells, on this four-processor machine:
- **The implicit pass:** 100 to 114 ms a round, over six timings.
- **One routing** (fill, directions, accumulation and order): 1.23 s.
- **The whole erosion stage:** 112.8 s for twelve rounds, against 104.1 s for chunk 3's tree on the
  same machine.

The pass is about a tenth of a routing and about 1% of a round. The stage's extra 8% comes from the
terrain, not the pass: more steep ground and flats for the flat potential, more load for the
deposition walk, and the higher belts. The graphics-card path is specified in `TODO.md` and is not
built.

## What the renders show

**The files.** Seeds 7 and 42 at 1024 and 969495 at 2048, in the Atlas view and as elevation, whole
and cropped, from `origin/main` (fb22d30), chunk 3's head (89f062f) and this head. They are under
`review/renders/`, named `<seed>-<size>-<atlas|elevation>-<whole|coast|range>-<main|chunk3|implicit>.png`.

**The windows.** They were chosen once on main's world and reused for the other two:
- **coast** is the window holding the most traced river mouths;
- **range** is the window holding the most ground over 1,500 m.

**The dissection comes back.**
- Chunk 3's ranges and plateaus are the smooth, pillowy masses its report described.
- On this head the valleys are back and denser than main's. Dendritic networks reach into every
  slope, divides are sharp, and the plateaus of seeds 7 and 42 are cut into where chunk 3 left them
  as smooth ramps.
- On 969495's coast the whole peninsula is finely dissected where chunk 3's was a broad smooth
  surface.

**The rivers.**
- Rivers under the belts run through deep valleys.
- The trunks on the plains are much the same courses.
- Channels are drawn denser on the dissected ground.

**Rock, rain and uplift.**
- **Uplift** shows plainly. The belts stand far higher and whiter under the re-derived uplift: seed
  42's southern range carries snow along its whole crest, and 969495's Y-shaped range is a white
  wall.
- **Rock and rain** are not something the eye separates on these maps.

**The climate changed with the relief.**
- 969495's broad interior desert on chunk 3's tree is mostly green on this head.
- Its northern and western ice caps have almost gone.
- Seed 42 holds fewer lakes.

I did not separate which of the uplift and the incision moved these.

**Grid-shaped (rule 13).** The flanks of east-west ranges are combed by straight north-south gullies
a cell apart: seed 7's upper-right range in `7-1024-elevation-coast-implicit.png`, and the upper left
of `42-1024-elevation-range-implicit.png`.
- **What the count says.** On steep land, 59 to 64% of cells drain straight down a column on all
  three trees, against about 35% expected on these cells. Of those, 21 to 29% have both row
  neighbours doing the same, again on all three trees.
- **So it is the routing's bearing, made visible.** The law now cuts those channels to grade over
  twice the steep ground, where the cap kept them shallow.
- **Not new.** 969495's straight range flanks are the plate boundaries', and are the same on chunk
  3's tree.
- **Missed by the guard.** The geometry guard does not see the comb. It is in `TODO.md`, with the
  hillslope transport length that would answer it.

## Tests

**`:worldgen:jvmTest`** was run alone on the final head. **`:cartography:jvmTest`, `:ui:jvmTest` and
`:desktop:test`** were then run together in one build. Counts are from the JUnit XML under each
module's `build/test-results`.

| Task | Tests | Failures | Errors | Skipped | Known failures reported |
|---|---|---|---|---|---|
| `:worldgen:jvmTest` | 265 | 0 | 0 | 0 | 23 |
| `:cartography:jvmTest` | 117 | 0 | 0 | 0 | 24 (and 335 clauses too small to measure) |
| `:ui:jvmTest` | 129 | 0 | 0 | 0 | 5 |
| `:desktop:test` | 104 | 0 | 0 | 19 | 2 |

**Desktop's 19 skips.**
- The graphics-card tests, which need a display: `GpuRasterTest` 5, `GpuOceanTest` 5, `GpuErosionTest`
  2, `GpuIceSheetTest` 1.
- The benchmarks: `GpuExportBenchmarkTest` 4, `EngravedRasterBenchmarkTest` 2.

**The generator's 265** is chunk 3's 259, plus the six new cases in `ImplicitIncisionTest` and the one
in `RiverProfileReportTest`, less the retired half-the-drop case in `ErosionUnitsTest`.

**No red was rerun to be believed.** The one impossible-looking red, the flat potential's cost on a
loaded machine, was measured alone before it was recorded (2.02%).

**Could not run here:**
- `:ui:wasmJsTest`, `:desktop:siteTest` and `:worldgen:wasmJsTest`: a Kotlin tooling download is
  refused.
- The graphics-card tests: they skip without a display, and are counted as skips above.
- This is also why the relief's haze and ordinary ground were recorded rather than re-derived.
