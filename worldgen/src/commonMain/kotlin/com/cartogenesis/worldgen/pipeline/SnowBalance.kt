package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Whether a cell gains more snow in a year than it loses, in millimetres of water equivalent.
 *
 * ### Why a balance and not a temperature
 *
 * Before this existed, ice was "the annual mean is at or below freezing", which is a statement
 * about how cold a place is and not about whether a glacier can live there. A glacier is a
 * reservoir: it exists where the snow that falls in a year survives the year, and it does not
 * exist where the same year's melt takes more than fell, however cold the air was while nothing
 * was falling. Siberia is colder than the Norwegian coast in every month and carries no ice sheet,
 * because almost no snow reaches it; Patagonia's snowline stands at about 1000 m and the Atacama's,
 * at the same latitude on the far side of a desert, at about 6000 m. A temperature threshold
 * cannot tell those two apart and calls every cold interior an ice cap — see REALISM_PLAN.md, H2,
 * for what that measured against Earth's own ice share.
 *
 * So: accumulation against ablation, per cell, out of the four seasonal fields the climate stage
 * already computes.
 *
 * ### Accumulation
 *
 * The precipitation that falls as snow. The seasonal rainfall fields are half-year rates in the
 * annual-equivalent millimetres [ClimateStage.MM_SCALE] produces — the annual total is their mean —
 * so each season contributes half of its own rate, and each is asked separately what fraction of
 * it fell frozen. Asking both halves rather than only the cold one is what lets a polar cell whose
 * *summer* never thaws accumulate all year, which is the Antarctic case and the reason an ice sheet
 * can sit on a desert: 50 mm a year of snow that never melts is still an ice sheet.
 *
 * The rain/snow split is a ramp rather than a step, between [SNOW_ALL_C] and [RAIN_ALL_C]. A
 * half-year mean of -0.1 C and one of +0.1 C do not really differ in how much of their weather
 * arrived frozen, and a step there would draw the ice margin along an isotherm — the very artefact
 * a balance exists to remove.
 *
 * ### Ablation
 *
 * The positive-degree-day model, which is the standard way to get melt out of a temperature record
 * and the only one that can be fed by the fields this generator has. Melt in millimetres water
 * equivalent is [DEGREE_DAY_FACTOR_MM] times the sum of the season's daily mean temperatures above
 * freezing.
 *
 * The published range of the degree-day factor for *snow* is roughly 3-5 mm w.e. per degree-day
 * (Braithwaite 1995; Hock, *Temperature index melt modelling in mountain areas*, J. Hydrol. 282,
 * 2003, tabulates 2.5-11.6 across all surfaces with snow clustering near 4 and bare ice at 6-8) —
 * the plan's "roughly 3-8 mm per degree-day for snow to ice" is that whole span. This uses **4.5**,
 * the middle of the snow range: what accumulates on a glacier's surface and has to survive the
 * summer is snow, and treating it as bare ice would put the ablation of a firn field at an
 * exposed-ice rate.
 *
 * ### Half-year means into degree-days
 *
 * The fields are half-year *means*, and the sum of the positive part of a series is not the
 * positive part of its mean: a season averaging -3 C still has thawing days in it, and a season
 * averaging +1 C does not melt for 182 days at 1 C. The published fix is Calov and Greve's
 * closed form (*A semi-analytical solution for the positive degree-day model with stochastic
 * temperature variations*, J. Glaciol. 51, 2005), which integrates a normal distribution of daily
 * temperature about the seasonal mean:
 *
 * ```
 * PDD per day = sigma/sqrt(2*pi) * exp(-T^2 / (2*sigma^2)) + (T/2) * erfc(-T / (sigma*sqrt(2)))
 * ```
 *
 * with [PDD_SIGMA_C] the day-to-day standard deviation about the mean — 4.5 C here, inside the
 * 3.5-5.5 C the literature uses for that parameter (Reeh 1991 takes 4.5 for Greenland; Calov and
 * Greve 5.0). The expression is smooth and strictly positive everywhere, which is what keeps the
 * ice margin off the isotherms: it falls to 4 degree-days a season by -10 C and to nothing by -20,
 * rather than switching off at zero.
 *
 * Worked, so the constants can be checked against something: at a half-year mean of exactly 0 C the
 * formula gives 1.80 degree-days a day, 328 over the half year, and 1475 mm w.e. of melt — so a
 * cell whose warm half averages freezing point needs about a metre and a half of snow to hold its
 * ice, which is a maritime temperate glacier and is roughly what Norway's get. At -5 C it is 249 mm
 * and at -10 C, 17 mm.
 *
 * ### Where it runs
 *
 * Per-cell arithmetic over four fields, so the seam for somewhere other than the CPU is
 * [SnowBalanceAccelerator] — cut, measured, and left unimplemented: see that interface's own note.
 */
object SnowBalance {

    /** Days in a half-year, the span each seasonal field is the mean of. */
    internal const val SEASON_DAYS = 182.62f

    /**
     * Melt per positive degree-day, in millimetres of water equivalent.
     *
     * 4.5, the middle of the published 3-5 for snow. See the class comment for the sources and for
     * why the snow figure rather than the 6-8 of bare ice.
     */
    internal const val DEGREE_DAY_FACTOR_MM = 4.5f

    /** Day-to-day standard deviation of temperature about a seasonal mean, in C. */
    internal const val PDD_SIGMA_C = 4.5f

    /** At or below this seasonal mean, all of the season's precipitation falls as snow. */
    internal const val SNOW_ALL_C = -1f

    /** At or above this seasonal mean, none of it does. */
    internal const val RAIN_ALL_C = 3f

    /**
     * Accumulation minus ablation for one cell, in millimetres of water equivalent a year.
     *
     * Positive means the cell keeps snow through the year, which is a glacier. The seasonal
     * rainfall arguments are in [ClimateStage.MM_SCALE]'s annual-equivalent millimetres, as
     * [ClimateResult.precipitationMm]'s two halves are.
     */
    fun balanceMm(
        summerC: Float,
        winterC: Float,
        summerMm: Float,
        winterMm: Float
    ): Float {
        val accumulation =
            0.5f * summerMm * snowFraction(summerC) + 0.5f * winterMm * snowFraction(winterC)
        val ablation = DEGREE_DAY_FACTOR_MM * SEASON_DAYS *
            (positiveDegreeDaysPerDay(summerC) + positiveDegreeDaysPerDay(winterC))
        return accumulation - ablation
    }

    /**
     * The whole field at once. Every cell is independent; water is left at zero.
     *
     * A colder world is asked for by handing in the temperature and rainfall fields of a colder
     * world, not by shifting these ones: `ClimateStage.provisionalSnowBalance` puts the glacial
     * cooling into the energy balance as a forcing and passes on what comes out. Until W1 there was
     * a per-row cooling ramp here instead, a third of the global mean at the equator to twice it at
     * the pole, written down from the proxies because a latitude curve could not produce polar
     * amplification of its own. The model can, so the ramp is gone.
     */
    fun field(
        isLand: BooleanArray,
        summerTemperature: FloatField,
        winterTemperature: FloatField,
        summerPrecipitationMm: FloatField,
        winterPrecipitationMm: FloatField
    ): FloatField {
        val cellsAcross = summerTemperature.width
        val cellsDown = summerTemperature.height
        val balance = FloatField(cellsAcross, cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (cell in row * cellsAcross until (row + 1) * cellsAcross) {
                    if (!isLand[cell]) continue
                    balance.data[cell] = balanceMm(
                        summerTemperature.data[cell],
                        winterTemperature.data[cell],
                        summerPrecipitationMm.data[cell],
                        winterPrecipitationMm.data[cell]
                    )
                }
            }
        }
        return balance
    }

    /**
     * The fraction of a season's precipitation that arrives as snow, ramped over
     * [SNOW_ALL_C]..[RAIN_ALL_C].
     */
    internal fun snowFraction(seasonalMeanC: Float): Float =
        ((RAIN_ALL_C - seasonalMeanC) / (RAIN_ALL_C - SNOW_ALL_C)).coerceIn(0f, 1f)

    /**
     * Expected positive degree-days contributed by one day of a season whose mean is [meanC],
     * by Calov and Greve's closed form. See the class comment.
     */
    internal fun positiveDegreeDaysPerDay(meanC: Float): Float {
        val sigma = PDD_SIGMA_C.toDouble()
        val mean = meanC.toDouble()
        // The two halves of Calov and Greve's expression: the first is what a spread of daily
        // temperatures contributes when the mean itself is below freezing, the second what the
        // mean contributes once it is above it.
        val spreadTerm = sigma / sqrt(2.0 * PI) * exp(-(mean * mean) / (2.0 * sigma * sigma))
        val meanTerm = mean / 2.0 * erfc(-mean / (sigma * sqrt(2.0)))
        return (spreadTerm + meanTerm).toFloat()
    }

    /**
     * The complementary error function, by Abramowitz and Stegun 7.1.26 — the rational
     * substitution and the five polynomial coefficients below are that formula's own, in Horner
     * form. Maximum absolute error 1.5e-7, which is well inside a float.
     *
     * Written out here rather than taken from a library because Kotlin's common standard library
     * has no `erf` and the whole of this file has to run on the JVM and in a browser alike.
     */
    private fun erfc(argument: Double): Double {
        val sign = if (argument < 0.0) -1.0 else 1.0
        val magnitude = abs(argument)
        val substitution = 1.0 / (1.0 + 0.3275911 * magnitude)
        val polynomial = substitution * (0.254829592 +
            substitution * (-0.284496736 +
                substitution * (1.421413741 +
                    substitution * (-1.453152027 + substitution * 1.061405429))))
        val erf = sign * (1.0 - polynomial * exp(-magnitude * magnitude))
        return 1.0 - erf
    }
}

/**
 * Somewhere other than the CPU to run [SnowBalance.field] — the same shape [ErosionAccelerator]
 * has: suspending, returning null to fall back to the CPU.
 *
 * **Measured, and declined.** The balance is four multiplies, two exponentials and two error
 * functions per cell, with no neighbourhood and no iteration. Timed on this machine inside
 * `SnowBalanceTest`, over land only, it costs single-digit milliseconds at 2048 — far inside the
 * 50 ms below which a shader is not worth writing, and far inside the cost of the buffer upload it
 * would need. So there is no OpenGL implementation and no WGSL one; this interface is here so that
 * a later chunk which finds a reason to want one — a much larger grid, or a balance that grows an
 * iterative firn model — has the seam already cut and does not have to reach into [ClimateStage]
 * to add it.
 *
 * The provisional climate march that feeds the balance stays on the CPU regardless: it is the
 * moisture march, whose lock-step wavefronts are why that pass has no GPU path either.
 */
interface SnowBalanceAccelerator {

    /** Shown to the user, so it should name the actual device where that is knowable. */
    val name: String

    /**
     * The balance for every cell, or null if this accelerator cannot do the job — in which case
     * the caller falls back to [SnowBalance.field]. None of the input arrays is modified.
     */
    suspend fun balance(
        width: Int,
        height: Int,
        summerTemperature: FloatArray,
        winterTemperature: FloatArray,
        summerPrecipitationMm: FloatArray,
        winterPrecipitationMm: FloatArray
    ): FloatArray?
}
