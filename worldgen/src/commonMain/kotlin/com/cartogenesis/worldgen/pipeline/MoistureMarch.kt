package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.time.TimeSource

/**
 * The rain: water carried along the wind in kilograms per square meter, taken up from the sea and
 * the ground, rained out where the column cannot keep it, and conserved between the two.
 *
 * # What is solved
 *
 * The steady state of the column-water budget on the sphere,
 *
 *     div(W V) = E - P,
 *
 * with `W` the column's water (kg m⁻², which is millimeters), `V` the surface wind, `E` what the
 * sea and the ground put in and `P` what rains. Marched column by column along each cell's own
 * zonal wind `u`, the steady equation is a march in space and not in time: crossing a column of
 * ground width `dx = cellWidth cos(latitude)` takes `dt = dx / |u|`, every source and sink is
 * charged over that `dt`, and what a row carries across a column's side is the mass flux
 * `|u| W cellHeight`. The march keeps that flux in one unit for every row, the column water it
 * would be at [TRANSPORT_SPEED_MPS], so a row's **parcel** is `|u| W / U`, and the parcels of
 * different rows and different speeds add without weights. Where the wind slows the same flux is
 * more water over the ground and stays longer, and where it reverses the column water a cell
 * holds is still finite while its flux passes through zero.
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
 * one. Water that crosses from one sweep's cell into the other's where two zonal winds meet head
 * on is **banked** at the receiving cell and taken up by the other sweep when it reaches it, its cloud
 * and falling water with it, to fall over their own delays there; rained out at the turn instead,
 * they drew a wet line wherever the zonal wind reversed. So is
 * what the mean wind carries through a face into a row of the other sweep: each row's outflow
 * through such a face is solved in its own sweep's system, implicitly, beside the inflow its own
 * sweep's rows give it in the same column, and banked at the receiving cell, the eastward sweep's
 * for the westward one this lap and the westward sweep's for the eastward one the next. That is
 * the flux the same face would carry between two rows of one sweep. Solved instead in the westward
 * sweep's system, with an eastward row entering it as the water it had left the column with and
 * none of its own sweep's faces, the eastward row could send no more than it carried along the row
 * and keep no more than it could mix back, so water piled up on the upwind side of every reversal
 * of the zonal wind and the downwind side went dry (docs/DESIGN_LEDGER.md, A1-6b). The eddies'
 * mixing between the two sweeps' rows is still solved that way, by the westward sweep with the
 * eastward rows beside its own in one system: mixed a lap late in one direction, as the mean wind's
 * outflow is, two rows that mix hard each wait on the other's last lap, and the march settled only
 * over dozens of laps.
 *
 * # The sweeps are held
 *
 * Which sweep marches a cell is its first lap's wind's to say, and the coupled laps after it keep
 * that partition: a cell whose wind later turns against its sweep is carried along it at the
 * march's least speed, as calm. Where the zonal wind is weak, near every reversal, the solved
 * atmosphere's lap-to-lap changes turned ten thousand cells a lap from one sweep to the other, and
 * the march's answer stepped with each turn, so the coupled loop never settled: its residual hung
 * at a third of the heating over forty laps on seed 42 at 1,024 rows. Held, the march's answer
 * moves with the wind's speed alone and the loop settles in eleven laps. A cell's zonal flux is its
 * speed along its sweep, so as its wind turns against the sweep that flux falls to nothing before
 * the turn and not after it (docs/DESIGN_LEDGER.md, A1-6b).
 *
 * # The sinks
 *
 * One family, each a rate the column's own state sets, charged in turn on what the one before
 * left:
 * - **The column's rain** at its relative humidity `r = W / W_s`, Bretherton, Peters and Back's
 *   (2004) relation over the tropical oceans, `P = exp(11.4 (r - 0.522))` mm a day for monthly
 *   means ([columnRainMmPerDay]). It is the rate that sets the atmosphere's turnover: nothing
 *   here states a lifetime, and the one the world ends with is diagnosed (`RainAgainstEarthTest`).
 * - **Condensate.** Rising air condenses at Smith and Barstad's (2004) source, `C_w w`, on the
 *   saturated share of the column (its water over what it holds when its surface air saturates,
 *   [HOLDABLE_SHARE]); what the column cannot hold at all condenses too. The rise `w` is the
 *   atmosphere's own vertical motion at the boundary layer's top ([Season.largeScaleAscentMps]) and
 *   the wind's climb over the ground, summed before anything condenses, so a climb inside a large
 *   descent is first spent against it. The cloud turns to falling hydrometeors and falls out over
 *   their two delays. `C_w` is thermodynamics ([ColumnWater.upliftCondensationKgPerM3]), so neither
 *   the climb's rain nor the ascent's has a strength of its own to set.
 * - **Convergence**, a closure switched off by default (`ClimateConfig.convergenceRain`).
 *
 * **Descent** warms the air at the same `C_w |w|` and takes back water at that rate: first the
 * cloud it carries, then the column's rain as it falls, never past what the column holds before
 * its surface air saturates. That is the subtropics' dryness where the atmosphere sinks, from the
 * solved subsidence and from nothing else. The marine inversion's lid slows the column's rain, the
 * cloud's conversion to rain and the convergence closure on the land under it. None of them
 * deletes water: what they hold back is carried on.
 *
 * **What condenses** is counted apart from what falls ([Result.julyHalfCondensationMm]): the
 * column's rain and the convergence closure's, which leave the vapor where they fall, and the
 * cloud's growth less its evaporation. The cloud is carried, so its rain falls downwind of where it
 * condensed; the latent heat is released where it condenses, and that is what the atmosphere reads.
 *
 * # The sources
 *
 * The open sea evaporates by the bulk formula against surface air at the marine boundary layer's
 * own relative humidity ([MARINE_RELATIVE_HUMIDITY]), not the column's, at the scalar mean wind
 * the turbulence feels ([Season.scalarWindAt10mMps]), the mean wind and the weather's gusts
 * together, which does not fall to nothing where the mean wind reverses. The ground gives
 * back Budyko's share of its own year of rain against its potential evapotranspiration.
 *
 * # One season at a time, coupled through the year's rain
 *
 * Each half-year is marched on its own wind and temperatures. With a [Coupling] the atmosphere is
 * solved again between laps from each half's condensation, and the laps run until its heating has
 * settled too. The ground's return couples the halves:
 * it is Budyko's share of the cell's **annual** rain, after the blur, against the annual
 * potential evaporation, which is what the rivers and lakes read too. The laps run both seasons
 * in step and update the return between laps until the year's rain stops moving
 * ([CONVERGED_SHARE]); the last lap's rain and potential are then the ones the return was set
 * from, so the surface budget, rain equals the ground's return plus the runoff, closes cell by
 * cell.
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
     * The relative humidity of the air at the sea's surface, which the bulk formula evaporates
     * against: Dai's (2006) 75 to 80 percent over most of the oceans in every season, from ship
     * observations, with small variations in space and from year to year; its middle.
     */
    const val MARINE_RELATIVE_HUMIDITY = 0.775

    /**
     * Earth's mean rain, millimeters a day: Trenberth and others' (2007) 486.9 thousand km³ a year
     * over the planet's 510.1 million km², 954 mm a year.
     */
    private const val EARTH_MEAN_RAIN_MM_PER_DAY = 486.9e3 / 510.1e6 * 1.0e6 / 365.25

    /**
     * The share of its saturated water a column holds when the air at its surface saturates,
     * which is where lifting it begins to condense: the column's relative humidity over its
     * surface air's, both of them Earth's. Bretherton, Peters and Back's relation puts the column at
     * 0.606 of its saturated water at Earth's mean rain of 2.61 mm a day, and Dai's surface air
     * stands at [MARINE_RELATIVE_HUMIDITY], so a column holds 0.78 of its saturated water at
     * surface saturation. Water vapor thins upward faster than its saturated value does, over a
     * scale height near 2 km (a shipboard survey of 1984 read 1.9 to 2.5 over the Mediterranean, the
     * Indian Ocean and the Red Sea, as Otarola and others 2011 report it) against the saturated column's 2.6 at 15 C, which is the same ratio within that
     * spread.
     */
    val HOLDABLE_SHARE = (COLUMN_RAIN_HUMIDITY_OFFSET +
        kotlin.math.ln(EARTH_MEAN_RAIN_MM_PER_DAY) / COLUMN_RAIN_PER_HUMIDITY) / MARINE_RELATIVE_HUMIDITY

    /**
     * Smith and Barstad's (2004) two delays in the orographic rain, seconds: cloud water turning
     * into hydrometeors, and hydrometeors falling out. A thousand each in their worked example;
     * Smith and others (2005) read 500 to 5,000 from the Oregon Cascades' rain and isotopes.
     */
    const val CLOUD_CONVERSION_SECONDS = 1_000.0
    const val FALLOUT_SECONDS = 1_000.0

    /**
     * The most laps of both seasons round the planet. The first starts from air at four fifths of
     * its saturated column over dry ground and leaves a year's rain for the second's ground to
     * give back; each later one carries the return a lap further toward the year it belongs to,
     * until the year's land rain moves by less than [CONVERGED_SHARE], and with a [Coupling] until
     * the atmosphere's heating has settled too.
     *
     * Forty, a ceiling and not a count: the coupled loop settles in 13 to 18 laps on the standard
     * worlds at 512 and 1,024 rows, and the plain iteration it is accelerated from in 12 to 29, or
     * not within forty on one world in four (docs/DESIGN_LEDGER.md, A1-5). A loop that reaches the ceiling has a feedback at or
     * over one, which more laps would not settle; it stops with the last lap's rain, and
     * `AtmosphereCouplingTest` holds the standard worlds short of it.
     */
    const val MAX_LAPS = 40

    /**
     * When the year's rain has stopped moving: the land's mean change of annual rain from one lap
     * to the next, cell by cell, under six thousandths of its mean rain, the twice-standard-error
     * of GPCP's global rain over 17 years (2.9 of 486.9 thousand km³; Trenberth and others 2007),
     * which `MoistureClosureTest` holds the march's last storage change to as well. A year nearer
     * its fixed point than that is nearer than Earth's own rain is known.
     */
    const val CONVERGED_SHARE = 2.9 / 486.9

    /** The fewest laps run before the year's rain is asked whether it has settled. */
    private const val MIN_LAPS = 3

    /** Seconds in a year: 365.25 days. */
    const val SECONDS_PER_YEAR = 3.15576e7

    /** Seconds in a day. */
    private const val SECONDS_PER_DAY = 86_400.0

    /**
     * The share of its saturated column the first lap's air starts at. A starting guess and no
     * more: the laps wash it out.
     */
    private const val INITIAL_HUMIDITY = 0.8

    /** What a season's march reads, every per-cell array row-major on the world's grid. */
    class Season(
        /** The half-year's air temperature, degrees Celsius. */
        val airTemperatureC: FloatArray,
        /** The water's temperature at sea cells, current and all; ignored on land. */
        val seaSurfaceC: FloatArray,
        val seaIce: BooleanArray,
        /** The zonal wind, belts and pressure departure together, meters a second, positive east. */
        val eastwardMps: FloatArray,
        /** The meridional wind, meters a second, positive toward the south (down the map). */
        val southwardMps: FloatArray,
        /**
         * The wind's mean speed at 10 m through every gust and calm, meters a second, which the
         * sea's evaporation reads ([BoundaryLayer.scalarWindAt10mMps]); null for the belts' control,
         * which reads [TRANSPORT_SPEED_MPS].
         */
        val scalarWindAt10mMps: FloatArray? = null,
        /** The wind at 2 m over FAO-56's grass, meters a second; null for its 2 m/s station mean. */
        val windAt2mMps: FloatArray? = null,
        /**
         * The atmosphere's vertical velocity at the boundary layer's top, meters a second, positive
         * up ([BoundaryLayer.groundAscentMps]); null for the belts' control, which has none.
         */
        val largeScaleAscentMps: FloatArray?,
        /** The marine inversion's hold on each land cell's rain, 0..1, or null when it is off. */
        val inversionSuppression: FloatArray?,
        /** The half-year's mean sun at the top of the air, per row, MJ m⁻² day⁻¹. */
        val extraterrestrialOfRow: DoubleArray
    )

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
        val blurSigmaKm: Double
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
        /** The land's mean change of annual rain after each lap, as a share of its mean rain ([CONVERGED_SHARE]). */
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
     * A half's water in flight at the end of a lap, which the next lap starts from: the bank at every
     * cell, kilograms a square meter of column water in the march's own units (row-major), which
     * holds what the westward sweep's faces moved between rows; and each row's parcel arriving at the
     * map's first column in each sweep, vapor, cloud and falling water.
     *
     * Started cold, the first lap marches with an empty bank and its rows exchange nothing they did
     * not exchange within the lap, so it condenses a quarter differently from the settled lap
     * whatever the heating it runs under (docs/DESIGN_LEDGER.md, A1-6a). The land-origin parts are
     * not carried: they start at none, as a cold march's do, and only the recycling's share reads them.
     */
    class WaterInFlight(
        val bank: FloatArray,
        val eastwardVapor: DoubleArray,
        val eastwardCloud: DoubleArray,
        val eastwardFalling: DoubleArray,
        val westwardVapor: DoubleArray,
        val westwardCloud: DoubleArray,
        val westwardFalling: DoubleArray
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
        /**
         * The two seasons the first lap reads, April to September about July and October to March
         * about January: built inside the march and held by no frame of it or its caller once the
         * marches are set up, so a coupled run's first seasons are let go after its first lap.
         */
        /** The ground's return to start from, from an earlier march of this world's grid; dry ground when none. */
        start: StartState? = null,
        firstSeasons: () -> Pair<Season, Season>
    ): Result {
        val config = inputs.config
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val grid = Grid(config)
        val seasons = startMarches(inputs, grid, keepCells = ledger != null, firstSeasons)
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
            // The two seasons share nothing inside a lap but the ground's return, which was set
            // before it began, so they march side by side.
            parallelChunks(0, 2) { first, last ->
                for (season in first until last) seasons[season].lap(laps[season])
            }
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
            val change = landChangeShare(inputs.isLand, annualRain.data, previousRain, grid)
            ledger?.settling?.add(change)
            landRainChanges.add(change)
            var lapElapsed = lapStarted.elapsedNow()
            val heatingSettled = coupling?.settledAfter(july.condensationRowMajor(), january.condensationRowMajor()) ?: true
            val settled = lap >= MIN_LAPS && change < CONVERGED_SHARE && heatingSettled
            if (settled || lap >= MAX_LAPS) {
                lapMillis.add(lapElapsed.inWholeMicroseconds / 1_000.0)
                break
            }
            if (lap == 1) {
                july.holdSweeps()
                january.holdSweeps()
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
                    val returning = config.climate.groundReturn
                    july.setGroundReturn(cell, annualRain.data[cell], annualPotential[cell], returning)
                    january.setGroundReturn(cell, annualRain.data[cell], annualPotential[cell], returning)
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

    /**
     * How far the land's annual rain moved between two laps: the mean of each land cell's change
     * over the land's mean rain, both by area on the sphere.
     */
    private fun landChangeShare(isLand: BooleanArray, rain: FloatArray, previous: FloatArray, grid: Grid): Double {
        var area = 0.0
        var total = 0.0
        var moved = 0.0
        for (cell in rain.indices) {
            if (!isLand[cell]) continue
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
     * Peters and Back's (2004) `exp(11.4 (r - 0.522))` less its value at a dry column, 0.0026 mm
     * a day, so a column with no water rains none however long the wind leaves it over a cell.
     */
    fun columnRainMmPerDay(relativeHumidity: Double): Double =
        exp(COLUMN_RAIN_PER_HUMIDITY * (relativeHumidity - COLUMN_RAIN_HUMIDITY_OFFSET)) -
            exp(-COLUMN_RAIN_PER_HUMIDITY * COLUMN_RAIN_HUMIDITY_OFFSET)

    /**
     * The column water left when a column of [startMm] against a saturated column of
     * [saturatedMm] rains at [columnRainMmPerDay] for [seconds], slowed by [suppression]: the
     * backward step `W + dt S P(W / W_s) = W_0`, solved by Newton's method. The left side rises
     * and bends upward in `W`, so Newton from `W_0` falls to the root without overshooting it, and
     * the root lies between zero and `W_0`: the column neither rains more than it holds nor goes
     * below dry.
     */
    fun columnAfterRainMm(startMm: Double, saturatedMm: Double, seconds: Double, suppression: Double): Double {
        if (startMm <= 0.0 || saturatedMm <= 0.0 || seconds <= 0.0 || suppression <= 0.0) return startMm.coerceAtLeast(0.0)
        val a = COLUMN_RAIN_PER_HUMIDITY / saturatedMm
        val scaleMm = seconds / SECONDS_PER_DAY * suppression * exp(-COLUMN_RAIN_PER_HUMIDITY * COLUMN_RAIN_HUMIDITY_OFFSET)
        var water = startMm
        for (iteration in 0 until NEWTON_ITERATIONS) {
            val grown = exp(a * water)
            val excess = water + scaleMm * (grown - 1.0) - startMm
            val slope = 1.0 + scaleMm * a * grown
            val next = (water - excess / slope).coerceAtLeast(0.0)
            if (abs(next - water) <= NEWTON_TOLERANCE * startMm) return next
            water = next
        }
        return water
    }

    /** Newton's method's ceiling and its stopping step, as a share of the column. */
    private const val NEWTON_ITERATIONS = 60
    private const val NEWTON_TOLERANCE = 1.0e-12

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
     * and the same eddies carry the water. About 1.2e6 m²/s under the polar floor and 2.2e6 at
     * the storm track's 50 degrees on Earth.
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

    /** Standard gravity, the standard atmosphere's surface pressure and dry air's heat capacity. */
    private const val STANDARD_GRAVITY_MPS2 = 9.80665
    private const val SEA_LEVEL_PRESSURE_PA = 101_325.0
    private const val AIR_HEAT_CAPACITY_J_PER_KG_K = 1004.0

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
     * One season's march: its precomputed fields, column-major so a column's rows are adjacent
     * in memory, and its state between laps.
     */
    /** Both seasons' marches under [firstSeasons], July's then January's. */
    private fun startMarches(inputs: Inputs, grid: Grid, keepCells: Boolean, firstSeasons: () -> Pair<Season, Season>): Array<SeasonMarch> {
        val (julyHalf, januaryHalf) = firstSeasons()
        return arrayOf(SeasonMarch(inputs, julyHalf, grid, keepCells), SeasonMarch(inputs, januaryHalf, grid, keepCells))
    }

    private class SeasonMarch(
        val inputs: Inputs,
        season: Season,
        val grid: Grid,
        keepCells: Boolean
    ) {
        val cellsAcross = grid.cellsAcross
        val cellsDown = grid.cellsDown
        val cellCount = cellsAcross * cellsDown
        val config = inputs.config

        // What the ledger and the last potentials read of the season's air. The rest of a season,
        // its wind and its ascent, is let go once each lap's fields are built from it, so a
        // coupled lap's next seasons are not built beside the last ones.
        private var airTemperatureC: FloatArray = season.airTemperatureC
        private var seaSurfaceC: FloatArray = season.seaSurfaceC
        private var windAt2mMps: FloatArray? = season.windAt2mMps
        private var extraterrestrialOfRow: DoubleArray = season.extraterrestrialOfRow

        // Column-major: cell (row, column) is column * cellsDown + row.
        val direction = ByteArray(cellCount)

        /** Whether [direction] is held at the first lap's (see the class comment). */
        private var sweepsHeld = false

        /** Holds every cell in the sweep it marched in this lap, through the laps after it. */
        fun holdSweeps() {
            sweepsHeld = true
        }
        val surface = ByteArray(cellCount)
        val saturatedMm = FloatArray(cellCount)

        /** The sea's evaporation, kilograms per square meter per second, at open-sea cells. */
        val seaEvaporationRate = FloatArray(cellCount)

        /**
         * How much more column water a unit of parcel is at each cell than at the reference speed:
         * `U / |u|`. The crossing time is the reference's times this.
         */
        val stretch = FloatArray(cellCount)

        /**
         * Smith and Barstad's (2004) uplift source at each cell, kilograms per square meter per
         * second: `C_w w`, the condensation of saturated air rising at `w`, the atmosphere's
         * ascent plus the wind's climb over the ground, `u dh/dx + v dh/dy`; negative where it
         * descends.
         */
        val upliftCondensationRate = FloatArray(cellCount)

        /** The marine inversion's lid, 0..1: what slows every sink on the land under it. */
        val suppression = FloatArray(cellCount)
        val convergenceShare = FloatArray(cellCount)
        val faceShare = FloatArray(cellCount)

        /** The ground's return this season, millimeters a second, land cells only. */
        val groundReturnRate = FloatArray(cellCount)

        /** The year's rain and potential the ground's return was last set from, row-major. */
        val returnRain = FloatArray(cellCount)
        val returnPotential = FloatArray(cellCount)

        // This lap's results, column-major.
        val rainMm = FloatArray(cellCount)
        val landRainMm = FloatArray(cellCount)
        val condensationMm = FloatArray(cellCount)
        val humidity = FloatArray(cellCount)
        val sourceMm = FloatArray(cellCount)

        /** The parcel each cell sent on, column-major, for the westward sweep's faces to read. */
        val sentParcel = DoubleArray(cellCount)
        val sentParcelLand = DoubleArray(cellCount)

        // Potential evaporation from the last lap's air, row-major, and its terms in the air's
        // humidity at each land cell, built once.
        val potentialMm = FloatArray(cellCount)
        val openWaterMm = FloatArray(cellCount)
        val potentialConstant = FloatArray(cellCount)
        val potentialPerRootHumidity = FloatArray(cellCount)
        val potentialPerHumidity = FloatArray(cellCount)

        // The march's state: per row and sweep, the parcel of vapor, of cloud and of falling
        // hydrometeors, each with its land-origin part; and the bank of each cell.
        val eastParcel = RowState(cellsDown)
        val westParcel = RowState(cellsDown)
        val bank = DoubleArray(cellCount)
        val bankLand = DoubleArray(cellCount)

        /** The cloud and the falling water banked where the wind turns, with their land-origin parts, parcel units. */
        val bankCloud = DoubleArray(cellCount)
        val bankCloudLand = DoubleArray(cellCount)
        val bankFalling = DoubleArray(cellCount)
        val bankFallingLand = DoubleArray(cellCount)

        // Working space for one column's solve.
        val role = ByteArray(cellsDown)
        val water = DoubleArray(cellsDown)
        val waterLand = DoubleArray(cellsDown)
        val lower = DoubleArray(cellsDown)
        val diagonal = DoubleArray(cellsDown)
        val upper = DoubleArray(cellsDown)
        val sweepUpper = DoubleArray(cellsDown)
        val sweepSolved = DoubleArray(cellsDown)
        val sweepSolvedLand = DoubleArray(cellsDown)
        val acrossBefore = DoubleArray(cellsDown)
        val acrossBeforeLand = DoubleArray(cellsDown)

        /**
         * Each marched row's outflow by the mean wind, per unit of its solved parcel, through its
         * face with a row of the other sweep above or below: what [solveFaces] hands to the bank
         * there.
         */
        val sentAbove = DoubleArray(cellsDown)
        val sentBelow = DoubleArray(cellsDown)

        val cells: MoistureLedger.Cells? =
            if (keepCells) MoistureLedger.Cells(cellsAcross, cellsDown) else null

        init {
            precompute(season)
            precomputePotentialTerms()
            for (row in 0 until cellsDown) {
                val east = index(row, 0)
                val west = index(row, cellsAcross - 1)
                eastParcel.vapor[row] = if (direction[east] > 0) INITIAL_HUMIDITY * saturatedMm[east] / stretch[east] else 0.0
                westParcel.vapor[row] = if (direction[west] < 0) INITIAL_HUMIDITY * saturatedMm[west] / stretch[west] else 0.0
            }
        }

        /**
         * Marches the next lap under [next], the same half-year's air under a newly solved
         * atmosphere: every field the wind and the vertical motion set is built again, and the
         * water in flight, the bank and the ground's return are kept.
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
            for (row in 0 until cellsDown) {
                bankParcelIfTurned(eastParcel, row, index(row, 0), 1)
                bankParcelIfTurned(westParcel, row, index(row, cellsAcross - 1), -1)
            }
        }

        private fun bankParcelIfTurned(parcel: RowState, row: Int, first: Int, sweep: Int) {
            if (direction[first].toInt() == sweep) return
            bank[first] += parcel.vapor[row] + parcel.cloud[row] + parcel.falling[row]
            bankLand[first] += parcel.vaporLand[row] + parcel.cloudLand[row] + parcel.fallingLand[row]
            parcel.clear(row)
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

        private fun index(row: Int, column: Int) = column * cellsDown + row

        /** The wind at 2 m over [cell] (row-major), meters a second. */
        private fun windAt2m(cell: Int): Double =
            windAt2mMps?.let { it[cell].toDouble() } ?: SurfaceEvaporation.LAND_WIND_AT_2_M_MPS

        private fun precompute(season: Season) {
            val climate = config.climate
            val referenceMassFlux = transferMassFlux(TRANSPORT_SPEED_MPS)
            val slowest = TRANSPORT_SPEED_MPS * SLOWEST_SPEED_SHARE
            parallelChunks(0, cellsAcross) { startColumn, endColumn ->
                for (column in startColumn until endColumn) {
                    for (row in 0 until cellsDown) {
                        val cell = row * cellsAcross + column
                        val here = index(row, column)
                        val eastward = season.eastwardMps[cell].toDouble()
                        if (!sweepsHeld) direction[here] = if (eastward >= 0.0) 1 else -1
                        // The speed along the cell's sweep, its whole speed until the sweeps are held.
                        stretch[here] = (TRANSPORT_SPEED_MPS / maxOf(eastward * direction[here], slowest)).toFloat()
                        surface[here] = when {
                            inputs.isLand[cell] -> LAND
                            season.seaIce[cell] -> SEA_ICE
                            else -> OPEN_SEA
                        }
                        val airC = season.airTemperatureC[cell].toDouble()
                        saturatedMm[here] = ColumnWater.saturatedColumnMm(airC).toFloat()
                        if (surface[here] == OPEN_SEA) {
                            val surfaceHumidity = SurfaceEvaporation.seaSurfaceHumidity(season.seaSurfaceC[cell].toDouble())
                            val airHumidity = MARINE_RELATIVE_HUMIDITY * ColumnWater.specificHumidity(
                                ColumnWater.saturationVaporPressureKpa(airC), ColumnWater.SEA_LEVEL_PRESSURE_KPA
                            )
                            val massFlux = season.scalarWindAt10mMps?.let { transferMassFlux(it[cell].toDouble()) } ?: referenceMassFlux
                            seaEvaporationRate[here] = SurfaceEvaporation.bulkEvaporationKgPerM2S(
                                surfaceHumidity, airHumidity, massFlux
                            ).toFloat()
                        }
                        suppression[here] = (if (inputs.isLand[cell]) lidFactor(cell, season.inversionSuppression) else 1.0).toFloat()
                    }
                }
            }
            // Faces: the meridional wind at the face below each row, the mean of the two rows'.
            parallelChunks(0, cellsAcross) { startColumn, endColumn ->
                for (column in startColumn until endColumn) {
                    for (row in 0 until cellsDown - 1) {
                        val above = season.southwardMps[row * cellsAcross + column]
                        val below = season.southwardMps[(row + 1) * cellsAcross + column]
                        faceShare[index(row, column)] = ((above + below) * 0.5 * grid.faceSharePerMps[row]).toFloat()
                    }
                }
            }
            // The rise: the atmosphere's ascent at the layer's top, plus on land the ground's slope
            // along the wind (centered differences on the ground's own lengths), summed before it
            // is turned into condensation by the saturated air's yield per meter of rise.
            val cellHeightM = config.cellHeightKm * METERS_PER_KM
            val lapseRate = climate.lapseRateCPerKm.toDouble()
            val ascent = season.largeScaleAscentMps
            parallelChunks(0, cellsDown) { startRow, endRow ->
                for (row in startRow until endRow) {
                    val cellWidthM = config.cellWidthKm * METERS_PER_KM * grid.cosRow[row]
                    val north = if (row > 0) row - 1 else row
                    val south = if (row < cellsDown - 1) row + 1 else row
                    for (column in 0 until cellsAcross) {
                        val cell = row * cellsAcross + column
                        var upward = ascent?.get(cell)?.toDouble() ?: 0.0
                        if (inputs.isLand[cell]) {
                            val east = row * cellsAcross + (column + 1) % cellsAcross
                            val west = row * cellsAcross + (column + cellsAcross - 1) % cellsAcross
                            val slopeEast = (inputs.elevationM[east] - inputs.elevationM[west]) / (2.0 * cellWidthM)
                            val slopeSouth = (inputs.elevationM[south * cellsAcross + column] -
                                inputs.elevationM[north * cellsAcross + column]) / ((south - north) * cellHeightM)
                            upward += season.eastwardMps[cell] * slopeEast + season.southwardMps[cell] * slopeSouth
                        }
                        upliftCondensationRate[index(row, column)] = if (upward == 0.0) 0f else (ColumnWater.upliftCondensationKgPerM3(
                            season.airTemperatureC[cell].toDouble(), lapseRate
                        ) * upward).toFloat()
                    }
                }
            }
            if (!config.climate.convergenceRain) return
            // The air's own budget per column, when the convergence closure is on: what flows in
            // through the four sides less what flows out, as shares of the column.
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
                        convergenceShare[here] = (inflow - outflow).coerceAtLeast(0.0).toFloat()
                    }
                }
            }
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
         * Sets this season's ground return from the year's rain and potential, the season taking
         * the year's return in proportion to its own potential, and keeps the year it was set
         * from for the rivers. With the return switched off the year is still kept.
         */
        fun setGroundReturn(cell: Int, annualRainMm: Float, annualPotentialMm: Float, returning: Boolean) {
            val here = index(cell / cellsAcross, cell % cellsAcross)
            val annualReturnMm = if (returning) groundReturnMm(annualRainMm, annualPotentialMm) else 0f
            val shareOfYear = if (annualPotentialMm > 0f) potentialMm[cell] / annualPotentialMm else 0.5f
            groundReturnRate[here] = (annualReturnMm * shareOfYear / SECONDS_PER_YEAR).toFloat()
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
         * Starts this season's water in flight from [start], a march's end on the same grid: the bank
         * at every cell and each row's arriving parcels, their land-origin parts at none.
         */
        fun startWaterFrom(start: WaterInFlight) {
            for (cell in 0 until cellCount) bank[index(cell / cellsAcross, cell % cellsAcross)] = start.bank[cell].toDouble()
            bankCloud.fill(0.0)
            bankCloudLand.fill(0.0)
            bankFalling.fill(0.0)
            bankFallingLand.fill(0.0)
            for (row in 0 until cellsDown) {
                eastParcel.clear(row)
                westParcel.clear(row)
                eastParcel.vapor[row] = start.eastwardVapor[row]
                eastParcel.cloud[row] = start.eastwardCloud[row]
                eastParcel.falling[row] = start.eastwardFalling[row]
                westParcel.vapor[row] = start.westwardVapor[row]
                westParcel.cloud[row] = start.westwardCloud[row]
                westParcel.falling[row] = start.westwardFalling[row]
                // A parcel this world's wind does not march in from the first column waits in the
                // bank there, as between two laps ([reseason]).
                bankParcelIfTurned(eastParcel, row, index(row, 0), 1)
                bankParcelIfTurned(westParcel, row, index(row, cellsAcross - 1), -1)
            }
            bankLand.fill(0.0)
        }

        /** This season's water in flight now, for a later march to start from ([WaterInFlight]). */
        fun waterInFlight(): WaterInFlight = WaterInFlight(
            FloatArray(cellCount) {
                val here = index(it / cellsAcross, it % cellsAcross)
                (bank[here] + bankCloud[here] + bankFalling[here]).toFloat()
            },
            eastParcel.vapor.copyOf(), eastParcel.cloud.copyOf(), eastParcel.falling.copyOf(),
            westParcel.vapor.copyOf(), westParcel.cloud.copyOf(), westParcel.falling.copyOf()
        )

        /** The ground's return at [cell] (row-major), millimeters a second. */
        fun groundReturnRateRowMajor(cell: Int): Float = groundReturnRate[index(cell / cellsAcross, cell % cellsAcross)]

        fun lap(entry: MoistureLedger.Lap?) {
            if (entry != null) {
                entry.storageAtStart = storage()
                entry.landStorageAtStart = landStorage()
            }
            for (step in 0 until cellsAcross) sweepColumn(step, 1, entry)
            for (step in 0 until cellsAcross) sweepColumn(cellsAcross - 1 - step, -1, entry)
            if (entry != null) {
                entry.storageAtEnd = storage()
                entry.landStorageAtEnd = landStorage()
                entry.bankAtEnd = bank.sum()
            }
        }

        private fun storage(): Double = eastParcel.total() + westParcel.total() + bank.sum() + bankCloud.sum() + bankFalling.sum()
        private fun landStorage(): Double =
            eastParcel.totalLand() + westParcel.totalLand() + bankLand.sum() + bankCloudLand.sum() + bankFallingLand.sum()

        /**
         * The faces of one column, solved: every row this sweep marches, and in the westward
         * sweep every eastward row beside one of them, as one tridiagonal system in the parcels
         * (see the class comment). [water] holds the parcels going in and comes out with them
         * solved; [waterLand] the same for the land-origin part, which the same matrix moves.
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
            for (row in 0 until cellsDown) {
                val denominator = diagonal[row] - lower[row] * previousUpper
                sweepUpper[row] = upper[row] / denominator
                sweepSolved[row] = (water[row] - lower[row] * previousSolved) / denominator
                sweepSolvedLand[row] = (waterLand[row] - lower[row] * previousSolvedLand) / denominator
                previousUpper = sweepUpper[row]
                previousSolved = sweepSolved[row]
                previousSolvedLand = sweepSolvedLand[row]
            }
            water[cellsDown - 1] = sweepSolved[cellsDown - 1]
            waterLand[cellsDown - 1] = sweepSolvedLand[cellsDown - 1]
            for (row in cellsDown - 2 downTo 0) {
                water[row] = sweepSolved[row] - sweepUpper[row] * water[row + 1]
                waterLand[row] = sweepSolvedLand[row] - sweepUpper[row] * waterLand[row + 1]
            }
        }

        private fun sweepColumn(column: Int, sweep: Int, entry: MoistureLedger.Lap?) {
            val base = column * cellsDown
            val parcel = if (sweep > 0) eastParcel else westParcel

            // What arrives: the row's parcel from the column behind and whatever was banked here;
            // and, in the westward sweep, the eastward rows beside them as they left this column.
            for (row in 0 until cellsDown) {
                val here = base + row
                if (direction[here].toInt() == sweep) {
                    role[row] = MARCHED
                    // A bank the westward sweep's faces drew on can hold less than nothing; what
                    // the parcel cannot pay of it waits there for the next lap's.
                    parcel.cloud[row] += bankCloud[here]
                    parcel.cloudLand[row] += bankCloudLand[here]
                    parcel.falling[row] += bankFalling[here]
                    parcel.fallingLand[row] += bankFallingLand[here]
                    bankCloud[here] = 0.0
                    bankCloudLand[here] = 0.0
                    bankFalling[here] = 0.0
                    bankFallingLand[here] = 0.0
                    val arriving = parcel.vapor[row] + bank[here]
                    val arrivingLand = parcel.vaporLand[row] + bankLand[here]
                    water[row] = arriving.coerceAtLeast(0.0)
                    waterLand[row] = arrivingLand.coerceIn(0.0, water[row])
                    bank[here] = arriving - water[row]
                    bankLand[here] = arrivingLand - waterLand[row]
                } else {
                    role[row] = OUTSIDE
                    water[row] = 0.0
                    waterLand[row] = 0.0
                }
            }
            if (sweep < 0) {
                for (row in 0 until cellsDown) {
                    if (role[row] != OUTSIDE) continue
                    val besideMarched = (row > 0 && role[row - 1] == MARCHED) ||
                        (row < cellsDown - 1 && role[row + 1] == MARCHED)
                    if (!besideMarched) continue
                    role[row] = ACROSS
                    // What the eastward row will bring here, net of what it already owes here.
                    val bringing = (sentParcel[base + row] + bank[base + row]).coerceAtLeast(0.0)
                    water[row] = bringing
                    waterLand[row] = (sentParcelLand[base + row] + bankLand[base + row]).coerceIn(0.0, bringing)
                    acrossBefore[row] = water[row]
                    acrossBeforeLand[row] = waterLand[row]
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
                }
                if (sentBelow[row] > 0.0) {
                    bank[base + row + 1] += sentBelow[row] * water[row]
                    bankLand[base + row + 1] += sentBelow[row] * waterLand[row]
                }
            }
            // What the solve's mixing gave or took from the eastward rows waits in their bank.
            if (sweep < 0) {
                for (row in 0 until cellsDown) {
                    if (role[row] != ACROSS) continue
                    bank[base + row] += water[row] - acrossBefore[row]
                    bankLand[base + row] += waterLand[row] - acrossBeforeLand[row]
                }
            }

            // The column's physics, row by row, and the water it sends on.
            val downwindBase = ((column + sweep + cellsAcross) % cellsAcross) * cellsDown
            for (row in 0 until cellsDown) {
                if (role[row] != MARCHED) {
                    parcel.clear(row)
                    continue
                }
                marchCell(base, row, sweep, parcel, downwindBase, entry)
            }
        }

        private fun marchCell(base: Int, row: Int, sweep: Int, parcel: RowState, downwindBase: Int, entry: MoistureLedger.Lap?) {
            val here = base + row
            val kind = surface[here]
            val surfaceIndex = kind.toInt()
            val toColumnWater = stretch[here].toDouble()
            val seconds = grid.secondsPerColumn[row] * toColumnWater
            val saturated = saturatedMm[here].toDouble()
            val slowing = suppression[here].toDouble()

            // Everything below is in the cell's own column water, the parcel times the stretch.
            var vapor = water[row] * toColumnWater
            var vaporLand = waterLand[row] * toColumnWater
            var cloud = parcel.cloud[row] * toColumnWater
            var cloudLand = parcel.cloudLand[row] * toColumnWater
            var falling = parcel.falling[row] * toColumnWater
            var fallingLand = parcel.fallingLand[row] * toColumnWater
            if (entry != null) {
                if (vapor < -NEGATIVE_SLACK_MM) entry.negativeParcels++
                val slack = TRACER_SLACK * (abs(vapor) + 1.0)
                if (vaporLand < -slack || vaporLand > vapor + slack) entry.tracerOutOfBounds++
            }
            vapor = vapor.coerceAtLeast(0.0)
            vaporLand = vaporLand.coerceIn(0.0, vapor)

            // The sources: the sea by the bulk formula, the ground by its share of the year's return.
            var added = 0.0
            if (kind == OPEN_SEA) {
                added = seaEvaporationRate[here].toDouble() * seconds
                if (entry != null) entry.seaEvaporation += added / toColumnWater
            } else if (kind == LAND) {
                added = groundReturnRate[here].toDouble() * seconds
                vaporLand += added
                if (entry != null) entry.groundReturn += added / toColumnWater
            }
            vapor += added

            // What the column cannot hold condenses to cloud. A column holds [HOLDABLE_SHARE] of
            // its saturated water before its surface air saturates.
            val holdable = HOLDABLE_SHARE * saturated
            val uplift = upliftCondensationRate[here].toDouble()
            // What the descent may still take back after the cloud, column water this crossing.
            var descentLeft = 0.0
            // Vapor turned to condensate here less condensate turned back, column water.
            var condensedHere = 0.0
            if (vapor > holdable) {
                val condensed = vapor - holdable
                val condensedLand = condensed * (vaporLand / vapor)
                cloud += condensed
                cloudLand += condensedLand
                vapor = holdable
                vaporLand -= condensedLand
                condensedHere += condensed
                if (entry != null) entry.condensed += condensed / toColumnWater
            }
            if (uplift > 0.0 && vapor > 0.0 && holdable > 0.0) {
                // Air carried up the ground condenses at Smith and Barstad's rate where it is
                // saturated, and the column's saturated share is its water over what it holds at
                // surface saturation: the climb takes `C_w w W / W_hold` a second, which never
                // empties the column however long the climb.
                val condensed = vapor * (1.0 - exp(-uplift / holdable * seconds))
                val condensedLand = condensed * (vaporLand / vapor)
                cloud += condensed
                cloudLand += condensedLand
                vapor -= condensed
                vaporLand -= condensedLand
                condensedHere += condensed
                if (entry != null) entry.condensed += condensed / toColumnWater
            } else if (uplift < 0.0) {
                // Descending air warms and takes back water at the same rate: the cloud it carries
                // first, then (below) the column's rain as it falls through it.
                descentLeft = -uplift * seconds
                if (cloud > 0.0) {
                    val evaporated = minOf(cloud, descentLeft, (holdable - vapor).coerceAtLeast(0.0))
                    val evaporatedLand = evaporated * (cloudLand / cloud)
                    cloud -= evaporated
                    cloudLand -= evaporatedLand
                    vapor += evaporated
                    vaporLand += evaporatedLand
                    descentLeft -= evaporated
                    condensedHere -= evaporated
                    if (entry != null) entry.reevaporated += evaporated / toColumnWater
                }
            }

            // The convergence closure, when switched on.
            var convergenceRain = 0.0
            val convergence = convergenceShare[here].toDouble() * slowing * toColumnWater
            if (convergence > 0.0 && vapor > 0.0) {
                convergenceRain = vapor * (1.0 - exp(-convergence))
            }
            val vaporShareLand = if (vapor > 0.0) (vaporLand / vapor).coerceIn(0.0, 1.0) else 0.0
            vapor -= convergenceRain

            // The column's own rain at its relative humidity, less what the descent takes back as it
            // falls, up to what the column holds before its surface air saturates.
            val afterRain = columnAfterRainMm(vapor, saturated, seconds, slowing)
            var columnRain = vapor - afterRain
            vapor = afterRain
            if (descentLeft > 0.0 && columnRain > 0.0) {
                val takenBack = minOf(columnRain, descentLeft, (holdable - vapor).coerceAtLeast(0.0))
                columnRain -= takenBack
                vapor += takenBack
                if (entry != null) entry.reevaporated += takenBack / toColumnWater
            }
            vaporLand = vapor * vaporShareLand
            condensedHere += columnRain + convergenceRain
            val vaporLandRained = (columnRain + convergenceRain) * vaporShareLand

            // The cloud turns to hydrometeors and they fall: Smith and Barstad's two delays in
            // series over the crossing, the first slowed by the descent. The condensate's
            // land-origin share is mixed once.
            val condensateBefore = cloud + falling
            val condensateShareLand = if (condensateBefore > 0.0) {
                ((cloudLand + fallingLand) / condensateBefore).coerceIn(0.0, 1.0)
            } else 0.0
            val conversion = slowing * config.climate.rainShadowScale / CLOUD_CONVERSION_SECONDS
            val fallout = 1.0 / FALLOUT_SECONDS
            val cloudLeft = exp(-conversion * seconds)
            val fallingLeft = exp(-fallout * seconds)
            val handedOn = twoDelayShare(conversion, fallout, seconds)
            val cloudAfter = cloud * cloudLeft
            val fallingAfter = falling * fallingLeft + cloud * handedOn
            val condensateRain = (condensateBefore - cloudAfter - fallingAfter).coerceAtLeast(0.0)
            val condensateLandRain = condensateRain * condensateShareLand
            cloud = cloudAfter
            falling = fallingAfter
            cloudLand = cloud * condensateShareLand
            fallingLand = falling * condensateShareLand

            val downwind = downwindBase + row
            val carriedOn = direction[downwind].toInt() == sweep

            val rainedCondensate = condensateRain
            val rainedTotal = columnRain + convergenceRain + rainedCondensate
            val landRained = vaporLandRained + condensateLandRain
            if (entry != null) {
                val perParcel = 1.0 / toColumnWater
                entry.rain[MoistureLedger.Sink.COLUMN.ordinal][surfaceIndex] += columnRain * perParcel
                entry.rain[MoistureLedger.Sink.CONVERGENCE.ordinal][surfaceIndex] += convergenceRain * perParcel
                entry.rain[MoistureLedger.Sink.CONDENSATE.ordinal][surfaceIndex] += rainedCondensate * perParcel
                entry.landOriginRain += landRained * perParcel
                if (!vapor.isFinite() || !cloud.isFinite() || !falling.isFinite() || !vaporLand.isFinite()) entry.nonFinite++
            }

            // Rates at the cell: a parcel's worth over the reference crossing time is a cell's
            // rain over its own, since the two differ by the stretch on both sides.
            val rate = SECONDS_PER_YEAR / seconds
            rainMm[here] = (rainedTotal * rate).toFloat()
            landRainMm[here] = (landRained * rate).toFloat()
            condensationMm[here] = (condensedHere * rate).toFloat()
            humidity[here] = if (saturated > 0.0) (vapor / saturated).toFloat() else 1f
            sourceMm[here] = (added * rate).toFloat()
            cells?.let { record ->
                val cell = row * cellsAcross + base / cellsDown
                record.rain.data[cell] = rainMm[here]
                record.columnWater.data[cell] = vapor.toFloat()
                record.saturatedColumn.data[cell] = saturated.toFloat()
                if (kind == OPEN_SEA) record.seaMinusAirC.data[cell] = seaSurfaceC[cell] - airTemperatureC[cell]
                if (kind == LAND) record.groundReturn.data[cell] = (added * rate).toFloat()
                if (kind == OPEN_SEA) record.seaEvaporation.data[cell] = (added * rate).toFloat()
                record.convergenceRain.data[cell] = (convergenceRain * rate).toFloat()
                record.condensateRain.data[cell] = (rainedCondensate * rate).toFloat()
            }

            // Back into parcels, and on downwind: to the next cell of this sweep, or banked where
            // the wind turns.
            val perParcel = 1.0 / toColumnWater
            val sent = vapor * perParcel
            val sentLand = vaporLand * perParcel
            sentParcel[here] = sent
            sentParcelLand[here] = sentLand
            if (carriedOn) {
                parcel.vapor[row] = sent
                parcel.vaporLand[row] = sentLand
                parcel.cloud[row] = cloud * perParcel
                parcel.cloudLand[row] = cloudLand * perParcel
                parcel.falling[row] = falling * perParcel
                parcel.fallingLand[row] = fallingLand * perParcel
            } else {
                // Where the wind turns, the cloud and the falling water go on in the other sweep
                // as the vapor does, and fall over their own delays there.
                bank[downwind] += sent
                bankLand[downwind] += sentLand
                bankCloud[downwind] += cloud * perParcel
                bankCloudLand[downwind] += cloudLand * perParcel
                bankFalling[downwind] += falling * perParcel
                bankFallingLand[downwind] += fallingLand * perParcel
                parcel.clear(row)
            }
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
                        val humidity = humidity[index(row, column)].toDouble()
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

        fun rainMmRowMajor(cell: Int): Float = rainMm[index(cell / cellsAcross, cell % cellsAcross)]

        /** This lap's net condensation, millimeters a year, row-major. */
        fun condensationRowMajor(): FloatArray = FloatArray(cellCount) { condensationMm[index(it / cellsAcross, it % cellsAcross)] }
        fun landRainMmRowMajor(cell: Int): Float = landRainMm[index(cell / cellsAcross, cell % cellsAcross)]
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
    }

    /**
     * What one row and sweep carries from column to column, in the march's parcels: vapor, cloud
     * and falling hydrometeors, each with its land-origin part.
     */
    private class RowState(rows: Int) {
        val vapor = DoubleArray(rows)
        val vaporLand = DoubleArray(rows)
        val cloud = DoubleArray(rows)
        val cloudLand = DoubleArray(rows)
        val falling = DoubleArray(rows)
        val fallingLand = DoubleArray(rows)

        fun clear(row: Int) {
            vapor[row] = 0.0; vaporLand[row] = 0.0
            cloud[row] = 0.0; cloudLand[row] = 0.0
            falling[row] = 0.0; fallingLand[row] = 0.0
        }

        fun total(): Double = vapor.sum() + cloud.sum() + falling.sum()
        fun totalLand(): Double = vaporLand.sum() + cloudLand.sum() + fallingLand.sum()
    }

    /**
     * The share of a cloud that has turned into hydrometeors and not yet fallen after [seconds],
     * converting at [conversionPerSecond] and falling at [falloutPerSecond]: the two-reservoir
     * chain's middle, `c (e^-ct - e^-ft) / (f - c)`, and its limit `c t e^-ct` where the two
     * rates meet. Exact over any crossing, so the delays filter the same on every grid.
     */
    private fun twoDelayShare(conversionPerSecond: Double, falloutPerSecond: Double, seconds: Double): Double {
        val difference = falloutPerSecond - conversionPerSecond
        val conversionLeft = exp(-conversionPerSecond * seconds)
        return if (abs(difference) * seconds < EQUAL_RATES) {
            conversionPerSecond * seconds * conversionLeft
        } else {
            conversionPerSecond * (conversionLeft - exp(-falloutPerSecond * seconds)) / difference
        }
    }

    /** Below this product of the rates' difference and the time, the two are taken as equal. */
    private const val EQUAL_RATES = 1.0e-6

    /**
     * How far the land-origin water may stand outside zero to the column's water before the ledger
     * counts it, as a share: a millionth, for the rounding of the doubles it is carried in.
     */
    private const val TRACER_SLACK = 1.0e-6

    /**
     * How far below zero a parcel may stand after transport before the ledger counts it negative,
     * in millimeters: a billionth, the rounding of the solve's doubles.
     */
    private const val NEGATIVE_SLACK_MM = 1.0e-9
}
