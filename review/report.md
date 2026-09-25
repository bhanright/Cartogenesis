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
