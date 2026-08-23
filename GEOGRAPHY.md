# Geography audit

What fantasy maps are commonly caught getting wrong, and what this generator actually does about
each. Measured with `GeographyAuditTest` on four seeds at 512×512, not asserted.

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
bands follow Earth: trades easterly below 30°, westerlies 30–60°, polar easterlies above.

**Deserts sit near the horse latitudes.** Desert mean latitude 16–29° against a land mean of 41–53°,
so deserts are pulled strongly equatorward of average land, toward the 30° band.

## Known deviations

**Some river segments still run uphill on the raw surface.** 12–14% of drawn segments rise rather
than fall against unfilled elevation, down from 13–20% before lakes. What remains is shallow filled
ground that does not clear `LakesConfig.minDepth` — flats raised by a hair rather than basins deep
enough to hold water. Dropping the threshold would catch more of them at the cost of flagging half a
continent as lake.

**Desert placement is only loosely tied to latitude.** Two of four seeds put desert mean latitude at
16°, with under half of desert inside 15–45°. Rain shadow can dominate the latitude band and push
desert toward the equator, where rainforest belongs. The latitude model is also wrong at high
latitudes: the ITCZ term makes 60° the driest band, when it should be moderately wet. It rarely
shows because those latitudes are cold enough to classify as tundra or taiga on temperature, but the
underlying curve is not right.

**Nothing models ocean currents or continentality.** Biomes come from latitude, altitude and
orographic rainfall. A warm current making a high-latitude west coast temperate, or a continental
interior swinging further between seasons than a coast at the same latitude, are not represented.

**No erosion.** Coastlines come from a sea-level cut through the height field. There is no
deposition, no deltas, no fjords carved by ice, no meandering. It looks plausible; it is not the
result of a process.

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

What is still missing is water. Nothing carves a valley, so the rivers find routes down terrain
that was never shaped by them.

## Not modelled, and probably shouldn't be

Settlements below the capital, trade routes, and roads. The atlas invents exports and imports from
what a realm's land can produce, but there is no network of towns for them to move between.
