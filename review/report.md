# Chunk 3: the erosion's units

**Branch:** `chunk/3-erosion-units` at `89f062f`, from `origin/main` at fb22d30. Not merged, and no
pull request. Chunk 6's six files (RiverStage, LakeWaterBalance, BasinPartition, BasinRealms,
NationStage, CultureStage) were not edited.

| Commit | What it does |
|---|---|
| `a801da5` | The WIP commit that opened the chunk. |
| `6edfca6` | Two watchers, off by default: `IncisionWatch` and `FlatRouting.surfaceWatch`. The world digests of seeds 42 and 7 at 512 match fb22d30 in every branch the erosion reaches. |
| `853aa36` | Finding 2, step one: the clock's labels. |
| `1ca026c` | Finding 1: the incision's caps in one unit. |
| `c8b4882` | Finding 3: the outlet notch starts at the lip. |
| `ee045c2` | Finding 4: the flat's floor. |
| `49b0568` | The clauses the new terrain moves: recorded, re-recorded or armed. The render records re-taken. |
| `283f139` | The ledger row, and corrections to README, GEOGRAPHY and TODO. |
| `5728839` | The map's own clauses the new terrain moves (`:cartography`): the 512 geometry census re-recorded from its own record lines, Pen and ink's figure, and the shading's haze (0.12 against 0.10) and ordinary ground (0.9489 against 0.9225) recorded, not re-derived. |
| `89f062f` | The ledger and TODO name those clauses. |

**Step two is not on the branch.** The maintainer decided the next chunk replaces the explicit
update with an implicit solver, so step two's measured figures are reported below as findings.

## The headline

Each of the four defects is fixed and guarded, and each guard was shown failing on the old code.

Fixing finding 1 also exposed what the audit's figures already hinted at. Once the caps are spent in
the right unit, **the cap at half the drop sets 100% of the cuts on the drawn river network, on
every world measured.** The honest cap is three eighths of what was being spent, so it binds more,
not less.

The consequences are visible to the eye and to many guards:
- The valleys are shallower and the ranges smoother.
- The coast's box-counting dimension falls below Mandelbrot's band.
- The lowstand drowns fewer valleys.

Every clause that crossed its bar runs as a recorded known failure under a new finding. None was
loosened. The implicit update is set out at the end.

## Finding 1 (B-D1): the caps in one unit

**What changed.** In `HydraulicErosion.cut`:
- The half-the-drop cap is `drop * 0.5 * landRange`, converted once.
- The shoreline cap is read off the height field (`surface - shorelineHeight`), so a cut to it lands
  exactly on the shoreline.
- The drop to a sea receiver is measured to the shoreline (`fallToReceiver`), in the incision and in
  the deposition walk.
- The law's own term is not converted, because the coefficient already carries the ratio of the two
  rulers.
- One thing the audit did not name, found on the way: inside the rounds, the outlet notch's falling
  surface below the lip also ran a few centimetres past the shoreline (0.1% of drawn mouths, up to
  0.2 m). It now stops there, as its KDoc says it does.

**Before and after.** Measured with a probe that watches every round (not committed). A mouth is a
land cell whose receiver is sea. The drawn network is the cells carrying at least `DRAWN_RIVER`
(0.0006) of the land's water. A cell is cap-bound when the half-the-drop cap is the smallest of the
three terms.

| World | Mouth cuts below the shoreline, before | Drawn mouths below, before | Depth below, before (median / max) | Cap-bound share of drawn network, before | After |
|---|---|---|---|---|---|
| 7 @ 512 | 57.3% | 100% | 16.1 / 980 m | 95.0% | none below; cap-bound 100.0% |
| 42 @ 512 | 57.3% | 100% | 28.0 / 908 m | 95.5% | none below; cap-bound 100.0% |
| 1234 @ 512 | 57.7% | 100% | 14.4 / 869 m | 95.3% | none below; cap-bound 100.0% |
| 42 @ 1024 | 54.9% | 100% | 17.0 / 510 m | 99.3% | none below; cap-bound 100.0% |
| 969495 @ 2048 | 56.2% | 100% | 9.6 / 266 m | 99.5% | none below; cap-bound 100.0% |

Per round at 2048, 42 to 67% of mouth cuts ended below the shoreline. The drawn-mouth figure is 100%
in every round of every world. These agree with the audit's figures for this tree: the cap-bound
share is 95% at 512 and 99.5% at 2048, against the audit's 86 to 93% and 100%.

**Guards** (`ErosionUnitsTest`, which watches production's ordered pass through `IncisionWatch`):
- **(a) Every mouth.** No land cell draining into the sea ends a round below the shoreline. The
  guard first asserts that it saw a positive number of mouth cuts. Seed 42 at 512: 22,721 of 39,631
  mouth cuts ended below on the old code; none now.
- **(b) Half the drop.** No cut asks for more than half its drop in the field's unit, read before
  the receiver clamp. The guard first asserts a positive number of cap-set cells. 472,659 cuts were
  over on the old code; none now.

Both were shown failing by running them on the tree at `853aa36`, which is fb22d30 plus the watcher
and the relabelled clock.

**`ReceiverClampTest`.** As its KDoc predicted, the incision makes no hole without the clamp now: 0
on all three seeds. The control is restated on the clamp's other half: it refuses to deepen a basin
floor (a cell already below its receiver). Without the clamp, more channel cells end under water,
pooled over the three seeds. The census with the clamp on is still asserted.

**The two known failures named for B-D1 are not resolved.** Both describe the cap per step: a
north-south step is half a cell width, so where the cap sets the cut a north-south channel falls
half as far a round. The cap is still per step, and it now binds on every drawn channel. Both stay
recorded, with new figures:
- **`GroundIsotropyTest`, the coast's projection ratio:** 1.12 pooled, where it was 1.39, and still
  over on seed 1234 at 1.19. The ratio moved a lot. Whether that came from the mouths no longer
  being cut below the sea or from the cap binding everywhere was not isolated.
- **`ValleyIncisionTest`, the notch depth:** 0.0077 of the field, against 0.0127 before and a bar of
  0.0148 × 0.9.

A third clause joins them under the same finding: `GroundIsotropyTest`'s steep-ground facing on
seed 99, 18.0 m/km down a column against 14.9 along a row. That the cap causes it is my reading, not
a measurement.

## Finding 2 (B-F1): the clock

**Step one (labels only), `853aa36`.**
- The clock is 336,476.4 years a round, and `highestLandMetres / reliefSpanMetres` is back in
  `Rates.incisionCoefficient`. The coefficient comes out as the same float at all 1,001 sea levels
  tried.
- The four uplift rates are multiplied by 126,178.65 / 336,476.4 (3/8):
  - collision 0.775 → 0.290625;
  - Andean 0.310 → 0.11625;
  - island arc 0.109 → 0.040875;
  - rift shoulder 0.047 → 0.017625 mm a year.
- `IsostasyTest`'s synthetic belt was restated the same way.
- `UnitsTest`'s "constant with no unit" control now reads the land-unit coefficient
  (`relativeIncisionCoefficient`). That one still reads no vertical ruler; the field's coefficient
  now does, and should.

**Step one is not bit-identical, and I checked rather than assumed.** A restated rate times the
restated clock rounds differently in the last place from the old pair, in about 35 to 75% of
products. On seed 42 at 512 the eroded height moved at 103,465 cells:
- the median change is 1 mm and the 99th percentile 16 mm;
- 57 cells moved by more than a metre, the most 36.5 m;
- eight cells changed side of the shoreline.

Seed 7 has five cells past a metre and none changing side. `plates.height` is unchanged; the
digests show `erosion.height` and everything downstream moving. This is a floor of float noise, not
a change of physics, but it is not zero.

**Guard.**
- **Setup:** a plane of land 32 cells wide falling 1 m/km due west, with the cover at one, flat
  rain, the notch, deposition, uplift and flexure off, and the relaxation the identity.
- **Check:** one production round removes K·T·√A·S at catchments of 1, 2 and 3 cells, below the
  cap.
- **Result:** 22.30 m against the law's 22.30 m now; 2.667 times the law on the old clock.

**Step two, measured and not committed** (at the maintainer's decision). On the honest clock,
with fixes 1, 3 and 4 in and the uplift off:
- the belts lose **0.074 mm a year**: 0.069, 0.091, 0.059, 0.075 and 0.074 on seeds 7, 42, 1234, 99
  and 718106;
- Earth's 0.5 plus that gives **collision 0.574**, and by the existing ratios **Andean 0.230, island
  arc 0.081, rift shoulder 0.035**.

That denudation is the cap's and not the law's, so a rate derived from it would have to be derived
again once the law governs. `IsostasyTest`'s derivation clause therefore runs as a known failure
(`RATE_WAITS_FOR_THE_LAW`), reading 0.57 implied against 0.29.

**The uplift figures, before and after:**

| | Clock (years a round) | Twelve rounds | Collision | Andean | Arc | Rift | Uplift a round at 1 mm/yr |
|---|---|---|---|---|---|---|---|
| fb22d30 (labels 2.67× off) | 126,178.65 | 1.51 My | 0.775 | 0.310 | 0.109 | 0.047 | 126.2 m |
| This branch (step one) | 336,476.4 | 4.04 My | 0.2906 | 0.1163 | 0.0409 | 0.0176 | 336.5 m (same metres a round per belt) |
| Step two (not committed) | 336,476.4 | 4.04 My | 0.574 | 0.230 | 0.081 | 0.035 | — |

## Finding 3 (B-D3): the notch at the lip

**What changed.**
- `FlowRouting.Spillways.spill` is now the basin's pour point: the first cell on the outflow's path,
  walked from the old exit, that the fill did not raise. The old exit is kept as `Spillways.entry`.
- The breach starts at the lip.
- **The power and the catchment moved with the start, deliberately.** The outflow's power is
  measured over the lip's catchment, which is the water crossing the sill, and over the channel below
  the lip. Measured from the old exit, the level margin diluted the slope.
- The same falling surface is carried back across the shelving margin, so a round deeper than the
  margin lowers the whole sill rather than leaving the margin as a new one.
- The drowned-basin pass in `SeaLevelStage` continues its backward walk from the entry. It used to
  start from the spill.

**Guard.** A basin with a 30 m core, an 18 to 20 m margin shelving from the rim, and an outlet graded
so one round's power is 10.0 m, built through production's fill, routing and spill selection. The
test first asserts that the old exit lies in the margin (column 32). The notch is run on its own
(`HydraulicErosion.breach`), which isolates it from ordinary incision. Result: the lip was lowered
0.00 m on the old code and 9.37 m now.

**`OutletIncisionTest`'s fill case** now asserts the depth it is named for: the fill's depth over the
land, a new `RoundMass.fillDepthOverLandMetres`. It is 0.074 of the control's pooled at the last
round; the largest basin's area (0.239) is still printed. The notch's three clauses recorded since
Fix 2 now pass and are armed.

## Finding 4 (Astra 2.4): the flat's floor

**What changed.** `FlatRouting.layInBand` lays the band between the highest entry's own height and
the flat's lowest raised level, not from that level less the nominal step. With a converged
potential, every member then has a strictly lower neighbour, so the potential can no longer be
refused. The class's KDoc claim is now true.

**Guards.**
- **A trench flat.** 107 cells down one column, its entry at 1.5 in the relative field, where a
  float's step is 8.39 last places and the flood's addition rounds down. The test asserts that
  premise first. The column-run preconditioner is the whole matrix, so the solve is exact in one
  step. Result: the flat kept its staircase on the old code and is laid now.
- **flatsKept over every routing pass at 2048** (`FlatCourseAuditTest`, audit tier, 33 passes per
  world):

  | Seed at 2048 | Flats kept, before | After |
  |---|---|---|
  | 718106 | 179 | 0 |
  | 59758 | 102 | 0 |

On the final head the audit case read 33 routing passes and 97,395 flats on 718106, and 29 passes
and 86,034 flats on 59758, with none kept on either. The counts differ from the old tree's 1.1 and
1.0 million flats because the terrain moved.

The flat potential's cost clause (seed 7 at 512) now reads 1.00% of a generation, under rule 8's
line, and is armed. It sits on the line, so a loaded machine could put it over.

## What the new terrain moved

Every clause that crossed its bar with the new terrain is recorded, not loosened. Most go under a
new finding, `CAP_SETS_EVERY_CUT`. The ones whose bar is an Earth figure keep the Earth figure,
because the world moved, not Earth.

- **Recorded under the cap finding:**
  - the valley notch, 1.68× the bare ground against its 1.9 pin;
  - the coast's box-counting dimension, 1.028 pooled and 1.036 in the Earth-likeness suite, under
    Mandelbrot's 1.25 ± 0.15;
  - the littoral pass's smoothing gain, 0.740 against 0.596;
  - the lowstand's estuaries: seed 7 has 10 against 31 with the sea held at today's level, pooled
    0.84×;
  - the ground's texture, highest quarter 68.5 m against 103.8 m recorded, and a belt's flank 101 m
    against 113;
  - the rain's dissection contrast on seeds 42 and 1234;
  - the drylands' drainage density on seed 99.
- **Re-recorded with new figures:**
  - the valley notch's depth (0.0077);
  - the rain-dissection pin (seeds 7, 1234 and 99 under it);
  - the wet flank (1.12 under 1.49);
  - the rainfall calibration;
  - the cold-current coast;
  - the tropics' desert (×1.09);
  - the glacial lakes;
  - the ice sheet's edge and thickness;
  - the foreland (242 m);
  - the shelf's jump flood;
  - the recycling ratio (0.279);
  - the largest realm (seed 7 40.5%, seed 1234 36.5%).
- **Armed:** the ice bed under Airy's share, the notch's three clauses, seed 298405's coast by ruler,
  and the flat potential's cost.
- **`ClimateFedErosionTest`'s cover guard.** Its own copy of the caps used the old mixed units. It
  now models the caps in production's units and passes on 52,000 to 57,000 cells a seed.
- **`WaterlineBasinTest`.** The census finds no basin at the waterline on its four seeds since the
  mouths stopped being cut below the sea. It now asserts that it sorted the basins below the cut:
  1 to 3 per seed.

## What the renders show

Seeds 7 and 42 at 1024 and 969495 at 2048, in the Atlas view and as elevation, whole and cropped.
The before images are from `origin/main` at fb22d30. The after images are from the tree with fixes
1, 3 and 4 and step one, before the final re-record commits, which changed no generator code.
The crop is the window holding the most drawn river mouths on the before world, and the same window
is used after. Files are under `review/renders/`, named `<seed>-<size>-<view>-<whole|crop>-<before|after>.png`.

- **At the mouths.**
  - Before, every drawn mouth flared into a funnel inlet cut below the sea. Seed 7's central river
    opened into a widening estuary and its north coast carried a fringe of short notches.
  - After, the rivers meet the sea without the funnels, and the fringe is gone.
  - On 969495 the delta lobes were smooth rounded discs before. After, several carry distributary
    fingers that look like the grooves the lobe pass cuts. I have not measured why they now show.
  - The comb of short spikes along 969495's east coast is much reduced.
- **Along the trunks.** The courses are the same rivers in the same valleys. Mouths are narrower,
  and some small trunk lakes are gone.
- **Across the land, the cost.** The ranges are smoother. On seed 42 the whole-map relief goes from
  strongly dissected, with combed valleys everywhere, to broad, pillowy masses. Most of the fine
  dissection is gone and there are fewer lakes. This is what the cap setting every cut produces: a
  round cuts half the drop and no more, where the law asks for 65 to 97% of it on the trunks.

## The implicit update (Braun and Willett), for the decision

Not built; set out for the decision. Figures below marked *measured* come from this chunk's runs; the
rest is arithmetic on the code as it stands, and is labelled as such.

### Why the cap sets every cut

The explicit update cuts `E = F * drop` in a round, where `F = K * T * sqrt(A) / L` is the
round's Courant number: the stream-power law's cut over the drop to the receiver, `L` the step on the
ground. On this map `F` is about `0.24 * sqrt(A in cells)` at every grid (K = 1e-6, T = 336,476
years, a cell's area and width both scaling with the grid). The cap at half the drop binds where
`F > 0.5`, a catchment of about four cells. The drawn network starts at 0.0006 of the land, about
60 cells at 512 and 1,000 at 2048, where `F` is 1.9 and 7.5; a trunk carrying 1% of the land has
`F` about 7.5 at 512 and 30 at 2048. So on the drawn network the explicit scheme is always past its
own stability limit, and the cap is what keeps it bounded. *Measured*: 100.0% of drawn-network cuts
are cap-set after the unit fix, on all five worlds.

### What changes in the ordered pass

`HydraulicErosion`'s ordered incision pass already walks the D8 forest from the outlets upstream,
`FlowRouting.drainageOrder` read backwards, so every receiver is final before its donors. That is
exactly the order Braun and Willett's solve needs, and it can be reused as it is. For `n = 1` each
cell's new height is closed-form in its receiver's new height:

    z_i' = (z_i + F_i * z_r') / (1 + F_i),   F_i = coefficient * sqrt(share_i) * erodibility_i * cellsAcross / L_i
    (in the field's unit: the coefficient already carries highestLandMetres / reliefSpanMetres)

One multiply-add and one division per cell in place of today's three-way `minOf`. The pass stays a
single O(N) walk in the same order, with no new array (the receiver's new height is `surfaceOf[r]`,
already final).

What becomes of each limit:

- **The half-the-drop cap** goes. The implicit step never carries a cell past its receiver's new
  height, whatever `F` is, so the scheme is unconditionally stable and needs no limiter. The cut is
  `F / (1 + F)` of the drop to the receiver's new height: the law's `F * drop` where `F` is small,
  and nearly the whole drop where it is large.
- **The receiver clamp** is kept, for one case only. For a cell above its receiver the formula
  satisfies `z_i' >= z_r'` by construction, which is the clamp's bound. For a cell already below its
  receiver (the floor of a filled basin, where the routing runs on the fill) the formula would
  *raise* it toward the receiver, which is deposition by the back door. That cell has to keep
  today's rule, no cut.
- **The shoreline cap** folds into the formula. A mouth's receiver is sea, and taking `z_r'` as the
  round's shoreline height gives `z_i' = (z_i + F * shore) / (1 + F) >= shore`. The base level is the
  boundary condition, not a separate cap. The drop to a sea receiver is to the shoreline, as this
  chunk already measures it.
- **Deposition** is unchanged in structure. The ordered pass still records what it took in
  `incisedAt`, and the sources-first walk carries it as today. The totals move a lot: the drawn
  network is cut `F/(1+F)` of its drop (65 to 97%) where today it is cut 50%. So a round delivers
  up to about twice the load to the trunks and deltas, and the transport-capacity and delta-share
  calibrations were all set under the cap.
- **The outlet notch** is a separate mechanism and runs before the ordered pass. It does not need
  to change. Its power reads `relativeIncisionCoefficient * outletIncisionRatio` on the explicit
  form, and the ratio (1.125) was set against an ordinary cut that was capped. Whether a knickpoint
  should still cut 1.125 times an ordinary reach is the implicit chunk's question.

### The per-step anisotropy behind the two B-D1 known failures

Today the cut is half the drop on nearly every drawn cell, and the drop is proportional to the
step. A north-south step is half a cell width, so a north-south channel falls half as far a round
as an east-west one on the same slope. That is `GroundIsotropyTest`'s coast ratio and
`ValleyIncisionTest`'s shallower column-stepping notch.

Under the implicit update the per-round cut is `F/(1+F)` of the drop, and `F` doubles when `L`
halves. For a north-south step against an east-west step on the same slope, the ratio of cuts is
`(1 + F_ew) / (1 + 2 F_ew)`: about 0.92 at `F = 0.1`, 0.67 at 1, and 0.52 at 10. So per round the
anisotropy stays on the trunks, where `F` is large.

What changes is the distance a knickpoint retreats in a round. The implicit upwind scheme retreats
at the law's celerity `K sqrt(A)` in ground distance for any Courant number, so north-south and
east-west channels grade upstream equally fast. The cap limits retreat to half a step a round, half
as far north-south. My expectation, from the arithmetic and not measured, is that the finished
worlds lose most of the systematic north-south shortfall. What remains is the scheme's numerical
diffusion, which scales with the step and so smears a north-south profile over half the ground. The
two known failures should be re-measured on the implicit build, not assumed fixed.

### What `ClimateFedErosionTest`'s three failing clauses would read

All three compare the cut with and without the rain or the cover, and assume the law sets the cut.

- **The cover** (*measured* today: 24,438 of 77,152 "unclamped" cells out by more than 0.001 on
  seed 7, before the test's own cap model was put into production's units). Under the implicit
  update the realised cut responds to the erodibility factor `e` as `e(1+F)/(1+eF)`, not `e`. The
  per-cell clause would hold only where `F` is small, a few cells of catchment. It should be restated
  on the law's rate (`IncisionWatch.asked`'s stream-power term, which is `e` times the bare one
  exactly) or on the cells where `F` is under about 0.1.
- **Dissection follows the rainfall**: on the implicit update the fed-over-flat contrast is set by
  `F/(1+F)` as well. It grows where `F` is near one and saturates on the trunks. I would expect the
  contrast to be larger than under the cap, where it is zero on every capped cell, but not the
  law's full ratio.
- **The wet flank against the law**: the same compression. The measured ratio would sit between
  today's (1.12 on seed 1234, under the law's 1.49) and the law's, closer to the law on the gentle
  low-order flanks than on the trunks.

In short, all three clauses measure a proportional law, and the implicit update is proportional
only where `F` is small. Each wants restating on the law's rate, read through `IncisionWatch`, not
the realised cut.

### Cost per round at 2048

The ordered pass is one walk over the land's cells, about 1.6 million at 2048 on a 38% land world.
The implicit form costs the same memory traffic as today's pass plus a division. By arithmetic, not
measured, it is tens of milliseconds a round against the round's own several seconds, which are the
priority flood, the flat potential, the routing and the accumulation. *Measured*: seed 969495's
twelve rounds take 93 s at 2048 on this machine. The solve is not where the time goes. Where it adds
cost is downstream: a round delivers more load, so the deposition walk and the delta fans do more.

### A graphics-card path

The implicit solve is sequential along each receiver chain: a cell needs its receiver's new height.
Two ways to put it on the device:

1. **By level.** Group cells by their depth from the outlet, their distance down the receiver chain
   in cells, and solve one level per dispatch, every cell of a level in parallel. It is simple and
   exact, but the number of dispatches is the longest river in cells, a few thousand at 2048.
2. **By composing affine maps (pointer jumping).** Each cell's update is affine in its receiver's
   new height, `z_i' = a_i + b_i * z_r'` with `a_i = z_i / (1 + F_i)` and `b_i = F_i / (1 + F_i)`.
   Affine maps compose, so each pass replaces a cell's map with its composition with its receiver's
   and jumps the cell's pointer to the receiver's receiver. After `ceil(log2(depth))` passes every
   cell's map is expressed against its outlet, whose height is known. That is about 12 passes at
   2048, each an O(N) compute dispatch over two float arrays and one int array. It is the standard
   list-ranking technique, and it maps onto the compute kernels the thermal sweeps already use.
   Precision is the thing to watch. Products of the `b`s shrink toward zero along long chains, which
   is harmless. Sums of `a` terms along a chain of thousands accumulate float error, so the
   composition should be carried in the same float the CPU uses and the two paths compared cell for
   cell, as `GpuErosionTest` does for the sweeps.

The incision pass has no graphics-card kernel today (I searched and found none), so the explicit
update also breaks the per-cell rule. The implicit chunk would be adding the first one, not keeping
one in parity.

### What the clock guard and the other guards would need

`ErosionUnitsTest`'s clock guard reads `K T sqrt(A) S` on channels below the cap. Under the implicit
update one round removes `F/(1+F)` of the drop, so the guard's expectation becomes that formula, or
it keeps reading the law on channels with `F` well under 0.1. The two unit guards stay as they are:
no mouth ends below the shoreline, which becomes true by construction, and no cut exceeds half its
drop, which the implicit update does not promise. That second guard would be retired in favour of
"no cut exceeds the drop to the receiver's new height".

## The percentile quirk (not changed)

`SeaLevelStage.thresholdAtRank` finds the histogram bin where the cumulative count reaches the target
rank (`>=`). When the target is the bin's last cell, the index is clamped and the returned threshold
is the highest sea value, so every cell at that value counts as land.

Synthetic cases, run through `SeaLevelStage.percentileCut` at half sea:

| Cells | Land returned | Land expected |
|---|---|---|
| 0.1, 0.1, 0.9, 0.9 | 4 of 4 | 2 |
| 0.1, 0.2, 0.8, 0.9 | 3 of 4 | 2 |

The smallest fix is `>` in the bracket search. The bracket is then the bin holding the target rank's
own cell, and its value is returned. On the second case that gives 0.8 and 2 land. It moves every
world by the cells at the boundary, so it wants a fingerprint check of its own. It is in `TODO.md`.
The clock guard does not need it: its sea has distinct depths, and it reads the catchment as the
stage defines it, which is the configured land area over the land's actual count.

## Found and left alone

`NationStage` orders tied biomes by a `HashMap` keyed by an enum. Seed 7's `nations.nations` digest
differs between two JVM processes running the same code. It is being fixed on chunk 6.

## Tests

Counted from the JUnit XML under each module's `build/test-results`, on the final head
(`:worldgen:jvmTest` and `:ui:jvmTest` at `283f139`, whose generator and interface sources are the
head's; `:cartography:jvmTest` and `:desktop:test` at `89f062f`):

| Task | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| `:worldgen:jvmTest` | 259 | 0 | 0 | 0 |
| `:cartography:jvmTest` | 117 | 0 | 0 | 0 |
| `:ui:jvmTest` | 129 | 0 | 0 | 0 |
| `:desktop:test` | 104 | 0 | 0 | 19 |
| `:worldgen:audit`, `FlatCourseAuditTest` only | 2 | 0 | 0 | 0 |

- **Desktop's 19 skips** are the graphics-card tests, which need a display (`GpuRasterTest` 5,
  `GpuOceanTest` 5, `GpuErosionTest` 2, `GpuIceSheetTest` 1), and the benchmarks
  (`GpuExportBenchmarkTest` 4, `EngravedRasterBenchmarkTest` 2).
- **Known failures reported:** 27 lines in the generator's report, 23 in cartography's and 2 in
  desktop's.
- **One impossible red, rerun once.** On the first full run, `:desktop:test`'s `SeedFieldTest`
  ("a typed seed applies on Enter and on Go") timed out after a minute of coroutine test time while
  the generator suite ran beside it. It passed alone, and passed in the final desktop run.
- **Cartography needed a second run.** Its first full run on the final generator had three reds:
  Pen and ink's figure and the geometry census, both recorded clauses reading new figures, and the
  shading's haze. Commit `5728839` re-records or records them, and the counts above are from the run
  after it.

## Could not run here

- `:ui:wasmJsTest` and `:desktop:siteTest`: a Kotlin tooling download is refused by the network.
- The graphics-card tests: they skip without a display, and are counted as skips below.
- `:worldgen:wasmJsTest`: the same download (`kotlinWasmToolingSetup`, a 403 on a karma tarball)
  failed, so it did not run.
