# To do

## Done

- **Geography audit** (2026-08-23) — see [GEOGRAPHY.md](GEOGRAPHY.md). Rules checked with
  `GeographyAuditTest` rather than assumed. Capital siting was the one clear violation and is
  fixed; the remaining deviations are recorded below.

## Open

- **Lakes.** The highest-value item from the geography audit. Depression filling removes every
  enclosed basin, which is why 13-20% of river segments technically run uphill across raw terrain,
  and why the maps have no lakes at all. Rendering a filled basin as water — river in, spill point
  out — fixes the artefact and adds a missing feature.
- **Desert latitude.** Rain shadow can overwhelm the latitude band and push desert to the equator
  where rainforest belongs; two of four audited seeds show desert mean latitude at 16 degrees. The
  ITCZ curve is also wrong at high latitudes, making 60 degrees the driest band.
- **Ocean currents and continentality** are not modelled at all, so a high-latitude west coast
  cannot be temperate and an interior does not swing more than a coast.
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
