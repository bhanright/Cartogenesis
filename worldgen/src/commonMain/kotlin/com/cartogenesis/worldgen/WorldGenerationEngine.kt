package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import com.cartogenesis.worldgen.pipeline.ErosionStage
import com.cartogenesis.worldgen.pipeline.LandmarkStage
import com.cartogenesis.worldgen.pipeline.NationStage
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.RiverStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage

enum class GenerationStage(val label: String) {
    TERRAIN("Shaping terrain"),
    TECTONICS("Drifting plates"),
    EROSION("Wearing down the mountains"),
    SEA_LEVEL("Flooding oceans"),
    OCEAN("Turning the currents"),
    CLIMATE("Simulating climate"),
    RIVERS("Carving rivers"),
    NATIONS("Settling realms"),
    LANDMARKS("Stocking the wilds")
}

fun interface GenerationProgress {
    fun onStage(stage: GenerationStage, stageIndex: Int, stageCount: Int)
}

/**
 * Runs the generation pipeline.
 *
 * Suspending only because of [ErosionAccelerator]: everything here is ordinary blocking work, but
 * a GPU accelerator has to await its device and its results, so the one call that might do so
 * makes the whole chain suspend. Nothing suspends when generating on the CPU. Generation is fully deterministic for a given config, which is
 * what lets HD export re-run at a higher resolution instead of upscaling a preview bitmap.
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
     */

    suspend fun generate(
        config: WorldGenConfig,
        previous: WorldMap? = null,
        /**
         * Used only when the config asks for it, and only for erosion. Declared before [progress]
         * rather than after so that a trailing lambda at a call site still binds to the progress
         * callback, which is what every caller means by it.
         */
        accelerator: ErosionAccelerator? = null,
        progress: GenerationProgress = NO_PROGRESS
    ): WorldMap {
        val reusable = previous?.takeIf { it.config.sameResolutionAndSeed(config) }

        val stages = GenerationStage.entries
        fun report(stage: GenerationStage) = progress.onStage(stage, stage.ordinal, stages.size)

        report(GenerationStage.TERRAIN)
        val terrain = reusable?.takeIf { it.config.terrain == config.terrain }?.terrain
            ?: TerrainStage.generate(config)

        report(GenerationStage.TECTONICS)
        val plates = reusable
            ?.takeIf { it.terrain === terrain && it.config.tectonics == config.tectonics }
            ?.plates
            ?: PlateStage.generate(config, terrain)

        report(GenerationStage.EROSION)
        val erosion = reusable
            ?.takeIf {
                it.plates === plates &&
                    it.config.erosion == config.erosion &&
                    // Hydraulic erosion routes water against a provisional shoreline, so where the
                    // sea sits changes what gets carved. Guarding on `erosion` alone reused a
                    // stale height field whenever sea level moved.
                    it.config.seaLevel == config.seaLevel
            }
            ?.erosion
            ?: ErosionStage.apply(config, plates.height, accelerator)

        report(GenerationStage.SEA_LEVEL)
        val sea = reusable
            ?.takeIf { it.erosion === erosion && it.config.seaLevel == config.seaLevel }
            ?.sea
            ?: SeaLevelStage.apply(erosion.height, config.seaLevel)

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
            ?: OceanStage.generate(config, sea)

        report(GenerationStage.CLIMATE)
        val climate = reusable
            ?.takeIf { it.ocean === ocean && it.config.climate == config.climate }
            ?.climate
            ?: ClimateStage.generate(config, sea, ocean)

        report(GenerationStage.RIVERS)
        val rivers = reusable
            ?.takeIf {
                it.climate === climate &&
                    it.config.rivers == config.rivers &&
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
            landmarks = landmarks,
            labels = previous?.labels ?: emptyList()
        )
    }

    private fun WorldGenConfig.sameResolutionAndSeed(other: WorldGenConfig): Boolean =
        seed == other.seed && width == other.width && height == other.height
}
