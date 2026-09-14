package com.cartogenesis.worldgen.model

import kotlinx.serialization.Serializable

/**
 * How big the world is, and how long a round of erosion lasts: the only place a physical unit is
 * declared.
 *
 * Every stage that needs metres, kilometres or years reads them from here and converts to the grid
 * where it uses them. Nothing else in the pipeline may hold a metre of its own — before this
 * section existed, one unit of land elevation was 6,000 m in the climate and about 8,000 m in the
 * sea-level and erosion constants, and the sea had no depth at all, so a hypsometric curve had to
 * carry the land's ruler past the shoreline. `UnitsTest` is what keeps that from coming back.
 *
 * The vertical range is two numbers rather than one because the shoreline is where the map's two
 * halves meet: [SeaLevelResult.relativeElevation] runs 0..1 from the shoreline to the highest land
 * and -1..0 from the shoreline to the deepest floor, each side normalised against its own range. So
 * the ruler is piecewise, with a knot at zero — [metresAboveShoreline] above it and
 * [metresBelowShoreline] below.
 *
 * There is a third reading, and which one a figure takes is decided by where the figure is spent.
 * A quantity that is a *level in the raw height field* rather than a height above the water or a
 * depth below it — the sea's own stand, or a depth measured down from the shoreline into the
 * elevation the erosion stages work on — takes [fieldShareOfMetres], the whole
 * [reliefSpanMetres]. That distinction is not pedantry: the two halves of the piecewise ruler are
 * measured against ranges the world produces, which differ by seed and by grid, while the field's
 * is declared and does not.
 */
@Serializable
data class WorldScale(
    /**
     * How wide the world is taken to be, which is what turns cells into kilometres and an area.
     *
     * The map is an equirectangular projection of a whole world, so it covers 360 degrees of
     * longitude against 180 of latitude and is twice as wide as it is tall — hence
     * [WORLD_HEIGHT_AS_SHARE_OF_WIDTH]. A cell is not square in kilometres unless the grid is too.
     * Earth's equator is 40,075 km; 12,000 km is a smaller world, and the one every knob in this
     * file is calibrated against.
     */
    val worldWidthKm: Double = 12_000.0,
    /**
     * The altitude of the highest land, in metres: the top of the land's half of the ruler.
     *
     * A cell mean and not a summit. A cell of the default 512 grid is 23 km by 12 km, and no cell
     * that size holds Everest's 8,849 m — a summit is a point. The highest ground a cell this
     * coarse can hold is a plateau: Tibet's interior averages 5,023 m (Fielding, Isacks, Barazangi
     * & Duncan, *How flat is Tibet?*, Geology 22, 1994) and the Karakoram-Himalaya cells above it
     * a little more, so 6,000 m is where a 23 km cell tops out.
     *
     * This is the figure the climate has always used for the lapse rate, and choosing it as the one
     * ruler is why every temperature on the map is where it was. The 8 km the sea-level and erosion
     * constants assumed was Everest, which is to say a summit; see `REALISM_PLAN.md`, S1.
     */
    val highestLandMetres: Float = 6_000f,
    /**
     * The depth of the deepest sea floor, in metres: the bottom of the sea's half of the ruler.
     *
     * A cell mean on the same terms, but a trench survives a cell mean far better than a summit
     * does, because a trench is a line where a peak is a point: the Mariana axis holds below 10 km
     * for hundreds of kilometres along strike, so a cell laid along it loses little of the
     * Challenger Deep's 10,935 m. Ten kilometres is that figure less the cell's share of the trench
     * walls.
     *
     * Before this existed the sea had no depth: below the shoreline `relativeElevation` was
     * normalised to whatever the deepest cell happened to be, so the only way to read a depth in
     * metres was to carry the land's ruler downward, which put the deepest floor exactly as far
     * below the water as the highest summit stood above it.
     */
    val deepestOceanMetres: Float = 10_000f,
    /**
     * How long one hydraulic round stands for, in years.
     *
     * Derived rather than chosen, which is why it is not a round number. Stream-power incision is
     * `E = K * A^m * S^n` with m near 0.5 and n near 1, and K in m^(1-2m)/yr — for bedrock rivers
     * 10^-6 to 10^-5 (Whipple & Tucker, *Dynamics of the stream-power river incision model*, JGR
     * 104, 1999; Lague, *The stream power river incision model*, ESPL 39, 2014, for the range
     * across lithologies). Fix K at the bottom of that band, in
     * [ErosionConfig.bedrockErodibilityPerYear], and the time step is whatever makes a round remove
     * what a round removes today: see [ErosionConfig.bedrockErodibilityPerYear] for the arithmetic.
     *
     * Twelve rounds of it is 1.51 million years, which is the right order for the time a mountain
     * belt takes to reach a steady state between uplift and erosion — Whipple and Tucker put the
     * response time of an orogen at 10^5 to 10^6 years for erodibilities in this band — and a
     * reassuring answer to a question the generator could not previously be asked.
     *
     * S1 reached 336,476.4 years by solving an expression with a term too many in it. The term was
     * `highestLandMetres / reliefSpanMetres`, which S1 needed while the height field was
     * renormalised to its own extremes and which cancels now that S2 has made the field an absolute
     * altitude — see
     * [com.cartogenesis.worldgen.pipeline.HydraulicErosion.Rates.incisionCoefficient] — and it was
     * worth a factor of 2.67. So one of the two figures S1 fixed had to give: either this one comes
     * down to 0.375 of what S1 wrote and every world stays exactly where it is, or the coefficient
     * goes up by 2.67 and the rounds do 2.67 times the geomorphic work in the four million years
     * S1 declared.
     *
     * This one, and the other was built and measured before it was refused. Twelve rounds at 2.67
     * times the cut do not dissect this landscape more; they wear it away. Measured on the four
     * standard worlds, the valleys came out *shallower* against their own terrain — 0.019 against
     * `ValleyIncisionTest`'s bar of 0.059, where holding the cut gives 0.047 — the coastline's
     * box-counting dimension fell to 1.01-1.10 on every seed, under Mandelbrot's floor, because a
     * coast worn for four million years is a smooth coast, and the largest lake and the count of
     * undrained cells both went up rather than down. A cut spent faster than the uplift feeding it
     * does not sharpen a landscape, and this one already removes 0.36 mm/yr, which is Earth's own
     * order for an orogen.
     *
     * So the world is exactly the world it was and what changed is the label on the clock: a round
     * is 126,179 years rather than 336,476. It re-dates every erosion figure the project has
     * recorded, and the one that had to move with it is
     * `TectonicsConfig.collisionUpliftMmPerYear` — a rate per year against a denudation per year,
     * both of them now measured over a span two and two-thirds shorter.
     *
     * Written to a hundredth of a year, which is not precision anybody could defend about a
     * landscape: it is the figure at which the coefficient the stage computes lands on the same
     * float it has always held. A round of erosion is chaotic in its own last bit —
     * `ErosionConfig.outletIncisionRatio` records the largest lake on a seed jumping by a factor of
     * two between neighbouring rates — so a rate that is a millionth off is a different world.
     */
    val yearsPerHydraulicRound: Double = 126_178.65
) {

    /**
     * The altitude, in metres, of a **land** cell standing at [relativeElevation].
     *
     * The land's half of the ruler. A land cell can stand below the waterline — ice carves troughs
     * into ground the coastline has already been drawn around, and a drowned basin's outlet is cut
     * below it — and such a cell keeps the land's scale rather than crossing to the sea's, because
     * which half a cell belongs to is a question about `SeaLevelResult.isLand` and not about the
     * sign of a float.
     */
    fun metresAboveShoreline(relativeElevation: Float): Float =
        relativeElevation * highestLandMetres

    /** The altitude, in metres and so negative, of a **water** cell at [relativeElevation]. */
    fun metresBelowShoreline(relativeElevation: Float): Float =
        relativeElevation * deepestOceanMetres

    /**
     * The altitude in metres of a cell whose side of the shoreline the caller does not know, read
     * off whichever half of the ruler the sign points at.
     *
     * For converting a *constant* rather than a cell: a depth written as a negative number of
     * metres, or a height as a positive one, lands on the right half without the caller saying so.
     * A stage walking a grid should use [metresAboveShoreline] or [metresBelowShoreline] and let
     * `isLand` decide, for the reason the first of those gives.
     */
    fun metresAtRelativeElevation(relativeElevation: Float): Float =
        if (relativeElevation >= 0f) metresAboveShoreline(relativeElevation)
        else metresBelowShoreline(relativeElevation)

    /** [metres] of altitude as a share of the land's relief above the shoreline. */
    fun reliefShareOfMetres(metres: Float): Float = metres / highestLandMetres

    /** [metres] of depth as a share of the sea's own range below the shoreline. */
    fun depthShareOfMetres(metres: Float): Float = metres / deepestOceanMetres

    /**
     * [metres] as a share of the **raw height field**, whose whole 0..1 is [reliefSpanMetres].
     *
     * The third conversion, and the one a stage needs when the quantity it is spending straddles
     * the shoreline or is a level in the field rather than a height above or a depth below the
     * water. The sea's own stand is the case: dropping the shoreline 120 m is moving a level in
     * the field, and reading it off either half of the piecewise ruler would multiply it by that
     * half's own measured range — which is a different number on every seed and at every grid, and
     * is the resolution dependence this chunk exists to end.
     */
    fun fieldShareOfMetres(metres: Float): Float = metres / reliefSpanMetres

    /**
     * The metres one unit of the *raw* height field is worth, which is the whole world's relief.
     *
     * The field the terrain and erosion stages work in runs 0..1 between the deepest floor and the
     * highest land, so its span is by construction the two figures above added together. This is
     * the ruler a stage has to use when it is working before the shoreline exists — the thermal
     * sweeps and the stream-power incision both do.
     *
     * Since S2 the field is an *absolute* altitude and not a normalisation: [altitudeAtField] and
     * [fieldAtAltitude] are exact inverses and the shoreline stands at [shorelineFieldLevel],
     * because isostasy gives the two crusts their levels in metres and nothing divides by a
     * measured range any more. Before that the field was renormalised to its own extremes after
     * every generation, so the same 120 m meant a different level on every seed; `UnitsTest`
     * measured the disagreement at 0.64x to 1.09x of the declared ruler and S2 closed it.
     */
    val reliefSpanMetres: Float get() = highestLandMetres + deepestOceanMetres

    /**
     * Where the shoreline stands in the raw height field: the level worth an altitude of zero.
     *
     * The field's floor is [deepestOceanMetres] below the water and its ceiling
     * [highestLandMetres] above it, so the waterline is that much of the way up — 0.625 at the
     * stock figures. This is a *declaration* about the field, and since S2 it is also true of it:
     * the plate stage builds the field from altitudes rather than normalising it, so a cell above
     * this level is above the sea by construction rather than by percentile.
     */
    val shorelineFieldLevel: Float get() = deepestOceanMetres / reliefSpanMetres

    /** The raw height field's value for an altitude of [metres] above (or below) the waterline. */
    fun fieldAtAltitude(metres: Float): Float = (metres + deepestOceanMetres) / reliefSpanMetres

    /** The altitude in metres, above the waterline, of a raw height field standing at [field]. */
    fun altitudeAtField(field: Float): Float = field * reliefSpanMetres - deepestOceanMetres

    /**
     * How much of the raw height field the land's half of the ruler occupies, and the sea's.
     *
     * The two conversions `SeaLevelStage` divides by to turn an altitude into
     * `SeaLevelResult.relativeElevation`. Declared rather than measured since S2: the land's half
     * runs 0..1 from the waterline to [highestLandMetres] whether or not any cell of a given world
     * reaches that high, which is what makes [metresAboveShoreline] exactly true instead of
     * approximately so.
     */
    val landHalfOfField: Float get() = highestLandMetres / reliefSpanMetres
    val seaHalfOfField: Float get() = deepestOceanMetres / reliefSpanMetres

    /** How wide one cell is, in kilometres, on a grid [cellsAcross] cells wide. */
    fun cellWidthKm(cellsAcross: Int): Double = worldWidthKm / cellsAcross

    /** How tall one cell is, in kilometres, on a grid [cellsDown] cells tall. */
    fun cellHeightKm(cellsDown: Int): Double =
        worldWidthKm * WORLD_HEIGHT_AS_SHARE_OF_WIDTH / cellsDown

    /** How much ground one cell stands for, which is what turns a cell count into an area. */
    fun squareKilometresPerCell(cellsAcross: Int, cellsDown: Int): Double =
        cellWidthKm(cellsAcross) * cellHeightKm(cellsDown)

    /**
     * [kilometres] as a count of cells across a grid [cellsAcross] cells wide.
     *
     * This is the conversion that retired [WorldGenConfig.atResolution] for every reach, radius and
     * width in the pipeline: a length on the ground is more cells on a finer grid, and saying so
     * once here is arithmetic where carrying it by hand through a rescaling function was a contract.
     */
    fun cellsAcrossFor(kilometres: Double, cellsAcross: Int): Float =
        (kilometres * cellsAcross / worldWidthKm).toFloat()

    /** The whole world's surface in square kilometres, land and sea alike. */
    val worldAreaKm2: Double get() = worldWidthKm * worldWidthKm * WORLD_HEIGHT_AS_SHARE_OF_WIDTH

    companion object {
        /** Pole to pole against the equator's whole circumference, on an equirectangular map. */
        const val WORLD_HEIGHT_AS_SHARE_OF_WIDTH = 0.5

        /** Metres in a kilometre, so no stage has to write the conversion out. */
        const val METRES_PER_KM = 1_000f
    }
}

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
    val smoothing: Float = 0.05f,
    /**
     * The wavelength, in kilometres, at which the base relief is loudest — and above which it is
     * taken out of the surface altogether.
     *
     * Four hundred kilometres, and the derivation is a division of labour. Since S2 the broad
     * shape of the ground is the crust's: where a continent stands and where the sea floor lies is
     * [IsostasyConfig]'s answer, from two densities and an age, and the base noise's job is only
     * the relief *within* those. On Earth that relief is organised at a few hundred kilometres —
     * the spacing of the Great Plains' major divides, the wavelength of the Brazilian and East
     * African epeirogenic swells, the width of a basin-and-range province — while everything
     * broader than about a thousand kilometres is crustal thickness rather than topography
     * (Watts 2001, chapter 5, on the spectral separation of the two). Two hundred to a thousand
     * kilometres is that band; 400 is where inside it this map's two hypsometric modes stay apart
     * on every standard seed *and* its coastline keeps Mandelbrot's dimension, which are the two
     * measurements the choice trades against and which pull opposite ways — a shorter wavelength
     * makes a rougher coast and drowns more of the platform in a shallow fringe that fills the
     * trough back in, a longer one does the reverse. Measured across 250, 300, 350, 375, 400, 425
     * and 500 km on the five standard worlds; 400 is the only figure at which all five carry a
     * trough, all five put their sea mode inside Earth's, and all five keep a coastline dimension
     * above the floor.
     *
     * Before this, the noise carried both, and the broad half of it fought the crust and won: an
     * fBm slope field integrates to a surface whose power grows as its wavelength, so the loudest
     * thing in it was a map-wide tilt of several kilometres, which smeared the two hypsometric
     * modes back together. S2's first pass answered that by scaling the whole field down to 2,000 m
     * peak to peak, which separated the modes and cost the map its texture — 300 m of modulation
     * on a four-kilometre belt, a smooth pale tongue where main had a dissected range. Filtering
     * the broad half out instead keeps both: the modes stay apart because the map-scale component
     * is gone, and the mid-band relief the eye reads is louder than it has ever been.
     *
     * Zero switches the filter off, which is the unshaped `1/k` surface and the control
     * `TerrainSpectrumTest` measures the shaped one against.
     *
     * See [TerrainStage.ReliefBand] for the filter and
     * [TectonicsConfig.continentalReliefStandardDeviationMetres] for how loud the band is.
     */
    val reliefCornerKm: Double = 400.0,
    /**
     * How much of the map-scale relief survives the filter above, as a share of what it would have
     * carried unfiltered.
     *
     * A floor on [reliefCornerKm]'s response, and it exists because a continent needs a *little*
     * regional slope even after the crust has taken over the broad shape. Drainage is organised by
     * the ground's longest wavelengths: what makes the Mississippi, the Ob and the Parana is a
     * continental interior that tilts one way for two thousand kilometres, and a surface with no
     * component at that scale grows a great many short rivers instead of a few long ones. Measured,
     * a hard first-order high pass took the weighted mean bifurcation ratio from 4.63 to 5.82,
     * outside Horton's 3 to 5, by halving the count of third- and fourth-order streams: the
     * catchments stopped merging.
     *
     * A sixth, and the floor is small because the unfiltered amplitude at that scale is enormous:
     * integration makes it grow as the wavelength, so the map's own width carries fifteen times
     * what the corner does, and a sixth of that is two and a half times the corner's own amplitude.
     *
     * S2's second pass took a tenth and measured that as where the bifurcation ratio stopped
     * falling. It is not enough, and what the ratio could not see the eye could: with a tenth the
     * ground between the belts has no slope worth the name, so the water ponds where it falls and
     * the sea-level cut lands on a platform flat enough to drown into an archipelago. Rendered at
     * 2048, 718106's southern half was a maze of inlets and islands and both worlds were pocked
     * with small lakes.
     *
     * Measured over 0.08, 0.12, 0.16 and 0.20 on the five standard worlds at 512, 0.16 is where
     * the drainage is Earth's and the coast is still a coast. The lake share of land falls from
     * 3.55% at a tenth to 1.73% against Earth's 1.48% at this cell area; the drainage density
     * rises from 0.0023 to 0.0029 km/km2 against the 0.0026 the tree before S2 measured, where
     * the bar is a fifth either way and 0.0031 is the ceiling; the drawn rivers go from 160 to
     * 164 against that tree's 156; and the coastline's box dimension reads 1.129, 1.124, 1.134,
     * 1.172 and 1.186 on the five seeds, every one of them inside Mandelbrot's band. Above a
     * sixth the coastline goes — a map-scale tilt moves the shoreline bodily — and at 0.20 two of
     * the five seeds fall under the floor while the density overshoots at 0.0031; below it the
     * lakes come back, 2.3% at 0.12 and 2.9% at 0.08.
     *
     * A continental interior swell of Bond's own amplitude and wavelength, windowed onto the crust,
     * was built and measured as the physically better answer and removed again: over 400, 800 and
     * 1,200 m it moved the bifurcation ratio by less than the seeds differ from each other. The
     * long slopes drainage needs turn out to be the ones that run the whole way across a map, not
     * the ones that fit inside a continent.
     */
    val regionalReliefShare: Double = 0.16
)

@Serializable
data class TectonicsConfig(
    val plateCount: Int = 14,
    /**
     * How much of a plate's continental crust stands under water, as a share of its area — which
     * is what turns the ocean-coverage slider into a count of continental plates.
     *
     * Continental crust is not the same thing as land. Earth's continental crust covers 41.2% of
     * the surface and its land 29.2% (Cogley, *Continental margins and the extent and number of
     * the continents*, Rev. Geophys. 22, 1984), so 29% of the continents are drowned — the shelves,
     * the banks and the shallow seas. `PlateStage` reads it backwards: to put `1 - seaLevel` of the
     * world above water it has to draw `(1 - seaLevel) / (1 - this)` of it as continental crust.
     *
     * That is the whole of what the ocean-coverage slider now does to the tectonics, and it is why
     * there is no longer a plate-count fraction here: which plates are continental is chosen by
     * *area* until the target is met, so a world of fourteen plates of unequal size lands on the
     * share asked for rather than on the nearest whole plate.
     *
     * **A fifth, which is this model's own figure and not Earth's 29%.** The number has to be the
     * one the model actually drowns or the sea-level cut cannot land on the isostatic datum, and
     * where it lands is not a tidiness question: every metre the cut stands above the datum is a
     * metre of continental platform put under shallow water, and enough of them smear the two
     * hypsometric modes back into one. At Earth's 0.291 the crust drew more continent than the
     * slider asked for — 0.43 to 0.49 of the world above the datum against the 0.38 wanted, so the
     * cut came up 131 to 323 m to meet it — and the fringe that drowned was the busiest band on the
     * map: three of the five standard worlds then had no hypsometric trough at all. At 0.20 the
     * crust lands on the coverage asked for, all five carry a trough, and all five put their sea
     * mode within Earth's tolerance of -3,700.
     *
     * The gap to Earth's 29% is a finding and it has a name. This generator's continents drown a
     * fifth of themselves where Earth's drown three tenths because it has no epicontinental seas —
     * no Hudson Bay, no Baltic, no North Sea, no Sunda shelf — since nothing in the model floods a
     * continent's interior, which is a question about how crustal thickness varies *inside* a
     * plate. In `TODO.md`.
     *
     * S2's first pass measured 0.12 here and recorded that taking it cost the coastline everything:
     * the shoreline then sat on the margin's own slope, where the ground falls hundreds of metres a
     * cell, and the four standard seeds carried 1, 1, 2 and 4 islands between them with a
     * box-counting dimension of 1.05. That reading was true of the surface it was taken on — a base
     * relief of 2,000 m peak to peak whose loudest component was the width of the map. With the
     * relief shaped into a 400 km band instead ([TerrainConfig.reliefCornerKm]) and given Earth's
     * own spread ([continentalReliefStandardDeviationMetres]), the platform's edge carries enough
     * topography of its own that a shoreline standing on it is still a coastline: 1.105 to 1.177
     * across the five worlds, inside Mandelbrot's band.
     */
    val continentalCrustSubmergedShare: Float = 0.20f,
    /**
     * What one unit of every belt height below is worth, in metres.
     *
     * The profiles in this section are shapes: a plateau is flat across 60% of its half-width, a
     * margin's arc stands 0.20 where its range stands 0.52. Until S2 the field they were stamped
     * into was renormalised to its own extremes afterwards, so those numbers had no vertical scale
     * at all and `WorldGenConfig.atResolution` had to carry a belt's width and its height together
     * or not at all. Isostasy gives the field an absolute scale, so the shapes get one too, and
     * this is it: one multiplier for the lot, which keeps every ratio `BoundaryPairTest` measures
     * exactly where B2 left it.
     *
     * Thirteen thousand metres, and the derivation is the plateau. A continental collision stamps
     * [collisionHeight] times the pair's convergence times its along-strike swell: the strongest
     * pair on a map reaches about 0.55 of this scale and a typical one about 0.15, so the tallest
     * plateau a world draws stands near 7,000 m before erosion and an ordinary one near 2,000.
     * Tibet's interior averages 5,023 m (Fielding, Isacks, Barazangi & Duncan, *How flat is
     * Tibet?*, Geology 22, 1994) and the Altiplano 3,800, above forelands near 500 — so the
     * strongest collisions on this map are Tibet with room above it for the twelve rounds of
     * erosion that follow, and the weakest are worn uplands. It is also, to within a percent, the
     * scale the normalised field carried before it was declared: the old field spanned about 1.2
     * raw units and stood for [WorldScale.reliefSpanMetres], which is 13,300 m per raw unit.
     */
    val beltReliefMetres: Float = 13_000f,
    /**
     * Height added at continental collision boundaries, as a share of [beltReliefMetres].
     *
     * Only the one-profile control reads it; see [crustPairProfiles].
     */
    val mountainHeight: Float = 0.55f,
    /** Depth of oceanic trenches at subduction boundaries, on the same scale. */
    val trenchDepth: Float = 0.3f,
    /** How far, in cells, boundary effects reach inland. */
    val boundaryFalloffCells: Float = 26f,
    /**
     * How wide the band is over which one crust becomes the other, in kilometres — a continental
     * margin, measured from where the crust starts to thin to where it is ocean floor.
     *
     * Implicit before it was a setting: the plate base was blurred by a third of
     * [boundaryFalloffCells], which comes to about 200 km on the default grid and was never a
     * length anybody had chosen. Since isostasy puts 4,500 m between the two crusts, the width of
     * this band *is* the gradient the continental slope stands at — 300 km is 350 m in every cell
     * of a 512 grid, 600 km is 175 — so it wanted a figure of its own and a reason for it.
     *
     * Three hundred kilometres, from the bathymetry rather than from the crust. Earth's margin has
     * three parts and they are measured separately: a shelf averaging 78 km (Cogley,
     * *Continental margins and the extent and number of the continents*, Rev. Geophys. 22, 1984),
     * a slope of 20 to 100 km falling from the 130 m break to three or four kilometres, and a rise
     * of 100 to 300 km out to the abyssal plain. Two hundred to four hundred and fifty kilometres
     * is the sum of those, and 300 is the middle of it. Watts (2001) measures the *crustal*
     * thinning — 40 km of crust to 10 — over a wider band, 200 to 500 km, but what this setting
     * governs is where the ground is, and the ground finishes its fall at the foot of the rise.
     *
     * Six hundred was S2's first pass, on Watts's broad end plus the widest shelves. Measured on
     * the five standard worlds it is worth 0.06 of hypsometric trough — the wider band spreads
     * three times as much drowned platform through the shallowest band and fills the gap between
     * the two modes back in — and about +0.01 of coastline dimension, which is the wrong trade.
     */
    val crustMarginKm: Double = 300.0,
    /**
     * How far the boundary between the two crusts wanders inside its own margin, as a share of
     * that margin's width.
     *
     * Zero draws it where the blur puts it, which is a smooth ramp, and a coastline standing on
     * a smooth ramp is a smooth curve. Earth's margins are not smooth either: they are offset by
     * transform faults every few hundred kilometres, embayed where a rift arm failed, and cut into
     * banks and troughs by everything that has poured off them since — Georges Bank, the Blake
     * Plateau, the Niger and Amazon fans, the Agulhas and Falkland plateaus. A third of the
     * margin's width is the order of those.
     *
     * Measured, it is worth about +0.02 on the coastline's box-counting dimension and a little
     * more drowned margin, which is honest rather than impressive: what decides that measurement
     * is where the shoreline sits relative to the crust, not how the crust's own edge wanders.
     * See [continentalCrustSubmergedShare].
     */
    val marginRoughness: Float = 0.35f,
    /**
     * How much relief the base noise carries at a continental margin and on the sea floor — the
     * standard deviation of the ground the belts and the isostatic levels are laid on, in metres.
     *
     * The margin's rather than the whole continent's since S2's fourth pass: the interior carries
     * [cratonReliefStandardDeviationMetres] instead and the two are joined by [cratonReachKm].
     *
     * A standard deviation and not a peak-to-peak range since S2's second pass, because the field
     * these scale is no longer a smooth ramp with two ends. [TerrainConfig.reliefCornerKm] filters
     * the map-scale component out of it, and what is left is a band of relief whose extremes are
     * two outlying cells and whose spread is the thing worth naming. `PlateStage` standardises the
     * field to zero mean and unit deviation before it multiplies by these, so the figure is the
     * same statement at 512 and at 4096, which a peak-to-peak of a filtered field is not.
     *
     * These two numbers are also what keeps the hypsometry bimodal, and getting them wrong is what
     * made it unimodal for the whole life of the generator. Earth's two modes stand 4,500 m apart
     * (a continental platform near +800 m against a sea floor near -3,700), so relief with more
     * spread than that in it smears the two together into one peak straddling the shoreline —
     * which is exactly what M1 measured: the busiest land band and the busiest sea band adjacent,
     * no trough at all, and 0.52 of the surface in the two modal bands against Earth's 0.85.
     *
     * Seven hundred metres on the continents, which is the spread of Earth's own continental crust
     * away from its orogens. The shelves sit at -30 to -130 m, the coastal plains between 0 and
     * 200, the shields between 0 and 500 (the Canadian, Baltic and West African), the platforms
     * between 200 and 800 (the Russian and North American), and the high plains and epeirogenic
     * plateaus between 1,000 and 1,700 (the Great Plains, the Brazilian and East African
     * highlands). A distribution over -130 to +1,700 with its mass in the middle has a standard
     * deviation near 700 m, and Earth's land elevations as a whole — orogens included — stand at
     * about 900. Nothing outside an orogen stands higher than the high plains, and the orogens are
     * the belts' business: see [orogenReliefStandardDeviationMetres].
     *
     * It is also what makes the continents drown, and *where* they drown. A platform floating at
     * 840 m with 700 m of spread on it everywhere puts a fifth of itself under water wherever the
     * noise happens to dip, interior included, which is the flooded continent William named in
     * S2's third pass; with the spread falling to
     * [cratonReliefStandardDeviationMetres] inland the same fifth drowns at the rim, which is
     * where Earth's is. [continentalCrustSubmergedShare] carries what the model actually drowns
     * and is what the sea-level cut is solved against.
     *
     * Two hundred and fifty on the sea floor. Abyssal hills carry 50 to 300 m of relief with a
     * spacing of two to eight kilometres (Goff & Jordan, *Stochastic modeling of seafloor
     * morphology*, JGR 93, 1988), which is well below a 23 km cell; what a cell this size can hold
     * is the roughness of a young ridge flank, the scarp of a fracture zone and the odd seamount,
     * and 250 m is the rough end of Goff and Jordan's band, which is what a flank near a ridge
     * carries. The deep sea's *large* structure is no longer noise at all: it is the depth-age
     * curve in [IsostasyConfig.seafloorRidgeDepthMetres].
     */
    val marginReliefStandardDeviationMetres: Float = 700f,
    val oceanicReliefStandardDeviationMetres: Float = 250f,
    /**
     * How much relief the base noise carries on cratonic crust — the middle of a continent, far
     * enough in from its own edge that the crust is at full thickness.
     *
     * The counterpart of [marginReliefStandardDeviationMetres], and the reason it is a separate
     * figure is that the two are not the same on Earth. A margin carries four provinces inside a
     * few hundred kilometres — the shelf, the coastal plain, the piedmont and the marginal upwarp
     * — which is why the spread of Earth's continental crust *as a whole* is the margin's figure
     * rather than an average of the two. A craton carries one: the Canadian Shield runs 100 to
     * 500 m over three thousand kilometres, the West Siberian Plain stays under 200 m over two
     * thousand, the Russian Platform 100 to 300 and the West African craton 200 to 400.
     *
     * Five hundred metres, which is above the shields' own spread and deliberately so. This model
     * has no separate mechanism for an epeirogenic swell, and the swells sit on cratons — the
     * Brazilian and East African highlands at 1,000 to 1,700 m, the Colorado Plateau at 2,000 — so
     * the craton's noise has to carry them or the map has no high interior at all. Measured over
     * 400, 500 and 600 m on the five standard worlds at 512, as the median cell-scale departure on
     * the lowest quarter of the land against the ice share of land: 57 m and 5.10%, 62 m and
     * 5.83%, 69 m and 6.83%. Below 500 the ice goes — `SnowBalanceTest` holds it at half Earth's
     * 10.1% and there is no other high cold ground on the map — and above it the plains come back
     * as sandpaper, past the 65 m the tree before S2 manages.
     *
     * Spent through [cratonReachKm], so the two are the ends of one profile and there is no step
     * anywhere. It is what puts the drowning at the rim: a stationary field with the whole
     * continent's spread on it drowns the interior wherever the noise happens to dip, which is
     * what S2's third pass drew and what William named as flooded continents. Earth's drowned
     * continental crust is its shelves.
     */
    val cratonReliefStandardDeviationMetres: Float = 550f,
    /**
     * How far in from the edge of its own crust a continent becomes cratonic, in kilometres.
     *
     * The reach of the profile that carries both [cratonReliefStandardDeviationMetres] and
     * [IsostasyConfig.cratonThickeningKm]: relief falls and the crust thickens as
     * `1 - exp(-distance / this)`, so a cell this far in has made about two thirds of the journey
     * and one twice as far in nearly all of it.
     *
     * Four hundred kilometres, the middle of the band Watts (*Isostasy and Flexure of the
     * Lithosphere*, 2001) measures continental crust thinning from 40 km to 10 across at a rifted
     * margin: 200 to 500. An exponential rather than a ramp because a ramp ends at a distance
     * contour, and a distance contour drawn on a map is a ring — the annulus S2's earlier passes
     * were called out for.
     *
     * The reach trades the two things this profile is for against each other, because a short one
     * leaves a coastal plain cratonic and flat while a long one keeps the drowned band narrow.
     * Measured over 300, 400, 600 and 1,000 km on the five standard worlds at 512, as the median
     * cell-scale departure on the lowest quarter of the land against the share of the drowned
     * continental crust lying within 800 km of the crust's edge: 60 m and 82.2%, 62 m and 85.3%,
     * 67 m and 88.7%, 70 m and 89.3%. Four hundred is the last of them inside `main`'s own 65 m,
     * and it clears the four fifths `GroundTextureTest` holds by five points.
     */
    val cratonReachKm: Double = 400.0,
    /**
     * Where the ground stops being a shape and starts being a texture, in kilometres, and the
     * window the local relief that texture answers to is read over.
     *
     * Everything in the base relief broader than [textureCornerKm] is the shape of the country and
     * keeps the crust's own deviation above; everything finer is dissection, and how deep a
     * landscape is dissected is set by how much relief it has. That is Ahnert's relation
     * (*Functional relationships between denudation, relief and uplift in large mid-latitude
     * drainage basins*, Am. J. Sci. 268, 1970): denudation grows linearly with local relief over
     * two orders of magnitude of it, so a plain is worn smooth and a range is cut to pieces. It is
     * also Musgrave, Kolb and Mace's heterogeneous terrain (*The synthesis and rendering of eroded
     * fractal terrains*, SIGGRAPH 1989), which is the same observation made in a renderer: scale
     * the higher octaves by what the lower ones have already built.
     *
     * Without it every cell of land carried the same texture, because the base field is one
     * stationary random surface with one amplitude per crust. William, looking at S2's third pass
     * at 2048: *"the entire land has a very rough texture it did not have before ... no map of
     * Earth at any scale I've seen has that appearance."* Measured on the five standard worlds,
     * the tree before S2 puts 65 m of cell-scale departure on the lowest quarter of its land and
     * 116 m on the highest; that pass put 96 m on the lowest and 188 on the highest — half again
     * as rough as Earth's own map on ground that should be a plain. See `GroundTextureTest`.
     *
     * Two hundred kilometres is about where a drainage basin stops: a fourth-order catchment is a
     * hundred kilometres across and the ground inside one is the rivers' work, while above that
     * the shape is the crust's — a basin, an arch, a province. Measured over 100, 150, 200 and
     * 300 km on the five standard worlds at 512, the lowest quarter of the land reads 73, 68, 62
     * and 58 m of cell-scale departure and the highest 144, 137, 126 and 120, against `main`'s 65
     * and 116. Below 200 the band left unscaled between the corner and the four cells the eye's
     * own window spans is loud enough to undo the rule; above it the ranges start to go with the
     * plains.
     *
     * The window cancels, which is the check that the law is self-consistent rather than a knob:
     * a self-affine surface's relief grows as `window^H`, so `relief(window) * (corner/window)^H`
     * does not depend on the window at all. Measured over 500, 800 and 1,200 km it moves the
     * lowest quarter by a metre and a half — the residual being the surface's departure from exact
     * self-affinity. Five hundred is stated because a window has to be one length or another and
     * this one is clear of the corner.
     */
    val textureCornerKm: Double = 200.0,
    val textureReliefWindowKm: Double = 500.0,
    /**
     * The Hurst exponent of continental topography, which is what turns a relief measured over
     * [textureReliefWindowKm] into an amplitude at [textureCornerKm].
     *
     * A self-affine surface's standard deviation over a window of length L grows as `L^H`, so the
     * texture's share of the local relief is `(textureCornerKm / textureReliefWindowKm)^H` and
     * nothing else has to be declared. Seven tenths: Turcotte (*Fractals and Chaos in Geology and
     * Geophysics*, 2nd ed., chapter 7) puts continental topography between 0.5 and 0.8, and Gagnon,
     * Lovejoy and Schertzer (*Multifractal earth topography*, Nonlin. Processes Geophys. 13, 2006)
     * measure 0.66 over five decades of scale.
     *
     * It is checkable on this project's own maps, and it checks out: on the tree before S2 the
     * median cell-scale departure over a 200 km local relief reads 0.16 to 0.28 of it across five
     * worlds and four elevation quartiles, against the 0.22 this exponent predicts for a 23 km
     * cell in a 200 km window.
     */
    val topographyHurstExponent: Double = 0.7,
    /**
     * The local relief, in metres, at which a landscape is half as dissected as its relief alone
     * would make it.
     *
     * Ahnert's relation is linear, and it is fitted to basins that all have relief; at the flat end
     * of the range it is not what happens. Montgomery and Brandon (*Nonlinear controls on erosion
     * rates in the Washington Cascades and Olympic Mountains*, EPSL 201, 2002) measure erosion
     * rates that barely move with relief across low-relief country and then climb steeply once
     * hillslopes approach their threshold angle: a plain is transport-limited and aggrades, and
     * only ground with relief to spare cuts into itself in proportion to it. So the texture's
     * amplitude is `relief * relief / (relief + this)` rather than `relief` — Ahnert's line where
     * there is relief to spend, and a landscape that stays flat where there is not.
     *
     * Zero recovers Ahnert's line unmodified, which is what this rule is measured against. Twelve
     * hundred metres, measured over 0, 600, 1,200 and 2,400 m on the five standard worlds at 512
     * as the median cell-scale departure on the lowest quarter of the land against the same on the
     * highest, with the crust's relief held at its margin figure so the threshold is the only
     * thing moving: 94 and 168, 77 and 140, 74 and 131, 71 and 124. It quiets the whole field
     * rather than tilting it — the ratio of the two quarters barely moves — because most of what a
     * four-cell window reads on this map is the rivers' own incision and not the base field. What
     * it buys is the room to keep the crust's relief near Earth's instead of flattening the
     * cratons to quiet the plains, and the ice sheets that go with a high cold interior: at the
     * linear law the craton has to come down to 200 m of spread and the ice with it, 5.8% of land
     * to 4.2% against Earth's 10.1%. Past 1,200 the ranges start to go — at 2,400 the highest
     * quarter is eight metres above `main`'s floor.
     */
    val textureReliefThresholdMetres: Float = 2_400f,
    /**
     * How much more relief an active orogen carries than the plain beside it, in metres of
     * standard deviation, at a cell the present epoch raised in full.
     *
     * A mountain belt is not a plain with a hill on it. Between the crest lines of an orogen and
     * the valleys that drain it there is a kilometre and more of relief at the scale of the ranges
     * themselves: the Cordillera Occidental and Oriental stand 1,500 m above the Altiplano between
     * them over 150 km, the Greater and Lesser Himalaya are separated by the Midlands by as much
     * again, the Alps' longitudinal valleys — the Rhone, the Inn, the Valtellina — cut 1,500 to
     * 2,000 m below the massifs either side. So the standard deviation of the ground inside an
     * orogen at 100 to 200 km is 600 to 900 m where the plain beside it is a few hundred, and 700
     * is the middle of that.
     *
     * Spent in proportion to how hard the present epoch worked on the cell, on the same
     * [crustAgeReference] of stamped relief that decides whether the crust counts as this epoch's
     * own — so a belt's crest gets all of it, its flanks less, and the foreland none. Without it a
     * stamped profile is an analytic surface and reads as one: S2's first pass drew a collision
     * belt as a smooth pale tongue with contour banding and ponded lakes on a dead-flat plateau,
     * because there was nothing in the field at the belt's own scale for the water to cut into.
     */
    val orogenReliefStandardDeviationMetres: Float = 700f,
    /**
     * Amplitude of the fine relief added on top of the blended terrain, as a share of
     * [beltReliefMetres]. Small enough not to alter the visible shape of the land, large enough to
     * stop flat plains routing water in straight parallel lines.
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
    val rangeVariationCycles: Float = 13f,
    /**
     * Whether a convergent boundary's profile depends on which crusts are colliding.
     *
     * On it, the three convergent pairs build three different things: oceanic under continental a
     * narrow coastal range with a volcanic arc behind it, continental against continental a broad
     * flat-topped plateau, oceanic under oceanic an island arc. Off, every convergent boundary
     * gets the single [mountainHeight]-at-[boundaryFalloffCells] belt the generator used before, which
     * is what `BoundaryPairTest` turns off to show its measurement has teeth — with one profile
     * the Andes and Tibet are the same shape and the width-to-height ratios coincide.
     */
    val crustPairProfiles: Boolean = true,
    /**
     * Half-width, in cells, of the coastal range on the continental side of an oceanic–continental
     * margin. Deliberately far narrower than [collisionWidthCells]: the Andes are a few hundred
     * kilometres across where Tibet is well over a thousand, and that contrast is the whole point
     * of distinguishing the pairs. [WorldGenConfig.atResolution] rescales it with the grid.
     */
    val andeanWidthCells: Float = 14f,
    /** Crest height of that coastal range, in normalized elevation units. Narrow but tall. */
    val andeanHeight: Float = 0.52f,
    /**
     * How far inland of the suture the volcanic arc stands, in cells.
     *
     * A subducting slab does not melt at the trench; it melts once it is deep enough, which puts
     * the volcanoes a fixed distance behind the margin rather than on it. That offset is what
     * makes the margin asymmetric in a way a symmetric falloff cannot express.
     */
    val arcOffsetCells: Float = 13f,
    /** Half-width of the volcanic arc ridge about its own axis, in cells. */
    val arcWidthCells: Float = 5f,
    /** Height of the volcanic arc above the range it rides on, in normalized elevation units. */
    val arcHeight: Float = 0.20f,
    /**
     * Half-width, in cells, of a continental collision plateau. Broad — see [andeanWidthCells].
     * [WorldGenConfig.atResolution] rescales it with the grid.
     */
    val collisionWidthCells: Float = 26f,
    /**
     * Height of the plateau, in normalized elevation units.
     *
     * Lower than [andeanHeight] on purpose. Tibet stands below the highest Andean peaks and holds
     * that height over a hundred times the area, and since the whole field is normalized before
     * sea level is cut, a plateau as tall as it is wide would simply push every other landform
     * down the colour ramp.
     */
    val collisionHeight: Float = 0.34f,
    /**
     * Share of the plateau's half-width that is dead flat before the profile starts falling away.
     *
     * Tibet is a plain at altitude, not a ridge: the interesting thing about a continent-continent
     * collision is that it thickens the crust over a wide area rather than piling it on a line.
     */
    val plateauFlatShare: Float = 0.60f,
    /**
     * Height of the ranges around a plateau's rim, above the plateau surface itself.
     *
     * The Himalaya, the Karakoram, the Kunlun and the Qilian all stand on the edge of Tibet rather
     * than in it, which is what stops a plateau reading as a dome: the high ground is a rough plain
     * inside a ring of mountains. Without it the collision profile is smooth everywhere and the
     * eye reads a mound.
     */
    val plateauRimHeight: Float = 0.12f,
    /**
     * Where the rim ranges crest, as a share of the plateau's half-width, and how wide they are —
     * the rim's own half-width is one minus this. Dimensionless, so it needs no rescaling.
     */
    val plateauRimShare: Float = 0.78f,
    /**
     * How much of [rangeVariation] a plateau feels, as a fraction.
     *
     * A belt sagging near to nothing between massifs is right for a range and wrong for a plateau:
     * a plateau that broke into separate massifs would be a chain again, and uniform height over a
     * very wide area is the striking thing about Tibet. So it is damped — but only by half, and
     * the reason for not damping it further is downstream rather than tectonic. A plateau that
     * holds one altitude for its entire run is one continuous ice cap once the climate stage sees
     * it, and a cap of that size walls the habitable ground behind it into a single region, which
     * `CultureRealmTest` reads as one people holding too much of the world. A plateau that swells
     * and sags is not only better geography, it is the difference between one ice cap and several.
     * See REALISM_PLAN.md, B2, for the figures at a fifth of the variation and at a half.
     */
    val plateauAlongVariation: Float = 0.50f,
    /**
     * How far from the suture the island arc stands, on the overriding plate, in cells.
     * [WorldGenConfig.atResolution] rescales it with the grid.
     */
    val islandArcOffsetCells: Float = 8f,
    /** Half-width of the island-arc ridge about its own axis, in cells. */
    val islandArcWidthCells: Float = 7f,
    /**
     * Crest height of an island arc, in normalized elevation units.
     *
     * Sized so the arc mostly stays under water — it is built on oceanic crust, which isostasy
     * sets some four kilometres below continental — and only the swells of [rangeVariation] break
     * the surface. That is what makes an arc a chain of islands rather than a ridge of land.
     */
    val islandArcHeight: Float = 0.24f,
    /**
     * Depth of the floor of a continental rift valley, in normalized elevation units.
     *
     * Deeper is refused, and the refusal is a measurement rather than a preference. The value is
     * in the same units as [riftShoulderHeight] and [collisionHeight] — shares of a field that is
     * normalized and then cut at a percentile — so what it comes out as on the finished map has to
     * be read off the map: measured over the five seeds `RiftDepthTest` runs, at 512 and at 2048,
     * the floor already stands 45-72% of the land's relief below its shoulder crest, against
     * Earth's 21-50% (Baikal 3.2-4.0 km of crest-to-floor against 8 km of relief, Tanganyika
     * 2.8-3.8, Malawi 1.7-2.7, the Dead Sea 1.7-1.9), and the deepest rift lake a finished world
     * holds is 24.2% of the land's relief against Baikal's 20%. Deeper still turns the rift from a
     * chain of basins into one continuous axis that drains along itself, which is the opposite of
     * what the segmentation exists to produce.
     *
     * Giving the floor relief *within* itself was likewise written, measured and reverted: the
     * floor is not the plane it looks like, and every amplitude tried put a closed sub-basin below
     * the sea-level cut that the post-cut outlet cannot open. `RiftDepthTest` and
     * `RiftDepthAuditTest` are what is left of that — the measurements, without the change. See
     * REALISM_PLAN.md, E7, for the figures.
     */
    val riftDepth: Float = 0.25f,
    /** Half-width of the rift trough, in cells. */
    val riftWidthCells: Float = 7f,
    /**
     * Share of the trough's half-width that is flat floor before the ground starts climbing.
     *
     * A rift valley has a floor, not a keel: the Rift Valley is a flat plain with an escarpment on
     * either side, and lakes and rivers lie along it. A V-shaped trough instead gives every river
     * that finds the axis banks it never cut — enough, measured, to account for most of what
     * `ValleyIncisionTest` reads as incision. Dimensionless, so it needs no rescaling.
     */
    val riftFloorShare: Float = 0.55f,
    /** How far from the rift axis its raised shoulders crest, in cells. */
    val riftShoulderOffsetCells: Float = 11f,
    /** Half-width of each shoulder about its own crest, in cells. */
    val riftShoulderWidthCells: Float = 7f,
    /**
     * Height of the rift shoulders, in normalized elevation units.
     *
     * Crust that is being pulled apart thins and drops, and the flanks rebound: the East African
     * rift is a trough between two escarpments, not a simple groove. Without the shoulders a rift
     * reads as an erosional valley rather than a tectonic one.
     */
    val riftShoulderHeight: Float = 0.10f,
    /**
     * Whether a continental rift is broken along its length into half-grabens.
     *
     * A rift is not one trough of constant depth between two shoulders of constant height. It is a
     * chain of half-grabens fifty to a hundred and fifty kilometres long, each tilted the opposite
     * way from its neighbour — a high footwall on one flank, a low hinge on the other, the floor
     * deepening toward the footwall — separated by accommodation zones where the floor rises back
     * toward the hinge. That is why the Red Sea, the Gulf of California, Baikal and Tanganyika are
     * strings of deeps and sills rather than canals, and why the sea enters only the segments that
     * have subsided below it.
     *
     * Off reproduces the uniform trough this generator built before, which is what
     * `RiftSegmentationTest` measures its "before" against: on seed 59758 the whole rift floods as
     * one twenty-to-one strait of very nearly constant width.
     */
    val riftSegmentation: Boolean = true,
    /**
     * Shortest and longest half-graben segment, as a fraction of the map's width.
     *
     * A map fraction rather than a count of cells, so a rift breaks into the same segments at 512
     * and at 2048 — which is also why [WorldGenConfig.atResolution] leaves both alone.
     *
     * Real half-grabens run 50 to 150 km. On the 12,000 km world this generator's other knobs are
     * calibrated against, that is two to six cells at 512, which is below the size at which a grid
     * this coarse can draw a basin at all: the rift would alternate polarity faster than its own
     * trough is wide and read as noise. So the segments are set to the largest structures a real
     * rift is built from rather than to its smallest — roughly 500 to 1200 km, the spacing of the
     * Red Sea's separate deeps and of Tanganyika's basins — which is what this grid can show.
     */
    val riftSegmentMin: Float = 0.040f,
    val riftSegmentMax: Float = 0.100f,
    /**
     * Half-length of the accommodation zone at each join between segments, as a fraction of the
     * map's width. Through it the trough's depth tapers to nothing and its asymmetry to symmetry,
     * so neighbouring half-grabens of opposite polarity meet without a step.
     */
    val riftAccommodation: Float = 0.016f,
    /**
     * Spread of the per-segment depth factor on [riftDepth]: a segment's trough is between
     * `1 - this` and `1 + this` times as deep as the nominal rift. This is what decides which
     * segments flood and which stay dry.
     */
    val riftSegmentDepthVariation: Float = 0.45f,
    /** The same spread, applied to each segment's shoulder height and shoulder half-width. */
    val riftSegmentShoulderVariation: Float = 0.40f,
    /**
     * Depth of the floor against the hinge flank of a half-graben, as a share of its depth against
     * the footwall. The basin is a wedge, deepest along the fault it hangs from.
     */
    val riftHingeFloorShare: Float = 0.28f,
    /** Height of the hinge shoulder as a share of the footwall shoulder, for the same reason. */
    val riftHingeShoulderShare: Float = 0.32f,
    /**
     * Height of the sill in an accommodation zone, in normalized elevation units — the ground that
     * rises between two half-grabens and becomes the land bridge between two gulfs.
     */
    val riftSillHeight: Float = 0.075f,
    /**
     * How many tectonic epochs the world's history carries, the present one included.
     *
     * A drift vector that only classifies today's boundaries gives a world where nothing has ever
     * moved: every range is young and sits exactly on a plate edge. Earth is not like that. The
     * Appalachians and the Urals are collisions whose boundary is gone — worn low, broadened, and
     * a thousand kilometres from any plate edge — and the North Sea and the Benue trough are rifts
     * that opened, failed and filled with sediment.
     *
     * So the stage runs itself [historyEpochs] times. Each past epoch displaces every plate seed
     * back along minus its own drift (see [epochDriftCells]), classifies the boundaries of *that*
     * configuration by the same crust pairs, stamps the same profiles, and then ages what it
     * stamped: lower (by the time since it stopped rising — see [epochLengthYears] and
     * [orogenDecayTimeYears]), broader ([beltAgeWidening]), rounder ([beltAgeBlurCells]). The
     * present epoch stamps last and sharpest, and its boundaries, distances and classes are the
     * ones the rest of the pipeline sees, unchanged.
     *
     * 1 — or 0, which means the same thing — is the world before any history existed, bit for
     * bit: the present epoch alone, with every ageing factor exactly 1 and never applied.
     * `TectonicHistoryTest` pins that against checksums taken from that build.
     */
    val historyEpochs: Int = 3,
    /**
     * How far a plate travels between one epoch and the next, in cells.
     *
     * A plate boundary only moves if the plates either side of it move relative to one another, so
     * this is what decides how far an old belt ends up from a present one. At the default a
     * two-epochs-ago boundary sits some 90 cells from where its plates are now, against a plate
     * radius of about 137 cells on a 14-plate 512 world — far enough that an old belt lands well
     * inside a plate interior rather than merging with the modern edge beside it, which is the
     * whole point. [WorldGenConfig.atResolution] rescales it with the grid.
     */
    val epochDriftCells: Float = 45f,
    /**
     * How long one tectonic epoch lasts, in years, and how long a dead orogen takes to fall to
     * `1/e` of its height once its uplift has stopped.
     *
     * H1 aged a belt by a factor per epoch — 0.45, chosen from the Appalachians against the Alps.
     * A factor is not a mechanism: it says a belt is low because it is old, where what is true is
     * that a belt is low because its uplift stopped and erosion went on, and the two are only the
     * same thing when the arithmetic between them is written down. So the factor is retired and
     * these two times replace it, with the decay `exp(-epochsAgo * epochLengthYears /
     * orogenDecayTimeYears)` derived from them. Double the epoch and an old belt is lower by the
     * exponential's own amount rather than by nothing; `TectonicHistoryTest` holds that.
     *
     * Both figures are Earth's, and the pair reproduces H1's 0.45 to two places, which is why the
     * belts did not have to move to gain a reason. Three hundred million years is the age of the
     * Alleghanian orogeny that finished the Appalachians and of the Uralian that finished the
     * Urals, which are the two collisions the ageing was written from; the Variscan is the same
     * epoch. And the decay time is those two ranges themselves: an active collision stands
     * 4,000-4,800 m (the Alps, the Southern Alps, the Zagros) and the Appalachians stand about
     * 2,000 and the Urals about 1,500 after 300 Myr, so the ratio is 0.42 and the time constant
     * `300 / ln(1 / 0.42)` is 345 Myr. Baldwin, Whipple & Tucker (*Implications of the
     * shear stress river incision model for the timescale of postorogenic decay of topography*,
     * JGR 108, 2003) put the decay of an unforced orogen at 10 to 100 Myr for a soft lithology and
     * several hundred for a resistant one, which is the band this sits in.
     *
     * `exp(-300/345)` is 0.42, against the 0.45 H1 used: an old belt is 7% lower than it was, and
     * two epochs back 13%.
     */
    val epochLengthYears: Double = 300e6,
    val orogenDecayTimeYears: Double = 345e6,
    /**
     * How fast the rock rises in an active belt, in millimetres a year, by the crust pair that is
     * raising it — the field the hydraulic rounds add to the terrain every round.
     *
     * This is the coupling the generator lacked. Uplift used to be a thing that happened once,
     * before erosion started, and stopped the moment it did; on Earth the two run together, and
     * the height of a range is the balance between them (Whipple & Tucker, *Dynamics of the
     * stream-power river incision model*, JGR 104, 1999). A belt that is still being pushed up
     * holds its height against the rivers cutting it down, and one whose boundary has moved away
     * does not — which is the whole difference between the Alps and the Appalachians, and the
     * reason [epochLengthYears] above can retire a decay factor.
     *
     * The *ratios* between the four are England & Molnar's (*Surface uplift, uplift of rocks, and
     * exhumation of rocks*, Geology 18, 1990), which is also the paper that insists on the
     * distinction these numbers have to make. Active collision runs 1-10 mm/yr of rock uplift (the
     * Himalaya 5, the Southern Alps 5-10, Taiwan 5-7) and an Andean margin 1-3 (the Central Andes
     * 1-2 through the Neogene); a rift's shoulders are a fraction of that, lifted by the flexure of
     * the fault rather than by convergence (the Rwenzori, the Ethiopian escarpment, a few tenths);
     * an island arc has no continent to thicken and manages less still; and a craton does nothing
     * at all, which is what makes it a craton. Five to two to seven-tenths to three-tenths is that,
     * and it is the part of their measurement this model can take unchanged.
     *
     * The *scale* is this model's own, and the reason is a measurement rather than a preference.
     * England and Molnar's rock uplift is nearly all spent against exhumation — the Himalaya rise
     * at five millimetres a year and gain about half of one, because the rest comes off as
     * sediment — so a rate is only meaningful beside the erosion it is racing. This generator's
     * rivers and hillslopes take **0.27 mm/yr** off an active belt, measured over the belts of the
     * present epoch on seeds 7, 42, 1234, 99 and 718106 at 512 with the uplift switched off, which
     * `IsostasyTest` re-measures and holds this constant against. It was 0.36 until S2's fourth
     * pass gave the base relief a texture proportional to the ground's own relief: a smoother
     * plain is less for the water to take away.
     *
     * So the collision rate is the surface uplift Earth's own collisions manage — half a
     * millimetre a year — plus what this model's rivers will take back off it, which is 0.77 mm/yr
     * of rock uplift, and the other three follow the ratios above. That is close to England and
     * Molnar's own band for an active collision, 1 to 10 mm/yr, where S2's first pass reached 0.6,
     * and the reason is worth saying. The first pass measured the denudation at 0.101 mm/yr on a
     * surface with a quarter of the mid-band relief for the water to cut into, and divided the
     * metres a round removes by a round two and two-thirds longer than S1's own derivation gives
     * (see [WorldScale.yearsPerHydraulicRound]). Both were corrected in the second pass, the
     * erosion rate came out at Earth's own order for an orogen, and the uplift that has to race it
     * came with it.
     *
     * Over the one and a half million years twelve rounds stand for that is 1.2 km of rock into a
     * collision belt and 0.4 km out of it, against a dead belt of the same age that only loses.
     * The difference between the two is what S2 exists to show.
     *
     * Spent over [WorldScale.yearsPerHydraulicRound] per round. See `HydraulicErosion.apply`.
     */
    val collisionUpliftMmPerYear: Float = 0.77f,
    val andeanUpliftMmPerYear: Float = 0.308f,
    val islandArcUpliftMmPerYear: Float = 0.108f,
    val riftShoulderUpliftMmPerYear: Float = 0.046f,
    /**
     * The height, in metres, past which the crust's own strength starts to hold a range back.
     *
     * Not a clamp for tidiness but the oldest limit in orogeny: rock is not strong enough to
     * support unlimited relief, so a range that is pushed up past a few kilometres spreads
     * sideways under its own weight instead of rising further. Molnar and Lyon-Caen (*Some simple
     * physical aspects of the support, structure, and evolution of mountain belts*, GSA Special
     * Paper 218, 1988) put that limit at about three kilometres of elevation above the
     * surroundings for crust of ordinary strength, which is why Tibet and the Altiplano stand at
     * five and nothing on Earth stands at ten; Whipple and Tucker reach the same ceiling from the
     * erosional side and Willett (*Orogeny and orography*, JGR 104, 1999) from the critical wedge.
     *
     * Two things read it, and both need it. The belts are stamped by profiles whose along-strike
     * swell puts a strong pair's crest eight times above a typical one's, so without a limit one
     * massif per map would stand at ten kilometres and out of the ruler the climate reads; above
     * this knee the stamp is compressed toward [WorldScale.highestLandMetres] by
     * `knee + span * (1 - exp(-(h - knee) / span))`, which never reaches the ceiling and never
     * flattens a crest into a bench — a slope through it is compressed, not erased. And the uplift
     * the hydraulic rounds add tapers linearly to nothing between here and the ceiling, so a belt
     * rising at five millimetres a year for four million years arrives at Tibet's height and
     * stops.
     */
    val elevationLimitKneeMetres: Float = 3_000f,
    /**
     * How much broader a belt gets per epoch of age.
     *
     * An orogen does not merely sink, it spreads: the crustal root relaxes and the debris is laid
     * out on the forelands either side, which is why the Appalachian province is wider than the
     * Alpine one for a third of the height.
     */
    val beltAgeWidening: Float = 1.45f,
    /**
     * Radius, in cells, of the rounding blur applied per epoch of age.
     *
     * Two box passes rather than three: an old belt should read as rounded, not as a stain. The
     * blur is what turns a stamped profile with a crest and a toe into the smooth swell of a worn
     * range, and it is applied to the epoch's own uplift field alone, so it never touches the
     * present epoch's edges. [WorldGenConfig.atResolution] rescales it with the grid.
     *
     * Held at three cells rather than the six first tried, for a reason about the *length* of a
     * belt rather than its cross-section. A blur is isotropic: at six cells and two passes its
     * reach is comparable to the saddles [rangeVariation] leaves between one massif and the next
     * (about forty cells at 512 for [rangeVariationCycles] of thirteen), so it does not only round
     * the profile, it fills the gaps and welds a chain of worn massifs into one continuous upland.
     * That is bad geography — the Appalachians are a province of separate ranges with valleys
     * through them — and it showed up downstream as one people holding 45% of seed 42's habitable
     * land against `CultureRealmTest`'s 45% ceiling, because a continuous upland is a corridor.
     * At three cells the saddles survive and the same seed reads 33%.
     */
    val beltAgeBlurCells: Float = 3f,
    /**
     * How much of a failed rift's trough survives as a trough, the rest having filled with
     * sediment.
     *
     * A rift that opened in a past epoch and then stopped does not stay a canyon: it becomes an
     * aulacogen, a broad shallow sag full of its own erosion products, which is what the North
     * Sea, the Benue trough and the Mississippi embayment are. So a past epoch's continental rift
     * keeps this share of its depth (before ageing takes its share too) and only a remnant of its
     * shoulders ([failedRiftShoulder]).
     */
    val failedRiftFill: Float = 0.55f,
    /** What a failed rift keeps of its shoulders. Flexural uplift relaxes once the fault stops. */
    val failedRiftShoulder: Float = 0.35f,
    /**
     * The relief, in normalized elevation units, at which an epoch's belt counts as having made
     * the crust beneath it its own age — the scale of [PlateResult.crustAge].
     *
     * A cell the epoch raised by this much or more takes that epoch's age outright; one it barely
     * touched keeps whatever older age it had. Erosion reads the field to decide erodibility, so
     * what matters is that the bands are unambiguous, which is why the value is a relief rather
     * than a distance.
     */
    val crustAgeReference: Float = 0.05f,
    /**
     * Share of plates that carry a hotspot — a point fixed in the mantle that the plate drifts
     * over, leaving a line of seamounts behind it.
     *
     * Only oceanic plates are considered, so what this produces is island chains in open water
     * rather than volcanic fields inland.
     */
    val hotspotPlateFraction: Float = 0.35f,
    /** How long a hotspot trail runs before it has subsided to nothing, in cells. */
    val hotspotChainLengthCells: Float = 110f,
    /** Distance between successive seamounts along a trail, in cells. */
    val hotspotSpacingCells: Float = 15f,
    /** Radius of a single seamount, in cells. */
    val hotspotRadiusCells: Float = 5f,
    /** Height of the youngest seamount in a chain, in normalized elevation units. */
    val hotspotHeight: Float = 0.17f,
    /**
     * Antialias a seamount's stamp with sub-cell supersampling and give its rim a few low
     * harmonics of seeded noise, so a cone only a handful of cells across does not rasterize as a
     * blocky near-octagon and no two cones are identical. Off reproduces the old single-sample,
     * unmodulated stamp, which is what [com.cartogenesis.worldgen.pipeline.PlateStage]'s hotspot
     * guard measures "before" against.
     */
    val hotspotConeDetail: Boolean = true
)

/**
 * The crust floating on the mantle: what sets the two levels the world's hypsometry is built
 * around, and how the ground bends under a load.
 *
 * Two ideas, one section, because they are two readings of the same equation. Airy isostasy is the
 * local one — a column of crust displaces its own weight of mantle, so a thick light column floats
 * high and a thin dense one floats low, and that is why continents stand about 800 m above the sea
 * and the abyssal floor lies 3,700 m below it. Flexure is the same statement made about a plate
 * with strength: a load does not sink only where it stands, it bends the plate around it, so a
 * mountain belt is ringed by a moat and a low swell, an ice sheet depresses ground far beyond its
 * margin, and a river's delta subsides under its own sediment.
 *
 * Before this section the generator had neither. Sea level was a percentile through a field that
 * had been renormalised to its own extremes, so "62% ocean" was a statement about the histogram
 * and not about the planet, the hypsometric curve came out as one peak straddling the shoreline,
 * and erosion could take four kilometres off a range without the range rising by a millimetre in
 * reply. See `REALISM_AUDIT.md` 1.2 and REALISM_PLAN.md, S2.
 */
@Serializable
data class IsostasyConfig(
    /**
     * Off puts every crust at one level and lets the sea-level percentile decide the coastline
     * again, as it did before S2 — which is the control every guard in this chunk is shown to fail
     * against, and the flat base `BoundaryPairTest` measures a belt profile over.
     */
    val enabled: Boolean = true,
    /**
     * The densities of the four things a column can be made of, in kilograms per cubic metre.
     *
     * The mantle is Turcotte and Schubert's peridotite (*Geodynamics*, 3rd ed., table 4-1); the
     * continental crust is Christensen and Mooney's global mean of 2,835 over 41 km of thickness
     * (*Seismic velocity structure and composition of the continental crust*, JGR 100, 1995); the
     * oceanic crust is Carlson and Raskin's 2,900 (*Density of the ocean crust*, Nature 311,
     * 1984) over the 7.1 km White, McKenzie and O'Nions measure from seismic refraction
     * (*Oceanic crustal thickness from seismic measurements*, JGR 97, 1992). Sea water at 1,030 is
     * the standard mean.
     */
    val mantleDensity: Float = 3_300f,
    val continentalCrustDensity: Float = 2_835f,
    val oceanicCrustDensity: Float = 2_900f,
    val seaWaterDensity: Float = 1_030f,
    /** The two crusts' thicknesses, in kilometres; see [continentalCrustDensity] for the sources. */
    val continentalCrustThicknessKm: Float = 41f,
    val oceanicCrustThicknessKm: Float = 7.1f,
    /**
     * How much thicker a cratonic column is than the crust at its own margin, in kilometres.
     *
     * Continental crust is not one thickness. Christensen and Mooney's global compilation gives
     * the mean as 41 km and reads shields and platforms at 41 to 45, orogens thicker again, and
     * extended and rifted crust at 25 to 30 — so a continent is thickest in the middle and thins
     * toward its own edge. Airy turns a kilometre of crust into 141 m of altitude, because a
     * kilometre of crust is worth `(mantle - crust) / mantle` of itself in freeboard.
     *
     * Twelve kilometres is the *swing* across the profile rather than an excess over the mean, and
     * what it comes to on this map is Christensen and Mooney's own two ends: with the profile's
     * mean over the map's continental crust subtracted, a craton carries 44.6 km and the crust's
     * own outer edge 32.6, against their 41 to 45 for shields and platforms and 30.5 for extended
     * crust. The tilt between them is 1,700 m of freeboard.
     *
     * Spent through [TectonicsConfig.cratonReachKm], and *mass-neutral*: the profile's mean over
     * the continental crust of the map is subtracted before it is applied, so the average column
     * is still 41 km and the datum is still Earth's 840 m of freeboard
     * ([continentalFreeboardMetres]). What it changes is not how high a continent stands but how
     * it is tilted — up in the middle, down at the rim — which is what puts the drowned part of it
     * where Earth's is.
     */
    val cratonThickeningKm: Float = 12f,
    /**
     * How high a standard continental column floats, in metres — the constant of integration for
     * every other column on the map.
     *
     * Airy isostasy fixes the *differences* between columns and says nothing about where the datum
     * is; what puts the datum where it is on a real planet is how much water it has. So one figure
     * is declared and the rest follow, and this is Earth's own: the mean elevation of the land is
     * 840 m (Cogley 1984; Eakins & Sharman's ETOPO1 volumes give 797 m for the same quantity, and
     * the difference is what counts as land at the shelf edge).
     */
    val continentalFreeboardMetres: Float = 840f,
    /**
     * How deep the sea floor lies at a spreading ridge, and how much deeper it sinks per root of
     * a million years — the depth-age curve the whole ocean's shape now comes from.
     *
     * Ocean floor is made hot at a ridge and sinks as it cools, and it sinks as the square root of
     * its age, because that is how far heat diffuses out of a half-space in a given time. Parsons
     * and Sclater (*An analysis of the variation of ocean floor bathymetry and heat flow with
     * age*, JGR 82, 1977) fitted `d = 2,500 + 350*sqrt(t)` metres to the North Pacific and North
     * Atlantic for floor younger than about 70 Myr, and found the older floor flattening out
     * rather than going on down the root — `d = 6,400 - 3,200*exp(-t / 62.8)` — which Stein and
     * Stein (*A model for the global variation in oceanic depth and heat flow with lithospheric
     * age*, Nature 359, 1992) confirmed on a global compilation and explained as the base of the
     * plate reaching a fixed temperature. Both branches are here and they are Parsons and
     * Sclater's own figures.
     *
     * What it buys is the difference between an ocean and a flat polygon. A ridge stands at 2.5 km
     * and eighty-million-year floor lies at 5.6, so the deep sea now has three kilometres of its
     * own structure laid out in a smooth curve away from every spreading boundary — where before
     * S2's second pass it was one level per plate with a thousand metres of noise on it, and the
     * plate partition showed straight through the bathymetry.
     *
     * Read through Airy's equation rather than written onto the map: [Isostasy.Columns] turns the
     * depth this curve asks for into the thermal buoyancy a column of that age must have, so a
     * continental margin still blends between the two crusts by mixture rather than by a
     * special case. See [continentalFreeboardMetres] for the datum the whole thing hangs from.
     */
    val seafloorRidgeDepthMetres: Float = 2_500f,
    val seafloorSubsidenceMetresPerRootMyr: Float = 350f,
    /**
     * The depth the old floor approaches, in metres, and the two constants of the exponential that
     * takes it there: `6,400 - 3,200 * exp(-t / 62.8)`, Parsons and Sclater's own fit to the floor
     * older than about seventy million years. See [seafloorRidgeDepthMetres].
     */
    val seafloorAbyssalAsymptoteMetres: Float = 6_400f,
    val seafloorFlatteningRangeMetres: Float = 3_200f,
    val seafloorFlatteningTimeMyr: Float = 62.8f,
    /**
     * The oldest sea floor a world may carry, in millions of years.
     *
     * A hundred and eighty is Earth's: the Jurassic floor of the western Pacific and the eastern
     * Mediterranean, the oldest in-situ oceanic crust there is, because everything older has been
     * subducted (Muller, Sdrolias, Gaina & Roest, *Age, spreading rates and spreading asymmetry of
     * the world's ocean crust*, G3 9, 2008). A corner of a map further from a ridge than the
     * spreading rate can account for in that time is a corner whose trenches are in the wrong
     * place, and capping the age is the honest answer to it rather than inventing crust older than
     * any planet keeps.
     */
    val oldestSeafloorAgeMyr: Float = 180f,
    /**
     * How deep the sea floor lies on average, in metres below the water — the one figure the
     * depth-age curve is anchored to, and what the spreading rate is solved from.
     *
     * The mean depth of Earth's ocean is 3,682 m (Charette & Smith, *The volume of Earth's ocean*,
     * Oceanography 23, 2010). Before S2's second pass this was the depth of *every* piece of sea
     * floor, one level for the lot; now it is the mean of a distribution whose shape is Parsons and
     * Sclater's and whose spread is the map's own geometry, and what is solved from it is the rate
     * the ridges spread at. See [PlateStage.seafloorAgeOf] for why the rate is the thing that gives
     * and the depth the thing that is held.
     *
     * One honest qualification, and it is the same one [TectonicsConfig.continentalCrustSubmergedShare]
     * carries. Earth's 3,682 m is a mean over the whole ocean, and about a fifth of that ocean is
     * shelf, slope and rise standing on continental crust — the deep floor away from the margins
     * averages nearer 4,300. This generator drowns 9 to 13% of its continents against Earth's 29%,
     * so it has far less of that shallow fifth, and anchoring its *oceanic crust* at 3,682 rather
     * than at 4,300 is the choice to keep the whole ocean's mean where Earth's is instead of the
     * deep floor's. The deep floor is therefore some 600 m shallower than Earth's, which is exactly
     * the margin the model is missing. In `TODO.md`.
     */
    val oceanicMeanFloorMetres: Float = 3_682f,
    /**
     * Off gives every cell of sea floor the one age that floats at [oceanicMeanFloorMetres], which
     * is the one-age ocean S2's first pass drew and the control the depth-age guards are shown to
     * fail against.
     */
    val seafloorAge: Boolean = true,
    /**
     * Whether the plate bends under a load as well as floating on the mantle, and the elastic
     * thickness it bends with, in kilometres.
     *
     * The elastic thickness is the depth of plate that behaves as a beam rather than flowing, and
     * it is what decides *how far* a load is felt. Watts (*Isostasy and Flexure of the
     * Lithosphere*, 2001) puts continents between 20 and 40 km once thermally mature, oceanic
     * lithosphere lower and young orogens lower still; thirty is the middle of the continental
     * band and the figure the Ganges and Po forelands are usually fitted with.
     *
     * What it comes to on this map: the flexural rigidity `D = E * Te^3 / (12 * (1 - v^2))` is
     * 1.68e23 N m at these figures, and the flexural parameter `(4D / (dRho * g))^(1/4)` is 68 km
     * against an empty moat — three cells of the default grid. A load's own basin therefore reaches
     * some 160 km in front of it and its forebulge some 210, and once the range's debris has filled
     * the basin the response to that reaches further again. Earth's Ganges foreland is 300 km wide
     * over a plate stiffer than this one (the Indian shield's elastic thickness is 70-90 km where
     * an average continent's is 30), and the Alpine molasse is 100.
     */
    val flexure: Boolean = true,
    val elasticThicknessKm: Float = 30f,
    /** Young's modulus in gigapascals and Poisson's ratio, for the rigidity above. */
    val youngsModulusGPa: Float = 70f,
    val poissonRatio: Float = 0.25f,
    /**
     * The density of whatever fills the space the plate bends into, in kilograms per cubic metre —
     * which with [mantleDensity] is the `dRho` of the flexure equation.
     *
     * Air, which is to say nothing, and the reason is worth setting out because the textbook
     * figure is not this one. Turcotte and Schubert's worked foreland case takes `dRho` as mantle
     * against sediment — `3,300 - 2,700 = 600` — because the moat they are describing is already
     * full: the sediment shed off the range beside it is itself a load, and counting it into the
     * restoring term is how a closed-form solution gets the amplification without iterating. This
     * model does iterate. It has a deposition stage that fills the moat with the range's own
     * debris round after round, and the next round's flexure answers the extra weight, so taking
     * the 600 here would count that sediment twice and deepen every basin by a factor of five and
     * a half. An empty moat bends against the whole mantle, so `dRho` is 3,300, and the 600 comes
     * back out of the loop where the sediment actually arrives.
     */
    val deflectionFillDensity: Float = 0f,
    /** Gravity, in metres per second squared. Earth's standard value. */
    val gravity: Float = 9.81f,
    /**
     * Whether an ice sheet's weight is handed to the flexure, and the density of the ice.
     *
     * Glacial isostasy is the most directly observed part of this whole section: Scandinavia is
     * still rising a centimetre a year from the load that left it ten thousand years ago, its
     * raised beaches are the record of it, and Greenland's bed lies below sea level over most of
     * its interior because three kilometres of ice are standing on it. Ice at 917 kg/m³ against
     * mantle at 3,300 depresses its bed by 28% of its own thickness once the mantle has flowed.
     *
     * The map is drawn after the last deglaciation, so what it shows of that is the ground the
     * *present* ice still holds down and the ground the *former* ice has already let go: the
     * rebound is the absence of a load rather than a load of its own. S2 takes the ice mask the
     * glaciation stage produces and gives it a thickness; I1 gives the sheet a Vialov profile and
     * this reads it instead. See REALISM_PLAN.md, S2 and `REALISM_AUDIT.md` section 5.
     */
    val iceLoad: Boolean = true,
    val iceDensity: Float = 917f,
    /**
     * How thick the ice is taken to be at the middle of a sheet, in metres, and how far in from
     * its margin it reaches that thickness, in kilometres.
     *
     * Antarctica averages 2,126 m of ice and Greenland 1,673 (Fretwell et al. 2013; Morlighem et
     * al. 2017), and both thin to nothing at the coast over a few hundred kilometres. Two
     * thousand metres over a 400 km ramp is that, and it is deliberately the crudest thing that
     * can be true: a sheet's real profile is a parabola in the distance from its margin (Vialov
     * 1958) and drawing it is I1's, which this chunk exists to leave room for rather than to
     * pre-empt.
     */
    val iceSheetThicknessMetres: Float = 2_000f,
    val iceSheetMarginRampKm: Double = 400.0
)

/**
 * The continental shelf: a remap of the ocean floor, applied in [SeaLevelStage] *after* the
 * percentile sea-level cut rather than in the tectonics that feed it.
 *
 * An earlier version of this shaped the shelf as an extra depression in [PlateStage], before sea
 * level was chosen. That moved the percentile threshold itself, so any depth large enough to read
 * on the map also reshuffled which cells were land — closing straits into land bridges and merging
 * landmasses that should have stayed apart, visible downstream as a realm or a people swallowing a
 * neighbour it used to be cut off from. Remapping the ocean floor afterward, keyed on distance to
 * the coastline that sea level already chose, gets the same shallow margin without moving a single
 * `isLand` bit or a single land [SeaLevelResult.relativeElevation] value.
 */
@Serializable
data class SeaConfig(
    /**
     * Width of the shelf plateau, in kilometres; a further band of the same width blends the
     * plateau back down to the natural sea floor, so the whole wedge reaches twice this from the
     * coast.
     *
     * Seventy-five kilometres, which is Earth's mean shelf width: 78 km (Cogley, *Continental
     * margins and the extent and number of the continents*, Rev. Geophys. 22, 1984), against 50 to
     * 200 on most coasts and past 1,000 on the Arctic and Patagonian margins.
     *
     * It was 468.75 km until S2's second pass, and that figure was doing a job it no longer has to
     * do. B1 invented this wedge because the sea floor dropped straight off every coast: there was
     * no two-density crust, so nothing but a remap could put shallow water on a margin, and the
     * remap had to be wide enough to be seen. S2 gives the crust its two densities and a 600 km
     * margin between them ([TectonicsConfig.crustMarginKm]), so the shallow water on a margin is
     * now the drowned platform itself and this is only the sediment wedge on top of it. Measured,
     * the old width was not a margin but the ocean: a 20-cell plateau and a 20-cell slope around
     * every coast of a world whose coastline runs seven thousand cells covered essentially all of
     * it, which put 44% of the water shallower than 1,650 m against Earth's 15%, smeared the
     * hypsometric trough shut, and drew the concentric distance bands around every landmass that
     * S2's first pass was called out for.
     *
     * Converted to cells where the stage reads it: left as a count, a finer grid would shrink the
     * shelf to a sliver and every coast would drop straight into deep water again.
     */
    val shelfWidthKm: Double = 75.0,
    /**
     * Depth of the shelf plateau at its outer edge, in metres below the shoreline.
     *
     * A hundred and thirty, which is Earth's shelf break — the figure
     * `HydraulicErosion.SHELF_BREAK_METRES` has carried since S1 and the same one the last glacial
     * lowstand exposed. It was 1,000 m until S2's second pass, and S1 recorded the gap as a
     * finding rather than a knob: a plateau at 130 m would have been one part in seventy-seven of a
     * sea whose own mode was at -390 m, far below what the ocean floor's relief could hold apart.
     * S2 gives the sea a floor at three to five kilometres, so a shelf break at Earth's own depth
     * is now a shelf break and not a rounding error.
     */
    val shelfDepthMetres: Float = 130f,
    /**
     * How far below today's shoreline the sea stood while the rivers were cutting, as a fraction
     * of the land's own relief.
     *
     * The hydraulic rounds grade every channel to the sea they can see, so with the sea fixed at
     * today's level no valley may continue below it and every coastline is a clean percentile cut
     * through the land. That is not the coast any real continent has. The last glacial maximum put
     * the sea about 120 m below where it stands now, rivers cut to *that* level and left their
     * lower valleys hanging when the ice melted, and what the sea did on the way back up is the
     * Atlantic seaboard's sounds, Brittany's and Galicia's rias, the Chesapeake, the Severn, every
     * estuary on the map. The shelf between the two stands is a drowned plain with the old channels
     * still on it.
     *
     * A hundred and twenty metres, in metres, which is the figure the paragraph above is about.
     * Read off the height field's own ruler — [WorldScale.reliefSpanMetres], the whole 16,000 m
     * from the deepest floor to the highest land — because the shoreline is a *level in that
     * field* and moving it is not a height above the water or a depth below it. 120 m of 16,000 is
     * 0.0075 of the field, and no measured range enters the arithmetic at all, which is the point:
     * the constant this replaced was 0.015 of "the land's relief above the shoreline", a quantity
     * that is 0.25 of the field on one seed and 0.59 on another and different again at every grid.
     * Against the worlds measured that came to 0.0088 of the field, so the stand this chunk gives
     * them is about 15% shallower and no longer moves when the seed does.
     *
     * Zero puts the sea where it is today for every round, which is what the generator did before
     * this setting existed and reproduces that world bit for bit — the control the estuary guard
     * needs.
     */
    val lowstandMetres: Float = 120f,
    /**
     * Whether a body of water the ocean cannot reach is treated as land after the cut.
     *
     * Sea level is a percentile over the whole height field, so *any* hollow below the cut is
     * drawn as ocean whether or not a drop of ocean could get to it — and erosion leaves a great
     * many one-cell hollows just under the waterline near a coast. Measured with deposition
     * switched off entirely, a third of every seed's river mouths ended in such a pocket. Cutting
     * an inlet from each of them out to the sea was tried in the hydraulic pass and reverted,
     * because a small body of water the ocean cannot reach is sometimes a landform rather than an
     * artefact: the gulfs of a flooded rift are exactly such bodies, and joining them to the ocean
     * turns the chain back into the canal `RiftSegmentationTest` exists to break up.
     *
     * Connectedness in the cut itself is the answer, and it is the only place that can tell the
     * two apart without guessing. A water region that does not touch the ocean's main body — by
     * eight-connectivity, wrapping in x as every neighbour walk in this generator does, because a
     * rift's sill may be one cell wide and a diagonal step is a step — is marked land at the
     * elevation it already has. What happens to it next is not this stage's business: the river
     * stage's depression fill raises it to its lowest outlet and the water balance decides whether
     * it holds a lake (a lake below sea level is the Caspian, the Dead Sea, the Qattara) or dries
     * out into a salt flat.
     *
     * Off is the control: the plain percentile cut, with every hollow below it drawn as ocean.
     */
    val enclosedSeaIsLand: Boolean = true,
    /**
     * How large a body of unreachable water may be and still be turned into land, as a share of the
     * map. Bigger ones are left as sea.
     *
     * The Caspian is 371,000 km² on a 510-million-km² Earth, which is 0.073% of the surface and the
     * largest lake this planet has; `OutletIncisionTest` already holds the generator to it for the
     * lakes the drainage makes. It is the right figure here for the same reason. A hollow under the
     * waterline that the ocean cannot reach is a lake, and a lake that would be larger than any lake
     * Earth has is not a lake — it is a piece of the sea that the percentile cut has walled off with
     * a sliver of ground, and calling it land invents a landform nothing on Earth resembles.
     *
     * The cap is not decoration: converting *every* unreachable body turns 3 to 5% of the map from
     * sea into land, which hands one seed a lake four times the Caspian, turns a segmented rift's
     * gulfs into lakes so the rift reads as one body again, dries the interiors that were drinking
     * from the water it removed, and ponds the channels crossing a drowned tract into exactly the
     * thin grid-bearing bars `GlaciationTest` exists to catch. With the cap the river mouths this
     * rule was written for are still rescued — the pockets a river ends in are a handful of cells,
     * not an inland sea — and none of that follows. See REALISM_PLAN.md, H5, for the figures.
     *
     * At or below the cap, not above it, so a body exactly this size becomes a lake.
     *
     * In square kilometres since the world had an area to state one against. The value is the
     * Caspian's *share of Earth* — 0.073% — carried onto this map, which is 52,600 km² because
     * this world is a seventh of Earth's surface. The Caspian itself is 371,000 km². Which of the
     * two a world this size should use is a real question and not this chunk's to answer: seven
     * times the cap turns several more inland seas into land on every seed and moves coastlines
     * that nothing else in S1 touches. It is written up in `TODO.md`.
     */
    val enclosedSeaMaxKm2: Double = 52_560.0,
    /**
     * Whether a basin the cut converts from unreachable sea to land gets its outlet cut, once,
     * after the cut.
     *
     * [enclosedSeaIsLand] hands the river stage a hollow whose floor lies below sea level, and the
     * depression fill then raises it to its lowest rim — which can be a good deal wider than the
     * water that was there. On seed 718106 at 512 one such basin came out at 0.62% of the land,
     * two and a half times the Caspian's share of Earth's, and at 2048 the same trough held a
     * Caspian-shaped lake against a coastal rift. Neither of the two mechanisms that size the other
     * lakes can reach it: `ErosionConfig.outletIncision` runs inside the hydraulic rounds, while
     * that ground is still under the provisional sea, so there is no lip for it to cut and no
     * outflow to cut with; and `LakesConfig.waterBalance` cannot drain a floor that is already
     * below sea level, because there is nowhere for the water to go.
     *
     * So the notch is run once more on the far side of the cut, with the same stream power, the
     * same [ErosionConfig.outletIncisionRatio] and the same units — see
     * `SeaLevelStage.drainDrownedBasins`. One limit is lifted: inside the rounds the notch may
     * never cut below the sea, which is the base level a river grades to, but the water behind one
     * of these sills stands *below* the sea and the river flowing over the sill is grading to
     * that. So the cut may reach the waterline, and where the outflow has the power to take it
     * there, the sill becomes water and the basin is an arm of the sea — a sound, or a ria with a
     * narrow mouth, which is the Bosphorus and the Black Sea. Where it has not, the sill stands and
     * the basin keeps whatever the water balance then allows it: a lake below sea level, which is
     * the Caspian, the Dead Sea and the Qattara.
     *
     * Off is the control the guard needs: the drowned basins keep whatever sill they were left.
     */
    val postCutOutlet: Boolean = true,
    /**
     * Whether the waves are allowed to put the coast back in order after the sea has finished
     * rising.
     *
     * [lowstandMetres] drops the base level for nine of the twelve hydraulic rounds, so running
     * water works every cell within 120 m of the shoreline, and the transgression floods all of it.
     * That is the right half of the story and it is the only half the generator told: measured at
     * 512 over five seeds, the lowstand takes the shoreline from 43,967 cells to 60,755 and puts a
     * saw-tooth one to four cells deep on *every* coast, mountainous or flat, sheltered or exposed.
     * Earth's coasts are not alike in that way. The sea reached its present level about six
     * thousand years ago and the shore has been worked ever since, so a coast on low ground is a
     * graded arc of beach, barrier and marsh — Texas, Holland, Bengal — while a coast on high
     * ground keeps the outline the drowning gave it, which is Galicia, Maine and western Norway.
     *
     * See `LittoralGrading`, which holds the criterion and the Earth figures behind it. Off is the
     * control its guard needs, and is the coast the 2.0.2 release drew.
     */
    val littoralGrading: Boolean = true,
    /**
     * How far along the shore the littoral system carries sediment, in kilometres.
     *
     * Twenty-three, which is Earth's spacing of the inlets through a barrier coast — the length of
     * shore a drift system holds unbroken: 10 to 30 km between the Frisian islands, 20 to 60
     * through the Outer Banks, 180 for Padre Island in one piece. It sets how many sweeps of the
     * grading a fully depositional coast gets, so a re-entrant narrower than twice this is what
     * fills.
     *
     * A length on the ground, converted to cells where the stage reads it, like every other reach
     * in this file. It comes out as one cell at 512, two at 1024 and four at 2048, and rounds to
     * nothing at 128 or 256 — correctly, since the whole six thousand years of it is well under a
     * cell there and the pass switches itself off.
     */
    val littoralReachKm: Double = 23.4375,
    /**
     * How large a window the land behind a coast is judged over, in kilometres.
     *
     * 187 km, eight cells at 512. A coastal plain's own scale: the United States' Atlantic plain
     * runs 50 to 200 km inland, the Gulf plain 150 to 500, the North European plain 200 to 400. The
     * window is square, so the same figure is also how far *along* the shore the judgement is
     * averaged, and that is the half of it that turned out to matter. At 47 km — the width of the
     * narrowest of those plains, which was the first figure tried — the classification flickered
     * from cell to cell along a single coast and the coasts came out uniformly a little smoother
     * instead of some smooth and some not: the spread of the per-stretch dimension went from 0.105
     * ungraded to 0.102 graded, the wrong way. At 187 km a coast keeps one character for a stretch,
     * which is how Earth's coasts come, and the spread goes to 0.108.
     */
    val littoralBackshoreKm: Double = 187.5,
    /**
     * How far out to sea the exposure of a coast is measured, in kilometres.
     *
     * 492 km, twenty-one cells at 512. Wave height grows as the square root of the fetch until the
     * sea is fully arisen, and for an ordinary wind that takes a few hundred kilometres of open
     * water; five hundred is the round figure. Beyond it the waves stop growing, so measuring
     * further would only average in coasts on the other side of an ocean.
     */
    val littoralFetchKm: Double = 492.1875,
    /**
     * The share of a world's shoreline that is a depositional coast, and so the share the littoral
     * pass grades.
     *
     * Earth's own figure, put into the model directly rather than reached through a threshold on
     * the height of the land — the way [enclosedSeaMaxKm2] carries the Caspian's share of Earth's
     * surface and `GlaciationConfig.maxLakeShareOfMap` carries Superior's. Luijendijk et al. (2018),
     * *Scientific Reports* 8:6641, classify 31% of the world's ice-free shoreline as sandy from
     * three decades of satellite imagery; Bird (2000), *Coastal Geomorphology: An Introduction*,
     * puts the depositional share at about a third; Young and Carilli (2019) put the rocky share at
     * 52%, leaving 48% for everything softer. Thirty-one per cent is the tightest of those and the
     * one with a measurement behind it.
     *
     * It is a share rather than a height because no height can be derived. The postglacial rise —
     * did the sea flood a flat, or run up a valley — calls 59% of this generator's shoreline
     * depositional; a coastal plain's own one-metre-per-kilometre gradient calls 1.9% of it
     * depositional; and picking a figure in between so that the answer came out at Earth's third
     * would be tuning a threshold to a target. The gap between the two is real and it is the
     * low-lying *rocky* coast — Finland, the Canadian Shield, western Scotland, flat and ragged
     * both — which needs the lithology the plan's H3 has not built yet. See `LittoralGrading`.
     */
    val littoralDepositionalShare: Float = 0.31f,
    /**
     * Whether a drowned valley too narrow for its cell is filled back to the ground either side of
     * it.
     *
     * [lowstandMetres] cuts a channel down to the low stand at every shore, and the transgression floods
     * every one of them, so the cut comes back with a notch at every stream mouth: measured on the
     * four standard seeds and 298405 at 512, the coastline's Richardson dimension over its first
     * octave is 1.398 against 1.115 over its last, where a real coast measures much the same at
     * every scale. On the grid a channel is a whole cell wide whatever it carries. Earth's coasts at
     * six to twelve kilometres are indented by the Chesapeake, the Severn and the Gironde and by
     * nothing smaller — the Rias Baixas are two to seven kilometres across and a 1024 map cannot
     * hold one.
     *
     * So a drowned cell keeps its water only where the valley behind it is at least half the cell
     * wide, by Leopold and Maddock's square root of the catchment; below that the cell takes the
     * height it would have if the channel had the share of it that it really has, which is above the
     * waterline. See `DrownedValleys` for the five estuaries the constant is measured from.
     *
     * Off is the control its guard needs, and is the coast release 2.0.2 drew.
     */
    val drownedValleyFill: Boolean = true
)

@Serializable
data class ClimateConfig(
    /**
     * How much warmer or colder than the model's own answer this world's global mean is, in
     * degrees Celsius.
     *
     * The temperature is solved rather than declared — see
     * [com.cartogenesis.worldgen.pipeline.EnergyBalance] — and with Earth's own sun, greenhouse and
     * albedo the answer is 13.8 C, which is Earth's. This is the one knob on that: a shift of the
     * greenhouse, applied as a change in the outgoing-longwave offset and solved so that the
     * degrees asked for are the degrees delivered, feedback and all. Positive is a warmer world
     * with less ice and a flatter pole-to-equator gradient; negative is a colder one.
     *
     * It replaced [equatorTemperatureC] and [poleTemperatureC], which were the two anchors of the
     * curve the model retired. Their job — how warm the world is — survives here; their other job,
     * how steep it is from equator to pole, does not, because that is now a consequence of heat
     * transport and ice rather than something a reader states.
     */
    val globalMeanShiftC: Float = 0f,
    /** Temperature drop per kilometre of altitude, in C. */
    val lapseRateCPerKm: Float = 6.5f,
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
    val landRecoveryRate: Float = 0.010f,
    /**
     * How far the thermal equator migrates toward the summer hemisphere, in degrees of latitude.
     *
     * Everything seasonal follows from this one number. It carries the wind belts and the rain
     * belts, so the horse latitudes and the ITCZ march up and down the map over the year the way
     * they do on Earth; and it is what the planet's axial tilt is read off, so it decides the
     * sunlight the energy balance receives in each half of the year as well
     * ([com.cartogenesis.worldgen.pipeline.EnergyBalance.obliquityDegrees]). Ten degrees is the
     * modest, oceanic figure and is Earth's own zonal-mean migration, which is why it corresponds
     * to Earth's own 23.44-degree tilt; the great continents swing further than that, which is the
     * coastline's business rather than this one's.
     */
    val seasonalTiltDegrees: Float = 10f,
    /**
     * Whether the year has seasons at all.
     *
     * Off is exactly a tilt of zero: every seasonal field collapses onto the annual mean and the
     * world is bit for bit the one this generator made before seasons existed. Kept as a setting
     * rather than left to a tilt of zero so that `SeasonsTest` can state plainly what it is
     * turning off, and so the guard that needs seasons can be shown to fail without them.
     */
    val seasons: Boolean = true,
    /**
     * How far the wind slants across the latitude lines, in rows per cell of eastward travel.
     *
     * The three-cell circulation is not purely zonal: the trades spiral in toward the thermal
     * equator, the westerlies carry poleward, and the polar easterlies run back down. Giving the
     * march that component is what turns a row-by-row scan into a diagonal one, and with the belts
     * migrating over the year it is the whole of the monsoon — in summer the thermal equator
     * crosses over a tropical coast, the trades there reverse, and air that spent the winter
     * blowing out to sea spends the summer coming in off it.
     *
     * Zero is exactly the zonal march this generator used before, arithmetic for arithmetic. At
     * 0.3 the air crosses a row every three or four cells, so it traverses ten degrees of latitude
     * over a continent's width — about what it takes for a coast to feel a sea it does not face.
     */
    val meridionalWind: Float = 0.3f,
    /**
     * How strongly a current's sea-surface temperature anomaly scales the moisture the march
     * picks up over that sea cell, per degree of anomaly.
     *
     * Evaporation follows sea-surface temperature (Clausius-Clapeyron gives roughly +7% of
     * saturation per degree), and which water is warm or cold is a question about currents, not
     * latitude alone — [OceanStage] already solves the gyres and reports each cell's departure
     * from its latitude's mean as [com.cartogenesis.worldgen.pipeline.OceanResult.anomaly]. This
     * multiplies the march's over-sea pickup by `1 + currentMoisture * anomaly`, so a cold
     * upwelling current (Atacama, Namib, Baja) starves the coast it washes and a warm one (the
     * Gulf Stream, Norway) feeds it. The default of 0.07 is the Clausius-Clapeyron figure, so a
     * 5-degree cold anomaly cuts pickup by 35% ("cuts it by a third"). Zero reproduces the field
     * from before this setting existed, bit for bit, whatever the anomaly.
     */
    val currentMoisture: Float = 0.07f,
    /**
     * Whether the sea freezes.
     *
     * On, a water cell whose sea surface sits at or below the freezing point of sea water in a
     * season is under ice for that season
     * ([com.cartogenesis.worldgen.pipeline.ClimateResult.summerSeaIce]), the moisture march takes
     * nothing at all from it, and the warm season's mask is what the biome draws as pack ice.
     *
     * Off leaves the polar ocean evaporating as freely as the tropics do, which is the world before
     * W1 and is the control its guard needs. It is not a plausible world: an ocean under a metre of
     * ice is a lid, the polar sea is one of the driest places on the planet, and an ice sheet that
     * can draw on it never stops growing.
     */
    val seaIce: Boolean = true,
    /**
     * Whether ice is decided by a snow mass balance rather than by a temperature.
     *
     * On, [com.cartogenesis.worldgen.pipeline.SnowBalance] weighs a year's snowfall against a
     * year's melt in each cell, and ice is where the year ends in surplus. Two things read it: the
     * `ICE_SHEET` gate in [com.cartogenesis.worldgen.pipeline.ClimateStage]'s classifier, and the
     * frozen mask [com.cartogenesis.worldgen.pipeline.GlaciationStage] carves from — which is why
     * this setting lives in the climate section although one of its consumers runs two stages
     * before climate does. It is a fact about the climate; the engine runs a provisional climate
     * ahead of the ice to have it in time.
     *
     * Off restores the world from before the balance existed, exactly: the classifier's ice gate
     * goes back to an annual mean below -8 C, the glaciation mask back to a provisional annual mean
     * at or below [GlaciationConfig.freezingC], and no provisional climate is run at all. That is
     * the control `SnowBalanceTest` measures against, and the checksum in that test is the proof
     * that it is the old world bit for bit.
     */
    val snowBalance: Boolean = true
)

@Serializable
data class RiverConfig(
    /**
     * How much of the world's runoff a cell must carry before it is drawn as a river, as a share
     * of the whole world's runoff rather than as a count of cells — which is what keeps the river
     * network the same density at every grid.
     */
    val sourceFlowShare: Float = 0.0006f,
    /** The most channels drawn, longest first, so a very wet world does not become a thicket. */
    val maxRivers: Int = 400,
    /** Shortest channel worth drawing, in cells. Below this it is a rill, not a river. */
    val minLengthCells: Int = 8
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
    val speedCellsPerPass: Float = 1.6f,
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
    val coastalReachCells: Int = 10,
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
     * The critical slope: the steepest a hillside can stand before it fails, as a fall in metres
     * per kilometre of ground.
     *
     * Sixty metres per kilometre, which is 6% or 3.4 degrees. That is nothing like the thirty
     * degrees a scree slope stands at, and it should not be: a cell of the default grid is 23 km
     * across, so this is the steepest *mean* slope a stretch of ground 23 km long may hold. What
     * this number governs is the shape of a belt hundreds of kilometres wide, not the angle of any
     * real hillside, and the finer detail below the cell is not represented at all.
     *
     * The figure is the gentlest of the great mountain fronts, read over a cell's width: the
     * Andes' western flank climbs 6,000 m in the 100 km from the Peruvian coast to the Altiplano's
     * rim, which is 60 m/km; the Himalayan front is 5,000 m in 50, which is 100; the Sierra
     * Nevada's east face is 3,000 m in 20, which is 150. Taking the gentlest means the sweeps
     * plane nothing that any real range sustains, and everything steeper than all of them.
     *
     * It was 12 m/km until S2's third pass, and that was never a slope anybody had chosen. The
     * figure S1 found in the code was 9 units of a renormalised height field per 512 cells, and
     * converting it honestly gave 0.69 degrees — a twentieth of the gentlest front on Earth, and
     * gentle enough to plane a collision belt's own rim. A stamped plateau's rim ramp falls at
     * about 12 m/km over its 200 km, so it sat exactly at the threshold and the sweeps flattened
     * it to a dead plane: what the eye saw was a smooth cream annulus round every belt, with no
     * channel crossing it. Measured over 18, 24, 36 and 60 m/km on the five standard worlds, the
     * belt's band-pass relief at 5 to 20 cells climbs from 222 m to 253 against main's 200 and
     * the coastline's box dimension from 1.114 to 1.126; the figures are flat from 36 upward,
     * because by then the sweeps no longer reach anything a belt profile draws.
     *
     * Converted to the height field at the point of use, through
     * [WorldScale.reliefSpanMetres] and the cell's own width, so the same terrain wears to the
     * same profile whatever grid it is computed on.
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
     * are touched. Below about 6.7 m/km it starts erasing the terrain noise itself and the land
     * goes mushy.
     */
    val criticalFallMetresPerKm: Float = 60f,
    /**
     * How far debris may travel from where it came off, in kilometres.
     *
     * Spent as a count of sweeps, because material moves at most one cell per sweep: 80 sweeps on
     * the default grid, 320 on a grid four times as fine, which is the same distance on the ground
     * either way. Written as the distance rather than as the count so that the grid does the
     * arithmetic instead of a rescaling function doing it by hand.
     *
     * 1,875 km is a great deal further than any real talus apron, and the honest reading is that
     * this is not an apron: it is how far the slope-limiting rule is allowed to propagate before
     * the sweeps are called finished, and a rule that only ever moves material standing above the
     * critical slope cannot flatten ground that is already at rest. The cost of a long run is time
     * and not fidelity.
     *
     * Thermal erosion approaches its equilibrium asymptotically, so this is a real question rather
     * than a taste setting, and `ErosionConvergenceTest` reports the curve. Too low and the very
     * feature this stage exists to remove survives: at 18 sweeps the steepest slope left on the map
     * was still ten times the critical angle, meaning the knife edges had barely been touched. By
     * 160 it is down to 1.6. Because the rule only ever moves material that sits above the critical
     * slope, raising this cannot flatten terrain that was already at rest — gentler ground is
     * untouched however long it runs.
     */
    val debrisTravelKm: Double = 1_875.0,
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
     * The bedrock erodibility K of the stream-power law, in m^(1-2m) per year.
     *
     * Incision is `E = K * A^m * S^n` with m = 0.5 and n = 1, so K carries the units of a
     * reciprocal time. Whipple & Tucker (*Dynamics of the stream-power river incision model*, JGR
     * 104, 1999) put bedrock rivers at 10^-6 to 10^-5; Lague (*The stream power river incision
     * model*, ESPL 39, 2014) reviews the evidence and widens that to 10^-7 to 10^-4 across
     * lithologies and climates. This is the bottom of Whipple and Tucker's band, which is hard
     * rock, and the pairing with [WorldScale.yearsPerHydraulicRound] is what makes a round remove
     * what a round removed before either had a unit.
     *
     * The arithmetic, which is also the derivation of the time step. Per round a cell loses
     * `K * sqrt(A) * S * years` metres, with A the catchment in square metres and S the slope. The
     * stage works in a catchment expressed as a share of all land and a slope expressed as a rise
     * per map width, so `sqrt(A) = sqrt(share * landArea)` and `S = slopePerMapWidth *
     * highestLandMetres / worldWidthMetres`; the cut is spent on a height field whose whole 0..1
     * spans [WorldScale.reliefSpanMetres]. Multiply those through and the coefficient the stage
     * actually uses is
     *
     *     K * years * sqrt(landArea) * highestLandMetres / (worldWidth * reliefSpan)
     *
     * which at the default world — 38% of a 12,000 by 6,000 km map in land, 6,000 m of land relief
     * over 16,000 m of world relief — comes to 0.055, the figure this knob held before it had a
     * unit. See [com.cartogenesis.worldgen.pipeline.HydraulicErosion.incisionCoefficient].
     *
     * Raising it deepens valleys and sharpens divides; too high and the channels cut to the sea
     * and the land between them is left as unconnected plateaux.
     */
    val bedrockErodibilityPerYear: Float = 1e-6f,
    /**
     * Whether rivers put material back down as well as taking it away.
     *
     * Off, the hydraulic pass is detachment-limited: everything it cuts leaves the model, no delta
     * builds at a mouth and no floodplain aggrades. That was the behaviour for the whole life of
     * this project, so this switch is also the control the deposition guard needs — with it off the
     * world is reproduced bit for bit, which is what makes the guard's "before" honest.
     */
    val deposition: Boolean = true,
    /**
     * How much sediment a channel can carry, as a coefficient on `sqrt(area) * slope` — the same
     * stream-power form the incision uses, in the same units as its coefficient, because carrying
     * capacity and cutting power come from the same quantity.
     *
     * Transport-limited deposition: a cell carrying more than this lays the excess down instead of
     * cutting. Both terms are held against the map rather than the grid, so the scheme survives a
     * change of resolution for the same reason incision does.
     *
     * Note the ratio to the incision's own coefficient rather than the absolute value. At 20
     * against the 0.055 that
     * [com.cartogenesis.worldgen.pipeline.HydraulicErosion.incisionCoefficient] comes to on the
     * default world, a cell
     * can carry some three hundred times what it could cut on its own, so the upper catchment never
     * reaches capacity and settles nothing, and aggradation only begins once a trunk has gathered
     * the yield of a large basin. Measured across 4, 20 and 60 the valley-incision figure moved by
     * less than the sea-level histogram's own quantisation, so this is a middle value rather than a
     * fitted one.
     */
    val transportCapacity: Float = 20f,
    /**
     * How much of the shortfall settles per cell, per round — of the surplus over capacity, or of
     * the room below the cell that feeds this one, whichever is smaller.
     *
     * The second of those is nearly always the binding one, so read this as the speed at which an
     * overloaded channel creeps up toward grade over the twelve rounds. It cannot fill a valley in:
     * the room above a cell is measured against the finished surface, spoil included, so the margin
     * closes as the spoil accumulates.
     *
     * Measured at 0.008, 0.01, 0.03, 0.04, 0.06 and 0.10 against every downstream guard on seeds 7,
     * 42 and 1234. The figures wander — 0.01 put 44% of seed 7 under one realm where 0.008 and 0.03
     * put 36% and 29%, against a bar of 40% — and they wander because the realm and culture stages
     * are chaotic in the coastline, not because the deposition is. This value has the widest margin
     * of the six on the tightest of those figures.
     */
    val depositionRate: Float = 0.06f,
    /**
     * The share of what a river still carries when it reaches the sea that builds a delta, rather
     * than dispersing offshore and leaving the model.
     *
     * Real rivers lose most of their load to the shelf and the deep; what stays is what makes the
     * Nile's fan or the Mississippi's bird's foot. Raising it pushes deltas further out to sea.
     *
     * Low, because sea level is an *area*. A fixed share of the world is under water, so every cell
     * a delta lifts above the line pushes a cell somewhere else below it — and the cells nearest the
     * line are the low coastal ground people live on, which is why `CultureRealmTest` is the guard
     * that feels this setting first. See [deltaFreeboardMetres] for the measurements.
     */
    val deltaShare: Float = 0.15f,
    /** The same, for a river reaching a lake: how much of its load the basin traps at the inflow. */
    val lakeShare: Float = 0.1f,
    /**
     * How much land a watercourse must drain, as a share of all land, before it builds anything at
     * its mouth. Below it, everything the flow carries disperses into the sea.
     *
     * Without this the result is not deltas but a prograded coast: every rill reaching the water
     * carries enough to lift the cell in front of it over a shoreline that is, by construction,
     * right there — so the whole coastline creeps out by a few cells and nothing stands out as a
     * landform. Deltas are made by rivers, and a third of a percent of a continent is a river.
     */
    val deltaMinCatchment: Float = 0.003f,
    /**
     * How far from a mouth sediment may be laid, in kilometres — the radius of a delta or a
     * lacustrine fan.
     *
     * A hundred and forty kilometres, which is six cells of the default grid and the order of the
     * Nile's delta, 160 km from apex to shore. Rounded to the nearest whole cell where the stage
     * reads it, so it is six cells at 512 and twenty-four at 2048: the same fan on the ground,
     * drawn in more cells.
     */
    val deltaReachKm: Double = 140.625,
    /**
     * How high above the shoreline a delta cell is built, in metres.
     *
     * Forty-eight metres. Mississippi lobes stand a few metres above the Gulf, so this is an order
     * of magnitude too proud — and it has to be, for the reason below, because a cell of this map
     * is 23 km across and the shoreline is re-cut as a percentile after the delta is laid.
     *
     * A delta that stops exactly at the waterline is not visible: the sea-level percentile is taken
     * again from the whole field afterwards and would drown it. A small freeboard is what lets new
     * land actually clear the water, which is the entire point of the feature.
     *
     * Higher than one would guess, and for a reason that is easy to get backwards. A given budget
     * of sediment either makes a small delta standing a little proud of the water or a wide one
     * lying flat on it, and the wide one converts *more* sea into land. Since sea level is an area,
     * more new land means more old land drowned somewhere else, so the thin delta is the disruptive
     * one. Thicker and smaller is both gentler on the rest of the map and closer to what a delta is
     * — Mississippi lobes stand a few metres above the Gulf.
     *
     * The evidence, measured across twelve combinations of this, [deltaShare] and
     * [deltaMinCatchment] on seeds 7, 42 and 1234: at 0.004 the culture guard's three figures ran
     * as poor as 47% of habitable land under one people (the bar is 45%) and 1.20 realms per people
     * (the bar is 1.3), while at 0.008 the same settings gave 38% and 1.43. That guard is the
     * sharpest instrument the pipeline has for "did the coastline move", and it is worth reading
     * its numbers as a measure of disturbance rather than only as pass or fail. Those figures are
     * in the fractions of the land's relief this was written in before it had a unit: 0.004 and
     * 0.008 of 6,000 m are 24 m and 48 m.
     */
    val deltaFreeboardMetres: Float = 48f,
    /**
     * Whether the outflow from a filled basin is allowed to cut its own lip down.
     *
     * Every round fills the hollows so the water has somewhere to go, and the routing then runs
     * over the filled surface — which means the lip of a basin is the one piece of ground the
     * water never touches, and a tectonic hollow stays a lake the size of the hollow for the whole
     * life of the world. That is backwards. A basin filled to its rim overflows, the overflow has
     * a knickpoint at the lip, and the lip gives way: Bonneville emptied through Red Rock Pass in
     * weeks and left Great Salt Lake, and Agassiz drained through one outlet after another as each
     * in turn cut down. A lake is sized by the resistance of its outlet, not by the size of its
     * basin.
     *
     * Off is the control the guard needs: a basin stays the size of its own hollow, for ever.
     */
    val outletIncision: Boolean = true,
    /**
     * How much harder the water cuts at a basin's outlet than it does in an ordinary channel, as a
     * multiple of the same stream-power coefficient.
     *
     * Expressed as a ratio rather than as its own rate because it is the same stream power in the
     * same form — the discharge through an outlet is the basin's whole catchment, which flow
     * accumulation has already routed through that cell, and the slope is the one the channel below
     * the lip stands at. What the multiplier says is that a knickpoint is not an ordinary reach: the
     * flow over a lip is concentrated into a notch rather than spread across a valley floor, it is
     * falling over a step rather than running down a grade, and the lip is the one place on the
     * network where every round's fill hands the water a fresh head to work with.
     *
     * Three, and the honest thing to say about the number is that the landscape it acts on is
     * chaotic in it: measured at three, four, six and eight on seven seeds at 512, the largest lake
     * on a given seed jumps by a factor of two between neighbouring rates, because which basin ends
     * up largest changes. Three is chosen on the measurement that does not wander — the same world
     * at 512, 1024 and 2048 — where it holds the largest lake to within 1.33x on seed 59758 and
     * 1.01x on seed 42 and under the Caspian's share of the map at every grid. Six clears every
     * seed at 512 and then leaves seed 59758 a lake of 0.122% of the map at 1024, which is the
     * defect this rate exists to prevent.
     *
     * The number has moved twice, and both moves were the same kind of thing: the *unit* under it
     * changed and the multiplier absorbed the change, so that the notch went on cutting what it had
     * been cutting. It was one until the rate was expressed against the land's own relief rather
     * than against the height field, and rose to three with that change, because the old units
     * divided it by the range of the land — a different number at every grid, and what made the
     * largest lake grow threefold from 512 to 2048. It is now 1.125, because the ordinary incision
     * has been given a unit too: both rates are the one coefficient
     * [com.cartogenesis.worldgen.pipeline.HydraulicErosion.incisionCoefficient] derives from K and
     * the time step, and the notch's is charged in shoreline-relative units where the ordinary
     * cut's is charged on the height field. Three of the old rate is 1.125 of the new one, exactly:
     * 3 * highestLandMetres / reliefSpanMetres, or 3 * 6,000 / 16,000.
     *
     * What that arithmetic exposes is worth saying plainly, because it was invisible while the two
     * rates were written in different units. Converted to a single ruler, a knickpoint has been
     * cutting about 1.1 times as hard as an ordinary reach and not three times — and against the
     * height field, where the ordinary cut is actually spent, about nine tenths as hard. The
     * physical claim the number is supposed to make is not the claim it was making.
     */
    val outletIncisionRatio: Float = 1.125f,
    /**
     * How far below the lip the notch is cut, in kilometres.
     *
     * Fifteen hundred, which is sixty-four cells of the default grid — a long reach, and it has to
     * be, for the reason below.
     *
     * The lip cannot fall further than the ground immediately below it, so cutting the lip alone
     * buys one step and then stops: the spill is by construction the *lowest* point on the rim, and
     * the ground just beyond a saddle is gentle. Cutting the channel with it is what lets the notch
     * grade toward the steeper ground further down and keep deepening round after round, which is
     * what a knickpoint retreating upstream actually does.
     */
    val outletReachKm: Double = 1_500.0,
    /**
     * Whether the notch's channel gradient counts the step into the water the outflow empties into.
     *
     * It has to, where the sill runs level all the way to that water: the walk that measures the
     * fall stops on the last cell of land, so what it reads there is the epsilon the depression
     * fill nudges a flat by, which is not a small gradient but the absence of one — no stream
     * power, and a sill that stands for the life of the world however large the catchment behind
     * it. Only where the walk found no fall the fill did not put there, so an outlet that measured
     * a real gradient is untouched. See `HydraulicErosion.breach`.
     *
     * Off is the rule this replaced, kept as the control `OutletIncisionTest` measures against and
     * the renders are drawn against; a guard that has only ever been green proves nothing.
     */
    val outletFallToTheWater: Boolean = true,
    /**
     * Whether a delta is built as a lobe — sloping seaward from its apex, reaching out in front of
     * its river, and made only of cells the load could lift clear of the water.
     *
     * Off, it is what it was: every cell within [deltaReachCells] of the mouth raised to one level, in
     * whatever order the growth reached them, with the last one part-filled when the sediment ran
     * out. Three things follow from that and all three were visible on the author's own world at
     * 2048. The slab is flat, so a river arriving at its own delta has nowhere downhill to go and
     * stops at the inner edge of it. The outline is the square the growth ran out at, which is not
     * a landform. And the part-filled cells stay under water while later rounds build past them, so
     * the map ends up with pockets of sea enclosed by new land — forty-two of a hundred and
     * fifty-two river mouths on seed 59758, which is what a river appearing to dead-end in a bay
     * turned out to be.
     *
     * Off is also the control `DeltaMouthTest` measures against.
     */
    val deltaLobe: Boolean = true,
    /**
     * Whether a fan's outline is a curve about its apex — Euclidean distance, bent by the depth of
     * the water it builds into, wobbled by a few harmonics hashed from the mouth — or the square
     * the breadth-first growth ran out at.
     *
     * Off, it is what [deltaLobe] left: the growth's *step count* stands in for a distance, and
     * over eight neighbours a step count is the Chebyshev metric, whose iso-lines are squares. A
     * lacustrine fan, whose acceptance rule says only "any ponded cell", therefore covers the whole
     * `2·deltaReachCells+1` square around its inflow and leaves a flat raft with straight edges and
     * right-angle corners when the water goes away — the rafts the author found beside a rift mouth
     * on seed 718106 at 2048, ten and more cells of dead-straight coast at a stretch. A sea lobe
     * does shape itself, by a cosine of the angle to its trunk, but it compares that shape against
     * the same step count, so what it draws is a half-disc with its corners pulled out along the
     * diagonals, and its only irregularity is a per-cell hash one cell deep that nobody can see.
     *
     * On, the rim is `R · (sides + (1 − sides) · max(cos θ, 0)) · (1 + a · s(θ))` about the apex,
     * the walk fills shallow cells before deep ones so a delta progrades over a shelf and stubs
     * into a trench, and a lobe with enough cells to hold them is cut two to five distributary
     * grooves that the D8 routing then follows. See `DeltaFan`.
     *
     * Off is the control `DeltaOutlineTest` measures against.
     */
    val deltaOutline: Boolean = true,
    /**
     * Whether a floodplain aggrades to the slope its river needs in order to carry its load, or
     * simply rises until it is level with the cell that feeds it.
     *
     * Off, it is what it was, and its fixed point is a **flat**: a cell may rise to within nothing
     * of its feeder, so twelve rounds of creeping a fraction of the way there turn a lower valley
     * into a plane. A plane meeting the sea has a level set that is a straight line, which is why
     * the author's rift mouth on seed 718106 at 2048 had 32 cells of dead-straight shore where the
     * same ground with no deposition at all has 16; and the residual hollows in a near-plane pond
     * into shapes with no landform behind them — the rounded-square lake and the ring of crescent
     * moats in the same crop.
     *
     * On, the margin is measured down to the slope at which the transport capacity equals the load
     * — the equilibrium slope of a transport-limited channel, solved out of the same expression
     * `transportCapacity` already appears in, so it costs no new constant. A river aggrades where
     * its bed is steeper than it needs and stops when it is graded, which is what a delta plain and
     * a floodplain actually are.
     *
     * Off is the control `BayHeadDeltaTest` measures against.
     */
    val gradedAggradation: Boolean = true
)

/**
 * Ice, and what it leaves behind.
 *
 * Running water cuts a V and carries its spoil away; ice fills a valley wall to wall, cuts a U,
 * scours hollows into the floor that no river would ever leave, and dumps everything it carried in
 * a heap at its snout. Those are different landforms, and a world that has only the first one reads
 * as a world with no cold in its past — which, until this section existed, was exactly what this
 * one was.
 *
 * Every length here is in kilometres and every depth in metres, converted to the grid through
 * [WorldScale] where the stage reads them, for the same reason [SeaConfig.shelfWidthKm] is: a
 * trough a hundred and fifty kilometres wide is four cells at 512 and sixteen at 2048, and
 * anything else changes the world rather than its detail.
 *
 * The figures those kilometres come to are far larger than any real glacier's, and the KDoc on
 * [GlaciationConfig.valleyWidthKm] says why: at 23 km to a cell there is no smaller landform a map
 * of a whole world can draw. Writing the unit down is what made that visible.
 */
@Serializable
data class GlaciationConfig(
    /**
     * Off reproduces the world from before there was any ice, bit for bit — the stage returns the
     * sea-level result it was handed, the same object, so nothing downstream can even tell it ran.
     * That is what makes the lake-density guard's "before" honest.
     */
    val enabled: Boolean = true,
    /**
     * Mean annual temperature, in C, at or below which ice is permanent and flows.
     *
     * Judged on a provisional temperature computed from latitude and altitude alone — the same
     * curve [ClimateStage] uses, because it is literally the same function — since the climate
     * stage itself cannot run until the terrain this stage carves is final. Zero is the honest
     * line: it is where [ClimateStage.classify]'s own ice and tundra gates sit, so the mask is
     * bounded by the classification rather than merely near it.
     */
    val freezingC: Float = 0f,
    /**
     * How much colder the world that *carved* this terrain was than the world the map shows, in C.
     *
     * The ice a map draws and the ice that shaped the ground beneath it are not the same ice, and
     * this is the number that separates them. Finland's two hundred thousand lakes, the Canadian
     * Shield, the Lake District and the Finger Lakes were all cut by the Laurentide and Fennoscandian
     * sheets, which are gone: at the last glacial maximum ice covered about a quarter of the land
     * and today it covers a tenth, nearly all of it in two places neither of those lake countries
     * is near. So the mask this stage carves from is the snow balance of a *colder* world, while
     * `ClimateStage.classify` paints today's ice from today's balance, and the difference between
     * the two is the country that was glaciated and is not now — which is exactly where a map
     * should show lakes.
     *
     * 6 C, from the estimate of the last glacial maximum's *global mean* cooling: Tierney et al.,
     * *Glacial cooling and climate sensitivity revisited* (Nature 584, 2020), put it at 6.1 ± 0.4 C
     * below pre-industrial, and earlier proxy syntheses at 4-7.
     *
     * It is a **forcing**, not a shift applied to a finished field:
     * [com.cartogenesis.worldgen.pipeline.EnergyBalance.solarScaleForCooling] asks how far the sun
     * must be dimmed for the global mean to fall this far — three per cent, as it turns out — and
     * the model then answers with a colder world of its own. The cooling comes out polar-amplified,
     * which is what the proxies describe (MARGO 2009 put the tropical oceans 1.5-3 C below present
     * and the high northern latitudes 10-20 C below it), because the poles turn white and not
     * because anyone wrote a latitude ramp; until W1 there was such a ramp, a third of the mean at
     * the equator to twice it at the pole, and the model retired it. The colder world's rainfall is
     * the march's answer to that world too, so the mask is no longer generous by leaving a glacial
     * climate as wet as an interglacial one.
     *
     * Zero makes the carving mask today's ice, which was the first attempt and left one measured
     * world with 4,047 frozen cells, 92 of them in channelled country and not one glacier — so it
     * had no glacial lakes at all and the lake guard collapsed to zero. That is the correct answer
     * to "where are the glaciers today" and the wrong answer to "what does this landscape look
     * like", and the distinction is what this setting is. See REALISM_PLAN.md, H2.
     */
    val glacialMaximumC: Float = 6f,
    /**
     * Smallest frozen catchment that carries a valley glacier, as a share of *the frozen ground*.
     *
     * The equivalent of [ErosionConfig.deltaMinCatchment], and there for the same reason: without
     * it every frozen cell is its own little glacier and the whole ice cap is stippled with troughs
     * instead of drained by a few of them.
     *
     * Measured against the frozen cells rather than against all land, which is the correction the
     * lattice forced. A share of all land makes the same trough appear or not depending on how much
     * *warm* ground the world happens to have: on a world that is a tenth frozen the threshold asks
     * for ten times the snowfield it asks for on a world that is frozen through. What feeds a
     * glacier is the snow that falls on frozen ground above it, so that is the denominator. It is
     * also the half of the resolution bug: a share of *all land* is a share of a number that
     * quadruples with the grid, so at 1024 the same setting admitted four times as many parallel
     * flow paths per unit of map as at 512 while the trough stayed the same fraction
     * of the map wide — which is why the mesh appeared at the desktop's default resolution and not
     * in the 512 crops this stage was reviewed on. At the default it asks for a quarter of a
     * percent of the world's frozen ground before any ice is called a glacier at all: some eighty
     * cells of snowfield on seed 718106 at 512, and the same fraction of the world at any grid.
     */
    val minCatchment: Float = 0.0025f,
    /** Frozen catchment, in the same share-of-frozen-ground units, at which a glacier is at full
     * width and cuts its full depth. */
    val fullCatchment: Float = 0.06f,
    /**
     * How much local relief the ground must have before valley-glacier machinery runs on it, in
     * metres.
     *
     * The whole distinction between the two regimes, and the reason this stage was rewritten. A
     * valley glacier is ice *confined by a valley*: it is thick, it is channelled, and it planes a
     * U across the section it is squeezed into. Ground with no valley in it has no such ice on it —
     * it is under a sheet, which is not steered by the drainage network at all. The first version
     * of this stage did not make the distinction, so on a flat cold plain it ran the trough, the
     * staircase and the recessional moraine along every D8 path; the paths on a plain are straight
     * and parallel and meet at 45 degrees, and the result was a wire mesh of straight lakes at 0,
     * 45 and 90 degrees over the whole lowland. Relief is measured as the elevation range within
     * [reliefWindow] valley-widths of the cell, so it asks the only question that matters: is there
     * a valley here for the ice to be channelled by?
     *
     * The value is a reading off the terrain rather than a guess. Sampled over land on seeds 42, 7
     * and 718106 at 512, the elevation range inside that window has a median of 0.23 to 0.41 of the
     * land's own range — this generator's ground is rugged at cell scale almost everywhere — so a
     * threshold near a tenth, which is what it looked like it ought to be, left 92% of the frozen
     * ground "channelled" and the mesh untouched. At 0.35 of the relief the channelled share is a
     * fifth of the frozen ground on seed 42 and two fifths on 718106, which is the mountainous
     * part of each. Against the ruler that fraction is 2,100 m of local relief, which is a
     * mountain range and not a hill.
     */
    val valleyReliefMetres: Float = 2_100f,
    /**
     * The radius over which [valleyRelief] is measured, in multiples of [valleyWidthCells].
     *
     * About twice the trough the ice would cut: wide enough to take in both walls of the valley
     * and the interfluves beyond, narrow enough that a continental slope hundreds of cells across
     * does not read as a valley.
     */
    val reliefWindow: Float = 2f,
    /**
     * The shortest channelled flow path that may become a trough, in kilometres.
     *
     * A catchment threshold alone cannot tell a glacier from a gully: twenty cells of upstream
     * frozen ground is twenty cells whether they lie in a mountain valley or in a hollow on a
     * plain. A trough is a *long* landform, so this asks for a run of channelled ground — measured
     * from the furthest head above the cell to the furthest snout below it — before any of it is
     * carved.
     */
    val minTroughLengthKm: Double = 328.125,
    /**
     * The catchment a trough needs as a share of *its own ice field's* frozen ground.
     *
     * [minCatchment] asks whether there is enough ice in the world to make a glacier; this asks
     * whether this particular path is one of the few that drain the ice field it belongs to. The
     * difference is the comb. A straight range front carries a rank of parallel gullies, every one
     * of which clears a world-wide threshold at much the same time, so the stage cut a trough down
     * every one of them and left five to fifteen short bars of water side by side at exactly 45
     * degrees — the lattice again, at the scale of a mountain flank. A real range carries a handful
     * of glaciers, in its trunk valleys. Measured against the connected frozen region rather than
     * against all frozen ground so that a small cold massif gets its own few glaciers instead of
     * none, and a continental ice field does not get hundreds.
     */
    val trunkCatchment: Float = 0.05f,
    /**
     * How much a trough's path must wander before it counts as a valley, as the ratio of its length
     * to the straight line between its head and its snout.
     *
     * A valley is cut by water that had to find its way around things. A D8 path that runs dead
     * straight at one of eight bearings for its whole length is not a valley the ice found, it is
     * the grid: on a uniform slope every flow line takes the same step over and over, and a trough
     * carved along one is a ruled line on the map. One is perfectly straight; this asks for a few
     * percent of wander, which any path down real ground has and a ruled line does not.
     */
    val minSinuosity: Float = 1.25f,
    /**
     * How close two troughs of the same bearing may run, as a multiple of the trough's half-width.
     *
     * Neighbouring trunk valleys are not parallel straight lines a few cells apart; ice that close
     * together is one glacier, not two. Where two qualify, the one draining less ice is dropped —
     * the greater first, so the choice does not depend on the order cells happen to be visited in.
     * Zero turns the rule off, which is how its own guard is shown to have teeth.
     *
     * Four half-widths, which is *two trough-widths*. At one half-width the rule only merged ice
     * that already overlapped, which is a tautology rather than a rule; two trough-widths is the
     * distance at which two glaciers are plainly two glaciers rather than one braided one.
     *
     * Honestly reported: on seed 718106 at 2048, the world the widening was asked for, it drops
     * nothing at all — that world's comb was twenty *reaches* of three trunks, not twenty trunks,
     * and what removed it was the refusal to cut a basin along a straight D8 path (see
     * [minSinuosity] and `GlaciationStage.cutBasins`). The wider spacing is kept because the rule
     * as it stood could only ever have caught ice that was already the same glacier, not because
     * it was measured doing the work here.
     */
    val parallelSpacing: Float = 4f,
    /**
     * Whether flat frozen ground is scoured by an ice sheet instead of being left alone.
     *
     * The other half of the regime split. Off, low-relief frozen ground is simply not glaciated,
     * which is the honest control for the scour's own guard.
     */
    val sheetScour: Boolean = true,
    /**
     * How far sheet ice planes the ground down away from its basins, in metres.
     *
     * Modulated by noise rather than by the flow network, because that is what sheet scour does: it
     * strips a whole province to bedrock and leaves it hummocky, not grooved.
     *
     * Not small, and the reason is a measurement rather than a preference. The stage this replaced
     * cut a third of every cold lowland to trough depth, and that removal was doing real work in the
     * world downstream: it lowered the frozen interiors, which warmed them, which kept the ice caps
     * from closing over and walling the habitable ground into one region. At a token 0.004 the cold
     * interiors stayed high and icy and seed 42's largest people held 53% of the habitable world
     * against `CultureRealmTest`'s 45% ceiling — the same failure mode
     * [TectonicsConfig.plateauFlatShare] records. At 0.012 the mean rock removed from flat cold
     * country is 0.0072 against the old stage's 0.0118, the largest people holds 35%, and the ice
     * shares are 18/42/26% on seeds 42, 7 and 1234 against 17/41/25% before. The point of the
     * regime split was never that the ice should do less; it was that it should not do it in
     * channels.
     */
    val sheetLoweringMetres: Float = 72f,
    /** How deep a scour basin is cut below its own rim, in metres. */
    val sheetBasinDepthMetres: Float = 156f,
    /**
     * How much of the frozen flat country the ice may leave under water, as a share of it.
     *
     * The stage's whole water budget, and the answer to "how much lake is a glaciated world
     * allowed?". Both regimes draw on it: the valley basins are taken out of it first and the sheet
     * gets what is left, so the two cannot each quietly spend a full allowance.
     *
     * The Earth reasoning, since a number like this is worthless without it. The glaciated shields
     * are the wettest land there is — Finland is 10% water by area, the Canadian Shield much the
     * same — but almost all of that is in bodies far below one cell of a world map. At 2048 across
     * a 12,000 km world a cell is about 17 km², so the lakes a map at this scale can draw at all
     * are the ones over a thousand km²; in Finland those are Saimaa, Päijänne, Inari, Oulujärvi and
     * Pielinen, and together they are about 2.5% of the country, not 10%. A generated sheet
     * province is a whole cold lowland rather than a lake belt, so it should sit a little under
     * even that: a fifth of the raw shield figure, and the default is 2%.
     *
     * Measured on seed 718106 at 2048 with the author's settings, where the old code selected 13%
     * of the province by quantile and capped nothing: 113 lakes over 2.10% of the land, against 70
     * over 1.45% now — of which 54 lakes and 1.19% are in basins that exist with the whole stage
     * switched off, so what the ice itself contributes went from 0.91 to 0.26 percentage points.
     */
    val sheetLakeShare: Float = 0.02f,
    /**
     * The largest single basin the ice may cut, in square kilometres.
     *
     * Not a tuning knob but a fact about worlds: Lake Superior, the largest lake on Earth that is
     * not a sea, is 82,100 km² against Earth's 510 million, which is 0.016% of the surface. A body
     * of water larger than that share of a world is not a lake, it is the Caspian. Expressed
     * as an area rather than in cells, so that 512, 1024 and 2048 draw the same lake: 42 cells at
     * 512, 168 at 1024, 671 at 2048, every one of them 11,520 km². Superior's share of Earth
     * carried onto a world a seventh of its size, so the same caveat as
     * [SeaConfig.enclosedSeaMaxKm2] applies - the lake Earth actually has is seven times this.
     *
     * A basin over the cap is not thrown away — that would delete the lake country rather than
     * size it — it is peeled inward, ring by ring, until its floor fits. The rest of the blob keeps
     * the scour without the water.
     */
    val maxLakeAreaKm2: Double = 11_520.0,
    /**
     * The smallest basin the ice bothers to cut, in square kilometres.
     *
     * The floor that stops a finer grid from manufacturing speckle. A tenth of [maxLakeAreaKm2]
     * is 1,152 km², Lake Geneva's order of magnitude, and roughly the smallest body a map of a
     * whole world should draw at all.
     *
     * Four cells at 512, 17 at 1024, 67 at 2048. At 512 and 1024 [LakesConfig.minLakeAreaKm2] is
     * still the binding floor, so this changes nothing there; at 2048 and above it takes over,
     * which is the point.
     */
    val minLakeAreaKm2: Double = 1_152.0,
    /**
     * The size of the basins, as the number of noise periods across the map.
     *
     * A fraction of the world rather than a count of cells, so the same world gains detail rather
     * than changing character when it is generated at export resolution.
     */
    val sheetBasinCycles: Float = 26f,
    /**
     * How much a cell's own hollowness counts toward being chosen as a basin, against the noise.
     *
     * Zero would put the lakes wherever the noise fell; this pulls them into the hollows the
     * terrain already has, which is what makes them look like they belong to the ground rather
     * than like a pattern laid over it. It is still the noise that decides their shape.
     */
    val sheetConcavity: Float = 0.8f,
    /**
     * Half-width of the widest trough, in kilometres: how far up the valley sides the ice reaches.
     *
     * A hundred and fifty kilometres, which is six and a half cells of the default grid. A real
     * glacial trough is two to five kilometres across, so this is fifty times too wide, and it
     * cannot be otherwise: one cell of this map is 23 km, and a trough narrower than a cell cannot
     * be drawn at all. What the stage carves is a glaciated province the shape of a valley, and
     * the figures below are its figures rather than a glacier's. See GEOGRAPHY.md.
     */
    val valleyWidthKm: Double = 152.34375,
    /**
     * How thick the ice standing in a trough is, in metres: how far above its bed the ice surface
     * lies, and so how high up the valley side the ice is in contact with rock at all.
     *
     * The figure [valleyWidthKm] above was missing, and the 2048 render is what showed it. A
     * cross-section 150 km wide is not a valley, it is a province, and planing all of it to the
     * height of the one cell at its axis is planing whatever stood there — a ridge a kilometre
     * higher included. What that draws is a dead-level slab with a straight edge at the flow's own
     * D8 bearing and, where two glaciers of different bearings cross, a cross. So the cut is
     * shared by how deeply a cell is buried as well as by how far across it lies: the valley floor
     * is under the whole thickness and is planed, the shoulder is barely under the ice and is
     * barely touched, and rock standing above the ice surface is not touched at all. See
     * REALISM_PLAN.md, I2.
     *
     * Six hundred metres is what a large valley glacier measures. Radio-echo sounding puts the
     * Aletsch at about 900 m at its thickest and 300 to 500 m over most of its length, and the
     * Alaskan trunk glaciers are the same order; 600 m is the middle of that band. It is not
     * inflated to match [valleyWidthKm] the way the width is, and deliberately: the width is
     * inflated because a trough narrower than a cell cannot be *drawn*, while a thickness is a
     * height and this map's heights are true.
     */
    val valleyIceThicknessMetres: Float = 600f,
    /**
     * How much of that half-width is flat floor before the walls start to climb.
     *
     * The U, as against the V. A river's own cross-section comes to a point, because water cuts at
     * a point; ice is in contact with the whole bed at once and planes it flat, and the flat floor
     * with steep walls above it is the section every photograph of a glaciated valley shows. It is
     * also what makes an over-deepened basin hold water: a floor that comes to a point one cell
     * wide leaves a lake one cell wide, which [LakesConfig.minCells] rightly refuses to call a
     * lake at all.
     */
    val floorShare: Float = 0.65f,
    /** How far a full glacier lowers its bed, in metres. */
    val deepeningMetres: Float = 24f,
    /**
     * The extra cut in the over-deepened reaches between the steps, in metres.
     *
     * This is the number that makes lakes. A basin holds water only if its floor lies below the
     * step downstream of it, and the difference between the two is exactly this — so it has to
     * clear [LakesConfig.minDepth] with room to spare, at a glacier well short of full strength.
     */
    val overDeepeningMetres: Float = 144f,
    /**
     * How much descent ends a reach and starts the next basin, in metres.
     *
     * The staircase's rise per step, and the reason a basin can be cut to a level floor at all.
     * Flattening a reach costs whatever that reach descends, so a reach measured in *cells* costs
     * nothing on a plain and costs a mountainside in a mountain valley — and an earlier version of
     * this, spaced purely by distance, either failed to close its basins on any slope worth the
     * name or removed a tenth of every continent trying to. Measured in descent instead, the cost
     * is the same everywhere, which is also what glaciated valleys look like: a steep trough is a
     * short-stepped staircase of small rock basins, a gentle one a long flat reach with a single
     * broad lake in it.
     *
     * Doubled when the comb was dealt with. On a steep flank this term ends the reach long before
     * [basinSpacingCells] does, so a trough down a mountainside was a staircase of a dozen or more short
     * basins, and a dozen basins across a trough running at 45 degrees is a dozen straight bars of
     * water lying parallel — a paternoster chain drawn with a ruler. At twice the descent per step
     * the same trough carries three or four basins instead, each broad enough to read as a lake:
     * measured on seed 42 at 1024, the share of standing water in parallel grid-bearing bars fell
     * from 2.3% to 0.8%, and the count of separate lakes from 37 to 30.
     */
    val basinDropMetres: Float = 360f,
    /**
     * The furthest one reach may run before the next basin starts, in kilometres.
     *
     * The other half of the same rule, for ground with no descent to speak of: without it a plain
     * inside the ice would be one reach a thousand cells long. The two terms simply add, so a
     * reach ends when it has fallen [basinDrop] *or* run this far, whichever happens first.
     */
    val basinSpacingKm: Double = 375.0,
    /** Share of a reach the basin occupies; the rest is the step at its lower end. */
    val basinShare: Float = 0.75f,
    /** Radius of the bowl bitten out of a glacier's head, in kilometres. */
    val cirqueRadiusKm: Double = 70.3125,
    /** How deep that bowl is cut below the headwall, in metres. */
    val cirqueDepthMetres: Float = 84f,
    /**
     * How far a glacier runs on past the freezing line before it melts, in kilometres.
     *
     * A glacier's snout sits below its own snowline — that is what an ablation zone is — so the
     * trough, and the moraine at its end, belong a little way into ground that is not frozen. This
     * is the only licence the mask gets; nothing is carved further down than this.
     */
    val runOutKm: Double = 187.5,
    /** Height of the ridge of spoil left at a land terminus, in metres. */
    val moraineHeightMetres: Float = 270f,
    /**
     * Height of the recessional moraine laid across the valley at the lower end of an accepted
     * basin, in metres. Zero by default, and the zero is the fix.
     *
     * This was once the dam that did most of the work: a basin cut into a slope spread two cells
     * before the ground rose out of it, so the water needed a bar of till to pond behind. That is
     * no longer how a basin is made. A basin is now a *region* — a footprint round the ice's path,
     * opened so it is nowhere narrower than three cells, with its floor cut below the lowest cell
     * of its own rim — so it is closed by construction and holds water without any till at all.
     * With the basin closed anyway, every bar the retreating snout laid could only pond a second,
     * narrower body of water on the step below it, one cell thick and lying square across the flow:
     * which is to say a straight bar of water, which is the artefact this stage keeps being fixed
     * for. Left as a knob rather than deleted so the contribution can be measured again.
     */
    val riegelHeightMetres: Float = 0f,
    /**
     * Whether a glacier that ends in the sea leaves a trough on the sea floor.
     *
     * The drowned half of a fjord. The coastline itself is settled before this stage runs and is
     * not moved — see [SeaLevelStage]'s note on why the shelf never touches land — so what is left
     * to model is the bathymetry: a deep basin at the mouth shallowing out to the shelf, which is a
     * fjord's sill.
     */
    val fjords: Boolean = true,
    /** How deep a fjord basin is cut at the mouth, in metres below the shoreline. */
    val fjordDepthMetres: Float = 2_000f,
    /** How far out to sea that basin reaches, in kilometres. */
    val fjordReachKm: Double = 140.625
)

/** Standing fresh water in basins the terrain does not drain. */
@Serializable
data class LakesConfig(
    val enabled: Boolean = true,
    /**
     * How far the filled surface must sit above real ground before a cell counts as under water,
     * in metres.
     *
     * Epsilon-filling raises every cell along the flood path by a hair and those increments
     * accumulate over long flats, so this has to clear that noise or most of a continent reads as
     * lake. Twenty-four metres, which is deeper than a great many real lakes and is a statement
     * about the arithmetic rather than about water: it is where the fill's own rounding stops.
     */
    val minDepthMetres: Float = 24f,
    /**
     * Smallest lake worth drawing, in square kilometres. Below this it is a puddle, not a feature.
     *
     * An area rather than a count of cells, because a count of cells is a different lake at every
     * resolution: twelve cells at 512 is 3,296 km² and at 2048 it is 206, which is how a 2048
     * render ended up sprinkled with ponds a 512 one never had. Held as the area those twelve
     * cells stood for, so the same water is drawn at every grid.
     */
    val minLakeAreaKm2: Double = 3_295.8984375,
    /**
     * Whether a closed basin's lake is sized by its water balance rather than filled to the brim.
     *
     * Depression filling answers a routing question and its answer is the spill level, so every
     * basin on the map used to hold a lake full to the rim. That is right where the lake overflows
     * and wrong everywhere else: the Caspian, the Aral, Chad, Eyre and the Great Salt Lake all sit
     * far below the rim of basins many times their size, held there by evaporation. With this on,
     * a basin's surface settles where its catchment's runoff matches evaporation off the water,
     * capped at the spill. Off reproduces the old world exactly — a basin whose balance reaches the
     * brim takes the same code path either way.
     */
    val waterBalance: Boolean = true,
    /**
     * What share of the rain falling on a catchment reaches the basin, rather than evaporating or
     * transpiring off the ground where it fell.
     *
     * Earth's land receives roughly 110,000 cubic kilometres of rain a year and its rivers deliver
     * roughly 40,000, so a third is the global figure. A real runoff coefficient is far from
     * constant — it rises with rainfall and falls in hot, dry, vegetated country — and holding it
     * constant flatters dry basins, giving them more inflow than they would truly get, so the
     * effect this exists to produce is if anything understated.
     */
    val runoffFraction: Float = 0.35f,
    /**
     * Multiplies the Thornthwaite potential evaporation, for a world meant to be wetter or drier
     * than Earth. One is the published curve, unmodified; see
     * [com.cartogenesis.worldgen.pipeline.LakeWaterBalance.potentialEvaporationMm] for what it puts
     * a hot desert and a cool temperate basin at.
     */
    val evaporationScale: Float = 1.0f
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
    /**
     * How much a climb was to cost, which was what pinned borders onto mountain ranges.
     *
     * Nothing reads it, and nothing reads [terrainResistance] either: the realm stage's cost
     * surface was rewritten around catchments at some point and these two were left behind. They
     * are not given units here, because inventing a unit for a knob nobody spends would be worse
     * than leaving it plain, and they are not deleted here either, because deciding what the cost
     * surface should charge for a climb is a change to the realm stage and not to its units. See
     * `TODO.md`.
     */
    val slopeResistance: Float = 26f,
    /** Water deeper than this many metres is open ocean and effectively impassable. */
    val navigableDepthMetres: Float = 600f,
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
     * The most of the world any one realm may hold, as a share of all land. A realm over this is
     * split along its own watersheds until it is not.
     *
     * Appetite alone cannot bound a realm, because it is a brake relative to the neighbours
     * bidding for the same ground, and a realm that is the only bidder for a region takes it
     * whatever its appetite. Spacing the seeds further apart stops that but thins the contest for
     * river valleys, which is where borders on rivers come from; a cap does not.
     */
    val maxRealmShare: Float = 0.30f,
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
    val peoplePerArableKm2: Double = 38.0
)

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

/**
 * The peoples of the world, as opposed to its states.
 *
 * Kept as its own section rather than folded into [NationsConfig] so that the two are independent:
 * changing the realm count must not move every culture on the map, which it would if cultures were
 * guarded on a political setting.
 */
@Serializable
data class CulturesConfig(
    val enabled: Boolean = true,
    /**
     * How many peoples. Fewer than there are realms, because a culture is the larger thing: it is
     * normal for one to span several states and for a state to contain several.
     */
    val cultureCount: Int = 8,
    /**
     * How strongly a people prefers country like its homeland, against simple distance from it.
     *
     * This is the dial that decides whether the map reads as peoples or as pie slices. At zero a
     * culture spreads evenly in all directions and the layer is a Voronoi diagram; turned up, it
     * follows a grassland belt or a river system and stops where the climate turns.
     */
    val climateAffinity: Float = 7.0f,
    /** Extra cost of settling across a strait, on the same scale as a step of unlike country. */
    val seaCrossingCost: Float = 3.0f,
    /**
     * Extra cost of crossing country nobody settles, such as an ice cap.
     *
     * A cost rather than a wall. Treating hostile ground as impassable stranded everything behind
     * it, which on one seed meant a third of the world's land.
     */
    val hostileCrossingCost: Float = 6.0f,
    /** Largest catchment left whole when dividing land into cultural regions, as a share of land. */
    val maxRegionShare: Float = 0.030f,
    /** Smallest cultural region, as a share of land; anything under is merged into a neighbour. */
    val minRegionShare: Float = 0.006f
)

@Serializable
data class WorldGenConfig(
    val seed: Long = 1L,
    val width: Int = 512,
    val height: Int = 512,
    /**
     * The world's physical size and the time a hydraulic round stands for.
     *
     * Beside the grid rather than inside a stage's own section, because it is what the grid *means*
     * and every stage from erosion onward reads it. [atResolution] does not touch it: how many
     * cells the world is cut into says nothing about how wide the world is.
     */
    val scale: WorldScale = WorldScale(),
    val terrain: TerrainConfig = TerrainConfig(),
    val tectonics: TectonicsConfig = TectonicsConfig(),
    /**
     * How the crust floats and how it bends — which is what gives the height field an absolute
     * scale, so it belongs beside the tectonics that stamp into it rather than inside them.
     */
    val isostasy: IsostasyConfig = IsostasyConfig(),
    val erosion: ErosionConfig = ErosionConfig(),
    /**
     * Whether the water's direction is taken from the steepest triangular facet, with the one
     * receiver drawn across it, rather than snapped to the steepest of the eight neighbours.
     *
     * Top level rather than inside a section because four stages route water — erosion, the
     * post-cut outlet inside the sea-level step, glaciation and rivers — and the rule is the same
     * rule for all of them. Off is the plain steepest-neighbour rule the generator used until F18,
     * kept as the control the straight-bar census is measured against; see
     * [com.cartogenesis.worldgen.pipeline.FlowRouting.flowDirections] for what it does and why.
     */
    val facetRouting: Boolean = true,
    /**
     * Fraction of the world covered by ocean, 0..1.
     *
     * Read twice since S2, and the two readings are the point of the chunk. The plate stage draws
     * `(1 - this) / (1 - TectonicsConfig.continentalCrustSubmergedShare)` of the world as
     * continental crust, so the share of the map that stands above the waterline is a consequence
     * of what the crust is rather than of where a histogram was cut; and the sea-level stage still
     * cuts at the percentile this asks for, which is a statement about how much water the planet
     * has and is the one thing isostasy cannot supply. How far apart the two answers land — the
     * coverage the crust alone would give against the coverage asked for — is what
     * `IsostasyTest` measures and what the shoreline residual in `UnitsTest` reads.
     */
    val seaLevel: Float = 0.62f,
    val sea: SeaConfig = SeaConfig(),
    val glaciation: GlaciationConfig = GlaciationConfig(),
    val climate: ClimateConfig = ClimateConfig(),
    val rivers: RiverConfig = RiverConfig(),
    val lakes: LakesConfig = LakesConfig(),
    val ocean: OceanConfig = OceanConfig(),
    val nations: NationsConfig = NationsConfig(),
    val cultures: CulturesConfig = CulturesConfig(),
    val landmarks: LandmarksConfig = LandmarksConfig()
) {
    init {
        require(isPowerOfTwo(width) && isPowerOfTwo(height)) {
            "width/height must be powers of two for the FFT-based height integration (got $width x $height)"
        }
    }

    /** How wide one cell of this grid is, in kilometres. */
    val cellWidthKm: Double get() = scale.cellWidthKm(width)

    /** How tall one cell of this grid is, in kilometres. Not the same as [cellWidthKm]. */
    val cellHeightKm: Double get() = scale.cellHeightKm(height)

    /**
     * How tall one cell of this grid is as a fraction of how wide it is — a half on a square grid
     * of a world twice as wide as it is tall.
     *
     * What a distance field measured in cells has to be told before it can claim to measure ground.
     * See `JumpFloodDistance.run`.
     */
    val cellHeightInCellWidths: Double get() = cellHeightKm / cellWidthKm

    /** How much ground one cell of this grid stands for, in square kilometres. */
    val squareKilometresPerCell: Double get() = scale.squareKilometresPerCell(width, height)

    /** [kilometres] on the ground as a count of cells of this grid. */
    fun cellsFor(kilometres: Double): Float = scale.cellsAcrossFor(kilometres, width)

    /** [kilometres] on the ground as a whole number of cells, never fewer than [atLeast]. */
    fun wholeCellsFor(kilometres: Double, atLeast: Int = 1): Int =
        kotlin.math.round(cellsFor(kilometres)).toInt().coerceAtLeast(atLeast)

    /**
     * Re-targets the same world at a different grid size — used by HD export.
     *
     * There used to be a great deal here. Every reach, radius, depth and rate in the pipeline was
     * a count of cells or a fraction of an assumed range, and this function carried each of them
     * across a change of grid by hand; the class of bug that produced was fixed three times in the
     * month before it was written down. They are now lengths in kilometres, depths in metres and
     * rates in years, converted to the grid by [WorldScale] where each stage reads them, so the
     * scaling is arithmetic rather than a contract and there is nothing left here to carry.
     *
     * What is left is the tectonics' *widths*, and they are left deliberately. Since S2 a belt's
     * height does carry a metre value — every one of them is a share of
     * [TectonicsConfig.beltReliefMetres], so none of them appears below — but a belt's half-width
     * is still written as a count of cells, and carrying that count across a change of grid is
     * what this function is for. Writing the widths in kilometres instead would do exactly the
     * same arithmetic in a different place; it is a rename with no physics under it, and it is in
     * `TODO.md` rather than here. See REALISM_PLAN.md, S1 and S2.
     *
     *  - [TectonicsConfig.boundaryFalloffCells] is the width of a mountain belt and of the blur
     *    that softens the plate base. Left alone, a 4x larger grid makes both four times narrower
     *    in map terms, so plate edges surface as straight cliffs and coastlines turn angular.
     *  - Every crust-pair width and offset ([TectonicsConfig.andeanWidthCells],
     *    [TectonicsConfig.arcOffsetCells], [TectonicsConfig.arcWidthCells],
     *    [TectonicsConfig.collisionWidthCells], [TectonicsConfig.islandArcOffsetCells],
     *    [TectonicsConfig.islandArcWidthCells], [TectonicsConfig.riftWidthCells],
     *    [TectonicsConfig.riftShoulderOffsetCells], [TectonicsConfig.riftShoulderWidthCells]) is
     *    measured in cells for the same reason, and so is the geometry of a hotspot trail
     *    ([TectonicsConfig.hotspotChainLengthCells], [TectonicsConfig.hotspotSpacingCells],
     *    [TectonicsConfig.hotspotRadiusCells]). Left alone, a larger grid would narrow Tibet to the
     *    width of the Andes and the distinction between the crust pairs would quietly disappear at
     *    export resolution. The rift's *segmentation* knobs ([TectonicsConfig.riftSegmentMin],
     *    [TectonicsConfig.riftSegmentMax], [TectonicsConfig.riftAccommodation]) are the exception:
     *    they are map fractions already, so a rift breaks into the same half-grabens at every
     *    resolution and they are not touched.
     *  - A displacement and a blur radius are both lengths on the ground, so
     *    [TectonicsConfig.epochDriftCells] and [TectonicsConfig.beltAgeBlurCells] are more cells on
     *    a finer grid; `historyEpochs` and the three dimensionless ageing factors are not.
     *
     * And one more, for a reason of the same shape. [ClimateConfig.baseRainRate] is charged per
     * cell of wind travel, so a 4x wider grid depletes moisture four times over the same journey
     * and parches every interior. It is not a physical rate — the moisture march has no closed
     * form linking it to millimetres — and it is added to `orographicStrength * rise`, whose rise
     * is a per-cell elevation in the same unitless field. Giving one of the pair a kilometre while
     * the other keeps a bare ratio would read worse than leaving both; W3 in `REALISM_AUDIT.md`
     * calibrates the moisture budget and is where the pair gets its units.
     *
     * [scale] is not touched at all, and that is the point of it: how many cells a world is cut
     * into says nothing about how wide the world is, how high its land stands or how long a round
     * of erosion lasts.
     */
    fun atResolution(newWidth: Int, newHeight: Int): WorldGenConfig {
        val scale = newWidth.toFloat() / width
        return copy(
            width = newWidth,
            height = newHeight,
            tectonics = tectonics.copy(
                boundaryFalloffCells = tectonics.boundaryFalloffCells * scale,
                andeanWidthCells = tectonics.andeanWidthCells * scale,
                arcOffsetCells = tectonics.arcOffsetCells * scale,
                arcWidthCells = tectonics.arcWidthCells * scale,
                collisionWidthCells = tectonics.collisionWidthCells * scale,
                islandArcOffsetCells = tectonics.islandArcOffsetCells * scale,
                islandArcWidthCells = tectonics.islandArcWidthCells * scale,
                riftWidthCells = tectonics.riftWidthCells * scale,
                riftShoulderOffsetCells = tectonics.riftShoulderOffsetCells * scale,
                riftShoulderWidthCells = tectonics.riftShoulderWidthCells * scale,
                epochDriftCells = tectonics.epochDriftCells * scale,
                beltAgeBlurCells = tectonics.beltAgeBlurCells * scale,
                hotspotChainLengthCells = tectonics.hotspotChainLengthCells * scale,
                hotspotSpacingCells = tectonics.hotspotSpacingCells * scale,
                hotspotRadiusCells = tectonics.hotspotRadiusCells * scale
            ),
            climate = climate.copy(baseRainRate = climate.baseRainRate / scale)
        )
    }

    companion object {
        fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0
    }
}
