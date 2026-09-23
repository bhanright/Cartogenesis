# To do

- **On a smooth steep coastal slope the grid spaces the valleys.** X1d measured the author's comb on
  969495's eastern peninsula, a 367 km coast where a plateau at 1,100 to 1,900 m falls to the sea
  over 100 to 150 km: catchments of at least 1,440 km2 (Hack's area for the shortest traced course)
  reach that coast 62 km apart at 512 and at 1024 and 19.4 km apart at 2048, and at both finer
  grids they are 2.6 cells wide, 30 km and then 15 km. Coasts pooled over seven worlds hold their
  spacing in kilometres (0.83 to 0.97 coast by coast); this one holds it in cells. The hypothesis,
  not established: the hillslope process is the thermal pass at
  `ErosionConfig.criticalFallMetresPerKm`, a slope threshold with no length of its own, where
  Earth's first-order valley spacing is set by the ratio of hillslope transport to incision
  (Perron, Kirchner and Dietrich 2009, Nature 460), so on ground smooth enough that nothing else
  converges the flow, the cell is the only length left. The fix
  chunk would first confirm that on this chord (a 4096 world if the heap allows, or the same slope
  in a synthetic fixture at two grids), then give the hillslopes a transport length in kilometres,
  with GPU parity under the erosion seam. Its guard is this measurement: on that chord the
  catchment spacing and the catchments' mean width in kilometres hold from 1024 to 2048 within the
  matched-coast spread (interquartile 0.71 to 1.28 there), shown failing on today's 0.31.
  `CoastalSpacingAuditTest` and `RangeFront` are the instrument. 2026-09-22, X1d.
- **What spaces the valleys that reach the divide is not known.** X1d found them about 70 km apart
  on straight coasts and mountain fronts at every grid (65 to 71 km pooled, the same in kilometres
  front by front across 512, 1024 and 2048), and found it moved by none of the three named suspects:
  halving and doubling `TerrainConfig.reliefCornerKm`, the belts' half-widths and the noise's
  octave count left it at 0.82 to 1.27 of stock in no consistent direction. It barely follows the
  depth of the ground behind the front either (log-log slope 0.21 to 0.31 on coasts), and Hovius's
  ratio of half-width to spacing reads 0.86 to 1.05 on coasts and 0.61 to 0.95 on mountain fronts
  against Earth's 2.1. Untested candidates: the crust margin's 300 km band
  (`TectonicsConfig.crustMarginKm`), the erosion's own lengths (`debrisTravelKm`, `deltaReachKm`,
  `outletReachKm`), and the instrument's own 25 km strips, which a controlled run of the strip
  width would clear. 2026-09-22, X1d.
- **The land is drawn isotropic in cells, not in kilometres.** On 969495 the coastline projects
  1.82 times as far east-west as north-south at 512 and 2.12 times at 2048, the 2,000 m contour 1.99
  and 2.09, where land isotropic on the ground gives 1. Two known inputs draw it so: the terrain
  noise's lattice has as many cycles down the map as across it (`TerrainStage.buildNormalField`),
  and `PlateStage`'s boundary distance counts cells with no row scale (the `JumpFloodDistance` entry
  below). A kilometre-isotropic noise would take half the cycles down the map that it takes across
  it, and would move every world; measure the coastline's two projections before and after, per
  seed, as the guard. Found by X1d, whose straight-front finder saw one to three north-south
  coasts against 86 to 120 east-west ones at each grid for this reason. 2026-09-22, X1d.
- **The incision and the routing measure a step in cell widths whichever way it runs.**
  `HydraulicErosion.cut` divides a drop by 1 for a cardinal step and by the square root of 2 for a
  diagonal one and multiplies by the column count, so on a grid whose cells are twice as wide as
  they are tall it reads a north-south slope at 0.50 of the ground's and a diagonal one at 0.79,
  where `RiverStage.stepKilometres` measures the same step in true kilometres;
  `FlowRouting.flowDirections` compares its facets on the same square ruler. On 969495 at 512,
  29.4% of land steps run down a column and 37.7% diagonally. X1d recorded it and did not find it
  setting a spacing; a fix moves every world and wants `GpuErosionTest` in step. 2026-09-22, X1d.
- **The moisture march does not conserve its water, in two places.** In `ClimateStage.marchLandStep` the parcel's stock is capped to the cold cap *after* its rain for the cell has been taken, so the water the cap removes over cold ground is neither rained nor carried: it leaves the budget silently. In `marchSeaStep` the rain over open water is reported (`moisture * seaRainPerCell`) but never subtracted from the stock handed to the next cell, so the ocean reservoir approaches saturation whatever the sea rain rate is set to. Both were found by reading the code against the ledger's recycling figure, which therefore does not by itself show the budget is right. The fix is an instrumented budget first (every source, every sink, the storage change and the boundary flux summing to zero per lap), then the two corrections, then re-measuring recycling and the interior mean; it belongs to the chunk on wetter interiors, because closing the sea leak alone will move every coast. Beside it: the 1,000 km depletion length cites van der Ent and Savenije (2011) for a figure that paper gives as 500-2,000 km for tropical and mountain recycling, with 3,000-5,000 km in temperate climates and over 7,000 in deserts, so the constant's justification is misread and the transport time and the rain lifetime want testing separately. 2026-09-21.
- **A lake fan outlives its lake, and what it leaves is a sill.** On 718106 at 2048 the deposited world holds 23,362 cells of standing water (graded) and 24,421 (ungraded) against 20,998 with no deposition at all, and the window where deposition ponds the most - `[48,400,203,650]`, found by `BayHeadDeltaAuditTest` - holds one lake of 1,038 cells the no-deposition world does not have. `DepositionLog` says its shore is ringed with lake-fan spoil, and a render of the log's mechanism over the window (looked at during T4, not kept) shows that spoil lying in a ring well outside the present shore. The reading of that picture, which is an inference and not a measurement: the rounds ponded a far larger basin there, fans were built into it, the basin's rim was cut and it drained, and the spoil laid across its floor was left standing across the hollow in the middle. A fan is stopped two pond depths short of the surface it is built toward (`HydraulicErosion`, the lake inflow branch) so that every cell it touches is still water afterwards, and that is true of the surface it was built toward and not of the one the basin drains to later; sediment sits in its own array until `settle` adds it to the terrain at the end, so the per-round breaches lower the rock under it and not it. Nothing forbids cutting it afterwards: the closing breach is blind to mechanism and `openMouths` cuts any spoil under a drawn course, so what preserved these lakes is one of their limits - the closing breach's stream power, floor and reach, or `openMouths`' discharge threshold, which a small basin's outflow does not meet - and which one is not yet measured. The graded rule has no say in any of it, which is why the two settings hold nearly the same water in that window (3,699 and 3,687); over the whole world they make fourteen and thirteen lakes the no-deposition world lacks, 5,600 and 6,100 cells, and the audit case prints, for each, the gross deposition on its shore by mechanism. The audit's water clause is therefore printed as a census and not asserted. What would answer it: find which limit preserved the lakes (a round-by-round trace of one basin's spill and floor), then either stop a fan short of the floor of the basin's outlet rather than of its surface or give the closing breach a drained basin's former discharge, and measure whole-world standing water against the no-deposition world on both authored seeds at 1024 and 2048. With it, a discriminating guard for the graded rule itself, which no per-merge test has: a controlled channel whose upstream margin leaves the ungraded rule headroom to deposit and the graded rule none, shown failing by forcing the graded branch to a zero grade. Whole-world standing water on deposited worlds has stood at or above the no-deposition figure since E6 (1.04x and 1.16x at 1024 then; 1.26x and 0.99x at 1024 and 1.11x and 1.16x at 2048 on the 3.2 tree), so this is not new, only named. 2026-09-22.
- **The audit tier cannot be finished on the machine T3 ran it on, and the fault is the machine.**
  Three `EXCEPTION_ACCESS_VIOLATION`s inside `jvm.dll` in one day, 2026-09-21, in three processes
  doing three unrelated jobs. The first killed the test worker 180 seconds into the tier, on the
  exception-throw path under `LandmarkStage.generate`'s `sortByDescending` — which reads as a
  comparator raising Tim sort's broken-contract error, and `NationStage.habitability` does end in
  `coerceIn(0f, 1f)`, which passes a not-a-number straight through because every comparison against
  one is false. It is not that: the same class was rerun on the same commit with the same three
  worlds and went through the generation, failing only on the assertion it was already failing on.
  The second took out the build daemon's **C2 compiler thread** while it was compiling Kotlin,
  which shares nothing with the generator. The third, at the byte-for-byte same address as the
  first (`jvm.dll+0x820c2a`), killed the worker again after `ScaleFreeAuditTest`, so the five
  classes after it alphabetically — the snow balance, the stage profile, the straight-run network,
  the tectonic history and W1's renders — never ran at all and reported nothing.
  In the same run three classes threw index errors from three different stages with values no
  program produces: `PlateStage.classifyBoundaries` read plate 2,055 out of fourteen,
  `SeaLevelStage.markUnreachableWaterAsLand` cell 2,048 out of 790, and `FlowRouting.drainageOrder`
  went exactly one past the end of a 1,593,836-cell array. A plate id of 2,055 among fourteen
  plates is not arithmetic going wrong, it is a word of memory that changed; and the same machine
  is faulting in the optimiser. Treated as one fault with one cause.
  Recorded rather than fixed, and recorded rather than dropped, for three reasons: a tier that
  dies at a class reports every later class as never having run, which is most of what
  "chronically red with a rotating cast" looks like from outside; the reds that are real are
  invisible underneath it; and a machine whose virtual machine faults in the optimiser can also
  finish a run with numbers that are quietly wrong, so a figure measured there alone is worth less
  than one a second machine has seen. **Nothing in this chunk's ledger row that was measured only
  on that machine should be treated as settled until the nightly runner has agreed with it.** If
  any of the three index errors ever reproduces on the runner, the habitability path above is
  where to start on the first of them. 2026-09-21, T3.
- **A ring of standing water is not a measurement that tells graded aggradation from flat.** E6's
  discriminating guard counted water lying in a circular band inside a window the author had
  cropped by eye round seed 718106's southern rift, where reverting the `headroom` unit muddle by
  hand had put 563 cells of it and closing the muddle none. F35 moved every world above 512 and the
  crop stopped pointing at the rift: by 2026-09-14 it held no standing water at all in any of the
  three worlds the case generates, so its own control read nothing against nothing and the case
  failed on the clause that says so. T3 replaced the written-down window with a finder — the
  window of the same size holding the most ringed standing water on the ungraded world — and that
  did what it was meant to for the two clauses with a control: the ungraded valley now holds
  **1,218** cells of standing water against **1,118** with no deposition at all, and the graded one
  **957**, so the claim underneath the shape is measured again. It did not rescue the ring count.
  In the found window, `[896,144,1051,394]` at 2048, the ungraded world holds **275** cells of
  ringed water and the graded world holds **275**, and they are **the same 275 cells** — so what
  the detector points at is a curved lake both settings make, which is the false positive
  `BayHeadDeltaTest` already records over the whole world at 1024, where every long curved lake
  fits a circular band well enough to be counted as one. Searching the map for ringed water walks
  into it by construction, because the ringest body on a map is whichever lake happens to be most
  crescent-shaped. A window found by what the artefact leaves *and only on the world that has it*
  would be a window fitted to the answer, so the count is printed with its overlap beside it and
  nothing is asserted on it. What would earn the clause back is a measurement of the shape that a
  natural lake cannot satisfy — concentricity with the aggrading channel's own outlet rather than
  with any centre at all is the obvious candidate, and the author's crescents are on record as
  scoring nothing against the mouth, so it wants the fitted centre compared against the mouth and
  a bar derived from the difference. 2026-09-21, T3.
- **The sheet's surface envelope measures straight lines, not distances through the ice.** I3 made
  the surface the lower envelope of the plastic profiles rising from every margin point, which is
  the yield condition's own solution and is what took the facets and the ruling off the flank. The
  distance in it is the straight line to the margin cell, and a straight line is free to leave the
  ice: on seed 878210 at 1024 a cell whose nearest margin is 250 km off is governed by one 800 km
  away across open water, because that one stands at the waterline and the near one stands on a
  3 km massif. The envelope can then only under-state a dome, never over-state it, which is the
  safe direction and is why it was left; the honest quantity is the geodesic through the ice, and
  the tool for it is a marching solve rather than a jump flood, whose whole point is that it
  measures to a real cell and not along a path. Worth doing before any claim is made about how
  thick a sheet with a ragged coast stands. 2026-09-21, I3.
- **A balance of nothing still decides a biome and a permafrost zone.** I3 gave the frozen mask a
  floor at the arithmetic's own precision, `SnowBalance.SMALLEST_MEANINGFUL_BALANCE_MM`, and the
  biome classification reads the same predicate. What is not settled is the ground *between* that
  floor and anything Earth would call a glacier: on the reported world 6,164 frozen cells carry a
  balance under 1 mm a year and 12,446 under 20 mm, against the 21-23 mm a year measured at Vostok
  and Dome A, which are the driest places on Earth that sustain a sheet. Raising the floor to an
  Earth figure would take about a third of that world's ice, which is a change to the ice share
  every world is judged on and belongs in a chunk that re-derives it rather than in a defect fix.
  2026-09-21, I3.
- **The dome clause's own gate refuses a neighbourhood that is plainly one.** `IceSheetTest`
  reads its radial-flow clause only where the ice near the dome fills a third of the disc
  `NEAR_THE_DOME_KM` describes, which I1 derived as "where a neighbourhood is still a
  neighbourhood" rather than from any measurement. On seed 878210 at 1024 that refuses 3,076 cells
  — 26.9% of the 11,440 a 1024 disc holds — on a sheet whose flow is the best of the five worlds
  the class measures, 86.8% outward at a mean 44.3 degrees off radial, where an indifferent bearing
  gives 50% and 90. A share of a disc is also not scale-free in the way it looks: the same sheet on
  the ground fills a smaller share of a disc that holds four times as many cells. What the gate
  wants is a shape test — whether the neighbourhood is round or an arc — and not a count.
  `LEAST_SEEDS_WITH_A_DOME` went from two to one because of it. 2026-09-21, I3.
- **Four worlds are not a sample for an outlet's depth.** I3 turned `IceSheetTest`'s fjord clause
  into a finding with a floor at 0.9 of Sognefjord, because the deepest outlet over the four
  audited worlds now asks 1,255 m against the 1,308 the bar is, 0.96 of it. Sognefjord is a
  measurement of Earth and did not move; what moved is the ice, for two reasons that are both
  corrections. The question the clause is really asking — can a sheet of this kind deliver enough
  ice to an outlet to cut a fjord — wants more worlds rather than a lower bar, and the pooled
  maximum over four is a statistic with one observation in it. 2026-09-21, I3.
- **An export is bounded by the heap, and the graphics card has memory of its own.** The packaged
  application's heap is a share of the machine's memory (three quarters), which lifts the fixed
  12 GB wall an 8192 export hit, but the fields of an 8192 world still all live on the heap at once.
  The device already holds a field for the length of a stage (erosion, the ice, the ocean, the
  raster); keeping the world's fields resident in graphics memory between stages, or generating in
  tiles, is what would let an export outgrow the heap. Whether 8192 completes in 24 GB wants
  measuring first. 2026-09-20.
- ~~**The render records are pinned in two places and the regenerator only writes one of them.**~~
  Closed by R1, 2026-09-21, and confirmed on the merged tree. `PenAndInkTest.RECORDED_STYLES` is a
  getter onto `RecordedRenders.STYLES_AT_512` now, so the file whose KDoc calls itself "the one
  place a chunk that moves the ground re-takes them" is the one place, and `RegenerateRecordedRenders`
  writing it is enough. The long per-chunk history comment that says *why* each re-take happened
  stays on `PenAndInkTest`, above the getter, because that is where a reader of the guard meets it.
- **Erosion's rule for a river is its own, and is not the network the map draws.**
  `HydraulicErosion.DRAWN_RIVER` picks the mouths the distributary pass cuts grooves at, and it is
  a share of the land's water - 0.0006 of it, on an accumulation whose weights are rainfall. Since
  R1 a watercourse is drawn where `ChannelInitiation` says the ground can be cut, which is an area
  times a gradient in square kilometres, raised by the plant cover and held off ground that never
  thaws, and the two rules answer different questions. Erosion cannot read the other one cheaply:
  the criterion is parameterised by `config.rivers`, which is chosen long after erosion runs, so
  reading it would put `rivers` into erosion's reuse guard and let a river setting re-cut twelve
  rounds of valleys. The constant says all of this where it stands. What is *not* known is how far
  apart the two networks are at a mouth - how many wet-country trunks the groove pass cuts that the
  map does not draw, and how many dry-country ones it misses - and that is a measurement nobody has
  taken. 2026-09-22, S3.

- ~~**Seven guards are red on terrain S3 moved, and none of them is a bar that can honestly be
  lifted.**~~ Closed on the merged tree, 2026-09-22, and none of the seven turned out to want a
  lifted bar. The chunk's own defect - the cover counted twice against an erodibility already
  calibrated on vegetated catchments - was fixed by spending the factor relative to its own land
  mean, and denudation off an active belt went **0.271 -> 0.189 -> 0.218 mm/yr** (see the ledger,
  S3). What was left was a world that is genuinely a different world, and the seven cases sorted
  into four kinds. **One was a derivation, not a pin**: `IsostasyTest` holds
  `TectonicsConfig.collisionUpliftMmPerYear` to Earth's collision surface uplift plus what this
  model's own rivers remove, and the second term is re-measured on every run, so the rate follows
  it - 0.77 to **0.718 mm/yr**, with the Andean, arc and rift-shoulder rates keeping England and
  Molnar's ratios at 0.287, 0.101 and 0.043. **Two were recorded figures and were re-taken with
  the reason**: `GroundTextureTest`'s quarter textures to **65.7 and 110.4 m** from 65.2 and 115.9
  (the plains a little rougher because dry lowland now draws the least water, the ranges a little
  smoother because high ground draws the most and is shielded by its own cover, and because a belt
  races a gentler uplift), and `LakeWaterBalanceTest`'s dry basin to **50%** from 45% (at 81 mm a
  year that catchment carries the least water on the map, so its floor is the least incised and
  the same balance level covers more of it). The highest-quarter clause was a second red standing
  behind the first, at 110.378 m against a bar of 115.9, and is re-taken with it. **Two were
  single-grid control comparisons and are pooled now**: `StraightRunTest` reads 422 ruled runs
  against 521 over the four standard seeds where one seed's margin has been a single run either
  way, and `OutletIncisionTest`'s sill clause reads **0.0531% of land against 0.0904%** over five
  seeds where 718106 alone has gone the wrong way by thirteen ten-thousandths of a per cent.
  **Two became findings**: `IceSheetTest`'s outlet depth, printed against Sognefjord because an
  outlet deepens a pre-glacial valley and these sheets stand over the dry interiors the rain-fed
  rounds cut least, with the clause that the sheet feeds its outlets at all kept as the guard;
  and `FlatCourseTest`'s census, which has read 2 against 1, 4 against 4 and 1 against 5 on three
  re-cuts of the same four worlds and is asserted at 2048 in `FlatCourseAuditTest` instead.
- **The ice outlets ask for 0.72 of Sognefjord and what would settle it is more than four seeds.**
  The deepest cut asked for over the four standard worlds is 936 m against Sognefjord's 1,308, and
  it was 1,255 before the hydraulic rounds read the climate. The mechanism named above is
  plausible and unmeasured: an outlet trough deepens the valley the rivers left, a sheet grows over
  a cold interior, a cold interior is a dry one, and the rainfall weight gives dry ground the least
  water. What would turn that into a measurement is the correlation between a sheet's own
  pre-glacial dissection and the trough its outlets cut, over more worlds than four - the same
  sample problem the ice share carries, which GEOGRAPHY.md records. 2026-09-22, S3.

- ~~**Erosion does not read the vegetation, and the field it would read is sitting there.**~~ Done
  by S3, 2026-09-21. The provisional climate march W4 named as the prerequisite is in
  `HydraulicErosion.provisionalWeather`, and the density it computes scales the incision in `cut`
  exactly as W4 guessed it would: `1 - VEGETATION_SHIELDING * density`, with the half from
  Istanbulluoglu and Bras (2005) carried at the constant, and spent *relative to its own mean over
  the land* so that an erodibility already calibrated on vegetated catchments is not asked to carry
  the cover twice. Checked cell by cell against the first round's own arithmetic on four seeds at
  512: right to within 7.7e-08 on every one of the ~92,000 cells a seed leaves unclamped, with the
  factor running 0.577 to 1.295 and averaging 1.000000 over the land. Only the hillslope cut takes it;
  the outlet notch and the distributary grooves cut through days-old spoil with nothing growing on
  it. H3's half of the field - erodibility by rock and by age of crust - is still open.
- **The canopy darkening was sized for a step and is now spending its range on differences nobody
  can see.** `ClimateTint.CANOPY_DARKENING` is a twelfth, derived when the canopy was one figure
  per biome and the only question was whether a wood read as darker ground than the plain beside
  it. The canopy is a continuous field now, so most of the range is spent on differences of a few
  hundredths: across five worlds W4 measured about 30% of pixels moving between the table and the
  field, by a mean of 1 channel of 255 and at worst 13-17. The step across a woodland boundary
  fell from 0.682 to 0.044 in the *field*, which is the claim, and in the drawn ground that is
  under a colour step. Whether a twelfth is still the right figure once the quantity under it is
  continuous is a question for V1 and wants the derivation re-done against a picture rather than
  the constant nudged. 2026-09-20, W4.
- **Budyko's evaporative fraction is the wrong shape for the march's ground return, and the right
  shape is not known.** W4 offered `VegetationDensity.density` in place of
  `MoistureBudget.groundWetness`'s rainfall proxy and measured 26.5% continental recycling against
  the proxy's 32.7% and Earth's 30-45%, so the proxy stayed. The diagnosis is that the two are
  different integrals: Budyko's fraction is the share of a *year's* evaporative energy the water
  supply meets, and the march wants the share of a *parcel's* passage the ground under it can
  supply in the hours it takes to cross a cell. Over dry and cold ground the annual figure is the
  smaller, which is why the continents give less back. What would close it is a return written on
  the parcel's own timescale - soil moisture with a store and a drawdown, rather than a ratio - and
  that is a change to what the march integrates rather than to which field it reads. The switch is
  `ClimateConfig.vegetationRecycling` and `MoistureBudgetTest` re-measures both numbers on every
  audited seed, so whoever opens this starts from figures rather than from this paragraph.
  2026-09-20, W4.
- **Permafrost covers 2.10 times Earth's share of the land, and it is the tundra entry wearing a
  second hat.** Pooled over seeds 7/42/1234/99 at 512 the permafrost zones take 35.7% of ice-free
  land against Earth's 17% (Zhang and others 1999), running 7.7 to 59.9% by seed. The mask is a
  threshold on the annual mean and this map's land stands 1200-1700 m above its own sea against
  Earth's 840, so a lapse rate of 6.5 C/km takes three to five degrees off nearly every land cell -
  the same cause GEOGRAPHY.md records for half the land being tundra, and the same fix, which is
  S2's hypsometry. Nothing in the permafrost model should be moved for it: the cold end of each
  published band is already taken. What would settle that it is the hypsometry and not the
  thresholds is re-measuring this share on a world whose land hypsometry has been brought to
  Earth's, which cannot be done until there is one. 2026-09-20, W4.
- **Koppen calls some of the continuous-permafrost ground forest, and nobody has decided whether it
  should.** `VegetationDensityTest` measures the disagreement between the two classifications of
  the same cell and holds it under a quarter of the zone; the density caps those cells at two
  fifths so anything reading the *field* is answered, but the Biomes view still draws taiga over
  ground whose active layer is too shallow to root one. Teaching `classify` to read the mask is a
  one-line change and a redesign of the cold end of the classifier at the same time, which is why
  W4 reported the figure instead of making it. Whoever opens it should decide against Earth first:
  the Siberian larch forests stand on *discontinuous* permafrost, so the question is only about the
  continuous zone and is narrower than it looks. 2026-09-20, W4.

- **The largest lake in the land is 1.12 times the Caspian's share once the grids agree.** The same
  cause: with the plate seeds resolution-free, `OutletResolutionTest`'s six worlds are the 512 world
  at three sizes rather than six unrelated ones, and **59758 at 2048 reads 1.84 times the Caspian's
  share of Earth's land**. Pooled over all six the figure is 1.12; over the five without it, 0.98.
  The Caspian's share is an Earth figure, so the pool asserts the five and that one world is printed
  with Earth's figure beside it and carried as a finding. Note the shape of it before diagnosing:
  the 512 figures did not move at all (59758 1.55x, 42 0.67x, both as on main), so whatever this is,
  it is something the finer grids resolve — which is the same sentence I2's trough needed, and may
  or may not be the same mechanism. docs/DESIGN_LEDGER.md row F37. 2026-09-19, F35.
- **A shore is still a cell over the bar, and nobody has looked at it.** On the merged tree — S2b's
  flood repair included — `StraightRunTest`'s `report how straight every shore is` finds one body of
  standing water on six worlds outside the bar it derives: a 296-cell lake at (1075,1998) of 364673
  at 2048, with a 20-cell run due north-south against 18.7 allowed, **1.07 times the bar**. Every
  other measurable body on those worlds is at 0.93 times or under. That is marginal where F30's own
  two were 1.20 and 1.75, and it is a different body from either, so nothing in F30's diagnosis
  applies to it without being re-done. Whoever opens it should find out what cut it before deciding
  anything: if it is the same family the case can go back to being the assertion it was written as,
  and if a lake of three hundred cells can honestly carry a 20-cell straight edge then the bar's
  `STRAIGHTEST_SHORE_OVER_A_CIRCLE` wants re-deriving against Earth's straightest *small* lake rather
  than against Tanganyika. 2026-09-14, F30.
- **The post-cut outlet takes a deep sill down in one bite, and the slot it leaves is drawn as
  water.** F30's second body, on the tree as it stood at 00b13fe; S2b's flood repair has since moved
  the drowned basins this was measured on and both of F30's canals are gone with them, so the numbers
  below are a record of the mechanism rather than of anything on the map today. The mechanism is
  untouched and will do the same thing again wherever a drowned basin has a deep sill. On seed 364673 at
  2048 the *first* pass of `SeaLevelStage.drainDrownedBasins` takes a sill standing about a
  kilometre above the waterline down to the basin's own floor over 75 cells at once — 1,652 cells
  cut on that pass, the whole reach left at one level falling only by the notch's own gradient —
  because `HydraulicErosion.breach`'s `dropRelative` is capped by `level - floor`, the basin's whole
  depth, and F22's `outletFallToTheWater` gives a drowned sill the entire drop to the water as its
  slope, so the stream power comes out larger than the cap. What the map then shows is a canal of
  open water one cell wide and 440 km long, its shore a ruled line 1.80 times the bar
  `StraightRunTest`'s `no shore is a ruled line` derives. Nothing about the *bearing* fixes it:
  routing that pass by `FlowRouting`'s `byBestTwo` rule leaves the run at exactly 75 cells in
  exactly the same place, because the reach lies inside the drowned basin's own fill flat where the
  staircase admits too few eligible neighbours to draw between. The retreat is meant to be
  geometric — `MAX_POST_CUT_OUTLET_PASSES` records 0.60, 0.34, 0.22 ... of the field per pass on
  718106 — and a knickpoint that consumes its whole sill on the first pass is not a knickpoint.
  Whoever opens it should say what bounds one pass's bite, with the figure derived rather than
  chosen, and should look again at whether a cut one cell wide belongs in the sea mask at all:
  `RiverStage.openWater` and `DrownedValleys` both hold that a strip of water one cell wide is a
  channel and not a body of water, and the sea mask has no such rule. `StraightRunTest`'s
  `report how straight every shore is` is the instrument, and prints the figure every run.
  2026-09-14, F30.
- **Closed 2026-09-20 by F30b.** *The ruled course over a filled basin is still there, and buying it costs Earth figures.* Neither of the two fixes F30 measured is what landed: the flats keep their staircase on the filled field, and the routing alone reads a potential laid across each flat, so no level, lake or outlet walk moved and no Earth-derived guard was paid. `FlatRouting` and the F30b row carry the figures: 76 to 44 ruled runs over raised ground on 718106 at 2048. What follows is the entry as it stood. Also
  measured at 00b13fe, before S2b; the flats and their staircase are exactly as they were, so the
  mechanism stands even though the two bodies it was measured on have gone. F30
  measured what it takes to stop the depression fill's flats routing water dead straight everywhere,
  rather than in the one pass that turns such a path into open water. The fill raises each cell of a
  flat one `FlowRouting.FLAT_GRADIENT_STEP` above the cell the priority flood reached it from, so
  the routing surface inside a flat is the flood's own expansion order, its contours are the grid's
  metric exactly, and neither Tarboton's facets nor Rho8's draw has anything to work against.
  Routing every flat by the bed instead, with the draw, fixes it — 364673 at 2048 falls from 1.75
  times the shore bar to 0.46 — and takes three Earth-derived guards with it: seed 59758 at 1024
  keeps a lake of 1.83 times the Caspian's share of its land against the 1.4 times
  `OutletResolutionTest` allows, `RiverWidthTest`'s drawn pen goes to 2.46 px against the 1.23 the
  nib declares, and `OutletIncisionTest`'s control loses the separation it exists to show. Narrowing
  it to `SeaLevelStage.drainDrownedBasins` alone — the one pass whose cut becomes open water — does
  take the 41-cell canal in the author's own window away, and costs one Earth-derived guard instead
  of three: seed 1234 keeps 189 cells of water the ocean cannot reach, against `SeaLevelHistoryTest`'s
  rule that every pocket no larger than the Caspian is gone. Ground rule 5 forbids paying an Earth
  figure for a measurement, so both were reverted and what shipped is the measurement. Whoever opens
  this should start from the narrow one, which is a hundred lines and one guard away, and find out
  why that pocket survives the enclosure rule's second pass. It is F15's and F18's own family.
  2026-09-14, F30.
- **Closed 2026-09-20 by W3.** *One glacial basin's floor sat exactly on Salar de Uyuni's
  flatness.* 364673's great southern basin had been on that bar for as long as the bar existed and
  went over it when I1 gave the ice a margin that tapers: 30.7% of its floor within a metre of one
  height over 905 m of relief against 27.4% allowed, 1.12 times the bar. The mechanism was the
  flexure and not the carving - a basin is cut into rock before the ice is weighed, and what the
  load then does to it is *tilt* it, so a thinner margin presses its bed down less, the tilt is
  weaker and the floor reads flatter. W3's moisture budget moved the rain that decides where the
  ice is at all, and the basin came back inside with room to spare; the worst floor over every
  audited seed is now seed 7's 45-cell basin at 13.3% against 100% allowed, 0.13 times the bar. The
  exemption, the clause that kept it measured and the clause that kept it to one basin are all
  gone, and `GlacialBasinShapeTest` asserts the floor claim over every basin on every seed again.
  **The question the entry raised is still open and is not closed by this**: whether a basin ought
  to be cut *after* the load rather than before it. The order is the real question, it is older
  than I1, and nothing here has answered it - what has changed is that no measurement is currently
  pressing on it. 2026-09-14, I1; closed 2026-09-20, W3.
- **The sheet's scour is not lineated, though its flow is radial.** I1 gave the sheet a surface
  and a flow down it, and the flow is the dome's: 70-92% of the ice within 500 km of the summit
  flows outward, at a mean 35-66 degrees off radial, against the 50% and 90 degrees a bearing that
  has never heard of the dome gives. What has *not* followed is the scour. The hummocky lowering is
  averaged along each cell's own flow line for six cells, which should stretch the features along
  the flow and leave the field's gradient standing across it - and the gradient measures |cos| 0.74
  to 0.79 against the flow on the four standard worlds at 512, *above* the 0.637 an isotropic field
  gives, so if anything it is aligned with the flow rather than across it. Two candidate causes, and
  neither is settled: a converging flow makes neighbouring lines share most of their samples a few
  steps down, so the field varies as slowly across the flow as along it; and much of what gradient
  is left belongs to the edge of the mask and to the `min` against the already-carved ground, which
  have no bearing of their own. Whoever opens it should measure the lowering field's structure
  tensor rather than its plain gradient, and should look at sampling the noise in a frame stretched
  along the flow instead of averaging along it - the reason I1 did not is the east-west seam, which
  a rotated periodic noise does not close. `IceSheetTest` prints the figure beside the isotropic
  control and asserts only the radial half. 2026-09-19, I1.
- **An outlet trough asks for a fjord and gets half of one.** I1's outlets cut in proportion to the
  ice they are delivering, and on 718106 at 512 the deepest asks for 2,051 m, which is between
  Sognefjord's 1,308 and Skelton Inlet's 1,933. What lands on the ground is 1,025 m, half of it, and
  two of this stage's own rules take the difference: the cross-section only planes ground it is
  actually under (`GlaciationStage.cutShare`'s burial term), and nothing is ever cut below the
  waterline, because moving one cell of the sea-level percentile moves every other. Both are right
  to, and the second is the one K4 exists to lift - a fjord is a trough the sea has *drowned*, and
  until the sea can reach it the trough has to stop at the shoreline. The note is here so that K4
  reads the figure rather than rediscovering it. Two of the four standard worlds grow no outlet at
  all, which is the 2% `GlaciationConfig.outletCatchment` bar doing its job on a sheet whose flow
  does not converge anywhere; whether that bar is right wants measuring on more than four worlds.
  2026-09-19, I1.
- **A trough is still stamped along a D8 path, and at 2048 you can just see it.** I2 stopped the
  cross-section planing ground that stands above the ice, which is what made the slab, but the
  cross-section is still laid one cell at a time along the flow path and a flow path still runs
  dead straight at one of eight bearings. What is left on 364673 at 2048, in the crop at
  `desktop/build/i2-crops/after/364673-2048-window.png`, is a faint pale smear along the two legs of
  the reach and a faint brightening where they cross — the same cross, at tens of metres instead of
  the 1,130 to 1,370 m the planing was worth. It reads as a valley rather than as a stamp and no
  guard fires on it, so it is a note rather than a defect; whoever picks it up should look at
  smoothing the stamped axis rather than at the cross-section, since the cross-section is now
  bounded by the ground. 2026-09-14, I2. **Not what I3 was:** the vertical ruling reported on seed
  878210's ice was the sheet's surface and not a trough at all — that world carries one outlet of
  seventeen cells and none after the mask was floored — so this entry is still open and still
  wants a render at 2048 of a reach with two legs. 2026-09-21, I3.
- **`cutBasins` cuts one basin a world, or none, and the sinuosity test is why.** Tallies at 1024 on
  the four seeds I2 measured: seed 42 one basin from ten candidate stretches, seed 7 one from
  twelve, 718106 one from nine — and 364673 at 2048 **none at all**, from no candidates. Every
  refusal is `GlaciationConfig.minSinuosity`, which asks the whole stretch of ice inside one reach to
  have walked 1.25 times the straight line from its head to its lip. A reach is at most
  `basinSpacingKm` long or one `basinDropMetres` of descent, whichever ends first, and over that
  short a run of a D8 path 1.25 is a hard test to pass. The consequence is that the valley regime's
  over-deepened basins — the landform this stage's own KDoc calls the thing that makes lakes — are
  almost never cut, and nearly all the standing water the ice leaves is the sheet's scour. Whether
  the bar is wrong or the reaches are too short to wander in is not settled, and it wants measuring
  before either is moved. 2026-09-14, I2.
- **The straight rivers and ruled lake shores on 364673 are not the ice at all.** The author
  reported three things in the window at (1560,1480)-(2048,1968) and only one of them was glacial.
  The lakes at about (1740,1760) and (1715,1825), whose shores run straight at 45 degrees, and the
  dead-straight north-south river at x = 1788 between y = 1717 and 1770, are *identical* with
  `GlaciationConfig.enabled` false: zero cells of water differ in a 61-cell box around either lake
  and every flow target along that river is the same with the ice off. So whatever rules those
  shores is upstream of this stage — the terrain the belt is built from, the depression fill, or the
  routing — and it is still there. The straight ridge the upper lake's north-east shore lies against
  is the thing to look at first. 2026-09-14, I2.
- **A continent has no slope of its own, and the drainage shows it.** Since S2's second pass the
  base relief is shaped into a band around 400 km, which is where Earth's non-orogenic continental
  topography sits and where the eye reads a range — and it left the ground with almost nothing at
  the wavelength that makes a *long river*. The Mississippi, the Ob, the Parana and the Congo are
  long because the ground tilts one way for two thousand kilometres, and this world's continental
  crust is one thickness everywhere, so between its belts it is level.
  `TerrainConfig.regionalReliefShare` puts some of the map-scale component back, and S2's third pass
  raised it from a tenth to a sixth after finding that a tenth cost far more than the bifurcation
  ratio the second pass measured it by: at a tenth, lakes covered 3.55% of the land against Earth's
  1.48% at this cell area and whole regions drowned into mazes of inlets, because ground with no
  long slope ponds the water where it falls. A sixth is where the drainage density lands on the
  pre-S2 generator's 0.0026 km/km² and every seed's coastline still clears Mandelbrot's floor;
  above it the coast goes and the density overshoots. What a sixth buys is bought against the
  coast, and the trade is the finding: `main` before S2 had both — a coastline of 1.17 pooled *and*
  Earth's drainage — because its shoreline was a percentile through a fractal noise field rather
  than a contour across a 4,500 m crustal step. Every other lever was measured and none of them moved it: the corner
  wavelength over seven values, the relief's amplitude over 700 to 2,000 m (with the submerged
  share moved with it to keep the cut on the datum), the amplitude on orogens, the margin's own
  roughness over 0.35, 0.70 and 1.00 — worth 0.01 on the dimension, because the window that lets a
  margin wander is zero where the shoreline actually stands — the fine detail noise, the
  enclosed-sea rule, and a continental interior swell of Bond's own amplitude and wavelength built
  for the purpose and then removed because the metrics could not see it. What is wanted is not a
  swell of the surface but a variation in the crust's own thickness, which is the same thing the
  epicontinental-seas entry below wants. 2026-09-13, S2.
- ~~**There are three times as many lakes as there were, and they are the right area.**~~ Answered
  at S2's fourth pass, by the crust rather than by anything the entry proposed: giving the
  continental crust a thickness that rises inland gave a continent a slope of its own, and the
  water that had been standing on it runs. The lake share of land is 0.70% against Earth's 1.48% at
  this cell area, where the third pass measured 1.73% and the pre-S2 generator 0.54%. What the
  entry said below is kept because the question it asks — which of the count and the area is the
  defect — is still unanswered, and is now asked of a generator with too *few* lakes rather than too
  many. The original reading follows.

  Measured over the five standard worlds at 512 after S2's third pass: 1.73% of land under lakes
  against Earth's 1.48% at this cell area and the pre-S2 generator's 0.54%, in 39 lakes against its
  13. So the map
  now holds about Earth's share of its land in lakes where it used to hold half of it, and it does
  that with twice as many, each smaller. Which of the two figures is the defect is not settled:
  Earth's lake *count* at a 275 km² floor is not a number this project has looked up, and the
  Pareto exponent M1 does assert is 1.05 against Downing's 1.06. Whoever looks should start by
  asking where the extra basins are — they are not the ice moat and they are not the bend the plate
  makes at the end of a round, both of which S2's third pass fixed and which were worth about a
  third of them between them. 2026-09-13, S2.
- **The rivers deepen their notches by a fifth where they used to deepen them by half.** Measured
  along the same courses on the same world's un-eroded ground, seeds 7, 42 and 1234 at 512: the
  tree before S2 cut its channels to 0.0148 of the height field out of ground standing at 0.0075,
  and this one cuts to 0.0186 out of ground standing at 0.0157. The finished channel is a quarter
  deeper and the *deepening* is less than half what it was, because the ground the rivers are
  handed is twice as rough at this cross-section — the base relief is a band at 400 km with an
  amplitude in metres where it used to be a `1/k` surface renormalised to its own extremes. Whether
  a stream-power law calibrated on the smoother ground is under-cutting on the rougher is S3's
  question, and it is the same question as whether `K` should vary with the rock (H3).
  2026-09-13, S2.
- **The thermal sweeps no longer give a mountain its flanks.** S2's third pass raised the critical
  slope from 12 m/km to 60, which is the gentlest of Earth's great mountain fronts read over a
  cell's width, and every measurement improved — the belt flank stopped being planed into an
  annulus, the coastline rose, the lakes fell. But the figures are flat from about 36 m/km upward,
  which says the sweeps now reach almost nothing a belt profile draws, and `GEOGRAPHY.md` still
  says they are what gives a mountain its flanks. They are not; the rivers are. Either the claim
  should go or the sweeps should be given a threshold that means something at a 23 km cell, which
  is a question about what a sub-cell distribution of slopes does and belongs with lithology (H3)
  rather than with a constant. 2026-09-13, S2.
- **The spreading rate this world needs is faster than Earth's fastest ridge.** Sea floor is
  destroyed as fast as it is made, so the mean age of a planet's floor is its ocean's area over its
  ridges' production, and a world with less ridge for its ocean has to spread faster or its floor
  would be older than the planet. `PlateStage.seafloorAgeOf` therefore solves the rate from Earth's
  mean ocean depth rather than declaring it, and the figure it reaches runs to a few hundred kilometres per million years against
  Earth's area-weighted mean of 28 mm/yr and its fastest, the East Pacific Rise, at 75. The cause is
  the plate partition: fourteen Voronoi plates on a cylinder put most of their boundaries between
  crusts that are not both oceanic, so this map carries about half Earth's ridge length for its
  ocean area. Giving the plates a spreading history — ridges that propagate, and triple junctions
  that migrate — is what would fix it, and it is a chunk rather than a knob. 2026-09-13, S2.
- **The deep sea floor is some 600 m shallower than Earth's, and the missing 600 m is the margin.**
  Earth's mean ocean depth of 3,682 m is a mean over the whole ocean, and about a fifth of that
  ocean is shelf, slope and rise standing on continental crust; the deep floor away from the margins
  averages nearer 4,300. `IsostasyConfig.oceanicMeanFloorMetres` anchors this generator's *oceanic
  crust* at 3,682 rather than at 4,300, which keeps the whole ocean's mean where Earth's is at the
  cost of the deep floor's, because the model has far less of that shallow fifth than Earth does.
  Which of the two to anchor on is a real question and it is the same question as the
  epicontinental seas below: drown the continents as much as Earth drowns its own and the two
  figures reconcile. 2026-09-13, S2.

- **The coastline is drawn by the cell-scale relief on low ground, and this pass took some of that
  away.** The box-counting dimension is a measure of how crinkled the shoreline is at four, eight
  and sixteen cells, and what crinkles a contour at that scale is the height noise around it
  divided by the slope it crosses. S2's fourth pass made the texture proportional to the local
  relief, which is smallest exactly where the shoreline is, and the pooled dimension came down from
  1.149 over five seeds to 1.129 over four — still inside Mandelbrot's 1.25 ± 0.15, but seed 99 at
  1.092 is under its floor on its own, which is why `EarthLikeness`'s clause is now asserted pooled.
  The same trade is the first entry in this file read the other way round: `main` before S2 had a
  coastline of 1.17 *and* Earth's drainage because its shoreline was a percentile through a fractal
  noise field, and every rule that has since given the ground a physical shape has cost the coast
  something. What would buy it back honestly is a coastal *process* — waves, longshore drift, a
  barrier island — which is section 5 of `REALISM_AUDIT.md` and is nobody's chunk yet.
  2026-09-13, S2.
- **A sixth of the map-scale relief may no longer be needed, and nothing has measured it since
  the crust got a slope.** `TerrainConfig.regionalReliefShare` was raised from a tenth to a sixth
  at S2's third pass because at a tenth the water ponded: lakes covered 3.55% of the land and whole
  regions drowned into mazes of inlets. S2's fourth pass gave the continental crust a thickness
  that rises inland, which is a long slope of the same kind and a better-founded one, and at a
  tenth *and* a flat crust the lakes now read 0.96%. So the control `GroundTextureTest` used no
  longer bites and the sixth is carrying an unknown share of its own weight. What it costs is
  measured: TODO's first entry records that a sixth is bought against the coastline. Somebody
  should sweep the share again on the new ground and take back whatever the crust is now paying
  for. 2026-09-13, S2. *S2b, 2026-09-14: the control has now been taken out of
  `GroundTextureTest` rather than printed, and re-measured on the repaired ground it reads 0.84% of
  land in lakes against a bar of 2.22% — it passes by a factor of two and a half. The clause it
  stood in no longer claims to justify the sixth, so this entry is the only thing holding the
  question.*
- **Four `JumpFloodDistance` callers still measure north-south distance with the cell's width.**
  S2b gave the flood a row scale and passed it from `PlateStage`'s craton reach and sea-floor age,
  which are S2's own. The rest still count cells and convert with `cellWidthKm`, so on a 2:1 grid
  each of them reaches half as far north-south as the kilometres it is given: `ClimateStage`'s
  `waterDistance`, which sets how maritime a coast is; `SeaLevelStage`'s distance to land, off which
  the shelf, the slope and the rise are read, so a shelf is half as wide off a northern coast as off
  a western one; `PlateStage`'s two boundary-distance fields, which every belt profile, plateau rim
  and trench wall is a function of; and `GlaciationStage`'s distance to the ice edge and distance to
  ice. Each is a one-line change — pass `config.cellHeightInCellWidths` and convert the answer with
  `cellWidthKm` — and each moves every world it touches, so each wants its own before-and-after
  renders and its own re-pinning. The belt profiles are the largest of them and probably want a
  chunk rather than a line. 2026-09-14, S2b.
- **A below-sea-level basin can come out four rows deep and twenty-five columns long, and nobody
  has looked at one.** S2b's repair to `fillDepressions` reaches, for the first time, land that the
  enclosed-water rule left standing below the water beside it, so those hollows now hold standing
  water instead of lying dry and undrained. On seed 7 at 512 one of them is 28 cells at (344,477),
  590 km by 47 km on the ground and within 1.12 cells of one straight line — straight enough that
  `StraightRunTest`'s ruled-bar census counted it until that case was split by the sea-level cut.
  It is not the ruled *trench* the census exists to catch: it sits at 0.272 of the field against a
  shoreline at 0.632, its floor is not flat, and the world built with the plain routing rule has no
  such basin at all. But an inland sea that shape is a claim about the map, and the only thing that
  can judge it is a render of the seed it is on, which nobody has taken. 2026-09-14, S2b.
- **No seed left in the rift scan floods as three separate gulfs, so that bar is withdrawn.**
  `RiftSegmentationTest` held three figures against the unsegmented control: separate bodies of sea
  inside the rift, land bridges crossing it, and how much the flooded width varies along its length.
  On the ground S2b leaves, the same twelve-seed scan the class documents finds no seed that clears
  three bodies with a control that fails — seed 77 reads three either way, and every other segmented
  rift floods as one body or two. The other two bars still separate the two worlds widely (seed 43
  reads 3 bridges and 0.15 of variation against 0 and 0.04), so the clause keeps its teeth, but the
  claim that a flooded rift is a *chain of basins* is now only carried by the picture. What would
  restore it is a rift that subsides below the waterline along part of its length and not the whole
  of it, which is the rift's own subsidence entry above; today a rift either floods end to end or
  stays dry, and the accommodation zones show as bridges rather than as sills between gulfs.
  2026-09-14, S2b.
- **`outletFallToTheWater` has one seed's worth of guard left, and it wants a synthetic sill.**
  The rule is that the outlet notch measures its channel's fall to the water it empties into rather
  than to the last cell of land, so a sill lying level to the shore is not read as having no
  gradient at all. `OutletIncisionTest` shows it by generating two worlds per seed and comparing
  their largest drowned basin, and S2b's depression fill has taken most of that difference away:
  the flood now raises land standing below the water beside it, which is the ground such a sill is
  made of, so the fill does part of what the outlet walk used to be left to do. Over the case's six
  seeds, without the step against with it, 718106 reads 0.1397% of land against 0.1319% and is the
  only one that still shrinks; 99 reads 0.1093% against 0.1095%, 7 nothing against nothing, and 42,
  1234 and 43 read *larger* with the step because cutting a level sill lets a neighbouring hollow
  join the sea and a different body becomes the largest drowned one. A guard resting on one seed's
  6% is a guard waiting to go green for the wrong reason. What it wants is a synthetic basin with a
  sill level to the water, the way S2b's other three guards are built. 2026-09-14, S2b.
- **The flexure's continuation past a pole is a plain mirror, not the far side of the world.**
  `Isostasy.Flexure` pads the load out to twice the map's height by reflecting it about each polar
  row, which stops the two poles bending each other and gives a pole the zero slope it must have.
  The exact continuation is a reflection in y *together with* a shift of half the map in x, because
  this world is 12,000 km round and 6,000 from pole to pole, so a meridian is a closed loop and what
  lies past the north pole is the far side of the world. The two differ only for a load at a pole
  that is not zonal, which a polar ice cap very nearly is. The same entry covers what the padding
  costs: the transform pair now runs on a grid twice as tall, which is 2.2 times the arithmetic and
  twice the buffers (134 MB at 2048, 537 at 4096). Both could be taken back at once — the mirrored
  field is even, so the y transform is a cosine transform and could run at the map's own height —
  but that is a real piece of numerical work and the flexure is already a G-track candidate at 4096.
  2026-09-14, S2b.
- **The drainage density has no Earth figure, only a regression bar.** `GroundTextureTest` holds
  the channel length per unit area within a third of what the pre-S2 tree measured, which is a bar
  against a generator and not against a planet. It was a fifth until this pass and moved because
  the ground now drains — the lake share fell from 1.73% of land to 0.70% against Earth's 1.48%
  while the density rose from 0.00256 to 0.0032 km/km², which are the same fact twice. What is
  missing is Earth's own channel length per unit area *at this instrument's support threshold* of
  275 km², which M1 never looked up because its own drainage row is a shape claim (density peaks
  on the dry side of the aridity index) and not a level. Until somebody does, the direction of a
  change in this figure cannot be read. 2026-09-13, S2.
- **A glacial trough has no bounded reach.** `GlaciationConfig.runOutKm` is 187.5 km and says how
  far a trough may continue past the frozen mask onto ground an ice age's ablation would keep warm.
  The trunk pass does not read it: it follows a flow path down from a cirque for as far as the path
  descends, so a trough off a six-kilometre massif ends four kilometres warmer than its head and
  three hundred kilometres away. `SnowBalanceTest`'s carving control had to gain a floor in cells
  because of it — the warm tail is a fixed cost per trough and its *share* of the carved ground
  grows as the ice shrinks, which is how a seed with 0.9% of its land under ice reads 10% of its
  carved ground above freezing where a seed with 8.7% reads 0.5%. Either the trunk should stop at
  the reach the setting names or the setting should be retired as a description of the cirque pass
  alone. 2026-09-13, S2.
- **The lowest ground on the map is the roughest, and on Earth it is the flattest.** Since S2's
  fourth pass the base relief's texture follows the local relief, and the relief itself follows the
  crust: `TectonicsConfig.marginReliefStandardDeviationMetres` at the crust's own edge falling to
  `cratonReliefStandardDeviationMetres` inland. What that leaves is a coastal band carrying the
  margin's 700 m of spread, so on four of the five standard worlds at 512 the *lowest* quarter of
  the land is rougher than the second quarter — 69/47, 91/66, 44/37, 44/41 and 66/35 m of
  cell-scale departure — where `main`'s rises monotonically with elevation on all five. Earth's
  coastal plains are the flattest large ground there is (the Gulf, the Atlantic, the Amazon, the
  Ganges, the West Siberian), and they are flat because they are built by deposition rather than
  left by erosion. The margin's figure is doing two jobs at once — the structure of the shelf and
  the texture of the plain behind it — and only the first of them is what it was derived for. What
  is wanted is the coastal plain as a depositional apron, which is the deposition stage's business
  rather than the noise's. 2026-09-13, S2.
- **The continental crust's altitude spreads by a fifth more than Earth's.** With the field on an
  absolute ruler the spread can be read directly: over the five standard worlds at 512 the standard
  deviation of altitude over cells that are more than half continental crust is 1,341 m. Earth's,
  worked from its own hypsometry — 71% land at a mean of 840 m and a spread near 1,090, 29% drowned
  at a mean near −400 and a spread near 500 — is about 1,110. The excess is the same one the entry
  below names as too much high ground, and it is now a single number that a guard could hold if
  anybody decided which of the model's amplitudes should give: the margin's relief, the belts'
  along-strike swell, or the gravitational limit. 2026-09-13, S2.
- **This generator's continents have no epicontinental seas.** Earth's continental crust covers
  41.2% of the surface and its land 29.2%, so 29% of the continents are under water; this generator
  drowns a fifth of its own, which is what `TectonicsConfig.continentalCrustSubmergedShare` carries
  and what its sea-level cut is solved against. S2's second pass closed most of the gap by giving
  the continental surface Earth's own spread about its mean — 700 m of standard deviation, where
  the first pass gave it a fifth of that — and the shoreline residual came down from 428-796 m to
  -187 to +112 m. What is left is the harder half and it is nameable: there is no Hudson Bay, no
  Baltic, no North Sea, no Sunda shelf, because nothing in the model floods a continent's *interior*.
  That is a question about how the crust's own thickness varies inside a plate, which the model does
  not represent — every continental column is 41 km of crust. A swell of the surface at Bond's
  amplitude and wavelength was built for S2's second pass and measured: it is not the same thing and
  the metrics could not see it, so it was removed again. What is wanted is thickness.

  S2's fourth pass gave the crust a thickness that varies *with distance in from its own edge* —
  44.6 km in the craton against 32.6 at the rim — and that is what moved the drowning to the
  margins, from 68% of it within 500 km of the crust's edge to 81%. It is not what this entry
  wants. A Hudson Bay is thin crust in the *middle* of a craton, which is a failed rift or an old
  suture and not a distance from anywhere; the profile this pass added cannot draw one, and the
  drowned share fell from a fifth toward the rim rather than rising toward Earth's three tenths.
  What is wanted is still thickness, and now specifically thickness that varies with the crust's
  own history rather than with its geometry. 2026-09-13, S2.
- **There is half again too much high ground.** With the field on an absolute ruler the land's
  elevation distribution can be read against Earth's for the first time, and the top of it is fat:
  over the five standard worlds at 512, 4.0-13.0% of land stands above 3 km against Earth's 5% and
  3.2-7.6% above 4 km against Earth's 2%, while the bands below 2 km are close (25.8-79.3% above
  1 km against 31%, the spread being how far each seed's sea-level cut sits from the datum). Two
  candidates and neither was measured apart at S2. The belts' along-strike swell puts a strong
  pair's crest eight times above a typical one's, which is a much wider spread than Earth's ranges
  have; and the gravitational limit compresses everything above 3 km toward 6 km rather than
  removing it, so what the limit refuses to raise it piles up instead. Lowering
  `TectonicsConfig.beltReliefMetres` from 13,000 to 10,000 moved the figures by less than a tenth,
  which says it is the limit and the swell rather than the scale. 2026-09-13, S2.
- **A rift trough does not subside while it opens.** S2's uplift field is positive only: it takes
  the part of a belt's stamped profile that stands *up*, so a rift's shoulders rise every round and
  its floor does nothing. On Earth the floor is the half that moves — a half-graben subsides as its
  master fault slips, which is why the Gulf of California and the Red Sea are drowned across their
  whole width and this generator's coastal rifts show a dry hinge shelf. The mechanism is one sign:
  give `CONTINENTAL_RIFT` a negative rate on the trough as well as a positive one on the shoulders,
  scaled by how far the rift has opened. It was left out of S2 deliberately, because the E-track's
  rift lakes are held to `RiftDepthTest`'s and `OutletIncisionTest`'s bars and deepening a trough
  round by round is exactly what E7 measured and refused when it was done by the stamp. Whoever
  takes it should read E7 and E8's notes below first. 2026-09-13, S2.
- **The tectonics' belt widths are still counts of cells.** S1 left the widths and the heights
  together because neither could carry a unit while the field was normalised; S2 gave the heights
  one — every belt height is a share of `TectonicsConfig.beltReliefMetres` — and left the widths
  where they were, because `WorldGenConfig.atResolution` already carries them across a change of
  grid and writing them in kilometres would do the same arithmetic in a different place. It would
  read better all the same, and it would empty `atResolution` of everything but the moisture
  march's own knob. A rename with no physics under it. 2026-09-13, S2.


- **M1's coastline box count reads structure far below its own smallest box.** It counts the boxes
  of four, eight and sixteen cells holding both land and water, and a box is mixed by a *single*
  cell of the other kind — so a tooth one cell deep makes a four-cell box mixed and rarely makes a
  sixteen-cell box mixed, and the slope over 4 to 16 is read partly off structure under four cells.
  It is why 2.0.2 scored 1.207, inside Earth's band, with a tooth on every cell of every coast, and
  why F17 removing the teeth takes it to 1.167 pooled and 1.116 on seed 7 — inside the bar M1 asserts,
  but with a tenth of the room it had. The coast at those scales did not change: measured with a
  ruler coarsened by majority, which cannot see under its own step, the same coast reads 1.255
  pooled after against 1.260 before, and seed 7 reads 1.228 against 1.230. The repair belongs to the instrument — `CoastRoughness`'s
  `richardsonLength` is the one F17 uses and M1 could take it, or its box sizes could start above
  the scale it means to measure. 2026-09-13.
- **A graded coast has no barrier islands.** F17's littoral pass fills the re-entrants of Earth's
  third of the shoreline but does not throw a barrier across the mouth of one and leave a lagoon
  behind it, which is what Earth's depositional coasts are — Padre Island and the Laguna Madre, the
  Frisian chain and the Wadden Sea. A filled bay is one shoreline where a barred one is two, so the
  coast is short of both the coastline it should have and the tidal country behind it. The audit's
  K1 (wave climate and longshore drift) is the chunk that owns it. 2026-09-12.
- **The coast is still rougher at the cell than four cells up, and the rest is not channels.** After
  both of F17's passes the excess is 0.198 where 2.0.2's was 0.322, against Earth's zero —
  Richardson's plots are straight lines. Filling *every* drowned notch, estuaries and all, reaches
  the same 0.199, because what stops the fill is not the width bar but the two rules the fill is
  bounded by: new ground may not stand above the ground beside it, nor fail to fall towards the sea.
  So the residue is not the channels; it is the percentile cut running through the erosion's own
  texture at the cell, and it is still 0.288 with the lowstand switched off entirely. Closing it
  means the sub-grid correction F17 applies to drowned channels applied to the whole near-shore
  height field, which moves every coastline rather than the drowned ones and wants its own chunk and
  its own renders. 2026-09-13.
- **`OutletResolutionTest`'s resolution contract has two hundredths of room left.** Seed 59758's
  standing water spreads 1.39x across 512, 1024 and 2048 against a bar of 1.4; with F17's
  drowned-valley fill off it reads 1.37x, and the test's own comment records 1.14x when the contract
  was written, so the drift is mostly older than this chunk. A sub-grid correction necessarily does
  more at a coarse grid — that is what sub-grid means — so anything else of this kind will push the
  same figure. The term that actually misbehaves is a 755-cell drowned basin seed 59758 has at 1024
  and not at 2048, which nothing in F17 touches. 2026-09-13.
- **The littoral criterion cannot tell a coastal plain from a flat coast on hard rock.** It ranks
  the shoreline by the height of the land within 187 km and takes Earth's 31%, because no height
  derivable from Earth lands on that share: the postglacial rise calls 59% of the shoreline
  depositional and a coastal plain's own gradient calls 1.9%. The quantity in the gap is lithology —
  Finland, the Canadian Shield and western Scotland are flat, ragged and rock — which H3, a queued lithology chunk
  would supply. Until then a world's depositional share is Earth's by construction rather than by
  measurement, and a world that genuinely had less low coast than Earth would not show it.
  2026-09-12.
- ~~**A lake can be a dead-straight diagonal bar.**~~ Fixed at F18 (2026-09-13). The cause was not a
  tie among near-equal descents, as the jitter's case would have been: on the apron the trench
  crossed, the drop to the winning diagonal was 1.9489e-02 and to the runner-up 1.5050e-02, the same
  two figures to four digits at seven cells running. The plane simply faces 83% of the way from the
  cardinal toward the diagonal, and rounding a bearing to one of eight makes that 100% every time.
  `FlowRouting.flowDirections` now takes the direction from Tarboton's steepest triangular facet and
  draws the one receiver across it at the bearing's own share (Rho8), so the course follows the same
  slope without being ruled. Census of straight bars 1/0/0/0/0 before, 0/0/0/0/0 after. See
  `StraightRunTest` and `GEOGRAPHY.md`.
- ~~**A sill that runs level to the shore reads as having no gradient, so it never cuts.**~~ Fixed
  at F22 (2026-09-13). The walk that measures an outlet channel's fall stopped on the last cell of
  land, one step short of the water it empties into, so where the sill ran level to the shore the
  whole of its fall was in the step not taken and what was read instead was the 1e-6 the depression
  fill nudges a flat by. The step into the water now counts, only where the walk found no fall the
  fill did not put there. Largest drowned basin 0.361% of land to 0.083% on 718106 and 0.658% to
  0.122% on 99, against the Caspian's 0.249%; seed 42's round that cut no notch cuts one.
  The pass ceiling was re-derived from the retreat it now has, eight to ten, on the release line;
  forward-merged here it is left at the sixteen S1's metre-deep lowstand needs, because a pass that
  cuts more can only shorten the retreat and the loop leaves early when a pass finds nothing. See
  `OutletIncisionTest` and `GEOGRAPHY.md`.
- **The drainage's standing water grows with the grid, and nothing guards it where it belongs.**
  `GlaciationTest`'s resolution clause used to assert that the lake share of land grows by less than
  2.0 when the grid doubles, on seed 42, through a glacial mask. F22 stopped asserting it, because
  the same quantity measured across seeds does not hold still: standing water above the sea-level
  cut, as a share of land, grows by 2.29 on seed 42 between 512 and 1024, 2.32 on 7, 6.80 on 1234
  and 0.41 on 99. One seed cannot carry a bar on a figure with a sixteen-fold spread. The clause it
  replaced still catches the mesh it was written for by shape — trough depth, till, the comb, the
  filaments — but the whole-map question it was also being asked, whether the drainage selects lakes
  per cell or per unit of map, now has no guard at all. It belongs in `ResolutionScalingTest`,
  pooled over several seeds rather than read off one. 2026-09-13.
- **The facet routing doubles the share of standing water lying in thin parallel bars, and no
  guard owns that figure.** On seed 42 at 1024, with the outlet notch and the tectonic history off
  and the ice switched off as well, the share of lake cells in a thin grid-bearing bar with a
  parallel twin within ten cells goes from 3.00% (49 cells of 1650) under the old steepest-of-eight
  rule to 6.03% (124 of 2060) under the facet rule. The other two seeds the comb case uses barely
  move: 718106 reads 1.21% and 7 reads 1.65%. This is the opposite direction from what the routing
  change is for, and it is not the ruled *bar* F18 removed — `StraightRunTest`'s census of
  twenty-cell straight bodies is 0 on every seed — but short bars of four cells or more, running in
  ranks. A plausible mechanism is that drawing the receiver across a facet makes neighbouring flow
  lines converge and diverge where the plain rule ran them all the same way, which puts more short
  reaches side by side; that is a guess and has not been measured. `GlaciationTest`'s comb clause
  used to be where this figure was asserted, and it has been restated to measure what the ice adds
  because the ice adds none of it (-3 cells on seed 42). The drainage's own parallel-bar share now
  has no guard. It belongs beside `StraightRunTest`, over several seeds, with the routing rule as
  its control. 2026-09-13.
- **Which basin is the largest drowned one is not stable, so a repair can raise the figure.**
  Cutting a sill that runs level to the water takes seed 99's largest drowned basin from 0.5362% of
  its land to 0.1183%, which is the repair working. On seed 718106 at 512 the same switch takes it
  from 0.2731% to 0.3240% — 1.10 to 1.30 times the Caspian's share — because the two runs do not
  measure the same body of water: with S1's 120 m stand and sixteen post-cut passes the basin the
  release line measured is already open, and cutting the level sills lets a neighbour of it join the
  sea, leaving a different basin the largest. Both figures are inside the guard's own allowance, so
  nothing is failing; what is missing is a measure of the drowned water that does not depend on
  which single body happens to be biggest — the same complaint `OutletIncisionTest` already makes
  about the largest lake in the land, and answers there by measuring the world's whole standing
  water. The drowned half has no such pooled figure. 2026-09-13.
- **A basin can be left standing at the waterline behind a sill at the waterline.** The post-cut
  outlet stops when it has cut a sill to the shoreline, correctly, and 10/5/23/22 hollows survive
  that on seeds 7/42/1234/99 at 512 over 15/12/189/47 cells. On Earth a barrier within a storm
  surge of the waterline is overtopped and scoured — the Bosporus is the case. E8 built the rule
  (cut the exit to a surge below the waterline, by the sea rather than any river, then re-label)
  and reverted it: it broke `DepositionTest`'s land pin, `GlaciationTest`'s ice control and
  `DeltaMouthTest`'s vacuity check, none with an Earth figure to re-derive from, to buy 1517 cells
  of 4.19 million at 2048. `WaterlineBasinTest` counts the population it would act on, which is
  also the population S2's rift subsidence will move. 2026-09-12.
- **A coastal rift's dry hinge shelf needs subsidence, not water.** Measured three ways now and the
  third is E8's: the author's trough on 718106 at 2048 keeps 29% of its flat floor wet, and 30% is
  the ceiling for *any* marine process, because only 2215 of its 7397 floor cells stand below the
  sea-level cut. It is already an arm of the sea — 1635 of the 2158 wet cells are ocean — so there
  is no sill to breach. To cover 60% of the floor the water must stand at 0.095 of the land's
  relief, about 760 m up, which is a perched lake behind a dam and is the pre-H5b world whose
  largest lake was four times the Caspian. E7 refused deepening the stamp and hashing sub-basins
  onto the floor on the same bar. What is left is subsidence that scales with how far the rift has
  opened (S2 in REALISM_AUDIT.md). 2026-09-12.
- **A cap that is the Caspian's *share of Earth* is a seventh of the Caspian.**
  `SeaConfig.enclosedSeaMaxKm2` is 52,600 km² and `GlaciationConfig.maxLakeAreaKm2` is 11,520, and
  both came from carrying a share of Earth's surface — the Caspian's 0.073%, Superior's 0.016% —
  onto a world of 72 million km² against Earth's 510. The lakes they are named for are 371,000 km²
  and 82,100. S1 turned both into areas, which is what made the question visible, and deliberately
  kept the value that reproduces today's behaviour: seven times the cap turns several more inland
  seas into land on every seed and moves coastlines nothing else in S1 touches. Which of the two a
  world a seventh of Earth's size should use is a question about what a fantasy world is, not about
  units. 2026-09-13, S1.
- **The height field has no absolute vertical scale, so the three parts of the ruler disagree.**
  `WorldScale` declares the land's relief above the shoreline (6,000 m), the sea's below it
  (10,000 m) and the raw height field's whole range (their sum, 16,000 m). The three are consistent
  only if the shoreline sits at 0.625 of the field, and it does not: it is a percentile of the
  *cells*, so where it lands in the *range* is an output. Measured on the standard seeds at 512 it
  sits at 0.395, 0.437, 0.477 and 0.554, which puts the metres one unit of the field is worth,
  read off the land, at 10,228 to 17,370 against the 16,000 declared — a spread of 0.64x to 1.09x.
  `UnitsTest` holds it inside a stated factor as a regression guard. Not closable by declaring
  anything; uplift and isostasy (S2) give the field a scale that does not move with the sea level.
  2026-09-13, S1.
- **The in-round outlet notch makes drowned basins larger, not smaller.** On seed 718106 at 512 the
  largest basin below the sea-level cut is 0.176% of the land with `ErosionConfig.outletIncision`
  off and 0.352% with it on: the notch cuts channels across ground that is dry at the lowstand and
  drowns when the sea returns, and those channels join hollows that would otherwise be separate
  basins. On Earth that is how the Bosphorus and the North Sea's Silver Pit work, so it is not
  obviously wrong — but it is the mechanism behind the one seed that now sits over the Caspian, and
  nothing measures it apart from this note. 2026-09-13, S1.
- **`NationsConfig.slopeResistance` and `terrainResistance` are dead.** Nothing reads either. The
  realm stage's cost surface was rewritten around catchments and the two were left behind, still
  serialised, still rescaled by `atResolution` until S1 stopped doing that. They are not given
  units, because inventing a unit for a knob nobody spends is worse than leaving it plain, and not
  deleted, because deciding what a realm should pay for a climb is a change to the realm stage.
  2026-09-13, S1.
- **A drawn river begins at its biggest headwater, not at its farthest.** `RiverStage.traceRivers`
  sorts channel heads by the flow each already carries and traces the largest first, so the course a
  `River` holds runs from that head to the mouth and the longest watercourse in the same catchment
  is drawn afterwards as a tributary stopping at the junction. The union of the drawn cells is the
  right network and the picture is right; what is wrong is any consumer that reads one `River` as
  one river. M1 measures how wrong, and it is half: over seeds 7/42/1234/99 at 512 the drawn courses
  cover **0.484** of the watercourses they stand for by length (0.465/0.531/0.487/0.458), and over
  the six audited seeds at 2048 **0.408** (0.367 to 0.451), where the share is 1.0 by definition — a
  river is its own longest watercourse. Hack's exponent over the same basins does not settle in one
  direction, 0.463 drawn against 0.507 on the terrain at 512 and 0.591 against 0.491 at 2048, so
  what is wrong is not a consistent scaling but which branch the trace happened to take. It matters
  for V3's labels, for anything quoting a river's length, and for what `RiverWidth` treats as a
  trunk. The repair is in the tracing: rank the heads by the length of the path below them rather
  than by the flow at them, or trace each mouth upstream along its longest branch. 2026-09-12.

- **Moglen's wet side flattens as the grid is refined.** R1's criterion has humid country carrying
  0.73 of the semi-arid drainage density pooled at 512 and 0.79 at 2048, and per seed the narrowing
  is larger than the pooled figure suggests: 1234 reads 0.81 at 512 and 0.97 at 2048, seed 7 0.80
  and 0.92. Every seed still turns the curve over, so the clause holds at both grids, but the margin
  is thinner where the grid is finer and the direction is consistent. The cause is in the criterion:
  it reads the gradient to a cell's own receiver, a finer grid resolves the steep ground orographic
  rain falls on, and the wet side therefore gains more from refinement than the dry does. Worth a
  measurement at 4096 before deciding whether it converges or keeps going. 2026-09-21, R1.
- **`EarthLikeness.strahlerStreamOrders` still walks the height order.** R1 moved the
  longest-flow-path walk onto `FlowRouting.drainageOrder` after the height order was shown to lose
  length on long paths — the drawn courses read 1.10 to 1.30 of the watercourse they lie on at 2048,
  which cannot happen. Strahler's ordering has the same shape of walk over the same tree and the
  same exposure, and it was left alone because Horton's ratios are asserted and passing and a chunk
  should not move a green bar in passing. What it would cost is one sort; what it might move is the
  bifurcation ratio, which would then want its own measurement of before and after. 2026-09-21, R1.

## Done

- **A lake's outflow could be discarded as a headwater stub** (2026-09-21, R1) — the course from a
  head fed only by a lake's open water was measured against `RiverConfig.minLengthCells`' eight, and
  an outflow within a few cells of its trunk was dropped with the scratchy headwater scratches the
  rule existed to suppress. Both halves of that are gone: the length rule is a hundred kilometres of
  ground rather than eight cells, and a cell below a lake outlet carries the whole lake's
  runoff-weighted catchment, which clears the channel-head threshold by orders of magnitude. What is
  left of the old note is that the criterion is still about the water and not about where it came
  from; `RiverCourseTest`'s `gaps` clause counts what is left.

- **`RiverConfig.maxRivers` was a count of courses, so the drawn network thinned on a finer grid**
  (2026-09-21, R1) — four hundred courses at 512 and four hundred at 2048 over sixteen times the
  cells, so the map drew a smaller share of its own network the further in it was generated: 0.484
  of the watercourses it stood for at 512 against 0.408 to 0.506 at 2048. The cap is gone rather
  than re-expressed as an area, because what it was for — keeping a wet world from becoming a
  thicket — is the renderer's job and the renderer already does it by Töpfer and Pillewizer's root
  law. `minLengthCells` went with it, replaced by `shortestDrawnCourseKm`.

- **Hack's exponent fell by nine hundredths between 512 and 2048, because the channel network was
  thresholded in cells and not in ground** (2026-09-21, R1) — T3 measured it and named R1; R1 made
  both ends of the fit areas of ground (`EarthLikeness.CHANNEL_SUPPORT_KM2` and
  `SMALLEST_HACK_CATCHMENT_KM2`) and moved the fit onto the network the generator itself initiates.
  The exponent is a clause again in `EarthLikeness.complaints`. The cure T3 declined — making the
  support area an area — turned out not to move Horton's ratios out of Horton's band, which is why
  it could be taken here: the same pair of thresholds read as ground gives 4.52 to 4.69 at the
  finer of them.

- **The generator's ocean was nearly all shallow** (2026-09-13, S2) — the oceanic hypsometric mode
  sat at about -390 m against Earth's -3,700, because the height field was renormalised to its own
  extremes and the shoreline was its 62nd percentile, so most water cells sat just below the
  waterline with a long tail to a few trenches. Two crusts of different density floating at two
  levels is what makes Earth's floor bimodal, and S2 models it: the mode is now at -3,233 to -4,229
  m over the five standard worlds, the curve has a trough between its two modes holding 0.086 to
  0.158 of the smaller one against Earth's 0.17, and both clauses are asserted in
  `EarthLikenessTest` where they were findings. What the same change did *not* fix is the shelf
  plateau, which is still 1,000 m and is now its own entry above.

- **The generator's ocean is nearly all shallow.** With the sea's own depth declared, the
  Earth-likeness suite reads the oceanic mode at about -390 m against Earth's -3,700: the height
  field is roughly normal and the shoreline is its 62nd percentile, so most water cells sit just
  below the waterline with a long tail down to a few trenches. Earth's floor is bimodal because two
  crusts of different density float at two levels, which is an isostatic fact and is S2's. The same
  cause puts the continental shelf at 1,000 m against Earth's 130. 2026-09-13, S1.
- **A drowned valley's catchment is measured with square cells on a 2:1 world.**
  `DrownedValleys` turns a cell count into square kilometres as the cell's *width* squared, and a
  cell of a square grid on a world twice as wide as it is tall is half that. The bar it feeds —
  `RESOLVED_SHARE_OF_A_CELL`, half a cell's width — is calibrated against that figure and comes out
  at about 39 cells of catchment at every grid, so correcting the area would double what a valley
  must drain and move every coast. Found while merging F17 onto S1's units, and left alone there
  rather than changed inside a merge: it wants its own measurement of what the coast does either
  way. 2026-09-13.

## Done

- **The temperature was a curve, so it could not hold a cap or give a continent a winter**
  (2026-09-13, W1) — the latitude curve with an exponent and two anchors is gone, and with it
  `equatorTemperatureC`, `poleTemperatureC` and `continentality`. In its place is a one-dimensional
  energy balance over 240 latitude bands, marched through 360 steps of the year for twenty years:
  insolation from the planet's own tilt, `A + B·T` out with North's slope and an offset fixed by
  Earth's 240 W/m² at 14 °C, a heat transport split between a Hadley cosine-squared and a
  storm-track Gaussian at 50°, and an albedo fitted to Earth's observed zonal planetary albedo that
  then follows the ice the model itself grows.
  Each band carries **three** reservoirs: an air column over its land (1.7 × 10⁷ J/m²/°C, soil plus
  air), an air column over its sea (1.04 × 10⁷, `c_p·p/g`), and a fifty-metre mixed layer under that
  (2.0 × 10⁸) coupled to the air above it by a bulk surface flux of 25 W/m²/°C — sensible 11.6 plus
  latent 13.4 from the standard bulk formulae at 8 m/s. The two air columns trade heat round the
  latitude circle at 8 W/m²/°C, a fortnight's exchange, and carry the meridional transport; the
  water carries none of it. A cell takes a blend of the two air columns, falling away from the coast
  with the 350 km e-folding Earth's own stations give; the water is read only where the sea freezes
  and where the march evaporates. Two readings of the year are kept per column, because Köppen's
  thresholds are monthly means and a degree-day sum is a half-year integral.
  On Earth's land fraction it reads 15.5 °C globally against 14, 26.1 at the equator against 27,
  1.7 at 60° against 0, and −15.6 at the pole against −20; land and marine air at 0/20/40/60 sit at
  26.2/26.0, 23.4/23.3, 13.9/13.9 and 1.5/1.8 against a lowland-station and a reanalysis
  climatology's 26.0/26.5, 25.0/24.5, 14.5/14.5 and −2.0/2.0, and the warmest month over the sea at
  26.7/26.0/19.2/7.9 against 27/27/19/7, inside a ±3 °C envelope. Warmest month against coldest at
  50-60°: land 38.7 °C against Earth's continental 34-38, marine air 13.0 against 8-11, water 6.9
  against 5-8. The poleward transport is 4.6/4.9/3.2 PW at 30/45/60 against Trenberth and Caron's
  5.3/5.0/3.3, and the model's cold-season ice edge lands at 60.4° N against Earth's zonal-mean 60.
  `glacialMaximumC` became a dimmer sun rather than a redrawn mask, so the poles cool 5.8 °C where
  the same forcing with the feedback off cools them 4.3, and the cap walks to 52.1° instead of
  53.6°. Sea ice is two saved masks at −1.8 °C on the *water*, the march takes nothing from a frozen
  cell, and the biome draws the pack that survives the summer: over seeds 7/42/1234 at 512 the cold
  season freezes 25–33% of the sea and reaches 55–58°, and the frozen sea takes 257–523 mm a year
  against 1,811–2,508 over the open water beside it. On the map, A6's own guard reads 40/52/51% of
  warm-current west-facing coast at 50–60° as temperate forest against its recorded 65/53/59, the
  boreal belt holds 6.7% of ice-free land against Earth's 11%, permanent ice 8.7% against Earth's
  10.1%, and `OceanCurrentTest`'s warm-against-cold coastal habitability reads +4.3/+5.5/+5.5%. The
  two anchors' old complaint — the equator 5 °C warm and 60° 3 °C cold — is answered by
  construction.
- **One unit of land elevation was six kilometres in the climate and eight everywhere else**
  (2026-09-13, S1) — `WorldScale` is now the only place a physical unit is declared: the map's
  width in kilometres, the two ends of its vertical range in metres and the years a hydraulic round
  stands for. The ruler chosen is the climate's 6,000 m, because a cell of the default grid is
  23 km across and 6,000 m is a cell mean where 8,849 is a summit; the sea gained a depth of its
  own, 10,000 m, so the hypsometry no longer has to carry the land's ruler past the shoreline. The
  two constants that disagreed took the figures they always claimed — 120 m of lowstand and a 130 m
  shelf break — read off the height field's own 16,000 m, because both are levels in that field
  rather than heights above the water or depths below it. They come to 0.0075 and 0.0081 where they
  had been 0.015 of a *measured* land relief, which was 0.0037 of the field on one seed and 0.0088
  on another; the world moved by that much, once, with the pins re-recorded in the same commit. The
  relief the Earth-likeness suite reads went from 11,913 m pooled to about 15,600.

- **A drawn river begins at its biggest headwater, not at its farthest** (2026-09-12, F15) — M1's
  finding, fixed in the tracing as it suggested: `RiverStage.traceRivers` now ranks channel heads by
  the length of the watercourse below them rather than by the flow at them, so the first course
  traced out of a catchment is that catchment's longest and every other branch is a tributary of it.
  Coverage of the watercourse each course stands for, over seeds 7/42/1234/99 at 512: **1.000**
  (worst 1.000 per seed) against 0.780 for the biggest-headwater order measured on the same worlds.
  M1's open entry, written on `main`, was struck when this merged there.

- **A trunk crossing a lake's narrow arm was drawn as a thread** (2026-09-12, F15) — the "strange
  thin squiggly connection between two thicker rivers" on seed 298405 at 1024 is lake 3: 61 cells of
  water, one cell wide, strung diagonally along the trunk of the map's biggest river system, painted
  as a dotted line of single water pixels with no river over it because the tracer stopped at every
  lake cell and the renderer refused to draw inside one. The lake's outlet was never the problem —
  measured, the accumulation below every lake on that world is 1.00 to 1.70 times the largest
  accumulation inside it, so the outlet has always carried its lake. `LakeResult.openWater` now
  distinguishes water two cells across from water one cell across, and the line runs through the
  latter. 237 channel cells over the four seeds at 512 stand under water one cell wide; 170 of them
  are now drawn, none before, and no drawn line has a break or a gap at one.

- **The rift-mouth valley: pocket, moats and terrace** (2026-09-12, E6) — the three things in the
  author's crop of 718106's southern rift turned out to be three different causes, found with the
  deposition log. The rounded-square pocket and the concentric crescent moats are the spoil's: the
  margin `headroom` allows an alluvial dam was measured in shoreline-relative units and spent as a
  height, so a dam could stand about four times higher than the no-uphill rule allows, and the rule
  it bounds has a flat for its fixed point anyway. Aggradation now stops at the slope the river
  needs to carry its load. In his window: 563 cells of ringed water before, none after; 2282 cells
  of standing water before, 1121 after, against 1535 with no deposition at all. The saw-tooth
  fringe and the forty-five degree comb are almost none of them the spoil's — 102 of the 145 cells
  are there with deposition switched off — and the dead-straight seaward front is none of it: the
  deposition-off terrain cut at the deposited world's own sea level gives the same 31-cell straight
  run, so what moved was the sea level, not the shape. That shoulder is E4's.

- **A lacustrine fan's floor is charged per cell** (2026-09-12, E6) — fixed as E5 had written it and
  reverted it: the floor is a fraction of the fan's rim, identical at 512 and held at every other
  grid. It surfaced as B4's resolution contract, where the drainage's lake area per unit of map was
  growing 2.38x between 512 and 1024; with the fan fixed it is 1.91x.

- **Square-cornered coastal lobes** (2026-09-12, E5) — the fan walk handed its own breadth-first
  step count to the acceptance rule as though it were a distance, and over eight neighbours a step
  count is the Chebyshev metric whose iso-lines are squares. The lacustrine fan, whose rule was
  "any ponded cell", therefore took the whole `2R+1` square around its inflow — the rafts with
  right-angle corners — and the sea lobe, which did shape itself by a cosine, compared that shape
  against the same count and came out a half-disc. Both now grow by Euclidean distance from the
  apex against a rim of four hashed harmonics, bent by the depth of the water they build into. At
  2048 on the author's two worlds the longest straight run of new coast falls from 34 cells to 13
  and from 30 to 15; at 512, per mechanism, the share of a fan's perimeter in runs longer than one
  lobe reach falls from 2.0%/1.8% to 0.7%/0.0%.

- **Channels pond into thin grid-bearing lakes** (2026-09-12, H5b) — the incision is now a pass of
  its own, walking the D8 tree from the outlets upstream so a cell's receiver is already final when
  the cell is cut, and refusing the part of the cut that would take it below: Braun and Willett's
  `z_i' >= z_r'`. The cap it replaces was written in shoreline-relative units and spent on the
  height field, so it had been letting a well-fed channel cell be cut by about twice the height it
  stood above its own receiver, every round. Census over the twelve rounds at 512: the incision made
  6383 / 10 / 5 such holes on 718106 / 42 / 7 and now makes none; the channel cells the map draws as
  standing water fall 1627 → 780, 106 → 54 and 323 → 265; the comb share at 1024 goes 2.4/4.9/2.6%
  to 2.2/4.4/1.7% and its bar 5% → 4.5%. The residual is the alluvial-dam item above.
- **Over-large filled basins, the drowned kind** (2026-09-12, H5b) — `SeaConfig.postCutOutlet` runs
  the breach again on the far side of the cut, over the basins the enclosure rule made, with the
  base-level limit lifted (the water behind one of these sills stands below the sea) and the cut
  continued back across the lake bed, which is the other half of a sill once the target goes below
  the old water surface. A basin whose outflow can take its sill to the waterline becomes an arm of
  the sea; one whose cannot keeps a lake below sea level, which is the Caspian. Largest drowned
  basin at 512 as a share of land: 718106 1.13% → 0.26%, 99 0.61% → 0.07%, 43 unmoved at 0.16%
  because its outflow cannot cut its sill. Asserted against the Caspian's 0.249% of land in
  `OutletIncisionTest`, `OutletResolutionTest` and `SeaLevelHistoryAuditTest`.
- **`LakesConfig.minCells` scales as an area** (2026-09-12, H5) — twelve cells at 512, 48 at
  1024, 192 at 2048, the same piece of ground at every grid; the 2048 sprinkle of ponds that 512
  never had is gone, and `OutletResolutionTest` holds at 512/1024/2048 on both of the author's
  seeds.

- **Lakes** (2026-08-23) — basins the flood had to raise are now standing water, with rivers
  running in and one leaving at the outlet. Took uphill-looking river segments from 13-20% down to
  12-14%; what is left is shallow filled ground below the lake depth threshold.
- **Straight plate-edge cliffs** (2026-08-23) — the resolution picker in both UIs changed the grid
  with a plain `copy(width = ...)`, bypassing `atResolution`, so every setting measured in cells
  stayed at its 512 value. At the desktop default of 1024 that made mountain belts half their
  proper width and the plate-base blur half its radius, surfacing plate edges as ruler-straight
  cliffs. Both UIs now go through `atResolution`, whose contract `ResolutionScalingTest` pins.
  Mountain belts also got a flat-crested profile instead of a knife edge, a width that swells and
  pinches along their length, and deeper along-strike sag so a long belt breaks into massifs.
- **GPU acceleration** (2026-08-23) — erosion runs on the graphics card behind an opt-in toggle,
  through a single `ErosionAccelerator` seam so the engine still knows nothing about hardware. 55x
  on an RTX 3070 Ti. The terrain differs from the CPU's by about six parts in a million, which
  changed no coastline cell, river or border on the seed tested — but a guarantee is not an
  observation, so a GPU world saves its terrain rather than its seed alone. The same seam replays
  that stored terrain on load, so the file opens identically on a machine with no GPU at all.
- **Android build retired** (2026-08-23) — a phone's memory ceiling capped exports at a fraction of
  what the pipeline produces, and erosion wants far more compute than a handset gives. The engine
  never depended on Android, so removing `:app` touched no generation code. Export now offers PNG
  or WebP; WebP is a quarter the size but not lossless, and `ExportSmokeTest` records what that
  costs.
- **Web front end and WebGPU** (2026-08-24) — the interface moved into a shared `:ui` module and
  `:web` runs it in a browser: local storage for saves, downloads for export, WGSL compute for
  erosion. 70x on the erosion sweeps against the browser's single thread, agreeing with the CPU to
  seven parts in a million. Bringing it up cost two false starts worth remembering: Compose puts
  its canvas inside a shadow root, so the page looked dead when it was working; and `target` is a
  reserved word in WGSL, so both shaders silently failed to compile and every dispatch was a no-op
  that returned a buffer of zeros as if it were terrain. Shader compilation is now checked.
- **Hydraulic erosion** (2026-08-24) — stream-power incision, interleaved with the thermal sweeps.
  Rivers now run in valleys they cut rather than in whatever hollows the noise left: measured, the
  banks stand twice as high above the channel as before. Flow routing moved into `FlowRouting` and
  is shared with the river stage, so the valleys erosion carves are the ones the rivers later find.
  Costs about 10 seconds at 2048.
- **Desert latitude** (2026-08-24) — deserts now sit where the horse latitudes are: 99-100% of
  desert falls in 15-45 degrees on all four audited seeds, against 43-97% before and 34% in
  aggregate. Two changes, both about mechanism rather than tuning. The latitude bands now scale the
  rain *rate* during the march instead of multiplying the finished totals, because a post-hoc
  multiplier cannot put rain back into air already wrung out by a mountain. And land now returns
  moisture to the air, scaled by that same band, so a rain shadow recovers downwind in the tropics
  and stays arid in the subtropics. `GeographyAuditTest` asserts the placement now rather than
  merely printing it.
- **Erosion** (2026-08-23) — thermal erosion, as its own pipeline stage between tectonics and sea
  level. Halves the land sitting in thin strips on seed 234475, from 0.4% to 0.2%, and gives belts
  flanks instead of walls. Critical slope is held per unit of map rather than per cell so terrain
  wears to the same shape at any resolution, and the sweep count scales with the grid for the same
  reason. It is now the most expensive stage in the pipeline at 52% of a 2048 generation.
- **Ocean currents** (2026-08-23) — surface flow is solved for rather than drawn: wind stress has
  a curl, and the stream function satisfying that curl inside a closed basin *is* a gyre. Poleward
  flow arrives warm on 85% of samples and the anomaly reaches ±7°C, which is the right order for
  a western boundary current. Sea temperature feeds evaporation and coastal climate, two new map
  layers draw the currents and the wind belts, and coastal habitability now answers to both — warm
  water for the harbour, cold upwelling on a shelf for the fishery.
- **Geography audit** (2026-08-23) — see [GEOGRAPHY.md](GEOGRAPHY.md). Rules checked with
  `GeographyAuditTest` rather than assumed. Capital siting was the one clear violation and is
  fixed; the remaining deviations are recorded below.

- **Full-world saves** (2026-09-11) — a save now stores the finished world rather than the recipe
  for it: an uncompressed JSON header (format version, config, overrides, labels, which front end
  wrote it) followed by one gzipped binary section per stage's result, laid out by `WorldCodec` in
  `:cartography` so every front end reads and writes the same bytes. Gzip takes a 1024 save from
  98.7 to 38-40 MB and a 512 save from 24.7 to 10.4 MB (2.36-2.57x); id maps compress 136-1010x,
  height fields barely move at 1.1x because they are noise by construction. The web library moved
  off `localStorage`, which cannot hold a full save, onto IndexedDB with a headers store so the
  listing never deserialises an array; a round-trip through it comes back byte-identical at 729 KB,
  85ms to write and 61ms to read. A version-2 seed-only save still opens and regenerates from its
  seed exactly as before, and — since A1 added four climate sections and broke every save written
  before it — a save missing a section a later build added now regenerates that stage and
  everything downstream instead of refusing to open. Cross-platform bit-identity between JVM and
  Wasm is no longer required for a save to be portable, so CI's JVM-vs-Wasm fingerprint comparison
  is informational now (`continue-on-error`, printed as a warning annotation) rather than a gate.

- **Climate realism** (2026-09-11) — temperature and rainfall now run twice, for the local warm
  season and the local cold one, with the thermal equator migrating `seasonalTilt` (10°) toward
  whichever hemisphere is in summer: at 35°, land swings 13.1°C through the year against 2.9°C over
  open sea. Seasonal amplitude scales with distance from water — a 50° interior swings 7.5°C more
  than a coast at the default `continentality`, 0.2°C more with it off. Wind gained a meridional
  component, trades toward the equator and westerlies toward the pole, so the rain march advects
  along a vector instead of scanning rows; riding the migrating thermal equator, that is the
  monsoon, measured unclamped at 4.07% of land on seed 26 (was 2.93% under the old rainfall clamp).
  Rainfall is calibrated to approximate mm/year — seed 42's wettest windward coast lands near
  3000mm, its desert cores at 89-190mm — instead of rescaled per world, so deserts genuinely differ
  by seed: 0.99-6.14% of land, a 6.2x driest-to-wettest spread. The temperate/continental/tundra
  boundary now reads the coldest and warmest month, Köppen-style, instead of the annual mean: 50-60°
  west-facing coasts went from 0/0/0.1% forested to 65/53/59%, while interior taiga at the same
  latitudes held at 100/97/96%. Two new biomes, Mediterranean and monsoon forest, come out of the
  seasonal contrast rather than the annual total.

- **Terrain realism** (2026-09-11) — oceanic crust now carries a continental shelf: after the
  sea-level cut, near-coast sea floor remaps onto a shallow platform, measured at 100% shallow
  within `shelfWidthCells` of a coast against 60.3% unremapped, falling to 0-2.5% beyond twice that
  distance, with zero land cells moved on any seed. Convergent boundaries are classified by crust
  pair instead of sharing one profile: an Andean margin (narrow coastal range, volcanic arc inland
  of its trench), a collision plateau (broad and flat, 3.47x broader for its height than a margin,
  against 0.72x with one shared profile), an island arc, plus continental rift valleys and hotspot
  seamount chains on over a third of oceanic plates. Hydraulic erosion now deposits what it carries
  instead of only removing it — floodplains, alluvial fans, and deltas at 68 river mouths on
  seed 42 — with incision balancing deposition plus sediment lost to the sea to the last float
  (0.0000% off). Where the provisional annual mean sits below freezing, ice carves U-shaped
  troughs, cirques and moraine-dammed basins into the valleys the rivers already cut: 12.36 lakes
  per 10k cold cells against 0.99 per 10k temperate ones (12.47x), 0.00x with glaciation off.

- **JVM and Wasm had drifted apart, and CI said so for two weeks** (2026-09-10). Every
  commit since the basin rework failed the cross-platform fingerprint check: terrain, land and
  rivers identical, but 14 realms on the JVM against 13 on Wasm. Nobody looked at CI. The cause
  was `HashSet<Int>.toIntArray()` for catchment neighbour lists - the JVM iterates a hash set in
  bucket order and Kotlin/Wasm in insertion order, so every `firstOrNull`, tie-broken
  `maxByOrNull` and flood-fill cutoff downstream quietly followed its platform. Neighbour arrays
  are now sorted at construction and HashMap picks tie-break on key; both platforms agree on all
  six fingerprint lines again. The README has carried a CI badge since 2026-08-23 - it was red for
  the whole fortnight and nobody looked at it, so a badge is not the answer. GitHub can email on
  workflow failure (Settings -> Notifications -> Actions); that is the setting to turn on.

  Fixing the order changed which realm won the growth race, which surfaced two things the old
  hash order had hidden. Seed 7 produced a realm holding 42% of the world, because the seeds of
  its largest landmass all sat at one end and the far end had a single bidder - seeds are now
  spaced two rings apart, as the culture stage already did. And a nineteen-cell sovereign state on
  seed 42 turned out to be enclave dissolution keeping the capital's sliver and giving the country
  away - a realm now keeps its largest piece and the capital moves to it.

  The lesson is process, not code: a red pipeline is only useful if somebody reads it.

- **A peoples layer** (2026-08-24) — a map of who lives where, separate from who rules where.
  Realms are grown from catchments because a state's reach is about ground it can hold; a culture
  spreads through country that *resembles the country it came from*, so it follows a grassland belt
  or a river system and stops where the climate turns, not where a border was drawn. Cost is
  measured against the hearth rather than the neighbour, since against the neighbour every step is
  a small change and a chain of small changes walks a steppe people into a rainforest.

  The point of the layer is that it disagrees with the political one, so `CultureRealmTest` measures
  that rather than assuming it: peoples span 1.4-1.9 realms each, and 70-100% of cultural frontier
  runs *inside* a country rather than along its border. Its mirror figure - peoples per realm - is
  reported but deliberately not asserted, because cultures are roughly twice the size of realms and
  most realms therefore sit inside one culture by simple geometry; on seed 7 it lands at 1.15, and
  asserting on it would be fitting a threshold to the last run.

  Two false readings on the way, both the same shape as the old WebP test. Hostile ground was
  treated as impassable, which stranded everything behind it; it is now dear to cross and drawn
  empty. And the coverage guard measured settled land against *all* land, which made a correct map
  of a world that is a third ice sheet look like it had abandoned a third of the world - it now
  measures against habitable land, where 97-98% is settled.

- **Stage reuse switched on** (2026-08-24) — `WorldGenerationEngine` could already skip any stage
  whose settings had not changed, but no app ever passed it the previous world, so every slider
  nudge re-ran erosion. The UI now hands the old world back: toggling wilderness at 512 went from
  1375ms to 170ms, and the gap widens with resolution because erosion scales worst.

  Switching it on first meant fixing it. A stage is guarded on its own config section, and three
  stages read a section they were not guarded on — erosion reads `seaLevel` (hydraulic routing
  needs a shoreline), ocean reads `climate` (the gyres are driven by the wind belts), rivers read
  `lakes` (same depression fill). Each would have served a stale result. `IncrementalReuseTest`
  compares reuse against a fresh generation for a change to every config section in turn and was
  shown to fail on all three before the fix. Its first version passed the lakes case vacuously,
  because the world it tested had no lakes — the base config now asserts it has lakes, rivers,
  realms and landmarks to compare.

- **Realms built from drainage catchments** (2026-08-24), replacing cell-by-cell expansion.
  Borders were near enough to arbitrary before — 1.14x as likely to follow a river as blank land,
  1.09x on ridge crests — and tuning could not fix it, because a cheapest-path border lands where
  two cost fields meet and expense shifts that meeting point without ever making a line *follow* a
  feature. A local relaxation pass was written and thrown away for the same reason: 352 flips in
  three rounds, 421 in ten, ratios unmoved.

  Building from catchments answers it by construction. The edge of a catchment is a watershed and a
  watershed is a ridge, so a border between two units runs along high ground because there is
  nowhere else for it to run. Three things had to be added on top of the basic idea:

  - **Trunk splitting.** A catchment contains its river, so on the first version every frontier was
    a divide and rivers became *interior* — the river ratio fell to 0.60, meaning borders started
    avoiding them. Cutting large catchments along their trunk, left bank from right, restores the
    other kind of border. Real frontiers are both: the Pyrenees are a divide, the Rio Grande is a
    river.
  - **Strait crossing.** Catchments only border their neighbours on the same landmass, so realms
    could not reach an island or a second continent at all and rendered them blank. Coastal cells
    now look a short way straight out across water for a far bank. Crossing costs something, because
    at no cost one realm island-hopped an entire archipelago and held most of the world.
  - **Enclave dissolution.** A race between realms leaves debris — ground reached late by a realm
    whose route home was then taken by someone else. Pockets you can walk out of are given to
    whichever neighbour surrounds them most; overseas islands, which you cannot walk out of, stay.
    Done on cells rather than catchments, because a catchment cut along its trunk can leave a bank
    in two pieces: a unit-level version removed almost none of them.

  Measured now: borders follow rivers 1.38-2.09x and ridge crests 1.23-1.53x, all land is settled,
  realm sizes span roughly 40:1 largest to median, and inland enclaves are down from 16 to 0-1 per
  world. `RealmSpreadTest` and `BorderRealismTest` hold the figures.

- **Distance fields are Euclidean.** The chamfer transform behind distance-from-water
  (continentality), the plate-boundary profiles and the continental shelf measured an octagonal
  metric: a walk over the grid that costs 1 along an axis and sqrt(2) along a diagonal, exact on
  those eight bearings and up to 8.2% long in between, so every contour of the field was an octagon
  and every feature cut from it inherited the facets. `JumpFloodDistance` replaces it with a
  jump-flooded Euclidean field — the source's coordinates are propagated instead of a path length,
  so the distance reported is the straight line to a real cell — and is exact against a brute-force
  nearest-source search, not merely close. Measured: on one source cell the iso-contour's eight-fold
  component falls from 8.3% of the radius to 0.4% (floor 1%); on seed 42's shelf break the chamfer
  contour stood 4.8% too far out on average, 7.6% at 22.5 degrees off the axis and 0.4% along it,
  an eight-fold component of 3.0% against nothing. Costs 23/93/367 ms at 512/1024/2048 against the
  chamfer's 6/33/110 — under 1% of a 2048 generation for all three call sites together, which is
  why the GPU half of G4 was not built. Plate assignment keeps the chamfer transform on purpose:
  only the nearest-seed label is read there, so the metric decides a partition rather than a
  contour. 2026-09-12.

## Open

- **Habitability decides which cells are on a river by a rule the map no longer draws by.**
  `NationStage.drawableRiverFlow` counts a cell as riverine where its accumulated runoff passes
  0.0006 of the world's own total, which was `RiverConfig.sourceFlowShare`'s default until R1
  retired it. The map now draws a channel where the ground can cut one, so the two answers part
  company — most visibly on a bare steep hillside, which carries a channel and has never been a
  place to live. Whether habitability should read the channel mask instead is a question about what
  a settlement wants from water (discharge it can drink and float a boat on) rather than about the
  drawing, and answering it moves every realm on every map, so it wants its own measurement and its
  own guard. The older half of this entry stands too: the figure is a constant and not the setting,
  so it was already not what a moved slider drew. 2026-09-12, restated 2026-09-21 at R1.
- **Realm governments are decided by cell counts, not areas.** `Atlas.government`'s empire and
  free-city bars count cells, so the same world exported at a finer grid promotes every realm: the
  `minCells` class of defect again. Express them as shares of the land and pin with the resolution
  contract. Found by the C2 sweep. 2026-09-12.

- **Cold currents should suppress rain-out, not only pickup.** H4 scales the moisture march's
  over-sea pickup by sea-surface temperature, which is physically right and measurably tiny
  (-0.8% on a cold coast) because the march saturates before landfall. The Atacama and the Namib
  are as much the cold sea stabilising the air as less evaporation: over a coast washed by a cold
  current, scale the release rate down (a marine inversion) so the moisture passes inland. Guard
  on a subtropical west coast with a cold current: a coastal desert appears. 2026-09-12.
- **D8 holds a bearing on smooth slopes.** On a planar hillside a drawn river runs 20-35 cells in
  one of the eight grid directions before it bends (seed 59758 at 2048, (34,1095) to (68,1095),
  drops 3e-3 to 9e-3 per cell), because steepest descent on a plane always picks the same
  neighbour. Real channels wander. The cure is a routing that carries direction between cells
  (D-infinity, or D8 with a seeded low-amplitude perturbation of the surface it reads), pinned
  by a straight-run guard against the current figure. Found 2026-09-12.
- **The trench is a plane, so the sea cuts a subduction margin in a straight line.** Restated at E7,
  which measured the window rather than inferring it: 718106's southern "rift" valley at 2048 is an
  **Andean margin**, with not one of its 38,750 cells on a continental-rift boundary, so the 31-cell
  straight run of coast and the pale bench of constant width down its west side are not E4's
  half-graben. They are the trench, whose profile is `trenchDepth * strength * narrow` — a function
  of the distance to the boundary and of nothing else, which is a plane along strike, and every
  contour of a plane is a straight line. Every other belt on the map varies along its own length.
  Giving the trench the same swell was written, run and reverted: at `rangeVariationCycles`'s
  wavelength (about 160 cells at 2048, against a bench 30 cells long) it slides the coast onto a
  different straight contour instead of bending it, and the window went from 108 cells of thin
  grid-bearing water and a 31-cell run to 146 and 39. Wants a shorter wavelength on the trench, or
  dissection of a coastal plain too flat for the hydraulic rounds to cut. E4/B2's geometry.
  2026-09-12, measured at E7.
- **A rift that meets the coast should be drowned across its whole width.** A half-graben's floor is
  a wedge and the water in a coastal one stands at the waterline, so the hinge shelf is dry: the
  author's trough on 718106 at 2048 keeps 38% of its flat floor under water and shows the rest as a
  lacustrine plain. The Gulf of California and the Red Sea are drowned right across, because a rift
  that has opened that far has thinned its crust under the whole trough. Two repairs measured and
  refused at E7: deepening the stamp (at `riftDepth` 0.35 the floor reaches 43% wet but the rift
  becomes one continuous deep axis and seed 718106's standing water halves, 17,412 cells to 8,200,
  with its deepest rift lake falling from 24.2% of the land's relief to 2.4%); and a hashed chain of
  sub-basins on the floor (takes the trough to 38% wet and leaves the world's water and hypsometry
  alone, but at every amplitude from 12% to 45% of the segment's depth it leaves a closed basin
  below the sea-level cut the post-cut outlet cannot open — seeds 718106 and 99 at 0.46-0.54% of
  their land, about twice the Caspian's share of Earth's, over `OutletIncisionTest`'s bar). Both
  are standing in for subsidence that scales with how far the rift has opened: S2 in
  `REALISM_AUDIT.md`. Note for whoever takes it: the outlet is the binding constraint, so the
  sub-basins may be affordable once a drowned basin's outlet can finish its cut. 2026-09-12, E7.
- **The ice's own added water is still charged per cell.** With the drainage's share separated out,
  `GlaciationTest`'s resolution case measures the ice adding 2 cells of standing water at 512 and 46
  at 1024 on seed 718106 — nearly six times as much per unit of map, on a count too small at 512 to form a
  ratio. The drainage's own share keeps the 2.0 contract at 1.92. B4's. 2026-09-12.
- **Saw-tooth lake shores and forty-five degree bars.** Thin one- and two-cell bars of water along
  the flow grid fringe the lakes in 718106's southern rift at 2048: 107 cells in the author's
  window, of which 102 are there with deposition switched off entirely, so they are the depression
  fill's or the routing's rather than the spoil's. Not yet traced further. 2026-09-12.
- **Lakes never feed the moisture march.** Lakes are decided two stages after the climate, so no
  lake evaporates into the air above it: no lake-effect rain downwind of a Caspian or a Great
  Lake, and the interiors that used to drink from H5's spurious sea pockets are drier now that
  those are land. A provisional lake mask from the filled surface before the march (the way H2
  runs a provisional climate before the ice) is the cure; W3 in REALISM_AUDIT.md. 2026-09-12.
- **Nested crescent lakes down a hotspot cone.** On 718106's southern rift at 2048 a cone carries
  a round crater lake and, below it, a stack of crescent-shaped lakes that are the cone's
  terraces ponded at successive fill levels. Whether the terraces are E3's supersampled stamp or
  the fill stepping down a smooth slope is not yet measured. 2026-09-12.
- **The lowstand roughens every coast.** H5's base-level fall cuts every shoreline, not only the
  ones a river reaches, so the rise floods a fringe of small bays round whole continents. Earth's
  drowned coasts are indented where the rivers are and straight where they are not; the repair is
  to scale the stand's effect by local drainage, or to let K1's wave climate smooth the coasts that
  drift would (REALISM_AUDIT.md). Recorded by H5, 2026-09-12.
- **Hotspot cones on land are eight-sided.** At low ocean coverage an oceanic plate's hotspot chain
  surfaces as volcanoes and each reads as a faceted cone. Seen on seed 718106 at 62% ocean, 2048.
  E3 showed the cause is not the distance metric — the stamp's falloff was always Euclidean — and
  supersampled the stamp; G4 has since made every other distance field Euclidean too, so if the
  facets are still there the remaining suspect is erosion cutting radial gullies along the eight D8
  bearings down a symmetric cone. Not re-checked at 2048 since E3.
- **The resolution-consistency guard no longer has a statistical form.** It began as mean slope
  away from plate boundaries; erosion invalidated that, and reintroducing the bug it was written
  for showed it no longer caught it. Measuring belt reach directly does not work either, because a
  256 grid and a 512 grid genuinely are different worlds once erosion shapes them. `PipelineTest`
  now reports the figure instead, and `ResolutionScalingTest` pins the contract that actually
  matters. A metric that discriminates the real bug would still be worth having.
- **Skia is two thirds of the web payload** — 8.4 MB of 12.4 MB raw, 3.2 MB of 4.4 MB on the wire.
  Nothing the website can do moves the first-visit cost as much as shrinking this would. No obvious
  lever: it is Compose's renderer, not ours.
- **The web app does not display properly at mobile resolutions.** Confirmed by the author on a
  real device 2026-08-25, no longer merely untested. Accepted for now — the site warns small touch
  screens off rather than blocking them. The interface was laid out for a desktop window: a fixed
  320px control column either side of the map does not fit a phone, so a real fix is a layout that
  collapses the panels rather than a tweak.
- **Match the visual style to the site.** Requested 2026-08-25 for the next version. The app
  currently uses stock Material 3 colours, which sit oddly next to the site it is embedded in. The
  theme is set in one place — `MaterialTheme` in the web and desktop entry points — so this is a
  colour scheme rather than a rewrite. Worth taking the palette from the site's own CSS rather than
  eyeballing it, and worth checking it against the nine map styles, which carry their own colours
  and should probably stay as they are.

- **Tectonic drift.** Requested 2026-08-23 as a stretch goal and not started. Plates already carry
  a drift vector, but it only classifies boundaries — nothing moves. Simulating it would mean
  stepping plates across several frames and accumulating the terrain each step, so a range records
  where a boundary *was* as well as where it is, and a coastline can carry a rifted margin that
  matches another continent's.
- **Territory editing on the map.** `WorldOverrides.territory` is saved, applied and rendered, but
  there is no gesture to reassign a cell. Realm statistics also would not recompute after an edit.
- **8192 exports.** Untested. 4096 peaks at 2.0GB, so 8192 would want roughly 8GB — inside the
  12GB heap, but the FFT buffers may not be.
- **Landmark placement is single-threaded** at ~18% of generation time. Parallelising it means
  replacing the sequential RNG in its per-cell scoring with a position-derived hash, which would
  change which sites a given seed produces.
- **Island-arc ridges run dead straight** where a real arc bows convex toward the subducting
  plate — seen on seed 1234 as a bar across the centre and a spine down the north-east. Worth a
  curvature term along strike when someone next opens `PlateStage`.
- **Faint rainfall banding at circulation-belt seams.** Seed 42's annual rainfall carries a visible
  horizontal band across the northern continent where two belts meet. A wind-band seam, worth a
  look when `buildWind` is next opened.
- **The sea never drowns a glacial trough.** A fjord is a trough the sea has flooded, and flooding
  one means re-cutting the sea-level percentile, which moves every other coastline on the map. So
  `GlaciationStage` grades its marine troughs down to the waterline instead: the depth and the
  islands are there, but high-latitude coasts get none of the long narrow inlets fjords actually
  are. See GEOGRAPHY.md's "Known deviations".
- **The colour-blind style draws a coastal desert dark olive.** Its ramp starts at #2B2E1C, whose
  green channel is three of 255 above its red, so a desert at the shoreline is the one place on any
  style where sand reads as vegetation — and it must, because that ramp is ordered by lightness and
  cannot spend any of it on climate without breaking the promise it exists for. Measured at F13:
  45% of the desert cells of seed 234475, which is the same 45% it was before the chunk. Fixing it
  properly means a second ordered ramp for arid ground whose stops are also 8.00 CIEDE2000 apart
  from each other under both deficiencies, which is a palette exercise rather than a rendering one.
- **The sky model doubles the processor's raster.** Twenty-four horizon samples a land pixel against
  the single lamp's four central differences: about 0.44 s against 0.21 s at 2048 and 1.65 s against
  0.81 s at 4096, measured on seed 42 at F13. The desktop draws exports on the graphics card, where
  it costs nothing measurable, but the browser has no raster device and pays it in full. If it ever
  matters, the horizon is separable — one sweep along each of the eight bearings with a running
  maximum is O(1) a pixel instead of three samples — at the cost of the two paths no longer being
  the same arithmetic per pixel.
- **Aerial perspective was written for F13 and taken out again.** The plan asked for the low ground
  to be veiled slightly toward the paper; it was built, rendered and reviewed, and it cost the
  relief more contrast than the haze it stood for was worth — aerial perspective is a painter's
  device for an oblique view, and a map is a plan. If it ever comes back it should be a style's own
  decision, declared like the biome wash, rather than a physical claim about the air.
- **The graticule's figures are ink on the sheet, so at fit they shrink with it.** Found by F14.
  Everything an export needs is on the sheet — the grid, the figures, the scale bar — which is the
  right answer for a printed chart and means that on screen at whole-world scale a 2048 sheet's
  eleven-pixel figures come down to five. That is what a printed map does too, and the reader zooms;
  but a live view could draw the figures in screen space at a constant size instead. It would need
  the graticule's geometry projected into the pane by the front end and a second drawing site for
  the numerals, which is exactly the divergence `MapImage`'s one Skia path exists to avoid, so it
  waits for a reason better than tidiness.
- **The overlay is baked into the sheet, so zooming past 1:1 magnifies the ink with the raster.**
  Also F14. The traced coast is a line rather than a staircase at every zoom, which is the win; but
  it is a line drawn at the sheet's resolution, so at four times zoom it is a soft two-pixel line
  rather than a crisp one. Drawing the overlay in screen space over the scaled raster would fix it
  and is the same second-drawing-site problem as above. A cheaper half-measure, if it is ever worth
  it: re-raster at the zoomed resolution over the visible window only.
- **The traced coast does not wrap the east-west seam.** F14 traces on the grid as a sheet, so a
  landmass crossing longitude 180 has its outline stopped at the two edge columns rather than
  carried round. The raster's own coastline pass does wrap, so the difference is one column of
  pixels at each edge and nothing has been seen of it; a wrapping tracer would have to split every
  ring that crosses the seam for drawing anyway. `RiverSegment` already carries the split-at-the-seam
  trick if someone wants to copy it.
- **The scale bar is drawn on every picture export.** F14 puts it on anything that goes through
  `Exporter.export` or the web's equivalent, because a PNG has no legend beside it. Nobody has asked
  for a way to turn it off; if someone wants a clean plate, it wants a switch beside the format
  chips rather than a Cartography mark, since it is a property of the export and not of the map.
- **The energy balance costs the browser a second or two of every generation, whatever the grid.**
  It solves 240 bands through 360 steps of twenty years, and a generation solves it eight or nine
  times: once for the map's own climate, once for the ocean stage's sea-surface temperature, once
  for the provisional field the glaciation stage freezes on, and five or six more inside the secant
  search that finds the dimmed sun `glacialMaximumC` asks for. None of that shrinks with the map,
  so on a 128-cell world in Wasm it is nearly the whole generation: `GenerationProgressTest` was
  timing out against Mocha's two-second default until W1 hoisted the band albedo out of the step
  loop, and the 2.0.3 stages pushed it back over. The timeout is sixty seconds now, which is what a
  timeout should be for a case that generates a world.
  Two repairs, both larger than the merge they were found in. Three of those solves are the *same*
  computation with the same inputs and could be one, which needs the zonal climate threaded from
  the engine through the ocean, glaciation and climate stages rather than each solving it again —
  the comment on `ClimateStage.zonalClimate` explains why it is solved rather than cached, and that
  reasoning is right about staleness and wrong about the cost in a browser. And the spin-up is
  longer than it needs: twenty years leaves a residual of 0.0001 C where the coupled column's own
  memory is about three years, so twelve would leave 0.002 and save two fifths of the time. Both
  move every number in every world, so neither belongs in a merge. 2026-09-13, W1.
- **Half the land is tundra, and it is the hypsometry rather than the climate.** Over seeds
  7/42/1234/99 at 512, tundra takes 55/43/47/42% of the ice-free land, pooled 47%, against Earth's
  6% (Olson et al. 2001: 8.1 of about 135 million km² ice-free). Boreal forest is 7.8% against
  Earth's 11%, which is right, and the zonal temperatures the same worlds are built on sit within a
  degree or two of the reanalysis at every latitude from the equator to 70° — so the belts are in
  the right places and the tree line is not. What puts them there is the ground: M1 measured this
  map's land standing 1200–1700 m above its own sea against Earth's 840, and a lapse rate of
  6 °C/km takes three to five degrees off nearly every land cell. `ColdBiomeShareTest` prints both
  shares per seed and pooled and asserts only the boreal one, because no factor a guard could state
  would both accept 47% and mean anything. S2's hypsometry is where this is settled.
  2026-09-13, W1.
- **The ice makes almost no lakes any more, because it cuts almost no valleys.** `GlaciationTest`'s
  two glacial-lake clauses are findings from W1 rather than assertions. Pooled over seeds 42, 7 and
  718106 at 1024 — pooled because two lakes against one on one seed is not a density — the ice
  raises cold-country lake density from 0.21 to 0.28 per 10k cells, where the clause asks for three
  times, and the iced zone ratio reaches 1.70 against a bar of 2.5. The budget line says why:
  `trunks=0 cirques=0 moraines=0` on seed 42 at 1024, with 31,453 cells channelled and *nothing
  refused*, so no flow path is even proposed as a trough. The candidate test asks that a path carry
  `minCatchment` of the whole frozen area's ice, and W1's energy balance replaced a few
  concentrated mountain ice fields with one diffuse 41,000-cell sheet, under which no single valley
  can clear that share. The climate itself is not the complaint — the pooled permanent-ice share is
  8.7% of land against Earth's 10.1% — so the repair is `GlaciationStage`'s catchment thresholds
  re-derived against a mask of that shape, with the comb and lattice clauses (which still pass)
  protecting the D8 artefacts while it is done. 2026-09-13, W1.
- **A drowned basin is over the Caspian cap again, and the cap is the thing to look at.**
  `OutletResolutionTest`'s clause on basins below the sea-level cut was an assertion from H5b and is
  a printed finding again from W1: seed 42's largest walled-off hollow at 2048 went from 3,453 cells
  to 4,924 — 0.86 times the Caspian's share of its land to 1.23 — because the glacial mask is now
  struck on a colder world's own rainfall and the ice carved somewhere slightly different. Nothing
  about the outlet notch moved and the post-cut pass is not short of passes. The clause could only
  ever discriminate while the two sample hollows sat under an Earth figure, and that figure's
  meaning on a world a seventh of Earth's size is the open question two entries below this one:
  1.23 times the Caspian's *share* of a world this size is a fifth of the Caspian's actual area.
  Whoever settles the cap should settle this clause with it. The largest lake in the land is still
  asserted against the same figure and is well under it, at 0.16%. The same case's "at least one
  seed still forms a ratio" clause is a finding now too, and for a plainer reason: it was passing on
  seed 59758 reading 0.501% standing water against a floor of 0.500%, and W1's climate moved it to
  0.371% while moving seed 42's the other way, 0.120% to 0.210%. The spread that floor protected was
  retired by S1 in favour of `ScaleFreeTest`, so what it guarded is already measured elsewhere.
- **The marine air swings a third too far, and the mixed layer has one depth all year.** W1's third
  pass gave each sea band an air column over a fifty-metre slab, coupled by a bulk surface flux of
  25 W/m2/K, and the water's own year came right: 6.9 C from warmest month to coldest at 50-60
  degrees against Earth's 5-8. The air over it did not quite. It swings 13.0 C where Earth's
  zonal-mean marine air swings 8-11, and the reason is structural rather than a constant: with a
  bulk coefficient of 25 against the slab's own inertia the air can only hand the water about half
  its amplitude, so the excess has nowhere to go but the air. Earth's air-sea difference over the
  open ocean is about a degree all year, which is a coupling nearer 100 W/m2/K than 25 — the surface
  flux is mostly radiative and evaporative and only weakly proportional to the temperature
  difference, which a bulk formula linearised about one wind speed cannot say. The other half of it
  is the fixed depth: a mixed layer that shoals to 25 m in summer and deepens past 200 in winter
  damps the winter far more than the summer, and a single depth cannot. Both belong to whoever next
  opens the ocean's side of the energy balance; neither is worth a fitted fudge. 2026-09-13, W1.
- **The mid-latitude ocean is a degree or two cold and the pole two or three warm.** W1's third pass
  split the diffusivity into a Hadley cosine-squared and a storm-track Gaussian at 50 degrees, which
  moved the 45-60 band from 3-4 C below the reanalysis to within 1-2 and put the pole at -15.6
  against a nominal -20 and an ice edge at 60.4 N against Earth's 60. What is left is small and
  consistent: 11.2 C at 45 against about 12.5, 8.3 at 50 against 10, 5.0 at 55 against 7.5, and
  -13.1/-12.7 over land and sea at 80 against -15/-16. The shape between the storm track and the
  pole is the part still being carried by one Gaussian and one floor, and a transport read off the
  observed eddy flux rather than fitted to five latitudes would settle it. 2026-09-13, W1.
- **A band has no internal geography.** `EnergyBalance` gives each latitude a land column and a sea
  column but nothing tells it that a band's land is an island in its sea, so a band that is one per
  cent island carries a fully continental land column. The map is saved from that by the marine
  blend — an island is entirely within reach of water and takes the sea column's year — but a large
  island in a wide ocean is a case where the two disagree, and a within-band exchange scaled by how
  broken up the band's land is would close it.
- **Nothing falls at the pole, so the ice edge there is a threshold cutting a field two to three orders of magnitude too small.** With `BoxBlur`'s arithmetic repaired (X1b) the frozen mask on 969495 at 2048 still flips 209 times over rows 1975-1995 and columns 0-299, and the reason is no longer rounding: the provisional accumulation over that strip is **0.005 to 0.16 mm of water a year**, against the 21-23 mm measured at Vostok and Dome A that `SnowBalance` cites as the driest accumulation on Earth that sustains a sheet: 130 to 4,600 times under, the strip's top against Vostok's bottom and its bottom against Vostok's top. `SMALLEST_MEANINGFUL_BALANCE_MM` is 0.01 mm, so it falls *inside* that distribution instead of under it, and which polar cell carries ice is decided by a hundredth of a millimetre either way. Raising the floor would be fitting a threshold to a field that is wrong, so it was not touched. Where to look: the march's cold cap floors a parcel at `MIN_COLD_CAP` 0.15 of saturation and the polar band factor multiplies the rain rate, and between them a polar land cell records a rain of order 0.1 mm a year where the raw cold-half march delivers 0 to 5.7e-13 mm, which is nothing — a half-year that rains nothing at all over a continent is the thing to explain first. Beside it, the `TODO` entry above on the march's water conservation, whose cold-cap leak is in the same expression. Whoever opens it should re-measure the strip with `X1b`'s own table (the field-by-field median-crossing count over rows 1975-1995) and the ice share of land on the six audited seeds, which is 7.6% pooled at 512 against Earth's 10.1%. 2026-09-22, X1b.
- **The polar ice's surface is one circular profile per notch in its margin, and the notches are one cell wide.** Measured for X1a on the same strip of 969495 at 2048 after X1b's repair: 9,706 frozen cells are governed by **542 distinct margin sources**, one per eighteen cells, 27% of east-west ice pairs are governed by different sources, and 2,632 pairs carry a surface step over 50 m. On the governing-source overlay and the envelope's surface, the envelope is doing exactly what I3 built it to do — a lower envelope of profiles, creasing at ice divides rather than stepping — but on a margin fringed with one- and two-cell teeth every tooth is a separate profile origin, so the surface reads as a rank of semicircular fans rather than as a sheet, which is the "row of semicircular shapes" in the original report. X1b did not touch the envelope: repairing the blur took the sources from 758 to 542 and the 50 m steps from 3,818 to 2,632 and left the arcs. Whether the answer is a margin that is a body rather than a fringe (X1a's occupancy mask), a minimum body size before a frozen cell seeds a margin, or a profile whose datum is smoothed along the margin, is X1a's to decide and to guard. The nearest-margin distance and the raw `bed + thickness` were not overlaid, so that each arc's radius is its source's margin distance is inferred, not measured. 2026-09-22, X1b.
