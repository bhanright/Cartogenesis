# Geography audit

What fantasy maps are commonly caught getting wrong, and what this generator actually does about
each. Measured with `GeographyAuditTest` on four seeds at 512×512, not asserted.

Once saves carry the world (Track D), cross-platform generation bit-identity stops being a requirement and this document's realism gaps become the focus.

Sources for the rules: [Inkwell Ideas, "Top 10 Mistakes of Fantasy Map
Making"](https://inkwellideas.com/2026/05/top-10-mistakes-of-fantasy-map-making/), [Map Effects,
"River Sins"](https://www.mapeffects.co/tutorials/river-sins), [Mythcreants, "How to Color Your Map
Using SCIENCE!"](https://mythcreants.com/blog/how-to-color-your-map-using-science/), and
[Worldbuilding Pasta on biomes and climate
zones](https://worldbuildingpasta.blogspot.com/2020/05/an-apple-pie-from-scratch-part-vib.html).

## Held by construction

**Rivers never split.** Measured 0 splits across all seeds. D8 routing gives every cell exactly one
downstream neighbour, so the drawn network is a forest — a river physically cannot fork. This is the
single most common river sin and the pipeline cannot commit it.

**Rivers never run coast to coast.** Measured 0 rivers rising on the shoreline. Sources are
headwaters — channel cells with no upstream channel — which by definition sit inland.

**Rivers always reach the sea.** Every river terminates at water or merges into one that does;
`RiverEndingsTest` follows the whole drainage and finds no breaks. Depression filling guarantees
every land cell has a downhill path out.

**Mountains come from plate tectonics, in ranges.** Uplift is applied along classified plate
boundaries — convergent belts, subduction trenches, divergent ridges — rather than scattered. Belts
now also vary along their length (`rangeVariation`), because a uniform ridge for a boundary's whole
run is what makes plate edges read as drawn on.

**Rain shadow is real, not decorative.** Rainfall is produced by marching moist air along prevailing
winds and wringing it out on windward slopes, so leeward dryness emerges from the simulation. Wind
bands follow Earth: trades easterly below 30°, westerlies 30–60°, polar easterlies above — and those
boundaries migrate with the season, so a coast can sit in one belt in summer and another in winter.

**Deserts sit near the horse latitudes.** Desert mean latitude 31–33° against a land mean of 41–53°,
so deserts are pulled strongly equatorward of average land, toward the 30° band.

## Known deviations

**Some river segments still run uphill on the raw surface.** Routing uses depression-filled elevation, but where a river crosses filled basins it is strictly flowing across ground that does not slope downhill on the original surface. Last measured 2026-08-23 at 12–14% of drawn segments, down from 13–20% before lakes were introduced. What remains is shallow filled ground below `LakesConfig.minDepth` — flats raised by a hair rather than basins deep enough to hold water.

**No continentality.** A continental interior swings no more between seasons than a coast at the same latitude. The seasonal departure is damped over water and applied at full strength over every land cell alike, so a shore and an interior at the same latitude swing equally. Addressed in [A2 Continentality](REALISM_PLAN.md#a2-continentality--sonnet).

**Winds are purely zonal.** Wind direction varies only with latitude (±1 per row) and has no meridional component. This prevents monsoons, where seasonal motion of the ITCZ pulls ocean air onto tropical landmasses. Addressed in [A3 Meridional wind and the monsoon](REALISM_PLAN.md#a3-meridional-wind-and-the-monsoon--opus).

**Rainfall normalizes per world.** Every world rescales so its 88th land percentile sits at 1.0, which means an arid world and a lush one classify identically and every world gets roughly 4.6% desert regardless of its actual moisture. This prevents worlds from differing in their biome distribution. Addressed in [A4 Absolute rainfall](REALISM_PLAN.md#a4-absolute-rainfall--sonnet).

**High-latitude west coasts classify as taiga/tundra despite abundant rainfall.** Measured on seeds 7, 42, 1234 at 512×512: zero cells on 50–60° west-facing coasts class as temperate forest despite 3.83–3.71–1.88× the latitudinal mean precipitation (seeds 42/7/1234). The cold cap is not the cause — rainfall is abundant (coast precip 0.91–0.99 normalized). The cause is `classify`, which gates temperate/taiga/tundra on annual-mean temperature (< 7 °C → taiga), and the latitude curve places 55° near 0 °C, so warm-current anomalies (+1.7–2.0°C) still fall below 7 °C. Bergen is temperate by Köppen definition (coldest month > −3 °C, warmest > 10 °C), not by annual mean. Addressed in [A6 Temperate climates by coldest month](REALISM_PLAN.md#a6-temperate-climates-by-coldest-month--sonnet), which classifies Köppen-style on the seasonal fields A1 added and checks whether the latitude curve runs too cold at 45–60°.

**No deposition.** The hydraulic erosion stage removes material and never returns it. No deltas build at river mouths, no floodplains or alluvial fans form along lower channels, and no mass is laid down as rivers flatten. Addressed in [B3 Deposition](REALISM_PLAN.md#b3-deposition--opus).

**No continental shelves.** Sea level is a percentile cut through a single height field, so the sea floor drops straight off the coast. There are no shallow waters along continental margins. Addressed in [B1 Continental shelves](REALISM_PLAN.md#b1-continental-shelves--sonnet).

**Convergent boundaries do not distinguish crust pairs.** All convergent boundaries use one profile regardless of whether the collision is oceanic–continental (Andes), continental–continental (Tibet), or oceanic–oceanic (arcs). Addressed in [B2 Crust-pair boundary types](REALISM_PLAN.md#b2-crust-pair-boundary-types--opus).

**No glaciation.** Ice sheets and alpine glaciers are not carved where mean annual temperature falls below freezing. No U-shaped valleys, cirques, or fjords carved by ice exist. Addressed in [B4 Glaciation](REALISM_PLAN.md#b4-glaciation--opus).

## Fixed by this audit

**Lakes.** A basin the priority-flood had to raise is now recognised as standing water: 25–47 lakes
per world, the largest a few hundred cells. The lake surface sits at the basin's spill level, rivers
run into it, and one river leaves at the outlet. River segments *inside* a lake are no longer drawn,
since the river there is the lake — and those were precisely the segments that appeared to flow
uphill. Depth is shaded from how far the water surface stands above the ground beneath it.

**Capital siting.** Every capital was coastal — 12 of 12 on every seed, which no real map shows. The
harbour bonus was large enough to outweigh everything else, and the best river cell is always the
mouth. Siting now weighs fresh water above a harbour, adds the surrounding hinterland's carrying
capacity and defensibility (height above the local average), and prefers the **head of navigation**
over the river mouth — inland enough to be defensible and out of the floodplain, still reachable by
water, which is why London, Paris and Rome are where they are.

After: 6–8 of 12 coastal, 6–9 of 12 on a river. A mix rather than a rule.

## Where the deserts are

Deserts belong to the horse latitudes, near 30 degrees, where air that rose at the equator descends
dry. Getting them there took two mechanisms rather than a tuned constant.

- **The circulation belt scales the rain rate, not the finished rainfall.** As a multiplier applied
  afterwards it could not make a rain shadow wet again — twice nearly nothing is still nearly
  nothing — so a range at the equator produced desert on the wettest row of the map. Applied to the
  rate during the march it suppresses rain where air descends and encourages it where air rises,
  which is what those belts actually do.
- **Land gives moisture back.** Forests and soil return water to the air, and the tropics recycle a
  large share of their own rainfall. Without that, orographic depletion is permanent and a
  continent stays parched from its first mountain to its far coast. The recovery is scaled by the
  same belt, because descending subtropical air suppresses the convection that would return the
  moisture — remove that scaling and every latitude re-moistens alike, at which point deserts stop
  preferring the subtropics at all. Measured: placement falls from 90% to 34%.

Verified by `GeographyAuditTest`, which asserts at least 85% of desert falls between 15 and 45
degrees; all four audited seeds manage 98-100%. Desert covers about 4.6% of land without seasons
and about 2% with them — see "The year has two halves" below for why, and for the third mechanism
seasons made necessary.
`DesertCauseTest` is the diagnostic that found the cause, attributing each desert cell to its belt,
its upwind climb, and how far its air travelled over land.

## The year has two halves

Temperature and rainfall are computed twice, for the local warm season and the local cold one, and
biomes are read off all four numbers instead of two. The whole mechanism is one setting —
`ClimateConfig.seasonalTilt`, the degrees the thermal equator migrates toward whichever hemisphere
is in summer — and everything that reads a latitude reads the shifted one: the temperature curve,
the wind belts, and the rain belts alike.

- **"Summer" is local, not July.** Northern July and southern January are both stored as the warm
  season, so one classification rule serves both hemispheres and a dry-summer coast reads the same
  either side of the equator.
- **The annual fields are unchanged.** `temperature` is still the annual mean and `precipitation`
  is the mean of the two marches, so every stage downstream — rivers, realms, peoples, landmarks —
  sees exactly what it saw before. With `seasons = false` the seasonal fields collapse onto the
  annual ones bit for bit and the generator reproduces the pre-seasons world exactly.
- **The sea barely swings.** Water's heat capacity is why a maritime climate has a small annual
  range, so the seasonal departure is damped to a fifth over open water. Measured on seed 42 at
  35°: land swings 13.1 °C through the year, the open sea 2.9 °C.
- **Two new classes come out of the seasonality rather than the total.** A Mediterranean coast is
  dry in the warm half of the year and wet in the cold one, which happens where the subtropical
  high sits over a west-facing coast all summer and the westerlies swing back over it in winter; a
  monsoon forest is the opposite, a drenching wet season and a dry winter. Savanna is now decided
  the same way, on the lopsidedness of the year rather than on the annual total alone.
- **The desert belt migrates, and the instantaneous belts are sharper than their mean.** The
  subtropical high rides the thermal equator, so the dry belt sits some ten degrees poleward in
  summer and the same distance equatorward in winter. Averaging two offset marches, though, is a
  second computation of the annual mean, and it flattened a profile whose constants were already
  annual means: measured, the anomaly at the high's centre fell from −1.12 to −0.51, seed 42 lost
  three quarters of its desert, placement fell from 100% to 74%, and rain-shadow deserts came back
  at the equator. Each season's belt anomaly is therefore scaled by the factor that restores the
  annual mean at the high's own centre — derived from the tilt, and exactly 1 when the tilt is
  zero. With that, placement is 100/99/100/98% across the four audited seeds, against 100/100/100/98%
  before. Desert *area* is still lower than it was — seed 42 at 512 falls from 5.1% of land to
  1.9% — because with the belt moving, fewer latitudes are dry in both halves of the year. That is
  the right sign for the mechanism and a number for [A4](REALISM_PLAN.md#a4-absolute-rainfall--sonnet)
  to revisit when rainfall stops being normalised per world.

Verified by `SeasonsTest`, which asserts the land/sea swing above and that a Mediterranean band of
at least 40 cells sits on a west-facing coast between 30° and 45° on at least two of three seeds
(measured: 211, 517 and 494 cells on seeds 7, 42 and 1234). The same measurement with
`seasons = false` returns zero on every seed, which is what gives the guard its meaning.

## Coasts and the sea beside them

Two coasts at the same latitude are not the same coast, and the usual fantasy-map mistake is to
treat them as if they were. What separates them is the current offshore.

- **A warm current makes a mild coast.** Bergen sits at 60°N and its harbour does not freeze,
  because the water arriving there came from the tropics. Cartogenesis gets this by advecting sea
  temperature along the solved currents and letting the coast take on the anomaly, so the same
  latitude can be temperate on one shore and subarctic on another.
- **A cold current makes a dry one.** Cold water offshore means little evaporation and a stable
  air column, which is why the Namib and the Atacama are deserts on the sea. This falls out of the
  same mechanism, since evaporation is charged against the local sea temperature.
- **Fisheries sit on cold upwelling over a shelf**, not on the warmest water. The Grand Banks and
  the Humboldt support far more people than their hinterlands could, so a cold shallow shelf raises
  coastal habitability even as the harbour term lowers it.

Verified by `OceanCurrentTest`: poleward flow arrives warm on 85% of samples, the anomaly reaches
±7°C, and warm coasts out-score cold ones at matched latitude on every seed tested. With the
coastal term removed the last of those falls to zero and tips negative.

## Mountain belts

An orogen is hundreds of kilometres across, with its high ground spread over a wide axis and
foothills grading into the forelands. It is not a wall along the suture, and the difference shows
most where a belt crosses submerged ground: a knife-edge crest clears sea level as a ruler-straight
strip of land with a strait either side, which is the single most recognisable tell of a generated
map.

So a convergent belt is built from a flat-crested profile rather than one that peaks on the
boundary line, its width swells and pinches along its length, and its height sags near to nothing
between massifs so a long belt reads as a chain rather than a wall.

The belts are then eroded, which is what gives them flanks. Thermal erosion cannot remove a strip
where a belt crosses shallow sea — land above water stays above water — but by moving material off
the crest and onto the flanks it widens the footprint until the strip stops reading as one. On the
seed this was diagnosed from, it halves the land sitting in strips, from 0.4% to 0.2%.

Water carves the rest. Thermal erosion answers a question about rock — how steeply it can stand —
and gives mountains their flanks; it cannot make a valley, because a valley is cut by something
that flows. Stream-power incision does that: a cell lowers in proportion to the square root of the
area draining through it times the slope it sits on, so a channel that cuts down gathers more water
and cuts deeper still, and the divides between channels sharpen as the channels fall away. That
feedback is where dendritic drainage and ridge lines come from, and no amount of smoothing produces
them.

Two details matter more than they look. Incision is interleaved with the thermal sweeps rather than
run after them, because on its own it cuts a slot one cell wide — and a one-cell slot is twice as
steep on a grid twice as fine, so the world stops being the same world at different resolutions.
Letting the walls fail between rounds caps them at the critical slope, which is a property of the
map. And nothing cuts below sea level, because that is the base level every river grades to; without
that limit the cells nearest the shore incise hardest, having a whole catchment behind them and open
water in front, and the coastline shreds into drowned valleys.

Verified by `ValleyIncisionTest`, which measures how far the banks stand above the channel across
every drawn river: twice as high as without water.

## Not modelled, and probably shouldn't be

Settlements below the capital, trade routes, and roads. The atlas invents exports and imports from
what a realm's land can produce, but there is no network of towns for them to move between.
