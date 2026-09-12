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
    val rangeVariationScale: Float = 13f,
    /**
     * Whether a convergent boundary's profile depends on which crusts are colliding.
     *
     * On it, the three convergent pairs build three different things: oceanic under continental a
     * narrow coastal range with a volcanic arc behind it, continental against continental a broad
     * flat-topped plateau, oceanic under oceanic an island arc. Off, every convergent boundary
     * gets the single [mountainHeight]-at-[boundaryFalloff] belt the generator used before, which
     * is what `BoundaryPairTest` turns off to show its measurement has teeth — with one profile
     * the Andes and Tibet are the same shape and the width-to-height ratios coincide.
     */
    val crustPairProfiles: Boolean = true,
    /**
     * Half-width, in cells, of the coastal range on the continental side of an oceanic–continental
     * margin. Deliberately far narrower than [collisionWidth]: the Andes are a few hundred
     * kilometres across where Tibet is well over a thousand, and that contrast is the whole point
     * of distinguishing the pairs. Measured in cells, so [WorldGenConfig.atResolution] rescales it.
     */
    val andeanWidth: Float = 14f,
    /** Crest height of that coastal range, in normalized elevation units. Narrow but tall. */
    val andeanHeight: Float = 0.52f,
    /**
     * How far inland of the suture the volcanic arc stands, in cells.
     *
     * A subducting slab does not melt at the trench; it melts once it is deep enough, which puts
     * the volcanoes a fixed distance behind the margin rather than on it. That offset is what
     * makes the margin asymmetric in a way a symmetric falloff cannot express.
     */
    val arcOffset: Float = 13f,
    /** Half-width of the volcanic arc ridge about its own axis, in cells. */
    val arcWidth: Float = 5f,
    /** Height of the volcanic arc above the range it rides on, in normalized elevation units. */
    val arcHeight: Float = 0.20f,
    /**
     * Half-width, in cells, of a continental collision plateau. Broad — see [andeanWidth].
     * Measured in cells, so [WorldGenConfig.atResolution] rescales it.
     */
    val collisionWidth: Float = 26f,
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
     * the reason for not damping it further is worth recording. A plateau that holds one altitude
     * for its entire run is one continuous ice cap once the climate stage sees it, and on seed 7,
     * where over half the land is already ice, that cap walls off the habitable ground behind it
     * into a single region: at a fifth of the variation one people held 48% of the habitable world
     * against `CultureRealmTest`'s 45% ceiling, and at half it holds 34%, better than the 38% the
     * generator managed before this chunk. The same change lifts seed 1234's warm-against-cold
     * coastal gap from 2.0% to 3.1%. A plateau that swells and sags is not only better geography,
     * it is the difference between one ice cap and several.
     */
    val plateauAlongVariation: Float = 0.50f,
    /**
     * How far from the suture the island arc stands, on the overriding plate, in cells.
     * Measured in cells, so [WorldGenConfig.atResolution] rescales it.
     */
    val islandArcOffset: Float = 8f,
    /** Half-width of the island-arc ridge about its own axis, in cells. */
    val islandArcWidth: Float = 7f,
    /**
     * Crest height of an island arc, in normalized elevation units.
     *
     * Sized so the arc mostly stays under water — it is built on oceanic crust, which sits a
     * [plateElevationBias] below continental — and only the swells of [rangeVariation] break the
     * surface. That is what makes an arc a chain of islands rather than a ridge of land.
     */
    val islandArcHeight: Float = 0.24f,
    /** Depth of the floor of a continental rift valley, in normalized elevation units. */
    val riftDepth: Float = 0.25f,
    /** Half-width of the rift trough, in cells. */
    val riftWidth: Float = 7f,
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
    val riftShoulderOffset: Float = 11f,
    /** Half-width of each shoulder about its own crest, in cells. */
    val riftShoulderWidth: Float = 7f,
    /**
     * Height of the rift shoulders, in normalized elevation units.
     *
     * Crust that is being pulled apart thins and drops, and the flanks rebound: the East African
     * rift is a trough between two escarpments, not a simple groove. Without the shoulders a rift
     * reads as an erosional valley rather than a tectonic one.
     */
    val riftShoulderHeight: Float = 0.10f,
    /**
     * Share of plates that carry a hotspot — a point fixed in the mantle that the plate drifts
     * over, leaving a line of seamounts behind it.
     *
     * Only oceanic plates are considered, so what this produces is island chains in open water
     * rather than volcanic fields inland.
     */
    val hotspotPlateFraction: Float = 0.35f,
    /** How long a hotspot trail runs before it has subsided to nothing, in cells. */
    val hotspotChainLength: Float = 110f,
    /** Distance between successive seamounts along a trail, in cells. */
    val hotspotSpacing: Float = 15f,
    /** Radius of a single seamount, in cells. */
    val hotspotRadius: Float = 5f,
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
     * Width, in cells, of the shelf plateau; a further band of the same width blends the plateau
     * back down to the natural sea floor, so the whole remap reaches `2 * shelfWidth` from the
     * coast. Measured in cells, so [WorldGenConfig.atResolution] rescales it like
     * [TectonicsConfig.boundaryFalloff] — left alone, a larger grid would shrink the shelf to a
     * sliver and coastlines would drop straight into deep water again.
     */
    val shelfWidth: Float = 20f,
    /**
     * Depth of the shelf plateau at its outer edge, in the same normalized units as
     * [SeaLevelResult.relativeElevation]. Kept shallower than the -0.12 cut [ClimateStage] uses
     * for `SHALLOW_OCEAN`, so the entire plateau reads as shallow water; the coast itself sits
     * shallower still, at a fixed -0.02, so there is a genuine (if gentle) slope across the shelf
     * rather than a dead-flat plain right up to the shore.
     */
    val shelfDepth: Float = 0.10f
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
    val landRecoveryRate: Float = 0.010f,
    /**
     * How far the thermal equator migrates toward the summer hemisphere, in degrees.
     *
     * Everything seasonal follows from this one number: it is what the latitude term of the
     * temperature curve is offset by, and it is what carries the wind belts and the rain belts
     * with it, so the horse latitudes and the ITCZ march up and down the map over the year the
     * way they do on Earth. Ten degrees is the modest, oceanic figure; the great continents swing
     * further than that, which is continentality's business rather than this one's.
     */
    val seasonalTilt: Float = 10f,
    /**
     * Whether the year has seasons at all.
     *
     * Off is exactly a tilt of zero: every seasonal field collapses onto the annual mean and the
     * world is bit for bit the one this generator made before seasons existed. Kept as a setting
     * rather than left to `seasonalTilt = 0` so that `SeasonsTest` can state plainly what it is
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
     * How much further inland a cell's seasonal swing grows once it can no longer feel the sea.
     *
     * Water's heat capacity is what damps a coast's year down from what its latitude alone would
     * predict — that is [ClimateStage]'s maritime-influence term. Continentality is the same fact
     * seen from the other side of the coastline: a cell with no nearby water to borrow the damping
     * from swings the full, undamped amount, and one at `continentality` above that. The amplitude
     * applied to the seasonal departure from the annual mean is `1 + continentality *
     * continentalityFactor`, where `continentalityFactor` is [ClimateStage]'s actual cell distance
     * to the nearest sea, clamped to 0..1 over three [OceanConfig.coastalReach] — a shoreline cell
     * (factor 0) keeps the amplitude at 1 and a cell three reaches inland or further (factor 1)
     * reaches the full `1 + continentality`. An earlier version read the blurred water-exposure
     * field here instead, on the theory that "exposed to water" and "close to water" were the same
     * question; they were not at this radius — two box-blur passes read barely 0.3 exposure right
     * at the edge of a single `coastalReach`, so a coast measured that way was already most of the
     * way to fully continental. Zero reproduces the world from before this setting existed, bit
     * for bit — Siberia and Ireland at the same latitude, swinging by the same amount.
     */
    val continentality: Float = 0.6f
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
    val erodibility: Float = 0.055f,
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
     * stream-power form [erodibility] uses for incision, because carrying capacity and cutting
     * power come from the same quantity.
     *
     * Transport-limited deposition: a cell carrying more than this lays the excess down instead of
     * cutting. Both terms are held against the map rather than the grid, so the scheme survives a
     * change of resolution for the same reason incision does.
     *
     * Note the ratio to [erodibility] rather than the absolute value. At 20 against 0.055 a cell
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
     * that feels this setting first. See [deltaFreeboard] for the measurements.
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
     * How far from a mouth, in cells, sediment may be laid — the radius of a delta or a lacustrine
     * fan. In cells rather than against the map, and so rescaled by
     * [WorldGenConfig.atResolution] along with everything else measured that way.
     */
    val deltaReach: Int = 6,
    /**
     * How high above the shoreline a delta cell is built, as a fraction of the land's elevation
     * range.
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
     * its numbers as a measure of disturbance rather than only as pass or fail.
     */
    val deltaFreeboard: Float = 0.008f
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
 * Every length here is in cells and so is rescaled by [WorldGenConfig.atResolution], for the same
 * reason [SeaConfig.shelfWidth] is: a trough four cells wide on a 512 grid is a trough sixteen
 * cells wide on a 2048 one, and anything else changes the world rather than its detail.
 */
@Serializable
data class GlaciationConfig(
    /**
     * Off reproduces the pre-B4 world bit for bit — the stage returns the sea-level result it was
     * handed, the same object, so nothing downstream can even tell it ran. That is what makes the
     * lake-density guard's "before" honest.
     */
    val enabled: Boolean = true,
    /**
     * Mean annual temperature, in C, at or below which ice is permanent and flows.
     *
     * Judged on a provisional temperature computed from latitude and altitude alone — the same
     * curve [ClimateStage] uses, because it is literally the same function — since the climate
     * stage itself cannot run until the terrain this stage carves is final. Zero is the honest
     * line: it is where [ClimateStage.classify]'s own ice and tundra gates sit, so the mask is
     * bounded by the classification the plan asked for rather than merely near it.
     */
    val freezingC: Float = 0f,
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
     * flow paths per unit of map as at 512 while `atResolution` kept each trough the same fraction
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
     * How much local relief the ground must have before valley-glacier machinery runs on it, as a
     * fraction of the land's elevation range.
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
     * ground "channelled" and the mesh untouched. At 0.35 the channelled share is a fifth of the
     * frozen ground on seed 42 and two fifths on 718106, which is the mountainous part of each.
     */
    val valleyRelief: Float = 0.35f,
    /**
     * The radius over which [valleyRelief] is measured, in multiples of [valleyWidth].
     *
     * About twice the trough the ice would cut: wide enough to take in both walls of the valley
     * and the interfluves beyond, narrow enough that a continental slope hundreds of cells across
     * does not read as a valley.
     */
    val reliefWindow: Float = 2f,
    /**
     * The shortest channelled flow path that may become a trough, in cells.
     *
     * A catchment threshold alone cannot tell a glacier from a gully: twenty cells of upstream
     * frozen ground is twenty cells whether they lie in a mountain valley or in a hollow on a
     * plain. A trough is a *long* landform, so this asks for a run of channelled ground — measured
     * from the furthest head above the cell to the furthest snout below it — before any of it is
     * carved.
     */
    val minTroughLength: Int = 14,
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
     * How far sheet ice planes the ground down away from its basins, as a fraction of the range.
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
    val sheetLowering: Float = 0.012f,
    /** How deep a scour basin is cut below its own rim, as a fraction of the elevation range. */
    val sheetBasinDepth: Float = 0.026f,
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
     * The largest single basin the ice may cut, as a fraction of the whole map.
     *
     * Not a tuning knob but a fact about worlds: Lake Superior, the largest lake on Earth that is
     * not a sea, is 82,100 km² against Earth's 510 million, which is 0.016% of the surface. A body
     * of water larger than that share of a world is not a lake, it is the Caspian. Expressed
     * against the map rather than in cells so that 512, 1024 and 2048 draw the same lake: 42 cells
     * at 512, 168 at 1024, 671 at 2048, which on a 12,000 km world is about 11,500 km² at every
     * one of them.
     *
     * A basin over the cap is not thrown away — that would delete the lake country rather than
     * size it — it is peeled inward, ring by ring, until its floor fits. The rest of the blob keeps
     * the scour without the water.
     */
    val maxLakeShareOfMap: Float = 0.00016f,
    /**
     * The smallest basin the ice bothers to cut, as a fraction of the whole map.
     *
     * The floor that stops a finer grid from manufacturing speckle. [LakesConfig.minCells] is a
     * count of cells and so means a different lake at every resolution — on a 12,000 km world its
     * twelve cells are 3,300 km² at 512 and 206 km² at 2048 — which is exactly how a 2048 render
     * ends up sprinkled with ponds that 512 never had. A tenth of [maxLakeShareOfMap] is about
     * 1,150 km², Lake Geneva's order of magnitude, and that is roughly the smallest body a map of
     * a whole world should draw at all.
     *
     * Four cells at 512, 17 at 1024, 67 at 2048. At 512 and 1024 [LakesConfig.minCells] is still
     * the binding floor, so this changes nothing there; at 2048 and above it takes over, which is
     * the point.
     */
    val minLakeShareOfMap: Float = 0.000016f,
    /**
     * The size of the basins, as the number of noise periods across the map.
     *
     * A fraction of the world rather than a count of cells, so the same world gains detail rather
     * than changing character when it is generated at export resolution.
     */
    val sheetBasinScale: Float = 26f,
    /**
     * How much a cell's own hollowness counts toward being chosen as a basin, against the noise.
     *
     * Zero would put the lakes wherever the noise fell; this pulls them into the hollows the
     * terrain already has, which is what makes them look like they belong to the ground rather
     * than like a pattern laid over it. It is still the noise that decides their shape.
     */
    val sheetConcavity: Float = 0.8f,
    /** Half-width of the widest trough, in cells: how far up the valley sides the ice reaches. */
    val valleyWidth: Float = 6.5f,
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
    /** How far a full glacier lowers its bed, as a fraction of the land's elevation range. */
    val deepening: Float = 0.004f,
    /**
     * The extra cut in the over-deepened reaches between the steps, in the same units.
     *
     * This is the number that makes lakes. A basin holds water only if its floor lies below the
     * step downstream of it, and the difference between the two is exactly this — so it has to
     * clear [LakesConfig.minDepth] with room to spare, at a glacier well short of full strength.
     */
    val overDeepening: Float = 0.024f,
    /**
     * How much descent ends a reach and starts the next basin, as a fraction of the range.
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
     * [basinSpacing] does, so a trough down a mountainside was a staircase of a dozen or more short
     * basins, and a dozen basins across a trough running at 45 degrees is a dozen straight bars of
     * water lying parallel — a paternoster chain drawn with a ruler. At twice the descent per step
     * the same trough carries three or four basins instead, each broad enough to read as a lake:
     * measured on seed 42 at 1024, the share of standing water in parallel grid-bearing bars fell
     * from 2.3% to 0.8%, and the count of separate lakes from 37 to 30.
     */
    val basinDrop: Float = 0.060f,
    /**
     * The most cells one reach may run before the next basin starts, whatever the descent.
     *
     * The other half of the same rule, for ground with no descent to speak of: without it a plain
     * inside the ice would be one reach a thousand cells long. The two terms simply add, so a
     * reach ends when it has fallen [basinDrop] *or* run this far, whichever happens first.
     */
    val basinSpacing: Float = 16f,
    /** Share of a reach the basin occupies; the rest is the step at its lower end. */
    val basinShare: Float = 0.75f,
    /** Radius of the bowl bitten out of a glacier's head, in cells. */
    val cirqueRadius: Float = 3f,
    /** How deep that bowl is cut below the headwall, as a fraction of the elevation range. */
    val cirqueDepth: Float = 0.014f,
    /**
     * How far a glacier runs on past the freezing line before it melts, in cells.
     *
     * A glacier's snout sits below its own snowline — that is what an ablation zone is — so the
     * trough, and the moraine at its end, belong a little way into ground that is not frozen. This
     * is the only licence the mask gets; nothing is carved further down than this.
     */
    val runOut: Int = 8,
    /** Height of the ridge of spoil left at a land terminus, as a fraction of the range. */
    val moraineHeight: Float = 0.045f,
    /**
     * Height of the recessional moraine laid across the valley at the lower end of an accepted
     * basin, as a fraction of the range. Zero by default, and the zero is the fix.
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
    val riegelHeight: Float = 0f,
    /**
     * Whether a glacier that ends in the sea leaves a trough on the sea floor.
     *
     * The drowned half of a fjord. The coastline itself is settled before this stage runs and is
     * not moved — see [SeaLevelStage]'s note on why the shelf never touches land — so what is left
     * to model is the bathymetry: a deep basin at the mouth shallowing out to the shelf, which is a
     * fjord's sill.
     */
    val fjords: Boolean = true,
    /** How deep a fjord basin is cut at the mouth, in [SeaLevelResult.relativeElevation] units. */
    val fjordDepth: Float = 0.20f,
    /** How far out to sea that basin reaches, in cells. */
    val fjordReach: Int = 6
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
    val minCells: Int = 12,
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
    val terrain: TerrainConfig = TerrainConfig(),
    val tectonics: TectonicsConfig = TectonicsConfig(),
    val erosion: ErosionConfig = ErosionConfig(),
    /** Fraction of the world covered by ocean, 0..1. */
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

    /**
     * Re-targets the same world at a different grid size â€” used by HD export.
     *
     * Some settings are measured in cells and have to be rescaled, or the world changes character
     * rather than just gaining detail:
     *  - [TectonicsConfig.boundaryFalloff] is the width of a mountain belt and of the blur that
     *    softens the plate base. Left alone, a 4x larger grid makes both four times narrower in
     *    map terms, so plate edges surface as straight cliffs and coastlines turn angular.
     *  - Every crust-pair width and offset ([TectonicsConfig.andeanWidth],
     *    [TectonicsConfig.arcOffset], [TectonicsConfig.arcWidth], [TectonicsConfig.collisionWidth],
     *    [TectonicsConfig.islandArcOffset], [TectonicsConfig.islandArcWidth],
     *    [TectonicsConfig.riftWidth], [TectonicsConfig.riftShoulderOffset],
     *    [TectonicsConfig.riftShoulderWidth]) is measured in cells for the same reason, and so is
     *    the geometry of a hotspot trail ([TectonicsConfig.hotspotChainLength],
     *    [TectonicsConfig.hotspotSpacing], [TectonicsConfig.hotspotRadius]). Left alone, a larger
     *    grid would narrow Tibet to the width of the Andes and the distinction this chunk exists
     *    for would quietly disappear at export resolution.
     *  - [SeaConfig.shelfWidth] is the width of the continental shelf, in the same cell terms as
     *    [TectonicsConfig.boundaryFalloff] and for the same reason: left alone, a larger grid
     *    would shrink it to nothing and every coast would drop straight into deep water again.
     *  - [ClimateConfig.baseRainRate] is charged per cell of wind travel, so a 4x wider grid
     *    depletes moisture four times over the same journey and parches every interior.
     *  - [ErosionConfig.passes] moves material one cell per sweep, so covering the same distance
     *    across the map takes proportionally more sweeps on a finer grid. Left alone, a large map
     *    would come out barely eroded at all.
     *  - [ErosionConfig.deltaReach] is the radius of a delta, in cells, so a finer grid would
     *    otherwise shrink every delta to a speck.
     *  - Every length in [GlaciationConfig] — the width of a trough, the spacing of the basins
     *    along it, the reach of a cirque, how far the snout runs past the freezing line — is in
     *    cells for the same reason, and a trough that stayed four cells wide on a 2048 grid would
     *    be a gully rather than a glacial valley.
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
            tectonics = tectonics.copy(
                boundaryFalloff = tectonics.boundaryFalloff * scale,
                andeanWidth = tectonics.andeanWidth * scale,
                arcOffset = tectonics.arcOffset * scale,
                arcWidth = tectonics.arcWidth * scale,
                collisionWidth = tectonics.collisionWidth * scale,
                islandArcOffset = tectonics.islandArcOffset * scale,
                islandArcWidth = tectonics.islandArcWidth * scale,
                riftWidth = tectonics.riftWidth * scale,
                riftShoulderOffset = tectonics.riftShoulderOffset * scale,
                riftShoulderWidth = tectonics.riftShoulderWidth * scale,
                hotspotChainLength = tectonics.hotspotChainLength * scale,
                hotspotSpacing = tectonics.hotspotSpacing * scale,
                hotspotRadius = tectonics.hotspotRadius * scale
            ),
            sea = sea.copy(shelfWidth = sea.shelfWidth * scale),
            erosion = erosion.copy(
                passes = (erosion.passes * scale).toInt(),
                deltaReach = (erosion.deltaReach * scale).toInt().coerceAtLeast(1)
            ),
            glaciation = glaciation.copy(
                valleyWidth = glaciation.valleyWidth * scale,
                basinSpacing = glaciation.basinSpacing * scale,
                cirqueRadius = glaciation.cirqueRadius * scale,
                runOut = (glaciation.runOut * scale).toInt().coerceAtLeast(1),
                fjordReach = (glaciation.fjordReach * scale).toInt().coerceAtLeast(1),
                // A trough is a length on the ground, so it is more cells on a finer grid.
                // `reliefWindow` is a multiple of `valleyWidth`, `sheetBasinScale` a count of
                // periods across the whole map, and the three lake knobs are map fractions, so
                // none of them is touched.
                minTroughLength = (glaciation.minTroughLength * scale).toInt().coerceAtLeast(2)
            ),
            climate = climate.copy(baseRainRate = climate.baseRainRate / scale),
            nations = nations.copy(slopeResistance = nations.slopeResistance * scale)
        )
    }

    companion object {
        fun isPowerOfTwo(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0
    }
}
