# To do

## Done

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

## Open

- **The resolution-consistency guard no longer has a statistical form.** It began as mean slope
  away from plate boundaries; erosion invalidated that, and reintroducing the bug it was written
  for showed it no longer caught it. Measuring belt reach directly does not work either, because a
  256 grid and a 512 grid genuinely are different worlds once erosion shapes them. `PipelineTest`
  now reports the figure instead, and `ResolutionScalingTest` pins the contract that actually
  matters. A metric that discriminates the real bug would still be worth having.
- **Skia is two thirds of the web payload** — 8.4 MB of 12.4 MB raw, 3.2 MB of 4.4 MB on the wire.
  Nothing the website can do moves the first-visit cost as much as shrinking this would. No obvious
  lever: it is Compose's renderer, not ours.
- **Mobile is still untested.** The site warns small touch screens off rather than blocking them,
  but nobody has actually loaded the app on a phone.

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
- **The chamfer distance transform leaves faint octagonal streaks** in terrain near plate
  boundaries. An exact Euclidean transform would remove them.
- **`WorldCodec.FORMAT_VERSION` is 2 but nothing reads it.** Fine until the format changes
  incompatibly, at which point a migration needs somewhere to hook in.
