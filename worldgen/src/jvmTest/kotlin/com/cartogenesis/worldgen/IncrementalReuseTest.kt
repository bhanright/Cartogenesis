package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.LoadedWorld
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateResult
import com.cartogenesis.worldgen.pipeline.CultureResult
import com.cartogenesis.worldgen.pipeline.ErosionResult
import com.cartogenesis.worldgen.pipeline.LakeResult
import com.cartogenesis.worldgen.pipeline.LandmarkResult
import com.cartogenesis.worldgen.pipeline.NationResult
import com.cartogenesis.worldgen.pipeline.NormalField
import com.cartogenesis.worldgen.pipeline.OceanResult
import com.cartogenesis.worldgen.pipeline.PlateResult
import com.cartogenesis.worldgen.pipeline.RiverResult
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.TerrainResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Whether reusing the previous world's stages gives the same answer as generating from scratch.
 *
 * [WorldGenerationEngine.generate] can be handed the last world and skip any stage whose settings
 * have not changed, which is what keeps a change to a late setting — realm count, wilderness — from
 * re-running erosion. That is only sound if each stage's guard names every config section the stage
 * actually reads, and three of them did not: erosion reads `seaLevel`, ocean reads `climate`, and
 * rivers read `lakes`, while each was guarded on its own section alone. The bugs stayed invisible
 * because no app ever passed a previous world — only `PipelineTest` did, and it checks that terrain
 * and plates *are* reused rather than that anything downstream is correctly discarded.
 *
 * So this compares the two routes for a change to every section in turn. They must agree exactly.
 */
class IncrementalReuseTest {

    // Seed 99 rather than the usual 42, because 42 at this size has no lakes at all and the
    // lakes case then passes without ever exercising the setting it names.
    private val base = WorldGenConfig(seed = 99L, width = 256, height = 256)

    @Test
    fun `reusing stages gives the same world as generating afresh`() {
        val previous = WorldGenerationEngine.generateBlocking(base)
        println("REUSE base ${fingerprint(previous)}")

        // A case can only 'agree' meaningfully if there was something to disagree about. The first
        // version of this ran on a world with no lakes, so the lakes case passed while reusing a
        // stale river stage — it went through the motions without looking at what the setting
        // changes. These assertions make that failure loud instead of green.
        assertTrue(previous.rivers.lakes.lakes.isNotEmpty(), "base world has no lakes to compare")
        assertTrue(previous.rivers.rivers.isNotEmpty(), "base world has no rivers to compare")
        assertTrue(previous.nations.nations.isNotEmpty(), "base world has no realms to compare")
        assertTrue(previous.landmarks.landmarks.isNotEmpty(), "base world has no landmarks")

        val variants = listOf(
            // S1: the world's own size and the years a round stands for. Every rate and reach from
            // erosion onward is converted through it, so a stale erosion stage would carry a
            // terrain cut to the wrong ruler through everything below it. A taller world is the
            // largest change the section can make short of resizing the map itself.
            "scale" to base.copy(
                scale = base.scale.copy(highestLandMetres = base.scale.highestLandMetres * 1.5f)
            ),
            "worldWidthKm" to base.copy(
                scale = base.scale.copy(worldWidthKm = base.scale.worldWidthKm * 0.5)
            ),
            "terrain" to base.copy(terrain = base.terrain.copy(octaves = base.terrain.octaves - 1)),
            "tectonics" to base.copy(
                tectonics = base.tectonics.copy(plateCount = base.tectonics.plateCount + 3)
            ),
            // H1's knobs live on the same section, so the tectonics guard already covers them —
            // but only if a case actually moves one, and the history is the largest change that
            // section can make: it rewrites the height field every later stage is built on.
            // The crust's own settings, which the plate stage reads to turn a crust into an
            // altitude and the erosion stage reads to bend the plate under what it moves.
            "isostasy" to base.copy(
                isostasy = base.isostasy.copy(elasticThicknessKm = base.isostasy.elasticThicknessKm * 2f)
            ),
            "isostasyOff" to base.copy(isostasy = base.isostasy.copy(enabled = false)),
            "uplift" to base.copy(
                tectonics = base.tectonics.copy(collisionUpliftMmPerYear = 0f)
            ),
            "tectonicHistory" to base.copy(
                tectonics = base.tectonics.copy(historyEpochs = 1)
            ),
            "erosion" to base.copy(erosion = base.erosion.copy(enabled = false)),
            "seaLevel" to base.copy(seaLevel = base.seaLevel - 0.04f),
            "sea" to base.copy(sea = base.sea.copy(shelfDepthMetres = base.sea.shelfDepthMetres + 500f)),
            // H5: the lowstand is the one field of the sea section that reaches *back* into
            // erosion, since it is the base level the hydraulic rounds grade to. The sea stage's
            // own guard would never have caught it going stale, because the sea stage would have
            // been recomputed anyway and would simply have recut a terrain nobody re-eroded.
            "lowstand" to base.copy(sea = base.sea.copy(lowstandMetres = 0f)),
            // And the other half of H5, which changes only the cut: water the ocean cannot reach
            // is land, so this moves `isLand` and everything downstream of it without touching a
            // single height.
            "enclosedSea" to base.copy(sea = base.sea.copy(enclosedSeaIsLand = false)),
            // H5b: the outlet pass that runs on the far side of the cut. Like the enclosure rule
            // above it changes only the sea stage's own two fields — the notch it cuts lives in
            // `relativeElevation`, and where the notch reaches the waterline it moves `isLand` too
            // — so a stale sea stage would carry an undrained basin through every stage below it.
            "postCutOutlet" to base.copy(sea = base.sea.copy(postCutOutlet = false)),
            // Glaciation carves the sea stage's own field, in the same step, so its guard is the
            // sea stage's guard. Turning it off rather than nudging a number, because off is the
            // largest change the section can make and so the loudest failure if it went stale.
            "glaciation" to base.copy(
                glaciation = base.glaciation.copy(enabled = !base.glaciation.enabled)
            ),
            "climate" to base.copy(
                climate = base.climate.copy(
                    equatorTemperatureC = base.climate.equatorTemperatureC + 4f
                )
            ),
            // The seasonal knobs live in the same section as the rest of the climate, so the guard
            // above already covers them — but only if a case actually moves one. A change to the
            // tilt has to reach the ocean stage too, since the currents are driven by the wind
            // belts, which is exactly the kind of cross-stage staleness this test exists for.
            "seasonalTiltDegrees" to base.copy(
                climate = base.climate.copy(
                    seasonalTiltDegrees = base.climate.seasonalTiltDegrees + 6f
                )
            ),
            "seasons" to base.copy(climate = base.climate.copy(seasons = false)),
            // The slant of the wind belts is the same section again, and the same cross-stage
            // question: it changes the wind, and the ocean stage is driven by the wind.
            "meridionalWind" to base.copy(
                climate = base.climate.copy(meridionalWind = 0f)
            ),
            "continentality" to base.copy(
                climate = base.climate.copy(continentality = base.climate.continentality + 0.4f)
            ),
            // H4: the march's over-sea moisture pickup now scales by the ocean stage's current
            // anomaly, so this knob has to invalidate the same way the others in this section do.
            "currentMoisture" to base.copy(
                climate = base.climate.copy(currentMoisture = base.climate.currentMoisture + 0.1f)
            ),
            "rivers" to base.copy(rivers = base.rivers.copy(maxRivers = base.rivers.maxRivers / 2)),
            "lakes" to base.copy(lakes = base.lakes.copy(enabled = !base.lakes.enabled)),
            // E2's knobs live on the same section, and the river stage's guard covers the whole of
            // it - but the water balance reaches further than any earlier lake setting did, since
            // an endorheic basin rewrites the flow targets under it and takes its catchment out of
            // everything downstream. A stale river stage would show up here and nowhere else.
            "waterBalance" to base.copy(lakes = base.lakes.copy(waterBalance = false)),
            "runoffFraction" to base.copy(lakes = base.lakes.copy(runoffFraction = 0.08f)),
            "ocean" to base.copy(ocean = base.ocean.copy(enabled = !base.ocean.enabled)),
            "nations" to base.copy(nations = base.nations.copy(nationCount = base.nations.nationCount + 4)),
            "wilderness" to base.copy(
                nations = base.nations.copy(wilderness = WildernessMode.LEAVE_WILDERNESS)
            ),
            "landmarks" to base.copy(landmarks = base.landmarks.copy(count = base.landmarks.count + 7))
        )

        val disagreed = ArrayList<String>()
        variants.forEach { (name, config) ->
            val fresh = fingerprint(WorldGenerationEngine.generateBlocking(config))
            val reused = fingerprint(WorldGenerationEngine.generateBlocking(config, previous = previous))
            if (fresh != reused) {
                disagreed.add(name)
                println("REUSE $name DIFFERS\n  fresh  $fresh\n  reused $reused")
            } else {
                println("REUSE $name agrees")
            }
        }
        assertEquals(
            emptyList(), disagreed,
            "reusing the previous world changed the result for: $disagreed"
        )
    }

    @Test
    fun `a world rebuilt from its parts reuses every stage and generates nothing`() {
        // What opening a save is: the world arrives as freshly built objects holding freshly
        // allocated arrays — nothing in it came from this engine — and is handed back as the world
        // to reuse. Every stage should match its config and none should run. Identity rather than
        // equality, because a stage that recomputed the same answer would pass an equality check
        // while costing exactly what reuse exists to avoid.
        //
        // The codec's own version of this lives in `:cartography`, where a save can actually be
        // written; this pins the engine half of the contract, which is where it can break.
        val generated = WorldGenerationEngine.generateBlocking(base)
        val loaded = rebuiltAsIfLoaded(generated)

        val opened = WorldGenerationEngine.generateBlocking(base, previous = loaded)

        assertSame(loaded.terrain, opened.terrain, "terrain was regenerated")
        assertSame(loaded.plates, opened.plates, "plates were regenerated")
        assertSame(loaded.erosion, opened.erosion, "erosion was regenerated")
        assertSame(loaded.sea, opened.sea, "sea level was regenerated")
        assertSame(loaded.ocean, opened.ocean, "ocean was regenerated")
        assertSame(loaded.climate, opened.climate, "climate was regenerated")
        assertSame(loaded.rivers, opened.rivers, "rivers were regenerated")
        assertSame(loaded.nations, opened.nations, "realms were regenerated")
        assertSame(loaded.cultures, opened.cultures, "peoples were regenerated")
        assertSame(loaded.landmarks, opened.landmarks, "landmarks were regenerated")
        assertEquals(fingerprint(generated), fingerprint(opened))
    }

    @Test
    fun `a partial world reuses what it has and regenerates climate downstream`() {
        // The engine half of D4's contract: a save missing a stage - here, climate, the one A1
        // actually broke - is not a world the engine refuses. It is a `PartialWorld` with that
        // stage `null`, and the same guard chain that already forces a recompute of a changed
        // stage forces a recompute of a missing one, cascading to everything the pipeline runs
        // after it. `WorldCodecTest` in `:cartography` pins the loader half - this pins the half
        // that can break here.
        val generated = WorldGenerationEngine.generateBlocking(base)
        val loaded = rebuiltAsIfLoaded(generated)
        val partial = LoadedWorld(
            config = loaded.config,
            terrain = loaded.terrain,
            plates = loaded.plates,
            erosion = loaded.erosion,
            sea = loaded.sea,
            ocean = loaded.ocean,
            climate = null,
            rivers = loaded.rivers,
            nations = loaded.nations,
            cultures = loaded.cultures,
            landmarks = loaded.landmarks,
            labels = loaded.labels
        )

        val opened = WorldGenerationEngine.generateBlocking(base, previous = partial)

        assertSame(partial.terrain, opened.terrain, "terrain was regenerated")
        assertSame(partial.plates, opened.plates, "plates were regenerated")
        assertSame(partial.erosion, opened.erosion, "erosion was regenerated")
        assertSame(partial.sea, opened.sea, "sea level was regenerated")
        assertSame(partial.ocean, opened.ocean, "ocean was regenerated")
        // Rivers, nations, cultures and landmarks all had real (if stale) data in `partial` - their
        // own sections would have survived a save missing only climate - so a bug that reused them
        // anyway would still pass a null check. Only identity catches it.
        assertNotSame(partial.rivers, opened.rivers, "rivers should regenerate along with climate")
        assertNotSame(partial.nations, opened.nations, "realms should regenerate along with climate")
        assertNotSame(partial.cultures, opened.cultures, "peoples should regenerate along with climate")
        assertNotSame(partial.landmarks, opened.landmarks, "landmarks should regenerate along with climate")
        assertEquals(fingerprint(generated), fingerprint(opened))
    }

    @Test
    fun `an entirely absent previous still generates every stage`() {
        // The degenerate case of a partial world: nothing at all survived (a version-2 save, or the
        // very first generation). Every guard's `reusable?.takeIf { ... }` has to fail cleanly
        // rather than throw when `previous` itself is null.
        val empty = LoadedWorld(config = base)
        assertNull(empty.terrain)
        val fresh = fingerprint(WorldGenerationEngine.generateBlocking(base))
        val fromEmpty = fingerprint(WorldGenerationEngine.generateBlocking(base, previous = empty))
        assertEquals(fresh, fromEmpty)
    }

    /** Every stage result rebuilt around copied arrays, which is what a decoder hands back. */
    private fun rebuiltAsIfLoaded(world: WorldMap): WorldMap {
        fun field(source: FloatField) = FloatField(source.width, source.height, source.data.copyOf())
        return WorldMap(
            config = world.config,
            terrain = TerrainResult(
                normals = NormalField(
                    field(world.terrain.normals.gradientX),
                    field(world.terrain.normals.gradientY)
                ),
                height = field(world.terrain.height)
            ),
            plates = PlateResult(
                plates = world.plates.plates.toList(),
                plateId = world.plates.plateId.copyOf(),
                boundaryDistance = field(world.plates.boundaryDistance),
                nearestBoundaryType = world.plates.nearestBoundaryType.copyOf(),
                nearestBoundaryClass = world.plates.nearestBoundaryClass.copyOf(),
                height = field(world.plates.height),
                continentalShare = field(world.plates.continentalShare),
                seafloorAgeMyr = field(world.plates.seafloorAgeMyr),
                seafloorHalfSpreadingRateKmPerMyr =
                    world.plates.seafloorHalfSpreadingRateKmPerMyr,
                upliftRateMmPerYear = field(world.plates.upliftRateMmPerYear),
                crustAge = field(world.plates.crustAge)
            ),
            erosion = ErosionResult(height = field(world.erosion.height)),
            sea = SeaLevelResult(
                shorelineHeight = world.sea.shorelineHeight,
                isLand = world.sea.isLand.copyOf(),
                relativeElevation = field(world.sea.relativeElevation),
                landCellCount = world.sea.landCellCount
            ),
            ocean = OceanResult(
                velocityX = field(world.ocean.velocityX),
                velocityY = field(world.ocean.velocityY),
                temperature = field(world.ocean.temperature),
                anomaly = field(world.ocean.anomaly)
            ),
            climate = ClimateResult(
                temperature = field(world.climate.temperature),
                summerTemperature = field(world.climate.summerTemperature),
                winterTemperature = field(world.climate.winterTemperature),
                precipitation = field(world.climate.precipitation),
                summerPrecipitation = field(world.climate.summerPrecipitation),
                winterPrecipitation = field(world.climate.winterPrecipitation),
                precipitationMm = field(world.climate.precipitationMm),
                windDirection = world.climate.windDirection.copyOf(),
                windMeridional = field(world.climate.windMeridional),
                biome = world.climate.biome.copyOf()
            ),
            rivers = RiverResult(
                filledElevation = field(world.rivers.filledElevation),
                flowAccumulation = field(world.rivers.flowAccumulation),
                flowTarget = world.rivers.flowTarget.copyOf(),
                rivers = world.rivers.rivers.toList(),
                lakes = LakeResult(
                    world.rivers.lakes.lakeId.copyOf(),
                    world.rivers.lakes.lakes.toList(),
                    world.rivers.lakes.playa.copyOf()
                )
            ),
            nations = NationResult(
                nationId = world.nations.nationId.copyOf(),
                nations = world.nations.nations.toList(),
                habitability = field(world.nations.habitability)
            ),
            cultures = CultureResult(
                cultureId = world.cultures.cultureId.copyOf(),
                cultures = world.cultures.cultures.toList()
            ),
            landmarks = LandmarkResult(world.landmarks.landmarks.toList()),
            labels = world.labels.toList()
        )
    }

    @Test
    fun `reuse makes a late setting change much cheaper`() {
        // Larger than the correctness case, because the point is the cost of erosion and that only
        // dominates once the grid is big enough to be worth measuring.
        val config = WorldGenConfig(seed = 99L, width = 512, height = 512)
        val previous = WorldGenerationEngine.generateBlocking(config)
        val toggled = config.copy(
            nations = config.nations.copy(wilderness = WildernessMode.LEAVE_WILDERNESS)
        )

        fun time(block: () -> Unit): Long {
            val started = System.nanoTime()
            block()
            return (System.nanoTime() - started) / 1_000_000
        }

        // Once each first, so neither route pays for class loading or a cold JIT.
        WorldGenerationEngine.generateBlocking(toggled)
        WorldGenerationEngine.generateBlocking(toggled, previous = previous)

        val fresh = time { WorldGenerationEngine.generateBlocking(toggled) }
        val reused = time { WorldGenerationEngine.generateBlocking(toggled, previous = previous) }

        println("REUSE COST wilderness toggle: fresh ${fresh}ms, reused ${reused}ms")
        // Deliberately loose. The real figure is far better than this, but a timing assertion that
        // sits near the true ratio fails on a busy machine and teaches everyone to ignore it.
        assertTrue(
            reused * 2 < fresh,
            "reuse saved little: fresh ${fresh}ms against reused ${reused}ms"
        )
    }

    /**
     * A number per stage rather than one for the whole world, so a disagreement says which stage
     * went stale instead of merely that something did.
     */
    private fun fingerprint(world: WorldMap): String {
        fun sum(values: FloatArray): Long {
            var checksum = 0L
            values.forEach { checksum = checksum * 31 + it.toRawBits() }
            return checksum
        }
        return listOf(
            "terrain=${sum(world.terrain.height.data)}",
            "plates=${sum(world.plates.height.data)}",
            "erosion=${sum(world.erosion.height.data)}",
            "sea=${sum(world.sea.relativeElevation.data)}",
            "ocean=${sum(world.ocean.velocityX.data)},${sum(world.ocean.velocityY.data)}",
            "climate=${sum(world.climate.temperature.data)},${sum(world.climate.precipitation.data)}",
            // The seasonal fields separately: they are what a seasonal setting moves, and a
            // checksum of the annual mean alone would be blind to a season going stale.
            "seasons=${sum(world.climate.summerTemperature.data)}," +
                "${sum(world.climate.winterTemperature.data)}," +
                "${sum(world.climate.summerPrecipitation.data)}," +
                "${sum(world.climate.winterPrecipitation.data)}",
            "rivers=${world.rivers.rivers.size},${sum(world.rivers.flowAccumulation.data)}",
            // Lakes are their own result hanging off the river stage. Leaving them out made
            // the `lakes` case pass while reusing a stale river stage — the check went
            // through the motions without ever looking at what the setting changes.
            "lakes=${world.rivers.lakes.lakes.size},${world.rivers.lakes.lakeId.sum()}," +
                // E2's endorheic basins and playas: a stale river stage would keep the old
                // brim-full lakes, and the cell count alone would not say so.
                "${world.rivers.lakes.lakes.count { it.endorheic }}," +
                "${world.rivers.lakes.playa.count { it }}",
            "nations=${world.nations.nations.size},${world.nations.nationId.sum()}",
            "landmarks=${world.landmarks.landmarks.size}"
        ).joinToString("\n         ")
    }
}
