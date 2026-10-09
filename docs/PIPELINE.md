# The pipeline

What each stage of generation reads, what it writes, and who reads what it writes — so that a change
to one stage can be followed to everything it can move. Read from the code at `f08e3e3a`
(`WorldGenerationEngine.generate`, each stage's entry point, and every `world.<stage>.<field>` read
in `:worldgen` and `:cartography`); where this page and the code disagree, the code is right and
this page is stale.

Stages run in `GenerationStage` order. Each is reused only when its upstream result is the same
object (`===`) and every config section it reads is unchanged; those sections are listed in the
engine's guards, and `IncrementalReuseTest` holds them (conventions rule 12).

## Stages

| # | Stage (file) | Reads | Writes (result) | Read downstream by |
|---|---|---|---|---|
| 1 | Terrain (`TerrainStage.kt`) | `terrain`, `scale` | `normals`, `height` (0..1) | Plates (`height`); drawing: the normals view (`MapRasterizer`, `RasterAccelerator`) |
| 2 | Plates (`PlateStage.kt`, `Isostasy.kt`) | terrain `height`; `tectonics`, `isostasy`, `seaLevel`, `scale` | `height` (isostatic altitude), `upliftRateMmPerYear`, `plateId`, `boundaryDistance`, `nearestBoundaryClass`/`Type`, `seafloorAgeMyr`, `crustAge`, `continentalShare`, `plates` | Erosion (`height`, `upliftRateMmPerYear`); Landmarks (`boundaryDistance`, via the plate result); drawing: plate and boundary views (`plateId`, `boundaryDistance`, `nearestBoundaryClass`) |
| 3 | Erosion (`ErosionStage.kt`, `HydraulicErosion.kt`, `DeltaFan.kt`) | plate `height`, `upliftRateMmPerYear`; `erosion`, `scale`, `isostasy`, `facetRouting`, `flatPotential`, `seaLevel`, `sea.lowstandMetres`; with the climate feed on, also `climate`, `ocean`, `vegetation` | `height` | Sea level (`height`); the geometry guard's deposition layers through `LayerCapture` |
| 4 | Sea level and ice (`SeaLevelStage.kt`, `LittoralGrading.kt`, `DrownedValleys.kt`, `GlaciationStage.kt`, `IceSheet.kt`) | erosion `height`; `seaLevel`, `sea`, `facetRouting`, `flatPotential`, `glaciation`; with glaciation on, `climate` (and `ocean` with the snow balance) | `relativeElevation` (0 at the shoreline, ice troughs cut in), `isLand`, `landCellCount`, `shorelineHeight` | Every later stage (`isLand`, `relativeElevation`, `landCellCount`); drawing: the coast, relief, isobaths, the narrow sea, data export (`MapRasterizer`, `RasterAccelerator`, `NarrowSea`, `RiverSelection`, `DataExport`) |
| 5 | Ocean (`OceanStage.kt`, `OceanCirculation.kt`, `OceanHeat.kt`, `SurfaceBelts.kt`) | sea result; `ocean`, `climate` (the wind belts) | `velocityX`, `velocityY`, `temperature`, `anomaly` (sea surface, °C) | Climate (`anomaly`: the marine inversion and the maritime temperatures); Nations (`anomaly`); drawing: currents (`velocityX/Y`) and the sea temperature anomaly view (`anomaly`) |
| 6 | Climate (`ClimateStage.kt`, `EnergyBalance.kt`, `PressureWind.kt` with `SphericalGrid.kt`, `SphericalOperators.kt` and `AtmosphereRemap.kt`, `MoistureMarch.kt`, `ColumnWater.kt`, `SurfaceEvaporation.kt`, `SphereBlur.kt`, `MoistureBudget.kt`, `SnowBalance.kt`, `VegetationDensity.kt`) | sea result, ocean result; `climate`, `vegetation` | `temperature`, `summer/winterTemperature` (each place's warmest and coldest month), `july/januaryTemperature`, `precipitation` (0..1), `julyHalf/januaryHalfPrecipitation` (April to September, October to March), `precipitationMm`, `windDirection`, `windMeridional`, `julyHalf/januaryHalfSeaIce`, `biome`, `vegetationDensity`, `permafrost`, `potentialEvapotranspirationMm`, `openWaterEvaporationMm` | Erosion's feed and Rivers (`precipitationMm` and `potentialEvapotranspirationMm` through `Runoff`, `openWaterEvaporationMm` for the lakes; `summer/winterTemperature`); Nations' runoff (`precipitationMm`, `potentialEvapotranspirationMm`); Nations (`biome`, `precipitationMm`); Cultures (`temperature`, `precipitation`, `biome`); Landmarks (`temperature`, `biome`); drawing: every climate view, the biome fill and ice (`MapRasterizer`, `RasterAccelerator`), the land tint (`ClimateTint`: `biome`, `temperature`, `precipitationMm`, `vegetationDensity`), data export (`biome`) |
| 7 | Rivers and lakes (`RiverStage.kt`, `ChannelInitiation.kt`, `LakeWaterBalance.kt`, `RiverWidth.kt`, `FlowRouting.kt`) | sea result, climate result; `rivers`, `lakes`, `facetRouting`, `flatPotential` | `filledElevation`, `flowAccumulation`, `flowTarget`, `rivers`, `lakes` | Nations (`flowAccumulation`, through `BasinPartition`); Cultures (through `BasinPartition`); Landmarks; drawing: lakes (`MapRasterizer`, `RasterAccelerator`), the drawn rivers (`RiverSelection` chooses them, `MapRasterizer` inks them at `RiverPen`'s widths), the save (`WorldCodec`) |
| 8 | Realms (`NationStage.kt`, `BasinPartition.kt`, `BasinRealms.kt`, `Atlas.kt`) | sea, climate, rivers and ocean results; `nations` | `nationId`, `nations`, `habitability` | Landmarks (`nationId`, `habitability`); drawing: borders and realm fills (`nationId`), data export, the interface's realm list (`nations`) |
| 9 | Peoples (`CultureStage.kt`) | sea, climate and rivers results — not the realms; `cultures` | `cultureId`, `cultures` | drawing: peoples' borders (`cultureId`), the cartouche (`cultures`) |
| 10 | Landmarks (`LandmarkStage.kt`) | sea, climate, rivers, plates and realms results; `landmarks` | `landmarks` | drawing: map symbols (`MapRasterizer`), the save |

Every result is also written whole by the save (`WorldSections.kt`, `WorldCodec.kt`), so a new or
renamed field is a format change (conventions rule 11).

## Loops: a stage run early, inside another

The pipeline is a straight line except where a stage needs an answer only a later stage gives. It
then runs a provisional copy of that later stage, and nothing outside the stage sees the copy.

- **Erosion runs the climate.** With `erosion.climateFeed` on, `HydraulicErosion.provisionalWeather`
  cuts a provisional sea level (`SeaLevelStage.percentileCut`) and runs the whole climate
  (`ClimateStage.generateWithSeasonalMm`) over a still ocean (`OceanStage.withoutCurrents`), then
  cuts with that rain. So a change to the climate, ocean or vegetation code or settings moves the
  terrain itself, and through it everything below.
- **Sea level runs the climate for the ice.** With glaciation and `climate.snowBalance` on, the
  engine runs `ClimateStage.provisionalSnowBalance` on the freshly cut sea over a still ocean, and
  `GlaciationStage` decides the ice from it and carves `relativeElevation`. Glaciation also reads
  `ClimateStage.buildTemperature` directly.
- **The ocean reads the climate's code.** `OceanStage` builds its surface temperature with
  `ClimateStage.buildTemperature` and `zonalClimate`, and its wind stress from `SurfaceBelts`, all
  before the real climate runs.
- **Both provisional climates have the currents off.** The real ocean exists only from stage 5 on;
  erosion and glaciation never see it.
- **Isostasy is solved three times.** Plates (`Isostasy.Columns`), erosion and glaciation
  (`Isostasy.Flexure`) each flex the plate under what they add or remove.

## Downstream reach, in short

- Terrain → plates → erosion → sea level → ocean → climate → rivers → realms and peoples →
  landmarks → drawing. Anything in the first four moves every later stage and every drawn layer.
- A change to the **climate's code** reaches further than its stage: through erosion's feed and the
  ice it moves the ground, the coast and the sea, and so the ocean, then the real climate, and
  everything after.
- A change to **routing** (`FlowRouting`, `FlatRouting`, `facetRouting`, `flatPotential`) moves
  erosion, the sea level's outlet pass, glaciation and the rivers.
- **Peoples do not depend on realms**; landmarks do. A realm-only change moves realms, landmarks and
  the drawing of both, and nothing else.
- The drawing reads only these results and `LayerCapture` (the deposition and ice layers the
  geometry guard reads in `MapLayers`, `cartography/src/jvmTest`); it writes nothing back.

## What a change moves in the tests

A generation change moves the world fingerprint (`WorldFingerprintTest` prints digests by branch),
the render records (`RecordedRenders.kt`), the geometry census (`GeometryExpectations.kt`) and the
known failures' signatures. Run the affected tests with `-Precord`, then
`./gradlew :worldgen:applyPinRecords`, and review the diff; see `PinRecords.kt` in
`worldgen/src/sharedTestSupport`.

The standard worlds are the default planet's, Earth's 40,075 km round (docs/DESIGN_LEDGER.md, K2).
A guard that measures an operator or a drawing constant at the cell size it was set on builds its
worlds on `CalibrationPlanet` (the same shared test support), the 12,000 km planet with fourteen
plates the default was until K2, and says so beside it. Those worlds are generated by the same code,
so a generation change moves them too, and their figures are re-recorded with the rest.

## Which deep tests a change calls for

Every run of `jvmTest` is the everyday tier: standard worlds only (default settings, 512 rows or
fewer, one grid per seed). The deep tier — each class's own variants for an on/off control, grid
comparisons, worlds of 1,024 rows or more — runs with `deepTest`, by stage. The application makes
one grid, 1,024 rows (docs/DESIGN_LEDGER.md, G1), so a grid comparison there reports its figures
under `CROSS-GRID` rather than failing; the worlds of 1,024 rows are the application's own.

    ./gradlew :worldgen:deepTest :cartography:deepTest :desktop:deepTest -Pstages=<stages>

`-Pstages` takes the stages below, and adds every stage each reaches by the table above and the
loops (climate and ocean reach back to erosion and the sea level), with `engine` and `drawing`
always. Without it, every deep class runs. What to name, by where the change is:

| A change to | `-Pstages=` |
|---|---|
| `TerrainStage.kt`, `noise/`, `math/` (shared by most stages), `concurrent/` | `terrain` (reaches every stage) |
| `PlateStage.kt`, `Isostasy.kt` (also solved in erosion and glaciation) | `plates` |
| `ErosionStage.kt`, `HydraulicErosion.kt`, `DeltaFan.kt`, `GroundSteps.kt`, `Runoff.kt` | `erosion` |
| Routing: `FlowRouting.kt`, `FlatRouting.kt`, `facetRouting`, `flatPotential` | `erosion` (reaches the sea level, the ice and the rivers) |
| `SeaLevelStage.kt`, `LittoralGrading.kt`, `DrownedValleys.kt`, `WaterTopology.kt`, `GlaciationStage.kt`, `IceSheet.kt` | `sea` |
| `OceanStage.kt`, `OceanCirculation.kt`, `OceanHeat.kt`, `SurfaceBelts.kt` | `ocean` (reaches back to erosion through the climate feed) |
| `ClimateStage.kt`, `EnergyBalance.kt`, `PressureWind.kt`, `SphericalGrid.kt`, `SphericalOperators.kt`, `AtmosphereRemap.kt`, `MoistureMarch.kt`, `ColumnWater.kt`, `SurfaceEvaporation.kt`, `SphereBlur.kt`, `MoistureBudget.kt`, `SnowBalance.kt`, `VegetationDensity.kt` | `climate` (reaches back to erosion and the ice) |
| `RiverStage.kt`, `ChannelInitiation.kt`, `LakeWaterBalance.kt`, `RiverWidth.kt` | `rivers` |
| `NationStage.kt`, `BasinPartition.kt`, `BasinRealms.kt`, `Atlas.kt`, `NameForge.kt` | `realms` (`NameForge` is read by peoples and landmarks too: add `peoples`) |
| `CultureStage.kt` | `peoples` |
| `LandmarkStage.kt` | `landmarks` |
| `WorldGenerationEngine.kt`, `PartialWorld.kt`, the stage guards | `engine` |
| A default in `WorldGenConfig.kt` | the stage whose section it is: a default moves the standard worlds, so the everyday tier sees it too |
| `:cartography`'s drawing, `:desktop`'s rendering and export | `drawing` |
| `:desktop`'s erosion or ocean accelerator on the card | `erosion` or `ocean` (their deep classes skip without a card) |
| `:desktop`'s atmosphere remapping on the card (`GpuAtmosphere`) | none: `GpuAtmosphereTest` is everyday, and skips without a card |
| The shared test support (`SharedWorlds`, `WorldFile`, `WorldDiskCache`) | none: `WorldDiskCacheTest` is everyday |

The dry stationary-wave model (`StationaryWaveModel.kt`, `ZonalBasicState.kt`, `AtmosphereLevels.kt`,
`WaveDamping.kt`, with `math/ComplexBlockTridiagonal.kt` and `math/SymmetricEigen.kt`) is read by no stage
yet (docs/DESIGN_LEDGER.md, A1-3): a change to it moves no world and calls for no deep class. Its tests
are `StationaryWaveModelTest` and `StationaryWaveBenchmarkTest`, everyday, and `StationaryWaveReport` and
`StationaryWaveCostTest` in the audit tier.

Any change to the generator's code also empties the world cache for the next run (its key is a hash
of the compiled classes), so the first everyday run after one generates its standard worlds afresh.
