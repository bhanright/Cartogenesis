# To do

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
- **There are three times as many lakes as there were, and they are the right area.** Measured over
  the five standard worlds at 512 after S2's third pass: 1.73% of land under lakes against Earth's
  1.48% at this cell area and the pre-S2 generator's 0.54%, in 39 lakes against its 13. So the map
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
  Finland, the Canadian Shield and western Scotland are flat, ragged and rock — which the plan's H3
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
- **`RiverConfig.maxRivers` is a count of courses, so the drawn network thins on a finer grid.**
  Four hundred courses at 512 and four hundred at 2048, over sixteen times the cells: the map draws
  a smaller share of its own network the further in it is generated. `ScaleFree` measures drainage
  density off the terrain's channel network rather than the drawn one for exactly this reason, and
  says so. The cure is a cap that is an area or a share rather than a count, and it belongs with
  R2's rivers-drawn-as-rivers. 2026-09-13, S1.
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

## Done

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

- **Habitability reads the river default, not the river setting.** `NationStage.drawableRiverFlow`
  pins `RiverConfig.sourceFlowShare`'s default rather than reading the setting, so a world whose river
  slider has been moved builds its habitability against a different river density than the map
  draws. Found by the C2 sweep, named and documented, not fixed (a behaviour change wants its own
  guard). 2026-09-12.
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
- **Match the visual style to the author's site.** Requested 2026-08-25 for the next version. The app
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
