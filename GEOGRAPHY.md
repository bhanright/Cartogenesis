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

**A collision builds what its crusts allow.** Convergence is classified by the pair of crusts as
well as by the relative motion, so an ocean going under a continent builds a narrow coastal range
with a volcanic arc behind it and a trench in front, two continents meeting build a broad
flat-topped plateau ringed by mountains, and two oceans meeting build a chain of volcanic islands.
Divergence under continental crust builds a rift, and the rift is segmented into half-grabens of
alternating polarity, so a drowned one is a string of gulfs and lakes rather than a canal. See
"Three kinds of collision" below.

**Rain shadow is real, not decorative.** Rainfall is produced by marching moist air along prevailing
winds and wringing it out on windward slopes, so leeward dryness emerges from the simulation. Wind
bands follow Earth: trades easterly below 30°, westerlies 30–60°, polar easterlies above — and those
boundaries migrate with the season, so a coast can sit in one belt in summer and another in winter.

**Deserts sit near the horse latitudes.** Desert mean latitude 31–33° against a land mean of 41–53°,
so deserts are pulled strongly equatorward of average land, toward the 30° band.

**An interior swings more than a coast.** The seasonal departure is scaled by distance from
water — a jump-flooded Euclidean distance transform from every sea and lake cell, saturating at
three times the coastal reach — so a shore and an interior at the same latitude no longer swing
alike. On seed 42 at 50° the interior-versus-coast gap in the seasonal swing is 0.6 °C with
`continentality` at zero and 8.5 °C at the default 0.6 (measured again at G4; A2 first recorded
0.2 and 7.5, and the chunks between have moved the coastline under it), and the annual mean is
bit-identical either way, because the scaling applies to the departure and never to the mean.

**Continents stand on shelves.** After the sea-level cut, the sea floor within `shelfWidth` of a
coast (twenty cells at 512, scaled with resolution) is remapped onto a shallow platform at
`shelfDepth` of the depth range, falling away to the abyss beyond. The remap touches only water,
so no coastline moves: `ContinentalShelfTest` finds 100% of near-coast sea shallow against 60% with
the remap off, 0–2.5% of far sea shallow, and zero land cells changed on any seed. Island arcs
inherit the same platform, which is what makes an archipelago read as one drowned ridge.

**Rivers put back what they take.** The hydraulic pass carries a sediment load down the same flow
network it cuts with, and lays the surplus down wherever the gradient can no longer hold it:
floodplains along lower trunks, alluvial fans at range fronts, fans at lake inflows, and deltas
where the biggest rivers meet the sea. The budget is exact rather than approximate — `DepositionTest`
measures material incised against material deposited plus material carried out to sea, over all
twelve rounds on seed 42, and finds them equal to the last float. No cell is ever raised as high as
the ground draining into it, so deposition cannot invent an uphill river.

**A lake is sized by its outlet, not by its basin.** Depression filling gives the router an outlet
for every cell, and the routing then runs over the filled surface — which left the lip of a basin as
the one piece of ground on the map the water never touched, so a tectonic hollow stayed a lake the
size of the hollow for the whole life of the world. Real basins are drained by their outlets: Lake
Bonneville emptied through Red Rock Pass and what survived is Great Salt Lake, and Agassiz drained
through one outlet after another as each in turn cut down. Every hydraulic round now finds each
filled basin's rim, works out what the outflow can take off it — stream power, the same expression
and the same coefficient as the ordinary incision, with the basin's whole catchment as the discharge
and the outlet channel's own slope — and *breaches* the sill: the lip and the ground below it are cut
to a surface falling away from the new lake level, as far as the first cell that already lies lower.
Cutting the rim cell alone does nothing, because the next fill finds the same rim; the length of the
sill is why a lake on a plateau lasts and one behind a ridge does not. Measured on the author's own
world, seed 718106 at 512: the fill over the largest basin's floor falls from 0.239 of the land's
relief to 0.010 across the twelve rounds, where with `outletIncision` off it ends at 0.240, exactly
where it started. What the map keeps is bounded by a figure with a meaning — no world has a lake
larger than the Caspian's 0.073% share of its surface, where two seeds in four did before.

**Cold country is lake country.** Where the provisional mean annual temperature — latitude and
altitude, from the same curve `ClimateStage` later uses — falls to freezing, ice takes over the
valleys the water cut: a flat-floored U-shaped trough across the flow instead of a V, a cirque
bitten out of every head, a staircase of over-deepened basins whose spacing is set by descent
rather than distance, a recessional moraine barring the valley at the lower end of each reach and a
terminal moraine at the snout. The signature is the standing water that leaves behind, because a
river network cannot leave a hollow in its own bed and ice does nothing else: on seed 42
`GlaciationTest` measures 52 lakes per 42,000 cells of ice, tundra and taiga against 3 per 30,000
cells of temperate country — 12.5 times the density, against 0.0 times with the ice switched off.

**A dry basin is not a full one.** Depression filling raises every closed basin to its spill level,
which is the right answer to a routing question and the wrong answer to a hydrological one: the
Caspian, the Aral, Chad, Eyre and the Great Salt Lake all sit far below the rims of basins many
times their own size, because a lake with no outlet loses water only by evaporating and settles
where its catchment's inflow matches evaporation off its surface. `RiverStage` now solves that
balance for every basin before it calls one a lake. Inflow is a runoff fraction (0.35 by default —
Earth's rivers deliver about a third of the rain that falls on land) of `precipitationMm` summed
over the catchment, which is the flow accumulation at the basin's pour point; the loss is
Thornthwaite (1948) potential evaporation read off the warm- and cold-season temperature fields,
which puts a hot desert at 2270 mm a year and cool temperate country at 554 mm with none of its
published constants touched. The area at a given level is the basin's own hypsometry, so the answer
is found by bisecting over the basin's cells sorted by the ground beneath them. A basin whose
balance reaches the brim overflows exactly as before — wet country is untouched, cell for cell —
and one that cannot is endorheic: its water is re-routed inward to the lake it can sustain, no
river leaves it, and the rivers that used to be drawn below its rim are gone because that water
never left. Where even the first cell of water cannot be held there is no lake at all, only a
playa, recorded per cell for a later chunk to draw as salt flats. Measured on the largest dry basin
in seeds 1-120 (seed 43, 1775 cells at 172 mm of rain against 577 mm of evaporation): 18% of its
spill-level area holds water at balance, against 100% with the balance switched off. Across the
author's world at 1024 the lake count falls 82 to 67 and the lake share of land 1.15% to 0.91%,
with ten endorheic basins and 132 playa cells.

## Known deviations

**The shoreline does not know whether the sea can reach it.** Sea level is a percentile and nothing
else, so every hollow the erosion leaves below it is drawn as ocean whether or not a drop of ocean
could get there, and a D8 river ends at the first one it meets. On seed 59758 at 2048 that is 475
separate bodies of water outside the ocean, and a third of every seed's river mouths end in one —
measured with deposition switched off entirely, so it is not the deltas' doing. Cutting an inlet
from each such pocket to the sea was tried in the hydraulic pass and reverted: it works on the
numbers (that seed's stranded mouths fell from 40 to 15 at 512) and it is the wrong place for it,
because a small body of water the sea cannot reach is sometimes a landform rather than an artefact —
the gulfs of a flooded rift are exactly such bodies, and joining them to the ocean turns the chain
back into the channel `RiftSegmentationTest` exists to break up. The cure is connectedness in the
sea-level cut itself: water the ocean cannot reach is land, or a lake, and the cut is the only place
that can tell which without guessing.

**Every basin's outlet erodes, including the ones that would never overflow.** Outlet incision is
driven by the outflow over a lip, and a basin in dry country has no outflow: Lake Eyre does not cut
down through its rim, which is why it is still there. The hydraulic pass cannot tell the difference,
because it runs before there is a climate and works to uniform rain — the same circle that makes
erosion's rainfall flat in the first place. So a desert basin is drained on the same terms as a wet
one, and the standing water a dry basin keeps is decided afterwards, by the water balance, out of
whatever rim survived. Breaking that would mean either a rainfall field before the terrain is
shaped, or a second erosion pass after the climate.

**Some river segments still run uphill on the raw surface.** Routing uses depression-filled elevation, but where a river crosses filled basins it is strictly flowing across ground that does not slope downhill on the original surface. Last measured 2026-08-23 at 12–14% of drawn segments, down from 13–20% before lakes were introduced. What remains is shallow filled ground below `LakesConfig.minDepth` — flats raised by a hair rather than basins deep enough to hold water.

**The monsoon lands on the wrong coast.** The wind slants across the latitude lines — see "Which way the wind blows" below — and the trades do reverse over the year in the deep tropics. But the thermal equator migrates only `seasonalTilt` degrees, ten, which is the zonal-mean figure rather than the twenty-five or thirty a heated continent manages, so the summer ITCZ sits at ten degrees and most tropical land is poleward of it. The onshore summer flow therefore arrives on coasts whose sea lies *poleward*, not on the equatorward-facing coast the Indian monsoon belongs to. [A4 Absolute rainfall](REALISM_PLAN.md#a4-absolute-rainfall--sonnet) closed the other half of this note — rainfall was normalized and clamped at 1, and tropical coasts sat against that clamp in the warm season (measured at 0.94-1.00 across five seeds), so the wet half of a monsoon year had no room left to get wetter. `precipitationMm` has no such clamp, and re-measured on A3's own seed (26) with the plan's original claim — summer beating winter 3x over a contiguous region of at least 2% of land — the region now covers 4.07% of land, up from 2.93% under the clamp: the claim holds. Letting the thermal equator run further over land than over sea, which would put the monsoon on the correct coast, is not yet planned.

**The sea never drowns a glacial trough.** A fjord is a trough the sea has flooded, and flooding one means re-cutting the sea-level percentile, which moves every other coastline on the map. `GlaciationStage` therefore grades its marine troughs down to the waterline and carves the over-deepened basin on the sea floor beyond the mouth, leaving the shelf as a sill — fjord bathymetry without a fjord's coastline. The high-latitude coasts gain depth and islands, not the long narrow inlets of Norway.

## Fixed by this audit

**Rainfall no longer normalizes per world.** Every world used to rescale so its 88th land percentile sat at 1.0, which meant an arid world and a lush one classified identically and every world got roughly the same desert share regardless of its actual moisture. Fixed by [A4 Absolute rainfall](REALISM_PLAN.md#a4-absolute-rainfall--sonnet): `classify` now reads `precipitationMm`, millimetres calibrated from the march's own physics (seed 42's windward coast lands at 3000mm, its desert core at 142mm) rather than rescaled per world, so a genuinely arider seed produces genuinely more desert — measured, desert share now ranges 0.99-6.14% across seeds 7/42/1234/99, a 6.2x driest-to-wettest spread where the old normalization produced near-identical shares by construction. The 0..1 field every earlier consumer expects (`CultureStage`'s climate distance, `RiverStage`/`NationStage` runoff weighting, the rainfall map view) is kept as `precipitationMm` divided by a fixed reference and clamped, so nothing downstream needed to change, only what it is calibrated against.

**Lakes.** A basin the priority-flood had to raise is now recognised as standing water: 25–47 lakes
per world, the largest a few hundred cells. The lake surface sits at the basin's spill level, rivers
run into it, and one river leaves at the outlet. River segments *inside* a lake are no longer drawn,
since the river there is the lake — and those were precisely the segments that appeared to flow
uphill. Depth is shaded from how far the water surface stands above the ground beneath it. (The
spill level is now only where a lake sits when it overflows; see "A dry basin is not a full one"
above for the basins that stand below their rims.)

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
degrees; all four audited seeds manage 100%. Desert covers about 4.6% of land without seasons
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
(measured: 222, 537 and 504 cells on seeds 7, 42 and 1234). The same measurement with
`seasons = false` returns zero on every seed, which is what gives the guard its meaning.

## Which way the wind blows

The three-cell circulation is not a set of stripes running due east and west. Each cell has air
rising at one edge and sinking at the other, and the surface leg runs between them: the trades
spiral in toward the thermal equator, the westerlies carry poleward toward the polar front, the
polar easterlies run back down. `ClimateConfig.meridionalWind` is how far that leg carries the air
across the latitude lines per cell of zonal travel — 0.3 rows, so the air crosses a row every three
or four cells.

- **Direction is measured from the *thermal* equator, not the geographic one.** In summer the
  thermal equator migrates `seasonalTilt` degrees into the hemisphere, and a tropical row it has
  crossed finds its trades reversed: blowing away from the equator rather than toward it. That
  reversal between the halves of the year is the monsoon wind, and a belt model that reads its
  direction off `|latitude|` cannot have it.
- **The rain march became an advection.** It used to be one air mass per row scanning along X with
  moisture that never left the row. Now each cell takes its moisture from the point one cell
  upwind, `(x - dx, y - dy)`, as a bilinear blend of the two cells in the column behind it — so a
  whole column depends only on the column behind it, and the march is a wavefront walked in lock
  step across rows. Rows are grouped into *runs* of the same zonal direction, which are the
  circulation belts; air is not carried across a belt edge, because at 30 degrees the two cells'
  surface legs diverge.
- **A ridge running east-west used to be invisible to the rain.** Stepping along X, the only climb
  the march could see was a climb along X. Measured as a paired difference — the same world
  generated with the slant on and off — land climbing along the meridional leg gains 0.031, 0.030
  and 0.013 of normalised rainfall on seeds 7, 42 and 1234 while land descending along it gains
  0.020, 0.010 and 0.006. `MeridionalWindTest` asserts the sign, which is exactly zero with the
  slant off.
- **At `meridionalWind = 0` the march is the old zonal scan, bit for bit.** Pinned by checksum
  against the build before the change.

The deserts survived it: `GeographyAuditTest` reads 100% in band on all four seeds, up from
100/99/100/98.

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

## Temperate coasts by coldest month

A5 measured that despite the current above, every one of seeds 7, 42 and 1234's 50–60° west-facing
coasts classed taiga or tundra rather than temperate forest, though they carried 1.9–3.8× their
latitude's mean rainfall. The rain was never the problem. `classify` gated temperate against taiga
on *annual-mean* temperature (`t < 7 → taiga`), and the latitude curve put 55° within a couple of
degrees of freezing, so even a strong warm-current anomaly could not lift a mild-winter coast over
the annual-mean bar. Bergen is temperate at an 8°C annual mean because its *coldest month* is about
2°C, not because its year is warm — real Köppen classification never looks at the annual mean for
this boundary at all.

`classify` now reads the coldest and warmest month instead: warmest month under 10°C is tundra
(ET); warmest above 10°C with coldest at or below −3°C is continental (D), where taiga lives, split
from tundra by moisture exactly as the old `t < 7` branch was; warmest above 10°C with coldest
above −3°C is temperate (C), keeping every existing moisture class including the Mediterranean one.
The tropical line is Köppen's own, a coldest month at or above 18°C, taken verbatim.

Reading the coldest month at all needed the latitude curve to actually reach it: at the exponent
seasons landed with, 45° — the effective latitude a 55° coast's summer reads off, one
`seasonalTilt` equatorward — sat at a mere 6.8°C, below the 10°C tree line regardless of any
current. The exponent moved from 1.25 to 1.8 (equator and pole anchors untouched) to fix that, and
it cuts both ways: the same lift that gets a coast's summer past 10°C also lifts a *continental
interior*'s winter past Köppen's −3°C line at the same latitudes, so a dry rain-shadow interior that
used to be taiga could reach `classify`'s desert check on nothing more than a fixed millimetre cut
— measured, that alone dropped desert-in-band on seed 42 from 98–100% to 48%, because the two
effective-latitude ranges overlap almost exactly and no choice of exponent or pole separates them.

A6 landed with that gated off by a provisional fix (an annual mean of at least 13°C added to the
temperate branch's desert case) rather than solved, and said so: the gate abolished cold deserts —
the Gobi's annual mean is about 2°C, Patagonia's under 10 — and named the real fix as a Köppen B
(arid) test on rainfall in mm against a temperature-dependent threshold. [A4 Absolute
rainfall](REALISM_PLAN.md#a4-absolute-rainfall--sonnet) is that test, and the 13°C gate is gone: B
is now decided before any of Köppen's thermal groups run, exactly as real Köppen decides it, on
`classify`'s own `koppenAridityThresholdMm` — `20 × annual-mean-°C` plus a seasonal-concentration
term, with desert (BW) below half that threshold and steppe (BS) below it outright. The formula is
self-limiting at cold temperatures: at an annual mean of −15°C the threshold is already negative, so
no rainfall total can read as arid there, and a genuinely polar cell reaches the ET gate untouched —
a cold desert has to be cold *and* dry, not merely cold.

The concentration term is not Köppen's own 280/140/0mm figures, though it keeps their shape (warm-
season-concentrated rain demands the most to escape aridity, cool-season-concentrated the least).
This march's `coldCap` suppresses winter moisture by temperature almost everywhere cold — a
temperature effect, not a seasonal-rainfall-pattern one — so on a measured seed the warm/cool
rainfall ratio has a *median* of 18.7 at 50-70°, calling nearly every cold cell "concentrated"
regardless of whether either season actually brought meaningful rain. Applying Köppen's real figures
unguarded put desert as far as 70°+ and dropped desert-in-band to 76/73/94/80% on seeds
7/42/1234/99. Fixed in two steps, both measured against the same guard: a floor requiring the wetter
season to have brought a real amount of rain (500mm) before its ratio is trusted, and the
concentration constants scaled to `32`/`16`/`0` millimetres — a fifth of Köppen's own figures, the
first value found past a straight halving (which measured worse on one seed, confirming the
remaining shortfall was a genuine compact rain-shadow region rather than a value to tune past) that
cleared 85% on every seed. Desert-in-band is 86/90/92/85% on seeds 7/42/1234/99 — see
`AbsoluteRainfallTest` and `GeographyAuditTest` for the full figures, and `DesertCauseTest` for the
per-cell diagnostic this was checked against.

Verified by `ColdCapReportTest`, extended from A5's report into an assertion: on seeds 7/42/1234,
the share of 50–60° west-facing coast cells with a positive current anomaly classing as temperate
forest or rainforest is 60.8/66.0/29.4% (two of three above half, up from 0.0/0.0/0.1% on the
classifier before A6), while the interior at the same latitudes — too far from any coast for a
current to reach — stays 99.8/97.0/99.8% taiga or tundra. Siberia stays taiga.

A6 also exposed, and fixed, a real bug in how peoples settle the ice margin. `CultureStage` decides
habitability per drainage-basin *unit*: `biome[u]` was a majority vote across every cell in it, and
that vote gated both whether a culture could ever reach the unit and which of its cells ended up
settled. A unit straddling a retreating ice margin can vote `ICE_SHEET` while a large minority of
its cells individually are not — measured, 11,144 such cells on seed 7 alone — and the whole unit,
non-ice minority included, was then unreachable. `habitable[u]` is now "this unit has at least one
non-ice cell", so the spread reaches every mixed unit; `biome[u]`'s majority vote is untouched for
everything else it decides (hearth scoring, `climateDistance`). Which cells actually get settled is
then decided per cell against that cell's *own* biome, not the unit's vote — people live on the
tundra half of a catchment even when the other half is ice. `CultureStage.profile` also drops a
second, redundant habitability test, `CulturesConfig.minTemperatureC`, that duplicated the
temperature test `Biome.ICE_SHEET` already is; measured, it never actually excluded a unit on any
of seeds 7/42/1234, so it was a latent bug rather than a live one.

`CultureRealmTest` now settles 100% of habitable land on all three seeds (seed 7 was 86% against a
90% floor before the fix), and `RealmSpreadTest` still holds — 100% of land claimed on every seed,
zero inland enclaves.

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

## Three kinds of collision

Convergence says two plates are closing; it does not say what the closing builds. Oceanic crust is
dense and goes under, continental crust is buoyant and will not, so which crusts meet decides the
landform — and for a long time this generator raised the same belt for all three cases, which is
why every range on its maps was the same range.

- **Ocean under continent is the Andes.** A trench offshore on the subducting plate, a narrow range
  along the coast of the overriding one, and a line of volcanoes a fixed distance inland of it,
  because a slab melts once it is deep enough rather than where it goes under. That offset is the
  reason the profile had to become a signed one: nothing built out of distance-to-the-boundary
  alone can put a crest anywhere but on the boundary.
- **Continent against continent is Tibet.** Nothing subducts, so the crust thickens over a wide
  area instead of piling onto a line: a plateau three times the width of the coastal range and
  lower than its peaks, flat across most of its span, with rim ranges around the edge. The
  along-strike sag that turns a long belt into a chain of massifs is damped to a seventh here,
  because a plateau that breaks into massifs is a chain again — uniform height over a very wide
  area is the striking thing about Tibet and the thing worth reproducing.
- **Ocean under ocean is an island arc.** The same trench, and behind it a narrow volcanic ridge on
  the overriding plate — chosen as the lower plate id, since between two plates of the same kind
  the choice is arbitrary and has to be made by something that cannot vary between cells. It is
  built on oceanic crust, so most of it stays under water and only the swells of `rangeVariation`
  break the surface, which is what makes an arc a chain of islands rather than a ridge of land.
- **Divergence under continental crust is a rift valley, and a rift valley is a chain.** A trough
  on the axis between two rebounding shoulders, rather than the simple groove it was; under
  oceanic crust it stays a spreading ridge. But no rift on Earth holds one depth between two
  shoulders of one height for a thousand kilometres. A rift is a string of half-grabens: each
  basin hangs from a fault on one flank, with the floor deepest against that footwall and rising
  across to a low hinge on the other, and the polarity flips from one segment to the next, with an
  accommodation zone between them where the floor rises to a sill. So the sea enters only the
  segments that have subsided below it, and what a drowned rift gives is a string of gulfs and
  lakes joined by sills and land bridges — the Red Sea, the Gulf of California, Baikal and
  Tanganyika — rather than a canal. Each segment is a fraction of the map's width rather than a
  count of cells, so the same rift breaks into the same basins at 512 and at 2048, and its
  shoulders vary in height and width with the segment and with `rangeVariation` as ranges do.
- **A few oceanic plates carry a hotspot**, a point that stays put while the plate drifts over it,
  leaving a line of seamounts along the drift vector that subside with age. It is the only thing
  in the pipeline that puts islands somewhere other than a plate boundary. Measured on seeds 7, 42
  and 1234: 0.10–0.21% of the map raised, of which roughly three fifths is clear of every belt.

Verified by `BoundaryPairTest`, which measures each class's mean radial profile away from its
boundary, takes the height as the peak above the plate interior and the width as the full width at
half that height, and asserts the plateau is at least twice as broad for its height as the coastal
range. Pooled over six seeds that carry all three convergent pairs it measures 3.3x, and the same
measurement with `crustPairProfiles` off — one belt profile for every convergent boundary, as
before — returns 0.6x, the margin then being the broader of the two because the old code gave an
oceanic-continental boundary four fifths of the height at the same width. Per seed the plateau is
68–72 cells across at half height against the margin's 10–16.

## Not modelled, and probably shouldn't be

Settlements below the capital, trade routes, and roads. The atlas invents exports and imports from
what a realm's land can produce, but there is no network of towns for them to move between.
