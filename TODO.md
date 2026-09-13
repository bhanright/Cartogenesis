# To do

- **A lake can be a dead-straight diagonal bar.** On seed 298405 at 1024 one of the eight lakes —
  53 cells at (509,860), every one of them within 1.2 cells of a single straight line — is drawn as
  a rectangle laid on the diagonal with square ends, in a straight-walled trench beside it. It is
  the artefact William called "this diagonal rectangle section of river" (F15). Diagnosed and not
  fixed: it survives with the outlet incision off, with deposition off and with the post-cut outlet
  off, at the same place and the same size each time, and disappears only with erosion switched off
  altogether — so what cuts the trench is the ordinary stream-power incision, and what makes it
  straight is D8 itself, which on ground smooth at the cell scale (here the apron below a range)
  takes the same neighbour twenty cells running. The reach then ponds behind its own lip, the fill
  raises it, and `findLakes` calls it standing water. Rare: a census of straight bars of 20 cells or
  more finds 1 on 298405 at 1024, 0 on seeds 7 and 42 at 512, 1 on 1234 and 2 on 99. The repair is
  to break D8's straight-line bias on smooth ground — `LakeWaterBalance.jitter` already does exactly
  this inside an endorheic basin's re-routing, and the same idea in `FlowRouting.flowDirections`
  would do it everywhere — but `FlowRouting` is shared with erosion, so it moves every world's
  terrain and belongs in a chunk that can render and review the lot. 2026-09-12.
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

## Done

- **A drawn river begins at its biggest headwater, not at its farthest** (2026-09-12, F15) — M1's
  finding, fixed in the tracing as it suggested: `RiverStage.traceRivers` now ranks channel heads by
  the length of the watercourse below them rather than by the flow at them, so the first course
  traced out of a catchment is that catchment's longest and every other branch is a tributary of it.
  Coverage of the watercourse each course stands for, over seeds 7/42/1234/99 at 512: **1.000**
  (worst 1.000 per seed) against 0.780 for the biggest-headwater order measured on the same worlds.
  The open entry is on `main`, where M1 wrote it, and should be struck when this merges there.

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
  within `shelfWidth` of a coast against 60.3% unremapped, falling to 0-2.5% beyond twice that
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
  Giving the trench the same swell was written, run and reverted: at `rangeVariationScale`'s
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
- **The latitude curve runs a few degrees off at its anchors.** Checked arithmetically after A6:
  the equator anchor sits about 5°C warm (32°C modelled against a real ~27°C, a pre-existing
  anchor) and 60° about 3°C cold even with a warm current. Neither has been shown to matter to a
  render; worth revisiting if a future chunk touches `buildTemperature` for another reason.
