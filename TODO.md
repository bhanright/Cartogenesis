# To do

## Done

- **Lakes** (2026-08-23) — basins the flood had to raise are now standing water, with rivers
  running in and one leaving at the outlet. Took uphill-looking river segments from 13-20% down to
  12-14%; what is left is shallow filled ground below the lake depth threshold.
- **Geography audit** (2026-08-23) — see [GEOGRAPHY.md](GEOGRAPHY.md). Rules checked with
  `GeographyAuditTest` rather than assumed. Capital siting was the one clear violation and is
  fixed; the remaining deviations are recorded below.

## Open

- **Desert latitude.** Rain shadow can overwhelm the latitude band and push desert to the equator
  where rainforest belongs; two of four audited seeds show desert mean latitude at 16 degrees. The
  ITCZ curve is also wrong at high latitudes, making 60 degrees the driest band.
- **Ocean currents, and everything downstream of them.** Requested 2026-08-23. Currently nothing
  models them, so a high-latitude west coast cannot be temperate and a continental interior does
  not swing more than a coast at the same latitude. Worth doing as one piece of work because each
  part feeds the next:
  - Generate surface currents from the prevailing wind bands deflected by the Coriolis effect and
    turned by coastlines, giving the usual subtropical gyres — clockwise north, anticlockwise
    south — with warm water carried poleward on western ocean margins and cold water equatorward
    on eastern ones.
  - Feed sea-surface temperature back into the climate stage, so an onshore wind crossing a warm
    current arrives warm and wet and one crossing a cold current arrives cool and dry. This is
    what makes Bergen mild and coastal Namibia a desert at the same latitude.
  - Add map layers for wind and for ocean currents, drawn as flow arrows or streamlines over the
    existing views.
  - Fold the result into settlement: sheltered harbours on a warm current are worth more than
    exposed ones on a cold one, and a fishery sits where a cold upwelling meets a shelf.
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
