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
 * exist. A save is not the other case: the codec hands back a whole [WorldMap] or refuses the
 * file, so the only [LoadedWorld] with a stage missing is one a test builds.
 *
 * The engine does not need to treat an absent stage as a special case at all. A missing stage is
 * regenerated because there is nothing to reuse, and every stage after the first is guarded on
 * `it.<upstream> === <freshlyChosenValue>` for one stage it is built from, the latest, which is
 * reused only where every earlier one it is built from was. So a `null` here — which can never
 * `===` anything a stage computes — fails the guards that read it exactly as a recomputed (and
 * therefore differently-identified) stage would. "A missing stage forces every
 * stage built from it, directly or through another, to regenerate" falls out of the existing
 * reuse chain for free; a stage not built from it, as the peoples are not built from the realms,
 * is still reused.
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
 * A [PartialWorld] with whichever stages it is given, `null` where it is not given one.
 *
 * Nothing in the program builds one: a save opens as a whole [WorldMap] or not at all. It is never
 * rendered or saved directly — it exists only to be handed to
 * [com.cartogenesis.worldgen.WorldGenerationEngine.generate] as `previous`, which turns it into a
 * complete [WorldMap] by regenerating whatever is missing (and, by the guard chain above,
 * every stage built from it).
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
