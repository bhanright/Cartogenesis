package com.cartogenesis.worldgen.pipeline

/**
 * Diagnostic switches for measuring the march's water budget one term at a time. Every default
 * leaves the march exactly as it is; a measurement sets one, reruns the climate and sets it back.
 * Not part of the generator.
 */
internal object MoistureProbe {
    /** Multiplies the atmosphere's large-scale ascent where the march turns it into condensation. */
    var ascentScale = 1.0

    /** Multiplies the wind's climb over the ground where the march turns it into condensation. */
    var climbScale = 1.0

    /** Replaces [MoistureMarch.HOLDABLE_SHARE] when set. */
    var holdableShare: Double? = null

    /** False switches off the descent's taking back of cloud and falling rain. */
    var descentDrying = true

    /** Replaces [MoistureMarch.MARINE_RELATIVE_HUMIDITY] in the sea's evaporation when set. */
    var marineRelativeHumidity: Double? = null

    /** Replaces [MoistureMarch.SEA_OVER_MARINE_AIR_C] in the sea's evaporation when set. */
    var seaOverMarineAirC: Double? = null

    /** Multiplies the scalar wind the sea's evaporation reads. */
    var seaWindScale = 1.0

    /** Multiplies the saturated column the column's rain law reads, and only that. */
    var rainSaturatedScale = 1.0

    /**
     * When positive, a crossing longer than this many reference crossings is charged in that many
     * equal steps per reference crossing, at most [MAX_SUBSTEPS], sources and sinks together.
     */
    var substepPerReference = 0.0
    const val MAX_SUBSTEPS = 512

    /** Reads the saturated column up the profile of a subcloud parcel at the marine air's humidity. */
    var subcloudColumn = false

    /** Gates the large-scale ascent's and the climb's condensation by Sundqvist's saturated share over this critical humidity of the holdable column, when set. */
    var criticalHumidity: Double? = null
    var criticalHumiditySea: Double? = null

    /** Warms the open sea's surface and its air, in the march alone, degrees. */
    var seaWarmingC = 0.0

    fun reset() {
        ascentScale = 1.0
        climbScale = 1.0
        holdableShare = null
        descentDrying = true
        marineRelativeHumidity = null
        seaOverMarineAirC = null
        seaWindScale = 1.0
        rainSaturatedScale = 1.0
        substepPerReference = 0.0
        subcloudColumn = false
        criticalHumidity = null
        criticalHumiditySea = null
        seaWarmingC = 0.0
    }
}
