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

**Rivers cross depression-filled basins, which is uphill on the raw surface.** 13–20% of river
segments rise rather than fall against unfilled elevation (worst single rise 0.055 of the elevation
range). Priority-flood filling makes a basin drain by raising it, so the water is routed correctly
but the ground beneath it does not slope. Visible only if you shade by elevation and follow a river
through a basin.

*The fix is lakes.* A filled basin that holds water should render as a lake, with the river entering
it and leaving at the spill point. That removes the artefact and adds a feature the maps currently
lack entirely.

**Desert placement is only loosely tied to latitude.** Two of four seeds put desert mean latitude at
16°, with under half of desert inside 15–45°. Rain shadow can dominate the latitude band and push
desert toward the equator, where rainforest belongs. The latitude model is also wrong at high
latitudes: the ITCZ term makes 60° the driest band, when it should be moderately wet. It rarely
shows because those latitudes are cold enough to classify as tundra or taiga on temperature, but the
underlying curve is not right.

**No lakes at all.** Depression filling removes every enclosed basin. The 17–31 "inland water
bodies" the audit counts are enclosed *seas* — regions below sea level cut off from the ocean — not
lakes fed by rivers. Real maps have both.

**Nothing models ocean currents or continentality.** Biomes come from latitude, altitude and
orographic rainfall. A warm current making a high-latitude west coast temperate, or a continental
interior swinging further between seasons than a coast at the same latitude, are not represented.

**No erosion.** Coastlines come from a sea-level cut through the height field. There is no
deposition, no deltas, no fjords carved by ice, no meandering. It looks plausible; it is not the
result of a process.

## Fixed by this audit

**Capital siting.** Every capital was coastal — 12 of 12 on every seed, which no real map shows. The
harbour bonus was large enough to outweigh everything else, and the best river cell is always the
mouth. Siting now weighs fresh water above a harbour, adds the surrounding hinterland's carrying
capacity and defensibility (height above the local average), and prefers the **head of navigation**
over the river mouth — inland enough to be defensible and out of the floodplain, still reachable by
water, which is why London, Paris and Rome are where they are.

After: 6–8 of 12 coastal, 6–9 of 12 on a river. A mix rather than a rule.

## Not modelled, and probably shouldn't be

Settlements below the capital, trade routes, and roads. The atlas invents exports and imports from
what a realm's land can produce, but there is no network of towns for them to move between.
