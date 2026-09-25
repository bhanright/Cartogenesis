# Chunk 6: water and realms — report

## Where the work is

- **Branch:** `chunk/6-water-realms`, created from origin/main at `fb22d30`. It is not merged and no
  pull request is open.
- **Head:** `66d745d`, after the review round (see "Review round" at the end). The first build,
  which the sections below describe, was `acb9df9`.
- **Review branch:** `review/6-water-realms` is the head with `review/` on top: this report and the
  renders.

## Baseline: origin/main as it stood

`:worldgen:jvmTest` was run once on `fb22d30` before any change (`--no-daemon --console=plain
-PtestWorkers=2`):

- **254 tests, 0 failures, 0 errors, 0 skipped**, in 49 min 36 s.
- No red, so there was nothing to rerun.
- 23 known failures were reported by the `KnownFailures` helper, E-T10 among them.

## The findings

Each finding was checked against the code before it was changed. Line numbers were ignored and
every place was found by name.

**How the guards were shown failing on the old code.** Each new guard was run against origin/main's
own code in a separate checkout of `fb22d30`. Thin test-side adapters mapped the new signatures onto
the old ones: km² limits were turned back into cell counts, and the realm and culture partitions
were replayed line for line from origin/main. The adapters were kept in ignored `SeedProbe*` files
and never committed. Findings 1 and 1b have no old function to adapt to, so their guard was run on
the new code with the one fix switched off.

### 1. A closed basin's rain counted downstream

**Verified.** `RiverStage` accumulated `catchmentRainMm` once, before `findLakes` re-pointed any
basin, and solved the basins in cell-index order.

**Change.** The basins are now ordered upstream first, by where their last exit falls in
`FlowRouting.drainageOrder`. The moment the balance closes a basin, its catchment rain is subtracted
along the old path below every exit, before any lower basin is solved. The lakes are still numbered
in cell-index order.

**Guard.** `WaterReceivedTest`, "a closed basin upstream sends none of its rain to the basin below".
The fixture chains two basins: the upper one is a hot-desert playa on 6 mm of rain, closed with
positive catchment rain; the lower one is steppe. The test compares the lower basin's inflow
directly with the rain on the land whose routing reaches it.

- **Old behaviour:** run on the new code with only the subtraction switched off, which gives
  exactly the stale inflow the old code used. The lower basin got **83,328 mm-cells against 76,800
  reaching it**, over by exactly the playa's 6,528.
- **Now:** 76,800 against 76,800.

### 1b. Found while fixing 1: inflow read from one cell

**Verified** on the fixture. The inflow was the maximum of the field over the basin's cells, so a
basin whose filled flat drains out across a level rim, through several cells, was under-fed. The
first run of the fixture gave 8,500 against 76,800.

**Change.** The inflow is now summed over every cell where the basin's water leaves it and does not
come back.

**Effect on lakes** (seeds 7/42/1234/99 at 512 and 969495 at 2048, findings 1 and 1b together):

| | Before | After |
|---|---|---|
| Lakes | 19/24/4/16/32 | 19/24/4/17/32 |
| Closed lakes | 6/2/1/4/5 | 5/1/0/5/2 |
| Playa cells | 8/34/10/23/902 | 11/34/15/7/1,133 |
| Share of land under lakes (%) | 1.028/1.887/0.129/1.966/2.681 | 1.010/1.893/0.148/1.994/2.727 |

### 2. Flow stopped growing above 1,200 mm

**Verified.** `RiverStage.runoffWeight` was `0.05 + precipitation`, the 0..1 copy clamped at
`ClimateStage.REFERENCE_MM`, and `NationStage.drawableRiverFlow` summed the same weight.

**Change.** Both the river accumulation and the realm stage's threshold now use
`Runoff.annualWeightMm`, so they are in the same unit, millimetre-cells. `ClimateStage` is
untouched. `RiverStage.RUNOFF_FLOOR` and `runoffWeight` are gone.

- **The floor.** `Runoff.FLOOR_MM` stays at 60 mm and is now derived as UNEP's hyper-arid aridity
  index, 0.05, times a potential evaporation of 1,200 mm. That 1,200 mm is the climate's own
  normalisation scale, used here as an assumption.
- **The riverine threshold's floor** is restated as two cells' floor water (120 mm). The old
  `1e-4` was a fiftieth of one cell's own weight and never bound.
- **What changed in dry country.** The old weight added the floor to the rain; the new one floors
  it. A 100 mm cell weighed 160 against a 1,200 mm cell's 1,260 (in mm-equivalents); now it weighs
  100 against 1,200. The share of drawn kilometres in catchments under 400 mm fell by 0.4 to 3.9
  points per world.

**Guard.** `WaterReceivedTest`, "a catchment twice as wet carries twice the water": two identical
islands at 1,500 and 3,000 mm. **Old code: ratio 1.0000. Now: 2.0000.**

**What moved** (export selection at Earth's density, same five worlds):

| | Before | After |
|---|---|---|
| Drawn km in catchments over 1,200 mm (%) | 0.0/1.5/0.8/4.6/2.6 | 0.6/2.5/1.1/6.7/3.4 |
| Drawn km in catchments under 400 mm (%) | 69.6/57.8/75.4/62.9/65.8 | 68.4/56.7/75.0/59.0/64.2 |
| Courses drawn | 58/71/56/67/201 | 58/74/55/67/209 |
| Median drawn mouth width ratio | 0.432/0.348/0.340/0.398/0.319 | 0.460/0.362/0.321/0.436/0.312 |

The drawn kilometres are unchanged, because the budget is Earth's. Courses traced moved by at most 2.

### 3. Catchments split in the wrong order

**Verified.** 0.8%, 0.3%, 0.0% and 0.5% of land cells (seeds 7, 42, 1234, and 718106 at 2048) had
a receiver later in filled-height order.

**Change.** `BasinPartition.compute` labels by walking `FlowRouting.drainageOrder` in reverse,
receivers first; the comment says so. The fallback is gone: an unsettled receiver is now an
invariant failure.

**Guard.** `CatchmentUnitsTest`, a 925-cell tree on a flat whose receivers all have higher indices.
**Old code: 925 units. Now: 1.**

### 4. Closed basins as whole units

**Verified.** Every water cell of an endorheic lake or playa has no receiver, and each one opened a
unit.

**Change.** Each endorheic lake, and each connected stretch of playa, is gathered into one sink. The
lake's own cells are never cut; the land draining to it joins, up to the size rule.

**Guard.** `CatchmentUnitsTest`: a 25-cell lake in a 225-cell closed basin, and the same basin as a
playa. **Old code: the water in 25 units, the basin in 125. Now: 1 and 1**, lake and playa alike.

### 5. Catchment sizes in mixed units

**Verified.** Merged realm units ran up to 4.2, 3.8, 3.0 and 2.5 times `maxBasinShare` (seeds 7, 42,
1234, and 718106 at 2048). Units over the share held 41–65% of the land. The audit's 7–20× was not
reproduced on this tree.

**Change.** The rule is an area on the ground, in km². Walking sources first, each cell keeps its
tributaries, smallest first, while its area stays within the limit, and cuts the rest at their
mouths. `mergeSmall` takes a maximum and refuses any merge that would pass it.
`NationStage.realmUnits` and `CultureStage.regionUnits` state the two shares in km².

**Guards**, all in `CatchmentUnitsTest`, and all on the areas of the resulting units:

- **Arid comb** of 3.1 times the share on 100 mm of rain, through the realm stage's own partition.
  - Old code: one unit of 50 times the limit after its trunk split.
  - Now: the largest unit is 0.78 of the limit.
- **Merge fixture**: a two-cell catchment against a 200-cell one, with a limit of 201.5 cells.
  - Now: the small unit stays unmerged.
  - On the old code this fixture fails first on its premise, because the old ordering (finding 3)
    breaks the flat into one-cell units. The merge's own defect is shown on the standard worlds
    below instead.
- **Standard worlds**, seeds 7/42/1234, realms and peoples.
  - Old code: seed 7 had 9 realm units over the limit when computed and 15 after merging.
  - Now: 0 over for realms and for peoples, and every land cell is in a unit.

### 6. The realm cap exceeded (E-T10)

**Diagnosed; the suggested cause was not it.** On seed 7 at 512, origin/main's largest realm held
**24.2%** of the land straight after `BasinRealms.assign`, in 12 units whose largest was 8.4%. The
finished map had it at **33.6%**. The growth came after the cap, in
`NationStage.dissolveEnclaves`, which gave the realm its neighbours' stranded pieces.

**Change.**

- A piece now goes only to a neighbour that it leaves within `maxRealmShare`; a piece no neighbour
  can take stays where it is.
- The cap pass in `BasinRealms.schism` no longer `break`s the whole pass when the largest realm over
  the cap cannot be split. It sets that realm aside and goes on to the next.

**Armed:** `RealmSpreadTest` asserts the cap outright.

| Largest realm (% of land) | Seed 7 | 42 | 1234 | 99 | 969495 at 2048 |
|---|---|---|---|---|---|
| Before | 33.6 | 29.2 | 24.7 | 23.1 | 29.9 |
| After | 21.4 | 23.8 | 19.9 | 20.6 | 21.0 |

`RealmSpreadTest` prints 21%, 24% and 20% for seeds 7, 42 and 1234.

### 7. Tied biomes in varying order

**Verified.** `NationStage.describe` sorted `HashMap<Biome, …>` entries by count alone, in three
places: the biome shares, the heartland biome and the settled shares feeding the exports.

**Change.** Every tie in those three goes to the lower ordinal, as `CultureStage` already did. I
checked the rest of the realm and culture stages and found no other enum-keyed map;
`BasinRealms`, `CultureStage`, `LandmarkStage` and `Atlas` key their hash maps by `Int` or iterate
only in explicit orders.

**Guard.** `RealmTiesTest` generates seed 42 at 512 in two child JVMs; the second draws 7,919
identity hashes before hashing the `Biome` constants. It then compares a digest of every `Nation`
field and of every `Culture` field.

- **Old code:** the realms' digests differed (`aa153c…` against `c5ecf5…`); the peoples' agreed.
- **Now:** both agree.

### 8. Units merged across straits

**Verified.** `mergeSmall` took its host from `neighbours`, which includes strait crossings, and
picked it by largest area. The KDoc says longest shared edge.

**Change.** Merge candidates are land neighbours only, ranked by:

1. the longest shared border, measured on the ground (a column side is a row's height long, a row
   side a column's width);
2. then corner contacts;
3. then the lower id.

The border counts are integers, so the order of summation cannot move a bit. Realm expansion's
strait adjacency is unchanged.

**Guards**, in `CatchmentUnitsTest`:

- **Island fixture:** a four-cell island two cells of sea off a large one.
  - Old code: landmasses 2 → 1 across the merge, and one unit spanning both islands.
  - Now: 2 → 2, and 0 units spanning two.
- **Standard worlds:** each unit is checked against the land mask's own components.
  - Old code: seed 7 had 14 realm units spanning two landmasses.
  - Now: 0 on all three seeds, for realms and peoples.

### 9. Coastal capitals and the sea floor

**Measured, and not changed.** The coastal share of capitals, with the blur over land and sea as it
is against a blur over land only (the land sum divided by the land share, both box-blurred), on
seeds 7/42/1234/99 at 512 and 969495 at 2048:

| Coastal capitals | Seed 7 | 42 | 1234 | 99 | 969495 at 2048 |
|---|---|---|---|---|---|
| origin/main | 10/15 | 7/17 | 11/16 | 6/15 | 2/14 |
| Branch, current blur | 15/18 | 7/14 | 8/16 | 7/15 | 3/14 |
| Branch, land-only blur | 15/18 | 7/14 | 8/16 | 7/15 | 4/14 |

The land-only mean moved nothing on the four standard seeds, and on 969495 it put one more capital
on the coast. The sea floor does pull the mean down near a coast, but that is not what puts capitals
there, so the term was left and a comment records the measurement. Over the five worlds the coastal
share is 40 of 77 capitals, against 36 of 77 before, because the realms were re-laid.

## Known failures

**Armed:** E-T10, the realm cap (`RealmSpreadTest`), as above.

**Re-recorded.** These are not this chunk's findings. The lakes moved, so their recorded figures
moved with them; each clause still fails for the reason it records.

| Clause | Recorded before | Recorded now |
|---|---|---|
| The notch's pooled drowned-basin clause (`OutletIncisionTest`) | `0.2609% against 0.2505%` | `0.2591% against 0.2489%` |
| C I4's glacial lake ratio (`GlaciationTest`) | iced zone ratio 1.08 | iced zone ratio 1.02 |

## Border census: seed 718106 at 2048, before and after

Taken with the geometry guard's realm-border and peoples'-border layers (`MapLayers`,
`GeometryGuard.read` with the audit's judge). The straight-run counts are stretches lying exactly
along one grid line (`LatticeRuns`, eight steps or more), counted once per border.

No detector failed on either layer, before or after; isotropy and orientation are too small to
measure on one world. On realm borders the worst aligned side went from 42.0 to 23.0 steps and the
worst facet from 193 km to 168 km.

**Realm borders**

| | Before | After |
|---|---|---|
| Runs along a row or column (8+ steps) | 98 | 94 |
| … of 50 km or more | 10 | 8 |
| … of 100 km or more | 1 | 0 |
| Longest along a row or column | 123 km (north–south, cell (980,462)) | 67 km |
| Diagonal runs of 50 km or more | 10 | 6 |
| Longest on any bearing | 147 km | 102 km |

The 67 km run at cell (981,505) is the realm border following the ice sheet's own ruled edge. That
belongs to the ice (`ICE_EDGE_ALONG_A_ROW`), which is a later chunk.

**Peoples' borders**

| | Before | After |
|---|---|---|
| Runs along a row or column of 50 km or more | 21 | 15 |
| … of 100 km or more | 3 | 3 |
| Longest | 179 km | 179 km |

The longest runs sit at the same cells before and after, (973,556), (1977,261) and (1584,1686). They
are the ice and biome edges the peoples' layer follows by construction (`PEOPLES_BORDERS_ON_THE_GRID`),
not the partition's.

The audit's figures for this world, 13 runs up to about 217 km, were taken on an older tree; on
`fb22d30` the census found the figures above.

## The renders

Files in `review/renders/`: seeds 42 and 718106 at 1024, in the Political and Fantasy views,
`-before` from `fb22d30` and `-after` from the head. The descriptions below are of the first build.
The Political `-after` files have since been re-taken on `66d745d`, and "Review round" says what
moved. The Fantasy `-after` files are unchanged to the pixel.

**Seed 718106, Political.** Before:

- the ice sheet stood as a pale piece with ruler-straight vertical sides, cut into the realm around
  it and bordered on both sides;
- fragments of other realms were scattered along the south-west coast of the western realm.

After:

- the ice sheet lies inside one realm, and only its eastern straight side is still a border, the
  67 km run above;
- the south-west coast has fewer fragments;
- the eastern landmass is shared by compact realms.

**Seed 42, Political.** Every realm is re-laid; its colours are reassigned, so 34% of pixels
differ. Gone:

- the long straight north–south border across the middle of the western continent;
- the thin realm held inside the eastern one.

Borders now follow valleys and ridges. One narrow realm follows a river on the west: the trunk
split working as intended, though it reads as a sliver. The largest realm is 24% of the land,
against 29% before.

**Fantasy, both seeds.** They look the same to the eye at this size. 0.92% (seed 42) and 1.08%
(seed 718106) of pixels differ: river ink and lake edges, which is where findings 1 and 2 act.

## Test counts on the first build (`acb9df9`)

The counts come from the JUnit XML under each module's `build/test-results`. The head's four suites
were run in one build, `--no-daemon --console=plain -PtestWorkers=2 --continue`.

| Task | Tests | Failures | Errors | Skipped | Run |
|---|---|---|---|---|---|
| `:worldgen:jvmTest` | 263 | 0 | 0 | 0 | alone, 52 min 38 s (see below) |
| `:cartography:jvmTest` | 117 | 0 | 0 | 0 | in the four-suite build |
| `:ui:jvmTest` | 129 | 0 | 0 | 0 | in the four-suite build |
| `:desktop:test` | 104 | 0 | 0 | 19 | in the four-suite build |

**One red, rerun alone.** In the four-suite build, `:worldgen:jvmTest`'s test worker was killed by
the system with exit value 137 (SIGKILL, out of memory) after 138 tests. It was sharing 16 GB with
the other three modules' test JVMs and with `RealmTiesTest`'s two child processes. Run alone on the
same head, `acb9df9`, it passed: 263 tests, no failures. The worldgen count above is that run's.
Against the baseline's 254, the nine extra tests are the new guards: `WaterReceivedTest` 2,
`CatchmentUnitsTest` 6 and `RealmTiesTest` 1.

**Could not run here:**

- `:ui:wasmJsTest` and `:desktop:siteTest`: the network refuses a Kotlin tooling download.
- The graphics-card tests skip without a display; they are among `:desktop:test`'s skips.
- `:worldgen:wasmJsTest` was tried and could not run: `:kotlinWasmToolingSetup` failed when the
  download of a Kotlin tooling package was refused by the network with 403 Forbidden.
- The 2048 audit tier (`:cartography:audit`) was not run. Its recorded realm-border signatures for
  seeds 42 and 59758 predate this chunk and may now be stale.

## Left for other chunks

- `ClimateStage`'s comment still lists the river and realm stages among the 0..1 rainfall's
  consumers. It is recorded in `docs/TODO.md`, because that file was not to be edited.
- The ice sheet's ruled edge that the remaining straight realm run follows belongs to the ice's
  chunk.

## Review round

A second reading of the branch at `acb9df9` raised four points. Each was checked against the code
before anything changed, then fixed and guarded by a test shown failing with the fix switched off.

Commits:

- `44925cf`: point 1 and point 4
- `f244bbc`: point 2
- `6d18d53`: point 3
- `66d745d`: docs, and a count corrected in a test comment

**Head:** `66d745d`.

### 1. Basins with several exits were not solved upstream first

**Verified.** The basins were ordered by the drainage rank of their last exit. That is not a
topological order once a basin has two exits: the early one can feed a lower basin that the late
one comes after. The subtraction also walked a routing that a closed basin below might already have
re-pointed.

**Change.** `RiverStage.basinsUpstreamFirst` builds a graph: an edge from each basin to the basin
that each of its exits' water reaches next, on the routing the fill left. It then walks the graph in
Kahn's order. The subtraction walks that same routing, kept as it was before any basin closed.

**Guard.** `WaterReceivedTest`, "a basin with two exits is solved before the basin one of them
feeds". Basin A has an early exit into B and a late exit at the end of a 47-cell chain, with the
ranks asserted as the precondition.

- **Fix off** (the exit-rank ordering and the live routing restored): B is given **700 mm-cells
  against 450 reaching it**.
- **Now:** 450 against 450, and A is solved before B.

### 2. The km² limit failed where a closed lake's catchment was larger than it

**Verified.** Each water cell kept its own slopes up to the limit, and the sink then kept every
water cell together with those slopes.

**Change.** A closed lake or playa is one node at its sink. Its water is never cut. Land draining to
any cell of the water is a tributary of the sink and is cut at the limit like any other. Only a body
of water larger than the limit on its own can exceed it.

**Guard.** `CatchmentUnitsTest`, "a closed lake's unit is cut at the limit like any other": a
two-cell lake with nine land cells draining to its second cell, under a ten-cell limit.

- **Fix off** (`BasinPartition` as of `acb9df9`): **one unit of 11 cells**.
- **Now:** units of 2 and 9 cells, with the lake whole.

### 3. The cap left stranded pieces

**Verified in the code:** `dissolveEnclaves` left a piece that no neighbour could take under the cap
where it was.

**The reviewer's example has a different cause.** On 718106 at 1024 I counted pieces with
`RealmPieces`: a *stranded* piece is a piece of a realm, other than its largest, that borders
another realm by land. `acb9df9` had none, there or on seeds 7/42/1234/99 at 512. The four small
realms inside the purple realm on the eastern island are whole realms, seeded on that island and
grown round by their neighbour. They are still there after this round.

**Change.**

- A piece that no neighbour can take within the cap becomes a realm of its own, with its capital on
  its best ground, if it is at least the smallest realm (0.4% of the land, and never under 24
  cells). A smaller piece goes to the neighbour holding most of its edge; that can put the
  neighbour over the cap by less than the smallest realm, and the code says so.
- A schism's breakaway takes any run of the rest that it would cut off from the rest's main body.
- A cap-split realm that cannot be split is set aside, as before this round.

**Guards**, in `RealmSpreadTest`:

- **"a piece no neighbour can take within the cap becomes a realm of its own".** A 36-cell piece
  lies inside a realm holding 764 cells against a cap of 768.
  - **Fix off** (the piece left where it is, as at `acb9df9`): **1 stranded piece**.
  - **Now:** the piece is realm 4, with its capital on it. No piece is stranded and no realm is over
    the cap.
  - On origin/main the piece would have gone to the large realm, taking it to 800 cells against the
    cap of 768. I established that by reading origin/main's code, not by running it.
- **"no piece is stranded and no more realms are landlocked inside one neighbour"**, over seeds
  7/42/1234/99 at 512, against origin/main's figures. It passes on origin/main and on `acb9df9`
  too, so it guards against regression and does not demonstrate the defect.

**Counts** (seeds 7/42/1234/99 at 512, then 718106 at 1024):

| | origin/main | `acb9df9` | Now |
|---|---|---|---|
| Stranded pieces | 0/0/0/0/1 | 0/0/0/0/0 | 0/0/0/0/0 |
| Realms whose largest piece meets exactly one other realm by land | 5/4/5/4/5 | 4/4/5/5/8 | 2/5/5/6/7 |
| … of those, landlocked | 4 | 2 | 2 |

**Largest realm** (seeds 7/42/1234/99 at 512 and 969495 at 2048): 21.4/26.6/19.9/24.8/21.0% of
the land. Seeds 42 and 99 rose from 23.8% and 20.6%, because the breakaway rule re-lays their realms.

### 4. The water guards now assert what they name

- **The hand-drawn case** asserts:
  - A's exits are exactly its two exit cells;
  - a path leaving A and coming back is counted once: A is given 2,650 against 2,650 entering it;
  - A's water reaches B on the routing before any basin closed.
- **The terrain case** asserts that the upper basin's water reaches the lower one on that routing,
  and that every basin has an exit. It prints the exit counts, which turn out to be three for each
  of its basins.

### What moved in this round

- **Rivers and lakes:** did not move on the five worlds measured.
- **Coastal capitals** (seeds 7/42/1234/99 at 512 and 969495 at 2048): 14/18, 8/14, 8/16, 6/14 and
  3/14, 39 of 76. origin/main had 36 of 77.
- **Realm borders on 718106 at 2048** (straight runs along a row or column):

  | | origin/main | First build | Now |
  |---|---|---|---|
  | 50 km or longer | 10 | 8 | **12** |
  | 100 km or longer | 1 | 0 | 0 |
  | Longest | 123 km | 67 km | 76 km |

  The longest now is north–south at cell (459,224). No guard detector failed on either layer. The
  peoples' borders did not move.
- **Renders.** The `-after` renders in `review/renders/` are re-taken on this head.
  - **Seed 42:** one large realm, 26.6% of the land, now runs across the middle of the western
    continent, and a small realm holds the isthmus.
  - **Seed 718106:** the south coast east of the ice is split between two realms where there was
    one. The four small realms inside the eastern island's large realm remain.
  - **Fantasy views:** did not move, since rivers and lakes did not.

### Test counts after this round

| Task | Tests | Failures | Errors | Skipped | Run on |
|---|---|---|---|---|---|
| `:worldgen:jvmTest` | 267 | 0 | 0 | 0 | `66d745d`, alone, 51 min 55 s |
| `:cartography:jvmTest` | 117 | 0 | 0 | 0 | `6d18d53`, with ui and desktop |
| `:ui:jvmTest` | 129 | 0 | 0 | 0 | `6d18d53`, with cartography and desktop |
| `:desktop:test` | 104 | 0 | 0 | 19 | `6d18d53`, with cartography and ui |

`66d745d` differs from `6d18d53` only in docs and a comment in `RealmSpreadTest`. The four new tests
account for 267 against the first build's 263.

**The 2048 audit tier** (`:cartography:audit`) is being run on `66d745d`; its result is added here when it finishes.
