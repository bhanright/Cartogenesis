package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.abs
import kotlin.math.exp

/**
 * The three things the moisture march needs that are properties of the ground and the wind rather
 * than of the air mass itself: how fast a parcel empties over land, where the regional wind
 * gathers air and where it spreads it, and where a cold sea puts a lid on the air above it.
 *
 * Each constant here is a figure from the literature converted into the march's own units through
 * `WorldScale`, so it is a length or a rate on the ground and not a number per cell. The march
 * before this stage charged its rain per *cell* of travel, which meant a finer grid emptied a
 * parcel over the same journey more times over; `ClimateStage.marchLandStep` now divides a
 * kilometre figure by the cell width once, where it reads it.
 *
 * See docs/DESIGN_LEDGER.md, W3, and section 4 of docs/REALISM_AUDIT.md.
 */
object MoistureBudget {

    /**
     * The e-folding distance over which an air mass gives its moisture back to the ground as rain
     * over flat, temperate land, in kilometres.
     *
     * A thousand. Van der Ent and Savenije (2011) measure the continental length scales of the
     * atmospheric moisture cycle directly from reanalysis, and find the distance over which
     * evaporated water is returned to the surface to be a few hundred kilometres to a couple of
     * thousand, a band whose land-weighted middle is about a thousand: short enough that a
     * continent's downwind half is a different climate from its upwind half, long enough that
     * crossing five hundred kilometres of plain does not exhaust a parcel.
     *
     * The generator's own figure before this chunk was `baseRainRate` of 0.02 charged per cell,
     * which on the reference 512 grid's 23.4 km cell is 1,172 km — inside the band already,
     * which is why flat land's rain barely moves under this chunk. What moves is everything that
     * modulates the length: [depletionLengthKm] shortens it on a climb and lengthens it over warm
     * ground, and neither modulation existed.
     */
    const val FLAT_DEPLETION_LENGTH_KM = 1_000f

    /**
     * **There is no temperature term on the depletion length, and the absence is a measurement.**
     *
     * The chunk set out to add one: a warm surface holds more water before it condenses, so a
     * parcel over warm ground should travel further before it has emptied, at Clausius-Clapeyron's
     * seven per cent a degree. Written as a factor on the length and referenced to the planet's
     * mean surface temperature of 15 C, it gave the tropics a length of 2,310 km — the
     * exponential's 2.31 at 27 C — which halved their flat-land rain rate, and
     * `GeographyAuditTest`'s desert-by-latitude guard failed on it: the 0-15 degree band went to
     * x1.42 of its own land on seed 7 and x1.76 on 1234 against Earth's x0.27, tropical interiors
     * turning to desert for no reason but the reference temperature. Moving the reference to the
     * warm end cleared three seeds of the four and is not done here, because there is no figure in
     * the literature that says the continental length scales were measured over 30 C ground; a
     * reference chosen to clear a guard is a tuned constant wearing a derivation.
     *
     * What the failure actually showed is that the term was already in the march twice.
     * `ClimateStage`'s cold cap *is* Clausius-Clapeyron, applied to the parcel's stock: it holds
     * moisture to a ramp from 0.15 at -25 C to all of it at +20 C, a factor of 6.7 across the
     * range, against the relation's own factor of 34 for saturation vapour pressure between -20 C
     * and +30 C. The march carries moisture as a fraction of saturation, so a capacity factor on
     * the *rate* beside a capacity ramp on the *stock* charges one piece of physics to the parcel
     * twice over, and the tropics paid it. The modulation W3 was asked for therefore stays where
     * the generator already had it, named at the cold cap and pointed at from here, and the
     * depletion length over land is [FLAT_DEPLETION_LENGTH_KM] shortened by the climb and nothing
     * else. Deriving the cold cap from Clausius-Clapeyron instead of from a straight ramp is a
     * real chunk of work and is not this one's. See docs/DESIGN_LEDGER.md, W3.
     */

    /**
     * The fetch over which an air mass crossing open water re-saturates, in kilometres.
     *
     * Four hundred. A cold, dry air mass blown off a continent onto a warm sea — the winter
     * outbreaks off Honshu and off the Carolinas, where the fetch is measured because the cloud
     * streets make it visible — has restored most of its boundary-layer humidity within three to
     * five hundred kilometres of the coast. The generator's own figure before this chunk was
     * `evaporationRate` of 0.06 per cell, 390 km on the reference grid, so this is that number
     * given its unit rather than a new one; what changes is that it now stays 390 km on a finer
     * grid, where per-cell charging made it 98 km at 2048.
     */
    const val OCEAN_EVAPORATION_LENGTH_KM = 400f

    /**
     * How much shorter the depletion length is over open sea than over land.
     *
     * A quarter, which is `SEA_RAIN_MULTIPLE`'s four the other way up and the same statement:
     * most of the world's rain falls on the ocean, and without it the march arrives at every
     * windward coast holding far more water than an air mass that has crossed an ocean really
     * does. Over sea ice the multiple does not apply, because it is the convection warm water
     * drives and ice drives none.
     */
    const val SEA_DEPLETION_SHARE = 0.25f

    /**
     * The e-folding distance over which land gives water back to the air above it, in kilometres.
     *
     * Fifteen hundred, the middle of van der Ent and Savenije's (2011) 500-2,000 km continental
     * evaporation length scales, and the value inside that band at which the march reproduces the
     * figure the literature states as the *result*: van der Ent and others (2010) measure the
     * continental precipitation recycling ratio — the share of rain over land whose water last
     * evaporated from land rather than from the sea — at about 40% globally and 30-45% continent
     * by continent, higher over the Amazon and the Congo. There is no closed form linking this
     * length to that ratio in a march whose moisture also depends on fetch, belt and cold cap, so
     * the two ends of the band were measured: at 3,000 km the pooled ratio reads 20.3%, under
     * Earth's, and at 1,500 it reads what `MoistureBudgetTest` prints, which is re-measured on
     * every audited seed and is what makes this a property of the model rather than a per-world
     * fit.
     *
     * The generator's own figure before this chunk was `landRecoveryRate` of 0.010 per cell,
     * 2,344 km on the reference grid — and, like the ocean's, four times shorter per kilometre at
     * 2048 than at 512, which is the resolution defect this chunk closes.
     */
    const val EVAPOTRANSPIRATION_LENGTH_KM = 1_500f

    /**
     * Annual rainfall, in millimetres, at which the ground is wet enough to return water freely,
     * and the share it still returns when it is bare.
     *
     * Land does not evaporate what it has not been given. Five hundred millimetres is Koppen's own
     * steppe line, `ClimateStage.STEPPE_MM`, and the same line in the water balance: above it the
     * ground carries standing vegetation and the return is limited by energy, below it by supply.
     * Bare ground still returns something — evapotranspiration over the Sahara runs at roughly a
     * seventh of the humid tropics' — so the ramp starts at 0.15 rather than at zero.
     *
     * The proxy is the previous lap's rain at the cell. W4 built the field it was standing in for
     * and offered [groundReturnShare] in its place, and **this is still what ships**: pooled over
     * the four standard seeds the derivation puts the continental recycling ratio at 26.5% against
     * this ramp's 32.7% and Earth's 30-45%, so the proxy is the one inside Earth's band. The
     * reason the two differ is written out at `ClimateConfig.vegetationRecycling`, which is the
     * control the figures were taken with. See docs/DESIGN_LEDGER.md, W4.
     */
    const val WETNESS_REFERENCE_MM = 500f
    const val BARE_GROUND_WETNESS = 0.15f

    /**
     * The most of a column that may converge into one cell of travel, as a fraction.
     *
     * A quarter. The convergence term below is the linearisation "what flows in, rains out", and a
     * cell that gathered more than a quarter of its own column in one step is past where that
     * linearisation means anything — it would be a squall line, not a season's mean.
     */
    const val MAX_CONVERGENCE_PER_CELL = 0.25f

    /**
     * The height an air mass has to climb before the marine inversion stops holding its rain in,
     * in metres, and how far inland the capped layer reaches, in kilometres.
     *
     * A thousand metres: Klein and Hartmann (1993) map the subtropical stratus regimes to the
     * strength of the lower-tropospheric inversion, whose base over the eastern ocean margins sits
     * between about five hundred and fifteen hundred metres. Below it the marine layer is
     * stratus-capped and rains a drizzle; the first ground that stands above it — the Andes behind
     * the Atacama, the Great Escarpment behind the Namib — rains normally, which is exactly why
     * those deserts are coastal strips and not whole interiors.
     *
     * Five hundred kilometres is how far the capped layer is carried inland before it has mixed
     * out, the width of the Atacama-Sechura and Namib strips taken together with the Baja
     * peninsula's.
     */
    const val INVERSION_LID_METRES = 1_000f
    const val INVERSION_REACH_KM = 500f

    /**
     * **What this term does not do, measured.**
     *
     * It does not make a coastal desert. Pooled over the standard seeds it moves a cold west
     * coast's share of its own hinterland's rain by 0.2%, with a suppression field whose
     * warm-season peak over those coasts is 0.95 and none of whose cells stand above the lid - so
     * the march reads a strong lid and rains anyway. The march is a reservoir and this is a
     * multiplier on the rate it empties at: hold the rate down and the moisture stands higher,
     * because the ground's return adds on the deficit, and `moisture x rate` comes back within a
     * few cells. A coastal desert needs the water taken out of the column, not the rain rate held
     * down, and that is a change to the march's shape rather than to this term. The term is kept
     * because the diagnosis names what would make it bite and `MoistureBudgetTest` goes on
     * measuring it. See docs/DESIGN_LEDGER.md, W3.
     */
    const val INVERSION_MEASURED_EFFECT = 0.002f

    /**
     * How cold the water off a coast has to be, as a departure from the mean of its own latitude
     * in degrees Celsius, for the inversion above it to be at full strength.
     *
     * Three degrees, the California current's — the mildest of the three eastern-boundary
     * currents that make a coastal desert, against the Peru current's four to seven and the
     * Benguela's four. A ramp to it rather than a threshold at it, so that a coast with a
     * one-degree anomaly gets a third of the suppression and nothing has to be decided by a cliff
     * the map's own noise could push a cell across.
     */
    const val INVERSION_FULL_ANOMALY_C = 3f

    /** Where the subtropical stratus decks sit, in degrees from the thermal equator. */
    private const val INVERSION_CENTRE_DEGREES = 25f
    private const val INVERSION_WIDTH_DEGREES = 15f

    /**
     * How wet the ground under a parcel is, 0..1, from the rain the previous lap of the march left
     * there.
     *
     * [previousLapMm] is that rain in millimetres for the season. A proxy for W4's vegetation
     * field, stated as one: see [WETNESS_REFERENCE_MM].
     */
    fun groundWetness(previousLapMm: Float): Float =
        BARE_GROUND_WETNESS + (1f - BARE_GROUND_WETNESS) *
            (previousLapMm / WETNESS_REFERENCE_MM).coerceIn(0f, 1f)

    /**
     * The same number derived rather than proxied: how freely the ground gives water back, 0..1,
     * from the vegetation the previous lap's rain would grow on it.
     *
     * [VegetationDensity.density] is Budyko's evaporative fraction — the share of the year's
     * available evaporative energy that the water supply actually meets — held down by the growing
     * season, and that share was expected to *be* the ground's return rather than a second proxy
     * for it. It measured lower than [groundWetness] over most of these worlds' land and put the
     * continental recycling ratio outside Earth's band, so it is the control and not the default;
     * `ClimateConfig.vegetationRecycling` carries the figures and the diagnosis.
     *
     * [biotemperatureC] is Holdridge's biotemperature at the cell and [previousLapMm] the rain the
     * previous lap left there, in millimetres a year. Floored at [BARE_GROUND_WETNESS] for the
     * reason that constant gives: bare ground still returns something, roughly a seventh of the
     * humid tropics', and a cell whose cover is nothing is not a cell that evaporates nothing.
     */
    fun groundReturnShare(biotemperatureC: Float, previousLapMm: Float): Float =
        BARE_GROUND_WETNESS + (1f - BARE_GROUND_WETNESS) *
            VegetationDensity.density(biotemperatureC, previousLapMm)

    /**
     * The share of an air column that converges into each cell over one cell of travel: the
     * regional wind's horizontal convergence multiplied by how long a parcel spends crossing a
     * cell.
     *
     * Positive where air gathers and rains, negative where it spreads and dries. This is mass
     * continuity and not a fitted term: a column whose horizontal wind converges at
     * `-div(u)` per second gathers `-div(u) * dt` of itself in `dt` seconds, and in a steady state
     * what it gathers is what it rains. The time is a cell's width divided by
     * [PressureWind.BELT_SPEED_MPS], because the march advances one cell per step and the belt's
     * own speed is what that step stands for.
     *
     * It is the **departure** wind's divergence and not the total wind's, which is the whole
     * reason the ITCZ does not move under this term. The belts' own convergence — the trades
     * meeting at the thermal equator, the air descending in the horse latitudes — is already in
     * `ClimateStage.latitudeBandAt`'s profile, which was measured as a rain rate against latitude;
     * adding the belt divergence here would count the same rising air twice and put a second,
     * sharper ITCZ inside the first. What the profile cannot know is where a *continent* gathers
     * air, and that is exactly what the departure carries.
     *
     * Returns null when there is no departure, which is `ClimateConfig.pressureWinds` or
     * `ClimateConfig.convergenceRain` off: then the march runs the arithmetic it ran before,
     * rather than the same arithmetic with a zero in it.
     */
    internal fun convergencePerCell(
        config: WorldGenConfig,
        departure: PressureWind.Vectors?
    ): FloatField? {
        if (departure == null) return null
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellWidthMetres = (config.scale.cellWidthKm(cellsAcross) * 1_000.0).toFloat()
        val cellHeightMetres = (config.scale.cellHeightKm(cellsDown) * 1_000.0).toFloat()
        val secondsPerCell = cellWidthMetres / PressureWind.BELT_SPEED_MPS
        val field = FloatField(cellsAcross, cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val rowAbove = (row - 1).coerceAtLeast(0)
                val rowBelow = (row + 1).coerceAtMost(cellsDown - 1)
                // The span the southward difference is taken over: two rows in the body of the
                // map, one at the top and bottom edges where there is no row beyond.
                val southwardSpanMetres = (rowBelow - rowAbove) * cellHeightMetres
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    val eastOf = row * cellsAcross + (column + 1) % cellsAcross
                    val westOf = row * cellsAcross + (column + cellsAcross - 1) % cellsAcross
                    val eastwardGradient =
                        (departure.eastwardMps[eastOf] - departure.eastwardMps[westOf]) /
                            (2f * cellWidthMetres)
                    val southwardGradient = if (southwardSpanMetres == 0f) {
                        0f
                    } else {
                        (departure.southwardMps[rowBelow * cellsAcross + column] -
                            departure.southwardMps[rowAbove * cellsAcross + column]) /
                            southwardSpanMetres
                    }
                    val divergencePerSecond = eastwardGradient + southwardGradient
                    field.data[cell] = (-divergencePerSecond * secondsPerCell)
                        .coerceIn(-MAX_CONVERGENCE_PER_CELL, MAX_CONVERGENCE_PER_CELL)
                }
            }
        }
        return field
    }

    /**
     * How far each land cell's rain is held back by a marine inversion, 0..1, where 1 is a cell
     * directly behind a west coast washed by water [INVERSION_FULL_ANOMALY_C] below its latitude's
     * mean, in the middle of the subtropical stratus belt.
     *
     * Built by one sweep west to east along each row, which is the direction the capped marine
     * layer travels: it comes off the cold water onto the coast facing it and is carried inland,
     * decaying over [INVERSION_REACH_KM]. That sweep is why the term makes a *west* coast desert
     * and not an east one, and it does not depend on which way the march happens to be sweeping,
     * because the inversion is a property of the air column over the ground rather than of the
     * parcel crossing it.
     *
     * [seaSurfaceAnomalyC] is the current stage's departure from the latitude mean, and
     * [tiltDegrees] with [warm] place the stratus belt in this season the same way the rain belts
     * are placed. Returns null when `ClimateConfig.marineInversion` is off or the ocean stage is,
     * so the march runs without the term rather than with a zero one.
     */
    internal fun inversionSuppression(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        seaSurfaceAnomalyC: FloatField?,
        tiltDegrees: Float,
        warm: Boolean
    ): FloatField? {
        if (seaSurfaceAnomalyC == null) return null
        val cellsAcross = config.width
        val cellsDown = config.height
        val decayPerCell = exp(
            (-config.scale.cellWidthKm(cellsAcross) / INVERSION_REACH_KM).toFloat()
        )
        val field = FloatField(cellsAcross, cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val latitude = ClimateStage.latitudeOf(row, cellsDown)
                val fromThermalEquator = if (warm) {
                    abs(abs(latitude) - tiltDegrees)
                } else {
                    abs(latitude) + tiltDegrees
                }
                val inBelt = stratusBelt(fromThermalEquator)
                if (inBelt <= 0f) continue

                // Two laps west to east, so a coast at the map's seam is carried across it: the
                // first lap leaves the strength the seam's own column would have handed on, the
                // second records. One extra lap is enough because the decay has fallen to nothing
                // long before a parcel could reach the seam twice.
                var carried = 0f
                for (lap in 0 until 2) {
                    for (column in 0 until cellsAcross) {
                        val cell = row * cellsAcross + column
                        carried = if (!sea.isLand[cell]) {
                            val coldness = (-seaSurfaceAnomalyC.data[cell] /
                                INVERSION_FULL_ANOMALY_C).coerceIn(0f, 1f)
                            coldness * inBelt
                        } else {
                            carried * decayPerCell
                        }
                        if (lap == 1 && sea.isLand[cell]) field.data[cell] = carried
                    }
                }
            }
        }
        return field
    }

    /** How much of the subtropical stratus belt a latitude is in, 0..1. */
    private fun stratusBelt(degreesFromThermalEquator: Float): Float {
        val widthsFromCentre =
            (degreesFromThermalEquator - INVERSION_CENTRE_DEGREES) / INVERSION_WIDTH_DEGREES
        return exp((-widthsFromCentre * widthsFromCentre)).coerceIn(0f, 1f)
    }
}
