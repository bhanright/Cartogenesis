# Realism audit II — the forces the generator still lacks

*Written 2026-09-12 against `main` at the H2 merge (Tracks A–E, G4, H1, H2, H4 landed; H5, G1
and H3 in flight). Read after `GEOGRAPHY.md`, which says what the generator holds by construction,
and `DESIGN_LEDGER.md`, which says how each of those was measured. This document says what
is still missing, judged against the geoscience literature rather than against other fantasy
generators, and turns the gaps into numbered chunks. Its ambition is a level of rigour and natural believability not yet seen in fantasy map generation.*

## 0. The verdict in one page

The generator now holds about twenty things by construction that map generators normally fake:
plate-classified belts by crust pair, segmented rifts, a sediment budget exact to the last float,
outlet incision, a hydrological water balance, seasons with Köppen classification, a snow mass
balance, jump-flooded distances, saves that carry the world. What remains is not a list of
missing landforms. It is five structural absences that every remaining artefact traces back to:

1. **No physical scale in the solid earth.** Elevation is a 0–1 number, erosion runs for "rounds"
   with dimensionless rates, and the horizontal cell size exists only for the atlas's area figures.
   Climate has metres and millimetres; terrain does not. Every resolution-dependence bug of this
   month (lattice, comb, breach) was this absence showing through.
2. **Uplift stops when erosion starts.** Belts are stamped, then eroded. There is no continuing
   uplift, no isostasy, no steady state. Real relief is the balance of the two (Willett 1999;
   Whipple & Tucker 1999), and H1's ageing factors are a heuristic for what that balance would do.
3. **The atmosphere is prescribed, not solved.** Temperature is a latitude curve with anchors;
   winds are belts; rain is a bucket walk. The three known climate deviations (monsoon on the
   wrong coast, uniform-rain erosion, currents that cannot starve a coast) are all consequences.
4. **The planet is a rectangle.** Cells near the poles are as wide as cells at the equator, so a
   polar row that would be a small cap on a globe is a continent-wide white band, and every
   distance, wind and current near the poles is wrong by a factor of 1/cos(latitude).
5. **The coast is a line, not a process.** No waves, tides, longshore drift, reefs or wetlands, so
   every coast is the same coast: a percentile cut with a shelf behind it.

Presentation has a parallel absence: relief shading is a single light, tints ignore climate,
nothing generalises with zoom, and there is no graticule, scale or projection, so the map never
says how big anything is.

Determinism is in good shape (eight identical generations at 2048; hash order purged; saves carry
the world) and has two remaining weaknesses: sequential random streams that reshuffle everything
downstream of any edit, and scale-free contracts that exist for three stages rather than all of
them.

Sections 1–4 justify those claims domain by domain with the literature; section 5 proposes a
suite of Earth-likeness metrics as the audit's permanent guard; section 6 is the priority table.

## 1. Solid earth

### 1.1 What is held now
Crust-pair profiles (B2), segmented rifts (E4), hotspot chains (B2), three past epochs of aged
belts and failed rifts with a crust-age field (H1), thermal talus by critical slope, stream-power
incision with transport-limited deposition (B3), sill breaching (E1), Euclidean distance fields
(G4). Land fraction is a percentile of the height field.

### 1.2 What the literature says the generator is missing

**Units and time (S1).** The stream-power law is `E = K·A^m·S^n` with m/n ≈ 0.5 and K in
m^(1−2m)/yr, typically 10⁻⁶–10⁻⁵ for bedrock rivers (Whipple & Tucker 1999; Lague 2014's review
of the evidence). Hillslope transport is Culling diffusion `∂z/∂t = D∇²z` with D ~ 10⁻² m²/yr,
or Roering's nonlinear form near the critical slope (Roering, Kirchner & Dietrich 1999). Both
need metres and years. The generator has `maxAltitudeMetres` and `lapseRateC` in the climate
stage, `squareKilometresPerCell` in the atlas, and nothing in erosion. Consequence: the same
world at 512 and 2048 is a different world unless every knob is hand-carried through
`atResolution`, which is exactly the class of bug fixed three times this month. Give the world a
`WorldScale` (planet radius or map width in km; vertical range in m; Myr per hydraulic round),
express K, D, uplift and every reach in it, and resolution invariance becomes a property rather
than a contract.

**Coupled uplift and erosion, and isostasy (S2).** Steady-state relief in an active belt is set
by uplift rate against erodibility and precipitation, not by a stamped profile (Willett 1999;
Whipple & Tucker's height limits). Old belts are low because uplift stopped and erosion
continued; H1 imitates that with decay factors. Running an uplift-rate field (mm/yr by boundary
class and age: 1–5 in active belts, ~0 on cratons) *during* the hydraulic rounds, with time in
Myr, produces it. Isostasy is the other half: crust floats, so erosion unloads and the range
rebounds, ice loads depress (post-glacial rebound is why Scandinavia's shores rise and its raised
beaches exist), sediment loads subside (deltas, foreland basins; DeCelles & Giles 1996). Flexural
isostasy is a low-pass filter on the load, `w(k) = L(k) / (Δρ·g + D·k⁴)` (Turcotte & Schubert),
which the terrain stage's FFT already provides the machinery for. Earth's bimodal hypsometry
(continental mode near +0.8 km, oceanic near −3.7 km) is an isostatic fact about two crust
densities; with isostasy in, "ocean coverage" stops being a percentile the user sets and becomes
the continental-crust fraction the plate stage draws, with the percentile as a check.

**Erosion reads the climate (S3).** The hydraulic pass runs before climate exists, so a rain
shadow erodes as fast as a windward slope; GEOGRAPHY.md lists this as a deviation. The cure is
the one H2 used for glaciation: a provisional climate march before erosion (0.9 s at 2048),
with discharge `Q = P·A` in the stream-power law and precipitation-weighted talus. Willett
(1999) shows the orographic asymmetry this produces: steeper, faster-exhuming windward flanks.

**Lithology (H3, already planned)** completes this: K and D vary by rock and age of crust.

**Volcanism.** Arc volcanoes are spaced 30–70 km along an arc (de Bremond d'Ars et al. 1995), so
at 20 km per cell an arc should carry a cone every two to four cells, not a continuous ridge;
calderas and flood-basalt plateaus (H3) follow.

### 1.3 Proposed chunks
- **S1 Units and time** — `WorldScale`; every erosion and glaciation knob in m, km, yr; `atResolution`
  retired for physical knobs. Guard: a scale-free suite (section 5) at 512/1024/2048 with no
  per-stage contracts. Large refactor, no visible change on its own, prerequisite for S2, S3, H3,
  G1's final semantics. GPU: none.
- **S2 Coupled uplift and flexural isostasy** — uplift field from boundary class and age; flexure
  by FFT after each round and after ice and sediment loads; H1's decay factors retired in favour of
  time. Guard: relief in active belts scales with uplift/K as Whipple & Tucker predict; old belts
  lower and broader by the measured ageing; hypsometry bimodal with modes near Earth's; foreland
  basins appear in front of collision belts. Visual: large (mountain roots, coastal plains,
  basins). GPU: the flexure is an FFT (G-track candidate at 4096+).
- **S3 Erosion reads the climate** — provisional march before erosion; Q = P·A. Guard: windward
  flank steeper and more dissected than leeward by a stated ratio on a seed with a strong shadow.
  Visual: medium. GPU: the march stays CPU (lock-step wavefronts).

## 2. Hydrology

### 2.1 What is held now
Priority-flood fill, D8 routing, rainfall-weighted accumulation, channel threshold by
accumulation, lakes by water balance with endorheic sinks and playas, outlet incision, deltas
that carry the river, rivers stopping at lake shores.

### 2.2 What the literature says
**Channel heads.** Channels begin where `A·S² > C` (Montgomery & Dietrich 1988), not where
accumulation crosses a constant. That threshold makes drainage density fall with steepness and
rise with rainfall, which is why humid uplands are finely dissected and arid plains are not
(Moglen, Eltahir & Bras 1998 find density maximal in semi-arid climates). The generator's constant
threshold gives one density everywhere.

**Network form.** Natural networks obey Hack's law `L ∝ A^h` with h ≈ 0.5–0.6 and Horton's
bifurcation ratio 3–5 (Horton 1945; Rodríguez-Iturbe & Rinaldo 1997). D8 on a smooth surface
tends to produce Hack exponents near 0.5 and long straight reaches (TODO.md records the
bearing-persistence artefact). Neither is measured today; both are cheap guards.

**Channel geometry and planform.** Width scales as `w ∝ Q^0.5` (Leopold & Maddock 1953);
meander wavelength as ~11·w (Leopold & Wolman 1960); a river braids above a critical slope
`S ≈ 0.06·Q^−0.44` (Leopold & Wolman 1957). None of these needs a grid cell: they are how the
river is *drawn*, and they are the difference between a line and a river on a 4096 export.

**Wetlands and groundwater.** Where slope is near zero, accumulation high and evaporation low,
the water table is at the surface: the Sudd, the Pantanal, the Okavango (an inland delta).
Springs and oases where an aquifer meets a desert surface. Neither exists; the first is a cheap
overlay from fields that exist, the second needs lithology.

### 2.3 Proposed chunks
- **R1 Channel initiation and drainage density** — A·S² threshold with a climate term; guards:
  drainage density vs aridity follows the Moglen curve's shape; Hack exponent 0.5–0.65 and
  Horton R_b 3–5 on every standard seed. Visual: medium at 2048. GPU: none.
- **R2 Rivers drawn as rivers** — width from discharge, braided reaches above the Leopold–Wolman
  slope, meander geometry below cell scale at export, floodplain width from deposition. Guard:
  none needed beyond the picture; visual: large on exports.
- **R3 Wetlands and inland deltas** — a wetland mask from slope, accumulation and P−E; drawn as a
  biome overlay; inland deltas where a big river enters a flat endorheic basin. Guard: wetland
  share of land within Earth's ~6% (Davidson 2014) by a factor of two. Visual: medium.

## 3. Coasts and the sea

### 3.1 What is held now
Shelves (B1), rift gulfs (E4), deltas (B3/E1), pockets and lowstand valleys (H5, in flight),
fjord bathymetry without fjord coastlines (B4 deviation), currents feeding pickup (H4).

### 3.2 What the literature says
Coasts are classified by the balance of river sediment, wave energy and tidal range (Galloway
1975; quantified by Nienhuis et al. 2020 and Broaddus et al. 2022): river-dominated deltas are
bird-foot, wave-dominated ones are cuspate with beach ridges, tide-dominated ones are estuarine
with tidal flats. Wave energy follows wind fetch (`H ∝ U·√fetch`, the SMB relation); longshore
drift builds spits, barrier islands and cuspate forelands on low-gradient coasts with sand
supply (Pilkey; Bird's *Coastal Geomorphology*). Tidal range grows with shelf width and basin
resonance. Coral reefs form where the coldest month stays above ~18 °C and the shelf is shallow;
on subsiding volcanic islands they pass through Darwin's sequence, fringing reef to barrier reef
to atoll, which the generator's age-progressive hotspot chains already set up: the old end of a
chain should be atolls. Mangroves line tropical sheltered coasts, salt marshes temperate ones.
Barrier islands and rias are both products of the post-glacial rise, which H5 provides.

### 3.3 Proposed chunks
- **K1 Wave climate and longshore drift** — fetch from the wind field, wave power per coast cell,
  drift direction from wave angle; spits and barrier islands where drift, supply and gradient
  allow. Guard: barrier islands appear only on low-gradient, sandy, wave-dominated coasts; count and
  length reported against the US Atlantic seaboard's ~300 barrier islands scaled by coast length.
  Visual: large at 2048. GPU: the wave field is per-cell (rule 8: behind the seam).
- **K2 Delta and estuary type** — Galloway class from river sediment flux (B3's load), wave power
  (K1) and a tidal proxy from shelf width; the lobe's shape follows the class. Guard: the mix of
  classes across seeds is not one class. Visual: medium.
- **K3 Reefs and atolls** — reef mask from coldest-month SST ≥ 18 °C and shelf depth; fringing on
  young volcanic islands, atolls on the old end of hotspot chains, barrier reefs on tropical
  shelves; drawn as a bathymetric feature. Guard: reefs confined to the tropics; atolls only at the
  subsided end of chains. Visual: large in the tropics.
- **K4 Fjord coastlines** — closes the B4 deviation once H5 makes the sea-level cut a
  connectivity question: a glacial trough graded below the present level floods. Visual: large on
  high-latitude coasts.

## 4. Atmosphere and climate

### 4.1 What is held now
Latitude curve with an exponent and two anchors; seasons by a tilted thermal equator; wind
belts with a meridional slant; a bucket moisture march with belt-scaled release and land
recycling; continentality by distance from water; currents scaling pickup; Köppen thermal gates;
Köppen aridity on millimetres; Thornthwaite evaporation; a snow mass balance with degree-days.

### 4.2 What the literature says
**Energy balance (W1).** A one-dimensional energy-balance model (Budyko 1969; Sellers 1969;
North 1975) gives temperature against latitude from insolation, albedo and meridional heat
diffusion, per season, with two things the curve cannot give: ice-albedo feedback (a snow-covered
band is colder because it is white, which is why polar climates are self-reinforcing and why the
model can hold a cap or lose it) and a land-sea contrast that comes from heat capacity rather than
from a continentality knob. It costs a few hundred latitude bands per season. Sea ice follows from
it (SST below −1.8 °C), and sea ice matters: it shuts off the moisture source over polar seas, so
polar deserts are dry, which H2's balance needs to keep ice sheets from over-feeding at the poles.

**Surface winds from pressure (W2).** Belts are the zonal mean. Regional winds follow surface
pressure: summer continents are thermal lows, winter continents highs, and the subtropical highs
sit over the eastern oceans (Holton & Hakim). A surface pressure field from the temperature
anomaly, and a wind that is geostrophic plus a 20–30° cross-isobar Ekman turn, superimposed on the
belts, gives onshore summer flow toward the heated continent — the monsoon on the coast it
belongs to, the deviation GEOGRAPHY.md records — and the winter outflow that dries Siberia.

**Moisture (W3).** The march is a moisture budget; its constants (release rate, recycling
fraction, depletion length) were tuned by picture. The literature gives the numbers: continental
precipitation recycling ~30–40% (van der Ent et al. 2010), moisture residence ~8–10 days, so the
advective depletion length is ~U·τ ≈ 3,000–5,000 km. Orographic precipitation's local structure is
Smith & Barstad's linear theory (2004): condensation from forced ascent with advection delays of
~1,000 s, solved by FFT; at 20 km cells its advection scale is sub-cell, so it matters for 4096
worlds and for the *shape* of rain against a range (upwind enhancement, spillover) more than for
the continental pattern. The cold-coast rain-out suppression noted in TODO.md is the
marine-inversion term.

**Biomes (W4).** Köppen is the right first classification. Holdridge life zones (biotemperature ×
precipitation × PET) and Whittaker's diagram are alternatives that read potential evaporation,
which the stage now has; the practical gain is finer steppe/savanna/woodland boundaries and a
vegetation density field that S3 and H3 can read (vegetation halves erodibility; Istanbulluoglu &
Bras 2005). Permafrost as a modifier where the annual mean is below −2 °C.

### 4.3 Proposed chunks
- **W1 Energy balance and sea ice** — 1-D EBM per season with ice-albedo feedback and heat-capacity
  contrast; replaces the latitude curve and the continentality knob; sea-ice mask feeds the march
  and the snow balance. Guard: Earth's zonal-mean temperature profile within a stated envelope
  (equator ~27 °C, 60° ~0 °C, poles ~−20 °C annual); polar seas frozen in winter; the cap responds
  to `glacialMaximumC` by growing, not by being redrawn. Visual: medium (correct polar and
  continental climates). GPU: none; it is one-dimensional.
- **W2 Pressure-driven surface winds** — thermal lows and highs from the seasonal temperature
  anomaly, geostrophic wind with Ekman turning, added to the belts. Guard: on a seed with a large
  subtropical continent, summer onshore flow on its equatorward and eastern coasts (the monsoon on
  the right coast), winter offshore flow; shown failing with the pressure term off. Visual: large
  (wet coasts move to where they belong). GPU: per-cell gradient work, behind the seam (rule 8).
- **W3 Moisture budget calibrated** — depletion length, recycling and residence set from the
  literature figures above; marine-inversion suppression on cold-current coasts; optional
  Smith–Barstad local term at 4096. Guard: continental recycling ratio measured at 30–40%; a
  subtropical west coast with a cold current becomes desert. Visual: medium.
- **W4 Vegetation density and permafrost** — a 0–1 vegetation field from Holdridge, permafrost
  mask; both saved; H3 and S3 read vegetation for erodibility. Visual: small alone, structural.

## 5. Cryosphere

Held: snow balance, Pleistocene carving mask, valley and sheet regimes, budgets in map fractions.
Missing: **ice sheets as bodies.** An ice sheet has a surface profile (Vialov: thickness ∝ √
distance from the margin), stands kilometres above its bed, and flows down its own surface, not
the bed's; its weight depresses the crust by a third of its thickness. Consequences the generator
lacks: the sheet's own altitude makes its climate (Greenland's summit is cold because it is
3 km up), flow is radial from the dome so scour is streamlined and drumlin fields align with it,
outlet glaciers converge into troughs that become fjords (K4), and the rebound after melting
lifts raised beaches (S2). **I1 Ice sheets with a profile** — Vialov profile over the carving
mask, flow from the surface gradient for the sheet regime, isostatic load handed to S2, fjord
troughs at outlets. Guard: sheet thickness within Earth's envelope (2–3 km at the centre of a
continental sheet); scour lineations parallel to the flow. Depends on S2. Visual: medium.

## 6. Planet geometry

The map is equirectangular: rows are latitudes, the top and bottom rows are the poles, and every
cell is the same size in the physics. On a globe the polar row has zero area. Three consequences
are visible today: a polar continent is drawn as a band across the whole map (seed 7's north),
distances and winds near the poles are stretched by 1/cos(latitude) so belts and currents there
are wrong in shape, and area statistics (land fraction, ice share, desert share) over-weight the
poles.

- **P1 Metric-aware physics** — every distance, gradient and advection step scaled by cos(lat) in
  x (the jump-flood distance, the D8 slopes, the wind march, the current solve on a β-plane, the
  plate Voronoi metric); every statistic and guard area-weighted; a display projection for the
  whole-world view (Robinson or Equal Earth) with a graticule and a scale bar from `WorldScale`,
  the flat view kept for editing. Guard: a feature of fixed physical size has the same cell footprint
  scaled by cos(lat) at 60° and at the equator; polar land share by area within Earth's order.
  Visual: large at high latitude. GPU: none new.
- **P2 A spherical grid** — generate on a cube-sphere or icosahedral grid and rasterise to any
  projection. Every stage generalises; the terrain FFT becomes a spherical harmonic transform or a
  per-face FFT. This is the honest end state and a multi-week track; P1 buys most of the visible
  benefit first.

## 7. Presentation

Held: nine styles, relief shading, coastline, bathymetric tints, rivers by width, lakes by depth,
a toolbar and cartouche, Mars.

The literature here is cartographic. Imhof (*Cartographic Relief Presentation*, 1965) sets the
standard: hypsometric tints should follow climate (no green in a desert), relief shading should
come from a sky model rather than one lamp (multidirectional and sky-model hillshading, Kennelly
& Stewart 2014), aerial perspective lightens distant lowlands, and contours carry the exact
information shading suggests. At export scale the missing generalisation is the tell: coastlines
keep every cell's stair-step, minor rivers are drawn at the same weight at every zoom, and there
is no graticule or scale because the world has no size.

- **V1 Tints by climate and sky-model shading** — hypsometric ramps modulated by biome and
  aridity; multidirectional shading with a sky term; aerial perspective; bathymetric contours.
  Guard: the picture, and the colour-blind margins from F6 still hold. Visual: large. GPU: the
  raster (G2) already runs on the card; the shading is a kernel there.
- **V2 Generalisation, graticule and scale** — Douglas–Peucker coastlines and rivers by zoom,
  river weight by Strahler order with pruning at low zoom, contour interval by zoom, a graticule
  and scale bar from `WorldScale`, a north arrow that means something once P1 gives the map a
  projection. Visual: large at whole-world scale.
- **V3 Labels** — the Wonderdraft-style system the author described: feature detection (continents,
  seas, bays, capes, straits, isthmuses, ranges, deserts, regions), naming from the peoples'
  languages, placement along curves with collision avoidance, overrides that survive saves. Three
  chunks (detect, name, place); the render check is the whole point.

## 8. Determinism

Held: eight identical generations at 2048 from one seed and config; no `HashMap`/`HashSet` order
in any decision; sorted neighbour lists; saves carry the world so platform parity is informational;
scale-free contracts for glaciation, outlet incision and lakes; the realm-id fail-fast.

Two weaknesses and one acceptance:

- **Sequential random streams (N1).** Each stage seeds `Random(seed·prime + k)` and draws in
  loop order, so any change in how many draws precede a feature reshuffles everything after it.
  Editing plate count changes every hearth and landmark even where the land did not move. Replace
  sequential draws with per-feature hashes (`hash(seed, stage, cellOrIndex)`, splitmix-style, as
  E3 and E4 already do) so that unchanged inputs give unchanged outputs locally. Guard: change a
  setting that leaves a region's terrain identical and assert that region's landmarks, hearths and
  names are identical. This is what makes "generate everything, override anything" editable
  without surprises.
- **Scale-free contracts everywhere (N2).** S1 makes most of this a property; the guard is the
  metrics suite (section 9) run at 512, 1024 and 2048 with each statistic within a stated factor.
  Where a stage cannot be scale-free (D8 on a grid), say so and pin the measured drift.
- **Chaos is accepted (N3).** Which basin ends up largest, where a rift floods, which coast wins a
  delta: these are sensitive to everything upstream, and E1 measured it. The generator promises
  determinism (same seed, same settings, same build → same world, on one platform) and stability
  under editing (N1), not continuity under settings changes. Document that promise in the README
  and the About dialog, and keep floating-point reductions in fixed order (they are; the soak proved
  it) so that promise holds under parallelism.

## 9. Earth-likeness metrics: the audit's permanent guard

Every chunk above should be accepted against numbers that describe Earth, not against a
threshold set from what the generator produced last week. A single `EarthLikenessTest`
(audit tier, 512 per merge for the cheap ones) measuring, per standard seed and pooled:

| Metric | Earth | Source |
|---|---|---|
| Hypsometric curve | bimodal, modes near +0.8 km and −3.7 km; 85% of surface in two bands | Cawood et al. 2022 review |
| Coastline fractal dimension | 1.2–1.3 (Britain 1.25) | Mandelbrot 1967 |
| Hack's exponent | 0.5–0.6 | Hack 1957; Rigon et al. 1996 |
| Horton bifurcation ratio | 3–5 | Horton 1945 |
| Drainage density vs aridity | peaked in semi-arid climates | Moglen, Eltahir & Bras 1998 |
| Lake size distribution | Pareto, exponent ~1.06 by count; 4.2 M km² total | Downing et al. 2006 |
| Island size distribution | power law (Korčak), exponent ~0.5 | Korčak 1938; Mandelbrot |
| Desert, share of the land in each latitude band (0–15°, 15–45°, 45–90°) | per-band BW fractions derived from the Köppen–Geiger map; H5b derives and writes them | Peel, Finlayson & McMahon 2007 |
| Ice share of land | 10.1% | Cogley 2014 / RGI |
| Lakes, share of land in lakes at least one cell in area | ≈1.7% for lakes ≥ 100 km², ≈1.2% for ≥ 1,000 km² (from the paper's size classes; re-read at M1); compare at the map's cell area, since the 4.2 M km² total is mostly ponds below any grid here | Downing et al. 2006 |
| Wetlands share of land | ~6% | Davidson 2014 |
| Reef latitude limit | coldest month ≥ 18 °C | Kleypas 1999 |
| Delta class mix | all three Galloway classes present | Nienhuis 2020 |
| Realm size distribution | heavy-tailed, not uniform | Zipf-like; report only |

Reported always, asserted where the measure-do-not-tune rule allows: a bar moves to Earth's figure with the
derivation beside it, never to the generator's last output.

## 10. Priority table

Effort: S small (a day of work), M (two to three), L (a week or more). Visual: 1–5 at
2048. Rigour: how much of the structural absence in section 0 it removes.

| Chunk | Depends on | Effort | Visual | Rigour | GPU (rule 8) |
|---|---|---|---|---|---|
| S1 Units and time | — | L | 1 | 5 | none |
| S2 Uplift and isostasy | S1 | L | 5 | 5 | FFT flexure later |
| S3 Erosion reads climate | S1 | M | 3 | 4 | none |
| W1 Energy balance, sea ice | — | M | 3 | 4 | none |
| W2 Pressure winds | W1 | M | 4 | 4 | seam |
| W3 Moisture calibrated | W2 | S | 3 | 3 | none |
| W4 Vegetation, permafrost | W1 | S | 2 | 2 | none |
| R1 Channel initiation | S1 | M | 3 | 3 | none |
| R2 Rivers drawn as rivers | — | M | 5 | 2 | raster |
| R3 Wetlands | W3 | S | 3 | 2 | none |
| K1 Waves and drift | W2 | L | 5 | 4 | seam |
| K2 Delta types | K1 | M | 3 | 3 | none |
| K3 Reefs and atolls | W1 | M | 4 | 3 | none |
| K4 Fjord coastlines | H5 | M | 4 | 3 | none |
| I1 Ice sheets with a profile | S2 | M | 3 | 4 | none |
| P1 Metric-aware physics | — | L | 4 | 5 | none |
| P2 Spherical grid | P1 | L+ | 5 | 5 | rethink |
| V1 Tints and sky shading | — | M | 5 | 1 | raster |
| V2 Generalisation and graticule | S1 | M | 4 | 2 | none |
| V3 Labels | — | L | 5 | 1 | none |
| N1 Per-feature hashes | — | M | 1 | 4 | none |
| N2 Scale-free suite | S1 | S | 1 | 4 | none |
| M1 Earth-likeness metrics | — | M | 1 | 5 | none |

**Suggested order.** M1 first, because it is the yardstick for everything after; then S1, which
every solid-earth chunk needs and which turns the resolution contracts into a property; then two
parallel lines, S2 → S3 → R1 → I1 on the solid earth and W1 → W2 → W3 → K1 → K2/K3 on the fluid
one, with P1 slotted where it touches the fewest open files; V1 and R2 whenever the raster is
free; V3 last, as the author asked, once the features it names exist.

## 11. Sources consulted for this audit

- Smith, R. B. & Barstad, I. (2004). A linear theory of orographic precipitation. *J. Atmos. Sci.*
  61, 1377–1391. https://journals.ametsoc.org/view/journals/atsc/61/12/1520-0469_2004_061_1377_altoop_2.0.co_2.xml
- Ohmura, A., Kasser, P. & Funk, M. (1992). Climate at the equilibrium line of glaciers.
  *J. Glaciol.* 38, 397–411. https://www.researchgate.net/publication/252219065_Climate_at_the_Equilibrium_Line_of_Glaciers
- Whipple, K. X. & Tucker, G. E. (1999). Dynamics of the stream-power river incision model.
  *JGR* 104, 17661–17674. https://agupubs.onlinelibrary.wiley.com/doi/10.1029/1999JB900120
- Willett, S. D. (1999). Orogeny and orography: the effects of erosion on the structure of
  mountain belts. *JGR* 104, 28957–28981. https://agupubs.onlinelibrary.wiley.com/doi/10.1029/1999JB900248
- Leopold, L. B. & Wolman, M. G. (1957). River channel patterns: braided, meandering and straight.
  *USGS Prof. Paper* 282-B. https://www.scirp.org/reference/referencespapers?referenceid=1691948
- Hack's law and Horton's ratios: https://en.wikipedia.org/wiki/Hack's_law ;
  https://ebooks.inflibnet.ac.in/geop11/chapter/morphometric-analysis/
- Downing, J. A. et al. (2006). The global abundance and size distribution of lakes, ponds, and
  impoundments. *Limnol. Oceanogr.* 51, 2388–2397. https://aslopubs.onlinelibrary.wiley.com/doi/10.4319/lo.2006.51.5.2388
- Galloway (1975) and its quantification: Nienhuis et al. 2020; Broaddus et al. 2022;
  Paniagua-Arroyave & Nienhuis 2024. https://agupubs.onlinelibrary.wiley.com/doi/full/10.1029/2024JF007878 ;
  https://geo.libretexts.org/Bookshelves/Oceanography/Coastal_Dynamics_(Bosboom_and_Stive)/02:_Large-scale_geographical_variation_of_coasts/2.07:_Process-based_classification/2.7.3:_Classification_of_deltas
- Earth's bimodal hypsometry: Cawood et al. (2022), *Rev. Geophys.*
  https://agupubs.onlinelibrary.wiley.com/doi/10.1029/2022RG000789 ; https://en.wikipedia.org/wiki/Hypsometry
- Coral reef thermal limit and Darwin's subsidence sequence:
  https://journals.plos.org/plosone/article?id=10.1371%2Fjournal.pone.0128831 ;
  https://link.springer.com/rwe/10.1007/978-90-481-2639-2_29
- Cited from memory and to be checked when a chunk is written: Culling (1960) and Roering et al.
  (1999) on hillslope diffusion; Montgomery & Dietrich (1988) on channel heads; Moglen, Eltahir &
  Bras (1998) on drainage density and climate; Leopold & Maddock (1953) hydraulic geometry;
  Budyko (1969), Sellers (1969), North (1975) energy-balance models; van der Ent et al. (2010)
  moisture recycling; Istanbulluoglu & Bras (2005) vegetation and erosion; DeCelles & Giles (1996)
  foreland basins; Turcotte & Schubert, *Geodynamics*, on flexure; de Bremond d'Ars et al. (1995)
  volcano spacing; Kennelly & Stewart (2014) sky-model shading; Imhof (1965); Mandelbrot (1967);
  Korčak (1938); Davidson (2014) wetlands; Kleypas et al. (1999) reef limits; Tierney et al.
  (2020) LGM cooling; Calov & Greve (2005) degree-days; Hock (2003) melt factors.
