package com.cartogenesis.worldgen.model

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

/**
 * A world as far as [com.cartogenesis.worldgen.WorldGenerationEngine.generate] needs one to reuse
 * what it can: every stage result is here if it is available to reuse, and absent (`null`) if it
 * is not.
 *
 * [WorldMap] — every stage present — satisfies this directly, which is what lets a live,
 * fully-generated world be handed back to the engine as `previous` exactly as before; nothing
 * about the ordinary "settings changed, reuse what still matches" path had to change for this to
 * exist. The other case is a save missing an array a newer build added: the loader has real data
 * for the stages a section survived for and nothing for the rest, and hands that mixture over as a
 * [LoadedWorld] rather than refusing to open the file.
 *
 * The engine does not need to treat an absent stage as a special case at all. Each stage's guard
 * already reads `it.<stage> === <freshlyChosenValue>` before trusting the next one downstream, so
 * a `null` here — which can never `===` anything a stage computes — fails that guard exactly as a
 * recomputed (and therefore differently-identified) stage would. "A missing stage forces
 * everything after it to regenerate" falls out of the existing reuse chain for free.
 */
interface PartialWorld {
    val config: WorldGenConfig
    val terrain: TerrainResult?
    val plates: PlateResult?
    val erosion: ErosionResult?
    val sea: SeaLevelResult?
    val ocean: OceanResult?
    val climate: ClimateResult?
    val rivers: RiverResult?
    val nations: NationResult?
    val cultures: CultureResult?
    val landmarks: LandmarkResult?
    val labels: List<MapLabel>
}

/**
 * A [PartialWorld] reconstructed from a save: present where a stage's sections survived in the
 * file, `null` where they did not.
 *
 * This is the shape [com.cartogenesis.cartography.WorldSections.rebuild] returns. It is never
 * rendered or saved directly — it exists only to be handed to
 * [com.cartogenesis.worldgen.WorldGenerationEngine.generate] as `previous`, which turns it into a
 * complete [WorldMap] by regenerating whatever is missing (and, by the guard chain above,
 * everything downstream of it).
 */
data class LoadedWorld(
    override val config: WorldGenConfig,
    override val terrain: TerrainResult? = null,
    override val plates: PlateResult? = null,
    override val erosion: ErosionResult? = null,
    override val sea: SeaLevelResult? = null,
    override val ocean: OceanResult? = null,
    override val climate: ClimateResult? = null,
    override val rivers: RiverResult? = null,
    override val nations: NationResult? = null,
    override val cultures: CultureResult? = null,
    override val landmarks: LandmarkResult? = null,
    override val labels: List<MapLabel> = emptyList()
) : PartialWorld
