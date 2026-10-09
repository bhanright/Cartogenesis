package com.cartogenesis.worldgen.pipeline

/**
 * How much water a land cell sheds in a year, in millimeters: the one weight every stage that
 * routes water routes with — the erosion rounds, the channel-initiation criterion, the drawn
 * network's discharge in `RiverStage`, the lakes' inflow and the riverine threshold `NationStage`
 * reads off it.
 *
 * **The runoff is the rain the ground did not give back.** The moisture march returns Budyko's
 * share of each land cell's year of rain to the air against the cell's potential
 * evapotranspiration ([MoistureMarch.groundReturnMm]); what is left runs off, and this is that
 * remainder, against the same rain and the same potential ([ClimateResult.precipitationMm],
 * [ClimateResult.potentialEvapotranspirationMm]). So at every cell the rain is the return plus the
 * runoff, and water the march put back into the air is not routed down the rivers as well. Until
 * C1 every stage routed the whole rainfall with a floor of 60 mm under it, a stand-in for a
 * runoff nothing modeled; the floor went with it, because Budyko's curve already gives a
 * hyper-arid cell its own small share, and a floor was water the cell's climate did not have
 * (docs/DESIGN_LEDGER.md, C1).
 *
 * Still annual and still smooth: what reaches a channel is delivered in floods rather than evenly,
 * and soil and snow carry water across the seasons, which an annual curve at each cell does not
 * see. Stated once, here, where the substitution is made.
 *
 * **The two normalizations, and why each is right for its consumer.** The two stages that compare
 * the weight against a fitted or measured figure do not route [annualRunoffMm] raw; each divides it
 * by a mean, and they divide by different ones on purpose. The drawn network and the realm stage
 * route it raw, because everything they do with it is a ratio within the one world — a channel's
 * width against the widest, a threshold as a share of all the land's water.
 *
 * `ChannelInitiation` divides by **Earth's own mean over land**, [EARTH_MEAN_LAND_RUNOFF_MM] —
 * an absolute reference. Its threshold is an area in square kilometers read off Montgomery and
 * Dietrich's field surveys, so the quantity it compares against that threshold has to be a
 * physical area of real ground and not a share of this world's.
 *
 * `HydraulicErosion` divides by **the mean over the land the pass is routing on** — a relative
 * reference, re-taken every pass because the shoreline moves. `Rates.incisionCoefficient` was
 * fitted at one world's total water and nothing re-fits it, and a mean of exactly 1 is what keeps
 * an accumulation a share of the land's water, so `E = K A^m S^n` is spent against the A it was
 * calibrated with. The cost is stated where it is paid: the erosion answers *where* the water runs
 * and not *how much*.
 *
 * See docs/DESIGN_LEDGER.md, R1, S3, chunk 6 and C1.
 */
object Runoff {

    /**
     * Rainfall over potential evaporation below which country is hyper-arid, in UNEP's World
     * Atlas of Desertification (Middleton and Thomas 1992, 1997): the Atacama, the Namib and the
     * core of the Sahara.
     */
    const val HYPER_ARID_ARIDITY_INDEX = 0.05f

    /**
     * Earth's runoff over its land, in millimeters a year: the 40.0e3 km³ its rivers and
     * Antarctica's ice deliver to the sea (Trenberth and others 2007, from Dai and Trenberth 2002)
     * over its 148.9 million km² of land. The reference the absolute form divides by.
     */
    const val EARTH_MEAN_LAND_RUNOFF_MM = 40.0e3f / 148.9e6f * 1.0e6f

    /**
     * A cell's runoff in millimeters a year: [precipitationMm] less what the ground gives back
     * against [potentialMm], by Budyko's curve ([LakeWaterBalance.runoffShareOfRain]). Zero where
     * there is no rain; all of it where nothing can evaporate.
     */
    fun annualRunoffMm(precipitationMm: Float, potentialMm: Float): Float =
        precipitationMm.coerceAtLeast(0f) *
            LakeWaterBalance.runoffShareOfRain(precipitationMm, potentialMm)

    /** [annualRunoffMm] at [cell] of [climate]. */
    fun annualRunoffMm(climate: ClimateResult, cell: Int): Float =
        annualRunoffMm(climate.precipitationMm.data[cell], climate.potentialEvapotranspirationMm.data[cell])

    /** [annualRunoffMm] as a share of Earth's land mean — the absolute form. */
    fun shareOfEarthMean(precipitationMm: Float, potentialMm: Float): Float =
        annualRunoffMm(precipitationMm, potentialMm) / EARTH_MEAN_LAND_RUNOFF_MM
}
