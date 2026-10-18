package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.concurrent.parallelism
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.time.TimeSource

/**
 * The rain: water carried along the wind in kilograms per square meter, taken up from the sea and
 * the ground, rained out where the air cannot keep it, and conserved between the two.
 *
 * # Two layers
 *
 * The column's water is carried in two layers, each on its own wind:
 * - **The boundary layer**, the dry atmosphere's lowest layer, a fixed mass of air
 *   ([ColumnWater.BOUNDARY_LAYER_MASS_KG_PER_M2]) over the ground, well mixed, with one specific
 *   humidity. The sea evaporates into it by the bulk formula on its own humidity, and the ground
 *   gives its water back into it. It blows the surface wind ([Season.eastwardMps]), the boundary
 *   layer's drag balance, which carries the trades' inflow into the rising branches.
 * - **The free troposphere** above it, which carries the rest of the column's water on the levels
 *   above ([Season.freeEastwardMps], [BoundaryLayer.freeTroposphereWindMps]): the circulation's
 *   zonal wind and the stationary waves' own, and the cells' return aloft.
 *
 * The two exchange their water at every cell, conservatively, in two ways. **What the boundary
 * layer's wind converges rises out of it**: its air is carried as a tracer beside its water, by
 * the same transport, so where the march's own faces gather more air into a cell than the layer
 * holds, the excess leaves it upward with its water into the free troposphere, and where they take
 * air out, the free troposphere's lowest air comes down to make it up; a uniform humidity stays
 * uniform under any wind. **The layer's top is stirred**: the free troposphere sinks into it where
 * its radiative cooling is balanced by its descent, and the convection carries as much out of it,
 * a mass of air each way at [exchangeVelocityMps]. The free troposphere's air comes down at its
 * own relative humidity and the saturation humidity of the boundary layer's top over the ground
 * there.
 *
 * # What is solved
 *
 * The steady state of each layer's water budget on the sphere, `div(W V) = E - P - X`, with `X`
 * the exchange. Marched column by column along each cell's own zonal wind `u`, the steady
 * equation is a march in space and not in time: crossing a column of ground width
 * `dx = cellWidth cos(latitude)` takes `dt = dx / |u|`, every source and sink is charged over that
 * `dt`, and what a row carries across a column's side is the mass flux `|u| W cellHeight`. The
 * march keeps that flux in one unit for every row, the column water it would be at
 * [TRANSPORT_SPEED_MPS], so a row's **parcel** is `|u| W / U`, and the parcels of different rows,
 * different speeds and different layers add without weights: a rate at a cell is the same parcel
 * in both layers, whichever wind crosses it.
 *
 * Between rows the wind's meridional part and the eddies carry water through each face, `v W`
 * and `K dW/dy` times the face's length `cellWidth cos(latitude of the face)`, each taken out of
 * one row and given to the other. Both are solved implicitly for the whole column at once: one
 * tridiagonal system, upwind for the mean wind and centered for the eddies, which is positive
 * however large the shares and moves no water but through a face (docs/DESIGN_LEDGER.md, C1b2,
 * for the explicit sub-steps it replaced).
 *
 * # Two sweeps, and the bank between them
 *
 * A column-by-column march has to run one way. A cell whose wind blows east is marched by the
 * eastward sweep and one whose wind blows west by the westward one; every cell belongs to exactly
 * one, in each layer. Water that crosses from one sweep's cell into the other's where two zonal
 * winds meet head on is **banked** at the receiving cell and taken up by the other sweep when it
 * reaches it, its cloud and falling water with it, to fall over their own delays there; rained out
 * at the turn instead, they drew a wet line wherever the zonal wind reversed. So is what the mean
 * wind carries through a face into a row of the other sweep: each row's outflow through such a
 * face is solved in its own sweep's system, implicitly, beside the inflow its own sweep's rows give
 * it in the same column, and banked at the receiving cell, the eastward sweep's for the westward
 * one this lap and the westward sweep's for the eastward one the next (docs/DESIGN_LEDGER.md,
 * A1-6b). The eddies' mixing between the two sweeps' rows is solved by the westward sweep with the
 * eastward rows beside its own in one system.
 *
 * # The exchange inside the lap
 *
 * What each layer sends the other at a cell, solved implicitly in its own water over its own
 * crossing, waits at the cell ([SeasonMarch]'s inboxes) and enters the other layer there as a
 * steady rate over that layer's crossing, as the surface's sources enter; the inboxes are held in
 * the budget like any bank. A lap is marched in stages of a few dozen columns of one sweep, the
 * free troposphere one stage behind the boundary layer ([MarchSchedule], [SeasonMarch.marchStage]).
 * Whichever layer crosses a cell second in the lap takes in what the first sent it in that very
 * lap, and the first takes in what the second sent in the lap before, so only one direction of the
 * exchange runs a lap behind: the free troposphere takes in the boundary layer's water of the same
 * lap in every cell but those the boundary layer marches westward and it eastward, and what it
 * sends down waits for the next. Marched side by side, each layer a lap behind the other, the loop
 * through both layers' inboxes closes a lap late both ways and takes a quarter to a third more laps
 * to settle (docs/DESIGN_LEDGER.md, W2f). Every read is of a stage done before the one reading
 * it, or of the last lap, so the answer does not depend on how the stages are scheduled. At the
 * march's steady state a lap's lag is no lag, and a uniform state stays uniform however long a
 * cell's crossing.
 *
 * # The sweeps are held
 *
 * Which sweep marches a cell is the dry atmosphere's wind's to say, the wind a coupled loop's first
 * lap runs under from a cold start ([Coupling.sweepWinds]), and every lap keeps that partition: a
 * cell whose wind later turns against its sweep is carried along it at the march's least speed, as
 * calm. Where the zonal wind is weak, near every reversal, the solved atmosphere's lap-to-lap
 * changes turned ten thousand cells a lap from one sweep to the other, and the march's answer
 * stepped with each turn, so the coupled loop never settled (docs/DESIGN_LEDGER.md, A1-6b).
 *
 * # The sinks
 *
 * Solved together with the sources over each crossing ([ColumnCrossing]):
 * - **The column's rain**, from the free troposphere, at Bretherton, Peters and Back's (2004)
 *   relation over the tropical oceans, `P = exp(11.4 (r - 0.522))` mm a day for monthly means
 *   ([columnRainMmPerDay]). Their humidity is the column's, but the boundary layer's part of it is
 *   insensitive to the rain ("below 900 mb, q_n is insensitive to P, presumably due to rapid
 *   turbulent interactions with a sea surface of nearly constant temperature", section 3e), and the
 *   rain moves with the troposphere's humidity between 850 and 400 hPa; so the law reads the free
 *   troposphere's water over a boundary layer at the marine humidity their columns stood on
 *   ([lawHumidity]). Its saturated column is the free troposphere's own air read from sea level, not
 *   the column over the ground under it, so high ground does not raise it: that water came from the
 *   air above the lowlands' boundary layer and is read against what that air holds. Over land and
 *   outside the tropics the relation is assumed, not measured.
 * - **The ground's lifting.** Air carried up the ground at `w`, the boundary layer's wind's climb
 *   over the ground read at [CLIMB_TERRAIN_SIGMA_KM] on the ground, lifts the boundary layer, whose
 *   air climbs dry to its own lifting condensation level and condenses above it at Smith and
 *   Barstad's (2004) saturated rate ([ColumnWater.boundaryLayerLiftYield]). Air that has already
 *   been lifted to its saturation once condenses again only where it is lifted higher, so a run of
 *   small hills rains as one. The cloud turns to falling hydrometeors and falls out over Smith and
 *   Barstad's two delays; where the air comes down the ground its descent evaporates the cloud that
 *   has not fallen, at the rate the same lift would condense it.
 * - **The cap.** What the boundary layer cannot hold at all, over its air's saturation at the
 *   ground's own temperature and pressure, condenses to cloud and falls out over the same delays;
 *   the free troposphere's water over its saturated column rains at once.
 * - **Convergence**, a closure switched off by default (`ClimateConfig.convergenceRain`), on the
 *   free troposphere's water.
 *
 * The marine inversion's lid slows the column's rain, the cloud's conversion to rain and the
 * convergence closure on the land under it. None of them deletes water: what they hold back is
 * carried on.
 *
 * **What condenses** is counted apart from what falls ([Result.julyHalfCondensationMm]): the
 * column's rain and the convergence closure's, which leave the vapor where they fall, and the
 * cloud's growth less its evaporation. The cloud is carried, so its rain falls downwind of where it
 * condensed; the latent heat is released where it condenses, and that is what the atmosphere reads.
 *
 * # The sources
 *
 * The open sea evaporates by the bulk formula, from its water ([Season.seaSurfaceC]) into the
 * boundary layer at the layer's own specific humidity, at the scalar mean wind the turbulence feels
 * ([Season.scalarWindAt10mMps]), the mean wind and the weather's gusts together, which does not fall
 * to nothing where the mean wind reverses. The ground gives back Budyko's share of its own year of
 * rain against its potential evapotranspiration, each half of the year its share of the potential as
 * far as that half's rain and the root zone's store can supply it ([halfYearReturnsMm]).
 *
 * # One season at a time, coupled through the year's rain
 *
 * Each half-year is marched on its own wind and temperatures. With a [Coupling] the atmosphere is
 * solved again between laps from each half's condensation, and the laps run until its heating has
 * settled too. The ground's return couples the halves: it is Budyko's share of the cell's
 * **annual** rain, after the blur, against the annual potential evaporation, which is what the
 * rivers and lakes read too, and the root zone carries water from one half into the other
 * ([ROOT_ZONE_STORE_MM]). The laps run both seasons in step and update the return between laps
 * until the year's rain stops moving ([CONVERGED_SHARE]); the last lap's rain and potential are
 * then the ones the return was set from, so the surface budget, rain equals the ground's return
 * plus the runoff, closes cell by cell.
 *
 * The march has no graphics path yet (docs/TODO.md, the moisture accelerator). Its per-cell work is
 * [ColumnCrossing]'s, which reads one cell and the layer's arriving water and nothing else, so each
 * column's rows are one wavefront for a device's kernel; the exchange between the layers reads only
 * the other layer's last lap at the same cell.
 */
object MoistureMarch {

    /**
     * The speed the march's unit of water is stated at, in meters a second: the belts' own,
     * [PressureWind.BELT_SPEED_MPS], the wind at the core of the trades and the westerlies. A unit
     * and nothing else where the boundary layer is solved; with the belts alone, the belts' control,
     * it is also the scalar mean wind the sea's evaporation is driven by everywhere.
     */
    const val TRANSPORT_SPEED_MPS = PressureWind.BELT_SPEED_MPS.toDouble()

    /**
     * The least zonal speed the march divides by, as a share of [TRANSPORT_SPEED_MPS]: a
     * thousandth, 7.5 mm a second, at which a cell of the 1,024-row grid takes a month to cross.
     * Not physics but a guard against the division: a cell that slow is in its own local balance
     * long before the month is out, rain against its sources and the faces' exchange, and a
     * slower floor moves nothing (docs/DESIGN_LEDGER.md, C1b2).
     */
    private const val SLOWEST_SPEED_SHARE = 1.0e-3

    /**
     * Bretherton, Peters and Back's (2004) relation between the column's rain and its relative
     * humidity, for monthly means over every tropical ocean and season: `P = exp(a (r - b))` mm a
     * day with `a` = 11.4 and `b` = 0.522, from four years of SSM/I water vapor and rain. At
     * Earth's global mean rain, 2.7 mm a day, it puts the column at 0.61 of its saturated water.
     */
    const val COLUMN_RAIN_PER_HUMIDITY = 11.4
    const val COLUMN_RAIN_HUMIDITY_OFFSET = 0.522

    /**
     * The relative humidity of the air at the sea's surface in the columns Bretherton and others'
     * relation was read on: Dai's (2006) 75 to 80 percent over most of the oceans in every season,
     * from ship observations, its middle. The boundary layer the law counts beside the free
     * troposphere's water ([lawHumidity]), and the surface air whose rise sets the saturated column's
     * profile ([ColumnWater.PARCEL_RELATIVE_HUMIDITY]); the sea now evaporates into the boundary
     * layer's own humidity.
     */
    const val MARINE_RELATIVE_HUMIDITY = 0.775

    /**
     * The free troposphere's net radiative cooling, kelvin a day: EUREC4A's clear-sky soundings
     * "largely negative from the surface up to 10 km, with a median value around -1 K/day"
     * (Albright and others 2021, read as chapter 3 of Albright's thesis, section 3.4.1).
     */
    const val FREE_TROPOSPHERE_COOLING_K_PER_DAY = 1.0

    /**
     * The velocity the boundary layer's top exchanges air at with the free troposphere, each way,
     * meters a second, under air whose temperature falls [lapseRateCPerKm] a kilometer: the speed at
     * which the free troposphere sinks where its radiative cooling
     * ([FREE_TROPOSPHERE_COOLING_K_PER_DAY]) is balanced by the warming of its descent, `Q / (Gamma_d
     * - gamma)`, 3.5 mm a second under the standard 6.5 K/km, against which the convection carries
     * the same mass up out of the layer: radiative-convective equilibrium's overturning, which the
     * march's winds do not resolve.
     *
     * Not the trade cumulus' mass flux out of the subcloud layer, 15.2 mm a second over EUREC4A
     * (Albright's thesis, chapter 7), nor the entrainment at its top, 18.8 (Galewsky and Los 2026,
     * section 2.1): those exchange the subcloud layer with the cloud layer just above it, 13.0 g/kg
     * against its 15.1 (Galewsky and Los, Table 1), inside the moist layer under the trade inversion.
     * The march's free troposphere holds one relative humidity from the boundary layer's top up, the
     * drier air above the inversion with the cloud layer, so the cumulus' mass flux against it took
     * the subcloud air down to 0.59 of saturation and evaporated half again the sea's water
     * (docs/DESIGN_LEDGER.md, W2e).
     */
    fun exchangeVelocityMps(lapseRateCPerKm: Double): Double {
        val stabilityKPerM = DRY_LAPSE_K_PER_M - lapseRateCPerKm / METERS_PER_KM
        return if (stabilityKPerM > 0.0) FREE_TROPOSPHERE_COOLING_K_PER_DAY / SECONDS_PER_DAY_DOUBLE / stabilityKPerM else 0.0
    }

    /** Seconds in a day. */
    private const val SECONDS_PER_DAY_DOUBLE = 86_400.0

    /**
     * Smith and Barstad's (2004) two delays in the orographic rain, seconds: cloud water turning
     * into hydrometeors, and hydrometeors falling out. A thousand each in their worked examples;
     * Smith and others (2005) read 500 to 5,000 from the Oregon Cascades' rain and isotopes.
     */
    const val CLOUD_CONVERSION_SECONDS = 1_000.0
    const val FALLOUT_SECONDS = 1_000.0

    /**
     * The length the ground's climb is read on, the standard deviation of a Gaussian on the ground,
     * kilometers: how far the march's wind carries the condensate over Smith and Barstad's two
     * delays, `U (tau_c + tau_f)`, 15 km at [TRANSPORT_SPEED_MPS]. Shorter terrain makes condensate
     * that the lee's descent evaporates before it falls, which their transfer function filters by the
     * same delays (`1 / ((1 + i sigma tau_c)(1 + i sigma tau_f))`), so a slope shorter than this is
     * not a slope the rain sees; and a length on the ground and not a count of cells, so every grid
     * reads the same terrain.
     */
    val CLIMB_TERRAIN_SIGMA_KM = TRANSPORT_SPEED_MPS * (CLOUD_CONVERSION_SECONDS + FALLOUT_SECONDS) / METERS_PER_KM_DOUBLE

    /**
     * The most laps of both seasons round the planet. The first starts from air at four fifths of
     * its saturation over dry ground and leaves a year's rain for the second's ground to give back;
     * each later one carries the return a lap further toward the year it belongs to, until the
     * year's land rain moves by less than [CONVERGED_SHARE], and with a [Coupling] until the
     * atmosphere's heating has settled too.
     *
     * Forty, a ceiling and not a count: the coupled loop settles in 13 to 18 laps on the standard
     * worlds at 512 and 1,024 rows (docs/DESIGN_LEDGER.md, A1-5). A loop that reaches the ceiling has
     * a feedback at or over one, which more laps would not settle; it stops with the last lap's
     * rain, and `AtmosphereCouplingTest` holds the standard worlds short of it.
     */
    const val MAX_LAPS = 40

    /**
     * When the year's rain has stopped moving: the land's mean change of annual rain from one lap
     * to the next, cell by cell, and the whole planet's, each under six thousandths of its mean rain, the twice-standard-error
     * of GPCP's global rain over 17 years (2.9 of 486.9 thousand km³; Trenberth and others 2007),
     * which `MoistureClosureTest` holds the march's last storage change to as well. A year nearer
     * its fixed point than that is nearer than Earth's own rain is known.
     */
    const val CONVERGED_SHARE = 2.9 / 486.9

    /** The fewest laps run before the year's rain is asked whether it has settled. */
    private const val MIN_LAPS = 3

    /** Seconds in a year: 365.25 days. */
    const val SECONDS_PER_YEAR = 3.15576e7

    /**
     * The share of its saturation each layer's air starts at on the first lap. A starting guess and
     * no more: the laps wash it out.
     */
    private const val INITIAL_HUMIDITY = 0.8

    /** One kilometer in meters. */
    private const val METERS_PER_KM_DOUBLE = 1_000.0

    /** What a season's march reads, every per-cell array row-major on the world's grid. */
    class Season(
        /** The half-year's air temperature at the ground, degrees Celsius. */
        val airTemperatureC: FloatArray,
        /** The water's temperature at sea cells, current and all; ignored on land. */
        val seaSurfaceC: FloatArray,
        val seaIce: BooleanArray,
        /**
         * The surface wind, the boundary layer's, belts and pressure departure together, meters a
         * second, positive east: what carries the boundary layer's water, what climbs the ground and
         * what the evaporation feels.
         */
        val eastwardMps: FloatArray,
        /** Its meridional part, meters a second, positive toward the south (down the map). */
        val southwardMps: FloatArray,
        /**
         * The wind's mean speed at 10 m through every gust and calm, meters a second, which the
         * sea's evaporation reads ([BoundaryLayer.scalarWindAt10mMps]); null for the belts' control,
         * which reads [TRANSPORT_SPEED_MPS].
         */
        val scalarWindAt10mMps: FloatArray? = null,
        /** The wind at 2 m over FAO-56's grass, meters a second; null for its 2 m/s station mean. */
        val windAt2mMps: FloatArray? = null,
        /** The marine inversion's hold on each land cell's rain, 0..1, or null when it is off. */
        val inversionSuppression: FloatArray?,
        /** The half-year's mean sun at the top of the air, per row, MJ m⁻² day⁻¹. */
        val extraterrestrialOfRow: DoubleArray,
        /**
         * The free troposphere's wind, meters a second, eastward and southward: the levels above the
         * boundary layer weighted by their vapor ([BoundaryLayer.freeTroposphereWindMps]). Null for
         * the belts' control, whose whole column travels on the surface wind.
         */
        val freeEastwardMps: FloatArray? = null,
        val freeSouthwardMps: FloatArray? = null
    ) {
        /** The free troposphere's eastward wind: [freeEastwardMps], or the surface's when there is none. */
        val freeEastward: FloatArray get() = freeEastwardMps ?: eastwardMps

        /** The free troposphere's southward wind: [freeSouthwardMps], or the surface's when there is none. */
        val freeSouthward: FloatArray get() = freeSouthwardMps ?: southwardMps

        /** The two layers' zonal winds, which hold each cell in its sweeps ([Coupling.sweepWinds]). */
        fun sweepWinds(): SweepWinds = SweepWinds(eastwardMps, freeEastward)
    }

    /**
     * The zonal winds that choose each cell's sweep in each layer, meters a second eastward,
     * row-major: the boundary layer's and the free troposphere's.
     */
    class SweepWinds(val boundaryEastwardMps: FloatArray, val freeEastwardMps: FloatArray)

    /**
     * What the march reads beyond the two seasons. [run] builds the seasons itself, so that a
     * coupled run's first seasons are not held here for its every lap.
     */
    class Inputs(
        val config: WorldGenConfig,
        val isLand: BooleanArray,
        /** `SeaLevelResult.relativeElevation`, for the inversion's lid. */
        val relativeElevation: FloatArray,
        /** Height above the sea in meters at land cells, zero at sea. */
        val elevationM: FloatArray,
        /** The relief share of the inversion's lid, `SeaLevelResult.relativeElevation` units. */
        val lidElevation: Float,
        /** The blur's width on the ground, the standard deviation of its Gaussian, kilometers. */
        val blurSigmaKm: Double,
        /**
         * The pieces a column's rows are marched in, side by side: half the processor's workers by
         * default, so that the four marches running at once (two seasons, two layers) offer the
         * pool twice its workers and keep it busy while a piece waits on the pool. A machine of one
         * or two workers marches its rows in order. Every row of a column is independent of every
         * other, so the answer does not depend on the number (`MarchScheduleTest` marches a world
         * in one piece and in several and holds them equal).
         */
        val rowPieces: Int = defaultRowPieces()
    )

    /** What the march hands back, every field row-major, millimeters a year. */
    class Result(
        val julyHalfRainMm: FloatField,
        val januaryHalfRainMm: FloatField,
        /** The year's rain whose water last evaporated from land: the recycling numerator. */
        val landOriginRainMm: FloatField,
        /** The year's FAO-56 reference evapotranspiration at every cell. */
        val potentialEvapotranspirationMm: FloatField,
        /** The year's open-water evaporation at every cell. */
        val openWaterEvaporationMm: FloatField,
        /** How many laps the march ran before the year's rain settled. */
        val laps: Int,
        /**
         * Each half's net condensation on the last lap, millimeters a year of water, row-major,
         * not blurred: what the latent heat is released from. Negative where more cloud evaporates
         * than condenses.
         */
        val julyHalfCondensationMm: FloatArray,
        val januaryHalfCondensationMm: FloatArray,
        /**
         * The annual rain's change after each lap, as a share of its mean rain, the larger of the
         * land's and the whole planet's ([CONVERGED_SHARE]).
         */
        val landRainChanges: List<Double>,
        /**
         * Each lap's own time in milliseconds: both seasons marched, the year's rain and potential
         * gathered and the ground's return set, without the coupling's solve between laps. A
         * report for the cost guards; nothing reads it.
         */
        val lapMillis: List<Double> = emptyList(),
        /** Where the march ended: the ground's return each half ran its last lap with. */
        val endState: StartState? = null
    )

    /**
     * A march's ground return, for a later march of the same world on the same grid to start from
     * rather than from dry ground: each half's return in millimeters a second at every cell,
     * row-major, and the land it was set over.
     *
     * The return is the march's slow part: the first lap leaves a year's rain for the second's
     * ground to give back, and each later lap carries it a lap further toward the year it belongs
     * to. The worlds a generation marches in turn (the erosion's two provisional climates, the ice's
     * and the finished one) differ in their ground and their coast by a little, so a return set
     * over the last one's land is near the next one's year where the two are land, and the march
     * still settles on its own year: only where it starts moves ([AtmosphereCoupling]'s warm start).
     */
    class StartState(
        val isLand: BooleanArray,
        val julyHalfReturnMmPerSecond: FloatArray,
        val januaryHalfReturnMmPerSecond: FloatArray,
        /** Each half's water in flight as the last lap ended ([WaterInFlight]). */
        val julyHalfWater: WaterInFlight,
        val januaryHalfWater: WaterInFlight
    )

    /**
     * A half's water in flight at the end of a lap, which the next lap starts from, each layer's:
     * the bank at every cell, kilograms a square meter of column water in the march's own units
     * (row-major), which holds what the westward sweep's faces moved between rows, the boundary
     * layer's with its cloud and falling water banked at a turn and the free troposphere's with what
     * the boundary layer sent it last, and the boundary layer's with what the free troposphere sent it;
     * and each row's parcel arriving at the map's first column in each sweep, vapor, cloud and falling
     * water.
     *
     * Started cold, the first lap marches with an empty bank and its rows exchange nothing they did
     * not exchange within the lap, so it condenses a quarter differently from the settled lap
     * whatever the heating it runs under (docs/DESIGN_LEDGER.md, A1-6a). The land-origin parts are
     * not carried: they start at none, as a cold march's do, and only the recycling's share reads
     * them; nor is the boundary layer's air, which starts at the layer's own mass.
     */
    class WaterInFlight(
        val bank: FloatArray,
        val eastwardVapor: DoubleArray,
        val eastwardCloud: DoubleArray,
        val eastwardFalling: DoubleArray,
        val westwardVapor: DoubleArray,
        val westwardCloud: DoubleArray,
        val westwardFalling: DoubleArray,
        val freeBank: FloatArray,
        val freeEastwardVapor: DoubleArray,
        val freeWestwardVapor: DoubleArray
    )

    /**
     * The atmosphere solved again between laps from the march's condensation (`AtmosphereCoupling`).
     * The march asks it after every lap whether its heating has settled ([settledAfter]) and, when
     * the laps go on, for the next lap's seasons ([nextSeasons]).
     */
    interface Coupling {
        /**
         * Whether the heating the last lap's atmosphere was solved under stands within
         * [CONVERGED_SHARE] of the latent heat of that lap's condensation, [julyHalfCondensationMm]
         * and [januaryHalfCondensationMm] (millimeters a year, row-major): the loop's unrelaxed
         * residual.
         */
        fun settledAfter(julyHalfCondensationMm: FloatArray, januaryHalfCondensationMm: FloatArray): Boolean

        /** The two halves' seasons under the atmosphere solved from the heating [settledAfter] last read. */
        fun nextSeasons(): Pair<Season, Season>

        /**
         * The zonal winds that choose each cell's sweeps, the half about July's and the half about
         * January's: the dry atmosphere's (see the class comment). None when the first lap's
         * seasons are already the dry atmosphere's.
         */
        fun sweepWinds(): Pair<SweepWinds, SweepWinds>? = null
    }

    /**
     * Marches both seasons until the year's rain settles and returns it, blurred, with the
     * potential evaporation it was coupled to. [ledger], when handed in, is filled with every
     * term; nothing the march computes depends on it.
     */
    internal fun run(
        inputs: Inputs,
        ledger: MoistureLedger? = null,
        coupling: Coupling? = null,
        /** The ground's return to start from, from an earlier march of this world's grid; dry ground when none. */
        start: StartState? = null,
        /**
         * The two seasons the first lap reads, April to September about July and October to March
         * about January: built inside the march and held by no frame of it or its caller once the
         * marches are set up, so a coupled run's first seasons are let go after its first lap.
         */
        firstSeasons: () -> Pair<Season, Season>
    ): Result {
        val config = inputs.config
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val grid = Grid(config)
        val climbElevationM = smoothedElevationM(config, grid, inputs.elevationM, CLIMB_TERRAIN_SIGMA_KM)
        val seasons = startMarches(inputs, grid, climbElevationM, keepCells = ledger != null, coupling?.sweepWinds(), firstSeasons)
        val july = seasons[0]
        val january = seasons[1]
        if (start != null) {
            require(start.isLand.size == cellCount) { "a start from a grid of ${start.isLand.size} cells" }
            july.startReturnFrom(start.isLand, start.julyHalfReturnMmPerSecond)
            january.startReturnFrom(start.isLand, start.januaryHalfReturnMmPerSecond)
            july.startWaterFrom(start.julyHalfWater)
            january.startWaterFrom(start.januaryHalfWater)
        }

        val annualRain = FloatField(cellsAcross, cellsDown)
        val previousRain = FloatArray(cellCount)
        val annualPotential = FloatArray(cellCount)
        ledger?.millimetersPerYearPerUnit = grid.millimetersPerYearPerUnit

        var lap = 0
        val landRainChanges = mutableListOf<Double>()
        val lapMillis = mutableListOf<Double>()
        while (true) {
            val lapStarted = TimeSource.Monotonic.markNow()
            val laps = Array(2) { season -> ledger?.let { MoistureLedger.Lap(season == 0, lap) } }
            for (season in 0 until 2) seasons[season].beginLap(laps[season])
            // The two seasons share nothing inside a lap but the ground's return, which was set
            // before it began, so they march side by side; and inside a season the free troposphere
            // follows the boundary layer by one stage, which is what gives it the boundary layer's
            // water of this lap where their sweeps allow ("The exchange inside the lap").
            val slots = seasons[0].schedule.slotCount
            for (slot in 0 until slots) {
                parallelChunks(0, 2 * LAYERS) { first, last ->
                    for (task in first until last) {
                        val season = seasons[task / LAYERS]
                        val layer = task % LAYERS
                        val stage = season.schedule.stageAt(layer == BOUNDARY, slot)
                        if (stage >= 0) season.marchStage(layer, stage)
                    }
                }
            }
            for (season in 0 until 2) seasons[season].endLap(laps[season])

            laps.forEach { entry -> if (entry != null) ledger?.laps?.add(entry) }

            // The year's rain, as the rivers will read it, and the potential evaporation the two
            // seasons' air sets.
            annualRain.data.copyInto(previousRain)
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (cell in startRow * cellsAcross until endRow * cellsAcross) {
                    annualRain.data[cell] = (july.rainMmRowMajor(cell) + january.rainMmRowMajor(cell)) * 0.5f
                }
            }
            SphereBlur.apply(config, annualRain, inputs.blurSigmaKm)
            july.updateLandPotential()
            january.updateLandPotential()
            for (cell in 0 until cellCount) {
                annualPotential[cell] = (july.potentialMmRowMajor(cell) + january.potentialMmRowMajor(cell)) * 0.5f
            }
            lap++
            // The land's year, which the ground's return couples, and the whole planet's, which the
            // two layers' exchange carries a lap behind and the sea's rain feeds the atmosphere with.
            val change = maxOf(
                changeShare(inputs.isLand, annualRain.data, previousRain, grid, landOnly = true),
                changeShare(inputs.isLand, annualRain.data, previousRain, grid, landOnly = false)
            )
            ledger?.settling?.add(change)
            landRainChanges.add(change)
            var lapElapsed = lapStarted.elapsedNow()
            val heatingSettled = coupling?.settledAfter(july.condensationRowMajor(), january.condensationRowMajor()) ?: true
            val settled = lap >= MIN_LAPS && change < CONVERGED_SHARE && heatingSettled
            if (settled || lap >= MAX_LAPS) {
                lapMillis.add(lapElapsed.inWholeMicroseconds / 1_000.0)
                break
            }
            val nextSeasons = coupling?.nextSeasons()
            val returnStarted = TimeSource.Monotonic.markNow()
            nextSeasons?.let { (nextJuly, nextJanuary) ->
                july.reseason(nextJuly)
                january.reseason(nextJanuary)
            }

            // The ground's return for the next lap, from this lap's year.
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (cell in startRow * cellsAcross until endRow * cellsAcross) {
                    if (!inputs.isLand[cell]) continue
                    setGroundReturn(cell, july, january, annualRain.data[cell], annualPotential[cell], config.climate.groundReturn)
                }
            }
            lapElapsed += returnStarted.elapsedNow()
            lapMillis.add(lapElapsed.inWholeMicroseconds / 1_000.0)
        }
        val lapsRun = lap
        ledger?.lapsRun = lapsRun

        july.finishPotentials()
        january.finishPotentials()
        val julyRain = july.rainField()
        val januaryRain = january.rainField()
        val landOrigin = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) {
            landOrigin.data[cell] =
                (july.landRainMmRowMajor(cell) + january.landRainMmRowMajor(cell)) * 0.5f
        }
        if (ledger != null) {
            ledger.julyHalf = july.cells
            ledger.januaryHalf = january.cells
        }
        SphereBlur.apply(config, julyRain, inputs.blurSigmaKm)
        SphereBlur.apply(config, januaryRain, inputs.blurSigmaKm)
        SphereBlur.apply(config, landOrigin, inputs.blurSigmaKm)

        // The potential the rivers read is the one the last lap's ground return was set from on
        // land, so the return and the runoff split one year of rain; the sea's cells take the rate
        // land would have there, because the shoreline moves under the erosion that reads it.
        val potential = FloatField(cellsAcross, cellsDown)
        val openWater = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) {
            potential.data[cell] = if (inputs.isLand[cell]) {
                july.returnPotentialMm(cell)
            } else {
                (july.potentialMmRowMajor(cell) + january.potentialMmRowMajor(cell)) * 0.5f
            }
            openWater.data[cell] = (july.openWaterMmRowMajor(cell) + january.openWaterMmRowMajor(cell)) * 0.5f
        }

        if (ledger != null) {
            val finalRain = FloatField(cellsAcross, cellsDown)
            val sources = FloatField(cellsAcross, cellsDown)
            val residual = FloatField(cellsAcross, cellsDown)
            val returnedRain = FloatField(cellsAcross, cellsDown)
            for (cell in 0 until cellCount) {
                finalRain.data[cell] = (julyRain.data[cell] + januaryRain.data[cell]) * 0.5f
                sources.data[cell] = (july.sourceMmRowMajor(cell) + january.sourceMmRowMajor(cell)) * 0.5f
                if (inputs.isLand[cell]) {
                    val rain = finalRain.data[cell]
                    val returned = (july.groundReturnMmRowMajor(cell) + january.groundReturnMmRowMajor(cell)) * 0.5f
                    val runoff = rain * LakeWaterBalance.runoffShareOfRain(rain, potential.data[cell])
                    residual.data[cell] = rain - returned - runoff
                    returnedRain.data[cell] = july.returnRainMm(cell)
                }
            }
            ledger.finalAnnualRainMm = finalRain
            ledger.finalAnnualSourcesMm = sources
            ledger.surfaceResidualMm = residual
            ledger.returnRainMm = returnedRain
        }
        val endState = StartState(
            inputs.isLand.copyOf(),
            FloatArray(cellCount) { july.groundReturnRateRowMajor(it) },
            FloatArray(cellCount) { january.groundReturnRateRowMajor(it) },
            july.waterInFlight(),
            january.waterInFlight()
        )
        return Result(
            julyRain, januaryRain, landOrigin, potential, openWater, lapsRun, july.condensationRowMajor(), january.condensationRowMajor(),
            landRainChanges, lapMillis, endState
        )
    }

    /** The march's two layers, as [SeasonMarch.marchStage] numbers them. */
    private const val LAYERS = 2

    /**
     * The most pieces a column's rows are marched in. Each piece is handed to the pool and waited
     * for once a column, a hand-off of a few tens of microseconds against the 0.1 to 0.3 ms a
     * layer's column takes at the application's 1,024 rows; past a dozen the hand-offs outweigh
     * what the pieces save on the sixteen-thread machine this was measured on
     * (docs/DESIGN_LEDGER.md, W2f).
     */
    private const val MAX_ROW_CHUNKS = 12

    /** [Inputs.rowPieces] by default. */
    private fun defaultRowPieces(): Int = ((parallelism() + 1) / 2).coerceIn(1, MAX_ROW_CHUNKS)

    /**
     * Columns of one sweep a stage marches ([MarchSchedule]). Every stage ends with the four marches
     * handing back to the pool, a few tens of microseconds, so a stage is many columns long, each
     * column a tenth of a millisecond or more at the application's grid; and the free troposphere
     * reads the boundary layer a stage behind, so a stage is a small part of the sweep. Sixteen to
     * 128 columns marched a lap in the same time (docs/DESIGN_LEDGER.md, W2f).
     */
    private const val COLUMNS_PER_STAGE = 32
    private const val BOUNDARY = 0
    private const val FREE = 1

    /**
     * How far the annual rain moved between two laps: the mean of each cell's change over the mean
     * rain, both by area on the sphere, over the land when [landOnly] and over every cell otherwise.
     */
    private fun changeShare(isLand: BooleanArray, rain: FloatArray, previous: FloatArray, grid: Grid, landOnly: Boolean): Double {
        var area = 0.0
        var total = 0.0
        var moved = 0.0
        for (cell in rain.indices) {
            if (landOnly && !isLand[cell]) continue
            val weight = grid.cosRow[cell / grid.cellsAcross]
            area += weight
            total += weight * rain[cell]
            moved += weight * abs(rain[cell] - previous[cell])
        }
        if (area == 0.0 || total <= 0.0) return 0.0
        return moved / total
    }

    /**
     * The column's rain at relative humidity [relativeHumidity], millimeters a day: Bretherton,
     * Peters and Back's (2004) `exp(11.4 (r - 0.522))`, less its value at a dry column, 0.0026 mm a
     * day, so a column with no water rains none however long the wind leaves it over a cell.
     */
    fun columnRainMmPerDay(relativeHumidity: Double): Double =
        exp(COLUMN_RAIN_PER_HUMIDITY * (relativeHumidity - COLUMN_RAIN_HUMIDITY_OFFSET)) -
            exp(-COLUMN_RAIN_PER_HUMIDITY * COLUMN_RAIN_HUMIDITY_OFFSET)

    /**
     * The water the column's rain law counts beside the free troposphere's at a cell whose air,
     * carried down to the sea at the environment's lapse rate, stands at [seaLevelTemperatureC],
     * millimeters: the boundary layer's mass at [MARINE_RELATIVE_HUMIDITY] of its surface air's
     * saturation, the boundary layer of the columns the relation was read on, whose humidity their
     * rain does not move (Bretherton, Peters and Back 2004, section 3e).
     */
    fun lawOffsetMm(seaLevelTemperatureC: Double): Double =
        MARINE_RELATIVE_HUMIDITY * ColumnWater.saturationSpecificHumidity(seaLevelTemperatureC, ColumnWater.SEA_LEVEL_PRESSURE_KPA) *
            ColumnWater.BOUNDARY_LAYER_MASS_KG_PER_M2

    /**
     * The humidity the column's rain law reads at a cell holding [freeWaterMm] in its free
     * troposphere, its air carried down to the sea standing at [seaLevelTemperatureC]: that water and
     * [lawOffsetMm] over the sea-level column's saturated water ([ColumnWater.saturatedColumnMm]).
     * Dimensionless; what [columnRainMmPerDay] is read at.
     */
    fun lawHumidity(freeWaterMm: Double, seaLevelTemperatureC: Double): Double {
        val saturated = ColumnWater.saturatedColumnMm(seaLevelTemperatureC)
        return if (saturated > 0.0) (freeWaterMm + lawOffsetMm(seaLevelTemperatureC)) / saturated else 0.0
    }

    /**
     * The share of a row's water the mid-latitude eddies trade through the face between two rows
     * at [faceLatitudeDegrees] in one column of the march, at [TRANSPORT_SPEED_MPS], each way, on
     * [config]'s grid.
     *
     * The mean meridional wind is not the whole of what carries water across the latitude lines:
     * poleward of the subtropics most of it is carried by the depressions of the storm track,
     * which stir a wet air mass and a dry one together without a mean wind between them. Written
     * as a diffusivity `K`, the same eddies' diffusivity for heat in the energy balance
     * ([EnergyBalance.eddyDiffusivityAt], in watts per square meter per kelvin per radian²) turned
     * into square meters a second by the air column's heat capacity and the planet's radius,
     * `K = D a² g / (p_s c_p)`: the energy balance carries the latent heat of that water already,
     * and the same eddies carry the water, in both layers alike. About 1.2e6 m²/s under the polar
     * floor and 2.2e6 at the storm track's 50 degrees on Earth.
     *
     * In the march's units a diffusive flux `K dW/dy` through a face `cellWidth cos(face)` long,
     * over a row `cellHeight` tall, against the reference throughflow `U cellHeight`, is
     * `K cellWidth cos(face) / (U cellHeight²)` of each side's column water per column; a row
     * slower than `U` holds more column water per unit of its parcel and trades in proportion.
     */
    fun eddyMixingShare(config: WorldGenConfig, faceLatitudeDegrees: Double): Double {
        val radius = config.scale.radiusMeters
        val diffusivity = EnergyBalance.eddyDiffusivityAt(faceLatitudeDegrees) * radius * radius *
            STANDARD_GRAVITY_MPS2 / (SEA_LEVEL_PRESSURE_PA * AIR_HEAT_CAPACITY_J_PER_KG_K)
        val cellWidthM = config.cellWidthKm * METERS_PER_KM
        val cellHeightM = config.cellHeightKm * METERS_PER_KM
        return diffusivity * cellWidthM * cos(faceLatitudeDegrees * PI / 180.0) /
            (TRANSPORT_SPEED_MPS * cellHeightM * cellHeightM)
    }

    /** Standard gravity, the standard atmosphere's surface pressure and dry air's heat capacity and gas constant. */
    private const val STANDARD_GRAVITY_MPS2 = 9.80665
    private const val SEA_LEVEL_PRESSURE_PA = 101_325.0
    private const val AIR_HEAT_CAPACITY_J_PER_KG_K = 1004.0
    private const val DRY_AIR_GAS_CONSTANT_J_PER_KG_K = 287.04

    /**
     * Seconds the march's unit of water takes to cross one cell of [row] on [config]'s grid at
     * [TRANSPORT_SPEED_MPS]: the cell's ground width over the speed. A figure per kilometer of
     * ground and not per cell, which `GroundFiguresTest` holds on every planet.
     */
    fun referenceSecondsPerColumn(config: WorldGenConfig, row: Int): Double = Grid(config).secondsPerColumn[row]

    /**
     * What the ground under a cell gives back to the air in a year, in millimeters: Budyko's
     * (1974) evaporated share of [annualRainMm] against [potentialMm], the curve
     * [LakeWaterBalance.runoffShareOfRain] is the other side of. One function, so the march's
     * return and the rivers' runoff add to the rain exactly.
     */
    fun groundReturnMm(annualRainMm: Float, potentialMm: Float): Float =
        annualRainMm * (1f - LakeWaterBalance.runoffShareOfRain(annualRainMm, potentialMm))

    /**
     * Water held between field capacity and the wilting point, millimeters per meter of soil: the
     * middle of the three soils FAO-56 works from its Table 19 (Example 36), loamy sand's 90,
     * silty clay's 120 and silt's 170.
     */
    const val AVAILABLE_WATER_MM_PER_M = 120.0

    /**
     * How deep the roots draw, meters: FAO-56's Table 22 gives grazing pasture 0.5 to 1.5 m and
     * conifer trees 1.0 to 1.5, and its footnote takes the larger values for modeling water stress
     * under rain alone, which is the ground here; so 1.5.
     */
    const val ROOTING_DEPTH_M = 1.5

    /**
     * The water the root zone holds for plants to draw, millimeters: FAO-56's total available
     * water `1000 (theta_FC - theta_WP) Z_r` (Allen and others 1998, equation 82), at
     * [AVAILABLE_WATER_MM_PER_M] of soil and [ROOTING_DEPTH_M] of roots, 180 mm. What one half of
     * the year can carry into the other: a dry half's ground gives back no more than its own rain
     * and this ([halfYearReturnsMm]).
     */
    const val ROOT_ZONE_STORE_MM = AVAILABLE_WATER_MM_PER_M * ROOTING_DEPTH_M

    /**
     * Each half-year's ground return, from the year's: millimeters a year at that half's own pace,
     * the July half's first. The year gives back [annualReturnMm] (Budyko's, [groundReturnMm]),
     * and each half takes its share of the year's potential evapotranspiration, the halves'
     * [julyHalfPotentialMm] and [januaryHalfPotentialMm] at their own paces, as far as the water
     * lets it: a half gives back at most its own rain, [julyHalfRainMm] or [januaryHalfRainMm] at
     * its own pace, and what the root zone carried in from the other half, [ROOT_ZONE_STORE_MM].
     * What a half cannot supply the other half gives back instead, from the rain that half did not
     * return, so the year's return stays Budyko's and the rivers' runoff the rest of the rain.
     *
     * Shared by the potential alone, a monsoon's dry half gave back half the year's return from
     * ground its few millimeters of rain had left dry, into air that does not rain, while its wet
     * half gave back too little to the air that does (docs/DESIGN_LEDGER.md, W2d).
     */
    fun halfYearReturnsMm(
        annualReturnMm: Double,
        julyHalfPotentialMm: Double,
        januaryHalfPotentialMm: Double,
        julyHalfRainMm: Double,
        januaryHalfRainMm: Double
    ): Pair<Double, Double> {
        val potential = julyHalfPotentialMm + januaryHalfPotentialMm
        val julyShare = if (potential > 0.0) julyHalfPotentialMm / potential else 0.5
        var july = 2.0 * annualReturnMm * julyShare
        var january = 2.0 * annualReturnMm - july
        // A half's pace is twice what it gives in its six months, so the store it draws counts twice.
        val julyMost = julyHalfRainMm + 2.0 * ROOT_ZONE_STORE_MM
        val januaryMost = januaryHalfRainMm + 2.0 * ROOT_ZONE_STORE_MM
        if (july > julyMost) {
            january += july - julyMost
            july = julyMost
        } else if (january > januaryMost) {
            july += january - januaryMost
            january = januaryMost
        }
        return july to january
    }

    /**
     * Sets both halves' ground return at [cell] (row-major) from the year's rain and potential
     * ([halfYearReturnsMm]), each half's rain the lap's own, scaled to the year's blurred rain so
     * the halves add to it; with the return switched off, none, and the year still kept.
     */
    private fun setGroundReturn(
        cell: Int,
        july: SeasonMarch,
        january: SeasonMarch,
        annualRainMm: Float,
        annualPotentialMm: Float,
        returning: Boolean
    ) {
        val annualReturnMm = if (returning) groundReturnMm(annualRainMm, annualPotentialMm).toDouble() else 0.0
        val julyRain = july.rainMmRowMajor(cell).toDouble().coerceAtLeast(0.0)
        val januaryRain = january.rainMmRowMajor(cell).toDouble().coerceAtLeast(0.0)
        val lapRain = julyRain + januaryRain
        val julyShare = if (lapRain > 0.0) julyRain / lapRain else 0.5
        val (julyReturn, januaryReturn) = halfYearReturnsMm(
            annualReturnMm,
            july.potentialMmRowMajor(cell).toDouble(),
            january.potentialMmRowMajor(cell).toDouble(),
            2.0 * annualRainMm * julyShare,
            2.0 * annualRainMm * (1.0 - julyShare)
        )
        july.setGroundReturn(cell, julyReturn, annualRainMm, annualPotentialMm)
        january.setGroundReturn(cell, januaryReturn, annualRainMm, annualPotentialMm)
    }

    /**
     * [elevationM] (row-major, meters) spread by a Gaussian of [sigmaKm] on the ground, row-major:
     * a pass along each row at that row's own cell width and one across the rows, each weighting a
     * neighbor by the Gaussian of its distance on the ground and dividing by the weights, so a
     * spread under a cell's width leaves the ground nearly as it is on every grid and the kernel is
     * round on the ground at every width (conventions rule 13).
     */
    private fun smoothedElevationM(config: WorldGenConfig, grid: Grid, elevationM: FloatArray, sigmaKm: Double): FloatArray {
        val cellsAcross = config.width
        val cellsDown = config.height
        val alongRows = FloatArray(elevationM.size)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val widthKm = config.cellWidthKm * grid.cosRow[row]
                val reach = minOf((GAUSSIAN_REACH_SIGMAS * sigmaKm / widthKm).toInt(), cellsAcross / 2)
                val weights = DoubleArray(reach + 1) { offset -> gaussian(offset * widthKm, sigmaKm) }
                for (column in 0 until cellsAcross) {
                    var sum = 0.0
                    var weight = 0.0
                    for (offset in -reach..reach) {
                        val neighbor = row * cellsAcross + (column + offset + cellsAcross) % cellsAcross
                        val w = weights[abs(offset)]
                        sum += w * elevationM[neighbor]
                        weight += w
                    }
                    alongRows[row * cellsAcross + column] = (sum / weight).toFloat()
                }
            }
        }
        val heightKm = config.cellHeightKm
        val reach = (GAUSSIAN_REACH_SIGMAS * sigmaKm / heightKm).toInt()
        val weights = DoubleArray(reach + 1) { offset -> gaussian(offset * heightKm, sigmaKm) }
        val smoothed = FloatArray(elevationM.size)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    var sum = 0.0
                    var weight = 0.0
                    for (offset in -reach..reach) {
                        val other = row + offset
                        if (other < 0 || other >= cellsDown) continue
                        val w = weights[abs(offset)]
                        sum += w * alongRows[other * cellsAcross + column]
                        weight += w
                    }
                    smoothed[row * cellsAcross + column] = (sum / weight).toFloat()
                }
            }
        }
        return smoothed
    }

    /** The unnormalized Gaussian of [distanceKm] at [sigmaKm]. */
    private fun gaussian(distanceKm: Double, sigmaKm: Double): Double = exp(-0.5 * distanceKm * distanceKm / (sigmaKm * sigmaKm))

    /** How many standard deviations the terrain's Gaussian reaches: three, past which it weighs under a hundredth. */
    private const val GAUSSIAN_REACH_SIGMAS = 3.0

    /** The grid's geometry as the march spends it. */
    private class Grid(config: WorldGenConfig) {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cosRow = DoubleArray(cellsDown) { cos(ClimateStage.latitudeOf(it, cellsDown) * PI / 180.0) }

        /** Seconds the reference speed takes to cross one column of each row. */
        val secondsPerColumn = DoubleArray(cellsDown) {
            config.cellWidthKm * METERS_PER_KM * cosRow[it] / TRANSPORT_SPEED_MPS
        }

        /**
         * The share of the donor's column water a meridional wind of one meter a second passes
         * through the face below each row in one column at the reference speed:
         * `cos(face) (cellWidth / cellHeight) / U`.
         */
        val faceSharePerMps = DoubleArray(cellsDown) { row ->
            val faceLatitude = 90.0 - 180.0 * (row + 1) / cellsDown
            cos(faceLatitude * PI / 180.0) * (config.cellWidthKm / config.cellHeightKm) / TRANSPORT_SPEED_MPS
        }

        /** [eddyMixingShare] at the face below each row. */
        val faceMixing = DoubleArray(cellsDown) { row ->
            eddyMixingShare(config, 90.0 - 180.0 * (row + 1) / cellsDown)
        }

        /** See [MoistureLedger.millimetersPerYearPerUnit]. */
        val millimetersPerYearPerUnit =
            TRANSPORT_SPEED_MPS * SECONDS_PER_YEAR /
                (cellsAcross * config.cellWidthKm * METERS_PER_KM * cosRow.sum())
    }

    private const val METERS_PER_KM = 1_000.0

    /** Surfaces, as the march stores them per cell. */
    private const val LAND: Byte = 0
    private const val OPEN_SEA: Byte = 1
    private const val SEA_ICE: Byte = 2

    /** How a row takes part in one sweep's column: marched, solved for its faces only, or not at all. */
    private const val OUTSIDE: Byte = 0
    private const val MARCHED: Byte = 1
    private const val ACROSS: Byte = 2

    /**
     * Both seasons' marches under [firstSeasons], July's then January's, each cell held in the sweeps
     * [sweepWinds] blow it along, or the first seasons' own winds when none.
     */
    private fun startMarches(
        inputs: Inputs,
        grid: Grid,
        climbElevationM: FloatArray,
        keepCells: Boolean,
        sweepWinds: Pair<SweepWinds, SweepWinds>?,
        firstSeasons: () -> Pair<Season, Season>
    ): Array<SeasonMarch> {
        val (julyHalf, januaryHalf) = firstSeasons()
        return arrayOf(
            SeasonMarch(inputs, julyHalf, grid, climbElevationM, keepCells, sweepWinds?.first ?: julyHalf.sweepWinds()),
            SeasonMarch(inputs, januaryHalf, grid, climbElevationM, keepCells, sweepWinds?.second ?: januaryHalf.sweepWinds())
        )
    }

    /**
     * One season's march: its precomputed fields, column-major so a column's rows are adjacent
     * in memory, its two layers and its state between laps.
     */
    private class SeasonMarch(
        val inputs: Inputs,
        season: Season,
        val grid: Grid,
        /** The ground the climb is read on, meters, row-major ([CLIMB_TERRAIN_SIGMA_KM]). */
        val climbElevationM: FloatArray,
        keepCells: Boolean,
        sweepWinds: SweepWinds
    ) {
        val cellsAcross = grid.cellsAcross
        val cellsDown = grid.cellsDown
        val cellCount = cellsAcross * cellsDown
        val config = inputs.config

        /** The pieces a column's rows are marched in ([Inputs.rowPieces]). */
        val rowChunks = inputs.rowPieces.coerceAtLeast(1)

        // What the ledger and the last potentials read of the season's air. The rest of a season,
        // its wind, is let go once each lap's fields are built from it, so a coupled lap's next
        // seasons are not built beside the last ones.
        private var airTemperatureC: FloatArray = season.airTemperatureC
        private var seaSurfaceC: FloatArray = season.seaSurfaceC
        private var windAt2mMps: FloatArray? = season.windAt2mMps
        private var extraterrestrialOfRow: DoubleArray = season.extraterrestrialOfRow

        val surface = ByteArray(cellCount)

        /** The whole saturated column over the ground, millimeters: the ledger's humidity reads it. */
        val saturatedMm = FloatArray(cellCount)

        /** The ground's pressure, kilopascals, and the boundary layer's depth over it, meters. */
        val groundPressureKpa = FloatArray(cellCount)
        val layerDepthM = FloatArray(cellCount)

        /** The boundary layer's water at its surface air's saturation: its cap, millimeters. */
        val boundaryCapMm = FloatArray(cellCount)

        /**
         * The bulk formula's `rho C_E U`, kilograms per square meter per second, at open-sea cells,
         * and the specific humidity at the sea's surface it evaporates from.
         */
        val seaTransferMassFlux = FloatArray(cellCount)
        val seaSurfaceHumidity = FloatArray(cellCount)

        /**
         * The boundary layer's wind's climb over the ground ([climbElevationM]), meters a second,
         * where it rises, and what its descent can evaporate where it comes down, kilograms per
         * square meter per second: the saturated layer's yield at the same rate ([ColumnWater.boundaryLayerLiftYield]).
         */
        val liftMps = FloatArray(cellCount)
        val descentRate = FloatArray(cellCount)

        /** The marine inversion's lid, 0..1: what slows every sink on the land under it. */
        val suppression = FloatArray(cellCount)

        /** The ground's return this season, millimeters a second, land cells only. */
        val groundReturnRate = FloatArray(cellCount)

        /** The year's rain and potential the ground's return was last set from, row-major. */
        val returnRain = FloatArray(cellCount)
        val returnPotential = FloatArray(cellCount)

        // This lap's results, column-major: the boundary layer's and the free troposphere's apart,
        // since the two are marched at once.
        val rainMm = FloatArray(cellCount)
        val landRainMm = FloatArray(cellCount)
        val condensationMm = FloatArray(cellCount)
        val freeRainMm = FloatArray(cellCount)
        val freeLandRainMm = FloatArray(cellCount)

        /** The boundary layer's surface air's relative humidity as it leaves each cell: what the potential reads. */
        val humidity = FloatArray(cellCount)
        val sourceMm = FloatArray(cellCount)

        /**
         * What each layer sent the other at each cell, in the march's units, with its land-origin
         * part, waiting for the other layer to take it in at its crossing of the cell, as a steady
         * rate over that crossing. The layer that crosses the cell second in the lap takes in what
         * the first sent in the same lap, and the first takes in what the second sent in the lap
         * before; the one that takes it in empties the cell, and the other writes it again later in
         * the lap, so one array does for both laps ([marchStage] orders the crossings).
         */
        val toFree = DoubleArray(cellCount)
        val toFreeLand = DoubleArray(cellCount)
        val toBoundary = DoubleArray(cellCount)
        val toBoundaryLand = DoubleArray(cellCount)

        /**
         * The share of the boundary layer's air the march's faces took out of each cell, per second
         * of its crossing, which the free troposphere's lowest air makes up: written by the boundary
         * layer's crossing of the cell and read by the free troposphere's, this lap's or the last
         * lap's by the same order as the inboxes above.
         */
        val replacedPerSecond = FloatArray(cellCount)

        // Potential evaporation from the last lap's air, row-major, and its terms in the air's
        // humidity at each land cell, built once.
        val potentialMm = FloatArray(cellCount)
        val openWaterMm = FloatArray(cellCount)
        val potentialConstant = FloatArray(cellCount)
        val potentialPerRootHumidity = FloatArray(cellCount)
        val potentialPerHumidity = FloatArray(cellCount)

        val cells: MoistureLedger.Cells? =
            if (keepCells) MoistureLedger.Cells(cellsAcross, cellsDown) else null

        val boundary = Layer(true, sweepWinds.boundaryEastwardMps)
        val free = Layer(false, sweepWinds.freeEastwardMps)

        /** Each layer's part of this lap's ledger, merged into the season's lap at its end. */
        private var boundaryEntry: MoistureLedger.Lap? = null
        private var freeEntry: MoistureLedger.Lap? = null

        init {
            precompute(season)
            precomputePotentialTerms()
            for (row in 0 until cellsDown) {
                val east = index(row, 0)
                val west = index(row, cellsAcross - 1)
                val boundaryStart = { here: Int -> INITIAL_HUMIDITY * boundaryCapMm[here] / boundary.stretch[here] }
                boundary.eastParcel.vapor[row] = if (boundary.direction[east] > 0) boundaryStart(east) else 0.0
                boundary.westParcel.vapor[row] = if (boundary.direction[west] < 0) boundaryStart(west) else 0.0
                val freeStart = { here: Int -> INITIAL_HUMIDITY * ColumnWater.referenceFreeSaturatedMm(seaLevelTemperatureC(here)) / free.stretch[here] }
                free.eastParcel.vapor[row] = if (free.direction[east] > 0) freeStart(east) else 0.0
                free.westParcel.vapor[row] = if (free.direction[west] < 0) freeStart(west) else 0.0
            }
            boundary.resetAir()
        }

        /** The air at [here] (column-major) carried down to the sea at the environment's lapse rate, degrees Celsius. */
        private fun seaLevelTemperatureC(here: Int): Double {
            val cell = (here % cellsDown) * cellsAcross + here / cellsDown
            return airTemperatureC[cell] + config.climate.lapseRateCPerKm * inputs.elevationM[cell] / METERS_PER_KM
        }

        /**
         * Marches the next lap under [next], the same half-year's air under a newly solved
         * atmosphere: every field the wind sets is built again, and the water in flight, the banks
         * and the ground's return are kept.
         */
        fun reseason(next: Season) {
            airTemperatureC = next.airTemperatureC
            seaSurfaceC = next.seaSurfaceC
            windAt2mMps = next.windAt2mMps
            extraterrestrialOfRow = next.extraterrestrialOfRow
            precompute(next)
            precomputePotentialTerms()
            // A row's parcel in flight into the lap's first column, where the new wind no longer
            // marches it in that sweep, waits in the bank there for the sweep that does, all of its
            // water: a parcel arriving at a cell of the other sweep would be cleared.
            for (layer in arrayOf(boundary, free)) {
                for (row in 0 until cellsDown) {
                    layer.bankParcelIfTurned(layer.eastParcel, row, index(row, 0), 1)
                    layer.bankParcelIfTurned(layer.westParcel, row, index(row, cellsAcross - 1), -1)
                }
            }
        }

        /** The land's FAO-56 terms from this season's air and wind, built once per season. */
        private fun precomputePotentialTerms() {
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (row in startRow until endRow) {
                    val sun = extraterrestrialOfRow[row]
                    for (column in 0 until cellsAcross) {
                        val cell = row * cellsAcross + column
                        if (!inputs.isLand[cell]) continue
                        val terms = SurfaceEvaporation.referenceTerms(
                            airTemperatureC[cell].toDouble(), sun, inputs.elevationM[cell].toDouble(),
                            windAt2m(cell)
                        )
                        potentialConstant[cell] = terms.constant.toFloat()
                        potentialPerRootHumidity[cell] = terms.perRootHumidity.toFloat()
                        potentialPerHumidity[cell] = terms.perHumidity.toFloat()
                    }
                }
            }
        }

        fun index(row: Int, column: Int) = column * cellsDown + row

        /** The wind at 2 m over [cell] (row-major), meters a second. */
        private fun windAt2m(cell: Int): Double =
            windAt2mMps?.let { it[cell].toDouble() } ?: SurfaceEvaporation.LAND_WIND_AT_2_M_MPS

        private fun precompute(season: Season) {
            val climate = config.climate
            val referenceMassFlux = transferMassFlux(TRANSPORT_SPEED_MPS)
            val lapseRateCPerKm = climate.lapseRateCPerKm.toDouble()
            boundary.precomputeTransport(season.eastwardMps, season.southwardMps)
            free.precomputeTransport(season.freeEastward, season.freeSouthward)
            parallelChunks(0, cellsAcross) { startColumn, endColumn ->
                val cellYield = DoubleArray(2)
                for (column in startColumn until endColumn) {
                    for (row in 0 until cellsDown) {
                        val cell = row * cellsAcross + column
                        val here = index(row, column)
                        surface[here] = when {
                            inputs.isLand[cell] -> LAND
                            season.seaIce[cell] -> SEA_ICE
                            else -> OPEN_SEA
                        }
                        val airC = season.airTemperatureC[cell].toDouble()
                        val groundPressure = ColumnWater.surfacePressureKpa(inputs.elevationM[cell].toDouble(), airC, lapseRateCPerKm)
                        groundPressureKpa[here] = groundPressure.toFloat()
                        layerDepthM[here] = ColumnWater.boundaryLayerDepthM(airC, groundPressure).toFloat()
                        saturatedMm[here] = ColumnWater.saturatedColumnMm(airC, groundPressure).toFloat()
                        val surfaceSaturation = ColumnWater.saturationSpecificHumidity(airC, groundPressure)
                        boundaryCapMm[here] = (surfaceSaturation * ColumnWater.BOUNDARY_LAYER_MASS_KG_PER_M2).toFloat()
                        if (surface[here] == OPEN_SEA) {
                            seaSurfaceHumidity[here] = SurfaceEvaporation.seaSurfaceHumidity(season.seaSurfaceC[cell].toDouble()).toFloat()
                            seaTransferMassFlux[here] = (season.scalarWindAt10mMps?.let { transferMassFlux(it[cell].toDouble()) } ?: referenceMassFlux).toFloat()
                        } else {
                            seaSurfaceHumidity[here] = 0f
                            seaTransferMassFlux[here] = 0f
                        }
                        suppression[here] = (if (inputs.isLand[cell]) lidFactor(cell, season.inversionSuppression) else 1.0).toFloat()
                        // The climb up the ground the terrain is read on, along the boundary layer's
                        // wind, by centered differences on the ground's own lengths.
                        var climb = 0.0
                        if (inputs.isLand[cell]) {
                            val cellWidthM = config.cellWidthKm * METERS_PER_KM * grid.cosRow[row]
                            val cellHeightM = config.cellHeightKm * METERS_PER_KM
                            val north = if (row > 0) row - 1 else row
                            val south = if (row < cellsDown - 1) row + 1 else row
                            val east = row * cellsAcross + (column + 1) % cellsAcross
                            val west = row * cellsAcross + (column + cellsAcross - 1) % cellsAcross
                            val slopeEast = (climbElevationM[east] - climbElevationM[west]) / (2.0 * cellWidthM)
                            val slopeSouth = (climbElevationM[south * cellsAcross + column] - climbElevationM[north * cellsAcross + column]) /
                                ((south - north) * cellHeightM)
                            climb = season.eastwardMps[cell] * slopeEast + season.southwardMps[cell] * slopeSouth
                        }
                        liftMps[here] = climb.coerceAtLeast(0.0).toFloat()
                        if (climb < 0.0) {
                            ColumnWater.boundaryLayerLiftYield(airC, groundPressure, layerDepthM[here].toDouble(), surfaceSaturation, cellYield)
                            descentRate[here] = (-climb * cellYield[0]).toFloat()
                        } else {
                            descentRate[here] = 0f
                        }
                    }
                }
            }
            if (config.climate.convergenceRain) free.precomputeConvergence()
        }

        /** The bulk formula's `rho C_E(U) U` at a wind of [windMps], kilograms per square meter per second. */
        private fun transferMassFlux(windMps: Double): Double =
            PressureWind.AIR_DENSITY_KG_PER_M3 * SurfaceEvaporation.evaporationTransferCoefficient(windMps) * windMps

        /** The marine inversion's hold on a land cell's rain, as [MoistureBudget] defines it. */
        private fun lidFactor(cell: Int, inversionSuppression: FloatArray?): Double {
            val suppressionOfCell = inversionSuppression ?: return 1.0
            val lid = inputs.lidElevation
            val aboveLid =
                if (lid <= 0f) 1.0 else (inputs.relativeElevation[cell] / lid).toDouble().coerceIn(0.0, 1.0)
            return 1.0 - suppressionOfCell[cell] * (1.0 - aboveLid)
        }

        /**
         * Sets this season's ground return at [cell] (row-major) to [returnMm], millimeters a year
         * at this half's pace, and keeps the year it was set from for the rivers.
         */
        fun setGroundReturn(cell: Int, returnMm: Double, annualRainMm: Float, annualPotentialMm: Float) {
            groundReturnRate[index(cell / cellsAcross, cell % cellsAcross)] = (returnMm / SECONDS_PER_YEAR).toFloat()
            returnRain[cell] = annualRainMm
            returnPotential[cell] = annualPotentialMm
        }

        /**
         * Starts this season's ground return from [rates] (millimeters a second, row-major) where
         * the ground is land now and was under [previousLand]; elsewhere the first lap's ground is
         * dry, as with no start.
         */
        fun startReturnFrom(previousLand: BooleanArray, rates: FloatArray) {
            for (cell in 0 until cellCount) {
                if (inputs.isLand[cell] && previousLand[cell]) {
                    groundReturnRate[index(cell / cellsAcross, cell % cellsAcross)] = rates[cell]
                }
            }
        }

        /**
         * Starts this season's water in flight from [start], a march's end on the same grid: each
         * layer's bank at every cell and each row's arriving parcels, their land-origin parts at
         * none, and the free troposphere's water at each cell.
         */
        fun startWaterFrom(start: WaterInFlight) {
            boundary.startFrom(start.bank, start.eastwardVapor, start.eastwardCloud, start.eastwardFalling,
                start.westwardVapor, start.westwardCloud, start.westwardFalling)
            free.startFrom(start.freeBank, start.freeEastwardVapor, null, null, start.freeWestwardVapor, null, null)
            boundary.resetAir()
        }

        /** This season's water in flight now, for a later march to start from ([WaterInFlight]). */
        fun waterInFlight(): WaterInFlight = WaterInFlight(
            boundary.bankRowMajor(toBoundary),
            boundary.eastParcel.vapor.copyOf(), boundary.eastParcel.cloud.copyOf(), boundary.eastParcel.falling.copyOf(),
            boundary.westParcel.vapor.copyOf(), boundary.westParcel.cloud.copyOf(), boundary.westParcel.falling.copyOf(),
            free.bankRowMajor(toFree),
            free.eastParcel.vapor.copyOf(), free.westParcel.vapor.copyOf()
        )

        /** The ground's return at [cell] (row-major), millimeters a second. */
        fun groundReturnRateRowMajor(cell: Int): Float = groundReturnRate[index(cell / cellsAcross, cell % cellsAcross)]

        /** Opens a lap: each layer's part of the ledger, and the storage read. */
        fun beginLap(entry: MoistureLedger.Lap?) {
            boundaryEntry = entry?.let { MoistureLedger.Lap(it.julyHalf, it.lap) }
            freeEntry = entry?.let { MoistureLedger.Lap(it.julyHalf, it.lap) }
            if (entry != null) {
                entry.storageAtStart = storage()
                entry.landStorageAtStart = landStorage()
            }
        }

        /** The order a lap is marched in ([MarchSchedule]): [stageCount] stages in [slotCount] slots. */
        val schedule = MarchSchedule(cellsAcross, COLUMNS_PER_STAGE)

        /**
         * Marches [stage] of one layer, [BOUNDARY] or [FREE]: its columns in the order its sweep
         * marches them.
         */
        fun marchStage(layer: Int, stage: Int) {
            val marched = if (layer == BOUNDARY) boundary else free
            marched.entry = if (layer == BOUNDARY) boundaryEntry else freeEntry
            val sweep = if (schedule.isEastward(stage)) 1 else -1
            var column = schedule.firstColumn(stage)
            val last = schedule.lastColumn(stage)
            while (true) {
                marched.sweepColumn(column, sweep)
                if (column == last) break
                column += sweep
            }
        }

        /**
         * Closes a lap: the free troposphere's water as this lap left it becomes what the next lap's
         * boundary layer draws on, the layers' ledgers are merged and the storage read again.
         */
        fun endLap(entry: MoistureLedger.Lap?) {
            if (entry != null) {
                boundaryEntry?.let(entry::add)
                freeEntry?.let(entry::add)
                entry.storageAtEnd = storage()
                entry.landStorageAtEnd = landStorage()
                entry.bankAtEnd = boundary.bank.sum() + free.bank.sum() + toFree.sum() + toBoundary.sum()
            }
            cells?.let { record ->
                for (cell in 0 until cellCount) {
                    record.columnWater.data[cell] = record.boundaryLayerWater.data[cell] + record.freeWater.data[cell]
                    record.rain.data[cell] = rainMmRowMajor(cell)
                }
            }
        }

        private fun storage(): Double = boundary.storage() + free.storage() + toFree.sum() + toBoundary.sum()
        private fun landStorage(): Double = boundary.landStorage() + free.landStorage() + toFreeLand.sum() + toBoundaryLand.sum()

        /** The velocity the boundary layer's top exchanges air at ([exchangeVelocityMps]). */
        private val exchangeVelocity = exchangeVelocityMps(config.climate.lapseRateCPerKm.toDouble())

        /**
         * The rate the boundary layer's air at [here] (column-major, [cell] row-major) is stirred
         * with the free troposphere's, per second of its mass: [exchangeVelocity] in the density of
         * the layer's top, the well-mixed layer's air carried up the dry adiabat.
         */
        fun exchangePerSecondAt(here: Int, cell: Int): Double {
            val groundPressure = groundPressureKpa[here].toDouble()
            val topKelvin = airTemperatureC[cell] + ColumnWater.KELVIN_AT_ZERO_C - DRY_LAPSE_K_PER_M * layerDepthM[here]
            val topDensity = (groundPressure - ColumnWater.BOUNDARY_LAYER_DEPTH_KPA).coerceAtLeast(0.0) * PASCALS_PER_KPA /
                (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * topKelvin)
            return topDensity * exchangeVelocity / ColumnWater.BOUNDARY_LAYER_MASS_KG_PER_M2
        }

        /**
         * The land's potential evapotranspiration this season's air sets, from the lap just run:
         * what the ground's return for the next lap is read against.
         */
        fun updateLandPotential() {
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (row in startRow until endRow) {
                    for (column in 0 until cellsAcross) {
                        val cell = row * cellsAcross + column
                        if (!inputs.isLand[cell]) continue
                        val humidity = humidity[index(row, column)].toDouble().coerceIn(0.0, 1.0)
                        potentialMm[cell] = ((potentialConstant[cell] + potentialPerRootHumidity[cell] *
                            kotlin.math.sqrt(humidity) + potentialPerHumidity[cell] * humidity)
                            .coerceAtLeast(0.0) * SurfaceEvaporation.DAYS_PER_YEAR_DOUBLE).toFloat()
                    }
                }
            }
        }

        /**
         * Both potential rates at every sea cell, and the open water's everywhere, from the last
         * lap's air: the fields the climate hands on beside the land's.
         */
        fun finishPotentials() {
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (row in startRow until endRow) {
                    val sun = extraterrestrialOfRow[row]
                    for (column in 0 until cellsAcross) {
                        val cell = row * cellsAcross + column
                        val airC = airTemperatureC[cell].toDouble()
                        val elevation = inputs.elevationM[cell].toDouble()
                        val humidity = humidity[index(row, column)].toDouble().coerceIn(0.0, 1.0)
                        if (!inputs.isLand[cell]) {
                            potentialMm[cell] = (SurfaceEvaporation.referenceTerms(airC, sun, elevation, windAt2m(cell))
                                .mmPerDay(humidity) * SurfaceEvaporation.DAYS_PER_YEAR_DOUBLE).toFloat()
                        }
                        openWaterMm[cell] = (SurfaceEvaporation.openWaterTerms(airC, sun, elevation, windAt2m(cell))
                            .mmPerDay(humidity) * SurfaceEvaporation.DAYS_PER_YEAR_DOUBLE).toFloat()
                    }
                }
            }
        }

        fun rainMmRowMajor(cell: Int): Float {
            val here = index(cell / cellsAcross, cell % cellsAcross)
            return rainMm[here] + freeRainMm[here]
        }

        /** This lap's net condensation, millimeters a year, row-major: the free troposphere's rain all condensed where it fell. */
        fun condensationRowMajor(): FloatArray = FloatArray(cellCount) {
            val here = index(it / cellsAcross, it % cellsAcross)
            condensationMm[here] + freeRainMm[here]
        }

        fun landRainMmRowMajor(cell: Int): Float {
            val here = index(cell / cellsAcross, cell % cellsAcross)
            return landRainMm[here] + freeLandRainMm[here]
        }

        fun sourceMmRowMajor(cell: Int): Float = sourceMm[index(cell / cellsAcross, cell % cellsAcross)]
        fun groundReturnMmRowMajor(cell: Int): Float =
            if (surface[index(cell / cellsAcross, cell % cellsAcross)] == LAND) sourceMmRowMajor(cell) else 0f
        fun potentialMmRowMajor(cell: Int): Float = potentialMm[cell]
        fun openWaterMmRowMajor(cell: Int): Float = openWaterMm[cell]
        fun returnPotentialMm(cell: Int): Float = returnPotential[cell]
        fun returnRainMm(cell: Int): Float = returnRain[cell]

        fun rainField(): FloatField {
            val field = FloatField(cellsAcross, cellsDown)
            for (cell in 0 until cellCount) field.data[cell] = rainMmRowMajor(cell)
            return field
        }

        /**
         * One layer's transport: its wind's sweeps, held through every lap, its parcels, banks and
         * the working space of one column's solve. The boundary layer's carries its air beside its
         * water ([Layer.isBoundary]), and its cloud and falling water, which the free troposphere,
         * raining what it condenses where it condenses, has none of.
         */
        inner class Layer(val isBoundary: Boolean, sweepEastwardMps: FloatArray) {

            /**
             * Each cell's sweep, east (1) or west (-1), held through every lap. Column-major: cell
             * (row, column) is column * cellsDown + row.
             */
            val direction = ByteArray(cellCount).also { held ->
                for (cell in 0 until cellCount) {
                    held[(cell % cellsAcross) * cellsDown + cell / cellsAcross] = if (sweepEastwardMps[cell] >= 0f) 1 else -1
                }
            }

            /**
             * How much more column water a unit of parcel is at each cell than at the reference speed:
             * `U / |u|`. The crossing time is the reference's times this.
             */
            val stretch = FloatArray(cellCount)
            val faceShare = FloatArray(cellCount)
            var convergenceShare: FloatArray? = null

            // The layer's state: per row and sweep, the parcel of vapor, of cloud and of falling
            // hydrometeors, each with its land-origin part, and the boundary layer's air; and the
            // bank of each cell.
            val eastParcel = RowState(cellsDown)
            val westParcel = RowState(cellsDown)
            val bank = DoubleArray(cellCount)
            val bankLand = DoubleArray(cellCount)
            val bankAir: DoubleArray? = if (isBoundary) DoubleArray(cellCount) else null

            /**
             * Each cell's place in the condensate banks below, or -1 (column-major). The cloud is
             * banked only where a row's wind turns, into a cell whose own downwind neighbor along the
             * row is of the other sweep, and with the sweeps held those cells are fixed and a few
             * hundredths of the map's: banks for every cell would hold 67 MB a half-year at 1,024
             * rows, most of it zero. The free troposphere carries no condensate and banks none.
             */
            private val turnSlot = IntArray(cellCount) { -1 }
            private val turnCells: Int = run {
                var count = 0
                if (isBoundary) {
                    for (column in 0 until cellsAcross) {
                        for (row in 0 until cellsDown) {
                            val here = index(row, column)
                            val sweep = direction[here].toInt()
                            if (direction[index(row, (column + sweep + cellsAcross) % cellsAcross)].toInt() != sweep) turnSlot[here] = count++
                        }
                    }
                }
                count
            }

            /** The cloud and the falling water banked where the wind turns, with their land-origin parts, parcel units, by [turnSlot]. */
            val bankCloud = DoubleArray(turnCells)
            val bankCloudLand = DoubleArray(turnCells)
            val bankFalling = DoubleArray(turnCells)
            val bankFallingLand = DoubleArray(turnCells)

            /**
             * Each cell's place among the cells whose sent parcel the westward sweep reads, or -1
             * (column-major). The westward sweep's faces read what an eastward row sent on only in
             * the cells where it stands beside a westward row of the same column, a few hundredths of
             * the map's with the sweeps held; kept for every cell the arrays below would hold 84 MB a
             * half-year over the two layers at 1,024 rows, most of it never read.
             */
            private val sentSlot = IntArray(cellCount) { -1 }
            private val sentSlots: Int = run {
                var count = 0
                for (here in 0 until cellCount) {
                    if (direction[here] <= 0) continue
                    val row = here % cellsDown
                    val westwardAbove = row > 0 && direction[here - 1] < 0
                    val westwardBelow = row < cellsDown - 1 && direction[here + 1] < 0
                    if (westwardAbove || westwardBelow) sentSlot[here] = count++
                }
                count
            }

            /** The parcel each of those cells sent on, by [sentSlot], for the westward sweep's faces to read. */
            val sentParcel = DoubleArray(sentSlots)
            val sentParcelLand = DoubleArray(sentSlots)
            val sentParcelAir: DoubleArray? = if (isBoundary) DoubleArray(sentSlots) else null

            // Working space for one column's solve.
            val role = ByteArray(cellsDown)
            val water = DoubleArray(cellsDown)
            val waterLand = DoubleArray(cellsDown)
            val waterAir = DoubleArray(cellsDown)
            val lower = DoubleArray(cellsDown)
            val diagonal = DoubleArray(cellsDown)
            val upper = DoubleArray(cellsDown)
            val sweepUpper = DoubleArray(cellsDown)
            val sweepSolved = DoubleArray(cellsDown)
            val sweepSolvedLand = DoubleArray(cellsDown)
            val sweepSolvedAir = DoubleArray(cellsDown)
            val acrossBefore = DoubleArray(cellsDown)
            val acrossBeforeLand = DoubleArray(cellsDown)
            val acrossBeforeAir = DoubleArray(cellsDown)

            /**
             * Each marched row's outflow by the mean wind, per unit of its solved parcel, through its
             * face with a row of the other sweep above or below: what [solveFaces] hands to the bank
             * there.
             */
            val sentAbove = DoubleArray(cellsDown)
            val sentBelow = DoubleArray(cellsDown)

            /**
             * The crossings this layer solves, one for each piece its rows are marched in
             * ([rowChunks]); a piece solves one at a time ([ColumnCrossing]).
             */
            val crossings = Array(rowChunks) { ColumnCrossing() }

            /** This lap's part of the ledger, when one is kept. */
            var entry: MoistureLedger.Lap? = null

            /** The boundary layer's lift yield, filled per cell. */
            private val yield = DoubleArray(2)

            /** Its stretch and faces from the layer's wind, [eastwardMps] and [southwardMps], row-major. */
            fun precomputeTransport(eastwardMps: FloatArray, southwardMps: FloatArray) {
                val slowest = TRANSPORT_SPEED_MPS * SLOWEST_SPEED_SHARE
                parallelChunks(0, cellsAcross) { startColumn, endColumn ->
                    for (column in startColumn until endColumn) {
                        for (row in 0 until cellsDown) {
                            stretch[index(row, column)] =
                                (TRANSPORT_SPEED_MPS / maxOf(leavingSpeedMps(eastwardMps, row, column), slowest)).toFloat()
                        }
                        // The meridional wind at the face below each row, the mean of the two rows'.
                        for (row in 0 until cellsDown - 1) {
                            val above = southwardMps[row * cellsAcross + column]
                            val below = southwardMps[(row + 1) * cellsAcross + column]
                            faceShare[index(row, column)] = ((above + below) * 0.5 * grid.faceSharePerMps[row]).toFloat()
                        }
                    }
                }
            }

            /**
             * The air's own budget per column, for the convergence closure: what flows in through the
             * four sides less what flows out, as shares of the column.
             */
            fun precomputeConvergence() {
                val shares = convergenceShare ?: FloatArray(cellCount).also { convergenceShare = it }
                parallelChunks(0, cellsAcross) { startColumn, endColumn ->
                    for (column in startColumn until endColumn) {
                        val west = (column + cellsAcross - 1) % cellsAcross
                        val east = (column + 1) % cellsAcross
                        for (row in 0 until cellsDown) {
                            val here = index(row, column)
                            var inflow = (if (direction[index(row, west)] > 0) 1.0 else 0.0) +
                                (if (direction[index(row, east)] < 0) 1.0 else 0.0)
                            var outflow = 1.0
                            if (row > 0) {
                                val north = faceShare[index(row - 1, column)].toDouble()
                                if (north > 0.0) inflow += north else outflow -= north
                            }
                            if (row < cellsDown - 1) {
                                val south = faceShare[here].toDouble()
                                if (south < 0.0) inflow -= south else outflow += south
                            }
                            shares[here] = (inflow - outflow).coerceAtLeast(0.0).toFloat()
                        }
                    }
                }
            }

            /**
             * The speed a cell's water leaves it by along its held sweep, meters a second: the zonal
             * wind on its downwind face, the mean of its own and its downwind neighbor's along the
             * sweep, where that neighbor is of the same sweep; its own where the wind turns there and
             * the water is banked. Read at the cell's own center instead, a cell beside a line where
             * the zonal wind parts held twice the water, and the doubling compounded row by row toward
             * a pole (docs/DESIGN_LEDGER.md, A1-6c).
             */
            private fun leavingSpeedMps(eastward: FloatArray, row: Int, column: Int): Double {
                val sweep = direction[index(row, column)]
                val own = eastward[row * cellsAcross + column] * sweep.toDouble()
                val downwindColumn = (column + sweep + cellsAcross) % cellsAcross
                if (direction[index(row, downwindColumn)] != sweep) return own
                return 0.5 * (own + eastward[row * cellsAcross + downwindColumn] * sweep.toDouble())
            }

            /** The boundary layer's air at its own mass in every row's parcels, and none banked. */
            fun resetAir() {
                if (!isBoundary) return
                for (row in 0 until cellsDown) {
                    eastParcel.air[row] = ColumnWater.BOUNDARY_LAYER_MASS_KG_PER_M2 / stretch[index(row, 0)]
                    westParcel.air[row] = ColumnWater.BOUNDARY_LAYER_MASS_KG_PER_M2 / stretch[index(row, cellsAcross - 1)]
                }
                bankAir?.fill(0.0)
            }

            fun bankParcelIfTurned(parcel: RowState, row: Int, first: Int, sweep: Int) {
                if (direction[first].toInt() == sweep) return
                bank[first] += parcel.vapor[row] + parcel.cloud[row] + parcel.falling[row]
                bankLand[first] += parcel.vaporLand[row] + parcel.cloudLand[row] + parcel.fallingLand[row]
                bankAir?.let { it[first] += parcel.air[row] }
                parcel.clear(row)
            }

            /** Starts the layer's bank and parcels from a march's end (see [WaterInFlight]). */
            fun startFrom(
                startBank: FloatArray,
                eastwardVapor: DoubleArray, eastwardCloud: DoubleArray?, eastwardFalling: DoubleArray?,
                westwardVapor: DoubleArray, westwardCloud: DoubleArray?, westwardFalling: DoubleArray?
            ) {
                for (cell in 0 until cellCount) bank[index(cell / cellsAcross, cell % cellsAcross)] = startBank[cell].toDouble()
                bankCloud.fill(0.0)
                bankCloudLand.fill(0.0)
                bankFalling.fill(0.0)
                bankFallingLand.fill(0.0)
                for (row in 0 until cellsDown) {
                    eastParcel.clear(row)
                    westParcel.clear(row)
                    eastParcel.vapor[row] = eastwardVapor[row]
                    westParcel.vapor[row] = westwardVapor[row]
                    if (eastwardCloud != null) eastParcel.cloud[row] = eastwardCloud[row]
                    if (eastwardFalling != null) eastParcel.falling[row] = eastwardFalling[row]
                    if (westwardCloud != null) westParcel.cloud[row] = westwardCloud[row]
                    if (westwardFalling != null) westParcel.falling[row] = westwardFalling[row]
                    // A parcel this world's wind does not march in from the first column waits in the
                    // bank there, as between two laps ([reseason]).
                    bankParcelIfTurned(eastParcel, row, index(row, 0), 1)
                    bankParcelIfTurned(westParcel, row, index(row, cellsAcross - 1), -1)
                }
                bankLand.fill(0.0)
            }

            /** The bank at every cell, row-major, its banked condensate and [extra] (column-major) with it. */
            fun bankRowMajor(extra: DoubleArray?): FloatArray = FloatArray(cellCount) {
                val here = index(it / cellsAcross, it % cellsAcross)
                val slot = turnSlot[here]
                (bank[here] + (extra?.get(here) ?: 0.0) + if (slot >= 0) bankCloud[slot] + bankFalling[slot] else 0.0).toFloat()
            }

            fun storage(): Double = eastParcel.total() + westParcel.total() + bank.sum() + bankCloud.sum() + bankFalling.sum()
            fun landStorage(): Double =
                eastParcel.totalLand() + westParcel.totalLand() + bankLand.sum() + bankCloudLand.sum() + bankFallingLand.sum()

            /**
             * The faces of one column, solved: every row this sweep marches, and in the westward
             * sweep every eastward row beside one of them, as one tridiagonal system in the parcels
             * (see the class comment). [water] holds the parcels going in and comes out with them
             * solved; [waterLand] and the boundary layer's [waterAir] the same, which the same matrix
             * moves.
             */
            private fun solveFaces(base: Int) {
                for (row in 0 until cellsDown) {
                    lower[row] = 0.0
                    upper[row] = 0.0
                    diagonal[row] = 1.0
                    sentAbove[row] = 0.0
                    sentBelow[row] = 0.0
                }
                for (face in 0 until cellsDown - 1) {
                    val above = face
                    val below = face + 1
                    val roleAbove = role[above]
                    val roleBelow = role[below]
                    // A face is solved when a marched row is on either side of it; between two rows
                    // of the other sweep it is that sweep's.
                    if (roleAbove != MARCHED && roleBelow != MARCHED) continue
                    val stretchAbove = stretch[base + above].toDouble()
                    val stretchBelow = stretch[base + below].toDouble()
                    val advection = faceShare[base + face].toDouble()
                    // The mean wind out of a marched row into the other sweep's row beside it,
                    // implicit in the marched row's own equation and handed to the bank.
                    if (roleAbove == MARCHED && roleBelow != MARCHED && advection > 0.0) {
                        sentBelow[above] = advection * stretchAbove
                        diagonal[above] += sentBelow[above]
                    }
                    if (roleBelow == MARCHED && roleAbove != MARCHED && advection < 0.0) {
                        sentAbove[below] = -advection * stretchBelow
                        diagonal[below] += sentAbove[below]
                    }
                    if (roleAbove == OUTSIDE || roleBelow == OUTSIDE) continue
                    val mixing = grid.faceMixing[face]
                    // Out of the row above, into the row below, as coefficients on each row's parcel:
                    // the eddies' mixing, and the mean wind's where both rows are this sweep's.
                    val oneSweep = roleAbove == MARCHED && roleBelow == MARCHED
                    val fromAbove = mixing * stretchAbove + (if (oneSweep && advection > 0.0) advection * stretchAbove else 0.0)
                    val fromBelow = mixing * stretchBelow + (if (oneSweep && advection < 0.0) -advection * stretchBelow else 0.0)
                    diagonal[above] += fromAbove
                    diagonal[below] += fromBelow
                    upper[above] -= fromBelow
                    lower[below] -= fromAbove
                }
                // The Thomas algorithm, which needs no pivoting on a diagonally dominant M-matrix.
                var previousUpper = 0.0
                var previousSolved = 0.0
                var previousSolvedLand = 0.0
                var previousSolvedAir = 0.0
                for (row in 0 until cellsDown) {
                    val denominator = diagonal[row] - lower[row] * previousUpper
                    sweepUpper[row] = upper[row] / denominator
                    sweepSolved[row] = (water[row] - lower[row] * previousSolved) / denominator
                    sweepSolvedLand[row] = (waterLand[row] - lower[row] * previousSolvedLand) / denominator
                    previousUpper = sweepUpper[row]
                    previousSolved = sweepSolved[row]
                    previousSolvedLand = sweepSolvedLand[row]
                    if (isBoundary) {
                        sweepSolvedAir[row] = (waterAir[row] - lower[row] * previousSolvedAir) / denominator
                        previousSolvedAir = sweepSolvedAir[row]
                    }
                }
                water[cellsDown - 1] = sweepSolved[cellsDown - 1]
                waterLand[cellsDown - 1] = sweepSolvedLand[cellsDown - 1]
                if (isBoundary) waterAir[cellsDown - 1] = sweepSolvedAir[cellsDown - 1]
                for (row in cellsDown - 2 downTo 0) {
                    water[row] = sweepSolved[row] - sweepUpper[row] * water[row + 1]
                    waterLand[row] = sweepSolvedLand[row] - sweepUpper[row] * waterLand[row + 1]
                    if (isBoundary) waterAir[row] = sweepSolvedAir[row] - sweepUpper[row] * waterAir[row + 1]
                }
            }

            fun sweepColumn(column: Int, sweep: Int) {
                val base = column * cellsDown
                val parcel = if (sweep > 0) eastParcel else westParcel
                val air = bankAir

                // What arrives: the row's parcel from the column behind and whatever was banked here;
                // and, in the westward sweep, the eastward rows beside them as they left this column.
                for (row in 0 until cellsDown) {
                    val here = base + row
                    if (direction[here].toInt() == sweep) {
                        role[row] = MARCHED
                        // A bank the westward sweep's faces drew on can hold less than nothing; what
                        // the parcel cannot pay of it waits there for the next lap's.
                        val slot = turnSlot[here]
                        if (slot >= 0) {
                            parcel.cloud[row] += bankCloud[slot]
                            parcel.cloudLand[row] += bankCloudLand[slot]
                            parcel.falling[row] += bankFalling[slot]
                            parcel.fallingLand[row] += bankFallingLand[slot]
                            bankCloud[slot] = 0.0
                            bankCloudLand[slot] = 0.0
                            bankFalling[slot] = 0.0
                            bankFallingLand[slot] = 0.0
                        }
                        val arriving = parcel.vapor[row] + bank[here]
                        val arrivingLand = parcel.vaporLand[row] + bankLand[here]
                        water[row] = arriving.coerceAtLeast(0.0)
                        waterLand[row] = arrivingLand.coerceIn(0.0, water[row])
                        bank[here] = arriving - water[row]
                        bankLand[here] = arrivingLand - waterLand[row]
                        if (air != null) {
                            val arrivingAir = parcel.air[row] + air[here]
                            waterAir[row] = arrivingAir.coerceAtLeast(0.0)
                            air[here] = arrivingAir - waterAir[row]
                        }
                    } else {
                        role[row] = OUTSIDE
                        water[row] = 0.0
                        waterLand[row] = 0.0
                        waterAir[row] = 0.0
                    }
                }
                if (sweep < 0) {
                    val sentAir = sentParcelAir
                    for (row in 0 until cellsDown) {
                        if (role[row] != OUTSIDE) continue
                        val besideMarched = (row > 0 && role[row - 1] == MARCHED) ||
                            (row < cellsDown - 1 && role[row + 1] == MARCHED)
                        if (!besideMarched) continue
                        role[row] = ACROSS
                        // What the eastward row will bring here, net of what it already owes here.
                        val sentPlace = sentSlot[base + row]
                        val bringing = (sentParcel[sentPlace] + bank[base + row]).coerceAtLeast(0.0)
                        water[row] = bringing
                        waterLand[row] = (sentParcelLand[sentPlace] + bankLand[base + row]).coerceIn(0.0, bringing)
                        acrossBefore[row] = water[row]
                        acrossBeforeLand[row] = waterLand[row]
                        if (air != null && sentAir != null) {
                            waterAir[row] = (sentAir[sentPlace] + air[base + row]).coerceAtLeast(0.0)
                            acrossBeforeAir[row] = waterAir[row]
                        }
                    }
                }
                solveFaces(base)
                // What the marched rows' mean wind sent into the other sweep's rows waits in those
                // rows' bank: the westward sweep's this lap, the eastward sweep's the next.
                for (row in 0 until cellsDown) {
                    if (role[row] != MARCHED) continue
                    if (sentAbove[row] > 0.0) {
                        bank[base + row - 1] += sentAbove[row] * water[row]
                        bankLand[base + row - 1] += sentAbove[row] * waterLand[row]
                        if (air != null) air[base + row - 1] += sentAbove[row] * waterAir[row]
                    }
                    if (sentBelow[row] > 0.0) {
                        bank[base + row + 1] += sentBelow[row] * water[row]
                        bankLand[base + row + 1] += sentBelow[row] * waterLand[row]
                        if (air != null) air[base + row + 1] += sentBelow[row] * waterAir[row]
                    }
                }
                // What the solve's mixing gave or took from the eastward rows waits in their bank.
                if (sweep < 0) {
                    for (row in 0 until cellsDown) {
                        if (role[row] != ACROSS) continue
                        bank[base + row] += water[row] - acrossBefore[row]
                        bankLand[base + row] += waterLand[row] - acrossBeforeLand[row]
                        if (air != null) air[base + row] += waterAir[row] - acrossBeforeAir[row]
                    }
                }

                // The column's physics, row by row, and the water it sends on. The rows share
                // nothing here: each reads its own arrivals and writes its own parcel and its own
                // cell of every field, and what it banks lands in the next column's cells, so they
                // are marched in [rowChunks] pieces side by side. A ledger's sums are not made
                // for that, and a march that keeps one marches its rows in order.
                val downwindBase = ((column + sweep + cellsAcross) % cellsAcross) * cellsDown
                if (rowChunks <= 1 || entry != null) {
                    marchRows(0, cellsDown, crossings[0], base, sweep, parcel, downwindBase)
                } else {
                    parallelChunks(0, rowChunks) { firstChunk, lastChunk ->
                        for (chunk in firstChunk until lastChunk) {
                            marchRows(chunk * cellsDown / rowChunks, (chunk + 1) * cellsDown / rowChunks, crossings[chunk], base, sweep, parcel, downwindBase)
                        }
                    }
                }
            }

            /** The crossings of rows [firstRow] to [lastRow] (exclusive) of one column, each solved on [budget]. */
            private fun marchRows(firstRow: Int, lastRow: Int, budget: ColumnCrossing, base: Int, sweep: Int, parcel: RowState, downwindBase: Int) {
                for (row in firstRow until lastRow) {
                    if (role[row] != MARCHED) {
                        parcel.clear(row)
                        continue
                    }
                    if (isBoundary) boundaryCell(budget, base, row, sweep, parcel, downwindBase) else freeCell(budget, base, row, sweep, parcel, downwindBase)
                }
            }

            /** Checks the arriving vapor and its tracer for the ledger. */
            private fun checkArriving(vapor: Double, vaporLand: Double) {
                val record = entry ?: return
                if (vapor < -NEGATIVE_SLACK_MM) record.negativeParcels++
                val slack = TRACER_SLACK * (abs(vapor) + 1.0)
                if (vaporLand < -slack || vaporLand > vapor + slack) record.tracerOutOfBounds++
            }

            /**
             * The boundary layer's crossing of a cell: its air's convergence sent up or made up from
             * the free troposphere, its sources and sinks solved together ([ColumnCrossing]), its
             * condensate's delays, and what it sends the free troposphere.
             */
            private fun boundaryCell(budget: ColumnCrossing, base: Int, row: Int, sweep: Int, parcel: RowState, downwindBase: Int) {
                val here = base + row
                val cell = row * cellsAcross + base / cellsDown
                val kind = surface[here]
                val surfaceIndex = kind.toInt()
                val toColumnWater = stretch[here].toDouble()
                val seconds = grid.secondsPerColumn[row] * toColumnWater
                val slowing = suppression[here].toDouble()
                val layerMass = ColumnWater.BOUNDARY_LAYER_MASS_KG_PER_M2

                // Everything below is in the cell's own column water, the parcel times the stretch.
                var vapor = water[row] * toColumnWater
                var vaporLand = waterLand[row] * toColumnWater
                val air = waterAir[row] * toColumnWater
                var cloud = parcel.cloud[row] * toColumnWater
                var cloudLand = parcel.cloudLand[row] * toColumnWater
                val falling = parcel.falling[row] * toColumnWater
                val fallingLand = parcel.fallingLand[row] * toColumnWater
                checkArriving(vapor, vaporLand)
                vapor = vapor.coerceAtLeast(0.0)
                vaporLand = vaporLand.coerceIn(0.0, vapor)

                // The layer's air: what the march's faces gathered here over its mass rises with its
                // water into the free troposphere at once; what they took away is made up from the
                // free troposphere's lowest air, which sends it on the next lap.
                var sentUp = 0.0
                var sentUpLand = 0.0
                if (air > layerMass) {
                    val rising = (air - layerMass) / air
                    sentUp = vapor * rising
                    sentUpLand = vaporLand * rising
                    vapor -= sentUp
                    vaporLand -= sentUpLand
                }
                replacedPerSecond[here] = if (air < layerMass) ((layerMass - air) / layerMass / seconds).toFloat() else 0f

                // What the free troposphere sent, its lowest air stirred in and drawn down, at a steady
                // rate over the crossing: this lap's if it has marched the cell already, last lap's if not,
                // and spent as it is read; the layer's own air stirred out, which the crossing solves.
                val exchangePerSecond = exchangePerSecondAt(here, cell)
                val inboxAmount = toBoundary[here]
                val inboxLandAmount = toBoundaryLand[here]
                toBoundary[here] = 0.0
                toBoundaryLand[here] = 0.0
                val mixedInRate = inboxAmount / grid.secondsPerColumn[row]
                val mixedInShareLand = if (inboxAmount > 0.0) (inboxLandAmount / inboxAmount).coerceIn(0.0, 1.0) else 0.0
                val groundPressure = groundPressureKpa[here].toDouble()
                val depth = layerDepthM[here].toDouble()
                val airC = airTemperatureC[cell].toDouble()

                val surfaceRate = when (kind) {
                    OPEN_SEA -> seaTransferMassFlux[here].toDouble() * seaSurfaceHumidity[here].toDouble()
                    LAND -> groundReturnRate[here].toDouble()
                    else -> 0.0
                }
                val cloudShareLand = if (cloud > 0.0) (cloudLand / cloud).coerceIn(0.0, 1.0) else 0.0
                budget.seconds = seconds
                budget.sourceKgPerM2PerSecond = surfaceRate + mixedInRate
                budget.lawSaturatedMm = 0.0
                budget.lawOffsetMm = 0.0
                budget.holdableMm = boundaryCapMm[here].toDouble()
                budget.rainSuppression = slowing
                budget.exchangePerSecond = exchangePerSecond
                if (kind == OPEN_SEA) {
                    budget.evaporationPerSecond = seaTransferMassFlux[here].toDouble() / layerMass
                    budget.evaporationCeilingMm = seaSurfaceHumidity[here].toDouble() * layerMass
                } else {
                    budget.evaporationPerSecond = 0.0
                    budget.evaporationCeilingMm = 0.0
                }
                budget.liftMps = liftMps[here].toDouble()
                budget.airTemperatureC = airC
                budget.groundPressureKpa = groundPressure
                budget.layerDepthM = depth
                budget.descentKgPerM2PerSecond = descentRate[here].toDouble()
                budget.conversionPerSecond = slowing * config.climate.rainShadowScale / CLOUD_CONVERSION_SECONDS
                budget.falloutPerSecond = 1.0 / FALLOUT_SECONDS
                budget.condensateFallsAtOnce = false
                budget.vapor = vapor
                budget.cloud = cloud
                budget.falling = falling
                val vaporIn = vapor
                val cloudIn = cloud
                val condensateLandIn = cloudLand + fallingLand
                budget.solve()
                val mixedIn = mixedInRate * seconds
                val added = budget.sourced - mixedIn - budget.evaporationWithheld
                entry?.let { record ->
                    if (kind == OPEN_SEA) record.seaEvaporation += added / toColumnWater
                    if (kind == LAND) record.groundReturn += added / toColumnWater
                    record.condensed += (budget.lifted + budget.capped) / toColumnWater
                    record.reevaporated += budget.cloudEvaporated / toColumnWater
                    val imbalance = abs(budget.waterIn - budget.waterOut) / (budget.waterIn + CROSSING_WATER_FLOOR_MM)
                    if (imbalance > record.worstCrossingImbalance) record.worstCrossingImbalance = imbalance
                }

                // The land-origin water: the vapor's share mixed once from what came in, what the free
                // troposphere mixed in, the ground's return and the cloud the descent evaporated; the
                // condensate's share mixed once from the cloud carried in and the vapor's share of what
                // grew.
                val vaporWaterIn = vaporIn + mixedIn + added + budget.cloudEvaporated
                val vaporLandIn = vaporLand + mixedIn * mixedInShareLand + (if (kind == LAND) added else 0.0) +
                    budget.cloudEvaporated * cloudShareLand
                val vaporShareLand = if (vaporWaterIn > 0.0) (vaporLandIn / vaporWaterIn).coerceIn(0.0, 1.0) else 0.0
                val grown = budget.lifted + budget.capped
                val condensateWaterIn = cloudIn - budget.cloudEvaporated + falling + grown
                val condensateLandWaterIn = condensateLandIn - budget.cloudEvaporated * cloudShareLand + grown * vaporShareLand
                val condensateShareLand = if (condensateWaterIn > 0.0) (condensateLandWaterIn / condensateWaterIn).coerceIn(0.0, 1.0) else 0.0

                vapor = budget.vapor
                vaporLand = vapor * vaporShareLand
                cloud = budget.cloud
                cloudLand = cloud * condensateShareLand
                val fallingOut = budget.falling
                val fallingOutLand = fallingOut * condensateShareLand
                val condensateRain = budget.condensateRain
                val condensateLandRain = condensateRain * condensateShareLand

                // What the free troposphere gets of this crossing, in the march's units: the air that
                // rose and the air stirred out.
                val sentToFree = sentUp + budget.exchanged
                toFree[here] = sentToFree / toColumnWater
                toFreeLand[here] = (sentUpLand + budget.exchanged * vaporShareLand) / toColumnWater

                entry?.let { record ->
                    record.rain[MoistureLedger.Sink.CONDENSATE.ordinal][surfaceIndex] += condensateRain / toColumnWater
                    record.landOriginRain += condensateLandRain / toColumnWater
                    if (!vapor.isFinite() || !cloud.isFinite() || !fallingOut.isFinite() || !vaporLand.isFinite()) record.nonFinite++
                }

                // Rates at the cell: a parcel's worth over the reference crossing time is a cell's
                // rain over its own, since the two differ by the stretch on both sides.
                val rate = SECONDS_PER_YEAR / seconds
                rainMm[here] = (condensateRain * rate).toFloat()
                landRainMm[here] = (condensateLandRain * rate).toFloat()
                condensationMm[here] = ((grown - budget.cloudEvaporated) * rate).toFloat()
                val cap = boundaryCapMm[here].toDouble()
                humidity[here] = if (cap > 0.0) (vapor / cap).toFloat() else 1f
                sourceMm[here] = (added * rate).toFloat()
                cells?.let { record ->
                    record.boundaryLayerWater.data[cell] = vapor.toFloat()
                    record.boundaryLayerHumidity.data[cell] = humidity[here]
                    record.boundaryLayerSpecificHumidity.data[cell] = (vapor / layerMass).toFloat()
                    record.saturatedColumn.data[cell] = saturatedMm[here]
                    if (kind == OPEN_SEA) record.seaMinusAirC.data[cell] = (seaSurfaceC[cell] - airTemperatureC[cell]).toFloat()
                    if (kind == OPEN_SEA) record.seaSurface.data[cell] = seaSurfaceC[cell]
                    if (kind == LAND) record.groundReturn.data[cell] = (added * rate).toFloat()
                    if (kind == OPEN_SEA) record.seaEvaporation.data[cell] = (added * rate).toFloat()
                    record.condensateRain.data[cell] = (condensateRain * rate).toFloat()
                    record.liftedCondensation.data[cell] = (budget.lifted * rate).toFloat()
                    record.capCondensation.data[cell] = (budget.capped * rate).toFloat()
                    record.reevaporation.data[cell] = (budget.cloudEvaporated * rate).toFloat()
                    record.crossingSeconds.data[cell] = seconds.toFloat()
                    record.meanColumnWater.data[cell] = budget.meanVapor.toFloat()
                    record.exchangeToFree.data[cell] = ((sentToFree - mixedIn) * rate).toFloat()
                    record.risingAir.data[cell] = (sentUp * rate).toFloat()
                    record.descentCapacity.data[cell] = (descentRate[here] * SECONDS_PER_YEAR).toFloat()
                    if (liftMps[here] > 0f) {
                        ColumnWater.boundaryLayerLiftYield(airC, groundPressure, depth, cap / layerMass, yield)
                        record.liftingAtSaturation.data[cell] = (liftMps[here] * yield[0] * SECONDS_PER_YEAR).toFloat()
                    }
                }

                // Back into parcels, and on downwind: to the next cell of this sweep, or banked where
                // the wind turns. The layer leaves at its own mass.
                val perParcel = 1.0 / toColumnWater
                val sent = vapor * perParcel
                val sentLand = vaporLand * perParcel
                val sentAirParcel = layerMass * perParcel
                val sentPlace = sentSlot[here]
                if (sentPlace >= 0) {
                    sentParcel[sentPlace] = sent
                    sentParcelLand[sentPlace] = sentLand
                    sentParcelAir?.let { it[sentPlace] = sentAirParcel }
                }
                val downwind = downwindBase + row
                if (direction[downwind].toInt() == sweep) {
                    parcel.vapor[row] = sent
                    parcel.vaporLand[row] = sentLand
                    parcel.air[row] = sentAirParcel
                    parcel.cloud[row] = cloud * perParcel
                    parcel.cloudLand[row] = cloudLand * perParcel
                    parcel.falling[row] = fallingOut * perParcel
                    parcel.fallingLand[row] = fallingOutLand * perParcel
                } else {
                    // Where the wind turns, the cloud and the falling water go on in the other sweep
                    // as the vapor does, and fall over their own delays there.
                    bank[downwind] += sent
                    bankLand[downwind] += sentLand
                    bankAir?.let { it[downwind] += sentAirParcel }
                    val slot = turnSlot[downwind]
                    bankCloud[slot] += cloud * perParcel
                    bankCloudLand[slot] += cloudLand * perParcel
                    bankFalling[slot] += fallingOut * perParcel
                    bankFallingLand[slot] += fallingOutLand * perParcel
                    parcel.clear(row)
                }
            }

            /**
             * The free troposphere's crossing of a cell: the convergence closure when it is on, and
             * the column's rain law and the layer's saturation solved together ([ColumnCrossing]).
             */
            private fun freeCell(budget: ColumnCrossing, base: Int, row: Int, sweep: Int, parcel: RowState, downwindBase: Int) {
                val here = base + row
                val cell = row * cellsAcross + base / cellsDown
                val surfaceIndex = surface[here].toInt()
                val toColumnWater = stretch[here].toDouble()
                val seconds = grid.secondsPerColumn[row] * toColumnWater
                val slowing = suppression[here].toDouble()

                var vapor = water[row] * toColumnWater
                var vaporLand = waterLand[row] * toColumnWater
                checkArriving(vapor, vaporLand)
                vapor = vapor.coerceAtLeast(0.0)
                vaporLand = vaporLand.coerceIn(0.0, vapor)

                // The convergence closure, when switched on, on the water arriving.
                var convergenceRain = 0.0
                var convergenceLandRain = 0.0
                val convergence = (convergenceShare?.get(here)?.toDouble() ?: 0.0) * slowing * toColumnWater
                if (convergence > 0.0 && vapor > 0.0) {
                    convergenceRain = vapor * (1.0 - exp(-convergence))
                    convergenceLandRain = convergenceRain * (vaporLand / vapor)
                    vaporLand -= convergenceLandRain
                    vapor -= convergenceRain
                }

                // What the boundary layer sent last lap, at a steady rate over the crossing; and what
                // this layer gives it, its lowest air stirred down and drawn into the boundary layer's
                // place, at the saturation humidity of the boundary layer's top and this layer's own
                // relative humidity, linear in its water.
                val seaLevelC = seaLevelTemperatureC(here)
                val saturatedFromSea = ColumnWater.saturatedColumnMm(seaLevelC)
                val freeSaturated = ColumnWater.referenceFreeSaturatedMm(seaLevelC)
                // The free troposphere's lowest air is the air at the boundary layer's top over this
                // ground, at the environment's temperature there: over high ground colder and thinner
                // than the air that stood over the lowlands' boundary layer.
                val topPressure = (groundPressureKpa[here] - ColumnWater.BOUNDARY_LAYER_DEPTH_KPA).coerceAtLeast(MIN_TOP_PRESSURE_KPA)
                val topC = airTemperatureC[cell] - config.climate.lapseRateCPerKm * layerDepthM[here] / METERS_PER_KM
                val baseShare = if (freeSaturated > 0.0) {
                    ColumnWater.saturationSpecificHumidity(topC, topPressure) * ColumnWater.BOUNDARY_LAYER_MASS_KG_PER_M2 / freeSaturated
                } else 0.0
                // What the boundary layer sent up, this lap's if it has marched the cell already and
                // last lap's if not, and spent as it is read.
                val inboxAmount = toFree[here]
                val inboxLandAmount = toFreeLand[here]
                toFree[here] = 0.0
                toFreeLand[here] = 0.0
                val sentInRate = inboxAmount / grid.secondsPerColumn[row]
                val sentInShareLand = if (inboxAmount > 0.0) (inboxLandAmount / inboxAmount).coerceIn(0.0, 1.0) else 0.0
                budget.seconds = seconds
                budget.sourceKgPerM2PerSecond = sentInRate
                budget.lawSaturatedMm = saturatedFromSea
                budget.lawOffsetMm = lawOffsetMm(seaLevelC)
                budget.holdableMm = freeSaturated
                budget.rainSuppression = slowing
                budget.exchangePerSecond = baseShare * (exchangePerSecondAt(here, cell) + replacedPerSecond[here])
                budget.evaporationPerSecond = 0.0
                budget.evaporationCeilingMm = 0.0
                budget.liftMps = 0.0
                budget.descentKgPerM2PerSecond = 0.0
                budget.condensateFallsAtOnce = true
                budget.vapor = vapor
                budget.cloud = 0.0
                budget.falling = 0.0
                val vaporIn = vapor
                budget.solve()
                val sentIn = budget.sourced
                val shareLand = if (vaporIn + sentIn > 0.0) ((vaporLand + sentIn * sentInShareLand) / (vaporIn + sentIn)).coerceIn(0.0, 1.0) else 0.0
                val rained = budget.columnRain + budget.condensateRain
                vapor = budget.vapor
                vaporLand = vapor * shareLand
                val landRained = rained * shareLand + convergenceLandRain
                toBoundary[here] = budget.exchanged / toColumnWater
                toBoundaryLand[here] = budget.exchanged * shareLand / toColumnWater
                entry?.let { record ->
                    record.rain[MoistureLedger.Sink.COLUMN.ordinal][surfaceIndex] += rained / toColumnWater
                    record.rain[MoistureLedger.Sink.CONVERGENCE.ordinal][surfaceIndex] += convergenceRain / toColumnWater
                    record.landOriginRain += landRained / toColumnWater
                    record.condensed += budget.capped / toColumnWater
                    val imbalance = abs(budget.waterIn - budget.waterOut) / (budget.waterIn + CROSSING_WATER_FLOOR_MM)
                    if (imbalance > record.worstCrossingImbalance) record.worstCrossingImbalance = imbalance
                    if (!vapor.isFinite() || !vaporLand.isFinite()) record.nonFinite++
                }

                val rate = SECONDS_PER_YEAR / seconds
                freeRainMm[here] = ((rained + convergenceRain) * rate).toFloat()
                freeLandRainMm[here] = (landRained * rate).toFloat()
                cells?.let { record ->
                    record.freeWater.data[cell] = vapor.toFloat()
                    record.columnRain.data[cell] = (rained * rate).toFloat()
                    record.convergenceRain.data[cell] = (convergenceRain * rate).toFloat()
                    record.lawHumidity.data[cell] = lawHumidity(budget.meanVapor, seaLevelC).toFloat()
                    record.freeSaturatedColumn.data[cell] = budget.holdableMm.toFloat()
                    record.freeCrossingSeconds.data[cell] = seconds.toFloat()
                }

                val perParcel = 1.0 / toColumnWater
                val sent = vapor * perParcel
                val sentLand = vaporLand * perParcel
                val sentPlace = sentSlot[here]
                if (sentPlace >= 0) {
                    sentParcel[sentPlace] = sent
                    sentParcelLand[sentPlace] = sentLand
                }
                val downwind = downwindBase + row
                if (direction[downwind].toInt() == sweep) {
                    parcel.vapor[row] = sent
                    parcel.vaporLand[row] = sentLand
                } else {
                    bank[downwind] += sent
                    bankLand[downwind] += sentLand
                    parcel.clear(row)
                }
            }
        }
    }

    /**
     * What one row and sweep carries from column to column, in the march's parcels: vapor, cloud
     * and falling hydrometeors, each with its land-origin part, and the boundary layer's air.
     */
    private class RowState(rows: Int) {
        val vapor = DoubleArray(rows)
        val vaporLand = DoubleArray(rows)
        val cloud = DoubleArray(rows)
        val cloudLand = DoubleArray(rows)
        val falling = DoubleArray(rows)
        val fallingLand = DoubleArray(rows)
        val air = DoubleArray(rows)

        fun clear(row: Int) {
            vapor[row] = 0.0; vaporLand[row] = 0.0
            cloud[row] = 0.0; cloudLand[row] = 0.0
            falling[row] = 0.0; fallingLand[row] = 0.0
            air[row] = 0.0
        }

        fun total(): Double = vapor.sum() + cloud.sum() + falling.sum()
        fun totalLand(): Double = vaporLand.sum() + cloudLand.sum() + fallingLand.sum()
    }

    /** The least pressure a boundary layer's top is read at, kilopascals: under any ground the plates raise. */
    private const val MIN_TOP_PRESSURE_KPA = 10.0

    /** The dry adiabat's lapse rate, `g / c_p`, kelvin per meter, and pascals in a kilopascal. */
    private const val DRY_LAPSE_K_PER_M = STANDARD_GRAVITY_MPS2 / AIR_HEAT_CAPACITY_J_PER_KG_K
    private const val PASCALS_PER_KPA = 1_000.0

    /**
     * How far the land-origin water may stand outside zero to the column's water before the ledger
     * counts it, as a share: a millionth, for the rounding of the doubles it is carried in.
     */
    private const val TRACER_SLACK = 1.0e-6

    /**
     * The water a crossing's closure is read against beside its own, millimeters: a micrometer,
     * so a column next to dry, whose doubles' rounding is a large share of its few molecules of
     * water, is not read as leaking.
     */
    private const val CROSSING_WATER_FLOOR_MM = 1.0e-3

    /**
     * How far below zero a parcel may stand after transport before the ledger counts it negative,
     * in millimeters: a billionth, the rounding of the solve's doubles.
     */
    private const val NEGATIVE_SLACK_MM = 1.0e-9
}
