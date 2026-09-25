package com.cartogenesis.worldgen.pipeline

/**
 * How much water a land cell sheds in a year, in millimetres: the one weight every stage that
 * routes water routes with — the erosion rounds, the channel-initiation criterion, the drawn
 * network's discharge in `RiverStage`, and the riverine threshold `NationStage` reads off it.
 *
 * Four stages ask the same question of a cell and used to answer it three ways. `RiverStage` added
 * a floor to a rainfall normalised to 0..1 and clamped at 1,200 mm, `ChannelInitiation` added a
 * hundred and fifty millimetres to a rainfall in millimetres, and `HydraulicErosion` took the larger
 * of the rainfall and a floor; all three meant "an arid upland still feeds the channel that leaves
 * it", and two of them named a different floor for it. There is one figure here now and the stages
 * differ only in what they divide it by, which is the part that genuinely differs.
 *
 * **Rainfall standing in for runoff, and the shortfall is the same in every consumer.** What
 * actually reaches a channel is rainfall less what the plants breathe out and the ground takes in,
 * delivered in floods rather than evenly. None of those three is modelled, here or anywhere
 * downstream of here, so every figure below is an upper bound on the water and a smooth one on a
 * quantity that is not smooth. Stated once, in the place the substitution is made.
 *
 * **The two normalisations, and why each is right for its consumer.** The two stages that compare
 * the weight against a fitted or measured figure do not route [annualWeightMm] raw; each divides it
 * by a mean, and they divide by different ones on purpose. The drawn network and the realm stage
 * route it raw, because everything they do with it is a ratio within the one world — a channel's
 * width against the widest, a threshold as a share of all the land's water.
 *
 * `ChannelInitiation` divides by **Earth's own mean over land**, [EARTH_MEAN_LAND_RAINFALL_MM] —
 * an absolute reference. Its threshold is an area in square kilometres read off Montgomery and
 * Dietrich's field surveys, so the quantity it compares against that threshold has to be a
 * physical area of real ground and not a share of this world's. Against the world's own mean a
 * world twice as wet would draw exactly the same network, because every weight would be divided by
 * twice as much; against Earth's it draws a denser one, which is the answer the criterion exists to
 * give.
 *
 * `HydraulicErosion` divides by **the mean over the land the pass is routing on** — a relative
 * reference, re-taken every pass because the shoreline moves. Its arithmetic is the opposite case:
 * `Rates.incisionCoefficient` was fitted at one world's total water and nothing re-fits it, and the
 * stage turns an accumulation into a share by dividing it by the land's *cell count* in two places
 * at once. A mean of exactly 1 is what keeps that division meaning "this cell's share of the land's
 * water", so `E = K A^m S^n` is spent against the A it was calibrated with. Handed the absolute
 * weight instead, A would be re-scaled by a factor nothing in the stage accounts for.
 *
 * The cost of the relative form is stated where it is paid: the erosion answers *where* the rain
 * falls and not *how much*, so a uniformly wetter world erodes exactly as it did. The channel
 * criterion has no such blind spot, and that difference between the two stages is real rather than
 * an inconsistency to be tidied away.
 *
 * See docs/DESIGN_LEDGER.md, R1, S3 and chunk 6.
 */
object Runoff {

    /**
     * The least water a cell is counted as shedding, in millimetres a year.
     *
     * A floor and not a physical term. Below it a desert range would contribute nothing at all to
     * the water leaving it, and the channel that drains it would have no discharge to cut with or
     * to be initiated by — but a dry upland does gather a trickle from snowmelt and the odd storm,
     * and its channels are cut by the rare storm rather than by the annual mean.
     *
     * Sixty millimetres is where the hyper-arid class ends: UNEP's aridity index, rainfall over
     * potential evaporation, puts the line at [HYPER_ARID_ARIDITY_INDEX], and under a potential
     * evaporation of [ClimateStage.REFERENCE_MM] — the scale the climate normalises its rainfall
     * against, and within the spread of the estimates of Earth's mean over land — that is sixty.
     * So a cell drier than the driest class keeps that class's edge, and nothing wetter is
     * touched. It is also the figure the river stage's own floor stood for since E1, 0.05 of the
     * 1,200 mm scale, so the erosion and the channel criterion, which read it already, do not move.
     */
    const val FLOOR_MM = HYPER_ARID_ARIDITY_INDEX * ClimateStage.REFERENCE_MM

    /**
     * Rainfall over potential evaporation below which country is hyper-arid, in UNEP's World
     * Atlas of Desertification (Middleton and Thomas 1992, 1997): the Atacama, the Namib and the
     * core of the Sahara.
     */
    const val HYPER_ARID_ARIDITY_INDEX = 0.05f

    /**
     * The rainfall the absolute form is measured against, in millimetres a year: Earth's mean over
     * land, about 715 (Legates & Willmott's land mean, quoted at 700-750 in every later census).
     *
     * See the class note for which consumer divides by this and why the other one does not.
     */
    const val EARTH_MEAN_LAND_RAINFALL_MM = 715f

    /** A cell's annual water in millimetres: its rainfall, or [FLOOR_MM] where that is drier. */
    fun annualWeightMm(precipitationMm: Float): Float =
        if (precipitationMm > FLOOR_MM) precipitationMm else FLOOR_MM

    /** [annualWeightMm] as a share of Earth's land mean — the absolute form. */
    fun shareOfEarthMean(precipitationMm: Float): Float =
        annualWeightMm(precipitationMm) / EARTH_MEAN_LAND_RAINFALL_MM
}
