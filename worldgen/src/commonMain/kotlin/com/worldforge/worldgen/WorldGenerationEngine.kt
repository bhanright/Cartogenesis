package com.worldforge.worldgen

import com.worldforge.worldgen.model.WorldGenConfig
import com.worldforge.worldgen.model.WorldMap
import com.worldforge.worldgen.pipeline.ClimateStage
import com.worldforge.worldgen.pipeline.LandmarkStage
import com.worldforge.worldgen.pipeline.NationStage
import com.worldforge.worldgen.pipeline.PlateStage
import com.worldforge.worldgen.pipeline.RiverStage
import com.worldforge.worldgen.pipeline.SeaLevelStage
import com.worldforge.worldgen.pipeline.TerrainStage

enum class GenerationStage(val label: String) {
    TERRAIN("Shaping terrain"),
    TECTONICS("Drifting plates"),
    SEA_LEVEL("Flooding oceans"),
    CLIMATE("Simulating climate"),
    RIVERS("Carving rivers"),
    NATIONS("Settling realms"),
    LANDMARKS("Stocking the wilds")
}

fun interface GenerationProgress {
    fun onStage(stage: GenerationStage, stageIndex: Int, stageCount: Int)
}

/**
 * Runs the generation pipeline. Generation is fully deterministic for a given config, which is
 * what lets HD export re-run at a higher resolution instead of upscaling a preview bitmap.
 */
object WorldGenerationEngine {

    private val NO_PROGRESS = GenerationProgress { _, _, _ -> }

    fun generate(
        config: WorldGenConfig,
        previous: WorldMap? = null,
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

        report(GenerationStage.SEA_LEVEL)
        val sea = reusable
            ?.takeIf { it.plates === plates && it.config.seaLevel == config.seaLevel }
            ?.sea
            ?: SeaLevelStage.apply(plates.height, config.seaLevel)

        report(GenerationStage.CLIMATE)
        val climate = reusable
            ?.takeIf { it.sea === sea && it.config.climate == config.climate }
            ?.climate
            ?: ClimateStage.generate(config, sea)

        report(GenerationStage.RIVERS)
        val rivers = reusable
            ?.takeIf { it.climate === climate && it.config.rivers == config.rivers }
            ?.rivers
            ?: RiverStage.generate(config, sea, climate)

        report(GenerationStage.NATIONS)
        val nations = reusable
            ?.takeIf { it.rivers === rivers && it.config.nations == config.nations }
            ?.nations
            ?: NationStage.generate(config, sea, climate, rivers)

        report(GenerationStage.LANDMARKS)
        val landmarks = reusable
            ?.takeIf { it.nations === nations && it.config.landmarks == config.landmarks }
            ?.landmarks
            ?: LandmarkStage.generate(config, sea, climate, rivers, plates, nations)

        return WorldMap(
            config = config,
            terrain = terrain,
            plates = plates,
            sea = sea,
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
