package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.PartialWorld
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.CultureStage
import com.cartogenesis.worldgen.pipeline.DepositionLog
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import com.cartogenesis.worldgen.pipeline.ErosionStage
import com.cartogenesis.worldgen.pipeline.GlaciationStage
import com.cartogenesis.worldgen.pipeline.LandmarkStage
import com.cartogenesis.worldgen.pipeline.NationStage
import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.RiverStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The steps of the pipeline, in the order they run.
 *
 * [label] is the sentence shown while a stage is running and [shortLabel] the noun used where
 * there is no room for one — a tooltip, a log line, an error naming which stage failed.
 */
enum class GenerationStage(val label: String, val shortLabel: String) {
    TERRAIN("Shaping terrain", "terrain"),
    TECTONICS("Drifting plates", "plate tectonics"),
    EROSION("Wearing down the mountains", "erosion"),
    SEA_LEVEL("Flooding oceans", "sea level"),
    OCEAN("Turning the currents", "ocean currents"),
    CLIMATE("Simulating climate", "climate"),
    RIVERS("Carving rivers", "rivers"),
    NATIONS("Settling realms", "realms"),
    CULTURES("Spreading peoples", "peoples"),
    LANDMARKS("Stocking the wilds", "landmarks")
}

/**
 * Told which stage is about to run, so a caller can draw a progress bar.
 *
 * Called on whatever thread generation is running on, once per stage and *before* the stage does
 * its work — so [stageIndex] is how many stages are finished, out of [stageCount]. A stage that is
 * reused rather than recomputed is still reported, because from the outside it did happen.
 *
 * [onStage] suspends, and that is the whole of what it buys. In a browser there is one thread: the
 * page, the generator and every repaint share it, so a report that merely writes a string somewhere
 * changes nothing a reader can see — the next stage takes the thread straight back and the browser
 * never gets a frame. A suspending report lets the caller hand the thread back at each boundary,
 * which is what turns ten stage names into ten things that actually appear. On the JVM, where the
 * generation is already off the interface's thread, the callback simply does not suspend.
 */
fun interface GenerationProgress {
    suspend fun onStage(stage: GenerationStage, stageIndex: Int, stageCount: Int)
}

/**
 * Runs the generation pipeline.
 *
 * Suspending for [ErosionAccelerator] and [OceanAccelerator]: stages do ordinary blocking work, but
 * a GPU accelerator has to await its device and its results, so the one call that might do so
 * makes the whole chain suspend. Nothing suspends when generating on the CPU.
 *
 * Generation is fully deterministic for a given config, which is what lets HD export re-run at a
 * higher resolution instead of upscaling a preview bitmap.
 */
object WorldGenerationEngine {

    private val NO_PROGRESS = GenerationProgress { _, _, _ -> }

    /*
     * On reuse: passing [previous] lets a stage be skipped when nothing it depends on has changed,
     * which is what keeps a change to a late setting - realm count, wilderness - from re-running
     * erosion. Each stage below is guarded on two things: that its upstream result is the very
     * same object (`===`, so a recomputed upstream forces everything after it), and that every
     * config section the stage *reads* is unchanged.
     *
     * That second half is the easy one to get wrong, and three stages had it wrong: erosion reads
     * `seaLevel`, ocean reads `climate`, and rivers read `lakes`, while each was guarded on its
     * own section alone. If you add a stage, or make an existing one read a new section, add it
     * here too - `IncrementalReuseTest` compares reuse against a fresh generation for a change to
     * every section in turn and will catch you.
     *
     * [previous] takes a [PartialWorld] rather than a [WorldMap] for one reason: a save can be
     * missing a stage a newer build added (see `WorldSections.rebuild`), and that stage's `null`
     * has to fail the `=== ` guard below exactly as a recomputed one would, which is what makes
     * "missing" cascade into "and everything downstream" with no extra logic. A `WorldMap` is a
     * `PartialWorld` with nothing missing, so every existing caller that hands back a live,
     * fully-generated world keeps compiling unchanged.
     */

    /**
     * Generates a whole world from [config] — every stage's result, in the order the enum above
     * lists them — or reuses the stages of [previous] that [config] cannot have changed.
     *
     * [previous] is only consulted when it was generated at the same seed and resolution; any
     * other difference falls through to a full generation. Labels are carried across from it,
     * since they are the user's and not the generator's.
     */
    suspend fun generate(
        config: WorldGenConfig,
        previous: PartialWorld? = null,
        /**
         * Used only when the config asks for it, and only for erosion. Declared before [progress]
         * rather than after so that a trailing lambda at a call site still binds to the progress
         * callback, which is what every caller means by it.
         */
        accelerator: ErosionAccelerator? = null,
        /** Uses the same graphics acceleration preference as erosion; null keeps the CPU solve. */
        oceanAccelerator: OceanAccelerator? = null,
        /** The same preference again, for the ice sheet's profile and surface flow; see rule 8. */
        iceAccelerator: IceSheetAccelerator? = null,
        /**
         * Filled in with the layers the map draws that no stage's result carries, for the geometry
         * guard; null, the default, allocates nothing and changes nothing. See [LayerCapture].
         */
        capture: LayerCapture? = null,
        progress: GenerationProgress = NO_PROGRESS
    ): WorldMap {
        val reusable = previous?.takeIf { it.config.sameResolutionAndSeed(config) }

        val stages = GenerationStage.entries
        /*
         * Every stage boundary is also a chance to give up. A reader who presses Stop is asking for
         * the settings back, not for the world, and the pipeline is ordinary blocking arithmetic
         * with no suspension point of its own, so without this a cancelled generation would run to
         * the last landmark and hand back a world nobody wanted. The long stages ask the same
         * question between their own rounds; between the short ones this is enough.
         */
        suspend fun report(stage: GenerationStage) {
            currentCoroutineContext().ensureActive()
            progress.onStage(stage, stage.ordinal, stages.size)
        }

        report(GenerationStage.TERRAIN)
        val terrain = reusable
            ?.takeIf {
                it.config.terrain == config.terrain &&
                    // Since S2's second pass the integration shapes the surface's spectrum, and
                    // the wavelength it is shaped around is a length in kilometres — so how wide
                    // the world is decides which components of the noise survive. See
                    // [TerrainStage.ReliefBand].
                    it.config.scale == config.scale
            }
            ?.terrain
            ?: TerrainStage.generate(config)

        report(GenerationStage.TECTONICS)
        val plates = reusable
            ?.takeIf {
                it.terrain === terrain &&
                    it.config.tectonics == config.tectonics &&
                    // Isostasy is what turns a crust into an altitude, so this stage's output is
                    // in its units; and since S2 the ocean-coverage slider chooses how much of the
                    // world is drawn as continental crust, which is a tectonics question decided
                    // two stages before the sea level stage runs.
                    it.config.isostasy == config.isostasy &&
                    it.config.seaLevel == config.seaLevel &&
                    // Every altitude the stage writes is read off the world's own ruler.
                    it.config.scale == config.scale
            }
            ?.plates
            ?: PlateStage.generate(config, terrain)

        report(GenerationStage.EROSION)
        val erosion = reusable
            ?.takeIf {
                it.plates === plates &&
                    it.config.erosion == config.erosion &&
                    // Every rate and reach in the erosion stage is now a length, a depth or a
                    // time, converted to the grid through `scale` where it is read, so the world's
                    // own size is one of this stage's settings. It is guarded here rather than at
                    // every stage below because erosion is the first to read it and each later
                    // guard already requires this stage's own result to be the one it was handed.
                    it.config.scale == config.scale &&
                    // The rounds raise the belts as well as cutting them, and they bend the plate
                    // under what that stacks on it, so both halves of the solid earth's settings
                    // are this stage's settings. The uplift rate itself arrives on the plate
                    // result, which the `===` above already guards.
                    it.config.isostasy == config.isostasy &&
                    // Water is routed twelve times over in this stage, and how it is routed
                    // is a top-level setting rather than one of `erosion`'s own.
                    it.config.facetRouting == config.facetRouting &&
                    it.config.flatPotential == config.flatPotential &&
                    // Hydraulic erosion routes water against a provisional shoreline, so where the
                    // sea sits changes what gets carved. Guarding on `erosion` alone reused a
                    // stale height field whenever sea level moved.
                    it.config.seaLevel == config.seaLevel &&
                    // And the shoreline the rounds grade to is not today's, it is the stand the
                    // sea was at while they were cutting. That one field of the sea section is
                    // named rather than the whole of it on purpose — the shelf remap and the
                    // enclosed-water rule both happen after erosion, and re-running twelve
                    // hydraulic rounds because someone moved a shelf slider would undo the whole
                    // point of this chain.
                    it.config.sea.lowstandMetres == config.sea.lowstandMetres &&
                    // And since S3 the rounds cut with the rain. The provisional march erosion
                    // runs is a whole climate stage over a still ocean, so it reads the climate
                    // section as the real one does and the ocean section through
                    // `OceanStage.withoutCurrents`, which copies that section with its gyres
                    // switched off; the cover it weighs the incision by comes from the vegetation
                    // section. All three only when the feed is on — with it off the rounds see
                    // flat rain and bare ground, and moving a rainfall slider must not re-cut
                    // twelve rounds' worth of valleys that could not have heard it.
                    (!config.erosion.climateFeed || it.config.climate == config.climate) &&
                    (!config.erosion.climateFeed || it.config.ocean == config.ocean) &&
                    (!config.erosion.climateFeed || it.config.vegetation == config.vegetation)
            }
            ?.erosion
            ?: if (capture == null) {
                ErosionStage.apply(config, plates.height, plates.upliftRateMmPerYear, accelerator)
            } else {
                val log = DepositionLog(config.width * config.height)
                ErosionStage.apply(
                    config, plates.height, plates.upliftRateMmPerYear, accelerator,
                    onRound = null, log = log
                ).also { capture.deposition = DepositionLayers(log.mechanism) }
            }

        report(GenerationStage.SEA_LEVEL)
        val sea = reusable
            ?.takeIf {
                it.erosion === erosion &&
                    it.config.seaLevel == config.seaLevel &&
                    // The post-cut outlet pass and the ice both route water; see the
                    // erosion guard above.
                    it.config.facetRouting == config.facetRouting &&
                    it.config.flatPotential == config.flatPotential &&
                    // The continental shelf is a post-percentile remap of the ocean floor, not a
                    // tectonics setting, so a shelf-only change must not reuse a stale sea stage.
                    it.config.sea == config.sea &&
                    // Glaciation carves the same field, immediately afterwards and as part of this
                    // stage's result, so its settings are this stage's settings for the purpose of
                    // reuse - and so is `climate`, because the freezing line is read off the
                    // climate section's own temperature curve two stages before that stage runs.
                    it.config.glaciation == config.glaciation &&
                    (!config.glaciation.enabled || it.config.climate == config.climate) &&
                    // The provisional climate below is a whole climate stage, so it reads the
                    // ocean section the way the real one does - the coastal reach continentality
                    // and the maritime term are measured in, and the sea temperature the march
                    // evaporates from. A change there moves the ice as well as the rain.
                    (!config.glaciation.enabled || !config.climate.snowBalance ||
                        it.config.ocean == config.ocean)
            }
            ?.sea
            // Ice carves between the percentile cut and everything that reads the terrain, which is
            // why it lives inside this step rather than beside it: what it produces is a sea-level
            // result, the same shape and the same coastline, with the troughs in it. Giving it a
            // `GenerationStage` of its own would have meant a save section of its own, and it has
            // no field of its own to save - it rewrites `sea.relativeElevation`, which is already
            // stored and already the thing every later stage reads.
            ?: run {
                val cut = SeaLevelStage.apply(erosion.height, config)
                // The provisional climate. Ice is a mass balance, and a mass balance needs the
                // rainfall as well as the temperature, so the ice cannot be decided from latitude
                // and altitude alone: the whole climate stage runs here, on the terrain as it
                // stands before the carving, purely to produce the snow balance the mask is taken
                // from. Nothing else in the pipeline sees it — the real ocean and the real climate
                // are computed below, after the ice has cut.
                //
                // On a still ocean, deliberately: the gyre solve is the expensive half of a
                // climate and it is worth well under two per cent of the ice mask. The finished
                // map's ice, which is what the reader sees, is classified from the real climate
                // below and does see the currents. See docs/DESIGN_LEDGER.md, H2, for both figures.
                val provisional =
                    if (config.glaciation.enabled && config.climate.snowBalance &&
                        cut.landCellCount > 0
                    ) {
                        ClimateStage.provisionalSnowBalance(
                            config, cut, OceanStage.withoutCurrents(config, cut)
                        )
                    } else null
                if (capture == null) {
                    GlaciationStage.apply(config, cut, provisional, iceAccelerator)
                } else {
                    GlaciationStage.apply(config, cut, provisional, iceAccelerator) { mass ->
                        capture.ice = IceLayers(
                            frozen = mass.frozen,
                            valleyGlacier = mass.valleyGlacier,
                            sheet = mass.onTheSheet,
                            cutByIce = mass.cutByIce,
                            cutByOutlets = mass.cutByOutlets,
                            basinFloor = mass.basinFloor,
                            valleyBasinCount = mass.basins,
                            iceThicknessMetres = mass.iceThicknessMetres
                        )
                    }
                }
            }

        report(GenerationStage.OCEAN)
        val ocean = reusable
            ?.takeIf {
                it.sea === sea &&
                    it.config.ocean == config.ocean &&
                    // Currents are the stream function of the wind stress, and the wind belts are
                    // a climate setting. Climate runs *after* this stage, so nothing downstream
                    // would have caught the staleness for us.
                    it.config.climate == config.climate
            }
            ?.ocean
            ?: OceanStage.generate(config, sea, oceanAccelerator)

        report(GenerationStage.CLIMATE)
        val climate = reusable
            ?.takeIf {
                it.ocean === ocean && it.config.climate == config.climate &&
                    // The cover on the ground and the frozen ground under it are this stage's
                    // fields: they are built from its own temperatures and rainfall and saved on
                    // its result, so its section is not the only one it reads. See
                    // `VegetationConfig` and rule 12 of docs/CONVENTIONS.md.
                    it.config.vegetation == config.vegetation
            }
            ?.climate
            ?: ClimateStage.generate(config, sea, ocean)

        report(GenerationStage.RIVERS)
        val rivers = reusable
            ?.takeIf {
                it.climate === climate &&
                    it.config.rivers == config.rivers &&
                    // The drawn network is the routing's own answer; see the erosion
                    // guard above.
                    it.config.facetRouting == config.facetRouting &&
                    it.config.flatPotential == config.flatPotential &&
                    // Lakes come out of the same depression fill and live on the river result, so
                    // a lake setting is a river setting as far as reuse is concerned.
                    it.config.lakes == config.lakes
            }
            ?.rivers
            ?: RiverStage.generate(config, sea, climate)

        report(GenerationStage.NATIONS)
        val nations = reusable
            ?.takeIf { it.rivers === rivers && it.config.nations == config.nations }
            ?.nations
            ?: NationStage.generate(config, sea, climate, rivers, ocean)

        report(GenerationStage.CULTURES)
        // Guarded on the rivers rather than on the realms: who lives where does not depend on who
        // rules where, and tying the two would move every people whenever the realm count changed.
        val cultures = reusable
            ?.takeIf { it.rivers === rivers && it.config.cultures == config.cultures }
            ?.cultures
            ?: CultureStage.generate(config, sea, climate, rivers)

        report(GenerationStage.LANDMARKS)
        val landmarks = reusable
            ?.takeIf { it.nations === nations && it.config.landmarks == config.landmarks }
            ?.landmarks
            ?: LandmarkStage.generate(config, sea, climate, rivers, plates, nations)

        return WorldMap(
            config = config,
            terrain = terrain,
            plates = plates,
            erosion = erosion,
            sea = sea,
            ocean = ocean,
            climate = climate,
            rivers = rivers,
            nations = nations,
            cultures = cultures,
            landmarks = landmarks,
            labels = previous?.labels ?: emptyList()
        )
    }

    private fun WorldGenConfig.sameResolutionAndSeed(other: WorldGenConfig): Boolean =
        seed == other.seed && width == other.width && height == other.height
}
