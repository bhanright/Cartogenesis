package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * How much living cover the ground carries, 0 on bare rock or ice to 1 under a closed forest, and
 * where the ground beneath it is frozen the year round.
 *
 * A field and not a table. Koppen's classifier beside this one says which of sixteen names a cell
 * has, and a name is a step function: two cells either side of the 500 mm steppe line are painted
 * as different countries although their vegetation differs by a few per cent. This field is the
 * continuous quantity the names are a partition of, so a steppe thins into woodland across fifty
 * cells rather than at one, and so that the stages that want a *number* for the cover — the tint,
 * and the erodibility S3 and H3 will read (Istanbulluoglu and Bras 2005, where vegetation roughly
 * halves the bare-soil erodibility) — have one to read.
 *
 * It is built from two factors, each of which is a published relation rather than a curve fitted
 * to this generator:
 *
 *  - **the water balance**, as the share of the energy available for evaporation that the water
 *    supply actually meets: Budyko's (1974) curve for actual evapotranspiration against the
 *    dryness index, which is Holdridge's own PET ratio. Eagleson's (1982) ecohydrological
 *    equilibrium is the reason a plant cover tracks that share — a canopy grows until it is
 *    transpiring all the water the climate will give it and no further.
 *  - **the growing season**, as Holdridge's (1967) biotemperature: the year's monthly means with
 *    everything below freezing and above 30 C thrown away, averaged over all twelve months, so a
 *    place with three warm months scores a quarter of one warm all year.
 *
 * The product of the two is the density. Both ends of both factors are Holdridge's or Budyko's own
 * lines; the only figure this file chooses is [PERMAFROST_CANOPY_CEILING], and its derivation sits
 * beside it.
 *
 * Checked against Whittaker's (1975) biome diagram rather than against the generator's last output:
 * `VegetationDensityTest` evaluates the function at the centre of each of Whittaker's climate
 * boxes and asserts the order of the answers, which is a property of the function and needs no
 * world at all.
 *
 * See docs/DESIGN_LEDGER.md, W4, and section 4 of docs/REALISM_AUDIT.md.
 */
object VegetationDensity {

    /**
     * Holdridge's potential evapotranspiration, in millimetres a year per degree of biotemperature.
     *
     * 58.93, and it is a definition rather than a measurement: Holdridge (1967) defines the
     * potential evapotranspiration of a life zone as its biotemperature times this factor, which
     * is what makes his chart's third axis a function of the first. The number is the ratio
     * between his own PET and biotemperature axes and carries the units with it.
     */
    const val HOLDRIDGE_PET_PER_BIOTEMPERATURE_C = 58.93f

    /**
     * The biotemperature, in degrees Celsius, at which Holdridge's chart leaves the subpolar
     * belt for the boreal one.
     *
     * Three. His latitudinal regions are octaves of biotemperature — 1.5, 3, 6, 12, 24 — and three
     * is the line between subpolar (tundra, dwarf scrub) and boreal (closed forest). It is
     * therefore the lowest growing season at which a full cover is on his chart at all, and the
     * top of the ramp below.
     */
    const val HOLDRIDGE_BOREAL_LINE_C = 3f

    /**
     * The warmest a month may count for, in degrees Celsius.
     *
     * Thirty, Holdridge's own upper clamp on biotemperature: above it the growing season is not
     * lengthened by more heat, it is shortened by it, and his chart stops.
     */
    const val BIOTEMPERATURE_CEILING_C = 30f

    /** Months in the year the biotemperature is averaged over. Twelve, because Holdridge's is. */
    private const val MONTHS = 12

    /**
     * The mean annual air temperature, in degrees Celsius, at or below which permafrost is
     * continuous, and the one at or below which it is discontinuous or sporadic.
     *
     * Minus eight and minus two. Brown and others' (1997) circum-Arctic permafrost map and Zhang
     * and others' (1999) area statistics taken off it put the southern limit of the *continuous*
     * zone close to the -8 C mean-annual-air-temperature isotherm and the outer limit of the
     * discontinuous and sporadic zones between -1 and -2 C; the ground runs a degree or two warmer
     * than the air under snow cover, which is why the air's isotherm and the ground's limit are
     * not the same number and why the band is quoted as a range rather than a line.
     *
     * The cold end of each published range is taken rather than the warm one, and the reason is a
     * measurement this generator already carries: `ColdBiomeShareTest` records that this map's
     * land stands 1,200-1,700 m above its own sea against Earth's 840, so a lapse rate of
     * 6.5 C/km takes three to five degrees off nearly every land cell and every cold threshold in
     * the pipeline bites on more ground here than it would on Earth. Choosing -6 and -1 would put
     * more of the map under permafrost for a reason that has nothing to do with permafrost.
     */
    const val CONTINUOUS_PERMAFROST_C = -8f
    const val DISCONTINUOUS_PERMAFROST_C = -2f

    /**
     * The most cover continuous permafrost allows, 0..1.
     *
     * Two fifths. Under continuous permafrost the active layer — the skin that thaws each summer
     * and the only ground a root can occupy — is a few tens of centimetres to about a metre
     * (Brown and others 1997), which is shallower than a tree needs and is why the continuous zone
     * is tundra and dwarf scrub rather than forest however long its summer runs. Two fifths is the
     * top of the open-shrubland and sparse-vegetation band in the land-cover classes this
     * repository already uses for the same purpose in `ClimateTint` — a tenth to two fifths woody
     * cover over bare or herbaceous ground — so the ceiling is the densest thing the classes call
     * open rather than a number chosen to move a share.
     *
     * Discontinuous permafrost carries **no** ceiling, which is deliberate and is the literature's
     * own answer: the Siberian larch forests stand on discontinuous permafrost over most of their
     * range. A mask that stopped forest wherever any permafrost was found would delete them.
     */
    const val PERMAFROST_CANOPY_CEILING = 0.4f

    /** How much of the ground is frozen the year round. Stored one byte a cell; see [ofCell]. */
    enum class Permafrost {
        /** No perennially frozen ground. */
        NONE,

        /** Patchy: the literature's discontinuous and sporadic zones together. Forest still grows. */
        DISCONTINUOUS,

        /** Frozen under the whole surface, with a thaw depth too shallow to root a tree in. */
        CONTINUOUS;

        companion object {
            /** The zone for a mean annual air temperature, in degrees Celsius. */
            fun ofCell(meanAnnualC: Float): Permafrost = when {
                meanAnnualC <= CONTINUOUS_PERMAFROST_C -> CONTINUOUS
                meanAnnualC <= DISCONTINUOUS_PERMAFROST_C -> DISCONTINUOUS
                else -> NONE
            }

            /** The zone a saved byte stands for; anything unknown reads as [NONE]. */
            fun ofOrdinal(ordinal: Int): Permafrost = entries.getOrElse(ordinal) { NONE }
        }
    }

    /**
     * The density field and the permafrost mask of a whole world, one entry per cell, row-major.
     *
     * [density] is 0..1 and is 0 at every sea cell, because a sea cell has no ground to carry a
     * cover and every consumer reads it behind the land mask anyway. [permafrost] holds a
     * [Permafrost] ordinal a cell, and is [Permafrost.NONE] at sea for the same reason: sea ice is
     * frozen water rather than frozen ground and `ClimateResult.summerSeaIce` already says where
     * it is.
     */
    class Field(val density: FloatField, val permafrost: ByteArray)

    /**
     * Holdridge's biotemperature for a cell, in degrees Celsius: the year's twelve monthly means
     * with everything outside 0..[BIOTEMPERATURE_CEILING_C] replaced by the nearer end, averaged.
     *
     * The stage carries three temperatures and not twelve — the annual mean, the warmest month and
     * the coldest — so the year is reconstructed as a sinusoid through them: the annual mean is the
     * middle, because it is the field every other part of the pipeline reads and the permafrost
     * mask beside this one is taken off it, and half the gap between the warmest and coldest
     * months is the swing. That reconstruction is the ordinary one for a mid-latitude annual cycle
     * and it is exact at the two months it is built from. Twelve samples rather than an integral,
     * because Holdridge's definition is a mean of twelve monthly means and a continuous integral
     * of the clamped sinusoid is a different number.
     */
    fun biotemperatureC(meanAnnualC: Float, warmestMonthC: Float, coldestMonthC: Float): Float {
        val swingC = (warmestMonthC - coldestMonthC) * 0.5f
        var total = 0f
        for (month in 0 until MONTHS) {
            val monthlyMeanC =
                meanAnnualC + swingC * cos(2f * PI.toFloat() * month / MONTHS)
            total += monthlyMeanC.coerceIn(0f, BIOTEMPERATURE_CEILING_C)
        }
        return total / MONTHS
    }

    /** Holdridge's potential evapotranspiration, in millimetres a year. */
    fun potentialEvapotranspirationMm(biotemperatureC: Float): Float =
        HOLDRIDGE_PET_PER_BIOTEMPERATURE_C * biotemperatureC

    /**
     * The share of the year's potential evapotranspiration the water supply actually meets, 0..1:
     * Budyko's (1974) curve, evaluated at the dryness index [potentialOverPrecipitation].
     *
     * Budyko writes the actual evapotranspiration of a catchment in a steady state as
     *
     * ```
     * AET / P = sqrt( f * tanh(1 / f) * (1 - exp(-f)) )     with f = PET / P
     * ```
     *
     * which is bounded by the water at the dry end and by the energy at the wet end and passes
     * smoothly between them. Dividing through by `f` turns it from a share of the rainfall into a
     * share of the energy, which is the form a plant cover answers to: a canopy transpires what it
     * is given, so what it can hold is `AET / PET`. At a dryness index of 1 this reads 0.69, at
     * Holdridge's semiarid 2 it reads 0.45, and beyond about 4 it is the rainfall over the
     * potential and nothing else.
     *
     * Undefined at a dryness index of zero, where there is no energy to evaporate anything; a
     * world with no warmth has no growing season either and [growingSeasonFactor] has already
     * returned zero there, so the guard here simply returns zero rather than dividing by it.
     */
    fun evaporativeFraction(potentialOverPrecipitation: Float): Float {
        val dryness = potentialOverPrecipitation
        if (dryness <= 0f) return 0f
        if (!dryness.isFinite()) return 0f
        val shareOfRainfall =
            sqrt(dryness * tanh(1f / dryness) * (1f - exp(-dryness)))
        return (shareOfRainfall / dryness).coerceIn(0f, 1f)
    }

    /**
     * How much of a full growing season a biotemperature is, 0..1.
     *
     * A straight ramp between two of Holdridge's own lines. The bottom is zero degrees of
     * biotemperature, which is not a chosen threshold but the definition of the quantity — a cell
     * whose every month is at or below freezing has a biotemperature of exactly zero and no
     * growing season at all. The top is [HOLDRIDGE_BOREAL_LINE_C], the lowest biotemperature at
     * which his chart carries a closed forest. Between them the season is what limits the cover
     * and the ramp says how far: tundra, whose biotemperature runs near 1 to 1.5, comes out at a
     * third to a half of what its water balance alone would allow, which is what the circumpolar
     * land cover reads.
     */
    fun growingSeasonFactor(biotemperatureC: Float): Float =
        (biotemperatureC / HOLDRIDGE_BOREAL_LINE_C).coerceIn(0f, 1f)

    /**
     * The vegetation density of one cell, 0..1, from its year's temperatures and its rainfall.
     *
     * [annualPrecipitationMm] is the year's total in millimetres — `ClimateResult.precipitationMm`
     * and not the 0..1 copy. [permafrost] caps the answer at [PERMAFROST_CANOPY_CEILING] where it
     * is [Permafrost.CONTINUOUS] and is ignored otherwise.
     *
     * Monotone in the water balance at a fixed biotemperature and monotone in the biotemperature
     * at a fixed balance, both by construction — the two factors are separately monotone and the
     * product of two non-negative monotone factors is monotone — and `VegetationDensityTest`
     * asserts it over the function rather than over a world.
     */
    fun density(
        biotemperatureC: Float,
        annualPrecipitationMm: Float,
        permafrost: Permafrost = Permafrost.NONE
    ): Float {
        val season = growingSeasonFactor(biotemperatureC)
        if (season <= 0f) return 0f
        val potentialMm = potentialEvapotranspirationMm(biotemperatureC)
        val dryness = if (annualPrecipitationMm <= 0f) {
            Float.POSITIVE_INFINITY
        } else {
            potentialMm / annualPrecipitationMm
        }
        val water = evaporativeFraction(dryness)
        val open = season * water
        return if (permafrost == Permafrost.CONTINUOUS) {
            open.coerceAtMost(PERMAFROST_CANOPY_CEILING)
        } else {
            open
        }
    }

    /**
     * [density] and the permafrost zone for every cell of a world.
     *
     * [isLand] is the sea-level stage's mask; [meanAnnualC], [warmestMonthC] and [coldestMonthC]
     * are `ClimateResult`'s three temperature fields and [annualPrecipitationMm] its millimetre
     * one. Every array is one entry per cell, row-major, and the result's are too.
     *
     * One pass over the grid with twelve clamps and three transcendentals a land cell, which is
     * why it has no accelerator: `VegetationCostTest` prints its share of a 2048 generation
     * against rule 8's one per cent.
     */
    fun field(
        isLand: BooleanArray,
        meanAnnualC: FloatField,
        warmestMonthC: FloatField,
        coldestMonthC: FloatField,
        annualPrecipitationMm: FloatField,
        /**
         * `VegetationConfig.permafrost`. False leaves every cell outside the zones, so nothing is
         * capped and the mask is empty — the control the permafrost figures are reported against.
         */
        permafrostEnabled: Boolean = true
    ): Field {
        val cellsAcross = meanAnnualC.width
        val cellsDown = meanAnnualC.height
        val density = FloatField(cellsAcross, cellsDown)
        val permafrost = ByteArray(cellsAcross * cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (cell in startRow * cellsAcross until endRow * cellsAcross) {
                if (!isLand[cell]) continue
                val zone = if (permafrostEnabled) {
                    Permafrost.ofCell(meanAnnualC.data[cell])
                } else {
                    Permafrost.NONE
                }
                permafrost[cell] = zone.ordinal.toByte()
                density.data[cell] = density(
                    biotemperatureC(
                        meanAnnualC.data[cell],
                        warmestMonthC.data[cell],
                        coldestMonthC.data[cell]
                    ),
                    annualPrecipitationMm.data[cell],
                    zone
                )
            }
        }
        return Field(density, permafrost)
    }
}
