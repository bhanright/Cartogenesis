# To do

## Research: check the generator against real-world geography

Search what fantasy maps commonly get *wrong* about how landforms, water, climate and settlement
actually work, then audit this generator against that list. Known families of mistake worth
checking against:

- **Rivers** — forking downstream (distributaries outside a delta), flowing out of one sea into
  another, crossing a watershed, starting nowhere, or running uphill.
- **Mountains** — as isolated ranges unrelated to plate boundaries, or as a wall with no foothills
  and no rain shadow behind it.
- **Coastlines and seas** — inland seas with no outlet and no evaporation balance; straits and
  bays that ignore how erosion and deposition actually shape a coast.
- **Climate** — deserts placed by aesthetics rather than at the horse latitudes or in rain shadow;
  ignoring that the west coast of a continent at 40 degrees behaves differently from the east.
- **Settlement** — cities placed with no regard for fresh water, defensibility, a harbour, arable
  hinterland, or a trade route; capitals in the geometric centre of a territory.
- **Biomes** — bands that follow latitude only, ignoring altitude, continentality and ocean
  currents.

Then write down which of these the pipeline already gets right (and *why* — the mechanism, not the
claim), which it violates, and which it cannot express at all. Fold the fixable ones into the
stages; note the rest here.

Raised 2026-08-23.

## Open

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
