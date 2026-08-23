package com.cartogenesis.worldgen.model

import kotlinx.serialization.Serializable

import com.cartogenesis.worldgen.pipeline.ClimateResult
import com.cartogenesis.worldgen.pipeline.LandmarkResult
import com.cartogenesis.worldgen.pipeline.NationResult
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

/** Everything a renderer or exporter needs to draw a finished world. */
data class WorldMap(
    val config: WorldGenConfig,
    val terrain: TerrainResult,
    val plates: PlateResult,
    val sea: SeaLevelResult,
    val climate: ClimateResult,
    val rivers: RiverResult,
    val nations: NationResult,
    val landmarks: LandmarkResult,
    val labels: List<MapLabel> = emptyList()
) {
    val width: Int get() = config.width
    val height: Int get() = config.height

    /** Height with tectonics applied, 0..1. */
    val elevation: FloatField get() = plates.height

    /** Elevation relative to the shoreline: positive on land, negative at sea. */
    val relativeElevation: FloatField get() = sea.relativeElevation

    fun isLand(x: Int, y: Int): Boolean = sea.isLand[y * width + x]

    fun landFraction(): Float = sea.landCellCount.toFloat() / (width * height)
}
