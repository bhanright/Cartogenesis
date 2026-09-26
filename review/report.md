# Chunk 3b, stage 1: the implicit incision on the processor

**Branch:** `chunk/3b-implicit-erosion` at `5bb85b3`, from `origin/chunk/3-erosion-units` at 89f062f.
Not merged, and no pull request. **A review round since** takes the branch to `bb7b606`; see
[the last section](#review-round).

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

## Review round

**Branch:** `chunk/3b-implicit-erosion` at `bb7b606`. Not merged, `origin/main` not merged in, and no
pull request.

| Commit | What it does |
|---|---|
| `991148e` | A lake falls with its outlet in the implicit pass; the draining-lake guard; the bounds guard made strict and given its own base. |
| `baa9a82` | The collision uplift's KDoc says what its derivation does not establish. |
| `549f657` | The generator's clauses the lake change moved: fourteen re-recorded, three armed, two recorded under a new finding. |
| `0f11942` | TODO and the ledger: the grid measurement, the ridge measurement, the lake semantics; the grid finding renamed. |
| `528f024` | The map's clauses the lake change moved (`:cartography`). |
| `bb7b606` | TODO and the ledger carry the comb's cause. |

### 1. The lake's surface during the pass

**The defect.** The pass held a lake at its filled level for the whole pass. A lake cell's receivers
are processed first, so by the time an inflow was graded, the outlet below the lake had often been
cut far below the lake's filled level. The inflow still graded to the old water, which no longer
stood there.

**The semantics chosen**, the candidate the review proposed:
- A lake's surface for the pass is the lower of its filled level and the level its outlet drains to
  once the outlet is cut. It is carried upstream through the lake's cells, receivers first, so the
  outlet is final before the lake behind it.
- A cell under that surface is neither cut nor raised.
- A cell the falling water uncovers is graded like any other.
- An inflow grades to the surface as it now stands.

**The guard,** `a lake falls with its outlet and its inflow grades to the lowered water`: a sea, an
outlet at 100 m, a lake bed filled to 100 m, and an inflow at 120 m, with the bed and inflow at `F` 9.

| Case | Outlet | Bed | Inflow | Inflow on 5bb85b3 |
|---|---|---|---|---|
| Outlet at `F` 9: the lake drains past its 70 m bed | 10.00 m | 16.00 m (uncovered, graded) | 26.40 m | 102.00 m |
| Outlet at `F` 0.25: the lake stands over its 50 m bed | 80.00 m | 50.00 m (kept) | 84.00 m | 102.00 m |

It fails on 5bb85b3's code and passes on this head.

### 2. The bounds guard

- **Strict.** The more-than-half-the-drop clause has no slack in the failing direction. Where the
  law's cut clears half the drop by less than two float steps of the height, the rounding of one
  cell can land either side of it, so those cells are counted and printed, not judged: 28 of the
  190,908 cells at `F` over one on seed 42 at 512. None of the others fail.
- **Its own base.** The watcher now finds each cell's base itself from the pass's result, not from
  the pass's report:
  - the shoreline for the sea;
  - the receiver's new ground on dry land;
  - for a receiver under a lake: walking down through the lake to what it spills onto, the lowest of
    the filled levels on the way and of that last level, or the receiver's new ground if that is
    higher.

  It requires the reported base to equal that exactly. On 5bb85b3's frozen lake it fails in round 0
  (20 violations, the most the watcher keeps, the first at cell 638); on this head there are none.
- **The KDoc** says the bound is on the cells the pass moves: a basin's floor under water, and a
  cell already at or below its base, keep their height below it.

### 3. What the lake change moved

The full `:worldgen:jvmTest` ran twice on this round's code: 20 red of 266 before the re-records, 0
of 266 after. Two ignored probe files were compiled into the second run and reported 268; they are
left out of these counts.

- **Re-recorded, figures only** (14): the rainfall calibration, the dissection contrast, the
  Earth-likeness suite, the coast's projection ratio (1.13), the plains' texture, the glacial lakes,
  the ice's flow and thickness, the foreland moat, the shelf's jump flood, the coast's dimension, the
  recycling ratio (0.296), and the notch's two clauses. In the Earth-likeness suite, seed 99's
  drylands now pass and the box dimension reads 1.093.
- **Armed** (3):
  - the ice sheet's edge: 31 against 36.1 allowed, where it was 46 against 36.6;
  - the graded coast's gain: 0.553 against 0.412, a gain of 1.34 over a bar of 1.3, **narrowly**;
  - the largest realm: 29%. It is in `RealmSpreadTest`, a file chunk 6 also changes.
- **The notch.** Seed 99's largest lake is now under the Caspian's share (0.113% of its land against
  0.249%). What fails instead is seeds 718106 and 7 keeping more than half their water after the
  notch.
- **Two new failures, under one finding: the cost of these semantics.** With the notch off at 1024,
  the thin parallel grid-bearing bars' share of the standing water:

  | Seed | Ice off, before | Ice off, now | Ice on, before | Ice on, now |
  |---|---|---|---|---|
  | 42 | 0.078 | 0.168 | 0.085 | 0.163 |
  | 7 | 0.120 | 0.153 | 0.108 | 0.137 |
  | 718106 | 0.108 | 0.167 | 0.098 | 0.159 |

  - The ice's comb clause counts cells. It is over its fiftieth on seed 42 (2.48%) because the
    glaciated world holds 1,554 more lake cells, not because the ice's own bar share rose.
  - The ruled-bar census finds its first bar since the facet routing: 27 cells on seed 42 at 512.
  - The likeliest reading, not tested: inflows now cut to a lower base, so more grid-bearing channels
    are deep enough for the next round's fill to stand in. **It wants weighing before the merge: it
    is a rule 13 regression the semantics bring.**
- **The map** (`:cartography:jvmTest`): 4 red of 117.
  - Handled: the 512 census re-taken (the anomaly's corner rate on seed 7 joins its banding finding),
    the recorded renders regenerated, ordinary ground 0.8750, and seed 99 joining seed 42 on the river
    floor.
  - The four were re-run alone and pass. The whole task was not re-run.

### 4. The network's growth with the grid (reported, nothing changed)

Channel-head density in km of channel per km² of land, by `ScaleFreeTest`'s arithmetic:

| Seed | 512 | 1024 | 1024, 24 rounds of half the years | 1024 / 512 | Half the step / 1024 |
|---|---|---|---|---|---|
| 7 | 0.0324 | 0.0499 | 0.0501 | 1.54 | 1.004 |
| 42 | 0.0379 | 0.0571 | 0.0582 | 1.51 | 1.019 |
| 1234 | 0.0296 | 0.0422 | 0.0422 | 1.42 | 1.000 |
| 99 | 0.0284 | 0.0412 | 0.0412 | 1.45 | 0.999 |

**It does not converge toward 512 as the round shortens.** The time step has already converged: half
the step moves the network by 0 to 2%. The growth is the grid's, not the round's.

The earlier report's reading, that the implicit scheme being first order was the cause, is not
supported. The finding is renamed to say so. Which part of the grid is responsible has not been
isolated:
- the criterion reading a slope over a shorter step;
- `F` doubling at a fixed catchment when the cell halves;
- or the routing.

### 5. The comb (reported, nothing changed)

**Synthetic ridges.** Running east-west and north-south on a 256 grid (cells 46.9 km by 23.4 km):
- a 4,000 m crest falling linearly over 50 cell widths either side, with 2 m of roughness;
- twelve rounds of uniform uplift;
- the production implicit update against a capped explicit one written in the probe.

Channels are cells with eight or more cells upstream. "Parallel" means a channel draining the same
way within two cell widths of ground across the fall line.

| Ridge | Update | Uplift | Channels down a column | along a row | diagonal | with a parallel channel |
|---|---|---|---|---|---|---|
| east-west | implicit | 0.29 | 94.8% | 0.5% | 4.7% | 58.8% |
| east-west | implicit | 0.738 | 94.5% | 0.6% | 4.9% | 56.3% |
| east-west | capped | 0.29 | 98.6% | 0.4% | 1.0% | 98.6% |
| east-west | capped | 0.738 | 98.4% | 0.4% | 1.2% | 98.5% |
| north-south | implicit | 0.29 | 3.0% | 89.4% | 7.6% | 71.2% |
| north-south | implicit | 0.738 | 3.7% | 88.6% | 7.7% | 69.4% |
| north-south | capped | 0.29 | 5.5% | 91.5% | 3.0% | 96.7% |
| north-south | capped | 0.738 | 6.3% | 90.6% | 3.0% | 96.7% |

- On both bearings and under both updates the channels follow the fall line.
- The cap combs more, and evenly on both bearings.
- The implicit update combs less, and no more on east-west ridges than on north-south ones.

**The update does not make the comb.**

Two caveats:
- With 20 m of roughness on a 1,500 m crest the roughness outweighs the ridge's fall. The order is the
  same (capped 61 and 80% parallel, implicit 19 and 51%), but channels there take diagonals 23 to 43%
  of the time.
- The share of *steep* cells draining along a grid line, which the request named, is no instrument
  once the valleys are cut deep. On the implicit ridges the steepest third of the ground is valley
  walls, and it drains across the fall line: 62 to 68% along a row on the east-west ridge. The
  channel reading above is the one that answers the question.

**The comb's cause, which decides the fix.** The steepest third of the land at 1024 on seeds 7 and 42,
counted on three surfaces routed by the production routing over the same land:
- an isotropic synthetic surface built on the ground (sixty-four waves of random bearing, 4 to 40 cell
  widths long);
- the world's terrain before erosion (`PlateStage`'s height);
- the finished world.

| Surface | Down a column | Along a row | Diagonal |
|---|---|---|---|
| Bearing geometry alone on these cells | about 35% | about 15% | about 50% |
| Isotropic surface, seeds 7 and 42 | 51.4, 47.4% | 17.0, 18.2% | 31.6, 34.4% |
| Terrain before erosion | 41.8, 44.5% | 17.7, 17.6% | 40.6, 37.9% |
| Finished world | 60.5, 59.1% | 13.6, 14.2% | 25.9, 26.7% |

The geometry row: on a cell half as tall as wide, a diagonal neighbour stands 63.4° off north. So on
locally planar ground, steepest descent to the nearest bearing goes:
- down a column for a fall line within 31.7° of north or south;
- along a row within 13.3° of east or west;
- on a diagonal otherwise.

So:
- On isotropic ground the routing already sends 12 to 16 points more down the columns than the
  bearings give, taken from the diagonals.
- The terrain before erosion sits near that baseline.
- The rounds of erosion deepen the preference to about 60%. The earlier count found 59 to 64% on
  main's tree and chunk 3's too, so any incision does this, not the implicit one in particular.

**The fix this points at is in the routing's choice between a column and a diagonal on a cell of this
shape, not in the incision.** Across the fall line, the share of steep cells whose two neighbours
drain the same way falls through the erosion (48 and 47% before, 23 and 24% finished), so the
channels organise. What the eye reads as a comb is the bearing, not a lattice of one-cell gullies.
Not tested: whether the routing's surplus comes from the facet rule, the flat potential, or the depression fill.

### 6. The uplift's note

`WorldGenConfig`'s KDoc for the collision rate, the ledger, and this report now say it:
- The 0.738 mm a year is exact only for the flexure-off measurement it was taken from: 0.5 mm of
  surface uplift plus 0.238 of denudation.
- With the flexure on, the same belts lose 0.134 mm a year, the flexural response entering the same
  budget.
- Nothing measures the surface uplift production's belts actually make. The rate is the flexure-off
  balance, not a calibration of production's ranges.

### 7. The renders

**The maps moved visibly, so this head's renders were re-taken.** Main's and chunk 3's are unchanged
and were not. They are under `review/renders/` with the suffix `-review`, in the same windows as
before.

Against the previous head (`-implicit`), the share of pixels off by more than 32 levels:

| World | Whole | Coast | Range |
|---|---|---|---|
| 7 at 1024 | 2.2% | 7.2% | 6.6% |
| 42 at 1024 | 2.8% | 9.7% | 8.9% |
| 969495 at 2048 | 1.5% | 5.8% | 5.1% |

What the eye sees:
- **Lakes are smaller.** The lake south of seed 42's long lake is nearly drained, seed 7's long lake is
  narrower, and 969495's lake in the south-east is gone.
- **Rivers re-route** in places.
- **The relief and its dissection read the same.**
- **The flanks' comb is unchanged.** The easiest place to see it is 969495's eastern flank in
  `969495-2048-atlas-coast-review.png`.

### 8. Tests this round

The review narrowed this round's testing partway through: only the guards the round touches and
the measurements; the full suites and the renders to run once, after the comb fix and a merge of
`origin/main`. What ran:

| Run | On | Result |
|---|---|---|
| `ImplicitIncisionTest`: the new lake fixture, and the bounds guard | this head's code, and 5bb85b3's pass | pass, and fail on 5bb85b3 |
| `:worldgen:jvmTest`, whole | after the lake change, before the re-records | 20 of 266 red, handled above |
| `:worldgen:jvmTest`, whole | after the re-records (`549f657`) | 266 of 266 pass, 22 known failures |
| `ScaleFreeTest`'s channel-head clause | after the renamed finding (`0f11942`) | pass, recorded |
| `:cartography:jvmTest`, whole | after the lake change | 4 of 117 red, handled above |
| `GeometryGuardTest`, `PenAndInkTest`, `ReliefShadingTest`, `RiverSelectionTest` | after the re-records | pass (22 tests) |
| `:ui:jvmTest`, whole | this head's code | 129 of 129 pass |
| `:desktop:test` | not run | stopped on the review's instruction |

`ErosionUnitsTest` and `ReceiverClampTest` were not changed this round; they ran green inside the
whole-worldgen run above. The probes (`SeedProbeGrid`, `SeedProbeRidge`, `SeedProbeCombCause`,
`SeedProbeRender`) are ignored files and are not committed.

## Comb experiments

**Branch:** `chunk/3b-implicit-erosion` at `1e8b3ac`. Not merged, `origin/main` not merged in, no pull
request, no full suites, no final renders. Every figure below is at 512 on seeds 7 and 42 unless it
says otherwise.

| Commit | What it does |
|---|---|
| `93169ca` | A guard on the router's step shares on planes; the comb's cause restated; `subGridDraw`'s KDoc fixed. |
| `16a92c8` | The comb guard (`CombGuardTest`, `CombCensus`), recorded as failing on this head. |
| `9a3fc39` | Candidate B behind `ErosionConfig.subGridTransport`, off by default, with `SubGridTransportTest`. |
| `1e8b3ac` | Candidate A behind `ErosionConfig.incisionNeedsChannelHead`, off by default, with its bounds case in `ImplicitIncisionTest`. |

Either candidate can be kept by turning its setting on and dropped by reverting its commit. They
touch separate code, apart from one shared settings block in `WorldGenConfig`.

### 1. What the router does, corrected

The review round read the steep ground's column share against 35%, which is what the nearest of the
eight bearings would give on cells half as tall as wide. The router is not that rule: it is
Tarboton's facet direction with the Rho8 draw.

- **Its own expectation** over isotropic bearings, integrated from the facet geometry, is 44.868%
  down a column, 15.311% along a row and 39.821% on a diagonal. This agrees with the review's
  figures.
- **Production's router on planes** at 360 bearings on three seeds takes 45.04%, 15.38% and 39.58%,
  each within 1.5 standard errors. The mean step's bearing holds on every plane; the worst is 3.5
  standard errors on the worst of 1,080 planes.
- **The new guard** (`RoutingGroundTest`) fails the plain steepest-of-eight rule at 35.6%, 14.4% and
  50.0%.
- **The standard error is counted over cells, not cells times bearings.** A cell's draw is the same
  hash at every bearing of one seed, and counting it once per bearing had first put the router 14
  errors off.

So the router has no residual on planes, and nothing to correct cheaply there. Against its own
44.9%:
- the isotropic synthetic surfaces read 47.4% and 51.4%, 2.5 to 6.5 points over it: rough, filled,
  steep-selected ground, not a plane;
- the terrain before erosion reads 42.0% and 45.9%, at the rule;
- the finished world reads 57 to 58%.

The earlier "the fix is the routing's" was wrong.

### 2. The feedback, tested on the same cells

The cells followed are the steepest third of the terrain before erosion, routed there by production's
router, and read again on the finished world's drainage. The same cells in every run (31,400 to
32,100 a seed).

| Run | Column share, before → after | Diagonal → column | Row → column | Column → column |
|---|---|---|---|---|
| Stock, seed 7 | 42.0 → 57.0% | 38.7% | 48.0% | 78.1% |
| Stock, seed 42 | 45.9 → 57.9% | 37.9% | 47.4% | 77.8% |
| A, seed 7 | 42.0 → 57.2% | 40.9% | 50.2% | 75.6% |
| A, seed 42 | 45.9 → 58.4% | 40.4% | 49.8% | 76.0% |
| B, seed 7 | 41.9 → 39.8% | 20.3% | 25.1% | 64.5% |
| B, seed 42 | 45.8 → 43.8% | 23.0% | 29.7% | 65.7% |

The rounds turn two in five diagonal steps and half of all row steps into columns. Under B the
column share stays at the rule's, and those conversions halve. This is consistent with a feedback
in which a channel cut down a column pulls its neighbours down the column, and the lateral transport
breaks it. It does not isolate that mechanism: B changes everything the rounds see, not only the
columns.

### 3. The comb guard

`CombGuardTest` counts channel cells, by the model's own channel-head criterion (the network the map
draws and every other drainage clause measures), that run:
- in a straight reach along one grid axis at least **60 km** long;
- with a second such reach running the same way **20 to 50 km** across;
- with a ridge of **100 m** or more between the two.

Both axes are counted at the same kilometres on the ground.

The bar has two parts:
- **The comb.** The columns may carry 3.5 times the rows' comb. That is the rule's own column-to-row
  ratio of parallel reaches on the isotropic surfaces (3.1 and 2.0), rounded up. Those surfaces'
  parallel reaches never have a 50 m ridge (0.00 to 0.01 km per 1,000 km²).
- **The network.** It may not thin by more than `ScaleFreeTest`'s 1.35, so the comb cannot be
  removed by removing channels.

The row control is matched by distance, not by count. Straight reaches are longer on the ground down
a column than along a row under this rule: for the same angular deviation about 23.4 / tan φ km
against 11.7 / tan φ. The 3.5 allows for that.

| Seed | Run | Channel, km per 1,000 km² | Sustained, column / row | Comb, column / row | Guard |
|---|---|---|---|---|---|
| 7 | stock (bb7b606's erosion) | 31.64 | 2.72 / 0.95 | 0.408 / 0.061 | fails: comb |
| 42 | stock | 36.61 | 3.27 / 1.05 | 0.519 / 0.079 | fails: comb |
| 7 | A | 36.53 | 2.86 / 0.96 | 0.429 / 0.068 | fails: comb |
| 42 | A | 40.81 | 3.27 / 1.07 | 0.533 / 0.083 | fails: comb |
| 7 | B | 20.77 | 3.04 / 1.75 | 0.077 / 0.000 | fails: network, and comb against a row of nought |
| 42 | B | 25.34 | 3.68 / 1.89 | 0.110 / 0.002 | fails: network |

At 1024 on stock the columns carry 0.44 and 0.48 and the rows 0.01 and 0.00, so the measure holds
across the grids.

It is recorded as a known failure on this head. **It has a weakness:** the ratio has no floor, so a
world with almost no row comb fails however little column comb it has. That is B's case on seed 7.
The maintainer may want an absolute floor added. I did not add one, because the only value I had to
set it from was this head's own row figure.

### 4. Candidate A: the incision cutoff

**As built** (`ErosionConfig.incisionNeedsChannelHead`):
- **The mask** is taken each round, after that round's routing and before the notch, by
  `ChannelInitiation`'s criterion:
  - the runoff-weighted area in km² over the round's own drainage, each cell's rain as a share of
    Earth's 715 mm land mean, from the rounds' provisional rainfall (or that mean where the climate
    feed is off);
  - the true ground's gradient to the round's receiver;
  - the rounds' provisional cover, against `RiverConfig`'s threshold and cover gain.
- **Carried downstream** through lakes, so a flatter reach below a head still incises.
- **Never reused:** each round's routing invalidates the mask.
- **Not applied:** the frozen-ground rule, because the rounds carry no summer temperature.
- **Cells left out** are not cut. They still pass their base upward, hold standing water, and take
  and carry spoil.
- **The watch.** The incision reports each left-out cell as excluded. The bounds guard holds it
  unmoved and holds the law's clauses on the rest: on seed 42, 428,881 cell-rounds were left out and
  792,822 cut, with no violation.
- **The notch** cuts only a basin's spill path, which carries the basin's whole discharge and is a
  channel by the same criterion, so it needs no change.

**What it did:**
- **Nothing to the comb:** 0.43 and 0.53 against 0.41 and 0.52.
- **The network grew** a little (36.5 and 40.8).
- **The ground stepped more:** the mean step across a row was 253 and 293 m, against 223 and 263.
- **Lake bars:** on seed 42 in production settings, 311 bar cells against 116. With the notch and
  ice off, 168 and 197 against 138 and 219.
- **The sea lobes** laid 9 to 12% less.

Why it misses: at these grids a cell's own area already passes a humid channel head (about 275 km²
against 0.01 to 0.1). What the criterion leaves out is gentle, forested or dry ground, and the comb
is on steep ground the criterion calls channel anyway. The crops show smooth uncut patches beside
the same comb.

**Recommendation: drop A.**

### 5. Candidate B: sub-grid transport

**The form** is the stream-power-plus-linear-diffusion model, `dz/dt = U - K A^m S + D ∇²z`:
- Perron, Dietrich and Kirchner, *Controls on the spacing of first-order valleys*, JGR Earth Surface
  113, F04016, 2008, and *Formation of evenly spaced ridges and valleys*, Nature 460, 502-505, 2009;
- Theodoratos, Seybold and Kirchner, *Scaling and similarity of a stream-power incision and linear
  diffusion landscape evolution model*, Earth Surface Dynamics 6, 779-808, 2018.

Its two laws set a length, `lc = (D/K)^(1/(2m+1))`, at which diffusion and incision are equally
effective, and Perron and others find valley spacing proportional to it. A caveat on the sources:
this environment's network proxy blocks the journals' pages, so these citations and the form rest
on search-engine records of the papers, not on reading them. The Earth range for `D` below is the
commonly quoted one, and I did not check it against a paper this round.

**The scale, derived before it was measured:**
- **Earth's soil creep** has `D` of 10⁻³ to 10⁻² m² a year. With this model's `K` of 10⁻⁶ a year,
  `lc` is 30 to 100 m.
- Over the rounds' four million years creep moves material `√(Dt)`, about 0.1 km, against cells of
  12 to 23 km. Earth's creep cannot act at this grid.
- **What a coarse cell's slope stands for** is many unresolved channels and hillslopes. The flux
  *they* carry across the cell's boundary, by the incision law on the cell's own ground (sub-grid
  catchment `a ~ Δ²`, upslope length `Δ`), is `K Δ^(2m+1) S`. That is diffusive, with
  `D_sub = K Δ²` at `m = ½`.
- **As built** it is `K e √w dx dy`, with the cell's cover factor `e` and runoff weight `w`: 274 m²
  a year at 512 on bare ground at mean rain. This is `lc` set equal to the cell, with no factor
  chosen.

**It may double-count.** The resolved incision already carries each cell's own-area cut along its
one receiver.

**As built** (`ErosionConfig.subGridTransport`):
- five-point diffusion of the actual ground after each round's deposition walk, over the land;
- conservative between land cells;
- into the sea at the shoreline, booked as incised and lost;
- explicit steps at a fifth of the stability limit.

`SubGridTransportTest`:
- an island's material is conserved to what crosses the shore (land lost 4.720452, sea took
  4.720451);
- an eight-row ripple keeps 0.668 of its amplitude, against diffusion's 0.674.

**What it did:**
- **The comb:** down about 80% (0.077 and 0.110 against 0.408 and 0.519). The row comb is almost
  nil.
- **The feedback:** gone (section 2).
- **Lake bars:** nearly gone.
  - Production: 20 and 6 bar cells (0.033 and 0.005 of 610 and 1,288 lake cells), against 127 and
    116.
  - Notch and ice off: 0 and 11, against 138 and 219.
  - Fewer lake cells too: 610 against 904 on seed 7.
- **Deltas:** unchanged (sea lobes 23.3 and 32.9 against 23.0 and 30.8).
- **Grid dependence:** better. The channel network grows 1.36, 1.33, 1.31 and 1.37 times from 512 to
  1024 on seeds 7, 42, 1234 and 99, against 1.42 to 1.54 stock, so two seeds come inside
  `ScaleFreeTest`'s 1.35.
- **The dissection clauses the branch armed**, run with B on in a scratch build (never committed):
  - the belt's flank: passes;
  - the wet flank: passes;
  - **the valley notch fails:** a finished channel stands 0.0108 of the field below its banks, more
    than a tenth shallower than the 0.0148 the clause asks;
  - the plains' texture, a recorded failure on this head, **now passes** (the harness says arm it);
  - the rain-dissection contrast fails as it does on stock, with new figures (seed 7 0.016 against
    0.035).
- **The network:** thins 34% and 31% (20.8 and 25.3 against 31.6 and 36.6), past the comb guard's
  1.35.
- **The ground:** the mean step across a row halves (120 and 148 m against 223 and 263). The land's
  mean height barely moves (1,564 and 1,802 m against 1,537 and 1,776).

In the crops, seed 7's range keeps its shape with most of the stripes gone and some left on its
south flank, but the lowland round it has lost most of its fine dissection. 969495's eastern flank
keeps parallel valleys running to the coast: fewer, broader and smoother, not gone.

### 6. The crops

In `review/renders/`: `comb-<seed>-<size>-<atlas|elevation>-<stock|A|B>.png`.
- **Seed 7 at 1024:** the upper-right range, sheet pixels 1040-1360 by 440-660.
- **969495 at 2048:** the eastern flank, sheet pixels 2096-2356 by 820-1080.

Each crop is a fresh generation with the setting on, at the same sheet window.

### 7. Recommendation

- **Drop A.** It does not touch the comb and adds lake bars on seed 42.
- **Keep B's direction, not B's scale as built.** B is the only candidate that removes the mechanism.
  It stops the rounds turning the flanks down the columns, cuts the comb by four fifths, removes
  most lake bars, brings the network's grid dependence toward `ScaleFreeTest`'s bar and fixes the
  plains' texture, and it leaves the deltas alone.
- **But at `K dx dy` it takes a quarter of the valleys' depth, a third of the network and half the
  ground's cell-to-cell relief.** It fails the valley-notch clause and the comb guard's network
  hold. The guard's comb clause, as written, also fails B on seed 7 only because the rows are
  nearly nought.
- **The next step is a derivation, not a tuning.** `D_sub` counts the cell's own-area transport the
  resolved incision already makes along its receiver. A sub-grid term net of that is the principled
  correction:
  - diffuse only the part of each cell's fall not carried by its receiver's cut;
  - or scale `D_sub` by the share of the cell's sub-grid catchment the resolved channel does not
    drain.
- **I did not try a smaller factor on `D_sub`.** Choosing one to bring the valley notch back inside
  its bar would be setting a value to make a world pass.

### 8. Tests this round

Only the tests the round touches were run, one Gradle build at a time:

| Test | Result |
|---|---|
| `RoutingGroundTest`, whole | pass; the new case fails on the steepest-of-eight rule |
| `CombGuardTest` | known failure recorded on this head |
| `SubGridTransportTest` | pass |
| `ImplicitIncisionTest`: the bounds, the lake and the new head-criterion cases | pass |
| With B's default on in a scratch build: `ValleyIncisionTest`, `GroundTextureTest`, `ClimateFedErosionTest`'s wet flank and dissection clauses, `CombGuardTest`, `ScaleFreeTest`'s channel-head clause | as in section 5 |

No full suite ran. The probes (`SeedProbeCandidates`, `SeedProbeCombGuard`, `SeedProbeCombCrops`) are
ignored files, not committed.

## Comb experiments, second round

**Branch:** `chunk/3b-implicit-erosion` at `071b0bb`. Not merged, `origin/main` not merged in, no pull
request, no full suites and no full render set. Every figure is at 512 on seeds 7 and 42 unless it
says otherwise.

| Commit | What it does |
|---|---|
| `43ab85f` | Reverts candidate A (`1e8b3ac`). |
| `c250862` | The two net forms, each behind a setting and off by default; the router can report the facet share; `SubGridTransportTest` holds both. |
| `79ea118` | `CombGuardTest`'s ratio gets an absolute floor from its control. |
| `071b0bb` | The ledger records the round. |

**The answer, plainly: no.** Neither form removes the comb while keeping the valleys and the
network.
- **Across the fall** removes about three quarters of the comb and stops the feedback, as B does. It
  keeps the network inside the guard's hold and passes every dissection clause the branch armed.
  But the valleys are a third shallower than stock's, and what is left of the comb is still ten
  times the control's floor.
- **The undrained share** keeps the valleys, the network and the texture almost as stock has them,
  and leaves the comb almost as stock has it.

### 1. The forms as built, and their derivations

Both start from B's flux, `-D_sub grad z` with `D_sub = K e sqrt(w) dx dy`. Both apply it as B
does: after each round's deposition walk, conservatively between land cells, into the sea at the
shore. At most one of the three sub-grid settings may be on. The derivations are in `ErosionConfig`'s
KDoc for each setting.

**Form 1, `ErosionConfig.subGridTransportAcrossTheFall`: diffuse only the part of the fall the
receiver's cut does not carry.**
- **Why along the receiver is counted twice.**
  - Take a cell with nothing upstream. The law's area is the cell's own, `a = dx dy` weighted by
    `w`, so the pass hands on `K e sqrt(w) (dx dy)^(3/2) S` of material a year across the face
    toward the receiver.
  - B's flux across that same face, of length `L`, is `K e sqrt(w) dx dy S L`.
  - These are the same flux. They are equal on a square cell. On this map's cells, half as tall as
    wide, they differ by `sqrt(2)` one way along a row and the other way down a column, so their
    geometric mean is equal.
  - Downstream the resolved cut carries that share and more.
  - So B's term along the receiver is a second copy of a flux the pass already makes. Across the
    fall it is a flux nothing else makes: hillslopes shedding sideways into the next channel.
- **The form.** Net of the receiver's component, the flux is `-D_sub (I - u u^T) grad z`, where `u`
  is the unit bearing to the receiver on the ground. The five-point scheme carries the tensor's
  diagonal:
  - a face across a row takes `D_sub (1 - u_x^2)`, and a face down a column takes
    `D_sub (1 - u_y^2)`;
  - a cell draining down a column diffuses fully across the row and not at all down the column;
  - a diagonal receiver, 63.4° off north on these cells, keeps a fifth across the row and four fifths
    down the column;
  - a cell with no receiver keeps the whole `D_sub`.
- **What is left out.** The tensor's cross term. It is nought for a cardinal receiver, and a
  five-point stencil cannot carry it monotonically.

**Form 2, `ErosionConfig.subGridTransportUndrainedShare`: scale `D_sub` by the share of the cell's
own catchment the resolved channel does not drain.**
- The router's Tarboton facet divides a cell's own water between two neighbours, `p` to the one the
  Rho8 draw picks and `1 - p` to the other. The resolved channel drains `p`, and its cut carries
  that share's transport, as form 1 derives. Nothing carries the `1 - p`.
- So `D_net = D_sub (1 - p)`, applied isotropically:
  - nought where the descent is clamped to one neighbour, which is every incised channel's cell;
  - the whole `D_sub` on a cell with no receiver.
- `FlowRouting.flowDirections` reports `1 - p` when it is asked for it. It routes exactly as before,
  and `WorldFingerprintTest` is unchanged.

**Literature.**
- I found no published form of either correction.
- Litwin, Malatesta and Sklar, *Hillslope diffusion and channel steepness in landscape evolution
  models* (Earth Surface Dynamics 13, 277-293, 2025), study the coupling this addresses: the
  stream-power-plus-diffusion model applying both laws in every cell, and the channel steepening it
  causes. The KDoc cites them for the problem only. As last round, the journal's pages are blocked
  here, so this rests on search records, not on reading the paper.

**`SubGridTransportTest`** holds each form to its law.

| Case | Figure |
|---|---|
| Island, B | land lost 4.720452, sea took 4.720451 |
| Island, across the fall (receivers on all eight bearings and none) | lost 3.015282, sea took 3.015282 |
| Island, undrained share 0 to 1 | lost 2.423606, sea took 2.423606 |
| Across the fall, draining down a column: ripple down the columns / along the rows | kept 1.0000 (law 1.0000) / 0.9056 (law 0.9061) |
| Across the fall, draining along a row: ripple down the columns / along the rows | 0.6682 (0.6742) / 1.0000 (1.0000) |
| Undrained share 0.25, ripple down the columns | 0.9046 (0.9061) |

### 2. The figures

Measured with one probe on every run, so stock and B were re-taken beside the new forms.
- Stock and B reproduce last round's comb, network and relief figures to the digit.
- The same-cell table differs from last round's by under a point, because the probe was rewritten.
- The 1024 worlds are the 512 configuration at `atResolution`, as `ScaleFreeTest` builds them.
- The clauses are the tests' own arithmetic, run with each form's default switched on in a scratch
  edit that was never committed.

| | Stock | B (`K dx dy`) | Across the fall | Undrained share |
|---|---|---|---|---|
| **Comb, down a column / along a row, km per 1,000 km²:** seed 7 | 0.408 / 0.061 | 0.077 / 0.000 | 0.102 / 0.002 | 0.322 / 0.014 |
| seed 42 | 0.519 / 0.079 | 0.110 / 0.002 | 0.109 / 0.000 | 0.423 / 0.037 |
| column comb at 1024, seeds 7 / 42 | 0.442 / 0.482 | 0.158 / 0.180 | 0.189 / 0.177 | 0.418 / 0.496 |
| change from stock, 512 / 1024 | | −81, −79 / −64, −63% | −75, −79 / −57, −63% | −21, −18 / −5, +3% |
| **Network**, km per 1,000 km², seeds 7 / 42 (guard's floor 23.4 / 27.1) | 31.64 / 36.61 | 20.77 / 25.34, fails | 24.45 / 29.24 | 30.15 / 34.29 |
| **Network's growth** 512 → 1024 (`ScaleFreeTest`'s density), seeds 7 / 42 | 1.54 / 1.51 | 1.36 / 1.32 | 1.48 / 1.42 | 1.52 / 1.48 |
| **`CombGuardTest`**, with the new floor | fails: comb | fails: network, and comb | fails: comb | fails: comb |
| **Valley notch**, depth and times the bare ground (bars 0.0133 and 1.9) | 0.0244, 4.89× | 0.0108, 2.43×, **fails** | 0.0159, 3.24× | 0.0230, 4.62× |
| notch by step: row / column / diagonal, seed 7 | 0.0243 / 0.0255 / 0.0242 | 0.0098 / 0.0122 / 0.0085 | 0.0139 / 0.0170 / 0.0143 | 0.0213 / 0.0237 / 0.0235 |
| **Belt's flank**, pooled m (main 113) | 275 | 162 | 208 | 247 |
| **Ranges' texture**, highest quarter, pooled m (bar 103.8) | 185.9 | 97.3, **under** | 125.6 | 166.5 |
| **Plains' texture**, lowest quarter, pooled m (record 68.6) | 100.9, recorded | 60.6, passes | 77.4, recorded | 89.1, recorded |
| **Dissection contrast** (armed clause) | passes | passes | passes | passes |
| its B-I2 pin, seed 7 fed | 0.035 | 0.016 | 0.017 | 0.030 |
| **Wet flank** | passes | passes | passes | passes |
| **Mean step along a row**, m, seeds 7 / 42 | 223 / 263 | 120 / 148 | 147 / 179 | 201 / 238 |
| **Lake bars, production:** bar cells of lake cells (share), seed 7 | 128 of 904 (0.141) | 20 of 610 (0.033) | 45 of 754 (0.060) | 57 of 777 (0.073) |
| seed 42 | 117 of 1,147 (0.102) | 6 of 1,288 (0.005) | 33 of 1,528 (0.022) | 155 of 1,977 (0.079) |
| **Lake bars, notch and ice off**, seed 7 | 139 of 971 (0.143) | 0 of 449 (0) | 18 of 652 (0.027) | 76 of 955 (0.080) |
| seed 42 | 220 of 2,343 (0.094) | 11 of 961 (0.011) | 70 of 1,560 (0.045) | 169 of 2,225 (0.076) |
| **Deltas:** sea-lobe cells, seeds 7 / 42 | 2,065 / 2,548 | 2,153 / 2,601 | 2,168 / 2,620 | 1,877 / 2,520 |
| lake-fan cells | 4,556 / 5,717 | 4,751 / 5,938 | 4,819 / 6,291 | 4,761 / 6,042 |

**How some rows were read.**
- **The ranges' texture** is not asserted when the plains' known failure throws first. The figure
  is what the clause computes. B's 97.3 would fail it, which last round did not report.
- **The dissection contrast.** Its armed clause, the fed figure against the flat control, passes on
  all four. Only the recorded pin's figures move.
- **The wet flank** reads the first round's law rate before any sub-grid transport has acted, so its
  figures are identical on all four runs. It cannot tell the forms apart.
- **The lake bars** are `GlaciationTest`'s `combShare`: thin grid-bearing bars with a parallel
  partner, as a share of the standing water.

**The same-cell feedback.** The steepest third of the terrain before erosion, routed by production's
router, read again on the finished drainage:

| Run | Column share, before → after | Diagonal → column | Row → column | Column → column |
|---|---|---|---|---|
| Stock, seed 7 / 42 | 41.1 → 56.8% / 45.1 → 57.8% | 38.8 / 38.6% | 48.3 / 47.5% | 78.0 / 77.6% |
| B | 41.1 → 39.0% / 45.2 → 43.5% | 20.1 / 24.0% | 25.0 / 29.3% | 63.8 / 65.1% |
| Across the fall | 41.2 → 39.2% / 45.1 → 42.5% | 19.1 / 21.7% | 21.5 / 24.6% | 67.0 / 66.7% |
| Undrained share | 41.2 → 46.7% / 45.1 → 48.6% | 27.8 / 29.0% | 40.9 / 40.4% | 67.6 / 68.0% |

Across the fall stops the feedback as fully as B, with less of the network lost. The undrained share
takes out about a quarter of it.

### 3. The comb guard's floor

`CombGuardTest` now passes a column comb up to **0.01 km per 1,000 km²**, whatever the rows carry.
- **Where the figure comes from.** The control recorded in the guard's own KDoc: on the isotropic
  synthetic surface, the router's parallel reaches carry 0.00 to 0.01 with a ridge of even 50 m
  between them, half the guard's ridge. No candidate's figure entered it.
- **What it changes.** On this head the guard records exactly what it did. Under B the comb is still
  judged, and fails at 0.077 against 0.01, not against a row of nought.
- **So the floor rescues nothing.** B carries 8 to 18 times it, across the fall 10 to 19 times, and
  the undrained share 32 to 50 times.

### 4. The crops

Under `review/renders/`: `comb-<seed>-<size>-<atlas|elevation>-<across|share>.png`, beside last
round's stock, A and B.
- **The windows:** seed 7 at 1024, sheet pixels 1040-1360 by 440-660; 969495 at 2048, 2096-2356 by
  820-1080.
- **The worlds:** the plain 1024 and 2048 configurations, as last round's crops were. This round's
  stock crop of seed 7 matches last round's to the eye.

What the eye sees:
- **Seed 7, across the fall.** The range keeps its shape. The stripes on its south flank are fewer
  and broader than stock's, but still run down the columns. The lowland round the range keeps more
  of its fine dissection than under B.
- **Seed 7, undrained share.** It is hard to tell from stock: the flanks are still densely striped.
- **Both forms on seed 7** leave a lake north of the range that stock drains.
- **969495, across the fall.** The eastern flank is B's picture with a little more relief: fewer,
  broader parallel valleys running to the coast, still parallel.
- **969495, undrained share.** Stock's dense fine comb.

### 5. Recommendation

- **Drop the undrained share.** It is principled and cheap, and keeps everything the branch armed,
  but it does not do the job: 20% off the comb at 512 and none at 1024. By construction it is
  nought on every cell whose descent is clamped to one neighbour, as an incised gully's is. That is
  the likely reason it leaves the comb, but I did not measure it.
- **Across the fall is the better-founded of the three sub-grid forms, and better than B on every
  count but one.**
  - Against B it keeps a sixth more network and half again the valley depth, and restores the
    ranges' texture.
  - It removes nearly as much comb at 512 and slightly less at 1024.
  - It stops the feedback as fully, and keeps the lake bars down to 0.02 to 0.06.
  - It passes every dissection clause the branch armed, and the guard's network hold.
- **But it does not fix the comb, and it costs the valleys a third of their depth.**
  - The comb left, 0.10 to 0.19 km per 1,000 km², is ten to nineteen times the control's floor.
  - The notch is 0.0159 against stock's 0.0244. That passes its bar, which was set on the tree
    before S2, but it is a real loss.
  - Seed 7's network is 4% over its floor at 512. At 1024 it is 1.35 times thinner than stock's
    there, right at the tolerance.
  - The network's growth with the grid barely improves (1.42 to 1.48).
- **What this says about the approach.** B, the whole `K dx dy` with the double count in, takes
  out 63 to 81% of the comb, and a form net of the double count can only take out less. So a
  sub-grid transport at the scale the law derives does not remove the comb at this grid, whichever
  net form is used. What survives, straight column gullies with ridges between, is not isolated.
  Candidates, none tested:
  - the Rho8 draw on the steep cells, where a clamped descent is always the steepest neighbour;
  - the scheme's first-order smear along a column.
- **If the maintainer wants a setting on now,** across the fall is the one whose derivation stands
  and whose costs are measured above. It is not a comb fix, and turning it on would re-record the
  plains' texture (77.4) and the comb guard's figures, and move the renders.

### 6. Tests this round

One Gradle build at a time. Only what the round touches:

| Test | Result |
|---|---|
| `SubGridTransportTest` | 4 of 4 pass |
| `WorldFingerprintTest` | 3 of 3 pass: the defaults move no bit |
| `CombGuardTest` on the head, with the floor | known failure recorded, figures unchanged |
| `ValleyIncisionTest`, `GroundTextureTest`, `ClimateFedErosionTest`'s wet flank and dissection clauses, `CombGuardTest` | on stock: all pass, known failures recorded |
| The same, in scratch builds with each form's default on (never committed) | as in section 2: B red on the notch, the network hold, the plains' "arm this" and the pin's new figures; across the fall and the undrained share red only on the comb, the plains' and the pin's recorded figures |

No full suite ran and `origin/main` was not merged. The probes (`SeedProbeCombRound2` in
`:worldgen`, `SeedProbeCombCrops` in `:desktop`) are ignored files and are not committed.

## Comb experiments, third round

**Branch:** `chunk/3b-implicit-erosion` at `34cd797`. Not merged, `origin/main` not merged in, no pull
request, no full suites, no render set. At 512 on seeds 7 and 42 throughout.

| Commit | What it does |
|---|---|
| `fa37334` | Form C, the draw in clamped descent, behind `WorldGenConfig.clampedDescentDraw`, off by default; the invariant case in `RoutingGroundTest`; the setting in every routing stage, the reuse guards and `IncrementalReuseTest`. |
| `34cd797` | The ledger records the round. |

**The answer, plainly: no.** C does not remove the comb, alone or with across the fall, and cannot:
the comb's cells are ones the draw leaves where they are by construction. By the brief, this is where
the candidates stop. The next step is the maintainer's choice: the square-cell grid, or accepting
across the fall.

### 1. The census first: what kind of descent the comb's cells have

`CombCensus`'s own arithmetic, on stock worlds, with each cell's descent read from the production
router:
- **clamped to the cardinal:** an undrained share of nought and a cardinal receiver;
- **clamped to the diagonal:** a share of nought and a diagonal receiver;
- **drawn inside the facet:** a share above nought.

The recomputed routing agrees with the world's drainage on 99.9 and 100% of the combed cells.

| Seed 7 / 42 | Cells | Clamped to the cardinal | Clamped to the diagonal | Drawn inside the facet |
|---|---|---|---|---|
| Combed, down a column | 959 / 1,214 | 86.9 / 84.8% | 0.0 / 0.0% | 13.1 / 15.2% |
| Sustained straight reaches, down a column | 6,381 / 7,654 | 79.5 / 78.2% | 0.1 / 0.1% | 20.4 / 21.7% |
| Combed, along a row | 72 / 92 | 90.3 / 92.4% | 5.6 / 0.0% | 4.2 / 7.6% |
| Every channel cell | 51,377 / 59,522 | 60.0 / 58.2% clamped, either edge | | |

So most of the comb's cells are clamped to their cardinal. None is clamped to a diagonal. The rest
are already drawn by the facet rule.

### 2. Form C as built, and why it cannot reach those cells

- **The rule.** Where a cell's descent is clamped to one edge of its steepest facet, its receiver is
  the neighbour with the steepest fall under Fairfield and Leymarie's Rho8 (1991, *Water Resources
  Research* 27(5), 709-717):
  - a cardinal's fall over its own step;
  - a diagonal's over a length drawn per cell from the same `subGridDraw` hash as the facet draw.
- **The drawn length, adapted to this cell.**
  - Their `rho = 1 / (2 - r)` draws the diagonal's length uniformly between one side and two. Those
    are the triangle inequality's bounds on a diagonal: no shorter than its longer leg, no longer
    than both legs end to end.
  - On a cell half as tall as wide that is `longer + (1 - r) * shorter`: one to one and a half cell
    widths.
  - The mean reciprocal is `2 ln 1.5 = 0.811` against the true `0.894`, 9% short, where theirs on
    the square is 2% short.
- **Only on clamped cells, not every cell.**
  - Inside a facet, the facet rule already draws, and it is unbiased on planes.
  - Rho8 on every cell would replace that with the 9%-short diagonal, a bias toward the cardinals.
- **Why it cannot reach the comb.**
  - A cell clamped to its cardinal has both flanking diagonals falling no more than the cardinal.
  - Each diagonal is drawn at least as long as its longer leg, which is at least the cardinal's step.
  - So under every draw neither diagonal is steeper, and the cardinal is kept.
  - Only a diagonal beyond another cardinal could win, and on ground falling toward the kept
    cardinal those rise.
  - So C acts only on cells clamped to a diagonal. The census puts none of the comb there.
- **Invariants.** `RoutingGroundTest` checks them with the draw on, over rough ground cut by gullies
  down the columns and on the diagonals: 83 receivers changed, none standing no lower on the filled
  surface, and no cycle. `WorldFingerprintTest` is unchanged. `IncrementalReuseTest` passes with the
  new variant.

### 3. The router's own expectation with C on

Production's router, interior cells of 160-cell planes on seeds 7, 42 and 1234. Bearings are on
the ground, from east toward south.

| Plane | Draw | Column | Row | Diagonal | Mean bearing (true) |
|---|---|---|---|---|---|
| Due north-south, the comb's bearing | off / on | 100.00 / 100.00% | 0 / 0 | 0 / 0 | 90.00 / 90.00 (90) |
| 22.5° off it | off / on | 79.28 / 79.28% | 0 / 0 | 20.72 / 20.72% | 67.49 / 67.49 (67.5) |
| 45° off it | off / on | 50.38 / 50.38% | 0 / 0 | 49.62 / 49.62% | 45.22 / 45.22 (45) |
| The diagonal's own bearing, 63.4° off it | off / on | 0 / 0 | 0 / 49.29% | 100 / 50.71% | 26.57 / **14.23** (26.57) |
| 360 bearings, pooled | off / on | 44.77 / 44.77% | 15.25 / 15.25% | 39.98 / 39.98% | |
| Isotropic synthetic surface (64 waves), three seeds | off → on | 42.2–43.9%, unchanged to 0.02 points | 16.7–16.8 → 18.6–18.7% | 39.3–41.0 → 37.5–39.1% | within 0.4° |

- **Residuals.** On the three bearings asked for, and on the pooled planes, the residual with C on is
  the facet rule's own: 0.00 to 0.22 degrees. The draw acts on none of those planes.
- **The diagonal's bearing.** This is the one plane whose descent is clamped to a diagonal. There C
  sends half the cells along the row and turns the mean bearing **12.3 degrees** toward it. So C does
  bias a plane's mean bearing, at exactly the bearing where it acts.
- **On isotropic ground** it moves about 1.8% of receivers, all from diagonals to rows. None goes to
  or from a column.

### 4. The figures (the census said the comb is out of reach, so the trimmed set)

Stock, B and across the fall are round 2's figures, not re-run.

| | Stock | Across the fall | C | C with across the fall |
|---|---|---|---|---|
| Column comb / row comb, seed 7 | 0.408 / 0.061 | 0.102 / 0.002 | 0.413 / 0.071 | 0.111 / 0.002 |
| seed 42 | 0.519 / 0.079 | 0.109 / 0.000 | 0.519 / 0.085 | 0.111 / 0.002 |
| column comb against its comparison run | | | +1%, 0% on stock | +9%, +2% on across the fall |
| Network, km per 1,000 km², seeds 7 / 42 | 31.64 / 36.61 | 24.45 / 29.24 | 31.54 / 36.49 | 24.43 / 29.51 |
| `CombGuardTest` | fails: comb | fails: comb | fails: comb | fails: comb |
| Valley notch, depth and times the bare ground | 0.0244, 4.89× | 0.0159, 3.24× | 0.0243, 4.91× | 0.0159, 3.24× |
| Same-cell column share, before → after, seed 7 | 41.1 → 56.8% | 41.2 → 39.2% | 41.1 → 56.7% | 41.1 → 38.9% |
| seed 42 | 45.1 → 57.8% | 45.1 → 42.5% | 45.1 → 57.8% | 45.1 → 42.2% |
| Diagonal → column, seeds 7 / 42 | 38.8 / 38.6% | 19.1 / 21.7% | 38.6 / 38.7% | 18.9 / 21.5% |
| Row → column | 48.3 / 47.5% | 21.5 / 24.6% | 47.6 / 47.6% | 22.1 / 24.6% |
| Column → column | 78.0 / 77.6% | 67.0 / 66.7% | 78.2 / 77.6% | 66.0 / 66.0% |

**What was skipped.** Neither run moved the comb by a quarter against its comparison run, so the
following were not taken, per the trimmed brief:
- 1024;
- the other dissection clauses, the plains' texture, the lake bars and the deltas;
- the crops.

So there are no crops of C, and nothing is said here about its drawn rivers under rule 13.

### 5. Recommendation

- **Drop C.** By its own derivation, the census and the measurement, it does not reach the comb.
  - Alone it leaves the comb, the network, the notch and the feedback as stock has them, within 1%.
  - With across the fall it leaves that form's figures within 9%.
  - It adds a 12-degree bias on planes at the diagonal's bearing that the router does not have now.
- **Neither run removes the comb to the guard's floor or near it.** C alone keeps the valleys and the
  network, and the comb. C with across the fall is across the fall again: three quarters of the comb
  gone, the valleys a third shallower, the network a fifth thinner.
- **The candidates stop here, as the brief says.** The choice is the maintainer's: the square-cell
  grid, or accepting across the fall.

### 6. Tests this round

| Test | Result |
|---|---|
| `RoutingGroundTest`, whole, with the new invariant case | 5 of 5 pass |
| `WorldFingerprintTest` | 3 of 3 pass |
| `IncrementalReuseTest`, with the new variant | 5 of 5 pass |
| `ValleyIncisionTest` in scratch builds with C, and with C and across the fall (never committed) | pass, figures in section 4 |

The probes (`SeedProbeRho8Router`, `SeedProbeCombKinds`, and `SeedProbeCombRound2` with two more
variants) are ignored files and are not committed.
