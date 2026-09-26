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

**Rivers never split.** Measured 0 splits across all seeds. The routing gives every cell exactly one
downstream neighbour — the direction is taken from the steepest facet and the receiver drawn across
it, but it is still one receiver — so the drawn network is a forest and a river physically cannot
fork. This is the single most common river sin and the pipeline cannot commit it.

**Rivers never run coast to coast.** Measured 0 rivers rising on the shoreline. Sources are
headwaters — channel cells with no upstream channel — which by definition sit inland.

**Rivers always reach the sea.** Every river terminates at water or merges into one that does;
`RiverEndingsTest` follows the whole drainage and finds no breaks. Depression filling guarantees
every land cell has a downhill path out.

**A channel begins where the ground can cut one.** A cell carries a channel where its
runoff-weighted drainage area times its gradient to the power 1.65 clears a threshold in square
kilometres, and everything downstream of such a cell carries one too, because discharge only grows.
That is Montgomery and Dietrich's criterion (1988, 1989; Dietrich et al. 1993) and the exponent is
their own fit over their steepland channel heads, `A ∝ S^-1.65`; the `A·S²` that gets quoted more
often is Dietrich et al.'s landsliding threshold, a different way for a hollow to become a channel.
The threshold is theirs too: five hectares of support area at a head gradient of 0.4 gives 0.011
km², and their arid lowland sites — three square kilometres at a gradient near 0.05 — give 0.021,
twice as much rather than two hundred times, which is the finding those papers are about.

Two terms make it a criterion rather than a number. The area is weighted by the cell's runoff
against Earth's mean over land, 715 mm a year, so a wet hillside reaches the threshold on less
ground. And the threshold is multiplied by the plant cover the ground carries, up to two
hundredfold under a closed canopy, which is the universal soil-loss equation's cover factor (1.0
bare, 0.005 forested) read as a resistance. The gradient is the **true ground's**, never the routing
surface's: the depression fill and the potential laid across the flats it makes are bookkeeping that
let a receiver be chosen, and a flat's true gradient is zero, which is why a reach crossing one
initiates nothing of its own and is carried by the downstream rule instead. Ground that never
thaws — where the warmest month of the year stays at or below freezing — starts no channel, though
a river rising in a warmer catchment still runs across it.

The runoff itself is one figure for the whole pipeline, `Runoff.annualWeightMm`: a cell's rainfall
in millimetres a year, held at or above a floor of **60 mm** so that an arid upland still feeds the
channel leaving it. Sixty millimetres is where UNEP's hyper-arid class ends — an aridity index,
rainfall over potential evaporation, of 0.05 — under a potential evaporation of 1,200 mm, the scale
`ClimateResult.precipitation` is normalised against; it is also the figure the river stage's own
floor stood for, 0.05 of that scale, so nothing that already read it moved when it was restated. The
channel criterion had it wrong at 150 mm until 2026-09-22 — read off a 3,000 mm scale that nothing in
the climate uses, and added to the rainfall rather than taken as a floor under it, which gave every
desert cell a fifth of Earth's land mean in water it does not have. Correcting it thins the network
where it should be thinnest: pooled over the four standard seeds at 512, drainage density in
hyper-arid country falls from 0.0527 to 0.0362 km/km² against humid country's 0.0309 to 0.0280, so
the dryland peak is narrower and the humid-over-semi-arid ratio rises from 0.71 to 0.83 — still
under one, which is the side of Moglen, Eltahir and Bras's curve the guard asserts, but with less
room than before.

**The drawn network carries the rain that falls, however much it is.** Until chunk 6 the discharge
the rivers are drawn, ranked and split by was weighted by the 0..1 rainfall, clamped at 1,200 mm and
with the floor added on top: a trunk draining 3,000 mm of rainforest carried what a trunk draining
1,200 mm did, and a 100 mm desert cell counted as 160. It is `Runoff.annualWeightMm` now, and so is
the threshold the realm stage reads a river off, so the two are in one unit. Measured over the export
selection at Earth's density on seeds 7/42/1234/99 at 512 and 969495 at 2048, the share of drawn
kilometres in catchments averaging over 1,200 mm rose from 0.0/1.5/0.8/4.6/2.6% to
0.6/2.5/1.1/6.7/3.4%, and in catchments under 400 mm fell from 69.6/57.8/75.4/62.9/65.8% to
68.4/56.7/75.0/59.0/64.2%; the courses drawn went from 58/71/56/67/201 to 58/74/55/67/209. The
median drawn mouth's width ratio moved from 0.432/0.348/0.340/0.398/0.319 to
0.460/0.362/0.321/0.436/0.312: widths are a share of the widest trunk, and which trunk is widest now
depends on how wet its country is. `WaterReceivedTest` holds two identical islands at 1,500 and
3,000 mm to a discharge ratio of two; the clamped weight gave one.

What each stage divides that weight by differs, and the difference is the point. The channel
criterion divides by **Earth's** land mean, because its threshold is an area of real ground: against
this world's own mean a world twice as wet would draw exactly the same network. The hydraulic
erosion divides by the mean over **the land the pass is routing on**, because its incision
coefficient was fitted at one world's total water and a mean of exactly one is what keeps a weighted
accumulation a share — which is why that stage says where the rain falls and not how much, recorded
below with the rest of its limitations.

Everything in that rule is a length, an area or a dimensionless gradient, so it means the same thing
at every grid. What it replaced meant three different things: a channel was drawn where the
accumulated runoff passed a share of the world's own total, the drawing was capped at four hundred
courses, and a course under eight cells was dropped — a share, a count of courses and a count of
cells, each of which describes different ground at 512 and at 2048. The one rule left about the
drawing is cartographic and says so: a course shorter than a hundred kilometres is not given a line
of its own, and a lake's outflow is exempt, because everything the lake drains comes down it and
its length is the lake's business rather than the channel's.

What the criterion cannot do at these grids is place an individual channel head. A cell is 275 km²
at 512 and 17 at 2048, and a humid channel head's support area is one to ten hectares — far below
one cell either way — so in wet country the area side of the inequality is settled before it is
asked and the gradient and the cover decide. What it can resolve is the aridity dependence: the
threshold reaches 0.6 km² under a closed canopy against 0.013 on bare ground, and a dry lowland's
support area runs to square kilometres, which spans cells at 2048.

**Drainage density peaks in dry country.** Langbein and Schumm (1958) and Moglen, Eltahir and Bras
(1998) put the maximum of channel length per unit of land at low to intermediate effective
precipitation, with a fall-off on both sides: more rain makes more runoff, and more rain also grows
the cover that holds a hillside together, and above semi-arid country the second wins. Only the
second can make a curve turn over — a runoff weight on the area can push cells over a fixed bar and
can never bring one back — so the cover term is what this claim rests on. Measured over the network
the criterion initiates, pooled on seeds 7/42/1234/99 at 512, the density is 0.0527, 0.0499, 0.0436,
0.0375 and 0.0316 km per km² across hyper-arid, arid, semi-arid, dry sub-humid and humid country,
and pooled on six seeds at 2048 it is 0.0741, 0.0674, 0.0602, 0.0508 and 0.0475. The peak is in a
dryland on every seed at both grids and humid country carries 0.73 of the semi-arid density at 512
and 0.79 at 2048. With the cover term off the same worlds peak in humid country and the ratio is
1.05 to 1.15, which is the control.

**A river runs from its farthest source.** A course is traced from the headwater with the longest
way down to the water rather than from the one already carrying the most, so what the map calls a
river holds the whole of the longest watercourse in its catchment and every other branch is drawn
as a tributary of it. Ranking by flow instead picks a short fat tributary surprisingly often, and
then the river's own upper half is drawn afterwards as a tributary stopping at the junction — the
union of drawn cells is the same either way, but nothing that reads one `River` as one river is
right. Measured on seeds 7/42/1234/99 at 512, the drawn courses cover **1.000** of the watercourses
they stand for by cell count, against 0.780 under the biggest-headwater order (M1's own measure of
the same defect: 0.484 at 512 and 0.408 at 2048).

**A river is drawn through water narrower than itself.** A lake stands at its basin's spill level,
which at the ends of the basin covers the channel that feeds it. Where that strip is one cell wide
it is a river and not a lake — a cell here is 23 km at 512 down to 6 km at 2048, and the Amazon's
mouth is about 10 — so the tracer and the renderer stop only at *open* water, meaning a lake cell
belonging to some 2×2 square of its own lake, and run the line through the rest. Left as lake, such
a strip was painted in flat water with no river over it, and a whole catchment's trunk crossed it as
a one-pixel thread between two thick channels (a dotted one where the strip ran diagonally and the
cells met only at their corners). Over seeds 7/42/1234/99 at 512, 237 channel cells stand under
water one cell wide; before, none of them was drawn and every line that reached one stopped dead.

**A river's ink stops at the shoreline.** A traced course ends *in* the water, so that the line
reaches it rather than stopping a step short; drawn literally that put the stroke's centre a whole
cell past the coast and the round cap that blends one cell-long segment into the next half a stroke
beyond that again — 3.0 to 3.2 pixels of river ink lying on the open sea under F10's pen. The last
stroke is now cut back along its own course by half its width, so the cap is tangent to the coast
and the last pixel of the river is the shoreline pixel. Measured on 298405 and seeds 7/42/1234/99 at
512: no stroke ends over water, and no river pixel falls on water with no land beside it (3 to 16
before).

**How wide a river is drawn is a share of the sheet.** A drawn river is a cartographic
exaggeration, not a width to scale: the Amazon's ten-kilometre mouth is 0.08% of a
twelve-thousand-kilometre world and would be invisible, and a printed map exaggerates a river of
that class about threefold. So the widest stroke on a map is **0.24% of its width** — 1.2 px at
512, 2.5 at 1024, 4.9 at 2048, 9.8 at 4096 — and the finest is a 0.8-pixel hairline, which is a
nib rather than a width and does not grow. Between them the stroke follows Leopold and Maddock's
square root of discharge. F10 held the full pen at five pixels whatever the size of the sheet,
which at 2048 was right and at 1024 was twice the ink against the same country. One consequence is
worth stating: with the hairline fixed, the range a sheet can show shrinks with it — the nib spans
6.1x at 2048, 3.1x at 1024 and only 1.5x at 512, so on a phone-sized map a trunk and a headwater
are nearly the same line, because the headwater is already the finest mark there is.

**The coast is a line, not a staircase of cells.** The raster inks the landward cell of every
land–water pair, which is right at one pixel to the cell and wrong at any other size: shrink it and
the line thins to nothing, enlarge it and the reader is looking at the grid. So the same boundary is
also traced off the land mask by marching squares — every vertex halfway between one land cell and
one water cell, so it runs exactly where the raster inks — and stroked over the fill at 0.05% of the
sheet's width, one pixel at 2048. Where four cells meet in a checkerboard the contour is closed so
that land touching corner to corner stays one coast, which is the same assumption the flow routing
makes when it lets a river run diagonally across an isthmus a cell wide. On 718106 at 2048 the trace
is 83,551 vertices and takes 17–35 ms against the raster's 148–236 ms.

**A map draws as much river as a published map at its scale draws, and no more.** How much that is
is an Earth figure and not a share of what the generator traced. Natural Earth's river layer, the
public-domain data built for small-scale mapping at three stated scales, draws **0.001707 km of
river line per km² of land at 1:50 000 000** (359 courses, 254,284 km over Earth's 148.94 million
km²) and **0.004025 at 1:10 000 000** (1,202 courses, 599,507 km). Between those two tiers the
drawn *length* goes as the 0.533 power of the change in scale, which is Töpfer and Pillewizer's
square root (*The principles of selection*, The Cartographic Journal 3(1), 1966) to within a
thirtieth — so the ink is carried between scales by their law, anchored on the 1:50M tier. Their
law counted features rather than measuring length, so carrying a length by it is an assumption;
Wilmer and Brewer (*Application of the radical law in generalization of national hydrography data
for multiscale mapping*, ISPRS Archives XXXVIII-4, 2010) make the same one for hydrography measured
as flowline length per square kilometre and find it needs a correction of its own, and between
these two tiers 0.5 is interpolation while anything outside them is extrapolation. Natural Earth is
a chosen benchmark and not a physical constant: its linework is hand-smoothed and hand-ranked, and
a different atlas would give a different figure. The
drawn *count* goes as the 0.751 power instead, which is what small-scale generalisation actually
does: it drops short courses outright rather than drawing half-length rivers, and the mean drawn
course is 499 km at 1:10M against 708 km at 1:50M. So the budget is in kilometres of ink, which the
law carries and which does not turn on where one course is cut from the next.

A sheet's scale is its representative fraction, which needs the drawing's physical size and not the
grid: at a 12,000 km world width and the CSS reference pixel, a 2048 export — drawn since Fix A on
its true-shape sheet, 4096 pixels by 2048 — is about 1:11 000 000, and the same world fitted into a
900-pixel pane about 1:50 400 000, which the pane's own half-octave zoom bands round to
1:44 300 000, the scale the selection is actually made at and the scale quoted below. The export's
figures here were measured on the square sheet that preceded it, at 1:22 000 000, and are kept as
that sheet's (the pane's scale did not move): seed 969495 at 2048 traces 2,081 courses and 471,294
km of watercourse; that export drew **107 courses and 70,294 km over 27.4 million km² of land,
0.002565 km/km² against Earth's 0.002565 at that scale**, and its pane 62 courses and 49,705 km.
`RiverSelectionAuditTest` prints the true-shape export's own. The rule that preceded this one kept a share of the traced count by the same
law of Töpfer's, which relates a derived map to a source map and so has no absolute answer: it drew
6.7 times that ink on the export and 7.7 times on the pane, and the *same country at the same size
on the same screen* came out at three densities depending on whether the world behind it had been
generated at 512, 1024 or 2048 — 0.010182, 0.012508 and 0.012781 km/km² on seed 7, a spread of
20.3%, against 0.001814, 0.001813 and 0.001814 now, a spread under a tenth of a per cent.

Which courses survive is decided by discharge, largest first, on the peak width ratio — the square
root of discharge normalised over the network. Two rules sit on top of that. The budget is spread by
a crowding lattice whose pitch is the mean spacing the reference's own course density implies (645
km at 1:50M, 475 km at 1:22M, 366 km at 1:11M): the first pass takes at most one course per square, the second
spends what is left, and the fullest square on the four standard seeds falls from 49–70 drawn
courses to 2 — which is what stops a straight coastal front from keeping every gully it has cut.
And a drawn tributary's trunk is drawn, always: ranking by discharge alone would give that for
nothing, because a trunk's peak is never below its tributaries', but the crowding pass can defer a
trunk and reach its tributary afterwards, so the chain below a course is taken with it and charged
to the budget.

**The reader can turn the ink up or down, and Earth's figure is the default mark.** The Cartography
panel's River density slider multiplies the budget in half-octave steps, √2 a mark, from a quarter
of Earth's figure to four times it, with a tenth mark at the top that removes the budget. The bottom
is the reference's own sparsest sheet: Natural Earth's 1:110 000 000 tier draws 13 courses and
42,873 km, 0.000288 km/km², where the law carried from the 1:50M anchor asks 0.001151 of that scale
— a quarter. The top is the rule that preceded this one, to the course, because there the budget
gives way to the radical law's cut on the traced count: every course on an export, 71% of them in a
pane at half a pixel to the cell, every course tied at the cut kept. The cut applies at the top
alone. The largest river and the trunks below it are drawn at every mark, since the budget never
falls below that chain, so Earth's mark is the selection described above to the course wherever
that chain fits Earth's budget. Nothing bounds the chain, so the floor could bind at any mark, the
default included; on the worlds measured - the six audited seeds at 512 on the export and in the
pane, and 969495 at 2048 - it binds at none, the longest such chain being 5,755 km against a
smallest bottom-mark budget of 8,791 km. The lattice runs at every mark, and it spreads rather than
caps: on the four standard seeds at 512 the fullest square
holds one or two drawn courses up to Earth's mark, 7–12 at twice it, 18–25 at four times, and 49–70
at the top, which is the law's own. So a reader who asks for every river asks for the coastal comb
back, and the mark says so.

**The coast is generalised by its own rule**, Douglas–Peucker at half a drawn pixel, which is a
tolerance in the plane of the drawing and needs no reference outside it: 19,634 vertices at fit
against 51,749 at four times zoom on seed 718106 at 2048.

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

**The crust floats, so the world has two levels and the sea has a floor.** Every cell carries a
mixture of continental and oceanic crust, blurred across a 300 km margin — the width over which
the mixture runs from nine tenths continental to a tenth, the same on the ground whichever way the
margin faces — so the band between the two is a shelf, a slope and a rise rather than a step, and
Airy isostasy turns the mixture into an
altitude: a standard continental column floats at Earth's mean land elevation of 840 m and the sea
floor at the depth its own age puts it. Everything follows from those two numbers. The
hypsometric curve has two modes with a trough between them where the continental slope is, which
is what Earth's has and what this generator did not have before S2 — its curve was one peak
straddling the shoreline, because the height field was renormalised to its own extremes after every
generation and the sea's mode sat 390 m down. The ocean-coverage slider now chooses how much of the
world is drawn as continental crust rather than where to cut a histogram, and the percentile cut is
the check: measured on five worlds it lands within 200 m of the level isostasy puts the
shoreline at.

**A continent is thickest in the middle, so what it drowns is its rim.** Continental crust is not
one thickness: Christensen and Mooney measure shields and platforms at 41 to 45 km and extended,
rifted crust at 30, so the crust thickens inland from its own edge over the 200 to 500 km a rifted
margin takes to thin. Both the thickness and the relief the base noise carries on it now follow
that profile — the craton stands 44.6 km thick and as flat as the West Siberian Plain, the crust's
outer edge 32.6 km and as varied as an Atlantic margin — and the thickness half of it is
mass-neutral, so the average column is still 41 km and the datum is still Earth's 840 m of
freeboard. What changes is where a continent drowns. With one thickness and one spread everywhere,
isostasy put a continent under water wherever the noise happened to dip, which drew flooded
interiors and inland seas; now four fifths of the drowned continental crust lies within 500 km of
the crust's own edge, which is a shelf.

**Cell-scale texture is proportional to relief, so plains are born smooth.** The base relief is
split at 200 km. Everything broader is the shape of the country and carries the crust's own
deviation; everything finer is dissection, and its amplitude at each cell is the local relief of
the ground the noise and the belts make, scaled down self-affinely with Earth's own Hurst exponent
of 0.7. That is Ahnert's relation — denudation grows linearly with local relief — and the rendering
tradition's heterogeneous terrain, which is the same statement. The window the relief is read over
cancels out of the arithmetic, because a self-affine surface's relief grows as `window^H`, so the
only figure chosen is the corner. Before it, the finest thing the grid could draw was as loud on a
coastal plain as on a mountain front and the whole of the land read as sandpaper. Measured as the
median departure from a four-cell box mean, the lowest quarter of the land now reads 63 m against
the pre-S2 generator's 65 and the highest 156 against its 116.

**The sea floor is as deep as it is old.** Ocean floor is made at a spreading ridge and sinks as it
cools, as the square root of its age — Parsons and Sclater's `2,500 + 350*sqrt(t)` metres,
flattening onto their 6,400 m asymptote past about seventy million years. So the depth of every
cell of sea floor is the distance to the nearest of the present epoch's spreading boundaries,
divided by the rate the plate carries it away at, put through that curve, and read back through
Airy as the thermal buoyancy a column of that age must carry. A ridge stands at 2,500 m and the
oldest floor on a map lies two to three kilometres below it along a smooth curve, where before S2's
second pass every piece of sea floor was of one age and the deep ocean was one level per plate with
the Voronoi partition showing straight through it. The spreading rate is solved rather than
declared: floor is destroyed as fast as it is made, so a world with less ridge for its ocean must
spread faster, and what a map can be held to is not the rate but Earth's mean ocean depth of
3,682 m, which is what the rate is solved from.

**A range that is being pushed up holds its height, and one that has stopped does not.** An uplift
rate in millimetres a year runs under the belts of the present epoch — 0.74 in a continental
collision, 0.30 on an Andean margin, less on an island arc and a rift's shoulders, nothing at all
on a craton — and is spent every hydraulic round over the years a round stands for. The collision
rate is rock uplift, Earth's surface uplift for a collision (half a millimetre a year) plus the
denudation this model's rivers and hillslopes take off an active belt with the uplift and the
flexure off, 0.238 mm a year on five worlds; the others follow England and Molnar's ratios
([Fix 3b](DESIGN_LEDGER.md), on the implicit incision, where the law and not a cap sets that
denudation). So the rivers
are cutting a belt that is still rising, and the height it settles at is the balance between the
two, which is Whipple and Tucker's steady state rather than a stamped profile. An old belt is low
because its uplift stopped and erosion went on, and the epochs say when: the ageing is
`exp(-time / decay time)` from two figures of Earth's rather than a factor per orogeny.

**The plate bends under what is put on it.** Flexure is a low-pass filter on the load,
`w(k) = L(k) / (dRho g + D k^4)`, solved by the same FFT the terrain stage uses, and it answers at
the head of a hydraulic round rather than at its tail — so every bend has a round of rivers after
it to adjust to it, which is the order the Earth does it in and which keeps a broad warp from being
the last thing laid on a landscape whose water has already finished routing. A range that is
being stripped loses mass and rebounds; the ground in front of it takes that mass as sediment and
sinks, which is a foreland basin; a delta subsides under its own load; and an ice sheet holds its
bed down, which is why Greenland's bed lies below sea level and why Scandinavia is still rising a
centimetre a year. What the ice presses down is the *bed*, and the elevation field is a surface —
the climate reads its altitude for a temperature and the rivers run down it — so over the middle of
a cap, where the ice fills the hollow it makes, the surface does not move at all; the bend shows at
the margin, where the sheet has thinned to nothing and there is nothing to fill it. That is the
moat: the Baltic, and the string of lakes along the Laurentide's rim. S2's second pass spent the
whole bend on the surface instead, and the map paid for it twice — the biome stage read the cap's
own ground as warmer and the ice share of land fell from 8.0% to 6.2%, while the hollow under the
cap ponded and the lake share rose from 2.6% to 3.6%. The elastic thickness is 30 km, the middle of the
20-40 km Watts measures for mature continents, which puts the flexural parameter at 68 km — three
cells of the default grid, so a load's own basin reaches some 160 km in front of it.

**The ground's own relief has a scale, and it is the one a range is read at.** The base terrain is
an integrated noise field, and integration divides each component's amplitude by its wavenumber, so
left alone it comes out as a map-wide tilt with the detail riding on it as a ripple. That was the
right shape while the noise was all the terrain there was and the wrong one once the crust decides
where a continent stands: the tilt fought the crust and smeared the two hypsometric modes together.
So the spectrum is shaped rather than scaled — a first-order high pass in the same transform that
integrates it, leaving the relief loudest at 400 km, which is the scale Earth's non-orogenic
continental relief sits at and the scale a range is read at on a map. Its amplitude is a standard
deviation in metres: 700 m at a continental margin, which is Earth's own spread away from its
orogens, 500 in a craton, which is a shield or a platform with the epeirogenic swells that sit on
one, 250 on the sea floor, which is Goff and Jordan's abyssal hills, and 700 m more inside an
active orogen, which is the relief between the cordilleras of one. A sixth of the map-scale component is
kept, because drainage is organised by a continent's longest slopes and a surface with nothing at
that wavelength grows many short rivers instead of a few long ones — and, as S2's third pass found,
ponds the water where it falls. At a tenth, which is what the second pass measured the bifurcation
ratio against, lakes covered 3.55% of the land against Earth's 1.48% at this cell area and the
sea-level cut landed on a platform flat enough to drown into an archipelago; at a sixth the lakes
are 0.70%, the drainage density is 0.0032 km/km² against the pre-S2 generator's 0.0026, and every
seed's coastline clears Mandelbrot's floor. Above a sixth the coast goes: a map-scale tilt moves a
shoreline bodily. Since S2's fourth pass gave the crust a thickness that rises inland, the sixth is
no longer what stops the ponding — at a tenth *and* a crust with no profile of its own the lakes
read 0.96% — and whether it is still needed at all is in `TODO.md`.

**Rain shadow is real, not decorative.** Rainfall is produced by marching moist air along prevailing
winds and wringing it out on windward slopes, so leeward dryness emerges from the simulation. Wind
bands follow Earth: trades easterly below 30°, westerlies 30–60°, polar easterlies above — and those
boundaries migrate with the season, so a coast can sit in one belt in summer and another in winter.

**Deserts sit near the horse latitudes.** Desert mean latitude 31–33° against a land mean of 41–53°,
so deserts are pulled strongly equatorward of average land, toward the 30° band.

**An interior swings more than a coast.** How much of the sea's year a cell takes falls away from
the coast with an e-folding of 350 km, measured on the ground to the nearest sea by a jump-flooded
Euclidean distance transform, so a shore and an interior at the same latitude do not swing alike,
and a coast facing north reaches as far inland as one facing east. Lakes are not in it: the
climate is solved before the rivers decide which hollows hold water. Since
W1 there is no setting for it: the gap is the difference between the band's two air columns — one
with a fifty-metre mixed layer under it and one with three metres of soil — and the world's own
coastline deciding how much of each a place gets. See "Temperature is solved, not drawn".

**The sea has not always been where it is.** The hydraulic rounds grade every channel to the sea
they can see, so with the sea fixed at today's level no valley may continue below it and every
coastline is a clean percentile cut. Earth's rivers cut to a stand about 120 m lower and the sea
came back up their valleys when the ice melted, which is the Chesapeake, the Severn, Galicia's rias
and the sounds of the Atlantic seaboard. `SeaConfig.lowstandMetres` puts the base level 120 m down,
read off the height field's own 16,000 m of relief, for the first nine of the twelve
rounds and walks it up to today over the last three, so the drowned valleys collect some of the
sediment coming down them as a real estuary does, and the deltas are built at the level the map is
drawn at. Measured at 512 on seeds 7, 42 and 1234: river mouths lying more than three cells inside a
narrow inlet run 26/24/15 with the stand at zero and 40/50/73 with it at the default, and the
ocean's shoreline runs 1.11, 1.23 and 1.55 times as long against a compact coast of the same land
area. At 2048 on the author's own two worlds, 3 estuary mouths become 32 on seed 718106 and 1
becomes 9 on 59758.

**Water the ocean cannot reach is not sea.** Sea level is a percentile over the height field, so
every hollow below it used to be drawn as ocean whether a drop of ocean could get there or not, and
a D8 river ended at the first one it met: a third of every seed's mouths did, and seed 59758 at 2048
carried 475 separate bodies of water outside the ocean. After the cut, each body of water is
labelled by an eight-connected walk that wraps in x as every other neighbour walk here does — the
sill of a flooded rift can be one cell wide and a diagonal step is a step — and any body that is not
the ocean and is no larger than the largest lake Earth has is marked land at the height it already
stands at. What it becomes is the river stage's business: the depression fill raises it to its
lowest outlet and the water balance decides whether it holds a lake (a lake below sea level is the
Caspian, the Dead Sea, the Qattara) or dries to a playa. Measured at 512: 73/78/558 pockets holding
358/430/2093 cells on seeds 7/42/1234 before, none after, and 37/41/60 river mouths ending in one
before, none after. At 2048, 925 pockets on 718106 and 305 on 59758, none after.

**Touching water is not the same as being able to drain into it.** The depression fill seeds its
priority flood from the land that touches the sea, on the assumption that such a cell has somewhere
to go. The rule above breaks that assumption: it turns sea the ocean cannot reach into land without
raising it, so a converted cell keeps a level *below* the shoreline and can stand lower than the
ocean floor beside it. Seeded as an outlet it is never filled, and the router then finds it nothing
to drain into at all — nine such cells on seed 42 at 512 and seven on 298405 at 1024, which is what
`PipelineTest`'s "every land cell drains downhill" and `StraightRunTest`'s forest check caught at
S2's fourth pass. A land cell is an outlet only where the water it touches is *lower than it*.
Every ordinary coast is unaffected, because land stands at or above the shoreline and water below
it.

**A drowned basin gets its outlet cut too.** What the rule above hands the river stage is a hollow
whose floor lies below sea level and whose rim is ordinary land, and the depression fill then raises
the hollow to that rim — which can be a great deal wider than the water that was there, because the
ground around a coastal saucer is low. Neither of the two mechanisms that size the other lakes can
reach it: the outlet notch runs inside the hydraulic rounds, while that ground is still under the
provisional sea, so there is no lip for it to cut and no outflow to cut with, and the water balance
cannot drain a floor that is already below sea level. So the notch is run again on the far side of
the cut, on the same terms — the same stream power, the same `outletIncisionRatio`, the same drop
limits in the land's own relief — with one limit lifted and one addition. The limit is the sea:
inside the rounds a river may not cut below it, because it is the base level a river grades to, but
the water behind one of these sills stands *below* the sea and the river crossing the sill is
grading to that, so here the cut may reach the waterline, and where the outflow has the power to
take it there the sill becomes water and the basin is an arm of the sea — a sound, or a ria with a
narrow mouth, which is the Bosphorus and the Black Sea behind it. The addition follows from the
same lift: once the target is below the old lake surface, the lake bed between the deep water and
the lip is part of the sill too, so the channel is cut back across it, up the inflow with the
largest catchment, exactly as an outlet incises headward across a draining floor. Where the outflow
has not the power, the sill stands lower than it did and the basin keeps whatever the balance then
allows: a lake below sea level, which is the Caspian, the Dead Sea and the Qattara. Measured at 512,
the largest such basin covers 1.13% of seed 718106's land with the pass off and 0.26% with it on,
0.61% and 0.07% on seed 99; seed 43's does not move at all, because its outflow cannot cut its sill.
Eight passes at most, the loop stopping when a pass finds nothing left to cut: 718106's takes seven
to stop retreating, seed 99's one.

**Continents stand on shelves.** After the sea-level cut, the sea floor within `shelfWidthKm` of a
coast (468.75 km, which is twenty cells at 512) is remapped onto a shallow platform at
`shelfDepthMetres` below the shoreline, falling away to the abyss beyond. The remap touches only water,
so no coastline moves: `ContinentalShelfTest` finds 100% of near-coast sea shallow against 60% with
the remap off, 0–2.5% of far sea shallow, and zero land cells changed on any seed. Island arcs
inherit the same platform, which is what makes an archipelago read as one drowned ridge.

**A valley narrower than the cell is not a bay.** The lowstand above cuts a channel down to the low
stand at every shore, and the transgression floods every one of them, so the cut used to come back
with a notch at every stream mouth. On the grid a channel is a whole cell wide whatever it carries.
Measured with a ruler — the coastline's length at one cell against two, which is Richardson's own
method and, unlike a box count, has no ceiling to run into — the coast of 2.0.2 gives a dimension of
1.582 over its finest octave against 1.260 from four cells to sixteen, and a real coast gives much
the same figure at every scale. A disc drawn on the same grid reads 1.006 against 1.000, so the
excess is the coast and not the ruler. Earth's coasts at six to twelve kilometres are indented by the
Chesapeake, the Severn and the Gironde and by nothing smaller; the Rias Baixas are two to seven
kilometres across and a 1024 map cannot hold one. So `SeaConfig.drownedValleyFill` keeps a drowned
cell as water only where the valley behind it crosses at least half the cell, by Leopold and
Maddock's square root of the catchment — the constant is 0.08 km per root square kilometre, measured
off the Chesapeake, the Delaware, the Severn, the Thames and the Gironde — and where it does not, the
cell takes the height it would have if the channel occupied the share of it that it really does.
Because the width goes as the root of the area and the bar goes as the cell, the catchment a valley
needs comes out as a fixed number of cells — about 39 — at every grid. New ground may never stand above the
ground beside it, nor fail to fall towards the sea, so a valley whose walls are no higher than the
water at its mouth is left as water. Measured on the five seeds at 512, the excess of the finest
octave over the coarsest falls from 0.322 to 0.270 with the littoral grading alone and to 0.198 with
both passes. What is left is not channels — filling *every* drowned notch reaches the same 0.199,
because the two rules above and not the width bar are what stop the fill — it is the percentile cut
running through the erosion's own texture at the cell, and it survives with the lowstand switched off
entirely (0.288 there).

**Not every coast is a ria.** The lowstand above drops the base level everywhere for nine of the
twelve rounds, so running water works every cell within about 120 m of the shoreline and the
transgression floods all of it. Measured at 512 pooled over the four standard seeds and 298405, that
took the shoreline from 43,967 cells to 60,755 and the box dimension over the finest octave from
1.22 to 1.26, on every coast alike — while the shelf remap moved the coastline not at all, the
enclosure rule by 7% and the plate detail noise by under 1%. Earth had the same fringe six thousand
years ago and has spent the time since filling it in where the coast is low: Texas, Holland and
Bengal are graded arcs of beach and marsh, while Galicia, Maine and western Norway kept the outline
the drowning gave them, and Luijendijk et al. (2018) find 31% of the ice-free shoreline sandy.
`SeaConfig.littoralGrading` runs that six thousand years, after the cut and before the shelf. It
ranks the shoreline by the height of the land within 187 km of it, takes Earth's third — a share
rather than a height, because no height derivable from Earth lands on it — scales how far the fill
reaches by the fetch in front (`H ∝ U√F`, so the square root of the open water within 500 km), and
fills the re-entrants of that third with sweeps of a three-by-three majority. It only fills: waves
take a cliff back 0.6 to 6 km in six thousand years, under a tenth of a cell at 2048, while the
Mississippi's plain advanced a hundred kilometres in the same time. It never dams a channel, so the
rias H5 cut stay open. Measured on the same five seeds at 512, with the drowned-valley
rule above running too: the share of coast reading smooth by Australia's 1.13 rises from 0.086 to
0.188 over 750 km stretches and from 0.141 to 0.224 over 375 km ones, against Earth's third; the
shoreline falls 8 to 10%, the land gains 0.2 to 0.4% of the map, and no body of land or water is
gained or lost on any seed. The world's coastline over four to sixteen cells reads 1.255 by the
coarsened ruler against 1.260 before, which is Mandelbrot's Britain; by M1's box count the same coast
reads 1.167 against 1.207, because a box is mixed by a
single cell of the other kind and so that instrument counts the teeth as well as the coast.

**Rivers put back what they take.** The hydraulic pass carries a sediment load down the same flow
network it cuts with, and lays the surplus down wherever the gradient can no longer hold it:
floodplains along lower trunks, alluvial fans at range fronts, fans at lake inflows, and deltas
where the biggest rivers meet the sea. The budget is exact rather than approximate — `DepositionTest`
measures material incised against material deposited plus material carried out to sea, over all
twelve rounds on seed 42, and finds them equal to the last float. No cell is ever raised as high as
the ground draining into it, so deposition cannot invent an uphill river.

**A river aggrades to grade, not to a flat.** Where a trunk carries more than its gradient can
hold it lays the surplus down, and the question is when it stops. The rule was "a cell may rise
until it is level with the cell that feeds it", whose fixed point is a plane: twelve rounds of
creeping a fraction of the way there turned lower valleys into flats, and a flat dams itself — the
depression fill ponds whatever hollows are left in it and the map shows water in shapes no landform
explains. On seed 718106's southern rift at 2048 that was a rounded-square pocket and two
concentric crescent moats, 563 cells of standing water lying in rings. A cell may now rise only
until the slope from its feeder reaches the slope at which the river's transport capacity equals
its load — the equilibrium slope of a transport-limited channel, solved out of the same expression
the capacity is written in, so it costs no constant of its own. Measured in that window: 563 cells
of ringed water before and none after; 2282 cells of standing water in all before and 1121 after,
against 1535 on the same ground with no deposition at all, so the spoil now takes water out of a
valley where it used to add it. The margin itself is measured and spent in one unit system, which
it was not: it was read in shoreline-relative units and spent as a height, so an alluvial dam could
stand `1 / landRange` times higher than the no-uphill rule allows — about four times on these
worlds.

**A delta is a fan, and its outline is a curve.** Every fan this stage lays — the lobe at a river
mouth, the cone at a lake inflow — is grown from its apex by a graph walk whose rim is
`R·(sides + (1 − sides)·max(cos θ, 0))·(1 + a·s(θ))`, with θ measured from the direction the river
was travelling when it arrived and `s` a wobble of four harmonics (orders two, three, five and
seven) whose phases are a splitmix hash of the seed and the mouth. The distance compared against it
is the true Euclidean distance from the apex, never the walk's own step count: over eight
neighbours a step count is the Chebyshev metric and its iso-lines are squares, which is why the
lacustrine fans used to cover the whole `2R+1` square around their inflow and leave flat rafts with
right-angle corners when the water went away, and why a lobe that did shape itself by a cosine came
out as a half-disc with its corners pulled along the diagonals. Measured at 512 on the author's two
worlds, the share of a fan's perimeter lying in a straight grid-axis run longer than one lobe reach
falls from 2.0% and 1.8% to 0.7% and 0.0%; at 2048, where the square a lacustrine fan takes is
forty-nine cells on a side rather than thirteen, the longest single straight run of new coast falls
from 34 cells to 13 on seed 718106 and from 30 to 15 on 59758. Of a lobe's rim measured about its
own apex on open water, max against min is at least 3.1 where a half-disc gives 1.06, and the two
sides of it differ by at least 10% of its mean radius where every shape a compass can draw about an
axis gives under 4%.

**A delta reaches further over a shelf than into deep water.** The cost of advancing into a cell is
one, plus one for every 130 m of water standing over it, read off the height field's own range.
That is Earth's shelf break, and Earth's shelf break is where it is because that is roughly where
the shoreline stood at the last glacial maximum — which is why it lands within a few metres of
`SeaConfig.lowstandMetres`. So a lobe spends its budget on shallow ground and progrades across a shelf the way the Nile
has, and stubs into a trench the way a delta at the head of a fjord does. On a synthetic coast with
a shelf on one side of the mouth and water thirty times deeper on the other, the lobe reaches 3.1
times further over the shelf; with the depth term off, 1.02 times. A lobe of more than four cells'
reach also carries two to five distributary grooves radiating from its apex — hashed from the
mouth, so the same delta has the same channels at every resolution — and any groove whose ray would
run into the back of the coast rather than reach the water is not cut, because a channel that ends
in a pit is not a channel.

**A river cannot end a round below its own bed.** Stream-power incision lowers a cell by what its
own discharge and its own slope allow and says nothing about what the cell below it is doing in the
same round, so two neighbours on one channel are cut by different amounts and often enough the upper
one is cut further — it carries nearly the same catchment down a steeper reach. The round then ends
with a hole in the river's bed, the next round's depression fill has to raise that hole to route
through it, and along a channel the holes line up into a rank of thin bars of standing water lying
at a grid bearing. Every landscape-evolution model since Braun and Willett (2013, the FastScape
scheme) bounds a node's new elevation below by its receiver's new elevation, and so does this one:
the incision is a pass of its own, walking the D8 tree from the outlets upstream so that a cell's
receiver is already final when the cell is cut, and refusing the part of the cut that would take it
below. Ties do not arise — each cell has one receiver and the network is a tree. The census that
justified it counted the holes each of a round's mechanisms makes, on seeds 718106, 42 and 7 at 512
over the twelve rounds: the incision made 6383, 10 and 5, the outlet notch none at all on any seed
in any round, the thermal relaxation none it did not also take away, and the spoil a few hundred
where a floodplain laid at the very end stands above the channel feeding it — an alluvial dam, which
is a real landform and is left alone. With the clamp the incision makes none, and the channel cells
the map ends up drawing as standing water fall from 1627 to 780 on seed 718106, 106 to 54 on 42 and
323 to 265 on 7. On the author's own world at 1024 the lakes go from 25 to 17 and the standing water
from 1.60% of the land to 0.49%. See `ReceiverClampTest`.

**A river crossing smooth ground does not run in a ruled line.** Eight neighbours cannot express a
slope that faces between two of them, so on ground that is a plane at the cell scale — the apron
below a range, laid by deposition and worn smooth by the thermal relaxation — the steepest of the
eight is the same neighbour at every cell and the water runs dead straight for as far as the plane
goes. That is the grid speaking rather than the ground: a real apron has relief at scales a
six-to-twenty-three-kilometre cell cannot hold, and a real river crossing one wanders. Left alone
the stream power cuts a ruled trench along such a run, the trench ponds behind its own lip, and the
map grows a lake shaped like a ruler — the "diagonal rectangle" the author found on seed 298405 at
1024, 53 cells all within 1.06 of one line and running 20.1 cells along it. The direction is
therefore read off the surface, not off the neighbour list: Tarboton's steepest triangular facet
(1997) gives the true bearing, and the single receiver every stage downstream needs is drawn across
that facet at the bearing's own share, which is Fairfield and Leymarie's Rho8 (1991). A reach four
fifths of the way toward the diagonal takes the diagonal four steps in five. Where the facet's
descent points out of the facet the answer collapses to the old steepest-neighbour one exactly,
which is what an incised channel always does — over the channel cells of five worlds the runner-up
carries 0.155 of the winner's slope on average — so the rule bites on smooth ground and almost
nowhere else. Measured on the apron the trench crossed, before erosion touched it: seven cells
running one bearing with the drop to the winning diagonal 1.9489e-02 and to the runner-up
1.5050e-02, the same two figures to four digits at every cell, which is a plane facing 83% of the
way toward the diagonal and not a tie. Census of standing water within 1.2 cells of one line and
twenty cells long: 1/0/0/0/0 on 298405 at 1024 and 7/42/1234/99 at 512 before, 0/0/0/0/0 after. A
ruled bar needs a lip to pond behind as well as a ruled course, so it is rare and the census is a
poor way to compare two routing rules; the ruled *course* is on every map, and runs of seven steps
on one bearing — 40 to 160 km of watercourse without a bend at these grids — fall from 76/28/38/22/31
to 66/21/32/15/20 over the same five worlds. Hack's exponent moves at most 0.018 against a spread of
0.032 across seeds and stays inside Earth's band; a third of the drawn river cells move, three
quarters of them by a cell or two. See `StraightRunTest` and `StraightRunAuditTest`, and
`docs/DESIGN_LEDGER.md`, F18.

**Nor does a river crossing a flat the fill raised.** The depression fill lifts every cell of a hollow to its spill and then one flat-gradient step higher per cell of flood, so that the flat still slopes back to the cell the flood came in by. That staircase is the flood's own expansion order: its level sets are the shells of the grid's chessboard distance from the entry, a cell on the entry's diagonal has exactly one lower neighbour, and the facet rule above has no slope to work against, so a course crossing a filled flat was drawn with a ruler at one of eight bearings however well the aprons were handled. On the author's 2048 world it was a 25-cell run dead north across a filled flat, 147 km without a bend. A filled flat is a sheet of water or a floodplain, and water spread over a flat sheet draining to one outlet follows a potential that satisfies Poisson's equation: rain falling on the flat is the source, the entry is the sink, the rim lets nothing through, and the level sets are smooth curves closing on the entry. The routing across each flat now reads that potential, solved per flat in double precision and laid into a band one flat-gradient step high just above the entry so the network stays a forest; the rain over the flat is varied by half with the same sub-grid field the draw reads, because a sheet fed evenly and draining to a straight edge flows in straight parallel lines, which is a ruler again. Measured on 718106 at 2048 in one run, ruled runs of seven cells or more over raised ground fell from **76 to 44** and from 9.0 to 5.2 per thousand drawn channel cells, the longest from 31 cells to 12; over unraised ground the census barely moved (181 to 166), because those cells were already routed by the facet rule. What remains over the flats is partly corridors the terrain made one cell wide, which no routing can bend, and partly open flats whose potential happens to lie within a few degrees of a bearing for a dozen cells; it is printed by `FlatCourseAuditTest` and not asserted. At 512 a flat is a few cells and both rules leave one to three runs across the four standard seeds, which is why F30 could not find the defect on the per-merge grid. The potential costs about a tenth of a second a routing pass at 2048, under one percent of a generation over the fifteen passes a world makes, so it has no device path.

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

**And the outflow's gradient is measured to the water it empties into.** The walk that measures an
outlet channel's fall stops on the last cell of land, one step short of the water below it. Where
the sill runs level all the way to that water — which is precisely the case the post-cut outlet pass
exists for, a basin below the shoreline behind a bar at the waterline — the whole of the fall is in
the step the walk did not take, and what is read instead is the 1e-6 the depression fill nudges a
flat by: not a small gradient but the absence of one, so no stream power, so a sill that stands for
the life of the world however large the catchment behind it. Seed 99 at 512 kept a 668-cell basin
that way, 2.64 times the Caspian's share of its land, its outflow's measured fall 1.0e-6 against the
2.5e-2 it actually descends and unmoved over every pass it was given; seed 718106 kept one at 1.65
times, and seed 42 had a hydraulic round in which the notch cut nothing anywhere. The step into the
water now counts, and only where the walk found no fall the fill did not put there — one epsilon a
step is the flood's own staircase on a flat — so an outlet that measured a real gradient keeps the
answer it had. Re-rating every sill instead hands each coastal one the whole fall to sea level at
once and empties basins that ought to hold their water: measured, that took seed 718106 from 44
lakes to 12. Largest drowned basin, as a share of land: 0.361% to 0.083% on 718106 and 0.658% to
0.122% on 99, against the Caspian's 0.249%. Every lake this drains is one below the sea-level cut —
classified over six seeds, the ice's own lakes are untouched to the last one (718106 7 and 7, seed 7
16 and 16, seed 99 9 and 9) and so are the lakes above the cut that the ice did not make, because
glaciation runs after this pass and a glacially over-deepened basin has no outlet to cut. With the
sill cutting, the retreat of the largest drowned basin takes ten passes rather than eight to stop:
1849, 1196, 988, 839, 727, 605, 495, 391, 285, 157, then 138 and flat. That was measured against a
lowstand read as a share of each world's land relief; the ceiling the pass actually runs to is the
sixteen the metre-deep stand needs, and a pass that cuts more can only shorten the retreat under it.
See `OutletIncisionTest`.

**A glacier is where the snow outlasts the year, not where it is cold.** Ice used to be simply
"the mean annual temperature is at or below freezing", which made an ice sheet of every cold
interior — 41.9% of seed 7's land, against the 10.1% of Earth's that carries glacier ice, nearly
all of it in two places. `SnowBalance` weighs the two things that actually decide it, out of the
four seasonal fields the climate stage already computes: accumulation, the share of each half
year's precipitation that falls with that half year below freezing, and ablation, a positive
degree-day melt at 4.5 mm water equivalent per degree-day (the middle of the published 3-5 for
snow) with the seasonal means turned into degree-days by Calov and Greve's closed form for a
normal spread of daily temperature about a mean. Ice is where the year ends in surplus. Siberia is
colder than the Norwegian coast in every month and has no ice sheet because nothing falls on it,
and that distinction is now available to the map: pooled over seeds 7, 42, 1234 and 99 the ice
share of land falls from 28.8% to 9.2%, and at one summer temperature the wettest quarter of the
land carries ice on every seed while the driest quarter carries none.

**A sheet's surface is the lowest profile that reaches it, and a balance of nothing is not ice.**
The plastic profile solves for a surface measured from the ice's own margin, and a margin is a line
of ground at many different heights. Measured from the *nearest* margin cell, the datum is a
piecewise constant field whose regions are Voronoi cells, so the surface steps wherever two
neighbouring cells look up different margins — on seed 878210 at 1024 by as much as 1,965 m between
one cell and the next, and by 40.9 m on the average east-west pair, which is the dome's own fall
across the same cell. The surface that satisfies the yield condition against a margin of varying
height is instead the lower envelope of the profiles rising from *every* margin point,
`S(x) = min over m of (z_m + k * sqrt(d(x, m)))`, which is that equation's viscosity solution:
differentiate one branch and `|grad S| = k^2 / 2H` falls out, and where two branches meet the
surface creases rather than steps, which is an ice divide. Beside it, the mask the profile is drawn
over: ice is where the year's snow outlasts the year's melt *by an amount that means something*,
because over a polar desert nothing falls and nothing melts, both sides of the subtraction cancel,
and the sign of what is left is float rounding rather than weather. Nearly a quarter of one world's
frozen ground was standing on a balance under a hundredth of a millimetre a year, and what a
plastic profile makes of a mask speckled that way is a comb of ice walls a cell wide and four
hundred metres tall. Both together are what drew the ruled flank I3 was reported for. See
`IceSheet.marginDistanceKm`, `SnowBalance.isGlaciated` and `IceSheetTest`.

Both take ice off the map, and the ice share of land — a standing finding at half Earth's since W2
— moved *further* from Earth's 10.1% on that account: pooled over seeds 7, 42, 1234 and 99 it
reads 4.86% where it read 5.26% before, against a control that fell from 33.45% to 32.68%. Of the
0.40 points, 0.32 is the datum and 0.08 the floor, measured by setting the floor to zero and
generating the four worlds again. Neither is ice the world was entitled to: a surface standing on
a stepped datum cools the ground under it by the lapse rate and so freezes more of it, and a
balance of a ten-thousandth of a millimetre a year is a rounding rather than a snowfall, so what
left was ice the model of the ice was manufacturing for itself. The gap to Earth remains what it
was — a continent whose interior takes a third of Earth's rainfall has polar deserts with no snow
to freeze, which is the moisture budget's open finding and not the cryosphere's.

**Cold country is lake country — and the cold that made it is not today's.** Where the ice is, it
takes over the valleys the water cut: a flat-floored U-shaped trough across the flow instead of a
V, a cirque bitten out of every head, a staircase of over-deepened basins whose spacing is set by
descent rather than distance, a recessional moraine barring the valley at the lower end of each
reach and a terminal moraine at the snout. But the ground that shows those landforms on Earth —
Finland, the Canadian Shield, the Lake District, the Finger Lakes — carries no glacier now and has
not for ten thousand years. So the mask the carving works from is the snow balance of a *colder*
world, `GlaciationConfig.glacialMaximumC`: the last glacial maximum's 6.1 C of global mean cooling
(Tierney et al. 2020), applied since W1 as a dimmer sun that the energy balance answers with a
colder world of its own — so the tropics cool 1.5-3 C and the high latitudes far more because the
poles turn white, rather than because a latitude ramp said so. That puts 26% of seed 42's
land under ice at the maximum against Earth's roughly 25%, while the map still draws today's 4%.
The signature is the standing water the ice leaves behind, because a river network cannot leave a
hollow in its own bed and ice does nothing else: on seed 42 at 1024 `GlaciationTest` measures 9
glacial lakes in 130,000 cells of ice, tundra and taiga against 2 with the ice switched off, in
country whose temperate half holds none at all.

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

**A closed basin keeps its rain.** The catchment rainfall the balance reads is accumulated once, over
the routing as the fill left it, when every basin still spills into the next; so a lake below a
closed basin was fed the rain its neighbour upstream had already evaporated. Since chunk 6 the
basins are solved upstream first — in a topological order of the basins' own graph, each basin
pointing at the basin its exits' water reaches next on the routing the fill left — and the moment
the balance closes one its whole catchment is taken out of every cell below its old spill, along
that same routing, before anything further down is solved. Ordering by where a basin's last exit
falls in the drainage is not enough once a basin has two exits, which is common: its early exit can
feed a lower basin that its late one comes after. Two basins can even feed each other, each by a
different exit; such a group has no upstream member, and is solved together to a fixed point, each
basin on the rain that reaches it once the others have closed. None of the standard worlds has one,
though most of their basins have more than one exit. A basin's inflow is also the rain leaving it at *every* cell where
its water leaves, not the largest of them: a rim level for several cells lets a filled flat drain
across more than one, and the largest alone under-fed it. `WaterReceivedTest` builds two chained
basins with a desert playa above a steppe basin and holds the lower one's inflow to the rain that
reaches it; without the subtraction it is over by exactly the playa's rain. A second case, drawn by
hand, gives a closed basin two exits and a path that leaves it and comes back: its inflow counts
that path once, and the basin its early exit feeds is given 450 mm-cells where the exit-rank order
gave it 700. Over seeds 7/42/1234/99
at 512 and 969495 at 2048 the lakes went from 19/24/4/16/32 to 19/24/4/17/32, the closed ones from
6/2/1/4/5 to 5/1/0/5/2, the playa cells from 8/34/10/23/902 to 11/34/15/7/1,133 and the share of land
under lakes from 1.028/1.887/0.129/1.966/2.681% to 1.010/1.893/0.148/1.994/2.727%.

**The ground carries a cover, and it is a field rather than a name.** Koppen's classifier gives a
cell one of sixteen names, and a name is a step: two cells either side of the 500 mm steppe line
are painted as different countries although their vegetation differs by a few per cent. Beside it
the world now carries a vegetation density, 0 on bare rock to 1 under a closed canopy, built from
two published relations and no fitted curve. The water half is Budyko's (1974) evaporative
fraction, the share of the year's available evaporative energy that the rainfall actually meets,
evaluated at Holdridge's own PET ratio; Eagleson's (1982) ecohydrological equilibrium is why a
plant cover tracks it, since a canopy grows until it is transpiring everything the climate will
give it and no further. The season half is Holdridge's (1967) biotemperature, the year's twelve
monthly means with everything below freezing and above 30 C thrown away, ramped from nothing at a
biotemperature of zero to all of it at his subpolar-to-boreal line of 3 C. The product is the
density. Checked against Whittaker's (1975) diagram at the centre of nine of his boxes, it puts
every forest above three fifths, every open woodland and grassland between a third and three
fifths, tundra at 0.21 and a subtropical desert at 0.07. Pooled over seeds 7/42/1234/99 at 512,
31.1% of land reads under a tenth — bare, sparsely vegetated or under ice — against Earth's 26%
from the MODIS IGBP census's barren and snow-and-ice classes, and 23.4% of ice-free land reads at
or above a closed canopy against FAO's 31% forest share. The drawn map reads the field for its
canopy darkening, so a woodland edge is now graded over tens of cells: across the open-to-wooded
boundaries of the gallery world the mean step in canopy falls from 0.68, which was the per-biome
table's, to 0.044.

**Frozen ground, in two zones rather than one.** Where the mean annual air temperature is at or
below -8 C the permafrost is continuous and where it is at or below -2 C it is discontinuous or
sporadic, the cold end of the bands Brown and others (1997) and Zhang and others (1999) map. Only
the continuous zone caps the cover, at two fifths: its active layer is a few tens of centimetres to
about a metre deep and a tree cannot root in that, which is why the continuous zone is tundra and
dwarf scrub however long its summer runs. The discontinuous zone caps nothing, deliberately — the
Siberian larch forests stand on discontinuous permafrost over most of their range, and a mask that
stopped forest wherever any permafrost was found would delete them.

**Realms of uneven size, and no world empire.** Realms are built from drainage catchments, so a
border falls on a watershed or on a trunk river because there is nowhere else for it to fall. How
much each realm takes is a draw with a long tail, which is what stops a world reading as a dozen
equal slabs. But appetite is a *comparative* brake — a realm bids against its neighbours — so a
realm that is the only bidder for a region takes it whatever its appetite: on seed 7 one realm
ended up holding 42% of the world that way. Two fixes were measured. Forcing the seeds two rings
apart instead of one worked, and cost the world its river borders: it halved how often a border
follows a river on two of four seeds, because river valleys are the richest ground and spacing the
seeds out of them leaves both banks to a single realm. So the spacing stayed at one ring and the
sprawl is cut where it happens instead — a realm over `NationsConfig.maxRealmShare` is split in two
along one of its own internal watersheds, largest first, until none is over. That split costs
nothing to place, because the divide is already there, and it is the same mechanism as a voluntary
schism. (Recorded here by C2, which found both figures in a code comment and nowhere else.)

Held since chunk 6. The cap was enforced on the catchments and the enclave pass then gave a realm the
stranded pieces of its neighbours: seed 7's largest realm held 24.2% of the land when the cap had
run and 33.6% on the finished map. A piece is now given only to a neighbour it leaves within the cap,
and the largest realm on seeds 7/42/1234/99 at 512 and 969495 at 2048 holds 21.4/26.6/19.9/24.8/21.0%
(33.6/29.2/24.7/23.1/29.9% before). `RealmSpreadTest` asserts it. Where no neighbour can take a
piece within the cap, the piece is not left behind as an exclave of a realm it no longer touches:
a smaller one than the smallest realm goes to the neighbour holding most of its edge while that
neighbour's whole excess over the cap stays under the smallest realm, and any other piece becomes a
realm of its own with its capital on its best ground. No realm ends a smallest realm or more over
the cap. A schism's breakaway also takes any run of the rest it
would cut off from the rest's main body. Counted over seeds 7/42/1234/99 at 512, no piece of any
realm is stranded inside another's land (none on origin/main either), and one realm is landlocked
inside a single neighbour against origin/main's three; realms on a coast with one land neighbour,
Portugal's case, are 17 against origin/main's 15.

**The pieces are catchments, and each is one piece of ground.** A realm's borders are only as good
as the units it is built from, and chunk 6 found four faults in them. They were labelled in order of
the filled surface, which since the flats and closed basins are routed along the true ground is not
the order the water takes, so a cell whose receiver was not yet labelled opened a unit of its own; a
closed lake opened a unit per cell of its water; the size bound compared rain-weighted flow with a
count of cells, so on the standard seeds the largest unit ran 3.0 to 4.2 times the configured share; and
the merge of small units could take one across a strait and join two landmasses. Units are now
labelled receivers first, down the drainage order reversed; each closed lake and each playa is one
sink, whose water is never cut and whose land is cut at the bound like any other tributary's; the
bound is an area on the ground, cut at confluences, which no merge may pass; and a small
unit merges only into a neighbour on its own landmass, the one it shares the longest border with. On
seeds 7/42/1234 at 512 the largest realm unit went from 4.2/3.8/3.0 times the share to within it, and
on seed 7 the merge had left 14 units spanning two landmasses and now leaves none. `CatchmentUnitsTest`
guards each rule on a hand-made world. On 718106 at 2048 the realm borders' straight runs along a
row or column of 50 km or more went from 10 to 12 and the longest from 123 km to 76, and the one of
100 km or more went: the realms are laid differently, so the borders fall on different ground, and
none of the runs reaches the guard's bar. The peoples' borders, whose longest runs lie at the same
cells before and after, follow the ice and biome edges the partition does not draw.

## The units these rules are stated in

Every physical figure below is in metres, kilometres, square kilometres or years, and all of them
come from one place: `WorldScale`, on `WorldGenConfig`. There is no second ruler, and until S1
there were two — one unit of land elevation was 6,000 m in the climate, which reads it for the
lapse rate, and about 8,000 m in the sea-level and erosion constants, which derived their defaults
from "roughly 8 km of relief". The sea had no depth at all: below the shoreline the elevation field
was normalised against whatever the deepest cell happened to be, so nothing in the pipeline said
how deep an ocean is.

The world is **12,000 km wide** and half that tall, being an equirectangular projection of a whole
planet. Its highest land stands **6,000 m** above the waterline and its deepest floor **10,000 m**
below it. One hydraulic round stands for about **336,000 years**, so the twelve of them are four
million — the right order for a mountain belt to reach a steady state between uplift and erosion.
The slope a river cuts with is read on the shoreline-relative field, 6,000 m a unit, and the cut is
spent on the height field, 16,000 m a unit, so `highestLandMetres / reliefSpanMetres` belongs in the
stream-power coefficient. From S2's second pass until [Fix 3](DESIGN_LEDGER.md) it was taken to
cancel, and the round was labelled 126,000 years with the cut unchanged, which put every figure per
year on the clock, the denudation compared with Earth's and the uplift the belts are fed, 2.67 times
too high.

Both ends of the vertical range are **cell means, not points**, and that is the thing to hold on to
when a figure below looks too large. A cell of the default 512 grid is 23 km by 12 km. No cell that
size holds Everest's 8,849 m: the highest ground a cell this coarse can carry is a plateau, and
Tibet's interior averages 5,023 m (Fielding et al. 1994), so 6,000 m is where a 23 km cell tops
out. A trench survives the same averaging far better, because a trench is a line where a peak is a
point — the Mariana axis holds below 10 km for hundreds of kilometres — which is why the two
figures are not the same.

The ruler has three parts, and which one a figure takes is decided by where the figure is spent.
A height above the water is the land's 6,000 m; a depth below it is the sea's 10,000 m; and a
*level in the height field* — the shoreline itself, or a depth measured down from it into the raw
elevation the erosion stages work on — is the whole 16,000 m. That third one matters more than it
sounds: before S1 those quantities were written as shares of "the land's relief above the
shoreline", which is a measured number and is a quarter of the height field on one seed and three
fifths on another, so the same setting was a different figure on every world.

Writing the units down made four things visible that were invisible while they were fractions:

- **The last glacial lowstand was 45 m on one world and 141 m on another.** `SeaConfig.lowstand`
  said 120 m in its own documentation and was written as 0.015 of the land's relief, which is not
  a fixed quantity. It is 120 m of the height field now, on every world, and the world moves with
  it: on the seeds where the stand deepens the sea comes back over a broader shelf and the basins
  it leaves behind are larger, which on seed 718106 takes the largest drowned basin from 0.9 to 1.4
  times the Caspian's share of the land. That is the deviation this document already records under
  "an inland sea is left as sea", now acting on a wider population.

- **A glacial trough here is 150 km wide.** The stage's half-width is six and a half cells, which
  in kilometres is fifty times a real trough's two to five. It cannot be otherwise at 23 km to a
  cell, and what the stage carves is better read as a glaciated *province* the shape of a valley
  than as a glacier. Every other figure in that section is the province's rather than the ice's:
  a 375 km reach between basins, a 70 km cirque, a 190 km run-out past the freezing line.
- ~~**The continental shelf stands at 1,000 m.**~~ **Closed by S2's second pass.** Against a sea
  10 km deep the shelf plateau's outer edge stood a tenth of the way down, where Earth's shelf break
  is at 130 m, and S1's reason was that the two-density crust which makes Earth's shelf a shelf was
  not modelled. S2 modelled it — a continental margin is a band of crust thinned on its way out to
  the ocean floor, and the ground it makes shelves rather than dropping — so the sediment wedge laid
  over that margin no longer has to stand in for it, and its break is Earth's own 130 m over Earth's
  own mean shelf width of 75 km. It fills rather than replaces, too, so a bank the crust put inside
  the band shows through instead of being planed into a concentric distance band. What the old
  figures were doing was covering the whole ocean: a 20-cell plateau and a 20-cell slope around
  every coast of a world whose coastline runs seven thousand cells put 44% of the water shallower
  than 1,650 m against Earth's 15%, and smeared the hypsometric trough shut.
- **A knickpoint was cutting nine tenths as hard as an ordinary reach, not three times.**
  `ErosionConfig.outletIncisionRatio` was three, but the two rates were written in different units:
  the outlet's in the land's relief and the ordinary incision's on the height field. Converted to
  one ruler the multiplier is 1.125, and against the field where the ordinary cut is actually spent
  it is about 0.9. The number did not say what it appeared to say.

Two more figures are the Caspian's and Superior's *shares of Earth* carried onto a world a seventh
of Earth's size, which makes them a seventh of the lakes they are named for: the enclosure rule's
cap is 52,600 km² where the Caspian is 371,000, and the ice's largest basin is 11,520 km² where
Superior is 82,100. Which of the two a world this size should use is a real question and is written
up in `TODO.md`; changing it would move coastlines that nothing else in S1 touches.

~~One limit is worth stating plainly. The three parts of the ruler are consistent only if the
shoreline sits where `WorldScale` implies it does.~~ **Closed by S2.** The three parts of the ruler
agree, because the height field is now an absolute altitude rather than a normalisation: the plate
stage builds it out of the levels the two crusts float at, so a cell's value converts to metres and
back exactly and the waterline stands at `deepestOceanMetres / reliefSpanMetres`, 0.625 of the
field. What is left is a residual, and it is the coarseness of the crust the aim can draw with
rather than a disagreement about the ruler: the ocean-coverage slider is met by choosing whole
plates, so the finest adjustment available is a fourteenth of the surface. Measured on the standard
seeds at 512 the cut lands +464, +216, +191 and -603 m from the isostatic datum, against +1,354,
+1,062, +1,013 and +1,187 before the aim existed. `UnitsTest` holds that inside 1,000 m and
`IsostasyTest` shows it opening up again when the crust is drawn to Earth's own submerged share
instead of this generator's.

The same closure fixes the shelf. Its plateau is still 1,000 m at the break against Earth's 130,
which is the deviation above, but the ocean it stands over is now a real ocean — the sea's
hypsometric mode is at -3,200 to -4,200 m where before S2 it was at -390 — so the shelf is a
margin on a deep sea rather than a step on a shallow one. Bringing the break itself to Earth's
figure is a separate question and is in `TODO.md`.

## Known deviations

**The incision is the stream-power law's, spent by a first-order implicit update, and its channel
network grows denser on a finer grid.** The incision is `E = K A^0.5 S` over a round, spent by
Braun and Willett's implicit update ([Fix 3b](DESIGN_LEDGER.md)): each cell keeps `1 / (1 + F)` of
its height over its receiver's new one, where `F` is the round's Courant number, about
`0.24 sqrt(cells of catchment)` at every grid, so two where a drawn river starts and tens on a
trunk. Until Fix 3b an explicit update capped at half the drop set 100% of the drawn network's cuts.
Now the law does: at balance under uniform uplift the update gives the law's own long profile
(concavity 0.5000, steepness `U / (K e)`, `U / (K sqrt(P))` across rain zones, on synthetic
landscapes), and the generated worlds' traced rivers read a whole-profile concavity of median 0.60
(quartiles 0.39 to 0.83) and, over homogeneous reaches, median 0.42 (quartiles 0.22 to 0.66, 31% of
them in Earth's 0.4 to 0.7; Whipple and others 2013); their steepness stands 1.6 to 1.9 times higher
under active uplift than off it. Its steepness follows the rock's erodibility and the rain in the
law's sense on some worlds and not on others, because four million years of rounds is not a
steady state. Stability is not accuracy. A large-`F` cell loses nearly its whole drop in a round and
the scheme smears a knickpoint over a few cells, and the network the channel-head criterion picks
out grows 1.38 to 1.48 times denser from 512 to 1024 on the four standard seeds (1.15 to 1.23 under
the cap; `ScaleFreeTest` records it). Whether rounds of less time bring the grids together has not
been tried. The coast's box-counting dimension is 1.08 pooled, still under Mandelbrot's 1.25 +/- 0.15
though up from the cap's 1.03, and a coast projects 1.12 times as far east-west as north-south
though the incision's own notches are now the same depth by bearing.

**Half the land is tundra.** Over seeds 7/42/1234/99 at 512, tundra takes 40–58% of the ice-free
land, pooled 47%, against Earth's 6% (Olson et al. 2001). The boreal forest beside it is 6.7%
against Earth's 11%, which is the right order, and the zonal temperatures these worlds are built on
sit within a degree or two of the reanalysis at every latitude from the equator to 70° — so the
belts are where they should be and the tree line is not. What puts them there is the ground: this
map's land stands 1200–1700 m above its own sea against Earth's 840, and a lapse rate of 6 °C/km
takes three to five degrees off nearly every land cell. `ColdBiomeShareTest` prints both shares per
seed and pooled and asserts only the boreal one; the hypsometry is `TODO.md`'s.

**Twice Earth's share of the land is over permafrost, for the reason half of it is tundra.**
Pooled over seeds 7/42/1234/99 at 512 the permafrost zones cover 35.7% of ice-free land against
Earth's 17% — Zhang and others' (1999) 22.79 million km2 of Northern Hemisphere permafrost against
a global ice-free land area of about 134 million — and by seed it runs 7.7, 35.6, 41.3 and 59.9%.
This is the tundra deviation above, arriving at a second measurement: the mask is a threshold on
the annual mean, this map's land stands 1200-1700 m above its own sea against Earth's 840, and a
lapse rate of 6.5 C/km takes three to five degrees off nearly every land cell. The bar was not
moved to admit it — the guard is a factor of three either way, which 2.10 is inside — and taking
the *cold* end of each published band (-8 and -2 rather than -6 and -1) is the one thing the
permafrost model itself does about it. The hypsometry is `TODO.md`'s, as it is for the tundra.

**Nothing on the map reaches a vegetation density of 1.** Budyko's curve approaches its energy
limit rather than meeting it, so a closed canopy reads 0.87 to 0.93 and only a superhumid climate
with an order of magnitude more rain than evaporative demand would read higher. The field is a 0-1
field by construction and correctly never saturates; what this costs is that the drawn canopy
darkening never reaches its own full twelfth. Recorded rather than rescaled, because a normalising
factor chosen to make the wettest cell read 1 is a per-world fit and is the thing A4 took out of
the rainfall.

**The moisture march's ground return is still a proxy, and W4 measured the derivation that would
have replaced it.** `MoistureBudget.groundWetness` scales the ground's return by the previous lap's
rain against Koppen's 500 mm steppe line, which W3 stated as a proxy for the vegetation field. The
field exists now and Budyko's evaporative fraction ought to *be* that return rather than stand in
for it — but measured, it puts the continental recycling ratio at 26.5% pooled against the proxy's
32.7% and van der Ent and others' (2010) 30-45%, so the derivation falls outside Earth's band and
the proxy does not. The proxy ships. The two differ because Budyko's fraction is a share of a
*year's* evaporative energy and the march wants a share of a *parcel's* passage; over the dry and
cold ground that covers most of these worlds the annual figure is the smaller of the two. It is
kept as `ClimateConfig.vegetationRecycling` so the figures are re-measured on every audited seed.

**An inland sea is left as sea.** The enclosure rule above stops at the largest lake Earth has,
0.073% of the surface: a body of unreachable water larger than that is a piece of the sea walled off
by a sliver of ground, and calling it land invents a landform Earth has no example of — it would
also turn a flooded rift's gulfs into lakes and take `RiftSegmentationTest`'s chain apart. So one to
five such inland seas survive on each of the standard seeds, holding 767 to 8386 cells at 512, and
they are drawn as ocean rather than as the Caspians they might be. The bodies below the cap are
converted, and since H5b their outlets are cut like everyone else's, so a converted saucer no longer
floods far wider than itself: `OutletIncisionTest` and `OutletResolutionTest` still measure the
drowned basins apart from the ones the in-round notch owns. H5b held both to the same bar; W1 put
the drowned half back over it and it is a reported figure again, because the glacial mask is now
struck on a colder world's own rainfall and the ice carved somewhere slightly different, so seed
42's largest walled-off hollow at 2048 went from 0.86 times the Caspian's share of its land to 1.23.
The largest lake standing *in the land* is still held to the bar and is well inside it. The cap is
an area in square kilometres now rather than a share of the map, which was
S1's business, and writing it down is what made the question underneath it visible: 52,600 km² is
0.073% — the Caspian's share of *Earth's* surface — carried onto a world a seventh of Earth's size,
so it is a seventh of the Caspian. Whether a world this size should cap at the share or at the lake
is not a units question and is in `TODO.md`.

**Where a rift meets the coast, half its floor is dry.** A half-graben's floor is a wedge — deepest
against the master fault and rising to about a fifth of that depth against the hinge — and where the
rift reaches the sea the water in it stands at the waterline, because the post-cut outlet cuts the
sill down to sea level and stops there. So the hinge shelf is dry ground, and on seed 718106 at 2048
the author's coastal trough keeps 29% of its flat floor under water and shows the other 71% as a
lacustrine plain with the river's fan across it. The Gulf of California and the Red Sea are drowned
along their whole width, because a rift that has opened that far has thinned its crust under the
whole trough. Two repairs were measured at E7 and both refused. Deepening the stamp: at `riftDepth`
0.35 against E4's 0.25 the trough's floor does reach 43% under water, but the rift stops being a
chain of basins and becomes one continuous deep axis that drains along itself, and seed 718106's
standing water falls from 17,412 cells to 8,200 with its deepest rift lake going from 24.2% of the
land's relief to 2.4% — a worse world for a better number. Giving the floor a hashed chain of
sub-basins: it works on the scene, taking the trough from 29% to 38% under water and leaving the
world's standing water and its hypsometry where they were, but at every amplitude tried, from 12% to
45% of the segment's depth, it leaves a closed basin below the sea-level cut that the post-cut
outlet cannot open — seeds 718106 and 99 came out holding a drowned basin of 0.46–0.54% of their
land, about twice the Caspian's share of Earth's, over `OutletIncisionTest`'s bar. A rift lake
larger than the largest lake Earth has is not the improvement that was wanted. A third was measured
at E8 and does not apply: letting the sea in through a sill at the waterline cannot help here,
because there is no sill — 1635 of the trough's 2158 wet cells are already ocean, the post-cut
outlet having cut through — and because the sea can flood only what lies below itself, and just
2215 of the 7397 floor cells do. What the dry 71% would need is water standing at 0.095 of the
land's relief, some 760 m above the sea, which is a lake perched behind a dam and is the world
this generator held before H5b. The repair all three attempts are standing in for is subsidence
that scales with how far the rift has opened, which is a process rather than a stamp (S2 in
`REALISM_AUDIT.md`).

**A subduction margin draws a coast of constant width.** Every belt on this map varies along its own
length except the trench, whose depth is `trenchDepth * strength * narrow` — a function of the
distance to the boundary and of nothing else, which makes it a plane along strike and every contour
of it a straight line, including the one the sea is cut at. That is the pale bench of constant width
running dead straight down the seaward side of seed 718106's southern valley at 2048, and the
measurement that found it also settled what that valley is: an Andean margin, with not one of its
38,750 cells on a continental rift, where E6 had read it as E4's half-graben. Modulating the trench
along strike with the same swell every other belt uses was written, measured and reverted — at that
wavelength (about 160 cells at 2048, against a bench 30 cells long) it slides the coast onto a
different straight contour instead of bending it, and the window went from 108 cells of thin
grid-bearing water and a 31-cell straight run to 146 and 39. The fix wants a shorter wavelength on
the trench, or dissection of the coastal plain that a nearly flat plain does not currently get.

**There is two and a half times as much cold desert as Earth carries.** Measured as a share of the
land poleward of 45 degrees and divided by each world's own desert share of all its land, seeds
7/42/1234/99 read 0.25, 0.55, 0.14 and 0.34 against Earth's 0.12, pooling to 0.31 — over the factor
of three this audit holds a seed to and the factor of two it holds the pool to. The tropics, which
are the defect the desert guard was written for, are at exactly zero on every seed. What the
poleward figure is, on the evidence, is the interiors drying: the enclosure rule took several
thousand cells of *inland evaporation* out of the moisture march — hollows below the percentile cut
that no ocean could reach, which the march had been drinking from as though they were open water —
and every one of them that now holds a lake is water the march still does not see, because lakes are
decided two stages after the climate. The repair is a provisional lake mask before the march, the
way the ice already gets a provisional climate before it (W3 in `REALISM_AUDIT.md`, and "Lakes never
feed the moisture march" in TODO.md). Measured, printed with Earth's figure beside it and left
un-asserted until then, rather than given a bar wide enough to pass.

**A basin can be left standing at the waterline behind a sill at the waterline.** The outlet pass
cuts a converted basin's sill by what the basin's own outflow can take off it and stops when it
reaches the shoreline, which is right — a lake whose surface is at sea level has no fall left to cut
with. What it leaves is a hollow with its brim a few metres above the waterline behind ground of the
same height, and on Earth that is not a barrier: a spring tide is two to four metres on an open
coast, a severe cyclone surge eight to nine (Katrina 8.5 m, Bhola about nine, the record 13.7 at
Bathurst Bay in 1899), and the sea has stood where it stands for six thousand years. The Bosporus
sill let the Mediterranean into the Black Sea. Measured at 512 on seeds 7/42/1234/99, 10/5/23/22
such basins survive the cut, over 15/12/189/47 cells; `WaterlineBasinTest` counts them and separates
them from the ones behind a sill a surge cannot climb, which are the Caspian's and the Qattara's
case and right to keep. The rule that would take them — cut the exit to a surge below the waterline,
by the sea rather than by any river, and let the labelling find the basin connected — was built at
E8 and reverted: it cannot reach the scene it was built for (see the rift deviation below), and it
broke three guards with no Earth figure behind them to buy 1517 cells of 4.19 million on the
author's world at 2048. The figures are in that test and in E8's ledger row.

**A graded coast is smoothed rather than built.** The littoral pass above fills a re-entrant; it
does not throw a barrier across the mouth of one and leave a lagoon behind it, which is what Earth's
depositional coasts actually look like — Padre Island and the Laguna Madre, the Frisian chain and
the Wadden Sea, the Curonian Spit. Two consequences are measured. The share of coast reading smooth
by Australia's 1.13 reaches 0.123 over 750 km stretches and 0.200 over 375 km ones, against Earth's
third: a graded shore that is a plain arc has less of a stretch to itself than one with a lagoon
system on it. And the world's pooled box dimension falls from 1.207 to 1.176, because a filled bay
is one shoreline where a barred one is two. The barrier islands, the spits and the tidal inlets are
the audit's K1, and this is the deviation that chunk closes.

**A flat coast on hard rock is graded like a coastal plain.** What separates Earth's graded coasts
from its ragged ones is not the height of the land behind them alone: Finland, the Canadian Shield
and western Scotland are all flat, all ragged, and all rock. This generator has no lithology (the
plan's H3), so the littoral criterion ranks coasts by the height of the land within 187 km and takes
Earth's third of them. Measured against a fixed height instead, the postglacial rise calls 59% of
the shoreline depositional and a coastal plain's own gradient calls 1.9% of it depositional; Earth's
31% sits between and no height derivable from Earth lands on it.

**A rain shadow is now cut as a rain shadow, at about half the strength the rainfall alone would
suggest.** This used to read that erosion could not see the weather at all: the hydraulic rounds ran
three stages before the climate and worked to flat rain, so a leeward slope wore down exactly as
fast as the windward one above it and the dissection of a range followed nothing but the geometry of
its divides. [S3 Erosion reads the climate](DESIGN_LEDGER.md) closed the circle the way H2 closed
glaciation's: a provisional climate march runs on the weathered uplift before the first round and
again at the midpoint, and the flow accumulation carries each cell's own rainfall, so discharge is
`Q = P * A` and the stream-power law is spent against the water rather than against the catchment.
**The forcing itself is the law's.** Measured on the largest convergent belt of each of the four
standard seeds at 512, on the first round — the one round where the two runs still share a terrain,
a set of receivers and a set of gradients, so the only thing that differs is the weather. The
windward flank takes 2.33 to 4.77 times the leeward's rain, and turning the feed on multiplies its
share of that round's incision by **1.57, 1.65, 1.26 and 2.00**, against the **1.49, 1.47, 1.22 and
1.75** that `sqrt(P_windward / P_leeward)` at m = 0.5 asks for with a fifth off. So the rain does
what the stream-power law says it should, and `ClimateFedErosionTest` asserts it there.

**What twelve rounds then leave is a good deal less, and that is a landscape and not a law.** The
relief lost on the windward flank over the leeward comes to 1.11 to 1.64 times, against 0.88 to 1.28
for the same belts with the feed off — tens of per cent where Earth's comparison, the Southern Alps
of New Zealand, has the western flank taking about ten times the eastern's rain and exhuming at 5-10
mm a year against the east's under one (Willett 1999; Hovius, Stark and Allen 1997). Three reasons,
none of them the law's. The interior is drier than Earth's by nearly three, recorded below, so the
rainfall contrast a range can make is smaller to begin with. A catchment does not stop at a divide,
so a windward channel is fed partly from over the crest and a leeward one gathers the crest's wet
side on its way down, and the *discharge* ratio between the flanks is far gentler than the rainfall
ratio. And the shadow fades as the range wears down: over the twelve rounds the flanks' own weight
ratio falls from 3.24 to 2.29 on seed 7, 3.17 to 2.49 on 42, 2.36 to 1.54 on 1234 and 3.99 to 3.41
on 99, because a lower range wrings less out of the wind crossing it. The vegetation the wet flank
grows is a fourth term and it pulls the same way, holding some of that flank's erodibility back —
which is a real effect and not an error. `ErosionConfig.climateFeed` is the control.

**What the scheme is and is not, in full.** Both terms are *relative*: the rainfall weight is
divided by its own mean over the land each pass routes on, and so is the cover's erodibility factor.
That is deliberate and it is what keeps the stage calibrated — `bedrockErodibilityPerYear` carries
figures measured on real bedrock rivers running through real forests, so an absolute cover
multiplier would count the vegetation twice, and an absolute rainfall weight would spend the
stream-power law against a differently scaled discharge than it was fitted to. The price is a list
of things this stage does not answer, and they are worth having in one place.

- **It says where the rain falls, not how much.** A world made uniformly wetter or drier erodes
  exactly as it did: the division cancels it. Total denudation is set by the coefficient and the
  time step, as it was before, and nothing here re-fits either.
- **Rainfall stands in for runoff.** What reaches a channel is rainfall less what the plants breathe
  out and the ground takes in, delivered in floods rather than evenly. None of the three is
  modelled, and the floor of 60 mm a year under every cell — `Runoff.FLOOR_MM`, the same figure the
  channel criterion reads — is a stand-in for the last of them.
- **The normalisation is global, so distant cover changes local erodibility.** A cell's factor is
  its own `1 - 0.5 x density` over the *land's* mean, so afforesting one continent lifts the bare
  ground of another by a fraction of a per cent. The mean-one rule removes the blanket attenuation
  an absolute multiplier caused; it does not prove the calibration survives cell by cell, and this
  coupling is the reason it does not.
- **The climate is stationary.** Today's rain and today's cover are spent over rounds standing for
  hundreds of thousands of years each. The march is re-taken once at the midpoint and no more, so
  what the rounds cut with is two snapshots and not a history.
- **A residual drift is left after that refresh.** From the midpoint's terrain the weights still
  move by 15 to 19 per cent of the land mean by the last round at 512, and about 10 at 2048. Two
  marches take the drift from about 40 per cent to that; a third would cost another 6 per cent of a
  generation for a good deal less.
- **A head is a cell.** The channel threshold is a share of the land's weighted water, so where a
  first-order channel begins is set by the grid as much as by the climate.

**A wet flank is not more finely divided than a dry one.** The companion claim to the one above,
and the measurement does not support it. Against a fixed support area — the network's bare geometry
— the windward flank of the belt on every one of the four standard seeds carries about **0.39 to
0.51** of the leeward flank's share of channel cells, and it carries **0.41 to 0.47** of it with the
climate feed switched off as well, so the ordering is the belt's shape and not the rain: the
windward side is the one the ocean is on, which makes it the shorter and steeper side, with less
room behind it for a network to branch in. On the discharge the stage actually routes, the wet flank
leads on two of the four seeds (1.08 and 1.82) and trails on two (0.88 and 0.44). Neither instrument
gives a consistent sign, so `ClimateFedErosionTest` prints the figures and asserts nothing about
them. What dissection *does* follow is rainfall over the land as a whole rather than across one
belt: Spearman's rank correlation between a cell's rainfall and how far it fell over the twelve
rounds reads **+0.244 to +0.312** over the four seeds, against **+0.087 to +0.137** with the feed off.

**Every basin's outlet erodes, including the ones that would never overflow.** Outlet incision is
driven by the outflow over a lip, and a basin in dry country has no outflow: Lake Eyre does not cut
down through its rim, which is why it is still there. Since S3 the hydraulic pass can tell part of
the difference — the accumulation the outlet notch and the closing breach read is discharge now, so
a desert basin's rim is cut by a desert's water — but only part, because the runoff a cell
contributes is floored at 60 mm a year (`Runoff.FLOOR_MM`, the river stage's own floor read in
millimetres) so
that an arid upland still feeds the channel leaving it, and a basin below that floor is drained on
the same terms as one at it. How much lake survives in dry country as a result was not re-measured
by S3; the standing water a dry basin keeps is still decided afterwards, by the water balance, out
of whatever rim survived.

**Some river segments still run uphill on the raw surface.** Routing uses depression-filled elevation, but where a river crosses filled basins it is strictly flowing across ground that does not slope downhill on the original surface. Last measured 2026-08-23 at 12–14% of drawn segments, down from 13–20% before lakes were introduced. What remains is shallow filled ground below `LakesConfig.minDepth` — flats raised by a hair rather than basins deep enough to hold water.

**The monsoon reaches the right coast, but not yet with Earth's force.** The belts are the zonal mean of the wind, and a zonal mean has no monsoon in it: averaging every longitude at a latitude together is exactly what throws the monsoon away. [W2 Pressure-driven surface winds](DESIGN_LEDGER.md) added the departure — a surface pressure anomaly from each season's temperature, and the wind that pressure drives — so a summer continent is a thermal low that draws marine air onto its equatorward and eastern coasts and a winter continent is a high that blows dry air back off them. Measured on the equatorward and eastern coasts of the two standard seeds that carry a subtropical continent holding more than a twentieth of their land, pooled over 4,566 coast cells: the warm half blows onshore at +0.38 m/s and the cold half offshore at -0.36, where with the pressure term off the same coasts take +0.72 in summer and +0.40 in winter — onshore in both halves, which is the failure this note used to record. `PressureWindTest` asserts the pair and shows the control failing. What is *not* fixed is the strength: Earth's summer monsoon flow is metres a second and this is tenths, because the belts still carry the zonal mean at full strength underneath and the thermal equator still migrates only `seasonalTilt` degrees, ten, rather than the twenty-five or thirty a heated continent manages. Deepening the migration over land is still not planned. [W3 The moisture budget calibrated](DESIGN_LEDGER.md) added the term W2 named as missing — a wind that blows two parcels together can now make them rain, from the divergence of the regional wind with the residence time a cell of travel takes — and it does not help the interior of a summer continent: pooled over the four standard seeds the warm half's interior rainfall reads 283 mm with the term and 305 without, 7.0% *down*. The mechanism is the march's own shape. A parcel made to rain harder where the wind converges arrives downwind with less, and Earth answers that by re-supplying it from a monsoon flow of metres a second where this one has tenths. So the term is kept, measured and recorded as a finding rather than asserted in the direction it was built for; `ClimateConfig.convergenceRain` is the control. What would earn the sign back is a deeper seasonal migration over land, above, and R1's channel initiation reading the climate. [A4 Absolute rainfall](DESIGN_LEDGER.md) closed the other half of this note — rainfall was normalized and clamped at 1, and tropical coasts sat against that clamp in the warm season (measured at 0.94-1.00 across five seeds), so the wet half of a monsoon year had no room left to get wetter. `precipitationMm` has no such clamp, and re-measured on A3's own seed (26) with the plan's original claim — summer beating winter 3x over a contiguous region of at least 2% of land — the region now covers 4.07% of land, up from 2.93% under the clamp: the claim holds.

**Rainfall was reported per cell, so every export-resolution world read four times too dry.** The moisture march charges its rain over a cell of travel, so what one cell records is proportional to how wide that cell is; the conversion to millimetres was a single constant calibrated on the 512 grid's 23.4 km cell. A 2048 world therefore reported a quarter of its own rainfall. Measured on two 2048 worlds before [W3 The moisture budget calibrated](DESIGN_LEDGER.md): the interior of 718106 read 101 mm a year and 59758 read 65, against figures in the hundreds for the same physics at 512, and the whole land surface of both rendered as one flat sand colour in the bottom few per cent of the Rainfall view's ramp. That uniform pale interior was a unit and not a climate. Fixed by scaling the conversion with the reference cell's width over this one's, which is exactly 1 at 512 — so every figure ever measured there stands — and 4 at 2048. This is the defect to reach for first whenever an export looks unlike the 512 preview of the same seed.

**The interior is drier than Earth's, by a factor of nearly three.** Over land more than 500 km from the sea, pooled across seeds 7/42/1234/99 at 512, the generator takes 206 mm a year against 573 mm derived in `PressureWindTest` from the annual normals of twenty-five named interior places on every inhabited continent. The spread is right and the level is not: the coefficient of variation reads 1.336 against Earth's 0.853, inside the factor of two this suite calls Earth-like, while the mean sits at 0.36 of Earth's and is recorded as a finding rather than asserted. Three causes, none of them W3's to close. The sea is the only moisture source the march has; lakes have never fed it, which TODO.md records; and the monsoon flow that would re-supply a parcel that has crossed a thousand kilometres of land is tenths of a metre a second here against Earth's metres, which the note above records. W3 moved the figure by taking a per-cell unit out of the millimetre conversion and by giving the ground's return a length in kilometres, and what is left is a moisture-supply question.

**Two of the four audited seeds no longer grow a sheet with a readable dome.** The ice share of land has been a standing finding at half Earth's since W2, and [W3 The moisture budget calibrated](DESIGN_LEDGER.md) made it worse on the drier seeds: `IceSheetTest`'s radial-flow clause reads the bearings within 500 km of a sheet's dome, a disc holding about 2,860 cells on the reference grid, and seeds 59758 and 42 now fill 271 and 245 of it — under a tenth. A neighbourhood that small is not a disc but a thin arc along a margin, where "outward from the dome" and "along the margin" are the same direction, so those two read 43.2% outward at 93.5 degrees off radial and 51.8% at 86.8, which are an indifferent bearing's own numbers. Seeds 718106 and 7 fill 2,656 and 996 and read 91.6% at 41.1 degrees and 81.4% at 58.5, so the clause is asserted on them and printed as a finding on the other two. This is the interior's dryness above showing up in the cryosphere: a sheet is made of snow, and a continent that takes 206 mm a year has none to give. The clause comes back when the march has a moisture supply.

**The cold currents are in the wrong latitudes to make a coastal desert.** The Atacama, the Namib and Baja are subtropical west coasts over cold eastern-boundary water, between about 15 and 30 degrees. The coasts this generator puts over its own coldest water sit at 33 to 42 degrees, in the westerlies with the storm track feeding them 900 to 2,400 mm a year: seed 7's coldest reads 4.48 C below its latitude's mean at 40.6 degrees, which is Earth-strength water at a latitude Earth does not build a coastal desert at, and seed 99's only subtropical candidate is 18 cells at 1.26 C. `MoistureBudgetTest` prints the coast, its latitude and its anomaly on every audited seed. This is a question about where `OceanStage` puts its eastern-boundary currents rather than about the moisture march, and W3's marine inversion had no subject on any standard seed because of it.

**A multiplier on the rain rate cannot dry a coast, for the same reason it cannot wet a rain shadow.** "Where the deserts are" below records that a circulation belt applied to the finished rainfall could not make a rain shadow wet again, so the belt scales the rate instead. W3 found the statement holds the other way up too, and it cost the chunk its marine inversion. The lid a cold sea puts on the air above a subtropical west coast was built, wired and measured strong — a suppression field with a warm-season peak of 0.95 over the coasts' own cells, not one of which stands above the 1,000 m inversion lid — and it moves those coasts' rain by 0.2%. The march is a reservoir: hold the rate down and the moisture stands higher, because evapotranspiration adds on the deficit, and the product the march records comes back within a few cells. Making a coastal desert needs the water taken out of the column rather than the rain rate held down, which is a change to the march's shape. `ClimateConfig.marineInversion` is on, and is recorded as not delivered.

**The sea-ice edge is still close to a line of latitude.** W1 recorded that the pack's edge runs dead straight across an ocean, because the energy balance is a band model with no longitude in it and the only thing that could bend the edge — the current anomaly under it — was a fifth of Earth's. W2 parked the finding on the hope that a regional wind would bend it, and it does not. Measured on the four standard seeds at 512, the cold-season edge's latitude varies across the basin by 17.1, 3.8, 14.8 and 22.7 degrees with the pressure wind and by 17.2, 3.9, 15.2 and 22.7 without it: the wind moved it by four tenths of a degree at most. That is not because the currents held still — the new wind stress moved the current anomaly by 0.30 to 0.66 C on average over the whole sea — but because the ice edge is decided by the sea surface temperature, and the sea surface temperature is the band model's, with the anomaly a small correction on top of it. Earth's Arctic winter edge runs from about 44 N in the Sea of Okhotsk to about 75 N off Norway, 31 degrees of spread (Fetterer and others, *Sea Ice Index*, NSIDC), so the seeds sit between an eighth and three quarters of Earth's. Bending it further is a question about the ocean's heat transport, not about the wind.

**The sea never drowns a glacial trough.** A fjord is a trough the sea has flooded, and flooding one means re-cutting the sea-level percentile, which moves every other coastline on the map. `GlaciationStage` therefore grades its marine troughs down to the waterline and carves the over-deepened basin on the sea floor beyond the mouth, leaving the shelf as a sill — fjord bathymetry without a fjord's coastline. The high-latitude coasts gain depth and islands, not the long narrow inlets of Norway.

**What spaces a coast's valleys is not established, and the comb the author saw is resolution-dependent.** Measured by X1d over seven worlds (969495 and the six audited seeds) at 512, 1024 and 2048, on every straight coast at least 300 km long, with a finder that reads the routing and never the traced rivers (`RangeFront`, driven by `CoastalSpacingAuditTest`). Under that instrument the valleys that reach the divide are about 70 km apart at every grid — 70.3, 70.2 and 66.2 km, 3, 6 and 12 cells — and matched coast by coast the fine grid's figure is 0.92 to 1.02 of the coarse grid's in kilometres. That steadiness is not evidence that the ground sets the figure: halving the instrument's catchment floor takes the spacing to 47 to 52 km and doubling it to 76 to 101, at every grid, so the value is the instrument's reading and a floor fixed in square kilometres would hold it steady across grids by itself. The floor is a choice (1,440 km2, where Hack's empirical length-area relation puts a typical basin's main stream at the 100 km shortest drawn course), not a law. The combs between the valleys are resolution-dependent. Catchments of at least that floor reach the coasts 70.0, 58.2 and 47.7 km apart, and the traced courses — every one of which an export drew before X1c's selection — 75.8, 53.3 and 45.7 km; matched coast by coast from 512 to 2048 they keep 0.83 and 0.80 of their spacing, and at half and double the floor the catchment comb keeps 0.63 and 0.81. A spacing fixed in cells would keep 0.25, one fixed on the ground 1.00: these contract, but far more slowly than the grid refines. The traced comb at 2048 is 45.7 km, 8.4 cells, which is the "ten cells" the author read. His own coast, on the eastern peninsula of 969495 — a smooth slope falling from a plateau at 1,100 to 1,900 m to the sea over 100 to 150 km, beside a transform boundary and on no mountain front — contracts faster. Measured on one line fixed in kilometres, its catchments are 62.3 km apart at 512 and at 1024 and 24.2 km apart at 2048, 0.39 of the 1024 figure, and its traced courses 124.5, 62.3 and 19.4 km, 0.31: below the 0.50 a spacing fixed in cells would keep between those grids, on samples of 5 and 11 gaps. Halving and doubling the relief wavelength and the belt width, and changing the octave count by one (which doubles and halves the finest wavelength the noise carries), on two seeds at 2048, leaves the coasts' valley spacing at 0.82 to 1.18 of the stock world's and the catchment comb at 0.83 to 1.14: no consistent proportional response, which does not show that none of these contributes. Nor does the spacing follow the depth of the ground behind the coast the way Earth's does: Hovius (1996) found the half-width of a linear mountain belt 2.1 times its outlet spacing, and on this instrument's divide proxy the ratio reads 0.86 to 1.09 on coasts and 0.61 to 0.95 on mountain fronts, with the log of spacing rising 0.21 to 0.32 per unit log of half-width where Hovius's belts give 1 — figures of the proxy, which lets a basin alone in its strip set its own divide. A hypothesis for the author's coast, not established: the hillslope process here is a slope threshold with no length of its own, and the spacing of first-order valleys on Earth is set by the ratio of hillslope transport to river incision (Perron, Kirchner and Dietrich 2009), so where the ground is smooth enough that nothing else intervenes the cell may be the only length left. It is recorded in `TODO.md` with the fix it would imply and this measurement as its guard.

**The land's outlines run about two fifths further east-west than north-south on the ground, and the rest of the twofold anisotropy they had is gone.** A square grid over a world twice as wide as it is tall has cells twice as wide as they are tall, and a landscape isotropic on the ground puts as much of its coastline's length into east-west travel as into north-south. Before Fix 2 the coastline projected 1.93, 1.99 and 2.06 times as far east-west as north-south at 512, 1024 and 2048 over seven worlds, which is what land isotropic in *cells* gives: every operator that shapes the ground counted a row as a column, the terrain noise's lattice and the plate stage's boundary distance among them. With each on the ground's ruler ([Fix 2](DESIGN_LEDGER.md)) the four standard worlds at 512 read 1.32, 1.41, 1.41 and 1.41, 1.39 pooled (`GroundIsotropyTest`), where the same measure read 1.88, 2.00, 1.97 and 1.89 before, and over the seven worlds `CoastalSpacingAuditTest` prints, 1.41, 1.48 and 1.53 at 512, 1024 and 2048, the 2,000 m contour 1.46, 1.47 and 1.48; Natural Earth's coastline reads 0.98 at 1:50,000,000 and 1.00 at 1:10,000,000, measured on the sphere. What is left is the hydraulic rounds'. The plate stage's own coast reads 1.09 and 0.91 on seeds 42 and 7, and the thermal sweeps leave it there; the twelve rounds take the erosion stage's land to 1.56 and 1.48, because the incision is capped at half the drop to a cell's receiver in a round, a drop is in proportion to the step's length, and a step down a column is half as long as one along a row, so wherever the cap and not the stream-power law sets the cut a channel running north-south is cut half as deep a round as one running east-west (Audit III's B-D1, the erosion's units). The notches say the same: where a river's course steps along a row it is cut about as deep as the tree before Fix 2 cut it, and where it steps down a column a quarter to a third shallower (`ValleyIncisionTest`). Weakening the law tenfold, so the cap binds less, takes the ratio to 1.14 and 1.04 on seeds 42 and 7, and thirtyfold to 1.05 and 0.91. The routing falls where the ground falls: a plane falling at 45 degrees on the ground routes at 45.0, where it routed at 14.1 (`RoutingGroundTest`). Since [Fix 3](DESIGN_LEDGER.md) put the incision's caps in one unit, the four worlds read 1.02, 1.15, 1.19 and 1.11 on seeds 7, 42, 1234 and 99, 1.12 pooled, where they read 1.32 to 1.41, and seed 1234 is still past what its length allows. The cap now sets every drawn channel's cut, so the per-step asymmetry is still there. Which of Fix 3's changes moved the ratio (the mouths no longer cut below the sea, or the cap binding on more cells) was not isolated. Since [Fix 3b](DESIGN_LEDGER.md) replaced the capped cut with the implicit update, the per-step cap is gone: the notches read the same depth whichever way a course steps (0.0225, 0.0230 and 0.0229 of the field along a row, down a column and on a diagonal on seed 7), the steep ground stands as steep facing a column as a row, and a knickpoint retreats as far north-south as east-west on the ground (`ImplicitIncisionTest`). The coast still reads 1.04, 1.14, 1.14 and 1.16 on seeds 7, 42, 1234 and 99, 1.12 pooled, seed 99 past what its length allows; what holds it over 1 is not isolated.

## Fixed by this audit

**Rainfall no longer normalizes per world.** Every world used to rescale so its 88th land percentile sat at 1.0, which meant an arid world and a lush one classified identically and every world got roughly the same desert share regardless of its actual moisture. Fixed by [A4 Absolute rainfall](DESIGN_LEDGER.md): `classify` now reads `precipitationMm`, millimetres calibrated from the march's own physics (seed 42's windward coast lands at 3000mm, its desert core at 142mm) rather than rescaled per world, so a genuinely arider seed produces genuinely more desert — measured, desert share now ranges 0.99-6.14% across seeds 7/42/1234/99, a 6.2x driest-to-wettest spread where the old normalization produced near-identical shares by construction. The 0..1 field the earlier consumers expected (`CultureStage`'s climate distance, the rainfall map view, and until chunk 6 `RiverStage`'s and `NationStage`'s runoff weighting, which read `precipitationMm` now) is kept as `precipitationMm` divided by a fixed reference and clamped, so nothing downstream needed to change, only what it is calibrated against.

**Lakes.** A basin the priority-flood had to raise is now recognised as standing water: 25–47 lakes
per world, the largest a few hundred cells. The lake surface sits at the basin's spill level, rivers
run into it, and one river leaves at the outlet. River segments *inside* a lake are no longer drawn,
since the river there is the lake — and those were precisely the segments that appeared to flow
uphill. (Inside *open* water, since F15; see "A river is drawn through water narrower than itself"
above.) Depth is shaded from how far the water surface stands above the ground beneath it. (The
spill level is now only where a lake sits when it overflows; see "A dry basin is not a full one"
above for the basins that stand below their rims.)

**Capital siting.** Every capital was coastal — 12 of 12 on every seed, which no real map shows. The
harbour bonus was large enough to outweigh everything else, and the best river cell is always the
mouth. Siting now weighs fresh water above a harbour, adds the surrounding hinterland's carrying
capacity and defensibility (height above the local average), and prefers the **head of navigation**
over the river mouth — inland enough to be defensible and out of the floodplain, still reachable by
water, which is why London, Paris and Rome are where they are.

After: 6–8 of 12 coastal, 6–9 of 12 on a river. A mix rather than a rule.

Chunk 6 asked whether the defensibility term still pulls capitals to the coast: it compares a
cell's height with a mean over land and sea together, so near a coast the sea floor pulls the mean
down and a low coastal cell reads as standing high. Measured against a mean over the land alone on
seeds 7/42/1234/99 at 512 and 969495 at 2048, on the realms chunk 6 first laid, the coastal
capitals were 15/18, 7/14, 8/16, 7/15 and 3/14 with the term as it is and the same with the land-only
mean except 969495, which went to 4/14. The pull is real in the arithmetic and does not decide where
capitals go, so the term was left. On the realms as chunk 6 finally lays them the coastal capitals
are 14/18, 8/14, 8/16, 6/14 and 3/14, 39 of 76, against 36 of 77 on origin/main.

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

**Land gives moisture back, and W3 measured how much of the rain that is.** The share of rain over land whose water last evaporated from land rather than from the sea is the continental precipitation recycling ratio, and van der Ent and others (2010) put Earth's at about 40% globally, 30-45% continent by continent and higher over the Amazon and the Congo. The march has given water back since the belts did, but nothing asked how much of the rain was that water: the answer read zero by construction, because no parcel carried where its moisture had come from. Each parcel now carries a second number that no term in the budget reads, and pooled over seeds 7/42/1234/99 at 512 the ratio reads 32.7% — 27.3, 45.4, 35.1 and 34.7 by seed — against 0.0% exactly with the ground's return switched off, which is the control. The return is an e-folding length of 1,500 km, the middle of van der Ent and Savenije's (2011) 500-2,000 km continental length scales; at 3,000 km the ratio read 20.3%, under Earth's, and the measurement chose between two figures the literature allows. It is scaled by how wet the ground already is, a proxy for the vegetation field, which W4 built and measured against this ramp without displacing it (see the deviation above): dry ground has less to give, which is the feedback that makes an interior either wet or arid rather than uniformly middling.

Verified by `GeographyAuditTest`, by bands since H5b. The old measure asked what share of a world's
desert *cells* fell between 15 and 45 degrees, which counts desert against desert — so the answer
moved whenever a world got wetter or drier for reasons that had nothing to do with placement, and
its bar walked 88 → 85 → 82 over three chunks. Worse, it pooled Earth's two out-of-band categories,
which are not the same thing: Earth's out-of-band desert is essentially all *poleward* of 45 — the
Gobi's north, Patagonia, the Kazakh deserts — and essentially none of it equatorward of 15, while
the defect the guard was written for was desert on the wettest rows of the map. So desert is now
measured as a share of the land in each of three bands, 0–15, 15–45 and 45–90 degrees with the
hemispheres pooled, divided by that world's own desert share of all its land, against the same
ratio for Earth. Earth's figures come from Peel, Finlayson and McMahon (2007): BW is 19.1% of its
land (BWh 14.2 + BWk 4.9), and a census of the named deserts against the land in each band puts
5.2% of the tropics, 39.2% of the horse latitudes and 2.2% of the poleward band under desert —
ratios of 0.27, 2.05 and 0.12. Measured on seeds 7/42/1234/99 at 512: **0.00** in the tropics on
every seed, 2.83/2.52/2.91/2.40 in the horse latitudes against Earth's 2.05, and 0.25/0.55/0.14/0.34
poleward against Earth's 0.12. The first two are asserted, within a factor of two pooled and three
per seed; the third is a deviation, recorded below. Shown to bite on a world with
`evapotranspirationLengthKm` at zero, where the tropics go to 1.15/2.30/2.46/2.46 against Earth's 0.27. Since W3 the tropical band's *per-seed* clause is a printed finding and its pooled clause is not: the ground-wetness feedback above carries seed 1234's tropical rain shadow to x0.89 of that world's own desert share against Earth's x0.27, which is the feedback working rather than the belt failing. Pooled over the four seeds the tropics stayed inside their bar until Fix 2 redrew the continents: on the ground's ruler they read x0.63, x1.04, x0.87 and x0.04, pooling to x0.69 against a bar of x0.54, and the pooled clause runs as a known failure in `GeographyAuditTest` with the cause not separated between the new continents and ranges now as broad across the trades as along them. The horse-latitude clause this guard exists for is untouched, x2.11 pooled against Earth's x2.05.
Desert covers about 4.6% of land without seasons and about 2% with them — see "The year has two
halves" below for why, and for the third mechanism seasons made necessary.
`DesertCauseTest` is the diagnostic that found the cause, attributing each desert cell to its belt,
its upwind climb, and how far its air travelled over land.

## Temperature is solved, not drawn

Until W1 the temperature was a curve: an exponent and two anchors, with the seasonal swing over
water damped by a constant and the swing inland scaled by a `continentality` setting. It could be
made to pass through Earth's equator and Earth's pole and it still could not do the two things that
decide where the interesting climates are, because a curve has no physics in it.

What stands in its place is a one-dimensional energy-balance model — Budyko (1969), Sellers (1969),
North (1975) — over 240 latitude bands, marched through 360 steps of the year for twenty years to a
periodic steady state. Each band balances three terms: the sunlight it absorbs (the astronomical
daily mean for its latitude and the day, at an obliquity read off `seasonalTiltDegrees` — Earth's
zonal-mean thermal equator swings about ten degrees, which is that setting's default, and Earth's
tilt is 23.44); the infrared it radiates, `A + B·T` with North, Cahalan and Coakley's slope of
2.09 W/m² per degree and an offset of 210.7 fixed by Earth's own budget of 240 W/m² at 14 °C; and
the heat its neighbours send it, a diffusion that is 0.90 W/m²/°C at the equator for the Hadley
cell, falls to 0.30 at the pole, and carries a Gaussian bulge of 0.30 more centred on 50° and
fifteen degrees wide for the mid-latitude storm track — two machines in two places, which is how
Trenberth and Stepaniak (2003) separate the observed transport, and a single monotonic shape cannot
be both. The poleward transport that comes out is 4.6, 4.9 and 3.2 PW at 30°, 45° and 60° against
Trenberth and Caron's observed 5.3, 5.0 and 3.3.

- **Three reservoirs per band, and that is the whole land–sea contrast.** Each band carries a land
  column and a marine column with the world's own coastline as their areas, and under the marine
  column a slab of sea water. Both columns are *air*: they see the same sunlight, radiate the same
  infrared law, and trade heat with each other round the latitude circle at 8 W/m²/°C, which is a
  fortnight's exchange for an air column of that capacity and is what the westerlies do. What
  separates them is memory — 1.7 × 10⁷ J/m²/°C over land (three metres of soil, the seasonal
  damping depth, plus the air) against 1.04 × 10⁷ at sea (the air alone, `c_p p / g`) — and what
  lies beneath. Beneath the marine column is a fifty-metre mixed layer, 2.0 × 10⁸ J/m²/°C, coupled
  to the air by a bulk surface flux of 25 W/m²/°C: sensible 11.6 plus latent 13.4 from the standard
  bulk formulae at a typical marine wind of 8 m/s. That split is why a coast is not the sea. On
  Earth's own land fraction, warmest month against coldest at 50–60°: land swings 38.7 °C against a
  continental interior's observed 34–38 (Novosibirsk 34, Winnipeg 38), the marine air 13.0 against
  Earth's 8–11, and the water 6.9 against the open ocean's 5–8. Nothing was fitted at any other
  latitude, and at 30–40° the land reads 28.6 °C against Earth's 24–26.
- **A cell takes a blend of the two air columns.** The two-dimensional field is the band's columns
  mixed by how much of the air over a cell came off the sea, falling away from the coast with an
  e-folding of 350 km. That figure is Earth's: at 50–56° north the annual range against distance to
  the nearest coast runs 8 °C at Valentia, 19 at Berlin (190 km), 22 at Warsaw (330) and 28 at
  Moscow (650), and fitting the exponential to the three inland stations gives 310, 377 and 363 km —
  agreeing to a tenth, which is what says the shape is right and not merely that the curve has a
  spare parameter. The water is not blended onto the land at all: it is read where the sea freezes
  and where the moisture march evaporates, and nowhere else.
- **Each column's year is kept twice, as months and as halves.** Every threshold the biome stage
  reads is one of Köppen's and Köppen's are monthly means — the 10 °C tree line, the −3 °C
  continental winter, the 18 °C tropical one — so a band's stored "summer" is the warmest
  thirty-step window of its own year and its "winter" the coldest. For a sinusoidal year a half-year
  mean is 0.64 of the month extreme, so handing halves to those gates asks each of them a question a
  third short of the one it was written for. But the snow balance's degree-day sum and the moisture
  march both *integrate across* a season, and those read the warm and cold half-years' means
  instead. Neither reading serves both.
- **The albedo is the model's own ice.** A band whose annual mean falls below −10 °C is white
  (Budyko's and North's ice line, where snow cover becomes permanent enough to change what the
  planet reflects), ramped over 4.5 °C either side. The ice-free albedo is a least-squares fit to
  Earth's observed zonal planetary albedo — flat through the tropics, dipping under the subtropical
  highs, climbing late and steeply toward the poles, with the ITCZ's cloud on top — because Earth's
  albedo does not fall monotonically toward the equator and a model that assumes it does bakes the
  subtropics and freezes the equator. So the cap is self-reinforcing: it can be held, and it can be
  lost.
- **`glacialMaximumC` is a forcing now.** The last glacial maximum's 6 °C of global-mean cooling is
  applied by dimming the sun until the model's own global mean falls that far — 4.7 per cent — and
  the model answers with 5.8 °C of cooling at 75° against 4.3 with the feedback switched off, and an
  ice edge that walks from 60.4° to 52.1° instead of to 53.6°. The per-row cooling ramp that used to
  write the amplification down is retired.

On Earth's own land fraction the model reads a global mean of 15.5 °C against 14, an equator of
26.1 against 27, 60° at 1.7 against 0, and a pole at −15.6 against the −20 that is the midpoint of
two poles 34 degrees apart. Column by column, land against a lowland-station climatology and marine
air against a reanalysis over ocean: 26.2/26.0 at the equator against 26.0/26.5, 23.4/23.3 at 20°
against 25.0/24.5, 13.9/13.9 at 40° against 14.5/14.5, and 1.5/1.8 at 60° against −2.0/2.0. The
warmest month over the sea lands at 26.7, 26.0, 19.2 and 7.9 against a reanalysis 27, 27, 19 and 7,
and the water under it at 26.3, 24.8, 16.8 and 4.9 against 27, 27, 19 and 5.5 — inside a ±3 °C
envelope, which is a real spread and not a hedge, because the Atlantic and the Pacific straddle
every one of those figures. `EnergyBalanceTest` measures them all and shows each clause failing
without its mechanism: a tenth of the heat transport bakes the equator to 41 °C and freezes 60° to
−43; an all-ocean world's land column still swings 5.7 times its water, so the contrast is the
capacities and not the geography; and an upright axis has no seasons at all, to a ten-thousandth of
a degree.

**Sea ice, and why the polar ocean is a desert.** Where a season's *water* — the band's mixed layer
plus the current anomaly the ocean stage carries — sits at or below −1.8 °C, the freezing
point of sea water at the ocean's mean salinity, that cell is under ice for that season. The
freezing test reads the water and not the air above it, which in a polar winter is a dozen degrees
colder than the sea it sits on. Two masks
are saved, one per season: the cold season's is the winter pack and the warm season's is the
perennial ice, which is what the map draws. The moisture march takes nothing at all from a frozen
cell, because a metre of ice is a lid — and that is why polar deserts exist, and what keeps an ice
sheet at the pole from feeding itself indefinitely.

On Earth's own land fraction the model's cold-season edge lands at 60.4° N and 61.1° S against
Earth's zonal-mean 60. On the map, measured over seeds 7, 42 and 1234 at 512: the cold season
freezes 25–33% of the sea and reaches 55–58° of latitude, the warm season's perennial pack holds
16–26% and reaches 62–64°, and every cell frozen in the warm season is frozen in the cold one by
construction. The lid is worth a factor of four to seven in the rain: on seed 7 the frozen sea takes
523 mm a year against 2,135 mm over the open water at the same latitudes, on seed 42 351 against
2,508, on seed 1234 257 against 1,811. With `ClimateConfig.seaIce` off — the control — the same
cells take 1,228, 1,592 and 1,305 mm, because then the polar ocean evaporates like any other.

## The year has two halves

Temperature and rainfall are computed twice, for the local warm season and the local cold one, and
biomes are read off all four numbers instead of two. Rainfall's seasons are one setting —
`ClimateConfig.seasonalTiltDegrees`, the degrees the thermal equator migrates toward whichever
hemisphere is in summer — carried by the wind belts and the rain belts alike; temperature's are the
same number read as the planet's axial tilt, so switching seasons off stands the axis upright and
there is no seasonal forcing for the energy balance to answer.

- **"Summer" is local, not July.** Northern July and southern January are both stored as the warm
  season, so one classification rule serves both hemispheres and a dry-summer coast reads the same
  either side of the equator.
- **The stored temperature of a season is its warmest or coldest month, and the rain's is its
  half-year.** Two different questions get asked of the same year. Köppen's thresholds are monthly
  means — the 10 °C tree line, the −3 °C continental winter — so what the biome stage reads and
  what a save carries is the warmest and coldest month. Anything that *integrates* across a season
  reads the half-year's mean instead: the snow balance's degree-day sum runs over 182 days and the
  moisture march evaporates for half a year, and giving either of those a warmest month has it
  melting at the peak of July for the whole of summer. W1's third pass made that mistake for one
  build and it cost the world a third of its permanent ice, which is why both are kept.
- **The annual fields are unchanged.** `temperature` is still the annual mean and `precipitation`
  is the mean of the two marches, so every stage downstream — rivers, realms, peoples, landmarks —
  sees exactly what it saw before. With `seasons = false` the seasonal fields collapse onto the
  annual ones bit for bit and the generator reproduces the pre-seasons world exactly.
- **The sea barely swings, and nobody told it to.** Water's heat capacity is why a maritime climate
  has a small annual range, and since W1 that is a heat capacity in a model rather than a damping
  factor. Measured on seed 42 at 35°, warmest month against coldest: land swings 20.6 °C through
  the year and the marine air 10.8 °C. Earth's figures there are 8–26 over land, 7–11 for the air
  over the ocean and 6–9 for the water beneath it. What the map stores over water is the air, which
  is what a coast feels; the water is a degree or two steadier than that and is read where the sea
  freezes and where the march evaporates.
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
  the right sign for the mechanism and a number for [A4](DESIGN_LEDGER.md)
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

But the belts are only the *zonal mean* of the wind, and since
[W2](DESIGN_LEDGER.md) they are not the whole of it. The regional departure comes from surface
pressure, and surface pressure comes from temperature:

- **A warm column is a low and a cold one is a high.** Each season's surface temperature is taken
  as a departure from the mean of its own row and turned into hectopascals by the hydrostatic
  relation for a heated column — 2.484 hPa per degree, which is the standard atmosphere's surface
  pressure times `ln(1013.25 / 500)` over 288.15 K, with 500 hPa the level of non-divergence. So a
  summer continent is a thermal low, a winter one a thermal high, and a sea chilled by a cold
  current on the east side of a basin carries a ridge, which is where Earth keeps its subtropical
  highs.
- **The anomaly is smoothed at the Rossby radius before it drives anything**, 970 km — `N H / f`
  with a stratification of 1.0e-2, a tropopause at 10 km and `f` at 45 degrees. Below that scale a
  pressure anomaly cannot hold itself up against the flow that drains it, so a bay warmer than the
  cape beside it does not get a weather system of its own. The radius is a length on the ground, so
  the same world smooths over the same distance at every grid.
- **The wind is the pressure gradient balanced against Coriolis and friction**, `f = 2 omega sin(phi)`
  for a planet with Earth's rotation period. Away from the equator that is the geostrophic wind,
  along the isobars with low pressure on the left in the northern hemisphere, turned toward the low
  by the boundary layer's own drag: 25 degrees over sea and 40 over land, the middle of Holton and
  Hakim's observed 10-20 and 25-45. **At the equator there is no special case**: the balance keeps
  a friction term in its denominator, so where `f` goes to zero the wind simply runs straight down
  the gradient, which is what tropical surface air actually does.
- **The two are added, and removing the pressure term gives back the old wind exactly.**
  `ClimateConfig.pressureWinds` off is the belts alone, arithmetic for arithmetic, and it is the
  control every guard on this mechanism is measured against.
- **The march now needs two sweeps.** A thermal low reverses the zonal wind over part of a belt,
  and the air arriving at a reversed cell comes from the column the wavefront has not reached yet.
  So each circulation belt is marched twice, once each way, and every cell records the march whose
  sweep matches the direction its own wind blows. Both marches are the same lock-step wavefront as
  before, so the determinism the pipeline rests on is untouched; a belt with no reversed cell in it
  never runs the second sweep, which is why the control costs nothing.
- **A step of the march is a step, however the wind slants.** The march advances one cell of zonal
  travel and charges that step one cell of rain and one cell of depletion, so the slant is capped
  at the cell own aspect ratio, which holds the step at no more than root two cells on any grid.
  Capping it at a flat one row per cell instead bound on 71% of the cells within ten degrees of the
  equator, where the Coriolis force vanishes and the wind runs down its own gradient, and cost
  those bands 8-9% of their rain; taking the cap away entirely cost them 18%, by letting one step
  reach hundreds of rows for one cell of rain.

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

Above all of them sits the ice gate, and it is no longer thermal at all: H2 replaced "annual mean
below −8°C" with `SnowBalance`'s surplus, so a cell falls through to the aridity line and the
thermal groups unless a year's snow actually outlives the year. What that took away from ice it
gave to tundra — on seed 7, ice 41.9% → 8.4% of land and tundra 17.9% → 50.3% — and to alpine
where the ground stands high enough (0.2% → 1.3% on the same seed). Every other class is unmoved
to within a tenth of a percent, which is the point: the change is about what the ice was hiding,
not about the moisture axis. Sea ice is untouched, because frozen sea water is not a mass
balance.

Reading the coldest month at all needed the latitude curve to actually reach it: at the exponent
seasons landed with, 45° — the effective latitude a 55° coast's summer reads off, one
`seasonalTilt` equatorward — sat at a mere 6.8°C, below the 10°C tree line regardless of any
current. The exponent moved from 1.25 to 1.8 (equator and pole anchors untouched) to fix that, and
it cuts both ways: the same lift that gets a coast's summer past 10°C also lifts a *continental
interior*'s winter past Köppen's −3°C line at the same latitudes, so a dry rain-shadow interior that
used to be taiga could reach `classify`'s desert check on nothing more than a fixed millimetre cut
— measured, that alone dropped desert-in-band on seed 42 from 98–100% to 48%, because the two
effective-latitude ranges overlap almost exactly and no choice of exponent or pole separates them.
(The curve, its exponent and its two anchors are all gone now; W1's energy balance produces the
same separation without an exponent, because a coast and an interior at one latitude are two
different columns rather than two points on one curve. The Köppen gates below are unchanged.)

A6 landed with that gated off by a provisional fix (an annual mean of at least 13°C added to the
temperate branch's desert case) rather than solved, and said so: the gate abolished cold deserts —
the Gobi's annual mean is about 2°C, Patagonia's under 10 — and named the real fix as a Köppen B
(arid) test on rainfall in mm against a temperature-dependent threshold. [A4 Absolute
rainfall](DESIGN_LEDGER.md) is that test, and the 13°C gate is gone: B
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
map. That slope is 60 m per kilometre — 3.4 degrees, which sounds absurdly gentle until you
remember that a cell of the default grid is 23 km across and this is therefore the steepest *mean*
gradient a stretch of ground 23 km long may hold. It is the gentlest of the great mountain fronts
read at that width: the Andes' western flank climbs 6,000 m in 100 km, the Himalayan front 5,000 in
50, the Sierra Nevada's east face 3,000 in 20. Until S2's third pass it was 12 m/km, which was S1's
honest conversion of a unitless figure nobody had ever chosen as a slope, and a twentieth of the
gentlest front on Earth. A stamped plateau's rim ramp falls at about 12 m/km over its 200 km, so the
sweeps found it exactly at the threshold and planed it to a dead plane: every collision belt on the
map wore a smooth cream annulus with no channel crossing it. And the incision never cuts below sea
level, because that is the base level every river grades to; without that limit the cells nearest
the shore incise hardest, having a whole catchment behind them and open water in front, and the
coastline shreds into drowned valleys. Until [Fix 3](DESIGN_LEDGER.md) it did: the limit was a
height above the sea in the land's unit spent on the height field, so a cell could lose 2.67 times
its height above the water, and every round cut 42 to 67% of the cells draining into the sea (55
to 58% over the twelve rounds, on seeds 7, 42 and 1234 at 512, 42 at 1024 and 969495 at 2048), and
every drawn river's mouth, below that round's shoreline, a median 10 to 28 m and up to 980 m. The
ice carving a trough and the pass that opens a drowned basin after the sea-level cut still cut below
it, deliberately.

Measured by `ValleyIncisionTest`, which reads how far the banks stand above the channel across
every drawn river: twice as high as without water until [Fix 3](DESIGN_LEDGER.md), 1.68 times since,
under the deviation below on the incision's cap.

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
  The **depth** of that floor is Earth's, measured rather than assumed (E7). Stamped, it stands
  45–72% of the land's relief below its shoulder crest across five seeds at both 512 and 2048,
  against Earth's own 21–50% (Baikal 3.2–4.0 km of crest-to-floor against 8 km of relief, Tanganyika
  2.8–3.8, Malawi 1.7–2.7, the Dead Sea 1.7–1.9); the water it ends up holding is Earth's too, the
  deepest rift lake on seed 718106 at 2048 measuring 24.2% of the land's relief against Baikal's
  20%. Nor is the floor flat: within half a segment it rises and falls by 65–76% of the trough's own
  depth, from the accommodation zones, the per-segment depth factor and the terrain the belts are
  stamped onto. Deepening it further, and laying a hashed chain of sub-basins on it, were both tried
  and both reverted — see the deviations below.
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

## Boundaries that are gone

A drift vector that only classifies today's boundaries builds a world in which nothing has ever
moved. Every range is young, every range is high, and every range sits exactly on a plate edge.
Earth's continents are not like that: most of a continent is the wreckage of collisions whose
boundary closed long ago. The Appalachians and the Urals are Palaeozoic sutures a thousand
kilometres from any modern plate edge, worn to about half of Alpine height and spread over a wider
province than the Alps occupy; the Benue trough, the North Sea graben and the Mississippi embayment
are rifts that opened, stopped, and filled with their own sediment.

So the stage runs itself several times. Each past epoch carries every plate seed back along minus
its own drift, re-partitions the map into that older set of plates, classifies the pairs that met
*then* by the same crust rules — the crusts themselves do not change, only which pairs meet and how
squarely — and stamps the same five profiles. What it stamps is then aged: the height decays by
roughly half per epoch, every belt half-width grows by half again, and the epoch's own uplift is
blurred before it is added, so a crest and a toe become the smooth swell of a worn range. The
present epoch stamps last and sharpest, and its boundaries, distances and classes are untouched, so
everything downstream still reads today's plate edges where they are.

A rift of a past epoch is not aged, it is buried: the fault dies, the flexural shoulders relax and
the trough fills, leaving the broad shallow sag an aulacogen is rather than the chain of half-grabens
a live rift is.

The result also carries an **age of crust** per cell — how long ago the ground under it was last
built, in bands that cannot overlap, with cratonic country no epoch ever deformed at the far end.
Measured on seeds 7, 42 and 1234, a third of the map is present-epoch belt, a fifth to a quarter
belongs to each older epoch, and 12–23% of the land is cratonic. Nothing reads it yet; it is the
field a later lithology chunk, listed on `ROADMAP.md`, will erode by.

Verified by `TectonicHistoryTest`, which isolates a past epoch's uplift by differencing two worlds
brought into one frame on the cells no epoch touched, and measures it by the same radial profile
`BoundaryPairTest` uses — against that epoch's own boundaries, since the boundary that built it has
since moved. Pooled over the three seeds an old belt stands 2.2 times below a present one and is
1.5 times broader at half height, and the tallest ground the history builds more than 52 cells from
any present boundary (twice `boundaryFalloffCells`, about 1,200 km) stands 0.08–0.14 in normalized
elevation. With the history switched off that difference field is identically zero and the guard
finds nothing at all.

One thing the blur must not do is close the gaps. A belt sags to nothing between massifs by design,
and an isotropic blur wide enough to weld those saddles shut turns a worn province into one
continuous upland — which is bad geography and, downstream, a corridor one people walks the length
of. The blur radius is held below the saddle spacing for that reason.

## Not modelled, and probably shouldn't be

Settlements below the capital, trade routes, and roads. The atlas invents exports and imports from
what a realm's land can produce, but there is no network of towns for them to move between.
