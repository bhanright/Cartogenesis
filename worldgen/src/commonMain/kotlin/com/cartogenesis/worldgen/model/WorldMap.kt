package com.cartogenesis.worldgen.model

import kotlinx.serialization.Serializable

import com.cartogenesis.worldgen.pipeline.ClimateResult
import com.cartogenesis.worldgen.pipeline.CultureResult
import com.cartogenesis.worldgen.pipeline.ErosionResult
import com.cartogenesis.worldgen.pipeline.LandmarkResult
import com.cartogenesis.worldgen.pipeline.NationResult
import com.cartogenesis.worldgen.pipeline.OceanResult
import com.cartogenesis.worldgen.pipeline.PlateResult
import com.cartogenesis.worldgen.pipeline.RiverResult
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.TerrainResult

@Serializable
enum class LabelKind { REGION, SETTLEMENT, MOUNTAIN, WATER, POINT_OF_INTEREST }

@Serializable
data class MapLabel(
    val id: Long,
    val text: String,
    /** Normalized 0..1 map coordinates, so labels survive a change of resolution. */
    val x: Float,
    val y: Float,
    val kind: LabelKind = LabelKind.POINT_OF_INTEREST
)

/**
 * Everything a renderer or exporter needs to draw a finished world.
 *
 * Implements [PartialWorld] — every stage non-null here, by construction — which is what lets a
 * live, fully-generated world be handed straight back to [com.cartogenesis.worldgen.WorldGenerationEngine.generate]
 * as `previous` with no conversion: a `WorldMap` already *is* the complete case of a partial one.
 */
data class WorldMap(
    override val config: WorldGenConfig,
    override val terrain: TerrainResult,
    override val plates: PlateResult,
    override val erosion: ErosionResult,
    override val sea: SeaLevelResult,
    override val ocean: OceanResult,
    override val climate: ClimateResult,
    override val rivers: RiverResult,
    override val nations: NationResult,
    override val cultures: CultureResult,
    override val landmarks: LandmarkResult,
    override val labels: List<MapLabel> = emptyList()
) : PartialWorld {
    val width: Int get() = config.width
    val height: Int get() = config.height

    /** Height with tectonics applied and erosion done, 0..1. */
    val elevation: FloatField get() = erosion.height

    /** Elevation relative to the shoreline: positive on land, negative at sea. */
    val relativeElevation: FloatField get() = sea.relativeElevation

    fun isLand(x: Int, y: Int): Boolean = sea.isLand[y * width + x]

    fun landFraction(): Float = sea.landCellCount.toFloat() / (width * height)
}
