package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome

/**
 * What the climate does to the colour of the ground under it.
 *
 * A hypsometric ramp says that this height is that colour, and on a world with more than one
 * climate it is a lie: the green a ramp gives a coastal plain is the green of a wet one, and drawn
 * over the Sahara it puts a lawn on the Tanezrouft. Imhof (1965, *Cartographic Relief Presentation*)
 * settles it the way every good atlas does — the ramp is not one series but a series modulated by
 * what grows there, so a desert is sand at every height, frozen ground is pale, and forest darkens
 * the lowland greens.
 *
 * Three quantities carry that, and each is a fact about the cell rather than a colour:
 *
 *  - **[dryness]**, from the climate. De Martonne's aridity index — the year's rain in millimetres
 *    over the mean temperature plus ten — is the oldest and simplest measure of how far a place is
 *    from having enough water for a closed cover of plants, and its own class boundaries are the
 *    two ends of the ramp here: below 10 the drainage is endorheic and the ground shows through,
 *    above 20 the cover closes over it.
 *  - **The band the biome allows**, which is the part aridity cannot know. A biome is the
 *    classifier's own verdict on what grows at a cell, and each class shows a *range* of ground:
 *    see [bareEarthLeast] and [bareEarthMost]. The index is spent inside that range rather than on
 *    top of it, which is what keeps a dry grassland straw and a desert sand.
 *  - **[canopyClosure]**, also from the biome: how much of the ground is under closed woody cover,
 *    which is what makes a forest darker than the plain beside it rather than merely greener.
 *
 * And one more, from the temperature alone: **[coldness]**, which pales the ground toward the sheet
 * where nothing grows because of the cold rather than the drought.
 *
 * Every one of these is a number between 0 and 1 computed per cell from fields the world already
 * carries, and a style decides through [MapStyle.climateTint] how much of it to let through — the
 * full effect on Atlas, a suggestion on the aged papers, nothing at all on the two styles whose
 * ramps are a promise to the reader.
 */
internal object ClimateTint {

    /**
     * De Martonne's aridity index for a cell: `annual millimetres / (mean °C + 10)`.
     *
     * Undefined at and below -10 °C, where the denominator vanishes; the denominator is held at
     * [COLDEST_DENOMINATOR] there, which reports the driest polar deserts as merely dry and leaves
     * [coldness] to say what is actually happening to them.
     */
    fun aridityIndex(meanAnnualC: Float, annualMm: Float): Float {
        val denominator = meanAnnualC + DE_MARTONNE_OFFSET_C
        return annualMm / (if (denominator < COLDEST_DENOMINATOR) COLDEST_DENOMINATOR else denominator)
    }

    /**
     * How bare the ground is, 0 under a closed cover to 1 on open sand.
     *
     * The climate's own answer and the vegetation's, whichever says the ground shows through more —
     * except in the cold, where the climate's answer is thrown away. De Martonne's index is a
     * statement about drought, and drought is not what is stopping anything growing at minus
     * twenty: an ice cap receives a desert's rainfall and scores as arid, and drawing it as sand
     * would be the same mistake as drawing the Sahara green. [coldness] carries the cold end
     * instead, and the two never both apply to the same cell.
     */
    fun drynessAt(world: WorldMap, cell: Int): Float {
        val biome = world.climate.biome[cell]
        val least = BARE_EARTH_LEAST[biome.ordinal]
        return least + (BARE_EARTH_MOST[biome.ordinal] - least) * droughtAt(world, cell)
    }

    /**
     * The climate's half of it: 1 where the year is arid by De Martonne's line, 0 where it is
     * sub-humid, and 0 wherever the cold rather than the drought is what stops things growing.
     *
     * Separate from [drynessAt] because it is the part that knows nothing about what grows there —
     * the biome's own band is what turns it into a colour.
     */
    fun droughtAt(world: WorldMap, cell: Int): Float {
        val index = aridityIndex(
            world.climate.temperature.data[cell],
            world.climate.precipitationMm.data[cell]
        )
        val drought = 1f - Engraving.smoothstep(DE_MARTONNE_ARID, DE_MARTONNE_SUBHUMID, index)
        return drought * (1f - coldnessAt(world, cell))
    }

    /** How far the cold has taken the ground out of the growing world, 0 to 1. */
    fun coldnessAt(world: WorldMap, cell: Int): Float =
        coldness(world.climate.temperature.data[cell])

    /**
     * 1 where the ground is frozen the year round, 0 where the forest still grows.
     *
     * The two ends are the conventional ones: a mean annual temperature of 0 °C is about where the
     * boreal forest gives way to tundra, and -10 °C is about where an ice cap becomes permanent.
     */
    fun coldness(meanAnnualC: Float): Float =
        1f - Engraving.smoothstep(ICE_CAP_C, TUNDRA_C, meanAnnualC)

    /** How bare this vegetation's ground can get: at its wettest, and at its driest. */
    fun bareEarthLeast(biome: Biome): Float = BARE_EARTH_LEAST[biome.ordinal]

    fun bareEarthMost(biome: Biome): Float = BARE_EARTH_MOST[biome.ordinal]

    /**
     * How much of the ground under this cell is closed woody canopy, 0 to 1.
     *
     * The world's own vegetation density, which is a continuous function of the water balance and
     * the growing season rather than one figure for every cell of a biome — so a woodland thins
     * into a steppe over fifty cells instead of at the one cell where the classifier changed its
     * mind. See [com.cartogenesis.worldgen.pipeline.VegetationDensity].
     *
     * Falls back to [CANOPY_CLOSURE] where the world carries no field, which is
     * `VegetationConfig.enabled` off: that is the picture before the field existed and is the
     * control `ClimateTintTest` measures the graded boundary against.
     */
    fun canopyAt(world: WorldMap, cell: Int): Float =
        if (world.config.vegetation.enabled) {
            world.climate.vegetationDensity.data[cell]
        } else {
            CANOPY_CLOSURE[world.climate.biome[cell].ordinal]
        }

    /** The figure a biome alone would give, which is what [canopyAt] falls back to. */
    fun canopyClosure(biome: Biome): Float = CANOPY_CLOSURE[biome.ordinal]

    /** [canopyAt] for every cell, for the same reason the colour tables are built on the processor. */
    fun canopyField(world: WorldMap): FloatArray =
        FloatArray(world.width * world.height) { canopyAt(world, it) }

    /** [drynessAt] for every cell, for the same reason the colour tables are built on the processor. */
    fun drynessField(world: WorldMap): FloatArray =
        FloatArray(world.width * world.height) { drynessAt(world, it) }

    /** [coldnessAt] for every cell. */
    fun coldnessField(world: WorldMap): FloatArray =
        FloatArray(world.width * world.height) { coldnessAt(world, it) }

    /**
     * Where on a style's land ramp dry ground begins, as a fraction of the way up it.
     *
     * Three sevenths is the fourth of the eight stops every one of these ramps is written with, and
     * on each of them that stop is where the series leaves the colours of vegetation and enters the
     * colours of earth: Atlas's sand, Schoolroom's yellow, Verdant's tan, Mars's ochre. Dry ground
     * starts there and climbs the rest of the ramp from it — compressed rather than clipped, so a
     * desert mountain still reaches the snow line and height still reads as height.
     */
    const val ARID_RAMP_FLOOR: Float = 3f / 7f

    /**
     * How far frozen ground is dragged toward the paper.
     *
     * Imhof's rule for the cold end of a hypsometric series: it loses its colour toward the sheet
     * rather than gaining one of its own. A third of the way is where the ramp's own tint is still
     * legible underneath — much further and every polar coast is the same white, which is the
     * mistake this is drawn to avoid rather than a stronger version of the effect.
     */
    const val COLD_PALING: Float = 0.30f

    /**
     * How far a closed canopy darkens the ground beneath it.
     *
     * A twelfth, which is about a third of the relief's own swing (see [ReliefShading]): enough
     * that a wood is plainly darker ground, short of enough to be mistaken for a hillside in
     * shadow. A forest that reads as terrain rather than as relief is the whole requirement.
     */
    const val CANOPY_DARKENING: Float = 0.12f

    /** The ten in `P / (T + 10)`, in degrees Celsius. De Martonne's own. */
    private const val DE_MARTONNE_OFFSET_C = 10f

    /** The smallest denominator the index is evaluated at, so -10 °C does not divide by zero. */
    private const val COLDEST_DENOMINATOR = 1f

    /** De Martonne's arid limit: below it drainage is endorheic and the cover is broken. */
    private const val DE_MARTONNE_ARID = 10f

    /** And his sub-humid limit, above which a place needs no irrigation and the cover closes. */
    private const val DE_MARTONNE_SUBHUMID = 20f

    /** Mean annual temperatures, in degrees Celsius, for the two ends of [coldness]. */
    private const val TUNDRA_C = 0f
    private const val ICE_CAP_C = -10f

    /**
     * How much bare earth each vegetation leaves showing: the wettest example of it, then the
     * driest.
     *
     * A band rather than a number, and the band is what stops a steppe being drawn as a desert. The
     * classifier has already decided what grows at a cell, and a class has a *range* of ground it
     * can show — the land-cover classes these biomes are named for are defined by exactly that:
     * barren is under a tenth vegetated, open shrubland a tenth to two fifths woody over bare or
     * herbaceous ground, savanna a tenth to three tenths tree cover over grass, grassland mostly
     * closed herb in the growing season and litter and soil in the dry one, closed forest over
     * three fifths canopy. So the aridity index is spent *within* the class's own band rather than
     * on top of it: a wet grassland sits at the green end of a grassland's range and a dry one at
     * the straw end, and neither reaches a desert's ground, because a desert is the class where the
     * ground is nearly all there is.
     *
     * A single number per biome, with nothing bounding how far the index could take a cell, drew
     * the interior of a continent as Sahara. See docs/DESIGN_LEDGER.md, F13, for the figures, and
     * `ClimateTintTest`, which keeps that version as its control.
     */
    private val BARE_EARTH_LEAST = floatArrayOf(
        0f,    // OCEAN
        0f,    // SHALLOW_OCEAN
        0f,    // ICE_SHEET — pale because it is frozen, not because it is bare
        0.1f,  // TUNDRA — frost-shattered ground between the mats
        0f,    // TAIGA
        0f,    // TEMPERATE_FOREST
        0f,    // TEMPERATE_RAINFOREST
        0.05f, // GRASSLAND
        0.2f,  // SHRUBLAND
        0.9f,  // DESERT — bare by the classifier's own definition
        0.1f,  // SAVANNA
        0f,    // TROPICAL_SEASONAL_FOREST
        0f,    // TROPICAL_RAINFOREST
        0.3f,  // ALPINE — rock and scree above the tree line
        0.1f,  // MEDITERRANEAN — maquis over dry ground for half the year
        0f     // MONSOON_FOREST
    )

    private val BARE_EARTH_MOST = floatArrayOf(
        0f,    // OCEAN
        0f,    // SHALLOW_OCEAN
        0f,    // ICE_SHEET
        0.4f,  // TUNDRA
        0.08f, // TAIGA
        0.08f, // TEMPERATE_FOREST
        0.05f, // TEMPERATE_RAINFOREST
        0.5f,  // GRASSLAND — straw and litter at the end of a dry season, not sand
        0.6f,  // SHRUBLAND
        1f,    // DESERT
        0.5f,  // SAVANNA
        0.08f, // TROPICAL_SEASONAL_FOREST
        0.05f, // TROPICAL_RAINFOREST
        0.7f,  // ALPINE
        0.5f,  // MEDITERRANEAN
        0.08f  // MONSOON_FOREST
    )

    /**
     * How much of each vegetation is closed woody canopy, **before** the world carried a density
     * field of its own.
     *
     * The forestry convention: a closed forest is more than three fifths canopy cover and an open
     * one between a tenth and two fifths, so the rainforests sit at one, the seasonal and boreal
     * forests a little below, the savanna and the maquis in the open-forest band, and the grasslands
     * and deserts at nothing.
     *
     * Kept as the fallback [canopyAt] reads with `VegetationConfig.enabled` off, which is the
     * control the graded-boundary guard is shown failing against: every cell of a biome takes the
     * same figure here, so every biome boundary is a step in the drawn ground. See
     * docs/DESIGN_LEDGER.md, W4.
     */
    private val CANOPY_CLOSURE = floatArrayOf(
        0f,    // OCEAN
        0f,    // SHALLOW_OCEAN
        0f,    // ICE_SHEET
        0f,    // TUNDRA
        0.7f,  // TAIGA
        0.85f, // TEMPERATE_FOREST
        1f,    // TEMPERATE_RAINFOREST
        0f,    // GRASSLAND
        0.1f,  // SHRUBLAND
        0f,    // DESERT
        0.15f, // SAVANNA
        0.8f,  // TROPICAL_SEASONAL_FOREST
        1f,    // TROPICAL_RAINFOREST
        0f,    // ALPINE
        0.25f, // MEDITERRANEAN
        0.75f  // MONSOON_FOREST
    )
}
