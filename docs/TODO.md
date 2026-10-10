# To do

- **The atlas overhaul: what the Earth-sized planet left for the realms, peoples and landmarks
  (K2).** K2 moved the default planet to Earth's 40,075 km and the geography with it; the atlas was
  out of its scope and was only kept from crashing or hanging. What the overhaul must revisit:
  - **Shares of the land that should be areas or discharges:** `NationsConfig.maxBasinShare` and
    `minBasinShare`, `CulturesConfig.maxRegionShare` and `minRegionShare`,
    `BasinRealms.MIN_ISLAND_REALM_SHARE`, the landmarks' spacing, `NationsConfig.riverBorderShare`
    (a share of the largest river's flow; a frontier river is big in cubic meters a second) and
    `NationStage.RIVERINE_FLOW_SHARE` (a share of the world's runoff; a settlement wants a
    discharge).
  - **The counts per world:** `nationCount`, `cultureCount` and `LandmarksConfig.count` are counts
    of a world, so on the Earth-sized planet a realm is an Earth continent's size: seed 42 at 1,024
    rows draws 15 realms, the largest 90 Mkm² and the median 6.6 (the probe at K2's head).
  - **The ranks:** `Atlas.IMPERIAL_KM2` (1.65 Mkm²) and `CITY_STATE_KM2` (0.41 Mkm²) were set as
    shares of the 12,000 km world, so on the Earth-sized planet nearly every realm is an empire and
    none a free city.
  - **D4 and D5 of the Earth-size audit:** `NationsConfig.reach` and `seedSpacing` are never read;
    realms fall under their own floor at Earth's size (C-42 had one of 670 km²), cause not isolated.
  - **The realm known failures:** `ClearStyleTest`'s realm fills under the margin, and
    `OceanCurrentTest`'s warm coasts, which read the realms' habitability.
  2026-10-08, K2.
- **The plate partition has one population where Earth has two (K2).** Bird's PB2002 model (2003)
  has 52 plates, 14 large and 38 small, and Morra, Seton, Quevedo and Müller (EPSL 373, 2013) find
  plate sizes on Earth fall in two populations, a few large plates and a power law of small ones;
  Sornette and Pisarenko (2003) fit the small ones' tail. The generator draws a warped Voronoi of
  fifteen seeds: seed 42's plates on the sphere run from 2.77 to 0.28 of the 34.1 Mkm² mean, where
  Earth's Pacific is 3.0 of it and its minor plates go down to 0.007. A partition that draws the
  large plates and then the small ones at their boundaries, by those distributions, is its own
  chunk, and moves every world. 2026-10-08, K2.
- **The disc relief window has no graphics-card path (K2).** `GlaciationStage.localRelief` takes
  the largest and smallest height within a disc on the ground (`ReliefWindowShape.DISC`, which
  replaced the octagon rule 13 bans), one half-width slide per distinct row folded down the column.
  Neither the octagon nor the disc ever had a device kernel; the project's rule asks for one behind
  the accelerator seam, held to the processor's answer cell for cell. 2026-10-08, K2.
- **A lake's evaporation is Thornthwaite's, which under-reads open water (K2).** K2's lakes take
  Budyko's share of their catchment's rain (`LakeWaterBalance.runoffShareOfRain`), which took seed
  42's largest lake at Earth's size from 2.79 Caspians to about one. What is left over a Caspian in
  square kilometers sits in closed basins on a dry equator (seed 42's largest, at 5.7 S, takes 311
  mm a year on it), and the water loses only Thornthwaite's potential evaporation, a land formula
  read off air temperature: 979 mm a year on that lake at 24 and 18 C, where an open water surface
  in that warmth loses more: Lake Victoria, at about the same temperature, some 1,500. Penman's combination equation, or a lake-evaporation
  figure on the ground, is the next step; the Caspian guards pass on the share of land without it.
  2026-10-08, K2. **Closed at C1b**: a lake loses Penman's open-water evaporation (1948, with the
  1956 wind function; McMahon and others 2013) from the climate's own air, and Thornthwaite is gone.
  What the lakes do on C1b's wetter land is the entry "The lakes on the wetter land".
- **The glacier strength is the square root of the catchment's share, where Bahr's scaling gives
  0.375 (K2).** `GlaciationStage`'s `strength`, which sets a trough's depth, half-width and ice
  thickness, is `sqrt(ice / fullCatchmentCells)`; a valley glacier's thickness grows with its
  area to the 0.375 under Bahr, Meier and Peckham's volume-area scaling (1997), which K2 cites for
  the catchments' areas. Moving the exponent moves every glaciated world. 2026-10-08, K2.
- **The drawing's constants and the site's figures were read off the 12,000 km planet (K2).** The
  guards that hold a drawing constant at the cell size it was set on build their worlds on
  `CalibrationPlanet`, the 12,000 km world with fourteen plates: the gallery (`TestWorlds`), the
  isobaths, the relief shading's floor, haze, ordinary ground and exaggeration, the river pen's
  span, the engraving's ink, the thermal aspect, the shelf contour, the comb guard and the
  boundary-pair profiles. Three of them are K2 known failures, since even that planet's ground
  moved with K2's figures (the exaggeration reads 41 where 37.5 is declared; the slope floor
  0.054 against 0.06; ordinary ground 0.9106 against 0.8963). Each wants re-deriving on the
  Earth-sized planet's 19.6 km cells, where the river pen is about one and a half pixels on the
  2,048-pixel sheet, and the site's figures with them. 2026-10-08, K2.
- **The everyday tier's 512 rows are 39 km cells on the Earth-sized planet (K2).** Guards whose
  figure is a few cells of ground read the grid there: `GroundTextureTest`'s drainage density
  (0.0067 km/km² against 0.0136 taken on 23 km cells), `GroundIsotropyTest`'s coastline, and the
  operators and figures K2 moved to the calibration planet: the ground's texture, read against a
  box 94 km in half-width, cannot tell the texture rule from the grid on 39 km cells, where the
  control with the rule off already reads inside the record. Whether the everyday tier should be 1,024 rows on
  the Earth-sized planet is a budget question. 2026-10-08, K2.
- **The Earth-sized planet has half Earth's lowlands (K2).** Seed 42 at 1,024 rows holds 12.8% of
  its land under 200 m, 19.9% at 200-500 m and 32.6% at 500 m-1 km, where Earth's hypsometry gives
  25, 22 and 22; the 1-2 km band is 27.7% against 19. The plains stand too high, and the lowstand,
  the deltas and the margins are the stages to read. 2026-10-08, K2.
- **The Earth-sized planet's physical known failures (K2).** Recorded, each with its figure, where
  a guard set on the 12,000 km planet's worlds reads the Earth-sized planet's and misses Earth's
  figure (C1b's worlds pass and arm six: the cold coast, the dome flow, the clamp, the
  doubled slant, the dry basin's endorheic lake and the Caspian clause's over-large starts): `CurrentFeedsRainTest` (seed 7's sample cold coast is shorter than the sample asks, and
  its warm sample coast comes out half a percent drier with the coupling on, 3.3% at C1b),
  `GlacialBasinShapeTest` (the ice leaves ground level to a meter over more than Salar de Uyuni's
  10,582 km²), `GlaciationCombTest` (one ice-made lake a straight one-cell line along a D8
  bearing, rule 13), `GroundTextureTest` (the drowned continental crust lies 0.47 within 800 km of
  its edge against Earth's 0.80: the continents are flooded rather than shelved), `IceSheetTest`
  (seed 7's sheet flows outward a hair short of what its dome owes), `IsostasyTest` (the moat round
  the ice is under a fifth of Airy's share), `MeridionalWindTest` (seed 9's monsoon coast 0.10% of
  land), `MoistureBudgetTest` (the continental recycling ratio about 0.5 against Earth's 0.30 to
  0.45), `ReceiverClampTest` (the clamped incision leaves a channel cell below its receiver, which
  the FastScape bound says it cannot: a numerical defect to trace; passing at C1b and armed, the
  cause untraced), `SeaLevelHistoryTest` (seed
  42's lowstand drowns no valley, and pooled the lowstand leaves 1.06 times the estuary mouths
  where a drowned valley owes 1.5) and `LakeWaterBalanceTest` (the dry basin's lake spills into a
  lower hollow that keeps no water, a playa downstream of a lake). And three instruments: `OutletIncisionTest`'s Caspian clause sees no seed start with a lake over the
  Caspian's share, since K2's Budyko runoff, so it cannot show the notch taking one down and wants
  seeds that do; `GridShapeTest`'s doubled-slant control moves no band of the Earth-sized
  planet's rain past the spread measured on the 12,000 km planet's worlds, which wants
  re-measuring there; and
  `RibbonLandTest`'s strip is a share of the map's width (`w / 170`, 71 km of half-width on the
  12,000 km planet and 236 km on Earth's), so the case runs on the calibration planet until the
  strip is a width on the ground and the Earth-sized world is held to Earth's 0.7 to 1.0% of land.
  2026-10-08, K2.
- **The hotspot trails' plate turns are drawn from one measured turn (K2).** A plate turns at each
  of the planet's reorganizations by an angle drawn uniformly up to the Hawaiian-Emperor bend's 60
  degrees either way (`PlateStage.plateTurnRadians`): Earth gives the Pacific's turn well
  measured and no distribution of them. The plates' present drift alone builds the belts, so the
  turns live in the trails and nowhere else; a plate history that turns its plates at the same
  reorganizations would carry them into the belts, the sutures and the old crust (the tectonic
  history entry). 2026-10-08, K2.
- **Land rain moves with the planet's size, wetter on a small one (K1).** With the conversion on
  the ground, seeds 42, 969495 and 7 at 256 rows rain 1.51 times as much on the land of a 6,000 km
  planet as on the 12,000 km one, and 0.94 times on a 24,000 km one (99.5th percentiles 1.32 and
  0.94). `PlanetWidthRainTest` holds the change within the seeds' own spread, 1.63. K2's figures
  for the Earth-sized default are in the entry below. 2026-10-05, K1.
- **The Earth-sized planet's land is dry, and a figure read per cell of the map reads it drier
  (K2).** Mean land rain at 1,024 rows is 445, 353 and 505 mm on seeds 42, 969495 and 7, weighted
  by area on the sphere, against Earth's 715; read per cell of the map, as the Earth-size audit and
  K2's first figures read it, 303, 260 and 361, because a cell at 75 degrees stands for a quarter
  of the ground a cell at the equator does and the polar land rains 63 to 69 mm. The rest of the
  shortfall is three things, none of them a law K2 moved, and none tuned:
  - **The interior.** Land more than 1,000 km from the sea rains 17 to 221 mm (per cell) and is 30%
    of the land's cells on the Earth-sized planet, against 6% for the same seed on the 12,000 km
    planet with fourteen plates, which rains 552 mm on the sphere. The march's only moisture source
    is the sea (GEOGRAPHY.md, "The interior is drier than Earth's"), and a planet with Earth's
    continents has Earth's interiors.
  - **The equator.** Land within 10 degrees of it rains 808, 977 and 1,120 mm, where Earth's
    equatorial land, the Amazon, the Congo and the islands between Asia and Australia, takes
    1,500 to 2,500.
  - **The sea.** The ocean takes 4,110 to 4,190 mm a year on the sphere against Earth's about 1,100,
    eight to twelve times the land's figure where Earth's is one and a half: the march rains most of
    its water out before it reaches a coast. Whether the millimeter conversion, which was set on the
    land, means anything over the sea is the first question.
  Plate count is not the cause: seed 42 with 14, 15 and 30 plates rains 483, 445 and 508 mm on the
  sphere, the continents' draw moving rather than a trend. The same per-cell reading puts the land
  at 38% of the map's cells and 34 to 37% of the sphere (Earth 29%, the sea-level setting being a
  share of cells), and the ice-sheet biome at 3.9 to 4.1% of the land's cells and 1.8 to 2.5% of its
  area (Earth 10.1%). The guards that hold a figure to Earth's read it per cell; reading them on the
  sphere is a change of its own. 2026-10-08, K2.
- **The sheet's streamlines are a count of cells (K1).** `GlaciationStage.STREAMLINE_CELLS`, six
  cells of flow line, is 141 km on the 512 by 512 grid's 23.4 km cells and 35 km on the 5.9 km cells
  of 1,024 rows, against hummocks 154 km long, so the drumlins' elongation falls as the grid is
  refined. Restating it on the ground moves every glaciated world. 2026-10-05, K1.
- **The coast pen is a share of the sheet (K1).** `MapRasterizer.COAST_SHARE_OF_MAP_WIDTH` is one
  pixel of the 2,048-pixel sheet it was matched on, where the river pen is now a width on the
  ground (`RiverPen.FULL_STROKE_KM`); on a planet three times as wide the coast is drawn three
  times as heavy against its rivers. `GroundFiguresTest` lets it stand by name until this is
  decided. 2026-10-05, K1.
- **`RiverConfig.shortestDrawnCourseKm`'s print scale is the 12,000 km world's (K1).** Its KDoc
  reasons from a whole world printed at about one to forty million, which is a sheet 30 cm wide for
  the 12,000 km world and a meter for Earth's; the 100 km it sets is a length on the ground, so on a
  larger planet the shortest drawn course is a smaller part of the sheet. Whether it should follow
  the sheet or the ground is the same question as the pens'. 2026-10-05, K1.
- **The relay crest's KDoc gives its wavelength as 100 km; the constant is 200 (K1).**
  `PlateStage.RELAY_CREST_WAVELENGTH_KM` is 200.0, the lattice's cell on the ground, and the KDoc
  above it says "its longest wavelength, 100 km, and its octaves, two, down to 50 km". One of the
  two is a misreading of a Perlin lattice's cell as its wavelength; K1 kept the 200 km lattice
  bit for bit. 2026-10-05, K1.
- **Two-height erosion is shelved on `chunk/e1-erosion-scale` (E1, 2026-10-04).** The branch splits
  each cell into a channel bed and a mean ground, with an in-cell closure for the relief between
  them, implicit lake outlets that carry each basin's actual surplus, rivers routed on the bed, and
  a drawn dissection from the stored in-cell relief. It brought the same world's denudation across
  grids from about x3.1 to x1.2-1.3, gave about half again as many lakes, closed dry basins and
  spilled wet ones, and removed seed 42's ruled bar of standing water at 512 rows. It was not merged
  because the picture at 1,024 rows was worse than main's: the drawn dissection read as evenly
  crumpled ground and flattened ridges, escarpment rims and range fronts; seed 7's north-western
  island grew a straight coast about 130 cells long and a rectangular dry tint; and a whole world
  took about 85 s against 50 s. With one grid the cross-grid motive mostly lapsed. Revisit with the
  planet-size input, when cell width will vary again: start from the branch's ledger rows (E1a, E1a
  round 2, E1b+c), and make the drawing carry ridges and valleys at the relief's own scale before
  anything else.
- **The desktop's Copy link opens a different world (G1).** File ▸ Copy link to this world writes
  an address for `cartogenesis.com/app/`, which is now the frozen browser preview: it makes the
  linked seed and settings with the generator as it stood at 8198db27, so the world it opens is not
  the one the link was copied from, and the size in the link is honored up to the preview's 1,024.
  The site's seed reel and its band caption link there too, beside pictures the current engine
  drew; the page now says the preview's generator is older. Decide whether the desktop should stop
  offering the command, say what the link opens, or point it somewhere that makes today's world.
- **The browser-only wording the interface still carries (G1).** G1 removed the browser module
  and the Wasm targets and kept, on purpose, what a revival would restore onto
  (docs/WEB_VERSION.md): the `Platform` members a browser implements (`openedAt`, `worldLinkBase`,
  `BROWSER_TAB`), the `expect` declarations, the suspending accelerator seams, and the data export's
  one-zip path. Those stay. What could go is wording: the compact arrangement's phone sentences,
  `FakePlatform`'s browser defaults (512 rows) where a test does not need them, and KDoc that speaks
  of the browser as a current front end.
- **The audit tier still makes worlds at 2,048 and 4,096 rows (G1).** The application makes one
  grid, 1,024 rows, and opens saves of other grids; the audit classes that export, save and draw
  at 2,048 and 4,096 (`ExportAuditTest`, `SaveResolutionAuditTest`, `GpuExportBenchmarkTest`, the
  render galleries, `renderSiteImagery`'s figures at 2,048) were written for the ladder. Which of
  them still earn their minutes is a decision for the budget; the site's figures at 2,048 rows are
  a picture of a grid the application no longer makes.
- **`:desktop`'s comments on heap and export sizes (G1).** `desktop/build.gradle.kts` says exports
  at 4,096 and beyond are the point of the module, and the packaged application's
  `MaxRAMPercentage` comment reasons from 4,096 and 8,192 exports; neither is offered now.
- **A deep-tier entry names a method that does not exist.** `worldgen/build.gradle.kts` lists
  `RiftSegmentationTest.the unsegmented rift fails every one of those` under `plates`, and the class
  has one test, `a flooded rift's gulfs, bridges and width are reported, segmented and plain`, which
  therefore runs in the everyday tier. Found during G1.
- **`--gpu-check` probes the erosion sweeps and the export raster only.** The ocean's and the ice
  sheet's shaders compile on the same context and are not reported; a driver that takes one and
  refuses another would be seen only as a world drawn on the processor.

- **The implicit incision has no graphics-card path (Fix 3b's stage 2).** Fix 3b replaced the
  explicit cut with Braun and Willett's implicit update on the processor (`HydraulicErosion.incise`):
  one walk of `FlowRouting.drainageOrder` backwards, receivers first, each cell
  `z' = (z + F z_r') / (1 + F)`, with a cell at or below its base level left alone. The incision has
  never had a device kernel (the explicit one did not either), so this adds the first, not a parity.
  The specification, for the next session:
  - **The per-cell map.** Each cell's new height is a function of its receiver's new height `x`:
    `z' = min(c, a + b x)` with `b = F / (1 + F)`, which is at least 0, `a = z / (1 + F)` and
    `c = z`, the cap of not cutting a cell at or below its receiver (at or below its base, `a + b x`
    is at least `z` and the minimum keeps `z`; above it, `a + b x` is under `z`). `F` is computed
    per cell as the processor does, `Rates.courantCoefficient * sqrt(share) * erodibility / step`.
  - **Composition.** Maps of that form compose into the same form: a cell's map applied after its
    receiver's, `min(c1, a1 + b1 min(c2, a2 + b2 x))`, is `min(C, A + B x)` with
    `C = min(c1, a1 + b1 c2)`, `A = a1 + b1 a2` and `B = b1 b2`, because `b1 >= 0` lets the outer
    affine map pass inside the minimum. So pointer jumping works on `(a, b, c)` triples: each pass
    replaces a cell's triple with its composition with its receiver's current triple and its
    pointer with its receiver's pointer, and after `ceil(log2(depth))` passes every cell's triple is
    expressed against a terminal.
  - **Terminals.** A cell whose receiver is sea is a terminal at the round's shoreline height; a
    cell whose receiver stands under a filled basin's water by more than the pond depth is a
    terminal at that water's surface (the processor's `baseLevel`); a cell with no receiver (the
    polar edge) keeps its height, the triple `(z, 0, z)`. At the end each cell's height is its
    triple evaluated at its terminal's level.
  - **Buffers.** Separate read and write buffers for the triples and the pointers in every pass,
    never updated in place, so a pass reads only the previous pass's values and the result does not
    depend on the order the device runs the cells in.
  - **Dispatch count.** From the measured chain depth: the longest receiver chain on the round's
    network, taken on the processor from the drainage order (or bounded by a first pass), and
    `ceil(log2)` of it passes; it is a few thousand cells at 2048, so about twelve passes.
  - **Parity.** Against the processor, cell for cell, as `GpuErosionTest` does for the thermal
    sweeps. Sums of `a` terms along a chain of thousands accumulate float error; the processor
    carries the update in double and rounds once per cell, so the device's tolerance has to be
    derived from the chain depth, not assumed. The deposition walk stays on the processor, fed the
    cuts the device returns.
  2026-09-25, Fix 3b.

- **The erosion's calibrations set under the capped explicit cut are open (Fix 3b).** Re-examined
  when the implicit update let the law set every cut, and none re-tuned:
  - `ErosionConfig.transportCapacity` (20) is a ratio to the incision's coefficient and a
    measurement that it barely matters, neither of which read the cap, so it stands; it is not an
    Earth figure. The deposition keeps under a hundredth of what the rounds cut (0.8 to 0.9% on
    seeds 7, 42 and 1234 at 512).
  - `ErosionConfig.deltaShare` (0.15), `deltaFreeboardMetres` and `deltaMinCatchment` were chosen
    by what they did to the culture guard's figures on the capped worlds: set to make worlds pass.
    An Earth figure for the share of a river's load its delta keeps would derive the first.
  - `ErosionConfig.outletIncisionRatio` (1.125) was chosen on the largest lake at three grids,
    also to make worlds pass. On the law's terrain the notch left seed 99 a lake 2.1 times the
    Caspian's share and the fill 82% as deep as the control's; once a lake falls with its outlet
    seed 99's lake is under the Caspian's share, seeds 718106 and 7 keep more than half their
    water, and the fill stands 82.5% as deep (`OutletIncisionTest` records both).
    Whether a knickpoint should cut harder than an ordinary reach at all is the question.
  2026-09-25, Fix 3b.

- **The channel network the implicit incision leaves grows denser on a finer grid, and the round's
  length is not why.** `ScaleFreeTest`'s channel-head clause reads 1.38 to 1.48 from 512 to 1024 on
  the four standard seeds (1.42 to 1.54 once a lake falls with its outlet), over its 1.35, where the
  capped update read 1.15 to 1.23. Measured at Fix 3b's review round: at 1024 with twenty-four rounds
  of half the years, the density is 1.004, 1.019, 1.000 and 0.999 times the stock 1024's on seeds 7,
  42, 1234 and 99. The time step has converged; the growth is the grid's, and the round's length is
  not a resolution parameter for it. Not isolated: whether it is the criterion reading a slope over
  a shorter step, `F` doubling at a fixed catchment when the cell halves, or the routing. A 2048
  comparison would say whether it settles. 2026-09-25, Fix 3b and its review round.

- **About a tenth of the standing water without the ice lies in thin parallel grid-bearing bars
  (rule 13).** `GlaciationTest`'s comb clause measures the ice's own addition, which passes; but
  with the ice off the share of lake cells in parallel bars at a grid bearing is 0.108, 0.078 and
  0.120 on seeds 718106, 42 and 7 at 1024 (notch off, one epoch) on the implicit update, where it
  was 0.014, 0.008 and 0.012 on the capped one. The worlds hold far less standing water (1,676, 7,175
  and 3,892 lake cells against 12,257, 24,088 and 19,488), so in cells it is 181, 560 and 467 against
  172, 193 and 234. Before the uplift was re-derived the ice itself added a comb (5.00% and 3.18% on
  718106 and 7). What makes the bars, and whether they are drainage lines the fill leaves standing
  along grid-bearing valleys, is not diagnosed; a guard on the drainage without the ice is owed.
  2026-09-25, Fix 3b.

  **And a sixth once a lake falls with its outlet.** Fix 3b's review round made a lake's surface
  in the implicit pass fall with its outlet's cut, instead of standing at its filled level for the
  whole pass, so an inflow grades to the lowered water. With the ice off the bars' share rose to
  0.167, 0.168 and 0.153 (561, 1,214 and 642 cells of 3,363, 7,224 and 4,198), and with the ice on
  to 0.159, 0.163 and 0.137. The ice's own share is still no higher than the bare world's, but on
  seed 42 the glaciated world holds 1,554 more lake cells, and the comb clause, which counts cells,
  is over its fiftieth at 2.48%. `StraightRunTest`'s census finds its first ruled bar since the facet
  rule: 27 cells on seed 42 at 512. Both are recorded under the one finding. The likeliest reading,
  not tested: inflows now cut to a lower base, so more grid-bearing channels are deep enough for
  the next round's fill to stand in. 2026-09-25, Fix 3b review round.

- **Steep flanks are combed by single-cell gullies down the columns, and the implicit incision makes
  it show (rule 13).** On the renders of seeds 7 and 42 at 1024 the flanks of east-west ranges carry
  straight north-south gullies about a cell apart. Measured on steep land (a fall over 20 m/km to the
  receiver) at 1024: 59 to 64% of cells drain straight down a column on all three trees measured
  (main, the capped update and the implicit one), where the router's own rule on ground with no
  preferred bearing sends 44.9% down a column (35% was the nearest bearing's figure, the wrong rule;
  see the restatement below); and 21 to 29% of those have both row neighbours draining the same way
  on all three. So the column preference is not new. What is
  new is that the law now cuts those channels to grade, twice as much ground is steep (155,444 cells
  against main's 71,442 on seed 7), and the comb that the cap kept shallow is plain to the eye. The
  geometry guard does not see it. Earth's first-order valleys are spaced by the ratio of hillslope
  transport to incision (Perron, Kirchner and Dietrich 2009), and this model's hillslopes have no
  length of their own (the X1d entry above); a transport length in kilometres, and a guard on the
  share of steep ground draining down a column against what the cell's shape predicts, are what
  would answer it. 2026-09-25, Fix 3b.

  **The update does not make the comb (Fix 3b's review round, reported).** On synthetic ridges on a
  256 grid (cells 46.9 km by 23.4 km), a 4,000 m crest falling linearly over 50 cell widths either
  side with 2 m of roughness, twelve rounds at 0.29 and 0.738 mm a year, the channels (eight cells
  upstream or more) follow the fall line on both bearings under both updates: 94 to 99% down a
  column on the east-west ridge and 89 to 92% along a row on the north-south one. The share of
  channel cells with a parallel channel draining the same way within two cell widths of ground
  across the fall line is 97 to 99% under the capped update on both bearings and 56 to 59%
  (east-west) and 69 to 71% (north-south) under the implicit one. The cap combs more, and more
  evenly; the implicit update combs less, and no more on east-west ridges than north-south, and
  cuts what comb there is to grade. With 20 m of roughness on a 1,500 m crest, where the roughness
  outweighs the ridge's fall, the order is the same (61 and 80% capped, 19 and 51% implicit). The
  columns the generated worlds' steep ground prefers are therefore not the update's; what they are
  is not isolated. The steep-cell count used above is no instrument for it once the valleys are cut
  deep: on the synthetic ridges the steepest third of the ground is valley walls, and it drains
  across the fall line (62 to 68% along a row on the east-west ridge).

  **What the routing gives on planes, and what is left (Fix 3b's comb round, restated).** The
  review round's cause paragraph read the steep ground's column share against 35%, the share the
  nearest of the eight bearings would give on cells half as tall as wide. The router is not that
  rule: it is Tarboton's facet direction with Fairfield and Leymarie's Rho8 draw, which takes the
  diagonal with the share of the facet's far edge the descent crosses, so its mean step is true but
  it takes the cardinal more often than the nearest bearing does. Its own expectation over isotropic
  bearings is 44.87% down a column, 15.31% along a row and 39.82% on a diagonal; on production's
  router, on planes at 360 bearings on three seeds, it takes 45.04, 15.38 and 39.58%, each within
  1.5 standard errors (`RoutingGroundTest`, which fails the plain steepest-of-eight rule at 35.6,
  14.4 and 50.0). Against that baseline the review round's figures read: the isotropic synthetic
  surfaces 47.4 and 51.4% down a column, 2.5 to 6.5 points over the rule, which is the ensemble of
  rough, filled, steep-selected ground and not a plane; the terrain before erosion 41.8 and 44.5%,
  at the rule; and the finished world 59.1 and 60.5%, 14 to 16 points over it. So the router is
  not the cause on planes and has no residual worth correcting there; what the rounds do is.
  Whether that is a feedback, a channel cut down a column tilting its neighbours down the column,
  is what the comb round tests next.

  **Three rounds of experiments, and the planned fix: square cells.** `CombGuardTest` measures the
  comb and records it as a known failure (0.41 and 0.52 km of comb per 1,000 km² down a column on
  seeds 7 and 42 at 512, against 0.06 and 0.08 along a row). The rounds established three things;
  the figures are in docs/DESIGN_LEDGER.md, Fix 3b.
  - **A feedback.** On the same steep cells followed through the rounds, the column share goes
    from the router's own figure to about 57%: the rounds turn two diagonal steps in five, and half
    the row steps, into column steps.
  - **Out of reach of any draw.** 85 to 87% of the combed cells have a descent clamped to their
    cardinal, which every draw leaves where it is: a Rho8 draw in clamped descent left the comb as
    it was.
  - **Sub-grid transport costs the valleys.** Diffusion at the law's own scale cuts the comb by three
    quarters to four fifths, but takes a third of the valleys' depth and a fifth to a third of the
    network, and still leaves ten times the guard's floor.

  The fix is cells square on the ground, twice as many across as down, so a column and a row are
  the same step. `CombGuardTest` is its acceptance test. 2026-09-26, Fix 3b.

  **What square cells did (Q2).** The one-sided comb is gone: at 512 rows the larger axis carries
  1.10 and 1.09 times the smaller's comb on seeds 7 and 42, where the 512 by 512 grid's columns
  carry 6.8 and 6.6 times its rows', and `CombGuardTest` holds it two-sided. What is left runs as a
  known failure of its own: 0.16 and 0.15 km of comb per 1,000 km² on seed 7's two axes and 0.20
  and 0.18 on seed 42's, seven to ten times the 0.021 the router makes on isotropic ground over the
  same land. It is made in the hydraulic rounds (the same network on the ground the rounds were
  handed combs 0.000 to 0.001), and over the rounds the steep cells' steps turn from the diagonal to
  both axes alike (41.7 and 42.0% diagonal before, 32.2 and 33.0% after); whether that turn is the
  cause is not isolated, and the figure is the census's as much as the ground's, 1.6 to 1.8 times as
  large with a sustained reach of 5 cells as with the 6 that 60 km takes. The bearing census
  (`BearingCensus`) reads the channels at the two axes 2.2 to 2.6 times as often as the fall line
  and at the diagonal 1.4 to 1.6 times. A transport length in kilometers, above, is still the
  candidate. 2026-09-28, Q2.

- **The sea-level percentile hands the sea's highest cell to the land where the sea fills its rank
  exactly.** `SeaLevelStage.thresholdAtRank` finds the bin where the cells counted so far reach the
  target rank, `>=`, and when the target is the bin's last cell the index is clamped to it and the
  returned threshold is the highest *sea* value, so every cell at that value counts as land: cut at
  half sea, four cells at 0.1, 0.1, 0.9 and 0.9 come out all land, and four at 0.1, 0.2, 0.8 and
  0.9 three land of four. On a real world the error is a cell or a few; on a synthetic one whose
  sea shares a depth it is the whole sea (Fix 3's clock
  guard gives its sea distinct depths and reads the catchment as the stage defines it). The smallest
  fix is `>` in the bracket search, which finds the bin holding the target rank's own cell and
  returns its value. Every world moves by the cells at the boundary, so it wants a fingerprint check
  of its own. 2026-09-25, Fix 3.

- **The flat potential's cost sits on rule 8's line.** After Fix 3 seed 7's flats at 512 held 2,696
  raised cells in 478 flats and a pass cost 3.4 ms, 1.00% of a generation over 33 passes. On the
  law's terrain (Fix 3b) they held 3,147 cells in 476 flats at 5.8 ms a pass, 2.02% of a 9.4 s
  generation. Since 4a solves the ocean on the processor, the generation is longer: 479 flats and
  3,223 raised cells at 3.4 to 3.7 ms a pass read 0.99% of a 12.2 s generation in one run and
  1.03% of an 11.0 s one in the next. A line the share straddles with the machine's load cannot be
  asserted, so `FlatCourseTest` now prints it (`F30B COST`) rather than recording a known failure
  that flips. Whether the potential needs a device path is still open. A device path, or a solve
  whose cost does not ride on the flats' size, settles it either way, and a faster ocean would put
  the share back over the line. 2026-09-25, Fix 3 and Fix 3b; 2026-09-27, 4a.

- **The Earth reference behind the river density is one dataset.** The Cartography panel's River
  density slider scales the ink from a quarter of Earth's figure to every course the sheet's scale
  allows, with Earth's figure as the default mark, so the reader can now have the old drawing back
  or a barer one; what stands is the figure the default is measured against. **It is one atlas.**
  Natural Earth's linework is hand-smoothed and hand-ranked and its own
  documentation recommends the 1:10M tier around 1:30M with supplements elsewhere, so 0.001707
  km/km2 at 1:50M is a chosen benchmark and not a constant of cartography; a named printed atlas
  sheet counted at 1:25M to 1:35M would be the independent check the brief asked for and was not
  found. **The count exponent is two points.** 0.751 between the 1:10M and 1:50M tiers, used for
  the crowding lattice's pitch and nothing else, with no published law behind it; the 1:110M tier
  is thirteen rivers and is a token selection rather than a third point, though it does set the
  slider's bottom mark (it draws a quarter of what the law asks of its scale). **The two rulers
  differ.** The reference is measured on the sphere over Earth's real land area and this map is
  measured in the generator's planar equirectangular kilometres over a rectangular cell area, so the
  agreement is a calibration in this map's own units and not a claim that the two draw the same
  physical length of river. 2026-09-22, X1c.

- **The river crowding lattice is a square grid, and CONVENTIONS rule 13 has not measured it.** The
  selection's first pass takes one candidate per square of a lattice laid out in ground kilometres
  (`RiverSelection.crowdingPitchKilometres`). It draws no shape - it decides which traced courses
  are inked, and every course keeps its own traced line - but it is a fixed-block operator of the
  kind rule 13 names, and it could in principle space the drawn mouths at the lattice's pitch along
  the rows and columns. Nothing has looked. What would settle it: add the drawn river mouths to the
  geometry guard (spacing by bearing, the nearest-neighbour distances of drawn mouths against the
  same count drawn from the traced set at random), and if the lattice shows, replace it with an
  isotropic spacing rule such as a minimum ground distance between drawn mouths. 2026-09-23, X1c.

- **No setting of the drawing is saved with a world.** The river density slider was asked for
  "saved with the world's render options as the other cartography settings are", and there are no
  such options: `WorldDocument` holds the config, the edits, the labels and the terrain, and every
  Cartography mark - style, view, relief shading, lamp, coastline, graticule - starts at its default
  in every new window. The slider's mark is kept in the application's preferences instead
  (`AppSettings.riverInkStep`), so it outlives the window and applies to every world opened after,
  which is a reader's standing preference rather than one world's. If the author wants the drawing
  saved per world, it is `RenderOptions` made `@Serializable`, a section in the save, a
  `WorldCodec.FORMAT_VERSION` bump and the gzip fixture regenerated (CONVENTIONS rule 11), and a
  decision about which wins when a world's own mark and the reader's preference disagree.
  2026-09-22, X1c.

- **The top of the river density scale is the coastal comb again, by construction.** The top mark
  was asked to reproduce the old rule's drawing exactly, and the old rule drew the comb; so the
  crowding lattice, which runs at every mark, decides only the order there and the fullest square
  is the radical law's own (the figures are in the X1c row). Below the top the lattice still takes
  one eligible, affordable candidate per square before the extra ink goes anywhere (closure can put
  a candidate's trunks in squares already taken), but its lead shrinks as the mark rises: on
  the four standard seeds the fullest square is 2 against 7-12 without it at Earth's mark and 18-25
  against 19-26 at four times, before the top's 49-70. If the author wants a top mark that draws every river
  and still thins a straight front, it has to stop being the old rule: a cap per lattice square
  that the top keeps would do it, at the price of the exact reproduction. 2026-09-22, X1c.

- **The crowding measure is printed against a control and not against Earth, because a coastline's
  length is fractal.** X1c's lattice is shown to work by the same selection with the lattice
  switched off - the fullest square holds fewer drawn courses with it on, on every standard seed -
  and that is a control, not a bar. The Earth figure the brief asked for, courses per unit of
  coast, is measurable on the same dataset: Natural Earth draws 0.60 river courses per 1000 km of
  its own 595,193 km of 1:50M coastline, and 1.31 per 1000 km of the 918,344 km it draws at 1:10M,
  a mean spacing of 1,658 km and 764 km respectively, which is itself a Töpfer-like 0.48 power of
  the scale. It is not used as a bar because a coastline's length is a property of the scale it was
  traced at: this map's coast is traced off the cell mask and simplified by Douglas-Peucker at half
  a drawn pixel, which is not the generalisation Natural Earth's coastline had, so the two
  denominators are not the same quantity. What would earn it: measure both coastlines at one
  representative fraction under one simplification, then the ratio is a bar. 2026-09-22, X1c.

- **The ink budget is spent in traced centreline kilometres, not in the kilometres the reader
  sees.** `RiverSelection.courseKilometres` sums the traced polyline including the step into the
  water, which is the same quantity the Earth reference measures, while the rasterizer cuts the
  last stroke back by half the mouth step plus half a pen width (`MapRasterizer.trimmedAtTheShore`)
  and skips any segment lying inside open water. The difference is a few parts in a thousand on
  these worlds and it depends on the resolution, the course count and the pen, so the density is
  reported in a unit slightly above the ink. Closing it means measuring the overlay's own segment
  lengths, which the selection cannot do because it runs before the overlay is laid out; the
  honest cure is to measure both in a guard and record the gap. 2026-09-22, X1c.

- **On a smooth steep coastal slope the valleys' spacing falls as the grid refines, and faster
  than a spacing fixed in cells would.** X1d measured the author's comb on 969495's eastern peninsula on one line fixed in
  kilometres (`RangeFront.measureLine`, printed by `CoastalSpacingAuditTest` as AUTHORS COAST), a
  367 km coast where a plateau at 1,100 to 1,900 m falls to the sea over 100 to 150 km: catchments
  of at least 1,440 km2 reach it 62.3 km apart at 512 and at 1024 and 24.2 km apart at 2048, over
  2, 5 and 11 gaps, and the traced courses 124.5, 62.3 and 19.4 km over 1, 5 and 12. From 1024 to
  2048 that is 0.39 and 0.31 of the coarser figure, where a spacing fixed in cells keeps 0.50 and
  one fixed on the ground 1.00; matched coasts elsewhere keep 0.89 (catchments, interquartile 0.69
  to 1.28) and 0.86 (traced). The hypothesis, not established: the hillslope process is the thermal
  pass at `ErosionConfig.criticalFallMetresPerKm`, a slope threshold with no length of its own,
  where Earth's first-order valley spacing is set by the ratio of hillslope transport to incision
  (Perron, Kirchner and Dietrich 2009, Nature 460), so on ground smooth enough that nothing else
  converges the flow the cell may be the only length left. The fix chunk would first test that on
  this line (a synthetic ramp at two grids, or a 4096 world if the heap allows) and widen the
  sample to the other smooth coastal ramps, since five gaps at 1024 is a weak discriminator; then
  give the hillslopes a transport length in kilometres, with GPU parity under the erosion seam. Its
  guard is the measured figure: the line's catchment spacing at 2048 over 1024, 0.39 today, inside
  the matched coasts' interquartile range between those grids, 0.69 to 1.28, shown failing on
  today's world first. 2026-09-22, X1d.
- **What spaces the valleys that reach the divide is not known, and the instrument's own floor
  sets the figure X1d reads.** Under X1d's instrument they are about 70 km apart on straight coasts
  and mountain fronts at every grid (66 to 71 km pooled), steady in kilometres front by front across
  512, 1024 and 2048; but halving the catchment floor takes that to 47 to 52 km and doubling it to
  76 to 101 (the divide's strip width barely moves it), so the value is the floor's and the
  steadiness across grids is what a floor in square kilometres would impose anyway. Halving and
  doubling `TerrainConfig.reliefCornerKm` and the belts' half-widths, and changing
  `TerrainConfig.octaves` by one (doubling and halving the finest wavelength), drew no consistent
  proportional response (0.82 to 1.18 of stock at 2048). The spacing barely follows the divide
  proxy's half-width (log-log slope 0.21 to 0.32 on coasts). What would settle it is a measure with
  no area floor: the spacing spectrum of valley axes along a coast (the dominant wavelength of the
  elevation profile a fixed distance inland), taken at three grids. Untested: the crust margin's
  300 km band (`TectonicsConfig.crustMarginKm`), the erosion's own lengths (`debrisTravelKm`,
  `deltaReachKm`, `outletReachKm`), and the instrument's reference grid and shortest front, which
  were not varied. 2026-09-22, X1d.
- ~~**The land's outlines are consistent with cell-space anisotropy in the kilometre metric.**~~
  Answered by Fix 2, 2026-09-24. The terrain noise's lattice and `PlateStage`'s boundary distance
  were put on the ground with every other operator Audit III found counting a row as a column, so
  which of them carried the twofold figure was not separated. On the four standard worlds at 512 the
  coastline now projects 1.32, 1.41, 1.41 and 1.41 times as far east-west as north-south, 1.39
  pooled, where the same measure read 1.88 to 2.00 before (`GroundIsotropyTest`). What is left is
  the incision's cap per step, Audit III's B-D1, which cuts a channel running north-south half as
  far a round; the test records it as a known failure. Fix 3 put the erosion in one unit and the
  ratio came to 1.12 pooled, still over on seed 1234 at 1.19. Fix 3b's implicit update took the cap
  away and the notches are the same depth by bearing, and the ratio still reads 1.12 pooled, seed 99
  past what its length allows at 1.16; `GroundIsotropyTest` records it under a finding of its own,
  the cause not isolated.
  Over the seven worlds `CoastalSpacingAuditTest` prints, the coastline reads 1.41, 1.48 and 1.53 at
  512, 1024 and 2048 where it read 1.93, 1.99 and 2.06, and the 2,000 m contour 1.46, 1.47 and 1.48
  where it read 1.99, 2.00 and 2.04. The coast's figure still rises with the grid; why is not
  measured.
- ~~**The map draws a world twice as wide as it is tall on a square sheet, so land that is round
  on the ground reads twice as tall as it is wide.**~~ Answered by Fix A, 2026-09-24: every picture,
  on screen and exported, is drawn on the true-shape sheet (`SheetGeometry`), a cell two pixels
  wide and one tall with its colour copied exactly and the ink laid over it at its own width; the
  cartouche quotes the sheet's one scale. The other half of the answer, grids twice as many cells
  across as down so a cell is square on the ground, is still open and reuses the same geometry.
- ~~**On the true-shape sheet a cell is two pixels wide, so what the raster decides a cell at a time
  is two pixels wide where it runs north-south and one tall where it runs east-west.**~~ Answered on
  square cells, 2026-09-28, Q4: a grid twice as many cells across as down is drawn a cell to a pixel,
  and `RasterMarkWidthTest` holds the raster's coast, read off the drawn sheet, to the same thinnest
  and commonest run either way. On the gallery's world at 512 rows both are one pixel (mean 1.32
  along the rows, 1.34 down the columns); the same seed on the 512 by 512 grid, the clause's control,
  reads two pixels along the rows and one down the columns. Since Q5 the applications name every
  size by its rows and ask for grids twice as many cells across, and `PanelKnobsTest` holds every
  size on the ladder and on the export row, through the chips, an export, the settings and a link,
  to a cell a pixel (on the ladder as many cells tall as wide it fails: 512 drew two pixels a cell
  across); the two-pixel branch of the sheet stays for such grids and is held by
  `SheetExpansionTest`'s and `GpuRasterTest`'s cases on them. 2026-09-24, Fix A.
- **Four grid-shaped marks on seed 718106 at 2048, seen while cutting the site's card pictures.**
  Each breaks rule 13; whether the geometry guard's detectors see them has not been checked:
  - the ice caps end in an edge straight down a column, with a fan of rays off it (the ice's work;
    compare the known failure that the sheet's edge runs straight along a row);
  - the dry belt crosses the northern lowlands as a band ruled along a row (the climate's);
  - the estuary sea in the site's styles window has a straight west edge and a straight top (the
    sea level's drowned basins), and shows on the site's Schoolroom card;
  - a short straight double line at the top left of the site's data-view window, in all four cards.
  The site's windows were kept (the three styles and four views must show the same ground), so the
  last two are on the page until the generator is fixed; the site's pictures are all made again
  once the audit's major fixes have merged, and the windows re-picked then if any mark remains.
  2026-09-25, Site 4. Site 5a moved the styles window, and with it the Schoolroom card, off the
  estuary, and put the six steps and the twelve styles in windows that hold none of the four; the
  dry belt also crosses the eastern island along the same rows (about 675 to 700 of the 2048
  sheet). Two remain on the page where the figure cannot move: the band the opening drifts (Site 6,
  now at the sheet's full 4096 by 800 and the page's largest picture) is the author's window all
  the way round the world, so it carries the range's ice cap with its straight edge and the dry
  belt the whole way, and its settled plate shows both; the data-view window's double line is
  unchanged. Site 5b added three figures that cannot avoid them: the lens's whole world and its
  full-size picture carry all four, the data frame (the cards' window widened east) carries the
  double line as the cards do, and the reel's five worlds at 512 show their own ice caps and dry
  belts. Two more marks were seen while picking the relief's patch, the eastern island's western
  end at (2720, 470): a scarp running nearly straight down a column from about (2960, 717) to
  (2950, 820) on the sheet, and a delta flat with straight edges at about (3010-3055, 800-835).
  Both are in the relief, where the tilt makes the scarp plainer; no window of that size on the
  island's range avoids them and the dry belt at once. On the implicit erosion's terrain Site 5c
  looked at every window again and moved four: the styles, the data frame (with the data cards)
  and the relief onto the south-western peninsula, where the comb of gullies down the columns is
  least, and the opening's band off seed 718106 altogether (below); the six steps stayed. What the
  page still shows, because the figure cannot move off it: the lens's whole world carries every
  mark, and the reel's worlds at 512, whole, show small glacier flats of their own.
  **On square cells** (2026-09-29, Q6) the site's world at 2048 rows, 4096 by 2048 square cells,
  puts the south-western peninsula where it was, and every window was kept. The subduction coast
  along its south-east shore is still straight on the diagonal for 687 km at 12 km (690 on the old
  grid), in the styles, the relief and the data frame; the drowned valleys at the top of the
  styles window, the data frame and the six steps are now a lake whose water stands up the gullies
  round it (the lake-area entry below); the northern range's ice cap still ends in straight edges
  on the lens's whole world.
- **No strip of 800 rows round any world measured is free of long straight runs.** Site 5c traced
  every coast (as drawn, and smoothed to 12 km), shelf break, ice-sheet edge, ice-flat edge and
  land-biome belt edge on the 2048 sheet of 21 worlds (718106, and 1, 3, 7, 8, 12, 21, 42, 64, 99,
  123, 300, 777, 1066, 2024, 2026, 5000, 31337, 65536, 90210 and 424242 made as the application
  makes a world with only a seed, taken to 2048) and flagged every run that stays within 12 km of
  its chord for more than 403 km, the Himalayan front's straightest stretch (Bendick and Bilham
  2001). Every band of 800 rows on every world holds at least one; the fewest on a band with 40%
  land or more is three. On 718106 the land-rich bands carry 22 or more, among them the ice cap's
  flats and the subduction coast straight down a column (the trench-is-a-plane entry). The page's
  band is now seed 1's rows 848 to 1,648 from column 3,264, the maintainer's choice of three
  offered; it keeps two coast runs, the smoothed coast from about (735, 1007) to (819, 1126) on the
  sheet, 427 km, and the shelf break from about (2139, 1530) to (2295, 1572), 473 km, and one belt
  edge of 434 km along a row near 1,375; its range's flanks also show the comb of gullies down
  the columns, plainest of the three offered. The full picture refresh after the square grid picks the
  band again, by the same measure. 2026-09-26, Site 5c.
  **On square cells** (2026-09-29, Q6) seed 1 at 2048 rows makes the same country, and the
  maintainer kept the window, from three offered. The same rows now hold five runs past the bar,
  all within 61 km of it: the smoothed coast of 464 km at about (3080-3238, 1117-1130) and four
  shelf breaks of 410 to 426 km, and one belt edge of 460 km. Three marks the rows cannot avoid
  without losing the range: its small ice cap drawn gray and smooth with a straight top edge along
  a row for about 200 km at about (269-479, 1043-1105), in the band and the link preview; a second
  scarp straight on the diagonal at about (800-930, 1200-1390), in the band; and the lighter lake's
  shore standing up the gullies round it at about (4380-4480, 1310-1360), in the preview (the
  lake-area entry below). The measure, ported to square cells, reads the comb either way.
- **The ocean's device path is slower than the processor.** Chunk 4a's circulation and heat are
  solved on the card behind `OceanAccelerator` and agree with the processor to the bit, but each
  batch of relaxation passes goes to the card and comes back, and the multigrid's restriction,
  prolongation and Krylov steps stay on the processor between batches: the whole stage takes 5.0
  to 6.7 s with the device against 1.7 to 2.8 s without it on seeds 42, 718106 and 59758
  (`GpuOceanTest`). Keeping the whole V-cycle resident on the card, the transfers and the Krylov
  vectors with it, is what would make the device pay; until then the graphics switch costs the
  ocean time. 2026-09-26, 4a.
- **The gyres' boundaries run along lines of latitude.** The belts' stress is a function of
  latitude alone, so where the regional wind is weak the curl changes sign along a row and the
  boundary between a subtropical and a subpolar gyre, and the warm band beside it, runs straight
  across a basin: on 969495 at 2048 at about 41 to 45 S and 43 N, softened by the eddies to a
  gradient about 100 km wide but straight. Earth's are bent by the pressure cells over the oceans
  and by the separated boundary currents' paths (the Gulf Stream's and the Kuroshio's extensions),
  which Stommel's balance with no inertia does not make. Chunk 4b-1 tried the cells and found them
  out of this model's reach: a thermally forced linear layer gave an annual pressure near zero and,
  under surface drag, a smooth response of 3 to 8 hPa per half-year that left the curl's zero line
  straighter than the regional wind already on main does. Measured as the zero line's range of
  latitude over a basin's interior, in kilometers, over the basin's width: 0.006 to 0.045 on the
  four standard seeds' northern basins at 512 with main's wind, and 0.000 to 0.006 with the solved
  pressure over belts left unmigrated; with the belts migrated by the seasons it read up to 0.086,
  from the extra zero lines the migration put in the curl, not from any bending
  (docs/DESIGN_LEDGER.md, 4b-1).
  **Owned by "Build the atmosphere, so the subtropical highs are real"** below; 4b-1 leaves the
  ocean on main's regional wind. The measure wants Earth's figure from a scatterometer stress
  climatology (Risien and Chelton 2008) before it becomes a bar; Gray et al. (2020, *Geophys. Res.
  Lett.*) describe the North Pacific's line as nearly zonal near 40 N, so the bar will be small.
  2026-09-26, 4a; 2026-09-28, 4b-1.
- **Build the atmosphere, so the subtropical highs are real.** An action item, owned by a chunk of
  its own. **What it is:** a stationary-wave model of the troposphere with at least two layers, or
  explicit vertical modes, forced by the latent heating of the model's own rainfall (the monsoon's
  Rossby response that puts a subtropical high west of a heated continent: Rodwell and Hoskins 2001,
  *J. Climate* 14, 3192-3211), by the land-sea contrast and by the Hadley cell's descent; with
  multi-day damping for the free atmosphere and the boundary layer's drag kept separate; every
  scale derived from the planet's radius and spin (`WorldScale`); and a graphics-card path behind
  the accelerator seam. **What it unlocks:** pressure cells over the oceans, so the gyres' fronts
  bend (the entry above); the wind 4b-2's march reads; and the rain and the deserts that follow a
  real circulation. **Where to start:** the solved, linear, damped single-layer response 4b-1 built
  and then took off the branch, `PressureResponse` in commit b50119c on `chunk/4b1-sea-wind`
  (`PressureResponseTest` beside it). It solves `αΦ - ∇·(c²a∇Φ) + c²(∂b/∂y ∂Φ/∂x - ∂b/∂x ∂Φ/∂y)
  = αΦ_eq` with land's and sea's drags, `a = ε/(ε²+f²)`, `b = f/(ε²+f²)`, as an advection-diffusion
  problem on the ocean's multigrid, and it is verified: a forced strip's reaches at 30 degrees read
  1,579 and 859 km against the analytic 1,580 and 851, and a cooled disc's centroid moved 562 km
  west against the analytic drift over α of 566, halving to 284 against 285 at twice the radius,
  at aspects 1.0 and 0.5. **What 4b-1 learned:** a layer forced by the thermal contrast alone has
  an annual mean near zero, because the energy balance gives land and sea nearly the same annual
  temperature, so the linear response to the year is nothing and only the seasons have cells; and a
  surface drag of 3 to 6 hours in the layer's momentum spreads the response over 900 to 2,100 km
  and cuts it to a quarter or a third of its forcing, while damping it at the layer's own rate
  (Gill's ε = α) lets the long Rossby wave carry it 2,700 km west at 30 degrees and 22,600 at 10.
  **The scale warning:** this world's β is 3.3 times Earth's, so its westward drift is 3.3 times
  faster and its equatorial radius √3.3 times shorter, and a response reaches a far larger share of
  a small planet than of Earth. Eastern-basin highs are plausible from such a model but not
  guaranteed; a second reader's view, recorded with the maintainer's decision of 2026-09-28.
- **The regional wind blows 30 to 35 m/s in some equatorial basins.** `PressureWind.surfaceWind`'s
  down-gradient limit divides the pressure gradient by the surface drag alone where `f` vanishes,
  and the pressure it is given carries the highlands' lapse, so near the equator a steep departure
  becomes a gale: the ocean's annual stress along the equator reads 1.3 N/m² in seed 42's basin at
  map columns 0 to 17 and 1.8 in seed 1234's at columns 15 to 27, at 512, where the belts' trades
  give 0.083. Chunk 4b-1's upwelling responds to it: the equatorial rise goes as the stress, 15.4
  m/day on the grid's rows beside the equator under the belts' trades (15.9 analytic) and some 250
  to 345 m/day under those stresses, so those basins' upwelling
  is too strong until the wind is fixed. **Owned by "Build the atmosphere, so the subtropical highs
  are real"** above, whose solved response replaces this wind. 2026-09-28, 4b-1. With the smoothing on the sphere
  (A1-2) the fastest annual regional wind within 30 degrees of the equator on seed 42 at 512 rows
  rose from 17.5 to 24.9 m/s, the mean from 3.0 to 3.3: the Gaussian keeps more of a steep
  departure than the cell-counted box it replaced. 2026-10-09, A1-2.
- **A world with no seasons has its trades' leg reverse on the equator.** `SurfaceBelts.hadleyLegNorth`
  takes the Hadley leg's direction as its year's mean under the ITCZ's migration, `-(2/π) asin(φ/T)`,
  which passes through zero on the equator for any tilt `T` above zero. With seasons off, or a
  tilt of zero, the ITCZ does not migrate, the mean is the instantaneous leg, and it reverses
  between the two rows either side of the equator, where its down-wind Ekman transport converges
  and sinks the water the easterlies raise, as every world's did before the leg was fixed (13 m/day
  down where the easterlies raise 16). Earth's surface meridional wind passes through zero across
  the ITCZ's own width even at an instant: the Hadley cell's surface branch carries no mass at its
  rising edge. Deriving that profile, rather than fitting a width, would close it for every tilt.
  2026-09-28, 4b-1.
- **The fishery reads a cold anomaly over a shelf as upwelling.** `NationStage`'s fishery rule
  (around its line 654) calls any negative anomaly over a shelf an upwelling. Since 4b-1 the ocean
  solves the upwelling itself (`OceanStage.upwellingMps`), and the rule could read the rate of rising
  water rather than its temperature's shadow; left as it is, measured through the coastal
  habitability gap (6.1% pooled, `OceanCurrentTest`). The site's data-view pictures (currents,
  temperature) also still show 4a's ocean and wait for the refresh after the square grid.
  2026-09-28, 4b-1.
- **A planet's size and spin are not yet settings.** Everything the ocean solves reads the radius
  from `WorldScale.radiusMeters` and the spin from `WorldScale.ROTATION_RATE_PER_S`, and
  `OceanPlanetSizeTest` holds the laws at twice the radius, the eddy diffusivity's equatorial
  deformation radius among them (`OceanHeat.diffusivity`); what a setting would still need is
  every other stage's lengths audited the same way, and a spin read from the setting where
  `ROTATION_RATE_PER_S` is read now. 2026-09-26, 4a.
- **Chunk 4a's climate moved the drawn ice's straight runs at 2048, recorded for the ice chunks.**
  The drawn ice follows the climate, so solving the gyres moved `ICE_EDGE_ALONG_A_ROW`'s marks in
  the 2048 census (`GeometryExpectations.at2048`), base 3a66025 against 4a's head: seed 42's
  facets clean against 199.0 cell widths; 969495's clean against two runs, 215.0; 1234's 179.3
  against 188.6; 718106's 273.0 at (1030, 1761) against two runs, 189.0 at (998, 1784); 99's
  row-bearing preference clean against 1.647; and 59758's cleared, 1.555 against clean. They go
  to the ice chunks, which will change what the map draws as ice: the maintainer has chosen that
  the drawn ice follows the sheet's thickness, which retakes every one of these. 2026-09-26, 4a.
- **Peoples' borders follow circular arcs on 59758 at 2048.** Two arcs of about 123 degrees on
  circles 10 to 12 cells across, `PEOPLES_BORDER_ARC`, a finding first made by 4a's census: base
  3a66025 clean, 4a's head two. The peoples settle by habitability, which the solved currents
  moved, so this is 4a's climate reaching a rule of the peoples' that draws a round edge where
  its inputs allow one. 2026-09-26, 4a.
- **A realm border follows a circular arc on 59758 at 2048.** One arc of 127 degrees on a circle
  11 cells across, 0.22 cells rms, `REALM_BORDER_ARC`: base 3a66025 clean, 4a's head 127.5.
  Realms follow habitability too, so this is 4a's climate moving a border onto a round path, as
  with the peoples' arcs above. 2026-09-26, 4a.
- ~~**Six operators still count a row as a column, each outside Fix 2's list.**~~ Closed on every grid the application makes by the switch to square cells, 2026-09-29, Q6: since Q5 every size is a grid twice as many cells across as down, so a row and a column are the same length on the ground and each of the six reaches as far one way as the other. A grid of cells twice as wide as tall, which
  only a test now builds, still has it. What follows is the entry as it stood. Found by reading the
  code, not by a guard: the climate stage's rainfall blur (a square box of cells, sized by
  `RAIN_BLUR_REFERENCE_WIDTH`) and its two coastal-reach blurs, the water exposure and the offshore
  anomaly's spread, whose radius is `OceanConfig.coastalReachKm` read as whole cells (chunk 4b,
  the coastal climate); the realms' two blurs (`NationStage`); the seeded field that jitters flat
  routing and the lake balance, a lattice of `FlowRouting.SMOOTH_FIELD_PERIOD_KM` read as whole
  cells each way (`FlowRouting.smoothSeededField`); the thermal sweeps' count,
  which spends `debrisTravelKm` as sweeps of one cell, a row down a column
  (`ErosionStage.sweepsFor`); and the glaciation's two distance fields (the `JumpFloodDistance`
  entry below). On square cells, the grid the switch of Q1 to Q6 moves the application to, each of
  them reaches as far one way as the other. 2026-09-24, Fix 2; 2026-09-28, Q2.
- **Fix 2 redrew every continent, and twenty clauses its new worlds tipped run as known failures,
  each named for where it is next taken up.** The plate partition moved from a chamfer on square
  cells to Euclid on the ground, so every seed's continents are new, and a clause that reads one
  sample of them moved with them. Each runs under `KnownFailures` with the figure that tipped it:
  - *the plates*: seed 42's old belts stand within 52 cell widths of a present boundary
    (`TectonicHistoryTest`); seed 42's foreland falls to the edge of the collision's own
    ground with no rise beyond (`IsostasyTest`); and the author's world at 2048 holds 2 rift lakes,
    the deepest 454 m, under Malawi's 706, where it held 4 and the deepest 1,025 m
    (`RiftDepthAuditTest`, the audit tier);
  - *the ice* (chunk 5): the bed under seed 7's cap sinks a metre past Airy's share of its column
    (`IsostasyTest`); sheets as wide as Greenland's grow on high plateaus and stand under its 2,000 m
    (`IceSheetTest`, seeds 718106 and 7; armed at C1b, whose snow thickens them past it); seed 59758's sheet edge runs 70 cells along a row, the
    census's ice-edge finding (`IceSheetTest`);
  - *the erosion* (chunk 3, done as Fix 3 and Fix 3b): the coast's projection ratio and the valley
    notch, both under B-D1 (`GroundIsotropyTest`, `ValleyIncisionTest`); seed 1234's windward flank
    cut 1.26 times as hard for 3.5 times the rain, under the law's 1.49, 1.12 since Fix 3
    (`ClimateFedErosionTest`). The notch and the flank are armed at Fix 3b; the coast's ratio is
    recorded under its own finding;
  - *the water*: the notch's three largest-basin clauses (`OutletIncisionTest`), armed again at Fix 3
    once the notch began at the basin's lip; the flat potential
    at 4.7% of a generation on seed 7, past rule 8's hundredth, because the redrawn world's flats
    hold twice the cells (`FlatCourseTest`) — a device path, or a cheaper solve, is owed;
  - *the climate*: the pooled recycling ratio, 0.292 against Earth's 0.30 (`MoistureBudgetTest`,
    both clauses); seed 1's cold-current coast, 0.66% wetter with the coupling on
    (`CurrentFeedsRainTest`); the tropics' pooled desert share, x0.69 against a bar of x0.54
    (`GeographyAuditTest`); the warm-current west coasts at 50-60 degrees forested on one seed of
    three, 64.0%, 12.7% and 34.3% where they were 56.9%, 41.1% and 62.1% (`ColdCapReportTest`, the
    audit tier);
  - *the coast*: seed 298405's coast, 1.092 by ruler on the ground (`LittoralCoastTest`);
  - *the distance*: seed 42's shelf, drawn off the plain jump flood, off Euclid by 0.0016 of a cell
    width on nine cells, under A-I11 (`JumpFloodDistanceTest`).
  Measured, and not tuned: no bar moved to take any of them in. 2026-09-24, Fix 2.
- ~~**The incision and the routing measure a step in cell widths whichever way it runs.**~~ Done by
  Fix 2, 2026-09-24. The routing's facets are built on the ground, a leg of a cell width and a leg of
  a row's height, so a plane falls where it faces: `RoutingGroundTest` routes a dozen planes, the
  ground's diagonal and the grid's among them, each within a quarter of a degree of its bearing, and
  `CoastalSpacingAuditTest` now asserts the 45 degrees it used to assert the 14 against. The
  incision, the transport walk, the notch and the headroom divide a drop by the step's length on
  the ground in cell widths, and the thermal sweeps hold a drop per kind of step on both devices.
  What the ruler does not reach is recorded under `GroundIsotropyTest`'s known failure: the cut is
  capped at half the drop to a cell's receiver in a round, a drop is in proportion to the step, and
  where the cap sets the cut (Audit III's B-D1) a north-south channel is cut half as deep a round.
  Since Fix 3b the cap is gone and the notches read the same by bearing.
- **The moisture march does not conserve its water, in two places.** In `ClimateStage.marchLandStep` the parcel's stock is capped to the cold cap *after* its rain for the cell has been taken, so the water the cap removes over cold ground is neither rained nor carried: it leaves the budget silently. In `marchSeaStep` the rain over open water is reported (`moisture * seaRainPerCell`) but never subtracted from the stock handed to the next cell, so the ocean reservoir approaches saturation whatever the sea rain rate is set to. Both were found by reading the code against the ledger's recycling figure, which therefore does not by itself show the budget is right. The fix is an instrumented budget first (every source, every sink, the storage change and the boundary flux summing to zero per lap), then the two corrections, then re-measuring recycling and the interior mean; it belongs to the chunk on wetter interiors, because closing the sea leak alone will move every coast. Beside it: the 1,000 km depletion length cites van der Ent and Savenije (2011) for a figure that paper gives as 500-2,000 km for tropical and mountain recycling, with 3,000-5,000 km in temperate climates and over 7,000 in deserts, so the constant's justification is misread and the transport time and the rain lifetime want testing separately. 2026-09-21.
  **Measured at C1a.** `MoistureLedger` sums every term of the march per lap, and `MoistureClosureTest` holds the storage change to the sources less the recorded rain; it runs as a known failure. Pooled over the standard seeds at 256 rows the recorded laps leave 15.66 times their sources unaccounted: the open sea's rain never taken out is +15.73 of the sources, the cold cap -0.03 and the row blend (the entry below) -0.05. On the Earth-sized planet at 1,024 rows, seeds 42, 969495 and 7, in millimeters a year over the map's cells: sources 130, 113 and 146 (the sea 63, 58 and 66, the ground 67, 55 and 80), recorded rain 2,250, 2,150 and 2,199, unaccounted +2,120, +2,037 and +2,053, of which the open sea's unremoved rain is +2,143, +2,056 and +2,068, the cold cap -4.6, -6.2 and -6.6 and the row blend -17.8, -12.5 and -8.0. Closing the sea leak alone takes the open sea from 4,186, 4,126 and 4,110 mm on the sphere to 1,384, 1,373 and 1,334 (Earth's ocean takes about 1,030: Trenberth and others 2007), nine tenths of the excess, and takes the land from 445, 353 and 505 mm to 317, 248 and 364, because `ClimateStage.MM_SCALE` was fitted to the leaky march's windward coasts. Raining the cap instead of dropping it adds 10 to 16 mm to the land. 2026-10-08, C1a.
  **Closed at C1b.** The march carries water in kilograms per square meter as fluxes between rows and columns, takes the sea's rain out of the parcel and rains the saturated column's excess where the cold cap dropped it; `MoistureClosureTest` holds every lap to its sources less its rain within a millionth (it reads 2e-14) and the annual field the pipeline reads to its sources less its storage within a hundred thousandth. 2026-10-08, C1b.
- **The march's sideways blend between rows makes and destroys water (C1a).** A slanting wind carries a parcel to a fractional row, and the march samples the column behind it at that row (`ClimateStage.marchRun`): an interpolation, not a flux, so where the slants converge the air that meets is dropped and where they diverge, or a departure is clamped at a belt's edge, it is copied. On the Earth-sized planet at 1,024 rows, by the ledger's per-cell account (`MoistureLedger.Cells.advectionGain`) averaged over five-degree bands, it takes 58, 191 and 94 mm a year net from the cells within 15 degrees of the equator (seeds 969495, 42 and 7), up to 294 in one band where the trades meet, takes 70 to 180 at 45 to 55 degrees, where the westerlies and the polar easterlies meet, and makes 85 to 270 in the bands beside the belt edges at 20 and 40 degrees. The same trades' convergence moved by a conservative donor-cell transport of the same slant piles into one or two rows, because the belts' meridional wind is a step at the thermal equator. 2026-10-08, C1a. **Closed at C1b**: the blend is gone; water crosses rows as donor-cell fluxes and eddy mixing, each taken out of one row and given to the other. 2026-10-08, C1b.
- **The ground gives back water it was never given (C1a).** The return over land (`ClimateStage.marchLandStep`) relaxes the parcel toward saturation over `evapotranspirationLengthKm`, scaled by a wetness read off the previous lap's rain and floored at 0.15 for bare ground, and nothing bounds it by the rain the cell received. On the Earth-sized planet at 1,024 rows the ground's annual return exceeds its annual rain on 42.0, 40.2 and 40.2% of the land's cells (seeds 42, 969495 and 7), while the land as a whole returns 0.65, 0.62 and 0.63 of its rain (Earth's land about 0.6; Trenberth and others 2007, Oki and Kanae 2006). With the return switched off the land beyond 1,000 km of the sea takes 41, 47 and 69 mm against 237, 168 and 285, so the interior is held up by water from nowhere. 2026-10-08, C1a. **Closed at C1b**: the return is Budyko's share of the cell's own year of rain against FAO-56's reference evapotranspiration, the curve the rivers' runoff is the rest of, so no cell returns more than it was given. 2026-10-08, C1b.
- **The climate measures a cell of the equator's width at every latitude (C1a).** The grid's cells are `worldWidthKm / cellsAcross` wide at the equator and `cos(latitude)` of that on the ground. The march charges every step a whole equatorial cell of rain, evaporation and return, and its slant is rows per equatorial cell of zonal travel; `MoistureBudget.convergencePerCell` takes the east-west gradient and the crossing time over the equatorial width; `PressureWind` takes its east-west gradients the same way; and `ClimateStage.waterDistance` measures distance in equatorial cell widths, so polar land reads far from the sea (the land counted 2,000 to 3,000 km from the sea on the Earth-sized planet lies at a mean of 48 to 72 degrees). At 60 degrees a step is twice its ground. Corrected in the march alone, the land's rain moves by 3 to 5% (445 to 465, 353 to 369, 505 to 522 mm on the sphere). 2026-10-08, C1a. **The march closed at C1b**: it charges every step its own ground, `cellWidth cos(latitude)`, and its blur is a Gaussian of one width on the ground. `PressureWind`'s east-west gradients and `ClimateStage.waterDistance` still take the equator's width. 2026-10-08, C1b. **`PressureWind` closed at A1-2**: its gradient is the sphere's (`SphericalOperators`), its smoothing a Gaussian on the sphere; `ClimateStage.waterDistance` still takes the equator's width. 2026-10-09, A1-2.
- **The rain blur carries the sea's rain onto the coast (C1a).** The march's rain is box-blurred over 93.75 km after the march (`ClimateStage.RAIN_BLUR_RADIUS_KM`), sea and land together, and the sea's recorded rain is 4,100 mm. On the Earth-sized planet at 1,024 rows the blur raises the land's mean from 391, 317 and 465 mm to 445, 353 and 505, and the land within 250 km of the sea from 583 to 755 mm on seed 42. 2026-10-08, C1a. **Moot at C1b**: the sea rains about Earth's 1,030 mm now, and the blur is a conservative Gaussian on the sphere (`SphereBlur`). 2026-10-08, C1b.
- **W3's convergence term rains water it never gathered (C1a).** `MoistureBudget.convergencePerCell` adds the departure wind's convergence to the rain rate, so the converging parcel loses it, but nothing brings the converging neighbors' water in: mass continuity's sink without its source. Switching it off wets the land beyond 1,000 km of the sea from 237, 168 and 285 to 279, 194 and 298 mm and the land within 10 degrees of the equator from 808, 977 and 1,121 to 852, 1,004 and 1,138, which is W3's 7% drier summer interior with its cause. Within 10 degrees of the equator the term averages -0.0008 to +0.0010 of the column per cell against the flat rain's 0.0196: it supplies no ITCZ. 2026-10-08, C1a. **Closed at C1b**: the term is gone. Its successor, rain at the air's own convergence through the four sides of each cell, was checked on its own against GPCP's equatorial band, failed, and is a control switched off (`ClimateConfig.convergenceRain`; the entry on the march's misses below). 2026-10-08, C1b.
- **The desert and the dry belt are ruled along rows by two latitude-only fields (C1a).** Air is not carried across a circulation belt's edge (`ClimateStage.marchRun`), and on the Earth-sized planet five of the six seasonal rain fields' largest row-to-row steps over land, and the sixth's second, sit exactly on those edges, 40.0 and 70.0 degrees in the warm half and 20.0 and 50.0 in the cold, at 2.8 to 3.6 times the median step; the annual field keeps the step at 50 degrees on 969495 and 7. And the belt factor (`ClimateStage.latitudeBandAt`, sharpened 2.18 times for the seasons) is clamped at `MIN_BAND` over 28.6 to 50.0 degrees in the warm half and 8.7 to 30.1 in the cold, so over 28.6 to 30.1 both seasons' rain and ground return run at a twentieth and the annual rate rises fourfold within 1.6 degrees either side. The desert biome's longest edges straight along a row, 330 km at 27.6 S on seed 42, 257 km at 34.8 N on 969495 and 346 km at 27.8 N on 7, sit on that strip's edges or on its flanks, where the annual rate climbs from a twentieth at 30 degrees to 0.54 at 35. The geometry guard holds the biome edges to the zonal control's ratios, which admits both. 2026-10-08, C1a. **Eased at C1b**: water crosses the belts' edges on the eddies and on a meridional wind that falls to zero at every edge, and the belts' factor reaches its floor without a kink. At 1,024 rows on seeds 42, 969495 and 7 the annual rain's largest row step is 1.8 to 2.0 times its median against main's 3.4 to 4.0, the seasons' 1.8 to 2.7 against 3.5 to 5.3, and the 250 mm isohyet's straightest edge along a row 267 to 276 km against 470 to 505; the desert's straightest is 274 to 366 km against 257 to 346, longer on 969495, at 9.8 N (`RainGeometryTest`, whose bar is main's until Earth's raster is in the repository: the entry below). 2026-10-08, C1b.
- **The ice-sheet share is a question about temperature, not rain (C1a).** The ice-sheet biome holds 1.77, 2.20 and 2.51% of the land's area on the Earth-sized planet (Earth 10.1%). With the year's rain raised to Earth's land mean (x1.6) the snow balance gives 2.05, 2.53 and 2.74%; five times the rain gives 3.2 to 3.7%, five times poleward of 50 degrees only 2.9 to 3.1%. With the rain as it is, 4 C colder gives 4.2 to 4.6% and 6 C colder 7.7 to 8.5%; x1.6 and 6 C colder gives 9.6 to 10.8%. The land poleward of 70 degrees, 6.7 to 8.2% of the land's area, has a mean annual temperature of -17.1 to -17.6 C and takes 16 to 19 mm. 2026-10-08, C1a.
- **`LakeWaterBalance.runoffShareOfRain` returns not-a-number for a vanishing positive rain.** Below about the potential evaporation over 3.4e38 the dryness overflows to infinity, `tanh(1 / dryness)` is zero and their product is not a number, which `coerceIn` passes through. No world is known to reach it; found by a probe that fed it the march's own first-lap rain. 2026-10-08, C1a. **Fixed at C1b**: worked in double, the infinite dryness's limit returned. 2026-10-08, C1b.
- **The march's misses against Earth, after C1b.** On the Earth-sized planet at 1,024 rows, seeds 42, 969495 and 7, weighted by area on the sphere, the march that closes its budget rains: land 913 to 1,316 mm against Earth's 756 (Trenberth and others 2007; 715 without Antarctica); open sea 851 to 1,178 against 1,032; the sea evaporates 1,301 to 1,472 against 1,143; the land returns 0.43 to 0.52 of its rain against 0.645; the atmosphere holds 19 mm against 24.7 and turns it over in 6.1 to 6.8 days against 8.9 (van der Ent and Tuinenburg 2017). Measured causes, not fixed (scope):
  - **The orographic term is most of the land's rain, and it grows with the grid.** `ClimateConfig.orographicStrength` rains a share of the column per step proportional to the climb, and a finer grid's rougher ground climbs more: 182 mm a year over the sphere at 256 rows on seed 42 against 363 at 1,024. It is the panel's knob and has no Earth source; the saturated column's own condensation on a climb (`ColumnWater`) is the thermodynamic half, and the two were meant to be measured against named ranges' windward-to-lee ratios before either moves.
  - **The land's return is low because its rain falls where it cannot be returned.** Half the land's rain falls where the potential evapotranspiration is under half the rain (windward coasts), where Budyko returns a quarter of it; Earth's land rain falls nearer a dryness of one. The same cause gave the C1a prototype's 0.30.
  - **The open sea evaporates too much because the column is dry.** The bulk formula reads the column's relative humidity as the surface air's; Earth's column holds about two thirds of its saturated water while its marine surface air stands near four fifths of saturation, so the surface deficit the formula sees is too large. A boundary-layer humidity from the column needs a vertical profile the march does not carry.
  - **The equatorial band.** The open ocean's zonal-mean rain peaks at 2,400 to 4,800 mm against GPCP's 2,920 (8 mm a day at 7 N; Adler and others), the column filling where the trades meet until it saturates. The convergence closure made it worse (6,800), and the moisture is carried on the surface wind where Earth's net column transport is the moisture-weighted wind's, smaller because the upper branch returns some of it.
  2026-10-08, C1b. **Superseded at C1b2**: the orographic term, the lifetime and the column's humidity at the sea are gone, and the misses that remain are the entry "The march's misses against Earth, after C1b2". 2026-10-09, C1b2.
- **The dry edges' bar is main's, not Earth's (C1b).** The review asked for the straightest dry-region edge on Earth's own Koppen raster (Peel, Finlayson and McMahon 2007; Beck and others 2018) as the bar for `RainGeometryTest`, read at this grid's 19.6 km cells. The raster is not in the repository and was not downloaded in C1b; until it is, the clause holds the change to main's figures. Also not done: a shifted grid, which the generator has no setting for (1,000 rows stands in for one). 2026-10-08, C1b.
- **The march's surface wind, its speed and its sun, all stand-ins (C1b).** The march carries water at the belts' 7.5 m/s east or west, with the belts' zonal speed constant up to their edges, so across an edge two air streams of different origin meet at full speed and the orographic term flips its windward side from one row to the next (the seasonal fields' pre-blur row steps of 4 to 6 at the edges). **The zonal wind closed at C1b2**: the belts' zonal wind is continuous through zero at each edge (`SurfaceBelts.zonalShare`) and the march carries water at each cell's own speed, so the reversal moves with the pressure field; what still draws rows is the entry "Fronts along the belts' factor". The land's potential evapotranspiration reads FAO-56's 2 m/s station mean for the wind at 2 m and Earth's land-mean transmissivity, 184.7 of 330.2 W/m² (Trenberth, Fasullo and Kiehl 2009), for the sun at the ground, so a desert's clear sky and a rainforest's cloud are the same. All three are the atmosphere's (the entry "Build the atmosphere"). 2026-10-08, C1b.
- **The belts' factor's floor has no Earth source (C1b).** `ClimateStage.MIN_BAND`, a twentieth of the rain's lifetime under the subtropical high, was kept as it stood; C1a's design guessed it inside a Saharan column's turnover of 110 to 365 days against 8.9 and left the figures to be checked. Reached smoothly now. 2026-10-08, C1b.
- **The ocean's wind stress still reads the belts' old meridional step (C1b).** `SurfaceBelts` gives the gyres the belts' slope as a constant across each belt, where the atmosphere's meridional wind is now a half sine, zero at every edge and at the equator. 2026-10-08, C1b.
- **The rain on the graphics card (C1b; the next chunk, C1c).** `MoistureAccelerator` is the seam, unwired: one workgroup per run of rows and sweep, a barrier per column, the faces' fluxes and the eddy exchange through shared memory and the bank in a buffer, held to the processor's rain and to its own ledger's closure. The march costs about 4.7 s a climate run at 1,024 rows against main's 2.6, and the climate runs three times a generation. 2026-10-08, C1b. At C1b2 a column is one tridiagonal solve over every row of both sweeps that meets one being marched (the Thomas algorithm, sequential in the rows: a parallel cyclic reduction on the card), and the physics per row is independent; the march costs 6.0 to 7.4 s a climate run. 2026-10-09, C1b2.
- **The marine inversion, re-measured on a march that takes its rain out (C1b).** `MoistureBudget.INVERSION_MEASURED_EFFECT` records 0.2% from the old march, a reservoir whose rate the lid held down; on a march that takes the rain out of the column the lid may bite, and `MoistureBudgetTest` prints the new figure. 2026-10-08, C1b.
- **The desert left the horse latitudes (C1b).** `GeographyAuditTest`'s band clause, asserted from Fix 3b, is a C1b known failure: pooled over the audit's worlds at 512 rows the desert's share of the 0 to 15 degree band's land is 2.95 times its share of all land, where Earth's is 0.27, and seed 42's 15 to 45 degree band holds 0.23 times it against Earth's 2.05. Measured causes, not fixed: in the warm half the belts' meridional wind converges from 10 to 25 degrees (the half sine peaks mid-belt, and the thermal equator stands at the tilt), so the subtropics take a monsoon's water in summer, and the orographic term rains it out of moist air on any climb; and the tropical continents' interiors are high dry plateaus some 900 km inland, which the march reaches with its water already rained out on the windward rise. The atmosphere's subsidence (the entry "Build the atmosphere") is what dries the horse latitudes on Earth, and the march has only the inversion's lid for it. `MeridionalWindTest`'s monsoon coast and `RainGeometryTest` print the figures. 2026-10-08, C1b. C1b2 restored the descent on every sink and measured the bands (the entry "The march's misses against Earth, after C1b2"). 2026-10-09, C1b2.
- **The ice after C1b.** The march that closes its budget snows on the polar land the water the old march's cold cap dropped, and the ice grew: the ice-sheet biome covers 8.6 to 10.0% of the land's area on seeds 42, 969495 and 7 at 256, 512 and 1,024 rows alike (Earth 10.1; main 1.8 to 2.5); `SnowBalanceTest`'s share, a count of the grid's cells that weighs the polar land several times over, reads 24.4% on its four seeds at 256 rows and fails its bar of twice Earth's, where by area it reads 7.86% against the control's 17.48%, so on the sphere the control sits inside the bar too and the guard wants a control that fails it (or the bar re-derived) when it is moved to area; the sheets stand 7.0 to 7.8 km at their thickest against Earth's envelope (`IceSheetTest`); 6% of the cold dry interior is ice (`SnowBalanceTest`, against 2%); the ice adds 2.9 to 3.1% of the standing water as parallel grid-bearing bars (`GlaciationCombTest`, bar 2%); the sheets cut a trough's depth into 36 to 45% of seed 718106's flat frozen country (`GlaciationLatticeTest`, bar 15%); and the geometry census's ice surface, measurable now on seeds 7 and 1234 at 512 rows, holds a crease and a round rim on each and a round rim on 99. All are C1b known failures; the brief measured the ice and left it. The thickness and the lattice are the ice's own physics (`GlaciationStage`'s flow law and scour, set when the sheets were a few percent of the land), the share is the snow's, and which to move first is the ice chunk's to measure: the snow's resolution dependence first, since a law cannot depend on the cell. 2026-10-08, C1b. The thickness is Glen's law's at C1b2 (the entry "The ice after C1b2"). 2026-10-09, C1b2.
- **The lakes on the wetter land (C1b).** The lakes take the annual runoff (`Runoff.annualRunoffMm`, Budyko against the march's potential evapotranspiration) and lose Penman's open-water evaporation, and the land rains more than Earth's and returns less of it (the entry "The march's misses against Earth"), so the catchments shed about twice Earth's runoff. C1b known failures: seed 59758's largest lake stands 1.10, 3.42 and 7.16 times the Caspian's share at 256, 512 and 1,024 rows, pooled over `OutletResolutionTest`'s six worlds 2.25 times it; two of `OutletIncisionTest`'s seeds start over the Caspian's share again and the notch takes their worlds' water to 0.68 and 0.76 of the control rather than under half; `LakeWaterBalanceTest`'s dry basin (217 mm of rain against 825 mm of open-water evaporation) holds 85% of its footprint against a bar of 50; `EarthLikenessTest` reads a lake-size exponent of 0.87 over 711 lakes against Downing's 1.06 and the drainage density peaking in humid country on seeds 42 and 99. Read as the rain's and not the lake balance's, since the largest lake grows with the grid as the orographic rain does; not separated further. 2026-10-08, C1b.
- **Dry branches add too little water to widen their trunks (C1b).** The drawn network's discharge is the annual runoff, which has no floor, so on seed 1234 at 512 rows 15.9% of confluences draw the trunk no wider than its larger branch: the branch's share of the trunk's water is under the float's last digit of the pen. A C1b known failure in `RiverWidthTest`. A pen that reads the storm weight (`Runoff.annualWeightMm`), which is what cuts the channel, or one with a floor on the ground, is the question; the discharge itself is right. 2026-10-08, C1b.
- **A world six degrees colder is no drier (C1b).** `AbsoluteRainfallTest`'s arid config (global mean 6 degrees colder, a glacial maximum's depth) carries 4.67% desert at 256 rows against the lush config's 5.26%, where Earth's glacial deserts were wider than today's; a C1b known failure. Why was not measured: the colder world's sea evaporates less and its air holds less, and the ice and the biome's thresholds move with the cold too, and which of them wins here is the question. 2026-10-08, C1b.
- **A pocket of water the ocean cannot reach survives the cut on seed 1234 (C1b).** `SeaLevelHistoryTest`: one body of 370 cells at 512 rows, under the enclosure cap, after the sea stage; which pass leaves it (the post-cut outlet, the lowstand or the ice) was not traced. A C1b known failure. 2026-10-08, C1b.
- **The march's misses against Earth, after C1b2.** C1b2 took out the three causes the entry "The march's misses against Earth, after C1b" measured: the column's rain is Bretherton, Peters and Back's (2004) relation to its relative humidity, so no lifetime is imposed; the sea evaporates against Dai's (2006) marine surface humidity, not the column's; and the climb condenses at Smith and Barstad's (2004) rate on the column's saturated share, with their two delays, so `orographicStrength` is gone. On the Earth-sized planet at 1,024 rows, seeds 42, 969495 and 7, on the sphere: land 471, 345 and 511 mm against Earth's 715 to 756; open sea 792, 822 and 708 against 1,032; the sea evaporates 872, 883 and 827 against 1,143; the land returns 0.68, 0.69 and 0.61 of its rain against 0.645; recycling 0.48, 0.46 and 0.44 against 0.40; the atmosphere holds 14.6 to 14.9 mm against 24.7 and turns it over in 7.8 to 8.4 days against 8.9, a result; desert 30.7, 39.9 and 39.4% of the land against Earth's BW 19.1 (Peel and others 2007). Measured causes, not fixed (scope):
  - **The sea evaporates a quarter less than Earth's** because the marine air stands at the sea's own temperature (the surface is 0.03 to 0.05 C over the air on average, where Earth's ocean is about a degree warmer than the air over it, which adds a fifth to the deficit the bulk formula sees) and the scalar wind is the belts' 7.5 m/s everywhere, where Earth's westerlies blow nearer 10. The column holds less water for it, and the sea and the land rain less.
  - **The land rains a third less than Earth's.** Bretherton's relation is the tropical oceans' monthly mean; over land the rain picks up at a lower column humidity and rises more gently (Ahmed and Schumacher's land against ocean; Schmidt and Hohenegger 2024 put the land's extra rain in the wetter tail of its humidity), and the extratropical land's rain is the storms' lifting, which a column-mean march has no term for. The land's columns run at 0.3 to 0.45 of their saturated water and rain little at that humidity; the climb's condensate is most of what they rain (152, 111 and 175 mm a year over the sphere against the column's own 16 to 24).
  - **The climb condenses on the column's saturated share, an assumption.** Smith and Barstad's source is for saturated flow; the march scales it by the column's water over what it holds at surface saturation (`MoistureMarch.HOLDABLE_SHARE`), so a column at half its holdable water condenses half the rate. Taken as the share of the time the flow is saturated; no source states it. Without the scaling the climb strips a dry column to nothing on every rise; with condensation only past surface saturation, the land rains 196 to 323 mm and 57 to 61% of it is desert (both measured at C1b2).
  - **The desert has not come back to the horse latitudes.** The desert's share of each band's land over its share of all land: 0 to 15 degrees x1.58, x1.27 and x0.90 (Earth x0.27), 15 to 45 x1.39, x1.37 and x1.56 (x2.05), 45 to 90 x0.25, x0.29 and x0.18 (x0.12); pooled over the audit's worlds at 512 rows 0 to 15 is x1.52, from C1b's x2.95. The belts' descent now slows every sink (the column's rain, the cloud's conversion and the convergence closure), as the brief asked; it is the deserts' total that moved, more than their place, because the land rains less everywhere. Without the descent on the cloud's conversion the bands read x2.06, x1.50, x1.00 / x1.18, x1.35, x1.54 / x0.13, x0.21, x0.12 and the desert 22.2, 32.6 and 33.3% (measured at C1b2), so the descent on the condensate does not place the desert either.
  - **The equatorial band.** The open ocean's wettest band takes 4,390 mm pooled at 512 rows against GPCP's 2,922; the convergence closure, switched on, takes 3,533, nearer (the entry "The convergence closure after C1b2").
  - **The march converges in 6 to 10 laps** to six thousandths of the land's rain, and costs 6.0 to 7.4 s a climate run at 1,024 rows against C1b's 3.9 to 5.8 and main's 2.3 to 2.9; the lag is the ground's return and the westward sweep's banking of what it trades across the zonal wind's reversals.
  2026-10-09, C1b2.
- **Fronts along the belts' factor (C1b2).** `RainGeometryTest`'s straight-front clause, a C1b2 known failure: the rain's longest front straight along a row, a doubling within the blur's width either side over land of 250 mm or more, runs 2,267 km at 50 N on seed 42, 2,681 at 50 S on 969495 and 3,761 at 50 N on 7, against a bar of 600 km, a quarter of the weather's wavelength; C1b's head read 1,147, 788 and 821. C1b2's continuous zonal wind takes out C1b's seam (the wind's sign flipping on a row at full speed); what holds a row now is the belts' factor on the rain, a function of latitude and season alone, which the restored descent multiplies onto the climb's condensate too: with the descent off the cloud's conversion the same fronts run 1,284, 1,045 and 828 km (measured at C1b2). The cure is a descent that answers the geography, the atmosphere's (the entry "Build the atmosphere"), or a thermal equator read off the model's own temperature column by column, so the belts bend over the continents. 2026-10-09, C1b2.
- **The convergence closure after C1b2.** On the march whose column rains at its humidity, the shipped march over-rains the ocean's wettest band (4,390 mm pooled at 512 rows against GPCP's 2,922) and the closure, switched on, takes 3,533, inside a quarter of GPCP's: `MoistureBudgetTest`'s clause that the closure is the worse of the two is a C1b2 known failure, and its clause that the closure over-rains is armed. The closure stays off: it is still an assumed rate, rain at the air's convergence, and not continuity. 2026-10-09, C1b2.
- **The ice after C1b2.** A sheet is Vialov's profile from Glen's flow law (n = 3) under its body's mean snow balance, with Cuffey and Paterson's (2010) rate factor weighted through Robin's (1955) temperature column over Pollack and others' (1993) continental heat flow, the bed held at the pressure melting point where Robin would pass it. At 1,024 rows on seeds 42, 969495 and 7 the sheets stand 1.39, 1.81 and 1.57 km on average and 4.39, 4.88 and 4.22 km at their thickest (Earth's Antarctica 2.1 and 4.8); at 512 rows `IceSheetTest`'s four worlds stand 4.7 to 5.5 km at their thickest, two over Earth's 4,776 m (a C1b2 known failure); the ice-sheet biome is 6.1 to 6.5% of the land against 10.1. Not done, each a figure the sheets would move by: no strain heating and no sliding (both would thin them), plane flow where a polar cap's diverges (2^(1/8) thinner), one dome per body where the snow falls unevenly, and the distances the jump flood measures in the equator's cell widths, so a body near the pole reads its east-west reach several times over. Vialov's dome is flat across its top, so near the divide of `IceSheetTest`'s reported world the flow is the margins' datums showing through more than the profile (64.5% outward at 72.4 degrees, a C1b2 known failure); the flow clause's control moved to seed 2, whose dome fills 80% of its disc. 2026-10-09, C1b2.
- **The drawing's constants on C1b2's gallery world.** The gallery's 12,000 km world is cold and dry on C1b2's rain: its closed forests a few thousand cells and its ice 59,000, so Scroll draws the steppe 165% of the way from desert to forest (`ClimateTintTest`), and a cone at the ninth decile of its land slope, 2.31, floors 39 of 360 faces under the sky (`ReliefShadingTest`); both C1b2 known failures, with why the slope moved not traced. The exaggeration's clause passes again and is armed. 2026-10-09, C1b2.
- ~~**A lake in two pieces (C1b2).**~~ Gone on A1-1's ground and the clause armed; the rule it exposed is untraced. `LakeBodyTest`: on C1b2's rain seed 1234's lake 71 and seed 99's lake 95 at 512 rows are each two pieces at one level, which L1's rule says cannot happen; a lake rule the rain exposed, not traced. A C1b2 known failure. 2026-10-09, C1b2.
- ~~**The climate's two seasons are both hemispheres' summer, then both their winters (A1a).**~~ Done at A1-1: the energy balance exports the calendar's July, January and the half-years about them (April to September, October to March, Peel, Finlayson and McMahon 2007) from its daily year, and the march, the belts, the pressure winds, the inversion, the sun, the sea ice and the snow balance read them; Koppen keeps each place's own warmest and coldest month, bit for bit as before, and the biomes read each cell's warmer half as its summer. See docs/DESIGN_LEDGER.md, A1-1. 2026-10-09.
- **A summer's contrast across a west coast is half of Earth's, and no larger than an east coast's (A1-1).** Measured as Earth's is read (`CoastContrast`: a month's sea-level temperature less its latitude's mean, the land's extreme within 2,000 km behind a coast against the sea's in front), seeds 42, 969495 and 7 at 1,024 rows read 7.6 to 11.5 C across subtropical west coasts in their July (and 8.0 to 8.8 across the southern ones in January) against Nakamura and Miyasaka's (2004) 18 to 20, and 9.4 to 15.3 across east coasts, where Earth's read 2 to 8 (their Fig. 2d); in January the mid-latitude coasts read -11 to -14 against Seager and others' (2002) -15 to -27. The contrast is the energy balance's land column less its marine column at that latitude and little else: the sea beside a subtropical west coast stands only 0.4 to 0.9 C under its latitude at its coldest, where the reanalysis puts the California and Canary Currents' air 6 to 10 C under it. Audited at A1-1 and not the causes: the columns' zonal exchange (the land column already swings 26.2 C at 30 to 40 degrees against Earth's interiors' 24 to 26, and the contrast equals the column gap) and the marine blend (it withholds 2 to 35% of the gap at the land's warmest point, 4 to 27% on most coast sets). What is missing, in order of size: the cold eastern-boundary sea, which the ocean stage's anomaly does not make (upwelling and equatorward advection under the highs, and the stratus over them: Nakamura and Miyasaka cite Klein and Hartmann 1993 for the stratus and Seager and others 2003 for the air-sea interaction that keeps the sea cold, neither read here); and the desert's own heat, since one land column carries wet and dry land alike and the Sahara's sensible heating is the same land as the Congo's. The first belongs to the ocean and to the atmosphere's marine-air and feedback chunks (the design's 4 to 6), the second to a land surface with its own Bowen ratio. `CoastContrastTest` records both clauses as known failures. 2026-10-09, A1-1.
- **The mixed layer is fifty meters all year (A1-1).** Since the sea surface takes its share of the sun, the water at 35 degrees swings 2.8 C from July to January on seed 42, against the 6 to 9 C `docs/GEOGRAPHY.md` gives for Earth's sea surface there, and the July marine air still stands 1.5 C over the water where Kara and others (2007) find the sea warmer than the air nearly everywhere. de Boyer Montegut and others (2004), which the constant cites, put the summer mixed layer at 20 to 30 m: a layer that shoals in summer would warm twice as far, and nothing here makes it. 2026-10-09, A1-1.
- **The coast takes a quarter of the sea's anomaly and the sea's air all of it (A1-1).** The air over the sea now carries the current anomaly whole (Kara and others 2007), while `ClimateStage.applyMaritimeInfluence` still hands the land a blurred quarter of it, so the first land cell beside a cold current stands warmer than the air one cell offshore by most of the anomaly. W1 measured the corrected form making the coasts worse and recorded that the fix is a directed one along the wind; the atmosphere's marine-air chunk is where that wind will be. 2026-10-09, A1-1.
- **The perennial pack is five sixths of the winter pack, and the pole opens on the warmest month (A1-1).** The biome draws as sea ice the water frozen through its own warmest half-year, as before A1-1: 22.2, 20.6 and 26.5% of the sea on seeds 7, 42 and 1234 at 256 rows against a winter pack of 26.4, 24.3 and 31.2%, where Earth's minima are a quarter of its maxima (NSIDC: 6.5 and 15.5 million km² in the Arctic, 2.5 and 18.5 in the Antarctic). Read on the water's warmest month the shares are 8.2, 5.7 and 8.4%, Earth's ratio, but the pole's sea opens and a frozen stripe stands twenty degrees from it, because the energy balance carries no latent heat: a frozen column under the polar summer's sun warms past its melting point instead of melting its ice at it. The sea surface's sunlight is withheld from the frozen share of a band for the same reason. What would answer it is a sea-ice budget, growth in winter against melt in summer at the melting point, the sea's twin of the snow balance; and the stripe the pack's edge draws along a row is W1's finding still. 2026-10-09, A1-1.
- **The ice after A1-1.** The calendar's halves and the sea surface's sunlight feed the sheets more snow: the ice-sheet biome covers 9.2, 8.2 and 8.8% of the land on seeds 42, 969495 and 7 at 1,024 rows (6.5, 6.1 and 6.5 before; Earth's 10.1), and `IceSheetTest`'s four worlds at 512 rows stand 5.3 to 6.6 km at their thickest against Earth's 4,776 m (4.8 to 5.5 before), the C1b known failure's signature re-recorded; the share of the land's *cells* at 256 rows is 24.5% (`SnowBalanceTest`, recorded again with C1b's note that a cell count weighs the polar land several times over), and the ice's comb, the share of the world's water it adds as parallel bars, reads 4.19% on seed 718106 and 2.07% on seed 42 where C1b2 recorded 2.04% on 718106 alone. The flow clause's control moved from seed 2, whose sheet filled 17.7% of its disc on A1-1's first pass, to seed 17 (99.1%); on that pass, of the eleven seeds from 1 to 25 that fill a third, four passed the flow clause, where five of seven did at C1b2, and four read an indifferent bearing's outward share or less (seeds 4, 6, 23 and 24). The reported world's sheet no longer fills a third of its disc, so its C1b2 known failure is not read and the clause is armed on seeds 42 and 17 alone. Not traced. 2026-10-09, A1-1.
- **An ice-made lake drawn as a straight one-cell line (A1-1, rule 13).** With A1-1's larger ice, seed 718106 at 1,024 rows carries two lakes and seed 42 one that are a straight one-cell line along a D8 bearing (`GlaciationCombTest`), the defect K2 recorded and C1b2's ice had cleared: a trough cut down a flow path rather than a valley the ice found. The ice's comb grows with it: the share of the world's water the ice adds as parallel bars reads 5.33% on seed 718106 and 3.40% on seed 42, where C1b2 recorded 2.04% on 718106 alone (`GlaciationCombTest`'s known failure, re-recorded). Not traced; a known failure. 2026-10-09, A1-1.
- **The lakes after A1-1's ground.** The provisional climate's new seasons moved the ground, and two lake clauses C1b2 had armed fail on it, each recorded as a known failure and neither traced: seed 99's world at 512 rows keeps 0.59 of its water when its over-large lake's outlet is cut (`OutletIncisionTest`), and seed 1234's lowstand leaves 109 estuary mouths against 112 without it (`SeaLevelHistoryTest`). On the same ground C1b2's lake in two pieces is gone (`LakeBodyTest`, armed), and so is C1b2's seed 1234, where the annual mean separated the marginal band's wet and dry quarters better than the snow balance (`SnowBalanceTest`, armed; seed 99's band now holds too little land to read, and the clause asks three seeds of four). 2026-10-09, A1-1.
- **One world's coast projects further one way than its length allows (A1-1).** Seed 99 at 512 rows reads 0.94 against three of its own spreads, the four worlds 1.00 together (`GroundIsotropyTest`), as at K2 before C1b2 armed it; the ground moved with the provisional climate. A known failure. 2026-10-09, A1-1.
- **The biharmonic is not accurate within a few rows of a pole (A1-2).** `SphericalOperators.biharmonic` is the Laplacian twice. Equatorward of 80 degrees it converges at second order on the harmonics (l = 8, m = 0, 1, 3 and l = 12, m = 6: 1.9 to 2.0e-3 at 240 rows), but over the whole sphere the waves m = 1 and 3 stand 0.23 and 0.50 off at every grid and do not converge: a second-order Laplacian's first rows carry an error of the order of the field's own small value there for every zonal wave but the mean, and the second Laplacian multiplies it by `m^2 / cos^2(phi)`. It stays symmetric and never negative, so as the dry model's hyperdiffusion (Ting and Yu's 10^17 m^4/s) it only damps, and most where a pole cannot hold a wave; read as a tendency near a pole it is wrong. The dry model must either take it as dissipation only or regularize the polar rows (a polar filter, or a pole condition per zonal wave). 2026-10-09, A1-2. **Replaced at A1-3**: the dry model's scale-selective damping is a Laplacian of vorticity, divergence and temperature (`WaveDamping`), built from the operators that converge over the whole sphere, so the biharmonic is read nowhere; a fourth difference would also have coupled rows two apart, which the block-tridiagonal solve cannot hold. Fixing the biharmonic itself (a pole condition per zonal wave, `s = sin^m(theta) g` with `g` even) is open if a later model wants it. 2026-10-09, A1-3.
- **The dry model must show its winds and vertical motion converge on the atmosphere's grid (A1-2).** The grid's rule (`SphericalGrid.rowsForAtmosphere`) is proved for the forcing scales the guards read: the operators and the forcing path carry a Gaussian of the storm track's deformation radius, 970 km, to 1% at 150 rows on Earth's planet. The review's point stands for what the model makes from that forcing: the third vertical mode's deformation radius in mid-latitudes is about 100 km, and a response sharper than its forcing (near a critical line, in the boundary layer) would need more rows. A1-3 repeats the ladder on its own winds and omega before any guard reads them. 2026-10-09, A1-2. **Shown at A1-3** (`StationaryWaveReport`): with forcing spread on the ground (`WaveForcing.fromGround`), the winds, the 500 hPa omega and the surface pressure stand within 0.66% of the exact answer at 150 rows (extrapolated from the ladder 60 to 240 at second order), and within 0.4 to 0.8% for Gill's, Hoskins and Karoly's jets and Rodwell and Hoskins' benchmarks; the rule stands. Hoskins and Karoly's sharp-edged mountain read unspread on super-rotation (no critical line, the weakest damping) wants 200 rows for 1%. This discretization's modes are faster than the design's `N H / (n pi)` (95, 42 and 23 m/s at four levels), so at four levels the third mode's deformation radius in mid-latitudes is about 220 km, not 100. 2026-10-09, A1-3.
- **The atmosphere's grid is set by the coast's filter, not the operators (A1-2).** The four operators meet 1% on the storm track's scale at 4.2 rows per deformation radius (90 rows on Earth's planet); the forcing path needs 7.1 (150 rows), because the diffusion that holds a coast's step to 2% overshoot (0.8 coarse rows wide) costs a feature of 970 km a percent of itself unless the rows are finer. A looser bound on the overshoot, or a filter that rings less for its width, moves the grid; the bound's derivation (0.4 C on Earth's 18 to 20 C July contrast across a west coast) is the place to start. 2026-10-09, A1-2.
- **The atmosphere's remapping on the card is held to the processor and not yet used (A1-2).** `GpuAtmosphere` carries a field down and up within single precision's rounding (`GpuAtmosphereTest`), but nothing in the engine carries a field to the atmosphere's grid on the card: the pressure wind's smoothing carries each of a world's thirteen pressure fields down and up on the processor, the whole field and its wind 0.47% of a world (`PressureWindCostTest`). Measured at 2,048 by 1,024 onto 150 by 300 (`AtmosphereRemapCostTest`, `GpuAtmosphereTest`): the area mean 1.0 to 2.6 ms on the processor and 7.5 to 11.9 on the card with its upload, so carrying down stays on the processor, 0.28% of a world at the design's eighty updates; carrying one field up 14 to 38 ms on the processor and 20 to 48 on the card between runs, uploading the coefficients and reading the field back each call, so the card is not yet the faster path, and the processor's carry is 9.3% of a world at four fields an update. When the coupled atmosphere carries those fields, the card's path wants its buffers resident and the drag balance on the card beside it, so nothing is read back between them. The forcing's filter on the coarse grid measured 12 to 19 ms a field, 2.8% of a world at the same count, which is the coarse grid's 45,000 cells and not the map's: the dry model's to budget, starting with why four implicit steps on so small a grid take that long. 2026-10-09, A1-2.
- **A dry edge along a row on seed 969495 (A1-2, rule 13).** With the pressure smoothed and differentiated on the sphere, seed 969495's 250 mm isohyet runs 734 km straight along 42.6 N across a continent's interior at 1,024 rows (main 505), where the rain falls from 1,274 to 37 mm over twelve rows at every one of its columns: a zonal gradient the belts set, which the regional wind may no longer break up there: the Gaussian is 970 km east-west on the ground at that latitude where the cell-counted box was 715. The other two seeds' edges shortened (447 against 487, 407 against 470), pooled 529 against main's 487 (`RainGeometryTest`, a known failure). Untraced beyond that; the solved atmosphere's stationary waves are what should break such a band. 2026-10-09, A1-2.
- **Only two seeds carry a marginal band for the snow balance's claim (A1-2).** `SnowBalanceTest`'s *at the same temperature, ice is where the snow is* reads the claim on each seed with a thousand cells or more of land at a summer of -6 to -3 C; seed 99 fell under it at A1-1 and seed 1234 at A1-2, so the claim is read on seeds 7 and 42 alone, where it holds (wet 99.4 and 100% iced, dry 0.0%). A known failure; the fixture wants a third seed with the band. 2026-10-09, A1-2.
- **Two Earth-likeness clauses fail on A1-2's ground (A1-2).** `EarthLikenessTest`: humid country carries 1.01 to 1.03 times the channel per unit of land that semi-arid country does on seeds 7, 42 and 1234 (1.01 pooled), where Moglen, Eltahir and Bras (1998) put it below one; and the pooled lake-size exponent is 0.825 over 421 lakes against Downing and others' 1.06 +/- 0.15. Both passed at A1-1 and are recorded in the clause's known failure; the first stands within a few percent of its bar. Untraced. 2026-10-09, A1-2.
- **Four levels are not enough for the surface pressure (A1-3).** Against 24 equal-mass levels at fixed pressures (`StationaryWaveReport`, *the responses converge in the vertical*), the surface pressure of Hoskins and Karoly's 45 N heating stands 64% off (relative RMS) at four levels, 21% at eight and 4.5% at sixteen; Rodwell and Hoskins' monsoon 66%, 17% and 2.9%; their mountain 19%, 6.2% and 1.2%; the 500 hPa omega 20%, 7.8% and 1.7% for the heating. Where things lie does not move with the levels (the 45 N trough at +19 to +21 degrees, the monsoon's descent 4 to 8 degrees west of the heating, the super-rotation train's wavelength within 2 to 8% of 5,019 km on 2+BL, 4, 5, 8 and 16 levels); how deep they are does (that trough 1.79, 2.59, 2.18, 1.73 and 1.49 hPa). Four levels put the top layer's level at 125 hPa and carry no tropopause, and the gravest mode's speed keeps rising with the levels (79, 95, 119 and 175 m/s at 2+BL, 4, 8 and 16), as a continuous atmosphere's spectrum to zero pressure does. Before the coupled model reads the surface pressure: sixteen levels (cost below), or levels set by the tropopause with a sponge above it, measured the same way. 2026-10-09, A1-3.
- **The stationary-wave model wants a graphics path or fewer solves (A1-3, rule 8).** On Earth's coarse grid, 150 by 300, waves 1 to 100, with the machine's pool (`StationaryWaveCostTest`, audit tier, a world of 53.4 to 53.5 s at 1,024 rows, three runs): four levels factor in 30 to 53 ms and solve by back-substitution in 14 to 31 ms (162 MB of factors, read from memory each solve) or factor and solve at once in 37 to 43 ms; eight levels 219 to 224, 29 to 36 and 166 to 179 ms (692 MB); sixteen levels about a second a solve, factored afresh (2.9 GB of factors could not be kept). At the design's 8 factorings and 80 solves a world that is 2.9 to 5.4% of a world at four levels, 7.7 to 8.7% at eight and about one and a half worlds at sixteen, all over the measured exception's hundredth; a zonally varying drag solved by GMRES (the design's section 2.3) multiplies the solves by its iterations. The design's one-workgroup-per-wave block Thomas sweep on the card is the path; the chunk that couples the model decides its counts and builds it, held to the processor by a `GpuAtmosphereTest`-style comparison. 2026-10-09, A1-3.
- **No Hadley circulation in the dry model's basic state (A1-3, the review's point 7).** The prescribed state is balanced (Jablonowski and Williamson's `v = 0`), so the Hadley meridional wind's advection of the waves is not linearized about; the waves' vertical advection of the basic momentum, `omega dU/dp`, is kept. Earth's Hadley wind is 1 to 3 m/s against subtropical westerlies of 10 to 30 aloft, a tenth of the zonal advection there but the whole of it near the equator, where Lee and others (2013) found it carries the northern monsoons' signal to the southern highs. It enters with a basic state derived from the energy balance, with `V dX/dy`, `v dV/dy`, `omega-bar dX/dp` and the terrain's `V dh/dy` lift. 2026-10-09, A1-3.
- **Jablonowski and Williamson's jets have no trades (A1-3).** The benchmarks' realistic state is westerly at every level and latitude (zero on the equator), so Hoskins and Karoly's 15 N source puts its surface trough at -1 degree where theirs, on observed winter winds with low-level easterlies, is at -14; and Rodwell and Hoskins' descent at 35 N lies 5 to 10 degrees west of the heating (jets of 35 and 20 m/s) where theirs, on observed June to August winds, is 15 to 30 west, at 0.54 to 0.67 hPa/h against their 0.75 to 1. The 45 N trough and the super-rotation's trains, where the basic state is not the question, match. A climatological basic state with trades and the summer's subtropical easterlies aloft (or the energy balance's own) should be read against both before the coupled model is judged on its monsoons. 2026-10-09, A1-3.
- **Three of the dry model's sources were not opened (A1-3).** Ting and Yu (1998) and Lee, Wang and Mapes (2009) were refused by their hosts in this chunk, so their damping figures are the review's and the A1a design's reading; Rodwell and Hoskins (1996), whose idealized heating's extent their 2001 paper cites, could not be found, so the benchmark's ellipse (10 by 20 degrees) is a choice, stated in `WaveBenchmarks`. Held and Suarez (1994) was read through the ClimateMachine implementation, as the review did. 2026-10-09, A1-3.
- **The rain after the boundary layer: belts' rain factor, solved wind (A1-4).** Chunk 5 replaces the march's latitude-only belt rain factor (`ClimateStage.seasonalBand`, `MIN_BAND`) with the vertical motion; until then the march's sinks are the belts' while its wind is the solved one. Measured at 1,024 rows, seeds 42, 969495 and 7, before and after: land rain 421, 298 and 472 mm to 946, 835 and 911 (Earth 715 to 756, Trenberth and others 2007); sea rain 819, 847 and 736 to 596, 599 and 540 (Earth 1,032); desert 21.6, 30.1 and 28.3% of land to 8.4, 15.4 and 13.8% (Peel and others' 19.1). The cause is what W2's departure did and the solved wind does not: the belts alone (`pressureWinds` off) rain 1,140 mm on seed 42's land and 597 on its sea, and W2's thermal pressure, 2.48 hPa a degree of the half-year's whole departure from its row (the altitude's lapse and the weather noise in it), blew the continents' air out over the sea in winter; the dry model's land-sea heating makes 1.6 hPa (root mean square, 15 to 60 degrees, seed 42 July) against the terrain's 5.0. Ruled out on seed 42 at 512 rows (land 883 mm with the change): land's drag set to the sea's, 937; FAO-56's wind at its 2 m/s, 871; the sea's evaporation at the belts' 7.5 m/s, 912; no eddy pressure at all, 963. Earth's land rain is between the branch's and this; the vertical motion's descent and the marine air's cold seas are the two mechanisms still missing from the march. 2026-10-09, A1-4.
- **The dry model's boundary layer and the diagnosed one differ by their drags (A1-4).** The surface wind is the pressure's drag balance at W2's drags (5.8 hours over the sea, from Holton and Hakim's cross-isobar angle), and the vertical motion at the layer's top is its convergence over the model's lowest layer (125 hPa at eight levels): the three are one boundary layer (`BoundaryLayerTest`). The dry model's own lowest layer is damped by the surface stress, `rho C_D |V| g / dp`, 1.4 days, and its own convergence at the same interface follows the diagnosed one at r = 0.46 to 0.54 and 0.28 to 0.54 of its size, 35 to 65 degrees on the four standard worlds. The diagnosed layer's Ekman pumping is the stronger of the two by about the ratio of the drags, and by Holton and Hakim's Ekman layer (`w = zeta (K / 2f)^(1/2)`) at K of 5 to 10 m2/s about two to three times too strong; the dry model's is nearer it. Before chunk 5 turns the vertical motion into rain, one drag for the layer: either the surface's angle carried by a thinner layer, or the stress's rate for the wind the march carries. 2026-10-09, A1-4.
- **The prescribed basic state is not the season's (A1-4).** Jablonowski and Williamson's state is the same in both halves, westerly at every latitude down to the ground (8.4 m/s at 45 degrees at the surface), so the summer hemisphere's terrain makes stationary waves as strong as the winter's, and the trades' terrain is lifted by a westerly: three quarters of the eddy sea-level pressure is the terrain's (5.0 against the land and sea's 1.6 hPa root mean square, seed 42 July, 15 to 60 degrees). Its jets and the energy balance's agree in size: the thermal wind of the energy balance's own zonal-mean gradient from the ground to 250 hPa is 16 to 28 m/s at 30 to 60 degrees on seed 42 against the state's 19 to 26 (`BoundaryLayerTest`, *the prescribed jets*); deriving the state from the energy balance, trades and seasons and all, is the chunk after the moisture's. 2026-10-09, A1-4.
- **The subtropical highs stand ten degrees poleward and five hectopascals high (A1-4).** On the three worlds at 1,024 rows the dry atmosphere closes four cells over the sea at 15 to 45 degrees in the six halves (`BoundaryLayerEarthTest`, a report): seed 42 July at 42.6 N, 1,032.8 hPa, prominence 16.5 hPa, basin position 0.56; seed 969495 July at 43.8 N, 1,031.8 hPa, 9.0, position 0.69; seed 7 January at 37.8 S, 1,031.6 hPa, 15.0, position 0.51 (the branch's pressure, the belts' zonal mean with W2's departure, closed two, at 0.09 and 0.80 of their basins, prominences 4.0 and 2.0). Nakamura and Miyasaka's July cells sit near 35 N in the basins' eastern portions over 1,020 hPa. The ridge is the belts', which migrate the full ten degrees with the thermal equator (the design's risk 2), and the cells are mostly the terrain's; no winter-hemisphere cell closes. Latent heat is chunk 5's. 2026-10-09, A1-4.
- **One transient wind everywhere (A1-4).** The sea's evaporation and FAO-56's wind read the mean wind and the weather's gusts in quadrature, the gusts 4.23 m/s at every latitude (`BoundaryLayer.TRANSIENT_WIND_MPS`, from Archer and Jacobson's ocean mean and the belts' mean square), and land's 10 m wind is 0.58 of the sea's under the same weather (`LAND_ROUGHNESS_SHARE`). The storm tracks (chunk 7) should carry the transient part: Earth's is larger in the storm tracks and smaller in the trades. The ocean's stress reads the mean wind's `|V| V` alone, as the belts' always did. 2026-10-09, A1-4.
- **The sea's current anomaly does not force the atmosphere (A1-4).** The land-sea heating is the energy balance's own columns blended by the marine air's reach, without the currents' anomaly, so the ocean's stress and the climate solve the same atmosphere and the currents are not forced by a wind their own warmth made. Nakamura and Miyasaka's cold eastern seas, the other half of the subtropical highs' forcing, enter with the marine air and the ocean's feedback (chunk 6). 2026-10-09, A1-4.
- **A coast's step rings at the coarse grid's scale (A1-4, rule 13).** `CoarsePeriod` reads its standard error over lines since A1-4, each line against its neighbors within a coarse period, because one row's coast or front made a phase of its own when the error was counted cell by cell. Read that way, A1-2's continent step carried down through the forcing's filter and up again shows the coarse grid down the map, x1.095 against a bar of 1.071 (`AtmosphereRemapTest`, a known failure; along the map x1.005, and the smooth bump passes at x1.047 against 1.048): the 1.67% overshoot the filter leaves rings at the coarse grid's own wavelength. The boundary layer's pressure and vertical motion carried up through the same filters pass with room (x1.000 to x1.003 against 1.003 to 1.023). A wider forcing filter or a smoother step reading is the place to start. 2026-10-09, A1-4.
- **The 512 census after A1-4 (rule 13).** `GeometryGuardTest` records twelve violations on the four standard worlds against nine at A1-3: new are a right angle in seed 7's realm borders at (968,108), a right angle in seed 42's biome edges at (605,422), a side of seed 42's sea temperature anomaly 62 cells straight along row 247 at column 959 (the standing finding *the sea temperature anomaly runs straight along rows*, on the equatorial water the eddies' stress now drives), seed 7's ice surface with three arcs where it had one, and seed 1234's terrain contours with the arc its ice surface carries, moved to (835,50); seed 99's two ice arcs and its contour arc are gone. Untraced; the climate's new wind moved the ground through the provisional climates, and the ocean through its stress. 2026-10-09, A1-4.
- **The everyday tier's clauses the new ground broke (A1-4).** Recorded as known failures, each untraced: seed 42's shelf off east- and west-facing coasts holds 88% of its band against the clause's nine tenths (`ContinentalShelfTest`); the gallery cone has 37 of 360 bearings floored again (`ReliefShadingTest`, armed at A1-2); the four seeds' desert shares stand 1.46 times apart against 1.5 (`AbsoluteRainfallTest`, armed at A1-2); the land gives back 0.514 of its rain against Earth's 0.645, x0.80 (`RainAgainstEarthTest`, held since C1b2), with the wetter land. Armed: the land's rain against Earth's (x1.14, recorded at x0.55 since C1b2) and the engraving's slope floor (recorded since K2). The ice-flow control's seed 17 now holds 8 cells near its dome, under the third of its disc the clause reads, so the control takes the first of the clause's seeds whose sheet has a dome (`IceSheetTest`). 2026-10-09, A1-4.
- **The deep tier's clauses the new ground broke (A1-4).** Recorded as known failures, each untraced: seed 1234's windward flank multiplies its share of the stream-power law's rate by 1.54 under the 1.57 the law asks (`ClimateFedErosionTest`); the cold sample coast rains 501 mm with the currents on against 459 without, wetter where it should be drier (`CurrentFeedsRainTest`; the atmosphere does not see the sea's anomaly, and the march's evaporation does), armed since Fix 3b, and the warm sample coast 2.5% drier with them (40.6 to 39.6 mm), armed at A1-2; seed 42's sheet flows outward from its dome at 65.9% and 66.6 degrees off radial against the 67% and 67.5 the clause owes (`IceSheetTest`); seed 718106's largest lake is 2.09 times the Caspian's share of the map, the land being wetter (`OutletIncisionTest`, armed at K2); and the author's world at 1,024 rows holds one ruled bar of standing water (`StraightRunTest`, rule 13, armed since Q2). Re-recorded: the ice's comb of parallel bars at 4.19, 3.77 and 2.11% of seeds 718106's, 42's and 7's standing water (5.67 and 2.85% on two seeds before; `GlaciationCombTest`), and the over-large lakes' water falling from 1.67 to 1.45% on seed 718106 and 1.60 to 0.95% on seed 7 with their outlets cut, where seed 99 kept 1.16 of 1.55% before (`OutletIncisionTest`). Armed, each passing on A1-4's ground: the realised cut read under the Courant bound (`ClimateFedErosionTest`), no ice-made lake a straight line (`GlaciationCombTest`), the doubled slant moving both the deserts and the rain (`GridShapeTest`), the convergence closure no nearer GPCP's band than the shipped march (`MoistureBudgetTest`), the lowstand's drowned valleys pooled and no pocket of unreachable water on seed 1234 (`SeaLevelHistoryTest`), and two seeds or more starting with a lake over the Caspian's share for the notch to take down (`OutletIncisionTest`). The monsoon clause holds: pooled over the four subtropical continents at 512 rows the warm half blows +0.23 m/s onshore and the cold half -0.83 (the belts alone +0.79 and -0.25), the eddies' highs over the continents taking from the summer's inflow (`PressureWindTest`). 2026-10-09, A1-4.
- **The ice surface's arcs in the 512 census (A1-2, rule 13).** `GeometryGuardTest` finds a circular arc on the ice surface of seeds 7 (154.5 at (589,461)), 1234 (124.4 at (734,24)) and 99 (two, at (998,22) and (181,24), the first also in its terrain contours), recorded as known in `GeometryExpectations`; A1-2's wind moved the provisional snow and so the sheets. The arcs are the one-profile-per-source fans X1b handed to X1a. 2026-10-09, A1-2.
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
  or may not be the same mechanism. 2026-09-19, F35. Measured again 2026-09-23 (Audit III C-I1):
  59758 at 2048 reads 0.82 times the share and the world is back in the pool, which reads 1.13
  over all six (59758 at 512 alone 2.09) and runs as a known failure; the lake is no longer the
  finer grids' but the pool's.
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
- ~~**Two `JumpFloodDistance` callers still measure north-south distance with the cell's width.**~~
  Closed on every grid the application makes by the switch to square cells, 2026-09-29, Q6: since Q5 every size is a grid twice as many cells across as down, so a row and a column are the same length on the ground and the cell's width is its height: the glaciation's two distances are
  kilometers both ways. A test's grid of cells twice as wide as tall still has it. As it stood:
  S2b gave the flood a row scale and passed it from `PlateStage`'s craton reach and sea-floor age,
  and Fix 2 from `ClimateStage.waterDistance`, `SeaLevelStage`'s distance to land and
  `PlateStage.boundaryDistance`, with a guard apiece. What still counts cells and converts with
  `cellWidthKm`, so reaching half as far north-south as the kilometres it is given, is
  `GlaciationStage`'s distance to the ice edge and distance to ice, which belong with the ice's own
  redesign. 2026-09-14, S2b; 2026-09-24, Fix 2.
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
- **Closed 2026-09-29 by L1.** *No seed left in the rift scan floods as three separate gulfs, so that bar is withdrawn.* With Earth's half-grabens (60 to 160 km, sills 50 km across) the scan over seeds 1 to 40 finds seeds 33 and 35 flooding as three and four bodies of sea against the control's one, and the clause is armed again on seed 35 (`RiftSegmentationTest`, docs/DESIGN_LEDGER.md, L1). What follows is the entry as it stood.
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
- **An old belt's rounding is not the same spread in kilometers at every grid.**
  `PlateStage.roundWithAge` rounds a past epoch's uplift by a Gaussian of spread
  `sqrt(2 r (r + 1) / 3)` cell widths, with `r` the setting `TectonicsConfig.beltAgeBlurKm` read in
  cell widths: the spread two box passes of that radius had. The formula is not proportional to
  `r`, so the same 70 km radius spreads 0.94 of itself at 23.4 km cells and 0.85 at 5.9 km cells
  (2.83 and 10.2 cell widths of spread for 3 and 12 cell widths of radius at one epoch). The
  physical statement is a spread in kilometers, `sqrt(2/3)` of the radius, which is what the
  formula tends to on a fine grid; Q2 kept the formula because restating it would move every world
  at every grid, and a chunk that changes the tectonics should take it. 2026-09-28, Q2.

- **Reopened 2026-10-08 at C1b**, whose rain moved the ground: four sea cells of the export's world come back over its shoreline (`DataExportTest`, a C1b known failure), the cause still untraced. **Closed again 2026-10-08 at K2**, whose worlds have no sea cell over its shoreline; the clause is armed, and the cause of the cell L1's worlds had was never traced. **Reopened 2026-10-03 at L1's review round.** L1's first rifts moved seed 42's ground and took the cell with it, and the clause was armed (closed 2026-09-29); with the rift valleys at Earth's width one sea cell of seed 42 at 512 rows stands over its shoreline again, where is not located, and `DataExportTest`'s clause is recorded again as a known failure. The cause is still not traced. *One sea cell of seed 42 at 512 rows stands above the shoreline the sea stage cut.* At column 33,
  row 414, beside the land, 9.0e-5 of the field (about half a meter) over the line; the same seed on
  the 512 by 512 grid has none. `DataExportTest`'s open-sea clause, which the heightmap draws
  faithfully, runs as a known failure on it. Which of the sea stage's rules leaves water over the
  cut (the drowned valleys, the littoral grading, the enclosed-sea repair) is not traced.
  2026-09-28, Q4.
- **A lake's area still follows the cell count after L1, and a lake still floods the one-cell
  gullies around it.** Q4's entry, updated by L1 (docs/DESIGN_LEDGER.md, L1). What L1 found and
  fixed: the rifts' half-grabens were drawn from a stream seeded by a cell index, so a rift was a
  different chain at every grid, and seed 42's trough held a closed basin of 2,455,444 km2 of
  catchment and a lake of 281,662 km2 at 512 rows alone; and a closed basin's water stood at one
  level over the lowest cells of the whole basin, so its separate hollows were drawn as one lake
  (seed 42's lake 1 at 512 rows was eight pieces). Both are fixed: a rift is the same rift at every
  grid (`RiftIdentityTest`), and each hollow is its own lake (`LakePockets`, `LakeBodyTest`).
  What remains, measured on `ScaleFreeTest`'s four seeds as a seed's lake share of land and its
  largest lake across 256, 512 and 1,024 rows (the tree before L1 in brackets): seed 7 x1.96 and
  x5.38 (x1.28 and x1.67), seed 42 x1.49 and x1.46 (x2.87 and x4.73), seed 1234 x2.10 and x1.50
  (x1.79 and x2.83), seed 99 x1.59 and x3.48 (x3.21 and x8.08), against a provisional bar of 1.35
  run as a known failure. Re-taken at L1's review round, whose joins are relay ramps: x1.36 and
  x2.30, x1.56 and x2.58, x1.80 and x3.44, x4.53 and x13.52: seed 99's largest lake 254,883 km2 at
  256 rows and 18,848 at 1,024, a drowned trough basin held as land that the post-cut outlet
  drained at one grid and not the other. With the valleys at Earth's width the basin is not made
  and the spreads read x1.66 and x2.47, x1.23 and x1.31, x2.21 and x2.56, x1.26 and x1.38; with the
  outlet off seed 99's largest lake is 488,342, 274,246 and 291,069 km2 at 256, 512 and 1,024 rows,
  which is L2's to take (docs/DESIGN_LEDGER.md, L1). On the five seeds of L1's diagnosis the whole lake area reads, at 256,
  512 and 1,024 rows and on the 512 by 512 grid: 42 310,913 / 330,963 / 464,172 / 321,625 km2; 7
  340,027 / 229,202 / 173,653 / 213,135; 99 445,496 / 280,014 / 410,751 / 310,913; 718106
  134,583 / 178,116 / 199,677 / 162,048; 59758 244,446 / 330,276 / 343,117 / 409,515. Three
  causes are left, each measured and none fixed here:
  - **The post-cut outlet** (L2, next): it cuts 1.35 m a pass and is still cutting at its sixteen
    passes, so a drowned basin's size is the pass count's. Seed 99's largest lake, 329,556 km2 at
    1,024 rows against 94,757 at 512, is a basin whose floor stands 130 m below the sea.
  - **Whether a rift trough is sea or land follows its sills.** With Earth's half-grabens the
    trough is a chain of basins joined by sills 50 km across; on seed 42 the chain is one body of
    enclosed water over the enclosed sea's cap (`SeaConfig.enclosedSeaMaxShareOfSurface`) at 256 and 512 rows, so it stays sea, and at
    1,024 rows the sills part it into basins under the cap, which become land and hold a chain of
    lakes, closed but for one (the world's closed water is 124,420 km2 there against 38,315 at 512
    rows). Earth's rift chains are lakes where the
    sea cannot reach them, which is the 1,024-row answer; the sea stage's enclosure rule and the
    post-cut outlet between them decide it at each grid, which is L2's ground.
  - **Chaos.** Re-drawing the routing's per-cell sub-grid draw (`FlowRouting.subGridDraw`'s salt)
    at 512 rows moves the five seeds' lake area by -11%, +4%, -25%, +19% and -22% and the largest
    lake by up to 44%; re-drawing the delta lobes' per-cell wobble (`HydraulicErosion.wobble`)
    moves it by +23%, -10%, +1%, -2% and -7%, seed 42's largest lake doubling. Both draws are per
    cell by design, standing for relief finer than a cell, so there is no key that makes them the
    same draw at another grid; what they show is that a lake census cannot hold tighter than about
    a quarter between any two runs that route differently, which is the floor any bar on it sits on.
  The gullies are unchanged: the comb (`CombGuardTest`'s `SYMMETRIC_COMB`) still stands one-cell
  gullies under the lakes' shores. Recorded without a bar in `ScaleFreeTest`, since no fractal
  dimension for lake shores has been sourced independently: the lakes' shore per km2 of lake grows
  from 0.073 to 0.156 to 0.269 km on seed 7 across 256, 512 and 1,024 rows, an outline dimension of
  1.53 and 1.39 read as 1 + log2 of the growth, and 1.28 to 1.92 on the other seeds, where natural
  coasts run 1.2 to 1.3. No claim of resolution independence is made for lakes until L2 lands.
  2026-09-28, Q4; 2026-09-29, L1.
- **Whether a plate is continental can change at 256 rows.** The crusts are handed out by
  accumulated cell counts against a target share and by raster adjacency between plates
  (`PlateStage.drawPlates`), and a plate near the target's edge, or two plates touching along a
  cell or two, can come out differently on a coarse grid: over seeds 1 to 60, the crusts differ
  between 256 rows and 512 or 1,024 on two seeds (seed 2's plate 10 is oceanic at 256 rows and
  continental above; seed 54's plates 0 and 1 swap), and 512 and 1,024 rows agree on every seed.
  The rift pairs differ on five, three of them a short rift 256 rows does not resolve (seeds 1, 22
  and 40). None of the five seeds L1 measured is affected, so it is not a cause of their lake
  spread, and it was left alone. What would settle it is the crusts chosen by area on the ground
  and adjacency by a length of shared boundary in kilometers. 2026-09-29, L1.
- **The rifts may be too long and too common, and the sill guard cannot see a rung at Earth's
  width.** Measured at 512 rows once the valleys were narrowed to Earth's 55 km: the standard seeds
  carry 53 to 242 km of continental rift axis per million km2 of land, 134 on the mean, against
  Earth's 85 (64 without the West Antarctic system); seed 1234 carries 6,640 km in two systems.
  Reported, not asserted: what sets how many continental pairs pull apart is the plates' drift and
  classification, not the rift's profile. Of the dry axis, 0 to 43% drains down the valley to the
  sea for 160 km or more, the rest to closed basins or across the flank; both are Earth's and
  neither is steered. `RiftSillGeometryTest` passes the relay ramps and would pass the rungs too at
  this width (a join square to a 4.7-cell valley is too short a line for the census), so it guards
  the valley and its shoulders and no longer the rungs; and the shoulders still switch at each join
  square to the rift over their 105 km, faintly on the plate floor. 2026-09-30, L1's review round.
- **At 256 rows a rift's anchor can sit two cells off.** `anchoredCourse` anchors a course at the
  first cell whose bisector coordinate reaches zero; on seed 59758's rifts 1-13 and 3-10 the 256-row
  course reads 54 and 41 km further along than the 1,024-row one at the same ground (the median over
  cells matched within a cell), where every rift at 512 rows is within 17 km. `RiftIdentityTest`'s
  share passes, leaving each stretch's end half-grabens out as its text allows. What would settle it
  is an anchor fitted over the course rather than read at one crossing. 2026-09-30, L1's review
  round.
- **Seed 5's ocean does not solve with the pressure departure off.** `OceanCirculation` stops at a
  relative residual of 0.0017 to 0.0019 after its 200 iterations against a tolerance of 0.001, at 256
  and 512 rows, with `climate.pressureWinds` false (the setting `MeridionalWindTest` builds its
  worlds with), and solves with it on; the tree before L1 fails the same way, so L1 did not cause it.
  Found by L1's monsoon scan, which skipped the seed. 2026-09-29, L1.
- **A world of square cells carries more ice at 60 to 90 degrees than a change of grid gives.**
  `GridShapeTest` compares the same seed at 512 by 512 and at `forRows(512)`, band by band. Its
  ice clause runs as a known failure. Seed 1234 at -75 to -90 degrees carries +0.017 of the band as
  ice. Seed 99 carries +0.022 at 75 to 60 degrees and +0.022 at -60 to -75. 512 by 512 against
  1,024 by 1,024 moves no band more than 0.016. The deserts, the land's rain and the warmth hold.
  Glaciation's cell-space operators reach twice as far north-south on square cells: the relief
  window, the discs and the square windows. They are the first suspects, and Q2 re-measures them.
  2026-09-28, Q1.

  **Q2 re-measured it** with the coast's reach in kilometers (234 km on square cells too, where it
  was 117): seed 99 at 75 to 60 degrees moved to +0.021, the other two figures held. The
  glaciation's cell-space operators were not isolated. 2026-09-28, Q2.
- **Square cells tip four clauses the 512 by 512 grid passed, three running as known failures, none
  of them isolated (Q2).** The ocean is still solved on its own grid of cells twice as wide as tall,
  which is the switch's next chunk, and is the first suspect for the first two.
  - `ColdWaterPlacementTest`: seed 42's coldest northern eastern-boundary water, -2.22 C, lies at
    7.9 degrees, in the equatorial tongue, where on the 512 by 512 grid it lay at 26.5.
  - `PressureWindTest`: the cold half blows onto the subtropical continents' equatorward and eastern
    coasts at +0.31 m/s pooled, where it blew off them at -0.17; seeds 42, 1234 and 99 onshore.
  - `SeaLevelHistoryTest`: the lowstand's pooled gain in estuary mouths is 1.47 against the 1.5 read
    off the 512 by 512 grid's worlds (1.60 there); every seed still gains.
  - `IceSheetTest`: no audited sheet fills a third of its dome's 500 km disc; the sheets near their
    domes cover about a third less ground than on the 512 by 512 grid. The flow clause reads seed 20
    instead, scanned for a dome that qualifies, so it is not blind; the audited seeds' shrinking
    domes are the finding left.
  2026-09-28, Q2.
- **The shared worlds drop a class's own variants before a plain world nobody will ask for again.**
  `WorldLender.admit` makes room by dropping the least recently lent variant first, then a plain
  world only one class has asked for. A class that borrows the four standard worlds and a variant
  of each, as `PressureWindTest` does in each of its tests, therefore drops its own variants to
  admit the next one while a plain world another class finished with stays, and on square cells,
  whose worlds hold 78 MB of arrays each against the 700 MB `SharedWorlds` keeps, it makes its four
  variants three times over: eight extra generations, about 160 s of the tier. Raising the
  retention is not the way: at 1.25 GB a worker ran out of its 3.5 GB heap. Preferring to drop what
  the class now borrowing has not asked for is the candidate; `SharedWorldsGuardTest` holds the
  order as it is. 2026-09-28, Q2.
  - At Q2b `PressureWindTest` no longer does it: its variants are 20 MB at 256 rows and its
    monsoon's four at 512 are each made once. What is left is the 1,024-row worlds, 312 MB each,
    pushing the standard worlds out as they pass through: fifteen worlds made again in the worker
    that had made them, 525 s of the tier. Six were in the worker that drew `GlaciationTest`,
    where the standard seed 99 at 512 rows was made four times, and four were 1,024-row worlds
    one class had made and the next to ask found gone: seeds 42 and 7 from `GlaciationTest` for
    `ScaleFreeTest`, 7 from `GlacialBasinShapeTest` for `RibbonLandTest`, and 718106 from
    `LakeWaterBalanceTest` for `RealmIdRangeTest`. 2026-09-28, Q2b.
- **The per-merge tier is as long as the worker that draws `GlaciationTest`.** Gradle deals the
  classes to its four workers without looking at what they cost. At Q2b the worker that drew
  `GlaciationTest` ran from the tier's first minute to its last, the 52.9th, 43.0 minutes of it
  in the classes whose shared worlds name it, while another had finished every such class of its
  own by the 14th. `GlaciationTest` alone is 21.6 minutes, fourteen worlds of 1,024 rows, and
  `ScaleFreeTest` (6 minutes) and `DeltaMouthTest` (5) drew the same worker. The classes add up to
  138 minutes, 34.5 a worker. Done at Q2b: `GlaciationTest`'s three cases are three classes
  (`GlaciationTest`, `GlaciationLatticeTest`, `GlaciationCombTest`, 9.4, 4.9 and 8.6 minutes run
  side by side), and `IncrementalReuseTest`'s equality and identity cases run at 128 rows (the
  class 4.8 minutes from 7.9). Not measured on a whole tier yet. Left: a store the workers
  share, so that the 1,024-row worlds, 21 distinct worlds made 30 times, are made once a tier.
  2026-09-28, Q2b.


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
  the scale it means to measure. 2026-09-13. Since Fix 2 its boxes are square on the ground, four,
  eight and sixteen cell widths across and twice as many rows down, and `CoastRoughness`'s with
  them: on the redrawn worlds the pooled figure reads 1.128 with them and 1.063 with boxes square in
  cells. The saturation this entry names is unchanged. 2026-09-24, Fix 2.
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
  units. 2026-09-13, S1. K1 restated both as the shares their derivations name,
  `SeaConfig.enclosedSeaMaxShareOfSurface` and `GlaciationConfig.maxLakeShareOfSurface`, at the
  same values on the 12,000 km world, so on a world of Earth's size they are the Caspian and
  Superior themselves; the question stands for every world smaller than Earth. 2026-10-05, K1.
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
- ~~**A drowned valley's catchment is measured with square cells on a 2:1 world.**~~ Closed on every grid the application makes by the switch to square cells, 2026-09-29, Q6: since Q5 every size is a grid twice as many cells across as down, so a row and a column are the same length on the ground and a cell's width squared is its area, so the catchment's square kilometers are right and the bar
  still asks for about 39 cells of it; how the coast answers the calibration on the ground, which
  the entry asked for, is the drowned valleys' own chunk's if it is ever wanted. As it stood:
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
  so it was already not what a moved slider drew. Since chunk 6 the runoff it sums and the discharge
  it compares with are both `Runoff.annualWeightMm`, in millimetres, so the two are at least in one
  unit. 2026-09-12, restated 2026-09-21 at R1 and 2026-09-25 at chunk 6.
- **`ClimateStage` still says the river and realm stages read its 0..1 rainfall.** The comment above
  the normalised copy in `ClimateStage.generate` lists `RiverStage`'s and `NationStage`'s runoff
  weighting among the copy's consumers; since chunk 6 both read `precipitationMm` through
  `Runoff.annualWeightMm`. Left for the chunk that corrects that stage's divisor KDoc, since chunk 6
  was not to edit the climate stage. 2026-09-25.
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
  Built as `ClimateConfig.marineInversion` by W3 and recorded as not delivered; with 4a's solved
  gyres the cold water is on the west coasts but mostly poleward of 35 degrees, and the inversion
  and the upwelling that makes Earth's cold coasts cold are chunk 4b's. 2026-09-26.
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
  contour of a plane is a straight line. Every other belt's stamp varies along its own length,
  though the collision belts the map draws do not (the next entry).
  Giving the trench the same swell was written, run and reverted: at `rangeVariationCycles`'s
  wavelength (`rangeVariationWavelengthKm` since K1, 923 km; about 160 cells at 2048, against a bench 30 cells long) it slides the coast onto a
  different straight contour instead of bending it, and the window went from 108 cells of thin
  grid-bearing water and a 31-cell run to 146 and 39. Wants a shorter wavelength on the trench, or
  dissection of a coastal plain too flat for the hydraulic rounds to cut. E4/B2's geometry.
  2026-09-12, measured at E7.
- **A collision belt is drawn as a ruled wall, because the uplift rate is a clamp of its stamp (rule
  13).** Seen on the review renders of 969495 at 2048: a belt running north-east with straight,
  parallel sides and a smooth flank of constant width, the same before and after the implicit
  incision. The stamp is not what the map draws. `PlateStage.recordUpliftRate` gives a cell its
  class's full rate wherever the stamp stands above `crustAgeReference` (650 m). That takes in 86 to
  89% of 969495's collision cells with a rate, and 54 to 92% on seeds 7, 42 and 718106. The erosion
  then lifts that footprint by 248 m a round for twelve rounds, so the finished belt is the rate's
  mesa. It is a contour of a stamp that is a function of distance from a warped bisector, times
  width noises 706 and 1,714 km long. The rate drops the pair's convergence (the belt reported
  converges at 0.25 and rises as fast as its 0.73 neighbour), the swell of `rangeVariationWavelengthKm`
  and the roughness. Measured on that belt at the renders' configuration, over 780 km: width 254 km
  with a coefficient of variation of 0.055; edges within 5 to 6 km rms of a straight line; a
  boundary within 12 km of its chord for 974 km; and a flank 37 km wide, varying by 4 to 6 km.
  Earth's most regular front, the Himalaya, is a small circle of radius 1,696 km (Bendick and Bilham
  2001), and stays within 12 km of a chord for only 403 km. On the app's configuration, 10 of the 14
  belts on land on 969495 at 2048 and seeds 7, 42 and 718106 at 1024 have an edge straighter than
  that. The geometry guard misses it: its runs break at 1.1 cell widths, and dissection breaks the
  contour long before the belt bends. The repair, in order:
  1. the rate in proportion to the stamp at a reference convergence, with `IsostasyTest`'s
     derivation re-stated;
  2. convergent runs segmented along strike on `arcAlongRun`, as rifts are, with each segment's
     width, height and front offset drawn from the detachment-strength and shortening variation that
     makes Earth's salients and recesses (Macedo and Marshak 1999; Jordan et al. 1983; Isacks 1988),
     and crests stepped en echelon where the pair is oblique;
  3. convergence projected on the local boundary normal;
  4. a straightness clause at hundreds of kilometres, shown failing on this belt.

  The graphics-card path is specified with the rate. Note: the review render set was built with
  `WorldGenConfig(seed, size, size)` outright rather than through `atResolution`, so its belts are a
  quarter of the app's width at 2048 and its boundaries straighter still. 2026-09-26, found on the
  Fix 3b review renders.
- **A drowned basin opens to the sea through a strait one cell wide and hundreds of kilometers
  long.** On seed 1 at 2048, in the site's opening band (sheet window 3264, 848, 1600 by 800, which
  crosses the east-west seam), a channel of sea one cell wide runs from an inland arm of the sea
  west along the southern foot of the east-west range to the coast: roughly 670 pixels of the
  4096-pixel sheet, about 2,000 km at 2.9 km a pixel, measured by eye from the band, around rows
  1,340 to 1,420. `SeaLevelStage.drainDrownedBasins` makes it: a basin whose floor stands below the
  waterline has its outlet notch cut down to the waterline, so the basin joins the ocean at the next
  labeling and the map shows "an arm of the sea with a narrow mouth". The notch is as wide as the
  river's one-cell course, so the mouth is one cell wide for the whole length of the river that cut
  it. It runs where it does because the collision belt's foot is a straight valley the length of
  the belt (the entry above). Water colors (2026-09-27) stopped the coast being inked shut over
  it, so it now draws as a thin line of water instead of a black one, but the feature itself has no
  Earth analogue: drowned valleys (rias, fjords, the Chesapeake's arms) are the valley's own width
  flooded, and sea straits such as the Bosporus are short. A sill taken below the waterline should
  let the sea into the valley floor across its width below that level, and a basin whose outlet
  cannot be taken below the waterline should stay a lake, or a dry depression, as the Caspian, the
  Dead Sea and the Qattara do. Wants measuring (the channel's length, width and count across the
  standard worlds) and a rule for how wide a drowned outlet opens. The ruled belts' repair may
  remove this one; others may remain. 2026-09-27, seen by the maintainer on the live site.
- **The ocean's heat carries water colder than sea water can be.** On 969495 at 2048, a shelf
  8 m deep at 46.8 N, open all year, whose latitude's annual water is 10.1 C, holds water at
  -3.1 C (an anomaly of -12.3 C; -3.45 and -13.1 with upwelling off), carried there by 4a's
  currents; the map's coldest anomaly, -13.6 C at 43.3 N with upwelling off, is the same patch. The
  heat solve (`OceanHeat`) relaxes toward the energy balance's annual `water`, which under sea ice is
  the ice's own surface (the frozen band's two meters of ice), far below the -1.8 C at which sea
  water freezes (`EnergyBalance.SEA_FREEZING_C`), and it advects that value as if it were liquid
  water. Beneath ice the liquid layer sits at its freezing point, and that is what a current can
  carry away from the ice. The upwelling's risen water already respects the bound (4b-1); the
  solve's own target and the water it transports do not. Wants the liquid water's temperature
  separated from the frozen surface in the ocean's target, with the ice surface kept for the
  climate's frozen marks, and a guard that no open-water cell's solved water falls below the
  freezing point. 2026-09-28, found on the 4b-1 merge's 2048 audit.
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
