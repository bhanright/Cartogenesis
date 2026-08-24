package com.cartogenesis.worldgen.model

import kotlinx.serialization.Serializable

/** Base terrain: the random gradient ("normal map") field that gets integrated into elevation. */
@Serializable
data class TerrainConfig(
    val octaves: Int = 8,
    val baseFrequency: Int = 4,
    val lacunarity: Float = 2f,
    /**
     * Octave falloff of the *gradient* field, not of the terrain. Integration divides amplitude by
     * frequency, which halves each octave again, so a gain near 1 here is what produces terrain
     * with the classic ~0.5 falloff. Lowering it gives smooth, rolling continents.
     */
    val gain: Float = 1.0f,
    /** Scales slope magnitude before integration. Higher = more dramatic relief. */
    val gradientStrength: Float = 1f,
    /** Blends the integrated height toward a smoothed version. 0 = raw, 1 = very smooth. */
    val smoothing: Float = 0.05f
)

@Serializable
data class TectonicsConfig(
    val plateCount: Int = 14,
    /** Fraction of plates that are oceanic (sit lower). */
    val oceanicFraction: Float = 0.55f,
    /** Height added at continental collision boundaries, in normalized elevation units. */
    val mountainHeight: Float = 0.55f,
    /** Depth of oceanic trenches at subduction boundaries. */
    val trenchDepth: Float = 0.3f,
    /** How far, in cells, boundary effects reach inland. */
    val boundaryFalloff: Float = 26f,
    /** Elevation offset between continental and oceanic plate interiors. */
    val plateElevationBias: Float = 0.35f,
    /**
     * How strongly tectonics dominate the base noise terrain. The plate base is heavily blurred,
     * so pushing this high gives smooth, obviously plate-shaped continents; too low and the plates
     * stop reading as continents at all.
     */
    val tectonicWeight: Float = 0.45f,
    /**
     * Amplitude of the fine relief added on top of the blended terrain. Small enough not to alter
     * the visible shape of the land, large enough to stop flat plains routing water in straight
     * parallel lines.
     */
    val detailAmplitude: Float = 0.012f,
    /** Cycles across the map for that fine relief; also its noise period, so it tiles in X. */
    val detailFrequency: Int = 96,
    /**
     * How much a mountain belt's height varies along its own length.
     *
     * At 0 every convergent boundary rises uniformly for its whole run, which is the single thing
     * that makes plate edges read as drawn on rather than grown. Higher values let belts swell,
     * sag, and break into separate massifs with saddles between them.
     */
    val rangeVariation: Float = 0.88f,
    /**
     * Cycles across the map for that variation — lower means longer, smoother swells.
     *
     * Kept high enough that a long belt breaks into a chain of separate massifs rather than
     * running unbroken from one end to the other. Where such a belt crosses submerged ground that
     * is the difference between a continuous ruler-straight strip of land and an island arc.
     */
    val rangeVariationScale: Float = 13f
)

@Serializable
data class ClimateConfig(
    val equatorTemperatureC: Float = 32f,
    val poleTemperatureC: Float = -28f,
    /** Metres of altitude represented by the full 0..1 land elevation range. */
    val maxAltitudeMetres: Float = 6000f,
    /** Temperature drop per 1000 m of altitude. */
    val lapseRateC: Float = 6.5f,
    /**
     * How much moisture windward slopes wring out of passing air. Raising this deepens rain
     * shadows; push it far above the base rate and mountains take essentially all the rain.
     */
    val orographicStrength: Float = 2.0f,
    /** Baseline rainfall rate over flat land, per cell of travel. */
    val baseRainRate: Float = 0.02f,
    /** How fast air over ocean re-saturates. */
    val evaporationRate: Float = 0.06f,
    /**
     * How strongly the descending air of the horse latitudes suppresses rain, near 30 degrees.
     *
     * This is what decides how much desert a world has, once [landRecoveryRate] has decided where
     * it sits. The two are close to independent: recovery governs whether a rain shadow stays a
     * desert far from the subtropics, this governs how arid the subtropics themselves get.
     */
    val subtropicalDryness: Float = 1.15f,
    /**
     * How fast air over *land* re-moistens, as a share of the deficit per cell travelled.
     *
     * Land is not a desert simply for being downwind of a mountain. Forests and soil return water
     * to the air, and in the warm tropics a large share of the rain that falls is rain that fell
     * before and was given back — the Amazon recycles roughly a third of its own. Without that,
     * orographic depletion is permanent: air wrung out by one range stays wrung out for the rest
     * of the continent, and a rain shadow at the equator becomes a desert on the wettest row of
     * the map.
     *
     * Smaller than [evaporationRate], because land gives back less water than an ocean does.
     */
    val landRecoveryRate: Float = 0.010f
)

@Serializable
data class RiverConfig(
    /**
     * Minimum upstream flow accumulation (as a fraction of total land cells) for a cell to
     * count as a river. Lower = denser river network.
     */
    val sourceThreshold: Float = 0.0006f,
    val maxRivers: Int = 400,
    val minLength: Int = 8
)

/** What happens to land no realm particularly wants. */
@Serializable
enum class WildernessMode(val label: String) {
    /** Realms stop where expansion gets expensive, leaving hostile country unclaimed. */
    LEAVE_WILDERNESS("Leave wilderness"),
    /** Every last cell of land ends up belonging to somebody, so the map reads as finished. */
    CLAIM_ALL_LAND("Claim all land")
}

/** Wind-driven surface currents, and the sea temperature they carry. */
@Serializable
data class OceanConfig(
    val enabled: Boolean = true,
    /** Strength of the wind stress driving the gyres. */
    val forcing: Float = 1.0f,
    /**
     * Grid the stream function is solved on. Gyres are basin-scale, and Jacobi spreads information
     * about one cell per pass, so at full resolution closing a basin would take tens of thousands
     * of passes. A small grid converges properly and costs far less.
     */
    val solveResolution: Int = 128,
    /** Over-relaxation factor. Above 1 converges faster; at or above 2 it diverges. */
    val overRelaxation: Float = 1.7f,
    /**
     * Jacobi sweeps used to solve for the stream function. Too few and basins do not close into
     * gyres; the cost is linear and this stage is a small share of generation either way.
     */
    val relaxationPasses: Int = 3000,
    /** Scales stream-function gradients into cells of travel per advection pass. */
    val speed: Float = 1.6f,
    val advectionPasses: Int = 200,
    /** How much of the upstream temperature a cell takes each pass. */
    val advectionRate: Float = 0.5f,
    /**
     * How strongly water is pulled back toward its latitude's own temperature each pass. Without
     * it a current would carry tropical water all the way to the pole.
     */
    val relaxationRate: Float = 0.02f,
    /**
     * How far inland a coast feels its water, in cells, and how strongly. This is what makes a
     * mild west coast at high latitude and an arid one beside a cold current.
     */
    val coastalReach: Int = 10,
    val coastalInfluence: Float = 0.85f
)

/** Where the erosion sweeps run. */
@Serializable
enum class Acceleration(val label: String) {
    /** Every machine agrees, so a seed and a config are enough to reproduce the world anywhere. */
    CPU("CPU"),
    /**
     * Far faster, and not bit-for-bit reproducible. Graphics hardware rounds differently, fuses
     * multiplies and adds, and may reorder a sum, so the same seed yields terrain that is visually
     * the same world but not numerically the same one. A world generated this way therefore has to
     * carry its terrain in the save rather than rely on being regenerated.
     */
    GPU("GPU")
}

/** Wearing the uplift down: rock fails past a critical slope and piles at the foot. */
@Serializable
data class ErosionConfig(
    val enabled: Boolean = true,
    /**
     * Where the sweeps run. Off the CPU this stage is many times faster, at the cost of the world
     * no longer being reproducible from its seed alone — see [Acceleration].
     */
    val acceleration: Acceleration = Acceleration.CPU,
    /**
     * The critical slope, in elevation per unit of map width — the steepest a slope can stand
     * before it fails. Held per map rather than per cell so that a belt of a given width on the
     * map wears to the same profile whatever grid it is computed on.
     *
     * That keeps the large-scale shape stable across resolutions but not the fine detail, and the
     * reason is worth knowing. Terrain comes from an fBm whose amplitude halves as its frequency
     * doubles, so each finer octave is twice as steep as the one before it, and the steepest
     * detail a grid can resolve gets steeper in proportion to the grid. A fixed critical slope
     * therefore bites into progressively finer detail as the resolution rises: at 512 the finest
     * octave sits near slope 4 and is left alone, at 1024 near 8 and is right at the threshold.
     * This is defensible — real landscapes are erosion-limited at their finest scales too — but it
     * does mean a high-resolution world is not merely a detailed version of a low-resolution one,
     * and it is why this stage does not get cheaper per cell as the map grows.
     *
     * Lower means a gentler, more worn world; high enough and only the knife edges left by uplift
     * are touched. Below about 5 it starts erasing the terrain noise itself and the land goes
     * mushy.
     */
    val talus: Float = 9f,
    /**
     * How many times to sweep the grid. Material moves at most one cell per pass, so this sets how
     * far debris can travel from where it came off — which is why [WorldGenConfig.atResolution]
     * scales it with the grid.
     *
     * Thermal erosion approaches its equilibrium asymptotically, so this is a real question rather
     * than a taste setting, and `ErosionConvergenceTest` reports the curve. Too low and the very
     * feature this stage exists to remove survives: at 18 sweeps the steepest slope left on the map
     * was still ten times the critical angle, meaning the knife edges had barely been touched. By
     * 160 it is down to 1.6. Because the rule only ever moves material that sits above the critical
     * slope, raising this cannot flatten terrain that was already at rest — gentler ground is
     * untouched however long it runs, so the cost of a high count is time and not fidelity.
     */
    val passes: Int = 80,
    /** Share of the material above the critical slope that moves each pass. Above 0.5 it rings. */
    val rate: Float = 0.25f,
    /**
     * How many route-and-incise rounds of hydraulic erosion follow the thermal sweeps.
     *
     * Unlike [passes], this does not scale with the grid. Each round routes the water globally --
     * filling every hollow, finding every downhill path, adding up everything upstream -- so one
     * round already carries information from a watershed's head to its mouth however large the
     * grid is. What more rounds buy is the feedback: a channel cut in one round gathers more water
     * in the next and cuts deeper still, which is what turns a slope into a valley.
     */
    val hydraulicRounds: Int = 12,
    /**
     * How readily running water cuts down, per round.
     *
     * Stream power: incision goes as the square root of the upstream area times the slope, both
     * expressed against the map rather than the grid so the result does not change with
     * resolution. Raising it deepens valleys and sharpens divides; too high and the channels cut
     * to the sea and the land between them is left as unconnected plateaux.
     */
    val erodibility: Float = 0.055f
)

/** Standing fresh water in basins the terrain does not drain. */
@Serializable
data class LakesConfig(
    val enabled: Boolean = true,
    /**
     * How far the filled surface must sit above real ground before a cell counts as under water.
     *
     * Epsilon-filling raises every cell along the flood path by a hair and those increments
     * accumulate over long flats, so this has to clear that noise or most of a continent reads as
     * lake.
     */
    val minDepth: Float = 0.004f,
    /** Smallest lake worth drawing, in cells. Below this it is a puddle, not a feature. */
    val minCells: Int = 12
)

/** Settlement. Everything here is a starting point the user can overrule per realm. */
@Serializable
data class NationsConfig(
    val nationCount: Int = 12,
    /**
     * Whether the world is fully partitioned or keeps unclaimed wilderness. Claiming everything
     * does not redraw the borders between settled regions — cheapest-path assignment gives the
     * same answer either way — it only decides whether the leftovers get divided up too.
     */
    val wilderness: WildernessMode = WildernessMode.CLAIM_ALL_LAND,
    /**
     * How far a realm pushes before it runs out of momentum, relative to the map. Below about
     * 1 the world keeps large tracts of unclaimed wilderness; well above it every cell ends up
     * owned by someone.
     */
    val reach: Float = 2.6f,
    /**
     * How far apart realm origins are forced, as a multiple of the natural spacing for this many
     * realms over this much land. Below 1 they cluster into the best country and leave whole
     * continents unsettled; at or above 1 they spread out to reach them.
     */
    val seedSpacing: Float = 1.0f,
    /** Habitability an origin cell needs. Lower lets realms take root on marginal ground. */
    val minSeedHabitability: Float = 0.18f,
    /** How much harder poor land is to settle than good land. */
    val terrainResistance: Float = 3.5f,
    /** How much a climb costs. This is what pins borders onto mountain ranges. */
    val slopeResistance: Float = 26f,
    /** Extra cost to cross a major river, so realms tend to stop at the near bank. */
    /** Water deeper than this is treated as open ocean and effectively impassable. */
    val navigableDepth: Float = 0.06f,
    /**
     * How much a warm current is worth to the coast it washes. Warm water means an ice-free port
     * and a mild hinterland, which is why Bergen is a city and Labrador is not; a cold current
     * takes the same amount back off.
     */
    val warmHarbourBonus: Float = 0.20f,
    /**
     * How much a cold upwelling on a shallow shelf is worth. Cold water rising over a shelf is
     * where the great fisheries are — the Grand Banks, the Humboldt, the Benguela — so it feeds a
     * coast that its own dry hinterland could not.
     */
    val upwellingFisheryBonus: Float = 0.16f,
    /**
     * Largest catchment left whole, as a share of all land. Anything draining more than this is
     * cut at its confluences, so the pieces are its tributaries.
     *
     * A single river basin can be a fifth of a continent. Left whole, every realm would be
     * enormous and shaped alike; cut too fine and realms become mosaics of scraps with no
     * geography to them.
     */
    val maxBasinShare: Float = 0.020f,
    /** Smallest catchment worth keeping, as a share of all land. Below this it joins a neighbour. */
    val minBasinShare: Float = 0.0035f,
    /**
     * What it costs a realm to take a catchment on the far side of a strait, on the same scale as
     * the quality and appetite terms — so crossing is roughly as dear as claiming poor ground.
     *
     * Realms do settle across narrow water and the model has to let them, or an island never
     * belongs to anyone. But at no cost at all one realm island-hops an entire archipelago.
     */
    val straitCrossingCost: Float = 3.5f,
    /**
     * How much water a river needs before a catchment is cut in two along it, as a share of all
     * land draining through.
     *
     * This is what gives the world its river borders. Without it every frontier is a watershed,
     * because a catchment contains its own river and the water is therefore interior. Real borders
     * are both kinds — the Pyrenees are a divide, the Rio Grande is a river — and a world with only
     * divides is as one-note as a world with neither.
     */
    val riverBorderShare: Float = 0.045f,
    /**
     * Chance that a realm holding several catchments splits in two along one of its own
     * watersheds.
     *
     * A geographic border is drawn by the land; this is the other kind, drawn because the people
     * either side of it stopped agreeing. Most interesting real borders are that kind, and the
     * divide it follows is already there, so it costs nothing to place.
     */
    val schismChance: Float = 0.3f,
    /** People per square kilometre of fully arable land. */
    val peoplePerArableKm2: Double = 38.0,
    /** How wide the world is taken to be, which is what turns cells into an area. */
    val worldWidthKm: Double = 12_000.0
) {
    fun squareKilometresPerCell(width: Int, height: Int): Double {
        val cellWidth = worldWidthKm / width
        val cellHeight = (worldWidthKm / 2.0) / height
        return cellWidth * cellHeight
    }
}

/** Monster lairs, ruins, hazards and the like, scattered through the wild places. */
@Serializable
data class LandmarksConfig(
    val count: Int = 28,
    /**
     * Restricts sites to land no realm claims. Ignored when the world is fully partitioned, since
     * there would then be nowhere at all to put them.
     */
    val wildernessOnly: Boolean = false,
    /** How strongly inhospitable, hard-to-reach country is favoured over settled farmland. */
    val remotenessBias: Float = 1.6f
)

@Serializable
data class WorldGenConfig(
    val seed: Long = 1L,
    val width: Int = 512,
    val height: Int = 512,
    val terrain: TerrainConfig = TerrainConfig(),
    val tectonics: TectonicsConfig = TectonicsConfig(),
    val erosion: ErosionConfig = ErosionConfig(),
    /** Fraction of the world covered by ocean, 0..1. */
    val seaLevel: Float = 0.62f,
    val climate: ClimateConfig = ClimateConfig(),
    val rivers: RiverConfig = RiverConfig(),
    val lakes: LakesConfig = LakesConfig(),
    val ocean: OceanConfig = OceanConfig(),
    val nations: NationsConfig = NationsConfig(),
    val landmarks: LandmarksConfig = LandmarksConfig()
) {
    init {
        require(isPowerOfTwo(width) && isPowerOfTwo(height)) {
            "width/height must be powers of two for the FFT-based height integration (got $width x $height)"
        }
    }

    /**
     * Re-targets the same world at a different grid size â€” used by HD export.
     *
     * Some settings are measured in cells and have to be rescaled, or the world changes character
     * rather than just gaining detail:
     *  - [TectonicsConfig.boundaryFalloff] is the width of a mountain belt and of the blur that
     *    softens the plate base. Left alone, a 4x larger grid makes both four times narrower in
     *    map terms, so plate edges surface as straight cliffs and coastlines turn angular.
     *  - [ClimateConfig.baseRainRate] is charged per cell of wind travel, so a 4x wider grid
     *    depletes moisture four times over the same journey and parches every interior.
     *  - [ErosionConfig.passes] moves material one cell per sweep, so covering the same distance
     *    across the map takes proportionally more sweeps on a finer grid. Left alone, a large map
     *    would come out barely eroded at all.
     *  - [NationsConfig.slopeResistance] is charged against the climb between adjacent cells. That
     *    climb halves as cells halve, so the total cost of crossing a range stays flat while the
     *    expansion budget grows with the map â€” mountains would stop holding borders.
     *
     * Anything expressed as a frequency, or as a fraction of the whole world, already scales.
     */
    fun atResolution(newWidth: Int, newHeight: Int): WorldGenConfig {
        val scale = newWidth.toFloat() / width
        return copy(
            width = newWidth,
            height = newHeight,
            tectonics = tectonics.copy(boundaryFalloff = tectonics.boundaryFalloff * scale),
            erosion = erosion.copy(passes = (erosion.passes * scale).toInt()),
            climate = climate.copy(baseRainRate = climate.baseRainRate / scale),
            nations = nations.copy(slopeResistance = nations.slopeResistance * scale)
        )
    }

    companion object {
        fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0
    }
}
